/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Ticker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Test;
import org.opensearch.sql.api.completion.CompletionMetadata.Field;
import org.opensearch.sql.api.completion.CompletionMetadata.MetadataException;
import org.opensearch.sql.api.completion.CompletionMetadata.Relation;
import org.opensearch.sql.api.completion.CompletionMetadata.RelationRef;
import org.opensearch.sql.backend.QueryBackend;
import org.opensearch.sql.executor.ExecutionEngine;

public class QueryMetadataServiceTest {
  @Test
  public void listsLiveTablesAndOnlyMatchingConfiguredPatterns() {
    TestBackend backend =
        new TestBackend(
            "logs",
            "elasticsearch",
            "",
            schema(Map.of("logevent-2026", table("message"), "audit", table("action"))));
    QueryMetadataService service =
        new QueryMetadataService(List.of(backend), "logs", Map.of("logs", "logevent-*"));

    assertEquals(
        List.of(
            new Relation(List.of(), "logevent-*", "pattern"),
            new Relation(List.of(), "logevent-2026", "index")),
        service.tables("logs", List.of(), "LOG", 10));
    assertEquals(
        List.of(new Relation(List.of(), "logevent-*", "pattern")),
        service.tables("logs", List.of(), "", 1));
    assertEquals(1, backend.enumerations.get());

    QueryMetadataService nonexistentDefault =
        new QueryMetadataService(List.of(backend), "logs", Map.of("logs", "missing-*"));
    assertTrue(nonexistentDefault.tables("logs", List.of(), "missing", 10).isEmpty());
  }

  @Test
  public void routesDefaultAndQualifiedNamespacesWithoutSplittingNames() {
    TestBackend backend =
        new TestBackend(
            "analytics",
            "clickhouse",
            "default",
            new AbstractSchema() {
              @Override
              protected Map<String, Schema> getSubSchemaMap() {
                return Map.of(
                    "default", schema(Map.of("daily.events", table("id"))),
                    "archive", schema(Map.of("daily.events", table("archived_id"))));
              }
            });
    QueryMetadataService service = new QueryMetadataService(List.of(backend), null, Map.of());

    assertEquals("analytics", service.resolveSource(null));
    assertEquals(
        List.of(new Relation(List.of("default"), "daily.events", "table")),
        service.tables(null, List.of(), "", 10));
    assertEquals(
        List.of(new Field("id", "varchar")),
        service.fields(null, new RelationRef(List.of(), "daily.events")));
    assertEquals(
        List.of(new Field("archived_id", "varchar")),
        service.fields("analytics", new RelationRef(List.of("archive"), "daily.events")));
    assertThrows(
        MetadataException.class,
        () -> service.fields("analytics", new RelationRef(List.of(), "default.daily.events")));
  }

  @Test
  public void readsWildcardAndNestedFieldsFromResolvedRowType() {
    Table wildcard =
        new AbstractTable() {
          @Override
          public RelDataType getRowType(RelDataTypeFactory factory) {
            return factory
                .builder()
                .add("status", SqlTypeName.ANY)
                .add("message.keyword", SqlTypeName.VARCHAR)
                .add("user", factory.builder().add("id", SqlTypeName.BIGINT).build())
                .build();
          }
        };
    TestBackend backend =
        new TestBackend("logs", "elasticsearch", "", schema(Map.of("events-*", wildcard)));
    QueryMetadataService service = new QueryMetadataService(List.of(backend), "logs", Map.of());

    assertEquals(
        List.of(
            new Field("message.keyword", "varchar"),
            new Field("status", "any"),
            new Field("user", "row"),
            new Field("user.id", "bigint", List.of("user", "id"))),
        service.fields("logs", new RelationRef(List.of(), "events-*")));
    assertEquals(0, backend.enumerations.get());
  }

  @Test
  public void distinguishesLiteralDottedNamesFromStructuredFieldPaths() {
    Table events =
        new AbstractTable() {
          @Override
          public RelDataType getRowType(RelDataTypeFactory factory) {
            return factory
                .builder()
                .add("http.status", SqlTypeName.VARCHAR)
                .add(
                    "http",
                    factory
                        .builder()
                        .add("status", SqlTypeName.INTEGER)
                        .add("status.code", SqlTypeName.BIGINT)
                        .build())
                .build();
          }
        };
    TestBackend backend = new TestBackend("logs", "test", "", schema(Map.of("events", events)));
    QueryMetadataService service = new QueryMetadataService(List.of(backend), "logs", Map.of());

    assertEquals(
        List.of(
            new Field("http", "row", List.of("http")),
            new Field("http.status", "varchar", List.of("http.status")),
            new Field("http.status", "integer", List.of("http", "status")),
            new Field("http.status.code", "bigint", List.of("http", "status.code"))),
        service.fields("logs", new RelationRef(List.of(), "events")));
  }

  @Test
  public void keepsSourceCachesSeparateAndResultsImmutable() {
    TestBackend first = new TestBackend("first", "test", "", schema(Map.of("events", table("a"))));
    TestBackend second =
        new TestBackend("second", "test", "", schema(Map.of("events", table("b"))));
    QueryMetadataService service =
        new QueryMetadataService(List.of(first, second), "first", Map.of());
    RelationRef relation = new RelationRef(List.of(), "events");

    assertEquals(List.of(new Field("a", "varchar")), service.fields(null, relation));
    assertEquals(List.of(new Field("b", "varchar")), service.fields("second", relation));
    assertThrows(
        UnsupportedOperationException.class,
        () -> service.fields(null, relation).add(new Field("mutated", "varchar")));
    assertEquals(List.of(new Field("a", "varchar")), service.fields("first", relation));
    assertEquals(1, first.schemas.get());
    assertEquals(1, second.schemas.get());
  }

  @Test
  public void expiresMetadataAndBoundsCachedRelations() {
    AtomicLong time = new AtomicLong();
    Ticker ticker =
        new Ticker() {
          @Override
          public long read() {
            return time.get();
          }
        };
    TestBackend backend =
        new TestBackend("logs", "test", "", schema(Map.of("a", table("old"), "b", table("other"))));
    QueryMetadataService service =
        new QueryMetadataService(
            List.of(backend), "logs", Map.of(), Duration.ofSeconds(30), 1, ticker);
    RelationRef a = new RelationRef(List.of(), "a");

    assertEquals(List.of(new Field("old", "varchar")), service.fields(null, a));
    backend.schema = schema(Map.of("a", table("new"), "b", table("other")));
    assertEquals(List.of(new Field("old", "varchar")), service.fields(null, a));
    time.set(Duration.ofSeconds(31).toNanos());
    assertEquals(List.of(new Field("new", "varchar")), service.fields(null, a));
    service.fields(null, new RelationRef(List.of(), "b"));
    backend.schema = schema(Map.of("a", table("after_eviction")));
    assertEquals(List.of(new Field("after_eviction", "varchar")), service.fields(null, a));
  }

  @Test
  public void distinguishesMissingTablesFromEmptyTablesAndDoesNotCacheErrors() {
    TestBackend backend = new TestBackend("logs", "test", "", schema(Map.of()));
    QueryMetadataService service = new QueryMetadataService(List.of(backend), "logs", Map.of());
    RelationRef relation = new RelationRef(List.of(), "new_table");

    assertThrows(MetadataException.class, () -> service.fields(null, relation));
    backend.schema = schema(Map.of("new_table", table()));
    assertTrue(service.fields(null, relation).isEmpty());
    assertEquals(2, backend.schemas.get());
  }

  @Test
  public void hidesBackendFailureDetailsAndRetriesTheNextLookup() {
    TestBackend backend = new TestBackend("logs", "test", "", schema(Map.of("events", table())));
    backend.failure = new IllegalStateException("secret password and internal host");
    QueryMetadataService service = new QueryMetadataService(List.of(backend), "logs", Map.of());

    MetadataException failure =
        assertThrows(MetadataException.class, () -> service.tables(null, List.of(), "", 10));
    assertFalse(failure.getMessage().contains("secret"));
    assertEquals("Unable to load tables for logs", failure.getMessage());
    backend.failure = null;
    assertEquals(1, service.tables(null, List.of(), "", 10).size());
    assertThrows(IllegalArgumentException.class, () -> service.resolveSource("missing"));
    assertThrows(MetadataException.class, () -> service.tables("logs", List.of("missing"), "", 10));
  }

  private static Schema schema(Map<String, Table> tables) {
    return new AbstractSchema() {
      @Override
      protected Map<String, Table> getTableMap() {
        return tables;
      }
    };
  }

  private static Table table(String... fields) {
    return new AbstractTable() {
      @Override
      public RelDataType getRowType(RelDataTypeFactory factory) {
        var builder = factory.builder();
        for (String field : fields) builder.add(field, SqlTypeName.VARCHAR);
        return builder.build();
      }
    };
  }

  private static final class TestBackend implements QueryBackend {
    private final String name;
    private final String type;
    private final String defaultSchema;
    private final AtomicInteger schemas = new AtomicInteger();
    private final AtomicInteger enumerations = new AtomicInteger();
    private Schema schema;
    private RuntimeException failure;

    private TestBackend(String name, String type, String defaultSchema, Schema schema) {
      this.name = name;
      this.type = type;
      this.defaultSchema = defaultSchema;
      this.schema = schema;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String type() {
      return type;
    }

    @Override
    public String defaultSchema() {
      return defaultSchema;
    }

    @Override
    public Schema createSchema(SchemaPlus parent) {
      schemas.incrementAndGet();
      if (failure != null) throw failure;
      return schema;
    }

    @Override
    public Set<String> tableNames(Schema schema) {
      enumerations.incrementAndGet();
      return QueryBackend.super.tableNames(schema);
    }

    @Override
    public ExecutionEngine executionEngine() {
      throw new AssertionError("Metadata lookup must not execute a data query");
    }
  }
}
