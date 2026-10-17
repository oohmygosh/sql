/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.executor.QueryType;

@EnabledIfEnvironmentVariable(named = "ELASTICSEARCH_TEST_URL", matches = ".+")
class ElasticsearchIntegrationTest {
  static RestClient authenticatedClient() {
    var builder = RestClient.builder(HttpHost.create(System.getenv("ELASTICSEARCH_TEST_URL")));
    String username = System.getenv("ELASTICSEARCH_TEST_USERNAME");
    if (username != null) {
      var credentials = new org.apache.http.impl.client.BasicCredentialsProvider();
      credentials.setCredentials(
          org.apache.http.auth.AuthScope.ANY,
          new org.apache.http.auth.UsernamePasswordCredentials(
              username, System.getenv("ELASTICSEARCH_TEST_PASSWORD")));
      builder.setHttpClientConfigCallback(
          client -> client.setDefaultCredentialsProvider(credentials));
    }
    return builder.build();
  }

  @Test
  void sqlPplPushdownAndResidualExecution() throws Exception {
    String index = "uq-" + UUID.randomUUID();
    try (RestClient client = authenticatedClient()) {
      Request create = new Request("PUT", "/" + index);
      create.setJsonEntity(
          "{\"mappings\":{\"properties\":{\"id\":{\"type\":\"integer\"},\"name\":{\"type\":\"text\"},\"department\":{\"type\":\"keyword\"},\"amount\":{\"type\":\"long\"}}}}");
      client.performRequest(create);
      try {
        for (int i = 1; i <= 3; i++) {
          Request put = new Request("PUT", "/" + index + "/_doc/" + i + "?refresh=true");
          put.setJsonEntity(
              "{\"id\":"
                  + i
                  + ",\"name\":\"person "
                  + i
                  + "\",\"department\":\""
                  + (i == 2 ? "B" : "A")
                  + "\""
                  + (i == 3 ? "" : ",\"amount\":" + (i * 10))
                  + "}");
          client.performRequest(put);
        }
        var service =
            new UnifiedQueryService(List.of(new ElasticsearchQueryBackend("es", client)), "es");
        String table = "`" + index + "`";
        var sql =
            service.execute(
                QueryType.SQL,
                "SELECT department,count(*) AS n FROM "
                    + table
                    + " GROUP BY department ORDER BY department");
        var ppl =
            service.execute(
                QueryType.PPL,
                "source="
                    + table
                    + " | stats count() as n by department | sort department | fields"
                    + " department,n");
        assertEquals(sql.getResults(), ppl.getResults());
        assertEquals(2, sql.getResults().size());
        assertEquals(2L, sql.getResults().getFirst().tupleValue().get("n").longValue());
        assertEquals(
            1,
            service
                .execute(QueryType.SQL, "SELECT id FROM " + table + " WHERE amount IS NULL")
                .getResults()
                .size());
        assertEquals(
            3,
            service
                .execute(QueryType.SQL, "SELECT id FROM " + table + " WHERE match(name,'person')")
                .getResults()
                .size());
        assertEquals(
            1,
            service
                .execute(QueryType.SQL, "SELECT id FROM " + table + " ORDER BY id DESC LIMIT 1")
                .getResults()
                .size());
        assertEquals(
            3,
            service
                .execute(
                    QueryType.SQL,
                    "SELECT a.id,b.department FROM "
                        + table
                        + " a JOIN "
                        + table
                        + " b ON a.id=b.id")
                .getResults()
                .size());
        assertEquals(
            3,
            service
                .execute(
                    QueryType.SQL, "SELECT id,row_number() OVER (ORDER BY id) AS rn FROM " + table)
                .getResults()
                .size());
        assertEquals(
            2L,
            service
                .execute(QueryType.SQL, "SELECT count(DISTINCT department) AS n FROM " + table)
                .getResults()
                .getFirst()
                .tupleValue()
                .get("n")
                .longValue());
        assertEquals(
            2,
            service
                .execute(
                    QueryType.SQL,
                    "SELECT id FROM "
                        + table
                        + " WHERE id IN (SELECT id FROM "
                        + table
                        + " WHERE id>1)")
                .getResults()
                .size());
        assertEquals(
            3,
            service
                .execute(
                    QueryType.PPL,
                    "source=" + table + " | eval label=upper(department) | fields label")
                .getResults()
                .size());
        assertEquals(
            2,
            service
                .execute(QueryType.SQL, "SELECT 1 AS n FROM " + table + " LIMIT 2")
                .getResults()
                .size());
        var reordered =
            service.execute(
                QueryType.SQL, "SELECT department,id FROM " + table + " ORDER BY id DESC LIMIT 2");
        assertEquals(3, reordered.getResults().getFirst().tupleValue().get("id").integerValue());
        var scored =
            service.execute(
                QueryType.SQL,
                "SELECT _id,_score FROM "
                    + table
                    + " WHERE match(name,'person') ORDER BY _score DESC");
        assertEquals(3, scored.getResults().size());
        assertTrue(scored.getResults().getFirst().tupleValue().get("_score").doubleValue() > 0);
        assertEquals(
            3,
            service
                .execute(
                    QueryType.SQL,
                    "SELECT id FROM " + table + " WHERE match(name,'person') AND length(name)>2")
                .getResults()
                .size());
        assertEquals(
            2L,
            service
                .execute(
                    QueryType.PPL,
                    "source=" + table + " | stats distinct_count_approx(department) as n")
                .getResults()
                .getFirst()
                .tupleValue()
                .get("n")
                .longValue());
        var highlighted =
            service.execute(
                "es",
                QueryType.PPL,
                "source=" + table + " | where match(name,'person') | fields name,_highlight",
                new org.opensearch.sql.ast.tree.HighlightConfig(List.of("name")));
        assertTrue(
            highlighted
                .getResults()
                .getFirst()
                .tupleValue()
                .get("_highlight")
                .toString()
                .contains("<em>person</em>"));
        assertTrue(
            service
                .explain(null, QueryType.SQL, "SELECT id FROM " + table + " WHERE id>1")
                .getCalcite()
                .getPhysical()
                .contains("range"));
      } finally {
        client.performRequest(new Request("DELETE", "/" + index));
      }
    }
  }

  @Test
  void pagesDocumentsAndAllAggregationBuckets() throws Exception {
    String index = "uq-pages-" + UUID.randomUUID();
    try (RestClient client = authenticatedClient()) {
      Request create = new Request("PUT", "/" + index);
      create.setJsonEntity("{\"mappings\":{\"properties\":{\"id\":{\"type\":\"integer\"}}}}");
      client.performRequest(create);
      try {
        StringBuilder bulk = new StringBuilder();
        for (int i = 0; i < 1005; i++)
          bulk.append("{\"index\":{}}\n{\"id\":").append(i).append("}\n");
        Request put = new Request("POST", "/" + index + "/_bulk?refresh=true");
        put.setJsonEntity(bulk.toString());
        client.performRequest(put);
        var service =
            new UnifiedQueryService(List.of(new ElasticsearchQueryBackend("es", client)), "es");
        String table = "`" + index + "`";
        assertEquals(
            1005, service.execute(QueryType.SQL, "SELECT id FROM " + table).getResults().size());
        assertEquals(
            1005,
            service
                .execute(QueryType.SQL, "SELECT id,count(*) AS n FROM " + table + " GROUP BY id")
                .getResults()
                .size());
        var page =
            service.execute(
                QueryType.SQL, "SELECT id FROM " + table + " ORDER BY id LIMIT 5 OFFSET 1000");
        assertEquals(5, page.getResults().size());
        assertEquals(1000, page.getResults().getFirst().tupleValue().get("id").integerValue());
      } finally {
        client.performRequest(new Request("DELETE", "/" + index));
      }
    }
  }

  @Test
  void nestedAndDeclaredArraysRetainTheirValues() throws Exception {
    String index = "uq-types-" + UUID.randomUUID();
    try (RestClient client = authenticatedClient()) {
      Request create = new Request("PUT", "/" + index);
      create.setJsonEntity(
          "{\"mappings\":{\"properties\":{\"amount\":{\"type\":\"long\"},\"nullable\":{\"type\":\"keyword\",\"null_value\":\"EMPTY\"},\"tags\":{\"type\":\"keyword\"},\"items\":{\"type\":\"nested\",\"properties\":{\"name\":{\"type\":\"keyword\"}}}}}}");
      client.performRequest(create);
      try {
        Request put = new Request("PUT", "/" + index + "/_doc/1?refresh=true");
        put.setJsonEntity(
            "{\"amount\":9007199254740993,\"nullable\":null,\"tags\":[\"a\",\"b\"],\"items\":[{\"name\":\"first\"},{\"name\":\"second\"}]}");
        client.performRequest(put);
        var service =
            new UnifiedQueryService(
                List.of(
                    new ElasticsearchQueryBackend(
                        "es", client, false, java.util.Map.of("tags", "array<keyword>"))),
                "es");
        String table = "`" + index + "`";
        var row =
            service
                .execute(QueryType.SQL, "SELECT tags,items FROM " + table)
                .getResults()
                .getFirst()
                .tupleValue();
        assertEquals(2, row.get("tags").collectionValue().size());
        assertEquals(
            9007199254740993L,
            service
                .execute(QueryType.SQL, "SELECT min(amount) AS n FROM " + table)
                .getResults()
                .getFirst()
                .tupleValue()
                .get("n")
                .longValue());
        assertEquals(
            1,
            service
                .execute(QueryType.SQL, "SELECT amount FROM " + table + " WHERE nullable IS NULL")
                .getResults()
                .size());
        assertEquals(2, row.get("items").collectionValue().size());
        assertEquals(
            2,
            service
                .execute(QueryType.PPL, "source=" + table + " | mvexpand tags | fields tags")
                .getResults()
                .size());
      } finally {
        client.performRequest(new Request("DELETE", "/" + index));
      }
    }
  }
}
