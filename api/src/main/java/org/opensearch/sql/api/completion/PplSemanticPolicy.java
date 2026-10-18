/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Semantic distinctions that cannot be inferred from a grammar token's spelling. */
final class PplSemanticPolicy {
  enum ReferenceRole {
    FIELD,
    TABLE,
    OUTPUT_NAME,
    LOOKUP_FIELD,
    UNKNOWN
  }

  enum SchemaEffect {
    PRESERVE,
    PROJECT,
    RENAME,
    EXTEND,
    TRANSFORM,
    EXTRACT,
    COMBINE,
    PIVOT,
    DYNAMIC,
    UNSUPPORTED,
    UNKNOWN
  }

  private PplSemanticPolicy() {}

  /** Binning/span accept numeric, temporal and convertible text inputs, not these known types. */
  static boolean acceptsFieldType(List<String> rules, List<String> tokens, String type) {
    boolean bucket =
        rules.contains("binCommand")
            || rules.contains("spanClause")
            || rules.contains("timechartParameter") && assignedTo(tokens, "timefield");
    return !bucket
        || type == null
        || !Set.of("boolean", "bool", "array", "map", "row", "struct")
            .contains(type.toLowerCase(Locale.ROOT));
  }

  /**
   * Classifies a reference reached by the grammar. Rules are ordered from root to leaf; tokens are
   * committed token texts before the caret, optionally including the preceding query. This method
   * does not generate keywords or decide which grammar alternatives are reachable.
   */
  static ReferenceRole referenceRole(List<String> ruleStack, List<String> tokensBeforeCaret) {
    // An outer eval/lookup must not change the meaning of references in a nested search.
    int command = 0;
    for (int index = 0; index < ruleStack.size(); index++) {
      if (ruleStack.get(index).endsWith("Command")) command = index;
    }
    List<String> rules = ruleStack.subList(command, ruleStack.size());
    List<String> tokens = currentStage(tokensBeforeCaret);

    if (containsAny(rules, "tableSource", "tableQualifiedName", "sourceReference")) {
      return ReferenceRole.TABLE;
    }
    if (containsAny(rules, "sideAlias", "foreachEvalTarget")
        || rules.contains("flattenCommand") && rules.contains("identifierSeq")) {
      return ReferenceRole.OUTPUT_NAME;
    }

    if (rules.contains("lookupPair")) {
      boolean renamed = afterAs(tokens);
      if (rules.contains("outputCandidateList")) {
        return renamed ? ReferenceRole.OUTPUT_NAME : ReferenceRole.LOOKUP_FIELD;
      }
      if (rules.contains("lookupMappingList")) {
        return renamed ? ReferenceRole.FIELD : ReferenceRole.LOOKUP_FIELD;
      }
    }

    if (rules.contains("graphLookupCommand")) {
      if (rules.contains("startClause")) return ReferenceRole.FIELD;
      if (rules.contains("graphLookupArgs")) {
        if (assignedTo(tokens, "depthfield")) return ReferenceRole.OUTPUT_NAME;
        if (assignedTo(tokens, "filter")) return ReferenceRole.LOOKUP_FIELD;
      }
      if (afterAs(tokens)) return ReferenceRole.OUTPUT_NAME;
    }

    if (rules.contains("spathParameter")) {
      if (assignedTo(tokens, "output")) return ReferenceRole.OUTPUT_NAME;
      if (assignedTo(tokens, "input")) return ReferenceRole.FIELD;
      // PATH addresses the JSON value in INPUT, not a column in the surrounding pipeline.
      return ReferenceRole.UNKNOWN;
    }
    if (rules.contains("rexOption") && assignedTo(tokens, "offset_field")) {
      return ReferenceRole.OUTPUT_NAME;
    }
    if (rules.contains("timechartParameter") && assignedTo(tokens, "timefield")) {
      return ReferenceRole.FIELD;
    }
    if (rules.contains("lambda") && !rules.contains("fieldExpression")) {
      return ReferenceRole.OUTPUT_NAME;
    }
    if (rules.contains("fieldFormatEvalClause") && !rules.contains("ffLogicalExpression")) {
      return ReferenceRole.FIELD;
    }
    if (rules.contains("evalClause") && !rules.contains("logicalExpression")) {
      return ReferenceRole.OUTPUT_NAME;
    }

    if (afterAs(tokens)
        && containsAny(
            rules,
            "renameClause",
            "convertFunction",
            "binCommand",
            "expandCommand",
            "trendlineClause",
            "bySpanClause",
            "statsAggTerm",
            "eventstatsAggTerm",
            "streamstatsAggTerm",
            "tableSourceClause",
            "tableOrSubqueryClause")) {
      return ReferenceRole.OUTPUT_NAME;
    }
    if (containsAny(
        rules,
        "fieldExpression",
        "wcFieldExpression",
        "selectFieldExpression",
        "renameFieldExpression",
        "relevanceField")) {
      return ReferenceRole.FIELD;
    }
    if (rules.contains("qualifiedName") && containsAny(rules, "regexExpr", "rexExpr")) {
      return ReferenceRole.FIELD;
    }
    return ReferenceRole.UNKNOWN;
  }

  /** Commands that retain the input fields rather than defining a different output field set. */
  static boolean preservesSchema(String commandRule) {
    return schemaEffect(commandRule) == SchemaEffect.PRESERVE;
  }

  /**
   * Describes output semantics independently of candidate generation. Unknown commands do not
   * implicitly inherit their input schema; callers must mark unresolved output as incomplete.
   */
  static SchemaEffect schemaEffect(String commandRule) {
    return switch (commandRule) {
      case "whereCommand",
          "sortCommand",
          "headCommand",
          "dedupCommand",
          "reverseCommand",
          "regexCommand",
          "replaceCommand",
          "fillnullCommand" ->
          SchemaEffect.PRESERVE;
      case "fieldsCommand", "tableCommand", "statsCommand", "rareTopCommand" ->
          SchemaEffect.PROJECT;
      case "renameCommand" -> SchemaEffect.RENAME;
      case "evalCommand",
          "fieldformatCommand",
          "eventstatsCommand",
          "streamstatsCommand",
          "spathCommand",
          "trendlineCommand",
          "flattenCommand",
          "addtotalsCommand",
          "addcoltotalsCommand" ->
          SchemaEffect.EXTEND;
      case "binCommand",
          "convertCommand",
          "expandCommand",
          "mvexpandCommand",
          "mvcombineCommand",
          "nomvCommand" ->
          SchemaEffect.TRANSFORM;
      case "grokCommand", "parseCommand", "rexCommand" -> SchemaEffect.EXTRACT;
      case "joinCommand",
          "lookupCommand",
          "appendCommand",
          "appendcolCommand",
          "appendPipeCommand",
          "unionCommand" ->
          SchemaEffect.COMBINE;
      case "chartCommand",
          "timechartCommand",
          "transposeCommand",
          "xyseriesCommand",
          "timewrapCommand" ->
          SchemaEffect.PIVOT;
      case "foreachCommand", "patternsCommand", "graphLookupCommand" -> SchemaEffect.DYNAMIC;
      case "adCommand", "kmeansCommand", "mlCommand" -> SchemaEffect.UNSUPPORTED;
      default -> SchemaEffect.UNKNOWN;
    };
  }

  /** Capability exclusions verified against CalciteRelNodeVisitor, used by the unified API. */
  static String unsupportedReason(String commandRule) {
    return switch (commandRule) {
      case "adCommand" -> "当前 Calcite 执行器不支持 ad 命令。";
      case "kmeansCommand" -> "当前 Calcite 执行器不支持 kmeans 命令。";
      case "mlCommand" -> "当前 Calcite 执行器不支持 ml 命令。";
      case "tableFunction" -> "当前 Calcite 执行器不支持表函数。";
      case "dynamicSourceClause" -> "当前执行器不支持动态数据源筛选。";
      case "explainStatement" -> "当前查询接口不支持 explain 语句。";
      case "showDataSourcesCommand" -> "当前 Calcite 执行器不支持 show datasources。";
      default -> "";
    };
  }

  private static boolean containsAny(List<String> values, String... candidates) {
    for (String candidate : candidates) if (values.contains(candidate)) return true;
    return false;
  }

  private static List<String> currentStage(List<String> input) {
    List<String> tokens = input.stream().map(token -> token.toLowerCase(Locale.ROOT)).toList();
    List<Integer> starts = new ArrayList<>(List.of(0));
    for (int index = 0; index < tokens.size(); index++) {
      switch (tokens.get(index)) {
        case "[" -> starts.add(index + 1);
        case "]" -> {
          if (starts.size() > 1) starts.remove(starts.size() - 1);
        }
        case "|" -> starts.set(starts.size() - 1, index + 1);
        default -> {}
      }
    }
    return tokens.subList(starts.get(starts.size() - 1), tokens.size());
  }

  private static boolean afterAs(List<String> tokens) {
    int alias = -1;
    int boundary = -1;
    int depth = 0;
    for (int index = 0; index < tokens.size(); index++) {
      String token = tokens.get(index);
      if (token.equals("(") || token.equals("[")) {
        if (depth == 0) boundary = index;
        depth++;
      } else if (token.equals(")") || token.equals("]")) {
        depth = Math.max(0, depth - 1);
      } else if (depth == 0) {
        if (token.equals("as")) alias = index;
        if (Set.of(",", "output", "append", "replace", "by").contains(token)) boundary = index;
      }
    }
    if (alias <= boundary) return false;
    int suffix = tokens.size() - alias - 1;
    if (suffix == 0) return true;
    // The token being edited is excluded. A committed alias without a trailing dot has already
    // finished; a following space-separated rename clause must read fields again.
    if (suffix % 2 != 0) return false;
    for (int index = alias + 2; index < tokens.size(); index += 2) {
      if (!tokens.get(index).equals(".")) return false;
    }
    return true;
  }

  private static boolean assignedTo(List<String> tokens, String name) {
    int depth = 0;
    String assignment = "";
    for (int index = 0; index < tokens.size(); index++) {
      String token = tokens.get(index);
      if (token.equals("(") || token.equals("[")) depth++;
      else if (token.equals(")") || token.equals("]")) depth = Math.max(0, depth - 1);
      else if (depth == 0 && token.equals("=") && index > 0) {
        assignment = tokens.get(index - 1);
      }
    }
    return assignment.equals(name);
  }
}
