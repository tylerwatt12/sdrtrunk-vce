'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

function decision(id = 1, extra = {}) {
  return { decision_id: id, sequence: id, timestamp: 1_700_000_004_000 + id,
    site_id: 'cleveland', site_name: 'Cleveland Simulcast', tuner_id: 'safe-tuner-1', tuner_name: 'RTL-SDR 1',
    type: 'move', outcome: 'moved', reason: 'An unused tuner could cover more known frequencies',
    before_frequency_hz: 854_300_000, requested_center_frequency_hz: 852_750_000,
    after_frequency_hz: 852_750_000, usable_bandwidth_hz: 2_160_000, dc_gap_hz: 25_000,
    requested_frequency_hz: null, covered_before: 27, covered_after: 30, repeat_count: 1,
    first_timestamp: 1_700_000_004_000 + id, last_timestamp: 1_700_000_004_000 + id, candidates: [], ...extra };
}

function snapshot(center = 852_750_000, decisions = [decision()]) {
  return { available: true, session_id: 'receiver-session', cursor: 3, generated_at_ms: Date.now(),
    sites: [{ id: 'cleveland', name: 'Cleveland Simulcast', system_name: 'GCRCN', channel_name: 'Cleveland',
      kind: 'trunked', known_frequency_count: 30, covered_frequency_count: center === 852_750_000 ? 30 : 27,
      frequencies: [{ frequency_hz: 852_012_500, bandwidth_hz: 12_500, covered: true, role: 'call' }],
      tuners: ['safe-tuner-1', 'safe-tuner-2'], status: 'control_and_calls' }],
    tuners: [{ id: 'safe-tuner-1', name: 'RTL-SDR 1', frequency_hz: center,
      usable_bandwidth_hz: 2_160_000, dc_gap_hz: 25_000, status: 'enabled', available: true,
      center_locked: false, min_frequency_hz: 851_000_000, max_frequency_hz: 860_000_000,
      preferred_for: ['Cleveland'], receiving: [], reason: 'An unused tuner could cover more known frequencies' },
    { id: 'safe-tuner-2', name: 'RTL-SDR 2', frequency_hz: 856_187_500,
      usable_bandwidth_hz: 2_160_000, dc_gap_hz: 25_000, status: 'enabled', available: true,
      center_locked: true, min_frequency_hz: 851_000_000, max_frequency_hz: 860_000_000,
      preferred_for: [], receiving: [{ frequency_hz: 856_162_500, role: 'control', site_id: 'cleveland', channel_name: 'Cleveland' },
        { frequency_hz: 855_737_500, role: 'call', site_id: 'cleveland', channel_name: 'Cleveland' }], reason: null }], decisions };
}

async function openApp(page, options = {}) {
  let requests = 0;
  let response = { data: snapshot() };
  let preferences = JSON.parse(JSON.stringify(defaults));
  preferences.appearance.theme = options.theme || 'light';
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === '/api/v1/auth/session') return route.fulfill({ json: { data: {
      configured: true, authenticated: true, username: 'admin', tier: 'admin', primary: true,
      capabilities: { 'admin-settings': true, credits: true } } } });
    if (pathname === '/api/v1/me/preferences') {
      if (route.request().method() === 'PUT') preferences = route.request().postDataJSON();
      return route.fulfill({ json: { revision: 1, preferences } });
    }
    if (pathname === '/api/v1/admin/tuner-allocation') {
      requests++;
      return route.fulfill({ status: response.status || 200,
        json: response.status && response.status !== 200 ? { error: { status: response.status, message: 'Unavailable' } } : { data: response.data } });
    }
    return route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
  });
  await page.goto('/app.html?view=admin&tab=tuner-allocation');
  await expect(page.locator('.tuner-allocation-live-status')).toContainText('Live');
  return { requests: () => requests, respond: (next) => { response = next; } };
}

test('decision navigation and live windows follow retunes, pause, and resume', async ({ page }) => {
  const app = await openApp(page);
  const group = page.locator('.admin-settings-branch').filter({ hasText: 'Decisions engine' });
  await expect(group.getByRole('link', { name: 'Call matching', exact: true })).toBeVisible();
  await expect(group.getByRole('link', { name: 'Tuner allocation', exact: true })).toHaveAttribute('aria-current', 'page');
  const support = page.locator('.admin-settings-branch').filter({ hasText: 'Status & support' });
  await expect(support.getByRole('link', { name: 'Call matching', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Details for Cleveland Simulcast' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('30 of 30 known frequencies');
  await expect(dialog.locator('.chart-window-center title').first()).toHaveText('Center 852.75000 MHz');
  const known = dialog.getByText('Known frequencies', { exact: true });
  await known.click();
  await known.focus();
  app.respond({ data: snapshot(854_300_000) });
  await expect(dialog.locator('.chart-window-center title').first()).toHaveText('Center 854.30000 MHz');
  await expect(dialog.locator('details[data-allocation-disclosure="known"]')).toHaveAttribute('open', '');
  await expect(dialog.getByText('Known frequencies', { exact: true })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: 'Details for Cleveland Simulcast' })).toBeFocused();
  await page.getByRole('button', { name: 'Pause tuner allocation monitor' }).click();
  const count = app.requests();
  app.respond({ data: snapshot() });
  await page.waitForTimeout(1150);
  expect(app.requests()).toBe(count);
  await page.getByRole('button', { name: 'Details for Cleveland Simulcast' }).click();
  await expect(page.getByRole('dialog')).toContainText('Receiving windows · paused');
  await expect(page.getByRole('dialog').locator('.chart-window-center title').first()).toHaveText('Center 854.30000 MHz');
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Resume tuner allocation monitor' }).click();
  await expect(page.locator('table[data-table-type="tuner-allocation-sites"]')).toContainText('30 / 30 known');
});

test('history keeps grouped decisions and their captured windows while current positions change', async ({ page }) => {
  const app = await openApp(page);
  const failed = decision(2, { outcome: 'allocation_failed', type: 'failure', repeat_count: 12,
    reason: "The frequency is outside the tuners' allowed range", requested_frequency_hz: 857_512_500,
    before_frequency_hz: null, after_frequency_hz: null, candidates: [{ tuner_name: 'RTL-SDR 1', reason: 'The center frequency is locked' }] });
  app.respond({ data: snapshot(852_750_000, [decision(), failed]) });
  await page.getByRole('button', { name: 'Decision history', exact: true }).click();
  const table = page.locator('table[data-table-type="tuner-allocation-decisions"]');
  await expect(table).toContainText('12 requests');
  await expect(table).not.toContainText('Call received');
  await page.getByRole('button', { name: 'Details for tuner decision 1' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('Receiving windows at this decision');
  await expect(dialog.locator('.chart-window-center title')).toHaveText(['Center 854.30000 MHz', 'Center 852.75000 MHz']);
  app.respond({ data: snapshot(853_500_000, [decision(1, { after_frequency_hz: 853_500_000 }), failed]) });
  await page.waitForTimeout(1150);
  await expect(dialog.locator('.chart-window-center title')).toHaveText(['Center 854.30000 MHz', 'Center 852.75000 MHz']);
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Details for tuner decision 2' }).click();
  await expect(page.getByRole('dialog')).toContainText('Requests12');
  await page.getByRole('dialog').getByText('Other tuners', { exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('The center frequency is locked');
  await page.keyboard.press('Escape');
  app.respond({ data: snapshot(852_750_000, Array.from({ length: 45 }, (_, index) => decision(index + 1))) });
  await expect(table.locator('tbody tr')).toHaveCount(20);
  await page.locator('.tuner-allocation-history-pager').getByRole('button', { name: 'Next' }).click();
  await expect(page.locator('.tuner-allocation-history-pager')).toContainText('Page 2 of 3');
});

for (const theme of ['light', 'dark']) {
  test(`coverage and decision drilldowns fit desktop and phone in ${theme}`, async ({ page }, testInfo) => {
    await openApp(page, { theme });
    for (const width of [1280, 390, 320]) {
      await page.setViewportSize({ width, height: 900 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
      expect(await page.locator('table[data-table-type="tuner-allocation-sites"] .ui-status').evaluate((element) =>
        element.scrollWidth <= element.clientWidth)).toBe(true);
      await page.getByRole('button', { name: 'Details for Cleveland Simulcast' }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog).toBeVisible();
      const rect = await dialog.boundingBox();
      expect(rect.x).toBeGreaterThanOrEqual(0);
      expect(rect.x + rect.width).toBeLessThanOrEqual(width);
      await expect(dialog.locator('.chart-window-control')).toHaveCount(1);
      await expect(dialog.locator('.chart-window-call')).toHaveCount(1);
      if (width === 1280 || width === 320) await page.screenshot({ path: testInfo.outputPath(`tuner-windows-${theme}-${width}.png`) });
      await page.keyboard.press('Escape');
      await expect(page.getByRole('dialog')).toHaveCount(0);
      if (width === 1280 || width === 320) await page.screenshot({ path: testInfo.outputPath(`tuner-coverage-${theme}-${width}.png`), fullPage: true });
    }
    await page.getByRole('button', { name: 'Decision history', exact: true }).click();
    await page.getByRole('button', { name: 'Details for tuner decision 1' }).click();
    await expect(page.getByRole('dialog')).toContainText('Before · 854.30000 MHz');
    await expect(page.getByRole('dialog')).not.toContainText('Previous center');
    await page.screenshot({ path: testInfo.outputPath(`tuner-decision-${theme}-320.png`) });
    await page.keyboard.press('Escape');
    await page.goto(`/design-system.html?view=receiving-windows&theme=${theme}`);
    await expect(page.locator('.visual-frequency-window-example')).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath(`receiving-window-gallery-${theme}-320.png`), fullPage: true });
  });
}

test('separate radio bands retain readable current receiving windows', async ({ page }) => {
  const app = await openApp(page);
  const data = snapshot();
  data.tuners.push({ ...data.tuners[0], id: 'safe-tuner-3', name: 'RTL-SDR 3', frequency_hz: 773_987_500, min_frequency_hz: 760_000_000, max_frequency_hz: 800_000_000, supports_shared_window: false,
    receiving: [{ frequency_hz: 773_987_500, bandwidth_hz: 12_500, role: 'control', site_id: 'cleveland' }] });
  data.sites[0].tuners.push('safe-tuner-3');
  data.sites[0].frequencies.push({ frequency_hz: 773_987_500, bandwidth_hz: 12_500, covered: true, role: 'control' });
  app.respond({ data });
  await expect(page.locator('table[data-table-type="tuner-allocation-sites"] tbody tr')).toHaveCount(1);
  await expect.poll(app.requests).toBeGreaterThan(1);
  await page.getByRole('button', { name: 'Details for Cleveland Simulcast' }).click();
  await expect(page.getByRole('dialog').locator('.chart-frequency-windows')).toHaveCount(2);
  await expect(page.getByRole('dialog').locator('.chart-window-band')).toHaveCount(3);
});

test('access loss clears tuner status, history, and open details', async ({ page }) => {
  const app = await openApp(page);
  await page.getByRole('button', { name: 'Details for Cleveland Simulcast' }).click();
  app.respond({ status: 403 });
  await expect(page.locator('.tuner-allocation-live-status')).toContainText('Access denied');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.locator('table[data-table-type="tuner-allocation-sites"] tbody tr')).toHaveCount(1);
  await expect(page.locator('table[data-table-type="tuner-allocation-sites"] tbody td.empty')).toBeVisible();
});
