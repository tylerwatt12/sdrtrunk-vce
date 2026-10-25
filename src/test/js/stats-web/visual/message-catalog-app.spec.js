'use strict';

const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Radio activity reuses message definitions and scopes source radios to the selected count in ${theme} ${width}px`, async ({ page }) => {
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
    await page.route('**/api/v1/activity/actions?*', route => route.fulfill({ json: { data: {
      rows: [{ action: 'NEW_ACTION', observation_count: 12 }] } } }));
    const radioQueries = [];
    await page.route('**/api/v1/activity/radios?*', route => {
      radioQueries.push(Object.fromEntries(new URL(route.request().url()).searchParams));
      return route.fulfill({ json: { data: { rows: [], total_count: 0, limit: 100, offset: 0,
        has_more: false, next_offset: null } } });
    });
    const activityRequests = [];
    page.on('request', request => {
      const path = new URL(request.url()).pathname;
      if (path.startsWith('/api/v1/activity/')) activityRequests.push(path);
    });
    await page.goto('/app.html?view=dashboard&tab=activity');
    await expect(page.locator('.dashboard-activity-legend')).toBeVisible();
    await page.evaluate(() => window.scrollTo(0, 0));
    if (process.env.WEB_REVIEW_DIR) await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR,
      `message-catalog-top-${theme}-${width}.png`) });
    const type = page.locator('.dashboard-activity-legend').getByRole('button', { name: /Receiver operation.*12/ });
    const requestsBeforeSelection = activityRequests.slice();
    expect(requestsBeforeSelection.slice().sort()).toEqual([
      '/api/v1/activity/actions', '/api/v1/activity/message-types'
    ]);
    await type.click();
    await expect(type).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('.dashboard-activity-center-count')).toHaveText('12');
    await expect(page.locator('.dashboard-activity-center-label')).toHaveText('Receiver operation');
    await expect.poll(() => radioQueries).toEqual([
      { range: '24h', action: 'NEW_ACTION', limit: '100', offset: '0' }
    ]);
    await expect(page.locator('.dashboard-activity-radio-host')).toContainText(
      'No source radios were identified in saved receiver operation events.');
    await expect(page.locator('.dashboard-activity-view table[data-table-type="dashboard-activity-radios"]')).toHaveCount(1);
    expect(activityRequests).toEqual([...requestsBeforeSelection, '/api/v1/activity/radios']);
    expect(definitionRequests).toBe(1);
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
