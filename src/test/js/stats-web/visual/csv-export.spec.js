'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const systemKey = 'p25:bee00:348';
const radioKey = 'v1-r-bee00-348-30914';
const exportPath = '/api/v1/exports/radio-system-talker-aliases.csv';
const downloadName = 'sdrtrunk-radio-system-talker-aliases-county-20261007-190000Z.csv';
const csv = '\ufeffprotocol,radio_system_key,native_id,talker_alias,alias\r\n' +
  'P25,p25:bee00:348,30914,ENG 4,Engine 4\r\n';
const exportLabel = 'Export radio system talker aliases as CSV';
const presentations = [
  { name: 'light-desktop', theme: 'light', viewport: { width: 1280, height: 900 } },
  { name: 'dark-mobile', theme: 'dark', viewport: { width: 390, height: 844 } }
];

test.describe('Talker Alias report downloads', () => {
  test.setTimeout(12_000);

  async function openReport(page, presentation) {
    await page.setViewportSize(presentation.viewport);
    const fixture = await installSiteStyleApplication(page, presentation.theme);
    const state = { requests: [], pending: [], deferred: true, failure: null, invalidContentType: null, downloads: [] };
    page.on('download', download => state.downloads.push(download));
    const system = { protocol: 'P25', radio_system_key: systemKey, wacn: 0xBEE00, system_id: 0x348,
      system_name: 'County Public Safety', channels: 1, radios: 1, talkgroups: 1,
      radio_system_entity_ref: { kind: 'radio_system', key: systemKey },
      capabilities: { talker_aliases: true, group_identities: true, radios: true, activity: true } };
    await page.route('**/api/v1/**', async route => {
      const url = new URL(route.request().url());
      const pathname = decodeURIComponent(url.pathname);
      if (pathname === `/api/v1/radio-systems/${systemKey}`) {
        return route.fulfill({ json: { data: system } });
      }
      if (pathname === `/api/v1/radio-systems/${systemKey}/talker-aliases`) {
        return route.fulfill({ json: { data: [{ ...system, identity_key: radioKey,
          native_id: 30914, last_talker_alias: 'ENG 4', alias_name: 'Engine 4',
          logical_call_count: 12, encrypted_logical_call_count: 0,
          last_talker_alias_seen_ms: 1_790_856_000_000,
          entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: radioKey } }],
          meta: { total_count: 1, limit: 100, offset: 0, has_more: false } } });
      }
      if (pathname !== exportPath) return route.fallback();
      state.requests.push({ method: route.request().method(), parameters: Object.fromEntries(url.searchParams) });
      let release;
      const ready = new Promise(resolve => { release = resolve; });
      let finish;
      const settled = new Promise(resolve => { finish = resolve; });
      const failure = state.failure;
      const contentType = state.invalidContentType || 'text/csv; charset=utf-8';
      state.pending.push({ release, settled });
      try {
        if (state.deferred) await ready;
        if (failure) return await route.fulfill({ status: failure, json: { error: {
          code: 'export_failed', message: 'The CSV export could not be prepared. Try again.' } } });
        return await route.fulfill({ contentType,
          headers: { 'Content-Disposition': `attachment; filename="${downloadName}"`,
            'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' }, body: csv });
      } finally {
        finish();
      }
    });
    await page.goto(`/app.html?view=radio-system&radio_system_key=${encodeURIComponent(systemKey)}&tab=talker-aliases`);
    await expect(page.getByRole('region', { name: 'Talker Alias Summary', exact: true })).toBeVisible();
    await expect(page.locator('#content')).toContainText('ENG 4');
    state.originalUrl = page.url();
    return { ...fixture, state, action: () => page.getByRole('link', { name: exportLabel, exact: true }) };
  }

  async function releaseAttempt(state, index) {
    state.pending[index].release();
    await state.pending[index].settled;
  }

  async function verifyDownload(download) {
    expect(download.suggestedFilename()).toBe(downloadName);
    expect(await download.failure()).toBeNull();
    expect(fs.readFileSync(await download.path(), 'utf8')).toBe(csv);
  }

  async function capture(page, name) {
    if (process.env.VCE_CSV_EXPORT_REVIEW_DIR) await page.screenshot({
      path: path.join(process.env.VCE_CSV_EXPORT_REVIEW_DIR, `${name}.png`), fullPage: true
    });
  }

  for (const presentation of presentations) {
    test(`pointer export downloads the complete named report and preserves the page ${presentation.name}`, async ({ page }) => {
      const fixture = await openReport(page, presentation);
      if (presentation.theme === 'light') await fixture.action().evaluate(link => {
        const target = new URL(link.href);
        target.searchParams.set('q', 'Engine 4');
        link.href = target.href;
      });
      await fixture.action().click();
      await expect.poll(() => fixture.state.requests.length).toBe(1);
      const busy = page.locator('.export-csv-action[aria-busy="true"]');
      await expect(busy).toBeVisible();
      await expect(busy).toHaveAccessibleName(/preparing.*export.*click to cancel/i);
      await expect(page.locator('.export-csv-feedback')).toContainText(/Preparing CSV export.*cancel/i);
      await expect(page.locator('.export-csv-feedback')).toBeVisible();
      await capture(page, `preparing-${presentation.name}`);
      const pendingDownload = page.waitForEvent('download');
      await releaseAttempt(fixture.state, 0);
      await verifyDownload(await pendingDownload);
      await expect(fixture.action()).not.toHaveAttribute('aria-busy', 'true');
      await expect(page.locator('#content')).toContainText(/CSV prepared.*browser.*downloads/i);
      expect(fixture.state.requests).toEqual([{ method: 'GET', parameters: { radio_system_key: systemKey,
        ...(presentation.theme === 'light' ? { q: 'Engine 4' } : {}) } }]);
      expect(page.url()).toBe(fixture.state.originalUrl);
      await expectNoHorizontalOverflow(page, '#content');
      await capture(page, `success-${presentation.name}`);
      expect(fixture.pageErrors).toEqual([]);
      expect(fixture.unexpected).toEqual([]);
    });
  }

  test('failed export gives visible feedback and a subsequent pointer retry downloads once', async ({ page }) => {
    const fixture = await openReport(page, presentations[0]);
    fixture.state.deferred = false;
    fixture.state.failure = 503;
    await fixture.action().click();
    await expect(page.getByRole('alert').filter({ hasText: /could not.*download|could not.*prepared|export.*failed/i })).toBeVisible();
    await expect(fixture.action()).not.toHaveAttribute('aria-busy', 'true');
    expect(fixture.state.downloads).toHaveLength(0);
    expect(page.url()).toBe(fixture.state.originalUrl);
    await capture(page, 'failed-light-desktop');
    fixture.state.failure = null;
    const pendingDownload = page.waitForEvent('download');
    await fixture.action().click();
    await verifyDownload(await pendingDownload);
    await expect(page.locator('#content')).toContainText(/CSV prepared.*browser.*downloads/i);
    expect(fixture.state.requests).toHaveLength(2);
    expect(fixture.state.downloads).toHaveLength(1);
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });

  test('a second pointer click cancels preparation and rejects its late response before retry', async ({ page }) => {
    const fixture = await openReport(page, presentations[1]);
    await fixture.action().click();
    await expect.poll(() => fixture.state.requests.length).toBe(1);
    await page.locator('.export-csv-action[aria-busy="true"]').click();
    await expect(fixture.action()).not.toHaveAttribute('aria-busy', 'true');
    await releaseAttempt(fixture.state, 0);
    fixture.state.deferred = false;
    const pendingDownload = page.waitForEvent('download');
    await fixture.action().click();
    await verifyDownload(await pendingDownload);
    await expect(page.locator('#content')).toContainText(/CSV prepared.*browser.*downloads/i);
    expect(fixture.state.requests).toHaveLength(2);
    expect(fixture.state.downloads).toHaveLength(1);
    expect(page.url()).toBe(fixture.state.originalUrl);
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });

  test('authentication and invalid report responses remain visible and never download before retry', async ({ page }) => {
    const fixture = await openReport(page, presentations[0]);
    fixture.state.deferred = false;
    for (const [status, message] of [[401, /Sign in again/i], [403, /account cannot export/i],
      [429, /Another export is running/i], [null, /did not return a CSV report/i]]) {
      fixture.state.failure = status;
      fixture.state.invalidContentType = status === null ? 'application/json' : null;
      await fixture.action().click();
      await expect(page.getByRole('alert').filter({ hasText: message })).toBeVisible();
      await expect(fixture.action()).not.toHaveAttribute('aria-busy', 'true');
      expect(fixture.state.downloads).toHaveLength(0);
      expect(page.url()).toBe(fixture.state.originalUrl);
    }
    fixture.state.invalidContentType = null;
    const pendingDownload = page.waitForEvent('download');
    await fixture.action().click();
    await verifyDownload(await pendingDownload);
    expect(fixture.state.requests).toHaveLength(5);
    expect(fixture.state.downloads).toHaveLength(1);
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });

  test('internal page navigation cancels preparation and prevents a late download or notice', async ({ page }) => {
    const fixture = await openReport(page, presentations[0]);
    await fixture.action().click();
    await expect.poll(() => fixture.state.requests.length).toBe(1);
    await page.getByRole('link', { name: 'Administration', exact: true }).click();
    await expect(page).toHaveURL(/view=admin/);
    await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
    await releaseAttempt(fixture.state, 0);
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
    expect(fixture.state.downloads).toHaveLength(0);
    await expect(page.locator('.export-csv-feedback')).toHaveCount(0);
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });
});
