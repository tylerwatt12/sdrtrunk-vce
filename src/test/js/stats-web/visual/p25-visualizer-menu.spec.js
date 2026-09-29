'use strict';

const { expect, test } = require('@playwright/test');

const p25Trunked = (protocol = 'p25-phase1', state = 'RUNNING') => ({
  configuration_id: '11111111-1111-4111-8111-111111111111',
  protocol_id: protocol, channel_kind: 'TRUNKED', processing_state: state
});

async function openMenu(page, initial, { delayCatalog = false } = {}) {
  let current = initial;
  let statusRequests = 0;
  let catalogRequests = 0;
  let releaseCatalog;
  const catalogReady = delayCatalog ? new Promise((resolve) => { releaseCatalog = resolve; }) : null;
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    const respond = (data) => route.fulfill({ json: { data } });
    if (pathname === '/api/v1/auth/session') {
      await respond({ configured: true, authenticated: true, username: 'operator', tier: 'admin',
        primary: true, capabilities: { dashboard: true, radio: true, credits: true } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences: {} } });
    } else if (pathname === '/api/v1/status') {
      statusRequests += 1;
      await respond({ stats_logging: { summary_active: current.summaryActive,
        detailed_history_active: current.historyActive }, database: {} });
    } else if (pathname === '/api/v1/channel-catalog') {
      catalogRequests += 1;
      if (catalogReady) await catalogReady;
      if (current.catalogUnavailable) {
        await route.fulfill({ status: 503, json: { error: { message: 'Unavailable' } } });
      } else {
        await respond({ revision: 1, channels: current.channels });
      }
    } else {
      await route.fulfill({ status: 404, json: { error: { message: 'Unavailable' } } });
    }
  });
  await page.goto('/app.html?view=credits');
  return {
    change: (next) => { current = { ...current, ...next }; },
    releaseCatalog: () => releaseCatalog?.(),
    statusRequests: () => statusRequests,
    catalogRequests: () => catalogRequests
  };
}

const menuLink = (page) => page.locator('.primary-nav a[data-view="network-visualizer"]');
const menuHidden = (page) => menuLink(page).evaluate((link) => link.hidden);

for (const [description, state, visible] of [
  ['running P25 Phase 1 with full logging', { channels: [p25Trunked()],
    summaryActive: true, historyActive: true }, true],
  ['running P25 Phase 2 with full logging', { channels: [p25Trunked('p25-phase2')],
    summaryActive: true, historyActive: true }, true],
  ['stopped P25 system', { channels: [p25Trunked('p25-phase1', 'STOPPED')],
    summaryActive: true, historyActive: true }, false],
  ['P25 conventional channel', { channels: [{ ...p25Trunked(), channel_kind: 'CONVENTIONAL' }],
    summaryActive: true, historyActive: true }, false],
  ['running non-P25 system', { channels: [p25Trunked('dmr')],
    summaryActive: true, historyActive: true }, false],
  ['activity summaries off', { channels: [p25Trunked()],
    summaryActive: false, historyActive: true }, false],
  ['full activity history off', { channels: [p25Trunked()],
    summaryActive: true, historyActive: false }, false]
]) {
  test(`P25 Visualizer menu ${visible ? 'appears' : 'stays hidden'} for ${description}`, async ({ page }) => {
    const app = await openMenu(page, state);
    await expect.poll(() => menuHidden(page)).toBe(!visible);
    if (visible) expect(app.catalogRequests()).toBe(1);
  });
}

test('P25 Visualizer menu waits for a confirmed channel catalog', async ({ page }) => {
  const app = await openMenu(page, { channels: [p25Trunked()],
    summaryActive: true, historyActive: true }, { delayCatalog: true });
  await expect.poll(app.catalogRequests).toBe(1);
  expect(await menuHidden(page)).toBe(true);
  app.releaseCatalog();
  await expect.poll(() => menuHidden(page)).toBe(false);
});

test('P25 Visualizer menu follows refreshed channel and logging state', async ({ page }) => {
  await page.clock.install();
  const app = await openMenu(page, { channels: [p25Trunked()],
    summaryActive: true, historyActive: true });
  await expect.poll(() => menuHidden(page)).toBe(false);

  app.change({ channels: [p25Trunked('dmr')] });
  const firstCatalogCount = app.catalogRequests();
  await page.clock.runFor(10_100);
  await expect.poll(app.catalogRequests).toBeGreaterThan(firstCatalogCount);
  await expect.poll(() => menuHidden(page)).toBe(true);

  app.change({ channels: [p25Trunked()], historyActive: false });
  const firstStatusCount = app.statusRequests();
  await page.clock.runFor(10_100);
  await expect.poll(app.statusRequests).toBeGreaterThan(firstStatusCount);
  await expect.poll(() => menuHidden(page)).toBe(true);

  app.change({ historyActive: true });
  const secondCatalogCount = app.catalogRequests();
  await page.clock.runFor(10_100);
  await expect.poll(app.catalogRequests).toBeGreaterThan(secondCatalogCount);
  await expect.poll(() => menuHidden(page)).toBe(false);

  app.change({ catalogUnavailable: true });
  const thirdCatalogCount = app.catalogRequests();
  await page.clock.runFor(10_100);
  await expect.poll(app.catalogRequests).toBeGreaterThan(thirdCatalogCount);
  await expect.poll(() => menuHidden(page)).toBe(true);
});
