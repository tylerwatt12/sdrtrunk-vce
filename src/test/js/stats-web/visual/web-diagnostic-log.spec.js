'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');
const ROOT = '/api/v1/admin/web-diagnostic-log';
const stamp = '2026-10-01T12:00:00Z';
const logEntry = (id, level, message, details = '') => ({ id, time: stamp, level, source: 'Web diagnostics',
  message, details, text: `${stamp} ${level} Web diagnostics - ${message}${details ? `\n${details}` : ''}` });
const snapshot = (entries, previous = false) => ({ log: previous ? 'previous' : 'current',
  file_name: previous ? 'sdrtrunk_web-previous.log' : 'sdrtrunk_web.log', available: true, entries,
  updated_at: Date.parse(stamp), max_entries: 500, truncated: false, latest_id: entries.at(-1)?.id ?? null });

async function openWebLog(page, { theme = 'light', allowed = true } = {}) {
  await installSiteStyleApplication(page, theme);
  const state = { calls: [], writes: [], putFailure: false, statusFailure: false, holdSave: null,
    status: { enabled: false, active: false, file_name: 'sdrtrunk_web.log', retention_days: 7,
      maximum_bytes: 104857600, queue_depth: 0, dropped_records: 0, written_records: 0,
      last_error: null, can_download: true }, current: snapshot([
      logEntry('1', 'INFO', 'Web activity recorded'),
      logEntry('2', 'WARN', 'North Ridge tuner data lost', 'Alert followed a USB transfer delay'),
      logEntry('3', 'ERROR', 'Tuner error', 'Temporary transfer failure')
    ]), previous: snapshot([logEntry('old', 'WARN', 'Previous tuner alert')], true) };
  if (!allowed) await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: { data: {
    configured: true, authenticated: true, username: 'fixture-reader', tier: 'admin', primary: true,
    capabilities: { 'receiver-health': true, 'admin-settings': false } } } }));
  await page.route(`**${ROOT}**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    state.calls.push({ method: request.method(), path: url.pathname, query: Object.fromEntries(url.searchParams) });
    const data = (value) => route.fulfill({ json: { data: value } });
    if (url.pathname === ROOT && request.method() === 'PUT') {
      state.writes.push({ body: request.postDataJSON(), csrf: request.headers()['x-csrf-token'] });
      if (state.holdSave) await state.holdSave;
      if (state.putFailure) return route.fulfill({ status: 503, json: { error: { message: 'Save failed' } } });
      state.status = { ...state.status, enabled: request.postDataJSON().enabled,
        active: request.postDataJSON().enabled };
      return data(state.status);
    }
    if (url.pathname === ROOT) {
      if (state.statusFailure) return route.fulfill({ status: 503, json: { error: { message: 'Status unavailable' } } });
      return data(state.status);
    }
    if (url.pathname === `${ROOT}/entries`) {
      return data(url.searchParams.get('log') === 'previous' ? state.previous : state.current);
    }
    if (url.pathname === `${ROOT}/download`) return route.fulfill({ contentType: 'application/zip',
      headers: { 'Content-Disposition': 'attachment; filename="web-server-logs.zip"' }, body: Buffer.from('PK\x05\x06' + '\0'.repeat(18)) });
    return route.fulfill({ status: 404 });
  });
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: async (text) => { window.copiedLogText = text; } } });
  });
  await page.goto('/app.html?view=admin&tab=web-diagnostic-log');
  return state;
}

const workspace = (page) => page.locator('.web-diagnostic-log-workspace');
const toggle = (page) => page.getByRole('checkbox', { name: 'Web server diagnostic logging', exact: true });

test('saving logging preserves a failed draft, locks duplicate submits, and uses CSRF', async ({ page }) => {
  const state = await openWebLog(page);
  await expect(workspace(page).locator('.application-log-entry')).toHaveCount(3);
  await expect(toggle(page)).not.toBeChecked();
  const save = workspace(page).getByRole('button', { name: 'Save', exact: true });
  await expect(save).toBeDisabled();
  await toggle(page).focus();
  await page.keyboard.press('Space');
  state.putFailure = true;
  await save.click();
  await expect(workspace(page)).toContainText('Your choice is kept; try saving again.');
  await expect(toggle(page)).toBeChecked();
  await page.getByRole('button', { name: 'Reload status', exact: true }).click();
  await expect(toggle(page)).toBeChecked();
  await expect(save).toBeEnabled();
  state.putFailure = false;
  let release;
  state.holdSave = new Promise((resolve) => { release = resolve; });
  await save.click();
  await expect(toggle(page)).toBeDisabled();
  await expect(save).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Reload status', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Download saved logs', exact: true })).toBeDisabled();
  expect(state.writes).toHaveLength(2);
  release();
  await expect(workspace(page)).toContainText('Diagnostic logging saved.');
  await expect(workspace(page).locator('.ui-fact-row').first()).toContainText('Active');
  await expect(toggle(page)).toBeEnabled();
  await expect(save).toBeDisabled();
  expect(state.writes.at(-1)).toEqual({ body: { enabled: true }, csrf: 'fixture-csrf' });
});

test('reused viewer searches, changes levels, pauses, selects archive and exports both shown and retained evidence', async ({ page }) => {
  const state = await openWebLog(page);
  const viewer = workspace(page).locator('.application-log-workspace');
  await expect(viewer.locator('.application-log-entry')).toHaveCount(3);
  await page.getByLabel('Level', { exact: true }).selectOption('WARN');
  await page.getByLabel('Search messages', { exact: true }).fill('USB');
  await expect(viewer.locator('.application-log-entry')).toHaveCount(1);
  await page.getByRole('button', { name: 'Copy shown', exact: true }).click();
  expect(await page.evaluate(() => window.copiedLogText)).toContain('USB transfer delay');
  let pendingDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download shown', exact: true }).click();
  expect((await pendingDownload).suggestedFilename()).toBe('web-server-log-current-shown.txt');
  pendingDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download saved logs', exact: true }).click();
  expect((await pendingDownload).suggestedFilename()).toBe('web-server-logs.zip');
  await page.getByRole('button', { name: 'Pause log updates', exact: true }).click();
  await expect(viewer.locator('.application-log-state')).toHaveText('Paused');
  await page.getByLabel('Search messages', { exact: true }).fill('');
  await page.getByLabel('Saved log', { exact: true }).selectOption('previous');
  await expect(viewer).toContainText('Previous tuner alert');
  await expect(viewer.locator('.application-log-state')).toHaveText('Saved log');
  expect(state.calls.filter(({ path }) => path.endsWith('/entries')).at(-1).query)
    .toEqual({ log: 'previous', compact: 'true' });
});

test('status exposes write failure and skipped entries, stops while hidden, and closes after navigation', async ({ page }) => {
  const state = await openWebLog(page);
  await expect(toggle(page)).toBeEnabled();
  state.status.can_download = false;
  await page.getByRole('button', { name: 'Download saved logs', exact: true }).click();
  await expect(workspace(page)).toContainText('No saved web server logs are available yet.');
  await expect(page.getByRole('button', { name: 'Download saved logs', exact: true })).toBeDisabled();
  state.status = { ...state.status, enabled: true, active: false, last_error: 'Log could not be written', dropped_records: 17 };
  await page.getByRole('button', { name: 'Reload status', exact: true }).click();
  await expect(workspace(page).locator('.ui-fact-row').first()).toContainText('Unable to write');
  await expect(workspace(page).locator('.ui-fact-row').last()).toContainText('17');
  await expect(workspace(page)).toContainText('Check available disk space and folder access');
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  const hiddenCalls = state.calls.length;
  await page.clock.runFor(6000);
  expect(state.calls).toHaveLength(hiddenCalls);
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: false });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await expect.poll(() => state.calls.length).toBeGreaterThan(hiddenCalls);
  await page.getByRole('navigation', { name: 'Administration sections' })
    .getByRole('link', { name: 'Receiver status', exact: true }).click();
  await expect(workspace(page)).toHaveCount(0);
  const closedCalls = state.calls.length;
  await page.clock.runFor(6000);
  expect(state.calls).toHaveLength(closedCalls);
});

test('status loading failure supports retry and the settings capability prevents requests', async ({ page }) => {
  const state = await openWebLog(page);
  await expect(toggle(page)).toBeEnabled();
  state.statusFailure = true;
  await page.getByRole('button', { name: 'Reload status', exact: true }).click();
  await expect(workspace(page)).toContainText('Use Reload status to try again.');
  state.statusFailure = false;
  await page.getByRole('button', { name: 'Reload status', exact: true }).click();
  await expect(workspace(page).locator('.admin-form-message')).toBeHidden();
  await page.unrouteAll({ behavior: 'wait' });
  const restricted = await openWebLog(page, { allowed: false });
  await expect(workspace(page)).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Web server log', exact: true })).toHaveCount(0);
  expect(restricted.calls).toEqual([]);
});

for (const theme of ['light', 'dark']) {
  for (const [device, size] of [['desktop', { width: 1440, height: 1000 }], ['phone', { width: 320, height: 760 }]]) {
    test(`web diagnostic logging ${theme} ${device} keeps settings and evidence within the viewport`, async ({ page }, testInfo) => {
      await page.setViewportSize(size);
      await openWebLog(page, { theme });
      await expect(toggle(page)).toBeEnabled();
      await expect(workspace(page)).toContainText('Logs are kept for up to 7 days, within a 100 MB limit.');
      await page.getByLabel('Expand details', { exact: true }).check();
      await toggle(page).focus();
      await page.keyboard.press('Space');
      await expect(toggle(page)).toBeChecked();
      await page.getByRole('button', { name: 'Change audio player size', exact: true }).press('Home');
      await page.evaluate(() => window.scrollTo(0, 0));
      await expectNoHorizontalOverflow(page);
      await page.screenshot({ path: testInfo.outputPath(`web-diagnostic-${theme}-${device}.png`), fullPage: true });
    });
  }
}
