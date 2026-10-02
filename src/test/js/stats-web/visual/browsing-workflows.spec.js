const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

async function openAliases(page, theme = 'light') {
  const state = { queries: [] };
  const aliasList = { alias_list_id: 1, name: 'County Public Safety', family: 'P25',
    alias_count: 5, assigned_channel_count: 0 };
  const preferences = structuredClone(defaults);
  preferences.appearance.theme = theme;
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    let data;
    if (path === '/api/v1/auth/session') data = {
      configured: true, authenticated: true, username: 'alias-admin', tier: 'admin', primary: true,
      csrf_token: 'test-token', capabilities: { 'admin-aliases': true, 'admin-channels': true, radio: true }
    };
    else if (path === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } }); return;
    }
    else if (path === '/api/v1/alias-lists') data = {
      rows: [aliasList], limit: 500, offset: 0, has_more: false, next_offset: null
    };
    else if (path === '/api/v1/admin/alias-lists') data = { revision: 1, alias_lists: [aliasList] };
    else if (path === '/api/v1/admin/channels') data = { revision: 1, channels: [] };
    else if (path === '/api/v1/admin/aliases/options') data = { revision: 1, alias_list: aliasList,
      group_names: ['Fire', 'Police'], scan_lists: [{ id: 2, name: 'Training', published: false }] };
    else if (path === '/api/v1/aliases') {
      const query = Object.fromEntries(url.searchParams);
      state.queries.push(query);
      const total = query.q === 'missing' ? 0 : query.type === 'radio' ? 1 : 5;
      const offset = Number(query.offset || 0);
      const length = Math.min(2, Math.max(0, total - offset));
      data = { rows: Array.from({ length }, (_, index) => ({
        alias_id: 101 + offset + index, alias_list_id: 1, name: `Fire Dispatch ${offset + index + 1}`,
        group: 'Fire', matcher_type: 'TALKGROUP', identifier_display: `TG ${101 + offset + index}`
      })), matcher_types: ['TALKGROUP'], total_count: total, offset, limit: 2,
      has_more: offset + length < total, next_offset: offset + length < total ? offset + 2 : null };
    }
    else if (path === '/api/v1/status') data = {};
    await route.fulfill(data ? { json: { data } } :
      { status: 404, json: { error: { message: 'Unavailable in this browser fixture' } } });
  });
  await page.goto('/app.html?view=aliases&list=1&aliasTab=configure&q=fire');
  await expect(page.locator('.alias-editor-table-host tbody tr')).toHaveCount(2);
  return state;
}

for (const theme of ['light', 'dark']) {
  test(`Alias phone ${theme} filters preserve drafts and Enter applies once`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const state = await openAliases(page, theme);
    const search = page.locator('.alias-editor-filter-toolbar input[name="q"]');
    const filters = page.locator('#alias-editor-filters-toggle');
    await search.fill('unfinished search');
    await filters.click();
    const dialog = page.getByRole('dialog', { name: 'Filters', exact: true });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Apply filters', exact: true })).toHaveCount(1);
    const identity = dialog.getByRole('combobox', { name: 'Identity', exact: true });
    await identity.selectOption('radio');
    await expect(dialog.getByRole('combobox', { name: 'Scan list', exact: true }))
      .toContainText('Training · Hidden from listeners');
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(dialog).toBeHidden();
    await expect(filters).toBeFocused();
    await expect(search).toHaveValue('unfinished search');
    expect(new URL(page.url()).searchParams.get('q')).toBe('fire');
    expect(state.queries).toHaveLength(1);
    await expect(filters.locator('.ui-pill')).toHaveCount(0);
    await filters.click();
    await expect(identity).toHaveValue('radio');
    await dialog.getByLabel('Group', { exact: true }).fill('Fire');
    await dialog.getByLabel('Group', { exact: true }).press('Enter');
    await expect(dialog).toBeHidden();
    await expect.poll(() => state.queries.length).toBe(2);
    expect(state.queries.at(-1)).toMatchObject({ q: 'unfinished search', type: 'radio', group: 'Fire', list: '1' });
    await expect(filters.locator('.ui-pill')).toHaveText('2');
    await expect(page.locator('.alias-editor-table-host tbody tr')).toHaveCount(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  });
}

test('Alias paging uses stable counts for multiple, single and empty results', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const state = await openAliases(page);
  const pager = page.locator('.ui-browse-pager').last();
  await expect(pager.locator('.ui-browse-pager-count')).toHaveText('Rows 1–2 of 5');
  await expect(pager.getByRole('button', { name: 'Previous', exact: true })).toBeDisabled();
  await pager.getByRole('link', { name: 'Next', exact: true }).click();
  await expect(pager.locator('.ui-browse-pager-count')).toHaveText('Rows 3–4 of 5');
  expect(state.queries.at(-1)).toMatchObject({ q: 'fire', offset: '2' });
  await pager.getByRole('link', { name: 'Next', exact: true }).click();
  await expect(pager.locator('.ui-browse-pager-count')).toHaveText('Rows 5–5 of 5');
  await expect(pager.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
  await page.locator('#alias-editor-filters-toggle').click();
  await page.getByRole('combobox', { name: 'Identity', exact: true }).selectOption('radio');
  // Paging uses the production / route; this test server serves the gallery there.
  await page.locator('.alias-editor-filter-toolbar').evaluate(form => { form.action = '/app.html'; });
  await page.getByRole('button', { name: 'Apply filters', exact: true }).click();
  await expect(pager.locator('.ui-browse-pager-count')).toHaveText('Rows 1–1 of 1');
  await expect(pager.getByRole('button', { name: 'Previous', exact: true })).toBeDisabled();
  await expect(pager.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
  await page.locator('.alias-editor-filter-toolbar input[name="q"]').fill('missing');
  await page.locator('.alias-editor-filter-toolbar').getByRole('button', { name: 'Search', exact: true }).click();
  await expect(pager.locator('.ui-browse-pager-count')).toHaveText('Rows 0 of 0');
  await expect(pager.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
});
