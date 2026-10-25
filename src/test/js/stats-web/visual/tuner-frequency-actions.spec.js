const { expect, test } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');

const application = readFileSync(resolve(__dirname, '../../../../..', 'stats-web/assets/app.js'), 'utf8');
const actions = application.slice(application.indexOf('function tunerFrequencyAction('),
  application.indexOf('function tunerStoredNumber('));

async function installActions(page) {
  await page.goto('/design-system.html?theme=light&view=gallery');
  await page.evaluate(async (source) => {
    const { createGalleryModalFoundation } = await import('/visual/modal-foundation-gallery.js');
    const { createDiscoveryRadioReferenceContext } = await import('/assets/features/discovery-radioreference.js');
    const { openRadioReferenceConnect } = await import('/assets/features/radioreference-connect.js');
    const shared = await createGalleryModalFoundation();
    window.frequencyTest = { audioStops: 0, streamCloses: 0, connectCount: 0, calls: [],
      allowed: true, account: { account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 },
      connected: { account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } };
    const node = (tag, className = '', value = '') => {
      const element = document.createElement(tag);
      element.className = className;
      element.textContent = value;
      return element;
    };
    const iconGlyph = () => document.createElement('span');
    const formField = (label, control) => {
      const field = node('label', 'ui-field');
      field.append(node('span', 'ui-field-label', label), control);
      return field;
    };
    const uiToggleField = (label, checked, accessibleLabel) => {
      const checkbox = node('input', 'ui-toggle-input');
      checkbox.type = 'checkbox'; checkbox.checked = checked;
      checkbox.setAttribute('aria-label', accessibleLabel);
      return formField(label, checkbox);
    };
    const uiSelect = (options, current = '') => {
      const select = node('select', 'ui-select');
      options.forEach(({ value, label }) => { const option = node('option', '', label); option.value = value; select.append(option); });
      select.value = String(current);
      return select;
    };
    const uiActionButton = (label, _icon, action, className) => {
      const button = node('button', className, label);
      button.type = 'button'; button.addEventListener('click', action);
      return button;
    };
    const diagnosticAudioPlayer = () => ({
      start: async () => {}, play: () => false,
      stop: () => { window.frequencyTest.audioStops += 1; }
    });
    const binaryFrameConnection = () => ({
      close: () => { window.frequencyTest.streamCloses += 1; }
    });
    const requestJson = async (path, options = {}) => {
      const state = window.frequencyTest;
      state.calls.push({ path, method: options.method });
      if (path.endsWith('/radioreference')) return structuredClone(state.account);
      if (path.endsWith('/radioreference/session')) {
        state.account = structuredClone(state.connected);
        return structuredClone(state.connected);
      }
      if (path.includes('/countries')) return { items: [{ id: 1, name: 'United States' }] };
      if (path.includes('/states?')) {
        if (state.holdStates) await new Promise((resolve, reject) => {
          state.finishStates = resolve;
          options.signal?.addEventListener('abort', () => reject(new DOMException('Lookup closed', 'AbortError')), { once: true });
        });
        return { items: [{ id: 39, name: 'Ohio' }, { id: 40, name: 'Oklahoma' }] };
      }
      if (path.includes('/frequencies?') && state.frequencyError) {
        throw Object.assign(new Error('RadioReference could not be searched. Try again.'), { code: state.frequencyError });
      }
      return { items: [], total_items: 0 };
    };
    const radioReferenceResultView = () => node('div', '', 'No matches');
    const connectRadioReference = (options) => {
      window.frequencyTest.connectCount += 1;
      return openRadioReferenceConnect({ node, formField, uiToggleField, ...shared, requestJson }, options);
    };
    const harness = new Function('node', 'iconGlyph', 'diagnosticAudioPlayer',
      'binaryFrameConnection', 'requestJson', 'radioReferenceResultView', 'openReadOnlyModal',
      'capabilityAllowed', 'ACCESS_CAPABILITIES', 'DIAGNOSTIC_FRAME_TYPES', 'number',
      'anchor', 'href', 'activeRenderController', 'createDiscoveryRadioReferenceContext', 'connectRadioReference',
      'uiSelect', 'uiSelectFrame', 'formField', 'uiActionButton',
      `${source}\nreturn { openTunerFrequencyActions };`);
    const controller = new AbortController();
    window.frequencyTest.abort = () => controller.abort();
    window.frequencyTest.open = harness(node, iconGlyph, diagnosticAudioPlayer,
      binaryFrameConnection, requestJson, radioReferenceResultView, shared.openReadOnlyModal,
      capability => capability !== 'admin-settings' || window.frequencyTest.allowed,
      { CALL_AUDIO: 'call_audio', ADMIN_SETTINGS: 'admin-settings' }, { AUDIO_PCM16: 1 }, String,
      (label) => node('a', '', label), () => '#', { signal: controller.signal },
      createDiscoveryRadioReferenceContext, connectRadioReference, uiSelect, control => control, formField, uiActionButton)
      .openTunerFrequencyActions;
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

test('inline frequency actions stop listening before opening another workflow', async ({ page }) => {
  await installActions(page);
  await page.evaluate(() => {
    const host = document.createElement('aside');
    host.className = 'spectrum-browse-control-rail';
    document.body.append(host);
    window.frequencyTest.open({ frequencyHz: 770306250, targetId: 'tuner-1', actionHost: host });
  });
  const rail = page.locator('.spectrum-browse-control-rail');
  await rail.getByRole('button', { name: 'Listen in NBFM' }).click();
  await expect(rail.getByRole('button', { name: 'Stop listening' })).toBeVisible();
  await rail.getByRole('button', { name: 'Look up on RadioReference' }).click();
  await expect(page.getByRole('dialog', { name: 'RadioReference lookup' })).toBeVisible();
  expect(await page.evaluate(() => window.frequencyTest.streamCloses)).toBe(1);
});


async function openLookup(page) {
  await page.evaluate(() => window.frequencyTest.open({ frequencyHz: 770306250,
    targetId: 'tuner-1', anchorRect: { left: 450, right: 450, top: 320, bottom: 320 } }));
  await page.getByRole('button', { name: 'RadioReference lookup', exact: true }).click();
  return page.getByRole('dialog', { name: 'RadioReference lookup', exact: true });
}

test('frequency lookups share public regions while keeping account checks fresh after closing the first dialog', async ({ page }) => {
  await installActions(page);
  await page.evaluate(() => { window.frequencyTest.holdStates = true; });
  const first = await openLookup(page);
  await expect.poll(() => page.evaluate(() => typeof window.frequencyTest.finishStates)).toBe('function');
  await first.locator('.modal-close').click();
  await expect(first).toHaveCount(0);
  const second = await openLookup(page);
  await expect.poll(() => page.evaluate(() => window.frequencyTest.calls.filter(call =>
    call.path === '/api/v1/admin/radioreference').length)).toBe(2);
  expect(await page.evaluate(() => window.frequencyTest.calls.filter(call => call.path.includes('/states?')).length)).toBe(1);
  await page.evaluate(() => { window.frequencyTest.holdStates = false; window.frequencyTest.finishStates(); });
  await expect(second.getByText('0 RadioReference matches found.', { exact: true })).toBeVisible();
  await second.locator('.modal-close').click();
  await expect(second).toHaveCount(0);
  const third = await openLookup(page);
  await expect(third.getByText('0 RadioReference matches found.', { exact: true })).toBeVisible();
  const calls = await page.evaluate(() => window.frequencyTest.calls);
  expect(calls.filter(call => call.path === '/api/v1/admin/radioreference')).toHaveLength(3);
  expect(calls.filter(call => call.path.endsWith('/countries'))).toHaveLength(1);
  expect(calls.filter(call => call.path.includes('/states?'))).toHaveLength(1);
});

test('signed-out frequency lookup signs in and searches the original selection without navigation', async ({ page }) => {
  await installActions(page);
  await page.evaluate(() => { window.frequencyTest.account = { account: { state: 'NOT_CONFIGURED' } }; });
  const originalUrl = page.url();
  const lookup = await openLookup(page);
  const connection = page.getByRole('dialog', { name: 'Connect RadioReference', exact: true });
  await expect(connection).toBeVisible();
  expect(await page.evaluate(() => window.frequencyTest.calls.filter(call => call.path.includes('/frequencies?')).length)).toBe(0);
  await connection.getByRole('textbox', { name: 'Username', exact: true }).fill('fixture-user');
  await connection.getByLabel('Password', { exact: true }).fill('synthetic-password');
  await connection.getByRole('button', { name: 'Connect account', exact: true }).click();
  await expect(connection).toHaveCount(0);
  await expect(lookup.getByText('0 RadioReference matches found.', { exact: true })).toBeVisible();
  await expect(lookup.getByText('770.306250 MHz', { exact: true })).toBeVisible();
  expect(page.url()).toBe(originalUrl);
  expect(await page.evaluate(() => window.frequencyTest.calls.filter(call => call.path.includes('/frequencies?')).map(call => call.path)))
    .toEqual(['/api/v1/admin/radioreference/frequencies?state_id=39&frequency_hz=770306250&limit=100']);
});

test('frequency lookup preserves cancellation and lets a new account choose its region in place', async ({ page }) => {
  await installActions(page);
  await page.evaluate(() => {
    window.frequencyTest.account = { account: { state: 'NOT_CONFIGURED' } };
    window.frequencyTest.connected = { account: { state: 'VALID_PREMIUM' } };
  });
  const lookup = await openLookup(page);
  const connection = page.getByRole('dialog', { name: 'Connect RadioReference', exact: true });
  await connection.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(lookup.getByText('Connect an account to look up this frequency.', { exact: true })).toBeVisible();
  await expect(lookup.getByRole('link')).toHaveCount(0);
  await lookup.getByRole('button', { name: 'Connect account', exact: true }).click();
  await connection.getByRole('textbox', { name: 'Username', exact: true }).fill('fixture-user');
  await connection.getByLabel('Password', { exact: true }).fill('synthetic-password');
  await connection.getByRole('button', { name: 'Connect account', exact: true }).click();
  await expect(lookup.getByRole('button', { name: 'Search', exact: true })).toBeDisabled();
  await lookup.getByLabel('RadioReference country', { exact: true }).selectOption('1');
  await lookup.getByLabel('RadioReference state or province', { exact: true }).selectOption('40');
  await lookup.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(lookup.getByText('0 RadioReference matches found.', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.frequencyTest.calls.filter(call => call.path.includes('/frequencies?')).map(call => call.path)))
    .toEqual(['/api/v1/admin/radioreference/frequencies?state_id=40&frequency_hz=770306250&limit=100']);
});

test('frequency lookup recovers a failed search and clears stale matches when its region changes', async ({ page }) => {
  await installActions(page);
  await page.evaluate(() => { window.frequencyTest.frequencyError = 'unavailable'; });
  const lookup = await openLookup(page);
  await expect(lookup.getByText('RadioReference could not be searched. Try again.', { exact: true })).toBeVisible();
  await page.evaluate(() => { window.frequencyTest.frequencyError = null; });
  await lookup.getByRole('button', { name: 'Try again', exact: true }).click();
  await expect(lookup.getByText('No matches', { exact: true })).toBeVisible();
  await lookup.getByText('Lookup region', { exact: true }).click();
  await lookup.getByLabel('RadioReference state or province', { exact: true }).selectOption('40');
  await expect(lookup.getByText('No matches', { exact: true })).toHaveCount(0);
  await lookup.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(lookup.getByText('0 RadioReference matches found.', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.frequencyTest.calls.filter(call => call.path.includes('/frequencies?')).length)).toBe(3);
});

test('frequency lookup respects account access without requesting RadioReference data', async ({ page }) => {
  await installActions(page);
  await page.evaluate(() => { window.frequencyTest.allowed = false; });
  const lookup = await openLookup(page);
  await expect(lookup.getByText('RadioReference settings access is required to look up this frequency.', { exact: true })).toBeVisible();
  await expect(lookup.getByRole('button', { name: 'Connect account', exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => window.frequencyTest.calls.filter(call => call.path.includes('/radioreference')).length)).toBe(0);
});
