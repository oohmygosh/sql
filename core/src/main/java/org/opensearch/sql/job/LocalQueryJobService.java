/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.job;

import java.lang.ref.WeakReference;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Single-process host for embedded ES/CH applications. No OpenSearch runtime is required. */
public final class LocalQueryJobService implements QueryJobService, AutoCloseable {
  private final InMemoryQueryJobStore store = new InMemoryQueryJobStore();
  private final ScheduledExecutorService retention =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "query-job-retention");
            thread.setDaemon(true);
            return thread;
          });
  private final DefaultQueryJobService delegate;
  private boolean closed;

  public LocalQueryJobService() {
    String nodeId = UUID.randomUUID().toString();
    delegate =
        new DefaultQueryJobService(
            store,
            () -> nodeId,
            Clock.systemUTC(),
            (job, ttl) ->
                job.onTerminal(
                    () -> {
                      QueryJobId id = job.id();
                      var retained = new WeakReference<>(job);
                      try {
                        retention.schedule(
                            () -> {
                              QueryJob current = retained.get();
                              if (current != null) store.remove(id, current);
                            },
                            ttl.toMillis(),
                            TimeUnit.MILLISECONDS);
                      } catch (RejectedExecutionException e) {
                        store.remove(job.id(), job);
                      }
                    }));
  }

  @Override
  public synchronized CompletionStage<QueryResult> submit(
      QueryRunner runner, Principal owner, Duration wait, Duration keepAlive) {
    ensureOpen();
    return delegate.submit(runner, owner, wait, keepAlive);
  }

  @Override
  public synchronized QueryJobId submitAsync(
      QueryRunner runner, Principal owner, Duration keepAlive) {
    ensureOpen();
    return delegate.submitAsync(runner, owner, keepAlive);
  }

  @Override
  public QueryJobStatus get(QueryJobId id, Principal caller) {
    return delegate.get(id, caller);
  }

  @Override
  public QueryJobStatus cancel(QueryJobId id, Principal caller) {
    return delegate.cancel(id, caller);
  }

  @Override
  public QueryJobStatus delete(QueryJobId id, Principal caller) {
    return delegate.delete(id, caller);
  }

  private void ensureOpen() {
    if (closed) throw new IllegalStateException("Query job service is closed");
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    store.close();
    retention.shutdownNow();
  }
}
