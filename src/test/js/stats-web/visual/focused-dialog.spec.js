const { test, expect } = require('@playwright/test');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

for (const theme of ['light', 'dark']) for (const width of [1280, 390]) {
  test(`focused detail dialog gallery ${theme} ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`/design-system.html?view=modal&modal-size=focused&theme=${theme}`);
    const dialog = page.locator('.visual-modal-example .read-only-modal');
    await expect(dialog).toHaveClass(/ui-modal-focused/);
    await expect(dialog).toHaveCSS('width', `${width === 1280 ? 660 : width}px`);
    await expectNoHorizontalOverflow(page);
    await expectContrast(page, { rootSelector: '.visual-modal-example .read-only-modal', reportName: `focused-gallery-${theme}-${width}` });
    await expect(dialog).toHaveScreenshot(`focused-dialog-${theme}-${width}.png`);
  });
}
