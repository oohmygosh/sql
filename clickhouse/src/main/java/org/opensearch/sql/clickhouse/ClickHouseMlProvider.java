/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.clickhouse;

import static org.opensearch.sql.backend.MachineLearningSupport.appendPrediction;
import static org.opensearch.sql.backend.MachineLearningSupport.appendPredictionType;
import static org.opensearch.sql.backend.MachineLearningSupport.checkCancelled;
import static org.opensearch.sql.backend.MachineLearningSupport.intArgument;
import static org.opensearch.sql.backend.MachineLearningSupport.rejectUnknownArguments;
import static org.opensearch.sql.backend.MachineLearningSupport.stringArgument;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.opensearch.sql.backend.MachineLearningProvider;

/** Query-local native regression states; no tables or persisted models are created. */
final class ClickHouseMlProvider implements MachineLearningProvider {
  private static final Set<String> ARGUMENTS =
      Set.of(
          "action",
          "algorithm",
          "target",
          "features",
          "prediction_field",
          "max_rows",
          "learning_rate",
          "l2",
          "batch_size",
          "optimizer");
  private static final Set<String> OPTIMIZERS = Set.of("Adam", "SGD", "Momentum", "Nesterov");
  private static final int MAX_INPUT_CELLS = 100_000;
  private static final int MAX_SQL_LENGTH = 128 * 1024;
  private final DataSource dataSource;

  ClickHouseMlProvider(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public Set<String> argumentNames() {
    return ARGUMENTS;
  }

  @Override
  public Operation plan(Map<String, Object> arguments, RelDataType inputType) {
    rejectUnknownArguments(arguments, ARGUMENTS);
    if (!"trainandpredict".equals(stringArgument(arguments, "action"))) {
      throw new IllegalArgumentException("ClickHouse ML supports only action='trainandpredict'");
    }
    String algorithm = stringArgument(arguments, "algorithm");
    boolean logistic;
    switch (algorithm) {
      case "linear_regression" -> logistic = false;
      case "logistic_regression" -> logistic = true;
      default ->
          throw new IllegalArgumentException(
              "ClickHouse ML supports only linear_regression and logistic_regression");
    }
    String target = stringArgument(arguments, "target");
    int targetIndex = numericField(inputType, target);
    List<String> features = new ArrayList<>();
    List<Integer> featureIndexes = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String item : stringArgument(arguments, "features").split(",", -1)) {
      String feature = item.trim();
      if (feature.isEmpty()) {
        throw new IllegalArgumentException(
            "ClickHouse ML features must contain nonempty field names");
      }
      if (!seen.add(feature)) {
        throw new IllegalArgumentException("Duplicate ClickHouse ML feature: " + feature);
      }
      if (feature.equals(target)) {
        throw new IllegalArgumentException(
            "ClickHouse ML target cannot also be a feature: " + target);
      }
      features.add(feature);
      featureIndexes.add(numericField(inputType, feature));
    }
    String predictionField =
        arguments.containsKey("prediction_field")
            ? stringArgument(arguments, "prediction_field")
            : "prediction";
    RelDataType outputType = appendPredictionType(inputType, predictionField, SqlTypeName.DOUBLE);
    int maxRows = intArgument(arguments, "max_rows", 1000, 1, 10_000);
    double learningRate = doubleArgument(arguments, "learning_rate", 0.00001);
    if (learningRate <= 0) {
      throw new IllegalArgumentException("ClickHouse ML learning_rate must be positive");
    }
    double l2 = doubleArgument(arguments, "l2", 0.1);
    if (l2 < 0) {
      throw new IllegalArgumentException("ClickHouse ML l2 must be nonnegative");
    }
    // Native regression leaves incomplete mini-batch gradients unapplied, even at prediction.
    int batchSize = intArgument(arguments, "batch_size", 1, 1, 10_000);
    String optimizer =
        arguments.containsKey("optimizer") ? stringArgument(arguments, "optimizer") : "Adam";
    if (!OPTIMIZERS.contains(optimizer)) {
      throw new IllegalArgumentException(
          "ClickHouse ML optimizer must be Adam, SGD, Momentum or Nesterov");
    }
    return new Regression(
        dataSource,
        outputType,
        inputType.getFieldCount(),
        logistic,
        target,
        targetIndex,
        List.copyOf(features),
        List.copyOf(featureIndexes),
        maxRows,
        learningRate,
        l2,
        batchSize,
        optimizer);
  }

  private static int numericField(RelDataType inputType, String name) {
    var field = inputType.getField(name, true, false);
    if (field == null) {
      throw new IllegalArgumentException("Unknown ClickHouse ML field: " + name);
    }
    if (!SqlTypeUtil.isNumeric(field.getType())) {
      throw new IllegalArgumentException("ClickHouse ML field must be numeric: " + name);
    }
    return field.getIndex();
  }

  private static double doubleArgument(
      Map<String, Object> arguments, String name, double fallback) {
    if (!arguments.containsKey(name)) {
      return fallback;
    }
    Object value = arguments.get(name);
    if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
      throw new IllegalArgumentException("ClickHouse ML " + name + " must be a finite number");
    }
    return number.doubleValue();
  }

  private record Regression(
      DataSource dataSource,
      RelDataType outputType,
      int inputColumns,
      boolean logistic,
      String target,
      int targetIndex,
      List<String> features,
      List<Integer> featureIndexes,
      int maxRows,
      double learningRate,
      double l2,
      int batchSize,
      String optimizer)
      implements Operation {
    @Override
    public Enumerable<Object[]> execute(Enumerable<Object[]> input, DataContext context) {
      return new AbstractEnumerable<>() {
        @Override
        public Enumerator<Object[]> enumerator() {
          return new Enumerator<>() {
            private List<Object[]> rows;
            private int position = -1;
            private boolean closed;

            @Override
            public Object[] current() {
              if (closed || rows == null || position < 0 || position >= rows.size()) {
                throw new NoSuchElementException();
              }
              return rows.get(position);
            }

            @Override
            public boolean moveNext() {
              if (closed) {
                return false;
              }
              try {
                checkCancelled(context);
                if (rows == null) {
                  rows = trainAndPredict(input, context);
                }
                checkCancelled(context);
                if (++position < rows.size()) {
                  return true;
                }
                close();
                return false;
              } catch (RuntimeException | Error e) {
                close();
                throw e;
              }
            }

            @Override
            public void reset() {
              throw new UnsupportedOperationException("ClickHouse ML enumerators cannot be reset");
            }

            @Override
            public void close() {
              closed = true;
              rows = null;
            }
          };
        }
      };
    }

    private List<Object[]> trainAndPredict(Enumerable<Object[]> input, DataContext context) {
      List<Object[]> rows = new ArrayList<>();
      List<double[]> numericRows = new ArrayList<>();
      long substitutedLength =
          Double.toString(learningRate).length()
              + Double.toString(l2).length()
              + Integer.toString(batchSize).length()
              + optimizer.length()
              + 2
              - 4;
      try (Enumerator<Object[]> source = input.enumerator()) {
        while (source.moveNext()) {
          checkCancelled(context);
          if (rows.size() == maxRows) {
            throw new IllegalArgumentException(
                "ClickHouse ML input exceeds max_rows=" + maxRows + "; limit the upstream query");
          }
          if ((long) (rows.size() + 1) * inputColumns > MAX_INPUT_CELLS) {
            throw new IllegalArgumentException(
                "ClickHouse ML input exceeds " + MAX_INPUT_CELLS + " cells; select fewer columns");
          }
          Object[] row = source.current();
          double[] numeric = new double[featureIndexes.size() + 1];
          numeric[0] = finiteValue(row[targetIndex], target);
          if (logistic) {
            if ((numeric[0] != 0 && numeric[0] != 1)
                || (row[targetIndex] instanceof BigDecimal decimal
                    && decimal.compareTo(BigDecimal.ZERO) != 0
                    && decimal.compareTo(BigDecimal.ONE) != 0)) {
              throw new IllegalArgumentException(
                  "ClickHouse logistic_regression target must be 0 or 1");
            }
            // ClickHouse trains with signed labels, but evalMLMethod returns P(label = 1).
            numeric[0] = numeric[0] == 0 ? -1 : 1;
          }
          for (int i = 0; i < featureIndexes.size(); i++) {
            numeric[i + 1] = finiteValue(row[featureIndexes.get(i)], features.get(i));
          }
          substitutedLength += Integer.toString(rows.size()).length() - 1;
          for (double value : numeric) {
            substitutedLength += Double.toString(value).length() - 1;
          }
          // JDBC 0.9.x expands bound values into SQL; bound both numeric payload and query text.
          if (substitutedLength > MAX_SQL_LENGTH) {
            throw queryTooLarge();
          }
          rows.add(row.clone());
          numericRows.add(numeric);
        }
      }
      checkCancelled(context);
      if (rows.isEmpty()) {
        return rows;
      }
      if (rows.size() % batchSize != 0) {
        throw new IllegalArgumentException(
            "ClickHouse ML batch_size must divide the input row count ("
                + rows.size()
                + "); use batch_size=1 to train on every row");
      }
      String sql = sql(rows.size());
      if (sql.length() + substitutedLength > MAX_SQL_LENGTH) {
        throw queryTooLarge();
      }
      try (var connection = dataSource.getConnection();
          PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(30);
        int parameter = 1;
        for (int i = 0; i < numericRows.size(); i++) {
          checkCancelled(context);
          statement.setInt(parameter++, i);
          for (double value : numericRows.get(i)) {
            statement.setDouble(parameter++, value);
          }
        }
        statement.setDouble(parameter++, learningRate);
        statement.setDouble(parameter++, l2);
        statement.setInt(parameter++, batchSize);
        statement.setString(parameter, optimizer);
        checkCancelled(context);
        try (var result = statement.executeQuery()) {
          int index = 0;
          while (result.next()) {
            checkCancelled(context);
            int ordinal = result.getInt(1);
            if (result.wasNull() || ordinal != index || index >= rows.size()) {
              throw new IllegalStateException(
                  "ClickHouse ML returned unexpected prediction row order");
            }
            double prediction = result.getDouble(2);
            if (result.wasNull()
                || !Double.isFinite(prediction)
                || (logistic && (prediction < 0 || prediction > 1))) {
              throw new IllegalStateException("ClickHouse ML returned an invalid prediction");
            }
            rows.set(index, appendPrediction(rows.get(index), prediction));
            index++;
          }
          if (index != rows.size()) {
            throw new IllegalStateException(
                "ClickHouse ML returned an unexpected prediction count");
          }
        }
      } catch (SQLException e) {
        checkCancelled(context);
        throw new IllegalStateException("ClickHouse native ML query failed: " + e.getMessage(), e);
      }
      return rows;
    }

    private String sql(int rows) {
      StringBuilder sql =
          new StringBuilder(
              "WITH _ml_input AS (SELECT * FROM values('_ml_row UInt32, _ml_target Float64");
      for (int i = 0; i < featureIndexes.size(); i++) {
        sql.append(", _ml_f").append(i).append(" Float64");
      }
      sql.append("'");
      String row = ", (?" + ", ?".repeat(featureIndexes.size() + 1) + ")";
      sql.append(row.repeat(rows));
      sql.append(")), (SELECT ")
          .append(
              logistic ? "stochasticLogisticRegressionState" : "stochasticLinearRegressionState")
          .append("(?, ?, ?, ?)(_ml_target");
      appendFeatures(sql);
      sql.append(") FROM _ml_input) AS _ml_model SELECT _ml_row, evalMLMethod(_ml_model");
      appendFeatures(sql);
      return sql.append(
              ") AS _ml_prediction FROM _ml_input ORDER BY _ml_row SETTINGS max_threads=1")
          .toString();
    }

    private void appendFeatures(StringBuilder sql) {
      for (int i = 0; i < featureIndexes.size(); i++) {
        sql.append(", _ml_f").append(i);
      }
    }
  }

  private static double finiteValue(Object value, String field) {
    if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
      throw new IllegalArgumentException(
          "ClickHouse ML field must contain finite, non-null numeric values: " + field);
    }
    return number.doubleValue();
  }

  private static IllegalArgumentException queryTooLarge() {
    return new IllegalArgumentException(
        "ClickHouse ML input exceeds the 128 KiB SQL limit; limit the upstream rows or features");
  }
}
