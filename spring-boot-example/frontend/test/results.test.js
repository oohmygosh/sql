import test from 'node:test';
import assert from 'node:assert/strict';
import { parse } from 'lossless-json';
import { normalizeResponse, jsonText } from '../src/api.js';
import { displayValue, filterRows, histogram, timestamp, queryTemplate } from '../src/results.js';

test('schema order, duplicate aliases, nulls and exact numbers survive table and JSON views', () => {
  const response = parse('{"schema":[{"name":"值","type":"long"},{"name":"值","type":"double"},{"name":"detail","type":"struct"}],"datarows":[[9007199254740993,18446744073709551615.123456789,{"tags":[null,"<script>"]}]]}');
  const result = normalizeResponse(response);
  assert.equal(displayValue(result.rows[0].values[0]), '9007199254740993');
  assert.equal(displayValue(result.rows[0].values[1]), '18446744073709551615.123456789');
  assert.match(jsonText(result.rows[0].values), /9007199254740993,18446744073709551615.123456789/);
  assert.equal(result.schema.length, 3);
  assert.deepEqual(normalizeResponse({ schema: response.schema, datarows: [] }).rows, []);
  assert.throws(() => normalizeResponse({ schema: response.schema, datarows: [[1]] }), /响应格式/);
});

test('local filters distinguish null from its string and constrain only received rows', () => {
  const rows = [null, 'null', 'ERROR', 'Info'].map((value, index) => ({ index, values: [value] }));
  assert.deepEqual(filterRows(rows, [{ column: 0, op: 'null', value: '' }], -1, 0, null).map(row => row.index), [0]);
  assert.deepEqual(filterRows(rows, [{ column: 0, op: 'contains', value: 'err' }], -1, 0, null).map(row => row.index), [2]);
  assert.equal(filterRows(rows, [], -1, 0, null).length, 4);
});

test('histogram includes final timestamp, accounts for non-log rows and rejects invalid dates', () => {
  const rows = ['2026-10-18T00:00:00Z', '2026-10-18T01:00:00Z', null, '42'].map((value, index) => ({ index, values: [value] }));
  const chart = histogram(rows, 0, -1);
  assert.equal(chart.omitted, 2);
  assert.equal(chart.buckets.reduce((sum, bucket) => sum + bucket.OTHER, 0), 2);
  assert.ok(chart.end > timestamp(rows[1].values[0]));
  assert.ok(Number.isNaN(timestamp(null)));
  assert.ok(Number.isNaN(timestamp(parse('2102658105230192640'))));
  const last = chart.buckets.at(-1);
  assert.deepEqual(filterRows(rows, [], 0, 0, last).map(row => row.index), [1]);
  assert.equal(histogram(rows.slice(2), 0, -1), null);
});

test('time controls use the current clock and exclude future timestamps', () => {
  const now = Date.parse('2026-10-18T01:00:00Z');
  const rows = [now - 60000, now - 3600000, now + 60000].map((value, index) => ({ index, values: [value] }));
  assert.deepEqual(filterRows(rows, [], 0, 15, null, now).map(row => row.index), [0]);
});

test('quick queries quote table identifiers and preserve limits', () => {
  assert.equal(queryTemplate('sql', 'logevent-*'), 'SELECT * FROM `logevent-*` LIMIT 100');
  assert.equal(queryTemplate('ppl', 'log_event'), 'source=`log_event` | head 100');
  assert.equal(queryTemplate('sql', 'a`b', 'count'), 'SELECT count(*) AS total FROM `a``b`');
  assert.throws(() => queryTemplate('sql', ' '), /表名/);
});
