/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.executor;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.runtime.Hook;
import org.opensearch.sql.ast.statement.ExplainMode;
import org.opensearch.sql.calcite.CalcitePlanContext;
import org.opensearch.sql.calcite.utils.CalciteToolsHelper.OpenSearchRelRunners;
import org.opensearch.sql.common.response.ResponseListener;
import org.opensearch.sql.planner.physical.PhysicalPlan;

/** Backend-independent execution of Calcite plans. Backend tables implement remote reads. */
public class CalciteExecutionEngine implements ExecutionEngine {
  public static PreparedStatement prepare(RelNode plan, CalcitePlanContext context)
      throws java.sql.SQLException {
    return OpenSearchRelRunners.run(context, plan);
  }

  @Override
  public void execute(
      RelNode plan, CalcitePlanContext context, ResponseListener<QueryResponse> listener) {
    QueryResponse response;
    try (PreparedStatement statement = prepare(plan, context);
        ResultSet result = statement.executeQuery()) {
      response =
          new CalciteResultSetBuilder(true)
              .buildResultSet(result, plan.getRowType(), context.sysLimit.querySizeLimit());
    } catch (Exception e) {
      listener.onFailure(e);
      return;
    }
    listener.onResponse(response);
  }

  @Override
  public List<Warning> stream(
      RelNode plan,
      CalcitePlanContext context,
      QueryRowConsumer consumer,
      QueryCancellation cancellation)
      throws Exception {
    cancellation.check();
    try (PreparedStatement statement = prepare(plan, context)) {
      cancellation.attach(statement);
      try (ResultSet result = statement.executeQuery()) {
        return new CalciteResultSetBuilder(true)
            .streamResultSet(result, plan.getRowType(), consumer, cancellation);
      } finally {
        cancellation.detach();
      }
    }
  }

  @Override
  public void explain(
      RelNode plan,
      ExplainMode mode,
      CalcitePlanContext context,
      ResponseListener<ExplainResponse> listener) {
    AtomicReference<String> physical = new AtomicReference<>();
    try (Hook.Closeable hook =
            Hook.PLAN_BEFORE_IMPLEMENTATION.addThread(
                (RelRoot root) -> physical.set(RelOptUtil.toString(root.rel)));
        PreparedStatement statement = prepare(plan, context)) {
      listener.onResponse(
          new ExplainResponse(
              new ExplainResponseNodeV2(RelOptUtil.toString(plan), physical.get(), null)));
    } catch (Exception e) {
      listener.onFailure(e);
    }
  }

  @Override
  public void execute(PhysicalPlan plan, ResponseListener<QueryResponse> listener) {
    execute(plan, ExecutionContext.emptyExecutionContext(), listener);
  }

  @Override
  public void execute(
      PhysicalPlan plan, ExecutionContext context, ResponseListener<QueryResponse> listener) {
    listener.onFailure(new UnsupportedOperationException("This backend uses Calcite plans"));
  }

  @Override
  public void explain(PhysicalPlan plan, ResponseListener<ExplainResponse> listener) {
    listener.onFailure(new UnsupportedOperationException("This backend uses Calcite plans"));
  }
}
