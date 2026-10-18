import { computed, shallowReactive } from 'vue';
import { request } from './api.js';

export const kindLabels = {
  keyword: '关键字',
  command: '命令',
  function: '函数',
  table: '表 / 索引',
  field: '字段',
  operator: '运算符',
  value: '值',
};

/** Reactive editor state; the server owns context, candidates and UTF-16 text edits. */
export function createQueryAssist({ getSnapshot, isFocused, applyEdit, onSelect = () => {} }) {
  const state = shallowReactive({ candidates: [], selected: 0, message: '', languages: null });
  let capabilitiesPromise,
    timer,
    version = 0,
    composing = false,
    disposed = false;
  let dismissedKey, completionKey, completionRequest;
  const capabilitiesRequest = new AbortController();
  const keyOf = (value) =>
    JSON.stringify([value.mode, value.source, value.text, value.caret, value.end]);
  const supported = computed(() => state.languages?.includes(getSnapshot().mode));
  function clearList() {
    state.candidates = [];
    state.selected = 0;
  }
  function invalidate() {
    version++;
    clearTimeout(timer);
    completionRequest?.abort();
    completionRequest = undefined;
    completionKey = undefined;
    clearList();
    state.message = '';
  }
  function reset() {
    invalidate();
    dismissedKey = keyOf(getSnapshot());
  }
  function loadCapabilities() {
    if (!capabilitiesPromise) {
      capabilitiesPromise = request('ui/query/completions', { signal: capabilitiesRequest.signal })
        .then((result) => {
          if (
            !Array.isArray(result.languages) ||
            result.languages.some((language) => typeof language !== 'string')
          )
            throw new Error('补全服务返回的语言能力无效。');
          if (!disposed) state.languages = result.languages;
        })
        .catch((error) => {
          capabilitiesPromise = undefined;
          throw error;
        });
    }
    return capabilitiesPromise;
  }
  function contextChanged() {
    invalidate();
    dismissedKey = undefined;
    loadCapabilities().catch(() => {});
    if (isFocused()) schedule();
  }
  function select(index) {
    state.selected = index;
    onSelect(index);
  }
  function normalizeItems(items, textLength) {
    if (!Array.isArray(items)) throw new Error('补全服务返回的候选无效。');
    return items.map((item) => {
      const edit = item?.textEdit,
        from = Number(edit?.from),
        to = Number(edit?.to);
      const caretOffset = item?.caretOffset == null ? null : Number(item.caretOffset);
      if (
        typeof item?.label !== 'string' ||
        typeof edit?.newText !== 'string' ||
        !Number.isInteger(from) ||
        !Number.isInteger(to) ||
        from < 0 ||
        to < from ||
        to > textLength ||
        (caretOffset !== null &&
          (!Number.isInteger(caretOffset) || caretOffset < 0 || caretOffset > edit.newText.length))
      )
        throw new Error('补全服务返回的替换范围无效。');
      return { ...item, textEdit: { ...edit, from, to }, caretOffset };
    });
  }
  function schedule(trigger = 'input') {
    const value = getSnapshot(),
      key = keyOf(value);
    invalidate();
    if (
      disposed ||
      composing ||
      (state.languages && !state.languages.includes(value.mode)) ||
      !value.source ||
      value.caret !== value.end ||
      !isFocused()
    )
      return;
    if (trigger !== 'manual' && key === dismissedKey) return;
    dismissedKey = undefined;
    completionKey = key;
    const current = version;
    const isCurrent = () =>
      !disposed && !composing && current === version && key === keyOf(getSnapshot()) && isFocused();
    const update = async () => {
      try {
        state.message = '正在获取补全建议…';
        await loadCapabilities();
        if (!isCurrent()) return;
        if (!state.languages.includes(value.mode)) {
          state.message = '';
          return;
        }
        completionRequest = new AbortController();
        const result = await request('ui/query/completions', {
          signal: completionRequest.signal,
          body: {
            language: value.mode,
            source: value.source,
            text: value.text,
            caret: value.caret,
            documentVersion: current,
            trigger,
          },
        });
        if (!isCurrent()) return;
        if (Number(result.documentVersion) !== current)
          throw new Error('补全响应与当前语句版本不一致。');
        state.candidates = normalizeItems(result.items, value.text.length);
        state.selected = 0;
        if (state.candidates.length) onSelect(0);
        state.message =
          result.message ||
          (result.incomplete
            ? '补全结果不完整，可继续输入缩小范围。'
            : trigger === 'manual' && !state.candidates.length
              ? '此位置暂无补全建议。'
              : '');
      } catch (error) {
        if (isCurrent() && error.name !== 'AbortError') {
          clearList();
          state.message = `补全暂不可用：${error.message} 按 Ctrl+Space 重试。`;
        }
      }
    };
    if (trigger === 'input') timer = setTimeout(update, 150);
    else update();
  }
  async function accept(index) {
    const item = state.candidates[index];
    if (!item) return;
    const snapshot = getSnapshot(),
      { from, to, newText } = item.textEdit;
    invalidate();
    const current = version;
    const pending = applyEdit(
      snapshot.text.slice(0, from) + newText + snapshot.text.slice(to),
      from + (item.caretOffset ?? newText.length),
    );
    if (pending) await pending;
    if (disposed || current !== version) return;
    dismissedKey = undefined;
    schedule('accept');
  }
  function keydown(event) {
    if (
      (state.languages && !state.languages.includes(getSnapshot().mode)) ||
      composing ||
      event.isComposing
    )
      return;
    if (
      event.ctrlKey &&
      !event.altKey &&
      !event.metaKey &&
      (event.code === 'Space' || event.key === ' ')
    ) {
      event.preventDefault();
      event.stopPropagation();
      schedule('manual');
      return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
      reset();
      return;
    }
    if (event.key === 'Escape') {
      reset();
      return;
    }
    if (
      !state.candidates.length ||
      event.ctrlKey ||
      event.metaKey ||
      event.altKey ||
      event.shiftKey
    )
      return;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      event.stopPropagation();
      select(
        (state.selected + (event.key === 'ArrowDown' ? 1 : -1) + state.candidates.length) %
          state.candidates.length,
      );
    } else if (event.key === 'Enter' || event.key === 'Tab') {
      event.preventDefault();
      event.stopPropagation();
      accept(state.selected);
    }
  }
  const handlers = {
    input(event) {
      if (!event.isComposing && !composing) {
        dismissedKey = undefined;
        schedule();
      }
    },
    keydown,
    keyup(event) {
      if (['ArrowLeft', 'ArrowRight', 'Home', 'End', 'PageUp', 'PageDown'].includes(event.key))
        schedule();
    },
    focus() {
      dismissedKey = undefined;
      schedule();
    },
    click() {
      schedule();
    },
    select() {
      const value = getSnapshot();
      if (value.caret !== value.end) reset();
      else if (keyOf(value) !== completionKey) schedule();
    },
    blur: reset,
    compositionstart() {
      composing = true;
      reset();
    },
    compositionend() {
      composing = false;
      dismissedKey = undefined;
      schedule();
    },
  };
  return {
    state,
    supported,
    handlers,
    accept,
    reset,
    contextChanged,
    dispose() {
      reset();
      disposed = true;
      capabilitiesRequest.abort();
    },
  };
}
