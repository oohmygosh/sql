/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch.planner.rules;

import java.util.List;
import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.opensearch.sql.elasticsearch.ElasticsearchScan;

final class ElasticsearchToEnumerableRule extends ConverterRule {
  static final ElasticsearchToEnumerableRule INSTANCE = new ElasticsearchToEnumerableRule();

  private ElasticsearchToEnumerableRule() {
    super(
        ElasticsearchScan.class,
        Convention.NONE,
        EnumerableConvention.INSTANCE,
        "ElasticsearchToEnumerable");
  }

  @Override
  public RelNode convert(RelNode rel) {
    return rel.copy(rel.getTraitSet().replace(EnumerableConvention.INSTANCE), List.of());
  }
}
