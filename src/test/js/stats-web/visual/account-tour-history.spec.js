'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');

const guide = page => page.locator('.ui-setup-guide');
const key = (account, scope, id, version) =>
  `sdrtrunk-vce-guide:v1:${encodeURIComponent(account)}:${encodeURIComponent(scope)}:${id}:${version}`;
const defaultIdentity = () => ({ account: 'fixture-admin', scope: 'account-1-100' });
const capabilities = Object.fromEntries([...fs.readFileSync(path.resolve(__dirname,
  '../../../../../src/main/java/io/github/dsheirer/web/auth/WebCapability.java'), 'utf8')
  .matchAll(/^\s+[A-Z_]+\("([^"]+)",/gm)].map(([, id]) => [id, true]));

function accountServer() {
  const accounts = new Map(), requests = [];
  const server = { accounts, requests, failReads: false, failWrites: false, readGate: null };
  server.record = identity => {
    const id = JSON.stringify([identity.account, identity.scope]);
    if (!accounts.has(id)) accounts.set(id, {});
    return accounts.get(id);
  };
  return server;
}

async function install(page, server, { identity = defaultIdentity(), legacy = {}, theme = 'light' } = {}) {
  const fixture = await installSiteStyleApplication(page, theme, undefined, { guides: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.addInitScript(entries => { for (const [name, value] of Object.entries(entries)) localStorage.setItem(name, value); }, legacy);
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
    configured: true, authenticated: true, username: identity.account, tier: 'admin', primary: true,
    guide_dismissal_scope: identity.scope, csrf_token: 'fixture-csrf', capabilities
  } } }));
  await page.route('**/api/v1/me/tour-dismissals', async route => {
    const request = route.request();
    const owner = { ...identity };
    const entry = { method: request.method(), owner, body: request.postDataJSON(), csrf: request.headers()['x-csrf-token'] };
    server.requests.push(entry);
    if (request.method() === 'GET' && server.readGate) await server.readGate;
    if (request.method() === 'GET' && server.failReads || request.method() === 'PUT' && server.failWrites)
      return route.fulfill({ status: 503, json: { error: { message: 'Example receiver unavailable.' } } });
    const dismissed = server.record(owner);
    if (request.method() === 'PUT') {
      expect(entry.body.version).toBe(1);
      expect(entry.csrf).toBe('fixture-csrf');
      for (const [id, version] of Object.entries(entry.body.dismissed)) dismissed[id] = Math.max(dismissed[id] || 0, version);
    }
    await route.fulfill({ json: { data: { version: 1, dismissed: { ...dismissed } } } });
  });
  return fixture;
}

async function open(page) {
  await page.goto('/app.html?view=admin&tab=health');
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  await expect(page.locator('#page-guide-button')).toBeVisible();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

function expectClean(fixture) {
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
}

test('saved account history keeps tours closed in another browser and after clearing site data', async ({ page, browser }) => {
  const server = accountServer();
  const owner = defaultIdentity();
  const fixture = await install(page, server);
  await open(page);
  await expect(guide(page)).toHaveAttribute('data-guide-id', 'welcome');
  await page.keyboard.press('Escape');
  await expect.poll(() => server.record(owner).welcome).toBe(1);
  await page.locator('#page-guide-button').click();
  await expect(guide(page)).toHaveAttribute('data-guide-id', 'admin');
  await guide(page).getByRole('button', { name: 'Receiver status', exact: true }).click();
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'health-purpose');
  await guide(page).getByRole('button', { name: 'Finish', exact: true }).click();
  await expect.poll(() => server.record(owner).admin).toBe(3);
  await page.evaluate(() => localStorage.clear());
  await page.reload();
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  await expect(guide(page)).toHaveCount(0);
  const freshContext = await browser.newContext();
  try {
    const fresh = await freshContext.newPage();
    const freshFixture = await install(fresh, server);
    await open(fresh);
    expect(await fresh.evaluate(() => Object.keys(localStorage).filter(name => name.startsWith('sdrtrunk-vce-guide:')))).toEqual([]);
    await expect(guide(fresh)).toHaveCount(0);
    await fresh.locator('#page-guide-button').click();
    await expect(guide(fresh)).toHaveAttribute('data-guide-step', 'overview');
    await guide(fresh).getByRole('button', { name: 'Close guide', exact: true }).click();
    expectClean(freshFixture);
  } finally { await freshContext.close(); }
  expect(server.requests.filter(request => request.method === 'PUT').map(request => request.body.dismissed))
    .toEqual([{ welcome: 1 }, { admin: 3 }]);
  expectClean(fixture);
});

test('legacy browser dismissals migrate only their known versions for the exact account and receiver', async ({ page }) => {
  const server = accountServer(), owner = defaultIdentity();
  const legacy = {
    [key(owner.account, owner.scope, 'welcome', 1)]: '1',
    [key(owner.account, owner.scope, 'admin', 2)]: '1',
    [key(owner.account, owner.scope, 'recordings', 4)]: '1',
    [key('other-user', owner.scope, 'admin', 3)]: '1',
    [key(owner.account, 'other-scope', 'admin', 3)]: '1',
    [key(owner.account, owner.scope, 'admin', 99)]: '1'
  };
  const fixture = await install(page, server, { legacy });
  await open(page);
  await expect.poll(() => server.record(owner)).toEqual({ welcome: 1, admin: 2, recordings: 4 });
  await expect(guide(page)).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await expect(guide(page)).toHaveAttribute('data-guide-id', 'admin');
  expect(await page.evaluate(names => Object.fromEntries(names.map(name => [name, localStorage.getItem(name)])), Object.keys(legacy)))
    .toEqual(legacy);
  await page.keyboard.press('Escape');
  await expect.poll(() => server.record(owner).admin).toBe(3);
  expectClean(fixture);
});

test('failed history reads suppress automatic tours and keep manual Help and Retry available', async ({ page }) => {
  const server = accountServer(), owner = defaultIdentity();
  server.failReads = true;
  const fixture = await install(page, server, { legacy: {
    [key(owner.account, owner.scope, 'welcome', 1)]: '1', [key(owner.account, owner.scope, 'admin', 3)]: '1'
  } });
  await open(page);
  await expect(page.locator('#guide-history-status')).toContainText('Tour history could not be loaded');
  await expect(guide(page)).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await expect(guide(page)).toHaveAttribute('data-guide-id', 'admin');
  await page.keyboard.press('Escape');
  expect(server.requests.filter(request => request.method === 'PUT')).toEqual([]);
  server.failReads = false;
  await page.locator('#guide-history-status').getByRole('button', { name: 'Retry', exact: true }).click();
  await expect.poll(() => server.record(owner)).toEqual({ welcome: 1, admin: 3 });
  await expect(page.locator('#guide-history-status')).toHaveCount(0);
  await expect(guide(page)).toHaveCount(0);
  expectClean(fixture);
});

test('a failed dismissal save survives reload and retries without reopening the tour', async ({ page }) => {
  const server = accountServer(), owner = defaultIdentity();
  Object.assign(server.record(owner), { welcome: 1 });
  server.failWrites = true;
  const fixture = await install(page, server);
  await open(page);
  await expect(guide(page)).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await expect(guide(page)).toHaveAttribute('data-guide-id', 'admin');
  await page.keyboard.press('Escape');
  await expect(page.locator('#guide-history-status')).toContainText('could not be saved to your account');
  expect(server.record(owner)).toEqual({ welcome: 1 });
  await page.reload();
  await expect(page.locator('#guide-history-status')).toContainText('could not be saved to your account');
  await expect(guide(page)).toHaveCount(0);
  server.failWrites = false;
  await page.locator('#guide-history-status').getByRole('button', { name: 'Retry', exact: true }).click();
  await expect.poll(() => server.record(owner).admin).toBe(3);
  await expect(page.locator('#guide-history-status')).toHaveCount(0);
  await expect(guide(page)).toHaveCount(0);
  expectClean(fixture);
});

test('a different sign-in and a recreated account keep independent tour history', async ({ page }) => {
  const server = accountServer(), identity = defaultIdentity();
  Object.assign(server.record(identity), { welcome: 1, admin: 3 });
  const fixture = await install(page, server, { identity, legacy: {
    [key(identity.account, identity.scope, 'welcome', 1)]: '1', [key(identity.account, identity.scope, 'admin', 3)]: '1'
  } });
  await open(page);
  await expect(guide(page)).toHaveCount(0);
  identity.account = 'example-listener'; identity.scope = 'account-2-200';
  await page.reload();
  await expect(guide(page)).toHaveAttribute('data-guide-id', 'welcome');
  await page.keyboard.press('Escape');
  await expect.poll(() => server.record(identity)).toEqual({ welcome: 1 });
  identity.account = 'fixture-admin'; identity.scope = 'account-3-300';
  await page.reload();
  await expect(guide(page)).toHaveAttribute('data-guide-id', 'welcome');
  await page.keyboard.press('Escape');
  await expect.poll(() => server.record(identity)).toEqual({ welcome: 1 });
  expect(server.accounts.get(JSON.stringify(['fixture-admin', 'account-1-100']))).toEqual({ welcome: 1, admin: 3 });
  expectClean(fixture);
});

test('manual dismissal during the initial account read is merged after that read completes', async ({ page }) => {
  const server = accountServer(), owner = defaultIdentity();
  let release;
  server.readGate = new Promise(resolve => { release = resolve; });
  Object.assign(server.record(owner), { welcome: 1 });
  const fixture = await install(page, server);
  try {
    await open(page);
    await expect(guide(page)).toHaveCount(0);
    await page.locator('#page-guide-button').click();
    await expect(guide(page)).toHaveAttribute('data-guide-id', 'admin');
    await page.keyboard.press('Escape');
    expect(server.requests.filter(request => request.method === 'PUT')).toEqual([]);
  } finally { release(); }
  await expect.poll(() => server.record(owner)).toEqual({ welcome: 1, admin: 3 });
  await expect(guide(page)).toHaveCount(0);
  expectClean(fixture);
});

for (const [viewportName, viewport] of [['desktop', { width: 1440, height: 1000 }],
  ['phone', { width: 390, height: 844 }]]) for (const theme of ['light', 'dark']) {
  test(`tour and My Settings failures stay readable together in ${theme} on ${viewportName}`, async ({ page }, testInfo) => {
    const server = accountServer(); server.failReads = true;
    const fixture = await install(page, server, { theme });
    await page.setViewportSize(viewport);
    await page.route('**/api/v1/me/preferences', route => route.request().method() === 'PUT' ?
      route.fulfill({ status: 503, json: { error: { message: 'Example preferences temporarily unavailable.' } } }) :
      route.fallback());
    await page.goto('/app.html?view=admin&tab=health');
    await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
    await page.getByRole('button', { name: `Use ${theme === 'dark' ? 'light' : 'dark'} theme`, exact: true }).click();
    const preferences = page.locator('#preference-status'), history = page.locator('#guide-history-status');
    await expect(preferences).toBeVisible();
    await expect(history).toBeVisible();
    await expect(preferences.getByRole('button', { name: 'Retry', exact: true })).toBeInViewport();
    await expect(history.getByRole('button', { name: 'Retry', exact: true })).toBeInViewport();
    if (theme === 'dark') await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    else await expect(page.locator('html')).not.toHaveAttribute('data-theme', 'dark');
    const a = await preferences.boundingBox(), b = await history.boundingBox();
    expect(a.y + a.height).toBeLessThanOrEqual(b.y);
    expect(a.x).toBeGreaterThanOrEqual(0);
    expect(a.x + a.width).toBeLessThanOrEqual(viewport.width);
    expect(b.x).toBeGreaterThanOrEqual(0);
    expect(b.x + b.width).toBeLessThanOrEqual(viewport.width);
    expect(b.y + b.height).toBeLessThanOrEqual(viewport.height);
    const directory = process.env.ACCOUNT_TOUR_HISTORY_EVIDENCE;
    const filename = `account-history-simultaneous-errors-${theme}${viewportName === 'phone' ? '-phone' : ''}.png`;
    if (directory) fs.mkdirSync(directory, { recursive: true });
    await page.screenshot({ path: directory ? path.join(directory, filename) : testInfo.outputPath(filename) });
    server.failReads = false;
    await history.getByRole('button', { name: 'Retry', exact: true }).click();
    await expect(history).toHaveCount(0);
    await expect(preferences).toBeVisible();
    expectClean(fixture);
  });
}
