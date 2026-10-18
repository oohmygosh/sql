import { isLosslessNumber, isSafeNumber } from 'lossless-json';
import { jsonText } from './api.js';
import { getLogLayout } from './log-results.js';

// Only an unchanged source projection proves that result names are source names.
// Keep this recognizer deliberately narrow; aliases and calculated results need
// source provenance from the server before they can offer automatic filters.
const identifier = '(?:`(?:[^`]|``)+`|"(?:[^"]|"")+"|[\\p{L}\\p{N}_@*?$-]+)';
const relation = `${identifier}(?:\\s*\\.\\s*${identifier})*`;
const directPpl = new RegExp(`^\\s*source\\s*=\\s*${relation}\\s*$`, 'iu');
const directSql = new RegExp(`^\\s*select\\s+\\*\\s+from\\s+${relation}\\s*;?\\s*$`, 'iu');

export function isDirectSourceQuery(query, language) {
  return typeof query === 'string' && (language?.toLowerCase() === 'ppl' ? directPpl.test(query)
    : language?.toLowerCase() === 'sql' && directSql.test(query));
}

export function quickValueFilter(field, value, exclude = false) {
  if (value === null) return { field, operator: exclude ? 'is_not_null' : 'is_null' };
  if (isLosslessNumber(value)) {
    if (!isSafeNumber(value.value)) throw new Error('此数值超出安全精度，请在查询语句中填写精确值。');
    value = Number(value.value);
  }
  if (!['string', 'number', 'boolean'].includes(typeof value)) throw new Error('对象和数组请展开记录查看，快捷筛选支持单个值。');
  if (typeof value === 'number' && (!Number.isFinite(value) || (Number.isInteger(value) && !Number.isSafeInteger(value)))) {
    throw new Error('此数值超出安全精度，请在查询语句中填写精确值。');
  }
  return { field, operator: exclude ? 'ne' : 'eq', value };
}

export function fieldValues(rows, column, limit = 10) {
  const values = new Map();
  for (const row of rows) {
    const value = row.values[column];
    if (value === undefined) continue;
    const key = jsonText(value);
    if (values.has(key)) values.get(key).count++;
    else values.set(key, { value, count: 1 });
  }
  return { total: values.size, values: [...values.values()].sort((a, b) => b.count - a.count).slice(0, limit) };
}

export function visibleColumnIndexes(schema, selection) {
  if (selection !== null) return selection.filter(index => Number.isInteger(index) && index >= 0 && index < schema.length);
  const layout = getLogLayout(schema);
  return layout ? [layout.timeIndex, layout.bodyIndex].filter(index => index >= 0) : schema.map((_, index) => index);
}

export function toggleColumn(schema, selection, index) {
  const columns = new Set(visibleColumnIndexes(schema, selection));
  if (columns.has(index)) { if (columns.size > 1) columns.delete(index); }
  else if (index >= 0 && index < schema.length) columns.add(index);
  return [...columns];
}
