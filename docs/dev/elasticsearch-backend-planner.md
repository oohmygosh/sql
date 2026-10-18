# Standalone Elasticsearch backend planning

The `elasticsearch` module uses independent Calcite planner rules, an immutable native plan,
and an execution-owned search cursor. It depends on the common query contracts in `core`,
not on the OpenSearch plugin's index scans, request builders, or clients.

## Value contract

Logical columns are decoded from `_source` according to their declared mapping types.
Object columns retain their original maps. Numeric and boolean source strings are decoded;
dates use their mapping format, including per-index formats for wildcard sources. Invalid
non-null values fail with the index, field, source path and target type in the error.

A native operation must preserve those logical values. Its eligibility is operation-specific:

- Comparisons, ordering and direct grouping reject quantized `float`, `half_float`, and
  `scaled_float` values. Presence-based predicates and counts can still be eligible.
- `copy_to` targets, their multi-fields, and generated `constant_keyword` fields cannot be
  treated as equivalent to values present in `_source`.
- Mapping modifiers such as `normalizer`, `null_value`, and `ignore_above` conservatively
  retain affected computations locally.
- LIKE/ILIKE and supported literal regular expressions can use scalar `keyword` or `wildcard`
  fields. A pattern on `text` can automatically use a source-equivalent multi-field, preferring
  `wildcard`, only when it covers every index contributing the parent field. This does not make
  wildcard fields eligible for ordering, range comparisons, or grouping.
- Direct `date_nanos` comparisons and grouping remain local because Calcite timestamps
  have millisecond precision. UTC time buckets at least one millisecond wide can remain native.
- Native sorting supplies `unmapped_type` so an unmapped index in a wildcard source has
  the same missing-value behavior as a document without the field.
- Existing numeric aggregate restrictions remain in place: for example, exact long sums
  cannot be delegated to a native double accumulator merely because filtering is safe.

Elasticsearch mappings cannot distinguish scalar fields from multi-valued fields. Callers must
continue to declare arrays through the existing `fieldTypes` configuration. `nested` mappings
are arrays and are not treated as ordinary object paths. Unsupported date formats fail explicitly;
the decoder does not claim to implement Elasticsearch's full named-format and locale catalog.
The extrema specialization currently covers proven numeric/string source fields; date/boolean
extrema after object projection, arbitrary computed values, and paths behind joins or volatile
projections can still encounter Calcite's existing `ANY` typing limitations.

## Planning stages

1. `UnifiedQueryService` invokes `QueryBackend.normalizePlan` before physical optimization.
   The default implementation is an identity operation for other backends.
2. `ElasticsearchPlanNormalizer` traces source-column lineage through projections and other
   supported relational operators. For an ordinary source object, it converts dotted accesses
   such as `ITEM(extracted, 'process.name')` into nested object access. This is mandatory for
   correct local execution and does not depend on native pushdown being enabled or cheaper.
   Computed maps, including `spath` output with literal dotted keys, keep normal ITEM semantics.
   Object shape can survive a CASE expression without retaining a single physical field identity.
   Flat mappings (`subobjects: false`) preserve literal dotted source keys. Known numeric/string
   MIN/MAX arguments are materialized as decoded scan columns before selecting a typed accumulator;
   the surrounding query retains its original output names and row contract.
3. `ElasticsearchExpressionResolver` materializes known source paths into scan columns for
   optimization. Physical field identity and per-index source paths remain separate from aliases.
   A name alone never makes a computed expression eligible for native execution.
4. Rules under `planner/rules` independently handle filters, projections, sorting/limits,
   aggregation, and conversion to Enumerable convention. `ElasticsearchFilterPushdown`
   returns native conjunctions, a mandatory local residual, and refusal reasons. An OR is
   native only when all of its branches can be translated with SQL null semantics preserved.
   Native scans retain the estimated selectivity of each pushed conjunction, so adding source
   time constraints does not erase the estimated benefit of another native predicate.
5. `ElasticsearchScan` holds an `ElasticsearchQueryPlan`. `ElasticsearchRequestBuilder`
   encodes requests, while `ElasticsearchSearchEnumerator` owns PIT IDs, pagination cursors,
   offsets and emitted-row counts. `ElasticsearchValueDecoder` handles source and aggregate
   representations separately; native aggregation dates are already epoch milliseconds.

A native final limit must not cross a residual filter or local sort. A previously applied
limit also prevents later filtering/aggregation from moving below it. Request page size is
only a transport setting and must not act as a logical limit. Source projection is allowed
with native computation disabled, since it preserves values while reducing transport work.
Ordering can move below a deterministic residual filter; OFFSET/FETCH stay above that filter.
Volatile predicates and a scan's existing result window prevent this reordering.

Explain output shows native query/fields/sort/fetch/aggregation, the remaining Enumerable
operators, relevant field constraints, and whether native computation was disabled.

## Example and diagnostic mode

Both query shapes below return the same results. With compatible mappings, both can use a
native term query, descending timestamp sort, and `fetch=5`:

```text
source=`logevent-*`
| fields id, timestamp, hostIp, extracted
| where extracted.process.name = 'nezha-agent'
| sort - timestamp
| head 5
```

```text
source=`logevent-*`
| where extracted.process.name = 'nezha-agent'
| sort - timestamp
| head 5
| fields id, timestamp, hostIp, extracted
```

To compare against local computation on the same source data, use the optional constructor:

```java
new ElasticsearchQueryBackend(name, client, owned, fieldTypes, false);
```

Existing constructors enable pushdown. Disabling it retains mandatory source normalization,
source projection, PIT pagination and mapping-aware decoding. This is useful for scalar and
object expressions with local implementations; native-only relevance functions still require
native execution.

## Verification

`ElasticsearchObjectPathPushdownTest` exercises the original fields-first query, reordered
queries, aliases, residual filters, null/missing objects, counts, and computed-object shadowing.
It compares enabled and disabled pushdown against independent expected rows.

`ElasticsearchSemanticEquivalenceTest`, `ElasticsearchSchemaSemanticsTest` and
`ElasticsearchValueDecoderTest` cover mapping restrictions and decoding.
`ElasticsearchFlatSourcePathTest` covers literal dotted source paths, and
`ElasticsearchTypedObjectAggregationTest` covers typed extrema after object projection. Execution-plan tests
cover deep immutability, offset/fetch behavior, multi-page iteration, rotated PIT cleanup,
partial-response failures, decoder failures and suppressed cleanup errors.

HTTP fixtures evaluate the supported DSL against fixed documents and reject unsafe or
unexpected operations. They validate adapter behavior, not Elasticsearch's own indexing
implementation. Live backend tests provide the complementary server-side validation.

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :elasticsearch:test :api:test
```
