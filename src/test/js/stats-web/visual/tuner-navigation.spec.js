const { expect, test } = require('@playwright/test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');

const root = resolve(__dirname, '../../../../..');
const mode = (page) => page.getByRole('group', { name: 'Tuner mode', exact: true });
const departure = (page) => page.getByRole('alertdialog', { name: 'Leave this page?', exact: true });

async function install(page, { operatorState = 'setup', theme = 'light' } = {}) {
  const preferenceModule = await import(pathToFileURL(resolve(root,
    'stats-web/assets/core/preference-schema.js')).href);
  const receiver = {
    id: 'tuner-a', name: 'Airspy R2', nickname: 'North antenna', tuner_class: 'AIRSPY',
    tuner_type: 'AIRSPY_R820T', status: 'ENABLED', available: operatorState !== 'disabled',
    operator_state: operatorState, operator_generation: 1, transition: null,
    channel_count: 0, frequency_hz: 851012500, configured_frequency_hz: 851012500,
    sample_rate_hz: 10000000, configured_sample_rate_hz: 10000000,
    usable_bandwidth_hz: 9000000, minimum_frequency_hz: 24000000, maximum_frequency_hz: 1800000000,
    spectrum_target_id: null, spectrum_available: false,
    device_group: { id: 'group-a', kind: 'single', role: 'member' },
    stopped_channels: operatorState === 'setup' ? [{ id: 'channel-a', name: 'County Control' }] : [],
    restore_result: null,
    settings: [
      { id: 'frequency_mhz', label: 'Center frequency', group: 'frequency', kind: 'decimal',
        value: 851.0125, minimum: 24, maximum: 1800, step: 0.00001, unit: 'MHz',
        scope: 'tuner', availability: 'live', editable: true, dependencies: [] },
      { id: 'center_frequency_locked', label: 'Lock center', group: 'frequency', kind: 'boolean',
        value: false, scope: 'tuner', availability: 'live', editable: true, dependencies: [] }
    ]
  };
  const state = { receiver, requests: [], lease: null, leaseSequence: 0 };
  const tourHistory = { version: 1, dismissed: { welcome: 1, tuners: 3, 'tuner-spectrum': 5, 'channel-setup': 5 } };
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    const body = JSON.parse(request.postData() || '{}');
    state.requests.push({ path, method, body });
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json',
      body: JSON.stringify({ data }) });
    if (path === '/api/v1/auth/session') return respond({ configured: true, authenticated: true,
      tier: 'admin', username: 'admin', csrf_token: 'test-token', guide_dismissal_scope: 'tuner-navigation-tests',
      capabilities: { 'web-access': true, dashboard: true, 'admin-tuners': true, 'tuner-spectrum': true,
        'admin-channels': true, 'admin-aliases': true, 'admin-settings': true } });
    if (path === '/api/v1/me/tour-dismissals') return respond(tourHistory);
    if (path === '/api/v1/me/preferences') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, preferences: { ...preferenceModule.defaults,
        appearance: { theme, hue: null } } }) });
    if (path === '/api/v1/admin/tuners') return respond({ tuners: [receiver] });
    if (path === '/api/v1/admin/tuners/tuner-a/state' && method === 'PUT') {
      if (body.operator_generation != null && body.operator_generation !== receiver.operator_generation)
        return route.fulfill({ status: 409, json: { error: { code: 'tuner_state_changed', message: 'The tuner changed.' } } });
      receiver.operator_state = body.state;
      receiver.operator_generation += 1;
      if (body.state === 'live') {
        receiver.channel_count = receiver.stopped_channels.length;
        receiver.stopped_channels = [];
      }
      return respond({ status: 'accepted' }, 202);
    }
    if (path === '/api/v1/admin/tuners/tuner-a/browse') {
      if (method === 'DELETE') {
        state.lease = null;
        return respond(null, 204);
      }
      state.lease ||= `monitor-${++state.leaseSequence}`;
      return respond({ lease_id: state.lease, expires_at_epoch_ms: Date.now() + 30000,
        can_tune: receiver.operator_state === 'setup', takeover: false,
        stopped_channels: [], tuner: receiver });
    }
    if (path === '/api/v1/diagnostics/tuners') return respond({ rows: [] });
    if (path === '/api/v1/spectrum-snap-presets') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, country_code: 'US', country_label: 'United States',
        countries: [{ code: 'US', label: 'United States' }], scopes: [] }) });
    if (path === '/api/v1/admin/channels') return respond({ revision: 1, channels: [] });
    if (path === '/api/v1/admin/channels/protocols') return respond({ profiles: [] });
    if (path === '/api/v1/admin/channels/options') return respond({ alias_lists: [] });
    if (path === '/api/v1/dashboard/main') return respond({ quality: { current: [] },
      directory: { systems: [], channels: [] } });
    return respond({});
  });
  return state;
}

async function navigate(page, view) {
  await page.locator(`a[data-view="${view}"]`).first().evaluate((link) => link.click());
}

function stateWrites(state) {
  return state.requests.filter(({ path, method }) => path.endsWith('/state') && method === 'PUT');
}

test('leaving Setup uses the agreed wording and Stay here preserves the tuner and route', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=tuners');
  await expect(mode(page).getByRole('button', { name: 'Setup', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await navigate(page, 'dashboard');
  const prompt = departure(page);
  await expect(prompt).toContainText('North antenna is in Setup. Some channels have been stopped. Go Live again?');
  await expect(prompt.locator('.ui-modal-footer').getByRole('button')).toHaveText(['Stay here', 'Leave stopped', 'Go Live & leave']);
  await expect(prompt.getByRole('button', { name: 'Stay here', exact: true })).toBeFocused();
  await prompt.getByRole('button', { name: 'Stay here', exact: true }).click();
  await expect(prompt).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Tuners', exact: true })).toBeVisible();
  expect(new URL(page.url()).searchParams.get('view')).toBe('tuners');
  expect(state.receiver.operator_state).toBe('setup');
  expect(stateWrites(state)).toEqual([]);
});

test('Leave stopped visits another screen without restarting and prompts again after returning', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=tuners');
  await expect(mode(page)).toBeVisible();
  await navigate(page, 'dashboard');
  await departure(page).getByRole('button', { name: 'Leave stopped', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  expect(state.receiver.operator_state).toBe('setup');
  expect(state.receiver.stopped_channels).toHaveLength(1);
  expect(stateWrites(state)).toEqual([]);
  await navigate(page, 'tuners');
  await expect(mode(page).getByRole('button', { name: 'Setup', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await navigate(page, 'dashboard');
  await expect(departure(page)).toBeVisible();
  await departure(page).getByRole('button', { name: 'Stay here', exact: true }).click();
});

test('Go Live and leave resumes once before visiting another screen', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=tuners');
  await expect(mode(page)).toBeVisible();
  await navigate(page, 'dashboard');
  await departure(page).getByRole('button', { name: 'Go Live & leave', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  expect(stateWrites(state)).toHaveLength(1);
  expect(stateWrites(state)[0].body).toMatchObject({ state: 'live' });
  expect(state.receiver.operator_state).toBe('live');
  expect(state.receiver.channel_count).toBe(1);
});

test('Tuners and Browse Spectrum retain Setup as one workspace until leaving both', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=tuners');
  await expect(mode(page)).toBeVisible();
  await navigate(page, 'tuner-spectrum');
  await expect(page.getByRole('heading', { name: 'Browse Spectrum', exact: true })).toBeVisible();
  await expect(mode(page).getByRole('button', { name: 'Setup', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(departure(page)).toHaveCount(0);
  await navigate(page, 'tuners');
  await expect(page.getByRole('heading', { name: 'Tuners', exact: true })).toBeVisible();
  await expect(mode(page).getByRole('button', { name: 'Setup', exact: true })).toHaveAttribute('aria-pressed', 'true');
  expect(stateWrites(state)).toEqual([]);
  expect(state.receiver.operator_state).toBe('setup');
  await navigate(page, 'dashboard');
  await expect(departure(page)).toBeVisible();
  await departure(page).getByRole('button', { name: 'Leave stopped', exact: true }).click();
});

test('browser Back asks before leaving and cancellation restores the current tuner page', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=dashboard');
  await navigate(page, 'tuners');
  await expect(mode(page)).toBeVisible();
  await page.goBack();
  await expect(departure(page)).toBeVisible();
  await departure(page).getByRole('button', { name: 'Stay here', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Tuners', exact: true })).toBeVisible();
  expect(new URL(page.url()).searchParams.get('view')).toBe('tuners');
  expect(stateWrites(state)).toEqual([]);
  await page.goBack();
  await departure(page).getByRole('button', { name: 'Leave stopped', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
});

test('a newer Disabled decision is not undone by a stale tuner page', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=tuners');
  await expect(mode(page)).toBeVisible();
  state.receiver.operator_state = 'disabled';
  state.receiver.operator_generation += 1;
  state.receiver.available = false;
  await navigate(page, 'dashboard');
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(departure(page)).toHaveCount(0);
  expect(state.receiver.operator_state).toBe('disabled');
  expect(stateWrites(state)).toEqual([]);
});

test('a Disable made in another tab while the departure prompt is open wins over Go Live', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=tuners');
  await expect(mode(page)).toBeVisible();
  await navigate(page, 'dashboard');
  await expect(departure(page)).toBeVisible();
  state.receiver.operator_state = 'disabled';
  state.receiver.operator_generation += 1;
  state.receiver.available = false;
  await departure(page).getByRole('button', { name: 'Go Live & leave', exact: true }).click();
  await expect.poll(() => stateWrites(state).length > 0 ||
    new URL(page.url()).searchParams.get('view') === 'dashboard').toBe(true);
  expect(state.receiver.operator_state).toBe('disabled');
  expect(stateWrites(state).length).toBeLessThanOrEqual(1);
});

test('ordinary channel-screen navigation does not prompt for an unrelated tuner in Setup', async ({ page }) => {
  const state = await install(page);
  await page.goto('/app.html?view=channel-setup');
  await expect(page.getByRole('heading', { name: 'Channel Setup', exact: true })).toBeVisible();
  await navigate(page, 'dashboard');
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(departure(page)).toHaveCount(0);
  expect(stateWrites(state)).toEqual([]);
});

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Setup departure choices fit and Escape keeps reception stopped in ${theme} at ${width}px`, async ({ page }, testInfo) => {
    const state = await install(page, { theme });
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/app.html?view=tuners');
    await expect(mode(page)).toBeVisible();
    await navigate(page, 'dashboard');
    const prompt = departure(page);
    await expect(prompt).toBeVisible();
    for (const name of ['Stay here', 'Leave stopped', 'Go Live & leave'])
      await expect(prompt.getByRole('button', { name, exact: true })).toBeInViewport();
    expect(await prompt.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    await prompt.screenshot({ path: testInfo.outputPath(`tuner-departure-${theme}-${width}.png`), animations: 'disabled' });
    await page.keyboard.press('Escape');
    await expect(prompt).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Tuners', exact: true })).toBeVisible();
    expect(state.receiver.operator_state).toBe('setup');
    expect(stateWrites(state)).toEqual([]);
  });
}

for (const theme of ['light', 'dark']) {
  for (const [size, width] of [['desktop', 1280], ['phone', 390]]) {
    test(`shared tuner Setup confirmation gallery in ${theme} on ${size}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await page.goto(`/design-system.html?theme=${theme}&view=modal&confirmation=tuner-setup`);
      const prompt = departure(page);
      await expect(prompt).toBeVisible();
      await expect(prompt.locator('.ui-modal-footer').getByRole('button'))
        .toHaveText(['Stay here', 'Leave stopped', 'Go Live & leave']);
      await expect(prompt.getByRole('button', { name: 'Stay here', exact: true })).toBeFocused();
      await expect(page).toHaveScreenshot(`tuner-setup-confirmation-${theme}-${size}.png`);
    });
  }
}
