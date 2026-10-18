# Embedded SQL/PPL backends and Spring Boot

The embedded entry point uses the existing SQL/PPL parsers, Calcite planner, function implementations, and query response model. `QueryBackend` selects a schema, planning rules, and execution engine together. A query has one selected data source; cross-source federation is outside this implementation.

## Modules

| Module | Responsibility |
| --- | --- |
| `core` | `QueryBackend`, `CalciteExecutionEngine`, shared result conversion and existing local operators |
| `api` | `UnifiedQueryService`, language frontends and per-query contexts |
| `opensearch` | Existing storage/DSL/executor, `OpenSearchQueryBackend`, Analytics adapter and OpenSearch error conversion |
| `elasticsearch` | ES mappings, typed scan, predicate/project/sort/aggregation rules, PIT pagination |
| `clickhouse` | JDBC schemas, conservative ClickHouse dialect/convention and relational pushdown |
| `spring-boot-autoconfigure` | Properties, optional backend factories, ownership and user bean overrides |
| `spring-boot-starter` | Minimal dependency bundle; database backends are selected separately |
| `spring-boot-example` | Search HTTP/UI policy, fixed-time rows and histograms, synchronous/asynchronous streaming CSV |

The shared API no longer has a runtime dependency on the OpenSearch server through `protocol`. OpenSearch error adapters are discovered only when the OpenSearch module is present. The original plugin keeps its own scheduling, resource protection and legacy `PhysicalPlan` execution path. `DelegatingExecutionEngine` continues to select vectorized execution extensions; it is not a data source router.

## Dependencies

Use Java 21 and Spring Boot 3.5. The current build publishes under `org.opensearch.query`, with the repository's version (`3.10.0.0-SNAPSHOT` for this checkout). Publish the shared modules and selected backends to your artifact repository before consuming them externally. Maven publication configuration is provided; no remote publication is performed by the implementation.

```xml
<dependency>
  <groupId>org.opensearch.query</groupId>
  <artifactId>unified-query-spring-boot-starter</artifactId>
  <version>${unified.query.version}</version>
</dependency>
<dependency>
  <groupId>org.opensearch.query</groupId>
  <artifactId>unified-query-elasticsearch</artifactId>
  <version>${unified.query.version}</version>
</dependency>
<dependency>
  <groupId>org.opensearch.query</groupId>
  <artifactId>unified-query-clickhouse</artifactId>
  <version>${unified.query.version}</version>
</dependency>
```

For OpenSearch instead, add `unified-query-opensearch` and `org.opensearch.client:opensearch-rest-high-level-client` matching the OpenSearch module's version. Its existing server/client dependencies are confined to applications choosing that backend. Alternatively, expose an `OpenSearchQueryBackend` bean wrapping the application's existing storage and executor; externally supplied resources remain owned by their host.

## Configuration

```yaml
unified-query:
  default-data-source: logs
  data-sources:
    logs:
      type: elasticsearch
      url: ${ES_URL}
      username: ${ES_USER}
      password: ${ES_PASSWORD}
      # Elasticsearch mappings do not distinguish scalar values from arrays.
      # Declare primitive arrays when those fields occur in the queried indices.
      field-types:
        tags: array<keyword>
    analytics:
      type: clickhouse
      url: ${CLICKHOUSE_JDBC_URL}
      username: ${CLICKHOUSE_USER}
      password: ${CLICKHOUSE_PASSWORD}
      database: default
    # With the optional OpenSearch dependencies installed:
    # search:
    #   type: opensearch
    #   url: ${OPENSEARCH_URL}
```

`unified-query.settings` accepts existing engine setting names. Existing
`UnifiedQueryContext`/`SysLimit` defaults still apply to the ordinary collected execution API.
The example's structured search windows use separate host bounds: `unified-query.search.max-page-size`
(default 1000), `max-offset` (100000), and `max-buckets` (2000). They do not silently reuse the
interactive `plugins.query.size_limit` as a total-result limit. Transport page size and PIT renewal
remain internal mechanics and do not provide a cross-request HTTP cursor or snapshot.

Search time defaults are configured under `unified-query.search.default-time-zone` (default
`Asia/Shanghai`) and `time-fields` (a source-name to source-field map). An explicit
`timeRange.field` overrides that source default; no configured or explicit field is an error.
`unified-query.ui.default-tables` supplies query templates only, and
`unified-query.ui.source-fields.<source>` contains `{name,type}` entries for reliable source-field
controls. The UI displays result aliases separately. `GET /ui/config` returns these display
properties and the top-level `maxPageSize`, without connection addresses or credentials.

The ClickHouse URL constructor sets `join_use_nulls=1`, `join_default_strictness=ALL`, and `union_default_mode=DISTINCT` to preserve SQL outer-join/duplicate-row semantics. An externally supplied JDBC DataSource must use those semantics too. This does not modify server-global settings. JDBC properties can be passed under the data source's `properties` map. OpenSearch `properties` are interpreted as its existing cluster setting names.

## Querying

`UnifiedQueryService` and the shared planner accept one immutable `QueryExecutionRequest`.
The hosting service resolves relative time and decides which result parts to request; the
library receives absolute instants and typed constraints:

```java
@Autowired UnifiedQueryService queries;

var base = new QueryExecutionRequest(
    "logs", QueryType.SQL, "SELECT message FROM application_logs ORDER BY timestamp",
    new QueryExecutionRequest.TimeRange("timestamp",
        Instant.parse("2026-10-17T08:00:00Z"), Instant.parse("2026-10-18T08:00:00Z"),
        ZoneId.of("Asia/Shanghai")),
    List.of(new QueryExecutionRequest.FieldFilter("level", "eq", "ERROR")), null, null);

var rows = queries.execute(base.withWindow(new QueryExecutionRequest.ResultWindow(0, 51)));
var plan = queries.explain(base);
queries.stream(base, consumer, cancellation);
var histogram = queries.execute(base.withHistogram(
    new QueryExecutionRequest.Histogram("timestamp", "1h", ZoneId.of("Asia/Shanghai"))));
```

The structured signatures are `execute(QueryExecutionRequest)`, `explain(QueryExecutionRequest)`,
and `stream(QueryExecutionRequest, QueryRowConsumer, QueryCancellation)`.
`UnifiedQueryPlanner.plan(QueryExecutionRequest)` binds external conditions immediately above
the unique source scan through typed expressions and plan nodes. It preserves the original
SQL/PPL text, OR grouping, `HAVING`, and PPL operators after `stats`. Unknown fields, invalid
literal types, and ambiguous binding across joins or branches fail rather than ignoring inputs.
Filters support `eq/ne/gt/gte/lt/lte/in/is_null/is_not_null/contains`; `contains` treats its value
literally, including SQL wildcard characters. Time uses `[from,to)` and does not require the
source time field to appear in the result projection.

Result windows wrap the complete user result and retain explicit `LIMIT/head`. A host may ask
for one sentinel row (51 in the example), trim it, and calculate `hasNext`. Unordered requests
return `UNSTABLE_ORDER`; bounded page numbers do not promise consistency across concurrent
writes. A histogram is a separate plan before the page window; it reuses the same source
constraints and respects explicit query limits. Aggregate, distinct, window, join and other
unsupported shapes throw `UnsupportedHistogramException`. Histogram output columns are
`__search_bucket` (UTC timestamp) and `__search_count` (long). The host fills empty buckets and
reports unsupported/error states independently of successful rows.

The original text-based execute/explain overloads remain available to embedded callers, including
the default-source `execute(QueryType, String)`. If exactly one backend exists it is selected by
default; multiple backends require a configured default or explicit source. Duplicate names and
missing drivers produce configuration errors. The engine's `ExecutionEngine.QueryResponse`
retains schema, rows, cursor, warnings and error fields. Native decimal/unsigned precision is
preserved by new backends; the common ExprType family labels these values `DOUBLE` because it
has no decimal member. The OpenSearch formatter's numeric behavior remains unchanged.

Backend clients are shared, but each invocation owns a separate planner and Calcite connection.
JDBC statements/result sets and ES PIT contexts close on completion, early termination,
cancellation or errors. Application-provided backend beans override configuration by name and
are not closed twice. `UnifiedQueryService` borrows backends; callers outside Spring own backend
lifetimes.

## Execution behavior

* ES lowers supported filters, direct projections, sorting/limits and compatible grouping/metric operations to DSL. Filters include three-valued boolean/NOT logic, exact-keyword LIKE/ILIKE and a restricted regex subset. Metrics include conditional counts/aggregate filters, variance/standard deviation and approximate percentile/median with compression. Compatible time `span` uses composite `date_histogram`: positive integer fixed intervals from milliseconds through days, or one calendar week/month/quarter/year with UTC boundaries. Numeric integral `span` uses parameterized long-arithmetic script terms to preserve truncation and large integers; safe floating spans and bounded-integer fixed bins use histogram sources. Composite aggregation enumerates all buckets across pages. Arbitrary computed metrics, unsafe mappings/types/intervals, checked integer sums and unsupported patterns retain local execution. Exact DISTINCT stays exact; explicit approximate aggregates use approximate backend functions. See [the PPL pushdown audit](elasticsearch-ppl-pushdown-audit.md) for detailed limits, validation and remaining opportunities.
* Search histograms use the separate internal `SEARCH_TIME_BUCKET` operator for `1m/1h/1d` buckets aligned to the supplied business time zone, including daylight transitions. ES emits a composite `date_histogram` with `calendar_interval` and `time_zone`; ClickHouse lowers the bucket function to timezone-aware interval starts and UTC instants. This does not change existing SQL/PPL `span` semantics. The host returns natural bucket boundaries and limits counts in the first/last bucket to the selected search range.
* ES provides mapping-driven columns, `_id`, `_index`, `_score`, full-text query functions and optional highlighting. Nested objects are returned as arrays of objects; this does not emulate every native nested-query or parent/child feature. Primitive arrays need an explicit field type declaration, because their array shape is absent from ES mappings.
* ClickHouse uses JDBC relational pushdown. Unknown PPL UDFs and checked integer aggregates remain in Calcite rather than being sent as nonexistent native functions. Empty aggregate and outer-join null semantics are adapted explicitly.
* Shared operators handle same-source joins, subqueries, windows and supported PPL expressions when they are not pushed down. Window execution currently stays local for the ClickHouse JDBC convention.
* Functions requiring OpenSearch plugins (for example node-dependent GeoIP, AD, or vector services) are not automatically available on the other backends. `ml` has explicit native adapters for the operations listed below; it does not translate OpenSearch K-means/RCF into different algorithms. Unsupported operations fail during planning.
* This is the current branch's SQL/PPL grammar, not a new ANSI SQL or ClickHouse-native SQL parser. The new backends do not implement the old serialized OpenSearch cursor protocol.

## Native ML queries

The embedded `ml` command executes a capability supplied by the selected backend. It consumes the
rows produced by preceding PPL commands and appends a prediction column. Subsequent `where`,
`fields`, `stats`, and sorting operate on those predictions. The default output column is
`prediction`; `prediction_field='name'` changes it. A name that already exists is rejected.
`explain` validates the operation and prepares the plan without calling inference or training.

| Backend | Algorithm | Action | Result |
| --- | --- | --- | --- |
| Elasticsearch | `regression` | `predict` | Numeric prediction from an existing trained model |
| Elasticsearch | `classification` | `predict` | Predicted class, represented as a string |
| ClickHouse | `linear_regression` | `trainandpredict` | Numeric prediction fitted on the input rows |
| ClickHouse | `logistic_regression` | `trainandpredict` | Probability of class `1`, fitted on the input rows |

### Elasticsearch trained-model inference

```ppl
source=events
| where status = 'ready'
| fields request_size, cpu_usage
| ml action='predict' algorithm='regression' model_id='latency-model' prediction_field='predicted_latency'
| where predicted_latency > 1000
```

Use `algorithm='classification'` to infer class labels instead. Input column names must match the
model's feature names (use `rename` or `eval` beforehand if needed). The adapter calls
`POST /_ml/trained_models/{model_id}/_infer`, preserving input order. `batch_size` defaults to 128
and must be between 1 and 1000. A downstream limit may read ahead and infer more rows than it
returns; put `head` before `ml` to cap the inference input. Empty input does not call inference.
Missing models, permissions,
license errors, per-row inference errors, and incomplete responses fail the query instead of
producing fabricated predictions. The ES deployment must provide the trained model and the
license/privileges required by its native API.

The adapter does not create ML jobs or destination indices. Anomaly-detection jobs, forecasts,
Data Frame Analytics training, ES|QL `CHANGE_POINT`/`CATEGORIZE`, and LLM inference endpoints are
separate native APIs and are not implicitly invoked by this command.

### ClickHouse inline regression

```ppl
source=training_samples
| fields target, x1, x2
| ml action='trainandpredict' algorithm='linear_regression' target='target' features='x1,x2' learning_rate=0.01
| fields target, prediction
```

For binary classification, use `algorithm='logistic_regression'` with a target containing only
`0` and `1`. The adapter maps those labels to ClickHouse's signed training labels and returns the
probability of class `1`; a caller can apply its own classification threshold with `eval`/`where`.
Feature and target columns must contain finite, non-null numeric values. `features` is an explicit
comma-separated list of distinct input column names and must not include the target.

The adapter executes one native SQL query using `stochasticLinearRegressionState` or
`stochasticLogisticRegressionState`, followed by `evalMLMethod`. All input rows participate in one
fit. It does not split training into independent batch models, create tables, or persist models.
These are **in-sample predictions**, not a held-out accuracy evaluation or a saved-model API.
Original columns stay in the query process; only row ordinals, targets and features are bound as
parameters to ClickHouse. SQL identifiers for this intermediate data are generated internally.

Optional training parameters are `learning_rate`, `l2`, `batch_size` (optimizer mini-batch size),
and `optimizer` (`Adam`, `SGD`, `Momentum`, or `Nesterov`). Defaults are learning rate `0.00001`,
L2 `0.1`, mini-batch size `1`, and optimizer `Adam`. The adapter deliberately uses a mini-batch
size of 1: ClickHouse does not apply the gradients of an incomplete final mini-batch. An explicit
`batch_size` must divide the nonempty input row count; otherwise the query fails before training
instead of silently ignoring its last samples.

Training materializes a bounded input: `max_rows` defaults to 1000 and may be set from 1 to 10000.
The adapter also limits retained input to 100,000 cells and expanded SQL to 128 KiB. Exceeding a
limit fails rather than silently truncating training data. Filter or explicitly limit the input **before** `ml` when
needed; a `head` after `ml` only limits its output. JDBC resources close on success or failure;
cancellation is checked while consuming and producing rows. An in-flight native request may take
until its driver/transport timeout to complete after cancellation.

The existing OpenSearch plugin's V2 `ml`, `kmeans`, and `ad` paths retain their original ML Commons
behavior. The embedded API does not acquire that V2 path by disabling Calcite. Unsupported
backend/action/algorithm combinations and unrecognized parameters are rejected; ML is not a
raw backend SQL or HTTP escape hatch.

## Adding a backend

Implement `QueryBackend` in a separate module. Supply its name/type, per-query Calcite schema, rules, and `ExecutionEngine`. `CalciteExecutionEngine` is reusable when the backend's tables implement the remote read operations. Implement the `RelNode` execution methods for a different execution strategy. Register a `QueryBackend` bean directly, or add a `QueryBackendFactory` bean for declarative configuration. The parser and core query service require no backend-specific switch changes.

A factory creates owned resources; the configuration registry closes these on startup failure and application shutdown. A backend constructed around externally owned clients must not close them. The tests include a custom backend/factory with no database driver to exercise the extension boundary.

## Validation

```sh
./gradlew :api:test :protocol:test :spring-boot-autoconfigure:test
./gradlew :opensearch-sql-plugin:compileJava
./gradlew :spring-boot-example:bootJar

# Dedicated test servers only: tests create and delete their own randomly named tables/indices.
ELASTICSEARCH_TEST_URL=http://localhost:9200 ./gradlew :elasticsearch:test --rerun-tasks
CLICKHOUSE_TEST_URL=jdbc:clickhouse://localhost:8123/default ./gradlew :clickhouse:test --rerun-tasks
```

Authenticated test servers can use `ELASTICSEARCH_TEST_USERNAME` / `ELASTICSEARCH_TEST_PASSWORD` and `CLICKHOUSE_TEST_USERNAME` / `CLICKHOUSE_TEST_PASSWORD`. Pass them through the process environment rather than embedding credentials in the URL or source files.

The integration classes are skipped when their endpoint variables are absent. They cover both languages, real pushdown, same-source JOIN, IN subqueries, windows, nulls, exact DISTINCT, result types and ES cross-page document/bucket retrieval. Supply credentials through dedicated test configuration; production credentials do not belong in command-line arguments.

`ClickHouseMlIntegrationTest` also uses `CLICKHOUSE_TEST_URL` to verify native fitting and the full
PPL pipeline. `ElasticsearchMlIntegrationTest` additionally requires
`ELASTICSEARCH_ML_TEST_ENABLED=true`: it creates and deletes uniquely named synthetic indices and
trained-tree models, so the test cluster needs an ML-capable license and permissions for those
operations. To run just the ML suites with these environment variables configured:

```sh
./gradlew :clickhouse:test --tests '*ClickHouseMl*' \
  :elasticsearch:test --tests '*ElasticsearchMl*' \
  :api:test --tests '*UnifiedQueryMLTest'
```

The packaged example starts a Web server on port `8080` without application arguments:

```sh
java -jar spring-boot-example/build/libs/spring-boot-example-3.10.0.0-SNAPSHOT.jar
```

Configure the backend connections through environment variables or add
`--spring.config.additional-location=file:/path/to/application.yml` to load an external
configuration file. Use the standard Spring Boot `--server.port` option to change the port.
The Spring context remains active until application shutdown.

The example's HTTP adapter is `SearchController`; `SearchService` resolves and validates time,
coordinates rows and histograms, and delegates to the structured API. `SearchRequest` /
`SearchResponse` define search HTTP DTOs; `ExportRequest` contains the shared export context.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/search` | Rows, histogram, or both |
| POST | `/search/export` | Synchronous CSV |
| POST | `/search/export/tasks` | Submit asynchronous CSV |
| GET | `/search/export/tasks/{id}` | Poll export progress |
| GET | `/search/export/tasks/{id}/file` | Download a successful task |
| GET | `/ui/config` | UI display configuration |

For an existing `employees` table in the configured ClickHouse database:

```sh
curl --fail --request POST 'http://localhost:8080/search' \
  --header 'Content-Type: application/json' \
  --data-raw '{"source":"analytics","language":"PPL","query":"source=employees | fields timestamp,message","timeRange":{"field":"timestamp","from":"now-1d","to":"now","timeZone":"Asia/Shanghai"},"filters":[{"field":"message","operator":"contains","value":"error"}],"page":{"number":1,"size":50},"histogram":{"interval":"auto"},"include":["rows","histogram"]}'
```

`query` is required; source and language default to the configured source and SQL.
`timeRange` is optional and filters default to an empty array. Both relative endpoints are
resolved from one clock instant, and the response returns `resolvedTimeRange`. Reuse that
absolute range for pages, chart refreshes and exports; a new search resolves `now` again. Time input supports millisecond precision; finer absolute input is rejected with HTTP 400, and generated `now` is resolved to milliseconds.
The response contains `rows:{schema,datarows}`, `page:{number,size,hasNext}`,
`histogram:{status,interval,timeZone,buckets:[{from,to,count}],reason}`, and warnings as applicable.
Unrequested parts are omitted and there is no total-hit count derived from page length.

`include` defaults to `["rows"]`; `["histogram"]` and both parts are supported. Page defaults are
one-based page 1 / 50 rows, bounded by host configuration. Page-only refreshes use
`include:["rows"]` and keep the original histogram. Auto intervals are minutes for ranges up
to one hour, hours through one day, and calendar days thereafter. Supported explicit intervals
are `1m/minute`, `1h/hour`, and `1d/day`. Automatic charts require finite time; unsupported
query shapes return `status:"unsupported"`, while execution or bucket-cap failures return
`status:"error"` and a reason without replacing successful rows. Invalid intervals remain
request errors.

Synchronous and asynchronous exports accept only source, language, query, timeRange and filters;
UI page/histogram/include fields are rejected. Copy the successful search's absolute context:

```sh
curl --fail --output result.csv \
  --request POST 'http://localhost:8080/search/export' \
  --header 'Content-Type: application/json' \
  --data-raw '{"source":"analytics","language":"PPL","query":"source=employees | fields timestamp,message","timeRange":{"field":"timestamp","from":"2026-10-17T08:00:00Z","to":"2026-10-18T08:00:00Z","timeZone":"Asia/Shanghai"},"filters":[{"field":"message","operator":"contains","value":"error"}]}'
```

For an asynchronous task submit the same body to `/search/export/tasks`, follow its `202`
response ID and context-path-aware Location, poll the status path, and download `/file` only
when state is `SUCCEEDED`. The task freezes its conditions at submission. Both exports share
typed source binding and a row-streaming runner rather than collecting all results in memory;
explicit LIMIT/head remains effective, and the interactive output-size cap does not limit the
export. Local blocking operators can still materialize data.

The existing worker/queue/retained-task/file-cap/expiry/ownership rules apply to both export
forms. Defaults under `unified-query.export` are 2 workers, queue capacity 16, 100 retained tasks,
10 GiB per file and one-hour terminal retention. Files are local, expired files are cleaned up,
and tasks do not survive a restart. See the [example README](../../spring-boot-example/README.md)
for full DTO fields, HTTP statuses, CSV behavior and configuration.

## Expanded SQL/PPL result corpus

`ElasticsearchCorpusTest` and `ClickHouseCorpusTest` share the query catalogue in
`api/src/testFixtures/resources/corpus/backend-queries.json`. The catalogue currently has
128 positive queries (51 SQL, 77 PPL), including 16 multi-stage combinations. Both backends
use isolated randomly named event and lookup tables/indices with null/missing values,
duplicates, accented text, Chinese/emoji, arrays, JSON and UTC timestamps.

Every positive case compares actual rows against a separate in-memory Calcite scan backend.
Deterministically ordered queries compare row order too; unordered results compare multisets,
including duplicate counts. Numbers are compared canonically (floating values at 12 significant
digits); strings remain strings. Important counts, empty aggregates, pagination and CROSS JOIN
also have explicit golden results. `NativeBackendSemanticsTest` independently checks the shared
temporal behavior, UTC instants, joins and generated row multiplicity so shared-engine defects
cannot be hidden by differential comparison alone.

The five cases in `grammar-boundaries.json` are negative capability checks, reported as
`unsupported-confirmed`, not successful queries or skipped tests. They document the current
SQL V2 frontend's COALESCE / explicit ROWS-frame limitations and PPL's direct function grouping /
MVCOUNT limitations, together with supported alternatives. Platform-specific ML/AD/REST commands
and cross-source federation are not counted as covered by the portable query corpus.

Run the expanded matrix against the configured test services with the same URL/credential
environment variables used by the integration tests:

```sh
./gradlew :elasticsearch:test --tests '*ElasticsearchCorpusTest' \
          :clickhouse:test --tests '*ClickHouseCorpusTest'
```

Per-case observations (query, category, status, native SQL when available, timing and diagnostics)
are written to `elasticsearch/build/reports/corpus/elasticsearch.json` and
`clickhouse/build/reports/corpus/clickhouse.json`. Fixture creation and deletion are outside the
query API, and cleanup runs even when a case fails. Credentials are read only from environment
variables and are not included in the corpus or reports.

## Host-managed query sessions

`UnifiedQueryService.openSession(source, language, query, options)` prepares and reads one
query across pages. The session owns its Calcite context, JDBC statement and result iterator.
`describe()` compiles without scanning rows; `readPage(size)` returns a response and `hasMore`.
A lookahead row belongs to the next page, and exhausting the cursor closes its statement/result.
Always close the session, including when a page is rejected by an application output policy.
The service borrows backend clients; closing it cancels its sessions without closing those clients.
Spring destroys the service before the configured backend registry.

Each session uses a serial virtual worker so pages arriving on different request threads retain
Calcite's query-local planning/warning/timewrap state. `closeAsync()` reports actual resource
cleanup and returns an independent future; caller cancellation cannot publish cleanup early.
`close()` waits at most five seconds. If a driver is still unwinding, cleanup remains active and
its scheduler stays alive until the actual cleanup completes.

`QueryExecutionOptions` accepts a trusted `schemaFactory` or `scopedSchemaFactory`, a logical
default schema, settings snapshot and a bound-plan processor. Both execution and explain apply
the same request-local catalog and plan policy. The controlled factory receives a
`QueryExecutionControl`; backend adapters register transport cancellation and account actual
scanned rows/bytes with it. Do not deserialize these callbacks from HTTP or put user-specific
state on a shared backend singleton.

`QueryLimits` defines a session deadline and scan/input/output/materialization limits. Plain
library defaults are unbounded for compatibility; a hosting product must select finite limits.
A backend must wire its actual reads into the control before the host can claim scan or transport
cancellation enforcement. The generic JDBC statement, optimizer checks and cancellation retry
mechanism are provided; backend-specific integration is a separate responsibility.

Ordinary pages stream rows using the same native type converter as collected responses.
`timewrap` requires complete bounded input: sessions require a finite materialization budget,
pivot once, and then page the resulting dynamic columns. Exceeding a limit fails rather than
returning truncated aggregates. The existing collected `execute` API retains its output-size
setting; it is not a substitute for a host's complete-input and paging contract.

For a source-stamped local publication, pass `-PunifiedQueryVersion=3.10.0.0-<source-stamp>`
to `publishToMavenLocal`. The override versions all query modules, including their internal Maven
dependencies, while OpenSearch server/build-tool versions stay independently configured.
Snapshot overrides are rejected. Record the source manifest and artifact checksums before an
embedded application consumes the result; remote artifact publication is a separate operation.
