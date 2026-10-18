/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.junit.Test;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLLexer;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLParser;

public class AntlrGrammarAssignmentsTest {
  private static final OpenSearchPPLLexer LEXER =
      new OpenSearchPPLLexer(CharStreams.fromString(""));
  private static final OpenSearchPPLParser PARSER =
      new OpenSearchPPLParser(new CommonTokenStream(LEXER));
  private static final AntlrGrammarAssignments ASSIGNMENTS =
      new AntlrGrammarAssignments(PARSER, LEXER);

  @Test
  public void directKeywordsRetainTheirAssignmentValueRule() {
    assertTrue(
        ASSIGNMENTS.isKey(
            OpenSearchPPLLexer.SPAN,
            List.of(
                OpenSearchPPLParser.RULE_commands,
                OpenSearchPPLParser.RULE_binCommand,
                OpenSearchPPLParser.RULE_binOption)));
    assertEquals(
        Set.of(OpenSearchPPLParser.RULE_binSpanValue),
        ASSIGNMENTS.valueRules(OpenSearchPPLLexer.SPAN, OpenSearchPPLParser.RULE_binOption));
    assertEquals(
        Set.of(OpenSearchPPLParser.RULE_booleanLiteral),
        ASSIGNMENTS.valueRules(OpenSearchPPLLexer.ALLNUM, OpenSearchPPLParser.RULE_allnumArg));
  }

  @Test
  public void finiteSubruleKeywordsAreRecognizedInTheirCaller() {
    assertTrue(
        ASSIGNMENTS.isKey(
            OpenSearchPPLLexer.ANALYZER,
            List.of(
                OpenSearchPPLParser.RULE_relevanceArg, OpenSearchPPLParser.RULE_relevanceArgName)));
    assertEquals(
        Set.of(OpenSearchPPLParser.RULE_relevanceArgValue),
        ASSIGNMENTS.valueRules(OpenSearchPPLLexer.ANALYZER, OpenSearchPPLParser.RULE_relevanceArg));
    assertEquals(
        Set.of(OpenSearchPPLParser.RULE_relevanceArgValue),
        ASSIGNMENTS.valueRules(OpenSearchPPLLexer.BOOST, OpenSearchPPLParser.RULE_relevanceArg));
  }

  @Test
  public void everyRelevanceParameterIsRecognizedWithoutASeparateNameList() {
    var candidates =
        AntlrCompletionSuggester.suggest(
            PARSER, OpenSearchPPLParser.RULE_relevanceArg, List.of(), Set.of());
    assertFalse(candidates.incomplete());
    assertFalse(candidates.tokens().isEmpty());
    for (var candidate : candidates.tokens()) {
      assertTrue(
          PARSER.getVocabulary().getSymbolicName(candidate.tokenType()),
          ASSIGNMENTS.isKey(candidate.tokenType(), candidate.ruleStack()));
    }
  }

  @Test
  public void explicitKeyTypesExcludeGenericIdentifierFallbacks() {
    assertEquals(
        Set.of(OpenSearchPPLParser.RULE_stringLiteral),
        ASSIGNMENTS.valueRules(OpenSearchPPLLexer.TIMEOUT, OpenSearchPPLParser.RULE_restArgument));
    assertEquals(
        Set.of(OpenSearchPPLParser.RULE_integerLiteral),
        ASSIGNMENTS.valueRules(OpenSearchPPLLexer.COUNT, OpenSearchPPLParser.RULE_restArgument));
    assertTrue(
        ASSIGNMENTS
            .valueRules(OpenSearchPPLLexer.ID, OpenSearchPPLParser.RULE_restArgument)
            .isEmpty());
  }

  @Test
  public void sameKeywordInsideAValueIsNotMistakenForAParameter() {
    assertFalse(
        ASSIGNMENTS.isKey(
            OpenSearchPPLLexer.ANALYZER,
            List.of(
                OpenSearchPPLParser.RULE_relevanceArg,
                OpenSearchPPLParser.RULE_relevanceArgValue,
                OpenSearchPPLParser.RULE_qualifiedName,
                OpenSearchPPLParser.RULE_ident,
                OpenSearchPPLParser.RULE_keywordsCanBeId,
                OpenSearchPPLParser.RULE_searchableKeyWord,
                OpenSearchPPLParser.RULE_relevanceArgName)));
    assertFalse(
        ASSIGNMENTS.isKey(
            OpenSearchPPLLexer.TIMEOUT,
            List.of(
                OpenSearchPPLParser.RULE_restArgument, OpenSearchPPLParser.RULE_stringLiteral)));
  }

  @Test
  public void arbitraryFieldsAndExpressionsCannotBecomeParameterCatalogs() {
    assertFalse(
        ASSIGNMENTS.isKey(
            OpenSearchPPLLexer.SPAN,
            List.of(
                OpenSearchPPLParser.RULE_evalClause,
                OpenSearchPPLParser.RULE_fieldExpression,
                OpenSearchPPLParser.RULE_qualifiedName,
                OpenSearchPPLParser.RULE_ident,
                OpenSearchPPLParser.RULE_keywordsCanBeId,
                OpenSearchPPLParser.RULE_searchableKeyWord)));
    assertTrue(
        ASSIGNMENTS
            .valueRules(OpenSearchPPLLexer.SPAN, OpenSearchPPLParser.RULE_evalClause)
            .isEmpty());
    assertTrue(
        ASSIGNMENTS
            .valueRules(OpenSearchPPLLexer.ID, OpenSearchPPLParser.RULE_evalClause)
            .isEmpty());
  }

  @Test
  public void assignmentsWithInlineLiteralValuesRemainKeys() {
    assertTrue(
        ASSIGNMENTS.isKey(
            OpenSearchPPLLexer.FORMAT, List.of(OpenSearchPPLParser.RULE_makeresultsArg)));
    assertTrue(
        ASSIGNMENTS
            .valueRules(OpenSearchPPLLexer.FORMAT, OpenSearchPPLParser.RULE_makeresultsArg)
            .isEmpty());
  }

  @Test
  public void unknownKeysAndRulePathsReturnEmptyResults() {
    assertFalse(ASSIGNMENTS.isKey(OpenSearchPPLLexer.SPAN, List.of()));
    assertFalse(ASSIGNMENTS.isKey(-1, List.of(OpenSearchPPLParser.RULE_binOption)));
    assertFalse(
        ASSIGNMENTS.isKey(OpenSearchPPLLexer.SPAN, List.of(OpenSearchPPLParser.RULE_binCommand)));
    assertTrue(ASSIGNMENTS.valueRules(OpenSearchPPLLexer.SPAN, -1).isEmpty());
  }

  @Test
  public void finiteFirstTokensAloneDoNotCertifyAMultiTokenNameRule() {
    ATN atn = assignmentGrammar();
    ATNState second = state(atn, 1);
    atn.ruleToStartState[1].addTransition(new AtomTransition(second, OpenSearchPPLLexer.BIN));
    second.addTransition(new AtomTransition(atn.ruleToStopState[1], OpenSearchPPLLexer.HEAD));
    AntlrGrammarAssignments assignments = assignments(atn);
    assertFalse(assignments.isKey(OpenSearchPPLLexer.BIN, List.of(0, 1)));
    assertTrue(assignments.valueRules(OpenSearchPPLLexer.BIN, 0).isEmpty());
  }

  @Test
  public void nullableNameRulesAreNotFixedParameterCatalogs() {
    ATN atn = assignmentGrammar();
    ATNState literal = state(atn, 1);
    atn.ruleToStartState[1].addTransition(new EpsilonTransition(literal));
    atn.ruleToStartState[1].addTransition(new EpsilonTransition(atn.ruleToStopState[1]));
    literal.addTransition(new AtomTransition(atn.ruleToStopState[1], OpenSearchPPLLexer.BIN));
    assertFalse(assignments(atn).isKey(OpenSearchPPLLexer.BIN, List.of(0, 1)));
  }

  private static AntlrGrammarAssignments assignments(ATN atn) {
    var parser =
        new OpenSearchPPLParser(new CommonTokenStream(LEXER)) {
          @Override
          public ATN getATN() {
            return atn;
          }
        };
    return new AntlrGrammarAssignments(parser, LEXER);
  }

  /** owner: name '=' value; name is filled by each certification test. */
  private static ATN assignmentGrammar() {
    ATN atn = new ATN(ATNType.PARSER, OpenSearchPPLLexer.VOCABULARY.getMaxTokenType());
    atn.ruleToStartState = new RuleStartState[3];
    atn.ruleToStopState = new RuleStopState[3];
    for (int rule = 0; rule < 3; rule++) {
      RuleStartState start = new RuleStartState();
      RuleStopState stop = new RuleStopState();
      start.ruleIndex = rule;
      stop.ruleIndex = rule;
      start.stopState = stop;
      atn.addState(start);
      atn.addState(stop);
      atn.ruleToStartState[rule] = start;
      atn.ruleToStopState[rule] = stop;
    }
    ATNState equals = state(atn, 0);
    ATNState value = state(atn, 0);
    atn.ruleToStartState[0].addTransition(
        new RuleTransition(atn.ruleToStartState[1], 1, 0, equals));
    equals.addTransition(new AtomTransition(value, OpenSearchPPLLexer.EQUAL));
    value.addTransition(new RuleTransition(atn.ruleToStartState[2], 2, 0, atn.ruleToStopState[0]));
    atn.ruleToStartState[2].addTransition(
        new AtomTransition(atn.ruleToStopState[2], OpenSearchPPLLexer.ID));
    return atn;
  }

  private static ATNState state(ATN atn, int rule) {
    BasicState state = new BasicState();
    state.ruleIndex = rule;
    atn.addState(state);
    return state;
  }
}
