const { expect, test } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');
const root = resolve(__dirname, '../../../../..');
const app = readFileSync(resolve(root, 'stats-web/assets/app.js'), 'utf8');
const protocols = require(resolve(root, 'src/main/resources/channel-protocols.json'));

function tuner() {
  return { id: 'idle-a', name: 'Test receiver', tuner_class: 'AIRSPY', status: 'ENABLED',
    available: true, operator_state: 'setup', channel_count: 0, frequency_hz: 851012500,
    spectrum_target_id: 'target-a', sample_rate_hz: 10000000,
    settings: [{ id: 'frequency_mhz', label: 'Center frequency', value: 851.0125,
      editable: true, availability: 'setup', minimum: 24, maximum: 1800,
      unavailable_reason: 'Unlock center', dependencies: [{ setting_id: 'center_frequency_locked', equals: false }] },
    { id: 'center_frequency_locked', label: 'Lock center', kind: 'boolean', value: false,
      editable: true, availability: 'live', dependencies: [] }] };
}

function snapshot(protocolId, state) {
  const profile = protocols.profiles.find((candidate) => candidate.id === protocolId);
  const isP25 = protocolId === 'p25-phase1';
  const digital = ['dmr', 'nxdn'].includes(protocolId);
  const analogName = digital ? `${protocolId.toUpperCase()} control` : protocolId === 'am' ? 'AM channel' : 'FM channel';
  const settings = Object.fromEntries(profile.sections.flatMap((section) => section.fields)
    .filter((field) => field.path.startsWith('settings.') && Object.hasOwn(field, 'default'))
    .map((field) => [field.path.substring(9), field.default]));
  if (digital) settings.channel_mode = 'TRUNKED';
  return { session_id: 'discovery-a', state: state.saved ? state.running ? 'running' : 'saved' : state.phase || 'ready', reason: state.reason || null,
    protocol_id: protocolId,
    tuner_id: 'idle-a', target_id: 'target-a', frequency_hz: state.frequencyHz || 851012500,
    expires_at_ms: Date.now() + 30000,
    radio_reference: state.directoryResult,
    digital_probe: digital ? { state: state.phase || 'ready', elapsed_ms: 1200, timeout_ms: 10000 } : null,
    trunked_evidence: digital ? { variant: protocolId === 'dmr' ? 'Tier III' : 'Type-C', identity: {
      radio_system_key: protocolId === 'dmr' ? 'dmr:tier3:small:12' : 'nxdn-c:local:12',
      network: protocolId === 'dmr' ? 12 : null, system: protocolId === 'nxdn' ? 12 : null, site: 3, ran: 9 },
      frequency_map: [{ number: 1, downlink_hz: 851012500 }] } : null,
    probe: isP25 ? {
      c4fm: { valid_messages: 18, valid_control_messages: 12, invalid_control_messages: 4, quality_pct: 81.2 },
      cqpsk: { valid_messages: 52, valid_control_messages: 41, invalid_control_messages: 1, quality_pct: 99.1,
        ...state.cqpskMetrics },
      elapsed_ms: 1500, timeout_ms: 15000,
      selected_modulation: state.phase === 'identifying' ? null : 'CQPSK',
      identity: state.phase === 'identifying' ? null : { wacn: 0xb0001, system: 0x123, rfss: 1, site: 2 },
      ...state.probeOverride
    } : null,
    review: state.saved || (state.phase && state.phase !== 'ready') ? null : {
      revision: state.revision || 7, template: { protocol_id: protocolId, name: state.templateName || (isP25 ? 'Control' : analogName),
        system: state.templateSystem || (isP25 ? 'P25 B0001-123' : digital ? 'County Transit' : ''),
        site: isP25 ? 'RFSS 1 Site 2' : digital ? 'North' : '', settings,
        frequency_map: digital ? state.frequencyMap || [{ number: 1, downlink_hz: 851012500 }] : [] },
      alias_lists: state.ambiguous ? [{ id: 21, name: 'North aliases', matched: true },
        { id: 22, name: 'South aliases', matched: true }] :
        (state.singleMatch ? [{ id: 21, name: 'County aliases', matched: true }] : []),
      suggested_alias_list_id: null, default_new_alias_list_name: isP25 ? 'P25 B0001-123' : analogName
    },
    saved: state.saved ? { configuration_id: 'channel-a', alias_list_id: 41,
      running: state.running === true, start_error: state.running ? null : 'Tuner capacity is in use.' } : null
  };
}

async function installLiveSpectrumStream(page) {
  await page.addInitScript(({ centerFrequencyHz, sampleRateHz }) => {
    const nativeFetch = window.fetch.bind(window);
    const encoder = new TextEncoder();
    let streamController = null;

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
      header.setBigInt64(16, 1n, true);
      header.setBigInt64(24, 1n, true);
      header.setBigInt64(32, BigInt(Date.now()), true);
      header.setBigInt64(40, BigInt(Date.now()), true);
      header.setBigInt64(48, BigInt(centerFrequencyHz), true);
      header.setInt32(56, sampleRateHz, true);
      header.setInt32(60, 2048, true);
      frame.set(payload, 64);
      return frame;
    };
    const sendTunerState = () => {
      if (!streamController || streamController.desiredSize === null) return;
      streamController.enqueue(multiplexFrame(5, 2, tunerStateFrame()));
    };

    window.fetch = (input, options = {}) => {
      const rawUrl = typeof input === 'string' || input instanceof URL ? String(input) : input.url;
      const url = new URL(rawUrl, window.location.href);
      if (url.pathname === '/api/v1/live/multiplex') {
        const ready = multiplexFrame(0, 1, encoder.encode(JSON.stringify({
          event: 'ready', data: { client_id: url.searchParams.get('client_id') }
        })));
        return Promise.resolve(new Response(new ReadableStream({
          start(controller) {
            streamController = controller;
            controller.enqueue(ready);
            window.setTimeout(sendTunerState, 100);
            window.setTimeout(sendTunerState, 500);
          },
          cancel() {
            streamController = null;
          }
        }), { status: 200, headers: { 'Content-Type': 'application/vnd.sdrtrunk.live+binary' } }));
      }
      return nativeFetch(input, options);
    };
  }, { centerFrequencyHz: 851012500, sampleRateHz: 10000000 });
}

async function install(page, state = {}) {
  const browseWillFail = state.failBrowseOnce === true;
  state.tuner = tuner();
  state.tuner.settings.find((setting) => setting.id === 'center_frequency_locked').value = state.locked === true;
  state.requests = [];
  if (state.liveSpectrum) await installLiveSpectrumStream(page);
  const preferenceModule = await import(pathToFileURL(resolve(root,
    'stats-web/assets/core/preference-schema.js')).href);
  await page.route('**/assets/app.js*', (route) => route.fulfill({ contentType: 'text/javascript',
    body: `${app}\nexport { openSpectrumDiscoveryWizard, openTunerFrequencyActions };` }));
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const body = JSON.parse(request.postData() || '{}');
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json',
      body: JSON.stringify({ data }) });
    state.requests.push({ path, method: request.method(), body });
    if (path === '/api/v1/auth/session') return respond({ configured: true, authenticated: true,
      tier: 'admin', username: 'admin', csrf_token: 'test-token', capabilities: {
        'web-access': true, dashboard: true, 'tuner-spectrum': true, 'admin-tuners': true,
        'admin-channels': true, 'admin-aliases': true,
        ...(state.listening ? { live: true, 'call-audio': true, recordings: true } : {})
      } });
    if (path === '/api/v1/me/preferences') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, preferences: { ...preferenceModule.defaults,
        appearance: { hue: null, theme: state.theme || 'light' } } }) });
    if (path === '/api/v1/spectrum-snap-presets') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, country_code: 'US', country_label: 'United States',
        countries: [{ code: 'US', label: 'United States' }], scopes: [] }) });
    if (path === '/api/v1/admin/tuners') return respond({ tuners: [state.tuner] });
    if (path.startsWith('/api/v1/admin/tuners/idle-a/settings/')) {
      const setting = state.tuner.settings.find((candidate) => candidate.id === path.split('/').at(-1));
      setting.value = body.value;
      if (setting.id === 'frequency_mhz') state.tuner.frequency_hz = Math.round(body.value * 1_000_000);
      return respond({});
    }
    if (path.endsWith('/browse')) {
      if (request.method() === 'DELETE') return respond(null, 204);
      if (state.failBrowseOnce) {
        state.failBrowseOnce = false;
        state.browseError = true;
        return route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'busy', message: 'Tuner is temporarily unavailable.' } }) });
      }
      if (Number.isInteger(state.browseChannelCount)) state.tuner.channel_count = state.browseChannelCount;
      return respond({ lease_id: 'browse-a', expires_at_epoch_ms: Date.now() + 30000,
        can_tune: !state.running && state.tuner.channel_count === 0, tuner: state.tuner });
    }
    if (path === '/api/v1/diagnostics/tuners') return respond({ rows: state.liveSpectrum ? [{
      target_id: 'target-a', label: 'Test receiver', center_frequency_hz: 851012500,
      sample_rate_hz: 10000000
    }] : [] });
    if (path === '/api/v1/admin/radioreference') {
      if (state.delayDirectoryConfiguration) await new Promise((release) => { state.releaseDirectory = release; });
      return respond(state.directoryConfiguration || { account: { state: 'SIGNED_OUT' } });
    }
    if (path === '/api/v1/admin/radioreference/countries') {
      if (state.failDirectoryCountriesCount > 0) {
        state.failDirectoryCountriesCount -= 1;
        return route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'unavailable', message: 'Countries temporarily unavailable.' } }) });
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
        return route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'unavailable', message: 'States temporarily unavailable.' } }) });
      }
      return respond({ items: [{ id: 39, name: 'Ohio' }, { id: 42, name: 'Pennsylvania' }] });
    }
    if (path === '/api/v1/admin/channels/protocols') return respond(protocols);
    if (state.manualSetup && path === '/api/v1/admin/channels/options') return respond({
      revision: 7, tuners: ['Test receiver'], alias_lists: [{ id: 21, name: 'Default P25', family: 'P25' }]
    });
    if (state.manualSetup && path === '/api/v1/admin/channels/protocols/p25-phase1/template') {
      const profile = protocols.profiles.find((candidate) => candidate.id === 'p25-phase1');
      const settings = Object.fromEntries(profile.sections.flatMap((section) => section.fields)
        .filter((field) => field.path.startsWith('settings.') && Object.hasOwn(field, 'default'))
        .map((field) => [field.path.substring(9), field.default]));
      return respond({ protocol_id: 'p25-phase1', name: 'P25 Channel', alias_list_id: 21,
        source: { frequencies_hz: [], rotation_delay_ms: 500 }, settings, observed: {},
        event_logs: [], recorders: [], auxiliary_decoders: [] });
    }
    if (state.manualSetup && path === '/api/v1/admin/channels') return respond({ revision: 8, channels: [] });
    if (path.endsWith('/eligibility')) return respond({ eligible: !state.known,
      reason: state.known ? 'This frequency belongs to County Control.' : null,
      matches: state.known ? [{ configuration_id: 'channel-a', name: 'County Control',
        system: 'County P25', site: 'North' }] : [] });
    if (path === '/api/v1/admin/spectrum-discovery' && request.method() === 'POST') {
      state.protocolId = body.protocol_id;
      state.frequencyHz = body.frequency_hz;
      return respond(snapshot(state.protocolId, state));
    }
    if (path === '/api/v1/admin/spectrum-discovery/discovery-a') {
      if (request.method() === 'DELETE') {
        if (state.deferDeleteOnce) {
          state.deferDeleteOnce = false;
          await new Promise((release) => { state.releaseDelete = release; });
        }
        if (state.failDeleteOnce) {
          state.failDeleteOnce = false;
          return route.fulfill({ status: 503, contentType: 'application/json',
            body: JSON.stringify({ error: { code: 'unavailable', message: 'Could not stop the signal check.' } }) });
        }
        return respond(null, 204);
      }
      if (state.expiredStatus) return route.fulfill({ status: 409, contentType: 'application/json',
        body: JSON.stringify({ error: { code: 'discovery_conflict', message: 'Channel discovery expired; begin again' } }) });
      if (state.failProbeStatusOnce) {
        state.failProbeStatusOnce = false;
        return route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'unavailable', message: 'Status temporarily unavailable.' } }) });
      }
      if (state.deferStatusOnce) {
        state.deferStatusOnce = false;
        const previous = snapshot(state.protocolId, state);
        await new Promise((release) => { state.releaseStatus = release; });
        return respond(previous);
      }
      return respond(snapshot(state.protocolId, state));
    }
    if (path.endsWith('/save')) {
      if (state.restartRequired) return route.fulfill({ status: 503, contentType: 'application/json',
        body: JSON.stringify({ error: { code: 'channel_saved_restart_required',
          message: 'The channel was saved, but receiver configuration could not refresh.' } }) });
      if (state.failSaveOnce) {
        state.failSaveOnce = false;
        return route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'unavailable', message: 'Could not add this channel. Try again.' } }) });
      }
      if (state.staleOnce) {
        state.staleOnce = false;
        state.revision = 8;
        return route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'stale_revision', message: 'Configuration changed.' } }) });
      }
      state.saved = true;
      state.running = state.failStart !== true;
      return respond(snapshot(state.protocolId, state));
    }
    if (path.endsWith('/start')) {
      state.running = true;
      state.tuner.channel_count = 1;
      state.tuner.operator_state = 'live';
      return respond(snapshot(state.protocolId, state));
    }
    return respond({});
  });
  await page.goto('/app.html?view=tuner-spectrum');
  await expect(page.getByRole('heading', { name: 'Tuner Spectrum', exact: true })).toBeVisible();
  if (browseWillFail) await expect(page.getByRole('button', { name: 'Retry browsing', exact: true })).toBeVisible();
  else await expect(page.locator('.spectrum-browse-center')).toContainText('0851.01250MHz');
  await page.evaluate(async () => {
    window.discoveryApi = await import(document.querySelector('script[type="module"][src*="/assets/app.js"]').src);
    window.discoveryProbeStates = [];
    window.openDiscovery = () => window.discoveryApi.openSpectrumDiscoveryWizard({
      tunerId: 'idle-a', tunerName: 'Test receiver', targetId: 'target-a', frequencyHz: 851012500,
      browseLeaseId: 'browse-a',
      setProbeActive: (active) => window.discoveryProbeStates.push(active)
    });
  });
}

const wizard = (page) => page.locator('.spectrum-discovery-modal');
const radioTypes = { 'p25-phase1': 'P25 radio system', dmr: 'DMR trunked system', nxdn: 'NXDN trunked system', am: 'AM radio', nbfm: 'FM two-way radio' };

async function begin(page, protocolId = 'p25-phase1') {
  await page.evaluate(() => window.openDiscovery());
  await wizard(page).getByRole('radio', { name: radioTypes[protocolId], exact: true }).check();
  await wizard(page).getByRole('button', { name: ['p25-phase1', 'dmr', 'nxdn'].includes(protocolId) ? 'Check signal' : 'Continue',
    exact: true }).click();
}

async function review(page, protocolId = 'p25-phase1') {
  await wizard(page).getByRole('button', { name: ['p25-phase1', 'dmr', 'nxdn'].includes(protocolId) ? 'Review channel' : 'Continue',
    exact: true }).click();
  await expect(wizard(page).getByRole('heading', { name: 'Name your channel', exact: true })).toBeVisible();
}

async function disclose(page, label) {
  const escaped = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  await wizard(page).locator('summary').filter({ hasText: new RegExp(`^${escaped}$`) }).click();
}

for (const [theme, viewport] of [['light', { width: 1365, height: 900 }],
  ['dark', { width: 390, height: 844 }]]) {
  test(`signed-in spectrum discovery offers a missing region directly in ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    const state = { theme, directoryConfiguration: { account: { state: 'VALID_PREMIUM' } } };
    await install(page, state);
    await page.evaluate(() => window.openDiscovery());
    await expect(wizard(page).getByLabel('RadioReference country')).toBeVisible();
    await expect(wizard(page)).toContainText('P25 systems can match by on-air identity.');
    await wizard(page).getByLabel('RadioReference country').selectOption('1');
    await expect(wizard(page).getByLabel('RadioReference state or province')).toBeEnabled();
    await expect(wizard(page).getByLabel('RadioReference state or province')).toHaveValue('');
    await wizard(page).getByLabel('RadioReference state or province').selectOption('42');
    await wizard(page).getByLabel('RadioReference state or province').scrollIntoViewIfNeeded();
    await wizard(page).screenshot({ path: testInfo.outputPath('region-selection.png') });
    await wizard(page).getByRole('button', { name: 'Check signal', exact: true }).click();
    await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
    expect(state.requests.find((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
      request.method === 'POST').body.radioreference_state_id).toBe(42);
  });
}

test('starting a signal check waits for the saved RadioReference region to load', async ({ page }) => {
  await page.clock.install();
  const state = { delayDirectoryConfiguration: true, directoryConfiguration: {
    account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } };
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  await expect.poll(() => typeof state.releaseDirectory).toBe('function');
  await wizard(page).getByRole('button', { name: 'Check signal', exact: true }).click();
  await page.clock.fastForward(6000);
  expect(state.requests.some((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
    request.method === 'POST')).toBe(false);
  state.releaseDirectory();
  await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  expect(state.requests.find((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
    request.method === 'POST').body.radioreference_state_id).toBe(39);
});

test('discovery lookup regions wait beyond five seconds and preserve the saved state at start', async ({ page }) => {
  await page.clock.install();
  const state = { delayDirectoryStates: true, directoryConfiguration: {
    account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } };
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  await expect.poll(() => typeof state.releaseDirectoryStates).toBe('function');
  await disclose(page, 'RadioReference names (optional)');
  await expect(wizard(page)).toContainText('Loading states and provinces…');
  await page.clock.fastForward(6000);
  await expect(wizard(page).getByRole('button', { name: 'Retry loading regions', exact: true })).toBeHidden();
  await wizard(page).getByRole('button', { name: 'Check signal', exact: true }).click();
  expect(state.requests.some(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST')).toBe(false);
  state.releaseDirectoryStates();
  await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  expect(state.requests.find(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST')
    .body.radioreference_state_id).toBe(39);
});

for (const [theme, viewport] of [['light', { width: 1365, height: 900 }],
  ['dark', { width: 390, height: 844 }]]) {
  test(`discovery lookup region failure can retry in ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    const state = { theme, failDirectoryStatesCount: 1,
      directoryConfiguration: { account: { state: 'VALID_PREMIUM' } } };
    await install(page, state);
    await page.evaluate(() => window.openDiscovery());
    await expect(wizard(page).getByLabel('RadioReference country')).toBeVisible();
    await wizard(page).getByLabel('RadioReference country').selectOption('1');
    await expect(wizard(page)).toContainText(
      'States and provinces could not be loaded. Retry, or continue without frequency matching.');
    const retry = wizard(page).getByRole('button', { name: 'Retry loading regions', exact: true });
    await expect(retry).toBeVisible();
    await expect(wizard(page).getByLabel('RadioReference state or province')).toBeDisabled();
    await retry.scrollIntoViewIfNeeded();
    await wizard(page).screenshot({ path: testInfo.outputPath('region-load-failure.png') });
    await retry.click();
    const region = wizard(page).getByLabel('RadioReference state or province');
    await expect(region).toBeEnabled();
    await expect(region).toHaveValue('');
    await expect(retry).toBeHidden();
    await region.selectOption('42');
    await region.scrollIntoViewIfNeeded();
    await wizard(page).screenshot({ path: testInfo.outputPath('region-load-recovered.png') });
    await wizard(page).getByRole('button', { name: 'Check signal', exact: true }).click();
    await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
    expect(state.requests.filter(({ path }) => path === '/api/v1/admin/radioreference/states')).toHaveLength(2);
    expect(state.requests.find(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST')
      .body.radioreference_state_id).toBe(42);
  });
}

test('discovery lookup region list failure keeps the saved state in the start payload', async ({ page }) => {
  const state = { failDirectoryStatesCount: 1, directoryConfiguration: {
    account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } };
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  await expect(wizard(page)).toContainText('Your saved lookup region will be used.');
  await expect(wizard(page).getByLabel('RadioReference state or province')).toHaveValue('39');
  await expect(wizard(page).getByRole('button', { name: 'Retry loading regions', exact: true })).toBeVisible();
  await wizard(page).getByRole('button', { name: 'Check signal', exact: true }).click();
  await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  expect(state.requests.find(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST')
    .body.radioreference_state_id).toBe(39);
});

test('discovery start waits for a pending region retry', async ({ page }) => {
  await page.clock.install();
  const state = { failDirectoryStatesCount: 1, directoryConfiguration: {
    account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } };
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  await expect(wizard(page)).toContainText('Your saved lookup region will be used.');
  state.delayDirectoryStates = true;
  await wizard(page).getByRole('button', { name: 'Retry loading regions', exact: true }).click();
  await expect.poll(() => typeof state.releaseDirectoryStates).toBe('function');
  await wizard(page).getByRole('button', { name: 'Check signal', exact: true }).click();
  await page.clock.fastForward(1000);
  expect(state.requests.some(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST')).toBe(false);
  state.releaseDirectoryStates();
  await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  expect(state.requests.filter(({ path }) => path === '/api/v1/admin/radioreference/states')).toHaveLength(2);
  expect(state.requests.find(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST')
    .body.radioreference_state_id).toBe(39);
});

test('discovery lookup country failure retries account settings and recovers its region choices', async ({ page }) => {
  const state = { failDirectoryCountriesCount: 1,
    directoryConfiguration: { account: { state: 'VALID_PREMIUM' } } };
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  await expect(wizard(page)).toContainText('Lookup regions could not be loaded. Retry, or continue without choosing a lookup region.');
  await wizard(page).getByRole('button', { name: 'Retry loading regions', exact: true }).click();
  await expect(wizard(page).getByLabel('RadioReference country')).toBeVisible();
  await wizard(page).getByLabel('RadioReference country').selectOption('1');
  const region = wizard(page).getByLabel('RadioReference state or province');
  await expect(region).toBeEnabled();
  await region.selectOption('39');
  await wizard(page).getByRole('button', { name: 'Check signal', exact: true }).click();
  await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  expect(state.requests.filter(({ path }) => path === '/api/v1/admin/radioreference')).toHaveLength(2);
  expect(state.requests.filter(({ path }) => path === '/api/v1/admin/radioreference/countries')).toHaveLength(2);
  expect(state.requests.find(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST')
    .body.radioreference_state_id).toBe(39);
});

test('signed-out spectrum discovery keeps names optional and links to connection settings', async ({ page }) => {
  const state = {};
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  await disclose(page, 'RadioReference names (optional)');
  await expect(wizard(page).getByRole('link', { name: 'RadioReference settings', exact: true }))
    .toHaveAttribute('href', /view=radioreference/);
  await expect(wizard(page).getByLabel('RadioReference state or province')).toHaveCount(0);
  await wizard(page).getByRole('button', { name: 'Check signal', exact: true }).click();
  await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  expect(state.requests.find((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
    request.method === 'POST').body.radioreference_state_id).toBe(null);
});

test('delayed click-directory matching refreshes untouched fields while preserving edited names and maps', async ({ page }) => {
  const state = { directoryResult: { state: 'pending' }, templateName: 'Decoded control', templateSystem: 'Decoded network' };
  await install(page, state);
  await begin(page, 'dmr');
  await review(page, 'dmr');
  await wizard(page).getByLabel('Channel name', { exact: true }).fill('My control');
  await wizard(page).getByLabel('New Alias List name', { exact: true }).fill('My listening');
  await wizard(page).getByRole('button', { name: 'Add mapping', exact: true }).click();
  const manual = wizard(page).locator('.channel-map-row').nth(1);
  await manual.locator('input').nth(0).fill('2');
  await manual.locator('input').nth(1).fill('851.25');
  state.templateName = 'Directory North';
  state.templateSystem = 'Transit authority';
  state.frequencyMap = [{ number: 1, downlink_hz: 851012500 }, { number: 3, downlink_hz: 851500000 }];
  state.directoryResult = { state: 'matched', match: { system_name: 'Transit authority', site_name: 'North',
    channels: [{ logical_channel_number: 3, frequency_hz: 851500000 }] } };
  await expect(wizard(page).getByLabel('System', { exact: true })).toHaveValue('Transit authority');
  await expect(wizard(page).getByLabel('Channel name', { exact: true })).toHaveValue('My control');
  await expect(wizard(page).getByLabel('New Alias List name', { exact: true })).toHaveValue('My listening');
  await expect(manual.locator('input').nth(0)).toHaveValue('2');
  await expect(manual.locator('input').nth(1)).toHaveValue('851.25');
  await disclose(page, 'RadioReference');
  await expect(wizard(page)).toContainText('851.5 MHz');
  await wizard(page).getByRole('button', { name: 'Add and start listening', exact: true }).click();
  const saved = state.requests.find((request) => request.path.endsWith('/save'));
  expect(saved.body.name).toBe('My control');
  expect(saved.body.new_alias_list_name).toBe('My listening');
  expect(saved.body.frequency_map).toHaveLength(2);
});

for (const protocolId of ['dmr', 'nxdn']) {
  test(`click spectrum checks ${protocolId.toUpperCase()} identity and reviews detected trunked channels`, async ({ page }) => {
    const theme = protocolId === 'dmr' ? 'light' : 'dark';
    await page.setViewportSize({ width: protocolId === 'dmr' ? 1280 : 390, height: 900 });
    const state = { theme, phase: 'identifying', directoryResult: { state: 'unavailable' } };
    await install(page, state);
    await begin(page, protocolId);
    await expect(wizard(page).getByRole('heading', { name: 'Checking this radio system…', exact: true })).toBeVisible();
    await expect(wizard(page).getByRole('button', { name: 'Review channel', exact: true })).toHaveCount(0);
    state.phase = 'ready';
    state.directoryResult = { state: 'matched', match: { system_name: 'County Transit', site_name: 'North',
      channels: [{ frequency_hz: 851012500, logical_channel_number: 1, primary_control: true }] } };
    await expect(wizard(page).getByRole('button', { name: 'Review channel', exact: true })).toBeVisible();
    await review(page, protocolId);
    await expect(wizard(page)).toContainText('Unmapped channels can be followed only when the system broadcasts their frequencies.');
    await expect(wizard(page)).toContainText('851.0125 MHz');
    await disclose(page, 'RadioReference');
    await expect(wizard(page)).toContainText('County Transit · North');
    await expect(wizard(page)).toContainText('Primary control');
    await wizard(page).getByText('Unmapped channels can be followed only when the system broadcasts their frequencies.',
      { exact: false }).scrollIntoViewIfNeeded();
    const boundedMap = await wizard(page).evaluate((element) => {
      const bounds = element.getBoundingClientRect();
      return element.scrollWidth <= element.clientWidth + 1 &&
        [...element.querySelectorAll('.channel-map-row')].every((row) => {
          const rect = row.getBoundingClientRect();
          return rect.left >= bounds.left && rect.right <= bounds.right;
        });
    });
    expect(boundedMap).toBe(true);
    await expect(wizard(page)).toHaveScreenshot(`spectrum-discovery-${protocolId}-${theme}.png`);
    await wizard(page).getByRole('button', { name: 'Add and start listening', exact: true }).click();
    const saved = state.requests.find((request) => request.path.endsWith('/save'));
    expect(saved.body.settings.channel_mode).toBe('TRUNKED');
    expect(saved.body.frequency_map).toEqual([{ number: 1, downlink_hz: 851012500, uplink_hz: 0 }]);
    expect(state.requests.find((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
      request.method === 'POST').body.protocol_id).toBe(protocolId);
  });
}

for (const [theme, width] of [
  ['light', 1280], ['light', 390], ['light', 320], ['dark', 1280], ['dark', 390], ['dark', 320]
]) {
  test(`Spectrum respects a saved center lock in ${theme} at ${width}px`, async ({ page }) => {
    const state = { locked: true, theme, liveSpectrum: true };
    await page.setViewportSize({ width, height: 900 });
    await install(page, state);
    const center = page.locator('.spectrum-browse-center .tuners-center-frequency');
    const digit = page.getByRole('button', { name: 'Decrease 100 MHz place', includeHidden: true });
    await expect(page.locator('.spectrum-browse-toolbar').getByRole('checkbox', { name: 'Lock center' }))
      .toHaveCount(0);
    await expect(center).toHaveAttribute('aria-disabled', 'true');
    await expect(digit).toBeDisabled();
    await center.focus();
    await center.locator('.tuners-frequency-digit').first().hover();
    await page.keyboard.type('075');
    await page.keyboard.press('Enter');
    await expect(center).toContainText('0851.01250MHz');
    const plot = page.locator('.spectrum-browse-panel .tuner-spectrum-canvas').first();
    await expect(page.locator('.spectrum-browse-panel .channel-diagnostic-overlay').first()).toBeHidden();
    const bounds = await plot.boundingBox();
    await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width / 2 + 30, bounds.y + bounds.height / 2, { steps: 3 });
    await page.mouse.up();
    await expect(center).toContainText('0851.01250MHz');
    expect(state.requests.filter((request) => request.path.includes('/settings/'))).toEqual([]);
    expect(state.tuner.settings.find((setting) => setting.id === 'center_frequency_locked').value).toBe(true);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <=
      document.documentElement.clientWidth)).toBe(true);
  });

  test(`Spectrum tunes an unlocked receiver with its lease in ${theme} at ${width}px`, async ({ page }) => {
    const state = { theme };
    await page.setViewportSize({ width, height: 900 });
    await install(page, state);
    const center = page.locator('.spectrum-browse-center .tuners-center-frequency');
    const digit = page.getByRole('button', { name: 'Decrease 100 MHz place', includeHidden: true });
    await expect(page.locator('.spectrum-browse-toolbar').getByRole('checkbox', { name: 'Lock center' }))
      .toHaveCount(0);
    await expect(center).toHaveAttribute('aria-disabled', 'false');
    await expect(digit).toBeEnabled();
    await digit.click();
    await expect(page.locator('.spectrum-browse-center')).toContainText('0751.01250MHz');
    expect(state.requests.find((request) => request.path.endsWith('/settings/frequency_mhz')).body)
      .toEqual({ value: 751.0125, lease_id: 'browse-a' });
    expect(state.requests.filter((request) => request.path.endsWith('/settings/center_frequency_locked')))
      .toEqual([]);
    expect(state.tuner.settings.find((setting) => setting.id === 'center_frequency_locked').value).toBe(false);
    await expect(center).toHaveAttribute('aria-disabled', 'false');
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <=
      document.documentElement.clientWidth)).toBe(true);
  });
}

test('active monitoring keeps Spectrum center tuning disabled without changing its saved lock', async ({ page }) => {
  const state = { locked: false, running: true, browseChannelCount: 1 };
  await install(page, state);
  await expect(page.locator('.spectrum-browse-toolbar').getByRole('checkbox', { name: 'Lock center' }))
    .toHaveCount(0);
  await expect(page.locator('.spectrum-browse-center .tuners-center-frequency'))
    .toHaveAttribute('aria-disabled', 'true');
  await expect(page.getByRole('button', { name: 'Decrease 100 MHz place', includeHidden: true })).toBeDisabled();
  expect(state.requests.filter((request) => request.path.includes('/settings/'))).toEqual([]);
  expect(state.tuner.settings.find((setting) => setting.id === 'center_frequency_locked').value).toBe(false);
});

for (const width of [1280, 1440]) {
  test(`desktop Spectrum keeps its fixed panes while the visible audio player resizes at ${width}px`,
    async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await install(page, { liveSpectrum: true, listening: true });
      const dock = page.locator('#audio-dock');
      const handle = dock.getByRole('button', { name: 'Change audio player size', exact: true });
      await expect(dock).toBeVisible();
      await expect(page.locator('.spectrum-browse-panel .channel-diagnostic-overlay').first()).toBeHidden();
      const geometry = () => page.evaluate(() => {
        const selectors = ['.spectrum-browse-workspace', '.spectrum-browse-panel', '.tuner-spectrum-plot',
          '.tuner-spectrum-waterfall', '.spectrum-browse-control-rail'];
        return selectors.map(selector => {
          const bounds = document.querySelector(selector).getBoundingClientRect();
          return [Math.round(bounds.x), Math.round(bounds.y), Math.round(bounds.width), Math.round(bounds.height)];
        });
      });
      let baseline;
      const heights = [];
      for (const size of ['collapsed', 'minimal', 'full', 'collapsed']) {
        await handle.focus();
        await handle.press(size === 'full' ? 'End' : 'Home');
        if (size === 'minimal') await handle.press('ArrowUp');
        await expect(dock).toHaveAttribute('data-state', size);
        await expect.poll(() => page.evaluate(() => {
          const measured = parseFloat(getComputedStyle(document.documentElement)
            .getPropertyValue('--audio-dock-height'));
          return Math.abs(measured - document.querySelector('#audio-dock').getBoundingClientRect().height);
        })).toBeLessThanOrEqual(1);
        await expect(page.locator('.content')).toHaveCSS('overflow', 'visible');
        // An ungenerated pseudo-element can report display:block without
        // creating a box. Desktop must not generate the mobile dock spacer.
        expect(await page.locator('.content').evaluate(content => getComputedStyle(content, '::after').content))
          .toBe('none');
        if (!baseline) {
          baseline = await geometry();
          expect(baseline[1][3]).toBeGreaterThan(500);
        }
        await expect.poll(geometry).toEqual(baseline);
        heights.push(await dock.evaluate(element => element.getBoundingClientRect().height));
      }
      expect(heights[1]).toBeGreaterThan(heights[0]);
      expect(heights[2]).toBeGreaterThan(heights[1]);
      expect(heights[3]).toBe(heights[0]);
    });
}

test('managed Spectrum keeps one aligned toolbar and updates one persistent frequency rail', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const state = { liveSpectrum: true };
  await install(page, state);

  const heading = page.getByRole('heading', { name: 'Tuner Spectrum', exact: true });
  const header = page.locator('.page-header').filter({ has: heading });
  const toolbar = page.locator('.spectrum-browse-toolbar');
  const panel = page.locator('.spectrum-browse-panel');
  const rail = page.locator('.spectrum-browse-control-rail');
  await expect(toolbar.getByRole('checkbox', { name: 'Lock center', exact: true })).toHaveCount(0);
  await expect(toolbar.locator('.spectrum-browse-field-label')).toHaveText('Tuner');
  await expect(toolbar.locator('.tuners-center-lock-field')).toHaveCount(0);
  await expect(page.getByText('Keep tuner here', { exact: true })).toHaveCount(0);
  await expect(header.getByRole('button', { name: 'Find P25 channels', exact: true })).toHaveCount(0);
  await expect(toolbar.getByRole('button', { name: 'Find Trunked Systems', exact: true })).toHaveCount(0);
  await expect(rail).toHaveCount(1);
  await expect(rail.getByText('Select a signal', { exact: true })).toBeVisible();
  await expect(toolbar.locator('.spectrum-browse-message')).toHaveText('Drag to tune · Zoom to pan');
  await expect(rail).toContainText(
    'Click a signal in the spectrum or waterfall to see its frequency and available actions.');
  const toolbarRows = await toolbar.evaluate((element) => {
    const centers = [...element.children].filter((child) => !child.hidden).map((child) => {
      const bounds = child.getBoundingClientRect();
      return bounds.top + bounds.height / 2;
    });
    return { centerSpread: Math.max(...centers) - Math.min(...centers) };
  });
  expect(toolbarRows.centerSpread).toBeLessThanOrEqual(1);
  await page.evaluate(() => {
    window.originalSpectrumFrequencyRail = document.querySelector('.spectrum-browse-control-rail');
  });

  const desktop = await page.evaluate(() => {
    const spectrum = document.querySelector('.spectrum-browse-panel').getBoundingClientRect();
    const actions = document.querySelector('.spectrum-browse-control-rail').getBoundingClientRect();
    return { spectrum: { x: spectrum.x, right: spectrum.right, y: spectrum.y },
      actions: { x: actions.x, y: actions.y } };
  });
  expect(desktop.actions.x).toBeGreaterThanOrEqual(desktop.spectrum.right);
  expect(Math.abs(desktop.actions.y - desktop.spectrum.y)).toBeLessThanOrEqual(1);

  await expect(panel.locator('.channel-diagnostic-overlay').first()).toBeHidden();
  await panel.getByRole('img', { name: 'Tuner frequency spectrum', exact: true })
    .click({ position: { x: 240, y: 80 } });
  await expect(rail.getByText('Selected frequency', { exact: true })).toBeVisible();
  await expect(rail.locator('.tuner-frequency-action-frequency')).toHaveText(/^\d+\.\d{6} MHz$/);
  await expect(rail.getByRole('button', { name: 'Add channel or system', exact: true })).toBeEnabled();
  expect(await rail.evaluate((element) => element === window.originalSpectrumFrequencyRail)).toBe(true);
  await expect(page.locator('.tuner-frequency-popover')).toHaveCount(0);
  await expect(rail.getByText('What can I do here?', { exact: true })).toHaveCount(0);
  await expect(rail.locator('.tuner-frequency-action-tip')).toHaveCount(0);
  expect(state.requests.filter((request) => request.path.endsWith('/eligibility'))).toHaveLength(1);

  await page.setViewportSize({ width: 900, height: 900 });
  await expect.poll(() => page.evaluate(() => {
    const spectrum = document.querySelector('.spectrum-browse-panel').getBoundingClientRect();
    const actions = document.querySelector('.spectrum-browse-control-rail').getBoundingClientRect();
    const toolbar = document.querySelector('.spectrum-browse-toolbar');
    const centers = [...toolbar.children].filter((child) => !child.hidden).map((child) => {
      const bounds = child.getBoundingClientRect();
      return bounds.top + bounds.height / 2;
    });
    return { aligned: Math.abs(actions.x - spectrum.x) <= 1,
      railStacked: actions.y >= spectrum.bottom,
      toolbarAligned: Math.max(...centers) - Math.min(...centers) <= 1 };
  })).toEqual({ aligned: true, railStacked: true, toolbarAligned: true });
  await panel.getByRole('img', { name: 'Tuner frequency spectrum', exact: true })
    .click({ position: { x: 180, y: 80 } });
  await expect.poll(() => rail.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return bounds.top >= 0 && bounds.top < window.innerHeight;
  })).toBe(true);
});

test('bands, FFT, waterfall, legend, and measurements render as one continuous instrument', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await install(page, { liveSpectrum: true });
  const geometry = await page.locator('.spectrum-browse-instrument').evaluate((instrument) => {
    const visual = instrument.querySelector('.tuner-spectrum-visual-window');
    const fft = visual.querySelector('.tuner-spectrum-fft');
    const plot = fft.querySelector('.tuner-spectrum-plot');
    const bands = fft.querySelector('.tuner-spectrum-band-rail');
    const waterfall = visual.querySelector('.tuner-spectrum-waterfall');
    const statusRail = instrument.querySelector('.spectrum-browse-status-rail');
    const legend = instrument.querySelector('.tuner-spectrum-display-controls');
    const measurements = instrument.querySelector('.tuner-spectrum-measurement-panel');
    const bounds = (element) => {
      const rect = element.getBoundingClientRect();
      return { top: rect.top, right: rect.right, bottom: rect.bottom, left: rect.left };
    };
    return {
      instrument: bounds(instrument), visual: bounds(visual), fft: bounds(fft), plot: bounds(plot),
      bands: bounds(bands), waterfall: bounds(waterfall), statusRail: bounds(statusRail), legend: bounds(legend),
      measurements: bounds(measurements),
      legendClientWidth: legend.clientWidth, legendScrollWidth: legend.scrollWidth,
      instrumentBorder: getComputedStyle(instrument).borderTopWidth,
      visualBorder: getComputedStyle(visual).borderTopWidth,
      bandBorderTop: getComputedStyle(bands).borderTopWidth,
      bandBorderBottom: getComputedStyle(bands).borderBottomWidth,
      plotBorderBottom: getComputedStyle(plot).borderBottomWidth,
      statusRailBorderTop: getComputedStyle(statusRail).borderTopWidth,
      legendBorderTop: getComputedStyle(legend).borderTopWidth,
      measurementBorderTop: getComputedStyle(measurements).borderTopWidth
    };
  });
  expect(geometry.bands.top).toBeLessThan(geometry.plot.top);
  expect(Math.abs(geometry.bands.bottom - geometry.plot.top)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(geometry.fft.bottom - geometry.waterfall.top)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(geometry.fft.left - geometry.waterfall.left)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(geometry.fft.right - geometry.waterfall.right)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(geometry.visual.bottom - geometry.statusRail.top)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(geometry.legend.top - geometry.measurements.top)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(geometry.legend.right - geometry.measurements.left)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(geometry.legend.bottom - geometry.measurements.bottom)).toBeLessThanOrEqual(0.5);
  expect(geometry.legendScrollWidth).toBeLessThanOrEqual(geometry.legendClientWidth);
  expect(geometry.instrumentBorder).toBe('1px');
  expect(geometry.visualBorder).toBe('0px');
  expect(geometry.bandBorderTop).toBe('0px');
  expect(geometry.bandBorderBottom).toBe('1px');
  expect(geometry.plotBorderBottom).toBe('1px');
  expect(geometry.statusRailBorderTop).toBe('1px');
  expect(geometry.legendBorderTop).toBe('0px');
  expect(geometry.measurementBorderTop).toBe('0px');

  const reset = page.getByRole('button', { name: 'Reset zoom', exact: true });
  const pause = page.getByRole('button', { name: 'Pause', exact: true });
  const display = page.getByRole('button', { name: 'Display options', exact: true });
  const more = page.locator('.tuner-spectrum-more-measurements > summary');
  for (const control of [reset, pause, display, more]) {
    await expect(control).toHaveClass(/ui-icon-button/);
    await expect(control).toHaveText('');
  }
  await expect(more).toHaveAttribute('aria-label', 'More measurements');
  await more.click();
  const moreReadouts = page.locator('.tuner-spectrum-more-readouts');
  await expect(moreReadouts).toBeVisible();
  const popup = await moreReadouts.boundingBox();
  const instrument = await page.locator('.spectrum-browse-instrument').boundingBox();
  expect(popup.y).toBeGreaterThanOrEqual(instrument.y);
  expect(popup.y + popup.height).toBeLessThanOrEqual(instrument.y + instrument.height);
  await more.click();
  await expect(pause).toBeEnabled();
  await pause.click();
  const resume = page.getByRole('button', { name: 'Resume', exact: true });
  await expect(resume).toHaveClass(/ui-icon-button/);
  await expect(resume).toHaveText('');
  await expect(resume).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.tuner-spectrum-readouts .channel-diagnostic-readout small'))
    .toHaveText(['Visible span', 'Zoom', 'Peak', 'Best SNR']);
});

test('Spectrum display options remain reachable from desktop and mobile controls', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await install(page, { liveSpectrum: true });
  const options = page.locator('.tuner-spectrum-options');
  const panel = page.locator('.tuner-spectrum-options-panel');
  const desktopDisplay = page.getByRole('button', { name: 'Display options', exact: true });
  await expect(desktopDisplay).toBeVisible();
  await desktopDisplay.click();
  await expect(options).toHaveAttribute('open', '');
  await expect(desktopDisplay).toHaveAttribute('aria-expanded', 'true');
  await expect(panel).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(options).not.toHaveAttribute('open', '');
  await expect(desktopDisplay).toBeFocused();

  await page.setViewportSize({ width: 390, height: 844 });
  const more = page.getByRole('button', { name: 'More spectrum actions', exact: true });
  await expect(desktopDisplay).toBeHidden();
  await expect(more).toBeVisible();
  await more.click();
  await expect(panel).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Reset view', exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(panel).toBeHidden();
  await expect(more).toBeFocused();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <=
    document.documentElement.clientWidth)).toBe(true);
});

test('confirmed lease activity updates the existing tuner option and preserves picker focus', async ({ page }) => {
  const state = { browseChannelCount: 1 };
  await page.clock.install();
  await install(page, state);
  const picker = page.locator('.spectrum-browse-tuner select');
  await expect(picker.locator('option:checked')).toHaveText('Test receiver · 1 active · 10 MHz');
  await picker.focus();
  await page.evaluate(() => {
    window.originalSpectrumPicker = document.querySelector('.spectrum-browse-tuner select');
    window.originalSpectrumOption = window.originalSpectrumPicker.selectedOptions[0];
  });
  state.browseChannelCount = 0;
  await page.clock.fastForward(11000);
  await expect(picker.locator('option:checked')).toHaveText('Test receiver · Idle · 10 MHz');
  await expect(picker).toBeFocused();
  state.browseChannelCount = 2;
  await page.clock.fastForward(11000);
  await expect(picker.locator('option:checked')).toHaveText('Test receiver · 2 active · 10 MHz');
  await expect(picker).toBeFocused();
  expect(await picker.evaluate((element) => element === window.originalSpectrumPicker &&
    element.selectedOptions[0] === window.originalSpectrumOption)).toBe(true);
});

test('an unchanged lease renewal preserves a typed center frequency draft and its focused control', async ({ page }) => {
  const state = {};
  await page.clock.install();
  await install(page, state);
  const center = page.locator('.spectrum-browse-center .tuners-center-frequency');
  await center.focus();
  await page.locator('.spectrum-browse-center .tuners-frequency-digit').first().hover();
  await page.keyboard.type('075');
  await expect(center).toContainText('0751.01250MHz');
  await page.evaluate(() => {
    window.originalSpectrumCenter = document.querySelector('.spectrum-browse-center .tuners-center-frequency');
  });
  await page.clock.fastForward(11000);
  await expect.poll(() => state.requests.some((request) => request.path.endsWith('/browse') &&
    request.body.lease_id === 'browse-a')).toBe(true);
  await expect(center).toBeFocused();
  expect(await center.evaluate((element) => element === window.originalSpectrumCenter)).toBe(true);
  await page.keyboard.type('412345');
  await page.keyboard.press('Enter');
  await expect(center).toContainText('0754.12345MHz');
  expect(state.requests.find((request) => request.path.endsWith('/settings/frequency_mhz')).body)
    .toEqual({ value: 754.12345, lease_id: 'browse-a' });
});

test('idle receiver browsing renews and releases on leaving Spectrum', async ({ page }) => {
  const state = {};
  await page.clock.install();
  await install(page, state);
  await page.clock.fastForward(11000);
  await expect.poll(() => state.requests.filter((request) => request.path.endsWith('/browse') &&
    request.body.lease_id === 'browse-a').length).toBeGreaterThan(0);
  await page.evaluate(() => document.querySelector('a[data-view="dashboard"]').click());
  await expect.poll(() => state.requests.filter((request) => request.path.endsWith('/browse') &&
    request.method === 'DELETE').length).toBeGreaterThan(0);
});

test('a single tuner can retry failed browsing without changing selection', async ({ page }) => {
  const state = { failBrowseOnce: true };
  await install(page, state);
  await expect(page.getByRole('button', { name: 'Retry browsing' })).toBeVisible();
  await page.getByRole('button', { name: 'Retry browsing' }).click();
  await expect(page.locator('.spectrum-browse-center')).toContainText('0851.01250MHz');
  await expect(page.getByRole('button', { name: 'Retry browsing' })).toBeHidden();
  expect(state.requests.filter((request) => request.path.endsWith('/browse') && request.method === 'POST'))
    .toHaveLength(2);
});

test('the default P25 path uses plain language and waits for explicit review', async ({ page }) => {
  const state = {};
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  const dialog = wizard(page);
  await expect(dialog.getByRole('heading', { name: 'Add a channel', exact: true })).toBeVisible();
  const types = dialog.getByRole('group', { name: 'Radio type', exact: true });
  await expect(types.getByRole('radio')).toHaveCount(5);
  await expect(types.getByRole('radio', { name: 'P25 radio system', exact: true })).toBeChecked();
  await expect(dialog.getByText('Help me choose', { exact: true })).toHaveCount(0);
  expect(await dialog.innerText()).not.toMatch(/C4FM|CQPSK|WACN|RFSS|SysID|NAC/);
  await dialog.getByRole('button', { name: 'Check signal', exact: true }).click();
  await expect(dialog.getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  await expect(dialog.getByText('Ready to add', { exact: true })).toBeVisible();
  await expect(dialog.getByText('The system, site, and signal setting stayed consistent.', { exact: true })).toBeVisible();
  await expect(dialog.getByText('How does this work?', { exact: true })).toHaveCount(0);
  const details = dialog.locator('details.spectrum-discovery-technical');
  await expect(details).toHaveAttribute('open', '');
  await expect(details.locator('summary')).toHaveText('Signal details');
  await expect(details.getByRole('table')).toBeVisible();
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Review channel', exact: true })).toBeVisible();
  await expect(details.getByRole('rowheader', { name: 'CQPSK Selected', exact: true })).toBeVisible();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
  await review(page);
  await expect(dialog.getByLabel('Channel name', { exact: true })).toBeVisible();
  await expect(dialog.getByLabel('System', { exact: true })).toBeHidden();
  await expect(dialog.getByLabel('Site', { exact: true })).toBeHidden();
  await expect(dialog.locator('summary').filter({ hasText: /^Additional labels \(optional\)$/ })).toBeVisible();
  await expect(dialog.locator('summary').filter({ hasText: /^What is an Alias List\?$/ })).toBeVisible();
  expect(await dialog.innerText()).not.toMatch(/C4FM|CQPSK|WACN|RFSS|SysID|NAC/);
  await expect(dialog.getByLabel('New Alias List name', { exact: true })).toBeVisible();
});

test('P25 becoming ready preserves keyboard focus until the user reviews it', async ({ page }) => {
  const state = { phase: 'identifying' };
  await page.clock.install();
  await install(page, state);
  await begin(page);
  const dialog = wizard(page);
  await expect(dialog.getByRole('heading', { name: 'Checking this radio system…', exact: true })).toBeVisible();
  const cancel = dialog.getByRole('button', { name: 'Cancel', exact: true });
  await expect(cancel).toBeEnabled();
  await cancel.focus();
  state.phase = 'ready';
  await page.clock.fastForward(900);
  await expect(dialog.getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  await expect(cancel).toBeFocused();
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveCount(0);
  await review(page);
  await expect(dialog.getByRole('heading', { name: 'Name your channel', exact: true })).toBeFocused();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
});

test('P25 polling retains Cancel focus and cancellation releases the probe', async ({ page }) => {
  const state = { phase: 'identifying' };
  await install(page, state);
  await begin(page);
  const cancel = wizard(page).getByRole('button', { name: 'Cancel', exact: true });
  await expect(wizard(page).getByRole('heading', { name: 'Checking this radio system…', exact: true })).toBeVisible();
  await expect(wizard(page).getByRole('table')).toBeHidden();
  await disclose(page, 'Signal details');
  const table = wizard(page).getByRole('table');
  await expect(table.getByRole('columnheader')).toHaveText([
    'Setting', 'Valid', 'Control', 'Rejected', 'Decode score'
  ]);
  await expect(table.locator('tbody tr').nth(0).getByRole('cell')).toHaveText(['18', '12', '4', '81%']);
  await expect(table.locator('tbody tr').nth(1).getByRole('cell')).toHaveText(['52', '41', '1', '99%']);
  await expect(table.getByRole('rowheader', { name: 'C4FM', exact: true })).toBeVisible();
  await expect(table.getByRole('rowheader', { name: 'CQPSK', exact: true })).toBeVisible();
  await expect(wizard(page).getByText(/Message quality is the proportion of messages/)).toHaveCount(0);
  await expect(cancel).toBeEnabled();
  await cancel.focus();
  await expect(cancel).toBeFocused();
  state.cqpskMetrics = { valid_messages: 53, valid_control_messages: 42 };
  await page.waitForTimeout(900);
  await expect(table.locator('tbody tr').nth(1).getByRole('cell')).toHaveText(['53', '42', '1', '99%']);
  await expect(cancel).toBeFocused();
  await cancel.click();
  await expect(wizard(page)).toHaveCount(0);
  await expect.poll(() => state.requests.some((request) => request.path.endsWith('/discovery-a') &&
    request.method === 'DELETE')).toBe(true);
  await expect.poll(() => page.evaluate(() => window.discoveryProbeStates)).toEqual([true, false]);
});

test('retrying inconclusive P25 identification restores Cancel while scanning and saves no channel', async ({ page }) => {
  const state = { phase: 'inconclusive' };
  await install(page, state);
  await begin(page);
  const dialog = wizard(page);
  await expect(dialog.getByRole('button', { name: 'Try again', exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Choose another radio type', exact: true })).toBeVisible();
  state.phase = 'identifying';
  await dialog.getByRole('button', { name: 'Try again', exact: true }).click();
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Try again', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Choose another radio type', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Set up manually', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('heading', { name: 'Checking this radio system…', exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect.poll(() => state.requests.filter((request) => request.path.endsWith('/discovery-a') &&
    request.method === 'DELETE').length).toBe(2);
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
});

test('weak P25 with incomplete identity hands off to manual setup without claiming discovery proof', async ({ page }) => {
  const state = { phase: 'inconclusive', manualSetup: true, probeOverride: {
    c4fm: { valid_messages: 38, valid_control_messages: 38, invalid_control_messages: 169, quality_pct: 3 },
    cqpsk: { valid_messages: 0, valid_control_messages: 0, invalid_control_messages: 0, quality_pct: 0 },
    identity: null, selected_modulation: null
  } };
  await install(page, state);
  await page.evaluate(() => window.discoveryApi.openSpectrumDiscoveryWizard({
    tunerId: 'idle-a', tunerName: 'Test receiver', frequencyHz: 774706250,
    setProbeActive: (active) => window.discoveryProbeStates.push(active)
  }));
  await wizard(page).getByRole('button', { name: 'Check signal', exact: true }).click();
  await expect(wizard(page).getByRole('button', { name: 'Set up manually', exact: true })).toBeVisible();
  await wizard(page).getByRole('button', { name: 'Set up manually', exact: true }).click();
  const editor = page.locator('.channel-editor-modal').filter({ has: page.getByRole('heading', { name: 'Create Channel', exact: true }) });
  await expect(editor).toBeVisible();
  await expect(wizard(page)).toHaveCount(0);
  await expect(editor.getByRole('combobox', { name: /^Protocol / })).toHaveValue('p25-phase1');
  await expect(editor.locator('[data-channel-path="source.frequencies_hz"] input')).toHaveValue('774.70625');
  await expect(editor.getByRole('combobox', { name: 'Preferred Tuner', exact: true })).toHaveValue('Test receiver');
  await expect(editor.getByRole('combobox', { name: 'Modulation', exact: true })).toHaveValue('C4FM');
  await expect(editor.getByLabel('System', { exact: true })).toHaveValue('');
  await expect(editor.getByLabel('Site', { exact: true })).toHaveValue('');
  await expect(editor.getByText('ABCDE', { exact: true })).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => window.discoveryProbeStates)).toEqual([true, false]);
  expect(state.requests.filter((request) => request.method === 'DELETE' && request.path.endsWith('/discovery-a'))).toHaveLength(1);
  expect(state.requests.filter((request) => request.path.endsWith('/save') || request.path.endsWith('/start'))).toHaveLength(0);
  await editor.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(editor).toHaveCount(0);
  expect(state.requests.filter((request) => request.path === '/api/v1/admin/channels' && request.method === 'POST')).toHaveLength(0);
});

test('manual P25 setup waits for cancellation and preserves the wizard when cancellation fails', async ({ page }) => {
  const state = { phase: 'inconclusive', manualSetup: true, deferDeleteOnce: true, failDeleteOnce: true };
  await install(page, state);
  await begin(page);
  const manual = wizard(page).getByRole('button', { name: 'Set up manually', exact: true });
  await manual.click();
  await expect.poll(() => typeof state.releaseDelete).toBe('function');
  await expect(manual).toBeDisabled();
  await expect(page.getByRole('heading', { name: 'Create Channel', exact: true })).toHaveCount(0);
  state.releaseDelete();
  await expect(wizard(page).getByText('This signal check could not be stopped yet. Try again before setting up manually.')).toBeVisible();
  await expect(manual).toBeEnabled();
  await expect(page.getByRole('heading', { name: 'Create Channel', exact: true })).toHaveCount(0);
  await manual.click();
  await expect(page.getByRole('heading', { name: 'Create Channel', exact: true })).toBeVisible();
  await expect(wizard(page)).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => window.discoveryProbeStates)).toEqual([true, false]);
  expect(state.requests.filter((request) => request.path.endsWith('/save') || request.path.endsWith('/start'))).toHaveLength(0);
});

for (const signal of [
  { name: '38 controls and 3% score', controls: 38, rejected: 169, score: 3, phase: 'inconclusive', modulation: 'C4FM' },
  { name: 'one control and 3% score', controls: 1, rejected: 169, score: 3, phase: 'inconclusive', modulation: 'C4FM' },
  { name: 'one C4FM control and zero score', controls: 1, rejected: 169, score: 0, phase: 'inconclusive', modulation: 'C4FM' },
  { name: 'one CQPSK control and zero score', controls: 1, rejected: 169, score: 0, phase: 'inconclusive', modulation: 'CQPSK' },
  { name: 'zero decode evidence', controls: 0, rejected: 0, score: 0, phase: 'failed' }
]) {
  test(`manual weak P25 setup saves ${signal.name} without verified identity or autostart`, async ({ page }) => {
    const state = { phase: signal.phase, manualSetup: true, probeOverride: {
      c4fm: { valid_messages: signal.modulation === 'C4FM' ? signal.controls : 0,
        valid_control_messages: signal.modulation === 'C4FM' ? signal.controls : 0,
        invalid_control_messages: signal.modulation === 'C4FM' ? signal.rejected : 0,
        quality_pct: signal.modulation === 'C4FM' ? signal.score : 0 },
      cqpsk: { valid_messages: signal.modulation === 'CQPSK' ? signal.controls : 0,
        valid_control_messages: signal.modulation === 'CQPSK' ? signal.controls : 0,
        invalid_control_messages: signal.modulation === 'CQPSK' ? signal.rejected : 0,
        quality_pct: signal.modulation === 'CQPSK' ? signal.score : 0 },
      identity: null, selected_modulation: null
    } };
    await install(page, state);
    await page.evaluate(() => window.discoveryApi.openSpectrumDiscoveryWizard({
      tunerId: 'idle-a', tunerName: 'Test receiver', frequencyHz: 774706250,
      setProbeActive: (active) => window.discoveryProbeStates.push(active)
    }));
    await wizard(page).getByRole('button', { name: 'Check signal', exact: true }).click();
    await wizard(page).getByRole('button', { name: 'Set up manually', exact: true }).click();
    const editor = page.locator('.channel-editor-modal').filter({ has: page.getByRole('heading', { name: 'Create Channel', exact: true }) });
    const modulation = editor.getByRole('combobox', { name: 'Modulation', exact: true });
    await expect(modulation).toHaveValue(signal.modulation || 'CQPSK');
    if (!signal.modulation) await modulation.selectOption('C4FM');
    await editor.getByLabel('Name', { exact: true }).fill('Weak Control');
    await editor.getByRole('button', { name: 'Create channel', exact: true }).click();
    await expect.poll(() => state.requests.filter((request) => request.path === '/api/v1/admin/channels' &&
      request.method === 'POST').length).toBe(1);
    const saved = state.requests.find((request) => request.path === '/api/v1/admin/channels' && request.method === 'POST').body;
    expect(saved.protocol_id).toBe('p25-phase1');
    expect(saved.name).toBe('Weak Control');
    expect(saved.source.frequencies_hz).toEqual([774706250]);
    expect(saved.source.preferred_tuner).toBe('Test receiver');
    expect(saved.settings.modulation).toBe(signal.modulation || 'C4FM');
    expect(saved.observed?.p25_site_identity).toBeUndefined();
    expect(saved.auto_start_order).toBeUndefined();
    await expect.poll(() => page.evaluate(() => window.discoveryProbeStates)).toEqual([true, false]);
    expect(state.requests.filter((request) => request.method === 'DELETE' && request.path.endsWith('/discovery-a'))).toHaveLength(1);
    expect(state.requests.filter((request) => request.path.endsWith('/save') || request.path.endsWith('/start') ||
      request.path.endsWith('/actions') && request.body.action === 'START')).toHaveLength(0);
  });
}

for (const protocolId of ['dmr', 'nxdn']) {
  test(`inconclusive ${protocolId.toUpperCase()} does not offer the P25 manual handoff`, async ({ page }) => {
    const state = { phase: 'inconclusive' };
    await install(page, state);
    await begin(page, protocolId);
    await expect(wizard(page).getByRole('button', { name: 'Try again', exact: true })).toBeVisible();
    await expect(wizard(page).getByRole('button', { name: 'Set up manually', exact: true })).toHaveCount(0);
    await expect(wizard(page).getByText('Decode score', { exact: true })).toHaveCount(0);
    await expect(wizard(page).getByText(/A low score can still confirm/)).toHaveCount(0);
  });
}

test('recovering P25 probe status restores the scanning footer', async ({ page }) => {
  const state = { phase: 'identifying', failProbeStatusOnce: true };
  await install(page, state);
  await begin(page);
  const dialog = wizard(page);
  await expect(dialog.getByRole('button', { name: 'Retry connection', exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Retry connection', exact: true }).click();
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Retry connection', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('heading', { name: 'Checking this radio system…', exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(dialog).toHaveCount(0);
});

test('a setup that expires during review cannot add a channel and can retry signal checking', async ({ page }) => {
  const state = {};
  await page.clock.install();
  await install(page, state);
  await begin(page);
  await review(page);
  const dialog = wizard(page);
  await dialog.getByLabel('Channel name', { exact: true }).fill('County Control');
  state.phase = 'failed';
  state.reason = 'The signal check has expired.';
  await page.clock.fastForward(11000);
  await expect(dialog.getByRole('heading', { name: 'We couldn’t identify this signal', exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Add and start listening', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Choose another radio type', exact: true })).toBeVisible();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
  state.phase = 'ready';
  state.reason = null;
  await dialog.getByRole('button', { name: 'Try again', exact: true }).click();
  await expect(dialog.getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveCount(0);
  await review(page);
  await expect(dialog.getByRole('button', { name: 'Add and start listening', exact: true })).toBeEnabled();
  expect(state.requests.filter((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
    request.method === 'POST')).toHaveLength(2);
});

test('a delayed status from the previous radio type cannot replace a new signal check', async ({ page }) => {
  const state = {};
  await page.clock.install();
  await install(page, state);
  await begin(page, 'am');
  await expect(wizard(page).getByRole('heading', { name: 'Ready for audio', exact: true })).toBeVisible();
  state.phase = 'failed';
  state.reason = 'The old AM setup has expired.';
  state.deferStatusOnce = true;
  await page.clock.fastForward(11000);
  await expect.poll(() => typeof state.releaseStatus).toBe('function');
  const dialog = wizard(page);
  await dialog.getByRole('button', { name: 'Back', exact: true }).click();
  state.phase = 'ready';
  state.reason = null;
  await dialog.getByRole('radio', { name: 'P25 radio system', exact: true }).check();
  await dialog.getByRole('button', { name: 'Check signal', exact: true }).click();
  await expect(dialog.getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  const response = page.waitForResponse((candidate) => candidate.request().method() === 'GET' &&
    new URL(candidate.url()).pathname.endsWith('/spectrum-discovery/discovery-a'));
  state.releaseStatus();
  await response;
  await page.waitForTimeout(100);
  await expect(dialog.getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  await expect(dialog.getByRole('heading', { name: 'AM radio setup interrupted', exact: true })).toHaveCount(0);
  await review(page);
  const opens = state.requests.filter((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
    request.method === 'POST');
  expect(opens.map((request) => request.body.protocol_id)).toEqual(['am', 'p25-phase1']);
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
});

test('an expired session response requires a fresh check instead of leaving Add available', async ({ page }) => {
  const state = {};
  await page.clock.install();
  await install(page, state);
  await begin(page);
  await review(page);
  state.expiredStatus = true;
  await page.clock.fastForward(11000);
  const dialog = wizard(page);
  await expect(dialog.getByRole('button', { name: 'Try again', exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Add and start listening', exact: true })).toHaveCount(0);
  state.expiredStatus = false;
  await dialog.getByRole('button', { name: 'Try again', exact: true }).click();
  await expect(dialog.getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
});

test('a saved channel requiring a receiver restart never offers another Add or start attempt', async ({ page }) => {
  const state = { restartRequired: true };
  await install(page, state);
  await begin(page);
  await review(page);
  const dialog = wizard(page);
  await dialog.getByRole('button', { name: 'Add and start listening', exact: true }).click();
  await expect(dialog.getByRole('heading', { name: 'Channel added', exact: true })).toBeVisible();
  await expect(dialog).toContainText('Your channel was saved, but the receiver needs to restart');
  await expect(dialog.getByRole('button', { name: 'Try adding again', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Try starting again', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('link', { name: 'Open channels', exact: true })).toBeVisible();
  await expect(dialog.locator('.spectrum-discovery-progress')).toBeHidden();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(1);
  await dialog.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(dialog).toHaveCount(0);
});

test('ambiguous aliases require a choice; saved start failure retries without recreating', async ({ page }) => {
  const state = { ambiguous: true, failStart: true };
  await install(page, state);
  await begin(page);
  await review(page);
  const dialog = wizard(page);
  await expect(dialog.getByLabel('Alias List', { exact: true })).toHaveValue('');
  await expect(dialog.getByLabel('Alias List', { exact: true }).locator('option[value="new"]')).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Add and start listening' }).click();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
  await dialog.getByLabel('Alias List', { exact: true }).selectOption('22');
  await dialog.getByRole('button', { name: 'Add and start listening' }).click();
  await expect(dialog.getByRole('heading', { name: 'Channel added' })).toBeVisible();
  await expect(dialog).toContainText('Tuner capacity is in use.');
  await dialog.getByRole('button', { name: 'Try starting again' }).click();
  await expect(dialog.getByRole('heading', { name: 'Ready to listen' })).toBeVisible();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(1);
  expect(state.requests.filter((request) => request.path.endsWith('/start'))).toHaveLength(1);
  const save = state.requests.find((request) => request.path.endsWith('/save')).body;
  expect(save.alias_list_id).toBe(22);
  expect(save.new_alias_list_name).toBeNull();
  await dialog.getByRole('button', { name: 'Done', exact: true }).click();
  await expect.poll(() => state.requests.some((request) => request.path.endsWith('/discovery-a') &&
    request.method === 'DELETE')).toBe(true);
});

test('one P25 identity match presents its Alias List without a choice and reuses it', async ({ page }) => {
  const state = { singleMatch: true };
  await install(page, state);
  await begin(page);
  await review(page);
  const dialog = wizard(page);
  await expect(dialog).toContainText('County aliases');
  await expect(dialog.getByRole('combobox', { name: 'Alias List', exact: true })).toHaveCount(0);
  await expect(dialog.getByLabel('New Alias List name', { exact: true })).toBeHidden();
  await dialog.getByRole('button', { name: 'Add and start listening' }).click();
  await expect(dialog.getByRole('heading', { name: 'Ready to listen' })).toBeVisible();
  expect(state.requests.find((request) => request.path.endsWith('/save')).body.alias_list_id).toBe(21);
});

test('stale save refreshes choices and preserves the draft before an explicit retry', async ({ page }) => {
  const state = { staleOnce: true };
  await install(page, state);
  await begin(page);
  await review(page);
  const dialog = wizard(page);
  await dialog.getByLabel('Channel name', { exact: true }).fill('County Control');
  await dialog.getByLabel('New Alias List name', { exact: true }).fill('County aliases');
  await dialog.getByRole('button', { name: 'Add and start listening' }).click();
  await expect(dialog).toContainText('Your saved choices changed. Check the Alias List and try adding again.');
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveValue('County Control');
  await expect(dialog.getByLabel('New Alias List name', { exact: true })).toHaveValue('County aliases');
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(1);
  await dialog.getByRole('button', { name: 'Add and start listening' }).click();
  await expect(dialog.getByRole('heading', { name: 'Ready to listen' })).toBeVisible();
  const saves = state.requests.filter((request) => request.path.endsWith('/save'));
  expect(saves.map((request) => request.body.revision)).toEqual([7, 8]);
  expect(saves[1].body.alias_list_id).toBe(0);
  expect(saves[1].body.name).toBe('County Control');
});

test('retrying a failed add preserves names and listening choices without adding twice automatically', async ({ page }) => {
  const state = { failSaveOnce: true };
  await install(page, state);
  await begin(page);
  await review(page);
  const dialog = wizard(page);
  await dialog.getByLabel('Channel name', { exact: true }).fill('County Control');
  await dialog.getByLabel('New Alias List name', { exact: true }).fill('County listening');
  await disclose(page, 'Additional labels (optional)');
  await dialog.getByLabel('System', { exact: true }).fill('County Radio');
  await dialog.getByLabel('Site', { exact: true }).fill('North');
  await dialog.getByRole('button', { name: 'Add and start listening', exact: true }).click();
  await expect(dialog.getByText('We couldn’t add your channel. Your choices are still here. Try again.', { exact: true }))
    .toBeVisible();
  await expect(dialog.getByText('Could not add this channel. Try again.', { exact: true })).toBeHidden();
  await expect(dialog.getByRole('button', { name: 'Try adding again', exact: true })).toBeEnabled();
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveValue('County Control');
  await expect(dialog.getByLabel('New Alias List name', { exact: true })).toHaveValue('County listening');
  await expect(dialog.getByLabel('System', { exact: true })).toHaveValue('County Radio');
  await expect(dialog.getByLabel('Site', { exact: true })).toHaveValue('North');
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(1);
  await dialog.getByRole('button', { name: 'Try adding again', exact: true }).click();
  await expect(dialog.getByRole('heading', { name: 'Ready to listen', exact: true })).toBeVisible();
  await expect(dialog).toContainText('County Control');
  await expect(dialog).toContainText('Test receiver');
  await expect(dialog).toContainText('851.012500 MHz');
  await expect(dialog).toContainText('P25 radio system');
  await expect(dialog).toContainText('County listening');
  await expect(dialog.getByText('Open channel', { exact: true })).toBeVisible();
  const saves = state.requests.filter((request) => request.path.endsWith('/save'));
  expect(saves).toHaveLength(2);
  expect(saves[1].body).toEqual(saves[0].body);
  expect(saves[1].body).toMatchObject({ name: 'County Control', system: 'County Radio', site: 'North',
    alias_list_id: 0, new_alias_list_name: 'County listening' });
  expect(state.requests.filter((request) => request.path.endsWith('/start'))).toHaveLength(0);
});

test('going Back with no analog Alias List selected does not silently create a new Alias List', async ({ page }) => {
  const state = { ambiguous: true };
  await install(page, state);
  await begin(page, 'am');
  await review(page, 'am');
  const dialog = wizard(page);
  const choices = dialog.getByLabel('Alias List', { exact: true });
  await expect(choices).toHaveValue('');
  await dialog.getByRole('button', { name: 'Back', exact: true }).click();
  await review(page, 'am');
  await expect(choices).toHaveValue('');
  await dialog.getByRole('button', { name: 'Add and start listening', exact: true }).click();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
  await choices.selectOption('21');
  await dialog.getByRole('button', { name: 'Add and start listening', exact: true }).click();
  await expect(dialog.getByRole('heading', { name: 'Ready to listen', exact: true })).toBeVisible();
  expect(state.requests.find((request) => request.path.endsWith('/save')).body.alias_list_id).toBe(21);
});

test('FM defaults are unambiguous and edited audio settings survive review and Back', async ({ page }) => {
  const state = {};
  await install(page, state);
  await begin(page, 'nbfm');
  const dialog = wizard(page);
  await expect(dialog.getByRole('heading', { name: 'Ready for audio', exact: true })).toBeVisible();
  expect(await dialog.innerText()).toContain('12.5 kHz');
  await expect(dialog.getByLabel('De-emphasis', { exact: true })).toBeHidden();
  await disclose(page, 'Adjust audio settings');
  const bandwidth = dialog.getByLabel('Bandwidth', { exact: true });
  const emphasis = dialog.getByLabel('De-emphasis', { exact: true });
  await expect(bandwidth).toHaveValue('BW_12_5');
  await expect(emphasis).toHaveValue('NONE');
  await expect(emphasis.locator('option').filter({ hasText: /^None$/ })).toHaveCount(1);
  await expect(dialog.getByRole('group', { name: 'Audio', exact: true })).toBeVisible();
  await expect(dialog.getByRole('group', { name: 'Squelch', exact: true })).toBeVisible();
  await bandwidth.selectOption('BW_25_0');
  await emphasis.selectOption('US_750US');
  await review(page, 'nbfm');
  expect(await dialog.innerText()).toContain('25.0 kHz');
  expect(await dialog.innerText()).toContain('750 µs');
  await dialog.getByLabel('Channel name', { exact: true }).fill('County dispatch');
  await dialog.getByLabel('New Alias List name', { exact: true }).fill('Dispatch listening');
  await dialog.getByRole('button', { name: 'Back', exact: true }).click();
  await disclose(page, 'Adjust audio settings');
  await expect(bandwidth).toHaveValue('BW_25_0');
  await expect(emphasis).toHaveValue('US_750US');
  await bandwidth.selectOption('BW_7_5');
  await review(page, 'nbfm');
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveValue('County dispatch');
  await expect(dialog.getByLabel('New Alias List name', { exact: true })).toHaveValue('Dispatch listening');
  const visibleReview = await dialog.innerText();
  expect(visibleReview).toContain('7.5 kHz');
  expect(visibleReview).toContain('750 µs');
  expect(visibleReview).not.toContain('25.0 kHz');
  await dialog.getByRole('button', { name: 'Add and start listening', exact: true }).click();
  await expect(dialog.getByRole('heading', { name: 'Ready to listen', exact: true })).toBeVisible();
  await expect(dialog).toContainText('County dispatch');
  await expect(dialog).toContainText('FM two-way radio');
  await expect(dialog).toContainText('Dispatch listening');
  expect(state.requests.find((request) => request.path.endsWith('/save')).body)
    .toMatchObject({ name: 'County dispatch', new_alias_list_name: 'Dispatch listening',
      settings: { bandwidth: 'BW_7_5', deemphasis: 'US_750US' } });
});

for (const protocolId of ['am', 'nbfm']) {
  test(`${protocolId} can be added with the audio settings disclosure left closed`, async ({ page }) => {
    const state = {};
    await install(page, state);
    await begin(page, protocolId);
    const dialog = wizard(page);
    await expect(dialog.getByLabel('Bandwidth', { exact: true })).toBeHidden();
    await expect(dialog.locator('summary').filter({ hasText: /^Adjust audio settings$/ }).locator('..'))
      .toHaveJSProperty('open', false);
    await review(page, protocolId);
    await dialog.getByRole('button', { name: 'Add and start listening', exact: true }).click();
    await expect(dialog.getByRole('heading', { name: 'Ready to listen', exact: true })).toBeVisible();
    const save = state.requests.find((request) => request.path.endsWith('/save')).body;
    expect(save.settings).toEqual(snapshot(protocolId, {}).review.template.settings);
    expect(save.alias_list_id).toBe(0);
    expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(1);
  });
}

for (const [protocolId, theme, width] of [['am', 'light', 1280], ['nbfm', 'dark', 390]]) {
  test(`${protocolId} manifest settings and review fit ${theme} at ${width}px`, async ({ page }) => {
    const state = { theme };
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ colorScheme: theme });
    await install(page, state);
    await begin(page, protocolId);
    const dialog = wizard(page);
    await expect(dialog.getByRole('heading', { name: 'Ready for audio', exact: true })).toBeVisible();
    const bandwidth = dialog.getByLabel('Bandwidth', { exact: true });
    await expect(bandwidth).toBeHidden();
    await disclose(page, 'Adjust audio settings');
    await expect(bandwidth).toBeVisible();
    await bandwidth.selectOption('BW_25_0');
    await review(page, protocolId);
    await expect(dialog.getByLabel('New Alias List name', { exact: true })).toBeVisible();
    const bounds = await dialog.boundingBox();
    expect(bounds.x).toBeGreaterThanOrEqual(0);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(width);
    await expect(dialog).toHaveScreenshot(`spectrum-discovery-${protocolId}-${theme}.png`);
    await dialog.getByRole('button', { name: 'Add and start listening' }).click();
    await expect(dialog.getByRole('heading', { name: 'Ready to listen' })).toBeVisible();
    const save = state.requests.find((request) => request.path.endsWith('/save')).body;
    expect(save.settings.bandwidth).toBe('BW_25_0');
    expect(save.alias_list_id).toBe(0);
    expect(save.new_alias_list_name).toBe(protocolId === 'am' ? 'AM channel' : 'FM channel');
  });
}

for (const theme of ['light', 'dark']) {
  for (const width of [1280, 390]) {
    test(`weak P25 manual choice fits ${theme} at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await page.emulateMedia({ colorScheme: theme });
      const state = { theme, phase: 'inconclusive', probeOverride: {
        c4fm: { valid_messages: 38, valid_control_messages: 38, invalid_control_messages: 169, quality_pct: 3 },
        cqpsk: { valid_messages: 0, valid_control_messages: 0, invalid_control_messages: 0, quality_pct: 0 },
        identity: null, selected_modulation: null
      } };
      await install(page, state);
      await begin(page);
      await disclose(page, 'Signal details');
      const dialog = wizard(page);
      await expect(dialog.getByRole('button', { name: 'Set up manually', exact: true })).toBeVisible();
      await expect(dialog.getByRole('columnheader', { name: 'Decode score', exact: true })).toBeVisible();
      const fits = await dialog.evaluate((element) => {
        const rect = element.getBoundingClientRect();
        return rect.left >= 0 && rect.right <= innerWidth && element.scrollWidth <= element.clientWidth + 1 &&
          [...element.querySelectorAll('.ui-modal-footer button')].every((button) => {
            const bounds = button.getBoundingClientRect();
            return bounds.left >= rect.left && bounds.right <= rect.right;
          });
      });
      expect(fits).toBe(true);
      await dialog.screenshot({ path: test.info().outputPath(`weak-p25-${theme}-${width}.png`) });
    });
  }
}

for (const protocolId of ['am', 'nbfm']) {
  test(`${protocolId} interrupted setup stays protocol-specific and retries the selected protocol`, async ({ page }) => {
    const state = {};
    await page.clock.install();
    await install(page, state);
    await begin(page, protocolId);
    const dialog = wizard(page);
    await expect(dialog.getByRole('heading', { name: 'Ready for audio', exact: true })).toBeVisible();
    state.phase = 'failed';
    state.reason = 'The selected tuner is unavailable.';
    await page.clock.fastForward(11000);
    await expect(dialog.getByRole('heading', { name: /setup interrupted/ })).toBeVisible();
    await expect(dialog).toContainText('The selected tuner is unavailable.');
    await expect(dialog).not.toContainText('P25');
    await expect(dialog).not.toContainText('C4FM');
    await expect(dialog).not.toContainText('CQPSK');
    await expect(dialog.getByRole('button', { name: 'Choose another radio type', exact: true })).toBeVisible();
    state.phase = 'ready';
    state.reason = null;
    await dialog.getByRole('button', { name: 'Try again', exact: true }).click();
    await expect(dialog.getByRole('heading', { name: 'Ready for audio', exact: true })).toBeVisible();
    const opens = state.requests.filter((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
      request.method === 'POST');
    expect(opens.map((request) => request.body.protocol_id)).toEqual([protocolId, protocolId]);
    expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
  });
}

test('a fractional Hertz spectrum selection displays and submits the same integer frequency', async ({ page }) => {
  const state = {};
  await install(page, state);
  await page.evaluate(() => window.discoveryApi.openTunerFrequencyActions({
    tunerId: 'idle-a', targetId: 'target-a', browseLeaseId: 'browse-a',
    frequencyHz: 776715007.9617834, rawFrequencyHz: 776715007.9617834
  }));
  const add = page.getByRole('button', { name: 'Add channel / system', exact: true });
  await expect(add).toBeEnabled();
  await add.click();
  const dialog = wizard(page);
  await expect(dialog.locator('.spectrum-discovery-context strong')).toHaveText('776.715008 MHz');
  await dialog.getByRole('button', { name: 'Check signal', exact: true }).click();
  await review(page);
  await expect(dialog.getByRole('heading', { name: 'Name your channel' })).toBeVisible();
  await expect(dialog.locator('.spectrum-discovery-context strong')).toHaveText('776.715008 MHz');
  expect(state.requests.find((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
    request.method === 'POST').body.frequency_hz).toBe(776715008);
  await dialog.getByRole('button', { name: 'Close Add a channel', exact: true }).click();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
});

test('configured or learned channel frequency blocks Add using receiver eligibility', async ({ page }) => {
  const state = { known: true };
  await install(page, state);
  await page.evaluate(() => window.discoveryApi.openTunerFrequencyActions({
    tunerId: 'idle-a', targetId: 'target-a', frequencyHz: 851012500
  }));
  const add = page.getByRole('button', { name: 'Add channel / system', exact: true });
  await expect(add).toBeDisabled();
  const popover = page.locator('.tuner-frequency-popover');
  await expect(popover).toContainText('This frequency belongs to County Control.');
  await expect(popover.getByRole('link', { name: 'County Control', exact: true }))
    .toHaveAttribute('href', /view=channel.*configuration_id=channel-a/);
  expect(state.requests.filter((request) => request.path.endsWith('/eligibility'))).toHaveLength(1);
});

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`P25 discovery gallery ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`/design-system.html?view=spectrum-discovery&theme=${theme}`);
    await expect(page.locator('.spectrum-discovery-modal')).toBeVisible();
    await expect(page.locator('.spectrum-discovery-modal')).toHaveScreenshot(`spectrum-discovery-p25-${theme}.png`);
  });
}
