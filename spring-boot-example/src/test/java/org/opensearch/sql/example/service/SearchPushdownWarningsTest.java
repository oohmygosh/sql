/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.service;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
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

  /** Metadata-only HTTP: explain must never read documents or execute a backend query. */
  private static final class ElasticsearchPlans implements AutoCloseable {
    private final HttpServer server;
    private final RestClient client;
    private final UnifiedQueryService queries;
    private final List<String> unexpectedRequests =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    ElasticsearchPlans() throws Exception {
      byte[] mapping =
          """
          {"events":{"mappings":{"properties":{
            "id":{"type":"keyword"},"timestamp":{"type":"date"},
            "message":{"type":"text","fields":{"keyword":{"type":"keyword"},"wc":{"type":"wildcard"}}},
            "local_message":{"type":"text"},
            "extracted":{"properties":{"process":{"properties":{"name":{"type":"keyword"}}}}}
          }}}}
          """
              .getBytes(StandardCharsets.UTF_8);
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              boolean metadata =
                  exchange.getRequestMethod().equals("GET")
                      && exchange.getRequestURI().getPath().endsWith("/_mapping");
              if (!metadata) unexpectedRequests.add(exchange.getRequestURI().toString());
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(metadata ? 200 : 400, mapping.length);
              exchange.getResponseBody().write(mapping);
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
