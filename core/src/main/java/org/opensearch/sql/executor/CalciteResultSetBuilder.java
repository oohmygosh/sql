/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.executor;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.sql.calcite.CalcitePlanContext;
import org.opensearch.sql.calcite.utils.OpenSearchTypeFactory;
import org.opensearch.sql.calcite.utils.TimewrapPivot;
import org.opensearch.sql.data.model.ExprTupleValue;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.data.model.ExprValueUtils;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.data.type.ExprType;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.ExecutionEngine.Schema;
import org.opensearch.sql.executor.ExecutionEngine.Schema.Column;

/** Shared result conversion, including PPL post-processing. */
public class CalciteResultSetBuilder {
  private final boolean preserveNativeTypes;

  public CalciteResultSetBuilder() {
    this(false);
  }

  public CalciteResultSetBuilder(boolean preserveNativeTypes) {
    this.preserveNativeTypes = preserveNativeTypes;
  }

  protected Object convertScalar(Object value, RelDataType type) {
    return value;
  }

  /**
   * Process values recursively, handling geo points, nested maps, structs and arrays. When a {@link
   * RelDataType} is provided, struct values (StructImpl) are converted to Maps keyed by field
   * names, preserving field-name information in the JSON output.
   *
   * @param value The raw value from the JDBC result set
   * @param type The Calcite type metadata for this value, or null if unavailable
   */
  @SuppressWarnings("unchecked")
  protected Object processValue(Object value, RelDataType type) throws SQLException {
    switch (value) {
      case null -> {
        return null;
      }
      case java.math.BigDecimal decimal when preserveNativeTypes -> {
        return new org.opensearch.sql.data.model.ExprDecimalValue(decimal);
      }
      case java.math.BigInteger integer when preserveNativeTypes -> {
        return new org.opensearch.sql.data.model.ExprDecimalValue(
            new java.math.BigDecimal(integer));
      }
      case java.sql.Array array -> {
        try {
          return processValue(Arrays.asList((Object[]) array.getArray()), type);
        } finally {
          array.free();
        }
      }
      case Map<?,?> ignored -> {
        Map<String, Object> map = (Map<String, Object>) value;
        Map<String, Object> convertedMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
          convertedMap.put(entry.getKey(), processValue(entry.getValue(), null));
        }
        return convertedMap;
      }
      case java.sql.Struct structImpl -> {
        Object[] attrs = structImpl.getAttributes();
        if (type != null && type.getSqlTypeName() == SqlTypeName.ROW) {
          List<RelDataTypeField> fields = type.getFieldList();
          Map<String, Object> map = new LinkedHashMap<>();
          for (int i = 0; i < fields.size() && i < attrs.length; i++) {
            map.put(fields.get(i).getName(), processValue(attrs[i], fields.get(i).getType()));
          }
          return map;
        }
        return Arrays.asList(attrs);
      }
      case List<?> ignored -> {
        List<Object> list = (List<Object>) value;
        RelDataType componentType =
            (type != null && type.getComponentType() != null) ? type.getComponentType() : null;
        List<Object> convertedList = new ArrayList<>();
        for (Object item : list) {
          convertedList.add(processValue(item, componentType));
        }
        return convertedList;
      }
      case Object[] array -> {
        return processValue(Arrays.asList(array), type);
      }
      default -> {}
    }
    // For other types, return as-is
    return convertScalar(value, type);
  }

  public QueryResponse buildResultSet(
      ResultSet resultSet, RelDataType rowTypes, Integer querySizeLimit) throws SQLException {
    // Get the ResultSet metadata to know about columns
    ResultSetMetaData metaData = resultSet.getMetaData();
    List<RelDataType> fieldTypes =
        rowTypes.getFieldList().stream().map(RelDataTypeField::getType).toList();
    List<ExprValue> values = new ArrayList<>();
    // Iterate through the ResultSet
    while (resultSet.next() && (querySizeLimit == null || values.size() < querySizeLimit)) {
      values.add(readRow(resultSet, fieldTypes, metaData));
    }

    List<Column> columns =
        columns(metaData, fieldTypes, values.isEmpty() ? null : values.getFirst());
    // Drain before the timewrap block: its finally clears the same thread-local.
    List<Warning> warnings = CalcitePlanContext.drainWarnings();

    // Timewrap post-processing: pivot unpivoted rows into period columns. The pivot is shared with
    // the analytics route (AnalyticsExecutionEngine) so both engines produce identical output.
    if (TimewrapPivot.isTimewrap()) {
      try {
        TimewrapPivot.Result pivoted =
            TimewrapPivot.pivot(
                columns,
                values,
                CalcitePlanContext.timewrapUnitName.get(),
                CalcitePlanContext.timewrapSeries.get());
        columns = pivoted.columns();
        values = pivoted.values();
      } finally {
        CalcitePlanContext.clearTimewrapSignals();
      }
    }

    Schema schema = new Schema(columns);
    QueryResponse response = new QueryResponse(schema, values, null);
    response.setWarnings(warnings);
    return response;
  }

  /** Emits a schema and one converted row at a time, without retaining the output collection. */
  public List<Warning> streamResultSet(
      ResultSet resultSet,
      RelDataType rowTypes,
      QueryRowConsumer consumer,
      QueryCancellation cancellation)
      throws Exception {
    if (TimewrapPivot.isTimewrap()) {
      throw new UnsupportedOperationException(
          "timewrap requires materialization and cannot be streamed");
    }
    ResultSetMetaData metadata = resultSet.getMetaData();
    List<RelDataType> types =
        rowTypes.getFieldList().stream().map(RelDataTypeField::getType).toList();
    cancellation.check();
    ExprValue first = resultSet.next() ? readRow(resultSet, types, metadata) : null;
    cancellation.check();
    consumer.schema(new Schema(columns(metadata, types, first)));
    cancellation.check();
    if (first != null) consumer.row(first);
    while (true) {
      cancellation.check();
      if (!resultSet.next()) break;
      cancellation.check();
      consumer.row(readRow(resultSet, types, metadata));
    }
    return CalcitePlanContext.drainWarnings();
  }

  private ExprValue readRow(
      ResultSet resultSet, List<RelDataType> fieldTypes, ResultSetMetaData metadata)
      throws SQLException {
    Map<String, ExprValue> row = new LinkedHashMap<>();
    for (int i = 1; i <= metadata.getColumnCount(); i++) {
      Object value;
      if (preserveNativeTypes && fieldTypes.get(i - 1).getSqlTypeName() == SqlTypeName.TIMESTAMP) {
        // Calcite exposes epoch milliseconds directly. Avatica's getTimestamp(Calendar) first
        // passes through JVM-local calendar fields, which can shift instants in a DST gap.
        long millis = resultSet.getLong(i);
        value = resultSet.wasNull() ? null : Instant.ofEpochMilli(millis);
      } else {
        value = resultSet.getObject(i);
      }
      row.put(
          metadata.getColumnName(i),
          ExprValueUtils.fromObjectValue(processValue(value, fieldTypes.get(i - 1))));
    }
    return ExprTupleValue.fromExprValueMap(row);
  }

  private List<Column> columns(
      ResultSetMetaData metaData, List<RelDataType> fieldTypes, ExprValue firstRow)
      throws SQLException {
    int columnCount = metaData.getColumnCount();
    List<Column> columns = new ArrayList<>(metaData.getColumnCount());
    for (int i = 1; i <= columnCount; ++i) {
      String columnName = metaData.getColumnName(i);
      RelDataType fieldType = fieldTypes.get(i - 1);
      // TODO: Correct this after fixing issue github.com/opensearch-project/sql/issues/3751
      //  The element type of struct and array is currently set to ANY.
      //  We set them using the runtime type as a workaround.
      ExprType exprType;
      if (fieldType.getSqlTypeName() == SqlTypeName.ANY) {
        if (firstRow != null) {
          exprType = firstRow.tupleValue().get(columnName).type();
        } else {
          // Using UNDEFINED instead of UNKNOWN to avoid throwing exception
          exprType = ExprCoreType.UNDEFINED;
        }
      } else {
        exprType = OpenSearchTypeFactory.convertRelDataTypeToExprType(fieldType);
      }
      columns.add(new Column(columnName, null, exprType));
    }
    return columns;
  }
}
