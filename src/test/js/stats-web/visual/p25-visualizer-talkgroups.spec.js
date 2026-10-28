'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { test, expect } = require('@playwright/test');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');
const { installSiteStyleApplication, systemKey, channelId, systemName } =
  require('./fixtures/site-style-app.cjs');

const group = (id, label, count, owner = systemKey) => ({ id, label, count, owner });
const sampleGroups = [group(1201, 'Fire Dispatch', 3),
  group(1202, 'Regional Operations with a long shared talkgroup name', 2), group(1301, '', 1)];
const panelOf = page => page.locator('.network-visualizer-talkgroups');
const toggleOf = page => page.getByRole('button', { name: 'Top Talkgroups', exact: true });

async function ready(page) {
  await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
  await expect(page.locator('.network-visualizer-status')).toHaveText('Live');
}

async function mount(page, theme = 'light', width = 1280, prepare = null) {
  await page.setViewportSize({ width, height: 844 });
  const talkgroupRequests = [];
  page.on('request', request => {
    if (/\/api\/v1\/radio-systems\/[^/]+\/group-identities(?:\?|$)/.test(request.url()))
      talkgroupRequests.push(request.url());
  });
  const fixture = await installSiteStyleApplication(page, theme);
  fixture.talkgroupRequests = talkgroupRequests;
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
  await ready(page);
  await expect(page.locator('.network-visualizer-scope-title')).toContainText(systemName);
  return fixture;
}

// Accepted Activity observations are the only count source. Current Live tables
// already show an ongoing call when the page opens, but do not seed these totals.
async function append(page, groups, options = {}) {
  return page.evaluate(({ groups, options, defaultSystem, defaultName, channelId }) => {
    const after = window.p25SessionAfterId || 1;
    const rows = [];
    for (const entry of groups) {
      const owner = entry.owner || defaultSystem;
      const [, wacn, system] = owner.split(':');
      for (let index = 0; index < entry.count; index++) {
        const id = after + rows.length + 1;
        rows.push({ id, observed_at_ms: Date.now(), action: options.action || (index % 2 ? 'GRANT' : 'CALL'),
          protocol: 'P25', channel_kind: 'TRUNKED_SITE', radio_system_key: owner,
          system_name: owner === defaultSystem ? defaultName : 'Other System', configuration_id: channelId,
          source_identity_key: `v1-r-${wacn}-${system}-30914`, source_native_id: 30914,
          source_radio_id: 30914, source_alias_name: 'Engine 4',
          target_identity_key: `v1-g-${wacn}-${system}-${entry.id}`, target_kind: 'talkgroup',
          target_id: entry.id, target_native_id: entry.id, target_alias_name: entry.label });
      }
    }
    const next = after + rows.length;
    const payload = { source_epoch: 'fixture-p25', after_id: after, next_after_id: next,
      watermark_id: next, rows };
    window.p25SessionAfterId = next;
    window.fixtureSendLiveFrame(7, 'activity_append', payload);
    return payload;
  }, { groups, options, defaultSystem: systemKey, defaultName: systemName, channelId });
}

function clean(fixture) {
  expect(fixture.talkgroupRequests).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
}

async function evidence(page, testInfo, filename) {
  const directory = process.env.P25_TALKGROUPS_EVIDENCE;
  if (directory) fs.mkdirSync(directory, { recursive: true });
  await page.screenshot({ path: directory ? path.join(directory, filename) : testInfo.outputPath(filename) });
}

test('Top Talkgroups ranks new calls and grants immediately without stored totals, polling, or duplicate counts', async ({ page }) => {
  const fixture = await mount(page);
  const toggle = toggleOf(page), panel = panelOf(page);
  expect(fixture.talkgroupRequests).toEqual([]);
  await toggle.click();
  await expect(panel).toContainText('Calls and grants since this page opened');
  await expect(panel.locator('li')).toHaveCount(0);
  await expect(panel).not.toContainText('1,487');
  await append(page, sampleGroups);
  await expect(panel.locator('li')).toHaveCount(3);
  await expect(panel.locator('.ui-pill')).toHaveText(['3', '2', '1']);
  await expect(panel.getByRole('link', { name: 'Fire Dispatch', exact: true })).toHaveAttribute('href', /group-identity/);
  await expect(panel).toContainText('1201');
  await expect(panel).toContainText('1301');
  const fireDispatch = panel.getByRole('link', { name: 'Fire Dispatch', exact: true });
  await fireDispatch.focus();
  const latest = await append(page, [group(1202, sampleGroups[1].label, 2)]);
  await expect(panel.locator('li').first()).toContainText(sampleGroups[1].label);
  await expect(panel.locator('.ui-pill')).toHaveText(['4', '3', '1']);
  await expect(fireDispatch).toBeFocused();
  await page.evaluate(payload => window.fixtureSendLiveFrame(7, 'activity_append', payload), latest);
  await page.clock.fastForward(30_100);
  await expect(panel.locator('.ui-pill')).toHaveText(['4', '3', '1']);
  clean(fixture);
});

test('closed and hidden panels accumulate session counts and preserve unsaved Actions choices', async ({ page }) => {
  const fixture = await mount(page);
  const toggle = toggleOf(page), panel = panelOf(page);
  await append(page, [group(1201, 'Fire Dispatch', 2)]);
  await toggle.click();
  await expect(panel.locator('.ui-pill')).toHaveText(['2']);
  await toggle.click();
  await expect(panel).toBeHidden();
  await append(page, [group(1201, 'Fire Dispatch', 1)]);
  await page.evaluate(() => window.p25TalkgroupsHidden(true));
  await append(page, [group(1201, 'Fire Dispatch', 2), group(1202, 'Operations', 1)]);
  await page.clock.fastForward(30_100);
  await page.evaluate(() => window.p25TalkgroupsHidden(false));
  await toggle.click();
  await expect(panel.locator('.ui-pill')).toHaveText(['5', '1']);
  await page.evaluate(() => window.p25TalkgroupsHidden(true));
  await append(page, [group(1202, 'Operations', 6)]);
  await page.evaluate(() => window.p25TalkgroupsHidden(false));
  await expect(panel.locator('.ui-pill')).toHaveText(['7', '5']);
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
  clean(fixture);
});

test('session rankings follow the selected system and keep repeated talkgroup IDs separate', async ({ page }) => {
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
  await append(page, [group(1201, 'Fire Dispatch', 2), group(1201, 'Other System Dispatch', 3, otherSystem)]);
  await toggleOf(page).click();
  const panel = panelOf(page);
  await expect(panel.locator('li')).toHaveCount(1);
  await expect(panel).toContainText('Fire Dispatch');
  await expect(panel.locator('.ui-pill')).toHaveText(['2']);
  await expect.poll(() => page.evaluate(key => window.p25TalkgroupsSelectSystem(key), otherSystem)).toBe(true);
  await expect(panel).toContainText('Other System Dispatch');
  await expect(panel).not.toContainText('Fire Dispatch');
  await expect(panel.locator('.ui-pill')).toHaveText(['3']);
  await expect(panel.getByRole('link', { name: 'Other System Dispatch', exact: true }))
    .toHaveAttribute('href', /p25%3A00002%3A002/);
  await page.evaluate(key => window.p25TalkgroupsSelectSystem(key), systemKey);
  await expect(panel.locator('.ui-pill')).toHaveText(['2']);
  clean(fixture);
});

test('refresh and returning to the page start a fresh session count', async ({ page }) => {
  const fixture = await mount(page);
  await append(page, [group(1201, 'Fire Dispatch', 3)]);
  await toggleOf(page).click();
  await expect(panelOf(page).locator('.ui-pill')).toHaveText(['3']);
  await page.reload();
  await ready(page);
  await toggleOf(page).click();
  await expect(panelOf(page).locator('li')).toHaveCount(0);
  await append(page, [group(1201, 'Fire Dispatch', 1)]);
  await expect(panelOf(page).locator('.ui-pill')).toHaveText(['1']);
  await page.goto('/app.html?view=dashboard');
  await expect(page.locator('.network-visualizer-layout')).toHaveCount(0);
  await page.goto('/app.html?view=network-visualizer');
  await ready(page);
  await toggleOf(page).click();
  await expect(panelOf(page).locator('li')).toHaveCount(0);
  clean(fixture);
});

test('a new live source epoch retains page counts and accepts its restarted observation IDs', async ({ page }) => {
  const fixture = await mount(page);
  const previous = await append(page, [group(1201, 'Fire Dispatch', 3)]);
  await toggleOf(page).click();
  const panel = panelOf(page);
  await expect(panel.locator('.ui-pill')).toHaveText(['3']);
  await page.evaluate(row => {
    window.fixtureSendLiveFrame(7, 'source_change', { source_epoch: 'fixture-new', sequence: 0,
      watermark_id: 0, server_time_ms: Date.now(), available: true, collection_enabled: true });
    window.fixtureSendLiveFrame(7, 'activity_append', { source_epoch: 'fixture-new', after_id: 0,
      next_after_id: 1, watermark_id: 1,
      rows: [{ ...row, id: 1, action: 'CALL', observed_at_ms: Date.now() }] });
  }, previous.rows[0]);
  await expect(panel.locator('li')).toHaveCount(1);
  await expect(panel).toContainText('Fire Dispatch');
  await expect(panel.locator('.ui-pill')).toHaveText(['4']);
  clean(fixture);
});

test('the full 20-row phone session list scrolls to its final talkgroup without horizontal overflow', async ({ page }, testInfo) => {
  const fixture = await mount(page, 'light', 390);
  const values = Array.from({ length: 20 }, (_, index) => group(1201 + index,
    `Regional Operations ${index + 1} with a long shared talkgroup name`, 21 - index));
  await append(page, values);
  const toggle = toggleOf(page), panel = panelOf(page);
  await toggle.click();
  await expect(panel.locator('li')).toHaveCount(20);
  const first = panel.locator('li').first(), last = panel.locator('li').last();
  await expect(first).toBeInViewport();
  await expect(last).not.toBeInViewport();
  await page.mouse.move(190, 600);
  await page.mouse.wheel(0, 3000);
  await expect(last).toBeInViewport();
  await expect(last.getByRole('link', { name: values[19].label, exact: true })).toBeVisible();
  await expect(last).toContainText('1220');
  await expect(last.locator('.ui-pill')).toHaveText('2');
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
  await evidence(page, testInfo, 'talkgroups-light-390-full-list.png');
  await page.getByRole('dialog').getByRole('button', { name: 'Close Top Talkgroups', exact: true }).click();
  await expect(panel).toBeHidden();
  await expect(toggle).toBeFocused();
  clean(fixture);
});

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 390], ['dark', 390]]) {
  test(`Top Talkgroups session counts stay readable in ${theme} at ${width}px`, async ({ page }, testInfo) => {
    const fixture = await mount(page, theme, width);
    await append(page, sampleGroups);
    const toggle = toggleOf(page), panel = panelOf(page);
    await toggle.click();
    await expect(panel.getByRole('link', { name: 'Fire Dispatch', exact: true })).toBeVisible();
    await expect(panel.locator('.ui-pill')).toHaveText(['3', '2', '1']);
    const geometry = await panel.evaluate(element => {
      const bounds = element.getBoundingClientRect();
      return { inside: bounds.left >= 0 && bounds.right <= innerWidth &&
        bounds.top >= 0 && bounds.bottom <= innerHeight,
      overflow: document.documentElement.scrollWidth > innerWidth };
    });
    expect(geometry).toEqual({ inside: true, overflow: false });
    if (width < 600) await expect(page.getByRole('dialog')).toBeVisible();
    await expectContrast(page, { rootSelector: '.network-visualizer-talkgroups',
      reportName: `session-talkgroups-${theme}-${width}` });
    for (const hue of [0, 120, 240]) {
      await page.evaluate(async value => {
        const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
        applyThemeHue(value);
      }, hue);
      await expectContrast(page, { rootSelector: '.network-visualizer-talkgroups',
        reportName: `session-talkgroups-${theme}-${width}-hue-${hue}` });
    }
    await page.evaluate(async () => {
      const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
      applyThemeHue(null);
    });
    await evidence(page, testInfo, `talkgroups-${theme}-${width}.png`);
    if (width < 600) {
      await page.keyboard.press('Escape');
      await expect(panel).toBeHidden();
      await expect(toggle).toBeFocused();
    } else {
      await panel.getByRole('button', { name: /close top talkgroups/i }).click();
      await expect(panel).toBeHidden();
    }
    clean(fixture);
  });
}
