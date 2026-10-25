'use strict';
const { expect, test } = require('@playwright/test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');

let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Scan List saves use the selected list version and preserve conflicting drafts in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 });
    const history = { version: 1, dismissed: { welcome: 1, 'scan-lists': 7 } };
    let preferences = structuredClone(defaults), preferenceRevision = 1;
    preferences.appearance.theme = theme;
    let revision = 1;
    const writes = [];
    const lists = [
      { id: 1, sort_order: 0, name: 'Default', published: true, default: true, alias_count: 0,
        unmatched_alias_list_count: 0, edit_revision: 'default-v1' },
      { id: 2, sort_order: 1, name: 'County', description: 'County calls', published: true, default: false,
        alias_count: 0, unmatched_alias_list_count: 0, edit_revision: 'county-v1' }
    ];
    await page.route('**/api/v1/**', async route => {
      const request = route.request(), path = new URL(request.url()).pathname;
      const data = value => route.fulfill({ json: { data: value } });
      if (path === '/api/v1/auth/session') return data({ configured: true, authenticated: true,
        username: 'administrator', tier: 'admin', primary: true, csrf_token: 'fixture',
        guide_dismissal_scope: 'scan-list-save-fixture',
        capabilities: { 'admin-aliases': true, 'admin-channels': true, radio: true } });
      if (path === '/api/v1/me/tour-dismissals') {
        if (request.method() === 'PUT') {
          for (const [id, version] of Object.entries(request.postDataJSON().dismissed))
            history.dismissed[id] = Math.max(history.dismissed[id] || 0, version);
        }
        return data(history);
      }
      if (path === '/api/v1/me/preferences') {
        if (request.method() === 'PUT') {
          preferences = request.postDataJSON();
          preferenceRevision++;
        }
        return route.fulfill({ json: { revision: preferenceRevision, preferences } });
      }
      if (path === '/api/v1/admin/scan-lists/2' && request.method() === 'PUT') {
        const body = request.postDataJSON();
        writes.push(body);
        if (body.edit_revision !== lists[1].edit_revision) return route.fulfill({ status: 409,
          json: { error: { code: 'stale_revision', message: 'These settings changed after you opened them.' } } });
        Object.assign(lists[1], body.scan_list, { edit_revision: 'county-v2' });
        return data({ revision: ++revision, scan_list_id: 2 });
      }
      if (path === '/api/v1/admin/scan-lists') return data({ revision, scan_lists: lists });
      if (path === '/api/v1/scan-lists') return data({ scan_lists: lists });
      return route.fulfill({ status: 404, json: { error: { message: 'Unavailable in this fixture' } } });
    });
    await page.goto('/app.html?view=scan-lists');
    await page.locator('html').evaluate((element, value) => { element.dataset.theme = value; }, theme);
    await page.locator('.scan-list-selector[data-scan-list-id="2"]').click();
    await page.locator('.admin-scan-list-edit[data-scan-list-id="2"]').click();
    let dialog = page.getByRole('dialog', { name: 'Edit scan list · County', exact: true });
    await dialog.getByRole('textbox', { name: /^Name/ }).fill('County Updated');
    revision += 1; // Another saved item changes while this editor remains open.
    await dialog.getByRole('button', { name: 'Save Scan List', exact: true }).click();
    await expect(dialog).toHaveCount(0);
    expect(writes).toHaveLength(1);
    expect(writes[0]).toMatchObject({ edit_revision: 'county-v1', scan_list: { name: 'County Updated' } });
    expect(Object.hasOwn(writes[0], 'revision')).toBe(false);
    await expect(page.locator('#admin-scan-list-detail')).toContainText('County Updated');

    await page.locator('.admin-scan-list-edit[data-scan-list-id="2"]').click();
    dialog = page.getByRole('dialog', { name: 'Edit scan list · County Updated', exact: true });
    await dialog.getByRole('textbox', { name: /^Name/ }).fill('Unsaved County Draft');
    lists[1].edit_revision = 'concurrent-county-change';
    await dialog.getByRole('button', { name: 'Save Scan List', exact: true }).click();
    await expect(dialog).toContainText('These settings changed after you opened them.');
    await expect(dialog.getByRole('textbox', { name: /^Name/ })).toHaveValue('Unsaved County Draft');
    await expect(dialog.getByRole('textbox', { name: /^Name/ })).toBeEnabled();
    expect(writes[1].edit_revision).toBe('county-v2');
    expect(lists[1].name).toBe('County Updated');
  });
}
