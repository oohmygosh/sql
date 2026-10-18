/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.example.config.QueryExportProperties;
import org.opensearch.sql.example.model.ExportDownload;
import org.opensearch.sql.example.model.ExportRequest;
import org.opensearch.sql.example.model.ExportTaskStatus;
import org.opensearch.sql.job.Principal;
import org.opensearch.sql.job.QueryFailure;
import org.opensearch.sql.job.QueryJobId;
import org.opensearch.sql.job.QueryJobService;
import org.opensearch.sql.job.QueryJobState;
import org.opensearch.sql.job.exceptions.QueryJobNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Owns export files and admission; QueryJobService alone owns lifecycle state and authorization.
 */
@Service
@EnableConfigurationProperties(QueryExportProperties.class)
public final class QueryExportService implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(QueryExportService.class);
  private final QueryJobService jobs;
  private final UnifiedQueryService queries;
  private final SearchService searches;
  private final QueryExportProperties properties;
  private final Path directory;
  private final ThreadPoolExecutor workers;
  private final ScheduledExecutorService cleanup;
  private final Map<QueryJobId, Export> exports = new HashMap<>();
  private boolean closed;

  public QueryExportService(
      QueryJobService jobs,
      UnifiedQueryService queries,
      QueryExportProperties properties,
      SearchService searches)
      throws IOException {
    this.jobs = jobs;
    this.queries = queries;
    this.searches = searches;
    this.properties = properties;
    Files.createDirectories(properties.directory());
    directory = Files.createTempDirectory(properties.directory(), "session-");
    workers =
        new ThreadPoolExecutor(
            properties.workers(),
            properties.workers(),
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(properties.queueCapacity()),
            runnable -> {
              Thread thread = new Thread(runnable, "query-export");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    cleanup =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "query-export-cleanup");
              thread.setDaemon(true);
              return thread;
            });
    cleanup.scheduleWithFixedDelay(this::removeExpired, 1, 1, TimeUnit.SECONDS);
  }

  /** Writes rows directly to the bounded export file, then streams the completed CSV. */
  public ExportDownload export(ExportRequest request) {
    QueryJobId id = QueryJobId.parse(submit(request, Principal.UNSECURED));
    try {
      Export export;
      synchronized (this) {
        export = requireExport(id);
      }
      export.runner().awaitCompletion();
      ExportDownload download = download(id, Principal.UNSECURED);
      return new ExportDownload(
          new FilterInputStream(download.stream()) {
            @Override
            public void close() throws IOException {
              try {
                super.close();
              } finally {
                remove(id);
              }
            }
          },
          download.size());
    } catch (IOException | RuntimeException e) {
      try {
        remove(id);
      } catch (IOException cleanupError) {
        e.addSuppressed(cleanupError);
      }
      if (e instanceof RuntimeException failure) throw failure;
      throw new java.io.UncheckedIOException((IOException) e);
    }
  }

  public synchronized String submit(ExportRequest request, Principal owner) {
    if (closed) throw new RejectedExecutionException("Export service is closed");
    queries.validateSource(request.source());
    var execution = searches.exportRequest(request);
    removeExpired();
    if (exports.size() >= properties.maxTasks()
        || (workers.getActiveCount() >= properties.workers()
            && workers.getQueue().remainingCapacity() == 0)) {
      throw new RejectedExecutionException("Export capacity reached; retry later");
    }
    var runner =
        new QueryExportRunner(queries, execution, workers, directory, properties.maxFileBytes());
    QueryJobId id = jobs.submitAsync(runner, owner, properties.keepAlive());
    if (runner.rejected()) {
      jobs.delete(id, owner);
      throw new RejectedExecutionException("Export capacity reached; retry later");
    }
    exports.put(id, new Export(runner, owner));
    return id.encode();
  }

  private synchronized void remove(QueryJobId id) throws IOException {
    Export export = exports.remove(id);
    if (export == null) return;
    try {
      jobs.delete(id, export.owner());
    } catch (QueryJobNotFoundException ignored) {
      /* Retention may already have removed metadata. */
    }
    export.runner().dispose();
  }

  public synchronized ExportTaskStatus status(QueryJobId id, Principal caller) {
    var status = jobs.get(id, caller);
    Export export = requireExport(id);
    var progress = export.runner().progress();
    return new ExportTaskStatus(
        id.encode(),
        status.state(),
        progress.exportedRows(),
        progress.fileSizeBytes(),
        status.submittedAtMillis(),
        status.completedAtMillis().isPresent() ? status.completedAtMillis().getAsLong() : null,
        status.failure().map(QueryFailure::reason).orElse(null));
  }

  public synchronized ExportDownload download(QueryJobId id, Principal caller) throws IOException {
    ExportTaskStatus status = status(id, caller);
    if (status.state() != QueryJobState.SUCCEEDED) {
      throw new IllegalStateException("Export is not complete: " + status.state());
    }
    // Open while holding the same monitor as eviction. An already-open download survives unlink.
    return new ExportDownload(requireExport(id).runner().openFile(), status.fileSizeBytes());
  }

  private Export requireExport(QueryJobId id) {
    Export export = exports.get(id);
    if (export == null) throw new QueryJobNotFoundException(id);
    return export;
  }

  private synchronized void removeExpired() {
    var iterator = exports.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      try {
        jobs.get(entry.getKey(), entry.getValue().owner());
      } catch (QueryJobNotFoundException expired) {
        try {
          entry.getValue().runner().dispose();
          iterator.remove();
        } catch (IOException e) {
          LOG.warn("Cannot remove expired export {}", entry.getKey().encode(), e);
        }
      }
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    closed = true;
    cleanup.shutdownNow();
    IOException failure = null;
    for (var entry : exports.entrySet()) {
      try {
        jobs.delete(entry.getKey(), entry.getValue().owner());
      } catch (QueryJobNotFoundException ignored) {
        // Retention may already have removed metadata.
      }
      try {
        entry.getValue().runner().dispose();
      } catch (IOException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
    }
    workers.shutdownNow();
    exports.clear();
    try {
      Files.deleteIfExists(directory);
    } catch (IOException e) {
      if (failure == null) failure = e;
      else failure.addSuppressed(e);
    }
    if (failure != null) throw failure;
  }

  private record Export(QueryExportRunner runner, Principal owner) {}
}
