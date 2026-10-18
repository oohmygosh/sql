/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import java.util.Locale;
import java.util.Set;

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

  enum ValueFamily {
    UNSPECIFIED,
    UNKNOWN,
    COMPOSITE,
    NUMBER,
    TEXT,
    BOOLEAN,
    TIME
  }

  ValueFamily family() {
    return family(valueType);
  }

  static ValueFamily family(String valueType) {
    if (valueType == null) return ValueFamily.UNKNOWN;
    valueType = valueType.toLowerCase(Locale.ROOT);
    if (valueType.isEmpty()) return ValueFamily.UNSPECIFIED;
    if (Set.of("any", "unknown", "undefined", "null").contains(valueType))
      return ValueFamily.UNKNOWN;
    if (Set.of("row", "struct", "map", "object", "array", "nested", "flattened")
        .contains(valueType)) return ValueFamily.COMPOSITE;
    if (Set.of("bool", "boolean").contains(valueType)) return ValueFamily.BOOLEAN;
    if (Set.of(
            "date",
            "datetime",
            "time",
            "timestamp",
            "timestamp_tz",
            "time_tz",
            "timestamp_with_local_time_zone",
            "timestamp_with_time_zone",
            "time_with_local_time_zone",
            "time_with_time_zone")
        .contains(valueType)) return ValueFamily.TIME;
    if (Set.of(
            "tinyint",
            "smallint",
            "integer",
            "int",
            "bigint",
            "long",
            "short",
            "byte",
            "float",
            "double",
            "decimal",
            "number",
            "numeric",
            "real")
        .contains(valueType)) return ValueFamily.NUMBER;
    if (Set.of("string", "varchar", "char", "text", "keyword", "ip").contains(valueType))
      return ValueFamily.TEXT;
    return ValueFamily.UNKNOWN;
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
    ValueFamily type = family();
    if (Set.of("and", "or", "xor").contains(label))
      return type == ValueFamily.BOOLEAN || type == ValueFamily.UNSPECIFIED;
    if (Set.of("*", "/", "%", "+", "-").contains(label))
      return slot != Slot.PREDICATE_OPERATOR
          && (type == ValueFamily.NUMBER || type == ValueFamily.UNSPECIFIED);
    if (Set.of("=", "==", "!=", "in", "not", "between").contains(label)
        && (type == ValueFamily.UNKNOWN || type == ValueFamily.COMPOSITE)) return false;
    if (Set.of(">", "<", ">=", "<=", "between").contains(label))
      return type == ValueFamily.NUMBER
          || type == ValueFamily.TIME
          || type == ValueFamily.UNSPECIFIED;
    if (Set.of("like", "ilike", "regexp", "contains").contains(label))
      return type == ValueFamily.TEXT || type == ValueFamily.UNSPECIFIED;
    return true;
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
    return switch (label) {
      case "=", "==" -> "等于";
      case "!=" -> "不等于";
      case ">" -> "大于";
      case ">=" -> "大于或等于";
      case "<" -> "小于";
      case "<=" -> "小于或等于";
      case "in" -> "匹配列表中的值";
      case "not" -> "否定后续条件";
      case "is" -> "判断空值：IS NULL / IS NOT NULL";
      case "between" -> "范围比较";
      case "like" -> "模式匹配";
      case "ilike" -> "忽略大小写的模式匹配";
      case "regexp" -> "正则匹配";
      case "contains" -> "包含匹配";
      case "and" -> "并且";
      case "or" -> "或者";
      case "xor" -> "异或";
      default -> "";
    };
  }
}
