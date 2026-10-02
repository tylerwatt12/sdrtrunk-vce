const { expect, test } = require('@playwright/test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');
const root = resolve(__dirname, '../../../../..');
const searchPath = '/api/v1/admin/spectrum-search';
const configurationId = '7f408d02-7c20-44b2-97ce-f6202b24b0f7';
const dialog = (page) => page.locator('.spectrum-search-modal');
const bandLabels = ['VHF high · 138–174 MHz', 'UHF · 406–470 MHz', '700 MHz · 769–775 MHz',
  '800 MHz · 851–869 MHz', 'Custom frequency range'];
const bandCheckbox = (page, label) => dialog(page).getByRole('checkbox', { name: label, exact: true });

function tuner(id, bandwidth) {
  return { id, name: id === 'idle-a' ? 'Small receiver' : 'Wide receiver', tuner_class: 'AIRSPY',
    status: 'ENABLED', available: true, operator_state: 'setup', channel_count: 0,
    frequency_hz: 773081250, sample_rate_hz: bandwidth, usable_bandwidth_hz: bandwidth,
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
    progress: { completed: 2, total: 4, current_frequency_hz: 773000000, checked: 1, total_signals: 4 }, candidates: state.empty ? [] : candidates,
    alias_groups: [{ group_id: 'county', wacn: 0xbee00, system: 0x348,
      alias_lists: state.ambiguous ? [{ id: 21, name: 'County Dispatch' }, { id: 22, name: 'County Operations' }] :
        [{ id: 21, name: 'County Dispatch' }], suggested_alias_list_id: null, default_new_alias_list_name: 'County P25' },
    { group_id: 'regional', wacn: 0xabc00, system: 0x234, alias_lists: [], suggested_alias_list_id: null,
      default_new_alias_list_name: 'Regional P25' }] };
}

async function install(page, state = {}) {
  state.requests = [];
  state.ledger = {};
  state.tuners = [tuner('idle-a', 2200000), tuner('idle-b', 9000000)];
  let leaseSequence = 0;
  const preferenceModule = await import(pathToFileURL(resolve(root, 'stats-web/assets/core/preference-schema.js')).href);
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
      revision: 1, preferences: { ...preferenceModule.defaults, appearance: { theme: state.theme || 'light' } } }) });
    if (path === '/api/v1/spectrum-snap-presets') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({
      revision: 1, country_code: 'US', country_label: 'United States', countries: [{ code: 'US', label: 'United States' }], scopes: [] }) });
    if (path === '/api/v1/admin/tuners') return respond({ tuners: state.tuners });
    if (path.endsWith('/browse')) {
      if (request.method() === 'DELETE') return respond(null, 204);
      const id = path.split('/').at(-2);
      const receiver = state.tuners.find((candidate) => candidate.id === id);
      return respond({ lease_id: body.lease_id || `lease-${++leaseSequence}`, tuner: receiver,
        expires_at_epoch_ms: Date.now() + 30000, can_tune: !receiver.channel_count });
    }
    if (path === '/api/v1/diagnostics/tuners') return respond({ rows: [] });
    if (path === `${searchPath}/catalog`) return respond({ tuners: state.tuners.map((receiver) => ({ ...receiver,
      eligible: !state.noIdle, reason: state.noIdle ? 'Channels are running' : null })), suggested_tuner_id: 'idle-b',
      presets: [{ id: 'vhf-high', label: 'VHF high · 138–174 MHz', ranges: [{ minimum_hz: 138000000, maximum_hz: 174000000 }] },
        { id: 'uhf', label: 'UHF · 406–470 MHz', ranges: [{ minimum_hz: 406000000, maximum_hz: 470000000 }] },
        { id: '700mhz', label: '700 MHz · 769–775 MHz', ranges: [{ minimum_hz: 769000000, maximum_hz: 775000000 }] },
        { id: '800mhz', label: '800 MHz · 851–869 MHz', ranges: [{ minimum_hz: 851000000, maximum_hz: 869000000 }] }],
      bounds: { maximum_ranges: 8, maximum_windows: 64, maximum_candidates: 32, maximum_total_hz: 150000000,
        minimum_dwell_ms: 750, maximum_dwell_ms: 5000, default_dwell_ms: 1500, maximum_scan_ms: 900000 } });
    if (path === searchPath && request.method() === 'POST') {
      if (state.invalidRequest) return fail(state.invalidRequest, 'invalid_request', 400);
      return respond(snapshot(state));
    }
    if (path === `${searchPath}/search-a`) {
      if (request.method() === 'DELETE') return respond(null, 204);
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
    if (path.endsWith('/start')) {
      for (const id of body.candidate_ids) if (state.ledger[id]?.saved) {
        state.ledger[id].running = id !== 'regional';
        if (id === 'regional') state.ledger[id].start_error = 'Outside this receiver window. Available to start later.';
      }
      state.tuners.find((receiver) => receiver.id === 'idle-b').channel_count = 2;
      return respond(snapshot(state));
    }
    return respond({});
  });
  await page.goto('/app.html?view=tuner-spectrum');
  if (state.adminTuners === false || state.adminChannels === false) {
    await expect(page.getByRole('heading', { name: state.adminTuners === false ? 'Access denied' : 'Tuner Spectrum', exact: true })).toBeVisible();
    return state;
  }
  await expect(page.getByRole('button', { name: 'Find P25 channels', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Find P25 channels', exact: true }).click();
  await expect(dialog(page).getByRole('heading', { name: 'Choose where to look' })).toBeVisible();
  return state;
}

async function complete(page) {
  await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(dialog(page).getByRole('heading', { name: 'Choose channels to add' })).toBeVisible();
}

test('uses one widest idle receiver, supports multiple ranges, and releases browsing before starting search', async ({ page }) => {
  const state = await install(page);
  await expect(dialog(page).getByLabel('Receiver', { exact: true })).toHaveValue('idle-b');
  await expect(page.locator('.spectrum-browse-tuner select')).toBeDisabled();
  await expect(dialog(page).getByRole('group', { name: 'Bands', exact: true }).getByRole('checkbox')).toHaveCount(5);
  for (const label of bandLabels) await expect(bandCheckbox(page, label)).toBeVisible();
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
  await complete(page);
  const create = state.requests.find((request) => request.path === searchPath && request.method === 'POST');
  expect(create.body).toEqual({ tuner_id: 'idle-b', browse_lease_id: 'lease-2', ranges: [
    { minimum_hz: 769000000, maximum_hz: 775000000 }, { minimum_hz: 851000000, maximum_hz: 869000000 }] });
  const release = state.requests.findIndex((request) => request.path === '/api/v1/admin/tuners/idle-a/browse' && request.method === 'DELETE');
  expect(release).toBeLessThan(state.requests.indexOf(create));
  await expect(dialog(page).getByRole('checkbox', { name: 'Select Saved East' })).toBeDisabled();
  await expect(dialog(page).getByRole('link', { name: 'Existing East Control' })).toHaveAttribute('href', /configuration_id=/);
  await expect(dialog(page)).toContainText('WACN BEE00 · System 348 · RFSS 2 · Site 27');
  await expect(dialog(page)).toContainText('snapshot from this search');
  await dialog(page).getByLabel('Search results', { exact: true }).fill('Regional Services');
  await expect(dialog(page).locator('tbody tr:visible')).toHaveCount(1);
  await dialog(page).getByLabel('Search results', { exact: true }).fill('840');
  await expect(dialog(page).locator('tbody tr:visible')).toHaveCount(3);
});

test('search requires both tuner and channel administration access', async ({ page }) => {
  const state = await install(page, { adminChannels: false });
  await expect(page.locator('.spectrum-browse-toolbar').getByText('Find P25 channels', { exact: true })).toBeHidden();
  expect(state.requests.some((request) => request.path.startsWith(searchPath))).toBe(false);
  const withoutTuners = await install(page, { adminTuners: false });
  await expect(page.getByRole('button', { name: 'Find P25 channels', exact: true })).toHaveCount(0);
  expect(withoutTuners.requests.some((request) => request.path.startsWith(searchPath))).toBe(false);
});

test('requires at least one independent band and preserves selected presets after restarting', async ({ page }) => {
  const state = await install(page);
  const find = dialog(page).getByRole('button', { name: 'Find signals', exact: true });
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
  await bandCheckbox(page, bandLabels[2]).uncheck();
  await bandCheckbox(page, bandLabels[3]).uncheck();
  await bandCheckbox(page, bandLabels[4]).check();
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
  await bandCheckbox(page, bandLabels[3]).press('Space');
  await bandCheckbox(page, bandLabels[0]).press('Space');
  await custom.press('Space');
  await expect(custom).toBeChecked();
  await expect(minimum).toBeVisible();
  await minimum.fill('482');
  await maximum.fill('484');
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

test('hides partial results, retries polling and discards the job before rebinding Spectrum', async ({ page }) => {
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
  await expect(dialog(page).getByRole('heading', { name: 'Choose channels to add' })).toBeVisible();
  await dialog(page).getByRole('button', { name: 'Close Find P25 channels' }).click();
  await expect(dialog(page)).toHaveCount(0);
  await expect(page.locator('.spectrum-browse-tuner select')).toBeEnabled();
  const jobDelete = state.requests.findIndex((request) => request.path === `${searchPath}/search-a` && request.method === 'DELETE');
  const browseDelete = state.requests.findIndex((request, index) => index > jobDelete && request.path === '/api/v1/admin/tuners/idle-b/browse' && request.method === 'DELETE');
  expect(jobDelete).toBeGreaterThan(-1);
  expect(browseDelete).toBeGreaterThan(jobDelete);
});

test('groups exact identities, separates startup from listening now, and retries only failed adds', async ({ page }) => {
  const state = await install(page, { failCandidateOnce: 'regional' });
  await complete(page);
  await dialog(page).getByRole('button', { name: 'Select all available' }).click();
  await dialog(page).getByRole('button', { name: 'Review selected' }).click();
  await expect(dialog(page).getByText('Use County Dispatch', { exact: true })).toHaveCount(1);
  await expect(dialog(page).getByLabel('New settings name for ABC00-234')).toHaveValue('Regional P25');
  await dialog(page).getByRole('checkbox', { name: 'Start automatically for 773.83125 MHz' }).press('Space');
  await expect(dialog(page).getByRole('checkbox', { name: 'Start automatically for 773.83125 MHz' })).not.toBeChecked();
  await dialog(page).getByRole('button', { name: 'Add selected channels' }).click();
  await expect(dialog(page).getByRole('heading', { name: 'Your channel results' })).toBeVisible();
  await expect(dialog(page).getByText('Added and listening', { exact: true })).toHaveCount(2);
  await expect(dialog(page).getByText('Could not add', { exact: true })).toHaveCount(1);
  const save = state.requests.find((request) => request.path.endsWith('/save'));
  expect(save.body.alias_groups).toEqual([{ group_id: 'county', alias_list_id: 21, new_alias_list_name: 'County P25' },
    { group_id: 'regional', alias_list_id: 0, new_alias_list_name: 'Regional P25' }]);
  expect(save.body.candidates.find((candidate) => candidate.candidate_id === 'central').auto_start).toBe(false);
  expect(state.requests.find((request) => request.path.endsWith('/start')).body).toEqual({ candidate_ids: ['north', 'central'], first_candidate_id: 'north' });
  await dialog(page).getByRole('button', { name: 'Retry adding Regional South' }).click();
  await expect(dialog(page).getByText('Added, available later', { exact: true })).toHaveCount(1);
  const saves = state.requests.filter((request) => request.path.endsWith('/save'));
  expect(saves).toHaveLength(2);
  expect(saves[1].body.candidates.map((candidate) => candidate.candidate_id)).toEqual(['regional']);
  expect(state.requests.filter((request) => request.path.endsWith('/start'))).toHaveLength(1);
});

test('a duplicate created during review becomes a link rather than an endless add retry', async ({ page }) => {
  const state = await install(page, { raceDuplicate: 'north' });
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  await dialog(page).getByRole('button', { name: 'Review selected' }).click();
  await dialog(page).getByRole('button', { name: 'Add selected channels' }).click();
  await expect(dialog(page).getByText('Already added', { exact: true })).toBeVisible();
  await expect(dialog(page).getByRole('link', { name: 'Concurrent saved control' })).toHaveAttribute('href', /configuration_id=/);
  await expect(dialog(page).getByRole('button', { name: /Retry adding/ })).toHaveCount(0);
  expect(state.requests.some((request) => request.path.endsWith('/start'))).toBe(false);
});

test('a committed channel with failed publication requires restart without another add or start', async ({ page }) => {
  const state = await install(page, { publicationFailure: true });
  await complete(page);
  await dialog(page).getByRole('button', { name: 'Select all available' }).click();
  await dialog(page).getByRole('button', { name: 'Review selected' }).click();
  await dialog(page).getByRole('button', { name: 'Add selected channels' }).click();
  await expect(dialog(page)).toContainText('Restart VCE before opening these channels');
  await expect(dialog(page).getByText('Added, available later', { exact: true })).toHaveCount(1);
  await expect(dialog(page).getByText('Not added', { exact: true })).toHaveCount(2);
  await expect(dialog(page).getByRole('link', { name: 'Open channel' })).toHaveCount(0);
  await expect(dialog(page).getByRole('button', { name: /Retry|Review choices|Add selected/ })).toHaveCount(0);
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(1);
  expect(state.requests.some((request) => request.path.endsWith('/start'))).toBe(false);
});

test('a definitive saved-restart error locks the flow even if ledger recovery fails', async ({ page }) => {
  const state = await install(page, { publicationFailure: true, failLedgerOnce: true });
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  await dialog(page).getByRole('button', { name: 'Review selected' }).click();
  await dialog(page).getByRole('button', { name: 'Add selected channels' }).click();
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

test('failed-row review edits only unsaved channels and preserves successful listening', async ({ page }) => {
  const state = await install(page, { failCandidateOnce: 'regional' });
  await complete(page);
  await dialog(page).getByRole('button', { name: 'Select all available' }).click();
  await dialog(page).getByRole('button', { name: 'Review selected' }).click();
  await dialog(page).getByRole('button', { name: 'Add selected channels' }).click();
  await dialog(page).getByRole('button', { name: 'Review choices', exact: true }).click();
  await expect(dialog(page).getByRole('textbox', { name: /Channel name for/ })).toHaveCount(1);
  await expect(dialog(page).getByRole('radio', { name: /Listen first/ })).toHaveCount(0);
  await expect(dialog(page).getByText('Added and listening', { exact: true })).toHaveCount(2);
  await dialog(page).getByLabel('Channel name for 860.0125 MHz').fill('My South Control');
  await dialog(page).getByLabel('New settings name for ABC00-234').fill('Regional listening');
  await dialog(page).getByRole('button', { name: 'Add selected channels' }).click();
  await expect(dialog(page).getByText('Added and listening', { exact: true })).toHaveCount(2);
  await expect(dialog(page).getByText('Added, available later', { exact: true })).toHaveCount(1);
  const saves = state.requests.filter((request) => request.path.endsWith('/save'));
  expect(saves.at(-1).body.candidates).toEqual([{ candidate_id: 'regional', name: 'My South Control', auto_start: true }]);
  expect(state.requests.filter((request) => request.path.endsWith('/start'))).toHaveLength(1);
});

test('listen later survives review navigation and never sends a start request', async ({ page }) => {
  const state = await install(page);
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  await dialog(page).getByRole('button', { name: 'Review selected' }).click();
  await expect(dialog(page)).toContainText('Start this channel when VCE starts.');
  await dialog(page).getByRole('radio', { name: 'Listen later', exact: true }).check();
  await expect(dialog(page)).toContainText('Start automatically still applies when VCE starts.');
  await dialog(page).getByRole('button', { name: 'Back', exact: true }).click();
  await dialog(page).getByRole('button', { name: 'Review selected' }).click();
  await expect(dialog(page).getByRole('radio', { name: 'Listen later', exact: true })).toBeChecked();
  await dialog(page).getByRole('button', { name: 'Add selected channels' }).click();
  await expect(dialog(page)).toContainText('Added, available later');
  expect(state.requests.some((request) => request.path.endsWith('/start'))).toBe(false);
});

test('requires a choice for ambiguous alias groups and preserves drafts after stale revision', async ({ page }) => {
  const state = await install(page, { ambiguous: true, staleOnce: true });
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  await dialog(page).getByRole('button', { name: 'Review selected' }).click();
  await dialog(page).getByRole('button', { name: 'Add selected channels' }).click();
  expect(state.requests.some((request) => request.path.endsWith('/save'))).toBe(false);
  await dialog(page).getByLabel('Listening settings for BEE00-348').selectOption('22');
  await dialog(page).getByLabel('Channel name for 773.08125 MHz').fill('My North Control');
  await dialog(page).getByRole('button', { name: 'Add selected channels' }).click();
  await expect(dialog(page)).toContainText('Listening settings changed');
  await expect(dialog(page).getByLabel('Channel name for 773.08125 MHz')).toHaveValue('My North Control');
  await expect(dialog(page).getByLabel('Listening settings for BEE00-348')).toHaveValue('22');
  await dialog(page).getByRole('button', { name: 'Add selected channels' }).click();
  await expect(dialog(page)).toContainText('Added and listening');
  expect(state.requests.filter((request) => request.path.endsWith('/save')).at(-1).body.revision).toBe(8);
});

test('an expired search requires fresh evidence and does not save stale choices', async ({ page }) => {
  const state = await install(page);
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  await dialog(page).getByRole('button', { name: 'Review selected' }).click();
  state.expired = true;
  await dialog(page).getByRole('button', { name: 'Add selected channels' }).click();
  await expect(dialog(page).getByRole('heading', { name: 'This search expired' })).toBeVisible();
  await dialog(page).getByRole('button', { name: 'Start a new search' }).click();
  await expect(dialog(page).getByRole('heading', { name: 'Choose where to look' })).toBeVisible();
});

test('renews the browse lease while a save is pending', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { delaySave: true });
  await complete(page);
  await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
  await dialog(page).getByRole('button', { name: 'Review selected' }).click();
  await dialog(page).getByRole('button', { name: 'Add selected channels' }).click();
  await expect.poll(() => Boolean(state.releaseSave)).toBe(true);
  await page.clock.fastForward(11000);
  await expect.poll(() => state.requests.some((request) => request.path === '/api/v1/admin/tuners/idle-b/browse' && Boolean(request.body.lease_id))).toBe(true);
  state.releaseSave();
  await expect(dialog(page)).toContainText('Added and listening');
});

test('no idle receiver, failed search and empty results provide an actionable next step', async ({ page }) => {
  const state = await install(page, { noIdle: true });
  await expect(dialog(page)).toContainText('An idle receiver is needed');
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
  await expect(dialog(page)).toContainText('No P25 control channels were identified');
  await expect(dialog(page).getByRole('button', { name: 'Review selected' })).toBeDisabled();
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
    await expect(receiver.locator('option')).toHaveText(['Small receiver · 2.20 MHz window', 'Wide receiver · 9.00 MHz window']);
    await receiver.click();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-receiver-control-${theme}-${width}.png`);
    await receiver.press('Enter');
    await expect(dialog(page).getByRole('group', { name: 'Bands', exact: true }).getByRole('checkbox')).toHaveCount(5);
    for (const label of bandLabels) await expect(bandCheckbox(page, label)).toBeVisible();
    await bandCheckbox(page, bandLabels[2]).focus();
    await expect(bandCheckbox(page, bandLabels[2])).toBeFocused();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-band-selection-${theme}-${width}.png`);
    await dialog(page).getByRole('button', { name: 'Find signals', exact: true }).click();
    await expect(dialog(page).getByRole('heading', { name: 'Find signals', exact: true })).toBeVisible();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-scanning-${theme}-${width}.png`);
    state.phase = 'checking';
    await expect(dialog(page).getByRole('heading', { name: 'Check signals', exact: true })).toBeVisible();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-checking-${theme}-${width}.png`);
    state.phase = 'complete';
    await expect(dialog(page).getByRole('heading', { name: 'Choose channels to add' })).toBeVisible();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-results-${theme}-${width}.png`);
    const details = dialog(page).locator('tbody tr').first().getByText('Signal details', { exact: true });
    await details.click();
    await details.scrollIntoViewIfNeeded();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-signal-details-${theme}-${width}.png`);
    await details.click();
    await dialog(page).getByRole('button', { name: 'Select all available' }).click();
    await dialog(page).getByRole('button', { name: 'Review selected' }).click();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-review-${theme}-${width}.png`);
    await dialog(page).getByRole('checkbox', { name: 'Start automatically for 773.08125 MHz' }).scrollIntoViewIfNeeded();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-review-listening-${theme}-${width}.png`);
    await dialog(page).getByRole('radio', { name: 'Listen later', exact: true }).scrollIntoViewIfNeeded();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-review-later-${theme}-${width}.png`);
    const geometry = await dialog(page).evaluate((element) => ({ width: document.documentElement.scrollWidth,
      bottom: element.querySelector('.spectrum-discovery-actions').getBoundingClientRect().bottom,
      scroll: element.querySelector('.spectrum-discovery-stage').scrollHeight,
      client: element.querySelector('.spectrum-discovery-stage').clientHeight }));
    expect(geometry.width).toBeLessThanOrEqual(width);
    expect(geometry.bottom).toBeLessThanOrEqual(900);
    expect(geometry.scroll).toBeGreaterThan(geometry.client);
    await dialog(page).getByRole('button', { name: 'Add selected channels' }).focus();
    await expect(dialog(page).getByRole('button', { name: 'Add selected channels' })).toBeFocused();
    await dialog(page).getByRole('button', { name: 'Add selected channels' }).click();
    await expect(dialog(page).getByRole('heading', { name: 'Your channel results' })).toBeVisible();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-saved-${theme}-${width}.png`);
  });
  test(`alias choices and restart notice remain readable in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const state = await install(page, { theme, ambiguous: true, publicationFailure: true });
    await complete(page);
    await dialog(page).getByRole('checkbox', { name: 'Select County North', exact: true }).check();
    await dialog(page).getByRole('button', { name: 'Review selected' }).click();
    const alias = dialog(page).getByLabel('Listening settings for BEE00-348');
    await expect(alias.locator('option')).toHaveText(['Choose listening settings', 'County Dispatch', 'County Operations']);
    await alias.click();
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-alias-control-${theme}-${width}.png`);
    await alias.press('Enter');
    await alias.selectOption('22');
    await expect(alias).toHaveValue('22');
    await dialog(page).getByRole('button', { name: 'Add selected channels' }).click();
    await expect(dialog(page)).toContainText('Restart VCE before opening these channels');
    await expect(dialog(page)).toHaveScreenshot(`spectrum-search-restart-${theme}-${width}.png`);
    expect(state.requests.some((request) => request.path.endsWith('/start'))).toBe(false);
  });
}
