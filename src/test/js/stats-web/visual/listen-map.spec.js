'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const fs = require('node:fs');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

const position = (latitude, longitude, timestamp_ms) => ({ latitude, longitude, timestamp_ms });
const snapshot = {
  generated_at_ms: 1_700_000_000_000, dropped_observations: 0, evicted_entities: 0,
  entities: [{ id: 'engine-4', label: 'Engine 4', identifier: '1204185', alias_list: 'County',
    icon: 'fire-truck', color: '#a82323', heading: 92, speed_kph: 41,
    positions: [position(41.5020, -81.6850, 1_700_000_010_000),
      position(41.5000, -81.6900, 1_700_000_000_000)] }]
};

async function openMap(page, allowed = true, mapSnapshot = snapshot) {
  const tileRequests = [];
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
        capabilities: { 'call-audio': allowed, dashboard: true } } } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences: defaultPreferences } });
    } else if (pathname === '/api/v1/listen/map') {
      await route.fulfill({ json: { data: mapSnapshot } });
    } else if (pathname === '/api/v1/listen/map/icons/fire-truck') {
      await route.fulfill({ contentType: 'image/png', body: icon });
    } else {
      await route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
    }
  });
  await page.goto('/app.html?view=map');
  return tileRequests;
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
  await expect(page.locator('.listen-map-details')).toContainText('41.50200, -81.68500');
  await expect(page.locator('.listen-map-details')).toContainText('41 km/h');
  await expect(page.locator('.listen-map-attribution a')).toHaveAttribute('href',
    'https://www.openstreetmap.org/copyright');
  const tileImages = page.locator('.listen-map-tiles img');
  await expect(tileImages.first()).toHaveAttribute('referrerpolicy', 'origin');
  expect(await tileImages.count()).toBeLessThanOrEqual(30);
  expect(tileRequests.length).toBeLessThanOrEqual(30);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator('.listen-map-layout')).toHaveCSS('grid-template-columns', /^\d+(?:\.\d+)?px$/);
  await expect(page.locator('.listen-map-list-item')).toBeVisible();
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
