'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

test('Alias inventory waits for a supported list selection', async ({ page }) => {
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
  const overview = { totals: { configured_alias_count: 2, active_alias_count: 1,
    zero_call_alias_count: 1, never_heard_alias_count: 1 },
    correlated_radio_system_count: 1,
    channels: [{ configuration_id: 'channel-1', name: 'County Simulcast',
      system_name: 'County System' }] };
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
  const sectionNavigation = page.getByRole('navigation', { name: 'Section navigation' });
  await expect(sectionNavigation.getByRole('link', { name: 'Radio Activity' })).toBeVisible();
  await page.getByRole('group', { name: 'Radio Directory view' })
    .getByRole('button', { name: 'Alias inventory' }).click();

  const inventory = page.locator('.alias-coverage');
  const listSelect = inventory.getByRole('combobox', { name: 'Alias List' });
  await expect(listSelect).toBeVisible();
  await expect(listSelect).toBeFocused();
  expect(await listSelect.evaluate((select) => select.matches(':focus-visible'))).toBe(true);
  await expect(listSelect).toHaveValue('');
  await expect(listSelect.locator('option')).toHaveText([
    'Select an alias list', 'County Public Safety · P25 · 2 aliases',
    'City Services · DMR · 1 alias', 'Regional · NXDN · 1 alias'
  ]);
  await expect(inventory.locator('.alias-coverage-field')).toHaveCount(1);
  await expect(inventory.locator('.alias-coverage-summary, .alias-coverage-scope, .alias-coverage-table-section'))
    .toHaveCount(0);
  expect(coverageRequests).toEqual(['/api/v1/identities/lists']);

  await listSelect.selectOption('2');
  await expect(inventory.locator('.alias-coverage-summary')).toBeVisible();
  await expect(inventory.getByRole('heading', { name: 'Channels using this Alias List' })).toBeVisible();
  await expect(inventory.getByRole('heading', { name: 'Alias inventory' })).toBeVisible();
  await expect(inventory.getByRole('button', { name: 'Other', exact: true })).toBeVisible();
  await expect(inventory.getByText('Fire Dispatch', { exact: true })).toBeVisible();
  await expect(inventory.locator('.alias-coverage-summary')).toContainText('Loading…');
  releaseUnassignedCount();
  await expect(inventory.locator('.alias-coverage-summary')).not.toContainText('Loading…');
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
});
