/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.calcite.runtime.Hook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.executor.ExecutionEngine;
import org.opensearch.sql.executor.ExecutionEngine.ExplainResponse;
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
 * Read-only integration and progressive benchmark tests against the configured live data sources
 * (Elasticsearch {@code logevent-*} and ClickHouse {@code default.log_event}).
 */
@SpringBootTest(
    classes = QueryApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QueryApplicationLiveTest {
  private static final Gson GSON =
      new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();

  @Autowired private UnifiedQueryService queries;
  @Autowired private UnifiedQueryProperties properties;

  @Value("${ES_TEST_INDEX:logevent-*}")
  private String elasticsearchIndex;

  @Value("${CLICKHOUSE_TEST_TABLE:log_event}")
  private String clickHouseTable;

  private final List<Map<String, Object>> testRecords = new ArrayList<>();

  @Test
  @Order(1)
  void elasticsearchSqlExecutesRealQuery() throws Exception {
    assertElasticsearchQuery(QueryType.SQL, "SELECT * FROM `logevent-*` LIMIT 1");
  }

  @Test
  @Order(2)
  void elasticsearchPplExecutesRealQuery() throws Exception {
    assertElasticsearchQuery(
        QueryType.PPL,
        """
        source=`logevent-*`
        | where timestamp > '2026-10-01' and source = 'openwrt访问日志'
        | stats sum(size) as sum by span(timestamp, 1d) as time_bucket
        """);
  }

  @Test
  @Order(3)
  void clickHouseSqlExecutesRealQuery() throws Exception {
    assertClickHouseQuery(
        QueryType.SQL, "SELECT count(*) AS total FROM `log_event` WHERE host_ip = '192.168.100.177'");
  }

  @Test
  @Order(4)
  void clickHousePplExecutesRealQuery() throws Exception {
    assertClickHouseQuery(QueryType.PPL);
  }

  @Test
  @Order(5)
  void elasticsearchSqlUsesRealBackend() {
    assertElasticsearchPlan(QueryType.SQL);
  }

  @Test
  @Order(6)
  void elasticsearchPplUsesRealBackend() {
    assertElasticsearchPlan(QueryType.PPL);
  }

  @Test
  @Order(7)
  void clickHouseSqlUsesRealBackend() {
    assertClickHousePlan(QueryType.SQL);
  }

  @Test
  @Order(8)
  void clickHousePplUsesRealBackend() {
    assertClickHousePlan(QueryType.PPL);
  }

  @Test
  @Order(10)
  void level0FieldNamingDiscrepancyVerification() {
    // Verify that ES uses camelCase (hostIp) while ClickHouse uses snake_case (host_ip)
    assertThatThrownBy(
            () ->
                queries.execute(
                    "analytics",
                    QueryType.SQL,
                    "SELECT count(*) FROM `log_event` WHERE hostIp = '192.168.100.177'"))
        .hasMessageContaining("hostIp");

    assertThatThrownBy(
            () ->
                queries.execute(
                    "logs",
                    QueryType.SQL,
                    "SELECT count(*) FROM `logevent-*` WHERE host_ip = '192.168.100.177'"))
        .hasMessageContaining("host_ip");
  }

  @Test
  @Order(20)
  void level1BasicQueriesProjectionAndSimpleFilters() throws Exception {
    // L1.1: Full-table count
    var esSqlCount =
        runCase(
            "L1.1-ES-SQL",
            "Level 1: 基础查询与简单过滤",
            "全量计数 (COUNT(*))",
            "logs",
            QueryType.SQL,
            "SELECT count(*) AS total FROM `logevent-*`",
            true);
    var esPplCount =
        runCase(
            "L1.1-ES-PPL",
            "Level 1: 基础查询与简单过滤",
            "全量计数 (stats count())",
            "logs",
            QueryType.PPL,
            "source=`logevent-*` | stats count() as total",
            true);
    var chSqlCount =
        runCase(
            "L1.1-CH-SQL",
            "Level 1: 基础查询与简单过滤",
            "全量计数 (COUNT(*))",
            "analytics",
            QueryType.SQL,
            "SELECT count(*) AS total FROM `log_event`",
            true);
    var chPplCount =
        runCase(
            "L1.1-CH-PPL",
            "Level 1: 基础查询与简单过滤",
            "全量计数 (stats count())",
            "analytics",
            QueryType.PPL,
            "source=`log_event` | stats count() as total",
            true);

    assertThat(esSqlCount.getResults()).hasSize(1);
    assertThat(esPplCount.getResults()).hasSize(1);
    assertThat(chSqlCount.getResults()).hasSize(1);
    assertThat(chPplCount.getResults()).hasSize(1);

    // L1.2: Field projection + LIMIT / head 5
    var esSqlProj =
        runCase(
            "L1.2-ES-SQL",
            "Level 1: 基础查询与简单过滤",
            "指定字段投影与LIMIT截断",
            "logs",
            QueryType.SQL,
            "SELECT id, timestamp, hostIp, level, source, collectionMethod, inputType, size FROM `logevent-*` LIMIT 5",
            true);
    var esPplProj =
        runCase(
            "L1.2-ES-PPL",
            "Level 1: 基础查询与简单过滤",
            "指定字段投影与head截断",
            "logs",
            QueryType.PPL,
            "source=`logevent-*` | fields id, timestamp, hostIp, level, source, collectionMethod, inputType, size | head 5",
            true);
    var chSqlProj =
        runCase(
            "L1.2-CH-SQL",
            "Level 1: 基础查询与简单过滤",
            "指定字段投影与LIMIT截断",
            "analytics",
            QueryType.SQL,
            "SELECT id, timestamp, host_ip, level, source, collection_method, input_type, size FROM `log_event` LIMIT 5",
            true);
    var chPplProj =
        runCase(
            "L1.2-CH-PPL",
            "Level 1: 基础查询与简单过滤",
            "指定字段投影与head截断",
            "analytics",
            QueryType.PPL,
            "source=`log_event` | fields id, timestamp, host_ip, level, source, collection_method, input_type, size | head 5",
            true);

    assertThat(esSqlProj.getResults()).hasSize(5);
    assertThat(esPplProj.getResults()).hasSize(5);
    assertThat(chSqlProj.getResults()).hasSize(5);
    assertThat(chPplProj.getResults()).hasSize(5);

    // L1.3: High-volume single equality filter (hostIp / host_ip = '192.168.100.177')
    runCase(
        "L1.3-ES-SQL",
        "Level 1: 基础查询与简单过滤",
        "单条件精确过滤计数 (hostIp)",
        "logs",
        QueryType.SQL,
        "SELECT count(*) AS cnt FROM `logevent-*` WHERE hostIp = '192.168.100.177'",
        true);
    runCase(
        "L1.3-ES-PPL",
        "Level 1: 基础查询与简单过滤",
        "单条件精确过滤计数 (hostIp)",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where hostIp = '192.168.100.177' | stats count() as cnt",
        true);
    runCase(
        "L1.3-CH-SQL",
        "Level 1: 基础查询与简单过滤",
        "单条件精确过滤计数 (host_ip)",
        "analytics",
        QueryType.SQL,
        "SELECT count(*) AS cnt FROM `log_event` WHERE host_ip = '192.168.100.177'",
        true);
    runCase(
        "L1.3-CH-PPL",
        "Level 1: 基础查询与简单过滤",
        "单条件精确过滤计数 (host_ip)",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | where host_ip = '192.168.100.177' | stats count() as cnt",
        true);

    // L1.4: Static batch cross-engine exact match (source = 'k311x-20260928144150-system.syslog' -> 300 rows)
    var esStaticSql =
        runCase(
            "L1.4-ES-SQL",
            "Level 1: 基础查询与简单过滤",
            "静态批次跨引擎一致性校验",
            "logs",
            QueryType.SQL,
            "SELECT count(*) AS cnt FROM `logevent-*` WHERE source = 'k311x-20260928144150-system.syslog'",
            true);
    var esStaticPpl =
        runCase(
            "L1.4-ES-PPL",
            "Level 1: 基础查询与简单过滤",
            "静态批次跨引擎一致性校验",
            "logs",
            QueryType.PPL,
            "source=`logevent-*` | where source = 'k311x-20260928144150-system.syslog' | stats count() as cnt",
            true);
    var chStaticSql =
        runCase(
            "L1.4-CH-SQL",
            "Level 1: 基础查询与简单过滤",
            "静态批次跨引擎一致性校验",
            "analytics",
            QueryType.SQL,
            "SELECT count(*) AS cnt FROM `log_event` WHERE source = 'k311x-20260928144150-system.syslog'",
            true);
    var chStaticPpl =
        runCase(
            "L1.4-CH-PPL",
            "Level 1: 基础查询与简单过滤",
            "静态批次跨引擎一致性校验",
            "analytics",
            QueryType.PPL,
            "source=`log_event` | where source = 'k311x-20260928144150-system.syslog' | stats count() as cnt",
            true);

    assertThat(esStaticSql.getResults().getFirst().tupleValue().get("cnt").longValue())
        .isEqualTo(300L);
    assertThat(esStaticPpl.getResults().getFirst().tupleValue().get("cnt").longValue())
        .isEqualTo(300L);
    assertThat(chStaticSql.getResults().getFirst().tupleValue().get("cnt").longValue())
        .isEqualTo(300L);
    assertThat(chStaticPpl.getResults().getFirst().tupleValue().get("cnt").longValue())
        .isEqualTo(300L);

    // L1.5: Boolean vs UInt8 & IS NOT NULL filter
    runCase(
        "L1.5-ES-SQL",
        "Level 1: 基础查询与简单过滤",
        "布尔与非空复合过滤 (parseSuccess=true)",
        "logs",
        QueryType.SQL,
        "SELECT count(*) AS cnt FROM `logevent-*` WHERE source = 'k311x-20260928144150-system.syslog' AND parseSuccess = true AND message IS NOT NULL",
        true);
    runCase(
        "L1.5-ES-PPL",
        "Level 1: 基础查询与简单过滤",
        "布尔与非空复合过滤 (parseSuccess=true)",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where source = 'k311x-20260928144150-system.syslog' and parseSuccess = true and isnotnull(message) | stats count() as cnt",
        true);
    runCase(
        "L1.5-CH-SQL",
        "Level 1: 基础查询与简单过滤",
        "整型布尔与非空复合过滤 (parse_success=1)",
        "analytics",
        QueryType.SQL,
        "SELECT count(*) AS cnt FROM `log_event` WHERE source = 'k311x-20260928144150-system.syslog' AND parse_success = 1 AND message IS NOT NULL",
        true);
    runCase(
        "L1.5-CH-PPL",
        "Level 1: 基础查询与简单过滤",
        "整型布尔与非空复合过滤 (parse_success=1)",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | where source = 'k311x-20260928144150-system.syslog' and parse_success = 1 and isnotnull(message) | stats count() as cnt",
        true);
  }

  @Test
  @Order(30)
  void level2CompoundFiltersPatternMatchingAndScalarExpressions() throws Exception {
    // L2.1: Compound boolean logic (AND / OR / != / >)
    runCase(
        "L2.1-ES-SQL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "多条件逻辑组合与数值比较",
        "logs",
        QueryType.SQL,
        "SELECT id, hostIp, level, size FROM `logevent-*` WHERE source = 'LogHub Cloud 模块日志' AND (level = 'ERROR' OR level = 'WARN') AND size >= 200 ORDER BY id LIMIT 5",
        true);
    runCase(
        "L2.1-ES-PPL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "多条件逻辑组合与数值比较",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where source = 'LogHub Cloud 模块日志' and (level = 'ERROR' or level = 'WARN') and size >= 200 | fields id, hostIp, level, size | sort id | head 5",
        true);
    runCase(
        "L2.1-CH-SQL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "多条件逻辑组合与数值比较",
        "analytics",
        QueryType.SQL,
        "SELECT id, host_ip, level, size FROM `log_event` WHERE source = 'LogHub Cloud 模块日志' AND level = 'ERROR' AND size >= 200 ORDER BY id LIMIT 5",
        true);
    runCase(
        "L2.1-CH-PPL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "多条件逻辑组合与数值比较",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | where source = 'LogHub Cloud 模块日志' and level = 'ERROR' and size >= 200 | fields id, host_ip, level, size | sort id | head 5",
        true);

    // L2.2: IN & BETWEEN (Sarg expansion in ES vs SEARCH operator in ClickHouse)
    runCase(
        "L2.2-ES-SQL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "IN 与 BETWEEN 范围谓词下推测试",
        "logs",
        QueryType.SQL,
        "SELECT count(*) AS cnt FROM `logevent-*` WHERE source = 'LogHub Cloud 模块日志' AND level IN ('ERROR', 'WARN') AND size BETWEEN 100 AND 500",
        true);
    runCase(
        "L2.2-ES-PPL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "IN 与范围谓词下推测试",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where source = 'LogHub Cloud 模块日志' and level in ('ERROR', 'WARN') and size >= 100 and size <= 500 | stats count() as cnt",
        true);
    // On ClickHouse, test both direct SQL with LIMIT 5 (to observe SEARCH fallback without hanging)
    // and two-stage PPL where source='...' pushes down first!
    runCase(
        "L2.2-CH-SQL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "IN 与 BETWEEN 范围谓词下推边界测试",
        "analytics",
        QueryType.SQL,
        "SELECT id, level, size FROM `log_event` WHERE level IN ('ERROR', 'WARN') AND size BETWEEN 100 AND 500 LIMIT 5",
        true);
    runCase(
        "L2.2-CH-PPL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "分段管道规避 SEARCH 谓词回退测试",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | where source = 'LogHub Cloud 模块日志' | eval lvl = level, sz = size | where lvl in ('ERROR', 'WARN') and sz >= 100 and sz <= 500 | stats count() as cnt",
        true);

    // L2.3: Pattern matching LIKE / ILIKE & Full-text match
    runCase(
        "L2.3-ES-SQL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "LIKE 前缀模式匹配下推 (keyword -> wildcard)",
        "logs",
        QueryType.SQL,
        "SELECT count(*) AS cnt FROM `logevent-*` WHERE source LIKE 'Linux%'",
        true);
    runCase(
        "L2.3-ES-PPL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "ILIKE 大小写不敏感模式匹配下推 (keyword -> regexp)",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where ilike(source, 'linux%') | stats count() as cnt",
        true);
    runCase(
        "L2.3-ES-MATCH-PPL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "全文检索函数下推 (match on text)",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where source = 'Linux系统日志_副本' and match(message, 'session opened') | stats count() as cnt",
        true);
    runCase(
        "L2.3-CH-SQL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "LIKE 模式匹配本地回退与 LIMIT 早停",
        "analytics",
        QueryType.SQL,
        "SELECT id, source, host_ip FROM `log_event` WHERE source LIKE 'Linux%' LIMIT 5",
        true);
    runCase(
        "L2.3-CH-PPL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "PPL 前置过滤下推 + 本地 like 过滤",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | where source = 'k311x-20260928144150-system.syslog' | eval msg = message | where like(msg, '%systemd%') | stats count() as cnt",
        true);

    // L2.4: Scalar functions (UPPER, LENGTH, arithmetic, ROUND, CASE WHEN / if, fillnull)
    var esScalarSql =
        runCase(
            "L2.4-ES-SQL",
            "Level 2: 复合过滤、模式匹配与标量计算",
            "标量函数与 CASE WHEN 表达式计算",
            "logs",
            QueryType.SQL,
            "SELECT id, upper(level) AS lvl_up, length(source) AS src_len, round(size / 1024.0, 2) AS size_kb, CASE WHEN size >= 120 THEN 'LARGE' ELSE 'SMALL' END AS tier FROM `logevent-*` WHERE source = 'k311x-20260928144150-system.syslog' ORDER BY id LIMIT 5",
            true);
    var esScalarPpl =
        runCase(
            "L2.4-ES-PPL",
            "Level 2: 复合过滤、模式匹配与标量计算",
            "eval 标量衍生、fillnull 与条件分支",
            "logs",
            QueryType.PPL,
            "source=`logevent-*` | where source = 'k311x-20260928144150-system.syslog' | fillnull using priority = -1 | eval lvl_up = upper(level), src_len = length(source), size_kb = round(size / 1024.0, 2), tier = if(size >= 120, 'LARGE', 'SMALL') | fields id, lvl_up, src_len, size_kb, priority, tier | sort id | head 5",
            true);
    var chScalarSql =
        runCase(
            "L2.4-CH-SQL",
            "Level 2: 复合过滤、模式匹配与标量计算",
            "标量函数与 CASE WHEN 表达式计算",
            "analytics",
            QueryType.SQL,
            "SELECT id, upper(level) AS lvl_up, length(source) AS src_len, round(size / 1024.0, 2) AS size_kb, CASE WHEN size >= 120 THEN 'LARGE' ELSE 'SMALL' END AS tier FROM `log_event` WHERE source = 'k311x-20260928144150-system.syslog' ORDER BY id LIMIT 5",
            true);
    var chScalarPpl =
        runCase(
            "L2.4-CH-PPL",
            "Level 2: 复合过滤、模式匹配与标量计算",
            "eval 标量衍生、fillnull 与条件分支",
            "analytics",
            QueryType.PPL,
            "source=`log_event` | where source = 'k311x-20260928144150-system.syslog' | fillnull using priority = -1 | eval lvl_up = upper(level), src_len = length(source), size_kb = round(size / 1024.0, 2), tier = if(size >= 120, 'LARGE', 'SMALL') | fields id, lvl_up, src_len, size_kb, priority, tier | sort id | head 5",
            true);

    assertThat(esScalarSql.getResults()).hasSize(5);
    assertThat(esScalarPpl.getResults()).hasSize(5);
    assertThat(chScalarSql.getResults()).hasSize(5);
    assertThat(chScalarPpl.getResults()).hasSize(5);

    // L2.5: Date/Time functions (YEAR, MONTH, DAYOFMONTH, HOUR)
    runCase(
        "L2.5-ES-SQL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "日期时间分量提取函数 (YEAR/MONTH/DAY/HOUR)",
        "logs",
        QueryType.SQL,
        "SELECT id, timestamp, year(timestamp) AS yr, month(timestamp) AS mo, dayofmonth(timestamp) AS dy, hour(timestamp) AS hr FROM `logevent-*` WHERE source = 'k311x-20260928144150-system.syslog' ORDER BY id LIMIT 5",
        true);
    runCase(
        "L2.5-ES-PPL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "PPL eval 日期时间函数提取",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where source = 'k311x-20260928144150-system.syslog' | eval yr = year(timestamp), mo = month(timestamp), dy = dayofmonth(timestamp), hr = hour(timestamp) | fields id, timestamp, yr, mo, dy, hr | sort id | head 5",
        true);
    runCase(
        "L2.5-CH-SQL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "日期时间分量提取函数 (YEAR/MONTH/DAY/HOUR)",
        "analytics",
        QueryType.SQL,
        "SELECT id, timestamp, year(timestamp) AS yr, month(timestamp) AS mo, dayofmonth(timestamp) AS dy, hour(timestamp) AS hr FROM `log_event` WHERE source = 'k311x-20260928144150-system.syslog' ORDER BY id LIMIT 5",
        true);
    runCase(
        "L2.5-CH-PPL",
        "Level 2: 复合过滤、模式匹配与标量计算",
        "PPL eval 日期时间函数提取",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | where source = 'k311x-20260928144150-system.syslog' | eval yr = year(timestamp), mo = month(timestamp), dy = dayofmonth(timestamp), hr = hour(timestamp) | fields id, timestamp, yr, mo, dy, hr | sort id | head 5",
        true);
  }

  @Test
  @Order(40)
  void level3AggregationsGroupingMetricsAndDistinct() throws Exception {
    // L3.1: Single-dimension GROUP BY on full table
    runCase(
        "L3.1-ES-SQL",
        "Level 3: 聚合、分组、去重与条件统计",
        "全量单维分组聚合 (GROUP BY level)",
        "logs",
        QueryType.SQL,
        "SELECT level, count(*) AS cnt FROM `logevent-*` GROUP BY level ORDER BY cnt DESC",
        true);
    runCase(
        "L3.1-ES-PPL",
        "Level 3: 聚合、分组、去重与条件统计",
        "全量单维分组聚合 (stats count() by level)",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | stats count() as cnt by level | sort - cnt",
        true);
    runCase(
        "L3.1-CH-SQL",
        "Level 3: 聚合、分组、去重与条件统计",
        "全量单维分组聚合 (GROUP BY level)",
        "analytics",
        QueryType.SQL,
        "SELECT level, count(*) AS cnt FROM `log_event` GROUP BY level ORDER BY cnt DESC",
        true);
    runCase(
        "L3.1-CH-PPL",
        "Level 3: 聚合、分组、去重与条件统计",
        "全量单维分组聚合 (stats count() by level)",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | stats count() as cnt by level | sort - cnt",
        true);

    // L3.2: Multi-dimension GROUP BY + HAVING
    runCase(
        "L3.2-ES-SQL",
        "Level 3: 聚合、分组、去重与条件统计",
        "全量多维分组与 HAVING 过滤",
        "logs",
        QueryType.SQL,
        "SELECT inputType, level, count(*) AS cnt FROM `logevent-*` GROUP BY inputType, level HAVING count(*) >= 500 ORDER BY cnt DESC",
        true);
    runCase(
        "L3.2-ES-PPL",
        "Level 3: 聚合、分组、去重与条件统计",
        "全量多维分组与聚合后 where 过滤",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | stats count() as cnt by inputType, level | where cnt >= 500 | sort - cnt",
        true);
    runCase(
        "L3.2-CH-SQL",
        "Level 3: 聚合、分组、去重与条件统计",
        "全量多维分组与 HAVING 过滤",
        "analytics",
        QueryType.SQL,
        "SELECT input_type, level, count(*) AS cnt FROM `log_event` GROUP BY input_type, level HAVING count(*) >= 500 ORDER BY cnt DESC",
        true);
    runCase(
        "L3.2-CH-PPL",
        "Level 3: 聚合、分组、去重与条件统计",
        "全量多维分组与聚合后 where 过滤",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | stats count() as cnt by input_type, level | where cnt >= 500 | sort - cnt",
        true);

    // L3.3: Numeric metrics (SUM, AVG, MIN, MAX, STDDEV_SAMP) & BIGINT/INTEGER precision guard check
    var esMetricsSql =
        runCase(
            "L3.3-ES-SQL",
            "Level 3: 聚合、分组、去重与条件统计",
            "数值指标聚合与 BIGINT 精度保护验证 (size)",
            "logs",
            QueryType.SQL,
            "SELECT count(*) AS cnt, sum(size) AS sum_size, avg(size) AS avg_size, min(size) AS min_size, max(size) AS max_size FROM `logevent-*` WHERE source = 'k311x-20260928144150-system.syslog'",
            true);
    var esMetricsPpl =
        runCase(
            "L3.3-ES-PPL",
            "Level 3: 聚合、分组、去重与条件统计",
            "数值指标聚合与标准差计算 (size)",
            "logs",
            QueryType.PPL,
            "source=`logevent-*` | where source = 'k311x-20260928144150-system.syslog' | stats count() as cnt, sum(size) as sum_size, avg(size) as avg_size, min(size) as min_size, max(size) as max_size, stddev_samp(size) as std_size",
            true);
    var chMetricsSql =
        runCase(
            "L3.3-CH-SQL",
            "Level 3: 聚合、分组、去重与条件统计",
            "数值指标聚合下推验证 (sumOrNull/avgOrNull/minOrNull/maxOrNull)",
            "analytics",
            QueryType.SQL,
            "SELECT count(*) AS cnt, sum(size) AS sum_size, avg(size) AS avg_size, min(size) AS min_size, max(size) AS max_size FROM `log_event` WHERE source = 'k311x-20260928144150-system.syslog'",
            true);
    var chMetricsPpl =
        runCase(
            "L3.3-CH-PPL",
            "Level 3: 聚合、分组、去重与条件统计",
            "PPL 数值指标聚合与 CHECKED_LONG_SUM 行为验证",
            "analytics",
            QueryType.PPL,
            "source=`log_event` | where source = 'k311x-20260928144150-system.syslog' | stats count() as cnt, sum(size) as sum_size, avg(size) as avg_size, min(size) as min_size, max(size) as max_size, stddev_samp(size) as std_size",
            true);

    // Verify 100% numeric equivalence between ES and CH on the 300-row static batch!
    assertThat(esMetricsSql.getResults().getFirst().tupleValue().get("sum_size").longValue())
        .isEqualTo(chMetricsSql.getResults().getFirst().tupleValue().get("sum_size").longValue());
    assertThat(esMetricsPpl.getResults().getFirst().tupleValue().get("min_size").longValue())
        .isEqualTo(chMetricsPpl.getResults().getFirst().tupleValue().get("min_size").longValue());
    assertThat(esMetricsPpl.getResults().getFirst().tupleValue().get("max_size").longValue())
        .isEqualTo(chMetricsPpl.getResults().getFirst().tupleValue().get("max_size").longValue());

    // L3.3b: ES native pushdown of TIMESTAMP extrema and INTEGER extended_stats/percentiles on full 7.59M table
    runCase(
        "L3.3b-ES-PPL",
        "Level 3: 聚合、分组、去重与条件统计",
        "ES 原生下推指标验证 (TIMESTAMP 极值 + INTEGER 统计/百分位)",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | stats min(timestamp) as min_ts, max(timestamp) as max_ts, avg(priority) as avg_prio, stddev_pop(priority) as std_prio, percentile(priority, 95) as p95_prio",
        true);

    // L3.4: Exact vs Approximate Distinct Count
    runCase(
        "L3.4-ES-APPROX-PPL",
        "Level 3: 聚合、分组、去重与条件统计",
        "全量近似去重下推 (cardinality)",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | stats distinct_count_approx(hostIp) as approx_hosts, distinct_count_approx(source) as approx_sources",
        true);
    runCase(
        "L3.4-ES-EXACT-SQL",
        "Level 3: 聚合、分组、去重与条件统计",
        "精确去重统计 (COUNT(DISTINCT))",
        "logs",
        QueryType.SQL,
        "SELECT count(DISTINCT hostIp) AS exact_hosts, count(DISTINCT level) AS exact_levels FROM `logevent-*` WHERE source = 'LogHub Cloud 模块日志'",
        true);
    runCase(
        "L3.4-CH-SQL",
        "Level 3: 聚合、分组、去重与条件统计",
        "全量精确去重下推 (COUNT(DISTINCT))",
        "analytics",
        QueryType.SQL,
        "SELECT count(DISTINCT host_ip) AS exact_hosts, count(DISTINCT source) AS exact_sources FROM `log_event`",
        true);
    runCase(
        "L3.4-CH-PPL",
        "Level 3: 聚合、分组、去重与条件统计",
        "全量精确去重与近似去重下推 (uniqHLL12)",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | stats dc(host_ip) as exact_hosts, distinct_count_approx(host_ip) as approx_hosts, dc(source) as exact_sources, distinct_count_approx(source) as approx_sources",
        true);

    // L3.5: Conditional Aggregation (FILTER / count(eval(...)))
    runCase(
        "L3.5-ES-SQL",
        "Level 3: 聚合、分组、去重与条件统计",
        "SQL FILTER 条件聚合下推",
        "logs",
        QueryType.SQL,
        "SELECT hostIp, count(*) AS total, count(*) FILTER (WHERE level = 'ERROR') AS err_cnt, count(*) FILTER (WHERE level = 'INFO') AS info_cnt FROM `logevent-*` WHERE source = 'LogHub Cloud 模块日志' GROUP BY hostIp ORDER BY total DESC",
        true);
    runCase(
        "L3.5-ES-PPL",
        "Level 3: 聚合、分组、去重与条件统计",
        "PPL count(eval(...)) 条件聚合下推",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where source = 'LogHub Cloud 模块日志' | stats count() as total, count(eval(level = 'ERROR')) as err_cnt, count(eval(level = 'INFO')) as info_cnt by hostIp | sort - total",
        true);
    runCase(
        "L3.5-CH-SQL",
        "Level 3: 聚合、分组、去重与条件统计",
        "SQL FILTER 条件聚合 (ClickHouse 投影下推 + 本地聚合)",
        "analytics",
        QueryType.SQL,
        "SELECT host_ip, count(*) AS total, count(*) FILTER (WHERE level = 'ERROR') AS err_cnt, count(*) FILTER (WHERE level = 'INFO') AS info_cnt FROM `log_event` WHERE source = 'LogHub Cloud 模块日志' GROUP BY host_ip ORDER BY total DESC",
        true);
    runCase(
        "L3.5-CH-PPL",
        "Level 3: 聚合、分组、去重与条件统计",
        "PPL count(eval(...)) 条件聚合",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | where source = 'LogHub Cloud 模块日志' | stats count() as total, count(eval(level = 'ERROR')) as err_cnt, count(eval(level = 'INFO')) as info_cnt by host_ip | sort - total",
        true);
  }

  @Test
  @Order(50)
  void level4SortingPaginationTopRareAndDedup() throws Exception {
    // L4.1: Multi-column sort + offset/limit
    var esSortSql =
        runCase(
            "L4.1-ES-SQL",
            "Level 4: 排序、分页、Top/Rare 与去重",
            "多字段排序与分页 (ORDER BY ... LIMIT ... OFFSET)",
            "logs",
            QueryType.SQL,
            "SELECT id, timestamp, hostIp, size FROM `logevent-*` WHERE source = 'k311x-20260928144150-system.syslog' ORDER BY size DESC, id ASC LIMIT 5 OFFSET 3",
            true);
    var esSortPpl =
        runCase(
            "L4.1-ES-PPL",
            "Level 4: 排序、分页、Top/Rare 与去重",
            "PPL 多字段排序与分页 (sort - size, + id | head 5 from 3)",
            "logs",
            QueryType.PPL,
            "source=`logevent-*` | where source = 'k311x-20260928144150-system.syslog' | sort - size, + id | head 5 from 3 | fields id, timestamp, hostIp, size",
            true);
    var chSortSql =
        runCase(
            "L4.1-CH-SQL",
            "Level 4: 排序、分页、Top/Rare 与去重",
            "多字段排序与分页 (ORDER BY ... LIMIT ... OFFSET)",
            "analytics",
            QueryType.SQL,
            "SELECT id, timestamp, host_ip, size FROM `log_event` WHERE source = 'k311x-20260928144150-system.syslog' ORDER BY size DESC, id ASC LIMIT 5 OFFSET 3",
            true);
    var chSortPpl =
        runCase(
            "L4.1-CH-PPL",
            "Level 4: 排序、分页、Top/Rare 与去重",
            "PPL 多字段排序与分页 (sort - size, + id | head 5 from 3)",
            "analytics",
            QueryType.PPL,
            "source=`log_event` | where source = 'k311x-20260928144150-system.syslog' | sort - size, + id | head 5 from 3 | fields id, timestamp, host_ip, size",
            true);

    assertThat(esSortSql.getResults()).hasSize(5);
    assertThat(esSortPpl.getResults()).hasSize(5);
    assertThat(chSortSql.getResults()).hasSize(5);
    assertThat(chSortPpl.getResults()).hasSize(5);

    // L4.2: Top / Rare
    runCase(
        "L4.2-ES-TOP-PPL",
        "Level 4: 排序、分页、Top/Rare 与去重",
        "频次最高 Top-N 统计 (top)",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where source = 'LogHub Cloud 模块日志' | top 3 level by hostIp",
        true);
    runCase(
        "L4.2-ES-RARE-PPL",
        "Level 4: 排序、分页、Top/Rare 与去重",
        "频次最低 Rare-N 统计 (rare)",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where source = 'LogHub Cloud 模块日志' | rare 3 level",
        true);
    runCase(
        "L4.2-CH-TOP-PPL",
        "Level 4: 排序、分页、Top/Rare 与去重",
        "频次最高 Top-N 统计 (top)",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | where source = 'LogHub Cloud 模块日志' | top 3 level by host_ip",
        true);
    runCase(
        "L4.2-CH-RARE-PPL",
        "Level 4: 排序、分页、Top/Rare 与去重",
        "频次最低 Rare-N 统计 (rare)",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | where source = 'LogHub Cloud 模块日志' | rare 3 level",
        true);

    // L4.3: Dedup
    runCase(
        "L4.3-ES-PPL",
        "Level 4: 排序、分页、Top/Rare 与去重",
        "按分组保留前 N 条去重 (dedup 2 level)",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where source = 'LogHub Cloud 模块日志' and hostIp = '127.0.0.1' | sort - timestamp, + id | dedup 2 level | fields id, timestamp, level, size",
        true);
    runCase(
        "L4.3-CH-PPL",
        "Level 4: 排序、分页、Top/Rare 与去重",
        "按分组保留前 N 条去重 (dedup 2 level)",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | where source = 'LogHub Cloud 模块日志' and host_ip = '127.0.0.1' | sort - timestamp, + id | dedup 2 level | fields id, timestamp, level, size",
        true);
  }

  @Test
  @Order(60)
  void level5ComplexAnalyticsSpanNestedWindowSubqueryAndPipeline() throws Exception {
    // L5.1: Time-series span bucketing
    runCase(
        "L5.1-ES-FULL-PPL",
        "Level 5: 复杂分析与高阶查询",
        "全量索引时间窗口分桶下推 (span 1d -> composite date_histogram)",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where timestamp >= '2026-10-11 00:00:00' and timestamp < '2026-10-17 00:00:00' | stats count() as cnt by span(timestamp, 1d) as time_bucket | sort time_bucket",
        true);
    runCase(
        "L5.1-ES-BATCH-PPL",
        "Level 5: 复杂分析与高阶查询",
        "小时级时间分桶聚合 (span 1h)",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where source = 'k311x-20260928144150-system.syslog' | stats count() as cnt by span(timestamp, 1h) as time_bucket | sort time_bucket",
        true);
    runCase(
        "L5.1-CH-BATCH-PPL",
        "Level 5: 复杂分析与高阶查询",
        "小时级时间分桶聚合 (span 1h 本地计算)",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | where source = 'k311x-20260928144150-system.syslog' | stats count() as cnt by span(timestamp, 1h) as time_bucket | sort time_bucket",
        true);

    // L5.2: Numeric span bucketing
    var esNumSpan =
        runCase(
            "L5.2-ES-PPL",
            "Level 5: 复杂分析与高阶查询",
            "数值分桶聚合下推 (span(size, 50) -> Painless long script)",
            "logs",
            QueryType.PPL,
            "source=`logevent-*` | where source = 'k311x-20260928144150-system.syslog' | stats count() as cnt by span(size, 50) as size_bucket | sort size_bucket",
            true);
    var chNumSpan =
        runCase(
            "L5.2-CH-PPL",
            "Level 5: 复杂分析与高阶查询",
            "数值分桶聚合 (span(size, 50) 本地计算)",
            "analytics",
            QueryType.PPL,
            "source=`log_event` | where source = 'k311x-20260928144150-system.syslog' | stats count() as cnt by span(size, 50) as size_bucket | sort size_bucket",
            true);
    assertThat(esNumSpan.getResults().size()).isEqualTo(chNumSpan.getResults().size());

    // L5.3: Nested Object Path (ES) vs JSON spath Extraction (CH) & Regex (rex)
    runCase(
        "L5.3-ES-NESTED-PPL",
        "Level 5: 复杂分析与高阶查询",
        "ES 嵌套对象路径分组下推 (extracted.process.name)",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where source = 'Linux系统日志_副本' | stats count() as cnt by extracted.process.name | sort - cnt | head 5",
        true);
    runCase(
        "L5.3-CH-SPATH-PPL",
        "Level 5: 复杂分析与高阶查询",
        "ClickHouse JSON 字符串 spath 解析与分组 (extracted_fields)",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | where source = 'Linux系统日志_副本' | spath input=extracted_fields output=proc_name path=process.name | stats count() as cnt by proc_name | sort - cnt | head 5",
        true);
    runCase(
        "L5.3-ES-REX-PPL",
        "Level 5: 复杂分析与高阶查询",
        "PPL rex 正则提取字段与聚合",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where source = 'k311x-20260928144150-system.syslog' | rex field=message \"(?<proc>[a-zA-Z0-9_()-]+)\\[\"| stats count() as cnt by proc | sort - cnt | head 5",
        true);
    runCase(
        "L5.3-CH-REX-PPL",
        "Level 5: 复杂分析与高阶查询",
        "PPL rex 正则提取字段与聚合",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | where source = 'k311x-20260928144150-system.syslog' | rex field=message \"(?<proc>[a-zA-Z0-9_()-]+)\\[\"| stats count() as cnt by proc | sort - cnt | head 5",
        true);

    // L5.4: Window functions (ROW_NUMBER() OVER & PPL eventstats)
    runCase(
        "L5.4-ES-WIN-SQL",
        "Level 5: 复杂分析与高阶查询",
        "SQL 窗口函数 (ROW_NUMBER() OVER PARTITION BY)",
        "logs",
        QueryType.SQL,
        "SELECT id, level, size, row_number() OVER (PARTITION BY level ORDER BY size DESC, id ASC) AS rn FROM `logevent-*` WHERE source = 'LogHub Cloud 模块日志' AND hostIp = '127.0.0.1' ORDER BY level, rn LIMIT 6",
        true);
    runCase(
        "L5.4-ES-EVENTSTATS-PPL",
        "Level 5: 复杂分析与高阶查询",
        "PPL 窗口增强分析 (eventstats)",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where source = 'LogHub Cloud 模块日志' and hostIp = '127.0.0.1' | eventstats count() as lvl_total, max(size) as lvl_max by level | fields id, level, size, lvl_total, lvl_max | sort - size, + id | head 5",
        true);
    runCase(
        "L5.4-CH-WIN-SQL",
        "Level 5: 复杂分析与高阶查询",
        "SQL 窗口函数 (ROW_NUMBER() OVER PARTITION BY)",
        "analytics",
        QueryType.SQL,
        "SELECT id, level, size, row_number() OVER (PARTITION BY level ORDER BY size DESC, id ASC) AS rn FROM `log_event` WHERE source = 'LogHub Cloud 模块日志' AND host_ip = '127.0.0.1' ORDER BY level, rn LIMIT 6",
        true);
    runCase(
        "L5.4-CH-EVENTSTATS-PPL",
        "Level 5: 复杂分析与高阶查询",
        "PPL 窗口增强分析 (eventstats)",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | where source = 'LogHub Cloud 模块日志' and host_ip = '127.0.0.1' | eventstats count() as lvl_total, max(size) as lvl_max by level | fields id, level, size, lvl_total, lvl_max | sort - size, + id | head 5",
        true);

    // L5.5: Subquery & Self-Join & Multi-stage Pipeline
    runCase(
        "L5.5-ES-JOIN-SQL",
        "Level 5: 复杂分析与高阶查询",
        "子查询聚合与 INNER JOIN 自关联分析",
        "logs",
        QueryType.SQL,
        "SELECT a.level AS level, a.cnt_local AS cnt_local, b.cnt_lan AS cnt_lan FROM (SELECT level, count(*) AS cnt_local FROM `logevent-*` WHERE source = 'LogHub Cloud 模块日志' AND hostIp = '127.0.0.1' GROUP BY level) a INNER JOIN (SELECT level, count(*) AS cnt_lan FROM `logevent-*` WHERE source = 'LogHub Cloud 模块日志' AND hostIp = '192.168.100.1' GROUP BY level) b ON a.level = b.level ORDER BY cnt_local DESC",
        true);
    runCase(
        "L5.5-CH-JOIN-SQL",
        "Level 5: 复杂分析与高阶查询",
        "子查询聚合与 INNER JOIN 自关联下推分析",
        "analytics",
        QueryType.SQL,
        "SELECT a.level AS level, a.cnt_local AS cnt_local, b.cnt_lan AS cnt_lan FROM (SELECT level, count(*) AS cnt_local FROM `log_event` WHERE source = 'LogHub Cloud 模块日志' AND host_ip = '127.0.0.1' GROUP BY level) a INNER JOIN (SELECT level, count(*) AS cnt_lan FROM `log_event` WHERE source = 'LogHub Cloud 模块日志' AND host_ip = '192.168.100.1' GROUP BY level) b ON a.level = b.level ORDER BY cnt_local DESC",
        true);
    runCase(
        "L5.5-ES-PIPE-PPL",
        "Level 5: 复杂分析与高阶查询",
        "多阶段管道组合分析 (where -> stats -> eval -> where -> sort -> fields)",
        "logs",
        QueryType.PPL,
        "source=`logevent-*` | where source = 'LogHub Cloud 模块日志' | stats count() as total, count(eval(level = 'ERROR')) as err_cnt, count(eval(level = 'WARN')) as warn_cnt by hostIp | eval problem_cnt = err_cnt + warn_cnt, err_pct = round(err_cnt * 100.0 / total, 2) | where total >= 1000 | sort - err_pct | fields hostIp, total, err_cnt, warn_cnt, problem_cnt, err_pct",
        true);
    runCase(
        "L5.5-CH-PIPE-PPL",
        "Level 5: 复杂分析与高阶查询",
        "多阶段管道组合分析 (where -> stats -> eval -> where -> sort -> fields)",
        "analytics",
        QueryType.PPL,
        "source=`log_event` | where source = 'LogHub Cloud 模块日志' | stats count() as total, count(eval(level = 'ERROR')) as err_cnt, count(eval(level = 'WARN')) as warn_cnt by host_ip | eval problem_cnt = err_cnt + warn_cnt, err_pct = round(err_cnt * 100.0 / total, 2) | where total >= 1000 | sort - err_pct | fields host_ip, total, err_cnt, warn_cnt, problem_cnt, err_pct",
        true);
  }

  @AfterAll
  void writeBenchmarkReport() throws Exception {
    if (testRecords.isEmpty()) return;
    Path reportJson = Path.of("build/reports/live-test-results.json");
    Files.createDirectories(reportJson.getParent());
    Files.writeString(reportJson, GSON.toJson(testRecords), StandardCharsets.UTF_8);
  }

  private QueryResponse runCase(
      String id,
      String level,
      String title,
      String source,
      QueryType language,
      String query,
      boolean executeQuery) {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("id", id);
    record.put("level", level);
    record.put("title", title);
    record.put("source", source);
    record.put("language", language.name());
    record.put("query", query);

    List<String> nativeSqlPlans = new ArrayList<>();
    long explainStart = System.nanoTime();
    ExplainResponse explain;
    try (var hook =
        Hook.QUERY_PLAN.addThread((Object sql) -> nativeSqlPlans.add(String.valueOf(sql)))) {
      explain = queries.explain(source, language, query);
    }
    double explainMs = (System.nanoTime() - explainStart) / 1_000_000.0;
    String logical = explain.getCalcite() != null ? explain.getCalcite().getLogical() : "";
    String physical = explain.getCalcite() != null ? explain.getCalcite().getPhysical() : "";
    record.put("explainMs", Math.round(explainMs * 1000.0) / 1000.0);
    record.put("logicalPlan", logical);
    record.put("physicalPlan", physical);
    record.put("nativeSqlPlans", nativeSqlPlans);

    QueryResponse response = null;
    if (executeQuery) {
      long execStart = System.nanoTime();
      response = queries.execute(source, language, query);
      long execNanos = System.nanoTime() - execStart;
      double execMs = execNanos / 1_000_000.0;
      assertThat(response.getError()).as("Query error for " + id).isNull();
      record.put("execMs", Math.round(execMs * 1000.0) / 1000.0);
      record.put("rowCount", response.getResults().size());
      String formatted =
          new SimpleJsonResponseFormatter(Style.PRETTY)
              .format(
                  new QueryResult(
                      response.getSchema(),
                      response.getResults().subList(0, Math.min(5, response.getResults().size())),
                      response.getCursor(),
                      LangSpec.fromLanguage(language.name()),
                      response.getWarnings()));
      record.put("sampleResult", JsonParser.parseString(formatted));
      System.out.printf(
          "=== [%s] %s | %s / %s | explain: %.2f ms | exec: %.2f ms | rows: %d ===%n",
          id, title, source, language, explainMs, execMs, response.getResults().size());
    }
    testRecords.add(record);
    return response;
  }

  private void assertElasticsearchQuery(QueryType language, String query) throws Exception {
    long queryStarted = System.nanoTime();
    QueryResponse response = queries.execute("logs", language, query);
    long queryElapsedNanos = System.nanoTime() - queryStarted;
    assertThat(response.getError()).isNull();
    printResult("logs", language, response, queryElapsedNanos);
  }

  private void assertElasticsearchQuery(QueryType language) throws Exception {
    long expected = nativeElasticsearchCount();
    long queryStarted = System.nanoTime();
    QueryResponse response =
        queries.execute("logs", language, countQuery(language, elasticsearchIndex));
    long queryElapsedNanos = System.nanoTime() - queryStarted;
    assertCount(response, expected);
    printResult("logs", language, response, queryElapsedNanos);
  }

  private void assertClickHouseQuery(QueryType language, String query) throws Exception {
    long queryStarted = System.nanoTime();
    QueryResponse response = queries.execute("analytics", language, query);
    long queryElapsedNanos = System.nanoTime() - queryStarted;
    assertThat(response.getError()).isNull();
    printResult("analytics", language, response, queryElapsedNanos);
  }

  private void assertClickHouseQuery(QueryType language) throws Exception {
    long expected = nativeClickHouseCount();
    long queryStarted = System.nanoTime();
    QueryResponse response =
        queries.execute("analytics", language, countQuery(language, clickHouseTable));
    long queryElapsedNanos = System.nanoTime() - queryStarted;
    assertCount(response, expected);
    printResult("analytics", language, response, queryElapsedNanos);
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
      assertThat(response.statusCode()).as("Native Elasticsearch _count HTTP status").isEqualTo(200);
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
        "=== %s / %s | query: %.3f ms ===%n", source, language, queryElapsedNanos / 1_000_000.0);
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
