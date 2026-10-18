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
import java.time.Instant;
import java.util.ArrayList;
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
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.QueryType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class ElasticsearchSemanticEquivalenceTest {
  private static final String INDEX = "sem-*";
  private static final double FIRST = 1.00000002d;
  private static final double SECOND = 1.00000001d;

  @ParameterizedTest
  @ValueSource(strings = {"float", "half_float", "scaled_float"})
  void quantizedSortAndHeadKeepSourcePrecisionAndMatchDisabledPushdown(String type)
      throws Exception {
    try (Fixture fixture = quantized(type)) {
      String command = " | sort value | head 1 | fields id,value";
      String plan = fixture.explain(command);
      assertTrue(plan.contains("EnumerableSort"), plan);
      assertTrue(plan.contains("fieldConstraints") && plan.contains("quantized"), plan);
      QueryResponse pushed = fixture.ppl(command, true);
      QueryResponse local = fixture.ppl(command, false);
      assertEquivalent(pushed, local);
      assertIds(pushed, List.of("B"));
      assertEquals(SECOND, row(pushed, 0).get("value").doubleValue());
      fixture.assertLocalScans(2);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"float", "half_float", "scaled_float"})
  void quantizedDirectGroupingDoesNotMergeDistinctSourceValues(String type) throws Exception {
    try (Fixture fixture = quantized(type)) {
      String command = " | stats count() as total by value | sort value";
      String plan = fixture.explain(command);
      assertTrue(
          plan.contains("EnumerableAggregate") || plan.contains("EnumerableSortedAggregate"), plan);
      QueryResponse pushed = fixture.ppl(command, true);
      QueryResponse local = fixture.ppl(command, false);
      assertEquivalent(pushed, local);
      assertEquals(2, pushed.getResults().size());
      assertEquals(SECOND, row(pushed, 0).get("value").doubleValue());
      assertEquals(FIRST, row(pushed, 1).get("value").doubleValue());
      assertEquals(1L, row(pushed, 0).get("total").longValue());
      assertEquals(1L, row(pushed, 1).get("total").longValue());
      fixture.assertLocalScans(2);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"target", "target.raw"})
  void copyTargetNullFiltersAndCountsUseOriginalSourceIncludingMultifields(String field)
      throws Exception {
    Map<String, Object> properties =
        Map.of(
            "id", Map.of("type", "keyword"),
            "source", Map.of("type", "keyword", "copy_to", "target"),
            "target",
                Map.of("type", "keyword", "fields", Map.of("raw", Map.of("type", "keyword"))));
    try (Fixture fixture =
        new Fixture(
            Map.of("sem-a", mapping(properties)),
            List.of(
                new Document("sem-a", Map.of("id", "copied-only", "source", "generated")),
                new Document(
                    "sem-a",
                    Map.of("id", "direct", "source", "also-generated", "target", "original")),
                new Document("sem-a", Map.of("id", "missing"))),
            Set.of("target", "target.raw"))) {
      String nulls = " | where isnull(`" + field + "`) | fields id,`" + field + "`";
      assertTrue(fixture.explain(nulls).contains("copy_to target"));
      QueryResponse pushedNulls = fixture.ppl(nulls, true);
      assertEquivalent(pushedNulls, fixture.ppl(nulls, false));
      assertIds(pushedNulls, List.of("copied-only", "missing"));
      for (ExprValue value : pushedNulls.getResults())
        assertTrue(value.tupleValue().get(field).isNull());

      String present = " | where isnotnull(`" + field + "`) | fields id,`" + field + "`";
      QueryResponse pushedPresent = fixture.ppl(present, true);
      assertEquivalent(pushedPresent, fixture.ppl(present, false));
      assertIds(pushedPresent, List.of("direct"));
      assertEquals("original", row(pushedPresent, 0).get(field).stringValue());

      String count = " | stats count(`" + field + "`) as present,count() as total";
      QueryResponse pushedCount = fixture.ppl(count, true);
      assertEquivalent(pushedCount, fixture.ppl(count, false));
      assertEquals(1, pushedCount.getResults().size());
      assertEquals(1L, row(pushedCount, 0).get("present").longValue());
      assertEquals(3L, row(pushedCount, 0).get("total").longValue());
      fixture.assertLocalScans(6);
    }
  }

  @Test
  void wildcardKeywordSortSuppliesUnmappedTypeAndMatchesLocalNullOrdering() throws Exception {
    Map<String, Object> mappings =
        Map.of(
            "sem-a",
                mapping(
                    Map.of("id", Map.of("type", "keyword"), "category", Map.of("type", "keyword"))),
            "sem-unmapped", mapping(Map.of("id", Map.of("type", "keyword"))));
    try (Fixture fixture =
        new Fixture(
            mappings,
            List.of(
                new Document("sem-a", Map.of("id", "A", "category", "zulu")),
                new Document("sem-unmapped", Map.of("id", "unmapped")),
                new Document("sem-a", Map.of("id", "B", "category", "alpha"))),
            Set.of())) {
      String sql =
          "SELECT id,category FROM `" + INDEX + "` ORDER BY category ASC NULLS FIRST LIMIT 2";
      QueryResponse pushed = fixture.sql(sql, true);
      assertSuccessful(pushed);
      assertEquals(1, fixture.searches.size(), "A native top-two should need only one search page");
      Map<String, Object> request = fixture.searches.getFirst();
      assertEquals(2, ((Number) request.get("size")).intValue());
      Map<String, Object> sort = object(list(request.get("sort")).getFirst());
      assertEquals(
          Map.of("order", "asc", "missing", "_first", "unmapped_type", "keyword"),
          sort.get("category"));
      assertIds(pushed, List.of("unmapped", "B"));
      assertTrue(row(pushed, 0).get("category").isNull());
      assertEquals("alpha", row(pushed, 1).get("category").stringValue());
      assertEquivalent(pushed, fixture.sql(sql, false));
      fixture.assertComplete(2);
    }
  }

  @Test
  void publicQueriesDecodeCoercedScalarsAndPerIndexDateFormats() throws Exception {
    Map<String, Object> mappings =
        Map.of(
            "sem-default", decodingMapping(Map.of("type", "date")),
            "sem-seconds", decodingMapping(Map.of("type", "date", "format", "epoch_second")),
            "sem-custom",
                decodingMapping(Map.of("type", "date", "format", "yyyy/MM/dd HH:mm:ss XXX")));
    try (Fixture fixture =
        new Fixture(
            mappings,
            List.of(
                new Document(
                    "sem-default",
                    Map.of("id", "date-only", "ts", "2024-01-01", "number", "42", "flag", "true")),
                new Document(
                    "sem-default",
                    Map.of(
                        "id",
                        "epoch-millis-string",
                        "ts",
                        "1704067200000",
                        "number",
                        "7",
                        "flag",
                        true)),
                new Document(
                    "sem-default",
                    Map.of(
                        "id",
                        "instant",
                        "ts",
                        "2024-01-01T00:00:00Z",
                        "number",
                        2,
                        "flag",
                        "false")),
                new Document(
                    "sem-seconds",
                    Map.of(
                        "id",
                        "epoch-seconds-number",
                        "ts",
                        1704067200L,
                        "number",
                        "-12.9",
                        "flag",
                        "false")),
                new Document(
                    "sem-seconds",
                    Map.of(
                        "id",
                        "epoch-seconds-string",
                        "ts",
                        "1704067200",
                        "number",
                        9,
                        "flag",
                        false)),
                new Document(
                    "sem-custom",
                    Map.of(
                        "id",
                        "custom",
                        "ts",
                        "2024/01/01 02:00:00 +02:00",
                        "number",
                        "0",
                        "flag",
                        ""))),
            Set.of())) {
      String command = " | fields id,ts,number,flag";
      QueryResponse pushed = fixture.ppl(command, true);
      assertEquivalent(pushed, fixture.ppl(command, false));
      assertEquals(6, pushed.getResults().size());
      List<Integer> numbers = List.of(42, 7, 2, -12, 9, 0);
      List<Boolean> flags = List.of(true, true, false, false, false, false);
      for (int i = 0; i < numbers.size(); i++) {
        Map<String, ExprValue> row = row(pushed, i);
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), row.get("ts").timestampValue());
        assertEquals(numbers.get(i).intValue(), row.get("number").integerValue());
        assertEquals(flags.get(i).booleanValue(), row.get("flag").booleanValue());
      }
      fixture.assertLocalScans(2);
    }
  }

  private static Fixture quantized(String type) throws IOException {
    Map<String, Object> valueMapping =
        type.equals("scaled_float")
            ? Map.of("type", type, "scaling_factor", 100)
            : Map.of("type", type);
    return new Fixture(
        Map.of("sem-a", mapping(Map.of("id", Map.of("type", "keyword"), "value", valueMapping))),
        List.of(
            new Document("sem-a", Map.of("id", "A", "value", FIRST)),
            new Document("sem-a", Map.of("id", "B", "value", SECOND))),
        Set.of("value"));
  }

  private static Map<String, Object> decodingMapping(Map<String, Object> date) {
    return mapping(
        Map.of(
            "id",
            Map.of("type", "keyword"),
            "ts",
            date,
            "number",
            Map.of("type", "integer"),
            "flag",
            Map.of("type", "boolean")));
  }

  private static Map<String, Object> mapping(Map<String, Object> properties) {
    return Map.of("mappings", Map.of("properties", properties));
  }

  private static void assertSuccessful(QueryResponse response) {
    assertNull(response.getError());
  }

  private static void assertEquivalent(QueryResponse pushed, QueryResponse local) {
    assertSuccessful(pushed);
    assertSuccessful(local);
    assertEquals(local.getResults(), pushed.getResults());
    assertEquals(
        local.getSchema().getColumns().stream().map(column -> column.getName()).toList(),
        pushed.getSchema().getColumns().stream().map(column -> column.getName()).toList());
  }

  private static Map<String, ExprValue> row(QueryResponse response, int ordinal) {
    return response.getResults().get(ordinal).tupleValue();
  }

  private static void assertIds(QueryResponse response, List<String> ids) {
    assertSuccessful(response);
    assertEquals(
        ids,
        response.getResults().stream()
            .map(value -> value.tupleValue().get("id").stringValue())
            .toList());
  }

  private record Document(String index, Map<String, Object> source) {}

  private record SortKey(String field, boolean descending, boolean missingFirst) {}

  /**
   * Reads the actual query DSL, applies source projection/sort/pagination, and rejects predicates
   * and aggregations. The rejected operations must remain local in all queries in this fixture.
   */
  private static final class Fixture implements AutoCloseable {
    private static final Set<String> SEARCH_KEYS =
        Set.of("query", "pit", "size", "sort", "_source", "search_after");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Object> mappings;
    private final List<Document> documents;
    private final Set<String> unsafeSorts;
    private final List<Map<String, Object>> searches = new CopyOnWriteArrayList<>();
    private final List<String> unexpected = new CopyOnWriteArrayList<>();
    private final Set<String> activePits = ConcurrentHashMap.newKeySet();
    private final AtomicInteger openedPits = new AtomicInteger();
    private final AtomicInteger closedPits = new AtomicInteger();
    private final HttpServer server;
    private final RestClient client;
    private final UnifiedQueryService pushedService;
    private final UnifiedQueryService localService;

    private Fixture(Map<String, Object> mappings, List<Document> documents, Set<String> unsafeSorts)
        throws IOException {
      this.mappings = mappings;
      this.documents = documents;
      this.unsafeSorts = unsafeSorts;
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
      pushedService =
          new UnifiedQueryService(List.of(new ElasticsearchQueryBackend("es", client)), "es");
      localService =
          new UnifiedQueryService(
              List.of(new ElasticsearchQueryBackend("es", client, false, Map.of(), false)), "es");
    }

    private QueryResponse ppl(String command, boolean pushdown) {
      return service(pushdown).execute(QueryType.PPL, "source=`" + INDEX + "`" + command);
    }

    private QueryResponse sql(String query, boolean pushdown) {
      return service(pushdown).execute(QueryType.SQL, query);
    }

    private String explain(String command) {
      return pushedService
          .explain(null, QueryType.PPL, "source=`" + INDEX + "`" + command)
          .getCalcite()
          .getPhysical();
    }

    private UnifiedQueryService service(boolean pushdown) {
      return pushdown ? pushedService : localService;
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
            String pit = "semantics-pit-" + openedPits.incrementAndGet();
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
      require(
          SEARCH_KEYS.containsAll(request.keySet()),
          "Native aggregations are unsafe here: " + request);
      require(
          Map.of("match_all", Map.of()).equals(request.get("query")),
          "Native predicates must stay local: " + request.get("query"));
      require(activePits.contains(object(request.get("pit")).get("id")), "Inactive PIT");
      int size = ((Number) request.get("size")).intValue();
      require(size > 0 && size <= 1000, "Invalid document page size: " + size);
      List<SortKey> order = sortKeys(request.get("sort"));
      List<Integer> selected = new ArrayList<>();
      for (int i = 0; i < documents.size(); i++) selected.add(i);
      selected.sort(
          (left, right) -> compare(sortValues(left, order), sortValues(right, order), order));
      int start = 0;
      if (request.containsKey("search_after")) {
        List<?> after = list(request.get("search_after"));
        require(after.size() == order.size(), "search_after arity");
        while (start < selected.size()
            && compare(sortValues(selected.get(start), order), after, order) <= 0) start++;
        require(
            start > 0 && compare(sortValues(selected.get(start - 1), order), after, order) == 0,
            "Unknown search_after");
      }
      List<?> includes = list(request.get("_source"));
      List<Map<String, Object>> hits = new ArrayList<>();
      for (int i = start; i < selected.size() && hits.size() < size; i++) {
        int ordinal = selected.get(i);
        Document document = documents.get(ordinal);
        Map<String, Object> source = new LinkedHashMap<>();
        document
            .source()
            .forEach(
                (key, value) -> {
                  if (includes.contains(key)) source.put(key, value);
                });
        hits.add(
            Map.of(
                "_index",
                document.index(),
                "_id",
                Integer.toString(ordinal),
                "_source",
                source,
                "sort",
                sortValues(ordinal, order)));
      }
      return Map.of(
          "hits",
          Map.of("total", Map.of("value", documents.size(), "relation", "eq"), "hits", hits));
    }

    private List<SortKey> sortKeys(Object value) {
      List<SortKey> result = new ArrayList<>();
      for (Object item : list(value)) {
        Map<String, Object> sort = object(item);
        require(sort.size() == 1, "Sort shape: " + sort);
        var entry = sort.entrySet().iterator().next();
        String field = entry.getKey();
        require(!unsafeSorts.contains(field), "Unsafe native sort on " + field);
        if (field.equals("_shard_doc")) {
          require("asc".equals(entry.getValue()), "PIT sort direction");
          result.add(new SortKey(field, false, false));
          continue;
        }
        Map<String, Object> options = object(entry.getValue());
        require(
            Set.of("order", "missing", "unmapped_type").containsAll(options.keySet()),
            "Sort options: " + options);
        require(Set.of("asc", "desc").contains(options.get("order")), "Sort direction");
        require(Set.of("_first", "_last").contains(options.get("missing")), "Missing-value order");
        boolean mappedSomewhere = false;
        for (Object mapping : mappings.values()) {
          Map<String, Object> properties = object(object(mapping).get("mappings"));
          Object fieldMapping = object(properties.get("properties")).get(field);
          if (fieldMapping == null)
            require(
                "keyword".equals(options.get("unmapped_type")),
                "Wildcard sort requires keyword unmapped_type");
          else {
            require(
                "keyword".equals(object(fieldMapping).get("type")),
                "Only exact keyword sorting is supported");
            mappedSomewhere = true;
          }
        }
        require(mappedSomewhere, "Unknown sort field: " + field);
        result.add(
            new SortKey(
                field,
                options.get("order").equals("desc"),
                options.get("missing").equals("_first")));
      }
      require(
          !result.isEmpty() && result.getLast().field().equals("_shard_doc"),
          "Missing PIT tiebreaker");
      return result;
    }

    private List<Object> sortValues(int ordinal, List<SortKey> order) {
      List<Object> values = new ArrayList<>();
      for (SortKey key : order)
        values.add(
            key.field().equals("_shard_doc")
                ? ordinal
                : documents.get(ordinal).source().get(key.field()));
      return values;
    }

    private static int compare(List<?> left, List<?> right, List<SortKey> order) {
      for (int i = 0; i < order.size(); i++) {
        Object a = left.get(i), b = right.get(i);
        if (a == null && b == null) continue;
        SortKey key = order.get(i);
        if (a == null) return key.missingFirst() ? -1 : 1;
        if (b == null) return key.missingFirst() ? 1 : -1;
        int comparison =
            a instanceof Number x && b instanceof Number y
                ? Long.compare(x.longValue(), y.longValue())
                : a.toString().compareTo(b.toString());
        if (comparison != 0) return key.descending() ? -comparison : comparison;
      }
      return 0;
    }

    private void assertLocalScans(int queries) {
      assertComplete(queries);
      for (Map<String, Object> search : searches) {
        assertEquals(Map.of("match_all", Map.of()), search.get("query"));
        assertEquals(List.of(Map.of("_shard_doc", "asc")), search.get("sort"));
        assertFalse(search.containsKey("aggs"));
      }
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
