/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.controller;

import java.util.List;
import org.opensearch.sql.api.completion.CompletionRequest;
import org.opensearch.sql.api.completion.CompletionResponse;
import org.opensearch.sql.api.completion.QueryCompletionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Editor assistance is read-only and never executes the supplied query. */
@RestController
public class QueryCompletionController {
  private final QueryCompletionService completions;

  public QueryCompletionController(QueryCompletionService completions) {
    this.completions = completions;
  }

  @GetMapping(value = "/ui/query/completions", produces = MediaType.APPLICATION_JSON_VALUE)
  public Capabilities capabilities() {
    return new Capabilities(completions.languages());
  }

  @PostMapping(
      value = "/ui/query/completions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public CompletionResponse complete(@RequestBody CompletionRequest request) {
    return completions.complete(request);
  }

  public record Capabilities(List<String> languages) {}
}
