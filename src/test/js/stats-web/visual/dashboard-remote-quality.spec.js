'use strict';

const { expect, test } = require('@playwright/test');
const { installSiteStyleApplication, channelId, channelName, systemName, systemKey } =
  require('./fixtures/site-style-app.cjs');

const sampleTime = Date.parse('2026-10-01T12:00:00Z');
const id = number => `${String(number).padStart(8, '0')}-1111-4111-8111-111111111111`;
const qualityRow = (configurationId, name, overrides = {}) => ({
  configuration_id: configurationId, name, site_name: 'North', system_name: systemName,
  protocol: 'P25', channel_kind: 'TRUNKED', radio_system_key: systemKey,
  entity_ref: { kind: 'channel', key: configurationId }, quality_frequency_hz: 851_012_500,
  last_observed_ms: sampleTime, signal_dbfs: null, average_signal_dbfs: null,
  decode_health_pct: 99.3, remote_origin: { remote: true }, ...overrides
});

for (const [theme, viewport] of [['light', { width: 1280, height: 900 }],
  ['dark', { width: 390, height: 844 }]]) {
  test(`Dashboard remote quality cards use decode quality in ${theme}`, async ({ page }, testInfo) => {
    const fixture = await installSiteStyleApplication(page, theme);
    await page.setViewportSize(viewport);
    const rows = [qualityRow(channelId, channelName),
      qualityRow(id(2), 'Remote degraded', { decode_health_pct: 75 }),
      qualityRow(id(3), 'Remote poor', { decode_health_pct: 74.9 }),
      qualityRow(id(4), 'Remote monitoring', { decode_health_pct: null }),
      qualityRow(id(5), 'Local no RF', { remote_origin: null }),
      qualityRow(id(6), 'Remote stale', { last_observed_ms: sampleTime - 45_001 }),
      qualityRow(id(7), 'Remote no samples', { last_observed_ms: null }),
      qualityRow(id(8), 'Local control', { remote_origin: null, signal_dbfs: -51,
        average_signal_dbfs: -52 })];
    await page.route('**/api/v1/quality?**', route => route.fulfill({ json: { data: {
      rows, total_count: rows.length, limit: 60, offset: 0, has_more: false, next_offset: null
    } } }));
    await page.goto('/app.html?view=dashboard');
    const card = configurationId => page.locator(`.signal-current-tile[data-configuration-id="${configurationId}"]`);
    await expect(page.locator('.signal-current-tile')).toHaveCount(6);
    const remote = card(channelId);
    await expect(remote.getByRole('link', { name: channelName, exact: true })).toHaveAttribute('href',
      new RegExp(`view=channel.*configuration_id=${channelId}`));
    await expect(remote.locator('.signal-current-system')).toHaveText(systemName);
    await expect(remote.locator('.identity-summary-context')).toHaveText('North');
    await expect(remote.locator('.signal-current-power strong')).toHaveText('99.3%');
    await expect(remote.locator('.signal-current-power span')).toHaveText('Decode quality');
    await expect(remote.locator('.signal-current-details')).toContainText('851.01250 MHz');
    await expect(remote.locator('.signal-current-details')).not.toContainText('Decode');
    await expect(remote).not.toContainText(/No signal|30s avg|dBFS/);
    await expect(remote.locator('.signal-state')).toHaveText('Healthy');
    await expect(remote.locator('.signal-state')).toHaveClass(/ui-pill-success/);
    await expect(card(id(2)).locator('.signal-state')).toHaveText('Degraded');
    await expect(card(id(2)).locator('.signal-state')).toHaveClass(/ui-pill-warning/);
    await expect(card(id(3)).locator('.signal-state')).toHaveText('Poor');
    await expect(card(id(3)).locator('.signal-state')).toHaveClass(/ui-pill-danger/);
    await expect(card(id(4)).locator('.signal-state')).toHaveText('Monitoring');
    await expect(card(id(4)).locator('.signal-state')).not.toHaveClass(/ui-pill-(success|warning|danger)/);
    await expect(card(id(4)).locator('.signal-current-power strong')).toHaveText('—');
    await expect(card(id(6))).toHaveCount(0);
    await expect(card(id(7))).toHaveCount(0);
    const localNoRf = card(id(5));
    await expect(localNoRf.locator('.signal-state')).toHaveText('No signal');
    await expect(localNoRf.locator('.signal-current-power strong')).toHaveText('—');
    await expect(localNoRf.locator('.signal-current-power span')).toHaveText('30s avg —');
    await expect(localNoRf.locator('.signal-current-details')).toContainText('Decode 99.3%');
    await expect(card(id(8)).locator('.signal-current-power strong')).toHaveText('-51.0 dBFS');
    await expect(card(id(8)).locator('.signal-current-power span')).toHaveText('30s avg -52.0 dBFS');
    await remote.screenshot({ path: testInfo.outputPath(`remote-quality-card-${theme}.png`) });
    await page.locator('.signal-health').screenshot({ path: testInfo.outputPath(`signal-quality-cards-${theme}.png`) });
    expect(fixture.unexpected).toEqual([]);
    expect(fixture.pageErrors).toEqual([]);
  });
}
