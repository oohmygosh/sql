/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Locale;
import org.opensearch.sql.api.QueryExecutionRequest.FieldFilter;
import org.opensearch.sql.executor.QueryType;

/** HTTP search policy; the execution API receives resolved constraints only. */
public record SearchRequest(
    String source,
    QueryType language,
    String query,
    TimeRange timeRange,
    List<FieldFilter> filters,
    Page page,
    Histogram histogram,
    List<String> include) {
  public SearchRequest {
    validate(source, query);
    language = language == null ? QueryType.SQL : language;
    filters = filters == null ? List.of() : List.copyOf(filters);
    include = include == null ? List.of("rows") : List.copyOf(include);
    if (include.isEmpty()
        || include.stream().anyMatch(part -> !List.of("rows", "histogram").contains(part)))
      throw new IllegalArgumentException("include must contain rows and/or histogram");
  }

  @JsonCreator
  public static SearchRequest fromJson(
      @JsonProperty("source") String source,
      @JsonProperty("language") String language,
      @JsonProperty("query") String query,
      @JsonProperty("timeRange") TimeRange timeRange,
      @JsonProperty("filters") List<FieldFilter> filters,
      @JsonProperty("page") Page page,
      @JsonProperty("histogram") Histogram histogram,
      @JsonProperty("include") List<String> include) {
    return new SearchRequest(
        source, parseLanguage(language), query, timeRange, filters, page, histogram, include);
  }

  public static QueryType parseLanguage(String language) {
    return language == null ? QueryType.SQL : QueryType.valueOf(language.toUpperCase(Locale.ROOT));
  }

  public static void validate(String source, String query) {
    if (query == null || query.isBlank())
      throw new IllegalArgumentException("query must not be blank");
    if (source != null && source.isBlank())
      throw new IllegalArgumentException("source must not be blank");
  }

  public record TimeRange(String field, String from, String to, String timeZone) {}

  public record Page(Integer number, Integer size) {}

  public record Histogram(String interval) {}
}
