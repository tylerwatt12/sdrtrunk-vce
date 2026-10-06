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

const ohioSystems = {
  marcs: 'Ohio MARCS-IP: Multi-Agency Radio Communications',
  cleveland: 'Greater Cleveland Radio Communications Network',
  parma: 'Parma / Parma Heights / Brooklyn / Brooklyn Heights'
};

function ohioResultsFixture({ pendingMarcs = false } = {}) {
  // Real system names with synthetic signal observations; no receiver is contacted.
  const systems = [
    ['marcs', 0x348, 'Cuyahoga County', 'MARCS Cuyahoga control', 773081250, 1, 27],
    ['marcs', 0x348, 'Lake County', 'MARCS Lake control', 773831250, 2, 1],
    ['cleveland', 0x14c, 'Cleveland Simulcast', 'Cleveland control', 851012500, 1, 3],
    ['parma', 0x2dd, 'Parma Simulcast', 'Parma control', 856012500, 1, 4]
  ];
  return {
    customCandidates: systems.map(([group, system, site, name, frequency, rfss, siteId], index) => ({
      candidate_id: `${group}-${siteId}`, frequency_hz: frequency, protocol_id: 'p25-phase1', variant: 'P25_PHASE_1',
      name, system_name: pendingMarcs && group === 'marcs' ? 'P25 BEE00-348' : ohioSystems[group],
      site_name: site, identity: { wacn: 0xbee00, system, rfss, site: siteId },
      modulation: index === 0 ? 'C4FM' : 'CQPSK', alias_group_id: group,
      selectable: true, saved: false, running: false, strength_dbfs: -35.2 - index * 7,
      health: { quality_pct: 99 - index, valid_messages: 54, valid_control_messages: 41,
        invalid_control_messages: 1, checked_at_ms: 1790878800000 },
      radio_reference: pendingMarcs && group === 'marcs' ? { state: 'pending' } : {
        state: 'matched', match: { system_name: ohioSystems[group], site_name: site,
          url: `https://www.radioreference.com/db/sid/${123 + Object.keys(ohioSystems).indexOf(group)}`,
          channels: [{ frequency_hz: frequency, primary_control: true }] }
      }
    })),
    customAliasGroups: Object.keys(ohioSystems).map((group, index) => ({
      group_id: group, protocol_id: 'p25-phase1', wacn: 0xbee00, system: systems.find((item) => item[0] === group)[1],
      alias_lists: index === 0 ? [{ id: 21, name: 'MARCS Dispatch' }] : [],
      suggested_alias_list_id: null, default_new_alias_list_name: {
        marcs: 'MARCS-IP', cleveland: 'GCRCN', parma: 'Parma Public Safety'
      }[group]
    }))
  };
}

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
    progress: { completed: 2, total: 4, current_frequency_hz: 773000000, checked: 1, total_signals: 4,
      ...(state.progress || {}) }, candidates: state.empty ? [] : state.customCandidates || candidates,
    alias_groups: state.customAliasGroups || [{ group_id: 'county', wacn: 0xbee00, system: 0x348,
      alias_lists: state.ambiguous ? [{ id: 21, name: 'County Dispatch' }, { id: 22, name: 'County Operations' }] :
        [{ id: 21, name: 'County Dispatch' }], suggested_alias_list_id: null, default_new_alias_list_name: 'County P25' },
    { group_id: 'regional', wacn: 0xabc00, system: 0x234, alias_lists: [], suggested_alias_list_id: null,
      default_new_alias_list_name: 'Regional P25' }] };
}

async function install(page, state = {}) {
  state.requests = [];
  state.ledger = {};
  state.identificationActive = false;
  state.tuners = state.recording ? [{ ...tuner('capture-a', 2000000), name: 'Trunked capture',
    tuner_class: 'RECORDING', source_type: 'recording', fixed_window: true, center_frequency_hz: 451000000, frequency_hz: undefined,
    configured_frequency_hz: 773081250,
    minimum_frequency_hz: 450000000, maximum_frequency_hz: 452000000 }] :
    [tuner('idle-a', 2200000), tuner('idle-b', 9000000)];
  if (state.centerFrequencies) state.tuners.forEach((receiver, index) => {
    receiver.center_frequency_hz = state.centerFrequencies[index];
  });
  if (state.tuningLimits) state.tuners.forEach((receiver, index) => {
    const limits = state.tuningLimits[index];
    if (limits) Object.assign(receiver, { minimum_frequency_hz: limits[0], maximum_frequency_hz: limits[1] });
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
    if (path === '/api/v1/admin/radioreference') {
      if (state.delayDirectoryConfiguration) await new Promise((resolve) => { state.releaseDirectory = resolve; });
      return state.directoryOffline ? fail('Directory unavailable', 'unavailable', 503) :
        respond(state.directoryConfiguration || {});
    }
    if (path === '/api/v1/admin/radioreference/countries') {
      if (state.failDirectoryCountriesCount > 0) {
        state.failDirectoryCountriesCount -= 1;
        return fail('Countries temporarily unavailable');
      }
      return respond({ items: [
        { id: 1, name: 'United States', abbreviation: 'US' }, { id: 2, name: 'Canada', abbreviation: 'CA' }
      ] });
    }
    if (path === '/api/v1/admin/radioreference/states') {
      if (state.delayDirectoryStates) {
        state.delayDirectoryStates = false;
        await new Promise((release) => { state.releaseDirectoryStates = release; });
      }
      if (state.failDirectoryStatesCount > 0) {
        state.failDirectoryStatesCount -= 1;
        return fail('States temporarily unavailable');
      }
      return respond({ items: [{ id: 39, name: 'Ohio' }, { id: 42, name: 'Pennsylvania' }] });
    }
    if (path === '/api/v1/admin/channels') return respond({ revision: 1, channels: [] });
    if (path === '/api/v1/admin/channels/protocols') return respond(protocols);
    if (path === '/api/v1/admin/channels/options') return respond({ alias_lists: [] });
    if (path === '/api/v1/admin/tuners') {
      const inventory = { tuners: state.tuners.map((receiver) => ({ ...receiver,
        frequency_hz: receiver.center_frequency_hz, ...(state.inventoryMeasurements?.[receiver.id] || {}) })) };
      if (state.delayNextInventory) {
        state.delayNextInventory = false;
        await new Promise((resolve) => { state.releaseInventory = resolve; });
      }
      if (state.failNextInventory) {
        state.failNextInventory = false;
        return fail('Receiver measurements are unavailable', 'tuner_read_failed', 503);
      }
      return respond(inventory);
    }
    if (path.endsWith('/browse')) {
      if (request.method() === 'DELETE') {
        if (state.requireJobRelease && state.identificationActive)
          return fail('Stop signal identification before releasing this tuner', 'tuner_browse_unavailable', 409);
        if (state.delayNextBrowseDelete) {
          state.delayNextBrowseDelete = false;
          await new Promise((resolve) => { state.releaseBrowseDelete = resolve; });
        }
        if (state.failBrowseDeleteCount > 0) {
          state.failBrowseDeleteCount -= 1;
          return fail('Receiver restoration still needs to finish', 'tuner_browse_unavailable', 409);
        }
        if (state.failBrowseDeleteAfterResume) {
          state.failBrowseDeleteAfterResume = false;
          state.receiverResumed = true;
          return fail('Resume reply was interrupted', 'tuner_browse_failed', 503);
        }
        if (state.receiverResumed) return fail('Receiver lease already ended', 'tuner_browse_expired', 410);
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
      const leasedReceiver = { ...receiver, frequency_hz: receiver.center_frequency_hz,
        ...(takeover ? { operator_state: 'setup', channel_count: 0 } : {}),
        ...(state.browseMeasurements?.[id] || {}) };
      if (!body.lease_id && state.delayNextBrowseAcquire) {
        state.delayNextBrowseAcquire = false;
        await new Promise((resolve) => { state.releaseBrowseAcquire = resolve; });
      }
      if (body.lease_id && state.delayNextBrowseRenew) {
        state.delayNextBrowseRenew = false;
        await new Promise((resolve) => { state.releaseBrowseRenew = resolve; });
      }
      if (body.lease_id && state.failBrowseRenewAfterDelay)
        return fail('Receiver lease already ended', 'tuner_browse_expired', 410);
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
      state.identificationActive = true;
      return respond(snapshot(state));
    }
    if (path === `${searchPath}/search-a`) {
      if (request.method() === 'DELETE') {
        if (state.failNextJobDelete) {
          state.failNextJobDelete = false;
          return fail('Search release failed', 'spectrum_search_failed', 503);
        }
        if (state.delayNextJobDelete) {
          state.delayNextJobDelete = false;
          await new Promise((resolve) => { state.releaseJobDelete = resolve; });
        }
        state.identificationActive = false;
        return respond(null, 204);
      }
      const result = snapshot(state);
      if (state.delayNextJobStatus) {
        state.delayNextJobStatus = false;
        await new Promise((resolve) => { state.releaseJobStatus = resolve; });
      }
      if (state.failJobStatusAfterDelay) return fail('Search was already released', 'search_expired', 410);
      if (state.expired) return fail('Search expired', 'search_expired', 410);
      if (state.restartRequired && state.failLedgerOnce) { state.failLedgerOnce = false; return fail('Connection interrupted'); }
      if (state.failPollOnce) { state.failPollOnce = false; return fail('Connection interrupted'); }
      return respond(result);
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

async function borrowedReview(page, options = {}) {
  const state = await install(page, { noIdle: true, requireJobRelease: true, ...options });
  await dialog(page).getByRole('button', { name: 'Stop channels and use: Small receiver', exact: true }).click();
  await page.getByRole('alertdialog', { name: 'Stop channels for this search?' })
    .getByRole('button', { name: 'Stop channels and search', exact: true }).click();
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  if (options.twoSelected) await dialog(page).getByRole('checkbox', { name: 'Select County Central', exact: true }).check();
  await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
  return state;
}

const savedSearchRequests = (state) => ({
  saves: state.requests.filter((request) => request.path.endsWith('/save')),
  jobDeletes: state.requests.filter((request) => request.path === `${searchPath}/search-a` && request.method === 'DELETE'),
  browseDeletes: state.requests.filter((request) => request.path.endsWith('/browse') && request.method === 'DELETE')
});

test('saved search closes identification before resuming and retains its committed results', async ({ page }) => {
  await page.clock.install();
  const state = await borrowedReview(page);
  await dialog(page).getByRole('button', { name: 'Add 1 channel', exact: true }).click();
  await expect(dialog(page)).toContainText('1 channel added.');
  await expect.poll(() => savedSearchRequests(state).browseDeletes.length).toBe(1);
  const { saves, jobDeletes, browseDeletes } = savedSearchRequests(state);
  expect(saves).toHaveLength(1);
  expect(jobDeletes).toHaveLength(1);
  expect(state.requests.indexOf(jobDeletes[0])).toBeGreaterThan(state.requests.indexOf(saves[0]));
  expect(state.requests.indexOf(browseDeletes[0])).toBeGreaterThan(state.requests.indexOf(jobDeletes[0]));
  await expect(dialog(page)).not.toContainText('could not resume');
  await expect(dialog(page)).not.toContainText('Stop signal identification');
  await expect(dialog(page).getByRole('button', { name: 'Done', exact: true })).toBeEnabled();
  const readsAfterCleanup = state.requests.length;
  await page.clock.fastForward(22000);
  expect(state.requests.length).toBe(readsAfterCleanup);
  await expect(dialog(page)).toContainText('1 channel added.');
  await dialog(page).getByRole('button', { name: 'Done', exact: true }).click();
  await expect(dialog(page)).toHaveCount(0);
  expect(savedSearchRequests(state).saves).toHaveLength(1);
  expect(savedSearchRequests(state).jobDeletes).toHaveLength(1);
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(1);
});

test('saved search cleanup failure retries identification before any receiver release', async ({ page }) => {
  const state = await borrowedReview(page);
  state.failNextJobDelete = true;
  await dialog(page).getByRole('button', { name: 'Add 1 channel', exact: true }).click();
  await expect(dialog(page)).toContainText('This receiver could not resume its channels yet');
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(0);
  await dialog(page).getByRole('button', { name: 'Try resuming channels', exact: true }).click();
  await expect(dialog(page).getByRole('button', { name: 'Try resuming channels', exact: true })).toHaveCount(0);
  await expect(dialog(page)).not.toContainText('could not resume');
  await expect(dialog(page)).toContainText('1 channel added.');
  expect(savedSearchRequests(state).saves).toHaveLength(1);
  expect(savedSearchRequests(state).jobDeletes).toHaveLength(2);
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(1);
});

test('saved search repeated resume retries do not create or release identification again', async ({ page }) => {
  const state = await borrowedReview(page, { failBrowseDeleteCount: 2 });
  await dialog(page).getByRole('button', { name: 'Add 1 channel', exact: true }).click();
  const retry = dialog(page).getByRole('button', { name: 'Try resuming channels', exact: true });
  await expect(retry).toBeEnabled();
  await retry.evaluate((element) => { element.click(); element.click(); });
  await expect.poll(() => savedSearchRequests(state).browseDeletes.length).toBe(2);
  await expect(retry).toBeEnabled();
  await retry.click();
  await expect(retry).toHaveCount(0);
  await expect(dialog(page)).not.toContainText('could not resume');
  expect(savedSearchRequests(state).saves).toHaveLength(1);
  expect(savedSearchRequests(state).jobDeletes).toHaveLength(1);
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(3);
});

test('partially saved search keeps identification until remaining selected rows are committed', async ({ page }) => {
  const state = await borrowedReview(page, { twoSelected: true, failCandidateOnce: 'central' });
  await dialog(page).getByRole('button', { name: 'Add 2 channels', exact: true }).click();
  const retry = dialog(page).getByRole('button', { name: 'Retry adding County Central', exact: true });
  await expect(retry).toBeEnabled();
  expect(savedSearchRequests(state).jobDeletes).toHaveLength(0);
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(0);
  await retry.click();
  await expect(dialog(page)).toContainText('2 channels added.');
  await expect.poll(() => savedSearchRequests(state).browseDeletes.length).toBe(1);
  const { saves, jobDeletes } = savedSearchRequests(state);
  expect(saves).toHaveLength(2);
  expect(saves[0].body.candidates.map((candidate) => candidate.candidate_id)).toEqual(['north', 'central']);
  expect(saves[1].body.candidates.map((candidate) => candidate.candidate_id)).toEqual(['central']);
  expect(jobDeletes).toHaveLength(1);
});

test('saved search handles an already resumed receiver reply without adding the channel again', async ({ page }) => {
  const state = await borrowedReview(page, { failBrowseDeleteAfterResume: true });
  await dialog(page).getByRole('button', { name: 'Add 1 channel', exact: true }).click();
  await expect(dialog(page)).toContainText('This receiver could not resume its channels yet');
  expect(state.receiverResumed).toBe(true);
  await dialog(page).getByRole('button', { name: 'Try resuming channels', exact: true }).click();
  await expect(dialog(page)).not.toContainText('could not resume');
  await expect(dialog(page)).toContainText('1 channel added.');
  expect(savedSearchRequests(state).saves).toHaveLength(1);
  expect(savedSearchRequests(state).jobDeletes).toHaveLength(1);
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(2);
});

test('saved search ignores a late receiver renewal error after successful resume', async ({ page }) => {
  await page.clock.install();
  const state = await borrowedReview(page);
  state.delayNextBrowseRenew = true;
  state.failBrowseRenewAfterDelay = true;
  await page.clock.fastForward(10100);
  await expect.poll(() => Boolean(state.releaseBrowseRenew)).toBe(true);
  await dialog(page).getByRole('button', { name: 'Add 1 channel', exact: true }).click();
  await expect.poll(() => savedSearchRequests(state).browseDeletes.length).toBe(1);
  state.releaseBrowseRenew();
  await page.clock.fastForward(1000);
  await expect(dialog(page)).toContainText('1 channel added.');
  await expect(dialog(page)).not.toContainText('expired');
  await expect(dialog(page)).not.toContainText('could not resume');
  expect(savedSearchRequests(state).saves).toHaveLength(1);
});

for (const failure of [false, true]) {
  test(`saved search ignores a late status ${failure ? 'error' : 'snapshot'} after releasing identification`, async ({ page }) => {
    await page.clock.install();
    const state = await borrowedReview(page);
    state.delayNextJobStatus = true;
    state.failJobStatusAfterDelay = failure;
    await page.clock.fastForward(10100);
    await expect.poll(() => Boolean(state.releaseJobStatus)).toBe(true);
    await dialog(page).getByRole('button', { name: 'Add 1 channel', exact: true }).click();
    await expect.poll(() => savedSearchRequests(state).browseDeletes.length).toBe(1);
    state.releaseJobStatus();
    await page.clock.fastForward(1000);
    await expect(dialog(page)).toContainText('1 channel added.');
    await expect(dialog(page)).not.toContainText('expired');
    await expect(dialog(page)).not.toContainText('could not resume');
    expect(savedSearchRequests(state).saves).toHaveLength(1);
  });
}

test('saved search forced dismissal during a pending save waits for commit and identification cleanup before resume', async ({ page }) => {
  const state = await borrowedReview(page, { delaySave: true, delayNextJobDelete: true });
  await dialog(page).getByRole('button', { name: 'Add 1 channel', exact: true }).click();
  await expect.poll(() => Boolean(state.releaseSave)).toBe(true);
  await expect(dialog(page).getByRole('button', { name: 'Close Find Trunked Systems', exact: true })).toBeDisabled();
  await page.evaluate(async () => {
    const api = await import(document.querySelector('script[type="module"][src*="/assets/app.js"]').src);
    api.closeReadOnlyModal(true);
  });
  await expect(dialog(page)).toHaveCount(0);
  expect(savedSearchRequests(state).jobDeletes).toHaveLength(0);
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(0);
  state.releaseSave();
  await expect.poll(() => Boolean(state.releaseJobDelete)).toBe(true);
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(0);
  state.releaseJobDelete();
  await expect.poll(() => savedSearchRequests(state).browseDeletes.length).toBe(1);
  expect(savedSearchRequests(state).saves).toHaveLength(1);
  expect(state.ledger.north.saved).toBe(true);
  expect(savedSearchRequests(state).jobDeletes).toHaveLength(1);
});

test('saved search defers navigation while identification cleanup is pending and releases each resource once', async ({ page }) => {
  const state = await borrowedReview(page, { delayNextJobDelete: true });
  await dialog(page).getByRole('button', { name: 'Add 1 channel', exact: true }).click();
  await expect(dialog(page)).toContainText('1 channel added.');
  await expect.poll(() => Boolean(state.releaseJobDelete)).toBe(true);
  await expect(dialog(page).getByRole('button', { name: 'Done', exact: true })).toBeDisabled();
  await dialog(page).getByRole('link', { name: 'Open Channels', exact: true }).click();
  await expect(dialog(page)).toHaveCount(1);
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(0);
  state.releaseJobDelete();
  await expect.poll(() => savedSearchRequests(state).browseDeletes.length).toBe(1);
  await expect(dialog(page).getByRole('button', { name: 'Done', exact: true })).toBeEnabled();
  await dialog(page).getByRole('link', { name: 'Open Channels', exact: true }).click();
  await expect(dialog(page)).toHaveCount(0);
  expect(savedSearchRequests(state).saves).toHaveLength(1);
  expect(savedSearchRequests(state).jobDeletes).toHaveLength(1);
});

test('pagehide retries failed search cleanup before releasing its borrowed receiver', async ({ page }) => {
  await page.clock.install();
  const state = await borrowedReview(page);
  state.failNextJobDelete = true;
  await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide')));
  await expect.poll(() => savedSearchRequests(state).jobDeletes.length).toBe(1);
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(0);
  await page.clock.fastForward(1100);
  await expect.poll(() => savedSearchRequests(state).jobDeletes.length).toBe(2);
  await expect.poll(() => savedSearchRequests(state).browseDeletes.length).toBe(1);
  expect(savedSearchRequests(state).saves).toHaveLength(0);
  const { jobDeletes, browseDeletes } = savedSearchRequests(state);
  expect(state.requests.indexOf(browseDeletes[0])).toBeGreaterThan(state.requests.indexOf(jobDeletes[1]));
});

test('pagehide waits for a pending channel commit before ordered identification and receiver release', async ({ page }) => {
  const state = await borrowedReview(page, { delaySave: true });
  await dialog(page).getByRole('button', { name: 'Add 1 channel', exact: true }).click();
  await expect.poll(() => Boolean(state.releaseSave)).toBe(true);
  await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide')));
  expect(savedSearchRequests(state).jobDeletes).toHaveLength(0);
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(0);
  state.releaseSave();
  await expect.poll(() => savedSearchRequests(state).browseDeletes.length).toBe(1);
  expect(savedSearchRequests(state).jobDeletes).toHaveLength(1);
  expect(savedSearchRequests(state).saves).toHaveLength(1);
  expect(state.ledger.north.saved).toBe(true);
});

test('forced dismissal waits for a late receiver acquisition and retries its exact failed release', async ({ page }) => {
  const state = await install(page, { delayNextBrowseAcquire: true, delayNextBrowseDelete: true,
    failNextBrowseDelete: true });
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect.poll(() => Boolean(state.releaseBrowseAcquire)).toBe(true);
  await expect(dialog(page).getByRole('button', { name: 'Close Find Trunked Systems', exact: true })).toBeDisabled();
  await page.evaluate(async () => {
    const api = await import(document.querySelector('script[type="module"][src*="/assets/app.js"]').src);
    api.closeReadOnlyModal(true);
  });
  await expect(dialog(page)).toHaveCount(0);
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(0);
  state.releaseBrowseAcquire();
  await expect.poll(() => Boolean(state.releaseBrowseDelete)).toBe(true);
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(1);
  expect(state.requests.filter((request) => request.path === searchPath && request.method === 'POST')).toHaveLength(0);
  state.releaseBrowseDelete();
  await expect.poll(() => savedSearchRequests(state).browseDeletes.length).toBe(2);
  expect(savedSearchRequests(state).browseDeletes.map((request) => ({ path: request.path, body: request.body })))
    .toEqual(Array(2).fill({ path: '/api/v1/admin/tuners/idle-b/browse', body: { lease_id: 'lease-1' } }));
});

test('pagehide waits for late takeover preparation and retries only its owned receiver lease', async ({ page }) => {
  const state = await install(page, { noIdle: true, delayNextBrowseAcquire: true, delayNextBrowseDelete: true,
    failNextBrowseDelete: true });
  await dialog(page).getByRole('button', { name: 'Stop channels and use: Small receiver', exact: true }).click();
  await page.getByRole('alertdialog', { name: 'Stop channels for this search?' })
    .getByRole('button', { name: 'Stop channels and search', exact: true }).click();
  await expect.poll(() => Boolean(state.releaseBrowseAcquire)).toBe(true);
  await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide')));
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(0);
  state.releaseBrowseAcquire();
  await expect.poll(() => Boolean(state.releaseBrowseDelete)).toBe(true);
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(1);
  state.releaseBrowseDelete();
  await expect.poll(() => savedSearchRequests(state).browseDeletes.length).toBe(2);
  expect(savedSearchRequests(state).browseDeletes.map((request) => ({ path: request.path, body: request.body })))
    .toEqual(Array(2).fill({ path: '/api/v1/admin/tuners/idle-a/browse', body: { lease_id: 'lease-1' } }));
  expect(state.requests.filter((request) => request.path === searchPath && request.method === 'POST')).toHaveLength(0);
});

test('a late dismissed acquisition cannot release a newer search receiver lease', async ({ page }) => {
  const state = await install(page, { delayNextBrowseAcquire: true });
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect.poll(() => Boolean(state.releaseBrowseAcquire)).toBe(true);
  await page.evaluate(async () => {
    const api = await import(document.querySelector('script[type="module"][src*="/assets/app.js"]').src);
    api.closeReadOnlyModal(true);
  });
  await page.getByRole('button', { name: 'Find Trunked Systems', exact: true }).click();
  await expect(dialog(page).getByLabel('Receiver', { exact: true })).toBeVisible();
  await dialog(page).getByLabel('Receiver', { exact: true }).selectOption('idle-a');
  await complete(page);
  state.releaseBrowseAcquire();
  await expect.poll(() => savedSearchRequests(state).browseDeletes.length).toBe(1);
  expect(savedSearchRequests(state).browseDeletes[0]).toEqual({ path: '/api/v1/admin/tuners/idle-b/browse',
    method: 'DELETE', body: { lease_id: 'lease-1' } });
  expect(savedSearchRequests(state).jobDeletes).toHaveLength(0);
  const created = state.requests.find((request) => request.path === searchPath && request.method === 'POST');
  expect(created.body.tuner_id).toBe('idle-a');
  expect(created.body.browse_lease_id).toBe('lease-2');
  await expect(dialog(page).getByRole('heading', { name: /channels? found$/ })).toBeVisible();
});

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
  await expect(dialog(page).locator('.spectrum-search-form > .ui-notice:not([hidden])'))
    .toHaveText('The receiver scans each band, then checks promising signals.');
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
    .toHaveText(['', 'Channel', 'Site', 'Signal', 'Quality', 'Details']);
  await expect(dialog(page).locator('.spectrum-search-group-table').first().locator('tbody tr').first().locator('td'))
    .toHaveCount(6);
  await expect(dialog(page)).toContainText('WACN BEE00 · SysID 348 · 3 sites');
  await expect(dialog(page).getByRole('button', { name: /^Details for / })).toHaveCount(4);
  await expect(dialog(page).getByRole('searchbox')).toHaveCount(0);
  await expect(dialog(page).getByText('Search results', { exact: true })).toHaveCount(0);
  await expect(dialog(page).locator('tbody tr:visible')).toHaveCount(4);
  await expect(dialog(page).locator('.spectrum-search-system-group:visible')).toHaveCount(2);
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

test('configured tuning limits identify invalid selected bands before acquiring or posting a search', async ({ page }) => {
  const state = await install(page, { tuningLimits: [null, [767600000, 777600000]] });
  const search = dialog(page);
  const find = search.getByRole('button', { name: 'Find signals', exact: true });
  await expect(search).toContainText('Configured tuning limits: 767.600000–777.600000 MHz');
  await expect(search).toContainText('Outside these limits: 800 MHz · 851–869 MHz.');
  await expect(search).not.toContainText('This receiver does not cover');
  await expect(find).toBeDisabled();
  expect(state.requests.some((request) => request.method !== 'GET')).toBe(false);
  await find.evaluate((element) => { element.disabled = false; element.click(); });
  await expect(find).toBeDisabled();
  expect(state.requests.some((request) => request.method !== 'GET')).toBe(false);
  await expect(search.getByRole('link', { name: 'Manage receivers', exact: true }))
    .toHaveAttribute('href', /view=tuners/);
  await search.getByLabel('Receiver', { exact: true }).selectOption('idle-a');
  await expect(find).toBeEnabled();
  await expect(search.getByText('Configured tuning limits:', { exact: false })).toHaveCount(0);
  await complete(page);
  const create = state.requests.find((request) => request.path === searchPath && request.method === 'POST');
  expect(create.body.ranges).toEqual([
    { minimum_hz: 769000000, maximum_hz: 775000000 }, { minimum_hz: 851000000, maximum_hz: 869000000 }
  ]);
});

test('configured tuning-limit validation follows custom edits and band changes', async ({ page }) => {
  const state = await install(page, { tuningLimits: [null, [767600000, 777600000]] });
  const find = dialog(page).getByRole('button', { name: 'Find signals', exact: true });
  await openBands(page);
  await bandCheckbox(page, bandLabels[3]).uncheck();
  await closeBands(page);
  await expect(find).toBeEnabled();
  await openBands(page);
  await bandCheckbox(page, bandLabels[2]).uncheck();
  await bandCheckbox(page, bandLabels[4]).check();
  await closeBands(page);
  const minimum = dialog(page).getByLabel('Start frequency (MHz)');
  const maximum = dialog(page).getByLabel('End frequency (MHz)');
  await minimum.fill('800');
  await maximum.fill('810');
  await expect(dialog(page)).toContainText('Outside these limits: Custom range · 800.000000–810.000000 MHz.');
  await expect(find).toBeDisabled();
  await minimum.fill('769');
  await maximum.fill('775');
  await expect(find).toBeEnabled();
  await maximum.fill('');
  await find.click();
  expect(state.requests.some((request) => request.method !== 'GET')).toBe(false);
  await maximum.fill('775');
  await complete(page);
  expect(state.requests.find((request) => request.path === searchPath && request.method === 'POST').body.ranges)
    .toEqual([{ minimum_hz: 769000000, maximum_hz: 775000000 }]);
});

test('configured-limit Manage receivers navigation releases a borrowed tuner', async ({ page }) => {
  const state = await install(page, { noIdle: true, tuningLimits: [[767600000, 777600000], null] });
  await dialog(page).getByRole('button', { name: 'Stop channels and use: Small receiver', exact: true }).click();
  await page.getByRole('alertdialog', { name: 'Stop channels for this search?' })
    .getByRole('button', { name: 'Stop channels and search', exact: true }).click();
  await expect(dialog(page).getByRole('button', { name: 'Find signals', exact: true })).toBeDisabled();
  await dialog(page).getByRole('link', { name: 'Manage receivers', exact: true }).click();
  await expect(dialog(page)).toHaveCount(0);
  await expect(page).toHaveURL(/view=tuners/);
  await expect.poll(() => state.requests.filter((request) => request.path === '/api/v1/admin/tuners/idle-a/browse' &&
    request.method === 'DELETE').length).toBe(1);
  expect(state.requests.some((request) => request.path === searchPath && request.method === 'POST')).toBe(false);
});

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 390], ['dark', 390]]) {
  test(`configured tuning limits notice remains readable in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const state = await install(page, { theme, tuningLimits: [null, [767600000, 777600000]] });
    const search = dialog(page);
    await expect(search).toContainText('Configured tuning limits: 767.600000–777.600000 MHz');
    await expect(search).toContainText('Outside these limits: 800 MHz · 851–869 MHz.');
    await expect(search.getByRole('button', { name: 'Find signals', exact: true })).toBeDisabled();
    await expect(search.getByRole('link', { name: 'Manage receivers', exact: true })).toBeVisible();
    expect(await search.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    expect(state.requests.some((request) => request.method !== 'GET')).toBe(false);
    const screenshot = test.info().outputPath(`configured-limits-${theme}-${width}.png`);
    await search.screenshot({ path: screenshot });
    await test.info().attach('Configured tuning limits', { path: screenshot, contentType: 'image/png' });
  });
}

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

test('active scan summary follows owned window centers and actual checking measurements', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { phase: 'scanning', progress: { current_frequency_hz: 771819575 } });
  const summary = () => dialog(page).locator('.spectrum-discovery-context > .muted');
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(summary()).toHaveText('771.819575 MHz @ 9.00 MHz');
  state.progress.current_frequency_hz = 853819575;
  await page.clock.fastForward(800);
  await expect(summary()).toHaveText('853.819575 MHz @ 9.00 MHz');
  state.progress.current_frequency_hz = 0;
  await page.clock.fastForward(800);
  await expect(summary()).toHaveText('853.819575 MHz @ 9.00 MHz');

  state.phase = 'checking';
  state.progress.current_frequency_hz = 852300000;
  state.inventoryMeasurements = { 'idle-b': { frequency_hz: 852200000, usable_bandwidth_hz: 8000000 } };
  await page.clock.fastForward(800);
  await expect(summary()).toHaveText('852.200000 MHz @ 8.00 MHz');
  const reads = () => state.requests.filter((request) => request.path === '/api/v1/admin/tuners').length;
  const firstReads = reads();
  await page.clock.fastForward(1600);
  expect(reads()).toBe(firstReads);
  state.progress.current_frequency_hz = 853700000;
  state.inventoryMeasurements['idle-b'] = { frequency_hz: 853819575, usable_bandwidth_hz: 9000000 };
  await page.clock.fastForward(800);
  await expect(summary()).toHaveText('853.819575 MHz @ 9.00 MHz');
  expect(reads()).toBe(firstReads + 1);

  state.browseMeasurements = { 'idle-b': { frequency_hz: 854819575, usable_bandwidth_hz: 2350000 } };
  await page.clock.fastForward(10100);
  await expect(summary()).toHaveText('854.819575 MHz @ 2.35 MHz');
  expect(reads()).toBe(firstReads + 1);
  await dialog(page).getByRole('button', { name: 'Stop search', exact: true }).click();
  await expect(dialog(page)).toHaveCount(0);
});

test('checking summary retains last observed center on read failure and ignores late candidate replies', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { phase: 'scanning', progress: { current_frequency_hz: 853819575 } });
  const summary = () => dialog(page).locator('.spectrum-discovery-context > .muted');
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(summary()).toHaveText('853.819575 MHz @ 9.00 MHz');
  state.phase = 'checking';
  state.progress.current_frequency_hz = 852300000;
  state.failNextInventory = true;
  await page.clock.fastForward(800);
  await expect.poll(() => state.requests.filter((request) => request.path === '/api/v1/admin/tuners').length).toBe(1);
  await expect(summary()).toHaveText('853.819575 MHz @ 9.00 MHz');
  state.progress.current_frequency_hz = 853700000;
  state.inventoryMeasurements = { 'idle-b': { frequency_hz: 853800000 } };
  state.delayNextInventory = true;
  await page.clock.fastForward(800);
  await expect.poll(() => Boolean(state.releaseInventory)).toBe(true);
  state.progress.current_frequency_hz = 854700000;
  state.inventoryMeasurements['idle-b'] = { frequency_hz: 854819575, usable_bandwidth_hz: 8000000 };
  await page.clock.fastForward(800);
  await expect(summary()).toHaveText('854.819575 MHz @ 8.00 MHz');
  state.releaseInventory();
  await page.clock.fastForward(800);
  await expect(summary()).toHaveText('854.819575 MHz @ 8.00 MHz');
  state.progress.current_frequency_hz = 855700000;
  state.inventoryMeasurements['idle-b'] = { frequency_hz: 855819575 };
  state.delayNextInventory = true;
  state.releaseInventory = null;
  await page.clock.fastForward(800);
  await expect.poll(() => Boolean(state.releaseInventory)).toBe(true);
  await dialog(page).getByRole('button', { name: 'Stop search', exact: true }).click();
  await expect(dialog(page)).toHaveCount(0);
  state.releaseInventory();
  await page.clock.fastForward(800);
  await expect(dialog(page)).toHaveCount(0);
});

test('late receiver renewal cannot replace the next search receiver summary', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { phase: 'checking', progress: { current_frequency_hz: 852300000 },
    inventoryMeasurements: { 'idle-b': { frequency_hz: 852200000 } } });
  const summary = () => dialog(page).locator('.spectrum-discovery-context > .muted');
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(summary()).toHaveText('852.200000 MHz @ 9.00 MHz');
  state.delayNextBrowseRenew = true;
  state.browseMeasurements = { 'idle-b': { frequency_hz: 856819575, usable_bandwidth_hz: 8000000 } };
  await page.clock.fastForward(10100);
  await expect.poll(() => Boolean(state.releaseBrowseRenew)).toBe(true);
  state.phase = 'failed';
  await page.clock.fastForward(800);
  await expect(dialog(page).getByRole('heading', { name: 'Search stopped', exact: true })).toBeVisible();
  await dialog(page).getByRole('button', { name: 'Try another search', exact: true }).click();
  await dialog(page).getByLabel('Receiver', { exact: true }).selectOption('idle-a');
  await expect(summary()).toHaveText('773.081250 MHz @ 2.20 MHz');
  state.releaseBrowseRenew();
  await page.clock.fastForward(800);
  await expect(summary()).toHaveText('773.081250 MHz @ 2.20 MHz');
  state.phase = 'scanning';
  state.progress.current_frequency_hz = 770000000;
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(summary()).toHaveText('770.000000 MHz @ 2.20 MHz');
});

test('borrowed receiver context and picker follow actual renewal frequency and width before scanning', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { noIdle: true,
    browseMeasurements: { 'idle-b': { frequency_hz: 852400000, usable_bandwidth_hz: 8000000 } } });
  await dialog(page).getByRole('button', { name: 'Stop channels and use: Wide receiver', exact: true }).click();
  await page.getByRole('alertdialog', { name: 'Stop channels for this search?' })
    .getByRole('button', { name: 'Stop channels and search', exact: true }).click();
  const summary = dialog(page).locator('.spectrum-discovery-context > .muted');
  const receiver = dialog(page).getByLabel('Receiver', { exact: true });
  await expect(summary).toHaveText('852.400000 MHz @ 8.00 MHz');
  await expect(receiver.locator('option')).toHaveText(['Wide receiver · 852.400000 MHz @ 8.00 MHz']);
  state.browseMeasurements['idle-b'] = { frequency_hz: 853819575, usable_bandwidth_hz: 9000000 };
  await page.clock.fastForward(10100);
  await expect(summary).toHaveText('853.819575 MHz @ 9.00 MHz');
  await expect(receiver.locator('option')).toHaveText(['Wide receiver · 853.819575 MHz @ 9.00 MHz']);
  expect(state.requests.some((request) => request.path === searchPath && request.method === 'POST')).toBe(false);
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
  await expect(dialog(page).locator('.spectrum-discovery-stage > .ui-notice')).toHaveText(state.reason);
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
  await expect(dialog(page).locator('.spectrum-search-review-system').filter({ hasText: 'Regional Services' })
    .getByRole('button', { name: 'Done' })).toBeVisible();
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

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`search review exposes Alias List creation and compatible reuse in ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    const state = await install(page, { theme });
    await complete(page);
    await dialog(page).getByRole('button', { name: 'Select all available' }).click();
    await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
    const existing = dialog(page).locator('.spectrum-search-review-system').filter({ hasText: 'County Public Safety' });
    await expect(existing).toContainText('Alias List: County Dispatch · Existing');
    const regional = dialog(page).locator('.spectrum-search-review-system').filter({ hasText: 'Regional Services' });
    const name = regional.getByLabel('New Alias List name for ABC00-234');
    await expect(name).toBeVisible();
    await expect(regional).toContainText('An existing compatible Alias List with this name will be used.');
    await name.fill('Regional existing');
    await expect(regional).toContainText('Alias List: Regional existing');
    await expect(regional).not.toContainText('· New');
    await name.scrollIntoViewIfNeeded();
    await dialog(page).screenshot({ path: testInfo.outputPath('alias-list-review.png') });
    await dialog(page).getByRole('button', { name: /^Add \d+ channels?$/ }).click();
    await expect(dialog(page)).toContainText('3 channels added.');
    const save = state.requests.find((request) => request.path.endsWith('/save'));
    expect(save.body.alias_groups).toEqual([
      { group_id: 'county', alias_list_id: 21, new_alias_list_name: 'County P25' },
      { group_id: 'regional', alias_list_id: 0, new_alias_list_name: 'Regional existing' }
    ]);
  });
}

test('search review reveals an invalid Alias List choice when its panel was closed', async ({ page }) => {
  const state = await install(page, { ambiguous: true });
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  await dialog(page).getByRole('button', { name: /^Review \d+ channels?$/ }).click();
  await dialog(page).getByRole('button', { name: 'Done', exact: true }).click();
  await expect(dialog(page).getByLabel('Alias List for BEE00-348')).toBeHidden();
  await dialog(page).getByRole('button', { name: 'Add 1 channel', exact: true }).click();
  await expect(dialog(page).getByLabel('Alias List for BEE00-348')).toBeVisible();
  expect(state.requests.some((request) => request.path.endsWith('/save'))).toBe(false);
});

test('signed-in search offers its missing frequency lookup region directly', async ({ page }) => {
  const state = await install(page, { directoryConfiguration: { account: { state: 'VALID_PREMIUM' } } });
  await expect(dialog(page).getByLabel('RadioReference country')).toBeVisible();
  await expect(dialog(page)).toContainText('Choose a country to look up nearby systems.');
  await dialog(page).getByLabel('RadioReference country').selectOption('1');
  await expect(dialog(page).getByLabel('RadioReference state or province')).toBeEnabled();
  await expect(dialog(page).getByLabel('RadioReference state or province')).toHaveValue('');
  await dialog(page).getByLabel('RadioReference state or province').selectOption('42');
  await complete(page);
  expect(state.requests.find((request) => request.path === searchPath && request.method === 'POST')
    .body.radioreference_state_id).toBe(42);
});

test('search start waits for its saved frequency lookup region', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { delayDirectoryConfiguration: true, directoryConfiguration: {
    account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } });
  await expect.poll(() => typeof state.releaseDirectory).toBe('function');
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await page.clock.fastForward(6000);
  expect(state.requests.some((request) => request.path === searchPath && request.method === 'POST')).toBe(false);
  state.releaseDirectory();
  await expect(dialog(page).getByRole('heading', { name: /channels? found$/ })).toBeVisible();
  expect(state.requests.find((request) => request.path === searchPath && request.method === 'POST')
    .body.radioreference_state_id).toBe(39);
});

test('search lookup regions wait beyond five seconds and preserve the saved state at start', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { delayDirectoryStates: true, directoryConfiguration: {
    account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } });
  await expect.poll(() => typeof state.releaseDirectoryStates).toBe('function');
  await dialog(page).getByText('RadioReference names (optional)', { exact: true }).click();
  await expect(dialog(page)).toContainText('Loading states and provinces…');
  await page.clock.fastForward(6000);
  await expect(dialog(page).getByRole('button', { name: 'Retry loading regions', exact: true })).toBeHidden();
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  expect(state.requests.some(({ path, method }) => path === searchPath && method === 'POST')).toBe(false);
  state.releaseDirectoryStates();
  await expect(dialog(page).getByRole('heading', { name: /channels? found$/ })).toBeVisible();
  expect(state.requests.find(({ path, method }) => path === searchPath && method === 'POST')
    .body.radioreference_state_id).toBe(39);
});

for (const [theme, viewport] of [['light', { width: 1365, height: 900 }],
  ['dark', { width: 390, height: 844 }]]) {
  test(`search lookup region failure can retry in ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    const state = await install(page, { theme, failDirectoryStatesCount: 1,
      directoryConfiguration: { account: { state: 'VALID_PREMIUM' } } });
    await expect(dialog(page).getByLabel('RadioReference country')).toBeVisible();
    await dialog(page).getByLabel('RadioReference country').selectOption('1');
    await expect(dialog(page)).toContainText(
      'States and provinces could not be loaded. Retry, or continue without frequency matching.');
    const retry = dialog(page).getByRole('button', { name: 'Retry loading regions', exact: true });
    await expect(retry).toBeVisible();
    await expect(dialog(page).getByLabel('RadioReference state or province')).toBeDisabled();
    await retry.scrollIntoViewIfNeeded();
    await dialog(page).screenshot({ path: testInfo.outputPath('region-load-failure.png') });
    await retry.click();
    const region = dialog(page).getByLabel('RadioReference state or province');
    await expect(region).toBeEnabled();
    await expect(region).toHaveValue('');
    await expect(retry).toBeHidden();
    await region.selectOption('42');
    await region.scrollIntoViewIfNeeded();
    await dialog(page).screenshot({ path: testInfo.outputPath('region-load-recovered.png') });
    await complete(page);
    expect(state.requests.filter(({ path }) => path === '/api/v1/admin/radioreference/states')).toHaveLength(2);
    expect(state.requests.find(({ path, method }) => path === searchPath && method === 'POST')
      .body.radioreference_state_id).toBe(42);
  });
}

test('search lookup region list failure keeps the saved state in the start payload', async ({ page }) => {
  const state = await install(page, { failDirectoryStatesCount: 1, directoryConfiguration: {
    account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } });
  await expect(dialog(page)).toContainText('Your saved lookup region will be used.');
  await expect(dialog(page).getByLabel('RadioReference state or province')).toHaveValue('39');
  await expect(dialog(page).getByRole('button', { name: 'Retry loading regions', exact: true })).toBeVisible();
  await complete(page);
  expect(state.requests.find(({ path, method }) => path === searchPath && method === 'POST')
    .body.radioreference_state_id).toBe(39);
});

test('search start waits for a pending region retry', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { failDirectoryStatesCount: 1, directoryConfiguration: {
    account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } });
  await expect(dialog(page)).toContainText('Your saved lookup region will be used.');
  state.delayDirectoryStates = true;
  await dialog(page).getByRole('button', { name: 'Retry loading regions', exact: true }).click();
  await expect.poll(() => typeof state.releaseDirectoryStates).toBe('function');
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await page.clock.fastForward(1000);
  expect(state.requests.some(({ path, method }) => path === searchPath && method === 'POST')).toBe(false);
  state.releaseDirectoryStates();
  await expect(dialog(page).getByRole('heading', { name: /channels? found$/ })).toBeVisible();
  expect(state.requests.filter(({ path }) => path === '/api/v1/admin/radioreference/states')).toHaveLength(2);
  expect(state.requests.find(({ path, method }) => path === searchPath && method === 'POST')
    .body.radioreference_state_id).toBe(39);
});

test('search lookup country failure retries account settings and recovers its region choices', async ({ page }) => {
  const state = await install(page, { failDirectoryCountriesCount: 1,
    directoryConfiguration: { account: { state: 'VALID_PREMIUM' } } });
  await expect(dialog(page)).toContainText('Lookup regions could not be loaded. Retry, or continue without choosing a lookup region.');
  await dialog(page).getByRole('button', { name: 'Retry loading regions', exact: true }).click();
  await expect(dialog(page).getByLabel('RadioReference country')).toBeVisible();
  await dialog(page).getByLabel('RadioReference country').selectOption('1');
  const region = dialog(page).getByLabel('RadioReference state or province');
  await expect(region).toBeEnabled();
  await region.selectOption('39');
  await complete(page);
  expect(state.requests.filter(({ path }) => path === '/api/v1/admin/radioreference')).toHaveLength(2);
  expect(state.requests.filter(({ path }) => path === '/api/v1/admin/radioreference/countries')).toHaveLength(2);
  expect(state.requests.find(({ path, method }) => path === searchPath && method === 'POST')
    .body.radioreference_state_id).toBe(39);
});

test('manually expanded search lookup settings remain open after delayed saved-region configuration', async ({ page }) => {
  const state = await install(page, { delayDirectoryConfiguration: true, directoryConfiguration: {
    account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } });
  await expect.poll(() => typeof state.releaseDirectory).toBe('function');
  const lookup = dialog(page).locator('details').filter({ has: page.locator('summary')
    .filter({ hasText: /^RadioReference names \(optional\)$/ }) });
  await lookup.locator('summary').click();
  const lookupNode = await lookup.elementHandle();
  await expect(lookup).toHaveAttribute('open', '');
  await expect(lookup).toContainText('Loading RadioReference settings…');
  state.releaseDirectory();
  await expect(lookup.getByLabel('RadioReference state or province')).toBeEnabled();
  await expect(lookup.getByLabel('RadioReference state or province')).toHaveValue('39');
  await expect(lookup).toHaveAttribute('open', '');
  expect(await lookup.evaluate((element, previous) => element === previous, lookupNode)).toBe(true);
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
  await expect(dialog(page)).toContainText('Saved choices changed');
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
  await expect(dialog(page)).toContainText('Lookup regions could not be loaded. Retry, or continue without choosing a lookup region.');
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
  await expect(dialog(page).getByRole('link', { name: 'County Transit', exact: true })).toHaveAttribute('href',
    'https://www.radioreference.com/db/sid/123');
  await expect(dialog(page).getByRole('link', { name: 'County Transit', exact: true })).toHaveCount(1);
  await expect(dialog(page)).not.toContainText('Primary control');
  await dialog(page).getByText('RadioReference', { exact: true }).click();
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
  await page.clock.install();
  const state = await install(page, { customCandidates: [{ candidate_id: 'digital', frequency_hz: 451000000,
    protocol_id: 'dmr', name: 'Decoded control', system_name: 'Decoded network', alias_group_id: 'digital',
    radio_reference: { state: 'pending' }, trunked_evidence: { identity: {
      radio_system_key: 'dmr:tier3:small:12', network: 12, site: 3 }, frequency_map: [] } }],
    customAliasGroups: [{ group_id: 'digital', protocol_id: 'dmr', alias_lists: [], default_new_alias_list_name: 'Decoded' }] });
  await complete(page);
  await dialog(page).getByRole('button', { name: 'Select all available' }).click();
  await dialog(page).getByRole('button', { name: 'Review 1 channel', exact: true }).click();
  await dialog(page).getByLabel('Channel name for 451 MHz', { exact: true }).fill('My control');
  await dialog(page).getByText('RadioReference', { exact: true }).click();
  const directory = dialog(page).locator('details').filter({ has: page.locator('summary').filter({ hasText: /^RadioReference$/ }) });
  const directoryNode = await directory.elementHandle();
  const pendingNode = await directory.locator('p').elementHandle();
  const name = dialog(page).getByLabel('Channel name for 451 MHz', { exact: true });
  const nameNode = await name.elementHandle();
  await name.focus();
  const polls = () => state.requests.filter(({ path, method }) => path === `${searchPath}/search-a` && method === 'GET').length;
  for (let update = 0; update < 3; update += 1) {
    const before = polls();
    state.customCandidates[0].radio_reference.message = `Raw directory stage ${update}`;
    await page.clock.fastForward(800);
    await expect.poll(polls).toBeGreaterThan(before);
    await expect(directory).toHaveAttribute('open', '');
    expect(await directory.evaluate((element, previous) => element === previous, directoryNode)).toBe(true);
    expect(await directory.locator('p').evaluate((element, previous) => element === previous, pendingNode)).toBe(true);
    await expect(directory.locator('p')).toHaveText('Looking up the system name…');
    expect(await name.evaluate((element, previous) => element === previous, nameNode)).toBe(true);
    await expect(name).toBeFocused();
  }
  state.customCandidates[0] = { ...state.customCandidates[0], name: 'Directory North', system_name: 'Transit authority',
    site_name: 'North', radio_reference: { state: 'matched', match: { system_name: 'Transit authority', site_name: 'North',
      url: 'https://www.radioreference.com/db/sid/123',
      channels: [{ logical_channel_number: 3, frequency_hz: 451500000 }] } }, trunked_evidence: {
      ...state.customCandidates[0].trunked_evidence, frequency_map: [{ number: 3, downlink_hz: 451500000 }] } };
  await page.clock.fastForward(800);
  await expect(dialog(page).getByRole('heading', { name: 'Transit authority', exact: true })).toBeVisible();
  await expect(dialog(page).getByLabel('Channel name for 451 MHz', { exact: true })).toHaveValue('My control');
  expect(await name.evaluate((element, previous) => element === previous, nameNode)).toBe(true);
  await expect(name).toBeFocused();
  await expect(dialog(page).locator('.channel-map-row input').nth(0)).toHaveValue('3');
  await expect(directory).toHaveCount(0);
  await expect(dialog(page).getByRole('heading', { name: 'Transit authority', exact: true }).getByRole('link'))
    .toHaveAttribute('href', 'https://www.radioreference.com/db/sid/123');
});

for (const [theme, viewport] of [['light', { width: 1280, height: 900 }],
  ['dark', { width: 1280, height: 900 }], ['light', { width: 390, height: 844 }],
  ['dark', { width: 390, height: 844 }]]) {
  test(`Ohio trunked results align selections and keep details separate in ${theme} at ${viewport.width}px`,
    async ({ page }, testInfo) => {
      await page.setViewportSize(viewport);
      const state = await install(page, { theme, ...ohioResultsFixture() });
      await complete(page);
      await expect(dialog(page).getByRole('heading', { name: '4 channels found', exact: true })).toBeVisible();
      const groups = dialog(page).locator('.spectrum-search-system-group');
      await expect(groups).toHaveCount(3);
      await expect(dialog(page)).not.toContainText('P25_PHASE_1');
      const checkShapes = await groups.locator('input[type="checkbox"]').evaluateAll((checks) => checks.map((check) => {
        const bounds = check.getBoundingClientRect();
        return { width: bounds.width, height: bounds.height };
      }));
      checkShapes.forEach((bounds) => {
        expect(bounds.width).toBe(bounds.height);
        expect(bounds.width).toBeGreaterThanOrEqual(16);
      });
      for (const name of Object.values(ohioSystems)) {
        await expect(dialog(page).getByRole('link', { name, exact: true })).toHaveCount(1);
      }
      const tables = dialog(page).locator('.spectrum-search-group-table');
      for (const table of await tables.all()) {
        await expect(table.locator('thead th')).toHaveText(['', 'Channel', 'Site', 'Signal', 'Quality', 'Details']);
        if (viewport.width > 900) {
          await expect(table.getByRole('columnheader').first()).toHaveAccessibleName('Select channels');
        }
        for (const row of await table.locator('tbody tr').all()) {
          await expect(row.locator('td')).toHaveCount(6);
          await expect(row.locator('td').first().getByRole('checkbox')).toHaveCount(1);
          const detailsAction = row.getByRole('button', { name: /^Details for / });
          await expect(detailsAction).toHaveText('Details');
          const captionLines = await detailsAction.evaluate((element) => {
            const text = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
            const tops = new Set();
            while (text.nextNode()) {
              if (!text.currentNode.textContent.trim()) continue;
              const range = document.createRange(); range.selectNodeContents(text.currentNode);
              [...range.getClientRects()].forEach((rect) => tops.add(Math.round(rect.top)));
            }
            return tops.size;
          });
          expect(captionLines).toBe(1);
          await expect(row.locator('details')).toHaveCount(0);
          if (viewport.width > 900) expect((await row.boundingBox()).height).toBeLessThanOrEqual(100);
        }
      }
      if (viewport.width > 900) {
        const widths = await tables.evaluateAll((elements) => elements.map((table) =>
          [...table.querySelectorAll('thead th')].map((cell) => cell.getBoundingClientRect().width)));
        for (const actual of widths.slice(1)) actual.forEach((width, column) =>
          expect(Math.abs(width - widths[0][column])).toBeLessThanOrEqual(1));
        const aligned = await groups.evaluateAll((elements) => elements.map((group) => ({
          group: group.querySelector('header input[type="checkbox"]').getBoundingClientRect().left,
          rows: [...group.querySelectorAll('tbody input[type="checkbox"]')]
            .map((check) => check.getBoundingClientRect().left)
        })));
        aligned.forEach((group) => group.rows.forEach((left) =>
          expect(Math.abs(left - group.group)).toBeLessThanOrEqual(2)));
      }
      expect(await dialog(page).evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);
      await dialog(page).screenshot({ path: testInfo.outputPath('ohio-results.png') });
      const first = dialog(page).getByRole('checkbox', { name: 'Select MARCS Cuyahoga control', exact: true });
      const second = dialog(page).getByRole('checkbox', { name: 'Select MARCS Lake control', exact: true });
      const cleveland = dialog(page).getByRole('checkbox', { name: 'Select Cleveland control', exact: true });
      const allMarcs = dialog(page).getByRole('checkbox', { name: `Select all channels in ${ohioSystems.marcs}`, exact: true });
      await first.check();
      await expect(allMarcs).toHaveJSProperty('indeterminate', true);
      await allMarcs.check();
      await expect(first).toBeChecked();
      await expect(second).toBeChecked();
      await expect(allMarcs).toHaveJSProperty('indeterminate', false);
      await second.uncheck();
      await expect(allMarcs).toHaveJSProperty('indeterminate', true);
      await cleveland.check();
      await expect(dialog(page).getByRole('searchbox')).toHaveCount(0);
      await expect(dialog(page).getByText('Search results', { exact: true })).toHaveCount(0);
      await expect(dialog(page).getByRole('button', { name: 'Review 2 channels', exact: true })).toBeEnabled();
      await expect(first).toBeChecked();
      await expect(dialog(page).locator('tbody tr:visible')).toHaveCount(4);
      await expect(cleveland).toBeChecked();
      const details = dialog(page).getByRole('button', { name: 'Details for 773.08125 MHz', exact: true });
      const parent = await dialog(page).elementHandle();
      const row = details.locator('xpath=ancestor::tr');
      const before = await row.boundingBox();
      await details.click();
      const signal = page.locator('.spectrum-search-detail-modal');
      await expect(signal.getByRole('heading', { name: 'Signal details · 773.08125 MHz', exact: true })).toBeVisible();
      await expect(signal.getByRole('group', { name: 'Channel', exact: true })).toBeVisible();
      await expect(signal.getByRole('group', { name: 'On-air identity', exact: true })).toBeVisible();
      await expect(signal.getByRole('group', { name: 'Signal health', exact: true })).toBeVisible();
      await expect(signal).toContainText(ohioSystems.marcs);
      await expect(signal).toContainText('Cuyahoga County');
      await expect(signal).toContainText('BEE00');
      expect(await signal.evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);
      const wacnLines = await signal.getByText('BEE00', { exact: true }).evaluate((element) => {
        const range = document.createRange(); range.selectNodeContents(element);
        return range.getClientRects().length;
      });
      expect(wacnLines).toBe(1);
      await signal.screenshot({ path: testInfo.outputPath('ohio-signal-details.png') });
      await page.keyboard.press('Escape');
      await expect(signal).toHaveCount(0);
      await expect(details).toBeFocused();
      expect(await dialog(page).evaluate((element, previous) => element === previous, parent)).toBe(true);
      expect((await row.boundingBox()).height).toBe(before.height);
      await expect(first).toBeChecked();
      await expect(cleveland).toBeChecked();
      await dialog(page).getByRole('button', { name: 'Review 2 channels', exact: true }).click();
      await expect(dialog(page).getByLabel('Channel name for 773.08125 MHz', { exact: true })).toHaveValue('MARCS Cuyahoga control');
      await expect(dialog(page).getByLabel('Channel name for 851.0125 MHz', { exact: true })).toHaveValue('Cleveland control');
      await expect(dialog(page).getByLabel('Channel name for 773.83125 MHz', { exact: true })).toHaveCount(0);
      for (const name of [ohioSystems.marcs, ohioSystems.cleveland]) {
        await expect(dialog(page).getByRole('link', { name, exact: true })).toHaveCount(1);
      }
      const marcsReview = dialog(page).locator('.spectrum-search-review-system').filter({
        has: page.getByRole('heading', { name: ohioSystems.marcs, exact: true })
      });
      const reviewAppearance = await marcsReview.locator('h4').evaluate((title) => {
        const style = getComputedStyle(title);
        return { marginBlockStart: style.marginBlockStart, marginBlockEnd: style.marginBlockEnd,
          fontSize: style.fontSize, fontWeight: style.fontWeight,
          headerHeight: title.closest('header').getBoundingClientRect().height };
      });
      if (viewport.width > 900) expect(reviewAppearance.headerHeight).toBeLessThanOrEqual(80);
      await testInfo.attach('Review header spacing', {
        body: JSON.stringify(reviewAppearance), contentType: 'application/json'
      });
      await marcsReview.getByRole('button', { name: 'Customize', exact: true }).click();
      const identity = marcsReview.locator('details').filter({ has: page.locator('summary').filter({ hasText: /^System identity$/ }) });
      await identity.locator('summary').click();
      await expect(identity).toHaveAttribute('open', '');
      await expect(identity).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
      await expect(dialog(page).getByText('RadioReference', { exact: true })).toHaveCount(0);
      await dialog(page).screenshot({ path: testInfo.outputPath('ohio-review-customize.png') });
      await dialog(page).getByRole('button', { name: 'Add 2 channels', exact: true }).click();
      const save = state.requests.find((request) => request.path.endsWith('/save'));
      expect(save.body.candidates.map((candidate) => candidate.candidate_id)).toEqual(['marcs-27', 'cleveland-3']);
    });
}

for (const [theme, viewport] of [['light', { width: 1280, height: 900 }],
  ['dark', { width: 390, height: 844 }]]) {
  test(`search result directory polls preserve detail dialogs, groups and selection in ${theme}`,
    async ({ page }, testInfo) => {
      await page.clock.install();
      await page.setViewportSize(viewport);
      const systemName = theme === 'dark' ? ohioSystems.marcs : 'County Transit';
      const state = await install(page, { theme, customCandidates: [
        { candidate_id: 'digital', frequency_hz: 451000000, protocol_id: 'dmr', name: 'Metro control',
          system_name: 'Decoded network', alias_group_id: 'digital', radio_reference: { state: 'pending' },
          trunked_evidence: { identity: { radio_system_key: 'dmr:tier3:small:12', network: 12, site: 3 } } },
        { candidate_id: 'local', frequency_hz: 452000000, protocol_id: 'dmr', name: 'Regional local',
          system_name: 'Other network', alias_group_id: 'other', trunked_evidence: {
            identity: { radio_system_key: 'dmr:tier3:small:13', network: 13, site: 4 } } }
      ], customAliasGroups: [{ group_id: 'digital', protocol_id: 'dmr', alias_lists: [], default_new_alias_list_name: 'Metro' },
        { group_id: 'other', protocol_id: 'dmr', alias_lists: [], default_new_alias_list_name: 'Regional' }] });
      await complete(page);
      await expect(dialog(page).getByRole('searchbox')).toHaveCount(0);
      await expect(dialog(page).locator('tbody tr:visible')).toHaveCount(2);
      const selection = dialog(page).getByRole('checkbox', { name: 'Select Metro control', exact: true, includeHidden: true });
      await selection.check();
      const group = dialog(page).locator('.spectrum-search-system-group').filter({ has: page.locator('input[aria-label="Select Metro control"]') });
      const directory = group.locator('details').filter({ has: page.locator('summary').filter({ hasText: /^RadioReference$/ }) });
      await directory.locator('summary').click();
      const detailButton = group.getByRole('button', { name: 'Details for 451 MHz', exact: true, includeHidden: true });
      const preserved = { parent: await dialog(page).elementHandle(), group: await group.elementHandle(),
        selection: await selection.elementHandle(), button: await detailButton.elementHandle(),
        directory: await directory.elementHandle(), pending: await directory.locator('p').elementHandle() };
      await detailButton.click();
      const signal = page.locator('.spectrum-search-detail-modal');
      await expect(signal).toBeVisible();
      await expect(signal.getByRole('heading', { name: 'Signal details · 451 MHz', exact: true })).toBeVisible();
      const signalNode = await signal.elementHandle();
      expect(await dialog(page).evaluate((element) => element.closest('.modal-backdrop').inert)).toBe(true);
      const polls = () => state.requests.filter(({ path, method }) => path === `${searchPath}/search-a` && method === 'GET').length;
      for (let update = 0; update < 2; update += 1) {
        const before = polls();
        state.customCandidates[0].radio_reference.message = `Raw directory stage ${update}`;
        await page.clock.fastForward(800);
        await expect.poll(polls).toBeGreaterThan(before);
        await expect(directory).toHaveAttribute('open', '');
        expect(await directory.evaluate((element, previous) => element === previous, preserved.directory)).toBe(true);
        expect(await directory.locator('p').evaluate((element, previous) => element === previous, preserved.pending)).toBe(true);
        await expect(directory.locator('p')).toHaveText('Looking up the system name…');
        expect(await signal.evaluate((element, previous) => element === previous, signalNode)).toBe(true);
      }
      await signal.screenshot({ path: testInfo.outputPath('signal-details-pending.png') });
      state.customCandidates[0] = { ...state.customCandidates[0], system_name: systemName,
        site_name: 'Directory-only North', radio_reference: { state: 'matched', provenance: 'Directory-only provenance',
          match: { system_name: systemName, site_name: 'Directory-only North',
            url: 'https://www.radioreference.com/db/sid/123', site_url: 'https://www.radioreference.com/db/site/456',
            channels: [{ frequency_hz: 451500000, logical_channel_number: 3, primary_control: true }] } } };
      await page.clock.fastForward(800);
      await expect(group.getByRole('link', { name: systemName, exact: true, includeHidden: true }))
        .toHaveAttribute('href', 'https://www.radioreference.com/db/sid/123');
      await expect(group.getByRole('link', { name: systemName, exact: true, includeHidden: true })).toHaveCount(1);
      await expect(directory).toHaveCount(0);
      await expect(signal).toBeVisible();
      expect(await signal.evaluate((element, previous) => element === previous, signalNode)).toBe(true);
      for (const [locator, previous] of [[dialog(page), preserved.parent], [group, preserved.group],
        [selection, preserved.selection], [detailButton, preserved.button]]) {
        expect(await locator.evaluate((element, previous) => element === previous, previous)).toBe(true);
      }
      await expect(selection).toBeChecked();
      await expect(signal).toContainText('Directory-only North');
      await page.keyboard.press('Escape');
      await expect(signal).toHaveCount(0);
      await expect(detailButton).toBeFocused();
      expect(await dialog(page).evaluate((element) => element.closest('.modal-backdrop').inert)).toBe(false);
      await expect(dialog(page).getByRole('checkbox', { name: 'Select Regional local', exact: true })).toBeVisible();
      await expect(dialog(page).locator('tbody tr:visible')).toHaveCount(2);
      await expect(dialog(page).getByRole('button', { name: 'Review 1 channel', exact: true })).toBeEnabled();
      await dialog(page).screenshot({ path: testInfo.outputPath('directory-compact-match.png') });
      expect(await dialog(page).evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
      await expect(selection).toBeVisible();
      await detailButton.click();
      await expect(page.locator('.spectrum-search-detail-modal')).toContainText('Directory-only North');
      await page.keyboard.press('Escape');
    });
}

test('verified system names keep pending sibling sites and selection visible', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { ...ohioResultsFixture({ pendingMarcs: true }) });
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select MARCS Lake control', exact: true }).check();
  const pending = dialog(page).locator('.spectrum-search-system-group').first().locator('summary')
    .filter({ hasText: /^RadioReference$/ });
  await pending.click();
  await pending.focus();
  const candidate = state.customCandidates[0];
  state.customCandidates[0] = { ...candidate, system_name: ohioSystems.marcs,
    radio_reference: { state: 'matched', match: { system_name: ohioSystems.marcs,
      url: 'https://www.radioreference.com/db/sid/123' } } };
  await page.clock.fastForward(800);
  await expect(dialog(page).getByRole('link', { name: ohioSystems.marcs, exact: true })).toBeFocused();
  await expect(dialog(page).getByRole('searchbox')).toHaveCount(0);
  await expect(dialog(page).locator('tbody tr:visible')).toHaveCount(4);
  await expect(dialog(page).getByRole('checkbox', { name: 'Select MARCS Lake control', exact: true })).toBeChecked();
  await expect(dialog(page).getByRole('link', { name: ohioSystems.marcs, exact: true })).toHaveCount(1);
  await expect(dialog(page).getByText('RadioReference', { exact: true })).toHaveCount(0);
  await expect(dialog(page).getByRole('button', { name: 'Review 1 channel', exact: true })).toBeEnabled();
  await dialog(page).getByRole('button', { name: 'Review 1 channel', exact: true }).click();
  const heading = dialog(page).getByRole('heading', { name: ohioSystems.marcs, exact: true });
  await expect(heading.getByRole('link')).toHaveAttribute('href', 'https://www.radioreference.com/db/sid/123');
  await expect(dialog(page).getByRole('link', { name: ohioSystems.marcs, exact: true })).toHaveCount(1);
  await expect(dialog(page).getByText('RadioReference', { exact: true })).toHaveCount(0);
  const headingNode = await heading.elementHandle();
  const polls = () => state.requests.filter(({ path, method }) => path === `${searchPath}/search-a` && method === 'GET').length;
  const before = polls();
  await page.clock.fastForward(800);
  await expect.poll(polls).toBeGreaterThan(before);
  expect(await heading.evaluate((element, previous) => element === previous, headingNode)).toBe(true);
  await expect(heading.getByRole('link')).toHaveAttribute('href', 'https://www.radioreference.com/db/sid/123');
});

test('result details close when polling removes their channel and prune only that selection', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { ...ohioResultsFixture({ pendingMarcs: true }) });
  await complete(page);
  await expect(dialog(page).getByText('RadioReference', { exact: true })).toHaveCount(1);
  await dialog(page).getByRole('checkbox', { name: 'Select MARCS Cuyahoga control', exact: true }).check();
  await dialog(page).getByRole('checkbox', { name: 'Select Cleveland control', exact: true }).check();
  await expect(dialog(page).getByRole('searchbox')).toHaveCount(0);
  await dialog(page).getByRole('button', { name: 'Details for 773.08125 MHz', exact: true }).click();
  await expect(page.locator('.spectrum-search-detail-modal')).toBeVisible();
  state.customCandidates = state.customCandidates.filter((candidate) => candidate.candidate_id !== 'marcs-27');
  await page.clock.fastForward(800);
  await expect(page.locator('.spectrum-search-detail-modal')).toHaveCount(0);
  await expect(dialog(page).getByRole('heading', { name: '3 channels found', exact: true })).toBeFocused();
  await expect(dialog(page).locator('tbody tr:visible')).toHaveCount(3);
  await expect(dialog(page).getByRole('checkbox', { name: 'Select MARCS Cuyahoga control', exact: true })).toHaveCount(0);
  await expect(dialog(page).getByRole('button', { name: 'Review 1 channel', exact: true })).toBeEnabled();
  await expect(dialog(page).getByRole('checkbox', { name: 'Select Cleveland control', exact: true })).toBeChecked();
  expect(savedSearchRequests(state).jobDeletes).toHaveLength(0);
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(0);
});

test('expired polling closes result details before guarded search cleanup and restores parent focus', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { ...ohioResultsFixture({ pendingMarcs: true }), requireJobRelease: true });
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select MARCS Cuyahoga control', exact: true }).check();
  await dialog(page).getByRole('button', { name: 'Details for 773.08125 MHz', exact: true }).click();
  await expect(page.locator('.spectrum-search-detail-modal')).toBeVisible();
  state.expired = true;
  state.failNextJobDelete = true;
  await page.clock.fastForward(800);
  await expect(page.locator('.spectrum-search-detail-modal')).toHaveCount(0);
  await expect(dialog(page).getByRole('heading', { name: 'This search expired', exact: true })).toBeFocused();
  await expect.poll(() => savedSearchRequests(state).jobDeletes.length).toBe(1);
  expect(savedSearchRequests(state).browseDeletes).toHaveLength(0);
  expect(savedSearchRequests(state).saves).toHaveLength(0);
  await dialog(page).getByRole('button', { name: 'Start a new search', exact: true }).click();
  await expect(dialog(page).getByRole('heading', { name: 'Choose where to look', exact: true })).toBeFocused();
  const { jobDeletes, browseDeletes } = savedSearchRequests(state);
  expect(jobDeletes).toHaveLength(2);
  expect(browseDeletes).toHaveLength(1);
  expect(state.requests.indexOf(browseDeletes[0])).toBeGreaterThan(state.requests.indexOf(jobDeletes[1]));
});

test('search start failure restores the saved region control after its pending initial load', async ({ page }) => {
  const state = await install(page, { delayDirectoryStates: true, failSearchCreateOnce: true,
    directoryConfiguration: { account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } });
  await expect.poll(() => typeof state.releaseDirectoryStates).toBe('function');
  await dialog(page).getByText('RadioReference names (optional)', { exact: true }).click();
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  state.releaseDirectoryStates();
  await expect(dialog(page)).toContainText('We couldn’t start the search.');
  const region = dialog(page).getByLabel('RadioReference state or province');
  await expect(region).toBeEnabled();
  await expect(region).toHaveValue('39');
  await complete(page);
  expect(state.requests.filter(({ path, method }) => path === searchPath && method === 'POST')
    .map(({ body }) => body.radioreference_state_id)).toEqual([39, 39]);
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
    const details = dialog(page).locator('tbody tr').first().getByRole('button', { name: /^Details for / });
    await details.click();
    const signalDetails = page.locator('.spectrum-search-detail-modal');
    await expect(signalDetails).toBeVisible();
    await expect(signalDetails).toHaveScreenshot(`spectrum-search-signal-details-${theme}-${width}.png`);
    await page.keyboard.press('Escape');
    await expect(signalDetails).toHaveCount(0);
    await expect(details).toBeFocused();
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
