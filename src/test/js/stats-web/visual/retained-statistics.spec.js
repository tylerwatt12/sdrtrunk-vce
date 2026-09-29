'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

async function openStatistics(page, options = {}) {
  const requests = { frequencyResults: 0, resultTypes: [], writes: [] };
  const frequencies = Array.from({ length: 30 }, (_, index) => ({
    label: `${(850 + index * 0.0125).toFixed(4)} MHz`, detail: `Channel ${index + 1}`,
    observation_count: index + 1, last_seen_ms: 1_780_000_000_000,
    target: { kind: 'frequency', site_configuration_id: 'site-a',
      expected_site_key: 'opaque-site-a', frequency_hz: 850_000_000 + index * 12_500 }
  }));
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const pathname = url.pathname;
    const data = (value, meta = undefined) => ({ data: value, ...(meta ? { meta } : {}) });
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: data({ configured: true, authenticated: true,
        username: 'admin', tier: 'admin', primary: true,
        capabilities: { 'admin-settings': true, 'receiver-health': true, credits: true } }) });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences: defaultPreferences } });
    } else if (pathname.endsWith('/retained-statistics/sources')) {
      const rows = url.searchParams.get('kind') === 'saved_channel' ? [
        { source_kind: 'saved_channel', source_key: 'site-a', label: 'Downtown channel',
          channel_kind: 'CONVENTIONAL', protocol: 'DMR' }
      ] : [
        { source_kind: 'radio_system', source_key: 'system-a', label: 'Metro P25' },
        { source_kind: 'radio_system', source_key: 'system-b', label: 'County P25' }
      ];
      await route.fulfill({ json: data(rows, { limit: 50, offset: 0, has_more: false }) });
    } else if (pathname.endsWith('/retained-statistics/sites')) {
      await route.fulfill({ json: data([
        { configuration_id: 'site-a', label: 'Downtown site', site_key: 'opaque-site-a' },
        { configuration_id: 'site-b', label: 'Uptown site', site_key: 'opaque-site-b' }
      ], { limit: 50, offset: 0, has_more: false }) });
    } else if (pathname.endsWith('/retained-statistics/results')) {
      const kind = url.searchParams.get('data_type');
      requests.resultTypes.push(kind);
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
            target: conventional ? { kind: 'conventional_radio', configuration_id: 'site-a',
              frequency_hz: 850_000_000, timeslot: 1, native_id: 1234 } :
              { kind: 'radio', radio_system_key: 'system-a', identity_key: 'radio-1234' } },
          talkgroups: { label: 'Talkgroup 30',
            target: conventional ? { kind: 'conventional_talkgroup', configuration_id: 'site-a',
              frequency_hz: 850_000_000, timeslot: 1, native_id: 30 } :
              { kind: 'talkgroup', radio_system_key: 'system-a', identity_key: 'group-30' } },
          sites: { label: 'Downtown site',
            target: { kind: 'saved_site', configuration_id: 'site-a',
              expected_site_key: 'opaque-site-a', include_channel_history: false } },
          channels: { label: 'Downtown channel',
            target: { kind: 'channel', configuration_id: 'site-a' } },
          systems: { label: 'Metro P25',
            target: { kind: 'system', radio_system_key: 'system-a', include_channel_history: false } }
        };
        await route.fulfill({ json: data(samples[kind] ? [samples[kind]] : [], { limit: 25, offset: 0,
          has_more: false, total_count: samples[kind] ? 1 : 0 }) });
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
  await expect(source.locator('option').nth(1)).toHaveText('Metro P25');
  await source.selectOption('system-a');
  await workspace.getByRole('button', { name: 'Frequencies' }).click();
  await expect(workspace.getByText('Choose a site to see frequencies.')).toBeVisible();
  expect(requests.frequencyResults).toBe(0);
  const site = workspace.getByRole('combobox', { name: 'Site', exact: true });
  await expect(site.locator('option')).toHaveCount(3);
  await site.selectOption('site-a');
  await expect(workspace.getByText('Metro P25 · Downtown site · Frequencies')).toBeVisible();
  await expect(workspace.locator('.retained-statistics-result-count')).toHaveText('30 results');
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
    site_configuration_id: 'site-a', expected_site_key: 'opaque-site-a' });
});

test('saved-channel selection keeps site drilldown and fits a phone', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openStatistics(page);
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('button', { name: 'Saved channel', exact: true }).click();
  const source = workspace.getByRole('combobox', { name: 'Source', exact: true });
  await expect(source.locator('option').nth(1)).toHaveText('Downtown channel');
  await source.selectOption('site-a');
  await expect(workspace.getByRole('button', { name: 'Radio IDs' })).toBeEnabled();
  await expect(workspace.getByRole('button', { name: 'Radio systems' })).toBeDisabled();
  await workspace.getByRole('button', { name: 'Radio IDs' }).click();
  await expect(workspace.getByText('850.0000 MHz · Slot 1')).toBeVisible();
  await workspace.getByRole('button', { name: 'Frequencies' }).click();
  await expect(workspace.getByRole('combobox', { name: 'Site', exact: true })).toBeVisible();
  const bounds = await workspace.boundingBox();
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(390);
});

test('each system data type opens results and broad scopes show their impact', async ({ page }) => {
  const requests = await openStatistics(page);
  const workspace = page.locator('.retained-statistics-page');
  await workspace.getByRole('combobox', { name: 'Source', exact: true }).selectOption('system-a');
  for (const type of ['Radio IDs', 'Talkgroups', 'Sites', 'Saved channels', 'Radio systems']) {
    await workspace.getByRole('button', { name: type, exact: true }).click();
    await expect(workspace.getByRole('button', { name: 'Review removal of', exact: false })).toHaveCount(1);
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
  await workspace.getByRole('combobox', { name: 'Source', exact: true }).selectOption('system-a');
  await workspace.getByRole('button', { name: 'Frequencies' }).click();
  await workspace.getByRole('combobox', { name: 'Site', exact: true }).selectOption('site-a');
  await workspace.getByRole('button', { name: 'Review removal of', exact: false }).first().click();
  await page.getByRole('dialog').getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(workspace.getByText('Site changed. Choose it again.')).toBeVisible();
  await expect(workspace.getByRole('combobox', { name: 'Site', exact: true })).toHaveValue('');
  await expect(workspace.getByText('Choose a site to see frequencies.')).toBeVisible();
});
