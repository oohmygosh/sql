/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch.planner.rules;

import java.util.List;
import org.apache.calcite.plan.RelOptRule;

/** The ES scan registers this rule set once with each Calcite planner. */
public final class ElasticsearchRules {
  private ElasticsearchRules() {}

  public static final List<RelOptRule> ALL =
      List.of(
          ElasticsearchToEnumerableRule.INSTANCE,
          ElasticsearchFilterRule.INSTANCE,
          ElasticsearchProjectRule.INSTANCE,
          ElasticsearchSortRule.INSTANCE,
          ElasticsearchAggregateRule.DIRECT,
          ElasticsearchAggregateRule.PROJECT);
}
