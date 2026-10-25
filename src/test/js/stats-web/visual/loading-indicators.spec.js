const { expect, test } = require('@playwright/test');

for (const theme of ['light', 'dark']) {
  for (const reducedMotion of ['no-preference', 'reduce']) {
    test(`shared loading rings ${theme} ${reducedMotion} stay visible and animate regardless of motion preference`, async ({ page }) => {
      await page.emulateMedia({ reducedMotion });
      await page.goto(`/design-system.html?theme=${theme}&view=gallery`);
      const messages = page.locator('.visual-section').filter({ has: page.getByRole('heading', { name: 'Messages', exact: true }) });
      const rings = messages.locator('.ui-feedback-loading, .visual-loading-actions .is-loading');
      await expect(rings).toHaveCount(3);
      for (const ring of await rings.all()) {
        const before = await ring.evaluate((element) => {
          const style = getComputedStyle(element, '::before');
          return { transform: style.transform, animation: style.animationName, width: style.width,
            height: style.height, shrink: style.flexShrink, track: style.borderRightColor,
            segment: style.borderTopColor, opacity: getComputedStyle(element).opacity };
        });
        expect(before.width).toBe(before.height);
        expect(Number.parseFloat(before.width)).toBeGreaterThanOrEqual(16);
        expect(before.shrink).toBe('0');
        expect(before.segment).not.toBe(before.track);
        expect(Number(before.opacity)).toBe(1);
        expect(before.animation).toBe('ui-feedback-spin');
        await expect.poll(() => ring.evaluate((element) =>
          getComputedStyle(element, '::before').transform)).not.toBe(before.transform);
      }
      await expect(page.getByRole('button', { name: 'Loading settings' }).locator('svg')).toBeHidden();
      await expect(page.getByRole('button', { name: 'Loading details' }).locator('svg')).toBeHidden();
      if (reducedMotion === 'reduce') {
        const actions = page.locator('.visual-loading-actions');
        const reducedFrame = await actions.screenshot({ animations: 'disabled' });
        await page.emulateMedia({ reducedMotion: 'no-preference' });
        expect(await actions.screenshot({ animations: 'disabled' })).toEqual(reducedFrame);
        await expect(actions).toHaveScreenshot(`loading-actions-${theme}.png`);
      }
    });
  }
}
