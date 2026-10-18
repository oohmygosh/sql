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
    return Response.json(await respond(url, options));
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
