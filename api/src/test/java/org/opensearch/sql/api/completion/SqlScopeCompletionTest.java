/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Test;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.backend.QueryBackend;
import org.opensearch.sql.executor.CalciteExecutionEngine;
import org.opensearch.sql.executor.ExecutionEngine;
import org.opensearch.sql.executor.QueryType;

/** Uses the same live schema for completion and execution, accepting actual server edits. */
public class SqlScopeCompletionTest {
  private final QueryBackend backend =
      new QueryBackend() {
        public String name() {
          return "memory";
        }

        public String type() {
          return "memory";
        }

        public ExecutionEngine executionEngine() {
          return new CalciteExecutionEngine();
        }

        public Schema createSchema(SchemaPlus parent) {
          return new AbstractSchema() {
            @Override
            protected Map<String, Table> getTableMap() {
              return Map.of(
                  "logs",
                  new Rows("id", "message"),
                  "orders",
                  new Rows("id", "amount"),
                  "third",
                  new Rows("id", "secret"));
            }
          };
        }
      };
  private final QueryMetadataService metadata =
      new QueryMetadataService(List.of(backend), "memory", Map.of());
  private final QueryCompletionService completions =
      new QueryCompletionService(metadata, List.of(new SqlCompletionProvider()));
  private final UnifiedQueryService execution = new UnifiedQueryService(List.of(backend), "memory");

  private static class Rows extends AbstractTable implements ScannableTable {
    private final List<String> columns;

    Rows(String... columns) {
      this.columns = List.of(columns);
    }

    public RelDataType getRowType(RelDataTypeFactory factory) {
      var row = factory.builder();
      columns.forEach(
          column ->
              row.add(
                  column,
                  column.equals("id") || column.equals("amount")
                      ? SqlTypeName.INTEGER
                      : SqlTypeName.VARCHAR));
      return row.build();
    }

    public Enumerable<Object[]> scan(DataContext context) {
      return Linq4j.asEnumerable(
          Collections.singletonList(
              columns.stream()
                  .map(
                      column ->
                          column.equals("id") || column.equals("amount") ? (Object) 1 : "alpha")
                  .toArray()));
    }
  }

  private CompletionResponse complete(String marked) {
    int caret = marked.indexOf('¦');
    assertTrue("A caret marker is required", caret >= 0);
    return completions.complete(
        new CompletionRequest("sql", "memory", marked.replace("¦", ""), caret, 1, "manual"));
  }

  private String accept(String marked, String label) {
    var item =
        complete(marked).items().stream()
            .filter(i -> i.label().equals(label))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + label + " in " + marked));
    var edit = item.textEdit();
    String raw = marked.replace("¦", "");
    return raw.substring(0, edit.from()) + edit.newText() + raw.substring(edit.to());
  }

  private void executesOneRow(String query) {
    assertEquals(query, 1, execution.execute(QueryType.SQL, query).getResults().size());
  }

  @Test
  public void joinOnRejectsFutureAliasAndAcceptedVisibleFieldExecutes() {
    String query = "SELECT l.message FROM logs l JOIN orders o ON ¦=o.id JOIN third t ON t.id=o.id";
    assertFalse(
        complete(query).items().stream()
            .anyMatch(i -> i.label().startsWith("t.") || i.label().equals("secret")));
    executesOneRow(accept(query, "l.id"));
  }

  @Test
  public void nestedOrderDoesNotExposeOuterSelectAliasToWhere() {
    String query =
        "SELECT message AS msg FROM logs WHERE id IN (SELECT id FROM orders ORDER BY id) AND ¦ > 0";
    assertFalse(complete(query).items().stream().anyMatch(i -> i.label().equals("msg")));
    executesOneRow(accept(query, "id"));
  }

  @Test
  public void computedDerivedNamesWithSpacesExecuteAfterAcceptingTheEdit() {
    executesOneRow(accept("SELECT d.¦ FROM (SELECT id + 1 FROM logs) d", "d.id + 1"));
    executesOneRow(accept("SELECT d.¦ FROM (SELECT COUNT( * ) FROM logs) d", "d.COUNT( * )"));
  }

  @Test
  public void correlatedOuterAliasIsCompletedAndExecutes() {
    executesOneRow(
        accept(
            "SELECT * FROM logs l WHERE EXISTS (SELECT * FROM orders o WHERE l.¦=o.id)", "l.id"));
    executesOneRow(
        accept(
            "SELECT * FROM logs l WHERE EXISTS (SELECT * FROM orders o WHERE EXISTS (SELECT * FROM"
                + " third t WHERE l.¦=t.id))",
            "l.id"));
  }

  @Test
  public void textOperatorsExcludeArithmeticAndOrderingAndAcceptedPredicatesExecute() {
    String query = "SELECT * FROM logs WHERE message ¦";
    var response = complete(query);
    var forbidden = java.util.Set.of("+", "-", "*", "/", "%", ">", ">=", "<", "<=", "BETWEEN", "!");
    assertFalse(response.items().stream().anyMatch(i -> forbidden.contains(i.label())));
    executesOneRow(accept(query, "LIKE") + "'alpha'");
    executesOneRow(accept(query, "!=") + "'beta'");
    executesOneRow(accept("SELECT * FROM logs WHERE id ¦", ">=") + "1");
    executesOneRow(accept("SELECT * FROM logs WHERE LOWER(message) ¦", "LIKE") + "'alpha'");
    executesOneRow(accept("SELECT * FROM logs WHERE CAST(id AS STRING) ¦", "=") + "'1'");
  }

  @Test
  public void spacedQualifiedEditsKeepTheFollowingClauseAndExecute() {
    executesOneRow(accept("SELECT l . me¦ssage FROM logs l", "l.message"));
    executesOneRow(accept("SELECT l . ¦ FROM logs l", "l.message"));
    executesOneRow(
        accept(
            "SELECT l\\n.\\tme¦ssage FROM logs l".replace("\\n", "\n").replace("\\t", "\t"),
            "l.message"));
  }
}
