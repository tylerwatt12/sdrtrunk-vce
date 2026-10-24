'use strict';
const { expect, test } = require('@playwright/test');

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Call Handling Defaults pair the displayed settings with their own edit version in ${theme} at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 850 });
    await page.addInitScript(() => {
      for (const id of ['welcome', 'channel-setup', 'aliases']) {
        localStorage.setItem(`sdrtrunk-vce-guide:v1:administrator:alias-defaults-fixture:${id}:${id === 'aliases' ? 5 : id === 'channel-setup' ? 4 : 1}`, '1');
      }
    });
    const stale = { alias_list_id: 1, name: 'County', family: 'P25', alias_count: 0, assigned_channel_count: 0,
      unknown_alias_behavior: { recordable: false, scan_list_ids: [], broadcast_configuration_ids: [] },
      new_alias_behavior: { recordable: false, scan_list_ids: [], broadcast_configuration_ids: [] } };
    const current = { ...stale,
      unknown_alias_behavior: { recordable: true, scan_list_ids: [7], broadcast_configuration_ids: ['stream-1'] },
      new_alias_behavior: { recordable: true, scan_list_ids: [7], broadcast_configuration_ids: ['stream-1'] } };
    const writes = [];
    await page.route('**/api/v1/**', async route => {
      const request = route.request(), path = new URL(request.url()).pathname;
      const data = value => route.fulfill({ json: { data: value } });
      if (path === '/api/v1/auth/session') return data({ configured: true, authenticated: true,
        username: 'administrator', tier: 'admin', primary: true, csrf_token: 'fixture',
        guide_dismissal_scope: 'alias-defaults-fixture',
        capabilities: { 'admin-aliases': true, 'admin-channels': true, radio: true } });
      if (path === '/api/v1/admin/alias-lists/1/defaults') {
        writes.push(request.postDataJSON());
        return data({ revision: 3, alias_list_id: 1 });
      }
      if (path === '/api/v1/admin/alias-lists') return data({ revision: 1, alias_lists: [stale] });
      if (path === '/api/v1/alias-lists') return data({ rows: [stale], offset: 0, limit: 500, has_more: false });
      if (path === '/api/v1/admin/aliases/options') return data({ revision: 2, edit_revision: 'county-defaults-v2',
        alias_list: current, scan_lists: [{ id: 7, name: 'County Calls', published: true }],
        streams: [{ configuration_id: 'stream-1', name: 'Approved Stream' }], matchers: [] });
      if (path === '/api/v1/admin/channels') return data({ revision: 1, channels: [] });
      if (path === '/api/v1/aliases') return data({ rows: [], total_count: 0, offset: 0, limit: 50, has_more: false });
      return route.fulfill({ status: 404, json: { error: { message: 'Unavailable in this fixture' } } });
    });
    await page.goto('/app.html?view=aliases&list=1&aliasTab=configure');
    await page.locator('html').evaluate((element, value) => { element.dataset.theme = value; }, theme);
    await page.locator('.alias-policy-button').click();
    const dialog = page.getByRole('dialog', { name: 'Call Handling Defaults · County', exact: true });
    const unknown = dialog.locator('[data-behavior="unknown"]');
    await expect(unknown.getByRole('checkbox', { name: 'Record calls', exact: true })).toBeChecked();
    await expect(unknown.getByRole('checkbox', { name: 'County Calls', exact: true })).toBeChecked();
    await expect(unknown.getByRole('checkbox', { name: 'Approved Stream', exact: true })).toBeChecked();
    const record = unknown.getByRole('checkbox', { name: 'Record calls', exact: true });
    await record.locator('..').locator('.ui-toggle-track').click();
    await expect(record).not.toBeChecked();
    await dialog.screenshot({ path: testInfo.outputPath(`defaults-${theme}.png`) });
    await dialog.getByRole('button', { name: 'Save Call Handling Defaults', exact: true }).click();
    await expect(dialog).toHaveCount(0);
    expect(writes).toHaveLength(1);
    expect(writes[0]).toEqual({ edit_revision: 'county-defaults-v2',
      unknown_alias_behavior: { recordable: false, scan_list_ids: [7], broadcast_configuration_ids: ['stream-1'] },
      new_alias_behavior: { recordable: true, scan_list_ids: [7], broadcast_configuration_ids: ['stream-1'] } });
  });
}
