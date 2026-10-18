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
  public void quoteStringLiteral(StringBuilder buffer, String charsetName, String value) {
    // ClickHouse strings are UTF-8 and do not accept SQL charset introducers such as _UTF-8.
    super.quoteStringLiteral(buffer, null, value);
  }

  @Override
  public boolean supportsFunction(SqlOperator operator, RelDataType type, List<RelDataType> args) {
    if (operator.getName().equals("SEARCH_TIME_BUCKET")) return true;
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
      writer.sep(",");
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
    if (call.getOperator().getName().equals("SEARCH_TIME_BUCKET")) {
      String interval =
          ((org.apache.calcite.sql.SqlLiteral) call.operand(1)).getValueAs(String.class);
      String zone = ((org.apache.calcite.sql.SqlLiteral) call.operand(2)).getValueAs(String.class);
      java.time.ZoneId normalized = java.time.ZoneId.of(zone).normalized();
      String unit =
          switch (interval) {
            case "1m" -> "MINUTE";
            case "1h" -> "HOUR";
            case "1d" -> "DAY";
            default ->
                throw new IllegalArgumentException("Unsupported search interval: " + interval);
          };
      // ClickHouse accepts IANA names but not Java's "Z" or numeric offset zone identifiers.
      // A fixed offset can be applied before UTC rounding and removed afterwards.
      if (normalized instanceof java.time.ZoneOffset offset) {
        int seconds = offset.getTotalSeconds();
        writer.print("toTimeZone(addSeconds(toStartOfInterval(addSeconds(");
        call.operand(0).unparse(writer, 0, 0);
        writer.print(
            ", " + seconds + "), INTERVAL 1 " + unit + ", 'UTC'), " + -seconds + "), 'UTC')");
        return;
      }
      // JDBC exposes calendar fields; return UTC wall-clock values for Calcite's UTC timestamps.
      writer.print("toTimeZone(toStartOfInterval(");
      call.operand(0).unparse(writer, 0, 0);
      writer.print(", INTERVAL 1 " + unit + ", ");
      call.operand(2).unparse(writer, 0, 0);
      writer.print("), 'UTC')");
      return;
    }
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
