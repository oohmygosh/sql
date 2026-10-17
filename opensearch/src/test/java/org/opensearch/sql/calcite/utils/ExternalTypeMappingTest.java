/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.opensearch.sql.calcite.utils.OpenSearchTypeFactory.TYPE_FACTORY;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.analytics.schema.BinaryType;
import org.opensearch.analytics.schema.IpType;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.data.type.ExprType;

class ExternalTypeMappingTest {
  @BeforeEach
  void register() {
    OpenSearchTypeFactory.registerExternalType(IpType.class, ExprCoreType.IP);
    OpenSearchTypeFactory.registerExternalType(BinaryType.class, ExprCoreType.BINARY);
  }

  // ---------- convertAnalyticsEngineRelDataTypeToExprType ----------
  // UDT-aware variant for the response-schema path. Must agree with the
  // planner-internal convertRelDataTypeToExprType on every non-UDT input.

  @Test
  public void testConvertAnalyticsEngineIpTypeReturnsIpExprType() {
    ExprType result =
        OpenSearchTypeFactory.convertAnalyticsEngineRelDataTypeToExprType(new IpType(true));
    assertEquals(ExprCoreType.IP, result);
  }

  @Test
  public void testConvertAnalyticsEngineBinaryTypeReturnsBinaryExprType() {
    ExprType result =
        OpenSearchTypeFactory.convertAnalyticsEngineRelDataTypeToExprType(new BinaryType(true));
    assertEquals(ExprCoreType.BINARY, result);
  }

  @Test
  public void testConvertAnalyticsEnginePlainVarbinaryFallsBackToBinary() {
    // Plain VARBINARY (no UDT) must still resolve to BINARY via the delegated path.
    RelDataType varbinary = TYPE_FACTORY.createSqlType(SqlTypeName.VARBINARY);
    ExprType result = OpenSearchTypeFactory.convertAnalyticsEngineRelDataTypeToExprType(varbinary);
    assertEquals(ExprCoreType.BINARY, result);
  }

  @Test
  public void testConvertAnalyticsEngineDelegatesParityForNonUdtTypes() {
    // Parity check: drift would mean response-schema labels diverge from Calcite's view.
    RelDataType[] samples =
        new RelDataType[] {
          TYPE_FACTORY.createSqlType(SqlTypeName.BIGINT),
          TYPE_FACTORY.createSqlType(SqlTypeName.VARCHAR),
          TYPE_FACTORY.createSqlType(SqlTypeName.BOOLEAN),
          TYPE_FACTORY.createSqlType(SqlTypeName.DOUBLE),
          TYPE_FACTORY.createSqlType(SqlTypeName.TIMESTAMP),
        };
    for (RelDataType t : samples) {
      assertEquals(
          OpenSearchTypeFactory.convertRelDataTypeToExprType(t),
          OpenSearchTypeFactory.convertAnalyticsEngineRelDataTypeToExprType(t),
          "Analytics-engine variant must agree with the general variant for " + t.getSqlTypeName());
    }
  }
}
