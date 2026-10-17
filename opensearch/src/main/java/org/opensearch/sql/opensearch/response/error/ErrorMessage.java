/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.response.error;

/** Compatibility envelope for OpenSearch callers. */
public class ErrorMessage extends org.opensearch.sql.common.error.ErrorMessage {
  public ErrorMessage(Throwable exception, int status) {
    super(exception, status);
  }
}
