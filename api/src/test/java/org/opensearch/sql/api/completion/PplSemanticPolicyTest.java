/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.*;
import static org.opensearch.sql.api.completion.PplSemanticPolicy.ReferenceRole.*;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.Transition;
import org.junit.Test;
import org.opensearch.sql.api.completion.PplSemanticPolicy.ReferenceRole;
import org.opensearch.sql.api.completion.PplSemanticPolicy.SchemaEffect;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLParser;

public class PplSemanticPolicyTest {
  @Test
  public void everyPipelineCommandHasAnExplicitSchemaPolicy() {
    Set<String> rules = commandRules();
    assertEquals(48, rules.size());
    for (String rule : rules) {
      assertNotEquals(rule, SchemaEffect.UNKNOWN, PplSemanticPolicy.schemaEffect(rule));
    }
    assertEquals(SchemaEffect.UNKNOWN, PplSemanticPolicy.schemaEffect("futureCommand"));
    assertFalse(PplSemanticPolicy.preservesSchema("futureCommand"));
  }

  @Test
  public void typeAndShapeChangingCommandsDoNotSilentlyPreserveSchema() {
    assertTrue(PplSemanticPolicy.preservesSchema("regexCommand"));
    assertTrue(PplSemanticPolicy.preservesSchema("fillnullCommand"));
    assertFalse(PplSemanticPolicy.preservesSchema("fieldformatCommand"));
    assertEquals(SchemaEffect.EXTEND, PplSemanticPolicy.schemaEffect("flattenCommand"));
    assertEquals(SchemaEffect.TRANSFORM, PplSemanticPolicy.schemaEffect("expandCommand"));
    assertEquals(SchemaEffect.PIVOT, PplSemanticPolicy.schemaEffect("timechartCommand"));
    assertEquals(SchemaEffect.DYNAMIC, PplSemanticPolicy.schemaEffect("patternsCommand"));
  }

  @Test
  public void capabilityRestrictionsComeFromTheCalciteExecutionPath() {
    for (String rule : List.of("adCommand", "mlCommand", "kmeansCommand")) {
      assertFalse(PplSemanticPolicy.unsupportedReason(rule).isEmpty());
      assertEquals(SchemaEffect.UNSUPPORTED, PplSemanticPolicy.schemaEffect(rule));
    }
    for (String rule :
        List.of("tableCommand", "convertCommand", "transposeCommand", "graphLookupCommand")) {
      assertEquals("", PplSemanticPolicy.unsupportedReason(rule));
    }
  }

  @Test
  public void evalSeparatesTargetsFromExpressions() {
    assertRole(OUTPUT_NAME, "eval", "evalCommand", "evalClause", "fieldExpression");
    assertRole(
        FIELD,
        "eval result =",
        "evalCommand",
        "evalClause",
        "logicalExpression",
        "fieldExpression");
    assertRole(
        FIELD, "fieldformat", "fieldformatCommand", "fieldFormatEvalClause", "fieldExpression");
    assertRole(
        FIELD,
        "fieldformat label =",
        "fieldformatCommand",
        "fieldFormatEvalClause",
        "ffLogicalExpression",
        "logicalExpression",
        "fieldExpression");
  }

  @Test
  public void renameDoesNotSuggestExistingFieldsAsItsOutputName() {
    assertRole(FIELD, "rename", "renameCommand", "renameClause", "renameFieldExpression");
    assertRole(
        OUTPUT_NAME, "rename status AS", "renameCommand", "renameClause", "renameFieldExpression");
    assertRole(
        OUTPUT_NAME,
        "rename status AS http .",
        "renameCommand",
        "renameClause",
        "renameFieldExpression");
    assertRole(
        FIELD, "rename status AS code ,", "renameCommand", "renameClause", "renameFieldExpression");
    assertRole(
        FIELD, "rename status AS code", "renameCommand", "renameClause", "renameFieldExpression");
  }

  @Test
  public void lookupMappingAndOutputHaveDifferentRightHandRoles() {
    assertRole(TABLE, "lookup", "lookupCommand", "tableSource", "tableQualifiedName");
    assertRole(
        LOOKUP_FIELD,
        "lookup customers",
        "lookupCommand",
        "lookupMappingList",
        "lookupPair",
        "fieldExpression");
    assertRole(
        FIELD,
        "lookup customers id AS",
        "lookupCommand",
        "lookupMappingList",
        "lookupPair",
        "fieldExpression");
    assertRole(
        LOOKUP_FIELD,
        "lookup customers id AS customer_id OUTPUT",
        "lookupCommand",
        "outputCandidateList",
        "lookupPair",
        "fieldExpression");
    assertRole(
        OUTPUT_NAME,
        "lookup customers id AS customer_id OUTPUT name AS",
        "lookupCommand",
        "outputCandidateList",
        "lookupPair",
        "fieldExpression");
    assertRole(
        LOOKUP_FIELD,
        "lookup customers id OUTPUT name AS customer_name ,",
        "lookupCommand",
        "outputCandidateList",
        "lookupPair",
        "fieldExpression");
  }

  @Test
  public void graphLookupChangesScopeBetweenStartAndFilter() {
    assertRole(TABLE, "graphlookup", "graphLookupCommand", "tableSource");
    assertRole(
        FIELD, "graphlookup edges start =", "graphLookupCommand", "startClause", "fieldExpression");
    assertRole(
        LOOKUP_FIELD,
        "graphlookup edges start = id edge=from-->to filter = ( enabled = true and",
        "graphLookupCommand",
        "graphLookupArgs",
        "logicalExpression",
        "fieldExpression");
    assertRole(
        OUTPUT_NAME,
        "graphlookup edges start = id edge=from-->to depthfield =",
        "graphLookupCommand",
        "graphLookupArgs",
        "fieldExpression");
    assertRole(
        OUTPUT_NAME,
        "graphlookup edges start = id edge=from-->to AS",
        "graphLookupCommand",
        "fieldExpression");
  }

  @Test
  public void spathDoesNotConfuseOutputAndJsonPathsWithInputFields() {
    assertRole(
        FIELD, "spath input =", "spathCommand", "spathParameter", "expression", "fieldExpression");
    assertRole(
        OUTPUT_NAME,
        "spath input = message output =",
        "spathCommand",
        "spathParameter",
        "expression",
        "fieldExpression");
    assertRole(
        UNKNOWN,
        "spath input = message path =",
        "spathCommand",
        "spathParameter",
        "indexablePath",
        "pathElement",
        "ident");
  }

  @Test
  public void rexOffsetAndTimechartTimefieldHaveOppositeRoles() {
    assertRole(FIELD, "rex field =", "rexCommand", "rexExpr", "qualifiedName");
    assertRole(
        OUTPUT_NAME,
        "rex field = message offset_field =",
        "rexCommand",
        "rexExpr",
        "rexOption",
        "qualifiedName");
    assertRole(FIELD, "timechart timefield =", "timechartCommand", "timechartParameter", "ident");
  }

  @Test
  public void genericAliasesAreOutputNamesButFunctionArgumentsReadFields() {
    assertRole(
        OUTPUT_NAME, "stats count ( ) AS", "statsCommand", "statsAggTerm", "wcFieldExpression");
    assertRole(
        FIELD,
        "stats count ( ) AS total , sum (",
        "statsCommand",
        "statsAggTerm",
        "statsFunction",
        "functionArgs",
        "fieldExpression");
    assertRole(
        OUTPUT_NAME,
        "convert num ( amount ) AS",
        "convertCommand",
        "convertFunction",
        "fieldExpression");
    assertRole(
        FIELD,
        "convert num ( amount ) AS number auto (",
        "convertCommand",
        "convertFunction",
        "fieldExpression");
    assertRole(UNKNOWN, "convert", "convertCommand", "convertFunction", "ident");
    assertRole(
        OUTPUT_NAME, "flatten details AS (", "flattenCommand", "identifierSeq", "qualifiedName");
    assertRole(OUTPUT_NAME, "join left =", "joinCommand", "sideAlias", "qualifiedName");
  }

  @Test
  public void parserFieldReferencesAndFreeTextIdentifiersRemainDistinct() {
    assertRole(FIELD, "parse", "parseCommand", "expression", "fieldExpression");
    assertRole(FIELD, "grok", "grokCommand", "expression", "fieldExpression");
    assertRole(FIELD, "where match (", "whereCommand", "relevanceField", "qualifiedName");
    assertRole(
        UNKNOWN,
        "where match ( message ,",
        "whereCommand",
        "relevanceQuery",
        "relevanceArgValue",
        "qualifiedName");
    assertRole(UNKNOWN, "something", "ident");
  }

  @Test
  public void nestedSearchDoesNotInheritTheOuterEvalTargetRole() {
    assertRole(
        FIELD,
        "source = orders | eval result = [ source = customers | fields",
        "root",
        "evalCommand",
        "evalClause",
        "logicalExpression",
        "subSearch",
        "fieldsCommand",
        "selectFieldExpression");
    assertRole(
        OUTPUT_NAME,
        "source = [ source = orders | fields status ] AS",
        "searchCommand",
        "fromClause",
        "tableOrSubqueryClause",
        "qualifiedName");
  }

  @Test
  public void lambdaBindingsAreNotDatabaseColumns() {
    assertRole(
        OUTPUT_NAME,
        "eval mapped = transform ( values ,",
        "evalCommand",
        "evalClause",
        "logicalExpression",
        "functionArgExpression",
        "lambda",
        "ident");
    assertRole(
        FIELD,
        "eval mapped = transform ( values , x ->",
        "evalCommand",
        "evalClause",
        "logicalExpression",
        "functionArgExpression",
        "lambda",
        "logicalExpression",
        "fieldExpression");
  }

  private static void assertRole(ReferenceRole expected, String tokens, String... rules) {
    assertEquals(
        tokens,
        expected,
        PplSemanticPolicy.referenceRole(List.of(rules), List.of(tokens.split(" +"))));
  }

  private static Set<String> commandRules() {
    Set<String> rules = new HashSet<>();
    Set<Integer> visited = new HashSet<>();
    ArrayDeque<ATNState> pending = new ArrayDeque<>();
    pending.add(OpenSearchPPLParser._ATN.ruleToStartState[OpenSearchPPLParser.RULE_commands]);
    while (!pending.isEmpty()) {
      ATNState state = pending.removeFirst();
      if (!visited.add(state.stateNumber)) continue;
      for (Transition transition : state.getTransitions()) {
        if (transition instanceof RuleTransition rule) {
          rules.add(OpenSearchPPLParser.ruleNames[rule.ruleIndex]);
        } else if (transition.isEpsilon()) pending.add(transition.target);
      }
    }
    return rules;
  }
}
