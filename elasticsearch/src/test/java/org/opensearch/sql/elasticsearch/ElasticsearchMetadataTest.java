/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.opensearch.sql.api.completion.CompletionMetadata.Field;
import org.opensearch.sql.api.completion.CompletionMetadata.MetadataException;
import org.opensearch.sql.api.completion.CompletionMetadata.RelationRef;
import org.opensearch.sql.api.completion.QueryMetadataService;

class ElasticsearchMetadataTest {
  @Test
  void enumeratesIndicesAndAliasesOnceAndReadsWildcardMappingWithoutSearching() throws Exception {
    List<String> requests = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          requests.add(exchange.getRequestMethod() + " " + path);
          String body =
              switch (path) {
                case "/_alias" ->
                    """
                    {"events-1":{"aliases":{"live-events":{}}},"events-2":{"aliases":{}}}
                    """;
                case "/events-*/_mapping" ->
                    """
                    {"events-1":{"mappings":{"properties":{"status":{"type":"long"},
                      "first_only":{"type":"keyword"}}}},
                     "events-2":{"mappings":{"properties":{"status":{"type":"keyword"},
                      "second_only":{"type":"boolean"}}}}}
                    """;
                case "/missing-*/_mapping" -> "{}";
                default -> "{\"error\":\"unexpected metadata request\"}";
              };
          byte[] response = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(
              body.contains("unexpected metadata request") ? 500 : 200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try (RestClient client =
        RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort(), "http"))
            .build()) {
      ElasticsearchQueryBackend backend = new ElasticsearchQueryBackend("logs", client);
      QueryMetadataService service =
          new QueryMetadataService(List.of(backend), "logs", Map.of("logs", "events-*"));

      assertEquals(
          List.of("events-*", "events-1", "events-2", "live-events"),
          service.tables("logs", List.of(), "", 20).stream()
              .map(relation -> relation.name())
              .toList());
      assertEquals(List.of("GET /_alias"), requests);

      List<Field> fields = service.fields("logs", new RelationRef(List.of(), "events-*"));
      assertTrue(fields.contains(new Field("status", "any")));
      assertTrue(fields.contains(new Field("first_only", "varchar")));
      assertTrue(fields.contains(new Field("second_only", "boolean")));
      assertEquals(List.of("GET /_alias", "GET /events-*/_mapping"), requests);

      assertThrows(
          MetadataException.class,
          () -> service.fields("logs", new RelationRef(List.of(), "missing-*")));
      assertEquals(
          List.of("GET /_alias", "GET /events-*/_mapping", "GET /missing-*/_mapping"), requests);
      // Completion distinguishes a missing pattern, while query-time empty-index semantics stay
      // compatible with the existing backend.
      assertNotNull(backend.createSchema(null).getTable("missing-*"));
    } finally {
      server.stop(0);
    }
  }
}
