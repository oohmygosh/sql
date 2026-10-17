/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api;

import static org.junit.Assert.*;

import java.time.Instant;
import org.junit.Test;
import org.opensearch.sql.api.corpus.BackendQueryCorpus;
import org.opensearch.sql.executor.QueryType;

/** Golden results for the shared defects exposed by real backend differential testing. */
public class NativeBackendSemanticsTest {
  @Test
  public void crossJoinDoesNotInferEquijoinFromCommonColumnNames() {
    var result =
        BackendQueryCorpus.referenceService()
            .execute(
                QueryType.SQL,
                "SELECT e.id AS id,d.owner AS owner FROM events e CROSS JOIN departments d WHERE"
                    + " e.id<=2 ORDER BY id,owner");
    assertEquals(8, result.getResults().size());
    assertEquals("Nobody", result.getResults().get(3).tupleValue().get("owner").stringValue());
  }

  @Test
  public void nativeTimestampFieldsWorkWithDateFunctions() {
    var result =
        BackendQueryCorpus.referenceService()
            .execute(
                QueryType.PPL,
                "source=events | eval y=year(ts),h=hour(ts) | fields id,y,h | sort id");
    for (int i = 0; i < 12; i++) {
      var row = result.getResults().get(i).tupleValue();
      assertEquals(2025, row.get("y").integerValue().intValue());
      assertEquals(i, row.get("h").integerValue().intValue());
    }
  }

  @Test
  public void datetimeNormalizationPreservesIntermediateProjectionTypesAndValues() {
    var service = BackendQueryCorpus.referenceService();
    var result =
        service.execute(
            QueryType.PPL, "source=events | bin ts span=2h | stats count() as n by ts | sort ts");
    assertEquals(6, result.getResults().size());
    for (int i = 0; i < 6; i++) {
      var row = result.getResults().get(i).tupleValue();
      assertEquals(2L, row.get("n").longValue().longValue());
      assertEquals(
          Instant.parse("2025-01-01T00:00:00Z").toEpochMilli() + i * 7200000L,
          row.get("ts").timestampValue().toEpochMilli());
    }
    var nested =
        service.execute(
            QueryType.PPL,
            "source=events | eval d=date(ts),midnight=timestamp(d) | fields midnight | head 1");
    assertEquals(
        Instant.parse("2025-01-01T00:00:00Z"),
        nested.getResults().getFirst().tupleValue().get("midnight").timestampValue());
  }

  @Test
  public void normalizedTimestampColumnsCanBeFilteredAndJoined() {
    var service = BackendQueryCorpus.referenceService();
    var filtered =
        service.execute(
            QueryType.PPL,
            "source=events | bin ts span=2h | where ts=timestamp('2025-01-01 00:00:00') | stats"
                + " count() as n");
    assertEquals(
        2L, filtered.getResults().getFirst().tupleValue().get("n").longValue().longValue());
    var joined =
        service.execute(
            QueryType.PPL,
            "source=events | bin ts span=2h | inner join left=e right=d on e.ts=d.ts [source=events"
                + " | bin ts span=2h] | stats count() as n");
    assertEquals(24L, joined.getResults().getFirst().tupleValue().get("n").longValue().longValue());
  }
}
