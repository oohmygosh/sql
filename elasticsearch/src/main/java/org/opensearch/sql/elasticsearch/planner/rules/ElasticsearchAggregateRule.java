/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch.planner.rules;

import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.opensearch.sql.elasticsearch.ElasticsearchGrouping;
import org.opensearch.sql.elasticsearch.ElasticsearchMetric;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryPlan;
import org.opensearch.sql.elasticsearch.ElasticsearchScan;

/** Direct aggregation and aggregation through an ordinary projection share one semantic check. */
final class ElasticsearchAggregateRule extends RelOptRule {
  static final ElasticsearchAggregateRule DIRECT = new ElasticsearchAggregateRule(false);
  static final ElasticsearchAggregateRule PROJECT = new ElasticsearchAggregateRule(true);
  private final boolean project;

  private ElasticsearchAggregateRule(boolean project) {
    super(
        project
            ? operand(
                Aggregate.class, operand(Project.class, operand(ElasticsearchScan.class, none())))
            : operand(Aggregate.class, operand(ElasticsearchScan.class, none())),
        project ? "ElasticsearchAggregateProject" : "ElasticsearchAggregate");
    this.project = project;
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Aggregate aggregate = call.rel(0);
    ElasticsearchScan scan = call.rel(project ? 2 : 1);
    var plan = scan.queryPlan();
    if (!scan.pushdownEnabled()
        || plan.isAggregate()
        || plan.fetch() != null
        || plan.offset() != 0
        || !Aggregate.isSimple(aggregate)) return;
    List<RexNode> inputs = new ArrayList<>();
    if (project) inputs.addAll(((Project) call.rel(1)).getProjects());
    else {
      for (int i = 0; i < scan.getRowType().getFieldCount(); i++)
        inputs.add(RexInputRef.of(i, scan.getRowType()));
    }
    List<ElasticsearchGrouping> groups = new ArrayList<>();
    for (int index : aggregate.getGroupSet()) {
      var group = ElasticsearchGrouping.translate(inputs.get(index), plan.fields());
      if (group == null) return;
      groups.add(group);
    }
    List<ElasticsearchMetric> metrics = new ArrayList<>();
    for (var callExpression : aggregate.getAggCallList()) {
      var metric =
          ElasticsearchMetric.translate(
              callExpression, inputs, plan.fields(), scan.getCluster().getRexBuilder());
      if (metric == null) return;
      metrics.add(metric);
    }
    call.transformTo(
        scan.withPlan(
            aggregate.getRowType(),
            scan.getTraitSet().replace(RelCollations.EMPTY),
            new ElasticsearchQueryPlan(
                plan.fields(),
                plan.query(),
                List.of(),
                0,
                null,
                new ElasticsearchQueryPlan.Aggregation(groups, metrics),
                plan.highlight())));
  }
}
