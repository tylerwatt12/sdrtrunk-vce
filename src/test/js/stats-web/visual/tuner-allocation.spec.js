'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

// Sanitized frequency/window geometry from the October 11, 2026 Albany support
// snapshot. The monitor represents configured/alternate controls as `control`.
// That report did not contain tuner movement history or tuner setting values.
// Broad 851–861 MHz limits below are synthetic to test geometric window matching.
const ALBANY_FREQUENCIES = [
  [851_737_500, 'call', true], [852_187_500, 'call', true], [852_950_000, 'control', true],
  [853_737_500, 'control', false], [854_037_500, 'control', false], [854_537_500, 'control', true],
  [856_212_500, 'call', true], [856_937_500, 'call', true], [857_937_500, 'call', true],
  [858_712_500, 'call', true], [860_212_500, 'call', true]
];

function snapshot(decisions = []) {
  const receiving = (frequency_hz, role) => ({ frequency_hz, bandwidth_hz: 12_500, role,
    site_id: 'albany', channel_name: 'Albany County Simulcast' });
  return { available: true, session_id: 'receiver-session', cursor: 3, generated_at_ms: Date.now(),
    sites: [{ id: 'albany', name: 'Albany County Simulcast', system_name: 'Albany/Schenectady Counties',
      channel_name: 'Albany County Simulcast', kind: 'trunked', known_frequency_count: 11,
      covered_frequency_count: 9, frequencies: ALBANY_FREQUENCIES.map(([frequency_hz, role, covered]) =>
        ({ frequency_hz, bandwidth_hz: 12_500, covered, role })),
      tuners: ['safe-tuner-1', 'safe-tuner-2', 'safe-tuner-3', 'safe-tuner-4'], status: 'control_and_calls' }],
    tuners: [
      [855_362_500, [receiving(854_537_500, 'control'), receiving(856_212_500, 'call')]],
      [852_212_500, [receiving(851_737_500, 'call')]], [859_462_500, []], [856_962_500, []]
    ].map(([frequency_hz, channels], index) => ({ id: `safe-tuner-${index + 1}`, name: `RTL-SDR ${index + 1}`,
      frequency_hz, usable_bandwidth_hz: 2_352_000, dc_gap_hz: 10_000, status: 'enabled', available: true,
      center_locked: false, min_frequency_hz: 851_000_000, max_frequency_hz: 861_000_000, preferred_for: [],
      receiving: channels, supports_shared_window: true, reason: null })), decisions };
}

// Synthetic shared-tuner work tests channel ownership. Albany's call frequency
// also appears on another site's receiving tuner; a conventional channel has
// the same saved channel name as Albany, and one receiver has no site identity.
function sharedTunerSnapshot() {
  const data = snapshot();
  const received = (frequency_hz, role, site_id, channel_name) =>
    ({ frequency_hz, bandwidth_hz: 12_500, role, site_id, channel_name });
  data.tuners[0].receiving.push(
    received(854_937_500, 'control', 'colonie', 'Colonie Simulcast'),
    received(855_187_500, 'conventional', 'weather', 'Albany County Simulcast'),
    received(855_062_500, 'call', null, 'Albany County Simulcast'));
  data.tuners[3].receiving.push(
    received(856_212_500, 'call', 'colonie', 'Colonie Simulcast'),
    received(856_937_500, 'control', 'colonie', 'Colonie Simulcast'));
  data.sites[0].frequencies.push({ frequency_hz: 855_062_500, bandwidth_hz: 12_500, covered: true, role: 'call' });
  data.sites[0].known_frequency_count++;
  data.sites[0].covered_frequency_count++;
  data.sites.push({ id: 'colonie', name: 'Colonie Simulcast', system_name: 'Other system',
    channel_name: 'Colonie Simulcast', kind: 'trunked', known_frequency_count: 3,
    covered_frequency_count: 3, status: 'control_and_calls', tuners: ['safe-tuner-1', 'safe-tuner-4'],
    frequencies: [[854_937_500, 'control'], [856_212_500, 'call'], [856_937_500, 'control']]
      .map(([frequency_hz, role]) => ({ frequency_hz, bandwidth_hz: 12_500, covered: true, role })) });
  data.sites.push({ id: 'weather', name: 'Weather', system_name: '', channel_name: 'Albany County Simulcast',
    kind: 'conventional', known_frequency_count: 1, covered_frequency_count: 1,
    status: 'conventional', tuners: ['safe-tuner-1'], frequencies: [
      { frequency_hz: 855_187_500, bandwidth_hz: 12_500, covered: true, role: 'conventional' }] });
  return data;
}

function separatedBandsSnapshot() {
  const data = snapshot();
  for (const [id, name, frequency_hz, role, site_id, channel_name] of [
    ['safe-tuner-5', 'RTL-SDR 5', 773_987_500, 'control', 'regional', 'Regional Simulcast'],
    ['safe-tuner-6', 'RTL-SDR 6', 150_000_000, 'conventional', 'county-fire', 'County Fire']
  ]) {
    const item = { frequency_hz, bandwidth_hz: 12_500, role, site_id, channel_name };
    data.tuners.push({ ...data.tuners[0], id, name, frequency_hz, min_frequency_hz: frequency_hz - 5_000_000,
      max_frequency_hz: frequency_hz + 5_000_000, receiving: [item] });
    data.sites.push({ id: site_id, name: channel_name, channel_name, system_name: '',
      kind: role === 'conventional' ? 'conventional' : 'trunked', known_frequency_count: 1,
      covered_frequency_count: 1, status: role, tuners: [id], frequencies: [{ ...item, covered: true }] });
  }
  return data;
}

// Synthetic decisions exercise history, grouped failures and captured geometry.
// None of these decisions is presented as a recorded Albany event.
function decision(id = 1, extra = {}) {
  return { decision_id: id, sequence: id, timestamp: 1_700_000_004_000 + id,
    site_id: 'albany', site_name: 'Albany County Simulcast', tuner_id: 'safe-tuner-3', tuner_name: 'RTL-SDR 3',
    type: 'move', outcome: 'moved', reason: 'An unused tuner could cover more known frequencies',
    before_frequency_hz: 859_462_500, requested_center_frequency_hz: 859_212_500,
    after_frequency_hz: 859_212_500, usable_bandwidth_hz: 2_352_000, dc_gap_hz: 10_000,
    requested_frequency_hz: null, covered_before: 8, covered_after: 9, repeat_count: 1,
    first_timestamp: 1_700_000_004_000 + id, last_timestamp: 1_700_000_004_000 + id, candidates: [], ...extra };
}

function catalogForSnapshot(data) {
  return { revision: 1, channels: data.sites.map((site) => ({
    configuration_id: site.id, name: site.channel_name || site.name, system: site.system_name || '', site: site.name,
    protocol_id: site.kind === 'conventional' ? 'p25-conventional' : 'p25-phase1',
    protocol_label: site.kind === 'conventional' ? 'P25 Conventional' : 'P25 Phase 1',
    channel_kind: site.kind === 'conventional' ? 'CONVENTIONAL' : 'TRUNKED',
    frequencies_hz: (site.frequencies || []).filter((item) => site.kind === 'conventional' || item.role === 'control')
      .map((item) => item.frequency_hz),
    processing_state: site.status === 'stopped' ? 'STOPPED' : 'RUNNING', auto_start_order: 1,
    alias_list_name: 'County P25', remote_origin: null
  })) };
}

// Real saved names/frequencies use sanitized IDs. Remaining state variations
// deliberately exercise channels without a current allocation snapshot.
function pickerCatalog() {
  const channel = (id, name, system, site, frequencies_hz, extra = {}) => ({
    configuration_id: id, name, system, site, frequencies_hz, protocol_id: 'p25-phase1',
    protocol_label: 'P25 Phase 1', channel_kind: 'TRUNKED', processing_state: 'RUNNING',
    auto_start_order: 1, alias_list_name: 'County P25', remote_origin: null, ...extra });
  const conventional = { protocol_id: 'p25-conventional', protocol_label: 'P25 Conventional', channel_kind: 'CONVENTIONAL' };
  const am = { protocol_id: 'am', protocol_label: 'AM', channel_kind: 'CONVENTIONAL' };
  return { revision: 1, channels: [
    channel('remote', 'Remote North', 'Remote system', 'North', [773_500_000], { remote_origin: { remote: true } }),
    channel('atc-1', 'ATC', 'ATC', 'ATC', [125_850_000], am),
    channel('marcs-elyria', 'MARCS-Elyria', 'Ohio MARCS-IP: Multi-Agency Radio Communications', 'Lorain', [770_206_250, 770_606_250]),
    channel('unnamed', null, 'Unknown', null, [154_464_000], conventional),
    ...catalogForSnapshot(snapshot()).channels,
    channel('stopped', 'Stopped channel', 'County conventional', 'West', [154_600_000], { ...conventional, processing_state: 'STOPPED' }),
    channel('never-run', 'Never started', 'County conventional', 'South', [154_700_000], { ...conventional, processing_state: 'STOPPED', auto_start_order: null }),
    channel('atc-2', 'ATC', 'ATC', 'ATC', [125_975_000], am),
    channel('marcs-grafton', 'MARCS-Grafton', 'Ohio MARCS-IP: Multi-Agency Radio Communications', 'Lorain', [773_081_250, 770_906_250]),
    channel('failed', 'Failed channel', 'County conventional', 'East', [154_800_000], { ...conventional, processing_state: 'STOPPED' })
  ] };
}

const pickerChoice = (page, id) => page.locator(`.tuner-allocation-channel-choice[data-configuration-id="${id}"]`);
const channelStatus = (page, label) => page.getByRole('group', { name: 'Channel status', exact: true })
  .getByRole('button', { name: new RegExp(`^${label}\\b`) });
async function chooseChannel(page, id) {
  await pickerChoice(page, id).click();
  await expect(page.locator(`.tuner-allocation-selected-channel[data-configuration-id="${id}"]`)).toBeVisible();
}
async function changeChannel(page, id) {
  await page.locator('.tuner-allocation-back').click();
  await chooseChannel(page, id);
}

async function openApp(page, options = {}) {
  let requests = 0;
  let catalogRequests = 0;
  let response = { data: options.data || snapshot() };
  let catalogResponse = options.catalogResponse || { data: options.catalog || catalogForSnapshot(response.data) };
  let preferences = JSON.parse(JSON.stringify(defaults));
  preferences.appearance.theme = options.theme || 'light';
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === '/api/v1/auth/session') return route.fulfill({ json: { data: {
      configured: true, authenticated: true, username: 'admin', tier: 'admin', primary: true,
      capabilities: { 'admin-settings': true, 'radio-view': true, 'admin-channels': false, credits: true } } } });
    if (pathname === '/api/v1/me/preferences') {
      if (route.request().method() === 'PUT') preferences = route.request().postDataJSON();
      return route.fulfill({ json: { revision: 1, preferences } });
    }
    if (pathname === '/api/v1/admin/tuner-allocation') {
      requests++;
      return route.fulfill({ status: response.status || 200,
        json: response.status && response.status !== 200 ? { error: { status: response.status, message: 'Unavailable' } } : { data: response.data } });
    }
    if (pathname === '/api/v1/channel-catalog') {
      catalogRequests++;
      return route.fulfill({ status: catalogResponse.status || 200,
        json: catalogResponse.status && catalogResponse.status !== 200 ? { error: { status: catalogResponse.status, message: 'Unavailable' } } : { data: catalogResponse.data } });
    }
    return route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
  });
  await page.goto(`/app.html?view=admin&tab=tuner-allocation${options.channel ? `&allocation_channel=${options.channel}` : ''}`);
  if (options.choose !== false && !options.channel) await chooseChannel(page, options.selectedId || 'albany');
  if (options.choose !== false || options.channel) await expect(page.locator('.tuner-allocation-live-status')).toContainText('Live');
  return { requests: () => requests, catalogRequests: () => catalogRequests,
    respond: (next) => { response = next; }, respondCatalog: (next) => { catalogResponse = next; } };
}

const sitePanel = (page) => page.locator('.tuner-allocation-site[data-site-id="albany"]');
const tunerRow = (page, id) => sitePanel(page).locator(`.tuner-allocation-window-row[data-tuner-id="safe-tuner-${id}"]`);
const centerReadout = (dialog) => dialog.locator('.ui-fact-row:has(dt:text-is("Center")) dd');
const receivingMarker = (host, titleText) => host.locator(
  '.chart-window-selected, .chart-window-other, .chart-window-unscoped').filter({ hasText: titleText });

for (const theme of ['light', 'dark']) {
  test(`compact channel catalog starts with running channels and keeps recognizable names in ${theme}`, async ({ page }, testInfo) => {
    const app = await openApp(page, { choose: false, catalog: pickerCatalog(), theme });
    const choices = page.locator('.tuner-allocation-channel-choice');
    await expect(choices).toHaveCount(6);
    await expect(channelStatus(page, 'Running')).toHaveAttribute('aria-pressed', 'true');
    await expect(channelStatus(page, 'Running')).toHaveAccessibleName('Running (6)');
    await expect(channelStatus(page, 'Stopped')).toHaveAccessibleName('Stopped (3)');
    await expect(channelStatus(page, 'All')).toHaveAccessibleName('All (9)');
    await expect(page.getByRole('button', { name: 'Coverage', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Decision history', exact: true })).toHaveCount(0);
    await expect(page.locator('.tuner-allocation-map')).toHaveCount(0);
    await expect(page.locator('table[data-table-type="tuner-allocation-decisions"]')).toHaveCount(0);
    expect(app.requests()).toBe(0);
    await expect(page.locator('.tuner-allocation-protocol-group')).toHaveCount(0);
    await expect(page.locator('.tuner-allocation-catalog-columns')).toContainText('Protocol');
    await expect(page.getByRole('combobox', { name: 'Protocol', exact: true })).toHaveValue('');
    await expect(page.locator('table')).toHaveCount(0);
    await expect(page.locator('.table-layout-menu')).toHaveCount(0);
    await expect(choices.first()).toHaveClass(/ui-select-list-item/);
    await channelStatus(page, 'All').click();
    await expect(choices).toHaveCount(9);
    expect(await choices.evaluateAll((elements) => elements.map((element) => element.dataset.configurationId)))
      .toEqual(['albany', 'marcs-elyria', 'marcs-grafton', 'unnamed', 'failed', 'never-run', 'stopped', 'atc-1', 'atc-2']);
    await expect(pickerChoice(page, 'remote')).toHaveCount(0);
    const row = (id) => pickerChoice(page, id);
    await expect(row('marcs-grafton')).toContainText('MARCS-Grafton');
    await expect(row('marcs-elyria')).toContainText('MARCS-Elyria');
    await expect(row('marcs-grafton')).toContainText('Lorain');
    await expect(row('marcs-elyria')).toContainText('Lorain');
    await expect(row('marcs-grafton')).not.toContainText('773.08125');
    await expect(row('marcs-grafton')).not.toContainText('770.90625');
    await expect(row('marcs-elyria')).not.toContainText('770.20625');
    await expect(row('marcs-grafton')).toContainText('Running');
    await expect(row('marcs-grafton')).toHaveAccessibleName(/P25 Trunked · Running$/);
    await expect(row('atc-1')).toContainText('125.85000');
    await expect(row('atc-2')).toContainText('125.97500');
    await expect(pickerChoice(page, 'atc-1')).not.toHaveAttribute('aria-label',
      await pickerChoice(page, 'atc-2').getAttribute('aria-label'));
    await expect(row('unnamed')).toContainText('154.46400');
    await expect(row('stopped')).toContainText('Stopped');
    await expect(row('marcs-grafton').locator('.tuner-allocation-channel-protocol')).toHaveText('P25 Trunked');
    await expect(row('atc-1').locator('.tuner-allocation-channel-protocol')).toHaveText('AM');
    await expect(page.getByRole('navigation', { name: 'Channel pages', exact: true })).toContainText('1–9 of 9');
    expect(app.requests()).toBe(0);
    for (const width of [1280, 320]) {
      await page.setViewportSize({ width, height: 900 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
      await page.screenshot({ animations: 'disabled', path: testInfo.outputPath(`tuner-channel-picker-${theme}-${width}.png`), fullPage: true });
    }
  });
}

test('duplicate trunked names include saved frequencies and the whole row is keyboard selectable', async ({ page }) => {
  const catalog = pickerCatalog();
  for (const item of catalog.channels.filter((channel) => channel.configuration_id.startsWith('marcs-'))) item.name = 'MARCS';
  await openApp(page, { choose: false, catalog });
  const grafton = pickerChoice(page, 'marcs-grafton');
  const elyria = pickerChoice(page, 'marcs-elyria');
  await expect(grafton).toContainText('773.08125');
  await expect(elyria).toContainText('770.20625');
  await expect(grafton).not.toHaveAttribute('aria-label', await elyria.getAttribute('aria-label'));
  await grafton.focus();
  await page.keyboard.press('Enter');
  const selected = page.locator('.tuner-allocation-selected-channel[data-configuration-id="marcs-grafton"]');
  await expect(selected).toBeVisible();
  await expect(selected).toContainText('773.08125');
  await expect(selected).not.toContainText('770.20625');
  await expect(page).toHaveURL(/allocation_channel=marcs-grafton/);
  await changeChannel(page, 'atc-1');
  const atc = page.locator('.tuner-allocation-selected-channel[data-configuration-id="atc-1"]');
  await expect(atc).toContainText('125.85000');
  await expect(atc).not.toContainText('125.97500');
});

test('channel search finds configured names, system, site, protocol, and RF frequency', async ({ page }) => {
  await openApp(page, { choose: false, catalog: pickerCatalog() });
  await channelStatus(page, 'All').click();
  const search = page.locator('.tuner-allocation-channel-search');
  const ids = () => page.locator('.tuner-allocation-channel-choice').evaluateAll(
    (elements) => elements.map((element) => element.dataset.configurationId));
  for (const [term, expected] of [
    ['MARCS-Grafton', ['marcs-grafton']], ['Ohio MARCS', ['marcs-elyria', 'marcs-grafton']],
    ['Lorain', ['marcs-elyria', 'marcs-grafton']], ['125.975', ['atc-2']],
    ['154.464', ['unnamed']], ['P25 Conventional', ['unnamed', 'failed', 'never-run', 'stopped']],
    ['Unknown', ['unnamed']], ['Unnamed', ['unnamed']]
  ]) {
    await search.fill(term);
    await expect.poll(ids).toEqual(expected);
  }
  await search.fill('No matching channel');
  await expect(page.locator('.tuner-allocation-channel-choice')).toHaveCount(0);
  await search.fill('');
  await expect(page.locator('.tuner-allocation-channel-choice')).toHaveCount(9);
});

test('chosen saved channel ID scopes coverage and decision history without a chooser inside either tab', async ({ page }) => {
  const data = snapshot([
    decision(71, { site_id: 'marcs-grafton', site_name: 'Lorain', reason: 'Grafton request could not fit', type: 'failure', outcome: 'allocation_failed' }),
    decision(72, { site_id: 'marcs-elyria', site_name: 'Lorain', reason: 'Elyria request could not fit', type: 'failure', outcome: 'allocation_failed' })
  ]);
  for (const [id, frequency_hz] of [['marcs-grafton', 773_081_250], ['marcs-elyria', 770_206_250]]) {
    data.sites.push({ id, name: 'Lorain', channel_name: id === 'marcs-grafton' ? 'MARCS-Grafton' : 'MARCS-Elyria',
      system_name: 'Ohio MARCS-IP: Multi-Agency Radio Communications', kind: 'trunked', known_frequency_count: 1,
      covered_frequency_count: 0, status: 'stopped', tuners: [], frequencies: [{ frequency_hz, bandwidth_hz: 12_500, covered: false, role: 'control' }] });
  }
  await openApp(page, { choose: false, data, catalog: pickerCatalog() });
  await chooseChannel(page, 'marcs-grafton');
  await expect(page).toHaveURL(/allocation_channel=marcs-grafton/);
  await expect(page.locator('.tuner-allocation-selected-channel')).toContainText('MARCS-Grafton');
  const selected = page.locator('.tuner-allocation-selected-channel');
  await expect(selected).toHaveClass(/ui-record-card/);
  await expect(selected).not.toContainText('773.08125');
  await expect(selected).not.toContainText('770.90625');
  const toolbar = selected.locator('.tuner-allocation-monitor-toolbar');
  await expect(toolbar.getByRole('button', { name: 'Coverage', exact: true })).toBeVisible();
  await expect(toolbar.getByRole('button', { name: 'Decision history', exact: true })).toBeVisible();
  await expect(toolbar.locator('.tuner-allocation-live-status')).toContainText('Live');
  await expect(toolbar.getByRole('button', { name: 'Pause tuner allocation monitor' })).toBeVisible();
  const details = selected.getByRole('button', { name: 'Channel details', exact: true });
  await details.click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('773.08125');
  await expect(dialog).toContainText('770.90625');
  await page.keyboard.press('Escape');
  await expect(details).toBeFocused();
  await expect(page.locator('.tuner-allocation-site[data-site-id="marcs-grafton"]')).toBeVisible();
  await expect(page.locator('.tuner-allocation-site[data-site-id="marcs-elyria"]')).toHaveCount(0);
  await expect(page.locator('.tuner-allocation-site-filter')).toHaveCount(0);
  await expect(page.locator('.tuner-allocation-channel-search')).toHaveCount(0);
  await page.getByRole('button', { name: 'Decision history', exact: true }).click();
  const table = page.locator('table[data-table-type="tuner-allocation-decisions"]');
  await expect(table.locator('tbody tr')).toHaveCount(1);
  await expect(table).toContainText('Grafton request could not fit');
  await expect(table).not.toContainText('Elyria request could not fit');
  await expect(page.locator('.tuner-allocation-site-filter')).toHaveCount(0);
  await expect(page.locator('.tuner-allocation-channel-search')).toHaveCount(0);
  await expect(page.locator('.tuner-allocation-back')).toBeVisible();
});

test('Back to channels and browser Back preserve search, filters, and explicit selection', async ({ page }) => {
  await openApp(page, { choose: false, catalog: pickerCatalog() });
  await channelStatus(page, 'All').click();
  await page.getByRole('combobox', { name: 'Protocol', exact: true }).selectOption('p25-trunked');
  await page.locator('.tuner-allocation-channel-search').fill('MARCS');
  await chooseChannel(page, 'marcs-grafton');
  await page.goBack();
  await expect(page.locator('.tuner-allocation-channel-search')).toHaveValue('MARCS');
  await expect(channelStatus(page, 'All')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('combobox', { name: 'Protocol', exact: true })).toHaveValue('p25-trunked');
  await expect(page.locator('.tuner-allocation-selected-channel')).toHaveCount(0);
  await page.goForward();
  await expect(page.locator('.tuner-allocation-selected-channel[data-configuration-id="marcs-grafton"]')).toBeVisible();
  await page.locator('.tuner-allocation-back').click();
  await expect(page.locator('.tuner-allocation-channel-search')).toHaveValue('MARCS');
  await expect(channelStatus(page, 'All')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('combobox', { name: 'Protocol', exact: true })).toHaveValue('p25-trunked');
  await expect(page).not.toHaveURL(/allocation_channel=/);
  await chooseChannel(page, 'marcs-elyria');
  await expect(page).toHaveURL(/allocation_channel=marcs-elyria/);
});

test('status and protocol filters combine with search without requesting allocation data', async ({ page }) => {
  const app = await openApp(page, { choose: false, catalog: pickerCatalog() });
  const protocol = page.getByRole('combobox', { name: 'Protocol', exact: true });
  const search = page.locator('.tuner-allocation-channel-search');
  const ids = () => page.locator('.tuner-allocation-channel-choice').evaluateAll(
    elements => elements.map(element => element.dataset.configurationId));
  await protocol.selectOption('p25-conventional');
  await expect.poll(ids).toEqual(['unnamed']);
  await expect(channelStatus(page, 'Running')).toHaveAccessibleName('Running (1)');
  await expect(channelStatus(page, 'Stopped')).toHaveAccessibleName('Stopped (3)');
  await expect(channelStatus(page, 'All')).toHaveAccessibleName('All (4)');
  await channelStatus(page, 'Stopped').click();
  await expect.poll(ids).toEqual(['failed', 'never-run', 'stopped']);
  await search.fill('West');
  await expect.poll(ids).toEqual(['stopped']);
  await expect(channelStatus(page, 'All')).toHaveAccessibleName('All (1)');
  await channelStatus(page, 'Running').click();
  await expect(page.locator('.tuner-allocation-channel-choice')).toHaveCount(0);
  await channelStatus(page, 'All').click();
  await expect.poll(ids).toEqual(['stopped']);
  await search.fill('125.975');
  await expect(page.locator('.tuner-allocation-channel-choice')).toHaveCount(0);
  await protocol.selectOption('');
  await expect.poll(ids).toEqual(['atc-2']);
  expect(app.requests()).toBe(0);
});

test('catalog pagination, search, and live refresh preserve the chosen browsing context', async ({ page }) => {
  const catalog = pickerCatalog();
  const template = catalog.channels.find(channel => channel.configuration_id === 'unnamed');
  catalog.channels = Array.from({ length: 27 }, (_, index) => ({ ...template,
    configuration_id: `paged-${index + 1}`, name: `Channel ${String(index + 1).padStart(2, '0')}`,
    site: `Sector ${index + 1}`, frequencies_hz: [154_000_000 + index * 25_000],
    processing_state: index < 24 ? 'RUNNING' : 'STOPPED' }));
  const app = await openApp(page, { choose: false, catalog });
  const pager = page.getByRole('navigation', { name: 'Channel pages', exact: true });
  const search = page.locator('.tuner-allocation-channel-search');
  const protocol = page.getByRole('combobox', { name: 'Protocol', exact: true });
  await expect(page.locator('.tuner-allocation-channel-choice')).toHaveCount(10);
  await expect(pager).toContainText('1–10 of 24');
  await pager.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(pager).toContainText('11–20 of 24');
  await chooseChannel(page, 'paged-11');
  await page.locator('.tuner-allocation-back').click();
  await expect(pager).toContainText('11–20 of 24');
  await expect(pickerChoice(page, 'paged-11')).toBeFocused();
  await protocol.selectOption('p25-conventional');
  await expect(pager).toContainText('1–10 of 24');
  await pager.getByRole('button', { name: 'Next', exact: true }).click();
  await search.fill('Channel');
  await expect(pager).toContainText('1–10 of 24');
  await pager.getByRole('button', { name: 'Next', exact: true }).click();
  const changed = structuredClone(catalog);
  changed.channels.find(channel => channel.configuration_id === 'paged-11').name = 'Channel 11 updated';
  app.respondCatalog({ data: changed });
  await expect(pickerChoice(page, 'paged-11')).toContainText('Channel 11 updated', { timeout: 7000 });
  await expect(pager).toContainText('11–20 of 24');
  await expect(search).toHaveValue('Channel');
  await expect(protocol).toHaveValue('p25-conventional');
  await expect(channelStatus(page, 'Running')).toHaveAttribute('aria-pressed', 'true');
  await search.fill('Channel 24');
  await expect(pickerChoice(page, 'paged-24')).toBeVisible();
  await expect(page.locator('.tuner-allocation-channel-choice')).toHaveCount(1);
  await expect(pager).toContainText('1–1 of 1');
  await search.fill('');
  await pager.getByRole('button', { name: 'Next', exact: true }).click();
  await channelStatus(page, 'Stopped').click();
  await expect(pager).toContainText('1–3 of 3');
  await expect(page.locator('.tuner-allocation-channel-choice')).toHaveCount(3);
  await expect(pickerChoice(page, 'paged-25')).toBeVisible();
  expect(app.requests()).toBeGreaterThan(0);
});

test('stopped, never-run, and failed channels retain the chosen ID and their own decisions without a current site snapshot', async ({ page }) => {
  const decisions = ['stopped', 'never-run', 'failed'].map((id, index) => decision(80 + index,
    { site_id: id, site_name: 'County conventional', reason: `${id} request could not start`, type: 'failure', outcome: 'allocation_failed' }));
  const app = await openApp(page, { choose: false, data: snapshot(decisions), catalog: pickerCatalog() });
  await channelStatus(page, 'Stopped').click();
  for (const [index, id] of ['stopped', 'never-run', 'failed'].entries()) {
    await chooseChannel(page, id);
    await expect(page.locator(`.tuner-allocation-selected-channel[data-configuration-id="${id}"]`)).toBeVisible();
    await expect(sitePanel(page)).toHaveCount(0);
    await expect(page).toHaveURL(new RegExp(`allocation_channel=${id}`));
    await page.getByRole('button', { name: 'Decision history', exact: true }).click();
    const table = page.locator('table[data-table-type="tuner-allocation-decisions"]');
    await expect(table.locator('tbody tr')).toHaveCount(1);
    await expect(table).toContainText(`${id} request could not start`);
    const changed = snapshot(decisions);
    changed.tuners[2].frequency_hz = 859_212_500;
    changed.decisions = decisions.map((item) => ({ ...item, repeat_count: index + 2 }));
    app.respond({ data: changed });
    await expect(page.locator(`.tuner-allocation-selected-channel[data-configuration-id="${id}"]`)).toBeVisible();
    await expect(table).toContainText(`${id} request could not start`);
    await expect(table).toContainText(`${index + 2} requests`);
    await page.locator('.tuner-allocation-back').click();
  }
  await page.goto('/app.html?view=admin&tab=tuner-allocation&allocation_channel=failed');
  await expect(page.locator('.tuner-allocation-selected-channel[data-configuration-id="failed"]')).toBeVisible();
  await expect(sitePanel(page)).toHaveCount(0);
});

test('catalog load failure retries and a revoked catalog session clears channel choices', async ({ page }) => {
  const app = await openApp(page, { choose: false, catalogResponse: { status: 503 } });
  await expect(page.locator('.tuner-allocation-channel-choice')).toHaveCount(0, { timeout: 7000 });
  const retry = page.getByRole('button', { name: 'Retry', exact: true });
  await expect(retry).toBeVisible();
  app.respondCatalog({ data: pickerCatalog() });
  await retry.click();
  await expect(page.locator('.tuner-allocation-channel-choice')).toHaveCount(6);
  await page.locator('.tuner-allocation-channel-search').fill('ATC');
  app.respondCatalog({ status: 403 });
  await expect(page.locator('.tuner-allocation-channel-choice')).toHaveCount(0, { timeout: 7000 });
  await expect(page.locator('.tuner-allocation-selected-channel')).toHaveCount(0);
  await expect(page.locator('.tuner-allocation-channel-search')).toHaveCount(0);
  await expect(page.locator('main')).not.toContainText('125.850');
});

test('real Albany coverage shows current windows, overlap, and uncovered known frequencies', async ({ page }) => {
  await openApp(page);
  const group = page.locator('.admin-settings-branch').filter({ hasText: 'Decisions engine' });
  await expect(group.getByRole('link', { name: 'Call matching', exact: true })).toBeVisible();
  await expect(group.getByRole('link', { name: 'Tuner allocation', exact: true })).toHaveAttribute('aria-current', 'page');
  const support = page.locator('.admin-settings-branch').filter({ hasText: 'Status & support' });
  await expect(support.getByRole('link', { name: 'Call matching', exact: true })).toHaveCount(0);
  const site = sitePanel(page);
  await expect(page.locator('.tuner-allocation-selected-channel')).toContainText('Albany/Schenectady Counties');
  await expect(site).toContainText('9 / 11 in windows');
  await expect(site.locator('.tuner-allocation-window-row[data-tuner-id]')).toHaveCount(4);
  await expect(tunerRow(page, 1).locator('.chart-window-center title')).toHaveText('Center 855.36250 MHz');
  await expect(tunerRow(page, 2).locator('.chart-window-center title')).toHaveText('Center 852.21250 MHz');
  await expect(tunerRow(page, 3)).toContainText('Unused');
  await expect(tunerRow(page, 4)).toContainText('Unused');
  await expect(tunerRow(page, 1).locator('.chart-window-control')).toHaveCount(1);
  await expect(tunerRow(page, 1).locator('.chart-window-call')).toHaveCount(1);
  await expect(tunerRow(page, 2).locator('.chart-window-call')).toHaveCount(1);
  const outside = page.getByRole('button', { name: 'Highlight 853.73750 MHz', exact: true });
  await outside.click();
  await expect(outside).toHaveAttribute('aria-pressed', 'true');
  await expect(site.locator('.chart-window-highlight')).toHaveCount(5);
  const known = site.locator('details[data-allocation-disclosure="known"]');
  await known.locator('summary').click();
  await expect(known.locator('tbody tr')).toHaveCount(11);
  const overlap = known.getByRole('row').filter({ hasText: '856.21250' });
  await expect(overlap).toContainText('RTL-SDR 1');
  await expect(overlap).toContainText('RTL-SDR 4');
  await expect(known.getByRole('row').filter({ hasText: '853.73750' })).toContainText('Outside');
  await page.getByRole('button', { name: 'Decision history', exact: true }).click();
  await expect(page.locator('table[data-table-type="tuner-allocation-decisions"]')).toContainText('No tuner moves or failed calls');
});

test('live page and focused tuner follow retunes while preserving disclosure, focus, and pause state', async ({ page }) => {
  const app = await openApp(page);
  await page.getByRole('button', { name: 'Highlight 853.73750 MHz', exact: true }).click();
  const known = sitePanel(page).locator('details[data-allocation-disclosure="known"]');
  await known.locator('summary').click();
  await known.locator('summary').focus();
  const changed = snapshot();
  changed.tuners[2].frequency_hz = 859_212_500;
  app.respond({ data: changed });
  await expect(tunerRow(page, 3).locator('.chart-window-center title')).toHaveText('Center 859.21250 MHz');
  await expect(known).toHaveAttribute('open', '');
  await expect(known.locator('summary')).toBeFocused();
  await expect(page.getByRole('button', { name: 'Highlight 853.73750 MHz', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(sitePanel(page).locator('.chart-window-highlight')).toHaveCount(5);
  const tableTuner = known.getByRole('row').filter({ hasText: '856.21250' })
    .getByRole('button', { name: 'Details for RTL-SDR 4', exact: true });
  await tableTuner.focus();
  app.respond({ data: snapshot() });
  await expect(tunerRow(page, 3).locator('.chart-window-center title')).toHaveText('Center 859.46250 MHz');
  await expect(tableTuner).toBeFocused();
  await tableTuner.click();
  await expect(centerReadout(page.getByRole('dialog'))).toHaveText('856.96250 MHz');
  await page.keyboard.press('Escape');
  await expect(tableTuner).toBeFocused();
  app.respond({ data: changed });
  await expect(tunerRow(page, 3).locator('.chart-window-center title')).toHaveText('Center 859.21250 MHz');
  await tunerRow(page, 3).getByRole('button', { name: 'Details for RTL-SDR 3', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('859.21250 MHz');
  const settings = dialog.locator('details').filter({ has: page.getByText('Tuner settings', { exact: true }) });
  await settings.locator('summary').click();
  await settings.locator('summary').focus();
  app.respond({ data: snapshot() });
  await expect(centerReadout(dialog)).toHaveText('859.46250 MHz');
  await expect(tunerRow(page, 3).locator('.chart-window-center title')).toHaveText('Center 859.46250 MHz');
  await expect(settings).toHaveAttribute('open', '');
  await expect(settings.locator('summary')).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(tunerRow(page, 3).getByRole('button', { name: 'Details for RTL-SDR 3', exact: true })).toBeFocused();
  await page.getByRole('button', { name: 'Pause tuner allocation monitor' }).click();
  const count = app.requests();
  app.respond({ data: changed });
  await page.waitForTimeout(1150);
  expect(app.requests()).toBe(count);
  await tunerRow(page, 3).getByRole('button', { name: 'Details for RTL-SDR 3', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('Receiving · paused');
  await expect(centerReadout(page.getByRole('dialog'))).toHaveText('859.46250 MHz');
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Resume tuner allocation monitor' }).click();
  await expect(tunerRow(page, 3).locator('.chart-window-center title')).toHaveText('Center 859.21250 MHz');
});

test('history keeps grouped decisions and captured windows while current positions change', async ({ page }) => {
  const failed = decision(2, { outcome: 'allocation_failed', type: 'failure', repeat_count: 12,
    reason: "The frequency is outside the tuners' allowed range", requested_frequency_hz: 857_512_500,
    before_frequency_hz: null, after_frequency_hz: null, candidates: [{ tuner_name: 'RTL-SDR 3', reason: 'The center frequency is locked' }] });
  const app = await openApp(page, { data: snapshot([decision(), failed]) });
  await page.getByRole('button', { name: 'Decision history', exact: true }).click();
  const table = page.locator('table[data-table-type="tuner-allocation-decisions"]');
  await expect(table).toContainText('12 requests');
  await expect(table).not.toContainText('Call received');
  await page.getByRole('button', { name: 'Details for tuner decision 1' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('Receiving windows at this decision');
  await expect(dialog.locator('.chart-window-center title')).toHaveText(['Center 859.46250 MHz', 'Center 859.21250 MHz']);
  const changed = snapshot([decision(1, { after_frequency_hz: 858_912_500 }), failed]);
  changed.tuners[2].frequency_hz = 858_912_500;
  app.respond({ data: changed });
  await page.waitForTimeout(1150);
  await expect(dialog.locator('.chart-window-center title')).toHaveText(['Center 859.46250 MHz', 'Center 859.21250 MHz']);
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Details for tuner decision 2' }).click();
  await expect(page.getByRole('dialog')).toContainText('Requests12');
  await page.getByRole('dialog').getByText('Other tuners', { exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('The center frequency is locked');
  await page.keyboard.press('Escape');
  app.respond({ data: snapshot(Array.from({ length: 45 }, (_, index) => decision(index + 1))) });
  await expect(table.locator('tbody tr')).toHaveCount(20);
  await page.locator('.tuner-allocation-history-pager').getByRole('button', { name: 'Next' }).click();
  await expect(page.locator('.tuner-allocation-history-pager')).toContainText('Page 2 of 3');
});

for (const theme of ['light', 'dark']) {
  test(`coverage and tuner/decision drilldowns fit desktop and phone in ${theme}`, async ({ page }, testInfo) => {
    await openApp(page, { theme, data: snapshot([decision()]) });
    for (const width of [1280, 390, 320]) {
      await page.setViewportSize({ width, height: 900 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
      const header = page.locator('.tuner-allocation-selected-channel');
      const headerRect = await header.boundingBox();
      expect(headerRect.x).toBeGreaterThanOrEqual(0);
      expect(headerRect.x + headerRect.width).toBeLessThanOrEqual(width);
      await expect(header.locator('.tuner-allocation-monitor-toolbar').getByRole('button', {
        name: 'Pause tuner allocation monitor'
      })).toBeVisible();
      const site = sitePanel(page);
      const rect = await site.boundingBox();
      expect(rect.x + rect.width).toBeLessThanOrEqual(width);
      await expect(site.locator('.chart-frequency-windows')).toHaveCount(6);
      const boxes = await site.locator('.chart-frequency-windows').evaluateAll((charts) => charts.map((chart) => chart.getAttribute('viewBox').split(' ')[2]));
      expect(new Set(boxes).size).toBe(1);
      await tunerRow(page, 1).getByRole('button', { name: 'Details for RTL-SDR 1', exact: true }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog).toBeVisible();
      const dialogRect = await dialog.boundingBox();
      expect(dialogRect.x).toBeGreaterThanOrEqual(0);
      expect(dialogRect.x + dialogRect.width).toBeLessThanOrEqual(width);
      await expect(centerReadout(dialog)).toHaveText('855.36250 MHz');
      await expect(dialog).toContainText('854.53750 MHz');
      await expect(dialog).toContainText('856.21250 MHz');
      if (width === 1280 || width === 320) await page.screenshot({ animations: 'disabled', path: testInfo.outputPath(`tuner-details-${theme}-${width}.png`) });
      await page.keyboard.press('Escape');
      await expect(page.getByRole('dialog')).toHaveCount(0);
      if (width === 1280 || width === 320) await page.screenshot({ animations: 'disabled', path: testInfo.outputPath(`tuner-coverage-${theme}-${width}.png`), fullPage: true });
    }
    await page.getByRole('button', { name: 'Decision history', exact: true }).click();
    await page.getByRole('button', { name: 'Details for tuner decision 1' }).click();
    await expect(page.getByRole('dialog')).toContainText('Before · 859.46250 MHz');
    await expect(page.getByRole('dialog')).not.toContainText('Previous center');
    await page.screenshot({ animations: 'disabled', path: testInfo.outputPath(`tuner-decision-${theme}-320.png`) });
    await page.keyboard.press('Escape');
    await page.goto(`/design-system.html?view=receiving-windows&theme=${theme}`);
    await expect(page.locator('.visual-frequency-window-example')).toBeVisible();
    await page.screenshot({ animations: 'disabled', path: testInfo.outputPath(`receiving-window-gallery-${theme}-320.png`), fullPage: true });
  });
}

test('separate radio bands remain readable and unavailable or limited tuners never appear as frequency matches', async ({ page }) => {
  const data = snapshot();
  data.tuners[3].dc_gap_hz = 500_000;
  data.sites[0].known_frequency_count++;
  data.sites[0].frequencies.push({ frequency_hz: 856_962_500, bandwidth_hz: 12_500, covered: false, role: 'call' });
  data.tuners.push({ ...data.tuners[0], id: 'safe-tuner-5', name: 'RTL-SDR 5', frequency_hz: 773_987_500,
    min_frequency_hz: 760_000_000, max_frequency_hz: 800_000_000, supports_shared_window: false, dc_gap_hz: 0,
    receiving: [{ frequency_hz: 773_987_500, bandwidth_hz: 12_500, role: 'control', site_id: 'albany' }] });
  data.tuners.push({ ...data.tuners[0], id: 'safe-tuner-6', name: 'RTL-SDR 6', available: false, status: 'disabled',
    frequency_hz: 853_912_500, receiving: [] });
  data.tuners.push({ ...data.tuners[0], id: 'safe-tuner-7', name: 'RTL-SDR 7', frequency_hz: 854_712_500,
    min_frequency_hz: 854_100_000, max_frequency_hz: 855_000_000, receiving: [] });
  data.sites[0].tuners.push('safe-tuner-5');
  data.sites[0].known_frequency_count++;
  data.sites[0].covered_frequency_count++;
  data.sites[0].frequencies.push({ frequency_hz: 773_987_500, bandwidth_hz: 12_500, covered: true, role: 'control' });
  await openApp(page, { data });
  const site = sitePanel(page);
  await expect(site.locator('.tuner-allocation-window-row[data-tuner-id]')).toHaveCount(7);
  await expect(site.getByRole('button', { name: 'Highlight 853.73750 MHz', exact: true })).toBeVisible();
  await expect(site.getByRole('button', { name: 'Highlight 856.96250 MHz', exact: true })).toBeVisible();
  const gapGeometry = await tunerRow(page, 4).locator('svg').evaluate((chart) => ({
    gap: Number(chart.querySelector('.chart-window-gap').getAttribute('width')),
    window: Number(chart.querySelector('.chart-window-band').getAttribute('width'))
  }));
  expect(gapGeometry.gap / gapGeometry.window).toBeCloseTo(500_000 / 2_352_000, 5);
  const known = site.locator('details[data-allocation-disclosure="known"]');
  await known.locator('summary').click();
  const alternate = known.getByRole('row').filter({ hasText: '853.73750' });
  await expect(alternate).toContainText('Outside');
  await expect(alternate).not.toContainText('RTL-SDR 6');
  await expect(alternate).not.toContainText('RTL-SDR 7');
  await expect(known.getByRole('row').filter({ hasText: '773.98750' })).toContainText('RTL-SDR 5');
  await expect(known.getByRole('row').filter({ hasText: '856.96250' })).toContainText('Outside');
  await expect(known.getByRole('row').filter({ hasText: '854.53750' })).not.toContainText('RTL-SDR 5');
  await tunerRow(page, 6).getByRole('button', { name: 'Details for RTL-SDR 6', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('Unavailable');
  await page.keyboard.press('Escape');
  await page.setViewportSize({ width: 320, height: 900 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320);
});

test('site selection scopes known frequencies without hiding the tuner inventory', async ({ page }) => {
  const data = snapshot();
  data.sites.push({ id: 'other-site', name: 'Other site', system_name: 'Other system', channel_name: 'Other control',
    kind: 'trunked', known_frequency_count: 1, covered_frequency_count: 0, status: 'stopped', tuners: [],
    frequencies: [{ frequency_hz: 773_987_500, bandwidth_hz: 12_500, covered: false, role: 'control' }] });
  await openApp(page, { data });
  await changeChannel(page, 'other-site');
  const selected = page.locator('.tuner-allocation-site[data-site-id="other-site"]');
  await expect(page.locator('.tuner-allocation-selected-channel')).toContainText('Other system');
  await expect(selected).toContainText('0 / 1 in windows');
  await expect(selected.locator('.tuner-allocation-window-row[data-tuner-id]')).toHaveCount(4);
  await expect(selected.getByRole('button', { name: 'Highlight 773.98750 MHz', exact: true })).toBeVisible();
  await expect(sitePanel(page)).toHaveCount(0);
});

test('selected channel markers distinguish shared tuner work by site identity through selection and retunes', async ({ page }) => {
  const app = await openApp(page, { data: sharedTunerSnapshot() });
  const selected = (id) => page.locator(`.tuner-allocation-site[data-site-id="${id}"]`);
  const row = (siteId, tunerId) => selected(siteId).locator(
    `.tuner-allocation-window-row[data-tuner-id="safe-tuner-${tunerId}"]`);
  const albany = selected('albany');
  await expect(albany.locator('[data-tuner-id] .chart-window-selected')).toHaveCount(3);
  await expect(albany.locator('[data-tuner-id] .chart-window-other')).toHaveCount(4);
  await expect(albany.locator('[data-tuner-id] .chart-window-unscoped')).toHaveCount(1);
  await expect(row('albany', 1).locator('.chart-window-selected.chart-window-control')).toHaveCount(1);
  await expect(row('albany', 1).locator('.chart-window-selected.chart-window-call')).toHaveCount(1);
  await expect(receivingMarker(row('albany', 4), 'Call 856.21250 MHz · Colonie Simulcast'))
    .toHaveClass(/chart-window-other/);
  await expect(receivingMarker(row('albany', 1), 'Control channel 854.93750 MHz · Colonie Simulcast'))
    .toHaveClass(/chart-window-other/);
  await expect(receivingMarker(row('albany', 1), 'Conventional channel 855.18750 MHz · Albany County Simulcast'))
    .toHaveClass(/chart-window-other/);
  await expect(receivingMarker(row('albany', 1), 'Call 855.06250 MHz · Albany County Simulcast'))
    .toHaveClass(/chart-window-unscoped/);
  await expect(row('albany', 1).locator('.chart-window-other.chart-window-control')).toHaveCount(0);
  await expect(albany.locator('.tuner-allocation-known-row .chart-window-selected')).toHaveCount(0);
  await expect(albany.locator('.tuner-allocation-known-row .chart-window-known-dot')).toHaveCount(10);

  // A frequency shared with Albany becomes selected only when its receiving
  // channel belongs to the selected site. Saved names alone never select it.
  await changeChannel(page, 'colonie');
  const colonie = selected('colonie');
  await expect(colonie.locator('[data-tuner-id] .chart-window-selected')).toHaveCount(3);
  await expect(colonie.locator('[data-tuner-id] .chart-window-other')).toHaveCount(4);
  await expect(colonie.locator('[data-tuner-id] .chart-window-unscoped')).toHaveCount(1);
  await expect(receivingMarker(row('colonie', 4), 'Call 856.21250 MHz · Colonie Simulcast'))
    .toHaveClass(/chart-window-selected/);
  await expect(receivingMarker(row('colonie', 1), 'Call 856.21250 MHz · Albany County Simulcast'))
    .toHaveClass(/chart-window-other/);
  await expect(row('colonie', 4).locator('.chart-window-selected.chart-window-control')).toHaveCount(1);

  const changed = sharedTunerSnapshot();
  changed.tuners[3].frequency_hz = 857_112_500;
  app.respond({ data: changed });
  await expect(row('colonie', 4).locator('.chart-window-center title')).toHaveText('Center 857.11250 MHz');
  await expect(receivingMarker(row('colonie', 4), 'Call 856.21250 MHz · Colonie Simulcast'))
    .toHaveClass(/chart-window-selected/);
  await expect(receivingMarker(row('colonie', 1), 'Call 856.21250 MHz · Albany County Simulcast'))
    .toHaveClass(/chart-window-other/);

  await changeChannel(page, 'weather');
  const weather = selected('weather');
  await expect(weather.locator('[data-tuner-id] .chart-window-selected')).toHaveCount(1);
  await expect(receivingMarker(row('weather', 1), 'Conventional channel 855.18750 MHz · Albany County Simulcast'))
    .toHaveClass(/chart-window-selected/);
  await expect(weather.locator('[data-tuner-id] .chart-window-unscoped')).toHaveCount(1);
});

for (const theme of ['light', 'dark']) {
  test(`tuner map row separators and selected versus other markers remain clear in ${theme}`, async ({ page }, testInfo) => {
    await openApp(page, { theme, data: sharedTunerSnapshot() });
    // Freeze this style inspection so a live redraw cannot detach the SVG
    // between locating its marker and reading its computed appearance.
    await page.getByRole('button', { name: 'Pause tuner allocation monitor' }).click();
    for (const width of [1280, 320]) {
      await page.setViewportSize({ width, height: 900 });
      const site = sitePanel(page);
      const rows = site.locator('.tuner-allocation-window-row');
      await expect(rows).toHaveCount(6);
      const geometry = await rows.evaluateAll((elements) => elements.map((element) => {
        const style = getComputedStyle(element);
        const bounds = element.getBoundingClientRect();
        const chart = element.querySelector('.tuner-allocation-plot').getBoundingClientRect();
        return { borderStyle: style.borderTopStyle, borderWidth: parseFloat(style.borderTopWidth),
          borderColor: style.borderTopColor, background: getComputedStyle(element.closest('.ui-section')).backgroundColor,
          top: bounds.top, bottom: bounds.bottom, left: bounds.left, right: bounds.right,
          chartLeft: chart.left, chartRight: chart.right };
      }));
      for (const item of geometry.slice(2)) {
        expect(item.borderStyle).toBe('solid');
        expect(item.borderWidth).toBeGreaterThanOrEqual(1);
        expect(item.borderColor).not.toMatch(/^(transparent|rgba\(0, 0, 0, 0\))$/);
        expect(item.borderColor).not.toBe(item.background);
      }
      geometry.forEach((item, index) => {
        expect(item.left).toBeGreaterThanOrEqual(0);
        expect(item.right).toBeLessThanOrEqual(width);
        expect(item.chartLeft).toBeCloseTo(geometry[0].chartLeft, 0);
        expect(item.chartRight).toBeCloseTo(geometry[0].chartRight, 0);
        if (index) expect(item.top).toBeGreaterThanOrEqual(geometry[index - 1].bottom - .5);
      });
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
      const selectedCall = tunerRow(page, 1).locator('.chart-window-selected.chart-window-call');
      const otherCall = receivingMarker(tunerRow(page, 4), 'Call 856.21250 MHz · Colonie Simulcast');
      await expect(otherCall).toHaveCSS('fill', 'none');
      const selectedStyle = await selectedCall.evaluate((element) => ({
        fill: getComputedStyle(element).fill, stroke: getComputedStyle(element).stroke }));
      const otherStyle = await otherCall.evaluate((element) => ({
        fill: getComputedStyle(element).fill, stroke: getComputedStyle(element).stroke,
        strokeWidth: parseFloat(getComputedStyle(element).strokeWidth), tag: element.tagName }));
      expect(selectedStyle.fill).not.toBe('none');
      expect(otherStyle.fill).toBe('none');
      expect(otherStyle.stroke).not.toBe('none');
      expect(otherStyle.strokeWidth).toBeGreaterThanOrEqual(1);
      expect(otherStyle.tag).toBe('circle');
      expect(selectedStyle.fill).not.toBe(otherStyle.stroke);
      const unknown = tunerRow(page, 1).locator('.chart-window-unscoped');
      expect(await unknown.evaluate((element) => getComputedStyle(element).fill)).toBe('none');
      await page.screenshot({ animations: 'disabled', path: testInfo.outputPath(`shared-tuner-map-${theme}-${width}.png`), fullPage: true });
    }
  });
}

test('widely separated tuner bands show measured gaps and keep selected known frequencies in their own band', async ({ page }, testInfo) => {
  await openApp(page, { data: separatedBandsSnapshot() });
  const site = sitePanel(page);
  await expect(site.locator('.tuner-allocation-map')).toHaveCount(3);
  await expect(site.locator('.tuner-allocation-window-row[data-tuner-id]')).toHaveCount(6);
  await expect(site.getByText(/621\.64\s*MHz/)).toBeVisible();
  await expect(site.getByText(/75\.87\s*MHz/)).toBeVisible();
  const maps = site.locator('.tuner-allocation-map');
  for (const [index, minimum, maximum] of [[0, 140, 160], [1, 760, 790], [2, 840, 870]]) {
    const map = maps.nth(index);
    await expect(map.locator('.tuner-allocation-ruler-row')).toHaveCount(1);
    await expect(map.locator('.tuner-allocation-known-row')).toHaveCount(index === 2 ? 1 : 0);
    const ticks = await map.locator('.chart-axis-label').allTextContents();
    expect(ticks.length).toBeGreaterThanOrEqual(3);
    expect(Math.min(...ticks.map(Number))).toBeGreaterThan(minimum);
    expect(Math.max(...ticks.map(Number))).toBeLessThan(maximum);
  }
  await expect(maps.nth(0).locator('.chart-window-other')).toHaveCount(1);
  await expect(maps.nth(1).locator('.chart-window-other')).toHaveCount(1);
  await expect(maps.nth(2).locator('[data-tuner-id] .chart-window-selected')).toHaveCount(3);
  for (const width of [1280, 320]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await expect(site.getByText(/621\.64\s*MHz/)).toBeVisible();
    await expect(site.getByText(/75\.87\s*MHz/)).toBeVisible();
    await page.screenshot({ animations: 'disabled', path: testInfo.outputPath(`separated-tuner-bands-${width}.png`), fullPage: true });
  }
  await changeChannel(page, 'county-fire');
  const fire = page.locator('.tuner-allocation-site[data-site-id="county-fire"]');
  await expect(fire.locator('.tuner-allocation-map')).toHaveCount(3);
  await expect(fire.locator('.tuner-allocation-window-row[data-tuner-id]')).toHaveCount(6);
  await expect(fire.locator('.tuner-allocation-map').nth(0).locator('.tuner-allocation-known-row')).toHaveCount(1);
  await expect(fire.locator('.tuner-allocation-map').nth(2).locator('.tuner-allocation-known-row')).toHaveCount(0);
  await expect(fire.locator('[data-tuner-id] .chart-window-selected')).toHaveCount(1);
});

test('receiver session changes close details and reset captured history', async ({ page }) => {
  const app = await openApp(page, { data: snapshot([decision()]) });
  await page.getByRole('button', { name: 'Decision history', exact: true }).click();
  await page.getByRole('button', { name: 'Details for tuner decision 1' }).click();
  const restarted = snapshot();
  restarted.session_id = 'next-receiver-session';
  app.respond({ data: restarted });
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.locator('table[data-table-type="tuner-allocation-decisions"]')).toContainText('No tuner moves or failed calls');
});

test('access loss clears tuner status, history, and open details', async ({ page }) => {
  const app = await openApp(page);
  await tunerRow(page, 1).getByRole('button', { name: 'Details for RTL-SDR 1', exact: true }).click();
  app.respond({ status: 403 });
  await expect(page.getByRole('alert')).toContainText('You no longer have access to tuner allocation.');
  await expect(page.locator('.tuner-allocation-selected-channel')).toHaveCount(0);
  await expect(page.locator('.tuner-allocation-live-status')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Pause tuner allocation monitor' })).toHaveCount(0);
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.locator('.tuner-allocation-window-row[data-tuner-id]')).toHaveCount(0);
  await expect(page.locator('.tuner-allocation-workspace')).not.toContainText('855.36250 MHz');
});
