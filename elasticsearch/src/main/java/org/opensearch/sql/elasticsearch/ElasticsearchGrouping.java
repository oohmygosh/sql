/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;
import org.opensearch.sql.expression.function.PPLBuiltinOperators;

/** A composite source together with the conversion from its key to the PPL grouping value. */
final class ElasticsearchGrouping {
  private static final String INTEGRAL_SPAN =
      "if (!doc.containsKey(params.field) || doc[params.field].size() == 0) { return null; } "
          + "long value = doc[params.field].value; long width = params.width; "
          + "return (value / width) * width;";

  private final Map<String, Object> source;
  private final Double binWidth;

  private ElasticsearchGrouping(Map<String, Object> source, Double binWidth) {
    this.source = source;
    this.binWidth = binWidth;
  }

  static ElasticsearchGrouping translate(RexNode expression, List<Field> fields) {
    if (expression instanceof RexInputRef ref) {
      Field field = fields.get(ref.getIndex());
      if (!field.exact() || List.of("_id", "_score").contains(field.name())) return null;
      return new ElasticsearchGrouping(
          Map.of("terms", Map.of("field", field.name(), "missing_bucket", true)), null);
    }
    if (!(expression instanceof RexCall call)) return null;
    boolean span = call.getOperator() == PPLBuiltinOperators.SPAN;
    boolean bin = call.getOperator() == PPLBuiltinOperators.SPAN_BUCKET;
    if ((!span && !bin)
        || call.getOperands().size() != (span ? 3 : 2)
        || !(call.getOperands().get(0) instanceof RexInputRef ref)
        || !(call.getOperands().get(1) instanceof RexLiteral width)
        || width.isNull()) return null;
    Field field = fields.get(ref.getIndex());
    if (!field.exact() || List.of("_id", "_score").contains(field.name())) return null;
    if (span) {
      if (!(call.getOperands().get(2) instanceof RexLiteral unit)) return null;
      if (!unit.isNull()) return timeSpan(field, width, unit);
    }
    if (!SqlTypeName.NUMERIC_TYPES.contains(width.getType().getSqlTypeName())) return null;
    BigDecimal literal = width.getValueAs(BigDecimal.class);
    if (literal == null || literal.signum() <= 0) return null;
    if (span && integral(call.getType().getSqlTypeName())) {
      if (!integral(field.sqlType()) || !integral(width.getType().getSqlTypeName())) return null;
      long interval;
      try {
        interval = literal.longValueExact();
      } catch (ArithmeticException e) {
        return null;
      }
      // Histogram floors negative values and converts longs to doubles. PPL's integral SPAN
      // truncates toward zero; long arithmetic also keeps adjacent values above 2^53 distinct.
      return new ElasticsearchGrouping(
          Map.of(
              "terms",
              Map.of(
                  "script",
                  Map.of(
                      "lang",
                      "painless",
                      "source",
                      INTEGRAL_SPAN,
                      "params",
                      Map.of("field", field.name(), "width", interval)),
                  "value_type",
                  "long",
                  "missing_bucket",
                  true)),
          null);
    }

    double interval = literal.doubleValue();
    if (!Double.isFinite(interval) || interval <= 0) return null;
    boolean boundedInteger =
        List.of(SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER)
            .contains(field.sqlType());
    // Float/half_float/scaled_float doc values may differ from the source value used locally.
    // Unsigned values and floating operations on longs require precision analysis beyond this
    // source's type-only bounds; leave these to the local implementation.
    if (!boundedInteger && !field.mappingType().equals("double")) return null;
    if (bin) {
      // For a signed <=32-bit input these bounds keep endpoints within 2^32 and distinct input
      // buckets at least ~1 apart (width < 1) or ~width apart (width >= 1). Range formatting uses
      // at most 0.1-sized rounding, so labels are injective. Unbounded doubles can saturate the
      // formatter's long casts; tiny floating bins can also share a four-decimal label.
      if (!boundedInteger || interval < 0.0001 || interval > Integer.MAX_VALUE) return null;
    } else if (integral(width.getType().getSqlTypeName()) && boundedInteger) {
      // The result must follow the integral implementation above, never histogram flooring.
      return null;
    }
    double bound = boundedInteger ? -(double) Integer.MIN_VALUE : Double.MAX_VALUE;
    // Finite input alone is insufficient: a small width may overflow division, or rounding at
    // the extreme may overflow multiplication. Both operations are monotone for a positive width.
    if (!Double.isFinite(Math.floor(-bound / interval) * interval)
        || !Double.isFinite(Math.floor(bound / interval) * interval)) return null;
    return new ElasticsearchGrouping(
        Map.of(
            "histogram",
            Map.of("field", field.name(), "interval", interval, "missing_bucket", true)),
        bin ? interval : null);
  }

  private static boolean integral(SqlTypeName type) {
    return List.of(
            SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER, SqlTypeName.BIGINT)
        .contains(type);
  }

  private static ElasticsearchGrouping timeSpan(Field field, RexLiteral width, RexLiteral unit) {
    if (field.sqlType() != SqlTypeName.TIMESTAMP) return null;
    int interval;
    try {
      interval = width.getValueAs(BigDecimal.class).intValueExact();
    } catch (ArithmeticException e) {
      return null;
    }
    if (interval <= 0) return null;
    String timeUnit = unit.getValueAs(String.class);
    String intervalKind;
    switch (timeUnit) {
      case "ms", "s", "m", "h", "d" -> intervalKind = "fixed_interval";
      case "w", "M", "q", "y" -> {
        // Preserve PPL's UTC calendar alignment and retain multi-calendar spans locally.
        if (interval != 1) return null;
        intervalKind = "calendar_interval";
      }
      default -> {
        return null;
      }
    }
    return new ElasticsearchGrouping(
        Map.of(
            "date_histogram",
            Map.of(
                "field", field.name(), intervalKind, interval + timeUnit, "missing_bucket", true)),
        null);
  }

  Map<String, Object> source() {
    return source;
  }

  Object value(Object nativeKey) {
    if (nativeKey == null || binWidth == null) return nativeKey;
    double start = ((Number) nativeKey).doubleValue();
    double end = start + binWidth;
    // Match SpanBucketFunction's formatter without bucketing the already rounded key again:
    // floor(start / width) * width can move a floating boundary into its previous bucket.
    if (binWidth == Math.floor(binWidth)
        && Math.abs(start - Math.round(start)) < 1e-10
        && Math.abs(end - Math.round(end)) < 1e-10) {
      return String.format("%d-%d", (long) start, (long) end);
    }
    int decimals = binWidth >= 1 ? 1 : binWidth >= 0.1 ? 2 : binWidth >= 0.01 ? 3 : 4;
    String format = "%." + decimals + "f-%." + decimals + "f";
    return String.format(format, start, end);
  }
}
