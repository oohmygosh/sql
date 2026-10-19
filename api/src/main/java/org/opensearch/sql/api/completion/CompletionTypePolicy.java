/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import java.util.Locale;
import java.util.Set;

/** Type facts and operator constraints shared by language-specific context adapters. */
final class CompletionTypePolicy {
  private CompletionTypePolicy() {}

  enum ValueFamily {
    UNSPECIFIED,
    UNKNOWN,
    COMPOSITE,
    NUMBER,
    TEXT,
    BOOLEAN,
    TIME
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

  static boolean operatorAllowed(String label, ValueFamily type, boolean predicateRequired) {
    label = label.toLowerCase(Locale.ROOT);
    if (Set.of("and", "or", "xor").contains(label))
      return type == ValueFamily.BOOLEAN || type == ValueFamily.UNSPECIFIED;
    if (Set.of("*", "/", "%", "+", "-").contains(label))
      return !predicateRequired && (type == ValueFamily.NUMBER || type == ValueFamily.UNSPECIFIED);
    if (Set.of("=", "==", "!=", "<>", "in", "not", "between").contains(label)
        && (type == ValueFamily.UNKNOWN || type == ValueFamily.COMPOSITE)) return false;
    if (Set.of(">", "<", ">=", "<=", "between").contains(label))
      return type == ValueFamily.NUMBER
          || type == ValueFamily.TIME
          || type == ValueFamily.UNSPECIFIED;
    if (Set.of("like", "ilike", "regexp", "contains").contains(label))
      return type == ValueFamily.TEXT || type == ValueFamily.UNSPECIFIED;
    return true;
  }

  static String operatorDetail(String label) {
    return switch (label.toLowerCase(Locale.ROOT)) {
      case "=", "==" -> "等于";
      case "!=", "<>" -> "不等于";
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
