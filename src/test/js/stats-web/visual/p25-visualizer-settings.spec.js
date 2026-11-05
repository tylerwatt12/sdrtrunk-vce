'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectFormWorkflowGeometry, expectStableFormWorkflowInsets,
  captureFormWorkflowEvidence } = require('./fixtures/form-workflow-geometry.cjs');

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 320], ['dark', 320]]) {
  test(`P25 settings form spacing survives dirty and save error states in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await installSiteStyleApplication(page, theme);
    let rejectSave = true;
    await page.route('**/api/v1/me/preferences', route => {
      if (route.request().method() === 'PUT' && rejectSave) return route.fulfill({ status: 503,
        json: { error: { message: 'Preferences unavailable.' } } });
      return route.fallback();
    });
    await page.goto('/app.html?view=network-visualizer');
    await page.getByRole('button', { name: 'Actions', exact: true }).click();
    const dialog = width < 600 ? page.getByRole('dialog', { name: 'P25 actions', exact: true }) :
      page.locator('.network-visualizer-events');
    const form = dialog.locator('form');
    const options = { modal: width < 600 };
    const before = await expectFormWorkflowGeometry(form, options);
    const choice = dialog.getByRole('checkbox').first();
    await choice.focus();
    await choice.press('Space');
    await expect(form).toHaveClass(/is-dirty/);
    await expectFormWorkflowGeometry(form, options);
    await expectStableFormWorkflowInsets(form, before);
    await captureFormWorkflowEvidence(form, `p25-settings-${theme}-${width}-dirty`);
    await form.locator('.ui-form-submit').click();
    await expect(form.getByRole('alert')).toContainText('Visualizer choices could not be saved. Try again.');
    await expectFormWorkflowGeometry(form, options);
    await expectStableFormWorkflowInsets(form, before);
    await captureFormWorkflowEvidence(form, `p25-settings-${theme}-${width}-error`);
    rejectSave = false;
    await form.locator('.ui-form-submit').click();
    await expect(form).not.toHaveClass(/is-dirty/);
    await expect(form.getByRole('alert')).toHaveCount(0);
  });
}
