/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.data.model;

import java.math.BigDecimal;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.data.type.ExprType;

/** Exact JDBC numeric result. The existing numeric schema family remains DOUBLE. */
public final class ExprDecimalValue extends AbstractExprNumberValue {
  private final BigDecimal decimal;

  public ExprDecimalValue(BigDecimal value) {
    super(value);
    this.decimal = value;
  }

  @Override
  public Object value() {
    return decimal;
  }

  @Override
  public ExprType type() {
    return ExprCoreType.DOUBLE;
  }

  @Override
  public String toString() {
    return decimal.toPlainString();
  }

  @Override
  public int compare(ExprValue other) {
    return decimal.compareTo(new BigDecimal(other.value().toString()));
  }

  @Override
  public boolean equal(ExprValue other) {
    return other.isNumber() && compare(other) == 0;
  }
}
