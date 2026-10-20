'use strict';

const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { installSiteStyleApplication, systemKey, radioKey } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Radio activity shares message definitions and consolidated identities in ${theme} ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const fixture = await installSiteStyleApplication(page, theme);
    let label = 'Receiver operation';
    const definitions = () => [{ definition_kind: 'ACTION', code: 90, name: 'NEW_ACTION', label,
      meaning: 'The receiver heard a new operation.', p25: false, dmr: true, nxdn: true, sort_order: 1 }];
    let definitionRequests = 0;
    await page.route('**/api/v1/activity/message-types', route => {
      definitionRequests += 1;
      return route.fulfill({ json: { data: definitions() } });
    });
    await page.route('**/api/v1/dashboard', route => route.fulfill({ json: { data: {} } }));
    await page.route('**/api/v1/activity/actions?*', route => route.fulfill({ json: { data: {
      rows: [{ action: 'NEW_ACTION', observation_count: 12 }] } } }));
    await page.route('**/api/v1/activity/radios?*', route => route.fulfill({ json: { data: [{
      native_id: 30914, radio_system_key: systemKey, system_name: 'County Public Safety',
      entity_ref: { kind: 'radio_system', key: systemKey }, alias_name: 'Engine 4',
      radio_entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: radioKey },
      observation_count: 10, last_seen_ms: Date.now(), protocol: 'P25'
    }], meta: { limit: 100, offset: 0, total_count: 1, has_more: false,
      action_observation_count: 12, retained_observation_count: 12,
      identified_observation_count: 10, unknown_source_observation_count: 2 } } }));
    await page.goto('/app.html?view=dashboard&tab=activity');
    await expect(page.locator('.dashboard-activity-legend')).toBeVisible();
    await page.evaluate(() => window.scrollTo(0, 0));
    if (process.env.WEB_REVIEW_DIR) await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR,
      `message-catalog-top-${theme}-${width}.png`) });
    await page.locator('.dashboard-activity-legend').getByRole('button', { name: /Receiver operation.*12/ }).click();
    const table = page.locator('[data-table-type="dashboard-activity-radios"]');
    await expect(table.locator('[data-column="alias"]')).toHaveCount(0);
    const radio = table.locator('tbody [data-column="radio"]');
    await expect(radio.locator('.identity-summary-primary')).toHaveText('Engine 4');
    await expect(radio.locator('.identity-summary-context')).toHaveText('30914');
    await expect(radio.getByRole('link')).toHaveAttribute('href', /view=radio/);
    await page.getByText('Activity types and protocols', { exact: true }).click();
    const guide = page.locator('details.ui-explainer');
    await expect(guide).toContainText('The receiver heard a new operation.');
    await expect(guide).toContainText('DMR · NXDN');
    await expect(guide).toContainText('Receiver operation');
    await expectNoHorizontalOverflow(page);
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    if (width < 720) {
      const finalRow = await guide.locator('tbody tr').last().boundingBox();
      const player = await page.locator('#audio-dock').boundingBox();
      expect(finalRow.y + finalRow.height).toBeLessThan(player.y);
    } else {
      await page.getByRole('button', { name: 'Minimize player to header', exact: true }).click();
      await expect(page.locator('#audio-dock')).toHaveAttribute('data-super-collapsed', 'true');
      await expect(page.locator('.audio-dock-motion')).toHaveCount(0);
    }
    if (process.env.WEB_REVIEW_DIR) {
      await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR,
        `message-catalog-guide-${theme}-${width}.png`) });
      await guide.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR,
        `message-catalog-guide-element-${theme}-${width}.png`) });
    }
    label = 'Updated receiver operation';
    await page.getByRole('button', { name: '6h', exact: true }).click();
    await page.getByRole('link', { name: 'Radio Activity', exact: true }).click();
    await expect(page.locator('.dashboard-activity-legend').getByRole('button', { name: /Updated receiver operation.*12/ })).toBeVisible();
    expect(definitionRequests).toBeGreaterThan(1);
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });
}
