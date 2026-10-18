# Lens 查询前端

保留 `search-ui` 原型的紧凑布局，使用原生 JavaScript ES Modules 和 Vite。真实调用示例应用的 HTTP 接口，不生成模拟数据，不解析或改写用户输入的 SQL / PPL。

## 开发

需要 Node.js 20.19+ 或 22.12+，以及已启动的 Spring Boot 示例：

```bash
cd spring-boot-example/frontend
npm ci
npm run dev
```

打开 `http://127.0.0.1:5173/`。开发服务器把 `/query`、`/ui` 转发到 `http://127.0.0.1:8080`。其他端口可设置：

```bash
QUERY_API_TARGET=http://127.0.0.1:8081 npm run dev
```

只构建前端：`npm run build`；产物在 `dist/`。`npm run preview` 使用 5174 端口，并使用相同的接口代理配置。

## 随示例打包

仓库根目录运行 `./gradlew :spring-boot-example:bootJar`，会依次执行 `npm ci`、`npm run build`，将 `dist/` 放进 JAR 的 `static/`。启动 JAR 后直接访问应用根路径。开发依赖不会进入 JAR；部署机器只需要 Java。

IDE 直接启动 Java 入口前，需要先运行 `:spring-boot-example:processResources`，或使用独立 Vite 开发服务器。前端请求使用相对路径，支持 Spring Boot servlet context path；开发模式代理目标应指向对应后端上下文。

## 能力范围

| 功能 | 当前接入方式 |
| --- | --- |
| SQL / PPL 查询 | `POST /query`，原文提交，支持引擎已有语法 |
| 数据源 | `GET /ui/config`，仅返回名称、类型、默认表名，不返回连接配置 |
| 表 / 索引 | 手动输入，用于生成快捷查询；手写 SQL/PPL 以语句为准 |
| 字段、表格、详情、JSON | 从 `schema` + `datarows` 动态生成，保留空 schema、嵌套值、null、重复别名和精确数值 |
| 分页、字段值、筛选、时间范围、柱状图 | 浏览器处理本次响应，不代表全表命中数；大结果仍受普通查询上限约束 |
| 查询历史 | 成功查询保存在当前浏览器，最多 20 条；载入后手动执行 |
| 直接 CSV | `POST /query/export`，重新执行上次成功查询 |
| 异步 CSV | 提交 `/query/export/tasks`、轮询状态、成功后从 `/file` 下载；当前任务 ID 保存在浏览器，可刷新恢复 |
| 停止等待 | 中止浏览器请求；不能取消服务端查询 |

时间字段优先选择 `timestamp` / `@timestamp` 等常见名称，否则选择 timestamp/date 类型。没有可解析的时间就显示提示；无时区字符串按浏览器本地时区解释。图表纵轴统计**返回行数**，不会把聚合结果的 `total` 自动当作日志条数。最近时间范围以当前时间为准。

导出重新执行上次成功查询，保留原有 `LIMIT` / `head`，不包含本地筛选。异步下载直接使用服务端附件链接，不将大文件全部读入浏览器内存。暂停刷新仅停止状态轮询，不取消任务。任务过期或服务重启后需要重新创建。

尚待后续评估：表/索引与完整字段元数据发现、游标分页、全量趋势与命中总数、把可视筛选安全编译为任意 SQL/PPL 条件、查询取消、共享历史和鉴权。这些能力未通过修改引擎或猜测数据补齐。

## 验证

```bash
npm test                 # 数据契约、精度、筛选、时间桶、快捷语句
npm run build
npx playwright install chromium  # 首次运行浏览器检查
UI_URL=http://127.0.0.1:8080/ npm run test:browser
UI_URL=http://127.0.0.1:8080/ node test/browser-contract.mjs
```

`test:browser` 连接实际示例服务，对已配置的 `logs` / `analytics` 和表中 `id,timestamp,message` 做小规模只读查询，并创建 3 行导出任务。覆盖两个后端、两种语言、详情、筛选、空结果、语法错误、历史和桌面/移动端布局；产物在 `../build/ui-verification/`。测试中的 `log_event` 需与示例表一致。

`browser-contract.mjs` 仅拦截查询响应，验证真实后端不便稳定制造的精度、HTML 转义、warning、429 和请求竞态。它不能替代真实数据源验收。
