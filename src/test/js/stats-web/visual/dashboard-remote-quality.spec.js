'use strict';

const { expect, test } = require('@playwright/test');
const { installSiteStyleApplication, channelId, channelName, systemName, systemKey } =
  require('./fixtures/site-style-app.cjs');

const sampleTime = Date.parse('2026-10-01T12:00:00Z');
const id = number => `${String(number).padStart(8, '0')}-1111-4111-8111-111111111111`;
const qualityRow = (configurationId, name, overrides = {}) => ({
  configuration_id: configurationId, name, site_name: 'North', system_name: systemName,
  protocol: 'P25', channel_kind: 'TRUNKED', radio_system_key: systemKey,
  last_observed_ms: sampleTime, signal_dbfs: null, average_signal_dbfs: null,
  decode_health_pct: 99.3, remote_origin: { remote: true }, ...overrides
});
const directoryFor = rows => ({ systems: [{ radio_system_key: systemKey,
  entity_ref: { kind: 'radio_system', key: systemKey }, system_name: systemName, protocol: 'P25',
  children: rows.filter(row => row.channel_kind === 'TRUNKED').map(row => ({ ...row,
    processing_state: row.processing_state || 'RUNNING', display_frequency_hz: 851_012_500, display_frequency_source: 'active-control' }))
}], channels: [] });
const site = (page, configurationId) => page.locator(`[data-directory-row-key="${configurationId}"]`);

for (const [theme, viewport] of [['light', { width: 1280, height: 900 }],
  ['dark', { width: 390, height: 844 }]]) {
  test(`Main grouped rows integrate real quality readings in ${theme}`, async ({ page }, testInfo) => {
    const fixture = await installSiteStyleApplication(page, theme);
    await page.setViewportSize(viewport);
    const rows = [qualityRow(channelId, channelName),
      qualityRow(id(2), 'Remote degraded', { decode_health_pct: 75 }),
      qualityRow(id(3), 'Remote poor', { decode_health_pct: 74.9 }),
      qualityRow(id(4), 'Remote monitoring', { decode_health_pct: null }),
      qualityRow(id(5), 'Local signal reading unavailable', { remote_origin: null }),
      qualityRow(id(6), 'Remote stale', { last_observed_ms: sampleTime - 45_001 }),
      qualityRow(id(7), 'Remote no samples', { last_observed_ms: null }),
      qualityRow(id(8), 'Local control', { remote_origin: null, signal_dbfs: -51,
        average_signal_dbfs: -52 }),
      qualityRow(id(9), 'Stopped control', { processing_state: 'STOPPED' }),
      qualityRow(id(11), 'Local quality unknown', { remote_origin: null, decode_health_pct: null })];
    const directory = directoryFor(rows);
    directory.channels.push({ configuration_id: id(10), name: 'Fire Dispatch',
      system_name: 'Local channels', protocol: 'NBFM', frequencies_hz: [154_430_000], processing_state: 'STOPPED' });
    await page.route('**/api/v1/dashboard/main', route => route.fulfill({ json: { data: {
      quality: { current: rows }, directory
    } } }));
    await page.goto('/app.html?view=dashboard');
    await expect(page.locator('.radio-directory-system-card')).toHaveCount(1);
    await expect(page.locator('.radio-directory-site-row')).toHaveCount(9);
    await expect(page.getByText('Trunked site signal quality', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Export signal health as CSV', exact: true })).toBeVisible();
    const remote = site(page, channelId);
    await expect(remote.getByRole('link', { name: channelName, exact: true })).toHaveAttribute('href',
      new RegExp(`view=channel.*configuration_id=${channelId}`));
    await expect(page.locator('.radio-directory-system-name')).toHaveText(systemName);
    await expect(remote.getByRole('group', { name: 'Current control', exact: true })).toContainText('851.01250 MHz');
    await expect(remote.getByRole('group', { name: 'Signal', exact: true })).toContainText('—Remote site');
    await expect(remote).not.toContainText(/No signal|30s avg|dBFS|RFSS|NAC/);
    await expect(remote.locator('meter')).toBeVisible();
    await expect(remote.locator('meter')).toHaveJSProperty('value', 99.3);
    for (const [configurationId, label, tone] of [[channelId, 'Healthy', 'success'],
      [id(2), 'Degraded', 'warning'], [id(3), 'Poor', 'danger'], [id(4), 'Monitoring', 'neutral']]) {
      const row = site(page, configurationId);
      await expect(row.locator('.radio-directory-quality-state')).toHaveText(label);
      if (tone !== 'neutral') await expect(row.locator('.radio-directory-quality-state')).toHaveClass(new RegExp(`ui-pill-${tone}`));
      const cloud = row.getByRole('img', { name: 'Received from another receiver.' });
      await expect(cloud.locator('svg use')).toHaveAttribute('href', '#icon-cloud');
      await expect(row.locator('.radio-directory-row-context')).toContainText('Remote');
    }
    await expect(site(page, id(4)).locator('meter')).toBeHidden();
    await expect(site(page, id(6)).locator('.radio-directory-quality-state')).toHaveText('Out of date');
    await expect(site(page, id(6)).locator('meter')).toBeHidden();
    await expect(site(page, id(7)).locator('.radio-directory-quality-state')).toHaveText('No samples');
    await expect(site(page, id(5)).locator('.radio-directory-quality-state')).toHaveText('Healthy');
    await expect(site(page, id(5)).getByRole('group', { name: 'Signal', exact: true })).toContainText('—No samples');
    await expect(site(page, id(11)).locator('.radio-directory-quality-state')).toHaveText('Unknown');
    await expect(site(page, id(11)).locator('.radio-directory-quality-state')).toHaveClass(/ui-pill-neutral/);
    await expect(site(page, id(11)).locator('meter')).toBeHidden();
    const local = site(page, id(8));
    await expect(local.getByRole('group', { name: 'Signal', exact: true })).toContainText('-51.0 dBFS');
    await expect(local).toContainText('30s avg -52.0');
    await expect(local.locator('meter')).toHaveJSProperty('value', 99.3);
    await page.getByRole('button', { name: 'All', exact: true }).click();
    await expect(site(page, id(9)).locator('.radio-directory-quality-state')).toHaveText('Stopped');
    await expect(site(page, id(9)).locator('.radio-directory-frequency')).toContainText('Unavailable');
    await expect(site(page, id(9)).locator('meter')).toBeHidden();
    await expect(site(page, id(10)).locator('.radio-directory-frequency')).toContainText('154.43000 MHz');
    await expect(site(page, id(10)).locator('.radio-directory-live-state')).toHaveText('Stopped');
    await page.getByRole('button', { name: 'Running', exact: true }).click();
    const geometry = await local.evaluate(row => {
      const bounds = selector => row.querySelector(selector).getBoundingClientRect().toJSON();
      return { identity: bounds('.radio-directory-item-identity'), control: bounds('.radio-directory-frequency'),
        signal: bounds('[data-directory-field="signal"]'), decode: bounds('[data-directory-field="decode"]') };
    });
    if (viewport.width < 620) {
      expect(geometry.identity.bottom).toBeLessThanOrEqual(geometry.control.y);
      expect(geometry.control.x).toBeLessThan(geometry.signal.x);
      expect(geometry.signal.x).toBeLessThan(geometry.decode.x);
    } else expect(geometry.identity.x).toBeLessThan(geometry.control.x);
    for (const width of [viewport.width, 320]) {
      await page.setViewportSize({ width, height: viewport.height });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      expect(await page.locator('.radio-directory-system-card').evaluateAll(cards =>
        cards.every(card => card.scrollWidth <= card.clientWidth))).toBe(true);
    }
    await page.setViewportSize(viewport);
    await page.locator('.radio-directory-panel-body').screenshot({ path: testInfo.outputPath(`grouped-main-${theme}.png`) });
    expect(fixture.unexpected).toEqual([]);
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('Grouped quality refresh preserves rows, order, and focused names', async ({ page }) => {
  await page.clock.install({ time: sampleTime });
  const fixture = await installSiteStyleApplication(page);
  let polls = 0;
  await page.route('**/api/v1/dashboard/main', route => {
    polls++;
    const rows = [qualityRow(id(2), polls === 1 ? 'Zulu site' : 'A renamed site'),
      qualityRow(channelId, channelName, { decode_health_pct: polls === 1 ? 99 : 82 })];
    if (polls > 1) rows.reverse();
    return route.fulfill({ json: { data: { quality: { current: rows }, directory: directoryFor(rows) } } });
  });
  await page.goto('/app.html?view=dashboard');
  const row = site(page, channelId);
  const link = row.getByRole('link', { name: channelName, exact: true });
  await link.focus();
  const prior = await row.elementHandle();
  const order = await page.locator('.radio-directory-site-row').evaluateAll(rows => rows.map(row => row.dataset.directoryRowKey));
  await page.clock.fastForward(10_100);
  await expect.poll(() => polls).toBeGreaterThan(1);
  await expect(link).toBeFocused();
  expect(await row.evaluate((row, previous) => row === previous, prior)).toBe(true);
  expect(await page.locator('.radio-directory-site-row').evaluateAll(rows => rows.map(row => row.dataset.directoryRowKey))).toEqual(order);
  await expect(row.locator('[data-directory-field="decode"]')).toContainText('82.0%');
  await expect(row.locator('meter')).toHaveJSProperty('value', 82);
  await expect(page.getByText('A renamed site', { exact: true })).toBeVisible();
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test('Main keeps one snapshot, usable search, and retained rows after a failed update', async ({ page }) => {
  await page.clock.install({ time: sampleTime });
  const fixture = await installSiteStyleApplication(page);
  const requests = [];
  page.on('request', request => requests.push({ path: new URL(request.url()).pathname, method: request.method() }));
  let failNext = false;
  await page.route('**/api/v1/dashboard/main', route => {
    if (!failNext) return route.fallback();
    failNext = false;
    return route.fulfill({ status: 503, json: { error: { message: 'Update unavailable.' } } });
  });
  await page.goto('/app.html?view=dashboard');
  const search = page.getByRole('searchbox', { name: 'Search systems, sites, channels, frequencies, protocols, or alias lists' });
  await search.fill('County Public Safety');
  const link = site(page, channelId).getByRole('link', { name: channelName, exact: true });
  await expect(link).toBeVisible();
  const snapshots = () => requests.filter(request => request.path === '/api/v1/dashboard/main').length;
  expect(snapshots()).toBe(1);
  failNext = true;
  await page.clock.runFor(5_100);
  await expect(page.getByRole('status').filter({ hasText: 'Systems and channels could not be updated.' })).toBeVisible();
  await expect(link).toBeVisible();
  await expect(search).toHaveValue('County Public Safety');
  await expect(search).toBeFocused();
  await page.clock.runFor(5_100);
  await expect(page.getByRole('status').filter({ hasText: 'Systems and channels could not be updated.' })).toBeHidden();
  expect(snapshots()).toBe(3);
  expect(requests.filter(request => ['/api/v1/quality', '/api/v1/channel-catalog', '/api/v1/radio-systems',
    '/api/v1/activity/message-types', '/api/v1/dashboard'].includes(request.path))).toEqual([]);
  expect(requests.filter(request => request.method === 'HEAD')).toEqual([]);
  await link.click();
  await expect(page.locator('body')).toHaveAttribute('data-view', 'channel');
  const before = snapshots();
  await page.clock.runFor(10_100);
  expect(snapshots()).toBe(before);
  expect(fixture.unexpected).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
});

test('Dashboard-only visitors get unlinked grouped quality without privileged directory facts', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page);
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
    configured: true, authenticated: false, capabilities: { dashboard: true, radio: false, live: true }
  } } }));
  await page.route('**/api/v1/dashboard/main', route => route.fulfill({ json: { data: {
    quality: { current: [qualityRow(channelId, channelName)] }
  } } }));
  await page.goto('/app.html?view=dashboard');
  const row = site(page, channelId);
  await expect(row).toContainText(channelName);
  await expect(row).toContainText('99.3%');
  await expect(row).toContainText('Status unavailable');
  await expect(row.locator('.radio-directory-frequency')).toContainText('Unavailable');
  await expect(page.locator('.radio-directory-system-card a')).toHaveCount(0);
  await expect(page.getByRole('group', { name: 'Filter radio directory', exact: true })).toHaveCount(0);
  await expect(page.locator('.radio-directory-local').getByRole('link', { name: /Live/ })).toHaveCount(0);
  await expect(page.locator('#content > .error')).toHaveCount(0);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});
