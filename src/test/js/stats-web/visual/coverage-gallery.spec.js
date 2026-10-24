'use strict';
const { expect, test } = require('@playwright/test');

for (const theme of ['light', 'dark']) {
  test(`coverage gallery compares actual tuner examples with keyboard controls in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    // Freeze before mounting so loading time cannot change the illustrated traffic.
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
    const rtl = choices.getByRole('button', { name: '2× RTL-SDR', exact: true });
    const airspy = choices.getByRole('button', { name: '1× Airspy', exact: true });
    await expect(rtl).toHaveAttribute('aria-pressed', 'true');
    await expect(airspy).toHaveAttribute('aria-pressed', 'false');
    await expect(graph.locator('.ui-setup-guide-coverage-receiver')).toHaveCount(2);
    expect(await graph.locator('[data-received="false"]').count()).toBeGreaterThan(0);
    await graph.evaluate(element => { window.galleryCoverageRoot = element; });
    await rtl.focus();
    await expect(guide).toHaveScreenshot(`coverage-gallery-rtl-${theme}.png`);
    await page.keyboard.press('Tab');
    await expect(airspy).toBeFocused();
    await page.keyboard.press('Space');
    await expect(graph).toHaveAttribute('data-example', 'airspy');
    await expect(airspy).toHaveAttribute('aria-pressed', 'true');
    await expect(rtl).toHaveAttribute('aria-pressed', 'false');
    await expect(graph.locator('.ui-setup-guide-coverage-receiver')).toHaveCount(1);
    await expect(graph.locator('.ui-setup-guide-coverage-receiver')).toContainText('9 MHz usable');
    await expect(graph.locator('[data-received="false"]')).toHaveCount(0);
    expect(await graph.locator('[data-received="true"]').count()).toBeGreaterThan(0);
    expect(await graph.evaluate(element => element === window.galleryCoverageRoot)).toBe(true);
    await expect(guide).toHaveScreenshot(`coverage-gallery-airspy-${theme}.png`);
    await page.keyboard.press('Shift+Tab');
    await expect(rtl).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(graph).toHaveAttribute('data-example', 'rtl');
    await expect(rtl).toHaveAttribute('aria-pressed', 'true');
    await expect(graph.locator('.ui-setup-guide-coverage-receiver')).toHaveCount(2);
    await page.keyboard.press('Escape');
    await expect(guide).toHaveCount(0);
    expect(await page.evaluate(() => window.galleryCoverageRoot.dataset.disposed)).toBe('true');
  });
}
