const { expect, test } = require('@playwright/test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');

const root = resolve(__dirname, '../../../../..');
const protocols = require(resolve(root, 'src/main/resources/channel-protocols.json'));
const INITIAL_CENTER_HZ = 851012500;
const SAMPLE_RATE_HZ = 10000000;
const frequencyPath = '/api/v1/admin/tuners/idle-a/settings/frequency_mhz';

function tuner(centerHz) {
  return {
    id: 'idle-a', name: 'Test receiver', tuner_class: 'AIRSPY', status: 'ENABLED',
    available: true, operator_state: 'setup', channel_count: 0, frequency_hz: centerHz,
    spectrum_target_id: 'target-a', sample_rate_hz: SAMPLE_RATE_HZ,
    minimum_frequency_hz: 24000000, maximum_frequency_hz: 1800000000,
    settings: [
      { id: 'frequency_mhz', label: 'Center frequency', value: centerHz / 1000000,
        editable: true, availability: 'setup', minimum: 24, maximum: 1800,
        dependencies: [{ setting_id: 'center_frequency_locked', equals: false }] },
      { id: 'center_frequency_locked', label: 'Lock center', kind: 'boolean', value: false,
        editable: true, availability: 'live', dependencies: [] }
    ]
  };
}

// Use the application's multiplex and diagnostic decoders, including real STATE and FFT payloads.
async function installLiveStream(page) {
  await page.addInitScript(({ initialCenterHz, sampleRateHz }) => {
    const nativeFetch = window.fetch.bind(window);
    const encoder = new TextEncoder();
    let controller = null;
    let sequence = 0;
    let generation = 0;
    let activeParameters = {};
    let receiverState = { centerHz: initialCenterHz, streamState: 'live', reason: null };
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
    const diagnostic = (type, payload, centerHz, count = 0, compact = false) => {
      const headerBytes = compact ? 84 : 72;
      const frame = new Uint8Array(headerBytes + payload.byteLength);
      const header = new DataView(frame.buffer);
      header.setUint32(0, 0x53444447, true);
      header.setUint8(4, 1);
      header.setUint8(5, type);
      header.setUint16(6, headerBytes, true);
      header.setUint32(8, payload.byteLength, true);
      header.setUint32(12, count, true);
      header.setBigInt64(16, BigInt(generation), true);
      header.setBigInt64(24, BigInt(++sequence), true);
      header.setBigInt64(32, BigInt(Date.now()), true);
      header.setBigInt64(40, BigInt(Date.now()), true);
      header.setBigInt64(48, BigInt(centerHz), true);
      header.setInt32(56, sampleRateHz, true);
      header.setInt32(60, count || 2048, true);
      header.setInt32(64, 0, true);
      header.setInt32(68, count, true);
      if (compact) {
        header.setUint8(72, 6);
        header.setUint8(73, 1);
        header.setFloat32(76, -80, true);
        header.setFloat32(80, 0, true);
      }
      frame.set(payload, headerBytes);
      return frame;
    };
    const emit = (centerHz = receiverState.centerHz, parameters = activeParameters,
      { streamState = receiverState.streamState, reason = receiverState.reason, fftValues = null } = {}) => {
      receiverState = { centerHz, streamState, reason };
      if (!controller || controller.desiredSize === null) return;
      activeParameters = parameters;
      const state = {
        stream_state: streamState, center_frequency_hz: centerHz,
        sample_rate_hz: sampleRateHz, profile: parameters.profile || 'balanced', reason
      };
      if (parameters.viewport_start_hz != null) {
        state.requested_start_frequency_hz = parameters.viewport_start_hz;
        state.requested_end_frequency_hz = parameters.viewport_end_hz;
        state.visible_start_frequency_hz = parameters.viewport_start_hz;
        state.visible_end_frequency_hz = parameters.viewport_end_hz;
      }
      controller.enqueue(multiplex(5, 2, diagnostic(1, encoder.encode(JSON.stringify(state)), centerHz)));
      if (streamState !== 'live') return;
      fftValues ??= window.spectrumDragStream.fftValues;
      if (fftValues) {
        const payload = new Uint8Array(fftValues.length * 4);
        const view = new DataView(payload.buffer);
        fftValues.forEach((value, index) => view.setFloat32(index * 4, value, true));
        controller.enqueue(multiplex(5, 2, diagnostic(4, payload, centerHz, fftValues.length)));
        return;
      }
      const compact = parameters.strength_encoding === 'packed6';
      const values = new Uint8Array(2048).fill(compact ? 20 : 118);
      values[900] = compact ? 63 : 180;
      let payload = values;
      if (compact) {
        payload = new Uint8Array(Math.ceil(values.length * 6 / 8));
        values.forEach((value, index) => {
          const bit = index * 6;
          payload[bit >> 3] |= value << (bit & 7);
          if ((bit & 7) > 2) payload[(bit >> 3) + 1] |= value >> (8 - (bit & 7));
        });
      }
      controller.enqueue(multiplex(5, 2, diagnostic(4, payload, centerHz, values.length, compact)));
    };
    window.spectrumDragStream = { emit, opened: 0, cancelled: 0, initialEmitted: false };
    document.addEventListener('pointerdown', (event) => {
      if (event.target.matches('.tuner-spectrum-canvas')) window.spectrumDragPointerId = event.pointerId;
    }, true);
    window.fetch = (input, options = {}) => {
      const rawUrl = typeof input === 'string' || input instanceof URL ? String(input) : input.url;
      const url = new URL(rawUrl, window.location.href);
      if (url.pathname !== '/api/v1/live/multiplex') return nativeFetch(input, options);
      generation += 1;
      const streamGeneration = generation;
      let streamController = null;
      let initialTimer = null;
      window.spectrumDragStream.opened += 1;
      window.spectrumDragStream.initialEmitted = false;
      const ready = multiplex(0, 1, encoder.encode(JSON.stringify({
        event: 'ready', data: { client_id: url.searchParams.get('client_id') }
      })));
      return Promise.resolve(new Response(new ReadableStream({
        start(value) {
          controller = streamController = value;
          controller.enqueue(ready);
          initialTimer = window.setTimeout(() => {
            if (controller !== streamController || generation !== streamGeneration) return;
            window.spectrumDragStream.initialEmitted = true;
            emit();
          }, 50);
        },
        cancel() {
          window.clearTimeout(initialTimer);
          if (controller === streamController) controller = null;
          window.spectrumDragStream.cancelled += 1;
        }
      }), { status: 200, headers: { 'Content-Type': 'application/vnd.sdrtrunk.live+binary' } }));
    };
  }, { initialCenterHz: INITIAL_CENTER_HZ, sampleRateHz: SAMPLE_RATE_HZ });
}

async function install(page) {
  const preferences = await import(pathToFileURL(resolve(root,
    'stats-web/assets/core/preference-schema.js')).href);
  const state = { centerHz: INITIAL_CENTER_HZ, requests: [], tunes: [], controls: [],
    browses: [], holdNextBrowse: false, failNextBrowse: false };
  await page.setViewportSize({ width: 1280, height: 900 });
  await installLiveStream(page);
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
        'web-access': true, dashboard: true, 'tuner-spectrum': true, 'admin-tuners': true,
        'admin-channels': true, 'admin-aliases': true
      } });
    if (path === '/api/v1/me/preferences') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, preferences: { ...preferences.defaults,
        appearance: { theme: 'light', hue: null } } }) });
    if (path === '/api/v1/spectrum-snap-presets') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, country_code: 'US', country_label: 'United States',
        countries: [{ code: 'US', label: 'United States' }], scopes: [] }) });
    if (path === '/api/v1/admin/tuners') return respond({ tuners: [tuner(state.centerHz)] });
    if (path === '/api/v1/diagnostics/tuners') return respond({ rows: [{ target_id: 'target-a',
      label: 'Test receiver', center_frequency_hz: state.centerHz, sample_rate_hz: SAMPLE_RATE_HZ }] });
    if (path === frequencyPath && method === 'PUT') {
      const record = { body, released: false };
      const gate = new Promise((release) => { record.release = () => { record.released = true; release(); }; });
      record.done = new Promise((complete) => { record.complete = complete; });
      state.tunes.push(record);
      await gate;
      try {
        if (record.fail) await route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'test_tune_failed',
            message: 'Receiver rejected this tune. Try again.' } }) });
        else {
          state.centerHz = Math.round(body.value * 1000000);
          await respond({});
        }
      } catch (_) { /* Navigation may abort the request under test. */ }
      finally { record.complete(); }
      return;
    }
    if (path === '/api/v1/admin/tuners/idle-a/browse') {
      if (method === 'DELETE') {
        state.takeover = false;
        return respond(null, 204);
      }
      if (body.takeover === true) state.takeover = true;
      const record = { body, snapshot: { lease_id: 'browse-a', expires_at_epoch_ms: Date.now() + 30000,
        takeover: state.takeover === true, can_tune: true, tuner: tuner(state.centerHz) }, fail: state.failNextBrowse };
      state.failNextBrowse = false;
      const gate = state.holdNextBrowse ? new Promise((release) => { record.release = release; }) : null;
      state.holdNextBrowse = false;
      record.done = new Promise((complete) => { record.complete = complete; });
      state.browses.push(record);
      if (gate) await gate;
      try {
        if (record.fail) await route.fulfill({ status: 410, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'tuner_browse_expired',
            message: 'Tuner browsing expired. Retry to resume.' } }) });
        else await respond(record.snapshot);
      } catch (_) { /* Closing the page may abort a deliberately held renewal. */ }
      finally { record.complete(); }
      return;
    }
    if (path === '/api/v1/live/multiplex/control') {
      const parameters = body.subscriptions?.tuner_diagnostics;
      state.controls.push(parameters);
      await respond({});
      if (parameters && !page.isClosed()) await page.evaluate(({ centerHz, parameters }) =>
        window.spectrumDragStream.emit(centerHz, parameters), { centerHz: state.centerHz, parameters });
      return;
    }
    if (path === '/api/v1/admin/channels/protocols') return respond(protocols);
    if (path === '/api/v1/admin/spectrum-discovery/eligibility') return respond({
      eligible: true, reason: null, matches: []
    });
    return respond({});
  });
  state.release = async (index, { fail = false } = {}) => {
    const record = state.tunes[index];
    expect(record, `tune request ${index + 1}`).toBeTruthy();
    record.fail = fail;
    record.release();
    await record.done;
  };
  state.releaseBrowse = async (record) => {
    expect(record?.release, 'held browse request').toBeTruthy();
    record.release();
    await record.done;
  };
  state.releaseAll = () => {
    state.tunes.forEach((record) => record.release());
    state.browses.forEach((record) => record.release?.());
  };
  await page.goto('/app.html?view=tuner-spectrum');
  await expect(page.getByRole('heading', { name: 'Browse Spectrum', exact: true })).toBeVisible();
  await expect(page.locator('.spectrum-browse-center')).toContainText('0851.01250MHz');
  await expect(page.getByRole('combobox', { name: 'Tuner', exact: true })).toBeEnabled();
  // Lease selection/inventory refresh can clear the plots after the first control response.
  // Wait for the producer's initial sample and the usable plots, rather than any transient Live badge.
  await expect.poll(() => page.evaluate(() => window.spectrumDragStream.initialEmitted)).toBe(true);
  await expect(page.locator('.tuner-spectrum-fft .channel-diagnostic-overlay')).toBeHidden();
  await expect(page.locator('.tuner-spectrum-waterfall .channel-diagnostic-overlay')).toBeHidden();
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Live');
  await expect(page.getByRole('button', { name: 'Zoom in', exact: true })).toBeEnabled();
  return state;
}

function plot(page, surface = 'fft') {
  return page.locator(`.tuner-spectrum-${surface} canvas`);
}

test('one display choice changes both plots, disables manual controls and follows the browser to Tuners', async ({ page }) => {
  const state = await install(page);
  await page.getByRole('button', { name: 'Display options', exact: true }).click();
  const options = page.locator('.tuner-spectrum-options-panel');
  const range = options.locator('output[aria-label="FFT and waterfall display range"]');
  const choice = options.locator('input[name="auto_range_on_retune"]');
  const manualRange = options.locator('.tuner-spectrum-range-heading output');
  const floor = options.getByRole('slider', { name: 'Lower display limit', exact: true });
  const ceiling = options.getByRole('slider', { name: 'Upper display limit', exact: true });
  await expect(floor).toBeDisabled();
  await expect(ceiling).toBeDisabled();
  await expect.poll(() => floor.inputValue()).not.toBe('-140');
  const automatic = await range.textContent();
  await choice.locator('..').click();
  await expect(choice).not.toBeChecked();
  await expect(floor).toBeEnabled();
  const baseline = await manualRange.textContent();
  await floor.focus();
  await page.keyboard.press('ArrowRight');
  const manual = await manualRange.textContent();
  expect(manual).not.toBe(baseline);
  await expect(range).toHaveText(`FFT and waterfall: ${manual}`);
  await page.evaluate((centerHz) => window.spectrumDragStream.emit(centerHz), INITIAL_CENTER_HZ + 1000000);
  await expect(range).toHaveText(`FFT and waterfall: ${manual}`, { timeout: 1000 });
  await choice.locator('..').click();
  await page.evaluate((centerHz) => window.spectrumDragStream.emit(centerHz), INITIAL_CENTER_HZ + 1000000);
  await expect(range).toHaveText(automatic);
  await expect(floor).toBeDisabled();
  await choice.locator('..').click();
  await expect(range).toHaveText(`FFT and waterfall: ${manual}`);
  expect(state.requests.filter((request) => request.path === '/api/v1/me/preferences' && request.method !== 'GET')
    .map((request) => request.body).some((body) => Object.hasOwn(body.preferences?.tuner || {}, 'auto_range_on_retune'))).toBe(false);
  await page.goto('/app.html?view=tuners');
  const embedded = page.locator('.tuners-spectrum');
  await embedded.getByRole('button', { name: 'Display options', exact: true }).click();
  await expect(embedded.locator('input[name="auto_range_on_retune"]')).not.toBeChecked();
});

for (const width of [1280, 390]) {
  test(`auto range uses visible FFT pixels instead of deep raw nulls at ${width}px`, async ({ page }) => {
    const state = await install(page);
    await page.setViewportSize({ width, height: 900 });
    await expect.poll(() => page.evaluate(() => window.spectrumDragStream.initialEmitted)).toBe(true);
    const center = INITIAL_CENTER_HZ + 1000000;
    state.centerHz = center;
    await page.evaluate((centerHz) => {
      window.spectrumDragStream.fftValues = Array.from({ length: 8192 }, (_, index) =>
        index % 8 === 0 ? -116 : -70);
      window.spectrumDragStream.emit(centerHz);
    }, center);
    const trigger = page.getByRole('button', {
      name: width < 600 ? 'More spectrum actions' : 'Display options', exact: true
    });
    await trigger.click();
    const options = page.locator('.tuner-spectrum-options-panel');
    const floor = options.getByRole('slider', { name: 'Lower display limit', exact: true });
    await expect(floor).toHaveValue('-70');
    await expect(floor).toBeDisabled();
    await expect(options.locator('output[aria-label="FFT and waterfall display range"]'))
      .toHaveText('FFT and waterfall: -70 to 0 dB');
    await page.evaluate((centerHz) => {
      window.spectrumDragStream.fftValues = Array.from({ length: 8192 }, (_, index) =>
        index % 8 === 0 ? -130 : -90);
      window.spectrumDragStream.emit(centerHz);
    }, center);
    await expect(floor).toHaveValue('-70');
    expect(state.requests.filter((request) => request.path === '/api/v1/me/preferences' && request.method !== 'GET'))
      .toHaveLength(0);
  });
}

test('Live and Setup retain manual settings and automatic minimum on the same center frequency', async ({ page }) => {
  await install(page);
  await page.getByRole('button', { name: 'Display options', exact: true }).click();
  const options = page.locator('.tuner-spectrum-options-panel');
  const range = options.locator('output[aria-label="FFT and waterfall display range"]');
  const automatic = await range.textContent();
  const choice = options.locator('input[name="auto_range_on_retune"]');
  await choice.locator('..').click();
  await options.getByRole('slider', { name: 'Lower display limit', exact: true }).focus();
  await page.keyboard.press('ArrowRight');
  const manual = await options.locator('.tuner-spectrum-range-heading output').textContent();
  await expect(range).toHaveText(`FFT and waterfall: ${manual}`);
  const status = page.locator('.spectrum-browse-status');
  for (const name of ['Setup', 'Live']) {
    await page.getByRole('button', { name, exact: true }).click();
    await expect(page.getByRole('button', { name, exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(status).toHaveText('Live');
    await page.evaluate((centerHz) => window.spectrumDragStream.emit(centerHz), INITIAL_CENTER_HZ);
    await expect(range).toHaveText(`FFT and waterfall: ${manual}`);
  }
  await choice.locator('..').click();
  await expect(range).toHaveText(`FFT and waterfall: ${manual}`);
  await page.evaluate((centerHz) => window.spectrumDragStream.emit(centerHz), INITIAL_CENTER_HZ);
  await expect(range).toHaveText(automatic);
});

test('compact strength frames retain numeric hover after changing the waterfall display range', async ({ page }) => {
  const state = await install(page);
  await expect.poll(() => state.controls.some((value) => value?.strength_encoding === 'packed6')).toBe(true);
  await page.evaluate(({ centerHz }) => window.spectrumDragStream.emit(centerHz,
    { strength_encoding: 'packed6' }), { centerHz: INITIAL_CENTER_HZ });
  await page.getByRole('button', { name: 'Display options', exact: true }).click();
  const smoothing = page.getByLabel('Smooth FFT', { exact: true });
  await smoothing.locator('..').click();
  await expect(smoothing).not.toBeChecked();
  await page.getByRole('button', { name: 'Display options', exact: true }).click();
  const canvas = plot(page);
  const bounds = await canvas.boundingBox();
  await page.mouse.move(bounds.x + bounds.width * 0.2, bounds.y + bounds.height * 0.5);
  await expect(page.locator('.tuner-spectrum-cursor-power')).toHaveText('-54.6 dB');
  await page.getByRole('button', { name: 'Display options', exact: true }).click();
  await page.getByRole('checkbox', { name: 'Auto range display range on retune', exact: true }).locator('..').click();
  const floor = page.getByRole('slider', { name: 'Lower display limit' });
  await floor.focus();
  await page.keyboard.press('ArrowRight');
  await page.getByRole('button', { name: 'Display options', exact: true }).click();
  await page.mouse.move(bounds.x + bounds.width * 0.2, bounds.y + bounds.height * 0.5);
  await expect(page.locator('.tuner-spectrum-cursor-power')).toHaveText('-54.6 dB');
  expect(await plot(page, 'waterfall').evaluate((value) => {
    const pixels = value.getContext('2d').getImageData(0, 0, value.width, 1).data;
    return pixels.some((component, index) => index % 4 !== 3 && component > 0);
  })).toBe(true);
  state.releaseAll();
});

async function beginDrag(page, canvas, ratio = 0.6) {
  const bounds = await canvas.boundingBox();
  expect(bounds).toBeTruthy();
  const y = bounds.y + bounds.height / 2;
  const startX = bounds.x + bounds.width * ratio;
  await page.mouse.move(startX, y);
  await expect(page.locator('.tuner-spectrum-fft .channel-diagnostic-overlay')).toBeHidden();
  await page.mouse.down();
  await expect(canvas).toHaveClass(/dragging/);
  return { bounds, y, startX, startRatio: ratio };
}

async function moveDrag(page, gesture, ratio) {
  const x = gesture.bounds.x + gesture.bounds.width * ratio;
  await page.mouse.move(x, gesture.y);
  return Math.round((gesture.startX - x) / gesture.bounds.width * SAMPLE_RATE_HZ);
}

async function expectTuneCount(state, count) {
  await expect.poll(() => state.tunes.length).toBe(count);
  state.tunes.forEach((record) => expect(record.body.lease_id).toBe('browse-a'));
}

async function settle(page) {
  // Allow a queued animation frame, tune throttle, and network continuation to run.
  await page.waitForTimeout(250);
}

async function editCenter(page, digits) {
  const center = page.locator('.spectrum-browse-center .tuners-center-frequency');
  await center.focus();
  await center.locator('.tuners-frequency-digit').first().hover();
  await page.keyboard.type(digits);
  await page.keyboard.press('Enter');
}

for (const surface of ['fft', 'waterfall']) {
  test(`${surface} drag tunes before release and coalesces a blocked request to the latest final center`, async ({ page }) => {
    const state = await install(page);
    try {
      const canvas = plot(page, surface);
      const gesture = await beginDrag(page, canvas);
      const feedback = page.locator('.spectrum-browse-feedback');
      await expect(feedback).toBeHidden();
      await moveDrag(page, gesture, 0.55);
      await expectTuneCount(state, 1);
      await expect(feedback).toBeHidden();
      expect(await canvas.boundingBox()).toEqual(gesture.bounds);
      await expect(canvas).toHaveClass(/dragging/);
      await moveDrag(page, gesture, 0.5);
      await moveDrag(page, gesture, 0.45);
      const finalDeltaHz = await moveDrag(page, gesture, 0.4);
      await settle(page);
      expect(state.tunes).toHaveLength(1);
      await expect(feedback).toBeHidden();
      expect(await canvas.boundingBox()).toEqual(gesture.bounds);
      if (surface === 'waterfall') await page.screenshot({ path: test.info().outputPath('quiet-waterfall-retune.png') });
      await page.mouse.up();
      await state.release(0);
      await expectTuneCount(state, 2);
      expect(state.tunes[1].body.value).toBe((INITIAL_CENTER_HZ + finalDeltaHz) / 1000000);
      await state.release(1);
      await settle(page);
      expect(state.tunes).toHaveLength(2);
      await expect(page.locator('.spectrum-browse-center')).toContainText('0853.01250MHz');
      await expect(feedback).toBeHidden();
      expect(await canvas.boundingBox()).toEqual(gesture.bounds);
    } finally { state.releaseAll(); }
  });
}

test('another drag keeps its starting origin when an older response and live STATE arrive', async ({ page }) => {
  const state = await install(page);
  try {
    const canvas = plot(page);
    const first = await beginDrag(page, canvas);
    const firstDeltaHz = await moveDrag(page, first, 0.5);
    await expectTuneCount(state, 1);
    await page.mouse.up();
    const firstDesiredHz = INITIAL_CENTER_HZ + firstDeltaHz;
    const second = await beginDrag(page, canvas);
    await moveDrag(page, second, 0.55);
    await expect(canvas).toHaveClass(/dragging/);
    await expect(page.locator('.spectrum-browse-center')).toContainText('0852.51250MHz');
    await page.evaluate((centerHz) => window.spectrumDragStream.emit(centerHz), INITIAL_CENTER_HZ);
    await expect(page.locator('.spectrum-browse-center')).toContainText('0852.51250MHz');
    await state.release(0);
    await expectTuneCount(state, 2);
    await expect(page.locator('.spectrum-browse-center')).toContainText('0852.51250MHz');
    const finalDeltaHz = await moveDrag(page, second, 0.45);
    await page.mouse.up();
    await state.release(1);
    await expectTuneCount(state, 3);
    expect(state.tunes[2].body.value).toBe((firstDesiredHz + finalDeltaHz) / 1000000);
    await state.release(2);
    await settle(page);
    expect(state.tunes).toHaveLength(3);
    await expect(page.locator('.spectrum-browse-center')).toContainText('0853.51250MHz');
  } finally { state.releaseAll(); }
});

test('releasing an already confirmed live target keeps center editing available without another PUT', async ({ page }) => {
  const state = await install(page);
  try {
    const canvas = plot(page);
    const gesture = await beginDrag(page, canvas);
    await moveDrag(page, gesture, 0.55);
    await expectTuneCount(state, 1);
    await state.release(0);
    const center = page.locator('.spectrum-browse-center');
    await expect(center).toContainText('0851.51250MHz');
    await expect(center).toHaveJSProperty('inert', false);
    await expect(canvas).toHaveClass(/dragging/);
    await page.mouse.up();
    await settle(page);
    expect(state.tunes).toHaveLength(1);
    await expect(center).toHaveJSProperty('inert', false);
    await expect(center.locator('.tuners-center-frequency')).toHaveAttribute('aria-disabled', 'false');
    await expect(page.locator('.spectrum-browse-message')).not.toContainText('Tuning to');
    await center.locator('.tuners-center-frequency').focus();
    await expect(center.locator('.tuners-center-frequency')).toBeFocused();
  } finally { state.releaseAll(); }
});

for (const cancellation of ['pointercancel', 'lostpointercapture']) {
  test(`${cancellation} drops queued tuning after an already sent request finishes`, async ({ page }) => {
    const state = await install(page);
    try {
      const canvas = plot(page);
      const gesture = await beginDrag(page, canvas);
      await moveDrag(page, gesture, 0.55);
      await expectTuneCount(state, 1);
      await moveDrag(page, gesture, 0.4);
      await canvas.evaluate((element, kind) => {
        const pointerId = window.spectrumDragPointerId;
        if (kind === 'pointercancel') element.dispatchEvent(new PointerEvent('pointercancel', {
          pointerId, bubbles: true, pointerType: 'mouse'
        }));
        else element.releasePointerCapture(pointerId);
      }, cancellation);
      await page.mouse.move(gesture.startX, gesture.y);
      await expect(canvas).not.toHaveClass(/dragging/);
      await page.mouse.up();
      await state.release(0);
      await settle(page);
      expect(state.tunes).toHaveLength(1);
      await expect(page.locator('.spectrum-browse-center')).toContainText('0851.51250MHz');
      // Cancellation must also permit a later independent gesture.
      const retry = await beginDrag(page, canvas);
      await moveDrag(page, retry, 0.55);
      await expectTuneCount(state, 2);
      await page.mouse.up();
      await state.release(1);
    } finally { state.releaseAll(); }
  });
}

test('leaving Spectrum discards the queued center and ignores a late tune response', async ({ page }) => {
  const state = await install(page);
  try {
    const canvas = plot(page);
    const gesture = await beginDrag(page, canvas);
    await moveDrag(page, gesture, 0.55);
    await expectTuneCount(state, 1);
    await moveDrag(page, gesture, 0.4);
    await page.evaluate(() => {
      window.detachedSpectrumCenter = document.querySelector('.spectrum-browse-center');
      document.querySelector('a[data-view="dashboard"]').click();
    });
    await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
    await expect.poll(() => state.requests.some((request) => request.path.endsWith('/browse') &&
      request.method === 'DELETE')).toBe(true);
    const detachedText = await page.evaluate(() => window.detachedSpectrumCenter.textContent);
    await page.mouse.up();
    await state.release(0);
    await settle(page);
    expect(state.tunes).toHaveLength(1);
    await expect(page.locator('.spectrum-browse-center')).toHaveCount(0);
    expect(await page.evaluate(() => window.detachedSpectrumCenter.textContent)).toBe(detachedText);
    await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  } finally { state.releaseAll(); }
});

test('a failed live tune restores the confirmed origin and permits the same drag to retry', async ({ page }) => {
  const state = await install(page);
  try {
    const canvas = plot(page);
    const first = await beginDrag(page, canvas);
    const deltaHz = await moveDrag(page, first, 0.55);
    await expectTuneCount(state, 1);
    await page.mouse.up();
    await state.release(0, { fail: true });
    await expect(page.locator('.spectrum-browse-message')).toHaveText('Receiver rejected this tune. Try again.');
    await expect(page.locator('.spectrum-browse-center')).toContainText('0851.01250MHz');
    await expect(page.getByRole('combobox', { name: 'Tuner', exact: true })).toBeEnabled();
    await settle(page);
    expect(state.tunes).toHaveLength(1);
    const retry = await beginDrag(page, canvas);
    await moveDrag(page, retry, 0.55);
    await expectTuneCount(state, 2);
    expect(state.tunes[1].body.value).toBe((INITIAL_CENTER_HZ + deltaHz) / 1000000);
    await page.mouse.up();
    await state.release(1);
    await expect(page.locator('.spectrum-browse-center')).toContainText('0851.51250MHz');
  } finally { state.releaseAll(); }
});

for (const streamState of ['unavailable', 'closed']) {
  test(`${streamState} STATE at an older center cancels the gesture and drops its queued tune`, async ({ page }) => {
    const state = await install(page);
    try {
      const canvas = plot(page);
      const gesture = await beginDrag(page, canvas);
      await moveDrag(page, gesture, 0.55);
      await expectTuneCount(state, 1);
      await moveDrag(page, gesture, 0.4);
      await page.evaluate(({ centerHz, streamState }) => window.spectrumDragStream.emit(centerHz, undefined, {
        streamState, reason: 'Receiver samples stopped.'
      }), { centerHz: INITIAL_CENTER_HZ - 5000000, streamState });
      await expect(page.locator('.spectrum-browse-status')).toHaveText('Unavailable');
      await expect(page.locator('.tuner-spectrum-fft .channel-diagnostic-overlay'))
        .toHaveText('Receiver samples stopped.');
      await expect(canvas).not.toHaveClass(/dragging/);
      await page.mouse.up();
      await state.release(0);
      await settle(page);
      expect(state.tunes).toHaveLength(1);
      await expect(page.locator('.spectrum-browse-status')).toHaveText('Unavailable');
      await expect(page.locator('.tuner-spectrum-waterfall .channel-diagnostic-overlay'))
        .toHaveText('Receiver samples stopped.');
    } finally { state.releaseAll(); }
  });
}

test('repeated unavailable STATE while idle leaves lease renewal on its existing schedule', async ({ page }) => {
  const state = await install(page);
  const browseCount = state.browses.length;
  for (const streamState of ['unavailable', 'closed', 'unavailable']) {
    await page.evaluate(({ centerHz, streamState }) => window.spectrumDragStream.emit(centerHz, undefined, {
      streamState, reason: 'Receiver samples stopped.'
    }), { centerHz: INITIAL_CENTER_HZ, streamState });
    await expect(page.locator('.spectrum-browse-status')).toHaveText('Unavailable');
    // Model a late subscription response. It must report the same stopped receiver, without reviving samples.
    const parameters = state.controls.filter(Boolean).at(-1);
    expect(parameters).toBeTruthy();
    await page.evaluate(({ centerHz, parameters }) => window.spectrumDragStream.emit(centerHz, parameters),
      { centerHz: INITIAL_CENTER_HZ, parameters });
    await settle(page);
    await expect(page.locator('.spectrum-browse-status')).toHaveText('Unavailable');
    await expect(page.locator('.tuner-spectrum-fft .channel-diagnostic-overlay'))
      .toHaveText('Receiver samples stopped.');
  }
  await settle(page);
  expect(state.browses).toHaveLength(browseCount);
  expect(state.tunes).toHaveLength(0);
  await page.evaluate((centerHz) => window.spectrumDragStream.emit(centerHz, undefined, {
    streamState: 'live', reason: null
  }), INITIAL_CENTER_HZ);
  await expect(page.locator('.spectrum-browse-status')).toHaveText('Live');
  await expect(page.locator('.tuner-spectrum-fft .channel-diagnostic-overlay')).toBeHidden();
});

test('repeated gesture cancellation serializes renewal and retains only one periodic timer', async ({ page }) => {
  await page.clock.install();
  const state = await install(page);
  try {
    state.holdNextBrowse = true;
    await page.clock.fastForward(11000);
    await expect.poll(() => state.browses.some((record) => record.release)).toBe(true);
    const periodic = state.browses.find((record) => record.release);
    expect(state.browses).toHaveLength(2);
    const canvas = plot(page);
    for (let index = 0; index < 2; index += 1) {
      const gesture = await beginDrag(page, canvas);
      await moveDrag(page, gesture, 0.55);
      await expectTuneCount(state, index + 1);
      await canvas.evaluate((element) => element.dispatchEvent(new PointerEvent('pointercancel', {
        pointerId: window.spectrumDragPointerId, bubbles: true, pointerType: 'mouse'
      })));
      await page.mouse.up();
      await state.release(index);
      // Each issued tune has its own completion POST, while the requested periodic renewal remains held.
      await expect.poll(() => state.browses.length).toBe(3 + index);
      await settle(page);
      expect(state.browses).toHaveLength(3 + index);
    }
    await state.releaseBrowse(periodic);
    await expect.poll(() => state.browses.length).toBe(5);
    await settle(page);
    expect(state.browses).toHaveLength(5);
    await page.clock.fastForward(10000);
    await expect.poll(() => state.browses.length).toBe(6);
    await settle(page);
    expect(state.browses).toHaveLength(6);
    expect(state.tunes).toHaveLength(2);
  } finally { state.releaseAll(); }
});

test('an old periodic lease snapshot cannot replace a final live tune that completed afterward', async ({ page }) => {
  await page.clock.install();
  const state = await install(page);
  try {
    const canvas = plot(page);
    const gesture = await beginDrag(page, canvas);
    await moveDrag(page, gesture, 0.55);
    await expectTuneCount(state, 1);
    await moveDrag(page, gesture, 0.4);
    await page.mouse.up();
    // Start the periodic request after the final target was queued; retain its actual old server snapshot.
    state.holdNextBrowse = true;
    await page.clock.fastForward(11000);
    await expect.poll(() => state.browses.some((record) => record.release)).toBe(true);
    const periodic = state.browses.find((record) => record.release);
    expect(periodic.snapshot.tuner.frequency_hz).toBe(INITIAL_CENTER_HZ);
    await state.release(0);
    await expectTuneCount(state, 2);
    await state.release(1);
    const center = page.locator('.spectrum-browse-center');
    await expect(center).toContainText('0853.01250MHz');
    await expect(center).toHaveJSProperty('inert', false);
    await state.releaseBrowse(periodic);
    await settle(page);
    await expect(center).toContainText('0853.01250MHz');
    await expect(center.locator('.tuners-center-frequency')).toHaveAttribute('aria-disabled', 'false');
    expect(state.tunes).toHaveLength(2);
  } finally { state.releaseAll(); }
});

test('manual center editing after a settled drag becomes the next gesture origin', async ({ page }) => {
  const state = await install(page);
  try {
    const canvas = plot(page);
    const first = await beginDrag(page, canvas);
    await moveDrag(page, first, 0.55);
    await expectTuneCount(state, 1);
    await page.mouse.up();
    await state.release(0);
    await expect(page.locator('.spectrum-browse-center')).toHaveJSProperty('inert', false);
    await editCenter(page, '085412345');
    await expectTuneCount(state, 2);
    expect(state.tunes[1].body.value).toBe(854.12345);
    await state.release(1);
    const center = page.locator('.spectrum-browse-center');
    await expect(center).toContainText('0854.12345MHz');
    await expect(center.locator('.tuners-center-frequency')).toHaveAttribute('aria-disabled', 'false');
    await expect(center).toHaveJSProperty('inert', false);
    await expect(page.getByRole('combobox', { name: 'Tuner', exact: true })).toBeEnabled();
    // The fixture emits on demand; model the next receiver STATE/FFT after the post-save waiting overlay.
    await page.evaluate((centerHz) => window.spectrumDragStream.emit(centerHz), state.centerHz);
    await expect(page.locator('.tuner-spectrum-fft .channel-diagnostic-overlay')).toBeHidden();
    const next = await beginDrag(page, canvas);
    const deltaHz = await moveDrag(page, next, 0.55);
    await expectTuneCount(state, 3);
    expect(state.tunes[2].body.value).toBe((854123450 + deltaHz) / 1000000);
    await page.mouse.up();
    await state.release(2);
    await expect(page.locator('.spectrum-browse-center')).toContainText('0854.62345MHz');
  } finally { state.releaseAll(); }
});

test('retrying an expired lease during a held manual save restores editable center and live dragging', async ({ page }) => {
  await page.clock.install();
  const state = await install(page);
  try {
    await editCenter(page, '085412345');
    await expectTuneCount(state, 1);
    state.failNextBrowse = true;
    await page.clock.fastForward(11000);
    const retry = page.getByRole('button', { name: 'Retry browsing', exact: true });
    await expect(retry).toBeVisible();
    await retry.click();
    const center = page.locator('.spectrum-browse-center');
    await expect(center).toContainText('0851.01250MHz');
    await expect(center.locator('.tuners-center-frequency')).toHaveAttribute('aria-disabled', 'false');
    await expect(center).toHaveJSProperty('inert', false);
    await expect(retry).toBeHidden();
    await expect(page.locator('.spectrum-browse-status')).toHaveText('Live');
    const canvas = plot(page);
    const gesture = await beginDrag(page, canvas);
    const deltaHz = await moveDrag(page, gesture, 0.55);
    await expectTuneCount(state, 2);
    expect(state.tunes[1].body.value).toBe((INITIAL_CENTER_HZ + deltaHz) / 1000000);
    await page.mouse.up();
    await state.release(0);
    await state.release(1);
    await expect(center).toContainText('0851.51250MHz');
    await expect(center).toHaveJSProperty('inert', false);
    await expect(center.locator('.tuners-center-frequency')).toHaveAttribute('aria-disabled', 'false');
    await settle(page);
    expect(state.tunes).toHaveLength(2);
  } finally { state.releaseAll(); }
});

test('wheel zoom and drag pan preserve viewport requests, and a click still selects a frequency', async ({ page }) => {
  const state = await install(page);
  const canvas = plot(page);
  await canvas.hover();
  await page.mouse.wheel(0, -200);
  await expect(page.locator('.tuner-spectrum-layout')).toHaveClass(/zoomed/);
  await expect.poll(() => state.controls.filter((parameters) =>
    parameters?.viewport_start_hz != null).length).toBeGreaterThan(0);
  const before = state.controls.filter((parameters) => parameters?.viewport_start_hz != null).at(-1);
  const gesture = await beginDrag(page, canvas);
  await moveDrag(page, gesture, 0.5);
  await page.mouse.up();
  await expect.poll(() => state.controls.filter((parameters) => parameters?.viewport_start_hz != null)
    .at(-1)?.viewport_start_hz).not.toBe(before.viewport_start_hz);
  const after = state.controls.filter((parameters) => parameters?.viewport_start_hz != null).at(-1);
  // The API rounds the two endpoints independently; preserving span permits their one-Hz rounding difference.
  expect(Math.abs((after.viewport_end_hz - after.viewport_start_hz) -
    (before.viewport_end_hz - before.viewport_start_hz))).toBeLessThanOrEqual(1);
  expect(state.tunes).toHaveLength(0);
  await page.getByRole('button', { name: 'Reset zoom', exact: true }).click();
  await expect(page.locator('.tuner-spectrum-layout')).not.toHaveClass(/zoomed/);
  await canvas.click({ position: { x: gesture.bounds.width / 2, y: gesture.bounds.height / 2 } });
  await expect(page.locator('.tuner-frequency-action-frequency')).toHaveText('851.012500 MHz');
  await expect(page.getByRole('button', { name: 'Add channel or system', exact: true })).toBeEnabled();
  expect(state.tunes).toHaveLength(0);
});
