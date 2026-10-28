'use strict';

const { test, expect } = require('@playwright/test');
const { installAliasSectionGroups } = require('./fixtures/alias-section-groups.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

async function expectIndependentGroups(page, dialog, labels) {
  const groups = dialog.locator('.ui-form-section-filled:visible');
  await expect(groups).toHaveCount(labels.length);
  await expect(groups.locator(':scope > legend')).toHaveText(labels);
  for (const group of await groups.all()) {
    const geometry = await group.evaluate(element => {
      const box = element.getBoundingClientRect();
      const heading = element.querySelector('legend').getBoundingClientRect();
      const css = getComputedStyle(element);
      return { fill: css.backgroundColor, headingInset: heading.y - box.y,
        padding: parseFloat(css.paddingTop), left: box.x, right: box.right, width: innerWidth };
    });
    expect(geometry.fill).not.toBe('rgba(0, 0, 0, 0)');
    expect(geometry.headingInset).toBeGreaterThanOrEqual(geometry.padding - 1);
    expect(geometry.left).toBeGreaterThanOrEqual(0);
    expect(geometry.right).toBeLessThanOrEqual(geometry.width);
  }
  // Transparent independent groups must not silently disappear from the audit.
  // Measure their composed paint against the modal, including custom palettes.
  for (const hue of [null, 0, 120, 240]) {
    await page.evaluate(async value => {
      const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
      applyThemeHue(value);
    }, hue);
    const audit = await expectContrast(page, { rootSelector: '.alias-editor-modal',
      reportName: `alias-sections-${hue ?? 'default'}` });
    const paintedGroups = audit.surfaces.filter(surface =>
      surface.selector.split(' > ').at(-1).startsWith('fieldset'));
    expect(paintedGroups).toHaveLength(labels.length);
    for (const surface of paintedGroups) expect(surface.ratio).toBeGreaterThanOrEqual(1.05);
  }
  await page.evaluate(async () => (await import('/assets/core/theme.js?v=2')).applyThemeHue(null));
  await expectNoHorizontalOverflow(page);
}

for (const theme of ['light', 'dark']) {
  for (const width of [1280, 390]) {
    test(`Alias call handling keeps independent groups visible in ${theme} at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await installAliasSectionGroups(page, theme);
      await page.goto('/app.html?view=aliases&list=1&aliasTab=configure&alias=101');
      const editor = page.getByRole('dialog', { name: 'Edit Fire Dispatch', exact: true });
      await editor.getByRole('tab', { name: 'Call Handling', exact: true }).click();
      await expectIndependentGroups(page, editor, ['Scan list membership', 'Recording', 'Streaming destinations']);
      const streaming = editor.getByRole('group', { name: 'Streaming destinations', exact: true });
      const streamAs = streaming.getByRole('spinbutton', { name: 'Stream as talkgroup' });
      await expect(streamAs).toBeVisible();
      const fieldBox = await streamAs.boundingBox();
      const groupBox = await streaming.boundingBox();
      expect(fieldBox.width).toBeGreaterThan(groupBox.width * .8);
      await streamAs.focus();
      await expect(streamAs).toBeFocused();
      await streamAs.fill('1202');
      await editor.getByRole('button', { name: 'Cancel', exact: true }).click();
      const discard = page.getByRole('alertdialog', { name: 'Discard unsaved changes' });
      await discard.getByRole('button', { name: 'Keep editing', exact: true }).click();
      await expect(streamAs).toHaveValue('1202');
      await editor.getByRole('button', { name: 'Cancel', exact: true }).click();
      await discard.getByRole('button', { name: 'Discard changes', exact: true }).click();
      await expect(editor).toHaveCount(0);

      await page.locator('.alias-policy-button').click();
      const defaults = page.getByRole('dialog', { name: /Call Handling Defaults/ });
      for (const tab of ['Unmatched Calls', 'New Aliases']) {
        await defaults.getByRole('tab', { name: tab, exact: true }).click();
        await expectIndependentGroups(page, defaults, ['Recording', 'Scan List', 'Streaming']);
      }
      await page.keyboard.press('Escape');
      await expect(defaults).toHaveCount(0);
      await expect(page.locator('.alias-policy-button')).toBeFocused();
    });
  }
}
