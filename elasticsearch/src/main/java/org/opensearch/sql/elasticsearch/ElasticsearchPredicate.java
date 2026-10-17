/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

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
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;

/** Translates only predicates whose SQL null semantics can be preserved by Elasticsearch. */
final class ElasticsearchPredicate {
  private ElasticsearchPredicate() {}

  static Map<String, Object> translate(RexNode expression, List<Field> fields) {
    if (expression instanceof RexLiteral literal && literal.getValueAs(Boolean.class) != null) {
      return Map.of(
          Boolean.TRUE.equals(literal.getValueAs(Boolean.class)) ? "match_all" : "match_none",
          Map.of());
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
      return search(call, fields, name);
    }
    List<RexNode> args = call.getOperands();
    if (call.getKind() == SqlKind.AND || call.getKind() == SqlKind.OR) {
      List<Map<String, Object>> children = new ArrayList<>();
      for (RexNode arg : args) {
        Map<String, Object> child = translate(arg, fields);
        if (child == null) return null;
        children.add(child);
      }
      return call.getKind() == SqlKind.AND
          ? and(children)
          : Map.of("bool", Map.of("should", children, "minimum_should_match", 1));
    }
    if ((call.getKind() == SqlKind.IS_NULL || call.getKind() == SqlKind.IS_NOT_NULL)
        && args.getFirst() instanceof RexInputRef ref) {
      Field field = fields.get(ref.getIndex());
      if (!field.exact() || field.name().equals("_score")) return null;
      Map<String, Object> exists = Map.of("exists", Map.of("field", field.name()));
      return call.getKind() == SqlKind.IS_NULL ? not(exists) : exists;
    }
    if (args.size() != 2) return null;
    RexNode left = args.get(0), right = args.get(1);
    SqlKind kind = call.getKind();
    if (left instanceof RexLiteral && right instanceof RexInputRef) {
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
    if (!(left instanceof RexInputRef ref) || !(right instanceof RexLiteral literal)) return null;
    Field field = fields.get(ref.getIndex());
    // An analyzed term is not SQL string equality. Keep such predicates in the shared engine.
    if (!field.exact() || field.name().equals("_score")) return null;
    if (!Set.of(SqlKind.EQUALS, SqlKind.NOT_EQUALS, SqlKind.LESS_THAN, SqlKind.LESS_THAN_OR_EQUAL,
        SqlKind.GREATER_THAN, SqlKind.GREATER_THAN_OR_EQUAL).contains(kind)) return null;
    Object value = literal(literal);
    if (value == null) return Map.of("match_none", Map.of());
    return switch (kind) {
      case EQUALS -> Map.of("term", Map.of(field.name(), value));
      case NOT_EQUALS ->
          and(
              List.of(
                  Map.of("exists", Map.of("field", field.name())),
                  not(Map.of("term", Map.of(field.name(), value)))));
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

  static Map<String, Object> and(List<Map<String, Object>> children) {
    return Map.of("bool", Map.of("must", children));
  }

  private static Map<String, Object> not(Map<String, Object> child) {
    return Map.of("bool", Map.of("must_not", child));
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
    if (node instanceof RexInputRef ref) return fields.get(ref.getIndex()).name();
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
