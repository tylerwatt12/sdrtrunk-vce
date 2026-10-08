const { expect, test } = require('@playwright/test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');
const { readFileSync } = require('node:fs');

const root = resolve(__dirname, '../../../../..');
const CENTER_HZ = 451000000;
const RATE_HZ = 2400000;

function recording(tunerClass = 'recording_tuner') {
  return {
    id: 'recording-a', name: 'Test baseband.wav', tuner_class: tunerClass,
    status: 'enabled', available: true, operator_state: 'in_use', channel_count: 1,
    frequency_hz: CENTER_HZ, sample_rate_hz: RATE_HZ,
    spectrum_target_id: 'recording-target', settings: []
  };
}

// Exercise the production multiplex, STATE and FFT decoders; no receiver endpoint is contacted.
async function installStream(page) {
  await page.addInitScript(({ centerHz, sampleRateHz }) => {
    const nativeFetch = window.fetch.bind(window);
    const encoder = new TextEncoder();
    const streams = new Set();
    let sequence = 0;
    let generation = 0;
    const multiplex = (topic, kind, payload) => {
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
    const diagnostic = (type, payload, count = 0) => {
      const frame = new Uint8Array(72 + payload.byteLength);
      const header = new DataView(frame.buffer);
      header.setUint32(0, 0x53444447, true);
      header.setUint8(4, 1);
      header.setUint8(5, type);
      header.setUint16(6, 72, true);
      header.setUint32(8, payload.byteLength, true);
      header.setUint32(12, count, true);
      header.setBigInt64(16, BigInt(generation), true);
      header.setBigInt64(24, BigInt(++sequence), true);
      header.setBigInt64(32, BigInt(Date.now()), true);
      header.setBigInt64(40, BigInt(Date.now()), true);
      header.setBigInt64(48, BigInt(centerHz), true);
      header.setInt32(56, sampleRateHz, true);
      header.setInt32(60, 2048, true);
      header.setInt32(64, 0, true);
      header.setInt32(68, count, true);
      frame.set(payload, 72);
      return frame;
    };
    window.recordingSpectrum = {
      opened: 0, cancelled: 0,
      activity(tables) {
        const payload = encoder.encode(JSON.stringify({ event: 'snapshot', data: { revision: 1, tables } }));
        for (const controller of streams) {
          if (controller.desiredSize !== null) controller.enqueue(multiplex(1, 1, payload));
        }
      },
      emit(streamState = 'live', reason = null, parameters = {}) {
        const state = { stream_state: streamState, reason, center_frequency_hz: centerHz,
          sample_rate_hz: sampleRateHz, profile: parameters.profile || 'balanced' };
        if (parameters.viewport_start_hz != null) {
          state.requested_start_frequency_hz = state.visible_start_frequency_hz = parameters.viewport_start_hz;
          state.requested_end_frequency_hz = state.visible_end_frequency_hz = parameters.viewport_end_hz;
        }
        for (const controller of streams) {
          if (controller.desiredSize === null) continue;
          controller.enqueue(multiplex(5, 2, diagnostic(1, encoder.encode(JSON.stringify(state)))));
          if (streamState === 'live') {
            const values = new Uint8Array(2048).fill(118);
            values[900] = 180;
            controller.enqueue(multiplex(5, 2, diagnostic(4, values, values.length)));
          }
        }
      }
    };
    window.fetch = (input, options = {}) => {
      const url = new URL(typeof input === 'string' || input instanceof URL ? String(input) : input.url,
        window.location.href);
      if (url.pathname !== '/api/v1/live/multiplex') return nativeFetch(input, options);
      generation += 1;
      window.recordingSpectrum.opened += 1;
      let controller;
      return Promise.resolve(new Response(new ReadableStream({
        start(value) {
          controller = value;
          streams.add(value);
          value.enqueue(multiplex(0, 1, encoder.encode(JSON.stringify({ event: 'ready',
            data: { client_id: url.searchParams.get('client_id') } }))));
        },
        cancel() { streams.delete(controller); window.recordingSpectrum.cancelled += 1; }
      }), { status: 200, headers: { 'Content-Type': 'application/vnd.sdrtrunk.live+binary' } }));
    };
  }, { centerHz: CENTER_HZ, sampleRateHz: RATE_HZ });
}

async function install(page, { tunerClass = 'recording_tuner', width = 1280, theme = 'light',
  targetAvailable = true, channelCount = 1, playbackState = 'playing', holdFirstDiagnostics = false,
  exposeController = false } = {}) {
  const preferences = await import(pathToFileURL(resolve(root,
    'stats-web/assets/core/preference-schema.js')).href);
  const receiver = recording(tunerClass);
  receiver.spectrum_target_id = targetAvailable ? 'recording-target' : null;
  receiver.channel_count = channelCount;
  receiver.recording_playback = { state: playbackState };
  const state = { receiver, requests: [], subscriptions: [], releases: [] };
  await page.setViewportSize({ width, height: 900 });
  await installStream(page);
  if (exposeController) await page.route('**/assets/app.js?*', async (route) => {
    const application = readFileSync(process.env.RECORDING_SPECTRUM_BASELINE_APP ||
      resolve(root, 'stats-web/assets/app.js'), 'utf8');
    // Expose the existing controller only in this isolated browser response; production source stays unchanged.
    const original = '  refreshTargets();\n  return controller;\n}';
    expect(application).toContain(original);
    await route.fulfill({ contentType: 'text/javascript', body: application.replace(original,
      '  window.recordingSpectrumController = controller;\n' + original) });
  });
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    const body = JSON.parse(request.postData() || '{}');
    state.requests.push({ path, method, body });
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json',
      body: JSON.stringify({ data }) });
    if (path === '/api/v1/auth/session') return respond({ configured: true, authenticated: true,
      tier: 'admin', username: 'admin', csrf_token: 'test-token', capabilities: {
        'web-access': true, dashboard: true, live: true, 'tuner-spectrum': true, 'admin-tuners': true
      } });
    if (path === '/api/v1/me/preferences') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, preferences: { ...preferences.defaults,
        appearance: { theme, hue: null } } }) });
    if (path === '/api/v1/spectrum-snap-presets') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, country_code: 'US', country_label: 'United States',
        countries: [{ code: 'US', label: 'United States' }], scopes: [] }) });
    if (path === '/api/v1/admin/tuners') return respond({ tuners: [receiver] });
    if (path === '/api/v1/diagnostics/tuners') {
      const snapshot = { rows: receiver.spectrum_target_id ? [{ target_id: receiver.spectrum_target_id,
        label: receiver.name, center_frequency_hz: CENTER_HZ, sample_rate_hz: RATE_HZ }] : [] };
      if (holdFirstDiagnostics || state.holdNextDiagnostics) {
        holdFirstDiagnostics = false;
        state.holdNextDiagnostics = false;
        await new Promise((release) => { state.releaseDiagnostics = release; });
      }
      return respond(snapshot);
    }
    if (path === '/api/v1/admin/tuners/recording-a/browse') {
      if (method === 'DELETE') { state.releases.push(body); return respond(null, 204); }
      return respond({ lease_id: 'recording-browse', expires_at_epoch_ms: Date.now() + 30000,
        can_tune: false, tuner: receiver });
    }
    if (path === '/api/v1/live/multiplex/control') {
      const parameters = body.subscriptions?.tuner_diagnostics;
      state.subscriptions.push(body.subscriptions);
      await respond({});
      if (parameters && !page.isClosed()) await page.evaluate((parameters) =>
        window.recordingSpectrum.emit('live', null, parameters), parameters);
      return;
    }
    return respond({});
  });
  await page.goto('/app.html?view=tuner-spectrum');
  await expect(page.getByRole('heading', { name: 'Browse Spectrum', exact: true })).toBeVisible();
  return state;
}

for (const theme of ['light', 'dark']) {
  for (const width of [1280, 390]) {
    test(`active WAV renders live spectrum and waterfall without takeover (${theme}, ${width})`, async ({ page }, testInfo) => {
      const state = await install(page, { theme, width });
      await expect(page.locator('.spectrum-browse-status')).toHaveText('Live');
      await expect(page.getByRole('combobox', { name: 'Tuner', exact: true }))
        .toContainText('Test baseband.wav · 1 active · 2.4 MHz');
      await expect(page.locator('.spectrum-browse-center')).toContainText('0451.00000MHz');
      await expect(page.locator('.spectrum-browse-center .tuners-center-frequency'))
        .toHaveAttribute('aria-disabled', 'true');
      await expect(page.locator('.tuner-spectrum-fft .channel-diagnostic-overlay')).toBeHidden();
      await expect(page.locator('.tuner-spectrum-waterfall .channel-diagnostic-overlay')).toBeHidden();
      await expect(page.getByRole('button', { name: 'Setup', exact: true })).toBeHidden();
      const plotPixels = await page.locator('.tuner-spectrum-fft canvas').evaluate((canvas) => {
        const pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        let blueTrace = 0;
        for (let index = 0; index < pixels.length; index += 4) {
          if (pixels[index + 1] > 150 && pixels[index + 2] > 200) blueTrace += 1;
        }
        return blueTrace;
      });
      expect(plotPixels).toBeGreaterThan(50);
      const waterfallColors = await page.locator('.tuner-spectrum-waterfall canvas').evaluate((canvas) => {
        const pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        const colors = new Set();
        for (let index = 0; index < pixels.length; index += 4) {
          colors.add(`${pixels[index]},${pixels[index + 1]},${pixels[index + 2]}`);
        }
        return colors.size;
      });
      expect(waterfallColors).toBeGreaterThan(1);
      expect(state.subscriptions.some((subscriptions) =>
        subscriptions?.tuner_diagnostics?.target_id === 'recording-target')).toBe(true);
      expect(state.requests.some(({ method, body }) => method === 'POST' && body.takeover)).toBe(false);
      expect(state.requests.some(({ path }) => /\/settings\//.test(path))).toBe(false);
      await page.screenshot({ path: testInfo.outputPath('recording-spectrum.png'), fullPage: true });
    });
  }
}

test('legacy RECORDING inventory remains available to Spectrum', async ({ page }) => {
  await install(page, { tunerClass: 'RECORDING' });
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Live');
  await expect(page.getByRole('combobox', { name: 'Tuner', exact: true })).toContainText('Test baseband.wav');
});

test('no-sample STATE reasons cover the stale plot, then resumed samples restore Live', async ({ page }) => {
  await install(page);
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Live');
  for (const reason of ['Recording playback is stopped.', 'Recording playback has no recent samples.',
    'Waiting for recording samples.']) {
    await page.evaluate((reason) => window.recordingSpectrum.emit('waiting', reason), reason);
    await expect(page.locator('.spectrum-browse-status')).toHaveText('Waiting');
    for (const surface of ['fft', 'waterfall']) await expect(page.locator(
      `.tuner-spectrum-${surface} .channel-diagnostic-overlay`)).toHaveText(reason);
    await page.evaluate(() => window.recordingSpectrum.emit());
    await expect(page.locator('.spectrum-browse-status')).toHaveText('Live');
    await expect(page.locator('.tuner-spectrum-fft .channel-diagnostic-overlay')).toBeHidden();
  }
});

test('navigation releases recording diagnostics and its browse lease; returning opens a new view', async ({ page }) => {
  const state = await install(page);
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Live');
  await page.evaluate(() => document.querySelector('a[data-view="dashboard"]').click());
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect.poll(() => state.releases.length).toBe(1);
  expect(state.releases[0].lease_id).toBe('recording-browse');
  await expect.poll(() => page.evaluate(() => window.recordingSpectrum.cancelled)).toBeGreaterThan(0);
  await page.evaluate(() => window.recordingSpectrum.emit());
  await expect(page.locator('.spectrum-browse-status')).toHaveCount(0);
  await page.evaluate(() => document.querySelector('a[data-view="tuner-spectrum"]').click());
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Live');
  await expect.poll(() => page.evaluate(() => window.recordingSpectrum.opened)).toBe(2);
});

test('local hardware retains its busy receiver controls and Spectrum frames', async ({ page }) => {
  await install(page, { tunerClass: 'AIRSPY' });
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Live');
  await expect(page.getByRole('button', { name: 'Setup', exact: true })).toBeVisible();
  await expect(page.locator('.tuner-spectrum-fft .channel-diagnostic-overlay')).toBeHidden();
});

test('recording Spectrum includes local channel markers and excludes remote-install rows', async ({ page }) => {
  await install(page);
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Live');
  await page.evaluate((frequencyHz) => window.recordingSpectrum.activity([
    { table_id: 'remote', channel_name: 'Remote site', remote_origin: { remote: true }, rows: [
      { status: 'CALL', frequency_hz: frequencyHz, channel_name: 'Remote voice', lcn: 'remote-1' }
    ] },
    { table_id: 'local', channel_name: 'Local recording', rows: [
      { status: 'CONTROL', frequency_hz: frequencyHz + 12500, channel_name: 'Local control', lcn: 'local-1' },
      { status: 'CALL', frequency_hz: frequencyHz + 25000, channel_name: 'Remote mixed',
        lcn: 'remote-2', remote_origin: { remote: true } }
    ] }
  ]), CENTER_HZ);
  const flags = page.locator('.tuner-spectrum-active-flag');
  await expect(flags).toHaveCount(1);
  for (const flag of await flags.all()) {
    await expect(flag).toHaveAttribute('aria-label', /Local control/);
    await expect(flag).not.toHaveAttribute('aria-label', /Remote/);
  }
});

test('playing WAV without channels is Playing and renders actual spectrum samples', async ({ page }) => {
  await install(page, { channelCount: 0 });
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Live');
  await expect(page.getByRole('combobox', { name: 'Tuner', exact: true })).toContainText('Playing');
  await expect(page.getByRole('button', { name: 'Setup', exact: true })).toBeHidden();
});

test('a newly available recording target binds on lease renewal without leaving Spectrum', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { targetAvailable: false, channelCount: 0 });
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Waiting');
  await expect(page.locator('.tuner-spectrum-fft .channel-diagnostic-overlay'))
    .toHaveText('Waiting for recording samples.');
  state.receiver.spectrum_target_id = 'recording-target';
  state.receiver.channel_count = 1;
  await page.clock.fastForward(11000);
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Live');
  expect(state.subscriptions.some((subscriptions) =>
    subscriptions?.tuner_diagnostics?.target_id === 'recording-target')).toBe(true);
  await expect(page.locator('.tuner-spectrum-fft .channel-diagnostic-overlay')).toBeHidden();
});

test('stopped WAV shows Stopped and resumes on a new target after restart', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { targetAvailable: false, channelCount: 0, playbackState: 'stopped' });
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Stopped');
  await expect(page.getByRole('combobox', { name: 'Tuner', exact: true })).toContainText('Stopped');
  await expect(page.locator('.tuner-spectrum-fft .channel-diagnostic-overlay'))
    .toHaveText('Recording playback is stopped.');
  expect(state.subscriptions.some((subscriptions) => subscriptions?.tuner_diagnostics)).toBe(false);
  state.receiver.recording_playback.state = 'playing';
  state.receiver.spectrum_target_id = 'replayed-recording-target';
  await page.clock.fastForward(11000);
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Live');
  expect(state.subscriptions.some((subscriptions) =>
    subscriptions?.tuner_diagnostics?.target_id === 'replayed-recording-target')).toBe(true);
});

test('recording state changes while no target exists refresh the empty state on renewal', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { targetAvailable: false, channelCount: 0, playbackState: 'stopped' });
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Stopped');
  state.receiver.recording_playback.state = 'waiting';
  await page.clock.fastForward(11000);
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Waiting');
  await expect(page.locator('.tuner-spectrum-fft .channel-diagnostic-overlay'))
    .toHaveText('Recording playback has no recent samples.');
  state.receiver.recording_playback.state = 'stopped';
  await page.clock.fastForward(11000);
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Stopped');
  await expect(page.locator('.tuner-spectrum-waterfall .channel-diagnostic-overlay'))
    .toHaveText('Recording playback is stopped.');
});

test('recording center display refreshes actual metadata on a restarted window without tuner settings', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { targetAvailable: false, channelCount: 0, playbackState: 'stopped' });
  await expect(page.locator('.spectrum-browse-center')).toContainText('0451.00000MHz');
  state.receiver.frequency_hz = 452000000;
  await page.clock.fastForward(11000);
  await expect(page.locator('.spectrum-browse-center')).toContainText('0452.00000MHz');
  await expect(page.locator('.spectrum-browse-center .tuners-center-frequency'))
    .toHaveAttribute('aria-disabled', 'true');
  expect(state.requests.some(({ path }) => /\/settings\//.test(path))).toBe(false);
});

test('a delayed old diagnostics response cannot strand a newly available recording target', async ({ page }) => {
  await page.clock.install();
  const state = await install(page, { targetAvailable: false, channelCount: 0, exposeController: true });
  await expect(page.locator('.spectrum-browse-status'))
    .toHaveText(process.env.RECORDING_SPECTRUM_BASELINE_APP ? 'Idle' : 'Waiting');
  // Start the old inventory request shortly before renewal, within the real request timeout.
  await page.clock.fastForward(9000);
  state.holdNextDiagnostics = true;
  await page.evaluate(() => window.recordingSpectrumController.refreshTargets());
  await expect.poll(() => typeof state.releaseDiagnostics).toBe('function');
  const initialRequestCount = state.requests.filter(({ path }) => path === '/api/v1/diagnostics/tuners').length;
  state.receiver.spectrum_target_id = 'restarted-recording-target';
  state.receiver.channel_count = 1;
  await page.clock.fastForward(2000);
  await expect(page.getByRole('combobox', { name: 'Tuner', exact: true })).toContainText('1 active');
  state.releaseDiagnostics();
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Live');
  expect(state.requests.filter(({ path }) => path === '/api/v1/diagnostics/tuners'))
    .toHaveLength(initialRequestCount + 1);
  expect(state.subscriptions.some((subscriptions) =>
    subscriptions?.tuner_diagnostics?.target_id === 'restarted-recording-target')).toBe(true);
});

test('leaving Spectrum drops a queued diagnostics refresh behind an older response', async ({ page }) => {
  const state = await install(page, { targetAvailable: false, channelCount: 0, holdFirstDiagnostics: true });
  await expect.poll(() => typeof state.releaseDiagnostics).toBe('function');
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Waiting');
  await page.evaluate(() => document.querySelector('a[data-view="dashboard"]').click());
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect.poll(() => state.releases.length).toBe(1);
  state.releaseDiagnostics();
  await page.waitForTimeout(100);
  expect(state.requests.filter(({ path }) => path === '/api/v1/diagnostics/tuners')).toHaveLength(1);
  await expect(page.locator('.spectrum-browse-status')).toHaveCount(0);
});
