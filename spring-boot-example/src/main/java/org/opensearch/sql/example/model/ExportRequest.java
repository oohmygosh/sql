/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.opensearch.sql.executor.QueryType;

/** Shared JSON request for synchronous downloads and asynchronous export tasks. */
public record ExportRequest(String source, QueryType language, String query) {
  @JsonCreator
  public static ExportRequest fromJson(
      @JsonProperty("source") String source,
      @JsonProperty("language") String language,
      @JsonProperty("query") String query) {
    QueryRequest request = QueryRequest.fromJson(source, language, query);
    return new ExportRequest(request.source(), request.language(), request.query());
  }
}
