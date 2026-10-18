/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.clickhouse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.util.TimestampString;
import org.junit.jupiter.api.Test;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.executor.QueryType;

/** Exercises emitted SQL and public JDBC metadata without modifying any live database. */
class ClickHouseAdapterRegressionTest {
  @Test
  void unicodeStringsUseNativeClickHouseQuoting() {
    String sql =
        SqlLiteral.createCharString("中文'😀", "UTF-8", SqlParserPos.ZERO)
            .toSqlString(QueryClickHouseDialect.INSTANCE)
            .getSql();
    assertEquals("'中文''😀'", sql);
  }

  @Test
  void timestampLiteralIncludesEveryArgumentSeparator() {
    for (int precision : List.of(3, 9)) {
      String sql =
          SqlLiteral.createTimestamp(
                  new TimestampString("2026-10-17 00:00:00"), precision, SqlParserPos.ZERO)
              .toSqlString(QueryClickHouseDialect.INSTANCE)
              .getSql();
      assertTrue(
          sql.matches(
              "toDateTime64\\('2026-10-17 00:00:00(?:\\.0+)?', " + precision + ", 'UTC'\\)"),
          sql);
    }
  }

  @Test
  void unsignedMetadataIsExactDecimalWhileStringsRemainUnbounded() throws Exception {
    try (var connection = new ClickHouseMetadataDataSource(source()).getConnection();
        var columns = connection.getMetaData().getColumns("default", null, "events", null)) {
      assertTrue(columns.next());
      assertEquals(Types.DECIMAL, columns.getInt(5));
      assertEquals(Types.DECIMAL, columns.getInt("DATA_TYPE"));
      assertEquals(20, columns.getInt(7));
      assertEquals(20, columns.getInt("COLUMN_SIZE"));
      assertEquals(0, columns.getInt(9));
      assertTrue(columns.next());
      assertEquals(Types.VARCHAR, columns.getInt(5));
      assertEquals(-1, columns.getInt(7));
    }
  }

  @Test
  void independentConjunctSurvivesUnsupportedLike() throws Exception {
    for (QueryType language : List.of(QueryType.SQL, QueryType.PPL)) {
      String query =
          language == QueryType.SQL
              ? "SELECT id FROM events WHERE source='batch' AND level LIKE '%ERR%' LIMIT 5"
              : "source=events | where source='batch' and like(level,'%ERR%') | fields id | head 5";
      Plan plan = plan(language, query);
      assertTrue(plan.sql.contains("WHERE `source` = 'batch'"), plan.sql);
      assertFalse(plan.sql.contains("LIKE"), plan.sql);
      assertFalse(plan.sql.contains("LIMIT"), "LIMIT cannot cross a residual filter: " + plan.sql);
      assertTrue(plan.physical.contains("EnumerableCalc"), plan.physical);
    }
  }

  @Test
  void searchPredicateIsExpandedAndPushed() throws Exception {
    Plan plan =
        plan(
            QueryType.SQL,
            "SELECT count(*) AS n FROM events WHERE source='batch' AND level IN ('ERROR','WARN')");
    assertTrue(plan.sql.contains("WHERE"), plan.sql);
    assertTrue(plan.sql.contains("'ERROR'") && plan.sql.contains("'WARN'"), plan.sql);
    assertFalse(plan.physical.contains("SEARCH("), plan.physical);
    assertFalse(plan.physical.contains("EnumerableCalc"), plan.physical);
  }

  @Test
  void unsupportedOrBranchIsNeverPartiallyPushed() throws Exception {
    Plan plan =
        plan(
            QueryType.SQL,
            "SELECT id FROM events WHERE source='batch' OR level LIKE '%ERR%' LIMIT 5");
    assertFalse(plan.sql.contains("WHERE"), plan.sql);
    assertFalse(plan.sql.contains("LIMIT"), plan.sql);
    assertTrue(plan.physical.contains("EnumerableCalc"), plan.physical);
  }

  @Test
  void unsignedSumIsNotDelegatedToWrappingUInt64Accumulator() throws Exception {
    Plan plan = plan(QueryType.SQL, "SELECT sum(id) AS n FROM events WHERE source='batch'");
    assertFalse(plan.sql.toUpperCase(java.util.Locale.ROOT).contains("SUM("), plan.sql);
    assertFalse(plan.sql.contains("sumOrNull"), plan.sql);
    assertTrue(plan.sql.contains("WHERE"), plan.sql);
    assertTrue(plan.physical.contains("EnumerableAggregate"), plan.physical);
  }

  @Test
  void aLimitBeforeFilteringRemainsBelowTheFilter() throws Exception {
    Plan plan =
        plan(QueryType.PPL, "source=events | sort id | head 2 | where source='batch' | fields id");
    // A native subquery may contain both operations, but cannot filter the base scan before LIMIT.
    if (plan.sql.contains("WHERE")) {
      assertTrue(plan.sql.indexOf("LIMIT 2") < plan.sql.indexOf("WHERE"), plan.sql);
    } else {
      assertTrue(plan.sql.contains("LIMIT 2"), plan.sql);
    }
  }

  private record Plan(String physical, String sql) {}

  private static Plan plan(QueryType language, String query) throws Exception {
    var service =
        new UnifiedQueryService(
            List.of(new ClickHouseQueryBackend("ch", source(), "default")), "ch");
    var sql = new ArrayList<String>();
    try (var hook = Hook.QUERY_PLAN.addThread((Object value) -> sql.add(value.toString()))) {
      var explanation = service.explain(null, language, query);
      return new Plan(explanation.getCalcite().getPhysical(), String.join("\n", sql));
    }
  }

  private static DataSource source() throws Exception {
    DataSource source = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getDatabaseProductName()).thenReturn("ClickHouse");
    when(metadata.getTables(any(), any(), any(), any()))
        .thenAnswer(invocation -> rows(new Object[][] {{"default", null, "events", "TABLE"}}));
    when(metadata.getColumns(any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                rows(
                    new Object[][] {
                      {
                        null,
                        null,
                        null,
                        "id",
                        Types.OTHER,
                        "UInt64",
                        20,
                        null,
                        0,
                        null,
                        DatabaseMetaData.columnNoNulls
                      },
                      {
                        null,
                        null,
                        null,
                        "source",
                        Types.VARCHAR,
                        "Nullable(String)",
                        0,
                        null,
                        0,
                        null,
                        DatabaseMetaData.columnNullable
                      },
                      {
                        null,
                        null,
                        null,
                        "level",
                        Types.VARCHAR,
                        "Nullable(String)",
                        0,
                        null,
                        0,
                        null,
                        DatabaseMetaData.columnNullable
                      }
                    }));
    return source;
  }

  private static ResultSet rows(Object[][] values) throws Exception {
    ResultSet result = mock(ResultSet.class);
    AtomicInteger row = new AtomicInteger(-1);
    when(result.next()).thenAnswer(invocation -> row.incrementAndGet() < values.length);
    when(result.getString(anyInt()))
        .thenAnswer(
            invocation -> {
              Object v = values[row.get()][(Integer) invocation.getArgument(0) - 1];
              return v == null ? null : v.toString();
            });
    when(result.getString("TYPE_NAME")).thenAnswer(invocation -> values[row.get()][5]);
    when(result.getInt(anyInt()))
        .thenAnswer(
            invocation -> {
              Object v = values[row.get()][(Integer) invocation.getArgument(0) - 1];
              return v == null ? 0 : ((Number) v).intValue();
            });
    when(result.getInt(anyString()))
        .thenAnswer(
            invocation -> {
              int column =
                  switch ((String) invocation.getArgument(0)) {
                    case "DATA_TYPE" -> 5;
                    case "COLUMN_SIZE" -> 7;
                    case "DECIMAL_DIGITS" -> 9;
                    default -> throw new AssertionError();
                  };
              return ((Number) values[row.get()][column - 1]).intValue();
            });
    return result;
  }
}
