/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.opensearch.sql.calcite.utils.OpenSearchTypeFactory.TYPE_FACTORY;

import java.util.List;
import java.util.Map;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;

class ElasticsearchFieldTest {
  @Test
  void integralMappingsWidenWithoutLosingPrecisionOrDependingOnIndexOrder() {
    Field integer = new Field("amount", "amount", "integer");
    Field signed = new Field("amount", "amount", "long");
    Field unsigned = new Field("amount", "amount", "unsigned_long");
    assertEquals(SqlTypeName.BIGINT, integer.merge(signed).sqlType());
    assertEquals(SqlTypeName.BIGINT, signed.merge(integer).sqlType());
    for (Field merged : List.of(signed.merge(unsigned), unsigned.merge(signed))) {
      var type = merged.type(TYPE_FACTORY);
      assertEquals(SqlTypeName.DECIMAL, type.getSqlTypeName());
      assertEquals(20, type.getPrecision());
      assertEquals(0, type.getScale());
      assertFalse(merged.exact());
    }
  }

  @Test
  void heterogeneousFieldsRemainDynamicInEitherIndexOrder() {
    Field integer = new Field("port", "port", "long");
    for (String mapping : List.of("keyword", "double", "object", "nested")) {
      Field other = new Field("port", "port", mapping);
      assertEquals(SqlTypeName.ANY, integer.merge(other).sqlType());
      assertEquals(SqlTypeName.ANY, other.merge(integer).sqlType());
      assertFalse(integer.merge(other).exact());
      // A later numeric mapping must not conceal an earlier incompatible mapping.
      assertEquals(SqlTypeName.ANY, integer.merge(other).merge(integer).sqlType());
    }
  }

  @Test
  void differingMappingOptionsDisablePushdownInEitherIndexOrder() {
    Field plain = new Field("name", "name", "keyword", true);
    Field normalized = new Field("name", "name", "keyword", false);
    assertEquals(SqlTypeName.VARCHAR, plain.merge(normalized).sqlType());
    assertFalse(plain.merge(normalized).exact());
    assertFalse(normalized.merge(plain).exact());
  }

  @Test
  void conflictingSourcePathsRemainAssociatedWithTheirPhysicalIndex() {
    Field multifield =
        new Field("message.keyword", "message", "keyword", true, Map.of("logs-a", "message"));
    Field property =
        new Field(
            "message.keyword",
            "message.keyword",
            "keyword",
            true,
            Map.of("logs-b", "message.keyword"));
    for (Field merged : List.of(multifield.merge(property), property.merge(multifield))) {
      assertEquals("message", merged.source("logs-a"));
      assertEquals("message.keyword", merged.source("logs-b"));
      assertEquals(2, merged.sourcePaths().distinct().count());
      assertFalse(merged.exact());
    }
  }
}
