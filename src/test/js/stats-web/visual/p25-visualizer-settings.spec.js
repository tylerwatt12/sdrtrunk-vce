'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectFormWorkflowGeometry, expectStableFormWorkflowInsets,
  captureFormWorkflowEvidence } = require('./fixtures/form-workflow-geometry.cjs');

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 320], ['dark', 320]]) {
  test(`P25 settings form spacing survives dirty and storage error states in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await installSiteStyleApplication(page, theme);
    await page.addInitScript(() => {
      const original = Storage.prototype.setItem;
      Storage.prototype.setItem = function(key, value) {
        if (key.startsWith('sdrtrunk-vce-p25-visualizer-events-v1:')) throw new Error('Storage unavailable');
        return original.call(this, key, value);
      };
    });
    await page.goto('/app.html?view=network-visualizer');
    await page.getByRole('button', { name: 'Settings', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'P25 Visualizer settings', exact: true });
    const form = dialog.locator('form');
    const before = await expectFormWorkflowGeometry(form);
    const choice = dialog.getByRole('checkbox').first();
    await choice.focus();
    await choice.press('Space');
    await expect(form).toHaveClass(/is-dirty/);
    await expectFormWorkflowGeometry(form);
    await expectStableFormWorkflowInsets(form, before);
    await captureFormWorkflowEvidence(form, `p25-settings-${theme}-${width}-dirty`);
    // Browser storage is synchronous, so this workflow has no sustained busy
    // layout; exercise the actual storage error without inventing a network save.
    await form.locator('.ui-form-submit').click();
    await expect(form.getByRole('alert')).toContainText('Visualizer choices could not be saved in this browser.');
    await expectFormWorkflowGeometry(form);
    await expectStableFormWorkflowInsets(form, before);
    await captureFormWorkflowEvidence(form, `p25-settings-${theme}-${width}-error`);
  });
}
