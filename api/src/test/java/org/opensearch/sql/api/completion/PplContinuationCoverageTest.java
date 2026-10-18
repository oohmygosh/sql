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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.Transition;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.opensearch.sql.api.completion.CompletionMetadata.Field;
import org.opensearch.sql.api.completion.CompletionMetadata.Relation;
import org.opensearch.sql.api.completion.CompletionMetadata.RelationRef;
import org.opensearch.sql.api.completion.CompletionResponse.Item;
import org.opensearch.sql.common.antlr.CaseInsensitiveCharStream;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLLexer;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLParser;

/**
 * User journeys, not isolated keyword checks. Every unmarked step accepts an actual completion and
 * applies its edit/caret contract. Only values, new names and patterns may use {@code ~} free
 * input. Advanced root commands use {@code @} only for the initial explicitly typed command.
 * Deliberately opening an optional source branch uses {@code ^} for its typed intent prefix; the
 * following steps still accept actual completions. Successful journeys end in a parsed query and
 * another accepted pipe; unsupported roots are checked for an explicit rejection instead.
 */
@RunWith(Parameterized.class)
public class PplContinuationCoverageTest {
  private static final OpenSearchPPLParser GRAMMAR = parser("");
  // These finite rules are checked by complete direct parse-tree paths, including optional
  // branches.
  // Rule calls are kept as semantic slots; the journeys separately exercise the nested slots.
  private static final Set<String> BRANCH_RULES =
      Set.of(
          "functionCall",
          "statsByClause",
          "spanClause",
          "bySpanClause",
          "lookupPair",
          "fillnullCommand",
          "fillNullWith",
          "sortFieldExpression",
          "sortField",
          "convertFunction",
          "trendlineClause",
          "spathParameter",
          "pathArrayAccess",
          "timewrapAlign",
          "timewrapSeries",
          "foreachTarget");
  private static final Set<String> OBSERVED_BRANCHES = new LinkedHashSet<>();
  private static final Map<String, String> UNEXERCISED_BRANCHES =
      Map.of(
          "sortField:PLUS sortFieldExpression ASC",
              "Grammar deliberately accepts this only to report invalid mixed prefix/suffix"
                  + " direction",
          "sortField:PLUS sortFieldExpression A",
              "Grammar deliberately accepts this only to report invalid mixed prefix/suffix"
                  + " direction",
          "sortField:PLUS sortFieldExpression DESC",
              "Grammar deliberately accepts this only to report invalid mixed prefix/suffix"
                  + " direction",
          "sortField:PLUS sortFieldExpression D",
              "Grammar deliberately accepts this only to report invalid mixed prefix/suffix"
                  + " direction",
          "sortField:MINUS sortFieldExpression ASC",
              "Grammar deliberately accepts this only to report invalid mixed prefix/suffix"
                  + " direction",
          "sortField:MINUS sortFieldExpression A",
              "Grammar deliberately accepts this only to report invalid mixed prefix/suffix"
                  + " direction",
          "sortField:MINUS sortFieldExpression DESC",
              "Grammar deliberately accepts this only to report invalid mixed prefix/suffix"
                  + " direction",
          "sortField:MINUS sortFieldExpression D",
              "Grammar deliberately accepts this only to report invalid mixed prefix/suffix"
                  + " direction");

  private static final Set<String> UNSUPPORTED = Set.of("adCommand", "kmeansCommand");

  // Each grammar command must have a complete journey or an explicit unsupported entry above.
  // Multiple entries exercise optional/recursive syntax; the descriptions are a branch checklist.
  private static final String PIPE_JOURNEYS =
      """
      whereCommand;comparison;where / status / = / ~200
      fieldsCommand;comma-separated projection;fields / status / , / level
      tableCommand;projection alias;table / amount
      joinCommand;table join;join / customers
      renameCommand;rename then new output name;rename / status / as / ~code
      statsCommand;aggregate and group;stats / count / ) / as / ~total / by / level
      eventstatsCommand;window aggregate and group;eventstats / count / ) / as / ~total / by / level
      streamstatsCommand;window options and aggregate;streamstats / current / = / true / window / = / ~2 / sum / amount / ) / by / level
      dedupCommand;both boolean options;dedup / status / keepempty / = / true / consecutive / = / false
      sortCommand;directions and comma;sort / amount / desc / , / level / asc
      evalCommand;new name and function;eval / ~result / = / ^ab / abs / amount / )
      foreachCommand;target and nested eval;foreach / amount / [ / eval / ~result / = / amount / ]
      headCommand;limit and offset;head / limit / = / ~10 / from / ~2
      binCommand;numeric span and alias;bin / amount / span / = / 10 / as / ~bucket
      rareTopCommand;top and grouping;top / level / by / status
      grokCommand;required pattern;grok / message / ~'%{WORD:word}'
      parseCommand;required pattern;parse / message / ~'(?<word>.*)'
      spathCommand;input path and output;spath / input / = / message / path / = / ~foo / output / = / ~payload
      patternsCommand;method and mode;patterns / message / method / = / simple_pattern / mode / = / label
      lookupCommand;mapping and output alias;lookup / customers / customer_key / as / customer_id / output / customer_name / as / ~name
      mlCommand;backend declared parameter;ml / algorithm / = / ~'linear_regression'
      fillnullCommand;with and in;fillnull / with / ~0 / in / amount
      convertCommand;conversion and output alias;convert / num / amount / ) / as / ~numeric_amount
      trendlineCommand;two arguments and alias;trendline / sma / ( / ~2 / , / amount / ) / as / ~moving
      appendcolCommand;override and inherited subpipeline;appendcol / override / = / true / [ / fields / amount / ]
      addtotalsCommand;field and optional output name;addtotals / amount / fieldname / = / ~'total'
      addcoltotalsCommand;field and optional label;addcoltotals / amount / label / = / ~'total'
      appendCommand;independent subquery;append / [ / source / = / customers / | / fields / customer_name / ]
      expandCommand;alias;expand / tags / as / ~tag
      mvexpandCommand;limit;mvexpand / tags / limit / = / ~10
      flattenCommand;struct;flatten / payload
      reverseCommand;no argument;reverse
      regexCommand;quoted pattern;regex / message / = / ~'error'
      chartCommand;over by and trailing option;chart / count / ) / over / level / by / status / limit / = / ~10
      timechartCommand;span and time field;timechart / span / = / 1h / count / ) / by / level / timefield / = / timestamp
      transposeCommand;count and column name;transpose / ~5 / column_name / = / ~'metric'
      rexCommand;mode pattern and trailing option;rex / field / = / message / mode / = / extract / ~'(?<word>.*)' / max_match / = / ~2
      appendPipeCommand;inherited subpipeline;appendpipe / [ / fields / amount / ]
      replaceCommand;replacement list and fields;replace / ~'a' / with / ~'b' / , / ~'c' / with / ~'d' / in / message
      mvcombineCommand;delimiter;mvcombine / tags / delim / = / ~','
      fieldformatCommand;multi argument expression;fieldformat / amount / = / ^ro / round / amount / , / ~2 / )
      nomvCommand;array field;nomv / tags
      graphLookupCommand;edge token and depth;graphlookup / customers / start / = / customer_id / edge= / customer_key / --> / customer_name / maxdepth / = / ~2 / as / ~ancestors
      xyseriesCommand;pivot values and data field;xyseries / level / status / in / ( / ~'ok' / , / ~'error' / ) / amount
      unionCommand;multiple tables;union / customers / , / orders
      timewrapCommand;span align and series;timewrap / 1d / align / = / now / series / = / exact
      whereCommand;between keeps both bounds;where / amount / between / ~1 / and / ~10
      whereCommand;in list;where / status / in / ( / ~200 / , / ~201 / )
      whereCommand;is not null;where / message / is / not / null
      whereCommand;grouped boolean expression;where / ( / status / = / ~200 / or / status / = / ~201 / ) / and / amount / > / ~0
      whereCommand;field comparison on right;where / amount / > / ^sta / status
      whereCommand;exists subquery;where / exists / [ / source / = / customers / | / fields / customer_key / ]
      whereCommand;not in subquery;where / customer_id / not / in / [ / source / = / customers / | / fields / customer_key / ]
      evalCommand;case branch and else;eval / ~band / = / ^ca / case / amount / > / ~10 / , / ~'high' / else / ~'low' / )
      evalCommand;case repeated conditions;eval / ~band / = / ^ca / case / amount / > / ~10 / , / ~'high' / , / amount / > / ~5 / , / ~'medium' / else / ~'low' / )
      evalCommand;position uses IN not a call;eval / ~pos / = / ^po / position / ~'a' / in / message / )
      evalCommand;nested if concat and arithmetic;eval / ~result / = / ^i / if / amount / > / ~0 / , / ^con / concat / message / , / ~'!' / ) / , / ~'empty' / ) / , / ~twice / = / amount / * / ~2 / + / status
      evalCommand;mvmap two arguments;eval / ~mapped / = / ^mvm / mvmap / tags / , / amount / + / ~1 / )
      evalCommand;timestampadd date unit and arguments;eval / ~later / = / ^timestampa / timestampadd / day / , / ~1 / , / timestamp / )
      evalCommand;extract uses FROM;eval / ~year / = / ^extr / extract / year / from / timestamp / )
      evalCommand;get format enum and value;eval / ~format / = / ^get_f / get_format / date / , / ~'ISO' / )
      evalCommand;multi argument avg expression;eval / ~mean / = / ^av / avg / amount / + / ~1 / , / status / * / ~2 / )
      evalCommand;cast with nested expression;eval / ~converted / = / ^cas / cast / amount / + / ~1 / as / string / )
      statsCommand;span only;stats / count / ) / as / ~total / by / span / timestamp / , / 1h / ) / as / ~hour
      statsCommand;span then field;stats / sum / amount / ) / by / span / timestamp / , / 1h / ) / , / level
      statsCommand;field then span;stats / count / ) / by / level / , / span / timestamp / , / 1h / )
      eventstatsCommand;span group;eventstats / sum / amount / ) / by / span / timestamp / , / 1h / )
      streamstatsCommand;span group;streamstats / sum / amount / ) / by / span / timestamp / , / 1h / )
      statsCommand;percentile optional compression;stats / percentile / amount / , / ~95 / , / ~100 / ) / as / ~p95
      statsCommand;take optional count;stats / take / message / , / ~3 / ) / as / ~examples
      statsCommand;count eval predicate;stats / count / eval / ( / status / = / ~200 / ) / ) / as / ~success
      statsCommand;multiple aggregate terms;stats / count / ) / as / ~total / , / sum / amount / ) / as / ~sum_amount / by / level / dedup_splitvalues / = / true
      streamstatsCommand;reset expression optional branches;streamstats / reset_before / = / status / = / ~500 / reset_after / = / amount / < / ~0 / count / ) / by / level
      sortCommand;prefix direction and wrapped field;sort / - / num / ( / amount / ) / , / + / str / ( / level / )
      fieldsCommand;exclude field list;fields / - / message / , / payload
      renameCommand;multiple rename clauses;rename / status / as / ~code / , / level / as / ~severity
      fillnullCommand;using field replacements;fillnull / using / amount / = / ~0 / , / message / = / ~'missing'
      fillnullCommand;value syntax;fillnull / value / = / ~0 / amount
      convertCommand;multiple functions and aliases;convert / num / amount / ) / as / ~numeric_amount / , / ctime / timestamp / ) / as / ~display_time
      chartCommand;by pair optional comma;chart / sum / amount / ) / by / level / , / status
      appendPipeCommand;project then predicate in nested scope;appendpipe / [ / fields / amount / | / where / amount / > / ~0 / ]
      appendCommand;nested aggregate scope;append / [ / source / = / customers / | / stats / count / ) / as / ~total / by / customer_name / | / fields / total / , / customer_name / ]
      unionCommand;nested subquery dataset;union / [ / source / = / customers / | / fields / customer_key / ]
      foreachCommand;mode and nested expression;foreach / mode / = / multifield / amount / [ / eval / ~result / = / amount / + / ~1 / ]
      lookupCommand;multiple mappings and output fields;lookup / customers / customer_key / as / customer_id / , / risk / as / amount / output / customer_name / as / ~name / , / risk / as / ~customer_risk
      statsCommand;implicit time field span;stats / count / ) / by / span / ^1 / 1h / )
      lookupCommand;mapping without alias;lookup / customers / customer_key
      fillnullCommand;value all fields;fillnull / value / = / ~0
      fillnullCommand;with all fields;fillnull / with / ~0
      sortCommand;auto and ip wrappers;sort / auto / ( / status / ) / , / ip / ( / message / )
      sortCommand;short direction aliases;sort / level / a / , / amount / d
      convertCommand;conversion without alias;convert / num / amount / )
      trendlineCommand;window without alias;trendline / wma / ( / ~2 / , / amount / )
      spathCommand;bare path;spath / ~foo
      spathCommand;path with explicit array offset;spath / path / = / ~foo / { / ~0 / }
      spathCommand;path with wildcard array;spath / path / = / ~foo / { / }
      timewrapCommand;end relative;timewrap / 1d / align / = / end / series / = / relative
      timewrapCommand;short series;timewrap / 1d / series / = / short
      foreachCommand;wildcard target;foreach / * / [ / eval / ~result / = / ~1 / ]
      foreachCommand;quoted target;foreach / ~'amount' / [ / eval / ~result / = / amount / ]
      foreachCommand;function target;foreach / ^spl / split / message / , / ~',' / ) / [ / eval / ~result / = / ~1 / ]
      statsCommand;span output used in next stage;stats / count / ) / as / ~total / by / span / timestamp / , / 1h / ) / as / ~hour / | / fields / total / , / hour
      renameCommand;renamed field used in next stage;rename / status / as / ~code / | / where / code / = / ~200
      evalCommand;created field used after projection;eval / ~result / = / amount / + / ~1 / | / fields / result / | / where / result / > / ~0
      joinCommand;typed join with criteria and subquery;left / join / left / = / ~l / right / = / ~r / on / ~l.customer_id / = / ~r.customer_key / [ / source / = / customers / | / fields / customer_key / ]
      """;

  // Advanced roots are deliberately typed: the empty editor must still suggest only the three
  // product entry points. This exercises their bodies and continuation without widening that menu.
  private static final String TOP_JOURNEYS =
      """
      searchCommand;source root;source / = / orders
      searchCommand;search source root;search / source / = / orders
      searchCommand;index root;index / = / orders
      searchCommand;search index root;search / index / = / orders
      searchCommand;source alias and schema;source / = / orders / ^a / as / ~o / | / where / amount / > / ~0
      searchCommand;source comma tables;source / = / orders / ^, / customers
      restCommand;rest arguments;@rest / ~'/_cat/indices' / count / = / ~10 / timeout / = / ~'1s'
      makeresultsCommand;makeresults json data;@makeresults / count / = / ~1 / format / = / json / data / = / ~'[]'
      makeresultsCommand;makeresults csv data;@makeresults / format / = / csv / data / = / ~'amount'
      describeCommand;describe real table;@describe / orders
      multisearchCommand;two independent nested searches;@multisearch / [ / source / = / orders / | / fields / amount / ] / [ / source / = / customers / | / fields / risk / ]
      unionCommand;union table and subquery;@union / maxout / = / ~10 / orders / , / [ / source / = / customers / | / fields / customer_key / ]
      graphLookupCommand;root graph with explicit initial value;@graphlookup / customers / start / = / ~'key' / edge= / customer_key / --> / customer_name / as / ~ancestors
      """;

  private static final String REJECTED_ROOTS =
      """
      showDataSourcesCommand;show incomplete;show
      showDataSourcesCommand;show complete;show datasources
      explainStatement;explain incomplete;explain
      explainStatement;explain complete;explain source=orders
      """;

  private static final CompletionMetadata METADATA =
      new CompletionMetadata() {
        @Override
        public String resolveSource(String source) {
          return source;
        }

        @Override
        public Set<String> capabilities(String source) {
          return Set.of("machine_learning");
        }

        @Override
        public List<String> parameterNames(String source, String capability) {
          return List.of("algorithm");
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
                    new Field("status", "integer"),
                    new Field("level", "string"),
                    new Field("message", "string"),
                    new Field("amount", "double"),
                    new Field("timestamp", "timestamp"),
                    new Field("customer_id", "string"),
                    new Field("tags", "array"),
                    new Field("payload", "struct"));
            case "customers" ->
                List.of(
                    new Field("customer_key", "string"),
                    new Field("customer_name", "string"),
                    new Field("risk", "double"));
            default -> throw new AssertionError("Wrong relation binding: " + relation);
          };
        }
      };

  @Parameterized.Parameters(name = "{0}")
  public static List<Object[]> journeys() {
    List<Object[]> result = new ArrayList<>();
    addJourneys(result, "PIPE", PIPE_JOURNEYS);
    addJourneys(result, "TOP", TOP_JOURNEYS);
    addJourneys(result, "REJECT", REJECTED_ROOTS);
    return result;
  }

  private static void addJourneys(List<Object[]> target, String scope, String fixtures) {
    fixtures
        .lines()
        .filter(line -> !line.isBlank())
        .map(line -> line.split(";", 3))
        .forEach(
            parts ->
                target.add(
                    new Object[] {
                      scope + " " + parts[0] + ": " + parts[1], scope, parts[0], parts[2]
                    }));
  }

  @BeforeClass
  public static void everyCommandMustHaveAnEndToEndJourneyOrBeExplicitlyUnsupported() {
    Set<String> covered =
        PIPE_JOURNEYS
            .lines()
            .filter(line -> !line.isBlank())
            .map(line -> line.substring(0, line.indexOf(';')))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    assertTrue(
        "Unsupported entries must not pretend to be exercised",
        covered.stream().noneMatch(UNSUPPORTED::contains));
    covered.addAll(UNSUPPORTED);
    assertEquals(
        "A grammar command needs a complete continuous journey",
        directCommands("commands"),
        covered);
    Set<String> topCovered =
        TOP_JOURNEYS
            .lines()
            .filter(line -> !line.isBlank())
            .map(line -> line.substring(0, line.indexOf(';')))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    topCovered.add("showDataSourcesCommand");
    assertEquals(
        "A top-level grammar command needs a complete journey or capability rejection",
        directCommands("pplCommands"),
        topCovered);
    CompletionResponse empty = new Journey("empty document").complete();
    assertEquals(
        Set.of("source", "search", "index"),
        empty.items().stream().map(Item::label).collect(Collectors.toSet()));
  }

  private final String name;
  private final String scope;
  private final String commandRule;
  private final String steps;

  public PplContinuationCoverageTest(String name, String scope, String commandRule, String steps) {
    this.name = name;
    this.scope = scope;
    this.commandRule = commandRule;
    this.steps = steps;
  }

  @Test
  public void acceptsEveryStructuralStepThenParsesAndContinuesIntoAnotherCommand() {
    Journey journey = new Journey(name);
    if (scope.equals("REJECT")) {
      journey.typeOpening(steps);
      CompletionResponse response = journey.complete();
      assertTrue(name + " must report the execution capability restriction", response.incomplete());
      assertTrue(name + " must explain unsupported syntax", response.message().contains("不支持"));
      assertTrue(name + " must not suggest a successful continuation", response.items().isEmpty());
      return;
    }
    if (scope.equals("PIPE")) journey.select("source").select("=").select("orders").select("|");
    for (String step : steps.split(" / ")) {
      if (step.startsWith("@")) journey.typeOpening(step.substring(1));
      else if (step.startsWith("^")) journey.typeIntentPrefix(step.substring(1));
      else if (step.startsWith("~")) journey.typeValueOrNewName(step.substring(1));
      else journey.select(step);
    }
    OpenSearchPPLParser parser = parser(journey.text);
    var query = parser.root();
    assertEquals(name + " final text: " + journey.text, 0, parser.getNumberOfSyntaxErrors());
    ParserRuleContext command =
        (ParserRuleContext)
            (scope.equals("PIPE")
                ? query.pplStatement().queryStatement().commands(0).getChild(0)
                : query.pplStatement().queryStatement().pplCommands().getChild(0));
    assertEquals(name, commandRule, parser.getRuleNames()[command.getRuleIndex()]);
    recordBranches(query);
    journey.select("|").select("head");
    parser = parser(journey.text);
    parser.root();
    assertEquals(name + " next pipeline: " + journey.text, 0, parser.getNumberOfSyntaxErrors());
  }

  @AfterClass
  public static void everyAuditedGrammarBranchNeedsACompleteJourneyOrAnExplicitReason() {
    Set<String> expected = new LinkedHashSet<>();
    for (String rule : BRANCH_RULES) expected.addAll(directPaths(rule));
    Set<String> accounted = new LinkedHashSet<>(OBSERVED_BRANCHES);
    accounted.addAll(UNEXERCISED_BRANCHES.keySet());
    Set<String> missing = new LinkedHashSet<>(expected);
    missing.removeAll(accounted);
    Set<String> obsolete = new LinkedHashSet<>(accounted);
    obsolete.removeAll(expected);
    assertTrue(
        "Add complete continuous journeys for these grammar paths: " + missing, missing.isEmpty());
    assertTrue("Remove obsolete grammar coverage entries: " + obsolete, obsolete.isEmpty());
  }

  private static void recordBranches(ParseTree node) {
    if (node instanceof ParserRuleContext context) {
      String rule = OpenSearchPPLParser.ruleNames[context.getRuleIndex()];
      if (BRANCH_RULES.contains(rule)) {
        List<String> path = new ArrayList<>();
        for (int index = 0; index < context.getChildCount(); index++) {
          ParseTree child = context.getChild(index);
          if (child instanceof ParserRuleContext nested)
            path.add(OpenSearchPPLParser.ruleNames[nested.getRuleIndex()]);
          else if (child instanceof TerminalNode terminal)
            path.add(tokenName(terminal.getSymbol().getType()));
        }
        OBSERVED_BRANCHES.add(rule + ":" + String.join(" ", path));
      }
    }
    for (int index = 0; index < node.getChildCount(); index++) recordBranches(node.getChild(index));
  }

  private static Set<String> directPaths(String rule) {
    int index = Arrays.asList(OpenSearchPPLParser.ruleNames).indexOf(rule);
    Set<String> result = new LinkedHashSet<>();
    enumeratePaths(rule, GRAMMAR.getATN().ruleToStartState[index], List.of(), Set.of(), result);
    return result;
  }

  private static void enumeratePaths(
      String rule, ATNState state, List<String> path, Set<Integer> visited, Set<String> result) {
    if (state instanceof RuleStopState) {
      result.add(rule + ":" + String.join(" ", path));
      return;
    }
    assertFalse(
        "Only finite direct rules belong in this branch gate: " + rule,
        visited.contains(state.stateNumber));
    Set<Integer> nextVisited = new HashSet<>(visited);
    nextVisited.add(state.stateNumber);
    for (Transition transition : state.getTransitions()) {
      if (transition instanceof RuleTransition call) {
        List<String> next = new ArrayList<>(path);
        next.add(OpenSearchPPLParser.ruleNames[call.ruleIndex]);
        enumeratePaths(rule, call.followState, next, nextVisited, result);
      } else if (transition.isEpsilon()) {
        enumeratePaths(rule, transition.target, path, nextVisited, result);
      } else {
        for (int token : transition.label().toArray()) {
          List<String> next = new ArrayList<>(path);
          next.add(tokenName(token));
          enumeratePaths(rule, transition.target, next, nextVisited, result);
        }
      }
    }
  }

  private static String tokenName(int type) {
    return type == Token.EOF ? "EOF" : GRAMMAR.getVocabulary().getSymbolicName(type);
  }

  private static final class Journey {
    final String name;
    final PplCompletionProvider provider = new PplCompletionProvider();
    String text = "";
    int caret;
    long version;

    Journey(String name) {
      this.name = name;
    }

    CompletionResponse complete() {
      try {
        var response =
            provider.complete(
                new CompletionRequest("ppl", "logs", text, caret, ++version, "accept"), METADATA);
        assertTrue(
            "A small-schema journey must not expand an unbounded default menu: " + text,
            response.items().size() <= 100);
        return response;
      } catch (RuntimeException | AssertionError failure) {
        throw new AssertionError(
            name + " completion failed after [" + text + "] at " + caret, failure);
      }
    }

    void typeOpening(String opening) {
      assertTrue("Only the initial advanced command may be typed directly", text.isEmpty());
      typeValueOrNewName(opening);
    }

    void typeIntentPrefix(String prefix) {
      assertTrue("An explicit branch prefix follows an established context", !text.isEmpty());
      assertTrue(
          "A prefix cannot silently insert a complete expression",
          prefix.matches("[A-Za-z][A-Za-z0-9_]*|[0-9]+|,"));
      text = text.substring(0, caret) + prefix + text.substring(caret);
      caret += prefix.length();
    }

    Journey select(String label) {
      CompletionResponse response = complete();
      Item item =
          response.items().stream()
              .filter(candidate -> candidate.label().equals(label))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          name
                              + "\nMissing completion ["
                              + label
                              + "] after ["
                              + text
                              + "] at "
                              + caret
                              + "\nAvailable: "
                              + response.items().stream().map(Item::label).toList()
                              + "\nMessage: "
                              + response.message()));
      var edit = item.textEdit();
      assertTrue(name + " invalid edit start", edit.from() >= 0 && edit.from() <= caret);
      assertTrue(
          name + " invalid edit end", edit.to() >= edit.from() && edit.to() <= text.length());
      String previous = text;
      text = text.substring(0, edit.from()) + edit.newText() + text.substring(edit.to());
      caret =
          edit.from() + (item.caretOffset() == null ? edit.newText().length() : item.caretOffset());
      assertTrue(name + " invalid returned caret", caret >= 0 && caret <= text.length());
      assertFalse(name + " selection must advance: " + label, previous.equals(text));
      if (item.kind().equals("parameter") && !item.textEdit().newText().contains("=")) {
        assertEquals(
            name + " parameter key must not mix fields or values: " + text,
            Set.of("="),
            complete().items().stream().map(Item::label).collect(Collectors.toSet()));
      }
      return this;
    }

    void typeValueOrNewName(String value) {
      // Free input is deliberately visible in each fixture; it may never hide a missing keyword.
      String inserted = value + " ";
      text = text.substring(0, caret) + inserted + text.substring(caret);
      caret += inserted.length();
    }
  }

  private static Set<String> directCommands(String ruleName) {
    int rule = Arrays.asList(OpenSearchPPLParser.ruleNames).indexOf(ruleName);
    Set<String> result = new LinkedHashSet<>();
    Set<Integer> visited = new HashSet<>();
    ArrayDeque<ATNState> queue = new ArrayDeque<>();
    queue.add(GRAMMAR.getATN().ruleToStartState[rule]);
    while (!queue.isEmpty()) {
      ATNState state = queue.remove();
      if (!visited.add(state.stateNumber) || state instanceof RuleStopState) continue;
      for (Transition transition : state.getTransitions()) {
        if (transition instanceof RuleTransition call) {
          result.add(OpenSearchPPLParser.ruleNames[call.ruleIndex]);
        } else queue.add(transition.target);
      }
    }
    return result;
  }

  private static OpenSearchPPLParser parser(String query) {
    var lexer = new OpenSearchPPLLexer(new CaseInsensitiveCharStream(query));
    lexer.removeErrorListeners();
    var parser = new OpenSearchPPLParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    return parser;
  }
}
