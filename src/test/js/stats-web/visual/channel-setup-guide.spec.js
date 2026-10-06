const { expect, test } = require('@playwright/test');
const { installSiteStyleApplication, operatorTuner } = require('./fixtures/site-style-app.cjs');

const eligibleTuner = { ...operatorTuner({ spectrum_target_id: 'target-a', spectrum_available: true }),
  eligible: true, source_type: 'receiver', channel_count: 0, center_frequency_hz: 851012500,
  usable_bandwidth_hz: 9000000, minimum_frequency_hz: 24000000, maximum_frequency_hz: 1800000000 };
const guide = page => page.getByRole('dialog', { name: 'Add your first channel', exact: true });
const toolbarAction = (page, name) => page.locator('.channel-admin-toolbar').getByRole('button', { name, exact: true });
const actionNames = ['Find Trunked Systems', 'New channel'];
const dismissalKey = 'sdrtrunk-vce-channel-setup-guide-dismissed:v2:fixture-admin:account-1-100';

async function expectWithinViewport(locator, viewport) {
  await expect.poll(async () => {
    const bounds = await locator.boundingBox();
    return bounds !== null && bounds.x >= 0 && bounds.y >= 0 &&
      bounds.x + bounds.width <= viewport.width && bounds.y + bounds.height <= viewport.height;
  }).toBe(true);
}

async function expectSpotlightsFollowToolbar(page) {
  const viewport = page.viewportSize();
  for (const name of actionNames) {
    const action = toolbarAction(page, name);
    const bounds = await action.boundingBox();
    const targetId = await action.getAttribute('id');
    expect(targetId).toBeTruthy();
    const highlight = page.locator(`.ui-setup-guide-highlight[data-setup-guide-target="${targetId}"]`);
    await expect(highlight).toHaveCount(1);
    const expected = { x: Math.max(0, bounds.x - 4), y: Math.max(0, bounds.y - 4),
      width: Math.min(viewport.width, bounds.x + bounds.width + 4) - Math.max(0, bounds.x - 4),
      height: Math.min(viewport.height, bounds.y + bounds.height + 4) - Math.max(0, bounds.y - 4) };
    await expect.poll(async () => highlight.evaluate((element, expectedBounds) =>
      ['x', 'y', 'width', 'height'].every(coordinate =>
        Math.abs(Number(element.getAttribute(coordinate)) - expectedBounds[coordinate]) <= 1), expected)).toBe(true);
    const panel = await guide(page).boundingBox();
    const overlap = panel.x < bounds.x + bounds.width && panel.x + panel.width > bounds.x &&
      panel.y < bounds.y + bounds.height && panel.y + panel.height > bounds.y;
    expect(overlap).toBe(false);
  }
}

async function captureToolbarActions(page) {
  return Promise.all(actionNames.map(async name => {
    const button = await toolbarAction(page, name).elementHandle();
    return { name, button, parent: await button.evaluateHandle(element => element.parentElement),
      bounds: await button.boundingBox(), describedBy: await button.getAttribute('aria-describedby') };
  }));
}

async function expectToolbarActionsUnmoved(page, actions) {
  for (const { name, button, parent, bounds } of actions) {
    await expect(toolbarAction(page, name)).toBeVisible();
    await expect(page.getByRole('button', { name, exact: true })).toHaveCount(1);
    expect(await button.evaluate((element, originalParent) => element.isConnected && element.parentElement === originalParent, parent)).toBe(true);
    const current = await button.boundingBox();
    for (const coordinate of ['x', 'y', 'width', 'height']) expect(current[coordinate]).toBeCloseTo(bounds[coordinate], 0);
  }
}

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
    for (const name of actionNames) {
      await expect(toolbarAction(page, name)).toBeVisible();
      await expectWithinViewport(toolbarAction(page, name), viewport);
    }
    await expectWithinViewport(guide(page), viewport);
    await expectSpotlightsFollowToolbar(page);
  });

  test(`setup guide follows the toolbar when resized ${theme}`, async ({ page }) => {
    const { fixture } = await openChannels(page, { theme });
    await expect(guide(page)).toBeVisible();
    for (const viewport of [{ width: 320, height: 740 }, { width: 960, height: 800 }]) {
      await page.setViewportSize(viewport);
      await expectWithinViewport(guide(page), viewport);
      for (const name of actionNames) await expectWithinViewport(toolbarAction(page, name), viewport);
      await expectSpotlightsFollowToolbar(page);
    }
    await toolbarAction(page, 'Find Trunked Systems').click();
    await expect(guide(page)).toHaveCount(0);
    await expect(page.getByRole('dialog', { name: 'Find Trunked Systems', exact: true })).toBeVisible();
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('setup guide highlights the original toolbar buttons without moving them', async ({ page }) => {
  const { fixture, state } = await openChannels(page, { holdCatalog: true });
  await expect.poll(() => state.catalogReads).toBeGreaterThan(0);
  const actions = await captureToolbarActions(page);
  state.releaseCatalog();
  await expect(guide(page)).toBeVisible();
  await expectToolbarActionsUnmoved(page, actions);
  await expectSpotlightsFollowToolbar(page);
  await guide(page).getByRole('button', { name: 'Dismiss setup guide', exact: true }).click();
  await expect(guide(page)).toHaveCount(0);
  await expectToolbarActionsUnmoved(page, actions);
  for (const { button, describedBy } of actions) expect(await button.getAttribute('aria-describedby')).toBe(describedBy);
  expect(fixture.pageErrors).toEqual([]);
});

test('setup guide keeps keyboard focus and remembers dismissal after reload', async ({ page }) => {
  const { fixture } = await openChannels(page);
  await expect(guide(page)).toBeVisible();
  await expect(guide(page)).toContainText('Search for trunked radio systems your tuner can receive.');
  await expect(guide(page)).toContainText('Set up a channel manually using a known frequency.');
  await expect(page.getByRole('button', { name: 'New channel', exact: true })).toHaveCount(1);
  const focusNames = new Set();
  for (let index = 0; index < 6; index++) {
    await page.keyboard.press('Tab');
    const name = await page.evaluate(() => document.activeElement.getAttribute('aria-label') || document.activeElement.textContent.trim());
    expect(['Dismiss setup guide', ...actionNames]).toContain(name);
    focusNames.add(name);
  }
  expect([...focusNames].sort()).toEqual(['Dismiss setup guide', ...actionNames].sort());
  await page.keyboard.press('Shift+Tab');
  expect(await page.evaluate(() => document.activeElement.getAttribute('aria-label') || document.activeElement.textContent.trim()))
    .toMatch(/^(Dismiss setup guide|Find Trunked Systems|New channel)$/);
  await page.keyboard.press('Escape');
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator('.channel-admin-toolbar').getByRole('button', { name: 'New channel', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('button', { name: 'New channel', exact: true })).toBeVisible();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await expect(guide(page)).toHaveCount(0);
  expect(fixture.pageErrors).toEqual([]);
});

test('a new install with the same username shows guidance despite an old install dismissal', async ({ page }) => {
  const { fixture } = await openChannels(page);
  await expect(guide(page)).toBeVisible();
  await guide(page).getByRole('button', { name: 'Dismiss setup guide', exact: true }).click();
  expect(await page.evaluate(key => localStorage.getItem(key), dismissalKey)).toBe('1');
  fixture.guideDismissalScope = 'account-1-200';
  await page.reload();
  await expect(guide(page)).toBeVisible();
  await expectSpotlightsFollowToolbar(page);
  await page.screenshot({ path: test.info().outputPath('first-channel-guide-new-install.png') });
  await page.keyboard.press('Escape');
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Channels', exact: true }).first()).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  expect(fixture.pageErrors).toEqual([]);
});

test('the old username-only dismissal does not hide first-install guidance', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('sdrtrunk-vce-channel-setup-guide-dismissed:fixture-admin', '1'));
  const { fixture } = await openChannels(page);
  await expect(guide(page)).toBeVisible();
  await expectSpotlightsFollowToolbar(page);
  expect(fixture.pageErrors).toEqual([]);
});

test('clicking the dimmed page dismisses the guide without activating its background', async ({ page }) => {
  const { fixture } = await openChannels(page);
  await expect(guide(page)).toBeVisible();
  await page.mouse.click(8, 80);
  await expect(guide(page)).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Channels', exact: true }).first()).toBeVisible();
  expect(await page.evaluate(key => localStorage.getItem(key), dismissalKey)).toBe('1');
  for (const name of actionNames) await expect(toolbarAction(page, name)).toBeVisible();
  expect(fixture.pageErrors).toEqual([]);
});

for (const [action, destination] of [['New channel', 'Create Channel'], ['Find Trunked Systems', 'Find Trunked Systems']]) {
  test(`guide opens the existing ${action} workflow and restores toolbar actions`, async ({ page }) => {
    const { fixture } = await openChannels(page);
    await expect(guide(page)).toBeVisible();
    await toolbarAction(page, action).click();
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
  expect(await page.evaluate(key => localStorage.getItem(key), dismissalKey)).toBeNull();
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
  expect(await page.evaluate(key => localStorage.getItem(key), dismissalKey)).toBeNull();
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
  expect(await page.evaluate(key => localStorage.getItem(key), dismissalKey)).toBeNull();
  expect(fixture.pageErrors).toEqual([]);
});
