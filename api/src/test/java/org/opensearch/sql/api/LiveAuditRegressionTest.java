/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Test;
import org.opensearch.sql.backend.QueryBackend;
import org.opensearch.sql.executor.CalciteExecutionEngine;
import org.opensearch.sql.executor.ExecutionEngine;
import org.opensearch.sql.executor.QueryType;

/** Independent edge cases for the defects found in the read-only live audit. */
public class LiveAuditRegressionTest {
  private final UnifiedQueryService service =
      new UnifiedQueryService(
          List.of(
              new QueryBackend() {
                public String name() {
                  return "audit";
                }

                public String type() {
                  return "memory";
                }

                public ExecutionEngine executionEngine() {
                  return new CalciteExecutionEngine();
                }

                public Schema createSchema(SchemaPlus parent) {
                  return new AbstractSchema() {
                    @Override
                    protected Map<String, org.apache.calcite.schema.Table> getTableMap() {
                      return Map.of("numbers", new Numbers());
                    }
                  };
                }
              }),
          "audit");

  private static final class Numbers extends AbstractTable implements ScannableTable {
    @Override
    public RelDataType getRowType(RelDataTypeFactory factory) {
      return factory
          .builder()
          .add("id", factory.createSqlType(SqlTypeName.DECIMAL, 20, 0))
          .add(
              "size",
              factory.createTypeWithNullability(
                  factory.createSqlType(SqlTypeName.DECIMAL, 20, 0), true))
          .add("label", factory.createSqlType(SqlTypeName.VARCHAR, 20))
          .add(
              "priority",
              factory.createTypeWithNullability(factory.createSqlType(SqlTypeName.INTEGER), true))
          .build();
    }

    @Override
    public Enumerable<Object[]> scan(DataContext context) {
      return Linq4j.asEnumerable(
          List.of(
              new Object[] {
                new BigDecimal("18446744073709551613"),
                new BigDecimal("9007199254740993"),
                "中文",
                null
              },
              new Object[] {
                new BigDecimal("18446744073709551614"), new BigDecimal("9007199254740995"), "中文", 2
              },
              new Object[] {new BigDecimal("18446744073709551615"), null, "😀", null}));
    }
  }

  @Test
  public void coalesceAcceptsMultipleArgumentsAndKeepsNullSemantics() {
    var rows =
        service
            .execute(
                QueryType.SQL, "SELECT coalesce(priority,NULL,-1) AS p FROM numbers ORDER BY id")
            .getResults();
    assertEquals(
        List.of(-1, 2, -1),
        rows.stream().map(r -> r.tupleValue().get("p").integerValue()).toList());
    assertTrue(
        service
            .execute(QueryType.SQL, "SELECT coalesce(NULL,NULL) AS p")
            .getResults()
            .getFirst()
            .tupleValue()
            .get("p")
            .isNull());
  }

  @Test
  public void unicodeLiteralsMatchUnicodeColumnsInBothLanguages() {
    for (QueryType language : List.of(QueryType.SQL, QueryType.PPL)) {
      String query =
          language == QueryType.SQL
              ? "SELECT count(*) AS n FROM numbers WHERE label='中文' OR label='😀'"
              : "source=numbers | where label='中文' or label='😀' | stats count() as n";
      assertEquals(
          3L,
          service
              .execute(language, query)
              .getResults()
              .getFirst()
              .tupleValue()
              .get("n")
              .longValue()
              .longValue());
    }
  }

  @Test
  public void rowNumberAdvancesWithinEachPartition() {
    var rows =
        service
            .execute(
                QueryType.SQL,
                "SELECT id,row_number() OVER(PARTITION BY label ORDER BY id) AS rn FROM numbers"
                    + " ORDER BY id")
            .getResults();
    assertEquals(
        List.of(1L, 2L, 1L), rows.stream().map(r -> r.tupleValue().get("rn").longValue()).toList());
    assertEquals("18446744073709551615", rows.getLast().tupleValue().get("id").value().toString());
  }

  @Test
  public void decimalAggregationsAndWindowsKeepLowBits() {
    for (QueryType language : List.of(QueryType.SQL, QueryType.PPL)) {
      var row =
          service
              .execute(
                  language,
                  language == QueryType.SQL
                      ? "SELECT sum(size) AS s,min(size) AS lo,max(size) AS hi FROM numbers"
                      : "source=numbers | stats sum(size) as s,min(size) as lo,max(size) as hi")
              .getResults()
              .getFirst()
              .tupleValue();
      assertEquals(new BigDecimal("18014398509481988"), row.get("s").value());
      assertEquals(new BigDecimal("9007199254740993"), row.get("lo").value());
      assertEquals(new BigDecimal("9007199254740995"), row.get("hi").value());
      var idSum =
          service
              .execute(
                  language,
                  language == QueryType.SQL
                      ? "SELECT sum(id) AS total FROM numbers"
                      : "source=numbers | stats sum(id) as total")
              .getResults()
              .getFirst()
              .tupleValue();
      assertEquals(new BigDecimal("55340232221128654842"), idSum.get("total").value());
    }
    var rows =
        service
            .execute(
                QueryType.PPL,
                "source=numbers | eventstats max(size) as hi by label | sort id | fields hi")
            .getResults();
    assertEquals(
        new BigDecimal("9007199254740995"), rows.getFirst().tupleValue().get("hi").value());
    assertTrue(rows.getLast().tupleValue().get("hi").isNull());
  }

  @Test
  public void decimalSpanKeepsPrecisionAboveDoubleIntegerRange() {
    var rows =
        service
            .execute(
                QueryType.PPL,
                "source=numbers | where isnotnull(size) | stats count() as n by span(size,10) as"
                    + " bucket")
            .getResults();
    assertEquals(1, rows.size());
    assertEquals(
        new BigDecimal("9007199254740990"), rows.getFirst().tupleValue().get("bucket").value());
    assertEquals(2L, rows.getFirst().tupleValue().get("n").longValue().longValue());
  }

  @Test
  public void integralDecimalStatisticsDoNotTruncateToFourPlaces() {
    // Derive smaller values from exact integers so the reference variance does not suffer
    // floating-point cancellation at the UInt64 magnitude.
    var row =
        service
            .execute(
                QueryType.PPL,
                "source=numbers | eval small=size - 9007199254740990 | stats avg(small) as"
                    + " mean,stddev_samp(small) as sd")
            .getResults()
            .getFirst()
            .tupleValue();
    assertEquals(4.0, row.get("mean").doubleValue(), 1e-12);
    assertEquals(Math.sqrt(2.0), row.get("sd").doubleValue(), 1e-12);
  }
}
