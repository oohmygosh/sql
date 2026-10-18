/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.antlr.v4.runtime.atn.NotSetTransition;
import org.antlr.v4.runtime.atn.PredicateTransition;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.WildcardTransition;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.junit.Test;
import org.opensearch.sql.api.completion.AntlrCompletionSuggester.Analysis;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLLexer;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLParser;

public class AntlrCompletionSuggesterTest {
  @Test
  public void commandAlternativesComeFromTheGrammar() {
    Set<String> tokens = labels(suggest("", Set.of()));
    assertTrue(tokens.containsAll(List.of("where", "stats", "bin", "rex", "timechart", "lookup")));
    assertFalse(tokens.contains("span"));
    assertFalse(tokens.contains("="));
  }

  @Test
  public void preferredFieldRuleOffersMetadataWithoutExpandingKeywordIdentifiers() {
    Analysis result = suggest("BIN ", Set.of(OpenSearchPPLParser.RULE_fieldExpression));
    assertEquals(1, result.rules().size());
    assertEquals(OpenSearchPPLParser.RULE_fieldExpression, result.rules().get(0).ruleIndex());
    assertEquals(
        List.of(
            OpenSearchPPLParser.RULE_commands,
            OpenSearchPPLParser.RULE_binCommand,
            OpenSearchPPLParser.RULE_fieldExpression),
        result.rules().get(0).ruleStack());
    assertTrue(result.tokens().isEmpty());
  }

  @Test
  public void preferredRulesStillConsumeExistingTokensAndReturnToTheirOwnCaller() {
    Set<Integer> preferred = Set.of(OpenSearchPPLParser.RULE_fieldExpression);
    Analysis result = suggest("BIN timestamp ", preferred);
    assertTrue(labels(result).containsAll(List.of("span", "bins", "minspan", "aligntime", "as")));
    assertTrue(result.tokens().stream().anyMatch(token -> token.tokenType() == Token.EOF));
    assertTrue(result.rules().isEmpty());
    assertFalse(labels(result).contains("where"));
    assertEquals(Set.of("="), labels(suggest("BIN timestamp SPAN ", preferred)));
  }

  @Test
  public void repeatedOptionsRetainTheGrammarRuleContext() {
    Analysis result = suggest("BIN timestamp SPAN=1h ", Set.of());
    var bins =
        result.tokens().stream()
            .filter(token -> token.tokenType() == OpenSearchPPLParser.BINS)
            .findFirst()
            .orElseThrow();
    assertEquals(
        List.of(
            OpenSearchPPLParser.RULE_commands,
            OpenSearchPPLParser.RULE_binCommand,
            OpenSearchPPLParser.RULE_binOption),
        bins.ruleStack());
    assertTrue(labels(result).containsAll(List.of("bins", "span", "minspan", "start", "end")));
  }

  @Test
  public void leftRecursiveExpressionsResumeTheOuterLogicalContext() {
    Set<Integer> fields = Set.of(OpenSearchPPLParser.RULE_fieldExpression);
    Analysis finished = suggest("WHERE (status + 1) > 200 ", fields);
    assertTrue(labels(finished).containsAll(List.of("and", "or", "xor")));
    assertTrue(finished.tokens().stream().anyMatch(token -> token.tokenType() == Token.EOF));
    Analysis unfinished = suggest("WHERE (status + 1) > 200 AND ", fields);
    assertTrue(
        unfinished.rules().stream()
            .anyMatch(rule -> rule.ruleIndex() == OpenSearchPPLParser.RULE_fieldExpression));
    assertFalse(unfinished.tokens().stream().anyMatch(token -> token.tokenType() == Token.EOF));
  }

  @Test
  public void nestedFunctionArgumentsCanRequestFields() {
    Analysis result =
        suggest("WHERE ROUND(ABS(latency), ", Set.of(OpenSearchPPLParser.RULE_fieldExpression));
    assertTrue(
        result.rules().stream()
            .anyMatch(rule -> rule.ruleIndex() == OpenSearchPPLParser.RULE_fieldExpression));
    assertFalse(result.tokens().stream().anyMatch(token -> token.tokenType() == Token.EOF));
  }

  @Test
  public void invalidPrefixDoesNotProduceUnrelatedFallbackCandidates() {
    Analysis result = suggest("BIN = ", Set.of());
    assertTrue(result.tokens().isEmpty());
    assertTrue(result.rules().isEmpty());
  }

  @Test
  public void traversalAndNestingLimitsAreExplicit() {
    Parser parser = parser();
    assertTrue(
        AntlrCompletionSuggester.suggest(
                parser, OpenSearchPPLParser.RULE_commands, List.of(), Set.of(), 1, 96)
            .incomplete());
    assertTrue(
        AntlrCompletionSuggester.suggest(
                parser, OpenSearchPPLParser.RULE_commands, List.of(), Set.of(), 1000, 1)
            .incomplete());
    assertTrue(
        AntlrCompletionSuggester.suggest(
                parser,
                OpenSearchPPLParser.RULE_commands,
                java.util.Collections.nCopies(4097, OpenSearchPPLParser.ID),
                Set.of())
            .incomplete());
  }

  @Test
  public void validatesStartRuleAndLimits() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AntlrCompletionSuggester.suggest(parser(), -1, List.of(), Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> AntlrCompletionSuggester.suggest(parser(), 0, List.of(), Set.of(), 0, 1));
  }

  @Test
  public void wildcardAndNegatedSetsRespectTheParserVocabulary() {
    ATN wildcard = singleRule();
    wildcard.ruleToStartState[0].addTransition(new WildcardTransition(wildcard.ruleToStopState[0]));
    assertEquals(Set.of(1, 2, 3), tokenTypes(analyze(wildcard)));

    ATN negated = singleRule();
    negated.ruleToStartState[0].addTransition(
        new NotSetTransition(negated.ruleToStopState[0], IntervalSet.of(2)));
    assertEquals(Set.of(1, 3), tokenTypes(analyze(negated)));
  }

  @Test
  public void epsilonCyclesTerminateAndUnknownSemanticPredicatesMarkIncomplete() {
    ATN atn = singleRule();
    BasicState loop = new BasicState();
    loop.ruleIndex = 0;
    atn.addState(loop);
    atn.ruleToStartState[0].addTransition(new EpsilonTransition(loop));
    loop.addTransition(new EpsilonTransition(loop));
    loop.addTransition(new PredicateTransition(atn.ruleToStopState[0], 0, 0, true));
    Analysis result = analyze(atn);
    assertEquals(Set.of(Token.EOF), tokenTypes(result));
    assertTrue(result.incomplete());
  }

  @Test
  public void preferredStartRuleAndExplicitEofArePreserved() {
    Analysis preferred =
        AntlrCompletionSuggester.suggest(
            parser(),
            OpenSearchPPLParser.RULE_fieldExpression,
            List.of(),
            Set.of(OpenSearchPPLParser.RULE_fieldExpression));
    assertEquals(1, preferred.rules().size());
    assertTrue(preferred.tokens().isEmpty());

    ATN atn = singleRule();
    atn.ruleToStartState[0].addTransition(new AtomTransition(atn.ruleToStopState[0], Token.EOF));
    assertEquals(Set.of(Token.EOF), tokenTypes(analyze(atn)));
  }

  private static Analysis suggest(String text, Set<Integer> preferred) {
    OpenSearchPPLLexer lexer = new OpenSearchPPLLexer(CharStreams.fromString(text));
    lexer.removeErrorListeners();
    List<Integer> tokens =
        lexer.getAllTokens().stream()
            .filter(token -> token.getChannel() == Token.DEFAULT_CHANNEL)
            .map(Token::getType)
            .toList();
    Analysis result =
        AntlrCompletionSuggester.suggest(
            parser(), OpenSearchPPLParser.RULE_commands, tokens, preferred);
    assertFalse("Unexpected traversal limit for " + text, result.incomplete());
    return result;
  }

  private static OpenSearchPPLParser parser() {
    return new OpenSearchPPLParser(
        new CommonTokenStream(new OpenSearchPPLLexer(CharStreams.fromString(""))));
  }

  private static Set<String> labels(Analysis result) {
    return result.tokens().stream()
        .map(token -> OpenSearchPPLParser.VOCABULARY.getLiteralName(token.tokenType()))
        .filter(java.util.Objects::nonNull)
        .map(label -> label.substring(1, label.length() - 1).toLowerCase(java.util.Locale.ROOT))
        .collect(Collectors.toSet());
  }

  private static Set<Integer> tokenTypes(Analysis result) {
    return result.tokens().stream().map(token -> token.tokenType()).collect(Collectors.toSet());
  }

  private static ATN singleRule() {
    ATN atn = new ATN(ATNType.PARSER, 3);
    RuleStartState start = new RuleStartState();
    RuleStopState stop = new RuleStopState();
    start.ruleIndex = 0;
    stop.ruleIndex = 0;
    atn.addState(start);
    atn.addState(stop);
    start.stopState = stop;
    atn.ruleToStartState = new RuleStartState[] {start};
    atn.ruleToStopState = new RuleStopState[] {stop};
    return atn;
  }

  private static Analysis analyze(ATN atn) {
    Parser parser =
        new OpenSearchPPLParser(
            new CommonTokenStream(new OpenSearchPPLLexer(CharStreams.fromString("")))) {
          @Override
          public ATN getATN() {
            return atn;
          }
        };
    return AntlrCompletionSuggester.suggest(parser, 0, List.of(), Set.of());
  }
}
