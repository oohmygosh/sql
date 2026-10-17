/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.backend;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.opensearch.sql.DataSourceSchemaName;
import org.opensearch.sql.backend.QueryBackend;
import org.opensearch.sql.executor.ExecutionEngine;
import org.opensearch.sql.storage.StorageEngine;

/** Exposes the existing OpenSearch storage and executor as one replaceable backend. */
public record OpenSearchQueryBackend(
    String name, StorageEngine storage, ExecutionEngine executionEngine, AutoCloseable owner)
    implements QueryBackend {
  public OpenSearchQueryBackend(String name, StorageEngine storage, ExecutionEngine engine) {
    this(name, storage, engine, null);
  }

  @Override
  public void close() throws Exception {
    if (owner != null) owner.close();
  }

  /** Standalone REST transport, using the existing OpenSearch settings and protection policy. */
  public static OpenSearchQueryBackend connect(
      String name, String url, String username, String password, Map<String, String> properties)
      throws Exception {
    var builder =
        org.opensearch.client.RestClient.builder(org.apache.hc.core5.http.HttpHost.create(url));
    if (username != null) {
      var credentials = new org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider();
      credentials.setCredentials(
          new org.apache.hc.client5.http.auth.AuthScope(null, -1),
          new org.apache.hc.client5.http.auth.UsernamePasswordCredentials(
              username, password == null ? new char[0] : password.toCharArray()));
      builder.setHttpClientConfigCallback(
          client -> client.setDefaultCredentialsProvider(credentials));
    }
    var transport = new org.opensearch.client.RestHighLevelClient(builder);
    try {
      var settingsBuilder = org.opensearch.common.settings.Settings.builder();
      properties.forEach(settingsBuilder::put);
      var registered =
          new java.util.HashSet<>(
              org.opensearch.common.settings.ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
      registered.addAll(org.opensearch.sql.opensearch.setting.OpenSearchSettings.pluginSettings());
      registered.addAll(
          org.opensearch.sql.opensearch.setting.OpenSearchSettings.pluginNonDynamicSettings());
      var settings =
          new org.opensearch.sql.opensearch.setting.OpenSearchSettings(
              new org.opensearch.common.settings.ClusterSettings(
                  settingsBuilder.build(), registered));
      var client = new org.opensearch.sql.opensearch.client.OpenSearchRestClient(transport);
      var storage =
          new org.opensearch.sql.opensearch.storage.OpenSearchStorageEngine(client, settings);
      var monitor =
          new org.opensearch.sql.opensearch.monitor.OpenSearchResourceMonitor(
              settings,
              new org.opensearch.sql.opensearch.monitor.OpenSearchMemoryHealthy(settings));
      var engine =
          new org.opensearch.sql.opensearch.executor.OpenSearchExecutionEngine(
              client,
              new org.opensearch.sql.opensearch.executor.protector.OpenSearchExecutionProtector(
                  monitor),
              new org.opensearch.sql.executor.pagination.PlanSerializer(storage, settings));
      return new OpenSearchQueryBackend(name, storage, engine, transport);
    } catch (Exception error) {
      try {
        transport.close();
      } catch (Exception cleanup) {
        error.addSuppressed(cleanup);
      }
      throw error;
    }
  }

  @Override
  public boolean supportsHighlight() {
    return true;
  }

  @Override
  public String type() {
    return "opensearch";
  }

  @Override
  public Schema createSchema(SchemaPlus parent) {
    return new AbstractSchema() {
      @Override
      protected Map<String, Table> getTableMap() {
        return new AbstractMap<>() {
          @Override
          public Table get(Object key) {
            if (!(key instanceof String table)) return null;
            return (Table) storage.getTable(new DataSourceSchemaName(name, "default"), table);
          }

          @Override
          public boolean containsKey(Object key) {
            return get(key) != null;
          }

          @Override
          public Set<Entry<String, Table>> entrySet() {
            return Set.of();
          }
        };
      }
    };
  }
}
