const { expect, test } = require('@playwright/test');
const { installSiteStyleApplication, operatorTuner } = require('./fixtures/site-style-app.cjs');

const eligibleTuner = { ...operatorTuner({ spectrum_target_id: 'target-a', spectrum_available: true }),
  eligible: true, source_type: 'receiver', channel_count: 0, center_frequency_hz: 851012500,
  usable_bandwidth_hz: 9000000, minimum_frequency_hz: 24000000, maximum_frequency_hz: 1800000000 };
const guide = page => page.getByRole('dialog', { name: 'Add your first channel', exact: true });

async function openChannels(page, options = {}) {
  const fixture = await installSiteStyleApplication(page, options.theme || 'light');
  const state = { catalogReads: 0, revision: 1, channels: options.channels || [], tuners: options.tuners ?? [eligibleTuner],
    catalogFailure: options.catalogFailure, pendingCatalog: null, releaseCatalog: null };
  if (options.holdCatalog) state.pendingCatalog = new Promise(resolve => { state.releaseCatalog = resolve; });
  await page.route('**/api/v1/admin/channels', route => route.fulfill({ json: { data: {
    revision: state.revision, channels: state.channels } } }));
  await page.route('**/api/v1/channel-catalog', route => route.fulfill({ json: { data: {
    revision: state.revision, channels: state.channels } } }));
  await page.route('**/api/v1/admin/spectrum-search/catalog', async route => {
    state.catalogReads += 1;
    if (state.pendingCatalog) await state.pendingCatalog;
    if (state.catalogFailure) return route.fulfill({ status: 503, json: { error: { message: 'Catalog unavailable' } } });
    return route.fulfill({ json: { data: { tuners: state.tuners, suggested_tuner_id: 'tuner-a',
      presets: [{ id: '800mhz', label: '800 MHz · 851–869 MHz', ranges: [{ minimum_hz: 851000000, maximum_hz: 869000000 }] }],
      bounds: { maximum_ranges: 8, maximum_windows: 64, maximum_candidates: 32, maximum_total_hz: 150000000,
        minimum_dwell_ms: 750, maximum_dwell_ms: 5000, default_dwell_ms: 1500, maximum_scan_ms: 900000 } } } });
  });
  await page.route('**/api/v1/admin/tuners/tuner-a/browse', route => route.fulfill({ json: { data: {
    lease_id: 'fixture-guide-lease', expires_at_epoch_ms: Date.now() + 30000, can_tune: true, tuner: eligibleTuner } } }));
  await page.route('**/api/v1/admin/channels/protocols/*/template', route => route.fulfill({ json: { data: {
    protocol_id: new URL(route.request().url()).pathname.split('/').at(-2), name: '',
    source: { frequencies_hz: [], preferred_tuner: null }, settings: {}, event_logs: [], recorders: [], auxiliary_decoders: [] } } }));
  if (options.noTunerAccess) await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
    configured: true, authenticated: true, username: 'fixture-admin', tier: 'admin', primary: true,
    csrf_token: 'fixture-csrf', capabilities: { 'admin-channels': true, 'admin-tuners': false, 'user-settings': true } } } }));
  await page.goto('/app.html?view=channel-setup');
  await expect(page.getByRole('heading', { name: 'Channels', exact: true }).first()).toBeVisible();
  return { fixture, state };
}

for (const theme of ['light', 'dark']) {
    test(`first channel guide fits a 320px phone ${theme}`, async ({ page }) => {
      const viewport = { width: 320, height: 740 };
      await page.setViewportSize(viewport);
      await page.goto(`/design-system.html?view=setup-guide&theme=${theme}`);
      await expect(guide(page)).toBeVisible();
      await expect(guide(page).getByRole('button', { name: 'Dismiss setup guide', exact: true })).toBeVisible();
      await expect(guide(page).getByRole('button', { name: 'Find Trunked Systems', exact: true })).toBeVisible();
      await expect(guide(page).getByRole('button', { name: 'New channel', exact: true })).toBeVisible();
      const geometry = await guide(page).boundingBox();
      expect(geometry.x).toBeGreaterThanOrEqual(0);
      expect(geometry.x + geometry.width).toBeLessThanOrEqual(viewport.width);
      expect(geometry.y).toBeGreaterThanOrEqual(0);
      expect(geometry.y + geometry.height).toBeLessThanOrEqual(viewport.height);
    });
}

test('setup guide keeps keyboard focus and remembers dismissal after reload', async ({ page }) => {
  const { fixture } = await openChannels(page);
  await expect(guide(page)).toBeVisible();
  await expect(guide(page)).toContainText('Search for trunked radio systems your tuner can receive.');
  await expect(guide(page)).toContainText('Set up a channel manually using a known frequency.');
  await expect(page.getByRole('button', { name: 'New channel', exact: true })).toHaveCount(1);
  for (let index = 0; index < 5; index++) {
    await page.keyboard.press('Tab');
    expect(await guide(page).evaluate(element => element.contains(document.activeElement))).toBe(true);
  }
  await page.keyboard.press('Escape');
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator('.channel-admin-toolbar').getByRole('button', { name: 'New channel', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('button', { name: 'New channel', exact: true })).toBeVisible();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await expect(guide(page)).toHaveCount(0);
  expect(fixture.pageErrors).toEqual([]);
});

for (const [action, destination] of [['New channel', 'Create Channel'], ['Find Trunked Systems', 'Find Trunked Systems']]) {
  test(`guide opens the existing ${action} workflow and restores toolbar actions`, async ({ page }) => {
    const { fixture } = await openChannels(page);
    await expect(guide(page)).toBeVisible();
    await guide(page).getByRole('button', { name: action, exact: true }).click();
    await expect(guide(page)).toHaveCount(0);
    const workflow = page.getByRole('dialog', { name: destination, exact: true });
    await expect(workflow).toBeVisible();
    if (action === 'New channel') await expect(workflow.getByRole('button', { name: 'Create channel', exact: true })).toBeVisible();
    else await expect(workflow.getByRole('heading', { name: 'Choose where to look', exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(workflow).toHaveCount(0);
    await expect(page.locator('.channel-admin-toolbar').getByRole('button', { name: action, exact: true })).toBeVisible();
    expect(fixture.pageErrors).toEqual([]);
  });
}

for (const [name, options] of [
  ['no tuners', { tuners: [] }],
  ['ineligible tuner', { tuners: [{ ...eligibleTuner, eligible: false }] }],
  ['recording source', { tuners: [{ ...eligibleTuner, source_type: 'recording' }] }],
  ['unavailable catalog', { catalogFailure: true }],
  ['no tuner access', { noTunerAccess: true }]
]) {
  test(`guide is absent with ${name}`, async ({ page }) => {
    const { fixture, state } = await openChannels(page, options);
    await expect(page.getByRole('button', { name: 'New channel', exact: true })).toBeVisible();
    if (!options.noTunerAccess) await expect.poll(() => state.catalogReads).toBeGreaterThan(0);
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await expect(guide(page)).toHaveCount(0);
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('filters hiding configured channels do not trigger first channel guidance', async ({ page }) => {
  const { fixture } = await openChannels(page, { channels: [{ configuration_id: 'existing-channel', name: 'Existing channel',
    protocol: 'P25', protocol_id: 'p25-phase1', protocol_label: 'P25 Phase 1', channel_kind: 'TRUNKED',
    frequencies_hz: [851012500], processing_state: 'STOPPED', editable: true }] });
  await expect(page.locator('.channel-catalog-table tr[data-id="existing-channel"]')).toBeVisible();
  await page.getByRole('searchbox', { name: 'Search channels, systems, protocols, frequencies, or alias lists', exact: true }).fill('No match');
  await expect(page.getByText('No channels match this view', { exact: true })).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  expect(fixture.pageErrors).toEqual([]);
});

test('tuner readiness changes automatically remove and restore guidance without dismissing it', async ({ page }) => {
  await page.clock.install();
  const { fixture, state } = await openChannels(page);
  await expect(guide(page)).toBeVisible();
  state.tuners = [];
  await page.clock.fastForward(5100);
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator('.channel-admin-toolbar').getByRole('button', { name: 'New channel', exact: true })).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem('sdrtrunk-vce-channel-setup-guide-dismissed:fixture-admin'))).toBeNull();
  state.tuners = [eligibleTuner];
  await page.clock.fastForward(5100);
  await expect(guide(page)).toBeVisible();
  state.catalogFailure = true;
  await page.clock.fastForward(5100);
  await expect(guide(page)).toHaveCount(0);
  state.catalogFailure = false;
  await page.clock.fastForward(5100);
  await expect(guide(page)).toBeVisible();
  expect(fixture.pageErrors).toEqual([]);
});

test('adding a channel removes the guide automatically', async ({ page }) => {
  await page.clock.install();
  const { fixture, state } = await openChannels(page);
  await expect(guide(page)).toBeVisible();
  state.channels = [{ configuration_id: 'new-channel', name: 'Newly configured channel', protocol: 'P25',
    protocol_id: 'p25-phase1', protocol_label: 'P25 Phase 1', channel_kind: 'TRUNKED',
    frequencies_hz: [851012500], processing_state: 'STOPPED', editable: true }];
  state.revision += 1;
  await page.clock.fastForward(5100);
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator('.channel-catalog-table tr[data-id="new-channel"]')).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem('sdrtrunk-vce-channel-setup-guide-dismissed:fixture-admin'))).toBeNull();
  expect(fixture.pageErrors).toEqual([]);
});

test('a pending tuner readiness lookup cannot open guidance after navigation', async ({ page }) => {
  const { fixture, state } = await openChannels(page, { holdCatalog: true });
  await expect.poll(() => state.catalogReads).toBeGreaterThan(0);
  await page.evaluate(() => {
    history.pushState({}, '', '/app.html?view=dashboard');
    dispatchEvent(new PopStateEvent('popstate'));
  });
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  state.releaseCatalog();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await expect(guide(page)).toHaveCount(0);
  expect(await page.evaluate(() => localStorage.getItem('sdrtrunk-vce-channel-setup-guide-dismissed:fixture-admin'))).toBeNull();
  expect(fixture.pageErrors).toEqual([]);
});
