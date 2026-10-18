/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.opensearch.sql.example.config.SearchProperties;
import org.opensearch.sql.ppl.autocomplete.GrammarBundle;
import org.opensearch.sql.ppl.autocomplete.PPLGrammarBundleBuilder;
import org.opensearch.sql.spring.UnifiedQueryProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes UI metadata and runtime grammar; backend connection properties stay on the server. */
@RestController
public class SearchUiController {
  private final UnifiedQueryProperties properties;
  private final Map<String, String> defaultTables;
  private final SearchProperties searches;
  private final Environment environment;

  public SearchUiController(
      UnifiedQueryProperties properties, Environment environment, SearchProperties searches) {
    this.properties = properties;
    this.searches = searches;
    this.environment = environment;
    this.defaultTables =
        Binder.get(environment)
            .bind("unified-query.ui.default-tables", Bindable.mapOf(String.class, String.class))
            .orElseGet(LinkedHashMap::new);
  }

  @GetMapping("/ui/config")
  public UiConfig config() {
    return new UiConfig(
        properties.getDefaultDataSource(),
        searches.maxPageSize(),
        properties.getDataSources().entrySet().stream()
            .map(
                entry ->
                    new UiSource(
                        entry.getKey(),
                        entry.getValue().getType(),
                        defaultTables.getOrDefault(entry.getKey(), ""),
                        searches.timeFields().get(entry.getKey()),
                        searches.defaultTimeZone(),
                        Binder.get(environment)
                            .bind(
                                "unified-query.ui.source-fields." + entry.getKey(),
                                Bindable.listOf(SourceField.class))
                            .orElseGet(List::of)))
            .toList());
  }

  @GetMapping(value = "/ui/ppl/grammar", produces = MediaType.APPLICATION_JSON_VALUE)
  public GrammarBundle pplGrammar() {
    return PPLGrammarBundleBuilder.getBundle();
  }

  public record UiConfig(String defaultSource, int maxPageSize, List<UiSource> sources) {}

  public record UiSource(
      String name,
      String type,
      String table,
      String timeField,
      String timeZone,
      List<SourceField> sourceFields) {}

  public record SourceField(String name, String type) {}
}
