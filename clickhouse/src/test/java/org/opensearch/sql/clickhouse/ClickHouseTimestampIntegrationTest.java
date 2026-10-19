/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.clickhouse;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opensearch.sql.api.QueryExecutionRequest;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.executor.ExecutionEngine;
import org.opensearch.sql.executor.QueryCancellation;
import org.opensearch.sql.executor.QueryRowConsumer;
import org.opensearch.sql.executor.QueryType;

@EnabledIfEnvironmentVariable(named = "CLICKHOUSE_TEST_URL", matches = ".+")
class ClickHouseTimestampIntegrationTest {
  // Two instants share New York's autumn 01:30; the final UTC clock falls in its spring gap.
  private static final List<Instant> INSTANTS =
      List.of(
          Instant.parse("2026-10-19T02:12:46.415Z"),
          Instant.parse("2026-11-01T05:30:00.123Z"),
          Instant.parse("2026-11-01T06:30:00.123Z"),
          Instant.parse("2026-03-08T02:30:00.789Z"));

  @ParameterizedTest
  @EnumSource(QueryType.class)
  void preservesInstantsAcrossColumnTimeZonesAndExecutionPaths(QueryType language)
      throws Exception {
    String url = System.getenv("CLICKHOUSE_TEST_URL");
    String table = "uq_timestamp_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection =
            DriverManager.getConnection(url, ClickHouseIntegrationTest.credentials());
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE "
              + table
              + " (id Int32, sh DateTime64(3,'Asia/Shanghai'), utc DateTime64(3,'UTC'),"
              + " ny DateTime64(3,'America/New_York'), seconds DateTime('Asia/Shanghai'),"
              + " optional Nullable(DateTime64(3,'Asia/Shanghai')), day Date) ENGINE=Memory");
      try {
        for (int i = 0; i < INSTANTS.size(); i++) {
          String time = "fromUnixTimestamp64Milli(" + INSTANTS.get(i).toEpochMilli() + ")";
          statement.execute(
              "INSERT INTO "
                  + table
                  + " SELECT "
                  + i
                  + ","
                  + String.join(",", time, time, time, time, i == 0 ? "NULL" : time)
                  + ",toDate('2026-10-19')");
        }
        var service =
            new UnifiedQueryService(
                List.of(
                    new ClickHouseQueryBackend(
                        "ch", url, ClickHouseIntegrationTest.credentials(), "default")),
                "ch");
        String query =
            language == QueryType.SQL
                ? "SELECT * FROM " + table + " ORDER BY id"
                : "source=" + table + " | sort id";
        var rows = service.execute(language, query).getResults();
        assertRows(rows);

        var streamed = new ArrayList<ExprValue>();
        service.stream(
            null,
            language,
            query,
            new QueryRowConsumer() {
              public void schema(ExecutionEngine.Schema schema) {}

              public void row(ExprValue row) {
                streamed.add(row);
              }
            },
            new QueryCancellation());
        assertRows(streamed);

        // CAST stays local, so this also tests dates consumed by Enumerable expressions.
        String local =
            language == QueryType.SQL
                ? "SELECT CAST(sh AS STRING) AS text FROM " + table + " ORDER BY id"
                : "source=" + table + " | sort id | eval text=cast(sh as string) | fields text";
        var text = service.execute(language, local).getResults().getFirst().keyValue("text");
        assertEquals("2026-10-19 02:12:46", text.stringValue());

        String aggregate =
            language == QueryType.SQL
                ? "SELECT min(sh) AS first, max(ny) AS last FROM " + table
                : "source=" + table + " | stats min(sh) as first, max(ny) as last";
        var extrema = service.execute(language, aggregate).getResults().getFirst();
        assertEquals(
            INSTANTS.stream().min(Instant::compareTo).orElseThrow(),
            extrema.keyValue("first").timestampValue());
        assertEquals(
            INSTANTS.stream().max(Instant::compareTo).orElseThrow(),
            extrema.keyValue("last").timestampValue());

        var range =
            new QueryExecutionRequest.TimeRange(
                "sh",
                INSTANTS.getFirst(),
                INSTANTS.getFirst().plusMillis(1),
                ZoneId.of("Asia/Shanghai"));
        var request =
            new QueryExecutionRequest("ch", language, query, range, List.of(), null, null);
        var filtered = service.execute(request).getResults();
        assertEquals(1, filtered.size());
        assertEquals(INSTANTS.getFirst(), filtered.getFirst().keyValue("sh").timestampValue());
        var histogram =
            service.execute(
                request.withHistogram(
                    new QueryExecutionRequest.Histogram("sh", "1h", range.timeZone())));
        assertEquals(
            Instant.parse("2026-10-19T02:00:00Z"),
            histogram.getResults().getFirst().keyValue("__search_bucket").timestampValue());
      } finally {
        statement.execute("DROP TABLE " + table);
      }
    }
  }

  private static void assertRows(List<ExprValue> rows) {
    assertEquals(INSTANTS.size(), rows.size());
    for (int i = 0; i < rows.size(); i++) {
      ExprValue row = rows.get(i);
      Instant instant = INSTANTS.get(i);
      for (String field : List.of("sh", "utc", "ny")) {
        assertEquals(instant, row.keyValue(field).timestampValue(), field + " row " + i);
      }
      assertEquals(
          instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
          row.keyValue("seconds").timestampValue());
      if (i == 0) assertTrue(row.keyValue("optional").isNull());
      else assertEquals(instant, row.keyValue("optional").timestampValue());
      assertEquals("2026-10-19", row.keyValue("day").value());
    }
    assertEquals("2026-10-19 02:12:46.415", rows.getFirst().keyValue("sh").value());
  }
}
