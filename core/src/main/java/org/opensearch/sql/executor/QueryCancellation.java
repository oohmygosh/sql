/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.executor;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CancellationException;

/** Cooperative cancellation, including a best-effort JDBC interrupt of the active statement. */
public final class QueryCancellation {
  private volatile boolean cancelled;
  private Statement statement;

  public void check() {
    if (cancelled || Thread.currentThread().isInterrupted()) {
      throw new CancellationException("Query cancelled");
    }
  }

  public synchronized void attach(Statement statement) {
    check();
    this.statement = statement;
  }

  public synchronized void detach() {
    statement = null;
  }

  public synchronized void cancel() {
    cancelled = true;
    if (statement != null) {
      try {
        statement.cancel();
      } catch (SQLException e) {
        // Drivers may not support cancellation; the row loop still observes the flag.
        org.apache.logging.log4j.LogManager.getLogger(QueryCancellation.class)
            .debug("JDBC cancellation failed", e);
      }
    }
  }
}
