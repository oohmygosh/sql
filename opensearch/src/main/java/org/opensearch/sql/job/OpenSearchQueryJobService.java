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
import java.util.function.Function;
import org.opensearch.cluster.service.ClusterService;

/** OpenSearch hosting adapter for the shared job lifecycle, node identity and retention policy. */
public final class OpenSearchQueryJobService implements QueryJobService {

  private final DefaultQueryJobService delegate;

  public OpenSearchQueryJobService(
      QueryJobStore store,
      ClusterService clusterService,
      Clock clock,
      RetentionPolicy retentionPolicy) {
    this(store, clusterService, clock, retentionPolicy, null);
  }

  public OpenSearchQueryJobService(
      QueryJobStore store,
      ClusterService clusterService,
      Clock clock,
      RetentionPolicy retentionPolicy,
      Function<Throwable, Map<String, Object>> failureRenderer) {
    Objects.requireNonNull(clusterService, "clusterService must not be null");
    delegate =
        new DefaultQueryJobService(
            store,
            () -> clusterService.localNode().getId(),
            clock,
            retentionPolicy == null ? null : retentionPolicy::arm,
            failureRenderer);
  }

  @Override
  public CompletionStage<QueryResult> submit(
      QueryRunner runner, Principal submitter, Duration waitForCompletion, Duration keepAlive) {
    return delegate.submit(runner, submitter, waitForCompletion, keepAlive);
  }

  @Override
  public QueryJobId submitAsync(QueryRunner runner, Principal submitter, Duration keepAlive) {
    return delegate.submitAsync(runner, submitter, keepAlive);
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
}
