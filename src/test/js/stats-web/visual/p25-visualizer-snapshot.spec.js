'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, channelId, groupKey, radioKey, systemName } =
  require('./fixtures/site-style-app.cjs');

const now = Date.parse('2026-10-01T12:00:00Z');
const activity = (alias = 'Engine 4') => ({ id: 1, observed_at_ms: now - 1_000, action: 'JOIN',
  protocol: 'p25', channel_kind: 'trunked_site', radio_system_key: systemKey, system_name: systemName,
  configuration_id: channelId, source_identity_key: radioKey, source_radio_id: 30914,
  source_alias_name: alias, target_identity_key: groupKey, target_kind: 'talkgroup',
  target_id: 1201, target_alias_name: 'Fire Dispatch' });
const snapshot = (row) => ({ data: [row], meta: { next_after_id: 1, watermark_id: 1,
  has_more: false, traffic_grant_age_out_milliseconds: 1_200 } });

test('saved history changes only on Refresh and replaces enriched rows without a live subscription', async ({ page }, testInfo) => {
  const fixture = await installSiteStyleApplication(page);
  let historyRequests = 0;
  const liveRequests = [];
  page.on('request', (request) => {
    if (new URL(request.url()).pathname.startsWith('/api/v1/live/multiplex')) liveRequests.push(request.url());
  });
  await page.route('**/api/v1/activity?**', (route) => {
    historyRequests++;
    expect(new URL(route.request().url()).searchParams.get('after_id')).toBe('0');
    return route.fulfill({ json: snapshot(activity(historyRequests === 1 ? 'Engine 4' : 'Updated Engine 4')) });
  });
  await page.goto('/app.html?view=network-visualizer');
  const radio = page.locator('.network-visualizer-label[data-type="radio"]');
  await expect(radio).toHaveText('Engine 4');
  await expect(page.locator('.network-visualizer-status')).toHaveText(/^Saved history · Updated /);
  await page.waitForTimeout(5_200);
  await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
  expect(historyRequests).toBe(1, 'saved history has no timer or visibility refresh');
  expect(liveRequests).toEqual([]);
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(radio).toHaveText('Updated Engine 4');
  expect(historyRequests).toBe(2, 'manual refresh rereads the full snapshot, including the same enriched row ID');
  await expect(page.locator('.network-visualizer-scope-title a.ui-link-text')).toHaveAttribute('href',
    new RegExp(`radio_system_key=${encodeURIComponent(systemKey)}`));
  await page.screenshot({ path: testInfo.outputPath('saved-history-desktop-light.png') });
  await page.evaluate(() => document.querySelector('.primary-nav a[data-view="credits"]').click());
  await expect(page.locator('.credits-copy').first()).toBeVisible();
  expect(liveRequests).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test('failed Refresh retains the complete saved scene and a manual retry succeeds on a dark phone', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const fixture = await installSiteStyleApplication(page, 'dark');
  let historyRequests = 0;
  await page.route('**/api/v1/activity?**', (route) => {
    historyRequests++;
    if (historyRequests === 2) return route.fulfill({ status: 503,
      json: { error: { code: 'unavailable', message: 'Saved history is temporarily unavailable.' } } });
    return route.fulfill({ json: snapshot(activity(historyRequests === 1 ? 'Engine 4' : 'Updated Engine 4')) });
  });
  await page.goto('/app.html?view=network-visualizer');
  const radio = page.locator('.network-visualizer-label[data-type="radio"]');
  await expect(radio).toHaveText('Engine 4');
  const refresh = page.getByRole('button', { name: 'Refresh', exact: true });
  await refresh.click();
  await expect(page.locator('.network-visualizer-status')).toHaveText('Refresh failed · showing previous history');
  await expect(radio).toHaveText('Engine 4');
  await expect(page.locator('.network-visualizer-canvas canvas')).toBeVisible();
  await expect(refresh).toBeEnabled();
  await page.screenshot({ path: testInfo.outputPath('saved-history-refresh-failed-dark-phone.png') });
  await refresh.click();
  await expect(radio).toHaveText('Updated Engine 4');
  expect(historyRequests).toBe(3);
  await expect(page.locator('.network-visualizer-status')).toHaveText(/^Saved history · Updated /);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});
