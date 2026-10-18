<script setup>
import { computed, nextTick, onMounted, onBeforeUnmount, reactive, ref, shallowRef } from 'vue';
import Icon from './components/Icon.vue';
import QueryEditor from './components/QueryEditor.vue';
import Histogram from './components/Histogram.vue';
import ResultsPanel from './components/ResultsPanel.vue';
import WorkspaceDialog from './components/WorkspaceDialog.vue';
import FilterForm from './components/FilterForm.vue';
import ExportProgress from './components/ExportProgress.vue';
import { useWorkspace } from './composables/useWorkspace.js';
import { useExport } from './composables/useExport.js';
import { displayValue } from './results.js';
import { jsonText } from './api.js';
const ui = reactive({ sidebarOpen: false, historyOpen: false, modal: '', toast: '' });
const editor = ref(null),
  downloadLink = ref(null),
  detail = shallowRef(null),
  exportContext = shallowRef(null);
let toastTimer;
function toast(message) {
  clearTimeout(toastTimer);
  ui.toast = message;
  toastTimer = setTimeout(() => {
    ui.toast = '';
  }, 3500);
}
const workspace = useWorkspace(toast);
const { state, sourceFields } = workspace;
const exportManager = useExport({ persist: workspace.persist, toast });
const exportLabel = computed(() =>
  exportManager.state.task
    ? `导出 · ${{ PENDING: '排队', RUNNING: '运行中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' }[exportManager.state.task.state] || exportManager.state.task.state}`
    : '',
);
const modalTitle = computed(
  () =>
    ({ help: '查询工作台', filter: '源字段筛选', export: '导出查询', 'export-status': '导出任务' })[
      ui.modal
    ] || '',
);
const exportDescription = computed(() => {
  const context = exportContext.value;
  if (!context) return '';
  return `${context.source} / ${context.language}\n${context.query}\n${context.timeRange ? `${context.timeRange.from} → ${context.timeRange.to} · ${context.timeRange.timeZone}` : '全部时间'}\n${JSON.stringify(context.filters)}`;
});
function execute() {
  editor.value?.reset();
  return workspace.runSearch();
}
function refresh() {
  editor.value?.reset();
  return workspace.refresh();
}
function prepareTemplate(preset) {
  workspace.prepareTemplate(preset);
  editor.value?.reset();
  nextTick(() => editor.value?.focus());
}
function clearQuery() {
  state.query = '';
  workspace.editQuery();
  editor.value?.reset();
  editor.value?.focus();
}
function loadHistory(index) {
  if (!workspace.loadHistory(index)) return;
  ui.historyOpen = false;
  editor.value?.reset();
  nextTick(() => editor.value?.focus());
}
function openExport() {
  if (!state.lastRequest) return;
  exportContext.value = state.lastRequest;
  exportManager.state.message = '';
  ui.modal = 'export';
}
function showExportStatus() {
  ui.modal = 'export-status';
  exportManager.poll();
}
async function download() {
  if (await exportManager.download(exportContext.value)) {
    await nextTick();
    downloadLink.value?.click();
  }
}
function openDetail(index) {
  const row = state.rows[index];
  if (!row || !state.lastRequest) return;
  detail.value = {
    row,
    schema: state.schema,
    source: state.lastRequest.source,
    number: (state.page - 1) * state.pageSize + index + 1,
  };
}
async function copyRecord() {
  if (!detail.value) return;
  try {
    await navigator.clipboard.writeText(
      jsonText({ schema: detail.value.schema, datarows: [detail.value.row.values] }, null, 2),
    );
    toast('记录 JSON 已复制');
  } catch {
    toast('无法访问剪贴板，请从 JSON 视图复制。');
  }
}
function keyboard(event) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault();
    ui.modal = '';
    detail.value = null;
    nextTick(() => editor.value?.focusAndSelect());
  }
  if (event.key === 'Escape') {
    ui.historyOpen = false;
    ui.sidebarOpen = false;
  }
}
function cleanup() {
  clearTimeout(toastTimer);
  workspace.dispose();
  exportManager.dispose();
}
function pageHide(event) {
  editor.value?.reset();
  if (!event.persisted) cleanup();
  else {
    workspace.suspend();
    exportManager.pause(false);
    clearTimeout(toastTimer);
    ui.toast = '';
  }
}
function pageShow(event) {
  if (event.persisted) exportManager.restore();
}
onMounted(() => {
  workspace.initialize();
  exportManager.restore();
  window.addEventListener('pagehide', pageHide);
  window.addEventListener('pageshow', pageShow);
  window.addEventListener('keydown', keyboard);
});
onBeforeUnmount(() => {
  cleanup();
  window.removeEventListener('pagehide', pageHide);
  window.removeEventListener('pageshow', pageShow);
  window.removeEventListener('keydown', keyboard);
});
</script>
<template>
  <div
    class="workspace-root"
    :class="{ 'is-searching': state.busy }"
    @click="
      ui.historyOpen = false;
      ui.sidebarOpen = false;
    "
  >
    <div class="app-shell">
      <aside
        class="sidebar"
        :class="{ open: ui.sidebarOpen }"
        aria-label="主导航"
        @click.stop="ui.historyOpen = false"
      >
        <a class="brand" href="./" aria-label="Lens 首页"
          ><span class="brand-mark"><i></i><i></i></span
          ><span>lens<span class="brand-dot">.</span></span></a
        >
        <div class="workspace">
          <span class="workspace-symbol">L</span>
          <div><strong>我的工作空间</strong><span>Personal workspace</span></div>
          <span class="workspace-dot" title="本地工作空间"></span>
        </div>
        <p class="nav-heading">工作台</p>
        <nav class="nav-main">
          <button
            class="nav-item active"
            aria-current="page"
            @click="
              ui.sidebarOpen = false;
              editor?.focus();
            "
          >
            <Icon name="search" />数据搜索<span class="nav-shortcut">⌘ K</span>
          </button>
        </nav>
        <div class="section-label">
          <p class="nav-heading">数据源</p>
          <span id="source-count" class="tiny-count">{{ state.sources.length }}</span>
        </div>
        <nav id="source-list" class="nav-sources" aria-label="数据源">
          <button
            v-for="source in state.sources"
            :key="source.name"
            class="source-nav"
            :class="{ selected: state.source === source.name }"
            :data-source="source.name"
            @click="
              workspace.setSource(source.name);
              ui.sidebarOpen = false;
            "
          >
            <span
              class="source-logo"
              :class="source.type === 'clickhouse' ? 'clickhouse' : 'elastic'"
              ><Icon :name="source.type === 'clickhouse' ? 'columns' : 'database'"
            /></span>
            <div>
              <strong>{{ source.type }}</strong
              ><small>{{ source.name }}</small>
            </div>
          </button>
        </nav>
        <div class="sidebar-bottom">
          <button class="help-link" @click="ui.modal = 'help'">
            <Icon name="help" />使用指南<Icon name="arrow-up-right" />
          </button>
          <div class="profile">
            <span class="avatar">L</span>
            <div><strong>本地示例</strong><span>个人工作空间</span></div>
            <span class="profile-status">本地</span>
          </div>
        </div>
      </aside>
      <div class="main-shell">
        <header class="topbar">
          <div class="breadcrumb">
            <button
              class="icon-button mobile-nav-toggle"
              aria-label="切换导航"
              @click.stop="ui.sidebarOpen = !ui.sidebarOpen"
            >
              <Icon name="panel" /></button
            ><Icon name="grid" /><span>工作空间</span><span class="crumb-divider">/</span
            ><strong>数据搜索</strong>
          </div>
          <div class="topbar-end">
            <span
              id="connection-status"
              class="demo-badge"
              role="status"
              :data-status="state.connection"
              >{{ state.status }}</span
            ><button class="icon-button" aria-label="快捷键与帮助" @click="ui.modal = 'help'">
              <Icon name="keyboard" />
            </button>
          </div>
        </header>
        <main id="main-content">
          <h1 class="sr-only">数据搜索</h1>
          <section class="query-section" aria-label="查询编辑器">
            <QueryEditor
              ref="editor"
              v-model="state.query"
              v-model:time-preset="state.timePreset"
              v-model:time-from="state.timeFrom"
              v-model:time-to="state.timeTo"
              :sources="state.sources"
              :source="state.source"
              :mode="state.mode"
              :busy="state.busy"
              :history="state.history"
              :history-open="ui.historyOpen"
              @edit="workspace.editQuery"
              @execute="execute"
              @cancel="workspace.cancelQuery"
              @source="workspace.setSource"
              @mode="workspace.setMode"
              @preset="prepareTemplate"
              @clear="clearQuery"
              @time-change="workspace.timeChanged"
              @picker-change="workspace.pickerChanged"
              @toggle-history="ui.historyOpen = !ui.historyOpen"
              @close-history="ui.historyOpen = false"
              @load-history="loadHistory"
              @delete-history="workspace.deleteHistory"
            />
            <div id="query-error" class="query-error" role="alert" :hidden="!state.error">
              {{ state.error }}
            </div>
            <div id="query-warning" class="query-warning" role="status" :hidden="!state.warning">
              {{ state.warning }}
            </div>
            <div class="filter-row">
              <Icon class="filter-label" name="filter" />
              <div id="filter-chips">
                <span v-for="(filter, index) in state.filters" :key="index" class="filter-chip"
                  ><span>{{ filter.field }}</span
                  ><span>{{ filter.operator }}</span
                  ><b>{{ displayValue(filter.value) }}</b
                  ><button
                    :data-remove-filter="index"
                    :aria-label="`移除筛选 ${index + 1}`"
                    @click="workspace.removeFilter(index)"
                  >
                    <Icon name="x" /></button
                ></span>
              </div>
              <button class="add-filter" :disabled="!state.source" @click="ui.modal = 'filter'">
                <Icon name="plus" />添加源字段筛选
              </button>
            </div>
          </section>
          <Histogram
            :chart="state.chart"
            :visible="state.chartVisible"
            :elapsed="state.elapsed"
            :time-zone="state.timeSettings.timeZone"
            @toggle="state.chartVisible = !state.chartVisible"
            @bucket="workspace.selectBucket"
          />
          <ResultsPanel
            :workspace="workspace"
            :export-label="exportLabel"
            @export="openExport"
            @export-status="showExportStatus"
            @refresh="refresh"
            @detail="openDetail"
          />
          <footer class="page-footer">
            <span><span class="footer-dot"></span>专注每一次发现</span
            ><span>Lens <span class="footer-version">/</span> 数据探索工作台</span>
          </footer>
        </main>
      </div>
    </div>
    <WorkspaceDialog id="modal" :open="!!ui.modal" :title="modalTitle" @close="ui.modal = ''">
      <FilterForm
        v-if="ui.modal === 'filter'"
        :fields="sourceFields"
        @cancel="ui.modal = ''"
        @apply="
          ui.modal = '';
          workspace.addFilter($event);
        "
      />
      <template v-else-if="ui.modal === 'help'"
        ><p class="modal-note">
          SQL / PPL
          原文与源字段筛选共同提交后台。表名以语句为准。时间范围采用左闭右开区间，查询时由后端解析相对时间；翻页和导出复用固定的绝对时间。
        </p>
        <p class="modal-note">
          时间分布由后台在分页前聚合。全部时间、聚合、连接等查询可能无法生成辅助图表，明细仍可独立成功。点击时间桶缩小查询范围。
        </p>
        <p class="modal-note">
          导出使用最后一次成功查询的语句、数据源、时间与筛选，保留手写 LIMIT /
          head。暂停刷新仅停止导出状态轮询。
        </p>
        <div class="help-row"><span>聚焦编辑器</span><kbd>⌘ / Ctrl K</kbd></div>
        <div class="help-row"><span>执行查询</span><kbd>⌘ / Ctrl Enter</kbd></div>
        <div class="help-row">
          <span>查询补全 · 方向键选择，Enter/Tab 插入，Esc 关闭</span><kbd>Ctrl Space</kbd>
        </div></template
      >
      <template v-else-if="ui.modal === 'export'"
        ><p class="modal-note">
          重新执行下面这条成功查询。包含当前成功查询的时间与源字段筛选，保留 LIMIT /
          head；数据变化可能使导出结果与表格不同。
        </p>
        <pre class="export-query">{{ exportDescription }}</pre>
        <div class="modal-actions">
          <button
            id="sync-export"
            class="button secondary"
            :disabled="exportManager.state.downloading"
            @click="download"
          >
            直接下载 CSV</button
          ><button
            id="async-export"
            class="button primary"
            :disabled="exportManager.state.submitting || exportManager.active.value"
            @click="exportManager.submit(exportContext)"
          >
            创建异步导出
          </button>
        </div>
        <p id="export-error" class="modal-note" role="alert">
          {{ exportManager.state.message }}
        </p></template
      >
      <p v-else-if="ui.modal === 'export-status'" class="modal-note">
        任务由当前示例服务保留；刷新页面可继续查看。暂停刷新不会取消服务端任务。
      </p>
      <ExportProgress
        v-if="ui.modal === 'export' || ui.modal === 'export-status'"
        :manager="exportManager"
      />
      <a ref="downloadLink" :href="exportManager.state.downloadUrl" download="result.csv" hidden
        >下载 CSV</a
      >
    </WorkspaceDialog>
    <WorkspaceDialog
      id="detail-drawer"
      :open="!!detail"
      title="记录详情"
      drawer
      @close="detail = null"
      ><div v-if="detail" id="detail-content">
        <div class="detail-index">
          <Icon name="database" />{{ detail.source }} · 第 {{ detail.number }} 条
        </div>
        <div v-for="(column, index) in detail.schema" :key="index" class="detail-field">
          <span
            >{{ column.name }}<small>{{ column.type }}</small></span
          ><code>{{ displayValue(detail.row.values[index]) }}</code>
        </div>
        <div class="detail-actions">
          <button class="button secondary" @click="copyRecord">
            <Icon name="copy" />复制 JSON
          </button>
        </div>
      </div></WorkspaceDialog
    >
    <div id="toast" role="status" aria-live="polite" :class="{ visible: !!ui.toast }">
      {{ ui.toast }}
    </div>
  </div>
</template>
