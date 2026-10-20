'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

async function openCatalog(page, { theme = 'light', obsoleteLayout = false } = {}) {
  let revision = 1;
  const writes = [];
  const preferences = structuredClone(defaults);
  preferences.appearance.theme = theme;
  if (obsoleteLayout) {
    const schema = ['select', 'name', 'frequency', 'status', 'auto-start', 'alias-list', 'actions'];
    preferences.tables['channel-catalog-admin-v2'] = { schema, column_order: schema,
      column_widths: Object.fromEntries(schema.map((column) => [column, 1200])),
      hidden_columns: ['alias-list'], collapsed_groups: [] };
  }
  const system = 'Regional Public Safety and Interoperability Communications Network';
  const base = {
    protocol_id: 'p25-phase1', protocol_label: 'P25 Phase 1', channel_kind: 'TRUNKED',
    system, site: 'Central', frequencies_hz: [851012500, 852012500],
    processing_state: 'STOPPED', auto_start_order: null, editable: true,
    alias_list_id: 1, alias_list_name: 'Regional P25',
    source: { frequencies_hz: [851012500, 852012500] }, settings: {},
    frequency_map: [], event_logs: [], recorders: [], auxiliary_decoders: []
  };
  const channels = [
    { ...base, configuration_id: 'active', name: 'Active Control',
      display_frequency_hz: 853012500, display_frequency_source: 'active-control', processing_state: 'RUNNING',
      auto_start_order: 3, remote_origin: { remote: true } },
    { ...base, configuration_id: 'last', name: 'Last Control',
      display_frequency_hz: 854012500, display_frequency_source: 'last-control' },
    { ...base, configuration_id: 'configured', name: 'Configured Control',
      display_frequency_hz: 851012500, display_frequency_source: 'configured' },
    { ...base, configuration_id: 'legacy', name: 'Legacy Control' },
    ...Array.from({ length: 43 }, (_, index) => ({ ...base,
      configuration_id: `saved-${index}`, name: `Saved channel ${String(index).padStart(2, '0')}` })),
    { ...base, configuration_id: 'duplicate', name: system, site: 'East and Lake Shore Simulcast' },
    { ...base, configuration_id: 'combined', name: `${system} · West Ridge`, site: 'West Ridge' },
    { ...base, configuration_id: 'beyond-page', name: 'ZZZ Island Control', site: 'Outer Island Simulcast' }
  ];
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    const respond = (data) => route.fulfill({ json: { data } });
    if (pathname === '/api/v1/auth/session') return respond({ configured: true, authenticated: true,
      username: 'operator', tier: 'admin', primary: true, csrf_token: 'fixture-token',
      capabilities: { 'admin-channels': true, 'admin-aliases': true } });
    if (pathname === '/api/v1/me/preferences') return route.fulfill({ json: { revision: 1, preferences } });
    if (pathname === '/api/v1/admin/channels') return respond({ revision, channels });
    if (pathname === '/api/v1/admin/channels/options') return respond({ revision, alias_lists: [], tuners: [] });
    if (pathname === '/api/v1/admin/channels/protocols') return respond({ profiles: [{
      id: 'p25-phase1', label: 'P25 Phase 1', channel_kind: 'TRUNKED', alias_family: 'P25',
      sections: [{ id: 'general', label: 'General', fields: [
        { path: 'name', label: 'Name', type: 'text', required: true }
      ] }]
    }] });
    const match = pathname.match(/^\/api\/v1\/admin\/channels\/([^/]+)$/);
    if (match) {
      const channel = channels.find((row) => row.configuration_id === match[1]);
      if (channel && route.request().method() === 'PUT') {
        const body = route.request().postDataJSON();
        writes.push(body);
        Object.assign(channel, body);
        revision += 1;
        return respond({ revision, configuration_ids: [channel.configuration_id] });
      }
      if (channel) return respond({ revision, edit_revision: `fixture-${revision}`, channel,
        processing_state: channel.processing_state });
    }
    return route.fulfill({ status: 404, json: { error: { message: 'Unavailable in this browser contract.' } } });
  });
  await page.goto('/app.html?view=channel-setup');
  return { channels, system, writes };
}

test('Channel Setup shows one active, last, or configured frequency with a backward-compatible fallback', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openCatalog(page);
  for (const [id, frequency, source] of [
    ['active', '853.01250', 'Active control'],
    ['last', '854.01250', 'Last control'],
    ['configured', '851.01250', 'Configured'],
    ['legacy', '851.01250', 'Configured']
  ]) {
    const cell = page.locator(`tr[data-id="${id}"] td[data-column="frequency"]`);
    await expect(cell).toBeVisible();
    await expect(cell).toContainText(frequency);
    await expect(cell).toContainText(source);
    await expect(cell).not.toContainText('852.01250');
    expect((await cell.textContent()).match(/\d{3}\.\d{5}/g)).toEqual([frequency]);
  }
});

test('saving a channel on a later page reveals its row and returns focus to its name', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const { writes } = await openCatalog(page);
  const row = page.locator('tr[data-id="beyond-page"]');
  await expect(row).toHaveCount(0);
  await expect(page.getByRole('navigation', { name: 'Channel pages', exact: true }))
    .toContainText('Channels 1–20 of 50');
  for (let index = 0; index < 2; index += 1) {
    await page.getByRole('button', { name: 'Next', exact: true }).click();
  }
  await expect(page.getByRole('navigation', { name: 'Channel pages', exact: true }))
    .toContainText('Channels 41–50 of 50');
  await expect(page.locator('.channel-catalog-table tr[data-id]')).toHaveCount(10);
  await row.getByRole('button', { name: 'ZZZ Island Control', exact: true }).click();
  const editor = page.getByRole('dialog', { name: 'Edit ZZZ Island Control', exact: true });
  await editor.getByRole('textbox', { name: 'Name', exact: true }).fill('ZZZ Island Control Updated');
  await editor.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(editor).toHaveCount(0);
  await expect(row.getByRole('button', { name: 'ZZZ Island Control Updated', exact: true })).toBeFocused();
  await expect(page.getByRole('navigation', { name: 'Channel pages', exact: true }))
    .toContainText('Channels 41–50 of 50');
  await expect(page.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
  expect(writes).toHaveLength(1);
});

test('Channel Setup searches site names beyond the first page and keeps duplicate-name sites visible', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const { system } = await openCatalog(page);
  const rows = page.locator('.channel-catalog-table tr[data-id]');
  await expect(rows).toHaveCount(20);
  await expect(page.locator('tr[data-id="beyond-page"]')).toHaveCount(0);
  const search = page.getByRole('searchbox');
  await search.fill('Outer Island Simulcast');
  await expect(rows).toHaveCount(1);
  await expect(page.locator('tr[data-id="beyond-page"] .channel-site-value')).toHaveText('Outer Island Simulcast');
  await expect(page.getByRole('navigation', { name: 'Channel pages', exact: true }))
    .toContainText('Channels 1–1 of 1');
  await search.fill(system);
  for (const [id, site] of [['duplicate', 'East and Lake Shore Simulcast'], ['combined', 'West Ridge']]) {
    const row = page.locator(`tr[data-id="${id}"]`);
    await expect(row).toBeVisible();
    await expect(row.locator('.channel-site-value')).toHaveText(site);
    await expect(row.locator('.channel-row-context')).toHaveCount(0);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

for (const theme of ['light', 'dark']) {
  test(`Channel Setup matches the quiet mockup and fits all columns with obsolete widths ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1024, height: 900 });
    await openCatalog(page, { theme, obsoleteLayout: true });
    const table = page.locator('.channel-catalog-table');
    await expect(table).toHaveAttribute('data-table-type', 'channel-catalog-admin-v3');
    for (const className of ['ui-data-table-quiet', 'ui-data-table-calm', 'ui-catalog-table']) {
      await expect(table).toHaveClass(new RegExp(`\\b${className}\\b`));
    }
    const heading = page.locator('.channel-setup-page-header');
    await expect(heading.getByRole('heading', { name: 'Channel Setup', exact: true })).toBeVisible();
    await expect(heading.getByRole('button')).toHaveCount(0);
    const creationActions = page.locator('.channel-catalog-overview > .channel-creation-actions');
    await expect(creationActions.getByRole('button', { name: 'New channel', exact: true })).toBeVisible();
    await expect(page.locator('.channel-admin-toolbar').getByRole('button', { name: 'New channel', exact: true }))
      .toHaveCount(0);
    const toolbar = page.locator('.channel-admin-toolbar');
    await expect(toolbar).toHaveClass(/ui-catalog-toolbar-flat/);
    await expect(toolbar).toHaveCSS('border-top-width', '0px');
    await expect(toolbar).toHaveCSS('box-shadow', 'none');
    const [headingBox, tableBox, toolbarBox, actionsBox] = await Promise.all([
      heading.boundingBox(), page.locator('.channel-catalog-table-wrap').boundingBox(), toolbar.boundingBox(),
      creationActions.boundingBox()
    ]);
    expect(Math.abs(headingBox.x - tableBox.x), 'Heading and table share the same left edge').toBeLessThanOrEqual(1);
    expect(Math.abs(headingBox.x - toolbarBox.x), 'Heading and filters share the same left edge').toBeLessThanOrEqual(1);
    expect(actionsBox.x, 'Creation actions align with the table').toBeCloseTo(tableBox.x, 0);
    expect(toolbarBox.y - (actionsBox.y + actionsBox.height), 'Creation actions sit immediately above the filters')
      .toBe(8);
    await expect(table.locator('thead th[data-column="status"]')).toContainText('State');
    await expect(table.locator('thead th[data-column="auto-start"]')).toContainText('Auto start');
    const geometry = await table.evaluate((element) => {
      const wrapper = element.closest('.ui-catalog-table-wrap');
      const bounds = wrapper.getBoundingClientRect();
      return { scrollWidth: wrapper.scrollWidth, clientWidth: wrapper.clientWidth,
        cells: [...element.querySelector('tr[data-id="active"]').cells].map((cell) => {
          const rect = cell.getBoundingClientRect(), style = getComputedStyle(cell);
          return { column: cell.dataset.column, visible: rect.width > 0,
            contained: rect.left >= bounds.left - 1 && rect.right <= bounds.right + 1,
            borderLeft: style.borderLeftWidth, borderRight: style.borderRightWidth };
        }) };
    });
    expect(geometry.scrollWidth).toBeLessThanOrEqual(geometry.clientWidth + 1);
    expect(geometry.cells).toHaveLength(7);
    for (const cell of geometry.cells) {
      expect(cell.visible, `${cell.column} stays visible`).toBe(true);
      expect(cell.contained, `${cell.column} fits the table`).toBe(true);
      expect(cell.borderLeft, `${cell.column} has no vertical gridline`).toBe('0px');
      expect(cell.borderRight, `${cell.column} has no vertical gridline`).toBe('0px');
    }
    const active = table.locator('tr[data-id="active"]');
    await expect(active.getByRole('button', { name: 'Active Control', exact: true }))
      .toHaveClass(/ui-catalog-primary/);
    await expect(active.locator('.channel-row-links')).toHaveClass(/ui-catalog-secondary-links/);
    await expect(active.locator('td[data-column="alias-list"] a')).toHaveClass(/ui-catalog-alias/);
    const status = active.locator('td[data-column="status"] .ui-status');
    await expect(status).toHaveText('Running');
    await expect(active.locator('td[data-column="status"] .ui-pill')).toHaveCount(0);
    await expect(status).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
    await expect(active.locator('.channel-origin-icon')).toBeVisible();
    await expect(active.locator('.channel-origin-icon svg')).toHaveCount(1);
    for (const label of ['Details', 'Live']) {
      await expect(active.getByRole('link', { name: label, exact: true })).toHaveClass(/ui-link-text/);
    }
    for (const control of ['EARLIER', 'LATER']) {
      const button = active.locator(`[data-channel-order-direction="${control}"]`);
      await expect(button).toHaveClass(/ui-button-quiet/);
      await expect(button).toHaveCSS('height', '32px');
    }
    const off = table.locator('tr[data-id="configured"] .channel-order-number');
    await expect(off).toHaveText('Off');
    const offGeometry = await off.evaluate((element) => {
      const range = document.createRange();
      range.selectNodeContents(element);
      const lines = [...range.getClientRects()];
      return { lines: new Set(lines.map((rect) => Math.round(rect.top))).size,
        contained: lines.every((rect) => rect.width <= element.getBoundingClientRect().width + 1) };
    });
    expect(offGeometry.lines, 'Off stays on one line between the arrows').toBe(1);
    expect(offGeometry.contained, 'The startup value fits without clipping').toBe(true);
    const site = table.locator('tr[data-id="duplicate"] .channel-site-value');
    await expect(site).toHaveText('East and Lake Shore Simulcast');
    await expect(site).toHaveCSS('white-space', 'normal');
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1024);
    await page.screenshot({ path: testInfo.outputPath(`channel-setup-quiet-${theme}-1024.png`), fullPage: true });
  });
}
