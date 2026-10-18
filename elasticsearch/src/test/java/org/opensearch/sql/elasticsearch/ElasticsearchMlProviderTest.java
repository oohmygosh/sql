/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.calcite.DataContext;
import org.apache.calcite.DataContexts;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.http.HttpHost;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.BackendException;
import org.opensearch.sql.executor.QueryType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class ElasticsearchMlProviderTest {
  private static final JavaTypeFactoryImpl TYPES = new JavaTypeFactoryImpl();
  private static final RelDataType INPUT_TYPE =
      TYPES.builder().add("feature", SqlTypeName.INTEGER).build();
  private static final String ML =
      " | ml action='predict' algorithm='regression' model_id='model-1'";

  @Test
  void regressionUsesUpstreamRowsAndSupportsDownstreamPpl() throws Exception {
    try (Fixture fixture = new Fixture()) {
      var response =
          fixture.service.execute(
              QueryType.PPL,
              "source=events | eval scaled = feature * 10 | where scaled > 10 | fields scaled"
                  + ML
                  + " prediction_field='score' batch_size=1 | where score > 25 | fields scaled,"
                  + " score");
      assertEquals(1, response.getResults().size());
      var row = response.getResults().getFirst().tupleValue();
      assertEquals(30, row.get("scaled").integerValue());
      assertEquals(30.5, row.get("score").doubleValue());
      assertEquals(2, fixture.inferences.size());
      assertEquals(List.of(Map.of("scaled", 20)), fixture.inferences.get(0).get("docs"));
      assertEquals(List.of(Map.of("scaled", 30)), fixture.inferences.get(1).get("docs"));
      assertEquals(
          Map.of("regression", Map.of("results_field", "prediction")),
          fixture.inferences.getFirst().get("inference_config"));
      assertEquals(1, fixture.closedPits.get());
    }
  }

  @Test
  void classificationAppendsStringLabelsAndAllowsFiltering() throws Exception {
    try (Fixture fixture = new Fixture()) {
      var response =
          fixture.service.execute(
              QueryType.PPL,
              "source=events | fields feature"
                  + " | ml action='predict' algorithm='classification' model_id='model-1'"
                  + " prediction_field='label' | where label = 'label-2' | fields feature, label");
      assertEquals(1, response.getResults().size());
      assertEquals(
          "label-2", response.getResults().getFirst().tupleValue().get("label").stringValue());
      assertEquals(
          Map.of("classification", Map.of("results_field", "prediction")),
          fixture.inferences.getFirst().get("inference_config"));
    }
  }

  @Test
  void explainDoesNotReadRowsOrInfer() throws Exception {
    try (Fixture fixture = new Fixture()) {
      assertNotNull(
          fixture.service.explain(null, QueryType.PPL, "source=events | fields feature" + ML));
      assertTrue(fixture.inferences.isEmpty());
      assertEquals(0, fixture.searches.get());
      assertEquals(0, fixture.openedPits.get());
    }
  }

  @Test
  void inferenceIsLazyBatchedRepeatableAndSnapshotsReusedInputRows() throws Exception {
    try (Fixture fixture = new Fixture()) {
      var arguments = arguments("regression");
      arguments.put("batch_size", 2);
      var operation = fixture.backend.machineLearning().plan(arguments, INPUT_TYPE);
      assertEquals(
          SqlTypeName.DOUBLE,
          operation.outputType().getFieldList().getLast().getType().getSqlTypeName());
      for (int execution = 0; execution < 2; execution++) {
        var input = new TrackingInput(new Object[] {1}, new Object[] {2}, new Object[] {3});
        var enumerable = operation.execute(input, DataContexts.EMPTY);
        assertEquals(0, input.opened);
        int previous = fixture.inferences.size();
        try (var rows = enumerable.enumerator()) {
          assertEquals(previous, fixture.inferences.size());
          assertTrue(rows.moveNext());
          assertArrayEquals(new Object[] {1, 1.5}, rows.current());
          assertTrue(rows.moveNext());
          assertArrayEquals(new Object[] {2, 2.5}, rows.current());
          assertTrue(rows.moveNext());
          assertArrayEquals(new Object[] {3, 3.5}, rows.current());
          assertFalse(rows.moveNext());
          assertFalse(rows.moveNext());
          assertNull(rows.current());
          rows.close();
        }
        assertEquals(1, input.closed);
        assertEquals(
            List.of(Map.of("feature", 1), Map.of("feature", 2)),
            fixture.inferences.get(previous).get("docs"));
        assertEquals(
            List.of(Map.of("feature", 3)), fixture.inferences.get(previous + 1).get("docs"));
      }
    }
  }

  @Test
  void emptyInputAndClosingBeforeIterationNeverInfer() throws Exception {
    try (Fixture fixture = new Fixture()) {
      var operation = fixture.backend.machineLearning().plan(arguments("regression"), INPUT_TYPE);
      var empty = new TrackingInput();
      try (var rows = operation.execute(empty, DataContexts.EMPTY).enumerator()) {
        assertFalse(rows.moveNext());
      }
      assertEquals(1, empty.closed);
      var unstarted = new TrackingInput(new Object[] {1});
      var rows = operation.execute(unstarted, DataContexts.EMPTY).enumerator();
      rows.close();
      rows.close();
      assertFalse(rows.moveNext());
      assertEquals(1, unstarted.closed);
      assertTrue(fixture.inferences.isEmpty());
    }
  }

  @Test
  void downstreamHeadBoundsReadAheadAndClosesInput() throws Exception {
    try (Fixture fixture = new Fixture()) {
      var response =
          fixture.service.execute(
              QueryType.PPL, "source=events | fields feature" + ML + " batch_size=1 | head 1");
      assertEquals(1, response.getResults().size());
      // Calcite's takeWhile advances its input once before checking the remaining row count.
      assertEquals(2, fixture.inferences.size());
      assertEquals(List.of(Map.of("feature", 1)), fixture.inferences.get(0).get("docs"));
      assertEquals(List.of(Map.of("feature", 2)), fixture.inferences.get(1).get("docs"));
      assertEquals(1, fixture.closedPits.get());
    }
  }

  @Test
  void explicitEarlyCloseDoesNotInferAnotherBatch() throws Exception {
    try (Fixture fixture = new Fixture()) {
      var arguments = arguments("regression");
      arguments.put("batch_size", 1);
      var input = new TrackingInput(new Object[] {1}, new Object[] {2}, new Object[] {3});
      var operation = fixture.backend.machineLearning().plan(arguments, INPUT_TYPE);
      try (var rows = operation.execute(input, DataContexts.EMPTY).enumerator()) {
        assertTrue(rows.moveNext());
        rows.close();
        assertFalse(rows.moveNext());
      }
      assertEquals(1, fixture.inferences.size());
      assertEquals(1, input.closed);
    }
  }

  static Stream<Map<String, Object>> invalidArguments() {
    List<Map<String, Object>> cases = new ArrayList<>();
    for (var invalid :
        List.of(
            Map.entry("action", "train"),
            Map.entry("algorithm", "kmeans"),
            Map.entry("model_id", ""),
            Map.entry("model_id", "../other"),
            Map.entry("model_id", "*"),
            Map.entry("model_id", ".."),
            Map.entry("batch_size", 0),
            Map.entry("batch_size", 1001),
            Map.entry("batch_size", 1.5),
            Map.entry("batch_size", "1"),
            Map.entry("prediction_field", "FEATURE"),
            Map.entry("prediction_field", " "),
            Map.entry("unknown", "value"))) {
      var arguments = arguments("regression");
      arguments.put(invalid.getKey(), invalid.getValue());
      cases.add(arguments);
    }
    var missing = arguments("regression");
    missing.remove("model_id");
    cases.add(missing);
    return cases.stream();
  }

  @ParameterizedTest
  @MethodSource("invalidArguments")
  void invalidArgumentsFailBeforeAnyHttp(Map<String, Object> arguments) throws Exception {
    try (Fixture fixture = new Fixture()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.backend.machineLearning().plan(arguments, INPUT_TYPE));
      assertEquals(0, fixture.requests.get());
    }
  }

  static Stream<Map<String, Object>> invalidResponses() {
    return Stream.of(
        Map.of("error", "inference failed"),
        Map.of("inference_results", List.of()),
        Map.of("inference_results", List.of(Map.of("prediction", 1), Map.of("prediction", 2))),
        Map.of("inference_results", List.of("invalid")),
        Map.of("inference_results", List.of(Map.of("error", "bad feature"))),
        Map.of("inference_results", List.of(Map.of("warning", "missing feature"))),
        Map.of("inference_results", List.of(Map.of())),
        Map.of("inference_results", List.of(Map.of("prediction", "1.5"))),
        Map.of("inference_results", List.of(Map.of("prediction", List.of(1)))));
  }

  @ParameterizedTest
  @MethodSource("invalidResponses")
  void invalidInferenceResultsFailAndCloseInput(Map<String, Object> response) throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.inferenceResponse = request -> response;
      var arguments = arguments("regression");
      arguments.put("batch_size", 1);
      var input = new TrackingInput(new Object[] {1}, new Object[] {2});
      try (var rows =
          fixture
              .backend
              .machineLearning()
              .plan(arguments, INPUT_TYPE)
              .execute(input, DataContexts.EMPTY)
              .enumerator()) {
        assertThrows(IllegalStateException.class, rows::moveNext);
        assertFalse(rows.moveNext());
        assertNull(rows.current());
      }
      assertEquals(1, input.closed);
      assertEquals(1, fixture.inferences.size());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 404})
  void nativeHttpErrorsRetainTheirCauseAndCloseInput(int status) throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.inferenceStatus = status;
      var arguments = arguments("regression");
      arguments.put("batch_size", 1);
      var input = new TrackingInput(new Object[] {1}, new Object[] {2});
      try (var rows =
          fixture
              .backend
              .machineLearning()
              .plan(arguments, INPUT_TYPE)
              .execute(input, DataContexts.EMPTY)
              .enumerator()) {
        var failure = assertThrows(BackendException.class, rows::moveNext);
        var cause = assertInstanceOf(ResponseException.class, failure.getCause());
        assertEquals(status, cause.getResponse().getStatusLine().getStatusCode());
      }
      assertEquals(1, input.closed);
    }
  }

  @Test
  void cleanupFailureIsSuppressedWithoutReplacingInferenceFailure() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.inferenceResponse = request -> Map.of("inference_results", List.of());
      var arguments = arguments("regression");
      arguments.put("batch_size", 1);
      var input = new TrackingInput(new Object[] {1});
      input.failClose = true;
      try (var rows =
          fixture
              .backend
              .machineLearning()
              .plan(arguments, INPUT_TYPE)
              .execute(input, DataContexts.EMPTY)
              .enumerator()) {
        var failure = assertThrows(IllegalStateException.class, rows::moveNext);
        assertTrue(failure.getMessage().contains("result count"));
        assertEquals("input close failed", failure.getSuppressed()[0].getMessage());
      }
      assertEquals(1, input.closed);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cancellationBeforeNextRowOrAfterHttpClosesInput(boolean duringHttp) throws Exception {
    try (Fixture fixture = new Fixture()) {
      AtomicBoolean cancelled = new AtomicBoolean();
      DataContext context =
          DataContexts.of(Map.of(DataContext.Variable.CANCEL_FLAG.camelName, cancelled));
      var arguments = arguments("regression");
      arguments.put("batch_size", 1);
      var input = new TrackingInput(new Object[] {1}, new Object[] {2});
      if (duringHttp) fixture.afterInference = () -> cancelled.set(true);
      try (var rows =
          fixture
              .backend
              .machineLearning()
              .plan(arguments, INPUT_TYPE)
              .execute(input, context)
              .enumerator()) {
        if (!duringHttp) {
          assertTrue(rows.moveNext());
          cancelled.set(true);
        }
        assertThrows(CancellationException.class, rows::moveNext);
        assertFalse(rows.moveNext());
      }
      assertEquals(1, input.closed);
      assertEquals(1, fixture.inferences.size());
    }
  }

  @Test
  void threadInterruptionStopsInferenceAndClosesInput() throws Exception {
    try (Fixture fixture = new Fixture()) {
      var input = new TrackingInput(new Object[] {1});
      try (var rows =
          fixture
              .backend
              .machineLearning()
              .plan(arguments("regression"), INPUT_TYPE)
              .execute(input, DataContexts.EMPTY)
              .enumerator()) {
        Thread.currentThread().interrupt();
        try {
          assertThrows(CancellationException.class, rows::moveNext);
        } finally {
          Thread.interrupted();
        }
      }
      assertEquals(1, input.closed);
      assertTrue(fixture.inferences.isEmpty());
    }
  }

  @Test
  void temporalAndNestedValuesHaveExplicitJsonEncoding() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.inferenceResponse =
          request -> Map.of("inference_results", List.of(Map.of("prediction", 2)));
      var type =
          TYPES
              .builder()
              .add("at", SqlTypeName.TIMESTAMP)
              .add("day", SqlTypeName.DATE)
              .add("time", SqlTypeName.TIME)
              .add("payload", SqlTypeName.ANY)
              .build();
      var nested = Map.of("items", Arrays.asList("a", null, Map.of("flag", true)));
      var input =
          new TrackingInput(
              new Object[] {
                Instant.parse("2025-01-02T03:04:05Z").toEpochMilli(),
                (int) LocalDate.parse("2025-01-02").toEpochDay(),
                3_600_000,
                nested
              });
      try (var rows =
          fixture
              .backend
              .machineLearning()
              .plan(arguments("regression"), type)
              .execute(input, DataContexts.EMPTY)
              .enumerator()) {
        assertTrue(rows.moveNext());
        assertEquals(nested, rows.current()[3]);
        assertEquals(2.0, rows.current()[4]);
      }
      assertEquals(
          List.of(
              Map.of(
                  "at",
                  "2025-01-02T03:04:05Z",
                  "day",
                  "2025-01-02",
                  "time",
                  "01:00",
                  "payload",
                  nested)),
          fixture.inferences.getFirst().get("docs"));
    }
  }

  @Test
  void unsupportedSchemaAndRuntimeValuesFailBeforeInference() throws Exception {
    try (Fixture fixture = new Fixture()) {
      var binary = TYPES.builder().add("feature", SqlTypeName.VARBINARY).build();
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.backend.machineLearning().plan(arguments("regression"), binary));
      var type = TYPES.builder().add("feature", SqlTypeName.ANY).build();
      for (Object value : List.of(new Object(), Double.NaN, Map.of(1, "bad key"))) {
        var input = new TrackingInput(new Object[] {value});
        try (var rows =
            fixture
                .backend
                .machineLearning()
                .plan(arguments("regression"), type)
                .execute(input, DataContexts.EMPTY)
                .enumerator()) {
          assertThrows(IllegalArgumentException.class, rows::moveNext);
        }
        assertEquals(1, input.closed);
      }
      assertEquals(0, fixture.requests.get());
    }
  }

  @Test
  void classificationConvertsOnlySimpleLabelsToStrings() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.inferenceResponse =
          request ->
              Map.of(
                  "inference_results",
                  List.of(
                      Map.of("prediction", 7),
                      Map.of("prediction", true),
                      Map.of("prediction", "yes")));
      var operation =
          fixture.backend.machineLearning().plan(arguments("classification"), INPUT_TYPE);
      assertEquals(
          SqlTypeName.VARCHAR,
          operation.outputType().getFieldList().getLast().getType().getSqlTypeName());
      var input = new TrackingInput(new Object[] {1}, new Object[] {2}, new Object[] {3});
      assertEquals(
          List.of("7", "true", "yes"),
          operation.execute(input, DataContexts.EMPTY).select(row -> row[1]).toList());
      assertEquals(1, input.closed);
      fixture.inferenceResponse =
          request ->
              Map.of("inference_results", List.of(Map.of("prediction", Map.of("label", "bad"))));
      var invalid = new TrackingInput(new Object[] {1});
      try (var rows = operation.execute(invalid, DataContexts.EMPTY).enumerator()) {
        assertThrows(IllegalStateException.class, rows::moveNext);
      }
      assertEquals(1, invalid.closed);
    }
  }

  private static Map<String, Object> arguments(String algorithm) {
    return new LinkedHashMap<>(
        Map.of("action", "predict", "algorithm", algorithm, "model_id", "model-1"));
  }

  private static final class TrackingInput extends AbstractEnumerable<Object[]> {
    private final Object[][] rows;
    private int opened;
    private int closed;
    private boolean failClose;

    private TrackingInput(Object[]... rows) {
      this.rows = rows;
    }

    @Override
    public Enumerator<Object[]> enumerator() {
      opened++;
      return new Enumerator<>() {
        private int position;
        private final Object[] reused =
            rows.length == 0 ? new Object[0] : new Object[rows[0].length];

        @Override
        public Object[] current() {
          return reused;
        }

        @Override
        public boolean moveNext() {
          if (position == rows.length) return false;
          System.arraycopy(rows[position++], 0, reused, 0, reused.length);
          return true;
        }

        @Override
        public void reset() {
          throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
          closed++;
          if (failClose) throw new IllegalStateException("input close failed");
        }
      };
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final RestClient client;
    private final ElasticsearchQueryBackend backend;
    private final UnifiedQueryService service;
    private final List<Map<String, Object>> inferences = new CopyOnWriteArrayList<>();
    private final List<String> unexpected = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger searches = new AtomicInteger();
    private final AtomicInteger openedPits = new AtomicInteger();
    private final AtomicInteger closedPits = new AtomicInteger();
    private volatile int inferenceStatus = 200;
    private volatile Runnable afterInference = () -> {};
    private volatile Function<Map<String, Object>, Map<String, Object>> inferenceResponse =
        Fixture::predict;

    private Fixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext("/", this::handle);
      server.start();
      client =
          RestClient.builder(
                  new HttpHost(
                      server.getAddress().getAddress().getHostAddress(),
                      server.getAddress().getPort(),
                      "http"))
              .build();
      backend = new ElasticsearchQueryBackend("es", client);
      service = new UnifiedQueryService(List.of(backend), "es");
    }

    private static Map<String, Object> predict(Map<String, Object> request) {
      boolean regression = ((Map<?, ?>) request.get("inference_config")).containsKey("regression");
      List<?> docs = (List<?>) request.get("docs");
      return Map.of(
          "inference_results",
          docs.stream()
              .map(
                  doc -> {
                    var values = (Map<?, ?>) doc;
                    Number feature = (Number) values.values().iterator().next();
                    return Map.of(
                        "prediction",
                        regression ? feature.doubleValue() + 0.5 : "label-" + feature.intValue());
                  })
              .toList());
    }

    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        requests.incrementAndGet();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        int status = 200;
        Map<String, Object> response;
        if (method.equals("GET") && path.equals("/events/_mapping")) {
          response =
              Map.of(
                  "events",
                  Map.of(
                      "mappings",
                      Map.of("properties", Map.of("feature", Map.of("type", "integer")))));
        } else if (method.equals("POST") && path.equals("/events/_pit")) {
          openedPits.incrementAndGet();
          response = Map.of("id", "ml-pit");
        } else if (method.equals("POST") && path.equals("/_search")) {
          searches.incrementAndGet();
          var request =
              mapper.readValue(
                  exchange.getRequestBody(), new TypeReference<Map<String, Object>>() {});
          List<Map<String, Object>> hits = new ArrayList<>();
          if (!request.containsKey("search_after")) {
            for (int i = 1; i <= 3; i++)
              hits.add(
                  Map.of(
                      "_source",
                      Map.of("feature", i),
                      "_index",
                      "events",
                      "_id",
                      Integer.toString(i),
                      "sort",
                      List.of(i)));
          }
          response = Map.of("hits", Map.of("hits", hits));
        } else if (method.equals("DELETE") && path.equals("/_pit")) {
          closedPits.incrementAndGet();
          response = Map.of("succeeded", true);
        } else if (method.equals("POST") && path.equals("/_ml/trained_models/model-1/_infer")) {
          var request =
              mapper.readValue(
                  exchange.getRequestBody(), new TypeReference<Map<String, Object>>() {});
          inferences.add(request);
          status = inferenceStatus;
          response =
              status == 200
                  ? inferenceResponse.apply(request)
                  : Map.of(
                      "error",
                      Map.of("type", "inference_error", "reason", "model unavailable"),
                      "status",
                      status);
          afterInference.run();
        } else {
          unexpected.add(method + " " + path);
          status = 404;
          response = Map.of("error", "Unexpected request: " + method + " " + path);
        }
        byte[] json = mapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, json.length);
        exchange.getResponseBody().write(json);
      }
    }

    @Override
    public void close() throws IOException {
      try {
        client.close();
      } finally {
        server.stop(0);
      }
      assertEquals(List.of(), unexpected);
    }
  }
}
