import { test, expect } from '@playwright/test';

let source, table, field;
const quote = (value) => '`' + value.replaceAll('`', '``') + '`';
const options = (page) => page.getByRole('listbox').getByRole('option');

async function choose(page, label, key) {
  const option = page.locator('[role="option"]').and(page.locator(`[data-label=${JSON.stringify(label)}]`));
  await expect(option).toBeVisible();
  if (key) {
    await page.locator('#query-input').press('Control+Space');
    await expect(option).toBeVisible();
    const labels = await options(page).evaluateAll((nodes) => nodes.map((node) => node.dataset.label));
    const index = labels.indexOf(label);
    for (let i = 0; i < index; i++) await page.locator('#query-input').press('ArrowDown');
    await expect(option).toHaveAttribute('aria-selected', 'true');
    await page.locator('#query-input').press(key);
  } else await option.click();
}

async function edit(page, text, caret = text.length) {
  const response = page.waitForResponse((result) => {
    if (!result.url().endsWith('/ui/query/completions') || result.request().method() !== 'POST') return false;
    const snapshot = result.request().postDataJSON();
    return snapshot.text === text && snapshot.caret === caret;
  });
  const input = page.locator('#query-input');
  await input.fill(text);
  await input.evaluate((node, position) => node.setSelectionRange(position, position), caret);
  await input.press('Control+Space');
  return (await response).json();
}

test.beforeAll(async ({ request }) => {
  const capabilities = await request.get('/ui/query/completions');
  expect(capabilities.ok(), 'Start the updated Spring Boot backend before E2E').toBeTruthy();
  expect((await capabilities.json()).languages).toEqual(expect.arrayContaining(['ppl', 'sql']));
  const config = await (await request.get('/ui/config')).json();
  source = process.env.COMPLETION_E2E_SOURCE || config.defaultSource;
  const catalog = await request.post('/ui/query/completions', {
    data: { language: 'sql', source, text: 'SELECT * FROM ', caret: 14, documentVersion: 1 },
  });
  expect(catalog.ok()).toBeTruthy();
  const catalogResult = await catalog.json();
  const tables = catalogResult.items.filter((item) => item.kind === 'table');
  const configured = process.env.COMPLETION_E2E_TABLE || config.sources.find((item) => item.name === source)?.table;
  table = tables.find((item) => item.label === configured) || (process.env.COMPLETION_E2E_TABLE ? undefined : tables[0]);
  expect(table, `No live tables: ${catalogResult.message}`).toBeTruthy();
  // Use the server's exact quoted reference, preserving literal dots and namespaces.
  table.reference = table.textEdit.newText.trim();
  const text = `SELECT * FROM ${table.reference} WHERE `;
  const schemaResult = await (await request.post('/ui/query/completions', {
    data: { language: 'sql', source, text, caret: text.length, documentVersion: 2 },
  })).json();
  field = schemaResult.items.find((item) => item.kind === 'field' && item.label === 'message')
    || schemaResult.items.find((item) => item.kind === 'field' && !item.label.includes('.'));
  expect(field, `No live fields: ${schemaResult.message}`).toBeTruthy();
});

test.beforeEach(async ({ page }) => {
  const failures = [];
  page.on('pageerror', (error) => failures.push(error.message));
  page.on('console', (message) => { if (message.type() === 'error') failures.push(message.text()); });
  await page.goto('/');
  await page.locator('#dataset-select').selectOption(source);
  await page.getByRole('tab', { name: 'SQL', exact: true }).click();
  await expect(page.locator('#query-input')).toHaveAttribute('aria-autocomplete', 'list');
  page.testErrors = failures;
});

test.afterEach(async ({ page }) => {
  expect(page.testErrors).toEqual([]);
});

test('SQL accepts a continuous query and executes against the real backend', async ({ page }) => {
  await edit(page, '');
  await choose(page, 'SELECT', 'Enter');
  await page.locator('#query-input').pressSequentially('co');
  await choose(page, 'COUNT');
  await choose(page, '*');
  await choose(page, ')');
  await choose(page, 'AS');
  await page.locator('#query-input').pressSequentially('total ');
  await choose(page, 'FROM');
  await choose(page, table.label);
  await expect(page.locator('#query-input')).toHaveValue(`SELECT COUNT( * ) AS total FROM ${table.reference} `);
  await choose(page, 'LIMIT');
  await page.locator('#query-input').pressSequentially('1');
  const response = page.waitForResponse((r) => r.url().endsWith('/search') && r.request().method() === 'POST');
  await page.locator('#query-input').press('Control+Enter');
  const result = await response;
  expect(result.ok(), await result.text()).toBeTruthy();
  await expect(page.locator('#query-error')).toBeHidden();
  await expect(page.locator('#results-rows tr')).toHaveCount(1);
});

test('projection replacement preserves the later FROM and UTF-16 suffix', async ({ page }) => {
  const prefix = field.label.slice(0, 2);
  const text = `SELECT '😀', ${field.label} FROM ${table.reference}`;
  const caret = `SELECT '😀', ${prefix}`.length;
  await edit(page, text, caret);
  await choose(page, field.label);
  await expect(page.locator('#query-input')).toHaveValue(`SELECT '😀', ${field.textEdit.newText} FROM ${table.reference}`);
});

test('SQL alias fields and function insertion use actual server edits', async ({ page }) => {
  await edit(page, `SELECT l. FROM ${table.reference} l`, 'SELECT l.'.length);
  await choose(page, `l.${field.label}`);
  await expect(page.locator('#query-input')).toHaveValue(`SELECT ${quote('l')}.${field.textEdit.newText} FROM ${table.reference} l`);
  await edit(page, `SELECT co(*) FROM ${table.reference}`, 'SELECT co'.length);
  await choose(page, 'COUNT');
  await expect(page.locator('#query-input')).toHaveValue(`SELECT COUNT(*) FROM ${table.reference}`);
});

test('Enter and Tab accept fields, Escape dismisses, and PPL still completes after switching', async ({ page }) => {
  await edit(page, `SELECT * FROM ${table.reference} WHERE ${field.label.slice(0, 2)}`);
  await choose(page, field.label, 'Tab');
  await expect(page.locator('#query-input')).toHaveValue(`SELECT * FROM ${table.reference} WHERE ${field.textEdit.newText}`);
  await expect(page.getByRole('listbox')).toBeVisible();
  await page.locator('#query-input').press('Escape');
  await expect(page.getByRole('listbox')).toBeHidden();
  await page.getByRole('tab', { name: 'PPL', exact: true }).click();
  await edit(page, `source=${table.reference} | fields `);
  await choose(page, field.label);
  await expect(page.locator('#query-input')).toHaveValue(`source=${table.reference} | fields ${field.textEdit.newText}`);
  await page.getByRole('tab', { name: 'SQL', exact: true }).click();
  await edit(page, 'sel');
  await choose(page, 'SELECT');
  await expect(page.locator('#query-input')).toHaveValue('SELECT ');
});

test('JOIN and derived-table fields stay qualified and result aliases appear in ORDER BY', async ({ page }) => {
  await edit(page, `SELECT * FROM ${table.reference} l JOIN ${table.reference} r ON r.${field.label.slice(0, 2)}`);
  await choose(page, `r.${field.label}`);
  await expect(page.locator('#query-input')).toHaveValue(`SELECT * FROM ${table.reference} l JOIN ${table.reference} r ON ${quote('r')}.${field.textEdit.newText}`);
  const derived = `SELECT d.res FROM (SELECT ${field.textEdit.newText} AS result FROM ${table.reference}) d`;
  await edit(page, derived, 'SELECT d.res'.length);
  await choose(page, 'd.result');
  await expect(page.locator('#query-input')).toHaveValue(`SELECT ${quote('d')}.${quote('result')} FROM (SELECT ${field.textEdit.newText} AS result FROM ${table.reference}) d`);
  await edit(page, `SELECT ${field.textEdit.newText} AS result FROM ${table.reference} ORDER BY res`);
  await choose(page, 'result');
  await expect(page.locator('#query-input')).toHaveValue(`SELECT ${field.textEdit.newText} AS result FROM ${table.reference} ORDER BY ${quote('result')}`);
});

test('a real metadata lookup failure keeps syntax suggestions and shows its cause', async ({ page }) => {
  await edit(page, 'SELECT * FROM `completion_e2e_nonexistent_table` WHERE ');
  await expect(page.locator('#ppl-assist-status')).toContainText('无法加载表或字段');
  await expect(page.locator('[role="option"][data-label="NOT"]')).toBeVisible();
});


test('an early JOIN ON excludes later tables while retaining current aliases', async ({ page }) => {
  const prefix = `SELECT * FROM ${table.reference} l JOIN ${table.reference} r ON `;
  const suffix = ` IS NOT NULL JOIN ${table.reference} later ON l.${field.textEdit.newText}=later.${field.textEdit.newText}`;
  const unavailable = await edit(page, prefix + `later.${field.label.slice(0, 2)}` + suffix,
    (prefix + `later.${field.label.slice(0, 2)}`).length);
  expect(unavailable.items.filter((item) => item.kind === 'field')).toEqual([]);
  await expect(page.getByRole('listbox')).toBeHidden();
  await edit(page, prefix + `l.${field.label.slice(0, 2)}` + suffix,
    (prefix + `l.${field.label.slice(0, 2)}`).length);
  await choose(page, `l.${field.label}`);
  await expect(page.locator('#query-input')).toHaveValue(prefix + `${quote('l')}.${field.textEdit.newText}` + suffix);
});

test('nested ORDER BY cannot expose a SELECT alias to the outer WHERE', async ({ page }) => {
  const head = `SELECT ${field.textEdit.newText} AS completion_alias FROM ${table.reference}`;
  const subquery = `SELECT ${field.textEdit.newText} FROM ${table.reference} ORDER BY ${field.textEdit.newText}`;
  const unavailable = await edit(page, `${head} WHERE ${field.textEdit.newText} IN (${subquery}) AND completion_al`);
  expect(unavailable.items.filter((item) => item.kind === 'field')).toEqual([]);
  await expect(page.getByRole('listbox')).toBeHidden();
  await edit(page, `${head} WHERE ${field.textEdit.newText} IN (${subquery}) ORDER BY completion_al`);
  await choose(page, 'completion_alias');
});

test('an unaliased computed projection keeps its exact source name', async ({ page }) => {
  await edit(page, `SELECT d. FROM (SELECT COUNT( * ) FROM ${table.reference}) d`, 'SELECT d.'.length);
  await choose(page, 'd.COUNT( * )');
  await expect(page.locator('#query-input')).toHaveValue(`SELECT ${quote('d')}.${quote('COUNT( * )')} FROM (SELECT COUNT( * ) FROM ${table.reference}) d`);
});

test('correlated references see outer aliases while derived tables stay isolated', async ({ page }) => {
  const prefix = `SELECT * FROM ${table.reference} l WHERE EXISTS (SELECT * FROM ${table.reference} r WHERE l.`;
  await edit(page, prefix + ' IS NOT NULL)', prefix.length);
  await choose(page, `l.${field.label}`);
  await expect(page.locator('#query-input')).toHaveValue(prefix.slice(0, -2) + `${quote('l')}.${field.textEdit.newText} IS NOT NULL)`);
  const isolated = `SELECT * FROM ${table.reference} l JOIN (SELECT * FROM ${table.reference} r WHERE l.`;
  const result = await edit(page, isolated + ') d ON TRUE', isolated.length);
  expect(result.items.filter((item) => item.kind === 'field')).toEqual([]);
  await expect(page.getByRole('listbox')).toBeHidden();
});

test('qualified names with spaces and line breaks preserve the following FROM', async ({ page }) => {
  for (const qualifier of ['l . ', 'l\n.\t']) {
    const head = `SELECT ${qualifier}${field.label.slice(0, 2)}`;
    await edit(page, `${head}${field.label.slice(2)} FROM ${table.reference} l`, head.length);
    await choose(page, `l.${field.label}`);
    await expect(page.locator('#query-input')).toHaveValue(`SELECT ${quote('l')}.${field.textEdit.newText} FROM ${table.reference} l`);
  }
});


test('VARCHAR menus share the PPL type restrictions and guide the next value', async ({ page }) => {
  expect(field.detail.toLowerCase()).toMatch(/varchar|string|text|keyword|char/);
  const forbidden = ['+', '-', '*', '/', '%', '>', '>=', '<', '<=', 'BETWEEN', '!'];
  const sql = `SELECT * FROM ${table.reference} WHERE ${field.textEdit.newText} `;
  const result = await edit(page, sql);
  expect(result.items.map((item) => item.label)).toEqual(expect.arrayContaining(['=', 'LIKE', 'IS']));
  expect(result.items.some((item) => forbidden.includes(item.label))).toBe(false);
  for (const label of forbidden) await expect(page.locator(`[role="option"][data-label=${JSON.stringify(label)}]`)).toHaveCount(0);
  await choose(page, 'LIKE');
  await expect(page.locator('#ppl-assist-status')).toContainText('字符串');
  await expect(page.getByRole('listbox')).toBeHidden();
  await page.locator('#query-input').pressSequentially("'alpha'");
  await expect(page.locator('[role="option"][data-label="AND"]')).toBeVisible();
  await expect(page.locator('[role="option"][data-label="LIKE"]')).toHaveCount(0);
  await page.getByRole('tab', { name: 'PPL', exact: true }).click();
  const ppl = await edit(page, `source=${table.reference} | where ${field.textEdit.newText} `);
  expect(ppl.items.some((item) => forbidden.includes(item.label.toUpperCase()))).toBe(false);
  expect(ppl.items.map((item) => item.label)).toContain('like');
});
