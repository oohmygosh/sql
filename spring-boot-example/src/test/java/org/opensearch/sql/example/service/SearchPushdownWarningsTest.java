/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.opensearch.sql.api.QueryExecutionRequest;
import org.opensearch.sql.api.QueryExecutionRequest.ResultWindow;
import org.opensearch.sql.api.QueryExecutionRequest.TimeRange;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.backend.QueryBackend;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend;
import org.opensearch.sql.example.config.SearchProperties;
import org.opensearch.sql.example.controller.SearchController;
import org.opensearch.sql.example.model.SearchRequest;
import org.opensearch.sql.example.model.SearchResponse;
import org.opensearch.sql.executor.CalciteExecutionEngine;
import org.opensearch.sql.executor.ExecutionEngine;
import org.opensearch.sql.executor.QueryType;
import org.opensearch.sql.executor.Warning;
import org.opensearch.sql.spring.UnifiedQueryProperties;

/** Exercises final optimized plans and the search response without external services. */
class SearchPushdownWarningsTest {
  private static final String INCOMPLETE = "PUSHDOWN_INCOMPLETE";
  private static final String ORDER = " | sort 0 -timestamp, -id";
  private static final TimeRange TIME =
      new TimeRange(
          "timestamp",
          Instant.parse("2026-10-17T00:00:00Z"),
          Instant.parse("2026-10-17T00:03:00Z"),
          ZoneId.of("UTC"));

  @Test
  void pushedFiltersSortAndWindowDoNotWarnForProjectionOrRename() throws Exception {
    try (var fixture = new ElasticsearchPlans()) {
      for (String query :
          List.of(
              "source=events | where id='001' and message contains 'PASS'" + ORDER,
              "source=events | where id='001'"
                  + ORDER
                  + " | fields id,message | rename message as text",
              "source=events" + ORDER + " | fields extracted.process.name")) {
        RelNode plan = fixture.plan(QueryType.PPL, query);
        String text = RelOptUtil.toString(plan);
        assertTrue(text.contains("ElasticsearchScan"), text);
        assertTrue(text.contains("sort=[[{timestamp="), text);
        assertTrue(text.contains("fetch=[4]"), text);
        assertEquals(Set.of(), SearchPushdownWarnings.localOperators(plan), text);
      }
      RelNode constants =
          fixture.plan(
              QueryType.SQL,
              "SELECT id AS event_id, 1 AS marker FROM events ORDER BY timestamp DESC, id DESC");
      assertEquals(
          Set.of(),
          SearchPushdownWarnings.localOperators(constants),
          RelOptUtil.toString(constants));
    }
  }

  @Test
  void autoSearchHistogramUsesShanghaiCalendarBucketsWithoutLocalWork() throws Exception {
    Instant now = Instant.parse("2026-10-24T04:00:00Z");
    Instant firstBucket = Instant.parse("2026-10-16T16:00:00Z");
    Instant lastBucket = Instant.parse("2026-10-23T16:00:00Z");
    try (var fixture =
        new ElasticsearchPlans(
            "logevent-2026.10.17",
            List.of(
                Map.of("key", Map.of("g0", firstBucket.toEpochMilli()), "doc_count", 2),
                Map.of("key", Map.of("g0", lastBucket.toEpochMilli()), "doc_count", 1)))) {
      List<RelNode> plans = new ArrayList<>();
      SearchResponse response;
      try (Hook.Closeable ignored =
          Hook.PLAN_BEFORE_IMPLEMENTATION.addThread((RelRoot root) -> plans.add(root.rel))) {
        // Search builds SEARCH_TIME_BUCKET via withHistogram, rather than native PPL SPAN.
        response =
            fixture
                .controller(now)
                .search(
                    new SearchRequest(
                        "logs",
                        QueryType.PPL,
                        "source=`logevent-*`",
                        new SearchRequest.TimeRange("timestamp", "now-7d", "now", "Asia/Shanghai"),
                        List.of(),
                        new SearchRequest.Page(1, 20),
                        new SearchRequest.Histogram("auto"),
                        List.of("histogram")));
      }
      assertEquals("ok", response.histogram().status(), response.histogram().reason());
      assertEquals("1d", response.histogram().interval());
      assertEquals("Asia/Shanghai", response.histogram().timeZone());
      assertEquals(Instant.parse("2026-10-17T04:00:00Z"), response.resolvedTimeRange().from());
      assertEquals(now, response.resolvedTimeRange().to());
      assertEquals(firstBucket, response.histogram().buckets().getFirst().from());
      assertEquals(lastBucket, response.histogram().buckets().getLast().from());
      assertEquals(
          List.of(2L, 0L, 0L, 0L, 0L, 0L, 0L, 1L),
          response.histogram().buckets().stream().map(SearchResponse.Bucket::count).toList());
      assertTrue(incomplete(response).isEmpty(), response.warnings().toString());

      // The first plan validates the source; the final plan executes the independent chart.
      assertEquals(2, plans.size());
      RelNode histogram = plans.getLast();
      String physical = RelOptUtil.toString(histogram);
      assertTrue(physical.contains("ElasticsearchScan"), physical);
      assertTrue(physical.contains("date_histogram"), physical);
      assertFalse(physical.contains("EnumerableSort"), physical);
      assertEquals(Set.of(), SearchPushdownWarnings.localOperators(histogram), physical);

      assertEquals(1, fixture.searches.size(), fixture.searches.toString());
      JsonNode search = fixture.searches.getFirst();
      assertEquals(0, search.path("size").asInt(-1), search.toString());
      JsonNode sources = search.path("aggs").path("groups").path("composite").path("sources");
      assertTrue(sources.isArray(), search.toString());
      assertEquals(1, sources.size(), search.toString());
      JsonNode dateHistogram = sources.get(0).path("g0").path("date_histogram");
      assertEquals("timestamp", dateHistogram.path("field").asText(), search.toString());
      assertEquals("1d", dateHistogram.path("calendar_interval").asText(), search.toString());
      assertEquals("Asia/Shanghai", dateHistogram.path("time_zone").asText(), search.toString());
      assertEquals("asc", dateHistogram.path("order").asText(), search.toString());
      assertFalse(dateHistogram.has("fixed_interval"), search.toString());
      assertFalse(
          search.has("sort"), "Bucket ordering must not become document sorting: " + search);
      assertEquals(
          List.of("POST /logevent-*/_pit", "POST /_search", "DELETE /_pit"),
          fixture.executionRequests);
      assertTrue(fixture.unexpectedRequests.isEmpty(), fixture.unexpectedRequests.toString());
    }
  }

  @Test
  void partiallyPushedFilterReportsResidualFilterAndWindow() throws Exception {
    try (var fixture = new ElasticsearchPlans()) {
      for (String residual : List.of("length(message)>20", "local_message contains 'PASS'")) {
        RelNode plan =
            fixture.plan(QueryType.PPL, "source=events | where id='001' and " + residual + ORDER);
        String text = RelOptUtil.toString(plan);
        Set<String> operators = SearchPushdownWarnings.localOperators(plan);
        assertTrue(text.contains("term={id=001}"), text);
        assertTrue(text.contains("sort=[[{timestamp="), text);
        assertTrue(
            operators.stream().anyMatch(name -> name.endsWith("Calc") || name.endsWith("Filter")),
            operators.toString());
        assertTrue(operators.contains("EnumerableLimit"), operators.toString());
        assertFalse(operators.contains("EnumerableSort"), operators.toString());
        assertFalse(operators.contains("ElasticsearchScan"), operators.toString());
      }
    }
  }

  @Test
  void localAggregateExpressionAndSortRemainVisibleAfterOptimization() throws Exception {
    try (var fixture = new ElasticsearchPlans()) {
      RelNode aggregate =
          fixture.plan(QueryType.PPL, "source=events | stats count() as n by local_message");
      assertTrue(
          SearchPushdownWarnings.localOperators(aggregate).stream()
              .anyMatch(name -> name.endsWith("Aggregate")),
          RelOptUtil.toString(aggregate));
      RelNode expression =
          fixture.plan(QueryType.PPL, "source=events | eval n=length(message) | fields n");
      assertTrue(
          SearchPushdownWarnings.localOperators(expression).stream()
              .anyMatch(name -> name.endsWith("Calc") || name.endsWith("Project")),
          RelOptUtil.toString(expression));
      RelNode sort = fixture.plan(QueryType.PPL, "source=events | where rand()>0.5" + ORDER);
      assertTrue(
          SearchPushdownWarnings.localOperators(sort).contains("EnumerableSort"),
          RelOptUtil.toString(sort));
    }
  }

  @Test
  void controllerReturnsLocalWorkWarningAlongsideExistingUnstableOrderWarning() {
    SearchResponse response =
        controller()
            .search(
                request("SELECT id FROM events WHERE length(message)>3", null, List.of("rows")));
    assertEquals(2, response.rows().datarows().size());
    assertArrayEquals(new Object[] {1}, response.rows().datarows().getFirst());
    assertArrayEquals(new Object[] {3}, response.rows().datarows().getLast());
    assertFalse(response.page().hasNext());
    assertTrue(
        response.warnings().stream()
            .anyMatch(warning -> warning.getType().equals("UNSTABLE_ORDER")));
    List<Warning> pushdown = incomplete(response);
    assertEquals(1, pushdown.size());
    Warning warning = pushdown.getFirst();
    assertTrue(warning.getMessage().contains("下推"), warning.toString());
    assertTrue(warning.getDetail().contains("明细查询"), warning.toString());
    assertTrue(
        warning.getDetail().contains("Calc") || warning.getDetail().contains("Filter"),
        warning.toString());
    assertTrue(warning.getDetail().contains("Limit"), warning.toString());
  }

  @Test
  void histogramAndRowsHaveIndependentWarningsWhichDoNotLeakIntoLaterRequests() {
    SearchController controller = controller();
    SearchRequest.TimeRange range =
        new SearchRequest.TimeRange(
            "timestamp", TIME.from().toString(), TIME.to().toString(), "UTC");
    SearchResponse combined =
        controller.search(
            request(
                "SELECT id FROM events WHERE length(message)>3",
                range,
                List.of("rows", "histogram")));
    assertEquals("ok", combined.histogram().status(), combined.histogram().reason());
    assertEquals(
        List.of(1L, 0L, 1L),
        combined.histogram().buckets().stream().map(SearchResponse.Bucket::count).toList());
    List<Warning> warnings = incomplete(combined);
    assertEquals(2, warnings.size(), warnings.toString());
    assertEquals(
        1, warnings.stream().filter(warning -> warning.getDetail().contains("明细查询")).count());
    assertEquals(
        1, warnings.stream().filter(warning -> warning.getDetail().contains("时间分布")).count());
    assertTrue(
        warnings.stream()
            .filter(warning -> warning.getDetail().contains("时间分布"))
            .allMatch(warning -> warning.getDetail().contains("Aggregate")),
        warnings.toString());

    SearchResponse rows =
        controller.search(request("SELECT id FROM events ORDER BY id", null, List.of("rows")));
    assertEquals(1, incomplete(rows).size(), rows.warnings().toString());
    assertFalse(incomplete(rows).getFirst().getDetail().contains("时间分布"));
    assertFalse(
        rows.warnings().stream().anyMatch(warning -> warning.getType().equals("UNSTABLE_ORDER")));

    SearchResponse unsupported =
        controller.search(request("SELECT id FROM events", null, List.of("histogram")));
    assertEquals("unsupported", unsupported.histogram().status());
    assertTrue(unsupported.warnings().isEmpty(), unsupported.warnings().toString());
    assertEquals(
        2, incomplete(combined).size(), "Later requests must not mutate earlier responses");
  }

  private static List<Warning> incomplete(SearchResponse response) {
    return response.warnings().stream()
        .filter(warning -> INCOMPLETE.equals(warning.getType()))
        .toList();
  }

  private static SearchRequest request(
      String query, SearchRequest.TimeRange range, List<String> include) {
    return new SearchRequest(
        "memory",
        QueryType.SQL,
        query,
        range,
        List.of(),
        new SearchRequest.Page(1, 3),
        new SearchRequest.Histogram("1m"),
        include);
  }

  private static SearchController controller() {
    var backend =
        new QueryBackend() {
          @Override
          public String name() {
            return "memory";
          }

          @Override
          public String type() {
            return "memory";
          }

          @Override
          public ExecutionEngine executionEngine() {
            return new CalciteExecutionEngine();
          }

          @Override
          public Schema createSchema(SchemaPlus parent) {
            return new AbstractSchema() {
              @Override
              protected Map<String, Table> getTableMap() {
                return Map.of("events", new Events());
              }
            };
          }
        };
    var properties = new SearchProperties(null, null, null, "UTC", Map.of(), Map.of());
    var sources = new UnifiedQueryProperties();
    sources.setDefaultDataSource("memory");
    return new SearchController(
        new SearchService(
            new UnifiedQueryService(List.of(backend), "memory"),
            properties,
            new SearchTimeResolver(properties, sources),
            new SearchDefaultOrder(properties, sources)));
  }

  private static final class Events extends AbstractTable implements ScannableTable {
    @Override
    public RelDataType getRowType(RelDataTypeFactory factory) {
      return factory
          .builder()
          .add("id", SqlTypeName.INTEGER)
          .add("timestamp", SqlTypeName.TIMESTAMP)
          .add("message", SqlTypeName.VARCHAR)
          .build();
    }

    @Override
    public Enumerable<Object[]> scan(DataContext context) {
      return Linq4j.asEnumerable(
          List.of(
              new Object[] {1, TIME.from().plusSeconds(10).toEpochMilli(), "first"},
              new Object[] {2, TIME.from().plusSeconds(70).toEpochMilli(), "x"},
              new Object[] {3, TIME.from().plusSeconds(130).toEpochMilli(), "third"}));
    }
  }

  /** Metadata-only by default; optional native histogram buckets allow full service execution. */
  private static final class ElasticsearchPlans implements AutoCloseable {
    private final HttpServer server;
    private final RestClient client;
    private final UnifiedQueryService queries;
    private final List<JsonNode> searches = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> executionRequests =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> unexpectedRequests =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    ElasticsearchPlans() throws Exception {
      this("events", null);
    }

    ElasticsearchPlans(String index, List<Map<String, Object>> buckets) throws Exception {
      ObjectMapper mapper = new ObjectMapper();
      JsonNode mapping =
          mapper.readTree(
              """
              {"%s":{"mappings":{"properties":{
                "id":{"type":"keyword"},"timestamp":{"type":"date"},
                "message":{"type":"text","fields":{"keyword":{"type":"keyword"},"wc":{"type":"wildcard"}}},
                "local_message":{"type":"text"},
                "extracted":{"properties":{"process":{"properties":{"name":{"type":"keyword"}}}}}
              }}}}
              """
                  .formatted(index));
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              String method = exchange.getRequestMethod();
              String path = exchange.getRequestURI().getPath();
              Object response;
              int status = 200;
              boolean metadata = method.equals("GET") && path.endsWith("/_mapping");
              if (!metadata) executionRequests.add(method + " " + path);
              if (metadata) {
                response = mapping;
              } else if (buckets != null && method.equals("POST") && path.endsWith("/_pit")) {
                response = Map.of("id", "histogram-pit");
              } else if (buckets != null && method.equals("POST") && path.equals("/_search")) {
                JsonNode search = mapper.readTree(exchange.getRequestBody());
                searches.add(search);
                if (search.path("size").asInt(-1) != 0)
                  unexpectedRequests.add("Document search instead of native histogram: " + search);
                response =
                    Map.of(
                        "hits", Map.of("hits", List.of()),
                        "aggregations", Map.of("groups", Map.of("buckets", buckets)));
              } else if (buckets != null && method.equals("DELETE") && path.equals("/_pit")) {
                response = Map.of("succeeded", true, "num_freed", 1);
              } else {
                unexpectedRequests.add(method + " " + path);
                status = 400;
                response = Map.of("error", "Unexpected fixture request");
              }
              byte[] json = mapper.writeValueAsBytes(response);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(status, json.length);
              exchange.getResponseBody().write(json);
            }
          });
      server.start();
      client =
          RestClient.builder(
                  new HttpHost(
                      server.getAddress().getAddress().getHostAddress(),
                      server.getAddress().getPort(),
                      "http"))
              .build();
      queries = new UnifiedQueryService(List.of(new ElasticsearchQueryBackend("es", client)), "es");
    }

    SearchController controller(Instant now) {
      var properties =
          new SearchProperties(
              null, null, null, "Asia/Shanghai", Map.of("logs", "timestamp"), Map.of("logs", "id"));
      var sources = new UnifiedQueryProperties();
      sources.setDefaultDataSource("logs");
      return new SearchController(
          new SearchService(
              new UnifiedQueryService(
                  List.of(new ElasticsearchQueryBackend("logs", client)), "logs"),
              properties,
              new SearchTimeResolver(properties, "logs", Clock.fixed(now, ZoneOffset.UTC)),
              new SearchDefaultOrder(properties, sources)));
    }

    RelNode plan(QueryType language, String query) {
      List<RelNode> plans = new ArrayList<>();
      try (Hook.Closeable ignored =
          Hook.PLAN_BEFORE_IMPLEMENTATION.addThread((RelRoot root) -> plans.add(root.rel))) {
        var explanation =
            queries.explain(
                new QueryExecutionRequest(
                    "es", language, query, TIME, List.of(), new ResultWindow(0, 4), null));
        assertEquals(1, plans.size(), explanation.getCalcite().getPhysical());
        assertEquals(RelOptUtil.toString(plans.getFirst()), explanation.getCalcite().getPhysical());
      }
      assertTrue(unexpectedRequests.isEmpty(), unexpectedRequests.toString());
      return plans.getFirst();
    }

    @Override
    public void close() throws Exception {
      try {
        client.close();
      } finally {
        server.stop(0);
      }
    }
  }
}
