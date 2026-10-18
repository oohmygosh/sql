/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.clickhouse;

import java.util.ArrayList;
import java.util.Set;
import org.apache.calcite.adapter.jdbc.JdbcConvention;
import org.apache.calcite.adapter.jdbc.JdbcRules;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;

/** Prevents PPL-specific aggregate implementations being sent to ClickHouse by SQL kind alone. */
final class ClickHouseConvention extends JdbcConvention {
  ClickHouseConvention(Expression expression, String name) {
    super(QueryClickHouseDialect.INSTANCE, expression, name);
  }

  @Override
  public void register(RelOptPlanner planner) {
    super.register(planner);
    planner.removeRule(JdbcRules.JdbcAggregateRule.create(this));
    planner.addRule(new AggregateRule(this));
    planner.removeRule(JdbcRules.JdbcProjectRule.create(this));
    planner.removeRule(JdbcRules.JdbcFilterRule.create(this));
    planner.addRule(new ProjectRule(this));
    planner.addRule(new FilterRule(this));
    planner.addRule(new PartialFilterRule(this));
    // Core FilterMerge uses RelBuilder and can turn a native filter plus a local residual back
    // into one logical predicate. Preserve that boundary; merge logical filters only.
    planner.removeRule(org.apache.calcite.rel.rules.CoreRules.FILTER_MERGE);
    planner.addRule(
        org.apache.calcite.rel.rules.FilterMergeRule.Config.DEFAULT
            .withOperandFor(LogicalFilter.class)
            .toRule());
  }

  private static boolean supported(org.apache.calcite.rex.RexNode node) {
    if (node instanceof org.apache.calcite.rex.RexOver) return false;
    if (!(node instanceof org.apache.calcite.rex.RexCall call)) return true;
    return QueryClickHouseDialect.INSTANCE.supportsFunction(
            call.getOperator(),
            call.getType(),
            call.getOperands().stream().map(org.apache.calcite.rex.RexNode::getType).toList())
        && call.getOperands().stream().allMatch(ClickHouseConvention::supported);
  }

  private static final class ProjectRule extends JdbcRules.JdbcProjectRule {
    ProjectRule(JdbcConvention convention) {
      super(
          Config.INSTANCE.withConversion(
              org.apache.calcite.rel.core.Project.class,
              p ->
                  !p.containsOver()
                      && p.getProjects().stream().allMatch(ClickHouseConvention::supported),
              Convention.NONE,
              convention,
              "ClickHouseProjectRule"));
    }
  }

  private static final class FilterRule extends JdbcRules.JdbcFilterRule {
    FilterRule(JdbcConvention convention) {
      super(
          Config.INSTANCE.withConversion(
              org.apache.calcite.rel.core.Filter.class,
              f -> supported(f.getCondition()),
              Convention.NONE,
              convention,
              "ClickHouseFilterRule"));
    }

    @Override
    public RelNode convert(RelNode rel) {
      var filter = (Filter) rel;
      return new RemoteFilter(
          filter.getCluster(),
          filter.getTraitSet().replace(out),
          convert(filter.getInput(), filter.getInput().getTraitSet().replace(out)),
          filter.getCondition());
    }
  }

  /** Remote filtering adds CPU; only the JDBC converter materializes/transfers its output rows. */
  private static final class RemoteFilter extends JdbcRules.JdbcFilter {
    RemoteFilter(
        org.apache.calcite.plan.RelOptCluster cluster,
        org.apache.calcite.plan.RelTraitSet traits,
        RelNode input,
        RexNode condition) {
      super(cluster, traits, input, condition);
    }

    @Override
    public RemoteFilter copy(
        org.apache.calcite.plan.RelTraitSet traits, RelNode input, RexNode condition) {
      return new RemoteFilter(getCluster(), traits, input, condition);
    }

    @Override
    public org.apache.calcite.plan.RelOptCost computeSelfCost(
        RelOptPlanner planner, org.apache.calcite.rel.metadata.RelMetadataQuery metadata) {
      var cost = super.computeSelfCost(planner, metadata);
      // Volcano currently ranks costs by rows alone. Charging an extra intermediate row set
      // here makes two-stage (remote + residual) filtering lose to transferring the whole table.
      return cost == null
          ? null
          : planner
              .getCostFactory()
              .makeCost(0, cost.getCpu() * JdbcConvention.COST_MULTIPLIER, cost.getIo());
    }
  }

  /** Keep unsupported conjuncts local without losing independent, safe remote predicates. */
  private static final class PartialFilterRule extends RelOptRule {
    private final JdbcConvention convention;

    PartialFilterRule(JdbcConvention convention) {
      super(
          operand(Filter.class, Convention.NONE, any()),
          "ClickHousePartialFilterRule(" + convention.getName() + ")");
      this.convention = convention;
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
      Filter filter = call.rel(0);
      var builder = filter.getCluster().getRexBuilder();
      RexNode expanded = RexUtil.expandSearch(builder, null, filter.getCondition());
      var remote = new ArrayList<RexNode>();
      var local = new ArrayList<RexNode>();
      for (RexNode conjunct : RelOptUtil.conjunctions(expanded)) {
        (supported(conjunct) ? remote : local).add(conjunct);
      }
      if (remote.isEmpty() || (local.isEmpty() && expanded.equals(filter.getCondition()))) return;
      RelNode input =
          convert(filter.getInput(), filter.getInput().getTraitSet().replace(convention));
      if (input == null) return;
      RelNode pushed =
          new RemoteFilter(
              filter.getCluster(),
              filter.getTraitSet().replace(convention),
              input,
              RexUtil.composeConjunction(builder, remote));
      call.transformTo(
          local.isEmpty()
              ? pushed
              : LogicalFilter.create(pushed, RexUtil.composeConjunction(builder, local)));
    }
  }

  private static final class AggregateRule extends JdbcRules.JdbcAggregateRule {
    AggregateRule(JdbcConvention convention) {
      super(
          Config.INSTANCE.withConversion(
              Aggregate.class, Convention.NONE, convention, "ClickHouseAggregateRule"));
    }

    @Override
    public RelNode convert(RelNode rel) {
      Aggregate aggregate = (Aggregate) rel;
      // UInt64 is exposed as DECIMAL, but SUM(UInt64) in ClickHouse still wraps at 2^64.
      // Keep exact decimal sums local until the native argument is explicitly widened.
      if (aggregate.getAggCallList().stream()
          .anyMatch(
              call ->
                  call.getAggregation().getKind() == org.apache.calcite.sql.SqlKind.SUM
                      && call.getArgList().stream()
                          .anyMatch(
                              index ->
                                  aggregate
                                          .getInput()
                                          .getRowType()
                                          .getFieldList()
                                          .get(index)
                                          .getType()
                                          .getSqlTypeName()
                                      == org.apache.calcite.sql.type.SqlTypeName.DECIMAL)))
        return null;
      if (aggregate.getAggCallList().stream()
          .anyMatch(
              call ->
                  !Set.of(
                          "COUNT",
                          "SUM",
                          "MIN",
                          "MAX",
                          "AVG",
                          "DISTINCT_COUNT_APPROX",
                          "APPROX_COUNT_DISTINCT")
                      .contains(
                          call.getAggregation().getName().toUpperCase(java.util.Locale.ROOT))))
        return null;
      return super.convert(rel);
    }
  }
}
