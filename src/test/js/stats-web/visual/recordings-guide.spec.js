'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const guide = page => page.locator('.ui-setup-guide[data-guide-id="recordings"]');
const steps = ['targets-button', 'targets', 'time-range', 'search', 'transcript-search', 'transcript-submit',
  'call-card', 'details-button', 'details', 'details-close', 'actions-button', 'playback', 'player-overview', 'player-button', 'click-mode'];
const memoryKey = 'sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:recordings:4';
const evidence = process.env.RECORDINGS_GUIDE_EVIDENCE ||
  path.resolve(__dirname, '../../../../../build/playwright-results/recordings-guide-evidence');
const nonGuideStorage = page => page.evaluate(() => Object.fromEntries(Object.entries(localStorage)
  .filter(([key]) => !key.startsWith('sdrtrunk-vce-guide:'))));

function observeEffects(page) {
  const effects = { writes: [], samples: [], audio: [], sockets: [] };
  page.on('request', request => {
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/v1/')) return;
    if (!['GET', 'HEAD'].includes(request.method()) && url.pathname !== '/api/v1/live/multiplex/control')
      effects.writes.push(`${request.method()} ${url.pathname}`);
    if (/guide-recordings|recordings-guide|sample-recordings|tour-recordings|b00bc000|\b970[1-7]\b|p25:bee00:(123|456)|v1-[rg]-bee00/i
      .test(decodeURIComponent(url.pathname + url.search + (request.postData() || ''))))
      effects.samples.push(`${request.method()} ${url.pathname}`);
    if (/\/audio(?:$|\/)/.test(url.pathname)) effects.audio.push(url.pathname);
  });
  page.on('websocket', socket => effects.sockets.push(socket.url()));
  return effects;
}

async function openRecordings(page, { theme = 'light', replay = true, firstVisit = false, denied = [], empty = false } = {}) {
  const effects = observeEffects(page);
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const fixture = await installSiteStyleApplication(page, theme, { revision: 1, tables: [] }, { guides: firstVisit });
  await page.addInitScript(({ firstVisit, key }) => {
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:welcome:1', '1');
    for (const version of [1, 2, 3]) localStorage.setItem(
      `sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:recordings:${version}`, '1');
    if (!firstVisit) localStorage.setItem(key, '1');
    window.recordingsGuideAudioEvents = [];
    for (const name of ['AudioContext', 'webkitAudioContext']) {
      const Original = window[name];
      if (Original) window[name] = new Proxy(Original, { construct(target, args) {
        window.recordingsGuideAudioEvents.push(name);
        return Reflect.construct(target, args);
      } });
    }
    const play = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function (...args) {
      window.recordingsGuideAudioEvents.push(`play:${this.src}`);
      return play.apply(this, args);
    };
  }, { firstVisit, key: memoryKey });
  if (empty) {
    await page.route('**/api/v1/recordings/status', route => route.fulfill({ json: { data: {
      mode: 'CLASSIC', available: false, has_calls: false
    } } }));
    await page.route('**/api/v1/recordings/calls?*', route => route.fulfill({ json: { data: {
      calls: [], next_cursor: null, total: 0
    } } }));
  }
  if (denied.length) {
    const file = path.resolve(__dirname, '../../../../../src/main/java/io/github/dsheirer/web/auth/WebCapability.java');
    const capabilities = Object.fromEntries([...fs.readFileSync(file, 'utf8').matchAll(/^\s+[A-Z_]+\("([^"]+)",/gm)]
      .map(([, id]) => [id, !denied.includes(id)]));
    await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
      configured: true, authenticated: true, username: 'fixture-admin', tier: 'admin', primary: true,
      csrf_token: 'fixture-csrf', guide_dismissal_scope: 'account-1-100', capabilities
    } } }));
  }
  await page.goto(`/app.html?view=recordings${replay ? '&guide=recordings' : ''}`);
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  return { fixture, effects };
}

async function expectInViewport(locator) {
  await expect.poll(async () => {
    const box = await locator.boundingBox(), viewport = locator.page().viewportSize();
    return box && box.x >= 0 && box.y >= 0 && box.x + box.width <= viewport.width + 1 &&
      box.y + box.height <= viewport.height + 1 ? null : box;
  }, { message: 'The actual control and guide remain fully visible' }).toBeNull();
}

async function expectOverview(page) {
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await expect(guide(page).locator('.ui-setup-guide-counter')).toHaveText('Overview');
  await expect(guide(page)).toContainText('saved calls');
  await expect(page.getByRole('heading', { name: 'Recordings', exact: true })).toBeVisible();
  await expect(page.locator('.ui-setup-guide-highlight, [data-guide-sample], [data-guide-example]')).toHaveCount(0);
  await expectInViewport(guide(page));
}

async function start(page) {
  await expectOverview(page);
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).click();
  await expect(guide(page)).toHaveAttribute('data-guide-step', steps[0]);
}

async function advance(page, id) {
  await guide(page).getByRole('button', { name: id === steps.at(-1) ? 'Finish' : 'Next', exact: true }).click();
}

async function goTo(page, destination) {
  const current = await guide(page).getAttribute('data-guide-step');
  for (const id of steps.slice(Math.max(0, steps.indexOf(current)))) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    if (id === destination) return;
    await advance(page, id);
  }
  throw new Error(`Unknown Recordings guide step: ${destination}`);
}

async function expectSpotlight(page, id) {
  const names = id === 'transcript-search' ? ['transcript-search', 'transcript-range'] :
    id === 'transcript-submit' ? ['search'] : [id];
  const targets = page.locator(names.map(name => name === 'player-overview' ?
    '[data-guide-target="player"]:visible' : `[data-guide-target="rec-${name}"]:visible`).join(', '));
  await expect(targets.first()).toBeVisible();
  for (const target of await targets.all()) {
    await expectInViewport(target);
    await expect.poll(() => target.evaluate(element => {
      const rect = element.getBoundingClientRect();
      const header = document.querySelector('.app-header')?.getBoundingClientRect();
      const dialog = element.closest('.read-only-modal:not(.ui-setup-guide)');
      const card = document.querySelector('.ui-setup-guide').getBoundingClientRect();
      const highlights = [...document.querySelectorAll('.ui-setup-guide-highlight')].map(item => item.getBoundingClientRect());
      const overlap = Math.min(rect.right, card.right) - Math.max(rect.left, card.left) > 1 &&
        Math.min(rect.bottom, card.bottom) - Math.max(rect.top, card.top) > 1;
      const covered = highlights.some(box => box.left <= rect.left + 1 && box.top <= rect.top + 1 &&
        box.right >= rect.right - 1 && box.bottom >= rect.bottom - 1);
      // Actual dialogs normally overlay the application header. Their Close
      // buttons keep that production position during the tour.
      return (dialog || !header || rect.top >= header.bottom - 1) && !overlap && covered ? null :
        { target: rect.toJSON(), headerBottom: header?.bottom, overlap, covered };
    }), { message: `rec-${id} highlights the actual feature clearly in its normal page or dialog position` }).toBeNull();
  }
  await expectInViewport(guide(page));
  await expect.poll(() => guide(page).evaluate(element => {
    const back = [...element.querySelectorAll('button')].find(button => button.textContent.trim() === 'Back');
    const next = [...element.querySelectorAll('button')].find(button => /^(Next|Finish)/.test(button.textContent.trim()));
    const b = back?.getBoundingClientRect(), n = next?.getBoundingClientRect();
    return b && n && Math.abs(b.top - n.top) <= 1 && b.right <= n.left;
  }), { message: 'Guide navigation stays side by side' }).toBe(true);
}

async function expectLessonState(page, id) {
  const picker = page.getByRole('dialog', { name: 'Talkgroups & channels', exact: true });
  const details = page.getByRole('dialog', { name: 'Call details', exact: true });
  const actions = page.getByRole('dialog', { name: 'Call actions', exact: true });
  await expect(picker).toHaveCount(id === 'targets' ? 1 : 0);
  await expect(details).toHaveCount(['details', 'details-close'].includes(id) ? 1 : 0);
  await expect(actions).toHaveCount(id === 'playback' ? 1 : 0);
  await expect(page.locator('.recordings-call').first()).toBeAttached();
  if (id === 'targets-button') await expect(page.locator('#recordings-open-targets')).toBeVisible();
  if (id === 'targets') {
    for (const tab of ['All', 'Talkgroups', 'Channels'])
      await expect(picker.getByRole('tab', { name: tab, exact: true })).toBeVisible();
    await expect(picker).toContainText('County P25');
    await expect(picker).toContainText('City P25');
    await expect(picker.locator('.recordings-picker-choice').first()).toBeVisible();
    await expect(picker.getByRole('button', { name: 'Use selection', exact: true })).toBeVisible();
  }
  if (id === 'time-range') await expect(page.getByRole('combobox', { name: 'Time range', exact: true })).toBeVisible();
  if (['search', 'transcript-submit'].includes(id)) {
    await expect(page.getByRole('button', { name: 'Search recordings', exact: true })).toBeVisible();
  }
  if (id === 'transcript-search') {
    await expect(page.getByLabel('Full text', { exact: true })).toHaveValue(/arrived at staging/);
    await expect(page.getByRole('combobox', { name: 'Full text range', exact: true })).toHaveValue('recent');
    await expect(guide(page)).toContainText('24 hours');
  }
  if (id === 'details-button') await expect(page.locator('.recordings-call-details').first()).toBeVisible();
  if (id === 'call-card') {
    await expect(page.locator('[data-guide-target="rec-call-card"]')).toHaveClass(/recordings-call/);
    await expect(guide(page)).toContainText(/call/i);
    await expect(guide(page)).not.toContainText('Open Call details');
  }
  if (['details', 'details-close'].includes(id)) {
    await expect(details).toBeVisible();
    for (const tab of ['Overview', 'Transcript', 'Identities', 'Technical', 'Recording'])
      await expect(details.getByRole('tab', { name: tab, exact: true })).toBeVisible();
    await expect(page.locator('.recordings-library')).toBeAttached();
  }
  if (id === 'actions-button') await expect(page.locator('.recordings-call-info').first()).toBeVisible();
  if (id === 'playback') {
    for (const name of ['Play once', 'Continue from here', 'Add to queue'])
      await expect(actions.getByRole('button', { name, exact: true })).toBeVisible();
    await expect(guide(page)).toContainText('newer');
  }
  if (['player-overview', 'player-button'].includes(id)) {
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-source', 'recordings');
    await expect(page.locator('#audio-dock')).not.toHaveAttribute('data-state', 'full');
  }
  if (id === 'player-overview') {
    await expect(page.locator('[data-guide-target="player"]')).toHaveAttribute('id', 'audio-dock');
    await expect(guide(page)).not.toContainText('Expand the player');
  }
  if (id === 'player-button') {
    await expect(page.locator('[data-guide-target="rec-player-button"]')).toHaveAttribute('aria-label', 'Expand player');
  }
  if (id === 'click-mode') {
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-source', 'recordings');
    await expect(page.locator('#audio-dock-click-mode')).toBeVisible();
    await expect(page.locator('#audio-dock-click-mode option')).toHaveText([
      'Queue clicked calls', 'Play once', 'Continue matching calls'
    ]);
    await expect(page.locator('#audio-dock')).toContainText('Up next');
    await expect(page.locator('#audio-dock')).toContainText(/Regional Fire|City Fire|Engine/);
  }
}

function expectIsolated(fixture, effects) {
  expect(effects.writes).toEqual([]);
  expect(effects.samples).toEqual([]);
  expect(effects.audio).toEqual([]);
  expect(effects.sockets).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
}

async function screenshot(page, name) {
  fs.mkdirSync(path.join(evidence, 'screens'), { recursive: true });
  await page.screenshot({ path: path.join(evidence, 'screens', `${name}.png`) });
}

for (const { theme, width, height } of [
  { theme: 'light', width: 1280, height: 720 }, { theme: 'dark', width: 1440, height: 1000 }
]) {
  test(`Recordings tours the actual filter picker, dialogs and player in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    const { fixture, effects } = await openRecordings(page, { theme, empty: true });
    await expectOverview(page);
    const storage = await nonGuideStorage(page), url = page.url();
    await screenshot(page, `recordings-overview-${theme}`);
    await start(page);
    for (const [index, id] of steps.entries()) {
      await expect(guide(page)).toHaveAttribute('data-guide-step', id);
      await expect(guide(page)).toContainText(`Step ${index + 1} of ${steps.length}`);
      try { await expectSpotlight(page, id); await expectLessonState(page, id); }
      catch (error) {
        await screenshot(page, `recordings-failure-${id}-${theme}`);
        fs.writeFileSync(path.join(evidence, `geometry-${id}-${theme}.json`), JSON.stringify(await page.evaluate(() => ({
          scroll: [scrollX, scrollY], elements: ['.ui-setup-guide', '.app-header', '[data-guide-target]', '[role="dialog"]']
            .flatMap(selector => [...document.querySelectorAll(selector)].map(element => ({
              selector, target: element.dataset.guideTarget, box: element.getBoundingClientRect().toJSON()
            })))
        })), null, 2));
        throw error;
      }
      await expect(page).toHaveURL(url);
      await screenshot(page, `recordings-${id}-${theme}`);
      await advance(page, id);
    }
    await expect(page.locator('.ui-setup-guide, [data-guide-sample], [data-guide-example], .ui-setup-guide-highlight, .ui-setup-guide-layer')).toHaveCount(0);
    await expect(page.locator('.recordings-call')).toHaveCount(0);
    await expect(page).toHaveURL(url);
    expect(await nonGuideStorage(page)).toEqual(storage);
    expect(await page.evaluate(() => window.recordingsGuideAudioEvents)).toEqual([]);
    expectIsolated(fixture, effects);
  });
}

for (const { stop, dismiss } of [
  { stop: 'targets', dismiss: 'Close guide' }, { stop: 'details', dismiss: 'Escape' },
  { stop: 'click-mode', dismiss: 'shade' }
]) {
  test(`Recordings restores original results, search draft, scroll and player after ${dismiss}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    const { fixture, effects } = await openRecordings(page, { replay: false });
    const transcript = page.getByLabel('Full text', { exact: true });
    await transcript.fill('Unsaved search draft');
    await page.getByRole('combobox', { name: 'Full text range', exact: true }).selectOption('time');
    await page.getByRole('combobox', { name: 'Time range', exact: true }).selectOption('7d');
    await page.evaluate(() => {
      const content = document.getElementById('content');
      window.recordingsGuideOriginal = { content, nodes: [...content.childNodes],
        transcript: document.getElementById('recordings-input-transcript'),
        call: content.querySelector('.recordings-call') };
      window.scrollTo(0, Math.min(120, document.scrollingElement.scrollHeight - innerHeight));
    });
    const scroll = await page.evaluate(() => [scrollX, scrollY]);
    const storage = await nonGuideStorage(page), url = page.url(), title = await page.title();
    const playerBefore = await page.locator('#audio-dock').evaluate(element => ({ source: element.dataset.source,
      state: element.dataset.state, collapsed: element.dataset.superCollapsed }));
    await page.locator('#page-guide-button').evaluate(button => {
      button.focus({ preventScroll: true }); button.click();
    });
    await start(page); await goTo(page, stop);
    await guide(page).getByRole('button', { name: 'Back', exact: true }).click();
    await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
    await expect(guide(page)).toHaveAttribute('data-guide-step', stop);
    if (dismiss === 'Escape') await page.keyboard.press('Escape');
    else if (dismiss === 'shade') await page.mouse.click(8, 500);
    else await guide(page).getByRole('button', { name: dismiss, exact: true }).click();
    await expect(guide(page)).toHaveCount(0);
    expect(await page.locator('#content').evaluate(element => {
      const original = window.recordingsGuideOriginal;
      return element === original.content && element.querySelector('.recordings-call') === original.call &&
        document.getElementById('recordings-input-transcript') === original.transcript &&
        original.nodes.every((node, index) => element.childNodes[index] === node);
    })).toBe(true);
    await expect(transcript).toHaveValue('Unsaved search draft');
    await expect(page.getByRole('combobox', { name: 'Full text range', exact: true })).toHaveValue('time');
    await expect(page.getByRole('combobox', { name: 'Time range', exact: true })).toHaveValue('7d');
    await expect(page).toHaveURL(url); await expect(page).toHaveTitle(title);
    expect(await nonGuideStorage(page)).toEqual(storage);
    expect(await page.evaluate(() => [scrollX, scrollY])).toEqual(scroll);
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-source', playerBefore.source);
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-state', playerBefore.state);
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-super-collapsed', playerBefore.collapsed);
    await expect(page.locator('#page-guide-button')).toBeFocused();
    await expect(page.locator('[data-guide-sample], [data-guide-example], .ui-setup-guide-highlight, .ui-setup-guide-layer')).toHaveCount(0);
    expect(await page.evaluate(() => window.recordingsGuideAudioEvents)).toEqual([]);
    expectIsolated(fixture, effects);
  });
}

test('Recordings shows each opening control before its actual destination and can go Back', async ({ page }) => {
  const { fixture, effects } = await openRecordings(page);
  await start(page);
  for (const [opener, destination, dialog] of [
    ['targets-button', 'targets', 'Talkgroups & channels'], ['details-button', 'details', 'Call details'],
    ['actions-button', 'playback', 'Call actions']
  ]) {
    await goTo(page, opener);
    await expect(page.getByRole('dialog', { name: dialog, exact: true })).toHaveCount(0);
    await expectSpotlight(page, opener);
    await advance(page, opener);
    await expect(guide(page)).toHaveAttribute('data-guide-step', destination);
    await expect(page.getByRole('dialog', { name: dialog, exact: true })).toBeVisible();
    await guide(page).getByRole('button', { name: 'Back', exact: true }).click();
    await expect(guide(page)).toHaveAttribute('data-guide-step', opener);
    await expect(page.getByRole('dialog', { name: dialog, exact: true })).toHaveCount(0);
    await advance(page, opener);
  }
  await page.keyboard.press('Escape');
  expectIsolated(fixture, effects);
});

test('Recordings sample controls cannot write, request sample calls, navigate, or play audio', async ({ page }) => {
  const { fixture, effects } = await openRecordings(page);
  await start(page);
  const storage = await nonGuideStorage(page), url = page.url();
  let exercised = 0;
  for (const id of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    exercised += await page.evaluate(() => {
      const roots = [...document.querySelectorAll('[data-guide-sample]')];
      const controls = [...new Set(roots.flatMap(root => [...root.querySelectorAll('button, a, input, select, form, [role="button"]')]))];
      for (const control of controls) {
        control.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
        if (control.tagName === 'FORM') control.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      }
      return controls.length;
    });
    await expect(page).toHaveURL(url);
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    await advance(page, id);
  }
  expect(exercised).toBeGreaterThan(50);
  expect(await nonGuideStorage(page)).toEqual(storage);
  expect(await page.evaluate(() => window.recordingsGuideAudioEvents)).toEqual([]);
  expectIsolated(fixture, effects);
});

test('Recordings authored version replaces its placeholder and replays from Help and named URL', async ({ page }) => {
  const { fixture, effects } = await openRecordings(page, { replay: false, firstVisit: true });
  await expectOverview(page); await page.keyboard.press('Escape');
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBe('1');
  await page.reload();
  await expect(page.locator('#page-guide-button')).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  await page.locator('#page-guide-button').click(); await start(page); await page.keyboard.press('Escape');
  await page.goto('/app.html?view=recordings&guide=recordings');
  await expectOverview(page); await page.keyboard.press('Escape');
  expectIsolated(fixture, effects);
});

test('Recordings sample playback preserves an active real recording queue and click preference', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const state = await openAudioApp(page, { feedCallsEnabled: false });
  const player = page.locator('#audio-dock');
  await page.locator('.recordings-call-info').first().click();
  await page.getByRole('dialog', { name: 'Call actions', exact: true })
    .getByRole('button', { name: 'Continue from here', exact: true }).click();
  await expect(player).toHaveAttribute('data-source', 'recordings');
  await player.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').press('End');
  await expect(player).toHaveAttribute('data-state', 'full');
  await expect(player).toContainText('ENG 5');
  await player.locator('#audio-dock-click-mode').selectOption('once');
  const before = await player.evaluate(element => ({ source: element.dataset.source,
    state: element.dataset.state, collapsed: element.dataset.superCollapsed }));
  const audioBefore = await page.evaluate(() => window.audioTest.recordings);
  const storage = await nonGuideStorage(page), url = page.url();
  await page.locator('#page-guide-button').evaluate(button => { button.focus({ preventScroll: true }); button.click(); });
  await start(page); await goTo(page, 'click-mode');
  await expect(player).toContainText('Engine 7');
  await page.keyboard.press('Escape');
  await expect(guide(page)).toHaveCount(0);
  await expect(player).toHaveAttribute('data-source', before.source);
  await expect(player).toHaveAttribute('data-state', before.state);
  await expect(player).toHaveAttribute('data-super-collapsed', before.collapsed);
  await expect(player).toContainText('ENG 5');
  await expect(player).not.toContainText('County P25');
  await expect(player.locator('#audio-dock-click-mode')).toHaveValue('once');
  expect(await page.evaluate(() => window.audioTest.recordings)).toEqual(audioBefore);
  expect(await nonGuideStorage(page)).toEqual(storage);
  await expect(page).toHaveURL(url);
  expect(state.requests.filter(request => /guide-recordings|tour-recordings|bee00|9702/i.test(
    request.path + JSON.stringify(request.query)))).toEqual([]);
});

test('Recordings navigation releases its samples and restores the normal library on return', async ({ page }) => {
  const { fixture, effects } = await openRecordings(page);
  await start(page); await goTo(page, 'details');
  await page.locator('.primary-nav [data-view="live"]').evaluate(link => link.click());
  await expect(page.locator('.live-split')).toBeVisible();
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], [data-guide-example], .ui-setup-guide-layer')).toHaveCount(0);
  await page.locator('.primary-nav [data-view="recordings"]').evaluate(link => link.click());
  await expect(page.getByRole('heading', { name: 'Recordings', exact: true })).toBeVisible();
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  await expect(page.locator('.recordings-call')).toContainText('Fire Dispatch');
  await expect(page.locator('.recordings-call')).not.toContainText('County P25');
  expectIsolated(fixture, effects);
});

test('Recordings dismissal during preparation cannot reopen its example library or player', async ({ page }) => {
  const { fixture, effects } = await openRecordings(page, { replay: false, empty: true });
  await page.locator('#page-guide-button').click(); await expectOverview(page);
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).evaluate(button => {
    button.click();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
  });
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], [data-guide-example]')).toHaveCount(0);
  await expect(page.locator('.recordings-call')).toHaveCount(0);
  await page.locator('#page-guide-button').click(); await start(page); await page.keyboard.press('Escape');
  await expect(page.locator('[data-guide-sample], [data-guide-example]')).toHaveCount(0);
  expect(await page.evaluate(() => window.recordingsGuideAudioEvents)).toEqual([]);
  expectIsolated(fixture, effects);
});

test('phone visits leave the desktop Recordings tour and examples closed', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const { fixture, effects } = await openRecordings(page, { firstVisit: true });
  await expect(page.getByRole('heading', { name: 'Recordings', exact: true })).toBeVisible();
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], [data-guide-example]')).toHaveCount(0);
  await expect(page.locator('#page-guide-button')).toBeHidden();
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBeNull();
  expectIsolated(fixture, effects);
});

test('Recordings access denial cannot reveal the guide or example calls', async ({ page }) => {
  const { fixture, effects } = await openRecordings(page, { denied: ['recordings'], firstVisit: true });
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], [data-guide-example]')).toHaveCount(0);
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBeNull();
  expectIsolated(fixture, effects);
});

for (const theme of ['light', 'dark']) {
  test(`Recordings guide and actual player labels retain contrast across all 360 hues in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const { fixture, effects } = await openRecordings(page, { theme });
    await start(page); await goTo(page, 'click-mode');
    const result = await page.evaluate(async () => {
      const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
      const queue = document.querySelector('.audio-dock-up-next');
      const samples = [...document.querySelectorAll('.ui-setup-guide h2, .ui-setup-guide p, .ui-setup-guide button, .ui-setup-guide-counter'),
        ...queue.querySelectorAll('h3, strong, .ui-muted, .ui-field-label')]
        .filter(element => element.getClientRects().length && element.textContent.trim());
      const original = [...document.querySelectorAll('.ui-setup-guide *, #audio-dock *')]
        .map(element => [element, element.style.transition]);
      original.forEach(([element]) => { element.style.transition = 'none'; });
      const canvas = document.createElement('canvas'); canvas.width = canvas.height = 1;
      const context = canvas.getContext('2d', { willReadFrequently: true }), colors = new Map();
      const rgba = color => {
        if (!colors.has(color)) {
          context.clearRect(0, 0, 1, 1); context.fillStyle = color; context.fillRect(0, 0, 1, 1);
          colors.set(color, [...context.getImageData(0, 0, 1, 1).data]);
        }
        return colors.get(color);
      };
      const background = element => {
        for (let owner = element; owner; owner = owner.parentElement) {
          const css = getComputedStyle(owner), color = css.backgroundColor;
          if (css.backgroundImage !== 'none' || css.filter !== 'none' || Number(css.opacity) !== 1)
            throw new Error('Recordings text acquired non-solid paint requiring a fresh pixel review.');
          if (rgba(color)[3] === 255) return color;
          if (rgba(color)[3] !== 0) throw new Error('Recordings text needs a solid painted owner.');
        }
        throw new Error('Recordings text has no painted owner.');
      };
      const luminance = color => rgba(color).slice(0, 3).reduce((total, channel, index) => {
        const value = channel / 255;
        return total + (value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4) * [.2126, .7152, .0722][index];
      }, 0);
      const contrast = (front, back) => {
        const values = [luminance(front), luminance(back)].sort((a, b) => b - a);
        return (values[0] + .05) / (values[1] + .05);
      };
      let worstText = { ratio: Infinity }, checked = 0;
      try {
        for (let hue = 0; hue < 360; hue++) {
          applyThemeHue(hue);
          for (const sample of samples) {
            const foreground = getComputedStyle(sample).color, behind = background(sample);
            const ratio = contrast(foreground, behind); checked++;
            if (ratio < worstText.ratio) worstText = { ratio, hue, text: sample.textContent.trim(), foreground, background: behind };
          }
        }
      } finally {
        applyThemeHue(null);
        original.forEach(([element, transition]) => { element.style.transition = transition; });
      }
      return { samples: samples.length, checked, queueRows: queue.querySelectorAll('li').length, worstText };
    });
    fs.mkdirSync(evidence, { recursive: true });
    fs.writeFileSync(path.join(evidence, `palette-${theme}-360-hues.json`), `${JSON.stringify(result, null, 2)}\n`);
    expect(result.samples).toBeGreaterThan(8);
    expect(result.queueRows).toBeGreaterThan(1);
    expect(result.checked).toBe(result.samples * 360);
    expect(result.worstText.ratio, JSON.stringify(result.worstText)).toBeGreaterThanOrEqual(4.5);
    await page.keyboard.press('Escape'); expectIsolated(fixture, effects);
  });

  test(`Recordings populated page, picker, dialogs and player retain readable paint in ${theme} and representative hues`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const { fixture, effects } = await openRecordings(page, { theme });
    const summaries = [];
    const audit = async (rootSelector, section) => {
      await page.evaluate(rootSelector => {
        window.recordingsAuditInert = [...document.querySelectorAll('[inert]')];
        window.recordingsAuditInert.forEach(element => { element.inert = false; });
        const backdrop = document.querySelector('.ui-setup-guide-backdrop');
        window.recordingsAuditBackdrop = [backdrop, backdrop.style.visibility];
        if (rootSelector !== '.ui-setup-guide') backdrop.style.visibility = 'hidden';
      }, rootSelector);
      try {
        for (const hue of [null, 0, 120, 240]) {
          await page.evaluate(async value => { (await import('/assets/core/theme.js?v=2')).applyThemeHue(value); }, hue);
          const result = await expectContrast(page, { rootSelector, recordOnly: true,
            reportName: `recordings-guide-${section}-${theme}-hue-${hue}` });
          expect(Object.values(result.violations).flat()).toEqual([]);
          // Chromium paints these native checkboxes. Keep this narrow gap in
          // the report and inspect both selected and empty boxes in screenshots.
          expect(result.skipped.filter(item => item.status === 'needs-review' && !item.reviewed)
            .filter(item => item.reason !== 'native-control-internals' || !item.selector.includes('input.recordings-picker-check')))
            .toEqual([]);
          expect(result.summary.text.checked).toBeGreaterThan(3);
          summaries.push({ section, hue, summary: result.summary, reviewedPaint: result.skipped
            .filter(item => item.status === 'needs-review').map(({ reason, paintOwner, reviewOwner, label }) =>
              ({ reason, paintOwner, reviewOwner, label })) });
          await screenshot(page, `recordings-${section}-${theme}-hue-${hue}`);
        }
      } finally {
        await page.evaluate(async () => {
          (await import('/assets/core/theme.js?v=2')).applyThemeHue(null);
          window.recordingsAuditInert.forEach(element => { element.inert = true; });
          const [backdrop, visibility] = window.recordingsAuditBackdrop; backdrop.style.visibility = visibility;
          delete window.recordingsAuditInert; delete window.recordingsAuditBackdrop;
        });
      }
    };
    await expectOverview(page); await audit('.ui-setup-guide', 'overview');
    await start(page); await audit('.recordings-library', 'actual-page');
    await goTo(page, 'targets'); await audit('.recordings-filters-modal', 'actual-picker');
    await goTo(page, 'details'); await audit('.recordings-detail-modal', 'actual-details');
    await goTo(page, 'playback'); await audit('.recordings-actions-modal', 'actual-actions');
    await goTo(page, 'click-mode'); await audit('#audio-dock', 'actual-player');
    await audit('.ui-setup-guide', 'player-guide');
    fs.writeFileSync(path.join(evidence, `contrast-summary-${theme}.json`), `${JSON.stringify(summaries, null, 2)}\n`);
    await page.keyboard.press('Escape'); expectIsolated(fixture, effects);
  });
}
