const { expect, test } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');

const application = readFileSync(resolve(__dirname, '../../../../..', 'stats-web/assets/app.js'), 'utf8');
const actions = application.slice(application.indexOf('function tunerFrequencyAction('),
  application.indexOf('function tunerStoredNumber('));

async function installActions(page) {
  await page.goto('/design-system.html?theme=light&view=gallery');
  await page.evaluate((source) => {
    window.frequencyTest = { audioStops: 0, streamCloses: 0 };
    const node = (tag, className = '', value = '') => {
      const element = document.createElement(tag);
      element.className = className;
      element.textContent = value;
      return element;
    };
    const iconGlyph = () => document.createElement('span');
    const diagnosticAudioPlayer = () => ({
      start: async () => {}, play: () => false,
      stop: () => { window.frequencyTest.audioStops += 1; }
    });
    const binaryFrameConnection = () => ({
      close: () => { window.frequencyTest.streamCloses += 1; }
    });
    const requestJson = async (path) => path.endsWith('/radioreference') ?
      { account: { state: 'VALID_PREMIUM' }, state_id: 39 } : { items: [], total_items: 0 };
    const radioReferenceResultView = () => node('div', '', 'No matches');
    const openReadOnlyModal = (title, body) => {
      const dialog = node('section', 'test-lookup-dialog');
      dialog.setAttribute('role', 'dialog');
      dialog.setAttribute('aria-label', title);
      dialog.append(body);
      document.body.append(dialog);
      return { dialog };
    };
    const harness = new Function('node', 'iconGlyph', 'diagnosticAudioPlayer',
      'binaryFrameConnection', 'requestJson', 'radioReferenceResultView', 'openReadOnlyModal',
      'capabilityAllowed', 'ACCESS_CAPABILITIES', 'DIAGNOSTIC_FRAME_TYPES', 'number',
      'anchor', 'href', 'activeRenderController', `${source}\nreturn { openTunerFrequencyActions };`);
    const controller = new AbortController();
    window.frequencyTest.abort = () => controller.abort();
    window.frequencyTest.open = harness(node, iconGlyph, diagnosticAudioPlayer,
      binaryFrameConnection, requestJson, radioReferenceResultView, openReadOnlyModal,
      () => true, { CALL_AUDIO: 'call_audio' }, { AUDIO_PCM16: 1 }, String,
      (label) => node('a', '', label), () => '#', { signal: controller.signal }).openTunerFrequencyActions;
  }, actions);
}

test('spectrum actions light-dismiss without leaving live audio running', async ({ page }) => {
  await installActions(page);
  await page.evaluate(() => window.frequencyTest.open({ frequencyHz: 770306250,
    targetId: 'tuner-1', anchorRect: { left: 450, right: 450, top: 320, bottom: 320 } }));
  const popover = page.locator('.tuner-frequency-popover[popover]');
  await expect(popover).toBeVisible();
  await expect(page.locator('.modal-backdrop:visible')).toHaveCount(0);
  await popover.getByRole('button', { name: 'Listen to NBFM' }).click();
  await expect(popover.getByRole('button', { name: 'Stop listening' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(popover).toHaveCount(0);
  expect(await page.evaluate(() => window.frequencyTest.streamCloses)).toBe(1);
  await page.evaluate(() => window.frequencyTest.open({ frequencyHz: 770306250,
    targetId: 'tuner-1', anchorRect: { left: 450, right: 450, top: 320, bottom: 320 } }));
  await expect(popover).toBeVisible();
  await page.mouse.click(20, 20);
  await expect(popover).toHaveCount(0);
  await page.evaluate(() => window.frequencyTest.open({ frequencyHz: 770306250,
    targetId: 'tuner-1', anchorRect: { left: 450, right: 450, top: 320, bottom: 320 } }));
  await expect(popover).toBeVisible();
  await page.evaluate(() => window.frequencyTest.abort());
  await expect(popover).toHaveCount(0);
});

test('RadioReference opens its own dialog and edge clicks remain on screen', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await installActions(page);
  await page.evaluate(() => window.frequencyTest.open({ frequencyHz: 770306250,
    targetId: 'tuner-1', anchorRect: { left: 380, right: 380, top: 820, bottom: 820 } }));
  const popover = page.locator('.tuner-frequency-popover[popover]');
  await expect(popover).toBeVisible();
  const bounds = await popover.boundingBox();
  expect(bounds.x).toBeGreaterThanOrEqual(0);
  expect(bounds.y).toBeGreaterThanOrEqual(0);
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(390);
  expect(bounds.y + bounds.height).toBeLessThanOrEqual(844);
  await popover.getByRole('button', { name: 'Listen to NBFM' }).click();
  await expect(popover.getByRole('button', { name: 'Stop listening' })).toBeVisible();
  await expect.poll(async () => {
    const expanded = await popover.boundingBox();
    return expanded.y + expanded.height;
  }).toBeLessThanOrEqual(844);
  await popover.getByRole('button', { name: 'RadioReference lookup' }).click();
  await expect(popover).toHaveCount(0);
  await expect(page.getByRole('dialog', { name: 'RadioReference lookup' })).toBeVisible();
});
