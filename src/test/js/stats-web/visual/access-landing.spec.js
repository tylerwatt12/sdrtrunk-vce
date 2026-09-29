'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

function session(webAccess, authenticated = false) {
  return {
    configured: true,
    authenticated,
    username: authenticated ? 'listener' : null,
    tier: authenticated ? 'user' : 'public',
    primary: false,
    csrf_token: authenticated ? 'test-csrf-token' : null,
    capabilities: { 'web-access': webAccess, dashboard: webAccess }
  };
}

async function mockAccess(page, initialWebAccess, options = {}) {
  const state = { session: session(initialWebAccess, options.authenticated === true), logins: [],
    sessionRequests: 0 };
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    if (pathname === '/api/v1/auth/session') {
      state.sessionRequests += 1;
      if (options.sessionGate) await options.sessionGate;
      if (state.sessionRequests <= (options.sessionFailures || 0)) {
        await route.fulfill({ status: 503, json: { error: { message: 'Session unavailable' } } });
      } else {
        await route.fulfill({ json: { data: state.session } });
      }
    } else if (pathname === '/api/v1/auth/login') {
      state.logins.push(request.postDataJSON());
      if (options.rejectFirstLogin && state.logins.length === 1) {
        await route.fulfill({ status: 401, json: { error: { message: 'Invalid credentials' } } });
        return;
      }
      state.session = session(options.loginWebAccess !== false, true);
      await route.fulfill({ json: { data: state.session } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences: defaultPreferences } });
    } else if (pathname === '/api/v1/status') {
      await route.fulfill({ json: { data: {} } });
    } else {
      await route.fulfill({ status: 404, json: { error: { message: 'Unavailable in this browser test' } } });
    }
  });
  return state;
}

test('the app shell stays hidden while access is being checked', async ({ page }) => {
  let releaseSession;
  const sessionGate = new Promise((resolve) => { releaseSession = resolve; });
  await mockAccess(page, false, { sessionGate });
  try {
    await page.goto('/app.html?view=dashboard', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('.access-landing')).toBeVisible();
    await expect(page.locator('.app-shell')).toBeHidden();
    await expect(page.locator('a:visible')).toHaveCount(0);
  } finally {
    releaseSession();
  }
  await expect(page.locator('.access-login-form')).toBeVisible();
});

test('access retry remains available after another session failure', async ({ page }) => {
  const state = await mockAccess(page, false, { sessionFailures: 2 });
  await page.goto('/app.html?view=dashboard');

  const landing = page.locator('.access-landing');
  const retry = landing.getByRole('button', { name: 'Retry' });
  await expect(retry).toBeEnabled();
  await expect(page.locator('.app-shell')).toBeHidden();
  await retry.click();
  await expect(retry).toBeEnabled();
  await expect(page.locator('.app-shell')).toBeHidden();
  await retry.click();
  await expect(landing.locator('.access-login-form')).toBeVisible();
  expect(state.sessionRequests).toBe(3);
});

async function expectInsideViewport(page, locator) {
  const bounds = await locator.boundingBox();
  const viewport = page.viewportSize();
  expect(bounds).not.toBeNull();
  expect(bounds.x).toBeGreaterThanOrEqual(0);
  expect(bounds.y).toBeGreaterThanOrEqual(0);
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width + 1);
  expect(bounds.y + bounds.height).toBeLessThanOrEqual(viewport.height + 1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth))
    .toBeLessThanOrEqual(viewport.width + 1);
}

for (const [name, viewport] of [
  ['desktop', { width: 1440, height: 900 }],
  ['mobile', { width: 390, height: 844 }]
]) {
  test(`visitor without web access sees only the ${name} landing`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await mockAccess(page, false);
    await page.goto('/app.html?view=dashboard');

    const landing = page.locator('.access-landing');
    await expect(landing).toBeVisible();
    await expect(page.locator('.app-shell')).toBeHidden();
    await expect(page.locator('.topbar')).toBeHidden();
    await expect(page.locator('.playback-bar')).toBeHidden();
    await expect(page.locator('a:visible')).toHaveCount(0);
    await expect(page.locator('.access-login-form')).toBeVisible();
    await expect(landing.getByLabel('Username')).toBeVisible();
    await expect(landing.getByLabel('Password')).toBeVisible();
    await expect(landing.getByRole('button', { name: 'Sign In' })).toBeVisible();

    const exposedShellControls = await page.locator('.app-shell').evaluate((shell) =>
      [...shell.querySelectorAll('a[href], button, input, select, textarea, [tabindex]')]
        .filter((element) => element.getClientRects().length > 0 && !element.disabled &&
          element.tabIndex >= 0).map((element) => element.outerHTML));
    expect(exposedShellControls).toEqual([]);
    await expectInsideViewport(page, landing.locator('.access-login-form'));
  });
}

test('landing sign-in restores the app shell after access is granted', async ({ page }) => {
  const state = await mockAccess(page, false);
  await page.goto('/app.html?view=dashboard');

  const form = page.locator('.access-login-form');
  await expect(form).toBeVisible();
  await form.getByLabel('Username').fill('listener');
  await form.getByLabel('Password').fill('test-password');
  await form.getByRole('button', { name: 'Sign In' }).click();

  await expect(page.locator('.access-landing')).toBeHidden();
  await expect(page.locator('.app-shell')).toBeVisible();
  await expect(page.locator('.topbar')).toBeVisible();
  await expect(page.locator('#auth-action')).toHaveText('Sign Out');
  expect(state.logins).toEqual([{ username: 'listener', password: 'test-password' }]);
});

test('restricted signed-in account can switch accounts without exposing the shell', async ({ page }) => {
  const state = await mockAccess(page, false, { authenticated: true, rejectFirstLogin: true });
  await page.goto('/app.html?view=dashboard');

  const form = page.locator('.access-login-form');
  await expect(form).toBeVisible();
  await expect(page.locator('.app-shell')).toBeHidden();
  await form.getByLabel('Username').fill('authorized');
  await form.getByLabel('Password').fill('wrong-password');
  await form.getByRole('button', { name: 'Sign In' }).click();
  await expect(form.getByRole('alert')).toContainText('The username or password was not accepted.');
  await expect(page.locator('.app-shell')).toBeHidden();

  await form.getByLabel('Password').fill('valid-password');
  await form.getByRole('button', { name: 'Sign In' }).click();
  await expect(page.locator('.access-landing')).toBeHidden();
  await expect(page.locator('.app-shell')).toBeVisible();
  expect(state.logins).toEqual([
    { username: 'authorized', password: 'wrong-password' },
    { username: 'authorized', password: 'valid-password' }
  ]);
});

test('public web access retains a compact sign-in dialog', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await mockAccess(page, true);
  await page.goto('/app.html?view=dashboard');

  await expect(page.locator('.app-shell')).toBeVisible();
  await expect(page.locator('.access-landing')).toBeHidden();
  const action = page.locator('#auth-action');
  await expect(action).toHaveText('Sign In');
  await action.click();

  const dialog = page.getByRole('dialog', { name: 'Sign In' });
  await expect(dialog).toHaveClass(/access-login-modal/);
  await expect(dialog.locator('.login-form')).toBeVisible();
  await expect(dialog.getByLabel('Username')).toBeVisible();
  await expect(dialog.getByLabel('Password')).toBeVisible();
  const bounds = await dialog.boundingBox();
  expect(bounds.width).toBeLessThanOrEqual(520);
  await expectInsideViewport(page, dialog);

  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(action).toBeFocused();
});

test('signing into a restricted account closes the dialog and focuses the landing', async ({ page }) => {
  const state = await mockAccess(page, true, { loginWebAccess: false });
  await page.goto('/app.html?view=dashboard');
  await page.locator('#auth-action').click();

  const dialog = page.getByRole('dialog', { name: 'Sign In' });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel('Username').fill('restricted');
  await dialog.getByLabel('Password').fill('test-password');
  await dialog.getByRole('button', { name: 'Sign In', exact: true }).click();

  await expect(dialog).toBeHidden();
  await expect(page.locator('.app-shell')).toBeHidden();
  await expect(page.locator('.access-landing')).toBeVisible();
  await expect(page.locator('.access-login-form').getByLabel('Username')).toBeFocused();
  await expect(page.locator('a:visible')).toHaveCount(0);
  expect(state.logins).toEqual([{ username: 'restricted', password: 'test-password' }]);
});

test('a small phone can use the landing and compact dialog without horizontal scrolling', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 });
  const state = await mockAccess(page, false);
  await page.goto('/app.html?view=dashboard');

  await expect(page.locator('.access-login-form')).toBeVisible();
  await expect(page.locator('.access-login-form').getByLabel('Username')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(321);

  state.session = session(true);
  await page.reload();
  await expect(page.locator('.app-shell')).toBeVisible();
  await page.locator('#auth-action').click();
  const dialog = page.getByRole('dialog', { name: 'Sign In' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByLabel('Username')).toBeVisible();
  await expect(dialog.getByLabel('Password')).toBeVisible();
  const bounds = await dialog.boundingBox();
  expect(bounds.x).toBeGreaterThanOrEqual(0);
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(321);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(321);
});

test('reduced-motion visitors can use the landing form', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await mockAccess(page, false);
  await page.goto('/app.html?view=dashboard');

  await expect(page.locator('.access-landing')).toBeVisible();
  expect(await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches)).toBe(true);
  const username = page.locator('.access-login-form').getByLabel('Username');
  await username.fill('listener');
  await expect(username).toHaveValue('listener');
  await expectInsideViewport(page, page.locator('.access-login-form'));
});
