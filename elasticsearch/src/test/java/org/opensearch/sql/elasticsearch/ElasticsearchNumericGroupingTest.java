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
import static org.opensearch.sql.calcite.utils.OpenSearchTypeFactory.TYPE_FACTORY;
import static org.opensearch.sql.expression.function.udf.binning.SpanBucketFunction.SpanBucketImplementor.calculateSpanBucket;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.QueryType;
import org.opensearch.sql.expression.function.PPLBuiltinOperators;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class ElasticsearchNumericGroupingTest {
  private static final RexBuilder REX = new RexBuilder(TYPE_FACTORY);
  private static final long DAY = Instant.parse("2024-02-29T00:00:00Z").toEpochMilli();

  @ParameterizedTest
  @ValueSource(strings = {"byte", "short", "integer", "long"})
  void integralSpanKeepsTruncationForNegativeValues(String mapping) throws Exception {
    List<Map<String, Object>> documents = documents(-21, -20, -19, -1, 0, 1, 19, 20, 21, null);
    List<Map<String, Object>> buckets =
        List.of(
            bucket(null, 2),
            bucket(-20L, 2),
            bucket(-10L, 1),
            bucket(0L, 3),
            bucket(10L, 1),
            bucket(20L, 2));
    String commands = " | stats count() as total by span(value, 10) as bucket | sort bucket";
    try (Fixture pushed = new Fixture(mapping, false, documents, pages(buckets));
        Fixture local = new Fixture(mapping, true, documents, List.of())) {
      assertSameQuery(commands, pushed, local);
      Map<String, Object> terms = object(source(pushed.searches.getFirst(), "g0").get("terms"));
      assertEquals("long", terms.get("value_type"));
      assertEquals(true, terms.get("missing_bucket"));
      assertEquals("asc", terms.get("order"));
      assertEquals("first", terms.get("missing_order"));
      Map<String, Object> script = object(terms.get("script"));
      assertEquals("painless", script.get("lang"));
      assertEquals(Map.of("field", "value", "width", 10), script.get("params"));
      assertTrue(script.get("source").toString().contains("(value / width) * width"));
      assertFalse(script.get("source").toString().contains("Math.floor"));
      assertEquals(6, pushed.result.getResults().size());
      assertTrue(pushed.result.getResults().getFirst().tupleValue().get("bucket").isNull());
      assertEquals(0L, pushed.result.getResults().get(3).tupleValue().get("bucket").longValue());
    }
  }

  @Test
  void integralSpanRetainsAdjacentLongsAboveDoublePrecisionAndSignedExtremes() throws Exception {
    List<Long> values =
        List.of(
            Long.MIN_VALUE,
            -9007199254740993L,
            -9007199254740992L,
            0L,
            9007199254740992L,
            9007199254740993L,
            Long.MAX_VALUE);
    List<Map<String, Object>> buckets = values.stream().map(value -> bucket(value, 1)).toList();
    String commands = " | stats count() as total by span(value, 1) as bucket | sort bucket";
    try (Fixture pushed = new Fixture("long", false, documents(values.toArray()), pages(buckets));
        Fixture local = new Fixture("long", true, documents(values.toArray()), List.of())) {
      assertSameQuery(commands, pushed, local);
      assertEquals(
          values,
          pushed.result.getResults().stream()
              .map(row -> row.tupleValue().get("bucket").longValue())
              .toList());
    }
  }

  @Test
  void floatingSpanUsesHistogramFlooringAcrossNegativeAndPositiveBoundaries() throws Exception {
    List<Map<String, Object>> documents =
        documents(-20.1, -20.0, -19.9, -0.1, 0.0, 0.1, 9.9, 10.0, 19.9, 20.0, null);
    List<Map<String, Object>> buckets =
        List.of(
            bucket(null, 2),
            bucket(-30.0, 1),
            bucket(-20.0, 2),
            bucket(-10.0, 1),
            bucket(0.0, 3),
            bucket(10.0, 2),
            bucket(20.0, 1));
    try (Fixture pushed = new Fixture("double", false, documents, pages(buckets));
        Fixture local = new Fixture("double", true, documents, List.of())) {
      assertSameQuery(
          " | stats count() as total by span(value, 10) as bucket | sort bucket", pushed, local);
      assertEquals(orderedHistogram(10.0), source(pushed.searches.getFirst(), "g0"));
    }
  }

  @Test
  void floatingSpanRetainsSignedZeroGroups() throws Exception {
    List<Map<String, Object>> documents = documents(-0.0, 0.0);
    try (Fixture pushed =
            new Fixture(
                "double", false, documents, pages(List.of(bucket(-0.0, 1), bucket(0.0, 1))));
        Fixture local = new Fixture("double", true, documents, List.of())) {
      assertSameQuery(
          " | stats count() as total by span(value, 1) as bucket | sort bucket", pushed, local);
    }
  }

  @Test
  void fractionalSpanPromotesIntegerInputsAndKeepsFlooring() throws Exception {
    List<Map<String, Object>> documents = documents(-2, -1, 0, 1, 2, 3, null);
    List<Map<String, Object>> buckets =
        List.of(
            bucket(null, 2),
            bucket(-3.0, 1),
            bucket(-1.5, 1),
            bucket(0.0, 2),
            bucket(1.5, 1),
            bucket(3.0, 1));
    try (Fixture pushed = new Fixture("integer", false, documents, pages(buckets));
        Fixture local = new Fixture("integer", true, documents, List.of())) {
      assertSameQuery(
          " | stats count() as total by span(value, 1.5) as bucket | sort bucket", pushed, local);
      assertEquals(orderedHistogram(1.5), source(pushed.searches.getFirst(), "g0"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"10", "1.5", "0.3", "0.125", "0.0001", "2147483647"})
  void binUsesInjectiveRangeLabelsIncludingFractionalWidthsAndIntegerBoundaries(String width)
      throws Exception {
    double interval = Double.parseDouble(width);
    List<Map<String, Object>> documents =
        documents(Integer.MIN_VALUE, -76, -21, -1, 0, 1, 19, 21, Integer.MAX_VALUE, null);
    // Model the native histogram's numeric keys, then compare the complete decoded query with
    // actual local execution of SPAN_BUCKET over the same raw documents.
    Map<Double, Long> counts = new TreeMap<>();
    for (Map<String, Object> document : documents) {
      if (document.get("value") instanceof Number number) {
        double key = Math.floor(number.doubleValue() / interval) * interval;
        counts.merge(key, 1L, Long::sum);
      }
    }
    List<Map<String, Object>> buckets = new ArrayList<>();
    counts.forEach((key, count) -> buckets.add(bucket(key, count)));
    // The explicit null and missing source value are one local bin group.
    buckets.add(bucket(null, 2));
    String commands =
        " | bin value span="
            + width
            + " as bucket | stats count() as total by bucket"
            + " | sort bucket";
    try (Fixture pushed = new Fixture("integer", false, documents, pages(buckets));
        Fixture local = new Fixture("integer", true, documents, List.of())) {
      assertSameQuery(commands, pushed, local);
      assertEquals(histogram(interval), source(pushed.searches.getFirst(), "g0"));
      assertTrue(
          pushed.result.getResults().stream()
              .anyMatch(row -> row.tupleValue().get("bucket").isNull()));
      assertEquals(
          pushed.result.getResults().size(),
          pushed.result.getResults().stream()
              .map(row -> row.tupleValue().get("bucket"))
              .distinct()
              .count());
    }
  }

  @Test
  void binDecoderDoesNotRoundAnAlreadyRoundedBoundaryAgain() {
    ElasticsearchGrouping grouping = grouping("integer", true, "0.3", true);
    assertNotNull(grouping);
    assertEquals(calculateSpanBucket(-76, 0.3), grouping.value(-76.2));
    assertEquals("-76.20--75.90", grouping.value(-76.2));
    assertNull(grouping.value(null));
    assertFalse(calculateSpanBucket(-76.2, 0.3).equals(grouping.value(-76.2)));
  }

  @Test
  void mixedKeywordTimeAndNumericSourcesKeepOrderingAliasesAndRawAfterKeys() throws Exception {
    List<Map<String, Object>> documents =
        List.of(
            Map.of("category", "a", "event_time", DAY, "value", 1),
            Map.of("category", "a", "event_time", DAY + 1, "value", 9),
            Map.of("category", "b", "event_time", DAY, "value", 21));
    List<Map<String, Object>> buckets =
        List.of(
            Map.of("key", Map.of("g0", "a", "g1", 0.0, "g2", DAY), "doc_count", 2),
            Map.of("key", Map.of("g0", "b", "g1", 20.0, "g2", DAY), "doc_count", 1));
    Map<String, Object> after = Map.of("g0", "a", "g1", 0.125, "g2", DAY + 99);
    List<Map<String, Object>> pages =
        List.of(
            Map.of("buckets", buckets.subList(0, 1), "after_key", after),
            Map.of("buckets", buckets.subList(1, 2)));
    String commands =
        " | rename value as amount | bin amount span=10 as bucket"
            + " | stats count() as total by category, bucket, span(event_time, 1d) as day"
            + " | fields bucket, total, day, category | sort category";
    try (Fixture pushed = new Fixture("integer", false, documents, pages);
        Fixture local = new Fixture("integer", true, documents, List.of())) {
      assertSameQuery(commands, pushed, local);
      assertEquals(
          Map.of("terms", Map.of("field", "category", "missing_bucket", true)),
          source(pushed.searches.getFirst(), "g0"));
      assertEquals(
          Map.of(
              "date_histogram",
              Map.of("field", "event_time", "fixed_interval", "1d", "missing_bucket", true)),
          source(pushed.searches.getFirst(), "g2"));
      assertEquals(histogram(10.0), source(pushed.searches.getFirst(), "g1"));
      assertEquals(after, composite(pushed.searches.get(1)).get("after"));
    }
  }

  @Test
  void binPaginationUsesNumericAfterKeyRatherThanTheDecodedRangeLabel() throws Exception {
    List<Map<String, Object>> documents = documents(0, 1, 2);
    Map<String, Object> after = Map.of("g0", 0.125);
    List<Map<String, Object>> pages =
        List.of(
            Map.of("buckets", List.of(bucket(0.0, 1)), "after_key", after),
            Map.of("buckets", List.of(bucket(1.0, 1), bucket(2.0, 1))));
    try (Fixture pushed = new Fixture("integer", false, documents, pages);
        Fixture local = new Fixture("integer", true, documents, List.of())) {
      assertSameQuery(
          " | bin value span=0.125 as bucket | stats count() as total by bucket | sort bucket",
          pushed,
          local);
      assertEquals(after, composite(pushed.searches.get(1)).get("after"));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "long,false,0.5,true",
    "unsigned_long,false,10,false",
    "float,false,10,false",
    "half_float,false,10,false",
    "scaled_float,false,10,false",
    "double,false,0.5,true",
    "double,false,3,false",
    "double,true,10,false",
    "long,true,10,false",
    "integer,true,0.00001,true",
    "integer,true,2147483648,false",
    "integer,false,0,false",
    "integer,false,-1,false",
    "long,false,9223372036854775808,false",
    "integer,true,1E400,false",
    "integer,false,1E-400,true"
  })
  void unsafeTypesAndWidthsRemainLocal(
      String mapping, boolean bin, String width, boolean floating) {
    assertNull(grouping(mapping, bin, width, floating));
  }

  @Test
  void tinyFloatingBinLabelsCanCollideAndMustNotBeDecodedIntoDuplicateGroups() {
    assertEquals(calculateSpanBucket(0.000001, 0.000001), calculateSpanBucket(0.000002, 0.000001));
    assertNull(grouping("double", true, "0.000001", true));
  }

  @Test
  void scriptParametersPreserveLongWidthAndNeverInterpolateTheFieldName() {
    String name = "value']; return 0; //";
    Field field = new Field(name, name, "long");
    RexNode call =
        REX.makeCall(
            field.type(TYPE_FACTORY),
            PPLBuiltinOperators.SPAN,
            List.of(
                new RexInputRef(0, field.type(TYPE_FACTORY)),
                REX.makeBigintLiteral(BigDecimal.valueOf(Long.MAX_VALUE)),
                REX.constantNull()));
    ElasticsearchGrouping grouping = ElasticsearchGrouping.translate(call, List.of(field));
    assertNotNull(grouping);
    Map<String, Object> script = object(object(grouping.source().get("terms")).get("script"));
    assertEquals(Map.of("field", name, "width", Long.MAX_VALUE), script.get("params"));
    assertFalse(script.get("source").toString().contains(name));
    assertFalse(script.get("source").toString().contains(Long.toString(Long.MAX_VALUE)));
    assertEquals(9007199254740993L, grouping.value(9007199254740993L));
    assertNull(grouping.value(null));
  }

  @Test
  void computedInputsNullWidthsAndInexactMappingsRetainTheirLocalEvaluation() {
    Field field = new Field("value", "value", "integer", false);
    RexNode ref = new RexInputRef(0, field.type(TYPE_FACTORY));
    assertNull(ElasticsearchGrouping.translate(ref, List.of(field)));
    RexNode call =
        REX.makeCall(
            field.type(TYPE_FACTORY),
            PPLBuiltinOperators.SPAN,
            List.of(ref, REX.makeExactLiteral(BigDecimal.TEN), REX.constantNull()));
    assertNull(ElasticsearchGrouping.translate(call, List.of(field)));
    Field exact = new Field("value", "value", "integer");
    RexNode nullWidth =
        REX.makeCall(
            field.type(TYPE_FACTORY),
            PPLBuiltinOperators.SPAN,
            List.of(ref, REX.makeNullLiteral(field.type(TYPE_FACTORY)), REX.constantNull()));
    assertNull(ElasticsearchGrouping.translate(nullWidth, List.of(exact)));
    RexNode computed =
        REX.makeCall(
            field.type(TYPE_FACTORY),
            PPLBuiltinOperators.SPAN,
            List.of(call, REX.makeExactLiteral(BigDecimal.TEN), REX.constantNull()));
    assertNull(ElasticsearchGrouping.translate(computed, List.of(exact)));
  }

  private static ElasticsearchGrouping grouping(
      String mapping, boolean bin, String width, boolean floating) {
    Field field = new Field("value", "value", mapping);
    BigDecimal number = new BigDecimal(width);
    RexLiteral literal = floating ? REX.makeApproxLiteral(number) : REX.makeBigintLiteral(number);
    List<RexNode> operands =
        new ArrayList<>(List.of(new RexInputRef(0, field.type(TYPE_FACTORY)), literal));
    if (!bin) operands.add(REX.constantNull());
    SqlTypeName result =
        bin ? SqlTypeName.VARCHAR : floating ? SqlTypeName.DOUBLE : field.sqlType();
    RexNode call =
        REX.makeCall(
            TYPE_FACTORY.createSqlType(result),
            bin ? PPLBuiltinOperators.SPAN_BUCKET : PPLBuiltinOperators.SPAN,
            operands);
    return ElasticsearchGrouping.translate(call, List.of(field));
  }

  private static void assertSameQuery(String commands, Fixture pushed, Fixture local) {
    String physical = pushed.explain(commands);
    assertFalse(physical.contains("EnumerableAggregate"), physical);
    assertFalse(physical.contains("EnumerableSortedAggregate"), physical);
    assertTrue(physical.contains("grouping="), physical);
    String fallback = local.explain(commands);
    assertTrue(
        fallback.contains("EnumerableAggregate") || fallback.contains("EnumerableSortedAggregate"),
        fallback);
    assertTrue(pushed.searches.isEmpty());
    pushed.result = pushed.query(commands);
    QueryResponse expected = local.query(commands);
    assertNull(pushed.result.getError());
    assertNull(expected.getError());
    assertEquals(expected.getSchema(), pushed.result.getSchema());
    assertEquals(expected.getResults(), pushed.result.getResults());
    assertFalse(pushed.searches.isEmpty());
    for (Map<String, Object> request : pushed.searches) assertEquals(0, request.get("size"));
    assertEquals(List.of(), pushed.unexpected);
    assertEquals(List.of(), local.unexpected);
  }

  private static Map<String, Object> histogram(double interval) {
    return Map.of(
        "histogram", Map.of("field", "value", "interval", interval, "missing_bucket", true));
  }

  private static Map<String, Object> orderedHistogram(double interval) {
    return Map.of(
        "histogram",
        Map.of(
            "field",
            "value",
            "interval",
            interval,
            "missing_bucket",
            true,
            "order",
            "asc",
            "missing_order",
            "first"));
  }

  private static Map<String, Object> bucket(Object value, long count) {
    Map<String, Object> key = new LinkedHashMap<>();
    key.put("g0", value);
    return Map.of("key", key, "doc_count", count);
  }

  private static List<Map<String, Object>> pages(List<Map<String, Object>> buckets) {
    return List.of(Map.of("buckets", buckets));
  }

  private static List<Map<String, Object>> documents(Object... values) {
    List<Map<String, Object>> documents = new ArrayList<>();
    for (Object value : values) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("value", value);
      documents.add(row);
    }
    if (java.util.Arrays.asList(values).contains(null)) documents.add(Map.of());
    return documents;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  private static Map<String, Object> composite(Map<String, Object> request) {
    return object(object(object(request.get("aggs")).get("groups")).get("composite"));
  }

  private static Map<String, Object> source(Map<String, Object> request, String name) {
    List<?> sources = (List<?>) composite(request).get("sources");
    return sources.stream()
        .map(ElasticsearchNumericGroupingTest::object)
        .filter(source -> source.containsKey(name))
        .map(source -> object(source.get(name)))
        .findFirst()
        .orElseThrow();
  }

  /** Captures native DSL and supplies raw documents for the independently executed local plan. */
  private static final class Fixture implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final RestClient client;
    private final UnifiedQueryService service;
    private final Map<String, Object> mappings;
    private final List<Map<String, Object>> documents;
    private final List<Map<String, Object>> pages;
    private final AtomicInteger page = new AtomicInteger();
    private final List<Map<String, Object>> searches = new CopyOnWriteArrayList<>();
    private final List<String> unexpected = new CopyOnWriteArrayList<>();
    private QueryResponse result;

    private Fixture(
        String mapping,
        boolean local,
        List<Map<String, Object>> documents,
        List<Map<String, Object>> pages)
        throws IOException {
      this.documents = documents;
      this.pages = pages;
      Map<String, Object> properties =
          Map.of(
              "value",
                  local
                      ? Map.of("type", mapping, "ignore_malformed", true)
                      : Map.of("type", mapping),
              "category", Map.of("type", "keyword"),
              "event_time",
                  local
                      ? Map.of("type", "date", "ignore_malformed", true)
                      : Map.of("type", "date"));
      mappings = Map.of("numbers", Map.of("mappings", Map.of("properties", properties)));
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
      service = new UnifiedQueryService(List.of(new ElasticsearchQueryBackend("es", client)), "es");
    }

    private QueryResponse query(String commands) {
      return service.execute(QueryType.PPL, "source=numbers" + commands);
    }

    private String explain(String commands) {
      return service
          .explain(null, QueryType.PPL, "source=numbers" + commands)
          .getCalcite()
          .getPhysical();
    }

    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        Map<String, Object> response;
        if (method.equals("GET")
            && (path.equals("/numbers/_mapping") || path.equals("/_mapping"))) {
          response = mappings;
        } else if (method.equals("POST") && path.equals("/numbers/_pit")) {
          response = Map.of("id", "numeric-pit");
        } else if (method.equals("POST") && path.equals("/_search")) {
          Map<String, Object> request =
              mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
          searches.add(request);
          int size = ((Number) request.get("size")).intValue();
          if (size == 0) {
            int position = page.getAndIncrement();
            Map<String, Object> aggregate;
            if (position < pages.size()) aggregate = pages.get(position);
            else {
              unexpected.add("Unscripted aggregation page " + position);
              aggregate = Map.of("buckets", List.of());
            }
            response =
                Map.of(
                    "hits", Map.of("hits", List.of()), "aggregations", Map.of("groups", aggregate));
          } else {
            int start =
                request.get("search_after") instanceof List<?> after
                    ? ((Number) after.getFirst()).intValue() + 1
                    : 0;
            List<Map<String, Object>> hits = new ArrayList<>();
            for (int i = start; i < documents.size() && hits.size() < size; i++) {
              hits.add(
                  Map.of(
                      "_index",
                      "numbers",
                      "_id",
                      Integer.toString(i),
                      "_source",
                      documents.get(i),
                      "sort",
                      List.of(i)));
            }
            response = Map.of("hits", Map.of("hits", hits));
          }
        } else if (method.equals("DELETE") && path.equals("/_pit")) {
          response = Map.of("succeeded", true, "num_freed", 1);
        } else {
          unexpected.add(method + " " + path);
          response = Map.of("error", "Unexpected request");
        }
        byte[] json = mapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, json.length);
        exchange.getResponseBody().write(json);
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
