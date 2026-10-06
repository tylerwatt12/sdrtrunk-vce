'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { galleryViews, gallerySelector, presentations } = require('./fixtures/site-style-cases.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

test('style matrix includes every registered gallery composition', () => {
  const source = fs.readFileSync(path.join(__dirname, 'fixtures/design-system-gallery.js'), 'utf8');
  const registry = source.slice(source.indexOf('const view = ['),
    source.indexOf(".includes(parameters.get('view'))"));
  expect([...registry.matchAll(/'([^']+)'/g)].map((match) => match[1]).sort())
    .toEqual([...galleryViews].sort());
});

test('trunked results gallery keeps selection and filtering when shared signal details open', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?view=spectrum-search&theme=light');
  const search = page.locator('.spectrum-search-modal');
  const allMarcs = search.getByRole('checkbox', { name: 'Select all channels in Ohio MARCS-IP: Multi-Agency Radio Communications', exact: true });
  await expect(allMarcs).toHaveJSProperty('indeterminate', true);
  await allMarcs.check();
  await expect(search.getByRole('checkbox', { name: 'Select County Central', exact: true })).toBeChecked();
  const query = search.getByLabel('Search results', { exact: true });
  await query.fill('Regional');
  await expect(search.locator('tbody tr:visible')).toHaveCount(1);
  await expect(search.getByRole('button', { name: 'Review 2 channels', exact: true })).toBeEnabled();
  await query.fill('');
  const workspace = await search.locator('.spectrum-search-workspace').elementHandle();
  const details = search.getByRole('button', { name: 'Details for 773.08125 MHz', exact: true });
  await details.click();
  const child = page.locator('.spectrum-search-detail-modal');
  await expect(child.getByRole('heading', { name: 'Signal details · 773.08125 MHz', exact: true })).toBeVisible();
  expect(await search.evaluate((element) => element.closest('.modal-backdrop').inert)).toBe(true);
  await child.screenshot({ path: testInfo.outputPath('gallery-signal-details.png') });
  await page.keyboard.press('Escape');
  await expect(child).toHaveCount(0);
  await expect(details).toBeFocused();
  expect(await search.locator('.spectrum-search-workspace').evaluate((element, previous) => element === previous, workspace)).toBe(true);
  await expect(search.getByRole('checkbox', { name: 'Select County North', exact: true })).toBeChecked();
  await expect(search.getByRole('checkbox', { name: 'Select County Central', exact: true })).toBeChecked();
});

for (const view of galleryViews) {
  for (const presentation of presentations) {
    test(`gallery ${view} ${presentation.name}`, async ({ page }) => {
      await page.setViewportSize(presentation.viewport);
      await page.goto(`/design-system.html?view=${view}&theme=${presentation.theme}`);
      await expect(page.locator('body')).toHaveAttribute('data-gallery-view', view);
      const example = page.locator(gallerySelector(view));
      await expect(example).toBeVisible();
      if (view === 'focus') await page.locator('#visual-focus-target').focus();
      await expectNoHorizontalOverflow(page);
      if (process.env.STYLE_AUDIT_ASSERTIONS_ONLY !== '1') {
        await expect(example).toHaveScreenshot(`${view}-${presentation.name}.png`);
      }
    });
  }
}

for (const { theme, name, viewport } of presentations) {
  test(`shared controls preserve typography and interaction states ${name}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?view=control-states&theme=${theme}`);
    const host = page.locator('.visual-control-states-example');
    const styles = await host.evaluate((element) => {
      const roles = ['normal', 'primary', 'danger', 'input', 'select', 'framed-select', 'segment',
        'button-choice', 'compact-action', 'compact-input', 'compact-select'];
      return roles.map((role) => {
        const control = element.querySelector(`[data-state="${role}"]`);
        const style = getComputedStyle(control);
        return { role, family: style.fontFamily, fontSize: style.fontSize,
          radius: style.borderRadius, height: control.getBoundingClientRect().height };
      });
    });
    // A native button-based choice must inherit the same family as its label-based sibling.
    const bodyFamily = await page.locator('body').evaluate((element) => getComputedStyle(element).fontFamily);
    for (const style of styles) {
      expect(style.family, `${style.role} inherits the product font`).toBe(bodyFamily);
      expect(style.height, `${style.role} keeps a usable target`).toBeGreaterThanOrEqual(34);
    }
    const controls = styles.filter((style) => style.role !== 'button-choice');
    expect(new Set(controls.map((style) => style.fontSize)).size).toBe(1);
    expect(new Set(controls.filter((style) => style.role !== 'segment').map((style) => style.radius)).size).toBe(1);
    const normal = host.locator('[data-state="normal"]');
    for (const selector of ['[data-state=normal]', '[data-state=primary]', '[data-state=danger]', '[data-state=input]', '[data-state=select]']) {
      const contrast = await host.locator(selector).evaluate((element) => {
        const style = getComputedStyle(element);
        const luminance = (value) => {
          const channels = value.match(/[\d.]+/g).slice(0, 3).map(Number).map(channel => {
            const component = channel / 255;
            return component <= 0.04045 ? component / 12.92 : ((component + 0.055) / 1.055) ** 2.4;
          });
          return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
        };
        const foreground = luminance(style.color), background = luminance(style.backgroundColor);
        return (Math.max(foreground, background) + 0.05) / (Math.min(foreground, background) + 0.05);
      });
      expect(contrast, `${selector} text remains readable`).toBeGreaterThanOrEqual(4.5);
    }
    const rest = await normal.evaluate((element) => getComputedStyle(element).backgroundColor);
    await normal.hover();
    await expect.poll(() => normal.evaluate((element) => getComputedStyle(element).backgroundColor))
      .not.toBe(rest);
    await page.keyboard.press('Tab');
    await normal.focus();
    await expect(normal).toBeFocused();
    expect(await normal.evaluate((element) => {
      const style = getComputedStyle(element);
      return parseFloat(style.outlineWidth) > 0 || style.boxShadow !== 'none';
    })).toBe(true);
    for (const state of ['disabled-action', 'disabled-segment', 'disabled-select']) {
      const disabled = host.locator(`[data-state="${state}"]`);
      await expect(disabled).toBeDisabled();
      await expect(disabled).toHaveCSS('cursor', 'not-allowed');
    }
    await expect(host.getByRole('checkbox', { name: 'Unavailable setting' })).toBeDisabled();
    await expectNoHorizontalOverflow(page);
    if (process.env.STYLE_AUDIT_ASSERTIONS_ONLY !== '1') {
      await expect(host).toHaveScreenshot(`control-interactions-${name}.png`);
    }
  });
}

for (const [name, viewport] of [
  ['small-phone', { width: 320, height: 568 }],
  ['tablet', { width: 768, height: 1024 }],
  ['landscape', { width: 844, height: 390 }]
]) {
  for (const view of ['app-chrome', 'admin-navigation', 'control-states', 'mobile-table', 'modal-long', 'scanner']) {
    test(`shared layout ${view} ${name}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await page.goto(`/design-system.html?view=${view}&theme=dark`);
      await expect(page.locator(gallerySelector(view))).toBeVisible();
      await expectNoHorizontalOverflow(page);
      if (view === 'modal-long') {
        const modal = page.locator('.visual-modal-example .read-only-modal');
        const box = await modal.boundingBox();
        expect(box.x).toBeGreaterThanOrEqual(-1);
        expect(box.y).toBeGreaterThanOrEqual(-1);
        expect(box.x + box.width).toBeLessThanOrEqual(viewport.width + 1);
        expect(box.y + box.height).toBeLessThanOrEqual(viewport.height + 1);
        const content = modal.locator('.modal-content');
        await content.evaluate((element) => { element.scrollTop = element.scrollHeight; });
        expect(await content.evaluate((element) => element.scrollLeft)).toBe(0);
      }
    });
  }
}
