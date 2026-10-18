/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opensearch.sql.calcite.utils.OpenSearchTypeFactory.TYPE_FACTORY;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;

class ElasticsearchValueDecoderTest {
  @Test
  void defaultDatesAcceptDateOnlyInstantsAndEpochValues() {
    Field field = new Field("timestamp", "timestamp", "date");
    long midnight = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
    for (Object value : List.of("2024-01-01", "2024-01-01T00:00:00Z", "1704067200000", midnight))
      assertEquals(midnight, decode(value, field));
    assertEquals(midnight, decode("2024-01-01T02:00:00+02:00", field));
    assertEquals(midnight, decode("2024-01-01T00:00:00", field));
    assertEquals(midnight + 123, decode("2024-01-01T00:00:00.123456789Z", field));
  }

  @Test
  void epochSecondsApplyToNumbersAndStringsWithoutDependingOnTheirJsonRepresentation() {
    Field field = new Field("timestamp", "timestamp", "date", "epoch_second");
    for (Object value : List.of(1704067200L, "1704067200", new BigDecimal("1704067200.125"))) {
      long expected = new BigDecimal(value.toString()).movePointRight(3).longValueExact();
      assertEquals(expected, decode(value, field));
    }
    assertEquals(-1L, decode("-0.0001", field));
    assertEquals(
        1704067200000L,
        ElasticsearchValueDecoder.decodeAggregate(1704067200000L, field.type(TYPE_FACTORY)));
    assertThrows(IllegalArgumentException.class, () -> decode("2024-01-01", field));
  }

  @Test
  void configuredJavaPatternsAreStrictAndNeverSilentlyFallBack() {
    Field day = new Field("timestamp", "timestamp", "date", "yyyy/MM/dd");
    assertEquals(Instant.parse("2024-02-29T00:00:00Z").toEpochMilli(), decode("2024/02/29", day));
    assertThrows(IllegalArgumentException.class, () -> decode("2023/02/29", day));
    assertThrows(IllegalArgumentException.class, () -> decode("2024-02-29T00:00:00Z", day));
    assertThrows(IllegalArgumentException.class, () -> decode(1704067200000L, day));
    Field timestamp = new Field("timestamp", "timestamp", "date", "yyyy/MM/dd HH:mm:ss XXX");
    assertEquals(
        Instant.parse("2024-01-01T00:00:00Z").toEpochMilli(),
        decode("2024/01/01 02:00:00 +02:00", timestamp));
    Field alternatives = new Field("timestamp", "timestamp", "date", "yyyy/MM/dd||epoch_second");
    assertEquals(decode("2024/01/01", alternatives), decode("1704067200", alternatives));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decode(
                "2024-01-01",
                new Field("timestamp", "timestamp", "date", "unsupported_date_format")));
  }

  @Test
  void numericAndBooleanStringsUseMappedTypes() {
    assertEquals((byte) 12, decode("12.9", new Field("n", "n", "byte")));
    assertEquals((short) -12, decode("-12.9", new Field("n", "n", "short")));
    assertEquals(42, decode("42", new Field("n", "n", "integer")));
    assertEquals(9007199254740993L, decode("9007199254740993", new Field("n", "n", "long")));
    assertEquals(
        new BigDecimal("18446744073709551615"),
        decode("18446744073709551615", new Field("n", "n", "unsigned_long")));
    assertEquals(1.25d, decode("1.25", new Field("n", "n", "double")));
    Field bool = new Field("flag", "flag", "boolean");
    assertEquals(true, decode("true", bool));
    assertEquals(false, decode("false", bool));
    assertEquals(false, decode("", bool));
    assertEquals(true, decode(true, bool));
    assertThrows(IllegalArgumentException.class, () -> decode("not-a-boolean", bool));
    assertThrows(IllegalArgumentException.class, () -> decode("128", new Field("n", "n", "byte")));
  }

  @Test
  void quantizedMappingsRetainOriginalSourcePrecision() {
    for (String mapping : List.of("float", "half_float", "scaled_float")) {
      Field field = new Field("n", "n", mapping);
      assertEquals(1.00000001d, decode(1.00000001d, field));
      assertEquals(1.00000002d, decode("1.00000002", field));
    }
  }

  @Test
  void arraysDecodeElementsAndLeaveOriginalObjectsUnchanged() {
    Field numbers = new Field("numbers", "numbers", "array<integer>");
    List<Object> raw = Arrays.asList("42", null, 12);
    assertEquals(Arrays.asList(42, null, 12), decode(raw, numbers));
    assertEquals("42", raw.getFirst());
    Field dates = new Field("timestamps", "timestamps", "array<date>", "epoch_second");
    assertEquals(List.of(1704067200000L), decode(List.of("1704067200"), dates));
    Map<String, Object> original = new LinkedHashMap<>(Map.of("number", "42"));
    assertSame(original, decode(original, new Field("object", "object", "object")));
    List<?> nested = (List<?>) decode(List.of(original), new Field("nested", "nested", "nested"));
    assertSame(original, nested.getFirst());
    assertEquals("42", original.get("number"));
  }

  @Test
  void nullAndConversionErrorsRemainDistinctWithPhysicalFieldContext() {
    Field field =
        new Field("amount.raw", "amount", "integer", true, Map.of("logs-a", "payload.amount"));
    assertNull(ElasticsearchValueDecoder.decode(null, field.type(TYPE_FACTORY), field, "logs-a"));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ElasticsearchValueDecoder.decode("NaN", field.type(TYPE_FACTORY), field, "logs-a"));
    assertTrue(error.getMessage().contains("amount.raw"));
    assertTrue(error.getMessage().contains("payload.amount"));
    assertTrue(error.getMessage().contains("logs-a"));
    assertTrue(error.getMessage().contains("integer"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ElasticsearchValueDecoder.decodeAggregate(
                "bad", TYPE_FACTORY.createSqlType(SqlTypeName.BIGINT)));
  }

  private static Object decode(Object value, Field field) {
    return ElasticsearchValueDecoder.decode(value, field.type(TYPE_FACTORY), field, "logs-a");
  }
}
