/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
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

class ElasticsearchQueryBackendTest {
  private static final String PORT = "extracted.source.port";
  private static final long LONG_PORT = 9007199254740993L;

  @ParameterizedTest
  @ValueSource(strings = {"*", "logevent-*"})
  void wildcardDocumentQueriesPreserveConflictingAndMissingFields(String pattern) throws Exception {
    try (var fixture = new ElasticsearchFixture(pattern, false)) {
      var response = fixture.query("");

      assertEquals(3, response.getResults().size());
      assertTrue(row(response, 2).get("extracted").isNull());
      assertEquals(
          LONG_PORT,
          row(response, 0)
              .get("extracted")
              .tupleValue()
              .get("source")
              .tupleValue()
              .get("port")
              .value());
      assertEquals(
          "unknown",
          row(response, 1)
              .get("extracted")
              .tupleValue()
              .get("source")
              .tupleValue()
              .get("port")
              .value());
      assertEquals("long value", row(response, 0).get("long_only").stringValue());
      assertTrue(row(response, 1).get("long_only").isNull());
      assertEquals("keyword value", row(response, 1).get("keyword_only").stringValue());
      assertTrue(row(response, 0).get("keyword_only").isNull());
      fixture.assertRequestsComplete();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"*", "logevent-*"})
  void wildcardProjectionReturnsNullForUnmappedFields(String pattern) throws Exception {
    try (var fixture = new ElasticsearchFixture(pattern, false)) {
      var response = fixture.query(" | fields id, `" + PORT + "`");

      assertEquals(3, response.getResults().size());
      assertPortValues(response);
      fixture.assertRequestsComplete();
    }
  }

  @ParameterizedTest
  @CsvSource({"*,false", "*,true", "logevent-*,false", "logevent-*,true"})
  void wildcardProjectionAndHeadPreserveBothRuntimeTypes(String pattern, boolean reverseMappings)
      throws Exception {
    try (var fixture = new ElasticsearchFixture(pattern, reverseMappings)) {
      var rowType =
          fixture
              .backend
              .createSchema(null)
              .getTable(pattern)
              .getRowType(new JavaTypeFactoryImpl());
      assertEquals(SqlTypeName.ANY, rowType.getField(PORT, true, false).getType().getSqlTypeName());

      var response = fixture.query(" | fields id, `" + PORT + "` | head 2");

      assertEquals(2, response.getResults().size());
      assertEquals(List.of("id", PORT), new ArrayList<>(row(response, 0).keySet()));
      assertEquals("numeric", row(response, 0).get("id").stringValue());
      assertEquals(LONG_PORT, row(response, 0).get(PORT).value());
      assertEquals("keyword", row(response, 1).get("id").stringValue());
      assertEquals("unknown", row(response, 1).get(PORT).value());
      assertEquals(2, ((Number) fixture.searches.getFirst().get("size")).intValue());
      fixture.assertRequestsComplete();
    }
  }

  @ParameterizedTest
  @CsvSource({"*,false", "*,true", "logevent-*,false", "logevent-*,true"})
  void multifieldsAndObjectPropertiesUseTheirOwnIndexSources(
      String pattern, boolean reverseMappings) throws Exception {
    try (var fixture = new ElasticsearchFixture(pattern, reverseMappings)) {
      var response = fixture.query(" | fields id, `message.keyword`");

      assertEquals(3, response.getResults().size());
      assertEquals("plain message", row(response, 0).get("message.keyword").stringValue());
      assertEquals("nested message", row(response, 1).get("message.keyword").stringValue());
      assertTrue(row(response, 2).get("message.keyword").isNull());
      fixture.assertRequestsComplete();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"*", "logevent-*"})
  void wildcardCountIncludesDocumentsWithDifferentOrMissingMappings(String pattern)
      throws Exception {
    try (var fixture = new ElasticsearchFixture(pattern, false)) {
      var response = fixture.query(" | stats count() as total");

      assertEquals(1, response.getResults().size());
      assertEquals(List.of("total"), new ArrayList<>(row(response, 0).keySet()));
      assertEquals(3L, row(response, 0).get("total").longValue());
      assertEquals(1, fixture.searches.size());
      assertEquals(0, ((Number) fixture.searches.getFirst().get("size")).intValue());
      assertEquals(true, fixture.searches.getFirst().get("track_total_hits"));
      fixture.assertRequestsComplete();
    }
  }

  @ParameterizedTest
  @CsvSource({"*,false", "logevent-*,true"})
  void incompatibleNestedFieldFiltersRemainLocal(String pattern, boolean reverseMappings)
      throws Exception {
    try (var fixture = new ElasticsearchFixture(pattern, reverseMappings)) {
      var response =
          fixture.query(" | where isnotnull(`" + PORT + "`) | fields id, `" + PORT + "`");

      assertEquals(2, response.getResults().size());
      assertEquals(LONG_PORT, row(response, 0).get(PORT).value());
      assertEquals("unknown", row(response, 1).get(PORT).value());
      fixture.assertMatchAllSearches();
      fixture.assertRequestsComplete();
    }
  }

  @ParameterizedTest
  @CsvSource({"*,false", "*,true", "logevent-*,false", "logevent-*,true"})
  void sameTypeMappingModifiersAreConservativelyMerged(String pattern, boolean reverseMappings)
      throws Exception {
    try (var fixture = new ElasticsearchFixture(pattern, reverseMappings)) {
      // The keyword index has ignore_above=4, but _source still contains the full value.
      var response = fixture.query(" | where label = 'keyword label' | fields id, label");

      assertEquals(1, response.getResults().size());
      assertEquals("keyword", row(response, 0).get("id").stringValue());
      assertEquals("keyword label", row(response, 0).get("label").stringValue());
      fixture.assertMatchAllSearches();
      fixture.assertRequestsComplete();
    }
  }

  private static Map<String, ExprValue> row(QueryResponse response, int index) {
    return response.getResults().get(index).tupleValue();
  }

  private static void assertPortValues(QueryResponse response) {
    assertEquals(LONG_PORT, row(response, 0).get(PORT).value());
    assertEquals("unknown", row(response, 1).get(PORT).value());
    assertTrue(row(response, 2).get(PORT).isNull());
  }

  /** Only mapping discovery and the PIT search lifecycle are exposed on this loopback server. */
  private static final class ElasticsearchFixture implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String pattern;
    private final HttpServer server;
    private final RestClient client;
    private final ElasticsearchQueryBackend backend;
    private final UnifiedQueryService service;
    private final Map<String, Object> mappings = new LinkedHashMap<>();
    private final List<Map<String, Object>> hits;
    private final List<Map<String, Object>> searches = new CopyOnWriteArrayList<>();
    private final List<String> mappingTargets = new CopyOnWriteArrayList<>();
    private final List<String> pitTargets = new CopyOnWriteArrayList<>();
    private final List<String> unexpectedRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger closedPits = new AtomicInteger();

    private ElasticsearchFixture(String pattern, boolean reverseMappings) throws IOException {
      this.pattern = pattern;
      var indices =
          new ArrayList<>(List.of("logevent-long", "logevent-keyword", "logevent-missing"));
      if (reverseMappings) Collections.reverse(indices);
      for (String index : indices) {
        String type = index.equals("logevent-long") ? "long" : "keyword";
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", Map.of("type", "keyword"));
        properties.put(
            "label",
            index.equals("logevent-keyword")
                ? Map.of("type", "keyword", "ignore_above", 4)
                : Map.of("type", "keyword"));
        if (!index.equals("logevent-missing")) {
          properties.put(
              "extracted",
              Map.of(
                  "properties",
                  Map.of("source", Map.of("properties", Map.of("port", Map.of("type", type))))));
          properties.put(type + "_only", Map.of("type", "keyword"));
          properties.put(
              "message",
              index.equals("logevent-long")
                  ? Map.of("type", "text", "fields", Map.of("keyword", Map.of("type", "keyword")))
                  : Map.of("properties", Map.of("keyword", Map.of("type", "keyword"))));
        }
        mappings.put(index, Map.of("mappings", Map.of("properties", properties)));
      }
      hits =
          List.of(
              hit(
                  "logevent-long",
                  0,
                  Map.of(
                      "id", "numeric",
                      "label", "numeric label",
                      "long_only", "long value",
                      "message", "plain message",
                      "extracted", Map.of("source", Map.of("port", LONG_PORT)))),
              hit(
                  "logevent-keyword",
                  1,
                  Map.of(
                      "id", "keyword",
                      "label", "keyword label",
                      "keyword_only", "keyword value",
                      "message", Map.of("keyword", "nested message"),
                      "extracted", Map.of("source", Map.of("port", "unknown")))),
              hit("logevent-missing", 2, Map.of("id", "missing", "label", "missing label")));
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

    private static Map<String, Object> hit(
        String index, int position, Map<String, Object> document) {
      return Map.of(
          "_index",
          index,
          "_id",
          Integer.toString(position),
          "_source",
          document,
          "sort",
          List.of(position));
    }

    private QueryResponse query(String commands) {
      return service.execute(QueryType.PPL, "source=`" + pattern + "`" + commands);
    }

    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        Map<String, Object> response;
        int status = 200;
        if (method.equals("GET")
            && (path.equals("/" + pattern + "/_mapping") || path.equals("/_mapping"))) {
          mappingTargets.add(path);
          response = mappings;
        } else if (method.equals("POST") && path.equals("/" + pattern + "/_pit")) {
          pitTargets.add(path);
          response = Map.of("id", "fixture-pit");
        } else if (method.equals("POST") && path.equals("/_search")) {
          Map<String, Object> request =
              mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
          searches.add(request);
          int size = ((Number) request.get("size")).intValue();
          int start =
              request.get("search_after") instanceof List<?> after
                  ? ((Number) after.getFirst()).intValue() + 1
                  : 0;
          // Return raw documents so residual predicates are evaluated by the actual query engine.
          response =
              Map.of(
                  "hits",
                  Map.of(
                      "total", Map.of("value", hits.size(), "relation", "eq"),
                      "hits", hits.subList(start, Math.min(hits.size(), start + size))),
                  "aggregations",
                  Map.of());
        } else if (method.equals("DELETE") && path.equals("/_pit")) {
          closedPits.incrementAndGet();
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

    private void assertMatchAllSearches() {
      assertFalse(searches.isEmpty());
      for (var request : searches)
        assertEquals(Map.of("match_all", Map.of()), request.get("query"));
    }

    private void assertRequestsComplete() {
      assertEquals(List.of(), unexpectedRequests);
      assertTrue(mappingTargets.contains("/" + pattern + "/_mapping"));
      assertFalse(pitTargets.isEmpty());
      assertEquals(Collections.nCopies(pitTargets.size(), "/" + pattern + "/_pit"), pitTargets);
      assertEquals(pitTargets.size(), closedPits.get(), "Every opened PIT must be deleted");
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
