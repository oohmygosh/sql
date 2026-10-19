/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import java.util.Locale;
import java.util.Set;
import org.opensearch.sql.api.completion.CompletionTypePolicy.ValueFamily;

/** SQL syntax positions adapt to the shared type policy without changing grammar reachability. */
record SqlSuggestionContext(Slot slot, boolean prefixed, String valueType) {
  enum Slot {
    CONTINUATION,
    VALUE,
    VALUE_END,
    PREDICATE_OPERATOR,
    PREDICATE_END,
    RANGE_SEPARATOR,
    SYNTAX
  }

  private static final Set<String> COMPARISONS =
      Set.of("=", "!=", "<>", ">", ">=", "<", "<=", "in", "not", "is", "between", "like", "regexp");
  private static final Set<String> MATH = Set.of("+", "-", "*", "/", "%");

  boolean references() {
    return prefixed || slot != Slot.VALUE;
  }

  boolean uncertainReference() {
    return slot == Slot.PREDICATE_OPERATOR
        && CompletionTypePolicy.family(valueType) == ValueFamily.UNKNOWN;
  }

  boolean token(String text, boolean function) {
    String label = text.toLowerCase(Locale.ROOT);
    if (slot == Slot.CONTINUATION || slot == Slot.SYNTAX) return true;
    if (slot == Slot.RANGE_SEPARATOR) return label.equals("and");
    if (slot == Slot.VALUE)
      return prefixed
          || CompletionTypePolicy.family(valueType) == ValueFamily.BOOLEAN
              && Set.of("true", "false").contains(label);
    if (function) return prefixed;
    if (label.equals(".")) return false;
    if (slot == Slot.PREDICATE_END) return !COMPARISONS.contains(label) && !MATH.contains(label);
    if (!CompletionTypePolicy.operatorAllowed(
        label, CompletionTypePolicy.family(valueType), slot == Slot.PREDICATE_OPERATOR))
      return false;
    if (slot == Slot.PREDICATE_OPERATOR) {
      return COMPARISONS.contains(label)
          || CompletionTypePolicy.family(valueType) == ValueFamily.BOOLEAN && !MATH.contains(label);
    }
    return true;
  }

  String inputHint() {
    ValueFamily family = CompletionTypePolicy.family(valueType);
    if (uncertainReference()) return "字段类型为 " + valueType + "，暂只提示空值判断；请选择具体子字段或显式转换类型";
    if (slot == Slot.PREDICATE_OPERATOR && family == ValueFamily.COMPOSITE)
      return "复合字段暂只提示空值判断；请选择具体子字段";
    if (slot != Slot.VALUE || prefixed || family == ValueFamily.BOOLEAN) return "";
    return switch (family) {
      case NUMBER -> "输入数值；输入字段或函数前缀可编写表达式";
      case TEXT -> "输入带引号的字符串；输入字段或函数前缀可编写表达式";
      case TIME -> "输入时间表达式（可显式 CAST）；输入字段或函数前缀可编写表达式";
      default -> "输入比较值；输入字段或函数前缀可编写表达式";
    };
  }
}
