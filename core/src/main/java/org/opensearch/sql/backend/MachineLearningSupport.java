/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.backend;

import static org.opensearch.sql.calcite.utils.OpenSearchTypeFactory.TYPE_FACTORY;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.calcite.DataContext;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;

/** Validation and row conventions shared by the native ML adapters. */
public final class MachineLearningSupport {
  private MachineLearningSupport() {}

  public static void checkCancelled(DataContext context) {
    AtomicBoolean flag = DataContext.Variable.CANCEL_FLAG.get(context);
    if (Thread.currentThread().isInterrupted() || (flag != null && flag.get())) {
      throw new CancellationException("ML query cancelled");
    }
  }

  public static String stringArgument(Map<String, Object> arguments, String name) {
    if (!(arguments.get(name) instanceof String value) || value.isBlank()) {
      throw new IllegalArgumentException("ML requires a non-empty string argument: " + name);
    }
    return value;
  }

  public static int intArgument(
      Map<String, Object> arguments, String name, int defaultValue, int minimum, int maximum) {
    Object value = arguments.getOrDefault(name, defaultValue);
    if (!(value instanceof Number number)
        || !Double.isFinite(number.doubleValue())
        || number.doubleValue() != Math.rint(number.doubleValue())
        || number.doubleValue() < minimum
        || number.doubleValue() > maximum) {
      throw new IllegalArgumentException(
          "ML " + name + " must be an integer between " + minimum + " and " + maximum);
    }
    return ((Number) value).intValue();
  }

  public static void rejectUnknownArguments(Map<String, Object> arguments, Set<String> allowed) {
    for (String name : arguments.keySet()) {
      if (!allowed.contains(name)) {
        throw new IllegalArgumentException("Unsupported ML argument: " + name);
      }
    }
  }

  public static RelDataType appendPredictionType(
      RelDataType inputType, String field, SqlTypeName predictionType) {
    if (field == null || field.isBlank()) {
      throw new IllegalArgumentException("ML prediction_field must be non-empty");
    }
    if (inputType.getFieldNames().stream().anyMatch(field::equalsIgnoreCase)) {
      throw new IllegalArgumentException("ML output field already exists: " + field);
    }
    return TYPE_FACTORY
        .builder()
        .addAll(inputType.getFieldList())
        .add(field, predictionType)
        .build();
  }

  public static Object[] appendPrediction(Object[] row, Object prediction) {
    Object[] result = Arrays.copyOf(row, row.length + 1);
    result[row.length] = prediction;
    return result;
  }
}
