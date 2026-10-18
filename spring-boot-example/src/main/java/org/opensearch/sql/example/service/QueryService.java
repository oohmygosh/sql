/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.service;

import java.util.Locale;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.example.model.QueryColumn;
import org.opensearch.sql.example.model.QueryRequest;
import org.opensearch.sql.example.model.QueryResponse;
import org.opensearch.sql.lang.LangSpec;
import org.springframework.stereotype.Service;

/** Executes bounded interactive queries and converts engine values to the public JSON model. */
@Service
public class QueryService {
  private final UnifiedQueryService queries;

  public QueryService(UnifiedQueryService queries) {
    this.queries = queries;
  }

  public QueryResponse execute(QueryRequest request) {
    var response = queries.execute(request.source(), request.language(), request.query());
    if (response.getError() != null) {
      // An error-bearing response is never published as a successful partial result.
      throw new IllegalStateException("Query execution failed", response.getError());
    }
    var language = LangSpec.fromLanguage(request.language().name());
    var columns = response.getSchema().getColumns();
    var schema =
        columns.stream()
            .map(
                column ->
                    new QueryColumn(
                        column.getAlias() == null ? column.getName() : column.getAlias(),
                        language.typeName(column.getExprType()).toLowerCase(Locale.ROOT)))
            .toList();
    var rows =
        response.getResults().stream()
            .map(
                row ->
                    columns.stream()
                        .map(column -> row.keyValue(column.getName()).value())
                        .toArray())
            .toList();
    return new QueryResponse(schema, rows, rows.size(), rows.size(), response.getWarnings());
  }
}
