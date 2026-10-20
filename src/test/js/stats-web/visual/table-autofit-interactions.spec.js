'use strict';

const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication, systemKey, radioKey, channelId } =
  require('./fixtures/site-style-app.cjs');

const activityQuery = `view=radio&radio_system_key=${encodeURIComponent(systemKey)}` +
  `&identity_key=${radioKey}&tab=activity`;
const activitySchema = ['time', 'duration', 'action', 'event', 'tones', 'source', 'target',
  'channel', 'encryption', 'related'];

async function installApplication(page) {
  const fixture = await installSiteStyleApplication(page);
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  const preferences = structuredClone((await import(pathToFileURL(file).href)).defaults);
  preferences.tables.activity = { schema: activitySchema, column_order: activitySchema,
    column_widths: {}, hidden_columns: activitySchema.filter((id) => !['time', 'channel'].includes(id)),
    collapsed_groups: [] };
  const state = { fixture, preferences, revision: 1, saves: [], blockSave: false, releaseSave: null };
  await page.route('**/api/v1/me/preferences', async (route) => {
    if (route.request().method() === 'PUT') {
      state.preferences = route.request().postDataJSON();
      state.saves.push(structuredClone(state.preferences.tables.activity.column_widths));
      state.revision += 1;
      if (state.blockSave) await new Promise((resolve) => { state.releaseSave = resolve; });
    }
    await route.fulfill({ json: { revision: state.revision, preferences: state.preferences } });
  });
  await page.route('**/api/v1/activity**', (route) => {
    if (new URL(route.request().url()).pathname !== '/api/v1/activity') return route.fallback();
    return route.fulfill({ json: { data: {
      rows: [{ id: 1, observed_at_ms: Date.parse('2026-10-01T12:00:00Z'), action: 'REGISTER',
        event_type: 'REGISTER_ESN',
        protocol: 'P25', channel_kind: 'TRUNKED_SITE', radio_system_key: systemKey,
        configuration_id: channelId,
        name: 'North County Regional Emergency Services Simulcast Dispatch Channel',
        frequency_hz: 851012500, lcn: '0-1234', timeslot: 2,
        entity_ref: { kind: 'channel', key: channelId }, source_radio_id: 30914,
        source_native_id: 30914, source_identity_key: radioKey }],
      total_count: 1, limit: 100, offset: 0, has_more: false, next_offset: null,
      watermark_id: 1, next_after_id: 1
    } } });
  });
  await page.goto(`/app.html?${activityQuery}`);
  const table = page.locator('table[data-table-type="activity"]');
  await expect(table.locator('tbody tr[data-id="1"]')).toHaveCount(1);
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  const header = table.locator('thead [data-column="channel"]');
  return { state, table, header, handle: header.locator('.column-resizer') };
}

const widthOf = (element) => element.evaluate((node) => node.getBoundingClientRect().width);
const settleLayout = (page) => page.evaluate(() => new Promise((resolve) =>
  requestAnimationFrame(() => requestAnimationFrame(resolve))));

test('double-click fits a stretched column without saving either preceding click', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  const { state, table, header, handle } = await installApplication(page);
  await expect.poll(() => widthOf(header)).toBeGreaterThan(1200);
  state.blockSave = true;
  try {
    await handle.dblclick();
    await expect.poll(() => state.saves.length).toBe(1);
    expect(state.saves[0].channel).toBeGreaterThanOrEqual(48);
    expect(state.saves[0].channel).toBeLessThan(1200);
  } finally {
    state.blockSave = false;
    state.releaseSave?.();
  }
  await expect(table).not.toHaveClass(/table-layout-busy/);
  // A fitted table intentionally fills its panel even when the saved content
  // width is smaller. Repeating the fit must save the same content width.
  await expect.poll(() => widthOf(header)).toBeGreaterThan(1200);
  await handle.dblclick();
  await expect.poll(() => state.saves.length).toBe(2);
  expect(state.saves[1]).toEqual(state.saves[0]);
  expect(state.fixture.pageErrors).toEqual([]);
});

test('row wrapping and viewport changes preserve a column drag until it finishes', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const { state, table, header, handle } = await installApplication(page);
  const wrapper = table.locator('..');
  const startingWidth = await widthOf(header);
  const initialHeight = (await wrapper.boundingBox()).height;
  await handle.scrollIntoViewIfNeeded();
  const bounds = await handle.boundingBox();
  const startingX = bounds.x + bounds.width / 2;
  const y = bounds.y + bounds.height / 2;
  await page.mouse.move(startingX, y);
  await page.mouse.down();
  try {
    await page.mouse.move(startingX - startingWidth + 110, y);
    await settleLayout(page);
    expect(await widthOf(header)).toBeCloseTo(110, 0);
    expect((await wrapper.boundingBox()).height).toBeGreaterThan(initialHeight);
    expect(state.saves).toEqual([]);
    await page.setViewportSize({ width: 1100, height: 900 });
    await settleLayout(page);
    expect(await widthOf(header)).toBeCloseTo(110, 0);
  } finally {
    await page.mouse.up();
  }
  await expect.poll(() => state.saves.length).toBe(1);
  expect(state.saves[0].channel).toBe(110);
  await expect(table).not.toHaveClass(/table-layout-busy/);
  await expect.poll(() => widthOf(header)).toBeGreaterThan(110);
  expect(state.fixture.pageErrors).toEqual([]);
});
