/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.backend.QueryBackend;
import org.opensearch.sql.executor.CalciteExecutionEngine;
import org.opensearch.sql.executor.ExecutionEngine;
import org.opensearch.sql.executor.QueryType;

/**
 * The editor and executor read the same typed schema; every selected edit feeds the next request.
 */
@RunWith(Parameterized.class)
public class PplOperatorJourneyTest {
  private static final String SOURCE = "source=`typed_values` | ";
  private static final Set<String> NUMBER =
      Set.of("=", "!=", ">", "<", ">=", "<=", "in", "not", "is", "between");
  private static final Set<String> TEXT =
      Set.of("=", "!=", "in", "not", "is", "like", "ilike", "regexp", "contains");
  private static final Set<String> BOOLEAN = Set.of("=", "!=", "in", "not", "is");
  private static final Set<String> MATH = Set.of("*", "/", "%", "+", "-");
  private static final Set<String> TYPE_OPERATORS =
      java.util.stream.Stream.of(NUMBER, TEXT, BOOLEAN, MATH)
          .flatMap(Set::stream)
          .collect(Collectors.toSet());
  private static final Set<String> CONNECTORS = Set.of("and", "or", "xor", "|");
  private final String field;
  private final Set<String> expected;
  private final String literal;
  private final boolean numeric;

  @Parameterized.Parameters(name = "{0}")
  public static List<Object[]> types() {
    return List.of(
        new Object[] {"text_value", TEXT, "'alpha'", false},
        new Object[] {"number_value", NUMBER, "10", true},
        new Object[] {"decimal_value", NUMBER, "10.5", true},
        new Object[] {"boolean_value", BOOLEAN, "true", false},
        new Object[] {"time_value", NUMBER, "cast('2025-01-01 00:00:00' as timestamp)", false},
        new Object[] {"date_value", NUMBER, "cast('2025-01-01' as date)", false},
        new Object[] {"clock_value", NUMBER, "cast('00:00:00' as time)", false},
        new Object[] {"object_value", Set.of("is"), null, false},
        new Object[] {"map_value", Set.of("is"), null, false},
        new Object[] {"array_value", Set.of("is"), null, false},
        new Object[] {"unknown_value", Set.of("is"), null, false});
  }

  public PplOperatorJourneyTest(
      String field, Set<String> expected, String literal, boolean numeric) {
    this.field = field;
    this.expected = expected;
    this.literal = literal;
    this.numeric = numeric;
  }

  private final QueryBackend backend =
      new QueryBackend() {
        public String name() {
          return "typed";
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
            protected Map<String, org.apache.calcite.schema.Table> getTableMap() {
              return Map.of("typed_values", new TypedValues());
            }
          };
        }
      };
  private final QueryMetadataService metadata =
      new QueryMetadataService(List.of(backend), "typed", Map.of());
  private final QueryCompletionService completions =
      new QueryCompletionService(metadata, List.of(new PplCompletionProvider()));
  private final UnifiedQueryService execution = new UnifiedQueryService(List.of(backend), "typed");

  private static class TypedValues extends AbstractTable implements ScannableTable {
    @Override
    public RelDataType getRowType(RelDataTypeFactory factory) {
      var string = factory.createSqlType(SqlTypeName.VARCHAR);
      return factory
          .builder()
          .add("id", SqlTypeName.INTEGER)
          .add("text_value", string)
          .nullable(true)
          .add("number_value", SqlTypeName.BIGINT)
          .nullable(true)
          .add("decimal_value", SqlTypeName.DECIMAL, 10, 2)
          .nullable(true)
          .add("boolean_value", SqlTypeName.BOOLEAN)
          .nullable(true)
          .add("time_value", SqlTypeName.TIMESTAMP)
          .nullable(true)
          .add("date_value", SqlTypeName.DATE)
          .nullable(true)
          .add("clock_value", SqlTypeName.TIME)
          .nullable(true)
          .add("object_value", factory.builder().add("name", string).build())
          .nullable(true)
          .add("map_value", factory.createMapType(string, string))
          .nullable(true)
          .add("array_value", factory.createArrayType(string, -1))
          .nullable(true)
          .add("unknown_value", SqlTypeName.ANY)
          .nullable(true)
          .build();
    }

    @Override
    public Enumerable<Object[]> scan(DataContext context) {
      return Linq4j.asEnumerable(
          List.of(
              new Object[] {
                1,
                "alpha",
                10L,
                new BigDecimal("10.50"),
                true,
                1735689600000L,
                20089,
                0,
                new Object[] {"child"},
                Map.of("name", "child"),
                List.of("child"),
                "opaque"
              },
              new Object[] {2, null, null, null, null, null, null, null, null, null, null, null}));
    }
  }

  @Test
  public void predicateSlotsUseOneTypePolicyAtEveryNestingLevel() {
    for (String template :
        List.of(
            "where %s ",
            "where not %s ",
            "where (%s ",
            "where id=1 and %s ",
            "where (id=1 or id=2) and %s ",
            "eval result=case(%s ",
            "eval result=if(%s ",
            "stats count(eval(%s ",
            "streamstats reset_before=%s ",
            "streamstats reset_after=%s ")) {
      String input = SOURCE + template.formatted("`" + field + "`");
      var response = complete(input, input.length());
      assertEquals(input, expected, typeOperators(response));
      if (!field.equals("boolean_value")) {
        assertTrue(
            input,
            response.items().stream()
                .allMatch(item -> expected.contains(item.label()) || item.kind().equals("field")));
      }
    }
    String input = SOURCE + "join left=l right=r on `l`.`" + field + "` ";
    String full = input + "typed_values";
    assertEquals(full, expected, typeOperators(complete(full, input.length())));
  }

  @Test
  public void valueSlotsKeepArithmeticOnlyForNumericValues() {
    Set<String> wanted = new java.util.HashSet<>(expected);
    if (numeric) wanted.addAll(MATH);
    for (String template :
        List.of(
            "eval copied=%s ", "eval copied=(%s ", "eval copied=coalesce(%s ", "stats first(%s ")) {
      String input = SOURCE + template.formatted("`" + field + "`");
      assertEquals(input, wanted, typeOperators(complete(input, input.length())));
    }
  }

  @Test
  public void everyAdvertisedPredicateOperatorCompletesAndExecutes() {
    for (String operator : expected) {
      Editor editor = new Editor();
      editor
          .accept("source")
          .only("=")
          .accept("=")
          .accept("typed_values")
          .only("|")
          .accept("|")
          .accept("where")
          .accept("id")
          .accept("=")
          .type("1")
          .accept("|")
          .accept("where")
          .accept(field);
      assertEquals(editor.text, expected, typeOperators(editor.response()));
      editor.accept(operator);
      switch (operator) {
        case "is" -> editor.only("not", "null").accept("not").only("null").accept("null");
        case "in", "not" -> {
          if (operator.equals("not")) editor.accept("in");
          editor.accept("(");
          editor
              .inputValue(
                  switch (field) {
                    case "time_value" -> "timestamp '2025-01-01 00:00:00'";
                    case "date_value" -> "date '2025-01-01'";
                    case "clock_value" -> "time '00:00:00'";
                    default -> literal;
                  })
              .accept(")");
        }
        case "between" -> {
          editor.only().type(literal).only("and").accept("and").only().type(literal);
        }
        case "like" -> editor.only().type("'al%'");
        case "ilike" -> editor.only().type("'AL%'");
        case "regexp" -> editor.only().type("'^alpha$'");
        case "contains" -> editor.only().type("'alpha'");
        default -> editor.inputValue(literal);
      }
      editor.only("and", "or", "xor", "|").accept("|").accept("fields").accept("id");
      var rows = execution.execute(QueryType.PPL, editor.text).getResults();
      int expectedRows = Set.of("!=", ">", "<", "not").contains(operator) ? 0 : 1;
      assertEquals(editor.text, expectedRows, rows.size());
      if (!rows.isEmpty())
        assertEquals(
            editor.text, 1, rows.getFirst().tupleValue().get("id").integerValue().intValue());
      editor.accept("|").accept("head").accept("10");
      assertEquals(
          editor.text,
          expectedRows,
          execution.execute(QueryType.PPL, editor.text).getResults().size());
    }
  }

  @Test
  public void nestedPredicateJourneysExecuteAndContinue() {
    for (String prefix :
        List.of(
            "where (",
            "where not (",
            "eval result=case(",
            "eval result=if(",
            "stats count(eval(",
            "streamstats reset_before=",
            "streamstats reset_after=")) {
      Editor editor = new Editor();
      editor.type(SOURCE + prefix).accept(field).accept("is").accept("not").accept("null");
      assertTrue(editor.text, typeOperators(editor.response()).isEmpty());
      if (prefix.startsWith("where")) editor.accept(")");
      else if (prefix.startsWith("eval"))
        editor
            .accept(",")
            .type("1 ")
            .accept(prefix.contains("if(") ? "," : "else")
            .type("0")
            .accept(")");
      else if (prefix.startsWith("stats"))
        editor.accept(")").accept(")").accept("as").type("total ");
      else editor.accept("count").accept(")").accept("as").type("total ");
      editor.accept("|").accept("fields").accept(prefix.startsWith("stats") ? "total" : "id");
      int expectedRows = prefix.startsWith("eval") || prefix.startsWith("streamstats") ? 2 : 1;
      var rows = execution.execute(QueryType.PPL, editor.text).getResults();
      assertEquals(editor.text, expectedRows, rows.size());
      if (prefix.startsWith("stats"))
        assertEquals(
            editor.text, 1L, rows.getFirst().tupleValue().get("total").longValue().longValue());
      editor.accept("|").accept("head").accept("10");
      assertEquals(
          editor.text,
          expectedRows,
          execution.execute(QueryType.PPL, editor.text).getResults().size());
    }
  }

  @Test
  public void nullChecksExecuteForEveryTypeIncludingUnknown() {
    Editor editor = new Editor();
    editor
        .type(SOURCE + "where ")
        .accept(field)
        .accept("is")
        .accept("null")
        .only("and", "or", "xor", "|")
        .accept("|")
        .accept("fields")
        .accept("id");
    var rows = execution.execute(QueryType.PPL, editor.text).getResults();
    assertEquals(editor.text, 1, rows.size());
    assertEquals(editor.text, 2, rows.getFirst().tupleValue().get("id").integerValue().intValue());
  }

  @Test
  public void aggregatesPropagateTheirActualResultFamily() {
    for (String command : List.of("stats", "eventstats", "streamstats")) {
      String input = SOURCE + command + " first(`" + field + "`) as copied | where copied ";
      assertEquals(input, expected, typeOperators(complete(input, input.length())));
    }
  }

  @Test
  public void aliasesAndProjectionsPreserveTypesForTheNextPredicate() {
    String input = SOURCE + "eval copied=coalesce(`" + field + "`,`" + field + "`) | where copied ";
    assertEquals(input, expected, typeOperators(complete(input, input.length())));
    input = SOURCE + "eval copied=`" + field + "` | fields copied | where copied ";
    assertEquals(input, expected, typeOperators(complete(input, input.length())));
    input = SOURCE + "rename `" + field + "` as copied | where copied ";
    assertEquals(input, expected, typeOperators(complete(input, input.length())));
  }

  private CompletionResponse complete(String text, int caret) {
    return completions.complete(new CompletionRequest("ppl", "typed", text, caret, 10, "input"));
  }

  private static Set<String> typeOperators(CompletionResponse response) {
    return response.items().stream()
        .map(CompletionResponse.Item::label)
        .filter(TYPE_OPERATORS::contains)
        .collect(Collectors.toSet());
  }

  private class Editor {
    String text = "";
    int caret;

    CompletionResponse response() {
      return complete(text, caret);
    }

    Editor type(String input) {
      text = text.substring(0, caret) + input + text.substring(caret);
      caret += input.length();
      return this;
    }

    Editor inputValue(String input) {
      if (field.equals("boolean_value")) {
        only("true", "false");
        return accept(input);
      }
      only();
      return type(input);
    }

    Editor only(String... labels) {
      assertEquals(
          text,
          Set.of(labels),
          response().items().stream()
              .map(CompletionResponse.Item::label)
              .collect(Collectors.toSet()));
      return this;
    }

    Editor accept(String label) {
      var item =
          response().items().stream()
              .filter(candidate -> candidate.label().equals(label))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "Missing " + label + " after " + text + ": " + response()));
      var edit = item.textEdit();
      text = text.substring(0, edit.from()) + edit.newText() + text.substring(edit.to());
      caret =
          edit.from() + (item.caretOffset() == null ? edit.newText().length() : item.caretOffset());
      return this;
    }
  }
}
