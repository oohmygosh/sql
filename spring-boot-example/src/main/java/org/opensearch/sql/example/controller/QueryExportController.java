/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.controller;

import java.nio.charset.StandardCharsets;
import org.opensearch.sql.example.model.ExportRequest;
import org.opensearch.sql.example.service.QueryExportService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Streams a completed CSV file produced under the shared export capacity and file limits. */
@RestController
public class QueryExportController {
  private final QueryExportService exports;

  public QueryExportController(QueryExportService exports) {
    this.exports = exports;
  }

  @PostMapping(value = "/search/export", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<InputStreamResource> export(@RequestBody ExportRequest request) {
    var csv = exports.export(request);

    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("result.csv").build().toString())
        .contentLength(csv.size())
        .body(new InputStreamResource(csv.stream()));
  }
}
