/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.clickhouse;

import com.clickhouse.jdbc.ClickHouseDataSource;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;
import javax.sql.DataSource;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.opensearch.sql.backend.QueryBackend;
import org.opensearch.sql.executor.CalciteExecutionEngine;
import org.opensearch.sql.executor.ExecutionEngine;

/** ClickHouse relational pushdown through Calcite's JDBC convention. */
public final class ClickHouseQueryBackend implements QueryBackend {
  private final String name;
  private final DataSource dataSource;
  private final String database;
  private final ExecutionEngine engine = new CalciteExecutionEngine();

  public ClickHouseQueryBackend(String name, String url, Properties properties, String database)
      throws SQLException {
    this(
        name,
        new ClickHouseDataSource(url, sqlProperties(properties)),
        database != null ? database : databaseFromUrl(url));
  }

  private static String databaseFromUrl(String url) {
    String address = url.substring(url.indexOf(":") + 1);
    address = address.substring(address.indexOf(":") + 1);
    if (address.startsWith("//")) address = "http:" + address;
    String path = java.net.URI.create(address).getPath();
    return path == null || path.equals("/") || path.isEmpty()
        ? "default"
        : path.substring(path.lastIndexOf('/') + 1);
  }

  private static Properties sqlProperties(Properties source) {
    Properties properties = new Properties();
    properties.putAll(source);
    properties.setProperty("clickhouse_setting_join_use_nulls", "1");
    properties.setProperty("clickhouse_setting_join_default_strictness", "ALL");
    properties.setProperty("clickhouse_setting_union_default_mode", "DISTINCT");
    return properties;
  }

  /**
   * Externally supplied DataSources remain owned by their caller. Configure join_use_nulls=1 and
   * ALL strictness.
   */
  public ClickHouseQueryBackend(String name, DataSource dataSource, String database) {
    this.name = Objects.requireNonNull(name);
    this.dataSource = new ClickHouseMetadataDataSource(Objects.requireNonNull(dataSource));
    this.database = database != null ? database : "default";
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String type() {
    return "clickhouse";
  }

  @Override
  public ExecutionEngine executionEngine() {
    return engine;
  }

  @Override
  public String defaultSchema() {
    return database;
  }

  @Override
  public Schema createSchema(SchemaPlus parent) {
    return new org.apache.calcite.schema.impl.AbstractSchema() {
      private final java.util.Map<String, Schema> databases = new java.util.LinkedHashMap<>();

      private JdbcSchema schema(String db) {
        return (JdbcSchema)
            databases.computeIfAbsent(
                db,
                key -> {
                  var expression =
                      org.apache.calcite.schema.Schemas.subSchemaExpression(
                          parent.getSubSchema(name), key, JdbcSchema.class);
                  return new JdbcSchema(
                      dataSource,
                      QueryClickHouseDialect.INSTANCE,
                      new ClickHouseConvention(expression, name + "." + key),
                      key,
                      null);
                });
      }

      @Override
      protected java.util.Map<String, Schema> getSubSchemaMap() {
        return new java.util.AbstractMap<>() {
          @Override
          public Schema get(Object key) {
            return key instanceof String db ? schema(db) : null;
          }

          @Override
          public java.util.Set<Entry<String, Schema>> entrySet() {
            schema(database);
            return databases.entrySet();
          }
        };
      }

      @Override
      protected java.util.Map<String, org.apache.calcite.schema.Table> getTableMap() {
        return new java.util.AbstractMap<>() {
          @Override
          public org.apache.calcite.schema.Table get(Object key) {
            return key instanceof String table ? schema(database).getTable(table) : null;
          }

          @Override
          public java.util.Set<Entry<String, org.apache.calcite.schema.Table>> entrySet() {
            java.util.Map<String, org.apache.calcite.schema.Table> tables =
                new java.util.LinkedHashMap<>();
            schema(database)
                .getTableNames()
                .forEach(table -> tables.put(table, schema(database).getTable(table)));
            return tables.entrySet();
          }
        };
      }
    };
  }
}
