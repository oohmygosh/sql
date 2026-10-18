/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opensearch.sql.api.QueryExecutionRequest;
import org.opensearch.sql.api.QueryExecutionRequest.FieldFilter;
import org.opensearch.sql.api.QueryExecutionRequest.ResultWindow;
import org.opensearch.sql.api.QueryExecutionRequest.TimeRange;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.QueryType;
import tools.jackson.databind.ObjectMapper;

/** Compare final plans and real ES results, including the fully local execution oracle. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "ELASTICSEARCH_TEST_URL", matches = ".+")
class ElasticsearchSearchPushdownIntegrationTest {
  private static final long START = 1792247588208L;
  private static final String EQ = "`extracted.process.name` = 'sshd'";
  private static final FieldFilter FILTER = new FieldFilter("extracted.process.name", "eq", "sshd");
  private static final String ORDER = " | sort 0 -timestamp, -id";
  private static final String FIELDS = " | fields id,message";
  private static final TimeRange TIME =
      new TimeRange(
          "timestamp",
          Instant.ofEpochMilli(START),
          Instant.ofEpochMilli(START + 12000),
          ZoneId.of("Asia/Shanghai"));
  private final String index = "uq-search-pushdown-" + UUID.randomUUID();
  private final List<String> created = new ArrayList<>();
  private final ObjectMapper json = new ObjectMapper();
  private RestClient client;
  private UnifiedQueryService nativeService;
  private UnifiedQueryService localService;

  @BeforeAll
  void setup() throws Exception {
    client = ElasticsearchIntegrationTest.authenticatedClient();
    try {
      createIndex(index, true);
      String[] messages = {
        "Accepted password",
        "password",
        "PASSword\ncontinued",
        "nothing",
        null,
        null,
        "x".repeat(300) + "pass",
        "literal % _ * ? \\ pass",
        "PASS",
        "",
        "paß",
        "pass"
      };
      StringBuilder bulk = new StringBuilder();
      for (int i = 0; i < messages.length; i++) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", String.format("%03d", i + 1));
        doc.put("timestamp", START + (i == 11 ? 20000 : (i + 1) * 1000));
        doc.put("extracted", Map.of("process", Map.of("name", i == 1 || i == 8 ? "cron" : "sshd")));
        if (i != 5) {
          doc.put("message", messages[i]);
          doc.put("local_message", messages[i]);
        }
        bulk.append("{\"index\":{}}\n").append(json.writeValueAsString(doc)).append('\n');
      }
      Request insert = new Request("POST", "/" + index + "/_bulk?refresh=true");
      insert.setJsonEntity(bulk.toString());
      assertFalse(
          json.readTree(EntityUtils.toString(client.performRequest(insert).getEntity()))
              .get("errors")
              .asBoolean());
      nativeService = service(true);
      localService = service(false);
    } catch (Exception | AssertionError e) {
      cleanup();
      throw e;
    }
  }

  private void createIndex(String name, boolean wildcard) throws Exception {
    Map<String, Object> children = new LinkedHashMap<>();
    children.put("keyword", Map.of("type", "keyword", "ignore_above", 256));
    if (wildcard) children.put("wc", Map.of("type", "wildcard"));
    Map<String, Object> properties =
        Map.of(
            "id", Map.of("type", "keyword"),
            "timestamp", Map.of("type", "date", "format", "epoch_millis"),
            "message", Map.of("type", "text", "fields", children),
            "local_message", Map.of("type", "text"),
            "extracted",
                Map.of(
                    "properties",
                    Map.of(
                        "process",
                        Map.of("properties", Map.of("name", Map.of("type", "keyword"))))));
    Request create = new Request("PUT", "/" + name);
    create.setJsonEntity(
        json.writeValueAsString(Map.of("mappings", Map.of("properties", properties))));
    client.performRequest(create);
    created.add(name);
  }

  private UnifiedQueryService service(boolean enabled) {
    return new UnifiedQueryService(
        List.of(new ElasticsearchQueryBackend("es", client, false, Map.of(), enabled)), "es");
  }

  private String source() {
    return "source=`" + index + "`";
  }

  private QueryExecutionRequest request(
      String query, List<FieldFilter> filters, int offset, int size) {
    return new QueryExecutionRequest(
        "es",
        query.startsWith("SELECT") ? QueryType.SQL : QueryType.PPL,
        query,
        TIME,
        filters,
        new ResultWindow(offset, size),
        null);
  }

  private String plan(QueryExecutionRequest request) {
    return nativeService.explain(request).getCalcite().getPhysical();
  }

  private QueryResponse execute(UnifiedQueryService service, QueryExecutionRequest request) {
    QueryResponse response = service.execute(request);
    assertNull(response.getError(), () -> String.valueOf(response.getError()));
    return response;
  }

  private void agreesWithLocal(QueryExecutionRequest request, List<String> expectedIds) {
    QueryResponse actual = execute(nativeService, request);
    QueryResponse expected = execute(localService, request);
    assertEquals(expected.getSchema().getColumns(), actual.getSchema().getColumns());
    assertEquals(expected.getResults(), actual.getResults(), request.query());
    assertEquals(
        expectedIds,
        actual.getResults().stream().map(row -> row.keyValue("id").stringValue()).toList(),
        request.query());
  }

  private void assertNative(QueryExecutionRequest request) {
    String plan = plan(request);
    assertTrue(plan.contains("term={extracted.process.name=sshd}"), plan);
    assertTrue(plan.contains("regexp={message.wc="), plan);
    assertTrue(plan.contains("range={timestamp="), plan);
    assertTrue(plan.contains("sort=[[{timestamp="), plan);
    assertTrue(plan.contains("fetch=[" + request.window().limit() + "]"), plan);
    assertFalse(plan.contains("$condition") || plan.contains("EnumerableFilter"), plan);
    assertFalse(plan.contains("EnumerableSort") || plan.contains("EnumerableLimit"), plan);
  }

  @Test
  void equivalentPplSqlAndStructuredPredicatesPushFilterOrderAndWindow() {
    String contains = "message contains 'PASS'";
    List<QueryExecutionRequest> requests =
        List.of(
            request(
                source() + " | where " + EQ + " and " + contains + ORDER + FIELDS, List.of(), 0, 4),
            request(
                source() + ORDER + " | where " + EQ + " and " + contains + FIELDS, List.of(), 0, 4),
            request(
                source() + " | where " + EQ + " | where " + contains + ORDER + FIELDS,
                List.of(),
                0,
                4),
            request(source() + " | where " + contains + ORDER + FIELDS, List.of(FILTER), 0, 4),
            request(
                source() + ORDER + FIELDS,
                List.of(FILTER, new FieldFilter("message", "contains", "PASS")),
                0,
                4),
            request(
                source() + " | where " + EQ + " and `message.wc` contains 'PASS'" + ORDER + FIELDS,
                List.of(),
                0,
                4),
            request(
                "SELECT id,message FROM `"
                    + index
                    + "` WHERE `extracted.process.name` = 'sshd'"
                    + " AND message LIKE '%PASS%' ORDER BY timestamp DESC,id DESC",
                List.of(),
                0,
                4));
    QueryResponse first = execute(nativeService, requests.getFirst());
    for (QueryExecutionRequest request : requests) {
      assertNative(request);
      agreesWithLocal(request, List.of("008", "007", "003", "001"));
      assertEquals(first.getResults(), execute(nativeService, request).getResults());
    }
    QueryExecutionRequest page = requests.getFirst().withWindow(new ResultWindow(2, 2));
    assertNative(page);
    agreesWithLocal(page, List.of("003", "001"));
  }

  @Test
  void partialAndPushdownSurvivesTimeRangeAndResidualFunctions() {
    for (String predicate : List.of("local_message contains 'PASS'", "length(message)>20")) {
      for (String equality : List.of(EQ, "id >= '003'")) {
        QueryExecutionRequest request =
            request(
                source() + " | where " + equality + " and " + predicate + ORDER + FIELDS,
                List.of(),
                0,
                100);
        String plan = plan(request);
        assertTrue(
            plan.contains(
                equality.equals(EQ) ? "term={extracted.process.name=sshd}" : "range={id="),
            plan);
        assertTrue(plan.contains("$condition") || plan.contains("EnumerableFilter"), plan);
        assertFalse(plan.contains("EnumerableSort"), plan);
        assertEquals(
            execute(localService, request).getResults(),
            execute(nativeService, request).getResults());
      }
    }
  }

  @Test
  void residualFilterKeepsPaginationAboveFilterWhileOrderMovesBelowIt() {
    for (String query :
        List.of(
            source() + ORDER + " | where local_message contains 'PASS'" + FIELDS,
            source() + " | where local_message contains 'PASS'" + ORDER + FIELDS)) {
      QueryExecutionRequest request = request(query, List.of(FILTER), 1, 2);
      String plan = plan(request);
      assertFalse(plan.contains("EnumerableSort"), plan);
      assertTrue(plan.contains("EnumerableLimit"), plan);
      String scan =
          plan.lines().filter(line -> line.contains("ElasticsearchScan")).findFirst().orElseThrow();
      assertTrue(scan.contains("sort=[[{timestamp="), plan);
      assertFalse(scan.contains("fetch=[") || scan.contains("offset=["), plan);
      agreesWithLocal(request, List.of("007", "003"));
    }
  }

  @Test
  void containsEscapesAndCaseAgreeWithStructuredFiltersAndLocalExecution() {
    for (String value : List.of("pass", "PASS", "", "%", "_", "*", "?", "\\")) {
      String literal = value.replace("\\", "\\\\");
      QueryExecutionRequest ppl =
          request(
              source() + " | where message contains '" + literal + "'" + ORDER + FIELDS,
              List.of(FILTER),
              0,
              100);
      QueryExecutionRequest structured =
          request(
              source() + ORDER + FIELDS,
              List.of(FILTER, new FieldFilter("message", "contains", value)),
              0,
              100);
      var expected = execute(localService, ppl);
      assertEquals(expected.getResults(), execute(nativeService, ppl).getResults(), value);
      assertEquals(expected.getResults(), execute(nativeService, structured).getResults(), value);
      assertEquals(expected.getResults(), execute(localService, structured).getResults(), value);
    }
  }

  @Test
  void mixedOrNotAndHeadBoundariesPreserveResults() {
    QueryExecutionRequest or =
        request(
            source() + " | where " + EQ + " or local_message contains 'PASS'" + ORDER + FIELDS,
            List.of(),
            0,
            100);
    assertFalse(plan(or).contains("term={extracted.process.name=sshd}"), plan(or));
    assertEquals(execute(localService, or).getResults(), execute(nativeService, or).getResults());
    QueryExecutionRequest not =
        request(
            source() + " | where " + EQ + " and not (message contains 'PASS')" + ORDER + FIELDS,
            List.of(),
            0,
            100);
    agreesWithLocal(not, List.of("011", "010", "004"));
    QueryExecutionRequest head =
        request(
            source() + ORDER + " | head 3 | where message contains 'PASS'" + FIELDS,
            List.of(),
            0,
            100);
    agreesWithLocal(head, List.of("009"));
    String scan =
        plan(head)
            .lines()
            .filter(line -> line.contains("ElasticsearchScan"))
            .findFirst()
            .orElseThrow();
    assertFalse(scan.contains("regexp="), scan);
    assertTrue(scan.contains("fetch=[3]"), scan);
  }

  @Test
  void missingWildcardInOneIndexKeepsTextPredicateLocal() throws Exception {
    String legacy = index + "-legacy";
    createIndex(legacy, false);
    Request put = new Request("PUT", "/" + legacy + "/_doc/one?refresh=true");
    put.setJsonEntity(
        json.writeValueAsString(
            Map.of(
                "id",
                "legacy",
                "timestamp",
                START + 11500,
                "message",
                "x".repeat(300) + "PASS",
                "extracted",
                Map.of("process", Map.of("name", "sshd")))));
    client.performRequest(put);
    QueryExecutionRequest request =
        request(
            "source=`"
                + index
                + "*` | where "
                + EQ
                + " and message contains 'PASS'"
                + ORDER
                + FIELDS,
            List.of(),
            0,
            100);
    String plan = plan(request);
    assertFalse(plan.contains("regexp={message.wc="), plan);
    assertFalse(plan.contains("regexp={message.keyword="), plan);
    agreesWithLocal(request, List.of("legacy", "008", "007", "003", "001"));
  }

  @AfterAll
  void cleanup() throws Exception {
    if (client == null) return;
    try {
      for (String name : created) client.performRequest(new Request("DELETE", "/" + name));
      created.clear();
    } finally {
      client.close();
    }
  }
}
