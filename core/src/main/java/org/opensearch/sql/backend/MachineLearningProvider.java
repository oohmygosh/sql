/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.backend;

import java.util.Map;
import java.util.Set;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.rel.type.RelDataType;

/** Backend-native ML over the rows produced by the preceding query operators. */
public interface MachineLearningProvider {
  /** Argument names accepted by this backend, for query editor completion. */
  default Set<String> argumentNames() {
    return Set.of();
  }

  /** Validates arguments and resolves output types without executing a model or reading data. */
  Operation plan(Map<String, Object> arguments, RelDataType inputType);

  /** Immutable, query-scoped operation. Each execution owns its resources and model state. */
  interface Operation {
    RelDataType outputType();

    /**
     * Returns a lazy enumerable; closing its enumerator must also close the input and resources.
     */
    Enumerable<Object[]> execute(Enumerable<Object[]> input, DataContext context);
  }
}
