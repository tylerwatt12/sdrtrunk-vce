'use strict';

const { test, expect } = require('@playwright/test');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const frequency = '773.83125';
const longName = 'MARCS-CuyCoSimulcastControlChannelWithoutSpaces';

async function expectTextInsideCells(table) {
  const overflowing = await table.evaluate((element) => {
    const problems = [];
    for (const cell of element.querySelectorAll('th, td')) {
      if (!cell.getClientRects().length) continue;
      // A deliberately non-wrapping descendant is checked separately for clipping.
      if (cell.hasAttribute('data-clipped-value')) continue;
      const bounds = cell.getBoundingClientRect();
      const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT);
      while (walker.nextNode()) {
        const text = walker.currentNode;
        if (!text.textContent.trim()) continue;
        const range = document.createRange();
        range.selectNodeContents(text);
        for (const fragment of range.getClientRects()) {
          if (fragment.width < 1 || fragment.height < 1) continue;
          if (fragment.left < bounds.left - 1 || fragment.right > bounds.right + 1) {
            problems.push({ cell: cell.textContent.trim(), text: text.textContent.trim(),
              cellLeft: bounds.left, cellRight: bounds.right,
              textLeft: fragment.left, textRight: fragment.right });
          }
        }
      }
      if (cell.scrollWidth > cell.clientWidth + 1) {
        problems.push({ cell: cell.textContent.trim(), scrollWidth: cell.scrollWidth,
          clientWidth: cell.clientWidth });
      }
    }
    return problems;
  });
  expect(overflowing).toEqual([]);
}

for (const theme of ['light', 'dark']) {
  for (const [size, viewport] of [
    ['desktop', { width: 1280, height: 900 }],
    ['mobile', { width: 390, height: 844 }]
  ]) {
    test(`table values stay inside their cells in ${theme} ${size}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await page.goto(`/design-system.html?view=mobile-table&theme=${theme}`);
      const wrapper = page.locator('.visual-mobile-table-example .ui-table-wrap');
      await wrapper.evaluate((element, name) => {
        element.style.maxWidth = '480px';
        element.innerHTML = `<table class="ui-data-table resizable-table ui-mobile-cards" data-mobile-cards="true">
          <colgroup><col style="width: 145px"><col style="width: 215px"><col class="frequency-col" style="width: 120px"></colgroup>
          <thead><tr><th>Name / Site</th><th>Details with a long heading</th><th class="numeric">CC MHz</th></tr></thead>
          <tbody><tr>
            <td data-label="Name / Site"><a href="#">${name}</a><br><small>Cuyahoga</small></td>
            <td data-label="Details">RFSS 02 · Site 01 · NAC 340 · 4 band plans</td>
            <td class="numeric" data-label="CC MHz">773.83125</td>
          </tr><tr>
            <td data-label="Name / Site">West Site</td>
            <td data-label="Details" data-clipped-value><span class="visual-table-exact-time">2026-10-03T00:48:32.000000Z-UnbrokenExactTimestamp-0123456789012345678901234567890123456789</span></td>
            <td class="numeric" data-label="CC MHz">855.86250</td>
          </tr></tbody></table>`;
      }, longName);
      const table = wrapper.locator('table');
      const frequencyCell = table.locator('td.numeric').first();
      await expect(frequencyCell).toHaveText(frequency);
      await expect(table.getByRole('link', { name: longName })).toBeVisible();
      await expectTextInsideCells(table);

      // Match a user's saved narrow column width without relying on drag timing.
      await table.locator('col.frequency-col').evaluate((column) => {
        column.style.width = '65px';
      });
      await expectTextInsideCells(table);
      await expect(frequencyCell).toHaveText(frequency);
      const clippedValue = table.locator('[data-clipped-value]');
      const clip = await clippedValue.evaluate((cell) => ({
        overflow: getComputedStyle(cell).overflowX,
        nowrap: getComputedStyle(cell.querySelector('.visual-table-exact-time')).whiteSpace,
        contentWidth: cell.querySelector('.visual-table-exact-time').scrollWidth,
        cellWidth: cell.getBoundingClientRect().width
      }));
      expect(clip.nowrap).toBe('nowrap');
      expect(clip.contentWidth).toBeGreaterThan(clip.cellWidth);
      expect(clip.overflow).toBe('clip');
      await expectNoHorizontalOverflow(page);
      await expect(wrapper).toHaveScreenshot(`table-cell-containment-${theme}-${size}.png`);

      if (size === 'mobile') {
        // A dense table that opts out of cards must remain safe inside its own scrollport.
        await table.evaluate((element) => element.classList.remove('ui-mobile-cards'));
        await expectTextInsideCells(table);
        await expect(frequencyCell).toHaveText(frequency);
        await expectNoHorizontalOverflow(page);
      }
    });
  }
}
