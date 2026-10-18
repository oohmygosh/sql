/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.QueryType;
import org.opensearch.sql.lang.LangSpec;
import org.opensearch.sql.protocol.response.QueryResult;
import org.opensearch.sql.protocol.response.format.CsvResponseFormatter;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Downloads the rows returned by one query; the engine's output-size limit still applies. */
@RestController
public class QueryExportController {
  private final UnifiedQueryService queries;

  public QueryExportController(UnifiedQueryService queries) {
    this.queries = queries;
  }

  @PostMapping(value = "/query/export", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<byte[]> export(@RequestBody ExportRequest request) {
    if (request.query() == null || request.query().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query must not be blank");
    }
    if (request.source() != null && request.source().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source must not be blank");
    }
    QueryType language = request.language;
    QueryResponse response;
    try {
      response = queries.execute(request.source(), language, request.query());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Query execution failed", e);
    }
    if (response.getError() != null) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Query execution failed", response.getError());
    }
    var result =
        new QueryResult(
            response.getSchema(),
            response.getResults(),
            response.getCursor(),
            LangSpec.fromLanguage(language.name()),
            response.getWarnings());
    String csv = new CsvResponseFormatter().format(result);

    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("result.csv").build().toString())
        .body(csv.getBytes(StandardCharsets.UTF_8));
  }

  public record ExportRequest(String source, QueryType language, String query) {}
}
