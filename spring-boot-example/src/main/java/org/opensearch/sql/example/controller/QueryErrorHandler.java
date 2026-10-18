/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.controller;

import java.util.Map;
import org.opensearch.sql.common.antlr.SyntaxCheckException;
import org.opensearch.sql.common.error.ErrorReport;
import org.opensearch.sql.exception.SemanticCheckException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Makes the controllers' public error reasons available to the browser without exposing causes. */
@RestControllerAdvice(
    assignableTypes = {
      SearchController.class,
      QueryCompletionController.class,
      QueryExportController.class,
      QueryExportTaskController.class
    })
public class QueryErrorHandler {
  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, String>> malformedRequest() {
    return ResponseEntity.badRequest().body(Map.of("message", "Invalid request body"));
  }

  @ExceptionHandler(RuntimeException.class)
  ResponseEntity<Map<String, String>> handleExecution(RuntimeException error) {
    HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
    String message = "Query execution failed";
    if (error instanceof IllegalArgumentException
        || error instanceof SyntaxCheckException
        || error instanceof SemanticCheckException
        || error instanceof UnsupportedOperationException) {
      status = HttpStatus.BAD_REQUEST;
      message = error.getMessage();
    } else if (error instanceof java.util.concurrent.RejectedExecutionException) {
      status = HttpStatus.TOO_MANY_REQUESTS;
      message = error.getMessage();
    } else if (error instanceof ErrorReport report) {
      status =
          switch (report.getCode()) {
            case SYNTAX_ERROR,
                FIELD_NOT_FOUND,
                AMBIGUOUS_FIELD,
                SEMANTIC_ERROR,
                TYPE_ERROR,
                UNSUPPORTED_OPERATION,
                INDEX_NOT_FOUND ->
                HttpStatus.BAD_REQUEST;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case RESOURCE_LIMIT_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
          };
      if (!status.is5xxServerError()) message = report.getUserFacingMessage();
    }
    return ResponseEntity.status(status)
        .body(Map.of("message", message == null ? "Request failed" : message));
  }

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
