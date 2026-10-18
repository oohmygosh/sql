// Read-only live smoke test. Start the example and configure UI_URL before running.
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';

const base = process.env.UI_URL || 'http://127.0.0.1:8080/';
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1366, height: 768 }, acceptDownloads: true });
const errors = [];
page.on('pageerror', error => errors.push(error.message));
const report = [];
const output = new URL('../../build/ui-verification/', import.meta.url).pathname;
await mkdir(output, { recursive: true });

async function run(query) {
  await page.locator('#query-input').fill(query);
  const response = page.waitForResponse(response => response.url().endsWith('/query') && response.request().method() === 'POST');
  await page.locator('#search-button').click();
  const result = await response;
  await page.waitForFunction(() => !document.querySelector('#search-button').disabled);
  return result;
}
async function closeModal() { await page.locator('[data-action="close-modal"]').click(); }
try {
  await page.goto(base);
  await page.waitForFunction(() => document.querySelector('#elapsed').textContent.includes('ms'), { timeout: 60000 });
  for (const source of ['logs', 'analytics']) {
    await page.locator('#dataset-select').selectOption(source);
    const table = await page.locator('#table-input').inputValue();
    assert.ok(table);
    for (const language of ['sql', 'ppl']) {
      await page.locator(`[data-mode="${language}"]`).click();
      const query = language === 'sql' ? `SELECT id,timestamp,message FROM \`${table}\` LIMIT 3` : `source=\`${table}\` | fields id,timestamp,message | head 3`;
      const response = await run(query);
      assert.equal(response.status(), 200, await response.text());
      assert.equal(await page.locator('#result-count').textContent(), '3');
      assert.equal(await page.locator('thead th').count(), 4);
      report.push(`${source}/${language}: 3 real rows`);
    }
  }
  await page.locator('[data-row-id]').first().click();
  assert.equal(await page.locator('#detail-drawer').isVisible(), true);
  assert.equal(await page.locator('.detail-field').count(), 3);
  await page.locator('[data-action="close-detail"]').click();
  await page.locator('[data-view="json"]').click();
  assert.match(await page.locator('#json-content').textContent(), /"schema"/);
  await page.locator('[data-view="table"]').click();
  await page.locator('[data-field="0"]').click();
  await page.locator('[data-value-index="0"]').click();
  assert.equal(await page.locator('#result-count').textContent(), '1');
  await page.locator('[data-remove-filter="0"]').click();
  assert.equal(await page.locator('#result-count').textContent(), '3');
  report.push('record detail / JSON / local field filtering');

  await page.locator('[data-action="export"]').click();
  const syncDownload = page.waitForEvent('download');
  await page.locator('#sync-export').click();
  const file = await syncDownload;
  assert.equal(file.suggestedFilename(), 'result.csv');
  await file.saveAs(output + 'sync.csv');
  await page.locator('#async-export').click();
  await page.waitForFunction(() => document.querySelector('#export-task')?.textContent.includes('导出完成'), { timeout: 60000 });
  const asyncDownload = page.waitForEvent('download');
  await page.locator('#export-task a').click();
  await (await asyncDownload).saveAs(output + 'async.csv');
  await closeModal();
  report.push('real synchronous CSV / async submit-poll-download');

  const empty = await run('source=`log_event` | where 1=0 | fields id,timestamp,message | head 3');
  assert.equal(empty.status(), 200);
  assert.equal(await page.locator('#empty-state').isVisible(), true);
  assert.equal(await page.locator('#field-count').textContent(), '3');
  const bad = await run('source=`log_event` | nonexistent_command');
  assert.equal(bad.status(), 400);
  assert.equal(await page.locator('#query-error').isVisible(), true);
  assert.equal(await page.locator('[data-action="export"]').isDisabled(), true);
  report.push('real empty schema / backend syntax error with no stale export');

  await page.locator('[data-action="history"]').click();
  assert.ok(await page.locator('[data-load-history]').count() >= 4);
  await page.locator('[data-load-history="1"]').click();
  await page.locator('#search-button').click();
  await page.waitForFunction(() => document.querySelector('#elapsed').textContent.includes('ms'));
  await page.waitForFunction(() => !document.querySelector('#toast').classList.contains('visible'));
  await page.screenshot({ path: output + 'desktop.png', animations: 'disabled' });
  assert.equal(await page.evaluate(() => document.documentElement.scrollHeight <= innerHeight), true);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: output + 'mobile.png', animations: 'disabled' });
  assert.equal(await page.locator('.sidebar').isVisible(), false);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  assert.equal(await page.locator('#search-button').isVisible(), true);
  report.push('history / 1366x768 one-screen layout / 390px mobile');
  assert.deepEqual(errors, []);
  console.log(report.join('\n'));
  console.log('Browser errors: 0');
} finally { await browser.close(); }
