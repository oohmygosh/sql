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
import java.util.LinkedHashSet;
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
import org.opensearch.sql.backend.MachineLearningProvider;
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
  private final boolean pushdownEnabled;
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
    this(name, client, owned, fieldTypes, true);
  }

  /** Disable native computation while retaining source-path normalization and decoding. */
  public ElasticsearchQueryBackend(
      String name,
      RestClient client,
      boolean owned,
      Map<String, String> fieldTypes,
      boolean pushdownEnabled) {
    this.fieldTypes = Map.copyOf(fieldTypes);
    this.name = Objects.requireNonNull(name);
    this.client = Objects.requireNonNull(client);
    this.owned = owned;
    this.pushdownEnabled = pushdownEnabled;
  }

  public boolean pushdownEnabled() {
    return pushdownEnabled;
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
  public RelNode normalizePlan(RelNode plan) {
    return org.opensearch.sql.elasticsearch.planner.ElasticsearchPlanNormalizer.normalize(plan);
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
  public MachineLearningProvider machineLearning() {
    return new ElasticsearchMlProvider(this);
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
    Map<String, Field> fields = mappingFields(mappings);
    fieldTypes.forEach(
        (fieldName, type) -> {
          Field original = fields.get(fieldName);
          if (original != null) fields.put(fieldName, original.withMappingType(type));
        });
    fields.put("_id", new Field("_id", "_id", "keyword"));
    fields.put("_index", new Field("_index", "_index", "keyword"));
    fields.put("_score", new Field("_score", "_score", "double"));
    return new ElasticsearchTable(this, index, List.copyOf(fields.values()));
  }

  /** Collect mapping semantics without performing any requests. */
  static Map<String, Field> mappingFields(Map<String, Object> mappings) {
    Map<String, Field> fields = new LinkedHashMap<>();
    // Retain routing separately so wildcard merges do not repeatedly copy growing index maps.
    Map<String, Map<String, String>> sources = new LinkedHashMap<>();
    Map<String, Map<String, String>> formats = new LinkedHashMap<>();
    Map<String, Map<String, SourceOrigin>> origins = new LinkedHashMap<>();
    mappings.forEach(
        (index, value) -> {
          Map<String, Object> mapping = object(object(value).get("mappings"));
          Set<String> copyTargets = new LinkedHashSet<>();
          collectCopyTargets(mapping.get("properties"), copyTargets);
          Map<String, Field> indexFields = new LinkedHashMap<>();
          collectFields(
              "",
              "",
              mapping.get("properties"),
              SourceOrigin.ROOT,
              Boolean.FALSE.equals(mapping.get("subobjects")),
              Boolean.FALSE.equals(mapping.get("enabled")) ? "object mapping is disabled" : null,
              copyTargets,
              indexFields);
          indexFields.forEach(
              (name, field) -> {
                fields.merge(name, field, Field::merge);
                sources
                    .computeIfAbsent(name, key -> new LinkedHashMap<>())
                    .put(index, field.source());
                formats
                    .computeIfAbsent(name, key -> new LinkedHashMap<>())
                    .put(index, field.dateFormat());
                origins
                    .computeIfAbsent(name, key -> new LinkedHashMap<>())
                    .put(index, field.origin());
              });
        });
    fields.replaceAll(
        (name, field) ->
            new Field(
                field.name(),
                field.source(),
                field.mappingType(),
                field.sourceCompatible(),
                sources.get(name),
                field.dateFormat(),
                formats.get(name),
                field.origin(),
                origins.get(name),
                field.incompatibilityReason()));
    return fields;
  }

  private static void collectCopyTargets(Object properties, Set<String> targets) {
    object(properties)
        .values()
        .forEach(
            value -> {
              Map<String, Object> mapping = object(value);
              Object copy = mapping.get("copy_to");
              if (copy instanceof String target) targets.add(target);
              else if (copy instanceof List<?> list)
                list.forEach(target -> targets.add(String.valueOf(target)));
              collectCopyTargets(mapping.get("properties"), targets);
              collectCopyTargets(mapping.get("fields"), targets);
            });
  }

  private static void collectFields(
      String prefix,
      String sourcePrefix,
      Object properties,
      SourceOrigin parentOrigin,
      boolean flat,
      String inheritedReason,
      Set<String> copyTargets,
      Map<String, Field> fields) {
    object(properties)
        .forEach(
            (name, value) -> {
              Map<String, Object> mapping = object(value);
              String fieldName = prefix + name;
              String sourceName = sourcePrefix + name;
              String type = String.valueOf(mapping.getOrDefault("type", "object"));
              SourceOrigin origin =
                  type.equals("nested")
                      ? SourceOrigin.NESTED
                      : type.equals("flattened")
                          ? SourceOrigin.FLATTENED
                          : flat
                                  || Boolean.FALSE.equals(mapping.get("subobjects"))
                                  || name.contains(".")
                              ? SourceOrigin.FLAT
                              : parentOrigin;
              String reason =
                  inheritedReason != null ? inheritedReason : mappingRejectionReason(mapping);
              if (copyTargets.contains(fieldName))
                reason = "copy_to target has generated indexed values";
              Field field = mappedField(fieldName, sourceName, mapping, origin, reason);
              fields.put(fieldName, field);
              if (mapping.containsKey("properties") && !type.equals("nested")) {
                collectFields(
                    fieldName + ".",
                    sourceName + ".",
                    mapping.get("properties"),
                    origin == SourceOrigin.FLAT ? SourceOrigin.FLAT : SourceOrigin.OBJECT,
                    flat || Boolean.FALSE.equals(mapping.get("subobjects")),
                    reason,
                    copyTargets,
                    fields);
              }
              collectMultifields(
                  fieldName, sourceName, mapping.get("fields"), reason, copyTargets, fields);
            });
  }

  private static void collectMultifields(
      String parent,
      String source,
      Object mappings,
      String inheritedReason,
      Set<String> copyTargets,
      Map<String, Field> fields) {
    object(mappings)
        .forEach(
            (name, value) -> {
              String fieldName = parent + "." + name;
              Map<String, Object> mapping = object(value);
              // Multi-fields share the parent's raw source, but use their own indexing options.
              // A generated parent value is also indexed into all of its multi-fields.
              String reason =
                  inheritedReason != null
                          && (inheritedReason.startsWith("copy_to")
                              || inheritedReason.startsWith("constant_keyword")
                              || inheritedReason.equals("object mapping is disabled"))
                      ? inheritedReason
                      : mappingRejectionReason(mapping);
              if (copyTargets.contains(fieldName))
                reason = "copy_to target has generated indexed values";
              fields.put(
                  fieldName,
                  mappedField(fieldName, source, mapping, SourceOrigin.MULTIFIELD, reason));
              collectMultifields(
                  fieldName, source, mapping.get("fields"), reason, copyTargets, fields);
            });
  }

  private static Field mappedField(
      String name, String source, Map<String, Object> mapping, SourceOrigin origin, String reason) {
    String type = String.valueOf(mapping.getOrDefault("type", "object"));
    String format = String.valueOf(mapping.getOrDefault("format", Field.defaultDateFormat(type)));
    return new Field(
        name, source, type, reason == null, Map.of(), format, Map.of(), origin, Map.of(), reason);
  }

  private static String mappingRejectionReason(Map<String, Object> mapping) {
    if (Boolean.FALSE.equals(mapping.get("enabled"))) return "object mapping is disabled";
    if (Boolean.FALSE.equals(mapping.get("index"))) return "index is disabled";
    if (Boolean.FALSE.equals(mapping.get("doc_values"))) return "doc_values are disabled";
    for (String option : List.of("normalizer", "null_value", "ignore_above", "ignore_malformed"))
      if (mapping.containsKey(option)) return "mapping uses " + option;
    if ("constant_keyword".equals(mapping.get("type")))
      return "constant_keyword has generated indexed values";
    return null;
  }

  Map<String, Object> request(String method, String endpoint, Map<String, Object> body) {
    try {
      Request request = new Request(method, endpoint);
      if (body != null) request.setJsonEntity(mapper.writeValueAsString(body));
      String json = EntityUtils.toString(client.performRequest(request).getEntity());
      return mapper.readValue(json, new TypeReference<>() {});
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

  /** How a mapped field relates to the document's original object structure. */
  public enum SourceOrigin {
    ROOT,
    OBJECT,
    MULTIFIELD,
    NESTED,
    FLATTENED,
    FLAT,
    MIXED
  }

  /** Mapping identity and source semantics, retained through projection and wildcard discovery. */
  public record Field(
      String name,
      String source,
      String mappingType,
      boolean sourceCompatible,
      Map<String, String> sources,
      String dateFormat,
      Map<String, String> dateFormats,
      SourceOrigin origin,
      Map<String, SourceOrigin> origins,
      String incompatibilityReason) {
    public Field {
      Objects.requireNonNull(name);
      Objects.requireNonNull(source);
      Objects.requireNonNull(mappingType);
      sources = Map.copyOf(sources);
      dateFormat = dateFormat == null ? defaultDateFormat(mappingType) : dateFormat;
      dateFormats = Map.copyOf(dateFormats);
      Objects.requireNonNull(origin);
      origins = Map.copyOf(origins);
    }

    public Field(String name, String source, String mappingType) {
      this(name, source, mappingType, true);
    }

    public Field(String name, String source, String mappingType, boolean sourceCompatible) {
      this(name, source, mappingType, sourceCompatible, Map.of());
    }

    public Field(
        String name,
        String source,
        String mappingType,
        boolean sourceCompatible,
        Map<String, String> sources) {
      this(
          name,
          source,
          mappingType,
          sourceCompatible,
          sources,
          defaultDateFormat(mappingType),
          Map.of(),
          defaultOrigin(name, source, mappingType),
          Map.of(),
          sourceCompatible ? null : "source and indexed values differ");
    }

    public Field(String name, String source, String mappingType, String dateFormat) {
      this(
          name,
          source,
          mappingType,
          true,
          Map.of(),
          dateFormat,
          Map.of(),
          defaultOrigin(name, source, mappingType),
          Map.of(),
          null);
    }

    private static SourceOrigin defaultOrigin(String name, String source, String mappingType) {
      if (mappingType.equals("nested")) return SourceOrigin.NESTED;
      if (mappingType.equals("flattened")) return SourceOrigin.FLATTENED;
      if (!name.equals(source)) return SourceOrigin.MULTIFIELD;
      return name.contains(".") ? SourceOrigin.OBJECT : SourceOrigin.ROOT;
    }

    static String defaultDateFormat(String mappingType) {
      return switch (mappingType) {
        case "date", "array<date>" -> "strict_date_optional_time||epoch_millis";
        case "date_nanos", "array<date_nanos>" -> "strict_date_optional_time_nanos||epoch_millis";
        default -> "";
      };
    }

    public Field withMappingType(String type) {
      return new Field(
          name,
          source,
          type,
          sourceCompatible,
          sources,
          dateFormat,
          dateFormats,
          origin,
          origins,
          incompatibilityReason);
    }

    public Field merge(Field other) {
      boolean sameSource = source.equals(other.source);
      String reason =
          incompatibilityReason != null ? incompatibilityReason : other.incompatibilityReason;
      if (!sameSource) reason = "source paths differ between indices";
      else if (!mappingType.equals(other.mappingType))
        reason = "mapping types differ between indices";
      else if (origin != other.origin) reason = "source structures differ between indices";
      return new Field(
          name,
          sameSource ? source : name,
          mergedMappingType(other),
          sourceCompatible
              && other.sourceCompatible
              && sameSource
              && mappingType.equals(other.mappingType)
              && origin == other.origin,
          merged(sources, other.sources),
          dateFormat.equals(other.dateFormat) ? dateFormat : defaultDateFormat(mappingType),
          merged(dateFormats, other.dateFormats),
          origin == other.origin ? origin : SourceOrigin.MIXED,
          merged(origins, other.origins),
          reason);
    }

    private static <T> Map<String, T> merged(Map<String, T> left, Map<String, T> right) {
      if (left.isEmpty()) return right;
      if (right.isEmpty()) return left;
      Map<String, T> result = new LinkedHashMap<>(left);
      result.putAll(right);
      return result;
    }

    private String mergedMappingType(Field other) {
      if (mappingType.equals(other.mappingType)) return mappingType;
      List<String> integers = List.of("byte", "short", "integer", "long", "unsigned_long");
      int left = integers.indexOf(mappingType), right = integers.indexOf(other.mappingType);
      if (left >= 0 && right >= 0) return integers.get(Math.max(left, right));
      Set<String> floating = Set.of("half_float", "float", "double", "scaled_float");
      if (floating.contains(mappingType) && floating.contains(other.mappingType)) return "double";
      // Heterogeneous values must retain their runtime types, including large integers.
      return "any";
    }

    public String source(String index) {
      return sources.getOrDefault(index, source);
    }

    public String dateFormat(String index) {
      return dateFormats.getOrDefault(index, dateFormat);
    }

    public java.util.stream.Stream<String> sourcePaths() {
      return sources.isEmpty() ? java.util.stream.Stream.of(source) : sources.values().stream();
    }

    public RelDataType type(RelDataTypeFactory factory) {
      if (mappingType.startsWith("array<") && mappingType.endsWith(">")) {
        Field element = withMappingType(mappingType.substring(6, mappingType.length() - 1));
        return factory.createArrayType(
            factory.createTypeWithNullability(element.type(factory), true), -1);
      }
      if (mappingType.equals("nested"))
        return factory.createArrayType(factory.createSqlType(SqlTypeName.ANY), -1);
      return sqlType() == SqlTypeName.DECIMAL
          ? factory.createSqlType(SqlTypeName.DECIMAL, 20, 0)
          : factory.createSqlType(sqlType());
    }

    public SqlTypeName sqlType() {
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

    public boolean exact() {
      return sourceCompatible
          && !mappingType.startsWith("array<")
          && !Set.of("text", "object", "nested", "flattened", "any").contains(mappingType);
    }

    /** Only ordinary object properties can be recovered from ITEM(root, path). */
    public boolean hasObjectSourcePath() {
      return origin == SourceOrigin.ROOT || origin == SourceOrigin.OBJECT;
    }

    public boolean supportsComparison() {
      return comparisonRejectionReason() == null;
    }

    /** Pattern matching does not require ordering or range-comparison support. */
    public boolean supportsPattern() {
      return supportsExistence() && Set.of("keyword", "wildcard").contains(mappingType);
    }

    public boolean supportsOrdering() {
      return orderingRejectionReason() == null;
    }

    public boolean supportsGrouping() {
      return groupingRejectionReason() == null;
    }

    public boolean supportsExistence() {
      return existenceRejectionReason() == null;
    }

    public String comparisonRejectionReason() {
      String reason = existenceRejectionReason();
      if (reason != null) return reason;
      if (Set.of("float", "half_float", "scaled_float").contains(mappingType))
        return "indexed numeric values are quantized relative to _source";
      if (mappingType.equals("date_nanos"))
        return "nanosecond index precision differs from local millisecond timestamps";
      if (!Set.of(
              "keyword",
              "byte",
              "short",
              "integer",
              "long",
              "unsigned_long",
              "double",
              "boolean",
              "date")
          .contains(mappingType))
        return "mapping has no proven source-equivalent value operations: " + mappingType;
      return null;
    }

    public String orderingRejectionReason() {
      return comparisonRejectionReason();
    }

    public String groupingRejectionReason() {
      return comparisonRejectionReason();
    }

    public String existenceRejectionReason() {
      if (!sourceCompatible)
        return incompatibilityReason != null
            ? incompatibilityReason
            : "source and indexed values differ";
      return exact() ? null : "mapping is not an exact scalar: " + mappingType;
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
