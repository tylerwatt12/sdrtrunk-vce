'use strict';

const { expect, test } = require('@playwright/test');
const { openAudioApp, recording, transcript } = require('./fixtures/audio-dock-app.cjs');

function dock(page) { return page.locator('#audio-dock'); }

async function selectPanel(page, name) {
  await dock(page).getByRole('tab', { name, exact: true }).click();
}

async function fullControls(page) {
  const player = dock(page);
  await player.getByRole('button', { name: 'Change audio player size', exact: true }).press('End');
  await expect(player).toHaveAttribute('data-state', 'full');
}

async function chooseRecording(page, index = 0, mode = 'Play once') {
  if (await dock(page).getAttribute('data-state') === 'full') {
    await dock(page).getByRole('button', { name: 'Change audio player size', exact: true }).press('ArrowDown');
  }
  await page.locator('.recordings-play-glyph').nth(index).click();
  await page.getByRole('dialog', { name: 'Play recording', exact: true })
    .getByRole('button', { name: new RegExp(mode) }).click();
  await expect(dock(page)).toHaveAttribute('data-source', 'recordings');
}

async function expectReachableControls(player) {
  const controls = player.locator('button:visible, input:visible, select:visible');
  for (let index = 0; index < await controls.count(); index++) {
    const input = controls.nth(index);
    const control = await input.evaluate((element) => element.matches('.ui-toggle input')) ?
      input.locator('..') : input;
    await control.scrollIntoViewIfNeeded();
    const bounds = await control.evaluate((element) => {
      const rect = element.getBoundingClientRect();
      const hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
      return { x: rect.x, right: rect.right, top: rect.top, bottom: rect.bottom,
        width: rect.width, height: rect.height, viewportWidth: window.innerWidth,
        viewportHeight: window.innerHeight, covered: !hit || !element.contains(hit) };
    });
    expect(bounds.width).toBeGreaterThan(0);
    expect(bounds.height).toBeGreaterThan(0);
    expect(bounds.x).toBeGreaterThanOrEqual(-1);
    expect(bounds.right).toBeLessThanOrEqual(bounds.viewportWidth + 1);
    expect(bounds.top).toBeGreaterThanOrEqual(-1);
    expect(bounds.bottom).toBeLessThanOrEqual(bounds.viewportHeight + 1);
    expect(bounds.covered, `Control ${await control.getAttribute('aria-label') || await control.textContent()} is covered`)
      .toBe(false);
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
    await expect(player.getByRole('button', { name: 'Hold', exact: true })).toBeEnabled();
    for (const panel of ['Details', 'Queue', 'Scan Lists', 'Settings']) {
      await selectPanel(page, panel);
      await expectReachableControls(player);
    }
    await player.getByRole('button', { name: 'Change audio player size', exact: true }).press('Home');
    await expect(player).toHaveAttribute('data-state', 'collapsed');
    await expect(player.getByRole('slider')).toHaveCount(0);
    await expect(player.getByRole('tab')).toHaveCount(0);
    await expectReachableControls(player);
    await player.getByRole('button', { name: 'Change audio player size', exact: true }).click();
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
    await expect(player.getByRole('tab', { name: 'Scan Lists', exact: true })).toHaveCount(0);
    await expect(player.getByRole('tab', { name: 'Settings', exact: true })).toHaveCount(0);
    for (const panel of ['Details', 'Transcript', 'Queue']) {
      await selectPanel(page, panel);
      await expectReachableControls(player);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1);
  });
}

test('recording facts, transcript and entity links remain available in the full dock', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openAudioApp(page);
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
  await expect(player.locator('a[href*="view=radio-system"]')).toHaveCount(1);
  expect(await player.locator('a[href*="view=channel"]').count()).toBeGreaterThan(0);
  expect(await player.locator('a[href*="view=radio&"]').count()).toBeGreaterThan(0);
  expect(await player.locator('a[href*="view=group-identity"]').count()).toBeGreaterThan(0);
  await selectPanel(page, 'Transcript');
  await expect(player).toContainText(transcript.text);
  await expect(player).toContainText('Transcribed');
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
  const pager = page.getByRole('navigation', { name: 'Call result pages' });
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
  await dock(page).getByRole('button', { name: 'Live', exact: true }).click();
  await dock(page).getByRole('button', { name: 'Listen live', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.audioTest.liveSources.filter((source) => source.active).length))
    .toBe(1);
  await expect.poll(() => page.evaluate(() => window.audioTest.recordings.at(-1).action)).toBe('pause');
  await dock(page).getByRole('button', { name: 'Recordings', exact: true }).click();
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
  const burst = dock(page).getByRole('spinbutton', { name: 'Calls per target' });
  await burst.focus();
  state.feedCallsEnabled = true;
  await expect(dock(page).getByRole('button', { name: 'Hold', exact: true })).toBeEnabled();
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
  const clickedCallMode = dock(page).getByRole('combobox', { name: 'When a call is clicked' });
  await clickedCallMode.focus();
  await clickedCallMode.selectOption('once');
  await expect(clickedCallMode).toBeFocused();
  await expect(clickedCallMode).toHaveValue('once');
});

test('adding a recording to the queue preserves live playback and its selected source', async ({ page }) => {
  await openAudioApp(page);
  await fullControls(page);
  await dock(page).getByRole('button', { name: 'Listen live', exact: true }).click();
  await expect(dock(page).getByRole('button', { name: 'Hold', exact: true })).toBeEnabled();
  await dock(page).getByRole('button', { name: 'Change audio player size', exact: true }).press('ArrowDown');
  await page.locator('.recordings-play-glyph').first().click();
  await page.getByRole('dialog', { name: 'Play recording', exact: true })
    .getByRole('button', { name: /Add to queue/ }).click();
  await expect(dock(page)).toHaveAttribute('data-source', 'live');
  await expect(dock(page).getByRole('button', { name: 'Pause live audio', exact: true })).toBeEnabled();
  expect(await page.evaluate(() => window.audioTest.liveSources.filter((source) => source.active).length)).toBe(1);
  expect(await page.evaluate(() => window.audioTest.recordings.some((event) => event.action === 'play'))).toBe(false);
  await dock(page).getByRole('button', { name: 'Recordings', exact: true }).click();
  await fullControls(page);
  await selectPanel(page, 'Queue');
  await expect(dock(page).locator('.audio-dock-queue-row')).toContainText('Fire Dispatch');
});

for (const [name, capabilities, expected] of [
  ['live only', { recordings: false }, 'Live'],
  ['recordings only', { 'call-audio': false }, 'Recordings']
]) {
  test(`${name} access enables only the allowed source`, async ({ page }) => {
    await openAudioApp(page, { view: 'dashboard', capabilities });
    await expect(dock(page)).toBeVisible();
    await fullControls(page);
    await expect(dock(page).getByRole('button', { name: expected, exact: true })).toBeEnabled();
    await expect(dock(page).getByRole('button', { name: expected === 'Live' ? 'Recordings' : 'Live', exact: true }))
      .toBeDisabled();
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
  await expect(dock(page).getByRole('button', { name: 'Hold', exact: true })).toBeEnabled();
  await expect.poll(() => state.preferenceWrites.length).toBeGreaterThan(0);
  const volume = dock(page).getByRole('slider', { name: 'Volume', exact: true });
  await expect(volume).toHaveValue('0.65');
  const beforeMute = state.preferenceWrites.length;
  await dock(page).getByRole('button', { name: 'Mute audio', exact: true }).click();
  await expect(volume).toHaveValue('0');
  await dock(page).getByRole('button', { name: 'Unmute audio', exact: true }).click();
  await expect(volume).toHaveValue('0.65');
  await expect.poll(() => state.preferenceWrites.length).toBe(beforeMute + 2);
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

test('each source restores its own volume after muting and switching sources', async ({ page }) => {
  await openAudioApp(page, { volume: 0.65 });
  await fullControls(page);
  const player = dock(page);
  const volume = player.getByRole('slider', { name: 'Volume', exact: true });
  await expect(volume).toHaveValue('0.65');
  await player.getByRole('button', { name: 'Mute audio', exact: true }).click();
  await expect(volume).toHaveValue('0');
  await player.getByRole('button', { name: 'Recordings', exact: true }).click();
  await expect(volume).toHaveValue('1');
  await player.getByRole('button', { name: 'Mute audio', exact: true }).click();
  await expect(volume).toHaveValue('0');
  await player.getByRole('button', { name: 'Live', exact: true }).click();
  await player.getByRole('button', { name: 'Unmute audio', exact: true }).click();
  await expect(volume).toHaveValue('0.65');
  await player.getByRole('button', { name: 'Recordings', exact: true }).click();
  await player.getByRole('button', { name: 'Unmute audio', exact: true }).click();
  await expect(volume).toHaveValue('1');
});

test('repeated recordings retain separate queue row actions', async ({ page }) => {
  await openAudioApp(page);
  await chooseRecording(page);
  await chooseRecording(page, 0, 'Add to queue');
  await chooseRecording(page, 0, 'Add to queue');
  await fullControls(page);
  await selectPanel(page, 'Queue');
  const player = dock(page);
  await expect(player.locator('.audio-dock-queue-row')).toHaveCount(3);
  const remove = player.getByRole('button', { name: 'Remove Fire Dispatch from queue', exact: true });
  await expect(remove).toHaveCount(2);
  await remove.nth(1).click();
  await expect(player.locator('.audio-dock-queue-row')).toHaveCount(2);
  await expect(remove).toHaveCount(1);
  await player.getByRole('button', { name: 'Play Fire Dispatch', exact: true }).click();
  await expect(player.locator('.audio-dock-queue-row')).toHaveCount(1);
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
