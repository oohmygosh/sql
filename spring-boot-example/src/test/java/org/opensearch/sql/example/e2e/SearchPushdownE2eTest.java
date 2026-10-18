/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.runtime.Hook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.opensearch.sql.example.QueryApplication;
import org.opensearch.sql.example.config.SearchProperties;
import org.opensearch.sql.example.e2e.SearchPushdownCases.Case;
import org.opensearch.sql.example.e2e.SearchPushdownCases.Expectation;
import org.opensearch.sql.example.model.SearchRequest;
import org.opensearch.sql.example.service.SearchDefaultOrder;
import org.opensearch.sql.example.service.SearchService;
import org.opensearch.sql.example.service.SearchTimeResolver;
import org.opensearch.sql.spring.UnifiedQueryProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/** Real HTTP + ES differential tests with plans captured from the servlet execution itself. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class SearchPushdownE2eTest {
  private final List<Map<String, Object>> reports = new ArrayList<>();
  private ServletWebServerApplicationContext application;
  private SearchEsFixture fixture;
  private SearchService localSearch;
  private ObjectMapper json;
  private URI endpoint;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @BeforeAll
  void start() throws Exception {
    assertNotNull(
        System.getenv("ELASTICSEARCH_TEST_URL"),
        "Run :spring-boot-example:searchE2eTest with ELASTICSEARCH_TEST_URL");
    application =
        (ServletWebServerApplicationContext)
            new SpringApplicationBuilder(QueryApplication.class)
                .run("--spring.config.location=classpath:search-e2e.yml");
    json = application.getBean(ObjectMapper.class);
    endpoint = URI.create("http://127.0.0.1:" + application.getWebServer().getPort() + "/search");
    fixture =
        new SearchEsFixture(
            application.getBean(UnifiedQueryProperties.class).getDataSources().get("logs"), json);
    fixture.seed();
    localSearch =
        new SearchService(
            fixture.localQueries(),
            application.getBean(SearchProperties.class),
            application.getBean(SearchTimeResolver.class),
            application.getBean(SearchDefaultOrder.class));
  }

  @TestFactory
  Stream<DynamicTest> queryResultsAndPushdownPlans() {
    List<Case> cases = SearchPushdownCases.cases(fixture.index);
    assertEquals(57, cases.size(), "The accepted regression corpus must not silently shrink");
    return cases.stream().map(test -> DynamicTest.dynamicTest(test.id(), () -> verify(test)));
  }

  private void verify(Case test) throws Exception {
    SearchRequest request = test.request();
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("id", test.id());
    report.put("request", request);
    report.put("expectation", test.expectation());
    report.put("pass", false);
    // Global rather than thread-local: planning runs on the HTTP servlet thread. The hook is
    // removed before the local oracle runs, and this test JVM executes cases sequentially.
    List<RelNode> plans = new CopyOnWriteArrayList<>();
    try {
      HttpResponse<String> response;
      long started = System.nanoTime();
      try (Hook.Closeable ignored =
          Hook.PLAN_BEFORE_IMPLEMENTATION.add((RelRoot root) -> plans.add(root.rel))) {
        response =
            http.send(
                HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
      }
      report.put("httpMillis", (System.nanoTime() - started) / 1_000_000.0);
      report.put("httpStatus", response.statusCode());
      JsonNode actual = json.readTree(response.body());
      report.put("actual", actual);
      if (test.expectation() == Expectation.REJECT) {
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(
            actual.path("message").asText().contains("extracted.process.name"), response.body());
        assertTrue(plans.isEmpty(), "Invalid scope must be rejected before execution");
        report.put("pass", true);
        return;
      }
      assertEquals(200, response.statusCode(), response.body());
      assertEquals(
          test.histogram() ? 2 : 1, plans.size(), "Capture every actual HTTP execution plan");
      SearchPlanAssertions.verify(plans.getFirst(), test);
      if (test.histogram()) SearchPlanAssertions.verifyHistogram(plans.getLast());
      JsonNode expected = json.readTree(json.writeValueAsString(localSearch.execute(request)));
      report.put("localOracle", expected);
      // Fail before comparing two matching histogram-error envelopes as though they were data.
      if (test.histogram()) {
        assertEquals(
            "ok", actual.path("histogram").path("status").asText(), actual.toPrettyString());
        assertEquals(
            "ok", expected.path("histogram").path("status").asText(), expected.toPrettyString());
      }
      assertAll(
          "HTTP result equals the same query with native computation disabled",
          () ->
              assertEquals(
                  expected.get("resolvedTimeRange"), actual.get("resolvedTimeRange"), "time range"),
          () ->
              assertEquals(
                  expected.get("rows"), actual.get("rows"), "schema, row values and row order"),
          () -> assertEquals(expected.get("page"), actual.get("page"), "page size and hasNext"),
          () ->
              assertEquals(
                  expected.get("histogram"), actual.get("histogram"), "all histogram buckets"));
      report.put("pass", true);
    } catch (Exception | AssertionError failure) {
      report.put("failure", failure.toString());
      throw failure;
    } finally {
      report.put("physicalPlans", plans.stream().map(RelOptUtil::toString).toList());
      reports.add(report);
    }
  }

  @AfterAll
  void stop() throws Exception {
    try {
      if (json != null) {
        Path directory =
            Path.of(System.getProperty("search.e2e.reportDirectory", "build/reports/search-e2e"));
        Files.createDirectories(directory);
        json.writerWithDefaultPrettyPrinter()
            .writeValue(directory.resolve("cases.json").toFile(), reports);
      }
    } finally {
      try {
        if (fixture != null) fixture.close();
      } finally {
        if (application != null) application.close();
      }
    }
  }
}
