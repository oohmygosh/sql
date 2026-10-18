/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.opensearch.sql.elasticsearch.ElasticsearchPredicate;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;

/**
 * A native necessary condition and its mandatory local remainder, preserving SQL null semantics.
 */
public record ElasticsearchFilterPushdown(
    List<Map<String, Object>> predicates,
    RexNode residual,
    List<String> reasons,
    double selectivity) {
  public ElasticsearchFilterPushdown {
    predicates = List.copyOf(predicates);
    reasons = List.copyOf(reasons);
  }

  public static ElasticsearchFilterPushdown analyze(
      RexNode condition,
      List<Field> fields,
      List<Field> sourceFields,
      RexBuilder builder,
      boolean enabled) {
    List<Map<String, Object>> predicates = new ArrayList<>();
    List<RexNode> remaining = new ArrayList<>();
    List<String> reasons = new ArrayList<>();
    List<RexNode> pushed = new ArrayList<>();
    for (RexNode part : RelOptUtil.conjunctions(condition)) {
      Map<String, Object> nativeQuery =
          enabled
              ? ElasticsearchPredicate.translate(
                  RexUtil.expandSearch(builder, null, part), fields, sourceFields)
              : null;
      if (nativeQuery != null) {
        predicates.add(nativeQuery);
        pushed.add(part);
      } else {
        remaining.add(part);
        reasons.add((enabled ? "SOURCE_SEMANTICS_OR_EXPRESSION: " : "PUSHDOWN_DISABLED: ") + part);
      }
    }
    return new ElasticsearchFilterPushdown(
        predicates,
        remaining.isEmpty() ? null : RexUtil.composeConjunction(builder, remaining),
        reasons,
        RelMdUtil.guessSelectivity(RexUtil.composeConjunction(builder, pushed)));
  }
}
