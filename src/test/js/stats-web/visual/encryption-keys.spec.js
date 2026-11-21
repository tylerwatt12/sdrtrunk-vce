'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');
const { createEncryptionKeySnapshot } = require('./fixtures/encryption-keys.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const endpoint = '/api/v1/admin/encryption-keys';
const workspace = page => page.locator('.encryption-keys-workspace');
const editor = (page, editing = false) => page.getByRole('dialog', {
  name: editing ? 'Edit encryption key' : 'Add encryption key', exact: true
});
const protocols = createEncryptionKeySnapshot().protocols;
const keyId = index => `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`;
function keys(count = 33) {
  return Array.from({ length: count }, (_, index) => {
    const protocol = protocols[index % 3];
    return { id: keyId(index), label: index === 0 ? 'County Dispatch' : index === 1 ? 'City Utilities' :
      index === 2 ? 'Regional Transit' : index === count - 1 ? 'Zulu beyond first page' : `Receiver key ${String(index + 1).padStart(3, '0')}`,
    enabled: index !== 2, protocol: protocol.id, algorithm_id: protocol.algorithms[0].id, key_id: index + 7,
    key_hex: index % 3 === 0 ? '0102030405060708' : index % 3 === 1 ? '0102030405' : '1234',
    scope: index === count - 1 ? 'East Harbor' : index === 0 ? 'County' : null };
  });
}

async function openKeys(page, { theme = 'light', width = 1280, count = 33, denied = false } = {}) {
  await page.setViewportSize({ width, height: width < 600 ? 844 : 960 });
  const application = await installSiteStyleApplication(page, theme);
  const state = { keys: keys(count), writes: [], reads: 0, counter: 1, nextFailure: null,
    holdNext: false, pending: [] };
  state.revision = () => state.counter.toString(16).padStart(64, '0');
  state.snapshot = () => createEncryptionKeySnapshot(structuredClone(state.keys), state.revision());
  await page.route('**/api/v1/admin/encryption-keys**', async route => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const snapshot = status => route.fulfill({ status, headers: { ETag: `"${state.revision()}"` }, json: state.snapshot() });
    if (request.method() === 'GET') { state.reads++; return snapshot(200); }
    const write = { method: request.method(), pathname, headers: request.headers(),
      body: request.method() === 'DELETE' ? null : request.postDataJSON() };
    state.writes.push(write);
    let failure = state.nextFailure; state.nextFailure = null;
    if (state.holdNext) {
      state.holdNext = false;
      failure = await new Promise(resolve => state.pending.push(resolve));
    }
    if (failure === 'committed-network') {
      state.keys.push({ ...write.body, id: keyId(1000 + state.writes.length) }); state.counter++;
      return route.abort('connectionreset');
    }
    if (failure === 'network') return route.abort('connectionreset');
    if (failure === 409 || write.headers['if-match'] !== `"${state.revision()}"`) return snapshot(409);
    if (failure) return route.fulfill({ status: failure, json: { error: {
      code: 'save_failed', message: 'Keys could not be saved. Try again.' } } });
    if (write.method === 'POST') state.keys.push({ ...write.body, id: keyId(1000 + state.writes.length) });
    else if (write.method === 'PUT') {
      const id = pathname.split('/').at(-1);
      const index = state.keys.findIndex(key => key.id === id);
      expect(index).toBeGreaterThanOrEqual(0);
      state.keys[index] = { ...write.body, id };
    } else if (write.method === 'DELETE') state.keys = state.keys.filter(key => key.id !== pathname.split('/').at(-1));
    state.counter++;
    return snapshot(200);
  });
  if (denied) await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
    configured: true, authenticated: true, username: 'fixture-admin', tier: 'user', primary: false,
    csrf_token: 'fixture-csrf', guide_dismissal_scope: 'account-1-100', capabilities: {
      'admin-settings': false, 'admin-health': true, 'admin-users': false, 'admin-access': false,
      'admin-channels': false, 'admin-aliases': false, 'admin-tuners': false,
      dashboard: true, radio: true, channels: true, aliases: true
    }
  } } }));
  await page.goto('/app.html?view=admin&tab=encryption-keys');
  if (!denied) {
    await expect(workspace(page)).toBeVisible();
    await expect(workspace(page).getByRole('button', { name: 'Add key', exact: true })).toBeEnabled();
    await expect(workspace(page)).toContainText('County Dispatch');
  }
  return { ...application, state };
}

async function capture(page, name, rootSelector = '.encryption-keys-workspace') {
  if (process.env.WEB_REVIEW_DIR) {
    fs.mkdirSync(process.env.WEB_REVIEW_DIR, { recursive: true });
    await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR, `${name}.png`), animations: 'disabled' });
  }
  await expectContrast(page, { rootSelector, reportName: name });
}
async function auditEditor(page, name, editing = false) {
  const dialog = editor(page, editing);
  const id = await dialog.getAttribute('aria-labelledby');
  await capture(page, name, `[role="dialog"][aria-labelledby="${id}"]`);
}
async function fillNewKey(page, label = 'New County key') {
  const dialog = editor(page);
  await dialog.getByRole('textbox', { name: 'Label', exact: true }).fill(label);
  await dialog.getByRole('combobox', { name: 'Protocol', exact: true }).selectOption('APCO25');
  await dialog.getByRole('combobox', { name: 'Algorithm', exact: true }).selectOption('129');
  await dialog.getByRole('textbox', { name: /^Key ID/ }).fill('42');
  await dialog.getByRole('textbox', { name: /^Key \(hex\)/ }).fill('AA BB CC DD EE FF 00 11');
  await dialog.getByRole('textbox', { name: /^Scope/ }).fill('County');
}

for (const theme of ['light', 'dark']) for (const width of [1280, 390]) {
  test(`encryption keys render ${theme} at ${width}px with all protocol choices and contrast`, async ({ page }) => {
    const application = await openKeys(page, { theme, width });
    await expectNoHorizontalOverflow(page);
    for (const hue of [null, 35, 285]) {
      await page.evaluate(async value => (await import('/assets/core/theme.js?v=2')).applyThemeHue(value), hue);
      await capture(page, `keys-${theme}-${width}-${hue}`);
    }
    const add = workspace(page).getByRole('button', { name: 'Add key', exact: true });
    await add.focus(); await add.press('Enter');
    const dialog = editor(page);
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('textbox', { name: 'Label', exact: true })).toBeFocused();
    await expect(dialog.locator('input[type="password"]')).toHaveCount(0);
    await expect(dialog.getByRole('textbox', { name: /^Key \(hex\)/ })).toHaveAttribute('type', 'text');
    for (const protocol of protocols) {
      await dialog.getByRole('combobox', { name: 'Protocol', exact: true }).selectOption(protocol.id);
      await expect(dialog.getByRole('combobox', { name: 'Algorithm', exact: true }).locator('option')).toHaveCount(3);
    }
    for (const hue of [null, 35, 285]) {
      await page.evaluate(async value => (await import('/assets/core/theme.js?v=2')).applyThemeHue(value), hue);
      await auditEditor(page, `add-key-${theme}-${width}-${hue}`);
    }
    await dialog.getByRole('combobox', { name: 'Protocol', exact: true }).selectOption('APCO25');
    await dialog.getByRole('combobox', { name: 'Algorithm', exact: true }).selectOption('129');
    await expectNoHorizontalOverflow(page);
    const bounds = await dialog.boundingBox();
    expect(bounds.x).toBeGreaterThanOrEqual(0); expect(bounds.x + bounds.width).toBeLessThanOrEqual(width + 1);
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0); await expect(add).toBeFocused();
    const edit = workspace(page).getByRole('button', { name: 'Edit key County Dispatch', exact: true });
    await edit.click();
    const existing = editor(page, true);
    await expect(existing.getByRole('textbox', { name: /^Key \(hex\)/ })).toHaveValue('0102030405060708');
    for (const hue of [null, 35, 285]) {
      await page.evaluate(async value => (await import('/assets/core/theme.js?v=2')).applyThemeHue(value), hue);
      await auditEditor(page, `edit-key-${theme}-${width}-${hue}`, true);
    }
    await page.keyboard.press('Escape'); await expect(existing).toHaveCount(0); await expect(edit).toBeFocused();
    expect(application.pageErrors).toEqual([]); expect(application.unexpected).toEqual([]);
  });
}

test('encryption keys workflow searches the complete collection before paging', async ({ page }) => {
  const { state, pageErrors } = await openKeys(page);
  const panel = workspace(page), editLast = panel.getByRole('button', { name: 'Edit key Zulu beyond first page', exact: true });
  await expect(editLast).toHaveCount(0);
  await panel.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(editLast).toBeVisible();
  const search = panel.getByRole('searchbox');
  await search.fill('East Harbor');
  await expect(editLast).toBeVisible();
  await expect(panel.getByRole('button', { name: /^Edit key / })).toHaveCount(1);
  await expect(panel.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
  await search.fill('County');
  await expect(panel.getByRole('button', { name: 'Edit key County Dispatch', exact: true })).toBeVisible();
  await expect(panel.getByRole('button', { name: /^Edit key / })).toHaveCount(1);
  expect(state.reads).toBe(1); expect(pageErrors).toEqual([]);
});

test('encryption keys workflow preserves invalid and failed drafts, locks saves and retries once', async ({ page }) => {
  const { state, pageErrors } = await openKeys(page, { theme: 'dark', width: 390 });
  await workspace(page).getByRole('button', { name: 'Add key', exact: true }).click();
  await fillNewKey(page);
  const dialog = editor(page), label = dialog.getByRole('textbox', { name: 'Label', exact: true });
  await page.keyboard.press('Escape');
  const discard = page.getByRole('alertdialog', { name: 'Discard unsaved changes', exact: true });
  await expect(discard.getByRole('button', { name: 'Keep editing', exact: true })).toBeFocused();
  await discard.getByRole('button', { name: 'Keep editing', exact: true }).click();
  await expect(label).toHaveValue('New County key');
  const save = dialog.getByRole('button', { name: 'Save', exact: true });
  const material = dialog.getByRole('textbox', { name: /^Key \(hex\)/ });
  await material.fill('GG');
  expect(await material.evaluate(input => input.validity.valid)).toBe(false);
  await save.click(); expect(state.writes).toEqual([]); await expect(dialog).toBeVisible();
  await material.fill('AA BB CC DD EE FF 00 11');
  state.holdNext = true; await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  await expect(label).toBeDisabled(); await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeDisabled();
  await page.keyboard.press('Escape'); await expect(dialog).toBeVisible();
  await dialog.locator('form').evaluate(form => form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
  expect(state.writes).toHaveLength(1);
  state.pending.shift()(503);
  await expect(dialog).toContainText('could not be saved'); await expect(label).toHaveValue('New County key');
  await expect(save).toBeEnabled(); await auditEditor(page, 'failed-key-dark-phone');
  await save.click(); await expect(dialog).toHaveCount(0);
  expect(state.writes).toHaveLength(2);
  expect(state.writes[1].headers['x-csrf-token']).toBe('fixture-csrf');
  expect(state.writes[1].body).toMatchObject({ label: 'New County key', protocol: 'APCO25', algorithm_id: 129,
    key_id: 42, key_hex: 'AABBCCDDEEFF0011', scope: 'County', enabled: true });
  expect(state.keys.filter(key => key.label === 'New County key')).toHaveLength(1);
  expect(pageErrors).toEqual([]);
});

test('encryption keys workflow keeps an edited draft after conflict and uses the refreshed revision on retry', async ({ page }) => {
  const { state, pageErrors } = await openKeys(page);
  await workspace(page).getByRole('button', { name: 'Edit key County Dispatch', exact: true }).click();
  const dialog = editor(page, true), label = dialog.getByRole('textbox', { name: 'Label', exact: true });
  await label.fill('County draft');
  state.keys[0].label = 'Changed elsewhere'; state.counter++; state.nextFailure = 409;
  await dialog.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(dialog).toContainText('changed elsewhere'); await expect(label).toHaveValue('County draft');
  await dialog.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  expect(state.writes).toHaveLength(2);
  expect(state.writes[0].headers['if-match']).not.toBe(state.writes[1].headers['if-match']);
  expect(state.keys[0].label).toBe('County draft'); expect(pageErrors).toEqual([]);
});

test('encryption keys workflow edits every field and confirms deletion with Cancel first and retry', async ({ page }) => {
  const { state, pageErrors } = await openKeys(page, { theme: 'dark' });
  const panel = workspace(page);
  await panel.getByRole('button', { name: 'Edit key County Dispatch', exact: true }).click();
  const dialog = editor(page, true);
  await dialog.getByRole('combobox', { name: 'Protocol', exact: true }).selectOption('NXDN');
  await dialog.getByRole('combobox', { name: 'Algorithm', exact: true }).selectOption('1');
  await dialog.getByRole('textbox', { name: /^Key ID/ }).fill('9');
  await dialog.getByRole('textbox', { name: /^Key \(hex\)/ }).fill('1234');
  await dialog.getByRole('textbox', { name: /^Scope/ }).fill('North system');
  const enabled = dialog.getByRole('checkbox', { name: 'Enabled', exact: true });
  await enabled.focus(); await enabled.press('Space'); await expect(enabled).not.toBeChecked();
  await dialog.getByRole('button', { name: 'Save', exact: true }).click(); await expect(dialog).toHaveCount(0);
  expect(state.keys[0]).toMatchObject({ enabled: false, protocol: 'NXDN', algorithm_id: 1, key_id: 9,
    key_hex: '1234', scope: 'North system' });
  const remove = panel.getByRole('button', { name: 'Delete key County Dispatch', exact: true });
  await remove.click();
  let confirmation = page.getByRole('alertdialog', { name: /Delete/ });
  await expect(confirmation.getByRole('button', { name: 'Cancel', exact: true })).toBeFocused();
  await confirmation.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(confirmation).toHaveCount(0); await expect(remove).toBeFocused();
  await remove.click(); confirmation = page.getByRole('alertdialog', { name: /Delete/ });
  state.nextFailure = 503;
  await confirmation.getByRole('button', { name: 'Delete key', exact: true }).click();
  await expect(confirmation).toContainText('could not be saved'); await expect(confirmation).toBeVisible();
  expect(state.keys.some(key => key.id === keyId(0))).toBe(true);
  await confirmation.getByRole('button', { name: 'Delete key', exact: true }).click();
  await expect(confirmation).toHaveCount(0);
  expect(state.keys.some(key => key.id === keyId(0))).toBe(false);
  expect(state.writes.map(write => write.method)).toEqual(['PUT', 'DELETE', 'DELETE']);
  expect(pageErrors).toEqual([]);
});

test('encryption keys access does not fetch or display key values without settings permission', async ({ page }) => {
  const { state } = await openKeys(page, { denied: true });
  await expect(page.getByRole('heading', { name: 'Access denied', exact: true })).toBeVisible();
  await expect(workspace(page)).toHaveCount(0);
  await expect(page.locator('body')).not.toContainText('0102030405060708');
  await expect(page.getByRole('link', { name: 'Encryption keys', exact: true })).toHaveCount(0);
  expect(state.reads).toBe(0); expect(state.writes).toEqual([]);
});


test('encryption keys workflow confirms an uncertain committed addition without creating it twice', async ({ page }) => {
  const { state, pageErrors } = await openKeys(page);
  await workspace(page).getByRole('button', { name: 'Add key', exact: true }).click();
  await fillNewKey(page, 'Recovered County key');
  const dialog = editor(page), save = dialog.getByRole('button', { name: 'Save', exact: true });
  state.nextFailure = 'committed-network'; await save.click();
  await expect(dialog).toContainText('could not be confirmed'); await expect(save).toBeDisabled();
  await expect(dialog.getByRole('textbox', { name: 'Label', exact: true })).toHaveValue('Recovered County key');
  await dialog.getByRole('button', { name: 'Reload saved keys', exact: true }).click();
  await expect(dialog).toContainText('already saved'); await expect(save).toBeDisabled();
  await dialog.locator('form').evaluate(form => form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
  expect(state.writes).toHaveLength(1);
  expect(state.keys.filter(key => key.label === 'Recovered County key')).toHaveLength(1);
  await dialog.getByRole('textbox', { name: 'Label', exact: true }).fill('Explicitly another key');
  await expect(save).toBeEnabled(); expect(state.writes).toHaveLength(1);
  await dialog.getByRole('textbox', { name: 'Label', exact: true }).fill('Recovered County key');
  await expect(save).toBeDisabled();
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('alertdialog', { name: 'Discard unsaved changes', exact: true })).toHaveCount(0);
  expect(pageErrors).toEqual([]);
});
