'use strict';

const { expect, test } = require('@playwright/test');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');

test('Page title save failures retain previous values and offer a working retry without an unhandled rejection', async ({ page }) => {
  const browserErrors = [];
  page.on('pageerror', (error) => browserErrors.push(error.message));
  await page.addInitScript(() => {
    window.unhandledPreferenceRejections = [];
    window.addEventListener('unhandledrejection', (event) => {
      window.unhandledPreferenceRejections.push(String(event.reason?.message || event.reason));
    });
  });
  await page.setViewportSize({ width: 390, height: 844 });
  const app = await openAudioApp(page, { feedCallsEnabled: false });
  const attempts = [];
  await page.route('**/api/v1/me/preferences', async (route) => {
    if (route.request().method() === 'GET') return route.fallback();
    attempts.push(route.request().postDataJSON());
    if (attempts.length === 1) return route.fulfill({ status: 503, json: {
      error: { message: 'Settings are temporarily unavailable.' }
    } });
    return route.fallback();
  });

  const dock = page.locator('#audio-dock');
  const handle = dock.getByRole('button', { name: 'Change audio player size', exact: true });
  await handle.press('End');
  await dock.getByRole('tab', { name: 'Settings', exact: true }).click();
  const setting = dock.getByRole('checkbox', { name: 'Playing call in page title', exact: true });
  const previous = await setting.isChecked();
  await setting.locator('..').locator('.ui-toggle-track').click();
  const notice = page.locator('#preference-status');
  await expect(notice).toHaveAttribute('role', 'alert');
  await expect(notice).toContainText('My Settings could not be saved. Your previous values are still active.');
  await expect(setting).toBeChecked({ checked: previous });
  await handle.press('Home');
  await expect(notice.getByRole('button', { name: 'Retry', exact: true })).toBeVisible();
  await expect(notice.getByRole('button', { name: 'Dismiss', exact: true })).toBeVisible();
  await notice.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(notice).toBeHidden();
  await expect.poll(() => attempts.length).toBe(2);
  expect(attempts.map((write) => (write.preferences || write).page_titles.prepend_playing_call))
    .toEqual([!previous, !previous]);
  expect(app.preferenceWrites).toHaveLength(1);
  await handle.press('End');
  await expect(setting).toBeChecked({ checked: !previous });
  // Allow the browser's rejection checkpoint to run after the failed save and
  // successful retry have both settled; the listener does not suppress errors.
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  expect(browserErrors).toEqual([]);
  expect(await page.evaluate(() => window.unhandledPreferenceRejections)).toEqual([]);
});
