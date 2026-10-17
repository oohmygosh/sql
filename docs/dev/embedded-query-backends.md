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
| `spring-boot-example` | Executable, non-web Spring Boot command-line example |

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

`unified-query.settings` accepts existing engine setting names. No new query timeouts, row caps, scan quotas, memory limits, rate limits or execution modes are introduced. Existing `UnifiedQueryContext`/`SysLimit` defaults still apply, including the existing output size setting. Transport page size and PIT renewal are internal pagination mechanics, not total-result limits.

The ClickHouse URL constructor sets `join_use_nulls=1`, `join_default_strictness=ALL`, and `union_default_mode=DISTINCT` to preserve SQL outer-join/duplicate-row semantics. An externally supplied JDBC DataSource must use those semantics too. This does not modify server-global settings. JDBC properties can be passed under the data source's `properties` map. OpenSearch `properties` are interpreted as its existing cluster setting names.

## Querying

```java
@Autowired UnifiedQueryService queries;

var sql = queries.execute("logs", QueryType.SQL,
    "SELECT department, count(*) AS n FROM employees GROUP BY department");
var ppl = queries.execute("analytics", QueryType.PPL,
    "source=employees | stats count() as n by department");
var plan = queries.explain("logs", QueryType.SQL,
    "SELECT id FROM employees WHERE id > 10 ORDER BY id");
var highlighted = queries.execute("logs", QueryType.PPL,
    "source=employees | where match(message, 'error') | fields message,_highlight",
    new HighlightConfig(List.of("message")));
```

The default-source overload is `execute(QueryType, String)`. If exactly one backend exists, it is selected by default. Multiple backends require a default or an explicit source. Duplicate names and missing drivers produce configuration errors. `QueryResponse` retains existing schema, rows, cursor, warning and error fields. Native precision is preserved for new-backend decimal/unsigned numeric results; the existing schema type family labels these numbers as `DOUBLE` because the common ExprType model has no decimal member. The OpenSearch formatter's numeric behavior remains unchanged.

Backend clients are shared, but each invocation owns a separate planner and Calcite connection. JDBC statements/result sets and ES PIT contexts close on normal completion, early result termination or errors. Application-provided backend beans override configuration by name and are not closed twice. `UnifiedQueryService` borrows backends; callers using it without Spring own their backends' lifetimes.

## Execution behavior

* ES lowers supported filters, direct projections, sorting/limits and exact grouping/count/min/max/average operations to DSL. Composite aggregation enumerates all buckets, including across pages. Exact DISTINCT stays exact; explicit approximate aggregates use approximate backend functions.
* ES provides mapping-driven columns, `_id`, `_index`, `_score`, full-text query functions and optional highlighting. Nested objects are returned as arrays of objects; this does not emulate every native nested-query or parent/child feature. Primitive arrays need an explicit field type declaration, because their array shape is absent from ES mappings.
* ClickHouse uses JDBC relational pushdown. Unknown PPL UDFs and checked integer aggregates remain in Calcite rather than being sent as nonexistent native functions. Empty aggregate and outer-join null semantics are adapted explicitly.
* Shared operators handle same-source joins, subqueries, windows and supported PPL expressions when they are not pushed down. Window execution currently stays local for the ClickHouse JDBC convention.
* Functions requiring OpenSearch plugins (for example node-dependent GeoIP, ML, AD, or vector services) are not automatically available on the other backends. Unsupported functions fail; they are not approximated by unrelated operations.
* This is the current branch's SQL/PPL grammar, not a new ANSI SQL or ClickHouse-native SQL parser. The new backends do not implement the old serialized OpenSearch cursor protocol.

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

To run the packaged example:

```sh
java -jar spring-boot-example/build/libs/spring-boot-example-3.10.0.0-SNAPSHOT.jar \
  --spring.config.additional-location=file:/path/to/application.yml \
  --source=analytics --language=PPL \
  '--query=source=employees | stats count() as n by department'
```

The example exposes no HTTP endpoint and closes its Spring context after the query. Spring Boot host applications manage their normal application lifecycle independently.

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
