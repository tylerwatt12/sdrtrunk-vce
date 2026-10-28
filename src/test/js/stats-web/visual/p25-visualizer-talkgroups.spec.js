'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { test, expect } = require('@playwright/test');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');
const { installSiteStyleApplication, systemKey, groupKey, systemName } =
  require('./fixtures/site-style-app.cjs');

const endpoint = /\/api\/v1\/radio-systems\/[^/]+\/group-identities(?:\?|$)/;
const group = (id, label, calls, owner = systemKey) => ({
  radio_system_key: owner, group_identity_kind: 'talkgroup', native_id: id,
  group_identity_id: id, alias_name: label, logical_call_count: calls,
  entity_ref: { kind: 'talkgroup', radio_system_key: owner,
    identity_key: owner === systemKey && id === 1201 ? groupKey : `v1-g-00001-001-${id}` }
});
const rows = [group(1201, 'Fire Dispatch', 1487),
  group(1202, 'Regional Operations with a long shared talkgroup name', 327), group(1301, '', 12)];
const respond = (route, values = rows) => route.fulfill({ json: {
  data: values, meta: { limit: 20, offset: 0, total_count: values.length, has_more: false }
} });

async function mount(page, theme = 'light', width = 1280, prepare = null) {
  await page.setViewportSize({ width, height: 844 });
  const fixture = await installSiteStyleApplication(page, theme);
  await page.clock.install({ time: new Date('2026-10-01T12:00:00Z') });
  await page.addInitScript(() => {
    let hidden = false;
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
    window.p25TalkgroupsHidden = value => {
      hidden = value;
      document.dispatchEvent(new Event('visibilitychange'));
    };
  });
  if (prepare) await prepare();
  await page.goto('/app.html?view=network-visualizer');
  await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
  await expect(page.locator('.network-visualizer-status')).toHaveText('Live');
  await expect(page.locator('.network-visualizer-scope-title')).toContainText(systemName);
  return fixture;
}

test('Top Talkgroups loads stored system totals only on demand and releases polling when hidden or closed', async ({ page }) => {
  const requests = [];
  const fixture = await mount(page);
  await page.route(endpoint, route => { requests.push(new URL(route.request().url())); return respond(route); });
  const toggle = page.getByRole('button', { name: 'Top Talkgroups', exact: true });
  expect(requests).toHaveLength(0);
  await toggle.click();
  const panel = page.locator('.network-visualizer-talkgroups');
  await expect(panel).toBeVisible();
  await expect(panel.getByRole('link', { name: 'Fire Dispatch', exact: true })).toBeVisible();
  await expect(panel.locator('.ui-pill').first()).toHaveText('1,487');
  await expect(panel).toContainText('1201');
  await expect(panel).toContainText('1301');
  expect(requests).toHaveLength(1);
  expect(decodeURIComponent(requests[0].pathname)).toBe(`/api/v1/radio-systems/${systemKey}/group-identities`);
  expect(Object.fromEntries(requests[0].searchParams)).toMatchObject({
    sort: 'logical_call_count', direction: 'desc', limit: '20'
  });
  expect(requests[0].searchParams.has('from_ms')).toBe(false);
  expect(requests[0].searchParams.has('to_ms')).toBe(false);
  await expect(panel.getByRole('link', { name: 'Fire Dispatch', exact: true })).toHaveAttribute('href', /group-identity/);
  await page.clock.fastForward(10_100);
  await expect.poll(() => requests.length).toBe(2);
  await page.evaluate(() => window.p25TalkgroupsHidden(true));
  await page.clock.fastForward(30_100);
  expect(requests).toHaveLength(2);
  await page.evaluate(() => window.p25TalkgroupsHidden(false));
  await expect.poll(() => requests.length).toBe(3);
  await toggle.click();
  await expect(panel).toBeHidden();
  await page.clock.fastForward(30_100);
  expect(requests).toHaveLength(3);
  const actionsToggle = page.getByRole('button', { name: 'Actions', exact: true });
  await actionsToggle.click();
  const actions = page.locator('.network-visualizer-events');
  const callHighlight = actions.getByRole('checkbox', { name: 'Highlight Calls and grants', exact: true });
  await callHighlight.focus();
  await callHighlight.press('Space');
  await expect(actions.locator('form')).toHaveClass(/is-dirty/);
  await toggle.click();
  await expect(panel).toBeVisible();
  await expect(actions).toBeHidden();
  await actionsToggle.click();
  await expect(panel).toBeHidden();
  await expect(callHighlight).not.toBeChecked();
  await expect(actions.locator('form')).toHaveClass(/is-dirty/);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test('switching systems replaces the scoped list and ignores a late response for the previous system', async ({ page }) => {
  const feature = path.resolve(__dirname, '../../../../../stats-web/assets/features/network-visualizer/index.js');
  const marker = '  const close = () => {';
  const source = fs.readFileSync(feature, 'utf8');
  expect(source.split(marker)).toHaveLength(2);
  const fixture = await mount(page, 'light', 1280, () =>
    page.route('**/assets/features/network-visualizer/index.js?**', route => route.fulfill({
      contentType: 'text/javascript', body: source.replace(marker, `
  window.p25TalkgroupsSelectSystem = key => enterSystem(key, { animate: false, immediate: true });
${marker}`)
    })));
  const otherSystem = 'p25:00002:002';
  const requests = [];
  let release;
  await page.route(endpoint, route => {
    const owner = decodeURIComponent(new URL(route.request().url()).pathname).split('/')[4];
    requests.push(owner);
    if (owner === systemKey) return new Promise(resolve => {
      release = async () => { await respond(route).catch(() => {}); resolve(); };
    });
    return respond(route, [group(1201, 'Other System Dispatch', 88, otherSystem)]);
  });
  await page.getByRole('button', { name: 'Top Talkgroups', exact: true }).click();
  await expect.poll(() => requests).toEqual([systemKey]);
  await page.evaluate(({ owner }) => {
    window.fixtureSendLiveSnapshot({ revision: 2, tables: [{ table_id: 'other-system-channel',
      channel_running: true, system_name: 'Other System', rows: [{ key: 'other-call',
        protocol: 'P25', serving_system_key: owner, system_name: 'Other System', status: 'CALL',
        activation_order: 3, target_identity_key: 'v1-g-00002-002-1201', target_kind: 'talkgroup',
        target_id: 1201, target_alias: 'Other System Dispatch' }] }] });
  }, { owner: otherSystem });
  await expect.poll(() => page.evaluate(key => window.p25TalkgroupsSelectSystem(key), otherSystem)).toBe(true);
  const panel = page.locator('.network-visualizer-talkgroups');
  await expect(panel).toContainText('Other System Dispatch');
  await release();
  await expect(panel).not.toContainText('Fire Dispatch');
  await expect(panel.locator('.ui-pill').first()).toHaveText('88');
  expect(requests).toEqual([systemKey, otherSystem]);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test('Top Talkgroups gives a retry after failure and handles a system with no calls', async ({ page }) => {
  let attempts = 0;
  const fixture = await mount(page);
  await page.route(endpoint, route => ++attempts === 1 ?
    route.fulfill({ status: 503, json: { error: { message: 'Temporary fixture failure' } } }) : respond(route, []));
  await page.getByRole('button', { name: 'Top Talkgroups', exact: true }).click();
  const panel = page.locator('.network-visualizer-talkgroups');
  await expect(panel.getByRole('alert')).toContainText(/could not|couldn’t|unable/i);
  await panel.getByRole('button', { name: /retry|try again/i }).click();
  await expect(panel).toContainText(/no .*calls|no call totals|no talkgroups/i);
  await expect(panel.getByRole('alert')).toHaveCount(0);
  expect(attempts).toBe(2);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test('the full 20-row phone list scrolls to its final talkgroup without horizontal overflow', async ({ page }) => {
  const fixture = await mount(page, 'light', 390);
  const values = Array.from({ length: 20 }, (_, index) => group(1201 + index,
    `Regional Operations ${index + 1} with a long shared talkgroup name`, 1000 - index));
  await page.route(endpoint, route => respond(route, values));
  const toggle = page.getByRole('button', { name: 'Top Talkgroups', exact: true });
  await toggle.click();
  const panel = page.locator('.network-visualizer-talkgroups');
  await expect(panel.locator('li')).toHaveCount(20);
  const first = panel.locator('li').first(), last = panel.locator('li').last();
  await expect(first).toBeInViewport();
  await expect(last).not.toBeInViewport();
  await page.mouse.move(190, 600);
  await page.mouse.wheel(0, 3000);
  await expect(last).toBeInViewport();
  await expect(last.getByRole('link', { name: values[19].alias_name, exact: true })).toBeVisible();
  await expect(last).toContainText('1220');
  await expect(last.locator('.ui-pill')).toHaveText('981');
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
  if (process.env.P25_TALKGROUPS_EVIDENCE) await page.screenshot({
    path: path.join(process.env.P25_TALKGROUPS_EVIDENCE, 'talkgroups-light-390-full-list.png')
  });
  await page.getByRole('dialog').getByRole('button', { name: 'Close Top Talkgroups', exact: true }).click();
  await expect(panel).toBeHidden();
  await expect(toggle).toBeFocused();
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 390], ['dark', 390]]) {
  test(`Top Talkgroups stays readable in ${theme} at ${width}px`, async ({ page }, testInfo) => {
    const fixture = await mount(page, theme, width);
    await page.route(endpoint, route => respond(route));
    const toggle = page.getByRole('button', { name: 'Top Talkgroups', exact: true });
    await toggle.click();
    const panel = page.locator('.network-visualizer-talkgroups');
    await expect(panel.getByRole('link', { name: 'Fire Dispatch', exact: true })).toBeVisible();
    const geometry = await panel.evaluate(element => {
      const bounds = element.getBoundingClientRect();
      return { inside: bounds.left >= 0 && bounds.right <= innerWidth &&
        bounds.top >= 0 && bounds.bottom <= innerHeight,
      overflow: document.documentElement.scrollWidth > innerWidth };
    });
    expect(geometry).toEqual({ inside: true, overflow: false });
    if (width < 600) await expect(page.getByRole('dialog')).toBeVisible();
    await expectContrast(page, { rootSelector: '.network-visualizer-talkgroups',
      reportName: `talkgroups-${theme}-${width}` });
    for (const hue of [0, 120, 240]) {
      await page.evaluate(async value => {
        const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
        applyThemeHue(value);
      }, hue);
      await expectContrast(page, { rootSelector: '.network-visualizer-talkgroups',
        reportName: `talkgroups-${theme}-${width}-hue-${hue}` });
    }
    await page.evaluate(async () => {
      const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
      applyThemeHue(null);
    });
    const directory = process.env.P25_TALKGROUPS_EVIDENCE;
    if (directory) fs.mkdirSync(directory, { recursive: true });
    await page.screenshot({ path: directory ? path.join(directory, `talkgroups-${theme}-${width}.png`) :
      testInfo.outputPath(`talkgroups-${theme}-${width}.png`) });
    if (width < 600) {
      await page.keyboard.press('Escape');
      await expect(panel).toBeHidden();
      await expect(toggle).toBeFocused();
    } else {
      await panel.getByRole('button', { name: /close top talkgroups/i }).click();
      await expect(panel).toBeHidden();
    }
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });
}
