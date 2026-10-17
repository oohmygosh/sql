/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.common.error;

/** Optional backend error adapter. Returns null when the exception is not owned by this backend. */
public interface ErrorMessageProvider {
  ErrorMessage create(Throwable error, int status);
}
