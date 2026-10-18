import { stringify } from 'lossless-json';

export function displayValue(value) {
  if (value === null) return 'null';
  if (typeof value === 'string') return value;
  return stringify(value) ?? '';
}
export function quoteIdentifier(value) {
  return '`' + value.replaceAll('`', '``') + '`';
}
export function queryTemplate(mode, table, preset = 'sample', timeField = 'timestamp') {
  if (!table.trim()) throw new Error('此数据源未配置默认表，请直接在语句中填写表名。');
  const name = quoteIdentifier(table.trim());
  if (preset === 'count') return mode === 'sql' ? `SELECT count(*) AS total FROM ${name}` : `source=${name} | stats count() as total`;
  if (preset === 'distribution') return `source=${name} | stats count() as total by span(${quoteIdentifier(timeField)}, 1h) as time_bucket`;
  return mode === 'sql' ? `SELECT * FROM ${name}` : `source=${name}`;
}
