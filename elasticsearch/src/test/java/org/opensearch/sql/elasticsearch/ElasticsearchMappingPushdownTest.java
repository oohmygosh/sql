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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.ElasticsearchTable;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.QueryType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class ElasticsearchMappingPushdownTest {
  private static final String INDEX = "mapped-*";

  @ParameterizedTest
  @CsvSource({"index,false", "doc_values,false", "index,true", "doc_values,true"})
  void disabledNumericMappingsKeepSpanLocalForPropertiesAtEitherDepth(
      String disabledOption, boolean nested) throws Exception {
    Map<String, Object> valueMapping = Map.of("type", "integer", disabledOption, false);
    Map<String, Object> properties = numericProperties(valueMapping, nested);
    String field = nested ? "payload.value" : "value";
    List<Map<String, Object>> documents =
        List.of(
            hit("mapped-a", numericDocument(-11, nested)),
            hit("mapped-a", numericDocument(11, nested)),
            hit("mapped-a", numericDocument(19, nested)),
            hit("mapped-a", numericDocument(null, nested)),
            hit("mapped-a", Map.of()));
    try (Fixture fixture = new Fixture(Map.of("mapped-a", mapping(properties)), documents)) {
      assertFalse(fixture.field(field).exact());
      assertEquals("integer", fixture.field(field).mappingType());
      String command =
          " | stats count() as total by span(`" + field + "`, 10) as bucket | sort bucket";
      assertLocalAggregate(fixture.explain(command));
      assertTrue(fixture.searches.isEmpty());

      QueryResponse response = fixture.query(command);
      assertNull(response.getError());
      assertEquals(3, response.getResults().size());
      assertEquals(
          List.of("total", "bucket"),
          response.getSchema().getColumns().stream().map(column -> column.getName()).toList());
      assertEquals(
          List.of(ExprCoreType.LONG, ExprCoreType.INTEGER),
          response.getSchema().getColumns().stream().map(column -> column.getExprType()).toList());
      assertTrue(row(response, 0).get("bucket").isNull());
      assertEquals(2L, row(response, 0).get("total").longValue());
      assertEquals(-10, row(response, 1).get("bucket").integerValue());
      assertEquals(1L, row(response, 1).get("total").longValue());
      assertEquals(10, row(response, 2).get("bucket").integerValue());
      assertEquals(2L, row(response, 2).get("total").longValue());
      fixture.assertLocalSearchesComplete();
    }
  }

  @ParameterizedTest
  @CsvSource({
    "false,false,false",
    "false,true,false",
    "true,false,false",
    "false,false,true",
    "false,true,true",
    "true,false,true"
  })
  void disabledKeywordMappingsKeepLikeLocalIncludingMultifieldSourcePaths(
      boolean indexed, boolean docValues, boolean multifield) throws Exception {
    Map<String, Object> keyword =
        Map.of("type", "keyword", "index", indexed, "doc_values", docValues);
    String field = multifield ? "message.raw" : "label";
    String sourceField = multifield ? "message" : "label";
    Map<String, Object> properties =
        multifield
            ? Map.of("message", Map.of("type", "text", "fields", Map.of("raw", keyword)))
            : Map.of("label", keyword);
    try (Fixture fixture =
        new Fixture(
            Map.of("mapped-a", mapping(properties)),
            List.of(
                hit("mapped-a", Map.of(sourceField, "abc")),
                hit("mapped-a", Map.of(sourceField, "axe")),
                hit("mapped-a", Map.of(sourceField, "bee")),
                hit("mapped-a", Collections.singletonMap(sourceField, null)),
                hit("mapped-a", Map.of())))) {
      Field discovered = fixture.field(field);
      assertFalse(discovered.exact());
      assertEquals(sourceField, discovered.source());
      String command =
          " | where like(`" + field + "`, 'a%') | fields `" + field + "` | sort `" + field + "`";
      String plan = fixture.explain(command);
      assertFalse(plan.contains("wildcard"), plan);
      assertFalse(plan.contains("regexp"), plan);
      assertTrue(fixture.searches.isEmpty());

      QueryResponse response = fixture.query(command);
      assertNull(response.getError());
      assertEquals(
          List.of("abc", "axe"),
          response.getResults().stream()
              .map(value -> value.tupleValue().get(field).stringValue())
              .toList());
      assertEquals(ExprCoreType.STRING, response.getSchema().getColumns().getFirst().getExprType());
      fixture.assertLocalSearchesComplete();
      for (Map<String, Object> search : fixture.searches) {
        assertEquals(List.of(sourceField), search.get("_source"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void oneDisabledIndexKeepsTheMergedFieldIneligibleInEitherMappingOrder(boolean reverse)
      throws Exception {
    Map<String, Object> enabled =
        mapping(
            numericProperties(Map.of("type", "integer", "index", true, "doc_values", true), false));
    Map<String, Object> disabled =
        mapping(numericProperties(Map.of("type", "integer", "doc_values", false), false));
    Map<String, Object> mappings = new LinkedHashMap<>();
    if (reverse) {
      mappings.put("mapped-b", disabled);
      mappings.put("mapped-a", enabled);
    } else {
      mappings.put("mapped-a", enabled);
      mappings.put("mapped-b", disabled);
    }
    try (Fixture fixture =
        new Fixture(
            mappings,
            List.of(
                hit("mapped-a", Map.of("value", 11)),
                hit("mapped-b", Map.of("value", 19)),
                hit("mapped-b", Map.of("value", -11))))) {
      assertFalse(fixture.field("value").exact());
      String command = " | stats count() as total by span(value, 10) as bucket | sort bucket";
      assertLocalAggregate(fixture.explain(command));
      QueryResponse response = fixture.query(command);
      assertNull(response.getError());
      assertEquals(2, response.getResults().size());
      assertEquals(-10, row(response, 0).get("bucket").integerValue());
      assertEquals(1L, row(response, 0).get("total").longValue());
      assertEquals(10, row(response, 1).get("bucket").integerValue());
      assertEquals(2L, row(response, 1).get("total").longValue());
      fixture.assertLocalSearchesComplete();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void defaultAndExplicitlyEnabledMappingsRetainNativePlanning(boolean explicit) throws Exception {
    Map<String, Object> numeric = new LinkedHashMap<>(Map.of("type", "integer"));
    Map<String, Object> keyword = new LinkedHashMap<>(Map.of("type", "keyword"));
    if (explicit) {
      numeric.putAll(Map.of("index", true, "doc_values", true));
      keyword.putAll(Map.of("index", true, "doc_values", true));
    }
    Map<String, Object> properties =
        Map.of(
            "value",
            numeric,
            "payload",
            Map.of("type", "object", "properties", Map.of("value", numeric)),
            "label",
            keyword,
            "message",
            Map.of("type", "text", "fields", Map.of("raw", keyword)));
    try (Fixture fixture = new Fixture(Map.of("mapped-a", mapping(properties)), List.of())) {
      for (String field : List.of("value", "payload.value", "label", "message.raw")) {
        assertTrue(fixture.field(field).exact(), field);
      }
      for (String field : List.of("value", "payload.value")) {
        String plan = fixture.explain(" | stats count() by span(`" + field + "`, 10)");
        assertFalse(
            plan.contains("EnumerableAggregate") || plan.contains("EnumerableSortedAggregate"),
            plan);
        assertTrue(plan.contains("painless"), plan);
      }
      for (String field : List.of("label", "message.raw")) {
        String plan =
            fixture.explain(" | where like(`" + field + "`, 'a%') | fields `" + field + "`");
        assertTrue(plan.contains("wildcard") || plan.contains("regexp"), plan);
      }
      assertTrue(fixture.searches.isEmpty());
      assertEquals(0, fixture.openedPits);
      assertEquals(0, fixture.closedPits);
      assertEquals(List.of(), fixture.unexpected);
    }
  }

  private static void assertLocalAggregate(String plan) {
    assertTrue(
        plan.contains("EnumerableAggregate") || plan.contains("EnumerableSortedAggregate"), plan);
    assertFalse(plan.contains("painless"), plan);
    assertFalse(plan.contains("histogram"), plan);
  }

  private static Map<String, ExprValue> row(QueryResponse response, int index) {
    return response.getResults().get(index).tupleValue();
  }

  private static Map<String, Object> numericProperties(
      Map<String, Object> numeric, boolean nested) {
    return nested
        ? Map.of("payload", Map.of("type", "object", "properties", Map.of("value", numeric)))
        : Map.of("value", numeric);
  }

  private static Map<String, Object> numericDocument(Integer value, boolean nested) {
    Map<String, Object> document = Collections.singletonMap("value", value);
    return nested ? Map.of("payload", document) : document;
  }

  private static Map<String, Object> mapping(Map<String, Object> properties) {
    return Map.of("mappings", Map.of("properties", properties));
  }

  private static Map<String, Object> hit(String index, Map<String, Object> source) {
    return Map.of("_index", index, "_source", source);
  }

  /** Exposes mapping discovery and raw source documents on loopback for real local evaluation. */
  private static final class Fixture implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final RestClient client;
    private final ElasticsearchQueryBackend backend;
    private final UnifiedQueryService service;
    private final Map<String, Object> mappings;
    private final List<Map<String, Object>> documents;
    private final List<Map<String, Object>> searches = new CopyOnWriteArrayList<>();
    private final List<String> unexpected = new CopyOnWriteArrayList<>();
    private int mappingRequests;
    private int openedPits;
    private int closedPits;

    private Fixture(Map<String, Object> mappings, List<Map<String, Object>> documents)
        throws IOException {
      this.mappings = mappings;
      this.documents = documents;
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

    private Field field(String name) {
      ElasticsearchTable table = (ElasticsearchTable) backend.createSchema(null).getTable(INDEX);
      return table.fields.stream()
          .filter(field -> field.name().equals(name))
          .findFirst()
          .orElseThrow();
    }

    private QueryResponse query(String command) {
      return service.execute(QueryType.PPL, "source=`" + INDEX + "`" + command);
    }

    private String explain(String command) {
      return service
          .explain(null, QueryType.PPL, "source=`" + INDEX + "`" + command)
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
          mappingRequests++;
          response = mappings;
        } else if (method.equals("POST") && path.equals("/" + INDEX + "/_pit")) {
          openedPits++;
          response = Map.of("id", "mapping-pit");
        } else if (method.equals("POST") && path.equals("/_search")) {
          Map<String, Object> request =
              mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
          searches.add(request);
          int size = ((Number) request.get("size")).intValue();
          if (size <= 0 || request.containsKey("aggs"))
            unexpected.add("Unexpected native aggregation");
          if (!Map.of("match_all", Map.of()).equals(request.get("query"))) {
            unexpected.add("Unexpected native predicate: " + request.get("query"));
          }
          int start =
              request.get("search_after") instanceof List<?> after
                  ? ((Number) after.getFirst()).intValue() + 1
                  : 0;
          List<Map<String, Object>> hits = new ArrayList<>();
          for (int i = start; i < documents.size() && hits.size() < size; i++) {
            Map<String, Object> hit = new LinkedHashMap<>(documents.get(i));
            hit.put("_id", Integer.toString(i));
            hit.put("sort", List.of(i));
            hits.add(hit);
          }
          response = Map.of("hits", Map.of("hits", hits));
        } else if (method.equals("DELETE") && path.equals("/_pit")) {
          Map<String, Object> request =
              mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
          if (!"mapping-pit".equals(request.get("id"))) unexpected.add("Unexpected PIT close");
          closedPits++;
          response = Map.of("succeeded", true);
        } else {
          unexpected.add(method + " " + path);
          status = 404;
          response = Map.of("error", "Unexpected request");
        }
        byte[] bytes = mapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    }

    private void assertLocalSearchesComplete() {
      assertTrue(mappingRequests > 0);
      assertEquals(List.of(), unexpected);
      assertEquals(1, openedPits);
      assertEquals(1, closedPits);
      assertEquals(2, searches.size());
      for (Map<String, Object> request : searches) {
        assertTrue(((Number) request.get("size")).intValue() > 0);
        assertFalse(request.containsKey("aggs"));
        assertEquals(Map.of("match_all", Map.of()), request.get("query"));
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
