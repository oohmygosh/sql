/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.controller;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import org.opensearch.sql.example.model.ExportDownload;
import org.opensearch.sql.example.model.ExportRequest;
import org.opensearch.sql.example.model.ExportSubmission;
import org.opensearch.sql.example.model.ExportTaskStatus;
import org.opensearch.sql.example.service.QueryExportService;
import org.opensearch.sql.job.Principal;
import org.opensearch.sql.job.QueryJobId;
import org.opensearch.sql.job.exceptions.QueryJobForbiddenException;
import org.opensearch.sql.job.exceptions.QueryJobNotFoundException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/query/export/tasks")
public class QueryExportTaskController {
  private final QueryExportService exports;

  public QueryExportTaskController(QueryExportService exports) {
    this.exports = exports;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ExportSubmission> submit(
      @RequestBody ExportRequest request, java.security.Principal principal) {
    String id =
        exports.submit(request.source(), request.language(), request.query(), owner(principal));
    return ResponseEntity.accepted()
        .location(URI.create("/query/export/tasks/" + id))
        .body(new ExportSubmission(id));
  }

  @GetMapping("/{id}")
  public ExportTaskStatus status(@PathVariable("id") String id, java.security.Principal principal) {
    return exports.status(QueryJobId.parse(id), owner(principal));
  }

  @GetMapping("/{id}/file")
  public ResponseEntity<InputStreamResource> download(
      @PathVariable("id") String id, java.security.Principal principal) throws IOException {
    ExportDownload download;
    try {
      download = exports.download(QueryJobId.parse(id), owner(principal));
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
    } catch (java.nio.file.NoSuchFileException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Export expired", e);
    }
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .contentLength(download.size())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("result.csv").build().toString())
        .body(new InputStreamResource(download.stream()));
  }

  private static Principal owner(java.security.Principal principal) {
    return principal == null
        ? Principal.UNSECURED
        : new Principal(principal.getName(), null, List.of());
  }

  @ExceptionHandler(QueryJobNotFoundException.class)
  ResponseEntity<Map<String, String>> notFound(QueryJobNotFoundException error) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", error.getMessage()));
  }

  @ExceptionHandler(QueryJobForbiddenException.class)
  ResponseEntity<Map<String, String>> forbidden(QueryJobForbiddenException error) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", error.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
    return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
  }

  @ExceptionHandler(RejectedExecutionException.class)
  ResponseEntity<Map<String, String>> busy(RejectedExecutionException error) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, "1")
        .body(Map.of("error", error.getMessage()));
  }
}
