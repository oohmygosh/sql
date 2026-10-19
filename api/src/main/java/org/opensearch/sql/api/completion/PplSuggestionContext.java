/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import java.util.Set;
import org.opensearch.sql.api.completion.CompletionTypePolicy.ValueFamily;

/** Presentation intent applied to grammar candidates, without inventing commands or keywords. */
record PplSuggestionContext(
    Slot slot, boolean prefixed, String valueType, boolean expressionAlternatives) {
  enum Slot {
    CONTINUATION,
    FIELD,
    OPERAND,
    COMPARISON_VALUE,
    PREDICATE_OPERATOR,
    RANGE_SEPARATOR,
    FIELD_LIST_END,
    PREDICATE_END,
    PARAMETER_VALUE,
    OUTPUT_NAME
  }

  CompletionTypePolicy.ValueFamily family() {
    return CompletionTypePolicy.family(valueType);
  }

  static CompletionTypePolicy.ValueFamily family(String valueType) {
    return CompletionTypePolicy.family(valueType);
  }

  boolean uncertainReference() {
    return slot == Slot.PREDICATE_OPERATOR && family() == ValueFamily.UNKNOWN;
  }

  boolean compositeReference() {
    return slot == Slot.PREDICATE_OPERATOR
        && (family() == ValueFamily.UNKNOWN || family() == ValueFamily.COMPOSITE);
  }

  private boolean operatorAllowed(String label) {
    if (Set.of(
            Slot.PREDICATE_END,
            Slot.OPERAND,
            Slot.FIELD,
            Slot.FIELD_LIST_END,
            Slot.PARAMETER_VALUE,
            Slot.OUTPUT_NAME)
        .contains(slot)) return true;
    return CompletionTypePolicy.operatorAllowed(label, family(), slot == Slot.PREDICATE_OPERATOR);
  }

  boolean references() {
    return prefixed
        || slot != Slot.COMPARISON_VALUE
            && slot != Slot.FIELD_LIST_END
            && slot != Slot.OUTPUT_NAME
            && slot != Slot.PREDICATE_OPERATOR
            && slot != Slot.RANGE_SEPARATOR;
  }

  boolean functions() {
    return prefixed
        || slot != Slot.OPERAND
            && slot != Slot.FIELD
            && slot != Slot.COMPARISON_VALUE
            && slot != Slot.FIELD_LIST_END
            && slot != Slot.OUTPUT_NAME
            && slot != Slot.PREDICATE_OPERATOR
            && slot != Slot.RANGE_SEPARATOR;
  }

  boolean literalExamples() {
    return prefixed
        || slot != Slot.FIELD
            && slot != Slot.OPERAND
            && slot != Slot.COMPARISON_VALUE
            && slot != Slot.FIELD_LIST_END;
  }

  boolean token(String label, boolean function) {
    return token(label, function, false);
  }

  boolean token(String label, boolean function, boolean expressionToken) {
    if (slot == Slot.RANGE_SEPARATOR) return label.equals("and");
    if (function && !functions()) return false;
    if (!function && !operatorAllowed(label)) return false;
    if (prefixed) return true;
    if (label.equals("==")) return false;
    if (slot == Slot.PREDICATE_OPERATOR && label.equals(",")) return false;
    if (slot == Slot.OUTPUT_NAME && expressionToken) return false;
    if (slot == Slot.COMPARISON_VALUE)
      return family() == ValueFamily.BOOLEAN && Set.of("true", "false").contains(label);
    if (slot == Slot.PREDICATE_OPERATOR)
      return Set.of(
              "=",
              "!=",
              ">",
              "<",
              ">=",
              "<=",
              "in",
              "not",
              "is",
              "between",
              "like",
              "ilike",
              "regexp",
              "contains")
          .contains(label);
    if (slot == Slot.PREDICATE_END)
      return !expressionToken || Set.of("and", "or", "xor", "|", ")", "]", ",").contains(label);
    // Qualified paths and lambda bindings remain available when explicitly started, rather than
    // treating every finished identifier as an invitation to extend its spelling.
    if (Set.of(".", "->").contains(label)) return false;
    if (slot == Slot.FIELD_LIST_END && Set.of("*", "%").contains(label)) return false;
    if (slot == Slot.OPERAND
        && Set.of("true", "false", "null", "date", "time", "timestamp", "interval", "<", "[")
            .contains(label)) return false;
    return true;
  }

  String inputHint() {
    if (uncertainReference()) return "字段类型为 " + valueType + "，暂只提示空值判断；请选择具体子字段或显式转换类型";
    if (compositeReference()) return "复合字段暂只提示空值判断；请选择具体子字段";
    if (slot == Slot.OPERAND && !prefixed) return "选择字段，或输入值 / 函数前缀";
    if (slot != Slot.COMPARISON_VALUE || prefixed) return "";
    String expressionHint = expressionAlternatives ? "；输入字段或函数前缀可编写表达式" : "";
    if (family() == ValueFamily.BOOLEAN) return "";
    if (family() == ValueFamily.NUMBER) return "输入数值" + expressionHint;
    if (family() == ValueFamily.TEXT) return "输入带引号的字符串" + expressionHint;
    if (family() == ValueFamily.TIME)
      return (expressionAlternatives ? "输入时间表达式（可显式 CAST）" : "输入时间字面量") + expressionHint;
    return "输入比较值" + expressionHint;
  }

  String operatorDetail(String label) {
    if (slot == Slot.RANGE_SEPARATOR && label.equals("and")) return "连接范围下界和上界";
    if (slot != Slot.PREDICATE_OPERATOR && slot != Slot.PREDICATE_END) return "";
    return CompletionTypePolicy.operatorDetail(label);
  }
}
