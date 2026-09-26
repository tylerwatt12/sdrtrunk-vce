const { expect, test } = require('@playwright/test');

const fixtureUrl = (populated = false) =>
  `/visual/network-visualizer.html?theme=dark${populated ? '&network_fixture=1' : ''}`;

async function waitForController(page) {
  await page.waitForFunction(() => Boolean(window.networkVisualizerTest?.controller));
}

async function waitForRenderer(page) {
  await expect.poll(async () => page.evaluate(() =>
    window.networkVisualizerTest.diagnostics()?.renderer !== null)).toBe(true);
}

async function diagnostics(page) {
  return page.evaluate(() => window.networkVisualizerTest.diagnostics());
}

async function waitForFixtureDrain(page) {
  await expect.poll(async () => (await diagnostics(page)).state.queuedObservations,
    { timeout: 15_000 }).toBe(0);
}

async function clickRenderedNode(page, labelText, expectedLevel) {
  const label = page.locator('.network-visualizer-label[data-visible="true"]')
    .filter({ hasText: labelText }).first();
  await expect(label).toBeVisible();
  await label.click();
  await expect.poll(async () => (await diagnostics(page)).navigation.scope.level,
    { timeout: 5_000 }).toBe(expectedLevel);
}

async function drillToGroup(page, systemLabel, groupLabel) {
  await clickRenderedNode(page, systemLabel, 'system');
  await expect.poll(async () => (await diagnostics(page)).graph.counts.visibleGroups).toBeGreaterThan(0);
  await clickRenderedNode(page, groupLabel, 'group');
}

function pointDistance(left, right) {
  return Math.hypot(left.x - right.x, left.y - right.y, left.z - right.z);
}

async function toolbarReachability(page) {
  return page.evaluate(() => ({
    clientWidth: document.body.clientWidth,
    scrollWidth: document.body.scrollWidth,
    buttons: [...document.querySelectorAll('.network-visualizer-toolbar button')]
      .filter((element) => {
        const bounds = element.getBoundingClientRect();
        return getComputedStyle(element).display !== 'none' && bounds.width > 0 && bounds.height > 0;
      })
      .map((element) => {
        const bounds = element.getBoundingClientRect();
        return {
          label: element.getAttribute('aria-label') || element.textContent.trim(),
          left: bounds.left,
          right: bounds.right,
          top: bounds.top,
          bottom: bounds.bottom,
          tabIndex: element.tabIndex
        };
      })
  }));
}

function expectToolbarReachable(value, viewport) {
  expect(value.scrollWidth).toBe(value.clientWidth);
  expect(value.buttons.length).toBeGreaterThan(0);
  value.buttons.forEach((button) => {
    expect(button.left, `${button.label} begins inside the viewport`).toBeGreaterThanOrEqual(0);
    expect(button.right, `${button.label} ends inside the viewport`).toBeLessThanOrEqual(viewport.width);
    expect(button.top, `${button.label} begins inside the viewport`).toBeGreaterThanOrEqual(0);
    expect(button.bottom, `${button.label} ends inside the viewport`).toBeLessThanOrEqual(viewport.height);
    expect(button.tabIndex, `${button.label} remains keyboard reachable`).toBeGreaterThanOrEqual(0);
  });
}

function recordMeasurement(testInfo, name, value, unit = 'ms') {
  const formatted = `${name}=${Number(value).toFixed(unit === 'ms' ? 1 : 0)}${unit}`;
  testInfo.annotations.push({
    type: 'measurement',
    description: formatted
  });
  console.info(`[network-visualizer] ${formatted}`);
}

test('fresh view is empty, opens only shared live topics, and cleans up on repeated entry', async ({ page }, testInfo) => {
  const apiRequests = [];
  page.on('request', (request) => {
    if (new URL(request.url()).pathname.startsWith('/api/')) apiRequests.push(request.url());
  });

  await page.goto(fixtureUrl(false));
  await waitForController(page);
  await waitForRenderer(page);

  const ready = await page.evaluate(() => ({
    elapsedMs: performance.now() - window.networkVisualizerTest.metrics.bootStartedAt,
    diagnostic: window.networkVisualizerTest.diagnostics(),
    metrics: { ...window.networkVisualizerTest.metrics }
  }));
  recordMeasurement(testInfo, 'empty_ready', ready.elapsedMs);

  await expect(page.getByText('Listening — the map builds as activity arrives.')).toBeVisible();
  await expect(page.locator('.network-visualizer-counts')).toHaveCount(0);
  await expect(page.locator('.network-visualizer-offscreen')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Arrange layout', exact: true })).toHaveCount(0);
  const toolbar = page.getByRole('toolbar', { name: 'Network Visualizer controls' });
  await expect(toolbar.getByRole('button', { name: 'Auto', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(toolbar.getByRole('button', { name: 'Manual', exact: true })).toHaveAttribute('aria-pressed', 'false');
  for (const label of ['Affiliations', 'Activity', 'Quiet', 'Fit all', 'Focus', 'Auto rotate', 'Freeze layout']) {
    await expect(toolbar.getByRole('button', { name: label, exact: true })).toHaveCount(0);
  }
  await expect(page.getByRole('searchbox', { name: 'Search retained network entities' })).toHaveCount(0);
  expect(ready.diagnostic.state).toMatchObject({ universes: 0, groups: 0, radios: 0,
    activeCalls: 0, semanticEvents: 0, queuedObservations: 0 });
  expect(ready.diagnostic.graph).toMatchObject({ nodes: 0, links: 0 });
  expect(ready.metrics).toMatchObject({ networkCreated: 1, networkActive: 1,
    channelCreated: 1, channelActive: 1 });
  expect(ready.metrics.topics.map(({ topic }) => topic)).toEqual(['network_activity']);
  expect(apiRequests).toEqual([]);
  expectToolbarReachable(await toolbarReachability(page), page.viewportSize());

  for (let index = 0; index < 3; index += 1) {
    await page.evaluate(() => window.networkVisualizerTest.remount());
    await waitForRenderer(page);
    await expect(page.locator('.network-visualizer-layout')).toHaveCount(1);
    await expect(page.locator('.network-visualizer-canvas canvas')).toHaveCount(1);
    const cycle = await page.evaluate(() => ({
      metrics: { ...window.networkVisualizerTest.metrics },
      closed: window.networkVisualizerTest.metrics.lastClosedDiagnostics
    }));
    expect(cycle.metrics.networkActive).toBe(1);
    expect(cycle.metrics.channelActive).toBe(1);
    expect(cycle.closed.subscriptions).toEqual({ channel: false, network: false });
    expect(cycle.closed.timers).toEqual({ ingestion: false, animation: false });
  }

  const cleanupStarted = Date.now();
  const closed = await page.evaluate(() => window.networkVisualizerTest.close());
  recordMeasurement(testInfo, 'cleanup', Date.now() - cleanupStarted);
  expect(closed.subscriptions).toEqual({ channel: false, network: false });
  expect(closed.timers).toEqual({ ingestion: false, animation: false });
  expect(await page.evaluate(() => ({
    network: window.networkVisualizerTest.metrics.networkActive,
    channel: window.networkVisualizerTest.metrics.channelActive
  }))).toEqual({ network: 0, channel: 0 });
  await expect(page.locator('.network-visualizer-layout')).toHaveCount(0);
  expect(apiRequests).toEqual([]);
});

test('populated view drills from a system through its talkgroup to a radio', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(fixtureUrl(true));
  await waitForController(page);
  await waitForRenderer(page);
  await waitForFixtureDrain(page);

  let snapshot = await diagnostics(page);
  expect(snapshot.navigation).toMatchObject({
    scope: { level: 'overview', universeKey: '', groupKey: '' },
    depth: 0,
    cameraMode: 'auto'
  });
  expect(snapshot.graph.nodes).toBe(snapshot.graph.counts.visibleUniverses);
  expect(snapshot.graph.nodes).toBeLessThanOrEqual(64);
  expect(snapshot.graph.counts).toMatchObject({ visibleGroups: 0, visibleRadios: 0 });
  expect(snapshot.state.radios).toBeGreaterThan(1_000);
  await expect(page.getByRole('navigation', { name: 'Network view hierarchy' }))
    .toContainText('Observed radio systems');
  await expect(page.getByRole('button', { name: 'Auto', exact: true }))
    .toHaveAttribute('aria-pressed', 'true');
  expect(snapshot.renderer.autoRotateRequested).toBe(true);

  await clickRenderedNode(page, 'Alpha Regional', 'system');
  await expect.poll(async () => (await diagnostics(page)).graph.counts.visibleGroups).toBeGreaterThan(0);
  snapshot = await diagnostics(page);
  expect(snapshot.navigation.scope).toMatchObject({
    level: 'system',
    universeKey: 'system:p25:alpha-proven-system',
    groupKey: ''
  });
  expect(snapshot.graph.counts).toMatchObject({ visibleUniverses: 1, visibleGroups: 4 });
  expect(snapshot.graph.counts.visibleRadios).toBeGreaterThan(0);
  await expect(page.getByRole('navigation', { name: 'Network view hierarchy' }))
    .toContainText('Alpha Regional');
  await expect(page.getByRole('complementary', { name: 'Selected network entity' }))
    .toContainText('Alpha Regional');
  await expect(page.locator('.network-visualizer-label[data-visible="true"]')
    .filter({ hasText: 'Overflow Operations' })).toBeVisible();
  await expect(page.locator('.network-visualizer-label[data-visible="true"]')
    .filter({ hasText: 'Mutual Aid' })).toBeVisible();

  await page.waitForTimeout(800);
  await clickRenderedNode(page, 'North Tac', 'group');
  await expect.poll(async () => (await diagnostics(page)).graph.counts.visibleRadios).toBeGreaterThan(0);
  snapshot = await diagnostics(page);
  expect(snapshot.navigation.scope).toMatchObject({
    level: 'group',
    universeKey: 'system:p25:alpha-proven-system',
    groupKey: 'system:p25:alpha-proven-system|group:tg:202'
  });
  expect(snapshot.graph.counts.visibleRadios).toBeGreaterThan(0);
  await expect(page.getByRole('complementary', { name: 'Selected network entity' }))
    .toContainText('North Tac');

  await clickRenderedNode(page, 'Unit 12', 'group');
  snapshot = await diagnostics(page);
  expect(snapshot.navigation.scope).toMatchObject({
    level: 'group',
    universeKey: 'system:p25:alpha-proven-system',
    groupKey: 'system:p25:alpha-proven-system|group:tg:202'
  });
  await expect(page.getByRole('heading', { name: 'Unit 12' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Back to Alpha Regional' })).toBeVisible();
  await page.getByRole('button', { name: 'Back to Alpha Regional' }).click();
  await expect.poll(async () => (await diagnostics(page)).navigation.scope.level).toBe('system');
  expect((await diagnostics(page)).navigation.scope.universeKey).toBe('system:p25:alpha-proven-system');
  await page.getByRole('button', { name: 'Back to all systems' }).click();
  await expect.poll(async () => (await diagnostics(page)).navigation.scope.level).toBe('overview');
});

test('cursor zoom, orbit, and Clear keep camera and hierarchy semantics', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(fixtureUrl(true));
  await waitForController(page);
  await waitForRenderer(page);
  await waitForFixtureDrain(page);

  await expect(page.getByRole('button', { name: 'Arrange layout', exact: true })).toHaveCount(0);

  const autoMode = page.getByRole('button', { name: 'Auto', exact: true });
  const manualMode = page.getByRole('button', { name: 'Manual', exact: true });
  await manualMode.click();
  await expect(manualMode).toHaveAttribute('aria-pressed', 'true');
  await expect(autoMode).toHaveAttribute('aria-pressed', 'false');
  await expect(page.getByRole('note', { name: 'Manual camera controls' })).toContainText('W A S D');
  await expect.poll(async () => (await diagnostics(page)).navigation.cameraMode).toBe('manual');
  await expect.poll(async () => (await diagnostics(page)).renderer.autoRotateEffective).toBe(false);
  await page.waitForTimeout(800);

  const canvas = page.locator('.network-visualizer-canvas canvas');
  const bounds = await canvas.boundingBox();
  expect(bounds).not.toBeNull();
  const beforeZoom = (await diagnostics(page)).renderer.camera;
  await page.mouse.move(bounds.x + bounds.width * 0.8, bounds.y + bounds.height * 0.3);
  await page.mouse.wheel(0, -350);
  await page.waitForTimeout(450);
  const afterZoom = (await diagnostics(page)).renderer.camera;
  expect(pointDistance(beforeZoom.position, beforeZoom.target)).toBeGreaterThan(
    pointDistance(afterZoom.position, afterZoom.target));
  expect(pointDistance(beforeZoom.target, afterZoom.target)).toBeGreaterThan(1);

  await page.mouse.move(bounds.x + bounds.width * 0.2, bounds.y + bounds.height * 0.72);
  await page.mouse.down();
  await page.mouse.move(bounds.x + bounds.width * 0.4, bounds.y + bounds.height * 0.72, { steps: 15 });
  await page.mouse.up();
  await page.waitForTimeout(350);
  const afterOrbit = (await diagnostics(page)).renderer.camera;
  expect(pointDistance(afterZoom.position, afterOrbit.position)).toBeGreaterThan(10);
  expect(pointDistance(afterZoom.target, afterOrbit.target)).toBeLessThan(0.5);

  await autoMode.click();
  await expect(autoMode).toHaveAttribute('aria-pressed', 'true');
  await expect(manualMode).toHaveAttribute('aria-pressed', 'false');
  await expect.poll(async () => (await diagnostics(page)).navigation.cameraMode).toBe('auto');
  await expect(page.getByRole('note', { name: 'Manual camera controls' })).toBeHidden();
  await expect(page.getByRole('button', { name: 'Flatten', exact: true })).toHaveCount(0);

  await page.getByRole('button', { name: 'Clear map', exact: true }).click();
  await expect.poll(async () => (await diagnostics(page)).navigation.scope.level).toBe('overview');
  const cleared = await diagnostics(page);
  expect(cleared.navigation.depth).toBe(0);
  expect(cleared.graph.nodes).toBe(0);
  await expect(page.getByRole('navigation', { name: 'Network view hierarchy' }))
    .toContainText('Observed radio systems');
});

test('narrow toolbar, inspector, event drawer, and fullscreen controls stay reachable', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(fixtureUrl(true));
  await waitForController(page);
  await waitForRenderer(page);
  await expect.poll(async () => (await diagnostics(page)).state.queuedObservations, { timeout: 15_000 }).toBe(0);

  expectToolbarReachable(await toolbarReachability(page), page.viewportSize());
  await expect(page.getByText('Development fixture active — this is not receiver activity.')).toBeVisible();
  const hierarchy = page.getByRole('navigation', { name: 'Network view hierarchy' });
  await expect(hierarchy).toBeVisible();
  await expect(hierarchy).toContainText('Overview');
  await expect(hierarchy).toContainText('Observed radio systems');
  await expect(hierarchy.getByRole('button', { name: 'Back to all systems' })).toBeHidden();
  await expect(page.getByRole('button', { name: 'Auto', exact: true }))
    .toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('button', { name: 'Arrange layout', exact: true })).toHaveCount(0);

  await drillToGroup(page, 'Alpha Regional', 'North Tac');
  await clickRenderedNode(page, 'Unit 12', 'group');
  const inspector = page.getByRole('complementary', { name: 'Selected network entity' });
  await expect(inspector).toBeVisible();
  await expect(inspector.getByRole('button', { name: 'Close inspector' })).toBeVisible();
  await expect(inspector).toContainText('RFSS · Site');
  await expect(inspector).toContainText('01 / 01');
  await expect(inspector).toContainText('Configuration');
  const inspectorCollapse = inspector.getByRole('button', { name: 'Collapse inspector' });
  await inspectorCollapse.click();
  await expect(inspector.getByRole('button', { name: 'Expand inspector' })).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('#network-visualizer-inspector-body')).toBeHidden();
  await inspector.getByRole('button', { name: 'Expand inspector' }).click();
  await expect(page.locator('#network-visualizer-inspector-body')).toBeVisible();
  const inspectorBounds = await inspector.boundingBox();
  expect(inspectorBounds.x).toBeGreaterThanOrEqual(0);
  expect(inspectorBounds.x + inspectorBounds.width).toBeLessThanOrEqual(390);
  const inspectorClose = inspector.getByRole('button', { name: 'Close inspector' });
  await inspectorClose.focus();
  await expect(inspectorClose).toBeFocused();
  await inspectorClose.click();
  await expect(inspector).toBeHidden();

  await page.setViewportSize({ width: 844, height: 390 });
  const landscape = await page.evaluate(() => {
    const stage = document.querySelector('.network-visualizer-stage')?.getBoundingClientRect();
    return { bodyScrollHeight: document.body.scrollHeight, viewportHeight: innerHeight,
      stage: stage ? { top: stage.top, bottom: stage.bottom, height: stage.height } : null };
  });
  expect(landscape.stage).not.toBeNull();
  expect(landscape.bodyScrollHeight).toBeLessThanOrEqual(landscape.viewportHeight + 1);
  expect(landscape.stage.bottom).toBeLessThanOrEqual(landscape.viewportHeight + 1);
  expect(landscape.stage.height).toBeGreaterThan(80);
  await page.setViewportSize({ width: 390, height: 844 });

  const eventsToggle = page.getByRole('button', { name: 'Events', exact: true });
  await eventsToggle.click();
  const events = page.getByRole('complementary', { name: 'Recent observed network events' });
  await expect(events).toBeVisible();
  const eventsClose = events.getByRole('button', { name: 'Close event drawer' });
  await expect(eventsClose).toBeVisible();
  await expect(eventsClose).toBeFocused();
  const eventBounds = await events.boundingBox();
  expect(eventBounds.x).toBeGreaterThanOrEqual(0);
  expect(eventBounds.x + eventBounds.width).toBeLessThanOrEqual(390);
  await eventsClose.click();
  await expect(eventsToggle).toBeFocused();

  await page.getByRole('button', { name: 'Enter fullscreen' }).click();
  await expect.poll(async () => page.evaluate(() =>
    document.fullscreenElement?.classList.contains('network-visualizer-layout'))).toBe(true);
  await expect(page.getByRole('button', { name: 'Exit fullscreen' })).toBeVisible();
  await page.getByRole('button', { name: 'Exit fullscreen' }).click();
  await expect(page.getByRole('button', { name: 'Enter fullscreen' })).toBeVisible();
  expectToolbarReachable(await toolbarReachability(page), page.viewportSize());
});

test('deterministic burst stays bounded and all canvas controls preserve one state model', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const startedAt = Date.now();
  await page.goto(fixtureUrl(true));
  await waitForController(page);
  await waitForRenderer(page);
  await waitForFixtureDrain(page);

  let snapshot = await diagnostics(page);
  expect(snapshot.navigation.scope.level).toBe('overview');
  expect(snapshot.graph.nodes).toBe(snapshot.graph.counts.visibleUniverses);
  expect(snapshot.graph.counts).toMatchObject({ visibleGroups: 0, visibleRadios: 0 });
  await drillToGroup(page, 'Alpha Regional', 'Overflow Operations');
  await expect.poll(async () => (await diagnostics(page)).graph.nodes, { timeout: 15_000 }).toBeGreaterThan(100);
  await expect.poll(async () => (await diagnostics(page)).renderer.labels, { timeout: 5_000 }).toBeGreaterThan(10);

  snapshot = await diagnostics(page);
  recordMeasurement(testInfo, 'fixture_burst_drain', Date.now() - startedAt);
  expect(snapshot.state.radios).toBeGreaterThan(1_000);
  expect(snapshot.state.radios).toBeLessThanOrEqual(20_000);
  expect(snapshot.state.groups).toBeLessThanOrEqual(5_000);
  expect(snapshot.state.universes).toBeLessThanOrEqual(64);
  expect(snapshot.graph.nodes).toBeLessThanOrEqual(1_000);
  expect(snapshot.graph.links).toBeLessThanOrEqual(900);
  expect(snapshot.graph.counts.visibleLabels).toBeLessThanOrEqual(80);
  expect(snapshot.graph.counts.retainedRadios).toBe(snapshot.state.radios);
  expect(snapshot.renderer.labels).toBeGreaterThan(10);
  expect(snapshot.renderer.labels).toBeLessThanOrEqual(80);
  expect(snapshot.renderer.steadyParticles + snapshot.renderer.pendingParticles).toBeLessThanOrEqual(150);
  await expect(page.locator('.network-visualizer-empty')).toBeHidden();
  await expect(page.locator('.network-visualizer-counts')).toHaveCount(0);
  await expect(page.locator('.network-visualizer-offscreen')).toHaveCount(0);

  const interactionStarted = Date.now();
  await expect(page.getByRole('button', { name: 'Flatten', exact: true })).toHaveCount(0);
  snapshot = await diagnostics(page);
  if (snapshot.renderer.available) expect(snapshot.renderer.mode).toBe('3d');

  await page.getByRole('button', { name: 'Events', exact: true }).click();
  await expect(page.getByRole('complementary', { name: 'Recent observed network events' })).toBeVisible();
  await expect(page.getByRole('complementary', { name: 'Recent observed network events' })
    .getByText('Successful affiliation observed', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('North Dispatch → North Tac', { exact: true })).toBeVisible();

  const fullscreen = page.getByRole('button', { name: 'Enter fullscreen' });
  await fullscreen.click();
  await expect.poll(async () => page.evaluate(() =>
    document.fullscreenElement?.classList.contains('network-visualizer-layout'))).toBe(true);
  await expect(page.getByRole('button', { name: 'Exit fullscreen' })).toBeVisible();
  await page.getByRole('button', { name: 'Exit fullscreen' }).click();
  await expect(page.getByRole('button', { name: 'Enter fullscreen' })).toBeVisible();
  recordMeasurement(testInfo, 'toolbar_interactions', Date.now() - interactionStarted);

  await expect(page.locator('.network-visualizer-layout')).toHaveScreenshot('network-visualizer-dark-desktop.png', {
    animations: 'disabled'
  });
  await page.getByRole('button', { name: 'Clear map', exact: true }).click();
  await expect.poll(async () => (await diagnostics(page)).state.radios).toBe(0);
  await expect.poll(async () => (await diagnostics(page)).graph.nodes).toBe(0);
  await expect(page.getByText('Listening — the map builds as activity arrives.')).toBeVisible();
  await expect(page.getByText('Map cleared. Listening from a new live edge.')).toBeVisible();
  snapshot = await diagnostics(page);
  expect(snapshot.state).toMatchObject({ universes: 0, groups: 0, radios: 0,
    activeCalls: 0, semanticEvents: 0, queuedObservations: 0, pendingEffects: 0 });
});

test('reduced motion retains the live graph without transient particle playback', async ({ page }, testInfo) => {
  await page.emulateMedia({ reducedMotion: 'reduce', colorScheme: 'dark' });
  const startedAt = Date.now();
  await page.goto(fixtureUrl(true));
  await waitForController(page);
  await waitForRenderer(page);
  await waitForFixtureDrain(page);
  let snapshot = await diagnostics(page);
  expect(snapshot.navigation.scope.level).toBe('overview');
  expect(snapshot.graph.nodes).toBe(snapshot.graph.counts.visibleUniverses);
  expect(snapshot.graph.counts).toMatchObject({ visibleGroups: 0, visibleRadios: 0 });
  const autoMode = page.getByRole('button', { name: 'Auto', exact: true });
  await expect(autoMode).toBeEnabled();
  await expect(autoMode).toHaveAttribute('aria-pressed', 'true');
  expect(snapshot.renderer.autoRotateRequested).toBe(true);
  expect(snapshot.renderer.autoRotateEffective).toBe(false);

  await drillToGroup(page, 'Alpha Regional', 'Overflow Operations');
  await expect.poll(async () => (await diagnostics(page)).graph.nodes, { timeout: 15_000 }).toBeGreaterThan(100);
  snapshot = await diagnostics(page);
  recordMeasurement(testInfo, 'reduced_motion_burst_drain', Date.now() - startedAt);
  expect(await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches)).toBe(true);
  expect(snapshot.graph.nodes).toBeLessThanOrEqual(1_000);
  expect(snapshot.graph.links).toBeLessThanOrEqual(900);
  expect(snapshot.renderer.pendingParticles).toBe(0);
  await expect(page.locator('.network-visualizer-empty')).toBeHidden();
});

test('activity received while hidden renders current state without replaying transient effects', async ({ page }) => {
  await page.goto(fixtureUrl(false));
  await waitForController(page);
  await waitForRenderer(page);
  await page.evaluate(() => {
    const system = 'hidden-system';
    const configuration = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
    const radio = { kind: 'radio', identity_key: 'radio:hidden:8801', native_id: '8801',
      label: 'Hidden Radio', radio_system_key: system };
    const group = { kind: 'talkgroup', identity_key: 'tg:hidden:401', native_id: '401',
      label: 'Hidden Group', radio_system_key: system };
    window.networkVisualizerTest.setHidden(true);
    window.networkVisualizerTest.dispatchNetwork('network_event', {
      kind: 'affiliation_observed', event_id: 'hidden-affiliation', observed_at_ms: Date.now(), protocol: 'p25',
      radio_system_key: system, system_name: 'Hidden System', configuration_id: configuration,
      comparison_scope_key: `${configuration}:1:1:x`, evidence_type: 'group_affiliation_response',
      outcome: 'accepted', sequence: 1, radio, group
    });
  });
  await page.evaluate(() => {
    const system = 'hidden-system';
    const configuration = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
    const radio = { kind: 'radio', identity_key: 'radio:hidden:8801', native_id: '8801',
      label: 'Hidden Radio', radio_system_key: system };
    const group = { kind: 'talkgroup', identity_key: 'tg:hidden:401', native_id: '401',
      label: 'Hidden Group', radio_system_key: system };
    window.networkVisualizerTest.dispatchNetwork('network_event', {
      kind: 'call_start', event_id: 'hidden-call', transmission_state: 'active', observed_at_ms: Date.now(),
      protocol: 'p25', radio_system_key: system, system_name: 'Hidden System', configuration_id: configuration,
      call_leg_id: 'hidden-leg', resource_context_key: 'hidden-resource', radio, group
    });
  });
  await expect.poll(async () => (await diagnostics(page)).state.activeCalls).toBe(1);
  await expect.poll(async () => (await diagnostics(page)).state.pendingEffects).toBeGreaterThan(0);
  await page.evaluate(() => window.networkVisualizerTest.setHidden(false));
  await expect.poll(async () => (await diagnostics(page)).graph.nodes).toBe(1);
  const snapshot = await diagnostics(page);
  expect(snapshot.state).toMatchObject({ universes: 1, groups: 1, radios: 1, activeCalls: 1 });
  expect(snapshot.graph.counts).toMatchObject({ visibleUniverses: 1, visibleGroups: 0, visibleRadios: 0 });
  expect(snapshot.navigation.scope.level).toBe('overview');
  expect(snapshot.renderer.pendingParticles).toBe(0);
  expect(snapshot.renderer.animatedEffects).toBe(0);

  await page.evaluate(() => {
    const system = 'hidden-system';
    const configuration = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
    const radio = { kind: 'radio', identity_key: 'radio:hidden:8801', native_id: '8801',
      label: 'Hidden Radio', radio_system_key: system };
    const groups = [
      { kind: 'talkgroup', identity_key: 'tg:hidden:401', native_id: '401',
        label: 'Hidden Group', radio_system_key: system },
      { kind: 'talkgroup', identity_key: 'tg:hidden:402', native_id: '402',
        label: 'Hidden Alternate', radio_system_key: system }
    ];
    window.networkVisualizerTest.setHidden(true);
    for (let index = 0; index < 1_200; index += 1) {
      window.networkVisualizerTest.dispatchNetwork('network_event', {
        kind: 'affiliation_observed', event_id: `hidden-backlog-${index}`,
        observed_at_ms: Date.now() + index + 1, protocol: 'p25', radio_system_key: system,
        system_name: 'Hidden System', configuration_id: configuration,
        comparison_scope_key: `${configuration}:1:1:x`, evidence_type: 'group_affiliation_response',
        outcome: 'accepted', sequence: index + 2, radio, group: groups[index % groups.length]
      });
    }
    // Both visibility changes occur in this task, so the ingestion timer sees a genuine multi-batch backlog.
    window.networkVisualizerTest.setHidden(false);
  });
  await expect.poll(async () => (await diagnostics(page)).state.queuedObservations,
    { timeout: 15_000 }).toBe(0);
  const afterBacklog = await diagnostics(page);
  expect(afterBacklog.state.pendingEffects).toBeGreaterThan(0);
  expect(afterBacklog.renderer.pendingParticles).toBe(0);
  expect(afterBacklog.renderer.animatedEffects).toBe(0);
});

test('a controlled confirmed affiliation change surfaces the transient alert', async ({ page }) => {
  await page.goto(fixtureUrl(false));
  await waitForController(page);
  await waitForRenderer(page);

  const dispatchAffiliation = (sequence, groupId, groupLabel) => page.evaluate((value) => {
    const system = 'alert-system';
    const configuration = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';
    window.networkVisualizerTest.dispatchNetwork('network_event', {
      kind: 'affiliation_observed', event_id: `alert-affiliation-${value.sequence}`,
      observed_at_ms: Date.now(), protocol: 'p25', radio_system_key: system,
      system_name: 'Alert System', configuration_id: configuration,
      comparison_scope_key: `${configuration}:1:2:x`, evidence_type: 'group_affiliation_response',
      outcome: 'accepted', sequence: value.sequence,
      radio: { kind: 'radio', identity_key: 'radio:alert:41', native_id: '41',
        label: 'Alert Unit', radio_system_key: system },
      group: { kind: 'talkgroup', identity_key: `tg:alert:${value.groupId}`,
        native_id: value.groupId, label: value.groupLabel, radio_system_key: system }
    });
  }, { sequence, groupId, groupLabel });

  await dispatchAffiliation(1, '101', 'Dispatch');
  await expect.poll(async () => (await diagnostics(page)).state.queuedObservations).toBe(0);
  await dispatchAffiliation(2, '202', 'Tactical');
  await expect.poll(async () => (await diagnostics(page)).state.queuedObservations).toBe(0);

  const alert = page.getByRole('alert');
  await expect(alert).toBeVisible();
  await expect(alert).toContainText('Observed affiliation change');
  await expect(alert).toContainText('Alert Unit');
  await expect(alert).toContainText('Dispatch → Tactical');
  await expect(alert).toContainText('Alert System');
});

test('incoming queue overflow is surfaced as a live gap and clears stale work', async ({ page }) => {
  await page.goto(fixtureUrl(false));
  await waitForController(page);
  await waitForRenderer(page);
  await page.evaluate(() => {
    for (let index = 0; index < 4_097; index += 1) {
      window.networkVisualizerTest.dispatchNetwork('network_event', {
        kind: 'unsupported_fixture_event', event_id: `queue-overflow-${index}`
      });
    }
  });
  await expect(page.locator('.network-visualizer-status')).toHaveText('Live gap');
  await expect(page.locator('.network-visualizer-status'))
    .toHaveAttribute('title', 'A live observation interval was lost; active call continuity is uncertain.');
  await expect(page.getByText('Live observation gap — active transmission continuity is uncertain.'))
    .toHaveCount(0);
  await expect(page.locator('.network-visualizer-notice')).toBeHidden();
  const snapshot = await diagnostics(page);
  expect(snapshot.state.queuedObservations).toBe(0);
  expect(snapshot.renderer.pendingParticles).toBe(0);
  expect(snapshot.renderer.animatedEffects).toBe(0);
});

test('conventional P25 reports affiliation as unsupported and live call updates refresh the inspector', async ({ page }) => {
  await page.goto(fixtureUrl(false));
  await waitForController(page);
  await waitForRenderer(page);
  const dispatchCall = (encrypted) => page.evaluate((encryptedValue) => {
    const configuration = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
    window.networkVisualizerTest.dispatchNetwork('network_event', {
      kind: encryptedValue ? 'call_update' : 'call_start',
      event_id: encryptedValue ? 'conventional-update' : 'conventional-start',
      transmission_state: 'active', observed_at_ms: Date.now(), protocol: 'p25',
      configuration_id: configuration, channel_name: 'Conventional P25',
      call_leg_id: 'conventional-leg', resource_context_key: 'conventional-resource',
      encrypted: encryptedValue,
      radio: { kind: 'radio', identity_key: 'radio:conventional:77', native_id: '77',
        label: 'Conventional Unit' },
      group: { kind: 'talkgroup', identity_key: 'tg:conventional:7', native_id: '7',
        label: 'Conventional Group' }
    });
  }, encrypted);
  await dispatchCall(false);
  await expect.poll(async () => (await diagnostics(page)).state.queuedObservations).toBe(0);
  await drillToGroup(page, 'Conventional P25', 'Conventional Group');
  await clickRenderedNode(page, 'Conventional Unit', 'group');
  const inspector = page.getByRole('complementary', { name: 'Selected network entity' });
  await expect(inspector).toContainText('Unsupported by this live feed');
  await expect(inspector).not.toContainText('Encryption');

  await dispatchCall(true);
  await expect.poll(async () => (await diagnostics(page)).state.queuedObservations).toBe(0);
  await expect(inspector).toContainText('Observed on current/recent transmission');
});

test('WebGL bundle failure keeps live ingestion and the readable activity drawer available', async ({ page }) => {
  await page.route('**/assets/vendor/network-visualizer-vendor.js*', (route) => route.abort());
  await page.goto(fixtureUrl(true));
  await waitForController(page);
  await waitForRenderer(page);
  await expect.poll(async () => (await diagnostics(page)).state.queuedObservations, { timeout: 15_000 }).toBe(0);
  await expect(page.getByText('WebGL is unavailable')).toBeVisible();
  await expect(page.getByRole('complementary', { name: 'Recent observed network events' })).toBeVisible();
  const snapshot = await diagnostics(page);
  expect(snapshot.state.radios).toBeGreaterThan(1_000);
  expect(snapshot.renderer.available).toBe(false);
});

test('repeated populated teardown releases owned resources without retained heap growth', async ({ page }, testInfo) => {
  await page.goto(fixtureUrl(true));
  await waitForController(page);
  await waitForRenderer(page);
  await expect.poll(async () => (await diagnostics(page)).state.queuedObservations, { timeout: 15_000 }).toBe(0);

  // Warm the graph library and shader/program caches before establishing the retained-heap baseline.
  await page.evaluate(() => window.networkVisualizerTest.close());
  await page.requestGC();
  const before = await page.evaluate(() => performance.memory?.usedJSHeapSize || 0);

  for (let cycle = 0; cycle < 3; cycle += 1) {
    await page.evaluate(() => window.networkVisualizerTest.mount());
    await waitForRenderer(page);
    await expect.poll(async () => (await diagnostics(page)).state.queuedObservations, { timeout: 15_000 }).toBe(0);
    const active = await diagnostics(page);
    expect(active.graph.nodes).toBeLessThanOrEqual(1_000);
    expect(active.graph.links).toBeLessThanOrEqual(900);
    await page.evaluate(() => window.networkVisualizerTest.close());
    await page.requestGC();
    const closed = await page.evaluate(() => ({
      metrics: { ...window.networkVisualizerTest.metrics },
      layouts: document.querySelectorAll('.network-visualizer-layout').length,
      canvases: document.querySelectorAll('.network-visualizer-canvas canvas').length
    }));
    expect(closed.metrics.networkActive).toBe(0);
    expect(closed.metrics.channelActive).toBe(0);
    expect(closed.layouts).toBe(0);
    expect(closed.canvases).toBe(0);
  }

  const after = await page.evaluate(() => performance.memory?.usedJSHeapSize || 0);
  const retainedDelta = Math.max(0, after - before);
  recordMeasurement(testInfo, 'cleanup_retained_heap_delta', retainedDelta, 'B');
  if (before > 0 && after > 0) {
    expect(retainedDelta).toBeLessThan(16 * 1024 * 1024);
  }
});
