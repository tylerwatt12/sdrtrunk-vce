const { test, expect } = require('@playwright/test');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

for (const theme of ['light', 'dark']) for (const width of [1280, 390]) {
  test(`combined discovery Review remains readable in ${theme} at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`/design-system.html?view=spectrum-discovery&state=review&theme=${theme}`);
    const example = page.locator('.visual-spectrum-discovery-example');
    const modal = example.locator('.read-only-modal');
    const choice = example.getByRole('checkbox', { name: 'Import talkgroup names', exact: true });
    await expect(choice).toBeChecked();
    await expect(example.getByRole('heading', { name: 'Review channel', exact: true })).toBeVisible();
    await choice.scrollIntoViewIfNeeded();
    await expect(choice).toBeVisible();
    expect(await modal.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
    const rootSelector = '.visual-spectrum-discovery-example .read-only-modal';
    for (const hue of [null, 35, 285]) {
      await page.evaluate(async hue => {
        const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
        applyThemeHue(hue);
      }, hue);
      await expectContrast(page, { rootSelector, reportName: `discovery-review-${theme}-${width}-${hue}` });
    }
    await modal.screenshot({ path: testInfo.outputPath(`review-${theme}-${width}.png`) });
  });
}
