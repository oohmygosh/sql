/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.object;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.ElasticsearchTable;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;

/** One execution's pagination state and owned point-in-time context. */
final class ElasticsearchSearchEnumerator implements Enumerator<Object[]> {
  private final ElasticsearchTable source;
  private final ElasticsearchQueryPlan plan;
  private final RelDataType rowType;
  private Iterator<Map<String, Object>> page = Collections.emptyIterator();
  private Object[] current;
  private String pit;
  private Object after;
  private boolean finished;
  private long skipped;
  private long emitted;

  ElasticsearchSearchEnumerator(
      ElasticsearchTable source, ElasticsearchQueryPlan plan, RelDataType rowType) {
    this.source = Objects.requireNonNull(source);
    this.plan = Objects.requireNonNull(plan);
    this.rowType = Objects.requireNonNull(rowType);
  }

  @Override
  public Object[] current() {
    return current;
  }

  @Override
  public boolean moveNext() {
    try {
      if (plan.fetch() != null && emitted >= plan.fetch()) {
        close();
        return false;
      }
      while (true) {
        if (!page.hasNext()) {
          if (finished) {
            close();
            return false;
          }
          loadPage();
          if (!page.hasNext()) {
            close();
            return false;
          }
        }
        Map<String, Object> hit = page.next();
        if (skipped < plan.offset()) {
          skipped++;
          continue;
        }
        current = plan.isAggregate() ? aggregateRow(hit) : documentRow(hit);
        emitted++;
        return true;
      }
    } catch (RuntimeException | Error e) {
      try {
        close();
      } catch (RuntimeException | Error cleanup) {
        e.addSuppressed(cleanup);
      }
      throw e;
    }
  }

  @SuppressWarnings("unchecked")
  private void loadPage() {
    if (pit == null) {
      Object id =
          source
              .backend
              .request(
                  "POST",
                  "/" + ElasticsearchQueryBackend.path(source.index) + "/_pit?keep_alive=1m",
                  null)
              .get("id");
      if (id == null) {
        throw new IllegalStateException("Elasticsearch returned no point-in-time ID");
      }
      pit = id.toString();
    }
    Map<String, Object> response =
        source.backend.request(
            "POST",
            "/_search",
            ElasticsearchRequestBuilder.search(plan, pit, after, skipped, emitted));
    // A response may rotate the PIT ID even when the search is incomplete. Cleanup must
    // release the most recently returned context in both the success and failure paths.
    if (response.get("pit_id") != null) pit = response.get("pit_id").toString();
    if (Boolean.TRUE.equals(response.get("timed_out"))
        || ((Number) object(response.get("_shards")).getOrDefault("failed", 0)).intValue() > 0) {
      throw new IllegalStateException("Elasticsearch returned an incomplete search response");
    }
    List<Map<String, Object>> rows;
    if (!plan.isAggregate()) {
      rows = (List<Map<String, Object>>) object(response.get("hits")).get("hits");
      if (!rows.isEmpty()) {
        after = rows.getLast().get("sort");
        if (!(after instanceof List<?> values) || values.isEmpty()) {
          throw new IllegalStateException("Elasticsearch returned no search-after sort values");
        }
      }
      finished = rows.isEmpty();
    } else if (plan.groups().isEmpty()) {
      Map<String, Object> row = new LinkedHashMap<>(object(response.get("aggregations")));
      row.put("doc_count", object(object(response.get("hits")).get("total")).get("value"));
      rows = List.of(row);
      finished = true;
    } else {
      Map<String, Object> buckets = object(object(response.get("aggregations")).get("groups"));
      rows = (List<Map<String, Object>>) buckets.get("buckets");
      after = buckets.get("after_key");
      finished = after == null || rows.isEmpty();
    }
    page = rows.iterator();
  }

  private Object[] documentRow(Map<String, Object> hit) {
    Object[] values = new Object[plan.fields().size()];
    Map<String, Object> document = object(hit.get("_source"));
    String index = String.valueOf(hit.get("_index"));
    for (int i = 0; i < values.length; i++) {
      Field field = plan.fields().get(i);
      Object value;
      switch (field.name()) {
        case "_id", "_index", "_score" -> value = hit.get(field.name());
        case "_highlight" -> value = object(hit.get("highlight"));
        default -> value = ElasticsearchValueDecoder.sourceValue(document, field, index);
      }
      values[i] =
          ElasticsearchValueDecoder.decode(
              value, rowType.getFieldList().get(i).getType(), field, index);
    }
    return values;
  }

  private Object[] aggregateRow(Map<String, Object> bucket) {
    Object[] values = new Object[plan.groups().size() + plan.metrics().size()];
    for (int i = 0; i < plan.groups().size(); i++) {
      values[i] = plan.groups().get(i).value(object(bucket.get("key")).get("g" + i));
    }
    for (int i = 0; i < plan.metrics().size(); i++) {
      values[plan.groups().size() + i] = plan.metrics().get(i).value(bucket, i);
    }
    for (int i = 0; i < values.length; i++) {
      // Native date aggregation values are epoch milliseconds, independent of _source format.
      values[i] =
          ElasticsearchValueDecoder.decodeAggregate(
              values[i], rowType.getFieldList().get(i).getType());
    }
    return values;
  }

  @Override
  public void reset() {
    throw new UnsupportedOperationException("Create a new Enumerator to repeat the search");
  }

  @Override
  public void close() {
    finished = true;
    page = Collections.emptyIterator();
    current = null;
    after = null;
    if (pit != null) {
      String id = pit;
      pit = null;
      source.backend.request("DELETE", "/_pit", Map.of("id", id));
    }
  }
}
