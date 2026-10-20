'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, channelId, groupKey, radioKey, systemName } =
  require('./fixtures/site-style-app.cjs');

const now = Date.parse('2026-10-01T12:00:00Z');
const correctedRadio = 'v1-r-00001-001-30915';
const activity = (alias = 'Engine 4', overrides = {}) => ({ id: 1, observed_at_ms: now - 1_000, action: 'JOIN',
  protocol: 'p25', channel_kind: 'trunked_site', radio_system_key: systemKey, system_name: systemName,
  configuration_id: channelId, source_identity_key: radioKey, source_radio_id: 30914,
  source_alias_name: alias, target_identity_key: groupKey, target_kind: 'talkgroup',
  target_id: 1201, target_alias_name: 'Fire Dispatch', ...overrides });
const snapshot = (row) => ({ data: [row], meta: { next_after_id: 1, watermark_id: 1,
  has_more: false, traffic_grant_age_out_milliseconds: 1_200 } });
const send = (page, sequence, rows, removed = []) => page.evaluate(({ sequence, rows, removed }) =>
  window.fixtureSendLiveFrame(7, 'activity_delta', { source_epoch: 'fixture-p25',
    base_sequence: sequence - 1, sequence, rows, removed_ids: removed, part: 0, parts: 1 }), { sequence, rows, removed });

test('live saved activity replaces the same ID and follows new events without Refresh', async ({ page }, testInfo) => {
  const fixture = await installSiteStyleApplication(page);
  let historyRequests = 0;
  await page.route('**/api/v1/activity?**', (route) => {
    historyRequests++;
    return route.fulfill({ json: snapshot(activity()) });
  });
  await page.goto('/app.html?view=network-visualizer');
  const radio = page.locator('.network-visualizer-label[data-type="radio"]:not([hidden])');
  await expect(radio).toHaveText('Engine 4');
  await expect(page.locator('.network-visualizer-status')).toHaveText(/^Live · Updated /);
  await send(page, 1, [activity('Corrected Engine', { source_identity_key: correctedRadio, source_radio_id: 30915 })]);
  await expect(radio).toHaveText('Corrected Engine');
  await expect(radio).toHaveCount(1);
  const emergency = activity('Corrected Engine', { id: 2, observed_at_ms: now, action: 'EMERGENCY',
    source_identity_key: correctedRadio, source_radio_id: 30915 });
  await send(page, 2, [emergency]);
  await expect(page.locator('.network-visualizer-auto-focus')).toBeVisible();
  await expect(page.locator('.network-visualizer-auto-focus-title')).toContainText(/emergency/i);
  await page.getByRole('button', { name: 'Events', exact: true }).click();
  await expect(page.locator('.network-visualizer-event-list .network-visualizer-event')).toHaveCount(1);
  await send(page, 2, [emergency]);
  await expect(page.locator('.network-visualizer-event-list .network-visualizer-event')).toHaveCount(1);
  await page.evaluate(() => window.fixtureSendLiveFrame(7, 'source_change', {
    source_epoch: 'fixture-p25', sequence: 2, collection_enabled: false, server_time_ms: Date.now() }));
  await expect(page.locator('.network-visualizer-status')).toHaveText('Saved history · Collection off');
  expect(historyRequests).toBe(1, 'normal stream delivery never reloads the saved window');
  await expect(page.locator('.network-visualizer-scope-title a.ui-link-text')).toHaveAttribute('href',
    new RegExp(`radio_system_key=${encodeURIComponent(systemKey)}`));
  await page.screenshot({ path: testInfo.outputPath('live-history-desktop-light.png') });
  await page.evaluate(() => document.querySelector('.primary-nav a[data-view="credits"]').click());
  await expect(page.locator('.credits-copy').first()).toBeVisible();
  await expect.poll(() => fixture.requests.filter((request) => request === '/api/v1/live/multiplex/control').length).toBeGreaterThan(1);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test('a failed reload retains the scene and automatically recovers on a dark phone', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const fixture = await installSiteStyleApplication(page, 'dark');
  let historyRequests = 0, releaseRetry;
  const retry = new Promise((resolve) => { releaseRetry = resolve; });
  await page.route('**/api/v1/activity?**', async (route) => {
    historyRequests++;
    if (historyRequests === 2) return route.fulfill({ status: 503,
      json: { error: { code: 'unavailable', message: 'Saved history is temporarily unavailable.' } } });
    if (historyRequests > 2) await retry;
    return route.fulfill({ json: snapshot(activity(historyRequests === 1 ? 'Engine 4' : 'Updated Engine 4')) });
  });
  await page.goto('/app.html?view=network-visualizer');
  const radio = page.locator('.network-visualizer-label[data-type="radio"]:not([hidden])');
  await expect(radio).toHaveText('Engine 4');
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.locator('.network-visualizer-status')).toHaveText('Reconnecting · showing previous activity');
  await expect(radio).toHaveText('Engine 4');
  await expect(page.locator('.network-visualizer-canvas canvas')).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('live-history-retry-dark-phone.png') });
  releaseRetry();
  await expect(radio).toHaveText('Updated Engine 4');
  expect(historyRequests).toBe(3, 'the bounded retry recovers without requiring another click');
  await expect(page.locator('.network-visualizer-status')).toHaveText(/^Live · Updated /);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test('fresh catchup removes a seed row corrected outside the selected window', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page);
  await page.route('**/api/v1/activity?**', (route) => route.fulfill({ json: snapshot(activity()) }));
  await page.route('**/api/v1/activity/live?**', (route) => route.fulfill({ json: { data: {
    source_epoch: 'fixture-p25', through_sequence: 1, rows: [activity('Expired correction', {
      observed_at_ms: now - 2 * 60 * 60 * 1_000 })], removed_ids: [], next_after_id: 1, has_more: false
  } } }));
  await page.goto('/app.html?view=network-visualizer');
  await expect(page.locator('.network-visualizer-status')).toHaveText(/^Live · Updated /);
  await expect(page.locator('.network-visualizer-label[data-type="radio"]:not([hidden])')).toHaveCount(0);
  await expect(page.locator('.network-visualizer-empty')).toContainText('No noteworthy P25 activity was saved in this scope');
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});
