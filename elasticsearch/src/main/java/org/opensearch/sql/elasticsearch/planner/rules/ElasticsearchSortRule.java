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
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexUtil;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryPlan;
import org.opensearch.sql.elasticsearch.ElasticsearchScan;

/** Native ordering requires order-equivalence, not merely index availability. */
final class ElasticsearchSortRule extends RelOptRule {
  static final ElasticsearchSortRule INSTANCE = new ElasticsearchSortRule(false);
  static final ElasticsearchSortRule FILTER = new ElasticsearchSortRule(true);
  private final boolean throughFilter;

  private ElasticsearchSortRule(boolean throughFilter) {
    super(
        throughFilter
            ? operand(Sort.class, operand(Filter.class, operand(ElasticsearchScan.class, none())))
            : operand(Sort.class, operand(ElasticsearchScan.class, none())),
        throughFilter ? "ElasticsearchSortFilter" : "ElasticsearchSort");
    this.throughFilter = throughFilter;
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Sort sort = call.rel(0);
    ElasticsearchScan scan = call.rel(throughFilter ? 2 : 1);
    Filter filter = throughFilter ? call.rel(1) : null;
    if (filter != null && !RexUtil.isDeterministic(filter.getCondition())) return;
    var plan = scan.queryPlan();
    if (!scan.pushdownEnabled() || plan.fetch() != null || plan.offset() != 0) return;
    if ((sort.offset != null && !(sort.offset instanceof RexLiteral))
        || (sort.fetch != null && !(sort.fetch instanceof RexLiteral))) return;
    if (plan.isAggregate()) {
      pushDownBucketSort(call, sort, scan, filter);
      return;
    }
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
    if (throughFilter
        && (sort.getCollation().getFieldCollations().isEmpty() || ordering.equals(plan.sort())))
      return;
    RelNode result =
        scan.withPlan(
            sort.getRowType(),
            scan.getTraitSet().replace(sort.getCollation()),
            new ElasticsearchQueryPlan(
                plan.fields(),
                plan.query(),
                ordering,
                throughFilter || sort.offset == null
                    ? 0
                    : ((RexLiteral) sort.offset).getValueAs(Long.class),
                throughFilter || sort.fetch == null
                    ? null
                    : ((RexLiteral) sort.fetch).getValueAs(Long.class),
                null,
                plan.highlight()));
    if (filter != null) {
      result =
          filter.copy(
              filter.getTraitSet().replace(sort.getCollation()), result, filter.getCondition());
      // Only ordering commutes with a filter. Keep OFFSET/FETCH above the residual predicate.
      if (sort.offset != null || sort.fetch != null)
        result =
            sort.copy(sort.getTraitSet(), result, sort.getCollation(), sort.offset, sort.fetch);
    }
    call.transformTo(result);
  }

  private static void pushDownBucketSort(
      RelOptRuleCall call, Sort sort, ElasticsearchScan scan, Filter filter) {
    var plan = scan.queryPlan();
    List<RelFieldCollation> collations = sort.getCollation().getFieldCollations();
    if (collations.isEmpty()) return;
    for (var collation : collations) {
      // Aggregate outputs contain grouping keys followed by metrics, not source field ordinals.
      int index = collation.getFieldIndex();
      if (index >= plan.groups().size()) return;
      var type = scan.getRowType().getFieldList().get(index).getType().getSqlTypeName();
      if (!plan.groups().get(index).supportsOrdering(type)
          || (collation.direction != RelFieldCollation.Direction.ASCENDING
              && collation.direction != RelFieldCollation.Direction.DESCENDING)) return;
    }
    RelNode result =
        scan.withPlan(
            scan.getRowType(),
            scan.getTraitSet().replace(sort.getCollation()),
            new ElasticsearchQueryPlan(
                plan.fields(),
                plan.query(),
                List.of(),
                0,
                null,
                new ElasticsearchQueryPlan.Aggregation(plan.groups(), plan.metrics(), collations),
                plan.highlight()));
    if (filter != null)
      result =
          filter.copy(
              filter.getTraitSet().replace(sort.getCollation()), result, filter.getCondition());
    // Composite page size is not a global limit. Keep OFFSET/FETCH above all returned buckets.
    if (sort.offset != null || sort.fetch != null)
      result =
          sort.copy(
              sort.getTraitSet().replace(RelCollations.EMPTY),
              result,
              RelCollations.EMPTY,
              sort.offset,
              sort.fetch);
    call.transformTo(result);
  }
}
