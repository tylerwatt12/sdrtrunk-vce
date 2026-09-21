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

test('modal-mobile-light', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=modal');
  await expect(page.locator('body')).toHaveScreenshot('modal-mobile-light.png');
});

test('modal-long-dark', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=modal-long');
  const content = page.locator('.modal-content');
  await expect.poll(() => content.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
  await expect(page.locator('body')).toHaveScreenshot('modal-long-dark.png');
});

test('keyboard-focus', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 500 });
  await page.goto('/design-system.html?theme=light&view=focus');
  await page.locator('#visual-focus-target').focus();
  await expect(page.locator('.visual-focus-example')).toHaveScreenshot('keyboard-focus.png');
});

test('settings-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1180, height: 800 });
  await page.goto('/design-system.html?theme=light&view=settings');
  await expect(page.locator('body')).toHaveScreenshot('settings-light-desktop.png', { fullPage: true });
});

test('settings-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=settings');
  await expect(page.locator('body')).toHaveScreenshot('settings-dark-mobile.png', { fullPage: true });
});

test('receiver-health-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=health');
  await expect(page.locator('body')).toHaveScreenshot('receiver-health-light-desktop.png', { fullPage: true });
});

test('receiver-health-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=health');
  await expect(page.locator('body')).toHaveScreenshot('receiver-health-dark-mobile.png', { fullPage: true });
});

test('p25-settings-dark-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('/design-system.html?theme=dark&view=p25');
  await expect(page.locator('body')).toHaveScreenshot('p25-settings-dark-desktop.png', { fullPage: true });
});

test('p25-settings-light-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=p25');
  await expect(page.locator('body')).toHaveScreenshot('p25-settings-light-mobile.png', { fullPage: true });
});

test('admin-access-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=admin-access');
  await expect(page.locator('body')).toHaveScreenshot('admin-access-light-desktop.png', { fullPage: true });
});

test('admin-access-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=admin-access');
  await expect(page.locator('body')).toHaveScreenshot('admin-access-dark-mobile.png', { fullPage: true });
});

test('admin-receiver-dark-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=dark&view=admin-receiver');
  await expect(page.locator('body')).toHaveScreenshot('admin-receiver-dark-desktop.png', { fullPage: true });
});

test('admin-receiver-light-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=admin-receiver');
  await expect(page.locator('body')).toHaveScreenshot('admin-receiver-light-mobile.png', { fullPage: true });
});
