'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { openVoiceEnhancementApp } = require('./fixtures/voice-enhancement-app.cjs');

const panel = page => page.locator('[data-paging-tone-detection]');
const toggle = page => panel(page).getByRole('checkbox', { name: 'Paging-tone detection', exact: true });
const save = page => panel(page).getByRole('button', { name: 'Save', exact: true });
const reload = page => panel(page).getByRole('button', { name: 'Reload status', exact: true });
async function screenshot(page, name) {
  if (process.env.WEB_REVIEW_DIR) await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR, `${name}.png`), fullPage: true, animations: 'disabled' });
}

test('default On paging-tone control saves independently, guards revisions, and preserves drafts after failure', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const app = await openVoiceEnhancementApp(page, { holdPagingStatus: true,
    snapshot: { status: 'no_model', model: null } });
  const state = app.pagingTone;
  await expect(page.locator('.admin-settings-content [data-paging-tone-detection]')).toHaveCount(1);
  await expect(panel(page)).toContainText('Loading paging-tone setting…');
  await expect(toggle(page)).toBeDisabled(); await expect(save(page)).toBeDisabled();
  state.releaseStatus();
  await expect(toggle(page)).toBeEnabled(); await expect(toggle(page)).toBeChecked();
  await expect(save(page)).toBeDisabled();
  await expect(page.locator('[data-voice-enhancement] input[type=checkbox]')).toBeDisabled();
  await expect(panel(page)).toContainText('Off skips detection; tones remain audible.');
  await toggle(page).focus(); await screenshot(page, 'paging-tones-light-desktop');
  await toggle(page).press('Space');
  const release = state.holdWrite();
  await save(page).click();
  await expect(panel(page).locator('form')).toHaveAttribute('aria-busy', 'true');
  await expect(toggle(page)).toBeDisabled(); await expect(reload(page)).toBeDisabled();
  await panel(page).locator('form').evaluate(form => form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
  await expect.poll(() => state.writes.length).toBe(1);
  release();
  await expect(toggle(page)).toBeEnabled(); await expect(toggle(page)).not.toBeChecked();
  await expect(save(page)).toBeDisabled();
  expect(state.writes[0].headers['if-match']).toBe(`"${'1'.repeat(64)}"`);
  expect(state.writes[0].headers['x-csrf-token']).toBe('test-only-csrf');
  expect(state.writes[0].method).toBe('PUT');
  expect(JSON.parse(state.writes[0].body)).toEqual({ value: false });
  expect(app.writes).toHaveLength(0);
  state.current = { ...state.current, revision: '2'.repeat(64) };
  await toggle(page).press('Space'); await save(page).click();
  await expect(panel(page)).toContainText('The latest saved setting was loaded.');
  await expect(toggle(page)).not.toBeChecked(); await expect(save(page)).toBeDisabled();
  state.nextFailure = 500;
  await toggle(page).press('Space'); await save(page).click();
  await expect(panel(page).getByRole('alert')).toContainText('could not be saved');
  await expect(toggle(page)).toBeChecked(); await expect(save(page)).toBeEnabled();
  await save(page).click(); await expect(panel(page)).toContainText('Paging-tone detection saved.');
  await expect(toggle(page)).toBeChecked();
  state.nextFailure = 'network';
  await toggle(page).press('Space'); await save(page).click();
  await expect(panel(page).getByRole('alert')).toContainText('Reload status');
  await expect(toggle(page)).toBeDisabled();
  await reload(page).click(); await expect(toggle(page)).toBeEnabled(); await expect(toggle(page)).toBeChecked();
  expect(app.writes).toHaveLength(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('unavailable safe Off setting remains editable and can be repaired without a changed value on a phone', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const app = await openVoiceEnhancementApp(page, { theme: 'dark', pagingSnapshot: { enabled: false, status: 'unavailable' } });
  const state = app.pagingTone;
  await expect(panel(page)).toContainText('Paging-tone setting could not be loaded. Detection is off. Save to choose a setting.');
  await expect(toggle(page)).toBeEnabled(); await expect(toggle(page)).not.toBeChecked();
  await expect(save(page)).toBeEnabled(); await screenshot(page, 'paging-tones-dark-phone');
  await save(page).click();
  await expect(panel(page)).toContainText('Paging-tone detection saved.');
  await expect(panel(page).locator('.ui-notice-warning')).toBeHidden();
  await expect(save(page)).toBeDisabled();
  expect(JSON.parse(state.writes[0].body)).toEqual({ value: false });
  state.current = { ...state.current, revision: '2'.repeat(64), status: 'unavailable' };
  await reload(page).click(); await expect(save(page)).toBeEnabled();
  await toggle(page).press('Space'); await save(page).click();
  await expect(toggle(page)).toBeChecked(); await expect(save(page)).toBeDisabled();
  expect(JSON.parse(state.writes.at(-1).body)).toEqual({ value: true });
  state.loadFailure = true; await reload(page).click();
  await expect(panel(page).getByRole('alert')).toBeVisible(); await expect(toggle(page)).toBeDisabled();
  state.loadFailure = false; await reload(page).click(); await expect(toggle(page)).toBeEnabled();
  const bounds = await panel(page).locator('.ui-toggle').evaluate(toggle => {
    const control = toggle.getBoundingClientRect(), field = toggle.closest('.ui-toggle-field').getBoundingClientRect();
    return { left: control.left, right: control.right, fieldLeft: field.left, fieldRight: field.right };
  });
  expect(bounds.left).toBeGreaterThanOrEqual(bounds.fieldLeft);
  expect(bounds.right).toBeLessThanOrEqual(bounds.fieldRight);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
