/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend;
import org.opensearch.sql.spring.UnifiedQueryProperties;

/** Owns only a uniquely named test index and its separate local-oracle connection. */
final class SearchEsFixture implements AutoCloseable {
  final String index = "uq-search-e2e-" + UUID.randomUUID();
  private final RestClient client;
  private final ObjectMapper json;
  private boolean created;

  SearchEsFixture(UnifiedQueryProperties.DataSource config, ObjectMapper json) {
    this.json = json;
    var builder = RestClient.builder(HttpHost.create(config.getUrl()));
    if (config.getUsername() != null && !config.getUsername().isBlank()) {
      var credentials = new BasicCredentialsProvider();
      credentials.setCredentials(
          AuthScope.ANY,
          new UsernamePasswordCredentials(config.getUsername(), config.getPassword()));
      builder.setHttpClientConfigCallback(http -> http.setDefaultCredentialsProvider(credentials));
    }
    client = builder.build();
  }

  UnifiedQueryService localQueries() {
    return new UnifiedQueryService(
        java.util.List.of(new ElasticsearchQueryBackend("logs", client, false, Map.of(), false)),
        "logs");
  }

  void seed() throws IOException {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", Map.of("type", "keyword"));
    fields.put("timestamp", Map.of("type", "date", "format", "epoch_millis"));
    fields.put("quantity", Map.of("type", "integer"));
    fields.put("amount", Map.of("type", "double"));
    fields.put("large", Map.of("type", "long"));
    fields.put("level", Map.of("type", "keyword"));
    fields.put("label", Map.of("type", "keyword"));
    fields.put("enabled", Map.of("type", "boolean"));
    fields.put("local_message", Map.of("type", "text"));
    fields.put(
        "message",
        Map.of(
            "type",
            "text",
            "fields",
            Map.of(
                "wc", Map.of("type", "wildcard"),
                "keyword", Map.of("type", "keyword", "ignore_above", 64))));
    fields.put(
        "extracted",
        Map.of(
            "properties",
            Map.of(
                "process",
                Map.of(
                    "properties",
                    Map.of("name", Map.of("type", "keyword"), "pid", Map.of("type", "integer"))))));
    Request create = new Request("PUT", "/" + index);
    create.setJsonEntity(json.writeValueAsString(Map.of("mappings", Map.of("properties", fields))));
    client.performRequest(create);
    created = true;
    StringBuilder bulk = new StringBuilder();
    for (int i = 1; i <= 24; i++) append(bulk, document(i));
    Map<String, Object> before = document(25);
    before.put("id", "outside-before");
    before.put("timestamp", SearchPushdownCases.START - 1);
    append(bulk, before);
    Map<String, Object> after = document(26);
    after.put("id", "outside-after");
    after.put("timestamp", SearchPushdownCases.END);
    append(bulk, after);
    Request insert = new Request("POST", "/" + index + "/_bulk?refresh=true");
    insert.setJsonEntity(bulk.toString());
    var response = json.readTree(EntityUtils.toString(client.performRequest(insert).getEntity()));
    assertFalse(response.path("errors").asBoolean(), response.toPrettyString());
  }

  private void append(StringBuilder bulk, Map<String, Object> doc) throws IOException {
    bulk.append("{\"index\":{}}\n").append(json.writeValueAsString(doc)).append('\n');
  }

  private static Map<String, Object> document(int i) {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("id", String.format(java.util.Locale.ROOT, "%03d", i));
    doc.put("timestamp", SearchPushdownCases.START + i * 60000L);
    doc.put("quantity", i);
    doc.put("amount", i * 2.5);
    doc.put("large", 10000000000L + i);
    doc.put("label", "label-" + i % 4);
    doc.put("enabled", i % 2 == 0);
    if (i % 7 != 0) doc.put("level", i % 4 == 0 ? "ERROR" : i % 2 == 0 ? "INFO" : "WARN");
    // A matching message above ignore_above makes unsafe keyword substitution lose a real row.
    String message =
        i % 5 == 0
            ? null
            : (i == 7 ? "x".repeat(300) : "")
                + "Accepted "
                + (i % 2 == 0 || i == 7 ? "PASSword" : "publickey")
                + (i == 8 ? "\n% _ * ? \\" : "");
    if (i % 11 != 0) {
      doc.put("message", message);
      doc.put("local_message", message);
    }
    doc.put("extracted", Map.of("process", Map.of("name", i % 3 != 0 ? "sshd" : "cron", "pid", i)));
    return doc;
  }

  @Override
  public void close() throws IOException {
    try {
      if (created) client.performRequest(new Request("DELETE", "/" + index));
    } finally {
      client.close();
    }
  }
}
