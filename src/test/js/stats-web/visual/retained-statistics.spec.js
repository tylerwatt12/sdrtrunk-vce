'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const SYSTEM = 'p25:00001:001';
const CHANNEL = '00000000-0000-0000-0000-000000000101';
const SITE = '00000000-0000-0000-0000-000000000102';
let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

function scoped(sourceKind, dataType, parts, extra = {}) {
  return { kind: 'scoped_data', source_kind: sourceKind,
    ...(sourceKind === 'radio_system' ? { source_key: SYSTEM } : {}),
    ...(sourceKind === 'saved_channel' ? { source_key: CHANNEL } : {}),
    data_type: dataType, parts, ...extra };
}

async function openStatistics(page, options = {}) {
  const requests = { results: [], sources: [], previews: [], writes: [] };
  let failedPreview = false;
  const bands = Array.from({ length: 6 }, (_, index) => ({
    label: `Band ${index}`, band: index, base_hz: 851_000_000 + index * 1_000_000,
    spacing_hz: 12_500, bandwidth_hz: 12_500, transmit_offset_hz: -45_000_000,
    timeslots: 1, state: index === 5 ? 'HISTORICAL' : 'CURRENT',
    observation_count: index + 2, last_seen_ms: 1_780_000_000_000,
    entity_ref: { kind: 'channel', key: SITE },
    target: scoped('radio_system', 'band_plans', ['current', 'summary'],
      { site_configuration_id: SITE, expected_site_key: 'opaque-site', record_key: String(index) })
  }));
  const frequencies = Array.from({ length: 30 }, (_, index) => ({
    label: `${(850 + index * 0.0125).toFixed(6)} MHz`,
    frequency_hz: 850_000_000 + index * 12_500,
    channel_key: `0-${index + 1}`, uplink_hz: 805_000_000 + index * 12_500,
    current_tags: ['VOICE'], timeslots: 2, state: 'CURRENT',
    observation_count: index + 1, voice_grant_observations: index + 3,
    last_seen_ms: 1_780_000_000_000, channel_kind: 'TRUNKED',
    entity_ref: { kind: 'channel', key: SITE },
    target: scoped('radio_system', 'frequencies', ['current', 'summary', 'buckets', 'events'],
      { site_configuration_id: SITE, expected_site_key: 'opaque-site',
        record_key: String(850_000_000 + index * 12_500) })
  }));
  const radios = Array.from({ length: 30 }, (_, index) => ({
    label: index === 29 ? 'Engine 12 (Radio 1234)' : `Radio ${3000 + index}`,
    alias_name: index === 29 ? 'Engine 12' : null,
    native_id: index === 29 ? 1234 : 3000 + index,
    alias_group: index === 29 ? 'Fire' : null,
    alias_list_name: 'Metro P25 aliases', alias_list_id: 7,
    logical_call_count: index + 10, last_seen_ms: 1_780_000_000_000,
    entity_ref: { kind: 'radio', radio_system_key: SYSTEM,
      identity_key: `v1-r-00001-001-${index === 29 ? 1234 : 3000 + index}` },
    target: scoped('radio_system', 'radios', ['summary', 'buckets', 'events'],
      { record_key: `v1-r-00001-001-${index === 29 ? 1234 : 3000 + index}` })
  }));
  const aliases = Array.from({ length: 30 }, (_, index) => ({
    label: index === 29 ? 'Dispatch Console' : `Alias ${index + 1}`,
    alias_id: index + 101, alias_list_id: 7, alias_list_name: 'Metro P25 aliases',
    alias_group: 'Dispatch', matcher_type: 'RADIO_ID',
    logical_call_count: index + 1, signaling_observation_count: index,
    last_seen_ms: 1_780_000_000_000,
    target: scoped('alias_activity', 'alias_activity', ['summary'],
      { record_key: String(index + 101) })
  }));
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const pathname = url.pathname;
    const wrap = (data, meta) => ({ data, ...(meta ? { meta } : {}) });
    const paged = (rows) => {
      const offset = Number(url.searchParams.get('offset') || 0);
      const q = String(url.searchParams.get('q') || '').toLowerCase();
      const matched = q ? rows.filter((row) =>
        `${row.label} ${row.alias_name || ''} ${row.alias_list_name || ''} ${row.frequency_hz || ''}`
          .toLowerCase().includes(q)) : rows;
      return wrap(matched.slice(offset, offset + 25), { limit: 25, offset,
        has_more: offset + 25 < matched.length, next_offset: offset + 25,
        total_count: matched.length });
    };
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: wrap({ configured: true, authenticated: true,
        username: 'admin', tier: 'admin', primary: true,
        capabilities: { 'admin-settings': true, 'receiver-health': true, radio: true,
          'admin-aliases': true } }) });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences: { ...defaultPreferences,
        appearance: { theme: options.theme || 'light' } } } });
    } else if (pathname.endsWith('/retained-statistics/sources')) {
      const kind = url.searchParams.get('kind');
      requests.sources.push({ kind, q: url.searchParams.get('q') });
      const rows = kind === 'alias_activity' ? [{ source_kind: kind,
        source_key: 'alias_activity', label: 'Alias Activity',
        target: scoped('alias_activity', 'alias_activity', ['summary']) }] :
        kind === 'saved_channel' ? [{ source_kind: kind, source_key: CHANNEL,
          label: 'Downtown repeater', radio_system_name: 'County Fire', site_name: 'Downtown',
          protocol: 'DMR', channel_kind: 'CONVENTIONAL', primary_frequency_hz: 155_115_000,
          alias_list_id: 9, alias_list_name: 'Metro DMR aliases',
          entity_ref: { kind: 'channel', key: CHANNEL } }] :
          [{ source_kind: kind, source_key: SYSTEM, label: 'Metro P25', protocol: 'P25',
            alias_list_id: 7, alias_list_name: 'Metro P25 aliases',
            entity_ref: { kind: 'radio_system', key: SYSTEM } }];
      await route.fulfill({ json: wrap(rows, { limit: 50, offset: 0, has_more: false }) });
    } else if (pathname.endsWith('/retained-statistics/sites')) {
      await route.fulfill({ json: wrap([{ configuration_id: SITE, label: 'Downtown site',
        site_key: 'opaque-site', alias_list_id: 7, alias_list_name: 'Metro P25 aliases',
        entity_ref: { kind: 'channel', key: SITE } }],
      { limit: 50, offset: 0, has_more: false }) });
    } else if (pathname.endsWith('/retained-statistics/results')) {
      const dataType = url.searchParams.get('data_type');
      const kind = url.searchParams.get('source_kind');
      requests.results.push({ kind, dataType, q: url.searchParams.get('q'),
        offset: Number(url.searchParams.get('offset') || 0),
        sourceKey: url.searchParams.get('source_key'),
        site: url.searchParams.get('site_configuration_id') });
      const rows = dataType === 'band_plans' ? bands :
        dataType === 'frequencies' && kind === 'radio_system' ? frequencies :
          dataType === 'frequencies' ? [{ label: '155.115000 MHz',
            observation_count: 412, logical_call_count: 412,
            timeslots: [1, 2],
            last_seen_ms: 1_780_000_000_000,
            channel_kind: 'CONVENTIONAL', entity_ref: { kind: 'channel', key: CHANNEL },
            target: scoped('saved_channel', 'frequencies', ['summary', 'buckets', 'events'],
              { record_key: '155115000' }) }] :
            dataType === 'radios' ? radios :
              dataType === 'alias_activity' ? aliases :
                [{ label: dataType === 'all' ? 'All retained data' :
                  dataType === 'hourly_history' ? 'Hourly history' : 'Saved activity',
                detail: kind === 'radio_system' ? 'Metro P25' : 'Downtown repeater',
                target: scoped(kind, dataType, dataType === 'all' ?
                  ['current', 'summary', 'buckets', 'events'] : ['buckets'],
                url.searchParams.get('site_configuration_id') ?
                  { site_configuration_id: SITE, expected_site_key: 'opaque-site' } : {}) }];
      await route.fulfill({ json: paged(rows) });
    } else if (pathname.endsWith('/retained-statistics/preview')) {
      const target = JSON.parse(route.request().postData() || '{}').target;
      requests.previews.push(target);
      if (options.previewTooLarge) {
        await route.fulfill({ json: wrap({ outcome: 'too_large',
          counts_by_part: { summary: 100_001 }, rows_total: 100_001,
          effects: ['More linked rows may be removed.'] }) });
      } else if (options.previewFailsOnce && !failedPreview) {
        failedPreview = true;
        await route.fulfill({ status: 500, json: { error: { status: 500,
          message: 'Preview unavailable' } } });
      } else await route.fulfill({ json: wrap({ outcome: 'found',
        counts_by_part: Object.fromEntries(target.parts.map((part) => [part, 2])),
        rows_total: target.parts.length * 2,
        effects: ['Linked history may also be removed by this selection.'] }) });
    } else if (pathname.endsWith('/retained-statistics/deletions') &&
      route.request().method() === 'POST') {
      requests.writes.push(JSON.parse(route.request().postData() || '{}'));
      await route.fulfill({ status: 202, json: wrap({ job_id: 'job-1', state: 'running' }) });
    } else if (pathname.endsWith('/retained-statistics/deletions/job-1')) {
      await route.fulfill({ json: wrap({ job_id: 'job-1', state: 'succeeded',
        rows_deleted: options.jobTooLarge ? 0 : 2,
        outcome: options.jobTooLarge ? 'too_large' : 'deleted' }) });
    } else await route.fulfill({ status: 404,
      json: { error: { status: 404, message: 'Unavailable' } } });
  });
  await page.goto('/app.html?view=admin&tab=retained-statistics');
  return requests;
}

test('system site drilldown shows band plan details and preview counts before removal',
  async ({ page }, testInfo) => {
    const requests = await openStatistics(page);
    const workspace = page.locator('.retained-statistics-page');
    await workspace.getByRole('combobox', { name: 'Radio system' }).selectOption(SYSTEM);
    await workspace.getByRole('button', { name: 'Band plans', exact: true }).click();
    await expect(workspace.getByText('Choose a site.')).toBeVisible();
    expect(requests.results).toHaveLength(0);
    await workspace.getByRole('combobox', { name: 'Site' }).selectOption(SITE);
    await expect(workspace.locator('.retained-statistics-result-count')).toHaveText('6 results');
    const results = workspace.locator('.retained-statistics-table');
    await expect(results.getByRole('columnheader', { name: 'Base MHz' })).toBeVisible();
    await expect(results.getByRole('columnheader', { name: 'Space kHz' })).toBeVisible();
    await expect(results.getByRole('columnheader', { name: 'Observations' })).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath('retained-statistics-desktop-light.png'),
      fullPage: true });
    await results.getByRole('button', { name: 'Delete statistics for Band 0' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('4 directly matched records')).toBeVisible();
    await expect(dialog.getByText('Linked history may also be removed')).toBeVisible();
    await expect(dialog.getByText('Saved aliases and alias lists remain.')).toBeVisible();
    await dialog.getByRole('button', { name: 'Delete statistics', exact: true }).click();
    await expect(workspace.getByText('Statistics deleted.')).toBeVisible();
    expect(requests.writes[0].target).toMatchObject({ kind: 'scoped_data',
      data_type: 'band_plans', source_key: SYSTEM, site_configuration_id: SITE,
      record_key: '0', parts: ['current', 'summary'] });
  });

test('frequency results have visible delete buttons, preview before deletion, and search past page one',
  async ({ page }, testInfo) => {
    const requests = await openStatistics(page);
    const workspace = page.locator('.retained-statistics-page');
    await workspace.getByRole('combobox', { name: 'Radio system' }).selectOption(SYSTEM);
    await workspace.getByRole('button', { name: 'Frequencies', exact: true }).click();
    await workspace.getByRole('combobox', { name: 'Site' }).selectOption(SITE);
    const results = workspace.locator('.retained-statistics-table');
    await expect(results.getByRole('columnheader', { name: 'Decoder obs.' })).toBeVisible();
    await expect(results.getByRole('columnheader', { name: 'Voice grants' })).toBeVisible();
    await expect(results.getByRole('columnheader', { name: 'Last observed' })).toBeVisible();
    await expect(workspace.locator('.retained-statistics-result-count')).toHaveText('30 results');
    const firstDelete = results.getByRole('button', { name: 'Delete statistics for 850.000000 MHz' });
    await expect(firstDelete).toBeVisible();
    await expect(firstDelete).toHaveText('Delete…');
    await expect(firstDelete).toHaveClass(/\bui-button\b/);
    await expect(firstDelete).toHaveClass(/\bui-button-danger\b/);
    expect(await firstDelete.evaluate((button) => {
      const text = document.createRange();
      text.selectNodeContents(button);
      return text.getClientRects().length;
    })).toBe(1);
    await page.screenshot({ path: testInfo.outputPath('retained-statistics-frequencies-desktop-light.png'),
      fullPage: true });
    await firstDelete.click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('8 directly matched records')).toBeVisible();
    expect(requests.previews).toHaveLength(1);
    expect(requests.writes).toHaveLength(0);
    await dialog.getByRole('button', { name: 'Delete statistics', exact: true }).click();
    await expect(workspace.getByText('Statistics deleted.')).toBeVisible();
    expect(requests.writes).toHaveLength(1);
    expect(requests.writes[0].target).toMatchObject({ data_type: 'frequencies',
      site_configuration_id: SITE, record_key: '850000000' });
    await workspace.getByRole('button', { name: 'Next' }).click();
    await expect(results.getByRole('button', { name: /Delete statistics for/ })).toHaveCount(5);
    await workspace.getByRole('searchbox', { name: 'Search results' }).fill('850350000');
    await expect(workspace.locator('.retained-statistics-result-count')).toHaveText('1 result');
    expect(requests.results).toContainEqual(expect.objectContaining({ dataType: 'frequencies',
      q: '850350000', offset: 0, site: SITE }));
  });

test('conventional channel selection shows frequency context and separate calls and last heard',
  async ({ page }) => {
    await openStatistics(page);
    const workspace = page.locator('.retained-statistics-page');
    await workspace.getByRole('button', { name: 'Conventional channel', exact: true }).click();
    const source = workspace.getByRole('combobox', { name: 'Conventional channel' });
    await expect(source.locator('option').nth(1)).toContainText('155.11500 MHz');
    await source.selectOption(CHANNEL);
    await workspace.getByRole('button', { name: 'Frequencies', exact: true }).click();
    await expect(workspace.getByRole('combobox', { name: 'Site' })).toBeHidden();
    const results = workspace.locator('.retained-statistics-table');
    await expect(results.getByRole('columnheader', { name: 'Slots' })).toBeVisible();
    await expect(results.getByText('1, 2')).toBeVisible();
    await expect(results.getByRole('columnheader', { name: 'Calls' })).toBeVisible();
    await expect(results.getByRole('columnheader', { name: 'Last heard' })).toBeVisible();
    await expect(results.getByRole('link', { name: '155.115000 MHz' })).toBeVisible();
    await expect(results.getByRole('button', { name: 'Delete statistics for 155.115000 MHz' }))
      .toHaveText('Delete…');
  });

test('radio names and IDs link to details, with server search before pagination', async ({ page }) => {
  const requests = await openStatistics(page);
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('combobox', { name: 'Radio system' }).selectOption(SYSTEM);
  await workspace.getByRole('button', { name: 'Radio IDs' }).click();
  await expect(workspace.locator('.retained-statistics-result-count')).toHaveText('30 results');
  await workspace.getByRole('searchbox', { name: 'Search results' }).fill('Engine 12');
  const results = workspace.locator('.retained-statistics-table');
  await expect(results.getByRole('link', { name: 'Engine 12' })).toBeVisible();
  await expect(results.getByText('Radio 1234')).toBeVisible();
  await expect(results.getByRole('columnheader', { name: 'Calls' })).toBeVisible();
  await expect(results.getByRole('columnheader', { name: 'Last heard' })).toBeVisible();
  await expect(results.getByRole('button', { name: 'Delete statistics for Engine 12 (Radio 1234)' }))
    .toHaveText('Delete…');
  expect(requests.results).toContainEqual(expect.objectContaining({ dataType: 'radios',
    q: 'Engine 12', offset: 0 }));
});

test('Alias Activity supports individual reset and an explicit global reset', async ({ page }) => {
  const requests = await openStatistics(page);
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('button', { name: 'Alias Activity', exact: true }).click();
  const reset = workspace.getByRole('button', { name: 'Reset all Alias Activity…', exact: true });
  await expect(reset).toBeEnabled();
  await expect(workspace.locator('.retained-statistics-result-count')).toHaveText('30 results');
  await workspace.getByRole('searchbox', { name: 'Search results' }).fill('Dispatch Console');
  const results = workspace.locator('.retained-statistics-table');
  const alias = results.getByRole('link', { name: 'Dispatch Console' });
  await expect(alias).toBeVisible();
  const href = new URL(await alias.getAttribute('href'), 'http://127.0.0.1:4173');
  expect(href.searchParams.get('view')).toBe('aliases');
  expect(href.searchParams.get('alias')).toBe('130');
  const resetAlias = results.getByRole('button', { name: 'Reset activity for Dispatch Console' });
  await expect(resetAlias).toHaveText('Reset…');
  await resetAlias.click();
  await expect(page.getByRole('dialog').getByText('2 directly matched records')).toBeVisible();
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click();
  await reset.click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText('Saved aliases and alias lists remain.')).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Reset activity', exact: true })).toBeDisabled();
  await dialog.getByRole('textbox', { name: 'Type RESET ALIAS ACTIVITY to confirm' })
    .fill('RESET ALIAS ACTIVITY');
  await dialog.getByRole('button', { name: 'Reset activity', exact: true }).click();
  await expect(workspace.getByText('Activity reset.')).toBeVisible();
  expect(requests.writes[0].target).toEqual(scoped('alias_activity', 'alias_activity', ['summary']));
  expect(requests.results).toContainEqual(expect.objectContaining({ kind: 'alias_activity',
    sourceKey: null }));
});

test('preview failure blocks removal until retry succeeds', async ({ page }) => {
  await openStatistics(page, { previewFailsOnce: true });
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('combobox', { name: 'Radio system' }).selectOption(SYSTEM);
  await workspace.getByRole('button', { name: 'All retained data in scope' }).click();
  const deleteAll = workspace.getByRole('button', { name: 'Delete statistics for All retained data' });
  await expect(deleteAll).toHaveText('Delete…');
  await expect(deleteAll).toHaveClass(/\bui-button-danger\b/);
  await deleteAll.click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText('Affected records could not be checked.')).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Delete statistics', exact: true })).toBeDisabled();
  await dialog.getByRole('button', { name: 'Retry' }).click();
  await expect(dialog.getByText('8 directly matched records')).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Delete statistics', exact: true })).toBeDisabled();
  await dialog.getByRole('textbox', { name: 'Type Metro P25 to confirm' }).fill('Metro P25');
  await expect(dialog.getByRole('button', { name: 'Delete statistics', exact: true })).toBeEnabled();
});

test('a large preview requires a narrower selection', async ({ page }) => {
  const requests = await openStatistics(page, { previewTooLarge: true });
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('combobox', { name: 'Radio system' }).selectOption(SYSTEM);
  await workspace.getByRole('button', { name: 'All retained data in scope' }).click();
  await workspace.getByRole('button', { name: 'Delete statistics for All retained data' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText('Too many matching records.')).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Delete statistics', exact: true })).toBeDisabled();
  expect(requests.writes).toHaveLength(0);
});

test('a size change after preview keeps the selected scope', async ({ page }) => {
  await openStatistics(page, { jobTooLarge: true });
  const workspace = page.locator('.retained-statistics-page');
  const source = workspace.getByRole('combobox', { name: 'Radio system' });
  await source.selectOption(SYSTEM);
  await workspace.getByRole('button', { name: 'All retained data in scope' }).click();
  await workspace.getByRole('button', { name: 'Delete statistics for All retained data' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText('8 directly matched records')).toBeVisible();
  await dialog.getByRole('textbox', { name: 'Type Metro P25 to confirm' }).fill('Metro P25');
  await dialog.getByRole('button', { name: 'Delete statistics', exact: true }).click();
  await expect(workspace.getByText('Too many records. Narrow the scope')).toBeVisible();
  await expect(source).toHaveValue(SYSTEM);
});

test('the grouped workspace fits a phone in dark mode', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openStatistics(page, { theme: 'dark' });
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('combobox', { name: 'Radio system' }).selectOption(SYSTEM);
  await workspace.getByRole('button', { name: 'Frequencies', exact: true }).click();
  await workspace.getByRole('combobox', { name: 'Site' }).selectOption(SITE);
  const firstDelete = workspace.getByRole('button', { name: 'Delete statistics for 850.000000 MHz' });
  await expect(firstDelete).toBeVisible();
  const bounds = await workspace.boundingBox();
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(390);
  await page.screenshot({ path: testInfo.outputPath('retained-statistics-mobile-dark.png'),
    fullPage: true });
  await firstDelete.scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath('retained-statistics-mobile-dark-action.png') });
});
