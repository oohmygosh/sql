/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.executor;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
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

  protected Object convertScalar(Object value, RelDataType type) throws SQLException {
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
    if (value == null) {
      return null;
    }
    if (preserveNativeTypes && value instanceof java.math.BigDecimal decimal)
      return new org.opensearch.sql.data.model.ExprDecimalValue(decimal);
    if (preserveNativeTypes && value instanceof java.math.BigInteger integer)
      return new org.opensearch.sql.data.model.ExprDecimalValue(new java.math.BigDecimal(integer));
    if (value instanceof java.sql.Array array) {
      try {
        return processValue(java.util.Arrays.asList((Object[]) array.getArray()), type);
      } finally {
        array.free();
      }
    }
    if (value instanceof Map) {
      Map<String, Object> map = (Map<String, Object>) value;
      Map<String, Object> convertedMap = new HashMap<>();
      for (Map.Entry<String, Object> entry : map.entrySet()) {
        convertedMap.put(entry.getKey(), processValue(entry.getValue(), null));
      }
      return convertedMap;
    }
    if (value instanceof java.sql.Struct structImpl) {
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
    if (value instanceof List) {
      List<Object> list = (List<Object>) value;
      RelDataType componentType =
          (type != null && type.getComponentType() != null) ? type.getComponentType() : null;
      List<Object> convertedList = new ArrayList<>();
      for (Object item : list) {
        convertedList.add(processValue(item, componentType));
      }
      return convertedList;
    }
    if (value instanceof Object[] array) return processValue(Arrays.asList(array), type);
    // For other types, return as-is
    return convertScalar(value, type);
  }

  public QueryResponse buildResultSet(
      ResultSet resultSet, RelDataType rowTypes, Integer querySizeLimit) throws SQLException {
    // Get the ResultSet metadata to know about columns
    ResultSetMetaData metaData = resultSet.getMetaData();
    int columnCount = metaData.getColumnCount();
    List<RelDataType> fieldTypes =
        rowTypes.getFieldList().stream().map(RelDataTypeField::getType).toList();
    List<ExprValue> values = new ArrayList<>();
    var utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    // Iterate through the ResultSet
    while (resultSet.next() && (querySizeLimit == null || values.size() < querySizeLimit)) {
      Map<String, ExprValue> row = new LinkedHashMap<String, ExprValue>();
      // Loop through each column
      for (int i = 1; i <= columnCount; i++) {
        String columnName = metaData.getColumnName(i);
        Object value =
            preserveNativeTypes && fieldTypes.get(i - 1).getSqlTypeName() == SqlTypeName.TIMESTAMP
                ? resultSet.getTimestamp(i, utcCalendar)
                : resultSet.getObject(i);
        Object converted = processValue(value, fieldTypes.get(i - 1));
        ExprValue exprValue = ExprValueUtils.fromObjectValue(converted);
        row.put(columnName, exprValue);
      }
      values.add(ExprTupleValue.fromExprValueMap(row));
    }

    List<Column> columns = new ArrayList<>(metaData.getColumnCount());
    for (int i = 1; i <= columnCount; ++i) {
      String columnName = metaData.getColumnName(i);
      RelDataType fieldType = fieldTypes.get(i - 1);
      // TODO: Correct this after fixing issue github.com/opensearch-project/sql/issues/3751
      //  The element type of struct and array is currently set to ANY.
      //  We set them using the runtime type as a workaround.
      ExprType exprType;
      if (fieldType.getSqlTypeName() == SqlTypeName.ANY) {
        if (!values.isEmpty()) {
          exprType = values.getFirst().tupleValue().get(columnName).type();
        } else {
          // Using UNDEFINED instead of UNKNOWN to avoid throwing exception
          exprType = ExprCoreType.UNDEFINED;
        }
      } else {
        exprType = OpenSearchTypeFactory.convertRelDataTypeToExprType(fieldType);
      }
      columns.add(new Column(columnName, null, exprType));
    }
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
}
