'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function toneScreenshot(page, name, fullPage = true) {
  if (process.env.WEB_REVIEW_DIR) await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR, `${name}.png`), fullPage });
}

const radioSystemKey = 'p25:00001:001';
const encodedRadioSystemKey = encodeURIComponent(radioSystemKey);
const radioSystemPath = `/api/v1/radio-systems/${encodedRadioSystemKey}`;
const sourceIdentityKey = 'v1-r-00001-001-2808137';
const talkgroupIdentityKey = 'v1-g-00001-001-56131';

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

async function openActivity(page, options = {}) {
  const radioSystemKey = options.radioSystemKey || 'p25:00001:001';
  const encodedRadioSystemKey = encodeURIComponent(radioSystemKey);
  const radioSystemPath = `/api/v1/radio-systems/${encodedRadioSystemKey}`;
  let preferences = structuredClone(defaultPreferences);
  let preferenceRevision = 1;
  preferences.appearance.theme = options.theme || 'light';
  preferences.presentation.source_name_display = options.sourceMode || 'talker_alias';
  if (options.activityLayout) preferences.tables.activity = options.activityLayout;
  const requests = { activity: [], groups: [], radios: [], preferenceWrites: [] };
  const groupRows = [
    { identity_key: talkgroupIdentityKey, native_id: 56131, group_identity_kind: 'talkgroup',
      alias_name: 'CPD D3 DISP' },
    { identity_key: 'v1-p-00001-001-59001', native_id: 59001, group_identity_kind: 'patch_group',
      alias_name: 'Dispatch Patch' }
  ];
  const radioRows = [
    { identity_key: sourceIdentityKey, native_id: 2808137, alias_name: 'Engine 12',
      last_talker_alias: 'E12 Portable',
      presence: { evidence: 'affiliation', confirmed_at_ms: 1_790_720_000_000,
        channel: { protocol: 'P25', rfss: 1, site_id: 2, site_name: 'Lorain',
          name: 'North Simulcast Control',
          configuration_id: '11111111-1111-4111-8111-111111111111',
          entity_ref: { kind: 'channel', key: '11111111-1111-4111-8111-111111111111' } } } }
  ];
  const paged = (rows) => ({ data: rows, meta: { limit: 25, offset: 0,
    has_more: false, total_count: rows.length } });

  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const pathname = url.pathname;
    const respond = (data) => route.fulfill({ json: { data } });
    if (pathname === '/api/v1/auth/session') {
      await respond({ configured: true, authenticated: true, username: 'operator', tier: 'admin',
        primary: true, capabilities: { dashboard: true, radio: true, credits: true, recordings: options.recordings === true } });
    } else if (pathname === '/api/v1/me/preferences') {
      if (route.request().method() === 'PUT') {
        preferences = route.request().postDataJSON();
        preferenceRevision++;
        requests.preferenceWrites.push(preferences);
      }
      await route.fulfill({ json: { revision: preferenceRevision, preferences } });
    } else if (pathname === '/api/v1/status') {
      await respond({ stats_logging: { summary_configured: true, detailed_history_configured: true,
        summary_active: true, detailed_history_active: true, state: 'RUNNING' },
      database: { detailed_history_available: true } });
    } else if (pathname === '/api/v1/activity/message-types') {
      await respond([
        ['ACTION', 4, 'CALL', 'Call'], ['EVENT', 7, 'CALL_GROUP', 'Group call'],
        ['ACTION', 24, 'TONE', 'Tone'], ['EVENT', 62, 'TONE', 'Tone']
      ].map(([definition_kind, code, name, label]) => ({ definition_kind, code, name, label,
        p25: true, dmr: true, nxdn: true, sort_order: code })));
    } else if (pathname === '/api/v1/channel-catalog') {
      await respond({ revision: 1, channels: [] });
    } else if (pathname === radioSystemPath) {
      await respond({ radio_system_key: radioSystemKey, protocol: options.protocol || 'P25', wacn: 1, system_id: 1,
        system_name: 'Metro Public Safety', assignment_state: 'CURRENT',
        channel_names: 'Downtown · North', capabilities: { group_identities: true, radios: true,
          activity: true, talker_aliases: true, radio_channel_presence: true } });
    } else if (pathname === `${radioSystemPath}/group-identities`) {
      requests.groups.push(url.searchParams.get('q') || '');
      await route.fulfill({ json: paged(groupRows) });
    } else if (pathname === `${radioSystemPath}/radios`) {
      requests.radios.push(url.searchParams.get('q') || '');
      await route.fulfill({ json: paged(radioRows) });
    } else if (pathname === `${radioSystemPath}/channels`) {
      await route.fulfill({ json: paged([{ configuration_id: '11111111-1111-4111-8111-111111111111',
        site_name: 'Downtown', name: 'GCRCN' }]) });
    } else if (pathname === '/api/v1/activity') {
      requests.activity.push(Object.fromEntries(url.searchParams));
      const rows = options.rows || [{ id: 17, observed_at_ms: 1_790_720_000_000, action: 'CALL',
        event_type: 'CALL_GROUP', protocol: 'P25', source_radio_id: 2808137,
        source_identity_key: sourceIdentityKey, source_alias_name: 'Engine 12',
        source_talker_alias: 'E12 Portable', target_id: 56131,
        target_identity_key: talkgroupIdentityKey, target_kind: 'talkgroup',
        target_alias_name: 'CPD D3 DISP', name: 'GCRCN', frequency_hz: 855_737_500,
        lcn: '0-757', encrypted: false,
        configuration_id: '11111111-1111-4111-8111-111111111111' }];
      await respond({ rows: rows.filter(row => !url.searchParams.has('event_type') || String(row.event_type).toUpperCase() === url.searchParams.get('event_type')),
      has_more: false, next_before_id: null });
    } else {
      await route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
    }
  });

  await page.goto(`/app.html?view=radio-system&radio_system_key=${encodedRadioSystemKey}&tab=activity`);
  await expect(page.locator('.activity-filter-toolbar')).toBeVisible();
  return requests;
}

async function stubActivityAudio(page) {
  await page.addInitScript(() => {
    window.recordingPlays = [];
    class RecordingAudio extends EventTarget {
      constructor() { super(); this.paused = true; this.currentTime = 0; this.volume = 1; this._src = ''; window.recordingAudio = this; }
      get duration() { return this._src ? 42 : Number.NaN; }
      get src() { return this._src; }
      set src(value) { this._src = new URL(value, location.href).href; }
      play() { this.paused = false; window.recordingPlays.push(this.src); this.dispatchEvent(new Event('play')); return Promise.resolve(); }
      pause() { this.paused = true; this.dispatchEvent(new Event('pause')); }
      removeAttribute(name) { if (name === 'src') this._src = ''; }
      load() {}
    }
    window.Audio = RecordingAudio;
  });
}

async function showActivityColumns(page, ids) {
  await page.getByRole('button', { name: 'Choose table columns', exact: true }).click();
  const chooser = page.locator('.table-layout-panel:visible');
  for (const id of ids) await chooser.locator(`input[data-layout-column-id="${id}"]`).check();
  await page.keyboard.press('Escape');
}

function trackActivityRelationRequests(page) {
  const requests = [];
  page.on('request', request => {
    if (new URL(request.url()).pathname.startsWith('/api/v1/call-relations/activity/')) requests.push(request.url());
  });
  return requests;
}

test('Radio-system Site cells emphasize the unique channel name without confirmation dates', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openActivity(page);
  await page.goto(`/app.html?view=radio-system&radio_system_key=${encodedRadioSystemKey}&tab=radios`);

  await expect(page.locator('.page-subtitle')).not.toContainText('Current receiver assignment');
  await expect(page.locator('.page-subtitle')).not.toContainText('Assigned to a configured channel');
  await expect(page.getByText('Current receiver assignment', { exact: true })).toHaveCount(0);
  const table = page.getByRole('region', { name: 'Radios', exact: true }).getByRole('table');
  await expect(table.getByRole('link', { name: 'Site', exact: true })).toBeVisible();
  const site = table.locator('.identity-summary').filter({ hasText: 'North Simulcast Control' });
  await expect(site.locator('.identity-summary-primary')).toHaveText('North Simulcast Control');
  await expect(site.locator('.identity-summary-context')).toHaveText('RFSS 01 · Site 02 · Lorain');
  await expect(site).not.toContainText('Confirmed');
  await expect(site).not.toContainText('2026-');

  const target = new URL(await site.getByRole('link', { name: 'North Simulcast Control', exact: true })
    .getAttribute('href'), 'http://127.0.0.1:4173');
  expect(target.searchParams.get('view')).toBe('channel');
  expect(target.searchParams.get('configuration_id')).toBe('11111111-1111-4111-8111-111111111111');
});

function toneActivityRow(id, summary, overrides = {}) {
  return { id, observed_at_ms: 1_790_720_001_000, action: 'TONE', event_type: 'TONE', protocol: 'P25',
    source_radio_id: 2808137, source_identity_key: sourceIdentityKey, source_alias_name: 'Engine 12',
    target_id: 56131, target_identity_key: talkgroupIdentityKey, target_kind: 'talkgroup', target_alias_name: 'CPD D3 DISP',
    name: 'North Simulcast Control', frequency_hz: 855_737_500, lcn: '0-757', encrypted: false,
    configuration_id: '11111111-1111-4111-8111-111111111111',
    source_entity_ref: { kind: 'radio', radio_system_key: radioSystemKey, identity_key: sourceIdentityKey },
    target_entity_ref: { kind: 'talkgroup', radio_system_key: radioSystemKey, identity_key: talkgroupIdentityKey },
    entity_ref: { kind: 'channel', key: '11111111-1111-4111-8111-111111111111' },
    paging_tone_summary: summary, ...overrides };
}

for (const [theme, width] of [['light', 1440], ['dark', 390]]) {
  test(`Activity duration is shared, nullable, and sortable on ${theme} ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    const schema = ['time', 'action', 'event', 'tones', 'source', 'source-alias', 'source-ota-alias',
      'target', 'target-alias', 'channel', 'encryption', 'related'];
    await openActivity(page, { theme, rows: [
      toneActivityRow(17, undefined, { action: 'CALL', event_type: 'CALL_GROUP', duration_ms: 1250 }),
      toneActivityRow(18, undefined, { action: 'CALL', event_type: 'CALL_GROUP', duration_ms: null }),
      toneActivityRow(19, undefined, { action: 'CALL', event_type: 'CALL_GROUP', duration_ms: 30_000, encrypted: true }),
      toneActivityRow(20, undefined, { action: 'RESPONSE', event_type: 'RESPONSE', duration_ms: 1000 }),
      toneActivityRow(21, undefined, { action: 'CALL', event_type: 'CALL_GROUP', duration_ms: 0 })
    ], activityLayout: { schema, column_order: ['time', 'event', 'action', ...schema.slice(3)],
      column_widths: { time: 112 }, hidden_columns: ['tones', 'source-ota-alias'], collapsed_groups: [] } });
    const table = page.locator('table[data-table-type="activity"]');
    await expect(table.locator('thead th').first()).toHaveAttribute('data-column', 'time');
    await expect(table.locator('thead th').nth(1)).toHaveAttribute('data-column', 'duration');
    await expect(table.locator('thead th').nth(2)).toHaveAttribute('data-column', 'event');
    await expect(table.locator('thead th[data-column="tones"]')).toHaveCount(0);
    await expect(table.locator('col').first()).toHaveAttribute('style', 'width: 112px;');
    await expect(table.locator('tbody td[data-column="duration"]')).toHaveText(['1.3s', '', '30s', '', '0s']);
    await table.getByRole('button', { name: 'Duration', exact: true }).click();
    expect(await table.locator('tbody tr').evaluateAll((rows) => rows.map((row) => row.dataset.id)))
      .toEqual(['21', '17', '19', '18', '20']);
    await expect(table.locator('th[data-column="duration"]')).toHaveAttribute('aria-sort', 'ascending');
    await toneScreenshot(page, `activity-duration-${theme}-${width}`);
  });
}

for (const [recordings, theme, width] of [[true, 'light', 1440], [false, 'dark', 390]]) {
  test(`Activity Play follows actual recording availability with permission ${recordings}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    const relationRequests = trackActivityRelationRequests(page);
    const call = { action: 'CALL', event_type: 'CALL_GROUP', has_call_source: true };
    const tones = { source: 'encoded', method: 'ambe_tone_v1',
      tones: [{ frequencies_hz: [600], code: 'TONE_600', duration_milliseconds: 120 }] };
    await openActivity(page, { recordings, theme, rows: [
      toneActivityRow(40, tones, { ...call, duration_ms: 2_000, has_recording: false, encrypted: true }),
      toneActivityRow(41, undefined, { ...call, duration_ms: null, has_recording: true }),
      toneActivityRow(42, undefined, { ...call, duration_ms: 2_000 }),
      toneActivityRow(43, undefined, { ...call, duration_ms: 2_000, has_recording: null }),
      toneActivityRow(44, undefined, { ...call, duration_ms: 2_000, has_recording: 'true' }),
      toneActivityRow(45, undefined, { ...call, duration_ms: null, has_call_source: false, has_recording: true }),
      toneActivityRow(46, undefined, { ...call, duration_ms: 0, has_recording: true })
    ] });
    const table = page.locator('table[data-table-type="activity"]');
    await expect(table.locator('tr[data-id="40"] [data-column="duration"]')).toHaveText('2.0s');
    await expect(table.locator('tr[data-id="41"] [data-column="duration"]')).toBeEmpty();
    await expect(table.getByRole('button', { name: 'Play recording', exact: true })).toHaveCount(recordings ? 2 : 0);
    expect(await table.getByRole('button', { name: 'Play recording', exact: true })
      .evaluateAll(buttons => buttons.map(button => button.closest('tr').dataset.id)))
      .toEqual(recordings ? ['41', '46'] : []);
    const unavailable = table.locator('tr[data-id="40"] [data-column="related"]');
    await expect(unavailable.getByRole('img', { name: 'Tones', exact: true })).toBeVisible();
    await expect(unavailable.getByRole('img', { name: 'Encrypted', exact: true })).toBeVisible();
    expect(relationRequests).toHaveLength(0);
    await toneScreenshot(page, `activity-availability-${theme}-${width}`, false);
  });
}

test('Activity refresh adds and removes Play when recording availability changes', async ({ page }) => {
  const relationRequests = trackActivityRelationRequests(page);
  const activity = toneActivityRow(47, undefined, { action: 'CALL', event_type: 'CALL_GROUP', duration_ms: 8_000,
    has_call_source: true, has_recording: false });
  const requests = await openActivity(page, { recordings: true, rows: [activity] });
  const row = page.locator('tr[data-id="47"]');
  const play = row.getByRole('button', { name: 'Play recording', exact: true });
  await expect(play).toHaveCount(0);
  activity.has_recording = true;
  await page.getByRole('button', { name: 'Pause refresh', exact: true }).click();
  await page.getByRole('button', { name: 'Resume refresh', exact: true }).click();
  await expect(play).toBeVisible();
  await expect(row.locator('[data-column="duration"]')).toHaveText('8.0s');
  activity.has_recording = false;
  await page.getByRole('button', { name: 'Pause refresh', exact: true }).click();
  await page.getByRole('button', { name: 'Resume refresh', exact: true }).click();
  await expect(play).toHaveCount(0);
  await expect(row.locator('[data-column="duration"]')).toHaveText('8.0s');
  expect(requests.activity).toHaveLength(3);
  expect(relationRequests).toHaveLength(0);
});

for (const [theme, width] of [['light', 1440], ['dark', 390], ['light', 320]]) {
  test(`Activity plays exact recording parts directly on ${theme} ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    await stubActivityAudio(page);
    const relationRequests = trackActivityRelationRequests(page);
    const activity = toneActivityRow(18, undefined, { action: 'CALL', event_type: 'CALL_GROUP', has_call_source: true, has_recording: true,
      system_name: 'Metro Public Safety' });
    await openActivity(page, { theme, recordings: true, rows: [activity] });
    const recordings = [
      { id: 72, start_ms: activity.observed_at_ms + 41_400, duration_ms: 10_000, group_alias: 'CPD D3 DISP' },
      { id: 71, start_ms: activity.observed_at_ms, duration_ms: 41_400, group_alias: 'CPD D3 DISP' }
    ];
    await page.route('**/api/v1/call-relations/activity/18', route => route.fulfill({ json: { data: {
      activity_rows: [activity, { ...activity, id: 19, name: 'South Simulcast Control' }],
      recordings: [...recordings, recordings[1]], alerts: []
    } } }));
    await page.route('**/api/v1/recordings/calls/*', route => {
      const id = Number(new URL(route.request().url()).pathname.split('/').at(-1));
      return route.fulfill({ json: { data: { call: recordings.find(recording => recording.id === id) } } });
    });
    const row = page.locator('tr[data-id="18"]');
    const play = row.getByRole('button', { name: 'Play recording', exact: true });
    await expect(play).toBeVisible();
    expect(relationRequests).toHaveLength(0);
    await play.click();
    await expect.poll(() => page.evaluate(() => window.recordingPlays)).toEqual([
      expect.stringMatching(/calls\/71\/audio$/)
    ]);
    await expect(page.getByRole('dialog', { name: 'Call details', exact: true })).toHaveCount(0);
    expect(relationRequests).toHaveLength(1);
    expect(new URL(relationRequests[0]).pathname).toBe('/api/v1/call-relations/activity/18');
    await page.evaluate(() => window.recordingAudio.dispatchEvent(new Event('ended')));
    await expect.poll(() => page.evaluate(() => window.recordingPlays)).toEqual([
      expect.stringMatching(/calls\/71\/audio$/), expect.stringMatching(/calls\/72\/audio$/)
    ]);
    await page.evaluate(() => window.recordingAudio.dispatchEvent(new Event('ended')));
    await expect.poll(() => page.evaluate(() => window.recordingAudio.paused)).toBe(true);
    expect(await page.evaluate(() => window.recordingPlays.length)).toBe(2);
    expect(relationRequests).toHaveLength(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await toneScreenshot(page, `activity-direct-play-${theme}-${width}`, false);
  });
}

test('Activity missing and failed recordings stay in the row and can retry without a dialog', async ({ page }) => {
  await stubActivityAudio(page);
  const relationRequests = trackActivityRelationRequests(page);
  const activity = toneActivityRow(18, undefined, { action: 'CALL', event_type: 'CALL_GROUP', has_call_source: true, has_recording: true });
  await openActivity(page, { recordings: true, rows: [activity] });
  await page.route('**/api/v1/call-relations/activity/18', route => {
    if (relationRequests.length === 1) return route.fulfill({ status: 503, json: { error: { message: 'Unavailable' } } });
    return route.fulfill({ json: { data: { activity_rows: [], recordings: relationRequests.length > 2 ? [
      { id: 71, start_ms: activity.observed_at_ms, duration_ms: 41_400, group_alias: 'CPD D3 DISP' }
    ] : [], alerts: [] } } });
  });
  const row = page.locator('tr[data-id="18"]');
  const play = row.getByRole('button', { name: 'Play recording', exact: true });
  expect(relationRequests).toHaveLength(0);
  await play.click();
  await expect(row.getByRole('status')).toHaveText('Recording could not be loaded. Try again.');
  await expect(play).toBeEnabled();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await play.click();
  await expect(row.getByRole('status')).toHaveText('No recording available.');
  expect(await page.evaluate(() => window.recordingPlays)).toEqual([]);
  await play.click();
  await expect.poll(() => page.evaluate(() => window.recordingPlays.length)).toBe(1);
  await expect(row.getByRole('status')).toBeHidden();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(relationRequests).toHaveLength(3);
});

test('a newer Activity play selection wins over an earlier lookup still in flight', async ({ page }) => {
  await stubActivityAudio(page);
  const relationRequests = trackActivityRelationRequests(page);
  await openActivity(page, { recordings: true, rows: [18, 19].map(id => toneActivityRow(id, undefined,
    { action: 'CALL', event_type: 'CALL_GROUP', has_call_source: true, has_recording: true })) });
  let releaseFirst;
  await page.route('**/api/v1/call-relations/activity/18', route => new Promise(resolve => {
    releaseFirst = async () => {
      await route.fulfill({ json: { data: { activity_rows: [], alerts: [], recordings: [
        { id: 71, start_ms: 1000, duration_ms: 10_000 }
      ] } } }).catch(() => {});
      resolve();
    };
  }));
  await page.route('**/api/v1/call-relations/activity/19', route => route.fulfill({ json: { data: {
    activity_rows: [], alerts: [], recordings: [{ id: 72, start_ms: 2000, duration_ms: 10_000 }]
  } } }));
  await page.locator('tr[data-id="18"]').getByRole('button', { name: 'Play recording', exact: true }).click();
  await expect.poll(() => Boolean(releaseFirst)).toBe(true);
  await page.locator('tr[data-id="19"]').getByRole('button', { name: 'Play recording', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.recordingPlays)).toEqual([
    expect.stringMatching(/calls\/72\/audio$/)
  ]);
  await releaseFirst();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  expect(await page.evaluate(() => window.recordingPlays)).toEqual([expect.stringMatching(/calls\/72\/audio$/)]);
  expect(relationRequests).toHaveLength(2);
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('Stop in the audio dock cancels an Activity play lookup still in flight', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await stubActivityAudio(page);
  await openActivity(page, { recordings: true, rows: [18, 19].map(id => toneActivityRow(id, undefined,
    { action: 'CALL', event_type: 'CALL_GROUP', has_call_source: true, has_recording: true })) });
  await page.route('**/api/v1/call-relations/activity/18', route => route.fulfill({ json: { data: {
    activity_rows: [], alerts: [], recordings: [{ id: 71, start_ms: 1000, duration_ms: 10_000 }]
  } } }));
  let releaseLookup;
  await page.route('**/api/v1/call-relations/activity/19', route => new Promise(resolve => {
    releaseLookup = async () => {
      await route.fulfill({ json: { data: { activity_rows: [], alerts: [], recordings: [
        { id: 72, start_ms: 2000, duration_ms: 10_000 }
      ] } } }).catch(() => {});
      resolve();
    };
  }));
  await page.locator('tr[data-id="18"]').getByRole('button', { name: 'Play recording', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.recordingPlays.length)).toBe(1);
  await page.locator('tr[data-id="19"]').getByRole('button', { name: 'Play recording', exact: true }).click();
  await expect.poll(() => Boolean(releaseLookup)).toBe(true);
  const dock = page.locator('#audio-dock');
  await dock.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible').press('End');
  await dock.getByRole('button', { name: 'Stop recording', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.recordingAudio.paused)).toBe(true);
  await releaseLookup();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  expect(await page.evaluate(() => window.recordingPlays)).toEqual([expect.stringMatching(/calls\/71\/audio$/)]);
  expect(await page.evaluate(() => window.recordingAudio.src)).toBe('');
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

for (const [recordings, theme, width] of [[true, 'light', 1440], [false, 'dark', 390], [true, 'light', 320]]) {
  test(`Activity indicators keep tone and encryption detail with recording permission ${recordings} on ${theme} ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const relationRequests = trackActivityRelationRequests(page);
    const summary = { source: 'decoded_audio', method: 'p25_pcm_ambe_tone_v2', phase: 1,
      tones: [{ frequencies_hz: [593.75], duration_milliseconds: 1020, offset_milliseconds: 80 }] };
    const requests = await openActivity(page, { recordings, theme, rows: [
      toneActivityRow(18, summary, { action: 'CALL', event_type: 'CALL_GROUP', has_call_source: true, has_recording: true,
        encrypted: true, encryption_display: 'AES-256', encryption_full_display: 'AES-256 · Key ID 0042' }),
      toneActivityRow(19, undefined, { action: 'CALL', event_type: 'CALL_GROUP', has_call_source: false })
    ] });
    const table = page.locator('table[data-table-type="activity"]');
    await expect(table.locator('th[data-column="tones"], th[data-column="encryption"]')).toHaveCount(0);
    const row = table.locator('tr[data-id="18"]');
    const icons = row.locator('[data-column="related"] .activity-call-icons');
    await expect(icons.getByRole('button', { name: 'Play recording', exact: true })).toHaveCount(recordings ? 1 : 0);
    const tones = icons.getByRole('img', { name: 'Tones', exact: true });
    const encrypted = icons.getByRole('img', { name: 'Encrypted', exact: true });
    await expect(tones).toBeVisible();
    await expect(encrypted).toBeVisible();
    await expect(tones).toHaveAttribute('data-ui-hint', /593\.75 Hz.*1020 ms.*80 ms/);
    await expect(encrypted).toHaveAttribute('data-ui-hint', /AES-256.*0042/);
    await expect(tones).toHaveAccessibleDescription(/593\.75 Hz.*1020 ms.*80 ms/);
    await expect(encrypted).toHaveAccessibleDescription(/AES-256.*0042/);
    await tones.scrollIntoViewIfNeeded();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await tones.hover();
    await expect(page.getByRole('tooltip')).toContainText('593.75 Hz');
    await encrypted.focus();
    await page.keyboard.press('Shift+Tab');
    await expect(tones).toBeFocused();
    await expect(page.getByRole('tooltip')).toContainText('593.75 Hz');
    await page.keyboard.press('Tab');
    await expect(encrypted).toBeFocused();
    await expect(page.getByRole('tooltip')).toContainText('Key ID 0042');
    await toneScreenshot(page, `activity-indicators-${theme}-${width}`, false);
    await expect(table.locator('tr[data-id="19"] [data-column="related"]')
      .locator('button, [role="img"]')).toHaveCount(0);
    expect(relationRequests).toHaveLength(0);
    await showActivityColumns(page, ['tones', 'encryption']);
    await expect(table.locator('th[data-column="tones"]')).toHaveCount(1);
    await expect(table.locator('th[data-column="encryption"]')).toHaveCount(1);
    await expect(row.locator('[data-column="tones"]')).toContainText('593.75 Hz');
    await expect(row.locator('[data-column="encryption"]')).toContainText('AES-256');
    await expect.poll(() => requests.preferenceWrites.at(-1)?.tables.activity?.hidden_columns
      .filter(id => ['tones', 'encryption'].includes(id))).toEqual([]);
    await page.reload();
    await expect(table.locator('th[data-column="tones"]')).toHaveCount(1);
    await expect(table.locator('th[data-column="encryption"]')).toHaveCount(1);
    expect(relationRequests).toHaveLength(0);
  });
}

test('call tones preserve group call types and filter independently with alias context', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const requests = await openActivity(page, { rows: [
    toneActivityRow(18, { source: 'decoded_audio', method: 'p25_pcm_ambe_tone_v2', phase: 1, call_started_at_ms: 1_790_720_000_000,
      tones: [{ frequencies_hz: [593.75], duration_milliseconds: 1020, offset_milliseconds: 80, confidence: .99 }] }, { action: 'CALL', event_type: 'CALL_GROUP' }),
    toneActivityRow(19, { source: 'encoded', method: 'ambe_tone_v1', phase: 2, call_started_at_ms: 1_790_720_000_000,
      tones: [{ frequencies_hz: [600], code: 'TONE_600', duration_milliseconds: 120 },
        { frequencies_hz: [700], code: 'TONE_700', duration_milliseconds: 240 }] }),
    toneActivityRow(20, undefined, { source_alias_name: '', source_radio_id: 999, source_identity_key: '', source_entity_ref: null })
  ] });
  await showActivityColumns(page, ['tones']);
  const row = page.locator('tr[data-id="18"]');
  await expect(row.locator('[data-column="event"]')).toHaveText('Group call');
  await expect(row.locator('[data-column="action"]')).toHaveText('Call');
  await expect(row.locator('[data-column="tones"]')).toContainText('Audio detection');
  await expect(row.locator('[data-column="tones"]')).toContainText('593.75 Hz · 1020 ms · at 80 ms');
  await expect(row.locator('[data-column="event"]')).not.toContainText('measured');
  await expect(row.locator('[data-column="event"]')).not.toContainText('mapped');
  await expect(row.locator('[data-column="event"]')).not.toContainText('[object');
  await expect(row).toContainText('Engine 12'); await expect(row).toContainText('CPD D3 DISP');
  await expect(row.locator('[data-column="channel"]')).toContainText('855.73750 MHz');
  await expect(page.locator('tr[data-id="19"] [data-column="tones"]')).toContainText('Encoded');
  await expect(page.locator('tr[data-id="19"] [data-column="tones"]')).toContainText('TONE 600 · 600 Hz · 120 ms');
  await expect(page.locator('tr[data-id="19"] [data-column="tones"]')).toContainText('TONE 700 · 700 Hz · 240 ms');
  await expect(page.locator('tr[data-id="20"] [data-column="event"]')).toHaveText('Tone');
  await expect(row.locator('[data-column="event"]')).not.toContainText('99%');
  await expect(row.locator('[data-column="event"]')).not.toContainText('p25_pcm_tone_v1');
  await toneScreenshot(page, 'activity-tones-light-desktop');
  await row.locator('[data-column="event"] .activity-cell-filter-link').click();
  await expect.poll(() => requests.activity.at(-1)?.event_type).toBe('CALL_GROUP');
  await row.locator('[data-column="tones"] .activity-cell-filter-link').click();
  await expect.poll(() => requests.activity.at(-1)?.has_tones).toBe('true');
  await expect.poll(() => requests.activity.at(-1)?.event_type).toBe('CALL_GROUP');
  await expect(page.locator('.activity-filter-chips')).toContainText('With tones');
  await page.getByRole('button', { name: 'Clear filters', exact: true }).click();
  await expect.poll(() => new URL(page.url()).searchParams.get('activity_has_tones')).toBe(null);
  await expect.poll(() => requests.activity.at(-1)?.has_tones).toBeUndefined();
  await expect(page.locator('.activity-filter-chips')).not.toContainText('With tones');
  const source = page.locator('tr[data-id="18"] [data-column="source"] .activity-cell-action-link');
  await source.click();
  await expect(page.getByRole('link', { name: 'Open source radio details', exact: true })).toBeVisible();
});

test('paging tones keep DMR encoded dual frequencies and code readable on a dark phone', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openActivity(page, { protocol: 'DMR', radioSystemKey: 'dmr:channel:fixture', theme: 'dark', rows: [
    toneActivityRow(21, { source: 'encoded', method: 'ambe_tone_v1', call_started_at_ms: 1_790_720_000_000,
      tones: [{ frequencies_hz: [697, 1477], code: 'DTMF_3', duration_milliseconds: 120 }] },
      { protocol: 'DMR', source_alias_name: 'Depot Portable', target_alias_name: 'Depot Dispatch', timeslot: 2 })
  ] });
  await showActivityColumns(page, ['tones']);
  const row = page.locator('tr[data-id="21"]');
  await expect(row.locator('[data-column="tones"]')).toContainText('Encoded');
  await expect(row.locator('[data-column="tones"]')).toContainText('DTMF 3 · 697 + 1477 Hz · 120 ms');
  await expect(row.locator('[data-column="tones"]')).not.toContainText('[object');
  await expect(row).toContainText('Depot Portable'); await expect(row).toContainText('Depot Dispatch');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await row.locator('[data-column="tones"]').evaluate(cell => cell.scrollIntoView({ inline: 'center', block: 'nearest' }));
  await page.evaluate(() => window.scrollTo(0, 0));
  await toneScreenshot(page, 'activity-tones-dark-phone');
});

test('P25 Activity provides compact identity-first filters in the actual application', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const requests = await openActivity(page);
  const toolbar = page.locator('.activity-filter-toolbar');
  const primary = toolbar.locator('.activity-filter-primary');

  await expect(toolbar.locator(
    'label.activity-filter-field:has(> .ui-field-label:text-is("Time")) select')).toHaveValue('all');
  const action = toolbar.locator(
    'label.activity-filter-field:has(> .ui-field-label:text-is("Action")) select');
  await expect(action).toContainText('All actions (grants hidden)');
  await expect(action).toContainText('All actions (including grants)');
  await expect(primary.getByText('Include grants', { exact: true })).toHaveCount(0);
  await expect(primary.getByRole('button', { name: 'Filters', exact: true })).toBeVisible();
  await expect(primary.getByRole('button', { name: 'Apply filters', exact: true })).toBeVisible();

  const sourceTrigger = toolbar.locator('.activity-filter-picker-source .activity-filter-picker-trigger');
  await sourceTrigger.click();
  const sourcePicker = page.getByRole('dialog', { name: 'Choose a source radio' });
  await sourcePicker.locator('input[type="search"]').fill('Engine 12');
  await expect(sourcePicker.locator(`.activity-identity-result[data-identity-key="${sourceIdentityKey}"]`))
    .toContainText('2808137');
  await expect(sourcePicker.locator(`.activity-identity-result[data-identity-key="${sourceIdentityKey}"]`))
    .toContainText('E12 Portable');
  await sourcePicker.locator(`.activity-identity-result[data-identity-key="${sourceIdentityKey}"]`).click();

  const destinationTrigger = toolbar.locator(
    '.activity-filter-picker-destination .activity-filter-picker-trigger');
  await destinationTrigger.click();
  const destinationPicker = page.getByRole('dialog', { name: 'Choose a destination' });
  await destinationPicker.locator('input[type="search"]').fill('disp');
  const identityResults = destinationPicker.locator('.activity-identity-result[data-identity-key]');
  await expect(identityResults).toHaveCount(3);
  await expect(identityResults.filter({ hasText: 'CPD D3 DISP' })).toContainText('56131');
  await expect(identityResults.filter({ hasText: 'Engine 12' })).toContainText('2808137');
  await expect(identityResults.filter({ hasText: 'Dispatch Patch' })).toContainText('59001');

  await destinationPicker.getByRole('button', { name: 'Talkgroups', exact: true }).click();
  await expect(identityResults).toHaveCount(1);
  await expect(identityResults.first().locator('.activity-identity-result-kind')).toHaveText('Talkgroup');
  await destinationPicker.getByRole('button', { name: 'Radios', exact: true }).click();
  await expect(identityResults).toHaveCount(1);
  await expect(identityResults.first().locator('.activity-identity-result-kind')).toHaveText('Radio');
  await destinationPicker.getByRole('button', { name: 'Patches', exact: true }).click();
  await expect(identityResults).toHaveCount(1);
  await expect(identityResults.first().locator('.activity-identity-result-kind')).toHaveText('Patch');
  await destinationPicker.getByRole('button', { name: 'All', exact: true }).click();
  await identityResults.filter({ hasText: 'CPD D3 DISP' }).click();

  await action.selectOption('__include_grants__');
  await primary.getByRole('button', { name: 'Filters', exact: true }).click();
  const more = page.locator('.activity-filter-more-panel');
  await expect(more.locator('.activity-filter-group-title')).toHaveText([
    'Scope', 'Event details', 'Technical'
  ]);
  await expect(more.locator('.ui-field-label:text-is("Channel / site")')).toBeVisible();
  await expect(more.locator('.ui-field-label:text-is("Event subtype")')).toBeVisible();
  await expect(more.locator('.ui-field-label:text-is("Frequency (MHz)")')).toBeVisible();
  await expect(more.locator('.ui-field-label:text-is("LCN")')).toBeVisible();
  await expect(more.getByText('Timeslot', { exact: true })).toHaveCount(0);
  await more.locator(
    'label.activity-filter-field:has(> .ui-field-label:text-is("Encryption")) select')
    .selectOption('encrypted');
  await primary.getByRole('button', { name: 'Apply filters', exact: true }).click();

  await expect.poll(() => new URL(page.url()).searchParams.get('activity_include_grants')).toBe('true');
  await expect.poll(() => new URL(page.url()).searchParams.get('activity_target_identity_key'))
    .toBe(talkgroupIdentityKey);
  const chips = page.locator('.activity-filter-chips');
  await expect(chips).toContainText('Action:Grants included');
  await expect(chips).toContainText('Source:2808137 · E12 Portable');
  await expect(chips).toContainText('Destination:Talkgroup56131 · CPD D3 DISP');
  await expect(chips).toContainText('Encrypted only');
  await expect(chips.locator('.activity-filter-chip-label')).toHaveText([
    'Action:', 'Source:', 'Destination:'
  ]);
  expect(await chips.locator('.activity-filter-chip-label').evaluateAll((labels) => labels.every((label) =>
    label.scrollWidth <= label.clientWidth))).toBe(true);
  await expect.poll(() => requests.radios.includes('Engine 12')).toBe(true);
  await expect.poll(() => requests.radios.includes('disp')).toBe(true);
  await expect.poll(() => requests.groups.includes('disp')).toBe(true);
  await expect.poll(() => requests.activity.at(-1)?.hide_grants).toBe('false');
  await expect(toolbar).toHaveScreenshot('activity-shared-filters-desktop.png');
});

test('P25 Activity Filters is a usable mobile sheet and the table stays contained', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openActivity(page);
  const toolbar = page.locator('.activity-filter-toolbar');
  const toolbarBox = await toolbar.boundingBox();
  expect(toolbarBox.x).toBeGreaterThanOrEqual(0);
  expect(toolbarBox.x + toolbarBox.width).toBeLessThanOrEqual(390);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);

  await toolbar.getByRole('button', { name: 'Filters', exact: true }).click();
  const more = page.getByRole('dialog', { name: 'Filters' });
  await expect(more).toBeVisible();
  const panelBox = await more.boundingBox();
  expect(panelBox.x).toBeGreaterThanOrEqual(0);
  expect(panelBox.x + panelBox.width).toBeLessThanOrEqual(390);
  expect(panelBox.y).toBeGreaterThanOrEqual(0);
  expect(panelBox.y + panelBox.height).toBeLessThanOrEqual(844);
  await expect(more.locator('.activity-filter-group-title')).toHaveText([
    'Scope', 'Event details', 'Technical'
  ]);
  await expect(more.getByText('Timeslot', { exact: true })).toHaveCount(0);
  const apply = more.getByRole('button', { name: 'Apply filters', exact: true });
  await expect(apply).toBeVisible();
  const applyBox = await apply.boundingBox();
  expect(applyBox.y + applyBox.height).toBeLessThanOrEqual(844);
  await expect(more).toHaveScreenshot('activity-shared-filters-phone.png');
  const encryption = more.locator(
    'label.activity-filter-field:has(> .ui-field-label:text-is("Encryption")) select');
  const lcn = more.getByRole('textbox', { name: 'LCN', exact: true });
  await lcn.fill('invalid');
  await apply.click();
  await expect(more).toBeVisible();
  await expect(lcn).toHaveValue('invalid');
  await lcn.fill('');
  await encryption.selectOption('encrypted');
  await more.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(new URL(page.url()).searchParams.get('activity_encryption')).toBe(null);
  await expect(toolbar.getByRole('button', { name: 'Filters', exact: true })).toBeFocused();
  await toolbar.getByRole('button', { name: 'Filters', exact: true }).click();
  await expect(encryption).toHaveValue('encrypted');
  await apply.click();
  await expect.poll(() => new URL(page.url()).searchParams.get('activity_encryption')).toBe('encrypted');
});

test('Destination identity results stay contained and scrollable after async search', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openActivity(page);

  const groups = Array.from({ length: 25 }, (_, index) => ({
    identity_key: `v1-g-00001-001-${56000 + index}`,
    native_id: 56000 + index,
    group_identity_kind: 'talkgroup',
    alias_name: `Dispatch talkgroup ${String(index + 1).padStart(2, '0')}`
  }));
  const radios = Array.from({ length: 25 }, (_, index) => ({
    identity_key: `v1-r-00001-001-${2808000 + index}`,
    native_id: 2808000 + index,
    alias_name: `Dispatch radio ${String(index + 1).padStart(2, '0')}`
  }));
  const paged = (rows) => ({ data: rows, meta: { limit: 25, offset: 0,
    has_more: false, total_count: rows.length } });

  await page.route(`**${radioSystemPath}/group-identities**`, (route) =>
    route.fulfill({ json: paged(groups) }));
  await page.route(`**${radioSystemPath}/radios**`, (route) =>
    route.fulfill({ json: paged(radios) }));

  const toolbar = page.locator('.activity-filter-toolbar');
  await toolbar.locator('.activity-filter-picker-destination .activity-filter-picker-trigger').click();
  const picker = page.getByRole('dialog', { name: 'Choose a destination' });
  await picker.locator('input[type="search"]').fill('dispatch');
  const results = picker.locator('.activity-identity-results');
  await expect(results.locator('.activity-identity-result[data-identity-key]')).toHaveCount(50);
  await expect.poll(() => results.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
  const initialPickerBox = await picker.boundingBox();
  expect(initialPickerBox.y).toBeGreaterThanOrEqual(7);
  expect(initialPickerBox.y + initialPickerBox.height).toBeLessThanOrEqual(837);

  await results.evaluate((element) => { element.scrollTop = 560; });
  await expect.poll(() => results.evaluate((element) => element.scrollTop)).toBeGreaterThan(100);
  await page.waitForTimeout(500);
  const scrolledPickerBox = await picker.boundingBox();
  expect(Math.abs(scrolledPickerBox.y - initialPickerBox.y)).toBeLessThanOrEqual(1);
});

test('Destination selections filter requested activity and rendered rows by identity or type', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openActivity(page);

  const talkgroupKey = 'v1-g-00001-001-56131';
  const patchKey = 'v1-p-00001-001-59001';
  const radioKey = 'v1-r-00001-001-2808137';
  const groupRows = [
    { identity_key: talkgroupKey, native_id: 56131, group_identity_kind: 'talkgroup',
      alias_name: 'Alpha Dispatch' },
    { identity_key: patchKey, native_id: 59001, group_identity_kind: 'patch_group',
      alias_name: 'Bravo Patch' }
  ];
  const radioRows = [
    { identity_key: radioKey, native_id: 2808137, alias_name: 'Charlie Portable' }
  ];
  const activityRows = [
    { id: 101, observed_at_ms: 1_790_720_000_101, action: 'CALL', event_type: 'CALL_GROUP',
      protocol: 'P25', source_radio_id: 1001, target_id: 56131,
      target_identity_key: talkgroupKey, target_kind: 'talkgroup', target_alias_name: 'Alpha Dispatch',
      name: 'GCRCN', frequency_hz: 855_737_500, lcn: '0-757', encrypted: false },
    { id: 102, observed_at_ms: 1_790_720_000_102, action: 'CALL', event_type: 'CALL_GROUP',
      protocol: 'P25', source_radio_id: 1002, target_id: 59001,
      target_identity_key: patchKey, target_kind: 'patch_group', target_alias_name: 'Bravo Patch',
      name: 'GCRCN', frequency_hz: 855_737_500, lcn: '0-757', encrypted: false },
    { id: 103, observed_at_ms: 1_790_720_000_103, action: 'CALL', event_type: 'CALL_UNIT_TO_UNIT',
      protocol: 'P25', source_radio_id: 1003, target_id: 2808137,
      target_identity_key: radioKey, target_kind: 'radio', target_alias_name: 'Charlie Portable',
      name: 'GCRCN', frequency_hz: 855_737_500, lcn: '0-757', encrypted: false }
  ];
  const paged = (rows) => ({ data: rows, meta: { limit: 25, offset: 0,
    has_more: false, total_count: rows.length } });
  const activityRequests = [];

  await page.route(`**${radioSystemPath}/group-identities**`, (route) =>
    route.fulfill({ json: paged(groupRows) }));
  await page.route(`**${radioSystemPath}/radios**`, (route) =>
    route.fulfill({ json: paged(radioRows) }));
  await page.route('**/api/v1/activity**', (route) => {
    const url = new URL(route.request().url());
    const parameters = Object.fromEntries(url.searchParams);
    activityRequests.push(parameters);
    const identityKey = parameters.target_identity_key || '';
    const kind = parameters.target_kind || '';
    const rows = activityRows.filter((row) => (!identityKey || row.target_identity_key === identityKey) &&
      (!kind || row.target_kind === kind));
    return route.fulfill({ json: { data: { rows, has_more: false, next_before_id: null } } });
  });

  const chooseIdentity = async (query, alias) => {
    const toolbar = page.locator('.activity-filter-toolbar');
    await toolbar.locator('.activity-filter-picker-destination .activity-filter-picker-trigger').click();
    const picker = page.getByRole('dialog', { name: 'Choose a destination' });
    await picker.locator('input[type="search"]').fill(query);
    await picker.locator('.activity-identity-result[data-identity-key]').filter({ hasText: alias }).click();
    await toolbar.getByRole('button', { name: 'Apply filters', exact: true }).click();
  };
  const expectFilter = async (identityKey, kind, alias, rowCount = 1) => {
    await expect.poll(() => new URL(page.url()).searchParams.get('activity_target_identity_key'))
      .toBe(identityKey || null);
    await expect.poll(() => new URL(page.url()).searchParams.get('activity_target_kind')).toBe(kind);
    await expect.poll(() => activityRequests.at(-1)?.target_identity_key || null).toBe(identityKey || null);
    await expect.poll(() => activityRequests.at(-1)?.target_kind).toBe(kind);
    const rows = page.locator('[data-table-type="activity"] tbody tr');
    await expect(rows).toHaveCount(rowCount);
    await expect(rows).toContainText(alias);
  };

  await chooseIdentity('alpha', 'Alpha Dispatch');
  await expectFilter(talkgroupKey, 'talkgroup', 'Alpha Dispatch');

  await chooseIdentity('bravo', 'Bravo Patch');
  await expectFilter(patchKey, 'patch_group', 'Bravo Patch');

  await chooseIdentity('charlie', 'Charlie Portable');
  await expectFilter(radioKey, 'radio', 'Charlie Portable');

  const toolbar = page.locator('.activity-filter-toolbar');
  await toolbar.locator('.activity-filter-picker-destination .activity-filter-picker-trigger').click();
  const picker = page.getByRole('dialog', { name: 'Choose a destination' });
  await picker.getByRole('button', { name: 'Talkgroups', exact: true }).click();
  await picker.locator('.activity-identity-any-kind').click();
  await toolbar.getByRole('button', { name: 'Apply filters', exact: true }).click();
  await expectFilter('', 'talkgroup', 'Alpha Dispatch');
});


for (const [sourceMode, expected] of [['talker_alias', 'E12 Portable'],
  ['source_alias', 'Engine 12'], ['both', 'Engine 12 · E12 Portable']]) {
  test(`Activity combined source respects ${sourceMode} and filters by the scoped identity`, async ({ page }) => {
    const mobile = sourceMode === 'both';
    await page.setViewportSize({ width: mobile ? 390 : 1440, height: 1000 });
    const requests = await openActivity(page, { sourceMode, theme: mobile ? 'dark' : 'light', rows: [
      toneActivityRow(31, undefined, { action: 'CALL', event_type: 'CALL_GROUP',
        source_talker_alias: 'E12 Portable' })
    ] });
    const table = page.locator('table[data-table-type="activity"]');
    const row = table.locator('tr[data-id="31"]');
    const source = row.locator('[data-column="source"]');
    const target = row.locator('[data-column="target"]');
    await expect(table.locator('thead [data-column="source"]')).toHaveText('Source');
    await expect(table.locator('thead [data-column="target"]')).toHaveText('Target');
    await expect(table.locator('[data-column="source-alias"], [data-column="source-ota-alias"], [data-column="target-alias"]'))
      .toHaveCount(0);
    await expect(source.locator('.identity-summary-primary')).toHaveText(expected);
    await expect(source.locator('.identity-summary-context')).toHaveText('2808137');
    await expect(source.locator('.activity-cell-action-link')).toHaveAttribute('aria-label',
      `source radio ${expected} · 2808137 actions`);
    await expect(target.locator('.identity-summary-primary')).toHaveText('CPD D3 DISP');
    await expect(target.locator('.identity-summary-context')).toHaveText('56131');
    await expect(source.locator('.identity-summary-context')).toBeVisible();
    await source.locator('.activity-cell-action-link').click();
    const filter = page.getByRole('link', { name: 'Filter activity by this source radio', exact: true });
    const destination = new URL(await filter.getAttribute('href'), page.url());
    expect(destination.searchParams.get('radio_system_key')).toBe(radioSystemKey);
    expect(destination.searchParams.get('activity_source_identity_key')).toBe(sourceIdentityKey);
    expect(destination.searchParams.get('activity_source_id')).toBeNull();
    expect(destination.search).not.toContain('Engine');
    expect(destination.search).not.toContain('Portable');
    const details = page.getByRole('link', { name: 'Open source radio details', exact: true });
    expect(new URL(await details.getAttribute('href'), page.url()).searchParams.get('identity_key'))
      .toBe(sourceIdentityKey);
    await filter.click();
    await expect.poll(() => requests.activity.at(-1)?.source_identity_key).toBe(sourceIdentityKey);
    await expect.poll(() => requests.activity.at(-1)?.radio_system_key).toBe(radioSystemKey);
    if (sourceMode === 'both' && process.env.WEB_REVIEW_DIR) {
      await source.scrollIntoViewIfNeeded();
      await toneScreenshot(page, 'combined-activity-source-dark-phone');
      await target.scrollIntoViewIfNeeded();
      await toneScreenshot(page, 'combined-activity-target-dark-phone');
    }
  });
}

test('Activity combined cells show nameless identifiers once', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openActivity(page, { rows: [toneActivityRow(32, undefined, { action: 'CALL', event_type: 'CALL_GROUP',
    source_alias_name: '', source_talker_alias: '', target_alias_name: '' })] });
  const row = page.locator('tr[data-id="32"]');
  for (const [role, identifier] of [['source', '2808137'], ['target', '56131']]) {
    const party = row.locator(`[data-column="${role}"]`);
    await expect(party.locator('.identity-summary-primary')).toHaveText(identifier);
    await expect(party.locator('.identity-summary-context')).toHaveCount(0);
    await expect(party).toHaveText(identifier);
  }
});

test('Activity combined target keeps distinct talkgroup, patch, and radio filter identities', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const kinds = [['talkgroup', 'v1-g-00001-001-56131', 'Shared dispatch'],
    ['patch_group', 'v1-p-00001-001-56131', 'Shared dispatch'],
    ['radio', 'v1-r-00001-001-56131', 'Shared dispatch']];
  const requests = await openActivity(page, { rows: kinds.map(([kind, identity, alias], index) =>
    toneActivityRow(40 + index, undefined, { action: 'CALL', event_type: 'CALL_GROUP', target_kind: kind,
      target_id: 56131, target_identity_key: identity, target_alias_name: alias,
      target_entity_ref: { kind,
        radio_system_key: radioSystemKey, identity_key: identity } })) });
  for (const [index, [kind, identity, alias]] of kinds.entries()) {
    const target = page.locator(`tr[data-id="${40 + index}"] [data-column="target"]`);
    await expect(target.locator('.identity-summary-primary')).toHaveText(alias);
    await expect(target.locator('.identity-summary-context')).toHaveText('56131');
    await target.locator('.activity-cell-action-link').click();
    const filter = page.getByRole('link', { name: 'Filter activity by this target', exact: true });
    const destination = new URL(await filter.getAttribute('href'), page.url());
    expect(destination.searchParams.get('radio_system_key')).toBe(radioSystemKey);
    expect(destination.searchParams.get('activity_target_identity_key')).toBe(identity);
    expect(destination.searchParams.get('activity_target_kind')).toBe(kind);
    expect(destination.searchParams.get('activity_target_id')).toBeNull();
    const details = page.getByRole('link', { name: 'Open target details', exact: true });
    expect(new URL(await details.getAttribute('href'), page.url()).searchParams.get('identity_key')).toBe(identity);
    await filter.click();
    await expect.poll(() => requests.activity.at(-1)?.target_identity_key).toBe(identity);
    await expect.poll(() => requests.activity.at(-1)?.target_kind).toBe(kind);
  }
});
