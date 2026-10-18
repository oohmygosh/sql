/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.object;
import static org.opensearch.sql.elasticsearch.planner.ElasticsearchExpressionResolver.inputRef;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;

/** A metric whose field access, null behavior and numeric semantics can run in Elasticsearch. */
public final class ElasticsearchMetric {
  private static final Set<String> STATISTICS =
      Set.of("VAR_SAMP", "VAR_POP", "STDDEV_SAMP", "STDDEV_POP");
  private static final Map<String, Object> ALL = Map.of("match_all", Map.of());
  private static final Map<String, Object> NONE = Map.of("match_none", Map.of());

  private final String name;
  private final Field field;
  private final Map<String, Object> filter;
  private final Double percentile;
  private final Double compression;

  private ElasticsearchMetric(
      String name, Field field, Map<String, Object> filter, Double percentile, Double compression) {
    this.name = name;
    this.field = field;
    this.filter = filter;
    this.percentile = percentile;
    this.compression = compression;
  }

  public static ElasticsearchMetric translate(
      AggregateCall aggregate, List<RexNode> inputs, List<Field> fields, RexBuilder rexBuilder) {
    String name = aggregate.getAggregation().getName().toUpperCase(Locale.ROOT);
    if (aggregate.distinctKeys != null
        || !aggregate.collation.getFieldCollations().isEmpty()
        || (aggregate.isDistinct() && !(name.equals("COUNT") && aggregate.isApproximate()))) {
      return null;
    }
    if (name.equals("COUNT") && aggregate.isDistinct()) name = "APPROX_COUNT_DISTINCT";
    if (!Set.of(
            "COUNT",
            "SUM",
            "$SUM0",
            "SUM0",
            "MIN",
            "MAX",
            "AVG",
            "DISTINCT_COUNT_APPROX",
            "APPROX_COUNT_DISTINCT",
            "VAR_SAMP",
            "VAR_POP",
            "STDDEV_SAMP",
            "STDDEV_POP",
            "PERCENTILE_APPROX")
        .contains(name)) return null;

    Map<String, Object> filter = null;
    if (aggregate.filterArg >= 0) {
      filter = predicate(inputs.get(aggregate.filterArg), fields, rexBuilder);
      if (filter == null) return null;
    }
    List<RexNode> arguments = new ArrayList<>();
    for (int index : aggregate.getArgList()) arguments.add(inputs.get(index));
    if (!aggregate.rexList.isEmpty()) {
      if (!name.equals("PERCENTILE_APPROX") || arguments.size() != 1) return null;
      arguments.addAll(aggregate.rexList);
    }
    if (arguments.isEmpty()) {
      return name.equals("COUNT") ? new ElasticsearchMetric(name, null, filter, null, null) : null;
    }
    if (name.equals("COUNT") && arguments.size() == 1 && inputRef(arguments.getFirst()) == null) {
      Map<String, Object> nonNull = nonNull(arguments.getFirst(), fields, rexBuilder);
      if (nonNull == null) return null;
      return new ElasticsearchMetric(name, null, and(filter, nonNull), null, null);
    }
    RexInputRef ref = inputRef(arguments.getFirst());
    if (ref == null) return null;
    Field field = fields.get(ref.getIndex());
    if (!(name.equals("COUNT") ? field.supportsExistence() : field.supportsComparison())
        || Set.of("_id", "_score").contains(field.name())) return null;
    if (name.equals("PERCENTILE_APPROX")) return percentile(arguments, field, filter);
    if (arguments.size() != 1) return null;
    if (Set.of("COUNT", "APPROX_COUNT_DISTINCT", "DISTINCT_COUNT_APPROX").contains(name)) {
      return new ElasticsearchMetric(name, field, filter, null, null);
    }

    SqlTypeName type = field.sqlType();
    // Native numeric aggregations accumulate doubles. Keep exact large-integer and decimal
    // arithmetic in Calcite, including PPL's separate CHECKED_LONG_SUM implementation.
    if (type == SqlTypeName.BIGINT || type == SqlTypeName.DECIMAL) return null;
    boolean extremum = name.equals("MIN") || name.equals("MAX");
    if (!SqlTypeName.NUMERIC_TYPES.contains(type) && !(extremum && type == SqlTypeName.TIMESTAMP))
      return null;
    // Even small integral inputs can sum beyond 2^53; accepting them would silently round.
    if (Set.of("SUM", "SUM0", "$SUM0").contains(name) && SqlTypeName.INT_TYPES.contains(type)) {
      return null;
    }
    return new ElasticsearchMetric(name, field, filter, null, null);
  }

  private static ElasticsearchMetric percentile(
      List<RexNode> arguments, Field field, Map<String, Object> filter) {
    if (!SqlTypeName.NUMERIC_TYPES.contains(field.sqlType())
        || field.sqlType() == SqlTypeName.BIGINT
        || field.sqlType() == SqlTypeName.DECIMAL) return null;
    List<RexNode> options = new ArrayList<>(arguments.subList(1, arguments.size()));
    // PPL lowers percentile/median to (field, percentile [, compression], SQL-type flag).
    if (!options.isEmpty()
        && options.getLast() instanceof RexLiteral flag
        && flag.getType().getSqlTypeName() == SqlTypeName.SYMBOL) {
      if (flag.getValue() != field.sqlType()) return null;
      options.removeLast();
    }
    if (options.isEmpty() || options.size() > 2) return null;
    BigDecimal percent = number(options.getFirst());
    BigDecimal compression = options.size() == 2 ? number(options.get(1)) : BigDecimal.valueOf(100);
    if (percent == null
        || compression == null
        || percent.signum() < 0
        || percent.compareTo(BigDecimal.valueOf(100)) > 0
        || compression.signum() <= 0
        || !Double.isFinite(compression.doubleValue())) return null;
    // The current PPL evaluator truncates percentile via Number.intValue(). Keep fractional
    // percentiles local until that behavior is changed consistently across execution backends.
    if (percent.stripTrailingZeros().scale() > 0) return null;
    return new ElasticsearchMetric(
        "PERCENTILE_APPROX", field, filter, percent.doubleValue(), compression.doubleValue());
  }

  private static BigDecimal number(RexNode expression) {
    if (!(expression instanceof RexLiteral literal)
        || literal.isNull()
        || !SqlTypeName.NUMERIC_TYPES.contains(literal.getType().getSqlTypeName())) return null;
    return literal.getValueAs(BigDecimal.class);
  }

  Map<String, Object> aggregations(int ordinal) {
    Map<String, Object> metrics = new LinkedHashMap<>();
    if (field != null) {
      String operation =
          switch (name) {
            case "COUNT" -> "value_count";
            case "APPROX_COUNT_DISTINCT", "DISTINCT_COUNT_APPROX" -> "cardinality";
            case "SUM", "SUM0", "$SUM0" -> "sum";
            case "VAR_SAMP", "VAR_POP", "STDDEV_SAMP", "STDDEV_POP" -> "extended_stats";
            case "PERCENTILE_APPROX" -> "percentiles";
            default -> name.toLowerCase(Locale.ROOT);
          };
      Map<String, Object> options = new LinkedHashMap<>();
      options.put("field", field.name());
      if (percentile != null) {
        options.put("percents", List.of(percentile));
        options.put("tdigest", Map.of("compression", compression));
      }
      metrics.put("a" + ordinal, Map.of(operation, options));
      if (name.equals("SUM")) {
        metrics.put("n" + ordinal, Map.of("value_count", Map.of("field", field.name())));
      }
    }
    if (filter == null || filter.equals(ALL)) return metrics;
    Map<String, Object> filtered = new LinkedHashMap<>();
    filtered.put("filter", filter);
    if (!metrics.isEmpty()) filtered.put("aggs", metrics);
    return Map.of("f" + ordinal, filtered);
  }

  Object value(Map<String, Object> bucket, int ordinal) {
    if (filter != null && !filter.equals(ALL)) bucket = object(bucket.get("f" + ordinal));
    if (field == null) return bucket.get("doc_count");
    Map<String, Object> metric = object(bucket.get("a" + ordinal));
    if (name.equals("SUM")
        && ((Number) object(bucket.get("n" + ordinal)).getOrDefault("value", 0)).longValue() == 0) {
      return null;
    }
    if (STATISTICS.contains(name)) {
      long count = ((Number) metric.get("count")).longValue();
      boolean sample = name.endsWith("_SAMP");
      if (count == 0 || (sample && count == 1)) return null;
      String key = name.startsWith("VAR_") ? "variance" : "std_deviation";
      String variant = key + (sample ? "_sampling" : "_population");
      Object value = metric.get(variant);
      if (value != null) return finite(value);
      Object population = metric.get(key);
      if (!sample || population == null) return finite(population);
      double correction = (double) count / (count - 1);
      return finite(
          ((Number) population).doubleValue()
              * (name.startsWith("VAR_") ? correction : Math.sqrt(correction)));
    }
    if (percentile != null) return finite(object(metric.get("values")).get(percentile.toString()));
    return metric.get("value");
  }

  private static Object finite(Object value) {
    if (value instanceof Number number) return Double.isFinite(number.doubleValue()) ? value : null;
    return null;
  }

  private static Map<String, Object> predicate(
      RexNode expression, List<Field> fields, RexBuilder rexBuilder) {
    return ElasticsearchPredicate.translate(
        RexUtil.expandSearch(rexBuilder, null, expression), fields);
  }

  /** Matches rows on which COUNT's argument is non-null, including CASE's ordered branches. */
  private static Map<String, Object> nonNull(
      RexNode expression, List<Field> fields, RexBuilder rexBuilder) {
    if (expression instanceof RexLiteral literal) return literal.isNull() ? NONE : ALL;
    RexInputRef ref = inputRef(expression);
    if (ref != null) {
      Field field = fields.get(ref.getIndex());
      return field.supportsExistence() && !Set.of("_id", "_score").contains(field.name())
          ? Map.of("exists", Map.of("field", field.name()))
          : null;
    }
    if (!(expression instanceof RexCall call) || call.getKind() != SqlKind.CASE) return null;
    List<RexNode> operands = call.getOperands();
    List<Map<String, Object>> branches = new ArrayList<>();
    Map<String, Object> remaining = ALL;
    for (int i = 0; i < operands.size() - 1; i += 2) {
      Map<String, Object> condition = predicate(operands.get(i), fields, rexBuilder);
      Map<String, Object> result = nonNull(operands.get(i + 1), fields, rexBuilder);
      if (condition == null || result == null) return null;
      branches.add(and(remaining, and(condition, result)));
      // CASE continues when a condition is FALSE *or UNKNOWN*. Complementing the query's
      // TRUE set preserves that behavior, unlike applying SQL NOT to the Rex expression.
      remaining = and(remaining, Map.of("bool", Map.of("must_not", condition)));
    }
    Map<String, Object> otherwise = nonNull(operands.getLast(), fields, rexBuilder);
    if (otherwise == null) return null;
    branches.add(and(remaining, otherwise));
    branches.removeIf(NONE::equals);
    if (branches.isEmpty()) return NONE;
    if (branches.contains(ALL)) return ALL;
    return branches.size() == 1
        ? branches.getFirst()
        : Map.of("bool", Map.of("should", branches, "minimum_should_match", 1));
  }

  private static Map<String, Object> and(Map<String, Object> left, Map<String, Object> right) {
    if (left == null || left.equals(ALL)) return right;
    if (right.equals(ALL)) return left;
    if (left.equals(NONE) || right.equals(NONE)) return NONE;
    return ElasticsearchPredicate.and(List.of(left, right));
  }
}
