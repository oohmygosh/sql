/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opensearch.sql.calcite.utils.OpenSearchTypeFactory.TYPE_FACTORY;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.SourceOrigin;

class ElasticsearchSchemaSemanticsTest {
  @Test
  void copyTargetsAndTheirMultifieldsAreUnsafeWhileTheCopySourceRemainsEligible() {
    Map<String, Object> mappings =
        Map.of(
            "logs-a",
            mapping(
                Map.of(
                    "source",
                        Map.of("type", "keyword", "copy_to", List.of("target", "object.target")),
                    "target",
                        Map.of(
                            "type", "keyword", "fields", Map.of("raw", Map.of("type", "keyword"))),
                    "object", Map.of("properties", Map.of("target", Map.of("type", "keyword"))),
                    "constant", Map.of("type", "constant_keyword", "value", "generated"))));
    Map<String, Field> fields = ElasticsearchQueryBackend.mappingFields(mappings);
    assertTrue(fields.get("source").exact());
    for (String name : List.of("target", "target.raw", "object.target", "constant")) {
      Field field = fields.get(name);
      assertFalse(field.exact(), name);
      assertFalse(field.supportsExistence(), name);
      assertFalse(field.supportsComparison(), name);
      assertNotNull(field.existenceRejectionReason());
    }
    assertEquals("target", fields.get("target.raw").source());
  }

  @Test
  void copyTargetsAreDiscoveredInsideObjectsAndMultifieldMappings() {
    Map<String, Field> fields =
        ElasticsearchQueryBackend.mappingFields(
            Map.of(
                "logs-a",
                mapping(
                    Map.of(
                        "message",
                            Map.of(
                                "type",
                                "text",
                                "fields",
                                Map.of("raw", Map.of("type", "keyword", "copy_to", "target.raw"))),
                        "target",
                            Map.of(
                                "type",
                                "keyword",
                                "fields",
                                Map.of("raw", Map.of("type", "keyword")))))));
    assertTrue(fields.get("target").exact());
    assertFalse(fields.get("target.raw").exact());
    assertTrue(fields.get("message.raw").exact());
  }

  @Test
  void wildcardFieldsRetainDateFormatsAndSourceRoutesInEitherIndexOrder() {
    Map<String, Object> first =
        mapping(
            Map.of(
                "timestamp",
                Map.of(
                    "type",
                    "date",
                    "fields",
                    Map.of("raw", Map.of("type", "date", "format", "epoch_second")))));
    Map<String, Object> second =
        mapping(
            Map.of(
                "timestamp",
                Map.of(
                    "properties", Map.of("raw", Map.of("type", "date", "format", "yyyy/MM/dd")))));
    for (boolean reverse : List.of(false, true)) {
      Map<String, Object> mappings = new LinkedHashMap<>();
      mappings.put(reverse ? "logs-b" : "logs-a", reverse ? second : first);
      mappings.put(reverse ? "logs-a" : "logs-b", reverse ? first : second);
      Field field = ElasticsearchQueryBackend.mappingFields(mappings).get("timestamp.raw");
      assertEquals("timestamp", field.source("logs-a"));
      assertEquals("timestamp.raw", field.source("logs-b"));
      assertEquals("epoch_second", field.dateFormat("logs-a"));
      assertEquals("yyyy/MM/dd", field.dateFormat("logs-b"));
      assertEquals(
          1704067200000L,
          ElasticsearchValueDecoder.decode(1704067200L, field.type(TYPE_FACTORY), field, "logs-a"));
      assertEquals(
          1704067200000L,
          ElasticsearchValueDecoder.decode(
              "2024/01/01", field.type(TYPE_FACTORY), field, "logs-b"));
      assertFalse(field.exact());
      assertFalse(field.hasObjectSourcePath());
      assertEquals(SourceOrigin.MIXED, field.origin());
    }
  }

  @Test
  void wildcardDateDefaultsRemainAssociatedWithTheIndex() {
    Map<String, Field> fields =
        ElasticsearchQueryBackend.mappingFields(
            Map.of(
                "logs-a", mapping(Map.of("timestamp", Map.of("type", "date"))),
                "logs-b",
                    mapping(Map.of("timestamp", Map.of("type", "date", "format", "epoch_second"))),
                "logs-empty", mapping(Map.of())));
    Field field = fields.get("timestamp");
    assertEquals("strict_date_optional_time||epoch_millis", field.dateFormat("logs-a"));
    assertEquals("epoch_second", field.dateFormat("logs-b"));
    assertEquals(Map.of("logs-a", "timestamp", "logs-b", "timestamp"), field.sources());
    assertEquals(
        1704067200000L,
        ElasticsearchValueDecoder.decode(
            "1704067200000", field.type(TYPE_FACTORY), field, "logs-a"));
    assertEquals(
        1704067200000L,
        ElasticsearchValueDecoder.decode("1704067200", field.type(TYPE_FACTORY), field, "logs-b"));
  }

  @Test
  void oneCopyTargetIndexDisablesWildcardEligibilityRegardlessOfMappingOrder() {
    Map<String, Object> first = mapping(Map.of("target", Map.of("type", "keyword")));
    Map<String, Object> second =
        mapping(
            Map.of(
                "target",
                Map.of("type", "keyword"),
                "source",
                Map.of("type", "keyword", "copy_to", "target")));
    for (boolean reverse : List.of(false, true)) {
      Map<String, Object> mappings = new LinkedHashMap<>();
      mappings.put(reverse ? "logs-b" : "logs-a", reverse ? second : first);
      mappings.put(reverse ? "logs-a" : "logs-b", reverse ? first : second);
      Field field = ElasticsearchQueryBackend.mappingFields(mappings).get("target");
      assertFalse(field.exact());
      assertTrue(field.existenceRejectionReason().contains("copy_to"));
    }
  }

  @Test
  void objectPathOriginsDistinguishObjectsMultifieldsNestedAndLiteralDottedFields() {
    Map<String, Field> fields =
        ElasticsearchQueryBackend.mappingFields(
            Map.of(
                "logs-a",
                mapping(
                    Map.of(
                        "object", Map.of("properties", Map.of("leaf", Map.of("type", "keyword"))),
                        "nested",
                            Map.of(
                                "type",
                                "nested",
                                "properties",
                                Map.of("leaf", Map.of("type", "keyword"))),
                        "flattened", Map.of("type", "flattened"),
                        "flat.leaf", Map.of("type", "keyword"),
                        "text",
                            Map.of(
                                "type", "text", "fields", Map.of("raw", Map.of("type", "keyword"))),
                        "disabled",
                            Map.of(
                                "type",
                                "object",
                                "enabled",
                                false,
                                "properties",
                                Map.of("leaf", Map.of("type", "keyword")))))));
    assertTrue(fields.get("object").hasObjectSourcePath());
    assertTrue(fields.get("object.leaf").hasObjectSourcePath());
    assertEquals(SourceOrigin.OBJECT, fields.get("object.leaf").origin());
    assertEquals(SourceOrigin.NESTED, fields.get("nested").origin());
    assertEquals(SourceOrigin.FLATTENED, fields.get("flattened").origin());
    assertEquals(SourceOrigin.FLAT, fields.get("flat.leaf").origin());
    assertEquals(SourceOrigin.MULTIFIELD, fields.get("text.raw").origin());
    for (String name : List.of("nested", "flattened", "flat.leaf", "text.raw"))
      assertFalse(fields.get(name).hasObjectSourcePath(), name);
    // Disabling indexing does not change the structure of the original source object.
    assertTrue(fields.get("disabled.leaf").hasObjectSourcePath());
    assertFalse(fields.get("disabled.leaf").supportsComparison());
    assertNull(fields.get("nested.leaf"));
  }

  @Test
  void lossyValueMappingsOnlyAllowExistenceChecks() {
    for (String type : List.of("float", "half_float", "scaled_float", "date_nanos")) {
      Field field = new Field("value", "value", type);
      assertTrue(field.exact(), "legacy eligibility remains available");
      assertTrue(field.supportsExistence());
      assertFalse(field.supportsComparison());
      assertFalse(field.supportsOrdering());
      assertFalse(field.supportsGrouping());
      assertNotNull(field.comparisonRejectionReason());
    }
    Field field = new Field("value", "value", "double");
    assertTrue(field.supportsComparison());
    assertTrue(field.supportsOrdering());
    assertTrue(field.supportsGrouping());
    assertNull(field.groupingRejectionReason());
  }

  private static Map<String, Object> mapping(Map<String, Object> properties) {
    return Map.of("mappings", Map.of("properties", properties));
  }
}
