const { expect, test } = require('@playwright/test');

for (const theme of ['light', 'dark']) {
  for (const [device, viewport] of [
    ['desktop', { width: 1280, height: 900 }],
    ['mobile', { width: 390, height: 844 }]
  ]) {
    test(`settings workspace ${theme} ${device}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await page.goto(`/design-system.html?theme=${theme}&view=admin-navigation`);
      const workspace = page.locator('.visual-admin-navigation-example');
      const navigation = workspace.getByRole('navigation', { name: 'Administration sections' });
      const picker = navigation.getByLabel('Administration section', { exact: true });
      await expect(navigation.locator('.ui-settings-nav-group')).toHaveCount(5);
      await expect(navigation.locator('.ui-settings-nav-link')).toHaveCount(13);
      await expect(navigation.locator('.ui-settings-nav-link svg')).toHaveCount(13);
      await expect(navigation.locator('[aria-current="page"]')).toHaveText('Recording settings');
      if (device === 'desktop') {
        await expect(picker).toBeHidden();
        await expect(navigation.getByRole('link', { name: 'Audio quality', exact: true })).toBeVisible();
      } else {
        await expect(picker).toBeVisible();
        await expect(navigation.getByRole('link', { name: 'Audio quality', exact: true })).toBeHidden();
        await expect(picker.locator('optgroup')).toHaveCount(5);
      }
      await expect(page.locator('body')).toHaveScreenshot(`settings-workspace-${theme}-${device}.png`, { fullPage: true });
      const overflow = await workspace.evaluate((element) => element.scrollWidth > element.clientWidth);
      expect(overflow).toBe(false);
    });
  }
}

test('settings navigation keyboard focus and mobile picker keep one active destination', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=admin-navigation');
  const workspace = page.locator('.visual-admin-navigation-example');
  const link = workspace.getByRole('link', { name: 'Audio quality', exact: true });
  await link.focus();
  await expect(link).toBeFocused();
  await expect(link).toHaveScreenshot('settings-navigation-keyboard-focus.png');
  await link.press('Enter');
  await expect(workspace.getByRole('heading', { name: 'Audio quality', exact: true })).toBeVisible();
  await expect(workspace.locator('.ui-settings-nav-link[aria-current="page"]')).toHaveCount(1);
  await expect(link).toHaveAttribute('aria-current', 'page');
  await page.setViewportSize({ width: 390, height: 844 });
  const picker = workspace.getByLabel('Administration section', { exact: true });
  await expect(picker).toHaveValue('audio');
  await picker.selectOption('cleanup');
  await expect(workspace.getByRole('heading', { name: 'Saved data cleanup', exact: true })).toBeVisible();
  await expect(workspace.locator('[data-visual-admin-group]')).toHaveText('Activity & storage');
  await expect(workspace.locator('.ui-settings-nav-link[aria-current="page"]')).toHaveCount(1);
});
