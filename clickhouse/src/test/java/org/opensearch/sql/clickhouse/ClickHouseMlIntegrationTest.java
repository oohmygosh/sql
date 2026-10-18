/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.clickhouse;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.calcite.DataContexts;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.executor.QueryType;

/** Native fit-and-predict uses only query-local VALUES; these tests create no database objects. */
@EnabledIfEnvironmentVariable(named = "CLICKHOUSE_TEST_URL", matches = ".+")
class ClickHouseMlIntegrationTest {
  private static final RelDataType INPUT_TYPE =
      new JavaTypeFactoryImpl()
          .builder()
          .add("id", SqlTypeName.INTEGER)
          .add("x", SqlTypeName.DOUBLE)
          .add("label", SqlTypeName.DOUBLE)
          .build();

  @Test
  void nativeLinearRegressionFitsAndPredictsWithoutPersistingModel() throws Exception {
    List<Object[]> input = new ArrayList<>();
    for (int i = 0; i < 999; i++) {
      double x = i % 3 - 1;
      input.add(new Object[] {i, x, 2 * x + 1});
    }
    List<Object[]> result = run("linear_regression", input);
    assertEquals(input.size(), result.size());
    for (int i = 0; i < result.size(); i++) {
      assertEquals(i, result.get(i)[0]);
      assertEquals((double) input.get(i)[2], (double) result.get(i)[3], 0.1);
    }
  }

  @Test
  void nativeLogisticRegressionPredictsProbabilityOfBinaryLabelOne() throws Exception {
    List<Object[]> input = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      double label = i % 2;
      input.add(new Object[] {i, label == 0 ? -1.0 : 1.0, label});
    }
    List<Object[]> result = run("logistic_regression", input);
    assertEquals(input.size(), result.size());
    for (int i = 0; i < result.size(); i++) {
      assertEquals(i, result.get(i)[0]);
      assertEquals(input.get(i)[2], result.get(i)[2]);
      double probability = (double) result.get(i)[3];
      assertTrue(Double.isFinite(probability) && probability >= 0 && probability <= 1);
      assertTrue(i % 2 == 0 ? probability < 0.3 : probability > 0.7, "Probability: " + probability);
    }
  }

  @Test
  void pplPipelineTrainsFiltersAndAggregatesNativeLinearPredictions() throws Exception {
    try (var backend = backend()) {
      var result =
          new UnifiedQueryService(List.of(backend), "ch")
              .execute(
                  QueryType.PPL,
                  "makeresults count=999 | streamstats count() as id | eval x=mod(id, 3) - 1"
                      + " | eval target=2 * x + 1 | fields id,x,target"
                      + " | ml action='trainandpredict' algorithm='linear_regression'"
                      + " target='target' features='x' learning_rate=0.05 l2=0.0"
                      + " batch_size=1 optimizer='SGD' | where x=1"
                      + " | eval error=abs(prediction - target)"
                      + " | stats count() as n, max(error) as max_error");
      assertEquals(1, result.getResults().size());
      var row = result.getResults().getFirst().tupleValue();
      assertEquals(333L, row.get("n").longValue());
      assertTrue(row.get("max_error").doubleValue() < 0.1);
    }
  }

  @Test
  void pplPipelineTrainsAndAggregatesNativeClassificationProbabilities() throws Exception {
    try (var backend = backend()) {
      var result =
          new UnifiedQueryService(List.of(backend), "ch")
              .execute(
                  QueryType.PPL,
                  "makeresults count=1000 | streamstats count() as id | eval target=mod(id, 2)"
                      + " | eval x=2 * target - 1 | fields id,x,target"
                      + " | ml action='trainandpredict' algorithm='logistic_regression'"
                      + " target='target' features='x' learning_rate=0.05 l2=0.0"
                      + " batch_size=1 optimizer='SGD'"
                      + " | eval correct_probability=if(target=1, prediction, 1 - prediction)"
                      + " | stats count() as n, min(correct_probability) as confidence");
      assertEquals(1, result.getResults().size());
      var row = result.getResults().getFirst().tupleValue();
      assertEquals(1000L, row.get("n").longValue());
      assertTrue(row.get("confidence").doubleValue() > 0.7);
    }
  }

  @Test
  void defaultMiniBatchTrainsSingleRowForBothAlgorithms() throws Exception {
    try (var backend = backend()) {
      var service = new UnifiedQueryService(List.of(backend), "ch");
      for (String algorithm : List.of("linear_regression", "logistic_regression")) {
        var response =
            service.execute(
                QueryType.PPL,
                "makeresults | eval x=1.0, target=1.0 | fields x,target"
                    + " | ml action='trainandpredict' algorithm='"
                    + algorithm
                    + "' target='target' features='x'");
        double prediction =
            response.getResults().getFirst().tupleValue().get("prediction").doubleValue();
        assertTrue(
            prediction > (algorithm.equals("linear_regression") ? 0.0 : 0.5),
            "The only training row must update the model: " + prediction);
      }
    }
  }

  @Test
  void defaultMiniBatchIncludesTheLastTrainingRow() throws Exception {
    try (var backend = backend()) {
      var response =
          new UnifiedQueryService(List.of(backend), "ch")
              .execute(
                  QueryType.PPL,
                  "makeresults count=16 | streamstats count() as id | eval x=1.0, target=if(id=16,"
                      + " 10.0, 0.0) | fields x,target | ml action='trainandpredict'"
                      + " algorithm='linear_regression' target='target' features='x'"
                      + " learning_rate=0.1 l2=0.0 optimizer='SGD' | head 1");
      assertEquals(
          4.0,
          response.getResults().getFirst().tupleValue().get("prediction").doubleValue(),
          0.000001);
    }
  }

  private static ClickHouseQueryBackend backend() throws Exception {
    return new ClickHouseQueryBackend(
        "ch",
        System.getenv("CLICKHOUSE_TEST_URL"),
        ClickHouseIntegrationTest.credentials(),
        "default");
  }

  private static List<Object[]> run(String algorithm, List<Object[]> rows) throws Exception {
    try (var backend = backend()) {
      var operation =
          backend
              .machineLearning()
              .plan(
                  Map.of(
                      "action",
                      "trainandpredict",
                      "algorithm",
                      algorithm,
                      "features",
                      "x",
                      "target",
                      "label",
                      "learning_rate",
                      0.05,
                      "l2",
                      0.0,
                      "batch_size",
                      1,
                      "optimizer",
                      "SGD"),
                  INPUT_TYPE);
      return operation.execute(Linq4j.asEnumerable(rows), DataContexts.EMPTY).toList();
    }
  }
}
