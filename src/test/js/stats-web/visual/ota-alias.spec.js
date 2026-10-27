'use strict';

const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { installSiteStyleApplication, systemKey, radioKey } = require('./fixtures/site-style-app.cjs');
const { presentations } = require('./fixtures/site-style-cases.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

for (const presentation of presentations) {
  test(`forget OTA alias waits for completion ${presentation.name}`, async ({ page }) => {
    await page.setViewportSize(presentation.viewport);
    const fixture = await installSiteStyleApplication(page, presentation.theme);
    let alias = 'Observed unit name', submitted = null, finished = false, failed = false;
    await page.route('**/api/v1/radio-systems/**/radios/**', route => route.fulfill({ json: { data: {
      protocol: 'P25', radio_system_key: systemKey, identity_key: radioKey, native_id: 30914,
      alias_name: 'Engine 4', last_talker_alias: alias, system_name: 'Metro Public Safety',
      logical_call_count: 17, groups: 1, capabilities: { talker_aliases: true },
      entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: radioKey }
    } } }));
    await page.route('**/api/v1/admin/retained-statistics/deletions**', route => {
      if (route.request().method() === 'POST') submitted = route.request().postDataJSON();
      const state = failed ? 'failed' : finished ? 'succeeded' : 'running';
      if (finished) alias = null;
      return route.fulfill({ json: { data: { job_id: submitted.request_id, state, error: failed ? 'Alias update failed.' : null, outcome: finished ? 'deleted' : null } } });
    });
    await page.goto(`/app.html?view=radio&radio_system_key=${encodeURIComponent(systemKey)}&identity_key=${radioKey}`);
    const forget = page.getByRole('button', { name: 'Forget OTA alias', exact: true });
    await expect(forget).toBeVisible();
    await forget.click();
    const confirmation = page.getByRole('alertdialog', { name: 'Forget OTA alias?' });
    await expect(confirmation).toContainText('Activity history stays.');
    await expect(confirmation.getByRole('button', { name: 'Cancel', exact: true })).toBeFocused();
    await expectNoHorizontalOverflow(page, '#content');
    if (process.env.WEB_REVIEW_DIR) await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR,
      `ota-alias-confirm-${presentation.name}.png`), fullPage: true });
    await confirmation.getByRole('button', { name: 'Cancel', exact: true }).click();
    expect(submitted).toBeNull();
    await forget.click();
    await confirmation.getByRole('button', { name: 'Forget alias', exact: true }).click();
    await expect(forget).toBeDisabled();
    await expect(page.getByRole('status').filter({ hasText: 'Forgetting OTA alias' })).toBeVisible();
    await expect.poll(() => submitted).not.toBeNull();
    expect(submitted.target).toEqual({ kind: 'talker_alias', radio_system_key: systemKey,
      identity_key: radioKey, expected_alias: 'Observed unit name' });
    const firstRequest = submitted.request_id;
    failed = true;
    const retry = page.getByRole('button', { name: 'Retry forgetting alias', exact: true });
    await expect(retry).toBeEnabled();
    await expect(page.getByRole('alert').filter({ hasText: 'Alias update failed.' })).toBeVisible();
    failed = false;
    await retry.click();
    await confirmation.getByRole('button', { name: 'Forget alias', exact: true }).click();
    await expect.poll(() => submitted.request_id).not.toBe(firstRequest);
    await expect(retry).toBeDisabled();
    finished = true;
    await expect(page.getByRole('button', { name: /forgetting alias|Forget OTA alias/ })).toHaveCount(0);
    await expect(page.locator('#content')).toContainText('17');
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });
}
