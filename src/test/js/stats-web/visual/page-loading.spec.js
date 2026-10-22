'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { installSiteStyleApplication, channelId, systemKey, groupKey } = require('./fixtures/site-style-app.cjs');

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`channel tones retain a real loading state, Retry and current-route ownership in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const fixture = await installSiteStyleApplication(page, theme);
    const pending = [];
    await page.route('**/api/v1/activity?*', route => new Promise(resolve => pending.push({ route, resolve })));
    await page.goto(`/app.html?view=channel&configuration_id=${channelId}&tab=activity&activity_has_tones=true`);
    const region = page.getByRole('region', { name: 'Activity', exact: true });
    await expect(region.getByRole('status')).toHaveText('Loading activity…');
    await expect(region).toHaveAttribute('aria-busy', 'true');
    await expect.poll(() => pending.length).toBe(1);
    expect(new URL(pending[0].route.request().url()).searchParams.get('has_tones')).toBe('true');
    if (process.env.WEB_REVIEW_DIR) await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR,
      `activity-loading-${theme}-${width}.png`), fullPage: true });
    await pending[0].route.fulfill({ status: 503, json: { error: { status: 503, message: 'Activity is temporarily unavailable.' } } });
    pending[0].resolve();
    await expect(region.getByRole('button', { name: 'Retry', exact: true })).toBeVisible();
    await expect(page.locator('#content > .page-header')).toContainText('North County Simulcast');
    await region.getByRole('button', { name: 'Retry', exact: true }).click();
    await expect(region.getByRole('status')).toHaveText('Loading activity…');
    await expect(region).toHaveAttribute('aria-busy', 'true');
    await expect.poll(() => pending.length).toBe(2);
    await pending[1].route.fulfill({ json: { data: [], meta: { has_more: false, next_before_id: null } } });
    pending[1].resolve();
    await expect(region).toHaveAttribute('aria-busy', 'false');
    await expect(region.locator('.activity-filter-toolbar')).toBeVisible();
    await expect(region.locator('.loading')).toHaveCount(0);
    await page.evaluate(() => { history.pushState({}, '', '/app.html?view=channel&configuration_id=' +
      '11111111-1111-4111-8111-111111111111&tab=activity&activity_has_tones=true');
      dispatchEvent(new PopStateEvent('popstate')); });
    await expect.poll(() => pending.length).toBe(3);
    await page.evaluate(() => { history.pushState({}, '', '/app.html?view=credits'); dispatchEvent(new PopStateEvent('popstate')); });
    await expect(page.locator('#content > .page-header')).toContainText('About VCE');
    await pending[2].route.fulfill({ json: { data: [], meta: { has_more: false, next_before_id: null } } }).catch(() => {});
    pending[2].resolve();
    await expect(page.locator('#content > .page-header')).toContainText('About VCE');
    await expect(page.locator('#content .async-section-error')).toHaveCount(0);
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('a page-level failure offers Retry and does not wait for unrelated initial health', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page);
  let requests = 0;
  await page.route(`**/api/v1/channels/${channelId}`, route => {
    requests++;
    return requests === 1 ? route.fulfill({ status: 503, json: { error: { status: 503, message: 'Try this page again.' } } }) : route.fallback();
  });
  await page.route('**/api/v1/receiver-health', () => new Promise(() => {}));
  await page.goto(`/app.html?view=channel&configuration_id=${channelId}&tab=activity`);
  await expect(page.locator('#content .async-section-error')).toContainText('Try this page again.');
  await page.locator('#content').getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(page.locator('.activity-filter-toolbar')).toBeVisible();
  expect(requests).toBe(2); expect(fixture.pageErrors).toEqual([]);
});


for (const { name, url, endpoint, regionName, loading, ready } of [
  { name: 'channel quality', url: `?view=channel&configuration_id=${channelId}&tab=quality`,
    endpoint: `/api/v1/channels/${channelId}/quality`, regionName: 'Control Channel Quality History',
    loading: 'Loading control channel quality history…', ready: '.quality-chart-stack' },
  { name: 'channel call totals', url: `?view=channel&configuration_id=${channelId}`,
    endpoint: `/api/v1/channels/${channelId}/group-identities`, regionName: 'Calls heard on this channel',
    loading: 'Loading group activity for this channel…', ready: '.ui-table-wrap' },
  { name: 'channel patches', url: `?view=channel&configuration_id=${channelId}&tab=patches`,
    endpoint: `/api/v1/channels/${channelId}/patch-groups`, regionName: 'Patches',
    loading: 'Loading patches…', ready: '.ui-table-wrap' },
  { name: 'group history', url: `?view=group-identity&radio_system_key=${systemKey}&identity_key=${groupKey}`,
    endpoint: `/api/v1/radio-systems/${systemKey}/group-identities/${groupKey}/activity`,
    regionName: 'Call history', loading: 'Loading group activity history…', ready: '.group-identity-history-section' }
]) {
  test(`${name} mounts its loading section before the request and supports Retry`, async ({ page }) => {
    const fixture = await installSiteStyleApplication(page);
    if (name === 'channel patches') await page.route(`**/api/v1/channels/${channelId}`, route =>
      route.fulfill({ json: { data: { channel: { configuration_id: channelId, protocol: 'P25',
        channel_kind: 'TRUNKED', name: 'North County Simulcast', radio_system_key: systemKey,
        capabilities: { patch_groups: true, activity: true } } } } }));
    const pending = [];
    await page.route(url => decodeURIComponent(url.pathname) === endpoint, route => new Promise(resolve => pending.push({ route, resolve })));
    await page.goto(`/app.html${url}`);
    const region = page.getByRole('region', { name: regionName, exact: true });
    await expect(region.getByRole('status')).toHaveText(loading);
    await expect(region).toHaveAttribute('aria-busy', 'true');
    await expect.poll(() => pending.length).toBe(1);
    await pending[0].route.fulfill({ status: 503, json: { error: { message: 'History is temporarily unavailable.' } } });
    pending[0].resolve();
    await region.getByRole('button', { name: 'Retry', exact: true }).click();
    await expect(region.getByRole('status')).toHaveText(loading);
    await expect.poll(() => pending.length).toBe(2);
    await pending[1].route.fallback(); pending[1].resolve();
    await expect(region).toHaveAttribute('aria-busy', 'false');
    await expect(region.locator(ready)).toBeVisible();
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('initial access, preferences and status share ten seconds, then a page Retry gets a fresh deadline', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page);
  await page.route('**/api/v1/auth/session', async route => {
    await new Promise(resolve => setTimeout(resolve, 2_000)); await route.fallback();
  });
  await page.route('**/api/v1/me/preferences', async route => {
    await new Promise(resolve => setTimeout(resolve, 2_000)); await route.fallback();
  });
  let statusRequests = 0;
  await page.route('**/api/v1/status', () => { statusRequests++; return new Promise(() => {}); });
  const started = Date.now();
  await page.goto(`/app.html?view=channel&configuration_id=${channelId}&tab=activity`);
  await expect(page.locator('#content .loading')).toBeVisible();
  const retry = page.locator('#content').getByRole('button', { name: 'Retry', exact: true });
  await expect(retry).toBeVisible({ timeout: 11_500 });
  expect(Date.now() - started).toBeLessThan(12_000);
  await expect(page.locator('#content')).toContainText('The receiver did not respond in time.');
  await retry.click();
  await expect(page.locator('.activity-filter-toolbar')).toBeVisible();
  expect(statusRequests).toBeLessThanOrEqual(2);
  expect(fixture.pageErrors).toEqual([]);
});
