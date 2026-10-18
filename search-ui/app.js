'use strict';

const icons = {
  search: '<circle cx="10.8" cy="10.8" r="6.8"/><path d="m16 16 4.8 4.8"/>',
  play: '<path d="m8 4 12 8-12 8z"/>',
  bolt: '<path d="m13 2-9 12h7l-1 8 10-13h-7z"/>',
  history: '<path d="M3 11a9 9 0 1 1 2.7 7.4M3 5v6h6m3-4v5l3 2"/>',
  database: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 4 16 4 16 0V5M4 12c0 4 16 4 16 0"/>',
  columns: '<path d="M5 5v14M10 3v18M15 3v18M20 6v12"/>',
  star: '<path d="m12 3 2.8 5.7 6.3.9-4.6 4.5 1.1 6.3-5.6-3-5.6 3 1.1-6.3L3 9.6l6.2-.9z"/>',
  help: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9a2.5 2.5 0 0 1 5 .6c0 1.8-2.5 2-2.5 3.5m0 3v.1"/>',
  'arrow-up-right': '<path d="M6 18 18 6M6 6h12v12"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1.4"/><rect x="14" y="3" width="7" height="7" rx="1.4"/><rect x="3" y="14" width="7" height="7" rx="1.4"/><rect x="14" y="14" width="7" height="7" rx="1.4"/>',
  keyboard: '<rect x="2" y="5" width="20" height="14" rx="3"/><path d="M6 9h.1M10 9h.1M14 9h.1M18 9h.1M6 12h.1M10 12h.1M14 12h.1M18 12h.1M7 16h10"/>',
  eraser: '<path d="m4 13 9-9a2 2 0 0 1 3 0l4 4a2 2 0 0 1 0 3l-9 9H7l-3-3a3 3 0 0 1 0-4zm5-5 9 9M11 20h10"/>',
  'chevron-down': '<path d="m6 9 6 6 6-6"/>',
  'chevron-up': '<path d="m6 15 6-6 6 6"/>',
  'chevron-right': '<path d="m9 6 6 6-6 6"/>',
  'chevron-left': '<path d="m15 6-6 6 6 6"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/>',
  return: '<path d="M20 5v7a3 3 0 0 1-3 3H4m4-4-4 4 4 4"/>',
  filter: '<path d="M3 5h18l-7 8v6l-4 2v-8z"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  x: '<path d="m6 6 12 12M6 18 18 6"/>',
  'check-circle': '<circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/>',
  list: '<path d="M8 6h13M8 12h13M8 18h13M3 6h.1M3 12h.1M3 18h.1"/>',
  code: '<path d="m7 7-5 5 5 5m10-10 5 5-5 5m-4-14-2 18"/>',
  panel: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5M4 15v5a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-5"/>',
  refresh: '<path d="M20.5 10A8.5 8.5 0 0 0 5 6l-2 4m0-6v6h6m-5.5 4A8.5 8.5 0 0 0 19 18l2-4m0 6v-6h-6"/>',
  bulb: '<path d="M8 16c0-3-3-3.5-3-7a7 7 0 0 1 14 0c0 3.5-3 4-3 7zm1 4h6m-5 3h4"/>',
  'arrow-down': '<path d="M12 4v16m-5-5 5 5 5-5"/>',
  copy: '<rect x="8" y="8" width="12" height="13" rx="2"/><path d="M15 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/>',
};
const $ = (s, root = document) => root.querySelector(s);
const $$ = (s, root = document) => [...root.querySelectorAll(s)];
const escapeHtml = v => String(v).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon = name => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.code}</svg>`;
function fillIcons(root = document) { $$('[data-icon]', root).forEach(el => { el.innerHTML = icon(el.dataset.icon); }); }
fillIcons();

const anchor = Date.parse('2026-10-18T14:30:00+08:00');
const services = ['api-gateway','payment-api','user-service','order-service','inventory-api','scheduler'];
const fields = ['@timestamp','level','service','message','host','trace_id','duration','environment'];
const fieldTypes = {'@timestamp':'◷',level:'Aa',service:'Aa',message:'Aa',host:'Aa',trace_id:'#',duration:'#',environment:'Aa'};
const templates = {
  INFO: ['Request completed successfully · GET /api/v1/orders','Payment transaction processed successfully','User session authenticated · token verified','Order created · inventory reservation confirmed','Cache refreshed · 128 entries updated','Health check passed · all dependencies available'],
  WARN: ['Response time exceeded threshold · upstream latency','Retrying request · connection temporarily unavailable','Cache miss · falling back to primary database'],
  ERROR: ['Payment provider timeout · connection reset by peer','Failed to process request · upstream returned 503','Database connection failed · pool exhausted'],
};
// Deterministic local sample records. No network requests or backend credentials.
function generateData(source) {
  let seed = source === 'logs' ? 74 : 191;
  const random = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296; };
  const count = source === 'logs' ? 1284 : 864;
  return Array.from({length:count + 420}, (_, i) => {
    const recent = i < count;
    const minute = recent ? 2 + Math.floor(Math.pow(random(), .78) * 58) : 60 + Math.floor(random() * 1380);
    let level = i % 39 === 2 ? 'ERROR' : i % 8 === 1 ? 'WARN' : 'INFO';
    if(i < 8) level = ['INFO','INFO','WARN','INFO','ERROR','INFO','INFO','WARN'][i];
    const service = i < 6 ? services[i] : services[Math.floor(random() * services.length)];
    const timestamp = i < 8 ? anchor - 27000 - i * 11037 : anchor - (minute * 60 + Math.floor(random() * 60)) * 1000 - i % 1000;
    const sampleMessages = ['Request completed · GET /api/v1/orders · 200 OK','Payment transaction processed · amount=299.00','Response time exceeded threshold · duration=582ms','Order created · inventory reservation confirmed','Failed to process request · upstream returned 503','Health check passed · all dependencies available','Cache refreshed · 128 entries updated','Retrying request · connection temporarily unavailable'];
    return { id:`${source}-${i}`, timestamp, level, service, message:i<8?sampleMessages[i]:templates[level][i % templates[level].length], host:`node-${String(i % 8 + 1).padStart(2,'0')}`, trace_id:`a${(i * 8179 + 328761).toString(16)}d${(i * 1327 + 11981).toString(16)}`, duration:level === 'ERROR' ? 3000 + i % 450 : level === 'WARN' ? 580 + i % 780 : 12 + i % 210, environment:recent || i % 5 !== 0 ? 'production' : 'staging', source };
  }).sort((a,b) => b.timestamp - a.timestamp).map((r,i) => ({...r, '@timestamp':formatDate(r.timestamp)}));
}
function formatDate(ts) { const d = new Date(ts + 8 * 3600000); return `${d.toISOString().slice(0,10)} ${d.toISOString().slice(11,23)}`; }
function formatTime(ts) { return formatDate(ts).slice(11,19); }
function formatMinute(ts) { return formatDate(ts).slice(11,16); }
const data = {logs:generateData('logs'), analytics:generateData('analytics')};
const quickQueries = {errors:"level = 'ERROR'",slow:'duration > 500',payment:"service = 'payment-api'"};
function readStorage(key, fallback) { try { const v=JSON.parse(localStorage.getItem(key)); return Array.isArray(v) ? v.filter(r => r && typeof r.name === 'string' && typeof r.query === 'string' && ['sql','ppl'].includes(r.mode) && ['logs','analytics'].includes(r.source) && [15,60,360,1440].includes(r.minutes) && Array.isArray(r.filters) && r.filters.every(f => f && fields.includes(f.field) && ['=','!=','>','<'].includes(f.op) && ['string','number'].includes(typeof f.value))) : fallback; } catch { return fallback; } }
const state = {source:'logs',mode:'sql',view:'table',minutes:60,query:'SELECT * FROM application_logs',filters:[{field:'environment',op:'=',value:'production'}],page:1,pageSize:8,descending:true,results:[],history:readStorage('lens.history.v1',[]),chartVisible:true,detail:null,selection:null,columns:null};
let toastTimer, searchTimer;
function persist(key, value) { try { localStorage.setItem(key,JSON.stringify(value)); } catch { toast('浏览器未允许存储，本次内容仅保留在当前页面'); } }
function toast(message) { clearTimeout(toastTimer); $('#toast').textContent = message; $('#toast').classList.add('visible'); toastTimer=setTimeout(() => $('#toast').classList.remove('visible'),2800); }
function fieldValue(row, name) { if(!fields.includes(name)) throw new Error(`未知字段「${name}」。可用字段：${fields.join('、')}`); return row[name]; }
function comparison(field, op, value) {
  if(!fields.includes(field)) throw new Error(`未找到字段「${field}」`);
  if(['>','>=','<','<='].includes(op) && field !== 'duration') throw new Error('当前原型的大小比较支持 duration 字段');
  if(field === 'duration' && !Number.isFinite(Number(value))) throw new Error('duration 请填写数字');
  return row => { const a=fieldValue(row,field); const b=typeof a === 'number' ? Number(value) : String(value); if(op==='>' )return a>b; if(op==='>=')return a>=b; if(op==='<')return a<b; if(op==='<=')return a<=b; if(op==='!=' || op==='<>')return String(a).toLowerCase()!==String(b).toLowerCase(); return String(a).toLowerCase()===String(b).toLowerCase(); };
}
function parseConditions(text) {
  if(!text.trim()) return () => true;
  const parts = text.split(/\s+AND\s+/i);
  const predicates = parts.map(part => {
    const match=part.trim().match(/^([@\w.]+)\s*(!=|<>|>=|<=|=|>|<)\s*(?:'([^']*)'|"([^"]*)"|([^\s'"]+))$/);
    if(match) return comparison(match[1],match[2],match[3] ?? match[4] ?? match[5]);
    throw new Error("请使用字段条件，如 level = 'ERROR' 或 duration > 500，多条件用 AND 连接");
  });
  return row => predicates.every(p => p(row));
}
function compileQuery(query) {
  state.columns=null;
  const table=state.source==='logs'?'application_logs':'request_analytics';
  if(state.mode==='sql') {
    const match=query.trim().replace(/;$/,'').match(/^SELECT\s+(\*|[@\w,\s]+?)\s+FROM\s+`?([\w]+)`?(?:\s+WHERE\s+(.+?))?(?:\s+ORDER\s+BY\s+(@timestamp|duration)\s+(ASC|DESC))?(?:\s+LIMIT\s+(\d+))?$/i);
    if(!match) throw new Error('此 UI 原型支持 SELECT 字段 FROM 数据表 [WHERE 条件] [ORDER BY @timestamp 或 duration ASC / DESC] [LIMIT 数量]');
    if(match[2]!==table) throw new Error(`当前数据表是 ${table}，请更新 FROM 或切换数据源`);
    if(match[1]!=='*') { state.columns=match[1].split(',').map(c=>c.trim()); state.columns.forEach(c=>fieldValue(data[state.source][0],c)); }
    return {predicate:parseConditions(match[3]||''),limit:match[6]===undefined?Infinity:Number(match[6]),sort:match[4],direction:match[5]?.toUpperCase()};
  }
  const stages=query.split('|').map(p=>p.trim());
  if(stages.shift()!==`source=${table}`) throw new Error(`PPL 查询需要以 source=${table} 开头`);
  let predicate=()=>true,limit=Infinity,sort,direction;
  for(const stage of stages) {
    if(/^where\s+/i.test(stage)) { const previous=predicate,next=parseConditions(stage.replace(/^where\s+/i,''));predicate=row=>previous(row)&&next(row); }
    else if(/^head\s+\d+$/i.test(stage))limit=Number(stage.split(/\s+/)[1]);
    else if(/^fields\s+/i.test(stage)){state.columns=stage.replace(/^fields\s+/i,'').split(',').map(c=>c.trim());state.columns.forEach(c=>fieldValue(data[state.source][0],c));}
    else if(/^sort\s+[-+]?(@timestamp|duration)$/i.test(stage)){const v=stage.split(/\s+/)[1];sort=v.replace(/^[-+]/,'');direction=v.startsWith('-')?'DESC':'ASC';}
    else throw new Error('此 UI 原型支持 PPL 的 where、fields、sort、head 管道');
  }
  return {predicate,limit,sort,direction};
}
function executeQuery(recordHistory = true) {
  const start=performance.now();
  const previousColumns=state.columns;
  try {
    const compiled=compileQuery(state.query);
    const predicates=state.filters.map(f=>comparison(f.field,f.op,f.value));
    let rows=data[state.source].filter(r=>r.timestamp>anchor-state.minutes*60000 && compiled.predicate(r) && predicates.every(p=>p(r)));
    if(state.selection)rows=rows.filter(r=>r.timestamp>=state.selection.start && r.timestamp<state.selection.end);
    if(compiled.sort)rows.sort((a,b)=>{const av=compiled.sort==='@timestamp'?a.timestamp:a[compiled.sort],bv=compiled.sort==='@timestamp'?b.timestamp:b[compiled.sort];return (av-bv)*(compiled.direction==='ASC'?1:-1);});
    else rows.sort((a,b)=>state.descending?b.timestamp-a.timestamp:a.timestamp-b.timestamp);
    state.results=rows.slice(0,compiled.limit);
    $('#query-error').hidden=true;
    $('#elapsed').textContent=`查询完成 · ${Math.max(1,Math.round(performance.now()-start))} ms`;
    if(recordHistory){ const item=snapshot(state.query||'全部数据');state.history=[{...item,id:`h-${Date.now()}`},...state.history.filter(h=>h.query!==item.query||h.source!==item.source||h.mode!==item.mode)].slice(0,20);persist('lens.history.v1',state.history); }
    render();
    return true;
  } catch(error){ state.columns=previousColumns;$('#query-error').textContent=error.message;$('#query-error').hidden=false;return false; }
}
function runSearch() {
  clearTimeout(searchTimer);state.query=$('#query-input').value.trim();state.page=1;
  document.body.classList.add('is-searching');$('#search-button').disabled=true;
  searchTimer=setTimeout(()=>{executeQuery();document.body.classList.remove('is-searching');$('#search-button').disabled=false;},180);
}
function snapshot(name){return {id:`s-${Date.now()}`,name,query:state.query,mode:state.mode,source:state.source,minutes:state.minutes,filters:state.filters.map(f=>({...f}))};}
function render(){renderFilters();renderChart();renderRows();renderFields();}
function renderFilters(){
  $('#filter-chips').innerHTML=state.filters.map((f,i)=>`<span class="filter-chip"><span>${escapeHtml(f.field)}</span><span>${escapeHtml(f.op)}</span><b>${escapeHtml(f.value)}</b><button data-remove-filter="${i}" aria-label="移除 ${escapeHtml(f.field)} 筛选">${icon('x')}</button></span>`).join('')+(state.selection?`<span class="filter-chip"><span>${formatMinute(state.selection.start)} – ${formatMinute(state.selection.end)}</span><button data-action="clear-selection" aria-label="清除图表时间筛选">${icon('x')}</button></span>`:'');
}
function renderChart(){
  const rows=state.results;
  $('#result-count').textContent=rows.length.toLocaleString('en-US');$('#tab-count').textContent=rows.length.toLocaleString('en-US');
  for(const level of ['INFO','WARN','ERROR'])$('#'+level.toLowerCase()+'-count').textContent=rows.filter(r=>r.level===level).length.toLocaleString('en-US');
  const buckets=Array.from({length:60},()=>({INFO:0,WARN:0,ERROR:0}));const start=anchor-state.minutes*60000,width=state.minutes*1000;
  rows.forEach(r=>{const i=Math.min(59,Math.max(0,Math.floor((r.timestamp-start)/width)));buckets[i][r.level]++;});
  const max=Math.max(2,...buckets.map(b=>b.INFO+b.WARN+b.ERROR));const ceiling=Math.ceil(max/10)*10;
  $('#chart-max').textContent=ceiling;$('#chart-mid').textContent=ceiling/2;
  $('#chart-granularity').textContent=state.minutes===15?'每 15 秒':state.minutes===60?'每分钟':`每 ${state.minutes/60} 分钟`;
  $('#histogram').innerHTML=buckets.map((b,i)=>{const title=`${formatMinute(start+i*width)} · ${b.INFO+b.WARN+b.ERROR} 条记录（Info ${b.INFO} / Warn ${b.WARN} / Error ${b.ERROR}）`;return `<button class="chart-bar" data-bucket="${i}" title="${title}" aria-label="筛选 ${title}">${['INFO','WARN','ERROR'].map(level=>`<i class="${level.toLowerCase()}" style="height:${b[level]/ceiling*100}%"></i>`).join('')}</button>`;}).join('');
  $('#histogram').setAttribute('aria-label',`当前 ${rows.length} 条结果的时间分布，点击柱形筛选时间`);
  $('#histogram').setAttribute('role','group');
  $('#chart-axis').innerHTML=Array.from({length:7},(_,i)=>`<span>${formatMinute(start+i*state.minutes*10000)}</span>`).join('');
}
function visibleRows(){return state.results.slice((state.page-1)*state.pageSize,state.page*state.pageSize);}
function projectRow(row){const result={};(state.columns||fields).forEach(f=>result[f]=row[f]);return result;}
function renderRows(){
  const maxPage=Math.max(1,Math.ceil(state.results.length/state.pageSize));state.page=Math.min(state.page,maxPage);
  const rows=visibleRows();const empty=state.results.length===0;
  $('#empty-state').hidden=!empty;$('#table-view').hidden=empty||state.view==='json';$('#json-view').hidden=empty||state.view!=='json';
  if(state.columns){
    $('thead').innerHTML=`<tr><th class="expand-col"></th>${state.columns.map(c=>`<th>${escapeHtml(c)}</th>`).join('')}</tr>`;
    $('#results-rows').innerHTML=rows.map(r=>`<tr class="result-row" tabindex="0" data-row-id="${r.id}" aria-label="查看 ${formatTime(r.timestamp)} 的记录"><td>${icon('chevron-right')}</td>${state.columns.map(c=>`<td title="${escapeHtml(r[c])}">${escapeHtml(r[c])}</td>`).join('')}</tr>`).join('');
  }else{
    $('thead').innerHTML=`<tr><th class="expand-col"><span class="sr-only">详情</span></th><th class="time-col"><button id="sort-time">时间${icon(state.descending?'arrow-down':'chevron-up')}</button></th><th class="level-col">级别</th><th class="service-col">服务</th><th>消息</th></tr>`;
    $('#results-rows').innerHTML=rows.map(r=>`<tr class="result-row" tabindex="0" data-row-id="${r.id}" aria-label="查看 ${formatTime(r.timestamp)} ${r.service} 的记录"><td>${icon('chevron-right')}</td><td class="timestamp" title="${escapeHtml(r['@timestamp'])}">${formatTime(r.timestamp)}<span style="color:#bbc2cc">.${String(r.timestamp%1000).padStart(3,'0')}</span></td><td><span class="level-badge ${r.level}">${r.level}</span></td><td class="service-name">${escapeHtml(r.service)}</td><td class="message-cell" title="${escapeHtml(r.message)}">${escapeHtml(r.message)}</td></tr>`).join('');
  }
  $('#json-content').textContent=JSON.stringify(rows.map(projectRow),null,2);
  const first=empty?0:(state.page-1)*state.pageSize+1,last=Math.min(state.page*state.pageSize,state.results.length);
  $('#page-summary').textContent=`显示 ${first}–${last} 条，共 ${state.results.length.toLocaleString('en-US')} 条`;
  $('#page-number').textContent=state.page;$('#prev-page').disabled=state.page<=1;$('#next-page').disabled=state.page>=maxPage;
}
function fitPageSize(){
  const viewport=$('#table-view');
  const row=$('#results-rows tr');
  if($('#page-size').value!=='auto'||viewport.hidden||!row)return;
  const available=viewport.clientHeight-$('thead').getBoundingClientRect().height;
  const size=Math.max(1,Math.floor(available/row.getBoundingClientRect().height));
  $('#page-size option[value="auto"]').textContent=`自适应 · ${size} 条`;
  if(size===state.pageSize)return;
  const firstRow=(state.page-1)*state.pageSize;
  state.pageSize=size;
  state.page=Math.floor(firstRow/size)+1;
  renderRows();
}
function renderFields(){
  const term=$('#field-search').value.toLowerCase();const list=fields.filter(f=>f.toLowerCase().includes(term));
  $('#field-list').innerHTML=list.map((f,i)=>`${i===4&&!term?'<p class="other-fields">其他字段</p>':''}<button class="field-item" data-field="${f}"><span class="field-type">${fieldTypes[f]}</span>${f}<span>${icon('plus')}</span></button>`).join('')||'<p class="modal-note">没有匹配字段</p>';
}
function openModal(title, content){$('#modal-title').textContent=title;$('#modal-content').innerHTML=content;fillIcons($('#modal'));if(!$('#modal').open)$('#modal').showModal();}
function openFilter(prefill={}){
  openModal('添加筛选条件',`<form class="modal-form" id="filter-form"><label>字段<select id="filter-field">${fields.map(f=>`<option ${f===prefill.field?'selected':''}>${f}</option>`).join('')}</select></label><label>条件<select id="filter-op"><option value="=">等于</option><option value="!=">不等于</option><option value=">">大于（数字）</option><option value="<">小于（数字）</option></select></label><label>值<input id="filter-value" required placeholder="输入字段值" value="${escapeHtml(prefill.value??'')}" /></label><div class="modal-actions"><button type="button" class="button secondary" data-action="close-modal">取消</button><button class="button primary">应用筛选</button></div><p class="modal-note" id="filter-error" role="alert" hidden></p></form>`);
  $('#filter-form').addEventListener('submit',e=>{e.preventDefault();const f={field:$('#filter-field').value,op:$('#filter-op').value,value:$('#filter-value').value.trim()};try{comparison(f.field,f.op,f.value);addFilter(f);$('#modal').close();}catch(error){$('#filter-error').textContent=error.message;$('#filter-error').hidden=false;}});
}
function addFilter(filter){if(!state.filters.some(f=>f.field===filter.field&&f.op===filter.op&&f.value===filter.value))state.filters.push(filter);state.page=1;executeQuery(false);}
function openField(field){
  const values=new Map();state.results.forEach(r=>{const v=String(r[field]);values.set(v,(values.get(v)||0)+1);});const sorted=[...values.entries()].sort((a,b)=>b[1]-a[1]).slice(0,8);
  openModal(field,`<p class="modal-note">当前结果中的常见值，点击即可筛选。</p>${sorted.map(([v,n],i)=>`<button class="field-value" data-value-index="${i}"><span>${escapeHtml(v)}</span><span>${n.toLocaleString()} 条 ${icon('plus')}</span><i class="field-value-bar" style="width:${n/Math.max(1,state.results.length)*100}%"></i></button>`).join('')||'<p class="modal-note">当前没有可用的字段值。</p>'}<div class="modal-actions"><button class="button secondary" id="custom-field">自定义条件</button></div>`);
  $$('[data-value-index]').forEach(b=>b.onclick=()=>{addFilter({field,op:'=',value:sorted[Number(b.dataset.valueIndex)][0]});$('#modal').close();});$('#custom-field').onclick=()=>openFilter({field});
}
function detail(id){
  const row=data[state.source].find(r=>r.id===id);if(!row)return;state.detail=row;
  $('#detail-content').innerHTML=`<div class="detail-index">${icon('database')}${state.source==='logs'?'application_logs':'request_analytics'}<span> / ${escapeHtml(row.id)}</span></div><div class="detail-metadata"><span class="level-badge ${row.level}">${row.level}</span><span>${escapeHtml(row['@timestamp'])} +08:00</span></div><div class="detail-message">${escapeHtml(row.message)}</div>${fields.filter(f=>f!=='message').map(f=>`<div class="detail-field"><span>${escapeHtml(f)}</span><code>${escapeHtml(row[f])}${f==='duration'?' ms':''}</code></div>`).join('')}<div class="detail-actions"><button class="button secondary" data-action="copy-record">${icon('copy')}复制 JSON</button><button class="button secondary" data-action="trace-record">${icon('search')}查看关联数据</button></div>`;
  $('#detail-drawer').showModal();
}
function setMode(mode, replace = true){
  if(replace) drafts[state.mode]=$('#query-input').value;
  state.mode=mode;
  $$('[data-mode]').forEach(b=>{
    b.classList.toggle('selected',b.dataset.mode===mode);
    b.setAttribute('aria-selected',b.dataset.mode===mode);
  });
  if(replace) $('#query-input').value=drafts[mode]??defaultQuery(mode);
  $('#query-input').placeholder=defaultQuery(mode);
  $('#query-input').setAttribute('aria-label',`${mode.toUpperCase()} 查询`);
  state.query=$('#query-input').value.trim();
  $('#query-error').hidden=true;
}
const drafts={};
function defaultQuery(mode){
  const table=state.source==='logs'?'application_logs':'request_analytics';
  return mode==='sql'?`SELECT * FROM ${table}`:`source=${table}`;
}
function loadQuickQuery(id){
  const condition=quickQueries[id];
  if(!condition)return;
  const base=defaultQuery(state.mode);
  $('#query-input').value=state.mode==='sql'?`${base} WHERE ${condition}`:`${base} | where ${condition}`;
  state.selection=null;
  runSearch();
}
function setSource(source){
  state.source=source;state.selection=null;state.page=1;$('#dataset-select').value=source;$$('[data-source]').forEach(b=>b.classList.toggle('selected',b.dataset.source===source));
  for(const mode of Object.keys(drafts)){drafts[mode]=drafts[mode].replaceAll(source==='logs'?'request_analytics':'application_logs',source==='logs'?'application_logs':'request_analytics');}
  $('#query-input').value=$('#query-input').value.replaceAll(source==='logs'?'request_analytics':'application_logs',source==='logs'?'application_logs':'request_analytics');
  state.query=$('#query-input').value.trim();executeQuery(false);
}
function loadSearch(item){
  state.source=item.source;state.minutes=item.minutes;state.filters=item.filters.map(f=>({...f}));state.selection=null;state.page=1;state.query=item.query;
  $('#query-input').value=item.query;setMode(item.mode,false);$('#time-select').value=item.minutes;$('#dataset-select').value=item.source;$$('[data-source]').forEach(b=>b.classList.toggle('selected',b.dataset.source===item.source));
  setHistoryOpen(false);$('.sidebar').classList.remove('open');executeQuery();
}
function setHistoryOpen(open){
  if(open)renderHistory();
  $('#query-history-panel').hidden=!open;
  $('[data-action="history"]').setAttribute('aria-expanded',open);
}
function renderHistory(){
  $('#query-history-list').innerHTML=state.history.map((item,i)=>`
    <div class="history-item">
      <button type="button" class="history-query" data-load-history="${i}" aria-label="重新执行 ${escapeHtml(item.query)}">
        <span class="history-meta"><b>${item.mode.toUpperCase()}</b><span>${escapeHtml(item.source)}</span><span>最近 ${item.minutes<60?`${item.minutes} 分钟`:`${item.minutes/60} 小时`}</span></span>
        <code>${escapeHtml(item.query)}</code>
      </button>
      <button type="button" class="icon-button" data-delete-history="${i}" aria-label="移除历史记录 ${i+1}">${icon('x')}</button>
    </div>`).join('')||'<div class="history-empty">还没有查询历史<span>执行 SQL 或 PPL 后，可在这里快速复用。</span></div>';
}
function showHelp(){openModal('查询快捷键',`<div class="help-row"><span>聚焦查询编辑器</span><kbd>⌘ / Ctrl K</kbd></div><div class="help-row"><span>执行查询</span><kbd>⌘ / Ctrl Enter</kbd></div><div class="help-row"><span>编辑器换行</span><kbd>Enter</kbd></div><div class="help-row"><span>关闭历史、弹窗或详情</span><kbd>Esc</kbd></div><hr style="border:0;border-top:1px solid #edf0f5;margin:25px 0"/><p class="modal-note">仅支持 SQL 和 PPL 查询。快捷查询会根据当前语言和数据表生成语句；执行成功后自动记录查询历史。点击字段值或分布图，可进一步筛选结果。</p><p class="modal-note">此版本使用本地示例数据。SQL 支持 SELECT、WHERE、ORDER BY、LIMIT；PPL 支持 where、fields、sort、head。其他语法将在接入查询引擎后提供。</p>`);}
function exportData(){
  if(!state.results.length)return toast('没有可导出的数据');
  const columns=state.columns||fields;
  const cell=v=>{const s=String(v??'');return `"${/^[=+\-@\t\r]/.test(s)?"'":''}${s.replaceAll('"','""')}"`;};
  const csv='\uFEFF'+[columns.map(cell).join(','),...state.results.map(r=>columns.map(f=>cell(r[f])).join(','))].join('\r\n');
  const url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download=`lens-${state.source}-results.csv`;document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);toast(`已导出 ${state.results.length.toLocaleString()} 条记录`);
}
function resetSearch(){state.filters=[];state.selection=null;state.page=1;$('#query-input').value=defaultQuery(state.mode);state.query=$('#query-input').value;executeQuery(false);}

$('#search-form').addEventListener('submit',e=>{e.preventDefault();runSearch();});
$('#query-input').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey&&(e.metaKey||e.ctrlKey)){e.preventDefault();runSearch();}});
$('#dataset-select').addEventListener('change',e=>setSource(e.target.value));
$('#time-select').addEventListener('change',e=>{state.minutes=Number(e.target.value);state.page=1;state.selection=null;executeQuery(false);});
$('#field-search').addEventListener('input',renderFields);
$('#page-size').addEventListener('change',e=>{state.page=1;if(e.target.value==='auto')fitPageSize();else state.pageSize=Number(e.target.value);renderRows();$('#table-view').scrollTop=0;});
$('#prev-page').onclick=()=>{state.page--;renderRows();$('#table-view').scrollTop=0;};$('#next-page').onclick=()=>{state.page++;renderRows();$('#table-view').scrollTop=0;};
document.addEventListener('keydown',e=>{
  if((e.metaKey||e.ctrlKey)&&e.key.toLowerCase()==='k'){e.preventDefault();if($('#modal').open)$('#modal').close();if($('#detail-drawer').open)$('#detail-drawer').close();$('#query-input').focus();$('#query-input').select();}
  if(e.key==='Escape'){$('.sidebar').classList.remove('open');if(!$('#query-history-panel').hidden){setHistoryOpen(false);$('[data-action="history"]').focus();}}
  if((e.key==='Enter'||e.key===' ')&&e.target.matches('[data-row-id]')){e.preventDefault();detail(e.target.dataset.rowId);}
});
document.addEventListener('click',async e=>{
  if(!e.target.closest('#query-history-panel')&&!e.target.closest('[data-action="history"]'))setHistoryOpen(false);
  const button=e.target.closest('button');
  if(e.target.closest('[data-row-id]')){detail(e.target.closest('[data-row-id]').dataset.rowId);return;}
  if(!button)return;
  if(button.dataset.mode){setMode(button.dataset.mode);return;}
  if(button.dataset.source){setSource(button.dataset.source);$('.sidebar').classList.remove('open');return;}
  if(button.dataset.preset){loadQuickQuery(button.dataset.preset);return;}
  if(button.dataset.loadHistory!==undefined){loadSearch(state.history[Number(button.dataset.loadHistory)]);return;}
  if(button.dataset.deleteHistory!==undefined){state.history.splice(Number(button.dataset.deleteHistory),1);persist('lens.history.v1',state.history);renderHistory();return;}
  if(button.dataset.removeFilter!==undefined){state.filters.splice(Number(button.dataset.removeFilter),1);state.page=1;executeQuery(false);return;}
  if(button.dataset.level){addFilter({field:'level',op:'=',value:button.dataset.level});return;}
  if(button.dataset.field){openField(button.dataset.field);return;}
  if(button.dataset.bucket!==undefined){const start=anchor-state.minutes*60000+Number(button.dataset.bucket)*state.minutes*1000;state.selection={start,end:start+state.minutes*1000};state.page=1;executeQuery(false);return;}
  if(button.dataset.view){state.view=button.dataset.view;$$('[data-view]').forEach(b=>{b.classList.toggle('selected',b.dataset.view===state.view);b.setAttribute('aria-selected',b.dataset.view===state.view);});renderRows();return;}
  if(button.id==='sort-time'){state.descending=!state.descending;state.results.sort((a,b)=>state.descending?b.timestamp-a.timestamp:a.timestamp-b.timestamp);state.page=1;renderRows();return;}
  switch(button.dataset.action){
    case 'home':$('.sidebar').classList.remove('open');$('#query-input').focus();break;
    case 'menu':$('.sidebar').classList.toggle('open');break;
    case 'history':setHistoryOpen($('#query-history-panel').hidden);break;
    case 'close-history':setHistoryOpen(false);$('[data-action="history"]').focus();break;
    case 'help':showHelp();break;
    case 'filter':openFilter({field:'service'});break;
    case 'clear-query':$('#query-input').value='';$('#query-input').focus();break;
    case 'close-modal':$('#modal').close();break;
    case 'close-detail':$('#detail-drawer').close();break;
    case 'clear-selection':state.selection=null;state.page=1;executeQuery(false);break;
    case 'chart':state.chartVisible=!state.chartVisible;$('#chart-area').hidden=!state.chartVisible;button.innerHTML=icon(state.chartVisible?'chevron-up':'chevron-down');button.setAttribute('aria-expanded',state.chartVisible);button.setAttribute('aria-label',state.chartVisible?'收起趋势图':'展开趋势图');break;
    case 'fields':{const panel=$('#fields-panel');const visible=getComputedStyle(panel).display!=='none';panel.style.display=visible?'none':'block';button.setAttribute('aria-expanded',!visible);break;}
    case 'export':exportData();break;
    case 'refresh':runSearch();break;
    case 'reset':resetSearch();break;
    case 'copy-record':try{await navigator.clipboard.writeText(JSON.stringify(projectRow(state.detail),null,2));toast('记录 JSON 已复制');}catch{toast('无法访问剪贴板，请从 JSON 视图选择并复制');}break;
    case 'trace-record':state.filters=[{field:'trace_id',op:'=',value:state.detail.trace_id}];state.selection=null;state.page=1;$('#query-input').value=defaultQuery(state.mode);state.query=$('#query-input').value;$('#detail-drawer').close();executeQuery();break;
  }
});
for(const dialog of $$('dialog'))dialog.addEventListener('click',e=>{if(e.target===dialog){const r=dialog.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)dialog.close();}});
executeQuery(false);
const resultsResizeObserver=new ResizeObserver(fitPageSize);
resultsResizeObserver.observe($('#table-view'));
