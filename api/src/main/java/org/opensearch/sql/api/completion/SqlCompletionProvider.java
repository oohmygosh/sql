/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.opensearch.sql.api.completion.CompletionMetadata.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.opensearch.sql.common.antlr.CaseInsensitiveCharStream;
import org.opensearch.sql.sql.antlr.parser.OpenSearchSQLLexer;
import org.opensearch.sql.sql.antlr.parser.OpenSearchSQLParser;

/** SQL continuations from the execution grammar, bound to the current SELECT's relations. */
public final class SqlCompletionProvider extends AbstractCompletionProvider {
  private static final OpenSearchSQLLexer LEXER = lexer("");
  private static final OpenSearchSQLParser GRAMMAR = parser("");
  private static final Set<Integer> REFERENCES =
      Set.of(
          OpenSearchSQLParser.RULE_tableName,
          OpenSearchSQLParser.RULE_columnName,
          OpenSearchSQLParser.RULE_alias,
          OpenSearchSQLParser.RULE_qualifiedName,
          OpenSearchSQLParser.RULE_stringLiteral,
          OpenSearchSQLParser.RULE_decimalLiteral,
          OpenSearchSQLParser.RULE_realLiteral);
  private static final Map<Integer, List<String>> SPELLINGS = new ConcurrentHashMap<>();
  private static final Set<AntlrCompletionSuggester.TokenCandidate> FUNCTION_HEADS =
      Set.copyOf(
          AntlrCompletionSuggester.suggest(
                  GRAMMAR, OpenSearchSQLParser.RULE_functionCall, List.of(), REFERENCES)
              .tokens());

  private static final Set<Integer> IDENTIFIER_HEADS =
      AntlrCompletionSuggester.suggest(GRAMMAR, OpenSearchSQLParser.RULE_ident, List.of(), Set.of())
          .tokens()
          .stream()
          .map(AntlrCompletionSuggester.TokenCandidate::tokenType)
          .collect(Collectors.toUnmodifiableSet());

  private static boolean sqlIdentifier(Tok token) {
    return IDENTIFIER_HEADS.contains(token.type);
  }

  @Override
  public String language() {
    return "sql";
  }

  @Override
  public CompletionResponse complete(CompletionRequest request, CompletionMetadata metadata) {
    return new Session(request, metadata).complete();
  }

  private static final class Session extends CompletionSession {
    final List<Scope> scopes = new ArrayList<>();
    final Map<BindingKey, List<Binding>> schemas = new HashMap<>();
    Scope scope;

    Session(CompletionRequest request, CompletionMetadata metadata) {
      this(request, metadata, scan(request.text(), lexer(request.text())));
    }

    Session(CompletionRequest request, CompletionMetadata metadata, Scan scan) {
      super(
          request,
          metadata,
          scan,
          cursor(
              scan,
              request.text(),
              request.caret(),
              SqlCompletionProvider::literal,
              List.of("-- ", "--\n", "--\r", "#")));
    }

    CompletionResponse complete() {
      if (scan.tooDeep) {
        incomplete = true;
        message = "语句嵌套过深，暂无法提供上下文提示。";
        return response();
      }
      if (cursor == null
          || scan.all.stream()
              .anyMatch(
                  t ->
                      t.type == OpenSearchSQLLexer.LINE_COMMENT
                          && t.start < caret
                          && caret <= t.end
                          && !text.substring(t.start, caret).endsWith("\n"))) return response();
      discoverScopes();
      scope =
          scopes.stream()
              .filter(s -> s.start <= caret && caret <= s.end)
              .max(Comparator.comparingInt(s -> s.depth))
              .orElse(null);
      // SQL qualifiers are one replacement, including a partly typed suffix after the caret.
      qualifyCursor();
      var analysis = analyze(cursor.from);
      if (!cursor.prefix.isEmpty() && caret == cursor.to && completeToken(analysis)) {
        insertion();
        analysis = analyze(caret);
      }
      incomplete |= analysis.incomplete();
      SqlSuggestionContext guidance = suggestionContext(analysis);
      boolean columns = false;
      for (var rule : analysis.rules()) {
        if (rule.ruleIndex() == OpenSearchSQLParser.RULE_tableName) tables();
        else if (rule.ruleIndex() == OpenSearchSQLParser.RULE_columnName) columns = true;
        else if (rule.ruleIndex() == OpenSearchSQLParser.RULE_alias
            && items.isEmpty()
            && !incomplete) message = "输入新的字段名或别名";
        else if (rule.ruleIndex() == OpenSearchSQLParser.RULE_stringLiteral
            && items.isEmpty()
            && !incomplete) message = "输入字符串字面量";
        else if (rule.ruleIndex() == OpenSearchSQLParser.RULE_decimalLiteral
            && items.isEmpty()
            && !incomplete) message = "输入数值";
      }
      if (columns && guidance.references()) fields();
      for (var candidate : analysis.tokens()) {
        if (candidate.tokenType() == Token.EOF) continue;
        List<String> candidates =
            candidate.ruleStack().contains(OpenSearchSQLParser.RULE_comparisonOperator)
                ? ComparisonSpellings.ALL.getOrDefault(candidate.tokenType(), List.of())
                : spellings(candidate.tokenType());
        for (String spelling : candidates) {
          String label = spelling.toUpperCase(Locale.ROOT);
          int functionIndex =
              candidate.ruleStack().lastIndexOf(OpenSearchSQLParser.RULE_functionCall);
          boolean function =
              functionIndex >= 0
                  && !label.equals("CASE")
                  && FUNCTION_HEADS.contains(
                      new AntlrCompletionSuggester.TokenCandidate(
                          candidate.tokenType(),
                          candidate
                              .ruleStack()
                              .subList(functionIndex, candidate.ruleStack().size())));
          if (!guidance.token(label, function)) continue;
          // Ordinary functions need a prefix; required grammar syntax stays language-owned.
          if (function && cursor.prefix.isEmpty()) continue;
          if (function) {
            String suffix = text.substring(cursor.to).stripLeading().startsWith("(") ? "" : "(";
            add(label, "function", "SQL 函数", label + suffix, true);
          } else if (label.matches("[A-Z_]+")) {
            if (cursor.from == caret
                && cursor.to == caret
                && scan.visible.stream()
                    .filter(t -> t.start >= caret)
                    .findFirst()
                    .map(t -> t.is(label))
                    .orElse(false)) continue;
            if (guidance.slot() == SqlSuggestionContext.Slot.VALUE
                && Set.of("TRUE", "FALSE").contains(label)) value(label, "布尔值");
            else
              keyword(
                  label,
                  CompletionTypePolicy.operatorDetail(label).isEmpty()
                      ? "SQL 关键字"
                      : CompletionTypePolicy.operatorDetail(label));
          } else {
            symbol(
                label,
                CompletionTypePolicy.operatorDetail(label).isEmpty()
                    ? "SQL 语法"
                    : CompletionTypePolicy.operatorDetail(label),
                punctuation(label));
          }
        }
      }
      boolean degraded = incomplete && !message.isEmpty();
      if (guidance.uncertainReference()) incomplete = true;
      if (!degraded && !guidance.inputHint().isEmpty()) message = guidance.inputHint();
      else if (!items.isEmpty() && !incomplete) message = "";
      return response();
    }

    SqlSuggestionContext suggestionContext(AntlrCompletionSuggester.Analysis analysis) {
      var syntax = SqlSuggestionContext.Slot.SYNTAX;
      boolean prefixed = !cursor.prefix.isEmpty();
      if (analysis.tokens().stream()
          .anyMatch(c -> c.ruleStack().contains(OpenSearchSQLParser.RULE_nullNotnull)))
        return new SqlSuggestionContext(syntax, prefixed, "");
      List<Tok> before =
          scan.visible.stream()
              .filter(t -> scope != null && t.start >= scope.start && t.end <= cursor.from)
              .toList();
      if (before.isEmpty())
        return new SqlSuggestionContext(SqlSuggestionContext.Slot.CONTINUATION, prefixed, "");
      RangeFocus range = rangeFocus(before);
      if (range != null)
        return new SqlSuggestionContext(
            range.separator
                ? SqlSuggestionContext.Slot.RANGE_SEPARATOR
                : SqlSuggestionContext.Slot.VALUE,
            prefixed,
            precedingType(before, range.index));
      int last = before.size() - 1;
      if (Set.of("=", ">", "<", "!=", ">=", "<=", "<>", "like", "regexp")
          .contains(before.get(last).lower()))
        return new SqlSuggestionContext(
            SqlSuggestionContext.Slot.VALUE, prefixed, precedingType(before, last));
      int opening = -1;
      List<Integer> parentheses = new ArrayList<>();
      for (int i = 0; i < before.size(); i++) {
        if (before.get(i).is("(")) parentheses.add(i);
        else if (before.get(i).is(")") && !parentheses.isEmpty()) parentheses.removeLast();
      }
      if (!parentheses.isEmpty()) opening = parentheses.getLast();
      if (opening > 0 && before.get(opening - 1).is("in"))
        return new SqlSuggestionContext(
            SqlSuggestionContext.Slot.VALUE, prefixed, precedingType(before, opening - 1));
      int end =
          before.getLast().is("not") && last > 0 ? before.get(last - 1).end : before.getLast().end;
      ExpressionFocus focus = expressionFocus(end);
      if (focus == null)
        return new SqlSuggestionContext(SqlSuggestionContext.Slot.CONTINUATION, prefixed, "");
      String type = expressionType(focus.tree, visibleBindings(scope, caret), true);
      return new SqlSuggestionContext(
          booleanPredicate(focus.tree)
              ? SqlSuggestionContext.Slot.PREDICATE_END
              : focus.predicateRequired
                  ? SqlSuggestionContext.Slot.PREDICATE_OPERATOR
                  : SqlSuggestionContext.Slot.VALUE_END,
          prefixed,
          type);
    }

    record RangeFocus(int index, boolean separator) {}

    RangeFocus rangeFocus(List<Tok> tokens) {
      for (int index = tokens.size() - 1; index >= 0; index--) {
        if (!tokens.get(index).is("between")) continue;
        int depth = 0, separator = -1;
        for (int i = index + 1; i < tokens.size(); i++) {
          Tok token = tokens.get(i);
          if (token.is("(")) depth++;
          else if (token.is(")") && --depth < 0) return null;
          else if (depth == 0 && token.is("and") && separator < 0) separator = i;
          else if (depth == 0 && (token.is("and") || token.is("or") || token.is(","))) return null;
        }
        if (index == tokens.size() - 1 || separator == tokens.size() - 1)
          return new RangeFocus(index, false);
        if (separator < 0
            && validExpression(text.substring(tokens.get(index + 1).start, tokens.getLast().end))
                != null) return new RangeFocus(index, true);
        return null;
      }
      return null;
    }

    String precedingType(List<Tok> tokens, int operator) {
      int end = operator;
      // A comparison can consist of multiple lexer tokens, or carry a preceding NOT.
      while (end > 0 && Set.of("=", ">", "<", "!", "not").contains(tokens.get(end - 1).lower()))
        end--;
      ExpressionFocus focus = end == 0 ? null : expressionFocus(tokens.get(end - 1).end);
      return focus == null
          ? "unknown"
          : expressionType(focus.tree, visibleBindings(scope, caret), true);
    }

    record ExpressionFocus(ParserRuleContext tree, boolean predicateRequired) {}

    ExpressionFocus expressionFocus(int end) {
      String fragment = text.substring(scope.start, end);
      var tree = parser(fragment).querySpecification();
      List<ParserRuleContext> expressions = new ArrayList<>();
      collectExpressions(tree, expressions);
      expressions.sort(Comparator.comparingInt(node -> node.getStart().getStartIndex()));
      int[] positions = offsets(fragment);
      Set<Integer> visited = new HashSet<>();
      for (var node : expressions) {
        if (node instanceof OpenSearchSQLParser.AndExpressionContext
            || node instanceof OpenSearchSQLParser.OrExpressionContext
            || node instanceof OpenSearchSQLParser.NotExpressionContext
            || node.getStop() == null) continue;
        int start = node.getStart().getStartIndex(), stop = node.getStop().getStopIndex() + 1;
        if (start < 0
            || stop < 0
            || stop >= positions.length
            || positions[stop] != fragment.length()
            || !visited.add(start)) continue;
        ParserRuleContext complete = validExpression(fragment.substring(positions[start]));
        if (complete != null) return new ExpressionFocus(complete, requiresPredicate(node));
      }
      return null;
    }

    void collectExpressions(ParseTree tree, List<ParserRuleContext> expressions) {
      if (tree instanceof OpenSearchSQLParser.ExpressionContext
          || tree instanceof OpenSearchSQLParser.PredicateContext
          || tree instanceof OpenSearchSQLParser.ExpressionAtomContext)
        expressions.add((ParserRuleContext) tree);
      for (int i = 0; i < tree.getChildCount(); i++)
        collectExpressions(tree.getChild(i), expressions);
    }

    ParserRuleContext validExpression(String fragment) {
      var parser = parser(fragment);
      var tree = parser.expression();
      return parser.getNumberOfSyntaxErrors() == 0 && parser.getInputStream().LA(1) == Token.EOF
          ? tree
          : null;
    }

    boolean requiresPredicate(ParserRuleContext node) {
      for (ParseTree child = node, parent = child.getParent();
          parent instanceof ParserRuleContext context;
          child = parent, parent = parent.getParent()) {
        if (parent instanceof OpenSearchSQLParser.WhereClauseContext
            || parent instanceof OpenSearchSQLParser.HavingClauseContext
            || parent instanceof OpenSearchSQLParser.JoinClauseContext
            || parent instanceof OpenSearchSQLParser.NotExpressionContext) return true;
        if (parent instanceof OpenSearchSQLParser.CaseFuncAlternativeContext alternative)
          return child == alternative.condition;
        if (parent instanceof OpenSearchSQLParser.FunctionArgsContext args
            && args.getParent() instanceof OpenSearchSQLParser.ScalarFunctionCallContext call)
          return CompletionFunctionMetadata.requiresBooleanArgument(
              call.scalarFunctionName().getText(), args.functionArg().indexOf(child));
        if (parent instanceof OpenSearchSQLParser.FunctionCallContext
            || parent instanceof OpenSearchSQLParser.SelectElementContext) return false;
      }
      return false;
    }

    boolean booleanPredicate(ParserRuleContext node) {
      if (node instanceof OpenSearchSQLParser.BinaryComparisonPredicateContext
          || node instanceof OpenSearchSQLParser.IsNullPredicateContext
          || node instanceof OpenSearchSQLParser.BetweenPredicateContext
          || node instanceof OpenSearchSQLParser.LikePredicateContext
          || node instanceof OpenSearchSQLParser.RegexpPredicateContext
          || node instanceof OpenSearchSQLParser.InPredicateContext
          || node instanceof OpenSearchSQLParser.InSubqueryPredicateContext
          || node instanceof OpenSearchSQLParser.ExistsSubqueryExpressionAtomContext
          || node instanceof OpenSearchSQLParser.AndExpressionContext
          || node instanceof OpenSearchSQLParser.OrExpressionContext
          || node instanceof OpenSearchSQLParser.NotExpressionContext) return true;
      return node.getChildCount() == 1
          && node.getChild(0) instanceof ParserRuleContext child
          && booleanPredicate(child);
    }

    String expressionType(ParserRuleContext node, List<Binding> bindings, boolean outputAliases) {
      if (node instanceof OpenSearchSQLParser.FullColumnNameExpressionAtomContext column) {
        List<String> path = identifierParts(column.columnName().getText());
        for (Binding binding : bindings) {
          List<String> unqualified =
              path.size() > 1 && path.getFirst().equalsIgnoreCase(binding.name)
                  ? path.subList(1, path.size())
                  : path;
          for (Field field : binding.fields) {
            if (field.path().equals(unqualified)
                || unqualified.size() == 1 && field.name().equals(unqualified.getFirst()))
              return field.type() == null ? "unknown" : field.type();
          }
        }
        if (outputAliases
            && scope != null
            && Set.of("order", "group", "having").contains(clause(scope, cursor.from))) {
          for (Field field : projection(scope, bindings))
            if (field.name().equals(String.join(".", path))) return field.type();
        }
        return "unknown";
      }
      if (booleanPredicate(node)) return "boolean";
      if (node instanceof OpenSearchSQLParser.DataTypeFunctionCallContext cast)
        return cast.convertedDataType().getText().toLowerCase(Locale.ROOT);
      if (node instanceof OpenSearchSQLParser.MathExpressionAtomContext math)
        return CompletionTypePolicy.family(expressionType(math.left, bindings, outputAliases))
                    == CompletionTypePolicy.ValueFamily.NUMBER
                && CompletionTypePolicy.family(expressionType(math.right, bindings, outputAliases))
                    == CompletionTypePolicy.ValueFamily.NUMBER
            ? "number"
            : "unknown";
      if (node instanceof OpenSearchSQLParser.NestedExpressionAtomContext nested)
        return expressionType(nested.expression(), bindings, outputAliases);
      if (node instanceof OpenSearchSQLParser.ScalarFunctionCallContext call)
        return call.functionArgs() == null
            ? "unknown"
            : CompletionFunctionMetadata.returnType(
                call.scalarFunctionName().getText(),
                call.functionArgs().functionArg().stream()
                    .map(arg -> expressionType(arg, bindings, outputAliases))
                    .toList());
      if (node instanceof OpenSearchSQLParser.RegularAggregateFunctionCallContext call)
        return CompletionFunctionMetadata.returnType(
            call.aggregationFunctionName().getText(),
            List.of(expressionType(call.functionArg(), bindings, outputAliases)));
      if (node instanceof OpenSearchSQLParser.CountStarFunctionCallContext) return "bigint";
      if (node.getRuleIndex() == OpenSearchSQLParser.RULE_stringLiteral) return "varchar";
      if (node.getRuleIndex() == OpenSearchSQLParser.RULE_booleanLiteral) return "boolean";
      if (node.getRuleIndex() == OpenSearchSQLParser.RULE_decimalLiteral
          || node.getRuleIndex() == OpenSearchSQLParser.RULE_realLiteral) return "number";
      if (node.getChildCount() == 1 && node.getChild(0) instanceof ParserRuleContext child)
        return expressionType(child, bindings, outputAliases);
      return "unknown";
    }

    AntlrCompletionSuggester.Analysis analyze(int end) {
      List<Integer> types = scan.visible.stream().filter(t -> t.end <= end).map(Tok::type).toList();
      return AntlrCompletionSuggester.suggest(
          GRAMMAR,
          OpenSearchSQLParser.RULE_root,
          types,
          REFERENCES,
          (predicate, position) -> {
            // Matches OpenSearchSQLParser.isJoinKeyword without mutating a shared parser.
            if (predicate.ruleIndex != OpenSearchSQLParser.RULE_relation
                || predicate.predIndex != 0) return null;
            int next = position < types.size() ? types.get(position) : Token.EOF;
            return !Set.of(
                    OpenSearchSQLLexer.LEFT,
                    OpenSearchSQLLexer.RIGHT,
                    OpenSearchSQLLexer.INNER,
                    OpenSearchSQLLexer.CROSS,
                    OpenSearchSQLLexer.JOIN)
                .contains(next);
          });
    }

    boolean completeToken(AntlrCompletionSuggester.Analysis analysis) {
      if (analysis.tokens().stream()
          .flatMap(t -> spellings(t.tokenType()).stream())
          .anyMatch(s -> s.equalsIgnoreCase(cursor.prefix))) return true;
      if (analysis.rules().stream()
          .anyMatch(r -> r.ruleIndex() == OpenSearchSQLParser.RULE_columnName)) {
        if (visibleBindings(scope, caret).stream()
            .flatMap(
                b ->
                    b.fields.stream()
                        .map(f -> List.of(f.name(), b.name + "." + f.name()))
                        .flatMap(List::stream))
            .anyMatch(n -> n.equalsIgnoreCase(cursor.prefix))) return true;
      }
      if (analysis.rules().stream()
          .anyMatch(r -> r.ruleIndex() == OpenSearchSQLParser.RULE_tableName)) {
        List<String> parts = identifierParts(identifierFragment(cursor.from, caret));
        List<String> namespace = parts.subList(0, parts.size() - 1);
        try {
          if (metadata.tables(request.source(), namespace, parts.getLast(), 101).stream()
              .anyMatch(t -> t.name().equalsIgnoreCase(parts.getLast()))) return true;
        } catch (MetadataException failure) {
          metadataFailure(failure);
        }
      }
      // A closed quoted identifier establishes a complete reference without another catalog scan.
      if (cursor.to > cursor.from && text.charAt(cursor.to - 1) == '`') return true;
      return false;
    }

    String punctuation(String label) {
      if (label.equals(",")) return ", ";
      if (Set.of("(", ")", ";", ".", "[", "]").contains(label)) return label;
      return " " + label + " ";
    }

    void qualifyCursor() {
      int start = cursor.from;
      int i = scan.visible.size() - 1;
      while (i >= 0 && scan.visible.get(i).end > start) i--;
      while (i > 0 && scan.visible.get(i).is(".") && sqlIdentifier(scan.visible.get(i - 1))) {
        start = scan.visible.get(i - 1).start;
        i -= 2;
      }
      int end = cursor.to;
      // Visible-token adjacency admits spaces and line breaks around each dot.
      for (int index = 0; index < scan.visible.size(); index++) {
        Tok token = scan.visible.get(index);
        if (token.start != start || !sqlIdentifier(token)) continue;
        end = Math.max(end, token.end);
        while (index + 1 < scan.visible.size() && scan.visible.get(index + 1).is(".")) {
          end = Math.max(end, scan.visible.get(++index).end);
          if (index + 1 >= scan.visible.size() || !sqlIdentifier(scan.visible.get(index + 1)))
            break;
          end = Math.max(end, scan.visible.get(++index).end);
        }
        break;
      }
      cursor = new Cursor(start, end, unquote(identifierFragment(start, caret)));
    }

    /** Remove only trivia between identifier tokens; quoted spaces remain semantic. */
    String identifierFragment(int start, int end) {
      String raw = text.substring(start, end);
      StringBuilder result = new StringBuilder();
      boolean quoted = false;
      for (int index = 0; index < raw.length(); index++) {
        char c = raw.charAt(index);
        if (c == '\\' && quoted && index + 1 < raw.length()) {
          result.append(c).append(raw.charAt(++index));
          continue;
        }
        if (c == '`') quoted = !quoted;
        if (quoted || !Character.isWhitespace(c)) result.append(c);
      }
      return result.toString();
    }

    void tables() {
      List<String> parts = identifierParts(identifierFragment(cursor.from, caret));
      String prefix = parts.getLast();
      List<String> namespace = parts.subList(0, parts.size() - 1);
      try {
        for (var relation : metadata.tables(request.source(), namespace, prefix, 101)) {
          List<String> path = new ArrayList<>(namespace);
          path.add(relation.name());
          add(String.join(".", path), "table", relation.kind(), quoted(path) + " ", false);
        }
      } catch (MetadataException failure) {
        metadataFailure(failure);
      }
    }

    void fields() {
      List<Binding> available = visibleBindings(scope, caret);
      Map<String, Long> counts =
          available.stream()
              .filter(b -> b.owner == scope)
              .flatMap(b -> b.fields.stream())
              .collect(
                  Collectors.groupingBy(Field::name, LinkedHashMap::new, Collectors.counting()));
      boolean qualified = cursor.prefix.contains(".");
      for (Binding binding : available) {
        for (Field field :
            binding.fields.stream()
                .sorted(Comparator.comparingInt(AbstractCompletionProvider::fieldRank))
                .toList()) {
          boolean qualify =
              binding.owner != scope
                  || qualified
                      && cursor
                          .prefix
                          .toLowerCase(Locale.ROOT)
                          .startsWith(binding.name.toLowerCase(Locale.ROOT) + ".")
                  || counts.getOrDefault(field.name(), 0L) > 1;
          List<String> path = new ArrayList<>();
          if (qualify) path.add(binding.name);
          path.addAll(field.path());
          String label = (qualify ? binding.name + "." : "") + field.name();
          add(label, "field", field.type() == null ? "字段" : field.type(), quoted(path), false);
        }
      }
      // SELECT aliases are visible to ORDER BY and GROUP BY, but not WHERE or JOIN ON.
      if (scope != null) {
        String clause = clause(scope, cursor.from);
        if (Set.of("order", "group", "having").contains(clause)) {
          for (Field field : projection(scope, available))
            add(field.name(), "field", "结果列 · " + field.type(), quoted(field.path()), false);
        }
      }
    }

    void metadataFailure(MetadataException failure) {
      incomplete = true;
      message = "无法加载表或字段：" + failure.getMessage();
    }

    // SELECT scopes cover the full document so projection edits can see a later FROM.
    void discoverScopes() {
      List<Opening> openings = new ArrayList<>();
      Tok previous = null;
      for (Tok token : scan.visible) {
        if (token.is("("))
          openings.add(
              new Opening(
                  token.start, previous != null && (previous.is("from") || previous.is("join"))));
        else if (token.is(")")) {
          if (!openings.isEmpty()) {
            int opening = openings.removeLast().start;
            for (Scope s : scopes) if (s.opening == opening) s.end = token.start;
          }
        } else if (token.is("select")) {
          int depth = openings.size();
          for (Scope s : scopes)
            if (s.depth == depth && s.end == text.length()) s.end = token.start;
          Scope parent =
              scopes.stream()
                  .filter(s -> s.depth < depth && s.start < token.start && token.start < s.end)
                  .max(Comparator.comparingInt(s -> s.depth))
                  .orElse(null);
          Opening opening = openings.isEmpty() ? null : openings.getLast();
          scopes.add(
              new Scope(
                  token.start,
                  text.length(),
                  depth,
                  opening == null ? -1 : opening.start,
                  parent,
                  parent != null && opening != null && !opening.relation));
        } else if (token.is("union") || token.is(";")) {
          for (Scope s : scopes)
            if (s.depth == openings.size() && s.end == text.length()) s.end = token.start;
        }
        previous = token;
      }
    }

    List<Tok> topLevel(Scope current, int end) {
      List<Tok> result = new ArrayList<>();
      int depth = 0;
      for (Tok token : scan.visible) {
        if (token.start < current.start) continue;
        if (token.start >= Math.min(current.end, end)) break;
        if (token.is(")")) depth--;
        if (depth == 0) result.add(token);
        if (token.is("(")) depth++;
      }
      return result;
    }

    String clause(Scope current, int position) {
      String clause = "select";
      for (Tok token : topLevel(current, position)) {
        if (Set.of("select", "from", "join", "on", "where", "group", "having", "order", "limit")
            .contains(token.lower())) clause = token.lower();
      }
      return clause;
    }

    List<Binding> visibleBindings(Scope current, int position) {
      if (current == null) return List.of();
      List<Binding> result = new ArrayList<>(localBindings(current, position));
      if (current.correlated) {
        Set<String> names =
            result.stream()
                .map(b -> b.name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        // At a correlated reference, the enclosing ON has not introduced later JOINs yet.
        for (Binding outer : visibleBindings(current.parent, current.opening)) {
          if (names.add(outer.name.toLowerCase(Locale.ROOT))) result.add(outer);
        }
      }
      return result;
    }

    List<Binding> localBindings(Scope current, int position) {
      int limit = clause(current, position).equals("on") ? position : current.end;
      BindingKey key = new BindingKey(current, limit);
      if (schemas.containsKey(key)) return schemas.get(key);
      List<Binding> result = new ArrayList<>();
      schemas.put(key, result);
      List<Tok> tokens = topLevel(current, limit);
      for (int i = 0; i + 1 < tokens.size(); i++) {
        Tok token = tokens.get(i);
        if (!token.is("from") && !token.is("join")) continue;
        Tok next = tokens.get(i + 1);
        var relation = parser(text.substring(next.start, current.end)).relation();
        if (relation instanceof OpenSearchSQLParser.TableAsRelationContext table
            && table.tableName().getStart().getType() != Token.EOF
            && table.tableName().getStart().getTokenIndex() >= 0
            && table.tableName().getStop() != null
            && table.tableName().getStop().getTokenIndex()
                >= table.tableName().getStart().getTokenIndex()) {
          List<String> path = identifierParts(table.tableName().getText());
          String name = table.alias() == null ? path.getLast() : unquote(table.alias().getText());
          try {
            result.add(
                new Binding(
                    name,
                    metadata.fields(
                        request.source(),
                        new RelationRef(path.subList(0, path.size() - 1), path.getLast())),
                    current));
          } catch (MetadataException failure) {
            metadataFailure(failure);
          }
        } else if (relation instanceof OpenSearchSQLParser.SubqueryAsRelationContext subquery) {
          Scope inner =
              scopes.stream().filter(s -> s.opening == next.start).findFirst().orElse(null);
          if (inner != null && subquery.alias() != null)
            result.add(
                new Binding(
                    unquote(subquery.alias().getText()),
                    projection(inner, visibleBindings(inner, inner.start)),
                    current));
        } else {
          incomplete = true;
          message = "动态表函数的字段暂无法推导。";
        }
      }
      return result;
    }

    List<Field> projection(Scope current, List<Binding> input) {
      String fragment = text.substring(current.start, current.end);
      var parsed = parser(fragment).selectClause();
      int[] positions = offsets(fragment);
      List<Field> result = new ArrayList<>();
      if (parsed.selectElements() == null) return result;
      if (parsed.selectElements().star != null)
        input.stream()
            .filter(binding -> binding.owner == current)
            .forEach(binding -> result.addAll(binding.fields));
      for (var element : parsed.selectElements().selectElement()) {
        if (element.expression() == null) continue;
        var expressionNode = element.expression();
        if (expressionNode.getStop() == null
            || expressionNode.getStop().getTokenIndex() < expressionNode.getStart().getTokenIndex())
          continue;
        int start = expressionNode.getStart().getStartIndex();
        int end = expressionNode.getStop().getStopIndex() + 1;
        if (start < 0 || end < start || end >= positions.length) continue;
        // ANTLR offsets are code points; editor/source slices use UTF-16.
        String expression = fragment.substring(positions[start], positions[end]);
        List<String> path = identifierParts(expressionNode.getText());
        Field field =
            input.stream()
                .filter(b -> path.size() == 1 || b.name.equals(path.getFirst()))
                .flatMap(b -> b.fields.stream())
                .filter(f -> f.name().equals(path.getLast()))
                .findFirst()
                .orElse(null);
        String name =
            element.alias() == null
                ? field == null ? expression : field.name()
                : unquote(element.alias().getText());
        result.add(
            new Field(
                name, field == null ? expressionType(expressionNode, input, false) : field.type()));
      }
      return result;
    }
  }

  private static class Scope {
    final int start, depth, opening;
    final Scope parent;
    final boolean correlated;
    int end;

    Scope(int start, int end, int depth, int opening, Scope parent, boolean correlated) {
      this.start = start;
      this.end = end;
      this.depth = depth;
      this.opening = opening;
      this.parent = parent;
      this.correlated = correlated;
    }
  }

  private record Opening(int start, boolean relation) {}

  private record BindingKey(Scope scope, int end) {}

  private record Binding(String name, List<Field> fields, Scope owner) {}

  private static String quoted(List<String> path) {
    return path.stream().map(AbstractCompletionProvider::quote).collect(Collectors.joining("."));
  }

  private static boolean literal(Tok token) {
    String name = OpenSearchSQLLexer.VOCABULARY.getSymbolicName(token.type);
    return name != null && name.endsWith("_LITERAL")
        || Set.of("true", "false", "null").contains(token.lower());
  }

  private static OpenSearchSQLLexer lexer(String text) {
    var lexer = new OpenSearchSQLLexer(new CaseInsensitiveCharStream(text));
    lexer.removeErrorListeners();
    return lexer;
  }

  private static OpenSearchSQLParser parser(String text) {
    var parser = new OpenSearchSQLParser(new CommonTokenStream(lexer(text)));
    parser.removeErrorListeners();
    return parser;
  }

  /** Finite comparison paths come from the grammar, including multi-token !=, <> and >=. */
  private static final class ComparisonSpellings {
    static final Map<Integer, List<String>> ALL = collect();

    static Map<Integer, List<String>> collect() {
      Map<Integer, List<String>> result = new LinkedHashMap<>();
      List<List<Integer>> pending = new ArrayList<>();
      pending.add(List.of());
      for (int index = 0; index < pending.size(); index++) {
        List<Integer> path = pending.get(index);
        var analysis =
            AntlrCompletionSuggester.suggest(
                GRAMMAR, OpenSearchSQLParser.RULE_comparisonOperator, path, Set.of());
        for (var token : analysis.tokens()) {
          if (token.tokenType() == Token.EOF && !path.isEmpty()) {
            String name =
                path.stream().map(t -> spellings(t).getFirst()).collect(Collectors.joining());
            result.computeIfAbsent(path.getFirst(), ignored -> new ArrayList<>()).add(name);
          } else if (token.tokenType() > 0
              && path.size() < 3
              && !spellings(token.tokenType()).isEmpty()) {
            List<Integer> next = new ArrayList<>(path);
            next.add(token.tokenType());
            pending.add(List.copyOf(next));
          }
        }
      }
      return result;
    }
  }

  private static List<String> spellings(int type) {
    if (type <= 0) return List.of();
    return SPELLINGS.computeIfAbsent(
        type,
        token -> {
          String literal = OpenSearchSQLLexer.VOCABULARY.getLiteralName(token);
          if (literal != null) return List.of(literal.substring(1, literal.length() - 1));
          return AntlrLiteralVocabulary.spellings(LEXER, token);
        });
  }
}
