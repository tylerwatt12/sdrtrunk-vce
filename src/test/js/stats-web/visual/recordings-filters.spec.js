'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
  if (process.env.RECORDING_FILTER_EVIDENCE) fs.mkdirSync(process.env.RECORDING_FILTER_EVIDENCE, { recursive: true });
});

const systemKey = 'p25:00001:001';
const channelId = '00000000-0000-4000-8000-000000000017';
const call = {
  id: 17, start_ms: Date.parse('2026-09-28T10:08:42Z'), duration_ms: 42_000,
  system_key: systemKey, system_name: 'Metro Public Safety',
  channel_id: channelId, channel_name: 'North Ridge Channel',
  talkgroup_id: 1201, talkgroup_alias: 'Fire Dispatch',
  source_id: 30914, source_alias: 'Engine 4', source_ota_alias: 'ENG 4',
  site_name: 'North Ridge', call_type: 'GROUP', protocol: 'APCO25_PHASE2', voice_type: 'CLEAR',
  audio_from: { wacn: 1, system_id: 1, rfss: 1, site_id: 2 },
  radio_system_entity_ref: { kind: 'radio_system', key: systemKey },
  channel_entity_ref: { kind: 'channel', key: channelId },
  target_entity_ref: { kind: 'talkgroup', radio_system_key: systemKey,
    identity_key: 'v1-g-00001-001-1201' },
  source_entity_ref: { kind: 'radio', radio_system_key: systemKey,
    identity_key: 'v1-r-00001-001-30914' }
};

async function openRecordings(page, options = {}) {
  const state = { requests: [], unexpected: [], errorNext: false,
    calls: options.calls || [options.call || call] };
  const preferences = structuredClone(defaults);
  preferences.appearance.theme = options.theme || 'light';
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const key = url.pathname;
    if (key === '/api/v1/auth/session') {
      await route.fulfill({ json: { data: { configured: true, authenticated: true,
        username: 'admin', tier: 'admin', primary: true,
        capabilities: { recordings: true, 'admin-recordings': true, 'admin-settings': true,
          dashboard: true, radio: options.radio !== false } } } });
    } else if (key === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } });
    } else if (key === '/api/v1/recordings/status') {
      await route.fulfill({ json: { data: { mode: 'MANAGED', available: true, has_calls: true } } });
    } else if (key === '/api/v1/recordings/calls') {
      const filters = Object.fromEntries(url.searchParams);
      state.requests.push(filters);
      if (state.errorNext) {
        state.errorNext = false;
        await route.fulfill({ status: 503, json: { error: { message: 'Calls could not be loaded. Try again.' } } });
        return;
      }
      const calls = state.calls.filter((row) => !filters.system_key || row.system_key === filters.system_key);
      const offset = Number((filters.cursor || 'fixture:0').split(':')[1]);
      const limit = Number(filters.limit);
      await route.fulfill({ json: { data: { calls: calls.slice(offset, offset + limit),
        next_cursor: offset + limit < calls.length ? `fixture:${offset + limit}` : null, total: null } } });
    } else if (/^\/api\/v1\/recordings\/calls\/\d+$/.test(key)) {
      const selected = state.calls.find((row) => row.id === Number(key.split('/').at(-1)));
      await route.fulfill({ json: { data: { call: selected } } });
    } else if (key === '/api/v1/recordings/entities') {
      const kind = url.searchParams.get('kind');
      const rows = kind === 'system' ? [{ kind, id: systemKey, label: 'Metro Public Safety', system_key: systemKey,
        call_count: state.calls.length, selection: { system_key: systemKey } }] : [];
      await route.fulfill({ json: { data: { rows, has_more: false } } });
    } else if (key === '/api/v1/recordings/identities') {
      const kind = url.searchParams.get('kind');
      const q = (url.searchParams.get('q') || '').toLowerCase();
      const rows = state.calls.map(row => {
        const id = kind === 'radio' ? row.source_id : row.talkgroup_id;
        const identity_key = (kind === 'radio' ? row.source_entity_ref : row.target_entity_ref)?.identity_key;
        const label = kind === 'radio' ? row.source_ota_alias || row.source_alias || `Radio ${id}` : row.talkgroup_alias || `Talkgroup ${id}`;
        return { kind, id, label, system_key: row.system_key, system_name: row.system_name,
          ...(identity_key ? { identity_key } : {}), call_count: 1,
          selection: { id, system_key: row.system_key, ...(identity_key ? { identity_key } : {}) } };
      }).filter(row => row.id && (!q || [row.label, row.id].join(' ').toLowerCase().includes(q)));
      await route.fulfill({ json: { data: { rows, has_more: false } } });
    } else if (key === '/api/v1/recordings/suggestions') {
      await route.fulfill({ json: { data: { suggestions: options.suggestions || [] } } });
    } else if (key === '/api/v1/alerts/topics') {
      await route.fulfill({ json: { data: { enabled: false, topics: [] } } });
    } else if (key === '/api/v1/status') {
      await route.fulfill({ json: { data: {} } });
    } else {
      state.unexpected.push(key);
      await route.fulfill({ status: 404, json: { error: { message: 'Unexpected request' } } });
    }
  });
  await page.goto('/app.html?view=recordings');
  await expect(page.locator('.recordings-call')).toHaveCount(Math.min(25, state.calls.length));
  return state;
}

function filterLink(host, name) {
  return host.getByRole('link', { name, exact: false }).first();
}

async function openRecordingFilters(page, topic = 'more') {
  const title = { more: 'More filters', radios: 'Source radios', targets: 'Talkgroups & channels', systems: 'System' }[topic];
  const dialog = page.getByRole('dialog', { name: title, exact: true });
  if (!await dialog.isVisible()) await page.locator(`#recordings-open-${topic}`).click();
  await expect(dialog).toBeVisible();
  return dialog;
}

async function openCallDetails(page, card = page.locator('.recordings-call').first()) {
  await card.locator('.recordings-call-details').click();
  await expect(page.getByRole('dialog', { name: 'Call details', exact: true })).toBeVisible();
}

async function expectRecordingQuery(page, state, filters) {
  await expect.poll(() => state.requests.at(-1)).toMatchObject(filters);
  await expect(page.getByRole('heading', { name: 'Recordings', exact: true })).toBeVisible();
  expect(new URL(page.url()).searchParams.get('view')).toBe('recordings');
  expect(state.unexpected).toEqual([]);
}

async function captureEvidence(page, name) {
  if (!process.env.RECORDING_FILTER_EVIDENCE) return;
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(process.env.RECORDING_FILTER_EVIDENCE, name) });
}

test('talkgroup clicks preserve applied dates, full text, duration, source and sort while ignoring drafts', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const calls = Array.from({ length: 30 }, (_, index) => ({ ...call, id: 17 + index,
    talkgroup_id: 1201 + index, talkgroup_alias: `Fire Dispatch ${index}`, source_id: 30914 + index,
    source_entity_ref: { ...call.source_entity_ref, identity_key: `v1-r-00001-001-${30914 + index}` },
    target_entity_ref: { ...call.target_entity_ref, identity_key: `v1-g-00001-001-${1201 + index}` } }));
  const state = await openRecordings(page, { calls });
  await page.getByRole('combobox', { name: 'Time range', exact: true }).selectOption('custom');
  await page.getByLabel('From', { exact: true }).fill('2026-09-28T00:00');
  await page.getByLabel('To', { exact: true }).fill('2026-09-29T00:00');
  await page.getByLabel('Full text', { exact: true }).fill('arriving');
  await page.getByRole('combobox', { name: 'Full text range', exact: true }).selectOption('time');
  const radios = await openRecordingFilters(page, 'radios');
  await radios.getByLabel('Find radios', { exact: true }).fill('30914');
  await expect(radios.locator('.recordings-picker-choice')).toHaveCount(1);
  await radios.locator('.recordings-picker-choice').getByRole('checkbox').check();
  await radios.getByRole('button', { name: 'Use selection', exact: true }).click();
  const more = await openRecordingFilters(page);
  await more.getByLabel('Minimum duration (seconds)', { exact: true }).fill('1');
  await more.getByRole('combobox', { name: 'Voice', exact: true }).selectOption('CLEAR');
  await more.getByRole('combobox', { name: 'Protocol', exact: true }).selectOption('APCO25_PHASE2');
  await more.getByRole('button', { name: 'Use filters', exact: true }).click();
  await page.getByRole('button', { name: 'Search recordings', exact: true }).click();
  await expect.poll(() => state.requests.at(-1).transcript).toBe('arriving');
  await expect(page.locator('.recordings-selected-filters')).toContainText('arriving');
  await page.getByRole('combobox', { name: 'Sort calls' }).selectOption('asc');
  await expect.poll(() => state.requests.at(-1).sort).toBe('asc');
  const applied = { ...state.requests.at(-1) };
  const next = page.locator('.recordings-pager').last().getByRole('button', { name: 'Next', exact: true });
  await next.click();
  await expect(page.locator('.recordings-call')).toHaveCount(5);
  await page.getByRole('checkbox', { name: 'Select all calls on this page', exact: true }).check();
  await page.getByLabel('Full text', { exact: true }).fill('unsubmitted transcript');
  await filterLink(page.locator('.recordings-call').first(), calls[25].talkgroup_alias).click();
  await expectRecordingQuery(page, state, { ...applied,
    talkgroup_identity_key: `v1-g-00001-001-${calls[25].talkgroup_id}`, system_key: systemKey });
  expect(state.requests.at(-1)).not.toHaveProperty('cursor');
  await expect(page.locator('.recordings-call-select:checked')).toHaveCount(0);
  await expect(page.locator('.recordings-selected')).toBeHidden();
  await expect(page.locator('.recordings-pager').last().getByRole('button', { name: 'Previous', exact: true })).toBeDisabled();
  await expect(page.getByLabel('From', { exact: true })).toHaveValue('2026-09-28T00:00');
  await expect(page.getByLabel('To', { exact: true })).toHaveValue('2026-09-29T00:00');
  await expect(page.getByLabel('Full text', { exact: true })).toHaveValue('arriving');
  await captureEvidence(page, 'query-preserved-desktop-light.png');
});

test('source and destination radio links filter Recordings with pointer and keyboard', async ({ page }) => {
  const direct = { ...call, call_type: 'DIRECT', talkgroup_id: null, talkgroup_alias: null,
    destination_radio_id: 42137, destination_radio_alias: 'Unit 12',
    target_entity_ref: { kind: 'radio', radio_system_key: systemKey,
      identity_key: 'v1-r-00001-001-42137' } };
  const state = await openRecordings(page, { call: direct });
  await filterLink(page.locator('.recordings-call'), 'ENG 4').click();
  await expectRecordingQuery(page, state, { radio_identity_key: 'v1-r-00001-001-30914', system_key: systemKey });
  expect(state.requests.at(-1)).not.toHaveProperty('radio_id');
  const target = filterLink(page.locator('.recordings-call'), 'Direct to Unit 12');
  await target.focus();
  await target.press('Enter');
  await expectRecordingQuery(page, state, { radio_identity_key: 'v1-r-00001-001-42137', system_key: systemKey });
  expect(state.requests.at(-1)).not.toHaveProperty('radio_id');
});

test('card system and detail channel/site links apply the current recording query', async ({ page }) => {
  const namedCall = { ...call, alias_list_id: 7, alias_list_name: 'Metro Alias List',
    alias_list_entity_ref: { kind: 'alias_list', key: '7' } };
  const state = await openRecordings(page, { calls: [namedCall, { ...namedCall, id: 18, source_id: 30915,
    source_ota_alias: 'ENG 5', source_entity_ref: { ...call.source_entity_ref,
      identity_key: 'v1-r-00001-001-30915' } }] });
  await filterLink(page.locator('.recordings-call').first(), 'Metro Public Safety').click();
  await expectRecordingQuery(page, state, { system_key: systemKey });
  await openCallDetails(page);
  const detail = page.getByRole('dialog', { name: 'Call details' });
  await filterLink(detail, 'North Ridge Channel').click();
  await expect(detail).toBeHidden();
  await expectRecordingQuery(page, state, { system_key: systemKey, channel_id: channelId });
  expect(state.requests).toHaveLength(3);
  await openCallDetails(page);
  await page.getByRole('dialog', { name: 'Call details', exact: true }).getByRole('link', { name: 'North Ridge', exact: true }).click();
  await expectRecordingQuery(page, state, { system_key: systemKey, channel_id: channelId,
    wacn: '1', sysid: '1', rfss: '1', site_id: '2' });
  await openCallDetails(page);
  await filterLink(page.getByRole('dialog', { name: 'Call details' }), 'Metro Alias List').click();
  await expectRecordingQuery(page, state, { system_key: systemKey, channel_id: channelId,
    wacn: '1', sysid: '1', rfss: '1', site_id: '2', alias_list_id: '7' });
  expect(state.requests).toHaveLength(5);
});

test('same numeric IDs in different systems remain correctly scoped', async ({ page }) => {
  const otherSystem = 'p25:00002:002';
  const other = { ...call, id: 18, system_key: otherSystem, system_name: 'County Public Safety',
    talkgroup_alias: 'County Fire Dispatch', source_ota_alias: 'COUNTY ENG 4',
    radio_system_entity_ref: { kind: 'radio_system', key: otherSystem },
    source_entity_ref: { ...call.source_entity_ref, radio_system_key: otherSystem,
      identity_key: 'v1-r-00002-002-30914' },
    target_entity_ref: { ...call.target_entity_ref, radio_system_key: otherSystem,
      identity_key: 'v1-g-00002-002-1201' } };
  const state = await openRecordings(page, { calls: [call, other] });
  await filterLink(page.locator('.recordings-call').last(), 'County Fire Dispatch').click();
  await expectRecordingQuery(page, state, { talkgroup_identity_key: 'v1-g-00002-002-1201', system_key: otherSystem });
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  await expect(page.locator('.recordings-call')).toContainText('County Fire Dispatch');
  await expect(page.locator('.recordings-call')).not.toContainText('Metro Public Safety');
});

test('failed clicked filters remain available for Search retry without leaving Recordings', async ({ page }) => {
  const state = await openRecordings(page);
  state.errorNext = true;
  await filterLink(page.locator('.recordings-call'), 'Fire Dispatch').click();
  await expect(page.locator('.recordings-result-status')).toContainText('Calls could not be loaded. Try again.');
  await expectRecordingQuery(page, state, { talkgroup_identity_key: 'v1-g-00001-001-1201', system_key: systemKey });
  const query = { ...state.requests.at(-1) };
  await page.getByRole('button', { name: 'Search recordings', exact: true }).click();
  await expect.poll(() => state.requests.length).toBe(3);
  const { from_ms, to_ms, ...stableQuery } = query;
  expect(state.requests.at(-1)).toMatchObject(stableQuery);
  await expect(page.locator('.recordings-result-status')).toBeEmpty();
});

test('a Recordings-only account can filter safely without radio-page access', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await openRecordings(page, { radio: false, theme: 'dark' });
  const target = filterLink(page.locator('.recordings-call'), 'Fire Dispatch');
  await expect(target).toBeVisible();
  await target.click();
  await expectRecordingQuery(page, state, { talkgroup_identity_key: 'v1-g-00001-001-1201', system_key: systemKey });
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
  await captureEvidence(page, 'talkgroup-filter-phone-dark.png');
});

test('foreign P25 source uses its permanent identity and clears a prior radio range', async ({ page }) => {
  const foreign = { ...call, source_id: 401, source_alias: null, source_ota_alias: 'Foreign subscriber',
    source_home_wacn: 2, source_home_system_id: 2, source_home_id: 777,
    source_entity_ref: { kind: 'radio', radio_system_key: systemKey,
      identity_key: 'v1-r-00002-002-777' } };
  const state = await openRecordings(page, { call: foreign,
    suggestions: [{ kind: 'radio_range', id: '400-500', min_id: 400, max_id: 500,
      label: 'Field radios', system_key: systemKey }] });
  const more = await openRecordingFilters(page);
  await more.getByText('Names, IDs & ranges', { exact: true }).click();
  await more.getByLabel('First radio ID', { exact: true }).fill('400');
  await more.getByLabel('Last radio ID', { exact: true }).fill('500');
  await more.getByRole('button', { name: 'Use filters', exact: true }).click();
  await page.getByRole('button', { name: 'Search recordings', exact: true }).click();
  await expect.poll(() => state.requests.at(-1)).toMatchObject({ radio_min: '400', radio_max: '500' });
  await filterLink(page.locator('.recordings-call'), 'Foreign subscriber').click();
  await expectRecordingQuery(page, state, { radio_identity_key: 'v1-r-00002-002-777', system_key: systemKey });
  for (const key of ['radio_id', 'radio_min', 'radio_max']) expect(state.requests.at(-1)).not.toHaveProperty(key);
  await expect(page.locator('.recordings-selected-filters')).not.toContainText('Field radios');
});

for (const protocol of ['DMR', 'NXDN']) {
  test(`${protocol} source filters the recorded radio ID in its own system`, async ({ page }) => {
    const protocolSystem = `${protocol.toLowerCase()}:7`;
    const recorded = { ...call, protocol, system_key: protocolSystem,
      radio_system_entity_ref: { kind: 'radio_system', key: protocolSystem },
      target_entity_ref: { kind: 'talkgroup', radio_system_key: protocolSystem, identity_key: 'v1-g-x-x-1201' },
      source_entity_ref: { kind: 'radio', radio_system_key: protocolSystem, identity_key: 'v1-r-x-x-30914' } };
    const state = await openRecordings(page, { call: recorded });
    await filterLink(page.locator('.recordings-call'), 'ENG 4').click();
    await expectRecordingQuery(page, state, { system_key: protocolSystem, radio_role: 'source' });
    expect(JSON.parse(state.requests.at(-1).radios)).toEqual([{ id: 30914, system_key: protocolSystem, protocol }]);
    expect(state.requests.at(-1)).not.toHaveProperty('radio_id');
    expect(state.requests.at(-1)).not.toHaveProperty('radio_identity_key');
  });
}

test('opening a result filter URL retains the scoped query and sort on a fresh page', async ({ page }) => {
  const state = await openRecordings(page);
  await page.getByLabel('Full text', { exact: true }).fill('dispatch');
  await page.getByRole('combobox', { name: 'Full text range', exact: true }).selectOption('time');
  await page.getByRole('button', { name: 'Search recordings', exact: true }).click();
  await expect.poll(() => state.requests.at(-1).transcript).toBe('dispatch');
  await expect(page.locator('.recordings-selected-filters')).toContainText('dispatch');
  await page.getByRole('combobox', { name: 'Sort calls' }).selectOption('asc');
  await expect.poll(() => state.requests.at(-1).sort).toBe('asc');
  const applied = { ...state.requests.at(-1) };
  const target = filterLink(page.locator('.recordings-call'), 'Fire Dispatch');
  const link = new URL(await target.getAttribute('href'), page.url());
  expect(link.searchParams.get('recording_filters')).toBe('1');
  link.pathname = '/app.html';
  await page.goto(link.href);
  await expectRecordingQuery(page, state, { ...applied, system_key: systemKey, talkgroup_identity_key: 'v1-g-00001-001-1201' });
  await expect(page.getByRole('combobox', { name: 'Sort calls' })).toHaveValue('asc');
  await expect(page.getByLabel('Full text', { exact: true })).toHaveValue('dispatch');
  await expect(page.getByLabel('From', { exact: true })).toHaveValue(new Date(Number(applied.from_ms)).toISOString().slice(0, 16));
  await expect(page.getByLabel('To', { exact: true })).toHaveValue(new Date(Number(applied.to_ms)).toISOString().slice(0, 16));
});

test('patch member detail links use the member identity, not the parent patch or source', async ({ page }) => {
  const patch = { ...call, call_type: 'PATCH', talkgroup_alias: 'Dispatch Patch',
    target_entity_ref: { kind: 'patch_group', radio_system_key: systemKey,
      identity_key: 'v1-p-00001-001-1201' },
    patch_members: [{ kind: 'talkgroup', id: 1202, alias: 'Fireground',
      entity_ref: { kind: 'talkgroup', radio_system_key: systemKey,
        identity_key: 'v1-g-00001-001-1202' } }] };
  const state = await openRecordings(page, { call: patch });
  await openCallDetails(page);
  const detail = page.getByRole('dialog', { name: 'Call details' });
  await detail.getByRole('tab', { name: 'Identities', exact: true }).click();
  const members = detail.getByRole('tabpanel', { name: 'Identities', exact: true }).locator('section').filter({
    has: page.getByRole('heading', { name: 'Patch member identities', exact: true }) });
  await filterLink(members, 'Fireground').click();
  await expect(detail).toBeHidden();
  await expectRecordingQuery(page, state, { talkgroup_identity_key: 'v1-g-00001-001-1202', system_key: systemKey });

  const radioPatch = { ...patch, protocol: 'DMR', system_key: 'dmr:7',
    radio_system_entity_ref: { kind: 'radio_system', key: 'dmr:7' },
    patch_members: [{ kind: 'radio', id: 401, alias: 'Patch unit',
      entity_ref: { kind: 'radio', radio_system_key: 'dmr:7', identity_key: 'v1-r-x-x-401' } }] };
  await openRecordings(page, { call: radioPatch });
  const request = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return url.pathname === '/api/v1/recordings/calls' && JSON.parse(url.searchParams.get('radios') || '[]')[0]?.id === 401;
  });
  await openCallDetails(page);
  const radioDetail = page.getByRole('dialog', { name: 'Call details' });
  await radioDetail.getByRole('tab', { name: 'Identities', exact: true }).click();
  const radioMembers = radioDetail.getByRole('tabpanel', { name: 'Identities', exact: true }).locator('section').filter({
    has: page.getByRole('heading', { name: 'Patch member identities', exact: true }) });
  await filterLink(radioMembers, 'Patch unit').click();
  await request;
  await expect(radioDetail).toBeHidden();
});

test('unknown references and home-system names stay readable without incorrect navigation', async ({ page }) => {
  const unsupported = { ...call,
    source_entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: 'invalid' },
    target_entity_ref: { kind: 'unknown', radio_system_key: systemKey, identity_key: 'unknown' },
    source_home_system_name: 'Foreign Home Network',
    source_home_system: { system_name: 'Foreign Home Network',
      entity_ref: { kind: 'radio_system', key: 'p25:00002:002' } } };
  const state = await openRecordings(page, { call: unsupported });
  const card = page.locator('.recordings-call');
  await expect(card).toContainText('Fire Dispatch');
  await expect(card).toContainText('ENG 4');
  await expect(card.getByRole('link', { name: 'Fire Dispatch', exact: true })).toHaveCount(0);
  await expect(card.getByRole('link', { name: 'ENG 4', exact: true })).toHaveCount(0);
  await openCallDetails(page, card);
  const detail = page.getByRole('dialog', { name: 'Call details' });
  await detail.getByRole('tab', { name: 'Recording', exact: true }).click();
  const identifiers = detail.getByRole('tabpanel', { name: 'Recording', exact: true });
  await expect(identifiers).toContainText('Foreign Home Network');
  await expect(identifiers.getByRole('link', { name: 'Foreign Home Network', exact: true })).toHaveCount(0);
  expect(state.requests).toHaveLength(1);
  expect(state.unexpected).toEqual([]);
});

test('foreign talkgroup labels filter the permanent identity within the receiving system', async ({ page }) => {
  const foreign = { ...call, target_home_wacn: 2, target_home_system_id: 2, target_home_id: 777,
    target_entity_ref: { kind: 'talkgroup', radio_system_key: systemKey,
      identity_key: 'v1-g-00002-002-777' } };
  const state = await openRecordings(page, { call: foreign });
  const card = page.locator('.recordings-call');
  await expect(card).toContainText('Fire Dispatch');
  const target = card.getByRole('link', { name: 'Fire Dispatch', exact: true });
  await expect(target).toBeVisible();
  const destination = new URL(await target.getAttribute('href'), page.url());
  expect(destination.searchParams.get('talkgroup_identity_key')).toBe('v1-g-00002-002-777');
  expect(destination.searchParams.has('talkgroup_id')).toBe(false);
  await target.click();
  await expectRecordingQuery(page, state, { system_key: systemKey,
    talkgroup_identity_key: 'v1-g-00002-002-777' });
  await expect(card).toContainText('Fire Dispatch');
});
