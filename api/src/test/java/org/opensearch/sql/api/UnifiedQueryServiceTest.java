/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api;

import static org.junit.Assert.*;

import java.util.List;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.junit.Test;
import org.opensearch.sql.backend.QueryBackend;
import org.opensearch.sql.executor.CalciteExecutionEngine;
import org.opensearch.sql.executor.ExecutionEngine;
import org.opensearch.sql.executor.QueryType;

public class UnifiedQueryServiceTest extends UnifiedQueryTestBase {
  private QueryBackend backend(String name) {
    return backend(name, new CalciteExecutionEngine());
  }

  private QueryBackend backend(String name, ExecutionEngine engine) {
    return new QueryBackend() {
      public String name() {
        return name;
      }

      public String type() {
        return "test";
      }

      public Schema createSchema(SchemaPlus parent) {
        return testSchema;
      }

      public ExecutionEngine executionEngine() {
        return engine;
      }
    };
  }

  @Test
  public void sqlAndPplShareBackend() {
    var service = new UnifiedQueryService(List.of(backend("memory")), "memory");
    var sql =
        service.execute(
            QueryType.SQL,
            "SELECT department, count(*) AS n FROM employees GROUP BY department ORDER BY"
                + " department");
    var ppl =
        service.execute(
            QueryType.PPL,
            "source=employees | stats count() as n by department | sort department | fields"
                + " department,n");
    assertEquals(sql.getResults(), ppl.getResults());
    assertEquals(3, sql.getResults().size());
  }

  @Test
  public void joinsAndWindowsRemainAvailable() {
    var service = new UnifiedQueryService(List.of(backend("memory")), "memory");
    assertEquals(
        4,
        service
            .execute(
                QueryType.SQL, "SELECT a.id, b.name FROM employees a JOIN employees b ON a.id=b.id")
            .getResults()
            .size());
    assertEquals(
        4,
        service
            .execute(
                QueryType.SQL, "SELECT id, row_number() OVER (ORDER BY age) AS rn FROM employees")
            .getResults()
            .size());
  }

  @Test
  public void explainDoesNotExecuteRows() {
    var service = new UnifiedQueryService(List.of(backend("memory")), null);
    var explain = service.explain(null, QueryType.PPL, "source=employees | where age>30");
    assertNotNull(explain.getCalcite().getPhysical());
  }

  @Test
  public void duplicateAndUnknownSourcesFail() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new UnifiedQueryService(List.of(backend("x"), backend("x")), null));
    var service = new UnifiedQueryService(List.of(backend("x")), null);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.execute("missing", QueryType.SQL, "SELECT 1"));
  }

  @Test
  public void concurrentQueriesOwnIndependentPlanningState() throws Exception {
    var service = new UnifiedQueryService(List.of(backend("memory")), null);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Integer>>();
      for (int i = 0; i < 12; i++) {
        final int age = i % 2 == 0 ? 30 : 40;
        tasks.add(
            () ->
                service
                    .execute(QueryType.SQL, "SELECT name FROM employees WHERE age>" + age)
                    .getResults()
                    .size());
      }
      var results = workers.invokeAll(tasks);
      for (int i = 0; i < results.size(); i++)
        assertEquals(i % 2 == 0 ? 2 : 1, results.get(i).get().intValue());
    }
    assertThrows(RuntimeException.class, () -> service.execute(QueryType.PPL, "source=missing"));
    assertEquals(4, service.execute(QueryType.PPL, "source=employees").getResults().size());
  }

  @Test
  public void missingDefaultAndUnsupportedBackendFeaturesAreExplicit() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new UnifiedQueryService(List.of(backend("one")), "missing"));
    var service = new UnifiedQueryService(List.of(backend("one"), backend("two")), null);
    assertThrows(IllegalArgumentException.class, () -> service.execute(QueryType.SQL, "SELECT 1"));
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            service.execute(
                "one",
                QueryType.PPL,
                "source=employees",
                new org.opensearch.sql.ast.tree.HighlightConfig(List.of("name"))));
    assertEquals(
        4, service.execute("two", QueryType.SQL, "SELECT * FROM employees").getResults().size());
  }

  @Test
  public void backendFailuresReachTheCallerAndDoNotPoisonFollowingQueries() {
    for (Exception failure :
        List.of(
            new IllegalArgumentException("native failure"),
            new java.sql.SQLException("connection failed"))) {
      var engine =
          new CalciteExecutionEngine() {
            @Override
            public void execute(
                org.apache.calcite.rel.RelNode plan,
                org.opensearch.sql.calcite.CalcitePlanContext context,
                org.opensearch.sql.common.response.ResponseListener<QueryResponse> listener) {
              listener.onFailure(failure);
            }
          };
      var service =
          new UnifiedQueryService(
              List.of(backend("failed", engine), backend("healthy")), "healthy");
      RuntimeException actual =
          assertThrows(
              RuntimeException.class,
              () -> service.execute("failed", QueryType.SQL, "SELECT * FROM employees"));
      if (failure instanceof RuntimeException) assertSame(failure, actual);
      else assertSame(failure, actual.getCause());
      assertEquals(4, service.execute(QueryType.PPL, "source=employees").getResults().size());
    }
  }
}
