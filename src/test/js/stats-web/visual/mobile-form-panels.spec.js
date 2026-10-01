const { expect, test } = require('@playwright/test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');

// Exercise the actual application and shared modal/table helpers with bounded,
// synthetic API responses. No destination configuration is written by these tests.
let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(resolve(__dirname,
    '../../../../..', 'stats-web/assets/core/preference-schema.js')).href)).defaults;
});
const sizes = [[320, 568], [320, 740], [390, 844], [430, 932], [844, 390]];
const streamNames = ['North', 'South'].map(direction =>
  `Regional Emergency Communications Streaming Archive ${direction} Dispatch`);

async function mockApplication(page, theme) {
  const preferences = structuredClone(defaults);
  preferences.appearance.theme = theme;
  const now = Date.parse('2026-10-01T12:00:00Z');
  await page.clock.setFixedTime(new Date(now));
  const list = { alias_list_id: 1, id: 1, name: 'County Public Safety', family: 'P25',
    alias_count: 1, assigned_channel_count: 0 };
  const aliases = [{ alias_id: 101, alias_list_id: 1, name: 'Fire Dispatch', group: 'Fire',
    matcher_type: 'TALKGROUP', identifier_display: 'TG 1201' }];
  const stream = { configuration_id: 'destination', name: 'County Calls', provider: 'BROADCASTIFY_CALL',
    provider_label: 'Broadcastify Calls', enabled: true, state: 'CONNECTED', state_label: 'Connected',
    queued: 0, sent: 128, aged_off: 0, errors: 0, attention: false };
  const assignments = Array.from({ length: 51 }, (_, i) => ({ id: i + 1, name: `Dispatch ${i + 1}`,
    identifier: String(1201 + i), alias_list_id: 1, alias_list_name: list.name, assigned: false }));
  const activity = Array.from({ length: 16 }, (_, i) => ({ id: i + 1, observed_at_ms: now - (16 - i) * 90000,
    action: i % 2 ? 'DENIAL' : 'EMERGENCY', protocol: 'P25', channel_kind: 'TRUNKED_SITE',
    radio_system_key: 'p25:00001:001', system_name: 'County Public Safety', wacn: 1, system_id: 1,
    source_radio_id: 30914 + i, source_native_id: 30914 + i,
    source_identity_key: `v1-r-00001-001-${30914 + i}`, source_alias_name: `Engine ${i + 1}`,
    target_id: 1201, target_native_id: 1201, target_identity_key: 'v1-g-00001-001-1201',
    target_kind: 'talkgroup', target_alias_name: 'Fire Dispatch' }));
  const writes = [];
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), p = url.pathname;
    if (request.method() !== 'GET') writes.push(p);
    let data;
    if (p === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } }); return;
    }
    if (p === '/api/v1/auth/session') data = { configured: true, authenticated: true,
      username: 'test-admin', tier: 'admin', primary: true, csrf_token: 'fixture', capabilities:
        Object.fromEntries(['web-access', 'radio', 'dashboard', 'live', 'call-audio', 'recordings',
          'user-settings', 'admin-aliases', 'admin-channels', 'admin-streaming', 'credits'].map(id => [id, true])) };
    else if (p === '/api/v1/status') data = { stats_logging: { summary_active: true,
      detailed_history_active: true }, database: { detailed_history_available: true } };
    else if (p === '/api/v1/channel-catalog' || p === '/api/v1/admin/channels') data = { revision: 1,
      channels: [{ configuration_id: '11111111-1111-4111-8111-111111111111', protocol_id: 'p25-phase1',
        channel_kind: 'TRUNKED', processing_state: 'RUNNING', alias_list_id: 1 }] };
    else if (p === '/api/v1/scan-lists') data = { revision: 1, scan_lists: [] };
    else if (p === '/api/v1/alias-lists') data = { rows: [list], limit: 500, offset: 0, has_more: false };
    else if (p === '/api/v1/admin/alias-lists') data = { revision: 1, alias_lists: [list] };
    else if (p === '/api/v1/aliases') data = { rows: aliases, total_count: 1, limit: 100, offset: 0,
      has_more: false, next_offset: null };
    else if (p === '/api/v1/admin/aliases/options') data = { revision: 1, alias_list: list,
      streams: streamNames.map((name, i) => ({ configuration_id: `stream-${i}`, name })) };
    else if (p === '/api/v1/admin/streaming') data = { revision: '1:1:1', destinations: [stream] };
    else if (p === '/api/v1/admin/streaming/options') data = { revision: '1:1:1', sites: [], providers: [
      { id: 'BROADCASTIFY_CALL', label: 'Broadcastify Calls', fields: [
        { key: 'name', label: 'Name', type: 'text', maximum: 1024 },
        { key: 'enabled', label: 'Enabled', type: 'boolean' }] }] };
    else if (p === '/api/v1/admin/streaming/destination') data = { revision: '1:1:1', status: stream,
      destination: { configuration_id: 'destination', provider: 'BROADCASTIFY_CALL',
        settings: { name: stream.name, enabled: true }, configured_credentials: [] }, references: { aliases: 0 } };
    else if (p === '/api/v1/admin/streaming/destination/aliases') {
      const offset = Number(url.searchParams.get('offset')) || 0;
      data = { revision: '1:1:1', total: 51, limit: 50, items: assignments.slice(offset, offset + 50) };
    } else if (p === '/api/v1/activity') data = {
      rows: Number(url.searchParams.get('after_id')) > 0 ? [] : activity,
      watermark_id: 16, next_after_id: 16, has_more: false };
    await route.fulfill({ status: data ? 200 : 404, json: data ? { data } : { error: { message: 'Fixture unavailable' } } });
  });
  return writes;
}

async function dockSize(page, state) {
  const handle = page.getByRole('button', { name: 'Change audio player size', exact: true });
  await handle.focus();
  await handle.press('Home');
  if (state === 'minimal') await handle.press('ArrowUp');
  await expect(page.locator('#audio-dock')).toHaveAttribute('data-state', state);
}

async function assertHit(control) {
  await expect(control).toBeVisible();
  const geometry = await control.evaluate(element => {
    const r = element.getBoundingClientRect();
    const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    return { reachable: element === hit || element.contains(hit), name: element.textContent || element.getAttribute('aria-label'),
      top: r.top, bottom: r.bottom, hitClass: hit?.className, hitText: hit?.textContent?.slice(0, 100) };
  });
  expect(geometry.reachable, JSON.stringify(geometry)).toBe(true);
}

for (const theme of ['light', 'dark']) for (const [width, height] of sizes) {
  test(`mobile form panels ${theme} ${width}x${height}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    const writes = await mockApplication(page, theme);
    await page.goto('/app.html?view=aliases&list=1&aliasTab=configure');
    await page.getByRole('checkbox', { name: 'Select Fire Dispatch', exact: true }).check();
    await page.locator('.alias-bulk-bar').getByRole('button', { name: 'Stream', exact: true }).click();
    const modal = page.getByRole('dialog');
    await modal.getByRole('combobox', { name: 'Stream change', exact: true }).selectOption('add');
    const labels = modal.locator('.alias-bulk-streams .alias-check-option span');
    await expect(labels).toHaveText(streamNames);
    expect(await labels.evaluateAll(elements => elements.every(element => {
      const text = document.createRange(); text.selectNodeContents(element);
      const r = element.getBoundingClientRect(), rects = [...text.getClientRects()];
      return element.scrollWidth <= element.clientWidth + 1 && rects.every(line =>
        line.left >= r.left - 1 && line.right <= r.right + 1 && line.bottom <= r.bottom + 1);
    }))).toBe(true);
    await modal.getByRole('checkbox', { name: streamNames[1], exact: true }).check();
    await expect(modal.getByRole('checkbox', { name: streamNames[0], exact: true })).not.toBeChecked();
    await modal.locator('.modal-content').evaluate(element => { element.scrollTop = element.scrollHeight; });
    await test.info().attach('bulk-stream-choices', { body: await page.screenshot(), contentType: 'image/png' });
    if (height === 568 || width === 844) await expect(modal).toHaveScreenshot(
      `bulk-stream-choices-${theme}-${width}x${height}.png`);
    await page.keyboard.press('Escape');

    await page.goto('/app.html?view=streaming');
    await page.getByRole('button', { name: 'County Calls', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Aliases', exact: true }).click();
    const next = page.getByRole('dialog').getByRole('button', { name: 'Next', exact: true });
    await expect(next).toBeEnabled();
    const scrolling = page.getByRole('dialog').locator('.modal-content');
    await scrolling.evaluate(element => { element.scrollTop = 1000; });
    const stickyGeometry = await page.getByRole('dialog').locator('.streaming-alias-actions').evaluate(element => {
      const r = element.getBoundingClientRect(), scroll = element.closest('.modal-content').getBoundingClientRect();
      return { top: r.top, scrollTop: scroll.top, inset: parseFloat(getComputedStyle(element.closest('.modal-content')).paddingTop),
        position: getComputedStyle(element).position, cssTop: getComputedStyle(element).top };
    });
    expect(stickyGeometry.top, JSON.stringify(stickyGeometry)).toBeCloseTo(stickyGeometry.scrollTop + stickyGeometry.inset, 0);
    await scrolling.evaluate(element => { element.scrollTop = element.scrollHeight; });
    // On the shortest screens the fixed footer occupies much of the final table
    // viewport. A normal upward scroll must expose the last alias below the toolbar.
    if (height <= 640) {
      await scrolling.hover();
      await page.mouse.wheel(0, -100);
      await expect.poll(() => scrolling.evaluate(element => element.scrollTop)).toBeLessThan(
        await scrolling.evaluate(element => element.scrollHeight - element.clientHeight));
    }
    await assertHit(page.getByRole('dialog').getByRole('link', { name: 'Dispatch 50', exact: true }));
    await scrolling.evaluate(element => { element.scrollTop = element.scrollHeight; });
    await assertHit(next);
    await assertHit(page.getByRole('dialog').getByRole('button', { name: 'Cancel', exact: true }));
    await test.info().attach('streaming-aliases-pagination', { body: await page.screenshot(), contentType: 'image/png' });
    await next.click();
    await expect(page.getByRole('dialog').getByRole('link', { name: 'Dispatch 51', exact: true })).toBeVisible();
    await assertHit(page.getByRole('dialog').getByRole('button', { name: 'Previous', exact: true }));
    await page.keyboard.press('Escape');

    await page.goto('/app.html?view=network-visualizer');
    await expect(page.locator('.network-visualizer-status')).toHaveText('Saved history');
    for (const state of ['collapsed', 'minimal']) {
      await dockSize(page, state);
      const toggle = page.getByRole('button', { name: 'Events', exact: true });
      await toggle.click();
      const events = page.locator('.network-visualizer-events');
      const list = events.locator('.network-visualizer-event-list');
      await expect(list.locator('li')).toHaveCount(16);
      expect(await list.evaluate(element => element.clientHeight)).toBeGreaterThanOrEqual(
        await list.locator('li').first().evaluate(element => element.getBoundingClientRect().height));
      await list.evaluate(element => { element.scrollTop = element.scrollHeight; });
      const last = list.locator('li').last();
      expect(await last.evaluate(element => {
        const r = element.getBoundingClientRect(), p = element.parentElement.getBoundingClientRect();
        return r.top >= p.top - 1 && r.bottom <= p.bottom + 1;
      })).toBe(true);
      const close = height <= 640 ? page.getByRole('dialog').getByRole('button',
        { name: 'Close Noteworthy P25 activity', exact: true }) :
        page.getByRole('button', { name: 'Close noteworthy activity', exact: true });
      await assertHit(close);
      if (state === 'minimal') await test.info().attach('p25-events', {
        body: await page.screenshot(), contentType: 'image/png' });
      if (state === 'minimal' && (height === 568 || width === 844)) await expect(page.getByRole('dialog'))
        .toHaveScreenshot(`p25-events-sheet-${theme}-${width}x${height}.png`);
      await close.click();
      await expect(toggle).toBeFocused();
      await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    }
    expect(writes.filter(path => path !== '/api/v1/me/preferences')).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
  });
}

test('P25 Events retains its single list through a short-screen orientation change and route teardown', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockApplication(page, 'light');
  await page.goto('/app.html?view=network-visualizer');
  await expect(page.locator('.network-visualizer-status')).toHaveText('Saved history');
  await dockSize(page, 'collapsed');
  const toggle = page.getByRole('button', { name: 'Events', exact: true });
  await toggle.click();
  await page.locator('.network-visualizer-event-list').focus();
  await page.evaluate(() => { window.originalP25EventsList = document.querySelector('.network-visualizer-event-list'); });
  await page.setViewportSize({ width: 844, height: 390 });
  await expect(page.getByRole('dialog', { name: 'Noteworthy P25 activity', exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.originalP25EventsList === document.querySelector('.network-visualizer-event-list'))).toBe(true);
  await page.keyboard.press('Escape');
  await expect(toggle).toBeFocused();
  await expect(page.locator('.network-visualizer-stage > .network-visualizer-events')).toHaveCount(1);
  await page.setViewportSize({ width: 390, height: 844 });
  await dockSize(page, 'minimal');
  await toggle.click();
  // This height is above the compact media query. The actual remaining graph
  // stage must still trigger the sheet when a complete event would not fit.
  await page.setViewportSize({ width: 320, height: 675 });
  await expect(page.getByRole('dialog', { name: 'Noteworthy P25 activity', exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
  await toggle.click();
  await page.evaluate(() => {
    history.pushState({}, '', '/app.html?view=credits');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
  await expect(page.locator('body')).toHaveAttribute('data-view', 'credits');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.setViewportSize({ width: 844, height: 390 });
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('resizing open P25 Events never replaces a different modal with unsaved choices', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockApplication(page, 'light');
  await page.goto('/app.html?view=network-visualizer');
  await expect(page.locator('.network-visualizer-status')).toHaveText('Saved history');
  await dockSize(page, 'collapsed');
  await page.getByRole('button', { name: 'Events', exact: true }).click();
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  const settings = page.getByRole('dialog', { name: 'P25 Visualizer settings', exact: true });
  const choice = settings.getByRole('checkbox').first();
  const initial = await choice.isChecked();
  await choice.focus();
  await choice.press('Space');
  const confirmations = [];
  page.on('dialog', dialog => { confirmations.push(dialog.message()); void dialog.dismiss(); });
  await page.setViewportSize({ width: 844, height: 390 });
  await expect(settings).toBeVisible();
  expect(await choice.isChecked()).toBe(!initial);
  expect(confirmations).toEqual([]);
  await expect(page.getByRole('dialog', { name: 'Noteworthy P25 activity', exact: true })).toHaveCount(0);
});

test('short-screen P25 Events uses the shared modal inside the browser fullscreen layout', async ({ page }) => {
  await page.setViewportSize({ width: 844, height: 390 });
  await mockApplication(page, 'dark');
  await page.goto('/app.html?view=network-visualizer');
  await expect(page.locator('.network-visualizer-status')).toHaveText('Saved history');
  await page.getByRole('button', { name: 'Enter fullscreen', exact: true }).click();
  await expect.poll(() => page.evaluate(() => document.fullscreenElement?.className)).toBe('network-visualizer-layout');
  const toggle = page.getByRole('button', { name: 'Events', exact: true });
  await toggle.click();
  const modal = page.getByRole('dialog', { name: 'Noteworthy P25 activity', exact: true });
  await expect(modal).toBeVisible();
  expect(await modal.evaluate(element => document.fullscreenElement.contains(element))).toBe(true);
  const list = modal.locator('.network-visualizer-event-list');
  await expect(list.locator('li')).toHaveCount(16);
  expect(await list.evaluate(element => element.clientHeight)).toBeGreaterThan(70);
  const close = modal.getByRole('button', { name: 'Close Noteworthy P25 activity', exact: true });
  await assertHit(close);
  await close.click();
  await expect(modal).toHaveCount(0);
  await expect(toggle).toBeFocused();
  await expect(toggle).toHaveAttribute('aria-pressed', 'false');
  await expect(page.locator('.network-visualizer-stage > .network-visualizer-events')).toHaveCount(1);
  await page.evaluate(() => document.exitFullscreen());
});
