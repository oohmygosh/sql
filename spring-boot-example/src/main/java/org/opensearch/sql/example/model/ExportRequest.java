/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;
import org.opensearch.sql.executor.QueryType;

/** Shared JSON request for synchronous downloads and asynchronous export tasks. */
public record ExportRequest(String source, QueryType language, String query) {
  @JsonCreator
  public static ExportRequest fromJson(
      @JsonProperty("source") String source,
      @JsonProperty("language") String language,
      @JsonProperty("query") String query) {
    if (query == null || query.isBlank())
      throw new IllegalArgumentException("query must not be blank");
    if (source != null && source.isBlank())
      throw new IllegalArgumentException("source must not be blank");
    return new ExportRequest(
        source,
        language == null ? QueryType.SQL : QueryType.valueOf(language.toUpperCase(Locale.ROOT)),
        query);
  }
}
