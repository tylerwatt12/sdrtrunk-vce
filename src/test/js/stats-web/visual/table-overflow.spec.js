'use strict';

const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication, systemKey, radioKey, channelId } =
  require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const activityQuery = `view=radio&radio_system_key=${encodeURIComponent(systemKey)}` +
  `&identity_key=${radioKey}&tab=activity`;

async function installApplication(page, theme = 'light') {
  const fixture = await installSiteStyleApplication(page, theme);
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  const preferences = structuredClone((await import(pathToFileURL(file).href)).defaults);
  preferences.appearance.theme = theme;
  const state = { fixture, preferences, revision: 1 };
  await page.route('**/api/v1/me/preferences', async (route) => {
    if (route.request().method() === 'PUT') {
      state.preferences = route.request().postDataJSON();
      state.revision += 1;
    }
    await route.fulfill({ json: { revision: state.revision, preferences: state.preferences } });
  });
  await page.route('**/api/v1/activity**', (route) => {
    if (new URL(route.request().url()).pathname !== '/api/v1/activity') return route.fallback();
    return route.fulfill({ json: { data: {
    rows: [{ id: 1, observed_at_ms: Date.parse('2026-10-01T12:00:00Z'),
      action: 'REGISTER', event_type: 'REGISTER_ESN', protocol: 'P25', channel_kind: 'TRUNKED_SITE',
      radio_system_key: systemKey, configuration_id: channelId,
      name: 'North County Regional Emergency Services Simulcast',
      frequency_hz: 851012500, lcn: '0-1234', timeslot: 2,
      entity_ref: { kind: 'channel', key: channelId }, source_radio_id: 30914,
      source_native_id: 30914, source_identity_key: radioKey,
      source_alias_name: 'Engine 4 Portable - Regional Incident Command',
      source_talker_alias: 'Engine 4 North County', target_id: 1201, target_native_id: 1201,
      target_identity_key: 'v1-g-00001-001-1201', target_kind: 'talkgroup',
      target_alias_name: 'Regional Fire and Emergency Medical Dispatch' }],
    total_count: 1, limit: 100, offset: 0, has_more: false, next_offset: null,
    watermark_id: 1, next_after_id: 1
    } } });
  });
  return state;
}

function tableParts(page, type) {
  const table = page.locator(`table[data-table-type="${type}"]`);
  const wrapper = table.locator('..');
  const root = wrapper.locator('..');
  return { table, wrapper, root, controls: root.locator('.ui-table-overflow-controls'),
    left: root.getByRole('button', { name: 'Scroll table left', exact: true }),
    right: root.getByRole('button', { name: 'Scroll table right', exact: true }) };
}

const position = (wrapper) => wrapper.evaluate((element) => ({ left: element.scrollLeft,
  width: element.clientWidth, maximum: element.scrollWidth - element.clientWidth }));

async function expectBoundary(parts, side) {
  await expect(parts.controls).toBeVisible();
  await expect(parts[side]).toBeDisabled();
  await expect(parts[side === 'left' ? 'right' : 'left']).toBeEnabled();
}

async function expectCuesAtViewport(wrapper) {
  const result = await wrapper.evaluate((element) => {
    const viewport = element.getBoundingClientRect();
    const visible = [...element.querySelectorAll('.ui-table-overflow-edge')].filter((cue) => !cue.hidden);
    return visible.map((cue) => {
      const bounds = cue.getBoundingClientRect();
      return { horizontal: cue.classList.contains('ui-table-overflow-edge-left') ?
        Math.abs(bounds.left - viewport.left - element.clientLeft) :
        Math.abs(bounds.right - viewport.left - element.clientLeft - element.clientWidth),
      vertical: Math.abs(bounds.top - viewport.top - element.clientTop) };
    });
  });
  expect(result.length).toBeGreaterThan(0);
  for (const cue of result) {
    expect(cue.horizontal, 'edge cue follows the visible horizontal boundary').toBeLessThanOrEqual(1);
    expect(cue.vertical, 'edge cue stays at the scroll viewport top').toBeLessThanOrEqual(1);
  }
}

async function hideColumns(parts, page, ids = null) {
  await parts.root.getByRole('button', { name: 'Choose table columns' }).click();
  const chooser = page.locator('.table-layout-panel:visible');
  const columnIds = ids || await chooser.getByRole('checkbox').evaluateAll((elements) =>
    elements.map((element) => element.dataset.layoutColumnId));
  for (const id of columnIds) {
    const checkbox = chooser.locator(`input[data-layout-column-id="${id}"]`);
    if (await checkbox.isEnabled() && await checkbox.isChecked()) {
      const previousCount = await parts.table.locator('thead th').count();
      await checkbox.uncheck();
      await expect(parts.table.locator('thead th')).toHaveCount(previousCount - 1);
    }
  }
  return chooser;
}

test('Activity arrows reveal clipped columns, track both ends, and preserve the native scrollbar', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const state = await installApplication(page);
  await page.goto(`/app.html?${activityQuery}`);
  const parts = tableParts(page, 'activity');
  await expect(parts.table.locator('tbody tr[data-id="1"]')).toHaveCount(1);
  await expect(parts.table.locator('tbody [data-column="source-alias"]')).toContainText('Engine 4 Portable');
  await expectBoundary(parts, 'left');
  await expect(parts.wrapper).toHaveCSS('overflow-x', 'auto');
  const initial = await position(parts.wrapper);
  await parts.right.click();
  await expect.poll(() => parts.wrapper.evaluate((element) => element.scrollLeft))
    .toBeGreaterThanOrEqual(Math.min(initial.maximum, initial.width * 0.7) - 1);
  await expectCuesAtViewport(parts.wrapper);
  await parts.wrapper.evaluate((element) => { element.scrollLeft = element.scrollWidth; });
  await expectBoundary(parts, 'right');
  await expectCuesAtViewport(parts.wrapper);
  await parts.left.click();
  await expect.poll(() => parts.wrapper.evaluate((element) => element.scrollLeft)).toBeLessThan(initial.maximum);
  await parts.wrapper.evaluate((element) => { element.scrollLeft = 0; });
  await expectBoundary(parts, 'left');
  await expectNoHorizontalOverflow(page);
  expect(state.fixture.pageErrors).toEqual([]);
  await parts.root.screenshot({ path: testInfo.outputPath('activity-overflow-controls.png') });
});

test('table keyboard scrolling leaves column-resizer arrows available and tracks changed layouts', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const state = await installApplication(page, 'dark');
  await page.goto(`/app.html?${activityQuery}`);
  const parts = tableParts(page, 'activity');
  await expectBoundary(parts, 'left');
  await parts.wrapper.focus();
  await parts.wrapper.press('ArrowRight');
  await expect.poll(() => parts.wrapper.evaluate((element) => element.scrollLeft)).toBeGreaterThan(0);
  const handle = parts.table.locator('thead [data-column="channel"] .column-resizer');
  await handle.scrollIntoViewIfNeeded();
  await handle.focus();
  const before = await position(parts.wrapper);
  await handle.press('ArrowRight');
  await expect.poll(() => state.preferences.tables.activity?.column_widths.channel).toBe(330);
  expect((await position(parts.wrapper)).left).toBe(before.left);
  await hideColumns(parts, page, ['source-alias', 'source-ota-alias', 'target-alias']);
  await page.keyboard.press('Escape');
  await page.setViewportSize({ width: 2560, height: 900 });
  await expect(parts.controls).toBeHidden();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(parts.controls).toBeVisible();
  await expectNoHorizontalOverflow(page);
});

test('choosing fewer columns hides overflow actions and rebuilding a table creates one control group', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await installApplication(page);
  await page.goto(`/app.html?${activityQuery}`);
  const parts = tableParts(page, 'activity');
  await expectBoundary(parts, 'left');
  const chooser = await hideColumns(parts, page);
  await expect(parts.controls).toBeHidden();
  await chooser.getByRole('button', { name: 'Reset this table', exact: true }).click();
  await expect(parts.controls).toHaveCount(1);
  await expect(parts.controls).toBeVisible();
  await page.keyboard.press('Escape');
  await page.getByRole('navigation', { name: 'Section navigation' }).getByRole('link', { name: 'Info', exact: true }).click();
  await expect(parts.controls).toHaveCount(0);
  await page.getByRole('navigation', { name: 'Section navigation' }).getByRole('link', { name: 'Activity', exact: true }).click();
  await expect(parts.controls).toHaveCount(1);
  await expect(parts.wrapper.locator('.ui-table-overflow-cues')).toHaveCount(1);
  await expectBoundary(parts, 'left');
});

test('phone card tables hide scrolling controls while plain Activity keeps them', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await installApplication(page, 'dark');
  await page.route('**/api/v1/admin/retained-statistics/results**', (route) => route.fulfill({ json: {
    data: [{ label: '30914', alias_name: 'Engine 4', native_id: 30914,
      detail: '00001.001.30914', alias_list_name: 'County Public Safety',
      logical_call_count: 1487, last_seen_ms: Date.parse('2026-10-01T12:00:00Z'), protocol: 'P25',
      target: { kind: 'scoped_data', source_kind: 'radio_system', source_key: systemKey,
        data_type: 'radios', record_key: '30914', parts: ['summary'] } }],
    meta: { limit: 25, offset: 0, has_more: false, total_count: 1 }
  } }));
  await page.goto('/app.html?view=admin&tab=retained-statistics');
  await page.getByRole('combobox', { name: 'Radio system', exact: true }).selectOption(systemKey);
  await page.getByRole('button', { name: 'Radio IDs', exact: true }).click();
  const cards = page.locator('table[data-table-type="retained-statistics-v3.radio_system.radios"]');
  await expect(cards).toBeVisible();
  await expect(cards.locator('..').locator('..').locator('.ui-table-overflow-controls')).toBeHidden();
  await page.goto(`/app.html?${activityQuery}`);
  await expectBoundary(tableParts(page, 'activity'), 'left');
  await expectNoHorizontalOverflow(page);
  expect(state.fixture.pageErrors).toEqual([]);
});

test('Live streamed row updates keep the horizontal position and bounded pane height', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const state = await installApplication(page);
  let eventSubscription;
  await page.addInitScript(({ configurationId }) => {
    const originalFetch = window.fetch.bind(window);
    const frame = (topic, event, data) => {
      const payload = new TextEncoder().encode(JSON.stringify({ event, data }));
      const bytes = new Uint8Array(16 + payload.length), header = new DataView(bytes.buffer);
      header.setUint32(0, 0x534c4d58); header.setUint8(4, 2); header.setUint8(5, 1);
      header.setUint16(6, topic); header.setUint32(8, payload.length); bytes.set(payload, 16);
      return bytes;
    };
    window.fetch = (input, options) => {
      const url = new URL(typeof input === 'string' ? input : input.url, location.href);
      if (url.pathname !== '/api/v1/live/multiplex') return originalFetch(input, options);
      return Promise.resolve(new Response(new ReadableStream({ start(controller) {
        window.sendTableOverflowFrame = (topic, event, data) => controller.enqueue(frame(topic, event, data));
        controller.enqueue(frame(0, 'ready', { client_id: url.searchParams.get('client_id') }));
        controller.enqueue(frame(1, 'snapshot', { revision: 1, tables: [{
          table_id: configurationId, configuration_id: configurationId, title: 'County Simulcast',
          channel_running: true, rows: [{ key: 'county-voice', configuration_id: configurationId,
            lcn: 2, frequency_hz: 851037500, status: 'CALL', tags: ['VOICE'],
            activation_order: 1, talkgroup: 1201, talkgroup_alias: 'Fire Dispatch' }] }] }));
      } }), { status: 200 }));
    };
  }, { configurationId: channelId });
  await page.route('**/api/v1/live/multiplex/control', async (route) => {
    const subscriptions = route.request().postDataJSON()?.subscriptions;
    if (subscriptions?.decode_events) eventSubscription = subscriptions.decode_events;
    await route.fulfill({ json: { data: { accepted: true } } });
  });
  await page.goto(`/app.html?view=live&channel=${channelId}`);
  await page.getByRole('button', { name: 'Change audio player size', exact: true }).press('Home');
  await page.locator('tr[data-id="county-voice"]').click();
  await expect.poll(() => eventSubscription).toBeTruthy();
  await page.evaluate((subscription) => window.sendTableOverflowFrame(2, 'source_change', subscription), eventSubscription);
  const event = { event_id: 1, configuration_id: channelId, frequency_hz: 851037500,
    time_start_ms: Date.parse('2026-10-01T12:00:00Z'), duration_ms: 1200, event_type: 'GROUP_VOICE',
    event_label: 'Group call', category: 'VOICE', protocol: 'P25', from_aliases: 'Engine 4',
    to_aliases: 'Fire Dispatch', channel: 'County Simulcast', details: 'Voice grant' };
  await page.evaluate((value) => {
    for (let eventId = 1; eventId <= 80; eventId += 1) {
      window.sendTableOverflowFrame(2, 'decode_event', { ...value, event_id: eventId });
    }
  }, event);
  const table = page.locator('table[data-table-type="live-events"]');
  const wrapper = table.locator('..');
  await expect(table.locator('tbody tr')).toHaveCount(80);
  const controls = page.locator('.live-detail-columns .ui-table-overflow-controls').first();
  await expect(controls).toBeVisible();
  const start = await position(wrapper);
  await controls.getByRole('button', { name: 'Scroll table right' }).click();
  await expect.poll(async () => Math.abs((await position(wrapper)).left -
    Math.min(start.maximum, start.width * 0.8))).toBeLessThanOrEqual(1);
  const before = await position(wrapper);
  const pane = page.locator('.live-details');
  const height = (await pane.boundingBox()).height;
  await wrapper.evaluate((element) => { element.scrollTop = 150; });
  await expect.poll(() => wrapper.evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
  await expectCuesAtViewport(wrapper);
  await page.evaluate((value) => window.sendTableOverflowFrame(2, 'decode_event', value),
    { ...event, event_id: 81, details: 'A later streamed grant' });
  await expect(table.locator('tbody tr')).toHaveCount(81);
  expect((await position(wrapper)).left).toBe(before.left);
  expect((await pane.boundingBox()).height).toBe(height);
  await expect(controls).toHaveCount(1);
  await expect(wrapper.locator('.ui-table-overflow-cues')).toHaveCount(1);
  await expectNoHorizontalOverflow(page);
  expect(state.fixture.pageErrors).toEqual([]);
});
