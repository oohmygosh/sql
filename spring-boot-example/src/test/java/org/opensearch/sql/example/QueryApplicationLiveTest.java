/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.executor.ExecutionEngine;
import org.opensearch.sql.executor.ExecutionEngine.QueryResponse;
import org.opensearch.sql.executor.QueryType;
import org.opensearch.sql.lang.LangSpec;
import org.opensearch.sql.protocol.response.QueryResult;
import org.opensearch.sql.protocol.response.format.JsonResponseFormatter.Style;
import org.opensearch.sql.protocol.response.format.SimpleJsonResponseFormatter;
import org.opensearch.sql.spring.UnifiedQueryProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Read-only integration tests against the example's configured, real data sources.
 */
@SpringBootTest(
        classes = QueryApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        args = "--query=SELECT 1 AS ready")
// @EnabledIfEnvironmentVariable(named = "ES_PASSWORD", matches = ".+")
// @EnabledIfEnvironmentVariable(named = "CLICKHOUSE_PASSWORD", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QueryApplicationLiveTest {
    @Autowired
    private UnifiedQueryService queries;
    @Autowired
    private UnifiedQueryProperties properties;

    @Value("${ES_TEST_INDEX:logevent-214-000001}")
    private String elasticsearchIndex;

    @Value("${CLICKHOUSE_TEST_TABLE:log_event}")
    private String clickHouseTable;

    @Test
    void elasticsearchSqlExecutesRealQuery() throws Exception {
        assertElasticsearchQuery(QueryType.SQL, "SELECT *  FROM logevent-107-000064 limit 1");
    }

    @Test
    void elasticsearchPplExecutesRealQuery() throws Exception {
        assertElasticsearchQuery(QueryType.PPL, """
                source=`logevent-*`
                | fields id, timestamp, hostIp, extracted
                | where extracted.process.name != 'nezha-agent'
                | sort - timestamp
                | head 5
                """);
        // assertElasticsearchQuery(QueryType.PPL, """
        //         source=`*`
        //         | where timestamp >= "2026-10-18"
        //         | stats sum(size) by span(timestamp, 1h) as time_bucket
        //         | sort - time_bucket
        //         """);
    }

    @Test
    void clickHouseSqlExecutesRealQuery() throws Exception {
        assertClickHouseQuery(QueryType.SQL, "SELECT count(*) FROM `log_event` where hostIp = '192.168.100.177'");
        // assertClickHouseQuery(QueryType.SQL);
    }

    @Test
    void clickHousePplExecutesRealQuery() throws Exception {
        assertClickHouseQuery(QueryType.PPL);
    }

    @Test
    void elasticsearchSqlUsesRealBackend() {
        assertElasticsearchPlan(QueryType.SQL);
    }

    @Test
    void elasticsearchPplUsesRealBackend() {
        assertElasticsearchPlan(QueryType.PPL);
    }

    @Test
    void clickHouseSqlUsesRealBackend() {
        assertClickHousePlan(QueryType.SQL);
    }

    @Test
    void clickHousePplUsesRealBackend() {
        assertClickHousePlan(QueryType.PPL);
    }

    private void assertElasticsearchQuery(QueryType language, String query) throws Exception {
        long expected = nativeElasticsearchCount();
        long queryStarted = System.nanoTime();
        QueryResponse response =
                queries.execute("logs", language, query);
        long queryElapsedNanos = System.nanoTime() - queryStarted;
        // assertCount(response, expected);
        printResult("logs", language, response, queryElapsedNanos);
    }

    private void assertElasticsearchQuery(QueryType language) throws Exception {
        assertElasticsearchQuery(language, countQuery(language, elasticsearchIndex));
    }

    private void assertClickHouseQuery(QueryType language, String query) throws Exception {
        long expected = nativeClickHouseCount();
        long queryStarted = System.nanoTime();
        QueryResponse response =
                queries.execute("analytics", language, query);
        long queryElapsedNanos = System.nanoTime() - queryStarted;
        // assertCount(response, expected);
        printResult("analytics", language, response, queryElapsedNanos);
    }

    private void assertClickHouseQuery(QueryType language) throws Exception {
        assertClickHouseQuery(language, countQuery(language, clickHouseTable));
    }

    private void assertElasticsearchPlan(QueryType language, String query) {
        var response = queries.explain("logs", language, query);
        assertThat(response.getCalcite()).isNotNull();
        assertThat(response.getCalcite().getPhysical())
                .contains("ElasticsearchScan")
                .contains(elasticsearchIndex);
    }

    private void assertElasticsearchPlan(QueryType language) {
        assertElasticsearchPlan(language, countQuery(language, elasticsearchIndex));
    }

    private void assertClickHousePlan(QueryType language, String query) {
        var response = queries.explain("analytics", language, query);
        assertThat(response.getCalcite()).isNotNull();
        assertThat(response.getCalcite().getPhysical())
                .contains("JdbcTableScan")
                .contains(clickHouseTable);
    }

    private void assertClickHousePlan(QueryType language) {
        assertClickHousePlan(language, countQuery(language, clickHouseTable));
    }

    private long nativeElasticsearchCount() throws Exception {
        var source = properties.getDataSources().get("logs");
        String index =
                URLEncoder.encode(elasticsearchIndex, StandardCharsets.UTF_8).replace("+", "%20");
        String url = source.getUrl().replaceAll("/+$", "") + "/" + index + "/_count";
        var request = HttpRequest.newBuilder(URI.create(url)).GET();
        if (source.getUsername() != null) {
            String credentials = source.getUsername() + ":" + source.getPassword();
            request.header(
                    "Authorization",
                    "Basic "
                            + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode())
                    .as("Native Elasticsearch _count HTTP status")
                    .isEqualTo(200);
            var body = JsonParser.parseString(response.body()).getAsJsonObject();
            assertThat(body.getAsJsonObject("_shards").get("failed").getAsInt()).isZero();
            return body.get("count").getAsLong();
        }
    }

    private long nativeClickHouseCount() throws Exception {
        var source = properties.getDataSources().get("analytics");
        Properties connectionProperties = new Properties();
        connectionProperties.putAll(source.getProperties());
        if (source.getUsername() != null)
            connectionProperties.setProperty("user", source.getUsername());
        if (source.getPassword() != null)
            connectionProperties.setProperty("password", source.getPassword());
        String table = quote(source.getDatabase()) + "." + quote(clickHouseTable);
        try (var connection = DriverManager.getConnection(source.getUrl(), connectionProperties);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static String countQuery(QueryType language, String table) {
        return language == QueryType.SQL
                ? "SELECT count(*) AS total FROM " + quote(table)
                : "source=" + quote(table) + " | stats count() as total";
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("\\", "\\\\").replace("`", "``") + "`";
    }

    private static void assertCount(QueryResponse response, long expected) {
        assertThat(response.getError()).isNull();
        assertThat(response.getSchema().getColumns())
                .extracting(ExecutionEngine.Schema.Column::getName)
                .containsExactly("total");
        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().getFirst().tupleValue().get("total").longValue())
                .isEqualTo(expected);
    }

    private static void printResult(
            String source, QueryType language, QueryResponse response, long queryElapsedNanos) {
        System.out.printf(
                "=== %s / %s | query: %.3f ms ===%n",
                source, language, queryElapsedNanos / 1_000_000.0);
        System.out.println(
                new SimpleJsonResponseFormatter(Style.PRETTY)
                        .format(
                                new QueryResult(
                                        response.getSchema(),
                                        response.getResults(),
                                        response.getCursor(),
                                        LangSpec.fromLanguage(language.name()),
                                        response.getWarnings())));
    }
}
