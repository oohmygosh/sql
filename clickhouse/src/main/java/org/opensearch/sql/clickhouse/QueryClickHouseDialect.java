/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.clickhouse;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.calcite.config.NullCollation;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlAbstractDateTimeLiteral;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlTimestampLiteral;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.dialect.ClickHouseSqlDialect;
import org.apache.calcite.sql.validate.SqlUserDefinedFunction;

/** SQL/PPL null semantics and conservative handling of local functions. */
final class QueryClickHouseDialect extends ClickHouseSqlDialect {
  static final QueryClickHouseDialect INSTANCE = new QueryClickHouseDialect();

  private QueryClickHouseDialect() {
    super(DEFAULT_CONTEXT.withNullCollation(NullCollation.HIGH));
  }

  @Override
  public boolean supportsFunction(SqlOperator operator, RelDataType type, List<RelDataType> args) {
    if (Set.of(
            "CAST",
            "SAFE_CAST",
            "ROUND",
            "REGEXP_CONTAINS",
            "LIKE",
            "ILIKE",
            "ARRAY_LENGTH",
            "ARRAY_JOIN",
            "CHAR_LENGTH",
            "CHARACTER_LENGTH",
            "LENGTH")
        .contains(operator.getName().toUpperCase(Locale.ROOT))) return false;
    return !(operator instanceof SqlUserDefinedFunction)
        && super.supportsFunction(operator, type, args);
  }

  @Override
  public boolean supportsAggregateFunction(SqlKind kind) {
    return kind == SqlKind.OTHER_FUNCTION || super.supportsAggregateFunction(kind);
  }

  @Override
  public boolean supportsAggregateFunctionFilter() {
    return false;
  }

  @Override
  public void unparseDateTimeLiteral(
      org.apache.calcite.sql.SqlWriter writer,
      SqlAbstractDateTimeLiteral literal,
      int left,
      int right) {
    if (literal instanceof SqlTimestampLiteral timestamp) {
      writer.print("toDateTime64");
      var frame =
          writer.startList(org.apache.calcite.sql.SqlWriter.FrameTypeEnum.FUN_CALL, "(", ")");
      writer.literal("'" + timestamp.toFormattedString() + "'");
      writer.sep(",");
      writer.literal(Integer.toString(Math.max(3, timestamp.getPrec())));
      writer.sep(",");
      writer.literal("'UTC'");
      writer.endList(frame);
    } else super.unparseDateTimeLiteral(writer, literal, left, right);
  }

  @Override
  public void unparseCall(SqlWriter writer, SqlCall call, int left, int right) {
    String function =
        switch (call.getKind()) {
          case SUM -> "sumOrNull";
          case MIN -> "minOrNull";
          case MAX -> "maxOrNull";
          case AVG -> "avgOrNull";
          default -> null;
        };
    if (Set.of("DISTINCT_COUNT_APPROX", "APPROX_COUNT_DISTINCT")
        .contains(call.getOperator().getName().toUpperCase(Locale.ROOT))) function = "uniqHLL12";
    if (function == null) {
      super.unparseCall(writer, call, left, right);
      return;
    }
    writer.print(function);
    SqlWriter.Frame frame = writer.startList(SqlWriter.FrameTypeEnum.FUN_CALL, "(", ")");
    if (call.getFunctionQuantifier() != null) call.getFunctionQuantifier().unparse(writer, 0, 0);
    for (var operand : call.getOperandList()) {
      writer.sep(",");
      operand.unparse(writer, 0, 0);
    }
    writer.endList(frame);
  }
}
