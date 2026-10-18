import { computed, shallowReactive } from 'vue';
import { request, normalizeResponse, jsonText } from '../api.js';
import { displayValue, queryTemplate } from '../results.js';
import {
  isDirectSourceQuery,
  quickValueFilter,
  visibleColumnIndexes,
  toggleColumn,
} from '../fields.js';
import { getLogLayout } from '../log-results.js';
import { copyContext, freezeContext, searchBody } from '../search-context.js';
import { timePresets, toDateTimeInput, validatePickerTimeRange } from '../time-range.js';

const historyKey = 'lens.search.history.v3';
export function useWorkspace(toast) {
  const state = shallowReactive({
    sources: [],
    source: '',
    mode: 'ppl',
    query: '',
    view: 'table',
    schema: [],
    rows: [],
    filters: [],
    timeSettings: { field: 'timestamp', timeZone: 'Asia/Shanghai' },
    timePreset: '1d',
    timeFrom: '',
    timeTo: '',
    customTimeRange: null,
    expandedRows: new Set(),
    visibleColumns: null,
    columnSchema: null,
    activeField: null,
    fieldSearch: '',
    chart: null,
    chartVisible: true,
    page: 1,
    pageSize: 20,
    pageSizeChoice: '20',
    hasNext: false,
    dirty: true,
    history: readHistory(),
    lastRequest: null,
    lastQueryIdentity: null,
    maxPageSize: 1000,
    busy: false,
    error: '',
    warning: '',
    elapsed: '等待查询',
    status: '连接中',
    connection: '',
    scrollVersion: 0,
  });
  const drafts = new Map();
  const configController = new AbortController();
  let requestId = 0,
    controller,
    disposed = false;
  const sourceConfig = computed(
    () => state.sources.find((item) => item.name === state.source) || {},
  );
  const canUseResultFields = computed(
    () =>
      !!state.lastRequest &&
      !state.dirty &&
      isDirectSourceQuery(state.lastRequest.query, state.lastRequest.language),
  );
  const sourceFields = computed(() => {
    const configured = Array.isArray(sourceConfig.value.sourceFields)
      ? sourceConfig.value.sourceFields.filter(
          (field) => field && typeof field.name === 'string' && typeof field.type === 'string',
        )
      : [];
    return [
      ...configured,
      ...(canUseResultFields.value ? state.schema : []).filter(
        (field) => !configured.some((item) => item.name === field.name),
      ),
    ];
  });
  const layout = computed(() => getLogLayout(state.schema));
  const columns = computed(() => visibleColumnIndexes(state.schema, state.visibleColumns));
  const pageSummary = computed(() => {
    if (state.busy) return state.lastRequest ? '正在更新 · 显示上次结果' : '正在查询…';
    if (state.dirty && state.lastRequest) return '条件已修改 · 显示上次结果';
    if (!state.lastRequest) return '等待查询';
    const first = state.rows.length ? (state.page - 1) * state.pageSize + 1 : 0;
    return `${first}–${state.rows.length ? first + state.rows.length - 1 : 0} 行${state.hasNext ? ' · 还有下一页' : ' · 已到末页'}`;
  });
  function readHistory() {
    try {
      const data = JSON.parse(localStorage.getItem(historyKey));
      return Array.isArray(data)
        ? data
            .filter(
              (item) =>
                item &&
                typeof item.query === 'string' &&
                typeof item.source === 'string' &&
                ['SQL', 'PPL'].includes(item.language) &&
                Array.isArray(item.filters),
            )
            .slice(0, 20)
        : [];
    } catch {
      return [];
    }
  }
  function persist(key, data) {
    try {
      localStorage.setItem(key, JSON.stringify(data));
    } catch {
      toast('浏览器存储不可用，记录仅在当前页面保留。');
    }
  }
  function invalidateRequest() {
    requestId++;
    controller?.abort();
    controller = null;
    state.busy = false;
  }
  function clearResults() {
    Object.assign(state, {
      expandedRows: new Set(),
      activeField: null,
      schema: [],
      rows: [],
      lastRequest: null,
      lastQueryIdentity: null,
      chart: null,
      page: 1,
      hasNext: false,
      dirty: true,
      warning: '',
      elapsed: '等待查询',
      error: '',
    });
  }
  function conditionsChanged() {
    state.dirty = true;
    invalidateRequest();
  }
  const draftKey = () => `${state.source}:${state.mode}`;
  function saveDraft() {
    drafts.set(draftKey(), state.query);
  }
  function editQuery() {
    saveDraft();
    conditionsChanged();
  }
  function setMode(mode) {
    if (mode === state.mode) return;
    saveDraft();
    invalidateRequest();
    state.mode = mode;
    Object.assign(state, {
      visibleColumns: null,
      columnSchema: null,
      fieldSearch: '',
      query:
        drafts.get(draftKey()) ??
        (sourceConfig.value.table ? queryTemplate(mode, sourceConfig.value.table) : ''),
    });
    clearResults();
  }
  function setSource(source) {
    const config = state.sources.find((item) => item.name === source);
    if (!config) return;
    saveDraft();
    invalidateRequest();
    state.source = source;
    Object.assign(state, {
      filters: [],
      visibleColumns: null,
      columnSchema: null,
      fieldSearch: '',
      query:
        drafts.get(draftKey()) ?? (config.table ? queryTemplate(state.mode, config.table) : ''),
      timeSettings: {
        field: config.timeField || 'timestamp',
        timeZone: config.timeZone || 'Asia/Shanghai',
      },
      customTimeRange: null,
      timeFrom: '',
      timeTo: '',
      timePreset: '1d',
    });
    clearResults();
  }
  function prepareTemplate(preset) {
    try {
      const mode = preset === 'distribution' ? 'ppl' : state.mode;
      const query = queryTemplate(
        mode,
        sourceConfig.value.table || '',
        preset,
        state.timeSettings.field,
      );
      setMode(mode);
      state.query = query;
      editQuery();
      state.error = '';
      toast('已填入查询，点击「执行查询」运行。');
    } catch (error) {
      state.error = error.message;
    }
  }
  function readContext() {
    const query = state.query.trim();
    if (!query) throw new Error('请输入 SQL 或 PPL 查询。');
    if (!state.source) throw new Error('尚未加载数据源，请重新加载页面。');
    let range = timePresets[state.timePreset];
    if (state.timePreset === 'custom') {
      const saved = state.customTimeRange;
      range =
        saved && saved.fromInput === state.timeFrom && saved.toInput === state.timeTo
          ? saved.range
          : validatePickerTimeRange(state.timeFrom, state.timeTo);
    }
    return {
      source: state.source,
      language: state.mode.toUpperCase(),
      query,
      filters: copyContext(state.filters),
      timeRange: { ...state.timeSettings, ...range },
    };
  }
  function setCustomTimeRange(range) {
    const display = (value) =>
      /(?:Z|[+-]\d{2}:\d{2})$/i.test(value)
        ? toDateTimeInput(value, state.timeSettings.timeZone)
        : value;
    state.timeFrom = display(range.from);
    state.timeTo = display(range.to);
    state.customTimeRange = {
      range: copyContext(range),
      fromInput: state.timeFrom,
      toInput: state.timeTo,
    };
  }
  function applyTimeRange(range) {
    if (!range) {
      state.timePreset = '1d';
      return;
    }
    state.timeSettings = {
      field: range.field || state.timeSettings.field,
      timeZone: range.timeZone || state.timeSettings.timeZone,
    };
    const preset = Object.keys(timePresets).find(
      (key) => timePresets[key].from === range.from && timePresets[key].to === range.to,
    );
    if (preset || (range.from === 'now-1d' && range.to === 'now'))
      state.timePreset = preset || '1d';
    else if (range.from.startsWith('now')) {
      const legacy = /^now-(15m|1h)$/.exec(range.from);
      state.timePreset = legacy && range.to === 'now' ? 'custom' : '1d';
      if (state.timePreset === 'custom') {
        const now = Date.now();
        setCustomTimeRange({
          from: new Date(now - (legacy[1] === '15m' ? 900000 : 3600000)).toISOString(),
          to: new Date(now).toISOString(),
        });
      }
    } else {
      state.timePreset = 'custom';
      setCustomTimeRange(range);
    }
  }
  function timeChanged() {
    if (state.timePreset === 'custom' && !state.timeFrom && !state.timeTo) {
      const now = Date.now();
      setCustomTimeRange(
        state.lastRequest?.timeRange || {
          from: new Date(now - 86400000).toISOString(),
          to: new Date(now).toISOString(),
        },
      );
    }
    conditionsChanged();
    if (state.timePreset !== 'custom' && state.query.trim()) runSearch();
  }
  function pickerChanged() {
    state.customTimeRange = null;
    conditionsChanged();
  }
  function requestedPageSize() {
    return Math.min(state.maxPageSize, Number(state.pageSizeChoice));
  }
  async function runSearch({
    number = 1,
    continuation = false,
    size = continuation ? state.pageSize : requestedPageSize(),
  } = {}) {
    if (disposed) return;
    let context;
    try {
      context = continuation ? state.lastRequest : readContext();
      if (!context) return;
    } catch (error) {
      state.error = error.message;
      return;
    }
    const identity = JSON.stringify(context);
    const preservePosition =
      number === state.page && (continuation || identity === state.lastQueryIdentity);
    invalidateRequest();
    state.error = '';
    saveDraft();
    const id = requestId;
    controller = new AbortController();
    state.busy = true;
    state.elapsed = '正在执行查询…';
    const started = performance.now();
    try {
      const result = normalizeResponse(
        await request('search', {
          body: searchBody(context, number, size, continuation ? ['rows'] : ['rows', 'histogram']),
          signal: controller.signal,
        }),
      );
      if (id !== requestId || disposed) return;
      if (!result.rows)
        throw new Error(
          result.errors?.rows?.message ||
            result.errors?.rows ||
            '明细查询失败，服务端未返回 rows。',
        );
      const frozen = continuation ? context : freezeContext(context, result.resolvedTimeRange);
      const signature = JSON.stringify([context.source, context.language, result.rows.schema]);
      if (state.columnSchema !== signature) {
        state.visibleColumns = null;
        state.columnSchema = signature;
        state.activeField = null;
      }
      state.expandedRows = new Set(
        [...state.expandedRows].filter(
          (index) =>
            preservePosition &&
            result.rows.rows[index] &&
            jsonText(state.rows[index]?.values) === jsonText(result.rows.rows[index].values),
        ),
      );
      Object.assign(state, {
        schema: result.rows.schema,
        rows: result.rows.rows,
        lastRequest: frozen,
        page: result.page.number,
        pageSize: result.page.size,
        hasNext: result.page.hasNext,
        dirty: false,
      });
      if (!continuation) {
        state.lastQueryIdentity = identity;
        state.chart = result.histogram || { status: 'error', reason: '服务端未返回时间分布。' };
        const item = { ...copyContext(context), at: Date.now() };
        state.history = [
          item,
          ...state.history.filter((entry) => {
            const { at, ...saved } = entry;
            return JSON.stringify(saved) !== identity;
          }),
        ].slice(0, 20);
        persist(historyKey, state.history);
      }
      state.warning = result.warnings
        .map((warning) =>
          typeof warning === 'string'
            ? warning
            : [warning.type, warning.reason || warning.message, warning.details]
                .filter(Boolean)
                .map(displayValue)
                .join(' · '),
        )
        .join('\n');
      state.elapsed = `${context.source} / ${context.language} · ${Math.round(performance.now() - started)} ms`;
      state.status = '查询服务已连接';
      state.connection = 'ok';
      if (!preservePosition) state.scrollVersion++;
    } catch (error) {
      if (id !== requestId || error.name === 'AbortError' || disposed) return;
      state.error = error.message;
      state.elapsed = state.lastRequest ? '查询失败 · 保留上次结果' : '查询失败';
      if (error.status === 0) {
        state.status = '连接失败';
        state.connection = 'error';
      }
    } finally {
      if (id === requestId) {
        controller = null;
        state.busy = false;
      }
    }
  }
  function cancelQuery() {
    invalidateRequest();
    state.elapsed = '已停止等待';
    toast('已停止接收结果；服务端查询可能仍在执行。');
  }
  function refresh() {
    return runSearch({ number: state.dirty ? 1 : state.page });
  }
  function toggleLogRow(index) {
    const expanded = new Set(state.expandedRows);
    if (expanded.has(index)) expanded.delete(index);
    else expanded.add(index);
    state.expandedRows = expanded;
  }
  function selectColumns(selection) {
    state.visibleColumns = selection;
    state.expandedRows = new Set();
  }
  function toggleVisibleColumn(index) {
    selectColumns(toggleColumn(state.schema, state.visibleColumns, index));
  }
  function addFilter(filter) {
    state.filters = [...state.filters, filter];
    conditionsChanged();
    runSearch();
  }
  function removeFilter(index) {
    state.filters = state.filters.filter((_, i) => i !== index);
    conditionsChanged();
    runSearch();
  }
  function resetFilters() {
    state.filters = [];
    conditionsChanged();
    runSearch();
  }
  function applyQuickFilter(field, value, exclude) {
    if (!canUseResultFields.value || state.busy) return;
    try {
      const filter = quickValueFilter(field, value, exclude);
      if (state.filters.some((item) => JSON.stringify(item) === JSON.stringify(filter))) {
        toast('该筛选条件已存在');
        return;
      }
      addFilter(filter);
    } catch (error) {
      toast(error.message);
    }
  }
  function selectBucket(bucket) {
    const range = state.lastRequest?.timeRange;
    if (!range) return;
    const from = new Date(Math.max(Date.parse(bucket.from), Date.parse(range.from))).toISOString();
    const to = new Date(Math.min(Date.parse(bucket.to), Date.parse(range.to))).toISOString();
    if (Date.parse(from) >= Date.parse(to)) return;
    applyTimeRange({ ...range, from, to });
    conditionsChanged();
    runSearch();
  }
  function loadHistory(index) {
    const item = state.history[index];
    if (!state.sources.some((source) => source.name === item.source)) {
      toast('此历史记录的数据源已不在服务端配置中。');
      return false;
    }
    setSource(item.source);
    setMode(item.language.toLowerCase());
    state.query = item.query;
    state.filters = copyContext(item.filters);
    applyTimeRange(item.timeRange);
    conditionsChanged();
    saveDraft();
    toast('历史查询及完整条件已载入，点击执行查询。');
    return true;
  }
  function deleteHistory(index) {
    state.history = state.history.filter((_, i) => i !== index);
    persist(historyKey, state.history);
  }
  function pageSizeChanged() {
    if (requestedPageSize() !== state.pageSize && state.lastRequest && !state.dirty)
      return runSearch({ continuation: true, size: requestedPageSize() });
  }
  async function initialize() {
    try {
      const config = await request('ui/config', { signal: configController.signal });
      if (disposed) return;
      if (!Array.isArray(config.sources) || !config.sources.length)
        throw new Error('服务端没有配置可用的数据源。');
      state.sources = config.sources;
      const maximum = Number(config.maxPageSize);
      state.maxPageSize = Number.isInteger(maximum) && maximum > 0 ? maximum : 1000;
      state.pageSize = Math.min(state.pageSize, state.maxPageSize);
      state.pageSizeChoice = String(state.pageSize);
      setSource(
        state.sources.some((source) => source.name === config.defaultSource)
          ? config.defaultSource
          : state.sources[0].name,
      );
      state.status = '服务已连接';
      state.connection = 'ok';
      if (state.query.trim()) await runSearch();
    } catch (error) {
      if (!disposed) {
        state.status = '配置加载失败';
        state.connection = 'error';
        state.error = error.message;
      }
    }
  }
  function dispose() {
    disposed = true;
    configController.abort();
    invalidateRequest();
  }
  return {
    state,
    sourceConfig,
    sourceFields,
    canUseResultFields,
    layout,
    columns,
    pageSummary,
    persist,
    editQuery,
    conditionsChanged,
    setMode,
    setSource,
    prepareTemplate,
    timeChanged,
    pickerChanged,
    runSearch,
    cancelQuery,
    refresh,
    toggleLogRow,
    selectColumns,
    toggleVisibleColumn,
    addFilter,
    removeFilter,
    resetFilters,
    applyQuickFilter,
    selectBucket,
    loadHistory,
    deleteHistory,
    pageSizeChanged,
    initialize,
    suspend: invalidateRequest,
    dispose,
  };
}
