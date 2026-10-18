/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api;

import static org.opensearch.sql.monitor.profile.MetricName.ANALYZE;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.runtime.CalciteException;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.util.SqlVisitor;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.sql.api.parser.UnifiedQueryParser;
import org.opensearch.sql.ast.tree.Relation;
import org.opensearch.sql.ast.tree.UnresolvedPlan;
import org.opensearch.sql.calcite.CalcitePlanContext;
import org.opensearch.sql.calcite.CalciteRelNodeVisitor;
import org.opensearch.sql.common.antlr.SyntaxCheckException;
import org.opensearch.sql.common.error.ErrorReport;
import org.opensearch.sql.exception.CalciteUnsupportedException;
import org.opensearch.sql.exception.QueryEngineException;
import org.opensearch.sql.exception.SemanticCheckException;

/**
 * {@code UnifiedQueryPlanner} provides a high-level API for parsing and analyzing queries using the
 * Calcite-based query engine. It serves as the primary integration point for external consumers
 * such as Spark or command-line tools, abstracting away Calcite internals.
 */
public class UnifiedQueryPlanner {

  private static final Logger LOG = LogManager.getLogger(UnifiedQueryPlanner.class);

  /** Planning strategy selected at construction time based on query type. */
  private final PlanningStrategy strategy;

  /** Unified query context for profiling support. */
  private final UnifiedQueryContext context;

  /**
   * Constructs a UnifiedQueryPlanner with a unified query context.
   *
   * @param context the unified query context containing CalcitePlanContext
   */
  public UnifiedQueryPlanner(UnifiedQueryContext context) {
    this.context = context;
    this.strategy = new CustomVisitorStrategy(context);
  }

  /**
   * Parses and analyzes a query string into a Calcite logical plan (RelNode). TODO: Generate
   * optimal physical plan to fully unify query execution and leverage Calcite's optimizer.
   *
   * @param query the raw query string in PPL or SQL syntax
   * @return a logical plan representing the query
   */
  public RelNode plan(String query) {
    return plan(query, null);
  }

  private RelNode plan(String query, QueryExecutionRequest request) {
    try {
      return context.measure(
          ANALYZE,
          () -> {
            RelNode plan = strategy.plan(query, request);
            for (var shuttle : context.getLangSpec().postAnalysisRules()) {
              plan = plan.accept(shuttle);
            }
            return plan;
          });
    } catch (CalciteUnsupportedException e) {
      // Unsupported feature (e.g. table functions) is an invalid query, i.e. a client error.
      // Must precede the QueryEngineException branch as it is a subclass.
      throw new SemanticCheckException(e.getMessage(), e);
    } catch (SyntaxCheckException
        | QueryEngineException
        | UnsupportedOperationException
        | IllegalArgumentException
        | ErrorReport e) {
      LOG.warn("Failed to plan query: {}", e.getMessage());
      throw e;
    } catch (CalciteException e) {
      // Calcite validation errors (e.g. table not found) indicate an invalid query.
      LOG.warn("Failed to plan query, invalid query: {}", e.getMessage());
      throw new SemanticCheckException(e.getMessage(), e);
    } catch (AssertionError e) {
      // Calcite throws assertion error directly when building bad RelNode
      String message = "Failed to plan query: invalid plan structure";
      LOG.warn(message, e);
      throw new SemanticCheckException(message, e);
    } catch (Exception e) {
      String message = "Failed to plan query: unexpected error";
      LOG.error(message, e);
      throw new IllegalStateException(message, e);
    }
  }

  /** Plans the original statement once, then applies typed source and output constraints. */
  public RelNode plan(QueryExecutionRequest request) {
    if (request.language() != context.getPlanContext().queryType)
      throw new IllegalArgumentException("Request language does not match the planner context");
    return QueryExecutionPlan.applyOutput(
        plan(request.query(), request), request, context.getPlanContext().relBuilder);
  }

  /** Strategy interface for language-specific planning logic. */
  private interface PlanningStrategy {
    RelNode plan(String query, QueryExecutionRequest request) throws Exception;
  }

  /** ANSI SQL planning using Calcite's native SqlParser → SqlValidator → SqlToRelConverter. */
  @RequiredArgsConstructor
  private static class CalciteNativeStrategy implements PlanningStrategy {
    private final UnifiedQueryContext context;

    @Override
    public RelNode plan(String query, QueryExecutionRequest request) throws Exception {
      try (Planner planner = Frameworks.getPlanner(context.getPlanContext().config)) {
        SqlNode parsed = planner.parse(query);
        if (!parsed.isA(SqlKind.QUERY)) {
          throw new UnsupportedOperationException(
              "Only query statements are supported. Got: " + parsed.getKind());
        }

        // TODO: move post-parse rewriting into CalciteSqlQueryParser
        SqlNode rewritten = parsed;
        for (SqlVisitor<SqlNode> visitor : context.getLangSpec().postParseRules()) {
          rewritten = rewritten.accept(visitor);
        }

        SqlNode validated = planner.validate(rewritten);
        RelRoot relRoot = planner.rel(validated);
        RelNode plan = relRoot.project();
        return QueryExecutionPlan.hasSourceConstraints(request)
            ? QueryExecutionPlan.bindSource(plan, request)
            : plan;
      }
    }
  }

  /** AST-based planning via context-owned parser → UnresolvedPlan → CalciteRelNodeVisitor. */
  private static class CustomVisitorStrategy implements PlanningStrategy {
    private final UnifiedQueryContext context;
    private final UnifiedQueryParser<UnresolvedPlan> parser;
    private final CalciteRelNodeVisitor relNodeVisitor =
        new CalciteRelNodeVisitor(new EmptyDataSourceService());

    @SuppressWarnings("unchecked")
    CustomVisitorStrategy(UnifiedQueryContext context) {
      this.context = context;
      this.parser = (UnifiedQueryParser<UnresolvedPlan>) context.getParser();
    }

    @Override
    public RelNode plan(String query, QueryExecutionRequest request) {
      UnresolvedPlan ast = parser.parse(query);
      if (!QueryExecutionPlan.hasSourceConstraints(request))
        return preserveCollation(relNodeVisitor.analyze(ast, context.getPlanContext()));
      validateSourceShape(ast);
      var visitor = new SourceConstrainedVisitor(request);
      RelNode logical = visitor.analyze(ast, context.getPlanContext());
      if (visitor.sources != 1)
        throw new IllegalArgumentException("External conditions require exactly one source table");
      if (request.histogram() != null && hasHistogramReduction(ast))
        throw new UnsupportedHistogramException();
      return preserveCollation(
          QueryExecutionPlan.bindValidatedSource(logical, request, visitor.source));
    }

    private void validateSourceShape(UnresolvedPlan plan) {
      if (plan.getChild().size() > 1)
        throw new IllegalArgumentException("External conditions require a single source query");
      for (var child : plan.getChild())
        if (child instanceof UnresolvedPlan nested) validateSourceShape(nested);
    }

    private boolean hasHistogramReduction(UnresolvedPlan plan) {
      if (plan instanceof org.opensearch.sql.ast.tree.Aggregation
          || plan instanceof org.opensearch.sql.ast.tree.Dedupe
          || plan instanceof org.opensearch.sql.ast.tree.Window) return true;
      for (var child : plan.getChild())
        if (child instanceof UnresolvedPlan nested && hasHistogramReduction(nested)) return true;
      return false;
    }

    private static class SourceConstrainedVisitor extends CalciteRelNodeVisitor {
      private final QueryExecutionRequest request;
      private int sources;
      private QueryExecutionPlan.SourceTable source;

      SourceConstrainedVisitor(QueryExecutionRequest request) {
        super(new EmptyDataSourceService());
        this.request = request;
      }

      @Override
      public RelNode visitRelation(Relation relation, CalcitePlanContext planContext) {
        if (++sources != 1 || relation.getTableNames().size() != 1)
          throw new IllegalArgumentException("External conditions require a single source query");
        RelNode source = super.visitRelation(relation, planContext);
        // Validate before user operators can eliminate the scan. Bind after analysis so existing
        // visitors can still inspect source table metadata, such as query_string field mappings.
        this.source = QueryExecutionPlan.validateSource(source, request);
        return source;
      }
    }

    private RelNode preserveCollation(RelNode logical) {
      if (logical instanceof Sort) {
        return logical;
      }
      List<RelCollation> collations =
          logical.getTraitSet().getTraits(RelCollationTraitDef.INSTANCE);
      if (collations != null
          && collations.size() == 1
          && !collations.get(0).getFieldCollations().isEmpty()) {
        return LogicalSort.create(logical, collations.get(0), null, null);
      }
      return logical;
    }
  }
}
