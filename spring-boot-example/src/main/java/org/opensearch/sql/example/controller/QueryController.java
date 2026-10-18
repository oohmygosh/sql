/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.controller;

import org.opensearch.sql.common.antlr.SyntaxCheckException;
import org.opensearch.sql.common.error.ErrorReport;
import org.opensearch.sql.example.model.QueryRequest;
import org.opensearch.sql.example.model.QueryResponse;
import org.opensearch.sql.example.service.QueryService;
import org.opensearch.sql.exception.SemanticCheckException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** JSON query endpoint for both Elasticsearch and ClickHouse, using SQL or PPL. */
@RestController
public class QueryController {
  private final QueryService queries;

  public QueryController(QueryService queries) {
    this.queries = queries;
  }

  @PostMapping(
      value = "/query",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public QueryResponse query(@RequestBody QueryRequest request) {
    try {
      return queries.execute(request);
    } catch (IllegalArgumentException
        | SyntaxCheckException
        | SemanticCheckException
        | UnsupportedOperationException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    } catch (ErrorReport e) {
      HttpStatus status =
          switch (e.getCode()) {
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
      throw new ResponseStatusException(
          status,
          status.is5xxServerError() ? "Query execution failed" : e.getUserFacingMessage(),
          e);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Query execution failed", e);
    }
  }
}
