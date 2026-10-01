'use strict';

const { expect } = require('@playwright/test');

// Metadata remains a definition list, but its boxed cards share the visual
// foundation of numeric cards. Measure the result, including long linked values.
async function expectBoxedFacts(facts, metric) {
  expect(await facts.count()).toBeGreaterThan(0);
  const reference = await metric.evaluate((element) => {
    const styles = getComputedStyle(element);
    const label = getComputedStyle(element.querySelector('.ui-metric-label'));
    return { background: styles.backgroundColor, border: styles.border, radius: styles.borderRadius,
      padding: styles.padding, labelColor: label.color, labelSize: label.fontSize,
      labelWeight: label.fontWeight };
  });
  for (const list of await facts.all()) {
    await expect(list).toBeVisible();
    const rendered = await list.evaluate((element) => {
      const bounds = element.getBoundingClientRect();
      const listStyles = getComputedStyle(element);
      const section = element.parentElement;
      const title = section.querySelector(':scope > .ui-section-title');
      const sectionBounds = section.getBoundingClientRect();
      const sectionStyles = getComputedStyle(section);
      const cards = [...element.children].map((card) => {
        const styles = getComputedStyle(card);
        const label = card.querySelector(':scope > dt');
        const value = card.querySelector(':scope > dd');
        const labelStyles = getComputedStyle(label);
        const valueStyles = getComputedStyle(value);
        const cardBounds = card.getBoundingClientRect();
        return { background: styles.backgroundColor, border: styles.border, radius: styles.borderRadius,
          padding: styles.padding, gap: parseFloat(styles.gap),
          labelColor: labelStyles.color, labelSize: labelStyles.fontSize,
          labelWeight: labelStyles.fontWeight, letterSpacing: labelStyles.letterSpacing,
          valueSize: valueStyles.fontSize, margin: valueStyles.margin,
          labels: card.querySelectorAll(':scope > dt').length,
          values: card.querySelectorAll(':scope > dd').length,
          overflow: value.scrollWidth - value.clientWidth,
          left: cardBounds.left, right: cardBounds.right, top: cardBounds.top, bottom: cardBounds.bottom };
      });
      return { tag: element.tagName, cards,
        columns: listStyles.gridTemplateColumns.split(/\s+/).length,
        padding: [listStyles.paddingTop, listStyles.paddingRight, listStyles.paddingBottom,
          listStyles.paddingLeft].map(parseFloat), gap: parseFloat(listStyles.gap),
        insets: [Math.min(...cards.map((card) => card.top)) - bounds.top,
          bounds.right - Math.max(...cards.map((card) => card.right)),
          bounds.bottom - Math.max(...cards.map((card) => card.bottom)),
          Math.min(...cards.map((card) => card.left)) - bounds.left],
        sectionInsets: [Math.min(...cards.map((card) => card.top)) - title.getBoundingClientRect().bottom,
          sectionBounds.right - parseFloat(sectionStyles.borderRightWidth) -
            Math.max(...cards.map((card) => card.right)),
          Math.min(...cards.map((card) => card.left)) - sectionBounds.left -
            parseFloat(sectionStyles.borderLeftWidth)] };
    });
    expect(rendered.tag).toBe('DL');
    expect(rendered.cards.length).toBeGreaterThan(0);
    expect(rendered.padding).toEqual([12, 12, 12, 12]);
    expect(rendered.gap).toBe(8);
    const partialRow = rendered.cards.length < rendered.columns;
    rendered.insets.forEach((inset, index) => {
      // A single fact deliberately occupies one of two desktop tracks. Its
      // unused right track is not a lost section inset.
      if (partialRow && index === 1) expect(inset).toBeGreaterThanOrEqual(12);
      else expect(inset).toBeCloseTo(12, 0);
    });
    rendered.sectionInsets.forEach((inset, index) =>
      expect(inset).toBeCloseTo(partialRow && index === 1 ? rendered.insets[1] : 12, 0));
    for (const card of rendered.cards) {
      for (const [property, value] of Object.entries(reference)) expect(card[property]).toBe(value);
      expect(card.gap).toBe(8);
      expect(card.letterSpacing).toBe('normal');
      expect(card.valueSize).toBe('16px');
      expect(card.margin).toBe('0px');
      expect(card.labels).toBe(1);
      expect(card.values).toBe(1);
      expect(card.overflow).toBeLessThanOrEqual(1);
    }
  }
}

async function expectFlatFacts(facts, { padding = '0px', radius = '0px' } = {}) {
  expect(await facts.count()).toBeGreaterThan(0);
  for (const fact of await facts.all()) {
    await expect(fact).toBeVisible();
    await expect(fact).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
    await expect(fact).toHaveCSS('border-width', '0px');
    await expect(fact).toHaveCSS('padding', padding);
    if (radius !== null) await expect(fact).toHaveCSS('border-radius', radius);
  }
}

module.exports = { expectBoxedFacts, expectFlatFacts };
