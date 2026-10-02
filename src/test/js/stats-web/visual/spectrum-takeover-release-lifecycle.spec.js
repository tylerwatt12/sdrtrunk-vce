const { expect, test } = require('@playwright/test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');

const root = resolve(__dirname, '../../../../..');
const browsePath = (id) => `/api/v1/admin/tuners/${id}/browse`;

function tuner(id, { active = false } = {}) {
  return {
    id, name: active ? 'Dispatch receiver' : 'Spare receiver', tuner_class: 'AIRSPY', status: 'ENABLED',
    available: true, operator_state: 'live', channel_count: active ? 2 : 0,
    frequency_hz: active ? 773081250 : 851012500, spectrum_target_id: `target-${id}`,
    sample_rate_hz: 10000000, usable_bandwidth_hz: 9000000,
    minimum_frequency_hz: 24000000, maximum_frequency_hz: 1800000000,
    settings: [
      { id: 'frequency_mhz', label: 'Center frequency', value: active ? 773.08125 : 851.0125,
        editable: true, availability: 'setup', minimum: 24, maximum: 1800,
        dependencies: [{ setting_id: 'center_frequency_locked', equals: false }] },
      { id: 'center_frequency_locked', label: 'Lock center', kind: 'boolean', value: active,
        editable: true, availability: 'live', dependencies: [] }
    ]
  };
}

function preparedTuner() {
  const value = tuner('active-a', { active: true });
  value.operator_state = 'setup';
  value.channel_count = 0;
  value.settings = value.settings.map((setting) => setting.id === 'center_frequency_locked' ?
    { ...setting, value: false } : setting);
  return value;
}

async function install(page) {
  const state = {
    requests: [], takeoverSequence: 0, monitorSequence: 0,
    failNextDelete: false, delayNextDelete: false, releaseDelete: null, failNextRenewal: null
  };
  const preferenceModule = await import(pathToFileURL(resolve(root,
    'stats-web/assets/core/preference-schema.js')).href);

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    const body = JSON.parse(request.postData() || '{}');
    state.requests.push({ path, method, body });
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json',
      body: JSON.stringify({ data }) });
    const fail = (message, code, status) => route.fulfill({ status, contentType: 'application/json',
      body: JSON.stringify({ error: { message, code } }) });

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
    if (path === '/api/v1/admin/tuners') return respond({ tuners: [
      tuner('active-a', { active: true }), tuner('idle-b')
    ] });
    if (path === '/api/v1/diagnostics/tuners') return respond({ rows: [] });
    if (path === '/api/v1/admin/spectrum-search/catalog') return respond({
      tuners: [tuner('active-a', { active: true }), tuner('idle-b')], suggested_tuner_id: 'idle-b',
      presets: [
        { id: '700mhz', label: '700 MHz · 769–775 MHz',
          ranges: [{ minimum_hz: 769000000, maximum_hz: 775000000 }] },
        { id: '800mhz', label: '800 MHz · 851–869 MHz',
          ranges: [{ minimum_hz: 851000000, maximum_hz: 869000000 }] }
      ],
      bounds: { maximum_ranges: 8, maximum_windows: 64, maximum_candidates: 32,
        maximum_total_hz: 150000000, minimum_dwell_ms: 750, maximum_dwell_ms: 5000,
        default_dwell_ms: 1500, maximum_scan_ms: 900000 }
    });
    if (path === '/api/v1/admin/spectrum-search' && method === 'POST') return respond({
      job_id: 'search-a', phase: 'complete', revision: 1, reason: null, restart_required: false,
      progress: { completed: 1, total: 1 }, candidates: [], alias_groups: []
    });
    if (path === browsePath('active-a') || path === browsePath('idle-b')) {
      const id = path.includes('active-a') ? 'active-a' : 'idle-b';
      if (method === 'DELETE') {
        if (state.failNextDelete) {
          state.failNextDelete = false;
          return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({
            error: { code: 'release_failed', message: 'Receiver release failed' }
          }) });
        }
        if (state.delayNextDelete) {
          state.delayNextDelete = false;
          await new Promise((resolveDelete) => { state.releaseDelete = resolveDelete; });
          state.releaseDelete = null;
        }
        return respond({ released: true });
      }
      if (body.takeover === true) {
        const leaseId = `takeover-${++state.takeoverSequence}`;
        return respond({ lease_id: leaseId, expires_at_epoch_ms: Date.now() + 30000,
          takeover: true, can_tune: true, stopped_channels: [{ id: 'channel-a', name: 'County control' }],
          tuner: preparedTuner() });
      }
      if (String(body.lease_id || '').startsWith('takeover-')) {
        if (state.failNextRenewal) {
          const failure = state.failNextRenewal;
          state.failNextRenewal = null;
          return fail(failure.message, failure.code, failure.status);
        }
        return respond({ lease_id: body.lease_id, expires_at_epoch_ms: Date.now() + 30000,
          takeover: true, can_tune: true, stopped_channels: [{ id: 'channel-a', name: 'County control' }],
          tuner: preparedTuner() });
      }
      const value = id === 'active-a' ? tuner(id, { active: true }) : tuner(id);
      return respond({ lease_id: `monitor-${++state.monitorSequence}`,
        expires_at_epoch_ms: Date.now() + 30000, takeover: false,
        can_tune: id === 'idle-b', stopped_channels: [], tuner: value });
    }
    return respond({});
  });

  await page.goto('/app.html?view=tuner-spectrum');
  await expect(page.getByRole('heading', { name: 'Tuner Spectrum', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Stop channels to tune', exact: true })).toBeVisible();
  return state;
}

async function takeControl(page) {
  await page.getByRole('button', { name: 'Stop channels to tune', exact: true }).click();
  const warning = page.getByRole('alertdialog', { name: 'Stop channels to tune?' });
  await expect(warning).toBeVisible();
  await warning.getByRole('button', { name: 'Stop channels and tune', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Resume channels', exact: true })).toBeVisible();
}

test('failed Resume keeps takeover ownership and renews its lease', async ({ page }) => {
  await page.clock.install();
  const state = await install(page);
  await takeControl(page);
  state.failNextDelete = true;

  await page.getByRole('button', { name: 'Resume channels', exact: true }).click();
  await expect(page.getByText('Receiver release failed', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Resume channels', exact: true })).toBeEnabled();
  const renewals = () => state.requests.filter((request) => request.path === browsePath('active-a') &&
    request.method === 'POST' && request.body.lease_id === 'takeover-1').length;
  const before = renewals();

  await page.clock.fastForward(10_100);
  await expect.poll(renewals).toBeGreaterThan(before);
});

test('failed tuner switch retains the takeover and does not browse the new receiver', async ({ page }) => {
  const state = await install(page);
  await takeControl(page);
  state.failNextDelete = true;
  const secondReceiverPosts = () => state.requests.filter((request) =>
    request.path === browsePath('idle-b') && request.method === 'POST').length;

  await page.getByLabel('Tuner', { exact: true }).selectOption('idle-b');

  await expect(page.getByText('Receiver release failed', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Tuner', { exact: true })).toHaveValue('active-a');
  await expect(page.getByRole('button', { name: 'Resume channels', exact: true })).toBeVisible();
  expect(secondReceiverPosts()).toBe(0);
});

test('Resume locks receiver choices until release and reacquisition finish', async ({ page }) => {
  const state = await install(page);
  await takeControl(page);
  state.delayNextDelete = true;

  await page.getByRole('button', { name: 'Resume channels', exact: true }).click();
  await expect.poll(() => typeof state.releaseDelete).toBe('function');
  await expect(page.getByLabel('Tuner', { exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Find P25 channels', exact: true })).toBeDisabled();

  state.releaseDelete();
  await expect(page.getByRole('button', { name: 'Stop channels to tune', exact: true })).toBeVisible();
  await expect(page.getByLabel('Tuner', { exact: true })).toBeEnabled();
  const releaseIndex = state.requests.findIndex((request) => request.path === browsePath('active-a') &&
    request.method === 'DELETE' && request.body.lease_id === 'takeover-1');
  const reacquireIndex = state.requests.findIndex((request, index) => index > releaseIndex &&
    request.path === browsePath('active-a') && request.method === 'POST' && !request.body.lease_id &&
    request.body.takeover !== true);
  expect(releaseIndex).toBeGreaterThanOrEqual(0);
  expect(reacquireIndex).toBeGreaterThan(releaseIndex);
});

test('temporary renewal failure keeps takeover ownership and retries automatically', async ({ page }) => {
  await page.clock.install();
  const state = await install(page);
  await takeControl(page);
  state.failNextRenewal = { status: 503, code: 'tuner_browse_failed', message: 'Renewal interrupted' };
  const renewals = () => state.requests.filter((request) => request.path === browsePath('active-a') &&
    request.method === 'POST' && request.body.lease_id === 'takeover-1').length;

  await page.clock.fastForward(10_100);
  await expect(page.getByText('Connection interrupted. Your stopped channels are still under Spectrum control; retrying automatically.',
    { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Resume channels', exact: true })).toBeVisible();
  const afterFailure = renewals();

  await page.clock.fastForward(10_100);
  await expect.poll(renewals).toBeGreaterThan(afterFailure);
  await expect(page.getByRole('button', { name: 'Resume channels', exact: true })).toBeVisible();
  await expect(page.getByText(/1 active channel stopped while you tune/)).toBeVisible();
  await expect(page.getByText(/Calls that were in progress will not resume/)).toBeVisible();
});

test('confirmed renewal ownership conflict clears the expired takeover', async ({ page }) => {
  await page.clock.install();
  const state = await install(page);
  await takeControl(page);
  state.failNextRenewal = { status: 409, code: 'tuner_browse_unavailable',
    message: 'Spectrum tuner session expired; reopen Spectrum' };

  await page.clock.fastForward(10_100);
  await expect(page.getByText('Spectrum tuner session expired; reopen Spectrum', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Resume channels', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Retry browsing', exact: true })).toBeVisible();
});

test('Find P25 reuses an existing takeover without resuming and stopping channels again', async ({ page }) => {
  const state = await install(page);
  await takeControl(page);
  const requestStart = state.requests.length;

  await page.getByRole('button', { name: 'Find P25 channels', exact: true }).click();
  const search = page.locator('.spectrum-search-modal');
  await expect(search.getByRole('heading', { name: 'Choose where to look' })).toBeVisible();
  await expect(search.getByLabel('Receiver', { exact: true })).toHaveValue('active-a');
  await search.getByRole('button', { name: 'Find signals', exact: true }).click();
  await expect(search.getByRole('heading', { name: 'Choose channels to add' })).toBeVisible();

  const requests = state.requests.slice(requestStart);
  expect(requests.some((request) => request.path === browsePath('active-a') &&
    request.method === 'DELETE' && request.body.lease_id === 'takeover-1')).toBe(false);
  expect(requests.some((request) => request.path === browsePath('active-a') &&
    request.method === 'POST' && request.body.takeover === true)).toBe(false);
  expect(requests.some((request) => request.path === browsePath('active-a') &&
    request.method === 'POST' && request.body.lease_id === 'takeover-1')).toBe(true);
  const create = requests.find((request) => request.path === '/api/v1/admin/spectrum-search' &&
    request.method === 'POST');
  expect(create.body.browse_lease_id).toBe('takeover-1');
});
