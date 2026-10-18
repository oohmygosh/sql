/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.service;

import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.calcite.adapter.enumerable.EnumerableInterpreter;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.jdbc.JdbcToEnumerableConverter;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.Converter;
import org.apache.calcite.rel.core.Calc;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.sql.calcite.plan.rel.EnumerableBackendML;

/** Identifies computation left outside native scans in the optimized execution plan. */
final class SearchPushdownWarnings {
  private SearchPushdownWarnings() {}

  static Set<String> localOperators(RelNode plan) {
    Set<String> operators = new LinkedHashSet<>();
    collect(plan, operators);
    return operators;
  }

  private static void collect(RelNode node, Set<String> operators) {
    // JDBC descendants describe remote SQL, even when they have filter/project/aggregate nodes.
    if (node instanceof JdbcToEnumerableConverter) return;
    if (node instanceof EnumerableRel
        && !(node instanceof TableScan)
        && !(node instanceof Values)
        && !(node instanceof Converter)
        && !(node instanceof EnumerableInterpreter)
        && !(node instanceof EnumerableBackendML)
        && !projectionOnly(node)) operators.add(node.getRelTypeName());
    node.getInputs().forEach(input -> collect(input, operators));
  }

  private static boolean projectionOnly(RelNode node) {
    if (node instanceof Project project)
      return project.getProjects().stream().allMatch(SearchPushdownWarnings::referenceOrLiteral);
    if (node instanceof Calc calc) {
      var program = calc.getProgram();
      return program.getCondition() == null
          && program.getProjectList().stream()
              .map(program::expandLocalRef)
              .allMatch(SearchPushdownWarnings::referenceOrLiteral);
    }
    return false;
  }

  private static boolean referenceOrLiteral(RexNode expression) {
    if (expression instanceof RexInputRef || expression instanceof RexLiteral) return true;
    // The ES normalizer preserves document field types with representation-only casts to ANY.
    return expression instanceof RexCall call
        && call.getKind() == SqlKind.CAST
        && call.getType().getSqlTypeName() == SqlTypeName.ANY
        && referenceOrLiteral(call.getOperands().getFirst());
  }
}
