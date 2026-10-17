/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.api.corpus.BackendQueryCorpus;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "ELASTICSEARCH_TEST_URL", matches = ".+")
class ElasticsearchCorpusTest {
  private RestClient client;
  private UnifiedQueryService service;
  private final BackendQueryCorpus corpus = new BackendQueryCorpus();
  private final List<String> created = new ArrayList<>();
  private final String prefix = "uq-matrix-" + UUID.randomUUID();
  private final String events = prefix + "-events";
  private final String departments = prefix + "-departments";

  @BeforeAll
  void setup() throws Exception {
    client = ElasticsearchIntegrationTest.authenticatedClient();
    try {
      for (boolean event : List.of(true, false)) {
        String index = event ? events : departments;
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        for (String column :
            event ? BackendQueryCorpus.COLUMNS : BackendQueryCorpus.DEPARTMENT_COLUMNS) {
          String type =
              switch (column) {
                case "id", "status", "budget" -> "integer";
                case "amount" -> "double";
                case "bytes" -> "long";
                case "ts" -> "date";
                case "message" -> "text";
                default -> "keyword";
              };
          properties.put(column, Map.of("type", type));
        }
        Request create = new Request("PUT", "/" + index);
        create.setJsonEntity(
            BackendQueryCorpus.json(Map.of("mappings", Map.of("properties", properties))));
        client.performRequest(create);
        created.add(index);
        StringBuilder bulk = new StringBuilder();
        for (String row : BackendQueryCorpus.jsonLines(event, false).split("\n"))
          bulk.append("{\"index\":{}}\n").append(row).append('\n');
        Request put = new Request("POST", "/" + index + "/_bulk?refresh=true");
        put.setJsonEntity(bulk.toString());
        String response =
            org.apache.http.util.EntityUtils.toString(client.performRequest(put).getEntity());
        if (new com.google.gson.JsonParser()
            .parse(response)
            .getAsJsonObject()
            .get("errors")
            .getAsBoolean()) throw new AssertionError("Fixture bulk insert failed: " + response);
      }
      service =
          new UnifiedQueryService(
              List.of(
                  new ElasticsearchQueryBackend(
                      "es", client, false, Map.of("tags", "array<keyword>"))),
              "es");
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
      for (String index : created) {
        try {
          client.performRequest(new Request("DELETE", "/" + index));
        } catch (Exception e) {
          if (failure == null) failure = e;
          else failure.addSuppressed(e);
        }
      }
      created.clear();
    } finally {
      if (client != null) {
        client.close();
        client = null;
      }
      corpus.writeReport(Path.of("build/reports/corpus/elasticsearch.json"));
    }
    if (failure != null) throw failure;
  }
}
