'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { openVoiceEnhancementApp, model } = require('./fixtures/voice-enhancement-app.cjs');
const { openAudioApp, liveCall } = require('./fixtures/audio-dock-app.cjs');
const { selectAudioPanel } = require('./audio-dock-controls.cjs');

const zip = { name: 'voice-model.zip', mimeType: 'application/zip', buffer: Buffer.from([80, 75, 3, 4]) };
const panel = page => page.locator('[data-voice-enhancement]');
const toggle = page => panel(page).getByRole('checkbox', { name: '16 kHz voice enhancement', exact: true });
const save = page => panel(page).getByRole('button', { name: 'Save', exact: true });
const update = page => panel(page).getByRole('button', { name: 'Update model', exact: true });

async function screenshot(page, name) {
  if (process.env.WEB_REVIEW_DIR) await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR, `${name}.png`), fullPage: true });
}

test('voice enhancement saves revisions, blocks duplicate writes, and retains a model after rejected replacement', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const state = await openVoiceEnhancementApp(page, { holdStatus: true,
    snapshot: { model: model('voice-model-' + 'long-'.repeat(25)) } });
  await expect(panel(page)).toContainText('Loading voice enhancement…');
  await expect(page.locator('.admin-settings-content [data-voice-enhancement]')).toHaveCount(1);
  await expect(toggle(page)).toBeDisabled(); await expect(save(page)).toBeDisabled();
  await expect(panel(page).locator('input[type=file]')).toBeDisabled();
  state.releaseStatus();
  await expect(toggle(page)).toBeEnabled();
  await expect(save(page)).toBeDisabled();
  await expect(panel(page)).toContainText('Applies to new browser calls.');
  await toggle(page).focus();
  await screenshot(page, 'administration-light-desktop');
  await toggle(page).press('Space');
  const release = state.holdWrite();
  await save(page).click();
  await expect(panel(page).locator('form').first()).toHaveAttribute('aria-busy', 'true');
  await expect(toggle(page)).toBeDisabled(); await expect(update(page)).toBeDisabled();
  await panel(page).locator('form').first().evaluate(form => form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
  await expect.poll(() => state.writes.length).toBe(1);
  release();
  await expect(toggle(page)).toBeEnabled(); await expect(toggle(page)).toBeChecked();
  expect(state.writes[0].headers['if-match']).toBe(`"${'a'.repeat(64)}"`);
  expect(state.writes[0].headers['x-csrf-token']).toBe('test-only-csrf');
  expect(JSON.parse(state.writes[0].body)).toEqual({ value: true });
  const file = panel(page).locator('input[type=file]');
  await file.setInputFiles({ ...zip, name: 'model.json' });
  await expect(update(page)).toBeDisabled();
  await expect(panel(page).getByRole('alert')).toContainText('Choose a ZIP file');
  await file.setInputFiles(zip);
  state.nextFailure = 422;
  await update(page).click();
  await expect(panel(page).getByRole('alert')).toContainText('The current model is unchanged.');
  await expect(panel(page)).toContainText(state.current.model.id);
  await update(page).click();
  await expect(panel(page)).toContainText('Model updated.');
  expect(state.writes.at(-1).headers['content-type']).toBe('application/zip');
  expect(state.writes.at(-1).body).toEqual(zip.buffer);
  expect(state.writes.at(-1).headers['if-match']).toBe(`"${'b'.repeat(64)}"`);
  state.current = { ...state.current, revision: 'c'.repeat(64), enabled: false };
  await toggle(page).press('Space'); await save(page).click();
  await expect(panel(page)).toContainText('The latest saved settings were loaded.');
  await expect(toggle(page)).not.toBeChecked(); await expect(save(page)).toBeDisabled();
  state.nextFailure = 'network';
  await toggle(page).press('Space'); await save(page).click();
  await expect(panel(page).getByRole('alert')).toContainText('Reload status');
  await expect(toggle(page)).toBeDisabled();
  await panel(page).getByRole('button', { name: 'Reload status' }).click();
  await expect(toggle(page)).toBeEnabled(); await expect(toggle(page)).not.toBeChecked();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('missing and unavailable model states explain original audio fallback on a phone', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await openVoiceEnhancementApp(page, { theme: 'dark', snapshot: { status: 'no_model', model: null } });
  await expect(panel(page)).toContainText('Install a model to turn this on. Browser calls use original audio.');
  await expect(toggle(page)).toBeDisabled(); await expect(save(page)).toBeDisabled();
  const fieldBounds = await panel(page).locator('input[type=file]').evaluate(input => {
    const field = input.closest('.ui-field').getBoundingClientRect(), control = input.getBoundingClientRect();
    return { fieldRight: field.right, controlRight: control.right, fieldLeft: field.left, controlLeft: control.left };
  });
  expect(fieldBounds.controlRight).toBeLessThanOrEqual(fieldBounds.fieldRight + 1);
  expect(fieldBounds.controlLeft).toBeGreaterThanOrEqual(fieldBounds.fieldLeft - 1);
  await screenshot(page, 'administration-dark-phone');
  await panel(page).locator('input[type=file]').setInputFiles(zip);
  await update(page).click(); await expect(toggle(page)).toBeEnabled();
  state.current = { ...state.current, enabled: true, status: 'unavailable' };
  await panel(page).getByRole('button', { name: 'Reload status' }).click();
  await expect(panel(page)).toContainText('The model is unavailable. Browser calls use original audio.');
  await expect(toggle(page)).toBeEnabled(); await expect(toggle(page)).toBeChecked();
  await toggle(page).press('Space'); await save(page).click();
  await expect(toggle(page)).not.toBeChecked(); await expect(toggle(page)).toBeDisabled();
  state.current = { ...state.current, model: null };
  await panel(page).getByRole('button', { name: 'Reload status' }).click();
  await expect(panel(page)).toContainText('Model unavailable');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('completed call Details shows audio enhancement and measured tones without codec metadata claims', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openAudioApp(page, { view: 'dashboard', theme: 'dark', feedCallsEnabled: false });
  let delivered = false;
  await page.route('**/api/v1/calls/feed*', async route => {
    const calls = delivered ? [] : [{ ...liveCall, protocol: 'P25 Phase 1', decoder: 'P25 Phase 1',
      audio_sample_rate: 16000, voice_enhancement: true, voice_model: 'voice-model-1',
      detected_paging_tones: [{ frequency_hz: 600, duration_milliseconds: 1000, offset_milliseconds: 80,
        confidence: 0.98, source: 'decoded_audio', method: 'p25_pcm_tone_v1' }] }];
    delivered = true; return route.fulfill({ json: { data: { cursor: '1', reset: false, calls } } });
  });
  const dock = page.locator('#audio-dock');
  await dock.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').press('End');
  await dock.getByRole('button', { name: 'Listen live', exact: true }).click();
  await selectAudioPanel(dock, 'Details');
  await dock.locator('summary').filter({ hasText: /^Audio$/ }).click();
  await dock.locator('summary').filter({ hasText: /^Detected tones$/ }).click();
  await expect(dock.locator('#audio-dock-panel')).toContainText('16 kHz');
  await expect(dock.locator('#audio-dock-panel')).toContainText('voice-model-1');
  await expect(dock.locator('#audio-dock-panel')).toContainText('Audio detection');
  await expect(dock.locator('#audio-dock-panel')).toContainText('600 Hz · 1000 ms · at 80 ms');
  await screenshot(page, 'playback-audio-details-dark-phone');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
