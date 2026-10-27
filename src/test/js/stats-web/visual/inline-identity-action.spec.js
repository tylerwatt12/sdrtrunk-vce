'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

for (const theme of ['light', 'dark']) {
  for (const width of [1280, 390]) {
    test(`inline identity action stays compact and reachable ${theme} ${width}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await page.goto(`/design-system.html?view=gallery&theme=${theme}`);
      const host = page.locator('[data-visual-inline-identity]');
      const action = host.getByRole('button', { name: /Add Regional Fire Dispatch.*and play/ });
      await expect(action).toBeVisible();
      await host.scrollIntoViewIfNeeded();
      const bounds = await host.evaluate(element => {
        const row = element.querySelector('.ui-identity-action').getBoundingClientRect();
        const label = element.querySelector('.ui-identity-action-label').getBoundingClientRect();
        const button = element.querySelector('button').getBoundingClientRect();
        const glyph = element.querySelector('svg').getBoundingClientRect();
        return { row: { left: row.left, right: row.right, height: row.height },
          lineHeight: parseFloat(getComputedStyle(element.querySelector('.ui-identity-action-label')).lineHeight),
          label: { left: label.left, height: label.height },
          button: { left: button.left, right: button.right, width: button.width, height: button.height },
          glyph: { width: glyph.width, height: glyph.height } };
      });
      expect(bounds.button.width).toBe(24);
      expect(bounds.button.height).toBeCloseTo(Math.min(24, bounds.lineHeight), 1);
      expect(bounds.glyph.width).toBe(16);
      expect(bounds.glyph.height).toBe(16);
      expect(bounds.row.height).toBeLessThanOrEqual(24);
      expect(bounds.button.right).toBeLessThanOrEqual(bounds.label.left);
      expect(bounds.button.right).toBeLessThanOrEqual(bounds.row.right + 1);
      await action.focus();
      await expect(action).toBeFocused();
      expect(await action.evaluate(element => {
        const style = getComputedStyle(element);
        return parseFloat(style.outlineWidth) > 0 || style.boxShadow !== 'none';
      })).toBe(true);
      await expectContrast(page, { rootSelector: '[data-visual-inline-identity]' });
      if (process.env.WEB_REVIEW_DIR) {
        fs.mkdirSync(process.env.WEB_REVIEW_DIR, { recursive: true });
        await host.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR, `inline-identity-${theme}-${width}.png`) });
      }
    });
  }
}
