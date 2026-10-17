/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.response.error;

import org.opensearch.OpenSearchException;
import org.opensearch.sql.common.error.ErrorMessageProvider;

public final class OpenSearchErrorMessageProvider implements ErrorMessageProvider {
  @Override
  public ErrorMessage create(Throwable error, int status) {
    return ErrorMessageFactory.unwrapCause(error) instanceof OpenSearchException
        ? ErrorMessageFactory.createErrorMessage(error, status)
        : null;
  }
}
