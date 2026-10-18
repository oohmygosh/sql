/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.*;

import java.util.ArrayList;
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

/** Presence and absence are both part of the product contract, including between accepted edits. */
public class PplGuidanceTest {
  private static final String SOURCE = "source=orders | ";
  private static final Set<String> FIELDS =
      Set.of("amount", "amount2", "timestamp", "active", "message");
  private final List<RelationRef> fieldReads = new ArrayList<>();
  private final CompletionMetadata metadata =
      new CompletionMetadata() {
        public String resolveSource(String source) {
          return source;
        }

        public List<Relation> tables(
            String source, List<String> namespace, String prefix, int limit) {
          return List.of("orders", "customers").stream()
              .filter(name -> name.startsWith(prefix))
              .map(name -> new Relation(namespace, name, "table"))
              .toList();
        }

        public List<Field> fields(String source, RelationRef relation) {
          fieldReads.add(relation);
          return switch (relation.name()) {
            case "orders" ->
                List.of(
                    new Field("amount", "double"),
                    new Field("amount2", "double"),
                    new Field("timestamp", "timestamp"),
                    new Field("active", "boolean"),
                    new Field("message", "varchar"));
            case "customers" ->
                List.of(new Field("customer_id", "string"), new Field("name", "string"));
            default -> throw new AssertionError("Unexpected relation " + relation);
          };
        }
      };
  private final QueryCompletionService service =
      new QueryCompletionService(metadata, List.of(new PplCompletionProvider()));

  @Test
  public void commandBoundaryDoesNotAdvertiseFieldsValuesOrFunctions() {
    var result = complete(SOURCE);
    assertTrue(labels(result).containsAll(Set.of("where", "stats", "bin", "fields")));
    assertTrue(result.items().stream().allMatch(item -> item.kind().equals("command")));
    assertFalse(result.incomplete());
    assertTrue(fieldReads.isEmpty());
  }

  @Test
  public void fieldAndOperandStartsAreFocusedWithoutTruncation() {
    for (String suffix :
        List.of(
            "where ",
            "eval result=",
            "eval result=round(",
            "stats avg(",
            "spath input=",
            "streamstats reset_before=",
            "fields ",
            "sort ",
            "regex ")) {
      var result = complete(SOURCE + suffix);
      assertEquals(suffix, FIELDS, fields(result));
      assertFalse(suffix, result.incomplete());
      assertFalse(
          suffix,
          result.items().stream()
              .anyMatch(
                  item -> Set.of("function", "value", "table", "command").contains(item.kind())));
      assertTrue(
          suffix,
          java.util.Collections.disjoint(
              labels(result),
              Set.of("true", "false", "null", "date", "interval", "->", ".", "10", "100")));
    }
    assertFalse(labels(complete(SOURCE + "eval result=round(")).contains(")"));
    assertTrue(labels(complete(SOURCE + "stats count(")).contains(")"));
  }

  @Test
  public void comparisonValuesDoNotDumpFieldsOrInventSampleValues() {
    var numeric = complete(SOURCE + "where amount = ");
    assertTrue(numeric.items().isEmpty());
    assertTrue(numeric.message().contains("数值"));
    assertFalse(numeric.message().contains("别名"));
    var string = complete(SOURCE + "where message = ");
    assertTrue(string.items().isEmpty());
    assertTrue(string.message().contains("字符串"));
    assertEquals(Set.of("true", "false"), labels(complete(SOURCE + "where active = ")));
    assertTrue(fields(complete(SOURCE + "where active = ")).isEmpty());
  }

  @Test
  public void inListUsesTheComparedFieldType() {
    assertTrue(complete(SOURCE + "where amount in (").items().isEmpty());
    assertFalse(complete(SOURCE + "where amount in (").message().contains("函数"));
    assertTrue(complete(SOURCE + "where message in (").items().isEmpty());
    assertEquals(Set.of("true", "false"), labels(complete(SOURCE + "where active in (")));
  }

  @Test
  public void acceptedPredicateTransitionsToLogicAndBackToFields() {
    Editor editor = new Editor(SOURCE);
    editor.accept("where").accept("amount").accept("=").only();
    editor.type("10").only("and", "or", "xor", "|");
    editor.accept("and");
    assertEquals(FIELDS, fields(editor.response()));
    editor.accept("active").accept("=").only("true", "false");
    editor.accept("true").accept("|").accept("fields").accept("amount").only(",", "|");
    editor.accept(",").accept("active").assertParses();
  }

  @Test
  public void groupedPredicateKeepsItsRequiredClosingDelimiter() {
    Editor editor = new Editor(SOURCE + "where (");
    editor.accept("amount").accept(">").type("10").only("and", "or", "xor", ")");
    editor.accept(")").accept("|").accept("head").accept("10").assertParses();
  }

  @Test
  public void explicitValueExpressionPrefixOpensOnlyItsMatches() {
    Editor editor = new Editor(SOURCE + "where amount = ");
    editor.only().type("ro");
    assertEquals(Set.of("round"), labels(editor.response()));
    editor.accept("round").accept("amount2").accept(")");
    editor.only("and", "or", "xor", "|").assertParses();
    assertEquals(Set.of("amount", "amount2"), fields(complete(SOURCE + "where amount = am")));
  }

  @Test
  public void nestedFunctionJourneyPreservesStructuralKeywordsAndDownstreamScope() {
    Editor editor = new Editor(SOURCE + "eval result=");
    editor.type("ro").accept("round").type("ca").accept("cast").accept("amount");
    editor.accept("as");
    assertTrue(
        editor.response().items().stream()
            .noneMatch(item -> item.kind().equals("field") || item.kind().equals("function")));
    editor.accept("double").accept(")").accept(",").type("2").accept(")");
    editor.accept("|").accept("fields").accept("result").only(",", "|").assertParses();
  }

  @Test
  public void operatorInsideAFunctionIsNotMistakenForAnotherFunctionHead() {
    Item like =
        complete(SOURCE + "eval result=if(`message`").items().stream()
            .filter(item -> item.label().equals("like"))
            .findFirst()
            .orElseThrow();
    assertEquals("keyword", like.kind());
    assertFalse(like.textEdit().newText().contains("("));
  }

  @Test
  public void completedFieldListDoesNotOfferEveryFieldAgain() {
    for (String command : List.of("fields", "table", "dedup")) {
      Editor editor = new Editor(SOURCE + command + " ");
      editor.accept("amount");
      assertTrue(command, fields(editor.response()).isEmpty());
      assertTrue(
          command,
          java.util.Collections.disjoint(labels(editor.response()), Set.of(".", "*", "%", "->")));
      editor.accept(",");
      assertEquals(command, FIELDS, fields(editor.response()));
      editor.accept("amount2").assertParses();
    }
  }

  @Test
  public void requiredAdjacentFieldsRemainVisible() {
    Editor editor = new Editor(SOURCE + "xyseries ");
    editor.accept("message");
    assertEquals(FIELDS, fields(editor.response()));
    editor.accept("active");
    assertTrue(labels(editor.response()).contains("in"));
  }

  @Test
  public void groupSlotKeepsSpanButItsFieldSlotDoesNotOfferImplicitValueExamples() {
    Editor editor = new Editor(SOURCE + "stats count() as total by ");
    assertTrue(labels(editor.response()).contains("span"));
    editor.accept("span");
    assertEquals(Set.of("amount", "amount2", "timestamp", "message"), fields(editor.response()));
    assertTrue(editor.response().items().stream().allMatch(item -> item.kind().equals("field")));
    editor.accept("timestamp").accept(",").only("1s", "1m", "1h", "1d", "5m");
    editor.accept("1h").accept(")").accept("as").only().type("hour");
    editor.accept("|").accept("fields");
    assertEquals(Set.of("total", "hour"), fields(editor.response()));
    editor.accept("hour").assertParses();
    new Editor(SOURCE + "stats count() by span(").type("1").accept("1h").accept(")").assertParses();
  }

  @Test
  public void selectedParameterKeysRequireEqualsAndDoNotReadFields() {
    for (String suffix :
        List.of(
            "stats allnum ",
            "dedup amount consecutive ",
            "bin timestamp span ",
            "rex field ",
            "timechart span ",
            "foreach mode ",
            "foreach fieldstr ")) {
      new Editor(SOURCE + suffix).only("=");
    }
    assertTrue(fieldReads.isEmpty());
  }

  @Test
  public void parameterValuesStayWithinTheirDeclaredDomains() {
    new Editor(SOURCE + "stats allnum=").only("true", "false");
    new Editor(SOURCE + "dedup amount consecutive=").only("false");
    new Editor(SOURCE + "rex field=message mode=").only("sed", "extract");
    new Editor("makeresults format=").only("csv", "json");
    new Editor(SOURCE + "bin timestamp span=").only("1s", "1m", "1h", "1d", "5m");
    new Editor(SOURCE + "bin amount span=").only("1", "10", "100", "log10");
  }

  @Test
  public void bucketFieldSlotsExcludeKnownUnsupportedTypes() {
    for (String suffix : List.of("bin ", "stats count() by span(", "timechart timefield=")) {
      assertFalse(suffix, fields(complete(SOURCE + suffix)).contains("active"));
      assertTrue(suffix, fields(complete(SOURCE + suffix)).contains("timestamp"));
    }
    assertTrue(fields(complete(SOURCE + "stats count() by ")).contains("active"));
  }

  @Test
  public void largeMappingsCannotHideRequiredSyntaxOrSpan() {
    CompletionMetadata large =
        new CompletionMetadata() {
          public String resolveSource(String source) {
            return source;
          }

          public List<Relation> tables(
              String source, List<String> namespace, String prefix, int limit) {
            return List.of();
          }

          public List<Field> fields(String source, RelationRef relation) {
            return java.util.stream.IntStream.range(0, 200)
                .mapToObj(index -> new Field("field" + index, "double"))
                .toList();
          }
        };
    var bounded = new QueryCompletionService(large, List.of(new PplCompletionProvider()));
    for (String suffix : List.of("stats count() by ", "where ")) {
      String query = SOURCE + suffix;
      var result =
          bounded.complete(new CompletionRequest("ppl", "logs", query, query.length(), 1, "input"));
      assertEquals(100, result.items().size());
      assertTrue(result.incomplete());
      if (suffix.startsWith("stats")) {
        assertEquals("span", result.items().getFirst().label());
        assertTrue(
            result.items().stream()
                .allMatch(item -> item.kind().equals("field") || item.label().equals("span")));
      } else assertTrue(labels(result).containsAll(Set.of("not", "(", "exists")));
    }
  }

  @Test
  public void aliasesAreUserSuppliedRatherThanReplacedBySourceColumns() {
    for (String suffix :
        List.of(
            "eval ",
            "rename amount as ",
            "stats count() as ",
            "bin amount span=10 as ",
            "spath output=")) {
      var result = complete(SOURCE + suffix);
      assertTrue(suffix, result.items().isEmpty());
      assertTrue(suffix, result.message().contains("别名"));
    }
  }

  @Test
  public void lookupSidesNeverBorrowUnrelatedFieldsOrFunctions() {
    Editor editor = new Editor(SOURCE + "lookup customers ");
    assertEquals(Set.of("customer_id", "name"), labels(editor.response()));
    editor.accept("customer_id").accept("as");
    assertEquals(FIELDS, labels(editor.response()));
    editor.accept("message").accept("output").accept("name").accept("as").only();
    editor.type("customer_name").assertParses();
  }

  @Test
  public void nestedPipelineUsesTheSameFocusedSlotsAndOwnTable() {
    Editor editor = new Editor(SOURCE + "append [source=customers ");
    editor.only("|", "]").accept("|").accept("where");
    assertEquals(Set.of("customer_id", "name"), fields(editor.response()));
    assertFalse(labels(editor.response()).contains("amount"));
    editor.accept("name").accept("=").only().type("'alice'");
    editor.only("and", "or", "xor", "|", "]").accept("]").assertParses();
  }

  @Test
  public void syntaxAndGuidanceDoNotDependOnTrigger() {
    String text = SOURCE + "where amount = ";
    for (String trigger : List.of("input", "accept", "manual")) {
      var result =
          service.complete(new CompletionRequest("ppl", "logs", text, text.length(), 78, trigger));
      assertEquals(78, result.documentVersion());
      assertTrue(result.items().isEmpty());
      assertFalse(result.incomplete());
    }
  }

  private CompletionResponse complete(String text) {
    return service.complete(new CompletionRequest("ppl", "logs", text, text.length(), 1, "input"));
  }

  @Test
  public void repeatableTargetsTransitionToTheirBodyWithoutAnotherFieldDump() {
    Editor editor = new Editor(SOURCE + "foreach ");
    editor.accept("mode").only("=").accept("=").accept("multifield");
    editor.accept("amount");
    assertTrue(fields(editor.response()).isEmpty());
    assertFalse(labels(editor.response()).contains("="));
    assertTrue(labels(editor.response()).contains("["));
    editor
        .accept("[")
        .accept("eval")
        .type("result ")
        .accept("=")
        .type("1")
        .accept("]")
        .assertParses();
  }

  private static Set<String> labels(CompletionResponse response) {
    return response.items().stream().map(Item::label).collect(java.util.stream.Collectors.toSet());
  }

  private static Set<String> fields(CompletionResponse response) {
    return response.items().stream()
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

    Editor only(String... wanted) {
      assertEquals(text, Set.of(wanted), labels(response()));
      return this;
    }

    Editor type(String value) {
      text += value;
      return this;
    }

    Editor accept(String label) {
      var result = response();
      Item item =
          result.items().stream()
              .filter(candidate -> candidate.label().equals(label))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "Missing " + label + " after " + text + ": " + labels(result)));
      var edit = item.textEdit();
      text = text.substring(0, edit.from()) + edit.newText() + text.substring(edit.to());
      return this;
    }

    void assertParses() {
      var lexer = new OpenSearchPPLLexer(new CaseInsensitiveCharStream(text));
      var parser = new OpenSearchPPLParser(new CommonTokenStream(lexer));
      parser.removeErrorListeners();
      parser.root();
      assertEquals(text, 0, parser.getNumberOfSyntaxErrors());
      assertEquals(Token.EOF, parser.getCurrentToken().getType());
    }
  }
}
