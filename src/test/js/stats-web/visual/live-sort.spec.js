'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

// Different LCN, frequency and activation orders make every selection observable.
const snapshot = {
  revision: 1,
  tables: [{ table_id: 'metro-north', configuration_id: 'metro-north', title: 'Metro North',
    system_name: 'Metro', site_name: 'North', channel_running: true,
    rows: [
      { key: 'lcn-30', configuration_id: 'metro-north', lcn: 30,
        frequency_hz: 851_012_500, status: 'ACTIVE', activation_order: 2 },
      { key: 'lcn-10', configuration_id: 'metro-north', lcn: 10,
        frequency_hz: 851_062_500, status: 'ACTIVE', activation_order: 3 },
      { key: 'lcn-20', configuration_id: 'metro-north', lcn: 20,
        frequency_hz: 851_037_500, status: 'ACTIVE', activation_order: 1 },
      { key: 'lcn-5', configuration_id: 'metro-north', lcn: 5,
        frequency_hz: 851_050_000, status: 'IDLE', activation_order: 0 }
    ] }]
};

async function openLive(page, theme = 'light') {
  let revision = 1;
  let preferences = structuredClone(defaultPreferences);
  preferences.appearance.theme = theme;
  const writes = [];
  const receiverWrites = [];
  await page.addInitScript((liveSnapshot) => {
    const originalFetch = window.fetch.bind(window);
    const encoder = new TextEncoder();
    const frame = (topic, event, data) => {
      const payload = encoder.encode(JSON.stringify({ event, data }));
      const bytes = new Uint8Array(16 + payload.length);
      const header = new DataView(bytes.buffer);
      header.setUint32(0, 0x534c4d58);
      header.setUint8(4, 2);
      header.setUint8(5, 1);
      header.setUint16(6, topic);
      header.setUint32(8, payload.length);
      bytes.set(payload, 16);
      return bytes;
    };
    window.fetch = (input, options) => {
      const url = new URL(typeof input === 'string' ? input : input.url, location.href);
      if (url.pathname === '/api/v1/live/multiplex/control') {
        const body = JSON.parse(options?.body || '{}');
        if (body.subscriptions?.channel_activity) window.fixtureSendLiveSnapshot?.();
        return Promise.resolve(new Response(JSON.stringify({ data: { accepted: true } }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      if (url.pathname !== '/api/v1/live/multiplex') return originalFetch(input, options);
      const stream = new ReadableStream({
        start(controller) {
          window.fixtureSendLiveSnapshot = () => controller.enqueue(frame(1, 'snapshot', liveSnapshot));
          controller.enqueue(frame(0, 'ready', { client_id: url.searchParams.get('client_id') }));
          window.fixtureSendLiveSnapshot();
        }
      });
      return Promise.resolve(new Response(stream, { status: 200 }));
    };
  }, snapshot);
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: { data: { configured: true, authenticated: true,
        username: 'operator', tier: 'admin', primary: true,
        capabilities: { live: true, radio: true, 'call-audio': true } } } });
    } else if (pathname === '/api/v1/me/preferences') {
      if (request.method() === 'PUT') {
        expect(request.headers()['if-match']).toBe(`"${revision}"`);
        preferences = request.postDataJSON();
        writes.push(structuredClone(preferences));
        revision += 1;
      }
      await route.fulfill({ json: { revision, preferences } });
    } else if (pathname === '/api/v1/live/multiplex/control') {
      await route.fulfill({ json: { data: { accepted: true } } });
    } else {
      if (request.method() !== 'GET') receiverWrites.push(pathname);
      await route.fulfill({ status: 404, json: { error: { message: 'Unavailable' } } });
    }
  });
  await page.goto('/app.html?view=live&channel=metro-north');
  return { writes, receiverWrites, preferences: () => preferences };
}

async function presentationDialog(page) {
  await page.getByRole('button', { name: 'Live presentation settings', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Live presentation', exact: true });
  await expect(dialog).toBeVisible();
  return dialog;
}

async function savePresentation(dialog) {
  await dialog.getByRole('button', { name: 'Save Live Presentation', exact: true }).click();
  await expect(dialog).toBeHidden();
}

async function expectCallOrder(page, keys) {
  const table = page.locator('.channels-live-table');
  await expect(table.locator('tbody tr[data-id]')).toHaveCount(keys.length);
  await expect.poll(() => table.locator('tbody tr[data-id]')
    .evaluateAll((rows) => rows.map((row) => row.dataset.id))).toEqual(keys);
  await expect(table.locator('thead th button')).toHaveCount(0);
}

test('Live presentation switches sort defaults and saves explicit sorting through rerenders', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const app = await openLive(page);
  await expectCallOrder(page, ['lcn-20', 'lcn-30', 'lcn-10']);
  let dialog = await presentationDialog(page);
  const sort = () => dialog.getByRole('combobox', { name: /^Sort calls by/ });
  const activeOnly = () => dialog.getByRole('checkbox', { name: 'Show only active trunked channels', exact: true });
  await expect(activeOnly()).toBeChecked();
  await expect(sort()).toHaveValue('order_appeared');
  await expect(sort().locator('option')).toHaveText(['LCN', 'Order appeared', 'Frequency']);

  await activeOnly().press('Space');
  await expect(activeOnly()).not.toBeChecked();
  await expect(sort()).toHaveValue('lcn');
  await savePresentation(dialog);
  await expectCallOrder(page, ['lcn-5', 'lcn-10', 'lcn-20', 'lcn-30']);
  expect(app.preferences().presentation.live_channel_sort).toBe('lcn');

  dialog = await presentationDialog(page);
  await sort().selectOption('order_appeared');
  await savePresentation(dialog);
  await expectCallOrder(page, ['lcn-20', 'lcn-30', 'lcn-10', 'lcn-5']);

  dialog = await presentationDialog(page);
  await sort().selectOption('frequency');
  await savePresentation(dialog);
  await expectCallOrder(page, ['lcn-30', 'lcn-20', 'lcn-5', 'lcn-10']);
  // The app canonicalizes its URL to /, which this harness reserves for its gallery.
  await page.goto('/app.html?view=live&channel=metro-north');
  await expectCallOrder(page, ['lcn-30', 'lcn-20', 'lcn-5', 'lcn-10']);
  dialog = await presentationDialog(page);
  await expect(sort()).toHaveValue('frequency');
  await expect(activeOnly()).not.toBeChecked();

  await activeOnly().press('Space');
  await expect(activeOnly()).toBeChecked();
  await expect(sort()).toHaveValue('order_appeared');
  await savePresentation(dialog);
  await expectCallOrder(page, ['lcn-20', 'lcn-30', 'lcn-10']);
  dialog = await presentationDialog(page);
  await sort().selectOption('frequency');
  await savePresentation(dialog);
  await expectCallOrder(page, ['lcn-30', 'lcn-20', 'lcn-10']);
  await page.goto('/app.html?view=live&channel=metro-north');
  await expectCallOrder(page, ['lcn-30', 'lcn-20', 'lcn-10']);
  dialog = await presentationDialog(page);
  await expect(sort()).toHaveValue('frequency');
  await expect(activeOnly()).toBeChecked();
  expect(app.writes.map((write) => write.presentation.live_channel_sort))
    .toEqual(['lcn', 'order_appeared', 'frequency', 'order_appeared', 'frequency']);
  expect(app.receiverWrites).toEqual([]);
});

async function expectReachable(control) {
  await control.scrollIntoViewIfNeeded();
  // Center fields in the shared scrolling dialog above its sticky save footer.
  await control.evaluate((element) => element.scrollIntoView({ block: 'center' }));
  await expect(control).toBeVisible();
  const geometry = await control.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    const hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
    return { reachable: element === hit || element.contains(hit),
      top: rect.top, bottom: rect.bottom, hitClass: hit?.className,
      hitText: hit?.textContent?.slice(0, 80) };
  });
  expect(geometry.reachable, JSON.stringify(geometry)).toBe(true);
}

for (const theme of ['light', 'dark']) {
  for (const viewport of [{ width: 1280, height: 900 }, { width: 320, height: 740 }]) {
    test(`Live sort control remains reachable in ${theme} at ${viewport.width}px`, async ({ page }) => {
      await page.setViewportSize(viewport);
      const app = await openLive(page, theme);
      const dialog = await presentationDialog(page);
      const sort = dialog.getByRole('combobox', { name: /^Sort calls by/ });
      await expectReachable(sort);
      await sort.focus();
      await expect(sort).toBeFocused();
      await expect(sort.locator('option')).toHaveText(['LCN', 'Order appeared', 'Frequency']);
      const screenshot = test.info().outputPath(`live-sort-${theme}-${viewport.width}.png`);
      await page.screenshot({ path: screenshot });
      await test.info().attach('Live presentation sort', { path: screenshot, contentType: 'image/png' });
      expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width);
      await expectReachable(dialog.getByRole('button', { name: 'Save Live Presentation', exact: true }));
      expect(app.receiverWrites).toEqual([]);
    });
  }
}
