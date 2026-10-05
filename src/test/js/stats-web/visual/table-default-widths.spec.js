'use strict';

const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication, systemKey, channelId, radioKey, systemName } =
  require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const channelName = 'North County Regional Emergency Services Simulcast';
const aliasName = 'Regional Fire and Emergency Medical Dispatch';
const aliasDescription = 'Countywide fire dispatch and regional incident coordination';
const aliasGroup = 'Emergency response and mutual aid';
const listName = 'County Public Safety and Regional Services';
const observedAt = Date.parse('2026-10-01T12:00:00Z');
const activityQuery = `view=radio&radio_system_key=${encodeURIComponent(systemKey)}` +
  `&identity_key=${radioKey}&tab=activity`;
const pageOf = (rows) => ({ rows, total_count: rows.length, limit: 100, offset: 0,
  has_more: false, next_offset: null });

async function installTableApplication(page, theme) {
  const fixture = await installSiteStyleApplication(page, theme);
  const preferencesFile = path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js');
  const preferences = structuredClone((await import(pathToFileURL(preferencesFile).href)).defaults);
  preferences.appearance.theme = theme;
  const state = { preferences, revision: 1, savedProfiles: [], fixture };
  const alias = { alias_id: 101, alias_list_id: 1, alias_list_name: listName,
    name: aliasName, description: aliasDescription, group: aliasGroup,
    matcher_type: 'TALKGROUP', matcher_label: 'P25 talkgroup', family: 'P25',
    identifier_display: 'TG 1201', logical_call_count: 1487, signaling_observation_count: 328,
    last_evidence_ms: observedAt, recordable: true, overlap_count: 0 };
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = decodeURIComponent(new URL(request.url()).pathname);
    const respond = (data) => route.fulfill({ json: { data } });
    if (pathname === '/api/v1/me/preferences') {
      if (request.method() === 'PUT') {
        state.preferences = request.postDataJSON();
        state.savedProfiles.push(structuredClone(state.preferences));
        state.revision += 1;
      }
      return route.fulfill({ json: { revision: state.revision, preferences: state.preferences } });
    }
    if (pathname === '/api/v1/activity') return respond({ ...pageOf([{
      id: 1, observed_at_ms: observedAt, action: 'REGISTER', event_type: 'REGISTER_ESN',
      protocol: 'P25', channel_kind: 'TRUNKED_SITE', radio_system_key: systemKey,
      system_name: systemName, configuration_id: channelId, name: channelName,
      frequency_hz: 851012500, lcn: '0-1234', timeslot: 2,
      entity_ref: { kind: 'channel', key: channelId },
      source_radio_id: 1234567, source_native_id: 1234567,
      source_canonical_identity: { wacn: 0x00002, system_id: 0x002, subscriber_id: 1234567 },
      source_identity_key: 'v1-r-00002-002-1234567',
      source_entity_ref: { kind: 'radio', radio_system_key: systemKey,
        identity_key: 'v1-r-00002-002-1234567' },
      source_alias_name: 'Engine 12 Portable - Regional Incident Command',
      source_talker_alias: 'Engine 12 North County', target_id: 1201, target_native_id: 1201,
      target_identity_key: 'v1-g-00001-001-1201', target_kind: 'talkgroup',
      target_entity_ref: { kind: 'talkgroup', radio_system_key: systemKey,
        identity_key: 'v1-g-00001-001-1201' }, target_alias_name: aliasName
    }]), watermark_id: 1, next_after_id: 1 });
    if (pathname === '/api/v1/aliases') return respond(pageOf([alias]));
    if (pathname === '/api/v1/admin/retained-statistics/results') return route.fulfill({ json: {
      data: [{ label: '1234567', alias_name: aliasName, native_id: 1234567,
        detail: '00002.002.1234567', alias_list_id: 1, alias_list_name: listName,
        alias_group: aliasGroup, logical_call_count: 1487, last_seen_ms: observedAt,
        protocol: 'P25', entity_ref: { kind: 'radio', radio_system_key: systemKey,
          identity_key: radioKey }, target: { kind: 'scoped_data', source_kind: 'radio_system',
          source_key: systemKey, data_type: 'radios', record_key: '1234567', parts: ['summary'] } }],
      meta: { limit: 25, offset: 0, has_more: false, total_count: 1 }
    } });
    return route.fallback();
  });
  return state;
}

async function expectTablePage(page, state, table) {
  await expect(table).toBeVisible();
  await expect(table.locator('tbody tr')).toHaveCount(1);
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  await expectNoHorizontalOverflow(page);
  expect(state.fixture.pageErrors).toEqual([]);
  expect(state.fixture.unexpected).toEqual([]);
}

async function expectReadableCell(table, id, minimumWidth, maximumLines = 3) {
  const cell = table.locator(`tbody [data-column="${id}"]`).first();
  await expect(cell).toBeVisible();
  const geometry = await cell.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    const inner = element.querySelector('.identity-summary, a') || element;
    const textBounds = [];
    const walker = document.createTreeWalker(inner, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      if (!walker.currentNode.textContent.trim()) continue;
      const text = document.createRange();
      text.selectNodeContents(walker.currentNode);
      textBounds.push(...[...text.getClientRects()].filter((rect) => rect.width > 0 && rect.height > 0));
    }
    const tops = new Set(textBounds.map((rect) => Math.round(rect.top)));
    return { width: bounds.width, overflow: element.scrollWidth > element.clientWidth + 1,
      lines: tops.size };
  });
  expect(geometry.width, `${id} keeps room for its descriptive value`).toBeGreaterThanOrEqual(minimumWidth - 1);
  expect(geometry.overflow, `${id} content stays within its cell`).toBe(false);
  expect(geometry.lines, `${id} avoids a tall stack of wrapped words`).toBeLessThanOrEqual(maximumLines);
}

for (const presentation of [
  { name: 'light 1280 desktop', theme: 'light', viewport: { width: 1280, height: 900 } },
  { name: 'dark 1920 desktop', theme: 'dark', viewport: { width: 1920, height: 1080 } }
]) {
  test(`actual Activity defaults fit long channel context at ${presentation.name}`, async ({ page }, testInfo) => {
    await page.setViewportSize(presentation.viewport);
    const state = await installTableApplication(page, presentation.theme);
    await page.goto(`/app.html?${activityQuery}`);
    const table = page.locator('table[data-table-type="activity"]');
    await expectTablePage(page, state, table);
    const channel = table.locator('tbody [data-column="channel"]');
    await expect(channel).toContainText(channelName);
    await expect(channel).toContainText('851.01250 MHz · LCN 0-1234 · Slot 2');
    await expectReadableCell(table, 'channel', 320, 4);
    await expectReadableCell(table, 'source', 220, 1);
    await expect(table.locator('tbody [data-column="source"]')).toContainText('00002.002.1234567');
    const stack = channel.locator('.activity-cell-action-content');
    await expect(stack).toHaveCSS('display', 'grid');
    const contextPosition = await stack.evaluate((element) => {
      const context = element.querySelector('.identity-summary-context').getBoundingClientRect();
      const primary = document.createRange();
      primary.setStart(element, 0);
      primary.setEndBefore(element.querySelector('.identity-summary-context'));
      return { primaryBottom: primary.getBoundingClientRect().bottom, contextTop: context.top };
    });
    expect(contextPosition.contextTop).toBeGreaterThanOrEqual(contextPosition.primaryBottom - 1);
    expect((await table.locator('tbody tr').boundingBox()).height).toBeLessThanOrEqual(100);
    if (presentation.theme === 'light') {
      await channel.scrollIntoViewIfNeeded();
      await table.locator('..').screenshot({ path: testInfo.outputPath('activity-defaults.png') });
    }
  });
}

test('Activity saved widths survive refresh and Reset restores the readable defaults', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const state = await installTableApplication(page, 'light');
  await page.goto(`/app.html?${activityQuery}`);
  const table = page.locator('table[data-table-type="activity"]');
  await expectTablePage(page, state, table);
  const handle = table.locator('thead [data-column="channel"] .column-resizer');
  await handle.focus();
  await handle.press('ArrowRight');
  await expect.poll(() => state.preferences.tables.activity?.column_widths.channel).toBe(330);
  await page.reload();
  await expect(table.locator('thead [data-column="channel"]')).toHaveCSS('width', '330px');
  await table.locator('..').locator('..').getByRole('button', { name: 'Choose table columns' }).click();
  await page.getByRole('button', { name: 'Reset this table', exact: true }).click();
  await expect.poll(() => state.preferences.tables.activity).toBeUndefined();
  await expect(table.locator('thead [data-column="channel"]')).toHaveCSS('width', '320px');
  await page.reload();
  await expectReadableCell(table, 'channel', 320, 4);
  expect(state.savedProfiles).toHaveLength(2);
});

test('actual alias modes keep descriptive columns readable in light desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const state = await installTableApplication(page, 'light');
  for (const mode of ['configure', 'activity', 'custom', 'scan-list-members']) {
    await page.goto(`/app.html?view=aliases&${mode === 'scan-list-members' ?
      'scanListId=1' : `list=1&aliasTab=${mode}`}`);
    const table = page.locator(`table[data-table-type="${mode === 'scan-list-members' ?
      'alias-scan-list-members' : `alias-editor-${mode}`}"]`);
    await expectTablePage(page, state, table);
    await expectReadableCell(table, 'alias', 180, 3);
    await expectReadableCell(table, 'description', 250, 3);
    await expectReadableCell(table, 'group', 180, 3);
    await expect(table.locator('tbody [data-column="alias"]')).toContainText(aliasName);
    if (mode === 'scan-list-members') await expectReadableCell(table, 'alias-list', 210, 3);
    expect((await table.locator('tbody tr').boundingBox()).height).toBeLessThanOrEqual(100);
  }
});

test('actual retained identity defaults fit names and their context in dark desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const state = await installTableApplication(page, 'dark');
  await page.goto('/app.html?view=admin&tab=retained-statistics');
  await page.getByRole('combobox', { name: 'Radio system', exact: true }).selectOption(systemKey);
  await page.getByRole('button', { name: 'Radio IDs', exact: true }).click();
  const table = page.locator('table[data-table-type="retained-statistics-v3.radio_system.radios"]');
  await expectTablePage(page, state, table);
  await expectReadableCell(table, 'item', 320, 4);
  await expectReadableCell(table, 'alias-list', 210, 3);
  await expectReadableCell(table, 'alias-group', 180, 3);
  await expect(table.locator('tbody [data-column="item"]')).toContainText('00002.002.1234567');
  expect((await table.locator('tbody tr').boundingBox()).height).toBeLessThanOrEqual(100);
});

test('long Activity and retained identity values remain contained on a dark phone', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await installTableApplication(page, 'dark');
  await page.goto(`/app.html?${activityQuery}`);
  const activity = page.locator('table[data-table-type="activity"]');
  await expectTablePage(page, state, activity);
  await expectReadableCell(activity, 'channel', 320, 4);
  await page.goto('/app.html?view=admin&tab=retained-statistics');
  await page.getByRole('combobox', { name: 'Radio system', exact: true }).selectOption(systemKey);
  await page.getByRole('button', { name: 'Radio IDs', exact: true }).click();
  const retained = page.locator('table[data-table-type="retained-statistics-v3.radio_system.radios"]');
  await expectTablePage(page, state, retained);
  await expect(retained).toHaveAttribute('data-mobile-cards', 'true');
  await expect(retained.locator('tbody [data-column="item"]')).toContainText(aliasName);
  await expectNoHorizontalOverflow(page);
});
