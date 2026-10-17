/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.opensearch.sql.backend.QueryBackend;
import org.opensearch.sql.executor.CalciteExecutionEngine;
import org.opensearch.sql.executor.ExecutionEngine;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Elasticsearch adapter with typed schemas and native search/aggregation execution. */
public final class ElasticsearchQueryBackend implements QueryBackend {
  private final String name;
  private final RestClient client;
  private final boolean owned;
  private final Map<String, String> fieldTypes;
  private final ExecutionEngine engine = new CalciteExecutionEngine();
  private final ObjectMapper mapper = new ObjectMapper();

  /** An externally supplied client remains owned by the caller. */
  public ElasticsearchQueryBackend(String name, RestClient client) {
    this(name, client, false);
  }

  public ElasticsearchQueryBackend(String name, RestClient client, boolean owned) {
    this(name, client, owned, Map.of());
  }

  /**
   * Mapping does not distinguish scalar fields from arrays; optional declarations preserve that
   * distinction.
   */
  public ElasticsearchQueryBackend(
      String name, RestClient client, boolean owned, Map<String, String> fieldTypes) {
    this.fieldTypes = Map.copyOf(fieldTypes);
    this.name = Objects.requireNonNull(name);
    this.client = Objects.requireNonNull(client);
    this.owned = owned;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean supportsHighlight() {
    return true;
  }

  @Override
  public String type() {
    return "elasticsearch";
  }

  @Override
  public ExecutionEngine executionEngine() {
    return engine;
  }

  @Override
  public void close() throws IOException {
    if (owned) client.close();
  }

  @Override
  public Schema createSchema(SchemaPlus parent) {
    return new AbstractSchema() {
      @Override
      protected Map<String, Table> getTableMap() {
        return new AbstractMap<>() {
          private final Map<String, Table> loaded = new LinkedHashMap<>();

          @Override
          public Table get(Object key) {
            if (!(key instanceof String index)) return null;
            return loaded.computeIfAbsent(index, ElasticsearchQueryBackend.this::table);
          }

          @Override
          public boolean containsKey(Object key) {
            return get(key) != null;
          }

          @Override
          public Set<Entry<String, Table>> entrySet() {
            request("GET", "/_mapping", null).keySet().forEach(this::get);
            return loaded.entrySet();
          }
        };
      }
    };
  }

  private Table table(String index) {
    Map<String, Object> mappings;
    try {
      mappings = request("GET", "/" + path(index) + "/_mapping", null);
    } catch (BackendException e) {
      if (e.getCause() instanceof ResponseException response
          && response.getResponse().getStatusLine().getStatusCode() == 404) return null;
      throw e;
    }
    Map<String, Field> fields = new LinkedHashMap<>();
    // Collect routing separately to avoid copying all prior index paths on every field merge.
    Map<String, Map<String, String>> sources = new LinkedHashMap<>();
    mappings.forEach(
        (name, mapping) ->
            collectFields(
                name,
                "",
                "",
                object(object(mapping).get("mappings")).get("properties"),
                fields,
                sources));
    fields.replaceAll(
        (fieldName, field) -> {
          Map<String, String> paths = sources.get(fieldName);
          if (paths.values().stream().allMatch(field.source()::equals)) return field;
          return new Field(
              field.name(), field.source(), field.mappingType(), field.sourceCompatible(), paths);
        });
    fieldTypes.forEach(
        (fieldName, type) -> {
          Field original = fields.get(fieldName);
          if (original != null)
            fields.put(
                fieldName,
                new Field(
                    original.name(),
                    original.source(),
                    type,
                    original.sourceCompatible(),
                    original.sources()));
        });
    fields.put("_id", new Field("_id", "_id", "keyword"));
    fields.put("_index", new Field("_index", "_index", "keyword"));
    fields.put("_score", new Field("_score", "_score", "double"));
    return new ElasticsearchTable(this, index, List.copyOf(fields.values()));
  }

  private static void collectFields(
      String index,
      String prefix,
      String sourcePrefix,
      Object properties,
      Map<String, Field> fields,
      Map<String, Map<String, String>> sources) {
    object(properties)
        .forEach(
            (name, value) -> {
              Map<String, Object> mapping = object(value);
              String fieldName = prefix + name;
              String sourceName = sourcePrefix + name;
              String type = String.valueOf(mapping.getOrDefault("type", "object"));
              Field field =
                  new Field(
                      fieldName,
                      sourceName,
                      type,
                      java.util.Collections.disjoint(
                          mapping.keySet(),
                          Set.of("normalizer", "null_value", "ignore_above", "ignore_malformed")));
              fields.merge(fieldName, field, Field::merge);
              sources
                  .computeIfAbsent(fieldName, key -> new LinkedHashMap<>())
                  .put(index, sourceName);
              if (mapping.containsKey("properties") && !type.equals("nested")) {
                collectFields(
                    index,
                    fieldName + ".",
                    sourceName + ".",
                    mapping.get("properties"),
                    fields,
                    sources);
              }
              object(mapping.get("fields"))
                  .forEach(
                      (subName, subValue) -> {
                        Field sub =
                            new Field(
                                fieldName + "." + subName,
                                sourceName,
                                String.valueOf(object(subValue).get("type")),
                                java.util.Collections.disjoint(
                                    object(subValue).keySet(),
                                    Set.of(
                                        "normalizer",
                                        "null_value",
                                        "ignore_above",
                                        "ignore_malformed")));
                        fields.merge(sub.name(), sub, Field::merge);
                        sources
                            .computeIfAbsent(sub.name(), key -> new LinkedHashMap<>())
                            .put(index, sourceName);
                      });
            });
  }

  Map<String, Object> request(String method, String endpoint, Map<String, Object> body) {
    try {
      Request request = new Request(method, endpoint);
      if (body != null) request.setJsonEntity(mapper.writeValueAsString(body));
      String json = EntityUtils.toString(client.performRequest(request).getEntity());
      return mapper.readValue(json, new TypeReference<>() {
      });
    } catch (IOException e) {
      throw new BackendException("Elasticsearch request failed: " + method + " " + endpoint, e);
    }
  }

  static String path(String name) {
    return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : Map.of();
  }

  record Field(
      String name,
      String source,
      String mappingType,
      boolean sourceCompatible,
      Map<String, String> sources) {
    Field {
      sources = Map.copyOf(sources);
    }

    Field(String name, String source, String mappingType) {
      this(name, source, mappingType, true);
    }

    Field(String name, String source, String mappingType, boolean sourceCompatible) {
      this(name, source, mappingType, sourceCompatible, Map.of());
    }

    Field merge(Field other) {
      Map<String, String> mergedSources = new LinkedHashMap<>(sources);
      mergedSources.putAll(other.sources);
      boolean sameSource = source.equals(other.source);
      return new Field(
          name,
          sameSource ? source : name,
          mergedMappingType(other),
          sourceCompatible
              && other.sourceCompatible
              && sameSource
              && mappingType.equals(other.mappingType),
          mergedSources);
    }

    private String mergedMappingType(Field other) {
      if (mappingType.equals(other.mappingType)) return mappingType;
      List<String> integers = List.of("byte", "short", "integer", "long", "unsigned_long");
      int left = integers.indexOf(mappingType), right = integers.indexOf(other.mappingType);
      if (left >= 0 && right >= 0) return integers.get(Math.max(left, right));
      Set<String> floating = Set.of("half_float", "float", "double", "scaled_float");
      if (floating.contains(mappingType) && floating.contains(other.mappingType)) return "double";
      // A heterogeneous field has no single static type. Preserve its source values rather than
      // coercing them to the first index's mapping, or losing large integers by promoting to
      // double.
      return "any";
    }

    String source(String index) {
      return sources.getOrDefault(index, source);
    }

    java.util.stream.Stream<String> sourcePaths() {
      return sources.isEmpty() ? java.util.stream.Stream.of(source) : sources.values().stream();
    }

    RelDataType type(RelDataTypeFactory factory) {
      if (mappingType.startsWith("array<") && mappingType.endsWith(">")) {
        Field element = new Field(name, source, mappingType.substring(6, mappingType.length() - 1));
        return factory.createArrayType(
            factory.createTypeWithNullability(element.type(factory), true), -1);
      }
      if (mappingType.equals("nested"))
        return factory.createArrayType(factory.createSqlType(SqlTypeName.ANY), -1);
      return sqlType() == SqlTypeName.DECIMAL
          ? factory.createSqlType(SqlTypeName.DECIMAL, 20, 0)
          : factory.createSqlType(sqlType());
    }

    SqlTypeName sqlType() {
      return switch (mappingType) {
        case "byte" -> SqlTypeName.TINYINT;
        case "short" -> SqlTypeName.SMALLINT;
        case "integer" -> SqlTypeName.INTEGER;
        case "long" -> SqlTypeName.BIGINT;
        case "unsigned_long" -> SqlTypeName.DECIMAL;
        case "float", "half_float", "double", "scaled_float" -> SqlTypeName.DOUBLE;
        case "boolean" -> SqlTypeName.BOOLEAN;
        case "date", "date_nanos" -> SqlTypeName.TIMESTAMP;
        case "object", "nested", "flattened", "any" -> SqlTypeName.ANY;
        default -> SqlTypeName.VARCHAR;
      };
    }

    boolean exact() {
      return sourceCompatible
          && !mappingType.startsWith("array<")
          && !Set.of("text", "object", "nested", "flattened", "any").contains(mappingType);
    }
  }

  static final class ElasticsearchTable extends AbstractTable implements TranslatableTable {
    final ElasticsearchQueryBackend backend;
    final String index;
    final List<Field> fields;

    ElasticsearchTable(ElasticsearchQueryBackend backend, String index, List<Field> fields) {
      this.backend = backend;
      this.index = index;
      this.fields = fields;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory factory) {
      RelDataTypeFactory.Builder builder = factory.builder();
      for (Field field : fields) {
        RelDataType type = field.type(factory);
        builder.add(field.name(), factory.createTypeWithNullability(type, true));
      }
      return builder.build();
    }

    @Override
    public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable table) {
      return ElasticsearchScan.create(context.getCluster(), table, this);
    }
  }

  public static final class BackendException extends RuntimeException {
    BackendException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
