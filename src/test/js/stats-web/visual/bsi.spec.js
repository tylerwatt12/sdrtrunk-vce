'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, channelId } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const now = Date.parse('2026-11-14T18:00:00Z');
const observation = (source, callsign, minutes) => ({ source, callsign,
  lastHeardMs: minutes === null ? null : now - minutes * 60000 });
const matching = [observation('control', 'WTEST777', 20), observation('voice', 'WTEST777', 5)];
const rows = [
  { descriptor: '0-1', downlink_hz: 851012500, bsi: matching },
  { descriptor: '0-5', downlink_hz: 851037500, bsi: [observation('voice', 'WTEST88', 90)] },
  { descriptor: '0-21', downlink_hz: 851137500, bsi: [observation('control', 'WTEST99', null)] },
  { descriptor: '0-25', downlink_hz: 851162500, bsi: [observation('control', 'WTEST777', 20),
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
      await expect(page.getByRole('link', { name: 'WTEST777', exact: true })).toHaveCount(1);
      await expect(page.locator('#content')).not.toContainText('Last heard');
      await expect(page.locator('#content')).not.toContainText('Conflict');
      await expectNoHorizontalOverflow(page);
      await expectContrast(page, { rootSelector: '#content' });
      await page.screenshot({ path: testInfo.outputPath('channel-info.png'), fullPage: true });

      await page.goto(`/app.html?view=channel&configuration_id=${channelId}&tab=frequencies`);
      const tableRows = page.locator('tbody tr');
      await expect(tableRows).toHaveCount(rows.length);
      const table = page.locator('table[data-table-type="channel-frequencies-p25"]');
      const bsiCells = table.locator('tbody [data-column="callsign"]');
      await expect(bsiCells.nth(0)).toHaveText('WTEST777');
      await expect(bsiCells.nth(1)).toHaveText('WTEST88');
      await expect(bsiCells.nth(2)).toHaveText('WTEST99');
      await expect(bsiCells.nth(3).getByRole('link', { name: 'WTEST777', exact: true })).toHaveCount(1);
      await expect(tableRows.nth(3).getByRole('link', { name: 'WTEST88', exact: true })).toHaveCount(1);
      await expect(bsiCells.nth(4)).toBeEmpty();
      expect((await bsiCells.allTextContents()).join(' ')).not.toMatch(/Control|Voice|Last heard|Conflict|Fresh|Historical/);
      await expect(bsiCells.locator('[data-ui-hint], [title]')).toHaveCount(0);
      await expect(table.locator('thead [data-column="callsign"]')).toHaveCSS('width', '112px');
      await expect(table.locator('thead [data-column="downlink"]')).toHaveCSS('width', '94px');
      const valueGeometry = await bsiCells.nth(0).getByRole('link').evaluate(element => {
        const range = document.createRange();
        range.selectNodeContents(element);
        const cell = element.closest('td');
        return { lines: range.getClientRects().length, overflow: cell.scrollWidth > cell.clientWidth + 1 };
      });
      expect(valueGeometry).toEqual({ lines: 1, overflow: false });
      await expectNoHorizontalOverflow(page);
      await expectContrast(page, { rootSelector: '#content' });
      await page.screenshot({ path: testInfo.outputPath('channel-frequencies.png'), fullPage: true });
      if (theme === 'light' && size === 'desktop') {
        await page.setViewportSize({ width: 1920, height: 1000 });
        const header = table.locator('thead [data-column="callsign"]');
        await expect(header).toHaveCSS('width', '112px');
        const saved = page.waitForResponse(response => response.request().method() === 'PUT' &&
          new URL(response.url()).pathname === '/api/v1/me/preferences');
        await header.locator('.column-resizer').focus();
        await header.locator('.column-resizer').press('ArrowRight');
        expect((await saved).ok()).toBe(true);
        await page.reload();
        await expect(header).toHaveCSS('width', '122px');
        await table.locator('xpath=ancestor::section[1]').getByRole('button', { name: 'Choose table columns' }).click();
        await page.getByRole('button', { name: 'Reset this table', exact: true }).click();
        await expect(header).toHaveCSS('width', '112px');
      }
      expect(state.pageErrors).toEqual([]);
    });
  }
}
