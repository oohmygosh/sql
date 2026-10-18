/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.executor;

import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.executor.ExecutionEngine.Schema;

/** Synchronous sink: each callback finishes before another row is read (natural backpressure). */
public interface QueryRowConsumer {
  void schema(Schema schema) throws Exception;

  void row(ExprValue row) throws Exception;
}
