'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, channelId, channelName } =
  require('./fixtures/site-style-app.cjs');
const { presentations } = require('./fixtures/site-style-cases.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

for (const presentation of presentations) {
  test(`retained ISSI lifetime ${presentation.name}`, async ({ page }) => {
    await page.setViewportSize(presentation.viewport);
    await installSiteStyleApplication(page, presentation.theme);
    const base = `/api/v1/radio-systems/${systemKey}`;
    const observedOn = { configuration_id: channelId, name: channelName,
      entity_ref: { kind: 'channel', configuration_id: channelId } };
    const rows = [
      { alias_name: 'Engine 4', last_advertised_lease_minutes: 240 },
      { alias_name: 'Engine 5', last_advertised_lease_minutes: 0 },
      { alias_name: 'Engine 6', last_advertised_lease_minutes: null }
    ].map((row, index) => ({ ...row,
      canonical_identity: { wacn: 2, system_id: 1, subscriber_id: 30914 + index },
      last_observed_working_id: 501 + index, last_assignment_evidence: 'registration',
      observed_on: observedOn, last_mapping_observed_ms: 1_790_856_000_000,
      last_seen_ms: 1_790_856_000_000 }));
    await page.route('**/api/v1/**', async route => {
      const pathname = decodeURIComponent(new URL(route.request().url()).pathname);
      if (pathname === base) return route.fulfill({ json: { data: {
        protocol: 'P25', radio_system_key: systemKey, wacn: 1, system_id: 1,
        capabilities: { issi: true }, system_name: 'County Public Safety'
      } } });
      if (pathname === `${base}/issi/subscribers`) return route.fulfill({ json: { data: rows,
        meta: { limit: 100, offset: 0, total_count: rows.length, has_more: false } } });
      return route.fallback();
    });
    await page.goto(`/app.html?view=radio-system&radio_system_key=${encodeURIComponent(systemKey)}` +
      '&tab=issi&issi_view=subscribers');
    const content = page.locator('#content');
    await expect(content).toContainText('Engine 4');
    await expect(content).toContainText('240m');
    await expect(content).toContainText('No expiry');
    await expect(content).toContainText('Not received');
    for (const tab of ['Current Assignments', 'Recent Changes', 'History', 'Foreign Systems', 'Band Plans']) {
      await expect(content.getByRole('link', { name: tab, exact: true })).toBeVisible();
    }
    await expectNoHorizontalOverflow(page, '#content');
    //Reload reads retained history directly; no live registration or assignment request supplies these rows.
    await page.reload();
    await expect(content).toContainText('Engine 4');
    await expect(content).toContainText('240m');
    await expectNoHorizontalOverflow(page, '#content');
    await page.screenshot({ path: test.info().outputPath('issi-history.png'), fullPage: true });
  });
}
