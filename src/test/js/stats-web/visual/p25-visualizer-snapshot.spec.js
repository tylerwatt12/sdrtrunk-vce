'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, channelId, groupKey, radioKey, systemName, p25Affiliations } =
  require('./fixtures/site-style-app.cjs');

const now = Date.parse('2026-10-01T12:00:00Z');
const correctedRadio = 'v1-r-00001-001-30915';
const activity = (alias = 'Engine 4', overrides = {}) => ({ id: 1, observed_at_ms: now - 1_000, action: 'JOIN',
  protocol: 'p25', channel_kind: 'trunked_site', radio_system_key: systemKey, system_name: systemName,
  configuration_id: channelId, source_identity_key: radioKey, source_radio_id: 30914,
  source_alias_name: alias, target_identity_key: groupKey, target_kind: 'talkgroup',
  target_id: 1201, target_alias_name: 'Fire Dispatch', ...overrides });
const snapshot = (row) => ({ data: { source_epoch: 'fixture-p25', sequence: 0, available: true, affiliations_truncated: false, rows: [row], ...p25Affiliations([row]),
  captured_at_ms: now, traffic_grant_age_out_milliseconds: 1_200, collection_enabled: true } });
const send = (page, sequence, changes = {}) => page.evaluate(({ sequence, changes }) =>
  window.fixtureSendLiveFrame(7, 'p25_state_delta', { source_epoch: 'fixture-p25',
    base_sequence: sequence - 1, sequence, rows: [], removed_ids: [], affiliations: [], removed_radio_keys: [],
    available: true, affiliations_truncated: false, collection_enabled: true, part: 0, parts: 1, ...changes }), { sequence, changes });

test('current state and saved corrections update independently without Refresh', async ({ page }, testInfo) => {
  const fixture = await installSiteStyleApplication(page);
  let historyRequests = 0;
  await page.route('**/api/v1/activity/live/seed*', (route) => {
    historyRequests++;
    expect(new URL(route.request().url()).search).toBe('');
    return route.fulfill({ json: snapshot(activity()) });
  });
  await page.goto('/app.html?view=network-visualizer');
  const radio = page.locator('.network-visualizer-label[data-type="radio"]:not([hidden])');
  await expect(radio).toHaveText('Engine 4');
  await expect(page.locator('.network-visualizer-status')).toHaveText(/^Live · Updated /);
  const corrected = activity('Corrected Engine', { source_identity_key: correctedRadio, source_radio_id: 30915 });
  await send(page, 1, { rows: [corrected] });
  await expect(radio).toHaveCount(2, 'correcting event identity does not erase canonical current state');
  await send(page, 2, { ...p25Affiliations([corrected]),
    removed_radio_keys: [{ radio_system_key: systemKey, radio_identity_key: radioKey }] });
  await expect(radio).toHaveCount(1);
  await expect(radio).toHaveText('Corrected Engine');
  const emergency = { ...corrected, id: 2, observed_at_ms: now, action: 'EMERGENCY' };
  await send(page, 3, { rows: [emergency] });
  await expect(page.locator('.network-visualizer-auto-focus')).toBeVisible();
  await expect(page.locator('.network-visualizer-auto-focus-title')).toContainText(/emergency/i);
  await page.getByRole('button', { name: 'Events', exact: true }).click();
  await expect(page.locator('.network-visualizer-event-list .network-visualizer-event')).toHaveCount(2);
  await send(page, 3, { rows: [emergency] });
  await expect(page.locator('.network-visualizer-event-list .network-visualizer-event')).toHaveCount(2);
  await send(page, 4, { collection_enabled: false });
  await expect(page.locator('.network-visualizer-status')).toHaveText('Live affiliations · Activity collection off');
  await send(page, 5, { available: false, collection_enabled: false });
  await expect(page.locator('.network-visualizer-status')).toHaveText('P25 data unavailable · Showing previous data');
  await expect(radio).toHaveText('Corrected Engine');
  await send(page, 6, { ...p25Affiliations([{ ...corrected, source_alias_name: 'Current Engine' }]), collection_enabled: false });
  await expect(radio).toHaveText('Current Engine');
  await expect(page.locator('.network-visualizer-status')).toHaveText('Live affiliations · Activity collection off');
  expect(historyRequests).toBe(1, 'normal stream delivery never reloads the recent seed');
  expect(fixture.requests).not.toContain('/api/v1/activity');
  await expect(page.getByRole('group', { name: 'History scope' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^(1h|24h)$/ })).toHaveCount(0);
  await expect(page.locator('.network-visualizer-scope-title a.ui-link-text')).toHaveAttribute('href',
    new RegExp(`radio_system_key=${encodeURIComponent(systemKey)}`));
  await page.screenshot({ path: testInfo.outputPath('live-affiliations-desktop-light.png') });
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
  let currentRow = activity();
  await page.route('**/api/v1/activity/live/seed*', async (route) => {
    historyRequests++;
    expect(new URL(route.request().url()).searchParams.has('refresh')).toBe(historyRequests === 2);
    if (historyRequests === 2) return route.fulfill({ status: 503,
      json: { error: { code: 'unavailable', message: 'Saved history is temporarily unavailable.' } } });
    if (historyRequests > 2) await retry;
    currentRow = activity(historyRequests === 1 ? 'Engine 4' : 'Updated Engine 4');
    return route.fulfill({ json: snapshot(currentRow) });
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

test('current affiliation survives deletion of every recent Activity row', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page);
  await page.route('**/api/v1/activity/live/seed*', (route) => route.fulfill({ json: snapshot(activity()) }));
  await page.goto('/app.html?view=network-visualizer');
  await expect(page.locator('.network-visualizer-status')).toHaveText(/^Live · Updated /);
  await send(page, 1, { removed_ids: [1] });
  await expect(page.locator('.network-visualizer-label[data-type="radio"]:not([hidden])')).toHaveText('Engine 4');
  await expect(page.locator('.network-visualizer-empty')).toBeHidden();
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test('current-only state visibly marks a bounded partial scene', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page);
  const current = p25Affiliations(Array.from({ length: 901 }, (_, index) => activity(`Unit ${index}`, {
    source_identity_key: `v1-r-${index}`, source_radio_id: index })));
  await page.route('**/api/v1/activity/live/seed*', (route) => route.fulfill({ json: { data: {
    ...snapshot(activity()).data, rows: [], ...current } } }));
  await page.goto('/app.html?view=network-visualizer');
  await expect(page.locator('.network-visualizer-status')).toContainText('Partial view');
  await expect(page.locator('.network-visualizer-canvas canvas')).toBeVisible();
  await expect(page.locator('.network-visualizer-status')).toHaveAttribute('title', /900 radios and 220 talkgroups/);
  await expect(page.locator('.network-visualizer-empty')).toBeHidden();
  expect(fixture.pageErrors).toEqual([]);
});

test('server-bounded current affiliations visibly identify their partial inventory on a phone', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const fixture = await installSiteStyleApplication(page, 'dark');
  const current = p25Affiliations(Array.from({ length: 10_000 }, (_, index) => activity(`Unit ${index}`, {
    source_identity_key: `v1-r-${index}`, source_radio_id: index })));
  await page.route('**/api/v1/activity/live/seed*', (route) => route.fulfill({ json: { data: {
    ...snapshot(activity()).data, rows: [], ...current, affiliations_truncated: true } } }));
  await page.goto('/app.html?view=network-visualizer');
  const status = page.locator('.network-visualizer-status');
  await expect(status).toContainText('Partial view');
  await expect(status).toHaveAttribute('title', /most recently confirmed 10,000 affiliations/);
  await expect(page.locator('.network-visualizer-empty')).toBeHidden();
  await page.screenshot({ path: testInfo.outputPath('server-partial-affiliations-dark-phone.png') });
  await send(page, 1, { affiliations_truncated: false });
  await expect(status).toHaveAttribute('title', /^Showing up to 900 radios and 220 talkgroups/);
  await expect(status).toContainText('Partial view');
  expect(fixture.pageErrors).toEqual([]);
});
