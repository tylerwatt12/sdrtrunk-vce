'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');
const { selectAudioPanel } = require('./audio-dock-controls.cjs');

const guide = page => page.locator('.ui-setup-guide[data-guide-id="scanner"]');
const steps = ['lists-button', 'lists-menu', 'lists', 'lists-back', 'lists-menu-close', 'call', 'queue', 'playback', 'hold', 'avoid',
  'detail-level', 'details', 'settings-button', 'settings-menu', 'playback-order', 'settings-close',
  'avoids-button', 'avoids', 'avoids-close', 'player-close'];
const memoryKey = 'sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:scanner:5';
const evidence = process.env.SCANNER_GUIDE_EVIDENCE ||
  path.resolve(__dirname, '../../../../../build/playwright-results/scanner-guide-evidence');
const nonGuideStorage = page => page.evaluate(() => Object.fromEntries(Object.entries(localStorage)
  .filter(([key]) => !key.startsWith('sdrtrunk-vce-guide:'))));

function observeEffects(page) {
  const effects = { writes: [], samples: [], audio: [], feed: [], sockets: [] };
  page.on('request', request => {
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/v1/')) return;
    if (!['GET', 'HEAD'].includes(request.method()) &&
        !(request.method() === 'PUT' && url.pathname === '/api/v1/me/tour-dismissals') && url.pathname !== '/api/v1/live/multiplex/control')
      effects.writes.push(`${request.method()} ${url.pathname}`);
    if (/guide-scanner|scanner-guide|sample-scanner|tour-scanner|b00ba000|p25:bee00:(123|456)|v1-[rg]-bee00|\b950[12]\b/i
      .test(decodeURIComponent(url.pathname + url.search + (request.postData() || ''))))
      effects.samples.push(`${request.method()} ${url.pathname}`);
    if (/\/audio(?:$|\/)/.test(url.pathname)) effects.audio.push(url.pathname);
    if (url.pathname === '/api/v1/calls/feed') effects.feed.push(url.pathname);
  });
  page.on('websocket', socket => effects.sockets.push(socket.url()));
  return effects;
}

async function openScanner(page, { theme = 'light', replay = true, firstVisit = false, denied = [] } = {}) {
  const effects = observeEffects(page);
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const fixture = await installSiteStyleApplication(page, theme, { revision: 1, tables: [] }, { guides: firstVisit });
  await page.addInitScript(({ firstVisit, key }) => {
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:welcome:1', '1');
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:scanner:4', '1');
    if (!firstVisit) localStorage.setItem(key, '1');
    window.scannerGuideAudioEvents = [];
    for (const name of ['AudioContext', 'webkitAudioContext']) {
      const Original = window[name];
      if (Original) window[name] = new Proxy(Original, { construct(target, args) {
        window.scannerGuideAudioEvents.push(name);
        return Reflect.construct(target, args);
      } });
    }
  }, { firstVisit, key: memoryKey });
  if (denied.length) {
    const file = path.resolve(__dirname, '../../../../../src/main/java/io/github/dsheirer/web/auth/WebCapability.java');
    const capabilities = Object.fromEntries([...fs.readFileSync(file, 'utf8').matchAll(/^\s+[A-Z_]+\("([^"]+)",/gm)]
      .map(([, id]) => [id, !denied.includes(id)]));
    await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
      configured: true, authenticated: true, username: 'fixture-admin', tier: 'admin', primary: true,
      csrf_token: 'fixture-csrf', guide_dismissal_scope: 'account-1-100', capabilities
    } } }));
  }
  await page.goto(`/app.html?view=scanner${replay ? '&guide=scanner' : ''}`);
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
  await expect(guide(page).locator('h2')).toHaveText('Listen to your Scan Lists');
  await expect(page.getByRole('heading', { name: 'Scanner', exact: true })).toBeVisible();
  await expect(page.locator('.ui-setup-guide-highlight, [data-guide-sample], [data-guide-example]')).toHaveCount(0);
  await expectInViewport(guide(page));
}

async function start(page) {
  await expectOverview(page);
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).click();
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'lists-button');
}

async function advance(page, id, available = steps) {
  await guide(page).getByRole('button', { name: id === available.at(-1) ? 'Finish' : 'Next', exact: true }).click();
}

async function goTo(page, destination) {
  const current = await guide(page).getAttribute('data-guide-step');
  for (const id of steps.slice(Math.max(0, steps.indexOf(current)))) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    if (id === destination) return;
    await advance(page, id);
  }
  throw new Error(`Unknown Scanner guide step: ${destination}`);
}

async function expectSpotlight(page, id) {
  const targets = page.locator(`[data-guide-target="sc-${id}"]:visible`);
  await expect(targets.first()).toBeVisible();
  for (const target of await targets.all()) {
    await expectInViewport(target);
    await expect.poll(() => target.evaluate(element => {
      const rect = element.getBoundingClientRect();
      const header = document.querySelector('.app-header')?.getBoundingClientRect();
      const card = document.querySelector('.ui-setup-guide').getBoundingClientRect();
      const highlights = [...document.querySelectorAll('.ui-setup-guide-highlight')].map(item => item.getBoundingClientRect());
      const overlap = Math.min(rect.right, card.right) - Math.max(rect.left, card.left) > 1 &&
        Math.min(rect.bottom, card.bottom) - Math.max(rect.top, card.top) > 1;
      const covered = highlights.some(box => box.left <= rect.left + 1 && box.top <= rect.top + 1 &&
        box.right >= rect.right - 1 && box.bottom >= rect.bottom - 1);
      return (!header || rect.top >= header.bottom - 1) && !overlap && covered ? null :
        { target: rect.toJSON(), headerBottom: header?.bottom, overlap, covered };
    }), { message: `sc-${id} highlights the actual control below the header and clear of the guide` }).toBeNull();
  }
  await expectInViewport(guide(page));
  await expect.poll(() => guide(page).evaluate(element => {
    const header = document.querySelector('.app-header')?.getBoundingClientRect();
    const back = [...element.querySelectorAll('button')].find(button => button.textContent.trim() === 'Back');
    const next = [...element.querySelectorAll('button')].find(button => /^(Next|Finish)/.test(button.textContent.trim()));
    const b = back?.getBoundingClientRect(), n = next?.getBoundingClientRect();
    return (!header || element.getBoundingClientRect().top >= header.bottom - 1) && b && n &&
      Math.abs(b.top - n.top) <= 1 && b.right <= n.left;
  }), { message: 'The guide clears the header and keeps navigation side by side' }).toBe(true);
}

async function expectLessonState(page, id) {
  const dock = page.locator('#audio-dock');
  const settings = dock.locator('.audio-dock-settings');
  const avoids = dock.locator('#audio-dock-panel');
  const lists = dock.locator('[data-guide-target="scan-lists-player-lists"], [data-guide-target="sc-lists"]');
  const panelTitles = { 'lists-menu': 'Player menu', lists: 'Lists', 'lists-back': 'Lists',
    'lists-menu-close': 'Player menu', 'settings-menu': 'Player menu', 'playback-order': 'Listening settings',
    'settings-close': 'Listening settings', 'avoids-button': 'Player menu', avoids: 'Avoid List',
    'avoids-close': 'Avoid List', 'player-close': 'Player menu' };
  const panelTitle = panelTitles[id];
  if (panelTitle) {
    await expect(dock).toBeVisible();
    await expect(dock.locator('#audio-dock-context-title')).toHaveText(panelTitle);
    await expectInViewport(dock);
    expect(await dock.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
  } else await expect(dock).toBeHidden();
  await expect(page.locator('.scanner-scan-lists, .scanner-settings-modal')).toHaveCount(0);
  const listOpen = ['lists', 'lists-back'].includes(id);
  if (listOpen) {
    if (id === 'lists') {
      await expect(guide(page)).toContainText('Select one or more Scan Lists to listen to their calls together.');
      await expect(guide(page)).not.toContainText('County Sheriff');
    }
    await expect(lists).toBeVisible();
    await expect(lists.getByRole('checkbox', { name: /^Law Enforcement(?:\s|$)/ })).toBeChecked();
    await expect(lists.getByRole('checkbox', { name: /^Fire\/EMS(?:\s|$)/ })).toBeChecked();
    await expect(page.locator('[data-guide-example="scanner"]')).toBeVisible();
  } else await expect(lists).toBeHidden();
  await expect(settings).toHaveCount(['playback-order', 'settings-close'].includes(id) ? 1 : 0);
  if (['playback-order', 'settings-close'].includes(id)) {
    await expect(settings).toBeVisible();
    await expect.poll(() => settings.evaluate(element => {
      const target = element.getBoundingClientRect();
      const coach = document.querySelector('.ui-setup-guide').getBoundingClientRect();
      return Math.min(target.right, coach.right) <= Math.max(target.left, coach.left) + 1 ||
        Math.min(target.bottom, coach.bottom) <= Math.max(target.top, coach.top) + 1;
    })).toBe(true);
    await expect(settings.getByRole('checkbox', { name: 'Group calls by target', exact: true })).toBeVisible();
    await expect(settings.getByRole('spinbutton', { name: 'Calls per target', exact: true })).toBeVisible();
    await expect(dock.locator('#audio-dock-context-back')).toHaveAccessibleName('Back to menu');
    await expect(page.locator('.scanner-call-title')).toHaveText('Regional Fire');
  }
  const afterAvoid = steps.indexOf(id) >= steps.indexOf('detail-level');
  await expect(page.locator('.scanner-call-title')).toHaveText(afterAvoid ? 'Regional Fire' : 'Sheriff Dispatch');
  if (id === 'call') {
    await expect(page.locator('.scanner-source-row')).toContainText('Sheriff 214');
    await expect(page.locator('.scanner-call-subtitle')).toContainText('County P25');
    await expect(page.locator('.scanner-field-grid')).toContainText('Frequency');
    const spectrum = page.getByRole('img', { name: 'Voice frequency spectrum', exact: true });
    await expect(spectrum.locator(':scope > i')).toHaveCount(64);
    const bars = await spectrum.locator(':scope > i').evaluateAll(elements => elements.map(element => element.style.height));
    expect(new Set(bars).size).toBeGreaterThan(4);
    // The application fixture freezes Date; advance its observation clock
    // deterministically so the production spectrum receives a second frame.
    await page.clock.setFixedTime(new Date((await page.evaluate(() => Date.now())) + 140));
    await expect.poll(() => spectrum.locator(':scope > i').evaluateAll(elements => elements.map(element => element.style.height)))
      .not.toEqual(bars);
  }
  if (id === 'queue') {
    await expect(guide(page)).toContainText('When several calls arrive together, VCE reorders them in queue so you hear them in turn.');
    const queue = page.locator('.scanner-up-next-list > li[data-ui-hint]');
    await expect(queue).toHaveCount(4);
    await expect(queue.nth(0)).toContainText('Regional Fire');
    await expect(queue.nth(0)).toContainText('Engine 7');
    await expect(queue.nth(1)).toContainText('Regional Fire');
    await expect(queue.nth(1)).toContainText('Engine 8');
    await expect(queue.nth(2)).toContainText(/Police Department|City Police/);
    await expect(queue.nth(3)).toContainText('City Fire');
  }
  if (id === 'playback') {
    await expect(guide(page)).toContainText('Pause audio while calls continue to queue.');
    for (const selector of ['#scanner-play', '#scanner-stop', '#scanner-replay']) await expect(page.locator(selector)).toBeEnabled();
  }
  if (id === 'hold') {
    await expect(page.locator('#scanner-hold')).toHaveText('Hd');
    await expect(page.locator('#scanner-hold')).toHaveAttribute('aria-pressed', 'false');
    await expect(guide(page)).toContainText('Select Hold to hear only the current talkgroup or channel. Select Hold again to resume your Scan Lists.');
    await expect(guide(page)).not.toContainText('H stands for');
  }
  if (id === 'avoid') await expect(page.locator('#scanner-avoid')).toBeEnabled();
  if (id === 'detail-level') await expect(page.getByRole('combobox', { name: 'Scanner detail level', exact: true })).toHaveValue('normal');
  if (steps.indexOf(id) >= steps.indexOf('details'))
    await expect(page.getByRole('combobox', { name: 'Scanner detail level', exact: true })).toHaveValue('advanced');
  if (id === 'details') {
    await expect(page.locator('.scanner-participant-grid')).toContainText('Target');
    await expect(page.locator('.scanner-participant-grid')).toContainText('Source');
    await expect(page.locator('.scanner-display button.link-button, .scanner-display button.scanner-field-link').first()).toBeVisible();
  }
  if (['lists-button', 'settings-button'].includes(id)) {
    await expect(page.locator('#scanner-player-menu')).toBeVisible();
    await expect(page.locator('#scanner-player-menu')).toHaveAccessibleName('Player menu');
    if (id === 'settings-button') await expect(guide(page)).toContainText('Open Player menu to adjust playback order and page title options.');
  }
  if (id === 'playback-order') await expect(guide(page)).toContainText('Group queued calls by talkgroup or channel and set how many play before switching.');
  if (id === 'avoids-button') {
    const item = dock.getByRole('button', { name: 'Avoid List', exact: true });
    await expect(item).toContainText('1');
  }
  if (id === 'avoids') {
    await expect(avoids).toBeVisible();
    await expect(avoids).toContainText('Sheriff Dispatch');
    await expect(avoids.getByRole('button', { name: 'Remove Sheriff Dispatch · 1001 from Avoid List', exact: true })).toBeVisible();
  }
  if (id === 'avoids-close') {
    await expect(avoids).toBeVisible();
    await expect(avoids).toContainText('No avoided targets');
    await expect(avoids.getByRole('button', { name: /^Remove .* from Avoid List$/ })).toHaveCount(0);
  }
  if (['lists-back', 'settings-close', 'avoids-close'].includes(id)) {
    await expect(dock.locator('#audio-dock-context-back')).toHaveAccessibleName('Back to menu');
  }
  if (['lists-menu-close', 'player-close'].includes(id)) {
    await expect(dock.locator('#audio-dock-context-back')).toHaveAccessibleName('Back to Scanner');
  }
}

async function expectIsolated(page, fixture, effects) {
  expect(effects.writes).toEqual([]);
  expect(effects.samples).toEqual([]);
  expect(effects.audio).toEqual([]);
  expect(effects.feed).toEqual([]);
  expect(effects.sockets).toEqual([]);
  expect(await page.evaluate(() => window.scannerGuideAudioEvents)).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
}

async function screenshot(page, name) {
  fs.mkdirSync(path.join(evidence, 'screens'), { recursive: true });
  await page.screenshot({ path: path.join(evidence, 'screens', `${name}.png`) });
}

for (const { theme, width, height } of [{ theme: 'light', width: 390, height: 844 }]) {
  test(`normal Scanner listening settings fits and returns through Player menu on a ${width}px phone`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    const { fixture, effects } = await openScanner(page, { theme, replay: false });
    const dock = page.locator('#audio-dock');
    await selectAudioPanel(dock, 'Listening settings');
    const settings = dock.locator('.audio-dock-settings');
    await expectInViewport(dock);
    await screenshot(page, `scanner-normal-settings-${theme}-${width}`);
    const titleToggle = settings.getByRole('checkbox', { name: 'Playing call in page title', exact: true });
    await titleToggle.scrollIntoViewIfNeeded();
    await expect(titleToggle).toBeInViewport({ ratio: 1 });
    await dock.locator('#audio-dock-context-back').click();
    await expect(settings).toHaveCount(0);
    await expect(dock.locator('#audio-dock-context-title')).toHaveText('Player menu');
    await dock.locator('#audio-dock-context-back').click();
    await expect(dock).toBeHidden();
    await expect(page.locator('#scanner-player-menu')).toBeFocused();
    await expectIsolated(page, fixture, effects);
  });
}

test('Scanner sample controls cannot start audio, fetch calls, save settings, or navigate to example identities', async ({ page }) => {
  const { fixture, effects } = await openScanner(page);
  await start(page);
  const storage = await nonGuideStorage(page), url = page.url();
  let exercised = 0;
  for (const id of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    await expectSpotlight(page, id);
    exercised += await page.evaluate(() => {
      const roots = [...document.querySelectorAll('[data-guide-sample]')];
      const controls = [...new Set(roots.flatMap(root => [...root.querySelectorAll('button, a, input, select, form, summary')]))];
      for (const control of controls) {
        control.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
        if (control.tagName === 'FORM') control.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      }
      return controls.length;
    });
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    await expect(page).toHaveURL(url);
    await expectLessonState(page, id);
    await advance(page, id);
  }
  expect(exercised).toBeGreaterThan(75);
  expect(await nonGuideStorage(page)).toEqual(storage);
  await expectIsolated(page, fixture, effects);
});

test('Scanner restores a paused original call, Engineer view, volume and shared Lists panel after the tour', async ({ page }) => {
  const effects = observeEffects(page), errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.addInitScript(() => localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-listener:audio-fixture-account:scanner:5', '1'));
  const fixture = await openAudioApp(page, { view: 'scanner', volume: .35 });
  await page.locator('#scanner-play').click();
  await expect(page.locator('.scanner-call-title')).toHaveText('Fire Dispatch');
  await page.locator('#scanner-play').click();
  await expect(page.locator('#scanner-play')).toHaveAttribute('aria-label', 'Resume browser call audio');
  await page.getByRole('combobox', { name: 'Scanner detail level', exact: true }).selectOption('engineer');
  await expect.poll(() => fixture.preferenceWrites.length).toBeGreaterThan(0);
  const dock = page.locator('#audio-dock');
  await selectAudioPanel(dock, 'Lists');
  await expect(dock).toBeVisible();
  await page.evaluate(() => {
    const content = document.getElementById('content');
    window.originalPausedScanner = { nodes: [...content.childNodes], page: content.querySelector('.scanner-page'),
      mode: content.querySelector('#scanner-detail-mode') };
  });
  await page.evaluate(() => window.scrollTo({ top: 100, behavior: 'instant' }));
  const originalScroll = await page.evaluate(() => [scrollX, scrollY]);
  expect(originalScroll[1]).toBeGreaterThan(0);
  const storage = await nonGuideStorage(page), writes = fixture.preferenceWrites.length;
  const selectedLists = await dock.locator('#audio-dock-panel input[type=checkbox]').evaluateAll(inputs =>
    inputs.map(input => [input.getAttribute('aria-label'), input.checked]));
  const audioBefore = await page.evaluate(() => ({ contexts: window.audioTest.contexts.length,
    sources: window.audioTest.liveSources.length, recordings: window.audioTest.recordings.length }));
  await page.locator('#page-guide-button').evaluate(button => { button.focus({ preventScroll: true }); button.click(); });
  await expectOverview(page);
  await expect(dock.locator('#audio-dock-context-title')).toHaveText('Lists');
  await start(page);
  await goTo(page, 'playback-order');
  await page.keyboard.press('Escape');
  await expect(guide(page)).toHaveCount(0);
  expect(await page.locator('#content').evaluate(element => {
    const original = window.originalPausedScanner;
    return element.querySelector('.scanner-page') === original.page &&
      element.querySelector('#scanner-detail-mode') === original.mode &&
      original.nodes.every((node, index) => element.childNodes[index] === node);
  })).toBe(true);
  await expect(page.locator('.scanner-call-title')).toHaveText('Fire Dispatch');
  await expect(page.getByRole('combobox', { name: 'Scanner detail level', exact: true })).toHaveValue('engineer');
  await expect(page.locator('#scanner-volume')).toHaveValue('0.35');
  await expect(page.locator('#scanner-play')).toHaveAttribute('aria-label', 'Resume browser call audio');
  await expect(dock).toBeVisible();
  await expect(dock.locator('#audio-dock-context-title')).toHaveText('Lists');
  expect(await dock.locator('#audio-dock-panel input[type=checkbox]').evaluateAll(inputs =>
    inputs.map(input => [input.getAttribute('aria-label'), input.checked]))).toEqual(selectedLists);
  expect(await page.evaluate(() => [scrollX, scrollY])).toEqual(originalScroll);
  await dock.locator('#audio-dock-context-back').click();
  await expect(dock.locator('#audio-dock-context-title')).toHaveText('Player menu');
  await dock.locator('#audio-dock-context-back').click();
  await expect(dock).toBeHidden();
  await expect(page.locator('#scanner-player-menu')).toBeFocused();
  expect(await nonGuideStorage(page)).toEqual(storage);
  expect(fixture.preferenceWrites.length).toBe(writes);
  expect(await page.evaluate(() => ({ contexts: window.audioTest.contexts.length,
    sources: window.audioTest.liveSources.length, recordings: window.audioTest.recordings.length }))).toEqual(audioBefore);
  expect(effects.samples).toEqual([]);
  expect(errors).toEqual([]);
});

test('Scanner Help restores the original shared listening settings without changing preferences', async ({ page }) => {
  const { fixture, effects } = await openScanner(page, { replay: false });
  const dock = page.locator('#audio-dock');
  await selectAudioPanel(dock, 'Listening settings');
  const settings = dock.locator('.audio-dock-settings');
  const grouping = settings.getByRole('checkbox', { name: 'Group calls by target', exact: true });
  const checked = await grouping.isChecked();
  const burst = await settings.getByRole('spinbutton', { name: 'Calls per target', exact: true }).inputValue();
  await page.locator('#page-guide-button').evaluate(button => button.click());
  await expectOverview(page);
  await start(page);
  await goTo(page, 'avoids');
  await page.keyboard.press('Escape');
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], [data-guide-example]')).toHaveCount(0);
  await expect(settings).toBeVisible();
  await expect(dock.locator('#audio-dock-context-title')).toHaveText('Listening settings');
  expect(await grouping.isChecked()).toBe(checked);
  await expect(settings.getByRole('spinbutton', { name: 'Calls per target', exact: true })).toHaveValue(burst);
  await expectIsolated(page, fixture, effects);
});

test('Scanner navigation and early cancellation release sample calls and dialogs', async ({ page }) => {
  const { fixture, effects } = await openScanner(page, { replay: false });
  await page.locator('#page-guide-button').click(); await expectOverview(page);
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).evaluate(button => {
    button.click(); document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
  });
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], [data-guide-example]')).toHaveCount(0);
  await page.locator('#page-guide-button').click(); await start(page); await goTo(page, 'avoids');
  await page.locator('.primary-nav [data-view="dashboard"]').evaluate(link => link.click());
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], [data-guide-example], .scanner-list-modal')).toHaveCount(0);
  await page.locator('.primary-nav [data-view="scanner"]').evaluate(link => link.click());
  await expect(page.locator('.scanner-display')).toHaveClass(/\bis-idle\b/);
  await expectIsolated(page, fixture, effects);
});
