/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.opensearch.sql.api.QueryExecutionRequest;
import org.opensearch.sql.api.QueryExecutionRequest.ResultWindow;
import org.opensearch.sql.api.QueryExecutionRequest.TimeRange;
import org.opensearch.sql.api.SearchTimeBucketFunction;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.api.UnsupportedHistogramException;
import org.opensearch.sql.example.config.SearchProperties;
import org.opensearch.sql.example.model.ExportRequest;
import org.opensearch.sql.example.model.QueryColumn;
import org.opensearch.sql.example.model.SearchRequest;
import org.opensearch.sql.example.model.SearchResponse;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.Warning;
import org.opensearch.sql.lang.LangSpec;
import org.springframework.stereotype.Service;

/** Coordinates fixed search constraints, result windows and independent histogram execution. */
@Service
public class SearchService {
  private final UnifiedQueryService queries;
  private final SearchProperties properties;
  private final SearchTimeResolver times;
  private final SearchDefaultOrder order;

  public SearchService(
      UnifiedQueryService queries,
      SearchProperties properties,
      SearchTimeResolver times,
      SearchDefaultOrder order) {
    this.queries = queries;
    this.properties = properties;
    this.times = times;
    this.order = order;
  }

  public QueryExecutionRequest exportRequest(ExportRequest request) {
    return new QueryExecutionRequest(
        request.source(),
        request.language(),
        order.apply(request.source(), request.language(), request.query()),
        times.resolve(request.source(), request.timeRange()),
        request.filters(),
        null,
        null);
  }

  public SearchResponse execute(SearchRequest request) {
    TimeRange range = times.resolve(request.source(), request.timeRange());
    var execution =
        new QueryExecutionRequest(
            request.source(),
            request.language(),
            order.apply(request.source(), request.language(), request.query()),
            range,
            request.filters(),
            null,
            null);
    List<Warning> warnings = new ArrayList<>();
    SearchResponse.Rows rows = null;
    SearchResponse.Page page = null;
    if (request.include().contains("rows")) {
      int number =
          request.page() == null || request.page().number() == null ? 1 : request.page().number();
      int size =
          request.page() == null || request.page().size() == null
              ? Math.min(50, properties.maxPageSize())
              : request.page().size();
      long offset = ((long) number - 1) * size;
      if (number < 1
          || size < 1
          || size > properties.maxPageSize()
          || offset > properties.maxOffset())
        throw new IllegalArgumentException("page exceeds configured size or offset limits");
      QueryResponse response =
          queries.execute(execution.withWindow(new ResultWindow((int) offset, size + 1)));
      checkResponse(response);
      warnings.addAll(response.getWarnings());
      var columns = response.getSchema().getColumns();
      var language = LangSpec.fromLanguage(request.language().name());
      var schema =
          columns.stream()
              .map(
                  column ->
                      new QueryColumn(
                          column.getAlias() == null ? column.getName() : column.getAlias(),
                          language.typeName(column.getExprType()).toLowerCase(Locale.ROOT)))
              .toList();
      var values =
          response.getResults().stream()
              .limit(size)
              .map(
                  row ->
                      columns.stream()
                          .map(column -> row.keyValue(column.getName()).value())
                          .toArray())
              .toList();
      rows = new SearchResponse.Rows(schema, values);
      page = new SearchResponse.Page(number, size, response.getResults().size() > size);
    }
    SearchResponse.Histogram histogram = null;
    if (request.include().contains("histogram")) {
      if (rows == null)
        queries.explain(execution); // Validate source fields even when no rows are requested.
      histogram = histogram(execution, request.histogram(), warnings);
    }
    var resolved =
        range == null
            ? null
            : new SearchResponse.ResolvedTimeRange(
                range.field(), range.from(), range.to(), range.timeZone().getId());
    return new SearchResponse(resolved, rows, page, histogram, List.copyOf(warnings));
  }

  private SearchResponse.Histogram histogram(
      QueryExecutionRequest execution, SearchRequest.Histogram input, List<Warning> warnings) {
    TimeRange range = execution.timeRange();
    if (range == null)
      return new SearchResponse.Histogram("unsupported", null, null, null, "时间分布需要有限时间范围");
    String interval = interval(range, input);
    var counts = new LinkedHashMap<Instant, Long>();
    for (Instant bucket = SearchTimeBucketFunction.floor(range.from(), interval, range.timeZone());
        bucket.isBefore(range.to());
        bucket = next(bucket, interval, range.timeZone())) {
      if (counts.size() >= properties.maxBuckets())
        return new SearchResponse.Histogram(
            "error", interval, range.timeZone().getId(), null, "时间分布桶数超过限制，请缩小时间范围或增大粒度");
      counts.put(bucket, 0L);
    }
    try {
      QueryResponse response =
          queries.execute(
              execution.withHistogram(
                  new QueryExecutionRequest.Histogram(range.field(), interval, range.timeZone())));
      checkResponse(response);
      if (response.getWarnings().stream()
          .anyMatch(warning -> Warning.TYPE_PARTIAL_RESULT.equals(warning.getType())))
        throw new IllegalStateException("Histogram returned partial results");
      warnings.addAll(response.getWarnings());
      for (var row : response.getResults()) {
        Instant bucket = row.keyValue("__search_bucket").timestampValue();
        if (!counts.containsKey(bucket))
          throw new IllegalStateException("Unexpected histogram bucket boundary");
        counts.merge(bucket, row.keyValue("__search_count").longValue(), Math::addExact);
      }
      var buckets =
          counts.entrySet().stream()
              .map(
                  entry ->
                      new SearchResponse.Bucket(
                          entry.getKey(),
                          next(entry.getKey(), interval, range.timeZone()),
                          entry.getValue()))
              .toList();
      return new SearchResponse.Histogram("ok", interval, range.timeZone().getId(), buckets, null);
    } catch (UnsupportedHistogramException e) {
      return new SearchResponse.Histogram("unsupported", null, null, null, e.getMessage());
    } catch (RuntimeException e) {
      return new SearchResponse.Histogram(
          "error", interval, range.timeZone().getId(), null, "时间分布执行失败");
    }
  }

  private static void checkResponse(QueryResponse response) {
    if (response.getError() != null)
      throw new IllegalStateException("Query execution failed", response.getError());
  }

  private static String interval(TimeRange range, SearchRequest.Histogram input) {
    String interval = input == null || input.interval() == null ? "auto" : input.interval();
    return switch (interval) {
      case "auto" -> {
        Duration duration = Duration.between(range.from(), range.to());
        yield duration.compareTo(Duration.ofHours(1)) <= 0
            ? "1m"
            : duration.compareTo(Duration.ofDays(1)) <= 0 ? "1h" : "1d";
      }
      case "1m", "minute" -> "1m";
      case "1h", "hour" -> "1h";
      case "1d", "day" -> "1d";
      default ->
          throw new IllegalArgumentException("histogram.interval must be auto, 1m, 1h or 1d");
    };
  }

  static Instant next(Instant bucket, String interval, ZoneId zone) {
    var local = bucket.atZone(zone);
    return switch (interval) {
      case "1m" -> local.plusMinutes(1).toInstant();
      case "1h" -> local.plusHours(1).toInstant();
      case "1d" -> local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
      default -> throw new IllegalArgumentException("Invalid histogram interval");
    };
  }
}
