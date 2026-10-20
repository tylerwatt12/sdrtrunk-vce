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
  test(`Main trunked site tiles show friendly identities and decode quality in ${theme}`, async ({ page }, testInfo) => {
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
    await page.route('**/api/v1/dashboard/main', route => route.fulfill({ json: { data: {
      quality: { current: rows }, directory: { systems: [], channels: [] }
    } } }));
    await page.goto('/app.html?view=dashboard');
    const card = configurationId => page.locator(`.signal-current-tile[data-configuration-id="${configurationId}"]`);
    await expect(page.locator('.signal-current-tile')).toHaveCount(6);
    const remote = card(channelId);
    await expect(remote.getByRole('link', { name: channelName, exact: true })).toHaveAttribute('href',
      new RegExp(`view=channel.*configuration_id=${channelId}`));
    await expect(remote.locator('.signal-current-system')).toHaveText(systemName);
    await expect(remote.locator('.signal-current-context').first()).toHaveText('North');
    await expect(page.locator('.section-title').filter({ hasText: 'Trunked site signal quality' })).toBeVisible();
    await expect(remote.locator('.signal-current-power')).toBeEmpty();
    await expect(remote.locator('.signal-current-power')).toHaveAttribute('aria-hidden', 'true');
    await expect(remote.locator('meter')).toBeHidden();
    await expect(remote.locator('.signal-current-details')).toHaveText('Decode 99.3%');
    await expect(remote).not.toContainText(/No signal|30s avg|dBFS|RFSS|NAC|ago/);
    for (const [configurationId, label, tone] of [[channelId, 'Healthy', 'success'],
      [id(2), 'Degraded', 'warning'], [id(3), 'Poor', 'danger'], [id(4), 'Monitoring', 'neutral']]) {
      const pill = card(configurationId).locator('.signal-state');
      await expect(pill).toHaveText(label);
      if (tone !== 'neutral') await expect(pill).toHaveClass(new RegExp(`ui-pill-${tone}`));
      const cloud = card(configurationId).getByRole('img', { name: 'Received from another receiver.' });
      await expect(cloud.locator('svg use')).toHaveAttribute('href', '#icon-cloud');
      await expect(cloud).toHaveAttribute('data-ui-hint', 'Received from another receiver.');
    }
    await expect(card(id(4)).locator('.signal-current-details')).toHaveText('Decode —');
    await expect(card(id(6))).toHaveCount(0);
    await expect(card(id(7))).toHaveCount(0);
    const localNoRf = card(id(5));
    await expect(localNoRf.locator('.signal-state')).toHaveText('No signal');
    await expect(localNoRf.locator('.signal-state svg')).toHaveCount(0);
    await expect(localNoRf.locator('.signal-current-power strong')).toHaveText('—');
    await expect(localNoRf.locator('.signal-current-power span')).toHaveText('dBFS');
    await expect(localNoRf.locator('.signal-current-details')).toContainText('Decode 99.3%');
    await expect(card(id(8)).locator('.signal-current-power strong')).toHaveText('-51.0');
    await expect(card(id(8)).locator('.signal-current-power span')).toHaveText('dBFS');
    await expect(card(id(8)).locator('.signal-state svg')).toHaveCount(0);
    await expect(card(id(8)).locator('meter')).toHaveJSProperty('value', 99.3);
    await remote.screenshot({ path: testInfo.outputPath(`remote-quality-card-${theme}.png`) });
    await page.locator('.signal-health').screenshot({ path: testInfo.outputPath(`signal-quality-cards-${theme}.png`) });
    expect(fixture.unexpected).toEqual([]);
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('Trunked signal refresh preserves tiles, order, and focused names', async ({ page }) => {
  await page.clock.install({ time: sampleTime });
  const fixture = await installSiteStyleApplication(page, 'light');
  let polls = 0;
  await page.route('**/api/v1/dashboard/main', route => {
    polls++;
    const rows = [qualityRow(id(2), polls === 1 ? 'Zulu site' : 'A renamed site'),
      qualityRow(channelId, channelName, { decode_health_pct: polls === 1 ? 99 : 82 }),
      qualityRow(id(3), 'Conventional quality', { channel_kind: 'CONVENTIONAL' })];
    if (polls > 1) rows.reverse();
    return route.fulfill({ json: { data: { quality: { current: rows },
      directory: { systems: [], channels: [] } } } });
  });
  await page.goto('/app.html?view=dashboard');
  await expect(page.locator('.signal-current-tile')).toHaveCount(2);
  const link = page.locator(`[data-configuration-id="${channelId}"] .signal-current-name a`);
  await link.focus();
  const tile = await link.evaluateHandle(element => element.closest('.signal-current-tile'));
  const order = await page.locator('.signal-current-tile').evaluateAll(elements => elements.map(e => e.dataset.configurationId));
  await page.clock.fastForward(10_100);
  await expect.poll(() => polls).toBeGreaterThan(1);
  await expect(link).toBeFocused();
  expect(await link.evaluate((element, previous) => element.closest('.signal-current-tile') === previous, tile)).toBe(true);
  expect(await page.locator('.signal-current-tile').evaluateAll(elements => elements.map(e => e.dataset.configurationId))).toEqual(order);
  await expect(page.locator(`[data-configuration-id="${channelId}"] .signal-current-details`)).toHaveText('Decode 82.0%');
  await expect(page.getByText('A renamed site', { exact: true })).toBeVisible();
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test('Main refreshes one snapshot and keeps search usable through a failed update', async ({ page }) => {
  await page.clock.install({ time: new Date(sampleTime) });
  const fixture = await installSiteStyleApplication(page);
  const requests = [];
  page.on('request', request => requests.push({ path: new URL(request.url()).pathname,
    query: new URL(request.url()).search, method: request.method() }));
  let failNext = false;
  await page.route('**/api/v1/dashboard/main', route => {
    if (!failNext) return route.fallback();
    failNext = false;
    return route.fulfill({ status: 503, json: { error: { message: 'Update unavailable. Try again shortly.' } } });
  });
  await page.goto('/app.html?view=dashboard');
  await expect(page.locator('.signal-current-tile')).toHaveCount(1);
  const search = page.getByRole('searchbox', { name: 'Search systems, sites, channels, frequencies, protocols, or alias lists' });
  await search.fill('County Public Safety');
  const channel = page.locator('.radio-directory-site-row').getByRole('link', { name: channelName, exact: true });
  await expect(channel).toBeVisible();
  await expect(channel).toHaveAttribute('href', new RegExp(`view=channel.*configuration_id=${channelId}`));
  const snapshots = () => requests.filter(request => request.path === '/api/v1/dashboard/main').length;
  expect(snapshots()).toBe(1);
  failNext = true;
  await page.clock.runFor(5_100);
  await expect(page.locator('.signal-health-summary')).toContainText('Signal quality update failed');
  await expect(channel).toBeVisible();
  await expect(search).toHaveValue('County Public Safety');
  await expect(search).toBeFocused();
  await page.clock.runFor(5_100);
  await expect(page.locator('.signal-health-summary')).toContainText('1 sites reporting');
  expect(snapshots()).toBe(3);
  expect(requests.filter(request => ['/api/v1/quality', '/api/v1/channel-catalog',
    '/api/v1/radio-systems', '/api/v1/activity/message-types', '/api/v1/dashboard'].includes(request.path))).toEqual([]);
  expect(requests.filter(request => request.method === 'HEAD')).toEqual([]);
  expect(requests.filter(request => /\/(?:alerts|listen-map|remote-links|streaming|retained-statistics|radioreference-import|spectrum-search|application-log|web-diagnostic-log|paging-tone-detection|channel-setup-guide)\.js$/.test(request.path)))
    .toEqual([]);
  expect(requests.filter(request => request.path.endsWith('/recordings.js'))).toHaveLength(1);
  await channel.click();
  await expect(page.locator('body')).toHaveAttribute('data-view', 'channel');
  const before = snapshots();
  await page.clock.runFor(10_100);
  expect(snapshots()).toBe(before);
  expect(fixture.unexpected).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
});

test('Dashboard-only visitors load signal quality without radio directory access', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page);
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
    configured: true, authenticated: false, capabilities: { dashboard: true, radio: false }
  } } }));
  await page.route('**/api/v1/dashboard/main', route => route.fulfill({ json: { data: {
    quality: { current: [qualityRow(channelId, channelName)] }
  } } }));
  await page.goto('/app.html?view=dashboard');
  await expect(page.locator('.signal-current-tile')).toHaveCount(1);
  await expect(page.locator('.signal-health-summary')).toContainText('1 sites reporting');
  await expect(page.locator('.radio-directory-panel-body')).toHaveCount(0);
  await expect(page.locator('#content > .error')).toHaveCount(0);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});
