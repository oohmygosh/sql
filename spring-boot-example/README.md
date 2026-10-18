# Elasticsearch / ClickHouse 搜索示例

这个 Java 21、Spring Boot 3 示例通过 `UnifiedQueryService` 查询 Elasticsearch 和 ClickHouse，提供统一 `/search` HTTP 接口、后台分页、时间分布及流式 CSV 导出。运行 `QueryApplication` 或打包后的 JAR 启动 Web 服务，默认端口 `8080`。

## 搜索前端

前端独立启动，访问 `http://127.0.0.1:5173/` 使用 Lens 搜索页面，源码及启动说明见 [`frontend/README.md`](frontend/README.md)。页面提交 SQL/PPL 原文、时间和结构化筛选；表名以语句为准，默认模板不自动添加 `LIMIT/head`。分页在后台执行，翻页只请求明细并保留图表。图表直接使用后台桶数据，点击桶缩小时间范围。查询历史保存完整条件，导出复用最后一次成功查询的固定时间与筛选。

`GET /ui/config` 仅提供数据源名称、类型、默认表、时间字段/时区及显式配置的源字段，并提供顶层 `maxPageSize`，不暴露连接地址和凭据。默认表为 `logevent-*` / `log_event`，可通过 `ES_TEST_INDEX` / `CLICKHOUSE_TEST_TABLE` 或 `unified-query.ui.default-tables` 覆盖。它们仅用于生成快捷语句；界面没有独立的表名输入框。字段面板区分源字段与结果列/别名，结果别名不能直接生成源字段筛选。

`GET /ui/query/completions` 返回已注册的补全语言，当前为 PPL。`POST /ui/query/completions` 接收 `language、source、text、caret、documentVersion、trigger`，返回带说明和 UTF-16 替换范围的候选。公共补全服务通过 `LanguageCompletionProvider` 注册语言，SQL 接入可复用相同协议和元数据服务；当前 SQL 编辑器不启用补全。

PPL 按位置连续引导 `source/search/index → = → 表/索引 → | → 命令 → 字段/条件`。命令、参数、枚举和函数入口由执行语法的 ANTLR ATN 统一发现；表字段、作用域、动态选项和执行能力由独立语义层补充。表和字段读取当前数据源的 Elasticsearch mapping 或 ClickHouse JDBC 元数据，不执行编辑中的语句，不扫描数据，也不使用上次结果列冒充字段。字段跟随语句中的表，并反映前序管道的投影、重命名、新增字段和聚合输出。表名、字段名由语言适配器转义。

前端负责 150ms 防抖、取消请求、版本校验、组合输入和候选插入，接受候选后继续请求下一位置。字段元数据短期缓存并隔离数据源和表；失败会显示状态，不影响查询执行。补全不包含实时语法错误诊断。`GET /ui/ppl/grammar` 保留为兼容的 PPL 语法包接口，编辑器不再依赖浏览器解释该语法包。

后端仅提供 HTTP 接口，IDE 直接运行 `QueryApplication`；Gradle 构建和 JAR 运行均不需要 Node.js，也不构建或打包前端。前端在 `frontend/` 下执行 `npm ci`、`npm run dev`，由 Vite 将 `/search` 和 `/ui` 代理到后端 `http://127.0.0.1:8080`。请求和异步任务链接支持 Spring servlet context path。

## 代码结构

```text
org.opensearch.sql.example
├── QueryApplication                # Spring Boot 入口
├── controller                      # HTTP 路由、状态码、JSON/附件响应
│   ├── SearchController
│   ├── SearchUiController
│   ├── QueryCompletionController
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
    id-fields:
      logs: id
      analytics: id
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

## 搜索下推端到端回归

57 项固定用例位于 `src/test/java/org/opensearch/sql/example/e2e/`，覆盖 PPL/SQL 筛选、
对象字段、投影/改名/eval、排序分页、聚合/HAVING/条件聚合、时间分桶和直方图。
测试自行启动随机端口的真实 Spring Boot HTTP 服务，在指定 ES 上创建唯一的
`uq-search-e2e-*` 索引，结束时删除该索引并关闭服务，不使用本地 8080 或业务索引。

配置独立测试 ES 后运行（需要创建/删除测试索引、写入、mapping、PIT 和搜索权限）：

```bash
export ELASTICSEARCH_TEST_URL=http://localhost:9200
# 如需认证，另行设置 ELASTICSEARCH_TEST_USERNAME 和 ELASTICSEARCH_TEST_PASSWORD。
./gradlew :spring-boot-example:test :spring-boot-example:searchE2eTest
```

`searchE2eTest` 缺少 URL、无法连接或断言失败时会失败；每次显式调用均重新执行，
不复用 Gradle 缓存。普通 `test` 仅检查用例清单，不连接数据库。CI 的
`search-pushdown-e2e.yml` 使用独立 Elasticsearch 8.19 服务执行这两个任务。

56 个成功查询会将 HTTP 响应的 schema、行值/顺序、分页/hasNext、时间范围和直方图
逐项对照禁用下推的本地执行结果；另 1 个用例校验字段作用域错误返回 400。
计划通过 Calcite hook 从实际 HTTP 执行线程捕获，直接校验 ES 扫描与本地算子，
区分必须下推、部分下推、必须本地执行和 head/分页边界，直方图还需原生 date_histogram。

JUnit 的每个用例有独立名称。HTML 报告位于
`build/reports/tests/searchE2eTest/index.html`，请求、实际响应、基准响应和物理计划
保存在 `build/reports/search-e2e/cases.json`；失败时也会保存，CI 会上传这些诊断产物。

## 日志

示例使用 Spring Boot 日志 starter 提供的 Logback，配置位于 `src/main/resources/logback-spring.xml`。默认以 `INFO` 级别同时输出到控制台和当前工作目录下的 `log/unified-query-example.log`，包含时间、线程、级别、logger 及异常堆栈。日志按天或单文件达到 `10MB` 滚动，历史文件压缩保存，最多保留 7 天且归档总量不超过 `100MB`。

通过 `LOGGING_FILE_NAME` 环境变量或 `--logging.file.name=/path/to/example.log` 修改文件位置；通过 `LOGGING_LEVEL_ROOT` 或 `--logging.level.root=DEBUG` 调整根日志级别，也可用 `--logging.level.org.opensearch.sql=DEBUG` 仅开启引擎调试日志。滚动策略可通过 `logging.logback.rollingpolicy` 下的 `max-file-size`、`max-history` 和 `total-size-cap` 覆盖。

## 搜索 HTTP 协议

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/search` | 明细、后台时间分布或两者 |
| POST | `/search/export` | 同步 CSV 导出 |
| POST | `/search/export/tasks` | 创建异步导出任务 |
| GET | `/search/export/tasks/{id}` | 查看任务进度 |
| GET | `/search/export/tasks/{id}/file` | 下载成功文件 |
| GET | `/ui/config` | 页面初始化显示配置 |
| GET | `/ui/query/completions` | 当前可用的补全语言 |
| POST | `/ui/query/completions` | 根据完整文本、光标和数据源生成上下文候选 |
| GET | `/ui/ppl/grammar` | 兼容的 PPL 语法包接口 |

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

支持的筛选操作为 `eq/ne/gt/gte/lt/lte/in/is_null/is_not_null/contains`；`in` 使用数组，空值条件不需要 value，其他操作需提供非 null 值。`contains` 与 PPL `contains` 一致，采用 ILIKE 的不区分 ASCII 大小写语义进行字面字符串包含匹配，用户输入的 `%` / `_` 不解释为 SQL 通配符。

外部条件在源数据读取之后、用户原有过滤/计算/聚合之前执行；保留原语句 OR 分组、HAVING 和 PPL stats 后 where 的位置。条件冲突返回空结果。不存在的字段、错误类型、无法明确绑定条件的连接/多分支查询直接报错，不猜测筛选目标。时间字段无需出现在最终投影中。

分页在完整用户结果外执行，多取一行判断 `hasNext`，保留手写 `LIMIT/head` 的边界与原有排序。示例层对未指定排序的普通单源明细查询，按该数据源的 `time-fields`、`id-fields` 两个字段倒序排序；两项都配置才启用，`id-fields` 应指向可排序且在查询范围内唯一的业务 ID（ES 使用业务字段 `id`，不是 `_id`）。排序在投影和 `LIMIT/head` 前生效，时间和 ID 无需出现在返回列中；搜索、时间图和导出使用相同策略。已有排序保持原样，聚合、去重、连接、子查询、窗口及其他无法安全补排序的查询不自动改写，未排序的分页仍会返回 `UNSTABLE_ORDER`。此策略仅在 `spring-boot-example` 实现，不改变核心框架。

时间加唯一 ID 可确定同一数据集的顺序；持续新增、删除或更新下的有界页码仍不保证跨请求快照一致性，底层 PIT/JDBC 分页不是 HTTP 跨请求游标。要严格避免动态数据下翻页重复或遗漏，需要另行提供跨请求快照和游标分页。

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
