/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.plan.rel;

import java.util.List;
import java.util.Map;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.type.RelDataType;
import org.opensearch.sql.backend.MachineLearningProvider;

/** Opaque ML boundary: filters and limits after ML must not change its training/input dataset. */
public final class LogicalBackendML extends SingleRel {
  private final MachineLearningProvider.Operation operation;
  private final Map<String, Object> arguments;

  public LogicalBackendML(
      RelNode input, MachineLearningProvider.Operation operation, Map<String, Object> arguments) {
    super(input.getCluster(), input.getCluster().traitSetOf(Convention.NONE), input);
    this.operation = operation;
    this.arguments = Map.copyOf(arguments);
  }

  public Map<String, Object> arguments() {
    return arguments;
  }

  public MachineLearningProvider.Operation operation() {
    return operation;
  }

  @Override
  protected RelDataType deriveRowType() {
    return operation.outputType();
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new LogicalBackendML(sole(inputs), operation, arguments);
  }

  @Override
  public RelWriter explainTerms(RelWriter writer) {
    // Include every option in the digest: different models over the same input are not equivalent.
    return super.explainTerms(writer).item("arguments", arguments);
  }
}
