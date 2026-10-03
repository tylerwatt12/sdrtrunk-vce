const { expect, test } = require('@playwright/test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');

const root = resolve(__dirname, '../../../../..');
const browsePath = '/api/v1/admin/tuners/active-a/browse';
const searchPath = '/api/v1/admin/spectrum-search';
const searchDialog = (page) => page.locator('.spectrum-search-modal');

function activeTuner() {
  return {
    id: 'active-a', name: 'Dispatch receiver', tuner_class: 'AIRSPY', status: 'ENABLED', available: true,
    operator_state: 'live', channel_count: 2, frequency_hz: 773081250, spectrum_target_id: 'target-active-a',
    sample_rate_hz: 10000000, usable_bandwidth_hz: 9000000,
    minimum_frequency_hz: 24000000, maximum_frequency_hz: 1800000000,
    settings: [
      { id: 'frequency_mhz', label: 'Center frequency', value: 773.08125, editable: true,
        availability: 'setup', minimum: 24, maximum: 1800,
        dependencies: [{ setting_id: 'center_frequency_locked', equals: false }] },
      { id: 'center_frequency_locked', label: 'Lock center', kind: 'boolean', value: true,
        editable: true, availability: 'live', dependencies: [] }
    ]
  };
}

function preparedTuner() {
  const tuner = activeTuner();
  tuner.operator_state = 'setup';
  tuner.channel_count = 0;
  tuner.settings = tuner.settings.map((setting) => setting.id === 'center_frequency_locked' ?
    { ...setting, value: false } : setting);
  return tuner;
}

function catalogTuner() {
  return { ...activeTuner(), eligible: false, reason: '2 channels are running' };
}

function catalog() {
  return {
    tuners: [catalogTuner()], suggested_tuner_id: null,
    presets: [
      { id: '700mhz', label: '700 MHz · 769–775 MHz',
        ranges: [{ minimum_hz: 769000000, maximum_hz: 775000000 }] },
      { id: '800mhz', label: '800 MHz · 851–869 MHz',
        ranges: [{ minimum_hz: 851000000, maximum_hz: 869000000 }] }
    ],
    bounds: { maximum_ranges: 8, maximum_windows: 64, maximum_candidates: 32,
      maximum_total_hz: 150000000, minimum_dwell_ms: 750, maximum_dwell_ms: 5000,
      default_dwell_ms: 1500, maximum_scan_ms: 900000 }
  };
}

function pairedTuners() {
  const primary = { ...activeTuner(), name: 'Primary receiver', channel_count: 0,
    device_group: { id: 'receiver-pair', kind: 'rsp_duo', role: 'tuner_1' } };
  const partner = { ...activeTuner(), id: 'active-b', name: 'Partner receiver',
    spectrum_target_id: 'target-active-b',
    device_group: { id: 'receiver-pair', kind: 'rsp_duo', role: 'tuner_2' } };
  return [primary, partner];
}

function searchSnapshot() {
  return {
    job_id: 'search-active-a', phase: 'scanning', revision: 1,
    expires_at_ms: Date.now() + 60000,
    progress: { completed: 0, total: 3, current_frequency_hz: 769000000,
      checked: 0, total_signals: 0 },
    candidates: [], alias_groups: []
  };
}

async function install(page, options = {}) {
  const state = { requests: [], takeoverSequence: 0, monitorSequence: 0 };
  const inventory = options.paired ? pairedTuners() : [activeTuner()];
  const preferenceModule = await import(pathToFileURL(resolve(root,
    'stats-web/assets/core/preference-schema.js')).href);

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const body = JSON.parse(request.postData() || '{}');
    const method = request.method();
    state.requests.push({ path, method, body });
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json',
      body: JSON.stringify({ data }) });
    const fail = (message, code = 'unavailable', status = 503) => route.fulfill({ status,
      contentType: 'application/json', body: JSON.stringify({ error: { message, code } }) });

    if (path === '/api/v1/auth/session') return respond({ configured: true, authenticated: true,
      tier: 'admin', username: 'admin', csrf_token: 'test-token', capabilities: {
        'web-access': true, dashboard: true, 'tuner-spectrum': true, 'admin-tuners': true,
        'admin-channels': true, 'admin-aliases': true
      } });
    if (path === '/api/v1/me/preferences') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, preferences: { ...preferenceModule.defaults,
        appearance: { theme: 'light', hue: null } } }) });
    if (path === '/api/v1/spectrum-snap-presets') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, country_code: 'US', country_label: 'United States',
        countries: [{ code: 'US', label: 'United States' }], scopes: [] }) });
    if (path === '/api/v1/admin/channels') return respond({ revision: 1, channels: [] });
    if (path === '/api/v1/admin/channels/protocols') return respond({ profiles: [] });
    if (path === '/api/v1/admin/channels/options') return respond({ alias_lists: [] });
    if (path === '/api/v1/admin/tuners') return respond({ tuners: inventory });
    if (path === browsePath) {
      if (method === 'DELETE') return respond(null, 204);
      if (body.takeover === true) {
        const leaseId = `takeover-${++state.takeoverSequence}`;
        const prepared = options.paired ? { ...inventory[0], operator_state: 'setup',
          settings: inventory[0].settings.map((setting) => setting.id === 'center_frequency_locked' ?
            { ...setting, value: false } : setting) } : preparedTuner();
        return respond({ lease_id: leaseId, expires_at_epoch_ms: Date.now() + 30000,
          takeover: true, can_tune: true, stopped_channels: [{ configuration_id: 'channel-a' }],
          tuner: prepared });
      }
      if (String(body.lease_id || '').startsWith('takeover-')) {
        return respond({ lease_id: body.lease_id, expires_at_epoch_ms: Date.now() + 30000,
          takeover: true, can_tune: true, stopped_channels: [{ configuration_id: 'channel-a' }],
          tuner: preparedTuner() });
      }
      if (options.paired && !body.lease_id)
        return fail('Channels are using this tuner\'s paired hardware', 'tuner_browse_unavailable', 409);
      return respond({ lease_id: `monitor-${++state.monitorSequence}`,
        expires_at_epoch_ms: Date.now() + 30000, takeover: false, can_tune: false,
        tuner: activeTuner() });
    }
    if (path === '/api/v1/diagnostics/tuners') return respond({ rows: [] });
    if (path === `${searchPath}/catalog`) return respond(catalog());
    if (path === searchPath && method === 'POST') return respond(searchSnapshot());
    if (path === `${searchPath}/search-active-a`) {
      if (method === 'DELETE') return respond(null, 204);
      return respond(searchSnapshot());
    }
    return respond({});
  });

  await page.goto('/app.html?view=tuner-spectrum');
  await expect(page.getByRole('heading', { name: 'Tuner Spectrum', exact: true })).toBeVisible();
  await expect.poll(() => state.requests.some((request) => request.path === browsePath &&
    request.method === 'POST' && Object.keys(request.body).length === 0)).toBe(true);
  return state;
}

async function confirmStopChannels(page, purpose = 'tune') {
  const search = purpose === 'search';
  const warning = page.getByRole('alertdialog', {
    name: search ? 'Stop channels for this search?' : 'Stop channels to tune?'
  });
  await expect(warning).toBeVisible();
  await expect(warning).toContainText('end any calls in progress');
  await expect(warning.getByRole('button', { name: 'Cancel', exact: true })).toBeFocused();
  await warning.getByRole('button', {
    name: search ? 'Stop channels and search' : 'Stop channels and tune', exact: true
  }).click();
}

test('Spectrum can temporarily stop an active locked tuner and resume its channels', async ({ page }) => {
  const state = await install(page);
  const stop = page.getByRole('button', { name: 'Stop channels to tune', exact: true });
  await expect(stop).toBeVisible();
  await expect(stop).toHaveClass(/ui-icon-button/);
  await expect(stop).toHaveText('');

  await stop.click();
  await confirmStopChannels(page);

  const resume = page.getByRole('button', { name: 'Resume channels', exact: true });
  await expect(resume).toBeVisible();
  await expect(resume).toHaveClass(/ui-icon-button/);
  await expect(resume).toHaveText('');
  const takeover = state.requests.find((request) => request.path === browsePath &&
    request.method === 'POST' && request.body.takeover === true);
  expect(takeover).toBeTruthy();

  const requestCount = state.requests.length;
  await resume.click();
  await expect(stop).toBeVisible();
  await expect.poll(() => state.requests.length).toBeGreaterThan(requestCount);

  const releaseIndex = state.requests.findIndex((request, index) => index >= requestCount &&
    request.path === browsePath && request.method === 'DELETE' && request.body.lease_id === 'takeover-1');
  const reacquireIndex = state.requests.findIndex((request, index) => index > releaseIndex &&
    request.path === browsePath && request.method === 'POST' && Object.keys(request.body).length === 0);
  expect(releaseIndex).toBeGreaterThanOrEqual(requestCount);
  expect(reacquireIndex).toBeGreaterThan(releaseIndex);
});

test('an idle member can stop channels using its paired receiver', async ({ page }) => {
  const state = await install(page, { paired: true });
  await expect(page.locator('.spectrum-browse-tuner option').first()).toContainText('2 active in pair');
  const stop = page.getByRole('button', { name: 'Stop channels to tune', exact: true });
  await expect(stop).toBeVisible();

  await stop.click();
  const warning = page.getByRole('alertdialog', { name: 'Stop channels to tune?' });
  await expect(warning).toContainText('Primary receiver is running 2 active channels');
  await expect(warning).toContainText('those calls will not resume');
  await expect(warning).toContainText('restart the same configured channels');
  await warning.getByRole('button', { name: 'Stop channels and tune', exact: true }).click();

  await expect(page.getByRole('button', { name: 'Resume channels', exact: true })).toBeVisible();
  await expect(page.locator('.spectrum-browse-tuner option')).toHaveText([
    /Primary receiver · Idle/, /Partner receiver · Idle/
  ]);
  expect(state.requests.some((request) => request.path === browsePath && request.method === 'POST' &&
    request.body.takeover === true)).toBe(true);

  await page.getByRole('button', { name: 'Resume channels', exact: true }).click();
  await expect(page.locator('.spectrum-browse-tuner option').first()).toContainText('2 active in pair');
});

test('Find Trunked Systems confirms a blocked receiver, reuses its prepared lease, and releases it on close', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=channel-setup');
  await page.getByRole('button', { name: 'Find Trunked Systems', exact: true }).click();
  const dialog = searchDialog(page);
  await expect(dialog.getByRole('heading', { name: 'Choose a receiver', exact: true })).toBeVisible();

  const prepare = dialog.getByRole('button', { name: 'Stop channels and use: Dispatch receiver', exact: true });
  await expect(prepare).toBeVisible();
  await prepare.click();
  await confirmStopChannels(page, 'search');

  await expect(dialog.getByRole('button', { name: 'Find signals', exact: true })).toBeEnabled();
  await dialog.getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(dialog.getByRole('heading', { name: 'Find signals', exact: true })).toBeVisible();

  const takeoverIndex = state.requests.findIndex((request) => request.path === browsePath &&
    request.method === 'POST' && request.body.takeover === true);
  const reuseIndex = state.requests.findIndex((request, index) => index > takeoverIndex &&
    request.path === browsePath && request.method === 'POST' && request.body.lease_id === 'takeover-1');
  const createIndex = state.requests.findIndex((request, index) => index > reuseIndex &&
    request.path === searchPath && request.method === 'POST');
  expect(takeoverIndex).toBeGreaterThanOrEqual(0);
  expect(reuseIndex).toBeGreaterThan(takeoverIndex);
  expect(createIndex).toBeGreaterThan(reuseIndex);
  expect(state.requests[createIndex].body.browse_lease_id).toBe('takeover-1');

  await dialog.getByRole('button', { name: 'Close Find Trunked Systems', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect.poll(() => state.requests.some((request) => request.path === browsePath &&
    request.method === 'DELETE' && request.body.lease_id === 'takeover-1')).toBe(true);
});

test('canceling Find Trunked Systems after preparing a receiver releases its takeover lease', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=channel-setup');
  await page.getByRole('button', { name: 'Find Trunked Systems', exact: true }).click();
  const dialog = searchDialog(page);
  await expect(dialog.getByRole('button', { name: 'Stop channels and use: Dispatch receiver', exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Stop channels and use: Dispatch receiver', exact: true }).click();
  await confirmStopChannels(page, 'search');
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeVisible();

  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect.poll(() => state.requests.some((request) => request.path === browsePath &&
    request.method === 'DELETE' && request.body.lease_id === 'takeover-1')).toBe(true);
  expect(state.requests.some((request) => request.path === searchPath && request.method === 'POST')).toBe(false);
});
