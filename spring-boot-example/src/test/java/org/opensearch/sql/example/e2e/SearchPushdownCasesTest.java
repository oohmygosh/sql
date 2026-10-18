/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.opensearch.sql.example.e2e.SearchPushdownCases.Expectation;

class SearchPushdownCasesTest {
  @Test
  void acceptedCorpusRetainsItsCasesAndBothPositiveAndNegativePlanContracts() {
    var cases = SearchPushdownCases.cases("isolated-test-index");
    assertEquals(57, cases.size());
    assertEquals(57, cases.stream().map(SearchPushdownCases.Case::id).distinct().count());
    assertEquals(
        Set.of(Expectation.values()),
        cases.stream().map(SearchPushdownCases.Case::expectation).collect(Collectors.toSet()));
    assertEquals(1, cases.stream().filter(c -> c.expectation() == Expectation.REJECT).count());
    assertEquals(1, cases.stream().filter(SearchPushdownCases.Case::histogram).count());
    for (var test : cases) {
      assertTrue(test.query().contains("isolated-test-index"), test.id());
      assertEquals("logs", test.request().source(), test.id());
      assertNotNull(test.request().timeRange(), test.id());
    }
  }
}
