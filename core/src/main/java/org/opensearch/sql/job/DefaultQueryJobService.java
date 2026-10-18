/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.job;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.opensearch.sql.job.exceptions.QueryJobForbiddenException;
import org.opensearch.sql.job.exceptions.QueryJobNotFoundException;

/** Runtime-neutral lifecycle service; hosting adapters supply identity and retention scheduling. */
public final class DefaultQueryJobService implements QueryJobService {

  private final QueryJobStore store;
  private final Supplier<String> nodeId;
  private final Clock clock;
  private final BiConsumer<QueryJob, Duration> retentionPolicy;
  private final Function<Throwable, Map<String, Object>> failureRenderer;

  public DefaultQueryJobService(
      QueryJobStore store,
      Supplier<String> nodeId,
      Clock clock,
      BiConsumer<QueryJob, Duration> retentionPolicy) {
    this(store, nodeId, clock, retentionPolicy, null);
  }

  public DefaultQueryJobService(
      QueryJobStore store,
      Supplier<String> nodeId,
      Clock clock,
      BiConsumer<QueryJob, Duration> retentionPolicy,
      Function<Throwable, Map<String, Object>> failureRenderer) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.retentionPolicy = retentionPolicy;
    this.failureRenderer = failureRenderer;
  }

  @Override
  public CompletionStage<QueryResult> submit(
      QueryRunner runner, Principal submitter, Duration waitForCompletion, Duration keepAlive) {
    validate(runner, submitter, waitForCompletion, keepAlive);
    QueryJob job = publish(runner, submitter);
    job.startRunner();
    return job.await(waitForCompletion)
        .whenComplete(
            (result, error) -> {
              if (error == null && result instanceof QueryResult.Running) {
                if (retentionPolicy != null) {
                  retentionPolicy.accept(job, keepAlive);
                }
              } else {
                store.remove(job.id(), job);
              }
            });
  }

  @Override
  public QueryJobId submitAsync(QueryRunner runner, Principal submitter, Duration keepAlive) {
    validate(runner, submitter, Duration.ZERO, keepAlive);
    QueryJob job = publish(runner, submitter);
    if (retentionPolicy != null) retentionPolicy.accept(job, keepAlive);
    job.startRunner();
    return job.id();
  }

  private static void validate(
      QueryRunner runner, Principal submitter, Duration waitForCompletion, Duration keepAlive) {
    Objects.requireNonNull(runner, "runner must not be null");
    Objects.requireNonNull(submitter, "submitter must not be null");
    Objects.requireNonNull(waitForCompletion, "waitForCompletion must not be null");
    Objects.requireNonNull(keepAlive, "keepAlive must not be null");
    if (waitForCompletion.isNegative()) {
      throw new IllegalArgumentException("waitForCompletion must not be negative");
    }
    if (!keepAlive.isPositive()) {
      throw new IllegalArgumentException("keepAlive must be positive");
    }
  }

  @Override
  public QueryJobStatus get(QueryJobId id, Principal caller) {
    QueryJob job = requireJob(id);
    authorize(job, caller);
    return job.status();
  }

  @Override
  public QueryJobStatus cancel(QueryJobId id, Principal caller) {
    QueryJob job = requireJob(id);
    authorize(job, caller);
    job.cancel();
    return job.status();
  }

  @Override
  public QueryJobStatus delete(QueryJobId id, Principal caller) {
    QueryJob job = requireJob(id);
    authorize(job, caller);
    if (!store.remove(id, job)) {
      throw new QueryJobNotFoundException(id);
    }
    job.cancel();
    return job.status();
  }

  /**
   * Mints a fresh {@link QueryJobId}, constructs the {@link QueryJob}, and inserts it into the
   * store. Retries on the (in practice unreachable) UUID collision so the store's invariant "one
   * job per id" holds without callers seeing partial state.
   *
   * @param runner engine adapter that will produce the result
   * @param owner caller identity retained with the job for later authorization
   * @return the registered job, ready to be started
   */
  private QueryJob publish(QueryRunner runner, Principal owner) {
    while (true) {
      QueryJobId id = QueryJobId.create(nodeId.get());
      QueryJob job = new QueryJob(id, owner, runner, clock, failureRenderer);
      if (store.register(job) == null) {
        return job;
      }
    }
  }

  /**
   * Resolves {@code id} against the store or raises {@link QueryJobNotFoundException}. Extracted
   * from {@link #get} and {@link #cancel} so both surface the same "not-found" error uniformly.
   *
   * @param id id supplied by the caller; must be non-{@code null}
   * @return the job registered under {@code id}
   * @throws QueryJobNotFoundException when the id does not resolve on this node
   */
  private QueryJob requireJob(QueryJobId id) {
    Objects.requireNonNull(id, "id must not be null");
    return store.find(id).orElseThrow(() -> new QueryJobNotFoundException(id));
  }

  /**
   * Denies access when {@code caller} is not allowed to observe or mutate {@code job}. The
   * exception message intentionally omits the owner's identity — see {@link
   * QueryJobForbiddenException}.
   *
   * @param job job the caller is trying to reach
   * @param caller identity resolved from the current request
   * @throws QueryJobForbiddenException when {@code caller} is not the owner
   */
  private static void authorize(QueryJob job, Principal caller) {
    Objects.requireNonNull(caller, "caller must not be null");
    if (!job.owner().allows(caller)) {
      throw new QueryJobForbiddenException();
    }
  }
}
