const { expect } = require('@playwright/test');

// Hit testing detects painted children escaping a surface's rounded corners.
async function expectRoundedCorners(surface, edge = 'top') {
  await surface.scrollIntoViewIfNeeded();
  const hits = await surface.evaluate((element, edge) => {
    const box = element.getBoundingClientRect();
    const y = edge === 'bottom' ? box.bottom - 2 : box.top + 2;
    return [box.left + 2, box.right - 2, box.left + box.width / 2]
      .map(x => element.contains(document.elementFromPoint(x, y)));
  }, edge);
  expect(hits).toEqual([false, false, true]);
}

module.exports = { expectRoundedCorners };
