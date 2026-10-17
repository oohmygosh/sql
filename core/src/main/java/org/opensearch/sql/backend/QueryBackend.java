/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.backend;

import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.opensearch.sql.executor.ExecutionEngine;

/** A data source's schema, planning rules and executor must be selected together. */
public interface QueryBackend extends AutoCloseable {
  String name();

  String type();

  Schema createSchema(SchemaPlus parent);

  default String defaultSchema() {
    return "";
  }

  default List<RelOptRule> rules() {
    return List.of();
  }

  default boolean supportsHighlight() {
    return false;
  }

  ExecutionEngine executionEngine();

  @Override
  default void close() throws Exception {}
}
