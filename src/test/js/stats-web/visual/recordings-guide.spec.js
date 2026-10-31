'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const guide = page => page.locator('.ui-setup-guide[data-guide-id="recordings"]');
const steps = ['targets-button', 'targets', 'time-range', 'search', 'transcript-search', 'transcript-submit',
  'call-card', 'actions-button', 'playback', 'details-button', 'details', 'details-close', 'player-overview', 'player-button', 'click-mode'];
const memoryKey = 'sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:recordings:6';
const evidence = process.env.RECORDINGS_GUIDE_EVIDENCE ||
  path.resolve(__dirname, '../../../../../build/playwright-results/recordings-guide-evidence');
const nonGuideStorage = page => page.evaluate(() => Object.fromEntries(Object.entries(localStorage)
  .filter(([key]) => !key.startsWith('sdrtrunk-vce-guide:'))));

function observeEffects(page) {
  const effects = { writes: [], samples: [], audio: [], sockets: [] };
  page.on('request', request => {
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/v1/')) return;
    if (!['GET', 'HEAD'].includes(request.method()) &&
        !(request.method() === 'PUT' && url.pathname === '/api/v1/me/tour-dismissals') && url.pathname !== '/api/v1/live/multiplex/control')
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
    for (const version of [1, 2, 3, 4, 5]) localStorage.setItem(
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
  const actions = page.getByRole('dialog', { name: 'Call actions', exact: true });
  await expect(picker).toHaveCount(id === 'targets' ? 1 : 0);
  await expect(actions).toHaveCount(['playback', 'details-button', 'details', 'details-close'].includes(id) ? 1 : 0);
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
  if (id === 'details-button') await expect(actions.getByRole('button', { name: 'Show more', exact: true })).toBeVisible();
  if (id === 'call-card') {
    await expect(page.locator('[data-guide-target="rec-call-card"]')).toHaveClass(/recordings-call/);
    await expect(guide(page)).toContainText(/call/i);
    await expect(guide(page)).not.toContainText('Open Call details');
  }
  if (['details', 'details-close'].includes(id)) {
    await expect(actions).toBeVisible();
    for (const tab of ['Overview', 'Transcript', 'Identities', 'Technical', 'Recording'])
      await expect(actions.getByRole('tab', { name: tab, exact: true })).toBeVisible();
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
