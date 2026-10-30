'use strict';

const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { openAudioApp, liveCall, recording } = require('./fixtures/audio-dock-app.cjs');
const { selectAudioPanel } = require('./audio-dock-controls.cjs');

async function openScannerPanel(page, name) {
  const dock = page.locator('#audio-dock');
  await selectAudioPanel(dock, name);
  return dock.locator('#audio-dock-panel');
}

async function clearScannerQueue(page) {
  await page.locator('#scanner-player-menu').click();
  const menu = page.locator('#audio-dock-panel');
  await expect(page.locator('#audio-dock-context-title')).toHaveText('Player menu');
  await menu.getByRole('button', { name: 'Clear queue', exact: true }).click();
  if (await menu.isVisible()) await page.keyboard.press('Escape');
  await expect(page.locator('#audio-dock')).toBeHidden();
}

async function expectScannerTransportOrder(page) {
  const bar = page.locator('#scanner-playback-bar');
  await expect(bar.locator('.ui-audio-transport-main > button:visible')).toHaveCount(4);
  expect(await bar.locator('.ui-audio-transport-main > button:visible').evaluateAll(buttons =>
    buttons.map(button => button.id))).toEqual(['scanner-replay', 'scanner-play', 'scanner-skip', 'scanner-stop']);
  expect(await bar.locator('.ui-audio-transport-options > button:visible').evaluateAll(buttons =>
    buttons.map(button => button.id))).toEqual(['scanner-hold', 'scanner-avoid', 'scanner-player-menu', 'scanner-volume-button']);
  await expect(bar.locator('#scanner-hold')).toHaveText('Hd');
  await expect(bar.locator('#scanner-avoid')).toHaveText('Av');
  await expect(bar.locator('#scanner-play')).toHaveClass(/ui-button-primary/);
  await expect(bar.locator('#scanner-clear-queue')).toHaveCount(0);
  const geometry = await bar.evaluate(element => {
    const timing = element.querySelector('.ui-audio-timing').getBoundingClientRect();
    const transport = element.querySelector('.ui-audio-transport').getBoundingClientRect();
    const display = document.querySelector('.scanner-display').getBoundingClientRect();
    return { timingBottom: timing.bottom, transportTop: transport.top, displayBottom: display.bottom, barTop: element.getBoundingClientRect().top };
  });
  expect(geometry.timingBottom).toBeLessThanOrEqual(geometry.transportTop);
  expect(geometry.displayBottom).toBeLessThanOrEqual(geometry.barTop);
}

async function expectFullWidthScannerActions(page) {
  await expectScannerTransportOrder(page);
  const bar = page.locator('#scanner-playback-bar');
  await bar.scrollIntoViewIfNeeded();
  const rows = [];
  for (const selector of ['.ui-audio-transport-main', '.ui-audio-transport-options']) {
    const controls = bar.locator(selector);
    const row = await controls.boundingBox();
    rows.push(row);
    const buttons = await controls.locator(':scope > button:visible').evaluateAll(elements =>
      elements.map(element => {
        element.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
        const box = element.getBoundingClientRect();
        const host = element.parentElement.getBoundingClientRect();
        const hit = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
        return { left: box.left, right: box.right, top: box.top, bottom: box.bottom,
          width: box.width, height: box.height, hostLeft: host.left, hostRight: host.right,
          reachable: Boolean(hit && element.contains(hit)) };
      }));
    expect(buttons).toHaveLength(4);
    for (const button of buttons) {
      expect(button.left).toBeGreaterThanOrEqual(button.hostLeft - 1);
      expect(button.right).toBeLessThanOrEqual(button.hostRight + 1);
      expect(button.width).toBeGreaterThanOrEqual(40);
      expect(button.height).toBeGreaterThanOrEqual(40);
      expect(button.reachable).toBe(true);
    }
    for (let index = 1; index < buttons.length; index++) {
      const previous = buttons[index - 1], next = buttons[index];
      if (Math.abs(next.top - previous.top) < 1) expect(next.left - previous.right).toBeGreaterThanOrEqual(7);
      else expect(next.top).toBeGreaterThanOrEqual(previous.bottom + 7);
    }
    if (selector === '.ui-audio-transport-main') {
      expect(buttons[0].left).toBeCloseTo(buttons[0].hostLeft, 1);
      expect(buttons.at(-1).right).toBeCloseTo(buttons.at(-1).hostRight, 1);
      for (const button of buttons) expect(button.width).toBeCloseTo((row.width - 3 * 8) / 4, 1);
    }
  }
  expect(rows[1].y).toBeGreaterThanOrEqual(rows[0].y + rows[0].height + 7);
}

async function expectCompactDesktopScannerActions(page) {
  await expectScannerTransportOrder(page);
  const primary = page.locator('#scanner-playback-bar .ui-audio-transport-main > button:visible');
  const widths = await primary.evaluateAll(buttons => buttons.map(button => button.getBoundingClientRect().width));
  expect(widths).toHaveLength(4);
  for (const width of widths) expect(width).toBeGreaterThanOrEqual(40);
  for (const width of widths) expect(width).toBeCloseTo(widths[0], 1);
  const utilities = page.locator('#scanner-playback-bar .ui-audio-transport-options > button:visible');
  for (const button of await utilities.all()) {
    const bounds = await button.boundingBox();
    expect(bounds.width).toBeGreaterThanOrEqual(40);
    expect(bounds.height).toBeGreaterThanOrEqual(40);
  }
  const bar = await page.locator('#scanner-playback-bar').boundingBox();
  const display = await page.locator('.scanner-display').boundingBox();
  expect(display.y + display.height).toBeLessThanOrEqual(bar.y);
}

async function activeSourceCount(page) {
  return page.evaluate(() => window.audioTest.liveSources.filter(source => source.active).length);
}

async function completeCurrentCall(page) {
  await page.evaluate(() => {
    const source = window.audioTest.liveSources.find(value => value.active);
    if (!source?.onended) throw new Error('Expected an active fixture call to complete.');
    source.active = false;
    source.onended();
  });
}

async function expectQueueTailReachable(list, calls) {
  await list.focus();
  await list.press('End');
  await expect.poll(() => list.evaluate(element => element.scrollTop)).toBeGreaterThan(0);
  const last = calls.last();
  await expect(last).toBeInViewport({ ratio: 1 });
  const bounds = await list.boundingBox();
  const tail = await last.boundingBox();
  expect(tail.y).toBeGreaterThanOrEqual(bounds.y);
  expect(tail.y + tail.height).toBeLessThanOrEqual(bounds.y + bounds.height + 1);
  expect(await last.evaluate(element => {
    const box = element.getBoundingClientRect();
    const hit = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
    return Boolean(hit && element.contains(hit));
  })).toBe(true);
}

async function expectPhoneQueueFits(page, list) {
  await list.scrollIntoViewIfNeeded();
  await expect.poll(async () => {
    const bounds = await list.boundingBox();
    const viewportHeight = await page.evaluate(() => window.innerHeight);
    return Boolean(bounds && bounds.y >= 0 && bounds.y + bounds.height <= viewportHeight + 1);
  }).toBe(true);
}

test('Scanner progress follows decoded playback position and stays still while paused', async ({ page }) => {
  await page.clock.install();
  await openAudioApp(page, { view: 'scanner' });
  const primary = page.locator('#scanner-play');
  const timing = page.locator('#scanner-playback-bar .ui-audio-timing');
  const progress = timing.locator('progress');
  await primary.click();
  await expect(page.locator('.scanner-call-title')).toHaveText('Fire Dispatch');
  await expect(progress).toBeVisible();
  await page.evaluate(() => { window.audioTest.contexts.at(-1).currentTime = 8; });
  await page.clock.runFor(260);
  await expect(timing.locator('.ui-audio-time').first()).toHaveText('00:08');
  await expect(timing.locator('.ui-audio-time').last()).toHaveText('00:42');
  await expect.poll(() => progress.evaluate(element => element.value / element.max)).toBeCloseTo(8 / 42, 5);
  const sources = await page.evaluate(() => window.audioTest.liveSources.length);
  await primary.click();
  await expect(primary).toHaveAttribute('aria-label', 'Resume live audio');
  expect(await page.evaluate(() => window.audioTest.contexts.at(-1).state)).toBe('suspended');
  await page.clock.runFor(1500);
  await expect(timing.locator('.ui-audio-time').first()).toHaveText('00:08');
  expect(await progress.evaluate(element => element.value / element.max)).toBeCloseTo(8 / 42, 5);
  await primary.click();
  await page.evaluate(() => { window.audioTest.contexts.at(-1).currentTime = 13; });
  await page.clock.runFor(260);
  await expect(timing.locator('.ui-audio-time').first()).toHaveText('00:13');
  expect(await progress.evaluate(element => element.value / element.max)).toBeCloseTo(13 / 42, 5);
  expect(await page.evaluate(() => window.audioTest.liveSources.length)).toBe(sources);
  const detachedProgress = await progress.elementHandle();
  await page.locator('.brand').click();
  await expect(page.locator('body')).toHaveAttribute('data-view', 'dashboard');
  expect(await detachedProgress.evaluate(element => element.isConnected)).toBe(false);
  await page.evaluate(() => { window.audioTest.contexts.at(-1).currentTime = 20; });
  await page.clock.runFor(510);
  expect(await detachedProgress.evaluate(element => element.value / element.max)).toBeCloseTo(13 / 42, 5);
});

test('Scanner progress waits for decoded duration without inventing a percentage or total', async ({ page }) => {
  await openAudioApp(page, { view: 'scanner' });
  await page.evaluate(() => {
    window.AudioContext.prototype.decodeAudioData = () => new Promise(resolve => {
      window.audioTest.completeDecode = resolve;
    });
  });
  await page.locator('#scanner-play').click();
  const timing = page.locator('#scanner-playback-bar .ui-audio-timing');
  await expect(timing).toContainText('Duration unavailable');
  await expect(timing.locator('progress')).toBeHidden();
  await expect(timing).not.toContainText('00:42');
  await expect(timing).not.toContainText('%');
  await expect.poll(() => page.evaluate(() => typeof window.audioTest.completeDecode)).toBe('function');
  await page.evaluate(() => window.audioTest.completeDecode({ duration: 42, sampleRate: 16000 }));
  await expect(timing.locator('progress')).toBeVisible();
  await expect(timing.locator('.ui-audio-time').last()).toHaveText('00:42');
  await expect(timing).not.toContainText('Duration unavailable');
});

test('Scanner and expanded dock share transport order while recordings keep their own actions', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openAudioApp(page, { view: 'scanner', feedCallsEnabled: false });
  await expectScannerTransportOrder(page);
  const scanner = page.locator('#scanner-playback-bar');
  const snapshot = async transport => transport.locator('.ui-audio-transport-main > button:visible').evaluateAll(buttons =>
    buttons.map(button => ({ icon: button.querySelector('use').getAttribute('href'),
      primary: button.classList.contains('ui-button-primary'),
      height: button.getBoundingClientRect().height,
      shape: getComputedStyle(button).borderRadius })));
  const scannerCommands = await snapshot(scanner);
  expect(scannerCommands.map(button => button.icon)).toEqual(['#icon-replay', '#icon-play', '#icon-skip', '#icon-stop']);
  await page.locator('.brand').click();
  const dock = page.locator('#audio-dock');
  await dock.locator('#audio-dock-size-handle').press('End');
  await expect(dock).toHaveAttribute('data-state', 'full');
  expect(await snapshot(dock)).toEqual(scannerCommands);
  await expect(dock.locator('.ui-audio-transport-options > button').first()).toHaveText('Hd');
  await expect(dock.locator('.ui-audio-transport-options > button').nth(1)).toHaveText('Av');
  await dock.getByRole('combobox', { name: 'Audio source', exact: true }).selectOption('recordings');
  expect((await snapshot(dock)).map(button => button.icon)).toEqual([
    '#icon-previous', '#icon-rewind', '#icon-play', '#icon-forward', '#icon-skip', '#icon-stop'
  ]);
  await expect(dock.getByRole('button', { name: 'Hold current target', exact: true })).toBeHidden();
  await expect(dock.getByRole('button', { name: 'Avoid current target', exact: true })).toBeHidden();
});

for (const radio of [true, false]) {
  test(`Scanner queued talkgroup links preserve identity and Radio access ${radio}`, async ({ page }) => {
    await openAudioApp(page, { view: 'scanner', feedCallsEnabled: false, capabilities: { radio } });
    const reference = recording.target_entity_ref;
    const otherReference = { kind: 'talkgroup', radio_system_key: 'p25:00002:002',
      identity_key: 'v1-g-00002-002-1201' };
    const calls = [reference, otherReference, null].map((target, index) => ({ ...liveCall,
      call_id: `queued-links-${index}`, target_alias: 'Dispatch', target_entity_ref: target,
      playback_target: { ...liveCall.playback_target, key: `queued-links-${index}`, label: 'Dispatch' },
      started_at_ms: liveCall.started_at_ms + index * 60_000,
      completed_at_ms: liveCall.completed_at_ms + index * 60_000 }));
    let delivered = false;
    await page.route('**/api/v1/calls/feed*', route => {
      const next = delivered ? [] : [{ ...calls[0], call_id: 'playing-call' }, ...calls];
      delivered = true;
      return route.fulfill({ json: { data: { cursor: '1', reset: false, calls: next } } });
    });
    await page.locator('.scanner-playback-primary').click();
    const queue = page.locator('.scanner-up-next-list > li[data-ui-hint]');
    await expect(queue).toHaveCount(3);
    const preview = page.locator('.scanner-up-next-preview strong');
    await expect(preview).toHaveText('Dispatch · 1201');
    if (radio) {
      for (const [index, target] of [reference, otherReference].entries()) {
        const link = queue.nth(index).getByRole('link');
        await expect(link).toHaveCount(1);
        await expect(link).toHaveText(index ? 'Dispatch · 00002.002.1201' : 'Dispatch · 1201');
        expect(Object.fromEntries(new URL(await link.getAttribute('href'), page.url()).searchParams))
          .toEqual({ view: 'group-identity', radio_system_key: target.radio_system_key,
            identity_key: target.identity_key });
      }
      await expect(preview.locator('a')).toHaveAttribute('href',
        await queue.first().getByRole('link').getAttribute('href'));
      await expect(queue.last().getByRole('link')).toHaveCount(0);
      await expect(queue.last().locator('strong')).toHaveText('Dispatch');
    } else {
      await expect(queue.getByRole('link')).toHaveCount(0);
      await expect(preview.locator('a')).toHaveCount(0);
      await expect(queue.first().locator('strong')).toHaveText('Dispatch · 1201');
    }
  });
}

for (const theme of ['light', 'dark']) {
  test(`Scanner direct actions control live playback ${theme}`, async ({ page }) => {
    const desktop = theme === 'light';
    await page.setViewportSize(desktop ? { width: 1280, height: 900 } : { width: 390, height: 844 });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await openAudioApp(page, { view: 'scanner', theme, feedCallsEnabled: false });
    const pending = [];
    let cursor = 0;
    let callNumber = 0;
    const call = (target, source) => ({ ...liveCall, call_id: `direct-${theme}-${++callNumber}`,
      playback_target: { ...liveCall.playback_target, key: `fixture:${target}`, label: target },
      target_alias: target, source_alias: source, source_id: 30914 + callNumber,
      started_at_ms: liveCall.started_at_ms + callNumber * 60_000,
      completed_at_ms: liveCall.completed_at_ms + callNumber * 60_000 });
    await page.route('**/api/v1/calls/feed*', route => route.fulfill({ json: {
      data: { cursor: String(++cursor), reset: false, calls: pending.splice(0) }
    } }));
    const primary = page.locator('.scanner-playback-primary');
    const title = page.locator('.scanner-call-title');
    const upNext = page.getByRole('complementary', { name: 'Up next', exact: true });
    const count = upNext.locator('.scanner-up-next-count');
    const disclosure = upNext.locator('.scanner-queue-disclosure');
    const summary = upNext.locator('summary');
    const teaser = upNext.locator('.scanner-up-next-preview');
    const list = upNext.locator('.scanner-up-next-list');
    const queuedCalls = list.locator(':scope > li[data-ui-hint]');
    for (const id of ['scanner-skip', 'scanner-replay', 'scanner-hold', 'scanner-stop',
      'scanner-avoid']) await expect(page.locator(`#${id}`)).toBeDisabled();
    await expect(page.locator('#scanner-player-menu')).toBeEnabled();
    if (desktop) await expectCompactDesktopScannerActions(page);
    else await expectFullWidthScannerActions(page);
    await expect(queuedCalls).toHaveCount(0);
    await expect(count).toHaveText('0 queued');
    await expect(page.locator('.scanner-queue-trigger, .scanner-queue-panel')).toHaveCount(0);

    pending.push(call('Fire Dispatch', 'Engine 4'));
    await primary.click();
    await expect(title).toHaveText('Fire Dispatch');
    await expect.poll(() => activeSourceCount(page)).toBe(1);
    const sourcesBeforePause = await page.evaluate(() => window.audioTest.liveSources.length);
    await primary.click();
    await expect(primary).toHaveAttribute('aria-label', 'Resume live audio');
    await expect.poll(() => page.evaluate(() => window.audioTest.contexts.at(-1).state)).toBe('suspended');
    pending.push(call('Fire Dispatch', 'Engine 5'), call('Police Dispatch', 'Unit 7'),
      call('Fire Dispatch', 'Engine 6'), call('Medical Dispatch', 'Medic 1'));
    pending.push(...Array.from({ length: 95 }, (_, index) =>
      call('Regional Dispatch', `Interoperability Unit ${index + 1}`)));
    await expect(count).toHaveText('99 queued');
    await expect(queuedCalls).toHaveCount(99);
    await expect(queuedCalls.nth(0)).toContainText('Engine 5');
    await expect(queuedCalls.nth(1)).toContainText('Engine 6');
    await expect(queuedCalls.nth(2)).toContainText('Unit 7');
    await expect(queuedCalls.nth(3)).toContainText('Medic 1');
    if (!desktop) {
      expect(await disclosure.evaluate(element => element.open)).toBe(false);
      await expect(list).toBeHidden();
      await expect(teaser).toBeVisible();
      await expect(teaser).toContainText('Engine 5');
      await expect(teaser).not.toContainText('Engine 6');
      await summary.press('Enter');
      await expect.poll(() => disclosure.evaluate(element => element.open)).toBe(true);
      await expectPhoneQueueFits(page, list);
    }
    await expect(list).toBeVisible();
    expect((await list.boundingBox()).height).toBeLessThanOrEqual(280);
    await expectQueueTailReachable(list, queuedCalls);
    await expect(queuedCalls.last()).toContainText('Interoperability Unit 95');
    const queueScroll = await list.evaluate(element => element.scrollTop);
    pending.push(call('Regional Dispatch', 'Interoperability Unit 96'));
    await expect(count).toHaveText('100 queued');
    await expect(queuedCalls).toHaveCount(100);
    await expect.poll(() => list.evaluate(element => element.scrollTop)).toBe(queueScroll);
    expect(await disclosure.evaluate(element => element.open)).toBe(true);
    await expectQueueTailReachable(list, queuedCalls);
    await expect(queuedCalls.last()).toContainText('Interoperability Unit 96');
    if (!desktop) {
      await page.setViewportSize({ width: 1280, height: 900 });
      expect(await disclosure.evaluate(element => element.open)).toBe(true);
      await page.setViewportSize({ width: 390, height: 844 });
      await expect.poll(() => disclosure.evaluate(element => element.open)).toBe(true);
      await summary.press('Space');
      await expect.poll(() => disclosure.evaluate(element => element.open)).toBe(false);
      await expect(list).toBeHidden();
      await expect(teaser).toContainText('Engine 5');
      await expect(summary).toBeFocused();
    }
    await primary.click();
    await expect(primary).toHaveAttribute('aria-label', 'Pause live audio');
    await expect.poll(() => page.evaluate(() => window.audioTest.contexts.at(-1).state)).toBe('running');
    expect(await page.evaluate(() => window.audioTest.liveSources.length)).toBe(sourcesBeforePause);

    await page.locator('#scanner-hold').click();
    await expect(page.locator('#scanner-hold')).toHaveAttribute('aria-pressed', 'true');
    await expect(count).toHaveText('2 queued');
    await expect(queuedCalls).toHaveCount(2);
    await expect(upNext).not.toContainText('Unit 7');
    await expect(queuedCalls.first()).toContainText('Engine 5');
    await clearScannerQueue(page);
    await expect(queuedCalls).toHaveCount(0);
    await expect(count).toHaveText('0 queued');
    await expect(upNext).toContainText('Waiting for calls');
    await expect(title).toHaveText('Fire Dispatch');
    expect(await activeSourceCount(page)).toBe(1);
    await page.locator('#scanner-hold').click();
    await expect(page.locator('#scanner-hold')).toHaveAttribute('aria-pressed', 'false');

    await completeCurrentCall(page);
    await expect.poll(() => activeSourceCount(page)).toBe(0);
    await expect(page.locator('#scanner-replay')).toBeEnabled();
    await page.locator('#scanner-replay').click();
    await expect.poll(() => activeSourceCount(page)).toBe(1);
    await expect(title).toHaveText('Fire Dispatch');
    await expect(page.locator('#scanner-hold')).toBeDisabled();
    await expect(page.locator('#scanner-avoid')).toBeDisabled();
    await page.locator('#scanner-skip').click();
    await expect.poll(() => activeSourceCount(page)).toBe(0);

    pending.push(call('Police Dispatch', 'Unit 8'), call('Police Dispatch', 'Unit 9'),
      call('Medical Dispatch', 'Medic 2'));
    await expect(title).toHaveText('Police Dispatch');
    await expect(count).toHaveText('2 queued');
    await page.locator('#scanner-avoid').click();
    await expect(title).toHaveText('Medical Dispatch');
    await expect(count).toHaveText('0 queued');
    const avoids = await openScannerPanel(page, 'Avoid List');
    await expect(avoids).toContainText('Police Dispatch');
    await avoids.getByRole('button', { name: 'Remove Police Dispatch from Avoid List', exact: true }).click();
    await expect(avoids).toContainText('No avoided targets');
    await expect(page.locator('#audio-dock-context-title + span')).toHaveText('0 avoided');
    await page.keyboard.press('Escape');

    pending.push(call('Police Dispatch', 'Unit 10'));
    await expect(count).toHaveText('1 queued');
    await expect(queuedCalls).toHaveCount(1);
    await expect(queuedCalls.first()).toContainText('Unit 10');
    await clearScannerQueue(page);
    await expect(count).toHaveText('0 queued');
    await expect(queuedCalls).toHaveCount(0);
    await expect(title).toHaveText('Medical Dispatch');
    await page.locator('#scanner-stop').click();
    await expect.poll(() => activeSourceCount(page)).toBe(0);
    await expect(primary).toHaveAttribute('aria-label', 'Listen live');
    await expect(page.locator('#scanner-stop')).toBeDisabled();
    await page.locator('#scanner-player-menu').click();
    await expect(page.locator('#audio-dock-panel').getByRole('button', { name: 'Clear queue', exact: true })).toBeDisabled();
    await page.keyboard.press('Escape');
    await page.screenshot({ path: test.info().outputPath(`scanner-direct-actions-${theme}.png`) });
    expect(errors).toEqual([]);
  });
}

for (const [theme, viewport] of [['light', { width: 1280, height: 900 }], ['dark', { width: 390, height: 844 }], ['light', { width: 320, height: 568 }], ['dark', { width: 844, height: 390 }]]) {
  test(`Scanner controls ${theme} ${viewport.width}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const fixture = await installSiteStyleApplication(page, theme);
    const defaults = (await import(pathToFileURL(path.resolve(__dirname,
      '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
    let preferences = structuredClone(defaults);
    preferences.appearance.theme = theme;
    let revision = 1;
    await page.route('**/api/v1/me/preferences', async route => {
      if (route.request().method() === 'PUT') {
        preferences = route.request().postDataJSON();
        revision += 1;
      }
      return route.fulfill({ json: { revision, preferences } });
    });
    await page.route('**/api/v1/scan-lists', route => route.fulfill({ json: { data: { scan_lists: [
      { id: 1, name: 'County Fire and Emergency Medical Services', description: 'Fire dispatch', default: true },
      { id: 2, name: 'Regional Interoperability and Emergency Operations Coordination',
        description: 'A long scan-list name remains readable in the dropdown', default: false }
    ] } } }));
    await page.goto('/app.html?view=scanner');
    const modes = page.getByRole('combobox', { name: 'Scanner detail level', exact: true });
    await expect(modes).toHaveValue('normal');
    await expect(modes.locator('option')).toHaveText(['Simple', 'Normal', 'Advanced', 'Engineer']);
    await expect(page.locator('.scanner-playback-primary')).toBeVisible();
    for (const id of ['scanner-skip', 'scanner-replay', 'scanner-hold', 'scanner-stop',
      'scanner-avoid', 'scanner-player-menu', 'scanner-volume-button']) {
      await expect(page.locator(`#${id}`)).toBeVisible();
    }
    await expect(page.locator('.scanner-queue-trigger, .scanner-queue-panel')).toHaveCount(0);
    await expect(page.locator('.scanner-playback-more-trigger')).toHaveCount(0);
    await expect(page.locator('#desktop-playback-slot #playback-volume')).toHaveCount(1);
    await expect(page.locator('#desktop-playback-slot #playback-skip')).toHaveCount(1);
    const transport = await page.locator('#scanner-playback-bar').boundingBox();
    const callStage = await page.locator('.scanner-display').boundingBox();
    expect(callStage.y + callStage.height).toBeLessThanOrEqual(transport.y);
    expect(await page.locator('#scanner-playback-bar').evaluate(element => getComputedStyle(element).position)).toBe('static');
    if (viewport.width >= 1181) await expectCompactDesktopScannerActions(page);
    await page.screenshot({ path: test.info().outputPath(`scanner-${theme}-${viewport.width}.png`), fullPage: true });
    await expect(page.locator('.scanner-scan-rail')).toHaveCount(0);
    const lists = await openScannerPanel(page, 'Lists');
    await expect(lists).toBeVisible();
    const checkboxes = lists.locator('input[id^="audio-dock-scan-list-"]');
    expect(await checkboxes.count()).toBeGreaterThan(1);
    const first = checkboxes.first();
    const before = await first.isChecked();
    await first.press('Space');
    await expect(first).toBeChecked({ checked: !before });
    await expect(lists).toBeVisible();
    await first.press('Escape');
    await expect(lists).toBeHidden();
    await expect.poll(() => preferences.playback.selected_scan_list_ids.includes(1)).toBe(!before);
    await page.goto('/app.html?view=scanner');
    await openScannerPanel(page, 'Lists');
    await expect(lists.locator('input[id^="audio-dock-scan-list-"]').first()).toBeChecked({ checked: !before });
    await page.keyboard.press('Escape');
    const primary = page.locator('.scanner-playback-primary');
    await primary.click();
    await expect(primary).toHaveAttribute('aria-label', 'Pause live audio');
    await primary.click();
    await expect(primary).toHaveAttribute('aria-label', 'Resume live audio');
    await primary.click();
    await expect(primary).toHaveAttribute('aria-label', 'Pause live audio');
    const disclosure = page.locator('.scanner-queue-disclosure');
    const summary = disclosure.locator('summary');
    const queueList = disclosure.locator('.scanner-up-next-list');
    await expect(page.getByRole('dialog', { name: 'Queued calls', exact: true })).toHaveCount(0);
    await expect(page.locator('.scanner-up-next-count')).toHaveText('0 queued');
    if (viewport.width <= 680) {
      expect(await disclosure.evaluate(element => element.open)).toBe(false);
      await expect(queueList).toBeHidden();
      await summary.press('Enter');
      await expect.poll(() => disclosure.evaluate(element => element.open)).toBe(true);
      await expect(queueList).toBeVisible();
      await expectPhoneQueueFits(page, queueList);
      await summary.press('Tab');
      await expect(queueList).toBeFocused();
      await summary.press('Space');
      await expect.poll(() => disclosure.evaluate(element => element.open)).toBe(false);
      await expect(queueList).toBeHidden();
      await expect(summary).toBeFocused();
    } else {
      expect(await disclosure.evaluate(element => element.open)).toBe(true);
      await expect(queueList).toBeVisible();
    }
    await modes.selectOption('engineer');
    await expect(page.locator('.scanner-page')).toHaveAttribute('data-scanner-mode', 'engineer');
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    const scrollY = await page.evaluate(() => window.scrollY);
    await modes.evaluate(select => {
      select.value = 'simple';
      select.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await expect(page.locator('.scanner-page')).toHaveAttribute('data-scanner-mode', 'simple');
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(scrollY);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width);
    if (viewport.width <= 680) {
      await expectFullWidthScannerActions(page);
      expect(await page.locator('.scanner-page').evaluate(element =>
        parseFloat(getComputedStyle(element).paddingBottom))).toBe(0);
    }
    const globalSkip = await page.locator('#playback-skip').elementHandle();
    await page.locator('.brand').click();
    await expect(page.locator('body')).toHaveAttribute('data-view', 'dashboard');
    await expect(page.locator('#desktop-playback-slot #playback-skip')).toHaveCount(1);
    await expect(page.locator('#desktop-playback-slot #playback-volume')).toHaveCount(1);
    await expect(page.locator('.scanner-playback-transport')).toHaveCount(0);
    expect(await globalSkip.evaluate(element => element.isConnected)).toBe(true);
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('Scanner scan-list loading, retry and selection limit', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const fixture = await installSiteStyleApplication(page, 'light');
  const defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
  const preferences = structuredClone(defaults);
  preferences.playback.selected_scan_list_ids = Array.from({ length: 16 }, (_, index) => index + 1);
  await page.route('**/api/v1/me/preferences', route => route.fulfill({ json: { revision: 1, preferences } }));
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  let reads = 0;
  await page.route('**/api/v1/scan-lists', async route => {
    reads += 1;
    if (reads === 1) {
      await gate;
      return route.fulfill({ status: 503, json: { error: 'temporarily unavailable' } });
    }
    return route.fulfill({ json: { data: { scan_lists: Array.from({ length: 17 }, (_, index) => ({
      id: index + 1, name: `Scan list ${index + 1}`, default: index === 0, description: ''
    })) } } });
  });
  await page.goto('/app.html?view=scanner');
  const lists = await openScannerPanel(page, 'Lists');
  await expect(lists.getByRole('status')).toHaveText('Lists are loading…');
  release();
  await expect(lists.getByRole('status')).toHaveText('Unable to load lists. Try again.');
  await lists.getByRole('button', { name: 'Retry scan lists', exact: true }).click();
  await expect(lists.locator('input[id^="audio-dock-scan-list-"]')).toHaveCount(17);
  await expect(lists.getByRole('checkbox', { name: 'Scan list 17', exact: true })).toBeDisabled();
  await expect(lists.getByRole('checkbox', { name: 'Scan list 1', exact: true })).toBeEnabled();
  expect(fixture.pageErrors).toEqual([]);
});

for (const theme of ['light']) {
  for (const width of [320, 390]) {
    test(`Scanner shared volume popup ${theme} ${width}`, async ({ page }) => {
      await page.setViewportSize({ width, height: width === 320 ? 568 : 844 });
      const state = await openAudioApp(page, { view: 'scanner', theme, volume: 0.75, feedCallsEnabled: false });
      const trigger = page.locator('#scanner-volume-button');
      const popup = page.locator('#scanner-volume-popover');
      const volume = popup.getByRole('slider', { name: 'Volume', exact: true });
      const output = popup.locator('output');
      const globalInput = await page.locator('#playback-volume').elementHandle();
      const globalInputStyle = await globalInput.evaluate(element => ({
        classes: element.className, parentClasses: element.parentElement.className
      }));
      await expect(trigger).toHaveText('75%');
      await expect(trigger).toHaveClass(/ui-audio-volume-trigger/);
      await expectFullWidthScannerActions(page);
      await trigger.click();
      await expect(trigger).toHaveAttribute('aria-expanded', 'true');
      await expect(volume).toBeFocused();
      await expect(output).toHaveText('75%');
      const bounds = await volume.boundingBox();
      await volume.click({ position: { x: bounds.width / 2, y: bounds.height / 2 } });
      await expect(volume).toHaveValue('0.5');
      await expect(output).toHaveText('50%');
      await volume.press('ArrowRight');
      await expect(volume).toHaveValue('0.55');
      await expect(output).toHaveText('55%');
      await expect(trigger).toHaveText('55%');
      await expect(volume).toHaveAttribute('aria-valuetext', '55 percent');
      await expect.poll(() => state.preferenceWrites.at(-1)?.playback?.volume).toBe(0.55);
      await volume.press('Escape');
      await expect(popup).toBeHidden();
      await expect(trigger).toBeFocused();
      await page.reload();
      await expect(trigger).toHaveText('55%');
      await trigger.click();
      await expect(volume).toHaveValue('0.55');
      await expect(output).toHaveText('55%');
      const reloadedGlobalInput = await page.locator('#playback-volume').elementHandle();
      const input = await volume.elementHandle();
      await page.keyboard.press('Escape');
      await page.screenshot({ path: test.info().outputPath(`scanner-proportions-${theme}-${width}.png`) });
      await trigger.click();
      await page.locator('.brand').click();
      await expect(page.locator('body')).toHaveAttribute('data-view', 'dashboard');
      expect(await input.evaluate(element => element.isConnected)).toBe(false);
      expect(await reloadedGlobalInput.evaluate(element => element.isConnected)).toBe(true);
      expect(await reloadedGlobalInput.evaluate(element => ({
        classes: element.className, parentClasses: element.parentElement.className
      }))).toEqual(globalInputStyle);
      expect(await reloadedGlobalInput.evaluate(element => element.value)).toBe('0.55');
      expect(await reloadedGlobalInput.evaluate(element => element.getAttribute('aria-valuetext'))).toBe('55 percent');
      await expect(trigger).toHaveCount(0);
      await expect(popup).toHaveCount(0);
      await page.locator('.primary-nav a[data-view="scanner"]').evaluate(element => element.click());
      await expect(trigger).toHaveCount(1);
      await expect(trigger).toHaveText('55%');
      await expect(popup).toHaveCount(1);
      expect(await input.evaluate(element => element.isConnected)).toBe(false);
      expect(await reloadedGlobalInput.evaluate(element => element.isConnected)).toBe(true);
    });
  }
}

test.describe('Landscape phone playback', () => {
  test.use({ hasTouch: true, isMobile: true, viewport: { width: 844, height: 390 } });
  test('keeps shared transport reachable below the call in a short coarse-pointer viewport', async ({ page }) => {
    await installSiteStyleApplication(page, 'dark');
    await page.goto('/app.html?view=scanner');
    expect(await page.evaluate(() => matchMedia('(pointer: coarse)').matches)).toBe(true);
    await expect(page.locator('.scanner-playback-primary')).toBeVisible();
    const bar = await page.locator('#scanner-playback-bar').boundingBox();
    const callStage = await page.locator('.scanner-display').boundingBox();
    expect(callStage.y + callStage.height).toBeLessThanOrEqual(bar.y);
    expect(await page.locator('#scanner-playback-bar').evaluate(element => getComputedStyle(element).position)).toBe('static');
    await expectFullWidthScannerActions(page);
    await expect(page.locator('.scanner-queue-trigger, .scanner-queue-panel')).toHaveCount(0);
    const disclosure = page.locator('.scanner-queue-disclosure');
    const summary = disclosure.locator('summary');
    const queueList = disclosure.locator('.scanner-up-next-list');
    expect(await disclosure.evaluate(element => element.open)).toBe(false);
    await expect(queueList).toBeHidden();
    await summary.press('Enter');
    await expect.poll(() => disclosure.evaluate(element => element.open)).toBe(true);
    await expect(queueList).toBeVisible();
    await expectPhoneQueueFits(page, queueList);
    expect((await queueList.boundingBox()).height).toBeLessThanOrEqual(390 * 0.4);
    await summary.press('Tab');
    await expect(queueList).toBeFocused();
    await summary.press('Space');
    await expect.poll(() => disclosure.evaluate(element => element.open)).toBe(false);
    await expect(queueList).toBeHidden();
    expect(await page.locator('.scanner-page').evaluate(element =>
      parseFloat(getComputedStyle(element).paddingBottom))).toBe(0);
    await page.screenshot({ path: test.info().outputPath('scanner-coarse-landscape.png') });
  });
});

for (const theme of ['light']) {
 test(`A scrolled live call stays still when Engineer changes to Simple ${theme}`, async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openAudioApp(page, { view: 'scanner', theme });
  const lists = await openScannerPanel(page, 'Lists');
  await lists.locator('input[id^="audio-dock-scan-list-"]').first().check();
  await page.keyboard.press('Escape');
  await page.locator('.scanner-playback-primary').click();
  await expect(page.locator('.scanner-call-title')).toHaveText('Fire Dispatch');
  await page.screenshot({ path: test.info().outputPath(`scanner-active-${theme}-mobile.png`) });
  const modes = page.getByRole('combobox', { name: 'Scanner detail level', exact: true });
  await modes.selectOption('engineer');
  await expect(page.locator('.scanner-display')).toHaveAttribute('data-scanner-mode', 'engineer');
  const tallHeight = await page.locator('.scanner-console').evaluate(element => element.offsetHeight);
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  const scrollY = await page.evaluate(() => window.scrollY);
  expect(scrollY).toBeGreaterThan(0);
  await modes.evaluate(select => {
    select.value = 'simple';
    select.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await expect(page.locator('.scanner-display')).toHaveAttribute('data-scanner-mode', 'simple');
  expect(await page.locator('.scanner-console').evaluate(element => element.offsetHeight)).toBeLessThan(tallHeight);
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(scrollY);
  await clearScannerQueue(page);
  await expect(page.locator('.scanner-up-next-count')).toHaveText('0 queued');
  const avoids = await openScannerPanel(page, 'Avoid List');
  await expect(avoids).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(avoids).toBeHidden();
});
}

test('Revoked call-audio access disables Scanner controls and blocks stale selection events', async ({ page }) => {
  await page.clock.install();
  const state = await openAudioApp(page, { view: 'scanner' });
  const lists = await openScannerPanel(page, 'Lists');
  const checkbox = await lists.locator('input[id^="audio-dock-scan-list-"]').first().elementHandle();
  const trigger = await page.locator('#scanner-player-menu').elementHandle();
  const volume = await page.locator('#scanner-volume').elementHandle();
  const volumeTrigger = await page.locator('#scanner-volume-button').elementHandle();
  const primary = await page.locator('.scanner-playback-primary').elementHandle();
  const writes = state.preferenceWrites.length;
  state.session.capabilities['call-audio'] = false;
  await page.clock.runFor(10_010);
  await expect.poll(() => checkbox.evaluate(element => element.disabled)).toBe(true);
  expect(await trigger.evaluate(element => element.disabled)).toBe(true);
  expect(await volume.evaluate(element => element.disabled)).toBe(true);
  expect(await volumeTrigger.evaluate(element => element.disabled)).toBe(true);
  await expect(page.locator('#scanner-volume-popover')).toBeHidden();
  expect(await primary.evaluate(element => element.disabled)).toBe(true);
  await expect(lists).toBeHidden();
  await checkbox.evaluate(element => {
    element.checked = !element.checked;
    element.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await volume.evaluate(element => {
    element.value = '0.2';
    element.dispatchEvent(new Event('change', { bubbles: true }));
  });
  expect(state.preferenceWrites.length).toBe(writes);
});

for (const [theme, viewport, mode] of [
  ['light', { width: 1280, height: 900 }, 'normal'],
  ['dark', { width: 1280, height: 900 }, 'engineer'],
  ['light', { width: 390, height: 844 }, 'normal'],
  ['dark', { width: 390, height: 844 }, 'engineer'],
  ['light', { width: 320, height: 568 }, 'simple'],
  ['dark', { width: 320, height: 568 }, 'advanced']
]) {
  test(`Scanner idle preserves call space ${theme} ${viewport.width} ${mode}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.clock.install();
    await openAudioApp(page, { view: 'scanner', theme, feedCallsEnabled: false });
    const pending = [];
    let cursor = 0;
    await page.route('**/api/v1/calls/feed*', route => route.fulfill({ json: {
      data: { cursor: String(++cursor), reset: false, calls: pending.splice(0) }
    } }));
    await page.getByRole('combobox', { name: 'Scanner detail level', exact: true }).selectOption(mode);
    const display = page.locator('.scanner-display');
    const source = display.getByRole('region', { name: 'Transmitting radio', exact: true });
    await expect(source.getByText('Transmitting', { exact: true })).toBeVisible();
    await expect(display.locator('.scanner-audio-wave')).toHaveCount(mode === 'simple' ? 0 : 1);
    const diagnostics = mode === 'advanced' || mode === 'engineer';
    await expect(display.getByRole('heading', { name: 'Target', exact: true })).toHaveCount(diagnostics ? 1 : 0);
    await expect(display.getByRole('heading', { name: 'Source', exact: true })).toHaveCount(diagnostics ? 1 : 0);
    await expect(display.getByText('Call Quality', { exact: true })).toHaveCount(mode === 'engineer' ? 1 : 0);
    await expect(display.locator('button:enabled, a[href]')).toHaveCount(0);
    if (viewport.width >= 1181) await expectCompactDesktopScannerActions(page);
    else await expectFullWidthScannerActions(page);

    const call = { ...liveCall,
      call_id: `idle-density-${theme}-${viewport.width}-${mode}`,
      target_alias: 'County Fire Dispatch and Emergency Operations Coordination',
      source_alias: 'Engine 4 — Regional Emergency Response Unit',
      channel: 'North Ridge Regional Emergency Operations Simulcast Channel',
      system: 'Metropolitan Public Safety Interoperability Network', site: 'North Ridge Simulcast',
      protocol: 'APCO25_PHASE2', wacn: 1, system_id: 1, rfss_id: 1, site_id: 2, nac: 293,
      source_description: 'Regional fire engine response crew', source_group: 'Field units',
      target_description: 'County fire dispatch operations', target_group: 'Fire',
      vc_quality_pct: 100, vc_decoded_frames: 100, vc_repeated_frames: 0,
      vc_concealed_frames: 0, vc_missing_frames: 0, vc_fec_errors: 0, vc_fec_protected_bits: 6400
    };
    pending.push(call);
    await page.locator('.scanner-playback-primary').click();
    await expect(page.locator('.scanner-call-title')).toHaveText(call.target_alias);
    await expect(source.locator('.scanner-field-link').first()).toHaveText(call.source_alias);
    if (mode !== 'simple') {
      await expect(source.getByLabel(`Target group: ${call.target_group}`, { exact: true })).toHaveText(call.target_group);
    }
    if (mode === 'normal') {
      for (const [label, value] of [
        ['Channel', call.channel], ['Scan Lists', 'County Fire and Emergency Medical Services']
      ]) {
        const detail = display.locator('.scanner-field-grid .scanner-field')
          .filter({ has: page.getByText(label, { exact: true }) })
          .locator('.scanner-field-link, .scanner-field-value');
        await expect(detail).toHaveCSS('white-space', 'nowrap');
        await expect(detail).toHaveAttribute('data-ui-hint', value);
      }
    }
    await expect.poll(() => page.evaluate(() =>
      window.audioTest.liveSources.filter(source => source.active).length)).toBe(1);
    expect(await display.locator('button:enabled').count()).toBeGreaterThan(0);
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    const before = await display.evaluate(element => {
      const host = element.getBoundingClientRect();
      const sections = [...element.children].map(section => {
        const box = section.getBoundingClientRect();
        return { name: section.className, top: box.top - host.top, width: box.width, height: box.height };
      });
      return { height: host.height, sections, scrollY: window.scrollY };
    });
    await page.evaluate(() => {
      const source = window.audioTest.liveSources.find(value => value.active);
      if (!source?.onended) throw new Error('Expected an active fixture call to complete.');
      source.active = false;
      source.onended();
    });
    await expect.poll(() => page.evaluate(() =>
      window.audioTest.liveSources.filter(source => source.active).length)).toBe(0);
    await expect(page.locator('.scanner-call-title')).toHaveText(call.target_alias);
    await page.clock.fastForward(5_001);
    await expect(display).not.toContainText(call.target_alias);
    await expect(display).not.toContainText(call.source_alias);
    await expect(display).not.toContainText(call.channel);
    await expect(display).not.toContainText('851.01250 MHz');
    await expect(display.locator('button:enabled, a[href]')).toHaveCount(0);
    await expect(display.locator('.scanner-field-grid [data-ui-hint]')).toHaveCount(0);
    await expect(display.locator('.scanner-group-value[aria-label]')).toHaveCount(0);
    const idle = await display.evaluate(element => {
      const host = element.getBoundingClientRect();
      const sections = [...element.children].map(section => {
        const box = section.getBoundingClientRect();
        return { name: section.className, top: box.top - host.top, width: box.width, height: box.height };
      });
      return { height: host.height, sections, scrollY: window.scrollY };
    });
    expect(idle.height).toBeCloseTo(before.height, 1);
    expect(idle.sections).toHaveLength(before.sections.length);
    for (let index = 0; index < before.sections.length; index += 1) {
      for (const dimension of ['top', 'width', 'height']) {
        expect(idle.sections[index][dimension],
          `${mode} ${before.sections[index].name} ${dimension}`).toBeCloseTo(before.sections[index][dimension], 1);
      }
    }
    expect(idle.scrollY).toBe(before.scrollY);
    const placeholders = await display.locator(
      '.scanner-field-link, .scanner-field-value, .scanner-engineer-item strong').allTextContents();
    expect(placeholders.length).toBeGreaterThan(0);
    expect(placeholders.every(value => value.trim() === '—')).toBe(true);
    await page.mouse.move(0, 0);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: test.info().outputPath(
      `scanner-idle-${theme}-${viewport.width}-${mode}.png`) });
    await page.screenshot({ path: test.info().outputPath(
      `scanner-idle-full-${theme}-${viewport.width}-${mode}.png`), fullPage: true });
    await page.locator('#scanner-stop').click();
    await expect(display).not.toContainText(call.target_alias);
    expect(await display.evaluate(element => element.getBoundingClientRect().height)).toBeCloseTo(before.height, 1);
    pending.push({ ...call, call_id: `${call.call_id}-next`,
      target_alias: 'Next dispatch call', source_alias: 'Engine 5',
      started_at_ms: call.started_at_ms + 60_000, completed_at_ms: call.completed_at_ms + 60_000 });
    await page.locator('.scanner-playback-primary').click();
    await expect(page.locator('.scanner-call-title')).toHaveText('Next dispatch call');
    const navigation = display.locator('.scanner-field-link:enabled').first();
    await expect(navigation).toBeEnabled();
    await navigation.click();
    await expect(page.getByRole('dialog', { name: 'Details unavailable', exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
  });
}
