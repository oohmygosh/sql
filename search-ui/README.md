# Lens 数据搜索 UI

独立的前端交互原型，使用 HTML、CSS 和原生 JavaScript，无需安装依赖。以当前项目的 Elasticsearch / ClickHouse、SQL / PPL 查询能力为背景，先验证界面与交互。没有接入后端，也不会读取真实数据或连接配置。

## 预览

直接用浏览器打开 `index.html` 即可使用；或者在此目录运行：

```sh
python3 -m http.server 4173 --bind 127.0.0.1
```

打开 http://127.0.0.1:4173 。查询历史使用浏览器 localStorage，禁止存储时仍可在当前会话使用。

## 设计

- 采用 Apple 原生应用的视觉取向：系统字体、浅灰侧栏、低对比度分隔、蓝色焦点与轻微层次。信息密度更接近桌面搜索工具。
- 紧凑查询块整合 SQL / PPL、数据表、时间范围、查询历史和快捷查询；移除独立标题区与保存搜索。
- 单独的记录抽屉承载详情，保留原有查询上下文。
- 布局适配桌面、平板和手机；主界面按视口高度分配空间，压缩趋势图，分页固定在结果区底部。默认自动计算一屏可容纳的记录数，手动选择较多记录时仅表格内部滚动。窄屏保留水平滚动，导航可展开；极低视口允许页面滚动，避免裁切内容。

参考：[Apple Search fields](https://developer.apple.com/design/human-interface-guidelines/search-fields)、[Kibana Discover](https://www.elastic.co/docs/explore-analyze/discover/)、[Splunk 事件检索](https://help.splunk.com/en/splunk-enterprise/search/search-manual/10.2/retrieve-events/about-retrieving-events)。

## 已实现的交互

- 仅 SQL / PPL 查询；字段比较、多条件 AND、数据源和时间范围切换。
- 字段值筛选、时间柱筛选、结果分页、时间排序、JSON 视图、记录详情。
- 查询块内复用历史；快捷查询随当前语言和数据源生成 SQL / PPL；CSV 导出、键盘快捷键、空状态和输入错误提示。
- SQL / PPL 编辑器与本地有限语法演示。SQL 支持 SELECT、WHERE、ORDER BY、LIMIT；PPL 支持 where、fields、sort、head。它们不是生产查询引擎，复杂语法会显示明确提示。
- 所有计数、分布图和导出均来自相同的确定性样例记录。样例时间固定在 2026-10-18 14:30（UTC+8），与运行时系统时间无关。

实际后端接入位于 `spring-boot-example/frontend/`，使用统一 `POST /search` 协议。该生产入口提交结构化时间与源字段筛选，由后台处理分页和时间桶；同步与异步导出均复用固定查询上下文。本目录保留为独立交互设计原型，所有演示数据和本地语法逻辑均不参与生产打包。
