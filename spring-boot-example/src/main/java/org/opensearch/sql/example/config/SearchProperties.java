/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.config;

import java.time.ZoneId;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounds interactive windows and calendar bucket allocation. */
@ConfigurationProperties("unified-query.search")
public record SearchProperties(
    Integer maxPageSize,
    Integer maxOffset,
    Integer maxBuckets,
    String defaultTimeZone,
    Map<String, String> timeFields) {
  public SearchProperties {
    maxPageSize = maxPageSize == null ? 1000 : maxPageSize;
    maxOffset = maxOffset == null ? 100000 : maxOffset;
    maxBuckets = maxBuckets == null ? 2000 : maxBuckets;
    defaultTimeZone = defaultTimeZone == null ? "Asia/Shanghai" : defaultTimeZone;
    timeFields = timeFields == null ? Map.of() : Map.copyOf(timeFields);
    ZoneId.of(defaultTimeZone);
    if (maxPageSize < 1
        || maxPageSize == Integer.MAX_VALUE
        || maxOffset < 0
        || maxBuckets < 1
        || (long) maxOffset + maxPageSize + 1 > Integer.MAX_VALUE)
      throw new IllegalArgumentException("Invalid search window or bucket limits");
  }
}
