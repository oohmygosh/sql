import { request } from './api.js';
import { createPplCompletion } from './ppl-completion.js';

/** Owns editor assistance independently of the running search. */
export function attachPplAssist({ input, list, status, getContext, onChange }) {
  let enginePromise, timer, version = 0, composing = false, disposed = false;
  let candidates = [], selected = 0, dismissedKey;
  const grammarRequest = new AbortController();
  const kindLabels = { keyword: '语法', function: '函数', table: '表', field: '源字段', operator: '运算符' };
  const snapshot = () => {
    const context = getContext();
    return { ...context, text: input.value, caret: input.selectionStart, end: input.selectionEnd };
  };
  const keyOf = value => JSON.stringify([value.mode, value.source, value.text, value.caret, value.end]);
  const message = text => { status.textContent = text; status.hidden = !text; };

  function clearList() {
    candidates = []; selected = 0; list.replaceChildren(); list.hidden = true;
    input.setAttribute('aria-expanded', 'false'); input.removeAttribute('aria-activedescendant');
  }
  function reset() {
    version++; clearTimeout(timer);
    clearList(); message(''); dismissedKey = keyOf(snapshot());
  }
  function loadEngine() {
    if (!enginePromise) {
      enginePromise = request('ui/ppl/grammar', { signal: grammarRequest.signal })
        .then(createPplCompletion).catch(error => {
          enginePromise = undefined;
          throw error;
        });
    }
    return enginePromise;
  }
  function contextChanged() {
    reset();
    const ppl = getContext().mode === 'ppl';
    input.setAttribute('aria-autocomplete', ppl ? 'list' : 'none');
    input.placeholder = ppl ? '输入 PPL 查询 · Ctrl+Space 补全' : '输入 SQL 查询';
  }
  function select(index) {
    selected = index;
    [...list.children].forEach((option, i) => option.setAttribute('aria-selected', String(i === index)));
    const option = list.children[index];
    input.setAttribute('aria-activedescendant', option.id);
    option.scrollIntoView({ block: 'nearest' });
  }
  function render(items) {
    candidates = items;
    const options = items.map((item, index) => {
      const option = document.createElement('button');
      option.type = 'button'; option.id = `ppl-candidate-${index}`;
      option.setAttribute('role', 'option'); option.setAttribute('aria-label', item.label);
      option.dataset.index = index; option.dataset.label = item.label; option.dataset.kind = item.kind;
      const label = document.createElement('span'); label.textContent = item.label;
      const kind = document.createElement('small'); kind.textContent = kindLabels[item.kind] || '语法';
      option.append(label, kind); return option;
    });
    list.replaceChildren(...options); list.hidden = !items.length;
    input.setAttribute('aria-expanded', String(items.length > 0));
    if (items.length) select(0);
  }
  function schedule(manual = false) {
    const value = snapshot(), key = keyOf(value);
    if (disposed || composing || value.mode !== 'ppl' || value.caret !== value.end || document.activeElement !== input) return;
    if (!manual && key === dismissedKey) return;
    dismissedKey = undefined; clearTimeout(timer); clearList(); message('');
    const current = ++version;
    const isCurrent = () => !disposed && !composing && current === version
      && key === keyOf(snapshot()) && document.activeElement === input;
    const update = async () => {
      try {
        if (!enginePromise) message('正在加载 PPL 提示…');
        const engine = await loadEngine();
        if (!isCurrent()) return;
        const items = engine.complete(value.text, value.caret, value.config);
        if (!isCurrent()) return;
        render(items); message(manual && !items.length ? '此位置暂无 PPL 补全建议。' : '');
      } catch (error) {
        if (isCurrent() && error.name !== 'AbortError') {
          clearList(); message('PPL 提示暂不可用，按 Ctrl+Space 重试。');
        }
      }
    };
    if (manual) update(); else timer = setTimeout(update, 150);
  }
  function accept(index) {
    const item = candidates[index]; if (!item) return;
    input.setRangeText(item.insertText, item.from, item.to, 'end');
    reset(); input.focus(); onChange();
    dismissedKey = undefined; schedule();
  }
  function keydown(event) {
    if (getContext().mode !== 'ppl' || composing || event.isComposing) return;
    if (event.ctrlKey && !event.altKey && !event.metaKey && (event.code === 'Space' || event.key === ' ')) {
      event.preventDefault(); event.stopPropagation(); schedule(true); return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') { reset(); return; }
    if (event.key === 'Escape') { reset(); return; }
    if (list.hidden || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault(); event.stopPropagation();
      select((selected + (event.key === 'ArrowDown' ? 1 : -1) + candidates.length) % candidates.length);
    } else if (event.key === 'Enter' || event.key === 'Tab') {
      event.preventDefault(); event.stopPropagation(); accept(selected);
    }
  }
  const events = new AbortController();
  const listen = (target, name, handler) => target.addEventListener(name, handler, { signal: events.signal });
  listen(input, 'input', event => { if (!event.isComposing && !composing) { dismissedKey = undefined; schedule(); } });
  listen(input, 'keydown', keydown);
  listen(input, 'keyup', event => { if (['ArrowLeft', 'ArrowRight', 'Home', 'End', 'PageUp', 'PageDown'].includes(event.key)) schedule(); });
  listen(input, 'click', () => schedule());
  listen(input, 'select', () => { if (input.selectionStart !== input.selectionEnd) reset(); else schedule(); });
  listen(input, 'blur', reset);
  listen(input, 'compositionstart', () => { composing = true; reset(); });
  listen(input, 'compositionend', () => { composing = false; dismissedKey = undefined; schedule(); });
  listen(list, 'mousedown', event => event.preventDefault());
  listen(list, 'click', event => {
    const option = event.target.closest('[role="option"]');
    if (option && list.contains(option)) accept(Number(option.dataset.index));
  });
  input.setAttribute('aria-controls', list.id);
  contextChanged();
  return {
    reset, contextChanged,
    dispose() { reset(); disposed = true; grammarRequest.abort(); events.abort(); },
  };
}
