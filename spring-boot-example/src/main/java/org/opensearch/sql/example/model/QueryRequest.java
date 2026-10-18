/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;
import org.opensearch.sql.executor.QueryType;

/** One SQL/PPL query routed to a configured backend. */
public record QueryRequest(String source, QueryType language, String query) {
  public QueryRequest {
    if (query == null || query.isBlank())
      throw new IllegalArgumentException("query must not be blank");
    if (source != null && source.isBlank())
      throw new IllegalArgumentException("source must not be blank");
    language = language == null ? QueryType.SQL : language;
  }

  @JsonCreator
  public static QueryRequest fromJson(
      @JsonProperty("source") String source,
      @JsonProperty("language") String language,
      @JsonProperty("query") String query) {
    return new QueryRequest(
        source,
        language == null ? QueryType.SQL : QueryType.valueOf(language.toUpperCase(Locale.ROOT)),
        query);
  }
}
