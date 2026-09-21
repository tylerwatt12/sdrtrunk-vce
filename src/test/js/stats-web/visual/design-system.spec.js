const { expect, test } = require('@playwright/test');

const galleryCases = [
  ['components-light-desktop', 'light', { width: 1280, height: 900 }],
  ['components-dark-desktop', 'dark', { width: 1280, height: 900 }],
  ['components-light-mobile', 'light', { width: 390, height: 844 }],
  ['components-dark-mobile', 'dark', { width: 390, height: 844 }],
];

for(const [name, theme, viewport] of galleryCases) {
  test(name, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=gallery`);
    await expect(page.locator('body')).toHaveScreenshot(`${name}.png`, { fullPage: true });
  });
}

for(const theme of ['light', 'dark']) {
  test(`modal-${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 900, height: 720 });
    await page.goto(`/design-system.html?theme=${theme}&view=modal`);
    await expect(page.locator('body')).toHaveScreenshot(`modal-${theme}.png`);
  });
}

test('keyboard-focus', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 500 });
  await page.goto('/design-system.html?theme=light&view=focus');
  await page.locator('#visual-focus-target').focus();
  await expect(page.locator('.visual-focus-example')).toHaveScreenshot('keyboard-focus.png');
});
