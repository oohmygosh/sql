/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.opensearch.sql.backend.MachineLearningSupport.appendPrediction;
import static org.opensearch.sql.backend.MachineLearningSupport.appendPredictionType;
import static org.opensearch.sql.backend.MachineLearningSupport.checkCancelled;
import static org.opensearch.sql.backend.MachineLearningSupport.intArgument;
import static org.opensearch.sql.backend.MachineLearningSupport.rejectUnknownArguments;
import static org.opensearch.sql.backend.MachineLearningSupport.stringArgument;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.sql.backend.MachineLearningProvider;

/**
 * Query-scoped inference using an existing Elasticsearch trained regression/classification model.
 */
final class ElasticsearchMlProvider implements MachineLearningProvider {
  private static final Set<String> ARGUMENTS =
      Set.of("action", "algorithm", "model_id", "prediction_field", "batch_size");
  // Keep the transport field separate from query aliases such as "error" or "warning".
  private static final String RESULT_FIELD = "prediction";
  private final ElasticsearchQueryBackend backend;

  ElasticsearchMlProvider(ElasticsearchQueryBackend backend) {
    this.backend = backend;
  }

  @Override
  public Set<String> argumentNames() {
    return ARGUMENTS;
  }

  @Override
  public Operation plan(Map<String, Object> arguments, RelDataType inputType) {
    rejectUnknownArguments(arguments, ARGUMENTS);
    if (!stringArgument(arguments, "action").equalsIgnoreCase("predict")) {
      throw new IllegalArgumentException(
          "Elasticsearch ML supports action='predict' using an existing trained model");
    }
    String algorithm = stringArgument(arguments, "algorithm").toLowerCase(Locale.ROOT);
    if (!Set.of("regression", "classification").contains(algorithm)) {
      throw new IllegalArgumentException(
          "Elasticsearch ML supports algorithm='regression' or 'classification'");
    }
    String modelId = stringArgument(arguments, "model_id");
    if (!modelId.matches("[A-Za-z0-9._-]+") || modelId.equals(".") || modelId.equals("..")) {
      throw new IllegalArgumentException(
          "Elasticsearch ML model_id must name a single trained model");
    }
    String predictionField =
        arguments.containsKey("prediction_field")
            ? stringArgument(arguments, "prediction_field")
            : "prediction";
    int batchSize = intArgument(arguments, "batch_size", 128, 1, 1000);
    RelDataType outputType =
        appendPredictionType(
            inputType,
            predictionField,
            algorithm.equals("regression") ? SqlTypeName.DOUBLE : SqlTypeName.VARCHAR);
    for (var field : inputType.getFieldList()) {
      validateType(field.getType(), field.getName());
    }
    if (Set.copyOf(inputType.getFieldNames()).size() != inputType.getFieldCount()) {
      throw new IllegalArgumentException("Elasticsearch ML input field names must be unique");
    }
    return new InferenceOperation(backend, inputType, outputType, algorithm, modelId, batchSize);
  }

  private record InferenceOperation(
      ElasticsearchQueryBackend backend,
      RelDataType inputType,
      RelDataType outputType,
      String algorithm,
      String modelId,
      int batchSize)
      implements Operation {
    @Override
    public Enumerable<Object[]> execute(Enumerable<Object[]> input, DataContext context) {
      return new AbstractEnumerable<>() {
        @Override
        public Enumerator<Object[]> enumerator() {
          checkCancelled(context);
          return new InferenceEnumerator(InferenceOperation.this, input.enumerator(), context);
        }
      };
    }
  }

  private static final class InferenceEnumerator implements Enumerator<Object[]> {
    private final InferenceOperation operation;
    private final Enumerator<Object[]> input;
    private final DataContext context;
    private List<Object[]> batch = List.of();
    private int position;
    private Object[] current;
    private boolean inputClosed;
    private boolean closed;

    private InferenceEnumerator(
        InferenceOperation operation, Enumerator<Object[]> input, DataContext context) {
      this.operation = operation;
      this.input = input;
      this.context = context;
    }

    @Override
    public Object[] current() {
      return current;
    }

    @Override
    public boolean moveNext() {
      if (closed) return false;
      try {
        checkCancelled(context);
        if (position == batch.size()) loadBatch();
        if (position == batch.size()) {
          close();
          return false;
        }
        current = batch.get(position++);
        return true;
      } catch (RuntimeException | Error failure) {
        try {
          close();
        } catch (RuntimeException | Error cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    private void loadBatch() {
      batch = List.of();
      position = 0;
      List<Object[]> rows = new ArrayList<>();
      List<Map<String, Object>> documents = new ArrayList<>();
      while (!inputClosed && rows.size() < operation.batchSize()) {
        checkCancelled(context);
        boolean hasNext = input.moveNext();
        checkCancelled(context);
        if (!hasNext) {
          closeInput();
          break;
        }
        Object[] row = input.current().clone();
        if (row.length != operation.inputType().getFieldCount()) {
          throw new IllegalArgumentException(
              "Elasticsearch ML input row does not match its schema");
        }
        Map<String, Object> document = new LinkedHashMap<>();
        for (var field : operation.inputType().getFieldList()) {
          document.put(
              field.getName(), jsonValue(row[field.getIndex()], field.getType(), field.getName()));
        }
        rows.add(row);
        documents.add(document);
      }
      if (rows.isEmpty()) return;
      checkCancelled(context);
      Map<String, Object> response =
          operation
              .backend()
              .request(
                  "POST",
                  "/_ml/trained_models/"
                      + ElasticsearchQueryBackend.path(operation.modelId())
                      + "/_infer",
                  Map.of(
                      "docs",
                      documents,
                      "inference_config",
                      Map.of(operation.algorithm(), Map.of("results_field", RESULT_FIELD))));
      checkCancelled(context);
      if (response.containsKey("error")) {
        throw new IllegalStateException("Elasticsearch inference failed: " + response.get("error"));
      }
      if (!(response.get("inference_results") instanceof List<?> predictions)
          || predictions.size() != rows.size()) {
        throw new IllegalStateException(
            "Elasticsearch inference result count does not match the input batch");
      }
      List<Object[]> result = new ArrayList<>(rows.size());
      for (int i = 0; i < rows.size(); i++) {
        checkCancelled(context);
        if (!(predictions.get(i) instanceof Map<?, ?> prediction)) {
          throw new IllegalStateException("Elasticsearch returned an invalid inference result");
        }
        if (prediction.containsKey("error") || prediction.containsKey("warning")) {
          throw new IllegalStateException("Elasticsearch inference failed: " + prediction);
        }
        Object value = prediction.get(RESULT_FIELD);
        if (operation.algorithm().equals("regression")) {
          if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalStateException(
                "Elasticsearch regression returned no finite prediction");
          }
          value = number.doubleValue();
        } else {
          if (!(value instanceof String || value instanceof Number || value instanceof Boolean)
              || (value instanceof Number number && !Double.isFinite(number.doubleValue()))) {
            throw new IllegalStateException(
                "Elasticsearch classification returned no scalar label");
          }
          value = value.toString();
        }
        result.add(appendPrediction(rows.get(i), value));
      }
      batch = result;
    }

    @Override
    public void reset() {
      throw new UnsupportedOperationException("Create a new Enumerator to repeat inference");
    }

    @Override
    public void close() {
      if (closed) return;
      closed = true;
      batch = List.of();
      current = null;
      closeInput();
    }

    private void closeInput() {
      if (!inputClosed) {
        inputClosed = true;
        input.close();
      }
    }
  }

  private static void validateType(RelDataType type, String field) {
    switch (type.getSqlTypeName()) {
      case ANY,
          NULL,
          CHAR,
          VARCHAR,
          BOOLEAN,
          TINYINT,
          SMALLINT,
          INTEGER,
          BIGINT,
          DECIMAL,
          FLOAT,
          REAL,
          DOUBLE,
          DATE,
          TIME,
          TIMESTAMP,
          TIMESTAMP_WITH_LOCAL_TIME_ZONE -> {}
      case ARRAY -> validateType(type.getComponentType(), field);
      case MAP -> {
        if (!Set.of(SqlTypeName.CHAR, SqlTypeName.VARCHAR, SqlTypeName.ANY)
            .contains(type.getKeyType().getSqlTypeName())) {
          throw new IllegalArgumentException("Elasticsearch ML map keys must be strings: " + field);
        }
        validateType(type.getValueType(), field);
      }
      default ->
          throw new IllegalArgumentException(
              "Elasticsearch ML does not support input type " + type + " for " + field);
    }
  }

  private static Object jsonValue(Object value, RelDataType type, String field) {
    if (value == null) return null;
    if (type != null) {
      switch (type.getSqlTypeName()) {
        case TIMESTAMP, TIMESTAMP_WITH_LOCAL_TIME_ZONE -> {
          if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue()).toString();
          }
        }
        case DATE -> {
          if (value instanceof Number number)
            return LocalDate.ofEpochDay(number.longValue()).toString();
        }
        case TIME -> {
          if (value instanceof Number number) {
            return LocalTime.ofNanoOfDay(number.longValue() * 1_000_000L).toString();
          }
        }
        default -> {}
      }
    }
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().toString();
    if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
    if (value instanceof java.sql.Time time) return time.toLocalTime().toString();
    if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC).toString();
    if (value instanceof OffsetDateTime time) return time.toInstant().toString();
    if (value instanceof Instant || value instanceof LocalDate || value instanceof LocalTime) {
      return value.toString();
    }
    if (value instanceof String || value instanceof Boolean) return value;
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigDecimal
        || value instanceof BigInteger) return value;
    if ((value instanceof Double || value instanceof Float)
        && Double.isFinite(((Number) value).doubleValue())) return value;
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      RelDataType valueType =
          type != null && type.getSqlTypeName() == SqlTypeName.MAP ? type.getValueType() : null;
      for (var entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw new IllegalArgumentException("Elasticsearch ML map keys must be strings: " + field);
        }
        result.put(key, jsonValue(entry.getValue(), valueType, field + "." + key));
      }
      return result;
    }
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>(list.size());
      RelDataType elementType =
          type != null && type.getSqlTypeName() == SqlTypeName.ARRAY
              ? type.getComponentType()
              : null;
      for (Object element : list) result.add(jsonValue(element, elementType, field));
      return result;
    }
    throw new IllegalArgumentException(
        "Elasticsearch ML cannot encode field '"
            + field
            + "' with value type "
            + value.getClass().getName());
  }
}
