'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function openMenu(page, available, { delayStatus = false } = {}) {
  const preferences = structuredClone((await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults);
  let current = available;
  let failed = false;
  let statusRequests = 0;
  let catalogRequests = 0;
  let releaseStatus;
  const statusReady = delayStatus ? new Promise((resolve) => { releaseStatus = resolve; }) : null;
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    const respond = (data) => route.fulfill({ json: { data } });
    if (pathname === '/api/v1/auth/session') {
      await respond({ configured: true, authenticated: true, username: 'operator', tier: 'admin',
        primary: true, capabilities: { dashboard: true, radio: true, credits: true } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } });
    } else if (pathname === '/api/v1/status') {
      statusRequests += 1;
      if (statusReady) await statusReady;
      if (failed) await route.fulfill({ status: 503, json: { error: { message: 'Unavailable' } } });
      else await respond({ p25_visualizer_available: current, stats_logging: {}, database: {} });
    } else {
      if (pathname === '/api/v1/channel-catalog') catalogRequests += 1;
      await route.fulfill({ status: 404, json: { error: { message: 'Unavailable' } } });
    }
  });
  await page.goto('/app.html?view=credits');
  return {
    change: (next) => { current = next; }, fail: () => { failed = true; },
    releaseStatus: () => releaseStatus?.(),
    statusRequests: () => statusRequests, catalogRequests: () => catalogRequests
  };
}

const menuLink = (page) => page.locator('.primary-nav a[data-view="network-visualizer"]');
const menuHidden = (page) => menuLink(page).evaluate((link) => link.hidden);

for (const available of [true, false]) {
  test(`P25 Visualizer menu follows receiver availability ${available}`, async ({ page }) => {
    const app = await openMenu(page, available);
    await expect.poll(() => menuHidden(page)).toBe(!available);
    expect(app.catalogRequests()).toBe(0);
  });
}

test('P25 Visualizer menu waits for confirmed receiver status', async ({ page }) => {
  const app = await openMenu(page, true, { delayStatus: true });
  await expect.poll(app.statusRequests).toBe(1);
  expect(await menuHidden(page)).toBe(true);
  app.releaseStatus();
  await expect.poll(() => menuHidden(page)).toBe(false);
  expect(app.catalogRequests()).toBe(0);
});

test('P25 Visualizer menu follows refreshed availability and failed status', async ({ page }) => {
  await page.clock.install();
  const app = await openMenu(page, true);
  await expect.poll(() => menuHidden(page)).toBe(false);
  for (const available of [false, true]) {
    app.change(available);
    const before = app.statusRequests();
    await page.clock.runFor(10_100);
    await expect.poll(app.statusRequests).toBeGreaterThan(before);
    await expect.poll(() => menuHidden(page)).toBe(!available);
  }
  app.fail();
  const before = app.statusRequests();
  await page.clock.runFor(10_100);
  await expect.poll(app.statusRequests).toBeGreaterThan(before);
  await expect.poll(() => menuHidden(page)).toBe(true);
  expect(app.catalogRequests()).toBe(0);
});
