'use strict';

const { expect, test } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, systemName, groupKey, radioKey } =
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
          identity_key: radioKey } }]
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
      await expect(table.getByRole('link', { name: systemName, exact: true })).toBeVisible();
      await expect(table.getByRole('cell', { name: '42', exact: true })).toBeVisible();
    }
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
