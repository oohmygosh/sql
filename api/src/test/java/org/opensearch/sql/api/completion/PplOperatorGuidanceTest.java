/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.junit.Test;
import org.opensearch.sql.api.completion.CompletionMetadata.Field;
import org.opensearch.sql.api.completion.CompletionMetadata.Relation;
import org.opensearch.sql.api.completion.CompletionMetadata.RelationRef;
import org.opensearch.sql.api.completion.CompletionResponse.Item;
import org.opensearch.sql.common.antlr.CaseInsensitiveCharStream;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLLexer;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLParser;

/** Operator menus must follow operand type and use, not merely grammar reachability. */
public class PplOperatorGuidanceTest {
  private static final String SOURCE = "source=`logevent-*` | ";
  private static final Set<String> NUMBER =
      Set.of("=", "!=", ">", "<", ">=", "<=", "in", "not", "is", "between");
  private static final Set<String> TEXT =
      Set.of("=", "!=", "in", "not", "is", "like", "ilike", "regexp", "contains");
  private static final Set<String> MATH = Set.of("*", "/", "%", "+", "-");
  private static final Set<String> CONNECTORS = Set.of("and", "or", "xor", "|");
  private final CompletionMetadata metadata =
      new CompletionMetadata() {
        public String resolveSource(String source) {
          return source;
        }

        public List<Relation> tables(
            String source, List<String> namespace, String prefix, int limit) {
          return List.of();
        }

        public List<Field> fields(String source, RelationRef relation) {
          return List.of(
              new Field("host", "any"),
              new Field("host.name", "varchar"),
              new Field("host.ip", "varchar"),
              new Field("detail", "row"),
              new Field("detail.name", "varchar", List.of("detail", "name")),
              new Field("items", "array"),
              new Field("attributes", "map"),
              new Field("unknown", "unknown"),
              new Field("future", "future_type"),
              new Field("point_value", "geo_point"),
              new Field("runtime_value", "runtime"),
              new Field("ambiguous_bool", "notboolean"),
              new Field("null_type", null),
              new Field("size", "bigint"),
              new Field("message", "varchar"),
              new Field("timestamp", "timestamp"),
              new Field("active", "boolean"));
        }
      };
  private final QueryCompletionService service =
      new QueryCompletionService(metadata, List.of(new PplCompletionProvider()));

  @Test
  public void exactReportedRequestDoesNotAssumeAnyIsNumericOrText() {
    String text = SOURCE + "where `host` ";
    assertEquals(35, text.length());
    for (String trigger : List.of("input", "accept", "manual")) {
      CompletionResponse response = complete(text, trigger);
      assertEquals(17, response.documentVersion());
      assertEquals(Set.of("is"), operators(response));
      assertEquals(Set.of("host.name", "host.ip"), fields(response));
      assertTrue(response.incomplete());
      assertTrue(response.message().contains("any"));
      assertTrue(response.message().contains("子字段"));
      assertTrue(
          response.items().stream()
              .filter(item -> item.label().equals("is"))
              .allMatch(item -> item.detail().contains("空值")));
      for (var item : response.items()) {
        assertTrue(item.textEdit().from() <= 35);
        assertEquals(35, item.textEdit().to());
      }
    }
  }

  @Test
  public void scalarPredicateOperatorsAreTypeSpecificAndIndependentOfWhitespace() {
    for (String whitespace : List.of("", " ", "\n")) {
      assertEquals(NUMBER, operators(complete(SOURCE + "where `size`" + whitespace)));
      assertEquals(TEXT, operators(complete(SOURCE + "where `message`" + whitespace)));
      assertEquals(NUMBER, operators(complete(SOURCE + "where `timestamp`" + whitespace)));
      assertEquals(
          Set.of("=", "!=", "in", "not", "is", "and", "or", "xor", "|"),
          operators(complete(SOURCE + "where `active`" + whitespace)));
    }
  }

  @Test
  public void operatorPrefixesCannotBypassKnownTypeConstraints() {
    assertTrue(complete(SOURCE + "where `size` li").items().isEmpty());
    assertTrue(complete(SOURCE + "where `host` li").items().isEmpty());
    assertEquals(Set.of("like"), operators(complete(SOURCE + "where `message` li")));
    assertEquals(Set.of("in"), operators(complete(SOURCE + "where `message` not ")));
  }

  @Test
  public void complexAndUncertainFieldsOfferOnlyNullChecks() {
    for (String field :
        List.of(
            "host",
            "detail",
            "items",
            "attributes",
            "unknown",
            "future",
            "null_type",
            "point_value",
            "runtime_value",
            "ambiguous_bool")) {
      var response = complete(SOURCE + "where `" + field + "` ");
      assertEquals(field, Set.of("is"), operators(response));
    }
  }

  @Test
  public void choosingRelatedFlatFieldReplacesTheOperandAndRecomputesItsOperators() {
    Editor editor = new Editor(SOURCE + "where `host` ");
    editor.accept("host.name");
    assertEquals(SOURCE + "where `host.name` ", editor.text);
    assertEquals(TEXT, operators(editor.response()));
    assertFalse(editor.response().incomplete());
    editor.accept("=");
    assertTrue(editor.response().items().isEmpty());
    assertTrue(editor.response().message().contains("字符串"));
    editor.type("'server-1'");
    assertEquals(CONNECTORS, operators(editor.response()));
    editor.accept("|").accept("head").accept("10").assertParses();
  }

  @Test
  public void relatedFieldsPreserveStructuredPathsAndSourceAliases() {
    Editor structured = new Editor(SOURCE + "where `detail` ");
    structured.accept("detail.name");
    assertEquals(SOURCE + "where `detail`.`name` ", structured.text);
    structured.accept("is").accept("null").assertParses();
    Editor aliased = new Editor("source=`logevent-*` as l | where `l`.`host` ");
    aliased.accept("l.host.name");
    assertEquals("source=`logevent-*` as l | where `l`.`host.name` ", aliased.text);
    aliased.accept("is").accept("not").accept("null").assertParses();
  }

  @Test
  public void unknownObjectCanCompleteANullPredicateWithoutScalarOperators() {
    Editor editor = new Editor(SOURCE + "where `host` ");
    editor.accept("is");
    assertEquals(Set.of("not", "null"), operators(editor.response()));
    editor.accept("not").accept("null");
    assertEquals(CONNECTORS, operators(editor.response()));
    editor.accept("|").accept("fields").accept("host").assertParses();
  }

  @Test
  public void inAndBetweenFinishAsPredicatesRatherThanReopeningValueOperators() {
    for (String predicate : List.of("size in (1,2)", "size between 1 and 2", "message is null"))
      assertEquals(predicate, CONNECTORS, operators(complete(SOURCE + "where " + predicate)));
  }

  @Test
  public void numericCalculationsRemainAvailableInActualValueExpressions() {
    assertTrue(operators(complete(SOURCE + "eval total=`size` ")).containsAll(MATH));
    assertTrue(operators(complete(SOURCE + "stats avg(`size` ")).containsAll(MATH));
    for (String field : List.of("host", "items", "message", "active", "timestamp"))
      assertTrue(
          field,
          java.util.Collections.disjoint(
              MATH, operators(complete(SOURCE + "eval copied=`" + field + "` "))));
    Editor editor = new Editor(SOURCE + "where `size` ");
    assertTrue(java.util.Collections.disjoint(MATH, operators(editor.response())));
    editor.type("+ 1 ").accept(">").type("10").assertParses();
  }

  @Test
  public void arithmeticAndExplicitCastsProvideTypesForLaterPredicates() {
    assertEquals(
        NUMBER, operators(complete(SOURCE + "eval adjusted=(size + -1) | where adjusted ")));
    assertEquals(
        TEXT, operators(complete(SOURCE + "eval typed=cast(host as string) | where typed ")));
    assertEquals(
        NUMBER,
        operators(
            complete(SOURCE + "eval typed=cast(host as double) | fields typed | where typed ")));
    assertEquals(
        Set.of("=", "!=", "in", "not", "is", "and", "or", "xor", "|"),
        operators(complete(SOURCE + "eval present=host is not null | where present ")));
  }

  @Test
  public void computedValuesUseTheirResultTypeRatherThanTheFinalToken() {
    assertEquals(NUMBER, operators(complete(SOURCE + "where (size + 1) ")));
    assertEquals(TEXT, operators(complete(SOURCE + "eval text=lower(message) | where text ")));
    assertEquals(NUMBER, operators(complete(SOURCE + "eval total=round(size) | where total ")));
    // The current AST visits ANSI datetime syntax as its string child. CAST produces a typed value.
    assertEquals(TEXT, operators(complete(SOURCE + "eval d=date '2025-01-01' | where d ")));
    assertEquals(
        NUMBER, operators(complete(SOURCE + "eval d=cast('2025-01-01' as date) | where d ")));
  }

  @Test
  public void nestedPredicatesUseTheSameTypeRestrictionsAndKeepTheirClosingDelimiter() {
    Editor editor = new Editor(SOURCE + "where (`host` ");
    assertEquals(Set.of("is"), operators(editor.response()));
    editor.accept("host.name").accept("=").type("'server-1'");
    assertEquals(Set.of("and", "or", "xor", ")"), operators(editor.response()));
    editor.accept(")").assertParses();
  }

  private CompletionResponse complete(String text) {
    return complete(text, "input");
  }

  private CompletionResponse complete(String text, String trigger) {
    return service.complete(new CompletionRequest("ppl", "logs", text, text.length(), 17, trigger));
  }

  private static Set<String> operators(CompletionResponse result) {
    return result.items().stream()
        .filter(item -> !item.kind().equals("field"))
        .map(Item::label)
        .collect(java.util.stream.Collectors.toSet());
  }

  private static Set<String> fields(CompletionResponse result) {
    return result.items().stream()
        .filter(item -> item.kind().equals("field"))
        .map(Item::label)
        .collect(java.util.stream.Collectors.toSet());
  }

  private class Editor {
    String text;

    Editor(String text) {
      this.text = text;
    }

    CompletionResponse response() {
      return complete(text);
    }

    Editor type(String value) {
      text += value;
      return this;
    }

    Editor accept(String label) {
      var response = response();
      var item =
          response.items().stream()
              .filter(candidate -> candidate.label().equals(label))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "Missing " + label + " after " + text + ": " + response.items()));
      var edit = item.textEdit();
      text = text.substring(0, edit.from()) + edit.newText() + text.substring(edit.to());
      return this;
    }

    void assertParses() {
      var parser =
          new OpenSearchPPLParser(
              new CommonTokenStream(new OpenSearchPPLLexer(new CaseInsensitiveCharStream(text))));
      parser.removeErrorListeners();
      parser.root();
      assertEquals(text, 0, parser.getNumberOfSyntaxErrors());
      assertEquals(Token.EOF, parser.getCurrentToken().getType());
    }
  }
}
