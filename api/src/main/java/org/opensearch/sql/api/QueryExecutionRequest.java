/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.opensearch.sql.executor.QueryType;

/** Immutable execution input. External constraints always refer to source fields. */
public record QueryExecutionRequest(
    String source,
    QueryType language,
    String query,
    TimeRange timeRange,
    List<FieldFilter> filters,
    ResultWindow window,
    Histogram histogram) {
  public QueryExecutionRequest {
    Objects.requireNonNull(language, "language");
    if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
    filters = filters == null ? List.of() : List.copyOf(filters);
    if (window != null && histogram != null)
      throw new IllegalArgumentException("Histogram requests cannot include a result window");
    if (histogram != null && timeRange == null)
      throw new IllegalArgumentException("Histogram requires a finite time range");
    if (histogram != null && !histogram.field().equals(timeRange.field()))
      throw new IllegalArgumentException("Histogram must use the time range field");
  }

  public QueryExecutionRequest(String source, QueryType language, String query) {
    this(source, language, query, null, List.of(), null, null);
  }

  public QueryExecutionRequest withWindow(ResultWindow value) {
    return new QueryExecutionRequest(source, language, query, timeRange, filters, value, histogram);
  }

  public QueryExecutionRequest withHistogram(Histogram value) {
    return new QueryExecutionRequest(source, language, query, timeRange, filters, null, value);
  }

  public record TimeRange(String field, Instant from, Instant to, ZoneId timeZone) {
    public TimeRange {
      requireField(field);
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      Objects.requireNonNull(timeZone, "timeZone");
      requireMillisecondPrecision(from);
      requireMillisecondPrecision(to);
      if (!from.isBefore(to)) throw new IllegalArgumentException("from must precede to");
    }
  }

  public record FieldFilter(String field, String operator, Object value) {
    public FieldFilter {
      requireField(field);
      if (operator == null) throw new IllegalArgumentException("filter operator is required");
      operator = operator.toLowerCase(Locale.ROOT);
      if (!List.of("eq", "ne", "gt", "gte", "lt", "lte", "in", "is_null", "is_not_null", "contains")
          .contains(operator))
        throw new IllegalArgumentException("Unknown filter operator: " + operator);
      if (operator.equals("in")) {
        if (!(value instanceof List<?> values))
          throw new IllegalArgumentException("in requires an array value");
        // A private immutable copy also freezes requests submitted to asynchronous export jobs.
        value = Collections.unmodifiableList(new ArrayList<>(values));
      }
      if (!operator.equals("is_null") && !operator.equals("is_not_null") && value == null)
        throw new IllegalArgumentException("filter value is required; use is_null for null values");
    }
  }

  /** A window on the complete user result; callers can request one sentinel row for hasNext. */
  public record ResultWindow(int offset, int limit) {
    public ResultWindow {
      if (offset < 0 || limit < 1) throw new IllegalArgumentException("Invalid result window");
      if ((long) offset + limit > Integer.MAX_VALUE)
        throw new IllegalArgumentException("Result window exceeds supported offset range");
    }
  }

  public record Histogram(String field, String interval, ZoneId timeZone) {
    public Histogram {
      requireField(field);
      Objects.requireNonNull(timeZone, "timeZone");
      if (!List.of("1m", "1h", "1d").contains(interval))
        throw new IllegalArgumentException("Histogram interval must be 1m, 1h or 1d");
    }
  }

  static void requireMillisecondPrecision(Instant value) {
    if (value.getNano() % 1_000_000 != 0)
      throw new IllegalArgumentException(
          "Structured time constraints support millisecond precision");
    try {
      value.toEpochMilli();
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          "Timestamp is outside the supported epoch range", overflow);
    }
  }

  private static void requireField(String field) {
    if (field == null || field.isBlank()) throw new IllegalArgumentException("field is required");
  }
}
