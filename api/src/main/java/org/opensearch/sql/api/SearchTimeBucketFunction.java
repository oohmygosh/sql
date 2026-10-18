/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.apache.calcite.adapter.enumerable.NullPolicy;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.sql.data.model.ExprValueUtils;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.expression.function.ImplementorUDF;
import org.opensearch.sql.expression.function.UDFOperandMetadata;

/** Search-only calendar buckets. Does not change the language-level span function. */
public final class SearchTimeBucketFunction extends ImplementorUDF {
  public static final SqlOperator OPERATOR =
      new SearchTimeBucketFunction().toUDF("SEARCH_TIME_BUCKET");

  public SearchTimeBucketFunction() {
    super(
        (translator, call, operands) ->
            Expressions.call(
                SearchTimeBucketFunction.class,
                "eval",
                Expressions.convert_(Expressions.box(operands.get(0)), Object.class),
                operands.get(1),
                operands.get(2)),
        NullPolicy.STRICT);
  }

  @Override
  public org.apache.calcite.sql.type.SqlReturnTypeInference getReturnTypeInference() {
    return binding ->
        binding
            .getTypeFactory()
            .createTypeWithNullability(
                binding.getTypeFactory().createSqlType(SqlTypeName.TIMESTAMP, 3), true);
  }

  @Override
  public UDFOperandMetadata getOperandMetadata() {
    return null;
  }

  public static Long eval(Object value, String interval, String timeZone) {
    if (value == null) return null;
    Instant instant =
        value instanceof Number number
            ? Instant.ofEpochMilli(number.longValue())
            : ExprValueUtils.fromObjectValue(value, ExprCoreType.TIMESTAMP).timestampValue();
    return floor(instant, interval, ZoneId.of(timeZone)).toEpochMilli();
  }

  public static Instant floor(Instant instant, String interval, ZoneId zone) {
    var local = instant.atZone(zone);
    return switch (interval) {
      case "1m" -> local.truncatedTo(ChronoUnit.MINUTES).toInstant();
      case "1h" -> local.truncatedTo(ChronoUnit.HOURS).toInstant();
      case "1d" -> local.toLocalDate().atStartOfDay(zone).toInstant();
      default -> throw new IllegalArgumentException("Unsupported histogram interval: " + interval);
    };
  }
}
