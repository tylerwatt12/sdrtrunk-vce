'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');
const { selectSettingsCategory } = require('./fixtures/my-settings-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');
const scriptErrors = new WeakMap();
test.beforeEach(async ({ page }) => {
  const errors = []; scriptErrors.set(page, errors);
  page.on('pageerror', error => errors.push(error.message));
  await page.route(url => url.pathname === '/', route => route.fulfill({
    path: path.resolve(__dirname, '../../../../../stats-web/index.html')
  }));
});
test.afterEach(async ({ page }) => { expect(scriptErrors.get(page)).toEqual([]); });

async function saveSection(page) {
  const form = page.getByRole('form');
  await form.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(form.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled();
}

for (const theme of ['light', 'dark']) {
  for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
    test(`guest settings remain local in ${theme} at ${viewport.width}px`, async ({ page }) => {
      await page.setViewportSize(viewport);
      const app = await openAudioApp(page, { authenticated: false, admin: false,
        feedCallsEnabled: false, view: 'settings' });
      await expect(page.getByRole('heading', { name: 'My Settings', exact: true })).toBeVisible();
      await expect(page.locator('.ui-settings-account-note')).toHaveText('Guest choices saved in this browser.');
      await expect(page.locator('[data-section="status"]')).toHaveCount(0);
      await page.getByRole('radio', { name: theme === 'dark' ? 'Dark' : 'Light', exact: true }).check();
      await page.getByRole('radio', { name: 'Indigo', exact: true }).check();
      await saveSection(page);
      await selectSettingsCategory(page, 'names', 'Source names');
      await page.getByRole('radio', { name: 'Both', exact: true }).check();
      await saveSection(page);
      await selectSettingsCategory(page, 'listening', 'Listening settings');
      await page.getByRole('spinbutton', { name: 'Playback volume (%)', exact: true }).fill('37');
      await page.getByRole('spinbutton', { name: 'Calls per target', exact: true }).fill('7');
      await page.getByRole('spinbutton', { name: 'Call display hangtime (seconds)', exact: true }).fill('9');
      await page.getByRole('combobox', { name: 'When a call is clicked', exact: true }).selectOption('once');
      await saveSection(page);
      await page.reload();
      await expect(page.getByRole('spinbutton', { name: 'Playback volume (%)', exact: true })).toHaveValue('37');
      await expect(page.getByRole('spinbutton', { name: 'Calls per target', exact: true })).toHaveValue('7');
      await expect(page.getByRole('spinbutton', { name: 'Call display hangtime (seconds)', exact: true })).toHaveValue('9');
      await expect(page.getByRole('combobox', { name: 'When a call is clicked', exact: true })).toHaveValue('once');
      if (theme === 'light' && viewport.width === 1280 || theme === 'dark' && viewport.width === 390) {
        await expectContrast(page, { reportName: `guest-listening-${theme}-${viewport.width}` });
      }
      if (process.env.CENTRAL_PREFERENCE_EVIDENCE) {
        await page.screenshot({ path: path.join(process.env.CENTRAL_PREFERENCE_EVIDENCE,
          `guest-listening-${theme}-${viewport.width}.png`), fullPage: true });
      }
      await selectSettingsCategory(page, 'names', 'Source names');
      await expect(page.getByRole('radio', { name: 'Both', exact: true })).toBeChecked();
      await selectSettingsCategory(page, 'guides', 'Guides');
      const automaticGuides = page.getByRole('checkbox', { name: 'Show page guides automatically', exact: true });
      await expect(automaticGuides).not.toBeChecked();
      await expect(automaticGuides).toBeEnabled();
      if (theme === 'light' && viewport.width === 1280) {
        await automaticGuides.focus(); await automaticGuides.press('Space');
        await saveSection(page); await expect(automaticGuides).toBeChecked();
        await automaticGuides.focus(); await automaticGuides.press('Space');
        await saveSection(page); await expect(automaticGuides).not.toBeChecked();
      }
      await selectSettingsCategory(page, 'pages', 'Page preferences');
      await expect(page.getByRole('link', { name: 'Open Live', exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Open Live presentation', exact: true })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Open Tuner spectrum', exact: true })).toHaveCount(0);
      expect(app.preferenceWrites).toHaveLength(0);
      expect(app.requests.filter(request => request.path === '/api/v1/me/preferences')).toHaveLength(0);
      expect(app.requests.filter(request => request.method !== 'GET' && request.path.startsWith('/api/v1/admin/'))).toHaveLength(0);
      expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
    });
  }
}

for (const theme of ['light', 'dark']) {
  for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
    test(`guests can change Live Presentation in ${theme} at ${viewport.width}px`, async ({ page }) => {
      await page.setViewportSize(viewport);
      const app = await openAudioApp(page, { authenticated: false, admin: false,
        feedCallsEnabled: false, view: 'settings' });
      if (theme === 'dark') await page.getByRole('button', { name: 'Use dark theme', exact: true }).click();
      await selectSettingsCategory(page, 'pages', 'Page preferences');
      await page.getByRole('button', { name: 'Open Live presentation', exact: true }).click();
      const modal = page.getByRole('dialog', { name: 'Live presentation', exact: true });
      await expect(modal).toBeVisible();
      await expect(page.getByRole('dialog', { name: 'Sign In', exact: true })).toHaveCount(0);
      await modal.getByRole('combobox', { name: 'Row density', exact: true }).selectOption('dense');
      await modal.getByRole('button', { name: 'Save Live Presentation', exact: true }).click();
      await expect(modal).toBeHidden();
      await page.reload();
      await page.getByRole('button', { name: 'Open Live presentation', exact: true }).click();
      await expect(modal.getByRole('combobox', { name: 'Row density', exact: true })).toHaveValue('dense');
      if (process.env.CENTRAL_PREFERENCE_EVIDENCE) {
        await modal.screenshot({ path: path.join(process.env.CENTRAL_PREFERENCE_EVIDENCE,
          `guest-live-presentation-${theme}-${viewport.width}.png`) });
      }
      await page.keyboard.press('Escape');
      await expect(page.getByRole('button', { name: 'Open Live presentation', exact: true })).toBeFocused();
      await page.getByRole('link', { name: 'Open Live', exact: true }).click();
      await page.getByRole('button', { name: 'Live presentation settings', exact: true }).click();
      await expect(modal).toBeVisible();
      await expect(modal.getByRole('combobox', { name: 'Row density', exact: true })).toHaveValue('dense');
      await expect(page.getByRole('dialog', { name: 'Sign In', exact: true })).toHaveCount(0);
      expect(app.preferenceWrites).toHaveLength(0);
      expect(app.requests.filter(request => request.method !== 'GET' && request.path.startsWith('/api/v1/admin/'))).toHaveLength(0);
    });
  }
}

test('guest reset restores browser defaults without changing an account', async ({ page }) => {
  const app = await openAudioApp(page, { authenticated: false, admin: false,
    feedCallsEnabled: false, view: 'settings' });
  await selectSettingsCategory(page, 'names', 'Source names');
  await page.getByRole('radio', { name: 'Both', exact: true }).check();
  await saveSection(page);
  await page.getByRole('button', { name: 'Reset guest preferences', exact: true }).filter({ visible: true }).click();
  const modal = page.getByRole('dialog', { name: 'Reset guest preferences', exact: true });
  await modal.getByRole('button', { name: 'Reset guest preferences', exact: true }).click();
  await expect(modal).toBeHidden();
  await selectSettingsCategory(page, 'names', 'Source names');
  await expect(page.getByRole('radio', { name: 'Talker Alias preferred', exact: true })).toBeChecked();
  await selectSettingsCategory(page, 'guides', 'Guides');
  await expect(page.getByRole('checkbox', { name: 'Show page guides automatically', exact: true })).not.toBeChecked();
  expect(app.preferenceWrites).toHaveLength(0);
});

test('guest settings keep a usable page profile and explain a failed browser save', async ({ page }) => {
  const app = await openAudioApp(page, { authenticated: false, admin: false,
    feedCallsEnabled: false, view: 'settings' });
  await page.evaluate(() => {
    Storage.prototype.setItem = () => { throw new DOMException('Sample storage quota', 'QuotaExceededError'); };
  });
  await selectSettingsCategory(page, 'names', 'Source names');
  await page.getByRole('radio', { name: 'Both', exact: true }).check();
  await saveSection(page);
  await expect(page.getByRole('radio', { name: 'Both', exact: true })).toBeChecked();
  await expect(page.locator('.ui-settings-account-note')).toHaveText('Guest choices apply for this page.');
  await expect(page.locator('#guest-preference-status')).toContainText('this browser could not save them');
  await expect(page.getByRole('form')).toContainText('Changes apply for this page. Browser storage is unavailable.');
  expect(app.preferenceWrites).toHaveLength(0);
});
