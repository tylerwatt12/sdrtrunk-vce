'use strict';

const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');

test.beforeEach(async ({ page }) => {
  await page.route(url => url.pathname === '/', route => route.fulfill({
    path: path.resolve(__dirname, '../../../../../stats-web/index.html')
  }));
});

async function openReset(page) {
  await page.getByRole('button', { name: 'Reset personal preferences', exact: true })
    .filter({ visible: true }).click();
  return page.getByRole('dialog', { name: 'Reset personal preferences', exact: true });
}

test('account reset locks dismissal and duplicate saves, retains failed choices and supports retry', async ({ page }) => {
  const preferenceStore = {};
  await openAudioApp(page, { theme: 'dark', volume: 0.35, admin: false, view: 'settings',
    feedCallsEnabled: false, preferenceStore });
  await expect(page.getByRole('form', { name: 'Appearance', exact: true })).toBeVisible();
  const pending = [];
  await page.route('**/api/v1/me/preferences', route => {
    if (route.request().method() === 'GET') return route.fallback();
    pending.push(route);
  });
  const modal = await openReset(page);
  const reset = modal.getByRole('button', { name: 'Reset personal preferences', exact: true });
  const cancel = modal.getByRole('button', { name: 'Cancel', exact: true });
  await reset.click();
  await expect.poll(() => pending.length).toBe(1);
  await expect(reset).toBeDisabled(); await expect(cancel).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(modal).toBeVisible();
  await reset.evaluate(button => button.dispatchEvent(new MouseEvent('click', { bubbles: true })));
  expect(pending).toHaveLength(1);
  await pending.shift().fulfill({ status: 503, json: { error: { message: 'Synthetic unavailable save' } } });
  await expect(modal).toContainText('Unable to save user preferences.');
  await expect(reset).toBeEnabled(); await expect(cancel).toBeEnabled();
  expect(preferenceStore.preferences.appearance.theme).toBe('dark');
  expect(preferenceStore.preferences.playback.volume).toBe(0.35);
  await reset.click();
  await expect.poll(() => pending.length).toBe(1);
  await pending.shift().fallback();
  await expect(modal).toBeHidden();
  expect(preferenceStore.preferences.version).toBe(15);
  expect(preferenceStore.preferences.appearance.theme).toBe('light');
  expect(preferenceStore.preferences.playback.volume).toBe(1);
});

for (const failedReload of [false, true]) {
  test(`reset conflict ${failedReload ? 'keeps recovery available when reload fails' : 'loads the current profile and closes'}`, async ({ page }) => {
    const preferenceStore = {};
    const state = await openAudioApp(page, { theme: 'dark', volume: 0.35, admin: false,
      view: 'settings', feedCallsEnabled: false, preferenceStore });
    await expect(page.getByRole('form', { name: 'Appearance', exact: true })).toBeVisible();
    await page.route('**/api/v1/me/preferences', route => {
      if (route.request().method() !== 'GET') return route.fulfill({ status: 409, json: {} });
      return failedReload ? route.fulfill({ status: 503, json: {} }) : route.fallback();
    });
    const modal = await openReset(page);
    await modal.getByRole('button', { name: 'Reset personal preferences', exact: true }).click();
    if (failedReload) {
      await expect(modal).toContainText('These preferences changed in another session, but the current values could not be reloaded.');
      await expect(modal.getByRole('button', { name: 'Cancel', exact: true })).toBeEnabled();
      await expect(modal.getByRole('button', { name: 'Reset personal preferences', exact: true })).toBeEnabled();
      await page.keyboard.press('Escape');
    }
    await expect(modal).toBeHidden();
    if (failedReload) {
      await expect(page.getByRole('button', { name: 'Reset personal preferences', exact: true })
        .filter({ visible: true })).toBeFocused();
    }
    expect(preferenceStore.preferences.appearance.theme).toBe('dark');
    expect(preferenceStore.preferences.playback.volume).toBe(0.35);
    expect(state.preferenceWrites).toHaveLength(0);
  });
}
