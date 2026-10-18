/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.NotSetTransition;
import org.antlr.v4.runtime.atn.PrecedencePredicateTransition;
import org.antlr.v4.runtime.atn.PredicateTransition;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.Transition;
import org.antlr.v4.runtime.atn.WildcardTransition;
import org.antlr.v4.runtime.misc.IntervalSet;

/**
 * Finds continuations in a generated parser's ATN without parsing or executing an unfinished query.
 *
 * <p>The language adapter supplies complete tokens before the cursor and semantic rules (for
 * example, field references) whose contents it will supply from metadata. Grammar alternatives,
 * optional parameters, repetition, punctuation and operator precedence remain owned by the
 * generated grammar.
 */
final class AntlrCompletionSuggester {
  private static final int MAX_CONFIGURATIONS = 150_000;
  private static final int MAX_RULE_DEPTH = 96;
  private static final int MAX_INPUT_TOKENS = 4096;

  private AntlrCompletionSuggester() {}

  /**
   * A token sequence excludes EOF and excludes any partial token being replaced at the cursor.
   * Candidate rule stacks run from the requested start rule to the candidate's innermost rule. EOF
   * is returned when the supplied sequence can complete the start rule.
   */
  static Analysis suggest(
      Parser parser, int startRule, List<Integer> tokenTypes, Set<Integer> preferredRules) {
    return suggest(
        parser, startRule, tokenTypes, preferredRules, MAX_CONFIGURATIONS, MAX_RULE_DEPTH);
  }

  static Analysis suggest(
      Parser parser,
      int startRule,
      List<Integer> tokenTypes,
      Set<Integer> preferredRules,
      int maxConfigurations,
      int maxRuleDepth) {
    ATN atn = parser.getATN();
    if (startRule < 0 || startRule >= atn.ruleToStartState.length) {
      throw new IllegalArgumentException("Unknown parser start rule: " + startRule);
    }
    if (maxConfigurations < 1 || maxRuleDepth < 1) {
      throw new IllegalArgumentException("Completion traversal limits must be positive");
    }
    if (tokenTypes.size() > MAX_INPUT_TOKENS) {
      return new Analysis(List.of(), List.of(), true);
    }

    Set<TokenCandidate> tokens = new LinkedHashSet<>();
    Set<RuleCandidate> rules = new LinkedHashSet<>();
    Set<Configuration> visited = new HashSet<>();
    ArrayDeque<Configuration> pending = new ArrayDeque<>();
    pending.add(new Configuration(atn.ruleToStartState[startRule].stateNumber, 0, null, 0));
    boolean incomplete = false;

    while (!pending.isEmpty()) {
      Configuration configuration = pending.removeFirst();
      if (!visited.add(configuration)) continue;
      if (visited.size() > maxConfigurations) {
        incomplete = true;
        break;
      }

      ATNState state = atn.states.get(configuration.state());
      boolean atCursor = configuration.position() == tokenTypes.size();
      if (atCursor && state instanceof RuleStartState && preferredRules.contains(state.ruleIndex)) {
        rules.add(new RuleCandidate(state.ruleIndex, ruleStack(configuration, state.ruleIndex)));
        continue;
      }

      if (state instanceof RuleStopState) {
        Frame frame = configuration.stack();
        if (frame == null) {
          if (atCursor) {
            tokens.add(new TokenCandidate(Token.EOF, List.of(startRule)));
          }
        } else {
          pending.add(
              new Configuration(
                  frame.followState(),
                  configuration.position(),
                  frame.parent(),
                  frame.precedence()));
        }
        continue;
      }

      for (Transition transition : state.getTransitions()) {
        if (transition instanceof RuleTransition call) {
          int depth = configuration.stack() == null ? 1 : configuration.stack().depth() + 1;
          if (depth >= maxRuleDepth) {
            incomplete = true;
            continue;
          }
          Frame frame =
              new Frame(
                  call.followState.stateNumber,
                  state.ruleIndex,
                  configuration.precedence(),
                  configuration.stack(),
                  depth);
          pending.add(
              new Configuration(
                  call.target.stateNumber, configuration.position(), frame, call.precedence));
        } else if (transition instanceof PrecedencePredicateTransition predicate) {
          // Equivalent to Parser.precpred for the precedence passed to this rule invocation.
          if (predicate.precedence >= configuration.precedence()) {
            pending.add(configuration.at(transition.target.stateNumber));
          }
        } else if (transition.isEpsilon()) {
          // Context-dependent semantic predicates need a language adapter. Do not pretend that
          // treating one as viable gives an exhaustive, execution-validated completion result.
          if (transition instanceof PredicateTransition) incomplete = true;
          pending.add(configuration.at(transition.target.stateNumber));
        } else if (atCursor) {
          List<Integer> stack = ruleStack(configuration, state.ruleIndex);
          for (int type : labels(transition, atn.maxTokenType).toArray()) {
            tokens.add(new TokenCandidate(type, stack));
          }
        } else if (transition.matches(
            tokenTypes.get(configuration.position()),
            Token.MIN_USER_TOKEN_TYPE,
            atn.maxTokenType)) {
          pending.add(
              new Configuration(
                  transition.target.stateNumber,
                  configuration.position() + 1,
                  configuration.stack(),
                  configuration.precedence()));
        }
      }
    }
    return new Analysis(List.copyOf(tokens), List.copyOf(rules), incomplete);
  }

  private static IntervalSet labels(Transition transition, int maxTokenType) {
    if (transition instanceof WildcardTransition) {
      return IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, maxTokenType);
    }
    if (transition instanceof NotSetTransition) {
      return transition.label().complement(Token.MIN_USER_TOKEN_TYPE, maxTokenType);
    }
    return transition.label();
  }

  private static List<Integer> ruleStack(Configuration configuration, int ruleIndex) {
    List<Integer> result = new ArrayList<>();
    result.add(ruleIndex);
    for (Frame frame = configuration.stack(); frame != null; frame = frame.parent()) {
      result.add(frame.ruleIndex());
    }
    Collections.reverse(result);
    return List.copyOf(result);
  }

  record Analysis(List<TokenCandidate> tokens, List<RuleCandidate> rules, boolean incomplete) {}

  record TokenCandidate(int tokenType, List<Integer> ruleStack) {}

  record RuleCandidate(int ruleIndex, List<Integer> ruleStack) {}

  private record Frame(int followState, int ruleIndex, int precedence, Frame parent, int depth) {}

  private record Configuration(int state, int position, Frame stack, int precedence) {
    Configuration at(int target) {
      return new Configuration(target, position, stack, precedence);
    }
  }
}
