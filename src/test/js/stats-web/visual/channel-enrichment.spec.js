'use strict';
const { expect, test } = require('@playwright/test');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const manifest = require('../../../../../src/main/resources/channel-protocols.json');
const noMatchReason = 'No RadioReference record matches this frequency and radio type in the selected region.';

function template(protocolId) {
  const profile = manifest.profiles.find(entry => entry.id === protocolId);
  return { protocol_id: protocolId, name: 'Channel', system: '', site: '',
    alias_list_id: profile.alias_family === 'NBFM' ? 1 : 2,
    source: { frequencies_hz: [154920000], preferred_tuner: null },
    settings: Object.fromEntries(profile.sections.flatMap(section => section.fields)
      .filter(field => field.path.startsWith('settings.') && Object.hasOwn(field, 'default'))
      .map(field => [field.path.slice(9), field.default])),
    frequency_map: [], event_logs: [], recorders: [], auxiliary_decoders: [], observed: {} };
}

async function openEditor(page, options = {}) {
  await installSiteStyleApplication(page, options.theme || 'light');
  const channel = { ...template(options.protocol || 'nbfm'),
    ...(options.create ? {} : { configuration_id: 'saved-channel' }) };
  const state = { channel, previews: [], writes: [], mode: options.mode || 'choose_record',
    account: options.account || 'VALID_PREMIUM', rejectSave: false };
  const names = { system: 'Lorain County', site: 'Fire Departments', name: 'Fire SE Dispatch' };
  await page.route('**/api/v1/**', async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const respond = data => route.fulfill({ json: { data } });
    if (path === '/api/v1/admin/channels' || path === '/api/v1/channel-catalog') {
      if (request.method() === 'POST') {
        state.writes.push(request.postDataJSON());
        if (state.rejectSave) return route.fulfill({ status: 409, json: { error: {
          code: 'configuration_conflict', message: 'A channel already uses 154.920 MHz.' } } });
      }
      return respond({ revision: 1, channels: options.catalogChannels || (options.create ? [] : [{ ...channel,
        name: channel.name, frequencies_hz: channel.source.frequencies_hz,
        protocol_label: 'NBFM', channel_kind: 'CONVENTIONAL', processing_state: 'STOPPED', editable: true }]) });
    }
    if (path === '/api/v1/admin/channels/saved-channel') {
      if (request.method() === 'PUT') state.writes.push(request.postDataJSON());
      return respond({ revision: 1, edit_revision: 'fixture-edit', channel, processing_state: 'STOPPED' });
    }
    if (path === '/api/v1/admin/channels/protocols') return respond(manifest);
    if (path.endsWith('/template')) return respond(template(path.split('/').at(-2)));
    if (path === '/api/v1/admin/channels/options') return respond({ revision: 1, tuners: [], alias_lists: [
      { id: 1, name: 'Default Analog', family: 'NBFM' }, { id: 2, name: 'Default P25', family: 'P25' },
      { id: 3, name: 'Default DMR', family: 'DMR' }, { id: 4, name: 'Default NXDN', family: 'NXDN' },
      ...(options.additionalAliasLists || [])
    ] });
    if (path === '/api/v1/admin/radioreference') return respond({ account: { state: state.account }, country_id: 1, state_id: 39 });
    if (path === '/api/v1/admin/radioreference/countries') return respond({ items: [{ id: 1, name: 'United States' }] });
    if (path === '/api/v1/admin/radioreference/states') return respond({ items: [{ id: 39, name: 'Ohio' }] });
    if (path.endsWith('/channels/enrichment/preview')) {
      expect(request.headers()['x-csrf-token']).toBeTruthy();
      const body = request.postDataJSON();
      state.previews.push(body);
      if (state.mode !== 'choose_record' && state.mode !== 'matched') return respond({
        state: state.mode, reason: state.mode === 'no_match' ? noMatchReason :
          'No matching RadioReference record was found.', changes: [] });
      if (!body.candidate_id && state.mode === 'choose_record') return respond({ state: 'choose_record', lookup_id: 'lookup-1', candidates: [
        { candidate_id: 'record-1', name: names.name, context: 'LC SE FDISP · Lorain County · FMN', protocol_id: 'nbfm' },
        { candidate_id: 'record-2', name: 'Other dispatch', context: 'OTHER · Neighbor County · FMN', protocol_id: 'nbfm' }
      ] });
      return respond({ state: 'matched', preview_id: 'preview-1', names,
        rr_system_id: options.rrSystemId || null, preferred_alias_list_id: options.preferredAliasListId || null,
        system_url: 'https://www.radioreference.com/db/subcat/16320',
        changes: Object.entries(names).map(([field, after]) => ({ field, before: body.channel[field] || '', after })) });
    }
    if (request.method() !== 'GET' && !path.startsWith('/api/v1/me/')) state.writes.push({ path, body: request.postDataJSON() });
    return route.fallback();
  });
  await page.goto(options.create ? '/app.html?view=channel-setup&create=1&frequency_hz=154920000' :
    '/app.html?view=channel-setup&channel=saved-channel');
  state.editor = page.locator('.channel-editor-modal');
  if (options.blocked) return state;
  await expect(state.editor).toBeVisible();
  state.openPreview = async () => {
    await state.editor.getByRole('button', { name: 'Update from RadioReference', exact: true }).click();
    const preview = page.getByRole('dialog', { name: 'Review RadioReference names', exact: true });
    await expect(preview).toBeVisible();
    await expect(preview.getByText('Looking up this channel in RadioReference…', { exact: true })).toHaveCount(0);
    return preview;
  };
  return state;
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`conventional names select a record and apply to an unsaved editor in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const state = await openEditor(page, { create: true, theme });
    await state.editor.locator('.channel-protocol-select select').selectOption('nbfm');
    await expect(state.editor.locator('[data-channel-path="source.frequencies_hz"] input').first()).toHaveValue('154.92');
    await state.editor.getByLabel('Name', { exact: true }).fill('My unsaved channel');
    const before = await state.editor.locator('[data-channel-path^="settings."]').evaluateAll(controls =>
      controls.map(c => [c.dataset.channelPath, c.type === 'checkbox' ? c.checked : c.value]));
    let preview = await state.openPreview();
    await expect(preview.getByRole('button', { name: 'Use these names' })).toBeDisabled();
    await expect(preview.getByLabel('RadioReference channel')).toHaveValue('');
    await expect(preview.getByLabel('RadioReference channel')).toContainText('LC SE FDISP');
    await preview.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(state.editor.getByLabel('Name', { exact: true })).toHaveValue('My unsaved channel');
    preview = await state.openPreview();
    await preview.getByLabel('RadioReference channel').selectOption('record-1');
    await expect(preview.getByRole('table').getByRole('rowheader')).toHaveText(['System', 'Site', 'Channel name']);
    await expect(preview.getByRole('link', { name: 'Source' })).toHaveAttribute('target', '_blank');
    await preview.getByRole('button', { name: 'Use these names' }).click();
    await expect(preview).toHaveCount(0);
    await expect(state.editor.getByLabel('System', { exact: true })).toHaveValue('Lorain County');
    await expect(state.editor.getByLabel('Site', { exact: true })).toHaveValue('Fire Departments');
    await expect(state.editor.getByLabel('Name', { exact: true })).toHaveValue('Fire SE Dispatch');
    expect(await state.editor.locator('[data-channel-path^="settings."]').evaluateAll(controls =>
      controls.map(c => [c.dataset.channelPath, c.type === 'checkbox' ? c.checked : c.value]))).toEqual(before);
    expect(state.writes).toHaveLength(0);
    await state.editor.screenshot({ path: test.info().outputPath(`names-${theme}-${width}.png`) });
    await state.editor.getByRole('button', { name: 'Create channel', exact: true }).click();
    await expect.poll(() => state.writes.length).toBe(1);
    expect(state.writes[0].name).toBe('Fire SE Dispatch');
    expect(state.writes[0].source.frequencies_hz).toEqual([154920000]);
    expect(state.writes[0].protocol_id).toBe('nbfm');
    expect(state.writes[0].reject_existing_frequency).toBe(true);
  });
}

test('manual handoff keeps frequency and protocol defaults when choosing every radio type', async ({ page }) => {
  const state = await openEditor(page, { create: true });
  for (const protocol of ['nbfm', 'p25-conventional', 'dmr', 'nxdn', 'p25-phase1', 'am']) {
    await state.editor.locator('.channel-protocol-select select').selectOption(protocol);
    const sources = state.editor.locator('[data-channel-path="source.frequencies_hz"]');
    await expect(sources.locator('input').first()).toHaveValue('154.92');
    await expect(state.editor.getByLabel('Alias List', { exact: true })).toHaveValue(
      ['am', 'nbfm'].includes(protocol) ? '1' : protocol === 'dmr' ? '3' : protocol === 'nxdn' ? '4' : '2');
  }
  expect(state.writes).toHaveLength(0);
});

test('existing conventional editor keeps names as a draft until normal Save', async ({ page }) => {
  const state = await openEditor(page);
  const preview = await state.openPreview();
  await preview.getByLabel('RadioReference channel').selectOption('record-1');
  await preview.getByRole('button', { name: 'Use these names' }).click();
  expect(state.writes).toHaveLength(0);
  await state.editor.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect.poll(() => state.writes.length).toBe(1);
  expect(state.writes[0].name).toBe('Fire SE Dispatch');
  expect({ ...state.channel.settings, ...state.writes[0].settings }).toEqual(state.channel.settings);
});

for (const create of [true, false]) {
  test(`names-only RadioReference lookup preserves an explicit Alias List in the ${create ? 'manual Spectrum draft' : 'existing editor'}`, async ({ page }) => {
    const state = await openEditor(page, { create, protocol: 'p25-phase1', mode: 'matched',
      rrSystemId: 123, preferredAliasListId: 2,
      additionalAliasLists: [{ id: 9, name: 'My selected event listeners', family: 'P25' }] });
    if (create) await state.editor.locator('.channel-protocol-select select').selectOption('p25-phase1');
    const aliases = state.editor.getByLabel('Alias List', { exact: true });
    await aliases.selectOption('9');
    const before = await state.editor.locator('[data-channel-path^="settings."]').evaluateAll(controls =>
      controls.map(control => [control.dataset.channelPath, control.type === 'checkbox' ? control.checked : control.value]));
    const preview = await state.openPreview();
    await preview.getByRole('button', { name: 'Use these names', exact: true }).click();
    await expect(state.editor.getByLabel('Name', { exact: true })).toHaveValue('Fire SE Dispatch');
    await expect(aliases).toHaveValue('9');
    expect(await state.editor.locator('[data-channel-path^="settings."]').evaluateAll(controls =>
      controls.map(control => [control.dataset.channelPath, control.type === 'checkbox' ? control.checked : control.value]))).toEqual(before);
    expect(state.writes).toHaveLength(0);
    await state.editor.getByRole('button', { name: create ? 'Create channel' : 'Save changes', exact: true }).click();
    await expect.poll(() => state.writes.length).toBe(1);
    expect(Number(state.writes[0].alias_list_id)).toBe(9);
  });
}

test('lookup failures allow retry and cancellation without altering a dirty parent', async ({ page }) => {
  const state = await openEditor(page, { mode: 'no_match' });
  await state.editor.getByLabel('Name', { exact: true }).fill('Keep my name');
  const preview = await state.openPreview();
  await expect(preview.getByText(noMatchReason, { exact: true })).toBeVisible();
  await expect(preview.getByRole('button', { name: 'Use these names' })).toBeDisabled();
  state.mode = 'matched';
  await preview.getByRole('button', { name: 'Look up again' }).click();
  await expect(preview.getByRole('button', { name: 'Use these names' })).toBeEnabled();
  await preview.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(state.editor.getByLabel('Name', { exact: true })).toHaveValue('Keep my name');
  expect(state.writes).toHaveLength(0);
});

test('empty NBFM 162.500 MHz lookup in Ohio shows no match and preserves the new channel draft', async ({ page }, testInfo) => {
  const state = await openEditor(page, { create: true, mode: 'no_match' });
  await state.editor.locator('.channel-protocol-select select').selectOption('nbfm');
  const frequency = state.editor.locator('[data-channel-path="source.frequencies_hz"] input').first();
  await frequency.fill('162.5');
  await state.editor.getByLabel('System', { exact: true }).fill('Local weather');
  await state.editor.getByLabel('Site', { exact: true }).fill('My area');
  await state.editor.getByLabel('Name', { exact: true }).fill('Weather 162.500');
  const settings = await state.editor.locator('[data-channel-path^="settings."]').evaluateAll(controls =>
    controls.map(control => [control.dataset.channelPath, control.type === 'checkbox' ? control.checked : control.value]));

  const preview = await state.openPreview();
  const noMatch = preview.getByText(noMatchReason, { exact: true });
  await expect(noMatch).toBeVisible();
  await expect(preview).not.toContainText(/unavailable|lookup failed/i);
  await expect(preview.getByRole('button', { name: 'Use these names', exact: true })).toBeDisabled();
  await expect(preview.getByRole('button', { name: 'Look up again', exact: true })).toBeEnabled();
  await expect(preview.getByLabel('RadioReference state or province')).toHaveValue('39');
  await preview.screenshot({ path: testInfo.outputPath('empty-frequency-result.png') });
  expect(state.previews).toHaveLength(1);
  expect(state.previews[0].state_id).toBe(39);
  expect(state.previews[0].channel.protocol_id).toBe('nbfm');
  expect(state.previews[0].channel.source.frequencies_hz).toEqual([162500000]);
  expect(state.previews[0]).not.toHaveProperty('configuration_id');

  await preview.getByRole('button', { name: 'Look up again', exact: true }).click();
  await expect.poll(() => state.previews.length).toBe(2);
  await expect(noMatch).toBeVisible();
  await expect(preview.getByRole('button', { name: 'Use these names', exact: true })).toBeDisabled();
  await expect(preview.getByRole('button', { name: 'Look up again', exact: true })).toBeEnabled();
  await preview.getByRole('button', { name: 'Cancel', exact: true }).click();

  await expect(preview).toHaveCount(0);
  await expect(state.editor.getByLabel('System', { exact: true })).toHaveValue('Local weather');
  await expect(state.editor.getByLabel('Site', { exact: true })).toHaveValue('My area');
  await expect(state.editor.getByLabel('Name', { exact: true })).toHaveValue('Weather 162.500');
  await expect(frequency).toHaveValue('162.5');
  await expect(state.editor.getByLabel('Alias List', { exact: true })).toHaveValue('1');
  expect(await state.editor.locator('[data-channel-path^="settings."]').evaluateAll(controls =>
    controls.map(control => [control.dataset.channelPath, control.type === 'checkbox' ? control.checked : control.value]))).toEqual(settings);
  await expect(state.editor.getByRole('button', { name: 'Create channel', exact: true })).toBeEnabled();
  expect(state.writes).toHaveLength(0);
});

for (const account of ['SIGNED_OUT', 'EXPIRED_PREMIUM']) test(`manual creation remains usable with RadioReference ${account}`, async ({ page }) => {
  const state = await openEditor(page, { create: true, account });
  await expect(state.editor.getByRole('button', { name: 'Update from RadioReference', exact: true })).toBeHidden();
  await expect(state.editor.getByRole('button', { name: 'Create channel', exact: true })).toBeEnabled();
});


test('duplicate save failure preserves the populated draft and permits correction', async ({ page }) => {
  const state = await openEditor(page, { create: true });
  await state.editor.getByLabel('Name', { exact: true }).fill('My channel');
  state.rejectSave = true;
  await state.editor.getByRole('button', { name: 'Create channel', exact: true }).click();
  await expect(state.editor).toContainText('A channel already uses 154.920 MHz.');
  await expect(state.editor.getByLabel('Name', { exact: true })).toHaveValue('My channel');
  await expect(state.editor.getByRole('button', { name: 'Create channel', exact: true })).toBeEnabled();
});

test('Spectrum manual handoff rejects an existing local frequency before opening the editor', async ({ page }) => {
  const state = await openEditor(page, { create: true, blocked: true, catalogChannels: [
    { configuration_id: 'local-channel', name: 'Local dispatch', frequencies_hz: [154920000],
      protocol_label: 'NBFM', channel_kind: 'CONVENTIONAL', processing_state: 'STOPPED', editable: true }
  ] });
  await expect(page.getByText('A channel already uses 154.92 MHz.', { exact: true })).toBeVisible();
  await expect(state.editor).toHaveCount(0);
  expect(state.writes).toHaveLength(0);
});

test('a remote stream on the same frequency permits a local Spectrum draft', async ({ page }) => {
  const state = await openEditor(page, { create: true, catalogChannels: [
    { configuration_id: 'remote-channel', name: 'Remote dispatch', frequencies_hz: [154920000],
      protocol_label: 'NBFM', channel_kind: 'CONVENTIONAL', processing_state: 'STOPPED', editable: false,
      remote_origin: { remote: true } }
  ] });
  await expect(state.editor.locator('[data-channel-path="source.frequencies_hz"] input').first()).toHaveValue('154.92');
  expect(state.writes).toHaveLength(0);
});

test('verified P25 names also apply to the existing editor draft without separate persistence', async ({ page }) => {
  const state = await openEditor(page, { protocol: 'p25-phase1', mode: 'matched' });
  const preview = await state.openPreview();
  await expect(preview.getByLabel('RadioReference channel')).toHaveCount(0);
  await preview.getByRole('button', { name: 'Use these names' }).click();
  await expect(state.editor.getByLabel('Name', { exact: true })).toHaveValue('Fire SE Dispatch');
  expect(state.writes).toHaveLength(0);
  expect(state.previews[0].configuration_id).toBe('saved-channel');
  expect(state.previews[0].channel.protocol_id).toBe('p25-phase1');
});
