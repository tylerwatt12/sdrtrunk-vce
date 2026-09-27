const { expect, test } = require('@playwright/test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');

function tuner(overrides = {}) {
  return {
    id: 'tuner-a', name: 'Airspy R2', tuner_class: 'AIRSPY', tuner_type: 'AIRSPY_R820T',
    status: 'ENABLED', enabled: true, available: true, channel_count: 0,
    frequency_hz: 851_012_500, sample_rate_hz: 10_000_000,
    planner: { model: 'airspy', rate_hz: 10_000_000 },
    spectrum_target_id: null, spectrum_available: false,
    device_group: { id: 'group-a', kind: 'single', role: 'member' },
    settings: [{ id: 'lna_gain', label: 'LNA gain', group: 'gain', kind: 'integer', value: 8,
      pending_value: null, minimum: 0, maximum: 15, step: 1, unit: 'dB',
      scope: 'tuner', requires_idle: false, editable: true }],
    ...overrides
  };
}

async function mockTuners(page, mutations, state = {}) {
  const preferenceModule = await import(pathToFileURL(resolve(__dirname,
    '../../../../..', 'stats-web/assets/core/preference-schema.js')).href);
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
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
      await respond({ tuners: Array.isArray(state.tuners) ? state.tuners : [state.currentTuner || tuner()] });
      return;
    }
    if (path === '/api/v1/admin/tuners/rf-analysis' && request.method() === 'GET') {
      await respond(state.rfAnalysis || { tuners: [{ id: 'tuner-a', model: 'airspy', rate_hz: 10000000 }],
        frequencies_hz: [851012500] });
      return;
    }
    if (path === '/api/v1/admin/tuners/rescan' && request.method() === 'POST') {
      mutations.push('rescan');
      await respond({ status: 'scanning' }, 202);
      return;
    }
    if (path === '/api/v1/admin/tuners/tuner-a' && request.method() === 'DELETE') {
      mutations.push('remove');
      state.tuners = [];
      await route.fulfill({ status: 204 });
      return;
    }
    if (path === '/api/v1/admin/tuners/tuner-a/enabled' && request.method() === 'PUT') {
      mutations.push(JSON.parse(request.postData()));
      await respond({ status: 'queued' }, 202);
      return;
    }
    if (path === '/api/v1/admin/tuners/tuner-a/settings/lna_gain' && request.method() === 'PUT') {
      mutations.push(JSON.parse(request.postData()));
      await respond({ tuner: tuner({ settings: [tuner().settings[0]] }) });
      return;
    }
    if (path === '/api/v1/admin/tuners/recordings' && request.method() === 'GET') {
      await respond({ entries: [{ id: 'file-a', name: 'debug-851.wav', size_bytes: 4_000_000_000,
        sample_rate_hz: 2_400_000, suggested_center_frequency_hz: 851_012_500 }],
      rejected_count: 0, truncated: false });
      return;
    }
    if (path === '/api/v1/admin/tuners/recordings' && request.method() === 'POST') {
      mutations.push(JSON.parse(request.postData()));
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

test('admin tuner workspace renders generic controls and directory recordings', async ({ page }) => {
  const mutations = [];
  await mockTuners(page, mutations);
  await page.goto('/app.html?view=tuners');
  await expect(page.getByRole('heading', { name: 'Tuners', exact: true })).toBeVisible();
  await expect(page.locator('#preference-status')).toBeHidden();
  await expect(page.getByRole('button', { name: /Airspy R2/ })).toBeVisible();
  await expect(page.getByLabel('LNA gain (dB)')).toHaveValue('8');
  await expect(page.getByText('No live samples—the tuner is idle.').first()).toBeVisible();
  await page.waitForLoadState('networkidle');
  await expect(page.locator('#preference-status')).toBeHidden();
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect(page.locator('main')).toHaveScreenshot('tuners-idle-light-desktop.png');

  await page.getByLabel('LNA gain (dB)').fill('10');
  await page.getByRole('button', { name: 'Apply' }).click();
  await expect.poll(() => mutations.length).toBe(1);
  expect(mutations[0]).toEqual({ value: 10 });

  await page.getByText('Recording tuner files · debugging').click();
  await expect(page.getByRole('option', { name: 'debug-851.wav' })).toHaveCount(1);
  await expect(page.getByLabel('Center frequency (MHz)')).toHaveValue('851.0125');
  await page.getByRole('button', { name: 'Add recording tuner' }).click();
  await expect.poll(() => mutations.length).toBe(2);
  expect(mutations[1]).toEqual({ file_id: 'file-a', center_frequency_hz: 851_012_500 });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => window.scrollTo(0, 0));
  const analyze = page.getByRole('button', { name: 'Analyze all tuners at RadioResolve' });
  await expect(analyze).toBeInViewport();
  const analyzeBounds = await analyze.boundingBox();
  expect(analyzeBounds.x).toBeGreaterThanOrEqual(0);
  expect(analyzeBounds.x + analyzeBounds.width).toBeLessThanOrEqual(390);
  await expect(page.locator('main')).toHaveScreenshot('tuners-recordings-light-mobile.png');
});

test('an idle tuner gains spectrum when a channel starts', async ({ page }) => {
  const state = { currentTuner: tuner(), targets: [] };
  await mockTuners(page, [], state);
  await page.goto('/app.html?view=tuners');
  await expect(page.locator('select[aria-label="Receiver window"]')).toHaveValue('');
  state.currentTuner = tuner({ channel_count: 1, spectrum_target_id: 'diagnostic-a',
    spectrum_available: true });
  state.targets = [{ target_id: 'diagnostic-a', label: 'Airspy R2' }];
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.locator('select[aria-label="Receiver window"]')).toHaveValue('diagnostic-a');
});

test('inventory polling does not discard an unsaved setting draft', async ({ page }) => {
  await page.clock.install();
  await mockTuners(page, []);
  await page.goto('/app.html?view=tuners');
  const gain = page.getByLabel('LNA gain (dB)');
  await gain.fill('12');
  await page.clock.runFor(5_100);
  await expect(gain).toHaveValue('12');
  await expect(gain).toBeFocused();
  await gain.blur();
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.getByLabel('LNA gain (dB)')).toHaveValue('12');
});

test('setting groups come from descriptors and queued values show current versus pending', async ({ page }) => {
  const currentTuner = tuner({ channel_count: 2, pending: true, settings: [
    { id: 'sample_rate', label: 'Sample rate', group: 'frequency', kind: 'choice', value: '2.4 MHz',
      pending_value: '1.2 MHz', options: [{ value: '2.4 MHz', label: '2.4 MHz' },
        { value: '1.2 MHz', label: '1.2 MHz' }], requires_idle: true, editable: true },
    { id: 'gain_x', label: 'Custom gain control', group: 'gain', kind: 'integer', value: 8,
      minimum: 0, maximum: 15, step: 1, pending_value: null, requires_idle: false, editable: true },
    { id: 'bias_t', label: 'Bias T', group: 'hardware', kind: 'boolean', value: false,
      pending_value: null, requires_idle: false, editable: true },
    { id: 'frequency_correction_ppm', label: 'Frequency correction', group: 'calibration',
      kind: 'decimal', value: 0.1, pending_value: null, requires_idle: false, editable: true }
  ] });
  await mockTuners(page, [], { currentTuner });
  await page.goto('/app.html?view=tuners');
  await expect(page.getByRole('heading', { name: 'Gain controls' })).toBeVisible();
  await expect(page.getByText('Frequency & allocation', { exact: true })).toBeVisible();
  await expect(page.getByText('Hardware controls', { exact: true })).toBeVisible();
  await expect(page.getByText('Calibration', { exact: true })).toBeVisible();
  await expect(page.getByText('Current: 2.4 MHz → Queued: 1.2 MHz')).toBeVisible();
  await expect(page.getByRole('button', { name: /Airspy R2/ })).toContainText('Queued');
});

test('RF analysis sends all eligible tuners and current running frequencies', async ({ page }) => {
  const state = { rfAnalysis: { tuners: [
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

test('RF analysis gives a clear message when no supported tuner is discovered', async ({ page }) => {
  await mockTuners(page, [], { tuners: [], rfAnalysis: { tuners: [], frequencies_hz: [] } });
  await page.goto('/app.html?view=tuners');
  await page.getByRole('button', { name: 'Analyze all tuners at RadioResolve' }).click();
  await expect(page.getByText('No supported physical tuners are available for external analysis.'))
    .toBeVisible();
});

test('USB rescan and recording removal stay admin-scoped and preserve the file', async ({ page }) => {
  const mutations = [];
  const state = { tuners: [tuner({ name: 'debug-851.wav', tuner_class: 'recording',
    tuner_type: 'recording', settings: [] })] };
  await mockTuners(page, mutations, state);
  await page.goto('/app.html?view=tuners');
  await page.getByRole('button', { name: 'Rescan USB' }).click();
  await expect.poll(() => mutations.length).toBe(1);
  expect(mutations[0]).toBe('rescan');
  await page.locator('#selected-tuner-remove').click();
  await expect(page.getByText('The WAV file will stay on disk.')).toBeVisible();
  await page.getByRole('button', { name: 'Remove recording tuner' }).last().click();
  await expect.poll(() => mutations.length).toBe(2);
  expect(mutations[1]).toBe('remove');
  await expect(page.getByText('No tuners have been discovered.')).toBeVisible();
});

test('an error-state tuner offers restart without disabling it', async ({ page }) => {
  const mutations = [];
  await mockTuners(page, mutations, { currentTuner: tuner({ status: 'error', available: false }) });
  await page.goto('/app.html?view=tuners');
  await page.getByRole('button', { name: 'Restart tuner' }).click();
  await expect.poll(() => mutations.length).toBe(1);
  expect(mutations[0]).toEqual({ enabled: true });
});
