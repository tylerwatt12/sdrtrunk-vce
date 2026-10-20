'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

async function openCatalog(page) {
  let revision = 1;
  const writes = [];
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
      display_frequency_hz: 853012500, display_frequency_source: 'active-control', processing_state: 'RUNNING' },
    { ...base, configuration_id: 'last', name: 'Last Control',
      display_frequency_hz: 854012500, display_frequency_source: 'last-control' },
    { ...base, configuration_id: 'configured', name: 'Configured Control',
      display_frequency_hz: 851012500, display_frequency_source: 'configured' },
    { ...base, configuration_id: 'legacy', name: 'Legacy Control' },
    ...Array.from({ length: 27 }, (_, index) => ({ ...base,
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
      capabilities: { 'admin-channels': true } });
    if (pathname === '/api/v1/me/preferences') return route.fulfill({ json: { revision: 1, preferences: defaults } });
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
  for (let index = 0; index < 4; index += 1) {
    await page.getByRole('button', { name: 'Next', exact: true }).click();
  }
  await row.getByRole('button', { name: 'ZZZ Island Control', exact: true }).click();
  const editor = page.getByRole('dialog', { name: 'Edit ZZZ Island Control', exact: true });
  await editor.getByRole('textbox', { name: 'Name', exact: true }).fill('ZZZ Island Control Updated');
  await editor.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(editor).toHaveCount(0);
  await expect(row.getByRole('button', { name: 'ZZZ Island Control Updated', exact: true })).toBeFocused();
  expect(writes).toHaveLength(1);
});

test('Channel Setup searches site names beyond the first page and keeps duplicate-name sites visible', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const { system } = await openCatalog(page);
  const rows = page.locator('.channel-catalog-table tr[data-id]');
  await expect(rows).toHaveCount(8);
  await expect(page.locator('tr[data-id="beyond-page"]')).toHaveCount(0);
  const search = page.getByRole('searchbox');
  await search.fill('Outer Island Simulcast');
  await expect(rows).toHaveCount(1);
  await expect(page.locator('tr[data-id="beyond-page"] .channel-site-value')).toHaveText('Outer Island Simulcast');
  await search.fill(system);
  for (const [id, site] of [['duplicate', 'East and Lake Shore Simulcast'], ['combined', 'West Ridge']]) {
    const row = page.locator(`tr[data-id="${id}"]`);
    await expect(row).toBeVisible();
    await expect(row.locator('.channel-site-value')).toHaveText(site);
    await expect(row.locator('.channel-row-context')).toHaveCount(0);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
