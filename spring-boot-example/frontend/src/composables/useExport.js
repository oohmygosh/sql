import { computed, shallowReactive } from 'vue';
import { apiUrl, request } from '../api.js';

const exportKey = 'lens.search.export.v2';
export function useExport({ persist, toast }) {
  const state = shallowReactive({
    task: null,
    submitting: false,
    downloading: false,
    message: '',
    downloadUrl: '',
  });
  const active = computed(() => !!state.task && ['PENDING', 'RUNNING'].includes(state.task.state));
  const downloadHref = computed(() =>
    state.task?.state === 'SUCCEEDED'
      ? apiUrl(`search/export/tasks/${encodeURIComponent(state.task.id)}/file`)
      : '',
  );
  let timer,
    version = 0,
    disposed = false;
  const controllers = new Set();
  async function call(path, options = {}) {
    const controller = new AbortController();
    controllers.add(controller);
    try {
      return await request(path, { ...options, signal: controller.signal });
    } finally {
      controllers.delete(controller);
    }
  }
  function pause(announce = true) {
    clearTimeout(timer);
    version++;
    if (announce) toast('已暂停刷新，服务端导出继续执行。');
  }
  async function poll() {
    clearTimeout(timer);
    const task = state.task;
    if (!task || disposed) return;
    const current = ++version;
    try {
      const status = await call(`search/export/tasks/${encodeURIComponent(task.id)}`);
      if (disposed || state.task?.id !== task.id || current !== version) return;
      state.task = {
        ...task,
        ...status,
        exportedRows: Number(status.exportedRows),
        fileSizeBytes: Number(status.fileSizeBytes),
        pollError: null,
      };
      persist(exportKey, state.task);
      if (active.value) timer = setTimeout(poll, 1500);
      else toast(`导出${status.state === 'SUCCEEDED' ? '完成，可下载 CSV' : '已结束，请查看状态'}`);
    } catch (error) {
      if (disposed || current !== version || error.name === 'AbortError') return;
      if ([403, 404].includes(error.status)) {
        state.task = {
          ...state.task,
          state: error.status === 404 ? 'EXPIRED' : 'UNAVAILABLE',
          error: error.status === 403 ? '无权访问此导出任务，请切换账号或重新创建导出。' : null,
          pollError: null,
        };
        persist(exportKey, state.task);
      } else {
        state.task = { ...state.task, pollError: error.message };
      }
    }
  }
  async function submit(context) {
    if (state.submitting || active.value || disposed) return;
    state.submitting = true;
    state.message = '';
    try {
      const task = await call('search/export/tasks', { body: context });
      if (disposed) return;
      if (typeof task.id !== 'string') throw new Error('导出响应缺少任务 ID。');
      state.task = { id: task.id, state: 'PENDING', query: context.query, source: context.source };
      persist(exportKey, state.task);
      poll();
    } catch (error) {
      if (!disposed && error.name !== 'AbortError') state.message = error.message;
    } finally {
      state.submitting = false;
    }
  }
  async function download(context) {
    state.downloading = true;
    state.message = '正在生成 CSV…';
    try {
      const blob = await call('search/export', { body: context, blob: true });
      if (disposed) return false;
      if (state.downloadUrl) URL.revokeObjectURL(state.downloadUrl);
      state.downloadUrl = URL.createObjectURL(blob);
      state.message = 'CSV 下载已开始。';
      return true;
    } catch (error) {
      if (!disposed && error.name !== 'AbortError') state.message = error.message;
      return false;
    } finally {
      state.downloading = false;
    }
  }
  function restore() {
    try {
      const task = JSON.parse(localStorage.getItem(exportKey));
      if (
        task &&
        typeof task.id === 'string' &&
        ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'UNAVAILABLE'].includes(task.state)
      ) {
        state.task = task;
        poll();
      }
    } catch {
      /* Storage may contain an older format. */
    }
  }
  function dispose() {
    disposed = true;
    pause(false);
    controllers.forEach((controller) => controller.abort());
    if (state.downloadUrl) URL.revokeObjectURL(state.downloadUrl);
  }
  return { state, active, downloadHref, pause, poll, submit, download, restore, dispose };
}
