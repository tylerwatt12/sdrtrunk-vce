'use strict';

const { expect, test } = require('@playwright/test');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');

test('Scan List member filtering survives a membership change without rebuilding the workspace', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 850 });
  await installSiteStyleApplication(page);
  const rows = [
    { alias_id: 101, alias_list_id: 1, alias_list_name: 'County', name: 'Fire Dispatch',
      group: 'Fire', matcher_type: 'TALKGROUP', identifier_display: 'TG 1201' },
    { alias_id: 102, alias_list_id: 1, alias_list_name: 'County', name: 'EMS Dispatch',
      group: 'EMS', matcher_type: 'TALKGROUP', identifier_display: 'TG 1202' }
  ];
  const queries = [], writes = [];
  await page.route('**/api/v1/**', route => {
    const request = route.request(), url = new URL(request.url());
    const respond = data => route.fulfill({ json: { data } });
    if (url.pathname === '/api/v1/admin/scan-lists') return respond({ revision: 1, scan_lists: [
      { id: 1, name: 'Public Safety', published: true, default: true, alias_count: rows.length,
        unmatched_alias_list_count: 0, edit_revision: 'members-v1' }
    ] });
    if (url.pathname === '/api/v1/aliases') {
      queries.push(Object.fromEntries(url.searchParams));
      const query = (url.searchParams.get('q') || '').toLowerCase();
      const matches = rows.filter(row => row.name.toLowerCase().includes(query));
      return respond({ rows: matches, total_count: matches.length, offset: 0, limit: 100,
        has_more: false, next_offset: null });
    }
    if (url.pathname === '/api/v1/admin/scan-lists/1/members' && request.method() === 'PUT') {
      const body = request.postDataJSON();
      writes.push(body);
      for (let index = rows.length - 1; index >= 0; index--) {
        if (body.alias_ids.includes(rows[index].alias_id)) rows.splice(index, 1);
      }
      return respond({ revision: 2 });
    }
    return route.fallback();
  });
  await page.goto('/app.html?view=aliases&scanListId=1&aliasTab=configure');
  const workspace = page.locator('.scan-list-members-workspace');
  await expect(workspace).toBeVisible();
  await page.evaluate(() => { window.memberWorkspace = document.querySelector('.scan-list-members-workspace'); });
  const search = page.locator('.alias-editor-filter-toolbar input[name="q"]');
  await search.fill('Fire');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.locator('.alias-filter-result-count')).toHaveText('1 matching alias');
  await page.getByRole('checkbox', { name: 'Select Fire Dispatch', exact: true }).check();
  await page.getByRole('button', { name: 'Remove from Public Safety', exact: true }).click();
  const dialog = page.getByRole('alertdialog', { name: 'Remove aliases · Public Safety', exact: true });
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(dialog).toHaveCount(0);
  expect(writes).toHaveLength(0);
  await page.getByRole('button', { name: 'Remove from Public Safety', exact: true }).click();
  await dialog.getByRole('button', { name: 'Remove 1 Aliases', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  expect(writes).toEqual([{ edit_revision: 'members-v1', operation: 'remove', alias_ids: [101] }]);
  expect(queries.at(-1)).toMatchObject({ q: 'Fire', scan_list_id: '1' });
  await expect(search).toHaveValue('Fire');
  await expect(page.locator('.alias-filter-result-count')).toHaveText('0 matching aliases');
  await expect(page.locator('.alias-editor-table-host')).toContainText('No aliases match these filters');
  expect(await page.evaluate(() => window.memberWorkspace === document.querySelector('.scan-list-members-workspace'))).toBe(true);
});
