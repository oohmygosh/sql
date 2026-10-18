/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.AbstractPredicateTransition;
import org.antlr.v4.runtime.atn.NotSetTransition;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.Transition;
import org.antlr.v4.runtime.atn.WildcardTransition;

/** Recovers finite keyword/operator spellings, including lexer aliases absent from literalName. */
final class AntlrLiteralVocabulary {
  private static final int MAX_CONFIGURATIONS = 16_384;
  private static final int MAX_DEPTH = 64;
  private static final int MAX_ACTIVE_CONFIGURATIONS = 512;
  private static final int MAX_LENGTH = 128;
  private static final int MAX_SPELLINGS = 128;
  private static final int MAX_CHARACTER_ALTERNATIVES = 4;

  private AntlrLiteralVocabulary() {}

  /**
   * Returns only a complete, bounded enumeration. Identifier, numeric, wildcard and other pattern
   * tokens produce no spellings; their values belong to semantic completion providers.
   */
  static List<String> spellings(Lexer lexer, int tokenType) {
    ATN atn = lexer.getATN();
    if (tokenType <= 0 || atn.ruleToTokenType == null) return List.of();
    Enumeration enumeration = new Enumeration(atn);
    for (int rule = 0; rule < atn.ruleToTokenType.length; rule++) {
      if (atn.ruleToTokenType[rule] == tokenType) {
        enumeration.visit(atn.ruleToStartState[rule], null, new StringBuilder());
        if (!enumeration.finite) return List.of();
      }
    }
    return enumeration.spellings.stream()
        .filter(spelling -> !spelling.isEmpty())
        .sorted(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()))
        .toList();
  }

  private static final class Enumeration {
    final ATN atn;
    final Set<String> spellings = new LinkedHashSet<>();
    final Map<Configuration, Integer> path = new HashMap<>();
    boolean finite = true;
    int configurations;

    Enumeration(ATN atn) {
      this.atn = atn;
    }

    void visit(ATNState state, Frame stack, StringBuilder spelling) {
      if (!finite) return;
      if (++configurations > MAX_CONFIGURATIONS
          || spelling.length() > MAX_LENGTH
          || path.size() > MAX_ACTIVE_CONFIGURATIONS) {
        finite = false;
        return;
      }
      Configuration configuration = new Configuration(state.stateNumber, stack);
      Integer previousLength = path.get(configuration);
      if (previousLength != null) {
        // An epsilon-only cycle adds no spellings; a consuming cycle makes the vocabulary infinite.
        if (previousLength != spelling.length()) finite = false;
        return;
      }
      path.put(configuration, spelling.length());
      try {
        if (state instanceof RuleStopState) {
          if (stack == null) {
            spellings.add(spelling.toString());
            if (spellings.size() > MAX_SPELLINGS) finite = false;
          } else {
            visit(atn.states.get(stack.followState), stack.parent, spelling);
          }
          return;
        }
        for (Transition transition : state.getTransitions()) {
          if (!finite) return;
          if (transition instanceof RuleTransition call) {
            int depth = stack == null ? 1 : stack.depth + 1;
            if (depth > MAX_DEPTH) {
              finite = false;
              return;
            }
            visit(call.target, new Frame(call.followState.stateNumber, stack, depth), spelling);
          } else if (transition instanceof AbstractPredicateTransition
              || transition instanceof WildcardTransition
              || transition instanceof NotSetTransition) {
            finite = false;
            return;
          } else if (transition.isEpsilon()) {
            visit(transition.target, stack, spelling);
          } else {
            List<Integer> characters = fixedCharacters(transition);
            if (characters.isEmpty()) {
              finite = false;
              return;
            }
            int length = spelling.length();
            for (int character : characters) {
              spelling.appendCodePoint(character);
              visit(transition.target, stack, spelling);
              spelling.setLength(length);
            }
          }
        }
      } finally {
        path.remove(configuration);
      }
    }
  }

  private static List<Integer> fixedCharacters(Transition transition) {
    if (transition.label() == null || transition.label().size() > MAX_CHARACTER_ALTERNATIVES * 2) {
      return List.of();
    }
    Set<Integer> characters = new LinkedHashSet<>();
    for (int character : transition.label().toArray()) {
      if (character < 0 || !Character.isValidCodePoint(character)) return List.of();
      characters.add(Character.toLowerCase(character));
      if (characters.size() > MAX_CHARACTER_ALTERNATIVES) return List.of();
    }
    return new ArrayList<>(characters);
  }

  private record Frame(int followState, Frame parent, int depth) {}

  private record Configuration(int state, Frame stack) {}
}
