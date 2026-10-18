/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.NotSetTransition;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.Transition;
import org.antlr.v4.runtime.atn.WildcardTransition;

/** Indexes grammar-owned keyword assignments once, including finite parameter-name subrules. */
final class AntlrGrammarAssignments {
  private final Map<Integer, Set<List<Integer>>> keyPaths;
  private final Map<Assignment, Set<Integer>> valueRules;

  AntlrGrammarAssignments(Parser parser, Lexer lexer) {
    Compiler compiler = new Compiler(parser, lexer);
    compiler.compile();
    keyPaths = immutableSets(compiler.keyPaths);
    valueRules = immutableSets(compiler.valueRules);
  }

  /** Tests the exact key-side rule path, never a same-named field nested in an assignment value. */
  boolean isKey(int tokenType, List<Integer> ruleStack) {
    for (List<Integer> path : keyPaths.getOrDefault(tokenType, Set.of())) {
      int offset = ruleStack.size() - path.size();
      if (offset < 0) continue;
      boolean matches = true;
      for (int i = 0; i < path.size(); i++) {
        if (!path.get(i).equals(ruleStack.get(offset + i))) {
          matches = false;
          break;
        }
      }
      if (matches) return true;
    }
    return false;
  }

  /** Rules entered immediately after '=' for an explicit key in its assignment-owning rule. */
  Set<Integer> valueRules(int tokenType, int ownerRule) {
    return valueRules.getOrDefault(new Assignment(ownerRule, tokenType), Set.of());
  }

  private static <K, V> Map<K, Set<V>> immutableSets(Map<K, Set<V>> values) {
    Map<K, Set<V>> result = new HashMap<>();
    values.forEach((key, value) -> result.put(key, Set.copyOf(value)));
    return Map.copyOf(result);
  }

  private static final class Compiler {
    final Parser parser;
    final Lexer lexer;
    final ATN atn;
    final Set<Integer> equalsTokens = new HashSet<>();
    final Map<Integer, List<String>> spellings = new HashMap<>();
    final Map<Integer, List<AntlrCompletionSuggester.TokenCandidate>> singleTokenRules =
        new HashMap<>();
    final Map<Integer, List<ATNState>> equalsSuccessors = new HashMap<>();
    final Map<Integer, Set<Integer>> firstValueRules = new HashMap<>();
    final Map<Integer, Set<List<Integer>>> keyPaths = new HashMap<>();
    final Map<Assignment, Set<Integer>> valueRules = new HashMap<>();

    Compiler(Parser parser, Lexer lexer) {
      this.parser = parser;
      this.lexer = lexer;
      this.atn = parser.getATN();
      for (int token = Token.MIN_USER_TOKEN_TYPE; token <= atn.maxTokenType; token++) {
        if (spellings(token).contains("=")) equalsTokens.add(token);
      }
    }

    void compile() {
      if (equalsTokens.isEmpty()) return;
      for (ATNState state : atn.states) {
        if (state == null || state instanceof RuleStopState) continue;
        for (Transition transition : state.getTransitions()) {
          if (transition instanceof RuleTransition call) {
            List<ATNState> values = valuesAfterEquals(call.followState);
            if (values.isEmpty()) continue;
            for (var keyword :
                singleTokenRules.computeIfAbsent(call.ruleIndex, this::singleTokens)) {
              List<Integer> path = new ArrayList<>();
              path.add(state.ruleIndex);
              path.addAll(keyword.ruleStack());
              add(state.ruleIndex, keyword.tokenType(), List.copyOf(path), values);
            }
          } else if (!transition.isEpsilon()
              && !(transition instanceof NotSetTransition)
              && !(transition instanceof WildcardTransition)
              && transition.label() != null) {
            List<ATNState> values = valuesAfterEquals(transition.target);
            if (values.isEmpty()) continue;
            for (int token : transition.label().toArray()) {
              if (!spellings(token).isEmpty()) {
                add(state.ruleIndex, token, List.of(state.ruleIndex), values);
              }
            }
          }
        }
      }
    }

    void add(int owner, int token, List<Integer> path, List<ATNState> values) {
      keyPaths.computeIfAbsent(token, ignored -> new LinkedHashSet<>()).add(path);
      Set<Integer> rules =
          valueRules.computeIfAbsent(
              new Assignment(owner, token), ignored -> new LinkedHashSet<>());
      for (ATNState value : values) rules.addAll(firstValueRules(value));
    }

    List<String> spellings(int token) {
      return spellings.computeIfAbsent(
          token, type -> AntlrLiteralVocabulary.spellings(lexer, type));
    }

    /**
     * Certifies that every alternative consumes exactly one token with a fixed lexical spelling.
     */
    List<AntlrCompletionSuggester.TokenCandidate> singleTokens(int rule) {
      var first = AntlrCompletionSuggester.suggest(parser, rule, List.of(), Set.of());
      if (first.incomplete() || first.tokens().isEmpty()) return List.of();
      Set<Integer> types = new LinkedHashSet<>();
      for (var candidate : first.tokens()) {
        if (candidate.tokenType() == Token.EOF || spellings(candidate.tokenType()).isEmpty()) {
          return List.of();
        }
        types.add(candidate.tokenType());
      }
      for (int type : types) {
        var afterToken = AntlrCompletionSuggester.suggest(parser, rule, List.of(type), Set.of());
        if (afterToken.incomplete()
            || afterToken.tokens().isEmpty()
            || afterToken.tokens().stream().anyMatch(token -> token.tokenType() != Token.EOF)) {
          return List.of();
        }
      }
      return first.tokens();
    }

    List<ATNState> valuesAfterEquals(ATNState start) {
      return equalsSuccessors.computeIfAbsent(
          start.stateNumber,
          ignored -> {
            List<ATNState> values = new ArrayList<>();
            for (ATNState state : localEpsilonClosure(start)) {
              for (Transition edge : state.getTransitions()) {
                if (edge.isEpsilon()
                    || edge instanceof NotSetTransition
                    || edge instanceof WildcardTransition
                    || edge.label() == null) continue;
                if (Arrays.stream(edge.label().toArray()).anyMatch(equalsTokens::contains)) {
                  values.add(edge.target);
                }
              }
            }
            return List.copyOf(values);
          });
    }

    Set<Integer> firstValueRules(ATNState start) {
      return firstValueRules.computeIfAbsent(
          start.stateNumber,
          ignored -> {
            Set<Integer> result = new LinkedHashSet<>();
            for (ATNState state : localEpsilonClosure(start)) {
              for (Transition edge : state.getTransitions()) {
                if (edge instanceof RuleTransition call) result.add(call.ruleIndex);
              }
            }
            return Set.copyOf(result);
          });
    }

    /** Walks within the owner; calls and returns are boundaries, not epsilon shortcuts. */
    List<ATNState> localEpsilonClosure(ATNState start) {
      List<ATNState> result = new ArrayList<>();
      Set<Integer> visited = new HashSet<>();
      ArrayDeque<ATNState> pending = new ArrayDeque<>();
      pending.add(start);
      while (!pending.isEmpty()) {
        ATNState state = pending.removeFirst();
        if (state.ruleIndex != start.ruleIndex
            || state instanceof RuleStopState
            || !visited.add(state.stateNumber)) continue;
        result.add(state);
        for (Transition edge : state.getTransitions()) {
          if (edge.isEpsilon() && !(edge instanceof RuleTransition)) pending.add(edge.target);
        }
      }
      return result;
    }
  }

  private record Assignment(int ownerRule, int tokenType) {}
}
