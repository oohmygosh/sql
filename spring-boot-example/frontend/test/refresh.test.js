import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { JSDOM } from 'jsdom';

const settle = () => new Promise((resolve) => setImmediate(resolve));

test('Vue workspace preserves query, exploration, completion, history and export behavior', async (t) => {
  const root = new URL('../', import.meta.url);
  const dom = new JSDOM(await readFile(new URL('index.html', root), 'utf8'), {
    url: 'http://localhost/',
  });
  const { window } = dom;
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
  window.HTMLDialogElement.prototype.showModal = function () {
    this.open = true;
  };
  window.HTMLDialogElement.prototype.close = function () {
    this.open = false;
  };
  window.HTMLElement.prototype.scrollIntoView = () => {};
  const globals = {
    window,
    document: window.document,
    localStorage: window.localStorage,
    HTMLElement: window.HTMLElement,
    Element: window.Element,
    Document: window.Document,
    Node: window.Node,
    SVGElement: window.SVGElement,
    AbortController: window.AbortController,
    getComputedStyle: window.getComputedStyle,
    requestAnimationFrame: () => {},
  };
  const saved = Object.fromEntries(
    [...Object.keys(globals), 'fetch'].map((key) => [key, globalThis[key]]),
  );
  Object.assign(globalThis, globals);
  const searches = [],
    exportRequests = [],
    downloads = [],
    completions = [];
  let exportComplete = false;
  window.HTMLAnchorElement.prototype.click = function () {
    downloads.push({ href: this.href, download: this.download });
  };
  const originalCreateUrl = URL.createObjectURL,
    originalRevokeUrl = URL.revokeObjectURL;
  URL.createObjectURL = () => 'blob:test-download';
  URL.revokeObjectURL = () => {};
  t.after(() => {
    URL.createObjectURL = originalCreateUrl;
    URL.revokeObjectURL = originalRevokeUrl;
  });
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/ui/config'))
      return Response.json({
        defaultSource: 'logs',
        sources: [
          { name: 'logs', type: 'elasticsearch', table: 'logs' },
          { name: 'analytics', type: 'clickhouse', table: 'analytics' },
        ],
      });
    if (url.includes('/ui/query/completions')) {
      if (!options.body) return Response.json({ languages: ['ppl'] });
      const body = JSON.parse(options.body);
      completions.push(body);
      return Response.json({
        documentVersion: body.documentVersion,
        items: body.text
          ? []
          : [
              {
                label: 'source',
                detail: '读取数据源',
                kind: 'keyword',
                textEdit: { from: 0, to: 0, newText: 'source = ' },
              },
            ],
      });
    }
    if (url.includes('/search/export')) {
      const body = options.body && JSON.parse(options.body);
      exportRequests.push({ url, body });
      if (url.endsWith('/search/export/tasks')) return Response.json({ id: 'export-1' });
      if (url.endsWith('/search/export'))
        return new Response('message\noriginal\n', { headers: { 'Content-Type': 'text/csv' } });
      return Response.json({
        id: 'export-1',
        state: exportComplete ? 'SUCCEEDED' : 'RUNNING',
        exportedRows: 42,
        fileSizeBytes: 1024,
      });
    }
    assert.ok(url.endsWith('/search'));
    return new Promise((resolve) => searches.push({ body: JSON.parse(options.body), resolve }));
  };
  const $ = (selector) => window.document.querySelector(selector);
  let app;
  t.after(() => {
    app?.unmount();
    window.close();
    Object.assign(globalThis, saved);
  });
  const [{ build }, { default: vue }] = await Promise.all([
    import('vite'),
    import('@vitejs/plugin-vue'),
  ]);
  const bundle = await build({
    configFile: false,
    plugins: [vue()],
    logLevel: 'silent',
    build: {
      write: false,
      minify: false,
      lib: { entry: new URL('src/App.vue', root).pathname, formats: ['es'] },
      rollupOptions: { external: ['vue'], output: { paths: { vue: import.meta.resolve('vue') } } },
    },
  });
  const code = (Array.isArray(bundle) ? bundle[0] : bundle).output.find(
    (item) => item.type === 'chunk' && item.isEntry,
  ).code;
  const { default: App } = await import(
    `data:text/javascript;base64,${Buffer.from(code + '\n//# sourceURL=lens-vue-under-test.js').toString('base64')}`
  );
  const { createApp, nextTick } = await import('vue');
  app = createApp(App);
  app.mount($('#app'));
  await settle();
  function reply(index, { page = 1, value = 'original', empty = false, logs = false } = {}) {
    searches[index].resolve(
      Response.json({
        rows: logs
          ? {
              schema: [
                { name: 'message', type: 'string' },
                { name: 'extracted', type: 'struct' },
                { name: 'collectorMetadata', type: 'struct' },
                { name: 'collectionMethod', type: 'string' },
              ],
              datarows: [
                [value, { service: 'test' }, {}, 'file'],
                ['another log', {}, {}, 'file'],
              ],
            }
          : { schema: [{ name: 'message', type: 'string' }], datarows: empty ? [] : [[value]] },
        page: { number: page, size: searches[index].body.page.size, hasNext: true },
        resolvedTimeRange: { from: '2026-10-17T00:00:00Z', to: '2026-10-18T00:00:00Z' },
        histogram: {
          status: 'ok',
          interval: '1d',
          buckets: [{ from: '2026-10-17T00:00:00Z', to: '2026-10-18T00:00:00Z', count: 10 }],
        },
      }),
    );
  }
  assert.equal($('#page-size').value, '20');
  assert.equal($('#page-size option[value="auto"]'), null);
  assert.deepEqual(searches[0].body.page, { number: 1, size: 20 });
  reply(0);
  await settle();
  const row = $('#results-rows tr'),
    heading = $('thead tr'),
    bar = $('#histogram button');
  const column = $('[data-column-index="0"]');
  $('#table-view').scrollTop = 100;
  $('[data-action="refresh"]').click();
  await nextTick();
  assert.equal($('#results-rows tr'), row);
  assert.equal($('#table-view').hidden, false);
  assert.equal($('.results-card').getAttribute('aria-busy'), 'true');
  assert.match($('#page-summary').textContent, /显示上次结果/);
  reply(1, { value: 'updated' });
  await settle();
  assert.equal($('#results-rows tr'), row);
  assert.equal($('thead tr'), heading);
  assert.equal($('#histogram button'), bar);
  assert.equal($('[data-column-index="0"]'), column);
  assert.match(row.textContent, /updated/);
  assert.equal($('#table-view').scrollTop, 100);

  // Continuations reuse the frozen range and never rebuild the histogram.
  $('#next-page').click();
  await nextTick();
  assert.equal(searches[2].body.page.number, 2);
  assert.deepEqual(searches[2].body.include, ['rows']);
  reply(2, { page: 2 });
  await settle();
  assert.equal($('#histogram button'), bar);
  assert.equal($('#table-view').scrollTop, 0);
  assert.match($('#page-summary').textContent, /21–21/);
  $('#query-input').value += ' | fields message';
  $('#query-input').dispatchEvent(new window.Event('input'));
  await nextTick();
  assert.equal($('#results-rows tr'), row);
  assert.equal($('#page-number').textContent, '2');
  assert.match($('#page-summary').textContent, /条件已修改/);
  assert.equal($('#next-page').disabled, true);
  $('#page-size').value = '50';
  $('#page-size').dispatchEvent(new window.Event('change'));
  await nextTick();
  assert.equal($('#page-number').textContent, '2');

  $('[data-action="refresh"]').click();
  await nextTick();
  searches[3].resolve(Response.json({ message: 'test failure' }, { status: 500 }));
  await settle();
  assert.equal($('#results-rows tr'), row);
  assert.match($('#query-error').textContent, /test failure/);
  assert.match($('#elapsed').textContent, /保留上次结果/);
  $('[data-action="refresh"]').click();
  await nextTick();
  $('#cancel-query').click();
  await nextTick();
  reply(4, { value: 'cancelled response' });
  await settle();
  assert.match(row.textContent, /original/);
  assert.equal($('.results-card').getAttribute('aria-busy'), 'false');
  $('[data-action="refresh"]').click();
  await nextTick();
  reply(5, { empty: true });
  await settle();
  assert.equal($('#results-rows').children.length, 0);
  assert.equal($('#empty-state').hidden, false);

  // Refresh keeps matching expanded records, but closes a record that changed.
  $('[data-action="refresh"]').click();
  await nextTick();
  reply(6, { logs: true });
  await settle();
  const secondLog = $('[data-row-id="1"]');
  $('[data-expand-row="0"]').click();
  await nextTick();
  assert.equal($('[data-row-id="1"]'), secondLog);
  assert.ok($('#extracted-row-0'));
  $('[data-action="refresh"]').click();
  await nextTick();
  reply(7, { logs: true });
  await settle();
  assert.ok($('#extracted-row-0'));
  assert.equal($('[data-row-id="1"]'), secondLog);
  $('[data-action="refresh"]').click();
  await nextTick();
  reply(8, { logs: true, value: 'changed record' });
  await settle();
  assert.equal($('#extracted-row-0'), null);
  function input(selector, value, event = 'input') {
    $(selector).value = value;
    $(selector).dispatchEvent(new window.Event(event, { bubbles: true }));
  }
  async function finishSearch(options) {
    reply(searches.length - 1, options);
    await settle();
  }
  async function closeModal() {
    $('#modal [aria-label="关闭弹窗"]').click();
    await nextTick();
  }

  await t.test('manual page sizes reset pagination and persist on subsequent pages', async () => {
    const histogram = $('#histogram button');
    $('#next-page').click();
    await nextTick();
    await finishSearch({ page: 2, logs: true });
    assert.equal($('#page-number').textContent, '2');

    for (const size of [8, 50]) {
      const count = searches.length;
      input('#page-size', String(size), 'change');
      await nextTick();
      assert.equal(searches.length, count + 1);
      assert.deepEqual(searches.at(-1).body.page, { number: 1, size });
      assert.deepEqual(searches.at(-1).body.include, ['rows']);
      assert.equal(searches.at(-1).body.timeRange.from, '2026-10-17T00:00:00Z');
      await finishSearch({ logs: true });
      assert.equal($('#page-size').value, String(size));
      assert.equal($('#page-number').textContent, '1');
      assert.equal($('#histogram button'), histogram);

      $('#next-page').click();
      await nextTick();
      assert.equal(searches.length, count + 2);
      assert.deepEqual(searches.at(-1).body.page, { number: 2, size });
      assert.deepEqual(searches.at(-1).body.include, ['rows']);
      await finishSearch({ page: 2, logs: true });
      assert.equal($('#page-size').value, String(size));
      assert.equal($('#page-number').textContent, '2');
      assert.match($('#page-summary').textContent, new RegExp(`${size + 1}–${size + 2}`));
      assert.equal($('#histogram button'), histogram);
    }
  });

  await t.test(
    'Vue models keep language/source drafts and templates without implicit execution',
    async () => {
      const count = searches.length;
      input('#query-input', 'source=`logs` | fields message');
      await nextTick();
      $('[data-mode="sql"]').click();
      await nextTick();
      assert.equal($('#query-input').value, 'SELECT * FROM `logs`');
      input('#query-input', 'SELECT message FROM `logs`');
      await nextTick();
      $('[data-mode="ppl"]').click();
      await nextTick();
      assert.equal($('#query-input').value, 'source=`logs` | fields message');
      input('#dataset-select', 'analytics', 'change');
      await nextTick();
      assert.equal($('#query-input').value, 'source=`analytics`');
      input('#dataset-select', 'logs', 'change');
      await nextTick();
      assert.equal($('#query-input').value, 'source=`logs` | fields message');
      $('[data-preset="count"]').click();
      await nextTick();
      assert.match($('#query-input').value, /stats count/);
      assert.equal(searches.length, count);
      $('[data-preset="sample"]').click();
      await nextTick();
      $('#search-form').dispatchEvent(
        new window.Event('submit', { bubbles: true, cancelable: true }),
      );
      await finishSearch({ logs: true });
    },
  );

  await t.test(
    'field values, typed quick filters, selected columns and JSON remain connected',
    async () => {
      $('[data-field-values="0"]').click();
      await nextTick();
      assert.match($('#field-values-0').textContent, /original/);
      $('#field-values-0 .field-value-action').click();
      await nextTick();
      assert.deepEqual(searches.at(-1).body.filters, [
        { field: 'message', operator: 'eq', value: 'original' },
      ]);
      assert.equal(searches.at(-1).body.page.number, 1);
      await finishSearch({ logs: true });
      $('[data-column-index="1"]').click();
      await nextTick();
      assert.match($('thead').textContent, /extracted/);
      $('[data-view="json"]').click();
      await nextTick();
      assert.equal($('#json-view').hidden, false);
      assert.match($('#json-content').textContent, /collectorMetadata/);
      $('[aria-label="恢复默认列"]').click();
      await nextTick();
      $('[data-view="table"]').click();
      await nextTick();
      assert.ok($('table.log-table'));
      $('[data-remove-filter="0"]').click();
      await nextTick();
      await finishSearch({ logs: true });
    },
  );

  await t.test('filter dialog validates values and preserves typed null operators', async () => {
    $('.add-filter').click();
    await nextTick();
    assert.equal($('#modal').open, true);
    input('#filter-field', 'level');
    input('#filter-value-type', 'number', 'change');
    input('#filter-value', 'oops');
    await nextTick();
    $('#filter-form').dispatchEvent(
      new window.Event('submit', { bubbles: true, cancelable: true }),
    );
    await nextTick();
    assert.match($('#filter-error').textContent, /有效数字/);
    input('#filter-op', 'is_null', 'change');
    await nextTick();
    assert.equal($('#filter-value').disabled, true);
    $('#filter-form').dispatchEvent(
      new window.Event('submit', { bubbles: true, cancelable: true }),
    );
    await nextTick();
    assert.deepEqual(searches.at(-1).body.filters, [{ field: 'level', operator: 'is_null' }]);
    assert.equal($('#modal').open, false);
    await finishSearch({ logs: true });
  });

  await t.test(
    'history restores the complete successful context and awaits manual execution',
    async () => {
      const count = searches.length;
      input('#query-input', 'different draft');
      await nextTick();
      $('[aria-label="查询历史"]').click();
      await nextTick();
      assert.equal($('#query-history-panel').hidden, false);
      $('.history-query').click();
      await nextTick();
      assert.equal($('#query-input').value, 'source=`logs`');
      assert.match($('#filter-chips').textContent, /is_null/);
      assert.equal($('#query-history-panel').hidden, true);
      assert.equal(searches.length, count);
      assert.ok(JSON.parse(localStorage.getItem('lens.search.history.v3')).length > 0);
    },
  );

  await t.test('custom time inputs and histogram drilldown submit their exact bounds', async () => {
    input('#time-select', 'custom', 'change');
    await nextTick();
    input('#time-from', '2026-10-17T00:00:00.123');
    input('#time-to', '2026-10-18T00:00:00.456');
    await nextTick();
    $('#search-form').dispatchEvent(
      new window.Event('submit', { bubbles: true, cancelable: true }),
    );
    assert.equal(searches.at(-1).body.timeRange.from, '2026-10-17T00:00:00.123');
    assert.equal(searches.at(-1).body.timeRange.timeZone, 'Asia/Shanghai');
    await finishSearch({ logs: true });
    $('#histogram button').click();
    await nextTick();
    assert.equal(searches.at(-1).body.timeRange.from, '2026-10-17T00:00:00.000Z');
    assert.equal(searches.at(-1).body.timeRange.to, '2026-10-18T00:00:00.000Z');
    await finishSearch({ logs: true });
    $('[aria-label="收起趋势图"]').click();
    await nextTick();
    assert.equal($('#chart-area').hidden, true);
    $('[aria-label="展开趋势图"]').click();
    await nextTick();
    assert.equal($('#chart-area').hidden, false);
  });

  await t.test(
    'Vue completion list and ARIA state follow input, keyboard and server text edits',
    async () => {
      $('[aria-label="清空查询"]').click();
      await nextTick();
      await new Promise((resolve) => setTimeout(resolve, 180));
      $('#query-input').dispatchEvent(
        new window.KeyboardEvent('keydown', {
          key: ' ',
          code: 'Space',
          ctrlKey: true,
          bubbles: true,
          cancelable: true,
        }),
      );
      await settle();
      assert.equal($('#ppl-completions').hidden, false);
      assert.equal($('#query-input').getAttribute('aria-expanded'), 'true');
      assert.equal($('#ppl-completions [role="option"]').textContent, 'source读取数据源');
      $('#query-input').dispatchEvent(
        new window.KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }),
      );
      await settle();
      assert.equal($('#query-input').value, 'source = ');
      assert.equal($('#query-input').selectionStart, 9);
      assert.equal(completions.at(-1).trigger, 'accept');
      $('#query-input').dispatchEvent(
        new window.KeyboardEvent('keydown', {
          key: 'Enter',
          metaKey: true,
          bubbles: true,
          cancelable: true,
        }),
      );
      assert.equal(searches.at(-1).body.query, 'source = '.trim());
      await finishSearch({ logs: true });
    },
  );

  await t.test(
    'exports keep a frozen query, download CSV and reactively update resumable task progress',
    async () => {
      $('[data-action="export"]').click();
      await nextTick();
      assert.equal($('#modal').open, true);
      const context = JSON.parse(JSON.stringify(searches.at(-1).body));
      $('#sync-export').click();
      await settle();
      assert.deepEqual(downloads.at(-1), { href: 'blob:test-download', download: 'result.csv' });
      assert.equal(exportRequests.at(-1).body.query, context.query);
      assert.equal(exportRequests.at(-1).body.timeRange.from, '2026-10-17T00:00:00Z');
      assert.equal(exportRequests.at(-1).body.page, undefined);
      $('#async-export').click();
      await settle();
      assert.match($('#export-task').textContent, /正在导出/);
      assert.equal($('#async-export').disabled, true);
      assert.ok(localStorage.getItem('lens.search.export.v2'));
      $('#pause-export').click();
      await nextTick();
      exportComplete = true;
      $('#resume-export').click();
      await settle();
      assert.match($('#export-task').textContent, /导出完成/);
      assert.match($('#export-task a').href, /search\/export\/tasks\/export-1\/file$/);
      await closeModal();
      $('#export-status-button').click();
      await settle();
      assert.match($('#export-task').textContent, /导出完成/);
      await closeModal();
    },
  );

  await t.test(
    'duplicate columns, nested values, exact large numbers and HTML text survive details and JSON',
    async () => {
      $('[data-action="refresh"]').click();
      await nextTick();
      searches
        .at(-1)
        .resolve(
          new Response(
            '{"rows":{"schema":[{"name":"alias","type":"long"},{"name":"alias","type":"struct"}],"datarows":[[9223372036854775807,{"value":"<img src=x onerror=alert(1)>"}]]},"page":{"number":1,"size":20,"hasNext":false},"resolvedTimeRange":{"from":"2026-10-17T00:00:00Z","to":"2026-10-18T00:00:00Z"},"histogram":{"status":"unsupported","reason":"aggregate"}}',
          ),
        );
      await settle();
      assert.equal($('thead').textContent.match(/alias/g).length, 2);
      assert.match($('#json-content').textContent, /9223372036854775807/);
      assert.equal($('#results-rows img'), null);
      $('#results-rows tr').click();
      await nextTick();
      assert.equal($('#detail-drawer').open, true);
      assert.equal($('#detail-content').querySelectorAll('.detail-field').length, 2);
      assert.match($('#detail-content').textContent, /9223372036854775807/);
      $('[aria-label="关闭详情"]').click();
      await nextTick();
      $('[aria-label="快捷键与帮助"]').click();
      await nextTick();
      assert.match($('#modal').textContent, /Ctrl Space/);
      await closeModal();
    },
  );
});
