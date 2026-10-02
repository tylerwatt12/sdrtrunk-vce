'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

// The global player is covered separately; these snapshots isolate recording components.
const componentScreenshot = { stylePath: path.resolve(__dirname, 'fixtures/hide-global-audio-dock.css') };

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
  return host.locator(`a[href="${href}"]`);
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

async function expectTranscriptsClearInfo(page) {
  const layout = await page.evaluate(() => ({
    overflow: document.documentElement.scrollWidth - window.innerWidth,
    excerpts: [...document.querySelectorAll('.recordings-transcript-preview')].map((element) => {
      const style = getComputedStyle(element);
      const card = element.closest('.recordings-call');
      const text = element.getBoundingClientRect();
      return { height: text.height, right: text.right,
        contentRight: card.querySelector('.recordings-call-main').getBoundingClientRect().right,
        infoLeft: card.querySelector('.recordings-call-info').getBoundingClientRect().left,
        lineHeight: Number.parseFloat(style.lineHeight) || Number.parseFloat(style.fontSize) * 1.5,
        textOverflow: element.scrollWidth - element.clientWidth };
    })
  }));
  expect(layout.overflow).toBeLessThanOrEqual(0);
  expect(layout.excerpts.length).toBeGreaterThan(0);
  expect(layout.excerpts[0].height).toBeGreaterThan(layout.excerpts[0].lineHeight + 1);
  for (const excerpt of layout.excerpts) {
    expect(excerpt.height).toBeLessThanOrEqual(excerpt.lineHeight * 2 + 1);
    expect(excerpt.textOverflow).toBeLessThanOrEqual(1);
    expect(excerpt.right).toBeLessThanOrEqual(excerpt.infoLeft - 4);
    expect(excerpt.contentRight).toBeLessThanOrEqual(excerpt.infoLeft - 4);
  }
}

async function openRecordings(page, options = {}) {
  const state = {
    mode: options.mode || 'MANAGED', available: options.available !== false,
    hasCalls: options.hasCalls !== false, matchCalls: true, admin: options.admin !== false,
    call: options.call || call, calls: options.calls || null, callDetail: options.callDetail || null,
    requestedFilters: [], suggestionRequests: [],
    paginate: options.paginate === true,
    transcription: options.transcription || null, settingsWrites: [], maintenanceWrites: [],
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
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const pathname = url.pathname;
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: { data: { configured: true, authenticated: state.admin,
        username: state.admin ? 'admin' : null, tier: state.admin ? 'admin' : 'public',
        primary: state.admin, capabilities: { recordings: true,
          'admin-recordings': state.admin, 'admin-settings': state.admin, dashboard: true,
          radio: options.radio !== false } } } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } });
    } else if (pathname === '/api/v1/recordings/status') {
      await route.fulfill({ json: { data: { mode: state.mode, available: state.available,
        has_calls: state.hasCalls } } });
    } else if (pathname === '/api/v1/recordings/calls') {
      state.requestedFilters.push(Object.fromEntries(url.searchParams));
      const matching = state.hasCalls && state.matchCalls;
      const calls = matching ? state.calls || [state.call] : [];
      const offset = state.paginate ? Number((url.searchParams.get('cursor') || 'fixture:0').split(':')[1]) : 0;
      const limit = state.paginate ? Number(url.searchParams.get('limit')) : calls.length;
      await route.fulfill({ json: { data: { calls: options.emptyFirstBatch && offset === 0 ? [] :
        calls.slice(offset, offset + limit),
        next_cursor: state.paginate && offset + limit < calls.length ? `fixture:${offset + limit}` : null,
        total: null } } });
    } else if (/^\/api\/v1\/recordings\/calls\/\d+$/.test(pathname)) {
      const id = Number(pathname.split('/').at(-1));
      const selected = state.calls?.find((item) => item.id === id) || state.call;
      await route.fulfill({ json: { data: { call: state.callDetail || selected,
        ...(state.transcription ? { transcription: state.transcription } : {}) } } });
    } else if (pathname === '/api/v1/admin/recordings/settings') {
      if (route.request().method() === 'PUT') {
        const update = route.request().postDataJSON();
        state.settingsWrites.push(update);
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
        mp3_normalize_audio: false, stats_logging_enabled: true, stats_detailed_history_enabled: false,
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
            system_key: 'metro', system_name: 'Metro Public Safety' }
        ] : []);
        await route.fulfill({ json: { data: { suggestions } } });
      }
    } else if (pathname === '/api/v1/status') {
      await route.fulfill({ json: { data: {} } });
    } else {
      await route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
    }
  });
  await page.goto(options.view === 'admin' ? `/app.html?view=admin&tab=${options.tab || 'recordings'}` :
    '/app.html?view=recordings');
  return state;
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
  await expect(page.getByRole('combobox', { name: 'Find a call' })).toBeVisible();
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

for (const theme of ['light', 'dark']) {
  test(`desktop ${theme} sidebar aligns controls and exposes structured autocomplete`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await openRecordings(page, { theme });
    await expect(page.locator('.recordings-call')).toHaveCount(1);
    const rowSelection = page.locator('.recordings-call-select');
    await expect(rowSelection).toHaveClass(/ui-selection-check/);
    await expect(rowSelection).toHaveCSS('appearance', 'none');
    const time = page.getByRole('combobox', { name: 'Date & time' });
    const query = page.getByRole('combobox', { name: 'Find a call' });
    const timeBox = await time.boundingBox();
    const queryBox = await query.boundingBox();
    expect(Math.abs(timeBox.x - queryBox.x)).toBeLessThan(2);
    expect(Math.abs(timeBox.width - queryBox.width)).toBeLessThan(2);
    expect(queryBox.y).toBeGreaterThan(timeBox.y + timeBox.height);
    expect(Math.abs(timeBox.height - queryBox.height)).toBeLessThan(2);
    expect(queryBox.width).toBeGreaterThan(150);
    await expect(page.getByRole('button', { name: /^Filters/ })).toBeVisible();
    await expect(page.locator('.recordings-library')).toHaveScreenshot(`recordings-desktop-${theme}.png`, componentScreenshot);

    await query.fill('fire');
    const suggestions = page.getByRole('listbox');
    await expect(suggestions.getByRole('option')).toHaveCount(1);
    await expect(suggestions.getByRole('option')).toContainText('Talkgroup');
    await expect(suggestions.getByRole('option')).toContainText('Metro Public Safety');
    const sidebarBox = await page.locator('.recordings-search-panel').boundingBox();
    const suggestionsBox = await suggestions.boundingBox();
    expect(suggestionsBox.x).toBeGreaterThanOrEqual(sidebarBox.x);
    expect(suggestionsBox.x + suggestionsBox.width).toBeLessThanOrEqual(sidebarBox.x + sidebarBox.width);
    if (theme === 'light') await expect(page.locator('.recordings-library'))
      .toHaveScreenshot('recordings-suggestions-light.png', componentScreenshot);
    await query.press('ArrowDown');
    await expect(query).toHaveAttribute('aria-activedescendant', /recordings-options-q-0/);
    await query.press('Enter');
    await expect(suggestions).toBeHidden();
    await expect(page.locator('.recordings-selected-filters')).toContainText('Fire Dispatch');
    await page.getByRole('button', { name: 'Search', exact: true }).click();
    await expect(page.locator('.recordings-selected-filters')).toContainText('Fire Dispatch');
    if (theme === 'light') await expect(page.locator('.recordings-library'))
      .toHaveScreenshot('recordings-filtered-context-light.png', componentScreenshot);
    await page.getByRole('button', { name: /^Filters/ }).click();
    await expect(page.getByRole('combobox', { name: 'Radio system' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Clear filters' })).toBeVisible();
    const fields = page.locator('.recordings-search-fields .recordings-field');
    const bounds = await fields.evaluateAll((elements) => elements.map((element) => {
      const box = element.getBoundingClientRect();
      return { x: box.x, right: box.right, y: box.y, bottom: box.bottom };
    }));
    expect(bounds.length).toBeGreaterThan(10);
    for (const [index, box] of bounds.entries()) {
      expect(box.x).toBeGreaterThanOrEqual(sidebarBox.x);
      expect(box.right).toBeLessThanOrEqual(sidebarBox.x + sidebarBox.width);
      if (index) {
        expect(Math.abs(box.x - bounds[0].x)).toBeLessThan(2);
        expect(box.y).toBeGreaterThanOrEqual(bounds[index - 1].bottom);
      }
    }
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
}

test('suggestions show no-match and failure states, then stay closed after blur', async ({ page }) => {
  await openRecordings(page);
  const query = page.getByRole('combobox', { name: 'Find a call' });
  await query.fill('zzzz');
  await expect(page.getByRole('listbox')).toContainText('No matching names or IDs');
  await query.fill('error');
  await expect(page.getByRole('listbox')).toContainText('Suggestions could not load');
  const requested = page.waitForRequest((request) => request.url().includes('/recordings/suggestions?q=slow'));
  await query.fill('slow');
  await requested;
  await page.getByRole('combobox', { name: 'Date & time' }).focus();
  await page.waitForTimeout(450);
  await expect(page.getByRole('listbox')).toBeHidden();
});

test('call length steps from sub-second to whole seconds with a thirty-second-plus endpoint', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const state = await openRecordings(page);
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  await page.getByRole('button', { name: /^Filters/ }).click();
  for (const name of ['WACN', 'SysID', 'RFSS', 'Site ID', 'Minimum length', 'Maximum length']) {
    await expect(page.getByLabel(name, { exact: true })).toHaveCount(0);
  }
  const lower = page.getByRole('slider', { name: 'Call length: Minimum', exact: true });
  const upper = page.getByRole('slider', { name: 'Call length: Maximum', exact: true });
  await expect(lower).toHaveValue('0');
  await expect(lower).toHaveAttribute('aria-valuetext', '<1 second');
  await expect(upper).toHaveValue('30');
  await expect(upper).toHaveAttribute('aria-valuetext', '30 seconds+');
  await expect(page.locator('.ui-dual-range-field')).toHaveScreenshot('recordings-duration-defaults-desktop.png', componentScreenshot);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('min_duration_ms');
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('max_duration_ms');

  await lower.focus();
  await lower.press('ArrowRight');
  await expect(lower).toHaveValue('1');
  await expect(lower).toHaveAttribute('aria-valuetext', '1 second');
  await upper.focus();
  await upper.press('Home');
  await expect(upper).toHaveValue('1');
  await expect(lower).toHaveAttribute('aria-valuemax', '1');
  await expect(upper).toHaveAttribute('aria-valuemin', '1');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  expect(state.requestedFilters.at(-1)).toMatchObject({ min_duration_ms: '1000', max_duration_ms: '1000' });
  await upper.focus();
  await upper.press('End');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  expect(state.requestedFilters.at(-1)).toHaveProperty('min_duration_ms', '1000');
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('max_duration_ms');
  await lower.press('End');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  expect(state.requestedFilters.at(-1)).toHaveProperty('min_duration_ms', '30000');
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('max_duration_ms');

  await page.getByRole('button', { name: 'Clear filters' }).click();
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  await page.getByRole('button', { name: /^Filters/ }).click();
  await expect(page.getByRole('slider', { name: 'Call length: Minimum', exact: true })).toHaveValue('0');
  await expect(page.getByRole('slider', { name: 'Call length: Maximum', exact: true }))
    .toHaveAttribute('aria-valuetext', '30 seconds+');
});

test('numeric talkgroups can be selected in Find a call and the dedicated filter', async ({ page }) => {
  const unknownGroup = { kind: 'talkgroup', id: 7, label: 'Talkgroup 7',
    detail: 'Metro Public Safety · TG 7', system_key: 'metro', system_name: 'Metro Public Safety' };
  const state = await openRecordings(page, { suggestions: [unknownGroup] });
  const query = page.getByRole('combobox', { name: 'Find a call' });
  await query.fill('7');
  await expect(page.locator('#recordings-options-q').getByRole('option')).toHaveCount(1);
  expect(state.suggestionRequests.at(-1)).toMatchObject({ q: '7' });
  await query.press('ArrowDown');
  await query.press('Enter');
  await expect(query).toHaveValue('');
  await expect(page.locator('.recordings-selected-filters')).toContainText('Talkgroup 7');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  expect(state.requestedFilters.at(-1)).toMatchObject({ talkgroup_id: '7', system_key: 'metro' });
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('q');

  await page.getByRole('button', { name: /^Filters/ }).click();
  const talkgroup = page.getByRole('combobox', { name: 'Talkgroup', exact: true });
  await talkgroup.fill('7');
  await expect(page.locator('#recordings-options-talkgroup_id').getByRole('option')).toHaveCount(1);
  expect(state.suggestionRequests.at(-1)).toMatchObject({ q: '7', kind: 'talkgroup', system_key: 'metro' });
  await talkgroup.press('ArrowDown');
  await expect(talkgroup).toHaveAccessibleName('Talkgroup');
  await talkgroup.press('Enter');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  expect(state.requestedFilters.at(-1)).toMatchObject({ talkgroup_id: '7', system_key: 'metro' });
});

test('raw numeric identities keep the selected system friendly name', async ({ page }) => {
  const state = await openRecordings(page, { suggestions: (q) => q === 'metro' ? [
    { kind: 'system', id: systemKey, system_key: systemKey, label: 'Metro Public Safety' }
  ] : q === '7' ? [
    { kind: 'talkgroup', id: 7, label: 'Talkgroup 7', system_key: systemKey }
  ] : q === '8' ? [
    { kind: 'radio', id: 8, label: 'Radio 8', system_key: systemKey }
  ] : [] });
  const query = page.getByRole('combobox', { name: 'Find a call' });
  const chips = page.locator('.recordings-selected-filters');
  for (const term of ['metro', '7', '8']) {
    await query.fill(term);
    await expect(page.locator('#recordings-options-q').getByRole('option')).toHaveCount(1);
    await query.press('ArrowDown');
    await query.press('Enter');
    await expect(chips.getByRole('button', { name: 'Remove Metro Public Safety filter', exact: true }))
      .toBeVisible();
    await expect(chips.getByRole('button', { name: `Remove ${systemKey} filter`, exact: true })).toHaveCount(0);
  }
  expect(state.suggestionRequests.at(-1)).toMatchObject({ q: '8', system_key: systemKey });
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  expect(state.requestedFilters.at(-1)).toMatchObject({ system_key: systemKey, talkgroup_id: '7', radio_id: '8' });
});

test('named site suggestions use channel names and preserve their hidden site tuple', async ({ page }) => {
  const recordedSite = { kind: 'site', id: channelId, channel_id: channelId,
    label: 'North Ridge Channel', detail: 'Metro Public Safety · North Ridge · RFSS 1 · Site 2',
    wacn: 1, sysid: 1, rfss: 1, site_id: 2 };
  const state = await openRecordings(page, { suggestions: [recordedSite] });
  await page.getByRole('button', { name: /^Filters/ }).click();
  const site = page.getByRole('combobox', { name: 'Site', exact: true });
  await site.fill('ridge');
  await expect(page.locator('#recordings-options-site').getByRole('option'))
    .toContainText('North Ridge Channel');
  await site.press('ArrowDown');
  await expect(site).toHaveAccessibleName('Site');
  await expect(site).toHaveAccessibleDescription('Choose a site from the suggestions.');
  await site.press('Enter');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  expect(state.requestedFilters.at(-1)).toMatchObject({ wacn: '1', sysid: '1', rfss: '1', site_id: '2' });
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('channel_id');
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('site');
  await page.locator('.recordings-selected-filters').getByRole('button', { name: /North Ridge Channel/ }).click();
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  for (const key of ['wacn', 'sysid', 'rfss', 'site_id']) {
    expect(state.requestedFilters.at(-1)).not.toHaveProperty(key);
  }

  const query = page.getByRole('combobox', { name: 'Find a call' });
  await query.fill('ridge');
  await expect(page.locator('#recordings-options-q').getByRole('option'))
    .toContainText('North Ridge Channel');
  await query.press('ArrowDown');
  await query.press('Enter');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  expect(state.requestedFilters.at(-1)).toMatchObject({ wacn: '1', sysid: '1', rfss: '1', site_id: '2' });
  expect(state.requestedFilters.at(-1)).not.toHaveProperty('q');
});

test.describe('touch call length', () => {
  test.use({ hasTouch: true });
  test('mobile duration handles survive filter dialog reopen and support touch', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const state = await openRecordings(page);
    await page.getByRole('button', { name: /^Filters/ }).click();
    const dialog = page.getByRole('dialog', { name: /^Filters/ });
    const lower = dialog.getByRole('slider', { name: 'Call length: Minimum', exact: true });
    const upper = dialog.getByRole('slider', { name: 'Call length: Maximum', exact: true });
    await lower.scrollIntoViewIfNeeded();
    const rail = await dialog.locator('.ui-dual-range-rail').boundingBox();
    await page.touchscreen.tap(rail.x + rail.width / 3, rail.y + rail.height / 2);
    await expect(lower).toHaveValue('10');
    await page.touchscreen.tap(rail.x + rail.width * 2 / 3, rail.y + rail.height / 2);
    await expect(upper).toHaveValue('20');
    await dialog.getByRole('button', { name: 'Apply filters' }).click();
    await expect(dialog).toBeHidden();
    expect(state.requestedFilters.at(-1)).toMatchObject({ min_duration_ms: '10000', max_duration_ms: '20000' });
    await page.getByRole('button', { name: /^Filters/ }).click();
    await expect(lower).toHaveValue('10');
    await expect(upper).toHaveValue('20');
    await lower.press('Home');
    await upper.press('Home');
    await expect(upper).toHaveAttribute('aria-valuetext', '<1 second');
    await dialog.getByRole('button', { name: 'Apply filters' }).click();
    expect(state.requestedFilters.at(-1)).toHaveProperty('max_duration_ms', '999');
    expect(state.requestedFilters.at(-1)).not.toHaveProperty('min_duration_ms');
    await page.getByRole('button', { name: /^Filters/ }).click();
    await expect(upper).toHaveValue('0');
    await expect(upper).toHaveAttribute('aria-valuetext', '<1 second');
    await dialog.getByRole('button', { name: 'Clear filters' }).click();
    await page.getByRole('button', { name: /^Filters/ }).click();
    await expect(dialog.getByRole('slider', { name: 'Call length: Minimum', exact: true })).toHaveValue('0');
    await expect(dialog.getByRole('slider', { name: 'Call length: Maximum', exact: true }))
      .toHaveAttribute('aria-valuetext', '30 seconds+');
  });
});

test('empty search results omit count and pagination', async ({ page }) => {
  const state = await openRecordings(page);
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  state.matchCalls = false;
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.locator('.recordings-results')).toContainText('No matching calls');
  await expect(page.locator('.recordings-result-count')).toBeHidden();
  await expect(page.locator('.recordings-pager')).toBeHidden();
});

test('search controls stay within the narrow desktop boundary', async ({ page }) => {
  await page.setViewportSize({ width: 768, height: 900 });
  await openRecordings(page);
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  const time = await page.getByRole('combobox', { name: 'Date & time' }).boundingBox();
  const query = await page.getByRole('combobox', { name: 'Find a call' }).boundingBox();
  expect(Math.abs(time.y - query.y)).toBeLessThan(2);
  expect(Math.abs(time.height - query.height)).toBeLessThan(2);
});

for (const width of [1151, 1440]) {
  test(`recordings at ${width}px use a quarter-width filter sidebar`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const state = await openRecordings(page, { calls: mixedRecordingCalls(), paginate: true });
    await expect(page.locator('.recordings-call')).toHaveCount(25);
    expect(state.requestedFilters.at(-1).limit).toBe('25');
    const search = await page.locator('.recordings-search-panel').boundingBox();
    const results = await page.locator('.recordings-results-panel').boundingBox();
    expect(Math.abs(search.y - results.y)).toBeLessThan(2);
    expect(results.x).toBeGreaterThan(search.x + search.width);
    expect(results.width / search.width).toBeGreaterThan(2.8);
    expect(results.width / search.width).toBeLessThan(3.2);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
}

for (const width of [1150, 768, 390]) {
  test(`recordings at ${width}px stack filters above results`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const state = await openRecordings(page, { calls: mixedRecordingCalls(), paginate: true });
    await expect(page.locator('.recordings-call')).toHaveCount(25);
    expect(state.requestedFilters.at(-1).limit).toBe('25');
    const search = await page.locator('.recordings-search-panel').boundingBox();
    const results = await page.locator('.recordings-results-panel').boundingBox();
    expect(Math.abs(search.x - results.x)).toBeLessThan(2);
    expect(Math.abs(search.width - results.width)).toBeLessThan(2);
    expect(results.y).toBeGreaterThanOrEqual(search.y + search.height);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
}

for (const theme of ['light', 'dark']) {
  test(`mobile ${theme} filter sheet fills viewport and applies fields`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const state = await openRecordings(page, { theme });
    await expect(page.locator('.recordings-call')).toHaveCount(1);
    await expect(page.locator('.recordings-library')).toHaveScreenshot(`recordings-mobile-${theme}.png`, componentScreenshot);
    await page.getByRole('button', { name: /^Filters/ }).click();
    const sheet = page.getByRole('dialog', { name: /^Filters/ });
    await expect(sheet).toBeVisible();
    const bounds = await sheet.boundingBox();
    expect(bounds.width).toBe(390);
    expect(bounds.height).toBe(844);
    await expect(sheet.getByRole('combobox', { name: 'Radio system' })).toBeVisible();
    await expect(sheet).toHaveScreenshot(`recordings-mobile-filters-${theme}.png`, componentScreenshot);
    const duration = sheet.locator('.ui-dual-range-field');
    await duration.scrollIntoViewIfNeeded();
    await expect(duration).toHaveScreenshot(`recordings-duration-defaults-mobile-${theme}.png`, componentScreenshot);
    await sheet.locator('.modal-content').evaluate((content) => { content.scrollTop = content.scrollHeight; });
    const lastField = await sheet.locator('.recordings-search-fields .recordings-field').last().boundingBox();
    const footer = await sheet.locator('.ui-filter-sheet-actions').boundingBox();
    expect(lastField.y + lastField.height).toBeLessThanOrEqual(footer.y);
    await sheet.getByRole('button', { name: 'Apply filters' }).click();
    await expect(sheet).toBeHidden();
    expect(state.requestedFilters.length).toBeGreaterThan(1);
  });
}

test('linked recording cards and details open canonical entity pages', async ({ page }) => {
  await openRecordings(page, { call: linkedCall });
  const card = page.locator('.recordings-call');
  await expect(card).toHaveCount(1);
  await expect(card.locator('.recordings-call-title')).toBeVisible();
  const cardLabels = ['Metro Public Safety', 'North Ridge Channel', 'Fire Dispatch', 'Engine 4'];
  for (const [index, target] of linkedHrefs.entries()) {
    if (index === 1) {
      await expect(entityLink(card, target)).toHaveCount(0);
      continue;
    }
    await expect(entityLink(card, target).first()).toBeVisible();
    await expect(entityLink(card, target).first()).toContainText(cardLabels[index]);
  }
  await expect(card.locator('.recordings-call-title')).toContainText('Fire Dispatch');
  await expect(card).toHaveScreenshot('recordings-linked-desktop.png', componentScreenshot);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(card).toHaveScreenshot('recordings-linked-mobile.png', componentScreenshot);
  await card.locator('.recordings-call-info').click();
  const detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail).toBeVisible();
  for (const target of linkedHrefs) {
    await expect(entityLink(detail, target).first()).toBeVisible();
  }
  await expect(detail).toHaveScreenshot('recordings-linked-detail-mobile.png', componentScreenshot);
});

test('conventional recordings link their configured channel without a radio system', async ({ page }) => {
  const analog = {
    ...call, system_name: null, site_name: null, talkgroup_id: null, talkgroup_alias: null,
    source_id: null, source_alias: null, source_ota_alias: null,
    call_type: 'CONVENTIONAL', protocol: 'NBFM',
    channel_id: channelId, channel_name: 'Hilltop FM',
    channel_entity_ref: { kind: 'channel', key: channelId }
  };
  await openRecordings(page, { call: analog });
  const card = page.locator('.recordings-call');
  const channelHref = `/?view=channel&configuration_id=${channelId}`;
  await expect(card.locator('.recordings-call-title')).toBeVisible();
  await expect(entityLink(card, channelHref).first()).toContainText('Hilltop FM');
  await card.locator('.recordings-call-info').click();
  const detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(entityLink(detail, channelHref).first()).toContainText('Hilltop FM');
});

test('direct destination and patch member facts link to their identities', async ({ page }) => {
  const direct = {
    ...linkedCall, call_type: 'DIRECT', talkgroup_id: null, talkgroup_alias: null,
    destination_radio_id: 42137, destination_radio_alias: 'Unit 12',
    target_entity_ref: {
      kind: 'radio', radio_system_key: systemKey, identity_key: 'v1-r-00001-001-42137'
    }
  };
  await openRecordings(page, { call: direct });
  await expect(entityLink(page.locator('.recordings-call'),
    '/?view=radio&radio_system_key=p25%3A00001%3A001&identity_key=v1-r-00001-001-42137').first())
    .toContainText('Direct to Unit 12');
  await page.locator('.recordings-call-info').click();
  let detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(entityLink(detail,
    '/?view=radio&radio_system_key=p25%3A00001%3A001&identity_key=v1-r-00001-001-42137').first())
    .toBeVisible();

  const patch = {
    ...linkedCall, call_type: 'PATCH', talkgroup_alias: 'Dispatch Patch',
    target_entity_ref: {
      kind: 'patch_group', radio_system_key: systemKey, identity_key: 'v1-p-00001-001-1201'
    },
    patch_members: [{ kind: 'talkgroup', id: 1202, alias: 'Fireground',
      entity_ref: { kind: 'talkgroup', radio_system_key: systemKey,
        identity_key: 'v1-g-00001-001-1202' } }]
  };
  await openRecordings(page, { call: patch });
  await expect(entityLink(page.locator('.recordings-call'),
    '/?view=group-identity&radio_system_key=p25%3A00001%3A001&identity_key=v1-p-00001-001-1201').first())
    .toContainText('Dispatch Patch');
  await page.locator('.recordings-call-info').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(entityLink(detail,
    '/?view=group-identity&radio_system_key=p25%3A00001%3A001&identity_key=v1-p-00001-001-1201').first())
    .toBeVisible();
  await expect(entityLink(detail,
    '/?view=group-identity&radio_system_key=p25%3A00001%3A001&identity_key=v1-g-00001-001-1202').first())
    .toContainText('Fireground');
});

test('recording identities remain readable when references or radio access are absent', async ({ page }) => {
  await openRecordings(page);
  let card = page.locator('.recordings-call');
  await expect(card).toContainText('Fire Dispatch');
  await expect(card).toContainText('Engine 4');
  await expect(card.locator('a[href*="view="]')).toHaveCount(0);
  await card.locator('.recordings-call-info').click();
  let detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail).toContainText('Fire Dispatch');
  await expect(detail.locator('a[href*="view="]')).toHaveCount(0);

  await openRecordings(page, { call: linkedCall, radio: false });
  card = page.locator('.recordings-call');
  await expect(card).toContainText('Fire Dispatch');
  await expect(card).toContainText('Engine 4');
  await expect(card.locator('a[href*="view="]')).toHaveCount(0);
  await card.locator('.recordings-call-info').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail).toContainText('North Ridge Channel');
  await expect(detail.locator('a[href*="view="]')).toHaveCount(0);
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

  await workspace.getByRole('radio', { name: /Managed/ }).check();
  await workspace.getByRole('button', { name: 'Save mode', exact: true }).click();
  await expect(workspace).toContainText('Recording mode saved.');
  expect(state.settingsWrites.at(-1)).toEqual({ mode: 'MANAGED' });
  await workspace.getByLabel('Remove managed calls older than (days)').fill('2.5');
  await workspace.getByRole('button', { name: 'Save age limit' }).click();
  await expect(workspace).toContainText('Enter a whole number of days, or leave the field blank.');
  await workspace.getByLabel('Remove managed calls older than (days)').fill('');
  await workspace.getByRole('button', { name: 'Save age limit' }).click();
  await expect(workspace).toContainText('Age limit saved.');
  expect(state.settingsWrites.at(-1)).toEqual({ retention_days: null });

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
  for (const invalid of ['499', '600001', '500.5']) {
    await workspace.getByLabel('Minimum call length (ms)').fill(invalid);
    await workspace.getByRole('button', { name: 'Save transcription settings' }).click();
    await expect(workspace).toContainText('Enter a whole number from 500 to 600,000 milliseconds.');
    expect(state.settingsWrites).toEqual([]);
  }
  await workspace.getByLabel('Minimum call length (ms)').fill('750');
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
  await page.locator('.recordings-admin .ui-toggle').click();
  await expect(page.getByRole('checkbox', { name: 'Transcribe managed calls' })).toBeChecked();
  await page.getByLabel('Transcription endpoint URL').fill('http://127.0.0.1:8000/v1/audio/transcriptions');
  await page.getByLabel('Model ID').fill('test-model');
  await page.getByLabel('Minimum call length (ms)').fill('750');
  await page.getByLabel('API key').fill('replacement-key');
  await page.getByRole('button', { name: 'Save transcription settings' }).click();
  await expect(page.locator('.recordings-admin')).toContainText('Transcription settings saved.');
  expect(state.settingsWrites.at(-1)).toMatchObject({ transcription_enabled: true,
    transcription_url: 'http://127.0.0.1:8000/v1/audio/transcriptions',
    transcription_model: 'test-model', transcription_min_duration_ms: 750,
    transcription_api_key: 'replacement-key' });
  await expect(page.getByLabel('API key')).toHaveValue('');
  await expect(page.locator('.recordings-admin')).not.toContainText('replacement-key');
  await page.getByRole('button', { name: 'Save transcription settings' }).click();
  expect(state.settingsWrites.at(-1)).not.toHaveProperty('transcription_api_key');
  await page.getByRole('button', { name: 'Clear saved key' }).click();
  const clearDialog = page.getByRole('dialog', { name: 'Clear saved transcription key' });
  await expect(clearDialog).toBeVisible();
  expect(state.settingsWrites.at(-1)).not.toHaveProperty('transcription_clear_api_key');
  await clearDialog.getByRole('button', { name: 'Clear saved key' }).click();
  await expect(clearDialog).toBeHidden();
  expect(state.settingsWrites.at(-1)).toMatchObject({ transcription_clear_api_key: true });
  await expect(page.getByRole('button', { name: 'Clear saved key' })).toBeHidden();
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

for (const theme of ['light', 'dark']) {
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
  await page.locator('.recordings-call-info').click();
  let detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail.locator('.recordings-transcript')).toContainText('Engine four arriving.');
  await expect(detail.locator('.recordings-transcript')).toContainText('Requesting another unit.');
  await expect(detail.getByRole('button', { name: 'Retry transcription' })).toHaveCount(0);

  const retryState = await openRecordings(page, { transcription: { status: 'failed',
    text: null, stored_at_ms: null } });
  await page.locator('.recordings-call-info').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail.getByRole('button', { name: 'Retry transcription' })).toBeVisible();
  await detail.getByRole('button', { name: 'Retry transcription' }).click();
  await expect(detail.locator('.recordings-transcript')).toContainText('Pending transcription.');
  expect(retryState.transcription.status).toBe('PENDING');

  await openRecordings(page, { admin: false,
    transcription: { status: 'failed', text: null, stored_at_ms: null } });
  await page.locator('.recordings-call-info').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail.locator('.recordings-transcript')).toContainText('Transcription failed.');
  await expect(detail.getByRole('button', { name: 'Retry transcription' })).toHaveCount(0);

  await openRecordings(page, { transcription: { status: 'too_short', text: null, stored_at_ms: null } });
  await page.locator('.recordings-call-info').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail.locator('.recordings-transcript')).toContainText('shorter than the minimum length');

  await openRecordings(page, { transcription: { status: 'disabled', text: null, stored_at_ms: null } });
  await page.locator('.recordings-call-info').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail.locator('.recordings-transcript')).toContainText('Transcription is off.');
});

for (const theme of ['light', 'dark']) {
  test(`approved cards and playback chooser ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    const first = { ...linkedCall, transcript_excerpt: 'Engine four arriving. Requesting another unit.' };
    const second = { ...first, id: 18, source_id: 30915, source_alias: 'Engine 5',
      source_ota_alias: null, source_entity_ref: null, start_ms: first.start_ms + 60_000 };
    const state = await openRecordings(page, { theme, call: first, calls: [second, first] });
    await expect(page.locator('.recordings-shared')).toContainText('Radio system');
    await expect(page.locator('.recordings-shared')).toContainText('Talkgroup');
    await expect(page.locator('.recordings-shared')).toContainText('Site');
    await expect(page.locator('.recordings-library')).not.toContainText('Audio from');
    await expect(page.locator('.recordings-call-actions')).toHaveCount(0);
    await expect(page.locator('.recordings-transcript-preview')).toHaveCount(2);
    await expect(page.locator('.recordings-library')).toHaveScreenshot(`recordings-approved-${theme}.png`, componentScreenshot);
    const firstCard = page.locator('.recordings-call').first();
    await firstCard.locator('.recordings-card-play').click({ position: { x: 180, y: 20 } });
    let chooser = page.getByRole('dialog', { name: 'Play recording', exact: true });
    await expect(chooser).toBeVisible();
    await expect(chooser.getByRole('button', { name: /Play once/ })).toContainText('then stop');
    await expect(chooser.getByRole('button', { name: /Continue from here/ })).toContainText('newer matching calls');
    await expect(chooser).toHaveScreenshot(`recordings-playback-${theme}.png`, componentScreenshot);
    await page.keyboard.press('Escape');
    await expect(firstCard.locator('.recordings-card-play')).toBeFocused();
    await firstCard.locator('.recordings-call-select').check();
    await expect(chooser).toHaveCount(0);
    await expect(page.locator('.recordings-selected')).toContainText('1 selected');
    await page.getByRole('button', { name: /^Filters/ }).click();
    await page.getByLabel('Transcript text').fill('arriving');
    await page.getByRole('button', { name: 'Search', exact: true }).click();
    await expect.poll(() => state.requestedFilters.at(-1).transcript).toBe('arriving');
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.locator('.recordings-results-body')).toHaveScreenshot(`recordings-approved-mobile-${theme}.png`, componentScreenshot);
    await firstCard.locator('.recordings-card-play').focus();
    await page.keyboard.press('Enter');
    chooser = page.getByRole('dialog', { name: 'Play recording', exact: true });
    await expect(chooser).toBeVisible();
    await expect(chooser).toHaveScreenshot(`recordings-playback-mobile-${theme}.png`, componentScreenshot);
    await chooser.getByRole('button', { name: /Add to queue/ }).click();
    await expect(chooser).toBeHidden();
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-source', 'recordings');
    await expect(page.locator('#audio-dock').getByRole('button', { name: 'Queue 1', exact: true })).toBeVisible();
  });
}

test('playback choices retain the selected call and continuation search', async ({ page }) => {
  await page.addInitScript(() => {
    window.playedRecordingSources = [];
    HTMLMediaElement.prototype.play = function () {
      window.playedRecordingSources.push(this.src);
      return Promise.resolve();
    };
    HTMLMediaElement.prototype.pause = function () {};
  });
  const state = await openRecordings(page, { call: { ...call, transcript_excerpt: 'Engine arriving' } });
  await page.getByRole('button', { name: /^Filters/ }).click();
  await page.getByLabel('Transcript text').fill('arriving');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect.poll(() => state.requestedFilters.at(-1).transcript).toBe('arriving');
  await page.locator('.recordings-card-play').click();
  await page.getByRole('dialog', { name: 'Play recording', exact: true })
    .getByRole('button', { name: /Play once/ }).click();
  await expect(page.locator('#audio-dock')).toHaveAttribute('data-source', 'recordings');
  await expect(page.locator('#audio-dock')).toContainText('Fire Dispatch');
  await expect.poll(() => page.evaluate(() => window.playedRecordingSources.at(-1))).toMatch(/calls\/17\/audio$/);
  await page.locator('.recordings-card-play').click();
  await page.getByRole('dialog', { name: 'Play recording', exact: true })
    .getByRole('button', { name: /Continue from here/ }).click();
  await expect.poll(() => state.requestedFilters.some((filter) => filter.sort === 'asc' &&
    filter.transcript === 'arriving' && filter.from_ms === String(call.start_ms))).toBe(true);
  await page.locator('.recordings-call-info').click();
  const details = page.getByRole('dialog', { name: 'Call details' });
  await expect(details.getByRole('button', { name: /Play once|Continue from here|Add to queue/ })).toHaveCount(0);
});

test('unfiltered calls keep repeated identities in different systems separate', async ({ page }) => {
  const other = { ...linkedCall, id: 18, system_key: 'p25:00002:002',
    system_name: 'Other Metro Public Safety', source_alias: 'Other Engine',
    radio_system_entity_ref: null, target_entity_ref: null, source_entity_ref: null,
    channel_name: 'Other channel', channel_id: 'other-channel', channel_entity_ref: null };
  await openRecordings(page, { calls: [linkedCall, other] });
  await expect(page.locator('.recordings-call')).toHaveCount(2);
  await expect(page.locator('.recordings-shared')).toBeEmpty();
  await expect(page.locator('.recordings-call').first()).toContainText('Fire Dispatch');
  await expect(page.locator('.recordings-call').last()).toContainText('Other Metro Public Safety');
  await expect(page.locator('.recordings-call').last()).toContainText('Other Engine');
});

for (const theme of ['light', 'dark']) {
  test(`mixed ${theme} results stay compact with twenty-five calls per page`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    const calls = mixedRecordingCalls();
    const state = await openRecordings(page, { theme, calls, paginate: true });
    const cards = page.locator('.recordings-call');
    await expect(cards).toHaveCount(25);
    expect(state.requestedFilters.at(-1).limit).toBe('25');
    await expect(page.locator('.recordings-result-count')).toContainText('25 calls shown');
    await expect(page.locator('.recordings-shared')).toBeEmpty();
    await expect(cards.first().locator('.recordings-call-title')).toContainText(calls[0].talkgroup_alias);
    await expect(cards.first()).toContainText('TG 1201');
    await expect(cards.first()).toContainText(calls[0].source_alias);
    await expect(cards.first()).toContainText(calls[0].system_name);
    await expect(cards.first()).toContainText(calls[0].site_name);
    for (const detail of [calls[0].talkgroup_description, calls[0].talkgroup_group, calls[0].channel_name, 'MHz']) {
      await expect(cards.filter({ hasText: detail })).toHaveCount(0);
    }
    await expect(page.locator('.recordings-transcript-preview')).toHaveCount(25);
    await expectTranscriptsClearInfo(page);
    await expect(page.locator('.recordings-library')).toHaveScreenshot(`recordings-mixed-compact-${theme}.png`, componentScreenshot);

    await cards.first().locator('.recordings-call-info').click();
    const details = page.getByRole('dialog', { name: 'Call details' });
    await expect(details).toContainText(calls[0].talkgroup_description);
    await expect(details).toContainText(calls[0].talkgroup_group);
    await expect(details).toContainText(calls[0].channel_name);
    await expect(details).toContainText('851.0125 MHz');
    await expect(entityLink(details, `/?view=channel&configuration_id=${calls[0].channel_id}`).first()).toBeVisible();
    await page.keyboard.press('Escape');

    await page.setViewportSize({ width: 390, height: 844 });
    await expect(cards).toHaveCount(25);
    await expectTranscriptsClearInfo(page);
    await expect(page.locator('.recordings-results-body')).toHaveScreenshot(`recordings-mixed-compact-mobile-${theme}.png`, componentScreenshot);
    await page.getByRole('button', { name: 'Next', exact: true }).click();
    await expect(cards).toHaveCount(5);
    await expect(cards.first().locator('.recordings-call-title')).toContainText(calls[25].talkgroup_alias);
    expect(state.requestedFilters.at(-1)).toMatchObject({ limit: '25', cursor: 'fixture:25' });
    await page.getByRole('button', { name: 'Previous', exact: true }).click();
    await expect(cards).toHaveCount(25);
  });
}

test('shared system and channel use stable identities when some labels and links are missing', async ({ page }) => {
  const first = { ...linkedCall, system_name: null, radio_system_entity_ref: null,
    channel_name: null, channel_entity_ref: null };
  const second = { ...linkedCall, id: 18, source_id: 30915, source_alias: 'Engine 5',
    source_entity_ref: null, source_ota_alias: null };
  await openRecordings(page, { calls: [first, second] });
  const shared = page.locator('.recordings-shared');
  await expect(shared).toContainText('Metro Public Safety');
  await expect(shared).toContainText('North Ridge Channel');
  await expect(page.locator('.recordings-call').filter({ hasText: 'Metro Public Safety' })).toHaveCount(0);
  await expect(page.locator('.recordings-call').filter({ hasText: 'North Ridge Channel' })).toHaveCount(0);
  await expect(page.locator('.recordings-call').last().locator('.recordings-call-title')).toContainText('Engine 5');
  await shared.getByRole('button', { name: 'Show all shared call details' }).click();
  const details = page.getByRole('dialog', { name: 'Shared call details' });
  await expect(details).toContainText('Metro Public Safety');
  await expect(details).toContainText('North Ridge Channel');
});

test('phone filters retain cancelled choices and Enter applies one query with its count', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await openRecordings(page);
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  const query = page.getByRole('combobox', { name: 'Find a call' });
  await query.fill('unit arriving');
  await query.press('Escape');
  const filters = page.locator('#recordings-filters-toggle');
  await filters.click();
  const dialog = page.getByRole('dialog', { name: 'Filters', exact: true });
  const transcript = dialog.getByLabel('Transcript text', { exact: true });
  await transcript.fill('dispatch');
  await dialog.getByRole('combobox', { name: 'Date & time' }).selectOption('custom');
  await expect(dialog.getByLabel('From', { exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(filters).toBeFocused();
  await expect(query).toHaveValue('unit arriving');
  expect(state.requestedFilters).toHaveLength(1);
  await expect(filters.locator('.recordings-filter-count')).toBeHidden();
  await filters.click();
  await expect(transcript).toHaveValue('dispatch');
  await expect(dialog.getByRole('combobox', { name: 'Date & time' })).toHaveValue('custom');
  await expect(dialog.getByLabel('From', { exact: true })).toBeVisible();
  await expect(dialog.getByLabel('To', { exact: true })).toBeVisible();
  await dialog.getByRole('combobox', { name: 'Date & time' }).selectOption('24h');
  await transcript.press('Enter');
  await expect(dialog).toBeHidden();
  await expect.poll(() => state.requestedFilters.length).toBe(2);
  expect(state.requestedFilters.at(-1)).toMatchObject({ q: 'unit arriving', transcript: 'dispatch' });
  await expect(filters.locator('.recordings-filter-count')).toHaveText('1');
});

test('cursor paging keeps selection across pages and Clear selection leaves results intact', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await openRecordings(page, { calls: mixedRecordingCalls(), paginate: true });
  const pager = page.locator('.recordings-pager');
  await expect(page.locator('.recordings-call')).toHaveCount(25);
  await expect(pager.locator('.ui-browse-pager-count')).toHaveText('Page 1');
  await expect(page.locator('.recordings-result-count')).not.toContainText(/page/i);
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

test('single call hides paging while an empty cursor batch retains Next', async ({ page }) => {
  await openRecordings(page);
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  await expect(page.locator('.recordings-pager')).toBeHidden();
  await page.unroute('**/api/v1/**');
  const state = await openRecordings(page, { calls: mixedRecordingCalls(), paginate: true,
    emptyFirstBatch: true });
  await expect(page.locator('.recordings-call')).toHaveCount(0);
  await expect(page.locator('.recordings-results')).toContainText('No matches in this batch.');
  const pager = page.locator('.recordings-pager');
  await expect(pager.locator('.ui-browse-pager-count')).toHaveText('Page 1');
  await expect(pager.getByRole('button', { name: 'Previous', exact: true })).toBeDisabled();
  await pager.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.recordings-call')).toHaveCount(5);
  expect(state.requestedFilters.at(-1)).toMatchObject({ cursor: 'fixture:25' });
});

test('same display names do not share identities across different systems', async ({ page }) => {
  const first = { ...linkedCall, talkgroup_description: 'Regional response coordination',
    talkgroup_group: 'Emergency services', audio_from: { wacn: 1, system_id: 1, rfss: 1, site_id: 1 } };
  const second = { ...first, id: 18, system_key: 'p25:00002:002',
    radio_system_entity_ref: null, target_entity_ref: null, source_entity_ref: null,
    channel_id: '00000000-0000-4000-8000-000000000018', channel_entity_ref: null,
    audio_from: { wacn: 2, system_id: 2, rfss: 1, site_id: 1 } };
  await openRecordings(page, { calls: [first, second] });
  await expect(page.locator('.recordings-shared')).toBeEmpty();
  const headings = page.locator('.recordings-call-heading');
  await expect(headings).toHaveCount(2);
  for (const heading of await headings.all()) {
    await expect(heading).toContainText('Fire Dispatch');
    await expect(heading).toContainText('TG 1201');
  }
  await expect(page.locator('.recordings-call').filter({ hasText: 'Regional response coordination' })).toHaveCount(0);
  await expect(page.locator('.recordings-call').filter({ hasText: 'Emergency services' })).toHaveCount(0);
});

test('displayed radio and talkgroup IDs with different home identities stay distinct', async ({ page }) => {
  const first = { ...linkedCall, source_entity_ref: null, target_entity_ref: null,
    source_home_wacn: 1, source_home_system_id: 1, source_home_id: 30914,
    target_home_wacn: 1, target_home_system_id: 1, target_home_id: 1201,
    talkgroup_description: 'Regional response coordination', talkgroup_group: 'Emergency services' };
  const second = { ...first, id: 18, source_home_wacn: 2, source_home_system_id: 2,
    target_home_wacn: 2, target_home_system_id: 2 };
  await openRecordings(page, { calls: [first, second] });
  const shared = page.locator('.recordings-shared');
  await expect(shared).toContainText('Metro Public Safety');
  await expect(shared.locator('dt').filter({ hasText: 'Talkgroup' })).toHaveCount(0);
  await expect(shared.locator('dt').filter({ hasText: 'Source radio' })).toHaveCount(0);
  await shared.getByRole('button', { name: 'Show all shared call details' }).click();
  const details = page.getByRole('dialog', { name: 'Shared call details' });
  await expect(details).not.toContainText('Regional response coordination');
  await expect(details).not.toContainText('Emergency services');
});

test('shared target descriptions and groups are available in info without cluttering the summary', async ({ page }) => {
  const first = { ...linkedCall, talkgroup_description: 'Regional response coordination',
    talkgroup_group: 'Emergency services' };
  const second = { ...first, id: 18, source_id: 30915, source_alias: 'Engine 5', source_entity_ref: null };
  await openRecordings(page, { calls: [first, second] });
  const shared = page.locator('.recordings-shared');
  await expect(shared).toContainText('Fire Dispatch');
  await expect(shared).not.toContainText('Regional response coordination');
  await expect(shared).not.toContainText('Emergency services');
  await shared.getByRole('button', { name: 'Show all shared call details' }).click();
  const details = page.getByRole('dialog', { name: 'Shared call details' });
  await expect(details).toContainText('Regional response coordination');
  await expect(details).toContainText('Emergency services');
});

test('calls on one local date show that date once above the results', async ({ page }) => {
  const second = { ...linkedCall, id: 18, start_ms: linkedCall.start_ms + 60_000 };
  await openRecordings(page, { calls: [linkedCall, second] });
  const dateLabel = await page.evaluate((timestamp) => new Intl.DateTimeFormat(undefined,
    { year: 'numeric', month: 'short', day: 'numeric' }).format(new Date(timestamp)), linkedCall.start_ms);
  await expect(page.locator('.recordings-result-count')).toContainText(dateLabel);
  const times = page.locator('.recordings-call-time');
  await expect(times).toHaveCount(2);
  await expect(times.filter({ hasText: dateLabel })).toHaveCount(0);
  for (const time of await times.all()) {
    await expect(time.locator('strong')).toHaveText(/\d/);
    await expect(time).toContainText('00:42');
  }
});

test('calls spanning local dates retain a date on each card', async ({ page }) => {
  const previousDay = { ...linkedCall, id: 18, start_ms: linkedCall.start_ms - 86_400_000 };
  await openRecordings(page, { calls: [linkedCall, previousDay] });
  const dates = await page.evaluate((timestamps) => timestamps.map((timestamp) =>
    new Intl.DateTimeFormat(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
      .format(new Date(timestamp))), [linkedCall.start_ms, previousDay.start_ms]);
  expect(dates[0]).not.toBe(dates[1]);
  const cards = page.locator('.recordings-call');
  await expect(cards).toHaveCount(2);
  await expect(cards.first().locator('.recordings-call-time')).toContainText(dates[0]);
  await expect(cards.last().locator('.recordings-call-time')).toContainText(dates[1]);
});
