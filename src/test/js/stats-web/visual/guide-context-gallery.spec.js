'use strict';
const { expect, test } = require('@playwright/test');

for (const theme of ['light', 'dark']) {
  test(`guide gallery keeps the owning panel readable and outlines only its control in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto(`/design-system.html?view=setup-guide&context=1&theme=${theme}`);
    const guide = page.locator('.ui-setup-guide');
    await expect(guide).toHaveAttribute('data-guide-step', 'new-channel');
    await expect(page.locator('.visual-channels-example')).toHaveAttribute('data-ready', 'true');
    await expect(page.locator('.ui-setup-guide-highlight')).toHaveCount(1);
    const state = await page.evaluate(() => {
      const example = document.querySelector('.visual-channels-example').getBoundingClientRect();
      const focus = document.querySelector('[data-guide-target="channel-new"]');
      const outline = document.querySelector('.ui-setup-guide-highlight');
      const shade = document.querySelector('.ui-setup-guide-shade');
      const maskId = shade.querySelector('.ui-setup-guide-dimmer').getAttribute('mask').slice(5, -1);
      const viewport = { width: innerWidth, height: innerHeight };
      const rects = [...document.getElementById(maskId).querySelectorAll('.ui-setup-guide-mask-hole')]
        .map(rect => ({ left: +rect.getAttribute('x'), top: +rect.getAttribute('y'),
          right: +rect.getAttribute('x') + +rect.getAttribute('width'),
          bottom: +rect.getAttribute('y') + +rect.getAttribute('height') }));
      return { focused: outline.dataset.setupGuideTarget === focus.id,
        ownerRevealed: rects.some(rect => rect.left <= Math.max(0, example.left) &&
          rect.top <= Math.max(0, example.top) && rect.right >= Math.min(viewport.width, example.right) &&
          rect.bottom >= Math.min(viewport.height, example.bottom)) };
    });
    expect(state).toEqual({ focused: true, ownerRevealed: true });
    await expect(page).toHaveScreenshot(`guide-context-gallery-${theme}.png`);
    await guide.getByRole('button', { name: 'Close guide', exact: true }).click();
    await expect(page.locator('.ui-setup-guide-shade')).toHaveCount(0);
  });
}
