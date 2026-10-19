import assert from 'node:assert/strict';
import { test } from 'node:test';
import { useExport } from '../src/composables/useExport.js';

const settle = () => new Promise(resolve => setImmediate(resolve));
function harness(t, respond) {
  const previous = { window: globalThis.window, localStorage: globalThis.localStorage, fetch: globalThis.fetch };
  const storage = new Map(), persisted = [], messages = [], calls = [];
  globalThis.window = { location: { href: 'http://localhost/workspace/' } };
  globalThis.localStorage = { getItem: key => storage.get(key) };
  globalThis.fetch = async (url, options) => {
    calls.push({ url, ...options });
    const response = await respond(url, options);
    return response instanceof Response ? response : Response.json(response);
  };
  const manager = useExport({ persist(key, value) { persisted.push({ key, value }); }, toast(message) { messages.push(message); } });
  t.after(() => { manager.dispose(); Object.assign(globalThis, previous); });
  return { manager, storage, persisted, messages, calls };
}

test('stored export tasks resume polling and expose a context-relative completed download', async t => {
  const h = harness(t, () => ({ id: 'restored/task', state: 'SUCCEEDED', exportedRows: 12, fileSizeBytes: 128 }));
  h.storage.set('lens.search.export.v2', JSON.stringify({ id: 'restored/task', state: 'RUNNING', source: 'logs', query: 'source=logs' }));
  h.manager.restore(); await settle();
  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].url, 'http://localhost/workspace/search/export/tasks/restored%2Ftask');
  assert.equal(h.manager.state.task.query, 'source=logs');
  assert.equal(h.manager.state.task.exportedRows, 12);
  assert.equal(h.manager.active.value, false);
  assert.equal(h.manager.downloadHref.value, 'http://localhost/workspace/search/export/tasks/restored%2Ftask/file');
  assert.equal(h.persisted.at(-1).value.state, 'SUCCEEDED');
});

test('missing stored export tasks expire without becoming failed exports', async t => {
  const h = harness(t, () => Response.json({ error: 'Export not found' }, { status: 404 }));
  let expectedCalls = 0;
  for (const state of ['SUCCEEDED', 'RUNNING', 'FAILED', 'EXPIRED']) {
    h.storage.set('lens.search.export.v2', JSON.stringify({
      id: 'expired-task', state, source: 'logs', query: 'source=logs', exportedRows: 42,
      error: state === 'FAILED' ? '任务已过期、不可访问或服务已重启，请重新创建。' : null,
    }));
    h.manager.restore(); await settle();
    assert.equal(h.calls.length, ++expectedCalls);
    assert.equal(h.manager.state.task.state, 'EXPIRED');
    assert.equal(h.manager.state.task.query, 'source=logs');
    assert.equal(h.manager.state.task.exportedRows, 42);
    assert.equal(h.manager.state.task.error, null);
    assert.equal(h.manager.state.task.pollError, null);
    assert.equal(h.manager.active.value, false);
    assert.equal(h.manager.downloadHref.value, '');
    assert.equal(h.persisted.at(-1).value.state, 'EXPIRED');
  }
});

test('inaccessible export tasks do not masquerade as expired files or export failures', async t => {
  const h = harness(t, () => Response.json({ error: 'Export belongs to another user' }, { status: 403 }));
  h.storage.set('lens.search.export.v2', JSON.stringify({ id: 'private-task', state: 'RUNNING' }));
  h.manager.restore(); await settle();
  assert.equal(h.manager.state.task.state, 'UNAVAILABLE');
  assert.match(h.manager.state.task.error, /无权访问/);
  assert.equal(h.manager.state.task.pollError, null);
  assert.equal(h.manager.active.value, false);
  assert.equal(h.manager.downloadHref.value, '');
  assert.equal(h.persisted.at(-1).value.state, 'UNAVAILABLE');
});

test('actual export failures retain the server error', async t => {
  const h = harness(t, () => ({ id: 'failed-task', state: 'FAILED', error: 'CSV size limit exceeded' }));
  h.manager.state.task = { id: 'failed-task', state: 'RUNNING' };
  await h.manager.poll();
  assert.equal(h.manager.state.task.state, 'FAILED');
  assert.equal(h.manager.state.task.error, 'CSV size limit exceeded');
  assert.equal(h.manager.state.task.pollError, null);
  assert.equal(h.manager.downloadHref.value, '');
});

test('temporary status errors preserve the export state for retry', async t => {
  const h = harness(t, () => Response.json({ error: 'Service unavailable' }, { status: 503 }));
  h.manager.state.task = { id: 'running-task', state: 'RUNNING' };
  await h.manager.poll();
  assert.equal(h.manager.state.task.state, 'RUNNING');
  assert.match(h.manager.state.task.pollError, /HTTP 503/);
  assert.equal(h.persisted.length, 0);
});

test('pausing ignores an in-flight status, and disposal aborts requests without reopening polling', async t => {
  const pending = [];
  const h = harness(t, (url, options) => new Promise(resolve => pending.push({ resolve, signal: options.signal })));
  h.manager.state.task = { id: 'task-1', state: 'RUNNING' };
  h.manager.poll(); h.manager.pause();
  pending[0].resolve({ id: 'task-1', state: 'SUCCEEDED' }); await settle();
  assert.equal(h.manager.state.task.state, 'RUNNING');
  h.manager.poll(); h.manager.dispose();
  assert.equal(pending[1].signal.aborted, true);
  pending[1].resolve({ id: 'task-1', state: 'SUCCEEDED' }); await settle();
  assert.equal(h.manager.state.task.state, 'RUNNING');
  assert.equal(h.persisted.length, 0);
});
