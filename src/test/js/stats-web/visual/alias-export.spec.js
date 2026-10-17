'use strict';

const fs = require('node:fs');
const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const filteredRoute = '/app.html?view=aliases&list=1&aliasTab=custom&q=Fire&group=Fire&record=true' +
  '&sort=name&direction=desc&offset=100&limit=1';
const reportCsv = 'alias_id,name,logical_calls\r\n101,Fire Dispatch,12\r\n';
const format = (modal, value) => modal.locator(`input[name="aliasTransferExportFormat"][value="${value}"]`);
const scope = (modal, value) => modal.locator(`input[name="aliasTransferExportScope"][value="${value}"]`);

async function openEditor(page, { theme = 'light', width = 1280, route = filteredRoute, csvExport = true } = {}) {
  await page.setViewportSize({ width, height: width < 600 ? 640 : 900 });
  const fixture = await installSiteStyleApplication(page, theme);
  if (!csvExport) {
    await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
      configured: true, authenticated: true, username: 'operator', tier: 'admin', primary: true,
      csrf_token: 'test-token', capabilities: { 'admin-aliases': true, 'admin-channels': true,
        radio: true, 'csv-export': false }
    } } }));
  }
  await page.goto(route);
  await expect(page.locator('.alias-editor-table-section')).toContainText('Fire Dispatch');
  await expect(page.locator('.alias-editor-table-section .export-csv-action')).toHaveCount(0);
  return fixture;
}

async function openExport(page) {
  await page.locator('.alias-transfer-export-button').click();
  const modal = page.getByRole('dialog', { name: 'Export aliases from County Public Safety', exact: true });
  await expect(modal).toBeVisible();
  return modal;
}

function expectCleanFixture(fixture) {
  expect(fixture.unexpected).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
}

async function expectBoundedExport(page, modal) {
  const action = modal.getByRole('link', { name: 'Export CSV', exact: true });
  await action.scrollIntoViewIfNeeded();
  const bounds = await modal.evaluate(element => {
    const box = element.getBoundingClientRect();
    return { left: box.left, right: box.right, viewport: { width: innerWidth, height: innerHeight },
      actions: [...element.querySelectorAll('.ui-modal-footer button, .ui-modal-footer a')].map(action => {
        const actionBox = action.getBoundingClientRect();
        return { left: actionBox.left, right: actionBox.right, top: actionBox.top, bottom: actionBox.bottom };
      }) };
  });
  expect(bounds.left).toBeGreaterThanOrEqual(0);
  expect(bounds.right).toBeLessThanOrEqual(bounds.viewport.width);
  for (const actionBox of bounds.actions) {
    expect(actionBox.left).toBeGreaterThanOrEqual(bounds.left);
    expect(actionBox.right).toBeLessThanOrEqual(bounds.right);
    expect(actionBox.top).toBeGreaterThanOrEqual(0);
    expect(actionBox.bottom).toBeLessThanOrEqual(bounds.viewport.height);
  }
  await expectNoHorizontalOverflow(page);
}

test('the consolidated export defaults to importable configuration and preserves both configuration scopes', async ({ page }) => {
  const fixture = await openEditor(page);
  const exports = [];
  await page.route(url => url.pathname === '/api/v1/admin/alias-lists/1/transfer' ||
    url.pathname === '/api/v1/exports/alias-list.csv', async route => {
    const url = new URL(route.request().url());
    exports.push(url);
    const token = url.searchParams.get('export_token');
    expect(token).toMatch(/^[a-f0-9]{32}$/);
    await route.fulfill({ contentType: 'text/csv', headers: {
      'Content-Disposition': 'attachment; filename="vce-alias-list-1.csv"',
      'Set-Cookie': `sdrtrunk_alias_export_ready_${token}=1; Path=/; SameSite=Lax`
    }, body: 'format_version,alias_list,name\r\n3,County Public Safety,Fire Dispatch\r\n' });
  });

  const modal = await openExport(page);
  await expect(format(modal, 'configuration')).toBeChecked();
  await expect(scope(modal, 'all')).toBeChecked();
  await expect(modal).toContainText('Importable configuration');
  await expect(modal).toContainText('Activity report');
  await expectBoundedExport(page, modal);
  const action = modal.getByRole('link', { name: 'Export CSV', exact: true });
  const allDownload = page.waitForEvent('download');
  await action.click();
  expect((await allDownload).suggestedFilename()).toBe('vce-alias-list-1.csv');
  await expect(action).not.toHaveAttribute('aria-disabled', 'true');
  expect(exports[0].pathname).toBe('/api/v1/admin/alias-lists/1/transfer');
  expect([...exports[0].searchParams.keys()]).toEqual(['export_token']);

  await scope(modal, 'filtered').check();
  const filteredDownload = page.waitForEvent('download');
  await action.click();
  await filteredDownload;
  await expect(action).not.toHaveAttribute('aria-disabled', 'true');
  expect(exports[1].pathname).toBe('/api/v1/exports/alias-list.csv');
  expect(Object.fromEntries([...exports[1].searchParams].filter(([key]) => key !== 'export_token')))
    .toEqual({ list: '1', scope: 'filtered', group: 'Fire', record: 'true', q: 'Fire' });
  await modal.getByRole('button', { name: 'Cancel', exact: true }).click();
  expectCleanFixture(fixture);
});

test('activity report on a dark phone exports all or filtered results without page or selection limits', async ({ page }) => {
  const fixture = await openEditor(page, { theme: 'dark', width: 390 });
  const exports = [];
  await page.route(url => url.pathname === '/api/v1/exports/aliases.csv', route => {
    exports.push(new URL(route.request().url()));
    return route.fulfill({ contentType: 'text/csv', headers: {
      'Content-Disposition': 'attachment; filename="alias-activity.csv"'
    }, body: reportCsv });
  });
  await page.locator('.alias-row-select').first().check();
  let modal = await openExport(page);
  await format(modal, 'activity').check();
  await expect(modal).toContainText(/cannot be imported/i);
  await expectBoundedExport(page, modal);
  let downloadReady = page.waitForEvent('download');
  await modal.getByRole('link', { name: 'Export CSV', exact: true }).click();
  let download = await downloadReady;
  expect(download.suggestedFilename()).toBe('alias-activity.csv');
  expect(fs.readFileSync(await download.path(), 'utf8')).toBe(reportCsv);
  expect(Object.fromEntries(exports[0].searchParams)).toEqual({ list: '1', sort: 'name', direction: 'desc' });

  if (!(await modal.isVisible())) modal = await openExport(page);
  await format(modal, 'activity').check();
  await scope(modal, 'filtered').check();
  downloadReady = page.waitForEvent('download');
  await modal.getByRole('link', { name: 'Export CSV', exact: true }).click();
  download = await downloadReady;
  expect(download.suggestedFilename()).toBe('alias-activity.csv');
  expect(Object.fromEntries(exports[1].searchParams)).toEqual({
    list: '1', group: 'Fire', record: 'true', q: 'Fire', sort: 'name', direction: 'desc'
  });
  await expectNoHorizontalOverflow(page);
  expectCleanFixture(fixture);
});

test('activity report failure preserves choices and allows retry', async ({ page }) => {
  const fixture = await openEditor(page);
  let attempts = 0;
  await page.route(url => url.pathname === '/api/v1/exports/aliases.csv', route => {
    attempts += 1;
    return attempts === 1 ? route.fulfill({ status: 503, body: 'Temporarily unavailable' }) :
      route.fulfill({ contentType: 'text/csv', body: reportCsv });
  });
  const modal = await openExport(page);
  await format(modal, 'activity').check();
  await scope(modal, 'filtered').check();
  const action = modal.getByRole('link', { name: 'Export CSV', exact: true });
  await action.click();
  await expect(modal.locator('.error')).toBeVisible();
  await expect(format(modal, 'activity')).toBeChecked();
  await expect(scope(modal, 'filtered')).toBeChecked();
  await expect(action).not.toHaveAttribute('aria-disabled', 'true');
  const download = page.waitForEvent('download');
  await action.click();
  await download;
  expect(attempts).toBe(2);
  expectCleanFixture(fixture);
});

test('a late configuration iframe error cannot interrupt an activity report', async ({ page }) => {
  const fixture = await openEditor(page);
  let releaseReport, reportStarted;
  const reportReleased = new Promise(resolve => { releaseReport = resolve; });
  const reportRequested = new Promise(resolve => { reportStarted = resolve; });
  await page.route(url => url.pathname === '/api/v1/exports/aliases.csv', async route => {
    reportStarted();
    await reportReleased;
    await route.fulfill({ contentType: 'text/csv', headers: {
      'Content-Disposition': 'attachment; filename="alias-activity.csv"'
    }, body: reportCsv });
  });
  const modal = await openExport(page);
  await format(modal, 'activity').check();
  const action = modal.getByRole('link', { name: 'Export CSV', exact: true });
  await action.click();
  await reportRequested;
  await modal.locator('iframe').evaluate(frame => {
    frame.contentDocument.body.textContent = JSON.stringify({ error: { message: 'Configuration export expired.' } });
    frame.dispatchEvent(new Event('load'));
  });
  await expect(modal.locator('.status')).toHaveText('Preparing the activity report…');
  await expect(action).toHaveAttribute('aria-disabled', 'true');
  const downloaded = page.waitForEvent('download');
  releaseReport();
  expect((await downloaded).suggestedFilename()).toBe('alias-activity.csv');
  await expect(modal.locator('.status')).toContainText('Activity report downloaded.');
  await expect(modal.locator('.error')).toHaveCount(0);
  expectCleanFixture(fixture);
});

for (const closeWith of ['Cancel', 'Escape']) {
  test(`closing a pending activity export with ${closeWith} aborts it and prevents a stale download`, async ({ page }) => {
    const fixture = await openEditor(page);
    await page.evaluate(() => {
      const original = window.fetch.bind(window);
      window.aliasExportTest = { started: false, aborted: false, completed: false };
      window.fetch = (input, options) => {
        const url = new URL(typeof input === 'string' ? input : input.url, location.href);
        if (url.pathname !== '/api/v1/exports/aliases.csv') return original(input, options);
        const state = window.aliasExportTest;
        state.started = true;
        options.signal.addEventListener('abort', () => { state.aborted = true; }, { once: true });
        // Deliberately finish after abort to check that a closed dialog cannot trigger a stale download.
        return new Promise(resolve => {
          state.release = () => {
            resolve(new Response('name\r\nLate export\r\n', { headers: { 'Content-Type': 'text/csv' } }));
            state.completed = true;
          };
        });
      };
    });
    const downloads = [];
    page.on('download', download => downloads.push(download));
    const modal = await openExport(page);
    await format(modal, 'activity').check();
    await modal.getByRole('link', { name: 'Export CSV', exact: true }).click();
    await expect.poll(() => page.evaluate(() => window.aliasExportTest.started)).toBe(true);
    if (closeWith === 'Cancel') await modal.getByRole('button', { name: 'Cancel', exact: true }).click();
    else await page.keyboard.press('Escape');
    await expect(modal).not.toBeVisible();
    await expect.poll(() => page.evaluate(() => window.aliasExportTest.aborted)).toBe(true);
    await page.evaluate(async () => {
      window.aliasExportTest.release();
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    });
    expect(downloads).toEqual([]);
    const reopened = await openExport(page);
    await expect(format(reopened, 'configuration')).toBeChecked();
    await expect(reopened.locator('.error')).toHaveCount(0);
    expectCleanFixture(fixture);
  });
}

test('CSV export permission disables activity and filtered configuration while retaining full configuration export', async ({ page }) => {
  const fixture = await openEditor(page, { csvExport: false });
  const modal = await openExport(page);
  await expect(format(modal, 'configuration')).toBeChecked();
  await expect(format(modal, 'configuration')).toBeEnabled();
  await expect(format(modal, 'activity')).toBeDisabled();
  await expect(scope(modal, 'all')).toBeChecked();
  await expect(scope(modal, 'all')).toBeEnabled();
  await expect(scope(modal, 'filtered')).toBeDisabled();
  await expect(modal.getByRole('link', { name: 'Export CSV', exact: true }))
    .toHaveAttribute('href', '/api/v1/admin/alias-lists/1/transfer');
  expectCleanFixture(fixture);
});
