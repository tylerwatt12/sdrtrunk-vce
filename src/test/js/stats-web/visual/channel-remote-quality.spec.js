'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

const remoteId = '11111111-1111-4111-8111-111111111111';
const localId = '22222222-2222-4222-8222-222222222222';
const sampleTime = Date.UTC(2026, 8, 28, 12);

async function openQuality(page, configurationId, { samples = false, theme = 'light' } = {}) {
  let qualityRequests = 0;
  const channel = {
    configuration_id: configurationId, channel_kind: 'TRUNKED', protocol: 'P25',
    name: configurationId === remoteId ? 'North Control' : 'West Control',
    site_name: configurationId === remoteId ? 'North' : 'West',
    capabilities: { quality: true }
  };
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    const respond = (data) => route.fulfill({ json: { data } });
    if (pathname === '/api/v1/auth/session') {
      await respond({ configured: true, authenticated: true, username: 'operator', tier: 'admin',
        primary: true, capabilities: { radio: true, 'csv-export': true } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences: {
        ...defaultPreferences, appearance: { theme }
      } } });
    } else if (pathname === '/api/v1/status') {
      await respond({ stats_logging: { summary_configured: true, summary_active: true }, database: {} });
    } else if (pathname === `/api/v1/channels/${configurationId}`) {
      await respond({ channel });
    } else if (pathname === '/api/v1/channel-catalog') {
      await respond({ revision: 1, channels: [
        { configuration_id: remoteId, remote_origin: { remote: true } },
        { configuration_id: localId, remote_origin: null }
      ] });
    } else if (pathname === `/api/v1/channels/${configurationId}/quality`) {
      qualityRequests += 1;
      const series = Array.from({ length: 289 }, (_, index) => ({
        time_ms: sampleTime - 86_400_000 + index * 300_000,
        last_observed_ms: sampleTime - 86_400_000 + index * 300_000,
        decode_health_pct: index === 288 ? 97.3 : Math.max(55, Math.min(99,
          91 + 6 * Math.sin(index / 17) - (index > 160 && index < 190 ? 25 : 0))),
        average_signal_dbfs: null, minimum_signal_dbfs: null, maximum_signal_dbfs: null,
        sample_count: 1, frequency_hz: 851_012_500
      }));
      await respond({ from_ms: sampleTime - 86_400_000, to_ms: sampleTime, bucket_ms: 300_000,
        rows: samples ? [{ configuration_id: configurationId, name: channel.name,
          decode_health_pct: 97.3, signal_dbfs: null, average_signal_dbfs: null,
          last_observed_ms: sampleTime - 30_000, series }] : [] });
    } else {
      await route.fulfill({ status: 404, json: { error: { message: 'Unavailable' } } });
    }
  });
  await page.goto(`/app.html?view=channel&configuration_id=${configurationId}&tab=quality`);
  return { qualityRequests: () => qualityRequests };
}

test('remote channel Quality keeps decode history without RF signal presentation', async ({ page }) => {
  await page.clock.install({ time: new Date(sampleTime) });
  await page.setViewportSize({ width: 1280, height: 900 });
  const requests = await openQuality(page, remoteId, { samples: true });
  const quality = page.locator('.site-signal-history-section');
  await expect(quality).toContainText('Decode quality reflects frames decoded from the remote bits');
  await expect(quality).toContainText('97.3%');
  await expect(quality.locator('.signal-range-controls')).toHaveCount(1);
  await expect(quality.getByRole('link', { name: /Export/ })).toHaveCount(1);
  await expect(quality.locator('.decode-chart')).toHaveCount(1);
  await expect(quality.locator('.decode-health-path')).toHaveCount(1);
  await expect(quality.locator('.signal-chart')).toHaveCount(0);
  await expect(quality.locator('.signal-history-overview .ui-metric')).toHaveCount(2);
  expect(requests.qualityRequests()).toBe(1);
  await expect(quality).toHaveScreenshot('remote-channel-quality-light.png');

  await quality.locator('.decode-chart .chart-hover-surface').hover();
  await expect(quality.getByRole('tooltip')).toContainText('Decode health:');
  await expect(quality.getByRole('tooltip')).not.toContainText('signal');
  await quality.getByRole('button', { name: '1 hour' }).click();
  await expect(quality.getByRole('link', { name: /Export/ })).toHaveAttribute('href', /range=1h/);
  await expect.poll(requests.qualityRequests).toBe(2);
});

test('remote decode history fits dark mobile without RF fields', async ({ page }) => {
  await page.clock.install({ time: new Date(sampleTime) });
  await page.setViewportSize({ width: 390, height: 844 });
  await openQuality(page, remoteId, { samples: true, theme: 'dark' });
  const quality = page.locator('.site-signal-history-section');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await expect(quality.locator('.signal-chart')).toHaveCount(0);
  await expect(quality).toHaveScreenshot('remote-channel-quality-dark-mobile.png');
});

test('remote channel Quality explains when decode samples are not yet retained', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const requests = await openQuality(page, remoteId);
  const quality = page.locator('.site-signal-history-section');
  await expect(quality).toContainText('No remote decode quality samples are available in the selected 24h range');
  await expect(quality.locator('.signal-range-controls')).toHaveCount(1);
  await expect(quality.getByRole('link', { name: /Export/ })).toHaveCount(1);
  expect(requests.qualityRequests()).toBe(1);
});

test('local channel Quality retains its existing empty-history state', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const requests = await openQuality(page, localId);
  const quality = page.locator('.site-signal-history-section');
  await expect(quality).toContainText('No retained control channel quality samples are available');
  await expect(quality.locator('.signal-range-controls')).toHaveCount(1);
  expect(requests.qualityRequests()).toBe(1);
});
