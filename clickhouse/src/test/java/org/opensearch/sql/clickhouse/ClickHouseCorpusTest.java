/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.clickhouse;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.api.corpus.BackendQueryCorpus;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "CLICKHOUSE_TEST_URL", matches = ".+")
class ClickHouseCorpusTest {
  private Connection connection;
  private UnifiedQueryService service;
  private final BackendQueryCorpus corpus = new BackendQueryCorpus();
  private final List<String> created = new ArrayList<>();
  private final String prefix = "uq_matrix_" + UUID.randomUUID().toString().replace("-", "");
  private final String events = prefix + "_events";
  private final String departments = prefix + "_departments";

  @BeforeAll
  void setup() throws Exception {
    String url = System.getenv("CLICKHOUSE_TEST_URL");
    connection = DriverManager.getConnection(url, ClickHouseIntegrationTest.credentials());
    try (var statement = connection.createStatement()) {
      for (boolean event : List.of(true, false)) {
        String table = event ? events : departments;
        String columns =
            event
                ? "id Int32,team Nullable(String),username String,status Int32,amount"
                    + " Nullable(Float64),bytes Int64,message String,tags Array(String),ts"
                    + " DateTime64(3,'UTC'),payload String"
                : "team Nullable(String),owner String,budget Int32";
        statement.execute("CREATE TABLE " + table + " (" + columns + ") ENGINE=Memory");
        created.add(table);
        statement.execute(
            "INSERT INTO "
                + table
                + " FORMAT JSONEachRow\n"
                + BackendQueryCorpus.jsonLines(event, true));
      }
      service =
          new UnifiedQueryService(
              List.of(
                  new ClickHouseQueryBackend(
                      "ch", url, ClickHouseIntegrationTest.credentials(), "default")),
              "ch");
    } catch (Exception | AssertionError e) {
      cleanup();
      throw e;
    }
  }

  @TestFactory
  Stream<DynamicTest> queryCorpus() {
    return BackendQueryCorpus.cases().stream()
        .map(
            query ->
                DynamicTest.dynamicTest(
                    query.id, () -> corpus.verify(query, service, events, departments)));
  }

  @TestFactory
  Stream<DynamicTest> grammarBoundaries() {
    return BackendQueryCorpus.grammarBoundaries().stream()
        .map(
            query ->
                DynamicTest.dynamicTest(
                    query.id, () -> corpus.verifyUnsupported(query, service, events, departments)));
  }

  @AfterAll
  void cleanup() throws Exception {
    Exception failure = null;
    try {
      if (connection != null)
        try (var statement = connection.createStatement()) {
          for (String table : created) {
            try {
              statement.execute("DROP TABLE " + table);
            } catch (Exception e) {
              if (failure == null) failure = e;
              else failure.addSuppressed(e);
            }
          }
          created.clear();
        }
    } finally {
      if (connection != null) {
        connection.close();
        connection = null;
      }
      corpus.writeReport(Path.of("build/reports/corpus/clickhouse.json"));
    }
    if (failure != null) throw failure;
  }
}
