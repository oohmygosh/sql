/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.apache.calcite.plan.RelOptRule.none;
import static org.apache.calcite.plan.RelOptRule.operand;
import static org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.object;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.adapter.enumerable.EnumerableConvention;
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
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.ElasticsearchTable;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;

/** Immutable search plan. Every Enumerator owns its pagination state and remote context. */
public final class ElasticsearchScan extends org.apache.calcite.rel.core.TableScan
    implements EnumerableRel, org.opensearch.sql.calcite.plan.HighlightPushDown {
  private final RelOptTable table;
  private final ElasticsearchTable source;
  private final List<Field> fields;
  private final Map<String, Object> query;
  private final List<Object> sort;
  private final long offset;
  private final Long fetch;
  private final List<Field> groups;
  private final List<AggregateCall> aggregates;
  private final org.opensearch.sql.ast.tree.HighlightConfig highlight;

  private ElasticsearchScan(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelOptTable table,
      ElasticsearchTable source,
      RelDataType type,
      List<Field> fields,
      Map<String, Object> query,
      List<Object> sort,
      long offset,
      Long fetch,
      List<Field> groups,
      List<AggregateCall> aggregates,
      org.opensearch.sql.ast.tree.HighlightConfig highlight) {
    super(cluster, traits, List.of(), table);
    this.table = table;
    this.source = source;
    this.rowType = type;
    this.fields = List.copyOf(fields);
    this.query = query;
    this.sort = List.copyOf(sort);
    this.offset = offset;
    this.fetch = fetch;
    this.groups = groups;
    this.aggregates = aggregates;
    this.highlight = highlight;
  }

  @Override
  public RelNode pushDownHighlight(org.opensearch.sql.ast.tree.HighlightConfig config) {
    List<Field> resultFields = new ArrayList<>(fields);
    resultFields.add(new Field("_highlight", "_highlight", "object"));
    RelDataType type =
        getCluster()
            .getTypeFactory()
            .builder()
            .addAll(rowType.getFieldList())
            .add("_highlight", getCluster().getTypeFactory().createSqlType(SqlTypeName.ANY))
            .build();
    return new ElasticsearchScan(
        getCluster(),
        traitSet,
        table,
        source,
        type,
        resultFields,
        query,
        sort,
        offset,
        fetch,
        groups,
        aggregates,
        config);
  }

  static ElasticsearchScan create(
      RelOptCluster cluster, RelOptTable table, ElasticsearchTable source) {
    return new ElasticsearchScan(
        cluster,
        cluster.traitSetOf(Convention.NONE),
        table,
        source,
        table.getRowType(),
        source.fields,
        Map.of("match_all", Map.of()),
        List.of(),
        0,
        null,
        null,
        null,
        null);
  }

  @Override
  public RelOptTable getTable() {
    return table;
  }

  @Override
  public RelNode copy(RelTraitSet traits, List<RelNode> inputs) {
    return new ElasticsearchScan(
        getCluster(),
        traits,
        table,
        source,
        rowType,
        fields,
        query,
        sort,
        offset,
        fetch,
        groups,
        aggregates,
        highlight);
  }

  @Override
  public double estimateRowCount(RelMetadataQuery mq) {
    return fetch != null
        ? fetch
        : aggregates != null ? 100 : query.containsKey("match_all") ? 100000 : 10000;
  }

  @Override
  public RelWriter explainTerms(RelWriter writer) {
    return super.explainTerms(writer)
        .item("index", source.index)
        .item("query", query)
        .item("fields", fields.stream().map(Field::name).toList())
        .itemIf("sort", sort, !sort.isEmpty())
        .itemIf("offset", offset, offset != 0)
        .itemIf("fetch", fetch, fetch != null)
        .itemIf("aggregation", aggregateBody(), aggregates != null);
  }

  @Override
  public void register(RelOptPlanner planner) {
    planner.addRule(TO_ENUMERABLE);
    planner.addRule(FILTER);
    planner.addRule(PROJECT);
    planner.addRule(SORT);
    planner.addRule(AGGREGATE);
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
            return new SearchEnumerator();
          }
        };
    // Match Calcite's optimized physical row format: zero columns use a comparable empty
    // list, one column is a scalar, and multiple columns use Object[].
    if (rowType.getFieldCount() == 0)
      return rows.select(
          row -> (Object) org.apache.calcite.runtime.FlatLists.COMPARABLE_EMPTY_LIST);
    if (rowType.getFieldCount() == 1) return rows.select(row -> row[0]);
    return rows;
  }

  private static final ConverterRule TO_ENUMERABLE =
      new ConverterRule(
          ElasticsearchScan.class,
          Convention.NONE,
          EnumerableConvention.INSTANCE,
          "ElasticsearchToEnumerable") {
        @Override
        public RelNode convert(RelNode rel) {
          return rel.copy(rel.getTraitSet().replace(EnumerableConvention.INSTANCE), List.of());
        }
      };

  private static final RelOptRule FILTER =
      new RelOptRule(
          operand(Filter.class, operand(ElasticsearchScan.class, none())), "ElasticsearchFilter") {
        @Override
        public void onMatch(RelOptRuleCall call) {
          Filter filter = call.rel(0);
          ElasticsearchScan scan = call.rel(1);
          if (scan.aggregates != null || scan.fetch != null || scan.offset != 0) return;
          List<Map<String, Object>> accepted = new ArrayList<>();
          List<org.apache.calcite.rex.RexNode> remaining = new ArrayList<>();
          for (var condition :
              org.apache.calcite.plan.RelOptUtil.conjunctions(filter.getCondition())) {
            var predicate =
                ElasticsearchPredicate.translate(
                    org.apache.calcite.rex.RexUtil.expandSearch(
                        scan.getCluster().getRexBuilder(), null, condition),
                    scan.fields);
            if (predicate == null) remaining.add(condition);
            else accepted.add(predicate);
          }
          if (accepted.isEmpty()) return;
          accepted.add(scan.query);
          RelNode pushed =
              new ElasticsearchScan(
                  scan.getCluster(),
                  scan.traitSet,
                  scan.table,
                  scan.source,
                  filter.getRowType(),
                  scan.fields,
                  ElasticsearchPredicate.and(accepted),
                  scan.sort,
                  scan.offset,
                  scan.fetch,
                  null,
                  null,
                  scan.highlight);
          if (!remaining.isEmpty())
            pushed =
                filter.copy(
                    filter.getTraitSet(),
                    pushed,
                    org.apache.calcite.rex.RexUtil.composeConjunction(
                        scan.getCluster().getRexBuilder(), remaining));
          call.transformTo(pushed);
        }
      };

  private static final RelOptRule PROJECT =
      new RelOptRule(
          operand(Project.class, operand(ElasticsearchScan.class, none())),
          "ElasticsearchProject") {
        @Override
        public void onMatch(RelOptRuleCall call) {
          Project project = call.rel(0);
          ElasticsearchScan scan = call.rel(1);
          if (scan.aggregates != null
              || project.getProjects().stream().anyMatch(p -> !(p instanceof RexInputRef))) return;
          List<Field> fields =
              project.getProjects().stream()
                  .map(p -> scan.fields.get(((RexInputRef) p).getIndex()))
                  .toList();
          call.transformTo(
              new ElasticsearchScan(
                  scan.getCluster(),
                  scan.traitSet.replace(org.apache.calcite.rel.RelCollations.EMPTY),
                  scan.table,
                  scan.source,
                  project.getRowType(),
                  fields,
                  scan.query,
                  scan.sort,
                  scan.offset,
                  scan.fetch,
                  null,
                  null,
                  scan.highlight));
        }
      };

  private static final RelOptRule SORT =
      new RelOptRule(
          operand(Sort.class, operand(ElasticsearchScan.class, none())), "ElasticsearchSort") {
        @Override
        public void onMatch(RelOptRuleCall call) {
          Sort sort = call.rel(0);
          ElasticsearchScan scan = call.rel(1);
          if (scan.aggregates != null || scan.fetch != null || scan.offset != 0) return;
          if ((sort.offset != null && !(sort.offset instanceof RexLiteral))
              || (sort.fetch != null && !(sort.fetch instanceof RexLiteral))) return;
          List<Object> ordering = new ArrayList<>();
          for (var collation : sort.getCollation().getFieldCollations()) {
            Field field = scan.fields.get(collation.getFieldIndex());
            if (!field.exact() || field.name().equals("_id")) return;
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("order", collation.direction.isDescending() ? "desc" : "asc");
            if (!field.name().equals("_score"))
              options.put(
                  "missing",
                  collation.nullDirection
                          == org.apache.calcite.rel.RelFieldCollation.NullDirection.FIRST
                      ? "_first"
                      : "_last");
            ordering.add(Map.of(field.name(), options));
          }
          if (ordering.isEmpty()) ordering = scan.sort;
          call.transformTo(
              new ElasticsearchScan(
                  scan.getCluster(),
                  scan.traitSet.replace(sort.getCollation()),
                  scan.table,
                  scan.source,
                  sort.getRowType(),
                  scan.fields,
                  scan.query,
                  ordering,
                  sort.offset == null ? 0 : ((RexLiteral) sort.offset).getValueAs(Long.class),
                  sort.fetch == null ? null : ((RexLiteral) sort.fetch).getValueAs(Long.class),
                  null,
                  null,
                  scan.highlight));
        }
      };

  private static final RelOptRule AGGREGATE =
      new RelOptRule(
          operand(Aggregate.class, operand(ElasticsearchScan.class, none())),
          "ElasticsearchAggregate") {
        @Override
        public void onMatch(RelOptRuleCall call) {
          Aggregate aggregate = call.rel(0);
          ElasticsearchScan scan = call.rel(1);
          if (scan.aggregates != null
              || scan.fetch != null
              || scan.offset != 0
              || !Aggregate.isSimple(aggregate)) return;
          List<Field> groups =
              aggregate.getGroupSet().asList().stream().map(scan.fields::get).toList();
          if (groups.stream()
              .anyMatch(f -> !f.exact() || List.of("_id", "_score").contains(f.name()))) return;
          for (AggregateCall agg : aggregate.getAggCallList()) {
            String name = agg.getAggregation().getName().toUpperCase(java.util.Locale.ROOT);
            if (agg.filterArg >= 0
                || (agg.isDistinct() && !agg.isApproximate())
                || !agg.collation.getFieldCollations().isEmpty()
                || !List.of(
                        "COUNT",
                        "SUM",
                        "$SUM0",
                        "SUM0",
                        "MIN",
                        "MAX",
                        "AVG",
                        "DISTINCT_COUNT_APPROX",
                        "APPROX_COUNT_DISTINCT")
                    .contains(name)
                || agg.getArgList().size() > 1
                || (agg.getArgList().isEmpty() && !name.equals("COUNT"))) return;
            if (!agg.getArgList().isEmpty()
                && (!scan.fields.get(agg.getArgList().getFirst()).exact()
                    || List.of("_id", "_score")
                        .contains(scan.fields.get(agg.getArgList().getFirst()).name()))) return;
            if (!agg.getArgList().isEmpty()
                && List.of("MIN", "MAX", "SUM", "SUM0", "$SUM0").contains(name)) {
              SqlTypeName inputType = scan.fields.get(agg.getArgList().getFirst()).sqlType();
              // ES numeric metrics use doubles; preserve exact 64-bit/unsigned results locally.
              if (inputType == SqlTypeName.BIGINT || inputType == SqlTypeName.DECIMAL) return;
            }
          }
          call.transformTo(
              new ElasticsearchScan(
                  scan.getCluster(),
                  scan.traitSet.replace(org.apache.calcite.rel.RelCollations.EMPTY),
                  scan.table,
                  scan.source,
                  aggregate.getRowType(),
                  scan.fields,
                  scan.query,
                  List.of(),
                  0,
                  null,
                  groups,
                  List.copyOf(aggregate.getAggCallList()),
                  scan.highlight));
        }
      };

  private Map<String, Object> aggregateBody() {
    if (aggregates == null) return Map.of();
    Map<String, Object> metrics = new LinkedHashMap<>();
    for (int i = 0; i < aggregates.size(); i++) {
      AggregateCall agg = aggregates.get(i);
      if (agg.getArgList().isEmpty()) continue;
      String field = fields.get(agg.getArgList().getFirst()).name();
      String name = agg.getAggregation().getName().toUpperCase(java.util.Locale.ROOT);
      String operation =
          switch (name) {
            case "COUNT" -> agg.isDistinct() && agg.isApproximate() ? "cardinality" : "value_count";
            case "DISTINCT_COUNT_APPROX", "APPROX_COUNT_DISTINCT" -> "cardinality";
            case "SUM", "SUM0", "$SUM0" -> "sum";
            default -> name.toLowerCase(java.util.Locale.ROOT);
          };
      metrics.put("a" + i, Map.of(operation, Map.of("field", field)));
      if (name.equals("SUM")) metrics.put("n" + i, Map.of("value_count", Map.of("field", field)));
    }
    return metrics;
  }

  private final class SearchEnumerator implements Enumerator<Object[]> {
    private Iterator<Map<String, Object>> page = Collections.emptyIterator();
    private Object[] current;
    private String pit;
    private Object after;
    private boolean finished;
    private long skipped;
    private long emitted;

    @Override
    public Object[] current() {
      return current;
    }

    @Override
    public boolean moveNext() {
      if (fetch != null && emitted >= fetch) {
        close();
        return false;
      }
      try {
        while (true) {
          if (!page.hasNext()) {
            if (finished) {
              close();
              return false;
            }
            loadPage();
            if (!page.hasNext()) {
              close();
              return false;
            }
          }
          Map<String, Object> hit = page.next();
          if (skipped < offset) {
            skipped++;
            continue;
          }
          current = aggregates == null ? documentRow(hit) : aggregateRow(hit);
          emitted++;
          return true;
        }
      } catch (RuntimeException e) {
        try {
          close();
        } catch (RuntimeException cleanup) {
          e.addSuppressed(cleanup);
        }
        throw e;
      }
    }

    @SuppressWarnings("unchecked")
    private void loadPage() {
      if (pit == null) {
        pit =
            String.valueOf(
                source
                    .backend
                    .request(
                        "POST",
                        "/" + ElasticsearchQueryBackend.path(source.index) + "/_pit?keep_alive=1m",
                        null)
                    .get("id"));
      }
      Map<String, Object> request = new LinkedHashMap<>();
      request.put("query", query);
      request.put("pit", Map.of("id", pit, "keep_alive", "1m"));
      if (aggregates == null) {
        // This is a transport page size, not a result limit. Continue until exhaustion.
        request.put(
            "size",
            fetch == null ? 1000 : Math.min(1000, fetch - emitted + Math.max(0, offset - skipped)));
        List<Object> ordering = new ArrayList<>(sort);
        ordering.add(Map.of("_shard_doc", "asc"));
        request.put("sort", ordering);
        if (highlight != null) {
          Map<String, Object> options = new LinkedHashMap<>();
          options.put("fields", highlight.fields());
          if (highlight.preTags() != null) options.put("pre_tags", highlight.preTags());
          if (highlight.postTags() != null) options.put("post_tags", highlight.postTags());
          if (highlight.fragmentSize() != null)
            options.put("fragment_size", highlight.fragmentSize());
          request.put("highlight", options);
        }
        if (fields.stream().anyMatch(field -> field.name().equals("_score")))
          request.put("track_scores", true);
        request.put("_source", fields.stream().flatMap(Field::sourcePaths).distinct().toList());
        if (after != null) request.put("search_after", after);
      } else {
        request.put("size", 0);
        if (groups.isEmpty()) {
          request.put("track_total_hits", true);
          request.put("aggs", aggregateBody());
        } else {
          List<Object> sources = new ArrayList<>();
          for (int i = 0; i < groups.size(); i++)
            sources.add(
                Map.of(
                    "g" + i,
                    Map.of(
                        "terms", Map.of("field", groups.get(i).name(), "missing_bucket", true))));
          Map<String, Object> composite = new LinkedHashMap<>();
          composite.put("size", 1000);
          composite.put("sources", sources);
          if (after != null) composite.put("after", after);
          request.put(
              "aggs", Map.of("groups", Map.of("composite", composite, "aggs", aggregateBody())));
        }
      }
      Map<String, Object> response = source.backend.request("POST", "/_search", request);
      if (Boolean.TRUE.equals(response.get("timed_out"))
          || ((Number) object(response.get("_shards")).getOrDefault("failed", 0)).intValue() > 0)
        throw new IllegalStateException("Elasticsearch returned an incomplete search response");
      if (response.get("pit_id") != null) pit = response.get("pit_id").toString();
      List<Map<String, Object>> rows;
      if (aggregates == null) {
        rows = (List<Map<String, Object>>) object(response.get("hits")).get("hits");
        if (!rows.isEmpty()) after = rows.getLast().get("sort");
        finished = rows.isEmpty();
      } else if (groups.isEmpty()) {
        Map<String, Object> row = new LinkedHashMap<>(object(response.get("aggregations")));
        row.put("doc_count", object(object(response.get("hits")).get("total")).get("value"));
        rows = List.of(row);
        finished = true;
      } else {
        Map<String, Object> buckets = object(object(response.get("aggregations")).get("groups"));
        rows = (List<Map<String, Object>>) buckets.get("buckets");
        after = buckets.get("after_key");
        finished = after == null || rows.isEmpty();
      }
      page = rows.iterator();
    }

    private Object[] documentRow(Map<String, Object> hit) {
      Object[] values = new Object[fields.size()];
      Map<String, Object> document = object(hit.get("_source"));
      for (int i = 0; i < values.length; i++) {
        Object value = document;
        for (String segment : fields.get(i).source(String.valueOf(hit.get("_index"))).split("\\."))
          value = object(value).get(segment);
        if (fields.get(i).name().equals("_id")
            || fields.get(i).name().equals("_index")
            || fields.get(i).name().equals("_score")) value = hit.get(fields.get(i).name());
        if (fields.get(i).name().equals("_highlight")) value = object(hit.get("highlight"));
        values[i] = convert(value, rowType.getFieldList().get(i).getType());
      }
      return values;
    }

    private Object[] aggregateRow(Map<String, Object> bucket) {
      Object[] values = new Object[groups.size() + aggregates.size()];
      for (int i = 0; i < groups.size(); i++) values[i] = object(bucket.get("key")).get("g" + i);
      for (int i = 0; i < aggregates.size(); i++) {
        AggregateCall agg = aggregates.get(i);
        Object value =
            agg.getArgList().isEmpty()
                ? bucket.get("doc_count")
                : object(bucket.get("a" + i)).get("value");
        if (agg.getAggregation().getName().equalsIgnoreCase("SUM")
            && ((Number) object(bucket.get("n" + i)).getOrDefault("value", 0)).longValue() == 0)
          value = null;
        values[groups.size() + i] = value;
      }
      for (int i = 0; i < values.length; i++)
        values[i] = convert(values[i], rowType.getFieldList().get(i).getType());
      return values;
    }

    @Override
    public void reset() {
      throw new UnsupportedOperationException("Create a new Enumerator to repeat the search");
    }

    @Override
    public void close() {
      finished = true;
      page = Collections.emptyIterator();
      current = null;
      if (pit != null) {
        String id = pit;
        pit = null;
        source.backend.request("DELETE", "/_pit", Map.of("id", id));
      }
    }
  }

  private static Object convert(Object value, RelDataType type) {
    if (value == null) return null;
    return switch (type.getSqlTypeName()) {
      case TINYINT -> ((Number) value).byteValue();
      case SMALLINT -> ((Number) value).shortValue();
      case INTEGER -> ((Number) value).intValue();
      case BIGINT -> ((Number) value).longValue();
      case FLOAT, REAL -> ((Number) value).floatValue();
      case DOUBLE -> ((Number) value).doubleValue();
      case DECIMAL -> new BigDecimal(value.toString());
      case ARRAY ->
          ((List<?>) value).stream().map(item -> convert(item, type.getComponentType())).toList();
      case TIMESTAMP ->
          value instanceof Number number
              ? number.longValue()
              : Instant.parse(value.toString()).toEpochMilli();
      default -> value;
    };
  }
}
