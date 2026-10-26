'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { test, expect } = require('@playwright/test');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');
const { installSiteStyleApplication, systemKey, channelId, groupKey, radioKey } =
  require('./fixtures/site-style-app.cjs');

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 390], ['dark', 390]]) {
  test(`Actions stays usable with live counts in ${theme} at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 844 });
    const fixture = await installSiteStyleApplication(page, theme);
    await page.clock.setSystemTime(new Date('2026-10-01T12:00:00Z'));
    const rendererPath = path.resolve(__dirname, '../../../../../stats-web/assets/features/network-visualizer/renderer.js');
    const source = fs.readFileSync(rendererPath, 'utf8').replace("\n  host.dataset.rendererState = 'ready';", `
  window.p25ActionsPainted = () => { let meshes = 0;
    graph.scene().traverse(object => { if (object.userData?.p25Node) meshes += 1; });
    return meshes >= 3 && graph.renderer().info.render.calls > 0;
  };
  host.dataset.rendererState = 'ready';`);
    await page.route('**/assets/features/network-visualizer/renderer.js?**', route =>
      route.fulfill({ contentType: 'text/javascript', body: source }));
    await page.goto('/app.html?view=network-visualizer');
    await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
    await expect(page.locator('.network-visualizer-status')).toHaveText('Live');
    await expect.poll(() => page.evaluate(() => window.p25ActionsPainted?.())).toBe(true);
    await page.evaluate(({ systemKey, channelId, groupKey, radioKey }) => {
      window.fixtureSendLiveFrame(7, 'activity_append', { source_epoch: 'fixture-p25',
        after_id: 1, next_after_id: 3, watermark_id: 3,
        rows: ['EMERGENCY', 'CALL'].map((action, index) => ({
          id: index + 2, observed_at_ms: Date.now(), action, protocol: 'P25', channel_kind: 'TRUNKED_SITE',
          radio_system_key: systemKey, configuration_id: channelId, system_name: 'County P25',
          source_identity_key: radioKey, source_radio_id: 30914, source_alias_name: 'Engine 4',
          target_identity_key: groupKey, target_kind: 'talkgroup', target_id: 1201, target_alias_name: 'Fire Dispatch'
        })) });
    }, { systemKey, channelId, groupKey, radioKey });
    await page.getByRole('button', { name: 'Actions', exact: true }).click();
    const actions = page.locator('.network-visualizer-events');
    await expect(actions.locator('[data-category="emergency"] .ui-pill')).toHaveText('1');
    await expect(actions.locator('[data-category="call"] .ui-pill')).toHaveText('1');
    const directory = process.env.P25_ACTIONS_EVIDENCE;
    if (directory) fs.mkdirSync(directory, { recursive: true });
    await page.screenshot({ path: directory ? path.join(directory, `actions-${theme}-${width}.png`) :
      testInfo.outputPath(`actions-${theme}-${width}.png`) });
    await expectContrast(page, { rootSelector: '.network-visualizer-events', reportName: `actions-${theme}-${width}` });
    for (const hue of [0, 120, 240]) {
      await page.evaluate(async value => {
        const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
        applyThemeHue(value);
      }, hue);
      await expectContrast(page, { rootSelector: '.network-visualizer-events', reportName: `actions-${theme}-${width}-hue-${hue}` });
    }
    await page.evaluate(async () => {
      const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
      applyThemeHue(null);
    });
    const callHighlight = actions.getByRole('checkbox', { name: 'Highlight Calls and grants', exact: true });
    await callHighlight.focus();
    await callHighlight.press('Space');
    await expect(callHighlight).not.toBeChecked();
    await actions.getByRole('button', { name: 'Save choices', exact: true }).click();
    await expect(actions.locator('form')).not.toHaveClass(/is-dirty/);
    await expect(actions.locator('[data-category="call"] .ui-pill')).toHaveText('1');
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });
}
