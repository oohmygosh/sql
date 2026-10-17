/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.common.error;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.ServiceLoader;
import java.util.Set;

public final class ErrorMessageFactory {
  private ErrorMessageFactory() {}

  public static ErrorMessage createErrorMessage(Throwable error, int status) {
    if (error instanceof ErrorReport) return new ErrorMessage(error, status);
    for (ErrorMessageProvider provider : ServiceLoader.load(ErrorMessageProvider.class)) {
      ErrorMessage message = provider.create(error, status);
      if (message != null) return message;
    }
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    while (seen.add(error) && error.getCause() != null && !seen.contains(error.getCause())) {
      error = error.getCause();
    }
    if (error.getCause() == null && error.getSuppressed().length > 0)
      error = error.getSuppressed()[0];
    return new ErrorMessage(error, status);
  }
}
