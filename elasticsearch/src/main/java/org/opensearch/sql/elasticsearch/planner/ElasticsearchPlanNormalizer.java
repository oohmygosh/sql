/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.Window;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.SourceOrigin;
import org.opensearch.sql.elasticsearch.ElasticsearchScan;

/** Correct object access before the optimizer can choose between local and native execution. */
public final class ElasticsearchPlanNormalizer {
  private ElasticsearchPlanNormalizer() {}

  public static RelNode normalize(RelNode plan) {
    List<RelNode> inputs =
        plan.getInputs().stream().map(ElasticsearchPlanNormalizer::normalize).toList();
    RelNode node = inputs.equals(plan.getInputs()) ? plan : plan.copy(plan.getTraitSet(), inputs);
    RelNode normalized =
        node.accept(
            new RexShuttle() {
              @Override
              public RexNode visitCall(RexCall original) {
                RexCall call = (RexCall) super.visitCall(original);
                String key = itemKey(call);
                if (key == null || !key.contains(".")) return call;
                if (!objectExpression(node.getInputs(), call.getOperands().getFirst())) return call;
                SourceColumn descendant = expressionSource(node.getInputs(), call);
                if (descendant != null && descendant.field().origin() == SourceOrigin.FLAT)
                  return call;
                RexNode value = call.getOperands().getFirst();
                // This is limited to known source-object lineage. A computed spath/JSON map may
                // deliberately contain a literal dotted key and retains normal ITEM semantics.
                for (String segment : key.split("\\.", -1)) {
                  value =
                      call.clone(
                          call.getType(),
                          List.of(value, node.getCluster().getRexBuilder().makeLiteral(segment)));
                }
                return value;
              }
            });
    return normalized instanceof Aggregate aggregate ? normalizeExtrema(aggregate) : normalized;
  }

  /**
   * MIN/MAX cannot execute on Calcite's Object/ANY accumulator. Specialize only arguments with
   * known scalar source lineage, before the optimizer can retain an untyped alternative.
   */
  private static RelNode normalizeExtrema(Aggregate aggregate) {
    RelNode input = aggregate.getInput();
    var builder = aggregate.getCluster().getRexBuilder();
    var types = aggregate.getCluster().getTypeFactory();
    List<RexNode> expressions = new ArrayList<>();
    List<String> names = new ArrayList<>(input.getRowType().getFieldNames());
    for (int i = 0; i < names.size(); i++) expressions.add(RexInputRef.of(i, input.getRowType()));
    Map<Integer, Integer> typedArguments = new LinkedHashMap<>();
    for (AggregateCall call : aggregate.getAggCallList()) {
      if ((call.getAggregation().getKind() != SqlKind.MIN
              && call.getAggregation().getKind() != SqlKind.MAX)
          || call.getArgList().size() != 1) continue;
      int argument = call.getArgList().getFirst();
      if (typedArguments.containsKey(argument)
          || input.getRowType().getFieldList().get(argument).getType().getSqlTypeName()
              != SqlTypeName.ANY) continue;
      SourceColumn source = columnSource(input, argument);
      if (source == null || !source.field().hasObjectSourcePath()) continue;
      var type = source.field().type(types);
      // A mapping with conflicting runtime types, an object, or an array does not establish
      // an ordered scalar contract. Date source formats also require mapping-aware decoding.
      if (!SqlTypeName.NUMERIC_TYPES.contains(type.getSqlTypeName())
          && !SqlTypeName.CHAR_TYPES.contains(type.getSqlTypeName())) continue;
      typedArguments.put(argument, expressions.size());
      expressions.add(
          builder.makeCast(
              types.createTypeWithNullability(type, true),
              RexInputRef.of(argument, input.getRowType())));
      names.add("$typed_extremum_" + argument);
    }
    if (typedArguments.isEmpty()) return aggregate;
    Materialization materialized = materializeExtremumInput(input, expressions);
    for (int ordinal : typedArguments.values()) {
      RexNode expression = materialized.expressions().get(ordinal);
      if (!(expression instanceof RexCall cast) || cast.getKind() != SqlKind.CAST) return aggregate;
      RexInputRef decoded = ElasticsearchExpressionResolver.inputRef(cast.getOperands().getFirst());
      // Do not apply SQL casts to raw ITEM values. A typed scan column guarantees the same
      // mapping-aware numeric/string decoding as a direct field read, even for coerced strings.
      if (decoded == null || decoded.getType().getSqlTypeName() == SqlTypeName.ANY)
        return aggregate;
    }
    RelNode typedInput =
        LogicalProject.create(materialized.input(), List.of(), materialized.expressions(), names);
    List<AggregateCall> calls = new ArrayList<>();
    for (AggregateCall call : aggregate.getAggCallList()) {
      Integer argument =
          call.getArgList().size() == 1
                  && (call.getAggregation().getKind() == SqlKind.MIN
                      || call.getAggregation().getKind() == SqlKind.MAX)
              ? typedArguments.get(call.getArgList().getFirst())
              : null;
      calls.add(
          argument == null
              ? call
              : call.adaptTo(
                  typedInput,
                  List.of(argument),
                  call.filterArg,
                  aggregate.getGroupCount(),
                  aggregate.getGroupCount()));
    }
    RelNode typedAggregate =
        aggregate.copy(
            aggregate.getTraitSet(),
            typedInput,
            aggregate.getGroupSet(),
            aggregate.getGroupSets(),
            calls);
    List<RexNode> output = new ArrayList<>();
    for (int i = 0; i < aggregate.getRowType().getFieldCount(); i++) {
      output.add(
          builder.ensureType(
              aggregate.getRowType().getFieldList().get(i).getType(),
              RexInputRef.of(i, typedAggregate.getRowType()),
              true));
    }
    // Existing parents still reference the original row type, names and ordinals. Only the
    // accumulator and its private input column are specialized; the boundary retains that API.
    return LogicalProject.create(typedAggregate, List.of(), output, aggregate.getRowType());
  }

  private record Materialization(RelNode input, List<RexNode> expressions) {}

  private static Materialization materializeExtremumInput(
      RelNode input, List<RexNode> expressions) {
    // Compose deterministic projections to expose the original source paths. Volatile computed
    // expressions must not be duplicated while adding a private aggregate argument.
    while (input instanceof Project project
        && project.getProjects().stream().allMatch(RexUtil::isDeterministic)) {
      expressions = RelOptUtil.pushPastProject(expressions, project);
      input = project.getInput();
    }
    return materializeThroughUnary(input, expressions);
  }

  private static Materialization materializeThroughUnary(RelNode input, List<RexNode> expressions) {
    if (input instanceof ElasticsearchScan scan) {
      var resolution = ElasticsearchExpressionResolver.resolve(scan, expressions);
      return new Materialization(resolution.scan(), resolution.expressions());
    }
    if (input instanceof Filter || input instanceof Sort) {
      Materialization child = materializeThroughUnary(input.getInput(0), expressions);
      return new Materialization(
          input.copy(input.getTraitSet(), List.of(child.input())), child.expressions());
    }
    return new Materialization(input, expressions);
  }

  private record SourceColumn(Field field, List<Field> schema) {}

  /** Shape can survive CASE even when a single physical field identity no longer exists. */
  private static boolean objectExpression(List<RelNode> inputs, RexNode expression) {
    if (expression instanceof RexLiteral literal && literal.isNull()) return true;
    RexInputRef ref = ElasticsearchExpressionResolver.inputRef(expression);
    if (ref != null) {
      int index = ref.getIndex();
      for (RelNode input : inputs) {
        if (index < input.getRowType().getFieldCount()) return objectColumn(input, index);
        index -= input.getRowType().getFieldCount();
      }
      return false;
    }
    if (expression instanceof RexCall call && call.getKind() == SqlKind.CASE) {
      List<RexNode> args = call.getOperands();
      for (int i = 1; i < args.size() - 1; i += 2)
        if (!objectExpression(inputs, args.get(i))) return false;
      return objectExpression(inputs, args.getLast());
    }
    return isObject(expressionSource(inputs, expression));
  }

  private static boolean objectColumn(RelNode node, int index) {
    if (node instanceof Project project)
      return objectExpression(project.getInputs(), project.getProjects().get(index));
    if (node instanceof Filter || node instanceof Sort)
      return objectColumn(node.getInput(0), index);
    if (node instanceof Window && index < node.getInput(0).getRowType().getFieldCount())
      return objectColumn(node.getInput(0), index);
    if (node instanceof Aggregate aggregate && index < aggregate.getGroupCount())
      return objectColumn(aggregate.getInput(), aggregate.getGroupSet().nth(index));
    if (node instanceof Join join) {
      int leftCount = join.getLeft().getRowType().getFieldCount();
      return index < leftCount
          ? objectColumn(join.getLeft(), index)
          : objectColumn(join.getRight(), index - leftCount);
    }
    return isObject(columnSource(node, index));
  }

  private static boolean isObject(SourceColumn source) {
    return source != null
        && source.field().hasObjectSourcePath()
        && List.of("object", "any").contains(source.field().mappingType());
  }

  private static SourceColumn expressionSource(List<RelNode> inputs, RexNode expression) {
    RexInputRef ref = ElasticsearchExpressionResolver.inputRef(expression);
    if (ref != null) {
      int index = ref.getIndex();
      for (RelNode input : inputs) {
        if (index < input.getRowType().getFieldCount()) return columnSource(input, index);
        index -= input.getRowType().getFieldCount();
      }
      return null;
    }
    if (!(expression instanceof RexCall call)) return null;
    String key = itemKey(call);
    if (key == null) return null;
    SourceColumn parent = expressionSource(inputs, call.getOperands().getFirst());
    if (parent == null || !parent.field().hasObjectSourcePath()) return null;
    String name = parent.field().name() + "." + key;
    for (Field field : parent.schema()) {
      if (field.name().equals(name) && field.source().equals(parent.field().source() + "." + key))
        return new SourceColumn(field, parent.schema());
    }
    return null;
  }

  private static SourceColumn columnSource(RelNode node, int index) {
    if (node instanceof ElasticsearchScan scan && !scan.queryPlan().isAggregate())
      return new SourceColumn(scan.queryPlan().fields().get(index), scan.sourceFields());
    if (node instanceof Project project)
      return expressionSource(project.getInputs(), project.getProjects().get(index));
    if (node instanceof Filter || node instanceof Sort)
      return columnSource(node.getInput(0), index);
    if (node instanceof Window && index < node.getInput(0).getRowType().getFieldCount())
      return columnSource(node.getInput(0), index);
    if (node instanceof Aggregate aggregate && index < aggregate.getGroupCount())
      return columnSource(aggregate.getInput(), aggregate.getGroupSet().nth(index));
    if (node instanceof Join join) {
      int leftCount = join.getLeft().getRowType().getFieldCount();
      return index < leftCount
          ? columnSource(join.getLeft(), index)
          : columnSource(join.getRight(), index - leftCount);
    }
    return null;
  }

  private static String itemKey(RexCall call) {
    if (call.getKind() != SqlKind.ITEM
        || call.getOperands().size() != 2
        || !(call.getOperands().get(1) instanceof RexLiteral literal)
        || literal.isNull()
        || !SqlTypeName.CHAR_TYPES.contains(literal.getType().getSqlTypeName())) return null;
    return literal.getValueAs(String.class);
  }
}
