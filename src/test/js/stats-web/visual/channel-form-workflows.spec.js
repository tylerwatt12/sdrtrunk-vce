'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

async function openEditor(page, processingState = 'RUNNING') {
  const id = '00000000-0000-4000-8000-000000000031';
  const profile = { id: 'dmr', label: 'DMR', channel_kind: 'TRUNKED', alias_family: 'DMR',
    sections: [{ id: 'general', label: 'General', fields: [
      { path: 'name', label: 'Name', type: 'text', required: true }
    ] }, { id: 'protocol', label: 'Decoder', fields: [
      { path: 'settings.ignore_encrypted_calls', label: 'Skip encrypted traffic channels (performance)',
        type: 'boolean', default: false }
    ] }] };
  const state = { revision: 4, processingState, pending: [], writes: [], channel: {
    configuration_id: id, protocol_id: 'dmr', name: 'County Dispatch', system: 'County', site: 'North',
    alias_list_id: 1, source: { frequencies_hz: [451012500] }, settings: { ignore_encrypted_calls: true },
    frequency_map: [], event_logs: [], recorders: [], auxiliary_decoders: [] } };
  await page.route('**/api/v1/**', async route => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const data = value => route.fulfill({ json: { data: value } });
    if (pathname === '/api/v1/auth/session') return data({ configured: true, authenticated: true,
      username: 'channel-admin', tier: 'admin', primary: true, csrf_token: 'fixture-token',
      capabilities: { 'admin-channels': true } });
    if (pathname === '/api/v1/me/preferences') return route.fulfill({ json: { revision: 1, preferences: defaults } });
    if (pathname === '/api/v1/admin/channels/protocols') return data({
      voice_decryption_module_loaded: true, profiles: [profile] });
    if (pathname === '/api/v1/admin/channels/options') return data({ revision: state.revision,
      alias_lists: [{ id: 1, name: 'Default DMR', family: 'DMR' }], tuners: [] });
    if (pathname === '/api/v1/admin/channels' && request.method() === 'GET') return data({
      revision: state.revision, channels: [{ ...state.channel, protocol_label: 'DMR', channel_kind: 'TRUNKED',
        frequencies_hz: [451012500], processing_state: state.processingState, editable: true }] });
    if ((pathname === `/api/v1/admin/channels/${id}` && request.method() === 'PUT') ||
        (pathname === '/api/v1/admin/channels/actions' && request.method() === 'POST')) {
      const body = request.postDataJSON();
      state.writes.push({ pathname, method: request.method(), body });
      await new Promise(resolve => state.pending.push(async (status) => {
        if (status !== 200) await route.fulfill({ status, json: { error: {
          message: 'Channel request failed. Try again.',
          ...(status === 409 ? { code: 'stale_revision' } : {}) } } });
        else {
          state.revision += 1;
          if (request.method() === 'PUT') {
            state.channel = { ...state.channel, ...body };
            await data({ revision: state.revision, configuration_ids: [id] });
          } else {
            state.processingState = body.action === 'START' ? 'RUNNING' : 'STOPPED';
            await data({ revision: state.revision, results: [{ configuration_id: id, success: true }] });
          }
        }
        resolve();
      }));
      return;
    }
    if (pathname === `/api/v1/admin/channels/${id}`) return data({ revision: state.revision,
      channel: state.channel, processing_state: state.processingState });
    return route.fulfill({ status: 404, json: { error: { message: 'Unavailable in this browser fixture' } } });
  });
  await page.goto('/app.html?view=channel-setup');
  await page.locator(`tr[data-id="${id}"]`).getByRole('button', { name: 'Edit', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Edit County Dispatch', exact: true });
  await expect(dialog.getByRole('textbox', { name: 'Name', exact: true })).toHaveValue('County Dispatch');
  return { state, dialog, release: async (status = 200) => state.pending.shift()(status) };
}

async function expectMutationLocked(page, dialog) {
  await expect(dialog).toHaveAttribute('aria-busy', 'true');
  await expect(dialog.locator('form')).toHaveAttribute('aria-busy', 'true');
  for (const label of ['Cancel', 'Reset changes'])
    await expect(dialog.getByRole('button', { name: label, exact: true })).toBeDisabled();
  await expect(dialog.getByRole('textbox', { name: 'Name', exact: true })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  await expect(page.getByRole('alertdialog')).toHaveCount(0);
}

test('channel save validates and locks dismissal, reset, edits and duplicate submits while preserving failed drafts', async ({ page }) => {
  const app = await openEditor(page);
  const { dialog, state } = app;
  const name = dialog.getByRole('textbox', { name: 'Name', exact: true });
  const save = dialog.getByRole('button', { name: 'Save & restart', exact: true });
  const locked = dialog.getByRole('checkbox', { name: 'Skip encrypted traffic channels (performance)', exact: true });
  await expect(locked).toBeDisabled();
  await name.fill('');
  await save.click();
  expect(state.writes).toHaveLength(0);
  await expect(page.getByRole('alertdialog')).toHaveCount(0);
  await name.fill('Updated Dispatch');
  await save.click();
  const confirmation = page.getByRole('alertdialog', { name: 'Restart running channel', exact: true });
  await confirmation.getByRole('button', { name: 'Save and restart', exact: true }).click();
  await expect.poll(() => state.pending.length).toBe(1);
  await expectMutationLocked(page, dialog);
  await expect(dialog.getByRole('status')).toContainText('Saving');
  await dialog.locator('form').evaluate(form => form.dispatchEvent(new Event('submit', { cancelable: true })));
  expect(state.writes).toHaveLength(1);
  await app.release(503);
  await expect(dialog.getByRole('alert')).toHaveText('Channel request failed. Try again.');
  await expect(name).toHaveValue('Updated Dispatch');
  await expect(name).toBeEnabled();
  await expect(save).toBeEnabled();
  await expect(locked).toBeDisabled();
  await expect(dialog.getByRole('button', { name: 'Stop', exact: true })).toBeDisabled();
  await expect(dialog.getByRole('button', { name: 'Reset changes', exact: true })).toBeEnabled();
  await save.click();
  await confirmation.getByRole('button', { name: 'Save and restart', exact: true }).click();
  await expect.poll(() => state.pending.length).toBe(1);
  await app.release();
  await expect(dialog).toHaveCount(0);
  expect(state.writes.map(write => write.body.revision)).toEqual([4, 4]);
  for (const write of state.writes) {
    expect(write.body.name).toBe('Updated Dispatch');
    expect(write.body.settings.ignore_encrypted_calls).toBe(true);
  }
});

test('declining a running-channel restart keeps the draft dirty and restores controls', async ({ page }) => {
  const { dialog, state } = await openEditor(page);
  const name = dialog.getByRole('textbox', { name: 'Name', exact: true });
  await name.fill('Unsaved Dispatch');
  await dialog.getByRole('button', { name: 'Save & restart', exact: true }).click();
  const confirmation = page.getByRole('alertdialog', { name: 'Restart running channel', exact: true });
  await confirmation.getByRole('button', { name: 'Keep editing', exact: true }).click();
  await expect(confirmation).toHaveCount(0);
  await expect(name).toBeEnabled();
  await expect(name).toHaveValue('Unsaved Dispatch');
  await expect(dialog.getByRole('button', { name: 'Save & restart', exact: true })).toBeEnabled();
  await expect(dialog.getByRole('alert')).toBeEmpty();
  await page.keyboard.press('Escape');
  const discard = page.getByRole('alertdialog', { name: 'Discard unsaved changes', exact: true });
  await expect(discard).toBeVisible();
  await discard.getByRole('button', { name: 'Keep editing', exact: true }).click();
  await expect(dialog).toBeVisible();
  expect(state.writes).toHaveLength(0);
});

test('a stale channel save keeps its draft until reloading the latest revision', async ({ page }) => {
  const app = await openEditor(page, 'STOPPED');
  const { dialog, state } = app;
  const name = dialog.getByRole('textbox', { name: 'Name', exact: true });
  await name.fill('Stale draft');
  await dialog.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect.poll(() => state.pending.length).toBe(1);
  state.revision = 5;
  await app.release(409);
  await expect(dialog.getByRole('alert')).toContainText('No changes were saved.');
  await expect(name).toHaveValue('Stale draft');
  const oldForm = await dialog.locator('form').elementHandle();
  await dialog.getByRole('button', { name: 'Reload current values', exact: true }).click();
  await expect(name).toHaveValue('County Dispatch');
  expect(await oldForm.evaluate(form => form.isConnected)).toBe(false);
  await name.fill('Current draft');
  await dialog.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect.poll(() => state.pending.length).toBe(1);
  expect(state.writes[1].body.revision).toBe(5);
  await app.release();
  await expect(dialog).toHaveCount(0);
});

test('channel Start holds all form controls and blocks concurrent Save, then restores locks after failure and retry', async ({ page }) => {
  const app = await openEditor(page, 'STOPPED');
  const { dialog, state } = app;
  const start = dialog.getByRole('button', { name: 'Start', exact: true });
  const locked = dialog.getByRole('checkbox', { name: 'Skip encrypted traffic channels (performance)', exact: true });
  await start.click();
  await expect.poll(() => state.pending.length).toBe(1);
  await expectMutationLocked(page, dialog);
  await expect(dialog.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled();
  await dialog.locator('form').evaluate(form => form.dispatchEvent(new Event('submit', { cancelable: true })));
  expect(state.writes).toHaveLength(1);
  await app.release(503);
  await expect(dialog.getByRole('alert')).toHaveText('Channel request failed. Try again.');
  await expect(start).toBeEnabled();
  await expect(locked).toBeDisabled();
  await start.click();
  await expect.poll(() => state.pending.length).toBe(1);
  await app.release();
  await expect(dialog.getByRole('button', { name: 'Stop', exact: true })).toBeEnabled();
  await expect(dialog.getByRole('textbox', { name: 'Name', exact: true })).toHaveValue('County Dispatch');
  await expect(locked).toBeDisabled();
  expect(state.writes.map(write => write.body)).toEqual(Array(2).fill({
    revision: 4, action: 'START', configuration_ids: [state.channel.configuration_id] }));
});
