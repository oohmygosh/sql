/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.backend;

import java.util.List;
import java.util.Set;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.opensearch.sql.executor.ExecutionEngine;

/** A data source's schema, planning rules and executor must be selected together. */
public interface QueryBackend extends AutoCloseable {
  String name();

  String type();

  Schema createSchema(SchemaPlus parent);

  /** Enumerates relation names without loading each relation's columns when the backend permits. */
  default Set<String> tableNames(Schema schema) {
    return schema.getTableNames();
  }

  /** Resolves completion metadata without changing the backend's query-time table semantics. */
  default Table metadataTable(Schema schema, String name) {
    return schema.getTable(name);
  }

  default String defaultSchema() {
    return "";
  }

  default List<RelOptRule> rules() {
    return List.of();
  }

  /** Mandatory source-semantic normalization, applied before optional cost-based optimization. */
  default RelNode normalizePlan(RelNode plan) {
    return plan;
  }

  default boolean supportsHighlight() {
    return false;
  }

  /** Native ML operations available to the unified query pipeline, if any. */
  default MachineLearningProvider machineLearning() {
    return null;
  }

  ExecutionEngine executionEngine();

  @Override
  default void close() throws Exception {}
}
