'use strict';

const { expect, test } = require('@playwright/test');
const { openAudioApp, liveCall } = require('./fixtures/audio-dock-app.cjs');

const liveFields = ['Target', 'Target ID', 'Source', 'Source ID', 'System', 'Channel', 'Started', 'Duration',
  'Scan lists', 'Target type', 'Target description', 'Target group', 'Source type', 'Source description',
  'Source group', 'Talker Alias', 'Alias List', 'Playback target', 'Patch group', 'Protocol', 'Decoder',
  'Modulation', 'Frequency', 'Site', 'Network ID', 'Site identity', 'WACN', 'SysID', 'RFSS', 'Site ID',
  'RAN', 'NAC', 'LCN', 'Timeslot', 'Encrypted', 'Quality', 'Decoded frames', 'Repeated frames',
  'Concealed frames', 'Missing frames', 'FEC errors', 'FEC protected bits', 'Call ID', 'Identifier',
  'Configuration ID', 'Radio system key', 'Scan List IDs', 'Start timestamp', 'Completed', 'Completion timestamp'];
const recordingFields = ['Target', 'Source', 'Latest OTA name', 'Radio system', 'Saved channel', 'Winning site',
  'Also received on', 'Started', 'Duration', 'Call type', 'Voice type', 'Decoder', 'Frequency', 'Timeslot',
  'Encrypted', 'Alias List', 'Matched Scan Lists', 'Source Radio ID', 'Source alias', 'Source group',
  'Source description', 'Talkgroup ID', 'Talkgroup alias', 'Talkgroup group', 'Talkgroup description',
  'Destination Radio ID', 'Destination alias', 'Destination group', 'Destination description', 'Patch members',
  'WACN', 'SysID', 'RFSS', 'Site ID', 'NAC', 'Tone kind', 'Tone', 'PL', 'DPL', 'Recording ID', 'Call ID',
  'Ended', 'File size', 'System key', 'Channel ID', 'Alias List ID', 'Raw target ID', 'Source home WACN',
  'Source home system', 'Source home identity', 'Target home WACN', 'Target home system', 'Target home identity'];

const player = (page) => page.locator('#audio-dock');
const handle = (page) => player(page).getByRole('button', { name: 'Change audio player size', exact: true });

async function size(page, value) {
  const key = value === 'full' ? 'End' : value === 'collapsed' ? 'Home' : null;
  await handle(page).focus();
  if (key) await handle(page).press(key);
  else {
    await handle(page).press('Home');
    await handle(page).press('ArrowUp');
  }
  await expect(player(page)).toHaveAttribute('data-state', value);
}

async function panel(page, name) {
  await player(page).getByRole('tab', { name, exact: true }).click();
}

async function openApp(page, options = {}) {
  const state = await openAudioApp(page, { ...options, feedCallsEnabled: false });
  // Complete the fixture Audio element through its normal ended event, without
  // exposing or replacing the production recordings adapter.
  await page.evaluate(() => {
    const original = window.Audio.prototype.play;
    window.Audio.prototype.play = function (...args) {
      window.audioTest.recordingPlayer = this;
      return original.apply(this, args);
    };
  });
  const feed = { pending: [], requests: 0 };
  await page.route('**/api/v1/calls/feed*', async (route) => {
    feed.requests++;
    await route.fulfill({ json: { data: { cursor: String(feed.requests), reset: false,
      calls: feed.pending.splice(0) } } });
  });
  return { ...state, feed };
}

async function chooseRecording(page, index = 0) {
  await size(page, 'minimal');
  await page.locator('.recordings-play-glyph').nth(index).click();
  await page.getByRole('dialog', { name: 'Play recording', exact: true })
    .getByRole('button', { name: /^Play once/ }).click();
  await expect(player(page)).toHaveAttribute('data-source', 'recordings');
  await size(page, 'full');
  await panel(page, 'Details');
}

async function disclosure(page, name, open) {
  const group = player(page).locator('details').filter({ has: page.locator('summary', { hasText: name }) });
  if (((await group.getAttribute('open')) !== null) !== open) await group.locator('summary').click();
  await expect(group).toHaveJSProperty('open', open);
  return group;
}

async function labels(page) {
  return player(page).locator('#audio-dock-panel dt').allTextContents();
}

async function expectBlankFacts(page) {
  await expect.poll(async () => (await player(page).locator('#audio-dock-panel dd').allTextContents())
    .every((value) => !/[A-Za-z0-9]/.test(value))).toBe(true);
  await expect(player(page).locator('#audio-dock-panel dd a')).toHaveCount(0);
}

test('desktop page geometry stays unchanged in every audio dock size', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openApp(page);
  await expect(page.locator('.recordings-call')).toHaveCount(25);
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  const geometry = () => page.locator('.content').evaluate((content) => ({
    height: content.getBoundingClientRect().height,
    scrollHeight: content.scrollHeight,
    paddingBottom: getComputedStyle(content).paddingBottom,
    spacer: getComputedStyle(content, '::after').content,
    documentHeight: document.documentElement.scrollHeight,
    scrollPaddingBottom: getComputedStyle(document.documentElement).scrollPaddingBottom,
  }));
  await size(page, 'collapsed');
  const baseline = await geometry();
  expect(baseline.spacer).toBe('none');
  for (const state of ['minimal', 'full', 'collapsed']) {
    await size(page, state);
    await expect.poll(geometry).toEqual(baseline);
  }
});

async function endLiveCall(page) {
  await page.evaluate(() => {
    const source = window.audioTest.liveSources.find((value) => value.active);
    if (!source?.onended) throw new Error('No playing fixture call is available to complete.');
    source.active = false;
    source.onended();
  });
}

async function expectReachable(control) {
  // Shared toggles keep their semantic input visually hidden; their label is
  // the visible pointer target, and native keyboard focus is checked separately.
  if (await control.evaluate((element) => element.matches('.ui-toggle input'))) control = control.locator('..');
  await control.scrollIntoViewIfNeeded();
  const geometry = await control.evaluate((element) => {
    const r = element.getBoundingClientRect();
    const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    return { x: r.x, right: r.right, y: r.y, bottom: r.bottom, width: r.width, height: r.height,
      viewportWidth: innerWidth, viewportHeight: innerHeight, hit: Boolean(hit && element.contains(hit)) };
  });
  expect(geometry.width).toBeGreaterThan(0);
  expect(geometry.height).toBeGreaterThan(0);
  expect(geometry.x).toBeGreaterThanOrEqual(-1);
  expect(geometry.right).toBeLessThanOrEqual(geometry.viewportWidth + 1);
  expect(geometry.y).toBeGreaterThanOrEqual(-1);
  expect(geometry.bottom).toBeLessThanOrEqual(geometry.viewportHeight + 1);
  expect(geometry.hit).toBe(true);
}

for (const viewport of [{ width: 320, height: 740 }, { width: 390, height: 844 },
  { width: 1280, height: 900 }, { width: 844, height: 390 }]) {
  test(`one accessible header handle cycles every size at ${viewport.width}×${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openApp(page, { theme: viewport.width === 390 ? 'dark' : 'light' });
    await size(page, 'collapsed');
    await expect(handle(page)).toHaveCount(1);
    for (const expected of ['minimal', 'full', 'collapsed']) {
      await expectReachable(handle(page));
      await handle(page).click();
      await expect(player(page)).toHaveAttribute('data-state', expected);
      await expect(handle(page)).toBeFocused();
    }
    await handle(page).press('Enter');
    await expect(player(page)).toHaveAttribute('data-state', 'minimal');
    await handle(page).press('Space');
    await expect(player(page)).toHaveAttribute('data-state', 'full');
    await handle(page).press('ArrowUp');
    await expect(player(page)).toHaveAttribute('data-state', 'full');
    await handle(page).press('ArrowDown');
    await expect(player(page)).toHaveAttribute('data-state', 'minimal');
    await handle(page).press('Home');
    await handle(page).press('ArrowDown');
    await expect(player(page)).toHaveAttribute('data-state', 'collapsed');
    await handle(page).press('End');
    await expect(player(page)).toHaveAttribute('data-state', 'full');
    await expect(player(page).getByRole('button', { name: /^(Expand audio player|Collapse audio player|Show minimal controls)$/ }))
      .toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1);
  });
}

test('vertical header drags resize once; horizontal, short and canceled drags do not resize', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openApp(page);
  const drag = async (dx, dy) => {
    const r = await handle(page).boundingBox();
    const x = r.x + r.width / 2, y = r.y + r.height / 2;
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x + dx, y + dy, { steps: 5 });
    await page.mouse.up();
  };
  await size(page, 'collapsed');
  for (const [dy, expected] of [[-48, 'minimal'], [-48, 'full'], [-48, 'full'],
    [48, 'minimal'], [48, 'collapsed'], [48, 'collapsed']]) {
    await drag(0, dy);
    await expect(player(page)).toHaveAttribute('data-state', expected);
  }
  await size(page, 'minimal');
  for (const [dx, dy] of [[65, -40], [0, 16]]) {
    await drag(dx, dy);
    await expect(player(page)).toHaveAttribute('data-state', 'minimal');
  }
  const r = await handle(page).boundingBox();
  const cdp = await page.context().newCDPSession(page);
  const x = r.x + r.width / 2, y = r.y + r.height / 2;
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y, id: 1 }] });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: y - 45, id: 1 }] });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] });
  await expect(player(page)).toHaveAttribute('data-state', 'minimal');
});

test('source and queue controls keep their own actions without cycling the header', async ({ page }) => {
  await openApp(page, { view: 'dashboard' });
  await size(page, 'full');
  await player(page).getByRole('button', { name: 'Recordings', exact: true }).click();
  await expect(page).toHaveURL(/view=recordings/);
  await expect(player(page)).toHaveAttribute('data-state', 'full');
  await player(page).getByRole('button', { name: 'Live', exact: true }).click();
  await expect(player(page)).toHaveAttribute('data-source', 'live');
  await expect(player(page)).toHaveAttribute('data-state', 'full');
  await player(page).getByRole('button', { name: 'Queue 0', exact: true }).click();
  await expect(player(page).getByRole('tab', { name: 'Queue', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(player(page)).toHaveAttribute('data-state', 'full');
});

test('Recordings navigates from another page even when that source is already selected, retaining playback', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openApp(page);
  await chooseRecording(page);
  const playCount = await page.evaluate(() => window.audioTest.recordings.filter((event) => event.action === 'play').length);
  await page.locator('[data-nav-group="listen"] summary').click();
  await page.locator('#primary-navigation a[href="/?view=dashboard"]').click();
  await expect(page).toHaveURL(/view=dashboard/);
  await expect(player(page)).toHaveAttribute('data-source', 'recordings');
  await player(page).getByRole('button', { name: 'Recordings', exact: true }).click();
  await expect(page).toHaveURL(/view=recordings/);
  await expect(player(page).getByRole('button', { name: 'Pause recording', exact: true })).toBeVisible();
  await expect(player(page)).toHaveAttribute('data-state', 'full');
  expect(await page.evaluate(() => window.audioTest.recordings.filter((event) => event.action === 'play').length)).toBe(playCount);
});

test('live Details keeps every label and disclosure state through active, paused and idle calls', async ({ page }) => {
  const state = await openApp(page);
  await size(page, 'full');
  await panel(page, 'Details');
  const idleLabels = await labels(page);
  expect(idleLabels).toEqual(expect.arrayContaining(liveFields));
  await expectBlankFacts(page);
  await disclosure(page, 'Identity & aliases', true);
  await disclosure(page, 'Radio & channel', false);
  await disclosure(page, 'Voice quality', true);
  await disclosure(page, 'Call', false);
  state.feed.pending.push({ ...liveCall, target_description: 'County fire dispatch', source_description: 'Response team',
    vc_quality_pct: 98, vc_decoded_frames: 84, vc_missing_frames: 0, talker_alias: 'ENG 4' });
  await player(page).getByRole('button', { name: 'Listen live', exact: true }).click();
  await expect(player(page)).toContainText('Engine 4');
  expect(await labels(page)).toEqual(idleLabels);
  await disclosure(page, 'Identity & aliases', true);
  await disclosure(page, 'Voice quality', true);
  await player(page).getByRole('button', { name: 'Pause live audio', exact: true }).click();
  await expect(player(page).locator('#audio-dock-panel')).toContainText('Engine 4');
  await player(page).getByRole('button', { name: 'Resume live audio', exact: true }).click();
  const identityHeading = player(page).locator('summary', { hasText: 'Identity & aliases' });
  await identityHeading.focus();
  await endLiveCall(page);
  await expectBlankFacts(page);
  await expect(identityHeading).toBeFocused();
  expect(await labels(page)).toEqual(idleLabels);
  await expect(player(page).locator('details').filter({ has: page.locator('summary', { hasText: 'Identity & aliases' }) }))
    .toHaveJSProperty('open', true);
  await expect(player(page).locator('details').filter({ has: page.locator('summary', { hasText: 'Radio & channel' }) }))
    .toHaveJSProperty('open', false);
  state.feed.pending.push({ ...liveCall, call_id: 'refinement-second-call', started_at_ms: liveCall.started_at_ms + 60_000,
    completed_at_ms: liveCall.completed_at_ms + 60_000, source_alias: 'Engine 5', source_id: 30915 });
  await expect(player(page).locator('#audio-dock-panel')).toContainText('Engine 5');
  await expect(identityHeading).toBeFocused();
  expect(await labels(page)).toEqual(idleLabels);
  await endLiveCall(page);
  await expectBlankFacts(page);
  await expect(player(page).locator('details').filter({ has: page.locator('summary', { hasText: 'Voice quality' }) }))
    .toHaveJSProperty('open', true);
});

test('recording Details retains static and received-site labels but clears values after end or stop', async ({ page }) => {
  await openApp(page);
  await size(page, 'full');
  await player(page).getByRole('button', { name: 'Recordings', exact: true }).click();
  await panel(page, 'Details');
  expect(await labels(page)).toEqual(expect.arrayContaining(recordingFields));
  await expectBlankFacts(page);
  await chooseRecording(page);
  await expect(player(page).locator('#audio-dock-panel')).toContainText('Engine 4');
  const activeLabels = await labels(page);
  expect(activeLabels).toEqual(expect.arrayContaining(['Winning site WACN', 'Received site 1 WACN', 'Received site 1 ID']));
  await disclosure(page, 'Source & target identities', true);
  await disclosure(page, 'Protocol & signaling', false);
  await disclosure(page, 'Received site identities', true);
  await player(page).getByRole('button', { name: 'Pause recording', exact: true }).click();
  await expect(player(page).locator('#audio-dock-panel')).toContainText('Engine 4');
  await player(page).getByRole('button', { name: 'Play recording', exact: true }).click();
  const sitesHeading = player(page).locator('summary', { hasText: 'Received site identities' });
  await sitesHeading.focus();
  await page.evaluate(() => window.audioTest.recordingPlayer.dispatchEvent(new Event('ended')));
  await expectBlankFacts(page);
  await expect(sitesHeading).toBeFocused();
  expect(await labels(page)).toEqual(activeLabels);
  await expect(player(page).locator('details').filter({ has: page.locator('summary', { hasText: 'Received site identities' }) }))
    .toHaveJSProperty('open', true);
  await chooseRecording(page, 1);
  await expect(player(page).locator('#audio-dock-panel')).toContainText('Engine 5');
  await player(page).getByRole('button', { name: 'Stop recording', exact: true }).click();
  await expectBlankFacts(page);
  expect(await labels(page)).toEqual(activeLabels);
});

test('disclosure choices remain separate by source and survive tab and size changes', async ({ page }) => {
  await openApp(page);
  await size(page, 'full');
  await panel(page, 'Details');
  await disclosure(page, 'Identity & aliases', true);
  await disclosure(page, 'Radio & channel', false);
  await player(page).getByRole('button', { name: 'Recordings', exact: true }).click();
  await disclosure(page, 'Source & target identities', false);
  await disclosure(page, 'Protocol & signaling', true);
  await panel(page, 'Queue');
  await panel(page, 'Details');
  await size(page, 'collapsed');
  await size(page, 'full');
  await expect(player(page).locator('details').filter({ has: page.locator('summary', { hasText: 'Protocol & signaling' }) }))
    .toHaveJSProperty('open', true);
  await player(page).getByRole('button', { name: 'Live', exact: true }).click();
  await expect(player(page).locator('details').filter({ has: page.locator('summary', { hasText: 'Identity & aliases' }) }))
    .toHaveJSProperty('open', true);
  await expect(player(page).locator('details').filter({ has: page.locator('summary', { hasText: 'Radio & channel' }) }))
    .toHaveJSProperty('open', false);
});

for (const viewport of [{ width: 320, height: 740 }, { width: 844, height: 390 }]) {
  test(`Listening setting sections retain actions and focus at ${viewport.width}×${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const state = await openApp(page);
    await size(page, 'full');
    await panel(page, 'Listening');
    for (const name of ['Scan Lists', 'Avoid List', 'Call grouping', 'Target rotation', 'Page title']) {
      await expect(player(page).getByRole('heading', { name, exact: true })).toHaveCount(1);
    }
    const scan = player(page).getByRole('checkbox', { name: /County Fire and Emergency Medical Services/ });
    await scan.check();
    await expect(scan).toBeFocused();
    await scan.uncheck();
    await expect(scan).toBeFocused();
    await scan.check();
    await expect(scan).toBeFocused();
    const grouping = player(page).getByRole('checkbox', { name: 'Group calls by target', exact: true });
    await expect(player(page).locator('section').filter({ has: page.getByRole('heading', { name: 'Call grouping', exact: true }) })
      .getByRole('checkbox', { name: 'Group calls by target', exact: true })).toHaveCount(1);
    // Use the visible shared toggle, as a person would, rather than clicking its
    // visually hidden one-pixel checkbox.
    const groupingTrack = grouping.locator('..').locator('.ui-toggle-track');
    await expectReachable(groupingTrack);
    await groupingTrack.click();
    await expect(grouping).not.toBeChecked();
    await expect(grouping).toBeFocused();
    await expect.poll(() => player(page).evaluate((element) => element.scrollTop)).toBe(0);
    await expectReachable(handle(page));
    const burst = player(page).getByRole('spinbutton', { name: 'Calls per target', exact: true });
    await expect(player(page).locator('section').filter({ has: page.getByRole('heading', { name: 'Target rotation', exact: true }) })
      .getByRole('spinbutton', { name: 'Calls per target', exact: true })).toHaveCount(1);
    await burst.fill('7');
    await burst.dispatchEvent('change');
    await expect(burst).toBeFocused();
    await expect(burst).toHaveValue('7');
    const prepend = player(page).getByRole('checkbox', { name: 'Playing call in page title', exact: true });
    await expect(player(page).locator('section').filter({ has: page.getByRole('heading', { name: 'Page title', exact: true }) })
      .getByRole('checkbox', { name: 'Playing call in page title', exact: true })).toHaveCount(1);
    await burst.focus();
    await page.keyboard.press('Tab');
    await expect(prepend).toBeFocused();
    await expect.poll(() => player(page).evaluate((element) => element.scrollTop)).toBe(0);
    await expectReachable(handle(page));
    const prependTrack = prepend.locator('..').locator('.ui-toggle-track');
    await expectReachable(prependTrack);
    await prependTrack.click();
    const expectedTitle = await prepend.isChecked();
    await expect(prepend).toBeFocused();
    await expect.poll(() => state.preferenceWrites.some((write) =>
      (write.preferences || write).playback?.target_grouping === false &&
      (write.preferences || write).playback?.target_burst_limit === 7 &&
      (write.preferences || write).page_titles?.prepend_playing_call === expectedTitle)).toBe(true);
    state.feed.pending.push(liveCall);
    await player(page).getByRole('button', { name: 'Listen live', exact: true }).click();
    await burst.focus();
    await expect(player(page).getByRole('button', { name: 'Avoid', exact: true })).toBeEnabled();
    await expect(burst).toBeFocused();
    await expect(burst).toHaveValue('7');
    await player(page).getByRole('button', { name: 'Avoid', exact: true }).click();
    const remove = player(page).getByRole('button', { name: 'Remove Fire Dispatch from avoid list', exact: true });
    await expect(remove).toBeVisible();
    await expectReachable(remove);
    await remove.click();
    await expect(remove).toHaveCount(0);
    await expect(player(page)).toContainText('No targets avoided.');
    for (const control of await player(page).locator('#audio-dock-panel input:visible, #audio-dock-panel button:visible').all()) {
      await expectReachable(control);
    }
  });
}
