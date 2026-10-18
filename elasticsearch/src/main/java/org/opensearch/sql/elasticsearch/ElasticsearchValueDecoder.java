/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.chrono.IsoEra;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.calcite.rel.type.RelDataType;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;

/** Converts original source values to Calcite values without altering source objects. */
public final class ElasticsearchValueDecoder {
  private ElasticsearchValueDecoder() {}

  /** Read a physical path according to its per-index object structure. */
  public static Object sourceValue(Map<String, Object> source, Field field, String index) {
    String path = field.source(index);
    var origin = field.origins().getOrDefault(index, field.origin());
    boolean flat = origin == ElasticsearchQueryBackend.SourceOrigin.FLAT;
    Object value = source;
    while (value instanceof Map<?, ?> object) {
      if (flat && object.containsKey(path)) return object.get(path);
      int separator = path.indexOf('.');
      if (separator < 0) return object.get(path);
      value = object.get(path.substring(0, separator));
      path = path.substring(separator + 1);
    }
    return null;
  }

  /** Null is a value; invalid non-null input is an error with its physical field context. */
  public static Object decode(Object value, RelDataType type, Field field, String index) {
    try {
      return decodeValue(value, type, field.dateFormat(index));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "Cannot decode Elasticsearch field '"
              + field.name()
              + "' (_source path '"
              + field.source(index)
              + "', index '"
              + index
              + "', mapping '"
              + field.mappingType()
              + "', format '"
              + field.dateFormat(index)
              + "') as "
              + type
              + ": "
              + e.getMessage(),
          e);
    }
  }

  /** Aggregation timestamps are already epoch milliseconds, independent of mapping input format. */
  public static Object decodeAggregate(Object value, RelDataType type) {
    try {
      return decodeValue(value, type, "strict_date_optional_time||epoch_millis");
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "Cannot decode Elasticsearch aggregate as " + type + ": " + e.getMessage(), e);
    }
  }

  private static Object decodeValue(Object value, RelDataType type, String dateFormat) {
    if (value == null) return null;
    return switch (type.getSqlTypeName()) {
      case TINYINT -> integral(value).byteValueExact();
      case SMALLINT -> integral(value).shortValueExact();
      case INTEGER -> integral(value).intValueExact();
      case BIGINT -> integral(value).longValueExact();
      case FLOAT, REAL -> floating(value).floatValue();
      case DOUBLE -> floating(value);
      case DECIMAL -> decimal(value);
      case BOOLEAN -> bool(value);
      case CHAR, VARCHAR -> {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean))
          throw new IllegalArgumentException("expected a scalar string value");
        yield value.toString();
      }
      case ARRAY -> {
        if (!(value instanceof List<?> list))
          throw new IllegalArgumentException("expected an array");
        List<Object> values = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
          try {
            values.add(decodeValue(list.get(i), type.getComponentType(), dateFormat));
          } catch (RuntimeException e) {
            throw new IllegalArgumentException("array element " + i + ": " + e.getMessage(), e);
          }
        }
        yield values;
      }
      case TIMESTAMP, TIMESTAMP_WITH_LOCAL_TIME_ZONE -> timestamp(value, dateFormat);
      // ANY/MAP/object values retain their exact source representation and object identity.
      default -> value;
    };
  }

  private static BigDecimal decimal(Object value) {
    if (!(value instanceof Number || value instanceof String))
      throw new IllegalArgumentException("expected a number or numeric string");
    return value instanceof BigDecimal number ? number : new BigDecimal(value.toString().trim());
  }

  private static BigDecimal integral(Object value) {
    // Elasticsearch's default numeric coercion truncates fractional input toward zero.
    return decimal(value).setScale(0, RoundingMode.DOWN);
  }

  private static Double floating(Object value) {
    if (!(value instanceof Number || value instanceof String))
      throw new IllegalArgumentException("expected a number or numeric string");
    // Preserve -0.0: passing through BigDecimal erases its sign and merges distinct groups.
    double result = Double.parseDouble(value.toString().trim());
    if (!Double.isFinite(result)) throw new IllegalArgumentException("number exceeds finite range");
    // In particular, do not quantize float/half_float/scaled_float source values to indexed values.
    return result;
  }

  private static boolean bool(Object value) {
    if (value instanceof Boolean bool) return bool;
    if ("true".equals(value)) return true;
    if ("false".equals(value) || "".equals(value)) return false;
    throw new IllegalArgumentException("expected true, false, or an empty boolean string");
  }

  private static long timestamp(Object value, String formats) {
    if (!(value instanceof Number || value instanceof String))
      throw new IllegalArgumentException("expected a date string or epoch number");
    if (formats.isEmpty()) formats = Field.defaultDateFormat("date");
    RuntimeException failure = null;
    for (String format : formats.split("\\|\\|", -1)) {
      try {
        if (format.equals("epoch_millis") || format.equals("epoch_second")) {
          BigDecimal epoch = decimal(value);
          if (format.equals("epoch_second")) epoch = epoch.movePointRight(3);
          return epoch.setScale(0, RoundingMode.FLOOR).longValueExact();
        }
        if (format.equals("strict_date_optional_time")
            || format.equals("strict_date_optional_time_nanos")
            || format.equals("date_optional_time")) {
          if (value instanceof Number) continue;
          return optionalDateTime(value.toString());
        }
        return epochMillis(formatter(format).parse(value.toString()));
      } catch (RuntimeException e) {
        failure = e;
      }
    }
    throw new IllegalArgumentException(
        "date does not match configured format '" + formats + "'", failure);
  }

  private static long optionalDateTime(String value) {
    if (value.contains("T")) return epochMillis(DateTimeFormatter.ISO_DATE_TIME.parse(value));
    return epochMillis(
        new DateTimeFormatterBuilder()
            .appendPattern("uuuu[-MM[-dd]]")
            .parseDefaulting(ChronoField.MONTH_OF_YEAR, 1)
            .parseDefaulting(ChronoField.DAY_OF_MONTH, 1)
            .toFormatter(Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)
            .parse(value));
  }

  private static DateTimeFormatter formatter(String format) {
    String pattern =
        switch (format) {
          case "strict_date", "date", "strict_year_month_day", "year_month_day" -> "uuuu-MM-dd";
          case "strict_year", "year" -> "uuuu";
          case "strict_year_month", "year_month" -> "uuuu-MM";
          case "basic_date" -> "uuuuMMdd";
          case "strict_date_hour", "date_hour" -> "uuuu-MM-dd'T'HH";
          case "strict_date_hour_minute", "date_hour_minute" -> "uuuu-MM-dd'T'HH:mm";
          case "strict_date_hour_minute_second", "date_hour_minute_second" ->
              "uuuu-MM-dd'T'HH:mm:ss";
          case "strict_date_time_no_millis", "date_time_no_millis" -> "uuuu-MM-dd'T'HH:mm:ssXXX";
          case "basic_date_time_no_millis" -> "uuuuMMdd'T'HHmmssXX";
          case "basic_date_time" -> "uuuuMMdd'T'HHmmss.SSSXX";
          default -> format;
        };
    if (List.of("strict_date_time", "date_time").contains(format))
      return DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    if (List.of(
            "strict_date_hour_minute_second_fraction",
            "date_hour_minute_second_fraction",
            "strict_date_hour_minute_second_millis",
            "date_hour_minute_second_millis")
        .contains(format)) return DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    // Unknown named formats fail here; never reinterpret them as ISO dates or epoch milliseconds.
    return new DateTimeFormatterBuilder()
        .appendPattern(pattern)
        .parseDefaulting(ChronoField.ERA, IsoEra.CE.getValue())
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);
  }

  private static long epochMillis(TemporalAccessor parsed) {
    if (parsed.isSupported(ChronoField.INSTANT_SECONDS)) return Instant.from(parsed).toEpochMilli();
    LocalDate date;
    if (parsed.isSupported(ChronoField.EPOCH_DAY)) date = LocalDate.from(parsed);
    else {
      int year = parsed.get(ChronoField.YEAR);
      int month =
          parsed.isSupported(ChronoField.MONTH_OF_YEAR) ? parsed.get(ChronoField.MONTH_OF_YEAR) : 1;
      int day =
          parsed.isSupported(ChronoField.DAY_OF_MONTH) ? parsed.get(ChronoField.DAY_OF_MONTH) : 1;
      date = LocalDate.of(year, month, day);
    }
    LocalTime time =
        parsed.isSupported(ChronoField.HOUR_OF_DAY) ? LocalTime.from(parsed) : LocalTime.MIDNIGHT;
    ZoneOffset offset =
        parsed.isSupported(ChronoField.OFFSET_SECONDS) ? ZoneOffset.from(parsed) : ZoneOffset.UTC;
    return date.atTime(time).toInstant(offset).toEpochMilli();
  }
}
