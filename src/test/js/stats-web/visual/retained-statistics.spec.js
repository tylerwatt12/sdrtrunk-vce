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
  const requests = { results: [], sources: [], previews: [], writes: [], actions: [], polls: [] };
  let currentJob = options.largeJob ? { job_id: 'job-1', state: 'running',
    rows_deleted: 80_000, rows_total: 213_000, cutoff_ms: 1_780_000_000_000,
    batches_completed: 8, can_resume: false } : null;
  let jobMissing = false;
  let firstPollRelease;
  let actionFailed = false;
  requests.setJob = (updates) => { currentJob = { ...currentJob, ...updates }; };
  requests.forgetJob = () => { jobMissing = true; };
  requests.releaseFirstPoll = () => firstPollRelease?.();
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
                  dataType === 'hourly_history' ? 'Hourly history' :
                    dataType === 'control_quality' ? 'Quality history' : 'Saved activity',
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
        counts_by_part: Object.fromEntries(target.parts.map((part) => [part, options.largeJob ? 213_000 : 2])),
        rows_total: options.largeJob ? 213_000 : target.parts.length * 2,
        effects: ['Linked history may also be removed by this selection.'] }) });
    } else if (pathname.endsWith('/retained-statistics/deletions') &&
      route.request().method() === 'POST') {
      const body = JSON.parse(route.request().postData() || '{}');
      requests.writes.push(body);
      if (currentJob) currentJob.target = body.target;
      await route.fulfill({ status: 202, json: wrap(currentJob ? { ...currentJob, rows_deleted: 0 } :
        { job_id: 'job-1', state: 'running' }) });
    } else if (/\/retained-statistics\/deletions\/job-1\/(cancel|resume)$/.test(pathname)) {
      const action = pathname.split('/').pop();
      requests.actions.push(action);
      if (options.actionFailsOnce && !actionFailed) {
        actionFailed = true;
        return route.fulfill({ status: 500,
          json: { error: { status: 500, message: 'Cleanup control unavailable' } } });
      }
      currentJob = { ...currentJob, state: action === 'cancel' ?
        (options.cancelQueues ? 'cancelling' : 'cancelled') : 'running',
        can_resume: action === 'cancel' && !options.cancelQueues };
      await route.fulfill({ json: wrap(currentJob) });
    } else if (pathname.endsWith('/retained-statistics/deletions/job-1')) {
      requests.polls.push(pathname);
      if (options.holdFirstPoll && requests.polls.length === 1) {
        const snapshot = { ...currentJob };
        await new Promise((resolve) => { firstPollRelease = resolve; });
        return route.fulfill({ json: wrap(snapshot) });
      }
      if (jobMissing) return route.fulfill({ status: 404,
        json: { error: { status: 404, code: 'job_not_found', message: 'Job no longer available' } } });
      if (currentJob) return route.fulfill({ json: wrap(currentJob) });
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

for (const theme of ['light', 'dark']) for (const mobile of [false, true]) {
  test(`cleanup preview cards fit the ${theme} ${mobile ? 'mobile' : 'desktop'} dialog`, async ({ page }) => {
    const width = mobile ? 390 : 1440;
    await page.setViewportSize({ width, height: mobile ? 844 : 1000 });
    const requests = await openStatistics(page, { theme });
    const workspace = page.locator('.retained-statistics-page');
    await workspace.getByRole('combobox', { name: 'Radio system' }).selectOption(SYSTEM);
    await workspace.getByRole('button', { name: 'All retained data in scope' }).click();
    await workspace.getByRole('button', { name: 'Delete statistics for All retained data' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('8 directly matched records')).toBeVisible();
    const cards = dialog.locator('.retained-statistics-preview-counts .ui-metric');
    await expect(cards).toHaveCount(4);
    await expect(cards.locator('strong')).toHaveText(['2', '2', '2', '2']);
    const dialogBounds = await dialog.boundingBox();
    const bounds = await cards.evaluateAll((items) => items.map((item) => {
      const rect = item.getBoundingClientRect();
      return { left: rect.left, right: rect.right };
    }));
    bounds.forEach((card) => {
      expect(card.left).toBeGreaterThan(dialogBounds.x);
      expect(card.right).toBeLessThan(dialogBounds.x + dialogBounds.width);
    });
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    expect(requests.writes).toHaveLength(0);
    await expect(dialog).toHaveScreenshot(`retained-statistics-preview-${theme}-${mobile ? 'mobile' : 'desktop'}.png`);
  });
}

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

test('an older receiver explains that large cleanup needs an update', async ({ page }) => {
  const requests = await openStatistics(page, { previewTooLarge: true });
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('combobox', { name: 'Radio system' }).selectOption(SYSTEM);
  await workspace.getByRole('button', { name: 'All retained data in scope' }).click();
  await workspace.getByRole('button', { name: 'Delete statistics for All retained data' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText('This receiver needs an update')).toBeVisible();
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
  await expect(workspace.getByText('This receiver needs an update')).toBeVisible();
  await expect(source).toHaveValue(SYSTEM);
});

test('large quality history cleanup shows progress and can stop and resume the same job',
  async ({ page }, testInfo) => {
    const requests = await openStatistics(page, { largeJob: true, cancelQueues: true });
    const workspace = page.locator('.retained-statistics-page');
    await workspace.getByRole('combobox', { name: 'Radio system' }).selectOption(SYSTEM);
    await workspace.getByRole('button', { name: 'Control quality', exact: true }).click();
    await workspace.getByRole('combobox', { name: 'Site' }).selectOption(SITE);
    await workspace.getByRole('button', { name: 'Delete statistics for Quality history' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('213,000 directly matched records')).toBeVisible();
    await expect(dialog.getByText('New observations after cleanup starts are kept.')).toBeVisible();
    await expect(dialog.getByText('Saved aliases and alias lists remain.')).toBeVisible();
    expect(requests.writes).toHaveLength(0);
    await dialog.getByRole('button', { name: 'Delete statistics', exact: true }).click();
    await expect(workspace.getByText('80,000 of 213,000 records deleted')).toBeVisible();
    await expect(workspace.locator('.retained-statistics-job-notice')).not.toContainText('[object');
    await expect(workspace.locator('.retained-statistics-job-notice time')).toBeVisible();
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: testInfo.outputPath('retained-statistics-large-job-desktop-light.png'),
      fullPage: true });
    await workspace.getByRole('button', { name: 'Stop cleanup', exact: true }).click();
    await expect(workspace.getByText('Stopping after the current batch…')).toBeVisible();
    requests.setJob({ state: 'cancelled', can_resume: true });
    await expect(workspace.getByText('Cleanup stopped. Deleted records stay deleted.')).toBeVisible();
    await expect(workspace.getByRole('button', { name: 'Resume cleanup' })).toBeEnabled();
    await page.reload();
    await expect(workspace.getByRole('button', { name: 'Resume cleanup' })).toBeEnabled();
    await workspace.getByRole('button', { name: 'Resume cleanup' }).click();
    requests.setJob({ state: 'running', rows_deleted: 160_000 });
    await expect(workspace.getByText('160,000 of 213,000 records deleted')).toBeVisible();
    requests.setJob({ state: 'succeeded', rows_deleted: 212_998, rows_retained: 2, outcome: 'deleted' });
    await expect(workspace.getByText('Statistics deleted.', { exact: true })).toBeVisible();
    await expect(workspace.getByText('212,998 records deleted', { exact: true })).toBeVisible();
    await expect(workspace.getByText('2 records kept because they changed during cleanup.')).toBeVisible();
    expect(requests.actions).toEqual(['cancel', 'resume']);
    expect(requests.writes).toHaveLength(1);
    expect(await page.evaluate(() => sessionStorage.getItem('retained-statistics-job'))).toBeNull();
  });

test('history limits are previewed and submitted with inclusive From and exclusive Before',
  async ({ page }) => {
    const requests = await openStatistics(page, { largeJob: true });
    const workspace = page.locator('.retained-statistics-page');
    await workspace.getByRole('combobox', { name: 'Radio system' }).selectOption(SYSTEM);
    await workspace.getByRole('button', { name: 'Control quality', exact: true }).click();
    await workspace.getByRole('combobox', { name: 'Site' }).selectOption(SITE);
    await workspace.getByRole('button', { name: 'Delete statistics for Quality history' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('button', { name: 'Delete statistics', exact: true })).toBeEnabled();
    await dialog.locator('summary').filter({ hasText: 'Limit history' }).click();
    await dialog.getByLabel('From', { exact: true }).fill('2026-09-01T00:00');
    await dialog.getByLabel('Before', { exact: true }).fill('2026-10-01T00:00');
    await dialog.getByRole('spinbutton', { name: 'Frequency in MHz' }).fill('772.43125');
    await expect(dialog.getByRole('button', { name: 'Delete statistics', exact: true })).toBeDisabled();
    await dialog.getByRole('button', { name: 'Update preview' }).click();
    await expect(dialog.getByRole('button', { name: 'Delete statistics', exact: true })).toBeEnabled();
    expect(requests.previews.at(-1)).toMatchObject({ from_ms: Date.parse('2026-09-01T00:00:00Z'),
      to_ms: Date.parse('2026-10-01T00:00:00Z'), frequency_hz: 772_431_250,
      data_type: 'control_quality', parts: ['buckets'], site_configuration_id: SITE });
    await dialog.getByRole('button', { name: 'Delete statistics', exact: true }).click();
    expect(requests.writes[0].target).toEqual(requests.previews.at(-1));
  });

test('cleanup resumes status after reload without storing its deletion target', async ({ page }) => {
  const requests = await openStatistics(page, { largeJob: true });
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('combobox', { name: 'Radio system' }).selectOption(SYSTEM);
  await workspace.getByRole('button', { name: 'Hourly history', exact: true }).click();
  await workspace.getByRole('button', { name: 'Delete statistics for Hourly history' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete statistics', exact: true }).click();
  await expect(workspace.getByText('80,000 of 213,000 records deleted')).toBeVisible();
  expect(await page.evaluate(() => sessionStorage.getItem('retained-statistics-job'))).toBe('job-1');
  requests.setJob({ rows_deleted: 120_000 });
  await page.reload();
  await expect(workspace.getByText('120,000 of 213,000 records deleted')).toBeVisible();
  await expect(workspace.getByRole('button', { name: 'Stop cleanup' })).toBeVisible();
  expect(requests.writes).toHaveLength(1);
  requests.forgetJob();
  await page.reload();
  await expect(workspace.getByText('Cleanup status is no longer available.')).toBeVisible();
  expect(await page.evaluate(() => sessionStorage.getItem('retained-statistics-job'))).toBeNull();
});

test('failed partial cleanup reports completed work and offers resume', async ({ page }) => {
  const requests = await openStatistics(page, { largeJob: true });
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('combobox', { name: 'Radio system' }).selectOption(SYSTEM);
  await workspace.getByRole('button', { name: 'Hourly history', exact: true }).click();
  await workspace.getByRole('button', { name: 'Delete statistics for Hourly history' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete statistics', exact: true }).click();
  requests.setJob({ state: 'failed', can_resume: true, error: 'Cleanup paused after a storage error.' });
  await expect(workspace.getByText('Cleanup paused after a storage error.')).toBeVisible();
  await expect(workspace.getByText('80,000 of 213,000 records deleted')).toBeVisible();
  await expect(workspace.getByRole('button', { name: 'Resume cleanup' })).toBeVisible();
});

test('failed cleanup control keeps polling after an older in-flight status response', async ({ page }) => {
  const requests = await openStatistics(page, { largeJob: true, holdFirstPoll: true, actionFailsOnce: true });
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('combobox', { name: 'Radio system' }).selectOption(SYSTEM);
  await workspace.getByRole('button', { name: 'Hourly history', exact: true }).click();
  await workspace.getByRole('button', { name: 'Delete statistics for Hourly history' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete statistics', exact: true }).click();
  await expect.poll(() => requests.polls.length).toBe(1);
  requests.setJob({ rows_deleted: 120_000 });
  try {
    await workspace.getByRole('button', { name: 'Stop cleanup' }).click();
    await expect.poll(() => requests.polls.length).toBeGreaterThanOrEqual(2);
  } finally {
    requests.releaseFirstPoll();
  }
  await expect(workspace.getByText('120,000 of 213,000 records deleted')).toBeVisible();
  requests.setJob({ rows_deleted: 140_000 });
  await expect(workspace.getByText('140,000 of 213,000 records deleted')).toBeVisible();
  expect(requests.actions).toEqual(['cancel']);
});

for (const selectedSite of [false, true]) {
  test(`all retained data cleanup keeps the selected ${selectedSite ? 'site' : 'system'} scope`, async ({ page }) => {
    await openStatistics(page);
    const workspace = page.locator('.retained-statistics-page');
    const source = workspace.getByRole('combobox', { name: 'Radio system' });
    await source.selectOption(SYSTEM);
    await workspace.getByRole('button', { name: 'All retained data in scope' }).click();
    if (selectedSite) await workspace.getByRole('combobox', { name: 'Site' }).selectOption(SITE);
    await workspace.getByRole('button', { name: 'Delete statistics for All retained data' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('8 directly matched records')).toBeVisible();
    const phrase = selectedSite ? 'DELETE SITE' : 'Metro P25';
    await dialog.getByRole('textbox', { name: `Type ${phrase} to confirm` }).fill(phrase);
    await dialog.getByRole('button', { name: 'Delete statistics', exact: true }).click();
    await expect(workspace.getByText('Statistics deleted.', { exact: true })).toBeVisible();
    await expect(source).toHaveValue(SYSTEM);
    await expect(workspace.getByRole('button', { name: 'All retained data in scope' }))
      .toHaveAttribute('aria-pressed', 'true');
    if (selectedSite) await expect(workspace.getByRole('combobox', { name: 'Site' })).toHaveValue(SITE);
  });
}

test('large cleanup limits and progress fit a phone in dark mode', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openStatistics(page, { largeJob: true, theme: 'dark' });
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('combobox', { name: 'Radio system' }).selectOption(SYSTEM);
  await workspace.getByRole('button', { name: 'Hourly history', exact: true }).click();
  await workspace.getByRole('button', { name: 'Delete statistics for Hourly history' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('button', { name: 'Delete statistics', exact: true })).toBeEnabled();
  await dialog.locator('summary').filter({ hasText: 'Limit history' }).click();
  const bounds = await dialog.boundingBox();
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(390);
  await page.screenshot({ path: testInfo.outputPath('retained-statistics-history-limits-mobile-dark.png') });
  await dialog.getByRole('button', { name: 'Delete statistics', exact: true }).click();
  await expect(workspace.getByText('80,000 of 213,000 records deleted')).toBeVisible();
  await workspace.getByRole('button', { name: 'Stop cleanup' }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath('retained-statistics-progress-mobile-dark.png') });
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
