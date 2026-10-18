/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch.planner.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rex.RexLiteral;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryPlan;
import org.opensearch.sql.elasticsearch.ElasticsearchScan;

/** Native ordering requires order-equivalence, not merely index availability. */
final class ElasticsearchSortRule extends RelOptRule {
  static final ElasticsearchSortRule INSTANCE = new ElasticsearchSortRule();

  private ElasticsearchSortRule() {
    super(operand(Sort.class, operand(ElasticsearchScan.class, none())), "ElasticsearchSort");
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Sort sort = call.rel(0);
    ElasticsearchScan scan = call.rel(1);
    var plan = scan.queryPlan();
    if (!scan.pushdownEnabled() || plan.isAggregate() || plan.fetch() != null || plan.offset() != 0)
      return;
    if ((sort.offset != null && !(sort.offset instanceof RexLiteral))
        || (sort.fetch != null && !(sort.fetch instanceof RexLiteral))) return;
    List<Object> ordering = new ArrayList<>();
    for (var collation : sort.getCollation().getFieldCollations()) {
      Field field = plan.fields().get(collation.getFieldIndex());
      if (!field.supportsOrdering() || field.name().equals("_id")) return;
      Map<String, Object> options = new LinkedHashMap<>();
      options.put("order", collation.direction.isDescending() ? "desc" : "asc");
      if (!field.name().equals("_score")) {
        options.put(
            "missing",
            collation.nullDirection == RelFieldCollation.NullDirection.FIRST ? "_first" : "_last");
        // A missing document value and an index with no mapping are different ES cases.
        if (!field.name().startsWith("_")) options.put("unmapped_type", field.mappingType());
      }
      ordering.add(Map.of(field.name(), options));
    }
    if (ordering.isEmpty()) ordering = plan.sort();
    call.transformTo(
        scan.withPlan(
            sort.getRowType(),
            scan.getTraitSet().replace(sort.getCollation()),
            new ElasticsearchQueryPlan(
                plan.fields(),
                plan.query(),
                ordering,
                sort.offset == null ? 0 : ((RexLiteral) sort.offset).getValueAs(Long.class),
                sort.fetch == null ? null : ((RexLiteral) sort.fetch).getValueAs(Long.class),
                null,
                plan.highlight())));
  }
}
