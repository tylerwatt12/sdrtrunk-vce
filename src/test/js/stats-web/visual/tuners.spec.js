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
    status: 'ENABLED', available: true, operator_state: 'live', transition: null,
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
    setting('frequency_correction_ppm', 'Frequency correction', 'calibration', 'decimal', 0,
      { minimum: -200, maximum: 200, step: 1, unit: 'ppm',
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
    setting('center_frequency_locked', 'Lock center', 'frequency', 'boolean', false),
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
      if (state.snapPresetError) {
        await route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: { message: 'Spectrum setup failed' } }) });
        return;
      }
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
  await expect(page.locator('.tuners-spectrum-toolbar-gain')).toBeVisible();
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

test('embedded signal view reuses cursor, zoom, and selectable quality without tuner actions', async ({ page }) => {
  const mutations = [];
  const current = operatorTuner({ channel_count: 2, spectrum_available: true,
    spectrum_target_id: 'diagnostic-a' });
  await mockTuners(page, mutations, { currentTuner: current,
    targets: [{ target_id: 'diagnostic-a', label: 'Airspy R2',
      center_frequency_hz: 851_012_500, sample_rate_hz: 10_000_000 }] });
  await page.goto('/app.html?view=tuners');

  const embedded = page.locator('.tuners-spectrum');
  const canvases = embedded.locator('canvas');
  await expect(canvases).toHaveCount(2);
  await expect(embedded.locator('canvas[tabindex="0"]')).toHaveCount(2);
  await expect(embedded.locator('canvas[aria-keyshortcuts="+ - ArrowLeft ArrowRight R 0 Home"]')).toHaveCount(2);
  await expect(embedded.locator('.visually-hidden')).toContainText('mouse wheel or plus and minus keys to zoom');
  await expect(embedded.locator('.tuner-spectrum-cursor-popup')).toHaveCount(1);
  await expect(embedded.locator('.tuner-spectrum-cursor-guide')).toHaveCount(2);
  await expect(embedded.getByRole('group', { name: 'Spectrum zoom' })).toBeVisible();
  await expect(embedded.getByRole('button', { name: 'Zoom in' })).toBeVisible();
  await expect(embedded.getByRole('button', { name: 'Zoom out' })).toBeVisible();
  await expect(embedded.getByRole('button', { name: 'Reset zoom' })).toBeVisible();

  const profile = embedded.locator('.tuner-spectrum-toolbar-profile select');
  await expect(profile).toBeVisible();
  await expect(profile.locator('option')).toHaveText([
    'Efficient · 2,048 bins / 5 FPS',
    'Balanced · 8,192 bins / 10 FPS',
    'High detail · 16,384 bins / 20 FPS · high load',
    'Maximum detail · 32,768 bins / 20 FPS · highest load'
  ]);
  await expect(profile).toHaveValue('efficient');
  for (const value of ['efficient', 'balanced', 'high-detail', 'maximum-detail']) {
    await profile.selectOption(value);
    await expect(profile).toHaveValue(value);
  }

  await canvases.first().hover({ position: { x: 100, y: 60 } });
  await expect(embedded.locator('.tuner-spectrum-cursor-popup')).toBeVisible();
  await expect(embedded.locator('.tuner-spectrum-cursor-frequency')).toContainText('MHz');
  await expect(embedded.locator('.tuner-spectrum-cursor-guide').first()).toBeVisible();
  await expect(embedded.locator('.tuner-spectrum-cursor-guide').last()).toBeVisible();

  await embedded.locator('.channel-diagnostic-overlay').first().evaluate((overlay) => {
    overlay.textContent = '';
    overlay.hidden = true;
  });
  const zoomIn = embedded.getByRole('button', { name: 'Zoom in' });
  await expect(zoomIn).toBeEnabled();
  await zoomIn.click();
  await expect(embedded.locator('.tuner-spectrum-layout')).toHaveClass(/\bzoomed\b/);
  await expect(embedded.getByRole('button', { name: 'Reset zoom' })).toBeEnabled();

  await expect(embedded.locator('.tuner-spectrum-measurement-panel')).toHaveCount(0);
  await expect(embedded.locator('.tuner-spectrum-active-flags')).toHaveCount(0);
  await expect(embedded.getByRole('button', { name: 'Pause' })).toHaveCount(0);
  await expect(embedded.locator('.tuner-spectrum-band-rail')).toHaveCount(0);
  await canvases.first().click({ position: { x: 40, y: 40 } });
  await expect(page.getByRole('dialog', { name: 'Frequency actions' })).toHaveCount(0);
  await expect(page.locator('[popover][aria-label="Frequency actions"]')).toHaveCount(0);
  expect(mutations).toEqual([]);
  const plot = await embedded.locator('.tuner-spectrum-visual-window').boundingBox();
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
  await page.getByText('Limits & sample rate').click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
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
    frequency_correction_ppm: { value: -0.017398311918371955 },
    center_frequency_locked: { value: true }
  });
  await mockTuners(page, [], { currentTuner: operatorTuner({ settings }) });
  await page.goto('/app.html?view=tuners');

  const automaticPpm = page.getByLabel('Automatic PPM');
  const manualPpm = page.getByLabel('Frequency correction (ppm)');
  await expect(manualPpm).toBeDisabled();
  await expect(manualPpm).toHaveValue('0');
  await expect(page.locator('[data-setting-id="frequency_correction_ppm"]')).toHaveAttribute(
    'aria-disabled', 'true');
  await expect(page.getByText('Turn off Auto PPM')).toBeVisible();
  const [automaticBox, manualBox] = await Promise.all([
    page.locator('[data-setting-id="automatic_ppm"] .ui-toggle').boundingBox(),
    manualPpm.boundingBox()
  ]);
  expect(automaticBox).not.toBeNull();
  expect(manualBox).not.toBeNull();
  expect(Math.abs(automaticBox.y - manualBox.y)).toBeLessThanOrEqual(1);
  expect(Math.abs(automaticBox.height - manualBox.height)).toBeLessThanOrEqual(1);
  expect(await automaticPpm.evaluate((control) => control.compareDocumentPosition(
    document.querySelector('[data-tuner-setting="frequency_correction_ppm"]')) & Node.DOCUMENT_POSITION_FOLLOWING))
    .toBeTruthy();
  await expect(page.locator('.tuners-center-frequency')).toHaveAttribute('tabindex', '-1');
  await expect(page.getByText('Unlock center')).toBeVisible();
  await page.getByText('Limits & sample rate').click();
  await expect(page.getByLabel('Sample rate')).toBeDisabled();
  await expect(page.getByText('Available in Setup')).toBeVisible();
  await expect(page.getByLabel('Bias T')).toBeEnabled();
  await expect(page.locator('.tuners-main').getByText(/queued/i)).toHaveCount(0);
});

test('manual PPM presents and applies whole-number steps', async ({ page }) => {
  const mutations = [];
  const settings = operatorSettings({
    frequency_correction_ppm: { value: -0.017398311918371955 }
  });
  await mockTuners(page, mutations, { currentTuner: operatorTuner({ settings }) });
  await page.goto('/app.html?view=tuners');

  const manualPpm = page.getByLabel('Frequency correction (ppm)');
  await expect(manualPpm).toHaveAttribute('step', '1');
  await expect(manualPpm).toHaveValue('0');
  await manualPpm.press('Enter');
  await page.waitForTimeout(50);
  expect(mutations).toEqual([]);
  await manualPpm.press('ArrowUp');
  await expect(manualPpm).toHaveValue('1');
  await page.locator('[data-setting-id="frequency_correction_ppm"]').getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => mutations.at(-1)).toEqual({
    type: 'setting', setting: 'frequency_correction_ppm', value: 1
  });
});

test('preset gain hides unusable controls and uses the Signal toolbar', async ({ page }) => {
  const settings = operatorSettings({
    lna_gain: { editable: false, unavailable_reason: 'Use manual gain' }
  });
  settings.unshift(setting('gain', 'Gain preset', 'gain', 'choice', 'LINEARITY_14', {
    options: [{ value: 'LINEARITY_14', label: 'LINEARITY_14' }, { value: 'CUSTOM', label: 'CUSTOM' }]
  }));
  settings.push(setting('mixer_agc', 'Mixer AGC', 'gain', 'boolean', false, {
    editable: false, unavailable_reason: 'Unavailable'
  }));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await mockTuners(page, [], { currentTuner: operatorTuner({ settings, spectrum_available: true,
    spectrum_target_id: 'diagnostic-a' }),
    targets: [{ target_id: 'diagnostic-a', label: 'Airspy R2',
      center_frequency_hz: 851_012_500, sample_rate_hz: 10_000_000 }] });
  await page.goto('/app.html?view=tuners');

  await expect(page.getByLabel('Gain preset')).toBeVisible();
  await expect(page.getByLabel('LNA gain (dB)')).toHaveCount(0);
  await expect(page.getByLabel('Mixer AGC')).toHaveCount(0);
  await expect(page.locator('.tuners-spectrum-toolbar-gain .tuners-setting')).toHaveCount(1);
  await expect(page.locator('.tuner-spectrum-toolbar-actions > .tuners-spectrum-toolbar-gain')).toHaveCount(1);
  const [gainBox, toolbarBox, spectrumBox, signalBox] = await Promise.all([
    page.locator('.tuners-spectrum-toolbar-gain').boundingBox(),
    page.locator('.tuner-spectrum-toolbar').boundingBox(),
    page.locator('.tuners-spectrum').boundingBox(),
    page.locator('.tuners-signal-layout').boundingBox()
  ]);
  expect(gainBox).not.toBeNull();
  expect(toolbarBox).not.toBeNull();
  expect(spectrumBox).not.toBeNull();
  expect(signalBox).not.toBeNull();
  expect(gainBox.y).toBeGreaterThanOrEqual(toolbarBox.y);
  expect(gainBox.y + gainBox.height).toBeLessThanOrEqual(toolbarBox.y + toolbarBox.height + 1);
  expect(Math.abs(spectrumBox.x - signalBox.x)).toBeLessThanOrEqual(1);
  expect(Math.abs(spectrumBox.width - signalBox.width)).toBeLessThanOrEqual(1);
  expect(Math.abs((spectrumBox.y + spectrumBox.height) - (signalBox.y + signalBox.height))).toBeLessThanOrEqual(1);
});

test('gain controls stay available when spectrum setup fails', async ({ page }) => {
  const mutations = [];
  await mockTuners(page, mutations, { currentTuner: operatorTuner(), snapPresetError: true });
  await page.goto('/app.html?view=tuners');

  await expect(page.getByText('Spectrum setup failed')).toBeVisible();
  await expect(page.locator('.tuners-spectrum-toolbar-fallback > .tuners-spectrum-toolbar-gain')).toBeVisible();
  await expect(page.getByLabel('LNA gain (dB)')).toHaveValue('8');
  await page.getByLabel('LNA gain (dB)').fill('10');
  await page.locator('[data-setting-id="lna_gain"]').getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => mutations.at(-1)).toEqual({ type: 'setting', setting: 'lna_gain', value: 10 });
});

test('wide tuner summary and common controls use their available row', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 1000 });
  await mockTuners(page, [], { currentTuner: operatorTuner() });
  await page.goto('/app.html?view=tuners');

  const [summaryBox, centerBox, actionsBox] = await Promise.all([
    page.locator('.tuners-detail-summary').boundingBox(),
    page.locator('.tuners-center-host').boundingBox(),
    page.locator('.tuners-detail-actions').boundingBox()
  ]);
  expect(summaryBox).not.toBeNull();
  expect(centerBox).not.toBeNull();
  expect(actionsBox).not.toBeNull();
  expect(summaryBox.x + summaryBox.width).toBeLessThanOrEqual(centerBox.x + 1);
  expect(centerBox.x + centerBox.width).toBeLessThanOrEqual(actionsBox.x + 1);
  const detailCenters = [summaryBox, centerBox, actionsBox].map((box) => box.y + box.height / 2);
  expect(Math.max(...detailCenters) - Math.min(...detailCenters)).toBeLessThanOrEqual(1);

  await page.getByText('Limits & sample rate').click();
  const limits = page.locator('.tuners-common-limits');
  const sample = page.locator('.tuners-common-sample');
  await expect(limits.locator(':scope > [data-setting-id="minimum_frequency_mhz"]')).toHaveCount(1);
  await expect(limits.locator(':scope > [data-setting-id="maximum_frequency_mhz"]')).toHaveCount(1);
  await expect(limits.locator(':scope > [data-setting-id="reset_frequency_extents"]')).toHaveCount(1);
  await expect(limits.locator(':scope > [data-setting-id="sample_rate"]')).toHaveCount(0);
  await expect(sample.locator(':scope > [data-setting-id="sample_rate"]')).toHaveCount(1);
  const [minimumBox, maximumBox, resetBox, limitsBox, sampleBox] = await Promise.all([
    limits.locator('[data-setting-id="minimum_frequency_mhz"]').boundingBox(),
    limits.locator('[data-setting-id="maximum_frequency_mhz"]').boundingBox(),
    limits.locator('[data-setting-id="reset_frequency_extents"]').boundingBox(),
    limits.boundingBox(), sample.boundingBox()
  ]);
  expect(Math.max(minimumBox.y + minimumBox.height, maximumBox.y + maximumBox.height,
    resetBox.y + resetBox.height) - Math.min(minimumBox.y + minimumBox.height,
    maximumBox.y + maximumBox.height, resetBox.y + resetBox.height)).toBeLessThanOrEqual(1);
  expect(limitsBox.x + limitsBox.width).toBeLessThanOrEqual(sampleBox.x + 1);
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
  await expect(page.locator('.tuners-center-frequency .tuners-center-lock')).toHaveCount(1);
  expect(await page.locator('.tuners-frequency-step').evaluateAll((buttons) => buttons.every((button) => {
    const arrow = getComputedStyle(button, '::after');
    return Math.abs(parseFloat(arrow.left) - button.getBoundingClientRect().width / 2) < 0.5 &&
      arrow.transform !== 'none';
  }))).toBeTruthy();

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
  const current = operatorTuner({ operator_state: 'disabled', available: false,
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

test('Find Optimal Tuner Placement opens every eligible tuner and running frequency in a new tab',
    async ({ page, context }) => {
  const state = { currentTuner: operatorTuner(), rfAnalysis: { tuners: [
    { id: 'tuner-a', model: 'airspy', rate_hz: 10000000 },
    { id: 'tuner-b', model: 'rtl-r8x', rate_hz: 2400000 }
  ], frequencies_hz: [155070000, 851012500, 155070000] } };
  await mockTuners(page, [], state);
  await context.route('https://radioresolve.com/rf-planner/**', (route) => route.fulfill({
    status: 200, contentType: 'text/html', body: '<title>RadioResolve RF Planner</title>'
  }));
  await page.goto('/app.html?view=tuners');
  const originalUrl = page.url();
  const placement = page.getByRole('button', { name: 'Find Optimal Tuner Placement', exact: true });
  await expect(placement).toBeVisible();
  const popupPromise = page.waitForEvent('popup');
  await placement.click();
  const planner = await popupPromise;
  await planner.waitForURL(/^https:\/\/radioresolve\.com\/rf-planner\//);
  await expect(page).toHaveURL(originalUrl);
  const url = new URL(planner.url());
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
