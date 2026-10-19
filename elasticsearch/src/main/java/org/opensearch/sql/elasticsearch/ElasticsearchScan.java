/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.adapter.enumerable.JavaRowFormat;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.tree.Blocks;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.sql.ast.tree.HighlightConfig;
import org.opensearch.sql.calcite.plan.HighlightPushDown;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.ElasticsearchTable;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;
import org.opensearch.sql.elasticsearch.planner.rules.ElasticsearchRules;

/** Calcite leaf holding an immutable remote plan. Execution state belongs to its enumerator. */
public final class ElasticsearchScan extends TableScan implements EnumerableRel, HighlightPushDown {
  private final RelOptTable table;
  private final ElasticsearchTable source;
  private final ElasticsearchQueryPlan queryPlan;
  private final double filteredRows;

  private ElasticsearchScan(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelOptTable table,
      ElasticsearchTable source,
      RelDataType type,
      ElasticsearchQueryPlan queryPlan,
      double filteredRows) {
    super(cluster, traits, List.of(), table);
    this.table = table;
    this.source = source;
    this.rowType = type;
    this.queryPlan = queryPlan;
    this.filteredRows = filteredRows;
  }

  static ElasticsearchScan create(
      RelOptCluster cluster, RelOptTable table, ElasticsearchTable source) {
    return new ElasticsearchScan(
        cluster,
        cluster.traitSetOf(Convention.NONE),
        table,
        source,
        table.getRowType(),
        new ElasticsearchQueryPlan(
            source.fields, Map.of("match_all", Map.of()), List.of(), 0, null, null, null),
        100000);
  }

  public ElasticsearchQueryPlan queryPlan() {
    return queryPlan;
  }

  /** Complete mapping metadata remains available after projection prunes materialized columns. */
  public List<Field> sourceFields() {
    return source.fields;
  }

  public boolean pushdownEnabled() {
    return source.backend.pushdownEnabled();
  }

  public ElasticsearchScan withPlan(
      RelDataType type, RelTraitSet traits, ElasticsearchQueryPlan plan) {
    return new ElasticsearchScan(getCluster(), traits, table, source, type, plan, filteredRows);
  }

  /** Retain the selectivity of each native predicate when comparing local and remote plans. */
  public ElasticsearchScan withSelectivity(double selectivity) {
    return new ElasticsearchScan(
        getCluster(), traitSet, table, source, rowType, queryPlan, filteredRows * selectivity);
  }

  /** Materialize additional source columns without changing existing input ordinals. */
  public ElasticsearchScan appendFields(List<Field> additional) {
    if (additional.isEmpty()) return this;
    List<Field> fields = new ArrayList<>(queryPlan.fields());
    var types = getCluster().getTypeFactory();
    var builder = types.builder().addAll(rowType.getFieldList());
    for (Field field : additional) {
      fields.add(field);
      builder.add(field.name(), types.createTypeWithNullability(field.type(types), true));
    }
    return withPlan(
        builder.build(),
        traitSet,
        new ElasticsearchQueryPlan(
            fields,
            queryPlan.query(),
            queryPlan.sort(),
            queryPlan.offset(),
            queryPlan.fetch(),
            queryPlan.aggregation(),
            queryPlan.highlight()));
  }

  @Override
  public RelNode pushDownHighlight(HighlightConfig config) {
    List<Field> fields = new ArrayList<>(queryPlan.fields());
    fields.add(new Field("_highlight", "_highlight", "object"));
    RelDataType type =
        getCluster()
            .getTypeFactory()
            .builder()
            .addAll(rowType.getFieldList())
            .add("_highlight", getCluster().getTypeFactory().createSqlType(SqlTypeName.ANY))
            .build();
    return withPlan(
        type,
        traitSet,
        new ElasticsearchQueryPlan(
            fields,
            queryPlan.query(),
            queryPlan.sort(),
            queryPlan.offset(),
            queryPlan.fetch(),
            queryPlan.aggregation(),
            config));
  }

  @Override
  public RelOptTable getTable() {
    return table;
  }

  @Override
  public RelNode copy(RelTraitSet traits, List<RelNode> inputs) {
    return withPlan(rowType, traits, queryPlan);
  }

  @Override
  public double estimateRowCount(RelMetadataQuery mq) {
    double rows = queryPlan.isAggregate() ? 100 : Math.max(0, filteredRows - queryPlan.offset());
    return queryPlan.fetch() == null ? rows : Math.min(rows, queryPlan.fetch());
  }

  @Override
  public RelWriter explainTerms(RelWriter writer) {
    return super.explainTerms(writer)
        .item("index", source.index)
        .item("query", queryPlan.query())
        .item("fields", queryPlan.fields().stream().map(Field::name).toList())
        .itemIf("sort", queryPlan.sort(), !queryPlan.sort().isEmpty())
        .itemIf("offset", queryPlan.offset(), queryPlan.offset() != 0)
        .itemIf("fetch", queryPlan.fetch(), queryPlan.fetch() != null)
        .itemIf("aggregation", aggregateBody(), queryPlan.isAggregate())
        .itemIf(
            "grouping",
            ElasticsearchRequestBuilder.groupSources(queryPlan),
            !queryPlan.groups().isEmpty())
        .itemIf("pushdown", "disabled", !pushdownEnabled())
        .itemIf("fieldConstraints", fieldConstraints(), !fieldConstraints().isEmpty());
  }

  private Map<String, String> fieldConstraints() {
    Map<String, String> reasons = new java.util.LinkedHashMap<>();
    for (Field field : queryPlan.fields()) {
      if (field.incompatibilityReason() != null
          || (field.supportsExistence() && !field.supportsComparison())) {
        reasons.put(field.name(), field.comparisonRejectionReason());
      }
    }
    return reasons;
  }

  private Map<String, Object> aggregateBody() {
    Map<String, Object> metrics = new java.util.LinkedHashMap<>();
    for (int i = 0; i < queryPlan.metrics().size(); i++)
      metrics.putAll(queryPlan.metrics().get(i).aggregations(i));
    return metrics;
  }

  @Override
  public void register(RelOptPlanner planner) {
    ElasticsearchRules.ALL.forEach(planner::addRule);
  }

  @Override
  public Result implement(EnumerableRelImplementor implementor, Prefer prefer) {
    var type = PhysTypeImpl.of(implementor.getTypeFactory(), rowType, JavaRowFormat.ARRAY);
    return implementor.result(
        type,
        Blocks.toBlock(Expressions.call(implementor.stash(this, ElasticsearchScan.class), "scan")));
  }

  public Enumerable<?> scan() {
    Enumerable<Object[]> rows =
        new AbstractEnumerable<>() {
          @Override
          public Enumerator<Object[]> enumerator() {
            return new ElasticsearchSearchEnumerator(source, queryPlan, rowType);
          }
        };
    if (rowType.getFieldCount() == 0)
      return rows.select(
          row -> (Object) org.apache.calcite.runtime.FlatLists.COMPARABLE_EMPTY_LIST);
    if (rowType.getFieldCount() == 1) return rows.select(row -> row[0]);
    return rows;
  }
}
