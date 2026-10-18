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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.QueryType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class ElasticsearchFlatSourcePathTest {
  private static final String INDEX = "flat-*";
  private static final String LEAF = "payload.literal.key";

  @Test
  void projectedFlatObjectKeepsTheRemainingDottedKeyAsOneItem() throws Exception {
    try (Fixture fixture = nestedFlatFixture()) {
      QueryResponse response =
          fixture.assertParity(" | fields payload | eval x=payload.literal.key | fields x");
      assertEquals(
          List.of("x"),
          response.getSchema().getColumns().stream().map(column -> column.getName()).toList());
      assertEquals(1, response.getResults().size());
      // Rewriting ITEM(payload, 'literal.key') into two hierarchical ITEM calls returns null.
      assertEquals("kept", response.getResults().getFirst().tupleValue().get("x").stringValue());
      fixture.assertComplete(2);
    }
  }

  @Test
  void directMappedLeafReadsTheLiteralKeyInsideItsSourceObject() throws Exception {
    try (Fixture fixture = nestedFlatFixture()) {
      QueryResponse response = fixture.assertParity(" | fields payload.literal.key");
      assertLeaf(response, "kept");
      fixture.assertComplete(2);
    }
  }

  @Test
  void rootSubobjectsFalseReadsTheEntireLiteralSourceKey() throws Exception {
    try (Fixture fixture =
        new Fixture(
            Map.of("flat-root", rootFlatMapping()),
            List.of(new Document("flat-root", Map.of(LEAF, "root-kept", "ignored", "pruned"))))) {
      QueryResponse response = fixture.assertParity(" | fields payload.literal.key");
      assertLeaf(response, "root-kept");
      fixture.assertComplete(2);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wildcardDirectLeafUsesEachIndexSourceOriginRegardlessOfMappingOrder(boolean reverse)
      throws Exception {
    Map<String, Object> candidates =
        Map.of(
            "flat-nested", nestedFlatMapping(),
            "flat-root", rootFlatMapping(),
            "flat-object", objectMapping());
    List<String> indices = new ArrayList<>(List.of("flat-nested", "flat-root", "flat-object"));
    if (reverse) Collections.reverse(indices);
    Map<String, Object> mappings = new LinkedHashMap<>();
    indices.forEach(index -> mappings.put(index, candidates.get(index)));
    try (Fixture fixture =
        new Fixture(
            mappings,
            List.of(
                new Document(
                    "flat-nested",
                    Map.of(
                        "id",
                        "nested",
                        "payload",
                        Map.of("literal.key", "nested-kept", "ignored", "pruned"))),
                new Document(
                    "flat-root", Map.of("id", "root", LEAF, "root-kept", "ignored", "pruned")),
                // For the ordinary object mapping, the hierarchical value is authoritative. A
                // decoder
                // that applies another index's FLAT origin to every hit incorrectly selects the
                // decoy.
                new Document(
                    "flat-object",
                    Map.of(
                        "id",
                        "object",
                        LEAF,
                        "wrong-flat-origin",
                        "payload",
                        Map.of("literal", Map.of("key", "object-kept"))))))) {
      QueryResponse response = fixture.assertParity(" | fields id,payload.literal.key");
      assertEquals(3, response.getResults().size());
      Map<String, String> values = new LinkedHashMap<>();
      response
          .getResults()
          .forEach(
              value ->
                  values.put(
                      value.tupleValue().get("id").stringValue(),
                      value.tupleValue().get(LEAF).stringValue()));
      assertEquals(
          Map.of("nested", "nested-kept", "root", "root-kept", "object", "object-kept"), values);
      fixture.assertComplete(2);
    }
  }

  private static Fixture nestedFlatFixture() throws IOException {
    return new Fixture(
        Map.of("flat-nested", nestedFlatMapping()),
        List.of(
            new Document(
                "flat-nested",
                Map.of("payload", Map.of("literal.key", "kept", "ignored", "pruned")))));
  }

  private static Map<String, Object> nestedFlatMapping() {
    return Map.of(
        "mappings",
        Map.of(
            "properties",
            Map.of(
                "id", Map.of("type", "keyword"),
                "payload",
                    Map.of(
                        "type",
                        "object",
                        "subobjects",
                        false,
                        "properties",
                        Map.of(
                            "literal.key",
                            Map.of("type", "keyword"),
                            "ignored",
                            Map.of("type", "keyword"))))));
  }

  private static Map<String, Object> rootFlatMapping() {
    return Map.of(
        "mappings",
        Map.of(
            "subobjects",
            false,
            "properties",
            Map.of(
                "id",
                Map.of("type", "keyword"),
                LEAF,
                Map.of("type", "keyword"),
                "ignored",
                Map.of("type", "keyword"))));
  }

  private static Map<String, Object> objectMapping() {
    return Map.of(
        "mappings",
        Map.of(
            "properties",
            Map.of(
                "id", Map.of("type", "keyword"),
                "payload",
                    Map.of(
                        "properties",
                        Map.of(
                            "literal",
                            Map.of("properties", Map.of("key", Map.of("type", "keyword"))))))));
  }

  private static void assertLeaf(QueryResponse response, String expected) {
    assertEquals(
        List.of(LEAF),
        response.getSchema().getColumns().stream().map(column -> column.getName()).toList());
    assertEquals(1, response.getResults().size());
    assertEquals(expected, response.getResults().getFirst().tupleValue().get(LEAF).stringValue());
  }

  private record Document(String index, Map<String, Object> source) {}

  /**
   * Returns projected original source keys, preserving literal dots instead of reconstructing them.
   */
  private static final class Fixture implements AutoCloseable {
    private static final Set<String> SEARCH_KEYS =
        Set.of("query", "pit", "size", "sort", "_source", "search_after", "track_scores");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Object> mappings;
    private final List<Document> documents;
    private final List<Map<String, Object>> searches = new CopyOnWriteArrayList<>();
    private final List<String> unexpected = new CopyOnWriteArrayList<>();
    private final Set<String> activePits = ConcurrentHashMap.newKeySet();
    private final AtomicInteger openedPits = new AtomicInteger();
    private final AtomicInteger closedPits = new AtomicInteger();
    private final HttpServer server;
    private final RestClient client;
    private final UnifiedQueryService pushed;
    private final UnifiedQueryService local;

    private Fixture(Map<String, Object> mappings, List<Document> documents) throws IOException {
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
      pushed = new UnifiedQueryService(List.of(new ElasticsearchQueryBackend("es", client)), "es");
      local =
          new UnifiedQueryService(
              List.of(new ElasticsearchQueryBackend("es", client, false, Map.of(), false)), "es");
    }

    private QueryResponse assertParity(String command) {
      String query = "source=`" + INDEX + "`" + command;
      QueryResponse response = pushed.execute(QueryType.PPL, query);
      QueryResponse baseline = local.execute(QueryType.PPL, query);
      assertNull(response.getError());
      assertNull(baseline.getError());
      assertEquals(baseline.getResults(), response.getResults());
      return response;
    }

    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        Map<String, Object> response;
        int status = 200;
        try {
          String method = exchange.getRequestMethod();
          String path = exchange.getRequestURI().getPath();
          if (method.equals("GET")
              && (path.equals("/" + INDEX + "/_mapping") || path.equals("/_mapping"))) {
            response = mappings;
          } else if (method.equals("POST") && path.equals("/" + INDEX + "/_pit")) {
            String pit = "flat-source-pit-" + openedPits.incrementAndGet();
            activePits.add(pit);
            response = Map.of("id", pit);
          } else if (method.equals("POST") && path.equals("/_search")) {
            Map<String, Object> request =
                mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
            searches.add(request);
            response = search(request);
          } else if (method.equals("DELETE") && path.equals("/_pit")) {
            Map<String, Object> request =
                mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
            require(activePits.remove(request.get("id")), "Unknown PIT: " + request);
            closedPits.incrementAndGet();
            response = Map.of("succeeded", true, "num_freed", 1);
          } else throw new IllegalArgumentException("Unexpected request: " + method + " " + path);
        } catch (RuntimeException e) {
          unexpected.add(e.toString());
          status = 400;
          response = Map.of("error", e.toString());
        }
        byte[] json = mapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, json.length);
        exchange.getResponseBody().write(json);
      }
    }

    private Map<String, Object> search(Map<String, Object> request) {
      require(SEARCH_KEYS.containsAll(request.keySet()), "Unexpected search keys: " + request);
      if (request.containsKey("track_scores"))
        require(request.get("track_scores") instanceof Boolean, "Invalid track_scores");
      require(
          Map.of("match_all", Map.of()).equals(request.get("query")),
          "Unexpected native predicate");
      require(
          List.of(Map.of("_shard_doc", "asc")).equals(request.get("sort")),
          "Unexpected native sort");
      require(activePits.contains(object(request.get("pit")).get("id")), "Inactive PIT");
      int size = ((Number) request.get("size")).intValue();
      require(size > 0 && size <= 1000, "Invalid document page size: " + size);
      int start = 0;
      if (request.containsKey("search_after")) {
        List<?> after = list(request.get("search_after"));
        require(after.size() == 1 && after.getFirst() instanceof Number, "Unexpected search_after");
        start = ((Number) after.getFirst()).intValue() + 1;
        require(start > 0 && start <= documents.size(), "Unknown search_after");
      }
      List<String> includes =
          list(request.get("_source")).stream()
              .map(
                  value -> {
                    require(value instanceof String, "Source include must be a string");
                    return (String) value;
                  })
              .toList();
      List<Map<String, Object>> hits = new ArrayList<>();
      for (int i = start; i < documents.size() && hits.size() < size; i++) {
        Document document = documents.get(i);
        hits.add(
            Map.of(
                "_index",
                document.index(),
                "_id",
                Integer.toString(i),
                "_score",
                1.0d,
                "_source",
                project(document.source(), "", includes),
                "sort",
                List.of(i)));
      }
      return Map.of(
          "hits",
          Map.of("total", Map.of("value", documents.size(), "relation", "eq"), "hits", hits));
    }

    private static Map<String, Object> project(
        Map<String, Object> source, String prefix, List<String> includes) {
      Map<String, Object> result = new LinkedHashMap<>();
      source.forEach(
          (key, value) -> {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (includes.stream()
                .anyMatch(include -> path.equals(include) || path.startsWith(include + "."))) {
              result.put(key, value);
            } else if (value instanceof Map<?, ?>
                && includes.stream().anyMatch(include -> include.startsWith(path + "."))) {
              result.put(key, project(object(value), path, includes));
            }
          });
      return result;
    }

    private void assertComplete(int queries) {
      assertEquals(List.of(), unexpected);
      assertFalse(searches.isEmpty());
      assertEquals(queries, openedPits.get());
      assertEquals(queries, closedPits.get());
      assertTrue(activePits.isEmpty());
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

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    require(value instanceof Map<?, ?>, "Expected object: " + value);
    return (Map<String, Object>) value;
  }

  private static List<?> list(Object value) {
    require(value instanceof List<?>, "Expected list: " + value);
    return (List<?>) value;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }
}
