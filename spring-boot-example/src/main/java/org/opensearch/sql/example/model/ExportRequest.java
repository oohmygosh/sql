/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.opensearch.sql.api.QueryExecutionRequest.FieldFilter;
import org.opensearch.sql.executor.QueryType;

/** Shared JSON request for synchronous downloads and asynchronous export tasks. */
public record ExportRequest(
    String source,
    QueryType language,
    String query,
    SearchRequest.TimeRange timeRange,
    List<FieldFilter> filters) {
  public ExportRequest {
    SearchRequest.validate(source, query);
    language = language == null ? QueryType.SQL : language;
    filters = filters == null ? List.of() : List.copyOf(filters);
  }

  @JsonCreator
  public static ExportRequest fromJson(
      @JsonProperty("source") String source,
      @JsonProperty("language") String language,
      @JsonProperty("query") String query,
      @JsonProperty("timeRange") SearchRequest.TimeRange timeRange,
      @JsonProperty("filters") List<FieldFilter> filters,
      @JsonProperty("page") Object page,
      @JsonProperty("histogram") Object histogram,
      @JsonProperty("include") Object include) {
    if (page != null || histogram != null || include != null)
      throw new IllegalArgumentException("Export does not accept page, histogram or include");
    return new ExportRequest(
        source, SearchRequest.parseLanguage(language), query, timeRange, filters);
  }
}
