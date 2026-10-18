/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.config;

import java.util.List;
import java.util.Map;
import org.opensearch.sql.api.completion.CompletionMetadata;
import org.opensearch.sql.api.completion.LanguageCompletionProvider;
import org.opensearch.sql.api.completion.PplCompletionProvider;
import org.opensearch.sql.api.completion.QueryCompletionService;
import org.opensearch.sql.api.completion.QueryMetadataService;
import org.opensearch.sql.spring.ConfiguredQueryBackends;
import org.opensearch.sql.spring.UnifiedQueryProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Reuses the query service's backend connections; adding a language is a provider registration. */
@Configuration(proxyBeanMethods = false)
public class QueryCompletionConfiguration {
  @Bean
  CompletionMetadata completionMetadata(
      ConfiguredQueryBackends backends,
      UnifiedQueryProperties properties,
      Environment environment) {
    Map<String, String> defaults =
        Binder.get(environment)
            .bind("unified-query.ui.default-tables", Bindable.mapOf(String.class, String.class))
            .orElseGet(Map::of);
    return new QueryMetadataService(backends.all(), properties.getDefaultDataSource(), defaults);
  }

  @Bean
  PplCompletionProvider pplCompletionProvider() {
    return new PplCompletionProvider();
  }

  @Bean
  QueryCompletionService queryCompletionService(
      CompletionMetadata metadata, List<LanguageCompletionProvider> providers) {
    return new QueryCompletionService(metadata, providers);
  }
}
