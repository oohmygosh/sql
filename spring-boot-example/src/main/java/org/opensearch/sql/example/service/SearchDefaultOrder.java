/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.service;

import java.util.function.Predicate;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.opensearch.sql.common.utils.StringUtils;
import org.opensearch.sql.example.config.SearchProperties;
import org.opensearch.sql.executor.QueryType;
import org.opensearch.sql.ppl.antlr.PPLSyntaxParser;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLParser;
import org.opensearch.sql.spring.UnifiedQueryProperties;
import org.opensearch.sql.sql.antlr.SQLSyntaxParser;
import org.opensearch.sql.sql.antlr.parser.OpenSearchSQLParser;
import org.springframework.stereotype.Component;

/** Applies the example's source-specific default order to ordinary, single-source row queries. */
@Component
public class SearchDefaultOrder {
  private final SearchProperties properties;
  private final String defaultSource;

  public SearchDefaultOrder(SearchProperties properties, UnifiedQueryProperties sources) {
    this.properties = properties;
    this.defaultSource =
        sources.getDefaultDataSource() != null
            ? sources.getDefaultDataSource()
            : sources.getDataSources().size() == 1
                ? sources.getDataSources().keySet().iterator().next()
                : null;
  }

  public String apply(String source, QueryType language, String query) {
    String selected = source == null ? defaultSource : source;
    if (selected == null) return query;
    String time = properties.timeFields().get(selected);
    String id = properties.idFields().get(selected);
    if (time == null || time.isBlank() || id == null || id.isBlank()) return query;
    return switch (language) {
      case SQL -> sql(query, time, id);
      case PPL -> ppl(query, time, id);
    };
  }

  private static String sql(String query, String time, String id) {
    var root = (OpenSearchSQLParser.RootContext) new SQLSyntaxParser().parse(query);
    if (root.sqlStatement() == null || root.sqlStatement().dmlStatement() == null) return query;
    var statement = root.sqlStatement().dmlStatement().selectStatement();
    if (!(statement instanceof OpenSearchSQLParser.SimpleSelectContext select)) return query;
    var specification = select.querySpecification();
    var from = specification.fromClause();
    if (from == null
        || from.orderByClause() != null
        || !from.joinClause().isEmpty()
        || from.groupByClause() != null
        || from.havingClause() != null
        || !(from.relation() instanceof OpenSearchSQLParser.TableAsRelationContext table)
        || (specification.selectClause().selectSpec() != null
            && specification.selectClause().selectSpec().DISTINCT() != null)) return query;
    if (contains(
        specification,
        node ->
            node instanceof OpenSearchSQLParser.AggregateFunctionContext
                || node instanceof OpenSearchSQLParser.WindowFunctionClauseContext
                || (node instanceof OpenSearchSQLParser.QuerySpecificationContext
                    && node != specification))) return query;

    // Qualifying source fields prevents SELECT message AS timestamp from changing the default key.
    String qualifier;
    if (table.alias() != null) {
      qualifier = original(query, table.alias());
    } else {
      var parts = table.tableName().qualifiedName().ident();
      qualifier = original(query, parts.get(parts.size() - 1));
    }
    String sourceName = StringUtils.unquoteIdentifier(qualifier);
    // The SQL AST resolves aliases by the flattened qualified name, including dots in aliases.
    for (var element : specification.selectClause().selectElements().selectElement()) {
      if (element.alias() != null) {
        String alias = StringUtils.unquoteIdentifier(element.alias().getText());
        if (alias.equals(sourceName + "." + time) || alias.equals(sourceName + "." + id))
          return query;
      }
    }
    return insertAfter(
        query,
        from,
        " ORDER BY "
            + qualifier
            + "."
            + quote(time)
            + " DESC, "
            + qualifier
            + "."
            + quote(id)
            + " DESC");
  }

  private static String ppl(String query, String time, String id) {
    var root = (OpenSearchPPLParser.RootContext) new PPLSyntaxParser().parse(query);
    var statement = root.pplStatement().queryStatement();
    if (statement == null
        || !(statement.pplCommands().searchCommand()
            instanceof OpenSearchPPLParser.SearchFromContext search)) return query;
    var from = search.fromClause().tableOrSubqueryClause();
    if (from == null
        || from.tableSourceClause() == null
        || from.tableSourceClause().tableSource().size() != 1) return query;
    if (statement.commands().stream().anyMatch(command -> !isRowCommand(command))
        || contains(statement, node -> node instanceof OpenSearchPPLParser.SubSearchContext))
      return query;

    // Source fields are still available here, before fields/rename/eval and before any user head.
    return insertAfter(query, search, " | sort 0 -" + quote(time) + ", -" + quote(id));
  }

  /** Commands that preserve source-row identity and order, or select an ordered subset. */
  private static boolean isRowCommand(OpenSearchPPLParser.CommandsContext command) {
    return command.whereCommand() != null
        || command.fieldsCommand() != null
        || command.tableCommand() != null
        || command.renameCommand() != null
        || command.evalCommand() != null
        || command.headCommand() != null
        || command.binCommand() != null
        || command.grokCommand() != null
        || command.parseCommand() != null
        || command.spathCommand() != null
        || command.fillnullCommand() != null
        || command.convertCommand() != null
        || command.regexCommand() != null
        || command.replaceCommand() != null
        || command.fieldformatCommand() != null
        || command.nomvCommand() != null;
  }

  private static boolean contains(ParseTree tree, Predicate<ParseTree> condition) {
    if (condition.test(tree)) return true;
    for (int i = 0; i < tree.getChildCount(); i++) {
      if (contains(tree.getChild(i), condition)) return true;
    }
    return false;
  }

  private static String quote(String field) {
    // The current AST builders only strip the outer backticks; they do not decode escapes.
    if (field.indexOf('`') >= 0 || field.indexOf('\\') >= 0)
      throw new IllegalArgumentException(
          "Default order fields cannot contain backticks or backslashes");
    return "`" + field + "`";
  }

  private static String original(String query, ParserRuleContext context) {
    int start = query.offsetByCodePoints(0, context.getStart().getStartIndex());
    int end = query.offsetByCodePoints(0, context.getStop().getStopIndex() + 1);
    return query.substring(start, end);
  }

  private static String insertAfter(String query, ParserRuleContext context, String order) {
    // ANTLR's CharStreams indexes code points; Java substring indexes UTF-16 code units.
    int offset = query.offsetByCodePoints(0, context.getStop().getStopIndex() + 1);
    return query.substring(0, offset) + order + query.substring(offset);
  }
}
