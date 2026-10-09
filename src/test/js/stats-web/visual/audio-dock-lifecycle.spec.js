'use strict';

const { expect, test } = require('@playwright/test');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');
const { openAudioVolume } = require('./audio-dock-controls.cjs');

test('visible Live spectrum and relative age stop updating when presentation is hidden or destroyed', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.clock.setFixedTime(new Date('2026-09-30T14:24:18Z'));
  await page.route('**/dock-lifecycle-test', route => route.fulfill({ contentType: 'text/html',
    body: '<link rel="stylesheet" href="/assets/app.css"><main class="app-shell"><div class="header-controls"><span id="receiver-health-indicator"></span></div></main>' }));
  await page.goto('/dock-lifecycle-test');
  // Mount the production presentation with observable engine adapters. Hiding,
  // access refresh, and destroy must release its loops without stopping audio.
  await page.evaluate(async () => {
    const { createAudioDock } = await import('/assets/core/audio-dock.js');
    const node = (tag, classes = '', text) => {
      const element = document.createElement(tag); element.className = classes;
      if (text !== undefined) element.textContent = text; return element;
    };
    const iconButton = (icon, label, classes) => {
      const button = node('button', classes); button.type = 'button'; button.setAttribute('aria-label', label);
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      const use = document.createElementNS(svg.namespaceURI, 'use'); use.setAttribute('href', `#${icon}`);
      svg.append(use); button.append(svg); return button;
    };
    const call = { call_id: 'lifecycle-call', target_alias: 'Fire Dispatch', source_alias: 'Engine 4',
      started_at_ms: Date.now() - 18_000, duration_ms: 42_000 };
    const events = { reads: 0, stops: 0, liveSubscriptions: 0, recordingSubscriptions: 0 };
    const permissions = { live: true, recordings: true };
    const liveState = { current: call, displayCall: call, volume: 0.65, stopped: false, paused: false,
      queuedCount: 0, queue: [], scanLists: [], currentReady: true, duration: 42, currentTime: 3 };
    const recordingState = { current: null, stopped: true, volume: 1, queue: [], clickMode: 'once' };
    const live = { viewState: () => liveState,
      subscribeState: listener => { events.liveSubscriptions++; listener(liveState);
        return () => { events.liveSubscriptions--; }; },
      readAudioSpectrum: values => { events.reads++; values.fill(0.6); return true; },
      togglePlayback: () => { events.stops++; }, setVolume: value => { liveState.volume = value; },
      writePreferences() {}, applyPreferences() {} };
    const recordings = { viewState: () => recordingState,
      subscribeState: listener => { events.recordingSubscriptions++; listener(recordingState);
        return () => { events.recordingSubscriptions--; }; },
      stop: () => { events.stops++; }, setVolume: value => { recordingState.volume = value; },
      renderDetails() {}, renderTranscript() {}, queueHref: () => '' };
    const uiToggleField = (text, checked) => {
      const label = node('label', '', text), input = node('input'); input.type = 'checkbox';
      input.checked = checked; label.append(input); return label;
    };
    const component = createAudioDock({ node, iconButton, uiToggleField, recordings, getLivePlayer: () => live,
      access: () => permissions, entityRefHref: () => null, canViewRadio: () => false, href: () => '/',
      getTitlePreference: () => false, setTitlePreference: async () => {} });
    window.dockLifecycle = { component, events, permissions };
  });
  const dock = page.locator('#audio-dock');
  await dock.locator('#audio-dock-size-handle').press('End');
  await expect(dock.locator('#audio-dock-call-age')).toHaveText('18s ago');
  await expect.poll(() => page.evaluate(() => window.dockLifecycle.events.reads)).toBeGreaterThan(2);
  await expect(dock.getByRole('img', { name: 'Voice frequency spectrum' })).toBeVisible();
  await expect.poll(() => dock.locator('.audio-dock-wave > i').first().evaluate(element => parseFloat(element.style.height))).toBeGreaterThan(3);
  await dock.locator('#audio-dock-settings').click();
  const burst = dock.getByRole('spinbutton', { name: 'Calls per target' });
  await burst.focus();
  await burst.evaluate(element => { element.dataset.focusWitness = 'same-control'; });
  await page.clock.setFixedTime(new Date('2026-09-30T14:25:18Z'));
  await expect(dock.locator('#audio-dock-call-age')).toHaveText('1m ago');
  await expect(burst).toBeFocused();
  await expect(burst).toHaveAttribute('data-focus-witness', 'same-control');
  await openAudioVolume(page);
  await dock.locator('#audio-dock-hide').click();
  await expect(page.locator('#audio-dock-volume-popover')).toBeHidden();
  const hiddenReads = await page.evaluate(() => window.dockLifecycle.events.reads);
  await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 100)));
  expect(await page.evaluate(() => window.dockLifecycle.events.reads)).toBe(hiddenReads);
  expect(await page.evaluate(() => window.dockLifecycle.events.stops)).toBe(0);
  await page.getByRole('button', { name: 'Restore player', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.dockLifecycle.events.reads)).toBeGreaterThan(hiddenReads);
  await openAudioVolume(page);
  await page.evaluate(() => { document.body.dataset.view = 'scanner'; });
  await expect(dock).toBeHidden();
  await expect(page.locator('#audio-dock-volume-popover')).toBeHidden();
  await page.evaluate(() => { document.body.dataset.view = 'dashboard'; });
  await expect(dock).toBeVisible();
  await openAudioVolume(page);
  await page.evaluate(() => {
    window.dockLifecycle.permissions.live = false; window.dockLifecycle.permissions.recordings = false;
    window.dockLifecycle.component.synchronize();
  });
  await expect(dock).toBeHidden();
  await expect(page.locator('#audio-dock-volume-popover')).toBeHidden();
  await page.evaluate(() => {
    window.dockLifecycle.permissions.live = true; window.dockLifecycle.component.synchronize();
  });
  await openAudioVolume(page);
  await page.evaluate(() => window.dockLifecycle.component.destroy());
  await expect(page.locator('#audio-dock, #audio-dock-volume-popover, .ui-audio-header-restore')).toHaveCount(0);
  const destroyedReads = await page.evaluate(() => window.dockLifecycle.events.reads);
  await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 100)));
  expect(await page.evaluate(() => window.dockLifecycle.events)).toEqual({ reads: destroyedReads, stops: 0,
    liveSubscriptions: 0, recordingSubscriptions: 0 });
});

test.describe('native volume with touch', () => {
  test.use({ hasTouch: true });
  test('touch and keyboard change Live gain and close the popup without a separate mute command', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const app = await openAudioApp(page, { view: 'dashboard', feedCallsEnabled: false, volume: 0.65 });
    const volume = await openAudioVolume(page);
    const range = await volume.boundingBox();
    await page.touchscreen.tap(range.x + range.width / 4, range.y + range.height / 2);
    await expect.poll(async () => Number(await volume.inputValue())).toBeGreaterThanOrEqual(0.2);
    expect(Number(await volume.inputValue())).toBeLessThanOrEqual(0.3);
    await expect.poll(() => app.preferenceWrites.length).toBe(1);
    expect(app.preferenceWrites.at(-1).playback.volume).toBe(Number(await volume.inputValue()));
    await volume.press('Home');
    await expect(volume).toHaveValue('0');
    await expect(page.locator('#audio-dock-volume-popover output')).toHaveText('0%');
    await volume.press('End');
    await expect(volume).toHaveValue('1');
    await expect(page.locator('#audio-dock-volume-popover output')).toHaveText('100%');
    await page.keyboard.press('Escape');
    await expect(page.locator('#audio-dock-volume-popover')).toBeHidden();
    await expect(page.locator('#audio-dock [data-volume-trigger]:visible')).toBeFocused();
    await openAudioVolume(page);
    await page.locator('#audio-dock-size-handle').press('Home');
    await expect(page.locator('#audio-dock-volume-popover')).toBeHidden();
    await expect(page.locator('#audio-dock-slim-expand')).toBeFocused();
    await page.locator('#audio-dock-slim-expand').press('End');
    await openAudioVolume(page);
    await page.touchscreen.tap(8, 8);
    await expect(page.locator('#audio-dock-volume-popover')).toBeHidden();
  });
});
