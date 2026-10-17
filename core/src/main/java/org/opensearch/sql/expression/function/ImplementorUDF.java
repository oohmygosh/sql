/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.expression.function;

import java.util.List;
import org.apache.calcite.adapter.enumerable.CallImplementor;
import org.apache.calcite.adapter.enumerable.NotNullImplementor;
import org.apache.calcite.adapter.enumerable.NullPolicy;
import org.apache.calcite.adapter.enumerable.RexImpTable;
import org.apache.calcite.schema.FunctionParameter;
import org.apache.calcite.schema.ImplementableFunction;

/** The UDF which implements its functionality by NotNullImplementor */
public abstract class ImplementorUDF implements UserDefinedFunctionBuilder {
  private final NotNullImplementor implementor;
  private final NullPolicy nullPolicy;

  protected ImplementorUDF(NotNullImplementor implementor, NullPolicy nullPolicy) {
    this.implementor = implementor;
    this.nullPolicy = nullPolicy;
  }

  @Override
  public ImplementableFunction getFunction() {
    return new ImplementableFunction() {
      @Override
      public List<FunctionParameter> getParameters() {
        return List.of();
      }

      @Override
      public CallImplementor getImplementor() {
        NotNullImplementor nativeAware =
            (translator, call, operands) -> {
              var result = implementor.implement(translator, call, operands);
              var kind = call.getType().getSqlTypeName();
              if (kind == org.apache.calcite.sql.type.SqlTypeName.DATE
                  || kind == org.apache.calcite.sql.type.SqlTypeName.TIME
                  || kind == org.apache.calcite.sql.type.SqlTypeName.TIMESTAMP) {
                var declared =
                    getReturnTypeInference()
                        .inferReturnType(
                            new org.apache.calcite.rex.RexCallBinding(
                                org.opensearch.sql.calcite.utils.OpenSearchTypeFactory.TYPE_FACTORY,
                                call.getOperator(),
                                call.getOperands(),
                                List.of()));
                if (declared
                    instanceof org.opensearch.sql.calcite.type.AbstractExprRelDataType<?>) {
                  result =
                      org.apache.calcite.linq4j.tree.Expressions.call(
                          ImplementorUDF.class,
                          "nativeTemporalResult",
                          org.apache.calcite.linq4j.tree.Expressions.convert_(
                              org.apache.calcite.linq4j.tree.Expressions.box(result), Object.class),
                          org.apache.calcite.linq4j.tree.Expressions.constant(kind.name()));
                  result =
                      org.apache.calcite.linq4j.tree.Expressions.convert_(
                          result,
                          kind == org.apache.calcite.sql.type.SqlTypeName.TIMESTAMP
                              ? Long.class
                              : Integer.class);
                }
              }
              return result;
            };
        return RexImpTable.createImplementor(nativeAware, nullPolicy, false);
      }
    };
  }

  /** Bridges VARCHAR-backed temporal UDFs when the unified API normalizes their return type. */
  public static Object nativeTemporalResult(Object value, String kind) {
    if (value == null) return null;
    if (value instanceof Number) return value;
    var type = org.opensearch.sql.data.type.ExprCoreType.valueOf(kind);
    var temporal = org.opensearch.sql.data.model.ExprValueUtils.fromObjectValue(value, type);
    return switch (type) {
      case DATE -> (int) temporal.dateValue().toEpochDay();
      case TIME -> (int) (temporal.timeValue().toNanoOfDay() / 1000000L);
      default -> temporal.timestampValue().toEpochMilli();
    };
  }
}
