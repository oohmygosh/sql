/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenSearchQueryBackendTest {
  @Test
  void standaloneBackendOwnsItsTransportAndUsesExistingSettings() throws Exception {
    try (var backend =
        OpenSearchQueryBackend.connect("os", "http://localhost:19200", null, null, Map.of())) {
      assertEquals("opensearch", backend.type());
      assertEquals("os", backend.name());
      assertNotNull(backend.executionEngine());
    }
  }
}
