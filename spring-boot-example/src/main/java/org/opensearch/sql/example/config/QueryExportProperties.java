/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Local export storage and admission limits. */
@ConfigurationProperties("unified-query.export")
public record QueryExportProperties(
    Path directory,
    Duration keepAlive,
    Integer workers,
    Integer queueCapacity,
    Integer maxTasks,
    Long maxFileBytes) {
  public QueryExportProperties {
    directory =
        directory == null
            ? Path.of(System.getProperty("java.io.tmpdir"), "query-exports")
            : directory;
    keepAlive = keepAlive == null ? Duration.ofHours(1) : keepAlive;
    workers = workers == null ? 2 : workers;
    queueCapacity = queueCapacity == null ? 16 : queueCapacity;
    maxTasks = maxTasks == null ? 100 : maxTasks;
    maxFileBytes = maxFileBytes == null ? 10L * 1024 * 1024 * 1024 : maxFileBytes;
    if (keepAlive.toMillis() < 1
        || workers < 1
        || queueCapacity < 1
        || maxTasks < 1
        || maxFileBytes < 1) {
      throw new IllegalArgumentException("Export retention and capacity limits must be positive");
    }
  }
}
