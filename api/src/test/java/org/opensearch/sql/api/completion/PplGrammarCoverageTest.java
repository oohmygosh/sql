/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.LL1Analyzer;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.Transition;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.junit.Test;
import org.opensearch.sql.api.completion.CompletionMetadata.Field;
import org.opensearch.sql.api.completion.CompletionMetadata.Relation;
import org.opensearch.sql.api.completion.CompletionMetadata.RelationRef;
import org.opensearch.sql.common.antlr.CaseInsensitiveCharStream;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLLexer;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLParser;

/**
 * Coverage is checked against the execution grammar, not against a second command/parameter
 * catalog. Fixtures supply valid contexts; the grammar supplies the expected command and parameter
 * tokens.
 */
public class PplGrammarCoverageTest {
  private static final String SOURCE = "source=orders | ";
  private static final OpenSearchPPLParser GRAMMAR = parser("");
  private static final Set<String> UNSUPPORTED_COMMANDS =
      Set.of("adCommand", "kmeansCommand", "mlCommand");

  // A new commands alternative must supply a complete example before this test can pass.
  private static final Map<String, String> COMMAND_EXAMPLES =
      fixture(
          """
          whereCommand;where status=200
          fieldsCommand;fields status
          tableCommand;table status
          joinCommand;join left=l right=r on l.status=r.status orders
          renameCommand;rename status as code
          statsCommand;stats count() by level
          eventstatsCommand;eventstats count() by level
          streamstatsCommand;streamstats count() by level
          dedupCommand;dedup status
          sortCommand;sort status
          evalCommand;eval code=status
          foreachCommand;foreach status [ eval status=status ]
          headCommand;head 10
          binCommand;bin amount span=10
          rareTopCommand;top level
          grokCommand;grok message '%{WORD:word}'
          parseCommand;parse message '(?<word>.*)'
          spathCommand;spath input=message path=foo output=payload
          patternsCommand;patterns message
          lookupCommand;lookup orders status
          kmeansCommand;kmeans centroids=2
          adCommand;ad number_of_trees=10
          mlCommand;ml algorithm='example'
          fillnullCommand;fillnull with 0 in status
          convertCommand;convert num(status)
          trendlineCommand;trendline sma(2,amount)
          appendcolCommand;appendcol [ fields status ]
          addtotalsCommand;addtotals amount
          addcoltotalsCommand;addcoltotals amount
          appendCommand;append [ source=orders ]
          expandCommand;expand message
          mvexpandCommand;mvexpand message
          flattenCommand;flatten message
          reverseCommand;reverse
          regexCommand;regex message='a'
          chartCommand;chart count() by level
          timechartCommand;timechart count() by level
          transposeCommand;transpose 5
          rexCommand;rex field=message '(?<word>.*)'
          appendPipeCommand;appendpipe [ fields status ]
          replaceCommand;replace 'a' with 'b' in message
          mvcombineCommand;mvcombine message
          fieldformatCommand;fieldformat amount=round(amount,2)
          nomvCommand;nomv message
          graphLookupCommand;graphlookup orders start=status edge=parent-->child as ancestors
          xyseriesCommand;xyseries level status in ('ok') amount
          unionCommand;union orders
          timewrapCommand;timewrap 1d
          """);

  private static final Map<String, String> TOP_COMMAND_EXAMPLES =
      fixture(
          """
          describeCommand;describe orders
          restCommand;rest '/_cat/indices' count=10 timeout='1s'
          showDataSourcesCommand;show datasources
          makeresultsCommand;makeresults count=1 format=json data='[]'
          searchCommand;search source=orders
          multisearchCommand;multisearch [source=orders] [source=orders]
          graphLookupCommand;graphlookup orders start=status edge=parent-->child as ancestors
          unionCommand;union orders
          """);

  private static final Map<String, String> TOP_PARAMETER_CONTEXTS =
      fixture(
          """
          restArgument;rest '/_cat/indices'
          makeresultsArg;makeresults
          """);

  // Each context reaches all keyword=value branches owned by this rule. New branches in an existing
  // rule are tested automatically; a new parameter rule fails the fixture coverage assertion.
  private static final Map<String, String> PARAMETER_CONTEXTS =
      fixture(
          """
          partitionsArg;stats
          allnumArg;stats
          delimArg;stats
          bucketNullableArg;stats
          dedupSplitArg;stats count()
          currentArg;streamstats
          windowArg;streamstats
          globalArg;streamstats
          resetBeforeArg;streamstats
          resetAfterArg;streamstats
          dedupCommand;dedup status
          chartOptions;chart
          transposeParameter;transpose
          timewrapParameter;timewrap 1d
          timechartParameter;timechart
          headCommand;head
          binOption;bin amount
          rareTopCommand;top
          rareTopOption;top
          spathParameter;spath
          rexExpr;rex
          rexOption;rex field=message
          patternsCommandOption;patterns message
          patternsParameter;patterns message
          fillnullCommand;fillnull
          convertCommand;convert
          mvcombineCommand;mvcombine message
          mvexpandCommand;mvexpand message
          appendcolCommand;appendcol
          subsearchOptions;union
          kmeansParameter;kmeans
          adParameter;ad
          addtotalsOption;addtotals
          addcoltotalsOption;addcoltotals
          startClause;graphlookup orders
          graphLookupArgs;graphlookup orders start=status edge=parent-->child
          xyseriesOption;xyseries
          fromClause;append [
          sideAlias;join
          joinOption;join
          timeModifier;append [
          relevanceArg;where match(message,'error',
          """);

  // Function names remain owned by the grammar. These fixtures supply only their expression slot.
  private static final Map<String, List<String>> FUNCTION_CONTEXTS =
      Map.of(
          "functionCall", List.of("eval result="),
          "evalFunctionName", List.of("eval result="),
          "statsFunction", List.of("stats "),
          "statsFunctionName", List.of("stats "),
          "windowFunctionName", List.of("eventstats ", "streamstats "),
          "scalarWindowFunctionName", List.of("eventstats ", "streamstats "),
          "relevanceExpression", List.of("where "));

  private static final CompletionMetadata METADATA =
      new CompletionMetadata() {
        @Override
        public String resolveSource(String source) {
          return source;
        }

        @Override
        public List<Relation> tables(
            String source, List<String> namespace, String prefix, int limit) {
          return "orders".startsWith(prefix)
              ? List.of(new Relation(namespace, "orders", "table"))
              : List.of();
        }

        @Override
        public List<Field> fields(String source, RelationRef relation) {
          return List.of(
              new Field("status", "integer"),
              new Field("level", "string"),
              new Field("message", "string"),
              new Field("amount", "double"),
              new Field("timestamp", "timestamp"));
        }
      };

  @Test
  public void everyPipeCommandHasAValidGrammarFixture() {
    assertEquals(
        "Add a command example when changing the commands grammar rule",
        directRuleCalls(rule("commands")),
        COMMAND_EXAMPLES.keySet());
    for (Map.Entry<String, String> example : COMMAND_EXAMPLES.entrySet()) {
      OpenSearchPPLParser parser = parser(SOURCE + example.getValue());
      var parsed = parser.root();
      assertEquals(example.toString(), 0, parser.getNumberOfSyntaxErrors());
      ParserRuleContext command =
          (ParserRuleContext) parsed.pplStatement().queryStatement().commands(0).getChild(0);
      assertEquals(
          example.toString(), example.getKey(), parser.getRuleNames()[command.getRuleIndex()]);
    }
  }

  @Test
  public void everyTopLevelCommandHasAValidGrammarFixture() {
    assertEquals(
        "Add a top-level command example when changing pplCommands",
        directRuleCalls(rule("pplCommands")),
        TOP_COMMAND_EXAMPLES.keySet());
    for (Map.Entry<String, String> example : TOP_COMMAND_EXAMPLES.entrySet()) {
      OpenSearchPPLParser parser = parser(example.getValue());
      var parsed = parser.root();
      assertEquals(example.toString(), 0, parser.getNumberOfSyntaxErrors());
      ParserRuleContext command =
          (ParserRuleContext) parsed.pplStatement().queryStatement().pplCommands().getChild(0);
      assertEquals(
          example.toString(), example.getKey(), parser.getRuleNames()[command.getRuleIndex()]);
    }
  }

  @Test
  public void explicitSupportedTopLevelCommandsCanContinueWithTheNextPipe() {
    assertEquals(Set.of("source", "search", "index"), labels(""));
    for (Map.Entry<String, String> example : TOP_COMMAND_EXAMPLES.entrySet()) {
      if (!example.getKey().equals("showDataSourcesCommand"))
        assertTrue(example.toString(), labels(example.getValue() + " ").contains("|"));
    }
  }

  @Test
  public void unsupportedTopLevelStatementsReportTheirExecutionCapability() {
    for (String query :
        List.of("show ", "show datasources ", "explain ", "explain source=orders ")) {
      CompletionResponse response = complete(query);
      assertTrue(query + " must not offer an executable continuation", response.items().isEmpty());
      assertTrue(
          query + " must explain its unsupported capability", response.message().contains("不支持"));
    }
  }

  @Test
  public void rootParametersContinueThroughAssignmentsAndFiniteValues() {
    String query = accept("makeresults ", "count");
    query = accept(query, "=");
    query = accept(query, "10");
    query = accept(query + " ", "format");
    query = accept(query, "=");
    assertTrue(labels(query).containsAll(List.of("csv", "json")));
    query = accept(query, "json");
    query = accept(query, "data");
    query = accept(query, "=");
    assertTrue(complete(query).message().contains("字符串"));
    assertTrue(labels(query + "'[]' ").contains("|"));

    query = accept("rest '/_cat/indices' ", "count");
    query = accept(query, "=");
    query = accept(query, "10");
    query = accept(query + " ", "timeout");
    query = accept(query, "=");
    assertTrue(complete(query).message().contains("字符串"));
    assertTrue(labels(query + "'1s' ").contains("|"));
  }

  @Test
  public void pipeMenuIncludesEverySupportedGrammarEntrypoint() {
    Set<String> actual = labels(SOURCE);
    for (String command : COMMAND_EXAMPLES.keySet()) {
      Set<String> starts =
          literalLabels(firstTokens(GRAMMAR.getATN().ruleToStartState[rule(command)]));
      if (UNSUPPORTED_COMMANDS.contains(command)) {
        assertTrue(
            command + " must not be advertised by Calcite completion", disjoint(actual, starts));
      } else {
        assertTrue(command + " missing " + difference(starts, actual), actual.containsAll(starts));
      }
    }
  }

  @Test
  public void completeSupportedCommandsCanContinueWithTheNextPipe() {
    for (Map.Entry<String, String> example : COMMAND_EXAMPLES.entrySet()) {
      if (!UNSUPPORTED_COMMANDS.contains(example.getKey())) {
        assertTrue(example.toString(), labels(SOURCE + example.getValue() + " ").contains("|"));
      }
    }
  }

  @Test
  public void everyGrammarParameterRuleHasAReachableContext() {
    Set<String> actualRules =
        assignments().stream()
            .map(assignment -> GRAMMAR.getRuleNames()[assignment.ruleIndex()])
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> contexts = new LinkedHashSet<>(PARAMETER_CONTEXTS.keySet());
    contexts.addAll(TOP_PARAMETER_CONTEXTS.keySet());
    assertEquals("Add a context for new keyword=value parameter rules", actualRules, contexts);
  }

  @Test
  public void everyParameterFollowsKeywordEqualsAndValueGrammar() {
    for (Assignment assignment : assignments()) {
      String ruleName = GRAMMAR.getRuleNames()[assignment.ruleIndex()];
      String parameter = literalLabel(assignment.keyword());
      String prefix = parameterPrefix(ruleName);
      assertContains(prefix, Set.of(assignment.keyword()), ruleName + " parameter " + parameter);
      assertContains(
          prefix + parameter + " ", Set.of(OpenSearchPPLLexer.EQUAL), ruleName + " equals");
      Set<Integer> expectedValues = new LinkedHashSet<>();
      for (ATNState valueStart : assignment.values())
        expectedValues.addAll(firstTokens(valueStart));
      expectedValues.remove(Token.EPSILON);
      assertFalse(ruleName + " has no value grammar", expectedValues.isEmpty());
      assertContains(
          prefix + parameter + "=", expectedValues, ruleName + " values for " + parameter);
    }
  }

  @Test
  public void providerExposesEverySupportedParameterAndItsAssignment() {
    for (Assignment assignment : assignments()) {
      String ruleName = GRAMMAR.getRuleNames()[assignment.ruleIndex()];
      if (Set.of("adParameter", "kmeansParameter").contains(ruleName)) continue;
      String parameter = literalLabel(assignment.keyword());
      // ALIGNTIME is meaningful for timestamp buckets; the other bin options use a numeric field.
      String prefix =
          ruleName.equals("binOption") && parameter.equals("aligntime")
              ? SOURCE + "bin timestamp span=1h "
              : parameterPrefix(ruleName);
      assertTrue(
          ruleName + " missing " + parameter + " after [" + prefix + "]",
          labels(prefix).contains(parameter));
      assertTrue(
          ruleName + " missing '=' after " + parameter,
          labels(prefix + parameter + " ").contains("="));
    }
  }

  @Test
  public void providerExposesFiniteGrammarValuesWithoutUnsupportedExecutionModes() {
    for (Assignment assignment : assignments()) {
      String ruleName = GRAMMAR.getRuleNames()[assignment.ruleIndex()];
      if (Set.of("adParameter", "kmeansParameter").contains(ruleName)) continue;
      Set<Integer> first = new LinkedHashSet<>();
      for (ATNState valueStart : assignment.values()) first.addAll(firstTokens(valueStart));
      Set<String> expected = literalLabels(first);
      // Identifier and literal slots are supplied by metadata or typed-value providers. Only finite
      // literal alternatives (boolean, enums, required parentheses) are exhaustively asserted here.
      if (expected.isEmpty() || expected.size() != first.size() || expected.size() > 12) continue;
      String parameter = literalLabel(assignment.keyword());
      if (ruleName.equals("dedupCommand") && parameter.equals("consecutive"))
        expected.remove("true");
      String prefix = parameterPrefix(ruleName) + parameter + "=";
      Set<String> actual = labels(prefix);
      assertTrue(
          ruleName + " missing values " + difference(expected, actual) + " after [" + prefix + "]",
          actual.containsAll(expected));
      if (ruleName.equals("dedupCommand") && parameter.equals("consecutive"))
        assertFalse("Calcite does not execute consecutive=true", actual.contains("true"));
    }
  }

  @Test
  public void everyGrammarFunctionNameHasAnExpressionContext() {
    Set<String> covered = new LinkedHashSet<>();
    for (String contextRule : FUNCTION_CONTEXTS.keySet()) {
      for (int reachable : leadingRules(contextRule)) {
        String name = GRAMMAR.getRuleNames()[reachable];
        if (name.endsWith("FunctionName")) covered.add(name);
      }
    }
    Set<String> expected =
        Arrays.stream(GRAMMAR.getRuleNames())
            .filter(name -> name.endsWith("FunctionName"))
            .collect(Collectors.toSet());
    assertEquals(
        "Add an expression context for new function-name grammar families", expected, covered);
  }

  @Test
  public void providerExposesEveryFunctionEntrypointFromTheGrammar() {
    for (Map.Entry<String, List<String>> fixture : FUNCTION_CONTEXTS.entrySet()) {
      Set<Integer> starts = firstTokens(GRAMMAR.getATN().ruleToStartState[rule(fixture.getKey())]);
      Set<String> finiteNames = literalLabels(starts);
      for (String context : fixture.getValue()) {
        String base = SOURCE + context;
        for (int token : starts) {
          String function = literalLabel(token);
          if (function == null) {
            // A patterned lexer token represents a family (for example numeric suffixes). Derive a
            // short representative from that lexer rule, rather than copying names into this test.
            String example = shortestLexeme(token).toLowerCase(Locale.ROOT);
            String prefix = example.substring(0, example.length() - 1);
            while (finiteNames.contains(prefix) && prefix.length() > 1)
              prefix = prefix.substring(0, prefix.length() - 1);
            assertFalse(
                "A function family needs a searchable prefix: " + example, prefix.isEmpty());
            assertTrue(
                fixture.getKey()
                    + " missing token family "
                    + GRAMMAR.getVocabulary().getSymbolicName(token),
                complete(base + prefix).items().stream()
                    .anyMatch(item -> firstToken(item.label()) == token));
          } else if (function.length() == 1) {
            assertTrue(
                fixture.getKey() + " must open the single-character function " + function,
                labels(base + function).contains("(")
                    || complete(base + function).items().stream()
                        .anyMatch(
                            item ->
                                item.label().equals(function)
                                    && item.textEdit().newText().endsWith("(")));
          } else {
            String prefix = function.substring(0, function.length() - 1);
            assertTrue(
                fixture.getKey() + " missing " + function + " after [" + base + prefix + "]",
                labels(base + prefix).contains(function));
          }
        }
      }
    }
  }

  private static Map<String, String> fixture(String lines) {
    Map<String, String> result = new LinkedHashMap<>();
    lines
        .lines()
        .filter(line -> !line.isBlank())
        .forEach(
            line -> {
              String[] pair = line.split(";", 2);
              if (result.put(pair[0], pair[1]) != null) throw new AssertionError(pair[0]);
            });
    return result;
  }

  private static String parameterPrefix(String ruleName) {
    return TOP_PARAMETER_CONTEXTS.containsKey(ruleName)
        ? TOP_PARAMETER_CONTEXTS.get(ruleName) + " "
        : SOURCE + PARAMETER_CONTEXTS.get(ruleName) + " ";
  }

  private static String accept(String text, String label) {
    var item =
        complete(text).items().stream()
            .filter(candidate -> candidate.label().equals(label))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "Missing " + label + " after [" + text + "]: " + labels(text)));
    return text.substring(0, item.textEdit().from())
        + item.textEdit().newText()
        + text.substring(item.textEdit().to());
  }

  private static Set<String> labels(String text) {
    return complete(text).items().stream()
        .map(CompletionResponse.Item::label)
        .collect(Collectors.toSet());
  }

  private static CompletionResponse complete(String text) {
    return new PplCompletionProvider()
        .complete(new CompletionRequest("ppl", "logs", text, text.length(), 1, "input"), METADATA);
  }

  private static int firstToken(String text) {
    return tokens(text).LT(1).getType();
  }

  private static void assertContains(String prefix, Set<Integer> expected, String description) {
    CommonTokenStream tokens = tokens(prefix);
    tokens.fill();
    List<Integer> tokenTypes =
        tokens.getTokens().stream()
            .filter(
                token ->
                    token.getType() != Token.EOF && token.getChannel() == Token.DEFAULT_CHANNEL)
            .map(Token::getType)
            .toList();
    var result = AntlrCompletionSuggester.suggest(GRAMMAR, rule("root"), tokenTypes, Set.of());
    Set<Integer> actual =
        result.tokens().stream()
            .map(AntlrCompletionSuggester.TokenCandidate::tokenType)
            .collect(Collectors.toSet());
    assertFalse(description + " exceeded grammar budget", result.incomplete());
    assertTrue(
        description + " after [" + prefix + "] missing " + tokenNames(difference(expected, actual)),
        actual.containsAll(expected));
  }

  private static CommonTokenStream tokens(String text) {
    OpenSearchPPLLexer lexer = new OpenSearchPPLLexer(new CaseInsensitiveCharStream(text));
    lexer.removeErrorListeners();
    return new CommonTokenStream(lexer);
  }

  private static OpenSearchPPLParser parser(String text) {
    return new OpenSearchPPLParser(tokens(text));
  }

  private static int rule(String name) {
    return Arrays.asList(GRAMMAR.getRuleNames()).indexOf(name);
  }

  private static Set<String> directRuleCalls(int ruleIndex) {
    Set<String> result = new LinkedHashSet<>();
    for (ATNState state : GRAMMAR.getATN().states) {
      if (state != null && state.ruleIndex == ruleIndex) {
        for (Transition transition : state.getTransitions()) {
          if (transition instanceof RuleTransition call)
            result.add(GRAMMAR.getRuleNames()[call.ruleIndex]);
        }
      }
    }
    return result;
  }

  private static Set<Integer> reachableRules() {
    Set<Integer> result = new LinkedHashSet<>(reachableRules("commands"));
    result.addAll(reachableRules("pplCommands"));
    return result;
  }

  private static Set<Integer> reachableRules(String startRule) {
    Set<Integer> result = new HashSet<>();
    ArrayDeque<Integer> pending = new ArrayDeque<>();
    pending.add(rule(startRule));
    while (!pending.isEmpty()) {
      int current = pending.removeFirst();
      if (result.add(current))
        for (String called : directRuleCalls(current)) pending.add(rule(called));
    }
    return result;
  }

  private static Set<Integer> leadingRules(String startRule) {
    Set<Integer> result = new LinkedHashSet<>();
    Set<Integer> visited = new HashSet<>();
    ArrayDeque<ATNState> pending = new ArrayDeque<>();
    pending.add(GRAMMAR.getATN().ruleToStartState[rule(startRule)]);
    while (!pending.isEmpty()) {
      ATNState state = pending.removeFirst();
      if (!visited.add(state.stateNumber)) continue;
      result.add(state.ruleIndex);
      if (state instanceof RuleStopState) continue;
      for (Transition transition : state.getTransitions()) {
        if (transition instanceof RuleTransition call) {
          pending.add(call.target);
          if (firstTokens(call.target).contains(Token.EPSILON)) pending.add(call.followState);
        } else if (transition.isEpsilon()) pending.add(transition.target);
      }
    }
    return result;
  }

  private record Lexeme(ATNState state, List<ATNState> returns, String text) {}

  /** Find a shortest accepted token spelling, following lexer fragments and epsilon transitions. */
  private static String shortestLexeme(int token) {
    var lexer = new OpenSearchPPLLexer(new CaseInsensitiveCharStream(""));
    var atn = lexer.getATN();
    int tokenRule = -1;
    for (int index = 0; index < atn.ruleToTokenType.length; index++)
      if (atn.ruleToTokenType[index] == token) tokenRule = index;
    assertTrue("No lexer rule for token " + token, tokenRule >= 0);
    PriorityQueue<Lexeme> pending =
        new PriorityQueue<>(
            java.util.Comparator.comparingInt((Lexeme path) -> path.text().length())
                .thenComparing(Lexeme::text));
    pending.add(new Lexeme(atn.ruleToStartState[tokenRule], List.of(), ""));
    Set<String> visited = new HashSet<>();
    while (!pending.isEmpty()) {
      Lexeme path = pending.remove();
      String key =
          path.state().stateNumber
              + ":"
              + path.returns().stream()
                  .map(state -> Integer.toString(state.stateNumber))
                  .collect(Collectors.joining(","));
      if (!visited.add(key)) continue;
      if (path.state() instanceof RuleStopState) {
        if (path.returns().isEmpty()) return path.text();
        pending.add(
            new Lexeme(
                path.returns().get(0),
                path.returns().subList(1, path.returns().size()),
                path.text()));
        continue;
      }
      for (Transition transition : path.state().getTransitions()) {
        if (transition instanceof RuleTransition call) {
          List<ATNState> returns = new ArrayList<>();
          returns.add(call.followState);
          returns.addAll(path.returns());
          pending.add(new Lexeme(call.target, returns, path.text()));
        } else if (transition.isEpsilon()) {
          pending.add(new Lexeme(transition.target, path.returns(), path.text()));
        } else if (transition.label() != null) {
          int character = transition.label().getMinElement();
          pending.add(
              new Lexeme(
                  transition.target, path.returns(), path.text() + Character.toString(character)));
        }
      }
    }
    throw new AssertionError(
        "No spelling for token " + GRAMMAR.getVocabulary().getSymbolicName(token));
  }

  private record Assignment(int ruleIndex, int keyword, List<ATNState> values) {}

  private record SingleTokenPosition(ATNState state, List<ATNState> returns, boolean consumed) {}

  /** Certifies a rule as a finite set of one-token literals, including aliases through subrules. */
  private static Set<Integer> singleLiteralTokens(int ruleIndex) {
    ATNState start = GRAMMAR.getATN().ruleToStartState[ruleIndex];
    Set<Integer> first = firstTokens(start);
    if (first.isEmpty() || first.stream().anyMatch(token -> literalLabel(token) == null))
      return Set.of();
    Set<SingleTokenPosition> visited = new HashSet<>();
    ArrayDeque<SingleTokenPosition> pending = new ArrayDeque<>();
    pending.add(new SingleTokenPosition(start, List.of(), false));
    boolean accepted = false;
    while (!pending.isEmpty()) {
      SingleTokenPosition current = pending.removeFirst();
      if (!visited.add(current)) continue;
      // Recursive rules cannot be certified as a finite token-name catalog by this bounded walk.
      if (current.returns().size() > 64) return Set.of();
      if (current.state() instanceof RuleStopState) {
        if (current.returns().isEmpty()) {
          if (!current.consumed()) return Set.of();
          accepted = true;
        } else {
          pending.add(
              new SingleTokenPosition(
                  current.returns().get(0),
                  current.returns().subList(1, current.returns().size()),
                  current.consumed()));
        }
        continue;
      }
      for (Transition transition : current.state().getTransitions()) {
        if (transition instanceof RuleTransition call) {
          List<ATNState> returns = new ArrayList<>();
          returns.add(call.followState);
          returns.addAll(current.returns());
          pending.add(new SingleTokenPosition(call.target, returns, current.consumed()));
        } else if (transition.isEpsilon()) {
          pending.add(
              new SingleTokenPosition(transition.target, current.returns(), current.consumed()));
        } else {
          if (current.consumed() || transition.label() == null) return Set.of();
          for (int token : transition.label().toArray())
            if (literalLabel(token) == null) return Set.of();
          pending.add(new SingleTokenPosition(transition.target, current.returns(), true));
        }
      }
    }
    return accepted ? first : Set.of();
  }

  /**
   * Finds literal-keyword '=' pairs, including inline parameters and finite parameter-name rules.
   */
  private static List<Assignment> assignments() {
    Set<Integer> reachable = reachableRules();
    Map<String, Assignment> result = new LinkedHashMap<>();
    for (ATNState state : GRAMMAR.getATN().states) {
      if (state == null || !reachable.contains(state.ruleIndex)) continue;
      for (Transition transition : state.getTransitions()) {
        Set<Integer> keywords;
        List<ATNState> values;
        if (transition instanceof RuleTransition call) {
          values = valuesAfterEquals(call.followState, state.ruleIndex);
          if (values.isEmpty()) continue;
          keywords = singleLiteralTokens(call.ruleIndex);
        } else {
          if (transition.isEpsilon() || transition.label() == null) continue;
          keywords =
              Arrays.stream(transition.label().toArray()).boxed().collect(Collectors.toSet());
          values = valuesAfterEquals(transition.target, state.ruleIndex);
        }
        if (values.isEmpty()) continue;
        for (int keyword : keywords) {
          String literal = literalLabel(keyword);
          if (literal == null || !Character.isLetter(literal.charAt(0))) continue;
          String key = state.ruleIndex + ":" + keyword;
          result
              .computeIfAbsent(
                  key, ignored -> new Assignment(state.ruleIndex, keyword, new ArrayList<>()))
              .values()
              .addAll(values);
        }
      }
    }
    return List.copyOf(result.values());
  }

  private static List<ATNState> valuesAfterEquals(ATNState start, int ruleIndex) {
    List<ATNState> result = new ArrayList<>();
    Set<Integer> visited = new HashSet<>();
    ArrayDeque<ATNState> pending = new ArrayDeque<>();
    pending.add(start);
    while (!pending.isEmpty()) {
      ATNState state = pending.removeFirst();
      if (state.ruleIndex != ruleIndex || !visited.add(state.stateNumber)) continue;
      for (Transition transition : state.getTransitions()) {
        if (transition instanceof RuleTransition) continue;
        if (transition.isEpsilon()) pending.add(transition.target);
        else if (transition.label() != null
            && transition.label().contains(OpenSearchPPLLexer.EQUAL)) result.add(transition.target);
      }
    }
    return result;
  }

  private static Set<Integer> firstTokens(ATNState state) {
    IntervalSet first = new LL1Analyzer(GRAMMAR.getATN()).LOOK(state, null);
    return Arrays.stream(first.toArray())
        .boxed()
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static String literalLabel(int token) {
    String literal = GRAMMAR.getVocabulary().getLiteralName(token);
    return literal == null
        ? null
        : literal.substring(1, literal.length() - 1).toLowerCase(Locale.ROOT);
  }

  private static Set<String> literalLabels(Set<Integer> tokens) {
    Set<String> result = new LinkedHashSet<>();
    for (int token : tokens) {
      String label = literalLabel(token);
      if (label != null) result.add(label);
    }
    return result;
  }

  private static String tokenNames(Set<Integer> tokens) {
    return tokens.stream()
        .map(GRAMMAR.getVocabulary()::getDisplayName)
        .collect(Collectors.joining(", "));
  }

  private static <T> Set<T> difference(Set<T> left, Set<T> right) {
    Set<T> result = new LinkedHashSet<>(left);
    result.removeAll(right);
    return result;
  }

  private static <T> boolean disjoint(Set<T> left, Set<T> right) {
    return left.stream().noneMatch(right::contains);
  }
}
