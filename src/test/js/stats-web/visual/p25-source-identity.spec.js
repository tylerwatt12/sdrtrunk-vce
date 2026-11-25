'use strict';

const { test, expect } = require('@playwright/test');
const { openAudioApp, recording, liveCall } = require('./fixtures/audio-dock-app.cjs');
const { selectAudioPanel } = require('./audio-dock-controls.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

test.setTimeout(12_000);

for (const presentation of [
  { name: 'light desktop', theme: 'light', viewport: { width: 1280, height: 900 } },
  { name: 'dark phone', theme: 'dark', viewport: { width: 390, height: 844 } }
]) {
  for (const known of [false, true]) {
    test(`${known ? 'full identity' : 'unknown source'} replaces radio zero in ${presentation.name}`, async ({ page }) => {
      await page.setViewportSize(presentation.viewport);
      const call = { ...recording, source_id: 0,
        source_alias: known ? 'OWNED CONFIGURED NAME' : 'PREVIOUS CONFIGURED NAME',
        source_ota_alias: known ? 'OWNED OTA NAME' : 'PREVIOUS OTA NAME',
        source_home_wacn: known ? 0xABCDE : null,
        source_home_system_id: known ? 0x123 : null,
        source_home_id: known ? 501 : null,
        source_entity_ref: known ? { kind: 'radio', radio_system_key: recording.system_key,
          identity_key: 'v1-r-abcde-123-501' } : null };
      await openAudioApp(page, { calls: [call], theme: presentation.theme, feedCallsEnabled: false });
      await page.locator('.recordings-call-info').first().click();
      const actions = page.getByRole('dialog', { name: 'Call actions', exact: true });
      if (known) await expect(actions).toContainText('ABCDE.123.501');
      await expect(actions).not.toContainText('Unknown radio');
      await expect(actions).not.toContainText('PREVIOUS OTA NAME');
      await expect(actions).not.toContainText('PREVIOUS CONFIGURED NAME');
      await actions.getByRole('button', { name: /^Play once/ }).click();
      const dock = page.locator('#audio-dock');
      await expect(dock).toHaveAttribute('data-source', 'recordings');
      await dock.locator('.audio-dock-compact-copy').click();
      await expect(dock).toHaveAttribute('data-state', 'full');
      await selectAudioPanel(dock, 'Details');
      if (known) await expect(dock).toContainText('ABCDE.123.501');
      await expect(dock).not.toContainText('Unknown radio');
      await expect(dock).not.toContainText('Radio 0');
      await expect(dock).not.toContainText('PREVIOUS OTA NAME');
      await expect(dock).not.toContainText('PREVIOUS CONFIGURED NAME');
      await expectNoHorizontalOverflow(page);
      if (process.env.WEB_REVIEW_DIR) await page.screenshot({
        path: `${process.env.WEB_REVIEW_DIR}/${known ? 'qualified-zero' : 'unknown-zero'}-${presentation.theme}.png` });
    });

    test(`live details preserve ${known ? 'full identity' : 'unknown ownership'} in ${presentation.name}`, async ({ page }) => {
      await page.setViewportSize(presentation.viewport);
      await openAudioApp(page, { theme: presentation.theme, feedCallsEnabled: false });
      let delivered = false;
      const call = { ...liveCall, protocol: 'APCO25_PHASE2', source_id: 0,
        source_alias: known ? 'OWNED CONFIGURED NAME' : 'PREVIOUS CONFIGURED NAME',
        talker_alias: known ? 'OWNED OTA NAME' : 'PREVIOUS OTA NAME',
        source_description: known ? 'Owned description' : 'PREVIOUS DESCRIPTION',
        source_group: known ? 'Owned group' : 'PREVIOUS GROUP',
        source_canonical_identity: known ? { wacn: 0xABCDE, system_id: 0x123, subscriber_id: 501 } : null };
      await page.route('**/api/v1/calls/feed*', async route => {
        const calls = delivered ? [] : [call];
        delivered = true;
        await route.fulfill({ json: { data: { cursor: delivered ? '1' : '0', reset: false, calls } } });
      });
      const dock = page.locator('#audio-dock');
      await dock.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').press('End');
      await expect(dock).toHaveAttribute('data-state', 'full');
      await dock.getByRole('button', { name: 'Listen live', exact: true }).click();
      if (known) await expect(dock).toContainText('OWNED OTA NAME');
      await expect(dock).not.toContainText('Unknown radio');
      await selectAudioPanel(dock, 'Details');
      await dock.locator('summary').filter({ hasText: 'Identity & aliases' }).click();
      if (known) await expect(dock).toContainText('ABCDE.123.501');
      await expect(dock).not.toContainText('Unknown radio');
      await expect(dock).not.toContainText('PREVIOUS');
      await expect(dock).not.toContainText('Radio 0');
      await expectNoHorizontalOverflow(page);
      if (process.env.WEB_REVIEW_DIR) await page.screenshot({
        path: `${process.env.WEB_REVIEW_DIR}/live-${known ? 'qualified-zero' : 'unknown-zero'}-${presentation.theme}.png` });
    });
  }
}
