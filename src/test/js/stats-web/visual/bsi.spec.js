'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, channelId } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const now = Date.parse('2026-11-14T18:00:00Z');
const observation = (source, callsign, minutes) => ({ source, callsign,
  lastHeardMs: minutes === null ? null : now - minutes * 60000 });
const matching = [observation('control', 'WTEST77', 20), observation('voice', 'WTEST77', 5)];
const rows = [
  { descriptor: '0-1', downlink_hz: 851012500, bsi: matching },
  { descriptor: '0-5', downlink_hz: 851037500, bsi: [observation('voice', 'WTEST88', 90)] },
  { descriptor: '0-21', downlink_hz: 851137500, bsi: [observation('control', 'WTEST99', null)] },
  { descriptor: '0-25', downlink_hz: 851162500, bsi: [observation('control', 'WTEST77', 20),
    observation('voice', 'WTEST88', 10)] },
  { descriptor: '0-29', downlink_hz: 851187500, bsi: [] }
].map(row => ({ ...row, current_tags: ['BSI'], tags: ['BSI'], observation_count: 12 }));

for (const theme of ['light', 'dark']) {
  for (const [size, width] of [['desktop', 1440], ['phone', 390]]) {
    test(`carrier BSI ${theme} ${size}`, async ({ page }, testInfo) => {
      await page.setViewportSize({ width, height: 1000 });
      const state = await installSiteStyleApplication(page, theme);
      await page.clock.setFixedTime(new Date(now));
      await page.route(`**/api/v1/channels/${channelId}**`, async route => {
        const url = new URL(route.request().url());
        if (url.pathname.endsWith('/frequencies')) return route.fulfill({ json: { data: {
          rows, total_count: rows.length, limit: 100, offset: 0, has_more: false, next_offset: null
        } } });
        if (url.pathname === `/api/v1/channels/${channelId}`) return route.fulfill({ json: { data: { channel: {
          configuration_id: channelId, protocol: 'P25', protocol_id: 'p25-phase1', channel_kind: 'TRUNKED',
          decoder: 'P25_PHASE1', name: 'Example P25 site', system_name: 'Example public safety system',
          site_name: 'Example site', alias_list_name: 'County Public Safety', alias_list_id: 1,
          primary_frequency_hz: 851012500, current_control_hz: 851012500,
          bsi_frequency_hz: 851012500, bsi: matching, frequencies: rows.length,
          wacn: 1, system_id: 1, nac: 0x34a, rfss: 1, site_id: 2, mfid_display: 'Motorola',
          capabilities: { channels: true, quality: true }, p25_decoder_mode: 'C4FM'
        } } } });
        return route.fallback();
      });
      await page.goto(`/app.html?view=channel&configuration_id=${channelId}&tab=info`);
      await expect(page.getByRole('link', { name: 'WTEST77', exact: true })).toHaveCount(1);
      await expect(page.locator('#content')).toContainText('Control · Fresh · Last heard');
      await expect(page.locator('#content')).toContainText('Voice · Fresh · Last heard');
      await expect(page.locator('#content')).not.toContainText('Conflict');
      await expectNoHorizontalOverflow(page);
      await expectContrast(page, { rootSelector: '#content' });
      await page.screenshot({ path: testInfo.outputPath('channel-info.png'), fullPage: true });

      await page.goto(`/app.html?view=channel&configuration_id=${channelId}&tab=frequencies`);
      const tableRows = page.locator('tbody tr');
      await expect(tableRows).toHaveCount(rows.length);
      await expect(tableRows.nth(0).getByRole('link', { name: 'WTEST77', exact: true })).toHaveCount(1);
      await expect(tableRows.nth(0)).toContainText('Control · Fresh · Last heard');
      await expect(tableRows.nth(0)).toContainText('Voice · Fresh · Last heard');
      await expect(tableRows.nth(1)).toContainText('Voice · Historical · Last heard');
      await expect(tableRows.nth(2)).toContainText('Control · Last heard unknown');
      await expect(tableRows.nth(3)).toContainText('Conflict');
      await expect(tableRows.nth(3)).toContainText('Control: WTEST77');
      await expect(tableRows.nth(3)).toContainText('Voice: WTEST88');
      await expect(tableRows.nth(3).getByRole('link', { name: 'WTEST88', exact: true })).toHaveCount(1);
      await expect(tableRows.nth(4)).not.toContainText('Conflict');
      await expect(page.locator('#content .ui-pill-warning')).toHaveCount(1);
      await expectNoHorizontalOverflow(page);
      await expectContrast(page, { rootSelector: '#content' });
      await page.screenshot({ path: testInfo.outputPath('channel-frequencies.png'), fullPage: true });
      expect(state.pageErrors).toEqual([]);
    });
  }
}
