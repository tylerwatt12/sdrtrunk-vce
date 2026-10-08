'use strict';

const { expect, test } = require('@playwright/test');

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 390], ['dark', 390]]) {
  test(`visualizer controls remain readable in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.goto(`/design-system.html?theme=${theme}&view=health`);
    await page.evaluate(() => {
      const stage = document.createElement('section');
      stage.className = 'network-visualizer-stage';
      stage.style.minHeight = '160px';
      const back = document.createElement('button');
      back.className = 'ui-button ui-button-secondary network-visualizer-back';
      back.textContent = '← All systems';
      const label = document.createElement('span');
      label.className = 'network-visualizer-label';
      label.dataset.signal = 'call';
      label.textContent = 'Dispatch · call activity';
      label.style.left = '50%';
      label.style.top = '65%';
      stage.append(back, label);
      document.querySelector('.visual-health-example').replaceChildren(stage);
    });
    const stage = page.locator('.network-visualizer-stage');
    const back = stage.getByRole('button', { name: 'All systems' });
    const contrast = () => back.evaluate((button) => {
      const style = getComputedStyle(button);
      const luminance = (color) => {
        const channels = color.match(/[\d.]+/g).slice(0, 3).map((value) => {
          const linear = Number(value) / 255;
          return linear <= 0.04045 ? linear / 12.92 : ((linear + 0.055) / 1.055) ** 2.4;
        });
        return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
      };
      const foreground = luminance(style.color);
      const background = luminance(style.backgroundColor);
      return (Math.max(foreground, background) + 0.05) / (Math.min(foreground, background) + 0.05);
    });
    expect(await contrast()).toBeGreaterThanOrEqual(4.5);
    await back.hover();
    expect(await contrast()).toBeGreaterThanOrEqual(4.5);
    await back.focus();
    expect(await contrast()).toBeGreaterThanOrEqual(4.5);
    const callColor = await stage.locator('[data-signal="call"]').evaluate((label) =>
      getComputedStyle(label).color.match(/\d+/g).map(Number));
    expect(callColor[1]).toBeGreaterThan(callColor[0]);
    expect(callColor[1]).toBeGreaterThan(callColor[2]);
    await expect(stage).toHaveScreenshot(`network-visualizer-controls-${theme}-${width}.png`);
  });
}
