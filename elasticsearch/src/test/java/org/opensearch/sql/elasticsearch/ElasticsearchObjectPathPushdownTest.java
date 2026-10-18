/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.Objects;
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

class ElasticsearchObjectPathPushdownTest {
  private static final String INDEX = "logevent-*";
  private static final String LEAF = "extracted.process.name";
  private static final String AGENT = "nezha-agent";
  private static final String FIELDS = " | fields id,timestamp,hostIp,extracted";
  private static final String FILTER = " | where " + LEAF + "='" + AGENT + "'";
  private static final String TOP_FIVE = " | sort -timestamp | head 5";
  private static final List<String> TOP_IDS =
      List.of("event-12", "event-10", "event-08", "event-06", "event-04");
  private static final List<String> NULL_IDS =
      List.of("root-missing", "root-null", "parent-null", "leaf-missing", "leaf-null");

  @Test
  void fieldsBeforeWhereMatchesFieldsLastAndPushesTermSortAndHead() throws Exception {
    try (Fixture fixture = new Fixture(false)) {
      String fieldsFirst = FIELDS + FILTER + TOP_FIVE;
      String fieldsLast = FILTER + TOP_FIVE + FIELDS;
      assertNativePlan(fixture.explain(fieldsFirst));
      assertNativePlan(fixture.explain(fieldsLast));
      assertTrue(fixture.searches.isEmpty(), "Explain must not open a search");

      QueryResponse first = fixture.query(fieldsFirst);
      QueryResponse last = fixture.query(fieldsLast);
      assertIds(first, TOP_IDS);
      assertIds(last, TOP_IDS);
      assertEquals(first.getResults(), last.getResults());
      assertEquals(
          List.of("id", "timestamp", "hostIp", "extracted"),
          first.getSchema().getColumns().stream().map(column -> column.getName()).toList());
      for (ExprValue value : first.getResults()) {
        Map<String, ExprValue> row = value.tupleValue();
        assertEquals(AGENT, processName(row.get("extracted")));
        assertTrue(row.get("hostIp").stringValue().startsWith("192.0.2."));
      }
      fixture.assertNativeTopFive(2);
    }
  }

  @Test
  void originalQueryAgreesWithPushdownDisabledOnTheSameDocuments() throws Exception {
    try (Fixture nativeFixture = new Fixture(false, true);
        Fixture localFixture = new Fixture(false, false)) {
      String command = FIELDS + FILTER + TOP_FIVE;
      assertNativePlan(nativeFixture.explain(command));
      assertLocalFilter(localFixture.explain(command));

      QueryResponse nativeResponse = nativeFixture.query(command);
      QueryResponse localResponse = localFixture.query(command);
      assertIds(nativeResponse, TOP_IDS);
      assertIds(localResponse, TOP_IDS);
      assertEquals(nativeResponse.getResults(), localResponse.getResults());
      assertEquals(nativeResponse.getSchema().getColumns(), localResponse.getSchema().getColumns());
      for (ExprValue value : localResponse.getResults()) {
        assertEquals(AGENT, processName(value.tupleValue().get("extracted")));
      }
      nativeFixture.assertNativeTopFive(1);
      localFixture.assertLocalScans(1);
    }
  }

  @Test
  void disabledPushdownPreservesNestedExtractionCountsAndNullsWithSafeMappings() throws Exception {
    try (Fixture fixture = new Fixture(false, false)) {
      QueryResponse extracted =
          fixture.query(
              FIELDS
                  + " | eval processName=extracted.process.name"
                  + " | fields id,processName | sort id");
      assertExtractedValues(extracted);

      QueryResponse counts =
          fixture.query(
              " | fields extracted | stats count(extracted.process.name) as present, count() as"
                  + " total");
      assertSuccessful(counts);
      assertEquals(1, counts.getResults().size());
      assertEquals(14L, counts.getResults().getFirst().tupleValue().get("present").longValue());
      assertEquals(19L, counts.getResults().getFirst().tupleValue().get("total").longValue());

      QueryResponse nulls =
          fixture.query(FIELDS + " | where isnull(extracted.process.name) | fields id | sort id");
      assertIds(nulls, NULL_IDS.stream().sorted().toList());
      fixture.assertLocalScans(3);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unrelatedComputedColumnPreservesObjectPathsAcrossResidualProject(boolean pushdownEnabled)
      throws Exception {
    try (Fixture fixture = new Fixture(true, pushdownEnabled)) {
      String command = FIELDS + " | eval label=concat(id, '-label')" + FILTER + TOP_FIVE;
      assertLocalFilter(fixture.explain(command));
      QueryResponse response = fixture.query(command);
      assertIds(response, TOP_IDS);
      for (ExprValue value : response.getResults()) {
        Map<String, ExprValue> row = value.tupleValue();
        assertEquals(AGENT, processName(row.get("extracted")));
        assertEquals(row.get("id").stringValue() + "-label", row.get("label").stringValue());
      }
      fixture.assertMatchAllSearches();
      fixture.assertComplete(1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void spathPreservesLiteralDottedMapKeysWhenReplacingSourceObject(boolean pushdownEnabled)
      throws Exception {
    try (Fixture fixture = new Fixture(false, pushdownEnabled)) {
      String command =
          " | fields id,timestamp,extracted,body"
              + " | spath input=body output=extracted"
              + FILTER
              + TOP_FIVE
              + " | fields id,extracted";
      assertLocalFilter(fixture.explain(command));
      QueryResponse response = fixture.query(command);
      assertIds(response, List.of("event-13", "event-11", "event-09", "event-07", "event-05"));
      for (ExprValue value : response.getResults()) {
        Map<String, ExprValue> extracted = value.tupleValue().get("extracted").tupleValue();
        assertEquals(Set.of("process.name"), extracted.keySet());
        assertEquals(AGENT, extracted.get("process.name").stringValue());
      }
      fixture.assertMatchAllSearches();
      fixture.assertComplete(1);
    }
  }

  @Test
  void renamedParentRetainsItsOriginalElasticsearchPath() throws Exception {
    try (Fixture fixture = new Fixture(false)) {
      String command =
          FIELDS + " | rename extracted as e | where e.process.name='" + AGENT + "'" + TOP_FIVE;
      assertNativePlan(fixture.explain(command));
      QueryResponse response = fixture.query(command);
      assertIds(response, TOP_IDS);
      assertEquals(
          List.of("id", "timestamp", "hostIp", "e"),
          response.getSchema().getColumns().stream().map(column -> column.getName()).toList());
      for (ExprValue value : response.getResults()) {
        assertEquals(AGENT, processName(value.tupleValue().get("e")));
      }
      fixture.assertNativeTopFive(1);
      for (Map<String, Object> request : fixture.searches) {
        assertFalse(((List<?>) request.get("_source")).contains("e"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"extracted.process", LEAF})
  void explicitParentAndLeafProjectionsRemainNative(String projection) throws Exception {
    try (Fixture fixture = new Fixture(false)) {
      String command = " | fields id,timestamp," + projection + FILTER + TOP_FIVE;
      assertNativePlan(fixture.explain(command));
      QueryResponse response = fixture.query(command);
      assertIds(response, TOP_IDS);
      assertEquals(
          List.of("id", "timestamp", projection),
          response.getSchema().getColumns().stream().map(column -> column.getName()).toList());
      for (ExprValue value : response.getResults()) {
        ExprValue projected = value.tupleValue().get(projection);
        assertEquals(
            AGENT,
            projection.equals(LEAF)
                ? projected.stringValue()
                : projected.tupleValue().get("name").stringValue());
      }
      fixture.assertNativeTopFive(1);
    }
  }

  @Test
  void ignoreAboveKeepsFilterLocalAndStillFindsNestedSourceValues() throws Exception {
    try (Fixture fixture = new Fixture(true)) {
      String command = FIELDS + FILTER + TOP_FIVE;
      assertLocalFilter(fixture.explain(command));
      QueryResponse response = fixture.query(command);
      assertIds(response, TOP_IDS);
      for (ExprValue value : response.getResults()) {
        assertEquals(AGENT, processName(value.tupleValue().get("extracted")));
      }
      fixture.assertMatchAllSearches();
      fixture.assertComplete(1);
    }
  }

  @Test
  void evalExtractsNestedValuesAndPropagatesMissingAndNullParents() throws Exception {
    try (Fixture fixture = new Fixture(true)) {
      QueryResponse response =
          fixture.query(
              FIELDS
                  + " | eval processName=extracted.process.name"
                  + " | fields id,processName | sort id");
      assertExtractedValues(response);
      fixture.assertMatchAllSearches();
      fixture.assertComplete(1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void isNullDistinguishesMissingSourceValuesFromUnindexedKeywords(boolean ignoreAbove)
      throws Exception {
    try (Fixture fixture = new Fixture(ignoreAbove)) {
      String command = FIELDS + " | where isnull(extracted.process.name) | fields id | sort id";
      String plan = fixture.explain(command);
      if (ignoreAbove) assertLocalFilter(plan);
      QueryResponse response = fixture.query(command);
      assertIds(response, NULL_IDS.stream().sorted().toList());
      if (ignoreAbove) {
        fixture.assertMatchAllSearches();
      } else {
        for (Map<String, Object> request : fixture.searches) {
          assertEquals(
              Map.of("bool", Map.of("must_not", Map.of("exists", Map.of("field", LEAF)))),
              canonicalQuery(request.get("query")));
        }
      }
      fixture.assertComplete(1);
    }
  }

  @Test
  void countOfUnsafeLeafCountsNonNullSourceValuesAfterParentProjection() throws Exception {
    try (Fixture fixture = new Fixture(true)) {
      String command =
          " | fields extracted | stats count(extracted.process.name) as present, count() as total";
      String plan = fixture.explain(command);
      assertTrue(
          plan.contains("EnumerableAggregate") || plan.contains("EnumerableSortedAggregate"), plan);
      QueryResponse response = fixture.query(command);
      assertSuccessful(response);
      assertEquals(1, response.getResults().size());
      assertEquals(14L, response.getResults().getFirst().tupleValue().get("present").longValue());
      assertEquals(19L, response.getResults().getFirst().tupleValue().get("total").longValue());
      fixture.assertMatchAllSearches();
      fixture.assertComplete(1);
    }
  }

  @Test
  void computedObjectShadowingCannotUseTheOriginalSourceLeafPredicate() throws Exception {
    try (Fixture fixture = new Fixture(false)) {
      String command =
          " | fields id,timestamp,extracted,replacement"
              + " | eval extracted=if(id='event-13', replacement, extracted)"
              + " | fields id,timestamp,extracted"
              + FILTER
              + TOP_FIVE;
      assertLocalFilter(fixture.explain(command));
      QueryResponse response = fixture.query(command);
      assertIds(response, List.of("event-13", "event-12", "event-10", "event-08", "event-06"));
      for (ExprValue value : response.getResults()) {
        assertEquals(AGENT, processName(value.tupleValue().get("extracted")));
      }
      fixture.assertMatchAllSearches();
      fixture.assertComplete(1);
    }
  }

  private static void assertSuccessful(QueryResponse response) {
    assertNotNull(response);
    assertNull(response.getError());
  }

  private static void assertIds(QueryResponse response, List<String> expected) {
    assertSuccessful(response);
    assertEquals(
        expected,
        response.getResults().stream()
            .map(value -> value.tupleValue().get("id").stringValue())
            .toList());
  }

  private static void assertExtractedValues(QueryResponse response) {
    assertSuccessful(response);
    assertEquals(19, response.getResults().size());
    Map<String, ExprValue> values = new LinkedHashMap<>();
    for (ExprValue row : response.getResults()) {
      values.put(row.tupleValue().get("id").stringValue(), row.tupleValue().get("processName"));
    }
    for (int i = 0; i < 14; i++) {
      assertEquals(i % 2 == 0 ? AGENT : "cron", values.get(eventId(i)).stringValue());
    }
    for (String id : NULL_IDS) assertTrue(values.get(id).isNull(), id);
  }

  private static String processName(ExprValue root) {
    return root.tupleValue().get("process").tupleValue().get("name").stringValue();
  }

  /** Removes only neutral match_all conjuncts; every other predicate remains in the assertion. */
  private static Map<String, Object> canonicalQuery(Object value) {
    Map<String, Object> query = Fixture.object(value);
    if (!query.keySet().equals(Set.of("bool"))) return query;
    Map<String, Object> bool = Fixture.object(query.get("bool"));
    if (!bool.keySet().equals(Set.of("must"))) return query;
    List<Map<String, Object>> clauses =
        Fixture.clauses(bool.get("must")).stream()
            .map(ElasticsearchObjectPathPushdownTest::canonicalQuery)
            .filter(clause -> !clause.equals(Map.of("match_all", Map.of())))
            .toList();
    if (clauses.isEmpty()) return Map.of("match_all", Map.of());
    return clauses.size() == 1 ? clauses.getFirst() : Map.of("bool", Map.of("must", clauses));
  }

  private static void assertNativePlan(String plan) {
    assertTrue(plan.contains("ElasticsearchScan"), plan);
    assertFalse(plan.contains("EnumerableFilter"), plan);
    assertFalse(plan.contains("EnumerableSort"), plan);
    assertFalse(plan.contains("EnumerableLimit"), plan);
    assertFalse(plan.contains("condition=[") || plan.contains("$condition"), plan);
  }

  private static void assertLocalFilter(String plan) {
    assertTrue(
        plan.contains("EnumerableFilter")
            || plan.contains("condition=[")
            || plan.contains("$condition"),
        plan);
  }

  private static String eventId(int value) {
    return "event-" + (value < 10 ? "0" : "") + value;
  }

  /** Executes the supported DSL against source documents; unsupported transport fails the test. */
  private static final class Fixture implements AutoCloseable {
    private static final Set<String> SEARCH_KEYS =
        Set.of("query", "pit", "size", "sort", "_source", "search_after", "track_scores");
    private static final Set<String> SCALAR_FIELDS =
        Set.of(
            "id",
            "timestamp",
            "hostIp",
            "body",
            LEAF,
            "extracted.process.pid",
            "replacement.process.name",
            "replacement.process.pid");
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean ignoreAbove;
    private final Map<String, Object> mappings;
    private final List<Map<String, Object>> documents = new ArrayList<>();
    private final List<Map<String, Object>> searches = new CopyOnWriteArrayList<>();
    private final List<String> unexpected = new CopyOnWriteArrayList<>();
    private final AtomicInteger mappingRequests = new AtomicInteger();
    private final AtomicInteger openedPits = new AtomicInteger();
    private final AtomicInteger closedPits = new AtomicInteger();
    private final Set<String> activePits = ConcurrentHashMap.newKeySet();
    private final HttpServer server;
    private final RestClient client;
    private final UnifiedQueryService service;

    private Fixture(boolean ignoreAbove) throws IOException {
      this(ignoreAbove, true);
    }

    private Fixture(boolean ignoreAbove, boolean pushdownEnabled) throws IOException {
      this.ignoreAbove = ignoreAbove;
      Map<String, Object> nameMapping =
          ignoreAbove ? Map.of("type", "keyword", "ignore_above", 4) : Map.of("type", "keyword");
      Map<String, Object> objectMapping =
          Map.of(
              "properties",
              Map.of(
                  "process",
                  Map.of(
                      "properties",
                      Map.of("name", nameMapping, "pid", Map.of("type", "integer")))));
      Map<String, Object> properties =
          Map.of(
              "id", Map.of("type", "keyword"),
              "timestamp", Map.of("type", "date", "format", "epoch_millis"),
              "hostIp", Map.of("type", "keyword"),
              "body", Map.of("type", "keyword"),
              "extracted", objectMapping,
              "replacement", objectMapping);
      mappings = Map.of("logevent-2026", Map.of("mappings", Map.of("properties", properties)));
      // Both source order and timestamp order interleave matches and nonmatches. Seven matches
      // make filtering after a remote top-five limit observably incorrect.
      for (int i : List.of(5, 0, 12, 3, 8, 1, 10, 7, 4, 13, 2, 11, 6, 9)) {
        Map<String, Object> document = baseDocument(eventId(i), i);
        document.put(
            "extracted", Map.of("process", Map.of("name", i % 2 == 0 ? AGENT : "cron", "pid", i)));
        documents.add(document);
      }
      for (int i = 0; i < NULL_IDS.size(); i++) {
        Map<String, Object> document = baseDocument(NULL_IDS.get(i), 14 + i);
        switch (i) {
          case 0 -> {}
          case 1 -> document.put("extracted", null);
          case 2 -> document.put("extracted", Collections.singletonMap("process", null));
          case 3 -> document.put("extracted", Map.of("process", Map.of("pid", 17)));
          case 4 ->
              document.put("extracted", Map.of("process", Collections.singletonMap("name", null)));
          default -> throw new AssertionError(i);
        }
        documents.add(document);
      }
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
      service =
          new UnifiedQueryService(
              List.of(
                  new ElasticsearchQueryBackend("es", client, false, Map.of(), pushdownEnabled)),
              "es");
    }

    private static Map<String, Object> baseDocument(String id, int ordinal) {
      Map<String, Object> document = new LinkedHashMap<>();
      document.put("id", id);
      document.put("timestamp", 1700000000000L + ordinal * 1000L);
      document.put("hostIp", "192.0.2." + (ordinal + 1));
      // spath flattens this object to a literal "process.name" key. Its matches deliberately
      // differ from the stored extracted object, exposing accidental source-field pushdown.
      document.put(
          "body",
          ordinal < 14
              ? "{\"process\":{\"name\":\"" + (ordinal % 2 == 0 ? "cron" : AGENT) + "\"}}"
              : "{}");
      document.put("replacement", Map.of("process", Map.of("name", AGENT, "pid", 99)));
      return document;
    }

    private QueryResponse query(String command) {
      return service.execute(QueryType.PPL, "source=`" + INDEX + "`" + command);
    }

    private String explain(String command) {
      return service
          .explain(null, QueryType.PPL, "source=`" + INDEX + "`" + command)
          .getCalcite()
          .getPhysical();
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
            mappingRequests.incrementAndGet();
            response = mappings;
          } else if (method.equals("POST") && path.equals("/" + INDEX + "/_pit")) {
            require("keep_alive=1m".equals(exchange.getRequestURI().getQuery()), "PIT keep_alive");
            String pit = "object-path-pit-" + openedPits.incrementAndGet();
            activePits.add(pit);
            response = Map.of("id", pit);
          } else if (method.equals("POST") && path.equals("/_search")) {
            Map<String, Object> request = readBody(exchange);
            searches.add(request);
            response = search(request);
          } else if (method.equals("DELETE") && path.equals("/_pit")) {
            Map<String, Object> request = readBody(exchange);
            require(request.keySet().equals(Set.of("id")), "PIT close body: " + request);
            require(activePits.remove(request.get("id")), "Unknown PIT: " + request);
            closedPits.incrementAndGet();
            response = Map.of("succeeded", true, "num_freed", 1);
          } else {
            throw new IllegalArgumentException("Unexpected request: " + method + " " + path);
          }
        } catch (RuntimeException failure) {
          unexpected.add(failure.toString());
          status = 400;
          response = Map.of("error", failure.toString());
        }
        byte[] bytes = mapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    }

    private Map<String, Object> readBody(HttpExchange exchange) throws IOException {
      return mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
    }

    private Map<String, Object> search(Map<String, Object> request) {
      require(SEARCH_KEYS.containsAll(request.keySet()), "Search keys: " + request.keySet());
      if (request.containsKey("track_scores")) {
        require(request.get("track_scores") instanceof Boolean, "track_scores must be boolean");
      }
      Map<String, Object> pit = object(request.get("pit"));
      require(pit.keySet().equals(Set.of("id", "keep_alive")), "PIT body: " + pit);
      require(activePits.contains(pit.get("id")), "Inactive PIT: " + pit);
      require("1m".equals(pit.get("keep_alive")), "PIT keep_alive: " + pit);
      int size = ((Number) request.get("size")).intValue();
      require(size > 0 && size <= 1000, "Search size: " + size);
      List<SortKey> order = sortKeys(request.get("sort"));
      List<String> includes = strings(request.get("_source"));
      for (String include : includes) {
        require(
            SCALAR_FIELDS.contains(include)
                || Set.of(
                        "extracted",
                        "extracted.process",
                        "replacement",
                        "replacement.process",
                        "_id",
                        "_index",
                        "_score")
                    .contains(include),
            "Unknown source path: " + include);
      }
      Map<String, Object> query = object(request.get("query"));
      List<Integer> selected = new ArrayList<>();
      for (int i = 0; i < documents.size(); i++) {
        if (matches(query, documents.get(i))) selected.add(i);
      }
      selected.sort(
          (left, right) -> compare(sortValues(left, order), sortValues(right, order), order));
      int start = 0;
      if (request.containsKey("search_after")) {
        List<?> after = list(request.get("search_after"));
        require(after.size() == order.size(), "search_after arity: " + after);
        while (start < selected.size()
            && compare(sortValues(selected.get(start), order), after, order) <= 0) start++;
        require(
            start > 0 && compare(sortValues(selected.get(start - 1), order), after, order) == 0,
            "Unknown search_after: " + after);
      }
      List<Map<String, Object>> hits = new ArrayList<>();
      for (int i = start; i < selected.size() && hits.size() < size; i++) {
        int ordinal = selected.get(i);
        Map<String, Object> hit =
            new LinkedHashMap<>(
                Map.of(
                    "_index", "logevent-2026",
                    "_id", Integer.toString(ordinal),
                    "_source", project(documents.get(ordinal), "", includes),
                    "sort", sortValues(ordinal, order)));
        if (Boolean.TRUE.equals(request.get("track_scores"))) hit.put("_score", 1.0);
        hits.add(hit);
      }
      return Map.of(
          "hits",
          Map.of("total", Map.of("value", selected.size(), "relation", "eq"), "hits", hits));
    }

    private boolean matches(Map<String, Object> query, Map<String, Object> document) {
      require(query.size() == 1, "Query shape: " + query);
      if (query.containsKey("match_all") || query.containsKey("match_none")) {
        require(object(query.values().iterator().next()).isEmpty(), "Match query: " + query);
        return query.containsKey("match_all");
      }
      if (query.containsKey("term")) {
        Map<String, Object> term = object(query.get("term"));
        require(term.size() == 1, "Term shape: " + term);
        var entry = term.entrySet().iterator().next();
        Object expected = entry.getValue();
        if (expected instanceof Map<?, ?> options) {
          require(options.keySet().equals(Set.of("value")), "Term options: " + options);
          expected = options.get("value");
        }
        Object actual = indexedValue(document, entry.getKey());
        return actual != null && Objects.equals(actual, expected);
      }
      if (query.containsKey("exists")) {
        Map<String, Object> exists = object(query.get("exists"));
        require(exists.keySet().equals(Set.of("field")), "Exists shape: " + exists);
        return indexedValue(document, (String) exists.get("field")) != null;
      }
      if (query.containsKey("bool")) {
        Map<String, Object> bool = object(query.get("bool"));
        require(
            Set.of("must", "filter", "must_not", "should", "minimum_should_match")
                .containsAll(bool.keySet()),
            "Bool keys: " + bool);
        boolean result = true;
        for (String key : List.of("must", "filter", "must_not")) {
          for (Map<String, Object> child : clauses(bool.get(key))) {
            boolean matched = matches(child, document);
            result &= key.equals("must_not") ? !matched : matched;
          }
        }
        List<Map<String, Object>> should = clauses(bool.get("should"));
        int defaultMinimum =
            should.isEmpty() || bool.containsKey("must") || bool.containsKey("filter") ? 0 : 1;
        int minimum =
            ((Number) bool.getOrDefault("minimum_should_match", defaultMinimum)).intValue();
        long matched = should.stream().filter(child -> matches(child, document)).count();
        return result && matched >= minimum;
      }
      throw new IllegalArgumentException("Unsupported query: " + query);
    }

    private Object indexedValue(Map<String, Object> document, String field) {
      require(SCALAR_FIELDS.contains(field), "Unknown indexed field: " + field);
      Object value = sourceValue(document, field);
      if (ignoreAbove
          && (field.equals(LEAF) || field.equals("replacement.process.name"))
          && value instanceof String text
          && text.length() > 4) return null;
      return value;
    }

    private static List<Map<String, Object>> clauses(Object value) {
      if (value == null) return List.of();
      if (value instanceof List<?> values) return values.stream().map(Fixture::object).toList();
      return List.of(object(value));
    }

    private static List<SortKey> sortKeys(Object value) {
      List<SortKey> result = new ArrayList<>();
      for (Object item : list(value)) {
        Map<String, Object> sort = object(item);
        require(sort.size() == 1, "Sort shape: " + sort);
        var entry = sort.entrySet().iterator().next();
        require(
            SCALAR_FIELDS.contains(entry.getKey()) || entry.getKey().equals("_shard_doc"),
            "Unknown sort field: " + entry.getKey());
        String direction;
        String missing = "_last";
        if (entry.getValue() instanceof String text) {
          direction = text;
        } else {
          Map<String, Object> options = object(entry.getValue());
          require(
              Set.of("order", "missing", "unmapped_type").containsAll(options.keySet()),
              "Sort options: " + options);
          if (options.containsKey("unmapped_type")) {
            String mappingType =
                entry.getKey().equals("timestamp")
                    ? "date"
                    : entry.getKey().endsWith(".pid") ? "integer" : "keyword";
            require(
                mappingType.equals(options.get("unmapped_type")),
                "Wrong unmapped_type for " + entry.getKey() + ": " + options);
          }
          direction = (String) options.get("order");
          missing = (String) options.getOrDefault("missing", "_last");
        }
        require(Set.of("asc", "desc").contains(direction), "Sort direction: " + direction);
        require(Set.of("_first", "_last").contains(missing), "Sort missing: " + missing);
        result.add(new SortKey(entry.getKey(), direction.equals("desc"), missing.equals("_first")));
      }
      require(!result.isEmpty(), "Missing sort");
      require(result.getLast().field().equals("_shard_doc"), "Missing PIT tiebreaker");
      return result;
    }

    private List<Object> sortValues(int ordinal, List<SortKey> order) {
      List<Object> values = new ArrayList<>();
      for (SortKey key : order) {
        values.add(
            key.field().equals("_shard_doc")
                ? ordinal
                : indexedValue(documents.get(ordinal), key.field()));
      }
      return values;
    }

    private static int compare(List<?> left, List<?> right, List<SortKey> order) {
      for (int i = 0; i < order.size(); i++) {
        Object a = left.get(i);
        Object b = right.get(i);
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

    private static Object sourceValue(Map<String, Object> document, String field) {
      Object value = document;
      for (String segment : field.split("\\.")) {
        if (!(value instanceof Map<?, ?> map)) return null;
        value = map.get(segment);
      }
      return value;
    }

    private static Map<String, Object> project(
        Map<String, Object> document, String prefix, List<String> includes) {
      Map<String, Object> projected = new LinkedHashMap<>();
      document.forEach(
          (name, value) -> {
            String path = prefix.isEmpty() ? name : prefix + "." + name;
            if (includes.stream()
                .anyMatch(include -> path.equals(include) || path.startsWith(include + "."))) {
              projected.put(name, value);
            } else if (includes.stream().anyMatch(include -> include.startsWith(path + "."))) {
              if (value instanceof Map<?, ?>)
                projected.put(name, project(object(value), path, includes));
              else if (value == null) projected.put(name, null);
            }
          });
      return projected;
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

    private static List<String> strings(Object value) {
      return list(value).stream()
          .map(
              item -> {
                require(item instanceof String, "Expected string: " + item);
                return (String) item;
              })
          .toList();
    }

    private static void require(boolean condition, String message) {
      if (!condition) throw new IllegalArgumentException(message);
    }

    private void assertNativeTopFive(int executions) {
      assertComplete(executions);
      assertEquals(executions, searches.size(), "Native head should stop after one page per query");
      for (Map<String, Object> request : searches) {
        assertEquals(Map.of("term", Map.of(LEAF, AGENT)), canonicalQuery(request.get("query")));
        assertEquals(5, ((Number) request.get("size")).intValue());
        List<SortKey> order = sortKeys(request.get("sort"));
        assertEquals(2, order.size());
        assertEquals("timestamp", order.getFirst().field());
        assertTrue(order.getFirst().descending());
        assertFalse(request.containsKey("search_after"));
      }
    }

    private void assertLocalScans(int executions) {
      assertComplete(executions);
      assertMatchAllSearches();
      for (Map<String, Object> request : searches) {
        assertEquals(List.of(Map.of("_shard_doc", "asc")), request.get("sort"));
        assertEquals(1000, ((Number) request.get("size")).intValue());
      }
      assertTrue(searches.stream().anyMatch(request -> request.containsKey("search_after")));
    }

    private void assertMatchAllSearches() {
      assertFalse(searches.isEmpty());
      for (Map<String, Object> request : searches) {
        assertEquals(Map.of("match_all", Map.of()), canonicalQuery(request.get("query")));
      }
    }

    private void assertComplete(int executions) {
      assertEquals(List.of(), unexpected);
      assertTrue(mappingRequests.get() > 0);
      assertFalse(searches.isEmpty());
      assertEquals(executions, openedPits.get());
      assertEquals(executions, closedPits.get());
      assertTrue(activePits.isEmpty(), "Every opened PIT must be closed");
    }

    @Override
    public void close() throws IOException {
      try {
        client.close();
      } finally {
        server.stop(0);
      }
    }

    private record SortKey(String field, boolean descending, boolean missingFirst) {}
  }
}
