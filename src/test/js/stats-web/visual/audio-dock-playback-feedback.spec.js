'use strict';

const { expect, test } = require('@playwright/test');
const { selectDockSource } = require('./fixtures/audio-dock-source.cjs');

const dock = (page) => page.locator('#audio-dock');
const badge = (page) => dock(page).locator('.audio-dock-listening-status > .ui-pill');
const feedback = (page) => page.locator('.audio-dock-playback-feedback');
const selectedScanList = { id: 1, name: 'Fire', enabled: true, selected: true };

async function mountDock(page, theme) {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.route('**/dock-feedback-test', route => route.fulfill({ contentType: 'text/html',
    body: `<html data-theme="${theme}"><link rel="stylesheet" href="/assets/app.css"><main class="app-shell"><div class="header-controls"><span id="receiver-health-indicator"></span></div></main></html>` }));
  await page.goto('/dock-feedback-test');
  // Observable adapters deliver normal engine notifications to the production
  // presentation without waiting for network polling or an audio decoder.
  return page.evaluate(async (scanList) => {
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
    const uiToggleField = (text, checked) => {
      const label = node('label', '', text), input = node('input'); input.type = 'checkbox';
      input.checked = checked; label.append(input); return label;
    };
    const call = { call_id: 'feedback-call', id: 17, target_alias: 'Fire Dispatch', talkgroup_alias: 'Fire Dispatch',
      source_alias: 'Engine 4', channel: 'North Ridge Channel', started_at_ms: Date.now(), duration_ms: 42_000 };
    const permissions = { live: true, recordings: true };
    const liveState = { current: call, displayCall: call, volume: 0.65, stopped: false, paused: false,
      queuedCount: 0, queue: [], scanLists: [scanList], scanListCatalogReady: true,
      scanListCatalogState: 'ready', currentReady: false, duration: 42, currentTime: 3,
      holdTarget: null, status: 'Waiting for the next matching call' };
    const recordingState = { current: call, stopped: false, paused: true, playing: false, loading: false,
      volume: 1, queue: [], clickMode: 'once', duration: 42, currentTime: 3, status: 'Ready to play' };
    const observers = { live: new Set(), recordings: new Set() };
    const states = { live: liveState, recordings: recordingState };
    const publish = (source, value) => {
      Object.assign(states[source], value);
      observers[source].forEach(listener => listener({ ...states[source] }));
    };
    const subscribe = (source, listener) => {
      observers[source].add(listener); listener({ ...states[source] });
      return () => observers[source].delete(listener);
    };
    const live = { viewState: () => ({ ...liveState }), subscribeState: listener => subscribe('live', listener),
      readAudioSpectrum: () => false, togglePlayback: () => publish('live', { stopped: !liveState.stopped }),
      setVolume: value => publish('live', { volume: value }), writePreferences() {}, applyPreferences() {} };
    const recordings = { viewState: () => ({ ...recordingState }),
      subscribeState: listener => subscribe('recordings', listener),
      stop: () => publish('recordings', { stopped: true, playing: false, status: 'Stopped' }),
      setVolume: value => publish('recordings', { volume: value }),
      renderDetails() {}, renderTranscript() {}, queueHref: () => '' };
    const component = createAudioDock({ node, iconButton, uiToggleField, recordings, getLivePlayer: () => live,
      access: () => permissions, entityRefHref: () => null, canViewRadio: () => false, href: () => '/',
      getTitlePreference: () => false, setTitlePreference: async () => {} });
    window.dockFeedback = { component, permissions, publish };
    return call;
  }, selectedScanList);
}

async function size(page, value) {
  const handle = () => dock(page).locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible');
  await handle().press(value === 'full' ? 'End' : 'Home');
  if (value === 'minimal') await handle().press('ArrowUp');
  await expect(dock(page)).toHaveAttribute('data-state', value);
}

async function publish(page, source, value) {
  await page.evaluate(({ source, value }) => window.dockFeedback.publish(source, value), { source, value });
}

const geometry = (page) => dock(page).evaluate(element => {
  const bounds = element.getBoundingClientRect();
  return { height: bounds.height, width: bounds.width, top: bounds.top,
    reservedHeight: document.documentElement.style.getPropertyValue('--audio-dock-height') };
});

for (const display of [{ name: 'desktop', width: 1280, height: 900, theme: 'light' },
  { name: 'phone', width: 390, height: 844, theme: 'dark' }]) {
  test(`ordinary playback states keep every ${display.name} player size steady`, async ({ page }) => {
    await page.setViewportSize({ width: display.width, height: display.height });
    const call = await mountDock(page, display.theme);
    for (const presentation of ['full', 'minimal', 'collapsed']) {
      await size(page, presentation);
      await publish(page, 'live', { stopped: false, paused: false, holdTarget: null,
        currentReady: false, status: 'Waiting for the next matching call' });
      await expect(badge(page)).toHaveText('Waiting');
      const baseline = await geometry(page);
      for (const [status, short, currentReady] of [
        ['Buffering', 'Buffering', false], ['Listening', 'Listening', true],
        ['Waiting for the next matching call', 'Waiting', false],
        ['Reconnecting', 'Reconnecting', false], ['Replaying last call', 'Replaying last call', true],
      ]) {
        await publish(page, 'live', { status, currentReady });
        await expect(badge(page)).toHaveText(short);
        await expect(feedback(page)).toBeHidden();
        await expect.poll(() => geometry(page)).toEqual(baseline);
      }
      await publish(page, 'live', { status: 'Buffering', currentReady: false,
        holdTarget: { label: 'Fire Dispatch' } });
      await expect(badge(page)).toHaveText('Holding');
      await expect(feedback(page)).toBeHidden();
      await publish(page, 'live', { scanLists: [{ ...selectedScanList, selected: false }],
        current: null, currentReady: false, queue: [], queuedCount: 0, status: 'Select a scan list' });
      await expect(badge(page)).toHaveText('Select a List');
      await expect(badge(page)).not.toHaveClass(/ui-pill-success/);
      await expect(feedback(page)).toBeHidden();
      await expect.poll(() => geometry(page)).toEqual(baseline);
      await publish(page, 'live', { scanLists: [selectedScanList], current: call, status: 'Buffering' });
      await expect(badge(page)).toHaveText('Holding');
      await publish(page, 'live', { status: 'Paused · collecting calls', paused: true });
      await expect(badge(page)).toHaveText('Paused');
      await expect(feedback(page)).toBeHidden();
      await expect.poll(() => geometry(page)).toEqual(baseline);
    }
    await publish(page, 'live', { stopped: false, paused: false, holdTarget: null, status: 'Listening' });
    await size(page, 'minimal');
    const grip = await dock(page).locator('#audio-dock-size-handle').boundingBox();
    const x = grip.x + grip.width / 2, y = grip.y + grip.height / 2;
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x, y - 100, { steps: 4 });
    const dragging = await geometry(page);
    for (const status of ['Buffering', 'Listening']) {
      await publish(page, 'live', { status });
      await expect(dock(page)).toHaveAttribute('data-dragging', 'true');
      await expect(feedback(page)).toBeHidden();
      await expect.poll(() => geometry(page)).toEqual(dragging);
    }
    await page.mouse.up();
    await expect(dock(page)).not.toHaveAttribute('data-dragging', 'true');
    await publish(page, 'live', { stopped: true, paused: false, holdTarget: null, status: 'Unavailable' });
    await expect(badge(page)).toHaveText('Unavailable');
    await expect(feedback(page)).toBeHidden();
    await publish(page, 'live', { stopped: true, status: 'Loading scan lists' });
    await expect(badge(page)).toHaveText('Loading Lists');
    await expect(feedback(page)).toBeHidden();
  });

  test(`recording errors remain visible without resizing the ${display.name} player`, async ({ page }) => {
    await page.setViewportSize({ width: display.width, height: display.height });
    await mountDock(page, display.theme);
    await selectDockSource(dock(page), 'recordings');
    const error = 'Playback could not start. Press Play to retry.';
    for (const presentation of ['full', 'minimal', 'collapsed']) {
      await size(page, presentation);
      await publish(page, 'recordings', { status: 'Ready to play' });
      await expect(feedback(page)).toBeHidden();
      const baseline = await geometry(page);
      await publish(page, 'recordings', { status: error });
      await expect(feedback(page)).toHaveText(error);
      await expect(feedback(page)).toHaveAttribute('role', 'alert');
      await expect(feedback(page)).toBeVisible();
      await expect.poll(() => geometry(page)).toEqual(baseline);
    }
    await publish(page, 'recordings', { stopped: true, paused: false, status: 'Loading shared recording queue…' });
    await expect(badge(page)).toHaveText('Loading queue');
    await expect(feedback(page)).toBeHidden();
    await page.evaluate(() => {
      window.dockFeedback.permissions.live = false;
      window.dockFeedback.permissions.recordings = false;
      window.dockFeedback.component.synchronize();
    });
    await expect(feedback(page)).toBeHidden();
    await page.evaluate(() => window.dockFeedback.component.destroy());
    await expect(feedback(page)).toHaveCount(0);
  });
}
