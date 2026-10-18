import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parse } from 'lossless-json';
import { isDirectSourceQuery, quickValueFilter, fieldValues, visibleColumnIndexes, toggleColumn } from '../src/fields.js';

test('only direct single-source results can supply source filter names', () => {
  for (const query of ['source=`logevent-*`', ' SOURCE = logs ', 'source = `日志``归档`', 'source = catalog.logs']) {
    assert.equal(isDirectSourceQuery(query, 'PPL'), true, query);
  }
  for (const query of ['SELECT * FROM logs;', 'select * from "log events"', 'select * from `archive`.`logs`']) {
    assert.equal(isDirectSourceQuery(query, 'SQL'), true, query);
  }
  for (const query of ['source=logs | eval level="ERROR"', 'source=logs | rename source as hostIp', 'source=logs | stats count()', 'source=logs,other', 'source=logs as other', 'source=logs | fields level']) {
    assert.equal(isDirectSourceQuery(query, 'ppl'), false, query);
  }
  for (const query of ['select count(*) as hostIp from logs', 'select other as level from logs', 'select * from a join b on a.id=b.id', 'select * from (select 1 as level)', 'select * from logs union select * from other']) {
    assert.equal(isDirectSourceQuery(query, 'sql'), false, query);
  }
  assert.equal(isDirectSourceQuery('source=logs', 'unknown'), false);
});

test('quick filters preserve string, boolean, numeric, empty and null semantics', () => {
  assert.deepEqual(quickValueFilter('hostIp', '10.0.0.1'), { field: 'hostIp', operator: 'eq', value: '10.0.0.1' });
  assert.deepEqual(quickValueFilter('level', ''), { field: 'level', operator: 'eq', value: '' });
  assert.deepEqual(quickValueFilter('parseSuccess', false, true), { field: 'parseSuccess', operator: 'ne', value: false });
  assert.deepEqual(quickValueFilter('size', parse('42')), { field: 'size', operator: 'eq', value: 42 });
  assert.deepEqual(quickValueFilter('size', parse('1.5')), { field: 'size', operator: 'eq', value: 1.5 });
  assert.deepEqual(quickValueFilter('level', null), { field: 'level', operator: 'is_null' });
  assert.deepEqual(quickValueFilter('level', null, true), { field: 'level', operator: 'is_not_null' });
  for (const value of [parse('9007199254740993'), parse('1.234567890123456789'), parse('1e400'), { nested: 'value' }, [], NaN, undefined]) {
    assert.throws(() => quickValueFilter('value', value));
  }
});

test('sample values count current rows without merging distinct JSON types or truncating numbers', () => {
  const rows = parse('[[null],[""],[false],[0],["0"],[false],[9007199254740993],[9007199254740992]]').map(values => ({ values }));
  const result = fieldValues(rows, 0, 3);
  assert.equal(result.total, 7);
  assert.deepEqual(result.values.map(item => item.count), [2, 1, 1]);
  assert.equal(result.values[0].value, false);
  assert.equal(fieldValues(rows, 0, 20).values.at(-2).value.toString(), '9007199254740993');
  assert.deepEqual(fieldValues([], 0), { total: 0, values: [] });
});

test('column selection addresses duplicate names by position and keeps at least one column', () => {
  const schema = [{ name: 'value' }, { name: 'value' }, { name: 'other' }];
  assert.deepEqual(visibleColumnIndexes(schema, null), [0, 1, 2]);
  assert.deepEqual(toggleColumn(schema, null, 1), [0, 2]);
  assert.deepEqual(toggleColumn(schema, [1], 1), [1]);
  assert.deepEqual(toggleColumn(schema, [1], 0), [1, 0]);
  assert.deepEqual(visibleColumnIndexes(schema, [1, 7]), [1]);
  const log = ['timestamp', 'message', 'hostIp', 'collectorMetadata', 'collectionMethod'].map(name => ({ name }));
  assert.deepEqual(visibleColumnIndexes(log, null), [0, 1]);
  assert.deepEqual(toggleColumn(log, null, 2), [0, 1, 2]);
  const unorderedLog = ['message', 'hostIp', 'collectorMetadata', 'collectionMethod', 'timestamp'].map(name => ({ name }));
  assert.deepEqual(toggleColumn(unorderedLog, null, 1), [4, 0, 1]);
});
