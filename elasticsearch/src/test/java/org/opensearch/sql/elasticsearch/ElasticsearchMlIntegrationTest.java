/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.executor.QueryType;
import tools.jackson.databind.ObjectMapper;

/**
 * Real Elasticsearch tree inference through the complete PPL pipeline. Requires an ML-capable
 * licensed test cluster and permission to create/delete trained models and a synthetic index. Set
 * ELASTICSEARCH_TEST_URL and ELASTICSEARCH_ML_TEST_ENABLED=true to opt in; optional credentials are
 * the same as for {@link ElasticsearchIntegrationTest}. Fixtures own only unique uq-ml-* names.
 */
@EnabledIfEnvironmentVariable(named = "ELASTICSEARCH_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "ELASTICSEARCH_ML_TEST_ENABLED", matches = "true")
class ElasticsearchMlIntegrationTest {
  @Test
  void regressionInfersTransformedIndexRowsAndSupportsDownstreamPpl() throws Exception {
    try (var fixture = new Fixture()) {
      String index = fixture.createIndex();
      String model = fixture.createModel("regression");
      var response =
          fixture.service.execute(
              QueryType.PPL,
              "source=`"
                  + index
                  + "` | where raw > 1 | eval feature=raw * 10 | fields id,feature | sort id"
                  + ml("regression", model, "score")
                  + " batch_size=2 | where score > 5 | eval adjusted=score + 0.5"
                  + " | fields id,feature,score,adjusted | sort id");
      assertEquals(2, response.getResults().size());
      for (int i = 0; i < 2; i++) {
        var row = response.getResults().get(i).tupleValue();
        assertEquals(i + 2, row.get("id").integerValue());
        assertEquals((i + 3) * 10, row.get("feature").integerValue());
        assertEquals(9.5, row.get("score").doubleValue());
        assertEquals(10.0, row.get("adjusted").doubleValue());
      }
    }
  }

  @Test
  void classificationInfersBothLabelsAndSupportsFilteringAndAggregation() throws Exception {
    try (var fixture = new Fixture()) {
      String model = fixture.createModel("classification");
      String query =
          "makeresults format=json data='[{\"id\":1,\"raw\":2},{\"id\":2,\"raw\":3},"
              + "{\"id\":3,\"raw\":4}]' | eval feature=raw * 10 | fields id,feature"
              + ml("classification", model, "label")
              + " batch_size=2";
      var rows = fixture.service.execute(QueryType.PPL, query + " | sort id").getResults();
      assertEquals(3, rows.size());
      for (int i = 0; i < 3; i++) {
        var row = rows.get(i).tupleValue();
        assertEquals(i + 1, row.get("id").integerValue());
        assertEquals((i + 2) * 10, row.get("feature").integerValue());
        assertEquals(i == 0 ? "low" : "high", row.get("label").stringValue());
      }
      var grouped =
          fixture.service.execute(
              QueryType.PPL,
              query + " | where label='high' | stats count() as n by label | fields label,n");
      assertEquals(1, grouped.getResults().size());
      var group = grouped.getResults().getFirst().tupleValue();
      assertEquals("high", group.get("label").stringValue());
      assertEquals(2L, group.get("n").longValue());
    }
  }

  @Test
  void nonexistentModelIsLazyForEmptyInputAndExplainButFailsForActualRows() throws Exception {
    try (var fixture = new Fixture()) {
      String input = "makeresults format=json data='[{\"feature\":20}]'";
      String inference = ml("regression", fixture.prefix + "-missing", "prediction");
      assertNotNull(fixture.service.explain(null, QueryType.PPL, input + inference));
      assertTrue(
          fixture
              .service
              .execute(QueryType.PPL, input + " | where feature < 0" + inference)
              .getResults()
              .isEmpty());
      var failure =
          assertThrows(
              RuntimeException.class,
              () -> fixture.service.execute(QueryType.PPL, input + inference));
      assertNativeStatus(404, failure);
    }
  }

  @Test
  void nativeModelTypeMismatchRetainsTheElasticsearchError() throws Exception {
    try (var fixture = new Fixture()) {
      String model = fixture.createModel("regression");
      var failure =
          assertThrows(
              RuntimeException.class,
              () ->
                  fixture.service.execute(
                      QueryType.PPL,
                      "makeresults format=json data='[{\"feature\":20}]'"
                          + ml("classification", model, "label")));
      assertNativeStatus(400, failure);
    }
  }

  private static String ml(String algorithm, String model, String prediction) {
    return " | ml action='predict' algorithm='"
        + algorithm
        + "' model_id='"
        + model
        + "' prediction_field='"
        + prediction
        + "'";
  }

  private static void assertNativeStatus(int expected, Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ResponseException response) {
        assertEquals(expected, response.getResponse().getStatusLine().getStatusCode());
        return;
      }
    }
    throw new AssertionError("The native Elasticsearch error was not preserved", failure);
  }

  private static final class Fixture implements AutoCloseable {
    private final String prefix = "uq-ml-" + UUID.randomUUID();
    private final RestClient client = ElasticsearchIntegrationTest.authenticatedClient();
    private final UnifiedQueryService service =
        new UnifiedQueryService(List.of(new ElasticsearchQueryBackend("es", client)), "es");
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> resources = new ArrayList<>();

    private String createIndex() throws IOException {
      String index = prefix + "-input";
      // Register exact owned names before creation so cleanup also covers an ambiguous HTTP
      // failure.
      resources.add("/" + index);
      request(
          "PUT",
          "/" + index,
          Map.of(
              "settings",
              Map.of("number_of_shards", 1, "number_of_replicas", 0),
              "mappings",
              Map.of(
                  "properties",
                  Map.of("id", Map.of("type", "integer"), "raw", Map.of("type", "integer")))));
      int[] features = {2, 3, 4, 0};
      for (int i = 0; i < features.length; i++) {
        request("PUT", "/" + index + "/_doc/" + (i + 1), Map.of("id", i + 1, "raw", features[i]));
      }
      request("POST", "/" + index + "/_refresh", null);
      return index;
    }

    private String createModel(String algorithm) throws IOException {
      String model = prefix + "-" + algorithm;
      boolean classification = algorithm.equals("classification");
      Map<String, Object> tree = new LinkedHashMap<>();
      tree.put("feature_names", List.of("feature"));
      tree.put("target_type", algorithm);
      tree.put(
          "tree_structure",
          List.of(
              Map.of(
                  "node_index", 0,
                  "split_feature", 0,
                  "threshold", 25.0,
                  "decision_type", "lte",
                  "default_left", true,
                  "left_child", 1,
                  "right_child", 2),
              Map.of("node_index", 1, "leaf_value", List.of(classification ? 0.0 : 1.25)),
              Map.of("node_index", 2, "leaf_value", List.of(classification ? 1.0 : 9.5))));
      if (classification) tree.put("classification_labels", List.of("low", "high"));
      String path = "/_ml/trained_models/" + model;
      resources.add(path);
      request(
          "PUT",
          path,
          Map.of(
              "input", Map.of("field_names", List.of("feature")),
              "inference_config", Map.of(algorithm, Map.of()),
              "definition", Map.of("trained_model", Map.of("tree", tree))));
      return model;
    }

    private void request(String method, String path, Map<String, Object> body) throws IOException {
      Request request = new Request(method, path);
      if (body != null) request.setJsonEntity(mapper.writeValueAsString(body));
      client.performRequest(request);
    }

    @Override
    public void close() throws IOException {
      IOException failure = null;
      for (String resource : resources.reversed()) {
        try {
          request("DELETE", resource, null);
        } catch (IOException cleanup) {
          if (cleanup instanceof ResponseException response
              && response.getResponse().getStatusLine().getStatusCode() == 404) continue;
          if (failure == null) failure = cleanup;
          else failure.addSuppressed(cleanup);
        }
      }
      try {
        client.close();
      } catch (IOException cleanup) {
        if (failure == null) failure = cleanup;
        else failure.addSuppressed(cleanup);
      }
      if (failure != null) throw failure;
    }
  }
}
