'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');
const { selectAudioPanel, openAudioVolume } = require('./audio-dock-controls.cjs');

async function clickMode(page) {
  const dock = page.locator('#audio-dock');
  await dock.getByRole('combobox', { name: 'Audio source', exact: true }).selectOption('recordings');
  await dock.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').press('End');
  await selectAudioPanel(dock, 'Queue');
  const control = dock.getByRole('combobox', { name: 'When a call is clicked', exact: true }).filter({ visible: true });
  await expect(control).toBeVisible();
  return control;
}

async function expectSelectedMode(page, mode) {
  const controls = page.locator('#audio-dock [data-click-mode]');
  await expect.poll(() => controls.evaluateAll(items => [...new Set(items.map(item => item.value))]))
    .toEqual([mode]);
}

test('recording click mode follows the account across reloads and fresh browsers', async ({ page, browser }) => {
  const store = {};
  const state = await openAudioApp(page, { preferenceStore: store, feedCallsEnabled: false });
  let control = await clickMode(page);
  await expect(control).toHaveValue('queue');
  for (const mode of ['once', 'continue']) {
    await control.selectOption(mode);
    await expect.poll(() => store.preferences.playback.recording_click_mode).toBe(mode);
    await expectSelectedMode(page, mode);
    await page.reload();
    control = await clickMode(page);
    await expect(control).toHaveValue(mode);
  }
  expect(state.preferenceWrites).toHaveLength(2);
  if (process.env.RECORDING_CLICK_MODE_EVIDENCE) {
    await page.locator('#audio-dock').screenshot({ path: path.join(process.env.RECORDING_CLICK_MODE_EVIDENCE,
      'saved-account-after-reload.png') });
  }

  const context = await browser.newContext({ baseURL: test.info().project.use.baseURL,
    viewport: { width: 1280, height: 900 } });
  try {
    const fresh = await context.newPage();
    await openAudioApp(fresh, { preferenceStore: store, feedCallsEnabled: false });
    await expect(await clickMode(fresh)).toHaveValue('continue');
    const other = await context.newPage();
    await openAudioApp(other, { username: 'other-listener', preferenceStore: {}, feedCallsEnabled: false });
    await expect(await clickMode(other)).toHaveValue('queue');
  } finally {
    await context.close();
  }
});

for (const theme of ['light', 'dark']) {
  for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
    test(`guest click mode persists locally in ${theme} at ${viewport.width}px`, async ({ page }) => {
      await page.setViewportSize(viewport);
      const state = await openAudioApp(page, { authenticated: false, feedCallsEnabled: false });
      if (theme === 'dark') {
        await page.getByRole('button', { name: 'Use dark theme', exact: true }).click();
        await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
      }
      const control = await clickMode(page);
      await control.selectOption('once');
      await expectSelectedMode(page, 'once');
      expect(state.preferenceWrites).toHaveLength(0);
      expect(state.requests.filter(request => request.path === '/api/v1/me/preferences')).toHaveLength(0);
      await page.reload();
      await expect(await clickMode(page)).toHaveValue('once');
      expect(state.preferenceWrites).toHaveLength(0);
    });
  }
}

test('recording click mode save failure restores the prior choice and supports retry', async ({ page }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(() => {
    window.unhandledClickModeRejections = [];
    window.addEventListener('unhandledrejection', event => {
      window.unhandledClickModeRejections.push(String(event.reason?.message || event.reason));
    });
  });
  const state = await openAudioApp(page, { feedCallsEnabled: false });
  let attempts = 0;
  await page.route('**/api/v1/me/preferences', async route => {
    if (route.request().method() === 'GET') return route.fallback();
    if (++attempts === 1) return route.fulfill({ status: 503, json: { error: { message: 'Temporarily unavailable.' } } });
    return route.fallback();
  });
  const control = await clickMode(page);
  await control.selectOption('once');
  const notice = page.locator('#preference-status');
  await expect(notice).toContainText('My Settings could not be saved. Your previous values are still active.');
  await expectSelectedMode(page, 'queue');
  await expect(control).toBeEnabled();
  await notice.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(notice).toBeHidden();
  await expectSelectedMode(page, 'once');
  expect(attempts).toBe(2);
  expect(state.preferenceWrites).toHaveLength(1);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  expect(errors).toEqual([]);
  expect(await page.evaluate(() => window.unhandledClickModeRejections)).toEqual([]);
});

test('a pending recording click mode save preserves a subsequent Live volume change', async ({ page }) => {
  const state = await openAudioApp(page, { feedCallsEnabled: false });
  let release;
  const pending = new Promise(resolve => { release = resolve; });
  const writes = [];
  await page.route('**/api/v1/me/preferences', async route => {
    if (route.request().method() === 'GET') return route.fallback();
    writes.push(route.request().postDataJSON());
    if (writes.length === 1) await pending;
    return route.fallback();
  });
  await (await clickMode(page)).selectOption('once');
  await expect.poll(() => writes.length).toBe(1);
  const dock = page.locator('#audio-dock');
  await expect(dock.locator('[data-click-mode]').first()).toBeDisabled();
  await expect(dock.locator('[data-click-mode]').last()).toBeDisabled();
  await dock.getByRole('combobox', { name: 'Audio source', exact: true }).selectOption('live');
  const volume = await openAudioVolume(page);
  await volume.evaluate(input => {
    input.value = '0.4';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  });
  release();
  await expect.poll(() => state.preferenceWrites.length).toBe(2);
  expect(state.preferenceWrites.at(-1).playback).toMatchObject({ recording_click_mode: 'once', volume: 0.4 });
  await expect(await clickMode(page)).toHaveValue('once');
});
