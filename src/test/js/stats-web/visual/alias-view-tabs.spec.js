const { expect, test } = require('@playwright/test');

test('Alias column views keep the current results and switch without rebuilding the page', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const aliasList = { alias_list_id: 1, name: 'County Public Safety', family: 'P25',
    alias_count: 102, assigned_channel_count: 0 };
  const aliases = [
    { alias_id: 101, alias_list_id: 1, name: 'Fire Dispatch', group: 'Fire',
      matcher_type: 'TALKGROUP', identifier_display: 'TG 101' },
    { alias_id: 102, alias_list_id: 1, name: 'Fireground', group: 'Fire',
      matcher_type: 'TALKGROUP', identifier_display: 'TG 102' }
  ];
  const aliasQueries = [];
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (path === '/api/v1/aliases') aliasQueries.push(url.searchParams.toString());
    const data = path === '/api/v1/auth/session' ? {
      configured: true, authenticated: true, username: 'alias-admin', tier: 'admin', primary: true,
      csrf_token: 'test-token', capabilities: { 'admin-aliases': true, 'admin-channels': true, radio: true }
    } : path === '/api/v1/alias-lists' ? {
      rows: [aliasList], limit: 500, offset: 0, has_more: false, next_offset: null
    } : path === '/api/v1/admin/alias-lists' ? { revision: 1, alias_lists: [aliasList] } :
      path === '/api/v1/admin/channels' ? { revision: 1, channels: [] } :
        path === '/api/v1/admin/aliases/options' ? { revision: 1, alias_list: aliasList } :
          path === '/api/v1/aliases' ? {
            rows: aliases.map((alias, index) => url.searchParams.get('include_activity') === 'false' ?
              alias : { ...alias, logical_call_count: index + 4,
                signaling_observation_count: index + 2, last_evidence_ms: 1_700_000_000_000 }),
            total_count: 102, limit: 100, offset: 100, has_more: false, next_offset: null
          } : path === '/api/v1/alias-lists/1/observed-group-identities' ? {
            rows: [], total_count: 0, limit: 100, offset: 0, has_more: false, next_offset: null
          } : null;
    await route.fulfill({ status: data ? 200 : 404, contentType: 'application/json',
      body: JSON.stringify(data ? { data } : { error: { message: 'Not available in this browser contract' } }) });
  });

  await page.goto('/app.html?view=aliases&list=1&aliasTab=configure&q=fire&offset=100');
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
