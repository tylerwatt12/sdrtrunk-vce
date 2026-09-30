'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;
const SYSTEM_A = 'p25:00001:001';
const SYSTEM_B = 'p25:00002:002';
const CHANNEL_A = '00000000-0000-0000-0000-000000000101';
const CHANNEL_B = '00000000-0000-0000-0000-000000000102';
const ALIAS_LIST_NAME = 'Metro P25 aliases';

const entityRefs = {
  system: { kind: 'radio_system', key: SYSTEM_A },
  channel: { kind: 'channel', key: CHANNEL_A },
  radio: { kind: 'radio', radio_system_key: SYSTEM_A, identity_key: 'v1-r-00001-001-1234' },
  talkgroup: { kind: 'talkgroup', radio_system_key: SYSTEM_A,
    identity_key: 'v1-g-00001-001-30' }
};

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

async function openStatistics(page, options = {}) {
  const requests = { frequencyResults: 0, resultTypes: [], resultQueries: [], writes: [] };
  const frequencies = Array.from({ length: 30 }, (_, index) => ({
    label: `${(850 + index * 0.0125).toFixed(4)} MHz`, detail: `Channel ${index + 1}`,
    observation_count: index + 1, last_seen_ms: 1_780_000_000_000,
    entity_ref: entityRefs.channel,
    channel_kind: options.conventionalFrequency ? 'CONVENTIONAL' : 'TRUNKED',
    target: { kind: 'frequency', site_configuration_id: CHANNEL_A,
      expected_site_key: 'opaque-site-a', frequency_hz: 850_000_000 + index * 12_500 }
  }));
  const radio = { label: 'Engine 12 (Radio 1234)', detail: 'Metro P25 · Fire response',
    native_id: 1234, alias_name: 'Engine 12', alias_description: 'Engine company',
    alias_group: 'Fire', alias_list_id: 7, alias_list_name: ALIAS_LIST_NAME,
    logical_call_count: 42, last_seen_ms: 1_780_000_000_000,
    entity_ref: options.invalidIdentityRef ? { kind: 'radio', key: 'bad' } : entityRefs.radio,
    target: { kind: 'radio', radio_system_key: SYSTEM_A,
      identity_key: 'v1-r-00001-001-1234' } };
  const talkgroup = { label: 'Fire Dispatch (Talkgroup 30)', detail: 'Metro P25',
    native_id: 30, alias_name: 'Fire Dispatch', alias_description: 'Dispatch traffic',
    alias_group: 'Fire', alias_list_id: 7, alias_list_name: ALIAS_LIST_NAME,
    logical_call_count: 213, last_seen_ms: 1_780_000_000_000,
    entity_ref: entityRefs.talkgroup,
    target: { kind: 'talkgroup', radio_system_key: SYSTEM_A,
      identity_key: 'v1-g-00001-001-30' } };
  const radioRows = options.pagedRadios ? [
    ...Array.from({ length: 29 }, (_, index) => ({
      label: `Radio ID ${3000 + index}`, native_id: 3000 + index,
      logical_call_count: index + 1, last_seen_ms: 1_780_000_000_000,
      target: { kind: 'radio', radio_system_key: SYSTEM_A,
        identity_key: `v1-r-00001-001-${3000 + index}` }
    })), radio
  ] : options.richRows ? [radio,
    { ...radio, label: 'Dispatch Chief (Radio 2234)', native_id: 2234,
      alias_name: 'Dispatch Chief', alias_group: 'Command', logical_call_count: 8,
      entity_ref: { kind: 'radio', radio_system_key: SYSTEM_A,
        identity_key: 'v1-r-00001-001-2234' },
      target: { kind: 'radio', radio_system_key: SYSTEM_A,
        identity_key: 'v1-r-00001-001-2234' } },
    { label: 'Radio ID 5678', detail: 'Metro P25 · No alias', native_id: 5678,
      alias_list_id: 7, alias_list_name: ALIAS_LIST_NAME,
      logical_call_count: 3, last_seen_ms: 1_780_000_000_000,
      target: { kind: 'radio', radio_system_key: SYSTEM_A,
        identity_key: 'v1-r-00001-001-5678' } }] : [radio];
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const pathname = url.pathname;
    const data = (value, meta = undefined) => ({ data: value, ...(meta ? { meta } : {}) });
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: data({ configured: true, authenticated: true,
        username: 'admin', tier: 'admin', primary: true,
        capabilities: { 'admin-settings': true, 'receiver-health': true, credits: true,
          radio: options.radioAllowed !== false, 'admin-aliases': options.aliasesAllowed !== false } }) });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences: { ...defaultPreferences,
        appearance: { theme: options.theme || 'light' } } } });
    } else if (pathname.endsWith('/retained-statistics/sources')) {
      const rows = url.searchParams.get('kind') === 'saved_channel' ? [
        { source_kind: 'saved_channel', source_key: CHANNEL_A, label: 'Downtown channel',
          site_name: 'Downtown site', alias_list_id: 9, alias_list_name: 'Metro DMR aliases',
          entity_ref: entityRefs.channel, channel_kind: 'CONVENTIONAL', protocol: 'DMR' }
      ] : [
        { source_kind: 'radio_system', source_key: SYSTEM_A, label: 'Metro P25',
          alias_list_id: 7, alias_list_name: ALIAS_LIST_NAME, entity_ref: entityRefs.system },
        { source_kind: 'radio_system', source_key: SYSTEM_B,
          label: options.duplicateNames ? 'Metro P25' : 'County P25',
          ...(options.duplicateNames ? { alias_list_id: 7, alias_list_name: ALIAS_LIST_NAME } : {}),
          entity_ref: { kind: 'radio_system', key: SYSTEM_B } }
      ];
      await route.fulfill({ json: data(rows, { limit: 50, offset: 0, has_more: false }) });
    } else if (pathname.endsWith('/retained-statistics/sites')) {
      await route.fulfill({ json: data([
        { configuration_id: CHANNEL_A, label: 'Downtown site', alias_list_id: 7,
          alias_list_name: ALIAS_LIST_NAME,
          entity_ref: entityRefs.channel, site_key: 'opaque-site-a' },
        { configuration_id: CHANNEL_B, label: 'Uptown site',
          entity_ref: { kind: 'channel', key: CHANNEL_B }, site_key: 'opaque-site-b' }
      ], { limit: 50, offset: 0, has_more: false }) });
    } else if (pathname.endsWith('/retained-statistics/results')) {
      const kind = url.searchParams.get('data_type');
      requests.resultTypes.push(kind);
      requests.resultQueries.push({ kind, q: url.searchParams.get('q'),
        offset: Number(url.searchParams.get('offset') || 0) });
      if (kind === 'frequencies') {
        requests.frequencyResults += 1;
        const offset = Number(url.searchParams.get('offset') || 0);
        await route.fulfill({ json: data(frequencies.slice(offset, offset + 25),
          { limit: 25, offset, has_more: offset + 25 < frequencies.length,
            next_offset: offset + 25, total_count: frequencies.length }) });
      } else {
        const conventional = url.searchParams.get('source_kind') === 'saved_channel';
        const samples = {
          radios: { label: 'Radio ID 1234', detail: '850.0000 MHz · Slot 1',
            alias_list_id: 9, alias_list_name: 'Metro DMR aliases',
            entity_ref: entityRefs.channel,
            target: { kind: 'conventional_radio', configuration_id: CHANNEL_A,
              frequency_hz: 850_000_000, timeslot: 1, native_id: 1234 } },
          talkgroups: conventional ? { label: 'Talkgroup 30',
            entity_ref: entityRefs.channel,
            target: { kind: 'conventional_talkgroup', configuration_id: CHANNEL_A,
              frequency_hz: 850_000_000, timeslot: 1, native_id: 30 } } : talkgroup,
          sites: { label: 'Downtown site', alias_list_id: 7, alias_list_name: ALIAS_LIST_NAME,
            entity_ref: entityRefs.channel,
            target: { kind: 'saved_site', configuration_id: CHANNEL_A,
              expected_site_key: 'opaque-site-a', include_channel_history: false } },
          channels: { label: 'Downtown channel', alias_list_id: 7,
            alias_list_name: ALIAS_LIST_NAME, entity_ref: entityRefs.channel,
            target: { kind: 'channel', configuration_id: CHANNEL_A } },
          systems: { label: 'Metro P25', alias_list_id: 7,
            alias_list_name: ALIAS_LIST_NAME, entity_ref: entityRefs.system,
            target: { kind: 'system', radio_system_key: SYSTEM_A, include_channel_history: false } }
        };
        let rows = kind === 'radios' && !conventional ? radioRows :
          samples[kind] ? [samples[kind]] : [];
        const q = url.searchParams.get('q');
        if (q) rows = rows.filter((row) => `${row.label} ${row.alias_name || ''}`
          .toLowerCase().includes(q.toLowerCase()));
        const offset = Number(url.searchParams.get('offset') || 0);
        await route.fulfill({ json: data(rows.slice(offset, offset + 25), { limit: 25, offset,
          has_more: offset + 25 < rows.length, next_offset: offset + 25, total_count: rows.length }) });
      }
    } else if (pathname.endsWith('/retained-statistics/deletions') && route.request().method() === 'POST') {
      requests.writes.push(JSON.parse(route.request().postData() || '{}'));
      await route.fulfill({ status: 202,
        json: data({ job_id: 'job-1', state: 'running' }) });
    } else if (pathname.endsWith('/retained-statistics/deletions/job-1')) {
      await route.fulfill({ json: data({ job_id: 'job-1', state: 'succeeded',
        rows_deleted: options.outcome === 'stale_site' ? 0 : 2,
        outcome: options.outcome || 'deleted' }) });
    } else {
      await route.fulfill({ status: 404,
        json: { error: { status: 404, message: 'Unavailable' } } });
    }
  });
  await page.goto('/app.html?view=admin&tab=retained-statistics');
  return requests;
}

test('frequency results require a site and removal preserves its identity', async ({ page }) => {
  const requests = await openStatistics(page);
  const workspace = page.locator('.retained-statistics-page');
  await expect(workspace).toBeVisible();
  const source = workspace.getByRole('combobox', { name: 'Source', exact: true });
  await expect(source.locator('option')).toHaveCount(3);
  await expect(source.locator('option').nth(1)).toHaveText(`Metro P25 · ${ALIAS_LIST_NAME}`);
  await source.selectOption(SYSTEM_A);
  await workspace.getByRole('button', { name: 'Frequencies' }).click();
  await expect(workspace.getByText('Choose a site to see frequencies.')).toBeVisible();
  expect(requests.frequencyResults).toBe(0);
  const site = workspace.getByRole('combobox', { name: 'Site', exact: true });
  await expect(site.locator('option')).toHaveCount(3);
  await site.selectOption(CHANNEL_A);
  const context = workspace.locator('.retained-statistics-context');
  await expect(context).toContainText(`Metro P25 · Downtown site · ${ALIAS_LIST_NAME} · Frequencies`);
  const sourceLink = new URL(await context.getByRole('link', { name: 'Metro P25', exact: true })
    .getAttribute('href'), 'http://127.0.0.1:4173');
  expect(sourceLink.searchParams.get('view')).toBe('radio-system');
  expect(sourceLink.searchParams.get('radio_system_key')).toBe(SYSTEM_A);
  const siteLink = new URL(await context.getByRole('link', { name: 'Downtown site', exact: true })
    .getAttribute('href'), 'http://127.0.0.1:4173');
  expect(siteLink.searchParams.get('view')).toBe('channel');
  expect(siteLink.searchParams.get('configuration_id')).toBe(CHANNEL_A);
  const listLink = new URL(await context.getByRole('link', { name: ALIAS_LIST_NAME, exact: true })
    .getAttribute('href'), 'http://127.0.0.1:4173');
  expect(listLink.searchParams.get('view')).toBe('aliases');
  expect(listLink.searchParams.get('list')).toBe('7');
  await expect(workspace.locator('.retained-statistics-result-count')).toHaveText('30 results');
  const frequencyLink = workspace.locator('.retained-statistics-table').getByRole('link',
    { name: '850.0000 MHz', exact: true });
  const frequencyHref = new URL(await frequencyLink.getAttribute('href'), 'http://127.0.0.1:4173');
  expect(frequencyHref.searchParams.get('view')).toBe('channel');
  expect(frequencyHref.searchParams.get('configuration_id')).toBe(CHANNEL_A);
  expect(frequencyHref.searchParams.get('tab')).toBe('frequencies');
  await expect(workspace.getByRole('button', { name: 'Review removal of', exact: false })).toHaveCount(25);
  await workspace.getByRole('button', { name: 'Next' }).click();
  await expect(workspace.getByRole('button', { name: 'Review removal of', exact: false })).toHaveCount(5);
  await workspace.getByRole('button', { name: 'Review removal of', exact: false }).first().click();
  const dialog = page.getByRole('dialog', { name: /Remove .* MHz\?/ });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText('Receiving continues; removed rows may return.')).toBeVisible();
  await dialog.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(workspace.getByText('Removal complete.')).toBeVisible();
  expect(requests.writes).toHaveLength(1);
  expect(requests.writes[0].request_id).toMatch(/^[0-9a-f-]{36}$/i);
  expect(requests.writes[0].target).toMatchObject({ kind: 'frequency',
    site_configuration_id: CHANNEL_A, expected_site_key: 'opaque-site-a' });
});

test('conventional frequencies open the channel info page', async ({ page }) => {
  await openStatistics(page, { conventionalFrequency: true });
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('button', { name: 'Saved channel', exact: true }).click();
  await workspace.getByRole('combobox', { name: 'Source', exact: true }).selectOption(CHANNEL_A);
  await workspace.getByRole('button', { name: 'Frequencies' }).click();
  await workspace.getByRole('combobox', { name: 'Site', exact: true }).selectOption(CHANNEL_A);
  const frequency = workspace.locator('.retained-statistics-table').getByRole('link',
    { name: '850.0000 MHz', exact: true });
  await expect(frequency).toBeVisible();
  const href = new URL(await frequency.getAttribute('href'), 'http://127.0.0.1:4173');
  expect(href.searchParams.get('view')).toBe('channel');
  expect(href.searchParams.get('configuration_id')).toBe(CHANNEL_A);
  expect(href.searchParams.has('tab')).toBe(false);
});

test('saved-channel selection keeps site drilldown and fits a phone', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openStatistics(page);
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('button', { name: 'Saved channel', exact: true }).click();
  const source = workspace.getByRole('combobox', { name: 'Source', exact: true });
  await expect(source.locator('option').nth(1)).toHaveText(
    'Downtown channel · Downtown site · Metro DMR aliases');
  await source.selectOption(CHANNEL_A);
  await expect(workspace.getByRole('button', { name: 'Radio IDs' })).toBeEnabled();
  await expect(workspace.getByRole('button', { name: 'Radio systems' })).toBeDisabled();
  await workspace.getByRole('button', { name: 'Radio IDs' }).click();
  await expect(workspace.getByText('850.0000 MHz · Slot 1')).toBeVisible();
  const conventionalRadio = workspace.locator('.retained-statistics-table').getByRole('link',
    { name: 'Radio ID 1234', exact: true });
  const radioHref = new URL(await conventionalRadio.getAttribute('href'), 'http://127.0.0.1:4173');
  expect(radioHref.searchParams.get('view')).toBe('channel');
  expect(radioHref.searchParams.get('configuration_id')).toBe(CHANNEL_A);
  expect(radioHref.searchParams.get('tab')).toBe('radios');
  await workspace.getByRole('button', { name: 'Frequencies' }).click();
  await expect(workspace.getByRole('combobox', { name: 'Site', exact: true })).toBeVisible();
  const bounds = await workspace.boundingBox();
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(390);
});

test('each system data type opens results and broad scopes show their impact', async ({ page }) => {
  const requests = await openStatistics(page);
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('combobox', { name: 'Source', exact: true }).selectOption(SYSTEM_A);
  const drilldowns = [
    { type: 'Radio IDs', label: 'Engine 12', view: 'radio' },
    { type: 'Talkgroups', label: 'Fire Dispatch', view: 'group-identity' },
    { type: 'Sites', label: 'Downtown site', view: 'channel' },
    { type: 'Saved channels', label: 'Downtown channel', view: 'channel' },
    { type: 'Radio systems', label: 'Metro P25', view: 'radio-system' }
  ];
  for (const { type, label, view } of drilldowns) {
    await workspace.getByRole('button', { name: type, exact: true }).click();
    const results = workspace.locator('.retained-statistics-table');
    await expect(results.getByRole('button', { name: 'Review removal of', exact: false })).toHaveCount(1);
    const item = results.getByRole('link', { name: label, exact: true });
    const href = new URL(await item.getAttribute('href'), 'http://127.0.0.1:4173');
    expect(href.searchParams.get('view')).toBe(view);
    await expect(results.getByRole('link', { name: ALIAS_LIST_NAME, exact: true })).toBeVisible();
  }
  expect(new Set(requests.resultTypes)).toEqual(new Set(['radios', 'talkgroups', 'sites', 'channels', 'systems']));
  await workspace.getByRole('button', { name: 'Sites', exact: true }).click();
  await workspace.getByRole('button', { name: 'Review removal of', exact: false }).click();
  const siteDialog = page.getByRole('dialog');
  await expect(siteDialog.getByRole('button', { name: 'Site inventory', exact: true })).toBeVisible();
  await expect(siteDialog.getByText('including frequencies')).toBeVisible();
  await siteDialog.getByRole('button', { name: 'Cancel' }).click();
  await workspace.getByRole('button', { name: 'Radio systems', exact: true }).click();
  await workspace.getByRole('button', { name: 'Review removal of', exact: false }).click();
  const systemDialog = page.getByRole('dialog');
  await expect(systemDialog.getByRole('button', { name: 'Remove', exact: true })).toBeDisabled();
  await systemDialog.getByRole('textbox', { name: 'Type Metro P25 to confirm' }).fill('Metro P25');
  await expect(systemDialog.getByRole('button', { name: 'Remove', exact: true })).toBeEnabled();
  await systemDialog.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(workspace.getByRole('combobox', { name: 'Source', exact: true })).toHaveValue('');
  await expect(workspace.getByText('Choose a source and data type.')).toBeVisible();
});

test('a changed site asks for a fresh site choice after the job finishes', async ({ page }) => {
  await openStatistics(page, { outcome: 'stale_site' });
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('combobox', { name: 'Source', exact: true }).selectOption(SYSTEM_A);
  await workspace.getByRole('button', { name: 'Frequencies' }).click();
  await workspace.getByRole('combobox', { name: 'Site', exact: true }).selectOption(CHANNEL_A);
  await workspace.getByRole('button', { name: 'Review removal of', exact: false }).first().click();
  await page.getByRole('dialog').getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(workspace.getByText('Site changed. Choose it again.')).toBeVisible();
  await expect(workspace.getByRole('combobox', { name: 'Site', exact: true })).toHaveValue('');
  await expect(workspace.getByText('Choose a site to see frequencies.')).toBeVisible();
});

test('named identities show exact IDs, scoped links, aliases, and preserve removal targets',
  async ({ page }) => {
    const requests = await openStatistics(page);
    const workspace = page.locator('.retained-statistics-page');
    await workspace.getByRole('combobox', { name: 'Source', exact: true }).selectOption(SYSTEM_A);
    await workspace.getByRole('button', { name: 'Talkgroups', exact: true }).click();
    const results = workspace.locator('.retained-statistics-table');
    await expect(results.getByText('Talkgroup 30')).toBeVisible();
    await expect(results.getByText('Fire', { exact: true })).toBeVisible();
    await expect(results.getByText('213', { exact: true })).toBeVisible();
    const identity = results.getByRole('link', { name: 'Fire Dispatch', exact: true });
    await expect(identity).toBeVisible();
    let target = new URL(await identity.getAttribute('href'), 'http://127.0.0.1:4173');
    expect(target.searchParams.get('view')).toBe('group-identity');
    expect(target.searchParams.get('radio_system_key')).toBe(SYSTEM_A);
    expect(target.searchParams.get('identity_key')).toBe('v1-g-00001-001-30');
    const list = results.getByRole('link', { name: ALIAS_LIST_NAME, exact: true });
    await expect(list).toBeVisible();
    target = new URL(await list.getAttribute('href'), 'http://127.0.0.1:4173');
    expect(target.searchParams.get('view')).toBe('aliases');
    expect(target.searchParams.get('list')).toBe('7');
    const context = workspace.locator('.retained-statistics-context');
    const source = context.getByRole('link', { name: 'Metro P25', exact: true });
    await expect(source).toBeVisible();
    const sourceHref = new URL(await source.getAttribute('href'), 'http://127.0.0.1:4173');
    expect(sourceHref.searchParams.get('view')).toBe('radio-system');
    expect(sourceHref.searchParams.get('radio_system_key')).toBe(SYSTEM_A);
    const contextList = context.getByRole('link', { name: ALIAS_LIST_NAME, exact: true });
    const contextListHref = new URL(await contextList.getAttribute('href'), 'http://127.0.0.1:4173');
    expect(contextListHref.searchParams.get('view')).toBe('aliases');
    expect(contextListHref.searchParams.get('list')).toBe('7');

    await results.getByRole('button', { name: 'Review removal of Fire Dispatch (Talkgroup 30)' }).click();
    const dialog = page.getByRole('dialog', { name: 'Remove Fire Dispatch (Talkgroup 30)?' });
    await dialog.getByRole('button', { name: 'Remove', exact: true }).click();
    await expect(workspace.getByText('Removal complete.')).toBeVisible();
    expect(requests.writes).toHaveLength(1);
    expect(requests.writes[0].target).toEqual({ kind: 'talkgroup', radio_system_key: SYSTEM_A,
      identity_key: 'v1-g-00001-001-30' });
  });

test('result search finds a named radio beyond page one and starts from offset zero', async ({ page }) => {
  const requests = await openStatistics(page, { pagedRadios: true });
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('combobox', { name: 'Source', exact: true }).selectOption(SYSTEM_A);
  await workspace.getByRole('button', { name: 'Radio IDs' }).click();
  await expect(workspace.locator('.retained-statistics-result-count')).toHaveText('30 results');
  await workspace.getByRole('button', { name: 'Next' }).click();
  await expect(workspace.getByRole('button', { name: 'Review removal of', exact: false })).toHaveCount(5);
  await workspace.getByRole('searchbox', { name: 'Search results' }).fill('Engine 12');
  const results = workspace.locator('.retained-statistics-table');
  await expect(results.getByRole('link', { name: 'Engine 12', exact: true })).toBeVisible();
  await expect(results.getByText('Radio 1234')).toBeVisible();
  await expect(workspace.locator('.retained-statistics-result-count')).toHaveText('1 result');
  expect(requests.resultQueries).toContainEqual({ kind: 'radios', q: 'Engine 12', offset: 0 });
  const radio = new URL(await results.getByRole('link', { name: 'Engine 12' }).getAttribute('href'),
    'http://127.0.0.1:4173');
  expect(radio.searchParams.get('view')).toBe('radio');
  expect(radio.searchParams.get('identity_key')).toBe('v1-r-00001-001-1234');
});

test('identity and alias list links fall back to text without permission or a valid reference',
  async ({ page }) => {
    await openStatistics(page, { radioAllowed: false, aliasesAllowed: false });
    const workspace = page.locator('.retained-statistics-page');
    await workspace.getByRole('combobox', { name: 'Source', exact: true }).selectOption(SYSTEM_A);
    await workspace.getByRole('button', { name: 'Radio IDs' }).click();
    const results = workspace.locator('.retained-statistics-table');
    await expect(results.getByText('Engine 12', { exact: true })).toBeVisible();
    await expect(results.getByText(ALIAS_LIST_NAME, { exact: true })).toBeVisible();
    await expect(results.getByRole('link')).toHaveCount(0);
    await expect(workspace.locator('.retained-statistics-context').getByRole('link')).toHaveCount(0);

    await page.reload();
    await openStatistics(page, { invalidIdentityRef: true });
    const refreshed = page.locator('.retained-statistics-page');
    await refreshed.getByRole('combobox', { name: 'Source', exact: true }).selectOption(SYSTEM_A);
    await refreshed.getByRole('button', { name: 'Radio IDs' }).click();
    const table = refreshed.locator('.retained-statistics-table');
    await expect(table.getByText('Engine 12', { exact: true })).toBeVisible();
    await expect(table.getByRole('link', { name: 'Engine 12' })).toHaveCount(0);
    await expect(table.getByRole('link', { name: ALIAS_LIST_NAME })).toBeVisible();
  });

test('duplicate source names reveal their stable system keys', async ({ page }) => {
  await openStatistics(page, { duplicateNames: true });
  const workspace = page.locator('.retained-statistics-page');
  const source = workspace.getByRole('combobox', { name: 'Source', exact: true });
  await expect(source.locator('option').nth(1)).toHaveText(`Metro P25 · ${ALIAS_LIST_NAME} · ${SYSTEM_A}`);
  await expect(source.locator('option').nth(2)).toHaveText(`Metro P25 · ${ALIAS_LIST_NAME} · ${SYSTEM_B}`);
  await source.selectOption(SYSTEM_A);
  await workspace.getByRole('button', { name: 'Talkgroups' }).click();
  await expect(workspace.locator('.retained-statistics-context')).toContainText(SYSTEM_A);
});

test('alias-rich retained tables render on desktop and mobile', async ({ page }, testInfo) => {
  await openStatistics(page, { richRows: true });
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('combobox', { name: 'Source', exact: true }).selectOption(SYSTEM_A);
  await workspace.getByRole('button', { name: 'Radio IDs' }).click();
  await expect(workspace.getByRole('link', { name: 'Engine 12', exact: true })).toBeVisible();
  await expect(workspace.getByText('Radio ID 5678')).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('retained-statistics-desktop-light.png'),
    fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.reload();
  await openStatistics(page, { richRows: true, theme: 'dark' });
  const mobile = page.locator('.retained-statistics-page');
  await mobile.getByRole('combobox', { name: 'Source', exact: true }).selectOption(SYSTEM_A);
  await mobile.getByRole('button', { name: 'Radio IDs' }).click();
  await expect(mobile.getByRole('link', { name: 'Engine 12', exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('retained-statistics-mobile-dark.png'),
    fullPage: true });
  const bounds = await mobile.boundingBox();
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(390);
});
