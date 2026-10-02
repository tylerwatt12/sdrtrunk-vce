'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { expectMetricGridSpacing, expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');
const { expectBoxedFacts } = require('./fixtures/fact-geometry.cjs');

const systemKey = 'p25:00001:001';
const systemPath = `/api/v1/radio-systems/${encodeURIComponent(systemKey)}`;
const groupKey = 'v1-g-00001-001-1201';
const radioKey = 'v1-r-00001-001-30914';
const channelId = '11111111-1111-4111-8111-111111111111';
const systemRef = { kind: 'radio_system', key: systemKey };
const groupRef = { kind: 'talkgroup', radio_system_key: systemKey, identity_key: groupKey };
const systemName = 'County Public Safety and Regional Emergency Services Communications Network';
const aliasListName = 'County P25 and Regional Emergency Services Shared Alias List';
let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

async function mockEntities(page, theme) {
  const totals = {
    logical_call_count: 279_148, channel_observation_count: 281_002,
    recorded_logical_call_count: 88, stream_submitted_logical_call_count: 145_601,
    encrypted_logical_call_count: 1_844, active_observation_count: 136_317,
    join_observation_count: 36_060, denial_observation_count: 553,
    register_observation_count: 32_789
  };
  const identity = {
    radio_system_key: systemKey, protocol: 'P25', wacn: 1, system_id: 1,
    system_name: systemName, radio_system_entity_ref: systemRef,
    first_seen_ms: Date.UTC(2026, 8, 5, 11), last_seen_ms: Date.UTC(2026, 9, 1, 13),
    capabilities: { current_affiliations: true, radio_channel_presence: true,
      group_identities: true, radios: true, activity: true },
    ...totals
  };
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    const respond = (data) => route.fulfill({ json: { data } });
    if (pathname === '/api/v1/auth/session') {
      return respond({ configured: true, authenticated: true, username: 'operator', tier: 'admin',
        primary: true, capabilities: { radio: true, dashboard: true, 'csv-export': true,
          'admin-aliases': true, 'admin-configuration': true } });
    }
    if (pathname === '/api/v1/me/preferences') {
      return route.fulfill({ json: { revision: 1, preferences: {
        ...defaultPreferences, appearance: { theme, hue: null }
      } } });
    }
    if (pathname === '/api/v1/status') {
      return respond({ stats_logging: { summary_configured: true, summary_active: true,
        detailed_history_configured: true, detailed_history_active: true },
      database: { detailed_history_available: true } });
    }
    if (pathname === '/api/v1/channel-catalog') return respond({ revision: 1, channels: [] });
    if (pathname === '/api/v1/admin/scan-lists') return respond({ revision: 1, scan_lists: [
      { id: 1, name: 'County Dispatch', alias_count: 124, unmatched_alias_list_count: 2,
        default: true, published: true },
      { id: 2, name: 'City Services', alias_count: 38, unmatched_alias_list_count: 0, published: true }
    ] });
    if (pathname === systemPath) {
      return respond({ ...identity, channels: 1, talkgroups: 180, patch_groups: 0, radios: 1_004,
        affiliated_radios: 291, channel_names: 'North Simulcast', alias_lists: [{ id: 7, name: aliasListName }],
        action_counts: ['ACTIVE', 'JOIN', 'REGISTER', 'DENIAL'].map((action, index) =>
          ({ action, observation_count: [136_317, 36_060, 32_789, 553][index] })) });
    }
    if (pathname === `${systemPath}/channels`) {
      return route.fulfill({ json: { data: [{ configuration_id: channelId,
        name: 'North Simulcast', site_name: 'North', protocol: 'P25', rfss: 1, site_id: 2,
        entity_ref: { kind: 'channel', key: channelId } }],
      meta: { limit: 25, offset: 0, has_more: false, total_count: 1 } } });
    }
    if (pathname === `${systemPath}/group-identities/${groupKey}`) {
      return respond({ ...identity, native_id: 1201, group_identity_kind: 'talkgroup',
        alias_name: 'County Fire Dispatch', alias_group: 'Fire', radios: 88,
        affiliated_radios: 9, affiliated_channels: 1 });
    }
    if (pathname === `${systemPath}/group-identities/${groupKey}/activity`) {
      return respond({ totals, series: [] });
    }
    if (pathname === `${systemPath}/radios/${radioKey}`) {
      return respond({ ...identity, native_id: 30914, alias_name: 'Engine 12 Portable', groups: 4,
        affiliated_talkgroup_id: 1201, affiliated_talkgroup_alias_name: 'County Fire Dispatch',
        affiliated_talkgroup_entity_ref: groupRef });
    }
    return route.fulfill({ status: 404, json: { error: { message: 'Unavailable in this browser test' } } });
  });
}

test('Scan List details use shared counters and preserve help when selecting another list', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockEntities(page, 'dark');
  await page.goto('/app.html?view=scan-lists');
  const summary = page.locator('.scan-list-detail-facts');
  await expect(summary.locator('.ui-metric-label')).toHaveText([
    'Assigned aliases', 'Alias Lists routing unmatched calls'
  ]);
  await expect(summary.locator('strong')).toHaveText(['124', '2']);
  await expectMetricGridSpacing(summary, { inset: 0, sectionInset: false });
  await page.getByRole('button', { name: /City Services.*38 aliases/ }).click();
  await expect(summary.locator('strong')).toHaveText(['38', '0']);
  await expect(summary.locator('.ui-metric-detail')).toHaveText([
    'Existing Aliases explicitly included in this Scan List.',
    'Alias Lists set to send calls here when no talkgroup or patch-group Alias matches. ' +
      'Their existing Aliases are not automatically included.'
  ]);
  await expect(page.getByRole('link', { name: /Manage.*aliases/ })).toHaveAttribute('href', /scanListId=2/);
  await expectNoHorizontalOverflow(page);
});

for (const theme of ['light', 'dark']) {
  for (const width of [1280, 390]) {
    test(`entity stat cards retain one inset and drilldown links in ${theme} at ${width}px`, async ({ page }, testInfo) => {
      await page.setViewportSize({ width, height: 900 });
      await mockEntities(page, theme);
      const scope = `radio_system_key=${encodeURIComponent(systemKey)}`;

      await page.goto(`/app.html?view=radio-system&${scope}`);
      await expect(page.locator('.system-info-column .ui-metric-grid')).toHaveCount(4);
      await expect.poll(() => page.locator('html').getAttribute('data-theme'))
        .toBe(theme === 'dark' ? 'dark' : null);
      await expectMetricGridSpacing(page.locator('.system-info-column .ui-metric-grid'));
      const systemInfo = page.locator('.system-info-column .ui-section')
        .filter({ has: page.locator('.ui-section-title:text-is("System Info")') });
      await expectBoxedFacts(systemInfo.locator(':scope > dl.ui-facts'),
        page.locator('.system-info-column .ui-metric').first());
      await expect(systemInfo.locator('dt')).toHaveText(['Radio System', 'Alias Lists', 'First Seen', 'Last Seen']);
      await expect(page.getByRole('link', { name: aliasListName, exact: true }))
        .toHaveAttribute('href', /view=aliases.*list=7/);
      await systemInfo.getByRole('link', { name: aliasListName, exact: true }).focus();
      await expect(systemInfo.getByRole('link', { name: aliasListName, exact: true })).toBeFocused();
      await expect(page.getByRole('link', { name: 'North Simulcast', exact: true }))
        .toHaveAttribute('href', new RegExp(`view=channel.*configuration_id=${channelId}`));
      await expectNoHorizontalOverflow(page);
      await page.evaluate(() => window.scrollTo(0, 0));
      await page.screenshot({ path: testInfo.outputPath(`radio-system-stat-cards-${theme}-${width}.png`),
        fullPage: true });

      await page.goto(`/app.html?view=group-identity&${scope}&identity_key=${groupKey}`);
      await expect(page.locator('.entity-info-layout .ui-metric-grid')).toHaveCount(5);
      await expectMetricGridSpacing(page.locator('.entity-info-layout .ui-metric-grid'));
      await expectBoxedFacts(page.locator('.entity-info-layout .ui-section > dl.ui-facts'),
        page.locator('.entity-info-layout .ui-metric:not(.ui-metric-compact)').first());
      await expect(page.locator('.entity-info-layout .ui-facts a').first())
        .toHaveAttribute('href', new RegExp(`view=radio-system.*radio_system_key=${encodeURIComponent(systemKey)}`));
      const affiliation = page.locator('.ui-metric').filter({ hasText: 'Currently Affiliated' })
        .getByRole('link', { name: '9', exact: true });
      const destination = new URL(await affiliation.getAttribute('href'), page.url());
      expect(destination.searchParams.get('view')).toBe('group-identity');
      expect(destination.searchParams.get('identity_key')).toBe(groupKey);
      expect(destination.searchParams.get('tab')).toBe('radios');
      expect(destination.searchParams.get('affiliated')).toBe('true');
      await affiliation.focus();
      await expect(affiliation).toBeFocused();
      await expectNoHorizontalOverflow(page);

      await page.goto(`/app.html?view=radio&${scope}&identity_key=${radioKey}`);
      await expect(page.locator('.entity-info-standalone .ui-metric-grid')).toHaveCount(3);
      await expectMetricGridSpacing(page.locator('.entity-info-standalone .ui-metric-grid'));
      await expectBoxedFacts(page.locator('.entity-info-standalone .ui-section > dl.ui-facts'),
        page.locator('.entity-info-standalone .ui-metric').first());
      await expect(page.getByRole('link', { name: 'County Fire Dispatch', exact: true }))
        .toHaveAttribute('href', new RegExp(`view=group-identity.*identity_key=${groupKey}`));
      await expectNoHorizontalOverflow(page);
    });
  }
}
