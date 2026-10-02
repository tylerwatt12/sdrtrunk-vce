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
        availability: 'setup', unavailable_reason: 'Use Setup' }),
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
      if (target === 'live') current.stopped_channels = [];
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
      if (state.pauseSetting === settingMatch[1]) {
        state.settingPauseStarted?.();
        await state.releaseSetting;
      }
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

test('two-column tuner workspace keeps settings below signal and recordings in the Add tuner dialog', async ({ page }) => {
  const mutations = [];
  const state = { currentTuner: operatorTuner() };
  await page.setViewportSize({ width: 1680, height: 1000 });
  await mockTuners(page, mutations, state);
  await page.goto('/app.html?view=tuners');

  await expect(page.getByRole('heading', { name: 'Tuners', exact: true })).toBeVisible();
  await expect(page.getByText('Signal', { exact: true })).toBeVisible();
  await expect(page.locator('.tuners-controls')).toBeVisible();
  await expect(page.locator('.tuners-selected-section')).toHaveCount(1);
  await expect(page.locator('.tuners-main > .tuners-selected-section > .tuners-details > .tuners-selected-header'))
    .toHaveCount(1);
  await expect(page.locator('.tuners-selected-section > .tuners-spectrum')).toHaveCount(1);
  await expect(page.locator('.tuners-selected-section > .tuners-readouts')).toHaveCount(1);
  await expect(page.locator('.tuners-main > .tuners-controls')).toHaveCount(1);
  await expect(page.getByRole('button', { name: 'Configure tuner' })).toHaveCount(0);
  await expect(page.locator('.tuners-gain-section')).toBeVisible();
  await expect(page.locator('.tuners-device-section')).toBeVisible();
  await expect(page.locator('.tuners-controls').getByText('Common tuning', { exact: true })).toBeVisible();
  await expect(page.getByLabel('LNA gain (dB)')).toHaveValue('8');
  await expect(page.locator('.tuners-frequency-digits')).toContainText('0851.01250MHz');
  await expect(page.locator('.tuners-readouts .tuners-channel-readout .tuners-readout-value'))
    .toHaveText('0');
  const sampleRateChoice = page.locator('.tuners-readouts').getByLabel('Sample rate');
  await expect(sampleRateChoice.locator('option')).toHaveText(['10 MHz', '2.5 MHz']);
  await expect(sampleRateChoice).toHaveValue('10 MHz');
  const readoutFonts = await page.evaluate(() => [
    '.tuners-channel-readout .tuners-readout-value',
    '.tuners-common-sample .ui-select',
    '.tuners-readout-center .tuners-frequency-digit > span',
    '.tuners-lock-readout .ui-toggle-copy strong'
  ].map((selector) => {
    const style = getComputedStyle(document.querySelector(selector));
    return [style.fontFamily, style.fontSize, style.fontWeight];
  }));
  expect(new Set(readoutFonts.map((font) => font.join('|'))).size).toBe(1);
  await expect(page.locator('.tuners-main').getByRole('button', { name: /Queue/i })).toHaveCount(0);
  await expect(page.locator('main')).toHaveScreenshot('tuners-operator-light-desktop.png');

  await page.getByLabel('LNA gain (dB)').focus();
  await page.getByLabel('LNA gain (dB)').press('ArrowRight');
  await page.getByLabel('LNA gain (dB)').press('ArrowRight');
  await page.locator('[data-setting-id="lna_gain"]').getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => mutations.at(-1)).toEqual({ type: 'setting', setting: 'lna_gain', value: 10 });

  await expect(page.getByRole('dialog', { name: 'Configure Airspy R2' })).toHaveCount(0);

  expect(state.recordingReads || 0).toBe(0);
  await page.getByRole('button', { name: 'Add tuner' }).click();
  await expect(page.getByRole('dialog', { name: 'Add recording tuner' })).toBeVisible();
  await expect(page.getByRole('radio', { name: /debug-851.wav/ })).toBeChecked();
  await expect(page.getByText('3.73 GiB')).toBeVisible();
  await page.getByRole('button', { name: 'Add recording tuner', exact: true }).click();
  await expect.poll(() => mutations.at(-1)).toEqual({ type: 'recording', file_id: 'file-a',
    center_frequency_hz: 851_012_500 });
});

test('embedded signal view reuses cursor and zoom with HiRes and LoRes controls', async ({ page }) => {
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
  await expect(embedded.locator('.tuner-spectrum-toolbar > .tuner-spectrum-zoom-actions')).toBeVisible();
  await expect(embedded.getByRole('button', { name: 'Zoom in' })).toBeVisible();
  await expect(embedded.getByRole('button', { name: 'Zoom out' })).toBeVisible();
  await expect(embedded.getByRole('button', { name: 'Reset zoom' })).toBeVisible();

  const quality = embedded.locator('.tuner-spectrum-quality-control');
  const hiRes = quality.getByRole('button', { name: 'HiRes' });
  const loRes = quality.getByRole('button', { name: 'LoRes' });
  await expect(quality).toHaveAttribute('role', 'group');
  await expect(quality).toHaveAttribute('aria-label', 'Signal detail');
  await expect(hiRes).toHaveAttribute('aria-pressed', 'false');
  await expect(loRes).toHaveAttribute('aria-pressed', 'true');
  await expect(hiRes).toHaveAttribute('title', 'Sharper signal display; uses more CPU');
  await hiRes.click();
  await expect(hiRes).toHaveAttribute('aria-pressed', 'true');
  await expect(loRes).toHaveAttribute('aria-pressed', 'false');
  await loRes.click();
  await expect(hiRes).toHaveAttribute('aria-pressed', 'false');
  await expect(loRes).toHaveAttribute('aria-pressed', 'true');
  await expect(embedded.locator('.tuner-spectrum-toolbar-profile select')).toHaveCount(0);

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

test('operator workspace keeps inline controls usable on a narrow screen', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockTuners(page, [], { currentTuner: operatorTuner() });
  await page.goto('/app.html?view=tuners');
  await expect(page.locator('.tuners-frequency-digits')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
  await page.evaluate(() => { document.querySelector('main').scrollTop = 0; });
  await expect(page.locator('main')).toHaveScreenshot('tuners-operator-light-mobile.png');
  await expect(page.locator('.tuners-controls .tuners-device-section')).toBeVisible();
  await expect(page.locator('.tuners-controls').getByLabel('Frequency correction (ppm)')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
});

test('inventory polling preserves a focused draft while updating the selected pane', async ({ page }) => {
  await page.clock.install();
  await mockTuners(page, []);
  await page.goto('/app.html?view=tuners');
  const gain = page.getByLabel('LNA gain (dB)');
  await gain.focus();
  for (let step = 0; step < 4; step += 1) await gain.press('ArrowRight');
  await page.clock.runFor(5_100);
  await expect(page.getByLabel('LNA gain (dB)')).toHaveValue('12');
  await expect(page.getByLabel('LNA gain (dB)')).toBeFocused();
});

test('inline tuner settings preserve a focused draft while inventory refreshes', async ({ page }) => {
  await page.clock.install();
  await mockTuners(page, []);
  await page.goto('/app.html?view=tuners');
  const manualPpm = page.locator('.tuners-controls').getByLabel('Frequency correction (ppm)');
  await manualPpm.focus();
  await manualPpm.fill('12');
  await page.clock.runFor(5_100);
  await expect(manualPpm).toHaveValue('12');
  await expect(manualPpm).toBeFocused();
});

test('inline setting drafts survive tuner selection and can be discarded', async ({ page }) => {
  const second = operatorTuner({ id: 'tuner-b', name: 'RTL-SDR', tuner_class: 'RTL',
    tuner_type: 'RTL2832', channel_count: 0 });
  await mockTuners(page, [], { tuners: [operatorTuner(), second] });
  await page.goto('/app.html?view=tuners');
  const ppm = page.locator('.tuners-controls').getByLabel('Frequency correction (ppm)');
  await ppm.fill('12');
  await page.locator('.tuners-list-item').filter({ hasText: 'RTL-SDR' }).click();
  await page.locator('.tuners-list-item').filter({ hasText: 'Airspy R2' }).click();
  await expect(ppm).toHaveValue('12');
  await page.locator('[data-setting-id="frequency_correction_ppm"]')
    .getByRole('button', { name: 'Discard' }).click();
  await expect(ppm).toHaveValue('0');
});

test('descriptor availability and dependencies control common and device settings', async ({ page }) => {
  const settings = operatorSettings({
    automatic_ppm: { value: true },
    frequency_correction_ppm: { value: -0.017398311918371955 },
    center_frequency_locked: { value: true }
  });
  await mockTuners(page, [], { currentTuner: operatorTuner({ settings }) });
  await page.goto('/app.html?view=tuners');
  const controls = page.locator('.tuners-controls');

  const automaticPpm = controls.getByLabel('Automatic PPM');
  const manualPpm = controls.getByLabel('Frequency correction (ppm)');
  await expect(manualPpm).toBeDisabled();
  await expect(manualPpm).toHaveValue('0');
  await expect(page.locator('[data-setting-id="frequency_correction_ppm"]')).toHaveAttribute(
    'aria-disabled', 'true');
  await expect(controls.getByRole('button', { name: 'Turn off Auto PPM to adjust' })).toBeVisible();
  expect(await automaticPpm.evaluate((control) => control.compareDocumentPosition(
    document.querySelector('[data-tuner-setting="frequency_correction_ppm"]')) & Node.DOCUMENT_POSITION_FOLLOWING))
    .toBeTruthy();
  await expect(page.locator('.tuners-center-frequency')).toHaveAttribute('tabindex', '-1');
  await expect(page.getByRole('button', { name: 'Unlock center to tune' })).toBeVisible();
  await expect(page.locator('.tuners-readouts').getByLabel('Sample rate')).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Switch to Setup to adjust' })).toBeVisible();
  await expect(controls.locator('.tuners-device-section').getByLabel('Bias T')).toBeEnabled();
  await expect(controls.getByText(/queued/i)).toHaveCount(0);
});

test('manual PPM presents and applies whole-number steps', async ({ page }) => {
  const mutations = [];
  const settings = operatorSettings({
    frequency_correction_ppm: { value: -0.017398311918371955 }
  });
  await mockTuners(page, mutations, { currentTuner: operatorTuner({ settings }) });
  await page.goto('/app.html?view=tuners');

  const manualPpm = page.locator('.tuners-controls').getByLabel('Frequency correction (ppm)');
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

test('boolean settings auto-save without flashing actions and allow failure recovery', async ({ page }) => {
  const mutations = [];
  let signalSaveStarted;
  let releaseSave;
  const saveStarted = new Promise((resolve) => { signalSaveStarted = resolve; });
  const saveReleased = new Promise((resolve) => { releaseSave = resolve; });
  const state = { currentTuner: operatorTuner(), pauseSetting: 'automatic_ppm',
    settingPauseStarted: signalSaveStarted, releaseSetting: saveReleased,
    rejectSetting: 'automatic_ppm' };
  await mockTuners(page, mutations, state);
  await page.goto('/app.html?view=tuners');

  const form = page.locator('[data-setting-id="automatic_ppm"]');
  const actions = form.locator('.tuners-setting-actions');
  const automaticPpm = form.getByLabel('Automatic PPM');
  await form.locator('.ui-toggle').click();
  await saveStarted;
  await expect(automaticPpm).toBeChecked();
  await expect(form.locator('.tuners-setting-message')).toHaveText('Saving…');
  await expect(actions).toBeHidden();
  await page.waitForTimeout(100);
  await expect(actions).toBeHidden();

  state.pauseSetting = null;
  releaseSave();
  await expect(form.getByText(/Channels won.t fit/)).toBeVisible();
  await expect(form.getByRole('button', { name: 'Save' })).toBeVisible();
  await expect(form.getByRole('button', { name: 'Discard' })).toBeVisible();

  await form.getByRole('button', { name: 'Discard' }).click();
  await expect(automaticPpm).not.toBeChecked();
  await expect(form.locator('.ui-toggle-state')).toHaveText('Off');
  await expect(actions).toBeHidden();

  await form.locator('.ui-toggle').click();
  await expect.poll(() => mutations.filter((mutation) => mutation.setting === 'automatic_ppm').length)
    .toBe(2);
  await expect(form.getByRole('button', { name: 'Save' })).toBeVisible();
  state.rejectSetting = null;
  await form.getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => mutations.filter((mutation) => mutation.setting === 'automatic_ppm').length)
    .toBe(3);
  await expect(automaticPpm).toBeChecked();
  await expect(actions).toBeHidden();
  expect(currentTuner(state).settings.find((setting) => setting.id === 'automatic_ppm').value).toBe(true);
});

test('preset gain shows disabled family controls and how to enable them', async ({ page }) => {
  const settings = operatorSettings({
    lna_gain: { editable: false, unavailable_reason: 'Use Custom gain' }
  });
  settings.unshift(setting('gain', 'Gain preset', 'gain', 'choice', 'LINEARITY_14', {
    options: [{ value: 'LINEARITY_14', label: 'LINEARITY_14' }, { value: 'CUSTOM', label: 'CUSTOM' }]
  }));
  settings.push(setting('mixer_agc', 'Mixer AGC', 'gain', 'boolean', false, {
    editable: false, unavailable_reason: 'Use Custom gain'
  }));
  await page.setViewportSize({ width: 1680, height: 1000 });
  await mockTuners(page, [], { currentTuner: operatorTuner({ settings, spectrum_available: true,
    spectrum_target_id: 'diagnostic-a' }),
    targets: [{ target_id: 'diagnostic-a', label: 'Airspy R2',
      center_frequency_hz: 851_012_500, sample_rate_hz: 10_000_000 }] });
  await page.goto('/app.html?view=tuners');

  await expect(page.getByLabel('Gain preset')).toBeVisible();
  await expect(page.getByLabel('LNA gain (dB)')).toBeDisabled();
  await expect(page.getByLabel('Mixer AGC')).toBeDisabled();
  await expect(page.locator('.tuners-gain-section .tuners-setting')).toHaveCount(3);
  await expect(page.locator('[data-setting-id="lna_gain"]')
    .getByRole('button', { name: 'Select Custom gain to adjust' })).toBeVisible();
  await expect(page.locator('[data-setting-id="mixer_agc"]')
    .getByRole('button', { name: 'Select Custom gain to adjust' })).toBeVisible();
  await expect(page.locator('.tuners-selected-section').getByLabel('Gain preset')).toHaveCount(0);
  const [gainBox, selectedBox] = await Promise.all([
    page.locator('.tuners-gain-section').boundingBox(),
    page.locator('.tuners-selected-section').boundingBox()
  ]);
  expect(gainBox).not.toBeNull();
  expect(selectedBox).not.toBeNull();
  expect(gainBox.y).toBeGreaterThanOrEqual(selectedBox.y + selectedBox.height);
});

test('switching tuner families preserves each descriptor-defined gain and device control', async ({ page }) => {
  const rtl = tuner({ name: 'RTL-SDR', tuner_class: 'RTL', tuner_type: 'RAFAELMICRO_R820T',
    settings: [
      setting('master_gain', 'Master gain', 'gain', 'choice', 'AUTOMATIC', {
        options: [{ value: 'AUTOMATIC', label: 'Automatic' }, { value: 'MANUAL', label: 'Manual' }]
      }),
      setting('mixer_gain', 'Mixer gain', 'gain', 'choice', 'G_0', {
        options: [{ value: 'G_0', label: '0 dB' }, { value: 'G_10', label: '10 dB' }],
        editable: false, unavailable_reason: 'Use manual gain'
      }),
      setting('lna_gain', 'LNA gain', 'gain', 'choice', 'G_0', {
        options: [{ value: 'G_0', label: '0 dB' }, { value: 'G_10', label: '10 dB' }],
        editable: false, unavailable_reason: 'Use manual gain'
      }),
      setting('vga_gain', 'VGA gain', 'gain', 'choice', 'G_0', {
        options: [{ value: 'G_0', label: '0 dB' }, { value: 'G_10', label: '10 dB' }],
        editable: false, unavailable_reason: 'Use manual gain'
      }),
      setting('bias_t', 'Bias T', 'hardware', 'boolean', false)
    ] });
  const rsp = tuner({ id: 'tuner-b', name: 'RSPduo Tuner 1', tuner_class: 'SDRPLAY',
    tuner_type: 'RSP_DUO_1', settings: [
      setting('baseband_gain_reduction', 'Baseband gain reduction', 'gain', 'integer', 40,
        { minimum: 20, maximum: 59, step: 1, unit: 'dB' }),
      setting('lna', 'LNA state', 'gain', 'integer', 3, { minimum: 0, maximum: 9, step: 1 }),
      setting('agc_mode', 'AGC mode', 'gain', 'choice', 'DISABLE', {
        options: [{ value: 'DISABLE', label: 'Off' }, { value: 'CONTROL', label: 'Control' }]
      }),
      setting('rf_notch', 'RF notch', 'hardware', 'boolean', false),
      setting('dab_notch', 'DAB notch', 'hardware', 'boolean', false),
      setting('am_port', 'AM port', 'hardware', 'choice', 'PORT_1', {
        options: [{ value: 'PORT_1', label: 'Port 1' }, { value: 'PORT_2', label: 'Port 2' }]
      }),
      setting('am_notch', 'AM notch', 'hardware', 'boolean', false),
      setting('external_reference', 'External reference', 'hardware', 'boolean', false)
    ] });
  await mockTuners(page, [], { tuners: [rtl, rsp] });
  await page.goto('/app.html?view=tuners');

  await expect(page.getByLabel('Master gain')).toBeVisible();
  await expect(page.getByLabel('Mixer gain')).toBeDisabled();
  await expect(page.getByLabel('LNA gain')).toBeDisabled();
  await expect(page.getByLabel('VGA gain')).toBeDisabled();
  await expect(page.locator('[data-setting-id="mixer_gain"]')
    .getByRole('button', { name: 'Select Manual gain to adjust' })).toBeVisible();
  await expect(page.getByLabel('Bias T')).toBeEnabled();

  await page.locator('.tuners-list-item').filter({ hasText: 'RSPduo Tuner 1' }).click();
  await expect(page.getByLabel('Baseband gain reduction (dB)')).toHaveValue('40');
  await expect(page.getByLabel('LNA state')).toHaveValue('3');
  await expect(page.getByLabel('AGC mode')).toHaveValue('DISABLE');
  for (const label of ['RF notch', 'DAB notch', 'AM notch', 'External reference']) {
    await expect(page.getByLabel(label, { exact: true })).toBeEnabled();
  }
  await expect(page.getByLabel('AM port')).toHaveValue('PORT_1');
  await expect(page.getByLabel('Bias T')).toHaveCount(0);
  await expect(page.getByLabel('Master gain')).toHaveCount(0);
});

test('gain controls stay available when spectrum setup fails', async ({ page }) => {
  const mutations = [];
  await mockTuners(page, mutations, { currentTuner: operatorTuner(), snapPresetError: true });
  await page.goto('/app.html?view=tuners');

  await expect(page.getByText('Spectrum setup failed')).toBeVisible();
  await expect(page.locator('.tuners-controls .tuners-gain-section')).toBeVisible();
  await expect(page.getByLabel('LNA gain (dB)')).toHaveValue('8');
  await page.getByLabel('LNA gain (dB)').focus();
  await page.getByLabel('LNA gain (dB)').press('ArrowRight');
  await page.getByLabel('LNA gain (dB)').press('ArrowRight');
  await page.locator('[data-setting-id="lna_gain"]').getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => mutations.at(-1)).toEqual({ type: 'setting', setting: 'lna_gain', value: 10 });
});

test('desktop receiver picker and selected tuner form two columns with settings below signal', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 1000 });
  await mockTuners(page, [], { currentTuner: operatorTuner({
    sample_rate_hz: 2_400_000, configured_sample_rate_hz: 2_400_000
  }) });
  await page.goto('/app.html?view=tuners');
  await expect(page.locator('.tuners-main > .tuners-selected-section > .tuners-details')).toHaveCount(1);
  await expect(page.locator('.tuners-selected-section > .tuners-readouts')).toHaveCount(1);
  await expect(page.locator('.tuners-readouts .tuners-channel-readout .tuners-readout-value'))
    .toHaveText('0');
  const [receiverBox, selectedBox, controlsBox, spectrumBox, readoutsBox] = await Promise.all([
    page.locator('.tuners-receivers').boundingBox(),
    page.locator('.tuners-selected-section').boundingBox(),
    page.locator('.tuners-controls').boundingBox(),
    page.locator('.tuners-spectrum').boundingBox(),
    page.locator('.tuners-readouts').boundingBox()
  ]);
  expect(receiverBox).not.toBeNull();
  expect(selectedBox).not.toBeNull();
  expect(controlsBox).not.toBeNull();
  expect(receiverBox.width).toBeGreaterThanOrEqual(264);
  expect(receiverBox.width).toBeLessThanOrEqual(310);
  expect(receiverBox.x + receiverBox.width).toBeLessThan(selectedBox.x);
  expect(Math.abs(receiverBox.y - selectedBox.y)).toBeLessThanOrEqual(1);
  expect(controlsBox.x).toBeCloseTo(selectedBox.x, 0);
  expect(controlsBox.y).toBeGreaterThanOrEqual(selectedBox.y + selectedBox.height);
  expect(readoutsBox.y).toBeGreaterThanOrEqual(spectrumBox.y + spectrumBox.height);

  await page.locator('.tuners-common-details > summary').click();
  const limits = page.locator('.tuners-common-limits');
  const sample = page.locator('.tuners-common-sample');
  await expect(limits.locator(':scope > [data-setting-id="minimum_frequency_mhz"]')).toHaveCount(1);
  await expect(limits.locator(':scope > [data-setting-id="maximum_frequency_mhz"]')).toHaveCount(1);
  await expect(limits.locator(':scope > [data-setting-id="reset_frequency_extents"]')).toHaveCount(1);
  await expect(limits.locator(':scope > [data-setting-id="sample_rate"]')).toHaveCount(0);
  await expect(sample.locator(':scope > [data-setting-id="sample_rate"]')).toHaveCount(1);
  await expect(page.locator('.tuners-readouts').getByLabel('Sample rate')).toHaveValue('10 MHz');
});

test('disabled tuner help icons align with their controls on desktop and mobile', async ({ page }) => {
  const settings = operatorSettings({
    lna_gain: { editable: false, unavailable_reason: 'Use Setup' },
    automatic_ppm: { value: true },
    center_frequency_locked: { value: true }
  });
  await mockTuners(page, [], { currentTuner: operatorTuner({ settings }) });
  await page.goto('/app.html?view=tuners');

  for (const id of ['sample_rate', 'frequency_correction_ppm', 'lna_gain', 'frequency_mhz']) {
    await expect(page.locator(`[data-tuner-help="${id}"]`)).toBeVisible();
  }

  for (const viewport of [{ width: 1680, height: 1000 }, { width: 390, height: 844 }]) {
    await page.setViewportSize(viewport);
    if (viewport.width === 390) {
      await page.reload();
      await expect(page.locator('[data-tuner-help="sample_rate"]')).toBeVisible();
    }
    const alignment = await page.evaluate(() => {
      const bounds = (selector) => {
        const element = document.querySelector(selector);
        if (!element) throw new Error(`Missing tuner control: ${selector}`);
        const rect = element.getBoundingClientRect();
        return { centerY: rect.top + rect.height / 2, right: rect.right };
      };
      const pairs = [
        ['sample_rate', '[data-setting-id="sample_rate"] .ui-select-frame'],
        ['frequency_correction_ppm', '[data-setting-id="frequency_correction_ppm"] .ui-input'],
        ['lna_gain', '[data-setting-id="lna_gain"] .ui-range'],
        ['frequency_mhz', '.tuners-frequency-digits']
      ];
      const verticalOffsets = Object.fromEntries(pairs.map(([id, control]) => [id,
        Math.abs(bounds(`[data-tuner-help="${id}"]`).centerY - bounds(control).centerY)]));
      return {
        verticalOffsets,
        sampleRightInset: bounds('.tuners-common-sample').right -
          bounds('[data-tuner-help="sample_rate"]').right,
        centerRightInset: bounds('.tuners-center-host').right -
          bounds('[data-tuner-help="frequency_mhz"]').right,
        pageOverflow: document.documentElement.scrollWidth - window.innerWidth,
        selectedOverflow: document.querySelector('.tuners-selected-section').scrollWidth -
          document.querySelector('.tuners-selected-section').clientWidth
      };
    });
    for (const offset of Object.values(alignment.verticalOffsets)) {
      expect(offset).toBeLessThanOrEqual(2);
    }
    expect(Math.abs(alignment.sampleRightInset - alignment.centerRightInset)).toBeLessThanOrEqual(2);
    expect(alignment.pageOverflow).toBeLessThanOrEqual(1);
    expect(alignment.selectedOverflow).toBeLessThanOrEqual(1);
  }
});

test('medium-width tuner controls remain below the selected tuner without horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await mockTuners(page, []);
  await page.goto('/app.html?view=tuners');

  const [receiverBox, selectedBox, controlsBox] = await Promise.all([
    page.locator('.tuners-receivers').boundingBox(),
    page.locator('.tuners-selected-section').boundingBox(),
    page.locator('.tuners-controls').boundingBox()
  ]);
  expect(receiverBox.width).toBeGreaterThanOrEqual(264);
  expect(selectedBox.x).toBeGreaterThan(receiverBox.x + receiverBox.width);
  expect(controlsBox.y).toBeGreaterThanOrEqual(selectedBox.y + selectedBox.height);
  expect(Math.abs(controlsBox.x - selectedBox.x)).toBeLessThanOrEqual(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth))
    .toBeLessThanOrEqual(1);
});

test('tuner without an editable center frequency keeps a read-only value without overflow', async ({ page }) => {
  await page.setViewportSize({ width: 960, height: 800 });
  await mockTuners(page, [], { currentTuner: tuner() });
  await page.goto('/app.html?view=tuners');

  const readouts = page.locator('.tuners-readouts');
  await expect(readouts.locator('.tuners-readout-center .tuners-readout-value'))
    .toHaveText('851.01250 MHz');
  await expect(readouts.locator('.tuners-center-frequency')).toHaveCount(0);
  await expect(readouts.locator('.tuners-lock-readout')).toBeHidden();
  await expect(readouts).toHaveAttribute('data-count', '3');
  expect(await page.locator('.tuners-selected-section').evaluate(
    (element) => element.scrollWidth <= element.clientWidth)).toBe(true);
});

test('Setup unlocks setup-only controls and remains unavailable to channels', async ({ page }) => {
  const current = operatorTuner({ operator_state: 'setup', channel_count: 0, spectrum_available: true,
    spectrum_target_id: 'diagnostic-a' });
  await mockTuners(page, [], { currentTuner: current,
    targets: [{ target_id: 'diagnostic-a', label: 'Airspy R2' }] });
  await page.goto('/app.html?view=tuners');

  const mode = page.getByRole('group', { name: 'Tuner mode' });
  await expect(mode.getByRole('button', { name: 'Setup' })).toHaveAttribute('aria-pressed', 'true');
  await expect(mode.getByRole('button', { name: 'Live' })).toHaveAttribute('aria-pressed', 'false');
  await expect(page.locator('.tuners-readouts').getByLabel('Sample rate')).toBeEnabled();
  await expect(page.locator('.tuners-device-section')).toBeVisible();
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
  await expect(page.locator('.tuners-lock-readout .tuners-center-lock')).toHaveCount(1);
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

test('segmented Setup mode stops channels once and offers one restore attempt', async ({ page }) => {
  const mutations = [];
  const state = { currentTuner: operatorTuner({ channel_count: 2 }) };
  await mockTuners(page, mutations, state);
  await page.goto('/app.html?view=tuners');

  const mode = page.getByRole('group', { name: 'Tuner mode' });
  const setup = mode.getByRole('button', { name: 'Setup' });
  await expect(setup).toBeVisible();
  await expect(mode.getByRole('button', { name: 'Live' })).toHaveAttribute('aria-pressed', 'true');
  await expect(setup).toHaveAttribute('title', 'Keep tuner on for adjustments; active channels stop');
  await setup.click();
  const stop = page.getByRole('dialog', { name: 'Enter Setup' });
  await expect(stop).toContainText('2 active channels will stop');
  await expect(stop).toContainText('unavailable to channels until you return to Live');
  await stop.getByRole('button', { name: 'Enter Setup', exact: true }).click();
  await expect(stop).toBeHidden();
  await expect(mode.getByRole('button', { name: 'Setup' })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.tuners-recovery')).toContainText('2 channels stopped for Setup');
  await expect(page.getByRole('button', { name: 'View stopped channels' })).toBeVisible();
  expect(mutations.filter((mutation) => mutation.type === 'state')).toEqual([{ type: 'state', state: 'setup' }]);

  await expect(mode.getByRole('button', { name: 'Live' })).toBeVisible();
  await page.getByRole('button', { name: 'Resume & go Live' }).click();
  const restore = page.getByRole('dialog', { name: 'Resume previous channels' });
  await expect(restore).toContainText('2 channels stopped');
  await expect(restore).toContainText('Voice channels are temporary and are not resumed.');
  await restore.getByRole('button', { name: 'Resume channels' }).click();
  await expect(restore).toBeHidden();
  expect(mutations.filter((mutation) => mutation.type === 'restore')).toHaveLength(1);
  await expect(page.getByRole('button', { name: /^Restart \d/ })).toHaveCount(0);
});

test('stopped-channel modal groups control and voice channels by their primary', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const stoppedChannels = [
    { id: 'control-a', name: 'GCRCN', parent_id: 'control-a', kind: 'standard',
      frequency_hz: 856_162_500, system: 'GCRCN', site: 'East Simulcast' },
    { id: 'traffic-a', name: 'T-GCRCN', parent_id: 'control-a', kind: 'traffic',
      frequency_hz: 857_112_500, system: 'GCRCN', site: 'East Simulcast' },
    { id: 'traffic-b', name: 'T-GCRCN', parent_id: 'control-a', kind: 'traffic',
      frequency_hz: 857_362_500, system: 'GCRCN', site: 'East Simulcast' },
    { id: 'control-b', name: 'Fire Dispatch', parent_id: 'control-b', kind: 'standard',
      frequency_hz: 852_762_500, system: 'County Fire', site: 'North' },
    { id: 'traffic-only', name: 'T-METRO', parent_id: 'missing-control', kind: 'traffic',
      frequency_hz: 858_212_500, system: 'METRO', site: 'West' }
  ];
  await mockTuners(page, [], { currentTuner: operatorTuner({ operator_state: 'setup',
    stopped_channels: stoppedChannels }) });
  await page.goto('/app.html?view=tuners');
  await page.getByRole('button', { name: 'View stopped channels' }).click();

  const modal = page.getByRole('dialog', { name: 'Stopped channels' });
  await expect(modal.getByText('GCRCN', { exact: true })).toHaveCount(1);
  await expect(modal).toContainText('1 Control · 856.16250 MHz');
  await expect(modal).toContainText('2 Voice channels');
  await expect(modal.getByText('T-GCRCN', { exact: true })).toHaveCount(0);
  await expect(modal.getByText('Fire Dispatch', { exact: true })).toHaveCount(1);
  await expect(modal).toContainText('1 Channel · 852.76250 MHz');
  await expect(modal).toContainText('METRO');
  await expect(modal).toContainText('1 Voice channel');
  await expect(modal).toHaveScreenshot('tuners-stopped-channels-grouped-light-desktop.png');

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(modal).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth))
    .toBeLessThanOrEqual(1);
  expect(await modal.evaluate((element) => element.scrollWidth - element.clientWidth))
    .toBeLessThanOrEqual(1);
  await expect(modal).toHaveScreenshot('tuners-stopped-channels-grouped-light-mobile.png');
});

test('segmented Setup mode starts a disabled tuner and prompts for stopped channels', async ({ page }) => {
  const mutations = [];
  const current = operatorTuner({ operator_state: 'disabled', available: false,
    stopped_channels: [{ id: 'channel-a', name: 'County Control' }], settings: operatorSettings() });
  await mockTuners(page, mutations, { currentTuner: current });
  await page.goto('/app.html?view=tuners');
  const mode = page.getByRole('group', { name: 'Tuner mode' });
  await expect(mode.getByRole('button', { name: 'Disabled' })).toHaveAttribute('aria-pressed', 'true');
  await expect(mode.getByRole('button', { name: 'Live' })).toHaveAttribute('aria-disabled', 'true');
  await expect(mode.getByRole('button', { name: 'Live' })).toHaveAttribute('title', 'Start Setup first');
  await mode.getByRole('button', { name: 'Setup' }).click();
  await expect(page.getByRole('dialog', { name: 'Resume previous channels' })).toBeVisible();
  await expect(page.getByText('1 channel stopped when this tuner entered Setup')).toBeVisible();
  await page.getByRole('button', { name: 'Not now' }).click();
  await expect(mode.getByRole('button', { name: 'Setup' })).toHaveAttribute('aria-pressed', 'true');
});

test('segmented Disabled mode confirms hardware shutdown', async ({ page }) => {
  const mutations = [];
  await mockTuners(page, mutations, { currentTuner: operatorTuner() });
  await page.goto('/app.html?view=tuners');

  const mode = page.getByRole('group', { name: 'Tuner mode' });
  await mode.getByRole('button', { name: 'Disabled' }).click();
  const confirmation = page.getByRole('dialog', { name: 'Disable tuner' });
  await expect(confirmation).toContainText('The tuner hardware will also stop');
  await confirmation.getByRole('button', { name: 'Disable tuner', exact: true }).click();
  await expect(confirmation).toBeHidden();
  await expect(mode.getByRole('button', { name: 'Disabled' })).toHaveAttribute('aria-pressed', 'true');
  expect(mutations.filter((mutation) => mutation.type === 'state')).toEqual([
    { type: 'state', state: 'disabled' }
  ]);
});

test('Go live without resuming explains and clears the stopped-channel choice', async ({ page }) => {
  const mutations = [];
  const current = operatorTuner({ operator_state: 'setup', stopped_channels: [
    { id: 'channel-a', name: 'County Control' }, { id: 'channel-b', name: 'City Dispatch' }
  ] });
  await mockTuners(page, mutations, { currentTuner: current });
  await page.goto('/app.html?view=tuners');

  const action = page.getByRole('group', { name: 'Tuner mode' })
    .getByRole('button', { name: 'Live' });
  await action.click();
  const confirmation = page.getByRole('dialog', { name: 'Go live without resuming' });
  await expect(confirmation).toContainText('will no longer remember them for the Resume action');
  await confirmation.getByRole('button', { name: 'Go live without resuming', exact: true }).click();
  await expect(confirmation).toBeHidden();
  await expect(page.getByRole('group', { name: 'Tuner mode' })
    .getByRole('button', { name: 'Live' })).toHaveAttribute('aria-pressed', 'true');
  expect(mutations.filter((mutation) => mutation.type === 'state')).toEqual([{ type: 'state', state: 'live' }]);
  expect(mutations.filter((mutation) => mutation.type === 'restore')).toHaveLength(0);
});

test('delayed restore failure reports only channels that could not start', async ({ page }) => {
  const mutations = [];
  const current = operatorTuner({ operator_state: 'setup', stopped_channels: [
    { id: 'channel-a', name: 'County Control' }, { id: 'channel-b', name: 'City Dispatch' }
  ] });
  await mockTuners(page, mutations, { currentTuner: current, restoreCompleteAfter: 3,
    restoreFailed: [{ id: 'channel-b', name: 'City Dispatch' }] });
  await page.goto('/app.html?view=tuners');
  await page.getByRole('button', { name: 'Resume & go Live' }).click();
  const restoring = page.getByRole('dialog', { name: 'Resume previous channels' });
  await restoring.getByRole('button', { name: 'Resume channels' }).click();
  await expect(restoring.getByText('Resuming channels…')).toBeVisible();
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
  await page.getByRole('button', { name: 'Resume & go Live' }).click();
  const restore = page.getByRole('dialog', { name: 'Resume previous channels' });
  await restore.getByRole('button', { name: 'Resume channels' }).click();
  await expect(restore.getByText('Tuner unavailable')).toBeVisible();
  await expect(page.getByText('Eligible channels resumed')).toHaveCount(0);
  await expect(page.getByRole('dialog', { name: 'Couldn’t start' })).toHaveCount(0);
  expect(mutations.filter((mutation) => mutation.type === 'restore')).toHaveLength(1);
});

test('Plan tuner placement opens every eligible tuner and running frequency in a new tab',
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
  const placement = page.getByRole('button', { name: 'Plan tuner placement', exact: true });
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
  await expect(dialog.locator('footer.ui-modal-footer')).toHaveCount(1);
  await expect(dialog.getByText('The WAV file will stay on disk.')).toBeVisible();
  await dialog.getByRole('button', { name: 'Remove recording tuner', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText('No tuners found')).toBeVisible();
  expect(mutations.at(-1)).toEqual({ type: 'remove' });
});
