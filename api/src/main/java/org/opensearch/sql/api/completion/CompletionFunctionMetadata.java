/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.calcite.sql.ExplicitOperatorBinding;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlOperandCountRange;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.sql.calcite.utils.OpenSearchTypeFactory;
import org.opensearch.sql.expression.function.PPLBuiltinOperators;

/** Operand counts from the operators used by the planner, rather than a completion-only catalog. */
final class CompletionFunctionMetadata {
  private CompletionFunctionMetadata() {}

  /**
   * Whether another argument can still lead to a valid operator call. The name is the planner's
   * operator name: frontend rewrites (for example scalar {@code avg} to arithmetic) must be handled
   * by the caller before using aggregate operator metadata. Unknown signatures remain permissive.
   */
  static boolean canAcceptMoreArguments(String functionName, int suppliedCount) {
    if (suppliedCount < 0) throw new IllegalArgumentException("suppliedCount must be non-negative");
    List<SqlOperandCountRange> ranges = Operators.RANGES.get(functionName.toLowerCase(Locale.ROOT));
    if (ranges == null || ranges.isEmpty()) return true;
    // Do not require suppliedCount + 1 to be a complete call: the second argument of a fixed
    // three-argument function must still allow a comma to reach its third argument.
    return ranges.stream().anyMatch(range -> range.getMax() < 0 || suppliedCount < range.getMax());
  }

  static boolean canCloseWithArguments(String functionName, int suppliedCount) {
    List<SqlOperandCountRange> ranges = Operators.RANGES.get(functionName.toLowerCase(Locale.ROOT));
    return ranges == null
        || ranges.isEmpty()
        || ranges.stream().anyMatch(range -> range.isValidCount(suppliedCount));
  }

  static boolean requiresBooleanArgument(String name, int index) {
    return org.opensearch.sql.expression.function.PPLFuncImpTable.INSTANCE.requiresArgumentType(
        name, index, SqlTypeName.BOOLEAN);
  }

  static String returnType(String functionName, List<String> arguments) {
    var factory = OpenSearchTypeFactory.TYPE_FACTORY;
    var types =
        arguments.stream()
            .map(
                type -> {
                  String name = type == null ? "ANY" : type.toUpperCase(Locale.ROOT);
                  name =
                      switch (name) {
                        case "STRING", "TEXT", "KEYWORD" -> "VARCHAR";
                        case "NUMBER", "NUMERIC" -> "DOUBLE";
                        case "LONG" -> "BIGINT";
                        case "INT" -> "INTEGER";
                        case "BOOL" -> "BOOLEAN";
                        default -> name;
                      };
                  SqlTypeName sql = SqlTypeName.get(name);
                  return factory.createSqlType(
                      sql == null
                              || sql == SqlTypeName.ARRAY
                              || sql == SqlTypeName.MAP
                              || sql == SqlTypeName.ROW
                          ? SqlTypeName.ANY
                          : sql);
                })
            .toList();
    Set<String> results = new java.util.HashSet<>();
    for (SqlOperator operator : Operators.ALL) {
      if (!operator.getName().equalsIgnoreCase(functionName)
          || operator.getOperandTypeChecker() == null
          || operator.getOperandCountRange() == null
          || !operator.getOperandCountRange().isValidCount(types.size())) continue;
      try {
        var result =
            operator.inferReturnType(new ExplicitOperatorBinding(factory, operator, types));
        if (result != null) results.add(result.getSqlTypeName().getName().toLowerCase(Locale.ROOT));
      } catch (RuntimeException unavailable) {
        // Some inference rules require actual literal operands or validator scope. The editor
        // has neither; keep the result unknown rather than guessing from the function's name.
        return "unknown";
      }
    }
    return results.size() == 1 ? results.iterator().next() : "unknown";
  }

  private static final class Operators {
    private static final List<SqlOperator> ALL = all();
    private static final Map<String, List<SqlOperandCountRange>> RANGES = collect();

    private static List<SqlOperator> all() {
      List<SqlOperator> result = new ArrayList<>(PPLBuiltinOperators.instance().getOperatorList());
      result.addAll(SqlStdOperatorTable.instance().getOperatorList());
      return List.copyOf(result);
    }

    private static Map<String, List<SqlOperandCountRange>> collect() {
      Map<String, List<SqlOperandCountRange>> result = new HashMap<>();
      for (SqlOperator operator : ALL) {
        if (!(operator instanceof SqlFunction)) continue;
        SqlOperandTypeChecker checker = operator.getOperandTypeChecker();
        // SqlOperator.getOperandCountRange() may reject operators without checkers. Inspect the
        // actual checker instead; operators with no declared signature cannot constrain completion.
        if (checker == null) continue;
        SqlOperandCountRange range = checker.getOperandCountRange();
        // UDFOperandMetadata.UDTOperandMetadata explicitly returns null (for example CIDRMATCH).
        if (range == null) continue;
        result
            .computeIfAbsent(
                operator.getName().toLowerCase(Locale.ROOT), unused -> new ArrayList<>())
            .add(range);
      }
      result.replaceAll((name, ranges) -> List.copyOf(ranges));
      return Map.copyOf(result);
    }
  }
}
