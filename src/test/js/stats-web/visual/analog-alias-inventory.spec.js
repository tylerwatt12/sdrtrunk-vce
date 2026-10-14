'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;
test.beforeAll(async () => {
  defaultPreferences = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

async function installAnalogFixture(page) {
  const pageOf = rows => ({ rows, total_count: rows.length, limit: 100, offset: 0,
    has_more: false, next_offset: null });
  const channelId = '10000000-0000-4000-8000-000000000001';
  const channelRef = { kind: 'channel', key: channelId };
  const lists = [
    { alias_list_id: 1, name: 'Conventional', family: 'NBFM', alias_count: 2, assigned_channel_count: 1,
      capabilities: { observed_group_discovery: true } },
    { alias_list_id: 2, name: 'Empty Analog', family: 'NBFM', alias_count: 0, assigned_channel_count: 1 }
  ];
  const timestamp = Date.now() - 60_000;
  const observed = { protocol: 'am', topology: 'CONVENTIONAL', identity_origin: 'configured_assignment',
    configuration_id: channelId, channel_names: 'Air Operations', system_name: 'Airport',
    frequency_hz: 123_450_000, assigned_talkgroup: 17, group_identity_id: 17, native_id: 17,
    group_identity_kind: 'talkgroup', group_identity_kind_code: 1, match_kind: 'none',
    promotion_supported: true, logical_call_count: 2, first_seen_ms: timestamp, last_seen_ms: timestamp,
    entity_ref: channelRef, channel_entity_ref: channelRef };
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url()), pathname = url.pathname;
    if (pathname === '/api/v1/me/preferences') return route.fulfill({ json: {
      revision: 1, preferences: structuredClone(defaultPreferences)
    } });
    const listId = Number(pathname.match(/\/identities\/lists\/(\d+)/)?.[1] ||
      url.searchParams.get('alias_list_id') || 1);
    const list = lists.find(row => row.alias_list_id === listId);
    const data = pathname === '/api/v1/auth/session' ? {
      configured: true, authenticated: true, username: 'operator', tier: 'admin', primary: true,
      csrf_token: 'fixture', capabilities: { dashboard: true, radio: true, 'admin-aliases': true }
    } : pathname === '/api/v1/me/preferences' ? {
      revision: 1, preferences: structuredClone(defaultPreferences)
    } : pathname === '/api/v1/status' ? {
      stats_logging: { summary_configured: true, summary_active: true }, database: {}
    } : ['/api/v1/quality', '/api/v1/radio-systems'].includes(pathname) ? pageOf([]) :
      pathname === '/api/v1/channel-catalog' ? { revision: 1, channels: [
        { configuration_id: channelId, name: 'Air Operations', alias_list_id: 1,
          decoder: 'AM', channel_kind: 'CONVENTIONAL' }
      ] } :
      ['/api/v1/alias-lists', '/api/v1/identities/lists'].includes(pathname) ? pageOf(lists) :
      pathname === '/api/v1/admin/alias-lists' ? { revision: 1, alias_lists: lists } :
      pathname === '/api/v1/admin/aliases/options' ? { revision: 1, alias_list: list,
        alias_lists: lists, scan_lists: [], streams: [], group_names: [], icon_names: [], matchers: [
          { type: 'talkgroup', protocol: 'am', label: 'AM Talkgroup', fields: ['value'], minimum: 1, maximum: 65535 },
          { type: 'talkgroup', protocol: 'nbfm', label: 'NBFM Talkgroup', fields: ['value'], minimum: 1, maximum: 65535 }
        ] } : pathname.endsWith('/observed-group-identities') ? pageOf([observed]) :
      pathname.endsWith('/overview') ? { alias_list: list,
        totals: { configured_alias_count: list.alias_count, activity_eligible_alias_count: listId === 1 ? 1 : 0,
          active_alias_count: listId === 1 ? 1 : 0, logical_call_count: 6,
          matched_logical_call_count: listId === 1 ? 4 : 0, unmatched_logical_call_count: listId === 1 ? 2 : 6 },
        correlated_radio_system_count: 0, channels: [{ configuration_id: channelId,
          name: 'Air Operations', protocol: 'am', channel_entity_ref: channelRef }] } :
      pathname.endsWith('/unassigned') ? pageOf([observed]) :
      pathname.endsWith('/aliases') && pathname.startsWith('/api/v1/identities/') ? pageOf(listId === 2 ? [] : [
        { alias_id: 21, name: 'County Dispatch', protocol: 'nbfm', identity_origin: 'configured_assignment',
          identity_type: 'talkgroup', matcher_type: 'talkgroup', identity_display: '1', value: 1,
          metrics_state: 'observed', logical_call_count: 4, last_evidence_ms: timestamp, entity_ref: channelRef },
        { alias_id: 22, name: 'Local squelch', identity_type: 'other', matcher_label: 'DCS',
          identity_display: 'D023N', metrics_state: 'unsupported' }
      ]) : null;
    await route.fulfill({ status: data ? 200 : 404, json: data ? { data } :
      { error: { message: 'Unavailable in this fixture' } } });
  });
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Analog alias inventory and discovery stay channel scoped in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await installAnalogFixture(page);
    await page.goto('/app.html?view=dashboard&directory_view=coverage&alias_list_id=1');
    const inventory = page.locator('.alias-coverage');
    await expect(inventory.getByText('County Dispatch', { exact: true })).toBeVisible();
    await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
    await expect(inventory.getByText('Local squelch', { exact: true })).toBeVisible();
    await expect(inventory.getByText('Not tracked')).toHaveCount(3);
    await expect(inventory.getByText('Total calls', { exact: true })).toBeVisible();
    await expect(inventory.getByText('Matched calls', { exact: true })).toBeVisible();
    await expect(inventory.locator('th[data-column="signals"]')).toHaveCount(0);
    const aliasLink = inventory.getByRole('link', { name: 'County Dispatch', exact: true });
    await expect(aliasLink).toHaveAttribute('href', /view=channel/);
    expect(await aliasLink.getAttribute('href')).not.toContain('tab=groups');
    await inventory.getByRole('button', { name: 'Unmatched assignments', exact: true }).click();
    await expect(inventory.getByText('Air Operations', { exact: true })).toHaveCount(2);
    await expect(inventory.locator('th[data-column="identity"]')).toContainText('Assigned number');
    expect(await inventory.locator('tbody a').evaluateAll(links => links.every(link =>
      !link.href.includes('tab=groups')))).toBe(true);
    await page.screenshot({ path: `build/playwright-results/analog-inventory-${theme}-${width}.png`, fullPage: true });
    await inventory.getByRole('combobox', { name: 'Alias List' }).selectOption('2');
    await inventory.getByRole('button', { name: 'Configured aliases', exact: true }).click();
    await expect(inventory.getByText('No configured aliases match these filters')).toBeVisible();
    await expect(inventory.getByText('Unmatched calls', { exact: true })).toBeVisible();

    await page.goto('/app.html?view=aliases&list=1&aliasTab=discover');
    const discover = page.locator('.observed-group-identity-section');
    await expect(discover.locator('.ui-section-title')).toContainText('Unmatched assignments');
    await expect(discover.getByText('Assigned numbers from received calls with no exact or range alias in this list.',
      { exact: true })).toBeVisible();
    await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
    await expect(discover.locator('th[data-column="group-identity-id"]')).toContainText('Assigned number');
    await expect(discover.locator('th[data-column="signaling"]')).toHaveCount(0);
    const channelLink = discover.getByRole('link', { name: 'Air Operations', exact: true });
    await expect(channelLink).toHaveAttribute('href', /view=channel/);
    expect(await channelLink.getAttribute('href')).not.toContain('tab=groups');
    await discover.getByRole('button', { name: 'Create Alias' }).click();
    const modal = page.getByRole('dialog');
    await expect(modal).toBeVisible();
    await modal.getByRole('tab', { name: 'Identifier', exact: true }).click();
    await expect(modal.locator('select[name="matcherType"] option:checked')).toHaveText('AM Talkgroup');
    await expect(modal.locator('input[name="matcher-value"]')).toHaveValue('17');
    await page.screenshot({ path: `build/playwright-results/analog-discover-${theme}-${width}.png`, fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });
}
