'use strict';

const { expect, test } = require('@playwright/test');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');
const { selectDockSource } = require('./fixtures/audio-dock-source.cjs');

const viewports = [{ width: 320, height: 740 }, { width: 390, height: 844 },
  { width: 430, height: 932 }, { width: 844, height: 390 }];

async function setDockSize(player, size) {
  const handle = player.getByRole('button', { name: 'Change audio player size', exact: true });
  await handle.press(size === 'full' ? 'End' : 'Home');
  if (size === 'minimal') await handle.press('ArrowUp');
  await expect(player).toHaveAttribute('data-state', size);
}

async function expectPointerReachable(control) {
  await control.scrollIntoViewIfNeeded();
  await expect.poll(() => control.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    const hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
    return rect.width > 0 && rect.height > 0 && rect.x >= 0 && rect.right <= innerWidth &&
      rect.y >= 0 && rect.bottom <= innerHeight && element.contains(hit);
  }), { message: `${await control.textContent()} must receive pointer hits inside the viewport` }).toBe(true);
}

for (const theme of ['light', 'dark']) {
  for (const viewport of viewports) {
    test(`drawer receives taps above every dock state at ${viewport.width}×${viewport.height} in ${theme}`,
      async ({ page }) => {
        await page.setViewportSize(viewport);
        await openAudioApp(page, { theme });
        const player = page.locator('#audio-dock');
        const navigation = page.locator('#primary-navigation');
        const toggle = page.locator('#navigation-toggle');
        for (const source of ['live', 'recordings']) {
          if (await player.getAttribute('data-state') === 'collapsed') await setDockSize(player, 'minimal');
          await selectDockSource(player, source);
          for (const size of ['collapsed', 'minimal', 'full']) {
            await setDockSize(player, size);
            await toggle.click();
            await expect(toggle).toHaveAttribute('aria-expanded', 'true');
            const summaries = navigation.locator('.nav-group > summary:visible');
            for (let index = 0; index < await summaries.count(); index++) {
              await summaries.nth(index).click();
              const controls = navigation.locator('summary:visible, a:visible');
              for (let control = 0; control < await controls.count(); control++) {
                await expectPointerReachable(controls.nth(control));
              }
            }
            const about = navigation.getByRole('link', { name: 'About', exact: true });
            await expectPointerReachable(about);
            await about.focus();
            await page.keyboard.press('Tab');
            await expect(toggle).toBeFocused();
            await page.keyboard.press('Shift+Tab');
            await expect(about).toBeFocused();
            // The backdrop must also cover dock controls outside the drawer.
            expect(await page.evaluate(() => document.elementFromPoint(innerWidth - 4, innerHeight - 4)?.id))
              .toBe('navigation-backdrop');
            await page.keyboard.press('Escape');
            await expect(toggle).toHaveAttribute('aria-expanded', 'false');
            await expect(toggle).toBeFocused();
            await expect(navigation).toHaveAttribute('inert', '');
          }
        }
        // A real pointer action on the formerly obscured last destination must navigate.
        await toggle.click();
        const about = navigation.getByRole('link', { name: 'About', exact: true });
        await expectPointerReachable(about);
        await about.click();
        await expect(page).toHaveURL(/view=credits/);
        await expect(toggle).toHaveAttribute('aria-expanded', 'false');
      });
  }
}

for (const viewport of viewports) {
  test(`ordinary user reaches My Settings from the drawer at ${viewport.width}×${viewport.height}`,
    async ({ page }) => {
      await page.setViewportSize(viewport);
      await openAudioApp(page, { admin: false, theme: viewport.width === 844 ? 'dark' : 'light' });
      await expect(page.getByRole('heading', { name: 'Recordings', exact: true })).toBeVisible();
      await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
      const toggle = page.locator('#navigation-toggle');
      await toggle.click();
      await expect(toggle).toHaveAttribute('aria-expanded', 'true');
      const navigation = page.locator('#primary-navigation');
      await expect(navigation.getByRole('link', { name: 'Administration', exact: true })).toBeHidden();
      const settings = navigation.getByRole('link', { name: 'My Settings', exact: true });
      await expectPointerReachable(settings);
      await settings.click();
      await expect(page).toHaveURL(/view=settings/);
      await expect(page.getByRole('heading', { name: 'My Settings', exact: true })).toBeVisible();
      await expect(page.locator('#navigation-toggle')).toHaveAttribute('aria-expanded', 'false');
      const statusIcon = page.getByRole('button', { name: 'Change Status Icon', exact: true });
      const reset = page.getByRole('button', { name: 'Reset All Personal Preferences', exact: true });
      await expect(statusIcon).toBeVisible();
      await expect(reset).toBeVisible();
      await reset.click();
      await expect(page.getByRole('dialog', { name: 'Reset personal preferences', exact: true })).toBeVisible();
      await page.getByRole('button', { name: 'Cancel', exact: true }).click();
      await expect(reset).toBeFocused();
    });
}

test('My Settings drawer entry follows authentication and user-settings access', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openAudioApp(page, { authenticated: false, admin: false });
  await page.locator('#navigation-toggle').click();
  await expect(page.locator('#primary-navigation a[data-view="settings"]')).toBeHidden();
  await page.unroute('**/api/v1/**');
  await openAudioApp(page, { admin: false, capabilities: { 'user-settings': false } });
  await page.locator('#navigation-toggle').click();
  const settings = page.locator('#primary-navigation a[data-view="settings"]');
  await expect(settings).toBeVisible();
  await expect(settings).toHaveClass(/access-locked/);
  await expect(settings.locator('.nav-lock')).toBeVisible();
  await settings.click();
  await expect(page.getByRole('heading', { name: 'Access denied', exact: true })).toBeVisible();
  await expect(page.getByText('fixture-listener is signed in with User access, which does not include My Settings.',
    { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'My Settings', exact: true })).toHaveCount(0);
});

test('desktop retains the account Settings entry without duplicating navigation', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openAudioApp(page, { admin: false });
  await expect(page.locator('#primary-navigation a[data-view="settings"]')).toBeHidden();
  const settings = page.getByRole('button', { name: 'Open My Settings for fixture-listener', exact: true });
  await expect(settings).toBeVisible();
  await settings.click();
  await expect(page.getByRole('heading', { name: 'My Settings', exact: true })).toBeVisible();
});
