import assert from 'node:assert/strict';
import { setMaxListeners } from 'node:events';
import { test } from 'node:test';
import { createQueryAssist } from '../src/query-assist.js';
import { reactive } from 'vue';

// The controller only needs these DOM operations; keep interaction tests dependency-free.
setMaxListeners(30);
class Element extends EventTarget {
  constructor(id = '') {
    super();
    this.id = id;
    this.children = [];
    this.dataset = {};
    this.attributes = new Map();
    this.value = '';
    this.textContent = '';
    this.selectionStart = 0;
    this.selectionEnd = 0;
    this.hidden = true;
  }
  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }
  getAttribute(name) {
    return this.attributes.get(name);
  }
  removeAttribute(name) {
    this.attributes.delete(name);
  }
  replaceChildren(...children) {
    this.children = children;
  }
  append(...children) {
    this.children.push(...children);
  }
  scrollIntoView() {}
  focus() {
    if (document.activeElement !== this) {
      document.activeElement = this;
      this.dispatchEvent(new Event('focus'));
    }
  }
  setRangeText(text, from, to) {
    this.value = this.value.slice(0, from) + text + this.value.slice(to);
    this.setSelectionRange(from + text.length, from + text.length);
  }
  setSelectionRange(from, to) {
    this.selectionStart = from;
    this.selectionEnd = to;
  }
}
const settle = () => new Promise((resolve) => setImmediate(resolve));
const debounce = () => new Promise((resolve) => setTimeout(resolve, 175));
function emit(target, name, properties = {}) {
  const event = new Event(name, { cancelable: true });
  Object.assign(event, properties);
  target.dispatchEvent(event);
  return event;
}
function candidate(
  label,
  body,
  {
    kind = 'keyword',
    detail = '',
    newText = `${label} `,
    from = body.caret,
    to = body.caret,
    caretOffset = null,
  } = {},
) {
  return { label, kind, detail, textEdit: { from, to, newText }, caretOffset };
}
function harness(t, { languages = ['ppl'], respond = () => ({ items: [] }) } = {}) {
  const previous = {
    document: globalThis.document,
    window: globalThis.window,
    fetch: globalThis.fetch,
  };
  globalThis.document = { activeElement: null, createElement: () => new Element() };
  globalThis.window = { location: { href: 'http://localhost/context/' } };
  const calls = [],
    context = reactive({ mode: 'ppl', source: 'logs' });
  globalThis.fetch = async (url, options) => {
    const body = options.body && JSON.parse(options.body);
    calls.push({ url, ...options, body });
    const result = body ? await respond(body, options.signal) : { languages };
    return new Response(
      JSON.stringify(
        body ? { documentVersion: body.documentVersion, incomplete: false, ...result } : result,
      ),
    );
  };
  const input = new Element();
  let changes = 0;
  const assist = createQueryAssist({
    getSnapshot: () => ({
      ...context,
      text: input.value,
      caret: input.selectionStart,
      end: input.selectionEnd,
    }),
    isFocused: () => document.activeElement === input,
    applyEdit(text, caret) {
      input.value = text;
      input.setSelectionRange(caret, caret);
      input.focus();
      changes++;
    },
  });
  // The transport controller is independent of DOM rendering. The component tests
  // separately exercise Vue's actual input, listbox and ARIA bindings.
  for (const [name, handler] of Object.entries(assist.handlers))
    input.addEventListener(name, handler);
  input.getAttribute = (name) =>
    name === 'aria-autocomplete'
      ? assist.supported.value
        ? 'list'
        : 'none'
      : input.attributes.get(name);
  const list = {
    get hidden() {
      return !assist.state.candidates.length;
    },
    get children() {
      return assist.state.candidates.map((item, index) => ({
        dataset: { label: item.label },
        children: [{ textContent: item.label }, { textContent: item.detail }],
        getAttribute: (name) =>
          name === 'aria-selected' ? String(index === assist.state.selected) : undefined,
      }));
    },
  };
  const status = {
    get textContent() {
      return assist.state.message;
    },
    get hidden() {
      return !assist.state.message;
    },
  };
  assist.contextChanged();
  t.after(() => {
    assist.dispose();
    Object.assign(globalThis, previous);
  });
  return {
    input,
    list,
    status,
    context,
    calls,
    assist,
    get changes() {
      return changes;
    },
    labels: () => list.children.map((option) => option.dataset.label),
    type(text, caret = text.length) {
      input.value = text;
      input.setSelectionRange(caret, caret);
      emit(input, 'input');
    },
    key(key, properties = {}) {
      return emit(input, 'keydown', { key, ...properties });
    },
    async manual() {
      emit(input, 'keydown', { key: ' ', code: 'Space', ctrlKey: true });
      await settle();
    },
    async choose(label, key = 'Tab') {
      const index = list.children.findIndex((option) => option.dataset.label === label);
      assert.notEqual(index, -1, `Missing ${label} at ${input.value}`);
      while (list.children[index].getAttribute('aria-selected') !== 'true') this.key('ArrowDown');
      assert.equal(this.key(key).defaultPrevented, true);
      await settle();
    },
  };
}

test('focus and accepted candidates continuously guide a query without inserting client-side spaces', async (t) => {
  const steps = new Map([
    ['', ['source', 'search', 'index']],
    ['source ', ['=']],
    ['source = ', ['logevent-*']],
    ['source = `logevent-*` ', ['|']],
    ['source = `logevent-*` | ', ['where', 'fields']],
    ['source = `logevent-*` | where ', ['status', 'message']],
    ['source = `logevent-*` | where status ', ['=']],
    ['source = `logevent-*` | where status = ', []],
    ['source = `logevent-*` | where status = 500 ', ['and', 'or', '|']],
    ['source = `logevent-*` | where status = 500 and ', ['status', 'message']],
  ]);
  const h = harness(t, {
    respond: (body) => ({
      items: (steps.get(body.text) || []).map((label) =>
        candidate(label, body, {
          newText: label === 'logevent-*' ? '`logevent-*` ' : `${label} `,
          detail: label === 'status' ? 'integer · logevent-*' : '继续编写查询',
        }),
      ),
    }),
  });
  h.input.focus();
  await debounce();
  assert.deepEqual(h.labels(), ['source', 'search', 'index']);
  for (const expected of [
    ['='],
    ['logevent-*'],
    ['|'],
    ['where', 'fields'],
    ['status', 'message'],
    ['='],
    [],
  ]) {
    const event = h.key('Tab');
    await settle();
    assert.equal(event.defaultPrevented, true);
    assert.deepEqual(h.labels(), expected);
  }
  assert.equal(h.input.value, 'source = `logevent-*` | where status = ');
  assert.equal(h.changes, 7);
  h.type(`${h.input.value}500 `);
  await debounce();
  assert.deepEqual(h.labels(), ['and', 'or', '|']);
  h.key('Enter');
  await settle();
  assert.deepEqual(h.labels(), ['status', 'message']);
  assert.equal(h.list.children[0].children[1].textContent, 'integer · logevent-*');
  assert.ok(h.calls.every((call) => call.url === 'http://localhost/context/ui/query/completions'));
  assert.ok(
    h.calls
      .filter((call) => call.body)
      .every((call) => call.body.language === 'ppl' && call.body.source === 'logs'),
  );
  assert.equal(h.calls.filter((call) => call.body?.trigger === 'accept').length, 8);
});

test('stale responses cannot overwrite a newer document or source even if abort is ignored', async (t) => {
  const pending = [];
  const h = harness(t, {
    respond: (body, signal) => new Promise((resolve) => pending.push({ body, signal, resolve })),
  });
  h.input.focus();
  await h.manual();
  h.type('source = b');
  await h.manual();
  h.context.source = 'other';
  h.assist.contextChanged();
  await h.manual();
  assert.equal(pending.length, 3);
  assert.equal(pending[0].signal.aborted, true);
  assert.equal(pending[1].signal.aborted, true);
  pending[2].resolve({ items: [candidate('new-table', pending[2].body)] });
  await settle();
  for (const request of pending.slice(0, 2))
    request.resolve({ items: [candidate('old-table', request.body)] });
  await settle();
  assert.deepEqual(h.labels(), ['new-table']);
});

test('server UTF-16 edits preserve suffixes and can place the caret inside function arguments', async (t) => {
  const original = 'source = `日志😀` | eval n = ab | fields n';
  const from = original.indexOf('ab'),
    to = from + 2;
  const h = harness(t, {
    respond: (body) => ({
      items: [
        candidate('abs', body, {
          kind: 'function',
          from,
          to,
          newText: 'abs()',
          caretOffset: 4,
        }),
      ],
    }),
  });
  h.input.focus();
  h.type(original, to);
  await h.manual();
  h.key('Tab');
  await settle();
  assert.equal(h.input.value, original.slice(0, from) + 'abs()' + original.slice(to));
  assert.equal(h.input.selectionStart, from + 4);
  assert.equal(h.calls.at(-1).body.caret, from + 4);
});

test('accepted edits keep their immediate continuation request when the browser emits select', async (t) => {
  const pending = [];
  const h = harness(t, {
    respond: (body, signal) =>
      body.text === ''
        ? { items: [candidate('source', body)] }
        : new Promise((resolve) => pending.push({ body, signal, resolve })),
  });
  h.input.focus();
  await h.manual();
  h.key('Tab');
  await settle();
  assert.equal(pending.length, 1);
  assert.equal(pending[0].body.trigger, 'accept');
  // setRangeText/setSelectionRange enqueue this event in real textareas.
  emit(h.input, 'select');
  await debounce();
  assert.equal(pending[0].signal.aborted, false);
  assert.equal(pending.length, 1);
  pending[0].resolve({ items: [candidate('=', pending[0].body)] });
  await settle();
  assert.deepEqual(h.labels(), ['=']);

  // A real caret change still invalidates the old position and requests the new one.
  h.input.setSelectionRange(3, 3);
  emit(h.input, 'select');
  await debounce();
  assert.equal(pending.length, 2);
  assert.equal(pending[1].body.caret, 3);
  assert.equal(pending[1].body.trigger, 'input');
  assert.equal(h.list.hidden, true);
  pending[1].resolve({ items: [candidate('source', pending[1].body, { from: 0, to: 6 })] });
  await settle();
  assert.deepEqual(h.labels(), ['source']);
});

test('continuous server edits traverse grouping functions, optional parameters, and another pipeline', async (t) => {
  const source = 'source = `logevent-*` ',
    stats = `${source}| stats `;
  const group = `${stats}count() as total by span(`;
  const bin = `${group}\`timestamp\`, 1h) as hour | bin \`total\``;
  const fields = `${bin} span = 10 | fields `;
  // These are transport fixtures, not a client grammar. Backend journey tests decide which
  // candidates exist; this exercises accepting their exact edits through the entire editor flow.
  const steps = [
    { text: '', label: 'source', insert: 'source ', alternatives: ['search', 'index'] },
    { text: 'source ', label: '=', insert: '= ' },
    { text: 'source = ', label: 'logevent-*', kind: 'table', insert: '`logevent-*` ' },
    { text: source, label: '|', insert: '| ' },
    { text: `${source}| `, label: 'stats', insert: 'stats ', alternatives: ['where', 'eval'] },
    { text: stats, label: 'count', kind: 'function', insert: 'count(' },
    { text: `${stats}count(`, label: ')', insert: ')' },
    { text: `${stats}count()`, label: 'as', insert: ' as ', alternatives: ['by', '|'] },
    { text: `${stats}count() as `, type: 'total ' },
    { text: `${stats}count() as total `, label: 'by', insert: 'by ', alternatives: ['|'] },
    {
      text: `${stats}count() as total by `,
      label: 'span',
      kind: 'function',
      insert: 'span(',
      alternatives: ['timestamp'],
    },
    { text: group, label: 'timestamp', kind: 'field', insert: '`timestamp`' },
    { text: `${group}\`timestamp\``, label: ',', insert: ', ' },
    {
      text: `${group}\`timestamp\`, `,
      label: '1h',
      kind: 'value',
      insert: '1h',
      alternatives: ['1m', '1d'],
    },
    { text: `${group}\`timestamp\`, 1h`, label: ')', insert: ')' },
    { text: `${group}\`timestamp\`, 1h)`, label: 'as', insert: ' as ', alternatives: ['|'] },
    { text: `${group}\`timestamp\`, 1h) as `, type: 'hour ' },
    { text: `${group}\`timestamp\`, 1h) as hour `, label: '|', insert: '| ' },
    {
      text: `${group}\`timestamp\`, 1h) as hour | `,
      label: 'bin',
      insert: 'bin ',
      alternatives: ['stats', 'fields'],
    },
    {
      text: `${group}\`timestamp\`, 1h) as hour | bin `,
      label: 'total',
      kind: 'field',
      insert: '`total`',
      alternatives: ['hour'],
    },
    { text: bin, label: 'span', kind: 'parameter', insert: ' span ', alternatives: ['bins', '|'] },
    { text: `${bin} span `, label: '=', insert: '= ' },
    { text: `${bin} span = `, label: '10', kind: 'value', insert: '10' },
    { text: `${bin} span = 10`, label: '|', insert: ' | ' },
    { text: `${bin} span = 10 | `, label: 'fields', insert: 'fields ' },
    { text: fields, label: 'hour', kind: 'field', insert: '`hour`', alternatives: ['total'] },
    { text: `${fields}\`hour\``, label: ',', insert: ', ', alternatives: ['|'] },
    { text: `${fields}\`hour\`, `, label: 'total', kind: 'field', insert: '`total`' },
    { text: `${fields}\`hour\`, \`total\`` },
  ];
  let nextResponse = 0;
  const h = harness(t, {
    respond: (body) => {
      const step = steps[nextResponse++];
      assert.ok(step, `Unexpected continuation: ${body.text}`);
      assert.equal(body.text, step.text);
      assert.equal(body.caret, step.text.length);
      return {
        items: step.label
          ? [
              ...(step.alternatives || []).map((label) => candidate(label, body)),
              candidate(step.label, body, { kind: step.kind, newText: step.insert }),
            ]
          : [],
      };
    },
  });
  h.input.focus();
  await debounce();
  for (const [index, step] of steps.slice(0, -1).entries()) {
    assert.equal(h.input.value, step.text);
    if (step.type) {
      h.type(step.text + step.type);
      await debounce();
    } else await h.choose(step.label, index % 2 ? 'Enter' : 'Tab');
    // Native selection notifications must not start a second request for an accepted edit.
    emit(h.input, 'select');
    await settle();
    assert.equal(h.input.value, steps[index + 1].text);
    assert.equal(h.input.selectionStart, h.input.value.length);
  }
  const requests = h.calls.filter((call) => call.body).map((call) => call.body);
  assert.equal(nextResponse, steps.length);
  assert.equal(requests.length, steps.length);
  assert.equal(h.changes, steps.filter((step) => step.label).length);
  for (let i = 1; i < requests.length; i++) {
    assert.equal(requests[i].trigger, steps[i - 1].type ? 'input' : 'accept');
    assert.ok(requests[i].documentVersion > requests[i - 1].documentVersion);
  }
  assert.equal(
    h.input.value,
    'source = `logevent-*` | stats count() as total by span(`timestamp`, 1h) as hour | bin `total` span = 10 | fields `hour`, `total`',
  );
});

test('nested function edits in the middle preserve UTF-16 suffixes and reuse closing parentheses', async (t) => {
  const prefix = 'source = `日志😀` | eval n = ',
    suffix = ' | fields n';
  const stages = [
    {
      expression: 'ro',
      caret: 2,
      label: 'round',
      insert: 'round()',
      from: 0,
      to: 2,
      caretOffset: 6,
    },
    { expression: 'round()', caret: 6, label: 'abs', insert: 'abs()', caretOffset: 4 },
    { expression: 'round(abs())', caret: 10, label: 'size', insert: '`size`' },
    { expression: 'round(abs(`size`))', caret: 16, label: ')', insert: ')', to: 17 },
    { expression: 'round(abs(`size`))', caret: 17, label: ',', insert: ', ' },
    { expression: 'round(abs(`size`), )', caret: 19, label: '2', insert: '2' },
    { expression: 'round(abs(`size`), 2)', caret: 20, label: ')', insert: ')', to: 21 },
    { expression: 'round(abs(`size`), 2)', caret: 21 },
  ];
  let nextResponse = 0;
  const h = harness(t, {
    respond: (body) => {
      const stage = stages[nextResponse++];
      assert.ok(stage, `Unexpected continuation: ${body.text}`);
      assert.equal(body.text, prefix + stage.expression + suffix);
      assert.equal(body.caret, prefix.length + stage.caret);
      return {
        items: stage.label
          ? [
              candidate(stage.label, body, {
                newText: stage.insert,
                from: prefix.length + (stage.from ?? stage.caret),
                to: prefix.length + (stage.to ?? stage.caret),
                caretOffset: stage.caretOffset,
              }),
            ]
          : [],
      };
    },
  });
  h.input.focus();
  h.type(prefix + stages[0].expression + suffix, prefix.length + stages[0].caret);
  await h.manual();
  for (const stage of stages.slice(0, -1)) {
    await h.choose(stage.label);
    emit(h.input, 'select');
    await settle();
    assert.ok(h.input.value.endsWith(suffix));
  }
  assert.equal(nextResponse, stages.length);
  assert.equal(h.input.value, prefix + stages.at(-1).expression + suffix);
  assert.equal(h.input.selectionStart, prefix.length + stages.at(-1).caret);
  assert.equal(h.input.selectionEnd, h.input.selectionStart);
  assert.equal(h.changes, stages.length - 1);
});

test('editing after an accepted suggestion rejects its late continuation before resuming the new flow', async (t) => {
  const pending = [];
  const h = harness(t, {
    respond: (body, signal) =>
      body.text === ''
        ? { items: [candidate('source', body)] }
        : new Promise((resolve) => pending.push({ body, signal, resolve })),
  });
  h.input.focus();
  await h.manual();
  await h.choose('source');
  assert.equal(pending[0].body.trigger, 'accept');
  h.type('index ');
  await debounce();
  assert.equal(pending[0].signal.aborted, true);
  pending[1].resolve({ items: [candidate('=', pending[1].body)] });
  await settle();
  pending[0].resolve({ items: [candidate('wrong-position', pending[0].body)] });
  await settle();
  assert.deepEqual(h.labels(), ['=']);
  await h.choose('=');
  assert.equal(pending[2].body.text, 'index = ');
  assert.equal(pending[2].body.trigger, 'accept');
  pending[2].resolve({
    items: [candidate('logevent-*', pending[2].body, { newText: '`logevent-*` ' })],
  });
  await settle();
  await h.choose('logevent-*');
  assert.equal(pending[3].body.text, 'index = `logevent-*` ');
  pending[3].resolve({ items: [candidate('|', pending[3].body)] });
  await settle();
  assert.deepEqual(h.labels(), ['|']);
});

test('capabilities disable SQL, and a future language requires no controller branch', async (t) => {
  const h = harness(t, {
    languages: ['ppl', 'future'],
    respond: (body) => ({ items: [candidate('start', body)] }),
  });
  h.input.focus();
  await h.manual();
  h.context.mode = 'sql';
  h.assist.contextChanged();
  await debounce();
  const count = h.calls.length;
  h.key(' ', { ctrlKey: true, code: 'Space' });
  await debounce();
  assert.equal(h.calls.length, count);
  assert.equal(h.input.getAttribute('aria-autocomplete'), 'none');
  assert.equal(h.list.hidden, true);
  h.context.mode = 'future';
  h.assist.contextChanged();
  await h.manual();
  assert.deepEqual(h.labels(), ['start']);
  assert.equal(h.calls.at(-1).body.language, 'future');
  assert.equal(h.input.getAttribute('aria-autocomplete'), 'list');
});

test('IME, dismissal, selection and execute shortcut do not apply or reopen a completion', async (t) => {
  const h = harness(t, {
    respond: (body) => ({ items: [candidate('source', body), candidate('index', body)] }),
  });
  h.input.focus();
  await h.manual();
  h.key('ArrowDown');
  assert.equal(h.list.children[1].getAttribute('aria-selected'), 'true');
  h.key('Escape');
  emit(h.input, 'click');
  await debounce();
  assert.equal(h.list.hidden, true);
  await h.manual();
  assert.equal(h.list.hidden, false);
  const execute = h.key('Enter', { metaKey: true });
  assert.equal(execute.defaultPrevented, false);
  assert.equal(h.input.value, '');
  assert.equal(h.list.hidden, true);
  emit(h.input, 'compositionstart');
  const count = h.calls.length;
  h.type('字');
  h.key('Enter', { isComposing: true });
  await debounce();
  assert.equal(h.calls.length, count);
  emit(h.input, 'compositionend');
  await debounce();
  assert.equal(h.calls.length, count + 1);
  h.input.setSelectionRange(0, 1);
  emit(h.input, 'select');
  assert.equal(h.list.hidden, true);
});

test('metadata messages keep usable candidates; request failures are visible and retryable', async (t) => {
  let attempt = 0;
  const h = harness(t, {
    respond: (body) => {
      if (++attempt === 1) throw new Error('network down');
      return { items: [candidate('|', body)], incomplete: true, message: '字段元数据暂不可用' };
    },
  });
  h.input.focus();
  await h.manual();
  assert.equal(h.list.hidden, true);
  assert.match(h.status.textContent, /补全暂不可用/);
  assert.equal(h.status.hidden, false);
  await h.manual();
  assert.deepEqual(h.labels(), ['|']);
  assert.equal(h.status.textContent, '字段元数据暂不可用');
});

test('out-of-range edits and mismatched response versions never modify the document', async (t) => {
  let attempt = 0;
  const h = harness(t, {
    respond: (body) =>
      ++attempt === 1
        ? { items: [candidate('invalid', body, { from: 0, to: 100 })] }
        : { documentVersion: body.documentVersion - 1, items: [candidate('stale', body)] },
  });
  h.input.focus();
  h.type('source ');
  await h.manual();
  assert.equal(h.list.hidden, true);
  assert.match(h.status.textContent, /替换范围无效/);
  await h.manual();
  assert.equal(h.list.hidden, true);
  assert.match(h.status.textContent, /版本不一致/);
  h.key('Tab');
  assert.equal(h.input.value, 'source ');
});

test('blur and disposal abort outstanding work and prevent late candidates', async (t) => {
  let finish, signal;
  const h = harness(t, {
    respond: (body, requestSignal) =>
      new Promise((resolve) => {
        signal = requestSignal;
        finish = () => resolve({ items: [candidate('late', body)] });
      }),
  });
  h.input.focus();
  await h.manual();
  document.activeElement = null;
  emit(h.input, 'blur');
  assert.equal(signal.aborted, true);
  finish();
  await settle();
  assert.equal(h.list.hidden, true);
  h.input.focus();
  await h.manual();
  h.assist.dispose();
  assert.equal(signal.aborted, true);
  finish();
  await settle();
  assert.equal(h.list.hidden, true);
});
