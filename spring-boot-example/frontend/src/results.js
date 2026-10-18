import { isLosslessNumber, stringify } from 'lossless-json';

export function displayValue(value) {
  if (value === null) return 'null';
  if (typeof value === 'string') return value;
  return stringify(value) ?? '';
}

export function timestamp(value) {
  if (value === null || value === '') return NaN;
  if (typeof value === 'number' || isLosslessNumber(value)) {
    const number = Number(String(value));
    const time = number > 1e11 ? number : number > 1e8 ? number * 1000 : NaN;
    return Math.abs(time) <= 8640000000000000 ? time : NaN;
  }
  // Do not interpret arbitrary string columns (e.g. "42") as dates.
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}(?:[T ]|$)/.test(value)) return NaN;
  return Date.parse(value.replace(' ', 'T'));
}

export function defaultTimeField(schema) {
  const byName = schema.findIndex(column => ['@timestamp', 'timestamp', 'time_bucket', 'event_time'].includes(column.name));
  if (byName >= 0) return byName;
  const byType = schema.findIndex(column => /timestamp|datetime|^date$/.test(column.type));
  return byType;
}

export function filterRows(rows, filters, timeField, minutes, selection, now = Date.now()) {
  return rows.filter(row => {
    if (timeField >= 0 && (minutes || selection)) {
      const time = timestamp(row.values[timeField]);
      if (!Number.isFinite(time) || (minutes && (time < now - minutes * 60000 || time > now))) return false;
      if (selection && (time < selection.start || time >= selection.end)) return false;
    }
    return filters.every(filter => {
      const value = row.values[filter.column];
      if (filter.op === 'null') return value === null;
      if (filter.op === 'not-null') return value !== null;
      const text = displayValue(value);
      if (filter.op === 'contains') return value !== null && text.toLowerCase().includes(filter.value.toLowerCase());
      if (value === null) return filter.op === '!=';
      if (filter.op === '!=') return text !== filter.value;
      return text === filter.value;
    });
  });
}

export function histogram(rows, timeField, levelField, count = 48) {
  const dated = rows.map(row => ({ time: timestamp(row.values[timeField]), row })).filter(item => Number.isFinite(item.time));
  if (!dated.length) return null;
  let min = Infinity, max = -Infinity;
  for (const { time } of dated) { min = Math.min(min, time); max = Math.max(max, time); }
  const width = Math.max(1000, Math.ceil((max - min + 1) / count));
  const buckets = Array.from({ length: count }, (_, i) => ({ start: min + i * width, end: min + (i + 1) * width, INFO: 0, WARN: 0, ERROR: 0, OTHER: 0 }));
  for (const { time, row } of dated) {
    const raw = displayValue(row.values[levelField]).toUpperCase();
    const level = ['INFO', 'WARN', 'ERROR'].includes(raw) ? raw : 'OTHER';
    buckets[Math.min(count - 1, Math.floor((time - min) / width))][level]++;
  }
  return { buckets, start: min, end: min + count * width, omitted: rows.length - dated.length };
}

export function quoteIdentifier(value) {
  return '`' + value.replaceAll('`', '``') + '`';
}

export function queryTemplate(mode, table, preset = 'sample', timeField = 'timestamp') {
  if (!table.trim()) throw new Error('请先填写表名或索引。');
  const name = quoteIdentifier(table.trim());
  if (preset === 'count') return mode === 'sql' ? `SELECT count(*) AS total FROM ${name}` : `source=${name} | stats count() as total`;
  if (preset === 'distribution') return `source=${name} | stats count() as total by span(${quoteIdentifier(timeField)}, 1h) as time_bucket | head 100`;
  return mode === 'sql' ? `SELECT * FROM ${name} LIMIT 100` : `source=${name} | head 100`;
}
