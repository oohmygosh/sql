/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Test;
import org.opensearch.sql.backend.QueryBackend;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.executor.*;

public class UnifiedQueryStreamingTest {
  @Test
  public void streamsPastInteractiveLimitWithoutCollectingAndPreservesExplicitLimits() {
    for (QueryType language : QueryType.values()) {
      var table = new Rows(25005);
      var service = service(table);
      String query =
          language == QueryType.SQL ? "SELECT id FROM events" : "source=events | fields id";
      assertEquals(2, service.execute(language, query).getResults().size());
      table.reads.set(0);
      AtomicInteger rows = new AtomicInteger();
      service.stream(
          null,
          language,
          query,
          new QueryRowConsumer() {
            public void schema(ExecutionEngine.Schema schema) {
              assertEquals("id", schema.getColumns().getFirst().getName());
            }

            public void row(ExprValue row) {
              assertEquals(
                  rows.getAndIncrement(), row.tupleValue().get("id").integerValue().intValue());
              // Calcite's JDBC cursor can prefetch; the sink must run before the source is drained.
              assertTrue(table.reads.get() <= rows.get() + 1);
            }
          },
          new QueryCancellation());
      assertEquals(25005, rows.get());
      assertEquals(2, table.closed.get());
      rows.set(0);
      service.stream(
          null,
          language,
          query + (language == QueryType.SQL ? " LIMIT 3" : " | head 3"),
          sink(rows),
          new QueryCancellation());
      assertEquals(3, rows.get());
    }
  }

  @Test
  public void sinkFailureAndCancellationCloseTheSourceAndEmptyResultsStillEmitSchema() {
    var table = new Rows(100);
    var service = service(table);
    var cancel = new QueryCancellation();
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () ->
            service.stream(
                null,
                QueryType.SQL,
                "SELECT * FROM events",
                new QueryRowConsumer() {
                  public void schema(ExecutionEngine.Schema schema) {}

                  public void row(ExprValue row) {
                    cancel.cancel();
                  }
                },
                cancel));
    assertEquals(1, table.closed.get());
    assertThrows(
        IllegalStateException.class,
        () ->
            service.stream(
                null,
                QueryType.SQL,
                "SELECT * FROM events",
                new QueryRowConsumer() {
                  public void schema(ExecutionEngine.Schema schema) {}

                  public void row(ExprValue row) throws Exception {
                    throw new java.io.IOException("disk full");
                  }
                },
                new QueryCancellation()));
    assertEquals(2, table.closed.get());
    AtomicInteger schemas = new AtomicInteger();
    service(new Rows(0)).stream(
        null,
        QueryType.PPL,
        "source=events",
        new QueryRowConsumer() {
          public void schema(ExecutionEngine.Schema schema) {
            schemas.incrementAndGet();
          }

          public void row(ExprValue row) {
            fail("empty result");
          }
        },
        new QueryCancellation());
    assertEquals(1, schemas.get());
  }

  private QueryRowConsumer sink(AtomicInteger rows) {
    return new QueryRowConsumer() {
      public void schema(ExecutionEngine.Schema schema) {}

      public void row(ExprValue row) {
        rows.incrementAndGet();
      }
    };
  }

  private UnifiedQueryService service(Rows rows) {
    QueryBackend backend =
        new QueryBackend() {
          public String name() {
            return "memory";
          }

          public String type() {
            return "test";
          }

          public Schema createSchema(SchemaPlus parent) {
            return new AbstractSchema() {
              protected Map<String, Table> getTableMap() {
                return Map.of("events", rows);
              }
            };
          }

          public ExecutionEngine executionEngine() {
            return new CalciteExecutionEngine();
          }
        };
    return new UnifiedQueryService(
        List.of(backend), "memory", Map.of("plugins.query.size_limit", 2));
  }

  private static class Rows extends AbstractTable implements ScannableTable {
    private final int count;
    final AtomicInteger reads = new AtomicInteger();
    final AtomicInteger closed = new AtomicInteger();

    Rows(int count) {
      this.count = count;
    }

    public RelDataType getRowType(RelDataTypeFactory factory) {
      return factory.builder().add("id", SqlTypeName.INTEGER).build();
    }

    public Enumerable<Object[]> scan(DataContext root) {
      return new AbstractEnumerable<>() {
        public Enumerator<Object[]> enumerator() {
          return new Enumerator<>() {
            int index = -1;

            public Object[] current() {
              return new Object[] {index};
            }

            public boolean moveNext() {
              if (++index >= count) return false;
              reads.incrementAndGet();
              return true;
            }

            public void reset() {
              throw new UnsupportedOperationException();
            }

            public void close() {
              closed.incrementAndGet();
            }
          };
        }
      };
    }
  }
}
