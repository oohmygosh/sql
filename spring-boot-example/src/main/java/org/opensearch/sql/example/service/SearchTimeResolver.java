/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import org.opensearch.sql.api.QueryExecutionRequest.TimeRange;
import org.opensearch.sql.example.config.SearchProperties;
import org.opensearch.sql.example.model.SearchRequest;
import org.opensearch.sql.spring.UnifiedQueryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Resolves both endpoints from a single clock instant, including calendar relative units. */
@Component
@EnableConfigurationProperties(SearchProperties.class)
public class SearchTimeResolver {
  private static final Pattern RELATIVE =
      Pattern.compile("now(?:([+-])(\\d+)([smhdwM]))?(?:/([mhd]))?");
  private final SearchProperties properties;
  private final String defaultSource;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public SearchTimeResolver(SearchProperties properties, UnifiedQueryProperties sources) {
    this(properties, sources.getDefaultDataSource(), Clock.systemUTC());
  }

  SearchTimeResolver(SearchProperties properties, String defaultSource, Clock clock) {
    this.properties = properties;
    this.defaultSource = defaultSource;
    this.clock = clock;
  }

  public TimeRange resolve(String source, SearchRequest.TimeRange input) {
    if (input == null) return null;
    String field = input.field();
    if (field == null || field.isBlank()) {
      String selectedSource = source == null ? defaultSource : source;
      field = selectedSource == null ? null : properties.timeFields().get(selectedSource);
    }
    if (field == null || field.isBlank())
      throw new IllegalArgumentException("timeRange.field is required for this source");
    try {
      ZoneId zone =
          ZoneId.of(input.timeZone() == null ? properties.defaultTimeZone() : input.timeZone());
      Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
      return new TimeRange(
          field, parse(input.from(), now, zone), parse(input.to(), now, zone), zone);
    } catch (java.time.DateTimeException | ArithmeticException e) {
      throw new IllegalArgumentException("Invalid time range: " + e.getMessage(), e);
    }
  }

  static Instant parse(String value, Instant now, ZoneId zone) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("timeRange.from and to are required");
    var match = RELATIVE.matcher(value);
    if (match.matches()) {
      ZonedDateTime resolved = now.atZone(zone);
      if (match.group(1) != null) {
        long amount = Long.parseLong(match.group(2));
        if (match.group(1).equals("-")) amount = -amount;
        resolved =
            switch (match.group(3)) {
              case "s" -> resolved.plusSeconds(amount);
              case "m" -> resolved.plusMinutes(amount);
              case "h" -> resolved.plusHours(amount);
              case "d" -> resolved.plusDays(amount);
              case "w" -> resolved.plusWeeks(amount);
              case "M" -> resolved.plusMonths(amount);
              default -> throw new IllegalArgumentException("Invalid relative time");
            };
      }
      if (match.group(4) != null)
        resolved =
            switch (match.group(4)) {
              case "m" -> resolved.truncatedTo(ChronoUnit.MINUTES);
              case "h" -> resolved.truncatedTo(ChronoUnit.HOURS);
              case "d" -> resolved.toLocalDate().atStartOfDay(zone);
              default -> throw new IllegalArgumentException("Invalid time rounding");
            };
      return resolved.toInstant();
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException ignored) {
      try {
        return LocalDateTime.parse(value).atZone(zone).toInstant();
      } catch (DateTimeParseException notDateTime) {
        return LocalDate.parse(value).atStartOfDay(zone).toInstant();
      }
    }
  }
}
