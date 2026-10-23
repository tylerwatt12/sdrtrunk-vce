const { test, expect } = require('@playwright/test');

for (const theme of ['light', 'dark']) {
  test(`Scan Lists relationship overview uses the shared gallery in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto(`/design-system.html?theme=${theme}&view=setup-guide&diagram=scan-lists`);
    await expect(page.locator('.visual-setup-guide-example')).toHaveAttribute('data-ready', 'true');
    const guide = page.locator('.ui-setup-guide');
    await expect(guide).toHaveAttribute('data-guide-step', 'overview');
    await expect(guide.getByRole('img', { name: 'County and city aliases grouped into Scan Lists', exact: true })).toBeVisible();
    await expect(guide.getByRole('button', { name: 'Close guide', exact: true })).toBeVisible();
    await expect(guide.getByRole('button', { name: 'Start tour', exact: true })).toBeVisible();
    await expect(guide).toHaveScreenshot(`scan-lists-relationship-${theme}.png`);
  });
}
