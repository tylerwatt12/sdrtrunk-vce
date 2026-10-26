const { expect, test } = require('@playwright/test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');

const views = (page) => page.locator('.alias-editor-view-tabs');
const table = (page) => page.locator('.alias-editor-table-host table');
const rowIds = (page) => table(page).locator('tbody tr').evaluateAll(rows => rows.map(row => row.dataset.id));
const search = (page) => page.locator('.alias-editor-filter-toolbar input[name="q"]');

async function installAliases(page, { paged = false, pageSize = 100 } = {}) {
  await page.setViewportSize({ width: 1280, height: 900 });
  const preferences = structuredClone((await import(pathToFileURL(resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults);
  await page.addInitScript(() => {
    for (const id of ['welcome', 'channel-setup', 'aliases']) {
      localStorage.setItem(`sdrtrunk-vce-guide:v1:alias-admin:alias-view-tabs-fixture:${id}:${id === 'aliases' ? 5 : id === 'channel-setup' ? 4 : 1}`, '1');
    }
  });
  const aliasList = { alias_list_id: 1, name: 'County Public Safety', family: 'P25',
    alias_count: paged ? 202 : 102, assigned_channel_count: 0 };
  const aliases = paged ? Array.from({ length: 202 }, (_, index) => ({
    alias_id: index + 1, alias_list_id: 1, name: `Fire Unit ${String(index + 1).padStart(3, '0')}`,
    group: 'Fire', matcher_type: 'TALKGROUP', identifier_display: `TG ${index + 1}`, recordable: true,
    logical_call_count: 300 - index, signaling_observation_count: (index + 1) * 2,
    last_evidence_ms: 1_700_000_000_000
  })) : [
    { alias_id: 101, alias_list_id: 1, name: 'Fire Dispatch', group: 'Fire',
      matcher_type: 'TALKGROUP', identifier_display: 'TG 101', logical_call_count: 4, signaling_observation_count: 2 },
    { alias_id: 102, alias_list_id: 1, name: 'Fireground', group: 'Fire',
      matcher_type: 'TALKGROUP', identifier_display: 'TG 102', logical_call_count: 5, signaling_observation_count: 3 }
  ];
  const aliasQueries = [];
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (path === '/api/v1/me/preferences') return route.fulfill({ json: { revision: 1, preferences } });
    if (path === '/api/v1/me/tour-dismissals') return route.fulfill({ json: { data: {
      version: 1, dismissed: { welcome: 1, 'channel-setup': 4, aliases: 5 } } } });
    let aliasPage = null;
    if (path === '/api/v1/aliases') {
      aliasQueries.push(url.searchParams.toString());
      let ordered = [...aliases];
      if (paged) {
        const q = (url.searchParams.get('q') || '').toLowerCase();
        ordered = ordered.filter(alias => (!q || alias.name.toLowerCase().includes(q)) &&
          (!url.searchParams.get('group') || alias.group === url.searchParams.get('group')));
        const key = url.searchParams.get('sort') || 'name';
        ordered.sort((left, right) => key === 'name' ? left.name.localeCompare(right.name) : Number(left[key]) - Number(right[key]));
        if (url.searchParams.get('direction') === 'desc') ordered.reverse();
      }
      const offset = paged ? Number(url.searchParams.get('offset') || 0) : 100;
      const limit = paged ? pageSize : 100;
      const rows = (paged ? ordered.slice(offset, offset + limit) : ordered).map(alias => {
        if (url.searchParams.get('include_activity') !== 'false') return alias;
        const { logical_call_count, signaling_observation_count, last_evidence_ms, ...configuration } = alias;
        return configuration;
      });
      aliasPage = { rows, total_count: paged ? ordered.length : 102, limit, offset,
        has_more: paged && offset + limit < ordered.length,
        next_offset: paged && offset + limit < ordered.length ? offset + limit : null };
    }
    const data = path === '/api/v1/auth/session' ? {
      configured: true, authenticated: true, username: 'alias-admin', tier: 'admin', primary: true,
      guide_dismissal_scope: 'alias-view-tabs-fixture', csrf_token: 'test-token',
      capabilities: { 'admin-aliases': true, 'admin-channels': true, radio: true }
    } : path === '/api/v1/alias-lists' ? {
      rows: [aliasList], limit: 500, offset: 0, has_more: false, next_offset: null
    } : path === '/api/v1/admin/alias-lists' ? { revision: 1, alias_lists: [aliasList] } :
      path === '/api/v1/admin/channels' ? { revision: 1, channels: [] } :
        path === '/api/v1/admin/aliases/options' ? { revision: 1, alias_list: aliasList, group_names: ['Fire'] } :
          path === '/api/v1/aliases' ? aliasPage : path === '/api/v1/alias-lists/1/observed-group-identities' ? {
            rows: [], total_count: 0, limit: 100, offset: 0, has_more: false, next_offset: null
          } : null;
    await route.fulfill({ status: data ? 200 : 404, contentType: 'application/json',
      body: JSON.stringify(data ? { data } : { error: { message: 'Not available in this browser contract' } }) });
  });
  return aliasQueries;
}

test('Explicit name order keeps the current Alias results when switching column views', async ({ page }) => {
  const aliasQueries = await installAliases(page);
  await page.goto('/app.html?view=aliases&list=1&aliasTab=configure&q=fire&offset=100&sort=name&direction=asc');
  const views = page.locator('.alias-editor-view-tabs');
  const table = page.locator('.alias-editor-table-host table');
  await expect(table.locator('tbody tr')).toHaveCount(2);
  await expect(views.locator('a')).toHaveText(['Configure', 'Activity', 'Custom']);
  await expect(views.getByRole('link', { name: 'Discover' })).toHaveCount(0);
  await expect(page.locator('.alias-editor-view-switcher > .alias-discover-link')).toBeVisible();
  await page.evaluate(() => {
    window.aliasSearchInputForTest = document.querySelector('.alias-editor-filter-toolbar input[name="q"]');
    window.aliasSummaryForTest = document.querySelector('.alias-list-summary');
  });
  await page.locator('.alias-editor-filter-toolbar input[name="q"]').fill('unfinished search');
  const rowIds = () => table.locator('tbody tr').evaluateAll((rows) => rows.map((row) => row.dataset.id));
  expect(await rowIds()).toEqual(['101', '102']);

  await views.getByRole('link', { name: 'Activity' }).click();
  await expect(views.getByRole('link', { name: 'Activity' })).toHaveAttribute('aria-current', 'page');
  await expect(table.locator('th[data-column="calls"]')).toBeVisible();
  await expect(table.locator('tbody td[data-column="calls"]')).toHaveText(['4', '5']);
  expect(await rowIds()).toEqual(['101', '102']);
  expect(await page.evaluate(() => window.aliasSearchInputForTest ===
    document.querySelector('.alias-editor-filter-toolbar input[name="q"]') &&
    window.aliasSummaryForTest === document.querySelector('.alias-list-summary'))).toBe(true);
  await expect(page.locator('.alias-editor-filter-toolbar input[name="q"]'))
    .toHaveValue('unfinished search');
  const activityRoute = new URL(page.url()).searchParams;
  expect(activityRoute.get('q')).toBe('fire');
  expect(activityRoute.get('offset')).toBe('100');
  expect(activityRoute.get('sort')).toBe('name');
  expect(activityRoute.get('direction')).toBe('asc');
  expect(activityRoute.get('aliasTab')).toBe('activity');

  await views.getByRole('link', { name: 'Custom' }).click();
  await expect(views.getByRole('link', { name: 'Custom' })).toHaveAttribute('aria-current', 'page');
  await expect(table).toHaveAttribute('data-table-type', 'alias-editor-custom');
  await expect(table.locator('th[data-column="behavior"]')).toHaveCount(0);
  expect(await rowIds()).toEqual(['101', '102']);
  await views.getByRole('link', { name: 'Configure' }).click();
  await expect(views.getByRole('link', { name: 'Configure' })).toHaveAttribute('aria-current', 'page');
  await expect(table.locator('th[data-column="behavior"]')).toBeVisible();
  expect(aliasQueries).toHaveLength(2);
  for (const query of aliasQueries) {
    const parameters = new URLSearchParams(query);
    expect(parameters.get('q')).toBe('fire');
    expect(parameters.get('offset')).toBe('100');
    expect(parameters.get('sort')).toBe('name');
    expect(parameters.get('direction')).toBe('asc');
  }

  await page.locator('.alias-discover-link').click();
  const discoverSearch = page.locator('.observed-group-identity-toolbar');
  await expect(discoverSearch).toBeVisible();
  expect(new URL(page.url()).searchParams.has('q')).toBe(false);
  const aligned = await discoverSearch.evaluate((form) => {
    const input = form.querySelector('input[type="search"]');
    const button = form.querySelector('button');
    return Math.abs(input.getBoundingClientRect().bottom - button.getBoundingClientRect().bottom) <= 1;
  });
  expect(aligned).toBe(true);
});

function lastQuery(queries) { return new URLSearchParams(queries.at(-1)); }
function currentRoute(page) { return new URL(page.url()).searchParams; }

async function expectRows(page, expected) {
  await expect.poll(async () => (await rowIds(page)).slice(0, expected.length)).toEqual(expected);
}

async function expectTabRoute(page, label, { sort = null, direction = null, offset = 0 } = {}) {
  const target = new URL(await views(page).getByRole('link', { name: label, exact: true }).getAttribute('href'), page.url()).searchParams;
  expect(target.get('sort')).toBe(sort);
  expect(target.get('direction')).toBe(direction);
  expect(Number(target.get('offset') || 0)).toBe(offset);
}

test('Activity opens with highest signaling first and its header toggles to ascending', async ({ page }, testInfo) => {
  const queries = await installAliases(page, { paged: true });
  await page.goto('/app.html?view=aliases&list=1&aliasTab=activity&q=fire');
  await expectRows(page, ['202', '201', '200']);
  await expect(table(page).locator('th[data-column="signaling"]')).toHaveAttribute('aria-sort', 'descending');
  await expect(table(page).locator('th[data-column="calls"]')).toHaveAttribute('aria-sort', 'none');
  expect(lastQuery(queries).get('sort')).toBe('signaling_observation_count');
  expect(lastQuery(queries).get('direction')).toBe('desc');
  expect(currentRoute(page).has('sort')).toBe(false);
  expect(currentRoute(page).has('direction')).toBe(false);
  await expectTabRoute(page, 'Configure');
  await table(page).locator('th[data-column="signaling"]').scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath('activity-default-signaling-desc.png') });
  await table(page).locator('th[data-column="signaling"]').getByRole('link', { name: /^Signaling/ }).click();
  await expectRows(page, ['1', '2', '3']);
  await expect(table(page).locator('th[data-column="signaling"]')).toHaveAttribute('aria-sort', 'ascending');
  expect(lastQuery(queries).get('sort')).toBe('signaling_observation_count');
  expect(lastQuery(queries).get('direction')).toBe('asc');
  expect(currentRoute(page).get('direction')).toBe('asc');
  await expectTabRoute(page, 'Configure', { sort: 'signaling_observation_count', direction: 'asc' });
  expect(queries).toHaveLength(2);
});

test('Configure to Activity fetches the highest signaling server page and retains workspace drafts filters and selection', async ({ page }) => {
  const queries = await installAliases(page, { paged: true });
  await page.goto('/app.html?view=aliases&list=1&aliasTab=configure&q=fire&group=Fire&record=true&offset=100');
  await expectRows(page, ['101', '102', '103']);
  await expectTabRoute(page, 'Activity');
  await expectTabRoute(page, 'Custom', { offset: 100 });
  await page.evaluate(() => {
    window.aliasWorkspaceForTest = document.querySelector('.alias-editor-workspace');
    window.aliasSummaryForTest = document.querySelector('.alias-list-summary');
  });
  await page.getByRole('checkbox', { name: 'Select Fire Unit 101', exact: true }).check();
  await search(page).fill('unfinished search');
  await views(page).getByRole('link', { name: 'Activity', exact: true }).click();
  await expectRows(page, ['202', '201', '200']);
  await expect(table(page).locator('th[data-column="signaling"]')).toHaveAttribute('aria-sort', 'descending');
  await expect(views(page).getByRole('link', { name: 'Activity', exact: true })).toHaveAttribute('aria-current', 'page');
  expect(Number(currentRoute(page).get('offset') || 0)).toBe(0);
  expect(currentRoute(page).has('sort')).toBe(false);
  expect(currentRoute(page).has('direction')).toBe(false);
  for (const [key, value] of [['q', 'fire'], ['group', 'Fire'], ['record', 'true']]) {
    expect(currentRoute(page).get(key)).toBe(value);
    expect(lastQuery(queries).get(key)).toBe(value);
  }
  expect(Number(lastQuery(queries).get('offset') || 0)).toBe(0);
  expect(lastQuery(queries).get('sort')).toBe('signaling_observation_count');
  expect(lastQuery(queries).get('direction')).toBe('desc');
  expect(lastQuery(queries).get('include_activity')).not.toBe('false');
  expect(queries).toHaveLength(2);
  await expect(search(page)).toHaveValue('unfinished search');
  await expect(page.locator('.alias-bulk-count')).toHaveText('1 selected');
  expect(await page.evaluate(() => window.aliasWorkspaceForTest === document.querySelector('.alias-editor-workspace') &&
    window.aliasSummaryForTest === document.querySelector('.alias-list-summary'))).toBe(true);
  await expectTabRoute(page, 'Configure');
  await expectTabRoute(page, 'Custom');
});

test('Explicit Calls sorting carries across Alias tabs without replacing the current server page', async ({ page }) => {
  const queries = await installAliases(page, { paged: true });
  await page.goto('/app.html?view=aliases&list=1&aliasTab=configure&q=fire&sort=logical_call_count&direction=desc&offset=100');
  await expectRows(page, ['101', '102', '103']);
  const expected = await rowIds(page);
  await views(page).getByRole('link', { name: 'Activity', exact: true }).click();
  await expect(table(page).locator('th[data-column="calls"]')).toHaveAttribute('aria-sort', 'descending');
  expect(await rowIds(page)).toEqual(expected);
  await expectTabRoute(page, 'Custom', { sort: 'logical_call_count', direction: 'desc', offset: 100 });
  await views(page).getByRole('link', { name: 'Custom', exact: true }).click();
  await expect(table(page)).toHaveAttribute('data-table-type', 'alias-editor-custom');
  await views(page).getByRole('link', { name: 'Configure', exact: true }).click();
  await expect(table(page).locator('th[data-column="behavior"]')).toBeVisible();
  expect(await rowIds(page)).toEqual(expected);
  expect(queries).toHaveLength(2);
  for (const query of queries) {
    const parameters = new URLSearchParams(query);
    expect(parameters.get('sort')).toBe('logical_call_count');
    expect(parameters.get('direction')).toBe('desc');
    expect(parameters.get('offset')).toBe('100');
  }
});

test('Default Activity paging and Back Forward retain signaling order without making the default explicit', async ({ page }) => {
  const queries = await installAliases(page, { paged: true, pageSize: 2 });
  await page.goto('/app.html?view=aliases&list=1&aliasTab=activity&q=fire');
  await expectRows(page, ['202', '201']);
  await page.getByRole('link', { name: 'Next', exact: true }).click();
  await expectRows(page, ['200', '199']);
  expect(currentRoute(page).get('offset')).toBe('2');
  expect(currentRoute(page).has('sort')).toBe(false);
  expect(currentRoute(page).has('direction')).toBe(false);
  await expectTabRoute(page, 'Configure');
  await page.goBack();
  await expectRows(page, ['202', '201']);
  await page.goForward();
  await expectRows(page, ['200', '199']);
  await views(page).getByRole('link', { name: 'Configure', exact: true }).click();
  await expectRows(page, ['1', '2']);
  expect(Number(currentRoute(page).get('offset') || 0)).toBe(0);
  await page.goBack();
  await expectRows(page, ['200', '199']);
  await expect(table(page).locator('th[data-column="signaling"]')).toHaveAttribute('aria-sort', 'descending');
  expect(queries.filter(query => new URLSearchParams(query).get('sort') === 'signaling_observation_count').length).toBeGreaterThanOrEqual(4);
});

test('Explicit name paging preserves its order through tabs and browser history', async ({ page }) => {
  await installAliases(page, { paged: true, pageSize: 2 });
  await page.goto('/app.html?view=aliases&list=1&aliasTab=activity&q=fire&sort=name&direction=asc&offset=2');
  await expectRows(page, ['3', '4']);
  await page.getByRole('link', { name: 'Next', exact: true }).click();
  await expectRows(page, ['5', '6']);
  await page.goBack();
  await expectRows(page, ['3', '4']);
  await page.goForward();
  await expectRows(page, ['5', '6']);
  await expectTabRoute(page, 'Configure', { sort: 'name', direction: 'asc', offset: 4 });
  await views(page).getByRole('link', { name: 'Configure', exact: true }).click();
  await expect(table(page).locator('th[data-column="behavior"]')).toBeVisible();
  await expectRows(page, ['5', '6']);
  expect(currentRoute(page).get('sort')).toBe('name');
  expect(currentRoute(page).get('direction')).toBe('asc');
  expect(currentRoute(page).get('offset')).toBe('4');
});
