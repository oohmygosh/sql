/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.*;

import java.util.*;
import java.util.stream.Collectors;
import org.junit.Test;
import org.opensearch.sql.api.completion.CompletionMetadata.*;

/** Positive and negative menu contracts, including the reported VARCHAR regression. */
public class SqlOperatorGuidanceTest {
  static final String SOURCE = "SELECT * FROM typed_values WHERE ";
  static final Set<String> TEXT = Set.of("=", "!=", "<>", "IN", "NOT", "IS", "LIKE", "REGEXP");
  static final Set<String> NUMBER =
      Set.of("=", "!=", "<>", "IN", "NOT", "IS", "BETWEEN", ">", ">=", "<", "<=");
  static final Set<String> BOOL = Set.of("=", "!=", "<>", "IN", "NOT", "IS", "AND", "OR");
  static final Set<String> MATH = Set.of("+", "-", "*", "/", "%");
  static final Set<String> OPERATORS =
      java.util.stream.Stream.of(TEXT, NUMBER, BOOL, MATH, Set.of("!", "."))
          .flatMap(Set::stream)
          .collect(Collectors.toSet());
  final CompletionMetadata metadata =
      new CompletionMetadata() {
        public String resolveSource(String source) {
          return source;
        }

        public List<Relation> tables(String source, List<String> path, String prefix, int limit) {
          return List.of(new Relation(path, "typed_values", "table"));
        }

        public List<Field> fields(String source, RelationRef relation) {
          return List.of(
              new Field("text_value", "varchar"),
              new Field("number_value", "bigint"),
              new Field("boolean_value", "boolean"),
              new Field("time_value", "timestamp"),
              new Field("object_value", "row"),
              new Field("unknown_value", "any"));
        }
      };
  final QueryCompletionService service =
      new QueryCompletionService(metadata, List.of(new SqlCompletionProvider()));

  CompletionResponse complete(String input, String trigger) {
    int marker = input.indexOf('¦');
    return service.complete(
        new CompletionRequest(
            "sql",
            "typed",
            input.replace("¦", ""),
            marker < 0 ? input.length() : marker,
            17,
            trigger));
  }

  CompletionResponse complete(String input) {
    return complete(input, "manual");
  }

  Set<String> labels(CompletionResponse response) {
    return response.items().stream()
        .map(CompletionResponse.Item::label)
        .collect(Collectors.toSet());
  }

  Set<String> operators(String input) {
    return labels(complete(input)).stream().filter(OPERATORS::contains).collect(Collectors.toSet());
  }

  String accept(String input, String label) {
    var item =
        complete(input).items().stream()
            .filter(i -> i.label().equals(label))
            .findFirst()
            .orElseThrow();
    var edit = item.textEdit();
    return input.substring(0, edit.from()) + edit.newText() + input.substring(edit.to());
  }

  @Test
  public void varcharOperatorsExcludeMathAndOrderingForEveryTriggerAndWhitespace() {
    for (String trigger : List.of("input", "accept", "manual")) {
      for (String whitespace : List.of("", " ", "\n")) {
        var response = complete(SOURCE + "`text_value`" + whitespace, trigger);
        Set<String> actual =
            labels(response).stream().filter(OPERATORS::contains).collect(Collectors.toSet());
        assertEquals(trigger + whitespace, TEXT, actual);
      }
    }
  }

  @Test
  public void fieldTypeConstrainsEveryPredicateSlot() {
    for (String prefix :
        List.of(
            SOURCE,
            SOURCE + "NOT ",
            SOURCE + "(",
            SOURCE + "number_value=1 AND ",
            "SELECT * FROM typed_values l JOIN typed_values r ON ",
            "SELECT CASE WHEN ")) {
      String field = prefix.contains(" ON ") ? "l." : "";
      String from = prefix.startsWith("SELECT CASE") ? "¦ FROM typed_values" : "";
      assertEquals(prefix, TEXT, operators(prefix + field + "text_value " + from));
      assertEquals(prefix, NUMBER, operators(prefix + field + "number_value " + from));
    }
    assertEquals(TEXT, operators("SELECT IF(text_value ¦,1,0) FROM typed_values"));
    assertEquals(BOOL, operators(SOURCE + "boolean_value "));
    assertEquals(NUMBER, operators(SOURCE + "time_value "));
  }

  @Test
  public void unknownAndCompositeTypesDoNotGuessNumericOrTextOperations() {
    assertEquals(Set.of("IS"), operators(SOURCE + "object_value "));
    var response = complete(SOURCE + "unknown_value ");
    assertEquals(
        Set.of("IS"),
        labels(response).stream().filter(OPERATORS::contains).collect(Collectors.toSet()));
    assertTrue(response.incomplete());
    assertTrue(response.message(), response.message().contains("any"));
  }

  @Test
  public void aKeywordPrefixCannotBypassTypeConstraints() {
    assertEquals(Set.of("LIKE"), labels(complete(SOURCE + "text_value li")));
    assertTrue(labels(complete(SOURCE + "number_value li")).isEmpty());
    assertTrue(labels(complete(SOURCE + "object_value li")).isEmpty());
  }

  @Test
  public void comparisonValuesAndCompletedPredicatesDoNotReopenOperatorMenus() {
    var value = complete(SOURCE + "text_value = ");
    assertTrue(value.items().isEmpty());
    assertTrue(value.message(), value.message().contains("字符串"));
    assertEquals(Set.of("TRUE", "FALSE"), labels(complete(SOURCE + "boolean_value = ")));
    assertEquals(Set.of("AND", "OR"), operators(SOURCE + "text_value='alpha' "));
    assertEquals(Set.of("AND", "OR"), operators(SOURCE + "number_value IN (1,2) "));
    assertEquals(Set.of("AND"), operators(SOURCE + "number_value BETWEEN 1 "));
    assertEquals(Set.of("AND", "OR"), operators(SOURCE + "number_value BETWEEN 1 AND 2 "));
  }

  @Test
  public void mathRemainsAvailableOnlyForNumericValueExpressions() {
    assertTrue(operators("SELECT number_value ¦ FROM typed_values").containsAll(MATH));
    assertTrue(Collections.disjoint(MATH, operators("SELECT text_value ¦ FROM typed_values")));
    assertTrue(operators("SELECT ABS(number_value ¦) FROM typed_values").containsAll(MATH));
    assertTrue(
        Collections.disjoint(MATH, operators("SELECT LOWER(text_value ¦) FROM typed_values")));
    assertEquals(NUMBER, operators(SOURCE + "(number_value + 1) "));
  }

  @Test
  public void castsFunctionsAndDerivedOutputsRetainTheirResultTypes() {
    assertEquals(TEXT, operators(SOURCE + "CAST(unknown_value AS STRING) "));
    assertEquals(TEXT, operators(SOURCE + "LOWER(text_value) "));
    assertEquals(NUMBER, operators(SOURCE + "ABS(number_value) "));
    assertEquals(
        NUMBER, operators("SELECT SUM(number_value) AS total FROM typed_values HAVING total "));
    assertEquals(
        TEXT,
        operators(
            "SELECT * FROM (SELECT CAST(unknown_value AS STRING) AS typed FROM typed_values) d"
                + " WHERE d.typed "));
    assertEquals(
        TEXT,
        operators(
            "SELECT * FROM typed_values l WHERE EXISTS (SELECT * FROM typed_values r WHERE"
                + " l.text_value "));
  }

  @Test
  public void acceptingCompoundComparisonOpensTheTypedValueSlot() {
    String text = accept(SOURCE + "number_value ", ">=");
    var response = complete(text);
    assertTrue(response.items().isEmpty());
    assertTrue(response.message(), response.message().contains("数值"));
    assertEquals(Set.of("AND", "OR"), operators(text + "1 "));
  }
}
