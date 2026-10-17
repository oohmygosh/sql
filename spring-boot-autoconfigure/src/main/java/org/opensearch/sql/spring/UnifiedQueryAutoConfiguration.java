/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.spring;

import java.util.Properties;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.backend.QueryBackend;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration
@EnableConfigurationProperties(UnifiedQueryProperties.class)
public class UnifiedQueryAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean({ConfiguredQueryBackends.class, UnifiedQueryService.class})
  ConfiguredQueryBackends configuredQueryBackends(
      UnifiedQueryProperties properties,
      ObjectProvider<QueryBackendFactory> factories,
      ObjectProvider<QueryBackend> backends)
      throws Exception {
    return new ConfiguredQueryBackends(
        properties, factories.orderedStream().toList(), backends.orderedStream().toList());
  }

  @Bean
  @ConditionalOnMissingBean
  UnifiedQueryService unifiedQueryService(
      UnifiedQueryProperties properties, ConfiguredQueryBackends backends) {
    return new UnifiedQueryService(
        backends.all(), properties.getDefaultDataSource(), properties.getSettings());
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend")
  static class ElasticsearchConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "elasticsearchBackendFactory")
    QueryBackendFactory elasticsearchBackendFactory() {
      return new QueryBackendFactory() {
        public String type() {
          return "elasticsearch";
        }

        public QueryBackend create(String name, UnifiedQueryProperties.DataSource config) {
          if (config.getUrl() == null)
            throw new IllegalArgumentException("Missing URL for " + name);
          var builder =
              org.elasticsearch.client.RestClient.builder(
                  org.apache.http.HttpHost.create(config.getUrl()));
          if (config.getUsername() != null) {
            var credentials = new org.apache.http.impl.client.BasicCredentialsProvider();
            credentials.setCredentials(
                org.apache.http.auth.AuthScope.ANY,
                new org.apache.http.auth.UsernamePasswordCredentials(
                    config.getUsername(), config.getPassword()));
            builder.setHttpClientConfigCallback(
                client -> client.setDefaultCredentialsProvider(credentials));
          }
          return new org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend(
              name, builder.build(), true, config.getFieldTypes());
        }
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.opensearch.sql.clickhouse.ClickHouseQueryBackend")
  static class ClickHouseConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "clickHouseBackendFactory")
    QueryBackendFactory clickHouseBackendFactory() {
      return new QueryBackendFactory() {
        public String type() {
          return "clickhouse";
        }

        public QueryBackend create(String name, UnifiedQueryProperties.DataSource config)
            throws Exception {
          if (config.getUrl() == null)
            throw new IllegalArgumentException("Missing URL for " + name);
          Properties properties = new Properties();
          properties.putAll(config.getProperties());
          if (config.getUsername() != null) properties.setProperty("user", config.getUsername());
          if (config.getPassword() != null)
            properties.setProperty("password", config.getPassword());
          return new org.opensearch.sql.clickhouse.ClickHouseQueryBackend(
              name, config.getUrl(), properties, config.getDatabase());
        }
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "org.opensearch.sql.opensearch.backend.OpenSearchQueryBackend",
        "org.opensearch.client.RestHighLevelClient"
      })
  static class OpenSearchConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "openSearchBackendFactory")
    QueryBackendFactory openSearchBackendFactory() {
      return new QueryBackendFactory() {
        public String type() {
          return "opensearch";
        }

        public QueryBackend create(String name, UnifiedQueryProperties.DataSource config)
            throws Exception {
          if (config.getUrl() == null)
            throw new IllegalArgumentException("Missing URL for " + name);
          return org.opensearch.sql.opensearch.backend.OpenSearchQueryBackend.connect(
              name,
              config.getUrl(),
              config.getUsername(),
              config.getPassword(),
              config.getProperties());
        }
      };
    }
  }
}
