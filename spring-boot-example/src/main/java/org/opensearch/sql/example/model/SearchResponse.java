/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import org.opensearch.sql.executor.Warning;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchResponse(
    ResolvedTimeRange resolvedTimeRange,
    Rows rows,
    Page page,
    Histogram histogram,
    List<Warning> warnings) {
  public record ResolvedTimeRange(String field, Instant from, Instant to, String timeZone) {}

  public record Rows(List<QueryColumn> schema, List<Object[]> datarows) {}

  public record Page(int number, int size, boolean hasNext) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Histogram(
      String status, String interval, String timeZone, List<Bucket> buckets, String reason) {}

  public record Bucket(Instant from, Instant to, long count) {}
}
