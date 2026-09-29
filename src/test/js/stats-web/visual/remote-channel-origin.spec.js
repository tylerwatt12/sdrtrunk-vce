'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

const channel = (configurationId, name, site, remote) => ({
  configuration_id: configurationId,
  protocol_id: 'p25-phase1', protocol_label: 'P25 Phase 1', channel_kind: 'TRUNKED',
  system: 'Metro', site, name, frequencies_hz: [851_012_500],
  processing_state: 'RUNNING', auto_start_order: 1,
  alias_list_id: 1, alias_list_name: 'Metro P25', editable: true,
  remote_origin: remote ? { remote: true } : null
});

const catalog = {
  revision: 1,
  channels: [channel('remote-control', 'North Control', 'North', true),
    channel('local-control', 'West Control', 'West', false)]
};

async function openApp(page, view, theme = 'light') {
  const preferences = structuredClone(defaultPreferences);
  preferences.appearance.theme = theme;
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    const respond = (data) => route.fulfill({ json: { data } });
    if (pathname === '/api/v1/auth/session') {
      await respond({ configured: true, authenticated: true, username: 'operator', tier: 'admin',
        primary: true, capabilities: { dashboard: true, radio: true, 'admin-channels': true } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } });
    } else if (pathname === '/api/v1/status') {
      await respond({ stats_logging: { summary_configured: true, summary_active: true,
        detailed_history_configured: false, detailed_history_active: false }, database: {} });
    } else if (pathname === '/api/v1/quality') {
      await respond({ rows: [], total_count: 0, limit: 60, offset: 0,
        has_more: false, next_offset: null });
    } else if (pathname === '/api/v1/channel-catalog' || pathname === '/api/v1/admin/channels') {
      await respond(catalog);
    } else if (pathname === '/api/v1/radio-systems') {
      await respond({ rows: [], total_count: 0, limit: 25, offset: 0,
        has_more: false, next_offset: null });
    } else if (pathname === '/api/v1/admin/channels/protocols') {
      await respond({ profiles: [] });
    } else if (pathname === '/api/v1/admin/channels/options') {
      await respond({ revision: 1, alias_lists: [], tuners: [] });
    } else {
      await route.fulfill({ status: 404, json: { error: { message: 'Unavailable' } } });
    }
  });
  await page.goto(`/app.html?view=${view}`);
}

test('Dashboard Main marks a remote site even when no signal samples exist', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openApp(page, 'dashboard');
  const remote = page.locator('.radio-directory-site-row').filter({
    has: page.getByRole('link', { name: 'North Control' })
  });
  const local = page.locator('.radio-directory-site-row').filter({
    has: page.getByRole('link', { name: 'West Control' })
  });
  await expect(remote).toBeVisible();
  await expect(local).toBeVisible();
  await expect(page.locator('.signal-current-tile')).toHaveCount(0);
  await expect(remote.getByLabel('Remote channel; depends on another receiver')).toHaveText('Remote');
  await expect(remote.locator('.channel-origin-line svg')).toHaveCount(1);
  await expect(local.locator('.channel-origin-line')).toHaveCount(0);
  await expect(page.locator('.radio-directory-system-header .channel-origin-line')).toHaveCount(0);
  await expect(remote).toHaveScreenshot('dashboard-remote-site-light.png');
});

test('Channels marks only remote configurations with the same cue', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openApp(page, 'channel-setup', 'dark');
  const remote = page.locator('tr').filter({ has: page.getByRole('button', { name: 'North Control' }) });
  const local = page.locator('tr').filter({ has: page.getByRole('button', { name: 'West Control' }) });
  await expect(remote).toBeVisible();
  await expect(local).toBeVisible();
  await expect(remote.getByLabel('Remote channel; depends on another receiver')).toHaveText('Remote');
  await expect(local.locator('.channel-origin-line')).toHaveCount(0);
  await expect(remote.locator('.channel-origin-line')).toHaveScreenshot('channels-remote-name-dark.png');
});
