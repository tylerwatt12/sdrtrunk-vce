'use strict';
const { test, expect } = require('@playwright/test');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');
const fs = require('node:fs');
const path = require('node:path');
const { selectAudioPanel } = require('./audio-dock-controls.cjs');

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Direct Listen gallery membership and contrast ${theme} ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.clock.setFixedTime(new Date('2026-09-30T14:24:18Z'));
    await page.goto(`/design-system.html?${new URLSearchParams({ view: 'audio-dock', theme,
      audioFixture: 'direct', audioState: 'full', audioPanel: 'direct' })}`);
    const dock = page.locator('#audio-dock');
    await expect(dock.locator('#audio-dock-panel')).toContainText('Fire Dispatch');
    await expect(dock.locator('#audio-dock-panel')).toContainText('County Fireground');
    await expectNoHorizontalOverflow(page);
    await expectContrast(page, { rootSelector: '#audio-dock-panel', reportName: `direct-listen-${theme}-${width}` });
    const evidence = process.env.DIRECT_LISTEN_EVIDENCE;
    if (evidence) {
      fs.mkdirSync(evidence, { recursive: true });
      await page.screenshot({ path: path.join(evidence, `gallery-${theme}-${width}.png`) });
    }
    await dock.getByRole('button', { name: 'Remove Fire Dispatch from Direct Listen', exact: true }).press('Enter');
    await expect(dock.getByRole('button', { name: 'Remove County Fireground from Direct Listen', exact: true })).toBeFocused();
    await selectAudioPanel(dock, 'Lists');
    const selection = dock.getByRole('checkbox', { name: 'Direct Listen', exact: true });
    await expect(selection).toBeChecked();
    await selection.uncheck();
    await expect(selection).not.toBeChecked();
    await selectAudioPanel(dock, 'Direct Listen');
    await expect(dock.locator('#audio-dock-panel')).toContainText('County Fireground');
    await dock.getByRole('button', { name: 'Remove County Fireground from Direct Listen', exact: true }).click();
    await expect(dock.locator('#audio-dock-panel')).toContainText('No Direct Listen targets');
    await page.evaluate(async () => {
      const { applyThemeHue } = await import('/assets/core/theme.js');
      applyThemeHue(35);
    });
    await expectContrast(page, { rootSelector: '#audio-dock-panel', reportName: `direct-listen-empty-${theme}-${width}-35` });
  });
}
