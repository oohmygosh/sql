/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.job;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.opensearch.sql.job.exceptions.QueryJobForbiddenException;
import org.opensearch.sql.job.exceptions.QueryJobNotFoundException;

class LocalQueryJobServiceTest {
  private static final Principal OWNER = new Principal("owner", null, List.of());

  @Test
  void retainsImmediateSuccessAndFailureAndEnforcesOwnership() {
    try (var jobs = new LocalQueryJobService()) {
      QueryRunner success = mock(QueryRunner.class);
      when(success.run())
          .thenReturn(CompletableFuture.completedFuture(new QueryResult.Export(10, 20, 1)));
      QueryJobId id = jobs.submitAsync(success, OWNER, Duration.ofMinutes(1));
      assertEquals(QueryJobState.SUCCEEDED, jobs.get(id, OWNER).state());
      assertThrows(QueryJobForbiddenException.class, () -> jobs.get(id, Principal.UNSECURED));
      assertThrows(QueryJobForbiddenException.class, () -> jobs.delete(id, Principal.UNSECURED));
      assertEquals(QueryJobState.SUCCEEDED, jobs.delete(id, OWNER).state());
      assertThrows(QueryJobNotFoundException.class, () -> jobs.get(id, OWNER));

      QueryRunner failure = mock(QueryRunner.class);
      when(failure.run()).thenThrow(new IllegalArgumentException("invalid query"));
      var failedId = jobs.submitAsync(failure, OWNER, Duration.ofMinutes(1));
      assertEquals("invalid query", jobs.get(failedId, OWNER).failure().orElseThrow().reason());
    }
  }

  @Test
  void retentionStartsAtCompletionAndShutdownCancelsWork() throws Exception {
    var jobs = new LocalQueryJobService();
    QueryRunner runner = mock(QueryRunner.class);
    CompletableFuture<QueryResult> completion = new CompletableFuture<>();
    when(runner.run()).thenReturn(completion);
    var id = jobs.submitAsync(runner, OWNER, Duration.ofMillis(50));
    Thread.sleep(80);
    assertEquals(QueryJobState.RUNNING, jobs.get(id, OWNER).state());
    completion.complete(new QueryResult.Export(0, 0, 0));
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (true) {
      try {
        jobs.get(id, OWNER);
      } catch (QueryJobNotFoundException expired) {
        break;
      }
      assertTrue(System.nanoTime() < deadline, "job did not expire");
      Thread.sleep(10);
    }
    QueryRunner pending = mock(QueryRunner.class);
    when(pending.run()).thenReturn(new CompletableFuture<>());
    jobs.submitAsync(pending, OWNER, Duration.ofMinutes(1));
    jobs.close();
    verify(pending).cancel();
    assertThrows(
        IllegalStateException.class, () -> jobs.submitAsync(pending, OWNER, Duration.ofMinutes(1)));
  }
}
