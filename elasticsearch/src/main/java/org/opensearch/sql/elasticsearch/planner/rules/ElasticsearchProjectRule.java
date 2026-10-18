/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch.planner.rules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.util.mapping.Mappings;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryPlan;
import org.opensearch.sql.elasticsearch.ElasticsearchScan;
import org.opensearch.sql.elasticsearch.planner.ElasticsearchExpressionResolver;

/** Prunes source reads and retains computed expressions in a residual projection. */
final class ElasticsearchProjectRule extends RelOptRule {
  static final ElasticsearchProjectRule INSTANCE = new ElasticsearchProjectRule();

  private ElasticsearchProjectRule() {
    super(operand(Project.class, operand(ElasticsearchScan.class, none())), "ElasticsearchProject");
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Project project = call.rel(0);
    ElasticsearchScan original = call.rel(1);
    if (original.queryPlan().isAggregate()) return;
    var resolution = ElasticsearchExpressionResolver.resolve(original, project.getProjects());
    ElasticsearchScan scan = resolution.scan();
    List<RexNode> expressions = resolution.expressions();
    var plan = scan.queryPlan();
    if (expressions.stream().allMatch(RexInputRef.class::isInstance)) {
      var fields =
          expressions.stream().map(e -> plan.fields().get(((RexInputRef) e).getIndex())).toList();
      call.transformTo(
          scan.withPlan(
              project.getRowType(),
              scan.getTraitSet().replace(RelCollations.EMPTY),
              new ElasticsearchQueryPlan(
                  fields,
                  plan.query(),
                  plan.sort(),
                  plan.offset(),
                  plan.fetch(),
                  null,
                  plan.highlight())));
      return;
    }
    Set<Integer> references = new LinkedHashSet<>();
    RexVisitorImpl<Void> visitor =
        new RexVisitorImpl<>(true) {
          @Override
          public Void visitInputRef(RexInputRef ref) {
            references.add(ref.getIndex());
            return null;
          }
        };
    expressions.forEach(e -> e.accept(visitor));
    List<Integer> selected = new ArrayList<>(references);
    boolean identity = selected.size() == scan.getRowType().getFieldCount();
    for (int i = 0; identity && i < selected.size(); i++) identity = selected.get(i) == i;
    if (identity && expressions.equals(project.getProjects())) return;
    var mapping = Mappings.target(selected, scan.getRowType().getFieldCount());
    var type = scan.getCluster().getTypeFactory().builder();
    selected.forEach(i -> type.add(scan.getRowType().getFieldList().get(i)));
    var projected =
        scan.withPlan(
            type.build(),
            scan.getTraitSet().replace(RelCollations.EMPTY),
            new ElasticsearchQueryPlan(
                selected.stream().map(plan.fields()::get).toList(),
                plan.query(),
                plan.sort(),
                plan.offset(),
                plan.fetch(),
                null,
                plan.highlight()));
    call.transformTo(
        project.copy(
            project.getTraitSet(),
            projected,
            RexUtil.apply(mapping, expressions),
            project.getRowType()));
  }
}
