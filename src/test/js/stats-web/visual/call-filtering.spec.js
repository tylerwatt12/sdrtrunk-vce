'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { openPagingToneApp } = require('./fixtures/paging-tone-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const panel = page => page.locator('[data-call-filtering]');
const toggle = page => panel(page).getByRole('checkbox', { name: 'Filter low-quality calls', exact: true });
const minimum = page => panel(page).getByRole('spinbutton', { name: 'Minimum usable voice (%)', exact: true });
const save = page => panel(page).getByRole('button', { name: 'Save', exact: true, includeHidden: true });
const reload = page => panel(page).getByRole('button', { name: 'Reload settings', exact: true });
async function screenshot(page, name) {
  if (process.env.WEB_REVIEW_DIR) await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR, `${name}.png`), fullPage: true, animations: 'disabled' });
  await expectContrast(page, { rootSelector: '[data-call-filtering]', reportName: name });
}

test('call filtering starts off, requires a chosen threshold, locks writes and keeps drafts through failures', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 1000 });
  const app = await openPagingToneApp(page, { holdCallFilteringStatus: true });
  const state = app.callFiltering;
  await expect(panel(page)).toContainText('Loading call filtering settings…');
  await expect(toggle(page)).toBeDisabled(); await expect(save(page)).toBeDisabled();
  state.releaseStatus();
  await expect(toggle(page)).toBeEnabled(); await expect(toggle(page)).not.toBeChecked();
  await expect(minimum(page)).toBeHidden(); await expect(save(page)).toBeHidden();
  await expect(page.locator('.admin-settings-content [data-call-filtering]')).toHaveCount(1);
  expect(await panel(page).evaluate(element => element.nextElementSibling.hasAttribute('data-paging-tone-detection'))).toBe(true);
  await screenshot(page, 'call-filtering-off-light-desktop');
  await toggle(page).press('Space');
  await expect(minimum(page)).toBeVisible(); await expect(minimum(page)).toBeEmpty();
  await expect(save(page)).toBeDisabled(); expect(state.writes).toHaveLength(0);
  await minimum(page).fill('65');
  const release = state.holdWrite(); await save(page).click();
  await expect(panel(page).locator('form')).toHaveAttribute('aria-busy', 'true');
  await expect(toggle(page)).toBeDisabled(); await expect(minimum(page)).toBeDisabled(); await expect(reload(page)).toBeDisabled();
  await panel(page).locator('form').evaluate(form => form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
  await expect.poll(() => state.writes.length).toBe(1); release();
  await expect(save(page)).toBeHidden(); await expect(toggle(page)).toBeEnabled();
  expect(state.writes[0].body).toEqual({ enabled: true, minimum_usable_voice_percent: 65 });
  expect(state.writes[0].headers['if-match']).toBe(`"${'a'.repeat(64)}"`);
  expect(state.writes[0].headers['x-csrf-token']).toBe('test-only-csrf');
  await toggle(page).press('Space'); await expect(minimum(page)).toBeHidden(); await save(page).click();
  await expect(save(page)).toBeHidden();
  expect(state.writes.at(-1).body).toEqual({ enabled: false, minimum_usable_voice_percent: 65 });
  await toggle(page).press('Space'); await expect(minimum(page)).toHaveValue('65');
  await minimum(page).fill('72'); state.nextFailure = 500; await save(page).click();
  await expect(panel(page).getByRole('alert')).toContainText('could not be saved');
  await expect(minimum(page)).toHaveValue('72'); await expect(toggle(page)).toBeChecked(); await expect(save(page)).toBeEnabled();
  state.current = { revision: 'b'.repeat(64), enabled: false, minimum_usable_voice_percent: 60 };
  await save(page).click();
  await expect(panel(page)).toContainText('Call filtering changed elsewhere. Your unsaved changes were kept.');
  await expect(minimum(page)).toHaveValue('72'); await expect(toggle(page)).toBeChecked();
  await save(page).click(); await expect(save(page)).toBeHidden();
  expect(state.writes.at(-1).headers['if-match']).toBe(`"${'b'.repeat(64)}"`);
  await minimum(page).fill('80'); state.nextFailure = 'network'; await save(page).click();
  await expect(panel(page).getByRole('alert')).toContainText('Reload settings');
  await expect(minimum(page)).toHaveValue('80'); await expect(save(page)).toBeDisabled();
  await reload(page).click(); await expect(save(page)).toBeEnabled(); await expect(minimum(page)).toHaveValue('80');
  await save(page).click(); await expect(save(page)).toBeHidden();
  await screenshot(page, 'call-filtering-on-light-desktop');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('phone controls validate whole percentages and preserve the threshold while off or reloading', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const { callFiltering: state } = await openPagingToneApp(page, { theme: 'dark', callFilteringSnapshot: {
    enabled: true, minimum_usable_voice_percent: 80 } });
  await expect(minimum(page)).toHaveValue('80');
  for (const invalid of ['0', '101', '65.5']) {
    await minimum(page).fill(invalid); await expect(save(page)).toBeDisabled(); expect(state.writes).toHaveLength(0);
  }
  await minimum(page).fill('100'); await save(page).click(); await expect(save(page)).toBeHidden();
  await toggle(page).press('Space'); await expect(minimum(page)).toBeHidden(); await save(page).click();
  await expect(save(page)).toBeHidden();
  await toggle(page).press('Space'); await expect(minimum(page)).toHaveValue('100');
  await minimum(page).fill('70'); state.loadFailure = true; await reload(page).click();
  await expect(panel(page).getByRole('alert')).toBeVisible(); await expect(minimum(page)).toHaveValue('70');
  await expect(save(page)).toBeDisabled(); state.loadFailure = false; await reload(page).click();
  await expect(save(page)).toBeEnabled(); await expect(minimum(page)).toHaveValue('70');
  await expect(panel(page)).toContainText('Calls below this percentage are skipped for listening, recordings, streaming and call alerts.');
  await minimum(page).focus(); await screenshot(page, 'call-filtering-on-dark-phone');
  const bounds = await minimum(page).evaluate(input => {
    const control = input.getBoundingClientRect(), field = input.closest('.ui-field').getBoundingClientRect();
    return { left: control.left, right: control.right, fieldLeft: field.left, fieldRight: field.right };
  });
  expect(bounds.left).toBeGreaterThanOrEqual(bounds.fieldLeft); expect(bounds.right).toBeLessThanOrEqual(bounds.fieldRight);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
