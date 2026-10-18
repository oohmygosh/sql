/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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

/** Source selection ends at a pipeline boundary; grammar alternatives require explicit intent. */
public class PplSourceBoundaryTest {
  private final PplCompletionProvider provider = new PplCompletionProvider();
  private final List<RelationRef> fieldLookups = new ArrayList<>();
  private boolean allowFields;
  private final CompletionMetadata metadata =
      new CompletionMetadata() {
        @Override
        public String resolveSource(String source) {
          return source;
        }

        @Override
        public List<Relation> tables(
            String source, List<String> namespace, String prefix, int limit) {
          return List.of("logevent-*", "orders", "customers").stream()
              .filter(name -> name.startsWith(prefix))
              .map(name -> new Relation(namespace, name, "table"))
              .toList();
        }

        @Override
        public List<Field> fields(String source, RelationRef relation) {
          assertTrue("No field lookup belongs at this cursor: " + relation, allowFields);
          fieldLookups.add(relation);
          return switch (relation.name()) {
            case "orders" ->
                List.of(new Field("amount", "double"), new Field("timestamp", "timestamp"));
            case "customers" ->
                List.of(new Field("customer_name", "string"), new Field("risk", "double"));
            case "logevent-*" -> List.of(new Field("message", "string"));
            default -> throw new AssertionError("Unexpected relation " + relation);
          };
        }
      };

  @Test
  public void exactReportedRequestReturnsOnlyPipeForEveryTriggerWithoutReadingFields() {
    for (String trigger : List.of("input", "accept", "manual")) {
      CompletionResponse response =
          provider.complete(
              new CompletionRequest("ppl", "logs", "source=`logevent-*` ", 20, 78, trigger),
              metadata);
      assertEquals(78, response.documentVersion());
      assertEquals(List.of("|"), labels(response));
      assertFalse(response.incomplete());
      assertEquals("", response.message());
      Item pipe = response.items().get(0);
      assertEquals("operator", pipe.kind());
      assertEquals(20, pipe.textEdit().from());
      assertEquals(20, pipe.textEdit().to());
      assertEquals("| ", pipe.textEdit().newText());
    }
    assertTrue(fieldLookups.isEmpty());
  }

  @Test
  public void completedSourcesHaveTheSameBoundaryAcrossEntrypointsAndWhitespace() {
    for (String entry : List.of("source", "index", "search source", "search index")) {
      for (String value : List.of("orders", "`orders`", "`logevent-*`", "`db`.`orders`")) {
        for (String whitespace : List.of("", " ", "\n")) {
          Editor editor = new Editor(entry + "=" + value + whitespace);
          editor.only("|");
          assertFalse(editor.text, editor.complete().incomplete());
        }
      }
    }
    assertTrue(fieldLookups.isEmpty());
  }

  @Test
  public void tableToCommandToFieldJourneyLoadsSchemaOnlyAtTheFieldPosition() {
    Editor editor = new Editor("");
    editor.accept("source").accept("=").accept("orders").only("|");
    editor.accept("|").require("where", "fields", "stats");
    assertTrue(fieldLookups.isEmpty());
    editor.accept("where");
    allowFields = true;
    editor.require("amount", "timestamp").accept("amount").accept(">").type("10");
    editor.accept("|").accept("head").assertParses();
    assertFalse(fieldLookups.isEmpty());
    assertTrue(
        fieldLookups.stream().allMatch(ref -> ref.equals(new RelationRef(List.of(), "orders"))));
  }

  @Test
  public void nestedSourceBoundaryOffersOnlyItsPipeAndClosingBracket() {
    for (String prefix :
        List.of(
            "source=orders | append [", "source=orders | join [", "multisearch [", "source=[")) {
      for (String suffix : List.of("", " ")) {
        new Editor(prefix + "source=`customers`" + suffix).only("|", "]");
      }
    }
    assertTrue(fieldLookups.isEmpty());
  }

  @Test
  public void nestedContinuationLoadsOnlyTheInnerRelationAfterSelectingAFieldCommand() {
    Editor editor = new Editor("source=orders | append [source=`customers` ");
    editor.only("|", "]").accept("|").accept("fields");
    assertTrue(fieldLookups.isEmpty());
    allowFields = true;
    editor.accept("customer_name").accept("]").accept("|").accept("head").assertParses();
    assertFalse(fieldLookups.isEmpty());
    assertTrue(
        fieldLookups.stream().allMatch(ref -> ref.equals(new RelationRef(List.of(), "customers"))));
  }

  @Test
  public void aliasBranchRequiresAnExplicitPrefixAndPreservesQualifiedFields() {
    Editor editor = new Editor("source=");
    editor.accept("orders").only("|");
    allowFields = true;
    editor.type("a").accept("as").type("o ");
    allowFields = false;
    editor.only("|").accept("|").accept("fields");
    allowFields = true;
    editor.type("o.").accept("o.amount").assertParses();
  }

  @Test
  public void commaBranchRequiresExplicitInputThenOffersRealTables() {
    Editor editor = new Editor("source=");
    editor.accept("orders").only("|").type(",").accept("customers");
    editor.only("|").accept("|").accept("head").assertParses();
    assertTrue(fieldLookups.isEmpty());
  }

  @Test
  public void explicitlyTypedTimeModifierStillCompletesItsGrammar() {
    Editor editor = new Editor("source=orders ");
    editor.only("|");
    allowFields = true;
    editor.type("ear").accept("earliest").accept("=").accept("now");
    editor.accept("|").accept("head").assertParses();
  }

  @Test
  public void sourceBoundaryDoesNotFilterNestedSpanOrExpressionArguments() {
    allowFields = true;
    Editor span = new Editor("source=orders | stats count() as total by ");
    span.require("span", "timestamp").accept("span").accept("timestamp");
    span.accept(",").accept("1h").accept(")").accept("as").type("hour ");
    span.accept("|").accept("fields").accept("hour").assertParses();

    Editor expression = new Editor("source=orders | where ");
    expression.require("amount").type("ro").accept("round").type("ab").accept("abs");
    expression.accept("amount").accept(")").accept(")").accept(">").type("10");
    expression.accept("|").accept("head").assertParses();
  }

  @Test
  public void boundaryInsertionPreservesSuffixAndDoesNotDuplicateAnExistingPipe() {
    Editor editor = new Editor("source=`logevent-*` ¦head 1");
    editor.only("|").accept("|");
    assertEquals("source=`logevent-*` | head 1", editor.text);
    editor.assertParses();
    new Editor("source=`logevent-*` ¦| head 1").only();
    assertTrue(fieldLookups.isEmpty());
  }

  @Test
  public void unfinishedSourceNameStillCompletesTheTableInsteadOfTheBoundary() {
    new Editor("source=ord").only("orders");
    new Editor("source=`ord").only("orders");
    assertTrue(fieldLookups.isEmpty());
  }

  private static List<String> labels(CompletionResponse response) {
    return response.items().stream().map(Item::label).toList();
  }

  private final class Editor {
    String text;
    int caret;
    long version;

    Editor(String markedText) {
      int marker = markedText.indexOf('¦');
      text = markedText.replace("¦", "");
      caret = marker < 0 ? text.length() : marker;
    }

    CompletionResponse complete() {
      return provider.complete(
          new CompletionRequest("ppl", "logs", text, caret, ++version, "accept"), metadata);
    }

    Editor only(String... expected) {
      CompletionResponse response = complete();
      assertEquals(
          text + " at " + caret + ": " + response.message(),
          Set.of(expected),
          response.items().stream().map(Item::label).collect(Collectors.toSet()));
      return this;
    }

    Editor require(String... expected) {
      List<String> actual = labels(complete());
      for (String label : expected)
        assertTrue(
            text + " at " + caret + " lacks " + label + ": " + actual, actual.contains(label));
      return this;
    }

    Editor accept(String label) {
      CompletionResponse response = complete();
      Item item =
          response.items().stream()
              .filter(candidate -> candidate.label().equals(label))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          text + " at " + caret + " lacks " + label + ": " + labels(response)));
      var edit = item.textEdit();
      assertTrue(edit.from() >= 0 && edit.from() <= caret && edit.to() >= caret);
      assertTrue(edit.to() <= text.length());
      text = text.substring(0, edit.from()) + edit.newText() + text.substring(edit.to());
      caret =
          edit.from() + (item.caretOffset() == null ? edit.newText().length() : item.caretOffset());
      return this;
    }

    Editor type(String value) {
      text = text.substring(0, caret) + value + text.substring(caret);
      caret += value.length();
      return this;
    }

    void assertParses() {
      var lexer = new OpenSearchPPLLexer(new CaseInsensitiveCharStream(text));
      var tokens = new CommonTokenStream(lexer);
      var parser = new OpenSearchPPLParser(tokens);
      parser.removeErrorListeners();
      parser.root();
      assertEquals(text, 0, parser.getNumberOfSyntaxErrors());
      assertEquals(text, Token.EOF, tokens.LA(1));
    }
  }
}
