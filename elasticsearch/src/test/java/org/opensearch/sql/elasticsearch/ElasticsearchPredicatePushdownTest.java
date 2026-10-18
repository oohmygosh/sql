/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.metadata.NullSentinel;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexInterpreter;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.runtime.SqlFunctions;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlCollation;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlLibraryOperators;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.NlsString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.sql.api.UnifiedQueryContext;
import org.opensearch.sql.api.UnifiedQueryPlanner;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;
import org.opensearch.sql.executor.QueryType;

class ElasticsearchPredicatePushdownTest {
  private static final List<Field> FIELDS =
      List.of(
          new Field("active", "active", "boolean"),
          new Field("verified", "verified", "boolean"),
          new Field("amount", "amount", "integer"),
          new Field("label", "label", "keyword"),
          new Field("message", "message", "text"));
  private final JavaTypeFactoryImpl types = new JavaTypeFactoryImpl();
  private final RexBuilder rex = new RexBuilder(types);
  private final RexInputRef active = ref(0);
  private final RexInputRef verified = ref(1);
  private final RexInputRef amount = ref(2);
  private final RexInputRef label = ref(3);

  @Test
  void patternsUseSafeWildcardMultifieldsWithoutEnablingOrderingOrRangeComparisons() {
    Field wildcard = new Field("message.wc", "message", "wildcard");
    assertTrue(wildcard.supportsPattern());
    assertFalse(wildcard.supportsComparison());
    assertFalse(wildcard.supportsOrdering());
    assertFalse(wildcard.supportsGrouping());
    RexNode pattern = call(SqlLibraryOperators.ILIKE, ref(4), rex.makeLiteral("%pass%"));
    Map<String, Object> safe = patternMapping(Map.of("type", "wildcard"));
    assertEquals(
        Map.of(
            "regexp",
            Map.of("message.wc", Map.of("value", ".*[pP][aA][sS][sS].*", "flags", "NONE"))),
        mappedPattern(pattern, Map.of("a", safe, "b", safe)));

    for (Map<String, Object> options :
        List.of(
            Map.<String, Object>of("type", "wildcard", "ignore_above", 256),
            Map.<String, Object>of("type", "wildcard", "null_value", "pass"),
            Map.<String, Object>of("type", "keyword", "normalizer", "lowercase"),
            Map.<String, Object>of("type", "keyword", "index", false),
            Map.<String, Object>of("type", "keyword", "ignore_above", 256))) {
      assertNull(mappedPattern(pattern, Map.of("a", patternMapping(options))), options.toString());
      assertNull(
          mappedPattern(pattern, Map.of("a", safe, "b", patternMapping(options))),
          options.toString());
    }
    Map<String, Object> missing =
        Map.of("mappings", Map.of("properties", Map.of("message", Map.of("type", "text"))));
    assertNull(mappedPattern(pattern, Map.of("a", safe, "b", missing)));
    assertNull(ElasticsearchPredicate.translate(pattern, FIELDS));
  }

  private static Map<String, Object> patternMapping(Map<String, Object> options) {
    return Map.of(
        "mappings",
        Map.of(
            "properties",
            Map.of("message", Map.of("type", "text", "fields", Map.of("wc", options)))));
  }

  private static Map<String, Object> mappedPattern(RexNode pattern, Map<String, Object> mappings) {
    Map<String, Field> discovered = ElasticsearchQueryBackend.mappingFields(mappings);
    List<Field> inputs = new ArrayList<>(FIELDS);
    inputs.set(4, discovered.get("message"));
    return ElasticsearchPredicate.translate(pattern, inputs, List.copyOf(discovered.values()));
  }

  @Test
  void booleanTruthTablesAgreeWithCalciteIncludingMissingAndExplicitNull() {
    RexNode comparison =
        call(SqlStdOperatorTable.GREATER_THAN, amount, rex.makeExactLiteral(BigDecimal.ONE));
    List<RexNode> seeds =
        new ArrayList<>(
            List.of(
                active,
                verified,
                rex.makeLiteral(true),
                rex.makeLiteral(false),
                rex.makeNullLiteral(active.getType())));
    for (SqlOperator operator :
        List.of(
            SqlStdOperatorTable.EQUALS,
            SqlStdOperatorTable.NOT_EQUALS,
            SqlStdOperatorTable.LESS_THAN,
            SqlStdOperatorTable.LESS_THAN_OR_EQUAL,
            SqlStdOperatorTable.GREATER_THAN,
            SqlStdOperatorTable.GREATER_THAN_OR_EQUAL)) {
      seeds.add(call(operator, amount, rex.makeExactLiteral(BigDecimal.ONE)));
    }
    for (RexNode left : List.copyOf(seeds)) {
      seeds.add(call(SqlStdOperatorTable.AND, left, verified));
      seeds.add(call(SqlStdOperatorTable.OR, left, verified));
      seeds.add(
          call(
              SqlStdOperatorTable.AND,
              left,
              call(SqlStdOperatorTable.OR, active, call(SqlStdOperatorTable.NOT, comparison))));
      seeds.add(
          call(SqlStdOperatorTable.OR, left, call(SqlStdOperatorTable.AND, verified, comparison)));
    }
    List<RexNode> expressions = new ArrayList<>();
    for (RexNode seed : seeds) {
      expressions.add(seed);
      for (SqlOperator operator :
          List.of(
              SqlStdOperatorTable.NOT,
              SqlStdOperatorTable.IS_TRUE,
              SqlStdOperatorTable.IS_FALSE,
              SqlStdOperatorTable.IS_NOT_TRUE,
              SqlStdOperatorTable.IS_NOT_FALSE,
              SqlStdOperatorTable.IS_UNKNOWN,
              SqlStdOperatorTable.IS_NOT_NULL)) {
        RexNode wrapped = call(operator, seed);
        expressions.add(wrapped);
        expressions.add(call(SqlStdOperatorTable.NOT, wrapped));
      }
    }
    for (Object a : new Object[] {true, false, null}) {
      for (Object b : new Object[] {true, false, null}) {
        for (Object n : new Object[] {0, 1, 2, null}) {
          Map<String, Object> document = new LinkedHashMap<>();
          document.put("active", a);
          document.put("verified", b);
          document.put("amount", n);
          Map<RexNode, Comparable> values =
              Map.of(active, comparable(a), verified, comparable(b), amount, comparable(n));
          for (RexNode expression : expressions) {
            Map<String, Object> query = translate(expression);
            assertNotNull(query, expression.toString());
            boolean expected = Boolean.TRUE.equals(RexInterpreter.evaluate(expression, values));
            assertEquals(expected, matches(query, document), expression + " on " + document);
            Map<String, Object> missing = new LinkedHashMap<>(document);
            missing.values().removeIf(value -> value == null);
            assertEquals(expected, matches(query, missing), expression + " on " + missing);
          }
        }
      }
    }
  }

  @Test
  void comparisonsAndDirectNullChecksKeepTheirExistingDsl() {
    RexNode value = rex.makeExactLiteral(BigDecimal.TEN);
    assertEquals(
        Map.of("range", Map.of("amount", Map.of("gte", BigDecimal.TEN))),
        translate(call(SqlStdOperatorTable.LESS_THAN_OR_EQUAL, value, amount)));
    assertEquals(
        Map.of("range", Map.of("amount", Map.of("lte", BigDecimal.TEN))),
        translate(
            call(SqlStdOperatorTable.NOT, call(SqlStdOperatorTable.GREATER_THAN, amount, value))));
    assertEquals(
        Map.of("exists", Map.of("field", "label")),
        translate(call(SqlStdOperatorTable.IS_NOT_NULL, label)));
    assertEquals(
        not(Map.of("exists", Map.of("field", "label"))),
        translate(call(SqlStdOperatorTable.IS_NULL, label)));
    for (SqlOperator op :
        List.of(
            SqlStdOperatorTable.EQUALS,
            SqlStdOperatorTable.NOT_EQUALS,
            SqlStdOperatorTable.GREATER_THAN)) {
      RexNode nullable = call(op, amount, rex.makeNullLiteral(amount.getType()));
      assertEquals(Map.of("match_none", Map.of()), translate(nullable));
      assertEquals(
          Map.of("match_none", Map.of()), translate(call(SqlStdOperatorTable.NOT, nullable)));
    }
  }

  @Test
  void floatQuantizationCanReverseComparisonAndNotResults() {
    Field field = new Field("amount", "amount", "float");
    List<Field> fields = new ArrayList<>(FIELDS);
    fields.set(2, field);
    RexInputRef input =
        rex.makeInputRef(types.createTypeWithNullability(field.type(types), true), 2);
    assertEquals(SqlTypeName.DOUBLE, input.getType().getSqlTypeName());
    assertTrue(field.exact());

    // 2^24 + 1 is exactly representable in _source's DOUBLE but rounds down in a float index.
    double sourceValue = 16777217.0;
    double indexedValue = (double) (float) sourceValue;
    assertEquals(16777216.0, indexedValue);
    RexNode threshold = rex.makeApproxLiteral(BigDecimal.valueOf(indexedValue));
    RexNode greater = call(SqlStdOperatorTable.GREATER_THAN, input, threshold);
    RexNode equal = call(SqlStdOperatorTable.EQUALS, input, threshold);
    for (RexNode expression :
        List.of(
            greater,
            equal,
            call(SqlStdOperatorTable.NOT, greater),
            call(SqlStdOperatorTable.NOT, equal))) {
      boolean local =
          Boolean.TRUE.equals(
              RexInterpreter.evaluate(expression, Map.of(input, comparable(sourceValue))));
      boolean indexed =
          Boolean.TRUE.equals(
              RexInterpreter.evaluate(expression, Map.of(input, comparable(indexedValue))));
      assertEquals(!local, indexed, expression.toString());
      assertNull(ElasticsearchPredicate.translate(expression, fields), expression.toString());
    }
    assertEquals(true, RexInterpreter.evaluate(greater, Map.of(input, comparable(sourceValue))));
    assertEquals(false, RexInterpreter.evaluate(greater, Map.of(input, comparable(indexedValue))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"float", "half_float", "scaled_float"})
  void quantizedMappingsKeepValuePredicatesLocalButPreservePresence(String mapping) {
    Field field = new Field("amount", "amount", mapping);
    List<Field> fields = new ArrayList<>(FIELDS);
    fields.set(2, field);
    RexInputRef input =
        rex.makeInputRef(types.createTypeWithNullability(field.type(types), true), 2);
    RexNode value = rex.makeApproxLiteral(BigDecimal.ONE);
    for (SqlOperator operator :
        List.of(
            SqlStdOperatorTable.EQUALS,
            SqlStdOperatorTable.NOT_EQUALS,
            SqlStdOperatorTable.LESS_THAN,
            SqlStdOperatorTable.LESS_THAN_OR_EQUAL,
            SqlStdOperatorTable.GREATER_THAN,
            SqlStdOperatorTable.GREATER_THAN_OR_EQUAL)) {
      for (RexNode comparison :
          List.of(call(operator, input, value), call(operator, value, input))) {
        assertNull(ElasticsearchPredicate.translate(comparison, fields), comparison.toString());
        for (SqlOperator wrapper :
            List.of(
                SqlStdOperatorTable.NOT,
                SqlStdOperatorTable.IS_TRUE,
                SqlStdOperatorTable.IS_FALSE,
                SqlStdOperatorTable.IS_NOT_TRUE,
                SqlStdOperatorTable.IS_NOT_FALSE)) {
          RexNode expression = call(wrapper, comparison);
          assertNull(ElasticsearchPredicate.translate(expression, fields), expression.toString());
        }
        for (SqlOperator connective : List.of(SqlStdOperatorTable.AND, SqlStdOperatorTable.OR)) {
          RexNode expression = call(connective, active, comparison);
          assertNull(ElasticsearchPredicate.translate(expression, fields), expression.toString());
          assertNull(
              ElasticsearchPredicate.translate(call(SqlStdOperatorTable.NOT, expression), fields));
        }
      }
    }
    Map<String, Object> exists = Map.of("exists", Map.of("field", "amount"));
    RexNode isNull = call(SqlStdOperatorTable.IS_NULL, input);
    RexNode isNotNull = call(SqlStdOperatorTable.IS_NOT_NULL, input);
    assertEquals(not(exists), ElasticsearchPredicate.translate(isNull, fields));
    assertEquals(exists, ElasticsearchPredicate.translate(isNotNull, fields));
    assertEquals(
        exists, ElasticsearchPredicate.translate(call(SqlStdOperatorTable.NOT, isNull), fields));
    assertEquals(
        not(exists),
        ElasticsearchPredicate.translate(call(SqlStdOperatorTable.NOT, isNotNull), fields));
    RexNode unknown = call(SqlStdOperatorTable.EQUALS, input, rex.makeNullLiteral(input.getType()));
    assertEquals(Map.of("match_none", Map.of()), ElasticsearchPredicate.translate(unknown, fields));
  }

  @ParameterizedTest
  @ValueSource(strings = {"double", "integer"})
  void unquantizedNumericComparisonsRemainSupported(String mapping) {
    Field field = new Field("amount", "amount", mapping);
    List<Field> fields = new ArrayList<>(FIELDS);
    fields.set(2, field);
    RexInputRef input =
        rex.makeInputRef(types.createTypeWithNullability(field.type(types), true), 2);
    RexNode threshold =
        mapping.equals("double")
            ? rex.makeApproxLiteral(BigDecimal.TEN)
            : rex.makeExactLiteral(BigDecimal.TEN);
    for (SqlOperator operator :
        List.of(
            SqlStdOperatorTable.EQUALS,
            SqlStdOperatorTable.NOT_EQUALS,
            SqlStdOperatorTable.LESS_THAN,
            SqlStdOperatorTable.LESS_THAN_OR_EQUAL,
            SqlStdOperatorTable.GREATER_THAN,
            SqlStdOperatorTable.GREATER_THAN_OR_EQUAL)) {
      RexNode comparison = call(operator, input, threshold);
      for (RexNode expression : List.of(comparison, call(SqlStdOperatorTable.NOT, comparison))) {
        Map<String, Object> query = ElasticsearchPredicate.translate(expression, fields);
        assertNotNull(query, expression.toString());
        for (int value : List.of(9, 10, 11)) {
          boolean expected =
              Boolean.TRUE.equals(
                  RexInterpreter.evaluate(expression, Map.of(input, comparable(value))));
          assertEquals(expected, matches(query, Map.of("amount", value)), expression.toString());
        }
        assertFalse(matches(query, Map.of()));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "active",
        "not active",
        "not (active or amount > 1)",
        "not (active and (verified or amount <= 1))",
        "active = false",
        "isnull(label)",
        "like(label, '/api/%', true)",
        "like(label, '/api/%', false)",
        "like(label, '/api/%')",
        "ilike(label, '%error%')",
        "like(label, 'a_b', true)",
        "not like(label, 'a%', true)",
        "regexp_match(label, '^/api/')",
        "not regexp_match(label, '^/api/')"
      })
  void realPplPlannerPredicatesTranslate(String predicate) throws Exception {
    RexNode condition = planned("where " + predicate);
    assertNotNull(translate(condition), condition.toString());
  }

  @Test
  void plannerLikeDefaultsAndRegexCommandHaveExpectedShapesAndDsl() throws Exception {
    RexCall defaultLike = (RexCall) planned("where like(label, '/api/%')");
    // The unified PPL context currently prefers legacy semantics: LIKE defaults to ILIKE.
    assertEquals(SqlLibraryOperators.ILIKE, defaultLike.getOperator());
    assertEquals(3, defaultLike.getOperands().size());
    assertEquals(regexp("/[aA][pP][iI]/.*"), translate(defaultLike));
    assertEquals(
        Map.of("wildcard", Map.of("label", "/api/*")), translate(planned("regex label='^/api/'")));
    RexCall insensitive = (RexCall) planned("where like(label, '/API/%', false)");
    assertEquals(SqlLibraryOperators.ILIKE, insensitive.getOperator());
    assertEquals(regexp("/[aA][pP][iI]/.*"), translate(insensitive));
    assertEquals(
        regexp(".*[eE][rR][rR][oO][rR].*"), translate(planned("where ilike(label, '%error%')")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "match(message, 'error')",
        "match_phrase(message, 'disk error')",
        "match_bool_prefix(message, 'err')",
        "match_phrase_prefix(message, 'disk err')",
        "multi_match(['message', 'label'], 'error')",
        "query_string(['message', 'label'], 'error')",
        "simple_query_string(['message', 'label'], 'error')"
      })
  void allSevenRelevanceFunctionsRetainPositiveAndSafeNegativeQueries(String predicate)
      throws Exception {
    RexNode positive = planned("where " + predicate);
    Map<String, Object> query = translate(positive);
    assertNotNull(query, positive.toString());
    assertFalse(positive.getType().isNullable());
    assertEquals(not(query), translate(call(SqlStdOperatorTable.NOT, positive)));
    assertEquals(not(query), translate(planned("where not " + predicate)));
    RexCall nullable =
        ((RexCall) positive)
            .clone(
                types.createTypeWithNullability(positive.getType(), true),
                ((RexCall) positive).operands);
    assertNull(translate(call(SqlStdOperatorTable.NOT, nullable)));
  }

  @Test
  void unsupportedBranchesAndIncompatibleMappingsRemainLocal() throws Exception {
    for (String predicate :
        List.of(
            "active or length(label) > 2",
            "not (active and length(label) > 2)",
            "match(message, 'error') or length(label) > 2",
            "like(message, 'a%')",
            "like(lower(label), 'a%')",
            "like(label, message)",
            "regexp_match(label, '[A-Z]+')",
            "regexp_match(label, 'error$')",
            "regexp_match(label, 'a.b')",
            "regexp_match(label, '(?i)error')")) {
      RexNode condition = planned("where " + predicate);
      assertNull(translate(condition), condition.toString());
    }
    RexNode like = like("a%", true, "\\");
    for (Field field :
        List.of(
            new Field("label", "label", "text"),
            new Field("label", "label", "keyword", false),
            new Field("label", "label", "array<keyword>"),
            new Field("_id", "_id", "keyword"))) {
      List<Field> fields = new ArrayList<>(FIELDS);
      fields.set(3, field);
      assertNull(ElasticsearchPredicate.translate(like, fields));
      assertNull(ElasticsearchPredicate.translate(call(SqlStdOperatorTable.NOT, like), fields));
    }
    List<Field> fields = new ArrayList<>(FIELDS);
    fields.set(0, new Field("active", "active", "boolean", false));
    assertNull(ElasticsearchPredicate.translate(active, fields));
    assertNull(ElasticsearchPredicate.translate(call(SqlStdOperatorTable.IS_NULL, active), fields));
    fields.set(2, new Field("_score", "_score", "double"));
    assertNull(
        ElasticsearchPredicate.translate(call(SqlStdOperatorTable.IS_NOT_NULL, amount), fields));
    assertNull(translate(call(SqlStdOperatorTable.SIMILAR_TO, label, rex.makeLiteral("a%"))));
  }

  @Test
  void likeEscapesLiteralGlobAndRegexCharactersAndHonorsCalciteNewlinesAndAsciiCase()
      throws Exception {
    assertEquals(
        Map.of("wildcard", Map.of("label", "a\\*\\?\\\\%_*")),
        translate(like("a*?\\\\\\%\\_%", true, "\\")));
    assertEquals(Map.of("wildcard", Map.of("label", "a%_*")), translate(like("a!%!_%", true, "!")));
    assertEquals(regexp("a[^\n\r\u0085\u2028\u2029]b"), translate(like("a_b", true, "\\")));
    assertEquals(regexp("[aA]é.*"), translate(like("Aé%", false, "\\")));
    assertEquals(
        regexp("[aA]\\*\\?\\.\\[\\]\\(\\)\\{\\}\\|\\@\\#\\&\\~\\<\\>\\\"\\\\.*"),
        translate(like("A*?.[](){}|@#&~<>\"\\\\%", false, "\\")));
    SqlFunctions.LikeFunction calcite = new SqlFunctions.LikeFunction();
    assertTrue(calcite.like("a\nb", "a%b", "\\"));
    for (String newline : List.of("\n", "\r", "\u0085", "\u2028", "\u2029")) {
      assertFalse(calcite.like("a" + newline + "b", "a_b", "\\"));
    }
    assertTrue(calcite.like("a😀b", "a_b", "\\"));
    assertTrue(calcite.ilike("aé!", "Aé%", "\\"));
    assertFalse(calcite.ilike("aÉ!", "Aé%", "\\"));
    assertFalse(calcite.ilike("K", "K", "\\"));
    assertTrue(calcite.like("a*?\\%_tail", "a*?\\\\\\%\\_%", "\\"));
    assertEquals(
        Map.of("wildcard", Map.of("label", "a\\*\\?*")),
        translate(planned("where like(label, 'a*?%', true)")));
  }

  @Test
  void likeAndItsNegationAgreeWithCalciteOnUnicodeNewlinesEscapesAndNulls() {
    SqlFunctions.LikeFunction calcite = new SqlFunctions.LikeFunction();
    List<String> values =
        List.of(
            "",
            "a",
            "A",
            "a_b",
            "axb",
            "a😀b",
            "a\nb",
            "a\rb",
            "a\u0085b",
            "a\u2028b",
            "a\u2029b",
            "aé",
            "aÉ",
            "K",
            "K",
            "a*?\\%_tail",
            "😀");
    for (boolean sensitive : List.of(true, false)) {
      for (String pattern :
          List.of(
              "",
              "%",
              "_",
              "a_b",
              "a%b",
              "a%",
              "Aé%",
              "K",
              "😀",
              "a*?\\\\\\%\\_%",
              "\\_",
              "_\\_%")) {
        RexNode expression = like(pattern, sensitive, "\\");
        Map<String, Object> positive = translate(expression);
        Map<String, Object> negative = translate(call(SqlStdOperatorTable.NOT, expression));
        assertNotNull(positive, pattern);
        assertNotNull(negative, pattern);
        for (String value : values) {
          boolean expected =
              sensitive ? calcite.like(value, pattern, "\\") : calcite.ilike(value, pattern, "\\");
          assertEquals(
              expected, matches(positive, Map.of("label", value)), pattern + " on " + value);
          assertEquals(
              !expected, matches(negative, Map.of("label", value)), pattern + " on " + value);
        }
        assertFalse(matches(positive, Map.of()));
        assertFalse(matches(negative, Map.of()));
        assertTrue(matches(translate(call(SqlStdOperatorTable.IS_NOT_TRUE, expression)), Map.of()));
        assertTrue(
            matches(translate(call(SqlStdOperatorTable.IS_NOT_FALSE, expression)), Map.of()));
      }
    }
    for (RexNode expression :
        List.of(
            call(SqlStdOperatorTable.LIKE, label, rex.makeNullLiteral(label.getType())),
            call(
                SqlStdOperatorTable.LIKE, label, string("%"), rex.makeNullLiteral(label.getType())),
            call(
                SqlLibraryOperators.REGEXP_CONTAINS,
                label,
                rex.makeNullLiteral(label.getType())))) {
      assertEquals(Map.of("match_none", Map.of()), translate(expression));
      assertEquals(
          Map.of("match_none", Map.of()), translate(call(SqlStdOperatorTable.NOT, expression)));
    }
    RexNode noEscape = call(SqlStdOperatorTable.LIKE, label, string("a\\%"));
    assertEquals(
        calcite.like("a\\tail", "a\\%"), matches(translate(noEscape), Map.of("label", "a\\tail")));
  }

  @Test
  void invalidOrDynamicLikeEscapesAndUnknownRegexDialectsRemainLocal() {
    for (String pattern : List.of("a\\q", "a\\", "\u0000")) {
      assertNull(translate(like(pattern, true, "\\")), pattern);
    }
    for (String escape : List.of("", "!!")) {
      assertNull(translate(like("a%", true, escape)), escape);
    }
    assertNull(translate(call(SqlStdOperatorTable.LIKE, label, rex.makeLiteral("a%"), label)));
    assertThrows(
        RuntimeException.class, () -> new SqlFunctions.LikeFunction().like("aq", "a\\q", "\\"));
    for (String pattern :
        List.of(
            "x$",
            "^x$",
            "a.b",
            "a.*b",
            "[a-z]",
            "a+",
            "a?",
            "a{2}",
            "a|b",
            "(?i)a",
            "(?s).*",
            "\\d",
            "\\Qabc\\E",
            "(a)\\1",
            "a.*\\z")) {
      assertNull(translate(regex(pattern)), pattern);
    }
  }

  @Test
  void supportedNegatedPatternOperatorsUseTheSameNullSafeQueries() {
    for (SqlOperator positive :
        List.of(
            SqlStdOperatorTable.LIKE,
            SqlLibraryOperators.ILIKE,
            SqlLibraryOperators.REGEXP,
            SqlLibraryOperators.RLIKE,
            SqlLibraryOperators.REGEXP_CONTAINS)) {
      RexNode expression = call(positive, label, string("literal"));
      Map<String, Object> query = translate(expression);
      Map<String, Object> negative = translate(call(SqlStdOperatorTable.NOT, expression));
      assertNotNull(query, positive.toString());
      assertFalse(matches(negative, Map.of()));
      assertEquals(
          ElasticsearchPredicate.and(
              List.of(Map.of("exists", Map.of("field", "label")), not(query))),
          negative);
    }
  }

  @Test
  void restrictedRegexMatchesCalciteFindSemanticsIncludingEscapesUnicodeAndNewlines() {
    Map<String, String> patterns = new LinkedHashMap<>();
    patterns.put("error", "*error*");
    patterns.put("^/api/", "/api/*");
    patterns.put("\\A/api/", "/api/*");
    patterns.put("^/api/.*", "/api/*");
    patterns.put("error.*", "*error*");
    patterns.put("error\\z", "*error");
    patterns.put("^error\\z", "error");
    patterns.put("a\\*\\?\\.\\\\", "*a\\*\\?.\\\\*");
    patterns.put("^", "*");
    patterns.put("", "**");
    patterns.put("😀", "*😀*");
    SqlFunctions.RegexFunction calcite = new SqlFunctions.RegexFunction();
    List<String> values =
        List.of(
            "",
            "error",
            "error\n",
            "\nerror",
            "some error!",
            "/api/error",
            "/api/\n",
            "\n/api/",
            "x\u2028error",
            "a*?.\\",
            "😀",
            "x😀\ny");
    patterns.forEach(
        (pattern, wildcard) -> {
          Map<String, Object> query = translate(regex(pattern));
          assertEquals(Map.of("wildcard", Map.of("label", wildcard)), query, pattern);
          Map<String, Object> negated = translate(call(SqlStdOperatorTable.NOT, regex(pattern)));
          for (String value : values) {
            boolean expected = calcite.regexpContains(value, pattern);
            assertEquals(
                expected, matches(query, Map.of("label", value)), pattern + " on " + value);
            assertEquals(
                !expected, matches(negated, Map.of("label", value)), pattern + " on " + value);
          }
          assertFalse(matches(query, Map.of()));
          assertFalse(matches(negated, Map.of()));
        });
  }

  private RexNode like(String pattern, boolean sensitive, String escape) {
    return call(
        sensitive ? SqlStdOperatorTable.LIKE : SqlLibraryOperators.ILIKE,
        label,
        string(pattern),
        string(escape));
  }

  private RexNode regex(String pattern) {
    return call(SqlLibraryOperators.REGEXP_CONTAINS, label, string(pattern));
  }

  private RexNode string(String value) {
    return rex.makeCharLiteral(new NlsString(value, "UTF-8", SqlCollation.IMPLICIT));
  }

  private RexNode call(SqlOperator operator, RexNode... args) {
    return rex.makeCall(operator, args);
  }

  private RexInputRef ref(int index) {
    return rex.makeInputRef(
        types.createTypeWithNullability(FIELDS.get(index).type(types), true), index);
  }

  private Map<String, Object> translate(RexNode expression) {
    return ElasticsearchPredicate.translate(expression, FIELDS);
  }

  private static Map<String, Object> regexp(String pattern) {
    return Map.of("regexp", Map.of("label", Map.of("value", pattern, "flags", "NONE")));
  }

  private static Map<String, Object> not(Map<String, Object> query) {
    return Map.of("bool", Map.of("must_not", query));
  }

  private RexNode planned(String command) throws Exception {
    Table table =
        new AbstractTable() {
          @Override
          public RelDataType getRowType(RelDataTypeFactory factory) {
            RelDataTypeFactory.Builder builder = factory.builder();
            FIELDS.forEach(
                field ->
                    builder.add(
                        field.name(),
                        factory.createTypeWithNullability(field.type(factory), true)));
            return builder.build();
          }
        };
    AbstractSchema schema =
        new AbstractSchema() {
          @Override
          protected Map<String, Table> getTableMap() {
            return Map.of("events", table);
          }
        };
    try (var context =
        UnifiedQueryContext.builder()
            .language(QueryType.PPL)
            .catalog("catalog", schema)
            .defaultNamespace("catalog")
            .build()) {
      RelNode node = new UnifiedQueryPlanner(context).plan("source=events | " + command);
      while (!(node instanceof Filter) && node.getInputs().size() == 1) node = node.getInput(0);
      assertTrue(node instanceof Filter, node.toString());
      return ((Filter) node).getCondition();
    }
  }

  private static Comparable comparable(Object value) {
    if (value == null) return NullSentinel.INSTANCE;
    return value instanceof Number ? new BigDecimal(value.toString()) : (Comparable) value;
  }

  /**
   * Evaluates the emitted boolean/term/range/glob subset, not simulated Elasticsearch responses.
   */
  @SuppressWarnings("unchecked")
  private static boolean matches(Map<String, Object> query, Map<String, Object> document) {
    if (query.containsKey("match_all")) return true;
    if (query.containsKey("match_none")) return false;
    if (query.get("exists") instanceof Map<?, ?> exists) {
      return document.get(exists.get("field")) != null;
    }
    if (query.get("bool") instanceof Map<?, ?> bool) {
      if (bool.get("must") instanceof List<?> children
          && !children.stream().allMatch(child -> matches((Map<String, Object>) child, document)))
        return false;
      if (bool.get("should") instanceof List<?> children
          && children.stream()
                  .filter(child -> matches((Map<String, Object>) child, document))
                  .count()
              < ((Number) bool.get("minimum_should_match")).intValue()) return false;
      return !(bool.get("must_not") instanceof Map<?, ?> child)
          || !matches((Map<String, Object>) child, document);
    }
    if (query.get("term") instanceof Map<?, ?> term) {
      var entry = term.entrySet().iterator().next();
      Object value = document.get(entry.getKey());
      if (value == null) return false;
      return comparable(value).compareTo(comparable(entry.getValue())) == 0;
    }
    if (query.get("range") instanceof Map<?, ?> range) {
      var entry = range.entrySet().iterator().next();
      Object value = document.get(entry.getKey());
      if (value == null) return false;
      var bound = ((Map<?, ?>) entry.getValue()).entrySet().iterator().next();
      int comparison = comparable(value).compareTo(comparable(bound.getValue()));
      return switch (bound.getKey().toString()) {
        case "lt" -> comparison < 0;
        case "lte" -> comparison <= 0;
        case "gt" -> comparison > 0;
        case "gte" -> comparison >= 0;
        default -> throw new AssertionError(bound);
      };
    }
    if (query.get("regexp") instanceof Map<?, ?> regexp) {
      var entry = regexp.entrySet().iterator().next();
      Object value = document.get(entry.getKey());
      if (value == null) return false;
      // Generated regex uses only concatenation, escaped punctuation, ASCII classes and .*,
      // shared by Java and Lucene. Never evaluate arbitrary user Java regex as Lucene regex.
      var options = (Map<?, ?>) entry.getValue();
      assertEquals("NONE", options.get("flags"));
      return Pattern.compile(options.get("value").toString(), Pattern.DOTALL)
          .matcher(value.toString())
          .matches();
    }
    if (query.get("wildcard") instanceof Map<?, ?> wildcard) {
      var entry = wildcard.entrySet().iterator().next();
      Object value = document.get(entry.getKey());
      if (value == null) return false;
      String glob = entry.getValue().toString();
      StringBuilder regex = new StringBuilder();
      for (int i = 0; i < glob.length(); ) {
        int c = glob.codePointAt(i);
        i += Character.charCount(c);
        if (c == '\\') {
          c = glob.codePointAt(i);
          i += Character.charCount(c);
          regex.append(Pattern.quote(new String(Character.toChars(c))));
        } else if (c == '*') regex.append(".*");
        else if (c == '?') regex.append('.');
        else regex.append(Pattern.quote(new String(Character.toChars(c))));
      }
      return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(value.toString()).matches();
    }
    throw new AssertionError("Unexpected query: " + query);
  }
}
