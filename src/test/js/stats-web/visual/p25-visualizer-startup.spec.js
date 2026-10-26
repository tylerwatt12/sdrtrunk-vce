'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, channelId, groupKey, radioKey } =
  require('./fixtures/site-style-app.cjs');

function barrier() {
  let release;
  const ready = new Promise(resolve => { release = resolve; });
  return { ready, release };
}

async function observeRenderer(page) {
  const filename = path.resolve(__dirname, '../../../../../stats-web/assets/features/network-visualizer/renderer.js');
  const original = fs.readFileSync(filename, 'utf8');
  const source = original.replace("\n  host.dataset.rendererState = 'ready';", `
  window.p25Scene = { nodes: () => graph.graphData().nodes.map(node => ({ ...node })),
    camera: () => cameraPose() };
  host.dataset.rendererState = 'ready';`);
  await page.route('**/assets/features/network-visualizer/renderer.js?**', route =>
    route.fulfill({ contentType: 'text/javascript', body: source }));
}

const noPreload = fixture => fixture.requests.filter(url => ['/api/v1/activity', '/api/v1/activity/live/seed'].includes(url));

test('the stage and current Live activity appear while the optional label catalog is pending', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page);
  const labels = barrier();
  await page.route('**/api/v1/activity/message-types', async route => {
    await labels.ready;
    await route.fulfill({ json: { data: [] } });
  });
  try {
    await page.goto('/app.html?view=network-visualizer', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
    await expect(page.locator('.network-visualizer-empty')).toBeHidden();
    await expect(page.locator('.network-visualizer-label[data-type="radio"]:not([hidden])')).toHaveText('Engine 4');
    await expect(page.getByRole('button', { name: 'Actions', exact: true })).toBeVisible();
    expect(noPreload(fixture)).toEqual([]);
    expect(fixture.pageErrors).toEqual([]);
  } finally { labels.release(); }
});

test('an empty scene waits for new activity and adds only the observed entities', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page, 'dark', { revision: 1, tables: [] });
  await observeRenderer(page);
  await page.goto('/app.html?view=network-visualizer');
  await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
  await expect(page.locator('.network-visualizer-empty')).toContainText('Waiting for P25 activity');
  await expect(page.locator('.network-visualizer-status')).toHaveText('Live');
  expect(await page.evaluate(() => window.p25Scene.nodes())).toEqual([]);
  await page.evaluate(({ systemKey, channelId, groupKey, radioKey }) => window.fixtureSendLiveFrame(7, 'activity_append', {
    source_epoch: 'fixture-p25', after_id: 1, next_after_id: 2, watermark_id: 2,
    rows: [{ id: 2, observed_at_ms: Date.now(), action: 'CALL', protocol: 'P25', channel_kind: 'TRUNKED_SITE',
      radio_system_key: systemKey, system_name: 'County P25', configuration_id: channelId,
      source_identity_key: radioKey, source_radio_id: 30914, source_alias_name: 'Engine 4',
      target_identity_key: groupKey, target_kind: 'talkgroup', target_id: 1201, target_alias_name: 'Fire Dispatch' }]
  }), { systemKey, channelId, groupKey, radioKey });
  await expect(page.locator('.network-visualizer-empty')).toBeHidden();
  await expect.poll(() => page.evaluate(() => window.p25Scene.nodes().map(node => node.type).sort()))
    .toEqual(['radio', 'system', 'talkgroup']);
  expect(noPreload(fixture)).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
});

test('navigation disposes a renderer that finishes loading after the page closes', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page);
  const vendor = barrier();
  await page.route('**/assets/vendor/network-visualizer-vendor.js?**', async route => {
    await vendor.ready;
    await route.continue();
  });
  try {
    await page.goto('/app.html?view=network-visualizer', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('.network-visualizer-stage')).toBeVisible();
    await page.evaluate(() => document.querySelector('.primary-nav a[data-view="credits"]').click());
    await expect(page.locator('.credits-copy').first()).toBeVisible();
    vendor.release();
    await expect(page.locator('.network-visualizer-canvas')).toHaveCount(0);
    expect(noPreload(fixture)).toEqual([]);
    expect(fixture.pageErrors).toEqual([]);
  } finally { vendor.release(); }
});

test('a fresh affiliation received during renderer loading keeps its source-to-destination journey', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page, 'light', { revision: 1, tables: [] });
  await page.clock.setSystemTime(new Date('2026-10-01T12:00:00Z'));
  await observeRenderer(page);
  const vendor = barrier();
  let requested = false;
  await page.route('**/assets/vendor/network-visualizer-vendor.js?**', async route => {
    requested = true;
    await vendor.ready;
    await route.continue();
  });
  try {
    await page.goto('/app.html?view=network-visualizer', { waitUntil: 'domcontentloaded' });
    await expect.poll(() => requested).toBe(true);
    await expect(page.locator('.network-visualizer-status')).toHaveText('Live');
    await page.evaluate(({ systemKey, channelId, groupKey, radioKey }) => {
      const previous = { radio_system_key: systemKey, radio_identity_key: radioKey,
        talkgroup_identity_key: groupKey, configuration_id: channelId, confirmed_at_ms: Date.now() - 2_000,
        system_name: 'County P25', radio_id: 30914, radio_alias_name: 'Engine 4',
        talkgroup_id: 1201, talkgroup_alias_name: 'Fire Dispatch' };
      window.fixtureSendLiveFrame(7, 'p25_state_delta', { source_epoch: 'fixture-p25', base_sequence: 0, sequence: 1,
        part: 0, parts: 1, available: true, collection_enabled: true, removed_radio_keys: [],
        affiliations: [{ ...previous, confirmed_at_ms: Date.now(), talkgroup_identity_key: 'v1-g-00001-001-1202',
          talkgroup_id: 1202, talkgroup_alias_name: 'Fire Tactical', previous_affiliation: previous }] });
    }, { systemKey, channelId, groupKey, radioKey });
    await page.getByRole('button', { name: 'Actions', exact: true }).click();
    await expect(page.locator('.network-visualizer-action[data-category="movement"] .ui-pill')).toHaveText('1');
    await page.getByRole('button', { name: 'Close actions', exact: true }).click();
    vendor.release();
    await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
    await expect(page.locator('.network-visualizer-auto-focus-title')).toHaveText('Observed affiliation change');
    const distances = await page.evaluate(({ systemKey, groupKey, radioKey }) => {
      const nodes = window.p25Scene.nodes(), radio = nodes.find(node => node.id === `${systemKey}:${radioKey}`);
      const source = nodes.find(node => node.id === `${systemKey}:${groupKey}`);
      const target = nodes.find(node => node.id === `${systemKey}:v1-g-00001-001-1202`);
      return { source: Math.hypot(radio.x - source.x, radio.y - source.y, radio.z - source.z),
        target: Math.hypot(radio.x - target.x, radio.y - target.y, radio.z - target.z) };
    }, { systemKey, groupKey, radioKey });
    expect(distances.source).toBeLessThan(distances.target);
    expect(noPreload(fixture)).toEqual([]);
    expect(fixture.pageErrors).toEqual([]);
  } finally { vendor.release(); }
});
