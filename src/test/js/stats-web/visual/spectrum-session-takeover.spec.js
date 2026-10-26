const { expect, test } = require('@playwright/test');
const { tourHistoryFixture } = require('./fixtures/tour-history.cjs');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const root = resolve(__dirname, '../../../../..');
const browsePath = '/api/v1/admin/tuners/receiver-a/browse';
const conflictMessage = 'In use by another Spectrum session';
const ownershipLostMessage = 'This Spectrum session was taken over.';
const discoveryConflictMessage = 'Stop signal identification before taking over this Spectrum session';

function tuner(setup = false) {
  return {
    id: 'receiver-a', name: 'Dispatch receiver', tuner_class: 'AIRSPY', status: 'ENABLED', available: true,
    operator_state: setup ? 'setup' : 'live', channel_count: setup ? 0 : 2,
    frequency_hz: 773081250, spectrum_target_id: 'target-receiver-a',
    sample_rate_hz: 10000000, usable_bandwidth_hz: 9000000,
    minimum_frequency_hz: 24000000, maximum_frequency_hz: 1800000000,
    settings: [
      { id: 'frequency_mhz', label: 'Center frequency', value: 773.08125, editable: true,
        availability: 'setup', minimum: 24, maximum: 1800,
        dependencies: [{ setting_id: 'center_frequency_locked', equals: false }] },
      { id: 'center_frequency_locked', label: 'Lock center', kind: 'boolean', value: !setup,
        editable: true, availability: 'live', dependencies: [] }
    ]
  };
}

function sessionState(options = {}) {
  const state = {
    requests: [], sequence: 0, owner: null, leaseId: null, sessionRevision: null,
    takeover: false, stoppedChannels: [], prepared: false,
    claimAllowed: options.claimAllowed !== false,
    delayClaim: false, releaseClaim: null
  };
  state.acquire = (owner) => {
    state.owner = owner;
    state.leaseId = `lease-${++state.sequence}`;
    state.sessionRevision = `revision-${state.sequence}`;
  };
  if (options.existing) state.acquire('existing-browser');
  return state;
}

async function install(page, state, owner, options = {}) {
  const tourHistory = tourHistoryFixture({ welcome: 1, tuners: 3, 'tuner-spectrum': 5 });
  const preferenceModule = await import(pathToFileURL(resolve(root,
    'stats-web/assets/core/preference-schema.js')).href);
  const snapshot = () => ({
    lease_id: state.leaseId, expires_at_epoch_ms: Date.now() + 30000,
    takeover: state.takeover, can_tune: state.prepared,
    stopped_channels: state.stoppedChannels, tuner: tuner(state.prepared)
  });

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    const body = JSON.parse(request.postData() || '{}');
    state.requests.push({ owner, path, method, body });
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json',
      body: JSON.stringify({ data }) });
    const fail = (code, message, details = undefined) => route.fulfill({ status: 409,
      contentType: 'application/json', body: JSON.stringify({ error: { code, message, details } }) });
    const conflict = () => fail('tuner_browse_conflict', state.claimAllowed ?
      'Another Spectrum session is using this tuner' : discoveryConflictMessage, {
      session_revision: state.sessionRevision, claim_allowed: state.claimAllowed
    });
    const lost = () => fail('tuner_browse_ownership_lost', ownershipLostMessage);

    if (path === '/api/v1/me/tour-dismissals') return tourHistory(route);
    if (path === '/api/v1/auth/session') return respond({ configured: true, authenticated: true,
      tier: 'admin', username: 'admin', csrf_token: 'test-token', capabilities: {
        'web-access': true, dashboard: true, 'tuner-spectrum': true, 'admin-tuners': true,
        'admin-channels': true, 'admin-aliases': true
      } });
    if (path === '/api/v1/me/preferences') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, preferences: { ...preferenceModule.defaults,
        appearance: { theme: options.theme || 'light', hue: null } } }) });
    if (path === '/api/v1/spectrum-snap-presets') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, country_code: 'US', country_label: 'United States',
        countries: [{ code: 'US', label: 'United States' }], scopes: [] }) });
    if (path === '/api/v1/admin/tuners') return respond({ tuners: [tuner(state.prepared)] });
    if (path === '/api/v1/diagnostics/tuners') return respond({ rows: [] });
    if (path === '/api/v1/dashboard/main') return respond({ quality: { current: [] },
      directory: { systems: [], channels: [] } });
    if (path === browsePath) {
      if (method === 'DELETE') {
        if (state.owner !== owner || body.lease_id !== state.leaseId) return lost();
        state.owner = null;
        state.leaseId = null;
        state.sessionRevision = null;
        state.takeover = false;
        state.prepared = false;
        state.stoppedChannels = [];
        return respond({ released: true });
      }
      if (body.claim_session) {
        if (!state.claimAllowed || body.claim_session !== state.sessionRevision) return conflict();
        if (state.delayClaim) {
          state.delayClaim = false;
          await new Promise((release) => { state.releaseClaim = release; });
          state.releaseClaim = null;
        }
        state.acquire(owner);
        return respond(snapshot());
      }
      if (body.lease_id) {
        if (state.owner !== owner || body.lease_id !== state.leaseId) return lost();
        return respond(snapshot());
      }
      if (state.owner && state.owner !== owner) return conflict();
      if (body.takeover === true) {
        state.takeover = true;
        state.prepared = true;
        state.stoppedChannels = [{ configuration_id: 'channel-a', name: 'County control' }];
      }
      state.acquire(owner);
      return respond(snapshot());
    }
    if (path.endsWith('/settings/frequency_mhz') && method === 'PUT') {
      if (state.owner !== owner || body.lease_id !== state.leaseId) return lost();
      return respond({ saved: true });
    }
    return respond({});
  });

  await page.goto('/app.html?view=tuner-spectrum');
  await expect(page.getByRole('heading', { name: 'Browse Spectrum', exact: true })).toBeVisible();
  await expect.poll(() => state.requests.some((request) => request.owner === owner &&
    request.path === browsePath && request.method === 'POST' && !Object.keys(request.body).length)).toBe(true);
}

function browseRequests(state, owner, method = 'POST') {
  return state.requests.filter((request) => request.owner === owner &&
    request.path === browsePath && request.method === method);
}

async function prepareFirstSession(page, state) {
  await install(page, state, 'first-browser');
  await page.getByRole('button', { name: 'Setup', exact: true }).click();
  const warning = page.getByRole('alertdialog', { name: 'Stop channels to tune?' });
  await warning.getByRole('button', { name: 'Stop channels and tune', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Setup', exact: true })).toHaveAttribute('aria-pressed', 'true');
  expect(state.takeover).toBe(true);
}

test('a second browser explicitly inherits Setup and the displaced browser stops renewing or releasing it', async ({ page, context }) => {
  await page.clock.install();
  const state = sessionState();
  await prepareFirstSession(page, state);
  const firstLease = state.leaseId;
  const revision = state.sessionRevision;
  const second = await context.newPage();
  await install(second, state, 'second-browser');
  await expect(second.getByText(conflictMessage, { exact: true })).toBeVisible();
  const takeover = second.getByRole('button', { name: 'Take over', exact: true });
  await expect(takeover).toBeVisible();
  expect(browseRequests(state, 'second-browser').filter((request) => request.body.claim_session)).toHaveLength(0);

  await takeover.click();
  await expect(second.getByRole('button', { name: 'Setup', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(second.getByText('1 channel stopped · Resume when finished', { exact: true })).toBeVisible();
  await expect(second.getByRole('group', { name: 'Center frequency', exact: true })).toHaveAttribute('aria-disabled', 'false');
  expect(browseRequests(state, 'second-browser').filter((request) => request.body.claim_session)
    .map((request) => request.body)).toEqual([{ claim_session: revision }]);
  expect(state.leaseId).not.toBe(firstLease);
  expect(state.stoppedChannels).toHaveLength(1);
  expect(tuner(state.prepared).frequency_hz).toBe(773081250);

  await page.clock.fastForward(10_100);
  await expect(page.locator('.spectrum-browse-message')).toHaveText(ownershipLostMessage);
  const displacedPosts = browseRequests(state, 'first-browser').length;
  const displacedDeletes = browseRequests(state, 'first-browser', 'DELETE').length;
  await page.clock.fastForward(30_100);
  expect(browseRequests(state, 'first-browser')).toHaveLength(displacedPosts);
  await page.evaluate(() => document.querySelector('a[data-view="dashboard"]').click());
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  expect(browseRequests(state, 'first-browser', 'DELETE')).toHaveLength(displacedDeletes);
  expect(state.owner).toBe('second-browser');
  expect(state.takeover).toBe(true);
  await page.close();
  expect(browseRequests(state, 'first-browser', 'DELETE')).toHaveLength(displacedDeletes);
  expect(state.owner).toBe('second-browser');
});

test('a stale center-frequency edit immediately clears the displaced session', async ({ page, context }) => {
  await page.clock.install();
  const state = sessionState();
  await prepareFirstSession(page, state);
  const second = await context.newPage();
  await install(second, state, 'second-browser');
  await second.getByRole('button', { name: 'Take over', exact: true }).click();
  await expect(second.getByRole('button', { name: 'Setup', exact: true })).toHaveAttribute('aria-pressed', 'true');

  const center = page.getByRole('group', { name: 'Center frequency', exact: true });
  await center.focus();
  for (let place = 0; place < 8; place++) await page.keyboard.press('ArrowRight');
  await page.keyboard.press('ArrowUp');
  await expect(page.locator('.spectrum-browse-message')).toHaveText(ownershipLostMessage);
  const displacedPosts = browseRequests(state, 'first-browser').length;
  await page.clock.fastForward(30_100);
  expect(browseRequests(state, 'first-browser')).toHaveLength(displacedPosts);
  expect(state.owner).toBe('second-browser');
  expect(state.requests.filter((request) => request.owner === 'first-browser' &&
    request.path.endsWith('/settings/frequency_mhz') && request.method === 'PUT')).toHaveLength(1);
});

test('a pending session claim locks controls and a late success is released after leaving Spectrum', async ({ page }) => {
  const state = sessionState({ existing: true });
  state.delayClaim = true;
  await install(page, state, 'second-browser');
  const takeover = page.getByRole('button', { name: 'Take over', exact: true });
  await expect(takeover).toBeVisible();
  await takeover.evaluate((button) => { button.click(); button.click(); });
  await expect.poll(() => typeof state.releaseClaim).toBe('function');
  await expect(page.getByRole('combobox', { name: 'Tuner', exact: true })).toBeDisabled();
  expect(browseRequests(state, 'second-browser').filter((request) => request.body.claim_session)).toHaveLength(1);

  await page.evaluate(() => document.querySelector('a[data-view="dashboard"]').click());
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  state.releaseClaim();
  await expect.poll(() => browseRequests(state, 'second-browser', 'DELETE').length).toBe(1);
  await expect.poll(() => state.owner).toBe(null);
  await expect(page.locator('.spectrum-browse-toolbar')).toHaveCount(0);
});

test('a stale takeover revision requires another explicit attempt', async ({ page }) => {
  const state = sessionState({ existing: true });
  await install(page, state, 'second-browser');
  const takeover = page.getByRole('button', { name: 'Take over', exact: true });
  await expect(takeover).toBeVisible();
  const originalRevision = state.sessionRevision;
  state.acquire('third-browser');

  await takeover.click();
  await expect(takeover).toBeEnabled();
  expect(state.owner).toBe('third-browser');
  expect(browseRequests(state, 'second-browser').filter((request) => request.body.claim_session)
    .map((request) => request.body.claim_session)).toEqual([originalRevision]);

  await takeover.click();
  await expect.poll(() => state.owner).toBe('second-browser');
  expect(browseRequests(state, 'second-browser').filter((request) => request.body.claim_session)).toHaveLength(2);
});

test('active discovery blocks session takeover until a deliberate retry finds it available', async ({ page }) => {
  const state = sessionState({ existing: true, claimAllowed: false });
  await install(page, state, 'second-browser');
  await expect(page.locator('.spectrum-browse-message')).toHaveText(discoveryConflictMessage);
  await expect(page.getByRole('button', { name: 'Take over', exact: true })).toBeHidden();
  const retry = page.getByRole('button', { name: 'Retry browsing', exact: true });
  await expect(retry).toBeVisible();
  state.claimAllowed = true;
  await retry.click();
  await expect(page.getByRole('button', { name: 'Take over', exact: true })).toBeVisible();
  expect(browseRequests(state, 'second-browser').filter((request) => request.body.claim_session)).toHaveLength(0);
});

for (const theme of ['light', 'dark']) {
  for (const width of [1722, 1280, 390, 320]) {
    test(`Spectrum session conflict fits ${theme} at ${width}px`, async ({ page }, testInfo) => {
      await page.setViewportSize({ width, height: 900 });
      const state = sessionState({ existing: true });
      await install(page, state, 'second-browser', { theme });
      const toolbar = page.locator('.spectrum-browse-toolbar');
      const message = toolbar.getByText(conflictMessage, { exact: true });
      const takeover = toolbar.getByRole('button', { name: 'Take over', exact: true });
      await expect(message).toBeVisible();
      await expect(takeover).toBeVisible();
      await expect(toolbar.locator('.spectrum-browse-state .badge')).toHaveText('In use');
      await expect(toolbar.locator('.tuners-center-frequency')).toHaveCount(0);
      expect(await toolbar.locator('.spectrum-browse-center').evaluate((element) =>
        element.hidden || getComputedStyle(element).display === 'none' || Boolean(element.textContent.trim())))
        .toBe(true);
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <=
        document.documentElement.clientWidth)).toBe(true);
      const bounds = await toolbar.boundingBox();
      for (const control of await toolbar.locator('button, select').all()) {
        if (!await control.isVisible()) continue;
        const controlBounds = await control.boundingBox();
        expect(controlBounds.x).toBeGreaterThanOrEqual(bounds.x - 1);
        expect(controlBounds.x + controlBounds.width).toBeLessThanOrEqual(bounds.x + bounds.width + 1);
      }
      if (width >= 1280) {
        const row = await Promise.all([
          toolbar.getByRole('combobox', { name: 'Tuner', exact: true }).boundingBox(),
          message.boundingBox(), takeover.boundingBox(),
          toolbar.locator('.spectrum-browse-command-cluster').boundingBox()
        ]);
        const centers = row.map((box) => box.y + box.height / 2);
        expect(Math.max(...centers) - Math.min(...centers)).toBeLessThanOrEqual(8);
        expect(bounds.height).toBeLessThan(72);
      }
      if (width === 1280 || width === 390) {
        for (const hue of [null, 285]) {
          await page.evaluate(async (value) => {
            const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
            applyThemeHue(value);
          }, hue);
          await expectContrast(page, { rootSelector: '.spectrum-browse-toolbar', recordOnly: false,
            reportName: `spectrum-session-conflict-${theme}-${width}-${hue}` });
        }
        await page.evaluate(async () => {
          const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
          applyThemeHue(null);
        });
      }
      await page.evaluate(() => document.fonts.ready);
      const screenshot = testInfo.outputPath(`session-conflict-${theme}-${width}.png`);
      await toolbar.screenshot({ path: screenshot });
      await testInfo.attach(`session-conflict-${theme}-${width}`, {
        path: screenshot, contentType: 'image/png'
      });
    });
  }
}

for (const theme of ['light', 'dark']) {
  for (const [device, viewport] of [['desktop', { width: 1280, height: 900 }],
    ['mobile', { width: 390, height: 844 }]]) {
    test(`Spectrum session conflict gallery ${theme} ${device}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await page.goto(`/design-system.html?view=tuner-spectrum&state=session-conflict&theme=${theme}`);
      const toolbar = page.locator('.visual-tuner-spectrum-example .spectrum-browse-toolbar');
      await expect(toolbar.getByText(conflictMessage, { exact: true })).toBeVisible();
      await expect(toolbar.getByRole('button', { name: 'Take over', exact: true })).toBeVisible();
      await expect(toolbar.getByRole('group', { name: 'Tuner mode', exact: true })).toBeHidden();
      await expect(toolbar.locator('.spectrum-browse-state .badge')).toHaveText('In use');
      await expect(toolbar.locator('.spectrum-browse-center')).toBeHidden();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <=
        document.documentElement.clientWidth)).toBe(true);
      await expect(toolbar).toHaveScreenshot(`spectrum-session-conflict-${theme}-${device}.png`);
    });
  }
}
