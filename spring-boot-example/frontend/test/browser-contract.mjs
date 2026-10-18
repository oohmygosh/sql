// Contract fixtures exercise failures/races that cannot be reliably induced on live data sources.
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1366, height: 768 } });
const errors = [];
page.on('pageerror', error => errors.push(error.message));
const payloads = [];
let release;
const delayed = new Promise(resolve => { release = resolve; });
let finishDelayed;
const delayedFinished = new Promise(resolve => { finishDelayed = resolve; });
const fixture = '{"schema":[{"name":"timestamp","type":"timestamp"},{"name":"<img src=x onerror=alert(1)>","type":"long"},{"name":"value","type":"struct"}],"datarows":[["2026-10-18 10:00:00",9007199254740993,{"text":"<script>alert(1)</script>","price":18446744073709551615.123456789}]],"warnings":[{"type":"PARTIAL_RESULT","reason":"fixture warning"}]}';
await page.route('**/query', async route => {
  const body = route.request().postDataJSON(); payloads.push(body);
  if (body.query === 'slow query') await delayed;
  try {
    await route.fulfill({ status: body.query === 'bad query' ? 429 : 200, contentType: 'application/json', body: body.query === 'bad query' ? '{"message":"fixture resource limit"}' : fixture });
  } catch (error) { if (body.query !== 'slow query') throw error; }
  finally { if (body.query === 'slow query') finishDelayed(); }
});
try {
  await page.goto(process.env.UI_URL || 'http://127.0.0.1:8080/');
  await page.waitForFunction(() => document.querySelector('#elapsed').textContent.includes('ms') && document.querySelector('#result-count').textContent === '1');
  assert.equal(await page.locator('th img').count(), 0);
  assert.match(await page.locator('thead').textContent(), /<img/);
  assert.equal(await page.locator('#query-warning').isVisible(), true);
  await page.locator('[data-view="json"]').click();
  const json = await page.locator('#json-content').textContent();
  assert.match(json, /9007199254740993/);
  assert.match(json, /18446744073709551615\.123456789/);
  const query = 'WITH x AS (SELECT * FROM `custom-table`) SELECT * FROM x';
  await page.locator('#query-input').fill(query);
  await page.locator('#search-button').click();
  await page.waitForFunction(() => !document.querySelector('#search-button').disabled);
  assert.equal(payloads.at(-1).query, query);
  await page.locator('#query-input').fill('bad query');
  await page.locator('#search-button').click();
  await page.waitForFunction(() => !document.querySelector('#search-button').disabled);
  assert.match(await page.locator('#query-error').textContent(), /HTTP 429.*fixture resource limit/);
  assert.equal(await page.locator('#result-count').textContent(), '0');
  assert.equal(await page.locator('[data-action="export"]').isDisabled(), true);
  await page.locator('#query-input').fill('slow query');
  const pending = page.waitForRequest(request => request.url().endsWith('/query') && request.postDataJSON()?.query === 'slow query');
  await page.locator('#search-button').click();
  await pending;
  await page.locator('#dataset-select').selectOption('analytics');
  release();
  await delayedFinished;
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await page.waitForFunction(() => document.querySelector('#dataset-select').value === 'analytics' && !document.querySelector('#search-button').disabled);
  assert.equal(await page.locator('#result-count').textContent(), '0');
  assert.equal(await page.locator('#elapsed').textContent(), '等待查询');
  assert.deepEqual(errors, []);
  console.log('Contract fixtures passed: exact numbers / HTML escaping / warnings / verbatim queries / 429 / source-switch race');
} finally { release(); await browser.close(); }
