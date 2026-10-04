const { expect, test } = require('@playwright/test');
const { resolve } = require('node:path');
const { readFileSync } = require('node:fs');
const { pathToFileURL } = require('node:url');
const root = resolve(__dirname, '../../../../..');
const searchPath = '/api/v1/admin/spectrum-search';
const configurationId = '7f408d02-7c20-44b2-97ce-f6202b24b0f7';
const protocols = require(resolve(root, 'src/main/resources/channel-protocols.json'));
const dialog = (page) => page.locator('.spectrum-search-modal');
const bandLabels = ['VHF high · 138–174 MHz', 'UHF · 406–470 MHz', '700 MHz · 769–775 MHz',
  '800 MHz · 851–869 MHz', 'Custom range'];
const bandCheckbox = (page, label) => dialog(page).getByRole('checkbox', { name: label, exact: true });
const bandsTrigger = (page) => dialog(page).locator('.spectrum-search-band-trigger');
const bandsMenu = (page) => dialog(page).locator('.spectrum-search-band-popover');

async function openBands(page) {
  if (!await bandsMenu(page).evaluate((element) => element.matches(':popover-open'))) await bandsTrigger(page).click();
  await expect(bandsTrigger(page)).toHaveAttribute('aria-expanded', 'true');
  await expect(bandsMenu(page)).toBeVisible();
}

async function closeBands(page) {
  if (await bandsMenu(page).evaluate((element) => element.matches(':popover-open'))) await bandsTrigger(page).click();
  await expect(bandsTrigger(page)).toHaveAttribute('aria-expanded', 'false');
  await expect(bandsMenu(page)).toBeHidden();
}

function tuner(id, bandwidth) {
  return { id, name: id === 'idle-a' ? 'Small receiver' : 'Wide receiver', tuner_class: 'AIRSPY',
    status: 'ENABLED', available: true, operator_state: 'setup', channel_count: 0,
    frequency_hz: 773081250, center_frequency_hz: 773081250, sample_rate_hz: bandwidth, usable_bandwidth_hz: bandwidth,
    minimum_frequency_hz: 24000000, maximum_frequency_hz: 1800000000,
    spectrum_target_id: `target-${id}`, settings: [{ id: 'frequency_mhz', label: 'Center frequency',
      value: 773.08125, editable: true, availability: 'setup', minimum: 24, maximum: 1800,
      dependencies: [] }] };
}

function snapshot(state) {
  const candidates = [
    { candidate_id: 'north', frequency_hz: 773081250, name: 'County North', system_name: 'County Public Safety',
      identity: { wacn: 0xbee00, system: 0x348, rfss: 2, site: 27 }, modulation: 'C4FM', alias_group_id: 'county' },
    { candidate_id: 'central', frequency_hz: 773831250, name: 'County Central', system_name: 'County Public Safety',
      identity: { wacn: 0xbee00, system: 0x348, rfss: 2, site: 1 }, modulation: 'CQPSK', alias_group_id: 'county' },
    { candidate_id: 'regional', frequency_hz: 860012500, name: 'Regional South', system_name: 'Regional Services',
      identity: { wacn: 0xabc00, system: 0x234, rfss: 1, site: 3 }, modulation: 'CQPSK', alias_group_id: 'regional' },
    { candidate_id: 'known', frequency_hz: 769431250, name: 'Saved East', system_name: 'County Public Safety',
      identity: { wacn: 0xbee00, system: 0x348, rfss: 2, site: 4 }, modulation: 'C4FM', alias_group_id: 'county',
      selectable: false, known_channel: { configuration_id: configurationId, name: 'Existing East Control',
        entity_ref: { kind: 'channel', key: configurationId } } }
  ].map((candidate, index) => ({ selectable: true, saved: false, running: false,
    strength_dbfs: -35.2 - index * 10, health: { quality_pct: 99 - index, valid_messages: 54,
      valid_control_messages: 41, invalid_control_messages: 1, checked_at_ms: 1790878800000 },
    ...candidate, ...(state.ledger?.[candidate.candidate_id] || {}) }));
  return { job_id: 'search-a', phase: state.phase || 'complete', revision: state.revision || 7,
    reason: state.reason || null,
    expires_at_ms: 1790880600000, restart_required: Boolean(state.restartRequired), truncated_reason: state.truncated ? 'Candidate limit reached' : null,
    progress: { completed: 2, total: 4, current_frequency_hz: 773000000, checked: 1, total_signals: 4 }, candidates: state.empty ? [] : state.customCandidates || candidates,
    alias_groups: state.customAliasGroups || [{ group_id: 'county', wacn: 0xbee00, system: 0x348,
      alias_lists: state.ambiguous ? [{ id: 21, name: 'County Dispatch' }, { id: 22, name: 'County Operations' }] :
        [{ id: 21, name: 'County Dispatch' }], suggested_alias_list_id: null, default_new_alias_list_name: 'County P25' },
    { group_id: 'regional', wacn: 0xabc00, system: 0x234, alias_lists: [], suggested_alias_list_id: null,
      default_new_alias_list_name: 'Regional P25' }] };
}

async function install(page, state = {}) {
  state.requests = [];
  state.ledger = {};
  state.tuners = state.recording ? [{ ...tuner('capture-a', 2000000), name: 'Trunked capture',
    tuner_class: 'RECORDING', source_type: 'recording', fixed_window: true, center_frequency_hz: 451000000, frequency_hz: undefined,
    configured_frequency_hz: 773081250,
    minimum_frequency_hz: 450000000, maximum_frequency_hz: 452000000 }] :
    [tuner('idle-a', 2200000), tuner('idle-b', 9000000)];
  if (state.centerFrequencies) state.tuners.forEach((receiver, index) => {
    receiver.center_frequency_hz = state.centerFrequencies[index];
  });
  if (state.busyWide) state.tuners[1] = { ...state.tuners[1], operator_state: 'live', channel_count: 2 };
  let leaseSequence = 0;
  const takeoverLeases = new Set();
  const preferenceModule = await import(pathToFileURL(resolve(root, 'stats-web/assets/core/preference-schema.js')).href);
  await page.route('**/assets/app.js*', (route) => route.fulfill({ contentType: 'text/javascript',
    body: `${readFileSync(resolve(root, 'stats-web/assets/app.js'), 'utf8')}\nexport { closeReadOnlyModal };` }));
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const body = JSON.parse(request.postData() || '{}');
    state.requests.push({ path, method: request.method(), body });
    const respond = (value, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ data: value }) });
    const fail = (message, code = 'unavailable', status = 503) => route.fulfill({ status, contentType: 'application/json',
      body: JSON.stringify({ error: { message, code } }) });
    if (path === '/api/v1/auth/session') return respond({ configured: true, authenticated: true, tier: 'admin',
      csrf_token: 'test-token', capabilities: { 'web-access': true, dashboard: true, 'tuner-spectrum': true,
        'admin-tuners': state.adminTuners !== false, 'admin-channels': state.adminChannels !== false, 'admin-aliases': true } });
    if (path === '/api/v1/me/preferences') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({
      revision: 1, preferences: { ...preferenceModule.defaults, appearance: { hue: null, theme: state.theme || 'light' } } }) });
    if (path === '/api/v1/spectrum-snap-presets') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({
      revision: 1, country_code: 'US', country_label: 'United States', countries: [{ code: 'US', label: 'United States' }], scopes: [] }) });
    if (path === '/api/v1/admin/radioreference') return state.directoryOffline ?
      fail('Directory unavailable', 'unavailable', 503) : respond(state.directoryConfiguration || {});
    if (path === '/api/v1/admin/radioreference/states') return respond({ items: [{ id: 39, name: 'Ohio' }, { id: 42, name: 'Pennsylvania' }] });
    if (path === '/api/v1/admin/channels') return respond({ revision: 1, channels: [] });
    if (path === '/api/v1/admin/channels/protocols') return respond(protocols);
    if (path === '/api/v1/admin/channels/options') return respond({ alias_lists: [] });
    if (path === '/api/v1/admin/tuners') return respond({ tuners: state.tuners });
    if (path.endsWith('/browse')) {
      if (request.method() === 'DELETE') {
        if (state.failNextBrowseDeleteConflict) {
          state.failNextBrowseDeleteConflict = false;
          return fail('Signal identification still holds this receiver', 'tuner_browse_unavailable', 409);
        }
        if (state.failNextBrowseDelete) {
          state.failNextBrowseDelete = false;
          return fail('Receiver release failed', 'tuner_browse_failed', 503);
        }
        return respond(null, 204);
      }
      if (body.lease_id && state.failNextBrowseRenewConflict) {
        state.failNextBrowseRenewConflict = false;
        return fail('Receiver session no longer exists', 'tuner_browse_unavailable', 409);
      }
      const id = path.split('/').at(-2);
      const receiver = state.tuners.find((candidate) => candidate.id === id);
      const leaseId = body.lease_id || `lease-${++leaseSequence}`;
      if (body.takeover === true) takeoverLeases.add(leaseId);
      const takeover = takeoverLeases.has(leaseId);
      const leasedReceiver = takeover ? { ...receiver, operator_state: 'setup', channel_count: 0 } : receiver;
      return respond({ lease_id: leaseId, tuner: leasedReceiver, takeover,
        stopped_channels: takeover ? [{ configuration_id: 'channel-a' }] : [],
        expires_at_epoch_ms: Date.now() + 30000, can_tune: takeover || !receiver.channel_count });
    }
    if (path === '/api/v1/diagnostics/tuners') return respond({ rows: [] });
    if (path === `${searchPath}/catalog`) return respond({ tuners: state.noCatalogTuners ? [] :
      state.tuners.map((receiver) => {
        const channelCount = state.noIdle ? 2 : receiver.channel_count;
        const locked = Boolean(state.lockedOnly);
        return { ...receiver,
        channel_count: channelCount,
        center_frequency_locked: locked,
        takeover_allowed: true,
        eligible: channelCount === 0 && !locked,
        reason: channelCount ? 'Channels are running' : locked ? 'Center frequency is locked' : null };
      }),
      suggested_tuner_id: state.noIdle || state.lockedOnly || state.noCatalogTuners ? null :
        (state.busyWide ? 'idle-a' : 'idle-b'),
      presets: [{ id: 'vhf-high', label: 'VHF high · 138–174 MHz', ranges: [{ minimum_hz: 138000000, maximum_hz: 174000000 }] },
        { id: 'uhf', label: 'UHF · 406–470 MHz', ranges: [{ minimum_hz: 406000000, maximum_hz: 470000000 }] },
        { id: '700mhz', label: '700 MHz · 769–775 MHz', ranges: [{ minimum_hz: 769000000, maximum_hz: 775000000 }] },
        { id: '800mhz', label: '800 MHz · 851–869 MHz', ranges: [{ minimum_hz: 851000000, maximum_hz: 869000000 }] }],
      bounds: { maximum_ranges: 8, maximum_windows: 64, maximum_candidates: 32, maximum_total_hz: 150000000,
        minimum_dwell_ms: 750, maximum_dwell_ms: 5000, default_dwell_ms: 1500, maximum_scan_ms: 900000 } });
    if (path === searchPath && request.method() === 'POST') {
      if (state.failSearchCreateOnce) {
        state.failSearchCreateOnce = false;
        return fail('Search could not start', 'spectrum_search_failed', 503);
      }
      if (state.invalidRequest) return fail(state.invalidRequest, 'invalid_request', 400);
      if (state.delayCreate) await new Promise((resolve) => { state.releaseCreate = resolve; });
      return respond(snapshot(state));
    }
    if (path === `${searchPath}/search-a`) {
      if (request.method() === 'DELETE') {
        if (state.failNextJobDelete) {
          state.failNextJobDelete = false;
          return fail('Search release failed', 'spectrum_search_failed', 503);
        }
        return respond(null, 204);
      }
      if (state.expired) return fail('Search expired', 'search_expired', 410);
      if (state.restartRequired && state.failLedgerOnce) { state.failLedgerOnce = false; return fail('Connection interrupted'); }
      if (state.failPollOnce) { state.failPollOnce = false; return fail('Connection interrupted'); }
      return respond(snapshot(state));
    }
    if (path.endsWith('/save')) {
      if (state.expired) return fail('Search expired', 'search_expired', 410);
      if (state.staleOnce) { state.staleOnce = false; state.revision = 8; return fail('Configuration changed', 'stale_revision', 409); }
      if (state.delaySave) await new Promise((resolve) => { state.releaseSave = resolve; });
      for (const candidate of body.candidates) {
        if (state.restartRequired) break;
        if (state.raceDuplicate === candidate.candidate_id) {
          state.ledger[candidate.candidate_id] = { saved: false, known_channel: {
            configuration_id: configurationId, name: 'Concurrent saved control' }, save_error: 'This frequency already belongs to a saved channel' };
        } else if (state.failCandidateOnce === candidate.candidate_id) {
          state.failCandidateOnce = null;
          state.ledger[candidate.candidate_id] = { saved: false, save_error: 'This channel could not be added. Retry.' };
        } else {
          state.ledger[candidate.candidate_id] = { saved: true, name: candidate.name, configuration_id: configurationId,
            alias_list_id: candidate.candidate_id === 'regional' ? 42 : 21, auto_start: candidate.auto_start };
          if (state.publicationFailure) state.restartRequired = true;
        }
      }
      if (state.restartRequired) return fail('Channel saved, restart required', 'channel_saved_restart_required');
      return respond(snapshot(state));
    }
    return respond({});
  });
  await page.goto('/app.html?view=channel-setup');
  if (state.adminTuners === false || state.adminChannels === false) {
    await expect(page.getByRole('heading', { name: state.adminChannels === false ? 'Access denied' : 'Channels', exact: true })).toBeVisible();
    return state;
  }
  await expect(page.getByRole('button', { name: 'Find Trunked Systems', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Find Trunked Systems', exact: true }).click();
  await expect(dialog(page).getByRole('heading', {
    name: state.noIdle || state.lockedOnly || state.noCatalogTuners ? 'Choose a receiver' : 'Choose where to look'
  })).toBeVisible();
  return state;
}

async function complete(page) {
  await closeBands(page);
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(dialog(page).getByRole('heading', { name: /channels? found$/ })).toBeVisible();
}

test('uses one widest idle receiver and acquires its own browse session when searching', async ({ page }) => {
  const state = await install(page);
  await expect(dialog(page).getByLabel('Receiver', { exact: true })).toHaveValue('idle-b');
  expect(state.requests.some((request) => request.path.endsWith('/browse'))).toBe(false);
  await expect(bandsTrigger(page)).toHaveAccessibleName('Bands 700 MHz, 800 MHz');
  await expect(bandsMenu(page)).toBeHidden();
  const modalBefore = await dialog(page).boundingBox();
  const receiverChevron = dialog(page).getByLabel('Receiver', { exact: true }).locator('xpath=..').locator('svg use');
  const bandsChevron = dialog(page).locator('.spectrum-search-band-frame > svg use');
  expect(await bandsChevron.getAttribute('href')).toBe(await receiverChevron.getAttribute('href'));
  await openBands(page);
  const modalAfter = await dialog(page).boundingBox();
  expect(modalAfter).toEqual(modalBefore);
  await expect(dialog(page).getByRole('group', { name: 'Bands', exact: true }).getByRole('checkbox')).toHaveCount(5);
  for (const label of bandLabels) await expect(bandCheckbox(page, label)).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog(page)).toBeVisible();
  await expect(bandsMenu(page)).toBeHidden();
  await openBands(page);
  await expect(bandCheckbox(page, bandLabels[0])).not.toBeChecked();
  await expect(bandCheckbox(page, bandLabels[1])).not.toBeChecked();
  await expect(bandCheckbox(page, bandLabels[2])).toBeChecked();
  await expect(bandCheckbox(page, bandLabels[3])).toBeChecked();
  await expect(bandCheckbox(page, bandLabels[4])).not.toBeChecked();
  await expect(dialog(page).getByRole('combobox', { name: 'Band', exact: true })).toHaveCount(0);
  await expect(dialog(page).getByText('700 and 800 MHz public safety', { exact: true })).toHaveCount(0);
  await expect(dialog(page).getByLabel('Start frequency (MHz)')).toBeHidden();
  await expect(dialog(page).getByLabel('End frequency (MHz)')).toBeHidden();
  await expect(dialog(page).getByLabel('Start frequency (MHz)')).not.toHaveAttribute('required', '');
  await expect(dialog(page).getByLabel('End frequency (MHz)')).not.toHaveAttribute('required', '');
  await expect(dialog(page).locator('.spectrum-search-form > .ui-notice'))
    .toHaveText('Checks promising signals for consistent trunked system and site identity.');
  await expect(dialog(page).getByText('What are P25 channels?', { exact: true })).toHaveCount(0);
  await expect(dialog(page).getByText('Use another receiver', { exact: true })).toHaveCount(0);
  await complete(page);
  const create = state.requests.find((request) => request.path === searchPath && request.method === 'POST');
  expect(create.body).toEqual({ tuner_id: 'idle-b', browse_lease_id: 'lease-1', radioreference_state_id: null, ranges: [
    { minimum_hz: 769000000, maximum_hz: 775000000 }, { minimum_hz: 851000000, maximum_hz: 869000000 }] });
  const acquired = state.requests.findIndex((request) => request.path === '/api/v1/admin/tuners/idle-b/browse' && request.method === 'POST');
  expect(acquired).toBeGreaterThan(-1);
  expect(acquired).toBeLessThan(state.requests.indexOf(create));
  expect(state.requests.some((request) => request.path === '/api/v1/admin/tuners/idle-a/browse')).toBe(false);
  await expect(dialog(page).getByRole('checkbox', { name: 'Select Saved East' })).toBeDisabled();
  await expect(dialog(page).getByRole('link', { name: 'Existing East Control' })).toHaveAttribute('href', /configuration_id=/);
  await expect(dialog(page).locator('.spectrum-search-group-table').first().locator('thead th'))
    .toHaveText(['Channel', 'Site', 'Signal', 'Health']);
  await expect(dialog(page).locator('.spectrum-search-group-table').first().locator('tbody tr').first().locator('td'))
    .toHaveCount(4);
  await expect(dialog(page)).toContainText('WACN BEE00 · SysID 348 · 3 sites');
  await expect(dialog(page).getByText('Signal details', { exact: true })).toHaveCount(4);
  await dialog(page).getByLabel('Search results', { exact: true }).fill('Regional Services');
  await expect(dialog(page).locator('tbody tr:visible')).toHaveCount(1);
  await dialog(page).getByLabel('Search results', { exact: true }).fill('840');
  await expect(dialog(page).locator('tbody tr:visible')).toHaveCount(3);
});

test('uses only eligible receivers when a wider receiver is busy', async ({ page }) => {
  const state = await install(page, { busyWide: true });
  const search = dialog(page);
  const receiver = search.getByLabel('Receiver', { exact: true });
  await expect(receiver).toHaveValue('idle-a');
  await expect(receiver.locator('option')).toHaveText(['Small receiver · 773.081250 MHz @ 2.20 MHz']);
  await expect(search.getByText('Use another receiver', { exact: true })).toHaveCount(0);
  await expect(search.getByRole('button', { name: 'Stop channels and use: Wide receiver', exact: true }))
    .toHaveCount(0);
  await complete(page);
  const create = state.requests.find((request) => request.path === searchPath && request.method === 'POST');
  expect(create.body.tuner_id).toBe('idle-a');
  expect(state.requests.some((request) => request.path === '/api/v1/admin/tuners/idle-b/browse' &&
    request.method === 'POST' && request.body.takeover === true)).toBe(false);
});

test('receiver picker and summary show compact live frequency and width without acquiring a receiver', async ({ page }) => {
  const state = await install(page, { centerFrequencies: [773081250, 852400000] });
  const search = dialog(page);
  const receiver = search.getByLabel('Receiver', { exact: true });
  await expect(receiver.locator('option')).toHaveText([
    'Small receiver · 773.081250 MHz @ 2.20 MHz',
    'Wide receiver · 852.400000 MHz @ 9.00 MHz'
  ]);
  await expect(search.locator('.spectrum-discovery-context')).toContainText('852.400000 MHz @ 9.00 MHz');
  await receiver.selectOption('idle-a');
  await expect(search.locator('.spectrum-discovery-context')).toContainText('773.081250 MHz @ 2.20 MHz');
  expect(state.requests.some((request) => request.method !== 'GET')).toBe(false);
  await search.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(search).toHaveCount(0);
  expect(state.requests.some((request) => request.path.endsWith('/browse'))).toBe(false);
});

test('refreshing in-use receivers updates compact frequency and width without saved defaults', async ({ page }) => {
  const state = await install(page, { noIdle: true, centerFrequencies: [773081250, 852400000] });
  const cards = dialog(page).locator('.spectrum-search-receiver-card');
  await expect(cards.first()).toContainText('773.081250 MHz @ 2.20 MHz');
  await expect(cards.last()).toContainText('852.400000 MHz @ 9.00 MHz');
  await expect(cards.locator('.spectrum-search-receiver-detail')).toHaveText([
    '773.081250 MHz @ 2.20 MHz', '852.400000 MHz @ 9.00 MHz'
  ]);
  await expect(dialog(page).locator('.spectrum-search-unavailable-intro'))
    .toContainText('Calls in progress will end and will not resume');
  state.tuners[0].center_frequency_hz = 856162500;
  state.tuners[0].usable_bandwidth_hz = 2350000;
  for (const unavailable of [null, 0, 'not-a-frequency']) {
    state.tuners[1].center_frequency_hz = unavailable;
    await dialog(page).getByRole('button', { name: 'Refresh receivers', exact: true }).click();
    await expect(cards.first()).toContainText('856.162500 MHz @ 2.35 MHz');
    await expect(cards.last()).toContainText('Frequency unavailable @ 9.00 MHz');
    await expect(cards.last()).not.toContainText('773.081250 MHz');
  }
  expect(state.requests.filter((request) => request.path === `${searchPath}/catalog`)).toHaveLength(4);
  state.noIdle = false;
  await dialog(page).getByRole('button', { name: 'Refresh receivers', exact: true }).click();
  const receiver = dialog(page).getByLabel('Receiver', { exact: true });
  await expect(receiver.locator('option')).toHaveText([
    'Small receiver · 856.162500 MHz @ 2.35 MHz', 'Wide receiver · Frequency unavailable @ 9.00 MHz'
  ]);
  await receiver.selectOption('idle-a');
  await expect(dialog(page).locator('.spectrum-discovery-context > .muted'))
    .toHaveText('856.162500 MHz @ 2.35 MHz');
  expect(state.requests.some((request) => request.method !== 'GET')).toBe(false);
});

test('failed search creation restores a borrowed receiver and refreshes its catalog row', async ({ page }) => {
  const state = await install(page, { noIdle: true, failSearchCreateOnce: true });
  const search = dialog(page);
  await search.getByRole('button', { name: 'Stop channels and use: Small receiver', exact: true }).click();
  await page.getByRole('alertdialog', { name: 'Stop channels for this search?' })
    .getByRole('button', { name: 'Stop channels and search', exact: true }).click();
  const catalogsBefore = state.requests.filter((request) => request.path === `${searchPath}/catalog`).length;

  await search.getByRole('button', { name: 'Find signals', exact: true }).click();

  await expect(search).toContainText('The receiver was restored');
  await expect(search.getByRole('heading', { name: 'Choose a receiver', exact: true })).toBeVisible();
  await expect(search.getByRole('button', { name: 'Stop channels and use' })).toHaveCount(2);
  expect(state.requests.filter((request) => request.path === `${searchPath}/catalog`)).toHaveLength(catalogsBefore + 1);
  expect(state.requests.some((request) => request.path === '/api/v1/admin/tuners/idle-a/browse' &&
    request.method === 'DELETE' && request.body.lease_id === 'lease-1')).toBe(true);
});

test('preparing and starting with a borrowed receiver leaves one renewal timer', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { noIdle: true });
  const search = dialog(page);
  await search.getByRole('button', { name: 'Stop channels and use: Small receiver', exact: true }).click();
  await page.getByRole('alertdialog', { name: 'Stop channels for this search?' })
    .getByRole('button', { name: 'Stop channels and search', exact: true }).click();
  await search.getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(search.getByRole('heading', { name: /channels? found$/ })).toBeVisible();
  const renewalCount = () => state.requests.filter((request) =>
    request.path === '/api/v1/admin/tuners/idle-a/browse' && request.method === 'POST' &&
    request.body.lease_id === 'lease-1').length;
  const before = renewalCount();

  await page.clock.fastForward(10_100);

  await expect.poll(renewalCount).toBe(before + 1);
});

test('band picker remains bounded and keyboard accessible on a narrow screen', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 700 });
  await install(page);
  const modalBefore = await dialog(page).boundingBox();
  await bandsTrigger(page).focus();
  await bandsTrigger(page).press('ArrowDown');
  await expect(bandCheckbox(page, bandLabels[0])).toBeFocused();
  const modalAfter = await dialog(page).boundingBox();
  const menu = await bandsMenu(page).boundingBox();
  expect(modalAfter).toEqual(modalBefore);
  expect(menu.x).toBeGreaterThanOrEqual(0);
  expect(menu.x + menu.width).toBeLessThanOrEqual(390);
  expect(menu.y).toBeGreaterThanOrEqual(0);
  expect(menu.y + menu.height).toBeLessThanOrEqual(700);
  await page.keyboard.press('Escape');
  await expect(bandsMenu(page)).toBeHidden();
  await expect(bandsTrigger(page)).toBeFocused();
  await openBands(page);
  await expect(bandCheckbox(page, bandLabels[2])).toBeFocused();
  await bandsTrigger(page).focus();
  await dialog(page).getByLabel('Receiver', { exact: true }).focus();
  await expect(bandsMenu(page)).toBeHidden();
});

test('search requires both tuner and channel administration access', async ({ page }) => {
  const state = await install(page, { adminChannels: false });
  await expect(page.getByRole('button',
    { name: 'Find Trunked Systems', exact: true })).toHaveCount(0);
  expect(state.requests.some((request) => request.path.startsWith(searchPath))).toBe(false);
  const withoutTuners = await install(page, { adminTuners: false });
  await expect(page.getByRole('button', { name: 'Find Trunked Systems', exact: true })).toHaveCount(0);
  expect(withoutTuners.requests.some((request) => request.path.startsWith(searchPath))).toBe(false);
});

test('requires at least one independent band and preserves selected presets after restarting', async ({ page }) => {
  const state = await install(page);
  const find = dialog(page).getByRole('button', { name: 'Find signals', exact: true });
  await openBands(page);
  await bandCheckbox(page, bandLabels[2]).uncheck();
  await bandCheckbox(page, bandLabels[3]).uncheck();
  await expect(find).toBeDisabled();
  expect(state.requests.some((request) => request.path === searchPath)).toBe(false);
  await bandCheckbox(page, bandLabels[0]).check();
  await bandCheckbox(page, bandLabels[1]).check();
  await expect(find).toBeEnabled();
  await complete(page);
  expect(state.requests.find((request) => request.path === searchPath).body.ranges).toEqual([
    { minimum_hz: 138000000, maximum_hz: 174000000 }, { minimum_hz: 406000000, maximum_hz: 470000000 }]);
  await dialog(page).getByRole('button', { name: 'Search another band' }).click();
  await expect(dialog(page).getByRole('heading', { name: 'Choose where to look' })).toBeVisible();
  await expect(bandsTrigger(page)).toHaveAccessibleName('Bands VHF high, UHF');
  await openBands(page);
  await expect(bandCheckbox(page, bandLabels[0])).toBeChecked();
  await expect(bandCheckbox(page, bandLabels[1])).toBeChecked();
  await expect(bandCheckbox(page, bandLabels[2])).not.toBeChecked();
  await expect(bandCheckbox(page, bandLabels[3])).not.toBeChecked();
  await expect(bandCheckbox(page, bandLabels[4])).not.toBeChecked();
  await bandCheckbox(page, bandLabels[0]).uncheck();
  await bandCheckbox(page, bandLabels[1]).uncheck();
  await expect(find).toBeDisabled();
  expect(state.requests.filter((request) => request.path === searchPath)).toHaveLength(1);
});

test('honors manual receiver choice and validates a bounded custom range before opening a job', async ({ page }) => {
  const state = await install(page);
  await dialog(page).getByLabel('Receiver', { exact: true }).selectOption('idle-a');
  await openBands(page);
  await bandCheckbox(page, bandLabels[2]).uncheck();
  await bandCheckbox(page, bandLabels[3]).uncheck();
  await bandCheckbox(page, bandLabels[4]).check();
  await closeBands(page);
  await expect(dialog(page).getByLabel('Start frequency (MHz)')).toBeVisible();
  await expect(dialog(page).getByLabel('End frequency (MHz)')).toBeVisible();
  await expect(dialog(page).getByLabel('Start frequency (MHz)')).toHaveAttribute('required', '');
  await expect(dialog(page).getByLabel('End frequency (MHz)')).toHaveAttribute('required', '');
  await dialog(page).getByLabel('Start frequency (MHz)').fill('');
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  expect(state.requests.some((request) => request.path === searchPath)).toBe(false);
  await dialog(page).getByLabel('Start frequency (MHz)').fill('773.2');
  await dialog(page).getByLabel('End frequency (MHz)').fill('773.1');
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(dialog(page)).toContainText('end frequency above the start');
  expect(state.requests.some((request) => request.path === searchPath)).toBe(false);
  await dialog(page).getByLabel('End frequency (MHz)').fill('773.9');
  await complete(page);
  const create = state.requests.find((request) => request.path === searchPath);
  expect(create.body.tuner_id).toBe('idle-a');
  expect(create.body.ranges).toEqual([{ minimum_hz: 773200000, maximum_hz: 773900000 }]);
  await dialog(page).getByRole('button', { name: 'Search another band' }).click();
  await expect(dialog(page).getByLabel('Receiver', { exact: true })).toHaveValue('idle-a');
  await expect(bandsTrigger(page)).toHaveAccessibleName('Bands Custom range');
  await openBands(page);
  await expect(bandCheckbox(page, bandLabels[2])).not.toBeChecked();
  await expect(bandCheckbox(page, bandLabels[3])).not.toBeChecked();
  await expect(bandCheckbox(page, bandLabels[4])).toBeChecked();
  await expect(dialog(page).getByLabel('Start frequency (MHz)')).toHaveValue('773.2');
  await expect(dialog(page).getByLabel('End frequency (MHz)')).toHaveValue('773.9');
});

test('keyboard band choices combine presets with a custom range and preserve the custom draft', async ({ page }) => {
  const state = await install(page);
  const custom = bandCheckbox(page, bandLabels[4]);
  const minimum = dialog(page).getByLabel('Start frequency (MHz)');
  const maximum = dialog(page).getByLabel('End frequency (MHz)');
  await bandsTrigger(page).focus();
  await bandsTrigger(page).press('ArrowDown');
  await expect(bandCheckbox(page, bandLabels[0])).toBeFocused();
  await bandCheckbox(page, bandLabels[3]).press('Space');
  await bandCheckbox(page, bandLabels[0]).press('Space');
  await custom.press('Space');
  await expect(custom).toBeChecked();
  await closeBands(page);
  await expect(minimum).toBeVisible();
  await minimum.fill('482');
  await maximum.fill('484');
  await openBands(page);
  await custom.press('Space');
  await expect(custom).not.toBeChecked();
  await expect(minimum).toBeHidden();
  await expect(maximum).toBeHidden();
  await expect(minimum).not.toHaveAttribute('required', '');
  await expect(maximum).not.toHaveAttribute('required', '');
  await expect(minimum).toHaveValue('482');
  await expect(maximum).toHaveValue('484');
  await custom.press('Space');
  await expect(custom).toBeChecked();
  await expect(minimum).toHaveAttribute('required', '');
  await expect(maximum).toHaveAttribute('required', '');
  await expect(minimum).toHaveValue('482');
  await expect(maximum).toHaveValue('484');
  await complete(page);
  expect(state.requests.find((request) => request.path === searchPath).body.ranges).toEqual([
    { minimum_hz: 138000000, maximum_hz: 174000000 }, { minimum_hz: 769000000, maximum_hz: 775000000 },
    { minimum_hz: 482000000, maximum_hz: 484000000 }]);
  await dialog(page).getByRole('button', { name: 'Search another band' }).click();
  await openBands(page);
  await expect(bandCheckbox(page, bandLabels[0])).toBeChecked();
  await expect(bandCheckbox(page, bandLabels[1])).not.toBeChecked();
  await expect(bandCheckbox(page, bandLabels[2])).toBeChecked();
  await expect(bandCheckbox(page, bandLabels[3])).not.toBeChecked();
  await expect(custom).toBeChecked();
  await expect(minimum).toBeVisible();
  await expect(maximum).toBeVisible();
  await expect(minimum).toHaveValue('482');
  await expect(maximum).toHaveValue('484');
});

test('hides partial results, retries polling and discards the job before releasing its receiver', async ({ page }) => {
  const state = await install(page, { phase: 'scanning', failPollOnce: true });
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(dialog(page).getByRole('heading', { name: 'Find signals', exact: true })).toBeVisible();
  await expect(dialog(page).getByRole('checkbox')).toHaveCount(0);
  await expect(dialog(page).getByRole('button', { name: 'Retry connection' })).toBeVisible();
  state.phase = 'checking';
  await dialog(page).getByRole('button', { name: 'Retry connection' }).click();
  await expect(dialog(page).getByRole('heading', { name: 'Check signals', exact: true })).toBeVisible();
  await expect(dialog(page).getByRole('checkbox')).toHaveCount(0);
  state.phase = 'complete';
  await expect(dialog(page).getByRole('heading', { name: /channels? found$/ })).toBeVisible();
  await dialog(page).getByRole('button', { name: 'Close Find Trunked Systems' }).click();
  await expect(dialog(page)).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Find Trunked Systems', exact: true })).toBeEnabled();
  await expect.poll(() => state.requests.some((request) => request.path === '/api/v1/admin/tuners/idle-b/browse' && request.method === 'DELETE')).toBe(true);
  const jobDelete = state.requests.findIndex((request) => request.path === `${searchPath}/search-a` && request.method === 'DELETE');
  const browseDelete = state.requests.findIndex((request, index) => index > jobDelete && request.path === '/api/v1/admin/tuners/idle-b/browse' && request.method === 'DELETE');
  expect(jobDelete).toBeGreaterThan(-1);
  expect(browseDelete).toBeGreaterThan(jobDelete);
});

test('groups exact identities, saves channels stopped, and retries only failed adds', async ({ page }) => {
  const state = await install(page, { failCandidateOnce: 'regional' });
  await complete(page);
  await dialog(page).getByRole('button', { name: 'Select all available' }).click();
  await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
  await expect(dialog(page).getByText('Alias List: County Dispatch · Existing', { exact: true })).toHaveCount(1);
  await dialog(page).getByRole('button', { name: 'Customize' }).nth(1).click();
  await expect(dialog(page).getByLabel('New Alias List name for ABC00-234')).toHaveValue('Regional P25');
  await expect(dialog(page).getByRole('checkbox', { name: /Listen now|Auto-start channels/ })).toHaveCount(0);
  await expect(dialog(page).getByLabel('Start with')).toHaveCount(0);
  await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
  await expect(dialog(page).getByRole('heading', { name: 'Your channel results' })).toBeVisible();
  await expect(dialog(page).getByText('Added', { exact: true })).toHaveCount(2);
  await expect(dialog(page).getByText('Could not add', { exact: true })).toHaveCount(1);
  const save = state.requests.find((request) => request.path.endsWith('/save'));
  expect(save.body.alias_groups).toEqual([{ group_id: 'county', alias_list_id: 21, new_alias_list_name: 'County P25' },
    { group_id: 'regional', alias_list_id: 0, new_alias_list_name: 'Regional P25' }]);
  expect(save.body.candidates.every((candidate) => candidate.auto_start === false)).toBe(true);
  expect(state.requests.some((request) => request.path.endsWith('/start'))).toBe(false);
  await dialog(page).getByRole('button', { name: 'Retry adding Regional South' }).click();
  await expect(dialog(page)).toContainText('3 channels added.');
  const saves = state.requests.filter((request) => request.path.endsWith('/save'));
  expect(saves).toHaveLength(2);
  expect(saves[1].body.candidates.map((candidate) => candidate.candidate_id)).toEqual(['regional']);
  expect(state.requests.some((request) => request.path.endsWith('/start'))).toBe(false);
});

test('a duplicate created during review remains non-retryable and points to Channels', async ({ page }) => {
  const state = await install(page, { raceDuplicate: 'north' });
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
  await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
  await expect(dialog(page)).toContainText('1 channel was already in Channels.');
  await expect(dialog(page).getByRole('link', { name: 'Open Channels' })).toHaveAttribute('href', /view=channel-setup/);
  await expect(dialog(page).getByRole('button', { name: /Retry adding/ })).toHaveCount(0);
  expect(state.requests.some((request) => request.path.endsWith('/start'))).toBe(false);
});

test('a committed channel with failed publication requires restart without another add or start', async ({ page }) => {
  const state = await install(page, { publicationFailure: true });
  await complete(page);
  await dialog(page).getByRole('button', { name: 'Select all available' }).click();
  await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
  await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
  await expect(dialog(page)).toContainText('Restart VCE before opening these channels');
  await expect(dialog(page).getByText('Added', { exact: true })).toHaveCount(1);
  await expect(dialog(page).getByText('Not added', { exact: true })).toHaveCount(2);
  await expect(dialog(page).getByRole('link', { name: 'Open channel', exact: true })).toHaveCount(0);
  await expect(dialog(page).getByRole('link', { name: 'Open Channels', exact: true })).toBeVisible();
  await expect(dialog(page).getByRole('button', { name: /Retry|Review choices|Add selected/ })).toHaveCount(0);
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(1);
  expect(state.requests.some((request) => request.path.endsWith('/start'))).toBe(false);
});

test('a definitive saved-restart error locks the flow even if ledger recovery fails', async ({ page }) => {
  const state = await install(page, { publicationFailure: true, failLedgerOnce: true });
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
  await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
  await expect(dialog(page)).toContainText('Restart VCE before opening these channels');
  await expect(dialog(page).getByText('Status unavailable', { exact: true })).toHaveCount(1);
  await expect(dialog(page).getByRole('button', { name: /Retry|Review choices|Add selected/ })).toHaveCount(0);
  expect(state.requests.some((request) => request.path.endsWith('/start'))).toBe(false);
});

test('search bounds and terminal failures explain the required action without opening details', async ({ page }) => {
  const reason = 'These bands need too many receiver windows. Choose fewer bands or a wider receiver';
  const state = await install(page, { invalidRequest: reason });
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(dialog(page).locator('.ui-feedback > p')).toHaveText(reason);
  state.invalidRequest = null;
  state.phase = 'failed';
  state.reason = 'This search reached its time limit. Choose a smaller range.';
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(dialog(page).locator('.ui-notice')).toHaveText(state.reason);
  await expect(dialog(page)).toContainText('Nothing has been added');
});

test('failed-row review edits only unsaved channels', async ({ page }) => {
  const state = await install(page, { failCandidateOnce: 'regional' });
  await complete(page);
  await dialog(page).getByRole('button', { name: 'Select all available' }).click();
  await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
  await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
  await dialog(page).getByRole('button', { name: 'Review choices', exact: true }).click();
  await expect(dialog(page).getByRole('radio')).toHaveCount(0);
  await expect(dialog(page).getByText('Added', { exact: true })).toHaveCount(2);
  await dialog(page).locator('.spectrum-search-review-system').filter({ hasText: 'Regional Services' })
    .getByRole('button', { name: 'Customize' }).click();
  await expect(dialog(page).getByRole('textbox', { name: /Channel name for/ })).toHaveCount(1);
  await dialog(page).getByLabel('Channel name for 860.0125 MHz').fill('My South Control');
  await dialog(page).getByLabel('New Alias List name for ABC00-234').fill('Regional listening');
  await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
  await expect(dialog(page)).toContainText('3 channels added.');
  const saves = state.requests.filter((request) => request.path.endsWith('/save'));
  expect(saves.at(-1).body.candidates).toEqual([{ candidate_id: 'regional', name: 'My South Control', auto_start: false }]);
  expect(state.requests.some((request) => request.path.endsWith('/start'))).toBe(false);
});

test('review has no start controls and links to Channels after saving', async ({ page }) => {
  const state = await install(page);
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
  await expect(dialog(page).getByRole('checkbox', { name: /Listen now|Auto-start channels/ })).toHaveCount(0);
  await expect(dialog(page).getByLabel('Start with')).toHaveCount(0);
  await dialog(page).getByRole('button', { name: 'Back', exact: true }).click();
  await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
  await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
  await expect(dialog(page)).toContainText('1 channel added.');
  await expect(dialog(page).getByRole('link', { name: 'Open Channels' })).toHaveAttribute('href', /view=channel-setup/);
  expect(state.requests.some((request) => request.path.endsWith('/start'))).toBe(false);
});

test('requires a choice for ambiguous alias groups and preserves drafts after stale revision', async ({ page }) => {
  const state = await install(page, { ambiguous: true, staleOnce: true });
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
  await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
  expect(state.requests.some((request) => request.path.endsWith('/save'))).toBe(false);
  await dialog(page).getByLabel('Alias List for BEE00-348').selectOption('22');
  await dialog(page).getByLabel('Channel name for 773.08125 MHz').fill('My North Control');
  await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
  await expect(dialog(page)).toContainText('Listening settings changed');
  await expect(dialog(page).getByLabel('Channel name for 773.08125 MHz')).toHaveValue('My North Control');
  await expect(dialog(page).getByLabel('Alias List for BEE00-348')).toHaveValue('22');
  await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
  await expect(dialog(page)).toContainText('1 channel added.');
  expect(state.requests.filter((request) => request.path.endsWith('/save')).at(-1).body.revision).toBe(8);
});

test('an expired search requires fresh evidence and does not save stale choices', async ({ page }) => {
  const state = await install(page);
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
  state.expired = true;
  await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
  await expect(dialog(page).getByRole('heading', { name: 'This search expired' })).toBeVisible();
  await dialog(page).getByRole('button', { name: 'Start a new search' }).click();
  await expect(dialog(page).getByRole('heading', { name: 'Choose where to look' })).toBeVisible();
});

test('renews the browse lease while a save is pending', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { delaySave: true });
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
  await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
  await expect.poll(() => Boolean(state.releaseSave)).toBe(true);
  await page.clock.fastForward(11000);
  await expect.poll(() => state.requests.some((request) => request.path === '/api/v1/admin/tuners/idle-b/browse' && Boolean(request.body.lease_id))).toBe(true);
  state.releaseSave();
  await expect(dialog(page)).toContainText('1 channel added.');
});

test('a confirmed receiver lease conflict expires the search instead of retrying stale ownership', async ({ page }) => {
  await page.clock.install();
  const state = await install(page);
  await complete(page);
  state.failNextBrowseRenewConflict = true;
  await page.clock.fastForward(11000);
  await expect(dialog(page).getByRole('heading', { name: 'This search expired' })).toBeVisible();
  const renewals = state.requests.filter((request) =>
    request.path === '/api/v1/admin/tuners/idle-b/browse' && request.body.lease_id === 'lease-1').length;
  await page.clock.fastForward(20000);
  expect(state.requests.filter((request) =>
    request.path === '/api/v1/admin/tuners/idle-b/browse' && request.body.lease_id === 'lease-1')).toHaveLength(renewals);
});

test('no idle receiver, failed search and empty results provide an actionable next step', async ({ page }) => {
  const state = await install(page, { noIdle: true });
  await expect(dialog(page)).toContainText('All receivers are in use');
  await expect(dialog(page).locator('.spectrum-search-receiver-card')).toHaveCount(2);
  await expect(dialog(page).getByText('2 channels running', { exact: true })).toHaveCount(2);
  const prepare = dialog(page).getByRole('button', { name: 'Stop channels and use' });
  await expect(prepare).toHaveCount(2);
  await expect(prepare.first()).toHaveClass(/ui-button-primary/);
  await expect(dialog(page).getByRole('link', { name: 'Manage receivers' })).toHaveAttribute('href', /view=tuners/);
  await expect(dialog(page).locator('.spectrum-discovery-actions > .ui-button')).toHaveCount(2);
  await expect(dialog(page).getByRole('button', { name: 'Refresh receivers', exact: true }))
    .toHaveClass(/ui-button-secondary/);
  const blockedGeometry = await dialog(page).evaluate((element) => {
    const list = element.querySelector('.spectrum-search-receiver-list').getBoundingClientRect();
    const footer = element.querySelector('.spectrum-discovery-actions').getBoundingClientRect();
    return { gap: footer.top - list.bottom };
  });
  expect(blockedGeometry.gap).toBeLessThanOrEqual(40);
  await expect(dialog(page)).toHaveScreenshot('spectrum-search-unavailable-light-1280.png');
  await expect(dialog(page).getByRole('button', { name: 'Find signals', exact: true })).toHaveCount(0);
  state.noIdle = false;
  await dialog(page).getByRole('button', { name: 'Refresh receivers' }).click();
  await expect(dialog(page).getByRole('button', { name: 'Find signals', exact: true })).toBeEnabled();
  state.phase = 'failed';
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(dialog(page)).toContainText('Nothing has been added');
  await expect(dialog(page).getByRole('checkbox')).toHaveCount(0);
  await dialog(page).getByRole('button', { name: 'Try another search' }).click();
  state.phase = 'complete';
  state.empty = true;
  await complete(page);
  await expect(dialog(page)).toContainText('No trunked control channels were identified');
  await expect(dialog(page).getByRole('button', { name: 'Review selected' })).toBeDisabled();
});

test('blocked receiver copy and actions match the actual reason', async ({ page }) => {
  await install(page, { lockedOnly: true });
  await expect(dialog(page)).toContainText('A receiver needs to be prepared');
  await expect(dialog(page)).toContainText('VCE will restore Lock center when you finish');
  await expect(dialog(page)).not.toContainText('Calls in progress will end');
  await expect(dialog(page).getByRole('button', { name: 'Unlock and use: Small receiver', exact: true }))
    .toBeVisible();
  await expect(dialog(page).getByRole('button', { name: 'Unlock and use: Wide receiver', exact: true }))
    .toBeVisible();
});

test('an empty receiver catalog explains how to recover', async ({ page }) => {
  await install(page, { noCatalogTuners: true });
  await expect(dialog(page)).toContainText('No receivers are available');
  await expect(dialog(page)).toContainText('Connect or enable a supported receiver, then refresh this list');
  await expect(dialog(page).locator('.spectrum-search-receiver-card')).toHaveCount(0);
});

test('warns before stopping active channels and keeps the takeover lease for the search', async ({ page }) => {
  const state = await install(page, { noIdle: true });
  await dialog(page).getByRole('button', { name: 'Stop channels and use' }).first().click();
  const warning = page.getByRole('alertdialog', { name: 'Stop channels for this search?' });
  await expect(warning).toContainText('2 active channels');
  await expect(warning).toContainText('those calls will not resume');
  await expect(warning).toContainText('restart the same configured channels');
  await expect(warning).toHaveScreenshot('spectrum-search-takeover-warning-light-1280.png');
  await warning.getByRole('button', { name: 'Stop channels and search', exact: true }).click();
  await expect(dialog(page).getByLabel('Receiver', { exact: true })).toHaveValue('idle-a');
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(dialog(page).getByRole('heading', { name: /channels? found$/ })).toBeVisible();
  const browsePosts = state.requests.filter((request) =>
    request.path === '/api/v1/admin/tuners/idle-a/browse' && request.method === 'POST');
  const takeoverIndex = browsePosts.findIndex((request) => request.body.takeover === true);
  expect(takeoverIndex).toBeGreaterThanOrEqual(0);
  expect(browsePosts[takeoverIndex + 1].body).toEqual({ lease_id: 'lease-1' });
});

test('searching another band refreshes receiver availability after restoring a borrowed tuner', async ({ page }) => {
  const state = await install(page, { noIdle: true });
  await dialog(page).getByRole('button', { name: 'Stop channels and use' }).first().click();
  const warning = page.getByRole('alertdialog', { name: 'Stop channels for this search?' });
  await warning.getByRole('button', { name: 'Stop channels and search', exact: true }).click();
  await complete(page);

  await dialog(page).getByRole('button', { name: 'Search another band', exact: true }).click();
  await expect(dialog(page).getByRole('heading', { name: 'Choose a receiver' })).toBeVisible();
  await expect(dialog(page).locator('.spectrum-discovery-context')).toBeEmpty();
  await expect(dialog(page)).toContainText('All receivers are in use');
  await expect(dialog(page).getByRole('button', { name: 'Stop channels and use' })).toHaveCount(2);
  expect(state.requests.filter((request) => request.path === `${searchPath}/catalog`).length).toBeGreaterThan(1);
});

test('saving restores a borrowed tuner as soon as it finishes', async ({ page }) => {
  const state = await install(page, { noIdle: true });
  await dialog(page).getByRole('button', { name: 'Stop channels and use' }).first().click();
  const warning = page.getByRole('alertdialog', { name: 'Stop channels for this search?' });
  await warning.getByRole('button', { name: 'Stop channels and search', exact: true }).click();
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
  await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
  await expect(dialog(page)).toContainText('1 channel added.');

  const saveIndex = state.requests.findIndex((request) => request.path.endsWith('/save'));
  const releaseRequestIndex = () => state.requests.findIndex((request, index) => index > saveIndex &&
    request.path === '/api/v1/admin/tuners/idle-a/browse' && request.method === 'DELETE' &&
    request.body.lease_id === 'lease-1');
  await expect.poll(releaseRequestIndex).toBeGreaterThan(saveIndex);
  const releaseIndex = releaseRequestIndex();
  const resumeRequestIndex = () => state.requests.findIndex((request, index) => index > releaseIndex &&
    request.path === '/api/v1/admin/tuners/idle-a/browse' && request.method === 'POST' &&
    Object.keys(request.body).length === 0);
  expect(releaseIndex).toBeGreaterThan(saveIndex);
  expect(resumeRequestIndex()).toBe(-1);
});

test('a release conflict keeps ownership until retry resumes the borrowed tuner', async ({ page }) => {
  const state = await install(page, { noIdle: true });
  await dialog(page).getByRole('button', { name: 'Stop channels and use' }).first().click();
  await page.getByRole('alertdialog', { name: 'Stop channels for this search?' })
    .getByRole('button', { name: 'Stop channels and search', exact: true }).click();
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
  state.failNextBrowseDeleteConflict = true;
  await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();

  await expect(dialog(page)).toContainText('This receiver could not resume its channels yet');
  const retry = dialog(page).getByRole('button', { name: 'Try resuming channels', exact: true });
  await expect(retry).toBeVisible();
  const failedRelease = state.requests.findIndex((request) => request.path === '/api/v1/admin/tuners/idle-a/browse' &&
    request.method === 'DELETE' && request.body.lease_id === 'lease-1');
  expect(failedRelease).toBeGreaterThanOrEqual(0);
  expect(state.requests.slice(failedRelease + 1).some((request) =>
    request.path === '/api/v1/admin/tuners/idle-a/browse' && request.method === 'POST' &&
    Object.keys(request.body).length === 0)).toBe(false);

  await retry.click();
  await expect(retry).toHaveCount(0);
  const successfulRelease = state.requests.findIndex((request, index) => index > failedRelease &&
    request.path === '/api/v1/admin/tuners/idle-a/browse' && request.method === 'DELETE' &&
    request.body.lease_id === 'lease-1');
  const resume = state.requests.findIndex((request, index) => index > successfulRelease &&
    request.path === '/api/v1/admin/tuners/idle-a/browse' && request.method === 'POST' &&
    Object.keys(request.body).length === 0);
  expect(successfulRelease).toBeGreaterThan(failedRelease);
  expect(resume).toBe(-1);
});

test('failed restart release retains the lease and retries before refreshing receivers', async ({ page }) => {
  const state = await install(page);
  await complete(page);
  state.failNextBrowseDelete = true;
  const catalogsBefore = state.requests.filter((request) => request.path === `${searchPath}/catalog`).length;

  await dialog(page).getByRole('button', { name: 'Search another band', exact: true }).click();
  await expect(dialog(page)).toContainText('This receiver could not be released yet');
  await expect(dialog(page).getByRole('button', { name: 'Try again', exact: true })).toBeVisible();
  expect(state.requests.filter((request) => request.path === `${searchPath}/catalog`)).toHaveLength(catalogsBefore);

  await dialog(page).getByRole('button', { name: 'Try again', exact: true }).click();
  await expect.poll(() => state.requests.filter((request) => request.path === `${searchPath}/catalog`).length)
    .toBe(catalogsBefore + 1);
  await expect(dialog(page).getByRole('button', { name: 'Find signals', exact: true })).toBeEnabled();
  const browseDeletes = state.requests.filter((request) =>
    request.path === '/api/v1/admin/tuners/idle-b/browse' && request.method === 'DELETE' &&
    request.body.lease_id === 'lease-1');
  expect(browseDeletes).toHaveLength(2);
});

test('failed search cleanup retains the job id and retries before releasing the receiver', async ({ page }) => {
  const state = await install(page);
  await complete(page);
  state.failNextJobDelete = true;
  const receiverDeletesBefore = state.requests.filter((request) =>
    request.path === '/api/v1/admin/tuners/idle-b/browse' && request.method === 'DELETE').length;

  await dialog(page).getByRole('button', { name: 'Search another band', exact: true }).click();

  await expect(dialog(page)).toContainText('The previous search could not be stopped yet');
  await expect(dialog(page).getByRole('button', { name: 'Try again', exact: true })).toBeVisible();
  expect(state.requests.filter((request) => request.path === `${searchPath}/search-a` &&
    request.method === 'DELETE')).toHaveLength(1);
  expect(state.requests.filter((request) => request.path === '/api/v1/admin/tuners/idle-b/browse' &&
    request.method === 'DELETE')).toHaveLength(receiverDeletesBefore);

  await dialog(page).getByRole('button', { name: 'Try again', exact: true }).click();

  await expect(dialog(page).getByRole('button', { name: 'Find signals', exact: true })).toBeEnabled();
  expect(state.requests.filter((request) => request.path === `${searchPath}/search-a` &&
    request.method === 'DELETE')).toHaveLength(2);
  expect(state.requests.filter((request) => request.path === '/api/v1/admin/tuners/idle-b/browse' &&
    request.method === 'DELETE').length).toBe(receiverDeletesBefore + 1);
});

test('closing after a transient job release failure retains its receiver until retry succeeds', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { phase: 'scanning' });
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(dialog(page).getByRole('heading', { name: 'Find signals', exact: true })).toBeVisible();
  state.failNextJobDelete = true;
  const checkpoint = state.requests.length;

  await dialog(page).getByRole('button', { name: 'Stop search', exact: true }).click();
  await expect(dialog(page)).toHaveCount(0);
  const cleanupRequests = () => state.requests.slice(checkpoint);
  const jobDeletes = () => cleanupRequests().filter((request) =>
    request.path === `${searchPath}/search-a` && request.method === 'DELETE');
  const leaseDeletes = () => cleanupRequests().filter((request) =>
    request.path.endsWith('/browse') && request.method === 'DELETE');
  const resumedBrowses = () => cleanupRequests().filter((request) =>
    request.path.endsWith('/browse') && request.method === 'POST');

  await expect.poll(() => jobDeletes().length).toBe(1);
  expect(leaseDeletes()).toHaveLength(0);
  expect(resumedBrowses()).toHaveLength(0);

  await page.clock.fastForward(1100);
  await expect.poll(() => jobDeletes().length).toBe(2);
  await expect.poll(() => leaseDeletes().length).toBe(1);
  expect(resumedBrowses()).toHaveLength(0);
  const secondJobDelete = state.requests.lastIndexOf(jobDeletes()[1]);
  const leaseDelete = state.requests.lastIndexOf(leaseDeletes()[0]);
  expect(leaseDelete).toBeGreaterThan(secondJobDelete);
});

test('recording tuners check only their fixed WAV window with directory state preserved', async ({ page }) => {
  const state = await install(page, { recording: true, directoryConfiguration: {
    account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } });
  await dialog(page).getByText('RadioReference names (optional)', { exact: true }).click();
  await expect(dialog(page).getByLabel('RadioReference state or province')).toHaveValue('39');
  await expect(bandsTrigger(page)).toBeHidden();
  await expect(dialog(page)).toContainText('WAV recording');
  await expect(dialog(page).getByLabel('Receiver', { exact: true }).locator('option'))
    .toHaveText(['Trunked capture · 451.000000 MHz @ 2.00 MHz']);
  await expect(dialog(page).locator('.spectrum-discovery-context')).toContainText('451.000000 MHz @ 2.00 MHz');
  await expect(dialog(page).locator('.spectrum-discovery-context')).not.toContainText('773.081250 MHz');
  await expect(dialog(page)).toContainText('450 to 452 MHz');
  await dialog(page).getByLabel('RadioReference state or province').selectOption('42');
  await complete(page);
  const create = state.requests.find((request) => request.path === searchPath && request.method === 'POST');
  expect(create.body).toEqual({ tuner_id: 'capture-a', browse_lease_id: 'lease-1',
    radioreference_state_id: 42, ranges: [{ minimum_hz: 450000000, maximum_hz: 452000000 }] });
});

test('directory network failure leaves local signal discovery available', async ({ page }) => {
  const state = await install(page, { directoryOffline: true });
  await dialog(page).getByText('RadioReference names (optional)', { exact: true }).click();
  await expect(dialog(page)).toContainText('Directory names are unavailable. Signal discovery can continue.');
  await complete(page);
  expect(state.requests.find((request) => request.path === searchPath && request.method === 'POST')
    .body.radioreference_state_id).toBe(null);
  await expect(dialog(page)).toContainText('County Public Safety');
});

test('mixed DMR and NXDN sites retain protocol identities and only verified RR names', async ({ page }) => {
  await install(page, { customCandidates: [
    { candidate_id: 'dmr', frequency_hz: 451000000, protocol_id: 'dmr', variant: 'Tier III',
      system_name: 'County Transit', site_name: 'North', selectable: true,
      trunked_evidence: { identity: { radio_system_key: 'dmr:tier3:small:12', network: 12, site: 3,
        model: 'small', color_code: 4 } }, radio_reference: { state: 'matched', match: {
        system_name: 'County Transit', site_name: 'North', url: 'https://www.radioreference.com/db/sid/123',
        channels: [{ frequency_hz: 451000000, logical_channel_number: 1, primary_control: true }] } } },
    { candidate_id: 'nxdn', frequency_hz: 452000000, protocol_id: 'nxdn', variant: 'Type-C', selectable: true,
      trunked_evidence: { identity: { radio_system_key: 'nxdn-c:local:12', system: 12, site: 3, ran: 9 } },
      radio_reference: { state: 'ambiguous', match: { system_name: 'Wrong system' } } }
  ] });
  await complete(page);
  await expect(dialog(page).locator('.spectrum-search-system-group')).toHaveCount(2);
  await expect(dialog(page)).toContainText('DMR Tier III');
  await expect(dialog(page)).toContainText('NXDN Type-C');
  await expect(dialog(page).getByText('Wrong system', { exact: true })).toHaveCount(0);
  await dialog(page).getByText('RadioReference', { exact: true }).first().click();
  await expect(dialog(page).getByRole('link', { name: 'County Transit', exact: true })).toHaveAttribute('href',
    'https://www.radioreference.com/db/sid/123');
  await expect(dialog(page)).toContainText('451 MHz · Primary control');
  await dialog(page).getByText('RadioReference', { exact: true }).nth(1).click();
  await expect(dialog(page)).toContainText('No directory names were applied');
});

test('navigation dismisses a running search and releases its job before its independent receiver', async ({ page }) => {
  const state = await install(page, { phase: 'scanning' });
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(dialog(page).getByRole('heading', { name: 'Find signals', exact: true })).toBeVisible();
  await page.locator('a[data-view="tuners"]').evaluate((link) => link.click());
  await expect(dialog(page)).toHaveCount(0);
  await expect.poll(() => state.requests.some((request) => request.path.endsWith('/browse') &&
    request.method === 'DELETE')).toBe(true);
  const jobDelete = state.requests.findIndex((request) => request.path === `${searchPath}/search-a` && request.method === 'DELETE');
  const leaseDelete = state.requests.findIndex((request) => request.path.endsWith('/browse') && request.method === 'DELETE');
  expect(jobDelete).toBeGreaterThan(-1);
  expect(leaseDelete).toBeGreaterThan(jobDelete);
});

test('forced dismissal during search creation releases the returned job before its receiver', async ({ page }) => {
  const state = await install(page, { phase: 'scanning', delayCreate: true });
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect.poll(() => typeof state.releaseCreate).toBe('function');
  await page.evaluate(async () => {
    const api = await import(document.querySelector('script[type="module"][src*="/assets/app.js"]').src);
    api.closeReadOnlyModal(true);
  });
  await expect(dialog(page)).toHaveCount(0);
  expect(state.requests.some((request) => request.path.endsWith('/browse') && request.method === 'DELETE')).toBe(false);
  state.releaseCreate();
  await expect.poll(() => state.requests.some((request) => request.path.endsWith('/browse') &&
    request.method === 'DELETE')).toBe(true);
  const jobDelete = state.requests.findIndex((request) => request.path === `${searchPath}/search-a` && request.method === 'DELETE');
  const leaseDelete = state.requests.findIndex((request) => request.path.endsWith('/browse') && request.method === 'DELETE');
  expect(jobDelete).toBeGreaterThan(-1);
  expect(leaseDelete).toBeGreaterThan(jobDelete);
});

test('delayed wide-directory names update review without replacing edited channel names', async ({ page }) => {
  const state = await install(page, { customCandidates: [{ candidate_id: 'digital', frequency_hz: 451000000,
    protocol_id: 'dmr', name: 'Decoded control', system_name: 'Decoded network', alias_group_id: 'digital',
    radio_reference: { state: 'pending' }, trunked_evidence: { identity: {
      radio_system_key: 'dmr:tier3:small:12', network: 12, site: 3 }, frequency_map: [] } }],
    customAliasGroups: [{ group_id: 'digital', protocol_id: 'dmr', alias_lists: [], default_new_alias_list_name: 'Decoded' }] });
  await complete(page);
  await dialog(page).getByRole('button', { name: 'Select all available' }).click();
  await dialog(page).getByRole('button', { name: 'Review 1 channel', exact: true }).click();
  await dialog(page).getByRole('button', { name: 'Customize', exact: true }).click();
  await dialog(page).getByLabel('Channel name for 451 MHz', { exact: true }).fill('My control');
  state.customCandidates[0] = { ...state.customCandidates[0], name: 'Directory North', system_name: 'Transit authority',
    site_name: 'North', radio_reference: { state: 'matched', match: { system_name: 'Transit authority', site_name: 'North',
      channels: [{ logical_channel_number: 3, frequency_hz: 451500000 }] } }, trunked_evidence: {
      ...state.customCandidates[0].trunked_evidence, frequency_map: [{ number: 3, downlink_hz: 451500000 }] } };
  await expect(dialog(page).getByRole('heading', { name: 'Transit authority', exact: true })).toBeVisible();
  await expect(dialog(page).getByLabel('Channel name for 451 MHz', { exact: true })).toHaveValue('My control');
  await expect(dialog(page).locator('.channel-map-row input').nth(0)).toHaveValue('3');
  await dialog(page).getByText('RadioReference', { exact: true }).click();
  await expect(dialog(page)).toContainText('451.5 MHz');
});

test('wide DMR review submits the explicit channel map through the shared editor', async ({ page }) => {
  const state = await install(page, { customCandidates: [{ candidate_id: 'digital', frequency_hz: 451000000,
    protocol_id: 'dmr', variant: 'Tier III', system_name: 'Transit', name: 'North', alias_group_id: 'digital',
    trunked_evidence: { identity: { radio_system_key: 'dmr:tier3:small:12', network: 12, site: 3 },
      frequency_map: [{ number: 1, downlink_hz: 451000000, uplink_hz: 0 }] } }],
    customAliasGroups: [{ group_id: 'digital', protocol_id: 'dmr', system_name: 'Transit',
      alias_lists: [], default_new_alias_list_name: 'Transit' }] });
  await complete(page);
  await dialog(page).getByRole('button', { name: 'Select all available' }).click();
  await dialog(page).getByRole('button', { name: 'Review 1 channel', exact: true }).click();
  await dialog(page).getByRole('button', { name: 'Customize', exact: true }).click();
  await expect(dialog(page)).toContainText('Unmapped channels can be followed only when the system broadcasts their frequencies.');
  await expect(dialog(page).locator('.channel-map-row')).toHaveCount(1);
  const mapFits = await dialog(page).locator('.spectrum-search-review-customize').evaluate((element) =>
    element.scrollWidth <= element.clientWidth + 1);
  expect(mapFits).toBe(true);
  const boundedReview = await dialog(page).evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    const fields = [...element.querySelectorAll('.spectrum-search-channel-fields .ui-field-label, .channel-map-row')];
    return { modalFits: element.scrollWidth <= element.clientWidth + 1,
      fieldsInside: fields.every((field) => {
        const rect = field.getBoundingClientRect();
        return rect.left >= bounds.left && rect.right <= bounds.right;
      }) };
  });
  expect(boundedReview).toEqual({ modalFits: true, fieldsInside: true });
  await dialog(page).getByText('Unmapped channels can be followed only when the system broadcasts their frequencies.',
    { exact: false }).scrollIntoViewIfNeeded();
  await expect(dialog(page)).toHaveScreenshot('spectrum-search-dmr-map-light-1280.png');
  await dialog(page).getByRole('button', { name: 'Add mapping', exact: true }).click();
  const added = dialog(page).locator('.channel-map-row').nth(1);
  await added.locator('input').nth(0).fill('2');
  await added.locator('input').nth(1).fill('451.25');
  await dialog(page).getByRole('button', { name: 'Add 1 channel', exact: true }).click();
  const saved = state.requests.find((request) => request.path.endsWith('/save'));
  expect(saved.body.candidates[0].frequency_map).toEqual([
    { number: 1, downlink_hz: 451000000, uplink_hz: 0 }, { number: 2, downlink_hz: 451250000, uplink_hz: 0 } ]);
});

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 390], ['dark', 390]]) {
  test(`search gallery and failed state use shared surfaces in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`/design-system.html?theme=${theme}&view=spectrum-search`);
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-gallery-${theme}-${width}.png`);
    await install(page, { theme, phase: 'failed' });
    await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
    await expect(dialog(page).getByRole('heading', { name: 'Search stopped' })).toBeVisible();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-failed-${theme}-${width}.png`);
  });
  test(`search stages remain readable with visible actions in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const state = await install(page, { theme, phase: 'scanning', truncated: true });
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-band-${theme}-${width}.png`);
    const receiver = dialog(page).getByLabel('Receiver', { exact: true });
    await expect(receiver.locator('option')).toHaveText(['Small receiver · 773.081250 MHz @ 2.20 MHz',
      'Wide receiver · 773.081250 MHz @ 9.00 MHz']);
    await receiver.click();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-receiver-control-${theme}-${width}.png`);
    await receiver.press('Enter');
    await openBands(page);
    await expect(dialog(page).getByRole('group', { name: 'Bands', exact: true }).getByRole('checkbox')).toHaveCount(5);
    for (const label of bandLabels) await expect(bandCheckbox(page, label)).toBeVisible();
    await bandCheckbox(page, bandLabels[2]).focus();
    await expect(bandCheckbox(page, bandLabels[2])).toBeFocused();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-band-selection-${theme}-${width}.png`);
    await closeBands(page);
    await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
    await expect(dialog(page).getByRole('heading', { name: 'Find signals', exact: true })).toBeVisible();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-scanning-${theme}-${width}.png`);
    state.phase = 'checking';
    await expect(dialog(page).getByRole('heading', { name: 'Check signals', exact: true })).toBeVisible();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-checking-${theme}-${width}.png`);
    state.phase = 'complete';
    await expect(dialog(page).getByRole('heading', { name: /channels? found$/ })).toBeVisible();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-results-${theme}-${width}.png`);
    const details = dialog(page).locator('tbody tr').first().getByText('Signal details', { exact: true });
    await details.click();
    await details.scrollIntoViewIfNeeded();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-signal-details-${theme}-${width}.png`);
    await details.click();
    await dialog(page).getByRole('button', { name: 'Select all available' }).click();
    await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
    await expect(dialog(page).getByRole('checkbox', { name: /Listen now|Auto-start channels/ })).toHaveCount(0);
    await expect(dialog(page).getByLabel('Start with')).toHaveCount(0);
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-review-${theme}-${width}.png`);
    const geometry = await dialog(page).evaluate((element) => ({ width: document.documentElement.scrollWidth,
      bottom: element.querySelector('.spectrum-discovery-actions').getBoundingClientRect().bottom,
      scroll: element.querySelector('.spectrum-discovery-stage').scrollHeight,
      client: element.querySelector('.spectrum-discovery-stage').clientHeight }));
    expect(geometry.width).toBeLessThanOrEqual(width);
    expect(geometry.bottom).toBeLessThanOrEqual(900);
    expect(geometry.scroll).toBeGreaterThan(0);
    await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).focus();
    await expect(dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ })).toBeFocused();
    await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
    await expect(dialog(page).getByRole('heading', { name: 'Your channel results' })).toBeVisible();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-saved-${theme}-${width}.png`);
  });
  test(`alias choices and restart notice remain readable in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const state = await install(page, { theme, ambiguous: true, publicationFailure: true });
    await complete(page);
    await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
    await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
    const alias = dialog(page).getByLabel('Alias List for BEE00-348');
    await expect(alias.locator('option')).toHaveText(['Choose an Alias List', 'County Dispatch', 'County Operations']);
    await alias.click();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-alias-control-${theme}-${width}.png`);
    await alias.press('Enter');
    await alias.selectOption('22');
    await expect(alias).toHaveValue('22');
    await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
    await expect(dialog(page)).toContainText('Restart VCE before opening these channels');
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-restart-${theme}-${width}.png`);
    expect(state.requests.some((request) => request.path.endsWith('/start'))).toBe(false);
  });
}
