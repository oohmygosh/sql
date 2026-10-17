/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.clickhouse;

import java.util.Set;
import org.apache.calcite.adapter.jdbc.JdbcConvention;
import org.apache.calcite.adapter.jdbc.JdbcRules;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;

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
  }

  private static boolean supported(org.apache.calcite.rex.RexNode node) {
    if (node instanceof org.apache.calcite.rex.RexOver) return false;
    if (!(node instanceof org.apache.calcite.rex.RexCall call)) return true;
    return QueryClickHouseDialect.INSTANCE.supportsFunction(call.getOperator(),call.getType(),
        call.getOperands().stream().map(org.apache.calcite.rex.RexNode::getType).toList())
        && call.getOperands().stream().allMatch(ClickHouseConvention::supported);
  }
  private static final class ProjectRule extends JdbcRules.JdbcProjectRule {
    ProjectRule(JdbcConvention convention) {
      super(Config.INSTANCE.withConversion(org.apache.calcite.rel.core.Project.class,
          p -> !p.containsOver() && p.getProjects().stream().allMatch(ClickHouseConvention::supported),
          Convention.NONE,convention,"ClickHouseProjectRule"));
    }
  }
  private static final class FilterRule extends JdbcRules.JdbcFilterRule {
    FilterRule(JdbcConvention convention) {
      super(Config.INSTANCE.withConversion(org.apache.calcite.rel.core.Filter.class,
          f -> supported(f.getCondition()),Convention.NONE,convention,"ClickHouseFilterRule"));
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
