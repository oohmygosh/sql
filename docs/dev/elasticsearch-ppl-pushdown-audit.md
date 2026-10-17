# Elasticsearch 适配器的 PPL 下推覆盖与缺口

## 范围与计数口径

本文针对本仓库的独立 `elasticsearch` 模块，即 `UnifiedQueryService → ElasticsearchQueryBackend → ElasticsearchScan`。它与 `opensearch` 模块的插件内执行路径不同，后者已有较丰富的下推规则，不能据此认为独立 ES 适配器也支持。

时间 `span` 下推完成后的盘点共识别 **15 类改进点**。其中 **5 类 P1 已实现首批安全子集**：布尔/NOT、字符串模式、统计指标、数值分桶及条件聚合；其余 **10 类仍待实现**。P1 中复杂正则、任意计算聚合等边界仍保留本地执行，不能把类别已实施理解为所有表达式均可下推。

这是能力类别数，不是 PPL 命令数或函数总数；一个命令可以部分下推，多个命令也可以共用同一个优化规则。自适应分桶、事件窗口另列为需要专门执行设计的能力，不计入上述 15 类。下文区分已执行的时间 `span`/P1 验证与其余源码盘点；通用函数/脚本下推仍是候选，不意味着迁移计算到 ES 一定更快。

## 已实现：时间 span 聚合

示例：

```text
source=`*`
| where timestamp > "2026-10-11"
| stats count() as requests by span(timestamp, 1d) as time_bucket
```

此前的物理计划包含本地 `SPAN` 计算与 `EnumerableAggregate`，ES 只执行 range 过滤，再以每页 1000 条取回约 26.65 万条文档。现在识别 `Aggregate → Project → ElasticsearchScan`，把时间分组表达式转换为 composite 聚合的 `date_histogram` source，外层 `size: 0`，只返回分组与指标。

实现位置：[ElasticsearchScan](../../elasticsearch/src/main/java/org/opensearch/sql/elasticsearch/ElasticsearchScan.java) 的 `AGGREGATE_PROJECT`、`pushAggregate` 和 `SearchEnumerator.loadPage`；分组表达式及键解码位于 [ElasticsearchGrouping](../../elasticsearch/src/main/java/org/opensearch/sql/elasticsearch/ElasticsearchGrouping.java)。

覆盖范围：

- 精确、标量 ES `date`/`date_nanos` 字段的时间分桶；使用 UTC 边界。
- `fixed_interval`：正整数字面量的 `ms`、`s`、`m`、`h`、`d`；例如 `5m`、`2d`。
- `calendar_interval`：`1w`、`1M`、`1q`、`1y`。多周/月/季度/年暂保留本地；ES calendar interval 不接受任意倍数，多周还涉及周一对齐偏移。
- 时间桶与普通字段混合分组、字段重命名/重排、原有支持的计数和数值指标；指标参数按 Project 的引用映射回实际 ES 字段。
- 沿用 composite `after_key` 分页及 PIT 生命周期，保留空输入、缺失值和结果类型语义。`missing_bucket` 不替代上游 PPL 生成的非空过滤。
- 计算型指标、无法下推的过滤、聚合前的 head/offset、不兼容映射等继续保留本地执行。
- 非数值 `min/max` 保留本地，避免把 keyword 的字典序极值误发成 ES 数值 min/max；原有 BIGINT/DECIMAL 精度保护保留。

### 实测

同一配置、同一原始 PPL 的客户端查询耗时，计时只包围 `queries.execute(...)`：

| 场景 | 改动前 | 改动后 |
| --- | ---: | ---: |
| 原有 JUnit 用例，每次新测试进程 | 8576.796 ms | 486.860 ms |
| 已执行 explain 的诊断进程 | 8964.153 ms | 219.512 ms |

以上是诊断样本，不是固定延迟承诺。原始查询的当天数据持续写入，因此不同请求的当天计数会变化。另对固定历史范围 `timestamp > "2026-10-11" and timestamp < "2026-10-17"` 做只读对照：`1h`、`1d`、`2d`、`1w`、`1M`、`1q`、`1y` 的全部桶键和计数与原生 ES `date_histogram` 一致，合计 **194840 条文档**，分别返回 144、6、4、2、1、1、1 个桶。

改后计划应具有 `ElasticsearchScan(... grouping=[[{date_histogram=...}]])`，没有本地 `EnumerableAggregate`；上层仅做结果列重排的 `EnumerableCalc` 是正常的。

## 已实现：P1 的五类安全下推

| 类别 | 当前实现 | 保留的边界 |
| --- | --- | --- |
| 布尔/NOT | exact boolean 字段、嵌套 AND/OR/NOT、真值测试和布尔表达式的空值测试；分别翻译 TRUE/FALSE 集合，保留 UNKNOWN。七种全文函数的非 nullable 布尔结果支持取反。 | 不可翻译的 OR 不会部分下推；全文与本地表达式混合的 OR 仍有限制。 |
| 字符串模式 | exact keyword 的 LIKE/ILIKE，含 `%`、`_` 和字面量转义；wildcard 或受限 regexp；正则支持字面量查找、起始 `^`/`\\A`、绝对结束 `\\z` 等安全子集。 | 按 Calcite 处理换行、Unicode 和 ASCII 大小写，不直接复用 Java 正则到 Lucene；复杂正则、`$` 结束锚点、动态模式/转义、text/normalizer 等回退。 |
| 统计指标 | `var_samp/var_pop/stddev_samp/stddev_pop` → `extended_stats`；`percentile/median/pXX` → t-digest `percentiles`，支持 compression。保留空组、单元素样本及返回类型。 | 百分位本来就是近似计算；分片归并可能与本地 t-digest 有近似差异。小数百分位、BIGINT/DECIMAL 数值指标和不安全数值映射继续本地执行。 |
| 数值分桶 | 整型 `span` → 带参数的 Painless long 运算与 composite terms；允许的浮点 `span` → composite histogram；有界整数的固定 `bin ... span=...` → histogram 并恢复字符串范围标签。 | 整型负数向零截断；浮点向下取整。固定 bin 只接受标量 byte/short/integer 和 `[0.0001, Integer.MAX_VALUE]` 内的正跨度；其他类型或可能造成溢出、精度变化、范围标签碰撞的组合回退。 |
| 条件聚合 | `count(eval(condition))`、受支持 CASE 分支的非空计数及 aggregate `FILTER` → filter 子聚合；保留 CASE 顺序和 FALSE/UNKNOWN 落入下一分支的行为。 | CASE 结果限可证明非空性的字面量、直接字段或嵌套 CASE；任意计算指标、精确 distinct、checked 整数求和仍本地执行。 |

实现分别位于 [ElasticsearchPredicate](../../elasticsearch/src/main/java/org/opensearch/sql/elasticsearch/ElasticsearchPredicate.java)、[ElasticsearchMetric](../../elasticsearch/src/main/java/org/opensearch/sql/elasticsearch/ElasticsearchMetric.java) 和 [ElasticsearchGrouping](../../elasticsearch/src/main/java/org/opensearch/sql/elasticsearch/ElasticsearchGrouping.java)，由 Scan 共用的聚合规则装配。聚合前的 head/offset、本地剩余过滤和不兼容映射仍会阻止不安全的聚合下推，composite 分页与 PIT 清理保持原有通路。

这里的数值 SPAN 限定为可证明与本地值等价的映射：整型跨度使用有符号整数类型；histogram 接受有界整数的浮点跨度及通过有限值检查的 ES double。float/half_float/scaled_float 的索引量化可能不同于 `_source`，其值比较和依赖具体值的指标同样回退；直接空值测试和非空计数不受量化影响。映射显式关闭 `index` 或 `doc_values` 时，适配器保守地禁用该字段的普通过滤/排序/聚合下推，避免原本可从 `_source` 执行的查询产生原生能力错误；该检查也覆盖多字段和跨索引合并。

### P1 验证

新增四组测试：[ElasticsearchPredicatePushdownTest](../../elasticsearch/src/test/java/org/opensearch/sql/elasticsearch/ElasticsearchPredicatePushdownTest.java)、[ElasticsearchMetricPushdownTest](../../elasticsearch/src/test/java/org/opensearch/sql/elasticsearch/ElasticsearchMetricPushdownTest.java)、[ElasticsearchNumericGroupingTest](../../elasticsearch/src/test/java/org/opensearch/sql/elasticsearch/ElasticsearchNumericGroupingTest.java)、[ElasticsearchMappingPushdownTest](../../elasticsearch/src/test/java/org/opensearch/sql/elasticsearch/ElasticsearchMappingPushdownTest.java)。覆盖真实 PPL 计划、请求 DSL、返回值/类型、三值逻辑、Unicode/换行/转义、空组/样本统计、负数和 long 边界、混合分组/分页及回退。谓词还与 Calcite 运算结果逐项比较。

只读真实 ES 对照使用同一组固定文档 ID 的 32 条记录，分别执行原生下推与聚合前 `head` 阻止下推的本地基线。布尔、NOT、嵌套逻辑、LIKE/ILIKE、条件计数、统计指标、百分位、整型/long/小数 SPAN、固定 bin 及多能力组合的 14 组查询结果一致；这些小样本用于语义验证，不是性能基准。原生 Painless 测试上下文验证了 8 个整数边界（含负数、`2^53+1`、long 两端）；只读 runtime 字段验证了 7 个 double histogram 边界（含正负零和大数）和 11 个字符串模式边界（含换行、Unicode、转义与大小写），模式结果与 Calcite 一致。未创建索引、持久化脚本或修改文档。

## 目前已经支持的能力

| 类别 | 覆盖与限制 |
| --- | --- |
| 基础过滤 | exact 标量字段与常量的 `= != < <= > >=`、`isnull/isnotnull`；布尔/真值测试和可翻译的 AND/OR/NOT；顶层 AND 可以拆成远端过滤与本地剩余条件。 |
| 字符串模式 | exact keyword 的 LIKE/ILIKE 与正则字面量子集；遵守上述换行、转义和方言限制。 |
| IN / BETWEEN | Calcite 的 SEARCH 会先展开，再进入谓词翻译；不能把整个 IN/BETWEEN 类别简单算成缺失。NOT IN 与 null 的组合仍需逐案验证。 |
| 全文检索 | `match`、`match_phrase`、`match_bool_prefix`、`match_phrase_prefix`、`multi_match`、`query_string`、`simple_query_string`，以及 highlight 通路。 |
| fields / rename | 纯字段引用的选择、重排、重命名及 `_source` 裁剪。 |
| 文档排序/分页 | 支持的直接字段排序、null 顺序、`_score`、字面量 head/fetch/offset。PIT + search_after 分页；offset 由客户端跳过，不能因未使用 ES `from` 就认为分页未实现。 |
| 普通聚合 | 直接字段分组、`COUNT`、部分数值 `SUM/MIN/MAX/AVG`、显式近似去重计数，以及上述条件计数、统计和近似百分位。指标类型/精度守卫仍适用。 |
| 分桶聚合 | 直接时间字段 `span`、受支持的数值 SPAN/固定 BIN、混合分组和 composite 桶分页。使用相同关系计划的 chart/timechart 初始聚合也可受益，但后续排名、OTHER 等不等于已下推。 |

主要证据：[ElasticsearchPredicate](../../elasticsearch/src/main/java/org/opensearch/sql/elasticsearch/ElasticsearchPredicate.java)、[ElasticsearchScan](../../elasticsearch/src/main/java/org/opensearch/sql/elasticsearch/ElasticsearchScan.java)、[Field.exact](../../elasticsearch/src/main/java/org/opensearch/sql/elasticsearch/ElasticsearchQueryBackend.java)。

## 原 15 类盘点项的当前状态

P1 已完成上述安全子集；P2 为有条件的后续优化，P3 为需先证明收益的扩展。保留原编号便于追踪，以下例子省略 `source=logs`。

| # | 能力类别 | 示例 | 当前缺口 / 原生方向 | 优先级与条件 |
| --- | --- | --- | --- | --- |
| 1 | 布尔值、真值测试、未规约的 NOT | `where enabled`；`where NOT enabled` | 已支持 term/exists/bool 组合及 TRUE/FALSE/UNKNOWN 语义。 | **P1 已实施**。不可翻译的组合仍回退。 |
| 2 | 字符串模式过滤 | `where like(path,'/api/%',true)`；`where ilike(path,'%error%')`；`regex path='^/api/'` | 已支持 exact keyword 的 LIKE/ILIKE 与正则字面量子集。 | **P1 已实施安全子集**。复杂正则和不兼容映射仍回退。 |
| 3 | IP 子网过滤 | `where cidrmatch(client_ip,'10.0.0.0/8')` | CIDRMATCH 未识别；标量 `ip` 映射可考虑原生 CIDR/IP 查询。 | P2。需校验 IPv4/IPv6、无效输入、null 与字符串字段的差异。 |
| 4 | 安全 keyword 子字段自动选择 | `where message='timeout'`；`sort message` | text 直接判为非 exact，没有寻找等价 keyword 子字段。 | P2。只在源值等价、跨索引映射一致、无索引值变换时使用；显式引用安全 keyword 字段目前已支持。 |
| 5 | 计算型过滤 | `where bytes > threshold`；`where length(message)>200` | 只接受直接字段与常量；可先做范围/代数改写，再考虑有限 script/runtime 表达式。 | P2。保留类型、精度、时区、null 和异常行为。 |
| 6 | 派生排序键和文档 top-N | `eval magnitude=abs(bytes) \| sort -magnitude \| head 20` | 计算 Project 阻断直接 Sort→Scan 规则；缺表达式排序。 | P2。优先可证明顺序等价的改写，再评估脚本排序及 ES CPU 成本。 |
| 7 | 计算投影及逐行转换 | `eval label=upper(service) \| fields label` | 没有 script/runtime 字段生成与读取通路；fillnull/convert/parse/rex/spath 的可翻译子集类似。 | P3。优先能减少返回数据或解锁过滤/排序的子集；单纯搬移逐行 CPU 未必有益。 |
| 8 | 统计指标 | `stats var_samp(latency), stddev_pop(latency), percentile(latency,95), median(latency) by service` | 已支持 extended_stats / percentiles 及多值响应解码。 | **P1 已实施安全子集**。样本/总体、空组、compression 有回归；精度敏感类型回退。 |
| 9 | 数值分桶与固定 bin 后聚合 | `stats count() by span(latency,100)`；`bin latency span=100 \| stats count() by latency` | 整型 SPAN 使用 long 脚本 terms；安全浮点 SPAN/固定 BIN 使用 histogram。 | **P1 已实施安全子集**。负数截断、大整数、正负零和范围标签约束见上文。 |
| 10 | 条件/计算聚合 | `stats count(eval(status>=500)) as failures by service`；`eval cost=price*quantity \| stats avg(cost) by service` | 已支持 filterArg、受限 CASE 非空计数和条件计数；任意计算指标仍本地。 | **P1 条件聚合已实施**。通用计算聚合后续按表达式分别评估。 |
| 11 | 按显式时间选值 | `stats latest(status,event_time), earliest(status,event_time) by service` | ARG_MAX/ARG_MIN 与双参数未支持；可用排序 top_hits / 合适的 top_metrics。 | P2。必须规定时间相同、字段缺失、null 时的选值行为；不能直接推广到流顺序 first/last。 |
| 12 | 有界 dedup | `sort -event_time \| dedup 2 user` | 落为 ROW_NUMBER/Filter，没有 dedup 识别规则；可研究 composite + bounded top_hits。 | P2。先支持标量键、显式排序、keepempty=false；keepempty=true 的空键记录不能压成一个有界桶。 |
| 13 | 聚合后排序/限制，top/rare 的排名阶段 | `stats count() by host \| sort host \| head 10`；`top 10 host` | 初始分组计数可下推，排名/截断仍本地；Sort 规则遇到已聚合 Scan 会返回。 | P2。先做分组键排序与安全提前停止。多分片按指标 top-N/rare 不能盲目换成 terms，否则可能遗漏全局正确结果。 |
| 14 | 集合聚合 | `stats take(message,5), list(message), values(host) by service` | 聚合器/参数及数组响应解码未实现；bounded take/list 可评估 top_hits。 | P2/P3。需保留去重、重复、null、字符串化、顺序及数量上限。无限 values 不能被默认 top terms 截断。 |
| 15 | chart/timechart 的全局系列排名及 OTHER | `timechart span=1h limit=5 useother=true count() by host` | 本次可优化初始时间分组，系列排名、关联、OTHER 合并、二次聚合仍有本地阶段。 | P2/P3，分阶段执行。每个时间桶各取 top hosts 不等同全局选出 top hosts。 |

### 源码依据与可复用设计

- **1–5**：[ElasticsearchPredicate.translate](../../elasticsearch/src/main/java/org/opensearch/sql/elasticsearch/ElasticsearchPredicate.java) 的 RexInputRef / RexLiteral 限制、比较白名单和 AND/OR 递归；[CidrMatchFunction](../../core/src/main/java/org/opensearch/sql/expression/function/udf/ip/CidrMatchFunction.java)；[ElasticsearchQueryBackend.collectFields / Field.exact](../../elasticsearch/src/main/java/org/opensearch/sql/elasticsearch/ElasticsearchQueryBackend.java)。
- **6–7**：[ElasticsearchScan.PROJECT / SORT / documentRow](../../elasticsearch/src/main/java/org/opensearch/sql/elasticsearch/ElasticsearchScan.java)；可对照 [SortExprIndexScanRule](../../opensearch/src/main/java/org/opensearch/sql/opensearch/planner/rules/SortExprIndexScanRule.java) 和 [ProjectIndexScanRule](../../opensearch/src/main/java/org/opensearch/sql/opensearch/planner/rules/ProjectIndexScanRule.java)。
- **8–11**：[ElasticsearchScan.pushAggregate / aggregateBody / aggregateRow](../../elasticsearch/src/main/java/org/opensearch/sql/elasticsearch/ElasticsearchScan.java)；[CalcitePPLAggregationTest](../../ppl/src/test/java/org/opensearch/sql/ppl/calcite/CalcitePPLAggregationTest.java)；[SpanFunction](../../core/src/main/java/org/opensearch/sql/expression/function/udf/SpanFunction.java)；[PPLFuncImpTable](../../core/src/main/java/org/opensearch/sql/expression/function/PPLFuncImpTable.java)。OpenSearch 的 [AggregateAnalyzer](../../opensearch/src/main/java/org/opensearch/sql/opensearch/request/AggregateAnalyzer.java) 已有 extended_stats、percentiles、ARG_MAX/MIN、部分 top_hits 处理，可参考其语义和响应提取。
- **12–13**：[DedupPushdownRule](../../opensearch/src/main/java/org/opensearch/sql/opensearch/planner/rules/DedupPushdownRule.java)、[RareTopPushdownRule](../../opensearch/src/main/java/org/opensearch/sql/opensearch/planner/rules/RareTopPushdownRule.java)、[AggPushDownAction](../../opensearch/src/main/java/org/opensearch/sql/opensearch/storage/scan/context/AggPushDownAction.java)。规则可参考，但不能照搬所有分布式精度假设。
- **14**：[ListAggFunction](../../core/src/main/java/org/opensearch/sql/calcite/udf/udaf/ListAggFunction.java)、[ValuesAggFunction](../../core/src/main/java/org/opensearch/sql/calcite/udf/udaf/ValuesAggFunction.java) 与 AggregateAnalyzer 的 TAKE 实现。
- **15**：[CalciteRelNodeVisitor.visitChart](../../core/src/main/java/org/opensearch/sql/calcite/CalciteRelNodeVisitor.java)、[AstBuilder](../../ppl/src/main/java/org/opensearch/sql/ppl/parser/AstBuilder.java) 的 timechart 转换。

OpenSearch 通用脚本使用插件自己的 CALCITE/COMPOUNDED 引擎，不能原样发送给 ES；ES 侧通用表达式支持需要独立、有限且类型安全的翻译能力。

## 不应误算为“直接补一条规则即可”的能力

1. **自适应 bin**：`bins/minspan` 依赖全量 min/max 窗口与分桶选择。可能需要同一快照中的“先取边界、再聚合”执行；auto_date_histogram 不自动等同所有 bin 语义。
2. **eventstats/streamstats**：eventstats 需要给原始行附加聚合值，仍需读取文档；streamstats 依赖事件顺序、窗口和 reset。日期桶上的 pipeline aggregation 不能替代任意事件窗口。

以下守卫也不应当为了扩大“覆盖率”而删除：

- **精确去重**：当前解析器将 `dc/distinct_count` 生成精确 COUNT(DISTINCT)，显式 `distinct_count_approx` 已支持下推。虽然部分用户文档称 dc 近似，不能未经契约澄清就改成 cardinality。精确方案需要完整去重桶枚举等不同执行设计。
- **整数精度与溢出**：PPL 整数 sum 使用 CHECKED_LONG_SUM/Math.addExact；ES 数值指标常用 double。BIGINT/DECIMAL 和 checked sum 回退不是可以直接删除的性能限制。
- **源值与索引值差异**：normalizer、ignore_above、null_value、ignore_malformed、数组/嵌套和跨索引类型冲突，需要保留其语义边界。
- **算子位置**：不能把后续过滤推过 head/offset，不能把 limit 放到本地剩余过滤前，也不能将 OR 中一个可下推分支单独作为筛选条件。

全文组合仍有一项限制：P1 已支持非 nullable 全文函数的 `NOT` 并补齐七种函数回归，但 `where match(...) OR length(message)>100` 中不可翻译的分支仍可能把整个 OR 留在本地。[RelevanceQueryFunction](../../core/src/main/java/org/opensearch/sql/expression/function/udf/RelevanceQueryFunction.java) 的本地实现会抛出“only supported when they are pushed down”。这种组合需要进一步的执行或明确拒绝策略；不能用部分下推 OR 来绕过，因为会丢行。顶层 `match AND residual` 已有拆分下推。

## 验证与后续顺序

时间分桶回归位于 [ElasticsearchSpanPushdownTest](../../elasticsearch/src/test/java/org/opensearch/sql/elasticsearch/ElasticsearchSpanPushdownTest.java)，与上述四组 P1 测试一起检查真实 PPL 计划、发出的 DSL、结果/类型、分页/PIT 生命周期及回退行为；原有后端与字段测试同时保留。真实配置仅用于只读验证，没有创建测试索引或修改现有文档。

```bash
./gradlew :elasticsearch:test
./gradlew :spring-boot-example:test --tests '*QueryApplicationLiveTest.elasticsearchPplExecutesRealQuery'
```

真实 ES 集成测试类依赖 `ELASTICSEARCH_TEST_URL` 等独立测试环境变量，缺失时会跳过；不要把跳过记成已验证。Spring 示例测试使用它自己的已配置数据源。

建议后续依次推进：

1. CIDR 和安全 keyword 选择；按实际查询负载决定优先级。
2. 聚合键排序/limit、显式时间选值、bounded dedup；保持分片间精确性。
3. 根据真实负载扩充 P1 中仍回退的模式和数值类型，逐项证明语义等价。
4. 最后评估通用脚本/计算表达式与多阶段 chart，不将“更多远端计算”本身作为性能目标。
