'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

test('Alias inventory waits for a list selection and includes analog lists', async ({ page }) => {
  const coverageRequests = [];
  let releaseUnassignedCount;
  const unassignedCountReady = new Promise((resolve) => { releaseUnassignedCount = resolve; });
  const pageOf = (rows) => ({ rows, total_count: rows.length, limit: 100, offset: 0,
    has_more: false, next_offset: null });
  const lists = pageOf([
    { alias_list_id: 1, name: 'Analog', family: 'NBFM', alias_count: 1 },
    { alias_list_id: 2, name: 'County Public Safety', family: 'P25', alias_count: 2 },
    { alias_list_id: 3, name: 'Airband', family: 'AM', alias_count: 1 },
    { alias_list_id: 4, name: 'City Services', family: 'DMR', alias_count: 1 },
    { alias_list_id: 5, name: 'Regional', family: 'NXDN', alias_count: 1 }
  ]);
  const channelIds = [
    '11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222',
    '33333333-3333-4333-8333-333333333333', '44444444-4444-4444-8444-444444444444',
    '55555555-5555-4555-8555-555555555555'
  ];
  const systemKeys = ['p25:00001:001', 'p25:00002:001'];
  const overview = { totals: { configured_alias_count: 2, active_alias_count: 1,
    zero_call_alias_count: 1, never_heard_alias_count: 1 },
    correlated_radio_system_count: 2,
    channels: [
      { configuration_id: channelIds[0], name: 'County Simulcast', site_name: 'Central',
        system_name: 'County System', radio_system_key: systemKeys[0], protocol: 'P25',
        channel_entity_ref: { kind: 'channel', key: channelIds[0] },
        radio_system_entity_ref: { kind: 'radio_system', key: systemKeys[0] } },
      { configuration_id: channelIds[1], name: 'North Control', site_name: 'North Control',
        system_name: 'County System', radio_system_key: systemKeys[0], protocol: 'P25',
        channel_entity_ref: { kind: 'channel', key: channelIds[1] },
        radio_system_entity_ref: { kind: 'radio_system', key: systemKeys[0] } },
      { configuration_id: channelIds[2], name: 'County System · West Dispatch', site_name: 'West',
        system_name: 'County System', radio_system_key: systemKeys[1], protocol: 'P25',
        channel_entity_ref: { kind: 'channel', key: channelIds[2] },
        radio_system_entity_ref: { kind: 'radio_system', key: systemKeys[1] } },
      { configuration_id: channelIds[3], name: 'Fireground Conventional', site_name: 'Local',
        system_name: 'County Fireground', protocol: 'P25',
        channel_entity_ref: { kind: 'channel', key: channelIds[3] } },
      { configuration_id: channelIds[4], name: 'Local Monitor', site_name: 'Local Monitor',
        system_name: 'County System', radio_system_key: systemKeys[0], protocol: 'P25',
        radio_system_entity_ref: { kind: 'radio_system', key: systemKeys[0] } }
    ] };
  const aliases = pageOf([
    { alias_id: 21, name: 'Fire Dispatch', matcher_label: 'Talkgroup',
      identity_display: '101', identity_type: 'talkgroup', value: 101,
      logical_call_count: 4, signaling_observation_count: 7 },
    { alias_id: 22, name: 'Dispatch Tone', matcher_label: 'Tone',
      identity_display: '123.0 Hz', identity_type: 'tone', metrics_state: 'unsupported' }
  ]);
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname.startsWith('/api/v1/identities/lists')) coverageRequests.push(pathname);
    if (pathname === '/api/v1/identities/lists/2/unassigned') await unassignedCountReady;
    const data = pathname === '/api/v1/auth/session' ? {
      configured: true, authenticated: true, username: 'operator', tier: 'admin', primary: true,
      csrf_token: 'test-token', capabilities: { dashboard: true, radio: true, 'admin-aliases': true }
    } : pathname === '/api/v1/me/preferences' ? {
      revision: 1, preferences: structuredClone(defaultPreferences)
    } : pathname === '/api/v1/status' ? {
      stats_logging: { summary_configured: true, summary_active: true,
        detailed_history_configured: false, detailed_history_active: false }, database: {}
    } : pathname === '/api/v1/quality' ? pageOf([]) :
      pathname === '/api/v1/channel-catalog' ? { revision: 1, channels: [] } :
        pathname === '/api/v1/radio-systems' ? pageOf([]) :
          pathname === '/api/v1/identities/lists' ? lists :
            pathname === '/api/v1/identities/lists/2/overview' ? overview :
              pathname === '/api/v1/identities/lists/2/aliases' ? aliases :
                pathname === '/api/v1/identities/lists/2/unassigned' ? pageOf([]) : null;
    await route.fulfill({ status: data ? 200 : 404, contentType: 'application/json',
      body: JSON.stringify(data ? { data } : { error: { message: 'Unavailable in this browser test' } }) });
  });

  await page.goto('/app.html?view=dashboard&tab=health');
  const sectionNavigation = page.getByRole('navigation', { name: 'Main sections' });
  await expect(sectionNavigation.getByRole('link', { name: 'Radio Activity', exact: true })).toBeVisible();
  await expect(page.getByRole('group', { name: 'Radio Directory view' })).toHaveCount(0);
  await sectionNavigation.getByRole('link', { name: 'Alias Inventory' }).click();

  const inventory = page.locator('.alias-coverage');
  const listSelect = inventory.getByRole('combobox', { name: 'Alias List' });
  await expect(listSelect).toBeVisible();
  await expect(listSelect).toBeFocused();
  expect(await listSelect.evaluate((select) => select.matches(':focus-visible'))).toBe(true);
  await expect(listSelect).toHaveValue('');
  const inventoryHeader = inventory.locator('.alias-coverage-header');
  await expect(inventoryHeader.getByRole('heading', { name: 'Alias inventory' })).toBeVisible();
  await expect(inventoryHeader).toContainText('Find heard aliases and activity without a match.');
  const [navigationBox, headerBox] = await Promise.all([
    sectionNavigation.boundingBox(), inventoryHeader.boundingBox()
  ]);
  expect(headerBox.y - navigationBox.y - navigationBox.height).toBeGreaterThanOrEqual(24);
  await expect(inventory.locator('.alias-coverage-selection-prompt')).toHaveText('Choose a list to get started');
  expect(await inventory.evaluate((element) => Boolean(element.closest('.ui-section')))).toBe(false);
  expect((await listSelect.boundingBox()).width).toBeLessThanOrEqual(360);

  await expect(listSelect.locator('option')).toHaveText([
    'Select an alias list', 'Analog · Conventional Analog (AM/NBFM) · 1 alias',
    'County Public Safety · P25 · 2 aliases', 'Airband · AM · 1 alias',
    'City Services · DMR · 1 alias', 'Regional · NXDN · 1 alias'
  ]);
  await expect(inventory.locator('.alias-coverage-field')).toHaveCount(1);
  await expect(inventory.locator('.alias-coverage-summary, .alias-coverage-scope, .alias-coverage-table-section'))
    .toHaveCount(0);
  expect(coverageRequests).toEqual(['/api/v1/identities/lists']);

  await listSelect.selectOption('2');
  await expect(inventory.locator('.alias-coverage-summary')).toBeVisible();
  const tableHeading = inventory.locator('.alias-coverage-table-heading');
  const listName = await tableHeading.textContent();
  await tableHeading.locator('h2').evaluate((element) => {
    element.textContent = 'CountyPublicSafetyAndRegionalEmergencyServices'.repeat(4);
  });
  for (const width of [1280, 320]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await tableHeading.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
    const [heading, actions] = await Promise.all([
      tableHeading.boundingBox(), inventory.locator('.alias-coverage-table-actions').boundingBox()
    ]);
    expect(heading.x + heading.width).toBeLessThanOrEqual(actions.x);
  }
  await tableHeading.locator('h2').evaluate((element, text) => { element.textContent = text; }, listName);
  await page.setViewportSize({ width: 1280, height: 900 });
  const channelScope = inventory.locator('details.alias-coverage-scope');
  const channelScopeSummary = channelScope.locator(':scope > summary.ui-section-summary');
  await expect(channelScopeSummary).toContainText('Channels using this Alias List');
  await expect(channelScopeSummary).toContainText(/5 channels.*2 radio systems/);
  await expect(channelScope).not.toHaveAttribute('open');
  await expect(channelScope.locator('.alias-coverage-channel-grid').first()).toBeHidden();
  expect(await inventory.evaluate((element) => [...element.childNodes]
    .filter((child) => child.nodeType === Node.TEXT_NODE && child.textContent.trim() === 'null')
    .map((child) => child.textContent))).toEqual([]);
  await channelScopeSummary.focus();
  await page.keyboard.press('Enter');
  await expect(channelScope).toHaveAttribute('open', '');
  const channelGroups = channelScope.locator('.alias-coverage-scope-group');
  await expect(channelGroups).toHaveCount(3);
  const systemLinks = channelGroups.locator('h3 a');
  await expect(systemLinks).toHaveText(['County System', 'County System']);
  for (const [index, key] of systemKeys.entries()) {
    const target = new URL(await systemLinks.nth(index).getAttribute('href'), page.url());
    expect(target.searchParams.get('view')).toBe('radio-system');
    expect(target.searchParams.get('radio_system_key')).toBe(key);
  }
  await expect(channelScope.locator('table')).toHaveCount(0);
  await expect(channelScope.locator('.ui-link-tile')).toHaveCount(5);
  await expect(channelScope.locator('a.ui-link-tile .ui-link-tile-icon')).toHaveCount(4);
  await expect(channelGroups.nth(0).locator('.alias-coverage-scope-row')).toHaveCount(3);
  await expect(channelGroups.nth(0).locator('.ui-link-tile-context')).toHaveText(['Central']);
  await expect(channelGroups.nth(1).locator('.ui-link-tile-context')).toHaveText(['West']);
  await expect(channelGroups.nth(2)).toContainText('County Fireground');
  await expect(channelGroups.nth(2).locator('.ui-link-tile-context')).toHaveText(['Local']);
  const unlinked = channelScope.locator('div.ui-link-tile');
  await expect(unlinked).toHaveText('Local Monitor');
  await expect(unlinked.locator('a, .ui-link-tile-icon')).toHaveCount(0);
  const shortened = channelScope.locator('a.ui-link-tile')
    .filter({ has: page.getByText('West Dispatch', { exact: true }) });
  await expect(shortened.locator('.ui-link-tile-label')).toHaveText('West Dispatch');
  await expect(shortened).toHaveAttribute('aria-label', 'County System · West Dispatch');
  await expect(shortened).toHaveAttribute('data-ui-hint', 'County System · West Dispatch');
  for (const [index, name] of ['County Simulcast', 'North Control', 'West Dispatch',
    'Fireground Conventional'].entries()) {
    const channelLink = channelScope.locator('a.ui-link-tile')
      .filter({ has: page.getByText(name, { exact: true }) });
    await expect(channelLink).toBeVisible();
    const target = new URL(await channelLink.getAttribute('href'), page.url());
    expect(target.searchParams.get('view')).toBe('channel');
    expect(target.searchParams.get('configuration_id')).toBe(channelIds[index]);
  }
  await channelScopeSummary.focus();
  await page.keyboard.press('Space');
  await expect(channelScope).not.toHaveAttribute('open');
  await expect(inventory.getByRole('heading', { name: 'Alias inventory' })).toBeVisible();
  await expect(inventory.getByRole('button', { name: 'Other', exact: true })).toBeVisible();
  await expect(inventory.getByText('Fire Dispatch', { exact: true })).toBeVisible();
  await expect(inventory.locator('.alias-coverage-summary')).toContainText('Loading…');
  releaseUnassignedCount();
  await expect(inventory.locator('.alias-coverage-summary')).not.toContainText('Loading…');
  expect(await inventory.evaluate((element) => [...element.childNodes]
    .filter((child) => child.nodeType === Node.TEXT_NODE && child.textContent.trim() === 'null')
    .map((child) => child.textContent))).toEqual([]);
  await expect(inventory.getByRole('button', { name: /^Unknown aliases(?: \(0\))?$/ })).toBeVisible();
  await expect(inventory.getByRole('button', { name: /Unassigned observations/ })).toHaveCount(0);
  await expect(inventory.getByRole('link', { name: 'Discover', exact: true })).toHaveCount(0);
  const toneRow = inventory.locator('.alias-coverage-table-section tbody tr')
    .filter({ hasText: 'Dispatch Tone' });
  await expect(toneRow).toContainText('Tone');
  await expect(toneRow).toContainText('123.0 Hz');
  await expect(toneRow).toContainText('Not tracked');
  expect(coverageRequests).toEqual(['/api/v1/identities/lists',
    '/api/v1/identities/lists/2/overview', '/api/v1/identities/lists/2/aliases',
    '/api/v1/identities/lists/2/unassigned']);

  await listSelect.selectOption('');
  await expect(inventory.getByRole('combobox', { name: 'Alias List' })).toHaveValue('');
  await expect(inventory.getByRole('combobox', { name: 'Alias List' })).toBeFocused();
  expect(await inventory.getByRole('combobox', { name: 'Alias List' })
    .evaluate((select) => select.matches(':focus-visible'))).toBe(true);
  await expect(inventory.locator('.alias-coverage-summary, .alias-coverage-scope, .alias-coverage-table-section'))
    .toHaveCount(0);
  expect(coverageRequests).toHaveLength(4);
  await page.setViewportSize({ width: 390, height: 844 });
  const [headingBox, selectBox] = await Promise.all([
    inventoryHeader.locator('.ui-heading-group').boundingBox(), listSelect.boundingBox()
  ]);
  expect(selectBox.y).toBeGreaterThanOrEqual(headingBox.y + headingBox.height);
  expect(await page.locator('body').evaluate((body) => body.scrollWidth <= body.clientWidth)).toBe(true);
});
