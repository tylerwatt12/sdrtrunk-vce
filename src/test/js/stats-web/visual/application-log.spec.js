'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
let defaultPreferences;
const capabilitySource = fs.readFileSync(path.resolve(__dirname,
  '../../../../../src/main/java/io/github/dsheirer/web/auth/WebCapability.java'), 'utf8');
const capabilities = Object.fromEntries([...capabilitySource.matchAll(
  /^\s+[A-Z_]+\("([^"]+)"/gm)].map(([, id]) => [id, true]));
const timestamp = '2026-10-02T18:32:32Z';

test.beforeAll(async () => {
  defaultPreferences = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

function entry(id, level = 'INFO', message = 'North Ridge receiver started', details = '') {
  return { id: String(id), time: timestamp, level, source: 'io.github.dsheirer.receiver.Receiver',
    message, details, text: `${timestamp} ${level} Receiver - ${message}${details ? `\n${details}` : ''}` };
}

function documentFor(entries, overrides = {}) {
  return { log: 'current', file_name: 'sdrtrunk_app.log', available: true, entries,
    updated_at: Date.parse(timestamp), max_entries: 500, truncated: false,
    latest_id: entries.at(-1)?.id || null, gap: false, change_reason: '', ...overrides };
}

async function openLog(page, options = {}) {
  const preferences = structuredClone(defaultPreferences);
  preferences.appearance.theme = options.theme || 'light';
  const state = { requests: [], failure: null, snapshot: options.snapshot || documentFor([
    entry(1), entry(2, 'WARN', 'North Ridge tuner temporarily unavailable'),
    entry(3, 'ERROR', 'Call upload failed', 'java.io.IOException: temporary network failure\n' +
      '\tat receiver.upload(Upload.java:17)\nCaused by: Connection reset'),
    entry(4, 'INFO', '<script>window.logInjection = true</script>')
  ]), previous: options.previous || documentFor([], { log: 'previous', available: false,
    file_name: null }) };
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const pathname = url.pathname;
    const data = (value) => route.fulfill({ json: { data: value } });
    if (pathname === '/api/v1/auth/session') return data({ configured: true, authenticated: true,
      username: 'admin', tier: 'admin', primary: true,
      capabilities: { ...capabilities, 'admin-settings': options.allowed !== false } });
    if (pathname === '/api/v1/me/preferences') return route.fulfill({ json: { revision: 1, preferences } });
    if (pathname === '/api/v1/application-log') {
      state.requests.push(Object.fromEntries(url.searchParams));
      if (state.failure) return route.fulfill({ status: state.failure, json: {
        error: { status: state.failure, message: 'Application log unavailable' } } });
      return data(url.searchParams.get('log') === 'previous' ? state.previous : state.snapshot);
    }
    if (pathname === '/api/v1/receiver-health') return data({ summary: { severity: 'healthy',
      active_count: 0, warning_count: 0, critical_count: 0 }, active: [], resolved: [], measurements: [] });
    return data({});
  });
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: async (text) => {
      window.copiedLogText = text;
    } } });
  });
  await page.goto('/app.html?view=admin&tab=application-log');
  return state;
}

test('loaded search, level filters, full details and shown exports preserve readable log evidence', async ({ page }) => {
  await openLog(page);
  const workspace = page.locator('.application-log-workspace');
  await expect(workspace.locator('.application-log-entry')).toHaveCount(4);
  await expect(page.getByRole('heading', { name: 'Application log', exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.logInjection)).toBeUndefined();
  await expect(workspace).toContainText('<script>window.logInjection = true</script>');
  await page.getByLabel('Level', { exact: true }).selectOption('WARN');
  await expect(workspace.locator('.application-log-entry')).toHaveCount(2);
  await page.getByLabel('Search messages', { exact: true }).fill('IOException');
  await expect(workspace.locator('.application-log-entry')).toHaveCount(1);
  const trace = workspace.locator('.application-log-trace');
  await expect(trace).toBeHidden();
  const disclosure = page.getByRole('button', { name: `Details for log message ${timestamp}` });
  await disclosure.focus();
  await page.keyboard.press('Enter');
  await expect(trace).toBeVisible();
  await expect(trace).toContainText('Caused by: Connection reset');
  await page.getByRole('button', { name: 'Copy shown', exact: true }).click();
  expect(await page.evaluate(() => window.copiedLogText)).toContain('Caused by: Connection reset');
  expect(await page.evaluate(() => window.copiedLogText)).not.toContain('tuner temporarily unavailable');
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download shown', exact: true }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe('application-log-current-shown.txt');
  expect(fs.readFileSync(await download.path(), 'utf8')).toBe((await page.evaluate(() => window.copiedLogText)) + '\n');
});

test('pause freezes rows, resume sends the last cursor, disconnect retains rows, and access loss removes them', async ({ page }) => {
  const state = await openLog(page);
  await expect(page.locator('.application-log-entry')).toHaveCount(4);
  await page.getByRole('button', { name: 'Pause log updates' }).click();
  const requests = state.requests.length;
  state.snapshot = documentFor([...state.snapshot.entries, entry(5, 'INFO', 'A new message arrived')]);
  await page.waitForTimeout(2_200);
  expect(state.requests).toHaveLength(requests);
  await expect(page.locator('.application-log-entry')).toHaveCount(4);
  await page.getByRole('button', { name: 'Resume log updates' }).click();
  await expect(page.locator('.application-log-entry')).toHaveCount(5);
  expect(state.requests.at(-1)).toEqual({ log: 'current', after: '4' });
  const retained = state.snapshot;
  state.snapshot = documentFor([], { available: false });
  await page.getByRole('button', { name: 'Refresh application log' }).click();
  await expect(page.locator('.application-log-state')).toHaveText('Unavailable');
  await expect(page.locator('.application-log-entry')).toHaveCount(5);
  state.snapshot = retained;
  state.failure = 503;
  await page.getByRole('button', { name: 'Refresh application log' }).click();
  await expect(page.locator('.application-log-state')).toHaveText('Disconnected');
  await expect(page.locator('.application-log-entry')).toHaveCount(5);
  await expect(page.locator('.application-log-footer')).toContainText('Updated');
  state.failure = 403;
  await page.getByRole('button', { name: 'Refresh application log' }).click();
  await expect(page.locator('.application-log-state')).toHaveText('Access denied');
  await expect(page.locator('.application-log-entry')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Copy shown', exact: true })).toBeDisabled();
});

test('previous-log empty state, rotation notice, bounded window and expand details have honest scope', async ({ page }) => {
  const state = await openLog(page);
  await expect(page.locator('.application-log-entry')).toHaveCount(4);
  await page.getByLabel('Saved log', { exact: true }).selectOption('previous');
  await expect(page.locator('.application-log-workspace')).toContainText('No previous application log is available.');
  await expect(page.locator('.application-log-state')).toHaveText('Saved log');
  await expect(page.getByRole('button', { name: 'Pause log updates' })).toBeDisabled();
  expect(state.requests.at(-1)).toEqual({ log: 'previous' });
  await expect(page.getByRole('button', { name: 'Download shown', exact: true })).toBeDisabled();
  state.snapshot = documentFor([entry('rotated', 'ERROR', 'Changed file', 'Full stack trace')], {
    truncated: true, gap: true, change_reason: 'rotation' });
  await page.getByLabel('Saved log', { exact: true }).selectOption('current');
  await expect(page.locator('.application-log-workspace')).toContainText('changed or rotated');
  await expect(page.locator('.application-log-footer')).toContainText('Newest 1 messages loaded · limit 500');
  await page.getByLabel('Expand details', { exact: true }).check();
  await expect(page.locator('.application-log-trace')).toBeVisible();
  state.snapshot = documentFor([], { truncated: true });
  await page.getByRole('button', { name: 'Refresh application log' }).click();
  await expect(page.locator('.application-log-workspace')).toContainText(
    'No complete messages fit within this viewer’s limit. The complete text remains in the saved application log.');
  await expect(page.locator('.application-log-workspace')).not.toContainText('No messages in this log yet.');
  await expect(page.getByRole('button', { name: 'Download shown', exact: true })).toBeDisabled();
});

test('scrolling disables follow, polling preserves expanded focused details and leaving closes polling', async ({ page }) => {
  const state = await openLog(page, { snapshot: documentFor(Array.from({ length: 50 }, (_, index) =>
    entry(index, 'ERROR', `Message ${index}`, `Detail ${index}`))) });
  const viewport = page.getByRole('region', { name: 'Application log messages', exact: true });
  await expect(page.locator('.application-log-entry')).toHaveCount(50);
  await viewport.evaluate((element) => { element.scrollTop = 0; });
  await expect(page.getByLabel('Follow newest', { exact: true })).not.toBeChecked();
  const disclosure = page.locator('.application-log-entry').first().getByRole('button');
  await disclosure.focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('.application-log-trace').first()).toBeVisible();
  state.snapshot = documentFor([entry(0, 'ERROR', 'Message 0', 'Detail 0\nAdditional detail'),
    ...state.snapshot.entries.slice(1), entry(50)]);
  await expect(page.locator('.application-log-entry')).toHaveCount(51);
  await expect(disclosure).toBeFocused();
  await expect(page.locator('.application-log-trace').first()).toBeVisible();
  await page.getByRole('navigation', { name: 'Administration sections' })
    .getByRole('link', { name: 'Receiver status', exact: true }).click();
  await expect(page.locator('.application-log-workspace')).toHaveCount(0);
  const requests = state.requests.length;
  await page.waitForTimeout(2_200);
  expect(state.requests).toHaveLength(requests);
});

test('settings capability hides application logs and prevents its request', async ({ page }) => {
  const state = await openLog(page, { allowed: false });
  await expect(page.getByRole('navigation', { name: 'Administration sections' })
    .getByRole('link', { name: 'Application log', exact: true })).toHaveCount(0);
  await expect(page.locator('.application-log-workspace')).toHaveCount(0);
  expect(state.requests).toEqual([]);
});

for (const theme of ['light', 'dark']) {
  for (const [device, viewport] of [['desktop', { width: 1440, height: 1000 }],
    ['mobile', { width: 390, height: 844 }], ['narrow', { width: 320, height: 760 }]]) {
    test(`application log ${theme} ${device} fits long messages and focused details`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await openLog(page, { theme, snapshot: documentFor([
        entry(1), entry(2, 'WARN', 'The radio device is temporarily unavailable. Check its USB connection.'),
        entry(3, 'ERROR', 'Failed to submit a call to the configured destination: ' + 'LongMessage'.repeat(25),
          'java.io.IOException: connection reset\n\tat receiver.upload(Upload.java:17)\n' +
          'Caused by: ' + 'LongDetails'.repeat(30))
      ]) });
      await expect(page.locator('.application-log-entry')).toHaveCount(3);
      await page.getByLabel('Expand details', { exact: true }).check();
      await expect(page.locator('.application-log-trace')).toBeVisible();
      await page.getByRole('button', { name: 'Change audio player size', exact: true }).press('Home');
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await expect(page.locator('body')).toHaveScreenshot(`application-log-${theme}-${device}.png`, { fullPage: true });
    });
  }
}
