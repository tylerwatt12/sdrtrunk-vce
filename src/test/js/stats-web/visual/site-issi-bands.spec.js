'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { installSiteStyleApplication, systemKey, channelId } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const foreignKey = 'p25:00002:002';
const foreignName = 'Regional Public Safety and Mutual Aid Communications ' + 'LongForeignSystemName'.repeat(4);
const sourceName = 'Central County Simulcast and Emergency Services ' + 'LongObservingSiteName'.repeat(4);
const siteName = 'Central Valley Public Safety Coverage Area';
const now = Date.parse('2026-10-01T12:00:00Z');
const channel = {
  configuration_id: channelId, protocol: 'P25', protocol_id: 'p25-phase1', channel_kind: 'TRUNKED',
  name: sourceName, site_name: siteName, system_name: 'County Public Safety',
  radio_system_key: systemKey, radio_system_entity_ref: { kind: 'radio_system', key: systemKey },
  entity_ref: { kind: 'channel', key: channelId }, rfss: 1, site_id: 27,
  capabilities: { channels: true, frequency_bands: true }, frequencies_hz: [851012500]
};
const band = {
  foreign_wacn: 2, foreign_system_id: 2, foreign_system_name: foreignName,
  foreign_system_entity_ref: { kind: 'radio_system', key: foreignKey },
  access_mode: 'FDMA', base_hz: 851000000, spacing_hz: 12500, bandwidth_hz: 12500,
  transmit_offset_hz: -45000000, timeslots: 1, voice_rate: 'FULL_RATE', state: 'CURRENT',
  observation_count: 7, last_seen_ms: now,
  observed_on: { protocol: 'P25', configuration_id: channelId, name: sourceName, site_name: siteName,
    rfss: 1, site_id: 27, entity_ref: { kind: 'channel', key: channelId } }
};
const siteRoute = `/app.html?view=channel&configuration_id=${channelId}&tab=frequencies&offset=25`;
const bandTable = page => page.locator('table[data-table-type="issi-frequency-bands.channel"]');
const bandSection = page => page.locator('#content > .ui-section').filter({
  has: page.locator('.ui-section-title').filter({ hasText: /^ISSI Advertised Band Plans/ })
});

async function installBands(page, theme) {
  const fixture = await installSiteStyleApplication(page, theme);
  const state = { mode: 'populated', issiRequests: [], frequencyRequests: [] };
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url());
    const pathname = decodeURIComponent(url.pathname);
    const respond = data => route.fulfill({ json: { data } });
    if (pathname === `/api/v1/channels/${channelId}`) return respond({ channel });
    if (pathname === `/api/v1/channels/${channelId}/frequency-bands`) return respond({
      band_source: 'OTA', home_bands: [{ band: 0, base_hz: 851000000, spacing_hz: 12500,
        bandwidth_hz: 12500, transmit_offset_hz: -45000000, tdma: false, timeslots: 1,
        state: 'CURRENT', observation_count: 8, last_seen_ms: now }]
    });
    if (pathname === `/api/v1/channels/${channelId}/frequencies`) {
      state.frequencyRequests.push(Object.fromEntries(url.searchParams));
      return respond({ rows: [{ descriptor: '0-001', downlink_hz: 851012500, uplink_hz: 806012500,
        tdma: false, timeslots: 1, state: 'CURRENT', voice_grant_observations: 9,
        data_grant_observations: 0, last_seen_ms: now }],
      limit: 100, offset: 25, total_count: 26, has_more: false });
    }
    if (pathname === `/api/v1/radio-systems/${systemKey}/issi/frequency-bands`) {
      state.issiRequests.push(Object.fromEntries(url.searchParams));
      if (state.mode === 'failure') return route.fulfill({ status: 503,
        json: { error: { status: 503, message: 'Foreign band plans temporarily unavailable' } } });
      const offset = Number(url.searchParams.get('offset') || 0);
      // A small server page exercises real pagination without filling the fixture with duplicate rows.
      const rows = state.mode === 'empty' ? [] : [0, 1, 2].slice(offset, offset + 2)
        .map(identifier => ({ ...band, band: identifier }));
      return respond({ rows, limit: 2, offset, total_count: state.mode === 'empty' ? 0 : 3,
        has_more: state.mode !== 'empty' && offset === 0,
        next_offset: state.mode !== 'empty' && offset === 0 ? 2 : null });
    }
    return route.fallback();
  });
  return { fixture, state };
}

for (const presentation of [
  { name: 'light-desktop', theme: 'light', viewport: { width: 1600, height: 1000 } },
  { name: 'dark-phone', theme: 'dark', viewport: { width: 390, height: 844 } }
]) {
  test(`site ISSI bands preserve long names, source links and independent pages ${presentation.name}`, async ({ page }) => {
    test.setTimeout(15000);
    await page.setViewportSize(presentation.viewport);
    const { fixture, state } = await installBands(page, presentation.theme);
    await page.goto(siteRoute);
    await expect(bandTable(page)).toBeVisible();
    await expect(page.locator('.channel-band-plan-column')).toContainText('851.00000');
    await expect(page.locator('.channel-frequency-column')).toContainText('0-001');
    await expect(bandTable(page)).toContainText(foreignName);
    await expect(bandTable(page)).toContainText(sourceName);
    await expect(bandTable(page)).toContainText('RFSS 01');
    await expect(bandTable(page)).toContainText('Site 1B');
    await expect(bandTable(page).locator('th').filter({ hasText: /^Heard On$/ })).toHaveCount(1);
    const sourceLink = bandTable(page).getByRole('link', { name: sourceName, exact: true }).first();
    const sourceTarget = new URL(await sourceLink.getAttribute('href'), page.url());
    expect(sourceTarget.searchParams.get('view')).toBe('channel');
    expect(sourceTarget.searchParams.get('configuration_id')).toBe(channelId);
    const foreignTarget = new URL(await bandTable(page).getByRole('link', {
      name: foreignName, exact: true }).first().getAttribute('href'), page.url());
    expect(foreignTarget.searchParams.get('radio_system_key')).toBe(foreignKey);
    expect(state.issiRequests[0]).toEqual({ configuration_id: channelId,
      offset: '0', limit: '100', sort: 'identity', direction: 'asc' });

    const geometry = await page.evaluate(() => {
      const grid = document.querySelector('.channel-frequency-layout');
      const foreign = document.querySelector('table[data-table-type="issi-frequency-bands.channel"]').closest('.ui-section');
      const rect = element => {
        const { x, y, width, height } = element.getBoundingClientRect();
        return { x, y, width, height };
      };
      return { grid: rect(grid), foreign: rect(foreign), parent: foreign.parentElement.id,
        frequencies: rect(grid.querySelector('.channel-frequency-column')),
        home: rect(grid.querySelector('.channel-band-plan-column')),
        pager: rect(foreign.querySelector('.ui-browse-pager-actions')),
        dock: rect(document.querySelector('#audio-dock')),
        document: { viewportHeight: document.documentElement.clientHeight,
          scrollHeight: document.documentElement.scrollHeight,
          scrollPaddingBottom: getComputedStyle(document.documentElement).scrollPaddingBottom },
        content: { scrollHeight: document.querySelector('#content').scrollHeight,
          paddingBottom: getComputedStyle(document.querySelector('#content')).paddingBottom,
          afterHeight: getComputedStyle(document.querySelector('#content'), '::after').height },
        audioDockHeight: getComputedStyle(document.documentElement).getPropertyValue('--audio-dock-height') };
    });
    expect(geometry.parent).toBe('content');
    expect(geometry.foreign.y).toBeGreaterThanOrEqual(geometry.grid.y + geometry.grid.height - 1);
    expect(geometry.foreign.width).toBeCloseTo(geometry.grid.width, 0);
    if (presentation.viewport.width > 900) {
      expect(geometry.frequencies.width).toBeGreaterThan(geometry.grid.width * 0.45);
      expect(geometry.home.width).toBeCloseTo(geometry.frequencies.width, 0);
      expect(geometry.home.x).toBeGreaterThan(geometry.frequencies.x);
    } else {
      expect(geometry.frequencies.width).toBeCloseTo(geometry.grid.width, 0);
      expect(geometry.home.width).toBeCloseTo(geometry.grid.width, 0);
    }
    await expectNoHorizontalOverflow(page);
    if (process.env.VCE_SITE_ISSI_REVIEW_DIR) {
      fs.writeFileSync(path.join(process.env.VCE_SITE_ISSI_REVIEW_DIR, `${presentation.name}-geometry.json`),
        JSON.stringify(geometry, null, 2));
      await page.screenshot({
        path: path.join(process.env.VCE_SITE_ISSI_REVIEW_DIR, `${presentation.name}.png`), fullPage: true
      });
    }
    const urlBeforePaging = page.url();
    const frequencyRequestsBeforePaging = state.frequencyRequests.length;
    await bandSection(page).getByRole('button', { name: 'Next', exact: true }).click({ timeout: 3000 });
    await expect.poll(() => state.issiRequests.length).toBe(2);
    await expect(bandTable(page).locator('tbody tr')).toHaveCount(1);
    expect(state.issiRequests[1]).toEqual({ configuration_id: channelId,
      offset: '2', limit: '100', sort: 'identity', direction: 'asc' });
    expect(state.frequencyRequests.length).toBe(frequencyRequestsBeforePaging);
    expect(page.url()).toBe(urlBeforePaging);
    await expect(page.locator('.channel-frequency-column')).toContainText('0-001');
    await expectNoHorizontalOverflow(page);
    if (presentation.viewport.width > 900) {
      const player = page.locator('#audio-dock');
      const sizeHandle = () => player.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible');
      await sizeHandle().press('Home');
      await expect(player).toHaveAttribute('data-state', 'collapsed');
      await bandSection(page).getByRole('button', { name: 'Previous', exact: true }).click({ timeout: 3000 });
      await expect.poll(() => state.issiRequests.length).toBe(3);
      await sizeHandle().press('End');
      await expect(player).toHaveAttribute('data-state', 'full');
      await bandSection(page).getByRole('button', { name: 'Next', exact: true }).click({ timeout: 3000 });
      await expect.poll(() => state.issiRequests.length).toBe(4);
      expect(state.frequencyRequests.length).toBe(frequencyRequestsBeforePaging);
      expect(page.url()).toBe(urlBeforePaging);
      await expectNoHorizontalOverflow(page);
    }
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });
}

test('site ISSI failure and retry leave frequencies and home bands available', async ({ page }) => {
  test.setTimeout(15000);
  const { fixture, state } = await installBands(page, 'light');
  state.mode = 'failure';
  await page.goto(siteRoute);
  const failure = page.locator('#content > .ui-section').filter({ has: page.getByRole('alert') });
  await expect(failure).toContainText('Foreign band plans temporarily unavailable');
  await expect(page.locator('.channel-band-plan-column')).toContainText('851.00000');
  await expect(page.locator('.channel-frequency-column')).toContainText('0-001');
  state.mode = 'empty';
  await failure.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(bandSection(page)).toContainText('No ISSI-advertised band plans heard on this site.');
  expect(state.issiRequests.map(request => request.configuration_id)).toEqual([channelId, channelId]);
  expect(state.frequencyRequests).toHaveLength(1);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});
