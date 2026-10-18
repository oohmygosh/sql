/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.tools.Frameworks;
import org.opensearch.sql.backend.QueryBackend;

/** Reads the same backend schemas used by execution, caching only immutable catalog facts. */
public final class QueryMetadataService implements CompletionMetadata {
  private final Map<String, QueryBackend> backends;
  private final String defaultSource;
  private final Map<String, String> defaultTables;
  private final Cache<NamespaceKey, List<Relation>> tables;
  private final Cache<RelationKey, List<Field>> fields;

  public QueryMetadataService(
      List<QueryBackend> backends, String defaultSource, Map<String, String> defaultTables) {
    this(
        backends, defaultSource, defaultTables, Duration.ofSeconds(30), 128, Ticker.systemTicker());
  }

  QueryMetadataService(
      List<QueryBackend> backends,
      String defaultSource,
      Map<String, String> defaultTables,
      Duration ttl,
      long maximumEntries,
      Ticker ticker) {
    Map<String, QueryBackend> registry = new LinkedHashMap<>();
    for (QueryBackend backend : backends) {
      if (registry.putIfAbsent(backend.name(), backend) != null)
        throw new IllegalArgumentException("Duplicate data source: " + backend.name());
    }
    this.backends = Map.copyOf(registry);
    this.defaultSource =
        defaultSource != null
            ? defaultSource
            : registry.size() == 1 ? registry.keySet().iterator().next() : null;
    if (this.defaultSource != null && !registry.containsKey(this.defaultSource))
      throw new IllegalArgumentException("Unknown default data source: " + this.defaultSource);
    this.defaultTables = Map.copyOf(defaultTables);
    this.tables =
        CacheBuilder.newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(maximumEntries)
            .ticker(ticker)
            .build();
    this.fields =
        CacheBuilder.newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(maximumEntries)
            .ticker(ticker)
            .build();
  }

  @Override
  public String resolveSource(String source) {
    String resolved = source == null ? defaultSource : source;
    if (resolved == null || !backends.containsKey(resolved))
      throw new IllegalArgumentException("Unknown or unspecified data source: " + resolved);
    return resolved;
  }

  @Override
  public java.util.Set<String> capabilities(String source) {
    return backends.get(resolveSource(source)).machineLearning() == null
        ? java.util.Set.of()
        : java.util.Set.of("machine_learning");
  }

  @Override
  public List<String> parameterNames(String source, String capability) {
    var provider = backends.get(resolveSource(source)).machineLearning();
    return capability.equals("machine_learning") && provider != null
        ? provider.argumentNames().stream().sorted().toList()
        : List.of();
  }

  @Override
  public List<Relation> tables(String source, List<String> namespace, String prefix, int limit) {
    if (limit <= 0) return List.of();
    NamespaceKey key = namespace(source, namespace);
    String normalizedPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
    return load(tables, key, () -> readTables(key), "Unable to load tables for " + key.source())
        .stream()
        .filter(relation -> relation.name().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
        .limit(limit)
        .toList();
  }

  @Override
  public List<Field> fields(String source, RelationRef relation) {
    if (relation == null || relation.name() == null || relation.name().isBlank())
      throw new MetadataException("A table or index expression is required");
    RelationKey key = new RelationKey(namespace(source, relation.namespace()), relation.name());
    return load(fields, key, () -> readFields(key), "Unable to load fields for " + relation.name());
  }

  private NamespaceKey namespace(String source, List<String> namespace) {
    String resolved = resolveSource(source);
    List<String> path = namespace == null ? List.of() : List.copyOf(namespace);
    if (path.stream().anyMatch(String::isBlank))
      throw new MetadataException("Schema names must not be empty");
    String fallback = backends.get(resolved).defaultSchema();
    return new NamespaceKey(
        resolved, path.isEmpty() && !fallback.isEmpty() ? List.of(fallback) : path);
  }

  private List<Relation> readTables(NamespaceKey key) {
    QueryBackend backend = backends.get(key.source());
    Schema schema = schema(key);
    String kind =
        backend.type().equals("elasticsearch") || backend.type().equals("opensearch")
            ? "index"
            : "table";
    List<Relation> result = new ArrayList<>();
    backend
        .tableNames(schema)
        .forEach(name -> result.add(new Relation(key.namespace(), name, kind)));
    String preferred = defaultTables.get(key.source());
    // A configured wildcard is a useful starting expression, but only offer it when live catalog
    // names establish that it matches. Configuration alone is never evidence that a table exists.
    if ("index".equals(kind)
        && key.namespace().isEmpty()
        && preferred != null
        && (preferred.contains("*") || preferred.contains("?"))
        && result.stream().noneMatch(relation -> relation.name().equals(preferred))) {
      Pattern pattern = indexPattern(preferred);
      if (result.stream().anyMatch(relation -> pattern.matcher(relation.name()).matches()))
        result.add(new Relation(key.namespace(), preferred, "pattern"));
    }
    result.sort(
        Comparator.comparing((Relation relation) -> !relation.name().equals(preferred))
            .thenComparing(Relation::name));
    return List.copyOf(result);
  }

  private List<Field> readFields(RelationKey key) {
    QueryBackend backend = backends.get(key.namespace().source());
    var table = backend.metadataTable(schema(key.namespace()), key.name());
    if (table == null) throw new MetadataException("Table or index does not exist: " + key.name());
    Map<List<String>, Field> result = new LinkedHashMap<>();
    collectFields(table.getRowType(new JavaTypeFactoryImpl()), List.of(), result);
    return result.values().stream().sorted(Comparator.comparing(Field::name)).toList();
  }

  private static void collectFields(
      RelDataType row, List<String> prefix, Map<List<String>, Field> result) {
    for (var field : row.getFieldList()) {
      List<String> components = new ArrayList<>(prefix);
      components.add(field.getName());
      List<String> path = List.copyOf(components);
      String name = String.join(".", path);
      RelDataType type = field.getType();
      result.putIfAbsent(
          path, new Field(name, type.getSqlTypeName().getName().toLowerCase(Locale.ROOT), path));
      if (type.isStruct()) collectFields(type, path, result);
    }
  }

  private Schema schema(NamespaceKey key) {
    QueryBackend backend = backends.get(key.source());
    SchemaPlus root = Frameworks.createRootSchema(false);
    root.setCacheEnabled(false);
    SchemaPlus catalog = root.add(key.source(), backend.createSchema(root));
    catalog.setCacheEnabled(false);
    Schema current = catalog;
    for (String component : key.namespace()) {
      current = current.getSubSchema(component);
      if (current == null)
        throw new MetadataException("Schema does not exist: " + String.join(".", key.namespace()));
    }
    return current;
  }

  private static Pattern indexPattern(String expression) {
    StringBuilder pattern = new StringBuilder();
    for (int index = 0; index < expression.length(); index++) {
      char character = expression.charAt(index);
      pattern.append(
          character == '*'
              ? ".*"
              : character == '?' ? "." : Pattern.quote(String.valueOf(character)));
    }
    return Pattern.compile(pattern.toString());
  }

  private static <K, V> V load(Cache<K, V> cache, K key, Callable<V> reader, String failure) {
    try {
      return cache.get(key, reader);
    } catch (ExecutionException | com.google.common.util.concurrent.UncheckedExecutionException e) {
      if (e.getCause() instanceof MetadataException metadata) throw metadata;
      throw new MetadataException(failure, e.getCause());
    }
  }

  private record NamespaceKey(String source, List<String> namespace) {}

  private record RelationKey(NamespaceKey namespace, String name) {}
}
