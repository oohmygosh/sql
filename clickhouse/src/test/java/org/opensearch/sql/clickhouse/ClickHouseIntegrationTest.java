/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.clickhouse;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.executor.QueryType;

@EnabledIfEnvironmentVariable(named = "CLICKHOUSE_TEST_URL", matches = ".+")
class ClickHouseIntegrationTest {
  static Properties credentials() {
    Properties properties = new Properties();
    String username = System.getenv("CLICKHOUSE_TEST_USERNAME");
    String password = System.getenv("CLICKHOUSE_TEST_PASSWORD");
    if (username != null) properties.setProperty("user", username);
    if (password != null) properties.setProperty("password", password);
    return properties;
  }

  @Test
  void sqlPplAndRelationalPushdown() throws Exception {
    String url = System.getenv("CLICKHOUSE_TEST_URL");
    String table = "uq_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, credentials());
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE "
              + table
              + " (id Int32, name String, department String, amount Nullable(Int64))"
              + " ENGINE=Memory");
      try {
        statement.execute(
            "INSERT INTO "
                + table
                + " VALUES (1,'Alice','A',10),(2,'Bob','B',20),(3,'Carol','A',NULL)");
        var service =
            new UnifiedQueryService(
                List.of(new ClickHouseQueryBackend("ch", url, credentials(), "default")), "ch");
        var sql =
            service.execute(
                QueryType.SQL,
                "SELECT department,count(*) AS n FROM "
                    + table
                    + " GROUP BY department ORDER BY department");
        var ppl =
            service.execute(
                QueryType.PPL,
                "source="
                    + table
                    + " | stats count() as n by department | sort department | fields"
                    + " department,n");
        assertEquals(sql.getResults(), ppl.getResults());
        assertEquals(2, sql.getResults().size());
        assertEquals(2L, sql.getResults().getFirst().tupleValue().get("n").longValue());
        assertEquals(
            1,
            service
                .execute(QueryType.SQL, "SELECT name FROM " + table + " WHERE amount IS NULL")
                .getResults()
                .size());
        assertEquals(
            3,
            service
                .execute(
                    QueryType.SQL,
                    "SELECT a.id,b.name FROM " + table + " a JOIN " + table + " b ON a.id=b.id")
                .getResults()
                .size());
        assertEquals(
            3,
            service
                .execute(
                    QueryType.SQL, "SELECT id,row_number() OVER (ORDER BY id) AS rn FROM " + table)
                .getResults()
                .size());
        assertEquals(
            2L,
            service
                .execute(QueryType.SQL, "SELECT count(DISTINCT department) AS n FROM " + table)
                .getResults()
                .getFirst()
                .tupleValue()
                .get("n")
                .longValue());
        assertEquals(
            2,
            service
                .execute(
                    QueryType.SQL,
                    "SELECT id FROM "
                        + table
                        + " WHERE id IN (SELECT id FROM "
                        + table
                        + " WHERE id>1)")
                .getResults()
                .size());
        assertEquals(
            3,
            service
                .execute(
                    QueryType.PPL,
                    "source=" + table + " | eval label=upper(department) | fields label")
                .getResults()
                .size());
        assertEquals(
            2L,
            service
                .execute(
                    QueryType.PPL,
                    "source=" + table + " | stats distinct_count_approx(department) as n")
                .getResults()
                .getFirst()
                .tupleValue()
                .get("n")
                .longValue());
        assertEquals(
            3,
            service
                .execute(QueryType.SQL, "SELECT id FROM ch.default." + table)
                .getResults()
                .size());
        assertTrue(
            service
                .execute(QueryType.SQL, "SELECT min(id) AS m FROM " + table + " WHERE id>999")
                .getResults()
                .getFirst()
                .tupleValue()
                .get("m")
                .isNull());
        var empty =
            service.execute(
                QueryType.SQL,
                "SELECT count(*) AS n,sum(id) AS s,min(id) AS m FROM " + table + " WHERE id>999");
        assertEquals(0L, empty.getResults().getFirst().tupleValue().get("n").longValue());
        assertTrue(empty.getResults().getFirst().tupleValue().get("s").isNull());
        assertTrue(empty.getResults().getFirst().tupleValue().get("m").isNull());
        var outer =
            service.execute(
                QueryType.SQL,
                "SELECT a.id,b.id AS rhs FROM "
                    + table
                    + " a LEFT JOIN "
                    + table
                    + " b ON a.id=b.id AND b.id=1 ORDER BY a.id");
        assertTrue(outer.getResults().get(1).tupleValue().get("rhs").isNull());
        assertTrue(
            service
                .explain(null, QueryType.SQL, "SELECT id FROM " + table + " WHERE id>1")
                .getCalcite()
                .getPhysical()
                .contains("Jdbc"));
      } finally {
        statement.execute("DROP TABLE " + table);
      }
    }
  }

  @Test
  void preservesNumericAndArrayResults() throws Exception {
    String url = System.getenv("CLICKHOUSE_TEST_URL");
    String table = "uq_types_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, credentials());
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE "
              + table
              + " (u UInt64, d Decimal(24,4), tags Array(String), stamp DateTime64(3,'UTC'))"
              + " ENGINE=Memory");
      try {
        statement.execute(
            "INSERT INTO "
                + table
                + " VALUES (18446744073709551615,12345678901234567890.1234,['a','b'],'2025-01-02"
                + " 03:04:05.123')");
        var service =
            new UnifiedQueryService(
                List.of(new ClickHouseQueryBackend("ch", url, credentials(), "default")), "ch");
        var row =
            service
                .execute(QueryType.SQL, "SELECT * FROM " + table)
                .getResults()
                .getFirst()
                .tupleValue();
        assertEquals("18446744073709551615", row.get("u").value().toString());
        assertEquals("12345678901234567890.1234", row.get("d").value().toString());
        assertEquals(2, row.get("tags").collectionValue().size());
        assertNotNull(row.get("stamp").value());
      } finally {
        statement.execute("DROP TABLE " + table);
      }
    }
  }
}
