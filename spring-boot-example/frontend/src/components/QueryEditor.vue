<script setup>
import { computed, ref, nextTick, watch, onMounted, onBeforeUnmount } from 'vue';
import Icon from './Icon.vue';
import { createQueryAssist, kindLabels } from '../query-assist.js';
const query = defineModel({ type: String, default: '' });
const timePreset = defineModel('timePreset');
const timeFrom = defineModel('timeFrom');
const timeTo = defineModel('timeTo');
const props = defineProps({
  sources: Array,
  source: String,
  mode: String,
  busy: Boolean,
  history: Array,
  historyOpen: Boolean,
});
const emit = defineEmits([
  'edit',
  'execute',
  'cancel',
  'mode',
  'source',
  'preset',
  'clear',
  'time-change',
  'picker-change',
  'toggle-history',
  'close-history',
  'load-history',
  'delete-history',
]);
const input = ref(null),
  focused = ref(false),
  options = [];
const assist = createQueryAssist({
  getSnapshot: () => ({
    mode: props.mode,
    source: props.source,
    text: query.value,
    caret: input.value?.selectionStart ?? 0,
    end: input.value?.selectionEnd ?? 0,
  }),
  isFocused: () => focused.value,
  async applyEdit(text, caret) {
    query.value = text;
    emit('edit');
    await nextTick();
    input.value?.focus();
    input.value?.setSelectionRange(caret, caret);
  },
  async onSelect(index) {
    await nextTick();
    options[index]?.scrollIntoView({ block: 'nearest' });
  },
});
const suggestions = assist.state;
const supported = assist.supported;
const expanded = computed(() => suggestions.candidates.length > 0);
function keydown(event) {
  assist.handlers.keydown(event);
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter' && !event.isComposing) {
    event.preventDefault();
    emit('execute');
  }
}
function onInput(event) {
  emit('edit');
  assist.handlers.input(event);
}
function focus() {
  input.value?.focus();
}
function focusAndSelect() {
  focus();
  input.value?.select();
}
function reset() {
  assist.reset();
}
function onFocus() {
  focused.value = true;
  assist.handlers.focus();
}
function onBlur() {
  focused.value = false;
  assist.handlers.blur();
}
watch(
  () => [props.mode, props.source],
  () => assist.contextChanged(),
  { flush: 'post' },
);
onMounted(assist.contextChanged);
onBeforeUnmount(assist.dispose);
defineExpose({ focus, focusAndSelect, reset });
</script>
<template>
  <form id="search-form" class="query-card" @submit.prevent="emit('execute')">
    <div class="query-toolbar">
      <div class="segmented" role="tablist" aria-label="查询语言">
        <button
          v-for="mode in ['sql', 'ppl']"
          :key="mode"
          type="button"
          role="tab"
          :data-mode="mode"
          :aria-selected="props.mode === mode"
          :class="{ selected: props.mode === mode }"
          @click="emit('mode', mode)"
        >
          {{ mode.toUpperCase() }}
        </button>
      </div>
      <span class="control-divider"></span>
      <div class="query-controls">
        <label class="select-control"
          ><Icon name="database" /><select
            id="dataset-select"
            :value="source"
            aria-label="数据源"
            :disabled="!sources.length"
            @change="emit('source', $event.target.value)"
          >
            <option v-for="item in sources" :key="item.name" :value="item.name">
              {{ item.name }}
            </option></select
          ><Icon name="chevron-down"
        /></label>
      </div>
      <button
        type="button"
        class="history-trigger"
        aria-label="查询历史"
        :aria-expanded="historyOpen"
        aria-controls="query-history-panel"
        @click.stop="
          reset();
          emit('toggle-history');
        "
      >
        <Icon name="history" /><span>查询历史</span><Icon name="chevron-down" />
      </button>
    </div>
    <div class="query-editor-row">
      <div class="query-input-wrap">
        <span class="editor-line-number" aria-hidden="true">1</span
        ><label class="sr-only" for="query-input">查询语句</label>
        <textarea
          id="query-input"
          ref="input"
          v-model="query"
          class="code-input"
          rows="2"
          spellcheck="false"
          :aria-label="`${mode.toUpperCase()} 查询`"
          aria-describedby="ppl-assist-status"
          aria-controls="ppl-completions"
          :aria-autocomplete="supported ? 'list' : 'none'"
          :aria-expanded="expanded"
          :aria-activedescendant="
            expanded ? `ppl-completions-candidate-${suggestions.selected}` : undefined
          "
          :placeholder="`输入 ${mode.toUpperCase()} 查询${supported ? ' · Ctrl+Space 补全' : ''}`"
          @input="onInput"
          @focus="onFocus"
          @blur="onBlur"
          @keydown="keydown"
          @keyup="assist.handlers.keyup"
          @click="assist.handlers.click"
          @select="assist.handlers.select"
          @compositionstart="assist.handlers.compositionstart"
          @compositionend="assist.handlers.compositionend"
        ></textarea>
        <div
          id="ppl-completions"
          class="ppl-completions"
          role="listbox"
          :aria-label="`${mode.toUpperCase()} 补全建议`"
          :hidden="!expanded"
          @mousedown.prevent
        >
          <button
            v-for="(item, index) in suggestions.candidates"
            :id="`ppl-completions-candidate-${index}`"
            :key="index"
            :ref="(element) => (options[index] = element)"
            type="button"
            role="option"
            :aria-selected="index === suggestions.selected"
            :aria-label="[item.label, item.detail].filter(Boolean).join(' · ')"
            :data-label="item.label"
            :data-kind="item.kind"
            :title="item.detail || kindLabels[item.kind] || '建议'"
            @click.stop="assist.accept(index)"
          >
            <span>{{ item.label }}</span
            ><small>{{ item.detail || kindLabels[item.kind] || '建议' }}</small>
          </button>
        </div>
      </div>
      <button type="submit" class="button primary" id="search-button" :disabled="busy || !source">
        <Icon name="play" /><span>{{ busy ? '查询中' : '执行查询' }}</span>
      </button>
      <button
        type="button"
        class="button secondary"
        id="cancel-query"
        :hidden="!busy"
        @click="emit('cancel')"
      >
        停止等待
      </button>
    </div>
    <p
      id="ppl-assist-status"
      class="ppl-assist-status"
      role="status"
      aria-live="polite"
      :hidden="!suggestions.message"
    >
      {{ suggestions.message }}
    </p>
    <div class="search-time-controls">
      <label
        >时间范围<select
          id="time-select"
          v-model="timePreset"
          aria-label="查询时间范围"
          @change="emit('time-change')"
        >
          <option value="yesterday">昨天</option>
          <option value="today">今天</option>
          <option value="1d">最近 24 小时</option>
          <option value="7d">最近 7 天</option>
          <option value="custom">自定义</option>
        </select></label
      >
      <span id="absolute-time-controls" :hidden="timePreset !== 'custom'"
        ><label
          >开始时间<input
            id="time-from"
            v-model="timeFrom"
            type="datetime-local"
            step="0.001"
            aria-label="开始时间"
            @input="emit('picker-change')" /></label
        ><span class="time-range-separator" aria-hidden="true">至</span
        ><label
          >结束时间<input
            id="time-to"
            v-model="timeTo"
            type="datetime-local"
            step="0.001"
            aria-label="结束时间"
            @input="emit('picker-change')" /></label
      ></span>
    </div>
    <div class="query-bottom">
      <div class="quick-queries" aria-label="快捷查询">
        <span class="quick-label"><Icon name="bolt" />快捷查询</span
        ><button
          v-for="preset in [
            { key: 'sample', label: '查询明细' },
            { key: 'count', label: '记录总数' },
            { key: 'distribution', label: '时间分布' },
          ]"
          :key="preset.key"
          type="button"
          :data-preset="preset.key"
          @click="emit('preset', preset.key)"
        >
          {{ preset.label }}
        </button>
      </div>
      <div class="editor-actions">
        <span class="run-hint"><kbd>⌘ ↵</kbd> 执行</span
        ><button type="button" class="icon-button" aria-label="清空查询" @click="emit('clear')">
          <Icon name="eraser" />
        </button>
      </div>
    </div>
    <section
      id="query-history-panel"
      class="query-history-panel"
      aria-label="查询历史列表"
      :hidden="!historyOpen"
      @click.stop
    >
      <div class="history-heading">
        <strong>查询历史</strong><span>最近 20 条</span
        ><button
          type="button"
          class="icon-button"
          aria-label="关闭查询历史"
          @click="emit('close-history')"
        >
          <Icon name="x" />
        </button>
      </div>
      <div id="query-history-list" class="query-history-list">
        <div v-for="(item, index) in history" :key="item.at" class="history-item">
          <button
            type="button"
            class="history-query"
            :aria-label="`载入 ${item.query}`"
            @click="emit('load-history', index)"
          >
            <span class="history-meta"
              ><b>{{ item.language }}</b
              ><span>{{ item.source }}</span
              ><span
                >{{
                  item.timeRange ? `${item.timeRange.from} → ${item.timeRange.to}` : '全部时间'
                }}
                · {{ item.filters.length }} 个筛选</span
              ></span
            ><code>{{ item.query }}</code></button
          ><button
            type="button"
            class="icon-button"
            :aria-label="`删除历史 ${index + 1}`"
            @click="emit('delete-history', index)"
          >
            <Icon name="x" />
          </button>
        </div>
        <div v-if="!history.length" class="history-empty">
          还没有查询历史<span>查询成功后保存在此浏览器中。</span>
        </div>
      </div>
    </section>
  </form>
</template>
