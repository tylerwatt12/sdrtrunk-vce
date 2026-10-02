const { expect, test } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');

const root = resolve(__dirname, '../../../../..');
const app = readFileSync(resolve(root, 'stats-web/assets/app.js'), 'utf8');
const protocols = require(resolve(root, 'src/main/resources/channel-protocols.json'));
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

function discoverySnapshot(frequencyHz = 773081250) {
  const profile = protocols.profiles.find((candidate) => candidate.id === 'p25-phase1');
  const settings = Object.fromEntries(profile.sections.flatMap((section) => section.fields)
    .filter((field) => field.path.startsWith('settings.') && Object.hasOwn(field, 'default'))
    .map((field) => [field.path.substring(9), field.default]));
  return {
    session_id: 'discovery-a', state: 'ready', reason: null, protocol_id: 'p25-phase1',
    tuner_id: 'active-a', target_id: 'target-active-a', frequency_hz: frequencyHz,
    expires_at_ms: Date.now() + 30000,
    probe: {
      c4fm: { valid_messages: 18, valid_control_messages: 12, invalid_control_messages: 4, quality_pct: 81.2 },
      cqpsk: { valid_messages: 52, valid_control_messages: 41, invalid_control_messages: 1, quality_pct: 99.1 },
      elapsed_ms: 1500, timeout_ms: 15000, selected_modulation: 'CQPSK',
      identity: { wacn: 0xb0001, system: 0x123, rfss: 1, site: 2 }
    },
    review: {
      revision: 7,
      template: { protocol_id: 'p25-phase1', name: 'Control', system: 'P25 B0001-123',
        site: 'RFSS 1 Site 2', settings },
      alias_lists: [], suggested_alias_list_id: null, default_new_alias_list_name: 'P25 B0001-123'
    },
    saved: null
  };
}

async function installLiveSpectrumStream(page) {
  await page.addInitScript(({ centerFrequencyHz, sampleRateHz }) => {
    const nativeFetch = window.fetch.bind(window);
    const encoder = new TextEncoder();
    window.spectrumStreamLifecycle = { opened: 0, cancelled: 0 };

    const multiplexFrame = (topic, kind, payload) => {
      const frame = new Uint8Array(16 + payload.byteLength);
      const header = new DataView(frame.buffer);
      header.setUint32(0, 0x534c4d58);
      header.setUint8(4, 2);
      header.setUint8(5, kind);
      header.setUint16(6, topic);
      header.setUint32(8, payload.byteLength);
      frame.set(payload, 16);
      return frame;
    };
    const tunerStateFrame = () => {
      const payload = encoder.encode(JSON.stringify({
        stream_state: 'live', center_frequency_hz: centerFrequencyHz,
        sample_rate_hz: sampleRateHz, profile: 'balanced'
      }));
      const frame = new Uint8Array(64 + payload.byteLength);
      const header = new DataView(frame.buffer);
      header.setUint32(0, 0x53444447, true);
      header.setUint8(4, 1);
      header.setUint8(5, 1);
      header.setUint16(6, 64, true);
      header.setUint32(8, payload.byteLength, true);
      header.setBigInt64(16, BigInt(window.spectrumStreamLifecycle.opened), true);
      header.setBigInt64(24, 1n, true);
      header.setBigInt64(32, BigInt(Date.now()), true);
      header.setBigInt64(40, BigInt(Date.now()), true);
      header.setBigInt64(48, BigInt(centerFrequencyHz), true);
      header.setInt32(56, sampleRateHz, true);
      header.setInt32(60, 2048, true);
      frame.set(payload, 64);
      return frame;
    };

    window.fetch = (input, options = {}) => {
      const rawUrl = typeof input === 'string' || input instanceof URL ? String(input) : input.url;
      const url = new URL(rawUrl, window.location.href);
      if (url.pathname === '/api/v1/live/multiplex') {
        window.spectrumStreamLifecycle.opened += 1;
        const ready = multiplexFrame(0, 1, encoder.encode(JSON.stringify({
          event: 'ready', data: { client_id: url.searchParams.get('client_id') }
        })));
        return Promise.resolve(new Response(new ReadableStream({
          start(controller) {
            controller.enqueue(ready);
            window.setTimeout(() => {
              if (controller.desiredSize !== null) controller.enqueue(multiplexFrame(5, 2, tunerStateFrame()));
            }, 50);
          },
          cancel() { window.spectrumStreamLifecycle.cancelled += 1; }
        }), { status: 200, headers: { 'Content-Type': 'application/vnd.sdrtrunk.live+binary' } }));
      }
      return nativeFetch(input, options);
    };
  }, { centerFrequencyHz: 773081250, sampleRateHz: 10000000 });
}

async function install(page, options = {}) {
  if (options.liveSpectrum) await installLiveSpectrumStream(page);
  const state = {
    requests: [], takeoverSequence: 0, monitorSequence: 0,
    failNextDelete: false, delayNextDelete: false, releaseDelete: null, failNextRenewal: null,
    failDiscoveryDeleteOnce: Boolean(options.failDiscoveryDeleteOnce),
    releaseDiscoveryPost: null, releaseDiscoveryDelete: null
  };
  const preferenceModule = await import(pathToFileURL(resolve(root,
    'stats-web/assets/core/preference-schema.js')).href);

  if (options.discovery) await page.route('**/assets/app.js*', (route) => route.fulfill({
    contentType: 'text/javascript', body: `${app}\nexport { closeReadOnlyModal };`
  }));

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
    if (path === '/api/v1/diagnostics/tuners') return respond({ rows: options.liveSpectrum ? [{
      target_id: 'target-active-a', label: 'Dispatch receiver', center_frequency_hz: 773081250,
      sample_rate_hz: 10000000
    }] : [] });
    if (path === '/api/v1/admin/channels/protocols') return respond(protocols);
    if (path === '/api/v1/admin/spectrum-discovery/eligibility') return respond({
      eligible: true, reason: null, matches: []
    });
    if (path === '/api/v1/admin/spectrum-discovery' && method === 'POST') {
      if (options.delayDiscoveryPost) {
        await new Promise((release) => { state.releaseDiscoveryPost = release; });
        state.releaseDiscoveryPost = null;
      }
      return respond(discoverySnapshot(body.frequency_hz), 201);
    }
    if (path === '/api/v1/admin/spectrum-discovery/discovery-a') {
      if (method === 'DELETE') {
        if (state.failDiscoveryDeleteOnce) {
          state.failDiscoveryDeleteOnce = false;
          return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({
            error: { message: 'Probe release interrupted', code: 'discovery_release_failed' }
          }) });
        }
        if (options.delayDiscoveryDelete) {
          await new Promise((release) => { state.releaseDiscoveryDelete = release; });
          state.releaseDiscoveryDelete = null;
        }
        return respond({ closed: true });
      }
      return respond(discoverySnapshot());
    }
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
  if (options.discovery) await page.evaluate(async () => {
    const script = document.querySelector('script[type="module"][src*="/assets/app.js"]');
    window.spectrumLifecycleApi = await import(script.src);
  });
  return state;
}

async function takeControl(page) {
  await page.getByRole('button', { name: 'Stop channels to tune', exact: true }).click();
  const warning = page.getByRole('alertdialog', { name: 'Stop channels to tune?' });
  await expect(warning).toBeVisible();
  await warning.getByRole('button', { name: 'Stop channels and tune', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Resume channels', exact: true })).toBeVisible();
}

async function openDiscoveryFromSpectrum(page) {
  const panel = page.locator('.spectrum-browse-panel');
  const rail = page.locator('.spectrum-browse-control-rail');
  await expect(page.locator('.spectrum-browse-state .badge')).toHaveText('Live');
  await panel.getByRole('img', { name: 'Tuner frequency spectrum', exact: true })
    .click({ position: { x: 240, y: 80 } });
  const add = rail.getByRole('button', { name: 'Add channel or system', exact: true });
  await expect(add).toBeEnabled();
  await add.click();
  const dialog = page.locator('.spectrum-discovery-modal');
  await expect(dialog.getByRole('heading', { name: 'What kind of signal is this?', exact: true })).toBeVisible();
  return dialog;
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

test('channel discovery reconnects Spectrum only after its probe DELETE completes', async ({ page }) => {
  const state = await install(page, {
    discovery: true, liveSpectrum: true, failDiscoveryDeleteOnce: true, delayDiscoveryDelete: true
  });
  const status = page.locator('.spectrum-browse-state .badge');
  const streams = () => page.evaluate(() => ({ ...window.spectrumStreamLifecycle }));
  await expect.poll(async () => (await streams()).opened).toBe(1);
  const dialog = await openDiscoveryFromSpectrum(page);

  await dialog.getByRole('button', { name: 'Check signal', exact: true }).click();
  await expect(dialog.getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  await expect(status).toHaveText('In use');
  const createIndex = state.requests.findIndex((request) =>
    request.path === '/api/v1/admin/spectrum-discovery' && request.method === 'POST');
  const suspendIndex = state.requests.findIndex((request) =>
    request.path === '/api/v1/live/multiplex/control' && request.method === 'POST' &&
    Object.keys(request.body.subscriptions || {}).length === 0);
  expect(suspendIndex).toBeGreaterThanOrEqual(0);
  expect(createIndex).toBeGreaterThan(suspendIndex);

  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect.poll(() => typeof state.releaseDiscoveryDelete).toBe('function');
  const releaseDelete = state.releaseDiscoveryDelete;
  try {
    await expect(status).toHaveText('In use');
    expect(await streams()).toEqual({ opened: 1, cancelled: 1 });
  } finally {
    releaseDelete();
  }

  await expect.poll(async () => (await streams()).opened).toBe(2);
  await expect(status).toHaveText('Live');
  const deleteIndexes = state.requests.map((request, index) => ({ request, index }))
    .filter(({ request }) => request.path === '/api/v1/admin/spectrum-discovery/discovery-a' &&
      request.method === 'DELETE').map(({ index }) => index);
  expect(deleteIndexes).toHaveLength(2);
  const resumeIndex = state.requests.findIndex((request, index) => index > deleteIndexes[1] &&
    request.path === '/api/v1/live/multiplex/control' && request.method === 'POST' &&
    request.body.subscriptions?.tuner_diagnostics);
  expect(deleteIndexes[0]).toBeGreaterThan(createIndex);
  expect(resumeIndex).toBeGreaterThan(deleteIndexes[1]);
});

test('forced close during discovery POST deletes the created probe before Spectrum reconnects', async ({ page }) => {
  const state = await install(page, {
    discovery: true, liveSpectrum: true, delayDiscoveryPost: true, delayDiscoveryDelete: true
  });
  const status = page.locator('.spectrum-browse-state .badge');
  const streams = () => page.evaluate(() => ({ ...window.spectrumStreamLifecycle }));
  await expect.poll(async () => (await streams()).opened).toBe(1);
  const dialog = await openDiscoveryFromSpectrum(page);

  await dialog.getByRole('button', { name: 'Check signal', exact: true }).click();
  await expect.poll(() => typeof state.releaseDiscoveryPost).toBe('function');
  await expect(status).toHaveText('In use');
  await page.evaluate(() => window.spectrumLifecycleApi.closeReadOnlyModal(true));
  await expect(dialog).toHaveCount(0);
  expect(await streams()).toEqual({ opened: 1, cancelled: 1 });

  state.releaseDiscoveryPost();
  await expect.poll(() => typeof state.releaseDiscoveryDelete).toBe('function');
  const releaseDelete = state.releaseDiscoveryDelete;
  try {
    await expect(status).toHaveText('In use');
    expect(await streams()).toEqual({ opened: 1, cancelled: 1 });
  } finally {
    releaseDelete();
  }

  await expect.poll(async () => (await streams()).opened).toBe(2);
  await expect(status).toHaveText('Live');
  const createIndex = state.requests.findIndex((request) =>
    request.path === '/api/v1/admin/spectrum-discovery' && request.method === 'POST');
  const deleteIndex = state.requests.findIndex((request) =>
    request.path === '/api/v1/admin/spectrum-discovery/discovery-a' && request.method === 'DELETE');
  const resumeIndex = state.requests.findIndex((request, index) => index > deleteIndex &&
    request.path === '/api/v1/live/multiplex/control' && request.method === 'POST' &&
    request.body.subscriptions?.tuner_diagnostics);
  expect(deleteIndex).toBeGreaterThan(createIndex);
  expect(resumeIndex).toBeGreaterThan(deleteIndex);
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
  await expect(page.getByText('1 channel stopped · Resume when finished', { exact: true })).toBeVisible();
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
