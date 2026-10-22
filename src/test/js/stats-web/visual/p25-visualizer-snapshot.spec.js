'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, channelId, groupKey, radioKey, systemName, p25Affiliations } =
  require('./fixtures/site-style-app.cjs');

const now = Date.parse('2026-10-01T12:00:00Z');
const activity = (alias = 'Engine 4', overrides = {}) => ({ id: 1, observed_at_ms: now - 1_000, action: 'JOIN',
  protocol: 'p25', channel_kind: 'trunked_site', radio_system_key: systemKey, system_name: systemName,
  configuration_id: channelId, source_identity_key: radioKey, source_radio_id: 30914,
  source_alias_name: alias, target_identity_key: groupKey, target_kind: 'talkgroup',
  target_id: 1201, target_alias_name: 'Fire Dispatch', ...overrides });
const snapshot = (rows) => ({ data: { source_epoch: 'fixture-p25', sequence: 0, available: true,
  ...p25Affiliations(rows), captured_at_ms: now, traffic_grant_age_out_milliseconds: 1_200, collection_enabled: true } });
const sendNative = (page, sequence, changes = {}) => page.evaluate(({ sequence, changes }) =>
  window.fixtureSendLiveFrame(7, 'p25_state_delta', { source_epoch: 'fixture-p25',
    base_sequence: sequence - 1, sequence, affiliations: [], removed_radio_keys: [],
    available: true, collection_enabled: true, part: 0, parts: 1, ...changes }), { sequence, changes });
const sendAppend = (page, after, next, rows) => page.evaluate(({ after, next, rows }) =>
  window.fixtureSendLiveFrame(7, 'activity_append', { source_epoch: 'fixture-p25', history_generation: 0,
    after_id: after, next_after_id: next, watermark_id: next, rows }), { after, next, rows });

async function historyRoute(page, currentRows, watermark = () => Math.max(0, ...currentRows().map(row => row.id))) {
  let requests = 0;
  await page.route(/\/api\/v1\/activity(?:\?|$)/, route => {
    requests++;
    const query = new URL(route.request().url()).searchParams;
    const after = Number(query.get('after_id') || 0), pinned = Number(query.get('watermark_id') || watermark());
    const rows = currentRows().filter(row => row.id > after && row.id <= pinned).sort((a, b) => a.id - b.id);
    return route.fulfill({ json: { data: rows, meta: { next_after_id: pinned, watermark_id: pinned,
      has_more: false, reset_required: false } } });
  });
  return { get requests() { return requests; } };
}

test('native affiliations and saved appends update independently without Refresh', async ({ page }, testInfo) => {
  const fixture = await installSiteStyleApplication(page);
  let seedRequests = 0;
  const rows = [activity()];
  const history = await historyRoute(page, () => rows);
  await page.route('**/api/v1/activity/live/seed*', route => {
    seedRequests++;
    expect(new URL(route.request().url()).search).toBe('');
    return route.fulfill({ json: snapshot([activity()]) });
  });
  await page.goto('/app.html?view=network-visualizer');
  const radios = page.locator('.network-visualizer-label[data-type="radio"]:not([hidden])');
  await expect(radios).toHaveText('Engine 4');
  await expect(page.locator('.network-visualizer-status')).toHaveText(/^Live · Updated /);
  const emergency = activity('Field unit', { id: 2, observed_at_ms: now, action: 'EMERGENCY',
    source_identity_key: 'v1-r-00001-001-30915', source_radio_id: 30915 });
  rows.push(emergency);
  await sendAppend(page, 1, 2, [emergency]);
  await expect(radios).toHaveCount(2);
  await expect(page.locator('.network-visualizer-auto-focus-title')).toContainText(/emergency/i);
  await page.getByRole('button', { name: 'Events', exact: true }).click();
  const events = page.locator('.network-visualizer-event-list .network-visualizer-event');
  await expect(events).toHaveCount(2);
  await sendAppend(page, 1, 2, [emergency]);
  await expect(events).toHaveCount(2);
  await sendNative(page, 1, { collection_enabled: false });
  await expect(page.locator('.network-visualizer-status')).toHaveText('Live affiliations · Activity collection off');
  await sendNative(page, 2, { available: false, collection_enabled: false });
  await expect(page.locator('.network-visualizer-status')).toHaveText('P25 data unavailable · Showing previous data');
  await expect(radios).toHaveCount(2);
  await sendNative(page, 3, { ...p25Affiliations([activity('Current Engine')]), collection_enabled: false });
  await expect(radios.filter({ hasText: 'Current Engine' })).toHaveCount(1);
  await expect(page.locator('.network-visualizer-status')).toHaveText('Live affiliations · Activity collection off');
  expect(seedRequests).toBe(1, 'ordinary native and saved updates do not fetch a new seed');
  expect(history.requests).toBeGreaterThan(0);
  await expect(page.getByRole('group', { name: 'History scope' })).toBeVisible();
  await expect(page.getByRole('button', { name: '1h', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.screenshot({ path: testInfo.outputPath('live-affiliations-desktop-light.png') });
  await page.evaluate(() => document.querySelector('.primary-nav a[data-view="credits"]').click());
  await expect(page.locator('.credits-copy').first()).toBeVisible();
  await expect.poll(() => fixture.requests.filter(request => request === '/api/v1/live/multiplex/control').length).toBeGreaterThan(1);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test('a failed native reload retains the scene and recovers on a dark phone', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const fixture = await installSiteStyleApplication(page, 'dark');
  let requests = 0, releaseRetry;
  const retry = new Promise(resolve => { releaseRetry = resolve; });
  await historyRoute(page, () => []);
  await page.route('**/api/v1/activity/live/seed*', async route => {
    requests++;
    expect(new URL(route.request().url()).searchParams.has('refresh')).toBe(requests === 2);
    if (requests === 2) return route.fulfill({ status: 503,
      json: { error: { code: 'unavailable', message: 'Current affiliations are temporarily unavailable.' } } });
    if (requests > 2) await retry;
    return route.fulfill({ json: snapshot([activity(requests === 1 ? 'Engine 4' : 'Updated Engine 4')]) });
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
  expect(requests).toBe(3);
  await expect(page.locator('.network-visualizer-status')).toHaveText(/^Live · Updated /);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test('current affiliations survive a committed saved-history clear', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page);
  let cleared = false;
  const history = await historyRoute(page, () => cleared ? [] : [activity()], () => cleared ? 0 : 1);
  await page.route('**/api/v1/activity/live/seed*', route => route.fulfill({ json: snapshot([activity()]) }));
  await page.goto('/app.html?view=network-visualizer');
  await expect(page.locator('.network-visualizer-status')).toHaveText(/^Live · Updated /);
  cleared = true;
  await page.evaluate(() => window.fixtureSendLiveFrame(7, 'activity_reset',
    { source_epoch: 'fixture-p25', watermark_id: 0, history_generation: 1 }));
  await expect.poll(() => history.requests).toBeGreaterThan(1);
  await expect(page.locator('.network-visualizer-label[data-type="radio"]:not([hidden])')).toHaveText('Engine 4');
  await expect(page.locator('.network-visualizer-empty')).toBeHidden();
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test('more than ten thousand native affiliations load while only the drawing is partial', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const fixture = await installSiteStyleApplication(page, 'dark');
  await historyRoute(page, () => []);
  const current = Array.from({ length: 10_001 }, (_, index) => activity(`Unit ${index}`, {
    source_identity_key: `v1-r-${index}`, source_radio_id: index }));
  await page.route('**/api/v1/activity/live/seed*', route => route.fulfill({ json: snapshot(current) }));
  await page.goto('/app.html?view=network-visualizer');
  const status = page.locator('.network-visualizer-status');
  await expect(status).toContainText('Partial view');
  await expect(status).toHaveAttribute('title', /^Showing up to 900 radios and 220 talkgroups/);
  await expect(status).not.toHaveAttribute('title', /10,000|recently confirmed/);
  await expect(page.locator('.network-visualizer-empty')).toBeHidden();
  await page.screenshot({ path: testInfo.outputPath('complete-affiliations-partial-drawing-dark-phone.png') });
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});
