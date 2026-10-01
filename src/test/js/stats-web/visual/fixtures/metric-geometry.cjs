'use strict';

const { expect } = require('@playwright/test');

// Check rendered geometry so a parent or feature override cannot silently remove
// the shared inset or gap while retaining the expected component classes.
async function expectMetricGridSpacing(grids, { inset = 12, sectionInset = true } = {}) {
  expect(await grids.count()).toBeGreaterThan(0);
  for (const grid of await grids.all()) {
    await expect(grid).toBeVisible();
    const geometry = await grid.evaluate((element) => {
      const bounds = element.getBoundingClientRect();
      const styles = getComputedStyle(element);
      const cards = [...element.querySelectorAll(':scope > .ui-metric')]
        .map((card) => card.getBoundingClientRect());
      const section = element.closest('.ui-section');
      const sectionBounds = section?.getBoundingClientRect();
      const sectionStyles = section ? getComputedStyle(section) : null;
      const title = section?.querySelector(':scope > .ui-section-title');
      const gaps = cards.slice(1).map((card, index) => {
        const previous = cards[index];
        return Math.abs(card.top - previous.top) < 1 ?
          card.left - previous.right : card.top - previous.bottom;
      });
      return {
        count: cards.length,
        padding: [styles.paddingTop, styles.paddingRight, styles.paddingBottom, styles.paddingLeft]
          .map(parseFloat),
        gap: parseFloat(styles.gap),
        gaps,
        insets: cards.length ? [
          Math.min(...cards.map((card) => card.top)) - bounds.top,
          bounds.right - Math.max(...cards.map((card) => card.right)),
          bounds.bottom - Math.max(...cards.map((card) => card.bottom)),
          Math.min(...cards.map((card) => card.left)) - bounds.left
        ] : [],
        sectionInsets: section && cards.length ? [
          Math.min(...cards.map((card) => card.top)) - (title ? title.getBoundingClientRect().bottom :
            sectionBounds.top + parseFloat(sectionStyles.borderTopWidth)),
          sectionBounds.right - parseFloat(sectionStyles.borderRightWidth) -
            Math.max(...cards.map((card) => card.right)),
          Math.min(...cards.map((card) => card.left)) - sectionBounds.left -
            parseFloat(sectionStyles.borderLeftWidth)
        ] : []
      };
    });
    expect(geometry.count).toBeGreaterThan(0);
    expect(geometry.padding).toEqual([inset, inset, inset, inset]);
    expect(geometry.gap).toBe(8);
    for (const measured of geometry.insets) expect(measured).toBeCloseTo(inset, 0);
    for (const measured of geometry.gaps) expect(measured).toBeCloseTo(8, 0);
    if (sectionInset) {
      expect(geometry.sectionInsets).toHaveLength(3);
      for (const measured of geometry.sectionInsets) expect(measured).toBeCloseTo(12, 0);
    }
  }
}

async function expectNoHorizontalOverflow(page) {
  const dimensions = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    content: document.documentElement.scrollWidth,
    body: document.body.scrollWidth
  }));
  expect(dimensions.content).toBeLessThanOrEqual(dimensions.viewport + 1);
  expect(dimensions.body).toBeLessThanOrEqual(dimensions.viewport + 1);
}

module.exports = { expectMetricGridSpacing, expectNoHorizontalOverflow };
