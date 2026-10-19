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
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.opensearch.sql.api.QueryExecutionRequest;
import org.opensearch.sql.api.QueryExecutionRequest.Histogram;
import org.opensearch.sql.api.QueryExecutionRequest.ResultWindow;
import org.opensearch.sql.api.QueryExecutionRequest.TimeRange;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.QueryType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Final plans, outgoing composite DSL, and results against execution with pushdown disabled. */
class ElasticsearchAggregateSortPushdownTest {
  private static final long DAY = Instant.parse("2024-02-28T00:00:00Z").toEpochMilli();
  private static final long NEXT_DAY = Instant.parse("2024-02-29T00:00:00Z").toEpochMilli();

  @ParameterizedTest
  @CsvSource({"ASC,FIRST", "ASC,LAST", "DESC,FIRST", "DESC,LAST"})
  void rawGroupKeyHonorsBothDirectionsAndExplicitNullPlacement(String direction, String nulls)
      throws Exception {
    List<Map<String, Object>> documents =
        List.of(
            Map.of("rank", 80),
            Map.of(),
            nullableDocument("rank", null),
            Map.of("rank", 10),
            Map.of("rank", 10));
    try (Fixture fixture =
        new Fixture(
            "integer",
            documents,
            List.of(bucket(1, 80), bucket(2, (Object) null), bucket(2, 10)))) {
      QueryExecutionRequest request =
          sql(
              "SELECT `rank`, COUNT(*) AS total FROM events GROUP BY `rank`"
                  + " ORDER BY `rank` "
                  + direction
                  + " NULLS "
                  + nulls);
      QueryResponse result = assertEquivalent(fixture, request, true);

      assertEquals(3, result.getResults().size());
      assertTrue(row(result, nulls.equals("FIRST") ? 0 : 2).get("rank").isNull());
      assertCompositeSources(
          fixture,
          List.of(orderedSource("g0", "terms", Map.of("field", "rank"), direction, nulls)));
      fixture.assertComplete();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"integer", "double"})
  void numericSpanSortKeepsNegativeBucketSemanticsAndNulls(String type) throws Exception {
    List<Map<String, Object>> documents = new ArrayList<>();
    for (int value : new int[] {-21, -19, -1, 0, 1, 19, 21}) documents.add(Map.of("value", value));
    documents.add(nullableDocument("value", null));
    List<Map<String, Object>> buckets =
        type.equals("integer")
            ? List.of(
                bucket(1, -20),
                bucket(1, -10),
                bucket(3, 0),
                bucket(1, 10),
                bucket(1, 20),
                bucket(1, (Object) null))
            : List.of(
                bucket(1, -30.0),
                bucket(1, -20.0),
                bucket(1, -10.0),
                bucket(2, 0.0),
                bucket(1, 10.0),
                bucket(1, 20.0),
                bucket(1, (Object) null));
    try (Fixture fixture = new Fixture(type, documents, buckets)) {
      QueryResponse result =
          assertEquivalent(
              fixture,
              ppl(" | stats count() as total by span(value, 10) as bucket | sort 0 -bucket"),
              true);

      Map<String, Object> source = object(sources(fixture.aggregations().getFirst()).getFirst());
      Map<String, Object> grouping = object(source.get("g0"));
      Map<String, Object> options =
          object(grouping.get(type.equals("integer") ? "terms" : "histogram"));
      assertEquals("desc", options.get("order"));
      assertEquals("last", options.get("missing_order"));
      assertEquals(true, options.get("missing_bucket"));
      if (type.equals("integer")) {
        assertEquals("long", options.get("value_type"));
        assertEquals(
            Map.of("field", "value", "width", 10), object(options.get("script")).get("params"));
      } else {
        assertEquals("value", options.get("field"));
        assertEquals(10.0, options.get("interval"));
      }
      assertEquals(20.0, row(result, 0).get("bucket").doubleValue());
      assertTrue(row(result, result.getResults().size() - 1).get("bucket").isNull());
      assertCompositeRequests(fixture);
      fixture.assertComplete();
    }
  }

  @Test
  void projectedMixedKeysReorderSourcesWithoutRenumberingDecodedGroupPositions() throws Exception {
    List<Map<String, Object>> documents =
        List.of(
            document("alpha", -11.0, NEXT_DAY),
            document("beta", -1.0, DAY),
            document("alpha", 1.0, DAY),
            document("beta", 2.0, DAY),
            document("alpha", 11.0, NEXT_DAY),
            document(null, 3.0, DAY),
            document("alpha", 1.0, NEXT_DAY),
            document("alpha", 5.0, DAY));
    List<Map<String, Object>> buckets =
        List.of(
            bucket(1, 10, NEXT_DAY, -20.0),
            bucket(1, 20, DAY, -10.0),
            bucket(2, 10, DAY, 0.0),
            bucket(1, 20, DAY, 0.0),
            bucket(1, 10, NEXT_DAY, 10.0),
            bucket(1, null, DAY, 0.0),
            bucket(1, 10, NEXT_DAY, 0.0));
    try (Fixture fixture = new Fixture("double", documents, buckets)) {
      QueryResponse result =
          assertEquivalent(
              fixture,
              ppl(
                  " | rename rank as label, value as amount, event_time as day"
                      + " | stats count() as total by label, day, span(amount, 10) as bucket"
                      + " | fields day, total, bucket, label | sort 0 -bucket, day, -label"),
              true);

      assertCompositeSources(
          fixture,
          List.of(
              orderedSource(
                  "g2", "histogram", Map.of("field", "value", "interval", 10.0), "DESC", "LAST"),
              orderedSource("g1", "terms", Map.of("field", "event_time"), "ASC", "FIRST"),
              orderedSource("g0", "terms", Map.of("field", "rank"), "DESC", "LAST")));
      assertEquals(
          List.of("day", "total", "bucket", "label"),
          result.getSchema().getColumns().stream().map(column -> column.getName()).toList());
      assertEquals(7, result.getResults().size());
      assertEquals(Instant.ofEpochMilli(NEXT_DAY), row(result, 0).get("day").timestampValue());
      assertEquals(10.0, row(result, 0).get("bucket").doubleValue());
      assertEquals(20, row(result, 1).get("label").integerValue());
      assertEquals(2L, row(result, 2).get("total").longValue());
      assertTrue(row(result, 3).get("label").isNull());
      fixture.assertComplete();
    }
  }

  @Test
  void remainingUnsortedGroupSourcesRetainTheirDefaultOrdering() throws Exception {
    try (Fixture fixture =
        new Fixture(
            "double",
            List.of(
                document("zulu", -11.0, DAY),
                document("alpha", 1.0, NEXT_DAY),
                document("beta", 11.0, DAY)),
            List.of(bucket(1, "zulu", -20.0), bucket(1, "alpha", 0.0), bucket(1, "beta", 10.0)))) {
      assertEquivalent(
          fixture,
          ppl(" | stats count() as total by category, span(value, 10) as bucket | sort 0 -bucket"),
          true);

      assertCompositeSources(
          fixture,
          List.of(
              orderedSource(
                  "g1", "histogram", Map.of("field", "value", "interval", 10.0), "DESC", "LAST"),
              source("g0", "terms", Map.of("field", "category"))));
      fixture.assertComplete();
    }
  }

  @Test
  void offsetAndFetchConsumeOrderedBucketsAcrossPagesAndReplayOpaqueAfterKeys() throws Exception {
    List<Map<String, Object>> documents = new ArrayList<>();
    List<Map<String, Object>> buckets = new ArrayList<>();
    for (int rank = 10; rank <= 80; rank += 10) {
      documents.add(Map.of("rank", rank));
      buckets.add(bucket(1, rank));
    }
    // These are valid cursor values between pages, deliberately different from emitted keys.
    List<Map<String, Object>> cursors =
        List.of(Map.of("g0", 65), Map.of("g0", 45), Map.of("g0", 25));
    try (Fixture fixture = new Fixture("integer", documents, buckets, cursors)) {
      QueryExecutionRequest request =
          sql(
              "SELECT `rank`, COUNT(*) AS total FROM events GROUP BY `rank`"
                  + " ORDER BY `rank` DESC NULLS LAST LIMIT 4 OFFSET 3");
      String plan = fixture.explain(request);
      assertTrue(plan.contains("EnumerableLimit"), plan);
      assertFalse(scan(plan).contains("fetch=[") || scan(plan).contains("offset=["), plan);
      QueryResponse result = assertEquivalent(fixture, request, true);

      assertEquals(List.of(50, 40, 30, 20), integers(result, "rank"));
      assertEquals(4, fixture.aggregations().size());
      assertFalse(composite(fixture.aggregations().getFirst()).containsKey("after"));
      for (int i = 0; i < cursors.size(); i++)
        assertEquals(cursors.get(i), composite(fixture.aggregations().get(i + 1)).get("after"));
      assertCompositeSources(
          fixture, List.of(orderedSource("g0", "terms", Map.of("field", "rank"), "DESC", "LAST")));
      fixture.assertComplete();
    }
  }

  @Test
  void deterministicResidualFilterKeepsWindowAboveTheFilterWhileSortingBucketsRemotely()
      throws Exception {
    List<Map<String, Object>> documents = new ArrayList<>();
    List<Map<String, Object>> buckets = new ArrayList<>();
    for (int rank = 10; rank <= 80; rank += 10) {
      int count = rank % 20 == 0 ? 2 : 1;
      for (int i = 0; i < count; i++) documents.add(Map.of("rank", rank));
      buckets.add(bucket(count, rank));
    }
    try (Fixture fixture = new Fixture("integer", documents, buckets)) {
      QueryExecutionRequest request =
          ppl(" | stats count() as total by rank | where total > 1 | sort 0 -rank")
              .withWindow(new ResultWindow(1, 2));
      String plan = fixture.explain(request);
      assertTrue(plan.contains("EnumerableLimit"), plan);
      int filterPosition = plan.indexOf("$condition");
      if (filterPosition < 0) filterPosition = plan.indexOf("EnumerableFilter");
      assertTrue(filterPosition >= 0, plan);
      assertTrue(plan.indexOf("EnumerableLimit") < filterPosition, plan);
      assertFalse(scan(plan).contains("fetch=[") || scan(plan).contains("offset=["), plan);
      QueryResponse result = assertEquivalent(fixture, request, true);

      assertEquals(List.of(60, 40), integers(result, "rank"));
      assertTrue(
          fixture.aggregations().size() >= 3, "The window must consume past rejected buckets");
      assertCompositeSources(
          fixture, List.of(orderedSource("g0", "terms", Map.of("field", "rank"), "DESC", "LAST")));
      fixture.assertComplete();
    }
  }

  @Test
  void searchCalendarBucketSortPreservesBusinessDayBoundariesAcrossDaylightSaving()
      throws Exception {
    long march9 = Instant.parse("2024-03-09T05:00:00Z").toEpochMilli();
    long march10 = Instant.parse("2024-03-10T05:00:00Z").toEpochMilli();
    long march11 = Instant.parse("2024-03-11T04:00:00Z").toEpochMilli();
    try (Fixture fixture =
        new Fixture(
            "integer",
            List.of(
                Map.of("event_time", "2024-03-11T12:00:00Z"),
                Map.of("event_time", "2024-03-10T07:00:00Z"),
                Map.of("event_time", "2024-03-09T17:00:00Z"),
                Map.of("event_time", "2024-03-10T06:59:59Z")),
            List.of(bucket(1, march11), bucket(2, march10), bucket(1, march9)))) {
      QueryExecutionRequest request =
          new QueryExecutionRequest(
              "es",
              QueryType.PPL,
              "source=events | fields category",
              new TimeRange(
                  "event_time",
                  Instant.ofEpochMilli(march9),
                  Instant.parse("2024-03-12T04:00:00Z"),
                  ZoneId.of("America/New_York")),
              List.of(),
              null,
              new Histogram("event_time", "1d", ZoneId.of("America/New_York")));
      QueryResponse result = assertEquivalent(fixture, request, true);

      assertCompositeSources(
          fixture,
          List.of(
              orderedSource(
                  "g0",
                  "date_histogram",
                  Map.of(
                      "field",
                      "event_time",
                      "calendar_interval",
                      "1d",
                      "time_zone",
                      "America/New_York"),
                  "ASC",
                  "LAST")));
      assertEquals(
          List.of(
              Instant.ofEpochMilli(march9),
              Instant.ofEpochMilli(march10),
              Instant.ofEpochMilli(march11)),
          result.getResults().stream()
              .map(value -> value.keyValue("__search_bucket").timestampValue())
              .toList());
      fixture.assertComplete();
    }
  }

  @Test
  void keywordGroupSortRetainsUtf16OrderingForSupplementaryCharacters() throws Exception {
    String bmp = "\uE000";
    String supplementary = "\uD800\uDC00";
    try (Fixture fixture =
        new Fixture(
            "integer",
            List.of(Map.of("category", bmp), Map.of("category", supplementary)),
            List.of(bucket(1, bmp), bucket(1, supplementary)))) {
      QueryResponse result =
          assertEquivalent(
              fixture, ppl(" | stats count() as total by category | sort 0 category"), false);

      // UTF-16 orders the surrogate pair before U+E000; Elasticsearch's UTF-8 order is reversed.
      assertEquals(List.of(supplementary, bmp), strings(result, "category"));
      assertCompositeSources(fixture, List.of(source("g0", "terms", Map.of("field", "category"))));
      fixture.assertComplete();
    }
  }

  @Test
  void metricSortRemainsLocalEvenThoughAggregationIsNative() throws Exception {
    try (Fixture fixture =
        new Fixture(
            "integer",
            List.of(
                Map.of("category", "a"),
                Map.of("category", "b"),
                Map.of("category", "b"),
                Map.of("category", "b"),
                Map.of("category", "c"),
                Map.of("category", "c")),
            List.of(bucket(1, "a"), bucket(3, "b"), bucket(2, "c")))) {
      QueryResponse result =
          assertEquivalent(
              fixture, ppl(" | stats count() as total by category | sort 0 -total"), false);

      assertEquals(List.of("b", "c", "a"), strings(result, "category"));
      assertCompositeSources(fixture, List.of(source("g0", "terms", Map.of("field", "category"))));
      fixture.assertComplete();
    }
  }

  @Test
  void stringBinLabelsWithNegativeRangesMustStillSortLocally() throws Exception {
    List<Map<String, Object>> documents = new ArrayList<>();
    for (int value : new int[] {-21, -20, -11, -1, 0, 1, 9, 10, 20, 21})
      documents.add(Map.of("value", value));
    try (Fixture fixture =
        new Fixture(
            "integer",
            documents,
            List.of(
                bucket(1, -30.0),
                bucket(2, -20.0),
                bucket(1, -10.0),
                bucket(3, 0.0),
                bucket(1, 10.0),
                bucket(2, 20.0)))) {
      QueryResponse result =
          assertEquivalent(
              fixture,
              ppl(
                  " | bin value span=10 as bucket | stats count() as total by bucket | sort 0"
                      + " bucket"),
              false);

      assertEquals(
          List.of("-10-0", "-20--10", "-30--20", "0-10", "10-20", "20-30"),
          strings(result, "bucket"));
      assertCompositeSources(
          fixture, List.of(source("g0", "histogram", Map.of("field", "value", "interval", 10.0))));
      fixture.assertComplete();
    }
  }

  private static QueryResponse assertEquivalent(
      Fixture fixture, QueryExecutionRequest request, boolean pushedSort) {
    String plan = fixture.explain(request);
    assertTrue(plan.contains("ElasticsearchScan") && plan.contains("grouping="), plan);
    assertFalse(
        plan.contains("EnumerableAggregate") || plan.contains("EnumerableSortedAggregate"), plan);
    assertEquals(!pushedSort, plan.contains("EnumerableSort"), plan);
    assertTrue(fixture.searches.isEmpty(), "Explain must not execute a search");
    QueryResponse result = fixture.execute(request, true);
    QueryResponse local = fixture.execute(request, false);
    assertNull(result.getError(), () -> String.valueOf(result.getError()));
    assertNull(local.getError(), () -> String.valueOf(local.getError()));
    assertEquals(local.getSchema(), result.getSchema());
    assertEquals(local.getResults(), result.getResults(), request.query());
    assertFalse(fixture.aggregations().isEmpty());
    return result;
  }

  private static QueryExecutionRequest ppl(String commands) {
    return new QueryExecutionRequest("es", QueryType.PPL, "source=events" + commands);
  }

  private static QueryExecutionRequest sql(String query) {
    return new QueryExecutionRequest("es", QueryType.SQL, query);
  }

  private static String scan(String plan) {
    return plan.lines()
        .filter(line -> line.contains("ElasticsearchScan("))
        .findFirst()
        .orElseThrow();
  }

  private static Map<String, ExprValue> row(QueryResponse result, int position) {
    return result.getResults().get(position).tupleValue();
  }

  private static List<String> strings(QueryResponse result, String field) {
    return result.getResults().stream().map(value -> value.keyValue(field).stringValue()).toList();
  }

  private static List<Integer> integers(QueryResponse result, String field) {
    return result.getResults().stream().map(value -> value.keyValue(field).integerValue()).toList();
  }

  private static Map<String, Object> document(String category, double value, long time) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("category", category);
    document.put("rank", category == null ? null : category.equals("alpha") ? 10 : 20);
    document.put("value", value);
    document.put("event_time", time);
    return document;
  }

  private static Map<String, Object> nullableDocument(String name, Object value) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put(name, value);
    return document;
  }

  private static Map<String, Object> bucket(long count, Object... values) {
    Map<String, Object> key = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i++) key.put("g" + i, values[i]);
    return Map.of("key", key, "doc_count", count);
  }

  private static Map<String, Object> source(String name, String kind, Map<String, Object> options) {
    Map<String, Object> missing = new LinkedHashMap<>(options);
    missing.put("missing_bucket", true);
    return Map.of(name, Map.of(kind, missing));
  }

  private static Map<String, Object> orderedSource(
      String name, String kind, Map<String, Object> options, String direction, String nulls) {
    Map<String, Object> ordering = new LinkedHashMap<>(options);
    ordering.put("order", direction.toLowerCase(java.util.Locale.ROOT));
    ordering.put("missing_order", nulls.toLowerCase(java.util.Locale.ROOT));
    return source(name, kind, ordering);
  }

  private static void assertCompositeSources(Fixture fixture, List<Object> expected) {
    assertCompositeRequests(fixture);
    for (Map<String, Object> request : fixture.aggregations())
      assertEquals(expected, composite(request).get("sources"));
  }

  private static void assertCompositeRequests(Fixture fixture) {
    for (Map<String, Object> request : fixture.aggregations()) {
      assertEquals(0, ((Number) request.get("size")).intValue());
      assertEquals(1000, ((Number) composite(request).get("size")).intValue());
      assertFalse(
          request.containsKey("sort")
              || request.containsKey("search_after")
              || request.containsKey("from"));
    }
  }

  private static Map<String, Object> composite(Map<String, Object> request) {
    return object(object(object(request.get("aggs")).get("groups")).get("composite"));
  }

  private static List<?> sources(Map<String, Object> request) {
    return (List<?>) composite(request).get("sources");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  /**
   * Precomputed native buckets are ordered by the actual ES DSL, independently of the local query
   * engine. Two-bucket pages expose ordering, cursor replay, and local OFFSET/FETCH mistakes.
   */
  private static final class Fixture implements AutoCloseable {
    private static final int PAGE_SIZE = 2;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final RestClient client;
    private final UnifiedQueryService nativeService;
    private final UnifiedQueryService localService;
    private final List<Map<String, Object>> documents;
    private final List<Map<String, Object>> buckets;
    private final List<Map<String, Object>> cursors;
    private final Map<String, Object> mappings;
    private final List<Map<String, Object>> searches = new CopyOnWriteArrayList<>();
    private final List<String> unexpected = new CopyOnWriteArrayList<>();
    private final List<String> opened = new CopyOnWriteArrayList<>();
    private final List<String> closed = new CopyOnWriteArrayList<>();
    private final AtomicInteger pit = new AtomicInteger();

    private Fixture(
        String numberType, List<Map<String, Object>> documents, List<Map<String, Object>> buckets)
        throws IOException {
      this(numberType, documents, buckets, List.of());
    }

    private Fixture(
        String numberType,
        List<Map<String, Object>> documents,
        List<Map<String, Object>> buckets,
        List<Map<String, Object>> cursors)
        throws IOException {
      this.documents = documents;
      this.buckets = mapper.readValue(mapper.writeValueAsString(buckets), new TypeReference<>() {});
      this.cursors = mapper.readValue(mapper.writeValueAsString(cursors), new TypeReference<>() {});
      mappings =
          Map.of(
              "events",
              Map.of(
                  "mappings",
                  Map.of(
                      "properties",
                      Map.of(
                          "category", Map.of("type", "keyword"),
                          "rank", Map.of("type", "integer"),
                          "value", Map.of("type", numberType),
                          "event_time", Map.of("type", "date")))));
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
      nativeService = service(true);
      localService = service(false);
    }

    private UnifiedQueryService service(boolean enabled) {
      return new UnifiedQueryService(
          List.of(new ElasticsearchQueryBackend("es", client, false, Map.of(), enabled)), "es");
    }

    private String explain(QueryExecutionRequest request) {
      return nativeService.explain(request).getCalcite().getPhysical();
    }

    private QueryResponse execute(QueryExecutionRequest request, boolean enabled) {
      return (enabled ? nativeService : localService).execute(request);
    }

    private List<Map<String, Object>> aggregations() {
      return searches.stream().filter(request -> request.containsKey("aggs")).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        Map<String, Object> response;
        int status = 200;
        if (method.equals("GET") && (path.equals("/events/_mapping") || path.equals("/_mapping"))) {
          response = mappings;
        } else if (method.equals("POST") && path.equals("/events/_pit")) {
          String id = "pit-" + pit.incrementAndGet();
          opened.add(id);
          response = Map.of("id", id);
        } else if (method.equals("POST") && path.equals("/_search")) {
          Map<String, Object> request =
              mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
          searches.add(request);
          if (request.containsKey("aggs")) {
            response =
                Map.of(
                    "hits",
                    Map.of("hits", List.of()),
                    "aggregations",
                    Map.of("groups", aggregatePage(request)));
          } else {
            int start =
                request.get("search_after") instanceof List<?> after
                    ? ((Number) after.getFirst()).intValue() + 1
                    : 0;
            int size = ((Number) request.get("size")).intValue();
            List<Map<String, Object>> hits = new ArrayList<>();
            for (int i = start; i < documents.size() && hits.size() < size; i++)
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
            response = Map.of("hits", Map.of("hits", hits));
          }
        } else if (method.equals("DELETE") && path.equals("/_pit")) {
          Map<String, Object> request =
              mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
          closed.add(request.get("id").toString());
          response = Map.of("succeeded", true, "num_freed", 1);
        } else {
          unexpected.add(method + " " + path);
          status = 404;
          response = Map.of("error", "Unexpected fixture request");
        }
        byte[] json = mapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, json.length);
        exchange.getResponseBody().write(json);
      }
    }

    private Map<String, Object> aggregatePage(Map<String, Object> request) {
      List<Map<String, Object>> sorted = new ArrayList<>(buckets);
      sorted.sort(
          (left, right) ->
              compareKeys(object(left.get("key")), object(right.get("key")), sources(request)));
      Object after = composite(request).get("after");
      int start = 0;
      if (after != null) {
        start = -1;
        for (int end = PAGE_SIZE; end < sorted.size(); end += PAGE_SIZE)
          if (cursor(sorted, end).equals(after)) {
            start = end;
            break;
          }
        if (start < 0) {
          unexpected.add("Composite after_key was not replayed verbatim: " + after);
          return Map.of("buckets", List.of());
        }
      }
      int end =
          Math.min(
              sorted.size(),
              start + Math.min(PAGE_SIZE, ((Number) composite(request).get("size")).intValue()));
      Map<String, Object> page = new LinkedHashMap<>();
      page.put("buckets", sorted.subList(start, end));
      if (end < sorted.size()) page.put("after_key", cursor(sorted, end));
      return page;
    }

    private Map<String, Object> cursor(List<Map<String, Object>> sorted, int end) {
      int position = end / PAGE_SIZE - 1;
      return position < cursors.size()
          ? cursors.get(position)
          : object(sorted.get(end - 1).get("key"));
    }

    private static int compareKeys(
        Map<String, Object> left, Map<String, Object> right, List<?> sources) {
      for (Object source : sources) {
        Map<String, Object> named = object(source);
        String name = named.keySet().iterator().next();
        Map<String, Object> options = object(object(named.get(name)).values().iterator().next());
        boolean descending = "desc".equals(options.get("order"));
        Object a = left.get(name);
        Object b = right.get(name);
        int result;
        if (a == null || b == null) {
          boolean first =
              "first".equals(options.get("missing_order"))
                  || (!options.containsKey("missing_order") && !descending);
          result = a == b ? 0 : a == null ? (first ? -1 : 1) : (first ? 1 : -1);
        } else {
          result =
              a instanceof Number && b instanceof Number
                  ? new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()))
                  : Arrays.compareUnsigned(
                      a.toString().getBytes(StandardCharsets.UTF_8),
                      b.toString().getBytes(StandardCharsets.UTF_8));
          if (descending) result = -result;
        }
        if (result != 0) return result;
      }
      return 0;
    }

    private void assertComplete() {
      assertEquals(List.of(), unexpected);
      assertEquals(2, opened.size());
      assertEquals(opened, closed);
      assertTrue(searches.stream().anyMatch(request -> !request.containsKey("aggs")));
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
