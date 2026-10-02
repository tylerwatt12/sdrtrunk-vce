'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

const remoteOrigin = { remote: true };
const snapshot = {
  revision: 1,
  tables: [
    { table_id: 'remote-site', configuration_id: 'remote-site', title: 'Metro North',
      system_name: 'Metro', site_name: 'North', channel_running: true, remote_origin: remoteOrigin,
      rows: [
        { key: 'remote-lcn-1', configuration_id: 'remote-site', lcn: 1,
          frequency_hz: 851_012_500, status: 'IDLE', tags: ['CURRENT_CONTROL'],
          activation_order: 1, remote_origin: remoteOrigin },
        { key: 'remote-lcn-2', configuration_id: 'remote-site', lcn: 2,
          frequency_hz: 851_037_500, status: 'IDLE', tags: ['ALTERNATE_CONTROL'],
          activation_order: 2, remote_origin: remoteOrigin }
      ] },
    { table_id: 'conventional', title: 'Conventional', channel_running: true,
      rows: [
        { key: 'mixed-remote', configuration_id: 'remote-conventional', channel_name: 'Remote conventional',
          frequency_hz: 155_010_000, status: 'IDLE', tags: ['CONVENTIONAL'], remote_origin: remoteOrigin },
        { key: 'mixed-local', configuration_id: 'local-conventional', channel_name: 'Local conventional',
          frequency_hz: 155_020_000, status: 'IDLE', tags: ['CONVENTIONAL'] }
      ] }
  ]
};

async function openLive(page, showOnlyActiveTrunkedChannels = false) {
  let diagnosticSubscriptions = 0;
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
      if (url.pathname !== '/api/v1/live/multiplex') return originalFetch(input, options);
      const stream = new ReadableStream({
        start(controller) {
          controller.enqueue(frame(0, 'ready', { client_id: url.searchParams.get('client_id') }));
          controller.enqueue(frame(1, 'snapshot', liveSnapshot));
        }
      });
      return Promise.resolve(new Response(stream, { status: 200 }));
    };
  }, snapshot);
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: { data: { configured: true, authenticated: true,
        username: 'operator', tier: 'admin', primary: true,
        capabilities: { live: true, radio: true, 'call-audio': true } } } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences: {
        ...defaultPreferences,
        presentation: { ...defaultPreferences.presentation,
          show_only_active_trunked_channels: showOnlyActiveTrunkedChannels }
      } } });
    } else if (pathname === '/api/v1/live/multiplex/control') {
      const body = route.request().postDataJSON();
      if (body?.subscriptions?.channel_diagnostics) diagnosticSubscriptions += 1;
      await route.fulfill({ json: { data: { accepted: true } } });
    } else {
      await route.fulfill({ status: 404, json: { error: { message: 'Unavailable' } } });
    }
  });
  await page.goto('/app.html?view=live&channel=remote-site');
  return { diagnosticSubscriptions: () => diagnosticSubscriptions };
}

async function panDiagnosticStatusIntoView(page, overlay) {
  await expect.poll(() => overlay.evaluate((element) => {
    const range = document.createRange();
    range.selectNodeContents(element);
    const text = range.getBoundingClientRect();
    const plot = element.parentElement.getBoundingClientRect();
    return text.top >= plot.top && text.bottom <= plot.bottom &&
      text.left >= plot.left && text.right <= plot.right;
  })).toBe(true);
  const grid = page.locator('.channel-diagnostic-grid');
  await grid.scrollIntoViewIfNeeded();
  const delta = await overlay.evaluate((element) => {
    const range = document.createRange();
    range.selectNodeContents(element);
    const text = range.getBoundingClientRect();
    const grid = element.closest('.channel-diagnostic-grid').getBoundingClientRect();
    const dock = document.querySelector('#audio-dock').getBoundingClientRect();
    const coveredHorizontally = text.right > dock.left && text.left < dock.right;
    const bottom = coveredHorizontally ? Math.min(grid.bottom, dock.top) : grid.bottom;
    return (text.top + text.bottom - grid.top - bottom) / 2;
  });
  await grid.hover({ position: { x: 5, y: 5 } });
  await page.mouse.wheel(0, delta);
  await expect.poll(() => overlay.evaluate((element) => {
    const range = document.createRange();
    range.selectNodeContents(element);
    const text = range.getBoundingClientRect();
    const grid = element.closest('.channel-diagnostic-grid').getBoundingClientRect();
    const plot = element.parentElement;
    const clear = [text.left + 1, text.right - 1].every((x) =>
      [text.top + 1, text.bottom - 1].every((y) => plot.contains(document.elementFromPoint(x, y))));
    return { visible: text.top >= grid.top && text.bottom <= grid.bottom && clear,
      textTop: text.top, textBottom: text.bottom, gridTop: grid.top, gridBottom: grid.bottom,
      dockTop: document.querySelector('#audio-dock').getBoundingClientRect().top, clear };
  })).toMatchObject({ visible: true });
}

test('new Live presentation preferences start with active trunked channels only', async ({ page }) => {
  expect(defaultPreferences.presentation.show_only_active_trunked_channels).toBe(true);
  await openLive(page, defaultPreferences.presentation.show_only_active_trunked_channels);
  await page.getByRole('button', { name: 'Live presentation settings' }).click();
  const dialog = page.getByRole('dialog', { name: 'Live presentation' });
  await expect(dialog.getByRole('checkbox', { name: 'Show only active trunked channels' })).toBeChecked();
});

for (const viewport of [{ width: 1280, height: 900 }, { width: 1024, height: 550 }]) {
  test(`desktop Live panes keep their usable height in every dock size at ${viewport.width}x${viewport.height}`,
    async ({ page }) => {
      await page.setViewportSize(viewport);
      await openLive(page);
      await expect(page.locator('.live-details')).toBeVisible();
      const dock = page.locator('#audio-dock');
      const handle = dock.getByRole('button', { name: 'Change audio player size', exact: true });
      const geometry = () => page.evaluate(() => Object.fromEntries(
        ['.content', '.live-split', '.live-right-workspace', '.live-channels-section', '.live-details'].map((selector) => {
          const element = document.querySelector(selector);
          return [selector, { height: element.getBoundingClientRect().height,
            scrollHeight: element.scrollHeight, paddingBottom: getComputedStyle(element).paddingBottom }];
        })));
      await handle.press('Home');
      await expect(dock).toHaveAttribute('data-state', 'collapsed');
      const baseline = await geometry();
      for (const [key, state] of [['ArrowUp', 'minimal'], ['End', 'full'], ['Home', 'collapsed']]) {
        await handle.press(key);
        await expect(dock).toHaveAttribute('data-state', state);
        await expect.poll(geometry).toEqual(baseline);
      }
    });
}

test('dedicated remote Live detail has one origin cue; mixed rows retain theirs', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const link = await openLive(page);
  const selected = page.locator('.live-channels-section');
  await expect(selected.locator('.live-selected-view-title')).toContainText('Metro North');
  await expect(selected.locator('.live-selected-view-title .live-remote-origin')).toHaveText('Remote');
  await expect(selected.locator('.live-selected-view-meta')).not.toContainText('Remote source');
  await expect(selected.locator('td[data-column="channel"]')).toHaveCount(2);
  await expect(selected.locator('td[data-column="channel"] .live-remote-origin')).toHaveCount(0);
  await expect(selected).toHaveScreenshot('live-selected-remote-detail-light.png');

  // The floating desktop dock can be reduced to inspect the page beneath it.
  await page.getByRole('button', { name: 'Change audio player size', exact: true }).press('Home');
  await selected.locator('tr[data-id="remote-lcn-1"]').click();
  await page.getByRole('tab', { name: 'Channel', exact: true }).click();
  const diagnostics = page.locator('.live-channel-pane');
  await expect(diagnostics.locator('.channel-diagnostic-card').first().locator('.channel-diagnostic-overlay'))
    .toHaveText('Signal is not available for remote linked VCE data');
  await expect(diagnostics.locator('.channel-diagnostic-card').last().locator('.channel-diagnostic-overlay'))
    .toHaveText('Symbols are not available for remote linked VCE data');
  await expect(diagnostics.locator('.channel-diagnostic-view-toggle')).toBeHidden();
  expect(link.diagnosticSubscriptions()).toBe(0);
  for (const overlay of await diagnostics.locator('.channel-diagnostic-overlay').all()) await panDiagnosticStatusIntoView(page, overlay);
  await expect(diagnostics).toHaveScreenshot('live-remote-diagnostics-light.png');

  await page.getByRole('tab', { name: /Show live channels for Conventional/ }).click();
  await expect(selected.locator('td[data-column="channel"]')).toHaveCount(2);
  await expect(selected.locator('td[data-column="channel"] .live-remote-origin')).toHaveCount(1);
  await selected.locator('tr[data-id="mixed-local"]').click();
  await expect(diagnostics.locator('.channel-diagnostic-view-toggle')).toBeVisible();
  await expect.poll(() => link.diagnosticSubscriptions()).toBeGreaterThan(0);
});

for (const viewport of [
  { width: 1280, height: 900 },
  { width: 1280, height: 600 },
  { width: 320, height: 740 },
  { width: 390, height: 844 },
  { width: 430, height: 932 },
  { width: 844, height: 390 },
]) {
  test(`remote diagnostic status remains reachable at ${viewport.width}x${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openLive(page);
    if (viewport.width > 900) {
      await page.getByRole('button', { name: 'Change audio player size', exact: true }).press('Home');
      if (viewport.height <= 600) {
        // A short desktop keeps its existing pane resizer above the floating dock.
        await page.getByRole('separator', { name: 'Resize live channels and details panels' }).press('End');
      }
    }
    await page.locator('tr[data-id="remote-lcn-1"]').click();
    const channelTab = page.getByRole('tab', { name: 'Channel', exact: true });
    await channelTab.scrollIntoViewIfNeeded();
    await channelTab.click();
    const statuses = page.locator('.channel-diagnostic-overlay');
    await expect(statuses).toHaveCount(2);
    for (const overlay of await statuses.all()) await panDiagnosticStatusIntoView(page, overlay);
  });
}
