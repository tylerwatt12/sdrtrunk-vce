'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const fs = require('node:fs');
const { expectFlatFacts } = require('./fixtures/fact-geometry.cjs');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

const position = (latitude, longitude, timestamp_ms) => ({ latitude, longitude, timestamp_ms });
const snapshot = {
  generated_at_ms: 1_700_000_000_000, dropped_observations: 0, evicted_entities: 0,
  entities: [{ id: 'engine-4', label: 'Engine 4', identifier: '1204185', alias_list: 'County',
    system: 'County P25', icon: 'fire-truck', color: '#a82323', heading: 92, speed_kph: 41,
    positions: [position(41.5020, -81.6850, 1_700_000_010_000),
      position(41.5000, -81.6900, 1_700_000_000_000)] }]
};

async function openMap(page, allowed = true, mapSnapshot = snapshot, options = {}) {
  const tileRequests = [];
  let initialSnapshotReturned = false;
  const icon = fs.readFileSync(path.resolve(__dirname,
    '../../../../../src/main/resources/images/fire_truck.png'));
  await page.route('https://tile.openstreetmap.org/**', async (route) => {
    tileRequests.push(route.request().url());
    await route.fulfill({ contentType: 'image/png', body: icon });
  });
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: { data: { configured: true, authenticated: false,
        capabilities: { 'call-audio': allowed, dashboard: true, radio: options.radio === true } } } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences: defaultPreferences } });
    } else if (pathname === '/api/v1/listen/map') {
      if (!initialSnapshotReturned && options.waitForInitialTiles) {
        // Hold the first locations response until the default geographic view has drawn.
        await expect.poll(() => tileRequests.some(url => new URL(url).pathname.startsWith('/4/'))).toBe(true);
      }
      initialSnapshotReturned = true;
      const response = typeof mapSnapshot === 'function' ? mapSnapshot() : mapSnapshot;
      await route.fulfill({ json: { data: response } });
    } else if (pathname === '/api/v1/listen/map/icons/fire-truck') {
      await route.fulfill({ contentType: 'image/png', body: icon });
    } else {
      await route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
    }
  });
  await page.goto('/app.html?view=map');
  return tileRequests;
}

function expectBoundedInitialTileRequests(tileRequests) {
  const requestsByZoom = new Map();
  for (const url of tileRequests) {
    const zoom = new URL(url).pathname.split('/')[1];
    requestsByZoom.set(zoom, (requestsByZoom.get(zoom) || 0) + 1);
  }
  // Startup may draw the default view before the first location selects its zoom.
  // Each view is bounded independently; identical redraws must reuse their tiles.
  expect([...requestsByZoom.keys()].every(zoom => ['4', '12'].includes(zoom))).toBe(true);
  expect(requestsByZoom.size).toBeLessThanOrEqual(2);
  for (const [zoom, count] of requestsByZoom) {
    expect(count, `Startup tile requests at zoom ${zoom}`).toBeLessThanOrEqual(30);
  }
  expect(tileRequests.length).toBeLessThanOrEqual(60);
  return requestsByZoom;
}

async function markerCenterOffset(page, name) {
  const viewport = await page.locator('.listen-map-viewport').boundingBox();
  const marker = await page.getByRole('button', { name: `Select ${name}` }).boundingBox();
  if (!viewport || !marker) return Number.POSITIVE_INFINITY;
  const horizontal = marker.x + marker.width / 2 - (viewport.x + viewport.width / 2);
  const vertical = marker.y + marker.height / 2 - (viewport.y + viewport.height / 2);
  return Math.hypot(horizontal, vertical);
}

async function trailSegments(page) {
  const path = page.locator('.listen-map-trails path').first();
  if (!await path.count()) return 0;
  return ((await path.getAttribute('d'))?.match(/L/g) || []).length + 1;
}

test('public listener sees geographic map, trail, standard icon, and selected details', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const tileRequests = await openMap(page);
  await expect(page.getByRole('heading', { name: 'Map', exact: true })).toBeVisible();
  await page.locator('[data-nav-group="listen"] summary').click();
  await expect(page.getByRole('link', { name: 'Map', exact: true })).toHaveClass(/active/);
  await expect(page.locator('.listen-map-viewport')).toBeVisible();
  await expect(page.locator('.listen-map-marker img')).toHaveAttribute('src',
    '/api/v1/listen/map/icons/fire-truck');
  await expect(page.locator('.listen-map-trails path')).toHaveCount(1);
  await expect(page.locator('.listen-map-details')).toContainText('Engine 4');
  await expect(page.locator('.listen-map-details')).toContainText('County P25');
  await expect(page.locator('.listen-map-details')).toContainText('41.50200, -81.68500');
  await expect(page.locator('.listen-map-details')).toContainText('41 km/h');
  await expectFlatFacts(page.locator('.listen-map-facts > .ui-fact'), { padding: '5px 0px', radius: null });
  await expect(page.locator('.listen-map-facts')).toHaveJSProperty('tagName', 'DL');
  await expect(page.locator('.listen-map-attribution a')).toHaveAttribute('href',
    'https://www.openstreetmap.org/copyright');
  const tileImages = page.locator('.listen-map-tiles img');
  await expect(tileImages.first()).toHaveAttribute('referrerpolicy', 'origin');
  expect(await tileImages.count()).toBeLessThanOrEqual(30);
  expectBoundedInitialTileRequests(tileRequests);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator('.listen-map-layout')).toHaveCSS('grid-template-columns', /^\d+(?:\.\d+)?px$/);
  await expect(page.locator('.listen-map-list-item')).toBeVisible();
  await expect(page.getByLabel('Trail length')).toBeVisible();
  await expectFlatFacts(page.locator('.listen-map-facts > .ui-fact'), { padding: '5px 0px', radius: null });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
});

test('roaming Map details keep serving and home systems distinct with the exact hexadecimal identifier',
  async ({ page }) => {
    const roaming = { ...snapshot, entities: [{ ...snapshot.entities[0],
      identifier: 'BEE00.348.1103 (Working ID 77)', system: 'GCRCN',
      serving_system: { key: 'p25:bee00:49f', name: 'GCRCN', wacn: 0xBEE00, system_id: 0x49F,
        entity_ref: { kind: 'radio_system', key: 'p25:bee00:49f' } },
      home_system: { key: 'p25:bee00:348', name: 'Ohio MARCS-IP', wacn: 0xBEE00, system_id: 0x348,
        entity_ref: { kind: 'radio_system', key: 'p25:bee00:348' } }
    }] };
    await openMap(page, true, roaming, { radio: true });
    const facts = page.locator('.listen-map-facts');
    await expect(facts).toContainText('BEE00.348.1103 (Working ID 77)');
    await expect(facts).toContainText('BEE00-49F');
    await expect(facts).toContainText('BEE00-348');
    const servingLink = facts.getByRole('link', { name: 'GCRCN', exact: true });
    const homeLink = facts.getByRole('link', { name: 'Ohio MARCS-IP', exact: true });
    await expect(servingLink).toHaveAttribute('href', /radio_system_key=p25%3Abee00%3A49f/);
    await expect(homeLink).toHaveAttribute('href', /radio_system_key=p25%3Abee00%3A348/);
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(homeLink).toBeVisible();
    await expect(facts).toContainText('BEE00.348.1103 (Working ID 77)');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
  });

test('delayed first locations keep both startup views bounded and unchanged refreshes reuse tiles', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const tileRequests = await openMap(page, true, snapshot, { waitForInitialTiles: true });
  await expect(page.locator('.listen-map-marker img')).toHaveAttribute('src',
    '/api/v1/listen/map/icons/fire-truck');
  const tileImages = page.locator('.listen-map-tiles img');
  await expect.poll(() => tileImages.evaluateAll(images =>
    images.length > 0 && images.every(image => image.complete && image.naturalWidth > 0))).toBe(true);
  expect(await tileImages.count()).toBeLessThanOrEqual(30);
  await expect(tileImages.first()).toHaveAttribute('referrerpolicy', 'origin');
  expect([...expectBoundedInitialTileRequests(tileRequests).keys()].sort()).toEqual(['12', '4']);
  const initialRequests = tileRequests.length;
  for (let index = 0; index < 2; index += 1) {
    await Promise.all([
      page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/listen/map'),
      page.getByRole('button', { name: 'Refresh', exact: true }).click()
    ]);
    await page.evaluate(() => new Promise(resolve =>
      requestAnimationFrame(() => requestAnimationFrame(resolve))));
    expect(tileRequests.length, 'Unchanged location redraw must reuse existing tile images').toBe(initialRequests);
    expect(await tileImages.count()).toBeLessThanOrEqual(30);
  }
});

test('trail length defaults to three and offers the full one-to-ten range', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const positions = Array.from({ length: 10 }, (_, index) =>
    position(41.5020 - index * .001, -81.6850 - index * .001, 1_700_000_010_000 - index * 1000));
  await openMap(page, true, { ...snapshot, entities: [{ ...snapshot.entities[0], positions }] });

  const length = page.getByLabel('Trail length');
  await expect(length).toHaveValue('3');
  await expect(length.locator('option')).toHaveCount(10);
  await expect.poll(() => trailSegments(page)).toBe(3);
  await expect(page.locator('.listen-map-history-item')).toHaveCount(10);

  await length.selectOption('1');
  await expect(page.locator('.listen-map-trails path')).toHaveCount(0);
  await expect(page.locator('.listen-map-history-item')).toHaveCount(10);
  await length.selectOption('10');
  await expect.poll(() => trailSegments(page)).toBe(10);
});

test('selection centering and detailed history use explicit operator controls', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const enginePositions = [
    position(41.5020, -81.6850, 1_700_000_010_000),
    position(41.5010, -81.7150, 1_700_000_005_000),
    position(41.5000, -81.7450, 1_700_000_000_000)
  ];
  const medic = { ...snapshot.entities[0], id: 'medic-2', label: 'Medic 2', identifier: '1204186',
    positions: [position(41.5020, -81.6350, 1_700_000_010_000)] };
  await openMap(page, true, { ...snapshot, entities: [
    { ...snapshot.entities[0], positions: enginePositions }, medic
  ] });

  const centerSelection = page.getByRole('checkbox', { name: 'Center on selection' });
  await expect(centerSelection).toBeChecked();
  const history = page.locator('.listen-map-history-item');
  const historyRows = page.locator('.listen-map-history tbody tr');
  await expect(history).toHaveCount(3);
  await expect(historyRows.nth(0)).toContainText('41.50200');
  await expect(historyRows.nth(2)).toContainText('41.50000');
  await expect(history.nth(0).getByText('2023-11-14', { exact: true })).toBeVisible();
  await expect(history.nth(0).getByText('22:13:30', { exact: true })).toBeVisible();

  await centerSelection.focus();
  await centerSelection.press('Space');
  await expect(centerSelection).not.toBeChecked();
  const engineOffset = await markerCenterOffset(page, 'Engine 4');
  await page.locator('.listen-map-list-item').filter({ hasText: 'Medic 2' }).click();
  await expect.poll(async () => Math.abs(await markerCenterOffset(page, 'Engine 4') - engineOffset))
    .toBeLessThan(2);

  await centerSelection.focus();
  await centerSelection.press('Space');
  await expect(centerSelection).toBeChecked();
  await page.locator('.listen-map-list-item').filter({ hasText: 'Medic 2' }).click();
  await expect.poll(() => markerCenterOffset(page, 'Medic 2')).toBeLessThan(3);
  await page.locator('.listen-map-list-item').filter({ hasText: 'Engine 4' }).click();
  await expect.poll(() => markerCenterOffset(page, 'Engine 4')).toBeLessThan(3);
  await page.getByRole('button', { name: 'Change audio player size', exact: true }).press('Home');
  await history.nth(2).locator('.listen-map-history-center').click();
  await expect.poll(() => markerCenterOffset(page, 'Engine 4')).toBeGreaterThan(80);
});

test('unchanged refresh preserves history focus without repeating the live status', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openMap(page);
  const historyPosition = page.locator('.listen-map-history-center').nth(1);
  await historyPosition.focus();
  await expect(historyPosition).toBeFocused();
  const status = page.locator('.listen-map-status');
  const statusText = await status.textContent();
  await page.evaluate(() => {
    const target = document.querySelector('.listen-map-status');
    window.__mapStatusMutations = 0;
    window.__mapStatusObserver = new MutationObserver((records) => {
      window.__mapStatusMutations += records.length;
    });
    window.__mapStatusObserver.observe(target, { childList: true, characterData: true, subtree: true });
  });

  await Promise.all([
    page.waitForResponse((response) => new URL(response.url()).pathname === '/api/v1/listen/map'),
    page.getByRole('button', { name: 'Refresh' }).evaluate((button) => button.click())
  ]);
  await expect(historyPosition).toBeFocused();
  await expect(status).toHaveText(statusText);
  await expect.poll(() => page.evaluate(() => window.__mapStatusMutations)).toBe(0);
});

test('follow recenters on each newer receiver observation', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  let current = snapshot;
  await openMap(page, true, () => current);
  await page.getByRole('button', { name: 'Follow', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Unfollow', exact: true })).toBeVisible();

  const viewport = page.locator('.listen-map-viewport');
  await viewport.focus();
  for (let index = 0; index < 4; index += 1) await viewport.press('ArrowRight');
  await expect.poll(() => markerCenterOffset(page, 'Engine 4')).toBeGreaterThan(100);
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect.poll(() => markerCenterOffset(page, 'Engine 4')).toBeGreaterThan(100);

  current = { ...snapshot, generated_at_ms: snapshot.generated_at_ms + 5000, entities: [{
    ...snapshot.entities[0], positions: [
      position(41.5150, -81.6550, 1_700_000_015_000), ...snapshot.entities[0].positions
    ]
  }] };
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect.poll(() => markerCenterOffset(page, 'Engine 4')).toBeLessThan(3);
});

test('clear and replot only change what this browser draws', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const medic = { ...snapshot.entities[0], id: 'medic-2', label: 'Medic 2',
    positions: [position(41.51, -81.67, 1_700_000_010_000),
      position(41.50, -81.68, 1_700_000_000_000)] };
  let current = { ...snapshot, entities: [snapshot.entities[0], medic] };
  await openMap(page, true, () => current);
  await expect(page.locator('.listen-map-list-item')).toHaveCount(2);
  await expect(page.locator('.listen-map-marker')).not.toHaveCount(0);

  await page.getByRole('button', { name: 'Clear map', exact: true }).click();
  await expect(page.locator('.listen-map-marker')).toHaveCount(0);
  await expect(page.locator('.listen-map-trails path')).toHaveCount(0);
  await expect(page.locator('.listen-map-list-item')).toHaveCount(2);
  await page.locator('.listen-map-list-item').filter({ hasText: 'Medic 2' }).click();
  await expect(page.locator('.listen-map-marker')).toHaveCount(1);
  await page.getByRole('button', { name: 'Clear map', exact: true }).click();
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.locator('.listen-map-marker')).toHaveCount(0);

  current = { ...current, entities: [{ ...snapshot.entities[0], positions: [
    position(41.52, -81.65, 1_700_000_020_000), ...snapshot.entities[0].positions
  ] }, medic] };
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.locator('.listen-map-marker')).toHaveCount(1);

  await page.getByRole('button', { name: 'Replot all', exact: true }).click();
  await expect(page.locator('.listen-map-marker')).toHaveCount(2);
  await expect(page.locator('.listen-map-trails path')).not.toHaveCount(0);
  await expect(page.locator('.listen-map-list-item')).toHaveCount(2);
});

test('removed tracks return only after a new observation and start fresh history', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const medic = { ...snapshot.entities[0], id: 'medic-2', label: 'Medic 2', identifier: '1204186',
    positions: [position(41.51, -81.67, 1_700_000_010_000),
      position(41.50, -81.68, 1_700_000_000_000)] };
  let current = { ...snapshot, entities: [snapshot.entities[0], medic] };
  await openMap(page, true, () => current);

  await page.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(page.locator('.listen-map-list-item')).toHaveCount(1);
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.locator('.listen-map-list-item')).toHaveCount(1);

  current = { ...current, entities: [{ ...snapshot.entities[0], positions: [
    position(41.52, -81.65, 1_700_000_020_000), ...snapshot.entities[0].positions
  ] }, medic] };
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.locator('.listen-map-list-item')).toHaveCount(2);
  await page.locator('.listen-map-list-item').filter({ hasText: 'Engine 4' }).click();
  await expect(page.locator('.listen-map-history-item')).toHaveCount(1);

  await page.getByRole('button', { name: 'Remove all', exact: true }).click();
  await expect(page.locator('.listen-map-list-item')).toHaveCount(0);
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.locator('.listen-map-list-item')).toHaveCount(0);

  current = { ...current, entities: [current.entities[0], { ...medic, positions: [
    position(41.53, -81.64, 1_700_000_030_000), ...medic.positions
  ] }] };
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.locator('.listen-map-list-item')).toHaveCount(1);
  await expect(page.locator('.listen-map-list-item')).toContainText('Medic 2');
  await expect(page.locator('.listen-map-history-item')).toHaveCount(1);
});

test('configured Listen access policy also hides the Map from guests', async ({ page }) => {
  const tileRequests = await openMap(page, false);
  await expect(page.getByRole('heading', { name: 'Map', exact: true })).toHaveCount(0);
  await expect(page.locator('.listen-map-viewport')).toHaveCount(0);
  expect(tileRequests).toEqual([]);
});

test('Show all fits locations across the date line', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const acrossDateLine = { ...snapshot, entities: [
    { ...snapshot.entities[0], id: 'east', label: 'East', positions: [position(10, 179, 1)] },
    { ...snapshot.entities[0], id: 'west', label: 'West', positions: [position(10, -179, 1)] }
  ] };
  await openMap(page, true, acrossDateLine);
  await page.getByRole('button', { name: 'Show all' }).click();
  const viewport = page.locator('.listen-map-viewport');
  await expect(viewport.getByRole('button', { name: 'Select East' })).toBeVisible();
  await expect(viewport.getByRole('button', { name: 'Select West' })).toBeVisible();
  const bounds = await viewport.boundingBox();
  for (const name of ['East', 'West']) {
    const marker = await viewport.getByRole('button', { name: `Select ${name}` }).boundingBox();
    expect(marker.x).toBeGreaterThanOrEqual(bounds.x);
    expect(marker.x + marker.width).toBeLessThanOrEqual(bounds.x + bounds.width);
  }
});

test('marker remains on map after panning across two wrapped worlds', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openMap(page);
  const viewport = page.locator('.listen-map-viewport');
  const marker = viewport.getByRole('button', { name: 'Select Engine 4' });
  await expect(marker).toBeVisible();
  const zoomOut = viewport.getByRole('button', { name: 'Zoom out' });
  await zoomOut.click();
  await viewport.focus();
  for (let index = 0; index < 15; index += 1) await viewport.press('-');
  await expect(zoomOut).toBeDisabled();
  const before = await marker.boundingBox();
  await viewport.focus();
  for (let index = 0; index < 26; index += 1) await viewport.press('ArrowRight');
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await expect(marker).toBeVisible();
  const after = await marker.boundingBox();
  expect(Math.abs(after.x - before.x)).toBeLessThan(60);
  expect(await page.locator('.listen-map-tiles img').count()).toBeLessThanOrEqual(30);
});
