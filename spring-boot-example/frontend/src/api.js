import { parse, stringify } from 'lossless-json';

export { stringify as jsonText };

export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

export function apiUrl(path) {
  // Relative to the page, so a Spring servlet context path also works.
  return new URL(path, new URL('./', window.location.href)).href;
}

export async function request(path, { body, signal, blob = false } = {}) {
  let response;
  try {
    response = await fetch(apiUrl(path), {
      method: body === undefined ? 'GET' : 'POST',
      headers: body === undefined ? { Accept: 'application/json' } : { 'Content-Type': 'application/json', Accept: blob ? 'text/csv' : 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (error) {
    if (error.name === 'AbortError') throw error;
    throw new ApiError('无法连接查询服务，请检查示例应用是否启动。', 0);
  }
  if (response.ok && blob) return response.blob();
  const raw = await response.text();
  let data;
  try { data = parse(raw); } catch {
    throw new ApiError(`服务返回了非 JSON 响应（HTTP ${response.status}），请检查接口地址。`, response.status);
  }
  if (!response.ok) {
    const message = data?.message || data?.detail || data?.error || response.statusText;
    throw new ApiError(`HTTP ${response.status} · ${message}`, response.status);
  }
  return data;
}

export function normalizeResponse(data) {
  if (!data || !Array.isArray(data.schema) || !Array.isArray(data.datarows)
      || data.schema.some(column => !column || typeof column.name !== 'string' || typeof column.type !== 'string')
      || data.datarows.some(row => !Array.isArray(row) || row.length !== data.schema.length)) {
    throw new Error('查询响应格式不符合 schema / datarows 契约。');
  }
  return {
    schema: data.schema,
    rows: data.datarows.map((values, index) => ({ index, values })),
    warnings: Array.isArray(data.warnings) ? data.warnings : [],
  };
}
