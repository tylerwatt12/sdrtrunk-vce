'use strict';

const { expect, test } = require('@playwright/test');
const { openAudioApp, liveCall } = require('./fixtures/audio-dock-app.cjs');
const { selectDockSource } = require('./fixtures/audio-dock-source.cjs');

const dock = (page) => page.locator('#audio-dock');
const hide = (page) => page.getByRole('button', { name: 'Hide audio player', exact: true });
const restore = (page) => page.getByRole('button', { name: 'Show audio player', exact: true });
const handle = (page) => dock(page).getByRole('button', { name: 'Change audio player size', exact: true });

async function size(page, value) {
  await handle(page).press(value === 'full' ? 'End' : 'Home');
  if (value === 'minimal') await handle(page).press('ArrowUp');
  await expect(dock(page)).toHaveAttribute('data-state', value);
}

async function openApp(page, options = {}) {
  const state = await openAudioApp(page, { feedCallsEnabled: false, ...options });
  // Capture the fixture media element without changing the production adapter.
  // Its normal timeupdate/ended events exercise observer and queue lifecycles.
  await page.evaluate(() => {
    const play = window.Audio.prototype.play;
    window.Audio.prototype.play = function (...args) {
      window.audioTest.recordingPlayer = this;
      return play.apply(this, args);
    };
  });
  return state;
}

async function chooseRecording(page, index, mode = 'Play once') {
  await page.locator('.recordings-play-glyph').nth(index).click();
  await page.getByRole('dialog', { name: 'Play recording', exact: true })
    .getByRole('button', { name: new RegExp(`^${mode}`) }).click();
}

async function navigate(page, view) {
  const destination = page.locator(`#primary-navigation a[href="/?view=${view}"]`);
  if (!await destination.isVisible()) await page.locator('[data-nav-group="listen"] summary').click();
  await destination.click();
  await expect(page).toHaveURL(new RegExp(`view=${view}`));
}

async function expectSuperCollapsed(page) {
  await expect(dock(page)).toBeVisible();
  await expect(dock(page)).toHaveAttribute('data-super-collapsed', 'true');
  await expect(dock(page).locator('.audio-dock-header')).toBeHidden();
  await expect(dock(page).locator('#audio-dock-content')).toBeHidden();
  await expect(restore(page)).toBeVisible();
  await expect(hide(page)).toBeHidden();
  await expect(dock(page).getByRole('button')).toHaveCount(1);
  const geometry = await restore(page).evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    const style = getComputedStyle(element);
    const hit = document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    return { width: bounds.width, height: bounds.height, right: innerWidth - bounds.right,
      bottom: innerHeight - bounds.bottom, radius: parseFloat(style.borderTopLeftRadius),
      reachable: Boolean(hit && element.contains(hit)), text: element.textContent.trim(),
      icon: element.querySelector('use')?.getAttribute('href') };
  });
  expect(geometry.width).toBeGreaterThanOrEqual(44);
  expect(geometry.height).toBeGreaterThanOrEqual(44);
  expect(Math.abs(geometry.width - geometry.height)).toBeLessThanOrEqual(1);
  // The computed radius can be a percentage or a resolved pixel radius.
  expect(geometry.radius).toBeGreaterThanOrEqual(geometry.width / 2 - 1);
  expect(geometry.right).toBeGreaterThanOrEqual(8);
  expect(geometry.right).toBeLessThanOrEqual(24);
  expect(geometry.bottom).toBeGreaterThanOrEqual(8);
  expect(geometry.bottom).toBeLessThanOrEqual(24);
  expect(geometry.reachable).toBe(true);
  expect(geometry.icon === '#icon-plus' || geometry.text === '+').toBe(true);
  // Resolve the semantic green through a temporary CSS color parser rather
  // than assuming that computed rgb() serializes like the hex token.
  const primaryColor = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    const context = canvas.getContext('2d');
    context.fillStyle = getComputedStyle(document.documentElement).getPropertyValue('--primary-control').trim();
    return context.fillStyle;
  });
  const buttonColor = await restore(page).evaluate((element) => {
    const context = document.createElement('canvas').getContext('2d');
    context.fillStyle = getComputedStyle(element).backgroundColor;
    return context.fillStyle;
  });
  expect(buttonColor).toBe(primaryColor);
}

for (const theme of ['light', 'dark']) {
  for (const value of ['collapsed', 'minimal', 'full']) {
    test(`desktop ${value} player hides to a green circle and restores its state in ${theme}`, async ({ page }) => {
      await page.setViewportSize({ width: 1280, height: 900 });
      await openApp(page, { theme });
      await size(page, 'full');
      await selectDockSource(dock(page), 'recordings');
      await dock(page).getByRole('tab', { name: 'Queue', exact: true }).click();
      await size(page, value);
      await expect(hide(page)).toBeVisible();
      const transport = await page.evaluate(() => [...window.audioTest.recordings]);
      await hide(page).focus();
      await hide(page).press('Enter');
      await expectSuperCollapsed(page);
      await expect(restore(page)).toBeFocused();
      expect(await page.evaluate(() => window.audioTest.recordings)).toEqual(transport);
      await restore(page).press('Space');
      await expect(dock(page)).toBeVisible();
      await expect(restore(page)).toBeHidden();
      await expect(dock(page)).toHaveAttribute('data-state', value);
      await expect(dock(page)).toHaveAttribute('data-source', 'recordings');
      await expect(hide(page)).toBeFocused();
      expect(await page.evaluate(() => window.audioTest.recordings)).toEqual(transport);
      if (value !== 'full') await size(page, 'full');
      await expect(dock(page).getByRole('tab', { name: 'Queue', exact: true })).toHaveAttribute('aria-selected', 'true');
    });
  }
}

test('hidden recording playback advances time and automatically plays the queued call at the same volume', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openApp(page);
  await chooseRecording(page, 0);
  await chooseRecording(page, 1, 'Add to queue');
  await size(page, 'full');
  await dock(page).getByRole('tab', { name: 'Transcript', exact: true }).click();
  const volume = dock(page).getByRole('slider', { name: 'Volume', exact: true });
  await volume.press('Home');
  for (let i = 0; i < 13; i++) await volume.press('ArrowRight');
  await expect(volume).toHaveValue('0.65');
  await expect(dock(page).getByRole('button', { name: 'Queue 1', exact: true })).toBeVisible();
  const transport = await page.evaluate(() => [...window.audioTest.recordings]);
  await hide(page).click();
  await expectSuperCollapsed(page);
  expect(await page.evaluate(() => window.audioTest.recordings)).toEqual(transport);
  await page.evaluate(() => {
    const audio = window.audioTest.recordingPlayer;
    if (!audio || audio.paused) throw new Error('Expected the recording to keep playing.');
    audio.currentTime = 9;
    audio.dispatchEvent(new Event('timeupdate'));
  });
  await expect(restore(page)).toBeVisible();
  await restore(page).click();
  await expect(dock(page).getByRole('slider', { name: 'Recording position', exact: true })).toHaveValue('9');
  await expect(dock(page).getByRole('tab', { name: 'Transcript', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(dock(page).getByRole('button', { name: 'Pause recording', exact: true })).toBeVisible();
  await hide(page).click();
  await page.evaluate(() => {
    const audio = window.audioTest.recordingPlayer;
    audio.currentTime = audio.duration;
    audio.paused = true;
    audio.dispatchEvent(new Event('ended'));
  });
  await expect.poll(() => page.evaluate(() => window.audioTest.recordings.filter((entry) => entry.action === 'play').length)).toBe(2);
  await expect.poll(() => page.evaluate(() => window.audioTest.recordingPlayer.src)).toMatch(/\/18\/audio$/);
  expect(await page.evaluate(() => ({ paused: window.audioTest.recordingPlayer.paused,
    volume: window.audioTest.recordingPlayer.volume }))).toEqual({ paused: false, volume: 0.65 });
  await expectSuperCollapsed(page);
  await restore(page).click();
  await expect(dock(page)).toHaveAttribute('data-state', 'full');
  await expect(dock(page)).toHaveAttribute('data-source', 'recordings');
  await expect(dock(page).getByRole('button', { name: 'Queue 0', exact: true })).toBeVisible();
  await expect(volume).toHaveValue('0.65');
  await expect(dock(page)).toContainText('Engine 5');
});

test('live calls continue arriving and auto-advancing while hidden without changing listening or volume', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const state = await openApp(page, { volume: 0.65 });
  const feed = { pending: [], requests: 0 };
  await page.route('**/api/v1/calls/feed*', async (route) => {
    feed.requests++;
    await route.fulfill({ json: { data: { cursor: String(feed.requests), reset: false, calls: feed.pending.splice(0) } } });
  });
  await size(page, 'full');
  await dock(page).getByRole('tab', { name: 'Listening', exact: true }).click();
  await dock(page).getByRole('checkbox', { name: /County Fire and Emergency Medical Services/ }).check();
  feed.pending.push(liveCall);
  await dock(page).getByRole('button', { name: 'Listen live', exact: true }).click();
  await expect(dock(page)).toContainText('Engine 4');
  await expect.poll(() => page.evaluate(() => window.audioTest.liveSources.filter((source) => source.active).length)).toBe(1);
  const before = { sources: await page.evaluate(() => window.audioTest.liveSources.length),
    recordings: await page.evaluate(() => [...window.audioTest.recordings]), writes: state.preferenceWrites.length };
  await hide(page).click();
  await expectSuperCollapsed(page);
  expect(await page.evaluate(() => window.audioTest.liveSources.length)).toBe(before.sources);
  expect(await page.evaluate(() => window.audioTest.liveSources.filter((source) => source.active).length)).toBe(1);
  expect(await page.evaluate(() => window.audioTest.recordings)).toEqual(before.recordings);
  const requests = feed.requests;
  feed.pending.push({ ...liveCall, call_id: 'super-collapse-live-2', started_at_ms: liveCall.started_at_ms + 60_000,
    completed_at_ms: liveCall.completed_at_ms + 60_000, source_alias: 'Engine 5', source_id: 30915 });
  await expect.poll(() => feed.requests).toBeGreaterThan(requests);
  await expect.poll(() => feed.pending.length).toBe(0);
  await expectSuperCollapsed(page);
  await page.evaluate(() => {
    const source = window.audioTest.liveSources.find((item) => item.active);
    if (!source?.onended) throw new Error('Expected an active live call.');
    source.active = false;
    source.onended();
  });
  await expect.poll(() => page.evaluate(() => window.audioTest.liveSources.length)).toBe(before.sources + 1);
  await expectSuperCollapsed(page);
  await restore(page).click();
  await expect(dock(page)).toContainText('Engine 5');
  await expect(dock(page).getByRole('button', { name: 'Pause live audio', exact: true })).toBeVisible();
  await expect(dock(page).getByRole('slider', { name: 'Volume', exact: true })).toHaveValue('0.65');
  await expect(dock(page).getByRole('tab', { name: 'Listening', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(dock(page).getByRole('checkbox', { name: /County Fire and Emergency Medical Services/ })).toBeChecked();
  expect(state.preferenceWrites.length).toBe(before.writes);
});

test('starting a recording from the page while hidden changes source without showing the dock', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openApp(page);
  await size(page, 'full');
  await hide(page).click();
  await chooseRecording(page, 0);
  await expectSuperCollapsed(page);
  await restore(page).click();
  await expect(dock(page)).toHaveAttribute('data-source', 'recordings');
  await expect(dock(page)).toHaveAttribute('data-state', 'full');
  await expect(dock(page).getByRole('button', { name: 'Pause recording', exact: true })).toBeVisible();
});

test('super collapse keeps desktop page geometry unchanged and remains hidden through navigation and Scanner', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const state = await openApp(page);
  // The fixture has 30 calls, with 25 rendered on the first result page. Wait
  // for real results before measuring so page loading cannot imitate a dock
  // layout change when this case runs behind the complete visual suite.
  await expect(page.locator('.recordings-call')).toHaveCount(25);
  await expect(page.locator('.recordings-call').last()).toContainText('Engine 28');
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  await size(page, 'full');
  const geometry = () => page.locator('.content').evaluate((content) => ({
    height: content.getBoundingClientRect().height, scrollHeight: content.scrollHeight,
    paddingBottom: getComputedStyle(content).paddingBottom,
    spacer: getComputedStyle(content, '::after').content,
    documentHeight: document.documentElement.scrollHeight,
    scrollPaddingBottom: getComputedStyle(document.documentElement).scrollPaddingBottom,
  }));
  const before = await geometry();
  const recordingRequests = state.requests.filter((request) => request.path === '/api/v1/recordings/calls').length;
  const writes = state.requests.filter((request) => request.method !== 'GET');
  const transport = await page.evaluate(() => [...window.audioTest.recordings]);
  await hide(page).click();
  await expectSuperCollapsed(page);
  await expect.poll(geometry).toEqual(before);
  expect(state.requests.filter((request) => request.path === '/api/v1/recordings/calls').length).toBe(recordingRequests);
  expect(state.requests.filter((request) => request.method !== 'GET')).toEqual(writes);
  expect(await page.evaluate(() => window.audioTest.recordings)).toEqual(transport);
  await navigate(page, 'dashboard');
  await expectSuperCollapsed(page);
  await navigate(page, 'scanner');
  await expect(dock(page)).toBeHidden();
  await expect(restore(page)).toBeHidden();
  await navigate(page, 'recordings');
  await expectSuperCollapsed(page);
  await restore(page).click();
  await expect(dock(page)).toHaveAttribute('data-state', 'full');
});

test('hidden desktop choice is suspended on mobile and retained when returning to desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openApp(page);
  await size(page, 'minimal');
  await hide(page).click();
  await expectSuperCollapsed(page);
  for (const width of [900, 390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    await expect(dock(page)).toBeVisible();
    await expect(hide(page)).toBeHidden();
    await expect(restore(page)).toBeHidden();
    await expect(dock(page)).toHaveAttribute('data-state', 'minimal');
    await expect(handle(page)).toBeFocused();
  }
  const sourcePicker = dock(page).getByRole('combobox', { name: 'Audio source', exact: true });
  await sourcePicker.focus();
  await expect(sourcePicker).toBeFocused();
  await page.setViewportSize({ width: 901, height: 900 });
  await expectSuperCollapsed(page);
  await expect(restore(page)).toBeFocused();
  await restore(page).click();
  await expect(dock(page)).toHaveAttribute('data-state', 'minimal');
});

test('short desktop full player hides every control and restores in its original size', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 500 });
  await openApp(page);
  expect(await page.evaluate(() => matchMedia('(pointer: fine)').matches)).toBe(true);
  await size(page, 'full');
  await hide(page).click();
  await expectSuperCollapsed(page);
  await expect(dock(page).getByRole('slider')).toHaveCount(0);
  await expect(dock(page).getByRole('tab')).toHaveCount(0);
  await restore(page).click();
  await expect(dock(page)).toHaveAttribute('data-state', 'full');
  await expect(dock(page).getByRole('slider', { name: 'Volume', exact: true })).toBeVisible();
  await expect(hide(page)).toBeFocused();
});

test('recording completion while hidden preserves detail disclosures and keeps the idle dock hidden', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openApp(page);
  await chooseRecording(page, 0);
  await size(page, 'full');
  await dock(page).getByRole('tab', { name: 'Details', exact: true }).click();
  const identities = dock(page).locator('details').filter({ has: page.locator('summary', { hasText: 'Source & target identities' }) });
  await identities.locator('summary').click();
  await expect(identities).toHaveJSProperty('open', true);
  const fieldLabels = await dock(page).locator('#audio-dock-panel dt').allTextContents();
  expect(fieldLabels).toContain('Source alias');
  await hide(page).click();
  await page.evaluate(() => {
    const audio = window.audioTest.recordingPlayer;
    audio.currentTime = audio.duration;
    audio.paused = true;
    audio.dispatchEvent(new Event('ended'));
  });
  await expect.poll(() => page.evaluate(() => window.audioTest.recordingPlayer.src)).toBe('');
  await expectSuperCollapsed(page);
  await restore(page).click();
  await expect(dock(page).getByRole('tab', { name: 'Details', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(identities).toHaveJSProperty('open', true);
  expect(await dock(page).locator('#audio-dock-panel dt').allTextContents()).toEqual(fieldLabels);
  await expect.poll(async () => (await dock(page).locator('#audio-dock-panel dd').allTextContents())
    .every((value) => !/[A-Za-z0-9]/.test(value))).toBe(true);
  await expect(dock(page)).toContainText('Playback finished.');
  await expect(dock(page).getByRole('button', { name: 'Play recording', exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.audioTest.recordings.filter((entry) => entry.action === 'play').length)).toBe(1);
});

test.describe('short coarse-pointer viewport', () => {
  test.use({ hasTouch: true });
  test('desktop hidden choice yields to the mobile dock in a short wide touch viewport', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await openApp(page);
    await hide(page).click();
    await expectSuperCollapsed(page);
    await page.setViewportSize({ width: 1050, height: 450 });
    expect(await page.evaluate(() => matchMedia('(pointer: coarse)').matches)).toBe(true);
    await expect(dock(page)).toBeVisible();
    await expect(hide(page)).toBeHidden();
    await expect(restore(page)).toBeHidden();
    await page.setViewportSize({ width: 1280, height: 900 });
    await expectSuperCollapsed(page);
  });
});

for (const [name, capabilities] of [
  ['no audio access', { recordings: false, 'call-audio': false }],
  ['restricted web access', { 'web-access': false }],
]) {
  test(`${name} does not expose the dock or its restore button`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    const state = await openApp(page, { view: 'dashboard', capabilities });
    await expect(dock(page)).toBeHidden();
    await expect(hide(page)).toBeHidden();
    await expect(restore(page)).toBeHidden();
    expect(state.requests.some((request) => request.path === '/api/v1/calls/feed')).toBe(false);
  });
}
