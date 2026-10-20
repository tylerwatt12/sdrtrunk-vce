'use strict';

const { expect, test } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, systemName, groupKey, radioKey, channelId } =
  require('./fixtures/site-style-app.cjs');

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Main Call Statistics renders identity aggregates and drilldowns in ${theme}`, async ({ page }) => {
    const fixture = await installSiteStyleApplication(page, theme);
    await page.setViewportSize({ width, height: 900 });
    const identity = { protocol: 'P25', protocol_code: 1, channel_kind: 'TRUNKED',
      radio_system_key: systemKey, system_name: systemName, wacn: 1, system_id: 1,
      logical_call_count: 42, recorded_logical_call_count: 6, stream_submitted_logical_call_count: 6 };
    await page.route('**/api/v1/dashboard', route => route.fulfill({ json: { data: {
      call_activity: { totals: { logical_call_count: 42 }, series: [] }, source_activity_24h: [],
      top_destinations: [{ ...identity, identity_kind: 'talkgroup', identity_id: 1201,
        alias_name: 'Fire Dispatch', entity_ref: { kind: 'talkgroup', radio_system_key: systemKey,
          identity_key: groupKey } }],
      top_sources: [{ ...identity, identity_kind: 'radio', identity_id: 30914,
        alias_name: 'Engine 4', entity_ref: { kind: 'radio', radio_system_key: systemKey,
          identity_key: radioKey } },
        { ...identity, channel_kind: 'CONVENTIONAL', configuration_id: channelId,
          name: 'Maintenance Dispatch', identity_kind: 'radio', identity_id: 84,
          alias_name: 'Road Crew 12', logical_call_count: 7, entity_tab: 'radios',
          entity_ref: { kind: 'channel', key: channelId } },
        { ...identity, radio_system_key: null, system_name: 'Context unavailable',
          identity_kind: 'radio', identity_id: 85, alias_name: 'Unknown context unit', logical_call_count: 8,
          entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: 'v1-r-00001-001-85' } }]
    } } }));
    await page.goto('/app.html?view=dashboard');
    await expect(page.locator('.signal-current-tile')).toHaveCount(1);
    await page.getByRole('link', { name: 'Call Statistics', exact: true }).click();
    const destinations = page.locator('[data-table-type="dashboard-destinations"]');
    const sources = page.locator('[data-table-type="dashboard-sources"]');
    await expect(destinations.getByRole('columnheader', { name: /^Destination/ })).toBeVisible();
    await expect(sources.getByRole('columnheader', { name: /^Source/ })).toBeVisible();
    const destination = destinations.getByRole('link', { name: 'Fire Dispatch', exact: true });
    const source = sources.getByRole('link', { name: 'Engine 4', exact: true });
    await expect(destination).toHaveAttribute('href', new RegExp(`view=group-identity.*identity_key=${groupKey}`));
    await expect(source).toHaveAttribute('href', new RegExp(`view=radio.*identity_key=${radioKey}`));
    for (const table of [destinations, sources]) {
      await expect(table.getByRole('link', { name: systemName, exact: true })).toHaveAttribute('href',
        `/?${new URLSearchParams({ view: 'radio-system', radio_system_key: systemKey })}`);
      await expect(table.getByRole('cell', { name: '42', exact: true })).toBeVisible();
    }
    await expect(sources.getByRole('link', { name: 'Maintenance Dispatch', exact: true })).toHaveAttribute('href',
      `/?view=channel&configuration_id=${channelId}`);
    await expect(sources.getByRole('link', { name: 'Road Crew 12', exact: true })).toHaveAttribute('href',
      `/?view=channel&configuration_id=${channelId}&tab=radios`);
    await expect(sources.getByRole('cell', { name: 'Context unavailable', exact: true })).toBeVisible();
    await expect(sources.getByRole('link', { name: 'Context unavailable', exact: true })).toHaveCount(0);
    await destinations.getByRole('link', { name: systemName, exact: true }).click();
    await expect(page.locator('body')).toHaveAttribute('data-view', 'radio-system');
    await expect(page.locator('.page-title')).toContainText(systemName);
    await page.goBack();
    await expect(source).toBeVisible();
    await source.click();
    await expect(page.locator('body')).toHaveAttribute('data-view', 'radio');
    await expect(page.locator('.page-title')).toContainText('Engine 4');
    await page.goBack();
    await expect(destination).toBeVisible();
    await destination.click();
    await expect(page.locator('body')).toHaveAttribute('data-view', 'group-identity');
    await expect(page.locator('.page-title')).toContainText('Fire Dispatch');
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });
}

test('Call Statistics context stays plain text without radio access', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page);
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
    configured: true, authenticated: false, capabilities: { dashboard: true, radio: false }
  } } }));
  await page.route('**/api/v1/dashboard', route => route.fulfill({ json: { data: {
    call_activity: { totals: {}, series: [] }, source_activity_24h: [], top_destinations: [],
    top_sources: [{ protocol: 'P25', channel_kind: 'TRUNKED', radio_system_key: systemKey,
      system_name: systemName, identity_kind: 'radio', identity_id: 30914, alias_name: 'Engine 4',
      entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: radioKey } },
    { protocol: 'P25', channel_kind: 'CONVENTIONAL', configuration_id: channelId,
      name: 'Maintenance Dispatch', identity_kind: 'radio', identity_id: 84,
      alias_name: 'Road Crew 12', entity_tab: 'radios', entity_ref: { kind: 'channel', key: channelId } }]
  } } }));
  await page.goto('/app.html?view=dashboard&tab=calls');
  const sources = page.locator('[data-table-type="dashboard-sources"]');
  await expect(sources.getByRole('cell', { name: systemName, exact: true })).toBeVisible();
  await expect(sources.getByRole('cell', { name: 'Maintenance Dispatch', exact: true })).toBeVisible();
  await expect(sources.getByRole('link')).toHaveCount(0);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});
