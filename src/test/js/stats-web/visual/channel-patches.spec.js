'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, channelId } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const channel = {
  configuration_id: channelId, protocol: 'P25', protocol_id: 'p25-phase1', channel_kind: 'TRUNKED',
  name: 'North Control', site_name: 'North', system_name: 'County Public Safety',
  radio_system_key: systemKey, capabilities: { patch_groups: true }
};
const entityRef = (kind, id) => ({ kind, radio_system_key: systemKey,
  identity_key: `v1-${kind === 'patch_group' ? 'p' : 'g'}-00001-001-${id}` });
const now = Date.parse('2026-10-01T12:00:00Z');
const groups = [
  { id: 500, name: 'Fire and EMS', type: 1 },
  { id: 600, name: 'Dispatch announcement', type: 2 },
  { id: 700, name: 'Earlier grouping', type: 0 }
].map(({ id, name, type }) => ({
  local_patch_group_id: id, patch_alias_name: name, regroup_type: type, version: 9,
  entity_ref: entityRef('patch_group', id), talkgroup_count: 1, talkgroups_included: 1,
  radio_count: 0, radios_included: 0, state: 'CURRENT', observation_count: 2, last_seen_ms: now
}));
const talkgroups = groups.map((group, index) => ({
  local_patch_group_id: group.local_patch_group_id, local_talkgroup_id: 101 + index,
  alias_name: ['Fire Dispatch', 'EMS Dispatch', 'Mutual Aid'][index],
  entity_ref: entityRef('talkgroup', 101 + index)
}));

for (const presentation of [
  { name: 'light desktop', theme: 'light', viewport: { width: 1600, height: 1000 } },
  { name: 'dark phone', theme: 'dark', viewport: { width: 390, height: 844 } }
]) {
  test(`site patches show grouping type and named members on ${presentation.name}`, async ({ page }, testInfo) => {
    test.setTimeout(15000);
    await page.setViewportSize(presentation.viewport);
    await installSiteStyleApplication(page, presentation.theme);
    await page.route('**/api/v1/**', route => {
      const pathname = decodeURIComponent(new URL(route.request().url()).pathname);
      if (pathname === `/api/v1/channels/${channelId}`) {
        return route.fulfill({ json: { data: { channel } } });
      }
      if (pathname === `/api/v1/channels/${channelId}/patch-groups`) {
        return route.fulfill({ json: { data: { groups, talkgroups, radios: [], offset: 0,
          limit: 100, has_more: false } } });
      }
      return route.fallback();
    });
    await page.goto(`/app.html?view=channel&configuration_id=${channelId}&tab=patches`);
    const table = page.locator('table[data-table-type="channel-patches-versioned"]');
    await expect(table).toBeVisible();
    await expect(table.locator('th[data-column="regroup-type"]')).toContainText('Type');
    for (const [index, label] of ['Patch', 'Simulselect', 'Unknown'].entries()) {
      const row = table.locator('tbody tr').nth(index);
      await expect(row.locator('[data-column="regroup-type"]')).toHaveText(label);
      await expect(row).toContainText(talkgroups[index].alias_name);
    }
    const patchLink = table.getByRole('link', { name: 'Fire and EMS', exact: true });
    const memberLink = table.getByRole('link', { name: 'Fire Dispatch', exact: true });
    expect(new URL(await patchLink.getAttribute('href'), page.url()).searchParams.get('identity_key'))
      .toBe('v1-p-00001-001-500');
    expect(new URL(await memberLink.getAttribute('href'), page.url()).searchParams.get('identity_key'))
      .toBe('v1-g-00001-001-101');
    await expectNoHorizontalOverflow(page);
    await page.screenshot({ path: testInfo.outputPath('patch-types.png'), fullPage: true });
  });
}
