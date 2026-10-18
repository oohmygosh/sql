/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch.planner.rules;

import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.opensearch.sql.elasticsearch.ElasticsearchPredicate;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryPlan;
import org.opensearch.sql.elasticsearch.ElasticsearchScan;
import org.opensearch.sql.elasticsearch.planner.ElasticsearchExpressionResolver;
import org.opensearch.sql.elasticsearch.planner.ElasticsearchFilterPushdown;

final class ElasticsearchFilterRule extends RelOptRule {
  static final ElasticsearchFilterRule INSTANCE = new ElasticsearchFilterRule();

  private ElasticsearchFilterRule() {
    super(operand(Filter.class, operand(ElasticsearchScan.class, none())), "ElasticsearchFilter");
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Filter filter = call.rel(0);
    ElasticsearchScan original = call.rel(1);
    if (original.queryPlan().isAggregate()) return;
    var resolution =
        ElasticsearchExpressionResolver.resolve(original, List.of(filter.getCondition()));
    ElasticsearchScan scan = resolution.scan();
    RexNode condition = resolution.expressions().getFirst();
    var plan = scan.queryPlan();
    var decision =
        ElasticsearchFilterPushdown.analyze(
            condition,
            plan.fields(),
            scan.sourceFields(),
            scan.getCluster().getRexBuilder(),
            scan.pushdownEnabled() && plan.fetch() == null && plan.offset() == 0);
    if (decision.predicates().isEmpty() && condition.equals(filter.getCondition())) return;
    if (!decision.predicates().isEmpty()) {
      var predicates = new ArrayList<>(decision.predicates());
      predicates.add(plan.query());
      scan =
          scan.withPlan(
                  scan.getRowType(),
                  scan.getTraitSet(),
                  new ElasticsearchQueryPlan(
                      plan.fields(),
                      ElasticsearchPredicate.and(predicates),
                      plan.sort(),
                      plan.offset(),
                      plan.fetch(),
                      null,
                      plan.highlight()))
              .withSelectivity(decision.selectivity());
    }
    RelNode result = scan;
    if (decision.residual() != null)
      result = filter.copy(filter.getTraitSet(), scan, decision.residual());
    if (scan.getRowType().getFieldCount() != original.getRowType().getFieldCount()) {
      List<RexNode> visible = new ArrayList<>();
      for (int i = 0; i < original.getRowType().getFieldCount(); i++)
        visible.add(RexInputRef.of(i, scan.getRowType()));
      result =
          call.builder()
              .push(result)
              .project(visible, original.getRowType().getFieldNames(), true)
              .build();
    }
    call.transformTo(result);
  }
}
