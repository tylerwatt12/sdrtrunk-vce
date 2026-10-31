'use strict';

const { expect, test } = require('@playwright/test');
const { tourHistoryFixture } = require('./fixtures/tour-history.cjs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { selectAudioPanel } = require('./audio-dock-controls.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');
const { expectFormWorkflowGeometry, expectStableFormWorkflowInsets, captureFormWorkflowEvidence,
  holdFormWorkflowFailure } = require('./fixtures/form-workflow-geometry.cjs');

// The global player is covered separately; these snapshots isolate recording components.
const componentScreenshot = { stylePath: [path.resolve(__dirname, 'fixtures/hide-global-audio-dock.css'),
  path.resolve(__dirname, 'fixtures/recordings-screenshot-timing.css')] };

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

const call = {
  id: 17, start_ms: Date.parse('2026-09-28T10:08:42Z'), duration_ms: 42_000,
  system_name: 'Metro Public Safety', talkgroup_id: 1201, talkgroup_alias: 'Fire Dispatch',
  source_id: 30914, source_alias: 'Engine 4', source_ota_alias: 'ENG 4',
  site_name: 'North Ridge', call_type: 'GROUP', protocol: 'APCO25_PHASE2', voice_type: 'CLEAR'
};

const systemKey = 'p25:00001:001';
const channelId = '00000000-0000-4000-8000-000000000017';
const linkedCall = {
  ...call,
  system_key: systemKey,
  channel_id: channelId,
  channel_name: 'North Ridge Channel',
  radio_system_entity_ref: { kind: 'radio_system', key: systemKey },
  channel_entity_ref: { kind: 'channel', key: channelId },
  target_entity_ref: {
    kind: 'talkgroup', radio_system_key: systemKey, identity_key: 'v1-g-00001-001-1201'
  },
  source_entity_ref: {
    kind: 'radio', radio_system_key: systemKey, identity_key: 'v1-r-00001-001-30914'
  }
};

const linkedHrefs = [
  '/?view=radio-system&radio_system_key=p25%3A00001%3A001',
  `/?view=channel&configuration_id=${channelId}`,
  '/?view=group-identity&radio_system_key=p25%3A00001%3A001&identity_key=v1-g-00001-001-1201',
  '/?view=radio&radio_system_key=p25%3A00001%3A001&identity_key=v1-r-00001-001-30914'
];

function entityLink(host, href) {
  const identity = new URL(href, 'http://127.0.0.1');
  const params = identity.searchParams;
  if (params.get('view') === 'group-identity') return host.locator(`a[href="${href}"]`).filter({ visible: true });
  const filters = {};
  if (params.get('view') === 'channel') filters.channel_id = params.get('configuration_id');
  else {
    filters.system_key = params.get('radio_system_key');
    if (params.get('view') === 'radio') filters.radio_identity_key = params.get('identity_key');
    if (params.get('view') === 'group-identity') filters.talkgroup_identity_key = params.get('identity_key');
  }
  const systemOnly = params.get('view') === 'radio-system' ?
    ':not([href*="talkgroup_id="]):not([href*="talkgroup_identity_key="]):not([href*="radio_identity_key="]):not([href*="channel_id="]):not([href*="site_id="])' : '';
  return host.locator(`a[href*="view=recordings"]${Object.entries(filters).map(([key, value]) =>
    `[href*="${key}=${encodeURIComponent(value)}"]`).join('')}${systemOnly}`).filter({ visible: true });
}

async function selectRecordingDetailTab(detail, name) {
  const tab = detail.getByRole('tab', { name, exact: true });
  await tab.click();
  await expect(tab).toHaveAttribute('aria-selected', 'true');
}

function mixedRecordingCalls() {
  return Array.from({ length: 30 }, (_, index) => {
    const secondSystem = index % 4 === 3;
    const scope = secondSystem ? 'p25:00002:002' : systemKey;
    const home = secondSystem ? '00002-002' : '00001-001';
    const groupId = 1201 + index % 3;
    const sourceId = 30914 + index;
    const configuredChannel = `00000000-0000-4000-8000-${String(17 + index % 4).padStart(12, '0')}`;
    const aliases = [
      'Central / East Ridge / Valley Township / River District Fire Dispatch and Mutual Aid',
      'Northern District Police Dispatch and Regional Public Safety Coordination',
      'Countywide Emergency Medical Services and Hospital Transport Coordination'
    ];
    return {
      ...linkedCall, id: 17 + index, start_ms: call.start_ms - index * 60_000,
      duration_ms: 7_000 + index * 1_100,
      system_key: scope,
      system_name: secondSystem ? 'County Regional Public Safety Communications' :
        'Metro Regional Interoperability and Public Safety Communications Network',
      radio_system_entity_ref: { kind: 'radio_system', key: scope },
      talkgroup_id: groupId, talkgroup_alias: aliases[index % aliases.length],
      talkgroup_description: 'Dispatch operations serving several municipalities, regional response teams, hospitals, and mutual aid partners.',
      talkgroup_group: 'Regional public safety operations and interagency mutual aid',
      target_entity_ref: { kind: 'talkgroup', radio_system_key: scope,
        identity_key: `v1-g-${home}-${groupId}` },
      source_id: sourceId, source_alias: `Regional response unit ${index + 1}`,
      source_ota_alias: `FIELD UNIT ${index + 1}`,
      source_entity_ref: { kind: 'radio', radio_system_key: scope,
        identity_key: `v1-r-${home}-${sourceId}` },
      site_name: index % 2 ? 'East Ridge / River Valley Simulcast' : 'Central / Valley Township Simulcast',
      audio_from: { wacn: secondSystem ? 2 : 1, system_id: secondSystem ? 2 : 1,
        rfss: 1, site_id: index % 2 + 1 },
      channel_id: configuredChannel,
      channel_name: `Regional Receiver / ${secondSystem ? 'County' : 'Metro'} / Simulcast Channel ${index % 4 + 1}`,
      channel_entity_ref: { kind: 'channel', key: configuredChannel },
      frequency_hz: 851_012_500 + index * 25_000,
      transcript_excerpt: 'Unit arriving at the designated staging location. Requesting the next available response team and confirming the information with dispatch before proceeding.' +
        (index === 0 ? ` ${'DispatchInteroperability'.repeat(10)}` : '')
    };
  });
}

function groupedChoices(rows, url) {
  if (url.searchParams.get('grouped') !== 'true') return null;
  const groups = new Map();
  for (const row of rows) {
    const key = JSON.stringify([row.kind, row.system_key || null, row.group || '', row.alias_list_id || '']);
    if (!groups.has(key)) groups.set(key, { group_key: key, kind: row.kind,
      label: row.kind === 'channel' ? 'Channels' : row.group || 'Ungrouped', system_key: row.system_key,
      system_name: row.system_name, alias_list_name: row.alias_list_name, rows: [] });
    groups.get(key).rows.push(row);
  }
  const offset = Number(url.searchParams.get('offset') || 0), limit = Number(url.searchParams.get('limit') || 10);
  return { groups: [...groups.values()].filter(group => !url.searchParams.get('group_key') || group.group_key === url.searchParams.get('group_key'))
    .map(group => ({ ...group, match_count: group.rows.length, has_more: offset + limit < group.rows.length,
      next_offset: offset + limit, rows: group.rows.slice(offset, offset + limit) })) };
}

async function stubRecordingAudio(page) {
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

async function openRecordings(page, options = {}) {
  const tourHistory = tourHistoryFixture({ welcome: 1, recordings: 6 });
  // These fixtures exercise an existing listener's recording workflows.
  // First-visit onboarding is covered by recordings-guide.spec.js.
  await page.addInitScript(() => {
    for (const account of ['admin', 'anonymous']) for (const [id, version] of [['welcome', 1], ['recordings', 6]]) {
      localStorage.setItem(`sdrtrunk-vce-guide:v1:${account}:recordings-fixture-account:${id}:${version}`, '1');
    }
  });
  const state = {
    mode: options.mode || 'MANAGED', available: options.available !== false,
    hasCalls: options.hasCalls !== false, matchCalls: true, admin: options.admin !== false,
    primary: options.primary !== false,
    call: options.call || call, calls: options.calls || null, callDetail: options.callDetail || null,
    requestedFilters: [], suggestionRequests: [],
    paginate: options.paginate === true,
    transcription: options.transcription || null, settingsWrites: [], maintenanceWrites: [], deletionWrites: [],
    catalogStatusError: null,
    catalogStatus: options.catalogStatus || { catalog: { call_count: 1 },
      transcription: { pending: 3, completed: 1, failed: 1, active: false } },
    settings: { mode: options.mode || 'MANAGED', managed_directory: '/recordings',
      retention_days: null, transcription_enabled: false, transcription_url: '',
      transcription_model: '', transcription_min_duration_ms: 500,
      transcription_key_configured: Boolean(options.keyConfigured), ...options.settings }
  };
  const preferences = structuredClone(defaultPreferences);
  preferences.appearance.theme = options.theme || 'light';
  preferences.appearance.hue = options.hue ?? null;
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const pathname = url.pathname;
    if (pathname === '/api/v1/me/tour-dismissals') return tourHistory(route);
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: { data: { configured: true, authenticated: state.admin,
        username: state.admin ? 'admin' : null, tier: state.admin ? 'admin' : 'public',
        guide_dismissal_scope: 'recordings-fixture-account',
        primary: state.admin && state.primary, capabilities: { recordings: true,
          'admin-recordings': state.admin, 'admin-settings': state.admin, dashboard: true,
          radio: options.radio !== false, 'call-audio': options.directListen === true,
          'direct-listen': options.directListen === true } } } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } });
    } else if (pathname === '/api/v1/recordings/status') {
      await route.fulfill({ json: { data: { mode: state.mode, available: state.available,
        has_calls: state.hasCalls } } });
    } else if (pathname === '/api/v1/recordings/calls') {
      state.requestedFilters.push(Object.fromEntries(url.searchParams));
      if (state.errorNext) { state.errorNext = false; await route.fulfill({ status: 503, json: { error: { message: 'Search temporarily unavailable.' } } }); return; }
      if (state.delayCalls) await new Promise(resolve => setTimeout(resolve, state.delayCalls));
      const matching = state.hasCalls && state.matchCalls;
      const calls = matching ? state.calls || [state.call] : [];
      const offset = state.paginate ? Number((url.searchParams.get('cursor') || 'fixture:0').split(':')[1]) : 0;
      const limit = state.paginate ? Number(url.searchParams.get('limit')) : calls.length;
      await route.fulfill({ json: { data: { calls: options.emptyFirstBatch && offset === 0 ? [] :
        calls.slice(offset, offset + limit),
        next_cursor: options.nextCursor || (state.paginate && offset + limit < calls.length ? `fixture:${offset + limit}` : null),
        total: null } } });
    } else if (pathname === '/api/v1/admin/recordings/calls' && route.request().method() === 'DELETE') {
      const update = route.request().postDataJSON();
      state.deletionWrites.push(update);
      const ids = new Set(update.ids);
      const calls = state.calls || [state.call];
      state.calls = calls.filter((item) => !ids.has(item.id));
      state.hasCalls = state.calls.length > 0;
      await route.fulfill({ json: { data: { requested: ids.size,
        deleted: calls.length - state.calls.length } } });
    } else if (/^\/api\/v1\/recordings\/calls\/\d+$/.test(pathname)) {
      const id = Number(pathname.split('/').at(-1));
      const selected = state.calls?.find((item) => item.id === id) || state.call;
      await route.fulfill({ json: { data: { call: state.callDetail || selected,
        ...(state.transcription ? { transcription: state.transcription } : {}) } } });
    } else if (pathname === '/api/v1/admin/recordings/settings') {
      if (route.request().method() === 'PUT') {
        const update = route.request().postDataJSON();
        state.settingsWrites.push(update);
        if (state.settingsWriteError) {
          await route.fulfill({ status: 503, json: { error: { message: state.settingsWriteError } } });
          return;
        }
        Object.assign(state.settings, update);
        if (update.transcription_api_key) state.settings.transcription_key_configured = true;
        if (update.transcription_clear_api_key) state.settings.transcription_key_configured = false;
      }
      await route.fulfill({ json: { data: state.settings } });
    } else if (pathname === '/api/v1/admin/recordings/status') {
      await route.fulfill(state.catalogStatusError ?
        { status: 503, json: { error: { message: state.catalogStatusError } } } :
        { json: { data: state.catalogStatus } });
    } else if (['/api/v1/admin/recordings/recount', '/api/v1/admin/recordings/reindex'].includes(pathname)) {
      state.maintenanceWrites.push(pathname.split('/').at(-1));
      await route.fulfill({ json: { data: state.catalogStatus } });
    } else if (pathname === '/api/v1/admin/operational-preferences') {
      await route.fulfill({ json: { revision: 'a'.repeat(64), settings: { audio_record_format: 'MP3',
        patch_group_streaming_option: 'PATCH_GROUP', mp3_setting: 'CBR_16', mp3_input_audio_format: 'SR_16000',
        mp3_normalize_audio: false, stats_logging_enabled: true,
        stats_detailed_history_enabled: false,
        stats_logging_retention_days: 30 },
      options: {
        patch_group_streaming_options: [{ value: 'PATCH_GROUP', label: 'Patch Group' }],
        audio_record_formats: [{ value: 'MP3', label: 'MP3' }, { value: 'WAVE', label: 'WAV' }],
        mp3_settings: [{ value: 'CBR_16', label: '16 kbps' }],
        mp3_input_audio_formats_by_setting: { CBR_16: [{ value: 'SR_16000', label: '16 kHz' }] },
        minimum_stats_logging_retention_days: 1, maximum_stats_logging_retention_days: 365
      } } });
    } else if (pathname === '/api/v1/admin/recordings/calls/17/transcription/retry') {
      state.transcription = { status: 'PENDING', text: null, stored_at_ms: null };
      await route.fulfill({ json: { data: state.transcription } });
    } else if (pathname === '/api/v1/recordings/identities') {
      state.identityRequests ||= [];
      state.identityRequests.push(Object.fromEntries(url.searchParams));
      const items = typeof options.identities === 'function' ? options.identities(Object.fromEntries(url.searchParams)) : options.identities || [
        { kind: 'talkgroup', id: 1201, system_key: systemKey, system_name: 'Metro Public Safety',
          label: 'Fire Dispatch', group: 'Fire / EMS', description: 'Primary fire dispatch', call_count: 42 },
        { kind: 'talkgroup', id: 1202, system_key: systemKey, system_name: 'Metro Public Safety',
          label: 'Fireground 2', group: 'Fire / EMS', call_count: 12 },
        { kind: 'talkgroup', id: 1201, system_key: 'p25:00002:002', system_name: 'County Public Safety',
          label: 'County Dispatch', group: null, call_count: 7 },
        { kind: 'radio', id: 30914, system_key: systemKey, system_name: 'Metro Public Safety',
          label: 'ENG 4', alias: 'Engine 4', ota_alias: 'ENG 4', call_count: 25 }
      ];
      const q = (url.searchParams.get('q') || '').toLowerCase();
      const rows = items.filter(row => row.kind === url.searchParams.get('kind') &&
        (!url.searchParams.get('system_key') || row.system_key === url.searchParams.get('system_key')) &&
        (!q || [row.label, row.alias, row.ota_alias, row.description, row.group, row.id].join(' ').toLowerCase().includes(q)));
      const sort = url.searchParams.get('sort') || 'calls';
      rows.sort(sort === 'name' ? (a, b) => String(a.label).localeCompare(String(b.label)) :
        sort === 'recent' ? (a, b) => Number(b.last_recorded_ms || 0) - Number(a.last_recorded_ms || 0) :
          (a, b) => Number(b.call_count || 0) - Number(a.call_count || 0));
      const offset = Number(url.searchParams.get('offset') || 0);
      const limit = options.identityPageSize || Number(url.searchParams.get('limit') || 100);
      const prepared = rows.map(row => ({ ...row, selection: row.selection || { id: row.id, system_key: row.system_key,
        ...(row.identity_key ? { identity_key: row.identity_key } : row.protocol ? { protocol: row.protocol === 'APCO25_PHASE2' ? 'APCO25' : row.protocol } : {}) } }));
      await route.fulfill({ json: { data: groupedChoices(prepared, url) || { rows: prepared.slice(offset, offset + limit),
        has_more: offset + limit < prepared.length, next_offset: offset + limit } } });
    } else if (pathname === '/api/v1/recordings/entities') {
      state.entityRequests ||= [];
      state.entityRequests.push(Object.fromEntries(url.searchParams));
      if (options.entityErrorOnce && state.entityRequests.length === 1) {
        await route.fulfill({ status: 503, json: { error: { message: 'Browse unavailable' } } }); return;
      }
      const kind = url.searchParams.get('kind');
      const suggested = Array.isArray(options.suggestions) ? options.suggestions.filter(row => row.kind === kind) : [];
      const items = (typeof options.entities === 'function' ? options.entities(Object.fromEntries(url.searchParams)) : options.entities) || (suggested.length ? suggested : [
        { kind: 'system', id: systemKey, system_key: systemKey, label: 'Metro Public Safety', protocol: 'APCO25_PHASE2', call_count: 42, selection: { system_key: systemKey } },
        { kind: 'system', id: 'nxdn-c:global:7', system_key: 'nxdn-c:global:7', label: 'NXDN Operations', protocol: 'NXDN', call_count: 11, selection: { system_key: 'nxdn-c:global:7' } },
        { kind: 'site', id: '1.1.1.2', label: 'North Ridge Channel', system_name: 'Metro Public Safety', protocol: 'APCO25_PHASE2',
          count_kind: 'heard_at', call_count: 21, wacn: 1, sysid: 1, rfss: 1, site_id: 2,
          selection: { wacn: '1', sysid: '1', rfss: '1', site_id: '2' } },
        { kind: 'channel', id: '00000000-0000-4000-8000-000000000018', label: 'Airfield AM', protocol: 'AM', call_count: 15, selection: { channel_id: '00000000-0000-4000-8000-000000000018' } },
        { kind: 'channel', id: channelId, label: 'North Ridge Channel', system_key: systemKey, system_name: 'Metro Public Safety', protocol: 'APCO25_PHASE2', call_count: 42, selection: { channel_id: channelId } },
        { kind: 'channel', id: '00000000-0000-4000-8000-000000000019', label: 'NXDN Conventional', system_key: 'nxdn-c:global:7', protocol: 'NXDN', call_count: 11, selection: { channel_id: '00000000-0000-4000-8000-000000000019' } }
      ]);
      const q = (url.searchParams.get('q') || '').toLowerCase();
      const rows = items.filter(row => row.kind === kind && (!q || [row.label, row.id, row.detail].join(' ').toLowerCase().includes(q)) &&
        (!url.searchParams.get('system_key') || !row.system_key || row.system_key === url.searchParams.get('system_key')));
      const sort = url.searchParams.get('sort') || 'calls';
      rows.sort(sort === 'name' ? (a, b) => String(a.label).localeCompare(String(b.label)) :
        sort === 'recent' ? (a, b) => Number(b.last_recorded_ms || 0) - Number(a.last_recorded_ms || 0) :
          (a, b) => Number(b.call_count || 0) - Number(a.call_count || 0));
      const offset = Number(url.searchParams.get('offset') || 0);
      const limit = options.entityPageSize || Number(url.searchParams.get('limit') || 100);
      await route.fulfill({ json: { data: groupedChoices(rows, url) || { rows: rows.slice(offset, offset + limit),
        has_more: offset + limit < rows.length, next_offset: offset + limit } } });
    } else if (pathname === '/api/v1/recordings/suggestions') {
      const q = url.searchParams.get('q') || '';
      state.suggestionRequests.push(Object.fromEntries(url.searchParams));
      if (q === 'error') {
        await route.fulfill({ status: 500, json: { error: { message: 'Suggestion error' } } });
      } else {
        if (q === 'slow') await new Promise((resolve) => setTimeout(resolve, 350));
        const suggestions = typeof options.suggestions === 'function' ?
          options.suggestions(q, Object.fromEntries(url.searchParams)) : options.suggestions ||
          (q.toLowerCase().includes('fire') ? [
          { kind: 'talkgroup', id: 1201, label: 'Fire Dispatch', detail: 'Metro Public Safety · TG 1201',
            system_key: systemKey, system_name: 'Metro Public Safety' }
        ] : []);
        await route.fulfill({ json: { data: { suggestions } } });
      }
    } else if (options.directListen && pathname === '/api/v1/scan-lists') {
      await route.fulfill({ json: { data: { revision: 1, scan_lists: [] } } });
    } else if (options.directListen && pathname === '/api/v1/calls/feed') {
      await route.fulfill({ json: { data: { cursor: '1', reset: false, calls: [] } } });
    } else if (pathname === '/api/v1/status') {
      await route.fulfill({ json: { data: {} } });
    } else {
      await route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
    }
  });
  await page.goto(options.view === 'admin' ? `/app.html?view=admin&tab=${options.tab || 'recordings'}` :
    `/app.html?view=recordings${options.query ? '&recording_filters=1&' + new URLSearchParams(options.query) : ''}`);
  return state;
}


async function ensureSearchOpen(page) {
  const toggle = page.locator('#recordings-filters-toggle');
  const fullText = page.getByRole('searchbox', { name: 'Full text', exact: true });
  await expect(page.locator('.recordings-filter-section')).toBeVisible();
  if (await toggle.isVisible() && await toggle.getAttribute('aria-expanded') === 'false') await toggle.click();
  await expect(fullText).toBeVisible();
}

async function openPicker(page, topic = 'targets', name = 'Talkgroups & channels') {
  await ensureSearchOpen(page);
  await page.locator(`#recordings-open-${topic}`).click();
  const dialog = page.getByRole('dialog', { name, exact: true }); await expect(dialog).toBeVisible(); return dialog;
}
async function openCallDetails(page, card = page.locator('.recordings-call').first()) {
  await card.locator('.recordings-call-info').click();
  const dialog = page.getByRole('dialog', { name: 'Call actions', exact: true });
  await dialog.getByRole('button', { name: 'Show more', exact: true }).click();
  await expect(dialog.getByRole('tab', { name: 'Overview', exact: true })).toBeVisible();
}

async function recordingAction(page, card = page.locator('.recordings-call').first(), name = 'Play once') {
  await card.locator('.recordings-call-info').click();
  await page.getByRole('dialog', { name: 'Call actions', exact: true })
    .getByRole('button', { name, exact: true }).click();
}

async function expectCallActionDescriptions(dialog) {
  for (const name of ['Play once', 'Continue from here', 'Add to queue'])
    await expect(dialog.getByRole('button', { name, exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Related activity and alerts', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Call details', exact: true })).toHaveCount(0);
  const more = dialog.getByRole('button', { name: 'Show more', exact: true });
  await expect(more).toHaveAttribute('aria-expanded', 'false');
  await expect(dialog.getByRole('tab')).toHaveCount(0);
  expect(await dialog.locator('.recordings-call-entity').count()).toBeGreaterThan(0);
  for (const row of await dialog.locator('.recordings-call-entity').all()) {
    const filter = row.getByRole('link', { name: /^Filter by / });
    if (await filter.count()) await expect(filter).toHaveAttribute('href', /view=recordings/);
    else await expect(row.getByRole('button', { name: /^Filter by / })).toBeDisabled();
    const navigation = row.getByRole('link', { name: /^Open .* page$/ });
    if (await navigation.count()) await expect(navigation).toHaveAttribute('href', /[?&]view=(?!recordings)/);
  }
}
async function searchNow(page, state) {
  await ensureSearchOpen(page);
  const count = state.requestedFilters.length;
  await page.getByRole('button', { name: 'Search recordings', exact: true }).click();
  await expect.poll(() => state.requestedFilters.length).toBe(count + 1);
  await expect(page.locator('.recordings-result-status')).toBeEmpty();
}

for (const [theme, width] of [['light', 1280], ['light', 320]]) {
  test(`recording form spacing survives dirty busy and error states in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await openRecordings(page, { view: 'admin', theme, settings: { retention_days: 90 } });
    const held = await holdFormWorkflowFailure(page, '**/api/v1/admin/recordings/settings');
    for (const [name, edit] of [
      ['mode', () => page.getByRole('radio', { name: /Classic/ }).check()],
      ['retention', () => page.getByLabel('Remove managed calls older than (days)').fill('30')]
    ]) {
      const form = page.locator('form.recordings-admin-body').nth(name === 'mode' ? 0 : 1);
      const before = await expectFormWorkflowGeometry(form, { modal: false });
      await edit();
      await expect(form).toHaveClass(/is-dirty/);
      await expectFormWorkflowGeometry(form, { modal: false });
      await expectStableFormWorkflowInsets(form, before);
      await captureFormWorkflowEvidence(form, `recordings-${name}-${theme}-${width}-dirty`);
      await form.locator('.ui-form-submit').click();
      await expect.poll(held.pending).toBe(true);
      await expect(form).toHaveAttribute('aria-busy', 'true');
      await expectStableFormWorkflowInsets(form, before);
      await captureFormWorkflowEvidence(form, `recordings-${name}-${theme}-${width}-saving`);
      await held.fail();
      await expect(form.getByRole('alert')).toContainText('could not be saved');
      await expectFormWorkflowGeometry(form, { modal: false });
      await expectStableFormWorkflowInsets(form, before);
      await captureFormWorkflowEvidence(form, `recordings-${name}-${theme}-${width}-error`);
    }
  });

  test(`transcription form spacing survives dirty busy and error states in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await openRecordings(page, { view: 'admin', tab: 'transcription', theme, keyConfigured: true,
      settings: { transcription_enabled: true, transcription_url: 'https://transcription.example.net/v1/audio/transcriptions',
        transcription_model: 'test-model' } });
    const held = await holdFormWorkflowFailure(page, '**/api/v1/admin/recordings/settings');
    const form = page.locator('form.recordings-admin-body');
    const before = await expectFormWorkflowGeometry(form, { modal: false });
    await page.getByLabel('Model ID').fill('Updated transcription model');
    await expect(form).toHaveClass(/is-dirty/);
    await expectFormWorkflowGeometry(form, { modal: false });
    await expectStableFormWorkflowInsets(form, before);
    await captureFormWorkflowEvidence(form, `transcription-${theme}-${width}-dirty`);
    await form.locator('.ui-form-submit').click();
    await expect.poll(held.pending).toBe(true);
    await expect(form).toHaveAttribute('aria-busy', 'true');
    await expectStableFormWorkflowInsets(form, before);
    await captureFormWorkflowEvidence(form, `transcription-${theme}-${width}-saving`);
    await held.fail();
    await expect(form.getByRole('alert')).toContainText('could not be saved');
    await expectFormWorkflowGeometry(form, { modal: false });
    await expectStableFormWorkflowInsets(form, before);
    await captureFormWorkflowEvidence(form, `transcription-${theme}-${width}-error`);
  });
}

test('Classic empty page invites only the primary administrator', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openRecordings(page, { mode: 'CLASSIC', hasCalls: false });
  await expect(page.getByRole('heading', { name: 'Make every call easy to find' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Enable Managed Recordings' }))
    .toHaveAttribute('href', '/?view=admin&tab=recordings');
  await expect(page.locator('.recordings-search')).toHaveCount(0);
  await expect(page.locator('.recordings-pager')).toHaveCount(0);
  await expect(page.locator('.recordings-library')).toHaveScreenshot('recordings-classic-admin-empty.png', componentScreenshot);

  await page.setViewportSize({ width: 390, height: 844 });
  await openRecordings(page, { mode: 'CLASSIC', hasCalls: false, admin: false, theme: 'dark' });
  await expect(page.getByRole('heading', { name: 'No managed recordings yet' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Enable Managed Recordings' })).toHaveCount(0);
  await expect(page.locator('.recordings-search')).toHaveCount(0);
  await expect(page.locator('.recordings-library')).toHaveScreenshot('recordings-classic-guest-empty.png', componentScreenshot);
});

test('Classic history remains searchable and unavailable catalog has no enable pitch', async ({ page }) => {
  await openRecordings(page, { mode: 'CLASSIC' });
  await expect(page.locator('.recordings-mode-notice')).toContainText('Earlier Managed calls remain searchable');
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  await expect(page.getByRole('searchbox', { name: 'Full text' })).toBeVisible();
  await expect(page.locator('.recordings-library')).toHaveScreenshot('recordings-classic-history.png', componentScreenshot);

  await openRecordings(page, { mode: 'CLASSIC', hasCalls: false, available: false });
  await expect(page.locator('.recordings-library')).toContainText('library is unavailable');
  await expect(page.getByRole('link', { name: 'Enable Managed Recordings' })).toHaveCount(0);
});

test('Managed empty page refreshes when the first call arrives', async ({ page }) => {
  const state = await openRecordings(page, { hasCalls: false });
  await expect(page.getByRole('heading', { name: 'Ready for the first call' })).toBeVisible();
  await expect(page.locator('.recordings-library')).toHaveScreenshot('recordings-managed-empty.png', componentScreenshot);
  state.hasCalls = true;
  await page.getByRole('button', { name: 'Refresh calls' }).click();
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  expect(state.requestedFilters.length).toBeGreaterThan(0);
});

test('recording settings retain mode, retention, catalog facts, and both maintenance actions', async ({ page }) => {
  const state = await openRecordings(page, { view: 'admin', mode: 'CLASSIC',
    settings: { retention_days: 90 },
    catalogStatus: { catalog: { call_count: 28_416, oldest_call_ms: Date.parse('2026-07-03T08:42:00Z') },
      last_recount_ms: Date.parse('2026-09-28T09:14:00Z'), maintenance: { state: 'IDLE' } } });
  const workspace = page.locator('.recordings-admin');
  await expect(workspace.getByRole('radio', { name: /Classic/ })).toBeChecked();
  await expect(workspace).toContainText('/recordings');
  await expect(workspace).toContainText('Existing managed calls stay searchable when you switch to Classic');
  await expect(workspace.getByLabel('Remove managed calls older than (days)')).toHaveValue('90');
  await expect(workspace.getByLabel('Recording format')).toHaveValue('MP3');
  expect(await workspace.getByLabel('Recording format').locator('option').allTextContents())
    .toEqual(['MP3', 'WAV']);
  await expect(workspace).toContainText('28,416');
  await expect(workspace).toContainText('Jul 3, 2026');
  await expect(workspace).toContainText('Last recount');
  await expect(workspace).toContainText('Idle');
  await expect(workspace.getByLabel('Transcription endpoint URL')).toHaveCount(0);
  await expect(workspace.getByRole('link', { name: 'Audio quality settings' }))
    .toHaveAttribute('href', '/?view=admin&tab=audio-quality');
  await expect(workspace.getByRole('link', { name: 'Transcription settings' }))
    .toHaveAttribute('href', '/?view=admin&tab=transcription');
  const saveMode = workspace.getByRole('button', { name: 'Save mode', exact: true });
  const saveAge = workspace.getByRole('button', { name: 'Save age limit' });
  await expect(saveMode).toBeHidden();
  await expect(saveAge).toBeHidden();

  await workspace.getByRole('radio', { name: /Managed/ }).check();
  await expect(saveMode).toBeVisible();
  await saveMode.click();
  await expect(saveMode).toBeHidden();
  await expect(workspace).not.toContainText('Recording mode saved.');
  expect(state.settingsWrites.at(-1)).toEqual({ mode: 'MANAGED' });
  await workspace.getByRole('radio', { name: /Classic/ }).check();
  await expect(saveMode).toBeVisible();
  await workspace.getByRole('radio', { name: /Managed/ }).check();
  await expect(saveMode).toBeHidden();
  await workspace.getByLabel('Remove managed calls older than (days)').fill('2.5');
  await saveAge.click();
  await expect(workspace).toContainText('Enter a whole number of days, or leave the field blank.');
  await workspace.getByLabel('Remove managed calls older than (days)').fill('');
  await expect(workspace).not.toContainText('Enter a whole number of days, or leave the field blank.');
  state.settingsWriteError = 'Age limit could not be saved. Try again.';
  await saveAge.click();
  await expect(workspace).toContainText(state.settingsWriteError);
  await expect(saveAge).toBeVisible();
  await expect(workspace.getByLabel('Remove managed calls older than (days)')).toHaveValue('');
  state.settingsWriteError = null;
  await saveAge.click();
  await expect(saveAge).toBeHidden();
  await expect(workspace).not.toContainText('Age limit saved.');
  expect(state.settingsWrites.at(-1)).toEqual({ retention_days: null });
  await workspace.getByLabel('Remove managed calls older than (days)').fill('30');
  await expect(saveAge).toBeVisible();
  await workspace.getByLabel('Remove managed calls older than (days)').fill('');
  await expect(saveAge).toBeHidden();
  await expect(workspace.getByRole('link', { name: 'Audio quality settings' })).toBeVisible();
  await expect(workspace.getByRole('link', { name: 'Transcription settings' })).toBeVisible();

  const maintenance = workspace.locator('details.ui-section-disclosure');
  await expect(maintenance).not.toHaveAttribute('open', '');
  await expect(workspace.getByRole('button', { name: 'Run recount calls' })).toBeHidden();
  await page.getByRole('button', { name: 'Change audio player size', exact: true }).press('Home');
  await maintenance.locator('summary').click();
  for (const [title, action] of [['Recount calls', 'recount'], ['Reindex calls', 'reindex']]) {
    await workspace.getByRole('button', { name: `Run ${title.toLowerCase()}` }).click();
    const dialog = page.getByRole('dialog', { name: title });
    await expect(dialog).toBeVisible();
    await expect(dialog.locator('footer.ui-modal-footer')).toHaveCount(1);
    if (action === 'recount') {
      await expect(dialog.locator('.ui-metric-label')).toHaveText('Listed calls');
      await expect(dialog.locator('.ui-metric strong')).toHaveText('28,416');
      await expect(dialog).toContainText('Last recount');
    } else {
      await expect(dialog).toContainText('may cause some to be dropped');
      await expect(dialog).toContainText('Run during quiet reception.');
      await expect(dialog).toContainText('It does not recover unlisted audio files.');
    }
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    expect(state.maintenanceWrites).not.toContain(action);
    await workspace.getByRole('button', { name: `Run ${title.toLowerCase()}` }).click();
    await dialog.getByRole('button', { name: `Run ${title.toLowerCase()}` }).click();
    await expect(dialog).toBeHidden();
    await expect(workspace).toContainText(`${title} complete.`);
    expect(state.maintenanceWrites.at(-1)).toBe(action);
  }
});

test('transcription page keeps every provider field and progress status through refresh', async ({ page }) => {
  const state = await openRecordings(page, { view: 'admin', tab: 'transcription', keyConfigured: true,
    settings: { transcription_enabled: true, transcription_url: 'http://127.0.0.1:8000/v1/audio/transcriptions',
      transcription_model: 'speech-model', transcription_min_duration_ms: 750 },
    catalogStatus: { transcription: { pending: 36, completed: 12_408, failed: 2, active: true,
      last_error: 'Transcription service was temporarily unavailable.' } } });
  const workspace = page.locator('.recordings-admin');
  await expect(workspace.locator('section').first()).toContainText('Transcription progress');
  await expect(workspace.getByRole('checkbox', { name: 'Transcribe managed calls' })).toBeChecked();
  await expect(workspace.getByLabel('Transcription endpoint URL'))
    .toHaveValue('http://127.0.0.1:8000/v1/audio/transcriptions');
  await expect(workspace.getByLabel('Model ID')).toHaveValue('speech-model');
  await expect(workspace.getByLabel('Minimum call length (ms)')).toHaveValue('750');
  await expect(workspace.getByLabel('API key')).toHaveValue('');
  await expect(workspace.getByRole('button', { name: 'Save transcription settings' })).toBeHidden();
  for (const invalid of ['499', '600001', '500.5']) {
    await workspace.getByLabel('Minimum call length (ms)').fill(invalid);
    await workspace.getByRole('button', { name: 'Save transcription settings' }).click();
    await expect(workspace).toContainText('Enter a whole number from 500 to 600,000 milliseconds.');
    expect(state.settingsWrites).toEqual([]);
  }
  await workspace.getByLabel('Minimum call length (ms)').fill('750');
  await expect(workspace.getByRole('button', { name: 'Save transcription settings' })).toBeHidden();
  const progress = workspace.locator('section').filter({ hasText: 'Transcription progress' });
  for (const text of ['Pending', '36', 'Completed', '12,408', 'Failed', '2', 'Worker',
    'Transcribing a call', 'Last error', 'Transcription service was temporarily unavailable.']) {
    await expect(progress).toContainText(text);
  }
  await expect(workspace.getByLabel('Remove managed calls older than (days)')).toHaveCount(0);
  await workspace.getByRole('button', { name: 'Clear saved key' }).click();
  const dialog = page.getByRole('dialog', { name: 'Clear saved transcription key' });
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  expect(state.settingsWrites).toEqual([]);
  await expect(workspace.getByRole('button', { name: 'Clear saved key' })).toBeFocused();
  state.catalogStatus.transcription.pending = 35;
  state.catalogStatus.transcription.completed = 12_409;
  await progress.getByRole('button', { name: 'Refresh status' }).click();
  await expect(progress).toContainText('35');
  await expect(progress).toContainText('12,409');
  await expect(workspace.getByRole('link', { name: 'Recording settings' }))
    .toHaveAttribute('href', '/?view=admin&tab=recordings');
});

test('recording settings keep running and failed maintenance visible while tools are collapsed', async ({ page }) => {
  const state = await openRecordings(page, { view: 'admin',
    catalogStatus: { catalog: { call_count: 28_416 },
      maintenance: { state: 'RUNNING', kind: 'REINDEX', inspected: 4_200 } } });
  const workspace = page.locator('.recordings-admin');
  await expect(workspace).toContainText('Reindex running · 4,200 checked');
  await expect(workspace.getByRole('button', { name: 'Run reindex calls' })).toBeHidden();
  state.catalogStatus.maintenance.state = 'FAILED';
  await expect(workspace).toContainText('Maintenance could not finish. Review the receiver log before trying again.');
  await expect(workspace.locator('.recordings-catalog-facts')).toContainText('Failed');
});

test('transcription settings save a write-only key and show catalog progress', async ({ page }) => {
  const state = await openRecordings(page, { view: 'admin', tab: 'transcription', keyConfigured: true });
  await expect(page.getByRole('checkbox', { name: 'Transcribe managed calls' })).toBeVisible();
  await expect(page.locator('.recordings-admin')).toContainText('3');
  await expect(page.getByRole('button', { name: 'Clear saved key' })).toBeVisible();
  const save = page.getByRole('button', { name: 'Save transcription settings' });
  await expect(save).toBeHidden();
  await page.locator('.recordings-admin .ui-toggle').click();
  await expect(page.getByRole('checkbox', { name: 'Transcribe managed calls' })).toBeChecked();
  await page.getByLabel('Transcription endpoint URL').fill('http://127.0.0.1:8000/v1/audio/transcriptions');
  await page.getByLabel('Model ID').fill('test-model');
  await page.getByLabel('Minimum call length (ms)').fill('750');
  await page.getByLabel('API key').fill('replacement-key');
  state.settingsWriteError = 'Transcription settings could not be saved. Try again.';
  await save.click();
  await expect(page.locator('.recordings-admin')).toContainText(state.settingsWriteError);
  await expect(page.getByLabel('API key')).toHaveValue('replacement-key');
  await expect(page.getByLabel('Model ID')).toHaveValue('test-model');
  await expect(save).toBeEnabled();
  state.settingsWriteError = null;
  await save.click();
  await expect(save).toBeHidden();
  await expect(page.locator('.recordings-admin')).not.toContainText('Transcription settings saved.');
  expect(state.settingsWrites.at(-1)).toMatchObject({ transcription_enabled: true,
    transcription_url: 'http://127.0.0.1:8000/v1/audio/transcriptions',
    transcription_model: 'test-model', transcription_min_duration_ms: 750,
    transcription_api_key: 'replacement-key' });
  await expect(page.getByLabel('API key')).toHaveValue('');
  await expect(page.locator('.recordings-admin')).not.toContainText('replacement-key');
  await page.getByLabel('Model ID').fill('next-model');
  await expect(save).toBeVisible();
  await page.getByLabel('Model ID').fill('test-model');
  await expect(save).toBeHidden();
  await page.getByLabel('Model ID').fill('next-model');
  await save.click();
  await expect(save).toBeHidden();
  expect(state.settingsWrites.at(-1)).not.toHaveProperty('transcription_api_key');
  await page.getByRole('button', { name: 'Clear saved key' }).click();
  const clearDialog = page.getByRole('dialog', { name: 'Clear saved transcription key' });
  await expect(clearDialog).toBeVisible();
  expect(state.settingsWrites.at(-1)).not.toHaveProperty('transcription_clear_api_key');
  await clearDialog.getByRole('button', { name: 'Clear saved key' }).click();
  await expect(clearDialog).toBeHidden();
  expect(state.settingsWrites.at(-1)).toMatchObject({ transcription_clear_api_key: true });
  await expect(page.getByRole('button', { name: 'Clear saved key' })).toBeHidden();
  await expect(save).toBeHidden();
  await expect(page.locator('.recordings-admin')).not.toContainText('Saved API key cleared.');
});

test('transcription settings fit a narrow dark viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openRecordings(page, { view: 'admin', tab: 'transcription', theme: 'dark', keyConfigured: true });
  await expect(page.getByLabel('Transcription endpoint URL')).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});

test('transcription progress distinguishes unavailable and zero counts and retains the latest status on refresh error', async ({ page }) => {
  const lastError = 'The transcription service returned a temporary error. '.repeat(12);
  const state = await openRecordings(page, { view: 'admin', tab: 'transcription',
    catalogStatus: { transcription: { pending: 0, completed: null, failed: 1, active: true,
      last_error: lastError } } });
  const progress = page.locator('section').filter({ hasText: 'Transcription progress' });
  await expect(progress.locator('.ui-metric-label')).toHaveText(['Pending', 'Failed']);
  await expect(progress.locator('.ui-metric strong')).toHaveText(['0', '1']);
  await expect(progress.locator('.recordings-transcription-error p')).toHaveText(lastError.trim());
  await expect(progress).toContainText('Transcribing a call');
  await expect(progress.getByRole('progressbar')).toHaveCount(0);

  state.catalogStatus.transcription = { pending: null, completed: 5, failed: 0, active: false };
  await progress.getByRole('button', { name: 'Refresh status' }).click();
  await expect(progress.locator('.ui-metric-label')).toHaveText(['Completed', 'Failed']);
  await expect(progress.locator('.ui-metric strong')).toHaveText(['5', '0']);
  await expect(progress.locator('.recordings-transcription-worker')).toHaveCount(0);
  await expect(progress.locator('.recordings-transcription-error')).toHaveCount(0);
  state.catalogStatusError = 'Transcription status could not be loaded. Try again.';
  await progress.getByRole('button', { name: 'Refresh status' }).click();
  await expect(progress.getByRole('alert')).toHaveText(state.catalogStatusError);
  await expect(progress.locator('.ui-metric strong')).toHaveText(['5', '0']);
  await expect(progress.getByRole('button', { name: 'Refresh status' })).toBeEnabled();
});

test('call catalog keeps all maintenance states and zero calls while omitting unavailable dates', async ({ page }) => {
  const state = await openRecordings(page, { view: 'admin',
    catalogStatus: { catalog: { call_count: 0 }, maintenance: { state: 'IDLE' } } });
  const catalog = page.locator('section').filter({ hasText: 'Call catalog' });
  await expect(catalog.locator('.ui-metric-label')).toHaveText('Listed calls');
  await expect(catalog.locator('.ui-metric strong')).toHaveText('0');
  await expect(catalog).not.toContainText('Oldest call');
  await expect(catalog).not.toContainText('Last recount');
  for (const [status, label] of [['QUEUED', 'Queued'], ['RUNNING', 'Running'],
    ['IN_PROGRESS', 'In Progress'], ['FAILED', 'Failed'], ['COMPLETED', 'Completed'], ['IDLE', 'Idle']]) {
    state.catalogStatus.maintenance = { state: status, kind: 'REINDEX', inspected: 0 };
    await catalog.getByRole('button', { name: 'Refresh status' }).click();
    await expect(catalog.locator('.recordings-catalog-facts')).toContainText(label);
    if (['QUEUED', 'RUNNING', 'IN_PROGRESS'].includes(status)) {
      await expect(catalog).toContainText('Reindex running · 0 checked');
    }
    if (status === 'FAILED') await expect(catalog).toContainText('Review the receiver log before trying again.');
    if (status === 'COMPLETED') await expect(catalog).toContainText('Maintenance finished. Call totals are current.');
  }
  state.catalogStatusError = 'Call catalog status could not be loaded. Try again.';
  await catalog.getByRole('button', { name: 'Refresh status' }).click();
  await expect(catalog.getByRole('alert')).toHaveText(state.catalogStatusError);
  await expect(catalog.locator('.ui-metric strong')).toHaveText('0');
  await expect(catalog.getByRole('button', { name: 'Refresh status' })).toBeEnabled();
});

for (const theme of ['light']) {
  for (const viewport of ['desktop', 'mobile']) {
    test(`recording administration panels match the approved ${theme} ${viewport} layout`, async ({ page }) => {
      await page.setViewportSize(viewport === 'desktop' ? { width: 1280, height: 1100 } :
        { width: 390, height: 844 });
      await openRecordings(page, { view: 'admin', tab: 'transcription', theme, keyConfigured: true,
        settings: { transcription_enabled: true,
          transcription_url: 'http://127.0.0.1:8000/v1/audio/transcriptions',
          transcription_model: 'speech-model', transcription_min_duration_ms: 750 },
        catalogStatus: { transcription: { pending: 36, completed: 12_408, failed: 2, active: true,
          last_error: 'The transcription service did not respond. Check that the service is running.' } } });
      const transcription = page.locator('.recordings-admin');
      await expect(transcription.locator('.ui-metric')).toHaveCount(3);
      const counts = await transcription.locator('.ui-metric').evaluateAll((tiles) =>
        tiles.map((tile) => tile.getBoundingClientRect().top));
      expect(new Set(counts).size).toBe(1);
      await page.evaluate(() => window.scrollTo(0, 0));
      await expect(page).toHaveScreenshot(`admin-transcription-${theme}-${viewport}.png`, { fullPage: true });
      expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth))
        .toBeLessThanOrEqual(0);

      await openRecordings(page, { view: 'admin', theme,
        catalogStatus: { catalog: { call_count: 28_416,
          oldest_call_ms: Date.parse('2026-07-03T08:42:00Z') },
          last_recount_ms: Date.parse('2026-09-28T09:14:00Z'), maintenance: { state: 'IDLE' } } });
      const catalog = page.locator('section').filter({ hasText: 'Call catalog' });
      await catalog.locator('details summary').click();
      await expect(catalog.getByRole('button', { name: 'Run reindex calls' })).toBeVisible();
      await page.evaluate(() => window.scrollTo(0, 0));
      await expect(page).toHaveScreenshot(`admin-call-catalog-${theme}-${viewport}.png`, { fullPage: true });
      expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth))
        .toBeLessThanOrEqual(0);
    });
  }
}

test('call details show a transcript and allow administrators to retry failures', async ({ page }) => {
  await openRecordings(page, { transcription: { status: 'complete',
    text: 'Engine four arriving.\nRequesting another unit.', stored_at_ms: Date.parse('2026-09-28T10:09:00Z') } });
  await openCallDetails(page);
  let detail = page.getByRole('dialog', { name: 'Call actions' });
  await selectRecordingDetailTab(detail, 'Transcript');
  await expect(detail.locator('.recordings-transcript')).toContainText('Engine four arriving.');
  await expect(detail.locator('.recordings-transcript')).toContainText('Requesting another unit.');
  await expect(detail.getByRole('button', { name: 'Retry transcription' })).toHaveCount(0);

  const retryState = await openRecordings(page, { transcription: { status: 'failed',
    text: null, stored_at_ms: null } });
  await openCallDetails(page);
  detail = page.getByRole('dialog', { name: 'Call actions' });
  await selectRecordingDetailTab(detail, 'Transcript');
  await expect(detail.getByRole('button', { name: 'Retry transcription' })).toBeVisible();
  await detail.getByRole('button', { name: 'Retry transcription' }).click();
  await expect(detail.locator('.recordings-transcript')).toContainText('Pending transcription.');
  expect(retryState.transcription.status).toBe('PENDING');
  await detail.getByRole('button', { name: 'Show less', exact: true }).click();
  await detail.getByRole('button', { name: 'Show more', exact: true }).click();
  await selectRecordingDetailTab(detail, 'Transcript');
  await expect(detail.locator('.recordings-transcript')).toContainText('Pending transcription.');
  await expect(detail.getByRole('button', { name: 'Retry transcription' })).toHaveCount(0);

  await openRecordings(page, { admin: false,
    transcription: { status: 'failed', text: null, stored_at_ms: null } });
  await openCallDetails(page);
  detail = page.getByRole('dialog', { name: 'Call actions' });
  await selectRecordingDetailTab(detail, 'Transcript');
  await expect(detail.locator('.recordings-transcript')).toContainText('Transcription failed.');
  await expect(detail.getByRole('button', { name: 'Retry transcription' })).toHaveCount(0);

  await openRecordings(page, { transcription: { status: 'too_short', text: null, stored_at_ms: null } });
  await openCallDetails(page);
  detail = page.getByRole('dialog', { name: 'Call actions' });
  await selectRecordingDetailTab(detail, 'Transcript');
  await expect(detail.locator('.recordings-transcript')).toContainText('shorter than the minimum length');

  await openRecordings(page, { transcription: { status: 'disabled', text: null, stored_at_ms: null } });
  await openCallDetails(page);
  detail = page.getByRole('dialog', { name: 'Call actions' });
  await selectRecordingDetailTab(detail, 'Transcript');
  await expect(detail.locator('.recordings-transcript')).toContainText('Transcription is off.');
});

test('call detail tabs retain identities, protocol, recording metadata, download, and transcript', async ({ page }) => {
  const richCall = {
    ...linkedCall, call_type: 'PATCH', talkgroup_alias: 'Dispatch Patch',
    target_entity_ref: { kind: 'patch_group', radio_system_key: systemKey, identity_key: 'v1-p-00001-001-1201' },
    source_description: 'Fire engine response crew', source_group: 'Field units',
    talkgroup_description: 'Regional fire response', talkgroup_group: 'Fire',
    source_home_system_name: 'Metro Home Network', source_home_wacn: 1, source_home_system_id: 1,
    source_home_id: 30914, target_home_system_name: 'Metro Home Network', target_home_wacn: 1,
    target_home_system_id: 1, target_home_id: 1201,
    frequency_hz: 851_012_500, timeslot: 1, encrypted: false, wacn: 1, system_id: 1,
    rfss_id: 1, site_id: 2, nac: 293,
    audio_from: { name: 'North Ridge', wacn: 1, system_id: 1, rfss_id: 1, site_id: 2 },
    also_received_on: [{ name: 'Central Simulcast', wacn: 1, system_id: 1, rfss_id: 1, site_id: 3 }],
    call_id: 'sample-recording-call-17', end_ms: call.start_ms + call.duration_ms, size_bytes: 184320,
    alias_list_id: 7, alias_list_name: 'Metro Alias List',
    patch_members: [{ kind: 'talkgroup', id: 1202, alias: 'Fireground',
      home_system_name: 'Metro Home Network', home_wacn: 1, home_system_id: 1, home_identity_id: 1202,
      entity_ref: { kind: 'talkgroup', radio_system_key: systemKey, identity_key: 'v1-g-00001-001-1202' } }]
  };
  await openRecordings(page, { call: richCall,
    transcription: { status: 'COMPLETE', text: 'Engine four arriving at staging.', stored_at_ms: call.start_ms + 60_000 } });
  await openCallDetails(page);
  const detail = page.getByRole('dialog', { name: 'Call actions', exact: true });
  await expect(detail.getByRole('tab')).toHaveText(['Overview', 'Transcript', 'Identities', 'Technical', 'Recording']);
  const overview = detail.getByRole('tab', { name: 'Overview', exact: true });
  await expect(overview).toHaveAttribute('aria-selected', 'true');
  await overview.focus();
  await overview.press('ArrowRight');
  await expect(detail.getByRole('tab', { name: 'Transcript', exact: true })).toBeFocused();
  await expect(detail.getByRole('tab', { name: 'Transcript', exact: true })).toHaveAttribute('aria-selected', 'true');
  await page.keyboard.press('End');
  await expect(detail.getByRole('tab', { name: 'Recording', exact: true })).toBeFocused();
  await page.keyboard.press('Home');
  await expect(overview).toBeFocused();
  await expect(detail.getByRole('tabpanel').filter({ visible: true })).toContainText('Dispatch Patch');
  await expect(detail.getByRole('tabpanel').filter({ visible: true })).toContainText('North Ridge Channel');

  await selectRecordingDetailTab(detail, 'Identities');
  let panel = detail.getByRole('tabpanel').filter({ visible: true });
  for (const text of ['Source Radio ID', '30914', 'Fire engine response crew', 'Patch ID', '1201',
    'Patch member identities', 'Fireground', 'Member 1 home identity', '1202']) await expect(panel).toContainText(text);
  await expect(panel.getByRole('link')).toHaveCount(0);
  await expect(panel).toContainText('Fireground');

  await selectRecordingDetailTab(detail, 'Technical');
  panel = detail.getByRole('tabpanel').filter({ visible: true });
  for (const text of ['WACN', 'SysID', 'NAC', '0x125 (293)', 'Received site identities',
    'Winning site ID', 'Received site 1 ID']) await expect(panel).toContainText(text);

  await selectRecordingDetailTab(detail, 'Recording');
  panel = detail.getByRole('tabpanel').filter({ visible: true });
  for (const text of ['Recording ID', 'Call ID', 'sample-recording-call-17', 'File size',
    'Channel ID', 'Source home system name', 'Metro Home Network', 'Target home identity']) await expect(panel).toContainText(text);
  const download = detail.getByRole('link', { name: 'Download recording', exact: true });
  await expect(download).toBeVisible();
  await expect(download).toHaveAttribute('href', '/api/v1/recordings/calls/17/audio');
  await expect(download).toHaveAttribute('download', '');

  await selectRecordingDetailTab(detail, 'Transcript');
  await expect(detail.getByRole('tabpanel').filter({ visible: true })).toContainText('Engine four arriving at staging.');
  await expect(detail.getByRole('button', { name: /Play once|Continue from here|Add to queue/ })).toHaveCount(3);
  await page.keyboard.press('Escape');
  await expect(page.locator('.recordings-call-info').first()).toBeFocused();
});

for (const theme of ['light', 'dark']) {
  for (const [device, viewport] of [['desktop', { width: 1280, height: 900 }], ['phone', { width: 390, height: 844 }]]) {
    test(`tabbed call details ${theme} ${device} retain visible actions`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await openRecordings(page, { theme, call: { ...linkedCall, frequency_hz: 851_012_500, timeslot: 1 },
        transcription: { status: 'COMPLETE', text: 'Engine four arriving at staging.', stored_at_ms: call.start_ms + 60_000 } });
      await openCallDetails(page);
      const detail = page.getByRole('dialog', { name: 'Call actions', exact: true });
      await expect(detail.getByRole('tab')).toHaveCount(5);
      for (const name of ['Play once', 'Continue from here', 'Add to queue']) {
        await expect(detail.getByRole('button', { name, exact: true })).toBeVisible();
      }
      await selectRecordingDetailTab(detail, 'Recording');
      await expect(detail.getByRole('link', { name: 'Download recording', exact: true })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
      await expect(detail).toHaveScreenshot(`recordings-details-${theme}-${device}.png`, componentScreenshot);
    });
  }
}

test('call detail footer executes each playback choice once after closing', async ({ page }) => {
  await page.addInitScript(() => {
    window.playedRecordingSources = [];
    HTMLMediaElement.prototype.play = function () {
      window.playedRecordingSources.push(this.src);
      return Promise.resolve();
    };
    HTMLMediaElement.prototype.pause = function () {};
  });
  const state = await openRecordings(page, { call: linkedCall });
  await openCallDetails(page);
  let detail = page.getByRole('dialog', { name: 'Call actions', exact: true });
  await detail.getByRole('button', { name: 'Add to queue', exact: true }).click();
  await expect(detail).toBeHidden();
  await expect(page.locator('#audio-dock .audio-dock-queue-count')).toContainText('1 queued');
  await expect(page.locator('.recordings-call-status')).toHaveText('Queued');
  expect(await page.evaluate(() => window.playedRecordingSources)).toEqual([]);
  await openCallDetails(page);
  detail = page.getByRole('dialog', { name: 'Call actions', exact: true });
  await detail.getByRole('button', { name: 'Play once', exact: true }).click();
  await expect(detail).toBeHidden();
  await expect.poll(() => page.evaluate(() => window.playedRecordingSources)).toHaveLength(1);
  expect(await page.evaluate(() => window.playedRecordingSources[0])).toMatch(/calls\/17\/audio$/);
  await openCallDetails(page);
  detail = page.getByRole('dialog', { name: 'Call actions', exact: true });
  await detail.getByRole('button', { name: 'Continue from here', exact: true }).click();
  await expect(detail).toBeHidden();
  await expect.poll(() => page.evaluate(() => window.playedRecordingSources)).toHaveLength(2);
  await expect.poll(() => state.requestedFilters.some((filter) => filter.sort === 'asc' &&
    filter.from_ms === String(call.start_ms))).toBe(true);
});

for (const [device, viewport] of [['desktop', { width: 1280, height: 900 }],
  ['phone', { width: 390, height: 844 }]]) {
  for (const size of ['collapsed', 'minimal', 'full']) {
    test(`direct row queue and pointer paging preserve the ${size} player queue on ${device}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await page.addInitScript(() => {
        window.playedRecordingSources = [];
        HTMLMediaElement.prototype.play = function () {
          window.playedRecordingSources.push(this.src);
          return Promise.resolve();
        };
        HTMLMediaElement.prototype.pause = function () {};
      });
      const state = await openRecordings(page, { calls: mixedRecordingCalls(), paginate: true });
      await page.locator('.recordings-call-info').first().click();
      await page.getByRole('dialog', { name: 'Call actions', exact: true }).getByRole('button', { name: 'Add to queue', exact: true }).click();
      const dock = page.locator('#audio-dock');
      await expect(dock).toHaveAttribute('data-source', 'recordings');
      await expect(page.getByRole('dialog', { name: 'Play recording', exact: true })).toHaveCount(0);
      await expect(page.locator('.recordings-call').first().locator('.recordings-call-status')).toHaveText('Queued');
      await expect(dock.locator('.audio-dock-queue-count')).toContainText('1 queued');
      expect(await page.evaluate(() => window.playedRecordingSources)).toEqual([]);
      if (size !== 'minimal') await dock.locator('#audio-dock-size-handle').press(size === 'collapsed' ? 'Home' : 'End');
      await expect(dock).toHaveAttribute('data-state', size);
      const next = page.locator('.recordings-pager').last().getByRole('button', { name: 'Next', exact: true });
      // Scroll to the page end as a user would; the measured player reserve must provide space.
      await next.evaluate((element) => element.scrollIntoView({ block: 'end', behavior: 'instant' }));
      await expect.poll(async () => {
        const pagerBounds = await next.boundingBox();
        const dockBounds = await dock.boundingBox();
        return pagerBounds.y + pagerBounds.height <= dockBounds.y - 4;
      }).toBe(true);
      await next.click();
      await expect(page.locator('.recordings-call')).toHaveCount(5);
      expect(state.requestedFilters.at(-1)).toMatchObject({ limit: '25', cursor: 'fixture:25' });
      await expect(dock.locator('.audio-dock-queue-count')).toContainText('1 queued');
      expect(await page.evaluate(() => window.playedRecordingSources)).toEqual([]);
    });
  }
}

for (const theme of ['light', 'dark']) {
  test(`compact recordings and unified call actions in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    const first = { ...linkedCall, transcript_excerpt: 'Engine four arriving. Requesting another unit.',
      frequency_hz: 851_012_500, site_entity_ref: { kind: 'channel', key: channelId },
      audio_from: { name: 'North Ridge', configuration_id: channelId, wacn: 1, system_id: 1, rfss_id: 1, site_id: 2 } };
    const second = { ...first, id: 18, source_id: 30915, source_alias: 'Engine 5',
      source_ota_alias: null, source_entity_ref: null, start_ms: first.start_ms + 60_000 };
    await openRecordings(page, { theme, call: first, calls: [second, first], directListen: true });
    const cards = page.locator('.recordings-call');
    await expect(cards).toHaveCount(2);
    await expect(cards.locator('a, .recordings-card-play, .recordings-call-details, summary')).toHaveCount(0);
    await expect(cards.locator('.recordings-call-actions > button')).toHaveCount(2);
    await expect(cards.first()).toContainText('Fire Dispatch');
    await expect(page.locator('.recordings-library')).toHaveScreenshot(`recordings-approved-${theme}.png`, componentScreenshot);
    await cards.first().locator('.recordings-call-info').click();
    const actions = page.getByRole('dialog', { name: 'Call actions', exact: true });
    await expectCallActionDescriptions(actions);
    await expect(actions).toHaveScreenshot(`recordings-actions-${theme}.png`, componentScreenshot);
    await expect(actions.getByRole('link', { name: 'Filter by talkgroup', exact: true }))
      .toHaveAttribute('href', /talkgroup_identity_key=v1-g-00001-001-1201/);
    await expect(actions.getByRole('link', { name: 'Open talkgroup page', exact: true }))
      .toHaveAttribute('href', /view=group-identity/);
    const talkgroup = actions.locator('.recordings-call-entity[data-entity="talkgroup"]');
    await expect(talkgroup).toContainText('Fire Dispatch');
    await expect(talkgroup.getByRole('link')).toHaveCount(2);
    for (const width of [1280, 800, 390, 320]) {
      await page.setViewportSize({ width, height: 900 });
      expect(await actions.evaluate(element => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(0);
      for (const control of await actions.locator('.recordings-call-entity a').all()) {
        const box = await control.boundingBox();
        expect(box.x).toBeGreaterThanOrEqual(0);
        expect(box.x + box.width).toBeLessThanOrEqual(width);
      }
      if (width === 390) await expect(actions).toHaveScreenshot(`recordings-actions-mobile-${theme}.png`, componentScreenshot);
      if (width === 320 || width === 800 && theme === 'light')
        await expect(actions).toHaveScreenshot(`recordings-actions-flow-${theme}-${width}.png`, componentScreenshot);
    }
    await page.keyboard.press('Escape');
    await expect(page.locator('.recordings-results-body')).toHaveScreenshot(`recordings-approved-mobile-${theme}.png`, componentScreenshot);
    await expect(cards.first().locator('.recordings-call-info')).toBeFocused();
    await cards.first().locator('.recordings-call-select').check();
    await expect(page.locator('.recordings-selected')).toContainText('1 selected');
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
  });
}

test('recording rows expose one menu and keep playback choices explicit', async ({ page }) => {
  await stubRecordingAudio(page);
  await openRecordings(page, { calls: [linkedCall, { ...linkedCall, id: 18 }] });
  const cards = page.locator('.recordings-call');
  await expect(cards.locator('a, .recordings-card-play, .recordings-call-details, summary')).toHaveCount(0);
  await expect(cards.locator('.recordings-call-actions > button')).toHaveCount(2);
  await recordingAction(page, cards.first(), 'Play once');
  await expect.poll(() => page.evaluate(() => window.recordingPlays.length)).toBe(1);
  await expect(cards.first()).toHaveClass(/recordings-call-playing/);
  await recordingAction(page, cards.nth(1), 'Add to queue');
  await expect(cards.nth(1)).toHaveClass(/recordings-call-queued/);
  await expect(page.locator('#audio-dock .audio-dock-queue-count')).toContainText('1 queued');
  expect(await page.evaluate(() => window.recordingPlays.length)).toBe(1);
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

async function setMountedCallClickMode(page, mode) {
  const dock = page.locator('#audio-dock');
  await expect(dock).toHaveAttribute('data-source', 'recordings');
  await dock.locator('#audio-dock-size-handle').press('End');
  await expect(dock).toHaveAttribute('data-state', 'full');
  const field = dock.getByRole('combobox', { name: 'When a call is clicked', exact: true }).filter({ visible: true }).first();
  await field.selectOption(mode);
  await expect(field).toHaveValue(mode);
}

test('recording row clicks use the default queue mode and changes made while the page stays mounted', async ({ page }) => {
  await stubRecordingAudio(page);
  const state = await openRecordings(page, { calls: [linkedCall,
    { ...linkedCall, id: 18, start_ms: call.start_ms + 60_000 },
    { ...linkedCall, id: 19, start_ms: call.start_ms + 120_000 }] });
  const rows = page.locator('.recordings-call');
  await rows.nth(0).locator('.recordings-call-time').click();
  await expect.poll(() => page.evaluate(() => window.recordingPlays)).toEqual([expect.stringMatching(/calls\/17\/audio$/)]);
  await rows.nth(1).locator('.recordings-call-time').click();
  await expect(rows.nth(1)).toHaveClass(/recordings-call-queued/);
  expect(await page.evaluate(() => window.recordingPlays)).toHaveLength(1);
  await expect(page.locator('#audio-dock .audio-dock-queue-count')).toContainText('1 queued');
  await setMountedCallClickMode(page, 'once');
  await rows.nth(1).locator('.recordings-call-time').click();
  await expect.poll(() => page.evaluate(() => window.recordingPlays)).toEqual([
    expect.stringMatching(/calls\/17\/audio$/), expect.stringMatching(/calls\/18\/audio$/)
  ]);
  await expect(rows.nth(1)).toHaveClass(/recordings-call-playing/);
  await expect(page.locator('#audio-dock .audio-dock-queue-count')).toContainText('0 queued');
  await setMountedCallClickMode(page, 'continue');
  await rows.nth(2).locator('.recordings-call-time').click();
  await expect.poll(() => page.evaluate(() => window.recordingPlays)).toHaveLength(3);
  await expect(rows.nth(2)).toHaveClass(/recordings-call-playing/);
  await expect.poll(() => state.requestedFilters.some(filter => filter.sort === 'asc' &&
    filter.from_ms === String(call.start_ms + 120_000))).toBe(true);
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('recording row keyboard playback and nested menu and selection controls stay independent', async ({ page }) => {
  await stubRecordingAudio(page);
  await openRecordings(page, { calls: [linkedCall, { ...linkedCall, id: 18 }] });
  const rows = page.locator('.recordings-call');
  const first = rows.first(), second = rows.nth(1);
  await first.locator('.recordings-call-select').check();
  await expect(first.locator('.recordings-call-select')).toBeChecked();
  expect(await page.evaluate(() => window.recordingPlays)).toHaveLength(0);
  await first.locator('.recordings-call-info').click();
  const actions = page.getByRole('dialog', { name: 'Call actions', exact: true });
  await expect(actions).toBeVisible();
  expect(await page.evaluate(() => window.recordingPlays)).toHaveLength(0);
  await page.keyboard.press('Escape');
  await expect(first.locator('.recordings-call-info')).toBeFocused();
  const play = first.locator('.recordings-call-title');
  await expect(play).toHaveJSProperty('tagName', 'BUTTON');
  await play.focus();
  await play.press('Enter');
  await expect.poll(() => page.evaluate(() => window.recordingPlays.length)).toBe(1);
  await expect(first).toHaveClass(/recordings-call-playing/);
  await second.locator('.recordings-call-title').focus();
  await second.locator('.recordings-call-title').press('Space');
  await expect(second).toHaveClass(/recordings-call-queued/);
  expect(await page.evaluate(() => window.recordingPlays)).toHaveLength(1);
  await expect(first.locator('.recordings-call-select')).toBeChecked();
  await expect(second.locator('.recordings-call-select')).not.toBeChecked();
});

test('selecting text in a recording row does not start playback', async ({ page }) => {
  await stubRecordingAudio(page);
  await openRecordings(page, { call: linkedCall });
  const time = page.locator('.recordings-call-time');
  await time.evaluate(element => {
    const range = document.createRange(); range.selectNodeContents(element);
    const selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
  });
  await time.dispatchEvent('click', { detail: 1, button: 0 });
  expect(await page.evaluate(() => window.getSelection().toString())).not.toBe('');
  expect(await page.evaluate(() => window.recordingPlays)).toHaveLength(0);
  await page.evaluate(() => window.getSelection().removeAllRanges());
  await time.click();
  await expect.poll(() => page.evaluate(() => window.recordingPlays.length)).toBe(1);
});

test('page selection selects and deselects displayed calls and reflects partial selection', async ({ page }) => {
  await openRecordings(page, { calls: mixedRecordingCalls(), paginate: true });
  const pageCheck = page.getByRole('checkbox', { name: 'Select all calls on this page', exact: true });
  const callChecks = page.locator('.recordings-call-select');
  await expect(callChecks).toHaveCount(25);
  await expect(pageCheck).not.toBeChecked();
  await expect(pageCheck).toBeEnabled();
  const toolbar = page.locator('.recordings-results-toolbar');
  await expect(toolbar.locator('.recordings-result-count')).toBeVisible();
  await expect(toolbar.locator('.recordings-selected')).toBeHidden();
  await pageCheck.check();
  await expect(toolbar.locator('.recordings-result-count > span').first()).toBeHidden();
  await expect(toolbar.locator('.recordings-selected')).toBeVisible();
  await expect(page.locator('.recordings-call-select:checked')).toHaveCount(25);
  await expect(page.locator('.recordings-selected')).toContainText('25 selected');
  await expect(pageCheck).toBeChecked();
  await expect.poll(() => pageCheck.evaluate((input) => input.indeterminate)).toBe(false);

  await callChecks.first().uncheck();
  await expect(page.locator('.recordings-selected')).toContainText('24 selected');
  await expect(pageCheck).not.toBeChecked();
  await expect.poll(() => pageCheck.evaluate((input) => input.indeterminate)).toBe(true);
  await pageCheck.check();
  await expect(page.locator('.recordings-call-select:checked')).toHaveCount(25);
  await expect.poll(() => pageCheck.evaluate((input) => input.indeterminate)).toBe(false);

  await pageCheck.uncheck();
  await expect(page.locator('.recordings-call-select:checked')).toHaveCount(0);
  await expect(page.locator('.recordings-selected')).toBeHidden();
  await expect(toolbar.locator('.recordings-result-count')).toBeVisible();
  await expect(page.locator('.recordings-call')).toHaveCount(25);
});

test('page selection preserves other pages and Clear selection resets every page', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openRecordings(page, { calls: mixedRecordingCalls(), paginate: true });
  const pageCheck = page.getByRole('checkbox', { name: 'Select all calls on this page', exact: true });
  const pager = page.locator('.recordings-pager').last();
  await expect(page.locator('.recordings-call')).toHaveCount(25);
  await pageCheck.check();
  await pager.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.recordings-call')).toHaveCount(5);
  await expect(pageCheck).not.toBeChecked();
  await expect.poll(() => pageCheck.evaluate((input) => input.indeterminate)).toBe(false);
  await pageCheck.check();
  await expect(page.locator('.recordings-selected')).toContainText('30 selected');
  await pageCheck.uncheck();
  await expect(page.locator('.recordings-call-select:checked')).toHaveCount(0);
  await expect(page.locator('.recordings-selected')).toContainText('25 selected');
  await pager.getByRole('button', { name: 'Previous', exact: true }).click();
  await expect(page.locator('.recordings-call-select:checked')).toHaveCount(25);
  await expect(pageCheck).toBeChecked();

  await page.locator('.recordings-selected').getByRole('button', { name: 'Clear selection', exact: true }).click();
  await expect(pageCheck).not.toBeChecked();
  await expect.poll(() => pageCheck.evaluate((input) => input.indeterminate)).toBe(false);
  await expect(page.locator('.recordings-selected')).toBeHidden();
  await expect(page.locator('.recordings-call-select').first()).toBeFocused();
  await pager.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.recordings-call')).toHaveCount(5);
  await expect(page.locator('.recordings-call-select:checked')).toHaveCount(0);
  await expect(pageCheck).not.toBeChecked();
});

test('page selection resets when a new call search is applied', async ({ page }) => {
  const state = await openRecordings(page, { calls: mixedRecordingCalls(), paginate: true });
  const pageCheck = page.getByRole('checkbox', { name: 'Select all calls on this page', exact: true });
  await expect(page.locator('.recordings-call')).toHaveCount(25);
  await pageCheck.check();
  await page.getByRole('searchbox', { name: 'Full text' }).fill('Fire Dispatch');
  await page.getByRole('button', { name: 'Search recordings', exact: true }).click();
  await expect.poll(() => state.requestedFilters.at(-1).transcript).toBe('Fire Dispatch');
  await expect(page.locator('.recordings-call-select:checked')).toHaveCount(0);
  await expect(page.locator('.recordings-selected')).toBeHidden();
  await expect(pageCheck).not.toBeChecked();
  await expect.poll(() => pageCheck.evaluate((input) => input.indeterminate)).toBe(false);
});

for (const account of [{ name: 'secondary administrator', admin: true, primary: false },
  { name: 'guest', admin: false }]) {
  test(`page selection is unavailable to a ${account.name}`, async ({ page }) => {
    await openRecordings(page, { ...account, calls: mixedRecordingCalls(), paginate: true });
    await expect(page.locator('.recordings-call')).toHaveCount(25);
    await expect(page.getByRole('checkbox', { name: 'Select all calls on this page', exact: true })).toHaveCount(0);
    await expect(page.locator('.recordings-call-select')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Review delete', exact: true })).toHaveCount(0);
    await expect(page.locator('.recordings-results-state .recordings-result-count')).toContainText('25 calls shown');
    await expect(page.locator('.recordings-results-toolbar .recordings-pager-top')).toBeVisible();
  });
}

test('page selection cannot select an empty cursor batch or a zero-match search', async ({ page }) => {
  const state = await openRecordings(page, { calls: mixedRecordingCalls(), paginate: true,
    emptyFirstBatch: true });
  const pageCheck = page.locator('.recordings-page-select');
  const pager = page.locator('.recordings-pager').last();
  await expect(page.locator('.recordings-call')).toHaveCount(0);
  await expect(pageCheck).toBeDisabled();
  await expect(pageCheck).not.toBeChecked();
  await expect(pager.getByRole('button', { name: 'Next', exact: true })).toBeEnabled();
  await pager.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.recordings-call')).toHaveCount(5);
  await expect(pageCheck).toBeEnabled();
  await pageCheck.check();
  state.matchCalls = false;
  const requests = state.requestedFilters.length;
  await page.getByRole('button', { name: 'Search recordings', exact: true }).click();
  await expect.poll(() => state.requestedFilters.length).toBe(requests + 1);
  await expect(page.locator('.recordings-call')).toHaveCount(0);
  await expect(page.locator('.recordings-results')).toContainText('No matching calls.');
  await expect(pageCheck).toBeDisabled();
  await expect(page.locator('.recordings-selected')).toBeHidden();
  await expect.poll(() => pageCheck.evaluate((input) => input.indeterminate)).toBe(false);
});

test('page selection deletes only the selected page calls after confirmation', async ({ page }) => {
  const calls = mixedRecordingCalls();
  const state = await openRecordings(page, { calls, paginate: true });
  await expect(page.locator('.recordings-call')).toHaveCount(25);
  await page.locator('.recordings-pager').last().getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.recordings-call')).toHaveCount(5);
  await page.getByRole('checkbox', { name: 'Select all calls on this page', exact: true }).check();
  await page.getByRole('button', { name: 'Review delete', exact: true }).click();
  const dialog = page.getByRole('alertdialog', { name: 'Delete recordings', exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('Delete 5 selected managed recordings?');
  await expect(dialog).toHaveAttribute('aria-describedby', /-message$/);
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeFocused();
  expect(state.deletionWrites).toEqual([]);
  await page.keyboard.press('Enter');
  await expect(dialog).toBeHidden();
  expect(state.deletionWrites).toEqual([]);
  await expect(page.locator('.recordings-call-select:checked')).toHaveCount(5);
  await expect(page.getByRole('button', { name: 'Review delete', exact: true })).toBeFocused();
  await page.getByRole('button', { name: 'Review delete', exact: true }).click();
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeFocused();
  await dialog.getByRole('button', { name: 'Delete recordings', exact: true }).click();
  await expect.poll(() => state.deletionWrites).toEqual([{ ids: calls.slice(25).map((item) => item.id) }]);
  await expect(dialog).toBeHidden();
  await expect(page.locator('.recordings-call')).toHaveCount(25);
  await expect(page.locator('.recordings-call-select:checked')).toHaveCount(0);
  await expect(page.getByRole('checkbox', { name: 'Select all calls on this page', exact: true })).not.toBeChecked();
  expect(state.calls.map((item) => item.id)).toEqual(calls.slice(0, 25).map((item) => item.id));
});

test('cursor paging keeps selection across pages and Clear selection leaves results intact', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await openRecordings(page, { calls: mixedRecordingCalls(), paginate: true });
  const pager = page.locator('.recordings-pager').last();
  await expect(page.locator('.recordings-call')).toHaveCount(25);
  await expect(pager.locator('.ui-browse-pager-count')).toHaveText('Page 1');
  await expect(page.locator('.recordings-result-count')).not.toContainText(/Page \d+/i);
  await expect(pager.getByRole('button', { name: 'Previous', exact: true })).toBeDisabled();
  await page.locator('.recordings-call-select').first().check();
  await pager.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.recordings-call')).toHaveCount(5);
  await expect(pager.locator('.ui-browse-pager-count')).toHaveText('Page 2');
  await expect(pager.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
  await page.locator('.recordings-call-select').first().check();
  const selection = page.locator('.recordings-selected');
  await expect(selection).toContainText('2 selected');
  const requests = state.requestedFilters.length;
  await selection.getByRole('button', { name: 'Clear selection', exact: true }).click();
  await expect(selection).toBeHidden();
  await expect(page.locator('.recordings-call-select:checked')).toHaveCount(0);
  await expect(page.locator('.recordings-call-select').first()).toBeFocused();
  expect(state.requestedFilters).toHaveLength(requests);
  await expect(page.locator('.recordings-call')).toHaveCount(5);
  await pager.getByRole('button', { name: 'Previous', exact: true }).click();
  await expect(page.locator('.recordings-call')).toHaveCount(25);
  await expect(page.locator('.recordings-call-select:checked')).toHaveCount(0);
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('cursor');
});


for (const theme of ['light', 'dark']) for (const device of ['desktop', 'phone']) {
  test(`workspace ${theme} ${device} keeps basic search and mixed conventional identities readable`, async ({ page }) => {
    await page.setViewportSize(device === 'phone' ? { width: 390, height: 844 } : { width: 1280, height: 900 });
    const calls = [
      { ...linkedCall, transcript_excerpt: 'Unit twelve, radio check. Loud and clear.' },
      { ...linkedCall, id: 18, protocol: 'NBFM', call_type: 'CONVENTIONAL', talkgroup_id: null,
        talkgroup_alias: null, target_entity_ref: null, source_id: null, source_alias: null, source_ota_alias: null,
        channel_name: 'County fire dispatch', frequency_hz: 154430000, transcript_excerpt: 'Station two, stand by for the scheduled test.' },
      { ...linkedCall, id: 19, protocol: 'AM', call_type: 'CONVENTIONAL', talkgroup_id: null,
        talkgroup_alias: null, target_entity_ref: null, channel_name: 'Airport tower', frequency_hz: 118500000 },
      { ...linkedCall, id: 20, protocol: 'APCO25', call_type: 'CONVENTIONAL', talkgroup_id: 0,
        talkgroup_alias: null, target_entity_ref: null, channel_name: 'Digital conventional', frequency_hz: 155190000 },
      { ...linkedCall, id: 21, protocol: 'DMR', talkgroup_id: 81, talkgroup_alias: 'Water operations' },
      { ...linkedCall, id: 22, protocol: 'NXDN', talkgroup_id: 0, talkgroup_alias: 'Local dispatch', system_key: 'nxdn-c:global:7', system_name: 'City transit',
        radio_system_entity_ref: { kind: 'radio_system', key: 'nxdn-c:global:7' }, target_entity_ref: { kind: 'talkgroup', radio_system_key: 'nxdn-c:global:7', identity_key: 'v1-g-x-x-0' } }
    ];
    await openRecordings(page, { calls, theme });
    await expect(page.locator('.recordings-call')).toHaveCount(6);
    await expect(page.locator('.recordings-call-title')).toContainText(['Fire Dispatch', 'County fire dispatch', 'Airport tower', 'Digital conventional', 'Water operations', 'Local dispatch']);
    await expect(page.locator('.recordings-call').nth(3).locator('.recordings-call-identity')).toHaveCount(0);
    await expect(page.locator('.recordings-call').nth(5).locator('.recordings-call-identity')).toHaveText('0');
    const filters = page.locator('#recordings-filters-toggle');
    if (device === 'desktop') {
      await expect(filters).toBeHidden();
      await expect(page.locator('h2.recordings-filter-title')).toContainText('Filters');
      await expect(page.getByRole('searchbox', { name: 'Full text', exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Search recordings', exact: true })).toBeVisible();
    } else {
      await expect(filters).toBeVisible();
      await expect(filters).toHaveAttribute('aria-expanded', 'false');
      await expect(page.getByRole('searchbox', { name: 'Full text', exact: true })).toBeHidden();
      await expect(page.locator('.recordings-filter-summary')).toContainText('Past 24 hours');
      await expect(page.locator('.recordings-call').first()).toBeInViewport();
    }
    await expect(page.getByRole('button', { name: /Share|Activity|Address|Receiver node/ })).toHaveCount(0);
    const layout = await page.evaluate(() => {
      const left = document.querySelector('.recordings-filter-section').getBoundingClientRect();
      const right = document.querySelector('.recordings-results-panel').getBoundingClientRect();
      return { left: { x: left.x, y: left.y, right: left.right, bottom: left.bottom, width: left.width },
        right: { x: right.x, y: right.y, width: right.width }, overflow: document.documentElement.scrollWidth - innerWidth };
    });
    expect(layout.overflow).toBeLessThanOrEqual(0);
    if (device === 'desktop') {
      expect(Math.abs(layout.right.y - layout.left.y)).toBeLessThanOrEqual(1);
      expect(layout.right.x).toBeGreaterThan(layout.left.right);
      expect(layout.right.width / layout.left.width).toBeGreaterThan(2);
    }
    else expect(layout.right.y).toBeLessThan(400);
    await expect(page.locator('.recordings-library')).toHaveScreenshot(`recordings-workspace-${theme}-${device}.png`, componentScreenshot);
    const picker = await openPicker(page);
    await expect(picker.locator('.recordings-picker-choice')).toHaveCount(6);
    const pickerBounds = await picker.boundingBox();
    if (device === 'desktop') { expect(pickerBounds.width).toBeGreaterThanOrEqual(680); expect(pickerBounds.width).toBeLessThanOrEqual(760); }
    else { expect(pickerBounds.width).toBeGreaterThanOrEqual(370); expect(pickerBounds.width).toBeLessThanOrEqual(390); }
    await expect(picker).toHaveScreenshot(`recordings-workspace-picker-${theme}-${device}.png`, componentScreenshot);
  });
}

test('desktop filter box follows scrolling separately from results and keeps every control reachable', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 1400 });
  const state = await openRecordings(page, { calls: browsingCalls(55) });
  await expect(page.locator('.recordings-call')).toHaveCount(55);
  const filterBox = page.locator('.recordings-filter-section');
  const panel = page.locator('.recordings-search-panel');
  const fields = panel.locator('.recordings-search-fields');
  const results = page.locator('.recordings-results-panel');
  await expect(page.locator('.recordings-browser > .recordings-filter-section.ui-surface')).toHaveCount(1);
  await expect(page.locator('.recordings-browser > .recordings-results-panel.ui-surface')).toHaveCount(1);
  await expect(page.locator('.recordings-browser')).not.toHaveClass(/(?:^|\s)ui-surface(?:\s|$)/);
  const [filterBounds, resultBounds] = await Promise.all([filterBox.boundingBox(), results.boundingBox()]);
  expect(Math.abs(filterBounds.y - resultBounds.y)).toBeLessThanOrEqual(1);
  expect(resultBounds.x - filterBounds.x - filterBounds.width).toBeGreaterThan(0);
  await expect(page.locator('#recordings-filters-toggle')).toBeHidden();
  await expect(filterBox.locator('h2.recordings-filter-title')).toContainText('Filters');
  for (const surface of [filterBox, results])
    await expect(surface).not.toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
  const expectNaturalHeight = async () => {
    for (const wrapper of [filterBox, panel, fields]) {
      expect(await wrapper.evaluate(element => element.scrollHeight - element.clientHeight)).toBeLessThanOrEqual(1);
      await expect(wrapper).toHaveCSS('overflow-y', 'visible');
    }
  };
  await expectNaturalHeight();
  for (const control of await panel.locator('input:visible, select:visible, button:visible, summary:visible').all()) {
    const bounds = await control.boundingBox();
    expect(bounds.y).toBeGreaterThanOrEqual(filterBounds.y);
    expect(bounds.y + bounds.height).toBeLessThanOrEqual(filterBounds.y + filterBounds.height);
    await expect(control).toBeInViewport({ ratio: 1 });
  }
  const measure = () => page.evaluate(() => {
    const filter = document.querySelector('.recordings-filter-section');
    return { y: filter.getBoundingClientRect().y, stickyTop: parseFloat(getComputedStyle(filter).top),
      firstCallY: document.querySelector('.recordings-call').getBoundingClientRect().y, scroll: window.scrollY };
  });
  await page.evaluate(() => window.scrollTo(0, 800));
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(500);
  const firstScroll = await measure();
  expect(Math.abs(firstScroll.y - firstScroll.stickyTop)).toBeLessThanOrEqual(2);
  await page.evaluate(() => window.scrollTo(0, 1300));
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(firstScroll.scroll + 300);
  const secondScroll = await measure();
  expect(Math.abs(secondScroll.y - firstScroll.y)).toBeLessThanOrEqual(2);
  expect(secondScroll.firstCallY).toBeLessThan(firstScroll.firstCallY - 300);
  await expect(filterBox).toBeInViewport();
  const search = panel.getByRole('button', { name: 'Search recordings', exact: true });
  const clear = panel.getByRole('button', { name: 'Clear filters', exact: true });
  await expect(search).toBeInViewport({ ratio: 1 });
  await expect(clear).toBeInViewport({ ratio: 1 });
  await page.setViewportSize({ width: 1280, height: 650 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect.poll(async () => Math.abs((await filterBox.boundingBox()).height - filterBounds.height)).toBeLessThanOrEqual(1);
  await expect.poll(async () => Math.abs((await filterBox.boundingBox()).y - (await results.boundingBox()).y)).toBeLessThanOrEqual(1);
  await expectNaturalHeight();
  await page.evaluate(() => window.scrollTo(0, 1300));
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(1000);
  for (const action of [search, clear]) {
    await expect(action).toBeInViewport({ ratio: 1 });
    expect(await action.evaluate(element => {
      const bounds = element.getBoundingClientRect();
      return element.contains(document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
    })).toBe(true);
  }
  expect(state.requestedFilters).toHaveLength(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
});

test('results group context and selection with the pager before the first call', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await openRecordings(page, { calls: browsingCalls(3), paginate: true, nextCursor: 'fixture:3',
    query: { system_key: systemKey, talkgroup_identity_key: 'v1-g-00001-001-1201' } });
  const results = page.locator('.recordings-results-panel');
  await expect(results.getByRole('heading', { name: 'All recordings', exact: true })).toHaveCount(0);
  await expect(results.locator('.recordings-results-scope')).toHaveText('Past 24 hours');
  await expect(results.locator('.recordings-results-actions').getByRole('combobox')).toHaveCount(2);
  await expect(page.locator('.recordings-filter-summary')).toContainText('Past 24 hours');
  await expect(page.locator('.recordings-search-panel .recordings-selected-filters')).toBeVisible();
  await expect(results.locator('.recordings-page-selection')).toHaveText('Select page');
  const state = results.locator('.recordings-results-state');
  await expect(state.getByRole('checkbox', { name: 'Select all calls on this page', exact: true })).toBeVisible();
  await expect(state.locator('.recordings-result-count')).toContainText('3 calls shown · more available');
  await expect(results.locator('.recordings-results-toolbar .recordings-pager-top')).toBeVisible();
  await expect(results.locator('.recordings-call-context')).toBeVisible();
  const geometry = await results.evaluate(element => {
    const rect = selector => {
      const bounds = element.querySelector(selector).getBoundingClientRect();
      return { x: bounds.x, y: bounds.y, right: bounds.right, bottom: bounds.bottom };
    };
    return { scope: rect('.recordings-results-scope'),
      toolbar: rect('.recordings-results-toolbar'), context: rect('.recordings-call-context'),
      date: rect('.recordings-result-date'),
      title: rect('.recordings-call-title'),
      actions: rect('.recordings-call-actions'), selection: rect('.recordings-call-select') };
  });
  expect(geometry.toolbar.y).toBeGreaterThanOrEqual(geometry.scope.bottom);
  expect(geometry.context.y).toBeGreaterThanOrEqual(geometry.toolbar.bottom);
  expect(geometry.date.y).toBeGreaterThanOrEqual(geometry.context.bottom);
  // The common-facts block is new content; retain the existing compactness
  // budget for the scope, controls and date outside that measured block.
  expect(geometry.date.y - geometry.scope.y - (geometry.context.bottom - geometry.context.y)).toBeLessThan(200);
  expect(Math.abs(geometry.scope.x - geometry.toolbar.x)).toBeLessThanOrEqual(1);
  expect(Math.abs(geometry.toolbar.x - geometry.date.x)).toBeLessThanOrEqual(1);
  expect(Math.abs((geometry.actions.y + geometry.actions.bottom) / 2 -
    (geometry.title.y + geometry.title.bottom) / 2)).toBeLessThanOrEqual(1);
  expect(geometry.selection.x).toBeLessThan(geometry.title.x);
  await expect(results.locator('.recordings-call > .recordings-call-actions')).toHaveCount(3);
  await expect(results.locator('.recordings-call > .recordings-call-select')).toHaveCount(3);
  await testInfo.attach('compact-header-geometry', { body: Buffer.from(JSON.stringify(geometry, null, 2)),
    contentType: 'application/json' });
  await results.screenshot({ ...componentScreenshot, path: testInfo.outputPath('recordings-compact-header-current.png') });
});

test('page summary shows common facts once across dates and omits facts that vary between calls', async ({ page }) => {
  const otherSite = { ...linkedCall, id: 18, start_ms: call.start_ms - 24 * 60 * 60_000,
    channel_id: '00000000-0000-4000-8000-000000000018', channel_name: 'South Ridge Channel',
    channel_entity_ref: { kind: 'channel', key: '00000000-0000-4000-8000-000000000018' },
    site_name: 'South Ridge' };
  await openRecordings(page, { calls: [linkedCall, otherSite] });
  const summary = page.locator('.recordings-call-context');
  await expect(summary).toHaveCount(1);
  await expect(summary.locator('.recordings-call-context-field[data-field="system"]')).toContainText('Metro Public Safety');
  await expect(summary.locator('.recordings-call-context-field[data-field="protocol"]')).toContainText('P25 Phase 2');
  await expect(summary.locator('.recordings-call-context-field[data-field="channel"], .recordings-call-context-field[data-field="site"]')).toHaveCount(0);
  await expect(page.locator('.recordings-call-columns')).toHaveCount(1);
  await expect(page.locator('.recordings-result-date')).toHaveCount(2);
  await expect(page.locator('.recordings-call').first()).toContainText('North Ridge');
  await expect(page.locator('.recordings-call').nth(1)).toContainText('South Ridge');
});

test('page summary keeps all four facts when every call shares them', async ({ page }) => {
  await openRecordings(page, { calls: [linkedCall, { ...linkedCall, id: 18,
    start_ms: call.start_ms - 60_000, source_id: 30915 }] });
  const summary = page.locator('.recordings-call-context');
  await expect(summary).toHaveCount(1);
  await expect(summary.locator('.recordings-call-context-field')).toHaveCount(4);
  for (const field of ['system', 'channel', 'site', 'protocol']) {
    await expect(summary.locator(`[data-field="${field}"]`)).toHaveCount(1);
  }
  await expect(page.locator('.recordings-call-columns')).toHaveCount(1);
});

test('page summary omits missing fields instead of borrowing another call’s data', async ({ page }) => {
  const missing = { ...linkedCall, id: 18, channel_id: null, channel_entity_ref: null,
    channel_name: null, site_name: null, protocol: null };
  await openRecordings(page, { calls: [linkedCall, missing] });
  const summary = page.locator('.recordings-call-context');
  await expect(summary).toHaveCount(1);
  await expect(summary.locator('.recordings-call-context-field')).toHaveCount(1);
  await expect(summary.locator('[data-field="system"]')).toContainText('Metro Public Safety');
  await expect(page.locator('.recordings-call-columns')).toHaveCount(1);
});

test('page summary does not combine matching names from different entity identities', async ({ page }) => {
  const otherSystem = { ...linkedCall, id: 18, system_key: 'p25:00002:002',
    radio_system_entity_ref: { kind: 'radio_system', key: 'p25:00002:002' },
    channel_id: '00000000-0000-4000-8000-000000000018',
    channel_entity_ref: { kind: 'channel', key: '00000000-0000-4000-8000-000000000018' },
    audio_from: { wacn: 2, system_id: 2, rfss: 1, site_id: 1 } };
  await openRecordings(page, { calls: [linkedCall, otherSystem] });
  const summary = page.locator('.recordings-call-context');
  await expect(summary).toHaveCount(1);
  await expect(summary.locator('.recordings-call-context-field')).toHaveCount(1);
  await expect(summary.locator('[data-field="protocol"]')).toContainText('P25 Phase 2');
});

test('page summary is absent when no facts are common while the column heading remains once', async ({ page }) => {
  const otherSystem = { ...linkedCall, id: 18, system_key: 'nxdn-c:global:7', system_name: 'County Operations',
    radio_system_entity_ref: { kind: 'radio_system', key: 'nxdn-c:global:7' },
    channel_id: '00000000-0000-4000-8000-000000000018', channel_name: 'County Channel',
    channel_entity_ref: { kind: 'channel', key: '00000000-0000-4000-8000-000000000018' },
    site_name: 'South Ridge', protocol: 'NXDN' };
  await openRecordings(page, { calls: [linkedCall, otherSystem] });
  await expect(page.locator('.recordings-call')).toHaveCount(2);
  await expect(page.locator('.recordings-call-context')).toHaveCount(0);
  await expect(page.locator('.recordings-call-columns')).toHaveCount(1);
});

test('page summary stays attached to displayed calls through pending filter removal', async ({ page }) => {
  const state = await openRecordings(page, { calls: [linkedCall, { ...linkedCall, id: 18 }], query: { system_key: systemKey } });
  const summary = page.locator('.recordings-call-context');
  await expect(summary).toHaveCount(1);
  await expect(summary).toContainText('Metro Public Safety');
  const displayed = await summary.textContent();
  const systemChip = page.locator('.recordings-selected-filters .recordings-filter-chip[aria-label*="System:"]');
  await expect(systemChip.locator('.recordings-filter-chip-label')).toHaveText('Metro Public Safety');
  await systemChip.click();
  await expect(systemChip).toHaveClass(/recordings-filter-chip-pending/);
  await expect(summary).toHaveText(displayed);
  await searchNow(page, state);
  await expect(summary).toHaveCount(1);
  await expect(summary).toHaveText(displayed);
  await expect(page.locator('.recordings-call a')).toHaveCount(0);
});

for (const [theme, width] of [['light', 1280], ['dark', 320]]) {
  test(`compact results retain long filter names, card actions and selection at ${width}px in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const transcript = 'DispatchInteroperability'.repeat(12);
    await openRecordings(page, { call: linkedCall, theme, nextCursor: 'fixture:1', query: { transcript, system_key: systemKey } });
    const results = page.locator('.recordings-results-panel');
    await ensureSearchOpen(page);
    const chip = page.getByRole('button', { name: `Remove Full text: ${transcript} filter`, exact: true });
    await expect(chip).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    for (const selector of ['.recordings-filter-chip', '.recordings-results-actions select',
      '.recordings-call-actions button', '.recordings-pager']) {
      for (const control of await page.locator(selector).all()) {
        const bounds = await control.boundingBox();
        expect(bounds.x).toBeGreaterThanOrEqual(0);
        expect(bounds.x + bounds.width, `${selector}: ${await control.getAttribute('class')}`).toBeLessThanOrEqual(width);
      }
    }
    await chip.scrollIntoViewIfNeeded();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const beforeFocus = await chip.boundingBox();
    await chip.focus();
    await page.keyboard.press('Shift+Tab');
    await page.keyboard.press('Tab');
    await expect(chip).toBeFocused();
    const hint = page.getByRole('tooltip');
    await expect(hint).toContainText(`Full text: ${transcript}`);
    const afterFocus = await chip.boundingBox(), hintBounds = await hint.boundingBox();
    expect(afterFocus.height).toBe(beforeFocus.height);
    expect(hintBounds.x).toBeGreaterThanOrEqual(0);
    expect(hintBounds.x + hintBounds.width).toBeLessThanOrEqual(width);
    if (width <= 900) await page.locator('#recordings-filters-toggle').click();
    await expect(results).toHaveScreenshot(`recordings-compact-long-filter-${theme}-${width}.png`, componentScreenshot);
    await page.locator('.recordings-call-select').check();
    await expect(results.locator('.recordings-results-toolbar .recordings-selected')).toContainText('1 selected');
    await expect(results.locator('.recordings-result-count > span').first()).toBeHidden();
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    await expect(results).toHaveScreenshot(`recordings-compact-selected-${theme}-${width}.png`, componentScreenshot);
  });
}

for (const [theme, width] of [['light', 1280], ['dark', 320]]) {
  test(`compact rows retain aliases, actions and active browsing state at ${width}px in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await stubRecordingAudio(page);
    const calls = [
      { ...linkedCall, transcript_excerpt: 'Engine four arriving at staging.' },
      { ...linkedCall, id: 18, start_ms: call.start_ms - 60_000, source_ota_alias: 'ENG 5' }
    ];
    const state = await openRecordings(page, { calls, theme,
      transcription: { status: 'COMPLETE', text: 'Engine four arriving at staging.' } });
    const rows = page.locator('.recordings-call');
    await expect(rows).toHaveCount(2);
    await recordingAction(page, rows.first());
    await expect.poll(() => page.evaluate(() => window.recordingPlays.length)).toBe(1);
    await rows.nth(1).locator('.recordings-call-info').click();
    await page.getByRole('dialog', { name: 'Call actions', exact: true })
      .getByRole('button', { name: 'Add to queue', exact: true }).click();
    await rows.nth(1).locator('.recordings-call-select').check();
    const requests = state.requestedFilters.length;
    await expect(page.getByRole('combobox', { name: 'Results layout', exact: true })).toHaveCount(0);
    await expect(rows.first().locator('.recordings-call-title')).toHaveText('Fire Dispatch');
    await expect(rows.first().locator('a')).toHaveCount(0);
    await expect(rows.first().locator('.recordings-call-identity')).toContainText('1201');
    await expect(rows.first().locator('.recordings-call-source')).toContainText('ENG 4');
    await expect(page.locator('.recordings-call-context').first()).toContainText('Metro Public Safety');
    await expect(rows.first()).toHaveClass(/recordings-call-playing/);
    await expect(rows.nth(1)).toHaveClass(/recordings-call-queued/);
    const queuedStatus = rows.nth(1).locator('.recordings-call-status');
    await expect(queuedStatus).toHaveText('Queued');
    await expect(rows.nth(1).locator('.recordings-call-title')).toHaveAccessibleDescription('Queued');
    await expect(queuedStatus).toMatchAriaSnapshot('- text: Queued');
    const rowPaint = await rows.evaluateAll(elements => {
      const color = token => {
        const probe = document.createElement('span'); probe.style.color = `var(${token})`;
        document.body.append(probe); const result = getComputedStyle(probe).color; probe.remove(); return result;
      };
      return { accent: color('--accent'), queued: color('--recording-queue-marker'), line: color('--line'),
        rows: elements.map(element => {
          const paint = getComputedStyle(element), status = getComputedStyle(element.querySelector('.recordings-call-status'));
          return { marker: paint.boxShadow, top: paint.borderTopWidth, right: paint.borderRightWidth,
            left: paint.borderLeftWidth, bottomColor: paint.borderBottomColor, outline: paint.outlineStyle,
            statusClip: status.clipPath, statusDisplay: status.display, statusWidth: status.width };
        }) };
    });
    expect(rowPaint.rows[0].marker).toContain(rowPaint.accent);
    expect(rowPaint.rows[1].marker).toContain(rowPaint.queued);
    expect(rowPaint.queued).not.toBe(rowPaint.accent);
    for (const paint of rowPaint.rows) {
      expect(paint.top).toBe('0px'); expect(paint.right).toBe('0px'); expect(paint.left).toBe('0px');
      expect(paint.bottomColor).toBe(rowPaint.line); expect(paint.outline).toBe('none');
      expect(paint.marker).toContain('inset');
      // Strip color syntax before checking that no second, outer shadow encloses the row.
      expect(paint.marker.replace(/(?:rgba?|oklch|color)\([^)]*\)/g, '')).not.toContain(',');
    }
    expect(rowPaint.rows[1].statusClip).toBe('inset(50%)');
    expect(rowPaint.rows[1].statusWidth).toBe('1px');
    expect(rowPaint.rows[1].statusDisplay).not.toBe('none');
    await expect(rows.nth(1).locator('.recordings-call-select')).toBeChecked();
    await expect(page.locator('.recordings-selected')).toContainText('1 selected');
    if (width > 700) expect((await rows.nth(1).boundingBox()).height).toBeLessThanOrEqual(64);
    await expect(rows.first().locator('.recordings-transcript-preview')).toBeVisible();
    expect(state.requestedFilters).toHaveLength(requests);
    expect(await page.evaluate(() => window.recordingPlays)).toHaveLength(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    await expectContrast(page, { rootSelector: '.recordings-library',
      reportName: `recordings-compact-rows-${theme}-${width}` });
    await expect(page.locator('.recordings-results-panel'))
      .toHaveScreenshot(`recordings-compact-rows-${theme}-${width}.png`, {
        ...componentScreenshot, style: '#topbar, #desktop-playback-slot { visibility: hidden !important; }'
      });

    const detailsButton = rows.first().locator('.recordings-call-info');
    await detailsButton.focus();
    await detailsButton.press('Enter');
    const details = page.getByRole('dialog', { name: 'Call actions', exact: true });
    await expect(details).toBeVisible();
    await details.getByRole('button', { name: 'Show more', exact: true }).click();
    await selectRecordingDetailTab(details, 'Transcript');
    await expect(details.getByRole('tabpanel').filter({ visible: true })).toContainText('Engine four arriving at staging.');
    await selectRecordingDetailTab(details, 'Recording');
    await expect(details.getByRole('link', { name: 'Download recording', exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(detailsButton).toBeFocused();
    await rows.first().locator('.recordings-call-info').click();
    const actions = page.getByRole('dialog', { name: 'Call actions', exact: true });
    await expect(actions.getByRole('link', { name: 'Filter by talkgroup', exact: true }))
      .toHaveAttribute('href', /talkgroup_identity_key=v1-g-00001-001-1201/);
    await page.keyboard.press('Escape');

    if (width <= 700) {
      const touch = await page.context().newCDPSession(page);
      await touch.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 });
      await expect.poll(() => page.evaluate(() => matchMedia('(pointer: coarse)').matches)).toBe(true);
      for (const control of await rows.first().locator('.recordings-call-actions button').all()) {
        const bounds = await control.boundingBox();
        expect(bounds.width).toBeGreaterThanOrEqual(44);
        expect(bounds.height).toBeGreaterThanOrEqual(44);
        expect(bounds.x).toBeGreaterThanOrEqual(0);
        expect(bounds.x + bounds.width).toBeLessThanOrEqual(width);
      }
      await touch.detach();
    }
    await expect(rows.first()).toHaveClass(/recordings-call-playing/);
    await expect(rows.nth(1)).toHaveClass(/recordings-call-queued/);
    await expect(rows.nth(1).locator('.recordings-call-select')).toBeChecked();
    expect(state.requestedFilters).toHaveLength(requests);
    expect(await page.evaluate(() => window.recordingPlays)).toHaveLength(1);
    await recordingAction(page, rows.first(), 'Add to queue');
    await expect(rows.first()).toHaveClass(/recordings-call-playing/);
    await expect(rows.first()).toHaveClass(/recordings-call-queued/);
    await expect(rows.first().locator('.recordings-call-status')).not.toHaveClass(/visually-hidden/);
    expect(await rows.first().evaluate(element => getComputedStyle(element).boxShadow)).toContain(rowPaint.accent);
    await recordingAction(page, rows.first(), 'Play once');
    await expect(rows.locator('.recordings-call-status.visually-hidden')).toHaveCount(0);
    await expect(page.locator('.recordings-call-queued')).toHaveCount(0);
  });
}

test('Full text defaults to a pinned past day, Time remains optional, and drafts only apply after success', async ({ page }) => {
  const state = await openRecordings(page, { call: linkedCall });
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  const fullText = page.getByRole('searchbox', { name: 'Full text', exact: true });
  const range = page.getByRole('combobox', { name: 'Time range', exact: true });
  await range.selectOption('7d');
  await fullText.fill('dispatch AND "radio check"');
  await expect(range).toBeDisabled();
  await expect(page.getByRole('combobox', { name: 'Full text range', exact: true })).toHaveValue('recent');
  expect(state.requestedFilters).toHaveLength(1);
  await expect(page.locator('.recordings-selected-filters')).not.toContainText('dispatch');
  await fullText.press('Enter');
  await expect.poll(() => state.requestedFilters.length).toBe(2);
  const recent = state.requestedFilters.at(-1);
  expect(recent.transcript).toBe('dispatch AND "radio check"'); expect(recent.text_range).toBe('recent');
  expect(Number(recent.to_ms) - Number(recent.from_ms)).toBe(86400000);
  await expect(page.locator('.recordings-selected-filters')).toContainText('Full text: dispatch');
  await page.getByRole('combobox', { name: 'Full text range', exact: true }).selectOption('time');
  await expect(range).toBeEnabled();
  state.errorNext = true;
  await fullText.fill('replacement');
  await page.getByRole('button', { name: 'Search recordings', exact: true }).click();
  await expect(page.locator('.recordings-result-status')).toContainText('Previous results are still shown');
  await expect(page.locator('.recordings-selected-filters')).toContainText('dispatch');
  await expect(page.locator('.recordings-selected-filters')).not.toContainText('replacement');
  await searchNow(page, state);
  expect(state.requestedFilters.at(-1).text_range).toBe('time');
  expect(Number(state.requestedFilters.at(-1).to_ms) - Number(state.requestedFilters.at(-1).from_ms)).toBe(7 * 86400000);
});

test('target picker keeps Cancel local and submits talkgroups plus channels as one OR target set', async ({ page }) => {
  const state = await openRecordings(page, { call: linkedCall });
  let picker = await openPicker(page);
  await expect(picker.locator('.recordings-picker-choice')).toHaveCount(6);
  await picker.locator('.recordings-picker-choice').filter({ hasText: 'Fire Dispatch' }).getByRole('checkbox').check();
  await picker.locator('.recordings-picker-choice').filter({ hasText: 'Airfield AM' }).getByRole('checkbox').check();
  await picker.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(state.requestedFilters).toHaveLength(1);
  await expect(page.locator('#recordings-open-targets')).toContainText('All talkgroups and channels');
  picker = await openPicker(page);
  await picker.locator('.recordings-picker-choice').filter({ hasText: 'Fire Dispatch' }).getByRole('checkbox').check();
  await picker.getByRole('tab', { name: 'Channels', exact: true }).click();
  await picker.locator('.recordings-picker-choice').filter({ hasText: 'Airfield AM' }).getByRole('checkbox').check();
  await picker.getByRole('button', { name: 'Use selection', exact: true }).click();
  expect(state.requestedFilters).toHaveLength(1);
  await expect(page.locator('#recordings-open-targets')).toContainText('2 selected');
  await expect(page.locator('#recordings-open-targets')).toBeFocused();
  await searchNow(page, state);
  expect(state.requestedFilters.at(-1).targets_any).toBe('true');
  expect(JSON.parse(state.requestedFilters.at(-1).talkgroups)).toEqual([{ id: 1201, system_key: systemKey }]);
  expect(JSON.parse(state.requestedFilters.at(-1).channel_ids)).toEqual(['00000000-0000-4000-8000-000000000018']);
  const requests = state.requestedFilters.length;
  const chips = page.locator('.recordings-selected-filters');
  for (const name of ['Fire Dispatch', 'Airfield AM']) {
    const chip = chips.locator('.recordings-filter-chip').filter({ hasText: name });
    await chip.click();
    await expect(chip).toHaveClass(/recordings-filter-chip-pending/);
    await expect(page.locator('#recordings-open-targets')).toContainText('1 selected');
    await chip.click();
    await expect(chip).not.toHaveClass(/recordings-filter-chip-pending/);
    await expect(page.locator('#recordings-open-targets')).toContainText('2 selected');
  }
  expect(state.requestedFilters).toHaveLength(requests);
  await page.locator('.recordings-call-info').click();
  await page.getByRole('dialog', { name: 'Call actions', exact: true })
    .getByRole('link', { name: 'Filter by talkgroup', exact: true }).click();
  await expect.poll(() => state.requestedFilters.at(-1).talkgroup_identity_key).toBe('v1-g-00001-001-1201');
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('channel_ids');
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('talkgroups');
});

test('large groups show ten, select only shown rows, search all matches, and retain selection across sort', async ({ page }) => {
  const identities = Array.from({ length: 135 }, (_, index) => ({ kind: 'talkgroup', id: 1000 + index,
    system_key: systemKey, system_name: 'Metro Public Safety', group: 'Public safety', alias_list_name: 'Metro aliases',
    label: index === 134 ? 'Zebra beyond first page' : `Dispatch ${String(index).padStart(3, '0')}`,
    call_count: 1000 - index, last_recorded_ms: index }));
  const state = await openRecordings(page, { identities, entities: [] });
  const picker = await openPicker(page);
  await expect(picker.locator('.recordings-picker-choice')).toHaveCount(10);
  await expect(picker.locator('.recordings-group-count')).toHaveText('10 of 135');
  await picker.getByRole('button', { name: 'Select shown talkgroups in Public safety on Metro Public Safety', exact: true }).click();
  await expect(picker.locator('.recordings-picker-selected').getByRole('button')).toHaveCount(10);
  await picker.getByRole('button', { name: 'Load 10 more in Public safety on Metro Public Safety', exact: true }).click();
  await expect(picker.locator('.recordings-picker-choice')).toHaveCount(20);
  await expect(picker.locator('.recordings-picker-choice input:checked')).toHaveCount(10);
  const query = picker.getByRole('searchbox', { name: 'Find talkgroups and channels', exact: true });
  await query.fill('Zebra');
  await expect(picker.locator('.recordings-picker-choice')).toHaveCount(1);
  await expect(picker.locator('.recordings-group-count')).toHaveText('1 of 1');
  await expect(picker.locator('.recordings-picker-selected').getByRole('button')).toHaveCount(10);
  const check = picker.locator('.recordings-picker-choice input'); await check.focus(); await check.press('Space'); await expect(check).toBeFocused();
  await picker.getByRole('combobox', { name: 'Sort picker list', exact: true }).selectOption('recent');
  await expect(picker.locator('.recordings-picker-choice input')).toBeChecked();
  await picker.getByRole('button', { name: 'Use selection', exact: true }).click(); await searchNow(page, state);
  expect(JSON.parse(state.requestedFilters.at(-1).talkgroups)).toHaveLength(11);
  expect(state.identityRequests.some(request => request.group_key && request.offset === '10')).toBe(true);
});

test('identity choices preserve repeated numbers, NXDN zero and typed auxiliary namespaces', async ({ page }) => {
  const first = { id: 0, system_key: 'nxdn-c:global:7', protocol: 'NXDN' };
  const typed = { id: null, system_key: null, protocol: 'TAIT1200', value: 'UNIT-A' };
  const state = await openRecordings(page, { identities: [
    { kind: 'talkgroup', ...first, selection: first, system_name: 'NXDN Operations', label: 'All units', group: 'Operations', call_count: 9 },
    ...['DMR', 'NXDN'].map(protocol => ({ kind: 'radio', id: 900, system_key: null, protocol, label: 'Unnamed radio', call_count: 4 })),
    ...['FLEETSYNC', 'MDC1200'].map(protocol => ({ kind: 'radio', id: null, system_key: null, label: 'Unit 123', protocol,
      selection: { id: null, system_key: null, protocol, value: '00123' }, call_count: 3 })),
    { kind: 'radio', ...typed, selection: typed, label: 'Tait service', call_count: 2 }
  ], entities: [] });
  let picker = await openPicker(page); await picker.locator('.recordings-picker-choice input').check();
  await picker.getByRole('button', { name: 'Use selection', exact: true }).click();
  picker = await openPicker(page, 'radios', 'Source radios');
  await expect(picker.locator('.recordings-picker-choice')).toHaveCount(5);
  await picker.getByRole('button', { name: 'Select shown', exact: true }).click();
  await picker.getByRole('button', { name: 'Use selection', exact: true }).click(); await searchNow(page, state);
  expect(JSON.parse(state.requestedFilters.at(-1).talkgroups)).toEqual([first]);
  const radios = JSON.parse(state.requestedFilters.at(-1).radios); expect(radios).toHaveLength(5); expect(radios).toContainEqual(typed);
  expect(radios.filter(item => item.id === 900).map(item => item.protocol)).toEqual(['DMR', 'NXDN']);
  expect(radios.filter(item => item.value === '00123').map(item => item.protocol)).toEqual(['FLEETSYNC', 'MDC1200']);
});

test('tone filters validate ordered pairs, browse captured DCS, and discard canceled fields', async ({ page }) => {
  const state = await openRecordings(page, { entities: [{ kind: 'dcs', id: 'I023', value: 'I023', label: 'DCS-023 Inverted', call_count: 4, selection: { dcs_code: 'I023' } }] });
  let dialog = await openPicker(page, 'tones', 'Tones & signaling');
  await expect(dialog.getByRole('option', { name: 'Without paging tones' })).toHaveCount(0);
  await dialog.getByRole('combobox', { name: 'Tones', exact: true }).selectOption('detected');
  await dialog.getByLabel('Then tone (Hz)', { exact: true }).fill('1180');
  await dialog.getByRole('button', { name: 'Use filters', exact: true }).click();
  await expect(dialog).toContainText('Enter the first tone');
  await dialog.getByLabel('First tone (Hz)', { exact: true }).fill('832.5');
  await dialog.getByRole('button', { name: 'DCS-023 Inverted · 4 calls', exact: true }).click();
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  dialog = await openPicker(page, 'tones', 'Tones & signaling');
  await expect(dialog.getByLabel('First tone (Hz)', { exact: true })).toHaveValue('');
  await dialog.getByRole('combobox', { name: 'Tones', exact: true }).selectOption('detected');
  await dialog.getByLabel('First tone (Hz)', { exact: true }).fill('832.5'); await dialog.getByLabel('Then tone (Hz)', { exact: true }).fill('1180');
  await dialog.getByRole('button', { name: 'DCS-023 Inverted · 4 calls', exact: true }).click();
  await dialog.getByRole('button', { name: 'Use filters', exact: true }).click(); await searchNow(page, state);
  expect(state.requestedFilters.at(-1)).toMatchObject({ paging_tones: 'detected', tone_frequency_hz: '832.5', tone_then_hz: '1180', dcs_code: 'I023' });
  dialog = await openPicker(page, 'tones', 'Tones & signaling'); await dialog.getByRole('combobox', { name: 'Tones', exact: true }).selectOption('');
  await dialog.getByRole('button', { name: 'Use filters', exact: true }).click(); await searchNow(page, state);
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('tone_frequency_hz'); expect(state.requestedFilters.at(-1).dcs_code).toBe('I023');
});

test('High Interest combines with explicit properties, blocks contradictory duration and preserves Cancel', async ({ page }) => {
  const state = await openRecordings(page);
  await page.getByRole('button', { name: /High Interest/ }).click();
  let more = await openPicker(page, 'more', 'More filters');
  await more.getByLabel('Maximum duration (seconds)', { exact: true }).fill('5');
  await more.getByRole('button', { name: 'Use filters', exact: true }).click(); await expect(more).toContainText('at least 10 seconds');
  await more.getByLabel('Maximum duration (seconds)', { exact: true }).fill('30');
  await more.getByRole('combobox', { name: 'Protocol', exact: true }).selectOption('DMR');
  await more.getByRole('button', { name: 'Use filters', exact: true }).click();
  expect(state.requestedFilters).toHaveLength(1);
  await searchNow(page, state); expect(state.requestedFilters.at(-1)).toMatchObject({ high_interest: '1', min_duration_ms: '10000', max_duration_ms: '30000', protocol: 'DMR' });
  more = await openPicker(page, 'more', 'More filters'); await more.getByRole('combobox', { name: 'Protocol', exact: true }).selectOption('AM'); await more.getByRole('button', { name: 'Cancel', exact: true }).click();
  await searchNow(page, state); expect(state.requestedFilters.at(-1).protocol).toBe('DMR');
});

test('applied chips show pending removal, support undo and Discard, and survive failed searches', async ({ page }) => {
  const state = await openRecordings(page);
  const fullText = page.getByRole('searchbox', { name: 'Full text', exact: true });
  const interest = page.locator('.recordings-interest');
  const chips = page.locator('.recordings-selected-filters');
  await fullText.fill('arriving');
  await interest.click();
  await searchNow(page, state);
  const requests = state.requestedFilters.length;
  const removeText = () => chips.getByRole('button', { name: 'Remove Full text: arriving filter', exact: true });
  const undoText = () => chips.getByRole('button', { name: 'Undo removal of Full text: arriving filter', exact: true });
  await removeText().click();
  await expect(fullText).toHaveValue('');
  await expect(undoText()).toHaveClass(/recordings-filter-chip-pending/);
  await expect(undoText()).toContainText('Remove on search · Undo');
  await expect(chips).toHaveScreenshot('recordings-pending-removal.png', componentScreenshot);
  expect(state.requestedFilters).toHaveLength(requests);

  await interest.click();
  await undoText().click();
  await expect(fullText).toHaveValue('arriving');
  await expect(removeText()).not.toHaveClass(/recordings-filter-chip-pending/);
  await expect(interest).toHaveAttribute('aria-pressed', 'false');
  await expect(chips.getByRole('button', { name: /Undo removal of High Interest/ })).toBeVisible();
  await page.getByRole('button', { name: 'Discard changes', exact: true }).click();
  await expect(interest).toHaveAttribute('aria-pressed', 'true');
  await expect(chips.locator('.recordings-filter-chip-pending')).toHaveCount(0);

  await removeText().click();
  state.errorNext = true;
  await page.getByRole('button', { name: 'Search recordings', exact: true }).click();
  await expect(page.locator('.recordings-result-status')).toContainText('Previous results');
  await expect(undoText()).toContainText('Remove on search · Undo');
  await expect(fullText).toHaveValue('');
  await undoText().click();
  await expect(fullText).toHaveValue('arriving');
  await removeText().click();
  await searchNow(page, state);
  await expect(chips).not.toContainText('Full text: arriving');
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('transcript');
  expect(state.requestedFilters.at(-1).high_interest).toBe('1');

  const beforeClear = state.requestedFilters.length;
  await page.getByRole('button', { name: 'Clear filters', exact: true }).click();
  await expect.poll(() => state.requestedFilters.length).toBe(beforeClear + 1);
  await expect(chips).not.toContainText('High Interest');
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('high_interest');
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('transcript');
});

test('call actions reuse VCE playback choices and route Live to the saved channel', async ({ page }) => {
  await page.addInitScript(() => { window.recordingPlays = []; HTMLMediaElement.prototype.play = function() { window.recordingPlays.push(this.src); return Promise.resolve(); }; HTMLMediaElement.prototype.pause = function() {}; });
  const state = await openRecordings(page, { call: linkedCall }); await expect(page.locator('.recordings-call')).toHaveCount(1);
  await page.locator('.recordings-call-info').click();
  let menu = page.getByRole('dialog', { name: 'Call actions', exact: true });
  await expect(menu.getByRole('link', { name: 'Go to Live', exact: true })).toHaveAttribute('href', `/?view=live&channel=${channelId}`);
  await expect(menu.getByRole('button', { name: /Play once|Continue from here|Add to queue/ })).toHaveCount(3);
  await menu.getByRole('button', { name: 'Play once', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.recordingPlays.length)).toBe(1);
  await page.locator('.recordings-call-info').click(); menu = page.getByRole('dialog', { name: 'Call actions', exact: true });
  await menu.getByRole('button', { name: 'Search around this call · ±30 min', exact: true }).click();
  await expect.poll(() => state.requestedFilters.length).toBe(2);
  const request = state.requestedFilters.at(-1); expect(Number(request.to_ms) - Number(request.from_ms)).toBe(3600000);
  expect(request).toHaveProperty('talkgroup_identity_key', 'v1-g-00001-001-1201'); expect(request).not.toHaveProperty('transcript');
});

test('call actions place the existing Direct Listen control beside Go to Live with exact identity and access', async ({ page }) => {
  await openRecordings(page, { call: linkedCall, directListen: true });
  await page.locator('.recordings-call-info').click();
  const actions = page.getByRole('dialog', { name: 'Call actions', exact: true });
  const direct = actions.locator('[data-direct-listen-target-key]');
  await expect(direct).toHaveAccessibleName('Add Fire Dispatch to Direct Listen and play');
  await expect(direct).toHaveAttribute('aria-pressed', 'false');
  await expect(direct.locator('use')).toHaveAttribute('href', '#icon-direct-listen');
  expect(JSON.parse(await direct.getAttribute('data-direct-listen-target-key')))
    .toEqual(linkedCall.target_entity_ref);
  expect(await direct.evaluate(element => element.previousElementSibling?.textContent.trim())).toBe('Go to Live');
  const directBounds = await direct.boundingBox();
  const liveBounds = await actions.getByRole('link', { name: 'Go to Live', exact: true }).boundingBox();
  expect(directBounds.height).toBe(liveBounds.height);
  expect(directBounds.width).toBe(directBounds.height);
  expect(directBounds.height).toBeGreaterThanOrEqual(40);
  await page.setViewportSize({ width: 390, height: 900 });
  const mobileDirect = await direct.boundingBox();
  const mobileLive = await actions.getByRole('link', { name: 'Go to Live', exact: true }).boundingBox();
  expect(mobileDirect.y).toBe(mobileLive.y);
  expect(mobileDirect.x).toBeGreaterThan(mobileLive.x);
  expect(mobileDirect.x + mobileDirect.width).toBeLessThanOrEqual(390);
  await direct.click();
  await expect(direct).toHaveAttribute('aria-pressed', 'true');
  await expect(direct.locator('use')).toHaveAttribute('href', '#icon-check');
  await direct.click();
  await expect(direct).toHaveAttribute('aria-pressed', 'false');
  await expect(direct.locator('use')).toHaveAttribute('href', '#icon-direct-listen');
  await page.keyboard.press('Escape');

  for (const options of [
    { call: linkedCall, directListen: false },
    { call: { ...linkedCall, call_type: 'DIRECT', talkgroup_id: null, destination_radio_id: 30915,
      target_entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: 'v1-r-00001-001-30915' } }, directListen: true }
  ]) {
    await openRecordings(page, options);
    await page.locator('.recordings-call-info').click();
    await expect(page.getByRole('dialog', { name: 'Call actions', exact: true })
      .locator('[data-direct-listen-target-key]')).toHaveCount(0);
  }
});

test('call actions omit the retired related activity action and keep unified information available', async ({ page }) => {
  const state = await openRecordings(page, { call: linkedCall });
  const relations = [];
  page.on('request', request => { if (request.url().includes('/call-relations/')) relations.push(request.url()); });
  await page.locator('.recordings-call-info').click();
  const actions = page.getByRole('dialog', { name: 'Call actions', exact: true });
  await expect(actions.getByRole('button', { name: 'Related activity and alerts', exact: true })).toHaveCount(0);
  await actions.getByRole('button', { name: 'Show more', exact: true }).click();
  await expect(actions.getByRole('tab', { name: 'Transcript', exact: true })).toBeVisible();
  expect(relations).toEqual([]);
  expect(state.requestedFilters).toHaveLength(1);
});

for (const [name, overrides, title, route] of [
  ['P25 talkgroup', {}, 'Open talkgroup page', {
    view: 'group-identity', radio_system_key: systemKey, identity_key: 'v1-g-00001-001-1201'
  }],
  ['DMR talkgroup', { protocol: 'DMR', system_key: 'dmr:tier3:small:7',
    target_entity_ref: { kind: 'talkgroup', radio_system_key: 'dmr:tier3:small:7', identity_key: 'v1-g-x-x-1201' } },
  'Open talkgroup page', { view: 'group-identity', radio_system_key: 'dmr:tier3:small:7', identity_key: 'v1-g-x-x-1201' }],
  ['NXDN talkgroup', { protocol: 'NXDN', system_key: 'nxdn-c:global:7',
    target_entity_ref: { kind: 'talkgroup', radio_system_key: 'nxdn-c:global:7', identity_key: 'v1-g-x-x-1201' } },
  'Open talkgroup page', { view: 'group-identity', radio_system_key: 'nxdn-c:global:7', identity_key: 'v1-g-x-x-1201' }],
  ['P25 patch group', { call_type: 'PATCH',
    target_entity_ref: { kind: 'patch_group', radio_system_key: systemKey, identity_key: 'v1-p-00001-001-1201' } },
  'Open patch group page', { view: 'group-identity', radio_system_key: systemKey, identity_key: 'v1-p-00001-001-1201' }],
  ['direct radio', { call_type: 'UNIT_TO_UNIT', talkgroup_id: null, talkgroup_alias: null,
    destination_radio_id: 30915,
    target_entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: 'v1-r-00001-001-30915' } },
  'Open destination radio page', { view: 'radio', radio_system_key: systemKey, identity_key: 'v1-r-00001-001-30915' }],
  ['AM channel', { protocol: 'AM', talkgroup_id: null, talkgroup_alias: null, target_entity_ref: null },
  'Open channel page', { view: 'channel', configuration_id: channelId }],
  ['NBFM channel', { protocol: 'NBFM', talkgroup_id: null, talkgroup_alias: null, target_entity_ref: null },
  'Open channel page', { view: 'channel', configuration_id: channelId }],
  ['unresolved digital target', { target_entity_ref: null },
  'Open channel page', { view: 'channel', configuration_id: channelId }],
  ['invalid digital target', { target_entity_ref: {
    kind: 'talkgroup', radio_system_key: systemKey, identity_key: 'v1-g-x-001-1201'
  } }, 'Open channel page', { view: 'channel', configuration_id: channelId }]
]) {
  test(`call actions open the existing ${name} page separately from a recordings search`, async ({ page }) => {
    const state = await openRecordings(page, { call: { ...linkedCall, ...overrides } });
    const expectedHref = `/?${new URLSearchParams(route)}`;
    if (route.view === 'group-identity') {
      await expect(page.locator('.recordings-call a')).toHaveCount(0);
    }
    await page.locator('.recordings-call-info').click();
    const actions = page.getByRole('dialog', { name: 'Call actions', exact: true });
    const open = actions.getByRole('link', { name: title, exact: true });
    await expect(open).toHaveAttribute('href', expectedHref);
    await expect(open.locator('svg')).toHaveCount(1);
    const searches = actions.getByRole('link', { name: /^Filter by / });
    expect(await searches.count()).toBeGreaterThan(0);
    for (const search of await searches.all()) await expect(search).toHaveAttribute('href', /view=recordings/);
    if (name === 'invalid digital target')
      await expect(actions.getByRole('link', { name: 'Filter by talkgroup', exact: true })).toHaveCount(0);
    await open.click();
    await expect(page).toHaveURL(new RegExp(`${expectedHref.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`));
    expect(state.requestedFilters).toHaveLength(1);
  });
}

test('call actions suppress entity navigation without radio access or a valid entity destination', async ({ page }) => {
  for (const options of [
    { radio: false, call: linkedCall },
    { call: { ...linkedCall, target_entity_ref: null, channel_entity_ref: null } },
    { call: { ...linkedCall, target_entity_ref: { kind: 'talkgroup', radio_system_key: 'invalid',
      identity_key: 'v1-g-00001-001-1201' }, channel_entity_ref: { kind: 'channel', key: 'invalid' } } }
  ]) {
    await openRecordings(page, options);
    await page.locator('.recordings-call-info').click();
    const actions = page.getByRole('dialog', { name: 'Call actions', exact: true });
    await expect(actions.getByRole('link', { name: /^Open (talkgroup|patch group|radio|channel) page$/ })).toHaveCount(0);
    await expect(actions.getByRole('link', { name: 'Filter by source radio', exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
  }
});

test('conventional call actions explain channel and radio searches without inventing a talkgroup', async ({ page }) => {
  const conventional = { ...linkedCall, protocol: 'NXDN', talkgroup_id: null,
    talkgroup_alias: null, target_entity_ref: null, source_entity_ref: null,
    system_key: 'nxdn-c:global:7', radio_system_entity_ref: null,
    system_name: 'NXDN Operations', channel_name: 'Operations Channel' };
  const state = await openRecordings(page, { call: conventional });
  await page.locator('.recordings-call-info').click();
  const actions = page.getByRole('dialog', { name: 'Call actions', exact: true });
  await expectCallActionDescriptions(actions, 11);
  await expect(actions.getByRole('button', { name: 'Related activity and alerts', exact: true })).toHaveCount(0);
  await expect(actions).not.toContainText(/talkgroup/i);
  await expect(actions.getByRole('link', { name: 'Filter by channel', exact: true }))
    .toHaveAttribute('href', new RegExp(`channel_id=${channelId}`));
  await expect(actions.getByRole('link', { name: 'Filter by source radio', exact: true }))
    .toHaveAttribute('href', /radios=/);
  await actions.getByRole('button', { name: 'Filter channel + radio', exact: true }).click();
  await expect(actions).toBeHidden();
  await expect.poll(() => state.requestedFilters.length).toBe(2);
  const request = state.requestedFilters.at(-1);
  expect(request.channel_id).toBe(channelId);
  expect(JSON.parse(request.radios)).toEqual([{ id: call.source_id, system_key: 'nxdn-c:global:7', protocol: 'NXDN' }]);
  expect(request).not.toHaveProperty('talkgroups');
  expect(request).not.toHaveProperty('talkgroup_id');
});

for (const theme of ['light', 'dark']) test(`unified call actions expands in place and keeps playback and downloads available in ${theme}`, async ({ page }) => {
  await openRecordings(page, { call: linkedCall, theme });
  await page.locator('.recordings-call-info').click();
  const actions = page.getByRole('dialog', { name: 'Call actions', exact: true });
  await expectCallActionDescriptions(actions);
  await actions.getByRole('button', { name: 'Show more', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(1);
  await expect(actions.getByRole('tab')).toHaveText(['Overview', 'Transcript', 'Identities', 'Technical', 'Recording']);
  for (const width of [1280, 800, 700, 390, 320]) {
    await page.setViewportSize({ width, height: 900 });
    if (width === 320 || theme === 'light' && [800, 700].includes(width))
      await expect(actions).toHaveScreenshot(`recordings-details-flow-${theme}-${width}.png`, componentScreenshot);
    for (const name of ['Play once', 'Continue from here', 'Add to queue'])
      await expect(actions.getByRole('button', { name, exact: true })).toBeVisible();
    await selectRecordingDetailTab(actions, 'Recording');
    await expect(actions.getByRole('link', { name: 'Download recording', exact: true })).toBeVisible();
    expect(await actions.evaluate(element => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(0);
    await actions.getByRole('button', { name: 'Show less', exact: true }).click();
    await expect(actions.getByRole('tab')).toHaveCount(0);
    await expect(actions.locator('.recordings-call-entity')).not.toHaveCount(0);
    await actions.getByRole('button', { name: 'Show more', exact: true }).click();
    await expect(actions.getByRole('tab', { name: 'Overview', exact: true })).toHaveAttribute('aria-selected', 'true');
  }
});

test('successful searches reset playback and queue while failed searches and cursor paging preserve them', async ({ page }) => {
  await page.addInitScript(() => { HTMLMediaElement.prototype.play = function() { return Promise.resolve(); }; HTMLMediaElement.prototype.pause = function() {}; });
  const state = await openRecordings(page, { calls: mixedRecordingCalls(), paginate: true });
  await page.locator('.recordings-call-info').first().click();
  await page.getByRole('dialog', { name: 'Call actions', exact: true }).getByRole('button', { name: 'Play once', exact: true }).click();
  await page.locator('.recordings-call-info').nth(1).click();
  await page.getByRole('dialog', { name: 'Call actions', exact: true }).getByRole('button', { name: 'Add to queue', exact: true }).click();
  const queue = page.locator('#audio-dock .audio-dock-queue-count');
  await expect(queue).toContainText('1 queued');
  await page.locator('.recordings-pager').last().getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.recordings-call')).toHaveCount(5); await expect(queue).toContainText('1 queued');
  await page.getByRole('searchbox', { name: 'Full text', exact: true }).fill('new query'); state.errorNext = true;
  await page.getByRole('button', { name: 'Search recordings', exact: true }).click();
  await expect(page.locator('.recordings-result-status')).toContainText('Previous results'); await expect(queue).toContainText('1 queued');
  await searchNow(page, state);
  await expect(page.locator('.recordings-call-playing,.recordings-call-queued')).toHaveCount(0);
  await expect(queue).not.toContainText('1 queued');
});

test('pending searches lock sort and pager and group Load more retains its exact period snapshot', async ({ page }) => {
  const identities = Array.from({ length: 25 }, (_, index) => ({ kind: 'talkgroup', id: index + 100,
    system_key: systemKey, label: `Group ${index}`, group: 'Operations', call_count: 100 - index }));
  const state = await openRecordings(page, { identities, entities: [], calls: mixedRecordingCalls(), paginate: true });
  const picker = await openPicker(page);
  await expect(picker.locator('.recordings-picker-choice')).toHaveCount(10);
  await picker.getByRole('button', { name: /Load 10 more in Operations/ }).click();
  await expect(picker.locator('.recordings-picker-choice')).toHaveCount(20);
  const requests = state.identityRequests; expect(requests).toHaveLength(2);
  expect(requests[1].from_ms).toBe(requests[0].from_ms); expect(requests[1].to_ms).toBe(requests[0].to_ms);
  await picker.getByRole('button', { name: 'Cancel', exact: true }).click();
  state.delayCalls = 450;
  await page.getByRole('searchbox', { name: 'Full text', exact: true }).fill('dispatch');
  await page.getByRole('button', { name: 'Search recordings', exact: true }).click();
  await expect(page.getByRole('combobox', { name: 'Sort calls', exact: true })).toBeDisabled();
  await expect(page.locator('.recordings-pager').last().getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Search recordings', exact: true })).toBeDisabled();
  await expect(page.locator('.recordings-selected-filters')).toContainText('Full text: dispatch');
  await expect(page.getByRole('combobox', { name: 'Sort calls', exact: true })).toBeEnabled();
});

test('false URL flags keep High Interest off and show the correct missing-transcript state', async ({ page }) => {
  const state = await openRecordings(page, { query: { high_interest: 'false', has_transcript: 'false' } });
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  await expect(page.getByRole('button', { name: /High Interest/ })).toHaveAttribute('aria-pressed', 'false');
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('min_duration_ms');
  expect(state.requestedFilters.at(-1).has_transcript).toBe('false');
  await expect(page.locator('.recordings-selected-filters')).toContainText('No saved transcript');
  const more = await openPicker(page, 'more', 'More filters');
  await expect(more.getByRole('combobox', { name: 'Transcript availability', exact: true })).toHaveValue('false');
});


test('High Interest is an explicit toggle and result controls have recognizable glyphs', async ({ page }) => {
  const state = await openRecordings(page);
  const interest = page.locator('.recordings-interest');
  await expect(interest).toHaveAttribute('aria-pressed', 'false');
  await expect(interest).toContainText('Off');
  await expect(interest.locator('svg')).toHaveCount(1);
  await interest.click();
  await expect(interest).toHaveAttribute('aria-pressed', 'true');
  await expect(interest).toContainText('On');
  expect(state.requestedFilters).toHaveLength(1);
  for (const selector of ['#recordings-open-systems', '#recordings-open-targets', '#recordings-open-radios',
    '#recordings-open-tones', '#recordings-open-more']) {
    await expect(page.locator(selector).locator('svg')).toHaveCount(1);
  }
  await expect(page.getByRole('button', { name: 'Search recordings', exact: true }).locator('svg')).toHaveCount(1);
  await expect(page.locator('.recordings-search-panel .ui-field-label svg')).toHaveCount(0);
  const actions = page.locator('.recordings-call-info');
  await expect(actions).toHaveText('');
  await expect(actions.locator('svg')).toHaveCount(1);
  await expect(actions).toHaveAccessibleName(/More actions for/);
  await actions.hover();
  await expect(page.getByRole('tooltip')).toHaveText('Call actions');
  await actions.focus();
  await actions.press('Enter');
  await expect(page.getByRole('dialog', { name: 'Call actions', exact: true })).toBeVisible();
  await expect(page.getByRole('dialog', { name: 'Call details', exact: true })).toHaveCount(0);
  await page.keyboard.press('Escape');
  await expect(actions).toBeFocused();
});

async function openRecordingAccentExample(page) {
  await stubRecordingAudio(page);
  await page.setViewportSize({ width: 1280, height: 900 });
  await openRecordings(page, { calls: [linkedCall, { ...linkedCall, id: 18, start_ms: call.start_ms - 60_000 }] });
  await page.getByRole('button', { name: /High Interest/ }).click();
  await recordingAction(page, page.locator('.recordings-call').first(), 'Play once');
  await recordingAction(page, page.locator('.recordings-call').nth(1), 'Add to queue');
  await expect(page.locator('.recordings-call').first()).toHaveClass(/recordings-call-playing/);
  await expect(page.locator('.recordings-call').nth(1)).toHaveClass(/recordings-call-queued/);
  await page.mouse.move(0, 0);
}

test('recordings accents follow every color scheme in day and night themes', async ({ page }) => {
  await openRecordingAccentExample(page);
  const palettes = await page.evaluate(async () => {
    const { APPEARANCE_SCHEMES, applyThemeHue } = await import('/assets/core/theme.js');
    const root = document.documentElement;
    const color = (token) => {
      const probe = document.createElement('span');
      probe.style.color = `var(${token})`; root.append(probe);
      const value = getComputedStyle(probe).color; probe.remove(); return value;
    };
    const colors = [];
    for (const theme of ['light', 'dark']) for (const scheme of APPEARANCE_SCHEMES) {
      if (theme === 'dark') root.dataset.theme = 'dark'; else delete root.dataset.theme;
      applyThemeHue(scheme.hue, root);
      const interestControl = document.querySelector('.recordings-interest');
      await Promise.all(interestControl.getAnimations()
        .filter(animation => Number.isFinite(animation.effect.getComputedTiming().endTime))
        .map(animation => animation.finished));
      const primary = getComputedStyle(document.querySelector('.recordings-search-actions .ui-button-primary'));
      const interest = getComputedStyle(interestControl);
      const card = getComputedStyle(document.querySelector('.recordings-call-playing'));
      const queued = getComputedStyle(document.querySelector('.recordings-call-queued'));
      const duration = getComputedStyle(document.querySelector('.recordings-call-duration'));
      colors.push({ theme, scheme: scheme.name, accent: color('--accent'), primary: color('--primary-control'),
        primaryInk: color('--primary-control-ink'), buttonBackground: primary.backgroundColor, buttonInk: primary.color,
        line: color('--line'), queueMarker: color('--recording-queue-marker'),
        interestBorder: interest.borderTopColor, cardBorder: card.borderBottomColor, cardShadow: card.boxShadow,
        queueShadow: queued.boxShadow,
        durationWeight: Number(duration.fontWeight) });
    }
    return colors;
  });
  expect(palettes).toHaveLength(16);
  for (const palette of palettes) {
    expect(palette.buttonBackground, `${palette.theme} ${palette.scheme} Search`).toBe(palette.primary);
    expect(palette.buttonInk, `${palette.theme} ${palette.scheme} Search label`).toBe(palette.primaryInk);
    expect(palette.interestBorder, `${palette.theme} ${palette.scheme} High Interest`).toBe(palette.accent);
    expect(palette.cardBorder, `${palette.theme} ${palette.scheme} row separator`).toBe(palette.line);
    expect(palette.cardShadow, `${palette.theme} ${palette.scheme} playing marker`).toContain(palette.accent);
    expect(palette.queueShadow, `${palette.theme} ${palette.scheme} queued marker`).toContain(palette.queueMarker);
    expect(palette.queueMarker).not.toBe(palette.accent);
    for (const shadow of [palette.cardShadow, palette.queueShadow]) {
      expect(shadow).toContain('inset');
      expect(shadow.replace(/(?:rgba?|oklch|color)\([^)]*\)/g, '')).not.toContain(',');
    }
    expect(palette.durationWeight).toBeGreaterThanOrEqual(400);
  }
  for (const theme of ['light', 'dark']) {
    expect(new Set(palettes.filter(palette => palette.theme === theme).map(palette => palette.accent)).size).toBe(8);
  }
  const markerSweep = await page.evaluate(async () => {
    const { applyThemeHue } = await import('/assets/core/theme.js');
    const root = document.documentElement, queued = document.querySelector('.recordings-call-queued');
    const canvas = document.createElement('canvas'); canvas.width = canvas.height = 1;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    const probe = document.createElement('span'); document.body.append(probe);
    const rgba = paint => {
      context.clearRect(0, 0, 1, 1); context.fillStyle = paint; context.fillRect(0, 0, 1, 1);
      return [...context.getImageData(0, 0, 1, 1).data];
    };
    const token = name => { probe.style.color = `var(${name})`; return getComputedStyle(probe).color; };
    const luminance = rgb => rgb.slice(0, 3).map(value => {
      const channel = value / 255; return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
    }).reduce((total, channel, index) => total + channel * [0.2126, 0.7152, 0.0722][index], 0);
    const background = element => {
      const layers = [];
      for (let parent = element; parent; parent = parent.parentElement) layers.push(rgba(getComputedStyle(parent).backgroundColor));
      return layers.reverse().reduce((paint, layer) => layer.slice(0, 3).map((value, index) =>
        value * layer[3] / 255 + paint[index] * (1 - layer[3] / 255)), [255, 255, 255]);
    };
    let minimumContrast = Infinity, indistinguishable = 0, checked = 0, unapplied = 0;
    for (const theme of ['light', 'dark']) {
      if (theme === 'dark') root.dataset.theme = 'dark'; else delete root.dataset.theme;
      for (let hue = 0; hue < 360; hue++) {
        applyThemeHue(hue, root);
        const marker = token('--recording-queue-marker'), ink = rgba(marker), playing = rgba(token('--accent'));
        if (ink.slice(0, 3).every((value, index) => value === playing[index])) indistinguishable++;
        if (!getComputedStyle(queued).boxShadow.includes(marker)) unapplied++;
        const light = luminance(ink), dark = luminance(background(queued));
        minimumContrast = Math.min(minimumContrast, (Math.max(light, dark) + 0.05) / (Math.min(light, dark) + 0.05));
        checked++;
      }
    }
    probe.remove(); return { checked, minimumContrast, indistinguishable, unapplied };
  });
  expect(markerSweep.checked).toBe(720);
  expect(markerSweep.indistinguishable).toBe(0);
  expect(markerSweep.unapplied).toBe(0);
  expect(markerSweep.minimumContrast).toBeGreaterThanOrEqual(3);
});

for (const [scheme, theme, viewport] of [['Ocean', 'light', { width: 1280, height: 900 }],
  ['Amber', 'dark', { width: 390, height: 844 }], ['Forest', 'light', { width: 390, height: 844 }]]) {
  test(`recordings accent appearance ${scheme} ${theme}`, async ({ page }) => {
    await openRecordingAccentExample(page);
    // The screenshot stylesheet hides timing text; keep its reserved width deterministic too.
    await page.locator('.recordings-search-timing').evaluate(element => { element.textContent = 'Loaded in 100 ms'; });
    await page.setViewportSize(viewport);
    await page.evaluate(async ({ scheme, theme }) => {
      const { APPEARANCE_SCHEMES, applyThemeHue } = await import('/assets/core/theme.js');
      if (theme === 'dark') document.documentElement.dataset.theme = 'dark'; else delete document.documentElement.dataset.theme;
      applyThemeHue(APPEARANCE_SCHEMES.find(item => item.name === scheme).hue);
    }, { scheme, theme });
    expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
    await expect(page.locator('.recordings-browser')).toHaveScreenshot(`recordings-scheme-${scheme.toLowerCase()}-${theme}.png`, componentScreenshot);
  });
}

function browsingCalls(count = 61) {
  return Array.from({ length: count }, (_, index) => ({ ...linkedCall, id: 17 + index,
    start_ms: call.start_ms - index * 90_000, end_ms: call.start_ms - index * 90_000 + 20_000,
    duration_ms: 20_000, transcript_excerpt: index === 0 ? 'Engine four arriving at staging.' : null }));
}

test('top and bottom pagination stay synchronized and First returns to the applied first page', async ({ page }) => {
  const state = await openRecordings(page, { calls: browsingCalls(), paginate: true });
  const top = page.locator('.recordings-pager-top'), bottom = page.locator('.recordings-pager-bottom');
  await expect(page.locator('.recordings-pager')).toHaveCount(2);
  const counts = page.locator('.recordings-pager .ui-browse-pager-count');
  await expect(counts).toHaveText(['Page 1', 'Page 1']);
  await expect(page.locator('.recordings-call-gap')).toHaveCount(24);
  for (const pager of [top, bottom]) {
    await expect(pager.getByRole('button', { name: 'First', exact: true })).toBeDisabled();
    await expect(pager.getByRole('button', { name: 'Previous', exact: true })).toBeDisabled();
    await expect(pager.getByRole('button', { name: 'Next', exact: true })).toBeEnabled();
    await expect(pager.locator('svg')).toHaveCount(0);
    expect(await pager.locator('button, .ui-browse-pager-count').allTextContents())
      .toEqual(['First', 'Previous', 'Page 1', 'Next']);
  }
  await top.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(counts).toHaveText(['Page 2', 'Page 2']);
  expect(state.requestedFilters.at(-1)).toMatchObject({ cursor: 'fixture:25', limit: '25' });
  await bottom.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(counts).toHaveText(['Page 3', 'Page 3']);
  await expect(page.locator('.recordings-call')).toHaveCount(11);
  await expect(page.locator('.recordings-call-gap')).toHaveCount(10);
  for (const pager of [top, bottom]) await expect(pager.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
  await top.getByRole('button', { name: 'First', exact: true }).click();
  await expect(counts).toHaveText(['Page 1', 'Page 1']);
  await expect(page.locator('.recordings-call')).toHaveCount(25);
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('cursor');
});

test('Calls per page uses applied filters, resets pagination, and preserves playback and queue', async ({ page }) => {
  await stubRecordingAudio(page);
  const state = await openRecordings(page, { calls: browsingCalls(), paginate: true,
    query: { transcript: 'arriving', text_range: 'time', high_interest: 'true' } });
  await expect(page.locator('.recordings-call')).toHaveCount(25);
  const size = page.getByRole('combobox', { name: 'Calls per page', exact: true });
  await expect(size).toHaveValue('25');
  await expect(size.locator('option')).toHaveText(['25 per page', '50 per page']);
  await page.locator('.recordings-call-info').first().click();
  await page.getByRole('dialog', { name: 'Call actions', exact: true }).getByRole('button', { name: 'Play once', exact: true }).click();
  await page.locator('.recordings-call-info').nth(1).click();
  await page.getByRole('dialog', { name: 'Call actions', exact: true }).getByRole('button', { name: 'Add to queue', exact: true }).click();
  const queue = page.locator('#audio-dock .audio-dock-queue-count');
  await expect(queue).toContainText('1 queued');
  await page.locator('.recordings-pager-top').getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.recordings-pager-top .ui-browse-pager-count')).toHaveText('Page 2');
  const applied = { ...state.requestedFilters.at(-1) }; delete applied.cursor;
  await page.getByRole('searchbox', { name: 'Full text', exact: true }).fill('unsubmitted draft');
  await size.selectOption('50');
  await expect(page.locator('.recordings-call')).toHaveCount(50);
  await expect(page.locator('.recordings-pager .ui-browse-pager-count')).toHaveText(['Page 1', 'Page 1']);
  expect(state.requestedFilters.at(-1)).toEqual({ ...applied, limit: '50' });
  await expect(page.getByRole('searchbox', { name: 'Full text', exact: true })).toHaveValue('unsubmitted draft');
  await expect(queue).toContainText('1 queued');
  await expect(page.locator('.recordings-call').first()).toHaveClass(/recordings-call-playing/);
  expect(await page.evaluate(() => window.recordingPlays.length)).toBe(1);
  await page.locator('.recordings-pager-bottom').getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.recordings-call')).toHaveCount(11);
  expect(state.requestedFilters.at(-1)).toMatchObject({ cursor: 'fixture:50', limit: '50' });
  await expect(queue).toContainText('1 queued');
});

test('failed pagination, page size and sort preserve committed results, controls and timing', async ({ page }) => {
  const state = await openRecordings(page, { calls: browsingCalls(), paginate: true });
  const size = page.getByRole('combobox', { name: 'Calls per page', exact: true });
  const sort = page.getByRole('combobox', { name: 'Sort calls', exact: true });
  const top = page.locator('.recordings-pager-top');
  const timing = page.locator('.recordings-search-timing');
  await expect(timing).toHaveText(/^Loaded in \d+(?:\.\d+)? (?:ms|s)$/);
  await top.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(top.locator('.ui-browse-pager-count')).toHaveText('Page 2');
  const successfulTiming = await timing.textContent();
  const previousIds = await page.locator('.recordings-call').evaluateAll(cards => cards.map(card => card.dataset.recordingId));
  const expectPrevious = async () => {
    await expect(page.locator('.recordings-result-status')).toContainText('Previous results');
    await expect(page.locator('.recordings-pager .ui-browse-pager-count')).toHaveText(['Page 2', 'Page 2']);
    await expect(size).toHaveValue('25'); await expect(sort).toHaveValue('desc');
    await expect(timing).toHaveText(successfulTiming);
    expect(await page.locator('.recordings-call').evaluateAll(cards => cards.map(card => card.dataset.recordingId))).toEqual(previousIds);
  };
  state.errorNext = true; await size.selectOption('50'); await expectPrevious();
  state.errorNext = true; await sort.selectOption('asc'); await expectPrevious();
  state.errorNext = true; await top.getByRole('button', { name: 'First', exact: true }).click(); await expectPrevious();
  state.errorNext = true; await top.getByRole('button', { name: 'Next', exact: true }).click(); await expectPrevious();
  await top.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.recordings-call')).toHaveCount(11);
  expect(state.requestedFilters.at(-1)).toMatchObject({ cursor: 'fixture:50', limit: '25' });
});

test('loading locks both pagers and page size and timing updates only after successful rendering', async ({ page }) => {
  const state = await openRecordings(page, { calls: browsingCalls(), paginate: true });
  const timing = page.locator('.recordings-search-timing');
  await expect(timing).toHaveText(/^Loaded in \d+(?:\.\d+)? (?:ms|s)$/);
  const before = await timing.textContent();
  state.delayCalls = 180;
  await page.locator('.recordings-pager-top').getByRole('button', { name: 'Next', exact: true }).click();
  for (const control of await page.locator('.recordings-pager button').all()) await expect(control).toBeDisabled();
  await expect(page.getByRole('combobox', { name: 'Calls per page', exact: true })).toBeDisabled();
  await expect(page.getByRole('combobox', { name: 'Sort calls', exact: true })).toBeDisabled();
  await expect(timing).toHaveText(before);
  await expect(page.locator('.recordings-pager-top .ui-browse-pager-count')).toHaveText('Page 2');
  await expect(page.getByRole('combobox', { name: 'Calls per page', exact: true })).toBeEnabled();
  const after = await timing.textContent();
  const elapsed = Number(after.match(/[\d.]+/)[0]) * (after.endsWith(' s') ? 1000 : 1);
  expect(elapsed).toBeGreaterThanOrEqual(170);
  state.matchCalls = false;
  await page.getByRole('button', { name: 'Search recordings', exact: true }).click();
  await expect(page.locator('.recordings-call')).toHaveCount(0);
  await expect(page.locator('.recordings-results')).toContainText('No matching calls');
  await expect(timing).toBeVisible();
  await expect(timing).toHaveText(/^Loaded in \d+(?:\.\d+)? (?:ms|s)$/);
});

test('gap dividers use the earlier end, work in both orders, and exclude short, invalid, overlap and date gaps', async ({ page }) => {
  const t = Date.parse('2026-09-28T10:00:00Z');
  const make = (id, seconds, extra = {}) => ({ ...linkedCall, id, start_ms: t + seconds * 1000,
    duration_ms: 10_000, ...extra });
  const calls = [make(17, 0), make(18, 40), make(19, 75), make(20, 105),
    make(21, 150, { end_ms: t + 175_000 }), make(22, 205),
    make(23, 250, { end_ms: t + 300_000 }), make(24, 270),
    make(25, 330, { duration_ms: null, end_ms: null }), make(26, 450),
    make(27, 24 * 3600)];
  const state = await openRecordings(page, { calls, paginate: true });
  const gaps = page.locator('.recordings-call-gap');
  await expect(gaps.locator('.recordings-call-gap-label')).toHaveText(['Gap 30s', 'Gap 35s', 'Gap 30s', 'Gap 35s', 'Gap 30s']);
  await expect(gaps.first()).toHaveAttribute('aria-label', 'Gap between matching calls: 30s');
  await expect(gaps.locator('svg')).toHaveCount(0);
  const ascending = await gaps.locator('.recordings-call-gap-label').allTextContents();
  state.calls = [...calls].reverse();
  await page.getByRole('combobox', { name: 'Sort calls', exact: true }).selectOption('asc');
  await expect(gaps.locator('.recordings-call-gap-label')).toHaveText([...ascending].reverse());
  const coveredCalls = [make(101, 0, { end_ms: t + 400_000 }), make(102, 100), make(103, 300)];
  state.calls = coveredCalls;
  await page.getByRole('combobox', { name: 'Sort calls', exact: true }).selectOption('desc');
  await expect(page.locator('.recordings-call').first()).toHaveAttribute('data-recording-id', '101');
  await expect(gaps).toHaveCount(0);
  state.calls = [...coveredCalls].reverse();
  await page.getByRole('combobox', { name: 'Sort calls', exact: true }).selectOption('asc');
  await expect(page.locator('.recordings-call').first()).toHaveAttribute('data-recording-id', '103');
  await expect(gaps).toHaveCount(0);
});

for (const theme of ['light', 'dark']) for (const device of ['desktop', 'phone']) {
  test(`browsing controls and matching-call gaps ${theme} ${device}`, async ({ page }) => {
    await page.setViewportSize(device === 'phone' ? { width: 320, height: 844 } : { width: 1280, height: 900 });
    await openRecordings(page, { calls: browsingCalls(3), paginate: true, nextCursor: 'fixture:3', theme });
    await expect(page.locator('.recordings-call-gap')).toHaveCount(2);
    await expect(page.getByRole('combobox', { name: 'Calls per page', exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    for (const pager of await page.locator('.recordings-pager').all()) {
      const bounds = await pager.boundingBox();
      expect(bounds.x).toBeGreaterThanOrEqual(0);
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(device === 'phone' ? 320 : 1280);
    }
    await expect(page.locator('.recordings-library')).toHaveScreenshot(`recordings-browsing-${theme}-${device}.png`, componentScreenshot);
  });
}

test('an aborted search cannot replace the new recordings page results or search timing', async ({ page }) => {
  const state = await openRecordings(page, { calls: [linkedCall], paginate: true });
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  let heldRoute, release, finish;
  const released = new Promise(resolve => { release = resolve; });
  const finished = new Promise(resolve => { finish = resolve; });
  let holdNext = true;
  await page.route('**/api/v1/recordings/calls?**', async route => {
    if (!holdNext) { await route.fallback(); return; }
    holdNext = false; heldRoute = route;
    await released;
    // Navigating aborts the old browser request; resolving it must not alter the new page.
    await route.fulfill({ json: { data: { calls: [{ ...linkedCall, id: 999,
      talkgroup_alias: 'Obsolete search result' }], next_cursor: null, total: null } } }).catch(() => {});
    finish();
  });
  await page.getByRole('searchbox', { name: 'Full text', exact: true }).fill('obsolete search');
  await page.getByRole('button', { name: 'Search recordings', exact: true }).click();
  await expect.poll(() => Boolean(heldRoute)).toBe(true);
  state.calls = [{ ...linkedCall, id: 101, talkgroup_alias: 'Current search result' }];
  await page.goto('/app.html?view=recordings');
  await expect(page.locator('.recordings-call')).toHaveAttribute('data-recording-id', '101');
  const timing = await page.locator('.recordings-search-timing').textContent();
  release();
  await finished;
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
  await expect(page.locator('.recordings-call')).toHaveAttribute('data-recording-id', '101');
  await expect(page.locator('.recordings-search-timing')).toHaveText(timing);
  await expect(page.locator('.recordings-results')).not.toContainText('Obsolete search result');
});

// A pending search is a reminder to apply filters, not a loading animation.
const searchButton = page => page.getByRole('button', { name: 'Search recordings', exact: true });
async function expectSearchHalo(button, active) {
  if (active) {
    await expect.poll(() => button.evaluate(element => getComputedStyle(element, '::after').animationName)).toBe('ui-button-awaiting-apply-halo');
    await expect.poll(() => button.evaluate(element => getComputedStyle(element, '::after').animationIterationCount)).toBe('infinite');
  } else {
    await expect.poll(() => button.evaluate(element => getComputedStyle(element, '::after').animationName)).toBe('none');
  }
}

test('search halo repeats continuously for unapplied filters and clears on reversion, Undo or Discard', async ({ page }, testInfo) => {
  const state = await openRecordings(page);
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  const submit = searchButton(page);
  const fullText = page.getByRole('searchbox', { name: 'Full text', exact: true });
  await expectSearchHalo(submit, false);
  const interest = page.locator('.recordings-interest');
  await interest.click();
  await expectSearchHalo(submit, true);
  const repeated = await submit.evaluate(element => {
    const animation = element.getAnimations({ subtree: true }).find(item => item.effect?.getTiming().iterations === Infinity);
    if (!animation) return null;
    const duration = Number(animation.effect.getTiming().duration);
    animation.currentTime = duration * 3.25;
    return { duration, playState: animation.playState, iteration: animation.effect.getComputedTiming().currentIteration };
  });
  expect(repeated.duration).toBeGreaterThan(0);
  expect(repeated.playState).toBe('running');
  expect(repeated.iteration).toBeGreaterThanOrEqual(3);
  await expectSearchHalo(submit, true);
  for (const theme of ['light', 'dark']) {
    await page.evaluate(theme => {
      if (theme === 'dark') document.documentElement.dataset.theme = 'dark';
      else delete document.documentElement.dataset.theme;
    }, theme);
    for (const [phase, time] of [['midpulse', 350], ['quiet', 1250]]) {
      await submit.evaluate((element, time) => {
        const animation = element.getAnimations({ subtree: true }).find(item => item.effect?.getTiming().iterations === Infinity);
        animation.pause(); animation.currentTime = time;
      }, time);
      await page.locator('.recordings-search-panel').screenshot({ path: testInfo.outputPath(`search-halo-${theme}-desktop-${phase}.png`), animations: 'allow' });
    }
  }
  await submit.evaluate(element => element.getAnimations({ subtree: true }).forEach(animation => animation.play()));
  expect(state.requestedFilters).toHaveLength(1);

  await interest.click();
  await expectSearchHalo(submit, false);
  await fullText.fill('dispatch');
  await searchNow(page, state);
  await expectSearchHalo(submit, false);
  const chips = page.locator('.recordings-selected-filters');
  await chips.getByRole('button', { name: 'Remove Full text: dispatch filter', exact: true }).click();
  await expectSearchHalo(submit, true);
  await chips.getByRole('button', { name: 'Undo removal of Full text: dispatch filter', exact: true }).click();
  await expectSearchHalo(submit, false);

  let picker = await openPicker(page);
  await picker.locator('.recordings-picker-choice').filter({ hasText: 'Fire Dispatch' }).getByRole('checkbox').check();
  await picker.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expectSearchHalo(submit, false);
  picker = await openPicker(page);
  await picker.locator('.recordings-picker-choice').filter({ hasText: 'Fire Dispatch' }).getByRole('checkbox').check();
  await picker.getByRole('button', { name: 'Use selection', exact: true }).click();
  await expectSearchHalo(submit, true);
  await page.getByRole('button', { name: 'Discard changes', exact: true }).click();
  await expectSearchHalo(searchButton(page), false);
});

test('search halo stops on the search attempt including validation and request failures, and new edits rearm it', async ({ page }) => {
  const state = await openRecordings(page);
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  const submit = searchButton(page);
  const range = page.getByRole('combobox', { name: 'Time range', exact: true });
  await range.selectOption('custom');
  await expectSearchHalo(submit, true);
  await submit.click();
  await expect(page.locator('.recordings-search-error')).toContainText('Choose a date range');
  await expectSearchHalo(submit, false);
  expect(state.requestedFilters).toHaveLength(1);
  const from = page.getByLabel('From', { exact: true });
  const to = page.getByLabel('To', { exact: true });
  // Seconds violate datetime-local's native minute step, before form submit fires.
  await from.fill('2026-09-28T10:08:42');
  await to.fill('2026-09-28T11:08:42');
  await expect.poll(() => from.evaluate(element => element.validity.stepMismatch)).toBe(true);
  await expectSearchHalo(submit, true);
  await submit.click();
  await expectSearchHalo(submit, false);
  expect(state.requestedFilters).toHaveLength(1);
  await from.fill('2026-09-28T10:08');
  await to.fill('2026-09-28T11:08');
  await range.selectOption('7d');
  await expectSearchHalo(submit, true);

  state.errorNext = true;
  state.delayCalls = 350;
  await submit.click();
  await expectSearchHalo(submit, false);
  await expect(page.locator('.recordings-result-status')).toContainText('Previous results');
  await expectSearchHalo(submit, false);
  await range.selectOption('30d');
  await expectSearchHalo(submit, true);
  await range.selectOption('7d');
  await expectSearchHalo(submit, true);
  const fullText = page.getByRole('searchbox', { name: 'Full text', exact: true });
  await fullText.fill('dispatch');
  await expectSearchHalo(submit, true);
  await fullText.press('Enter');
  await expectSearchHalo(submit, false);
  await expect(page.locator('.recordings-selected-filters')).toContainText('Full text: dispatch');
  await expectSearchHalo(submit, false);
});

test('paging, page size and sort preserve the search halo for an unapplied draft', async ({ page }) => {
  const state = await openRecordings(page, { calls: browsingCalls(), paginate: true });
  await expect(page.locator('.recordings-call')).toHaveCount(25);
  const submit = searchButton(page);
  await page.getByRole('searchbox', { name: 'Full text', exact: true }).fill('unsubmitted draft');
  await expectSearchHalo(submit, true);
  await page.locator('.recordings-pager-top').getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.recordings-pager-top .ui-browse-pager-count')).toHaveText('Page 2');
  await expectSearchHalo(submit, true);
  await page.getByRole('combobox', { name: 'Calls per page', exact: true }).selectOption('50');
  await expect(page.locator('.recordings-call')).toHaveCount(50);
  await expectSearchHalo(submit, true);
  await page.getByRole('combobox', { name: 'Sort calls', exact: true }).selectOption('asc');
  await expect.poll(() => state.requestedFilters.at(-1).sort).toBe('asc');
  await expectSearchHalo(submit, true);
  expect(state.requestedFilters.every(filters => !filters.transcript)).toBe(true);
});

for (const theme of ['light']) {
  test(`search halo animates with reduced motion, all schemes and keyboard focus on a 320px ${theme} page`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 320, height: 844 });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await openRecordings(page, { theme });
    await expect(page.locator('.recordings-call')).toHaveCount(1);
    await ensureSearchOpen(page);
    const submit = searchButton(page);
    const before = await submit.evaluate(element => getComputedStyle(element, '::before').borderTopWidth);
    await page.getByRole('searchbox', { name: 'Full text', exact: true }).fill('dispatch');
    await submit.scrollIntoViewIfNeeded();
    await expectSearchHalo(submit, true);
    await expect.poll(() => submit.evaluate(element => getComputedStyle(element, '::before').borderTopWidth)).not.toBe(before);
    await expect.poll(() => submit.evaluate(element => getComputedStyle(element, '::before').borderTopWidth)).toBe('2px');
    const wave = await submit.evaluate(element => getComputedStyle(element, '::after').opacity);
    await expect.poll(() => submit.evaluate(element => getComputedStyle(element, '::after').opacity)).not.toBe(wave);
    const palettes = await submit.evaluate(async element => {
      const { APPEARANCE_SCHEMES, applyThemeHue } = await import('/assets/core/theme.js');
      const root = document.documentElement;
      return APPEARANCE_SCHEMES.map(scheme => {
        applyThemeHue(scheme.hue, root);
        const ring = getComputedStyle(element, '::before');
        const wave = getComputedStyle(element, '::after');
        return { name: scheme.name, color: ring.borderTopColor, width: ring.borderTopWidth,
          style: ring.borderTopStyle, ringOpacity: ring.opacity, animation: wave.animationName, waveOpacity: wave.opacity };
      });
    });
    expect(palettes).toHaveLength(8);
    expect(new Set(palettes.map(palette => palette.color)).size).toBe(8);
    for (const palette of palettes) {
      expect(palette.color, palette.name).not.toBe('rgba(0, 0, 0, 0)');
      expect(palette.width, palette.name).toBe('2px');
      expect(palette.style, palette.name).toBe('solid');
      expect(palette.ringOpacity, palette.name).toBe('1');
      expect(palette.animation, palette.name).toBe('ui-button-awaiting-apply-halo');
    }
    await submit.focus();
    await page.keyboard.press('Shift+Tab');
    await page.keyboard.press('Tab');
    await expect(submit).toBeFocused();
    await expect(submit).toHaveCSS('outline-style', 'solid');
    const bounds = await submit.boundingBox();
    expect(bounds.x).toBeGreaterThanOrEqual(0);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(320);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    await page.locator('.recordings-search-panel').screenshot({ path: testInfo.outputPath(`search-halo-${theme}-320.png`) });
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await expectSearchHalo(submit, true);
    await submit.evaluate(element => element.blur());
    for (const [phase, time] of [['midpulse', 350], ['quiet', 1250]]) {
      await submit.evaluate((element, time) => {
        const animation = element.getAnimations({ subtree: true }).find(item => item.effect?.getTiming().iterations === Infinity);
        animation.pause(); animation.currentTime = time;
      }, time);
      await page.locator('.recordings-search-panel').screenshot({ path: testInfo.outputPath(`search-halo-${theme}-320-${phase}.png`), animations: 'allow' });
    }
    await submit.focus();
    await submit.press('Enter');
    await expect(page.getByRole('dialog', { name: 'Filters', exact: true })).toHaveCount(0);
    await expect(page.locator('.modal-backdrop')).toHaveCount(0);
    await expect(page.locator('#recordings-filters-toggle')).toHaveAttribute('aria-expanded', 'true');
    await expect(page.locator('.recordings-filter-summary')).toContainText('dispatch');
    await expectSearchHalo(searchButton(page), false);
  });
}
