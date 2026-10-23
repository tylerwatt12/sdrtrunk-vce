'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const guideDismissalScope = 'channel-form-workflows-fixture';
test.beforeEach(async ({ page }) => {
  const { guideStorageKey } = await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/guides/persistence.js')).href);
  const keys = ['welcome', 'channel-setup'].map(id => guideStorageKey('channel-admin', guideDismissalScope, id));
  await page.addInitScript(completed => {
    completed.forEach(key => localStorage.setItem(key, '1'));
  }, keys);
});

let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

async function openEditor(page, processingState = 'RUNNING', mode = 'edit') {
  const id = '00000000-0000-4000-8000-000000000031';
  const profile = { id: 'dmr', label: 'DMR', channel_kind: 'TRUNKED', alias_family: 'DMR',
    sections: [{ id: 'general', label: 'General', fields: [
      { path: 'name', label: 'Name', type: 'text', required: true },
      { path: 'system', label: 'System', type: 'text' },
      { path: 'site', label: 'Site', type: 'text' },
      { path: 'alias_list_id', label: 'Alias List', type: 'dynamic_select', required: true }
    ] }, { id: 'source', label: 'Source', fields: [
      { path: 'source.frequencies_hz', label: 'Control frequencies', type: 'frequency_list', required: true }
    ] }, { id: 'protocol', label: 'Decoder', fields: [
      { path: 'settings.ignore_encrypted_calls', label: 'Skip encrypted traffic channels (performance)',
        type: 'boolean', default: false }
    ] }] };
  const state = { revision: 4, editRevision: 'channel-v4', processingState, pending: [], writes: [], optionsReads: [], templateReads: 0, channel: {
    configuration_id: id, protocol_id: 'dmr', name: 'County Dispatch', system: 'County', site: 'North',
    alias_list_id: 1, source: { frequencies_hz: [451012500] }, settings: { ignore_encrypted_calls: true },
    frequency_map: [], event_logs: [], recorders: [], auxiliary_decoders: [] } };
  await page.route('**/api/v1/**', async route => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const data = value => route.fulfill({ json: { data: value } });
    if (pathname === '/api/v1/auth/session') return data({ configured: true, authenticated: true,
      username: 'channel-admin', guide_dismissal_scope: guideDismissalScope,
      tier: 'admin', primary: true, csrf_token: 'fixture-token',
      capabilities: { 'admin-channels': true } });
    if (pathname === '/api/v1/me/preferences') return route.fulfill({ json: { revision: 1, preferences: defaults } });
    if (pathname === '/api/v1/admin/channels/protocols') return data({
      voice_decryption_module_loaded: true, profiles: [profile] });
    if (pathname === '/api/v1/admin/channels/options') {
      state.optionsReads.push(state.revision);
      return data({ revision: state.revision, alias_lists: [
        { id: 1, name: 'Default DMR', family: 'DMR' },
        { id: 2, name: 'County DMR', family: 'DMR' }
      ], tuners: [] });
    }
    if (pathname === '/api/v1/admin/channels/protocols/dmr/template') {
      state.templateReads += 1;
      return data({ ...state.channel, configuration_id: null, name: 'DMR Channel', system: '', site: '' });
    }
    if (pathname === '/api/v1/admin/channels' && request.method() === 'GET') return data({
      revision: state.revision, channels: [{ ...state.channel, protocol_label: 'DMR', channel_kind: 'TRUNKED',
        frequencies_hz: [451012500], processing_state: state.processingState, editable: true }] });
    if ((pathname === '/api/v1/admin/channels' && request.method() === 'POST') ||
        (pathname === `/api/v1/admin/channels/${id}` && request.method() === 'PUT') ||
        (pathname === '/api/v1/admin/channels/actions' && request.method() === 'POST')) {
      const body = request.postDataJSON();
      state.writes.push({ pathname, method: request.method(), body });
      await new Promise(resolve => state.pending.push(async (status, code) => {
        if (status !== 200) await route.fulfill({ status, json: { error: {
          message: 'Channel request failed. Try again.',
          ...(code ? { code } : {}) } } });
        else {
          state.revision += 1;
          if (request.method() === 'PUT' || pathname === '/api/v1/admin/channels') {
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
    if (pathname === `/api/v1/admin/channels/${id}`) return data({ revision: state.revision, edit_revision: state.editRevision,
      channel: state.channel, processing_state: state.processingState });
    return route.fulfill({ status: 404, json: { error: { message: 'Unavailable in this browser fixture' } } });
  });
  await page.goto('/app.html?view=channel-setup');
  if (mode === 'create') {
    await page.getByRole('button', { name: 'New channel', exact: true }).click();
    await page.getByRole('dialog', { name: 'How would you like to create a channel?', exact: true })
      .getByRole('button', { name: 'Create a channel from scratch', exact: true }).click();
  } else {
    await page.locator(`tr[data-id="${id}"]`).getByRole('button', { name: state.channel.name, exact: true }).click();
  }
  const dialog = page.getByRole('dialog', {
    name: mode === 'create' ? 'Create Channel' : 'Edit County Dispatch', exact: true
  });
  await expect(dialog.getByRole('textbox', { name: 'Name', exact: true }))
    .toHaveValue(mode === 'create' ? 'DMR Channel' : 'County Dispatch');
  return { state, dialog, release: async (status = 200, code = status === 409 ? 'stale_channel' : null) =>
    state.pending.shift()(status, code) };
}

async function fillCreateDraft(dialog) {
  await dialog.getByRole('textbox', { name: 'Name', exact: true }).fill('County South Dispatch');
  await dialog.getByRole('textbox', { name: 'System', exact: true }).fill('County Radio');
  await dialog.getByRole('textbox', { name: 'Site', exact: true }).fill('South Ridge');
  await dialog.getByRole('combobox', { name: 'Alias List', exact: true }).selectOption('2');
  await dialog.getByRole('spinbutton', { name: 'Control frequencies in MHz', exact: true }).fill('452.125');
  await dialog.getByRole('button', { name: 'Add frequency', exact: true }).click();
  await dialog.getByRole('spinbutton', { name: 'Control frequencies in MHz', exact: true }).nth(1).fill('453.25');
}

async function expectCreateDraft(dialog) {
  await expect(dialog.getByRole('textbox', { name: 'Name', exact: true })).toHaveValue('County South Dispatch');
  await expect(dialog.getByRole('textbox', { name: 'System', exact: true })).toHaveValue('County Radio');
  await expect(dialog.getByRole('textbox', { name: 'Site', exact: true })).toHaveValue('South Ridge');
  await expect(dialog.getByRole('combobox', { name: 'Alias List', exact: true })).toHaveValue('2');
  const frequencies = dialog.getByRole('spinbutton', { name: 'Control frequencies in MHz', exact: true });
  await expect(frequencies).toHaveCount(2);
  await expect(frequencies.nth(0)).toHaveValue('452.125');
  await expect(frequencies.nth(1)).toHaveValue('453.25');
  await expect(dialog.getByRole('checkbox', {
    name: 'Skip encrypted traffic channels (performance)', exact: true
  })).toBeDisabled();
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

test('creating a channel ignores unrelated configuration changes and submits once without a catalog revision', async ({ page }) => {
  const app = await openEditor(page, 'STOPPED', 'create');
  const { dialog, state } = app;
  await fillCreateDraft(dialog);
  const optionsReads = state.optionsReads.length;
  state.revision = 20;
  await dialog.getByRole('button', { name: 'Create channel', exact: true }).click();
  await expect.poll(() => state.pending.length).toBe(1);
  await expectMutationLocked(page, dialog);
  expect(state.writes).toHaveLength(1);
  expect(state.writes[0].body).toMatchObject({
    protocol_id: 'dmr', name: 'County South Dispatch', system: 'County Radio', site: 'South Ridge',
    alias_list_id: '2', source: { frequencies_hz: [452125000, 453250000] },
    settings: { ignore_encrypted_calls: true }
  });
  expect(state.writes[0].body).not.toHaveProperty('revision');
  expect(state.writes[0].body).not.toHaveProperty('edit_revision');
  expect(state.optionsReads).toHaveLength(optionsReads);
  await app.release();
  await expect(dialog).toHaveCount(0);
});

test('a failed create retains every field and dirty-dismiss protection for a user retry', async ({ page }, testInfo) => {
  const app = await openEditor(page, 'STOPPED', 'create');
  const { dialog, state } = app;
  await fillCreateDraft(dialog);
  const oldForm = await dialog.locator('form').elementHandle();
  const create = dialog.getByRole('button', { name: 'Create channel', exact: true });
  const optionsReads = state.optionsReads.length;
  await create.click();
  await expect.poll(() => state.pending.length).toBe(1);
  await app.release(503);
  await expect(dialog.getByRole('alert')).toHaveText('Channel request failed. Try again.');
  await expectCreateDraft(dialog);
  await expect(create).toBeEnabled();
  expect(await oldForm.evaluate(form => form.isConnected)).toBe(true);
  expect(state.writes).toHaveLength(1);
  await page.screenshot({ path: testInfo.outputPath('create-failure.png'), fullPage: true });
  await page.keyboard.press('Escape');
  const discard = page.getByRole('alertdialog', { name: 'Discard unsaved changes', exact: true });
  await expect(discard).toBeVisible();
  await discard.getByRole('button', { name: 'Keep editing', exact: true }).click();
  await expectCreateDraft(dialog);
  state.revision = 30;
  await create.click();
  await expect.poll(() => state.pending.length).toBe(1);
  expect(state.writes[1].body).toEqual(state.writes[0].body);
  expect(state.optionsReads).toHaveLength(optionsReads);
  await app.release();
  await expect(dialog).toHaveCount(0);
});

test('a channel that starts after opening requires restart consent without refreshing the draft', async ({ page }) => {
  const app = await openEditor(page, 'STOPPED');
  const { dialog, state } = app;
  await dialog.getByRole('textbox', { name: 'Name', exact: true }).fill('Updated Dispatch');
  const save = dialog.getByRole('button', { name: 'Save changes', exact: true });
  await save.click();
  await expect.poll(() => state.pending.length).toBe(1);
  expect(state.writes[0].body.allow_restart).toBe(false);
  state.processingState = 'RUNNING';
  await app.release(409, 'restart_required');
  const confirmation = page.getByRole('alertdialog', { name: 'Restart running channel', exact: true });
  await expect(confirmation).toBeVisible();
  await confirmation.getByRole('button', { name: 'Save and restart', exact: true }).click();
  await expect.poll(() => state.pending.length).toBe(1);
  expect(state.writes[1].body).toEqual({ ...state.writes[0].body, allow_restart: true });
  await expectMutationLocked(page, dialog);
  await app.release();
  await expect(dialog).toHaveCount(0);
});

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
  expect(state.writes.map(write => write.body.edit_revision)).toEqual(['channel-v4', 'channel-v4']);
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
  await expect(dialog.locator('.channel-editor-message')).toBeEmpty();
  await page.keyboard.press('Escape');
  const discard = page.getByRole('alertdialog', { name: 'Discard unsaved changes', exact: true });
  await expect(discard).toBeVisible();
  await discard.getByRole('button', { name: 'Keep editing', exact: true }).click();
  await expect(dialog).toBeVisible();
  expect(state.writes).toHaveLength(0);
});

test('a conflicting channel edit retains the original baseline and every draft field', async ({ page }) => {
  const app = await openEditor(page, 'STOPPED');
  const { dialog, state } = app;
  const name = dialog.getByRole('textbox', { name: 'Name', exact: true });
  const original = structuredClone(state.channel);
  await name.fill('My Dispatch');
  await dialog.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect.poll(() => state.pending.length).toBe(1);
  state.editRevision = 'channel-v5';
  state.channel.name = 'Their Dispatch';
  await app.release(409);
  await expect(dialog.getByRole('alert')).toContainText('Your entry is still here.');
  await expect(name).toHaveValue('My Dispatch');
  await expect(dialog.getByRole('button', { name: 'Reload current values', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('link', { name: 'View current channel', exact: true })).toHaveAttribute('target', '_blank');
  expect(state.writes[0].body.edit_revision).toBe('channel-v4');
  expect(state.writes[0].body.original).toEqual(original);
  expect(state.writes[0].body).not.toHaveProperty('revision');
  expect(state.writes).toHaveLength(1);
  await page.keyboard.press('Escape');
  const discard = page.getByRole('alertdialog', { name: 'Discard unsaved changes', exact: true });
  await expect(discard).toBeVisible();
  await discard.getByRole('button', { name: 'Keep editing', exact: true }).click();
  await expect(name).toHaveValue('My Dispatch');
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
