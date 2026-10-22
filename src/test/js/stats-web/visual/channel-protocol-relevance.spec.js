'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

test.setTimeout(12000);

const root = path.resolve(__dirname, '../../../../..');
const catalog = JSON.parse(fs.readFileSync(path.join(root, 'src/main/resources/channel-protocols.json'), 'utf8'));
let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.join(root,
    'stats-web/assets/core/preference-schema.js')).href)).defaults;
});

async function openEditor(page, protocolId, theme) {
  const profile = catalog.profiles.find((entry) => entry.id === protocolId);
  const preferences = structuredClone(defaults);
  preferences.appearance.theme = theme;
  const settings = Object.fromEntries(profile.sections.flatMap((section) => section.fields)
    .filter((field) => field.path.startsWith('settings.') && Object.hasOwn(field, 'default'))
    .map((field) => [field.path.substring(9), field.default]));
  settings.channel_mode = 'TRUNKED';
  const channel = { configuration_id: 'protocol-editor', protocol_id: profile.id,
    name: 'County Dispatch', system: 'County', site: 'North', alias_list_id: 1,
    source: { frequencies_hz: [451012500], rotation_delay_ms: 2000 }, settings,
    event_logs: ['CALL_EVENT', 'TRAFFIC_CALL_EVENT'], recorders: ['BASEBAND', 'TRAFFIC_BASEBAND'],
    auxiliary_decoders: [], frequency_map: [] };
  const writes = [];
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const data = (value) => route.fulfill({ json: { data: value } });
    if (pathname === '/api/v1/auth/session') return data({ configured: true, authenticated: true,
      username: 'operator', tier: 'admin', primary: true, csrf_token: 'fixture-token',
      capabilities: { 'admin-channels': true } });
    if (pathname === '/api/v1/me/preferences') return route.fulfill({ json: { revision: 1, preferences } });
    if (pathname === '/api/v1/admin/channels/protocols') return data({ profiles: [profile] });
    if (pathname === '/api/v1/admin/channels/options') return data({ revision: 1,
      alias_lists: [{ id: 1, name: `Default ${profile.alias_family}`, family: profile.alias_family }], tuners: [] });
    if (pathname === '/api/v1/admin/channels') return data({ revision: 1, channels: [{
      ...channel, protocol_label: profile.label, channel_kind: 'TRUNKED',
      frequencies_hz: channel.source.frequencies_hz, processing_state: 'STOPPED', editable: true
    }] });
    if (pathname === '/api/v1/admin/channels/protocol-editor' && request.method() === 'PUT') {
      writes.push(request.postDataJSON());
      if (writes.length === 1) return route.fulfill({ status: 503,
        json: { error: { message: 'Save failed. Try again.' } } });
      return data({ revision: 2 });
    }
    if (pathname === '/api/v1/admin/channels/protocol-editor') return data({ revision: 1,
      edit_revision: 'protocol-v1', channel, processing_state: 'STOPPED' });
    return route.fulfill({ status: 404, json: { error: { message: 'Unavailable in this browser fixture' } } });
  });
  await page.goto('/app.html?view=channel-setup&channel=protocol-editor');
  const dialog = page.getByRole('dialog', { name: 'Edit County Dispatch', exact: true });
  await expect(dialog).toBeVisible();
  return { dialog, writes };
}

for (const protocolId of ['dmr', 'nxdn']) {
  for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
    test(`${protocolId} switches relevant controls and saves past hidden invalid drafts ${theme}`, async ({ page }, testInfo) => {
      await page.setViewportSize({ width, height: 900 });
      const { dialog, writes } = await openEditor(page, protocolId, theme);
      const openSection = async (id) => {
        const section = dialog.locator(`details[data-channel-section="${id}"]`);
        if (!await section.getAttribute('open').then((value) => value !== null))
          await section.locator('summary').click();
      };
      await openSection('protocol');
      const mode = dialog.getByRole('combobox', { name: 'Channel type', exact: true });
      await expect(mode).toHaveValue('TRUNKED');
      await dialog.getByRole('button', { name: 'Add mapping', exact: true }).click();
      const mapRow = dialog.locator('.channel-map-row');
      await mapRow.locator('input').first().fill('0');
      if (protocolId === 'dmr')
        await dialog.getByRole('checkbox', { name: 'Use compressed talkgroups', exact: true }).press('Space');
      await mode.selectOption('CONVENTIONAL');
      await expect(dialog.locator('[data-channel-protocol-summary]')).toHaveText(`${protocolId.toUpperCase()} · Conventional`);
      await expect(mapRow).toBeHidden();
      await expect(mapRow.locator('input').first()).toBeDisabled();
      await openSection('source');
      await expect(dialog.getByRole('spinbutton', { name: 'Frequencies in MHz', exact: true })).toBeVisible();
      await expect(dialog.getByRole('combobox', { name: 'Preferred frequency', exact: true })).toBeVisible();
      await openSection('output');
      const traffic = dialog.locator('[data-channel-traffic-option]');
      for (const option of await traffic.all()) {
        await expect(option).toBeHidden();
        await expect(option.locator('input')).toBeDisabled();
      }
      await expect(dialog.getByRole('checkbox', { name: 'Call events', exact: true })).toBeChecked();
      await openSection('protocol');
      await mode.selectOption('TRUNKED');
      await expect(dialog.locator('[data-channel-protocol-summary]')).toHaveText(`${protocolId.toUpperCase()} · Trunked`);
      await expect(mapRow).toBeVisible();
      await expect(mapRow.locator('input').first()).toHaveValue('0');
      if (protocolId === 'dmr')
        await expect(dialog.getByRole('checkbox', { name: 'Use compressed talkgroups', exact: true })).toBeChecked();
      await openSection('output');
      await expect(dialog.getByRole('checkbox', { name: 'Traffic-channel call events', exact: true })).toBeChecked();
      await openSection('protocol');
      await mode.selectOption('CONVENTIONAL');
      expect(await dialog.locator('form').evaluate((form) => form.checkValidity())).toBe(true);
      await openSection('output');
      await page.screenshot({ path: testInfo.outputPath(`${protocolId}-${theme}.png`), fullPage: true });
      await dialog.getByRole('button', { name: 'Save changes', exact: true }).click();
      await expect.poll(() => writes.length).toBe(1);
      await expect(dialog.getByRole('alert')).toContainText('Save failed');
      await expect(mapRow.locator('input').first()).toBeDisabled();
      await dialog.getByRole('button', { name: 'Save changes', exact: true }).click();
      await expect.poll(() => writes.length).toBe(2);
      expect(writes[1]).toEqual(writes[0]);
      expect(writes[0]).toMatchObject({ settings: { channel_mode: 'CONVENTIONAL' },
        event_logs: ['CALL_EVENT'], recorders: ['BASEBAND'], frequency_map: [] });
      for (const key of ['traffic_channel_pool_size', 'ignore_data_calls', 'ignore_encrypted_calls',
        ...(protocolId === 'dmr' ? ['use_compressed_talkgroups'] : [])])
        expect(writes[0].settings).not.toHaveProperty(key);
      await expect(dialog).toHaveCount(0);
    });
  }
}
