'use strict';
const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const dialogFor = page => page.getByRole('dialog', { name: 'Rename Alias List', exact: true });
const longName = 'County Public Safety and Regional Emergency Communications Dispatch Services';

async function installRenameFixture(page, theme = 'light') {
  const preferences = structuredClone((await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults);
  preferences.appearance.theme = theme;
  const list = { alias_list_id: 1, name: longName, name_edit_revision: 'list-name-1-v1',
    family: 'P25', alias_count: 400, assigned_channel_count: 1 };
  const writes = [], pending = [], reads = [];
  let revision = 1;
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), pathname = url.pathname;
    const data = value => route.fulfill({ json: { data: value } });
    if (pathname === '/api/v1/admin/alias-lists/1' && request.method() === 'PUT') {
      const body = request.postDataJSON();
      writes.push(body);
      await new Promise(resolve => pending.push(async (status = 200, code = 'unavailable') => {
        if (status === 200) {
          revision += 1;
          list.name = body.name;
          list.name_edit_revision = `list-name-1-v${revision}`;
        }
        await route.fulfill({ status, json: status === 200 ? { data: { ...list, revision } } :
          { error: { code, message: code === 'stale_revision' ? 'This Alias List changed. Reload its current name.' :
            code === 'duplicate_name' ? 'An Alias List already uses this name. Choose another name.' :
              'Could not save the name. Try again.' } } });
        resolve();
      }));
      return;
    }
    reads.push(pathname);
    if (pathname === '/api/v1/auth/session') return data({ configured: true, authenticated: true,
      username: 'rename-admin', tier: 'admin', primary: true, csrf_token: 'fixture',
      capabilities: { 'admin-aliases': true, 'admin-channels': true, radio: true } });
    if (pathname === '/api/v1/me/tour-dismissals') return data({ version: 1,
      dismissed: { welcome: 1, 'channel-setup': 4, aliases: 5 } });
    if (pathname === '/api/v1/me/preferences') return route.fulfill({ json: { revision: 1, preferences } });
    if (pathname === '/api/v1/admin/alias-lists') return data({ revision, alias_lists: [list] });
    if (pathname === '/api/v1/alias-lists') return data({ rows: [list], limit: 500, offset: 0, has_more: false });
    if (pathname === '/api/v1/admin/channels') return data({ revision, channels: [
      { configuration_id: '10000000-0000-4000-8000-000000000001', alias_list_id: 1, name: 'County Control' }
    ] });
    if (pathname === '/api/v1/admin/aliases/options') return data({ revision, alias_list: list,
      group_names: ['Fire'], scan_lists: [], streams: [], matchers: [] });
    if (pathname === '/api/v1/aliases') return data({ rows: [{ alias_id: 101, alias_list_id: 1,
      alias_list_name: list.name, name: 'Dispatch North', group: 'Fire', matcher_type: 'TALKGROUP',
      matcher: { type: 'talkgroup', protocol: 'APCO25', value: 1201 } }],
      total_count: 400, limit: 100, offset: 100, has_more: true, next_offset: 200 });
    return route.fulfill({ status: 404, json: { error: { message: 'Unavailable in this fixture' } } });
  });
  await page.goto('/app.html?view=aliases&list=1&aliasTab=custom&q=Dispatch&group=Fire&offset=100&sort=name&direction=desc');
  await expect(page.locator('.alias-list-summary h2')).toHaveText(longName);
  return { list, writes, pending, reads, changeElsewhere: () => {
    list.name = 'Name changed elsewhere';
    list.name_edit_revision = 'list-name-1-external';
    revision += 1;
  } };
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Alias List rename preserves the browsing workspace and failed drafts in ${theme} at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 850 });
    const fixture = await installRenameFixture(page, theme);
    const trigger = page.getByRole('button', { name: 'Rename Alias List', exact: true });
    const selected = page.getByRole('checkbox', { name: 'Select Dispatch North', exact: true });
    await selected.check();
    const filter = page.locator('.alias-editor-filter-toolbar input[name="q"]');
    await filter.fill('Pending filter draft');
    const originalUrl = page.url(), reads = fixture.reads.length;
    await page.evaluate(() => { window.renameWorkspace = document.querySelector('.alias-editor-workspace'); });
    await expect(trigger).toHaveAttribute('title', 'Rename Alias List');
    await expectContrast(page, { rootSelector: '.alias-list-summary', reportName: `alias-rename-header-${theme}-${width}` });
    await expectNoHorizontalOverflow(page);
    await page.locator('.alias-list-summary').screenshot({ path: testInfo.outputPath(`alias-rename-header-${theme}-${width}.png`),
      animations: 'disabled' });
    await expect(page.locator('.alias-list-summary')).toHaveScreenshot(`alias-list-header-${theme}-${width}.png`);
    await trigger.focus();
    await page.keyboard.press('Enter');
    let dialog = dialogFor(page);
    const name = dialog.getByRole('textbox', { name: 'Name', exact: true });
    let save = dialog.getByRole('button', { name: 'Save', exact: true, includeHidden: true });
    await expect(name).toBeFocused();
    await expect(name).toHaveValue(longName);
    await expect(name).toHaveAttribute('maxlength', '128');
    await expect(save).toBeDisabled();
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();
    await page.keyboard.press('Enter');
    dialog = dialogFor(page);
    save = dialog.getByRole('button', { name: 'Save', exact: true });
    await dialog.getByRole('textbox', { name: 'Name', exact: true }).fill('County Dispatch');
    await expectContrast(page, { rootSelector: '.read-only-modal', reportName: `alias-rename-${theme}-${width}` });
    await expectNoHorizontalOverflow(page);
    await page.screenshot({ path: testInfo.outputPath(`alias-rename-${theme}-${width}.png`), animations: 'disabled' });
    await expect(dialog).toHaveScreenshot(`alias-list-rename-${theme}-${width}.png`);
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    const discard = page.getByRole('alertdialog', { name: 'Discard unsaved changes' });
    await discard.getByRole('button', { name: 'Keep editing', exact: true }).click();
    await save.click();
    await expect.poll(() => fixture.pending.length).toBe(1);
    await expect(dialog.getByRole('textbox', { name: 'Name', exact: true })).toBeDisabled();
    await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeDisabled();
    await page.keyboard.press('Escape');
    for (const type of ['pointerdown', 'pointerup', 'click'])
      await page.locator('.modal-backdrop').last().dispatchEvent(type, { pointerId: 1, isPrimary: true, button: 0 });
    await dialog.locator('form').evaluate(form => form.dispatchEvent(new Event('submit', { cancelable: true })));
    expect(fixture.writes).toHaveLength(1);
    await fixture.pending.shift()(503);
    await expect(dialog).toContainText('Could not save the name. Try again.');
    await expect(dialog.getByRole('textbox', { name: 'Name', exact: true })).toHaveValue('County Dispatch');
    await save.click();
    await expect.poll(() => fixture.pending.length).toBe(1);
    await fixture.pending.shift()(409, 'duplicate_name');
    await expect(dialog).toContainText('An Alias List already uses this name. Choose another name.');
    await expect(dialog.getByRole('textbox', { name: 'Name', exact: true })).toHaveValue('County Dispatch');
    await save.click();
    await expect.poll(() => fixture.pending.length).toBe(1);
    await fixture.pending.shift()();
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();
    await expect(page.locator('.alias-list-summary h2')).toHaveText('County Dispatch');
    await expect(page.locator('.alias-list-item-name')).toHaveText('County Dispatch');
    await expect(page.getByRole('combobox', { name: 'Alias list', exact: true, includeHidden: true }).locator('option:checked'))
      .toContainText('County Dispatch');
    await expect(filter).toHaveValue('Pending filter draft');
    await expect(selected).toBeChecked();
    await expect(selected.locator('xpath=ancestor::tr')).toHaveClass(/selected/);
    await expect(selected.locator('xpath=ancestor::tr')).toHaveAttribute('aria-selected', 'true');
    expect(page.url()).toBe(originalUrl);
    expect(fixture.reads.length).toBe(reads);
    expect(await page.evaluate(() => window.renameWorkspace === document.querySelector('.alias-editor-workspace'))).toBe(true);
    await trigger.click();
    dialog = dialogFor(page);
    await expect(dialog.getByRole('textbox', { name: 'Name', exact: true })).toHaveValue('County Dispatch');
    await dialog.getByRole('textbox', { name: 'Name', exact: true }).fill('County Public Safety');
    await dialog.getByRole('button', { name: 'Save', exact: true }).click();
    await expect.poll(() => fixture.pending.length).toBe(1);
    expect(fixture.writes.at(-1)).toEqual({ name: 'County Public Safety', edit_revision: 'list-name-1-v2' });
    await fixture.pending.shift()();
    await expect(dialog).toHaveCount(0);
  });
}

test('A stale Alias List name reloads its current version while retaining the rename draft', async ({ page }) => {
  const fixture = await installRenameFixture(page);
  await page.getByRole('button', { name: 'Rename Alias List', exact: true }).click();
  const dialog = dialogFor(page), name = dialog.getByRole('textbox', { name: 'Name', exact: true });
  await name.fill('My retained name');
  await dialog.getByRole('button', { name: 'Save', exact: true }).click();
  await expect.poll(() => fixture.pending.length).toBe(1);
  fixture.changeElsewhere();
  await fixture.pending.shift()(409, 'stale_revision');
  await dialog.getByRole('button', { name: 'Reload current values', exact: true }).click();
  await expect(dialog).toContainText('Current name: Name changed elsewhere. Your draft is unchanged.');
  await expect(name).toHaveValue('My retained name');
  await expect(page.locator('.alias-list-summary h2')).toHaveText('Name changed elsewhere');
  await dialog.getByRole('button', { name: 'Save', exact: true }).click();
  await expect.poll(() => fixture.pending.length).toBe(1);
  expect(fixture.writes.at(-1)).toEqual({ name: 'My retained name', edit_revision: 'list-name-1-external' });
  await fixture.pending.shift()();
  await expect(dialog).toHaveCount(0);
  await expect(page.locator('.alias-list-summary h2')).toHaveText('My retained name');
});
