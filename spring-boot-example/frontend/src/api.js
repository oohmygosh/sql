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
  if (!data || typeof data !== 'object') throw new Error('搜索响应格式无效。');
  let rows;
  if (data.rows != null) {
    const result = data.rows;
    if (!Array.isArray(result?.schema) || !Array.isArray(result?.datarows)
        || result.schema.some(column => !column || typeof column.name !== 'string' || typeof column.type !== 'string')
        || result.datarows.some(row => !Array.isArray(row) || row.length !== result.schema.length)) {
      throw new Error('查询响应格式不符合 rows.schema / datarows 契约。');
    }
    if (!data.page || !Number.isInteger(Number(data.page.number)) || Number(data.page.number) < 1
        || !Number.isInteger(Number(data.page.size)) || Number(data.page.size) < 1 || typeof data.page.hasNext !== 'boolean') {
      throw new Error('搜索响应缺少有效的后台分页信息。');
    }
    rows = { schema: result.schema, rows: result.datarows.map((values, index) => ({ index, values })) };
  }
  if (data.histogram?.status === 'ok' && (!Array.isArray(data.histogram.buckets)
      || data.histogram.buckets.some(bucket => !Number.isFinite(Date.parse(bucket.from))
        || !Number.isFinite(Date.parse(bucket.to)) || Date.parse(bucket.to) <= Date.parse(bucket.from)
        || !Number.isFinite(Number(bucket.count)) || Number(bucket.count) < 0))) {
    throw new Error('时间分布响应缺少有效的桶边界或计数。');
  }
  return { rows, page: data.page && { number: Number(data.page.number), size: Number(data.page.size), hasNext: data.page.hasNext },
    histogram: data.histogram, resolvedTimeRange: data.resolvedTimeRange,
    warnings: Array.isArray(data.warnings) ? data.warnings : [], errors: data.errors };
}
