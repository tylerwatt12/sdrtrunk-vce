'use strict';
const { expect, test } = require('@playwright/test');

const examples = [
  { id: 'rtl-single', label: '1× RTL-SDR (unlocked)', receivers: 1 },
  { id: 'rtl', label: '2× RTL-SDR', receivers: 2 },
  { id: 'rtl-three', label: '3× RTL-SDR', receivers: 3 },
  { id: 'airspy', label: '1× Airspy', receivers: 1 }
];

for (const theme of ['light', 'dark']) {
  test(`coverage gallery compares actual tuner examples with keyboard controls in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    const start = new Date('2026-10-24T12:00:00Z');
    await page.clock.install({ time: start });
    await page.clock.pauseAt(new Date(start.getTime() + 1000));
    await page.goto(`/design-system.html?view=setup-guide&diagram=tuner-coverage&theme=${theme}`);
    const guide = page.locator('.ui-setup-guide');
    const graph = guide.locator('[data-guide-diagram="tuner-coverage"]');
    await expect(page.locator('.visual-channels-example')).toHaveAttribute('data-ready', 'true');
    await expect(guide).toHaveAttribute('data-guide-id', 'tuners');
    await expect(guide).toHaveAttribute('data-guide-step', 'overview');
    await expect(guide.getByRole('heading', { name: 'One receiver, several channels', exact: true })).toBeVisible();
    await expect(guide.locator('.ui-setup-guide-description')).toHaveCount(3);
    await expect(graph).toHaveAttribute('data-example', 'rtl');
    await expect(graph).toHaveAttribute('data-playing', 'true');
    await page.clock.runFor(8000);
    const choices = graph.getByRole('group', { name: 'Example tuner hardware', exact: true });
    await expect(choices.getByRole('button')).toHaveText(examples.map(example => example.label));
    await choices.getByRole('button', { name: '2× RTL-SDR', exact: true }).focus();
    await page.keyboard.press('Shift+Tab');
    await graph.evaluate(element => { window.galleryCoverageRoot = element; });
    const time = Number(await graph.getAttribute('data-time'));
    let sharedCalls;
    const missed = {};
    for (const [index, example] of examples.entries()) {
      const button = choices.getByRole('button', { name: example.label, exact: true });
      if (index) await page.keyboard.press('Tab');
      await expect(button).toBeFocused();
      await page.keyboard.press(index % 2 ? 'Enter' : 'Space');
      await expect(graph).toHaveAttribute('data-example', example.id);
      await expect(button).toHaveAttribute('aria-pressed', 'true');
      await expect(choices.locator('[aria-pressed="true"]')).toHaveCount(1);
      await expect(graph.locator('.ui-setup-guide-coverage-receiver')).toHaveCount(example.receivers);
      const calls = await graph.locator('[data-call]').evaluateAll(items => items.map(item => item.dataset.call));
      if (!sharedCalls) sharedCalls = calls;
      expect(calls).toEqual(sharedCalls);
      expect(Math.abs(Number(await graph.getAttribute('data-time')) - time)).toBeLessThan(.3);
      missed[example.id] = await graph.locator('[data-received="false"]').count();
      if (example.id === 'rtl-single') await expect(graph.locator('.ui-setup-guide-coverage-receiver')).toContainText('Unlocked');
      if (example.id === 'airspy') {
        await expect(graph.locator('.ui-setup-guide-coverage-receiver')).toContainText('9 MHz usable');
        expect(missed.airspy).toBe(0);
      }
      expect(await graph.evaluate(element => element === window.galleryCoverageRoot)).toBe(true);
      const bounds = await guide.boundingBox();
      expect(bounds.x).toBeGreaterThanOrEqual(0);
      expect(bounds.y).toBeGreaterThanOrEqual(0);
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(1280);
      expect(bounds.y + bounds.height).toBeLessThanOrEqual(900);
      await expect(guide).toHaveScreenshot(`coverage-gallery-${example.id}-${theme}.png`);
    }
    expect(missed['rtl-single']).toBeGreaterThan(missed.rtl);
    expect(missed['rtl-three']).toBeLessThan(missed.rtl);
    await page.keyboard.press('Shift+Tab');
    await page.keyboard.press('Shift+Tab');
    await expect(choices.getByRole('button', { name: '2× RTL-SDR', exact: true })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(graph).toHaveAttribute('data-example', 'rtl');
    await page.keyboard.press('Escape');
    await expect(guide).toHaveCount(0);
    expect(await page.evaluate(() => window.galleryCoverageRoot.dataset.disposed)).toBe('true');
  });
}
