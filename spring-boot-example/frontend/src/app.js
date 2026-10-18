import './styles.css';
import { icon } from './icons.js';
import { apiUrl, request, normalizeResponse, jsonText } from './api.js';
import { displayValue, queryTemplate } from './results.js';
import { copyContext, freezeContext, searchBody, parseFilterValue, validateAbsoluteTimeRange } from './search-context.js';
import { attachPplAssist } from './ppl-assist.js';

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const escapeHtml = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
const historyKey = 'lens.search.history.v3';
const exportKey = 'lens.search.export.v2';
const state = {
  sources: [], source: '', mode: 'sql', view: 'table', schema: [], rows: [], filters: [],
  chart: null, chartVisible: true, page: 1, pageSize: 20, hasNext: false, dirty: true,
  history: readHistory(), drafts: new Map(), lastRequest: null, detail: null, maxPageSize: 1000,
  requestId: 0, controller: null, exportTask: null, exportTimer: null, exportSubmitting: false,
};
let toastTimer, fitTimer;
function fillIcons(root = document) { $$('[data-icon]', root).forEach(element => { element.innerHTML = icon(element.dataset.icon); }); }
function toast(message) { clearTimeout(toastTimer); $('#toast').textContent = message; $('#toast').classList.add('visible'); toastTimer = setTimeout(() => $('#toast').classList.remove('visible'), 3500); }
function readHistory() {
  try { const data = JSON.parse(localStorage.getItem(historyKey)); return Array.isArray(data) ? data.filter(item => item && typeof item.query === 'string' && typeof item.source === 'string' && ['SQL', 'PPL'].includes(item.language) && Array.isArray(item.filters)).slice(0, 20) : []; } catch { return []; }
}
function persist(key, data) { try { localStorage.setItem(key, JSON.stringify(data)); } catch { toast('浏览器存储不可用，记录仅在当前页面保留。'); } }
function setStatus(text, status = '') { $('#connection-status').textContent = text; $('#connection-status').dataset.status = status; }
function queryError(message) { $('#query-error').textContent = message; $('#query-error').hidden = !message; }
function setBusy(busy) {
  document.body.classList.toggle('is-searching', busy); $('#results-rows').setAttribute('aria-busy', busy);
  $('#search-button').disabled = busy || !state.source; $('#page-size').disabled = busy;
  $('#search-button').innerHTML = icon('play') + `<span>${busy ? '查询中' : '执行查询'}</span>`; $('#cancel-query').hidden = !busy;
  $('#prev-page').disabled = busy || state.dirty || state.page <= 1; $('#next-page').disabled = busy || state.dirty || !state.hasNext;
}
function invalidateRequest() { state.requestId++; state.controller?.abort(); state.controller = null; clearTimeout(fitTimer); setBusy(false); }
function clearResults() {
  state.schema = []; state.rows = []; state.lastRequest = null; state.chart = null; state.page = 1; state.hasNext = false; state.dirty = true;
  $('#query-warning').hidden = true; $('#elapsed').textContent = '等待查询'; render();
}
function conditionsChanged() { invalidateRequest(); state.page = 1; state.dirty = true; renderFilters(); renderRows(); }
function sourceConfig() { return state.sources.find(item => item.name === state.source) || {}; }
function sourceFields() { return Array.isArray(sourceConfig().sourceFields) ? sourceConfig().sourceFields.filter(field => field && typeof field.name === 'string' && typeof field.type === 'string') : []; }
function draftKey() { return `${state.source}:${state.mode}`; }
function saveDraft() { state.drafts.set(draftKey(), $('#query-input').value); }
function setMode(mode) {
  if (mode === state.mode) return;
  saveDraft(); invalidateRequest(); state.mode = mode;
  $('#query-input').value = state.drafts.get(draftKey()) ?? (sourceConfig().table ? queryTemplate(mode, sourceConfig().table) : '');
  $$('[data-mode]').forEach(button => { const selected = button.dataset.mode === mode; button.classList.toggle('selected', selected); button.setAttribute('aria-selected', selected); });
  $('#query-input').setAttribute('aria-label', `${mode.toUpperCase()} 查询`); pplAssist.contextChanged(); clearResults(); queryError('');
}
function setSource(source) {
  const config = state.sources.find(item => item.name === source); if (!config) return;
  saveDraft(); invalidateRequest(); state.source = source; state.filters = [];
  $('#dataset-select').value = source;
  $$('[data-source]').forEach(button => button.classList.toggle('selected', button.dataset.source === source));
  $('#query-input').value = state.drafts.get(draftKey()) ?? (config.table ? queryTemplate(state.mode, config.table) : '');
  $('#time-field').value = config.timeField || 'timestamp'; $('#time-zone').value = config.timeZone || 'Asia/Shanghai'; $('#time-select').value = 'all'; updateAbsoluteControls();
  pplAssist.contextChanged(); clearResults(); queryError(''); setBusy(false);
}
function prepareTemplate(preset) {
  try {
    const mode = preset === 'distribution' ? 'ppl' : state.mode;
    const query = queryTemplate(mode, sourceConfig().table || '', preset, $('#time-field').value.trim() || 'timestamp');
    if (mode !== state.mode) setMode(mode);
    $('#query-input').value = query; pplAssist.reset(); saveDraft(); conditionsChanged(); queryError(''); $('#query-input').focus(); toast('已填入查询，点击「执行查询」运行。');
  } catch (error) { queryError(error.message); }
}
function readContext() {
  const query = $('#query-input').value.trim(); if (!query) throw new Error('请输入 SQL 或 PPL 查询。');
  if (!state.source) throw new Error('尚未加载数据源，请重新加载页面。');
  const context = { source: state.source, language: state.mode.toUpperCase(), query, filters: copyContext(state.filters) };
  const range = $('#time-select').value;
  if (range !== 'all') {
    const field = $('#time-field').value.trim(), timeZone = $('#time-zone').value.trim();
    if (!field || !timeZone) throw new Error('请选择源时间字段并填写业务时区。');
    let from = range === 'custom' ? $('#time-from').value.trim() : `now-${range}`;
    let to = range === 'custom' ? $('#time-to').value.trim() : 'now';
    if (range === 'custom') {
      validateAbsoluteTimeRange(from, to);
    }
    context.timeRange = { field, from, to, timeZone };
  }
  return context;
}
function applyTimeRange(range) {
  if (!range) { $('#time-select').value = 'all'; updateAbsoluteControls(); return; }
  $('#time-field').value = range.field; $('#time-zone').value = range.timeZone;
  const relative = /^now-(15m|1h|1d|7d)$/.exec(range.from);
  $('#time-select').value = relative && range.to === 'now' ? relative[1] : 'custom';
  $('#time-from').value = range.from; $('#time-to').value = range.to; updateAbsoluteControls();
}
function updateAbsoluteControls() { $('#absolute-time-controls').hidden = $('#time-select').value !== 'custom'; }
async function runSearch({ number = 1, continuation = false } = {}) {
  pplAssist.reset();
  let context;
  try { context = continuation ? state.lastRequest : readContext(); if (!context) return; } catch (error) { queryError(error.message); return; }
  invalidateRequest();
  if (!continuation) clearResults();
  queryError(''); saveDraft(); const id = state.requestId;
  const controller = new AbortController(); state.controller = controller;
  const body = searchBody(context, number, state.pageSize, continuation ? ['rows'] : ['rows', 'histogram']);
  const started = performance.now(); setBusy(true); $('#elapsed').textContent = '正在执行查询…';
  try {
    const result = normalizeResponse(await request('search', { body, signal: controller.signal }));
    if (id !== state.requestId) return;
    if (!result.rows) throw new Error(result.errors?.rows?.message || result.errors?.rows || '明细查询失败，服务端未返回 rows。');
    const frozen = continuation ? context : freezeContext(context, result.resolvedTimeRange);
    state.schema = result.rows.schema; state.rows = result.rows.rows; state.lastRequest = frozen;
    state.page = result.page.number; state.pageSize = result.page.size; state.hasNext = result.page.hasNext; state.dirty = false;
    if (!continuation) state.chart = result.histogram || { status: 'error', reason: '服务端未返回时间分布。' };
    $('#query-warning').textContent = result.warnings.map(warning => typeof warning === 'string' ? warning : [warning.type, warning.reason || warning.message, warning.details].filter(Boolean).map(displayValue).join(' · ')).join('\n');
    $('#query-warning').hidden = !result.warnings.length;
    $('#elapsed').textContent = `${context.source} / ${context.language} · ${Math.round(performance.now() - started)} ms`;
    if (!continuation) {
      const item = { ...copyContext(context), at: Date.now() }; const identity = JSON.stringify(context);
      state.history = [item, ...state.history.filter(entry => { const { at, ...saved } = entry; return JSON.stringify(saved) !== identity; })].slice(0, 20); persist(historyKey, state.history);
    }
    setStatus('查询服务已连接', 'ok'); render(); $('#table-view').scrollTop = 0;
  } catch (error) {
    if (id !== state.requestId || error.name === 'AbortError') return;
    queryError(error.message); $('#elapsed').textContent = '查询失败';
    if (error.status === 0) setStatus('连接失败', 'error');
  } finally { if (id === state.requestId) { state.controller = null; setBusy(false); } }
}
function render() { renderFilters(); renderChart(); renderRows(); renderFields(); }
function renderFilters() {
  $('#filter-chips').innerHTML = state.filters.map((filter, index) => `<span class="filter-chip"><span>${escapeHtml(filter.field)}</span><span>${escapeHtml(filter.operator)}</span><b>${escapeHtml(displayValue(filter.value))}</b><button data-remove-filter="${index}" aria-label="移除筛选 ${index + 1}">${icon('x')}</button></span>`).join('');
  $('[data-action="filter"]').disabled = !state.source; $('[data-action="export"]').disabled = !state.lastRequest;
}
function formatTime(value, timeZone = state.chart?.timeZone || $('#time-zone').value || 'Asia/Shanghai') {
  try { return new Date(value).toLocaleString('zh-CN', { timeZone, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }); } catch { return String(value); }
}
function renderChart() {
  const chart = state.chart, supported = chart?.status === 'ok';
  $('#chart-area').hidden = !supported || !state.chartVisible; $('#chart-empty').hidden = supported || !state.chartVisible;
  $('#chart-empty').textContent = chart ? chart.reason || chart.message || (chart.status === 'unsupported' ? '当前查询不支持自动生成时间分布。' : '时间分布执行失败。') : '执行查询后显示时间分布；自动图表需要有限时间范围。';
  $('#chart-empty').classList.toggle('chart-error', chart?.status === 'error');
  if (!supported) return;
  const buckets = chart.buckets; const ceiling = Math.max(1, ...buckets.map(bucket => Number(bucket.count)));
  $('#chart-max').textContent = ceiling.toLocaleString(); $('#chart-mid').textContent = Math.floor(ceiling / 2).toLocaleString();
  $('#chart-granularity').textContent = `${chart.interval} · ${chart.timeZone} · 后台聚合`;
  $('#histogram').innerHTML = buckets.map((bucket, index) => `<button class="chart-bar" data-bucket="${index}" title="${escapeHtml(formatTime(bucket.from))} – ${escapeHtml(formatTime(bucket.to))} · ${escapeHtml(displayValue(bucket.count))} 条" aria-label="查询 ${escapeHtml(formatTime(bucket.from))} 至 ${escapeHtml(formatTime(bucket.to))}"><i class="info" style="height:${Number(bucket.count) / ceiling * 100}%"></i></button>`).join('');
  const labels = buckets.length ? [buckets[0].from, ...[.25, .5, .75].map(fraction => buckets[Math.min(buckets.length - 1, Math.floor(buckets.length * fraction))].from), buckets.at(-1).to] : [];
  $('#chart-axis').innerHTML = labels.map(value => `<span>${escapeHtml(formatTime(value))}</span>`).join('');
}
function renderRows() {
  const rows = state.rows, empty = !rows.length; $('#tab-count').textContent = rows.length.toLocaleString();
  $('#empty-state').hidden = !empty; $('#empty-state h3').textContent = state.lastRequest ? '暂时没有匹配的数据' : '准备探索数据';
  $('#table-view').hidden = empty || state.view !== 'table'; $('#json-view').hidden = state.view !== 'json'; if (state.view === 'json') $('#empty-state').hidden = true;
  $('thead').innerHTML = `<tr><th class="expand-col"><span class="sr-only">详情</span></th>${state.schema.map(column => `<th title="${escapeHtml(column.type)}">${escapeHtml(column.name)}<small>${escapeHtml(column.type)}</small></th>`).join('')}</tr>`;
  $('#results-rows').innerHTML = rows.map(row => `<tr class="result-row" tabindex="0" data-row-id="${row.index}" aria-label="查看第 ${(state.page - 1) * state.pageSize + row.index + 1} 条记录"><td>${icon('chevron-right')}</td>${row.values.map((value, index) => { const text = displayValue(value); const isLevel = ['level', 'severity', 'log.level'].includes(state.schema[index].name.toLowerCase()) && ['INFO', 'WARN', 'ERROR'].includes(text); return `<td title="${escapeHtml(text)}">${isLevel ? `<span class="level-badge ${text}">${text}</span>` : `<span class="cell-value${value === null ? ' null-value' : ''}">${escapeHtml(text)}</span>`}</td>`; }).join('')}</tr>`).join('');
  $('#json-content').textContent = jsonText({ schema: state.schema, datarows: rows.map(row => row.values) }, null, 2);
  const first = empty ? 0 : (state.page - 1) * state.pageSize + 1;
  $('#page-summary').textContent = state.dirty && state.lastRequest ? '条件已修改，请执行查询' : state.lastRequest ? `${first}–${empty ? 0 : first + rows.length - 1} 行${state.hasNext ? ' · 还有下一页' : ' · 已到末页'}` : '等待查询';
  $('#page-summary').title = '页码由后台执行，不提供总命中数。'; $('#page-number').textContent = state.page;
  $('#prev-page').disabled = !!state.controller || state.dirty || state.page <= 1; $('#next-page').disabled = !!state.controller || state.dirty || !state.hasNext;
  requestAnimationFrame(fitPageSize);
}
function fitPageSize() {
  const row = $('#results-rows tr'), viewport = $('#table-view');
  if ($('#page-size').value !== 'auto' || viewport.hidden || !row) return;
  const height = row.getBoundingClientRect().height; if (!height) return;
  const size = Math.max(1, Math.min(state.maxPageSize, Math.floor((viewport.clientHeight - $('thead').getBoundingClientRect().height) / height)));
  $('#page-size option[value="auto"]').textContent = `自适应 · ${size} 条`;
  clearTimeout(fitTimer);
  if (size === state.pageSize || state.dirty || !state.lastRequest || state.controller) return;
  fitTimer = setTimeout(() => { if ($('#page-size').value !== 'auto' || size === state.pageSize || state.dirty) return; state.pageSize = size; runSearch({ continuation: true }); }, 180);
}
function renderFields() {
  const term = $('#field-search').value.toLowerCase(), fields = sourceFields();
  $('#field-count').textContent = `${fields.length} / ${state.schema.length}`;
  const sources = fields.map((field, index) => ({ field, index })).filter(({ field }) => field.name.toLowerCase().includes(term));
  $('#field-list').innerHTML = '<p class="field-group-label">源字段 · 可筛选</p>' + (sources.map(({ field, index }) => `<button class="field-item" data-source-field="${index}" title="${escapeHtml(field.type)}"><span class="field-type">Aa</span><span class="field-name">${escapeHtml(field.name)}</span><span>${icon('plus')}</span></button>`).join('') || '<p class="modal-note">未配置源字段，可显式填写字段名。</p>') + '<p class="field-group-label">结果列 / 别名 · 仅展示</p>' + state.schema.filter(field => field.name.toLowerCase().includes(term)).map(field => `<div class="field-item result-field" title="${escapeHtml(field.type)}"><span class="field-type">=</span><span class="field-name">${escapeHtml(field.name)}</span></div>`).join('');
}
function openModal(title, content) { $('#modal-title').textContent = title; $('#modal-content').innerHTML = content; fillIcons($('#modal')); if (!$('#modal').open) $('#modal').showModal(); }
function openFilter(index = -1) {
  const fields = sourceFields(), selected = fields[index];
  openModal('源字段筛选', `<form id="filter-form" class="modal-form"><p class="modal-note">条件在源数据读取后、用户语句计算和聚合前按 AND 生效。请填写源表字段；结果别名不能作为源字段。</p><label>源字段<input id="filter-field" list="source-field-options" value="${escapeHtml(selected?.name || '')}" required autocomplete="off" /><datalist id="source-field-options">${fields.map(field => `<option value="${escapeHtml(field.name)}">${escapeHtml(field.type)}</option>`).join('')}</datalist></label><label>值类型<select id="filter-value-type"><option value="auto">使用源字段配置（未配置按字符串）</option><option value="string">字符串</option><option value="number">数字</option><option value="boolean">布尔值</option></select></label><label>条件<select id="filter-op"><option value="eq">等于</option><option value="ne">不等于</option><option value="gt">大于</option><option value="gte">大于等于</option><option value="lt">小于</option><option value="lte">小于等于</option><option value="in">属于集合（JSON 数组）</option><option value="contains">字符串包含</option><option value="is_null">为 null</option><option value="is_not_null">不为 null</option></select></label><label>值<input id="filter-value" autocomplete="off" placeholder="按源字段类型填写" /></label><p id="filter-error" class="modal-note" role="alert"></p><div class="modal-actions"><button type="button" class="button secondary" data-action="close-modal">取消</button><button class="button primary">应用筛选</button></div></form>`);
  $('#filter-op').onchange = () => { $('#filter-value').disabled = ['is_null', 'is_not_null'].includes($('#filter-op').value); };
  $('#filter-form').onsubmit = event => {
    event.preventDefault();
    try {
      const field = $('#filter-field').value.trim(), operator = $('#filter-op').value; if (!field) throw new Error('请填写源字段名。');
      const valueType = $('#filter-value-type').value; const type = valueType === 'auto' ? fields.find(item => item.name === field)?.type || 'string' : valueType; const value = parseFilterValue($('#filter-value').value, type, operator);
      state.filters.push({ field, operator, ...(value === undefined ? {} : { value }) }); $('#modal').close(); conditionsChanged(); runSearch();
    } catch (error) { $('#filter-error').textContent = error.message; }
  };
}
function detail(index) {
  const row = state.rows[index]; if (!row) return; state.detail = row;
  $('#detail-content').innerHTML = `<div class="detail-index">${icon('database')}${escapeHtml(state.lastRequest.source)} · 第 ${(state.page - 1) * state.pageSize + index + 1} 条</div>${state.schema.map((column, i) => `<div class="detail-field"><span>${escapeHtml(column.name)}<small>${escapeHtml(column.type)}</small></span><code>${escapeHtml(displayValue(row.values[i]))}</code></div>`).join('')}<div class="detail-actions"><button class="button secondary" data-action="copy-record">${icon('copy')}复制 JSON</button></div>`; $('#detail-drawer').showModal();
}
function setHistoryOpen(open) { if (open) { pplAssist.reset(); renderHistory(); } $('#query-history-panel').hidden = !open; $('[data-action="history"]').setAttribute('aria-expanded', open); }
function renderHistory() {
  $('#query-history-list').innerHTML = state.history.map((item, index) => `<div class="history-item"><button type="button" class="history-query" data-load-history="${index}" aria-label="载入 ${escapeHtml(item.query)}"><span class="history-meta"><b>${item.language}</b><span>${escapeHtml(item.source)}</span><span>${item.timeRange ? escapeHtml(`${item.timeRange.from} → ${item.timeRange.to}`) : '全部时间'} · ${item.filters.length} 个筛选</span></span><code>${escapeHtml(item.query)}</code></button><button type="button" class="icon-button" data-delete-history="${index}" aria-label="删除历史 ${index + 1}">${icon('x')}</button></div>`).join('') || '<div class="history-empty">还没有查询历史<span>查询成功后保存在此浏览器中。</span></div>';
}
function loadHistory(index) {
  const item = state.history[index]; if (!state.sources.some(source => source.name === item.source)) { toast('此历史记录的数据源已不在服务端配置中。'); return; }
  setSource(item.source); setMode(item.language.toLowerCase()); $('#query-input').value = item.query; state.filters = copyContext(item.filters); applyTimeRange(item.timeRange);
  pplAssist.reset(); conditionsChanged(); setHistoryOpen(false); saveDraft(); $('#query-input').focus(); toast('历史查询及完整条件已载入，点击执行查询。');
}
function showHelp() {
  openModal('查询工作台', '<p class="modal-note">SQL / PPL 原文与源字段筛选共同提交后台。表名以语句为准。时间范围采用左闭右开区间，查询时由后端解析相对时间；翻页和导出复用固定的绝对时间。</p><p class="modal-note">时间分布由后台在分页前聚合。全部时间、聚合、连接等查询可能无法生成辅助图表，明细仍可独立成功。点击时间桶缩小查询范围。</p><p class="modal-note">导出使用最后一次成功查询的语句、数据源、时间与筛选，保留手写 LIMIT / head。暂停刷新仅停止导出状态轮询。</p><div class="help-row"><span>聚焦编辑器</span><kbd>⌘ / Ctrl K</kbd></div><div class="help-row"><span>执行查询</span><kbd>⌘ / Ctrl Enter</kbd></div>');
  $('#modal-content').insertAdjacentHTML('beforeend', '<div class="help-row"><span>PPL 补全 · 方向键选择，Enter/Tab 插入，Esc 关闭</span><kbd>Ctrl Space</kbd></div>');
}
function exportDialog() {
  if (!state.lastRequest) return;
  const body = state.lastRequest;
  openModal('导出查询', `<p class="modal-note">重新执行下面这条成功查询。包含当前成功查询的时间与源字段筛选，保留 LIMIT / head；数据变化可能使导出结果与表格不同。</p><pre class="export-query">${escapeHtml(body.source)} / ${body.language}\n${escapeHtml(body.query)}\n${escapeHtml(body.timeRange ? `${body.timeRange.from} → ${body.timeRange.to} · ${body.timeRange.timeZone}` : '全部时间')}\n${escapeHtml(JSON.stringify(body.filters))}</pre><div class="modal-actions"><button class="button secondary" id="sync-export">直接下载 CSV</button><button class="button primary" id="async-export">创建异步导出</button></div><p class="modal-note" id="export-error" role="alert"></p><div id="export-task"></div>`);
  $('#sync-export').onclick = async event => {
    event.target.disabled = true; $('#export-error').textContent = '正在生成 CSV…';
    try {
      const blob = await request('search/export', { body, blob: true });
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
      const task = await request('search/export/tasks', { body });
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
  $('#export-task').innerHTML = `<div class="export-progress"><strong>${labels[task.state] || escapeHtml(task.state)}</strong><span>${escapeHtml(task.exportedRows ?? 0)} 行 · ${(Number(task.fileSizeBytes || 0) / 1048576).toFixed(2)} MiB</span><p class="modal-note">${escapeHtml(task.source || '')} · ${escapeHtml(task.query || '')}</p>${task.error ? `<p class="query-error">${escapeHtml(task.error)}</p>` : ''}${task.pollError ? `<p class="query-error">${escapeHtml(task.pollError)}</p>` : ''}${task.state === 'SUCCEEDED' ? `<a class="button primary" href="${escapeHtml(apiUrl(`search/export/tasks/${encodeURIComponent(task.id)}/file`))}" download="result.csv">下载 CSV</a>` : ''}${activeExport() ? '<button class="button secondary" id="resume-export">刷新进度</button><button class="button secondary" id="pause-export">暂停刷新</button>' : ''}</div>`;
  if ($('#resume-export')) $('#resume-export').onclick = pollExport;
  if ($('#pause-export')) $('#pause-export').onclick = () => { clearTimeout(state.exportTimer); state.exportPollVersion++; toast('已暂停刷新，服务端导出继续执行。'); };
}
async function pollExport() {
  clearTimeout(state.exportTimer);
  const task = state.exportTask; if (!task) return;
  const version = (state.exportPollVersion || 0) + 1; state.exportPollVersion = version;
  try {
    const status = await request(`search/export/tasks/${encodeURIComponent(task.id)}`);
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

const pplAssist = attachPplAssist({
  input: $('#query-input'), list: $('#ppl-completions'), status: $('#ppl-assist-status'),
  getContext: () => ({ mode: state.mode, source: state.source, config: sourceConfig() }),
  onChange: () => { saveDraft(); conditionsChanged(); },
});
fillIcons();
$('#search-button').insertAdjacentHTML('afterend', '<button type="button" class="button secondary" id="cancel-query" hidden>停止等待</button>');
$('.result-tools').insertAdjacentHTML('afterbegin', '<button class="text-button" id="export-status-button" hidden>导出状态</button>');
$('#search-form').onsubmit = event => { event.preventDefault(); runSearch(); };
$('#query-input').oninput = () => { saveDraft(); conditionsChanged(); };
$('#cancel-query').onclick = () => { invalidateRequest(); $('#elapsed').textContent = '已停止等待'; toast('已停止接收结果；服务端查询可能仍在执行。'); };
$('#dataset-select').onchange = event => setSource(event.target.value);
$('#time-select').onchange = () => { updateAbsoluteControls(); conditionsChanged(); if ($('#time-select').value !== 'custom' && $('#query-input').value.trim()) runSearch(); };
for (const id of ['time-field', 'time-zone', 'time-from', 'time-to']) $(`#${id}`).oninput = conditionsChanged;
$('#field-search').oninput = renderFields;
$('#page-size').onchange = event => { clearTimeout(fitTimer); if (event.target.value === 'auto') { fitPageSize(); return; } const size = Number(event.target.value); if (size === state.pageSize) return; state.pageSize = size; state.page = 1; if (state.lastRequest && !state.dirty) runSearch({ continuation: true }); else renderRows(); };
$('#prev-page').onclick = () => runSearch({ number: state.page - 1, continuation: true });
$('#next-page').onclick = () => runSearch({ number: state.page + 1, continuation: true });
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
  const button = event.target.closest('button'); if (!button || button.disabled) return; const data = button.dataset;
  if (data.mode) { setMode(data.mode); return; }
  if (data.source) { setSource(data.source); $('.sidebar').classList.remove('open'); return; }
  if (data.preset) { prepareTemplate(data.preset); return; }
  if (data.loadHistory !== undefined) { loadHistory(Number(data.loadHistory)); return; }
  if (data.deleteHistory !== undefined) { state.history.splice(Number(data.deleteHistory), 1); persist(historyKey, state.history); renderHistory(); return; }
  if (data.removeFilter !== undefined) { state.filters.splice(Number(data.removeFilter), 1); conditionsChanged(); runSearch(); return; }
  if (data.sourceField !== undefined) { openFilter(Number(data.sourceField)); return; }
  if (data.bucket !== undefined) {
    const bucket = state.chart.buckets[Number(data.bucket)], range = state.lastRequest?.timeRange; if (!range) return;
    const from = new Date(Math.max(Date.parse(bucket.from), Date.parse(range.from))).toISOString(), to = new Date(Math.min(Date.parse(bucket.to), Date.parse(range.to))).toISOString();
    if (Date.parse(from) >= Date.parse(to)) return;
    applyTimeRange({ ...range, from, to }); conditionsChanged(); runSearch(); return;
  }
  if (data.view) { state.view = data.view; $$('[data-view]').forEach(item => { item.classList.toggle('selected', item.dataset.view === state.view); item.setAttribute('aria-selected', item.dataset.view === state.view); }); renderRows(); return; }
  switch (data.action) {
    case 'home': $('.sidebar').classList.remove('open'); $('#query-input').focus(); break;
    case 'menu': $('.sidebar').classList.toggle('open'); break;
    case 'history': setHistoryOpen($('#query-history-panel').hidden); break;
    case 'close-history': setHistoryOpen(false); break;
    case 'help': showHelp(); break;
    case 'filter': openFilter(); break;
    case 'clear-query': $('#query-input').value = ''; pplAssist.reset(); saveDraft(); conditionsChanged(); $('#query-input').focus(); break;
    case 'close-modal': $('#modal').close(); break;
    case 'close-detail': $('#detail-drawer').close(); break;
    case 'chart': state.chartVisible = !state.chartVisible; button.innerHTML = icon(state.chartVisible ? 'chevron-up' : 'chevron-down'); button.setAttribute('aria-expanded', state.chartVisible); button.setAttribute('aria-label', state.chartVisible ? '收起趋势图' : '展开趋势图'); renderChart(); break;
    case 'fields': { const panel = $('#fields-panel'); const visible = getComputedStyle(panel).display !== 'none'; panel.style.display = visible ? 'none' : 'block'; button.setAttribute('aria-expanded', !visible); break; }
    case 'export': exportDialog(); break;
    case 'refresh': runSearch(); break;
    case 'reset': state.filters = []; conditionsChanged(); runSearch(); break;
    case 'copy-record': try { await navigator.clipboard.writeText(jsonText({ schema: state.schema, datarows: [state.detail.values] }, null, 2)); toast('记录 JSON 已复制'); } catch { toast('无法访问剪贴板，请从 JSON 视图复制。'); } break;
  }
});
for (const dialog of $$('dialog')) dialog.addEventListener('click', event => { if (event.target === dialog) { const rect = dialog.getBoundingClientRect(); if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) dialog.close(); } });
new ResizeObserver(fitPageSize).observe($('#table-view'));
window.addEventListener('pagehide', event => { if (event.persisted) pplAssist.reset(); else pplAssist.dispose(); state.controller?.abort(); clearTimeout(fitTimer); clearTimeout(state.exportTimer); state.exportPollVersion++; });
async function initialize() {
  render();
  try {
    const config = await request('ui/config'); if (!Array.isArray(config.sources) || !config.sources.length) throw new Error('服务端没有配置可用的数据源。');
    state.sources = config.sources;
    const maximum = Number(config.maxPageSize);
    state.maxPageSize = Number.isInteger(maximum) && maximum > 0 ? maximum : 1000;
    state.pageSize = Math.min(state.pageSize, state.maxPageSize);
    $$('#page-size option').forEach(option => { if (option.value !== 'auto') option.disabled = Number(option.value) > state.maxPageSize; });
    $('#source-count').textContent = state.sources.length;
    $('#source-list').innerHTML = state.sources.map(source => `<button class="source-nav" data-source="${escapeHtml(source.name)}"><span class="source-logo ${source.type === 'clickhouse' ? 'clickhouse' : 'elastic'}">${icon(source.type === 'clickhouse' ? 'columns' : 'database')}</span><div><strong>${escapeHtml(source.type)}</strong><small>${escapeHtml(source.name)}</small></div></button>`).join('');
    $('#dataset-select').innerHTML = state.sources.map(source => `<option value="${escapeHtml(source.name)}">${escapeHtml(source.name)}</option>`).join(''); $('#dataset-select').disabled = false;
    setSource(state.sources.some(source => source.name === config.defaultSource) ? config.defaultSource : state.sources[0].name); setStatus('服务已连接', 'ok');
    try { const task = JSON.parse(localStorage.getItem(exportKey)); if (task && typeof task.id === 'string' && ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(task.state)) { state.exportTask = task; renderExportTask(); pollExport(); } } catch { /* Storage may be unavailable or contain an older format. */ }
    if ($('#query-input').value.trim()) runSearch();
  } catch (error) { setStatus('配置加载失败', 'error'); queryError(error.message); }
}
initialize();
