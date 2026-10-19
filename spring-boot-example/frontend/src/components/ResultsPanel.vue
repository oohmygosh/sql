<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue';
import Icon from './Icon.vue';
import FieldExplorer from './FieldExplorer.vue';
import LogRow from './LogRow.vue';
import { displayValue } from '../results.js';
import { jsonText } from '../api.js';
const props = defineProps({ workspace: Object, exportLabel: String });
const emit = defineEmits(['export', 'export-status', 'refresh', 'detail']);
const w = props.workspace;
const { state, columns, layout, pageSummary, canUseResultFields } = w;
const viewport = ref(null),
  jsonView = ref(null);
const pageSizes = computed(() =>
  [...new Set([8, 20, 50, Math.min(20, state.maxPageSize)])].sort((a, b) => a - b),
);
const narrow = ref(false),
  fieldsOverride = ref(null);
const fieldsVisible = computed(() => fieldsOverride.value ?? !narrow.value);
const logView = computed(() => !!layout.value && state.visibleColumns === null);
const json = computed(() =>
  jsonText({ schema: state.schema, datarows: state.rows.map((row) => row.values) }, null, 2),
);
let media;
function mediaChanged(event) {
  narrow.value = event.matches;
}
watch(
  () => state.scrollVersion,
  () => {
    if (viewport.value) viewport.value.scrollTop = 0;
    if (jsonView.value) jsonView.value.scrollTop = 0;
  },
  { flush: 'post' },
);
onMounted(() => {
  media = window.matchMedia('(max-width: 980px)');
  narrow.value = media.matches;
  media.addEventListener('change', mediaChanged);
});
onBeforeUnmount(() => {
  media?.removeEventListener('change', mediaChanged);
});
function isLevel(column, value) {
  return (
    ['level', 'severity', 'log.level'].includes(column.name.toLowerCase()) &&
    ['INFO', 'WARN', 'ERROR'].includes(displayValue(value))
  );
}
const number = (row) => (state.page - 1) * state.pageSize + row.index + 1;
</script>
<template>
  <section class="results-card" aria-label="查询结果" :aria-busy="state.busy">
    <div class="results-toolbar">
      <div class="result-tabs" role="tablist" aria-label="结果视图">
        <button
          role="tab"
          :class="{ selected: state.view === 'table' }"
          :aria-selected="state.view === 'table'"
          data-view="table"
          @click="state.view = 'table'"
        >
          <Icon name="list" />数据<span id="tab-count" class="tab-count">{{
            state.rows.length.toLocaleString()
          }}</span></button
        ><button
          role="tab"
          :class="{ selected: state.view === 'json' }"
          :aria-selected="state.view === 'json'"
          data-view="json"
          @click="state.view = 'json'"
        >
          <Icon name="code" />JSON
        </button>
      </div>
      <div class="result-tools">
        <button
          v-if="exportLabel"
          id="export-status-button"
          class="text-button"
          @click="emit('export-status')"
        >
          {{ exportLabel }}</button
        ><button
          class="text-button field-toggle"
          aria-label="字段探索与显示列"
          :aria-expanded="fieldsVisible"
          @click="fieldsOverride = !fieldsVisible"
        >
          <Icon name="panel" /><span>字段 / 列</span></button
        ><span class="tools-divider"></span
        ><button
          class="text-button"
          data-action="export"
          :disabled="!state.lastRequest"
          @click="emit('export')"
        >
          <Icon name="download" /><span>导出查询</span></button
        ><button
          class="icon-button"
          data-action="refresh"
          aria-label="重新执行查询"
          @click="emit('refresh')"
        >
          <Icon name="refresh" />
        </button>
      </div>
    </div>
    <div class="result-notices">
      <p id="elapsed" class="rows-elapsed" role="status" aria-live="polite">
        明细查询 · {{ state.elapsed }}
      </p>
      <div id="query-warning" class="query-warning" role="status" :hidden="!state.warning">
        <strong>明细查询提示</strong> · {{ state.warning }}
      </div>
    </div>
    <div class="results-body">
      <FieldExplorer
        :state="state"
        :columns="columns"
        :layout="layout"
        :can-filter="canUseResultFields"
        :style="{ display: fieldsVisible ? 'block' : 'none' }"
        @column="w.toggleVisibleColumn"
        @expand="state.activeField = state.activeField === $event ? null : $event"
        @filter="w.applyQuickFilter"
        @all="w.selectColumns(state.schema.map((_, index) => index))"
        @reset="w.selectColumns(null)"
      />
      <div class="data-panel">
        <div
          id="table-view"
          ref="viewport"
          class="table-scroll"
          :hidden="!state.rows.length || state.view !== 'table'"
        >
          <table
            :class="{
              'log-table': logView,
              'selected-log-columns': !!layout && state.visibleColumns !== null,
            }"
          >
            <thead>
              <tr v-if="logView">
                <th class="expand-col"><span class="sr-only">展开日志</span></th>
                <th class="log-time-heading">时间</th>
                <th>日志内容<span class="log-column-hint">点击行展开 extracted</span></th>
              </tr>
              <tr v-else>
                <th class="expand-col"><span class="sr-only">详情</span></th>
                <th v-for="index in columns" :key="index" :title="state.schema[index].type">
                  {{ state.schema[index].name }}<small>{{ state.schema[index].type }}</small>
                </th>
              </tr>
            </thead>
            <tbody id="results-rows">
              <template v-if="logView"
                ><LogRow
                  v-for="row in state.rows"
                  :key="row.index"
                  :row="row"
                  :layout="layout"
                  :expanded="state.expandedRows.has(row.index)"
                  :number="number(row)"
                  @toggle="w.toggleLogRow"
                  @detail="emit('detail', $event)" /></template
              ><template v-else
                ><tr
                  v-for="row in state.rows"
                  :key="row.index"
                  class="result-row"
                  tabindex="0"
                  :data-row-id="row.index"
                  :aria-label="`查看第 ${number(row)} 条记录`"
                  @click="emit('detail', row.index)"
                  @keydown.enter.self.prevent="emit('detail', row.index)"
                  @keydown.space.self.prevent="emit('detail', row.index)"
                >
                  <td><Icon name="chevron-right" /></td>
                  <td
                    v-for="index in columns"
                    :key="index"
                    :title="displayValue(row.values[index])"
                  >
                    <span
                      v-if="isLevel(state.schema[index], row.values[index])"
                      class="level-badge"
                      :class="displayValue(row.values[index])"
                      >{{ displayValue(row.values[index]) }}</span
                    ><span
                      v-else
                      class="cell-value"
                      :class="{ 'null-value': row.values[index] === null }"
                      >{{ displayValue(row.values[index]) }}</span
                    >
                  </td>
                </tr></template
              >
            </tbody>
          </table>
        </div>
        <div id="json-view" ref="jsonView" class="json-view" :hidden="state.view !== 'json'">
          <pre id="json-content">{{ json }}</pre>
        </div>
        <div
          id="empty-state"
          class="empty-state"
          :hidden="!!state.rows.length || state.view === 'json'"
        >
          <Icon name="search" />
          <h3>{{ state.lastRequest ? '暂时没有匹配的数据' : '准备探索数据' }}</h3>
          <p>执行 SQL / PPL，或调整查询时间与源字段筛选。</p>
          <button class="button secondary" @click="w.resetFilters">清除筛选</button>
        </div>
        <footer class="result-footer">
          <span id="page-summary" title="页码由后台执行，不提供总命中数。">{{ pageSummary }}</span>
          <div class="pagination">
            <label
              ><span class="sr-only">每页条数</span
              ><select
                id="page-size"
                v-model="state.pageSizeChoice"
                :disabled="state.busy"
                @change="w.pageSizeChanged"
              >
                <option
                  v-for="size in pageSizes"
                  :key="size"
                  :value="String(size)"
                  :disabled="size > state.maxPageSize"
                >
                  {{ size }} 条 / 页
                </option>
              </select></label
            ><button
              id="prev-page"
              class="icon-button"
              aria-label="上一页"
              :disabled="state.busy || state.dirty || state.page <= 1"
              @click="w.runSearch({ number: state.page - 1, continuation: true })"
            >
              <Icon name="chevron-left" /></button
            ><span id="page-number">{{ state.page }}</span
            ><button
              id="next-page"
              class="icon-button"
              aria-label="下一页"
              :disabled="state.busy || state.dirty || !state.hasNext"
              @click="w.runSearch({ number: state.page + 1, continuation: true })"
            >
              <Icon name="chevron-right" />
            </button>
          </div>
        </footer>
      </div>
    </div>
  </section>
</template>
