# Elasticsearch / ClickHouse 搜索示例

这个 Java 21、Spring Boot 3 示例通过 `UnifiedQueryService` 查询 Elasticsearch 和 ClickHouse，提供统一 `/search` HTTP 接口、后台分页、时间分布及流式 CSV 导出。运行 `QueryApplication` 或打包后的 JAR 启动 Web 服务，默认端口 `8080`。

## 搜索前端

访问 `http://localhost:8080/` 使用 Lens 搜索页面，源码及前端验证说明见 [`frontend/README.md`](frontend/README.md)。页面提交 SQL/PPL 原文、时间和结构化筛选；表名以语句为准，默认模板不自动添加 `LIMIT/head`。分页在后台执行，翻页只请求明细并保留图表。图表直接使用后台桶数据，点击桶缩小时间范围。查询历史保存完整条件，导出复用最后一次成功查询的固定时间与筛选。

`GET /ui/config` 仅提供数据源名称、类型、默认表、时间字段/时区及显式配置的源字段，并提供顶层 `maxPageSize`，不暴露连接地址和凭据。默认表为 `logevent-*` / `log_event`，可通过 `ES_TEST_INDEX` / `CLICKHOUSE_TEST_TABLE` 或 `unified-query.ui.default-tables` 覆盖。它们仅用于生成快捷语句；界面没有独立的表名输入框。字段面板区分源字段与结果列/别名，结果别名不能直接生成源字段筛选。

`GET /ui/ppl/grammar` 返回当前引擎的 PPL 语法包，供前端在浏览器中计算语法提示。响应沿用 `/_plugins/_ppl/_grammar` 的 JSON 结构，包括 ANTLR 版本、语法 hash、lexer/parser ATN、词表及补全规则；无需查询参数，也不访问 Elasticsearch 或 ClickHouse。表名和源字段提示使用 `/ui/config` 的显式配置。

构建需要 Node.js 20.19+ 或 22.12+ 和 npm。`:spring-boot-example:bootJar` 自动安装锁定依赖、构建前端并打包到 JAR；运行 JAR 不需要 Node.js。IDE 直接启动前执行 `:spring-boot-example:processResources`，或独立运行 Vite。请求和异步任务链接支持 Spring servlet context path。

## 代码结构

```text
org.opensearch.sql.example
├── QueryApplication                # Spring Boot 入口
├── controller                      # HTTP 路由、状态码、JSON/附件响应
│   ├── SearchController
│   ├── SearchUiController
│   ├── QueryExportController
│   └── QueryExportTaskController
├── service
│   ├── SearchService               # 时间解析、分页与图表编排
│   ├── SearchTimeResolver
│   ├── QueryExportService          # 导出任务、容量、文件生命周期
│   └── QueryExportRunner           # 流式逐行写入
├── model
│   ├── SearchRequest / SearchResponse / QueryColumn
│   ├── ExportRequest / ExportSubmission
│   └── ExportTaskStatus / ExportDownload
└── config
    ├── SearchProperties
    └── QueryExportProperties
```

Controller 适配 HTTP；`SearchService` 协调页面策略；统一 API 用类型化表达式和计划节点绑定源字段、组合条件、生成结果窗口或时间聚合并执行。同步和异步导出共享相同条件绑定与流式写入能力。

## 配置与启动

内置配置在 `src/main/resources/application.yml`，默认数据源为 `logs`：

| 数据源 | 后端 | 默认连接 | 默认用户 |
| --- | --- | --- | --- |
| `logs` | Elasticsearch | `http://192.168.100.212:9200` | `elastic` |
| `analytics` | ClickHouse | `jdbc:clickhouse://192.168.100.212:18123/default` | `admin` |

通过 `ES_PASSWORD` / `CLICKHOUSE_PASSWORD` 环境变量设置密码，例如在 Bash 隐藏输入：

```bash
read -r -s -p 'Elasticsearch password: ' ES_PASSWORD
printf '\n'
read -r -s -p 'ClickHouse password: ' CLICKHOUSE_PASSWORD
printf '\n'
export ES_PASSWORD CLICKHOUSE_PASSWORD
```

其他连接变量包括 `ES_URL`、`ES_USER`、`CLICKHOUSE_JDBC_URL`、`CLICKHOUSE_USER` 和 `CLICKHOUSE_DATABASE`。切换 CH 数据库时同时调整 JDBC URL 与数据库配置。解析和函数语义沿用本项目 SQL/PPL 引擎，不直接接受 ClickHouse 原生 SQL 方言。

搜索限制和 UI 字段配置示例：

```yaml
unified-query:
  search:
    max-page-size: 1000       # 单页上限；未传 page 时默认第一页、50 条
    max-offset: 100000       # (number - 1) * size 的上限
    max-buckets: 2000        # 时间分布桶数上限
    default-time-zone: Asia/Shanghai
    time-fields:
      logs: timestamp
      analytics: timestamp
  ui:
    default-tables:
      logs: logevent-*
      analytics: log_event
    source-fields:
      logs:
        - name: timestamp
          type: timestamp
        - name: level
          type: string
      analytics:
        - name: id
          type: long
        - name: message
          type: string
```

`timeRange.field` 可显式传入；省略时使用所选数据源的 `time-fields` 配置，否则报错。时区省略时使用 `default-time-zone`。UI `source-fields` 是源字段显示和输入类型配置，不替代后台元数据校验。没有配置源字段时仍可显式填写字段名；结果别名只展示。

ES 映射无法识别标量/数组差异，可按实际数据声明数组字段：

```yaml
unified-query:
  data-sources:
    logs:
      field-types:
        tags: array<keyword>
```

通过 `--spring.config.additional-location=file:/path/to/application.yml` 加载额外配置。构建并启动：

```bash
./gradlew :spring-boot-example:bootJar
JAR=spring-boot-example/build/libs/spring-boot-example-3.10.0.0-SNAPSHOT.jar
java -jar "$JAR" --server.port=8080
```

IDE 可运行 `org.opensearch.sql.example.QueryApplication`，设置相同环境变量，无需程序参数。

## 搜索 HTTP 协议

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/search` | 明细、后台时间分布或两者 |
| POST | `/search/export` | 同步 CSV 导出 |
| POST | `/search/export/tasks` | 创建异步导出任务 |
| GET | `/search/export/tasks/{id}` | 查看任务进度 |
| GET | `/search/export/tasks/{id}/file` | 下载成功文件 |
| GET | `/ui/config` | 页面初始化显示配置 |
| GET | `/ui/ppl/grammar` | 当前引擎的 PPL 语法包 |

请求使用 `Content-Type: application/json`。`query` 必填且非空；`source` 省略时使用默认数据源；`language` 大小写不敏感，省略时为 SQL。

```bash
curl --fail --request POST 'http://localhost:8080/search' \
  --header 'Content-Type: application/json' \
  --data-raw '{"source":"logs","language":"SQL","query":"SELECT timestamp,level,message FROM `your_index` ORDER BY timestamp","timeRange":{"field":"timestamp","from":"now-1d","to":"now","timeZone":"Asia/Shanghai"},"filters":[{"field":"level","operator":"eq","value":"ERROR"}],"page":{"number":1,"size":50},"histogram":{"interval":"auto"},"include":["rows","histogram"]}'
```

将 `your_index` 替换为实际索引。响应形状如下：

```json
{
  "resolvedTimeRange": {
    "field": "timestamp",
    "from": "2026-10-17T08:00:00Z",
    "to": "2026-10-18T08:00:00Z",
    "timeZone": "Asia/Shanghai"
  },
  "rows": {
    "schema": [{"name": "timestamp", "type": "timestamp"}, {"name": "level", "type": "string"}, {"name": "message", "type": "string"}],
    "datarows": [["2026-10-17T08:10:00Z", "ERROR", "example"]]
  },
  "page": {"number": 1, "size": 50, "hasNext": false},
  "histogram": {
    "status": "ok",
    "interval": "1h",
    "timeZone": "Asia/Shanghai",
    "buckets": [{"from": "2026-10-17T08:00:00Z", "to": "2026-10-17T09:00:00Z", "count": 1}]
  },
  "warnings": []
}
```

上例仅展示一个桶；实际成功分布补齐整个范围的空桶。`schema` 使用查询别名，`datarows` 按列顺序保留 null、对象、数组和数值；空结果仍带 schema。未请求的结果部分不返回。响应不提供总命中数，不用当前页行数代替总数。

| 参数 | 规则 |
| --- | --- |
| `timeRange` | 可选；未传表示不增加外部时间限制。采用 `[from,to)`，相对端点从同一个当前时间解析。时间输入支持毫秒，亚毫秒绝对值返回 400；系统生成的 `now` 统一到毫秒。 |
| `filters` | 默认空数组；`field/operator/value` 按 AND 组合，绑定源数据字段。 |
| `page` | 明细默认第一页、每页 50 条（受配置上限限制）；正整数页码，受每页和最大偏移限制。 |
| `histogram.interval` | `auto`、`1m/minute`、`1h/hour`、`1d/day`。 |
| `include` | 默认 `["rows"]`；可选 `["histogram"]` 或 `["rows","histogram"]`。 |

相对时间支持 `now`、`now-15m`、`now-1h`、`now-1d`、`now-1w`、`now-1M` 以及按分钟/小时/自然日取整的 `/m`、`/h`、`/d`。绝对时间支持 ISO 时间或按业务时区解释的本地日期/日期时间。前端绝对时间输入要求显式时区。客户端必须将 `resolvedTimeRange` 原样用于翻页、图表刷新和导出；重新搜索才重新解析 `now`。

支持的筛选操作为 `eq/ne/gt/gte/lt/lte/in/is_null/is_not_null/contains`；`in` 使用数组，空值条件不需要 value，其他操作需提供非 null 值。`contains` 是字面字符串包含，用户输入的 `%` / `_` 不解释为 SQL 通配符。

外部条件在源数据读取之后、用户原有过滤/计算/聚合之前执行；保留原语句 OR 分组、HAVING 和 PPL stats 后 where 的位置。条件冲突返回空结果。不存在的字段、错误类型、无法明确绑定条件的连接/多分支查询直接报错，不猜测筛选目标。时间字段无需出现在最终投影中。

分页在完整用户结果外执行，多取一行判断 `hasNext`，保留手写 `LIMIT/head` 的边界与原有排序。没有稳定排序时返回 `UNSTABLE_ORDER` 提示；持续写入下的有界页码不保证跨请求快照一致性。底层 PIT/JDBC 分页不是 HTTP 跨请求游标。

普通单表明细的时间图在分页前独立后台聚合，使用相同固定时间和筛选。自动粒度：一小时内用分钟，一天内用小时，更长范围用自然日。桶边界按业务时区对齐，首尾桶仅统计范围内的数据，返回完整自然桶的 `from/to`。全部时间、聚合/去重/连接/窗口等不支持形状返回 `histogram.status="unsupported"` 及 reason。图表执行失败或桶数过多返回 `status="error"` 及明确原因，明细仍可成功；桶数过多时缩小范围或调整粒度。非法 interval 属于请求错误。独立图表刷新可使用 `include:["histogram"]`。

ClickHouse PPL 明细示例：

```bash
curl --fail --request POST 'http://localhost:8080/search' \
  --header 'Content-Type: application/json' \
  --data-raw '{"source":"analytics","language":"PPL","query":"source=your_table | fields id,timestamp,message | head 10","page":{"number":1,"size":5}}'
```

非法请求、未知数据源及语法/语义错误返回 `400`；明确权限不足和资源限额分别返回 `403/429`；执行故障返回 `500`。明细执行故障不会返回假成功或全零图表。

## 同步与异步 CSV 导出

导出只接受 `source、language、query、timeRange、filters`，拒绝 UI 的 `page/histogram/include`。将当前成功搜索的绝对时间与完整筛选复制到请求中；相对时间若直接提交导出，也会在任务提交时解析并冻结。以下示例使用固定范围：

```bash
curl --fail --output result.csv \
  --request POST 'http://localhost:8080/search/export' \
  --header 'Content-Type: application/json' \
  --data-raw '{"source":"logs","language":"SQL","query":"SELECT timestamp,level,message FROM `your_index`","timeRange":{"field":"timestamp","from":"2026-10-17T08:00:00Z","to":"2026-10-18T08:00:00Z","timeZone":"Asia/Shanghai"},"filters":[{"field":"level","operator":"eq","value":"ERROR"}]}'
```

同步导出通过共享 runner 逐行写入受限临时文件，完成后输出 `text/csv;charset=UTF-8`，带 `Content-Disposition: attachment; filename="result.csv"`；不将全量结果收集进内存。默认使用 `CsvResponseFormatter` 的 `sanitize=true`，不添加 BOM。下载关闭后清理同步任务与临时文件。

异步提交相同条件，仅将路径改为 `/search/export/tasks`：

```bash
curl --fail --request POST 'http://localhost:8080/search/export/tasks' \
  --header 'Content-Type: application/json' \
  --data-raw '{"source":"analytics","language":"PPL","query":"source=your_table | fields timestamp,message","timeRange":{"field":"timestamp","from":"2026-10-17T08:00:00Z","to":"2026-10-18T08:00:00Z","timeZone":"Asia/Shanghai"},"filters":[]}'
# HTTP 202，响应 {"id":"..."}，Location 为带应用上下文的任务状态 URL

curl --fail 'http://localhost:8080/search/export/tasks/<id>'
# {"id":"...","state":"RUNNING","exportedRows":1000,"fileSizeBytes":65536,
#  "submittedAtMillis":...,"completedAtMillis":null,"error":null}

curl --fail --output result.csv 'http://localhost:8080/search/export/tasks/<id>/file'
# 仅 state=SUCCEEDED 时下载
```

- 状态为 `PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED`；排队中的任务也可能显示 RUNNING。提交成功始终返回 202 和 ID，即使随后立即失败。
- 每 1,000 行或写入期间约 250ms 刷新进度，完成时更新精确值。空结果仍有 CSV 表头；失败删除部分文件，文件大小归零，error 给出原因。
- 文件未成功返回 `409`；未知/过期任务 `404`；格式错误 `400`；其他用户任务 `403`；容量已满 `429`。
- 同步/异步使用同一组类型化约束、逐行转换和写盘；ES 使用 PIT + search_after（分组使用 composite 分页），CH 使用 JDBC 迭代。导出不受交互查询 `plugins.query.size_limit` 输出上限约束，保留 SQL LIMIT / PPL head。
- 本地排序、连接、聚合仍可能使用较多内存。`timewrap` 需要完整结果透视，当前流式导出明确失败；流式读取不代表任意算子都有固定内存上界。

容量与清理默认配置：

```yaml
unified-query:
  export:
    directory: /tmp/query-exports
    keep-alive: 1h
    workers: 2
    queue-capacity: 16
    max-tasks: 100
    max-file-bytes: 10737418240 # 10 GiB，超限失败，不截断成成功文件
```

目录默认位于 JVM 临时目录。TTL 从终态开始，过期后约一秒清理，已打开下载可完成。正常关闭取消任务并清理文件；异常退出可能遗留 `session-*` 目录。元数据在本机内存、文件在本机磁盘，没有重启恢复或跨实例任务查询，多实例部署应路由至原实例。

HTTP Principal 绑定异步任务归属；本例没有认证时共享匿名身份。Starter 自动提供 `LocalQueryJobService`，业务可提供自己的 `QueryJobService` bean。长期保留的任务用 `submitAsync`，原 submit 仍保留同步等待/超时返回 ID 的语义。

## 在业务代码中调用

注入 `UnifiedQueryService`，用同一个不可变执行模型调用 `execute/explain/stream`。库入口只接受已解析的绝对时间；相对时间和 UI include 策略属于宿主服务：

```java
var request = new QueryExecutionRequest(
    "logs", QueryType.SQL, "SELECT message FROM application_logs ORDER BY timestamp",
    new QueryExecutionRequest.TimeRange(
        "timestamp", Instant.parse("2026-10-17T08:00:00Z"),
        Instant.parse("2026-10-18T08:00:00Z"), ZoneId.of("Asia/Shanghai")),
    List.of(new QueryExecutionRequest.FieldFilter("level", "eq", "ERROR")),
    null, null);

var rows = queries.execute(request.withWindow(new QueryExecutionRequest.ResultWindow(0, 51)));
var plan = queries.explain(request);
queries.stream(request, consumer, cancellation);
var histogram = queries.execute(request.withHistogram(
    new QueryExecutionRequest.Histogram("timestamp", "1h", ZoneId.of("Asia/Shanghai"))));
```

宿主裁掉第 51 条哨兵行并计算 hasNext。`consumer` / `cancellation` 分别为 `QueryRowConsumer` / `QueryCancellation`；资源在返回、取消或异常时释放。引擎响应类型仍为 `ExecutionEngine.QueryResponse`。每次请求选一个数据源；共享计划入口 `UnifiedQueryPlanner.plan(QueryExecutionRequest)` 将外部条件绑定在唯一源表 scan 之后。更多后端行为见 [embedded-query-backends.md](../docs/dev/embedded-query-backends.md)。

## 真实数据源 JUnit 测试

现有 [QueryApplicationLiveTest](src/test/java/org/opensearch/sql/example/QueryApplicationLiveTest.java) 可作为可选的真实数据源检查，在 IDE 或仓库根目录运行。它会连接实际的 Elasticsearch 和 ClickHouse；运行前请配置连接环境变量，并核对测试中使用的索引、表、字段及查询条件是否适合目标数据集。

```bash
./gradlew :spring-boot-example:test \
  --tests 'org.opensearch.sql.example.QueryApplicationLiveTest'
```

具体检查内容以测试类为准。测试结果可在 IDE 测试控制台或 `spring-boot-example/build/reports/tests/test/index.html` 中查看。

## 百万行导出验收（2026-10-18）

通过提交任务、查询状态和下载文件三个 HTTP 接口，验证真实 ES/CH 的 SQL/PPL 百万行导出。查询投影为 `id,timestamp,message`，单并发执行。使用流式 CSV 解析器核对数据行数（排除表头）、每行列数，并检查文件字节数和 SHA-256。

环境：Java 21.0.12.1，G1，`-Xms64m -Xmx128m`，一个导出 worker，每条路径先预热 1,000 行，之后只运行一次百万行投影导出。ES 可用数据 7,597,050 行，CH 可用数据 5,306,448 行；均为真实数据，没有生成或补写数据。

| 路径 | 导出耗时 | CSV 数据行数 | 文件大小 MiB | 采样堆峰值 MiB | 采样 RSS 峰值 MiB | GC 后堆：前 → 后 MiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| es-sql | 81.623s | 1,000,000 | 305.01 | 96.96 | 304.12 | 24.57 → 25.32 |
| es-ppl | 84.566s | 1,000,000 | 305.01 | 125.05 | 336.50 | 27.47 → 24.49 |
| ch-sql | 3.568s | 1,000,000 | 297.36 | 79.63 | 322.89 | 26.37 → 26.40 |
| ch-ppl | 2.982s | 1,000,000 | 296.11 | 80.54 | 308.39 | 26.41 → 26.41 |

四个任务均为 `SUCCEEDED`。下载耗时分别为 0.646s、0.561s、0.511s、0.392s，下载的 `Content-Length`、实际字节数与任务状态一致。ES 两份文件均为 1,000,001 个物理文本行（含表头）；CH 为 1,000,390 和 1,000,607 个物理行，因 `message` 含换行，但严格 CSV 解析后的数据记录均恰好 1,000,000 条。查询未指定排序，CH 两次选中的记录可以不同，不能用这次耗时差宣称 SQL/PPL 的性能优劣。

每个输出文件都超过 128 MiB 堆上限，而百万行完成后的活跃堆仍约 24–26 MiB；加上实际调用链 `UnifiedQueryService.stream → CalciteExecutionEngine.stream → ResultSet.next → QueryRowConsumer.row → BufferedOutputStream`，可确认本次四条投影导出路径未在 JVM 中收集全量结果。ES 一次只保留一页（最多 1,000 条），CH 使用 JDBC 迭代器，CSV 按行生成，文件缓冲区为 64 KiB。**这不保证本地排序、连接或聚合等所有查询计划也具有同样的内存界限。**

峰值为 250ms 采样值，可能遗漏更短暂的峰值；JVM 堆上限始终是 128 MiB，RSS 包含非堆内存，GC 基线仅在测试前后采集。原始测量日志保存在本地构建报告目录中。应用已正常停止，服务器导出文件剩余 0 个；下载验证文件也已删除。
