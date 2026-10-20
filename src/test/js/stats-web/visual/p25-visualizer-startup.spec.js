'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, channelId, groupKey, radioKey, systemName, p25Affiliations } =
  require('./fixtures/site-style-app.cjs');

const now = Date.parse('2026-10-01T12:00:00Z');
const otherSystemKey = 'p25:00002:002';
const otherSystemName = 'Regional Transit';
const tacticalGroupKey = 'v1-g-00001-001-1202';
const rendererSource = fs.readFileSync(path.resolve(__dirname,
  '../../../../../stats-web/assets/features/network-visualizer/renderer.js'), 'utf8');
const activity = (id, overrides = {}) => ({ id, observed_at_ms: now - 1_000, action: 'JOIN',
  protocol: 'P25', channel_kind: 'TRUNKED_SITE', radio_system_key: systemKey, system_name: systemName,
  configuration_id: channelId, source_identity_key: radioKey, source_radio_id: 30914,
  source_alias_name: 'Engine 4', target_identity_key: groupKey, target_kind: 'talkgroup',
  target_id: 1201, target_alias_name: 'Fire Dispatch', ...overrides });
const otherActivity = (id) => activity(id, { radio_system_key: otherSystemKey,
  system_name: otherSystemName, source_identity_key: 'v1-r-00002-002-30914',
  target_identity_key: 'v1-g-00002-002-1201', source_alias_name: 'Transit supervisor',
  target_alias_name: 'Transit Operations', action: 'EMERGENCY' });
const firstPage = [activity(1), otherActivity(2)];
const secondPage = [activity(3, { observed_at_ms: now - 2_000, target_identity_key: tacticalGroupKey,
  target_id: 1202, target_alias_name: 'Fire Tactical' }), activity(4, { action: 'EMERGENCY' })];

function barrier() {
  let release;
  const ready = new Promise((resolve) => { release = resolve; });
  return { ready, release };
}

async function observeStartup(page, theme = 'light', width = 1280) {
  await page.setViewportSize({ width, height: 844 });
  const fixture = await installSiteStyleApplication(page, theme);
  await page.clock.setSystemTime(new Date(now));
  await page.addInitScript(() => {
    window.p25Startup = { requests: [], calls: [] };
    const original = window.fetch.bind(window);
    window.fetch = async (input, options) => {
      const url = new URL(typeof input === 'string' ? input : input.url, location.href);
      if (url.pathname !== '/api/v1/activity/live/seed') return original(input, options);
      const record = { params: Object.fromEntries(url.searchParams), aborted: false, finished: false };
      window.p25Startup.requests.push(record);
      options?.signal?.addEventListener('abort', () => { record.aborted = true; }, { once: true });
      const response = await original(input, options);
      record.finished = true;
      record.finishedAt = performance.now();
      return response;
    };
  });
  // Delegate every operation to the real renderer, recording the synchronous initial camera pose.
  const observed = rendererSource.replace("\n  host.dataset.rendererState = 'ready';", `
    window.p25Startup.camera = () => ({ ...cameraPose(), programmatic });
    window.p25Startup.renderedNodeCount = () => {
      let count = 0;
      graph.scene().traverse((object) => { if (object.userData?.p25Node) count += 1; });
      return count;
    };
    host.dataset.rendererState = 'ready';`)
    .replace('export { calculateFocusBounds, enqueueAffiliationChange, createP25Renderer };', `
    async function observedRenderer(options) {
      const renderer = await createP25Renderer(options);
      const fixture = window.p25Startup;
      fixture.rendererReadyAt = performance.now();
      return Object.freeze({ ...renderer,
        setData(data, options) {
          fixture.calls.push({ name: 'data', at: performance.now(),
            nodes: data.nodes.map((node) => ({ ...node })),
            links: data.links.map((link) => ({ ...link })) });
          return renderer.setData(data, options);
        },
        enterSystem(key, options) {
          const result = renderer.enterSystem(key, options);
          fixture.calls.push({ name: 'enter', key, options, at: performance.now(),
            camera: fixture.camera?.() });
          return result;
        },
        dispose() {
          fixture.calls.push({ name: 'dispose', at: performance.now() });
          return renderer.dispose();
        }
      });
    }
    export { calculateFocusBounds, enqueueAffiliationChange, observedRenderer as createP25Renderer };`);
  await page.route('**/assets/features/network-visualizer/renderer.js?**', (route) =>
    route.fulfill({ contentType: 'text/javascript', body: observed }));
  return fixture;
}

async function holdVendor(page) {
  const held = barrier();
  let requested = false;
  await page.route('**/assets/vendor/network-visualizer-vendor.js?**', async (route) => {
    requested = true;
    await held.ready;
    await route.continue();
  });
  return { ...held, requested: () => requested };
}

async function fulfillSeed(route, rows, epoch = 'fixture-p25') {
  await route.fulfill({ json: { data: { source_epoch: epoch, sequence: 0, available: true, affiliations_truncated: false, rows, ...p25Affiliations(rows),
    captured_at_ms: now, traffic_grant_age_out_milliseconds: 1_200, collection_enabled: true } } });
}

async function expectImmediateSystem(page, key = systemKey, name = systemName) {
  await expect(page.locator('.network-visualizer-scope-title')).toContainText(name, { timeout: 1_500 });
  await expect(page.locator('.network-visualizer-canvas canvas')).toBeVisible();
  const result = await page.evaluate(() => {
    const fixture = window.p25Startup;
    return { entries: fixture.calls.filter((call) => call.name === 'enter'),
      data: fixture.calls.filter((call) => call.name === 'data' && call.nodes.length),
      readyAt: Math.max(fixture.rendererReadyAt,
        ...fixture.requests.filter((request) => request.finished && !request.aborted)
          .map((request) => request.finishedAt)), requests: fixture.requests };
  });
  expect(result.entries).toHaveLength(1);
  const entry = result.entries[0];
  expect(entry.key).toBe(key);
  expect(entry.options.immediate).toBe(true);
  expect(entry.at - result.readyAt).toBeLessThan(500);
  expect(entry.camera.programmatic).toBe(false, 'initial entry has no pending camera tween');
  const system = result.data.at(-1).nodes.find((node) => node.type === 'system');
  expect(entry.camera.position.x).toBeCloseTo(system.x + system.radius * 0.47, 5);
  expect(entry.camera.position.y).toBeCloseTo(system.y + system.radius * 0.25, 5);
  expect(entry.camera.position.z).toBeCloseTo(system.z + system.radius * 0.53, 5);
  expect(entry.camera.target).toEqual({ x: system.x, y: system.y, z: system.z });
  await expect.poll(() => page.evaluate(() => window.p25Startup.renderedNodeCount()))
    .toBe(result.data.at(-1).nodes.length);
  expect(result.requests.every((request) => Object.keys(request.params).length === 0)).toBe(true);
  await expect(page.locator('.network-visualizer-scope-title a.ui-link-text')).toHaveAttribute('href',
    new RegExp(`radio_system_key=${encodeURIComponent(key)}`));
  return result;
}

test.setTimeout(10_000);

test('one recent seed finishes while the renderer vendor is still loading', async ({ page }, testInfo) => {
  const fixture = await observeStartup(page);
  const vendor = await holdVendor(page);
  await page.route('**/api/v1/activity/live/seed*', (route) => fulfillSeed(route, [...firstPage, ...secondPage]));
  try {
    await page.goto('/app.html?view=network-visualizer', { waitUntil: 'domcontentloaded' });
    await expect.poll(vendor.requested).toBe(true);
    await expect.poll(() => page.evaluate(() => window.p25Startup.requests.filter((value) => value.finished).length)).toBe(1);
    expect(await page.evaluate(() => window.p25Startup.rendererReadyAt)).toBeUndefined();
    await expect(page.locator('.network-visualizer-scope-title')).toHaveText('P25 radio systems');
    vendor.release();
    const result = await expectImmediateSystem(page);
    expect(result.requests).toHaveLength(1);
    expect(fixture.requests).not.toContain('/api/v1/activity');
    const data = result.data.at(-1);
    const current = data.links.find((link) => link.kind === 'current');
    expect(current.target).toBe(`${systemKey}:${groupKey}`,
      'canonical current state is independent of seed event order');
    expect(data.nodes.find((node) => node.id === current.source).label).toBe('Engine 4');
    expect(data.nodes.find((node) => node.id === current.target).label).toBe('Fire Dispatch');
    expect(data.nodes.some((node) => node.label === 'Fire Tactical')).toBe(true);
    await page.screenshot({ path: testInfo.outputPath('recent-startup-desktop-light.png') });
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  } finally { vendor.release(); }
});

test('a ready renderer waits for the single recent seed before entering the busiest system', async ({ page }) => {
  const fixture = await observeStartup(page);
  const seed = barrier();
  let requested = false;
  await page.route('**/api/v1/activity/live/seed*', async (route) => {
    requested = true;
    await seed.ready;
    await fulfillSeed(route, [...firstPage, ...secondPage]);
  });
  try {
    await page.goto('/app.html?view=network-visualizer', { waitUntil: 'domcontentloaded' });
    await expect.poll(() => requested).toBe(true);
    await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
    expect(await page.evaluate(() => window.p25Startup.calls.filter((call) => call.name === 'enter' ||
      call.name === 'data' && call.nodes.length))).toEqual([]);
    await expect(page.locator('.network-visualizer-status')).toHaveText('Loading recent activity');
    seed.release();
    await expectImmediateSystem(page);
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  } finally { seed.release(); }
});

test('source replacement before renderer readiness discards the obsolete seed on a dark phone',
  async ({ page }, testInfo) => {
  const fixture = await observeStartup(page, 'dark', 390);
  const vendor = await holdVendor(page);
  let epoch = 'fixture-p25';
  await page.route('**/api/v1/activity/live/seed*', (route) => fulfillSeed(route,
    epoch === 'fixture-p25' ? [activity(1)] : [otherActivity(4)], epoch));
  try {
    await page.goto('/app.html?view=network-visualizer', { waitUntil: 'domcontentloaded' });
    await expect.poll(() => page.evaluate(() => window.p25Startup.requests.filter((value) => value.finished).length)).toBe(1);
    epoch = 'fixture-p25-next';
    await page.evaluate(() => window.fixtureSendLiveFrame(7, 'source_change', {
      source_epoch: 'fixture-p25-next', sequence: 0, server_time_ms: Date.now(), collection_enabled: true }));
    await expect.poll(() => page.evaluate(() => window.p25Startup.requests.filter((value) => value.finished).length)).toBe(2);
    vendor.release();
    const result = await expectImmediateSystem(page, otherSystemKey, otherSystemName);
    expect(result.data.every((data) => data.nodes.every((node) => node.systemKey === otherSystemKey))).toBe(true);
    await expect(page.getByRole('group', { name: 'History scope' })).toHaveCount(0);
    await page.screenshot({ path: testInfo.outputPath('recent-startup-mobile-dark.png') });
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  } finally { vendor.release(); }
});

test('early navigation aborts the recent seed and disposes a renderer that finishes loading later', async ({ page }) => {
  const fixture = await observeStartup(page);
  const vendor = await holdVendor(page);
  const history = barrier();
  await page.route('**/api/v1/activity/live/seed*', async (route) => {
    await history.ready;
    await fulfillSeed(route, [activity(1)]);
  });
  try {
    await page.goto('/app.html?view=network-visualizer', { waitUntil: 'domcontentloaded' });
    await expect.poll(vendor.requested).toBe(true);
    await expect.poll(() => page.evaluate(() => window.p25Startup.requests.length)).toBe(1);
    await page.evaluate(() => document.querySelector('.primary-nav a[data-view="credits"]').click());
    await expect(page.locator('.credits-copy').first()).toBeVisible();
    await expect.poll(() => page.evaluate(() => window.p25Startup.requests[0].aborted)).toBe(true);
    vendor.release();
    history.release();
    await expect.poll(() => page.evaluate(() => window.p25Startup.calls.some((call) =>
      call.name === 'dispose'))).toBe(true);
    expect(await page.evaluate(() => window.p25Startup.calls.some((call) => call.name === 'enter' ||
      call.name === 'data' && call.nodes.length))).toBe(false);
    await expect(page.locator('.network-visualizer-canvas')).toHaveCount(0);
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  } finally { vendor.release(); history.release(); }
});
