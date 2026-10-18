import './styles.css';
import { icon } from './icons.js';
import { apiUrl, request, normalizeResponse, jsonText } from './api.js';
import { displayValue, timestamp, defaultTimeField, filterRows, histogram, queryTemplate } from './results.js';

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const escapeHtml = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
const historyKey = 'lens.query.history.v2';
const exportKey = 'lens.query.export.v1';
const state = {
  sources: [], source: '', mode: 'sql', view: 'table', table: '',
  schema: [], rows: [], results: [], filters: [], minutes: 0, timeField: -1,
  selection: null, chart: null, chartVisible: true, page: 1, pageSize: 8,
  history: readHistory(), drafts: new Map(), lastRequest: null, detail: null,
  requestId: 0, controller: null, exportTask: null, exportTimer: null, exportSubmitting: false,
};
let toastTimer;

function fillIcons(root = document) {
  $$('[data-icon]', root).forEach(element => { element.innerHTML = icon(element.dataset.icon); });
}
function toast(message) {
  clearTimeout(toastTimer);
  $('#toast').textContent = message;
  $('#toast').classList.add('visible');
  toastTimer = setTimeout(() => $('#toast').classList.remove('visible'), 3500);
}
function readHistory() {
  try {
    const data = JSON.parse(localStorage.getItem(historyKey));
    return Array.isArray(data) ? data.filter(item => item && typeof item.query === 'string' && typeof item.source === 'string' && typeof item.table === 'string' && ['sql', 'ppl'].includes(item.mode)).slice(0, 20) : [];
  } catch { return []; }
}
function persist(key, data) {
  try { localStorage.setItem(key, JSON.stringify(data)); } catch { toast('浏览器存储不可用，记录仅在当前页面保留。'); }
}
function setStatus(text, status = '') {
  $('#connection-status').textContent = text;
  $('#connection-status').dataset.status = status;
}
function queryError(message) {
  $('#query-error').textContent = message;
  $('#query-error').hidden = !message;
}
function setBusy(busy) {
  document.body.classList.toggle('is-searching', busy);
  $('#results-rows').setAttribute('aria-busy', busy);
  $('#search-button').disabled = busy || !state.source;
  $('#search-button').innerHTML = icon('play') + `<span>${busy ? '查询中' : '执行查询'}</span>`;
  $('#cancel-query').hidden = !busy;
}
function invalidateRequest() {
  state.requestId++;
  state.controller?.abort();
  state.controller = null;
  setBusy(false);
}
function clearResults() {
  state.schema = []; state.rows = []; state.results = []; state.filters = [];
  state.lastRequest = null; state.selection = null; state.timeField = -1; state.minutes = 0; state.page = 1;
  $('#time-select').value = '0';
  $('#time-field').innerHTML = '<option value="-1">无时间字段</option>';
  $('#query-warning').hidden = true;
  $('#elapsed').textContent = '等待查询';
  render();
}
function draftKey() { return `${state.source}:${state.mode}`; }
function saveDraft() { state.drafts.set(draftKey(), $('#query-input').value); }
function updateModeControls() {
  $$('[data-mode]').forEach(button => {
    const selected = button.dataset.mode === state.mode;
    button.classList.toggle('selected', selected); button.setAttribute('aria-selected', selected);
  });
  $('#query-input').setAttribute('aria-label', `${state.mode.toUpperCase()} 查询`);
}
function setMode(mode) {
  if (mode === state.mode) return;
  saveDraft(); invalidateRequest(); state.mode = mode;
  $('#query-input').value = state.drafts.get(draftKey()) ?? (state.table ? queryTemplate(mode, state.table) : '');
  updateModeControls(); clearResults(); queryError('');
}
function setSource(source) {
  const config = state.sources.find(item => item.name === source);
  if (!config) return;
  saveDraft(); invalidateRequest(); state.source = source; state.table = config.table || '';
  $('#dataset-select').value = source; $('#table-input').value = state.table;
  $$('[data-source]').forEach(button => button.classList.toggle('selected', button.dataset.source === source));
  $('#query-input').value = state.drafts.get(draftKey()) ?? (state.table ? queryTemplate(state.mode, state.table) : '');
  clearResults(); queryError(''); setBusy(false);
}
function prepareTemplate(preset) {
  try {
    const field = state.timeField >= 0 ? state.schema[state.timeField].name : 'timestamp';
    const table = $('#table-input').value.trim();
    const mode = preset === 'distribution' ? 'ppl' : state.mode;
    const query = queryTemplate(mode, table, preset, field);
    if (mode !== state.mode) setMode(mode);
    state.table = table;
    $('#query-input').value = query; saveDraft(); queryError('');
    $('#query-input').focus();
    toast('已填入查询，点击「执行查询」运行。');
  } catch (error) { queryError(error.message); }
}
async function runSearch() {
  const query = $('#query-input').value.trim();
  if (!query) { queryError('请输入 SQL 或 PPL 查询。'); return; }
  if (!state.source) { queryError('尚未加载数据源，请重新加载页面。'); return; }
  invalidateRequest(); clearResults(); queryError(''); saveDraft();
  const id = state.requestId;
  const controller = new AbortController(); state.controller = controller;
  const body = { source: state.source, language: state.mode.toUpperCase(), query };
  const historyItem = { source: state.source, mode: state.mode, table: $('#table-input').value.trim(), query, at: Date.now() };
  const started = performance.now(); setBusy(true); $('#elapsed').textContent = '正在执行查询…';
  try {
    const result = normalizeResponse(await request('query', { body, signal: controller.signal }));
    if (id !== state.requestId) return;
    state.schema = result.schema; state.rows = result.rows; state.lastRequest = Object.freeze(body);
    state.timeField = defaultTimeField(state.schema);
    $('#time-field').innerHTML = '<option value="-1">不使用时间字段</option>' + state.schema.map((column, index) => `<option value="${index}">${escapeHtml(column.name)}</option>`).join('');
    $('#time-field').value = String(state.timeField);
    $('#query-warning').textContent = result.warnings.map(warning => [warning.type, warning.reason || warning.message, warning.details].filter(Boolean).map(displayValue).join(' · ')).join('\n');
    $('#query-warning').hidden = !result.warnings.length;
    $('#elapsed').textContent = `${body.source} / ${body.language} · ${Math.round(performance.now() - started)} ms`;
    state.history = [historyItem, ...state.history.filter(item => item.query !== query || item.source !== body.source || item.mode !== historyItem.mode)].slice(0, 20);
    persist(historyKey, state.history);
    setStatus('查询服务已连接', 'ok'); applyFilters();
  } catch (error) {
    if (id !== state.requestId || error.name === 'AbortError') return;
    queryError(error.message); $('#elapsed').textContent = '查询失败';
    if (error.status === 0) setStatus('连接失败', 'error');
  } finally {
    if (id === state.requestId) { state.controller = null; setBusy(false); }
  }
}
function applyFilters() {
  state.results = filterRows(state.rows, state.filters, state.timeField, state.minutes, state.selection);
  state.page = 1; render();
}
function render() { renderFilters(); renderChart(); renderRows(); renderFields(); }
function renderFilters() {
  $('#filter-chips').innerHTML = state.filters.map((filter, index) => `<span class="filter-chip"><span>${escapeHtml(state.schema[filter.column]?.name)}</span><span>${escapeHtml(filter.op)}</span><b>${escapeHtml(filter.value)}</b><button data-remove-filter="${index}" aria-label="移除筛选 ${index + 1}">${icon('x')}</button></span>`).join('') + (state.selection ? `<span class="filter-chip">${escapeHtml(formatTime(state.selection.start))} – ${escapeHtml(formatTime(state.selection.end))}<button data-action="clear-selection" aria-label="清除图表筛选">${icon('x')}</button></span>` : '');
  $('[data-action="filter"]').disabled = !state.schema.length;
  $('#time-field').disabled = !state.schema.length;
  $('#time-select').disabled = state.timeField < 0;
  $('[data-action="export"]').disabled = !state.lastRequest;
}
function formatTime(value) { return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }); }
function renderChart() {
  $('#result-count').textContent = state.results.length.toLocaleString();
  $('#tab-count').textContent = state.results.length.toLocaleString();
  const levelField = state.schema.findIndex(column => ['level', 'severity', 'log.level'].includes(column.name.toLowerCase()));
  for (const level of ['INFO', 'WARN', 'ERROR']) {
    const button = $(`[data-level="${level}"]`);
    button.hidden = levelField < 0;
    $(`#${level.toLowerCase()}-count`).textContent = state.results.filter(row => displayValue(row.values[levelField]).toUpperCase() === level).length;
  }
  state.chart = state.timeField >= 0 ? histogram(state.results, state.timeField, levelField) : null;
  $('#chart-area').hidden = !state.chart || !state.chartVisible;
  $('#chart-empty').hidden = !!state.chart || !state.chartVisible;
  $('#chart-empty').textContent = !state.lastRequest ? '执行查询后显示时间分布。' : state.timeField < 0 ? '结果中没有时间字段；可用「时间分布」快捷查询获取聚合结果。' : '本次结果没有可解析的时间值。';
  if (!state.chart) return;
  const totals = state.chart.buckets.map(bucket => bucket.INFO + bucket.WARN + bucket.ERROR + bucket.OTHER);
  const ceiling = Math.max(1, ...totals);
  $('#chart-max').textContent = ceiling; $('#chart-mid').textContent = Math.floor(ceiling / 2);
  $('#chart-granularity').textContent = `仅已返回行 · ${state.schema[state.timeField].name} · 本地时间${state.chart.omitted ? ` · ${state.chart.omitted} 行无时间` : ''}`;
  $('#histogram').innerHTML = state.chart.buckets.map((bucket, index) => `<button class="chart-bar" data-bucket="${index}" title="${escapeHtml(formatTime(bucket.start))} · ${totals[index]} 行" aria-label="筛选 ${escapeHtml(formatTime(bucket.start))} 的 ${totals[index]} 行">${['INFO', 'WARN', 'ERROR', 'OTHER'].map(level => `<i class="${level.toLowerCase()}" style="height:${bucket[level] / ceiling * 100}%"></i>`).join('')}</button>`).join('');
  $('#histogram').setAttribute('role', 'group');
  $('#chart-axis').innerHTML = Array.from({ length: 5 }, (_, index) => `<span>${formatTime(state.chart.start + (state.chart.end - state.chart.start) * index / 4)}</span>`).join('');
}
function renderRows() {
  const maxPage = Math.max(1, Math.ceil(state.results.length / state.pageSize));
  state.page = Math.max(1, Math.min(state.page, maxPage));
  const rows = state.results.slice((state.page - 1) * state.pageSize, state.page * state.pageSize);
  const empty = !rows.length;
  $('#empty-state').hidden = !empty;
  $('#empty-state h3').textContent = state.lastRequest ? '暂时没有匹配的数据' : '准备探索数据';
  $('#table-view').hidden = empty || state.view !== 'table';
  $('#json-view').hidden = state.view !== 'json';
  if (state.view === 'json') $('#empty-state').hidden = true;
  $('thead').innerHTML = `<tr><th class="expand-col"><span class="sr-only">详情</span></th>${state.schema.map(column => `<th title="${escapeHtml(column.type)}">${escapeHtml(column.name)}<small>${escapeHtml(column.type)}</small></th>`).join('')}</tr>`;
  $('#results-rows').innerHTML = rows.map(row => `<tr class="result-row" tabindex="0" data-row-id="${row.index}" aria-label="查看第 ${row.index + 1} 条记录"><td>${icon('chevron-right')}</td>${row.values.map((value, index) => {
    const text = displayValue(value);
    const isLevel = ['level', 'severity', 'log.level'].includes(state.schema[index].name.toLowerCase()) && ['INFO', 'WARN', 'ERROR'].includes(text);
    return `<td title="${escapeHtml(text)}">${isLevel ? `<span class="level-badge ${text}">${text}</span>` : `<span class="cell-value${value === null ? ' null-value' : ''}">${escapeHtml(text)}</span>`}</td>`;
  }).join('')}</tr>`).join('');
  // Keep schema + row arrays so duplicate aliases and exact numeric values are not lost.
  $('#json-content').textContent = jsonText({ schema: state.schema, datarows: rows.map(row => row.values) }, null, 2);
  const first = empty ? 0 : (state.page - 1) * state.pageSize + 1;
  $('#page-summary').textContent = `${first}–${Math.min(state.page * state.pageSize, state.results.length)} / ${state.results.length} 行 · 返回 ${state.rows.length} 行`;
  $('#page-summary').title = '本地分页，仅统计本次响应；不是源表的总命中数。';
  $('#page-number').textContent = state.page;
  $('#prev-page').disabled = state.page <= 1; $('#next-page').disabled = state.page >= maxPage;
  requestAnimationFrame(fitPageSize);
}
function fitPageSize() {
  const row = $('#results-rows tr'); const viewport = $('#table-view');
  if ($('#page-size').value !== 'auto' || viewport.hidden || !row) return;
  const height = row.getBoundingClientRect().height;
  if (!height) return;
  const size = Math.max(1, Math.floor((viewport.clientHeight - $('thead').getBoundingClientRect().height) / height));
  $('#page-size option[value="auto"]').textContent = `自适应 · ${size} 条`;
  if (size === state.pageSize) return;
  const first = (state.page - 1) * state.pageSize;
  state.pageSize = size; state.page = Math.floor(first / size) + 1; renderRows();
}
function renderFields() {
  $('#field-count').textContent = state.schema.length;
  const term = $('#field-search').value.toLowerCase();
  $('#field-list').innerHTML = state.schema.map((column, index) => ({ column, index })).filter(({ column }) => column.name.toLowerCase().includes(term)).map(({ column, index }) => `<button class="field-item" data-field="${index}" title="${escapeHtml(column.type)}"><span class="field-type">${/int|long|double|float|decimal|bigint/.test(column.type) ? '#' : /time|date/.test(column.type) ? '◷' : 'Aa'}</span><span class="field-name">${escapeHtml(column.name)}</span><span>${icon('plus')}</span></button>`).join('') || '<p class="modal-note">暂无字段</p>';
}
function openModal(title, content) {
  $('#modal-title').textContent = title; $('#modal-content').innerHTML = content; fillIcons($('#modal'));
  if (!$('#modal').open) $('#modal').showModal();
}
function openFilter(column = 0) {
  if (!state.schema.length) return;
  openModal('筛选本次结果', `<form id="filter-form" class="modal-form"><p class="modal-note">只筛选本次已返回的数据。查询全部数据请在 SQL / PPL 中填写条件。</p><label>字段<select id="filter-field">${state.schema.map((field, index) => `<option value="${index}" ${column === index ? 'selected' : ''}>${escapeHtml(field.name)}</option>`).join('')}</select></label><label>条件<select id="filter-op"><option value="=">等于</option><option value="!=">不等于</option><option value="contains">包含（不区分大小写）</option><option value="null">为 null</option><option value="not-null">不为 null</option></select></label><label>值<input id="filter-value" autocomplete="off" placeholder="按字段显示值比较" /></label><div class="modal-actions"><button type="button" class="button secondary" data-action="close-modal">取消</button><button class="button primary">应用筛选</button></div></form>`);
  $('#filter-op').onchange = () => { $('#filter-value').disabled = ['null', 'not-null'].includes($('#filter-op').value); };
  $('#filter-form').onsubmit = event => { event.preventDefault(); state.filters.push({ column: Number($('#filter-field').value), op: $('#filter-op').value, value: $('#filter-value').value }); state.selection = null; applyFilters(); $('#modal').close(); };
}
function openField(column) {
  const counts = new Map();
  state.results.forEach(row => { const value = row.values[column]; const key = (value === null ? 'null:' : 'value:') + displayValue(value); const entry = counts.get(key) || { value, count: 0 }; entry.count++; counts.set(key, entry); });
  const values = [...counts.values()].sort((a, b) => b.count - a.count).slice(0, 8);
  openModal(state.schema[column].name, `<p class="modal-note">类型 ${escapeHtml(state.schema[column].type)} · 本次结果中的常见值</p>${values.map((entry, index) => `<button class="field-value" data-value-index="${index}"><span>${escapeHtml(displayValue(entry.value))}</span><span>${entry.count} 行 ${icon('plus')}</span><i class="field-value-bar" style="width:${entry.count / state.results.length * 100}%"></i></button>`).join('') || '<p class="modal-note">当前没有字段值。</p>'}<div class="modal-actions"><button class="button secondary" id="custom-filter">自定义筛选</button></div>`);
  $$('[data-value-index]').forEach(button => { button.onclick = () => { const entry = values[Number(button.dataset.valueIndex)]; state.filters.push({ column, op: entry.value === null ? 'null' : '=', value: displayValue(entry.value) }); state.selection = null; applyFilters(); $('#modal').close(); }; });
  $('#custom-filter').onclick = () => openFilter(column);
}
function detail(index) {
  const row = state.rows[index]; if (!row) return;
  state.detail = row;
  $('#detail-content').innerHTML = `<div class="detail-index">${icon('database')}${escapeHtml(state.lastRequest.source)} · 第 ${index + 1} 条</div>${state.schema.map((column, i) => `<div class="detail-field"><span>${escapeHtml(column.name)}<small>${escapeHtml(column.type)}</small></span><code>${escapeHtml(displayValue(row.values[i]))}</code></div>`).join('')}<div class="detail-actions"><button class="button secondary" data-action="copy-record">${icon('copy')}复制 JSON</button></div>`;
  $('#detail-drawer').showModal();
}
function setHistoryOpen(open) {
  if (open) renderHistory();
  $('#query-history-panel').hidden = !open;
  $('[data-action="history"]').setAttribute('aria-expanded', open);
}
function renderHistory() {
  $('#query-history-list').innerHTML = state.history.map((item, index) => `<div class="history-item"><button type="button" class="history-query" data-load-history="${index}" aria-label="载入 ${escapeHtml(item.query)}"><span class="history-meta"><b>${item.mode.toUpperCase()}</b><span>${escapeHtml(item.source)}</span><span>${item.at ? escapeHtml(formatTime(item.at)) : ''}</span></span><code>${escapeHtml(item.query)}</code></button><button type="button" class="icon-button" data-delete-history="${index}" aria-label="删除历史 ${index + 1}">${icon('x')}</button></div>`).join('') || '<div class="history-empty">还没有查询历史<span>查询成功后保存在此浏览器中。</span></div>';
}
function loadHistory(index) {
  const item = state.history[index];
  if (!state.sources.some(source => source.name === item.source)) { toast('此历史记录的数据源已不在服务端配置中。'); return; }
  setSource(item.source); setMode(item.mode); state.table = item.table;
  $('#table-input').value = item.table; $('#query-input').value = item.query;
  setHistoryOpen(false); saveDraft(); $('#query-input').focus(); toast('历史查询已载入，点击执行查询。');
}
function showHelp() {
  openModal('查询工作台', `<p class="modal-note">SQL / PPL 原文提交给统一查询引擎。左侧选择配置的数据源，表名用于生成快捷查询，手写语句中的表名以语句为准。</p><p class="modal-note">字段、图表、时间选择、分页和筛选只作用于本次响应。普通查询沿用服务端返回上限；表总量请使用「记录总数」。没有时间字段时不显示图表。无时区的时间值按浏览器本地时区显示。</p><p class="modal-note">导出会重新执行上一次成功的查询，不包含本地筛选。异步导出支持大结果；查询中的 LIMIT / head 仍然有效。暂停刷新不会取消服务端导出。</p><div class="help-row"><span>聚焦编辑器</span><kbd>⌘ / Ctrl K</kbd></div><div class="help-row"><span>执行查询</span><kbd>⌘ / Ctrl Enter</kbd></div><p class="modal-note">表列表、全量趋势、服务端分页和查询取消尚无对应接口。时间分布快捷模板使用 PPL span；具体语法能力取决于当前引擎和后端。</p>`);
}

function exportDialog() {
  if (!state.lastRequest) return;
  const body = state.lastRequest;
  openModal('导出查询', `<p class="modal-note">重新执行下面这条成功查询。包含 LIMIT / head，不包含当前本地筛选；数据变化可能使导出结果与当前表格不同。</p><pre class="export-query">${escapeHtml(body.source)} / ${body.language}\n${escapeHtml(body.query)}</pre><div class="modal-actions"><button class="button secondary" id="sync-export">直接下载 CSV</button><button class="button primary" id="async-export">创建异步导出</button></div><p class="modal-note" id="export-error" role="alert"></p><div id="export-task"></div>`);
  $('#sync-export').onclick = async event => {
    event.target.disabled = true; $('#export-error').textContent = '正在生成 CSV…';
    try {
      const blob = await request('query/export', { body, blob: true });
      const url = URL.createObjectURL(blob); const anchor = document.createElement('a');
      anchor.href = url; anchor.download = 'result.csv'; anchor.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      if ($('#export-error')) $('#export-error').textContent = 'CSV 下载已开始。';
    } catch (error) { if ($('#export-error')) $('#export-error').textContent = error.message; }
    finally { if ($('#sync-export')) $('#sync-export').disabled = false; }
  };
  $('#async-export').onclick = async event => {
    if (state.exportSubmitting || activeExport()) return;
    state.exportSubmitting = true; event.target.disabled = true;
    try {
      const task = await request('query/export/tasks', { body });
      if (typeof task.id !== 'string') throw new Error('导出响应缺少任务 ID。');
      state.exportTask = { id: task.id, state: 'PENDING', query: body.query, source: body.source };
      persist(exportKey, state.exportTask); renderExportTask(); pollExport();
    } catch (error) { if ($('#export-error')) $('#export-error').textContent = error.message; }
    finally { state.exportSubmitting = false; if ($('#async-export')) $('#async-export').disabled = activeExport(); }
  };
  renderExportTask();
}
function activeExport() { return state.exportTask && ['PENDING', 'RUNNING'].includes(state.exportTask.state); }
function renderExportTask() {
  const task = state.exportTask;
  if ($('#async-export')) $('#async-export').disabled = state.exportSubmitting || activeExport();
  $('#export-status-button').hidden = !task;
  if (!task) return;
  $('#export-status-button').textContent = `导出 · ${{ PENDING: '排队', RUNNING: '运行中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' }[task.state] || task.state}`;
  if (!$('#export-task')) return;
  const labels = { PENDING: '等待执行', RUNNING: '正在导出', SUCCEEDED: '导出完成', FAILED: '导出失败', CANCELLED: '已取消' };
  $('#export-task').innerHTML = `<div class="export-progress"><strong>${labels[task.state] || escapeHtml(task.state)}</strong><span>${escapeHtml(task.exportedRows ?? 0)} 行 · ${(Number(task.fileSizeBytes || 0) / 1048576).toFixed(2)} MiB</span><p class="modal-note">${escapeHtml(task.source || '')} · ${escapeHtml(task.query || '')}</p>${task.error ? `<p class="query-error">${escapeHtml(task.error)}</p>` : ''}${task.pollError ? `<p class="query-error">${escapeHtml(task.pollError)}</p>` : ''}${task.state === 'SUCCEEDED' ? `<a class="button primary" href="${escapeHtml(apiUrl(`query/export/tasks/${encodeURIComponent(task.id)}/file`))}" download="result.csv">下载 CSV</a>` : ''}${activeExport() ? '<button class="button secondary" id="resume-export">刷新进度</button><button class="button secondary" id="pause-export">暂停刷新</button>' : ''}</div>`;
  if ($('#resume-export')) $('#resume-export').onclick = pollExport;
  if ($('#pause-export')) $('#pause-export').onclick = () => { clearTimeout(state.exportTimer); state.exportPollVersion++; toast('已暂停刷新，服务端导出继续执行。'); };
}
async function pollExport() {
  clearTimeout(state.exportTimer);
  const task = state.exportTask; if (!task) return;
  const version = (state.exportPollVersion || 0) + 1; state.exportPollVersion = version;
  try {
    const status = await request(`query/export/tasks/${encodeURIComponent(task.id)}`);
    if (state.exportTask?.id !== task.id || version !== state.exportPollVersion) return;
    state.exportTask = { ...task, ...status, exportedRows: Number(status.exportedRows), fileSizeBytes: Number(status.fileSizeBytes), pollError: null };
    persist(exportKey, state.exportTask); renderExportTask();
    if (activeExport()) state.exportTimer = setTimeout(pollExport, 1500);
    else toast(`导出${status.state === 'SUCCEEDED' ? '完成，可下载 CSV' : '已结束，请查看状态'}`);
  } catch (error) {
    if (version !== state.exportPollVersion) return;
    state.exportTask.pollError = error.message;
    if ([403, 404].includes(error.status)) { state.exportTask.state = 'FAILED'; state.exportTask.error = '任务已过期、不可访问或服务已重启，请重新创建。'; persist(exportKey, state.exportTask); }
    renderExportTask();
  }
}
function showExportStatus() { openModal('导出任务', '<p class="modal-note">任务由当前示例服务保留；刷新页面可继续查看。暂停刷新不会取消服务端任务。</p><div id="export-task"></div>'); renderExportTask(); pollExport(); }

fillIcons();
$('#search-button').insertAdjacentHTML('afterend', '<button type="button" class="button secondary" id="cancel-query" hidden>停止等待</button>');
$('.result-tools').insertAdjacentHTML('afterbegin', '<button class="text-button" id="export-status-button" hidden>导出状态</button>');
$('#search-form').onsubmit = event => { event.preventDefault(); runSearch(); };
$('#cancel-query').onclick = () => { invalidateRequest(); $('#elapsed').textContent = '已停止等待'; toast('已停止接收结果；服务端查询可能仍在执行。'); };
$('#dataset-select').onchange = event => setSource(event.target.value);
$('#table-input').onchange = event => { state.table = event.target.value.trim(); const source = state.sources.find(item => item.name === state.source); if (source) source.table = state.table; toast('表名已更新，点击快捷查询生成语句。'); };
$('#time-select').onchange = event => { state.minutes = Number(event.target.value); state.selection = null; applyFilters(); };
$('#time-field').onchange = event => { state.timeField = Number(event.target.value); state.selection = null; if (state.timeField < 0) { state.minutes = 0; $('#time-select').value = '0'; } applyFilters(); };
$('#field-search').oninput = renderFields;
$('#page-size').onchange = event => { state.page = 1; if (event.target.value === 'auto') fitPageSize(); else state.pageSize = Number(event.target.value); renderRows(); };
$('#prev-page').onclick = () => { state.page--; renderRows(); $('#table-view').scrollTop = 0; };
$('#next-page').onclick = () => { state.page++; renderRows(); $('#table-view').scrollTop = 0; };
$('#export-status-button').onclick = showExportStatus;
document.addEventListener('keydown', event => {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); $$('dialog[open]').forEach(dialog => dialog.close()); $('#query-input').focus(); $('#query-input').select(); }
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter' && event.target === $('#query-input')) { event.preventDefault(); runSearch(); }
  if ((event.key === 'Enter' || event.key === ' ') && event.target.matches('[data-row-id]')) { event.preventDefault(); detail(Number(event.target.dataset.rowId)); }
  if (event.key === 'Escape') { setHistoryOpen(false); $('.sidebar').classList.remove('open'); }
});
document.addEventListener('click', async event => {
  if (!event.target.closest('.sidebar') && !event.target.closest('[data-action="menu"]')) $('.sidebar').classList.remove('open');
  if (!event.target.closest('#query-history-panel') && !event.target.closest('[data-action="history"]')) setHistoryOpen(false);
  const row = event.target.closest('[data-row-id]'); if (row) { detail(Number(row.dataset.rowId)); return; }
  const button = event.target.closest('button'); if (!button || button.disabled) return;
  const data = button.dataset;
  if (data.mode) { setMode(data.mode); return; }
  if (data.source) { setSource(data.source); $('.sidebar').classList.remove('open'); return; }
  if (data.preset) { prepareTemplate(data.preset); return; }
  if (data.loadHistory !== undefined) { loadHistory(Number(data.loadHistory)); return; }
  if (data.deleteHistory !== undefined) { state.history.splice(Number(data.deleteHistory), 1); persist(historyKey, state.history); renderHistory(); return; }
  if (data.removeFilter !== undefined) { state.filters.splice(Number(data.removeFilter), 1); applyFilters(); return; }
  if (data.field !== undefined) { openField(Number(data.field)); return; }
  if (data.level) { const column = state.schema.findIndex(field => ['level', 'severity', 'log.level'].includes(field.name.toLowerCase())); state.filters.push({ column, op: '=', value: data.level }); state.selection = null; applyFilters(); return; }
  if (data.bucket !== undefined) { const bucket = state.chart.buckets[Number(data.bucket)]; state.selection = { start: bucket.start, end: bucket.end }; applyFilters(); return; }
  if (data.view) { state.view = data.view; $$('[data-view]').forEach(item => { item.classList.toggle('selected', item.dataset.view === state.view); item.setAttribute('aria-selected', item.dataset.view === state.view); }); renderRows(); return; }
  switch (data.action) {
    case 'home': $('.sidebar').classList.remove('open'); $('#query-input').focus(); break;
    case 'menu': $('.sidebar').classList.toggle('open'); break;
    case 'history': setHistoryOpen($('#query-history-panel').hidden); break;
    case 'close-history': setHistoryOpen(false); break;
    case 'help': showHelp(); break;
    case 'filter': openFilter(); break;
    case 'clear-query': $('#query-input').value = ''; $('#query-input').focus(); break;
    case 'close-modal': $('#modal').close(); break;
    case 'close-detail': $('#detail-drawer').close(); break;
    case 'clear-selection': state.selection = null; applyFilters(); break;
    case 'chart': state.chartVisible = !state.chartVisible; button.innerHTML = icon(state.chartVisible ? 'chevron-up' : 'chevron-down'); button.setAttribute('aria-expanded', state.chartVisible); button.setAttribute('aria-label', state.chartVisible ? '收起趋势图' : '展开趋势图'); renderChart(); break;
    case 'fields': { const panel = $('#fields-panel'); const visible = getComputedStyle(panel).display !== 'none'; panel.style.display = visible ? 'none' : 'block'; button.setAttribute('aria-expanded', !visible); break; }
    case 'export': exportDialog(); break;
    case 'refresh': runSearch(); break;
    case 'reset': state.filters = []; state.selection = null; state.minutes = 0; $('#time-select').value = '0'; applyFilters(); break;
    case 'copy-record': try { await navigator.clipboard.writeText(jsonText({ schema: state.schema, datarows: [state.detail.values] }, null, 2)); toast('记录 JSON 已复制'); } catch { toast('无法访问剪贴板，请从 JSON 视图复制。'); } break;
  }
});
for (const dialog of $$('dialog')) dialog.addEventListener('click', event => { if (event.target === dialog) { const rect = dialog.getBoundingClientRect(); if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) dialog.close(); } });
new ResizeObserver(fitPageSize).observe($('#table-view'));
window.addEventListener('pagehide', () => { state.controller?.abort(); clearTimeout(state.exportTimer); state.exportPollVersion++; });

async function initialize() {
  render();
  try {
    const config = await request('ui/config');
    if (!Array.isArray(config.sources) || !config.sources.length) throw new Error('服务端没有配置可用的数据源。');
    state.sources = config.sources;
    $('#source-count').textContent = state.sources.length;
    $('#source-list').innerHTML = state.sources.map(source => `<button class="source-nav" data-source="${escapeHtml(source.name)}"><span class="source-logo ${source.type === 'clickhouse' ? 'clickhouse' : 'elastic'}">${icon(source.type === 'clickhouse' ? 'columns' : 'database')}</span><div><strong>${escapeHtml(source.type)}</strong><small>${escapeHtml(source.name)}</small></div></button>`).join('');
    $('#dataset-select').innerHTML = state.sources.map(source => `<option value="${escapeHtml(source.name)}">${escapeHtml(source.name)}</option>`).join('');
    $('#dataset-select').disabled = false;
    setSource(state.sources.some(source => source.name === config.defaultSource) ? config.defaultSource : state.sources[0].name);
    setStatus('服务已连接', 'ok');
    try { const task = JSON.parse(localStorage.getItem(exportKey)); if (task && typeof task.id === 'string' && ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(task.state)) { state.exportTask = task; renderExportTask(); pollExport(); } } catch { /* Storage may be unavailable or contain an older format. */ }
    if ($('#query-input').value.trim()) runSearch();
  } catch (error) { setStatus('配置加载失败', 'error'); queryError(error.message); }
}
initialize();
