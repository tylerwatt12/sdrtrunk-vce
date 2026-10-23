'use strict';

const { expect, test } = require('@playwright/test');
const { installSiteStyleApplication, systemName, systemKey, radioKey } =
  require('./fixtures/site-style-app.cjs');

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Radio Activity source radios support selection, paging and range reset in ${theme}`, async ({ page }) => {
    const fixture = await installSiteStyleApplication(page, theme);
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/app.html?view=dashboard&tab=activity');
    await expect(page.getByText('Select an activity type to see its source radios.', { exact: true })).toBeVisible();
    expect(fixture.requests.filter(path => path === '/api/v1/activity/radios')).toEqual([]);
    await page.locator('.dashboard-activity-legend-button[data-action="GRANT"]').click();
    const table = page.locator('[data-table-type="dashboard-activity-radios"]');
    await expect(table.getByRole('link', { name: 'Engine 4', exact: true })).toHaveAttribute('href',
      new RegExp(`view=radio.*identity_key=${radioKey}`));
    await expect(table.getByRole('link', { name: systemName, exact: true }).first()).toHaveAttribute('href',
      `/?${new URLSearchParams({ view: 'radio-system', radio_system_key: systemKey })}`);
    await expect(page.locator('.dashboard-activity-radio-result > .ui-section-note')).toContainText(
      'Saved event details: 900; 800 identify a source radio and 100 do not.');
    await page.screenshot({ path: test.info().outputPath('source-radios.png') });
    const pager = page.getByRole('navigation', { name: 'Source radio pagination' });
    await expect(pager).toContainText('101');
    await pager.getByRole('button', { name: 'Next', exact: true }).click();
    await expect(table.getByRole('link', { name: 'Field unit 31014', exact: true })).toBeVisible();
    await expect(pager.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
    await pager.getByRole('button', { name: 'Previous', exact: true }).click();
    await expect(table.getByRole('link', { name: 'Engine 4', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '7d', exact: true }).click();
    await expect(page.getByText('Select an activity type to see its source radios.', { exact: true })).toBeVisible();
    await expect(table).toHaveCount(0);
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });
}
