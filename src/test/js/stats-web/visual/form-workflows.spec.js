'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

async function openForms(page, view, theme = 'light', scanList = {}) {
  const preferences = structuredClone(defaults);
  preferences.appearance.theme = theme;
  const state = { writes: [], pending: [], scanLists: [{ id: 1, name: 'Public Safety',
    description: 'Dispatch and mutual aid', sort_order: 0, published: true, default: true,
    alias_count: 2, unmatched_alias_list_count: 0, unmatched_alias_lists: [], ...scanList }],
    receiver: { revision: 4, settings: { traffic_grant_age_out_milliseconds: 2000 } } };
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const data = (value) => route.fulfill({ json: { data: value } });
    if (pathname === '/api/v1/auth/session') return data({ configured: true, authenticated: true,
      username: 'admin', tier: 'admin', primary: true, capabilities: {
        'admin-settings': true, 'admin-aliases': true, 'receiver-health': true,
        'scanner': true, 'recordings': true, 'credits': true } });
    if (pathname === '/api/v1/me/preferences') return route.fulfill({ json: { revision: 1, preferences } });
    if (['/api/v1/admin/scan-lists', '/api/v1/scan-lists'].includes(pathname)) {
      return data({ revision: 1, scan_lists: state.scanLists });
    }
    if (request.method() === 'PUT' &&
        ['/api/v1/admin/scan-lists/1', '/api/v1/admin/receiver-settings'].includes(pathname)) {
      const body = request.postDataJSON();
      state.writes.push({ pathname, body, revision: request.headers()['if-match'] });
      const response = await new Promise((resolve) => state.pending.push(resolve));
      if (response.status !== 200) return route.fulfill(response);
      if (pathname.endsWith('/scan-lists/1')) {
        state.scanLists[0] = { ...state.scanLists[0], ...body.scan_list };
        return data({ revision: 2 });
      }
      state.receiver = { revision: state.receiver.revision + 1, settings: body };
      return route.fulfill({ json: state.receiver });
    }
    if (pathname === '/api/v1/admin/receiver-settings') return route.fulfill({ json: state.receiver });
    if (pathname === '/api/v1/admin/spectrum-snap-presets') return route.fulfill({ json: {
      revision: 9, country_code: 'US', country_label: 'United States',
      countries: [{ code: 'US', label: 'United States' }], scopes: [] } });
    if (pathname === '/api/v1/status') return data({ database: { logger: [] }, stats_logging: {
      summary_configured: true, detailed_history_configured: true,
      summary_active: true, detailed_history_active: true, state: 'RUNNING' } });
    return route.fulfill({ status: 404, json: { error: { message: 'Unavailable' } } });
  });
  await page.goto(`/app.html?${view}`);
  return { state, finish: (status = 200) => state.pending.shift()({ status,
    json: { error: { message: 'The receiver is busy. Try again.' } } }) };
}

test('scan-list save blocks dismissal and duplicate submissions, keeps draft after failure, and returns focus', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  const app = await openForms(page, 'view=scan-lists');
  await page.getByRole('button', { name: 'Hide audio player', exact: true }).click();
  await expect(page.locator('#audio-dock')).toHaveAttribute('data-super-collapsed', 'true');
  const edit = page.getByRole('button', { name: 'Edit Public Safety details', exact: true });
  await edit.click();
  const dialog = page.getByRole('dialog', { name: 'Edit scan list · Public Safety' });
  const name = dialog.getByRole('textbox', { name: /^Name\b/ });
  const save = dialog.getByRole('button', { name: 'Save Scan List', exact: true });
  await expect(name).toBeFocused();
  await expect(save).toBeDisabled();
  await name.fill('County Dispatch');
  await expect(dialog.getByRole('status')).toHaveText('Unsaved changes');
  await save.click();
  await expect.poll(() => app.state.pending.length).toBe(1);
  await expect(dialog).toHaveAttribute('aria-busy', 'true');
  await expect(name).toBeDisabled();
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  await dialog.locator('form').evaluate((form) => form.dispatchEvent(new Event('submit', { cancelable: true })));
  expect(app.state.writes).toHaveLength(1);
  app.finish(503);
  await expect(dialog.getByRole('alert')).toHaveText('The receiver is busy. Try again.');
  await expect(name).toBeEnabled();
  await expect(name).toHaveValue('County Dispatch');
  await expect(dialog.getByLabel('Default scan list', { exact: true })).toBeDisabled();
  await expect(dialog.getByLabel('Available to listeners', { exact: true })).toBeDisabled();
  await expect(save).toBeEnabled();
  await save.click();
  await expect.poll(() => app.state.pending.length).toBe(1);
  app.finish();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('button', { name: 'Edit County Dispatch details', exact: true })).toBeFocused();
  expect(app.state.writes[1].body).toEqual({ revision: 1, scan_list: {
    name: 'County Dispatch', description: 'Dispatch and mutual aid', sort_order: 0,
    published: true, default: true } });
});

test('Live timing validates, retains failed edits for retry, and saves with the original revision', async ({ page }) => {
  const app = await openForms(page, 'view=admin&tab=display');
  const timing = page.getByRole('spinbutton', { name: /^Mark a traffic row idle/ });
  const save = page.getByRole('button', { name: 'Save Live timing', exact: true });
  const form = page.locator('.receiver-settings-form');
  await expect(timing).toHaveValue('2000');
  await expect(save).toBeDisabled();
  await timing.fill('99');
  await save.click();
  expect(app.state.writes).toHaveLength(0);
  await timing.fill('3750');
  await save.click();
  await expect.poll(() => app.state.pending.length).toBe(1);
  await expect(timing).toBeDisabled();
  await expect(form).toHaveAttribute('aria-busy', 'true');
  app.finish(503);
  await expect(form.getByRole('alert')).toHaveText('The receiver is busy. Try again.');
  await expect(timing).toHaveValue('3750');
  await expect(save).toBeEnabled();
  await save.click();
  await expect.poll(() => app.state.pending.length).toBe(1);
  app.finish();
  await expect(form.getByRole('status')).toHaveText('Live timing saved.');
  await expect(save).toBeDisabled();
  expect(app.state.writes.map((write) => write.revision)).toEqual(['"4"', '"4"']);
});

test('scan-list availability saves use the same neutral labels in the selector and detail', async ({ page }) => {
  const app = await openForms(page, 'view=scan-lists', 'light', { default: false });
  await page.getByRole('button', { name: 'Hide audio player', exact: true }).click();
  await expect(page.locator('#audio-dock')).toHaveAttribute('data-super-collapsed', 'true');
  const pill = page.locator('.scan-list-detail-badges .ui-pill');
  await expect(pill).toHaveText('Available to listeners');
  await expect(pill).toHaveClass('ui-pill');
  await page.getByRole('button', { name: 'Edit Public Safety details', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Edit scan list · Public Safety' });
  const available = dialog.getByRole('checkbox', { name: 'Available to listeners', exact: true });
  await available.focus();
  await page.keyboard.press('Space');
  await expect(available).not.toBeChecked();
  await dialog.getByRole('button', { name: 'Save Scan List', exact: true }).click();
  await expect.poll(() => app.state.pending.length).toBe(1);
  app.finish();
  await expect(dialog).toHaveCount(0);
  await expect(pill).toHaveText('Hidden from listeners');
  await expect(pill).toHaveClass('ui-pill');
  await expect(page.locator('.scan-list-selector small')).toHaveText('Hidden from listeners');
});

for (const theme of ['light', 'dark']) {
  test(`long ${theme} mobile form keeps action footer visible and keyboard reachable`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 520 });
    await openForms(page, 'view=scan-lists', theme);
    await page.getByRole('button', { name: 'Edit Public Safety details', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Edit scan list · Public Safety' });
    await dialog.getByRole('textbox', { name: /^Name\b/ }).fill('Regional Public Safety Dispatch');
    const footer = dialog.locator('.ui-modal-footer');
    await expect(footer).toHaveCSS('position', 'sticky');
    const bounds = await footer.boundingBox();
    expect(bounds.x).toBeGreaterThanOrEqual(0);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(390);
    expect(bounds.y + bounds.height).toBeLessThanOrEqual(520);
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).focus();
    await page.keyboard.press('Tab');
    await expect(dialog.getByRole('button', { name: 'Save Scan List', exact: true })).toBeFocused();
    await expect(dialog).toHaveScreenshot(`form-actions-${theme}-mobile.png`);
  });
}

test('failure mapping cannot strand a form busy or enable a permanently disabled control', async ({ page }) => {
  await page.goto('/design-system.html');
  const state = await page.evaluate(async () => {
    const { createFormWorkflow } = await import('/assets/core/form-workflows.js');
    const form = document.createElement('form');
    const input = document.createElement('input');
    input.disabled = true;
    const submit = document.createElement('button');
    const feedback = document.createElement('div');
    form.append(input, submit, feedback);
    document.body.append(form);
    const workflow = createFormWorkflow({ form, submit, feedback });
    await workflow.save(async () => { throw new Error('Save failed'); }, {
      onError: () => { throw new Error('Refresh failed'); }
    });
    return { busy: workflow.isBusy(), markedBusy: form.hasAttribute('aria-busy'),
      inputDisabled: input.disabled, submitDisabled: submit.disabled,
      role: feedback.getAttribute('role'), message: feedback.textContent };
  });
  expect(state).toEqual({ busy: false, markedBusy: false, inputDisabled: true,
    submitDisabled: false, role: 'alert', message: 'Refresh failed' });
});
