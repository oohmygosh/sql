/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.data.model.ExprValueUtils;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.example.config.QueryExportProperties;
import org.opensearch.sql.executor.ExecutionEngine.Schema;
import org.opensearch.sql.executor.QueryRowConsumer;
import org.opensearch.sql.executor.QueryType;
import org.opensearch.sql.job.*;
import org.opensearch.sql.job.exceptions.QueryJobNotFoundException;

class QueryExportServiceTest {
  @TempDir Path directory;
  private final UnifiedQueryService queries = mock(UnifiedQueryService.class);
  private static final Schema SCHEMA =
      new Schema(List.of(new Schema.Column("value", null, ExprCoreType.STRING)));

  @Test
  void expiresMetadataAndFilesWhileAnOpenDownloadRemainsReadable() throws Exception {
    writes("data");
    try (var jobs = new LocalQueryJobService();
        var exports = service(jobs, Duration.ofMillis(400), 2, 1000)) {
      var id =
          QueryJobId.parse(exports.submit(null, QueryType.SQL, "SELECT 1", Principal.UNSECURED));
      await(() -> exports.status(id, Principal.UNSECURED).state().isTerminal());
      try (var download = exports.download(id, Principal.UNSECURED).stream()) {
        await(
            () -> {
              try {
                exports.status(id, Principal.UNSECURED);
                return false;
              } catch (QueryJobNotFoundException expected) {
                return true;
              }
            });
        await(() -> fileCount() == 0);
        assertThat(new String(download.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("value" + System.lineSeparator() + "data");
      }
    }
    try (var files = Files.list(directory)) {
      assertThat(files.toList()).isEmpty();
    }
  }

  @Test
  void fileLimitFailsTaskAndDeletesPartialOutput() throws Exception {
    writes("too much data");
    try (var jobs = new LocalQueryJobService();
        var exports = service(jobs, Duration.ofMinutes(1), 2, 8)) {
      var id =
          QueryJobId.parse(exports.submit(null, QueryType.SQL, "SELECT 1", Principal.UNSECURED));
      await(() -> exports.status(id, Principal.UNSECURED).state().isTerminal());
      assertThat(exports.status(id, Principal.UNSECURED).state()).isEqualTo(QueryJobState.FAILED);
      assertThat(exports.status(id, Principal.UNSECURED).fileSizeBytes()).isZero();
      assertThat(exports.status(id, Principal.UNSECURED).error()).contains("max-file-bytes");
      assertThat(fileCount()).isZero();
      assertThatThrownBy(() -> exports.download(id, Principal.UNSECURED))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void boundsQueueAndRetainedTasksAndCancelsAtShutdown() throws Exception {
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    when(queries.stream(any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              QueryRowConsumer sink = invocation.getArgument(3);
              sink.schema(SCHEMA);
              running.countDown();
              finish.await(5, TimeUnit.SECONDS);
              return List.of();
            });
    try (var jobs = new LocalQueryJobService()) {
      var exports = service(jobs, Duration.ofMinutes(1), 2, 1000);
      try {
        exports.submit(null, QueryType.SQL, "SELECT 1", Principal.UNSECURED);
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        exports.submit(null, QueryType.SQL, "SELECT 2", Principal.UNSECURED);
        assertThatThrownBy(
                () -> exports.submit(null, QueryType.SQL, "SELECT 3", Principal.UNSECURED))
            .isInstanceOf(RejectedExecutionException.class);
      } finally {
        exports.close();
        finish.countDown();
      }
      await(() -> fileCount() == 0);
      assertThatThrownBy(() -> exports.submit(null, QueryType.SQL, "SELECT 4", Principal.UNSECURED))
          .isInstanceOf(RejectedExecutionException.class);
    }
  }

  private QueryExportService service(
      QueryJobService jobs, Duration retention, int maxTasks, long maxBytes) throws Exception {
    return new QueryExportService(
        jobs, queries, new QueryExportProperties(directory, retention, 1, 1, maxTasks, maxBytes));
  }

  private void writes(String value) {
    when(queries.stream(any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              QueryRowConsumer sink = invocation.getArgument(3);
              sink.schema(SCHEMA);
              sink.row(ExprValueUtils.tupleValue(Map.of("value", value)));
              return List.of();
            });
  }

  private long fileCount() {
    try (var files = Files.walk(directory)) {
      return files.filter(Files::isRegularFile).count();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private void await(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).isLessThan(end);
      Thread.sleep(10);
    }
  }
}
