/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.plan.rel;

import java.util.List;
import java.util.Map;
import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.adapter.enumerable.JavaRowFormat;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.Types;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.type.RelDataType;
import org.opensearch.sql.backend.MachineLearningProvider;

/** Executes the selected backend's native ML operation over an Enumerable input. */
public final class EnumerableBackendML extends SingleRel implements EnumerableRel {
  public static final RelOptRule RULE =
      new ConverterRule(
          LogicalBackendML.class,
          org.apache.calcite.plan.Convention.NONE,
          EnumerableConvention.INSTANCE,
          "EnumerableBackendMLRule") {
        @Override
        public RelNode convert(RelNode rel) {
          LogicalBackendML ml = (LogicalBackendML) rel;
          RelNode input =
              convert(
                  ml.getInput(),
                  ml.getInput().getTraitSet().replace(EnumerableConvention.INSTANCE));
          return new EnumerableBackendML(input, ml.operation(), ml.arguments());
        }
      };

  private final MachineLearningProvider.Operation operation;
  private final Map<String, Object> arguments;

  private EnumerableBackendML(
      RelNode input, MachineLearningProvider.Operation operation, Map<String, Object> arguments) {
    super(input.getCluster(), input.getCluster().traitSetOf(EnumerableConvention.INSTANCE), input);
    this.operation = operation;
    this.arguments = arguments;
  }

  @Override
  public RelWriter explainTerms(RelWriter writer) {
    return super.explainTerms(writer).item("arguments", arguments);
  }

  @Override
  protected RelDataType deriveRowType() {
    return operation.outputType();
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new EnumerableBackendML(sole(inputs), operation, arguments);
  }

  @Override
  public Result implement(EnumerableRelImplementor implementor, Prefer prefer) {
    Result child = implementor.visitChild(this, 0, (EnumerableRel) getInput(), Prefer.ARRAY);
    BlockBuilder block = new BlockBuilder();
    var rows = block.append("mlInput", child.block);
    var arrayRows = child.physType.convertTo(rows, JavaRowFormat.ARRAY);
    var executable = implementor.stash(operation, MachineLearningProvider.Operation.class);
    block.add(
        Expressions.return_(
            null,
            Expressions.call(
                executable,
                Types.lookupMethod(
                    MachineLearningProvider.Operation.class,
                    "execute",
                    Enumerable.class,
                    DataContext.class),
                arrayRows,
                implementor.getRootExpression())));
    return implementor.result(
        PhysTypeImpl.of(implementor.getTypeFactory(), getRowType(), JavaRowFormat.ARRAY, false),
        block.toBlock());
  }
}
