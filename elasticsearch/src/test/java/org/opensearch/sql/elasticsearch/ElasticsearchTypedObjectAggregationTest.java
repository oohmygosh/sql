/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.QueryType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class ElasticsearchTypedObjectAggregationTest {
  private static final String EXTREMA =
      " | stats min(payload.n) as lo, max(payload.n) as hi,"
          + " min(payload.amount) as small, max(payload.amount) as large";

  @ParameterizedTest
  @CsvSource({"true,true", "true,false", "false,false"})
  void projectedObjectExtremaMatchDirectTypedFieldsWithLocalAggregation(
      boolean unsafeMapping, boolean pushdown) throws Exception {
    try (Fixture fixture = new Fixture(unsafeMapping, pushdown, documents())) {
      QueryResponse direct = fixture.query(EXTREMA);
      QueryResponse projected = fixture.query(" | fields payload" + EXTREMA);
      assertEquals(direct.getResults(), projected.getResults());
      Map<String, ExprValue> row = projected.getResults().getFirst().tupleValue();
      assertEquals(-4, row.get("lo").integerValue());
      assertEquals(12, row.get("hi").integerValue());
      assertEquals(1.25, row.get("small").doubleValue());
      assertEquals(8.5, row.get("large").doubleValue());
      // 7.9 is a source string for an integer mapping. The typed path must use the same
      // mapping decoder as direct reads, rather than SQL's Integer.parseInt("7.9").
      fixture.assertLocalAndClosed(2);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void groupedMultipleExtremaRetainNamesOrdinalsNullsAndOtherAggregateCalls(boolean pushdown)
      throws Exception {
    try (Fixture fixture = new Fixture(true, pushdown, documents())) {
      String aggregation =
          EXTREMA
              + ", count() as total by category | sort category"
              + " | fields category,hi,lo,large,small,total";
      QueryResponse direct = fixture.query(aggregation);
      QueryResponse projected = fixture.query(" | fields payload,category" + aggregation);
      assertEquals(direct.getResults(), projected.getResults());
      assertEquals(
          List.of("category", "hi", "lo", "large", "small", "total"),
          projected.getSchema().getColumns().stream().map(column -> column.getName()).toList());
      assertEquals(3, projected.getResults().size());
      Map<String, ExprValue> first = projected.getResults().getFirst().tupleValue();
      assertEquals("a", first.get("category").stringValue());
      assertEquals(7, first.get("hi").integerValue());
      assertEquals(-4, first.get("lo").integerValue());
      assertEquals(2L, first.get("total").longValue());
      Map<String, ExprValue> last = projected.getResults().getLast().tupleValue();
      assertEquals("c", last.get("category").stringValue());
      for (String metric : List.of("lo", "hi", "small", "large"))
        assertTrue(last.get(metric).isNull());
      assertEquals(1L, last.get("total").longValue());
      fixture.assertLocalAndClosed(2);
    }
  }

  @Test
  void renamedParentAndLeafAliasesRetainSourceLineage() throws Exception {
    try (Fixture fixture = new Fixture(true, false, documents())) {
      QueryResponse result =
          fixture.query(
              " | fields payload,category | rename payload as p"
                  + " | eval value=p.n | fields category,value"
                  + " | stats min(value) as lo,max(value) as hi by category | sort category");
      QueryResponse direct =
          fixture.query(
              " | stats min(payload.n) as lo,max(payload.n) as hi by category | sort category");
      assertEquals(direct.getResults(), result.getResults());
      fixture.assertLocalAndClosed(2);
    }
  }

  @Test
  void sourceFilterRemainsBelowTheTypedAggregate() throws Exception {
    try (Fixture fixture = new Fixture(true, false, documents())) {
      QueryResponse result = fixture.query(" | where category='a' | fields payload" + EXTREMA);
      Map<String, ExprValue> row = result.getResults().getFirst().tupleValue();
      assertEquals(-4, row.get("lo").integerValue());
      assertEquals(7, row.get("hi").integerValue());
      fixture.assertLocalAndClosed(1);
    }
  }

  @Test
  void headBeforeAggregationStillLimitsTheInputRows() throws Exception {
    try (Fixture fixture = new Fixture(true, false, documents())) {
      QueryResponse result = fixture.query(" | head 2 | fields payload" + EXTREMA);
      Map<String, ExprValue> row = result.getResults().getFirst().tupleValue();
      assertEquals(-4, row.get("lo").integerValue());
      assertEquals(7, row.get("hi").integerValue());
      fixture.assertLocalAndClosed(1);
    }
  }

  @Test
  void emptyInputProducesOneNullableAggregateRow() throws Exception {
    try (Fixture fixture = new Fixture(true, false, List.of())) {
      QueryResponse result = fixture.query(" | fields payload" + EXTREMA);
      assertEquals(1, result.getResults().size());
      for (ExprValue value : result.getResults().getFirst().tupleValue().values())
        assertTrue(value.isNull());
      fixture.assertLocalAndClosed(1);
    }
  }

  private static List<Map<String, Object>> documents() {
    Map<String, Object> nullParent = new LinkedHashMap<>();
    nullParent.put("category", "c");
    nullParent.put("payload", null);
    return List.of(
        Map.of("category", "a", "payload", Map.of("n", "7.9", "amount", "1.25")),
        Map.of("category", "a", "payload", Map.of("n", -4, "amount", 8.5)),
        Map.of("category", "b", "payload", Map.of("n", "12", "amount", 2.0)),
        Map.of("category", "b", "payload", Map.of()),
        nullParent);
  }

  /** Native aggregation is forbidden: these tests exercise the mandatory local fallback. */
  private static final class Fixture implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final RestClient client;
    private final UnifiedQueryService service;
    private final List<Map<String, Object>> documents;
    private final Map<String, Object> mappings;
    private final List<Map<String, Object>> searches = new CopyOnWriteArrayList<>();
    private final List<String> unexpected = new CopyOnWriteArrayList<>();
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();

    private Fixture(boolean unsafe, boolean pushdown, List<Map<String, Object>> documents)
        throws IOException {
      this.documents = documents;
      Map<String, Object> integer = new LinkedHashMap<>(Map.of("type", "integer"));
      Map<String, Object> decimal = new LinkedHashMap<>(Map.of("type", "double"));
      if (unsafe) {
        integer.put("ignore_malformed", true);
        decimal.put("ignore_malformed", true);
      }
      mappings =
          Map.of(
              "events",
              Map.of(
                  "mappings",
                  Map.of(
                      "properties",
                      Map.of(
                          "category", Map.of("type", "keyword"),
                          "payload",
                              Map.of("properties", Map.of("n", integer, "amount", decimal))))));
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
              List.of(new ElasticsearchQueryBackend("es", client, false, Map.of(), pushdown)),
              "es");
    }

    private QueryResponse query(String commands) {
      QueryResponse response = service.execute(QueryType.PPL, "source=events" + commands);
      assertNull(response.getError());
      return response;
    }

    private void assertLocalAndClosed(int executions) {
      assertEquals(List.of(), unexpected);
      assertEquals(executions, opened.get());
      assertEquals(executions, closed.get());
      assertFalse(searches.isEmpty());
      for (Map<String, Object> request : searches) {
        assertTrue(((Number) request.get("size")).intValue() > 0);
        assertFalse(request.containsKey("aggs"));
      }
    }

    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        Map<String, Object> response;
        int status = 200;
        if (method.equals("GET") && (path.equals("/events/_mapping") || path.equals("/_mapping"))) {
          response = mappings;
        } else if (method.equals("POST") && path.equals("/events/_pit")) {
          response = Map.of("id", "pit-" + opened.incrementAndGet());
        } else if (method.equals("POST") && path.equals("/_search")) {
          Map<String, Object> request =
              mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
          searches.add(request);
          if (request.containsKey("aggs")) unexpected.add("Native aggregation must remain local");
          int from =
              request.get("search_after") instanceof List<?> after
                  ? ((Number) after.getFirst()).intValue() + 1
                  : 0;
          int size = ((Number) request.get("size")).intValue();
          List<Map<String, Object>> hits = new ArrayList<>();
          for (int i = from; i < Math.min(documents.size(), from + size); i++) {
            hits.add(
                Map.of(
                    "_index",
                    "events",
                    "_id",
                    Integer.toString(i),
                    "_source",
                    documents.get(i),
                    "sort",
                    List.of(i)));
          }
          response = Map.of("hits", Map.of("hits", hits));
        } else if (method.equals("DELETE") && path.equals("/_pit")) {
          closed.incrementAndGet();
          response = Map.of("succeeded", true);
        } else {
          unexpected.add(method + " " + path);
          status = 404;
          response = Map.of("error", "Unexpected fixture request");
        }
        byte[] data = mapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
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
