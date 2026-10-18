/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.opensearch.sql.elasticsearch.planner.ElasticsearchExpressionResolver.inputRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlLibraryOperators;
import org.apache.calcite.sql.fun.SqlLikeOperator;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;

/** Translates only predicates whose SQL null semantics can be preserved by Elasticsearch. */
public final class ElasticsearchPredicate {
  private ElasticsearchPredicate() {}

  public static Map<String, Object> translate(RexNode expression, List<Field> fields) {
    return translate(expression, fields, fields);
  }

  public static Map<String, Object> translate(
      RexNode expression, List<Field> fields, List<Field> sourceFields) {
    return translate(expression, fields, sourceFields, true);
  }

  /**
   * Selects the TRUE or FALSE rows of an expression. UNKNOWN belongs to neither set: complementing
   * only the TRUE query would incorrectly admit nulls under NOT, especially inside AND/OR.
   */
  private static Map<String, Object> translate(
      RexNode expression, List<Field> fields, List<Field> sourceFields, boolean truth) {
    if (expression instanceof RexLiteral literal) {
      if (literal.isNull()) return Map.of("match_none", Map.of());
      if (literal.getType().getSqlTypeName() != SqlTypeName.BOOLEAN) return null;
      return Map.of(
          Boolean.valueOf(truth).equals(literal.getValueAs(Boolean.class))
              ? "match_all"
              : "match_none",
          Map.of());
    }
    RexInputRef booleanRef = inputRef(expression);
    if (booleanRef != null) {
      Field field = fields.get(booleanRef.getIndex());
      return field.supportsComparison()
              && field.mappingType().equals("boolean")
              && booleanRef.getType().getSqlTypeName() == SqlTypeName.BOOLEAN
          ? Map.of("term", Map.of(field.name(), truth))
          : null;
    }
    if (!(expression instanceof RexCall call)) return null;
    String name = call.getOperator().getName().toLowerCase(Locale.ROOT);
    if (Set.of(
            "match",
            "match_phrase",
            "match_bool_prefix",
            "match_phrase_prefix",
            "multi_match",
            "query_string",
            "simple_query_string")
        .contains(name)) {
      Map<String, Object> query = search(call, fields, name);
      // RelevanceQueryFunction returns a non-nullable BOOLEAN: an unmatched/missing field is
      // FALSE. Do not assume that contract for a nullable function with the same name.
      return query == null || (!truth && call.getType().isNullable())
          ? null
          : truth ? query : not(query);
    }
    List<RexNode> args = call.getOperands();
    if (call.getKind() == SqlKind.AND || call.getKind() == SqlKind.OR) {
      List<Map<String, Object>> children = new ArrayList<>();
      for (RexNode arg : args) {
        Map<String, Object> child = translate(arg, fields, sourceFields, truth);
        // In particular an OR must never be partially pushed.
        if (child == null) return null;
        children.add(child);
      }
      return (call.getKind() == SqlKind.AND) == truth ? and(children) : or(children);
    }
    if (args.size() == 1) {
      RexNode arg = args.getFirst();
      switch (call.getKind()) {
        case NOT:
          return translate(arg, fields, sourceFields, !truth);
        case IS_TRUE, IS_FALSE, IS_NOT_TRUE, IS_NOT_FALSE:
          boolean wanted =
              call.getKind() == SqlKind.IS_TRUE || call.getKind() == SqlKind.IS_NOT_TRUE;
          Map<String, Object> selected = translate(arg, fields, sourceFields, wanted);
          boolean complement =
              call.getKind() == SqlKind.IS_NOT_TRUE || call.getKind() == SqlKind.IS_NOT_FALSE;
          return selected == null ? null : truth != complement ? selected : not(selected);
        case IS_NULL, IS_NOT_NULL, IS_UNKNOWN:
          Map<String, Object> known;
          RexInputRef ref = inputRef(arg);
          if (ref != null) {
            Field field = fields.get(ref.getIndex());
            if (!field.supportsExistence() || field.name().equals("_score")) return null;
            known = exists(field);
          } else {
            Map<String, Object> yes = translate(arg, fields, sourceFields, true);
            Map<String, Object> no = translate(arg, fields, sourceFields, false);
            if (yes == null || no == null) return null;
            known = or(List.of(yes, no));
          }
          return (call.getKind() == SqlKind.IS_NOT_NULL) == truth ? known : not(known);
        default:
          return null;
      }
    }
    if (call.getKind() == SqlKind.LIKE && call.getOperator() instanceof SqlLikeOperator like) {
      return pattern(
          call, fields, sourceFields, truth != like.isNegated(), !like.isCaseSensitive(), true);
    }
    if (call.getOperator() == SqlLibraryOperators.REGEXP_CONTAINS
        || call.getOperator() == SqlLibraryOperators.REGEXP
        || call.getOperator() == SqlLibraryOperators.RLIKE
        || call.getOperator() == SqlLibraryOperators.NOT_RLIKE) {
      return pattern(
          call,
          fields,
          sourceFields,
          truth != (call.getOperator() == SqlLibraryOperators.NOT_RLIKE),
          false,
          false);
    }
    if (args.size() != 2) return null;
    RexNode left = args.get(0), right = args.get(1);
    SqlKind kind = call.getKind();
    if (left instanceof RexLiteral && inputRef(right) != null) {
      RexNode tmp = left;
      left = right;
      right = tmp;
      kind =
          switch (kind) {
            case LESS_THAN -> SqlKind.GREATER_THAN;
            case LESS_THAN_OR_EQUAL -> SqlKind.GREATER_THAN_OR_EQUAL;
            case GREATER_THAN -> SqlKind.LESS_THAN;
            case GREATER_THAN_OR_EQUAL -> SqlKind.LESS_THAN_OR_EQUAL;
            default -> kind;
          };
    }
    RexInputRef ref = inputRef(left);
    if (ref == null || !(right instanceof RexLiteral literal)) return null;
    Field field = fields.get(ref.getIndex());
    if (!Set.of(
            SqlKind.EQUALS,
            SqlKind.NOT_EQUALS,
            SqlKind.LESS_THAN,
            SqlKind.LESS_THAN_OR_EQUAL,
            SqlKind.GREATER_THAN,
            SqlKind.GREATER_THAN_OR_EQUAL)
        .contains(kind)) return null;
    Object value = literal(literal);
    if (value == null) return Map.of("match_none", Map.of());
    // NULL comparisons never select rows, even where value comparison cannot be pushed down.
    // Non-null comparisons must preserve source values, not just indexed-value presence.
    if (!field.supportsComparison() || field.name().equals("_score")) return null;
    if (!truth) {
      kind =
          switch (kind) {
            case EQUALS -> SqlKind.NOT_EQUALS;
            case NOT_EQUALS -> SqlKind.EQUALS;
            case LESS_THAN -> SqlKind.GREATER_THAN_OR_EQUAL;
            case LESS_THAN_OR_EQUAL -> SqlKind.GREATER_THAN;
            case GREATER_THAN -> SqlKind.LESS_THAN_OR_EQUAL;
            case GREATER_THAN_OR_EQUAL -> SqlKind.LESS_THAN;
            default -> kind;
          };
    }
    return switch (kind) {
      case EQUALS -> Map.of("term", Map.of(field.name(), value));
      case NOT_EQUALS ->
          and(List.of(exists(field), not(Map.of("term", Map.of(field.name(), value)))));
      case LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL -> {
        if (field.name().equals("_id")) yield null;
        String operator =
            switch (kind) {
              case LESS_THAN -> "lt";
              case LESS_THAN_OR_EQUAL -> "lte";
              case GREATER_THAN -> "gt";
              default -> "gte";
            };
        yield Map.of("range", Map.of(field.name(), Map.of(operator, value)));
      }
      default -> null;
    };
  }

  public static Map<String, Object> and(List<Map<String, Object>> children) {
    return Map.of("bool", Map.of("must", children));
  }

  private static Map<String, Object> or(List<Map<String, Object>> children) {
    return Map.of("bool", Map.of("should", children, "minimum_should_match", 1));
  }

  private static Map<String, Object> not(Map<String, Object> child) {
    return Map.of("bool", Map.of("must_not", child));
  }

  private static Map<String, Object> exists(Field field) {
    return Map.of("exists", Map.of("field", field.name()));
  }

  private static Map<String, Object> pattern(
      RexCall call,
      List<Field> fields,
      List<Field> sourceFields,
      boolean truth,
      boolean insensitive,
      boolean like) {
    List<RexNode> args = call.getOperands();
    if (args.size() != 2 && !(like && args.size() == 3)) return null;
    RexInputRef ref = inputRef(args.get(0));
    if (ref == null || !(args.get(1) instanceof RexLiteral literal)) return null;
    Field field = patternField(fields.get(ref.getIndex()), sourceFields);
    if (field == null) return null;
    if (literal.isNull()) return Map.of("match_none", Map.of());
    if (!SqlTypeName.CHAR_TYPES.contains(literal.getType().getSqlTypeName())) return null;
    String value = literal.getValueAs(String.class);
    Map<String, Object> query;
    if (like) {
      char escape = 0;
      if (args.size() == 3) {
        if (!(args.get(2) instanceof RexLiteral escaping)) return null;
        if (escaping.isNull()) return Map.of("match_none", Map.of());
        if (!SqlTypeName.CHAR_TYPES.contains(escaping.getType().getSqlTypeName())) return null;
        String text = escaping.getValueAs(String.class);
        if (text.length() != 1 || Character.isSurrogate(text.charAt(0))) return null;
        escape = text.charAt(0);
      }
      query = like(field, value, escape, insensitive);
    } else {
      String wildcard = regexLiteral(value);
      query = wildcard == null ? null : Map.of("wildcard", Map.of(field.name(), wildcard));
    }
    return query == null ? null : truth ? query : and(List.of(exists(field), not(query)));
  }

  /** Use a multi-field only when it indexes the same raw value in every contributing index. */
  private static Field patternField(Field field, List<Field> sourceFields) {
    if (Set.of("_id", "_index", "_score").contains(field.name())) return null;
    if (field.supportsPattern()) return field;
    if (!field.mappingType().equals("text") || !field.sourceCompatible()) return null;
    return sourceFields.stream()
        .filter(
            candidate -> candidate.origin() == ElasticsearchQueryBackend.SourceOrigin.MULTIFIELD)
        .filter(candidate -> candidate.name().startsWith(field.name() + "."))
        .filter(Field::supportsPattern)
        .filter(candidate -> candidate.source().equals(field.source()))
        .filter(candidate -> candidate.sources().keySet().equals(field.sources().keySet()))
        .filter(
            candidate ->
                field.sources().keySet().stream()
                    .allMatch(index -> candidate.source(index).equals(field.source(index))))
        // Prefer the index intended for substring queries; never depend on a suffix such as .wc.
        .sorted(
            java.util.Comparator.comparingInt(
                candidate -> candidate.mappingType().equals("wildcard") ? 0 : 1))
        .findFirst()
        .orElse(null);
  }

  private static Map<String, Object> like(
      Field field, String pattern, char escape, boolean insensitive) {
    StringBuilder wildcard = new StringBuilder();
    StringBuilder regex = new StringBuilder();
    boolean needsRegex = insensitive;
    for (int i = 0; i < pattern.length(); i++) {
      char ch = pattern.charAt(i);
      // Calcite uses NUL to mean no escape, but still treats a NUL in the pattern as an escape.
      if (ch == 0
          || (Character.isSurrogate(ch)
              && !(Character.isHighSurrogate(ch)
                  && i + 1 < pattern.length()
                  && Character.isLowSurrogate(pattern.charAt(i + 1))))) return null;
      if (ch == escape) {
        if (++i == pattern.length()) return null;
        ch = pattern.charAt(i);
        if (ch != escape && ch != '%' && ch != '_') return null;
      } else if (ch == '%') {
        wildcard.append('*');
        regex.append(".*");
        continue;
      } else if (ch == '_') {
        // Java LIKE's '_' is a regex dot without DOTALL. Lucene '?' also matches line breaks.
        needsRegex = true;
        regex.append("[^\n\r\u0085\u2028\u2029]");
        continue;
      }
      int codePoint = Character.isHighSurrogate(ch) ? pattern.codePointAt(i++) : ch;
      globLiteral(wildcard, codePoint);
      if (insensitive
          && ((codePoint >= 'a' && codePoint <= 'z') || (codePoint >= 'A' && codePoint <= 'Z'))) {
        // Calcite ILIKE uses Java CASE_INSENSITIVE without UNICODE_CASE. Spell out ASCII pairs
        // rather than depending on the Elasticsearch/Lucene version's case-folding flags.
        regex
            .append('[')
            .appendCodePoint(Character.toLowerCase(codePoint))
            .appendCodePoint(Character.toUpperCase(codePoint))
            .append(']');
      } else {
        if ("\\.?+*|{}[]()\"#@&<>~".indexOf(codePoint) >= 0) regex.append('\\');
        regex.appendCodePoint(codePoint);
      }
    }
    return needsRegex
        ? Map.of("regexp", Map.of(field.name(), Map.of("value", regex.toString(), "flags", "NONE")))
        : Map.of("wildcard", Map.of(field.name(), wildcard.toString()));
  }

  /**
   * REGEXP_CONTAINS/REGEXP use Java find(), not Lucene's whole-term regex matching. Accept only
   * literal substrings, optional ^/\\A and \\z anchors, and a redundant trailing .* without an end
   * anchor. In particular '$' can match before a final newline, and Java dot excludes line breaks;
   * neither is silently translated to a Lucene anchor or wildcard.
   */
  private static String regexLiteral(String pattern) {
    boolean start = pattern.startsWith("^") || pattern.startsWith("\\A");
    int from = pattern.startsWith("^") ? 1 : pattern.startsWith("\\A") ? 2 : 0;
    int to = pattern.length();
    StringBuilder result = new StringBuilder(start ? "" : "*");
    boolean end = false;
    for (int i = from; i < to; i++) {
      char ch = pattern.charAt(i);
      if (ch == '\\') {
        if (++i == to) return null;
        ch = pattern.charAt(i);
        if (ch == 'z' && i == to - 1) {
          end = true;
          break;
        }
        if ("\\.^$|?*+()[]{}".indexOf(ch) < 0) return null;
      } else if (ch == '.' && i == to - 2 && pattern.charAt(i + 1) == '*') {
        break;
      } else if (".^$|?*+()[]{}".indexOf(ch) >= 0) {
        return null;
      }
      if (Character.isSurrogate(ch)) {
        if (!Character.isHighSurrogate(ch)
            || i + 1 == to
            || !Character.isLowSurrogate(pattern.charAt(i + 1))) return null;
        globLiteral(result, pattern.codePointAt(i++));
      } else {
        globLiteral(result, ch);
      }
    }
    if (!end) result.append('*');
    return result.toString();
  }

  private static void globLiteral(StringBuilder result, int ch) {
    if (ch == '*' || ch == '?' || ch == '\\') result.append('\\');
    result.appendCodePoint(ch);
  }

  private static Object literal(RexLiteral literal) {
    if (literal.isNull()) return null;
    return switch (literal.getType().getSqlTypeName()) {
      case CHAR, VARCHAR -> literal.getValueAs(String.class);
      case BOOLEAN -> literal.getValueAs(Boolean.class);
      case DATE, TIME, TIMESTAMP, TIMESTAMP_WITH_LOCAL_TIME_ZONE -> literal.getValueAs(Long.class);
      default -> literal.getValueAs(java.math.BigDecimal.class);
    };
  }

  private static Map<String, Object> search(RexCall call, List<Field> fields, String name) {
    Map<String, Object> options = new LinkedHashMap<>();
    for (RexNode arg : call.getOperands()) {
      if (!(arg instanceof RexCall map) || map.getKind() != SqlKind.MAP_VALUE_CONSTRUCTOR)
        return null;
      for (int i = 0; i < map.getOperands().size(); i += 2) {
        if (!(map.getOperands().get(i) instanceof RexLiteral key)) return null;
        Object value = searchValue(map.getOperands().get(i + 1), fields);
        if (value == null) return null;
        options.put(key.getValueAs(String.class), value);
      }
    }
    Object field = options.remove("field");
    if (field != null) return Map.of(name, Map.of(field.toString(), options));
    Object fieldMap = options.get("fields");
    if (fieldMap instanceof Map<?, ?> map) {
      options.put(
          "fields",
          map.entrySet().stream().map(entry -> entry.getKey() + "^" + entry.getValue()).toList());
    }
    return Map.of(name, options);
  }

  private static Object searchValue(RexNode node, List<Field> fields) {
    RexInputRef ref = inputRef(node);
    if (ref != null) return fields.get(ref.getIndex()).name();
    if (node instanceof RexLiteral value) return literal(value);
    if (node instanceof RexCall map && map.getKind() == SqlKind.MAP_VALUE_CONSTRUCTOR) {
      Map<String, Object> result = new LinkedHashMap<>();
      for (int i = 0; i < map.getOperands().size(); i += 2) {
        Object key = searchValue(map.getOperands().get(i), fields);
        Object value = searchValue(map.getOperands().get(i + 1), fields);
        if (key == null || value == null) return null;
        result.put(key.toString(), value);
      }
      return result;
    }
    return null;
  }
}
