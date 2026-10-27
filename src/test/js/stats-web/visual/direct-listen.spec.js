'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication, channelId, channelName, systemKey, systemName, groupKey } =
  require('./fixtures/site-style-app.cjs');
const { selectAudioPanel } = require('./audio-dock-controls.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const scanLists = [
  { id: 1, name: 'County Fire', description: 'Fire dispatch', default: true, enabled: true },
  { id: 2, name: 'County Police', description: 'Police dispatch', default: false, enabled: true }
];
const snapshot = { revision: 1, tables: [{ table_id: channelId, configuration_id: channelId,
  title: channelName, system_name: systemName, channel_running: true, rows: [{
    key: 'direct-listen-call', configuration_id: channelId, protocol: 'P25', decoder: 'P25_PHASE1',
    channel_kind: 'TRUNKED', serving_system_key: systemKey, radio_system_key: systemKey,
    system_name: systemName, channel_name: channelName, frequency_hz: 851_037_500,
    status: 'CALL', role: 'TRAFFIC', tags: ['VOICE'], activation_order: 1,
    source_id: 30914, source_form: 'RADIO', source_alias: 'Engine 4', target_id: 1201,
    target_form: 'TALKGROUP', target_kind: 'talkgroup', target_alias: 'Fire Dispatch',
    target_identity_key: groupKey,
    target_entity_ref: { kind: 'talkgroup', radio_system_key: systemKey, identity_key: groupKey }
  }] }] };
let defaults;

test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

async function openDirectListen(page, { theme = 'light', guest = false, audio = true, direct = true,
  liveSnapshot = snapshot, rowKey = 'direct-listen-call' } = {}) {
  const fixture = await installSiteStyleApplication(page, theme, liveSnapshot);
  const state = { feedRequests: [], preferenceWrites: [] };
  let preferences = structuredClone(defaults), revision = 1;
  preferences.appearance.theme = theme;
  preferences.playback.selected_scan_list_ids = [2];
  await page.route('**/api/v1/me/preferences', route => {
    if (route.request().method() === 'PUT') {
      expect(route.request().headers()['if-match']).toBe(`"${revision}"`);
      preferences = route.request().postDataJSON();
      state.preferenceWrites.push(structuredClone(preferences));
      revision += 1;
    }
    return route.fulfill({ json: { revision, preferences } });
  });
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
    configured: true, authenticated: !guest, username: guest ? null : 'fixture-admin',
    tier: guest ? 'public' : 'user', primary: false,
    csrf_token: guest ? null : 'fixture-csrf', guide_dismissal_scope: guest ? null : 'account-1-100',
    capabilities: { 'web-access': true, dashboard: true, live: true, radio: true, credits: true,
      'csv-export': true, 'call-audio': audio, 'direct-listen': direct, recordings: true,
      'user-settings': !guest }
  } } }));
  await page.route('**/api/v1/scan-lists', route => route.fulfill({ json: { data: { scan_lists: scanLists } } }));
  await page.route('**/api/v1/calls/feed*', route => {
    state.feedRequests.push(new URL(route.request().url()).search);
    return route.fulfill({ json: { data: { cursor: '1', reset: false, calls: [] } } });
  });
  await page.goto(`/app.html?view=live&channel=${channelId}`);
  const row = page.locator(`.channels-live-table tr[data-id="${rowKey}"]`);
  await expect(row).toBeVisible();
  return { ...state, fixture, row };
}

async function fullDock(page) {
  const dock = page.locator('#audio-dock');
  await dock.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').press('End');
  await expect(dock).toHaveAttribute('data-state', 'full');
  return dock;
}

async function expectStopped(dock, state) {
  await expect(dock.locator('.audio-dock-play:visible')).toHaveAttribute('aria-label', 'Listen live');
  await expect(dock.getByRole('button', { name: 'Stop listening', exact: true })).toBeDisabled();
  expect(state.feedRequests).toEqual([]);
}

test('Scanner opens Direct Listen management and returns to its own controls', async ({ page }) => {
  const state = await openDirectListen(page);
  await state.row.locator('[data-column="target"] [data-direct-listen-action="add"]').click();
  await page.goto('/app.html?view=scanner');
  const trigger = page.locator('.scanner-scan-trigger');
  await expect(trigger).toBeVisible();
  await trigger.click();
  await expect(page.locator('#scanner-scan-lists').getByRole('checkbox', { name: 'Direct Listen', exact: true })).toBeChecked();
  await page.locator('#scanner-scan-lists').getByRole('button', { name: 'Manage Direct Listen', exact: true }).click();
  const dock = page.locator('#audio-dock');
  await expect(dock).toBeVisible();
  await expect(dock.locator('#audio-dock-panel')).toContainText('Fire Dispatch');
  await dock.getByRole('button', { name: 'Make player compact', exact: true }).click();
  await expect(dock).toBeHidden();
  await expect(trigger).toBeFocused();
  expect(state.fixture.pageErrors).toEqual([]);
});

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Direct Listen preserves normal lists and stopped playback through refresh on ${theme} ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    const state = await openDirectListen(page, { theme });
    const add = state.row.locator('[data-column="target"] [data-direct-listen-action="add"]');
    await expect(add).toHaveAccessibleName('Add Fire Dispatch to Direct Listen');
    await add.click();

    const dock = await fullDock(page);
    await selectAudioPanel(dock, 'Scan Lists');
    const direct = dock.getByRole('checkbox', { name: 'Direct Listen', exact: true });
    const police = dock.getByRole('checkbox', { name: /^County Police(?:\s|$)/ });
    const fire = dock.getByRole('checkbox', { name: /^County Fire(?:\s|$)/ });
    await expect(direct).toBeChecked();
    await expect(police).toBeChecked();
    await expect(fire).not.toBeChecked();
    await expectStopped(dock, state);
    await dock.getByRole('button', { name: 'Manage Direct Listen', exact: true }).click();
    await expect(dock.locator('#audio-dock-context-title')).toHaveText('Direct Listen');
    await expect(dock.locator('#audio-dock-context-title + span')).toHaveText('1 target · This session');
    const remove = dock.getByRole('button', { name: 'Remove Fire Dispatch from Direct Listen', exact: true });
    await expect(remove).toHaveCount(1);
    await expect(dock.locator('#audio-dock-panel')).toContainText(systemName);

    // Adding the same identity again updates one member and preserves the selected normal lists.
    await dock.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').press('Home');
    await add.click();
    await fullDock(page);
    await selectAudioPanel(dock, 'Scan Lists');
    await dock.getByRole('button', { name: 'Manage Direct Listen', exact: true }).click();
    await expect(remove).toHaveCount(1);
    await expectStopped(dock, state);

    // The fixture serves the application at /app.html; / serves the component gallery.
    await page.goto(`/app.html?view=live&channel=${channelId}`);
    await expect(state.row).toBeVisible();
    await fullDock(page);
    await selectAudioPanel(dock, 'Scan Lists');
    await expect(direct).toBeChecked();
    await expect(police).toBeChecked();
    await expect(fire).not.toBeChecked();
    await expectStopped(dock, state);
    await dock.getByRole('button', { name: 'Manage Direct Listen', exact: true }).click();
    await expect(remove).toHaveCount(1);
    if (process.env.WEB_REVIEW_DIR) {
      fs.mkdirSync(process.env.WEB_REVIEW_DIR, { recursive: true });
      await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR,
        `direct-listen-${theme}-${width}.png`) });
    }
    await remove.click();
    await expect(remove).toHaveCount(0);
    await dock.getByRole('button', { name: /^Lists(?:\s|$)/ }).click();
    await expect(dock.locator('#audio-dock-context-title')).toHaveText('Lists');
    await expect(direct).not.toBeChecked();
    await expect(police).toBeChecked();
    await expect(fire).not.toBeChecked();
    await expectStopped(dock, state);
    await expectNoHorizontalOverflow(page);
    expect(state.preferenceWrites.every(value => value.playback.selected_scan_list_ids.join(',') === '2')).toBe(true);
    expect(state.fixture.pageErrors).toEqual([]);
    expect(state.fixture.unexpected).toEqual([]);
  });
}

test('conventional digital listening keeps the talkgroup qualifier while analog listening selects its channel', async ({ page }) => {
  const digitalChannel = '22222222-2222-4222-8222-222222222222';
  const analogChannel = '33333333-3333-4333-8333-333333333333';
  const conventionalSnapshot = { revision: 1, tables: [{ table_id: 'conventional', title: 'Conventional',
    channel_running: true, rows: [
      { key: 'conventional-digital', configuration_id: digitalChannel, channel_kind: 'CONVENTIONAL',
        protocol: 'P25', decoder: 'P25_PHASE1', channel_name: 'County Transit Repeater',
        status: 'CALL', role: 'CONVENTIONAL', tags: ['CONVENTIONAL', 'VOICE'], activation_order: 1,
        target_id: 1201, target_form: 'TALKGROUP', target_alias: 'Transit Dispatch',
        target_identity_key: groupKey, entity_ref: { kind: 'channel', key: digitalChannel } },
      { key: 'conventional-analog', configuration_id: analogChannel, channel_kind: 'CONVENTIONAL',
        protocol: 'NBFM', decoder: 'NBFM', channel_name: 'County Fire Mutual Aid',
        status: 'CALL', role: 'CONVENTIONAL', tags: ['CONVENTIONAL', 'VOICE'], activation_order: 2,
        target_id: 1, target_form: 'TALKGROUP', target_alias: 'Synthetic analog group',
        entity_ref: { kind: 'channel', key: analogChannel } }
    ] }] };
  const state = await openDirectListen(page, { liveSnapshot: conventionalSnapshot, rowKey: 'conventional-digital' });
  await state.row.locator('[data-column="target"]')
    .getByRole('button', { name: 'Add Transit Dispatch to Direct Listen', exact: true }).click();
  const analog = page.locator('.channels-live-table tr[data-id="conventional-analog"] [data-column="target"]');
  await analog.getByRole('button', { name: 'Add County Fire Mutual Aid to Direct Listen', exact: true }).click();
  const dock = await fullDock(page);
  await selectAudioPanel(dock, 'Scan Lists');
  await expect(dock.getByRole('checkbox', { name: 'Direct Listen', exact: true })).toBeChecked();
  await dock.getByRole('button', { name: 'Manage Direct Listen', exact: true }).click();
  await expect(dock.getByRole('button', { name: 'Remove Transit Dispatch from Direct Listen', exact: true })).toBeVisible();
  await expect(dock.getByRole('button', { name: 'Remove County Fire Mutual Aid from Direct Listen', exact: true })).toBeVisible();
  await expect(dock.locator('#audio-dock-panel')).not.toContainText('Synthetic analog group');
  await dock.getByRole('button', { name: 'Listen live', exact: true }).click();
  await expect.poll(() => state.feedRequests.length).toBeGreaterThan(0);
  await dock.getByRole('button', { name: 'Stop listening', exact: true }).click();
  const query = new URLSearchParams(state.feedRequests[0]);
  expect(query.getAll('scan_list_id')).toEqual(['2']);
  expect(query.getAll('direct_target').map(value => JSON.parse(value))).toEqual([
    { kind: 'channel', key: digitalChannel, identity_key: groupKey },
    { kind: 'channel', key: analogChannel }
  ]);
  expect(state.fixture.pageErrors).toEqual([]);
  expect(state.fixture.unexpected).toEqual([]);
});

test('a scoped P25 Visualizer talkgroup adds its system-scoped identity to Direct Listen', async ({ page }) => {
  const state = await openDirectListen(page);
  const filename = path.resolve(__dirname, '../../../../../stats-web/assets/features/network-visualizer/index.js');
  const marker = '  const close = () => {';
  const original = fs.readFileSync(filename, 'utf8');
  expect(original.split(marker)).toHaveLength(2);
  const instrumented = original.replace(marker,
    `  window.directListenSelectTalkgroup = enterTalkgroup;\n${marker}`);
  await page.route('**/assets/features/network-visualizer/index.js?**', route => route.fulfill({
    contentType: 'text/javascript', body: instrumented
  }));
  await page.goto('/app.html?view=network-visualizer');
  await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
  await expect.poll(() => page.evaluate(key => window.directListenSelectTalkgroup?.(key),
    `${systemKey}:${groupKey}`)).toBe(true);
  const scope = page.locator('.network-visualizer-scope-title');
  await expect(scope).toContainText('Fire Dispatch');
  await scope.getByRole('button', { name: 'Add Fire Dispatch to Direct Listen', exact: true }).click();
  const dock = await fullDock(page);
  await selectAudioPanel(dock, 'Scan Lists');
  await expect(dock.getByRole('checkbox', { name: 'Direct Listen', exact: true })).toBeChecked();
  await dock.getByRole('button', { name: 'Manage Direct Listen', exact: true }).click();
  await expect(dock.getByRole('button', { name: 'Remove Fire Dispatch from Direct Listen', exact: true })).toBeVisible();
  await expect(dock.locator('#audio-dock-panel')).toContainText(systemName);
  await dock.getByRole('button', { name: 'Listen live', exact: true }).click();
  await expect.poll(() => state.feedRequests.length).toBeGreaterThan(0);
  await dock.getByRole('button', { name: 'Stop listening', exact: true }).click();
  expect(new URLSearchParams(state.feedRequests[0]).getAll('direct_target').map(value => JSON.parse(value))).toEqual([
    { kind: 'talkgroup', radio_system_key: systemKey, identity_key: groupKey }
  ]);
  expect(state.fixture.pageErrors).toEqual([]);
  expect(state.fixture.unexpected).toEqual([]);
});

for (const [name, capabilities] of [
  ['Direct Listen permission', { direct: false }],
  ['call audio permission', { audio: false }]
]) {
  test(`a guest without ${name} cannot add Live talkgroups to Direct Listen`, async ({ page }) => {
    const state = await openDirectListen(page, { guest: true, ...capabilities });
    await expect(state.row.locator('[data-direct-listen-action="add"]')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Manage Direct Listen', exact: true })).toHaveCount(0);
    expect(state.feedRequests).toEqual([]);
    expect(state.fixture.pageErrors).toEqual([]);
    expect(state.fixture.unexpected).toEqual([]);
  });
}
