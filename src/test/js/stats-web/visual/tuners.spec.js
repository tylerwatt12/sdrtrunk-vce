const { expect, test } = require('@playwright/test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');

function setting(id, label, group, kind, value, overrides = {}) {
  return { id, label, group, kind, value, scope: 'tuner', availability: 'live', editable: true,
    dependencies: [], ...overrides };
}

function tuner(overrides = {}) {
  return {
    id: 'tuner-a', name: 'Airspy R2', tuner_class: 'AIRSPY', tuner_type: 'AIRSPY_R820T',
    status: 'ENABLED', enabled: true, available: true, operator_state: 'live', transition: null,
    channel_count: 0, frequency_hz: 851_012_500, configured_frequency_hz: 851_012_500,
    sample_rate_hz: 10_000_000, configured_sample_rate_hz: 10_000_000,
    stopped_channels: [], restore_result: null,
    planner: { model: 'airspy', rate_hz: 10_000_000 },
    spectrum_target_id: null, spectrum_available: false,
    device_group: { id: 'group-a', kind: 'single', role: 'member' },
    settings: [setting('lna_gain', 'LNA gain', 'gain', 'integer', 8,
      { minimum: 0, maximum: 15, step: 1, unit: 'dB' })],
    ...overrides
  };
}

function operatorSettings(overrides = {}) {
  const values = [
    setting('lna_gain', 'LNA gain', 'gain', 'integer', 8,
      { minimum: 0, maximum: 15, step: 1, unit: 'dB' }),
    setting('automatic_ppm', 'Automatic PPM', 'calibration', 'boolean', false),
    setting('frequency_correction_ppm', 'Frequency correction', 'calibration', 'decimal', 0.1,
      { minimum: -200, maximum: 200, step: 0.1, unit: 'ppm',
        dependencies: [{ setting_id: 'automatic_ppm', equals: false }],
        unavailable_reason: 'Turn off Auto PPM' }),
    setting('minimum_frequency_mhz', 'Minimum frequency', 'frequency', 'decimal', 24,
      { minimum: 24, maximum: 1800, step: 0.00001, unit: 'MHz' }),
    setting('maximum_frequency_mhz', 'Maximum frequency', 'frequency', 'decimal', 1800,
      { minimum: 24, maximum: 1800, step: 0.00001, unit: 'MHz' }),
    setting('reset_frequency_extents', 'Reset frequency limits', 'frequency', 'action', null),
    setting('frequency_mhz', 'Center frequency', 'frequency', 'decimal', 851.0125,
      { minimum: 24, maximum: 1800, step: 0.00001, unit: 'MHz',
        dependencies: [{ setting_id: 'center_frequency_locked', equals: false }],
        unavailable_reason: 'Unlock center' }),
    setting('center_frequency_locked', 'Lock center frequency', 'frequency', 'boolean', false),
    setting('sample_rate', 'Sample rate', 'frequency', 'choice', '10 MHz',
      { options: [{ value: '10 MHz', label: '10.00 MHz' }, { value: '2.5 MHz', label: '2.50 MHz' }],
        availability: 'setup', unavailable_reason: 'Available in Setup' }),
    setting('bias_t', 'Bias T', 'hardware', 'boolean', false)
  ];
  return values.map((value) => value.id in overrides ? { ...value, ...overrides[value.id] } : value);
}

function operatorTuner(overrides = {}) {
  return tuner({ settings: operatorSettings(), ...overrides });
}

function currentTuner(state) {
  return Array.isArray(state.tuners) ? state.tuners[0] : state.currentTuner;
}

function updateSetting(state, id, value) {
  const current = currentTuner(state);
  if (!current) return;
  current.settings = current.settings.map((candidate) => candidate.id === id ? { ...candidate, value } : candidate);
  if (id === 'frequency_mhz') {
    current.frequency_hz = Math.round(Number(value) * 1_000_000);
    current.configured_frequency_hz = current.frequency_hz;
  }
}

async function mockTuners(page, mutations, state = {}) {
  if (!state.currentTuner && !Array.isArray(state.tuners)) state.currentTuner = operatorTuner();
  const preferenceModule = await import(pathToFileURL(resolve(__dirname,
    '../../../../..', 'stats-web/assets/core/preference-schema.js')).href);
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const body = () => JSON.parse(request.postData() || '{}');
    const respond = (data, status = 200) => route.fulfill({
      status, contentType: 'application/json', body: JSON.stringify({ data })
    });
    if (path === '/api/v1/auth/session') {
      await respond({ configured: true, authenticated: true, username: 'admin', tier: 'admin', primary: true,
        csrf_token: 'test-token', capabilities: {
          'admin-tuners': true, 'tuner-spectrum': true, 'dashboard': true, 'web-access': true
        } });
      return;
    }
    if (path === '/api/v1/me/preferences') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        revision: 1, preferences: preferenceModule.defaults
      }) });
      return;
    }
    if (path === '/api/v1/admin/tuners' && request.method() === 'GET') {
      if (state.restorePending) {
        state.restorePolls = (state.restorePolls || 0) + 1;
        if (state.restorePolls >= (state.restoreCompleteAfter || 2)) {
          const current = currentTuner(state);
          current.transition = null;
          if (state.restoreError) {
            current.maintenance_error = state.restoreError;
          } else {
            current.operator_state = 'live';
            current.restore_result = { failed: state.restoreFailed || [] };
            current.stopped_channels = [];
          }
          state.restorePending = false;
        }
      }
      await respond({ tuners: Array.isArray(state.tuners) ? state.tuners :
        (state.currentTuner ? [state.currentTuner] : []) });
      return;
    }
    if (path === '/api/v1/admin/tuners/rf-analysis' && request.method() === 'GET') {
      await respond(state.rfAnalysis || { tuners: [{ id: 'tuner-a', model: 'airspy', rate_hz: 10000000 }],
        frequencies_hz: [851012500] });
      return;
    }
    if (path === '/api/v1/admin/tuners/rescan' && request.method() === 'POST') {
      mutations.push({ type: 'rescan' });
      await respond({ status: 'scanning' }, 202);
      return;
    }
    if (path === '/api/v1/admin/tuners/tuner-a/state' && request.method() === 'PUT') {
      const target = body().state;
      mutations.push({ type: 'state', state: target });
      const current = currentTuner(state);
      if (target !== 'live' && current.operator_state === 'live' && current.channel_count) {
        current.stopped_channels = [{ id: 'channel-a', name: 'County Control' },
          { id: 'channel-b', name: 'City Dispatch' }].slice(0, current.channel_count);
      }
      current.operator_state = target;
      current.enabled = target !== 'disabled';
      current.available = target !== 'disabled';
      if (target !== 'live') current.channel_count = 0;
      current.spectrum_available = target === 'setup';
      await respond({ status: 'applied' });
      return;
    }
    if (path === '/api/v1/admin/tuners/tuner-a/restore' && request.method() === 'POST') {
      mutations.push({ type: 'restore' });
      const current = currentTuner(state);
      current.transition = 'restoring';
      current.restore_result = null;
      current.maintenance_error = null;
      state.restorePending = true;
      state.restorePolls = 0;
      await respond({ status: 'accepted' }, 202);
      return;
    }
    const settingMatch = path.match(/^\/api\/v1\/admin\/tuners\/tuner-a\/settings\/([a-z0-9_]+)$/);
    if (settingMatch && request.method() === 'PUT') {
      const value = body().value;
      mutations.push({ type: 'setting', setting: settingMatch[1], value });
      if (state.rejectSetting === settingMatch[1]) {
        await route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ error: { message: 'Channels won’t fit' } }) });
        return;
      }
      updateSetting(state, settingMatch[1], value);
      await respond({ status: 'applied' });
      return;
    }
    if (path === '/api/v1/admin/tuners/tuner-a' && request.method() === 'DELETE') {
      mutations.push({ type: 'remove' });
      state.tuners = [];
      state.currentTuner = null;
      await route.fulfill({ status: 204 });
      return;
    }
    if (path === '/api/v1/admin/tuners/recordings' && request.method() === 'GET') {
      state.recordingReads = (state.recordingReads || 0) + 1;
      await respond({ entries: [{ id: 'file-a', name: 'debug-851.wav', size_bytes: 4_000_000_000,
        sample_rate_hz: 2_400_000, suggested_center_frequency_hz: 851_012_500 }],
      rejected_count: 0, truncated: false });
      return;
    }
    if (path === '/api/v1/admin/tuners/recordings/rescan' && request.method() === 'POST') {
      state.recordingReads = (state.recordingReads || 0) + 1;
      await respond({ entries: [{ id: 'file-a', name: 'debug-851.wav', size_bytes: 4_000_000_000,
        sample_rate_hz: 2_400_000, suggested_center_frequency_hz: 851_012_500 }],
      rejected_count: 0, truncated: false });
      return;
    }
    if (path === '/api/v1/admin/tuners/recordings' && request.method() === 'POST') {
      mutations.push({ type: 'recording', ...body() });
      await respond({ created: true }, 201);
      return;
    }
    if (path === '/api/v1/diagnostics/tuners') {
      await respond({ rows: state.targets || [] });
      return;
    }
    if (path === '/api/v1/spectrum-snap-presets') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        revision: 1, country_code: 'US', country_label: 'United States',
        countries: [{ code: 'US', label: 'United States' }], scopes: []
      }) });
      return;
    }
    await respond({});
  });
}

test('compact tuner workspace keeps recording tuners in the Add tuner dialog', async ({ page }) => {
  const mutations = [];
  const state = { currentTuner: operatorTuner() };
  await page.setViewportSize({ width: 1280, height: 900 });
  await mockTuners(page, mutations, state);
  await page.goto('/app.html?view=tuners');

  await expect(page.getByRole('heading', { name: 'Tuners', exact: true })).toBeVisible();
  await expect(page.getByText('Signal', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Gain' })).toBeVisible();
  await expect(page.getByText('Common tuning', { exact: true })).toBeVisible();
  await expect(page.getByText('Device settings', { exact: true })).toBeVisible();
  await expect(page.getByLabel('LNA gain (dB)')).toHaveValue('8');
  await expect(page.locator('.tuners-frequency-digits')).toContainText('0851.01250MHz');
  await expect(page.locator('.tuners-main').getByRole('button', { name: /Queue/i })).toHaveCount(0);

  await page.getByLabel('LNA gain (dB)').fill('10');
  await page.locator('[data-setting-id="lna_gain"]').getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => mutations.at(-1)).toEqual({ type: 'setting', setting: 'lna_gain', value: 10 });

  expect(state.recordingReads || 0).toBe(0);
  await page.getByRole('button', { name: 'Add tuner' }).click();
  await expect(page.getByRole('dialog', { name: 'Add recording tuner' })).toBeVisible();
  await expect(page.getByRole('radio', { name: /debug-851.wav/ })).toBeChecked();
  await expect(page.getByText('3.73 GiB')).toBeVisible();
  await page.getByRole('button', { name: 'Add recording tuner', exact: true }).click();
  await expect.poll(() => mutations.at(-1)).toEqual({ type: 'recording', file_id: 'file-a',
    center_frequency_hz: 851_012_500 });
});

test('embedded signal view contains only status, FFT, and waterfall', async ({ page }) => {
  const current = operatorTuner({ channel_count: 2, spectrum_available: true,
    spectrum_target_id: 'diagnostic-a' });
  await mockTuners(page, [], { currentTuner: current,
    targets: [{ target_id: 'diagnostic-a', label: 'Airspy R2' }] });
  await page.goto('/app.html?view=tuners');

  await expect(page.locator('.tuners-spectrum canvas')).toHaveCount(2);
  await expect(page.locator('.tuners-spectrum canvas[tabindex]')).toHaveCount(0);
  await expect(page.locator('.tuners-spectrum canvas[aria-keyshortcuts]')).toHaveCount(0);
  await expect(page.locator('.tuners-spectrum canvas').first()).toHaveCSS('cursor', 'default');
  await expect(page.locator('.tuners-spectrum .visually-hidden')).toHaveText('Read-only FFT and waterfall.');
  await expect(page.locator('.tuners-spectrum .tuner-spectrum-measurement-panel')).toHaveCount(0);
  await expect(page.locator('.tuners-spectrum .tuner-spectrum-cursor-popup')).toHaveCount(0);
  await expect(page.locator('.tuners-spectrum .tuner-spectrum-cursor-guide')).toHaveCount(0);
  await expect(page.locator('.tuners-spectrum .tuner-spectrum-active-flags')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Display options' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Zoom in' })).toHaveCount(0);
  await expect(page.locator('.tuners-spectrum').getByRole('button', { name: 'Pause' })).toHaveCount(0);
  await expect(page.locator('.tuners-spectrum .tuner-spectrum-band-rail')).toHaveCount(0);
  const plot = await page.locator('.tuners-spectrum .tuner-spectrum-visual-window').boundingBox();
  expect(plot.height).toBeGreaterThanOrEqual(400);
});

test('operator workspace stays compact on a narrow screen', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockTuners(page, [], { currentTuner: operatorTuner() });
  await page.goto('/app.html?view=tuners');
  await expect(page.locator('.tuners-frequency-digits')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
  await page.evaluate(() => { document.querySelector('main').scrollTop = 0; });
  await expect(page.locator('main')).toHaveScreenshot('tuners-operator-light-mobile.png');
});

test('inventory polling preserves a focused draft while updating the selected pane', async ({ page }) => {
  await page.clock.install();
  await mockTuners(page, []);
  await page.goto('/app.html?view=tuners');
  const gain = page.getByLabel('LNA gain (dB)');
  await gain.focus();
  await gain.fill('12');
  await page.clock.runFor(5_100);
  await expect(page.getByLabel('LNA gain (dB)')).toHaveValue('12');
  await expect(page.getByLabel('LNA gain (dB)')).toBeFocused();
});

test('descriptor availability and dependencies control common and device settings', async ({ page }) => {
  const settings = operatorSettings({
    automatic_ppm: { value: true },
    center_frequency_locked: { value: true }
  });
  await mockTuners(page, [], { currentTuner: operatorTuner({ settings }) });
  await page.goto('/app.html?view=tuners');

  await expect(page.getByLabel('Frequency correction (ppm)')).toBeDisabled();
  await expect(page.getByText('Turn off Auto PPM')).toBeVisible();
  await expect(page.locator('.tuners-center-frequency')).toHaveAttribute('tabindex', '-1');
  await expect(page.getByText('Unlock center')).toBeVisible();
  await page.getByText('Limits & sample rate').click();
  await expect(page.getByLabel('Sample rate')).toBeDisabled();
  await expect(page.getByText('Available in Setup')).toBeVisible();
  await expect(page.getByLabel('Bias T')).toBeEnabled();
  await expect(page.locator('.tuners-main').getByText(/queued/i)).toHaveCount(0);
});

test('Setup unlocks setup-only controls and remains unavailable to channels', async ({ page }) => {
  const current = operatorTuner({ operator_state: 'setup', channel_count: 0, spectrum_available: true,
    spectrum_target_id: 'diagnostic-a' });
  await mockTuners(page, [], { currentTuner: current,
    targets: [{ target_id: 'diagnostic-a', label: 'Airspy R2' }] });
  await page.goto('/app.html?view=tuners');

  await expect(page.getByText('Not available to channels')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Go live' })).toBeVisible();
  await page.getByText('Limits & sample rate').click();
  await expect(page.getByLabel('Sample rate')).toBeEnabled();
  await expect(page.locator('.tuners-center-frequency')).toHaveAttribute('tabindex', '0');
});

test('center frequency digit zones and hover typing commit exact values', async ({ page }) => {
  const mutations = [];
  const settings = operatorSettings({ frequency_mhz: { value: 852.1625, minimum: 0, maximum: 9999.99999 } });
  const state = { currentTuner: operatorTuner({ operator_state: 'setup', settings,
    frequency_hz: 852_162_500 }) };
  await mockTuners(page, mutations, state);
  await page.goto('/app.html?view=tuners');
  await expect(page.locator('.tuners-frequency-digits')).toContainText('0852.16250MHz');

  await page.getByRole('button', { name: 'Increase 100 MHz place' }).click();
  await expect.poll(() => mutations.at(-1)).toEqual({ type: 'setting', setting: 'frequency_mhz', value: 952.1625 });
  await expect(page.locator('.tuners-frequency-digits')).toContainText('0952.16250MHz');

  await page.locator('.tuners-frequency-digit').nth(3).hover();
  await page.keyboard.type('012345');
  await page.keyboard.press('Enter');
  await expect.poll(() => mutations.at(-1)).toEqual({ type: 'setting', setting: 'frequency_mhz', value: 950.12345 });
  await expect(page.locator('.tuners-frequency-digits')).toContainText('0950.12345MHz');
});

test('a rejected center-frequency click restores the confirmed digits', async ({ page }) => {
  const settings = operatorSettings({ frequency_mhz: { value: 852.1625, minimum: 0, maximum: 9999.99999 } });
  await mockTuners(page, [], { currentTuner: operatorTuner({ operator_state: 'setup', settings }),
    rejectSetting: 'frequency_mhz' });
  await page.goto('/app.html?view=tuners');
  await page.getByRole('button', { name: 'Increase 100 MHz place' }).click();
  await expect(page.locator('.tuners-frequency-digits')).toContainText('0852.16250MHz');
  await expect(page.getByText(/Channels won.t fit/)).toBeVisible();
});

test('leaving Live stops channels once and offers one restore attempt', async ({ page }) => {
  const mutations = [];
  const state = { currentTuner: operatorTuner({ channel_count: 2 }) };
  await mockTuners(page, mutations, state);
  await page.goto('/app.html?view=tuners');

  await page.getByRole('button', { name: 'Setup' }).click();
  const stop = page.getByRole('dialog', { name: 'Enter Setup' });
  await expect(stop.getByText('Stop 2 channels?')).toBeVisible();
  await stop.getByRole('button', { name: 'Enter Setup', exact: true }).click();
  await expect(stop).toBeHidden();
  await expect(page.getByRole('button', { name: 'Stopped 2 channels · View' })).toBeVisible();
  expect(mutations.filter((mutation) => mutation.type === 'state')).toEqual([{ type: 'state', state: 'setup' }]);

  await page.getByRole('button', { name: 'Restart 2' }).click();
  const restore = page.getByRole('dialog', { name: 'Tuner ready' });
  await restore.getByRole('button', { name: 'Restart 2' }).click();
  await expect(restore).toBeHidden();
  expect(mutations.filter((mutation) => mutation.type === 'restore')).toHaveLength(1);
});

test('starting a disabled tuner enters Setup and prompts for stopped channels', async ({ page }) => {
  const mutations = [];
  const current = operatorTuner({ operator_state: 'disabled', enabled: false, available: false,
    stopped_channels: [{ id: 'channel-a', name: 'County Control' }], settings: operatorSettings() });
  await mockTuners(page, mutations, { currentTuner: current });
  await page.goto('/app.html?view=tuners');
  await page.getByRole('button', { name: 'Start Setup' }).click();
  await expect(page.getByRole('dialog', { name: 'Tuner ready' })).toBeVisible();
  await expect(page.getByText('Restart 1 stopped channel?')).toBeVisible();
  await page.getByRole('button', { name: 'Not now' }).click();
  await expect(page.getByText('Not available to channels')).toBeVisible();
});

test('delayed restore failure reports only channels that could not start', async ({ page }) => {
  const mutations = [];
  const current = operatorTuner({ operator_state: 'setup', stopped_channels: [
    { id: 'channel-a', name: 'County Control' }, { id: 'channel-b', name: 'City Dispatch' }
  ] });
  await mockTuners(page, mutations, { currentTuner: current, restoreCompleteAfter: 3,
    restoreFailed: [{ id: 'channel-b', name: 'City Dispatch' }] });
  await page.goto('/app.html?view=tuners');
  await page.getByRole('button', { name: 'Restart 2' }).click();
  const restoring = page.getByRole('dialog', { name: 'Tuner ready' });
  await restoring.getByRole('button', { name: 'Restart 2' }).click();
  await expect(restoring.getByText('Restarting…')).toBeVisible();
  const failed = page.getByRole('dialog', { name: 'Couldn’t start' });
  await expect(failed).toContainText('City Dispatch');
  await expect(failed).not.toContainText('County Control');
  expect(mutations.filter((mutation) => mutation.type === 'restore')).toHaveLength(1);
});

test('restore operation error stays in the prompt without reporting success', async ({ page }) => {
  const mutations = [];
  const current = operatorTuner({ operator_state: 'setup', stopped_channels: [
    { id: 'channel-a', name: 'County Control' }
  ] });
  await mockTuners(page, mutations, { currentTuner: current, restoreCompleteAfter: 2,
    restoreError: 'Tuner unavailable' });
  await page.goto('/app.html?view=tuners');
  await page.getByRole('button', { name: 'Restart 1' }).click();
  const restore = page.getByRole('dialog', { name: 'Tuner ready' });
  await restore.getByRole('button', { name: 'Restart 1' }).click();
  await expect(restore.getByText('Tuner unavailable')).toBeVisible();
  await expect(page.getByText('Channels restarted')).toHaveCount(0);
  await expect(page.getByRole('dialog', { name: 'Couldn’t start' })).toHaveCount(0);
  expect(mutations.filter((mutation) => mutation.type === 'restore')).toHaveLength(1);
});

test('RF analysis sends every eligible tuner and running frequency', async ({ page }) => {
  const state = { currentTuner: operatorTuner(), rfAnalysis: { tuners: [
    { id: 'tuner-a', model: 'airspy', rate_hz: 10000000 },
    { id: 'tuner-b', model: 'rtl-r8x', rate_hz: 2400000 }
  ], frequencies_hz: [155070000, 851012500, 155070000] } };
  await mockTuners(page, [], state);
  await page.route('https://radioresolve.com/rf-planner/**', (route) => route.fulfill({
    status: 200, contentType: 'text/html', body: '<title>RadioResolve RF Planner</title>'
  }));
  await page.goto('/app.html?view=tuners');
  await page.getByRole('button', { name: 'Analyze all tuners at RadioResolve' }).click();
  await page.waitForURL(/^https:\/\/radioresolve\.com\/rf-planner\//);
  const url = new URL(page.url());
  expect(url.searchParams.getAll('model')).toEqual(['airspy', 'rtl-r8x']);
  expect(url.searchParams.getAll('rate')).toEqual(['10000000', '2400000']);
  expect(url.searchParams.get('frequencies')).toBe('155.07\n851.0125\n155.07');
});

test('recording removal closes its busy modal and keeps the WAV file', async ({ page }) => {
  const mutations = [];
  const recording = tuner({ name: 'debug-851.wav', tuner_class: 'recording', tuner_type: 'recording',
    settings: [] });
  await mockTuners(page, mutations, { tuners: [recording] });
  await page.goto('/app.html?view=tuners');
  await page.locator('#selected-tuner-remove').click();
  const dialog = page.getByRole('dialog', { name: 'Remove recording tuner' });
  await expect(dialog.getByText('The WAV file will stay on disk.')).toBeVisible();
  await dialog.getByRole('button', { name: 'Remove recording tuner', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText('No tuners found')).toBeVisible();
  expect(mutations.at(-1)).toEqual({ type: 'remove' });
});
