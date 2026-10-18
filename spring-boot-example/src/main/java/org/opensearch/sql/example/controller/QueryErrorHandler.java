/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Makes the controllers' public error reasons available to the browser without exposing causes. */
@RestControllerAdvice(
    assignableTypes = {
      QueryController.class,
      QueryExportController.class,
      QueryExportTaskController.class
    })
public class QueryErrorHandler {
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, String>> handle(ResponseStatusException error) {
    String message = error.getReason();
    if (error.getStatusCode().is5xxServerError()) {
      message = "Query execution failed";
    }
    return ResponseEntity.status(error.getStatusCode())
        .body(Map.of("message", message == null ? "Request failed" : message));
  }
}
