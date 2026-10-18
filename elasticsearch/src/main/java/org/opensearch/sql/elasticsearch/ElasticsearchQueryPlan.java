/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.opensearch.sql.ast.tree.HighlightConfig;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;

/** Immutable native query description shared by independently paginated executions. */
public record ElasticsearchQueryPlan(
    List<Field> fields,
    Map<String, Object> query,
    List<Object> sort,
    long offset,
    Long fetch,
    Aggregation aggregation,
    HighlightConfig highlight) {

  public ElasticsearchQueryPlan {
    fields = List.copyOf(fields);
    query = immutableMap(Objects.requireNonNull(query));
    sort = immutableList(Objects.requireNonNull(sort));
    if (offset < 0 || (fetch != null && fetch < 0)) {
      throw new IllegalArgumentException("Offset and fetch must be non-negative");
    }
    if (aggregation != null && (!sort.isEmpty() || offset != 0 || fetch != null)) {
      throw new IllegalArgumentException(
          "Document sorting, offset and fetch cannot be combined with aggregation");
    }
    if (highlight != null) {
      highlight =
          new HighlightConfig(
              highlight.fields() == null ? null : immutableMap(highlight.fields()),
              highlight.preTags() == null ? null : List.copyOf(highlight.preTags()),
              highlight.postTags() == null ? null : List.copyOf(highlight.postTags()),
              highlight.fragmentSize());
    }
  }

  public boolean isAggregate() {
    return aggregation != null;
  }

  public List<ElasticsearchGrouping> groups() {
    return isAggregate() ? aggregation.groups() : List.of();
  }

  public List<ElasticsearchMetric> metrics() {
    return isAggregate() ? aggregation.metrics() : List.of();
  }

  /** Grouping keys and metrics; empty grouping keys describe a global aggregation. */
  public record Aggregation(List<ElasticsearchGrouping> groups, List<ElasticsearchMetric> metrics) {
    public Aggregation {
      groups = List.copyOf(groups);
      metrics = List.copyOf(metrics);
    }
  }

  private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
    Map<K, V> copy = new LinkedHashMap<>();
    values.forEach((key, value) -> copy.put(key, immutableValue(value)));
    return Collections.unmodifiableMap(copy);
  }

  private static <T> List<T> immutableList(List<T> values) {
    List<T> copy = new ArrayList<>(values.size());
    values.forEach(value -> copy.add(immutableValue(value)));
    return Collections.unmodifiableList(copy);
  }

  @SuppressWarnings("unchecked")
  private static <T> T immutableValue(T value) {
    if (value instanceof Map<?, ?> map) return (T) immutableMap(map);
    if (value instanceof List<?> list) return (T) immutableList(list);
    return value;
  }
}
