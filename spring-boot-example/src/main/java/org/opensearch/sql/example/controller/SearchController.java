/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.controller;

import org.opensearch.sql.example.model.SearchRequest;
import org.opensearch.sql.example.model.SearchResponse;
import org.opensearch.sql.example.service.SearchService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** JSON query endpoint for both Elasticsearch and ClickHouse, using SQL or PPL. */
@RestController
public class SearchController {
  private final SearchService queries;

  public SearchController(SearchService queries) {
    this.queries = queries;
  }

  @PostMapping(
      value = "/search",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public SearchResponse search(@RequestBody SearchRequest request) {
    return queries.execute(request);
  }
}
