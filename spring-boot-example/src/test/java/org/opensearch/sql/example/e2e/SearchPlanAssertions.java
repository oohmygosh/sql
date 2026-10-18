/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.calcite.adapter.enumerable.EnumerableCalc;
import org.apache.calcite.adapter.enumerable.EnumerableLimit;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.Window;
import org.opensearch.sql.elasticsearch.ElasticsearchScan;
import org.opensearch.sql.example.e2e.SearchPushdownCases.Case;

/** Assert operations in the actual HTTP execution plan rather than matching a rendered snapshot. */
final class SearchPlanAssertions {
  private SearchPlanAssertions() {}

  static void verify(RelNode root, Case test) {
    List<RelNode> nodes = nodes(root);
    List<ElasticsearchScan> scans =
        nodes.stream()
            .filter(ElasticsearchScan.class::isInstance)
            .map(ElasticsearchScan.class::cast)
            .toList();
    String plan = RelOptUtil.toString(root);
    assertEquals(1, scans.size(), plan);
    var scan = scans.getFirst().queryPlan();
    assertTrue(
        hasQuery(scan.query(), "range", "timestamp"), "Missing native time constraint:\n" + plan);
    boolean residual = nodes.stream().anyMatch(SearchPlanAssertions::isFilter);
    boolean localOrder =
        nodes.stream()
            .anyMatch(n -> n instanceof Sort s && !s.getCollation().getFieldCollations().isEmpty());
    boolean localLimit = nodes.stream().anyMatch(EnumerableLimit.class::isInstance);
    switch (test.expectation()) {
      case ALL -> {
        assertFalse(residual, "Filter regressed to local execution:\n" + plan);
        assertFalse(localOrder, "Document sort regressed to local execution:\n" + plan);
        assertFalse(localLimit, "Result window regressed to local execution:\n" + plan);
        assertFalse(scan.sort().isEmpty(), "Missing native ordering:\n" + plan);
        assertEquals((long) test.size() + 1, scan.fetch(), plan);
        assertEquals((long) (test.page() - 1) * test.size(), scan.offset(), plan);
      }
      case PARTIAL -> {
        assertTrue(
            hasQuery(scan.query(), "term", "extracted.process.name"),
            "Lost necessary native predicate:\n" + plan);
        assertTrue(residual, "Missing mandatory local predicate:\n" + plan);
        assertFalse(localOrder, "Ordering did not cross the residual filter:\n" + plan);
        assertFalse(scan.sort().isEmpty(), plan);
        assertNull(scan.fetch(), "Window crossed the residual filter:\n" + plan);
        assertEquals(0, scan.offset(), plan);
        assertTrue(localLimit, "Missing outer result window:\n" + plan);
        assertFalse(
            hasQuery(scan.query(), "regexp", "message.keyword"),
            "ignore_above changed source semantics:\n" + plan);
      }
      case LOCAL_OR -> {
        assertFalse(
            hasQuery(scan.query(), "term", "extracted.process.name"),
            "Mixed OR was partially pushed:\n" + plan);
        assertTrue(residual, plan);
        assertNull(scan.fetch(), plan);
        assertTrue(localLimit, plan);
        assertFalse(localOrder, plan);
      }
      case BOUNDED -> {
        assertFalse(residual, plan);
        assertFalse(localOrder, plan);
        assertFalse(scan.sort().isEmpty(), plan);
        assertEquals(3L, scan.fetch(), "User head/LIMIT was not retained:\n" + plan);
        assertEquals(test.id().startsWith("sql_") ? 2L : 0L, scan.offset(), plan);
      }
      case AFTER_HEAD -> {
        assertEquals(3L, scan.fetch(), plan);
        assertTrue(residual, plan);
        assertFalse(
            hasQuery(scan.query(), "regexp", "message.wc")
                || hasQuery(scan.query(), "wildcard", "message.wc"),
            "Predicate moved below head:\n" + plan);
        assertTrue(localLimit, plan);
      }
      case AGGREGATE -> {
        assertTrue(scan.isAggregate(), "Aggregation regressed to local execution:\n" + plan);
        assertFalse(nodes.stream().anyMatch(Aggregate.class::isInstance), plan);
      }
      case LOCAL_AGGREGATE -> {
        assertFalse(scan.isAggregate(), "Unsafe aggregation was pushed:\n" + plan);
        assertTrue(
            nodes.stream().anyMatch(Aggregate.class::isInstance),
            "Missing local aggregation:\n" + plan);
        assertNull(scan.fetch(), "Window crossed local aggregation:\n" + plan);
      }
      case LOCAL_TRANSFORM -> {
        assertFalse(scan.isAggregate(), plan);
        assertTrue(
            nodes.stream().anyMatch(n -> n instanceof Window || n instanceof Aggregate),
            "Dedup lost its local reduction:\n" + plan);
        assertNull(scan.fetch(), "Window crossed local dedup:\n" + plan);
      }
      case REJECT -> fail("Rejected query must not produce a physical plan");
    }
    if (test.id().equals("ppl_original") || test.id().equals("structured_contains")) {
      assertTrue(hasQuery(scan.query(), "term", "extracted.process.name"), plan);
      assertTrue(
          hasQuery(scan.query(), "regexp", "message.wc")
              || hasQuery(scan.query(), "wildcard", "message.wc"),
          "Text pattern did not use its safe wildcard multi-field:\n" + plan);
    }
  }

  static void verifyHistogram(RelNode root) {
    List<ElasticsearchScan> scans =
        nodes(root).stream()
            .filter(ElasticsearchScan.class::isInstance)
            .map(ElasticsearchScan.class::cast)
            .toList();
    String plan = RelOptUtil.toString(root);
    assertEquals(1, scans.size(), plan);
    assertTrue(scans.getFirst().queryPlan().isAggregate(), plan);
    assertFalse(scans.getFirst().queryPlan().groups().isEmpty(), plan);
    assertTrue(plan.contains("date_histogram"), "Histogram regressed to local execution:\n" + plan);
  }

  private static boolean isFilter(RelNode node) {
    return node instanceof Filter
        || node instanceof EnumerableCalc calc && calc.getProgram().getCondition() != null;
  }

  private static List<RelNode> nodes(RelNode root) {
    List<RelNode> result = new ArrayList<>();
    result.add(root);
    root.getInputs().forEach(input -> result.addAll(nodes(input)));
    return result;
  }

  private static boolean hasQuery(Object value, String operation, String field) {
    if (value instanceof Map<?, ?> map) {
      if (map.get(operation) instanceof Map<?, ?> fields && fields.containsKey(field)) return true;
      return map.values().stream().anyMatch(child -> hasQuery(child, operation, field));
    }
    return value instanceof List<?> list
        && list.stream().anyMatch(child -> hasQuery(child, operation, field));
  }
}
