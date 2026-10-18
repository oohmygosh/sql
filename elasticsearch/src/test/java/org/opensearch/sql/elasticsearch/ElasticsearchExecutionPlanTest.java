/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.object;

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
import java.util.stream.IntStream;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rex.RexInputRef;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.ast.tree.HighlightConfig;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.BackendException;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.ElasticsearchTable;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryPlan.Aggregation;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.QueryType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class ElasticsearchExecutionPlanTest {
  private static final Field ID = new Field("id", "id", "integer");
  private static final Map<String, Object> MATCH_ALL = Map.of("match_all", Map.of());

  @Test
  void planSnapshotsNestedRequestCollectionsAndHighlightOptions() {
    List<Field> fields = new ArrayList<>(List.of(ID));
    List<Object> ids = new ArrayList<>(List.of(1, 2));
    Map<String, Object> terms = new LinkedHashMap<>(Map.of("id", ids));
    Map<String, Object> query = new LinkedHashMap<>(Map.of("terms", terms));
    Map<String, Object> ordering = new LinkedHashMap<>(Map.of("order", "asc"));
    List<Object> sort = new ArrayList<>(List.of(Map.of("id", ordering)));
    Map<String, Object> options = new LinkedHashMap<>(Map.of("fragment_size", 20));
    Map<String, Map<String, Object>> highlightFields =
        new LinkedHashMap<>(Map.of("message", options));
    List<String> preTags = new ArrayList<>(List.of("<b>"));
    List<String> postTags = new ArrayList<>(List.of("</b>"));
    ElasticsearchQueryPlan plan =
        new ElasticsearchQueryPlan(
            fields,
            query,
            sort,
            0,
            null,
            null,
            new HighlightConfig(highlightFields, preTags, postTags, 30));

    fields.clear();
    ids.add(3);
    terms.clear();
    query.clear();
    ordering.put("order", "desc");
    sort.clear();
    options.put("fragment_size", 100);
    highlightFields.clear();
    preTags.clear();
    postTags.clear();

    assertEquals(List.of(ID), plan.fields());
    assertEquals(Map.of("terms", Map.of("id", List.of(1, 2))), plan.query());
    assertEquals(List.of(Map.of("id", Map.of("order", "asc"))), plan.sort());
    assertEquals(Map.of("message", Map.of("fragment_size", 20)), plan.highlight().fields());
    assertEquals(List.of("<b>"), plan.highlight().preTags());
    assertEquals(List.of("</b>"), plan.highlight().postTags());
    assertEquals(30, plan.highlight().fragmentSize());
    assertFalse(plan.isAggregate());
    assertEquals(List.of(), plan.groups());
    assertEquals(List.of(), plan.metrics());
    assertThrows(UnsupportedOperationException.class, () -> plan.fields().clear());
    assertThrows(UnsupportedOperationException.class, () -> plan.query().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> ((List<?>) object(plan.query().get("terms")).get("id")).clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> object(object(plan.sort().getFirst()).get("id")).put("order", "desc"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> plan.highlight().fields().get("message").put("fragment_size", 40));
    assertThrows(UnsupportedOperationException.class, () -> plan.highlight().preTags().clear());
    assertThrows(UnsupportedOperationException.class, () -> plan.highlight().postTags().clear());
  }

  @Test
  void aggregationSnapshotsItsListsAndDefinesAggregateMode() {
    var factory = new JavaTypeFactoryImpl();
    ElasticsearchGrouping group =
        ElasticsearchGrouping.translate(new RexInputRef(0, ID.type(factory)), List.of(ID));
    assertNotNull(group);
    List<ElasticsearchGrouping> groups = new ArrayList<>(List.of(group));
    List<ElasticsearchMetric> metrics = new ArrayList<>();
    Aggregation aggregation = new Aggregation(groups, metrics);
    ElasticsearchQueryPlan plan =
        new ElasticsearchQueryPlan(List.of(ID), MATCH_ALL, List.of(), 0, null, aggregation, null);
    groups.clear();
    assertTrue(plan.isAggregate());
    assertEquals(List.of(group), plan.groups());
    assertEquals(List.of(), plan.metrics());
    assertThrows(UnsupportedOperationException.class, () -> plan.groups().clear());
    assertThrows(UnsupportedOperationException.class, () -> plan.metrics().add(null));
  }

  @Test
  void validatesDocumentBoundsAndRejectsDocumentOptionsOnAggregation() {
    assertThrows(IllegalArgumentException.class, () -> documents(-1, null));
    assertThrows(IllegalArgumentException.class, () -> documents(0, -1L));
    assertDoesNotThrow(() -> documents(0, 0L));
    assertDoesNotThrow(() -> documents(Long.MAX_VALUE, Long.MAX_VALUE));
    Aggregation aggregation = new Aggregation(List.of(), List.of());
    assertDoesNotThrow(
        () ->
            new ElasticsearchQueryPlan(
                List.of(ID), MATCH_ALL, List.of(), 0, null, aggregation, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ElasticsearchQueryPlan(
                List.of(ID), MATCH_ALL, List.of(Map.of("id", "asc")), 0, null, aggregation, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ElasticsearchQueryPlan(
                List.of(ID), MATCH_ALL, List.of(), 1, null, aggregation, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ElasticsearchQueryPlan(
                List.of(ID), MATCH_ALL, List.of(), 0, 0L, aggregation, null));
  }

  @Test
  void unboundedPublicQueryContinuesPastTransportPageAndClosesAtExhaustion() throws Exception {
    try (Fixture fixture = new Fixture(1007)) {
      QueryResponse response = fixture.query(QueryType.PPL, "source=events | fields id");
      assertIds(response, 0, 1007);
      assertEquals(List.of(1000, 1000, 1000), fixture.pageSizes());
      assertFalse(fixture.searches.getFirst().containsKey("search_after"));
      assertEquals(List.of(999), fixture.searches.get(1).get("search_after"));
      assertEquals(List.of(1006), fixture.searches.get(2).get("search_after"));
      fixture.assertClosedLatestPit(3);
    }
  }

  @Test
  void publicFetchStopsExactlyAtLimitWithoutRequestingAnExhaustionPage() throws Exception {
    try (Fixture fixture = new Fixture(1100)) {
      QueryResponse response =
          fixture.query(QueryType.PPL, "source=events | fields id | head 1003");
      assertIds(response, 0, 1003);
      assertEquals(List.of(1000, 3), fixture.pageSizes());
      assertEquals(List.of(999), fixture.searches.get(1).get("search_after"));
      fixture.assertClosedLatestPit(2);
    }
  }

  @Test
  void publicOffsetSkipsRowsAcrossPagesWithoutConsumingFetch() throws Exception {
    try (Fixture fixture = new Fixture(1100)) {
      QueryResponse response =
          fixture.query(QueryType.SQL, "SELECT id FROM events LIMIT 5 OFFSET 1003");
      assertIds(response, 1003, 1008);
      assertEquals(List.of(1000, 8), fixture.pageSizes());
      fixture.assertClosedLatestPit(2);
    }
  }

  @Test
  void zeroFetchDoesNotOpenAnyRemoteContext() throws Exception {
    try (Fixture fixture = new Fixture(3)) {
      try (var rows = fixture.enumerator(documents(0, 0L))) {
        assertFalse(rows.moveNext());
        assertFalse(rows.moveNext());
      }
      assertEquals(0, fixture.opened.get());
      assertTrue(fixture.searches.isEmpty());
      assertTrue(fixture.closed.isEmpty());
      assertTrue(fixture.unexpected.isEmpty());
    }
  }

  @Test
  void largeOffsetAndFetchDoNotOverflowTransportPageSize() throws Exception {
    try (Fixture fixture = new Fixture(3)) {
      try (var rows = fixture.enumerator(documents(Long.MAX_VALUE, Long.MAX_VALUE))) {
        assertFalse(rows.moveNext());
      }
      assertEquals(List.of(1000, 1000), fixture.pageSizes());
      fixture.assertClosedLatestPit(2);
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Failure.class,
      names = {"TIMEOUT", "FAILED_SHARD"})
  void partialResponseFailsPublicQueryAndClosesItsRotatedPit(Failure failure) throws Exception {
    try (Fixture fixture = new Fixture(1002, failure, false)) {
      RuntimeException error =
          assertThrows(
              RuntimeException.class,
              () -> fixture.query(QueryType.PPL, "source=events | fields id"));
      Throwable incomplete =
          findFailure(error, "Elasticsearch returned an incomplete search response");
      assertInstanceOf(IllegalStateException.class, incomplete);
      assertEquals(0, incomplete.getSuppressed().length);
      fixture.assertClosedLatestPit(2);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void decoderFailureClosesLatestPitAndPreservesAnyCleanupError(boolean cleanupFails)
      throws Exception {
    try (Fixture fixture = new Fixture(3, Failure.DECODER, cleanupFails)) {
      RuntimeException error =
          assertThrows(
              RuntimeException.class,
              () -> fixture.query(QueryType.PPL, "source=events | fields id"));
      Throwable decoding = findFailure(error, "Cannot decode Elasticsearch field 'id'");
      assertInstanceOf(IllegalArgumentException.class, decoding);
      assertEquals(cleanupFails ? 1 : 0, decoding.getSuppressed().length);
      if (cleanupFails) {
        BackendException cleanup =
            assertInstanceOf(BackendException.class, decoding.getSuppressed()[0]);
        assertTrue(cleanup.getMessage().contains("DELETE /_pit"));
      }
      fixture.assertClosedLatestPit(1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void closedEnumeratorNeverReopensBeforeOrAfterFirstRow(boolean startFirst) throws Exception {
    try (Fixture fixture = new Fixture(3)) {
      try (var rows = fixture.enumerator(documents(0, null))) {
        if (startFirst) {
          assertTrue(rows.moveNext());
          assertEquals(0, rows.current()[0]);
        }
        rows.close();
        rows.close();
        assertNull(rows.current());
        assertFalse(rows.moveNext());
        assertFalse(rows.moveNext());
      }
      if (startFirst) {
        fixture.assertClosedLatestPit(1);
      } else {
        assertEquals(0, fixture.opened.get());
        assertTrue(fixture.searches.isEmpty());
        assertTrue(fixture.closed.isEmpty());
        assertTrue(fixture.unexpected.isEmpty());
      }
    }
  }

  @Test
  void failedEnumeratorStaysClosedEvenWhenCleanupFails() throws Exception {
    try (Fixture fixture = new Fixture(3, Failure.DECODER, true)) {
      try (var rows = fixture.enumerator(documents(0, null))) {
        IllegalArgumentException failure =
            assertThrows(IllegalArgumentException.class, rows::moveNext);
        assertEquals(1, failure.getSuppressed().length);
        rows.close();
        assertFalse(rows.moveNext());
        assertFalse(rows.moveNext());
      }
      fixture.assertClosedLatestPit(1);
    }
  }

  private static ElasticsearchQueryPlan documents(long offset, Long fetch) {
    return new ElasticsearchQueryPlan(List.of(ID), MATCH_ALL, List.of(), offset, fetch, null, null);
  }

  private static void assertIds(QueryResponse response, int from, int to) {
    assertEquals(
        IntStream.range(from, to).boxed().toList(),
        response.getResults().stream()
            .map(row -> row.tupleValue().get("id").integerValue())
            .toList());
  }

  private static Throwable findFailure(Throwable error, String message) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() != null && cause.getMessage().startsWith(message)) return cause;
    }
    throw new AssertionError("Missing failure: " + message, error);
  }

  private enum Failure {
    NONE,
    TIMEOUT,
    FAILED_SHARD,
    DECODER
  }

  /** A loopback transport that pages documents, rotates PIT IDs, and injects explicit failures. */
  private static final class Fixture implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final RestClient client;
    private final ElasticsearchQueryBackend backend;
    private final UnifiedQueryService service;
    private final int documentCount;
    private final Failure failure;
    private final boolean cleanupFails;
    private final AtomicInteger opened = new AtomicInteger();
    private final List<Map<String, Object>> searches = new CopyOnWriteArrayList<>();
    private final List<String> closed = new CopyOnWriteArrayList<>();
    private final List<String> unexpected = new CopyOnWriteArrayList<>();

    private Fixture(int documentCount) throws IOException {
      this(documentCount, Failure.NONE, false);
    }

    private Fixture(int documentCount, Failure failure, boolean cleanupFails) throws IOException {
      this.documentCount = documentCount;
      this.failure = failure;
      this.cleanupFails = cleanupFails;
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
      service =
          new UnifiedQueryService(
              List.of(backend), "es", Map.of("plugins.query.size_limit", 10000));
    }

    private QueryResponse query(QueryType type, String query) {
      return service.execute(type, query);
    }

    private ElasticsearchSearchEnumerator enumerator(ElasticsearchQueryPlan plan) {
      var table = new ElasticsearchTable(backend, "events", List.of(ID));
      return new ElasticsearchSearchEnumerator(
          table, plan, table.getRowType(new JavaTypeFactoryImpl()));
    }

    private List<Integer> pageSizes() {
      return searches.stream().map(request -> ((Number) request.get("size")).intValue()).toList();
    }

    private void assertClosedLatestPit(int pages) {
      assertEquals(List.of(), unexpected);
      assertEquals(1, opened.get());
      assertEquals(pages, searches.size());
      for (int i = 0; i < pages; i++) {
        assertEquals("pit-" + i, object(searches.get(i).get("pit")).get("id"));
      }
      assertEquals(List.of("pit-" + pages), closed, "Only the newest PIT is deleted, exactly once");
    }

    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        int status = 200;
        Map<String, Object> response;
        if (method.equals("GET") && (path.equals("/events/_mapping") || path.equals("/_mapping"))) {
          response =
              Map.of(
                  "events",
                  Map.of(
                      "mappings", Map.of("properties", Map.of("id", Map.of("type", "integer")))));
        } else if (method.equals("POST") && path.equals("/events/_pit")) {
          opened.incrementAndGet();
          response = Map.of("id", "pit-0");
        } else if (method.equals("POST") && path.equals("/_search")) {
          Map<String, Object> request =
              mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
          searches.add(request);
          int page = searches.size();
          int size = ((Number) request.get("size")).intValue();
          int from =
              request.get("search_after") instanceof List<?> after
                  ? ((Number) after.getFirst()).intValue() + 1
                  : 0;
          List<Map<String, Object>> hits = new ArrayList<>();
          for (int i = from; i < Math.min(documentCount, from + size); i++) {
            Object value = failure == Failure.DECODER && i == 0 ? "not-an-integer" : i;
            hits.add(
                Map.of(
                    "_index",
                    "events",
                    "_id",
                    Integer.toString(i),
                    "_source",
                    Map.of("id", value),
                    "sort",
                    List.of(i)));
          }
          response = new LinkedHashMap<>();
          response.put("pit_id", "pit-" + page);
          response.put(
              "hits",
              Map.of("hits", hits, "total", Map.of("value", documentCount, "relation", "eq")));
          response.put("timed_out", failure == Failure.TIMEOUT && page == 2);
          response.put(
              "_shards", Map.of("failed", failure == Failure.FAILED_SHARD && page == 2 ? 1 : 0));
        } else if (method.equals("DELETE") && path.equals("/_pit")) {
          Map<String, Object> request =
              mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
          closed.add(request.get("id").toString());
          status = cleanupFails ? 500 : 200;
          response =
              cleanupFails
                  ? Map.of("error", "injected PIT cleanup failure")
                  : Map.of("succeeded", true);
        } else {
          unexpected.add(method + " " + path);
          status = 404;
          response = Map.of("error", "Unexpected fixture request");
        }
        byte[] body = mapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
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
