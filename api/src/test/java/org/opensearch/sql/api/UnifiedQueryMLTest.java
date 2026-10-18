/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.opensearch.sql.backend.MachineLearningProvider;
import org.opensearch.sql.backend.MachineLearningSupport;
import org.opensearch.sql.backend.QueryBackend;
import org.opensearch.sql.executor.CalciteExecutionEngine;
import org.opensearch.sql.executor.ExecutionEngine;
import org.opensearch.sql.executor.QueryType;

/**
 * ML is evaluated at its position in the pipeline, with a provider owned by the selected backend.
 */
public class UnifiedQueryMLTest {
  private final AtomicInteger executions = new AtomicInteger();
  private final List<Integer> inputValues = new ArrayList<>();

  @Test
  public void upstreamExpressionsAndDownstreamPredicatesUseNativePrediction() {
    var result =
        service(true)
            .execute(
                QueryType.PPL,
                "source=events | where id > 1 | eval feature=id * 2 | fields feature | ml"
                    + " action='predict' algorithm='regression' | where prediction>5 | fields"
                    + " feature,prediction | sort feature");
    assertEquals(List.of(4, 6), inputValues);
    assertEquals(1, executions.get());
    assertEquals(1, result.getResults().size());
    assertEquals(
        6, result.getResults().getFirst().tupleValue().get("feature").integerValue().intValue());
    assertEquals(
        6.5, result.getResults().getFirst().tupleValue().get("prediction").doubleValue(), 0.0);
  }

  @Test
  public void windowInputsAndAggregateOutputsCompileAgainstTheEnumerableInterface() {
    var result =
        service(true)
            .execute(
                QueryType.PPL,
                "source=events | streamstats count() as ordinal | fields ordinal | ml"
                    + " action='predict' algorithm='regression' | stats max(prediction) as"
                    + " maximum");
    assertEquals(List.of(1, 2, 3), inputValues);
    assertEquals(
        3.5, result.getResults().getFirst().tupleValue().get("maximum").doubleValue(), 0.0);
  }

  @Test
  public void downstreamLimitDoesNotReduceTrainingInput() {
    var result =
        service(true)
            .execute(
                QueryType.PPL,
                "source=events | ml action='predict' algorithm='regression' | head 1");
    assertEquals(List.of(1, 2, 3), inputValues);
    assertEquals(1, result.getResults().size());
  }

  @Test
  public void structuredFiltersApplyBeforeMlAndResultWindowsApplyAfterIt() {
    var request =
        new QueryExecutionRequest(
            "memory",
            QueryType.PPL,
            "source=events | ml action='predict' algorithm='regression'",
            null,
            List.of(new QueryExecutionRequest.FieldFilter("id", "gt", 1)),
            new QueryExecutionRequest.ResultWindow(0, 1),
            null);
    var response = service(true).execute(request);
    assertEquals(List.of(2, 3), inputValues);
    assertEquals(1, response.getResults().size());
    assertEquals(
        2.5, response.getResults().getFirst().tupleValue().get("prediction").doubleValue(), 0.0);
  }

  @Test
  public void explainDoesNotExecuteInference() {
    var result =
        service(true)
            .explain(
                "memory",
                QueryType.PPL,
                "source=events | ml action='predict' algorithm='regression'");
    assertNotNull(result);
    assertEquals(0, executions.get());
    assertTrue(inputValues.isEmpty());
  }

  @Test
  public void providersAreQueryScopedAndCanBeChained() {
    var service = service(true);
    var result =
        service.execute(
            QueryType.PPL,
            "source=events | ml action='predict' algorithm='regression' | fields prediction | ml"
                + " action='predict' algorithm='regression' prediction_field='second' | fields"
                + " second");
    assertEquals(2, executions.get());
    assertEquals(3, result.getResults().size());
    assertEquals(2.0, result.getResults().getFirst().tupleValue().get("second").doubleValue(), 0.0);
    service.execute(QueryType.PPL, "source=events | head 1");
    assertEquals(2, executions.get());
  }

  @Test
  public void differentModelArgumentsAreNotMergedByTheOptimizer() {
    var result =
        service(true)
            .execute(
                QueryType.PPL,
                "source=events | ml action='predict' algorithm='regression' offset=10 | append"
                    + " [source=events | ml action='predict' algorithm='regression' offset=20]"
                    + " | fields prediction | sort prediction");
    assertEquals(6, result.getResults().size());
    assertEquals(
        11.0, result.getResults().getFirst().tupleValue().get("prediction").doubleValue(), 0.0);
    assertEquals(
        23.0, result.getResults().getLast().tupleValue().get("prediction").doubleValue(), 0.0);
  }

  @Test
  public void missingCapabilityAndOutputCollisionFailDuringPlanning() {
    assertThrows(
        RuntimeException.class,
        () ->
            service(false)
                .execute(
                    QueryType.PPL, "source=events | ml action='predict' algorithm='regression'"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service(true)
                .execute(
                    QueryType.PPL,
                    "source=events | ml action='predict' algorithm='regression'"
                        + " prediction_field='id'"));
    assertEquals(0, executions.get());
  }

  private UnifiedQueryService service(boolean enabled) {
    var provider =
        (MachineLearningProvider)
            (arguments, inputType) -> {
              String field = (String) arguments.getOrDefault("prediction_field", "prediction");
              var output =
                  MachineLearningSupport.appendPredictionType(inputType, field, SqlTypeName.DOUBLE);
              return new MachineLearningProvider.Operation() {
                public RelDataType outputType() {
                  return output;
                }

                public Enumerable<Object[]> execute(
                    Enumerable<Object[]> input, DataContext context) {
                  executions.incrementAndGet();
                  List<Object[]> result = new ArrayList<>();
                  try (var rows = input.enumerator()) {
                    while (rows.moveNext()) {
                      MachineLearningSupport.checkCancelled(context);
                      Object[] row = rows.current();
                      Number value = (Number) row[0];
                      inputValues.add(value.intValue());
                      result.add(
                          MachineLearningSupport.appendPrediction(
                              row,
                              value.doubleValue()
                                  + ((Number) arguments.getOrDefault("offset", 0.5))
                                      .doubleValue()));
                    }
                  }
                  return Linq4j.asEnumerable(result);
                }
              };
            };
    QueryBackend backend =
        new QueryBackend() {
          public String name() {
            return "memory";
          }

          public String type() {
            return "test";
          }

          public Schema createSchema(SchemaPlus parent) {
            return new AbstractSchema() {
              protected Map<String, Table> getTableMap() {
                return Map.of("events", new Rows());
              }
            };
          }

          public MachineLearningProvider machineLearning() {
            return enabled ? provider : null;
          }

          public ExecutionEngine executionEngine() {
            return new CalciteExecutionEngine();
          }
        };
    return new UnifiedQueryService(List.of(backend), "memory");
  }

  private static final class Rows extends AbstractTable implements ScannableTable {
    public RelDataType getRowType(RelDataTypeFactory factory) {
      return factory.builder().add("id", SqlTypeName.INTEGER).build();
    }

    public Enumerable<Object[]> scan(DataContext root) {
      return Linq4j.asEnumerable(List.of(new Object[] {1}, new Object[] {2}, new Object[] {3}));
    }
  }
}
