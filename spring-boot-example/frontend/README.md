# Lens 搜索前端

保留 `search-ui` 原型的紧凑布局，使用原生 JavaScript ES Modules 与 Vite。生产入口位于本目录；`search-ui/` 仅为独立静态设计原型。SQL / PPL 原文和结构化条件交给后端执行。

## 开发与打包

需要 Node.js 20.19+ 或 22.12+，以及已启动的 Spring Boot 示例：

```bash
cd spring-boot-example/frontend
npm ci
npm run dev
```

打开 `http://127.0.0.1:5173/`，开发和预览服务器把 `/search`、`/ui` 转发到 `http://127.0.0.1:8080`。其他后端地址可设置 `QUERY_API_TARGET=http://127.0.0.1:8081 npm run dev`。`npm run build` 生成 `dist/`；`npm run preview` 使用 5174 端口。

仓库根目录执行 `./gradlew :spring-boot-example:bootJar`，会安装依赖、构建前端，并将 `dist/` 放入 JAR 的 `static/`。启动 JAR 后访问应用根路径。IDE 启动 Java 前先执行 `:spring-boot-example:processResources`，或使用 Vite。浏览器接口采用相对路径，支持应用上下文路径。

## 查询行为

- `POST /search` 提交 `source、language、query、timeRange、filters、page、histogram、include`。语句中的表名是唯一查询来源，默认表配置仅用于快捷模板；模板不自动添加 `LIMIT/head`。
- 默认使用 PPL 和最近 24 小时。时间范围提供昨天、今天（零点至当前）、最近 24 小时、最近 7 天和自定义；自定义通过日期时间选择器填写，支持毫秒。时间字段和业务时区采用数据源配置，默认 `timestamp` / `Asia/Shanghai`，不显示输入项。选择器中的时间按业务时区提交给后端解析，范围为 `[from,to)`。后端返回 `resolvedTimeRange` 后，翻页和导出复用该固定范围。
- 字段筛选采用 `eq/ne/gt/gte/lt/lte/in/is_null/is_not_null/contains`，按 AND 提交。数值和布尔字段按配置类型提交；`in` 输入 JSON 数组。`contains` 按字符串字面量包含处理。
- 页码和每页条数全部在后台执行，使用 `hasNext` 判断下一页，不用当前页行数代表总命中数。自适应只计算条数，防抖后请求后台；相同条数不重复执行。
- 翻页仅请求 `include:["rows"]`，保留原有图表。图表直接使用后台 `histogram.buckets:[{from,to,count}]`，不会从明细行推导计数；不支持和执行失败分别显示原因。点击桶提交其绝对时间范围并回到第一页。
- 编辑语句、时间或筛选会重置页码。手工编辑等待执行；筛选应用、时间预设和桶点击直接搜索。保留请求中止和版本检查，防止旧响应覆盖新条件。
- 历史记录保存语句、数据源、时间范围和筛选，最近 20 条保存在浏览器；载入后手动执行。相对范围在重新搜索时重新解析。
- 原始采集日志优先展示时间、正文、级别和来源，采集元数据在字段面板中折叠。点击行或展开箭头可在表格内查看 `extracted` 的解析字段，支持键盘 Enter / Space；“全部字段”打开完整记录详情。展开期间保留每页条数，不因详情高度重新查询。
- 普通投影和聚合结果按返回的列顺序展示；详情和 JSON 始终保留完整原始列、重复别名、null、嵌套值和精确数值。

`GET /ui/config` 提供数据源显示配置和顶层 `maxPageSize`，前端按该上限约束自适应条数并禁用超限选项。每个 source 可包括 `table`（快捷模板默认表）、`timeField`、`timeZone` 和 `sourceFields:[{name,type}]`。字段面板将配置源字段与结果列/别名分开；结果列不能直接点击生成源筛选。未配置字段时，可在筛选框显式输入源字段名，后端负责校验。时间字段通过数据源配置指定，不依赖该列出现在查询结果中。

## PPL 语法补全

PPL 模式首次通过 `GET /ui/ppl/grammar` 加载当前引擎的 JSON 语法包，在当前页面缓存并复用；刷新页面后可加载新语法，SQL 模式不加载它。浏览器使用 `antlr4ng 3.0.16` 反序列化并解释 lexer/parser ATN，由 `antlr4-c3 3.4.3` 推导命令、函数、表、字段及操作符候选。完整原文、光标、候选计算与排序都留在前端，补全不向后端发送语句或光标。

输入或移动光标后以 150ms 防抖更新建议，`Ctrl+Space` 可手动触发，方向键选择、`Enter/Tab` 插入、`Escape` 关闭，也可点击候选；`⌘/Ctrl+Enter` 仍执行查询。前端按文本框的 UTF-16 光标与 `[from,to)` 替换范围插入，并保留光标后的内容；组合输入期间暂停提示，模式或来源变化会丢弃旧候选，重新使用当前来源的配置。

`where` 按条件位置引导：空条件处先显示当前源字段，接受字段后自动提示 `=、!=、>、>=、<、<=` 等比较运算符，接受运算符后等待输入值。条件完整后停止提示；输入 `and/or` 后重新显示字段。函数和字段引用可通过输入前缀继续补全，空条件或值的位置不会堆出完整函数列表。

完整语句末尾停止提示尚需参数的续写词，例如 `head 10` 后不再弹出 `from`，`stats count()` 后不再弹出 `as/by`。继续编写时先输入 `|` 或下一词的前缀；例如输入 ` a` 后可补全 `as`，插入时会补上单词分隔空格。数值不会被当成字段前缀，已完整输入的表名或字段不会重复提示自身。

命令、关键字和函数来自语法；表名来自当前 source 的 `table`，源字段来自 `sourceFields` 和 `timeField`。只有语句主表与配置表一致时才显示这些字段；其他表、多表查询和别名不推导字段，也不使用结果列冒充源字段。不包含实时语法错误诊断。

在示例应用配置中填写已知源字段，例如：

```yaml
unified-query:
  ui:
    source-fields:
      logs:
        - name: message
          type: string
        - name: level
          type: string
```

提示加载失败会在编辑器旁显示状态，不影响查询；`Ctrl+Space` 可重试。表名、字段名会按 PPL 标识符规则转义，接受补全只修改当前词或标识符，保留光标后的语句。

## 导出

同步 CSV 使用 `POST /search/export`；异步使用 `POST /search/export/tasks`、`GET /search/export/tasks/{id}` 和 `GET /search/export/tasks/{id}/file`。两者都使用最后一次成功查询的完整冻结上下文，包含绝对时间与 filters，保留用户 `LIMIT/head`，不包含 UI 的 `page/histogram/include`。

异步提交后页面编辑不影响任务。任务 ID 保存在浏览器，刷新可恢复轮询；暂停刷新仅停止浏览器轮询。异步文件使用附件链接直接下载。停止查询等待仅中止浏览器请求，服务端可能继续执行。
