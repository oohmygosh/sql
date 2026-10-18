/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.RelVisitor;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.sql.fun.SqlLibraryOperators;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.DateString;
import org.apache.calcite.util.TimeString;
import org.apache.calcite.util.TimestampString;
import org.opensearch.sql.calcite.utils.OpenSearchTypeFactory;
import org.opensearch.sql.data.model.ExprValueUtils;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.expression.function.PPLFuncImpTable;

/** Typed source constraints and result transformations shared by execution, explain and export. */
final class QueryExecutionPlan {
  private QueryExecutionPlan() {}

  static boolean hasSourceConstraints(QueryExecutionRequest request) {
    return request != null && (request.timeRange() != null || !request.filters().isEmpty());
  }

  /** The AST's single relation fixes which source internal plan copies may read. */
  record SourceTable(List<String> qualifiedName, RelDataType rowType) {
    SourceTable {
      qualifiedName = List.copyOf(qualifiedName);
    }

    boolean matches(TableScan scan) {
      return qualifiedName.equals(scan.getTable().getQualifiedName())
          && rowType.equals(scan.getRowType());
    }
  }

  static SourceTable validateSource(RelNode source, QueryExecutionRequest request) {
    List<TableScan> scans = sourceScans(source, false);
    if (scans.size() != 1)
      throw new IllegalArgumentException("External conditions require exactly one source table");
    TableScan scan = scans.getFirst();
    sourceCondition(scan, request);
    return new SourceTable(scan.getTable().getQualifiedName(), scan.getRowType());
  }

  static RelNode bindSource(RelNode original, QueryExecutionRequest request) {
    return bindSource(original, request, null);
  }

  /** Source metadata was already checked; user operators may eliminate or copy its scan. */
  static RelNode bindValidatedSource(
      RelNode original, QueryExecutionRequest request, SourceTable source) {
    return bindSource(original, request, source);
  }

  private static List<TableScan> sourceScans(RelNode plan, boolean allowInternalBranches) {
    List<TableScan> scans = new ArrayList<>();
    new RelVisitor() {
      @Override
      public void visit(RelNode node, int ordinal, RelNode parent) {
        if (node instanceof TableScan scan) scans.add(scan);
        if (!allowInternalBranches && node.getInputs().size() > 1)
          throw new IllegalArgumentException("External conditions require a single source query");
        node.accept(
            new RexShuttle() {
              @Override
              public RexNode visitSubQuery(RexSubQuery subQuery) {
                throw new IllegalArgumentException("External conditions do not support subqueries");
              }
            });
        super.visit(node, ordinal, parent);
      }
    }.go(plan);
    return scans;
  }

  private static RelNode bindSource(
      RelNode original, QueryExecutionRequest request, SourceTable validated) {
    if (!hasSourceConstraints(request)) return original;
    List<TableScan> scans = sourceScans(original, validated != null);
    if (scans.isEmpty() && validated != null) return original;
    if (validated == null && scans.size() != 1)
      throw new IllegalArgumentException("External conditions require exactly one source table");
    Map<TableScan, RexNode> conditions = new IdentityHashMap<>();
    for (TableScan scan : scans) {
      if (validated != null && !validated.matches(scan))
        throw new IllegalArgumentException(
            "External conditions cannot bind an additional source table");
      conditions.computeIfAbsent(scan, candidate -> sourceCondition(candidate, request));
    }
    return original.accept(
        new RelShuttleImpl() {
          @Override
          public RelNode visit(TableScan scan) {
            return LogicalFilter.create(scan, conditions.get(scan));
          }
        });
  }

  private static RexNode sourceCondition(TableScan scan, QueryExecutionRequest request) {
    RexBuilder rex = scan.getCluster().getRexBuilder();
    List<RexNode> conditions = new ArrayList<>();
    if (request.timeRange() != null) {
      var range = request.timeRange();
      RexNode field = field(scan, range.field());
      requireTimestamp(field, range.field());
      conditions.add(compare(rex, ">=", field, timestamp(rex, range.from())));
      conditions.add(compare(rex, "<", field, timestamp(rex, range.to())));
    }
    for (var filter : request.filters()) {
      RexNode field = field(scan, filter.field());
      RexNode condition;
      switch (filter.operator()) {
        case "is_null" -> condition = rex.makeCall(SqlStdOperatorTable.IS_NULL, field);
        case "is_not_null" -> condition = rex.makeCall(SqlStdOperatorTable.IS_NOT_NULL, field);
        case "in" -> {
          List<RexNode> members = new ArrayList<>();
          for (Object value : (List<?>) filter.value()) {
            if (value == null) throw new IllegalArgumentException("in values cannot contain null");
            members.add(compare(rex, "=", field, literal(rex, field.getType(), value)));
          }
          condition =
              members.isEmpty()
                  ? rex.makeLiteral(false)
                  : org.apache.calcite.rex.RexUtil.composeDisjunction(rex, members);
        }
        case "contains" -> {
          if (OpenSearchTypeFactory.convertRelDataTypeToExprType(field.getType())
                  != ExprCoreType.STRING
              || !(filter.value() instanceof String text))
            throw new IllegalArgumentException("contains requires a string field and string value");
          String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
          condition =
              rex.makeCall(
                  SqlLibraryOperators.ILIKE,
                  field,
                  rex.makeLiteral("%" + escaped + "%"),
                  rex.makeLiteral("\\"));
        }
        default -> {
          String operator =
              switch (filter.operator()) {
                case "eq" -> "=";
                case "ne" -> "!=";
                case "gt" -> ">";
                case "gte" -> ">=";
                case "lt" -> "<";
                case "lte" -> "<=";
                default ->
                    throw new IllegalArgumentException(
                        "Unknown filter operator: " + filter.operator());
              };
          condition = compare(rex, operator, field, literal(rex, field.getType(), filter.value()));
        }
      }
      conditions.add(condition);
    }
    return org.apache.calcite.rex.RexUtil.composeConjunction(rex, conditions);
  }

  static RelNode applyOutput(RelNode original, QueryExecutionRequest request, RelBuilder builder) {
    RelNode plan = original;
    if (request.histogram() != null) {
      var spec = request.histogram();
      HistogramInput input = histogramInput(plan, spec.field());
      RexBuilder rex = plan.getCluster().getRexBuilder();
      requireTimestamp(input.time(), spec.field());
      RexNode bucket =
          rex.makeCall(
              SearchTimeBucketFunction.OPERATOR,
              input.time(),
              rex.makeLiteral(spec.interval()),
              rex.makeLiteral(spec.timeZone().getId()));
      builder.clear();
      builder.push(input.plan()).project(List.of(bucket), List.of("__search_bucket"));
      builder.aggregate(builder.groupKey(builder.field(0)), builder.countStar("__search_count"));
      builder.sort(builder.field(0));
      plan = builder.build();
    }
    if (request.window() != null) {
      var window = request.window();
      RexBuilder rex = plan.getCluster().getRexBuilder();
      // This wrapper is deliberately outside any explicit LIMIT/head, including its offset.
      plan =
          LogicalSort.create(
              plan,
              RelCollations.EMPTY,
              rex.makeExactLiteral(BigDecimal.valueOf(window.offset())),
              rex.makeExactLiteral(BigDecimal.valueOf(window.limit())));
    }
    return plan;
  }

  static boolean hasOrdering(RelNode plan) {
    if (plan instanceof Sort sort && !sort.collation.getFieldCollations().isEmpty()) return true;
    if (plan instanceof Project || plan instanceof Filter || plan instanceof Sort)
      return hasOrdering(plan.getInput(0));
    return false;
  }

  private record HistogramInput(RelNode plan, RexNode time) {}

  /** Carries source time through ordinary projections without exposing the extra result column. */
  private static HistogramInput histogramInput(RelNode plan, String name) {
    RexBuilder rex = plan.getCluster().getRexBuilder();
    if (plan instanceof TableScan scan) return new HistogramInput(plan, field(scan, name));
    // Source metadata was validated before user expressions could fold the input to empty.
    if (plan instanceof Values values && values.getTuples().isEmpty())
      return new HistogramInput(
          plan, rex.makeNullLiteral(rex.getTypeFactory().createSqlType(SqlTypeName.TIMESTAMP, 3)));
    if (plan instanceof Project project) {
      if (RexOver.containsOver(project.getProjects(), null))
        throw new UnsupportedHistogramException();
      HistogramInput child = histogramInput(project.getInput(), name);
      List<RexNode> expressions = new ArrayList<>(project.getProjects());
      List<String> names = new ArrayList<>(project.getRowType().getFieldNames());
      expressions.add(child.time());
      String hidden = "__search_source_time";
      while (names.contains(hidden)) hidden += "_";
      names.add(hidden);
      RelNode augmented =
          LogicalProject.create(child.plan(), project.getHints(), expressions, names);
      return new HistogramInput(augmented, rex.makeInputRef(augmented, expressions.size() - 1));
    }
    if (plan instanceof Filter || plan instanceof Sort) {
      HistogramInput child = histogramInput(plan.getInput(0), name);
      RelNode updated = plan.copy(plan.getTraitSet(), List.of(child.plan()));
      return new HistogramInput(updated, child.time());
    }
    throw new UnsupportedHistogramException();
  }

  private static RexNode field(TableScan scan, String name) {
    RexBuilder rex = scan.getCluster().getRexBuilder();
    var exact = scan.getRowType().getField(name, false, false);
    if (exact != null) return rex.makeInputRef(scan, exact.getIndex());
    String[] parts = name.split("\\.", -1);
    var root = scan.getRowType().getField(parts[0], false, false);
    if (root == null) throw new IllegalArgumentException("Unknown source field: " + name);
    RexNode expression = rex.makeInputRef(scan, root.getIndex());
    for (int i = 1; i < parts.length; i++) {
      if (!expression.getType().isStruct()
          || expression.getType().getField(parts[i], false, false) == null)
        throw new IllegalArgumentException("Unknown source field: " + name);
      expression = rex.makeFieldAccess(expression, parts[i], false);
    }
    return expression;
  }

  private static void requireTimestamp(RexNode field, String name) {
    if (OpenSearchTypeFactory.convertRelDataTypeToExprType(field.getType())
        != ExprCoreType.TIMESTAMP)
      throw new IllegalArgumentException("Time field must have timestamp type: " + name);
  }

  private static RexNode compare(RexBuilder rex, String operator, RexNode left, RexNode right) {
    return PPLFuncImpTable.INSTANCE.resolve(rex, operator, left, right);
  }

  private static RexNode timestamp(RexBuilder rex, Instant value) {
    QueryExecutionRequest.requireMillisecondPrecision(value);
    return rex.makeTimestampLiteral(TimestampString.fromMillisSinceEpoch(value.toEpochMilli()), 3);
  }

  private static RexNode literal(RexBuilder rex, RelDataType type, Object value) {
    var exprType = OpenSearchTypeFactory.convertRelDataTypeToExprType(type);
    if (exprType == ExprCoreType.TIMESTAMP) {
      if (!(value instanceof String || value instanceof Instant)) throw mismatch(type, value);
      try {
        Instant instant =
            value instanceof Instant time
                ? time
                : ExprValueUtils.fromObjectValue(value, ExprCoreType.TIMESTAMP).timestampValue();
        return timestamp(rex, instant);
      } catch (RuntimeException failure) {
        throw new IllegalArgumentException("Invalid timestamp filter value", failure);
      }
    }
    if (exprType == ExprCoreType.DATE || exprType == ExprCoreType.TIME) {
      if (!(value instanceof String)) throw mismatch(type, value);
      try {
        var converted = ExprValueUtils.fromObjectValue(value, exprType);
        if (exprType == ExprCoreType.TIME && converted.timeValue().getNano() % 1_000_000 != 0)
          throw new IllegalArgumentException(
              "Structured time constraints support millisecond precision");
        return exprType == ExprCoreType.DATE
            ? rex.makeDateLiteral(new DateString(converted.dateValue().toString()))
            : rex.makeTimeLiteral(new TimeString(converted.timeValue().toString()), 3);
      } catch (RuntimeException failure) {
        throw new IllegalArgumentException("Invalid temporal filter value", failure);
      }
    }
    if (SqlTypeUtil.isNumeric(type)) {
      if (!(value instanceof Number)) throw mismatch(type, value);
      BigDecimal number;
      try {
        number = new BigDecimal(value.toString());
      } catch (NumberFormatException failure) {
        throw mismatch(type, value);
      }
      try {
        switch (type.getSqlTypeName()) {
          case TINYINT -> number.byteValueExact();
          case SMALLINT -> number.shortValueExact();
          case INTEGER -> number.intValueExact();
          case BIGINT -> number.longValueExact();
          case DECIMAL -> {
            number = number.setScale(type.getScale(), java.math.RoundingMode.UNNECESSARY);
            if (number.precision() > type.getPrecision())
              throw new ArithmeticException("precision");
          }
          default -> {}
        }
      } catch (ArithmeticException failure) {
        throw mismatch(type, value);
      }
      return SqlTypeUtil.isApproximateNumeric(type)
          ? rex.makeApproxLiteral(number, type)
          : rex.makeExactLiteral(number, type);
    }
    if (exprType == ExprCoreType.STRING && value instanceof String text)
      return rex.makeLiteral(text);
    if (exprType == ExprCoreType.BOOLEAN && value instanceof Boolean bool)
      return rex.makeLiteral(bool);
    throw mismatch(type, value);
  }

  private static IllegalArgumentException mismatch(RelDataType type, Object value) {
    return new IllegalArgumentException("Filter value does not match source field type " + type);
  }
}
