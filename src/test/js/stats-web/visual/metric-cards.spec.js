'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { expectMetricGridSpacing, expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const systemKey = 'p25:00001:001';
const systemPath = `/api/v1/radio-systems/${encodeURIComponent(systemKey)}`;
const groupKey = 'v1-g-00001-001-1201';
const radioKey = 'v1-r-00001-001-30914';
const channelId = '11111111-1111-4111-8111-111111111111';
const systemRef = { kind: 'radio_system', key: systemKey };
const groupRef = { kind: 'talkgroup', radio_system_key: systemKey, identity_key: groupKey };
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
    system_name: 'County Public Safety', radio_system_entity_ref: systemRef,
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
          'admin-aliases': true } });
    }
    if (pathname === '/api/v1/me/preferences') {
      return route.fulfill({ json: { revision: 1, preferences: {
        ...defaultPreferences, appearance: { theme }
      } } });
    }
    if (pathname === '/api/v1/status') {
      return respond({ stats_logging: { summary_configured: true, summary_active: true,
        detailed_history_configured: true, detailed_history_active: true },
      database: { detailed_history_available: true } });
    }
    if (pathname === '/api/v1/channel-catalog') return respond({ revision: 1, channels: [] });
    if (pathname === systemPath) {
      return respond({ ...identity, channels: 1, talkgroups: 180, patch_groups: 0, radios: 1_004,
        affiliated_radios: 291, channel_names: 'North Simulcast', alias_lists: [{ id: 7, name: 'County P25' }],
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
      await expect(page.getByRole('link', { name: 'County P25', exact: true }))
        .toHaveAttribute('href', /view=aliases.*list=7/);
      await expect(page.getByRole('link', { name: 'North Simulcast', exact: true }))
        .toHaveAttribute('href', new RegExp(`view=channel.*configuration_id=${channelId}`));
      await expectNoHorizontalOverflow(page);
      await page.screenshot({ path: testInfo.outputPath(`radio-system-stat-cards-${theme}-${width}.png`),
        fullPage: true });

      await page.goto(`/app.html?view=group-identity&${scope}&identity_key=${groupKey}`);
      await expect(page.locator('.entity-info-layout .ui-metric-grid')).toHaveCount(5);
      await expectMetricGridSpacing(page.locator('.entity-info-layout .ui-metric-grid'));
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
      await expect(page.getByRole('link', { name: 'County Fire Dispatch', exact: true }))
        .toHaveAttribute('href', new RegExp(`view=group-identity.*identity_key=${groupKey}`));
      await expectNoHorizontalOverflow(page);
    });
  }
}
