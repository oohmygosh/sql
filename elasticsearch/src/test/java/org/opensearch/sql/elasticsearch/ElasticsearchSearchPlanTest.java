/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.opensearch.sql.api.QueryExecutionRequest;
import org.opensearch.sql.api.QueryExecutionRequest.FieldFilter;
import org.opensearch.sql.api.QueryExecutionRequest.ResultWindow;
import org.opensearch.sql.api.QueryExecutionRequest.TimeRange;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.executor.QueryType;

/** Always-on final-plan regressions; only metadata is served, no simulated query execution. */
class ElasticsearchSearchPlanTest {
  private static final String SOURCE = "source=events";
  private static final String ORDER = " | sort 0 -timestamp, -id";
  private static final String EQ = "`extracted.process.name`='sshd'";
  private static final TimeRange TIME =
      new TimeRange(
          "timestamp",
          Instant.parse("2026-10-17T00:00:00Z"),
          Instant.parse("2026-10-18T00:00:00Z"),
          ZoneId.of("Asia/Shanghai"));

  @Test
  void mixedPredicatesRetainNativeSelectivityWithOrWithoutSourceConstraints() throws Exception {
    try (Fixture f = new Fixture()) {
      for (TimeRange time : new TimeRange[] {null, TIME}) {
        for (String equality : List.of(EQ, "id='001'")) {
          for (String residual : List.of("length(message)>20", "local_message contains 'PASS'")) {
            String plan =
                f.plan(
                    SOURCE + " | where " + equality + " and " + residual + ORDER, time, List.of());
            assertTrue(
                plan.contains(
                    equality.equals(EQ) ? "term={extracted.process.name=sshd}" : "term={id=001}"),
                plan);
            assertTrue(plan.contains("$condition") || plan.contains("EnumerableFilter"), plan);
            assertFalse(plan.contains("EnumerableSort"), plan);
            assertTrue(plan.contains("EnumerableLimit"), plan);
          }
        }
      }
    }
  }

  @Test
  void sourceConstraintsAndPplFormsHaveTheSameNativeOperations() throws Exception {
    try (Fixture f = new Fixture()) {
      var eq = new FieldFilter("extracted.process.name", "eq", "sshd");
      for (String plan :
          List.of(
              f.plan(
                  SOURCE + " | where " + EQ + " and message contains 'PASS'" + ORDER,
                  TIME,
                  List.of()),
              f.plan(
                  SOURCE + ORDER + " | where " + EQ + " | where message contains 'PASS'",
                  TIME,
                  List.of()),
              f.plan(SOURCE + " | where message contains 'PASS'" + ORDER, TIME, List.of(eq)),
              f.plan(
                  SOURCE + ORDER,
                  TIME,
                  List.of(eq, new FieldFilter("message", "contains", "PASS"))))) {
        assertTrue(plan.contains("term={extracted.process.name=sshd}"), plan);
        assertTrue(plan.contains("regexp={message.wc="), plan);
        assertTrue(plan.contains("range={timestamp="), plan);
        assertTrue(plan.contains("sort=[[{timestamp="), plan);
        assertTrue(plan.contains("fetch=[4]"), plan);
        assertFalse(
            plan.contains("EnumerableLimit")
                || plan.contains("EnumerableSort")
                || plan.contains("$condition")
                || plan.contains("EnumerableFilter"),
            plan);
      }
    }
  }

  @Test
  void residualSortPushdownPreservesLimitAndVolatileFilterBoundaries() throws Exception {
    try (Fixture f = new Fixture()) {
      String residual =
          f.plan(SOURCE + " | where local_message contains 'pass'" + ORDER, TIME, List.of());
      String scan = scan(residual);
      assertTrue(scan.contains("sort=[[{timestamp="), residual);
      assertFalse(scan.contains("fetch=["), residual);
      assertTrue(residual.contains("EnumerableLimit"), residual);
      String bounded =
          f.plan(SOURCE + ORDER + " | head 3 | where message contains 'pass'", TIME, List.of());
      assertTrue(scan(bounded).contains("fetch=[3]"), bounded);
      assertFalse(scan(bounded).contains("regexp="), bounded);
      String random = f.plan(SOURCE + " | where rand() > 0.5" + ORDER, TIME, List.of());
      assertTrue(random.contains("EnumerableSort"), random);
    }
  }

  @Test
  void mixedOrCannotPushOnlyItsNativeBranch() throws Exception {
    try (Fixture f = new Fixture()) {
      String plan =
          f.plan(
              SOURCE + " | where " + EQ + " or local_message contains 'pass'" + ORDER,
              TIME,
              List.of());
      assertFalse(scan(plan).contains("term={extracted.process.name=sshd}"), plan);
      assertTrue(plan.contains("$condition") || plan.contains("EnumerableFilter"), plan);
    }
  }

  private static String scan(String plan) {
    return plan.lines()
        .filter(line -> line.contains("ElasticsearchScan("))
        .findFirst()
        .orElseThrow();
  }

  private static class Fixture implements AutoCloseable {
    private final HttpServer server;
    private final RestClient client;
    private final UnifiedQueryService service;

    Fixture() throws Exception {
      byte[] mapping =
          """
          {"events":{"mappings":{"properties":{
            "id":{"type":"keyword"},"timestamp":{"type":"date"},
            "message":{"type":"text","fields":{"keyword":{"type":"keyword","ignore_above":256},"wc":{"type":"wildcard"}}},
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
      service = new UnifiedQueryService(List.of(new ElasticsearchQueryBackend("es", client)), "es");
    }

    String plan(String query, TimeRange time, List<FieldFilter> filters) {
      return service
          .explain(
              new QueryExecutionRequest(
                  "es", QueryType.PPL, query, time, filters, new ResultWindow(0, 4), null))
          .getCalcite()
          .getPhysical();
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
