# Elasticsearch / ClickHouse 查询示例

这个 Java 21、Spring Boot 3 示例应用通过同一个 `UnifiedQueryService` 查询 Elasticsearch 和 ClickHouse，提供 SQL/PPL 的 Web 查询导出。运行 `QueryApplication` 或打包后的 JAR 即可启动 Web 服务，默认端口为 `8080`。

## 代码结构

```text
org.opensearch.sql.example
├── QueryApplication                # Spring Boot 入口
├── controller                      # HTTP 路由、状态码、附件响应
│   ├── QueryExportController
│   └── QueryExportTaskController
├── service                         # 同步导出、任务调度、进度与文件生命周期
│   ├── QueryExportService
│   └── QueryExportRunner            # service 内部的 QueryRunner 实现
├── model                           # 独立请求/返回模型
│   ├── ExportRequest
│   ├── ExportSubmission
│   ├── ExportTaskStatus
│   └── ExportDownload
└── config
    └── QueryExportProperties
```

Controller 只适配 HTTP，Service 调用统一查询和任务服务。公共请求/返回模型不再嵌在 Controller 或 Service 中；任务注册项、写入进度等实现内部状态仍由对应 service 类私有持有。测试目录按相同分包组织。

## 配置与启动

内置配置位于 `src/main/resources/application.yml`：

| 数据源名称 | 后端 | 默认连接 | 默认用户 |
| --- | --- | --- | --- |
| `logs`（默认） | Elasticsearch | `http://192.168.100.212:9200` | `elastic` |
| `analytics` | ClickHouse | `jdbc:clickhouse://192.168.100.212:18123/default` | `admin` |

建议通过 `ES_PASSWORD` 和 `CLICKHOUSE_PASSWORD` 环境变量为两个后端设置连接密码。在 Bash 中可以隐藏输入：

```bash
read -r -s -p 'Elasticsearch password: ' ES_PASSWORD
printf '\n'
read -r -s -p 'ClickHouse password: ' CLICKHOUSE_PASSWORD
printf '\n'
export ES_PASSWORD CLICKHOUSE_PASSWORD
```

可选环境变量包括 `ES_URL`、`ES_USER`、`CLICKHOUSE_JDBC_URL`、`CLICKHOUSE_USER` 和 `CLICKHOUSE_DATABASE`。切换 ClickHouse 数据库时同时调整 JDBC URL 和 `CLICKHOUSE_DATABASE`。

从仓库根目录构建并启动：

```bash
./gradlew :spring-boot-example:bootJar
JAR=spring-boot-example/build/libs/spring-boot-example-3.10.0.0-SNAPSHOT.jar
java -jar "$JAR"
```

无参数启动即运行 Web 服务，直到应用停止。IDE 中运行 `org.opensearch.sql.example.QueryApplication`，设置相同环境变量即可，无须程序参数。可使用 Spring Boot 标准配置调整端口：

```bash
java -jar "$JAR" --server.port=8081
```

解析和函数语义沿用项目现有 SQL/PPL 引擎，ClickHouse 原生 SQL 方言不直接作为输入语言。

如需按实际 ES 数据声明数组字段，添加 Spring 配置，例如：

```yaml
unified-query:
  data-sources:
    logs:
      field-types:
        tags: array<keyword>
```

通过 `--spring.config.additional-location=file:/path/to/application.yml` 载入额外配置；Spring 的标准外部配置覆盖规则同样适用。

## Web 查询导出

通过 `POST /query/export` 提交 `Content-Type: application/json` 请求：

| 请求字段 | 说明 |
| --- | --- |
| `source` | 数据源名称，省略时使用配置的默认数据源（本例为 `logs`）；可选名称包括 `logs`、`analytics`。 |
| `language` | `SQL` 或 `PPL`，大小写不敏感；省略时为 `SQL`。 |
| `query` | 必填，查询文本不能为空或仅包含空白。 |

以下 SQL 示例省略 `source` 和 `language`，使用默认的 `logs` 和 SQL。将 `your_index` 替换为实际索引或别名：

```bash
curl --fail --output result.csv \
  --request POST 'http://localhost:8080/query/export' \
  --header 'Content-Type: application/json' \
  --data-raw '{"query":"SELECT count(*) AS total FROM `your_index`"}'
```

PPL 示例选择 `analytics` 数据源，将 `your_table` 替换为 ClickHouse 当前数据库中的表：

```bash
curl --fail --output result.csv \
  --request POST 'http://localhost:8080/query/export' \
  --header 'Content-Type: application/json' \
  --data-raw '{"source":"analytics","language":"ppl","query":"source=your_table | stats count() as total"}'
```

成功响应为 `Content-Type: text/csv;charset=UTF-8`，并带有 `Content-Disposition: attachment; filename="result.csv"`。导出沿用现有 `CsvResponseFormatter` 的默认 `sanitize=true` 行为，不添加 BOM。

仅导出本次查询实际返回的行；嵌入式配置默认 `plugins.query.size_limit=10000`。查询结果在内存中收集，不承诺全量、异步或流式导出。

缺失或空白 `query`、非法 `language` 或 `source`，以及引擎报告的非法查询参数返回 HTTP `400`；其他查询执行失败返回 HTTP `500`。错误响应不会作为 CSV 附件返回。示例中的 `curl --fail` 遇到 HTTP 错误时以非零状态退出，不将错误体保存为 CSV。更改服务端口后，请同步调整请求 URL。

## 大数据异步导出

ES 和 ClickHouse 的 SQL/PPL 查询均可使用任务接口。请求字段与同步导出一致：

```bash
curl --fail --request POST 'http://localhost:8080/query/export/tasks' \
  --header 'Content-Type: application/json' \
  --data-raw '{"source":"logs","language":"SQL","query":"SELECT * FROM `your_index`"}'
# HTTP 202，响应 {"id":"..."}，Location 为任务状态 URL

curl --fail 'http://localhost:8080/query/export/tasks/<id>'
# {"id":"...","state":"RUNNING","exportedRows":1000,"fileSizeBytes":65536,
#  "submittedAtMillis":...,"completedAtMillis":null,"error":null}

# state 为 SUCCEEDED 后下载：
curl --fail --output result.csv 'http://localhost:8080/query/export/tasks/<id>/file'
```

ClickHouse 示例：

```json
{"source":"analytics","language":"PPL","query":"source=your_table | fields timestamp,message"}
```

- 状态沿用 `QueryJobService`：`PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`；排队中的导出也可能显示 `RUNNING`。即使查询立即完成或失败，提交仍返回 `202` 和可查询的 ID。
- `exportedRows`、`fileSizeBytes` 表示最近一次刷盘的进度；每 1,000 行或写入期间约 250ms 更新一次，完成时更新精确值。空结果也生成 CSV 表头。失败时删除部分文件，文件大小归零，`error` 给出失败原因。
- 只有成功文件可以下载；运行中、失败或取消返回 `409`。未知/过期任务返回 `404`，格式错误的 ID/请求返回 `400`，其他用户的任务返回 `403`，队列或保留任务容量满返回 `429`。
- 逐行转换和写盘，不创建全量结果集合；ES 使用 PIT + `search_after`（分组聚合使用 composite 分页），CH 使用 JDBC 结果迭代。忽略交互查询的 `plugins.query.size_limit` 输出上限，保留 SQL `LIMIT` / PPL `head` 等查询语义。
- 查询中的本地排序、连接、聚合等算子仍可能占用较多内存。`timewrap` 依赖全量结果透视，流式导出会明确失败；它仍可使用同步接口。

配置示例（以下均为默认值，目录默认在 JVM 临时目录下）：

```yaml
unified-query:
  export:
    directory: /tmp/query-exports
    keep-alive: 1h
    workers: 2
    queue-capacity: 16
    max-tasks: 100
    max-file-bytes: 10737418240 # 单文件 10 GiB，超限失败，不截断成成功文件
```

TTL 从任务进入终态开始计算。文件在任务过期后最多约 1 秒清理，已经开始的下载仍可完成；应用正常关闭时取消任务并删除文件。任务元数据保存在本机内存，文件保存在本机磁盘，不提供重启恢复或跨实例查询，部署多实例时需保持请求路由到原实例。进程异常退出可能留下旧 `session-*` 目录，需要按部署策略清理。

接口使用 HTTP 请求的 `Principal` 绑定任务归属；此示例未配置认证时，所有请求均为匿名身份。业务应用应在现有认证链中提供 `Principal`。

Starter 会自动配置 `LocalQueryJobService`，应用可以提供自己的 `QueryJobService` bean 覆盖它。普通程序也可直接使用 `LocalQueryJobService` 与 `QueryRunner`；长期保留的任务使用 `submitAsync`，原来的 `submit` 保持同步等待/超时返回 ID 的行为。`UnifiedQueryService.stream` 提供同步逐行回调，资源在返回或异常时释放。

## 在业务代码中调用

业务代码可以直接注入 `UnifiedQueryService`：

```java
public class ReportService {
  private final UnifiedQueryService queries;

  public ReportService(UnifiedQueryService queries) {
    this.queries = queries;
  }

  public ExecutionEngine.QueryResponse elasticsearchCount(String sql) {
    return queries.execute("logs", QueryType.SQL, sql);
  }

  public ExecutionEngine.QueryResponse clickHouseReport(String ppl) {
    return queries.execute("analytics", QueryType.PPL, ppl);
  }
}
```

每次调用选择一个数据源。更多后端配置和执行说明见 [embedded-query-backends.md](../docs/dev/embedded-query-backends.md)。

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
