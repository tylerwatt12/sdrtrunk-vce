'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');
const { selectAudioPanel } = require('./audio-dock-controls.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const entries = ['Lists', 'Direct Listen', 'Avoid List', 'Listening settings'];

async function chooseSize(dock, name) {
  const handle = dock.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible');
  await handle.press({ collapsed: 'Home', minimal: 'Home', full: 'End' }[name]);
  if (name === 'minimal') await dock.locator('#audio-dock-slim-expand').press('ArrowUp');
  await expect(dock).toHaveAttribute('data-state', name);
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`one transport menu reaches every listening panel and returns focus in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const state = await openAudioApp(page, { theme, view: 'dashboard', feedCallsEnabled: false,
      capabilities: { 'direct-listen': true } });
    const dock = page.locator('#audio-dock');
    for (const size of ['collapsed', 'minimal', 'full']) {
      await chooseSize(dock, size);
      const trigger = dock.getByRole('button', { name: 'Player menu', exact: true }).filter({ visible: true });
      await expect(trigger).toHaveCount(1);
      await expect(dock.locator('.audio-dock-header').getByRole('button', { name: /^Lists(?:\s|$)/ })).toHaveCount(0);
      await expect(dock.locator('.audio-dock-header').getByRole('button', { name: 'Listening settings', exact: true })).toHaveCount(0);
      await expect(dock.getByRole('button', { name: 'Open Avoid List', exact: true })).toHaveCount(0);
      await trigger.focus();
      await trigger.press('Enter');
      await expect(dock.locator('#audio-dock-context-title')).toHaveText('Player menu');
      const panel = dock.locator('#audio-dock-panel');
      for (const label of entries) {
        const entry = panel.getByRole('button', { name: label, exact: true });
        await expect(entry).toBeInViewport({ ratio: 1 });
        const geometry = await entry.evaluate(button => ({ height: button.getBoundingClientRect().height,
          icons: [...button.querySelectorAll('svg')].map(icon => ({
            width: icon.getBoundingClientRect().width, height: icon.getBoundingClientRect().height })) }));
        expect(geometry.height).toBeLessThanOrEqual(72);
        for (const icon of geometry.icons) {
          expect(icon.width).toBeLessThanOrEqual(24);
          expect(icon.height).toBeLessThanOrEqual(24);
        }
      }
      if (size === 'full' && process.env.WEB_REVIEW_DIR) {
        fs.mkdirSync(process.env.WEB_REVIEW_DIR, { recursive: true });
        await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR, `player-menu-${theme}-${width}.png`) });
      }
      for (const label of entries) {
        const entry = panel.getByRole('button', { name: label, exact: true });
        await entry.focus();
        await entry.press('Enter');
        await expect(dock.locator('#audio-dock-context-title')).toHaveText(label);
        await expect(panel.locator('.audio-dock-context-footer button')).toHaveCount(label === 'Lists' ? 1 : 0);
        if (label === 'Lists') await expect(panel.getByRole('button', { name: 'View coverage', exact: true })).toBeVisible();
        const back = dock.locator('#audio-dock-context-back');
        await back.press('Enter');
        await expect(dock.locator('#audio-dock-context-title')).toHaveText('Player menu');
        await expect(entry).toBeFocused();
      }
      await dock.locator('#audio-dock-context-back').press('Enter');
      await expect(dock).toHaveAttribute('data-state', size);
      await expect(trigger).toBeFocused();
      await expectNoHorizontalOverflow(page);
    }
    await chooseSize(dock, 'full');
    await expect(dock.getByRole('button', { name: 'Hold current target', exact: true })).toBeVisible();
    await expect(dock.getByRole('button', { name: 'Avoid current target', exact: true })).toBeVisible();
    expect(state.feedRequests).toBe(0);
    expect(state.requests.filter(request => /admin\/channels\/.+\/(start|stop)/.test(request.path))).toEqual([]);
  });
}

test('the player menu keeps normal list controls when Direct Listen permission is absent', async ({ page }) => {
  await openAudioApp(page, { view: 'dashboard', feedCallsEnabled: false, capabilities: { 'direct-listen': false } });
  const dock = page.locator('#audio-dock');
  await selectAudioPanel(dock, 'Player menu');
  await expect(dock.locator('#audio-dock-panel').getByRole('button', { name: 'Direct Listen', exact: true })).toHaveCount(0);
  for (const name of ['Lists', 'Avoid List', 'Listening settings']) {
    await expect(dock.locator('#audio-dock-panel').getByRole('button', { name, exact: true })).toBeVisible();
  }
  await selectAudioPanel(dock, 'Lists');
  await expect(dock.getByRole('checkbox', { name: /^County Fire and Emergency Medical Services(?:\s|$)/ })).toBeVisible();
  await expect(dock.getByRole('checkbox', { name: 'Direct Listen', exact: true })).toHaveCount(0);
});
