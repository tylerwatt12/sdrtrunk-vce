'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;
const enumSource = fs.readFileSync(path.resolve(__dirname,
  '../../../../../src/main/java/io/github/dsheirer/web/auth/WebCapability.java'), 'utf8');
const realPolicies = [...enumSource.matchAll(
  /^\s+[A-Z_]+\("([^"]+)", "([^"]+)", AccessTier\.(PUBLIC|USER|ADMIN)(, false)?\)/gm)]
  .map(([, id, display_name, tier, fixed]) => ({ id, display_name,
    required_tier: tier.toLowerCase(), default_tier: tier.toLowerCase(), configurable: !fixed }));
const changedAt = Date.UTC(2026, 8, 24, 13, 5);
const syntheticPassword = 'synthetic-fixture-only';

test.beforeAll(async () => {
  defaultPreferences = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

function users() {
  return [
    { username: 'field.operator', tier: 'user', primary: false, password_changed_at_epoch_millis: changedAt },
    { username: 'admin', tier: 'admin', primary: true, password_changed_at_epoch_millis: changedAt - 60_000 },
    { username: 'reviewer', tier: 'user', primary: false, password_changed_at_epoch_millis: 0 }
  ];
}

async function openAdmin(page, tab = 'users', options = {}) {
  let accounts = structuredClone(options.users || users());
  let policies = structuredClone(options.policies || realPolicies);
  const preferences = structuredClone(defaultPreferences);
  preferences.appearance.theme = options.theme || 'light';
  const writes = [];
  let sessionRequests = 0;
  await page.route('**/api/v1/**', async route => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const method = request.method();
    const data = value => route.fulfill({ json: { data: value } });
    const failure = message => route.fulfill({ status: 503, json: { error: { status: 503, message } } });
    if (pathname === '/api/v1/auth/session') {
      sessionRequests++;
      await data({ configured: true, authenticated: true, username: 'admin', tier: 'admin', primary: true,
        csrf_token: 'synthetic-csrf-fixture',
        capabilities: Object.fromEntries(realPolicies.map(policy => [policy.id, true])) });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } });
    } else if (pathname === '/api/v1/admin/users' && method === 'GET') {
      await data({ users: accounts, maximum_users: options.maximumUsers ?? 8 });
    } else if (pathname === '/api/v1/admin/users' && method === 'POST') {
      const body = request.postDataJSON();
      writes.push({ pathname, method, body, csrf: request.headers()['x-csrf-token'] });
      accounts.push({ username: body.username, tier: 'user', primary: false,
        password_changed_at_epoch_millis: changedAt });
      await data({ created: true });
    } else if (pathname.startsWith('/api/v1/admin/users/') && ['PUT', 'DELETE'].includes(method)) {
      const username = decodeURIComponent(pathname.split('/').at(-1));
      const body = request.postData() ? request.postDataJSON() : null;
      writes.push({ pathname, method, body, csrf: request.headers()['x-csrf-token'] });
      if (options.failPasswordOnce && method === 'PUT' && writes.filter(write => write.method === 'PUT').length === 1) {
        await failure('Password could not be changed. Try again.');
      } else {
        if (method === 'DELETE') accounts = accounts.filter(account => account.username !== username);
        else accounts.find(account => account.username === username).password_changed_at_epoch_millis = changedAt + 120_000;
        await data({ updated: true });
      }
    } else if (pathname === '/api/v1/admin/access') {
      if (method === 'PUT') {
        const body = request.postDataJSON();
        writes.push({ pathname, method, body, csrf: request.headers()['x-csrf-token'] });
        if (options.failAccessOnce && writes.filter(write => write.pathname === pathname).length === 1) {
          await failure('Page access could not be saved. Try again.');
          return;
        }
        policies.find(policy => policy.id === body.capability).required_tier = body.tier;
      }
      await data({ capabilities: policies });
    } else {
      await route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
    }
  });
  await page.goto(`/app.html?view=admin&tab=${tab}`);
  return { writes, sessionRequests: () => sessionRequests };
}

test('account cards preserve usernames, access levels, timestamps, primary restrictions and managed limits', async ({ page }) => {
  await openAdmin(page, 'users', { maximumUsers: 2 });
  await expect(page.getByRole('heading', { level: 1, name: 'Web accounts' })).toBeVisible();
  await expect(page.locator('.admin-account-card')).toHaveCount(3);
  const primary = page.locator('.admin-account-card[data-username="admin"]');
  await expect(primary.getByRole('heading', { name: 'admin', exact: true })).toBeVisible();
  await expect(primary).toContainText('Primary');
  await expect(primary).toContainText('Admin access');
  await expect(primary).toContainText('2026-09-24 13:04');
  await expect(primary).toContainText('Managed in the desktop application');
  await expect(primary.getByRole('button')).toHaveCount(0);
  const operator = page.locator('.admin-account-card[data-username="field.operator"]');
  await expect(operator).toContainText('User access');
  await expect(operator).toContainText('Password changed');
  await expect(operator).toContainText('2026-09-24 13:05');
  await expect(operator.getByRole('button', { name: 'Change password for field.operator' })).toBeEnabled();
  await expect(operator.getByRole('button', { name: 'Delete field.operator' })).toBeEnabled();
  await expect(page.locator('.admin-accounts-toolbar')).toContainText('2 managed accounts');
  await expect(page.getByRole('button', { name: 'Create account', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Create account', exact: true }))
    .toHaveAttribute('title', 'Account limit reached (2).');
  await expect(page.getByRole('link', { name: 'Manage page access' })).toHaveAttribute('href', /tab=access/);
  await expect(page.locator('.admin-settings-content table')).toHaveCount(0);
});

test('account creation keeps validation, password limits and normalized POST data', async ({ page }) => {
  const app = await openAdmin(page);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Create account', exact: true });
  const username = dialog.locator('input[name=username]');
  const password = dialog.locator('input[name=password]');
  const confirmation = dialog.getByLabel('Confirm password', { exact: true });
  await expect(username).toHaveAttribute('maxlength', '64');
  await expect(password).toHaveAttribute('minlength', '7');
  await expect(password).toHaveAttribute('maxlength', '256');
  await expect(password).toHaveAttribute('type', 'password');
  await expect(confirmation).toHaveAttribute('type', 'password');
  await username.fill('admin');
  await password.fill(syntheticPassword);
  await confirmation.fill(syntheticPassword);
  await dialog.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('The name admin is reserved.');
  expect(app.writes).toHaveLength(0);
  await username.fill(' ＯＰＥＲＡＴＯＲ.New ');
  await confirmation.fill('synthetic-different-fixture');
  await dialog.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(dialog.getByRole('alert')).toHaveText('Passwords do not match.');
  expect(app.writes).toHaveLength(0);
  await confirmation.fill(syntheticPassword);
  await dialog.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.locator('.admin-account-card[data-username="operator.new"]')).toBeVisible();
  expect(app.writes).toEqual([{ pathname: '/api/v1/admin/users', method: 'POST',
    body: { username: 'operator.new', password: syntheticPassword }, csrf: 'synthetic-csrf-fixture' }]);
});

test('password changes keep the account fixed, clear failed passwords and retry the original PUT endpoint', async ({ page }) => {
  const app = await openAdmin(page, 'users', { failPasswordOnce: true });
  const trigger = page.getByRole('button', { name: 'Change password for field.operator' });
  await trigger.click();
  const dialog = page.getByRole('dialog', { name: 'Change password · field.operator' });
  await expect(dialog.locator('input[name=username]')).toBeDisabled();
  await expect(dialog.locator('input[name=username]')).toHaveValue('field.operator');
  await dialog.locator('input[name=password]').fill(syntheticPassword);
  await dialog.getByLabel('Confirm password', { exact: true }).fill(syntheticPassword);
  await dialog.getByRole('button', { name: 'Change password', exact: true }).click();
  await expect(dialog.getByRole('alert')).toHaveText('Password could not be changed. Try again.');
  await expect(dialog.locator('input[name=password]')).toHaveValue('');
  await expect(dialog.getByLabel('Confirm password', { exact: true })).toHaveValue('');
  await expect(dialog.locator('input[name=password]')).toBeFocused();
  await dialog.locator('input[name=password]').fill(syntheticPassword);
  await dialog.getByLabel('Confirm password', { exact: true }).fill(syntheticPassword);
  await dialog.getByRole('button', { name: 'Change password', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.locator('.admin-account-card[data-username="field.operator"]')).toContainText('2026-09-24 13:07');
  expect(app.writes).toHaveLength(2);
  for (const write of app.writes) {
    expect(write).toEqual({ pathname: '/api/v1/admin/users/field.operator', method: 'PUT',
      body: { password: syntheticPassword }, csrf: 'synthetic-csrf-fixture' });
  }
  expect(app.sessionRequests()).toBeGreaterThan(1);
});

test('delete confirmation returns focus to Delete and sends no request until confirmed', async ({ page }) => {
  const app = await openAdmin(page);
  const trigger = page.getByRole('button', { name: 'Delete field.operator', exact: true });
  await trigger.click();
  const dialog = page.getByRole('dialog', { name: 'Delete account · field.operator' });
  await expect(dialog).toContainText('They will be signed out immediately.');
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(trigger).toBeFocused();
  expect(app.writes).toHaveLength(0);
  await trigger.click();
  await dialog.getByRole('button', { name: 'Delete account', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.locator('.admin-account-card[data-username="field.operator"]')).toHaveCount(0);
  await expect(page.locator('.admin-accounts-toolbar')).toContainText('1 managed account');
  expect(app.writes).toEqual([{ pathname: '/api/v1/admin/users/field.operator', method: 'DELETE',
    body: null, csrf: 'synthetic-csrf-fixture' }]);
});

test('page access retains all 19 real policies, three tiers and fixed restrictions in their groups', async ({ page }) => {
  expect(realPolicies).toHaveLength(19);
  await openAdmin(page, 'access');
  await expect(page.getByRole('heading', { level: 1, name: 'Page access' })).toBeVisible();
  await expect(page.locator('.admin-settings-content select.ui-select')).toHaveCount(19);
  await expect(page.getByRole('heading', { name: 'Entire web interface', exact: true })).toBeVisible();
  for (const policy of realPolicies) {
    const select = page.getByRole('combobox', { name: `Minimum access level for ${policy.display_name}` });
    await expect(select.locator('option')).toHaveText(['Public', 'User', 'Admin']);
    await expect(select).toHaveValue(policy.required_tier.toUpperCase());
    if (!policy.configurable || policy.id.startsWith('admin-')) await expect(select).toBeDisabled();
    else await expect(select).toBeEnabled();
  }
  const recordings = page.locator('.ui-section').filter({ has: page.locator('[data-capability="recordings"]') }).last();
  await expect(recordings.locator('[data-capability]')).toHaveCount(2);
  await expect(recordings).toContainText('Recordings search and playback');
  await expect(recordings).toContainText('Manage recordings');
  const administration = page.locator('.ui-section').filter({ has: page.locator('[data-capability="admin-users"]') }).last();
  await expect(administration.locator('[data-capability]')).toHaveCount(8);
  await expect(administration.locator('.ui-permission-fixed')).toHaveCount(8);
  expect(await page.locator('.ui-permission-row').evaluateAll((rows) =>
    rows.map((row) => row.dataset.capability))).toEqual([
    'dashboard', 'live', 'radio', 'call-audio', 'csv-export', 'credits', 'tuner-spectrum', 'user-settings',
    'recordings', 'admin-recordings', 'admin-users', 'admin-access', 'admin-aliases', 'admin-streaming',
    'admin-channels', 'admin-tuners', 'admin-settings', 'receiver-health'
  ]);
  await expect(page.locator('.admin-settings-content table')).toHaveCount(0);
});

test('policy updates roll back failures and preserve dynamically supplied capabilities', async ({ page }) => {
  const dynamic = { id: 'future-feature', display_name: 'Future feature with a server supplied name',
    required_tier: 'user', default_tier: 'admin', configurable: true };
  const futureAdmin = { id: 'admin-future', display_name: 'Future administration',
    required_tier: 'admin', default_tier: 'admin', configurable: true };
  const app = await openAdmin(page, 'access', { failAccessOnce: true,
    policies: [...realPolicies, dynamic, futureAdmin] });
  const select = page.getByRole('combobox', { name: `Minimum access level for ${dynamic.display_name}` });
  await expect(select).toHaveValue('USER');
  await expect(page.getByRole('combobox', { name: `Minimum access level for ${futureAdmin.display_name}` })).toBeDisabled();
  await select.selectOption('ADMIN');
  await expect(page.locator('.admin-operation-status')).toHaveText('Page access could not be saved. Try again.');
  await expect(page.locator('.admin-operation-status')).toHaveClass(/ui-notice-danger/);
  await expect(select).toHaveValue('USER');
  await expect(select).toBeEnabled();
  await select.selectOption('PUBLIC');
  await expect(page.locator('.admin-operation-status')).toHaveText(`${dynamic.display_name} now requires Public access.`);
  await expect(select).toHaveValue('PUBLIC');
  expect(app.writes.map(write => write.body)).toEqual([
    { capability: 'future-feature', tier: 'admin' }, { capability: 'future-feature', tier: 'public' }
  ]);
  expect(app.sessionRequests()).toBeGreaterThan(1);
});

for (const [tab, label] of [['users', 'Web accounts'], ['access', 'Page access']]) {
  test(`${label} fits desktop themes and 320px content with long names`, async ({ page }) => {
    for (const [name, viewport, theme] of [
      ['desktop-light', { width: 1440, height: 1000 }, 'light'],
      ['desktop-dark', { width: 1440, height: 1000 }, 'dark'],
      ['mobile-dark', { width: 320, height: 844 }, 'dark']
    ]) {
      await page.setViewportSize(viewport);
      await page.emulateMedia({ colorScheme: theme });
      const longUsername = 'operations.team.account.with.a.long.but.valid.descriptive.username';
      const longPolicy = { id: 'future-feature', display_name: 'Future feature with a long descriptive server supplied page and feature name',
        required_tier: 'user', default_tier: 'admin', configurable: true };
      await openAdmin(page, tab, { theme,
        users: [...users(), { username: longUsername, tier: 'user', primary: false,
          password_changed_at_epoch_millis: changedAt }], policies: [...realPolicies, longPolicy] });
      await expect(page.getByRole('heading', { level: 1, name: label })).toBeVisible();
      const content = page.locator('.admin-settings-content');
      if (tab === 'users') {
        await expect(content.getByRole('heading', { name: longUsername, exact: true })).toBeVisible();
        await page.getByRole('button', { name: 'Change password for field.operator' }).click();
        const dialog = page.getByRole('dialog', { name: 'Change password · field.operator' });
        await expect(dialog).toBeVisible();
        expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
        await page.keyboard.press('Escape');
      } else await expect(content.getByRole('combobox', { name: `Minimum access level for ${longPolicy.display_name}` })).toBeVisible();
      expect(await content.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
      await page.screenshot({ path: `build/playwright-results/admin-${tab}-${name}.png`, fullPage: true });
      await page.unrouteAll({ behavior: 'wait' });
    }
  });
}
