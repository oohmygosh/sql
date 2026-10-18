/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.controller;

import java.nio.charset.StandardCharsets;
import org.opensearch.sql.example.model.ExportRequest;
import org.opensearch.sql.example.service.QueryExportService;
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
  private final QueryExportService exports;

  public QueryExportController(QueryExportService exports) {
    this.exports = exports;
  }

  @PostMapping(value = "/query/export", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<byte[]> export(@RequestBody ExportRequest request) {
    if (request.query() == null || request.query().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query must not be blank");
    }
    if (request.source() != null && request.source().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source must not be blank");
    }
    byte[] csv;
    try {
      csv = exports.export(request);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Query execution failed", e);
    }

    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("result.csv").build().toString())
        .body(csv);
  }
}
