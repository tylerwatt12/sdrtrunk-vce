'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');

const root = path.resolve(__dirname, '../../../../..');
const guide = page => page.locator('.ui-setup-guide');
const choice = page => guide(page).getByRole('checkbox', { name: 'Skip guides on other pages', exact: true });
const helper = 'Page guides use examples to explain receiving, aliases, Scan Lists, and playback. Open any guide later with the ? button in the header.';
const defaultIdentity = () => ({ account: 'fixture-admin', scope: 'account-1-100' });
const capabilities = Object.fromEntries([...fs.readFileSync(path.join(root,
  'src/main/java/io/github/dsheirer/web/auth/WebCapability.java'), 'utf8')
  .matchAll(/^\s+[A-Z_]+\("([^"]+)",/gm)].map(([, id]) => [id, true]));

async function accountServer(theme = 'light') {
  const { defaults } = await import(pathToFileURL(path.join(root, 'stats-web/assets/core/preference-schema.js')).href);
  const server = { accounts: new Map(), writes: [], failWrites: false, writeGate: null };
  server.record = identity => {
    const key = JSON.stringify([identity.account, identity.scope]);
    if (!server.accounts.has(key)) {
      const preferences = structuredClone(defaults);
      preferences.appearance.theme = theme;
      server.accounts.set(key, { revision: 1, preferences, dismissed: {} });
    }
    return server.accounts.get(key);
  };
  return server;
}

async function install(page, server, { identity = defaultIdentity(), theme = 'light' } = {}) {
  const fixture = await installSiteStyleApplication(page, theme, undefined, { guides: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
    configured: true, authenticated: true, username: identity.account, tier: 'admin', primary: true,
    guide_dismissal_scope: identity.scope, csrf_token: 'fixture-csrf', capabilities
  } } }));
  await page.route('**/api/v1/me/preferences', async route => {
    const request = route.request(), owner = { ...identity }, record = server.record(owner);
    if (request.method() === 'PUT') {
      const write = { owner, body: request.postDataJSON(), csrf: request.headers()['x-csrf-token'],
        revision: request.headers()['if-match'] };
      server.writes.push(write);
      const gate = server.writeGate;
      if (gate) await gate;
      if (server.failWrites) return route.fulfill({ status: 503,
        json: { error: { message: 'Example preferences temporarily unavailable.' } } });
      expect(write.csrf).toBe('fixture-csrf');
      expect(write.revision).toBe(`"${record.revision}"`);
      record.preferences = structuredClone(write.body);
      record.revision++;
    }
    return route.fulfill({ json: { revision: record.revision, preferences: record.preferences } });
  });
  await page.route('**/api/v1/me/tour-dismissals', route => {
    const record = server.record(identity), request = route.request();
    if (request.method() === 'PUT') for (const [id, version] of Object.entries(request.postDataJSON().dismissed))
      record.dismissed[id] = Math.max(record.dismissed[id] || 0, version);
    return route.fulfill({ json: { data: { version: 1, dismissed: record.dismissed } } });
  });
  return fixture;
}

async function open(page, view = 'channel-setup', query = '') {
  await page.goto(`/app.html?view=${view}${query}`);
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  await expect(page.locator('#page-guide-button')).toBeVisible();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

function expectClean(fixture) {
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
}

async function saveSkip(page, server) {
  await expect(guide(page)).toHaveAttribute('data-guide-id', 'welcome');
  await expect(choice(page)).not.toBeChecked();
  await choice(page).check();
  await expect(choice(page)).toBeEnabled();
  await expect(choice(page)).toBeChecked();
  expect(server.record(defaultIdentity()).preferences.guides.automatic_page_guides).toBe(false);
}

for (const theme of ['light', 'dark']) test(`Welcome presents the account guide choice with readable copy in ${theme}`, async ({ page }, testInfo) => {
  const server = await accountServer(theme), fixture = await install(page, server, { theme });
  await page.setViewportSize(theme === 'dark' ? { width: 1280, height: 720 } : { width: 1280, height: 900 });
  await open(page);
  await expect(guide(page).getByRole('heading', { name: 'Welcome to sdrtrunk VCE', exact: true })).toBeVisible();
  await expect(choice(page)).not.toBeChecked();
  await expect(choice(page)).toHaveClass(/ui-selection-check/);
  await expect(choice(page).locator('..')).toHaveClass(/ui-choice-card/);
  await expect(choice(page)).toHaveAccessibleDescription(helper);
  await expect(choice(page)).toBeInViewport();
  const checkboxBounds = await choice(page).boundingBox();
  const detailBounds = await choice(page).locator('..').locator('.ui-heading-group').boundingBox();
  expect(checkboxBounds.x + checkboxBounds.width).toBeLessThan(detailBounds.x);
  expect(Math.abs(checkboxBounds.y - detailBounds.y)).toBeLessThan(4);
  await expect(guide(page).getByRole('button', { name: 'Next', exact: true })).toBeInViewport();
  await choice(page).focus();
  await page.keyboard.press('Space');
  await expect(choice(page)).toBeEnabled();
  await expect(choice(page)).toBeChecked();
  expect(server.writes).toHaveLength(1);
  expect(server.writes[0].body.version).toBe(13);
  expect(server.writes[0].body.guides).toEqual({ automatic_page_guides: false });
  const evidence = process.env.WELCOME_GUIDE_PREFERENCE_EVIDENCE;
  if (evidence) fs.mkdirSync(evidence, { recursive: true });
  await page.screenshot({ path: evidence ? path.join(evidence, `welcome-guide-choice-${theme}.png`) :
    testInfo.outputPath(`welcome-guide-choice-${theme}.png`) });
  await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
  await expect(choice(page)).toHaveCount(0);
  await guide(page).getByRole('button', { name: 'Back', exact: true }).click();
  await expect(choice(page)).toBeChecked();
  expectClean(fixture);
});

test('skipping page guides finishes Welcome without a handoff and leaves Help and URL replay available', async ({ page }) => {
  const server = await accountServer(), fixture = await install(page, server);
  await open(page);
  await saveSkip(page, server);
  for (let step = 1; step < 11; step++) await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
  await expect(guide(page)).toContainText('Step 11 of 11');
  await expect(guide(page).getByRole('button', { name: 'Set up channels', exact: true })).toHaveCount(0);
  await guide(page).getByRole('button', { name: 'Finish', exact: true }).click();
  await expect(guide(page)).toHaveCount(0);
  await expect.poll(() => server.record(defaultIdentity()).dismissed).toEqual({ welcome: 1 });
  await open(page, 'aliases');
  await expect(guide(page)).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await expect(guide(page)).toHaveAttribute('data-guide-id', 'aliases');
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await guide(page).getByRole('button', { name: 'Close guide', exact: true }).click();
  await open(page, 'tuners', '&guide=tuners');
  await expect(guide(page)).toHaveAttribute('data-guide-id', 'tuners');
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  expectClean(fixture);
});

test('the account choice survives clearing browser data and a fresh browser context', async ({ page, browser }) => {
  const server = await accountServer(), fixture = await install(page, server);
  await open(page);
  await saveSkip(page, server);
  await guide(page).getByRole('button', { name: 'Close guide', exact: true }).click();
  await expect.poll(() => server.record(defaultIdentity()).dismissed.welcome).toBe(1);
  await page.evaluate(() => localStorage.clear());
  await page.reload();
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  await expect(guide(page)).toHaveCount(0);
  const context = await browser.newContext();
  try {
    const fresh = await context.newPage(), freshFixture = await install(fresh, server);
    await open(fresh, 'scan-lists');
    await expect(guide(fresh)).toHaveCount(0);
    expect(await fresh.evaluate(() => Object.keys(localStorage).filter(key => key.startsWith('sdrtrunk-vce-guide:')))).toEqual([]);
    await open(fresh, 'scan-lists', '&guide=welcome');
    await expect(choice(fresh)).toBeChecked();
    const other = await context.newPage();
    const otherIdentity = { account: 'example-listener', scope: 'account-2-200' };
    const otherFixture = await install(other, server, { identity: otherIdentity });
    await open(other);
    await expect(choice(other)).not.toBeChecked();
    expect(server.record(otherIdentity).preferences.guides.automatic_page_guides).toBe(true);
    expect(server.record(defaultIdentity()).preferences.guides.automatic_page_guides).toBe(false);
    expectClean(otherFixture);
    expectClean(freshFixture);
  } finally { await context.close(); }
  expect(server.writes).toHaveLength(1);
  expectClean(fixture);
});

test('a failed account save rolls the checkbox back and allows an explicit retry', async ({ page }) => {
  const server = await accountServer(), fixture = await install(page, server);
  server.failWrites = true;
  await open(page);
  await choice(page).click();
  await expect(guide(page).getByRole('alert')).toHaveText('Your guide choice could not be saved. Try the checkbox again.');
  await expect(choice(page)).toBeEnabled();
  await expect(choice(page)).not.toBeChecked();
  await expect(guide(page).getByRole('button', { name: 'Next', exact: true })).toBeEnabled();
  expect(server.writes).toHaveLength(1);
  expect(server.record(defaultIdentity()).preferences.guides.automatic_page_guides).toBe(true);
  server.failWrites = false;
  await saveSkip(page, server);
  await expect(guide(page).getByRole('alert')).toBeHidden();
  expect(server.writes).toHaveLength(2);
  expectClean(fixture);
});

test('a pending save blocks advancing but still permits dismissal without reopening the guide', async ({ page }) => {
  const server = await accountServer(), fixture = await install(page, server);
  let release;
  server.writeGate = new Promise(resolve => { release = resolve; });
  await open(page);
  await choice(page).check();
  await expect.poll(() => server.writes.length).toBe(1);
  await expect(choice(page)).toBeDisabled();
  await expect(guide(page).getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
  await expect(guide(page).getByRole('button', { name: 'Close guide', exact: true })).toBeEnabled();
  await guide(page).getByRole('button', { name: 'Close guide', exact: true }).click();
  await expect(guide(page)).toHaveCount(0);
  release();
  await expect.poll(() => server.record(defaultIdentity()).preferences.guides.automatic_page_guides).toBe(false);
  await expect(guide(page)).toHaveCount(0);
  await open(page, 'channel-setup', '&guide=welcome');
  await expect(choice(page)).toBeChecked();
  expectClean(fixture);
});
