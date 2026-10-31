'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, radioKey } = require('./fixtures/site-style-app.cjs');
const { presentations } = require('./fixtures/site-style-cases.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

for (const presentation of presentations) {
  test(`radio details keep the OTA alias read only ${presentation.name}`, async ({ page }) => {
    await page.setViewportSize(presentation.viewport);
    const fixture = await installSiteStyleApplication(page, presentation.theme);
    const deletions = [];
    page.on('request', request => {
      if (new URL(request.url()).pathname.startsWith('/api/v1/admin/retained-statistics/deletions'))
        deletions.push(request);
    });
    await page.route('**/api/v1/radio-systems/**/radios/**', route => route.fulfill({ json: { data: {
      protocol: 'P25', radio_system_key: systemKey, identity_key: radioKey, native_id: 30914,
      alias_name: 'Engine 4', last_talker_alias: 'Observed unit name', system_name: 'Metro Public Safety',
      logical_call_count: 17, groups: 1, capabilities: { talker_aliases: true },
      entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: radioKey }
    } } }));
    await page.goto(`/app.html?view=radio&radio_system_key=${encodeURIComponent(systemKey)}&identity_key=${radioKey}`);
    await expect(page.getByText('Observed unit name', { exact: true })).toBeVisible();
    await expect(page.locator('#content')).toContainText('Talker Alias');
    await expect(page.locator('#content')).toContainText('Engine 4');
    await expect(page.locator('#content')).toContainText('17');
    await expect(page.getByRole('button', { name: /forget.*alias/i })).toHaveCount(0);
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    await expectNoHorizontalOverflow(page, '#content');
    expect(deletions).toEqual([]);
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });
}
