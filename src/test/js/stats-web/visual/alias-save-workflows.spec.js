'use strict';
const { expect, test } = require('@playwright/test');

async function openAliasForm(page, editing) {
  const list = { alias_list_id: 1, name: 'County', family: 'P25', alias_count: 1, assigned_channel_count: 0 };
  const alias = { alias_id: 101, alias_list_id: 1, name: 'Dispatch', description: '', group: 'Fire',
    color: 0, recordable: true, matcher: { type: 'talkgroup', protocol: 'APCO25', value: 1201 } };
  const writes = [], pending = [];
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), pathname = new URL(request.url()).pathname;
    const data = value => route.fulfill({ json: { data: value } });
    if (request.method() !== 'GET') {
      writes.push({ pathname, body: request.postDataJSON() });
      await new Promise(resolve => pending.push(async status => {
        await route.fulfill({ status, json: status === 200 ? { data: { revision: 2, alias_list_id: 1 } } :
          { error: { code: status === 409 ? 'stale_revision' : 'unavailable', message: 'Could not save. Try again.' } } });
        resolve();
      }));
      return;
    }
    if (pathname === '/api/v1/auth/session') return data({ configured: true, authenticated: true,
      username: 'alias-admin', tier: 'admin', primary: true, csrf_token: 'fixture',
      capabilities: { 'admin-aliases': true, 'admin-channels': true, radio: true } });
    if (pathname === '/api/v1/admin/alias-lists') return data({ revision: 1, alias_lists: [list] });
    if (pathname === '/api/v1/alias-lists') return data({ rows: [list], limit: 500, offset: 0, has_more: false });
    if (pathname === '/api/v1/admin/channels') return data({ revision: 1, channels: [] });
    if (pathname === '/api/v1/aliases') return data({ rows: [], total_count: 0, limit: 50, offset: 0, has_more: false });
    if (pathname === '/api/v1/admin/aliases/options') return data({ revision: 1, alias_list: list,
      matchers: [{ type: 'talkgroup', protocol: 'APCO25', label: 'Talkgroup', fields: ['value'], minimum: 1, maximum: 65535 }] });
    if (pathname === '/api/v1/admin/aliases/101') return data({ revision: 1, alias });
    return route.fulfill({ status: 404, json: { error: { message: 'Unavailable in this fixture' } } });
  });
  await page.goto(`/app.html?view=aliases&list=1${editing ? '&alias=101' : ''}`);
  if (!editing) await page.locator('.alias-list-create:visible, .alias-list-mobile-create:visible').first().click();
  const dialog = page.getByRole('dialog', { name: editing ? 'Edit Dispatch' : 'Create Alias List', exact: true });
  await expect(dialog).toBeVisible();
  return { dialog, writes, pending };
}

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 390], ['dark', 390]]) {
for (const editing of [false, true]) {
  test(`${editing ? 'Alias edit' : 'Alias List creation'} locks all fields and preserves failed drafts for retry in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 740 });
    const { dialog, writes, pending } = await openAliasForm(page, editing);
    await page.locator('html').evaluate((element, value) => { element.dataset.theme = value; }, theme);
    const name = dialog.getByRole('textbox', { name: new RegExp(editing ? '^Alias name' : '^List name') });
    const save = dialog.getByRole('button', { name: editing ? 'Save Changes' : 'Create Alias List', exact: true });
    await name.fill('');
    await save.click();
    expect(writes).toHaveLength(0);
    await name.fill('County Dispatch');
    await save.click();
    await expect.poll(() => pending.length).toBe(1);
    await expect(name).toBeDisabled();
    await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeDisabled();
    await page.keyboard.press('Escape');
    for (const type of ['pointerdown', 'pointerup', 'click'])
      await page.locator('.modal-backdrop').last().dispatchEvent(type, { pointerId: 1, isPrimary: true, button: 0 });
    await dialog.locator('form').evaluate(form => form.dispatchEvent(new Event('submit', { cancelable: true })));
    expect(writes).toHaveLength(1);
    await pending.shift()(editing ? 409 : 503);
    await expect(dialog).toContainText('Could not save. Try again.');
    await expect(name).toHaveValue('County Dispatch');
    await expect(name).toBeEnabled();
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    const discard = page.getByRole('alertdialog', { name: 'Discard unsaved changes' });
    await discard.getByRole('button', { name: 'Keep editing', exact: true }).click();
    await expect(name).toHaveValue('County Dispatch');
    if (editing) await expect(dialog.getByRole('button', { name: 'Reload current values' })).toBeVisible();
    await save.click();
    await expect.poll(() => pending.length).toBe(1);
    await pending.shift()(200);
    await expect(dialog).toHaveCount(0);
    expect(writes.map(write => write.body.revision)).toEqual([1, 1]);
    if (editing) expect(writes[1].body.alias).toMatchObject({ name: 'County Dispatch',
      alias_list_id: 1, recordable: true, matcher: { type: 'talkgroup', protocol: 'APCO25', value: 1201 } });
    else expect(writes[1].body).toMatchObject({ name: 'County Dispatch', family: 'p25' });
  });
}
}
