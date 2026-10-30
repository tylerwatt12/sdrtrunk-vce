'use strict';

const { expect, test } = require('@playwright/test');
const { openAudioApp, recording, liveCall } = require('./fixtures/audio-dock-app.cjs');
const { selectAudioPanel } = require('./audio-dock-controls.cjs');

const dock = page => page.locator('#audio-dock');
const hero = page => dock(page).locator('.audio-dock-hero .audio-dock-title');

async function chooseRecording(page, mode = 'Play once') {
  await page.locator('.recordings-call-info').first().click();
  await page.getByRole('dialog', { name: 'Call actions', exact: true })
    .getByRole('button', { name: new RegExp(`^${mode}`) }).click();
  await expect(dock(page)).toHaveAttribute('data-source', 'recordings');
}

async function expectDestination(link, page, reference) {
  await expect(link).toHaveCount(1);
  expect(Object.fromEntries(new URL(await link.getAttribute('href'), page.url()).searchParams))
    .toEqual({ view: reference.kind === 'radio' ? 'radio' : 'group-identity',
      radio_system_key: reference.radio_system_key, identity_key: reference.identity_key });
}

test('player and queued talkgroups retain their own system destinations and separate queue inspection', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const otherSystem = { ...recording, id: 18, start_ms: recording.start_ms + 60_000,
    end_ms: recording.end_ms + 60_000, system_key: 'p25:00002:001', system_name: 'Neighbor Public Safety',
    target_entity_ref: { kind: 'talkgroup', radio_system_key: 'p25:00002:001',
      identity_key: 'v1-g-00002-001-1201' } };
  await openAudioApp(page, { calls: [recording, otherSystem] });
  await chooseRecording(page, 'Continue from here');

  // The compact title remains a normal expansion control.
  await dock(page).locator('.audio-dock-compact-copy').click();
  await expect(dock(page)).toHaveAttribute('data-state', 'full');
  await expectDestination(hero(page).getByRole('link'), page, recording.target_entity_ref);
  await expect(dock(page).locator('button a, a button')).toHaveCount(0);

  const queued = dock(page).locator('.audio-dock-up-next .audio-dock-queue-item');
  await expect(queued).toHaveCount(1);
  await expectDestination(queued.getByRole('link', { name: 'Fire Dispatch', exact: true }),
    page, otherSystem.target_entity_ref);
  const inspect = queued.getByRole('button', { name: 'View queued call Fire Dispatch', exact: true });
  await expect(inspect).toHaveAttribute('aria-pressed', 'false');
  const playbackBeforeInspection = await page.evaluate(() => window.audioTest.recordings.length);
  await inspect.click();
  await expect(inspect).toHaveAttribute('aria-pressed', 'true');
  await expectDestination(dock(page).locator('.audio-dock-queue-inspector')
    .getByRole('link', { name: 'Fire Dispatch', exact: true }), page, otherSystem.target_entity_ref);
  expect(await page.evaluate(() => window.audioTest.recordings.length)).toBe(playbackBeforeInspection);
  if (process.env.WEB_REVIEW_DIR) await page.screenshot({
    path: `${process.env.WEB_REVIEW_DIR}/audio-dock-entity-links.png` });

  await selectAudioPanel(dock(page), 'Queue');
  const queue = dock(page).locator('#audio-dock-panel .audio-dock-queue-item');
  await expect(queue).toHaveCount(2);
  await expectDestination(queue.first().getByRole('link', { name: 'Fire Dispatch', exact: true }),
    page, recording.target_entity_ref);
  await expectDestination(queue.last().getByRole('link', { name: 'Fire Dispatch', exact: true }),
    page, otherSystem.target_entity_ref);
});

test('player identity links respect Radio access while compact and slim controls still expand', async ({ page }) => {
  await openAudioApp(page, { capabilities: { radio: false }, calls: [recording] });
  await chooseRecording(page);
  await dock(page).locator('.audio-dock-compact-copy').click();
  await expect(dock(page)).toHaveAttribute('data-state', 'full');
  await expect(hero(page)).toHaveText('Fire Dispatch');
  await expect(hero(page).getByRole('link')).toHaveCount(0);
  await dock(page).locator('#audio-dock-size-handle').press('Home');
  await dock(page).locator('.audio-dock-slim-copy').click();
  await expect(dock(page)).toHaveAttribute('data-state', 'minimal');
  await expect(dock(page).locator('button a, a button')).toHaveCount(0);
});

test('player leaves a talkgroup without a stable destination readable', async ({ page }) => {
  await openAudioApp(page, { calls: [{ ...recording, target_entity_ref: null, channel_entity_ref: null }] });
  await chooseRecording(page);
  await dock(page).locator('.audio-dock-compact-copy').click();
  await expect(hero(page)).toHaveText('Fire Dispatch');
  await expect(hero(page).getByRole('link')).toHaveCount(0);
});

test('avoided talkgroups keep their detail destination after playback clears the call', async ({ page }) => {
  await openAudioApp(page, { feedCallsEnabled: false });
  let delivered = false;
  await page.route('**/api/v1/calls/feed*', route => {
    const calls = delivered ? [] : [{ ...liveCall, target_entity_ref: recording.target_entity_ref }];
    delivered = true;
    return route.fulfill({ json: { data: { cursor: '1', reset: false, calls } } });
  });
  await dock(page).locator('.audio-dock-compact-copy').click();
  await dock(page).getByRole('button', { name: 'Listen live', exact: true }).click();
  await expectDestination(hero(page).getByRole('link'), page, recording.target_entity_ref);
  await dock(page).getByRole('button', { name: 'Avoid current target', exact: true }).click();
  await selectAudioPanel(dock(page), 'Avoid List');
  const avoided = dock(page).locator('#audio-dock-panel .ui-record-card-copy');
  await expectDestination(avoided.getByRole('link'), page, recording.target_entity_ref);
  const remove = dock(page).getByRole('button', { name: /^Remove Fire Dispatch.*from Avoid List$/ });
  await expect(remove.locator('a')).toHaveCount(0);
  await remove.click();
  await expect(dock(page).locator('#audio-dock-panel')).toContainText('No avoided targets');
});
