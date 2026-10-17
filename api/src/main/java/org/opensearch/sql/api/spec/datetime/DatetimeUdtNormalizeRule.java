/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.spec.datetime;

import java.util.ArrayList;
import java.util.Optional;
import org.apache.calcite.rel.RelHomogeneousShuttle;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.sql.api.spec.datetime.DatetimeExtension.UdtMapping;

/**
 * Temporary patch that rewrites datetime UDT return types on RexCall nodes to standard Calcite
 * types.
 *
 * <p>Not a singleton: {@link RelHomogeneousShuttle} inherits a stateful {@code stack} field from
 * {@link org.apache.calcite.rel.RelShuttleImpl}, so a fresh instance must be used per plan().
 */
class DatetimeUdtNormalizeRule extends RelHomogeneousShuttle {

  @Override
  protected RelNode visitChild(RelNode parent, int index, RelNode child) {
    stack.push(parent);
    try {
      RelNode updated = child.accept(this);
      if (updated == child) return parent;
      var inputs = new ArrayList<>(parent.getInputs());
      inputs.set(index, updated);
      var fields =
          inputs.stream().flatMap(input -> input.getRowType().getFieldList().stream()).toList();
      RexShuttle references =
          new RexShuttle() {
            @Override
            public RexNode visitInputRef(RexInputRef ref) {
              var type = fields.get(ref.getIndex()).getType();
              return ref.getType().equals(type) ? ref : new RexInputRef(ref.getIndex(), type);
            }
          };
      if (parent instanceof Project project) {
        var expressions =
            project.getProjects().stream().map(expr -> expr.accept(references)).toList();
        var type =
            parent
                .getCluster()
                .getTypeFactory()
                .createStructType(
                    expressions.stream().map(RexNode::getType).toList(),
                    project.getRowType().getFieldNames());
        return project.copy(project.getTraitSet(), updated, expressions, type);
      }
      if (parent instanceof Filter filter)
        return filter.copy(filter.getTraitSet(), updated, filter.getCondition().accept(references));
      if (parent instanceof Join join)
        return join.copy(
            join.getTraitSet(),
            join.getCondition().accept(references),
            inputs.get(0),
            inputs.get(1),
            join.getJoinType(),
            join.isSemiJoinDone());
      return parent.copy(parent.getTraitSet(), inputs);
    } finally {
      stack.pop();
    }
  }

  @Override
  public RelNode visit(RelNode other) {
    RelNode visited = super.visit(other);
    RexBuilder rexBuilder = visited.getCluster().getRexBuilder();
    RelDataTypeFactory typeFactory = rexBuilder.getTypeFactory();
    return visited.accept(
        new RexShuttle() {
          @Override
          public RexNode visitCall(RexCall call) {
            call = (RexCall) super.visitCall(call);
            Optional<UdtMapping> mapping = UdtMapping.fromUdtType(call.getType());
            if (mapping.isEmpty()) {
              return call;
            }

            // Normalize UDT return type to standard Calcite DATE/TIME/TIMESTAMP
            UdtMapping m = mapping.get();
            SqlTypeName stdTypeName = m.getStdType();
            RelDataType baseType =
                stdTypeName.allowsPrec()
                    ? typeFactory.createSqlType(
                        stdTypeName, typeFactory.getTypeSystem().getMaxPrecision(stdTypeName))
                    : typeFactory.createSqlType(stdTypeName);
            RelDataType stdType =
                typeFactory.createTypeWithNullability(baseType, call.getType().isNullable());
            return call.clone(stdType, call.getOperands());
          }
        });
  }
}
