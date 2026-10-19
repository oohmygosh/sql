/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.opensearch.sql.api.completion.CompletionMetadata.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.opensearch.sql.api.completion.CompletionResponse.Item;
import org.opensearch.sql.common.antlr.CaseInsensitiveCharStream;
import org.opensearch.sql.common.utils.StringUtils;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLLexer;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLParser;

/**
 * PPL editor context and pipeline schema, using the execution grammar without executing a query.
 */
public final class PplCompletionProvider extends AbstractCompletionProvider {
  private static final OpenSearchPPLParser GRAMMAR =
      new OpenSearchPPLParser(
          new CommonTokenStream(new OpenSearchPPLLexer(new CaseInsensitiveCharStream(""))));
  private static final Set<Integer> PREFERRED_RULES =
      java.util.stream.Stream.of(
              "fieldExpression",
              "qualifiedName",
              "tableQualifiedName",
              "wcQualifiedName",
              "ident",
              "numericLiteral",
              "integerLiteral",
              "decimalLiteral",
              "doubleLiteral",
              "floatLiteral",
              "stringLiteral",
              "searchableKeyWord",
              "spanLiteral")
          .map(name -> java.util.Arrays.asList(OpenSearchPPLParser.ruleNames).indexOf(name))
          .filter(index -> index >= 0)
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
  private static final OpenSearchPPLLexer LEXER_GRAMMAR =
      new OpenSearchPPLLexer(new CaseInsensitiveCharStream(""));
  private static final AntlrGrammarAssignments ASSIGNMENTS =
      new AntlrGrammarAssignments(GRAMMAR, LEXER_GRAMMAR);
  private static final Map<Integer, List<String>> SPELLINGS =
      new java.util.concurrent.ConcurrentHashMap<>();
  private static final Map<Integer, Set<AntlrCompletionSuggester.TokenCandidate>> FUNCTION_HEADS =
      new java.util.concurrent.ConcurrentHashMap<>();
  private static final Set<String> COMPARISONS =
      Set.of("=", "==", "!=", "<>", ">", ">=", "<", "<=", "like", "ilike", "regexp", "contains");
  private static final Set<String> LOGICAL = Set.of("and", "or", "xor");

  @Override
  public String language() {
    return "ppl";
  }

  @Override
  public CompletionResponse complete(CompletionRequest request, CompletionMetadata metadata) {
    return new Session(request, metadata).complete();
  }

  private static final class Session extends CompletionSession {
    boolean sourceBoundary;
    final Set<String> constructors = new java.util.HashSet<>();
    LinkedHashMap<String, Field> fields = new LinkedHashMap<>();
    String sourceAlias;

    Session(CompletionRequest request, CompletionMetadata metadata) {
      this(request, metadata, scan(request.text()));
    }

    Session(CompletionRequest request, CompletionMetadata metadata, Scan scan) {
      super(request, metadata, scan, cursor(scan, request.text(), request.caret()));
    }

    CompletionResponse complete() {
      if (scan.tooDeep) return degraded("语句嵌套过深，暂无法提供上下文提示。");
      if (cursor == null) return response();
      List<Stage> stages = stages(scan, text, caret);
      if (stages.size() > 1 && stages.get(0).tokens.isEmpty())
        stages = stages.subList(1, stages.size());
      Stage current = stages.get(stages.size() - 1);
      try {
        if (stages.size() == 1) {
          boolean searchEntry =
              current.tokens.isEmpty()
                  || Set.of("source", "search", "index").contains(current.tokens.get(0).lower())
                  || current.tokens.size() == 1
                      && List.of("source", "search", "index").stream()
                          .anyMatch(name -> name.startsWith(current.tokens.get(0).lower()));
          Parsed source = parse(current.text(text), true);
          boolean beyondRelation = false;
          int fromIndex =
              !current.tokens.isEmpty() && current.tokens.getFirst().is("search") ? 1 : 0;
          if (current.tokens.size() > fromIndex
              && Set.of("source", "index").contains(current.tokens.get(fromIndex).lower())) {
            int fromStart = current.tokens.get(fromIndex).start;
            String fromText = text.substring(fromStart, caret);
            List<ParserRuleContext> relations =
                nodes(parse(fromText, OpenSearchPPLParser.RULE_fromClause).tree, "tableSource");
            if (!relations.isEmpty() && relations.getFirst().getStop() != null) {
              int[] positions = offsets(fromText);
              int stop = relations.getFirst().getStop().getStopIndex() + 1;
              if (stop > 0 && stop < positions.length) {
                int relationEnd = fromStart + positions[stop];
                beyondRelation =
                    current.tokens.stream().anyMatch(token -> token.start >= relationEnd);
              }
            }
          }
          // An unfinished branch such as "earliest =" may not survive parser error recovery.
          // Visible tokens after the parsed relation still establish explicit continuation intent.
          boolean advancedSource =
              beyondRelation
                  || !nodes(source.tree, "searchExpression").isEmpty()
                  || current.tokens.stream()
                      .anyMatch(token -> token.is(",") || token.is("[") || token.is("as"));
          if (searchEntry && !advancedSource) source(current);
          else {
            grammarStart = OpenSearchPPLParser.RULE_root;
            allowRootSearch = searchEntry;
            command(stages, current);
          }
        } else {
          command(stages, current);
        }
      } catch (MetadataException failure) {
        incomplete = true;
        message = "无法加载表或字段：" + failure.getMessage();
      }
      return response();
    }

    @Override
    CompletionResponse response() {
      if (!items.isEmpty() && !incomplete && message.equals("输入新的字段名或别名")) message = "";
      return super.response();
    }

    int completionPriority(Item item) {
      if (constructors.contains(item.label()) && item.kind().equals("function")
          || item.kind().equals("parameter")
          || item.kind().equals("value")
              && (item.label().matches("[0-9.]+") || Set.of("true", "false").contains(item.label()))
          || (sourceBoundary || grammarStart == OpenSearchPPLParser.RULE_root)
              && item.label().equals("|")) return 0;
      if (item.kind().equals("field") || item.kind().equals("table")) return 1;
      if (item.kind().equals("function")) return 4;
      return item.label().equals("|") ? 3 : 2;
    }

    CompletionResponse degraded(String reason) {
      incomplete = true;
      message = reason;
      return response();
    }

    void source(Stage stage) {
      List<Tok> tokens = stage.tokens;
      if (tokens.isEmpty()
          || tokens.size() == 1
              && !Set.of("source", "index", "search").contains(tokens.get(0).lower())) {
        keyword("source", "指定查询表或索引");
        keyword("search", "开始搜索");
        keyword("index", "指定查询索引");
        return;
      }
      int position = tokens.get(0).is("search") ? 1 : 0;
      if (position == tokens.size()) {
        insertion();
        keyword("source", "指定查询表或索引");
        keyword("index", "指定查询索引");
        return;
      }
      if (!Set.of("source", "index").contains(tokens.get(position).lower())) {
        keyword("source", "指定查询表或索引");
        keyword("index", "指定查询索引");
        return;
      }
      if (position + 1 == tokens.size()) {
        insertion();
        symbol("=", "指定表或索引", " = ");
        return;
      }
      if (!tokens.get(position + 1).is("=")) return;
      if (tokens.size() == position + 2) {
        insertion();
        tables(List.of(), "");
        return;
      }
      int valueStart = tokens.get(position + 2).start;
      String value = text.substring(valueStart, caret).stripTrailing();
      if (tokens.subList(position + 2, tokens.size()).stream()
          .anyMatch(token -> token.is("[") || token.is("(") || token.is(","))) {
        degraded("动态数据源、子查询和多表来源暂不推导字段。");
        return;
      }
      Parsed parsed = parse(text.substring(stage.start, caret), true);
      List<ParserRuleContext> names = nodes(parsed.tree, "tableSource");
      if (parsed.complete
          && names.size() == 1
          && (Character.isWhitespace(text.charAt(caret - 1)) || value.endsWith("`"))) {
        insertion();
        sourceContinuation(stage);
        return;
      }
      cursor = new Cursor(valueStart, identifierEnd(scan, valueStart, caret), unquote(value));
      List<String> parts = identifierParts(value);
      String prefix = parts.isEmpty() ? "" : parts.get(parts.size() - 1);
      List<String> namespace = parts.size() > 1 ? parts.subList(0, parts.size() - 1) : List.of();
      List<Relation> matches = metadata.tables(request.source(), namespace, prefix, 101);
      if (parsed.complete
          && caret >= cursor.to
          && matches.stream().anyMatch(relation -> relation.name().equalsIgnoreCase(prefix))) {
        insertion();
        sourceContinuation(stage);
        return;
      }
      for (Relation relation : matches) table(relation, namespace);
    }

    void sourceContinuation(Stage stage) {
      grammarStart = OpenSearchPPLParser.RULE_root;
      allowRootSearch = true;
      command(List.of(stage), stage);
    }

    void tables(List<String> namespace, String prefix) {
      for (Relation relation : metadata.tables(request.source(), namespace, prefix, 101))
        table(relation, namespace);
    }

    void table(Relation relation, List<String> namespace) {
      String name =
          namespace.isEmpty()
              ? relation.name()
              : String.join(".", namespace) + "." + relation.name();
      String inserted =
          namespace.stream().map(PplCompletionProvider::quote).reduce("", (a, b) -> a + b + ".")
              + quote(relation.name());
      add(
          name,
          "table",
          "pattern".equals(relation.kind()) ? "索引模式" : "表 / 索引",
          inserted + " ",
          false);
    }

    void loadSchema(List<Stage> stages) {
      Parsed source = parse(stages.get(0).text(text), true);
      List<ParserRuleContext> relations = nodes(source.tree, "tableSource");
      if (!source.complete
          || relations.size() != 1
          || !nodes(source.tree, "subSearch").isEmpty()
          || !nodes(source.tree, "tableFunction").isEmpty()) {
        degraded("当前来源较复杂或尚未完成，暂无法确定可用字段。");
        return;
      }
      List<String> names = identifierParts(relations.get(0).getText());
      if (names.isEmpty()) {
        degraded("尚未确定查询表或索引。");
        return;
      }
      RelationRef relation =
          new RelationRef(names.subList(0, names.size() - 1), names.get(names.size() - 1));
      for (Field field : metadata.fields(request.source(), relation))
        fields.put(pathKey(field.path()), field);
      List<ParserRuleContext> clauses = nodes(source.tree, "tableSourceClause");
      if (clauses.size() == 1) {
        List<ParserRuleContext> aliases = directNodes(clauses.get(0), "qualifiedName");
        if (aliases.size() == 1) sourceAlias = unquote(aliases.get(0).getText());
      }
      for (int index = 1; index < stages.size() - 1; index++) propagate(stages.get(index));
    }

    void propagate(Stage stage) {
      Parsed parsed = parse(stage.text(text), false);
      if (!parsed.complete || stage.tokens.isEmpty()) {
        unknownSchema("前序管道尚未完成，暂无法确定输出字段。");
        return;
      }
      String command = stage.tokens.get(0).lower();
      String commandRule =
          parsed.tree.getChild(0) instanceof ParserRuleContext context
              ? OpenSearchPPLParser.ruleNames[context.getRuleIndex()]
              : "";
      if (PplSemanticPolicy.preservesSchema(commandRule)) return;
      if (command.equals("fields") || command.equals("table")) {
        List<List<String>> selection =
            nodes(parsed.tree, "selectFieldExpression").stream()
                .map(node -> unalias(identifierParts(node.getText())))
                .toList();
        boolean exclude = stage.tokens.size() > 1 && stage.tokens.get(1).is("-");
        fields
            .entrySet()
            .removeIf(
                entry ->
                    exclude
                        == selection.stream()
                            .anyMatch(path -> selectedPath(path, entry.getValue().path())));
      } else if (command.equals("rename")) {
        for (ParserRuleContext clause : nodes(parsed.tree, "renameClause")) {
          List<ParserRuleContext> names = nodes(clause, "renameFieldExpression");
          if (names.size() != 2 || names.stream().anyMatch(node -> node.getText().contains("*"))) {
            unknownSchema("通配符重命名后的字段暂不推导。");
            return;
          }
          List<String> oldPath = unalias(identifierParts(names.get(0).getText()));
          List<String> newPath = identifierParts(names.get(1).getText());
          List<Field> renamed = new ArrayList<>();
          fields
              .entrySet()
              .removeIf(
                  entry -> {
                    Field field = entry.getValue();
                    if (!selectedPath(oldPath, field.path())) return false;
                    List<String> path = new ArrayList<>(newPath);
                    path.addAll(field.path().subList(oldPath.size(), field.path().size()));
                    renamed.add(new Field(String.join(".", path), field.type(), path));
                    return true;
                  });
          for (Field field : renamed) fields.put(pathKey(field.path()), field);
        }
      } else if (command.equals("eval") || command.equals("fieldformat")) {
        for (ParserRuleContext clause :
            nodes(parsed.tree, command.equals("eval") ? "evalClause" : "fieldFormatEvalClause")) {
          List<ParserRuleContext> targets = directNodes(clause, "fieldExpression");
          if (!targets.isEmpty()) {
            List<String> path = identifierParts(targets.get(0).getText());
            String name = String.join(".", path);
            List<ParserRuleContext> expressions =
                directNodes(
                    clause, command.equals("eval") ? "logicalExpression" : "ffLogicalExpression");
            String type = expressions.isEmpty() ? "unknown" : expressionType(expressions.get(0));
            fields.put(pathKey(path), new Field(name, type, path));
          }
        }
      } else if (command.equals("convert")) {
        for (ParserRuleContext conversion : nodes(parsed.tree, "convertFunction")) {
          var call = (OpenSearchPPLParser.ConvertFunctionContext) conversion;
          Field input = field(call.fieldExpression(0).getText());
          if (input == null) {
            unknownSchema("无法确定 convert 的输入字段。");
            return;
          }
          List<String> path =
              call.alias == null ? input.path() : identifierParts(call.alias.getText());
          String type =
              call.functionName.getText().equalsIgnoreCase("none") ? input.type() : "unknown";
          fields.put(pathKey(path), new Field(String.join(".", path), type, path));
        }
      } else if (Set.of("mvexpand", "mvcombine", "nomv").contains(command)) {
        for (ParserRuleContext reference : nodes(parsed.tree, "fieldExpression")) {
          Field input = field(reference.getText());
          if (input != null)
            fields.put(pathKey(input.path()), new Field(input.name(), "unknown", input.path()));
        }
      } else if (command.equals("bin")) {
        var bin = (OpenSearchPPLParser.BinCommandContext) nodes(parsed.tree, "binCommand").get(0);
        Field input = field(bin.fieldExpression().getText());
        if (input == null) {
          unknownSchema("无法确定 bin 字段的类型。");
          return;
        }
        String name = bin.alias == null ? input.name() : unquote(bin.alias.getText());
        String span =
            bin.binOption().stream()
                .filter(option -> option.span != null)
                .map(option -> option.span.getText())
                .findFirst()
                .orElse("");
        // Numeric buckets are range strings. Month buckets also return strings; other time
        // spans return timestamps. AS adds/overrides its output and preserves the source field.
        String type =
            temporal(input) && !span.isEmpty()
                ? span.matches(".*(?:M|(?i:mon|months?))$") ? "varchar" : "timestamp"
                : "varchar";
        List<List<String>> replaced =
            fields.values().stream()
                .filter(
                    field ->
                        field.path().size() == 1
                            && (field.name().equals(name) || name.startsWith(field.name() + ".")))
                .map(Field::path)
                .toList();
        fields
            .entrySet()
            .removeIf(
                entry ->
                    entry.getValue().name().equals(name)
                        || replaced.stream()
                            .anyMatch(path -> selectedPath(path, entry.getValue().path())));
        Field output = new Field(name, type);
        fields.put(pathKey(output.path()), output);
      } else if (Set.of("stats", "eventstats", "streamstats").contains(command)) {
        LinkedHashMap<String, Field> output = new LinkedHashMap<>();
        for (ParserRuleContext by : nodes(parsed.tree, "statsByClause")) {
          var grouping = (OpenSearchPPLParser.StatsByClauseContext) by;
          // Window commands partition by span without projecting the partition expression.
          if (command.equals("stats") && grouping.bySpanClause() != null) {
            var bucket = grouping.bySpanClause();
            var span = bucket.spanClause();
            String name =
                bucket.alias == null
                    ? span.getText()
                    : StringUtils.unquoteIdentifier(bucket.alias.getText());
            Field input =
                field(
                    span.fieldExpression() == null
                        ? "@timestamp"
                        : span.fieldExpression().getText());
            // The execution function preserves the input type for time units, including strings;
            // numeric intervals may widen it, so retain only the numeric category here.
            String type =
                input == null
                    ? "unknown"
                    : span.value.SPANLENGTH() != null ? input.type() : "number";
            output.put(pathKey(List.of(name)), new Field(name, type));
          }
          for (ParserRuleContext field :
              grouping.fieldList() == null
                  ? List.<ParserRuleContext>of()
                  : nodes(grouping.fieldList(), "fieldExpression")) {
            List<String> path = unalias(identifierParts(field.getText()));
            String name = String.join(".", path);
            output.put(
                pathKey(path),
                fields.getOrDefault(pathKey(path), new Field(name, "unknown", path)));
          }
        }
        String term =
            command.equals("stats")
                ? "statsAggTerm"
                : command.equals("eventstats") ? "eventstatsAggTerm" : "streamstatsAggTerm";
        for (ParserRuleContext aggregate : nodes(parsed.tree, term)) {
          List<ParserRuleContext> aliases = directNodes(aggregate, "wcFieldExpression");
          String name =
              aliases.isEmpty()
                  ? aggregate.getChild(0).getText()
                  : unquote(aliases.get(0).getText());
          output.put(pathKey(List.of(name)), new Field(name, aggregateType(aggregate)));
        }
        if (command.equals("stats")) {
          fields.clear();
          sourceAlias = null;
        }
        fields.putAll(output);
      } else if (Set.of(
              PplSemanticPolicy.SchemaEffect.EXTEND, PplSemanticPolicy.SchemaEffect.EXTRACT)
          .contains(PplSemanticPolicy.schemaEffect(commandRule))) {
        fields.replaceAll((key, field) -> new Field(field.name(), "unknown", field.path()));
        degraded("此命令可能新增或覆盖字段；当前仅提示已确定保留的输入字段名。");
      } else unknownSchema("此管道命令的输出字段暂不推导，请显式输入字段。");
    }

    String expressionType(ParserRuleContext expression) {
      String value = expression.getText();
      if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return "boolean";
      Field referenced = field(value);
      if (referenced != null) return referenced.type() == null ? "unknown" : referenced.type();
      if (expression instanceof OpenSearchPPLParser.BinaryArithmeticContext binary) {
        return PplSuggestionContext.family(expressionType(binary.left))
                    == CompletionTypePolicy.ValueFamily.NUMBER
                && PplSuggestionContext.family(expressionType(binary.right))
                    == CompletionTypePolicy.ValueFamily.NUMBER
            ? "number"
            : "unknown";
      }
      if (expression instanceof OpenSearchPPLParser.DataTypeFunctionCallContext cast) {
        String type = cast.convertedDataType().getText().toLowerCase(Locale.ROOT);
        return type.equals("json") ? "any" : type;
      }
      if (expression instanceof OpenSearchPPLParser.NestedValueExprContext nested)
        return expressionType(nested.logicalExpression());
      if (expression instanceof OpenSearchPPLParser.EvalFunctionCallContext call)
        return CompletionFunctionMetadata.returnType(
            call.evalFunctionName().getText(),
            call.functionArgs().functionArg().stream().map(this::expressionType).toList());
      // The current execution AST visits ANSI datetime literals as their string child. Only an
      // explicit CAST or a function with a known return type establishes a temporal value here.
      if (Set.of(
              OpenSearchPPLParser.RULE_dateLiteral,
              OpenSearchPPLParser.RULE_timeLiteral,
              OpenSearchPPLParser.RULE_timestampLiteral)
          .contains(expression.getRuleIndex())) return "string";
      if (expression instanceof OpenSearchPPLParser.CompareExprContext
          || expression instanceof OpenSearchPPLParser.InExprContext
          || expression instanceof OpenSearchPPLParser.BetweenContext
          || expression instanceof OpenSearchPPLParser.IsNullPredicateContext
          || expression instanceof OpenSearchPPLParser.RelevanceExprContext
          || expression instanceof OpenSearchPPLParser.ExistsSubqueryExprContext
          || expression instanceof OpenSearchPPLParser.InSubqueryExprContext
          || expression instanceof OpenSearchPPLParser.LogicalAndContext
          || expression instanceof OpenSearchPPLParser.LogicalOrContext
          || expression instanceof OpenSearchPPLParser.LogicalXorContext
          || expression instanceof OpenSearchPPLParser.LogicalNotContext) return "boolean";
      if (expression.getRuleIndex() == OpenSearchPPLParser.RULE_integerLiteral) return "long";
      if (Set.of(
              OpenSearchPPLParser.RULE_decimalLiteral,
              OpenSearchPPLParser.RULE_doubleLiteral,
              OpenSearchPPLParser.RULE_floatLiteral)
          .contains(expression.getRuleIndex())) return "double";
      List<Tok> tokens = scan(value).visible;
      if (tokens.size() == 1 && literal(tokens.get(0))) {
        String symbol = OpenSearchPPLLexer.VOCABULARY.getSymbolicName(tokens.get(0).type);
        if (symbol.contains("QUOTA")) return "string";
        if (symbol.contains("INTEGER")) return "long";
        if (symbol.equals("NULL")) return "unknown";
        return "double";
      }
      if (expression.getChildCount() == 1
          && expression.getChild(0) instanceof ParserRuleContext child)
        return expressionType(child);
      return "unknown";
    }

    String aggregateType(ParserRuleContext aggregate) {
      ParserRuleContext call = (ParserRuleContext) aggregate.getChild(0);
      if (call instanceof OpenSearchPPLParser.CountAllFunctionCallContext
          || call instanceof OpenSearchPPLParser.CountEvalFunctionCallContext
          || call instanceof OpenSearchPPLParser.DistinctCountFunctionCallContext) return "long";
      List<ParserRuleContext> arguments = nodes(call, "functionArgs");
      List<String> types;
      if (!arguments.isEmpty())
        types =
            ((OpenSearchPPLParser.FunctionArgsContext) arguments.getFirst())
                .functionArg().stream().map(this::expressionType).toList();
      else {
        var expressions = nodes(call, "valueExpression");
        if (!expressions.isEmpty()) types = List.of(expressionType(expressions.getFirst()));
        else {
          var references = nodes(call, "fieldExpression");
          types = references.isEmpty() ? List.of() : List.of(expressionType(references.getFirst()));
        }
      }
      String name = call.getText().split("\\(", 2)[0];
      return CompletionFunctionMetadata.returnType(name, types);
    }

    void unknownSchema(String reason) {
      fields.clear();
      incomplete = true;
      message = reason;
    }

    boolean schemaLoaded;
    boolean joinScopeBound;
    int grammarStart = OpenSearchPPLParser.RULE_commands;
    boolean allowRootSearch;
    List<Stage> activeStages;

    void command(List<Stage> stages, Stage stage) {
      Parsed parsed = parse(stage.text(text), grammarStart);
      String commandRule = firstCommandRule(parsed.tree);
      if (!nodes(parsed.tree, "explainStatement").isEmpty()) commandRule = "explainStatement";
      String unsupported = unsupportedReason(commandRule);
      if (!unsupported.isEmpty()) {
        degraded(unsupported);
        return;
      }
      activeStages = stages;
      if (completeSourceBoundary(stage)) return;
      if (completeCompositeToken(stage)) return;
      if (completeParameterKey(stage)) return;
      qualifiedCursor();
      List<Tok> before = stage.tokens.stream().filter(token -> token.end <= cursor.from).toList();
      var analysis = candidates(before);
      List<String> words = before.stream().map(Tok::text).toList();
      boolean references =
          analysis.rules().stream()
              .anyMatch(
                  rule -> {
                    var role = PplSemanticPolicy.referenceRole(ruleNames(rule.ruleStack()), words);
                    return role == PplSemanticPolicy.ReferenceRole.FIELD;
                  });
      boolean lookup =
          analysis.rules().stream()
              .anyMatch(
                  rule ->
                      PplSemanticPolicy.referenceRole(ruleNames(rule.ruleStack()), words)
                          == PplSemanticPolicy.ReferenceRole.LOOKUP_FIELD);
      if (references) ensureSchema(analysis);
      if (references && ruleContext(analysis).contains("joinCriteria")) bindJoinScope(stage);
      if (lookup) {
        fields.clear();
        for (Field field : lookupFields(stage)) fields.put(pathKey(field.path()), field);
        sourceAlias = null;
      }
      Tok word =
          scan.visible.stream()
              .filter(token -> token.start >= cursor.from && token.end == caret)
              .reduce((a, b) -> b)
              .orElse(null);
      boolean writable =
          analysis.rules().stream()
              .anyMatch(
                  rule ->
                      PplSemanticPolicy.referenceRole(ruleNames(rule.ruleStack()), words)
                          == PplSemanticPolicy.ReferenceRole.OUTPUT_NAME);
      boolean exactToken =
          word != null
              && analysis.tokens().stream()
                  .anyMatch(candidate -> candidate.tokenType() == word.type)
              && tokenSpellings(word.type).stream()
                  .anyMatch(label -> label.equalsIgnoreCase(word.text))
              && analysis.tokens().stream()
                  .flatMap(candidate -> tokenSpellings(candidate.tokenType()).stream())
                  .noneMatch(
                      label ->
                          label.length() > cursor.prefix.length()
                              && label.startsWith(cursor.prefix.toLowerCase(Locale.ROOT)));
      boolean exactField =
          (references || lookup)
              && cursor.from < caret
              && field(text.substring(cursor.from, caret)) != null
              && (text.charAt(caret - 1) == '`'
                  || fields.values().stream()
                      .noneMatch(
                          candidate ->
                              candidate.name().length() > cursor.prefix.length()
                                  && candidate.name().startsWith(cursor.prefix)));
      boolean exactPatternValue =
          word != null
              && word.type == OpenSearchPPLLexer.LOG_WITH_BASE
              && ruleContext(analysis).contains("logSpanValue");
      boolean explicitAlias = writable && !before.isEmpty() && last(before).is("as");
      if (caret >= cursor.to
          && cursor.from < caret
          && (exactToken
              || exactField
              || exactPatternValue
              || writable && (!references && !lookup || explicitAlias))) {
        insertion();
        before = stage.tokens;
        analysis = candidates(before);
      }
      // A bare number is an unfinished time span, even though spanLiteral also admits numbers.
      if (!before.isEmpty()
          && last(before).end == caret
          && last(before).text.matches("[1-9][0-9]*")) {
        List<Tok> prefix = before.subList(0, before.size() - 1);
        var atValue = candidates(prefix);
        if (atValue.rules().stream()
            .anyMatch(
                rule -> OpenSearchPPLParser.ruleNames[rule.ruleIndex()].equals("spanLiteral"))) {
          if (!knownTimeSpan(atValue)) ensureSchema(atValue);
          if (timeSpanContext(atValue, prefix)) {
            cursor =
                new Cursor(
                    last(before).start,
                    identifierEnd(scan, last(before).start, caret),
                    last(before).text);
            before = prefix;
            analysis = atValue;
          }
        }
      }
      renderGrammar(stage, before, analysis);
    }

    boolean completeParameterKey(Stage stage) {
      if (stage.tokens.isEmpty()) return false;
      Tok key = last(stage.tokens);
      if (key.end > caret || cursor.to > caret || key.text.startsWith("`")) return false;
      var previous = candidates(stage.tokens.subList(0, stage.tokens.size() - 1));
      boolean declared =
          tokenSpellings(key.type).stream().anyMatch(key.text::equalsIgnoreCase)
              && previous.tokens().stream()
                  .anyMatch(
                      candidate ->
                          candidate.tokenType() == key.type
                              && ASSIGNMENTS.isKey(key.type, candidate.ruleStack()));
      boolean dynamic =
          previous.rules().stream()
              .anyMatch(
                  candidate -> dynamicParameter(ruleNames(candidate.ruleStack()), key.lower()));
      if (!declared && !dynamic) return false;
      if (candidates(stage.tokens).tokens().stream()
          .noneMatch(candidate -> candidate.tokenType() == OpenSearchPPLLexer.EQUAL)) return false;
      insertion();
      symbol("=", "设置参数值", " = ");
      return true;
    }

    boolean dynamicParameter(List<String> rules, String name) {
      if (rules.contains("foreachOption"))
        return java.util.Arrays.stream(org.opensearch.sql.ast.tree.Foreach.Option.values())
            .map(option -> option.toString().equals("matchseg") ? "matchseg1" : option.toString())
            .anyMatch(name::equals);
      return rules.contains("mlArg")
          && metadata.parameterNames(request.source(), "machine_learning").contains(name);
    }

    /** A completed source is a pipeline boundary until the user starts another search branch. */
    boolean completeSourceBoundary(Stage stage) {
      List<Integer> openings = new ArrayList<>();
      for (Tok token : scan.visible) {
        if (token.start >= caret) break;
        if (token.is("[")) openings.add(token.end);
        else if (token.is("]") && !openings.isEmpty()) openings.remove(openings.size() - 1);
      }
      boolean nested = !openings.isEmpty();
      if (!nested && grammarStart != OpenSearchPPLParser.RULE_root) return false;
      List<Stage> scope =
          nested ? stages(scan, text, caret, openings.get(openings.size() - 1)) : List.of(stage);
      if (scope.size() != 1 || scope.getFirst().tokens.isEmpty()) return false;
      Parsed parsed = parse(scope.getFirst().text(text), true);
      List<ParserRuleContext> searches = nodes(parsed.tree, "searchCommand");
      if (!parsed.complete
          || !firstCommandRule(parsed.tree).equals("searchCommand")
          || searches.isEmpty()
          || !(searches.getFirst() instanceof OpenSearchPPLParser.SearchFromContext search)
          || !search.searchExpression().isEmpty()
          || search.fromClause().tableOrSubqueryClause() == null) return false;

      var relation = search.fromClause().tableOrSubqueryClause();
      boolean aliased =
          relation.alias != null
              || relation.tableSourceClause() != null && relation.tableSourceClause().alias != null;
      boolean committed =
          cursor.from == caret && cursor.prefix.isEmpty()
              || Character.isWhitespace(text.charAt(caret - 1))
              || text.charAt(caret - 1) == '`'
              || aliased;
      if (!committed) return false;

      var analysis = candidates(stage.tokens);
      boolean pipeAllowed =
          analysis.tokens().stream()
              .anyMatch(
                  candidate ->
                      candidate.tokenType() == Token.EOF
                          || candidate.tokenType() == OpenSearchPPLLexer.PIPE);
      if (!pipeAllowed) return false;
      // This profile is deliberately applied before references/ensureSchema. A completed source
      // must neither fetch fields nor flood the menu with every optional search-expression start.
      sourceBoundary = true;
      insertion();
      pipe();
      if (nested
          && analysis.tokens().stream()
              .anyMatch(candidate -> candidate.tokenType() == OpenSearchPPLLexer.RT_SQR_PRTHS))
        symbol("]", "结束子查询", "]");
      return true;
    }

    /** EDGE_CLAUSE is a single lexer token; its field slots need catalog-aware value completion. */
    boolean completeCompositeToken(Stage stage) {
      Tok edge =
          stage.tokens.stream()
              .filter(
                  token ->
                      token.type == OpenSearchPPLLexer.EDGE
                          || token.type == OpenSearchPPLLexer.EDGE_CLAUSE)
              .reduce((left, right) -> right)
              .orElse(null);
      if (edge == null) return false;
      List<Tok> preceding = stage.tokens.stream().filter(token -> token.end <= edge.start).toList();
      if (candidates(preceding).tokens().stream()
          .noneMatch(candidate -> candidate.tokenType() == OpenSearchPPLLexer.EDGE_CLAUSE))
        return false;
      String fragment = text.substring(edge.start, caret);
      var match =
          Pattern.compile(
                  "(?i)edge\\s*=\\s*([a-z_@*][a-z_0-9*-]*)?(?:(-->|<->)\\s*([a-z_@*][a-z_0-9*-]*)?)?")
              .matcher(fragment);
      if (!match.matches()) return false;
      List<Field> related =
          lookupFields(stage).stream()
              .filter(
                  field ->
                      field.path().size() == 1
                          && field.name().matches("[A-Za-z_@*][A-Za-z_0-9-]*")
                          && !field.name().endsWith("-"))
              .toList();
      String direction = match.group(2);
      String prefix = match.group(direction == null ? 1 : 3);
      if (prefix == null) prefix = "";
      boolean exact = related.stream().map(Field::name).toList().contains(prefix);
      if (exact && direction != null && edge.type == OpenSearchPPLLexer.EDGE_CLAUSE) {
        insertion();
        return false;
      }
      if (exact && direction == null) {
        insertion();
        symbol("-->", "沿正向边查找", "-->");
        symbol("<->", "沿双向边查找", "<->");
        return true;
      }
      int from = prefix.isEmpty() ? caret : edge.start + match.start(direction == null ? 1 : 3);
      int to = caret;
      while (to < text.length() && Character.toString(text.charAt(to)).matches("[A-Za-z_0-9-]")) {
        if (text.startsWith("-->", to)) break;
        to++;
      }
      cursor = new Cursor(from, to, prefix);
      for (Field field : related)
        add(field.name(), "field", "关联表的边字段", field.name() + (direction == null ? "" : " "), false);
      return true;
    }

    AntlrCompletionSuggester.Analysis candidates(List<Tok> before) {
      var analysis =
          AntlrCompletionSuggester.suggest(
              GRAMMAR, grammarStart, before.stream().map(Tok::type).toList(), PREFERRED_RULES);
      if (grammarStart != OpenSearchPPLParser.RULE_root || allowRootSearch) return analysis;
      // Apply the entrypoint profile before resolving metadata, not only while rendering. Otherwise
      // an advanced command keyword can also be interpreted as a bare search expression and cause
      // a parameter such as UNION MAXOUT to be queried as a table.
      return new AntlrCompletionSuggester.Analysis(
          analysis.tokens().stream()
              .filter(
                  candidate ->
                      !firstCommand(ruleNames(candidate.ruleStack())).equals("searchCommand"))
              .toList(),
          analysis.rules().stream()
              .filter(
                  candidate ->
                      !firstCommand(ruleNames(candidate.ruleStack())).equals("searchCommand"))
              .toList(),
          analysis.incomplete());
    }

    void ensureSchema(AntlrCompletionSuggester.Analysis analysis) {
      if (schemaLoaded) return;
      schemaLoaded = true;
      List<Stage> scope = activeStages;
      List<String> context = ruleContext(analysis);
      boolean subSearch = context.contains("subSearch");
      boolean subPipeline = context.contains("subPipeline") || context.contains("appendcolCommand");
      List<Stage> innerPipeline = null;
      {
        List<Integer> openings = new ArrayList<>();
        for (Tok token : scan.visible) {
          if (token.start >= caret) break;
          if (token.is("[")) openings.add(token.end);
          else if (token.is("]") && !openings.isEmpty()) openings.remove(openings.size() - 1);
        }
        if (!openings.isEmpty()) {
          var inner = stages(scan, text, caret, openings.get(openings.size() - 1));
          boolean ownSource =
              !nodes(parse(inner.get(0).text(text), true).tree, "fromClause").isEmpty();
          if (subSearch || ownSource) scope = inner;
          else if (subPipeline) innerPipeline = inner;
        }
      }
      try {
        loadSchema(scope);
        if (innerPipeline != null)
          for (int index = 0; index < innerPipeline.size() - 1; index++)
            propagate(innerPipeline.get(index));
      } catch (MetadataException failure) {
        degraded("无法加载字段：" + failure.getMessage());
      }
    }

    void bindJoinScope(Stage stage) {
      if (joinScopeBound) return;
      int end = text.length(), nesting = 0;
      for (Tok token : scan.visible) {
        if (token.start < stage.start) continue;
        if (token.is("[")) nesting++;
        else if (token.is("]")) nesting--;
        else if (token.is("|") && nesting == 0 && token.start >= caret) {
          end = token.start;
          break;
        }
      }
      Parsed parsed = parse(text.substring(stage.start, end), false);
      var joins = nodes(parsed.tree, "joinCommand");
      if (joins.isEmpty()) return;
      var join = (OpenSearchPPLParser.JoinCommandContext) joins.get(joins.size() - 1);
      if (join.sideAlias() == null) return;
      joinScopeBound = true;
      String left =
          join.sideAlias().leftAlias == null
              ? sourceAlias
              : unquote(join.sideAlias().leftAlias.getText());
      String right =
          join.sideAlias().rightAlias == null
              ? null
              : unquote(join.sideAlias().rightAlias.getText());
      List<Field> visible =
          fields.values().stream()
              .map(field -> qualify(field, left))
              .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
      List<ParserRuleContext> relations =
          join.right == null ? List.of() : nodes(join.right, "tableSource");
      boolean rightStarted =
          join.right != null
              && join.joinCriteria() != null
              && join.joinCriteria().getStop() != null
              && join.right.getStart().getStartIndex()
                  > join.joinCriteria().getStop().getStopIndex()
              && !join.right.getText().startsWith(".");
      if (rightStarted && relations.size() == 1 && nodes(join.right, "subSearch").isEmpty()) {
        List<String> path = identifierParts(relations.get(0).getText());
        try {
          for (Field field :
              metadata.fields(
                  request.source(),
                  new RelationRef(path.subList(0, path.size() - 1), path.get(path.size() - 1))))
            visible.add(qualify(field, right));
        } catch (MetadataException error) {
          degraded("无法加载 JOIN 右表字段：" + error.getMessage());
        }
      } else if (rightStarted) degraded("JOIN 右侧派生表的字段暂无法确定。");
      fields.clear();
      for (Field field : visible) fields.put(pathKey(field.path()), field);
      sourceAlias = null;
    }

    Field qualify(Field field, String alias) {
      if (alias == null) return field;
      List<String> path = new ArrayList<>(List.of(alias));
      path.addAll(field.path());
      return new Field(alias + "." + field.name(), field.type(), path);
    }

    void renderGrammar(Stage stage, List<Tok> before, AntlrCompletionSuggester.Analysis analysis) {
      if (analysis.incomplete()) degraded("语句较复杂，当前补全结果不完整。");
      List<String> words = before.stream().map(Tok::text).toList();
      Stage localStage = currentScope(stage);
      Parsed local = parse(localStage.text(text), false);
      boolean where = !localStage.tokens.isEmpty() && localStage.tokens.getFirst().is("where");
      List<Tok> localBefore =
          before.stream().filter(token -> token.start >= localStage.start).toList();
      int logic = lastIndex(localBefore, token -> LOGICAL.contains(token.lower()) || token.is("("));
      List<Tok> predicate =
          localBefore.subList(
              Math.max(
                  logic + 1,
                  where && !localBefore.isEmpty() && localBefore.get(0).is("where") ? 1 : 0),
              localBefore.size());
      boolean comparison = predicate.stream().anyMatch(token -> COMPARISONS.contains(token.text));
      // A completed identifier no longer requests a field rule. Its operators still need the
      // field type, including after whitespace and in calculation/function-argument positions.
      boolean typedContinuation =
          !before.isEmpty()
              && !localStage.tokens.isEmpty()
              && last(before).start > localStage.tokens.getFirst().start
              && identifierToken(last(before))
              && !literal(last(before))
              && analysis.tokens().stream()
                  .anyMatch(
                      candidate -> {
                        List<String> rules = ruleNames(candidate.ruleStack());
                        return (rules.contains("valueExpression") || rules.contains("expression"))
                            && PplSemanticPolicy.referenceRole(rules, words)
                                != PplSemanticPolicy.ReferenceRole.OUTPUT_NAME;
                      });
      if (typedContinuation) ensureSchema(analysis);
      // Operators alone do not request a field rule. Resolve the preceding reference as well so
      // a trailing space cannot change whether a non-boolean field completes a predicate.
      if (where
          && !comparison
          && !predicate.isEmpty()
          && predicate.stream().allMatch(token -> identifierToken(token) || token.is(".")))
        ensureSchema(analysis);
      Set<Integer> valueRules = new java.util.LinkedHashSet<>();
      if (before.size() >= 2 && last(before).is("=")) {
        int parameter = before.get(before.size() - 2).type;
        java.util.stream.Stream.concat(
                analysis.rules().stream().flatMap(rule -> rule.ruleStack().stream()),
                analysis.tokens().stream().flatMap(token -> token.ruleStack().stream()))
            .distinct()
            .forEach(rule -> valueRules.addAll(ASSIGNMENTS.valueRules(parameter, rule)));
      }
      PplSuggestionContext guidance =
          suggestionContext(localStage, local, before, predicate, analysis, valueRules);
      if (!incomplete && !guidance.inputHint().isEmpty()) message = guidance.inputHint();
      if (guidance.uncertainReference()) incomplete = true;
      int opening = lastUnclosed(before);
      String call = opening > 0 ? before.get(opening - 1).lower() : "";
      int argumentCount = 1, depth = 0;
      for (int index = opening + 1; opening >= 0 && index < before.size(); index++) {
        if (before.get(index).is("(")) depth++;
        else if (before.get(index).is(")")) depth--;
        else if (depth == 0 && before.get(index).is(",")) argumentCount++;
      }
      List<ParserRuleContext> sortFields =
          ruleContext(analysis).contains("sortField")
              ? nodes(parse(stage.text(text), false).tree, "sortField")
              : List.of();
      boolean signedSortField =
          !sortFields.isEmpty()
              && Set.of("+", "-")
                  .contains(sortFields.get(sortFields.size() - 1).getStart().getText());
      for (var spelling : spelledCandidates(analysis)) {
        var candidate = spelling.candidate();
        List<String> rules = ruleNames(candidate.ruleStack());
        if (!valueRules.isEmpty() && candidate.ruleStack().stream().noneMatch(valueRules::contains))
          continue;
        if (rules.stream().anyMatch(rule -> !unsupportedReason(rule).isEmpty())) continue;
        int type = candidate.tokenType();
        if (type == Token.EOF) {
          if (cursor.prefix.isEmpty() && guidance.token("|", false)) pipe();
          continue;
        }
        String label = spelling.text();
        if (rules.contains("foreachOption")
            && !before.isEmpty()
            && last(before).is("=")
            && assignedParameter(before).equals("mode")) continue;
        if (rules.contains("mlArg") && !before.isEmpty() && last(before).is("=")) {
          message = "输入后端参数值；字符串需要加引号";
          continue;
        }
        if (label == null) {
          String symbolic = OpenSearchPPLLexer.VOCABULARY.getSymbolicName(type);
          if ("LOG_WITH_BASE".equals(symbolic)) {
            String name =
                cursor.prefix.matches("(?i)log[0-9]+(?:\\.[0-9]+)?") ? cursor.prefix : "log10";
            if (rules.contains("logSpanValue")) {
              ensureSchema(analysis);
              if (!timeSpanContext(analysis, before) && guidance.literalExamples())
                value(name, "对数步长");
            } else if (guidance.functions()) function(name, "对数函数");
          } else if ("PERCENTILE_SHORTCUT".equals(symbolic)) {
            String name =
                cursor.prefix.matches("(?i)(p|perc)[0-9]+(?:\\.[0-9]+)?")
                    ? cursor.prefix
                    : cursor.prefix.toLowerCase(Locale.ROOT).startsWith("perc") ? "perc95" : "p95";
            if (guidance.functions()) function(name, "百分位聚合");
          } else if ("EDGE_CLAUSE".equals(symbolic)) {
            add("edge=", "parameter", "关联表的边字段：edge=fromField-->toField", "edge=", false);
            message = "填写关联表的边字段，例如 edge=parent-->child";
          }
          continue;
        }
        String functionRule = nearestFunctionRule(rules);
        boolean function = isFunctionHead(candidate, functionRule);
        boolean requiredFunctionSyntax =
            functionRule != null
                && rules.getLast().equals(functionRule)
                && Set.of("as", "from", "in", "else").contains(label);
        boolean fieldConstructor =
            function
                && guidance.slot() == PplSuggestionContext.Slot.FIELD
                && !rules.contains("functionCall")
                && !rules.contains("relevanceExpression");
        if (fieldConstructor) constructors.add(label);
        boolean expressionToken =
            rules.stream()
                .anyMatch(
                    rule ->
                        Set.of(
                                "expression",
                                "valueExpression",
                                "logicalExpression",
                                "fieldExpression",
                                "wcFieldExpression",
                                "renameFieldExpression",
                                "selectFieldExpression",
                                "qualifiedName",
                                "wcQualifiedName",
                                "ident")
                            .contains(rule));
        if (!requiredFunctionSyntax
            && !guidance.token(label, function && !fieldConstructor, expressionToken)) continue;
        boolean parameter = ASSIGNMENTS.isKey(type, candidate.ruleStack());
        if (label.equals("=")
            && rules.contains("foreachOption")
            && !before.isEmpty()
            && !dynamicParameter(rules, last(before).lower())) continue;
        if (rules.contains("aligntimeValue") && !Set.of("earliest", "latest").contains(label))
          continue;
        if (opening > 0
            && label.equals(",")
            && "statsFunction".equals(functionRule)
            && !CompletionFunctionMetadata.canAcceptMoreArguments(call, argumentCount)) continue;
        if (label.equals(")")
            && "evalFunctionCall".equals(functionRule)
            && opening > 0
            && before.size() == opening + 1
            && !CompletionFunctionMetadata.canCloseWithArguments(call, 0)) continue;
        if (rules.contains("sortField")
            && Set.of("asc", "desc", "a", "d").contains(label)
            && signedSortField) continue;
        if (parameter && assigned(before, label)) continue;
        if (rules.contains("binOption") && parameter && !binParameterAllowed(stage, label))
          continue;
        if (label.equals("true")
            && rules.contains("dedupCommand")
            && assignedParameter(before).equals("consecutive")) continue;
        if (before.isEmpty()) add(label, "command", "管道命令", label + " ", false);
        else if (Set.of("true", "false").contains(label)
            && guidance.slot() == PplSuggestionContext.Slot.COMPARISON_VALUE) value(label, "布尔值");
        else if (function && Character.isLetter(label.charAt(0))) function(label, "函数");
        else if (Character.isLetter(label.charAt(0))) {
          if (parameter) add(label, "parameter", "设置 " + label + " 参数", label + " ", true);
          else
            keyword(
                label,
                guidance.operatorDetail(label).isEmpty()
                    ? "语法关键字"
                    : guidance.operatorDetail(label));
        } else
          symbol(
              label,
              guidance.operatorDetail(label).isEmpty() ? "语法符号" : guidance.operatorDetail(label),
              label.equals(",")
                  ? ", "
                  : Set.of("(", ")", "[", "]", ".").contains(label) ? label : " " + label + " ");
      }
      for (var candidate : analysis.rules()) {
        List<String> rules = ruleNames(candidate.ruleStack());
        if (!valueRules.isEmpty() && candidate.ruleStack().stream().noneMatch(valueRules::contains))
          continue;
        if (rules.stream().anyMatch(rule -> !unsupportedReason(rule).isEmpty())) continue;
        var role = PplSemanticPolicy.referenceRole(rules, words);
        if (role == PplSemanticPolicy.ReferenceRole.TABLE) {
          if (guidance.slot() == PplSuggestionContext.Slot.PREDICATE_OPERATOR
              || guidance.slot() == PplSuggestionContext.Slot.RANGE_SEPARATOR) continue;
          qualifiedCursor();
          List<String> parts = identifierParts(text.substring(cursor.from, caret));
          tables(
              parts.size() > 1 ? parts.subList(0, parts.size() - 1) : List.of(),
              parts.get(parts.size() - 1));
        } else if (role == PplSemanticPolicy.ReferenceRole.OUTPUT_NAME) {
          if (items.isEmpty() && message.isEmpty()) message = "输入新的字段名或别名";
        } else if (role == PplSemanticPolicy.ReferenceRole.FIELD) {
          if (guidance.references()) {
            ensureSchema(analysis);
            suggestFields(
                fields.values().stream()
                    .filter(field -> PplSemanticPolicy.acceptsFieldType(rules, words, field.type()))
                    .toList(),
                sourceAlias);
          }
        } else if (role == PplSemanticPolicy.ReferenceRole.LOOKUP_FIELD) {
          if (guidance.references()) suggestLookupFields(stage);
        } else if (rules.contains("convertFunction")
            && rules.get(rules.size() - 1).equals("ident")) {
          if (guidance.functions())
            for (String name :
                org.opensearch.sql.ppl.parser.AstBuilder.supportedConversionFunctions().stream()
                    .sorted()
                    .toList()) function(name, "转换函数");
        } else if (rules.contains("mlArg")) {
          if (before.isEmpty() || !last(before).is("=")) {
            for (String name : metadata.parameterNames(request.source(), "machine_learning"))
              if (!assigned(before, name)) add(name, "parameter", "后端注册参数", name + " ", true);
          } else message = "输入后端参数值；字符串需要加引号";
        } else if (rules.contains("foreachOption")) {
          if (!before.isEmpty()
              && last(before).is("=")
              && assignedParameter(before).equals("mode")) {
            for (var mode : org.opensearch.sql.ast.tree.Foreach.Mode.values())
              keyword(mode.toString(), "遍历模式");
          } else if (before.isEmpty() || !last(before).is("=")) {
            for (var option : org.opensearch.sql.ast.tree.Foreach.Option.values())
              if (!assigned(before, option.toString())) {
                String name =
                    option.toString().equals("matchseg") ? "matchseg1" : option.toString();
                add(name, "parameter", "遍历参数", name + " ", true);
              }
          }
        } else if (guidance.literalExamples()) literalSuggestions(rules, before, analysis);
      }
      if (guidance.compositeReference() && !guidance.prefixed()) suggestRelatedFields(predicate);
      if (items.isEmpty() && !stage.tokens.isEmpty()) {
        for (String rule : ruleContext(analysis)) {
          String reason = unsupportedReason(rule);
          int index = java.util.Arrays.asList(OpenSearchPPLParser.ruleNames).indexOf(rule);
          if (!reason.isEmpty()
              && (rule.endsWith("Command") || rule.endsWith("Statement"))
              && GRAMMAR
                  .getATN()
                  .nextTokens(GRAMMAR.getATN().ruleToStartState[index])
                  .contains(stage.tokens.get(0).type)) {
            degraded(reason);
            break;
          }
        }
      }
    }

    Stage currentScope(Stage stage) {
      List<Integer> openings = new ArrayList<>();
      for (Tok token : stage.tokens) {
        if (token.is("[")) openings.add(token.end);
        else if (token.is("]") && !openings.isEmpty()) openings.remove(openings.size() - 1);
      }
      if (openings.isEmpty()) return stage;
      List<Stage> inner = stages(scan, text, caret, openings.getLast());
      return inner.getLast();
    }

    PplSuggestionContext suggestionContext(
        Stage localStage,
        Parsed local,
        List<Tok> before,
        List<Tok> predicate,
        AntlrCompletionSuggester.Analysis analysis,
        Set<Integer> valueRules) {
      List<String> words = before.stream().map(Tok::text).toList();
      boolean reference = false, output = false, operand = false, dynamicFunction = false;
      for (var candidate : analysis.rules()) {
        List<String> rules = ruleNames(candidate.ruleStack());
        var role = PplSemanticPolicy.referenceRole(rules, words);
        boolean field =
            role == PplSemanticPolicy.ReferenceRole.FIELD
                || role == PplSemanticPolicy.ReferenceRole.LOOKUP_FIELD;
        reference |= field;
        output |= role == PplSemanticPolicy.ReferenceRole.OUTPUT_NAME;
        operand |=
            field
                && rules.stream()
                    .anyMatch(
                        rule ->
                            Set.of(
                                    "logicalExpression",
                                    "valueExpression",
                                    "functionArgExpression",
                                    "expression")
                                .contains(rule));
        dynamicFunction |=
            role == PplSemanticPolicy.ReferenceRole.UNKNOWN
                && rules.getLast().equals("ident")
                && nearestFunctionRule(rules) != null;
      }
      boolean prefixed = !cursor.prefix.isEmpty();
      var slot = PplSuggestionContext.Slot.CONTINUATION;
      boolean parameterValue = !valueRules.isEmpty();
      List<String> context = ruleContext(analysis);
      boolean literalList =
          analysis.rules().stream()
              .anyMatch(
                  candidate -> {
                    List<String> path = ruleNames(candidate.ruleStack());
                    return path.contains("valueList")
                        || path.contains("searchLiteralList")
                        || path.contains("relevanceQuery");
                  });
      boolean comparisonValue =
          !before.isEmpty()
              && COMPARISONS.contains(last(before).lower())
              && operand
              && !assignmentEquals(localStage, local, last(before));
      ExpressionFocus focus = expressionFocus(localStage, before);
      if (focus != null && !nodes(focus.tree, "fieldExpression").isEmpty()) {
        ensureSchema(analysis);
        if (context.contains("joinCriteria")) bindJoinScope(localStage);
      }
      RangeFocus range = rangeFocus(before);
      Field bareReference = field(join(predicate));
      if (bareReference == null && predicate.size() > 1 && last(predicate).is("not"))
        bareReference = field(join(predicate.subList(0, predicate.size() - 1)));
      Field terminalReference =
          before.isEmpty()
                  || literal(last(before))
                  || localStage.tokens.isEmpty()
                  || last(before).start <= localStage.tokens.getFirst().start
              ? null
              : field(currentIdentifier(before));
      boolean bareAtom =
          !predicate.isEmpty()
              && predicate.stream()
                  .allMatch(token -> !literal(token) && (identifierToken(token) || token.is(".")));
      boolean predicateFinished =
          !bareAtom
              && (completePredicate(predicate)
                  || focus != null && booleanPredicateTree(focus.tree)
                  || !localStage.tokens.isEmpty()
                      && localStage.tokens.getFirst().is("where")
                      && local.complete
                      && booleanPredicateTree(local.tree));
      boolean predicateOperator =
          (focus != null
                  && focus.predicateRequired
                  && PplSuggestionContext.family(expressionType(focus.tree))
                      != CompletionTypePolicy.ValueFamily.BOOLEAN)
              || bareAtom
                  && bareReference != null
                  && !localStage.tokens.isEmpty()
                  && localStage.tokens.getFirst().is("where")
                  && !context.contains("functionCall")
                  && !context.contains("functionArgs")
                  && !context.contains("functionArgExpression")
                  && PplSuggestionContext.family(bareReference.type())
                      != CompletionTypePolicy.ValueFamily.BOOLEAN;
      boolean elementFinished =
          !before.isEmpty()
              && (literal(last(before))
                  || last(before).is(")")
                  || last(before).is("]")
                  || last(before).is("*")
                  || identifierToken(last(before)) && localStage.tokens.size() > 1);
      boolean hasListElement =
          java.util.stream.Stream.of(
                  "fieldExpression",
                  "selectFieldExpression",
                  "wcFieldExpression",
                  "renameFieldExpression")
              .anyMatch(rule -> !nodes(local.tree, rule).isEmpty());
      hasListElement |= field(currentIdentifier(before)) != null;
      boolean bodyBoundary =
          !before.isEmpty()
              && (field(currentIdentifier(before)) != null
                  || literal(last(before))
                  || last(before).is(")")
                  || last(before).is("*"))
              && analysis.tokens().stream()
                  .anyMatch(candidate -> candidate.tokenType() == OpenSearchPPLLexer.LT_SQR_PRTHS);
      if (parameterValue)
        slot =
            output && !reference
                ? PplSuggestionContext.Slot.OUTPUT_NAME
                : operand
                    ? PplSuggestionContext.Slot.OPERAND
                    : reference
                        ? PplSuggestionContext.Slot.FIELD
                        : PplSuggestionContext.Slot.PARAMETER_VALUE;
      else if (output && !before.isEmpty() && last(before).is("as"))
        slot = PplSuggestionContext.Slot.OUTPUT_NAME;
      else if (context.contains("nullNotnull")) slot = PplSuggestionContext.Slot.PARAMETER_VALUE;
      else if (range != null)
        slot =
            range.separator
                ? PplSuggestionContext.Slot.RANGE_SEPARATOR
                : PplSuggestionContext.Slot.COMPARISON_VALUE;
      else if (comparisonValue || literalList) slot = PplSuggestionContext.Slot.COMPARISON_VALUE;
      else if (predicateOperator) slot = PplSuggestionContext.Slot.PREDICATE_OPERATOR;
      else if (predicateFinished) slot = PplSuggestionContext.Slot.PREDICATE_END;
      else if (!operand
          && (local.complete || bodyBoundary)
          && elementFinished
          && (reference && hasListElement || dynamicFunction))
        slot = PplSuggestionContext.Slot.FIELD_LIST_END;
      else if (operand) slot = PplSuggestionContext.Slot.OPERAND;
      else if (reference) slot = PplSuggestionContext.Slot.FIELD;
      else if (output) slot = PplSuggestionContext.Slot.OUTPUT_NAME;
      Field typedOperand = bareReference != null ? bareReference : terminalReference;
      String type =
          typedOperand == null
              ? ""
              : typedOperand.type() == null
                  ? "unknown"
                  : typedOperand.type().toLowerCase(Locale.ROOT);
      if (slot == PplSuggestionContext.Slot.COMPARISON_VALUE) {
        ensureSchema(analysis);
        Field target = relevantField(range == null ? before : before.subList(0, range.start));
        if (target != null && target.type() != null) type = target.type().toLowerCase(Locale.ROOT);
      } else if (focus != null && !parameterValue && !predicateOperator) {
        type = expressionType(focus.tree);
      } else if (focus != null && predicateOperator && bareReference == null) {
        type = expressionType(focus.tree);
      }
      return new PplSuggestionContext(slot, prefixed, type, operand);
    }

    record RangeFocus(int start, boolean separator) {}

    RangeFocus rangeFocus(List<Tok> before) {
      int start = lastIndex(before, token -> token.is("between"));
      if (start < 0) return null;
      int separator = -1, depth = 0;
      for (int i = start + 1; i < before.size(); i++) {
        Tok token = before.get(i);
        if (token.is("(") || token.is("[")) depth++;
        else if (token.is(")") || token.is("]")) {
          if (--depth < 0) return null;
        } else if (depth == 0) {
          if (token.is("and") && separator < 0) separator = i;
          else if (LOGICAL.contains(token.lower()) || token.is(",") || token.is("|")) return null;
        }
      }
      if (start == before.size() - 1 || separator == before.size() - 1)
        return new RangeFocus(start, false);
      if (separator < 0) {
        Parsed lower =
            parse(
                text.substring(before.get(start + 1).start, last(before).end),
                OpenSearchPPLParser.RULE_logicalExpression);
        if (lower.complete) return new RangeFocus(start, true);
      }
      return null;
    }

    record ExpressionFocus(ParserRuleContext tree, boolean predicateRequired) {}

    /**
     * Find the widest completed expression ending at the cursor, inside the current grammar slot.
     * Reparse each distinct start only once: recovery nodes for a missing ')' or BETWEEN bound must
     * not turn an unfinished condition into a completed value. Use syntax ancestry to tell
     * predicate slots from scalar arguments, rather than classifying the whole command as WHERE.
     */
    ExpressionFocus expressionFocus(Stage stage, List<Tok> before) {
      if (before.isEmpty()) return null;
      int end = last(before).end;
      String fragment = text.substring(stage.start, end);
      Parsed parsed = parse(fragment, grammarStart);
      int[] positions = offsets(fragment);
      List<ParserRuleContext> expressions = new ArrayList<>();
      for (String rule : List.of("logicalExpression", "expression", "valueExpression"))
        expressions.addAll(nodes(parsed.tree, rule));
      expressions.sort(Comparator.comparingInt(node -> node.getStart().getStartIndex()));
      Set<Integer> visited = new java.util.HashSet<>();
      for (ParserRuleContext expression : expressions) {
        if (expression instanceof OpenSearchPPLParser.LogicalNotContext) continue;
        if (expression.getStop() == null) continue;
        int stop = expression.getStop().getStopIndex() + 1;
        if (stop < 0 || stop >= positions.length || positions[stop] != fragment.length()) continue;
        int start = expression.getStart().getStartIndex();
        if (start < 0 || start >= positions.length || !visited.add(start)) continue;
        Parsed value =
            parse(fragment.substring(positions[start]), OpenSearchPPLParser.RULE_logicalExpression);
        if (value.tree instanceof OpenSearchPPLParser.LogicalAndContext
            || value.tree instanceof OpenSearchPPLParser.LogicalOrContext
            || value.tree instanceof OpenSearchPPLParser.LogicalXorContext
            || value.tree instanceof OpenSearchPPLParser.LogicalNotContext) continue;
        if (value.complete
            && !(last(before).is("exists") && start == expression.getStop().getStartIndex()))
          return new ExpressionFocus(value.tree, requiresPredicate(expression));
      }
      return null;
    }

    boolean requiresPredicate(ParserRuleContext expression) {
      for (ParseTree child = expression, parent = child.getParent();
          parent instanceof ParserRuleContext context;
          child = parent, parent = parent.getParent()) {
        String rule = OpenSearchPPLParser.ruleNames[context.getRuleIndex()];
        if (context instanceof OpenSearchPPLParser.LogicalNotContext
            || Set.of(
                    "whereCommand",
                    "joinCriteria",
                    "resetBeforeArg",
                    "resetAfterArg",
                    "evalExpression")
                .contains(rule)) return true;
        if (rule.equals("caseFunctionCall"))
          return child instanceof OpenSearchPPLParser.LogicalExpressionContext;
        if (rule.equals("functionArgExpression")) {
          ParseTree argument = context.getParent();
          if (argument != null
              && argument.getParent() instanceof OpenSearchPPLParser.FunctionArgsContext args
              && args.getParent() instanceof OpenSearchPPLParser.EvalFunctionCallContext call)
            return CompletionFunctionMetadata.requiresBooleanArgument(
                call.evalFunctionName().getText(), args.functionArg().indexOf(argument));
          return false;
        }
        if (Set.of(
                "functionArgExpression",
                "dataTypeFunctionCall",
                "evalClause",
                "fieldFormatEvalClause")
            .contains(rule)) return false;
      }
      return false;
    }

    boolean completePredicate(List<Tok> predicate) {
      if (predicate.isEmpty()) return false;
      boolean booleanValue =
          predicate.size() == 1 && Set.of("true", "false").contains(last(predicate).lower());
      Parsed parsed =
          parse(
              text.substring(predicate.getFirst().start, last(predicate).end),
              OpenSearchPPLParser.RULE_logicalExpression);
      return parsed.complete && (booleanValue || booleanPredicateTree(parsed.tree));
    }

    boolean booleanPredicateTree(ParseTree tree) {
      return !nodes(tree, "comparisonOperator").isEmpty()
          || !nodes(tree, "nullNotnull").isEmpty()
          || nodes(tree, "expression").stream()
              .anyMatch(
                  node ->
                      node instanceof OpenSearchPPLParser.InExprContext
                          || node instanceof OpenSearchPPLParser.BetweenContext);
    }

    void suggestRelatedFields(List<Tok> operand) {
      Field parent = field(join(operand));
      if (parent == null || operand.isEmpty()) return;
      boolean qualified =
          sourceAlias != null && identifierParts(join(operand)).getFirst().equals(sourceAlias);
      String whitespace = text.substring(last(operand).end, caret);
      Cursor saved = cursor;
      cursor = new Cursor(operand.getFirst().start, caret, "");
      for (Field child : fields.values()) {
        if (!child.name().startsWith(parent.name() + ".")) continue;
        List<String> path = new ArrayList<>();
        if (qualified) path.add(sourceAlias);
        path.addAll(child.path());
        String inserted =
            path.stream()
                .map(PplCompletionProvider::quote)
                .collect(java.util.stream.Collectors.joining("."));
        add(
            qualified ? sourceAlias + "." + child.name() : child.name(),
            "field",
            "相关字段 · " + child.type(),
            inserted + whitespace,
            false);
      }
      cursor = saved;
    }

    boolean assignmentEquals(Stage stage, Parsed parsed, Tok token) {
      int[] positions = offsets(stage.text(text));
      List<ParserRuleContext> clauses = new ArrayList<>(nodes(parsed.tree, "evalClause"));
      clauses.addAll(nodes(parsed.tree, "fieldFormatEvalClause"));
      for (ParserRuleContext clause : clauses) {
        var equals = clause.getToken(OpenSearchPPLLexer.EQUAL, 0);
        if (equals != null
            && equals.getSymbol().getStartIndex() >= 0
            && stage.start + positions[equals.getSymbol().getStartIndex()] == token.start)
          return true;
      }
      return false;
    }

    void suggestLookupFields(Stage stage) {
      suggestFields(lookupFields(stage), null);
    }

    List<Field> lookupFields(Stage stage) {
      Parsed parsed = parse(stage.text(text), false);
      List<ParserRuleContext> lookups = new ArrayList<>(nodes(parsed.tree, "lookupCommand"));
      lookups.addAll(nodes(parsed.tree, "graphLookupCommand"));
      ParserRuleContext lookup =
          lookups.stream()
              .max(Comparator.comparingInt(node -> node.getStart().getStartIndex()))
              .orElse(parsed.tree);
      List<ParserRuleContext> tables = nodes(lookup, "tableSource");
      if (tables.isEmpty()) {
        degraded("尚未确定关联表，暂无法提示它的字段。");
        return List.of();
      }
      List<String> path = identifierParts(tables.get(0).getText());
      try {
        return metadata.fields(
            request.source(),
            new RelationRef(path.subList(0, path.size() - 1), path.get(path.size() - 1)));
      } catch (MetadataException failure) {
        degraded("无法加载关联表字段：" + failure.getMessage());
        return List.of();
      }
    }

    void literalSuggestions(
        List<String> rules, List<Tok> before, AntlrCompletionSuggester.Analysis analysis) {
      String leaf = rules.get(rules.size() - 1);
      if (rules.contains("aligntimeValue")) {
        if (leaf.equals("stringLiteral")) value("'@d'", "以当天零点对齐");
      } else if (leaf.equals("spanLiteral")) {
        if (!knownTimeSpan(analysis)) ensureSchema(analysis);
        boolean time = timeSpanContext(analysis, before);
        if (time) {
          String count = cursor.prefix.matches("[1-9][0-9]*") ? cursor.prefix : "1";
          for (String unit : List.of("s", "m", "h", "d")) value(count + unit, "时间步长");
          if (cursor.prefix.isEmpty()) value("5m", "时间步长");
        } else for (String number : List.of("1", "10", "100")) value(number, "数值步长");
      } else if (Set.of(
                  "integerLiteral",
                  "numericLiteral",
                  "decimalLiteral",
                  "doubleLiteral",
                  "floatLiteral")
              .contains(leaf)
          && !rules.contains("logicalExpression")
          && !rules.contains("valueExpression")) {
        for (String number : List.of("10", "100")) value(number, "数值参数示例");
      } else if (leaf.equals("stringLiteral")
          && (items.isEmpty()
              || analysis.rules().stream()
                  .allMatch(
                      rule ->
                          OpenSearchPPLParser.ruleNames[rule.ruleIndex()].equals("stringLiteral"))))
        message = "输入带引号的字符串";
    }

    boolean timeSpanContext(AntlrCompletionSuggester.Analysis analysis, List<Tok> before) {
      int opening = lastUnclosed(before);
      if (opening > 0
          && before.get(opening - 1).is("span")
          && ruleContext(analysis).contains("spanClause")) {
        // With no explicit field, the grammar means span over the implicit timestamp field.
        if (before.size() == opening + 1) return true;
        Field input = field(join(before.subList(opening + 1, before.size() - 1)));
        return input != null && temporal(input);
      }
      Field field = relevantField(before);
      return knownTimeSpan(analysis) || field != null && temporal(field);
    }

    boolean knownTimeSpan(AntlrCompletionSuggester.Analysis analysis) {
      List<String> context = ruleContext(analysis);
      return context.contains("timechartParameter") || context.contains("timewrapCommand");
    }

    Field relevantField(List<Tok> before) {
      for (int end = before.size(); end > 0; end--) {
        Field field = field(currentIdentifier(before.subList(0, end)));
        if (field != null) return field;
      }
      return null;
    }

    boolean binParameterAllowed(Stage stage, String label) {
      Parsed parsed = parse(stage.text(text), false);
      var commands = nodes(parsed.tree, "binCommand");
      if (commands.isEmpty()) return true;
      var bin = (OpenSearchPPLParser.BinCommandContext) commands.get(0);
      if (bin.fieldExpression() == null) return true;
      ensureSchema(candidates(stage.tokens));
      Field target = field(bin.fieldExpression().getText());
      Set<String> used =
          bin.binOption().stream()
              .filter(
                  option ->
                      option.span != null
                          || option.bins != null
                          || option.minspan != null
                          || option.start != null
                          || option.end != null
                          || option.aligntime != null)
              .map(option -> option.getStart().getText().toLowerCase(Locale.ROOT))
              .collect(java.util.stream.Collectors.toSet());
      boolean span = used.contains("span");
      if (Set.of("span", "bins", "minspan").contains(label)
          && (span || used.contains("bins") || used.contains("minspan"))) return false;
      if (label.equals("span") && (used.contains("start") || used.contains("end"))) return false;
      if (Set.of("minspan", "start", "end").contains(label)
          && (span || target != null && temporal(target))) return false;
      if (label.equals("aligntime")) {
        String value =
            bin.binOption().stream()
                .filter(option -> option.span != null)
                .map(option -> option.span.getText())
                .findFirst()
                .orElse("");
        return target != null
            && temporal(target)
            && span
            && !value.matches(".*(?:M|(?i:d|days?|mon|months?))$");
      }
      return true;
    }

    boolean temporal(Field field) {
      String type = field.type() == null ? "" : field.type().toLowerCase(Locale.ROOT);
      return type.contains("timestamp")
          || type.equals("date")
          || type.equals("datetime")
          || type.equals("time");
    }

    void function(String label, String detail) {
      String suffix = text.substring(cursor.to).stripLeading().startsWith("(") ? "" : "(";
      add(label, "function", detail, label + suffix, true);
    }

    String unsupportedReason(String rule) {
      if (rule.equals("mlCommand")
          && metadata.capabilities(request.source()).contains("machine_learning")) return "";
      return PplSemanticPolicy.unsupportedReason(rule);
    }

    List<String> ruleContext(AntlrCompletionSuggester.Analysis analysis) {
      return java.util.stream.Stream.concat(
              analysis.rules().stream().flatMap(rule -> ruleNames(rule.ruleStack()).stream()),
              analysis.tokens().stream().flatMap(token -> ruleNames(token.ruleStack()).stream()))
          .distinct()
          .toList();
    }

    String assignedParameter(List<Tok> tokens) {
      int equals = lastIndex(tokens, token -> token.is("="));
      return equals > 0 ? tokens.get(equals - 1).lower() : "";
    }

    boolean assigned(List<Tok> tokens, String label) {
      List<Integer> scopes = new ArrayList<>(List.of(0));
      for (int i = 0; i < tokens.size(); i++) {
        if (tokens.get(i).is("(") || tokens.get(i).is("[")) scopes.add(i + 1);
        else if ((tokens.get(i).is(")") || tokens.get(i).is("]")) && scopes.size() > 1)
          scopes.remove(scopes.size() - 1);
        else if (tokens.get(i).is("|")) scopes.set(scopes.size() - 1, i + 1);
      }
      int depth = 0;
      for (int i = scopes.get(scopes.size() - 1); i + 1 < tokens.size(); i++) {
        if (tokens.get(i).is("(") || tokens.get(i).is("[")) depth++;
        else if (tokens.get(i).is(")") || tokens.get(i).is("]")) depth--;
        else if (depth == 0 && tokens.get(i).is(label) && tokens.get(i + 1).is("=")) return true;
      }
      return false;
    }

    void suggestFields(List<Field> available, String alias) {
      qualifiedCursor();
      boolean qualified = alias != null && cursor.prefix.startsWith(alias + ".");
      // Keep common source fields visible before expanded mappings fill the response limit.
      // A literal dotted name can represent a flattened mapping even when its path has one part.
      List<Field> orderedFields =
          available.stream()
              .sorted(Comparator.comparingInt(AbstractCompletionProvider::fieldRank))
              .toList();
      for (Field field : orderedFields) {
        String label = qualified ? alias + "." + field.name() : field.name();
        List<String> path = new ArrayList<>();
        if (qualified) path.add(alias);
        path.addAll(field.path());
        String inserted =
            path.stream()
                .map(PplCompletionProvider::quote)
                .collect(java.util.stream.Collectors.joining("."));
        String detail =
            (field.type() == null ? "字段" : field.type())
                + (field.path().size() > 1
                    ? " · 嵌套字段"
                    : field.name().contains(".") ? " · 字段名包含点号" : "");
        add(label, "field", detail, inserted, false);
      }
    }

    void qualifiedCursor() {
      int index = -1;
      for (int i = 0; i < scan.visible.size(); i++)
        if (scan.visible.get(i).end <= cursor.from) index = i;
      int from = cursor.from;
      if (index >= 0 && scan.visible.get(index).is(".") && scan.visible.get(index).end == from) {
        while (index > 0 && scan.visible.get(index).is(".")) {
          Tok identifier = scan.visible.get(index - 1);
          if (!identifierToken(identifier)) break;
          from = identifier.start;
          index -= 2;
        }
        cursor =
            new Cursor(
                from,
                qualifiedIdentifierEnd(scan, from, cursor.to),
                unquote(text.substring(from, caret)));
      }
    }

    List<String> unalias(List<String> path) {
      return sourceAlias != null && path.size() > 1 && path.get(0).equals(sourceAlias)
          ? path.subList(1, path.size())
          : path;
    }

    Field field(String value) {
      return fields.get(pathKey(unalias(identifierParts(value))));
    }

    boolean isCompleteIdentifier(String value) {
      return caret >= cursor.to && field(value) != null;
    }

    void pipe() {
      if (text.substring(caret).stripLeading().startsWith("|")) return;
      symbol("|", "继续处理查询结果", " | ");
    }
  }

  private static List<String> ruleNames(List<Integer> indices) {
    return indices.stream().map(index -> OpenSearchPPLParser.ruleNames[index]).toList();
  }

  private static String nearestFunctionRule(List<String> rules) {
    for (int index = rules.size() - 1; index >= 0; index--) {
      String rule = rules.get(index);
      if (rule.endsWith("FunctionName")
          || rule.endsWith("FunctionCall")
          || rule.endsWith("Function")
          || rule.equals("functionCall")
          || rule.equals("spanClause")) return rule;
    }
    return null;
  }

  private static boolean isFunctionHead(
      AntlrCompletionSuggester.TokenCandidate candidate, String rule) {
    if (rule == null) return false;
    int owner = java.util.Arrays.asList(OpenSearchPPLParser.ruleNames).indexOf(rule);
    int offset = candidate.ruleStack().lastIndexOf(owner);
    Set<AntlrCompletionSuggester.TokenCandidate> heads =
        FUNCTION_HEADS.computeIfAbsent(
            owner,
            index ->
                Set.copyOf(
                    AntlrCompletionSuggester.suggest(GRAMMAR, index, List.of(), Set.of())
                        .tokens()));
    return heads.contains(
        new AntlrCompletionSuggester.TokenCandidate(
            candidate.tokenType(),
            candidate.ruleStack().subList(offset, candidate.ruleStack().size())));
  }

  private static String firstCommand(List<String> rules) {
    return rules.stream().filter(rule -> rule.endsWith("Command")).findFirst().orElse("");
  }

  private static String firstCommandRule(ParseTree tree) {
    if (tree instanceof ParserRuleContext context) {
      String rule = OpenSearchPPLParser.ruleNames[context.getRuleIndex()];
      if (rule.endsWith("Command")) return rule;
    }
    for (int index = 0; index < tree.getChildCount(); index++) {
      String rule = firstCommandRule(tree.getChild(index));
      if (!rule.isEmpty()) return rule;
    }
    return "";
  }

  private record Spelling(AntlrCompletionSuggester.TokenCandidate candidate, String text) {}

  private static List<Spelling> spelledCandidates(AntlrCompletionSuggester.Analysis analysis) {
    List<Spelling> result = new ArrayList<>();
    for (var candidate : analysis.tokens()) {
      List<String> texts = tokenSpellings(candidate.tokenType());
      if (texts.isEmpty()) result.add(new Spelling(candidate, null));
      else for (String text : texts) result.add(new Spelling(candidate, text));
    }
    return result;
  }

  private static List<String> tokenSpellings(int type) {
    if (type <= 0) return List.of();
    return SPELLINGS.computeIfAbsent(
        type,
        token -> {
          String literal = OpenSearchPPLLexer.VOCABULARY.getLiteralName(token);
          if (literal != null && literal.startsWith("'") && literal.endsWith("'"))
            return List.of(literal.substring(1, literal.length() - 1).toLowerCase(Locale.ROOT));
          return AntlrLiteralVocabulary.spellings(LEXER_GRAMMAR, token);
        });
  }

  private record Stage(int start, int end, List<Tok> tokens) {
    String text(String document) {
      return document.substring(start, end);
    }
  }

  private record Parsed(ParserRuleContext tree, boolean complete) {}

  private static Scan scan(String text) {
    return scan(text, new OpenSearchPPLLexer(new CaseInsensitiveCharStream(text)));
  }

  private static Cursor cursor(Scan scan, String text, int caret) {
    return cursor(scan, text, caret, PplCompletionProvider::literal, List.of("//"));
  }

  private static List<Stage> stages(Scan scan, String text, int caret) {
    return stages(scan, text, caret, 0);
  }

  private static List<Stage> stages(Scan scan, String text, int caret, int initialStart) {
    List<Stage> result = new ArrayList<>();
    List<Tok> tokens = new ArrayList<>();
    int start = initialStart, brackets = 0;
    for (Tok token : scan.visible) {
      if (token.start < initialStart) continue;
      if (token.start >= caret) break;
      if (token.is("[")) brackets++;
      else if (token.is("]")) brackets = Math.max(0, brackets - 1);
      if (token.is("|") && brackets == 0) {
        result.add(new Stage(start, token.start, List.copyOf(tokens)));
        tokens.clear();
        start = token.end;
      } else
        tokens.add(
            token.end > caret
                ? new Tok(
                    token.type,
                    text.substring(token.start, caret),
                    token.start,
                    caret,
                    token.channel)
                : token);
    }
    result.add(new Stage(start, caret, List.copyOf(tokens)));
    return result;
  }

  private static Parsed parse(String text, boolean source) {
    return parse(
        text, source ? OpenSearchPPLParser.RULE_pplCommands : OpenSearchPPLParser.RULE_commands);
  }

  private static Parsed parse(String text, int startRule) {
    OpenSearchPPLLexer lexer = new OpenSearchPPLLexer(new CaseInsensitiveCharStream(text));
    lexer.removeErrorListeners();
    int[] errors = {0};
    BaseErrorListener listener =
        new BaseErrorListener() {
          @Override
          public void syntaxError(
              Recognizer<?, ?> recognizer,
              Object offendingSymbol,
              int line,
              int position,
              String message,
              RecognitionException error) {
            errors[0]++;
          }
        };
    lexer.addErrorListener(listener);
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    OpenSearchPPLParser parser = new OpenSearchPPLParser(tokens);
    parser.removeErrorListeners();
    parser.addErrorListener(listener);
    ParserRuleContext tree =
        startRule == OpenSearchPPLParser.RULE_root
            ? parser.root()
            : startRule == OpenSearchPPLParser.RULE_pplCommands
                ? parser.pplCommands()
                : startRule == OpenSearchPPLParser.RULE_fromClause
                    ? parser.fromClause()
                    : startRule == OpenSearchPPLParser.RULE_logicalExpression
                        ? parser.logicalExpression()
                        : parser.commands();
    return new Parsed(tree, errors[0] == 0 && tokens.LA(1) == Token.EOF);
  }

  private static List<ParserRuleContext> nodes(ParseTree root, String rule) {
    List<ParserRuleContext> result = new ArrayList<>();
    collect(root, rule, result);
    return result;
  }

  private static void collect(ParseTree node, String rule, List<ParserRuleContext> result) {
    if (node instanceof ParserRuleContext context
        && OpenSearchPPLParser.ruleNames[context.getRuleIndex()].equals(rule)) result.add(context);
    for (int index = 0; index < node.getChildCount(); index++)
      collect(node.getChild(index), rule, result);
  }

  private static List<ParserRuleContext> directNodes(ParserRuleContext root, String rule) {
    List<ParserRuleContext> result = new ArrayList<>();
    for (int index = 0; index < root.getChildCount(); index++) {
      ParseTree child = root.getChild(index);
      if (child instanceof ParserRuleContext context
          && OpenSearchPPLParser.ruleNames[context.getRuleIndex()].equals(rule))
        result.add(context);
    }
    return result;
  }

  private static int lastIndex(List<Tok> tokens, Predicate<Tok> predicate) {
    for (int index = tokens.size() - 1; index >= 0; index--)
      if (predicate.test(tokens.get(index))) return index;
    return -1;
  }

  private static int lastUnclosed(List<Tok> tokens) {
    List<Integer> openings = new ArrayList<>();
    for (int index = 0; index < tokens.size(); index++) {
      if (tokens.get(index).is("(")) openings.add(index);
      else if (tokens.get(index).is(")") && !openings.isEmpty())
        openings.remove(openings.size() - 1);
    }
    return openings.isEmpty() ? -1 : openings.get(openings.size() - 1);
  }

  private static Tok last(List<Tok> tokens) {
    return tokens.get(tokens.size() - 1);
  }

  private static String join(List<Tok> tokens) {
    return tokens.stream().map(Tok::text).reduce("", String::concat);
  }

  private static String currentIdentifier(List<Tok> tokens) {
    if (tokens.isEmpty()) return "";
    int start = tokens.size() - 1;
    while (start > 1 && tokens.get(start - 1).is(".")) start -= 2;
    return join(tokens.subList(start, tokens.size()));
  }

  private static boolean literal(Tok token) {
    String name = OpenSearchPPLLexer.VOCABULARY.getSymbolicName(token.type);
    return name != null
            && (name.endsWith("_LITERAL")
                || name.equals("SQUOTA_STRING")
                || name.equals("DQUOTA_STRING"))
        || token.is("true")
        || token.is("false")
        || token.is("null");
  }

  private static String pathKey(List<String> path) {
    return path.stream()
        .map(part -> part.length() + ":" + part)
        .collect(java.util.stream.Collectors.joining());
  }

  private static boolean selectedPath(List<String> selection, List<String> path) {
    if (selection.size() > path.size()) return false;
    for (int index = 0; index < selection.size(); index++)
      if (!matches(selection.get(index), path.get(index))) return false;
    return true;
  }

  private static boolean matches(String glob, String name) {
    String expression = Pattern.quote(glob).replace("*", "\\E.*\\Q");
    return name.matches(expression);
  }
}
