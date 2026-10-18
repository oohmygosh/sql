/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.opensearch.sql.spring.UnifiedQueryProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes only display metadata; backend URLs, credentials and properties stay on the server. */
@RestController
public class SearchUiController {
  private final UnifiedQueryProperties properties;
  private final Map<String, String> defaultTables;

  public SearchUiController(UnifiedQueryProperties properties, Environment environment) {
    this.properties = properties;
    this.defaultTables =
        Binder.get(environment)
            .bind("unified-query.ui.default-tables", Bindable.mapOf(String.class, String.class))
            .orElseGet(LinkedHashMap::new);
  }

  @GetMapping("/ui/config")
  public UiConfig config() {
    return new UiConfig(
        properties.getDefaultDataSource(),
        properties.getDataSources().entrySet().stream()
            .map(
                entry ->
                    new UiSource(
                        entry.getKey(),
                        entry.getValue().getType(),
                        defaultTables.getOrDefault(entry.getKey(), "")))
            .toList());
  }

  public record UiConfig(String defaultSource, List<UiSource> sources) {}

  public record UiSource(String name, String type, String table) {}
}
