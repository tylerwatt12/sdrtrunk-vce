'use strict';

const { expect, test } = require('@playwright/test');
const { selectAudioPanel } = require('./audio-dock-controls.cjs');

async function mountDock(page) {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.route('**/dock-guide-test', route => route.fulfill({ contentType: 'text/html',
    body: '<link rel="stylesheet" href="/assets/app.css"><main class="app-shell"><div class="header-controls"><span id="receiver-health-indicator"></span></div></main>' }));
  await page.goto('/dock-guide-test');
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
    const uiToggleField = (text, checked) => {
      const label = node('label', '', text), input = node('input'); input.type = 'checkbox';
      input.checked = checked; label.append(input); return label;
    };
    const sampleScanLists = [
      { id: 900001, name: 'Public Safety', description: 'Dispatch and response calls', enabled: true, selected: true },
      { id: 900002, name: 'Fire & EMS', description: 'Fire and medical calls', enabled: true, selected: false },
      { id: 900003, name: 'Unidentified Traffic', available_to_listeners: false, selected: false }
    ];
    const events = { commands: [], reads: 0, liveSubscriptions: 0, recordingSubscriptions: 0 };
    let liveListener, recordingListener;
    let liveState = { current: null, displayCall: null, volume: 0.65, stopped: true, paused: false,
      queuedCount: 0, queue: [], scanLists: sampleScanLists.slice(0, 2), currentReady: false,
      duration: 0, currentTime: 0, scanListCatalogReady: true, maximumSelectedScanLists: 16 };
    let recordingState = { current: { id: 42, talkgroup_alias: 'Original recording' }, stopped: false,
      playing: false, paused: true, volume: 0.5, queue: [], clickMode: 'once' };
    const command = name => (...args) => { events.commands.push([name, ...args]); };
    const live = { viewState: () => liveState,
      subscribeState: listener => { liveListener = listener; events.liveSubscriptions++; listener(liveState);
        return () => { events.liveSubscriptions--; }; },
      readAudioSpectrum: values => { events.reads++; values.fill(0); return false; },
      ...Object.fromEntries(['togglePlayback', 'togglePause', 'skip', 'setVolume', 'writePreferences',
        'applyPreferences', 'setScanListSelected', 'clearQueue', 'toggleHold', 'avoidCurrent',
        'removeAvoid', 'replayLastCall'].map(name => [name, command(`live.${name}`)])) };
    const recordings = { viewState: () => recordingState,
      subscribeState: listener => { recordingListener = listener; events.recordingSubscriptions++; listener(recordingState);
        return () => { events.recordingSubscriptions--; }; },
      renderDetails() {}, renderTranscript() {}, queueHref: () => '',
      ...Object.fromEntries(['stop', 'toggle', 'next', 'previous', 'seek', 'skipSeconds', 'setVolume',
        'clearQueue', 'setClickMode', 'removeQueued', 'playQueued', 'loadSharedQueue']
        .map(name => [name, command(`recordings.${name}`)])) };
    const component = createAudioDock({ node, iconButton, uiToggleField, recordings, getLivePlayer: () => live,
      access: () => ({ live: true, recordings: true }), entityRefHref: () => null, canViewRadio: () => false,
      href: () => '/', getTitlePreference: () => false, setTitlePreference: command('title'),
      openRecordings: command('openRecordings') });
    window.dockGuide = { component, events, sampleScanLists,
      emitLive(update) { liveState = { ...liveState, ...update }; liveListener(liveState); },
      emitRecording(update) { recordingState = { ...recordingState, ...update }; recordingListener(recordingState); },
      prepare(options = {}) { this.release = component.prepareGuide({ sampleScanLists, ...options }); },
      restore() { this.release(); } };
  });
}

test('recording examples use the actual player and restore incoming engine state without issuing commands', async ({ page }) => {
  await mountDock(page);
  await page.locator('.ui-audio-source-picker').selectOption('recordings');
  await page.evaluate(() => {
    window.dockGuide.events.commands.length = 0;
    const sample = { current: { id: 9702, target_alias: 'Regional Fire', source_alias: 'Engine 7' },
      stopped: false, playing: true, paused: false, currentTime: 2, duration: 12, volume: 0.75,
      queue: [{ id: 9703, target_alias: 'Regional Fire', source_alias: 'Engine 8', duration_ms: 9000 }],
      continuation: {}, clickMode: 'continue', canNext: true, canPrevious: false };
    window.sampleSubscribers = 0;
    const adapter = { viewState: () => sample, subscribeState(observer) {
      window.sampleSubscribers++; observer(sample);
      return () => { window.sampleSubscribers--; };
    } };
    window.dockGuide.prepare({ sampleScanLists: null, sampleRecordings: adapter, recordingsSize: 'full' });
    window.dockGuide.emitRecording({ current: { id: 44, talkgroup_alias: 'Incoming saved call' }, playing: true });
    window.dockGuide.emitLive({ current: { target_alias: 'Incoming live call' }, stopped: false });
  });
  const dock = page.locator('#audio-dock');
  await expect(dock).toHaveAttribute('data-source', 'recordings');
  await expect(dock).toHaveAttribute('data-state', 'full');
  await expect(dock.locator('h2.audio-dock-title')).toHaveText('Regional Fire');
  await expect(dock.locator('.audio-dock-queue-list')).toContainText('Engine 8');
  await expect(dock.locator('#audio-dock-click-mode')).toHaveValue('continue');
  await expect(dock.locator('.audio-dock-time').first()).toContainText('00:02');
  await page.evaluate(() => {
    document.querySelectorAll('#audio-dock button').forEach(button => button.click());
    const mode = document.querySelector('#audio-dock-click-mode'); mode.value = 'once';
    mode.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await expect(dock.locator('#audio-dock-click-mode')).toHaveValue('continue');
  expect(await page.evaluate(() => window.dockGuide.events.commands)).toEqual([]);
  await page.evaluate(() => { window.dockGuide.restore(); window.dockGuide.restore(); });
  await expect(dock).toHaveAttribute('data-source', 'recordings');
  await expect(dock).toHaveAttribute('data-state', 'minimal');
  await expect(dock.locator('.audio-dock-compact-copy')).toContainText('Incoming saved call');
  expect(await page.evaluate(() => window.sampleSubscribers)).toBe(0);
  expect(await page.evaluate(() => window.dockGuide.events.commands)).toEqual([]);
});

test('guide uses the same Lists panel and choices as ordinary Live listening', async ({ page }) => {
  await mountDock(page);
  await selectAudioPanel(page.locator('#audio-dock'), 'Lists');
  const panel = page.locator('#audio-dock-panel');
  const ordinary = await panel.innerHTML();
  await page.evaluate(() => window.dockGuide.prepare());
  expect(await panel.innerHTML()).toBe(ordinary);
  await expect(page.locator('[data-guide-target="scan-lists-player-lists"]')).toBeVisible();
  await expect(panel.getByRole('checkbox')).toHaveCount(2);
  await expect(panel).toContainText('Public Safety');
  await expect(panel).toContainText('Fire & EMS');
  await expect(panel).not.toContainText('Unidentified Traffic');
  const reads = await page.evaluate(() => window.dockGuide.events.reads);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  expect(await page.evaluate(() => window.dockGuide.events.reads)).toBe(reads);
  await page.evaluate(() => window.dockGuide.restore());
  expect(await panel.innerHTML()).toBe(ordinary);
  expect(await page.evaluate(() => window.dockGuide.events.commands)).toEqual([]);
});

test('guide shows the actual Player menu opening button before the sample menu and protects its controls', async ({ page }) => {
  await mountDock(page);
  const dock = page.locator('#audio-dock');
  const opener = dock.locator('[data-guide-target="player-menu"]').filter({ visible: true });
  const originalButton = await opener.evaluate(button => {
    window.originalListsOpener = button;
    return button.outerHTML;
  });
  await page.evaluate(() => window.dockGuide.prepare({ openSampleLists: false }));
  await expect(dock).toHaveAttribute('data-source', 'live');
  await expect(dock).toHaveAttribute('data-state', 'minimal');
  await expect(opener).toBeVisible();
  await expect(dock.locator('.audio-dock-context')).toBeHidden();
  await expect(dock.locator('[data-guide-target="scan-lists-player-lists"]')).toHaveCount(0);
  expect(await opener.evaluate(button => ({ same: button === window.originalListsOpener,
    html: button.outerHTML }))).toEqual({ same: true, html: originalButton });
  await page.evaluate(() => {
    document.querySelectorAll('#audio-dock button').forEach(button => button.click());
    const source = document.querySelector('.ui-audio-source-picker'); source.value = 'recordings';
    source.dispatchEvent(new Event('change', { bubbles: true }));
    const volume = document.querySelector('#audio-dock-volume'); volume.value = '0';
    volume.dispatchEvent(new Event('input', { bubbles: true }));
    document.querySelector('#audio-dock-size-handle').dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Home', bubbles: true, cancelable: true }));
  });
  await expect(dock).toHaveAttribute('data-state', 'minimal');
  await expect(dock).toHaveAttribute('data-source', 'live');
  await expect(dock.locator('.audio-dock-context')).toBeHidden();
  expect(await page.evaluate(() => window.dockGuide.events.commands)).toEqual([]);
  await page.evaluate(() => window.dockGuide.restore());
  expect(await opener.evaluate(button => button.outerHTML)).toBe(originalButton);
  expect(await page.evaluate(() => window.dockGuide.events.commands)).toEqual([]);
});

test('Next opens Player menu then Lists and Back restores each actual opening state before incoming player state', async ({ page }) => {
  await mountDock(page);
  const dock = page.locator('#audio-dock');
  await dock.locator('.ui-audio-source-picker').selectOption('recordings');
  await dock.locator('#audio-dock-size-handle').press('End');
  await dock.locator('#audio-dock-call-details').click();
  await page.evaluate(() => {
    window.dockGuide.events.commands.length = 0;
    window.dockGuide.prepare({ openSampleLists: false });
  });
  const opener = dock.locator('[data-guide-target="player-menu"]').filter({ visible: true });
  const openingButton = await opener.evaluate(button => button.outerHTML);
  for (let visit = 0; visit < 2; visit++) {
    await page.evaluate(() => {
      window.releasePlayerMenu = window.dockGuide.component.prepareGuide({
        sampleScanLists: window.dockGuide.sampleScanLists, guidePanel: 'menu' });
    });
    await expect(dock.locator('#audio-dock-context-title')).toHaveText('Player menu');
    await expect(dock.getByRole('button', { name: 'Lists', exact: true })).toBeVisible();
    await expect(dock.locator('#audio-dock-context-back')).toHaveAccessibleName('Back to player');
    await page.evaluate(() => {
      window.releaseListsPanel = window.dockGuide.component.prepareGuide({
        sampleScanLists: window.dockGuide.sampleScanLists, guidePanel: 'listening' });
      window.dockGuide.emitLive({ stopped: false, current: { target_alias: 'Incoming live call' },
        displayCall: { target_alias: 'Incoming live call' }, queuedCount: 3, queue: [] });
      window.dockGuide.emitRecording({ current: { id: 43, talkgroup_alias: 'Updated recording' }, playing: true });
    });
    await expect(dock).toHaveAttribute('data-state', 'full');
    await expect(dock.locator('#audio-dock-context-title')).toHaveText('Lists');
    await expect(dock.locator('#audio-dock-context-back')).toHaveAccessibleName('Back to menu');
    await expect(dock.locator('#audio-dock-panel').getByRole('checkbox')).toHaveCount(2);
    await expect(dock.locator('#audio-dock-panel')).toContainText('Public Safety');
    await expect(dock.locator('#audio-dock-panel')).not.toContainText('Incoming live call');
    await page.evaluate(() => { window.releaseListsPanel(); window.releaseListsPanel(); });
    await expect(dock.locator('#audio-dock-context-title')).toHaveText('Player menu');
    await expect(dock.getByRole('button', { name: 'Lists', exact: true })).toBeVisible();
    await page.evaluate(() => { window.releasePlayerMenu(); window.releasePlayerMenu(); });
    await expect(dock).toHaveAttribute('data-state', 'minimal');
    await expect(opener).toBeVisible();
    expect(await opener.evaluate(button => button.outerHTML)).toBe(openingButton);
    await expect(dock.locator('.audio-dock-context')).toBeHidden();
    await expect(dock.locator('[data-guide-target="scan-lists-player-lists"]')).toHaveCount(0);
    await expect(dock.locator('h2.audio-dock-title')).toHaveText('Nothing queued');
  }
  await page.evaluate(() => { window.dockGuide.restore(); window.dockGuide.restore(); });
  await expect(dock).toHaveAttribute('data-source', 'recordings');
  await expect(dock).toHaveAttribute('data-state', 'full');
  await expect(dock.locator('#audio-dock-context-title')).toHaveText('Call details');
  await expect(dock.locator('.audio-dock-context-heading')).toContainText('Updated recording');
  await expect(dock.locator('[data-guide-target="scan-lists-player-lists"]')).toHaveCount(0);
  expect(await page.evaluate(() => window.dockGuide.events.commands)).toEqual([]);
  expect(await page.evaluate(() => ({ live: window.dockGuide.events.liveSubscriptions,
    recordings: window.dockGuide.events.recordingSubscriptions }))).toEqual({ live: 1, recordings: 1 });
});

test('sample player rejects synthetic checkbox, keyboard, source, volume, and playback commands', async ({ page }) => {
  await mountDock(page);
  await page.evaluate(() => {
    window.dockGuide.prepare();
    const sample = document.querySelector('#audio-dock-scan-list-900002');
    sample.checked = true; sample.dispatchEvent(new Event('change', { bubbles: true }));
    document.querySelectorAll('#audio-dock button').forEach(button => button.click());
    document.querySelector('#audio-dock-size-handle').dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    const source = document.querySelector('.ui-audio-source-picker'); source.value = 'recordings';
    source.dispatchEvent(new Event('change', { bubbles: true }));
    const volume = document.querySelector('#audio-dock-volume'); volume.value = '0';
    volume.dispatchEvent(new Event('input', { bubbles: true }));
    volume.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await expect(page.locator('#audio-dock')).toHaveAttribute('data-state', 'full');
  await expect(page.locator('#audio-dock')).toHaveAttribute('data-source', 'live');
  await expect(page.locator('#audio-dock-scan-list-900002')).not.toBeChecked();
  await expect(page.locator('#audio-dock-panel')).toContainText('Only selected lists add calls to your Live queue.');
  expect(await page.evaluate(() => window.dockGuide.events.commands)).toEqual([]);
});

test('sample player lets Tab and Escape reach the guide without closing its Lists panel', async ({ page }) => {
  await mountDock(page);
  const keys = await page.evaluate(() => {
    window.dockGuide.prepare();
    const seen = [];
    const observe = event => seen.push({ key: event.key, prevented: event.defaultPrevented });
    document.addEventListener('keydown', observe);
    const checkbox = document.querySelector('#audio-dock-scan-list-900001');
    for (const key of ['Tab', 'Escape']) {
      checkbox.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    }
    document.removeEventListener('keydown', observe);
    return seen;
  });
  expect(keys).toEqual([{ key: 'Tab', prevented: false }, { key: 'Escape', prevented: false }]);
  await expect(page.locator('#audio-dock-context-title')).toHaveText('Lists');
  await expect(page.locator('#audio-dock-panel')).toContainText('Public Safety');
  expect(await page.evaluate(() => window.dockGuide.events.commands)).toEqual([]);
});

for (const initial of ['recordings', 'recordings-player', 'minimal', 'hidden']) {
  test(`guide restores ${initial} presentation and keeps engine updates received during the tour`, async ({ page }) => {
    await mountDock(page);
    const dock = page.locator('#audio-dock');
    if (initial.startsWith('recordings')) {
      await dock.locator('.ui-audio-source-picker').selectOption('recordings');
      await dock.locator('#audio-dock-size-handle').press('End');
      if (initial === 'recordings') await dock.locator('#audio-dock-call-details').click();
    } else if (initial === 'hidden') {
      await selectAudioPanel(dock, 'Listening settings');
      await dock.locator('#audio-dock-hide').click();
    }
    const original = await dock.evaluate(element => ({ source: element.dataset.source, state: element.dataset.state,
      hidden: element.hidden, collapsed: element.dataset.superCollapsed }));
    await page.evaluate(() => {
      window.dockGuide.events.commands.length = 0;
      window.dockGuide.prepare();
      window.dockGuide.emitLive({ stopped: false, current: { target_alias: 'Incoming live call' },
        displayCall: { target_alias: 'Incoming live call' }, queuedCount: 3, queue: [] });
      window.dockGuide.emitRecording({ current: { id: 43, talkgroup_alias: 'Updated recording' }, playing: true });
    });
    await expect(dock).toHaveAttribute('data-source', 'live');
    await expect(dock).toHaveAttribute('data-state', 'full');
    await expect(dock.locator('#audio-dock-panel')).not.toContainText('Incoming live call');
    await page.evaluate(() => { window.dockGuide.restore(); window.dockGuide.restore(); });
    await expect(dock.locator('[data-guide-target="scan-lists-player-lists"]')).toHaveCount(0);
    expect(await dock.evaluate(element => ({ source: element.dataset.source, state: element.dataset.state,
      hidden: element.hidden, collapsed: element.dataset.superCollapsed }))).toEqual(original);
    if (initial === 'recordings') {
      await expect(dock.locator('#audio-dock-context-title')).toHaveText('Call details');
      await expect(dock.locator('.audio-dock-context-heading')).toContainText('Updated recording');
    } else if (initial === 'recordings-player') {
      await expect(dock.locator('.audio-dock-context')).toBeHidden();
      await expect(dock.locator('h2.audio-dock-title')).toHaveText('Updated recording');
    } else {
      if (initial === 'hidden') await page.getByRole('button', { name: 'Restore player', exact: true }).click();
      await expect(dock.locator('h2.audio-dock-title')).toHaveText('Incoming live call');
    }
    expect(await page.evaluate(() => window.dockGuide.events.commands)).toEqual([]);
    expect(await page.evaluate(() => ({ live: window.dockGuide.events.liveSubscriptions,
      recordings: window.dockGuide.events.recordingSubscriptions }))).toEqual({ live: 1, recordings: 1 });
  });
}
