/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.junit.Test;
import org.opensearch.sql.api.completion.CompletionMetadata.Field;
import org.opensearch.sql.api.completion.CompletionMetadata.Relation;
import org.opensearch.sql.api.completion.CompletionMetadata.RelationRef;
import org.opensearch.sql.api.completion.CompletionResponse.Item;
import org.opensearch.sql.common.antlr.CaseInsensitiveCharStream;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLLexer;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLParser;

/** Regression journeys apply the exact edit contract, then parse the resulting complete query. */
public class PplFilterRegressionTest {
  private final PplCompletionProvider provider = new PplCompletionProvider();
  private final CompletionMetadata metadata =
      new CompletionMetadata() {
        @Override
        public String resolveSource(String source) {
          return source;
        }

        @Override
        public List<Relation> tables(
            String source, List<String> namespace, String prefix, int limit) {
          return List.of("orders", "customers").stream()
              .filter(name -> name.startsWith(prefix))
              .map(name -> new Relation(namespace, name, "table"))
              .toList();
        }

        @Override
        public List<Field> fields(String source, RelationRef relation) {
          return switch (relation.name()) {
            case "orders" ->
                List.of(
                    new Field("amount", "double"),
                    new Field("amount2", "double"),
                    new Field("timestamp", "timestamp"),
                    new Field("active", "boolean"),
                    new Field("message", "string"),
                    new Field("customer_id", "string"),
                    new Field("device.name", "string", List.of("device", "name")));
            case "customers" ->
                List.of(
                    new Field("customer_key", "string"),
                    new Field("customer_name", "string"),
                    new Field("risk", "double"));
            default -> throw new AssertionError("Unexpected table " + relation);
          };
        }
      };

  @Test
  public void nestedExtractKeywordDoesNotAcquireFunctionParentheses() {
    Editor editor = editor("source=orders | eval result=extract(day ");
    editor.accept("from");
    assertFalse(editor.text, editor.text.endsWith("from("));
    editor.accept("timestamp").accept(")").require("|").assertParses();
  }

  @Test
  public void nestedCastKeywordsAndTypesRemainKeywords() {
    Editor editor = editor("source=orders | eval result=round(cast(amount ");
    editor.accept("as").accept("int");
    assertFalse(editor.text, editor.text.contains("as("));
    assertFalse(editor.text, editor.text.contains("int("));
    editor.accept(")").accept(")").require("|").assertParses();
  }

  @Test
  public void caseElseBranchCanContinueThroughItsValueAndClosingParenthesis() {
    Editor editor = editor("source=orders | eval result=case(active, amount ");
    editor.accept("else").accept("amount2").accept(")").require("|").assertParses();
    assertFalse(editor.text, editor.text.contains("else("));
  }

  @Test
  public void positionKeywordCanBeAcceptedAfterAStringArgument() {
    editor("source=orders | eval result=position('a' ")
        .accept("in")
        .accept("message")
        .accept(")")
        .require("|")
        .assertParses();
  }

  @Test
  public void aggregateEvalPredicateContinuesIntoComparisonAndOutputScope() {
    Editor editor = editor("source=orders | stats count(eval(");
    editor.accept("amount").accept(">").type("1").accept(")").accept(")");
    editor.accept("as").type("total").accept("|").accept("fields").accept("total");
    editor.assertParses();
  }

  @Test
  public void scalarArgumentCanBeAnExpressionRatherThanOnlyOneAtom() {
    editor("source=orders | eval result=round(")
        .accept("amount")
        .accept("+")
        .accept("amount2")
        .accept(")")
        .require("|")
        .assertParses();
  }

  @Test
  public void nestedScalarCallDoesNotInheritTheOuterAggregateArity() {
    editor("source=orders | stats sum(avg(")
        .accept("amount")
        .accept(",")
        .accept("amount2")
        .accept(")")
        .accept(")")
        .require("|")
        .assertParses();
  }

  @Test
  public void fieldPredicateRetainsInBetweenAndNullBranches() {
    editor("source=orders | where ")
        .accept("amount")
        .accept("in")
        .accept("(")
        .type("1")
        .accept(",")
        .type("2")
        .accept(")")
        .require("|")
        .assertParses();
    editor("source=orders | where ")
        .accept("amount")
        .accept("between")
        .type("1 ")
        .accept("and")
        .type("2")
        .require("|")
        .assertParses();
    editor("source=orders | where ")
        .accept("amount")
        .accept("is")
        .accept("null")
        .require("|")
        .assertParses();
  }

  @Test
  public void comparisonRhsCanSelectAFieldOrFunction() {
    editor("source=orders | where ")
        .accept("amount")
        .accept(">")
        .type("ab")
        .require("abs")
        .accept("abs")
        .accept("amount2")
        .accept(")")
        .require("|")
        .assertParses();
  }

  @Test
  public void aCompleteComparisonCanStillExtendItsRhsExpression() {
    editor("source=orders | where amount > 1")
        .type(" + ")
        .accept("amount2")
        .require("and", "|")
        .assertParses();
  }

  @Test
  public void repeatedNamedParametersAreScopedToTheirFunctionInvocation() {
    Editor sameCall = editor("source=orders | where match(message,'x',fuzziness=1, ");
    assertFalse(sameCall.text, sameCall.labels().contains("fuzziness"));
    for (String prefix :
        List.of(
            "where match(message,'x',fuzziness=1) or match(message,'y', ",
            "eval a=match(message,'x',fuzziness=1), b=match(message,'y', ")) {
      editor("source=orders | " + prefix)
          .accept("fuzziness")
          .accept("=")
          .type("1")
          .accept(")")
          .require("|")
          .assertParses();
    }
  }

  @Test
  public void successiveFieldEditsKeepLexicalBoundaries() {
    for (String prefix : List.of("fields ", "dedup ", "stats count() by ")) {
      Editor editor = editor("source=orders | " + prefix);
      editor.accept("amount").type(" am").accept("amount2").require("|").assertParses();
      assertFalse(editor.text, editor.text.contains("`amount``amount2`"));
      assertTrue(editor.text, editor.text.contains("`amount` `amount2`"));
    }
    editor("source=orders | fields ")
        .accept("amount")
        .type(" am")
        .accept("amount2")
        .accept("|")
        .accept("where")
        .require("amount", "amount2");
    editor("source=orders | rename amount as renamed")
        .type(" am")
        .accept("amount2")
        .accept("as")
        .type("second")
        .require("|")
        .assertParses();
  }

  @Test
  public void middleFieldReplacementPreservesArithmeticSuffixes() {
    for (String suffix : List.of("+1", "* 1", "/amount2", "%2", "-1")) {
      // Unquoted PPL IDs may contain '*' and '-'; quote the identifier to make those operators.
      String identifier =
          suffix.startsWith("*") || suffix.startsWith("-") ? "`am¦ount`" : "am¦ount";
      Editor editor = editor("source=orders as o | eval result=o." + identifier + suffix);
      editor.accept("o.amount");
      assertEquals("source=orders as o | eval result=`o`.`amount`" + suffix, editor.text);
      assertEquals(editor.text.length() - suffix.length(), editor.caret);
      editor.assertParses();
    }
    Editor nested = editor("source=orders | eval result=device.na¦me+'suffix'");
    nested.accept("device.name");
    assertEquals("source=orders | eval result=`device`.`name`+'suffix'", nested.text);
    nested.assertParses();
  }

  @Test
  public void middleInsertionRetainsOperatorsAndSortDirection() {
    editor("source=orders | sort amount ¦,active").accept("desc").require("|").assertParses();
    editor("source=orders | where `amount`¦ and active").accept("=").type("1").assertParses();
    editor("source=orders | eval result=round(`amount`¦)").accept("+").type("1").assertParses();
  }

  @Test
  public void patternTokenValueAcceptanceAdvancesBeyondTheValue() {
    Editor editor = editor("source=orders | bin amount span=");
    editor.accept("log10").require("as", "|");
    assertFalse(editor.text, editor.labels().contains("log10"));
    editor.accept("|").accept("fields").accept("amount").assertParses();
  }

  @Test
  public void signedSortCountIsNotTheFieldDirectionPrefix() {
    Editor editor = editor("source=orders | sort +10 amount ");
    editor.accept("desc").accept(",").accept("+").accept("amount2");
    assertFalse(editor.text, editor.labels().contains("asc"));
    assertFalse(editor.text, editor.labels().contains("desc"));
    editor.require("|").assertParses();
  }

  @Test
  public void selectedSourceCanContinueIntoAliasesAndMultipleTables() {
    editor("source=")
        .accept("orders")
        .type("a")
        .accept("as")
        .type("o")
        .accept("|")
        .accept("fields")
        .type("o.")
        .accept("o.amount")
        .assertParses();
    editor("source=").accept("orders").type(",").accept("customers").require("|").assertParses();
  }

  @Test
  public void outputNameCanContinueIntoAQualifiedName() {
    editor("source=orders | rename amount as renamed")
        .type(".")
        .type("value")
        .accept("|")
        .accept("fields")
        .accept("renamed.value")
        .assertParses();
    editor("source=orders | eval result")
        .type(".")
        .type("value")
        .accept("=")
        .type("1")
        .require("|")
        .assertParses();
  }

  @Test
  public void exactFieldPrefixDoesNotHideTheLongerField() {
    editor("source=orders | where amount")
        .accept("amount2")
        .accept("=")
        .type("1")
        .require("|")
        .assertParses();
  }

  private Editor editor(String text) {
    return new Editor(text);
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
          new CompletionRequest("ppl", "test", text, caret, ++version, "accept"), metadata);
    }

    List<String> labels() {
      return complete().items().stream().map(Item::label).toList();
    }

    Editor require(String... labels) {
      List<String> actual = labels();
      for (String label : labels)
        assertTrue(
            text + " at " + caret + " lacks " + label + ": " + actual, actual.contains(label));
      return this;
    }

    Editor accept(String label) {
      CompletionResponse response = complete();
      Item selected =
          response.items().stream()
              .filter(item -> item.label().equals(label))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          text
                              + " at "
                              + caret
                              + " lacks "
                              + label
                              + ": "
                              + response.items().stream().map(Item::label).toList()
                              + " "
                              + response.message()));
      var edit = selected.textEdit();
      assertTrue(edit.from() >= 0 && edit.from() <= caret && edit.to() >= caret);
      assertTrue(edit.to() <= text.length());
      text = text.substring(0, edit.from()) + edit.newText() + text.substring(edit.to());
      caret =
          edit.from()
              + (selected.caretOffset() == null ? edit.newText().length() : selected.caretOffset());
      return this;
    }

    Editor type(String value) {
      text = text.substring(0, caret) + value + text.substring(caret);
      caret += value.length();
      return this;
    }

    void assertParses() {
      List<String> errors = new ArrayList<>();
      BaseErrorListener listener =
          new BaseErrorListener() {
            @Override
            public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String msg,
                RecognitionException e) {
              errors.add(line + ":" + charPositionInLine + " " + msg);
            }
          };
      OpenSearchPPLLexer lexer = new OpenSearchPPLLexer(new CaseInsensitiveCharStream(text));
      lexer.removeErrorListeners();
      lexer.addErrorListener(listener);
      CommonTokenStream tokens = new CommonTokenStream(lexer);
      OpenSearchPPLParser parser = new OpenSearchPPLParser(tokens);
      parser.removeErrorListeners();
      parser.addErrorListener(listener);
      parser.root();
      assertTrue(text + " : " + errors, errors.isEmpty());
      assertEquals(text, Token.EOF, tokens.LA(1));
    }
  }
}
