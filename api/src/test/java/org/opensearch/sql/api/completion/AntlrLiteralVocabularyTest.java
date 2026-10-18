/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Locale;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.SetTransition;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.junit.Test;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLLexer;

public class AntlrLiteralVocabularyTest {
  @Test
  public void aliasesWithoutLiteralNamesComeFromTheLexerGrammar() {
    assertNull(OpenSearchPPLLexer.VOCABULARY.getLiteralName(OpenSearchPPLLexer.REGEXP_MATCH));
    assertEquals(
        List.of("regex_match", "regexp_match"), spellings(OpenSearchPPLLexer.REGEXP_MATCH));
    assertEquals(List.of("!="), spellings(OpenSearchPPLLexer.NOT_EQUAL));
  }

  @Test
  public void caseInsensitiveSetsProduceOneCanonicalSpelling() {
    assertEquals(List.of("source"), spellings(OpenSearchPPLLexer.SOURCE));
    assertEquals(List.of("("), spellings(OpenSearchPPLLexer.LT_PRTHS));
    assertEquals(List.of("="), spellings(OpenSearchPPLLexer.EQUAL));
  }

  @Test
  public void everyFixedLiteralInTheActualLexerRemainsAvailable() {
    for (int token = 1; token <= OpenSearchPPLLexer.VOCABULARY.getMaxTokenType(); token++) {
      String literal = OpenSearchPPLLexer.VOCABULARY.getLiteralName(token);
      if (literal == null || literal.indexOf('\\') >= 0) continue;
      String spelling = literal.substring(1, literal.length() - 1).toLowerCase(Locale.ROOT);
      assertTrue(literal, spellings(token).contains(spelling));
    }
  }

  @Test
  public void patternTokensNeverInventLiteralValues() {
    for (int token :
        List.of(
            OpenSearchPPLLexer.ID,
            OpenSearchPPLLexer.INTEGER_LITERAL,
            OpenSearchPPLLexer.DECIMAL_LITERAL,
            OpenSearchPPLLexer.SPANLENGTH,
            OpenSearchPPLLexer.DECIMAL_SPANLENGTH,
            OpenSearchPPLLexer.EDGE_CLAUSE,
            OpenSearchPPLLexer.LOG_WITH_BASE,
            OpenSearchPPLLexer.PERCENTILE_SHORTCUT,
            OpenSearchPPLLexer.BQUOTA_STRING,
            OpenSearchPPLLexer.ERROR_RECOGNITION)) {
      assertEquals(
          OpenSearchPPLLexer.VOCABULARY.getSymbolicName(token), List.of(), spellings(token));
    }
  }

  @Test
  public void fragmentReuseReturnsToEachCallSiteWithoutBeingTreatedAsACycle() {
    ATN atn = grammar(2);
    ATNState afterFirst = state(atn, 0);
    atn.ruleToStartState[0].addTransition(
        new RuleTransition(atn.ruleToStartState[1], 1, 0, afterFirst));
    afterFirst.addTransition(
        new RuleTransition(atn.ruleToStartState[1], 1, 0, atn.ruleToStopState[0]));
    atn.ruleToStartState[1].addTransition(new AtomTransition(atn.ruleToStopState[1], 'A'));
    assertEquals(List.of("aa"), AntlrLiteralVocabulary.spellings(lexer(atn), 1));
  }

  @Test
  public void consumingCyclesInvalidateTheEntireEnumeration() {
    ATN atn = grammar(1);
    ATNState loop = state(atn, 0);
    ATNState finite = state(atn, 0);
    ATNState repeat = state(atn, 0);
    atn.ruleToStartState[0].addTransition(new EpsilonTransition(finite));
    finite.addTransition(new AtomTransition(atn.ruleToStopState[0], 'b'));
    atn.ruleToStartState[0].addTransition(new EpsilonTransition(loop));
    loop.addTransition(new EpsilonTransition(repeat));
    repeat.addTransition(new AtomTransition(loop, 'a'));
    loop.addTransition(new EpsilonTransition(atn.ruleToStopState[0]));
    assertEquals(List.of(), AntlrLiteralVocabulary.spellings(lexer(atn), 1));
  }

  @Test
  public void epsilonCyclesTerminateWithoutDroppingFiniteSpellings() {
    ATN atn = grammar(1);
    ATNState loop = state(atn, 0);
    ATNState literal = state(atn, 0);
    atn.ruleToStartState[0].addTransition(new EpsilonTransition(loop));
    loop.addTransition(new EpsilonTransition(loop));
    loop.addTransition(new EpsilonTransition(literal));
    literal.addTransition(new AtomTransition(atn.ruleToStopState[0], 'x'));
    assertEquals(List.of("x"), AntlrLiteralVocabulary.spellings(lexer(atn), 1));
  }

  @Test
  public void invalidTokensAndRecursionProduceNoSpellings() {
    assertEquals(List.of(), spellings(-1));
    assertEquals(List.of(), spellings(Integer.MAX_VALUE));
    ATN atn = grammar(1);
    atn.ruleToStartState[0].addTransition(
        new RuleTransition(atn.ruleToStartState[0], 0, 0, atn.ruleToStopState[0]));
    assertEquals(List.of(), AntlrLiteralVocabulary.spellings(lexer(atn), 1));
  }

  @Test
  public void tooManyFiniteCombinationsDoNotReturnAPartialVocabulary() {
    ATN atn = grammar(1);
    ATNState current = atn.ruleToStartState[0];
    for (int i = 0; i < 8; i++) {
      ATNState next = state(atn, 0);
      current.addTransition(new SetTransition(next, IntervalSet.of('a', 'b')));
      current = next;
    }
    current.addTransition(new EpsilonTransition(atn.ruleToStopState[0]));
    assertEquals(List.of(), AntlrLiteralVocabulary.spellings(lexer(atn), 1));
  }

  @Test
  public void overlongFixedSpellingsAreBounded() {
    ATN atn = grammar(1);
    ATNState current = atn.ruleToStartState[0];
    for (int i = 0; i < 129; i++) {
      ATNState next = state(atn, 0);
      current.addTransition(new AtomTransition(next, 'x'));
      current = next;
    }
    current.addTransition(new EpsilonTransition(atn.ruleToStopState[0]));
    assertEquals(List.of(), AntlrLiteralVocabulary.spellings(lexer(atn), 1));
  }

  private static List<String> spellings(int tokenType) {
    return AntlrLiteralVocabulary.spellings(
        new OpenSearchPPLLexer(CharStreams.fromString("")), tokenType);
  }

  private static ATN grammar(int ruleCount) {
    ATN atn = new ATN(ATNType.LEXER, 1);
    atn.ruleToStartState = new RuleStartState[ruleCount];
    atn.ruleToStopState = new RuleStopState[ruleCount];
    atn.ruleToTokenType = new int[ruleCount];
    atn.ruleToTokenType[0] = 1;
    for (int rule = 0; rule < ruleCount; rule++) {
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
    return atn;
  }

  private static ATNState state(ATN atn, int rule) {
    BasicState state = new BasicState();
    state.ruleIndex = rule;
    atn.addState(state);
    return state;
  }

  private static Lexer lexer(ATN atn) {
    return new OpenSearchPPLLexer(CharStreams.fromString("")) {
      @Override
      public ATN getATN() {
        return atn;
      }
    };
  }
}
