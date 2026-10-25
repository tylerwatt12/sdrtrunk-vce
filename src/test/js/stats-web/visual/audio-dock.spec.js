'use strict';

const { expect, test } = require('@playwright/test');
const { selectAudioPanel, openAudioVolume, expectAudioSettings } = require('./audio-dock-controls.cjs');
const { selectDockSource } = require('./fixtures/audio-dock-source.cjs');
const { openAudioApp, recording, transcript } = require('./fixtures/audio-dock-app.cjs');

function dock(page) { return page.locator('#audio-dock'); }

async function selectPanel(page, name) {
  await selectAudioPanel(dock(page), name);
}

async function fullControls(page) {
  const player = dock(page);
  await player.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').press('End');
  await expect(player).toHaveAttribute('data-state', 'full');
}

async function chooseRecording(page, index = 0, mode = 'Play once') {
  if (await dock(page).getAttribute('data-state') === 'full') {
    await dock(page).locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').press('ArrowDown');
  }
  await page.locator('.recordings-call-info').nth(index).click();
  await page.getByRole('dialog', { name: 'Call actions', exact: true })
    .getByRole('button', { name: new RegExp(mode) }).click();
  await expect(dock(page)).toHaveAttribute('data-source', 'recordings');
}

async function expectReachableControls(player) {
  // Measure one settled presentation in one browser turn. Native instant
  // scrolling reveals overflow controls without repeated driver round trips.
  const measurements = await player.locator('button:visible, input:visible, select:visible').evaluateAll(inputs =>
    inputs.map(input => {
      const element = input.matches('.ui-toggle input') ? input.parentElement : input;
      element.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
      const rect = element.getBoundingClientRect();
      const hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
      return { label: input.getAttribute('aria-label') || element.textContent.trim(),
        x: rect.x, right: rect.right, top: rect.top, bottom: rect.bottom,
        width: rect.width, height: rect.height, viewportWidth: window.innerWidth,
        viewportHeight: window.innerHeight, covered: !hit || !element.contains(hit) };
    }));
  expect(measurements.length).toBeGreaterThan(0);
  for (const bounds of measurements) {
    expect(bounds.width, bounds.label).toBeGreaterThan(0);
    expect(bounds.height, bounds.label).toBeGreaterThan(0);
    expect(bounds.x, bounds.label).toBeGreaterThanOrEqual(-1);
    expect(bounds.right, bounds.label).toBeLessThanOrEqual(bounds.viewportWidth + 1);
    expect(bounds.top, bounds.label).toBeGreaterThanOrEqual(-1);
    expect(bounds.bottom, bounds.label).toBeLessThanOrEqual(bounds.viewportHeight + 1);
    expect(bounds.covered, `Control ${bounds.label} is covered`).toBe(false);
  }
}

for (const viewport of [{ width: 320, height: 740 }, { width: 390, height: 844 },
  { width: 1280, height: 900 }, { width: 844, height: 390 }]) {
  test(`three dock states and panels remain reachable at ${viewport.width}×${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openAudioApp(page, { theme: viewport.width === 390 ? 'dark' : 'light' });
    const player = dock(page);
    await expect(player).toBeVisible();
    await fullControls(page);
    await player.getByRole('button', { name: 'Listen live', exact: true }).click();
    await expect(player.getByRole('button', { name: 'Hold this talkgroup', exact: true })).toBeEnabled();
    for (const panel of ['Details', 'Queue', 'Scan Lists', 'Settings']) {
      await selectPanel(page, panel);
      await expectReachableControls(player);
    }
    await player.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').press('Home');
    await expect(player).toHaveAttribute('data-state', 'collapsed');
    await expect(player.getByRole('slider')).toHaveCount(0);
    await expect(player.getByRole('tab')).toHaveCount(0);
    await expectReachableControls(player);
    await player.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').click();
    await expect(player).toHaveAttribute('data-state', 'minimal');
    await expectReachableControls(player);
    const bounds = await player.boundingBox();
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width);
    expect(bounds.y + bounds.height).toBeGreaterThanOrEqual(viewport.height - 25);
    if (viewport.width <= 844) {
      expect(bounds.x).toBeLessThanOrEqual(1);
      expect(bounds.width).toBeGreaterThanOrEqual(viewport.width - 1);
    } else {
      expect(bounds.x).toBeGreaterThan(viewport.width / 2);
      expect(bounds.width).toBeLessThan(viewport.width / 2);
    }
    await chooseRecording(page);
    await fullControls(page);
    await expect(player.locator('.audio-dock-lists-trigger:visible')).toHaveCount(0);
    await expect(player.locator('#audio-dock-settings:visible')).toHaveCount(0);
    for (const panel of ['Details', 'Transcript', 'Queue']) {
      await selectPanel(page, panel);
      await expectReachableControls(player);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1);
  });
}

test('recording facts and transcript remain available and applying a dock filter resets playback', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await openAudioApp(page);
  await chooseRecording(page);
  await fullControls(page);
  await selectPanel(page, 'Details');
  const player = dock(page);
  for (const group of ['Source & target identities', 'Protocol & signaling', 'Recording identifiers',
    'Received site identities']) await player.locator('summary').filter({ hasText: group }).click();
  for (const field of ['Duration', 'Call type', 'Voice type', 'Frequency', 'Winning site', 'Also received on',
    'Saved channel', 'Talkgroup ID', 'Talkgroup alias', 'Talkgroup description', 'Talkgroup group',
    'Source Radio ID', 'Source alias', 'Source description', 'Latest OTA name', 'Source group',
    'Radio system', 'Decoder', 'WACN', 'SysID', 'RFSS', 'Site ID', 'NAC', 'Timeslot', 'Started',
    'Recording ID', 'Ended', 'File size', 'System key', 'Channel ID', 'Alias List ID',
    'Source home WACN', 'Source home system', 'Source home identity',
    'Target home WACN', 'Target home system', 'Target home identity',
    'Winning site WACN', 'Winning site SysID', 'Winning site RFSS', 'Winning site ID',
    'Received site 1 WACN', 'Received site 1 SysID', 'Received site 1 RFSS', 'Received site 1 ID']) {
    await expect(player.locator('dt').filter({ hasText: new RegExp(`^${field}$`) })).toHaveCount(1);
  }
  await expect(player).toContainText('Engine 4');
  await expect(player).toContainText('ENG 4');
  await expect(player).toContainText('North Ridge');
  const factLink = (field) => player.locator('dt').filter({ hasText: new RegExp(`^${field}$`) })
    .locator('xpath=following-sibling::dd[1]').getByRole('link');
  for (const [field, value] of [['WACN', '0x00001 (1)'], ['SysID', '0x001 (1)'],
    ['RFSS', '0x01 (1)'], ['Site ID', '0x02 (2)'], ['NAC', '0x125 (293)'],
    ['Source home WACN', '0x00001 (1)'], ['Target home system', '0x001 (1)'],
    ['Winning site ID', '0x02 (2)'], ['Received site 1 ID', '0x03 (3)']]) {
    await expect(player.locator('dt').filter({ hasText: new RegExp(`^${field}$`) })
      .locator('xpath=following-sibling::dd[1]')).toHaveText(value);
  }
  for (const [field, filters] of [
    ['Radio system', { system_key: recording.system_key }],
    ['Saved channel', { channel_id: recording.channel_id }],
    ['Source Radio ID', { system_key: recording.system_key, radio_identity_key: recording.source_entity_ref.identity_key }],
    ['Talkgroup ID', { system_key: recording.system_key, talkgroup_identity_key: recording.target_entity_ref.identity_key }]
  ]) {
    const link = factLink(field);
    await expect(link).toHaveCount(1);
    const query = new URL(await link.getAttribute('href'), page.url()).searchParams;
    expect(Object.fromEntries(query)).toMatchObject({ view: 'recordings', ...filters });
  }
  await factLink('Radio system').click();
  await expect.poll(() => state.requests.filter((request) => request.path === '/api/v1/recordings/calls')
    .at(-1)?.query).toMatchObject({ system_key: recording.system_key });
  await expect(page).toHaveURL(/view=recordings/);
  await expect(player).toHaveAttribute('data-state', 'full');
  await expect(player).toHaveAttribute('data-source', 'recordings');
  await expect(player).toContainText('Nothing queued');
  await expect(player).not.toContainText('Engine 4');
  await selectPanel(page, 'Transcript');
  await expect(player).not.toContainText(transcript.text);
});

for (const [name, changes, expected] of [
  ['patch', { call_type: 'PATCH', talkgroup_id: 59001, talkgroup_alias: 'Dispatch Patch',
    patch_members: [{ kind: 'talkgroup', id: 1201, alias: 'Fire Dispatch', entity_ref: recording.target_entity_ref },
      { kind: 'talkgroup', id: 1202, alias: 'EMS Dispatch' }] }, ['Patch ID', 'Patch members', 'EMS Dispatch']],
  ['direct', { call_type: 'DIRECT', talkgroup_id: null, talkgroup_alias: null, destination_radio_id: 30920,
    destination_radio_alias: 'Medic Supervisor', destination_radio_description: 'Medical response supervisor',
    destination_radio_group: 'EMS command' }, ['Destination Radio ID', 'Destination alias', 'Destination description',
      'Destination group', 'Medic Supervisor']],
  ['analog', { protocol: 'NBFM', channel_name: 'County Fire Mutual Aid', tone: '156.7', pl: '156.7' },
    ['Tone', 'PL', '156.7']]
]) {
  test(`${name} recording preserves its conditional metadata`, async ({ page }) => {
    await openAudioApp(page, { calls: [{ ...recording, ...changes }] });
    await chooseRecording(page);
    await fullControls(page);
    await selectPanel(page, 'Details');
    await dock(page).locator('summary').filter({ hasText: name === 'analog' ? 'Protocol & signaling' :
      'Source & target identities' }).click();
    for (const text of expected) await expect(dock(page)).toContainText(text);
  });
}

test('an expanded queue leaves the last recording and pagination usable', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openAudioApp(page);
  await chooseRecording(page, 0, 'Continue from here');
  await fullControls(page);
  await selectPanel(page, 'Queue');
  await expect(dock(page)).toContainText('ENG 5');
  const pager = page.getByRole('navigation', { name: 'Call result pages below calls', exact: true });
  await pager.getByRole('button', { name: 'Next', exact: true }).scrollIntoViewIfNeeded();
  await pager.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(pager).toContainText('Page 2');
  await expect(page.locator('.recordings-call').last()).toContainText('ENG 33');
  await expectReachableControls(dock(page));
});

test('dock state and current recording survive navigation, while Scanner hides the dock', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openAudioApp(page);
  await chooseRecording(page);
  await fullControls(page);
  await selectPanel(page, 'Transcript');
  await page.locator('[data-nav-group="listen"] summary').click();
  await page.locator('#primary-navigation a[href="/?view=dashboard"]').click();
  await expect(page).toHaveURL(/view=dashboard/);
  await expect(dock(page)).toHaveAttribute('data-state', 'full');
  await expect(dock(page)).toHaveAttribute('data-source', 'recordings');
  await expect(dock(page)).toContainText(transcript.text);
  await page.locator('[data-nav-group="listen"] summary').click();
  await page.locator('#primary-navigation a[href="/?view=scanner"]').click();
  await expect(page).toHaveURL(/view=scanner/);
  await expect(dock(page)).toBeHidden();
  await page.locator('[data-nav-group="listen"] summary').click();
  await page.locator('#primary-navigation a[href="/?view=recordings"]').click();
  await expect(dock(page)).toBeVisible();
  await expect(dock(page)).toContainText(transcript.text);
});

test('live and recording playback stop one another without clearing the recording queue', async ({ page }) => {
  await openAudioApp(page);
  await chooseRecording(page, 0, 'Continue from here');
  await fullControls(page);
  await selectPanel(page, 'Queue');
  await expect(dock(page)).toContainText('ENG 5');
  await selectDockSource(dock(page), 'live');
  await dock(page).getByRole('button', { name: 'Listen live', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.audioTest.liveSources.filter((source) => source.active).length))
    .toBe(1);
  await expect.poll(() => page.evaluate(() => window.audioTest.recordings.at(-1).action)).toBe('pause');
  await selectDockSource(dock(page), 'recordings');
  await dock(page).getByRole('button', { name: /Play recording|Resume recording/ }).click();
  await expect.poll(() => page.evaluate(() => window.audioTest.liveSources.filter((source) => source.active).length))
    .toBe(0);
  await expect.poll(() => page.evaluate(() => window.audioTest.recordings.at(-1).action)).toBe('play');
  await selectPanel(page, 'Queue');
  await expect(dock(page)).toContainText('ENG 5');
});

test('Scan Lists and Settings keep keyboard focus and values while calls arrive and preferences change', async ({ page }) => {
  const state = await openAudioApp(page, { feedCallsEnabled: false });
  await fullControls(page);
  await selectPanel(page, 'Scan Lists');
  const scanList = dock(page).getByRole('checkbox', { name: /County Fire and Emergency Medical Services/ });
  await scanList.check();
  await expect(scanList).toBeFocused();
  await expect(scanList).toBeChecked();
  await dock(page).getByRole('button', { name: 'Listen live', exact: true }).click();
  await selectPanel(page, 'Settings');
  await expectAudioSettings(dock(page));
  const burst = dock(page).getByRole('spinbutton', { name: 'Calls per target' });
  await burst.focus();
  state.feedCallsEnabled = true;
  await expect(dock(page).getByRole('button', { name: 'Hold this talkgroup', exact: true })).toBeEnabled();
  await expect(burst).toBeFocused();
  await burst.fill('7');
  await burst.dispatchEvent('change');
  await expect(burst).toBeFocused();
  await expect(burst).toHaveValue('7');
  await expect.poll(() => state.preferenceWrites.some((write) => write.preferences?.playback?.target_burst_limit === 7 ||
    write.playback?.target_burst_limit === 7)).toBe(true);
  await chooseRecording(page, 0, 'Continue from here');
  await fullControls(page);
  await selectPanel(page, 'Queue');
  const clickedCallMode = dock(page).locator('#audio-dock-panel').getByRole('combobox', { name: 'When a call is clicked' });
  await clickedCallMode.focus();
  await clickedCallMode.selectOption('once');
  await expect(clickedCallMode).toBeFocused();
  await expect(clickedCallMode).toHaveValue('once');
});

test('adding a recording to the queue preserves live playback and its selected source', async ({ page }) => {
  await openAudioApp(page);
  await fullControls(page);
  await dock(page).getByRole('button', { name: 'Listen live', exact: true }).click();
  await expect(dock(page).getByRole('button', { name: 'Hold this talkgroup', exact: true })).toBeEnabled();
  await dock(page).locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').press('ArrowDown');
  await page.locator('.recordings-call-info').first().click();
  await page.getByRole('dialog', { name: 'Call actions', exact: true })
    .getByRole('button', { name: /Add to queue/ }).click();
  await expect(dock(page)).toHaveAttribute('data-source', 'live');
  await expect(dock(page).getByRole('button', { name: 'Pause live audio', exact: true })).toBeEnabled();
  expect(await page.evaluate(() => window.audioTest.liveSources.filter((source) => source.active).length)).toBe(1);
  expect(await page.evaluate(() => window.audioTest.recordings.some((event) => event.action === 'play'))).toBe(false);
  await selectDockSource(dock(page), 'recordings');
  await fullControls(page);
  await selectPanel(page, 'Queue');
  await expect(dock(page).locator('#audio-dock-panel .audio-dock-queue-item')).toContainText('Fire Dispatch');
});

for (const [name, capabilities, expected] of [
  ['live only', { recordings: false }, 'Live'],
  ['recordings only', { 'call-audio': false }, 'Recordings']
]) {
  test(`${name} access enables only the allowed source`, async ({ page }) => {
    await openAudioApp(page, { view: 'dashboard', capabilities });
    await expect(dock(page)).toBeVisible();
    await fullControls(page);
    const source = dock(page).getByRole('combobox', { name: 'Audio source', exact: true });
    await expect(source).toBeEnabled();
    await expect(source.locator(`option[value="${expected.toLowerCase()}"]`)).toHaveJSProperty('disabled', false);
    await expect(source.locator(`option[value="${expected === 'Live' ? 'recordings' : 'live'}"]`))
      .toHaveJSProperty('disabled', true);
  });
}

test('audio dock stays hidden when neither playback capability is allowed', async ({ page }) => {
  const state = await openAudioApp(page, { view: 'dashboard', capabilities: { recordings: false, 'call-audio': false } });
  await expect(dock(page)).toBeHidden();
  expect(state.requests.some((request) => request.path === '/api/v1/calls/feed')).toBe(false);
});

test('live volume updates immediately during dragging and persists only the final change', async ({ page }) => {
  const state = await openAudioApp(page, { volume: 0.65 });
  await fullControls(page);
  await dock(page).getByRole('button', { name: 'Listen live', exact: true }).click();
  await expect(dock(page).getByRole('button', { name: 'Hold this talkgroup', exact: true })).toBeEnabled();
  await expect.poll(() => state.preferenceWrites.length).toBeGreaterThan(0);
  const volume = await openAudioVolume(page);
  await expect(volume).toHaveValue('0.65');
  await expect(dock(page).getByRole('button', { name: /^(Mute|Unmute) audio$/ })).toHaveCount(0);
  const before = state.preferenceWrites.length;
  await volume.evaluate((element) => {
    for (const value of [0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.75]) {
      element.value = String(value);
      element.dispatchEvent(new Event('input', { bubbles: true }));
    }
  });
  await expect(volume).toHaveValue('0.75');
  await expect(page.locator('#playback-volume')).toHaveValue('0.75');
  expect(state.preferenceWrites.length).toBe(before);
  await volume.dispatchEvent('change');
  await expect.poll(() => state.preferenceWrites.length).toBe(before + 1);
  expect(state.preferenceWrites.at(-1).playback.volume).toBe(0.75);
});

test('native volume keeps zero and independent levels when switching sources', async ({ page }) => {
  await openAudioApp(page, { volume: 0.65 });
  await fullControls(page);
  const player = dock(page);
  let volume = await openAudioVolume(page);
  await expect(volume).toHaveValue('0.65');
  await volume.press('Home');
  await expect(volume).toHaveValue('0');
  await expect(volume).toHaveAttribute('aria-valuetext', 'Muted, 0 percent');
  await expect(page.locator('#audio-dock-volume-popover output')).toHaveText('0%');
  await page.keyboard.press('Escape');
  await expect(player.locator('[data-volume-trigger]:visible')).toBeFocused();
  await selectDockSource(player, 'recordings');
  volume = await openAudioVolume(page);
  await expect(volume).toHaveValue('1');
  await volume.press('Home');
  for (let index = 0; index < 8; index++) await volume.press('ArrowRight');
  await expect(volume).toHaveValue('0.4');
  await page.keyboard.press('Escape');
  await selectDockSource(player, 'live');
  volume = await openAudioVolume(page);
  await expect(volume).toHaveValue('0');
  await volume.press('ArrowRight');
  await expect(volume).toHaveValue('0.05');
  await page.keyboard.press('Escape');
  await selectDockSource(player, 'recordings');
  volume = await openAudioVolume(page);
  await expect(volume).toHaveValue('0.4');
});

test('sidebar inspector retains keyboard focus through queue advances and removes to a surviving row', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const calls = Array.from({ length: 12 }, (_, index) => ({ ...recording, id: 17 + index,
    start_ms: recording.start_ms + index * 60_000, end_ms: recording.end_ms + index * 60_000,
    source_alias: `Engine ${4 + index}`, source_ota_alias: `ENG ${4 + index}` }));
  await openAudioApp(page, { calls });
  await page.evaluate(() => {
    const play = window.Audio.prototype.play;
    window.Audio.prototype.play = function (...args) {
      window.audioTest.recordingPlayer = this;
      return play.apply(this, args);
    };
  });
  await chooseRecording(page, 0, 'Continue from here');
  await fullControls(page);
  const sidebar = dock(page).locator('.audio-dock-up-next');
  const list = sidebar.getByRole('list', { name: 'Queued calls', exact: true });
  const rows = list.locator('button');
  await expect(rows).toHaveCount(11);
  const key = await rows.nth(2).getAttribute('data-queue-key');
  await rows.nth(2).click();
  const selected = list.locator(`[data-queue-key="${key}"]`);
  await expect(selected).toHaveAttribute('aria-pressed', 'true');
  const inspector = sidebar.locator('.audio-dock-queue-inspector');
  await expect(inspector).toContainText('ENG 7');
  for (const [action, nextId, count] of [['Play now', 18, 10], ['Remove', 19, 9]]) {
    const control = inspector.getByRole('button', { name: action, exact: true });
    await control.focus();
    await list.evaluate(element => { element.scrollTop = 40; });
    const scrollTop = await list.evaluate(element => element.scrollTop);
    expect(scrollTop).toBeGreaterThan(0);
    await page.evaluate(() => {
      const audio = window.audioTest.recordingPlayer;
      audio.currentTime = audio.duration;
      audio.paused = true;
      audio.dispatchEvent(new Event('ended'));
    });
    await expect.poll(() => page.evaluate(() => window.audioTest.recordingPlayer.src)).toMatch(new RegExp(`/${nextId}/audio$`));
    await expect(rows).toHaveCount(count);
    await expect(selected).toHaveAttribute('aria-pressed', 'true');
    await expect(control).toBeFocused();
    expect(await list.evaluate(element => element.scrollTop)).toBe(scrollTop);
  }
  await inspector.getByRole('button', { name: 'Remove', exact: true }).press('Enter');
  await expect(selected).toHaveCount(0);
  await expect(inspector).toBeHidden();
  await expect(rows).toHaveCount(8);
  await expect(rows.first()).toBeFocused();
  expect(await page.evaluate(() => window.audioTest.recordingPlayer.src)).toMatch(/\/19\/audio$/);
});

test('repeated recordings retain separate queue row actions', async ({ page }) => {
  await openAudioApp(page);
  await chooseRecording(page);
  await chooseRecording(page, 0, 'Add to queue');
  await chooseRecording(page, 0, 'Add to queue');
  await fullControls(page);
  await selectPanel(page, 'Queue');
  const player = dock(page);
  await expect(player.locator('#audio-dock-panel .audio-dock-queue-item')).toHaveCount(3);
  const remove = player.getByRole('button', { name: 'Remove Fire Dispatch from queue', exact: true });
  await expect(remove).toHaveCount(2);
  await remove.nth(1).click();
  await expect(player.locator('#audio-dock-panel .audio-dock-queue-item')).toHaveCount(2);
  await expect(remove).toHaveCount(1);
  await player.getByRole('button', { name: 'Play Fire Dispatch', exact: true }).click();
  await expect(player.locator('#audio-dock-panel .audio-dock-queue-item')).toHaveCount(1);
  await expect(remove).toHaveCount(0);
});

for (const admin of [true, false]) {
  test(`failed transcript retry is ${admin ? 'available to primary administrators' : 'hidden for listeners'}`, async ({ page }) => {
    const state = await openAudioApp(page, { admin, transcript: { status: 'FAILED', text: null } });
    await chooseRecording(page);
    await fullControls(page);
    await selectPanel(page, 'Transcript');
    await expect(dock(page)).toContainText('Transcription failed');
    const retry = dock(page).getByRole('button', { name: 'Retry transcription', exact: true });
    if (admin) {
      await retry.click();
      await expect(dock(page)).toContainText('Pending transcription');
      expect(state.requests.some((request) => /\/transcription\/retry$/.test(request.path) && request.method === 'POST'))
        .toBe(true);
    } else await expect(retry).toHaveCount(0);
  });
}
