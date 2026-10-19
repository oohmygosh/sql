import assert from 'node:assert/strict';
import { test } from 'node:test';
import { normalizeResponse } from '../src/api.js';
import { useWorkspace } from '../src/composables/useWorkspace.js';

const settle = () => new Promise((resolve) => setImmediate(resolve));
const resolvedTimeRange = {
  field: '@timestamp',
  from: '2026-10-17T00:00:00.123+08:00',
  to: '2026-10-18T00:00:00.456+08:00',
  timeZone: 'Asia/Shanghai',
};
const sources = [
  { name: 'logs', table: 'logs', timeField: '@timestamp', timeZone: 'Asia/Shanghai' },
  { name: 'archive', table: 'archive' },
];
function rowsResponse({ value = 'successful rows', number = 1, size = 20, warnings = [] } = {}) {
  return {
    rows: { schema: [{ name: 'message', type: 'string' }], datarows: [[value]] },
    histogram: null,
    page: { number, size, hasNext: true },
    resolvedTimeRange,
    warnings,
  };
}
function histogramResponse({ count = 10, warnings = [] } = {}) {
  return {
    rows: null,
    page: null,
    histogram: {
      status: 'ok',
      interval: '1d',
      timeZone: resolvedTimeRange.timeZone,
      buckets: [{ from: resolvedTimeRange.from, to: resolvedTimeRange.to, count }],
    },
    resolvedTimeRange,
    warnings,
  };
}
function harness(t) {
  const previous = {
    window: globalThis.window,
    localStorage: globalThis.localStorage,
    fetch: globalThis.fetch,
  };
  const requests = [], storage = new Map(), messages = [];
  globalThis.window = { location: { href: 'http://localhost/workspace/' } };
  globalThis.localStorage = {
    getItem: (key) => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
  };
  globalThis.fetch = (url, options) => {
    if (url.endsWith('/ui/config'))
      return Promise.resolve(Response.json({ defaultSource: 'logs', sources }));
    assert.equal(url, 'http://localhost/workspace/search');
    // Ignore abort here: generation checks must also reject late server responses.
    return new Promise((resolve, reject) => {
      requests.push({
        body: JSON.parse(options.body),
        method: options.method,
        signal: options.signal,
        respond: (body, status = 200) => resolve(Response.json(body, { status })),
        reject,
      });
    });
  };
  const workspace = useWorkspace((message) => messages.push(message));
  workspace.state.sources = sources;
  workspace.setSource('logs');
  t.after(() => {
    workspace.dispose();
    Object.assign(globalThis, previous);
  });
  return { workspace, state: workspace.state, requests, messages };
}
function assertRowsRequest(request, page = { number: 1, size: 20 }) {
  assert.equal(request.method, 'POST');
  assert.deepEqual(request.body.include, ['rows']);
  assert.deepEqual(request.body.page, page);
  assert.equal(request.body.histogram, undefined);
}

test('normalization accepts null unrequested rows and histogram fields', () => {
  const chartOnly = histogramResponse();
  const chart = normalizeResponse(chartOnly);
  assert.ok(!chart.rows);
  assert.equal(chart.histogram, chartOnly.histogram);
  const rowsOnly = normalizeResponse(rowsResponse());
  assert.equal(rowsOnly.rows.rows[0].values[0], 'successful rows');
  assert.equal(rowsOnly.histogram, null);
});

test('initial, explicit and refresh searches finish rows before an independent frozen-range chart', async (t) => {
  const { workspace, state, requests } = harness(t);
  for (const execute of [
    () => workspace.initialize(),
    () => workspace.runSearch(),
    () => workspace.refresh(),
  ]) {
    state.filters = [{ field: 'level', operator: 'in', value: ['WARN', 'ERROR'] }];
    const index = requests.length;
    let completed = false;
    const pending = execute().then(() => { completed = true; });
    await settle();
    const rows = requests[index];
    assertRowsRequest(rows);
    assert.equal(requests.length, index + 1, 'chart waits for the server-resolved row range');
    assert.equal(state.busy, true);
    rows.respond(rowsResponse());
    await settle();

    assert.equal(completed, true, 'row search promise does not wait for the chart');
    assert.equal(state.busy, false);
    assert.equal(state.rows[0].values[0], 'successful rows');
    assert.equal(state.chartBusy, true);
    assert.equal(requests.length, index + 2);
    const chart = requests[index + 1];
    assert.equal(chart.method, 'POST');
    assert.deepEqual(chart.body, {
      source: rows.body.source,
      language: rows.body.language,
      query: rows.body.query,
      filters: rows.body.filters,
      timeRange: resolvedTimeRange,
      include: ['histogram'],
      histogram: { interval: 'auto' },
    });
    assert.notEqual(rows.body.timeRange.from, resolvedTimeRange.from);
    assert.deepEqual(state.lastRequest.timeRange, resolvedTimeRange);
    assert.notEqual(chart.signal, rows.signal);
    const rowElapsed = state.elapsed;
    chart.respond(histogramResponse());
    await settle();
    await pending;
    assert.equal(state.chartBusy, false);
    assert.equal(state.chart.status, 'ok');
    assert.equal(Number(state.chart.buckets[0].count), 10);
    assert.equal(state.elapsed, rowElapsed);
    assert.equal(typeof state.chartElapsed, 'string');
    assert.ok(state.chartElapsed.length > 0);
  }
});

test('row and chart warnings stay separate and clear when their own requests start', async (t) => {
  const { workspace, state, requests } = harness(t);
  state.warning = 'previous row warning';
  state.chartWarning = 'previous chart warning';
  workspace.runSearch();
  assert.equal(state.warning, '');
  requests[0].respond(rowsResponse({
    warnings: ['Rows warning', { type: 'partial', reason: 'Row limit reached', detail: 'Only matching rows' }],
  }));
  await settle();
  assert.match(state.warning, /Rows warning/);
  assert.match(state.warning, /Only matching rows/);
  assert.equal(state.chartWarning, '');
  requests[1].respond(histogramResponse({
    warnings: [
      { type: 'partial', reason: 'Histogram warning', detail: 'Only sampled chart buckets' },
      { type: 'limits', message: 'Legacy chart warning', details: 'Bucket limit reached' },
    ],
  }));
  await settle();
  assert.match(state.chartWarning, /Histogram warning/);
  assert.match(state.chartWarning, /Only sampled chart buckets/);
  assert.match(state.chartWarning, /Legacy chart warning/);
  assert.match(state.chartWarning, /Bucket limit reached/);
  assert.doesNotMatch(state.warning, /Histogram warning|chart buckets|Bucket limit/);
  assert.doesNotMatch(state.chartWarning, /Rows warning|matching rows/);
  const chartWarning = state.chartWarning;

  workspace.runSearch({ continuation: true, number: 2 });
  assert.equal(state.warning, '');
  assert.equal(state.chartWarning, chartWarning);
  requests[2].respond(rowsResponse({ number: 2 }));
  await settle();
  assert.equal(requests.length, 3);
  assert.equal(state.warning, '');
  assert.equal(state.chartWarning, chartWarning);

  workspace.refresh();
  requests[3].respond(rowsResponse({ number: 2 }));
  await settle();
  assert.equal(state.chartWarning, '');
  assert.equal(state.chartBusy, true);
  requests[4].respond(histogramResponse());
  await settle();
  assert.equal(state.chartWarning, '');
});

test('paging and page-size changes preserve a pending histogram without refetching or aborting it', async (t) => {
  const { workspace, state, requests } = harness(t);
  workspace.runSearch();
  requests[0].respond(rowsResponse());
  await settle();
  const chart = requests[1];
  assert.equal(state.chartBusy, true);

  workspace.runSearch({ continuation: true, number: 2 });
  assert.equal(chart.signal.aborted, false);
  assertRowsRequest(requests[2], { number: 2, size: 20 });
  assert.deepEqual(requests[2].body.timeRange, resolvedTimeRange);
  requests[2].respond(rowsResponse({ number: 2, value: 'second page' }));
  await settle();
  assert.equal(state.rows[0].values[0], 'second page');
  assert.equal(state.busy, false);
  assert.equal(state.chartBusy, true);
  assert.equal(requests.length, 3);

  state.pageSizeChoice = '50';
  workspace.pageSizeChanged();
  assert.equal(chart.signal.aborted, false);
  assertRowsRequest(requests[3], { number: 1, size: 50 });
  assert.deepEqual(requests[3].body.timeRange, resolvedTimeRange);
  requests[3].respond(rowsResponse({ size: 50, value: 'larger page' }));
  await settle();
  assert.equal(requests.length, 4);
  assert.equal(state.pageSize, 50);
  assert.equal(state.chartBusy, true);
  assert.equal(chart.signal.aborted, false);
  chart.respond(histogramResponse({ count: 45 }));
  await settle();
  assert.equal(state.chartBusy, false);
  assert.equal(Number(state.chart.buckets[0].count), 45);
  assert.equal(state.rows[0].values[0], 'larger page');
});

test('a late histogram from an earlier search cannot overwrite the newer chart or warnings', async (t) => {
  const { workspace, state, requests } = harness(t);
  workspace.runSearch();
  requests[0].respond(rowsResponse({ value: 'old rows' }));
  await settle();
  const oldChart = requests[1];
  state.query = 'source=`logs` | fields message';
  workspace.runSearch();
  assert.equal(oldChart.signal.aborted, true);
  requests[2].respond(rowsResponse({ value: 'current rows' }));
  await settle();
  assert.equal(state.chartBusy, true);
  requests[3].respond(histogramResponse({ count: 25, warnings: ['Current chart warning'] }));
  await settle();
  const chart = state.chart, warning = state.chartWarning, elapsed = state.chartElapsed;

  oldChart.respond(histogramResponse({ count: 999, warnings: ['Stale chart warning'] }));
  await settle();
  assert.equal(state.rows[0].values[0], 'current rows');
  assert.equal(state.chart, chart);
  assert.equal(state.chartWarning, warning);
  assert.equal(state.chartElapsed, elapsed);
  assert.equal(Number(state.chart.buckets[0].count), 25);
  assert.match(state.chartWarning, /Current chart warning/);
  assert.equal(state.chartBusy, false);
  assert.equal(state.error, '');
});

test('condition edits, source/mode changes, cancel and disposal invalidate late chart results', async (t) => {
  const actions = {
    'query edit': ({ workspace, state }) => { state.query += ' | fields message'; workspace.editQuery(); },
    'filter edit': ({ workspace, state }) => { state.filters = [{ field: 'level', operator: 'is_null' }]; workspace.conditionsChanged(); },
    'source change': ({ workspace }) => workspace.setSource('archive'),
    'mode change': ({ workspace }) => workspace.setMode('sql'),
    cancel: ({ workspace }) => workspace.cancelQuery(),
    suspend: ({ workspace }) => workspace.suspend(),
    dispose: ({ workspace }) => workspace.dispose(),
  };
  for (const [name, invalidate] of Object.entries(actions)) {
    await t.test(name, async (t) => {
      const h = harness(t);
      h.workspace.runSearch();
      h.requests[0].respond(rowsResponse());
      await settle();
      const request = h.requests[1];
      invalidate(h);
      assert.equal(request.signal.aborted, true);
      assert.equal(h.state.chartBusy, false);
      const { chart, rows, chartWarning, chartElapsed, error } = h.state;
      request.respond(histogramResponse({ count: 999, warnings: ['Stale histogram warning'] }));
      await settle();
      assert.equal(h.state.chart, chart);
      assert.equal(h.state.rows, rows);
      assert.equal(h.state.chartWarning, chartWarning);
      assert.equal(h.state.chartElapsed, chartElapsed);
      assert.equal(h.state.error, error);
      assert.equal(h.state.chartBusy, false);
      assert.equal(h.requests.length, 2);
    });
  }
});

for (const scenario of [
  { name: 'HTTP failure', body: { message: 'Histogram timed out' }, status: 503, chartStatus: 'error', reason: /Histogram timed out/ },
  { name: 'histogram error status', body: { rows: null, page: null, histogram: { status: 'error', reason: 'Aggregation rejected' } }, chartStatus: 'error', reason: /Aggregation rejected/ },
  { name: 'unsupported histogram', body: { rows: null, page: null, histogram: { status: 'unsupported', reason: 'Aggregate queries are unsupported' }, warnings: ['Histogram unavailable'] }, chartStatus: 'unsupported', reason: /Aggregate queries are unsupported/ },
]) {
  test(`${scenario.name} leaves successful rows and their warnings intact`, async (t) => {
    const { workspace, state, requests } = harness(t);
    workspace.runSearch();
    requests[0].respond(rowsResponse({ warnings: ['Rows remain complete'] }));
    await settle();
    const rows = state.rows, page = state.page, context = state.lastRequest, elapsed = state.elapsed;
    requests[1].respond(scenario.body, scenario.status);
    await settle();
    assert.equal(state.rows, rows);
    assert.equal(state.page, page);
    assert.equal(state.lastRequest, context);
    assert.equal(state.elapsed, elapsed);
    assert.equal(state.warning, 'Rows remain complete');
    assert.equal(state.error, '');
    assert.equal(state.busy, false);
    assert.equal(state.chartBusy, false);
    assert.equal(state.chart.status, scenario.chartStatus);
    assert.match(state.chart.reason || state.chart.message, scenario.reason);
    if (scenario.name === 'unsupported histogram')
      assert.match(state.chartWarning, /Histogram unavailable/);
  });
}
