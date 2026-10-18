/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.runtime.Hook;
import org.opensearch.sql.ast.statement.ExplainMode;
import org.opensearch.sql.backend.QueryBackend;
import org.opensearch.sql.common.response.ResponseListener;
import org.opensearch.sql.executor.ExecutionEngine;
import org.opensearch.sql.executor.QueryCancellation;
import org.opensearch.sql.executor.QueryRowConsumer;
import org.opensearch.sql.executor.QueryType;
import org.opensearch.sql.executor.Warning;

/** Thin embedded entry point. Each invocation owns its planner, connection and query state. */
public final class UnifiedQueryService {
  private final Map<String, QueryBackend> backends;
  private final String defaultBackend;
  private final Map<String, Object> settings;

  public UnifiedQueryService(Collection<? extends QueryBackend> backends, String defaultBackend) {
    this(backends, defaultBackend, Map.of());
  }

  public UnifiedQueryService(
      Collection<? extends QueryBackend> backends,
      String defaultBackend,
      Map<String, Object> settings) {
    Map<String, QueryBackend> registry = new LinkedHashMap<>();
    for (QueryBackend backend : backends) {
      if (registry.putIfAbsent(backend.name(), backend) != null)
        throw new IllegalArgumentException("Duplicate data source: " + backend.name());
    }
    this.backends = Map.copyOf(registry);
    this.defaultBackend =
        defaultBackend != null
            ? defaultBackend
            : registry.size() == 1 ? registry.keySet().iterator().next() : null;
    if (this.defaultBackend != null && !registry.containsKey(this.defaultBackend))
      throw new IllegalArgumentException("Unknown default data source: " + this.defaultBackend);
    this.settings = Map.copyOf(settings);
  }

  /** Validates routing without querying the backend or planning a statement. */
  public void validateSource(String source) {
    String name = source != null ? source : defaultBackend;
    if (name == null || !backends.containsKey(name))
      throw new IllegalArgumentException("Unknown or unspecified data source: " + name);
  }

  public ExecutionEngine.QueryResponse execute(String source, QueryType language, String query) {
    return run(source, language, query, false, null);
  }

  public ExecutionEngine.QueryResponse execute(QueryType language, String query) {
    return execute(null, language, query);
  }

  public ExecutionEngine.ExplainResponse explain(String source, QueryType language, String query) {
    return run(source, language, query, true, null);
  }

  public ExecutionEngine.QueryResponse execute(
      String source,
      QueryType language,
      String query,
      org.opensearch.sql.ast.tree.HighlightConfig highlight) {
    return run(source, language, query, false, highlight);
  }

  /** Streams SQL/PPL output with per-query ownership of JDBC, PIT and planner resources. */
  public List<Warning> stream(
      String source,
      QueryType language,
      String query,
      QueryRowConsumer consumer,
      QueryCancellation cancellation) {
    Objects.requireNonNull(consumer, "consumer");
    Objects.requireNonNull(cancellation, "cancellation").check();
    return run(source, language, query, false, null, consumer, cancellation);
  }

  private <T> T run(
      String source,
      QueryType language,
      String query,
      boolean explain,
      org.opensearch.sql.ast.tree.HighlightConfig highlight) {
    return run(source, language, query, explain, highlight, null, null);
  }

  @SuppressWarnings("unchecked")
  private <T> T run(
      String source,
      QueryType language,
      String query,
      boolean explain,
      org.opensearch.sql.ast.tree.HighlightConfig highlight,
      QueryRowConsumer consumer,
      QueryCancellation cancellation) {
    String name = source != null ? source : defaultBackend;
    QueryBackend backend = name == null ? null : backends.get(name);
    if (backend == null)
      throw new IllegalArgumentException("Unknown or unspecified data source: " + name);
    Objects.requireNonNull(query, "query");
    if (highlight != null && !backend.supportsHighlight())
      throw new UnsupportedOperationException("Highlight is not supported by " + backend.type());
    try (Hook.Closeable ignored =
        Hook.PLANNER.addThread(
            (RelOptPlanner planner) -> backend.rules().forEach(planner::addRule))) {
      UnifiedQueryContext.Builder builder =
          UnifiedQueryContext.builder()
              .language(language)
              .catalogFactory(name, backend::createSchema)
              .defaultNamespace(
                  name + (backend.defaultSchema().isEmpty() ? "" : "." + backend.defaultSchema()));
      settings.forEach(builder::setting);
      try (UnifiedQueryContext context = builder.build()) {
        context.getPlanContext().setHighlightConfig(highlight);
        RelNode plan = new UnifiedQueryPlanner(context).plan(query);
        for (var rule : context.getLangSpec().preCompilationRules()) plan = plan.accept(rule);
        plan = backend.normalizePlan(plan);
        if (consumer != null) {
          cancellation.check();
          return (T)
              backend.executionEngine().stream(
                  plan, context.getPlanContext(), consumer, cancellation);
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        ResponseListener<T> listener =
            new ResponseListener<>() {
              public void onResponse(T response) {
                result.complete(response);
              }

              public void onFailure(Exception error) {
                result.completeExceptionally(error);
              }
            };
        if (explain)
          backend
              .executionEngine()
              .explain(
                  plan,
                  ExplainMode.STANDARD,
                  context.getPlanContext(),
                  (ResponseListener<ExecutionEngine.ExplainResponse>) listener);
        else
          backend
              .executionEngine()
              .execute(
                  plan,
                  context.getPlanContext(),
                  (ResponseListener<ExecutionEngine.QueryResponse>) listener);
        return result.join();
      }
    } catch (CompletionException e) {
      if (e.getCause() instanceof RuntimeException cause) throw cause;
      throw new IllegalStateException("Query execution failed", e.getCause());
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Query execution failed", e);
    }
  }
}
