'use strict';
const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');
const { selectAudioPanel, openAudioVolume } = require('./audio-dock-controls.cjs');

test.beforeEach(async ({ page }) => {
  await page.route(url => url.pathname === '/', route => route.fulfill({
    path: path.resolve(__dirname, '../../../../../stats-web/index.html')
  }));
});

async function queueChoice(page) {
  const dock = page.locator('#audio-dock');
  await dock.getByRole('combobox', { name: 'Audio source', exact: true }).selectOption('recordings');
  await dock.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').press('End');
  await selectAudioPanel(dock, 'Queue');
  return dock.locator('#audio-dock-panel').getByRole('combobox', { name: 'When a call is clicked', exact: true });
}

test('guest title changes apply and both audio sources restore the shared browser volume', async ({ page }) => {
  const state = await openAudioApp(page, { authenticated: false, admin: false });
  const dock = page.locator('#audio-dock');
  await dock.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').press('End');
  await dock.getByRole('button', { name: 'Listen live', exact: true }).click();
  await expect.poll(() => page.title()).toContain('Fire Dispatch');
  await selectAudioPanel(dock, 'Listening settings');
  const title = dock.getByRole('checkbox', { name: 'Playing call in page title', exact: true });
  await title.locator('..').locator('.ui-toggle-track').click();
  await expect(title).not.toBeChecked();
  await expect.poll(() => page.title()).not.toContain('Fire Dispatch');
  await (await queueChoice(page)).selectOption('once');
  const volume = await openAudioVolume(page);
  await volume.evaluate(input => {
    input.value = '0.35'; input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await page.keyboard.press('Escape');
  await expect(dock.locator('[data-volume-value]:visible').first()).toHaveText('35%');
  await page.reload();
  await expect(await queueChoice(page)).toHaveValue('once');
  await expect(dock.locator('[data-volume-value]:visible').first()).toHaveText('35%');
  await dock.getByRole('combobox', { name: 'Audio source', exact: true }).selectOption('live');
  await expect(dock.locator('[data-volume-value]:visible').first()).toHaveText('35%');
  await selectAudioPanel(dock, 'Listening settings');
  await expect(dock.getByRole('checkbox', { name: 'Playing call in page title', exact: true })).not.toBeChecked();
  expect(state.requests.filter(request => request.path === '/api/v1/me/preferences')).toHaveLength(0);
});

test('signing in loads account choices and signing out restores the independent guest profile', async ({ page }) => {
  const store = {};
  const state = await openAudioApp(page, { authenticated: false, admin: false,
    preferenceStore: store, feedCallsEnabled: false });
  await (await queueChoice(page)).selectOption('once');
  await expect(await queueChoice(page)).toHaveValue('once');
  await page.route('**/api/v1/auth/login', route => {
    state.session = { ...state.session, authenticated: true, username: 'sample-listener', tier: 'user' };
    return route.fulfill({ json: { data: state.session } });
  });
  await page.route('**/api/v1/auth/logout', route => {
    state.session = { ...state.session, authenticated: false, username: null, tier: 'public' };
    return route.fulfill({ json: { data: state.session } });
  });
  await page.locator('#auth-action').click();
  const login = page.getByRole('dialog', { name: 'Sign In', exact: true });
  await login.getByRole('textbox', { name: 'Username', exact: true }).fill('sample-listener');
  await login.locator('input[type="password"]').fill('fixture-password');
  await login.getByRole('button', { name: 'Sign In', exact: true }).click();
  await expect(page.locator('#auth-action')).toHaveText('Sign Out');
  await expect(await queueChoice(page)).toHaveValue('queue');
  await (await queueChoice(page)).selectOption('continue');
  await expect.poll(() => store.preferences.playback.recording_click_mode).toBe('continue');
  await page.locator('#auth-action').click();
  await expect(page.locator('#auth-action')).toHaveText('Sign In');
  await expect(await queueChoice(page)).toHaveValue('once');
  const writes = state.preferenceWrites.length;
  await (await queueChoice(page)).selectOption('queue');
  await expect(await queueChoice(page)).toHaveValue('queue');
  expect(state.preferenceWrites).toHaveLength(writes);
  expect(store.preferences.playback.recording_click_mode).toBe('continue');
});

test('unavailable browser storage keeps guest controls usable with clear persistence feedback', async ({ page }) => {
  await page.addInitScript(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function(key, value) {
      if (key === 'sdrtrunk-vce-guest-preferences-v1') throw new DOMException('Storage is full', 'QuotaExceededError');
      return original.call(this, key, value);
    };
  });
  const state = await openAudioApp(page, { authenticated: false, admin: false, feedCallsEnabled: false });
  await expect(page.locator('#guest-preference-status')).toContainText('could not save');
  await (await queueChoice(page)).selectOption('once');
  await expect(await queueChoice(page)).toHaveValue('once');
  await page.reload();
  await expect(await queueChoice(page)).toHaveValue('queue');
  await expect(page.locator('#guest-preference-status')).toBeVisible();
  expect(state.requests.filter(request => request.path === '/api/v1/me/preferences')).toHaveLength(0);
});

test('a failed Live volume save restores the confirmed level in both playback sources', async ({ page }) => {
  await openAudioApp(page, { volume: 0.35, admin: false, feedCallsEnabled: false });
  await page.route('**/api/v1/me/preferences', route => route.request().method() === 'GET' ? route.fallback() :
    route.fulfill({ status: 503, json: { error: { message: 'Save unavailable' } } }));
  const dock = page.locator('#audio-dock');
  await dock.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').press('End');
  const volume = await openAudioVolume(page);
  await volume.evaluate(input => {
    input.value = '0.4'; input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await expect(page.locator('#preference-status')).toContainText('previous values are still active');
  await expect(volume).toHaveValue('0.35');
  await page.keyboard.press('Escape');
  await expect(dock.locator('[data-volume-value]:visible').first()).toHaveText('35%');
  await dock.getByRole('combobox', { name: 'Audio source', exact: true }).selectOption('recordings');
  await expect(dock.locator('[data-volume-value]:visible').first()).toHaveText('35%');
});

test('an unreadable saved guest profile is preserved until the explicit guest reset confirms replacement', async ({ page }) => {
  await page.addInitScript(() => {
    if (!localStorage.getItem('sdrtrunk-vce-guest-preferences-v1')) {
      localStorage.setItem('sdrtrunk-vce-guest-preferences-v1', JSON.stringify({ version: 2, preferences: {} }));
    }
  });
  const state = await openAudioApp(page, { authenticated: false, admin: false, feedCallsEnabled: false, view: 'settings' });
  await expect(page.locator('#guest-preference-status')).toContainText('could not be read');
  await page.getByRole('button', { name: 'Reset guest preferences', exact: true }).filter({ visible: true }).click();
  const modal = page.getByRole('dialog', { name: 'Reset guest preferences', exact: true });
  await modal.getByRole('button', { name: 'Reset guest preferences', exact: true }).click();
  await expect(modal).toBeHidden();
  await expect(page.locator('#guest-preference-status')).toHaveCount(0);
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('sdrtrunk-vce-guest-preferences-v1')).version)).toBe(1);
  expect(state.requests.filter(request => request.path === '/api/v1/me/preferences')).toHaveLength(0);
});
