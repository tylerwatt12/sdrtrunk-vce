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
  const analogName = protocolId === 'am' ? 'AM channel' : 'FM channel';
  const settings = Object.fromEntries(profile.sections.flatMap((section) => section.fields)
    .filter((field) => field.path.startsWith('settings.') && Object.hasOwn(field, 'default'))
    .map((field) => [field.path.substring(9), field.default]));
  return { session_id: 'discovery-a', state: state.saved ? state.running ? 'running' : 'saved' : state.phase || 'ready', reason: state.reason || null,
    protocol_id: protocolId,
    tuner_id: 'idle-a', target_id: 'target-a', frequency_hz: state.frequencyHz || 851012500,
    expires_at_ms: Date.now() + 30000,
    probe: isP25 ? {
      c4fm: { valid_messages: 18, valid_control_messages: 12, invalid_control_messages: 4, quality_pct: 81.2 },
      cqpsk: { valid_messages: 52, valid_control_messages: 41, invalid_control_messages: 1, quality_pct: 99.1,
        ...state.cqpskMetrics },
      elapsed_ms: 1500, timeout_ms: 15000,
      selected_modulation: state.phase === 'identifying' ? null : 'CQPSK',
      identity: state.phase === 'identifying' ? null : { wacn: 0xb0001, system: 0x123, rfss: 1, site: 2 }
    } : null,
    review: state.saved || (state.phase && state.phase !== 'ready') ? null : {
      revision: state.revision || 7, template: { protocol_id: protocolId, name: isP25 ? 'Control' : analogName,
        system: isP25 ? 'P25 B0001-123' : '', site: isP25 ? 'RFSS 1 Site 2' : '', settings },
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
        appearance: { theme: state.theme || 'light' } } }) });
    if (path === '/api/v1/spectrum-snap-presets') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, country_code: 'US', country_label: 'United States',
        countries: [{ code: 'US', label: 'United States' }], scopes: [] }) });
    if (path === '/api/v1/admin/tuners') return respond({ tuners: [state.tuner] });
    if (path.startsWith('/api/v1/admin/tuners/idle-a/settings/')) {
      const setting = state.tuner.settings.find((candidate) => candidate.id === path.split('/').at(-1));
      if (state.rejectLock && setting.id === 'center_frequency_locked') return route.fulfill({
        status: 409, contentType: 'application/json',
        body: JSON.stringify({ error: { code: 'busy', message: 'Could not change center lock.' } }) });
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
    if (path === '/api/v1/admin/channels/protocols') return respond(protocols);
    if (path.endsWith('/eligibility')) return respond({ eligible: !state.known,
      reason: state.known ? 'This frequency belongs to County Control.' : null, matches: [] });
    if (path === '/api/v1/admin/spectrum-discovery' && request.method() === 'POST') {
      state.protocolId = body.protocol_id;
      state.frequencyHz = body.frequency_hz;
      return respond(snapshot(state.protocolId, state));
    }
    if (path === '/api/v1/admin/spectrum-discovery/discovery-a') {
      if (request.method() === 'DELETE') return respond(null, 204);
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
const radioTypes = { 'p25-phase1': 'P25 radio system', am: 'AM radio', nbfm: 'FM two-way radio' };

async function begin(page, protocolId = 'p25-phase1') {
  await page.evaluate(() => window.openDiscovery());
  await wizard(page).getByRole('radio', { name: radioTypes[protocolId], exact: true }).check();
  await wizard(page).getByRole('button', { name: protocolId === 'p25-phase1' ? 'Check signal' : 'Continue',
    exact: true }).click();
}

async function review(page, protocolId = 'p25-phase1') {
  await wizard(page).getByRole('button', { name: protocolId === 'p25-phase1' ? 'Review channel' : 'Continue',
    exact: true }).click();
  await expect(wizard(page).getByRole('heading', { name: 'Name your channel', exact: true })).toBeVisible();
}

async function disclose(page, label) {
  const escaped = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  await wizard(page).locator('summary').filter({ hasText: new RegExp(`^${escaped}$`) }).click();
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Spectrum center lock unlocks tuning with its lease in ${theme} at ${width}px`, async ({ page }) => {
    const state = { locked: true, theme };
    await page.setViewportSize({ width, height: 900 });
    await install(page, state);
    const lock = page.locator('.spectrum-browse-center').getByRole('checkbox', { name: 'Lock center' });
    const digit = page.getByRole('button', { name: 'Decrease 100 MHz place', includeHidden: true });
    await expect(lock).toBeChecked();
    await expect(lock).toBeEnabled();
    await expect(digit).toBeDisabled();
    await lock.press('Space');
    await expect(digit).toBeEnabled();
    await expect(lock).toBeFocused();
    expect(state.requests.find((request) => request.path.endsWith('/settings/center_frequency_locked')).body)
      .toEqual({ value: false, lease_id: 'browse-a' });
    await digit.click();
    await expect(page.locator('.spectrum-browse-center')).toContainText('0751.01250MHz');
    expect(state.requests.find((request) => request.path.endsWith('/settings/frequency_mhz')).body)
      .toEqual({ value: 751.0125, lease_id: 'browse-a' });
    await lock.press('Space');
    await expect(digit).toBeDisabled();
  });
}

test('a rejected Spectrum center lock restores the confirmed switch and allows retry', async ({ page }) => {
  const state = { locked: true, rejectLock: true };
  await install(page, state);
  const lock = page.locator('.spectrum-browse-center').getByRole('checkbox', { name: 'Lock center' });
  await lock.press('Space');
  await expect(lock).toBeChecked();
  await expect(lock).toBeEnabled();
  await expect(page.locator('.spectrum-browse-message')).toContainText('Could not change center lock.');
  state.rejectLock = false;
  await lock.press('Space');
  await expect(lock).not.toBeChecked();
  await expect(page.getByRole('button', { name: 'Decrease 100 MHz place', includeHidden: true })).toBeEnabled();
});

test('active monitoring keeps the Spectrum center lock disabled', async ({ page }) => {
  await install(page, { locked: true, running: true });
  await expect(page.locator('.spectrum-browse-center').getByRole('checkbox', { name: 'Lock center' })).toBeDisabled();
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

test('managed Spectrum keeps its header actions and updates one persistent frequency rail', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const state = { liveSpectrum: true };
  await install(page, state);

  const heading = page.getByRole('heading', { name: 'Tuner Spectrum', exact: true });
  const header = page.locator('.page-header').filter({ has: heading });
  const toolbar = page.locator('.spectrum-browse-toolbar');
  const panel = page.locator('.spectrum-browse-panel');
  const rail = page.locator('.spectrum-browse-control-rail');
  const lock = toolbar.getByRole('checkbox', { name: 'Lock center', exact: true });

  await expect(lock).toHaveCount(1);
  await expect(page.getByText('Keep tuner here', { exact: true })).toHaveCount(0);
  await expect(header.getByRole('button', { name: 'Find P25 channels', exact: true })).toBeVisible();
  await expect(toolbar.getByRole('button', { name: 'Find P25 channels', exact: true })).toHaveCount(0);
  await expect(rail).toHaveCount(1);
  await expect(rail.getByText('Select a signal', { exact: true })).toBeVisible();
  await expect(rail).toContainText(
    'Click a signal in the spectrum or waterfall to see its frequency and available actions.');
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
  expect(state.requests.filter((request) => request.path.endsWith('/eligibility'))).toHaveLength(1);

  await page.setViewportSize({ width: 900, height: 900 });
  await expect.poll(() => page.evaluate(() => {
    const spectrum = document.querySelector('.spectrum-browse-panel').getBoundingClientRect();
    const actions = document.querySelector('.spectrum-browse-control-rail').getBoundingClientRect();
    return { aligned: Math.abs(actions.x - spectrum.x) <= 1, stacked: actions.y >= spectrum.bottom };
  })).toEqual({ aligned: true, stacked: true });
  await panel.getByRole('img', { name: 'Tuner frequency spectrum', exact: true })
    .click({ position: { x: 180, y: 80 } });
  await expect.poll(() => rail.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return bounds.top >= 0 && bounds.top < window.innerHeight;
  })).toBe(true);
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
  await expect(types.getByRole('radio')).toHaveCount(3);
  await expect(types.getByRole('radio', { name: 'P25 radio system', exact: true })).toBeChecked();
  await expect(dialog.locator('summary').filter({ hasText: /^Help me choose$/ })).toBeVisible();
  expect(await dialog.innerText()).not.toMatch(/C4FM|CQPSK|WACN|RFSS|SysID|NAC|Alias List/);
  await dialog.getByRole('button', { name: 'Check signal', exact: true }).click();
  await expect(dialog.getByRole('heading', { name: 'Ready to add', exact: true })).toBeVisible();
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Review channel', exact: true })).toBeVisible();
  expect(await dialog.innerText()).not.toMatch(/C4FM|CQPSK|WACN|RFSS|SysID|NAC|Alias List/);
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
  await review(page);
  await expect(dialog.getByLabel('Channel name', { exact: true })).toBeVisible();
  await expect(dialog.getByLabel('System', { exact: true })).toBeHidden();
  await expect(dialog.getByLabel('Site', { exact: true })).toBeHidden();
  await expect(dialog.locator('summary').filter({ hasText: /^Additional labels \(optional\)$/ })).toBeVisible();
  await expect(dialog.locator('summary').filter({ hasText: /^What are these settings\?$/ })).toBeVisible();
  expect(await dialog.innerText()).not.toMatch(/C4FM|CQPSK|WACN|RFSS|SysID|NAC|Alias List/);
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
  await expect(dialog.getByRole('heading', { name: 'Ready to add', exact: true })).toBeVisible();
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
  await disclose(page, 'Technical details');
  const table = wizard(page).getByRole('table');
  await expect(table.getByRole('columnheader')).toHaveText([
    'Signal setting', 'Valid messages', 'Control messages', 'Invalid control', 'Message quality'
  ]);
  await expect(table.locator('tbody tr').nth(0).getByRole('cell')).toHaveText(['18', '12', '4', '81%']);
  await expect(table.locator('tbody tr').nth(1).getByRole('cell')).toHaveText(['52', '41', '1', '99%']);
  await expect(table.getByRole('rowheader', { name: 'C4FM', exact: true })).toBeVisible();
  await expect(table.getByRole('rowheader', { name: 'CQPSK', exact: true })).toBeVisible();
  await expect(wizard(page).getByText(/Message quality is the proportion of messages/)).toBeVisible();
  await expect(wizard(page).getByText(/Message quality is the proportion of messages/)).toContainText('not a confidence score');
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
  expect(await page.evaluate(() => window.discoveryProbeStates)).toEqual([true, false]);
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
  await expect(dialog.getByRole('heading', { name: 'Checking this radio system…', exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect.poll(() => state.requests.filter((request) => request.path.endsWith('/discovery-a') &&
    request.method === 'DELETE').length).toBe(2);
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
});

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
  await expect(dialog.getByRole('heading', { name: 'Ready to add', exact: true })).toBeVisible();
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
  await expect(dialog.getByRole('heading', { name: 'Ready to add', exact: true })).toBeVisible();
  const response = page.waitForResponse((candidate) => candidate.request().method() === 'GET' &&
    new URL(candidate.url()).pathname.endsWith('/spectrum-discovery/discovery-a'));
  state.releaseStatus();
  await response;
  await page.waitForTimeout(100);
  await expect(dialog.getByRole('heading', { name: 'Ready to add', exact: true })).toBeVisible();
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
  await expect(dialog.getByRole('heading', { name: 'Ready to add', exact: true })).toBeVisible();
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
  await expect(dialog.getByLabel('Names and listening settings', { exact: true })).toHaveValue('');
  await expect(dialog.getByLabel('Names and listening settings', { exact: true }).locator('option[value="new"]')).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Add and start listening' }).click();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
  await dialog.getByLabel('Names and listening settings', { exact: true }).selectOption('22');
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

test('one P25 identity match presents its listening settings without a choice and reuses them', async ({ page }) => {
  const state = { singleMatch: true };
  await install(page, state);
  await begin(page);
  await review(page);
  const dialog = wizard(page);
  await expect(dialog).toContainText('County aliases');
  await expect(dialog.getByRole('combobox', { name: 'Names and listening settings', exact: true })).toHaveCount(0);
  await expect(dialog.getByLabel('New settings name', { exact: true })).toBeHidden();
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
  await dialog.getByLabel('New settings name', { exact: true }).fill('County aliases');
  await dialog.getByRole('button', { name: 'Add and start listening' }).click();
  await expect(dialog).toContainText('Your saved choices changed. Check the listening settings and try adding again.');
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveValue('County Control');
  await expect(dialog.getByLabel('New settings name', { exact: true })).toHaveValue('County aliases');
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
  await dialog.getByLabel('New settings name', { exact: true }).fill('County listening');
  await disclose(page, 'Additional labels (optional)');
  await dialog.getByLabel('System', { exact: true }).fill('County Radio');
  await dialog.getByLabel('Site', { exact: true }).fill('North');
  await dialog.getByRole('button', { name: 'Add and start listening', exact: true }).click();
  await expect(dialog.getByText('We couldn’t add your channel. Your choices are still here. Try again.', { exact: true }))
    .toBeVisible();
  await expect(dialog.getByText('Could not add this channel. Try again.', { exact: true })).toBeHidden();
  await expect(dialog.getByRole('button', { name: 'Try adding again', exact: true })).toBeEnabled();
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveValue('County Control');
  await expect(dialog.getByLabel('New settings name', { exact: true })).toHaveValue('County listening');
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

test('going Back with no analog listening settings selected does not silently create new settings', async ({ page }) => {
  const state = { ambiguous: true };
  await install(page, state);
  await begin(page, 'am');
  await review(page, 'am');
  const dialog = wizard(page);
  const choices = dialog.getByLabel('Names and listening settings', { exact: true });
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
  await dialog.getByLabel('New settings name', { exact: true }).fill('Dispatch listening');
  await dialog.getByRole('button', { name: 'Back', exact: true }).click();
  await disclose(page, 'Adjust audio settings');
  await expect(bandwidth).toHaveValue('BW_25_0');
  await expect(emphasis).toHaveValue('US_750US');
  await bandwidth.selectOption('BW_7_5');
  await review(page, 'nbfm');
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveValue('County dispatch');
  await expect(dialog.getByLabel('New settings name', { exact: true })).toHaveValue('Dispatch listening');
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
    await expect(dialog.getByLabel('New settings name', { exact: true })).toBeVisible();
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
  await expect(page.locator('.tuner-frequency-popover')).toContainText('This frequency belongs to County Control.');
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
