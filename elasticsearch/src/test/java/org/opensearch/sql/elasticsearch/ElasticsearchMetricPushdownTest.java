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
import static org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.object;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.QueryType;
import org.opensearch.sql.expression.function.PPLBuiltinOperators;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class ElasticsearchMetricPushdownTest {
  private final JavaTypeFactoryImpl types = new JavaTypeFactoryImpl();
  private final RexBuilder rex = new RexBuilder(types);
  private final List<Field> fields =
      List.of(
          new Field("amount", "amount", "double"),
          new Field("category", "category", "keyword"),
          new Field("status", "status", "integer"),
          new Field("large", "large", "long"),
          new Field("unsigned", "unsigned", "unsigned_long"));

  @Test
  void ordinaryMetricsKeepExistingNamesNullRulesAndProjectedArgumentMapping() {
    List<RexNode> inputs = List.of(ref(1), ref(0));
    ElasticsearchMetric count = metric(SqlStdOperatorTable.COUNT, List.of(0), inputs);
    assertEquals(
        Map.of("a2", Map.of("value_count", Map.of("field", "category"))), count.aggregations(2));
    assertEquals(3L, count.value(Map.of("a2", Map.of("value", 3L)), 2));

    ElasticsearchMetric sum = metric(SqlStdOperatorTable.SUM, List.of(1), inputs);
    assertEquals(
        Map.of(
            "a1",
            Map.of("sum", Map.of("field", "amount")),
            "n1",
            Map.of("value_count", Map.of("field", "amount"))),
        sum.aggregations(1));
    assertNull(sum.value(Map.of("a1", Map.of("value", 0.0), "n1", Map.of("value", 0)), 1));
    assertEquals(0.0, sum.value(Map.of("a1", Map.of("value", 0.0), "n1", Map.of("value", 2)), 1));
    ElasticsearchMetric sum0 = metric(SqlStdOperatorTable.SUM0, List.of(1), inputs);
    assertEquals(0.0, sum0.value(Map.of("a0", Map.of("value", 0.0)), 0));
    ElasticsearchMetric all = metric(SqlStdOperatorTable.COUNT, List.of(), inputs);
    assertEquals(Map.of(), all.aggregations(0));
    assertEquals(0L, all.value(Map.of("doc_count", 0L), 0));
  }

  @ParameterizedTest
  @MethodSource("statistics")
  void extendedStatisticsPreserveEmptyAndSingletonSemantics(
      SqlAggFunction function, String resultKey, boolean sample, double expected) {
    ElasticsearchMetric metric = metric(function, List.of(0), List.of(ref(0)));
    assertEquals(
        Map.of("a4", Map.of("extended_stats", Map.of("field", "amount"))), metric.aggregations(4));
    assertNull(metric.value(Map.of("a4", Map.of("count", 0)), 4));
    Object singleton = metric.value(Map.of("a4", Map.of("count", 1, resultKey, 0.0)), 4);
    if (sample) assertNull(singleton);
    else assertEquals(0.0, singleton);
    assertEquals(expected, metric.value(Map.of("a4", Map.of("count", 2, resultKey, expected)), 4));
  }

  private static Stream<Arguments> statistics() {
    return Stream.of(
        Arguments.of(PPLBuiltinOperators.VAR_SAMP_NULLABLE, "variance_sampling", true, 2.0),
        Arguments.of(PPLBuiltinOperators.VAR_POP_NULLABLE, "variance_population", false, 1.0),
        Arguments.of(
            PPLBuiltinOperators.STDDEV_SAMP_NULLABLE, "std_deviation_sampling", true, Math.sqrt(2)),
        Arguments.of(
            PPLBuiltinOperators.STDDEV_POP_NULLABLE, "std_deviation_population", false, 1.0));
  }

  @Test
  void olderExtendedStatisticsResponsesApplySampleCorrection() {
    ElasticsearchMetric variance =
        metric(PPLBuiltinOperators.VAR_SAMP_NULLABLE, List.of(0), List.of(ref(0)));
    ElasticsearchMetric deviation =
        metric(PPLBuiltinOperators.STDDEV_SAMP_NULLABLE, List.of(0), List.of(ref(0)));
    Map<String, Object> bucket =
        Map.of("a0", Map.of("count", 2, "variance", 1.0, "std_deviation", 1.0));
    assertEquals(2.0, variance.value(bucket, 0));
    assertEquals(Math.sqrt(2), deviation.value(bucket, 0));
  }

  @Test
  void percentileUsesProjectedLiteralsTypeFlagCompressionAndNullValues() {
    List<RexNode> inputs =
        List.of(ref(1), literal(95), rex.makeFlag(SqlTypeName.DOUBLE), ref(0), literal(250));
    ElasticsearchMetric metric =
        metric(PPLBuiltinOperators.PERCENTILE_APPROX, List.of(3, 1, 4, 2), inputs);
    assertEquals(
        Map.of(
            "a0",
            Map.of(
                "percentiles",
                Map.of(
                    "field",
                    "amount",
                    "percents",
                    List.of(95.0),
                    "tdigest",
                    Map.of("compression", 250.0)))),
        metric.aggregations(0));
    assertEquals(17.5, metric.value(Map.of("a0", Map.of("values", Map.of("95.0", 17.5))), 0));
    assertNull(metric.value(Map.of("a0", Map.of("values", Map.of("95.0", Double.NaN))), 0));
    assertNull(metric.value(Map.of("a0", Map.of("values", Map.of("95.0", "NaN"))), 0));
    ElasticsearchMetric median =
        metric(
            PPLBuiltinOperators.PERCENTILE_APPROX,
            List.of(0, 1, 2),
            List.of(ref(0), literal(50), rex.makeFlag(SqlTypeName.DOUBLE)));
    assertTrue(median.aggregations(0).toString().contains("compression=100.0"));
  }

  @Test
  void filteredCountAndSumUseFilterBucketsAndActualNonNullCount() {
    RexNode predicate =
        rex.makeCall(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, ref(2), literal(500));
    List<RexNode> inputs = List.of(predicate, ref(0));
    ElasticsearchMetric count = translate(call(SqlStdOperatorTable.COUNT, List.of(), 0), inputs);
    Map<String, Object> query =
        Map.of("range", Map.of("status", Map.of("gte", BigDecimal.valueOf(500.0))));
    assertEquals(Map.of("f2", Map.of("filter", query)), count.aggregations(2));
    assertEquals(7L, count.value(Map.of("f2", Map.of("doc_count", 7L)), 2));
    ElasticsearchMetric sum = translate(call(SqlStdOperatorTable.SUM, List.of(1), 0), inputs);
    Map<String, Object> filter = object(sum.aggregations(0).get("f0"));
    assertEquals(query, filter.get("filter"));
    assertEquals(
        Map.of(
            "a0",
            Map.of("sum", Map.of("field", "amount")),
            "n0",
            Map.of("value_count", Map.of("field", "amount"))),
        filter.get("aggs"));
    assertNull(
        sum.value(
            Map.of(
                "f0",
                Map.of("doc_count", 3L, "a0", Map.of("value", 0.0), "n0", Map.of("value", 0))),
            0));
    assertEquals(
        9.0,
        sum.value(
            Map.of(
                "f0",
                Map.of("doc_count", 3L, "a0", Map.of("value", 9.0), "n0", Map.of("value", 2))),
            0));
  }

  @Test
  void countCasePreservesOrderedBranchAndUnknownFallthroughSemantics() {
    RexNode condition =
        rex.makeCall(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, ref(2), literal(500));
    RexNode second = rex.makeCall(SqlStdOperatorTable.EQUALS, ref(1), rex.makeLiteral("keep"));
    RexNode expression =
        rex.makeCall(
            SqlStdOperatorTable.CASE,
            condition,
            rex.makeNullLiteral(types.createSqlType(SqlTypeName.DOUBLE)),
            second,
            ref(0),
            literal(1));
    ElasticsearchMetric metric = metric(SqlStdOperatorTable.COUNT, List.of(0), List.of(expression));
    Map<String, Object> filter = object(object(metric.aggregations(0).get("f0")).get("filter"));
    assertFalse(matches(filter, Map.of("status", 500, "category", "keep", "amount", 8.0)));
    assertFalse(matches(filter, Map.of("status", 200, "category", "keep")));
    assertTrue(matches(filter, Map.of("status", 200, "category", "keep", "amount", 8.0)));
    assertTrue(matches(filter, Map.of("category", "other")));
    assertTrue(matches(filter, Map.of()));
  }

  @Test
  void countEvalTreatsFalseAndUnknownAsNullAndCombinesFilterArg() {
    RexNode condition =
        rex.makeCall(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, ref(2), literal(500));
    RexNode expression =
        rex.makeCall(
            SqlStdOperatorTable.CASE,
            condition,
            literal(1),
            rex.makeNullLiteral(types.createSqlType(SqlTypeName.INTEGER)));
    RexNode extra = rex.makeCall(SqlStdOperatorTable.IS_NOT_NULL, ref(0));
    ElasticsearchMetric metric =
        translate(call(SqlStdOperatorTable.COUNT, List.of(0), 1), List.of(expression, extra));
    Map<String, Object> filter = object(object(metric.aggregations(0).get("f0")).get("filter"));
    assertTrue(matches(filter, Map.of("status", 500, "amount", 1.0)));
    assertFalse(matches(filter, Map.of("status", 200, "amount", 1.0)));
    assertFalse(matches(filter, Map.of("amount", 1.0)));
    assertFalse(matches(filter, Map.of("status", 500)));
  }

  @Test
  void percentileAlsoAcceptsLiteralPreArgumentsWithoutTreatingThemAsFieldOrdinals() {
    AggregateCall aggregate =
        AggregateCall.create(
            PPLBuiltinOperators.PERCENTILE_APPROX,
            false,
            false,
            false,
            List.of(literal(50), rex.makeFlag(SqlTypeName.DOUBLE)),
            List.of(0),
            -1,
            null,
            RelCollations.EMPTY,
            types.createSqlType(SqlTypeName.DOUBLE),
            "median");
    ElasticsearchMetric metric = translate(aggregate, List.of(ref(0)));
    assertEquals(List.of(50.0), findOption(metric.aggregations(0), "percents"));
    assertEquals("amount", findOption(metric.aggregations(0), "field"));
  }

  @Test
  void approximateDistinctKeepsNullExclusionAndRejectsUnsafeFieldMappings() {
    ElasticsearchMetric metric =
        translate(
            call(SqlStdOperatorTable.COUNT, List.of(0), -1)
                .withDistinct(true)
                .withApproximate(true),
            List.of(ref(1)));
    assertEquals(
        Map.of("a0", Map.of("cardinality", Map.of("field", "category"))), metric.aggregations(0));
    assertEquals(0L, metric.value(Map.of("a0", Map.of("value", 0L)), 0));
    assertEquals(2L, metric.value(Map.of("a0", Map.of("value", 2L)), 0));
    for (String mapping : List.of("text", "array<keyword>", "object", "any")) {
      assertNull(
          ElasticsearchMetric.translate(
              call(PPLBuiltinOperators.DISTINCT_COUNT_APPROX, List.of(0), -1),
              List.of(ref(0)),
              List.of(new Field("value", "value", mapping)),
              rex));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"float", "half_float", "scaled_float"})
  void quantizedIndexValuesCannotReplaceSourceValuesInMetrics(String mapping) {
    List<Field> quantized = List.of(new Field("amount", "amount", mapping));
    for (SqlAggFunction function :
        List.of(
            SqlStdOperatorTable.SUM,
            SqlStdOperatorTable.MIN,
            SqlStdOperatorTable.MAX,
            SqlStdOperatorTable.AVG,
            PPLBuiltinOperators.VAR_SAMP_NULLABLE,
            PPLBuiltinOperators.VAR_POP_NULLABLE,
            PPLBuiltinOperators.STDDEV_SAMP_NULLABLE,
            PPLBuiltinOperators.STDDEV_POP_NULLABLE,
            PPLBuiltinOperators.DISTINCT_COUNT_APPROX)) {
      assertNull(
          ElasticsearchMetric.translate(
              call(function, List.of(0), -1), List.of(ref(0)), quantized, rex));
    }
    assertNull(
        ElasticsearchMetric.translate(
            call(PPLBuiltinOperators.PERCENTILE_APPROX, List.of(0, 1), -1),
            List.of(ref(0), literal(50)),
            quantized,
            rex));
    ElasticsearchMetric count =
        ElasticsearchMetric.translate(
            call(SqlStdOperatorTable.COUNT, List.of(0), -1), List.of(ref(0)), quantized, rex);
    assertNotNull(count);
    assertEquals(
        Map.of("a0", Map.of("value_count", Map.of("field", "amount"))), count.aggregations(0));
    RexNode condition = rex.makeCall(SqlStdOperatorTable.GREATER_THAN, ref(0), literal(1.00000001));
    assertNull(
        ElasticsearchMetric.translate(
            call(SqlStdOperatorTable.COUNT, List.of(), 0), List.of(condition), quantized, rex));
    RexNode conditionalValue =
        rex.makeCall(
            SqlStdOperatorTable.CASE,
            condition,
            literal(1),
            rex.makeNullLiteral(types.createSqlType(SqlTypeName.INTEGER)));
    assertNull(
        ElasticsearchMetric.translate(
            call(SqlStdOperatorTable.COUNT, List.of(0), -1),
            List.of(conditionalValue),
            quantized,
            rex));
  }

  @Test
  void precisionSensitiveAndUnsupportedMetricsRemainLocal() {
    List<RexNode> inputs = List.of(ref(0), ref(1), ref(2), ref(3), ref(4));
    for (int index : List.of(3, 4)) {
      for (SqlAggFunction function :
          List.of(
              SqlStdOperatorTable.SUM,
              SqlStdOperatorTable.MIN,
              SqlStdOperatorTable.MAX,
              SqlStdOperatorTable.AVG,
              PPLBuiltinOperators.VAR_SAMP_NULLABLE)) {
        assertNull(
            ElasticsearchMetric.translate(call(function, List.of(index), -1), inputs, fields, rex));
      }
    }
    assertNull(
        ElasticsearchMetric.translate(
            call(PPLBuiltinOperators.CHECKED_LONG_SUM, List.of(2), -1), inputs, fields, rex));
    assertNull(
        ElasticsearchMetric.translate(
            call(SqlStdOperatorTable.SUM, List.of(2), -1), inputs, fields, rex));
    assertNull(
        ElasticsearchMetric.translate(
            call(SqlStdOperatorTable.MIN, List.of(1), -1), inputs, fields, rex));
    assertNull(
        ElasticsearchMetric.translate(
            call(SqlStdOperatorTable.MAX, List.of(1), -1), inputs, fields, rex));
    assertNull(
        ElasticsearchMetric.translate(
            call(SqlStdOperatorTable.COUNT, List.of(1), -1).withDistinct(true),
            inputs,
            fields,
            rex));
    RexNode calculated = rex.makeCall(SqlStdOperatorTable.PLUS, ref(0), literal(1));
    assertNull(
        ElasticsearchMetric.translate(
            call(SqlStdOperatorTable.SUM, List.of(0), -1), List.of(calculated), fields, rex));
    assertNull(
        ElasticsearchMetric.translate(
            call(PPLBuiltinOperators.PERCENTILE_APPROX, List.of(0, 1), -1),
            List.of(ref(0), literal(99.5)),
            fields,
            rex));
    assertNull(
        ElasticsearchMetric.translate(
            call(PPLBuiltinOperators.PERCENTILE_APPROX, List.of(0, 1, 2), -1),
            List.of(ref(0), literal(95), literal(0)),
            fields,
            rex));
    assertNull(
        ElasticsearchMetric.translate(
            call(SqlStdOperatorTable.COUNT, List.of(0), -1),
            List.of(ref(0)),
            List.of(new Field("amount", "amount", "double", false)),
            rex));
  }

  @ParameterizedTest
  @ValueSource(strings = {"var_samp", "var_pop", "stddev_samp", "stddev_pop"})
  void pplStatisticsExecuteWithoutLocalAggregation(String function) throws Exception {
    try (Fixture fixture = new Fixture()) {
      String command = " | stats " + function + "(amount) as result";
      String plan = fixture.explain(command);
      assertNative(plan);
      assertTrue(plan.contains("extended_stats"), plan);
      QueryResponse result = fixture.query(command);
      assertNull(result.getError());
      assertEquals(ExprCoreType.DOUBLE, result.getSchema().getColumns().getFirst().getExprType());
      double expected =
          function.contains("samp") ? (function.startsWith("var") ? 2.0 : Math.sqrt(2)) : 1.0;
      assertEquals(
          expected, result.getResults().getFirst().tupleValue().get("result").doubleValue());
      fixture.assertComplete();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"median(amount)", "percentile(amount, 95)", "percentile_approx(amount, 95, 250)"})
  void pplPercentileAliasesExecuteWithLiteralOptions(String expression) throws Exception {
    try (Fixture fixture = new Fixture()) {
      String command =
          " | fields category, amount | rename amount as amount_alias"
              + " | stats "
              + expression.replace("amount", "amount_alias")
              + " as quantile";
      String plan = fixture.explain(command);
      assertNative(plan);
      assertTrue(plan.contains("percentiles"), plan);
      QueryResponse result = fixture.query(command);
      assertNull(result.getError());
      assertEquals(ExprCoreType.DOUBLE, result.getSchema().getColumns().getFirst().getExprType());
      assertEquals(12.5, result.getResults().getFirst().tupleValue().get("quantile").doubleValue());
      assertEquals(
          expression.contains("250") ? 250.0 : 100.0,
          findOption(fixture.requests.getFirst().get("aggs"), "compression"));
      fixture.assertComplete();
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 1})
  void pplEmptyAndSingletonStatisticsReturnTypedNulls(long count) throws Exception {
    try (Fixture fixture = new Fixture(count)) {
      String command =
          " | stats var_samp(amount) as sample, var_pop(amount) as population,"
              + " stddev_samp(amount) as sample_std, stddev_pop(amount) as population_std";
      assertNative(fixture.explain(command));
      QueryResponse result = fixture.query(command);
      assertNull(result.getError());
      assertEquals(1, result.getResults().size());
      var row = result.getResults().getFirst().tupleValue();
      assertTrue(row.get("sample").isNull());
      assertTrue(row.get("sample_std").isNull());
      if (count == 0) {
        assertTrue(row.get("population").isNull());
        assertTrue(row.get("population_std").isNull());
      } else {
        assertEquals(0.0, row.get("population").doubleValue());
        assertEquals(0.0, row.get("population_std").doubleValue());
      }
      assertTrue(
          result.getSchema().getColumns().stream()
              .allMatch(column -> column.getExprType() == ExprCoreType.DOUBLE));
      fixture.assertComplete();
    }
  }

  @Test
  void pplConditionalCountRemapsArgumentsAndPreservesAliasesAndTypes() throws Exception {
    try (Fixture fixture = new Fixture()) {
      String command =
          " | fields status, category, amount | rename status as code"
              + " | stats count(eval(code>=500)) as failures, count() as total";
      String plan = fixture.explain(command);
      assertNative(plan);
      QueryResponse result = fixture.query(command);
      assertNull(result.getError());
      assertEquals(
          List.of("failures", "total"),
          result.getSchema().getColumns().stream().map(column -> column.getName()).toList());
      assertEquals(
          List.of(ExprCoreType.LONG, ExprCoreType.LONG),
          result.getSchema().getColumns().stream().map(column -> column.getExprType()).toList());
      assertEquals(1L, result.getResults().getFirst().tupleValue().get("failures").longValue());
      assertEquals(3L, result.getResults().getFirst().tupleValue().get("total").longValue());
      assertEquals("status", findRangeField(fixture.requests.getFirst().get("aggs")));
      fixture.assertComplete();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sum(status)",
        "sum(large)",
        "min(large)",
        "min(category)",
        "max(category)",
        "percentile(amount, 99.5)",
        "percentile(large, 50)"
      })
  void unsupportedPplMetricsRetainLocalAggregate(String expression) throws Exception {
    try (Fixture fixture = new Fixture()) {
      String plan = fixture.explain(" | stats " + expression + " as result");
      assertTrue(
          plan.contains("EnumerableAggregate") || plan.contains("EnumerableSortedAggregate"), plan);
      assertTrue(fixture.requests.isEmpty());
    }
  }

  private RexInputRef ref(int index) {
    return rex.makeInputRef(
        types.createTypeWithNullability(fields.get(index).type(types), true), index);
  }

  private RexNode literal(double number) {
    return rex.makeExactLiteral(BigDecimal.valueOf(number));
  }

  private AggregateCall call(SqlAggFunction function, List<Integer> arguments, int filter) {
    return AggregateCall.create(
        function,
        false,
        false,
        false,
        List.of(),
        arguments,
        filter,
        null,
        RelCollations.EMPTY,
        types.createSqlType(
            function == SqlStdOperatorTable.COUNT ? SqlTypeName.BIGINT : SqlTypeName.DOUBLE),
        "alias");
  }

  private ElasticsearchMetric metric(
      SqlAggFunction function, List<Integer> arguments, List<RexNode> inputs) {
    return translate(call(function, arguments, -1), inputs);
  }

  private ElasticsearchMetric translate(AggregateCall aggregate, List<RexNode> inputs) {
    ElasticsearchMetric result = ElasticsearchMetric.translate(aggregate, inputs, fields, rex);
    assertNotNull(result, aggregate.toString());
    return result;
  }

  private static void assertNative(String plan) {
    assertTrue(plan.contains("ElasticsearchScan"), plan);
    assertFalse(
        plan.contains("EnumerableAggregate") || plan.contains("EnumerableSortedAggregate"), plan);
  }

  private static Object findOption(Object value, String key) {
    if (value instanceof Map<?, ?> map) {
      if (map.containsKey(key)) return map.get(key);
      for (Object item : map.values()) {
        Object result = findOption(item, key);
        if (result != null) return result;
      }
    }
    return null;
  }

  private static String findRangeField(Object value) {
    return object(findOption(value, "range")).keySet().iterator().next();
  }

  /** Evaluates the small query subset used by CASE tests against concrete missing/null rows. */
  @SuppressWarnings("unchecked")
  private static boolean matches(Map<String, Object> query, Map<String, Object> row) {
    if (query.containsKey("match_all")) return true;
    if (query.containsKey("match_none")) return false;
    if (query.containsKey("exists"))
      return row.get(object(query.get("exists")).get("field")) != null;
    if (query.containsKey("term")) {
      var term = object(query.get("term")).entrySet().iterator().next();
      return term.getValue().equals(row.get(term.getKey()));
    }
    if (query.containsKey("range")) {
      var range = object(query.get("range")).entrySet().iterator().next();
      Object value = row.get(range.getKey());
      if (value == null) return false;
      Number lower = (Number) object(range.getValue()).get("gte");
      return ((Number) value).doubleValue() >= lower.doubleValue();
    }
    Map<String, Object> bool = object(query.get("bool"));
    if (bool.containsKey("must")) {
      return ((List<Map<String, Object>>) bool.get("must"))
          .stream().allMatch(child -> matches(child, row));
    }
    if (bool.containsKey("must_not")) return !matches(object(bool.get("must_not")), row);
    return ((List<Map<String, Object>>) bool.get("should"))
        .stream().anyMatch(child -> matches(child, row));
  }

  /** Local HTTP fixture: no live Elasticsearch instance or mutable remote data is used. */
  private static final class Fixture implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final RestClient client;
    private final UnifiedQueryService service;
    private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private final List<String> unexpected = new CopyOnWriteArrayList<>();
    private int opened;
    private int closed;
    private final long statisticCount;

    Fixture() throws IOException {
      this(2);
    }

    Fixture(long statisticCount) throws IOException {
      this.statisticCount = statisticCount;
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

    String explain(String command) {
      return service
          .explain(null, QueryType.PPL, "source=metrics" + command)
          .getCalcite()
          .getPhysical();
    }

    QueryResponse query(String command) {
      return service.execute(QueryType.PPL, "source=metrics" + command);
    }

    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        Map<String, Object> response;
        if (method.equals("GET") && path.endsWith("/_mapping")) {
          response =
              Map.of(
                  "metrics",
                  Map.of(
                      "mappings",
                      Map.of(
                          "properties",
                          Map.of(
                              "amount",
                              Map.of("type", "double"),
                              "category",
                              Map.of("type", "keyword"),
                              "status",
                              Map.of("type", "integer"),
                              "large",
                              Map.of("type", "long")))));
        } else if (method.equals("POST") && path.equals("/metrics/_pit")) {
          opened++;
          response = Map.of("id", "metric-pit");
        } else if (method.equals("POST") && path.equals("/_search")) {
          Map<String, Object> request =
              mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
          requests.add(request);
          if (((Number) request.get("size")).intValue() != 0) unexpected.add("Local document scan");
          response =
              Map.of(
                  "hits",
                  Map.of("total", Map.of("value", 3), "hits", List.of()),
                  "aggregations",
                  aggregateValues(object(request.get("aggs"))));
        } else if (method.equals("DELETE") && path.equals("/_pit")) {
          closed++;
          response = Map.of("succeeded", true);
        } else {
          unexpected.add(method + " " + path);
          response = Map.of("error", "Unexpected request");
        }
        byte[] bytes = mapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    }

    private Map<String, Object> aggregateValues(Map<String, Object> definitions) {
      Map<String, Object> result = new LinkedHashMap<>();
      definitions.forEach(
          (name, definition) -> {
            Map<String, Object> metric = object(definition);
            if (metric.containsKey("filter")) {
              Map<String, Object> filtered =
                  new LinkedHashMap<>(aggregateValues(object(metric.get("aggs"))));
              filtered.put("doc_count", 1);
              result.put(name, filtered);
            } else if (metric.containsKey("extended_stats")) {
              result.put(
                  name,
                  Map.of(
                      "count",
                      statisticCount,
                      "variance_population",
                      statisticCount < 2 ? 0.0 : 1.0,
                      "variance_sampling",
                      statisticCount < 2 ? 0.0 : 2.0,
                      "std_deviation_population",
                      statisticCount < 2 ? 0.0 : 1.0,
                      "std_deviation_sampling",
                      statisticCount < 2 ? 0.0 : Math.sqrt(2)));
            } else if (metric.containsKey("percentiles")) {
              Number percent =
                  (Number) ((List<?>) object(metric.get("percentiles")).get("percents")).getFirst();
              result.put(
                  name, Map.of("values", Map.of(Double.toString(percent.doubleValue()), 12.5)));
            } else {
              result.put(name, Map.of("value", 2));
            }
          });
      return result;
    }

    void assertComplete() {
      assertEquals(List.of(), unexpected);
      assertEquals(1, opened);
      assertEquals(1, closed);
      assertEquals(1, requests.size());
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
