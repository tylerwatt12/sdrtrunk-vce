'use strict';

const { expect, test } = require('@playwright/test');
const { selectAudioPanel } = require('./audio-dock-controls.cjs');
const { selectDockSource } = require('./fixtures/audio-dock-source.cjs');
const { openAudioApp, liveCall } = require('./fixtures/audio-dock-app.cjs');

async function openHangtimeDock(page, { recordingsOnly = false } = {}) {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.clock.setFixedTime(new Date('2026-09-30T14:24:18Z'));
  await page.route('**/playback-hangtime-test', route => route.fulfill({ contentType: 'text/html',
    body: '<link rel="stylesheet" href="/assets/app.css"><main class="app-shell"><div class="header-controls"><span id="receiver-health-indicator"></span></div></main>' }));
  await page.goto('/playback-hangtime-test');
  await page.evaluate(async ({ recordingsOnly }) => {
    const { createAudioDock } = await import('/assets/core/audio-dock.js');
    const { createRecordingsFeature } = await import('/assets/features/recordings.js');
    const { createCallDisplayRetention } = await import('/assets/core/call-display-retention.js');
    const node = (tag, classes = '', text) => {
      const element = document.createElement(tag); element.className = classes;
      if (text !== undefined) element.append(text); return element;
    };
    const iconButton = (icon, label, classes) => {
      const button = node('button', classes); button.type = 'button'; button.setAttribute('aria-label', label);
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      const use = document.createElementNS(svg.namespaceURI, 'use'); use.setAttribute('href', `#${icon}`);
      svg.append(use); button.append(svg); return button;
    };
    const uiToggleField = (label, checked) => {
      const field = node('label', 'ui-toggle-field'), input = node('input');
      input.type = 'checkbox'; input.checked = checked; input.setAttribute('aria-label', label);
      field.append(node('span', 'ui-toggle-copy', label), input); return field;
    };
    const call = { call_id: 'hangtime-live', target_alias: 'Fire Dispatch', source_alias: 'Engine 4',
      source_id: 30914, target_id: 1201, channel: 'North Ridge', system: 'Metro Public Safety',
      started_at_ms: Date.now() - 42_000, completed_at_ms: Date.now(), duration_ms: 42_000 };
    const recording = { id: 17, talkgroup_alias: 'EMS Dispatch', source_alias: 'Medic 9',
      source_id: 30919, talkgroup_id: 1202, channel_name: 'South Ridge', system_name: 'Metro Public Safety',
      start_ms: Date.now() - 42_000, end_ms: Date.now(), duration_ms: 42_000, protocol: 'APCO25_PHASE2' };
    const liveState = { current: call, displayCall: call, playing: true, paused: false, stopped: false,
      currentReady: true, lastCallReady: true, currentTime: 3, duration: 42, volume: 0.7,
      queue: [], queuedCount: 0, scanLists: [], avoids: [], targetGrouping: true, targetBurstLimit: 4 };
    const recordingState = { current: recording, displayCall: recording, playing: false, paused: true,
      stopped: false, currentTime: 3, duration: 42, volume: 0.7, queue: [], clickMode: 'once' };
    const observers = { live: new Set(), recordings: new Set() };
    const states = { live: liveState, recordings: recordingState };
    const notify = source => observers[source].forEach(listener => listener({ ...states[source] }));
    const subscribe = source => listener => {
      observers[source].add(listener); listener({ ...states[source] });
      return () => observers[source].delete(listener);
    };
    let hangtimeSeconds = 5;
    const writes = [];
    let now = Date.now(), nextTimer = 0;
    const timers = new Map();
    const retention = Object.fromEntries(['live', 'recordings'].map(source => [source,
      createCallDisplayRetention({ getHangtimeSeconds: () => hangtimeSeconds, now: () => now,
        setTimeout: (callback, delay) => { const id = ++nextTimer; timers.set(id, { callback, at: now + delay }); return id; },
        clearTimeout: id => timers.delete(id), onChange: () => {
        states[source].displayCall = retention[source].current(); notify(source);
      } })]));
    const live = { viewState: () => liveState, subscribeState: subscribe('live'),
      readAudioSpectrum: () => false, applyPreferences() {}, writePreferences() {},
      setVolume() {}, togglePlayback() {}, togglePause() {} };
    const renderer = createRecordingsFeature({ node, anchor: (text, path) => {
      const link = node('a', '', text); link.href = path; return link;
    }, href: () => '/', entityRefHref: () => null, canViewRadio: () => false, isPrimaryAdmin: () => false });
    const recordings = { viewState: () => recordingState, subscribeState: subscribe('recordings'),
      renderDetails: (host, value, options) => renderer.playback.renderDetails(host, value, options),
      renderTranscript: (host, value) => renderer.playback.renderTranscript(host, value),
      queueHref: () => '', setVolume() {}, stop() {}, toggle() {} };
    const component = createAudioDock({ node, iconButton, uiToggleField, recordings,
      getLivePlayer: () => recordingsOnly ? null : live,
      access: () => ({ live: !recordingsOnly, recordings: true }), entityRefHref: () => null,
      canViewRadio: () => false, href: () => '/', getTitlePreference: () => false,
      setTitlePreference: async () => {}, getCallDisplayHangtimeSeconds: () => hangtimeSeconds,
      setCallDisplayHangtimeSeconds: async value => {
        hangtimeSeconds = value; writes.push(value);
        Object.values(retention).forEach(display => display.refresh());
      } });
    window.hangtimeTest = { component, writes, states, retention,
      advance(milliseconds) {
        now += milliseconds;
        for (const [id, timer] of [...timers]) if (timer.at <= now) { timers.delete(id); timer.callback(); }
      },
      finish(source) {
        const state = states[source], completed = state.current;
        state.playing = false; state.paused = false; state.currentReady = false;
        state.stopped = source === 'recordings';
        if (source === 'live') state.current = null;
        retention[source].show(completed); notify(source);
      } };
  }, { recordingsOnly });
  const dock = page.locator('#audio-dock');
  await dock.locator('#audio-dock-size-handle').press('End');
  await expect(dock).toHaveAttribute('data-state', 'full');
  return dock;
}

for (const [source, retained] of [['live', 'Engine 4'], ['recordings', 'Medic 9']]) {
  test(`${source} call details share the player hangtime without reopening extending it`, async ({ page }) => {
    const dock = await openHangtimeDock(page);
    if (source === 'recordings') await selectDockSource(dock, source);
    await selectAudioPanel(dock, 'Details');
    await page.evaluate(source => window.hangtimeTest.finish(source), source);
    const details = dock.locator('#audio-dock-panel');
    await expect(details).toContainText(retained);
    await page.evaluate(() => window.hangtimeTest.advance(4500));
    await selectAudioPanel(dock, 'Player menu');
    await selectAudioPanel(dock, 'Details');
    await expect(details).toContainText(retained);
    await page.evaluate(() => window.hangtimeTest.advance(499));
    await expect(details).toContainText(retained);
    await page.evaluate(() => window.hangtimeTest.advance(1));
    await expect(details).not.toContainText(retained);
    await expect(dock.locator('.audio-dock-identity')).not.toContainText(retained);
    if (source === 'recordings') {
      expect(await page.evaluate(() => window.hangtimeTest.states.recordings.current.id)).toBe(17);
    }
  });
}

for (const recordingsOnly of [false, true]) {
  test(`hangtime accepts zero and sixty seconds ${recordingsOnly ? 'with recordings access only' : 'across both sources'}`, async ({ page }) => {
    const dock = await openHangtimeDock(page, { recordingsOnly });
    await selectAudioPanel(dock, 'Listening settings');
    const field = dock.getByRole('spinbutton', { name: 'Call display hangtime (seconds)', exact: true });
    await expect(field).toHaveValue('5');
    await expect(field).toHaveAttribute('min', '0');
    await expect(field).toHaveAttribute('max', '60');
    if (recordingsOnly) await expect(dock.getByRole('checkbox', { name: 'Group calls by target' })).toHaveCount(0);
    await field.fill('0'); await field.press('Tab');
    await expect.poll(() => page.evaluate(() => window.hangtimeTest.writes)).toEqual([0]);
    const source = recordingsOnly ? 'recordings' : 'live';
    await page.evaluate(source => window.hangtimeTest.finish(source), source);
    await selectAudioPanel(dock, 'Details');
    await expect(dock.locator('#audio-dock-panel')).not.toContainText(recordingsOnly ? 'Medic 9' : 'Engine 4');
    if (!recordingsOnly) await selectDockSource(dock, 'recordings');
    await selectAudioPanel(dock, 'Listening settings');
    await expect(field).toHaveValue('0');
    await field.fill('60'); await field.press('Tab');
    await expect.poll(() => page.evaluate(() => window.hangtimeTest.writes)).toEqual([0, 60]);
    await expect(field).toHaveValue('60');
    await page.evaluate(() => window.hangtimeTest.finish('recordings'));
    await selectAudioPanel(dock, 'Details');
    await page.evaluate(() => window.hangtimeTest.advance(59999));
    await expect(dock.locator('#audio-dock-panel')).toContainText('Medic 9');
    await page.evaluate(() => window.hangtimeTest.advance(1));
    await expect(dock.locator('#audio-dock-panel')).not.toContainText('Medic 9');
  });
}

async function openTitleHangtimeApp(page, { view = 'scanner', seconds = 9, titleEnabled = true } = {}) {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.clock.install({ time: new Date('2026-10-01T12:00:00Z') });
  const state = await openAudioApp(page, { view, callDisplayHangtimeSeconds: seconds, feedCallsEnabled: false });
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  const dock = page.locator('#audio-dock');
  if (view === 'scanner') await expect(page.locator('#scanner-player-menu')).toBeVisible();
  else {
    await dock.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').press('End');
    await expect(dock).toHaveAttribute('data-state', 'full');
  }
  if (!titleEnabled) {
    if (view === 'scanner') await page.locator('#scanner-player-menu').click();
    await selectAudioPanel(dock, 'Listening settings');
    const title = dock.getByRole('checkbox', { name: 'Playing call in page title', exact: true });
    await expect(title).toBeChecked();
    await title.focus(); await title.press('Space');
    await expect(title).not.toBeChecked();
    await expect.poll(() => state.preferenceWrites.some(write =>
      (write.preferences || write).page_titles?.prepend_playing_call === false)).toBe(true);
    await page.keyboard.press('Escape');
  }
  const baseTitle = await page.title();
  const pending = [{ ...liveCall, call_id: `title-hangtime-${view}-${seconds}-${titleEnabled}` }];
  let cursor = 0;
  await page.route('**/api/v1/calls/feed*', route => route.fulfill({ json: {
    data: { cursor: String(++cursor), reset: false, calls: pending.splice(0) }
  } }));
  const start = view === 'scanner' ? page.locator('#scanner-play') :
    dock.getByRole('button', { name: 'Listen live', exact: true });
  await start.click();
  await expect.poll(() => page.evaluate(() => window.audioTest.liveSources.filter(source => source.active).length)).toBe(1);
  // Pause only after the app and transport settle, so the deadline assertions
  // compare the actual completion time without real elapsed browser time.
  await page.clock.pauseAt(new Date(await page.evaluate(() => Date.now()) + 1000));
  return { dock, baseTitle };
}

async function finishTitleHangtimeCall(page) {
  await page.evaluate(() => {
    const source = window.audioTest.liveSources.find(value => value.active);
    if (!source?.onended) throw new Error('Expected an active fixture call to complete.');
    source.active = false;
    source.onended();
  });
}

for (const view of ['scanner', 'recordings']) {
  test(`page title shares the completed call hangtime in ${view}`, async ({ page }) => {
    const { dock, baseTitle } = await openTitleHangtimeApp(page, { view });
    await expect(page).toHaveTitle(/Fire Dispatch/);
    const playingTitle = await page.title();
    const identity = view === 'scanner' ? page.locator('.scanner-call-title') : dock.locator('.audio-dock-identity');
    await finishTitleHangtimeCall(page);
    await expect(page).toHaveTitle(playingTitle);
    await expect(identity).toContainText('Fire Dispatch');
    await page.clock.fastForward(8999);
    await expect(page).toHaveTitle(playingTitle);
    await expect(identity).toContainText('Fire Dispatch');
    await page.clock.fastForward(1);
    await expect(page).toHaveTitle(baseTitle);
    await expect(identity).not.toContainText('Fire Dispatch');
  });
}

test('page title clears immediately when call display hangtime is zero', async ({ page }) => {
  const { baseTitle } = await openTitleHangtimeApp(page, { view: 'recordings', seconds: 0 });
  await expect(page).toHaveTitle(/Fire Dispatch/);
  await finishTitleHangtimeCall(page);
  await expect(page).toHaveTitle(baseTitle);
});

test('page title hangtime follows the title preference while Scanner keeps its active call title', async ({ page }) => {
  const { baseTitle } = await openTitleHangtimeApp(page, { titleEnabled: false });
  await expect(page).toHaveTitle(/Fire Dispatch/);
  await finishTitleHangtimeCall(page);
  await expect(page).toHaveTitle(baseTitle);
  await expect(page.locator('.scanner-call-title')).toHaveText('Fire Dispatch');
});
