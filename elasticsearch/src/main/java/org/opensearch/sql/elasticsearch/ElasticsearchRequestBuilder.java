/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.opensearch.sql.ast.tree.HighlightConfig;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;

/** Builds request bodies without owning a remote context or pagination state. */
final class ElasticsearchRequestBuilder {
  private static final int PAGE_SIZE = 1000;

  private ElasticsearchRequestBuilder() {}

  static Map<String, Object> search(
      ElasticsearchQueryPlan plan, String pit, Object after, long skipped, long emitted) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("query", plan.query());
    request.put("pit", Map.of("id", pit, "keep_alive", "1m"));
    if (plan.isAggregate()) {
      request.put("size", 0);
      if (plan.groups().isEmpty()) {
        request.put("track_total_hits", true);
        request.put("aggs", aggregateBody(plan));
      } else {
        Map<String, Object> composite = new LinkedHashMap<>();
        composite.put("size", PAGE_SIZE);
        composite.put("sources", groupSources(plan));
        if (after != null) composite.put("after", after);
        request.put(
            "aggs", Map.of("groups", Map.of("composite", composite, "aggs", aggregateBody(plan))));
      }
    } else {
      // Transport pages do not limit the result set. Clamp before adding to avoid overflow
      // when a valid offset and fetch together exceed Long.MAX_VALUE.
      long size = PAGE_SIZE;
      if (plan.fetch() != null) {
        long remaining = Math.min(PAGE_SIZE, plan.fetch() - emitted);
        long remainingOffset = Math.min(PAGE_SIZE, Math.max(0, plan.offset() - skipped));
        size = Math.min(PAGE_SIZE, remaining + remainingOffset);
      }
      request.put("size", size);
      List<Object> ordering = new ArrayList<>(plan.sort());
      ordering.add(Map.of("_shard_doc", "asc"));
      request.put("sort", ordering);
      HighlightConfig highlight = plan.highlight();
      if (highlight != null) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("fields", highlight.fields());
        if (highlight.preTags() != null) options.put("pre_tags", highlight.preTags());
        if (highlight.postTags() != null) options.put("post_tags", highlight.postTags());
        if (highlight.fragmentSize() != null)
          options.put("fragment_size", highlight.fragmentSize());
        request.put("highlight", options);
      }
      if (plan.fields().stream().anyMatch(field -> field.name().equals("_score"))) {
        request.put("track_scores", true);
      }
      request.put(
          "_source", plan.fields().stream().flatMap(Field::sourcePaths).distinct().toList());
      if (after != null) request.put("search_after", after);
    }
    return request;
  }

  static List<Object> groupSources(ElasticsearchQueryPlan plan) {
    List<Object> sources = new ArrayList<>();
    Set<Integer> selected = new HashSet<>();
    if (plan.isAggregate()) {
      for (var collation : plan.aggregation().bucketOrder()) {
        int index = collation.getFieldIndex();
        if (selected.add(index))
          sources.add(Map.of("g" + index, plan.groups().get(index).source(collation)));
      }
    }
    for (int i = 0; i < plan.groups().size(); i++) {
      if (selected.add(i)) sources.add(Map.of("g" + i, plan.groups().get(i).source()));
    }
    return sources;
  }

  static Map<String, Object> aggregateBody(ElasticsearchQueryPlan plan) {
    Map<String, Object> metrics = new LinkedHashMap<>();
    for (int i = 0; i < plan.metrics().size(); i++) {
      metrics.putAll(plan.metrics().get(i).aggregations(i));
    }
    return metrics;
  }
}
