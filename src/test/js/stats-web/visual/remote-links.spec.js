'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

const senderId = '11111111-1111-4111-8111-111111111111';
const fixtureSecret = 'fixture-shared-secret-not-for-use';

function snapshot() {
  return {
    revision: 'fixture-revision',
    listener: { enabled: false, bind_address: '0.0.0.0', port: 53800, state: 'DISABLED', dependencies: [] },
    sender_connection: { enabled: false, state: 'DISABLED', credential_configured: false,
      exported_channel_configuration_ids: [] },
    senders: [{ sender_id: senderId, display_name: 'Field receiver', state: 'CONNECTED',
      last_seen_at_ms: Date.now() + 60_000, default_alias_list_id: 1,
      feeds: [{ feed_id: 'north-control', display_name: 'Metro North', protocol: 'P25_PHASE1',
        frequency_hz: 855_862_500, system_name: 'Metro', site_name: 'North', state: 'CONNECTED',
        channel_configuration_id: 'local-channel-1', alias_list_id: 1, enabled: true }] }],
    alias_lists: [{ alias_list_id: 1, name: 'P25' }],
    export_channel_options: [{ channel_configuration_id: 'local-channel-1', name: 'Metro North',
      system_name: 'Metro', site_name: 'North', protocol: 'P25_PHASE1' }]
  };
}

async function openApp(page, theme = 'light') {
  const preferences = structuredClone(defaultPreferences);
  preferences.appearance.theme = theme;
  let current = snapshot();
  let reads = 0;
  let revocations = 0;
  let revocationBlock = null;
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: { data: { configured: true, authenticated: true,
        username: 'admin', tier: 'admin', primary: true,
        capabilities: { 'admin-settings': true, 'receiver-health': true, credits: true } } } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } });
    } else if (pathname === '/api/v1/admin/remote-links' && route.request().method() === 'GET') {
      reads += 1;
      await route.fulfill({ json: { data: current } });
    } else if (pathname === '/api/v1/admin/remote-links/senders' && route.request().method() === 'POST') {
      await route.fulfill({ json: { data: { sender_id: senderId, secret: fixtureSecret } } });
    } else if (pathname === `/api/v1/admin/remote-links/senders/${senderId}` &&
      route.request().method() === 'DELETE') {
      revocations += 1;
      if (revocationBlock) {
        const error = await revocationBlock;
        revocationBlock = null;
        if (error) {
          await route.fulfill({ status: 503, json: { error: { status: 503, message: error } } });
          return;
        }
      }
      current = { ...current, senders: [] };
      await route.fulfill({ json: { data: current } });
    } else if (pathname === `/api/v1/admin/remote-links/senders/${senderId}` &&
      route.request().method() === 'PUT') {
      const body = route.request().postDataJSON();
      current = { ...current, senders: [{ ...current.senders[0],
        display_name: body.display_name, default_alias_list_id: body.default_alias_list_id }] };
      await route.fulfill({ json: { data: current } });
    } else if (pathname === `/api/v1/admin/remote-links/senders/${senderId}/feeds/north-control` &&
      route.request().method() === 'PUT') {
      const body = route.request().postDataJSON();
      current = { ...current, senders: [{ ...current.senders[0], feeds: [{ ...current.senders[0].feeds[0],
        display_name: body.display_name, alias_list_id: body.alias_list_id, enabled: body.enabled }] }] };
      await route.fulfill({ json: { data: current } });
    } else {
      await route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
    }
  });
  await page.goto('/app.html?view=admin&tab=remote-links');
  await expect(page.locator('.remote-links-sender-card')).toBeVisible();
  return {
    reads: () => reads,
    revocations: () => revocations,
    deferRevocation: (error = null) => {
      let release;
      revocationBlock = new Promise(resolve => { release = () => resolve(error); });
      return release;
    },
    updateListener: (changes) => { current = { ...current, listener: { ...current.listener, ...changes } }; },
    updateSenderConnection: (changes) => {
      current = { ...current, sender_connection: { ...current.sender_connection, ...changes } };
    },
    updateFeed: (changes) => { current = { ...current, senders: [{ ...current.senders[0],
      feeds: [{ ...current.senders[0].feeds[0], ...changes }] }] }; }
  };
}

for (const [theme, viewport] of [
  ['light', { width: 1280, height: 900 }],
  ['dark', { width: 390, height: 844 }]
]) {
  test(`${theme} remote links cards and listener editor keep consistent spacing`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openApp(page, theme);
    const grid = page.locator('.remote-links-sender-grid');
    const card = grid.locator('.remote-links-sender-card');
    const gridBox = await grid.boundingBox();
    const cardBox = await card.boundingBox();
    expect(cardBox.width).toBeGreaterThan(gridBox.width * .95);
    const feedBox = await card.locator('.remote-links-feed-card').boundingBox();
    const manageBox = await card.getByRole('button', { name: 'Manage sender' }).boundingBox();
    expect(manageBox.y).toBeGreaterThanOrEqual(feedBox.y + feedBox.height + 8);
    await expect(card).toContainText('P25 Phase 1');
    await expect(page.locator('.remote-links-overview')).not.toContainText('No optional dependency status reported.');
    const outbound = page.locator('.remote-links-card').filter({ hasText: 'Send local P25 feeds' });
    await expect(outbound.locator('.ui-metric-label')).toHaveText('Exported systems');
    await expect(outbound.locator('.ui-metric strong')).toHaveText('0');
    await expect(outbound.locator('.ui-fact dt')).toHaveText(['Authentication', 'Last connected']);
    const outboundBox = await outbound.boundingBox();
    const metricBox = await outbound.locator('.ui-metric').boundingBox();
    const factBox = await outbound.locator('.ui-fact').first().boundingBox();
    expect(metricBox.x - outboundBox.x).toBeGreaterThanOrEqual(12);
    expect(outboundBox.x + outboundBox.width - metricBox.x - metricBox.width).toBeGreaterThanOrEqual(12);
    expect(Math.abs(metricBox.x - factBox.x)).toBeLessThan(1);
    await expect(page.locator('.remote-links-card-header svg')).toHaveCount(0);
    await expect(card.locator('.remote-links-feed-title svg')).toHaveCount(1);
    await expect(page.locator('#icon-cloud path')).toHaveAttribute('fill', 'currentColor');
    await expect(page.locator('.remote-links-summary')).toContainText('1 connected sender');
    await expect(page.locator('.remote-links-summary')).not.toContainText('waiting');
    await expect(card.getByRole('button', { name: 'Manage feed' })).toBeVisible();
    await expect(card).not.toContainText('approval');
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(page).toHaveScreenshot(`remote-links-page-${theme}.png`, { fullPage: true });

    await page.getByRole('button', { name: 'Configure listener' }).click();
    const dialog = page.getByRole('dialog', { name: 'Remote listener' });
    await expect(dialog).toBeVisible();
    const dialogBox = await dialog.boundingBox();
    expect(dialogBox.width).toBeLessThanOrEqual(theme === 'light' ? 540 : 390);
    await expect(dialog.getByLabel('Bind address')).toHaveValue('0.0.0.0');
    await expect(dialog).toContainText('authentication does not encrypt traffic');
    if (theme === 'light') {
      const addressBox = await dialog.getByLabel('Bind address').boundingBox();
      const portBox = await dialog.getByLabel('Port', { exact: true }).boundingBox();
      expect(Math.abs(addressBox.y - portBox.y)).toBeLessThan(2);
    }
    await expect(dialog).toHaveScreenshot(`remote-listener-${theme}.png`);
  });
}

test('a newly advertised feed sets up automatically, without an adoption action', async ({ page }) => {
  const app = await openApp(page);
  app.updateFeed({ state: 'PENDING', channel_configuration_id: null, alias_list_id: null,
    status_message: 'Create a P25 Alias List to set up this feed.' });
  await expect(page.locator('.remote-links-feed-card')).toContainText('Setting up', { timeout: 7000 });
  await expect(page.locator('.remote-links-feed-card')).toContainText('Create a P25 Alias List');
  await expect(page.locator('.remote-links-feed-card').getByRole('button', { name: 'Manage feed' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /adopt|forget|remove local channel/i })).toHaveCount(0);
});

test('the sender preference and local feed stay editable without adoption fields', async ({ page }) => {
  await openApp(page);
  const writes = [];
  page.on('request', (request) => {
    if (request.method() === 'PUT' && request.url().includes('/remote-links/senders/')) writes.push(request.postDataJSON());
  });
  await page.getByRole('button', { name: 'Manage sender' }).click();
  const senderDialog = page.getByRole('dialog', { name: 'Trusted sender' });
  await expect(senderDialog.getByLabel('Preferred P25 Alias List')).toBeVisible();
  await expect(senderDialog).not.toContainText('Adopt');
  await senderDialog.getByRole('button', { name: 'Save sender' }).click();
  await expect(senderDialog).not.toBeVisible();
  expect(writes[0]).toEqual({ revision: 'fixture-revision', display_name: 'Field receiver',
    default_alias_list_id: 1 });

  await page.getByRole('button', { name: 'Manage feed' }).click();
  const feedDialog = page.getByRole('dialog', { name: 'Remote feed' });
  await expect(feedDialog.getByRole('combobox', { name: /^Alias List/ })).toBeVisible();
  await expect(feedDialog).not.toContainText('Adopt');
  await feedDialog.locator('label.ui-toggle').click();
  await expect(feedDialog.getByLabel('Enable this remote channel')).not.toBeChecked();
  await feedDialog.getByRole('button', { name: 'Save feed' }).click();
  await expect(feedDialog).not.toBeVisible();
  expect(writes[1]).toEqual({ revision: 'fixture-revision', display_name: 'Metro North',
    alias_list_id: 1, enabled: false });
  await expect(page.locator('.remote-links-feed-card')).toContainText('disabled');
});

test('outbound system selection uses the themed checkbox', async ({ page }) => {
  await openApp(page);
  await page.getByRole('button', { name: 'Configure sender' }).click();
  const dialog = page.getByRole('dialog', { name: 'Outbound remote sender' });
  const system = dialog.getByRole('checkbox', { name: /Metro North/ });
  await expect(system).toHaveClass('ui-selection-check');
  await expect(system).toHaveCSS('appearance', 'none');
  await system.check();
  await expect(system).toBeChecked();
});

test('new credential is announced and guarded until its secret is copied', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openApp(page);
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: async () => {} }, configurable: true });
  });
  await page.getByRole('button', { name: 'Add trusted sender' }).click();
  await page.getByLabel('Sender name').fill('Second field receiver');
  await page.getByRole('button', { name: 'Create credential' }).click();
  const dialog = page.getByRole('dialog', { name: 'Credential created' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('alert')).toBeFocused();
  await expect(dialog).toHaveScreenshot('remote-credential-light.png');
  await dialog.getByRole('button', { name: 'Close Credential created' }).click();
  const confirmation = page.getByRole('alertdialog', { name: 'Discard unsaved changes' });
  await expect(confirmation).toContainText('Close this one-time credential? The shared secret will not be shown again.');
  await confirmation.getByRole('button', { name: 'Keep editing' }).click();
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: 'Copy Shared secret' }).click();
  await expect(dialog.getByRole('status')).toHaveText('Shared secret copied.');
  await dialog.getByRole('button', { name: 'Done' }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('button', { name: 'Add trusted sender' })).toBeFocused();
});

test('trusted sender revocation waits for themed confirmation and preserves cancellation', async ({ page }) => {
  const app = await openApp(page);
  await page.getByRole('button', { name: 'Manage sender' }).click();
  const editor = page.getByRole('dialog', { name: 'Trusted sender' });
  const revoke = editor.getByRole('button', { name: 'Revoke sender' });
  await revoke.click();
  const confirmation = page.getByRole('alertdialog', { name: 'Revoke trusted sender' });
  await expect(confirmation.getByRole('button', { name: 'Cancel' })).toBeFocused();
  expect(app.revocations()).toBe(0);
  await confirmation.getByRole('button', { name: 'Cancel' }).click();
  await expect(editor).toBeVisible();
  await expect(revoke).toBeFocused();
  expect(app.revocations()).toBe(0);
  await revoke.click();
  await confirmation.getByRole('button', { name: 'Revoke sender' }).click();
  await expect(editor).toHaveCount(0);
  expect(app.revocations()).toBe(1);
});

test('pending revocation freezes sender actions, bypasses edit validation, and restores controls on failure', async ({ page }) => {
  const app = await openApp(page);
  const writes = [];
  page.on('request', request => {
    if (request.method() === 'PUT' && request.url().includes('/remote-links/senders/')) writes.push(request);
  });
  await page.getByRole('button', { name: 'Manage sender' }).click();
  const editor = page.getByRole('dialog', { name: 'Trusted sender' });
  const name = editor.getByLabel('Sender name');
  await name.fill('');
  const release = app.deferRevocation('Sender could not be revoked. Try again.');
  const revoke = editor.getByRole('button', { name: 'Revoke sender', exact: true });
  await revoke.click();
  await page.getByRole('alertdialog', { name: 'Revoke trusted sender' })
    .getByRole('button', { name: 'Revoke sender', exact: true }).click();
  await expect.poll(() => app.revocations()).toBe(1);
  await expect(editor).toHaveAttribute('aria-busy', 'true');
  await expect(revoke).toBeDisabled();
  await expect(editor.getByRole('button', { name: 'Save sender' })).toBeDisabled();
  await expect(name).toBeDisabled();
  await editor.locator('form').evaluate(form => {
    form.querySelector('input').value = 'Field receiver';
    form.querySelector('input').dispatchEvent(new Event('input', { bubbles: true }));
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    form.querySelector('.ui-button-danger').dispatchEvent(new Event('click', { bubbles: true }));
  });
  expect(app.revocations()).toBe(1);
  expect(writes).toHaveLength(0);
  await expect(page.getByRole('alertdialog')).toHaveCount(0);
  release();
  await expect(editor.getByRole('alert')).toContainText('Sender could not be revoked. Try again.');
  await expect(editor).not.toHaveAttribute('aria-busy', 'true');
  await expect(revoke).toBeEnabled();
  await expect(name).toBeEnabled();
  await name.fill('Field receiver');
  await expect(editor.getByRole('button', { name: 'Save sender' })).toBeEnabled();
});

test('polling refreshes status and restores a focused remote action', async ({ page }) => {
  const app = await openApp(page);
  const outbound = page.locator('.remote-links-card').filter({ hasText: 'Send local P25 feeds' });
  await expect(outbound.locator('.ui-metric strong')).toHaveText('0');
  const action = page.getByRole('button', { name: 'Configure listener' });
  await action.focus();
  const handle = await action.elementHandle();
  app.updateListener({ enabled: true, state: 'LISTENING' });
  app.updateSenderConnection({ credential_configured: true,
    exported_channel_configuration_ids: ['local-channel-1', 'local-channel-2'] });
  await expect(page.locator('.remote-links-summary')).toContainText('Listening', { timeout: 7000 });
  await expect(outbound.locator('.ui-metric strong')).toHaveText('2');
  await expect(outbound.locator('.ui-fact dd')).toHaveText(['Credential saved', 'Never']);
  expect(await handle.evaluate((element) => element.isConnected)).toBe(false);
  await expect(action).toBeFocused();
  app.updateSenderConnection({ exported_channel_configuration_ids: [] });
  await expect(outbound.locator('.ui-metric strong')).toHaveText('0', { timeout: 7000 });
  await expect(outbound.locator('.ui-fact dd')).toHaveText(['Credential saved', 'Never']);
  await action.click();
  await expect(page.getByRole('dialog', { name: 'Remote listener' })).toBeVisible();
});

for (const activation of ['pointer', 'keyboard']) {
  test(`polling preserves an in-progress ${activation} activation`, async ({ page }) => {
    const app = await openApp(page);
    const action = page.getByRole('button', { name: 'Configure listener' });
    const handle = await action.elementHandle();
    if (activation === 'pointer') {
      const box = await action.boundingBox();
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await page.mouse.down();
    } else {
      await action.focus();
      await page.keyboard.down('Space');
    }
    const before = app.reads();
    app.updateListener({ enabled: true, state: 'LISTENING' });
    await expect.poll(() => app.reads(), { timeout: 7000 }).toBeGreaterThan(before);
    await page.waitForTimeout(100);
    expect(await handle.evaluate((element) => element.isConnected)).toBe(true);
    if (activation === 'pointer') await page.mouse.up();
    else await page.keyboard.up('Space');
    await expect(page.getByRole('dialog', { name: 'Remote listener' })).toBeVisible();
  });
}

test('listener save shares busy guards, preserves failed drafts, and retries the same revision', async ({ page }) => {
  await openApp(page);
  const writes = [];
  const pending = [];
  await page.route('**/api/v1/admin/remote-links/listener', async (route) => {
    writes.push(route.request().postDataJSON());
    await new Promise((resolve) => pending.push(async (status) => {
      await route.fulfill(status === 200 ? { json: { data: {
        ...snapshot(), listener: { ...snapshot().listener, bind_address: writes.at(-1).bind_address }
      } } } : { status, json: { error: { message: 'Listener could not be saved. Try again.' } } });
      resolve();
    }));
  });
  await page.getByRole('button', { name: 'Configure listener' }).click();
  const dialog = page.getByRole('dialog', { name: 'Remote listener' });
  const address = dialog.getByRole('textbox', { name: /^Bind address/ });
  const save = dialog.getByRole('button', { name: 'Save listener', exact: true });
  await address.fill('127.0.0.1');
  await expect(dialog.getByRole('status')).toHaveText('Unsaved changes');
  await save.click();
  await expect.poll(() => pending.length).toBe(1);
  await expect(address).toBeDisabled();
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeDisabled();
  await expect(dialog).toHaveAttribute('aria-busy', 'true');
  await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  await dialog.locator('form').evaluate((form) => form.dispatchEvent(new Event('submit', { cancelable: true })));
  expect(writes).toHaveLength(1);
  await pending.shift()(503);
  await expect(dialog.getByRole('alert')).toHaveText('Listener could not be saved. Try again.');
  await expect(address).toHaveValue('127.0.0.1');
  await expect(address).toBeEnabled();
  await expect(save).toBeEnabled();
  await save.click();
  await expect.poll(() => pending.length).toBe(1);
  await pending.shift()(200);
  await expect(dialog).toHaveCount(0);
  expect(writes).toEqual(Array(2).fill({ revision: 'fixture-revision', enabled: false,
    bind_address: '127.0.0.1', port: 53800 }));
});

test('one-time credential transition cannot submit a second creation request', async ({ page }) => {
  await openApp(page);
  let creations = 0;
  await page.route('**/api/v1/admin/remote-links/senders', async (route) => {
    creations += 1;
    await route.fulfill({ json: { data: { sender_id: senderId, secret: fixtureSecret } } });
  });
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: async () => {} }, configurable: true });
  });
  await page.getByRole('button', { name: 'Add trusted sender' }).click();
  await page.getByLabel('Sender name').fill('Additional field receiver');
  await page.getByRole('button', { name: 'Create credential' }).click();
  const dialog = page.getByRole('dialog', { name: 'Credential created' });
  await expect(dialog.getByRole('button', { name: 'Done', exact: true })).toBeEnabled();
  await dialog.locator('form').evaluate((form) => form.dispatchEvent(new Event('submit', { cancelable: true })));
  await dialog.getByRole('button', { name: 'Copy Shared secret' }).click();
  await dialog.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  expect(creations).toBe(1);
});
