/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opensearch.sql.data.type.ExprCoreType.DOUBLE;
import static org.opensearch.sql.data.type.ExprCoreType.LONG;
import static org.opensearch.sql.data.type.ExprCoreType.STRING;
import static org.opensearch.sql.data.type.ExprCoreType.TIMESTAMP;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.QueryType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class ElasticsearchSpanPushdownTest {
  private static final String INDEX = "events-*";
  private static final long FEBRUARY_28 = Instant.parse("2024-02-28T00:00:00Z").toEpochMilli();
  private static final long FEBRUARY_29 = Instant.parse("2024-02-29T00:00:00Z").toEpochMilli();
  private static final long MARCH_1 = Instant.parse("2024-03-01T00:00:00Z").toEpochMilli();

  @ParameterizedTest
  @CsvSource({
    "5ms, fixed_interval, 5ms",
    "10s, fixed_interval, 10s",
    "15m, fixed_interval, 15m",
    "6h, fixed_interval, 6h",
    "2d, fixed_interval, 2d",
    "1w, calendar_interval, 1w",
    "1month, calendar_interval, 1M",
    "1q, calendar_interval, 1q",
    "1y, calendar_interval, 1y"
  })
  void supportedIntervalsExplainAndSendDateHistogramsWithoutInventingEmptyRows(
      String span, String intervalKind, String interval) throws Exception {
    try (var fixture = new ElasticsearchFixture("date", false, List.of(), List.of(emptyPage()))) {
      String commands = " | stats count() as total by span(event_time, " + span + ") as bucket";
      String physical = fixture.explain(commands);

      assertNativePlan(physical);
      assertTrue(physical.contains(intervalKind), physical);
      assertTrue(physical.contains(interval), physical);
      fixture.assertExplainOnly();

      QueryResponse response = fixture.query(commands);

      assertTrue(response.getResults().isEmpty());
      assertNull(response.getError());
      assertEquals(
          List.of("total", "bucket"),
          response.getSchema().getColumns().stream().map(column -> column.getName()).toList());
      assertEquals(
          List.of(LONG, TIMESTAMP),
          response.getSchema().getColumns().stream().map(column -> column.getExprType()).toList());
      assertEquals(1, fixture.searches.size());
      Map<String, Object> request = fixture.searches.getFirst();
      assertCompositeRequest(request, List.of(dateSource("g0", intervalKind, interval)), Map.of());
      assertFalse(composite(request).containsKey("after"));
      assertTrue(containsExists(request.get("query"), "event_time"));
      fixture.assertRequestsComplete();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"date", "date_nanos"})
  void mixedGroupsAndMetricsPreserveAliasesFieldOrderNullsAndCompositePagination(String dateType)
      throws Exception {
    String commands =
        " | fields latency, event_time, category, amount"
            + " | rename event_time as ts, category as label, latency as duration, amount as cost"
            + " | stats count() as total, avg(duration) as mean, sum(cost) as spent,"
            + " count(cost) as available by label, span(ts, 1d) as day"
            + " | fields day, spent, label, total, mean, available | sort day, label";
    // Deliberately differs from the last emitted key: clients must reuse after_key verbatim.
    Map<String, Object> firstAfter = Map.of("g0", "alpha", "g1", FEBRUARY_29 - 1);
    Map<String, Object> secondAfter = Map.of("g0", "beta", "g1", MARCH_1);
    List<Map<String, Object>> pages =
        List.of(
            page(
                List.of(
                    bucket(null, MARCH_1, 1, 6.0, 7.0, 1),
                    bucket("alpha", FEBRUARY_28, 1, 2.0, 10.0, 1)),
                firstAfter),
            page(
                List.of(
                    bucket("alpha", FEBRUARY_29, 2, 6.0, 20.0, 1),
                    bucket("beta", MARCH_1, 1, null, 0.0, 0)),
                secondAfter),
            emptyPage());
    try (var pushed = new ElasticsearchFixture(dateType, false, documents(), pages);
        var local = new ElasticsearchFixture(dateType, true, documents(), List.of())) {
      assertNativePlan(pushed.explain(commands));
      assertLocalPlan(local.explain(commands));
      pushed.assertExplainOnly();
      local.assertExplainOnly();

      QueryResponse nativeResult = pushed.query(commands);
      QueryResponse localResult = local.query(commands);

      assertNull(nativeResult.getError());
      assertNull(localResult.getError());
      assertEquals(localResult.getSchema(), nativeResult.getSchema());
      assertEquals(localResult.getResults(), nativeResult.getResults());
      assertEquals(4, nativeResult.getResults().size());
      List<String> names = List.of("day", "spent", "label", "total", "mean", "available");
      assertEquals(
          names,
          nativeResult.getSchema().getColumns().stream().map(column -> column.getName()).toList());
      assertEquals(
          List.of(TIMESTAMP, DOUBLE, STRING, LONG, DOUBLE, LONG),
          nativeResult.getSchema().getColumns().stream()
              .map(column -> column.getExprType())
              .toList());
      for (ExprValue result : nativeResult.getResults()) {
        assertEquals(names, new ArrayList<>(result.tupleValue().keySet()));
        assertEquals(TIMESTAMP, result.tupleValue().get("day").type());
        assertEquals(LONG, result.tupleValue().get("total").type());
      }
      assertRow(nativeResult, 0, FEBRUARY_28, "alpha", 1, 2.0, 10.0, 1);
      assertRow(nativeResult, 1, FEBRUARY_29, "alpha", 2, 6.0, 20.0, 1);
      assertRow(nativeResult, 2, MARCH_1, null, 1, 6.0, 7.0, 1);
      assertRow(nativeResult, 3, MARCH_1, "beta", 1, null, null, 0);

      List<Object> sources =
          List.of(
              Map.of("g0", Map.of("terms", Map.of("field", "category", "missing_bucket", true))),
              dateSource("g1", "fixed_interval", "1d"));
      // Calcite reduces AVG to SUM/COUNT and shares the COUNT used by SUM and count(cost).
      Map<String, Object> metrics =
          Map.of(
              "a1", Map.of("sum", Map.of("field", "latency")),
              "n1", Map.of("value_count", Map.of("field", "latency")),
              "a2", Map.of("value_count", Map.of("field", "latency")),
              "a3", Map.of("sum", Map.of("field", "amount")),
              "a4", Map.of("value_count", Map.of("field", "amount")));
      assertEquals(3, pushed.searches.size());
      for (Map<String, Object> request : pushed.searches) {
        assertCompositeRequest(request, sources, metrics);
        assertTrue(containsExists(request.get("query"), "event_time"));
      }
      assertFalse(composite(pushed.searches.get(0)).containsKey("after"));
      assertEquals(firstAfter, composite(pushed.searches.get(1)).get("after"));
      assertEquals(secondAfter, composite(pushed.searches.get(2)).get("after"));
      local.assertDocumentSearches();
      pushed.assertRequestsComplete();
      local.assertRequestsComplete();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        " | stats count() by span(event_time, 2w)",
        " | stats count() by span(event_time, 2month)",
        " | stats count() by span(event_time, 2q)",
        " | stats count() by span(event_time, 2y)",
        " | head 2 | stats count() by span(event_time, 1d)",
        " | eval adjusted = amount * 2 | stats avg(adjusted) by span(event_time, 1d)",
        " | stats sum(large) by span(event_time, 1d)",
        " | stats min(category) by span(event_time, 1d)",
        " | stats max(category) by span(event_time, 1d)",
        " | where length(category) > 1 | stats count() by span(event_time, 1d)"
      })
  void unsupportedSpansMetricsAndPreAggregationOperationsRemainLocal(String commands)
      throws Exception {
    try (var fixture = new ElasticsearchFixture("date", false, List.of(), List.of())) {
      assertLocalPlan(fixture.explain(commands));
      fixture.assertExplainOnly();
    }
  }

  @ParameterizedTest
  @CsvSource({"-1d,not a valid term", "0d,Zero or negative time interval not supported"})
  void invalidIntervalsAreRejectedBeforeAnySearch(String interval, String message)
      throws Exception {
    try (var fixture = new ElasticsearchFixture("date", false, List.of(), List.of())) {
      RuntimeException error =
          assertThrows(
              RuntimeException.class,
              () -> fixture.query(" | stats count() by span(event_time, " + interval + ")"));
      assertTrue(error.getMessage().contains(message), error.getMessage());
      assertTrue(fixture.searches.isEmpty());
      assertTrue(fixture.openedPits.isEmpty());
      assertTrue(fixture.closedPits.isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"array", "mixed", "modifier"})
  void unsafeOrdinaryGroupFieldsPreventSpanAggregationPushdown(String fieldKind) throws Exception {
    Map<String, Object> properties = properties("date", false);
    Map<String, Object> mappings = new LinkedHashMap<>();
    Map<String, String> declaredTypes = Map.of();
    switch (fieldKind) {
      case "array" -> declaredTypes = Map.of("category", "array<keyword>");
      case "modifier" -> properties.put("category", Map.of("type", "keyword", "ignore_above", 4));
      case "mixed" -> {
        Map<String, Object> other = properties("date", false);
        other.put("category", Map.of("type", "long"));
        mappings.put("events-2", mapping(other));
      }
      default -> throw new IllegalArgumentException(fieldKind);
    }
    mappings.put("events-1", mapping(properties));
    try (var fixture = new ElasticsearchFixture(mappings, declaredTypes, List.of(), List.of())) {
      assertLocalPlan(fixture.explain(" | stats count() by category, span(event_time, 1d) as day"));
      fixture.assertExplainOnly();
    }
  }

  private static void assertNativePlan(String physical) {
    assertTrue(physical.contains("ElasticsearchScan"), physical);
    assertTrue(physical.contains("date_histogram"), physical);
    assertFalse(physical.contains("EnumerableAggregate"), physical);
    assertFalse(physical.contains("EnumerableSortedAggregate"), physical);
  }

  private static void assertLocalPlan(String physical) {
    assertTrue(
        physical.contains("EnumerableAggregate") || physical.contains("EnumerableSortedAggregate"),
        physical);
    assertFalse(physical.contains("date_histogram"), physical);
  }

  private static void assertCompositeRequest(
      Map<String, Object> request, List<Object> sources, Map<String, Object> metrics) {
    assertEquals(0, ((Number) request.get("size")).intValue());
    assertEquals(1000, ((Number) composite(request).get("size")).intValue());
    assertEquals(sources, composite(request).get("sources"));
    assertEquals(metrics, object(object(request.get("aggs")).get("groups")).get("aggs"));
    assertFalse(request.containsKey("search_after"));
    assertFalse(request.containsKey("sort"));
  }

  private static Map<String, Object> composite(Map<String, Object> request) {
    return object(object(object(request.get("aggs")).get("groups")).get("composite"));
  }

  private static Map<String, Object> dateSource(String key, String intervalKind, String interval) {
    return Map.of(
        key,
        Map.of(
            "date_histogram",
            Map.of("field", "event_time", intervalKind, interval, "missing_bucket", true)));
  }

  private static void assertRow(
      QueryResponse response,
      int position,
      long day,
      String label,
      long total,
      Double mean,
      Double spent,
      long available) {
    Map<String, ExprValue> row = response.getResults().get(position).tupleValue();
    assertEquals(Instant.ofEpochMilli(day), row.get("day").timestampValue());
    assertEquals(total, row.get("total").longValue());
    assertEquals(available, row.get("available").longValue());
    if (label == null) assertTrue(row.get("label").isNull());
    else assertEquals(label, row.get("label").stringValue());
    if (mean == null) assertTrue(row.get("mean").isNull());
    else {
      assertEquals(DOUBLE, row.get("mean").type());
      assertEquals(mean, row.get("mean").doubleValue());
    }
    if (spent == null) assertTrue(row.get("spent").isNull());
    else {
      assertEquals(DOUBLE, row.get("spent").type());
      assertEquals(spent, row.get("spent").doubleValue());
    }
  }

  private static Map<String, Object> bucket(
      String category, long day, long count, Double average, double sum, long nonNullCount) {
    Map<String, Object> key = new LinkedHashMap<>();
    key.put("g0", category);
    key.put("g1", day);
    long latencyCount = average == null ? 0 : count;
    return Map.of(
        "key", key,
        "doc_count", count,
        "a1", Map.of("value", average == null ? 0.0 : average * latencyCount),
        "n1", Map.of("value", latencyCount),
        "a2", Map.of("value", latencyCount),
        "a3", Map.of("value", sum),
        "a4", Map.of("value", nonNullCount));
  }

  private static Map<String, Object> page(
      List<Map<String, Object>> buckets, Map<String, Object> after) {
    return Map.of("buckets", buckets, "after_key", after);
  }

  private static Map<String, Object> emptyPage() {
    return Map.of("buckets", List.of());
  }

  private static List<Map<String, Object>> documents() {
    Map<String, Object> explicitNull = new LinkedHashMap<>();
    explicitNull.put("event_time", null);
    explicitNull.put("category", "alpha");
    explicitNull.put("amount", 999.0);
    return List.of(
        Map.of(
            "event_time",
            "2024-02-28T23:59:59.999Z",
            "category",
            "alpha",
            "amount",
            10.0,
            "latency",
            2.0),
        Map.of(
            "event_time",
            "2024-02-29T00:00:00Z",
            "category",
            "alpha",
            "amount",
            20.0,
            "latency",
            4.0),
        Map.of("event_time", "2024-02-29T12:00:00Z", "category", "alpha", "latency", 8.0),
        Map.of("event_time", "2024-03-01T00:00:00Z", "category", "beta"),
        Map.of("event_time", "2024-03-01T01:00:00Z", "amount", 7.0, "latency", 6.0),
        Map.of("category", "alpha", "amount", 999.0, "latency", 100.0),
        explicitNull);
  }

  private static Map<String, Object> properties(String dateType, boolean forceLocal) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("amount", Map.of("type", "double"));
    properties.put("category", Map.of("type", "keyword"));
    properties.put(
        "event_time",
        forceLocal ? Map.of("type", dateType, "ignore_malformed", true) : Map.of("type", dateType));
    properties.put("latency", Map.of("type", "double"));
    properties.put("large", Map.of("type", "long"));
    return properties;
  }

  private static Map<String, Object> mapping(Map<String, Object> properties) {
    return Map.of("mappings", Map.of("properties", properties));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  private static boolean containsExists(Object value, String field) {
    if (value instanceof Map<?, ?> map) {
      if (Map.of("field", field).equals(map.get("exists"))) return true;
      return map.values().stream().anyMatch(item -> containsExists(item, field));
    }
    return value instanceof List<?> list
        && list.stream().anyMatch(item -> containsExists(item, field));
  }

  /**
   * Serves mapping discovery, scripted ES buckets, and raw documents for actual local execution.
   */
  private static final class ElasticsearchFixture implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final RestClient client;
    private final UnifiedQueryService service;
    private final Map<String, Object> mappings;
    private final List<Map<String, Object>> documents;
    private final List<Map<String, Object>> pages;
    private final List<Map<String, Object>> searches = new CopyOnWriteArrayList<>();
    private final List<String> mappingTargets = new CopyOnWriteArrayList<>();
    private final List<String> openedPits = new CopyOnWriteArrayList<>();
    private final List<String> closedPits = new CopyOnWriteArrayList<>();
    private final List<String> unexpectedRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger aggregatePage = new AtomicInteger();

    private ElasticsearchFixture(
        String dateType,
        boolean forceLocal,
        List<Map<String, Object>> documents,
        List<Map<String, Object>> pages)
        throws IOException {
      this(
          Map.of("events-1", mapping(properties(dateType, forceLocal))),
          Map.of(),
          documents,
          pages);
    }

    private ElasticsearchFixture(
        Map<String, Object> mappings,
        Map<String, String> fieldTypes,
        List<Map<String, Object>> documents,
        List<Map<String, Object>> pages)
        throws IOException {
      this.mappings = mappings;
      this.documents = documents;
      this.pages = pages;
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
      service =
          new UnifiedQueryService(
              List.of(new ElasticsearchQueryBackend("es", client, false, fieldTypes)), "es");
    }

    private QueryResponse query(String commands) {
      return service.execute(QueryType.PPL, "source=`" + INDEX + "`" + commands);
    }

    private String explain(String commands) {
      return service
          .explain(null, QueryType.PPL, "source=`" + INDEX + "`" + commands)
          .getCalcite()
          .getPhysical();
    }

    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        Map<String, Object> response;
        int status = 200;
        if (method.equals("GET")
            && (path.equals("/" + INDEX + "/_mapping") || path.equals("/_mapping"))) {
          mappingTargets.add(path);
          response = mappings;
        } else if (method.equals("POST") && path.equals("/" + INDEX + "/_pit")) {
          openedPits.add(path);
          response = Map.of("id", "pit-0");
        } else if (method.equals("POST") && path.equals("/_search")) {
          Map<String, Object> request =
              mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
          searches.add(request);
          int size = ((Number) request.get("size")).intValue();
          if (size == 0) {
            int position = aggregatePage.getAndIncrement();
            if (position >= pages.size()) {
              unexpectedRequests.add("Unscripted aggregation page " + position);
              status = 500;
              response = Map.of("error", "Unscripted aggregation page");
            } else {
              response =
                  Map.of(
                      "pit_id", "pit-" + searches.size(),
                      "hits", Map.of("hits", List.of()),
                      "aggregations", Map.of("groups", pages.get(position)));
            }
          } else {
            int start =
                request.get("search_after") instanceof List<?> after
                    ? ((Number) after.getFirst()).intValue() + 1
                    : 0;
            List<Map<String, Object>> hits = new ArrayList<>();
            for (int i = start; i < documents.size() && hits.size() < size; i++) {
              hits.add(
                  Map.of(
                      "_index", "events-1",
                      "_id", Integer.toString(i),
                      "_source", documents.get(i),
                      "sort", List.of(i)));
            }
            response = Map.of("pit_id", "pit-" + searches.size(), "hits", Map.of("hits", hits));
          }
        } else if (method.equals("DELETE") && path.equals("/_pit")) {
          Map<String, Object> request =
              mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
          closedPits.add(request.get("id").toString());
          response = Map.of("succeeded", true, "num_freed", 1);
        } else {
          unexpectedRequests.add(method + " " + path);
          status = 404;
          response = Map.of("error", "Unexpected fixture request: " + method + " " + path);
        }
        byte[] json = mapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, json.length);
        exchange.getResponseBody().write(json);
      }
    }

    private void assertExplainOnly() {
      assertTrue(mappingTargets.contains("/" + INDEX + "/_mapping"));
      assertTrue(searches.isEmpty());
      assertTrue(openedPits.isEmpty());
      assertTrue(closedPits.isEmpty());
      assertEquals(List.of(), unexpectedRequests);
    }

    private void assertDocumentSearches() {
      assertFalse(searches.isEmpty());
      for (Map<String, Object> request : searches) {
        assertTrue(((Number) request.get("size")).intValue() > 0);
        assertFalse(request.containsKey("aggs"));
      }
    }

    private void assertRequestsComplete() {
      assertEquals(List.of(), unexpectedRequests);
      assertEquals(List.of("/" + INDEX + "/_pit"), openedPits);
      assertEquals(List.of("pit-" + searches.size()), closedPits);
      for (int i = 0; i < searches.size(); i++) {
        assertEquals(Map.of("id", "pit-" + i, "keep_alive", "1m"), searches.get(i).get("pit"));
      }
    }

    @Override
    public void close() throws IOException {
      try {
        client.close();
      } finally {
        server.stop(0);
      }
    }
  }
}
