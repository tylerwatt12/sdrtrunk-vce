'use strict';
const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
let defaults;
const manifest = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../../../../src/main/resources/channel-protocols.json')));
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

async function openEditor(page, options = {}) {
  const protocol = options.protocol || 'p25-phase1';
  const profile = manifest.profiles.find((entry) => entry.id === protocol);
  const settings = Object.fromEntries(profile.sections.flatMap((section) => section.fields)
    .filter((field) => field.path.startsWith('settings.') && Object.hasOwn(field, 'default'))
    .map((field) => [field.path.substring(9), field.default]));
  const channel = { configuration_id: 'enrichment', protocol_id: protocol,
    system: 'P25 BEE00-348', site: 'Site 01-02', name: 'My control channel',
    radio_resolve_id: '30bf9438-6d5b-4a44-90b2-22b00efa731a', alias_list_id: 1,
    source: { source_type: options.remote ? 'REMOTE' : 'TUNER', frequencies_hz: [851012500],
      ...(options.remote ? { sender_id: 'test-sender', feed_id: 'test-feed' } : {}) },
    settings, event_logs: ['CALL_EVENT'], frequency_map: [], recorders: [], auxiliary_decoders: [],
    observed: { p25_site_identity: options.identity === false ? {} : { wacn: 0xBEE00, system: 0x348, rfss: 1, site: 2 } } };
  const original = structuredClone(channel);
  const state = { channel, original, revision: 1, previews: [], applies: [], puts: [],
    previewState: 'matched', failApply: false, waitApply: null,
    changes: [
      { field: 'system', before: channel.system, after: 'Countywide Public Safety Radio System' },
      { field: 'site', before: channel.site, after: 'North County Simulcast' },
      { field: 'name', before: channel.name, after: 'Countywide Public Safety Radio System · North County Simulcast' }
    ] };
  const preferences = structuredClone(defaults);
  preferences.appearance.theme = options.theme || 'light';
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const respond = (data) => route.fulfill({ json: { data } });
    if (pathname === '/api/v1/auth/session') return respond({ configured: true, authenticated: true,
      username: 'operator', tier: 'admin', primary: true, csrf_token: 'test-token', capabilities: { 'admin-channels': true } });
    if (pathname === '/api/v1/me/preferences') return route.fulfill({ json: { revision: 1, preferences } });
    if (pathname === '/api/v1/admin/channels') return respond({ revision: state.revision,
      channels: [{ ...channel, channel_kind: profile.channel_kind, protocol_label: profile.label,
        frequencies_hz: channel.source.frequencies_hz, processing_state: options.running ? 'RUNNING' : 'STOPPED', editable: true }] });
    if (pathname === '/api/v1/admin/channels/enrichment') {
      if (request.method() === 'PUT') state.puts.push(request.postDataJSON());
      if (state.waitRefresh && state.applies.length > 0) await state.waitRefresh;
      return respond({ revision: state.revision, channel, processing_state: options.running ? 'RUNNING' : 'STOPPED' });
    }
    if (pathname === '/api/v1/admin/channels/options') return respond({ revision: state.revision,
      alias_lists: [{ id: 1, name: 'My aliases', family: 'P25' }], tuners: [] });
    if (pathname === '/api/v1/admin/channels/protocols') return respond({ profiles: [profile] });
    if (pathname === '/api/v1/admin/radioreference') {
      if (options.waitAccount) await options.waitAccount;
      return respond({ account: { state: options.account || 'VALID_PREMIUM' } });
    }
    if (pathname.endsWith('/channels/enrichment/preview')) {
      state.previews.push(request.postDataJSON());
      return respond({ preview_id: state.previewState === 'matched' ? `preview-${state.previews.length}` : null,
        configuration_id: channel.configuration_id, revision: state.revision, state: state.previewState,
        reason: state.previewState === 'no_match' ? 'No matching RadioReference site was found.' :
          state.previewState === 'ambiguous' ? 'More than one RadioReference site matches this identity.' :
            'RadioReference is unavailable. Try again.', changes: state.changes,
        running: options.running === true,
        system_url: 'https://www.radioreference.com/db/sid/123', site_url: 'https://www.radioreference.com/db/site/456' });
    }
    if (pathname.endsWith('/channels/enrichment/apply')) {
      const payload = request.postDataJSON();
      state.applies.push(payload);
      if (state.waitApply) await state.waitApply;
      if (state.failApply) return route.fulfill({ status: 409, json: { error: { message: 'This comparison has expired.' } } });
      for (const change of state.changes) if (change.field !== 'name' || payload.replace_name) channel[change.field] = change.after;
      state.revision++;
      return respond({ configuration_id: channel.configuration_id, revision: state.revision });
    }
    return route.fulfill({ status: 404, json: { error: { message: 'Unavailable in this browser test.' } } });
  });
  await page.goto('/app.html?view=channel-setup&channel=enrichment');
  state.editor = page.locator('.channel-editor-modal');
  await expect(state.editor).toBeVisible();
  state.openPreview = async () => {
    await state.editor.getByRole('button', { name: 'Update from RadioReference', exact: true }).click();
    const confirm = page.getByRole('alertdialog', { name: 'Update from RadioReference' });
    await expect(confirm.getByRole('button', { name: 'Cancel', exact: true })).toBeFocused();
    await confirm.getByRole('button', { name: 'Look up channel', exact: true }).click();
    const preview = page.getByRole('dialog', { name: 'Review RadioReference names', exact: true });
    await expect(preview).toBeVisible();
    await expect(preview.getByText('Looking up this channel in RadioReference…')).toHaveCount(0);
    return preview;
  };
  return state;
}

test('remote P25 compares names, preserves cancelled choices and saves only reviewed names', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const state = await openEditor(page, { remote: true });
  let preview = await state.openPreview();
  await expect(preview.getByRole('checkbox', { name: 'Replace channel name' })).toHaveCount(0);
  await expect(preview.getByRole('heading', { name: 'Name', exact: true })).toBeVisible();
  await expect(preview.getByText('P25 BEE00-348', { exact: true })).toBeVisible();
  await expect(preview.getByText('Countywide Public Safety Radio System', { exact: true })).toBeVisible();
  await preview.screenshot({ path: test.info().outputPath('enrichment-light-desktop.png') });
  await preview.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(state.editor.getByLabel('Name', { exact: true })).toHaveValue(state.original.name);
  expect(state.applies).toHaveLength(0);
  preview = await state.openPreview();
  await preview.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(preview).toHaveCount(0);
  await expect(state.editor.getByLabel('System', { exact: true })).toHaveValue(state.changes[0].after);
  await expect(state.editor.getByLabel('Name', { exact: true })).toHaveValue(state.changes[2].after);
  await expect(state.editor.getByRole('button', { name: 'Start', exact: true })).toBeEnabled();
  expect(await state.editor.evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);
  await state.editor.screenshot({ path: test.info().outputPath('enrichment-editor-light-desktop.png') });
  expect(state.previews[0]).toEqual({ configuration_id: 'enrichment', revision: 1 });
  expect(state.applies[0]).toEqual({ preview_id: 'preview-2', replace_name: true });
  expect(state.puts).toHaveLength(0);
  for (const field of ['source', 'radio_resolve_id', 'alias_list_id', 'settings', 'recorders', 'event_logs']) {
    expect(state.channel[field]).toEqual(state.original[field]);
  }
});

test('name-only changes can be saved and saving locks duplicate submits and dismissal on a dark phone', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await openEditor(page, { theme: 'dark', running: true });
  state.changes = state.changes.filter((change) => change.field === 'name');
  const preview = await state.openPreview();
  const save = preview.getByRole('button', { name: 'Save', exact: true });
  await expect(preview.getByRole('heading', { name: 'Name', exact: true })).toBeVisible();
  await expect(save).toBeEnabled();
  await expect(preview.getByText('Saving briefly stops this channel and restores its running state.')).toBeVisible();
  expect(await preview.evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);
  await preview.screenshot({ path: test.info().outputPath('enrichment-dark-phone.png') });
  let release;
  state.waitApply = new Promise((resolve) => { release = resolve; });
  await save.click();
  await expect.poll(() => state.applies.length).toBe(1);
  await expect(save).toBeDisabled();
  await expect(preview.getByRole('button', { name: 'Cancel', exact: true })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(preview).toBeVisible();
  let releaseRefresh;
  state.waitRefresh = new Promise((resolve) => { releaseRefresh = resolve; });
  release();
  await expect.poll(() => state.revision).toBe(2);
  await expect(preview.getByRole('button', { name: 'Cancel', exact: true })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(preview).toBeVisible();
  releaseRefresh();
  await expect(preview).toHaveCount(0);
  await expect(state.editor.getByLabel('Name', { exact: true })).toHaveValue(state.changes[0].after);
  expect(state.applies).toEqual([{ preview_id: 'preview-1', replace_name: true }]);
});

test('dirty editor blocks lookup, and initial confirmation cancellation preserves it', async ({ page }) => {
  const state = await openEditor(page);
  const update = state.editor.getByRole('button', { name: 'Update from RadioReference', exact: true });
  await state.editor.getByLabel('Name', { exact: true }).fill('My unsaved draft');
  await update.click();
  await expect(state.editor.getByText('Save or reset your changes before updating from RadioReference.')).toBeVisible();
  expect(state.previews).toHaveLength(0);
  await state.editor.getByRole('button', { name: 'Reset changes', exact: true }).click();
  await update.click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(state.previews).toHaveLength(0);
  await expect(state.editor.getByLabel('Name', { exact: true })).toHaveValue(state.original.name);
});

for (const [label, options, message] of [
  ['signed out', { account: 'SIGNED_OUT' }, 'Sign in to RadioReference with a Premium account'],
  ['expired Premium', { account: 'EXPIRED_PREMIUM' }, 'A RadioReference Premium subscription is required.'],
  ['missing identity', { identity: false }, 'Run this channel until it learns its WACN']
]) test(`enrichment explains unavailable ${label}`, async ({ page }) => {
  const state = await openEditor(page, options);
  await expect(state.editor.getByRole('button', { name: 'Update from RadioReference', exact: true })).toBeDisabled();
  await expect(state.editor.getByText(message, { exact: false })).toBeVisible();
});

test('conventional P25 does not offer trunked enrichment', async ({ page }) => {
  const state = await openEditor(page, { protocol: 'p25-conventional' });
  await expect(state.editor.getByRole('button', { name: 'Update from RadioReference', exact: true })).toHaveCount(0);
});

test('lookup states and stale saves keep the parent intact and require a fresh review', async ({ page }) => {
  const state = await openEditor(page);
  state.previewState = 'ambiguous';
  const preview = await state.openPreview();
  await expect(preview.getByText('More than one RadioReference site matches this identity.')).toBeVisible();
  await expect(preview.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  state.previewState = 'no_match';
  await preview.getByRole('button', { name: 'Look up again', exact: true }).click();
  await expect(preview.getByText('No matching RadioReference site was found.')).toBeVisible();
  state.previewState = 'unavailable';
  await preview.getByRole('button', { name: 'Look up again', exact: true }).click();
  await expect(preview.getByText('RadioReference is unavailable. Try again.')).toBeVisible();
  state.previewState = 'matched';
  await preview.getByRole('button', { name: 'Look up again', exact: true }).click();
  await expect(preview.getByRole('button', { name: 'Save', exact: true })).toBeEnabled();
  state.failApply = true;
  await preview.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(preview.getByRole('alert')).toContainText('This comparison has expired.');
  await expect(preview.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  await expect(preview.getByRole('button', { name: 'Look up again', exact: true })).toBeEnabled();
  state.previewState = 'no_changes';
  state.revision = 2;
  await preview.getByRole('button', { name: 'Look up again', exact: true }).click();
  await expect(preview.getByText('This channel is already up to date.')).toBeVisible();
  expect(state.previews.at(-1).revision).toBe(2);
  await preview.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(state.editor.getByLabel('System', { exact: true })).toHaveValue(state.original.system);
});


test('location requirement links to existing RadioReference settings', async ({ page }) => {
  const state = await openEditor(page);
  state.previewState = 'location_required';
  const preview = await state.openPreview();
  const link = preview.getByRole('link', { name: 'Open RadioReference settings' });
  await expect(link).toHaveAttribute('href', /view=radioreference/);
  await expect(link).toHaveAttribute('target', '_blank');
  await expect(preview.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  await preview.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(state.editor.getByRole('button', { name: 'Update from RadioReference', exact: true })).toBeFocused();
});


test('a slow account check leaves the editor usable and preserves an in-progress draft', async ({ page }) => {
  let release;
  const waitAccount = new Promise((resolve) => { release = resolve; });
  const state = await openEditor(page, { waitAccount });
  const update = state.editor.getByRole('button', { name: 'Update from RadioReference', exact: true });
  await expect(update).toBeDisabled();
  await expect(state.editor.getByText('Checking RadioReference account…')).toBeVisible();
  await state.editor.getByLabel('Name', { exact: true }).fill('My draft while RadioReference loads');
  release();
  await expect(update).toBeEnabled();
  await expect(state.editor.getByLabel('Name', { exact: true })).toHaveValue('My draft while RadioReference loads');
  await update.click();
  await expect(state.editor.getByText('Save or reset your changes before updating from RadioReference.')).toBeVisible();
  expect(state.previews).toHaveLength(0);
});
