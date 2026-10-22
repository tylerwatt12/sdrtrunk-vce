'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { expectFormWorkflowGeometry, expectStableFormWorkflowInsets,
  captureFormWorkflowEvidence } = require('./fixtures/form-workflow-geometry.cjs');

async function expectRemoteWorkflowGeometry(dialog) {
  const form = dialog.locator('.ui-form-workflow');
  const geometry = await expectFormWorkflowGeometry(form);
  const decorations = await form.evaluate(element => {
    const body = element.querySelector(':scope > .ui-form-workflow-body');
    const bounds = body.getBoundingClientRect();
    return [...body.querySelectorAll('.ui-notice, .ui-feedback, .remote-links-credential-row .ui-button')]
      .filter(node => node.getClientRects().length).map(node => {
        const box = node.getBoundingClientRect();
        return { left: box.left - bounds.left, right: bounds.right - box.right };
      });
  });
  for (const decoration of decorations) {
    expect(decoration.left, 'Warnings and copy controls have a left inset').toBeGreaterThanOrEqual(8);
    expect(decoration.right, 'Warnings and copy controls have a right inset').toBeGreaterThanOrEqual(8);
  }
  await dialog.locator('.modal-content').evaluate(element => { element.scrollTop = element.scrollHeight; });
  const bottom = await form.evaluate(element => {
    const body = element.querySelector(':scope > .ui-form-workflow-body');
    const footer = element.querySelector(':scope > .ui-modal-footer');
    return { body: body.getBoundingClientRect().bottom, footer: footer.getBoundingClientRect().top };
  });
  expect(bottom.footer, 'The highlighted body ends above the sticky actions').toBeGreaterThanOrEqual(bottom.body - 1);
  return geometry;
}

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

async function openApp(page, theme = 'light', options = {}) {
  const preferences = structuredClone(defaultPreferences);
  preferences.appearance.theme = theme;
  let current = snapshot();
  if (options.senderState) current.senders[0].state = options.senderState;
  if (options.multipleFeeds) current.senders[0].feeds.push({ ...current.senders[0].feeds[0],
    feed_id: 'south-control', display_name: 'Metro South', channel_configuration_id: 'local-channel-2' });
  if (options.multipleSenders) current.senders.push({ ...current.senders[0],
    sender_id: '22222222-2222-4222-8222-222222222222', display_name: 'West receiver',
    feeds: [{ ...current.senders[0].feeds[0], feed_id: 'west-control', display_name: 'Metro West',
      channel_configuration_id: 'local-channel-west' }] });
  let reads = 0;
  let removals = 0;
  let removalBlock = null;
  let readBlock = null;
  const removalRequests = [];
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
      if (readBlock) await readBlock;
      await route.fulfill({ json: { data: current } });
    } else if (pathname === '/api/v1/admin/remote-links/senders' && route.request().method() === 'POST') {
      await route.fulfill({ json: { data: { sender_id: senderId, secret: fixtureSecret } } });
    } else if (pathname === `/api/v1/admin/remote-links/senders/${senderId}` &&
      route.request().method() === 'DELETE') {
      removals += 1;
      removalRequests.push(route.request().postDataJSON());
      if (removalBlock) {
        const error = await removalBlock;
        removalBlock = null;
        if (error) {
          await route.fulfill({ status: 503, json: { error: { status: 503, message: error } } });
          return;
        }
      }
      current = { ...current, senders: [], restart_required: options.restartRequiredOnRemoval === true };
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
  await expect(page.locator('.remote-links-sender-card').first()).toBeVisible();
  return {
    reads: () => reads,
    removals: () => removals,
    removalRequests,
    deferRemoval: (error = null) => {
      let release;
      removalBlock = new Promise(resolve => { release = () => resolve(error); });
      return release;
    },
    deferReads: () => {
      let release;
      readBlock = new Promise(resolve => { release = () => { readBlock = null; resolve(); }; });
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
  test(`${theme} remote links tabs distinguish both directions and keep consistent spacing`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openApp(page, theme);
    const incoming = page.getByRole('region', { name: 'Receive here', exact: true });
    const outbound = page.locator('[data-remote-direction="send"]');
    const receiveTab = page.getByRole('tab', { name: 'Receive here', exact: true });
    const sendTab = page.getByRole('tab', { name: 'Send from here', exact: true });
    await expect(receiveTab).toHaveAttribute('aria-selected', 'true');
    await expect(sendTab).toHaveAttribute('aria-selected', 'false');
    await expect(receiveTab).toHaveAttribute('aria-controls', 'remote-links-receive-view');
    await expect(sendTab).toHaveAttribute('aria-controls', 'remote-links-send-view');
    await expect(page.locator('#remote-links-receive-view')).toHaveAttribute('role', 'tabpanel');
    await expect(page.locator('#remote-links-send-view')).toHaveAttribute('role', 'tabpanel');
    await expect(outbound).toBeHidden();
    await expect(incoming).toHaveAttribute('data-remote-direction', 'receive');
    await expect(outbound).toHaveAttribute('data-remote-direction', 'send');
    const card = incoming.locator('.remote-links-sender-card');
    const gridBox = await incoming.locator('.remote-links-sender-grid').boundingBox();
    const cardBox = await card.boundingBox();
    expect(cardBox.width).toBeGreaterThan(gridBox.width * .95);
    const feedBox = await card.locator('.remote-links-feed-card').boundingBox();
    const manageBox = await card.getByRole('button', { name: 'Settings for Field receiver' }).boundingBox();
    expect(manageBox.y).toBeLessThan(feedBox.y);
    await expect(card).toContainText('P25 Phase 1');
    await expect(incoming).not.toContainText('No optional dependency status reported.');
    await expect(outbound.locator('.ui-fact-list')).toHaveCount(0);
    await expect(outbound).toContainText('Create a trusted sender on the destination receiver');
    await expect(incoming.locator('svg use[href="#icon-download"]')).toHaveCount(1);
    await expect(outbound.locator('svg use[href="#icon-upload"]')).toHaveCount(1);
    await expect(incoming.locator('svg use[href="#icon-arrow-right"]')).toHaveCount(1);
    await expect(outbound.locator('svg use[href="#icon-arrow-right"]')).toHaveCount(1);
    await expect(card.locator('.remote-links-feed-title svg')).toHaveCount(1);
    await expect(page.locator('#icon-cloud path')).toHaveAttribute('fill', 'none');
    await expect(page.locator('#icon-cloud path')).toHaveAttribute('stroke', 'currentColor');
    await expect(incoming).toContainText('1 connected sender');
    await expect(incoming).not.toContainText('waiting');
    await expect(incoming.getByRole('button', { name: 'Add sender', exact: true })).toBeVisible();
    await expect(outbound.getByRole('button', { name: 'Add sender', exact: true })).toHaveCount(0);
    await expect(outbound.locator('.remote-links-sender-card, .remote-links-feed-card')).toHaveCount(0);
    await expect(card.locator('.ui-fact')).toHaveCount(0);
    await expect(card.getByRole('button', { name: 'Manage feed' })).toBeVisible();
    await expect(card).not.toContainText('approval');
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(page).toHaveScreenshot(`remote-links-page-${theme}.png`, { fullPage: true });

    await sendTab.click();
    await expect(sendTab).toHaveAttribute('aria-selected', 'true');
    await expect(receiveTab).toHaveAttribute('aria-selected', 'false');
    await expect(incoming).toBeHidden();
    await expect(outbound).toBeVisible();
    const setup = outbound.getByRole('button', { name: 'Set up sending', exact: true });
    await expect(setup).toBeVisible();
    const outboundBox = await outbound.boundingBox();
    const setupBox = await setup.boundingBox();
    expect(setupBox.x - outboundBox.x).toBeGreaterThanOrEqual(12);
    expect(outboundBox.x + outboundBox.width - setupBox.x - setupBox.width).toBeGreaterThanOrEqual(12);
    await expect(page.getByRole('button', { name: 'Receive settings' })).toHaveCount(0);
    await expect(page).toHaveScreenshot(`remote-links-send-page-${theme}.png`, { fullPage: true });
    await receiveTab.click();
    await page.getByRole('button', { name: 'Receive settings' }).click();
    const dialog = page.getByRole('dialog', { name: 'Receive settings' });
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
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.setViewportSize({ width: 320, height: 844 });
    await card.locator('.ui-disclosure-copy strong').evaluate(element => {
      element.textContent = 'LongSenderName'.repeat(8);
    });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
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
  await page.getByRole('button', { name: 'Settings for Field receiver' }).click();
  const senderDialog = page.getByRole('dialog', { name: 'Sender settings' });
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
  await page.getByRole('tab', { name: 'Send from here', exact: true }).click();
  await page.getByRole('button', { name: 'Set up sending' }).click();
  const dialog = page.getByRole('dialog', { name: 'Sending settings' });
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
  await page.getByRole('button', { name: 'Add sender' }).click();
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
  await expect(page.getByRole('button', { name: 'Add sender' })).toBeFocused();
});

for (const [theme, viewport] of [
  ['light', { width: 1280, height: 900 }], ['dark', { width: 1280, height: 900 }],
  ['light', { width: 390, height: 844 }], ['dark', { width: 390, height: 844 }]
]) {
  const presentation = `${theme} ${viewport.width}px`;
  test(`sender removal confirms its channel scope, preserves cancellation, and applies its returned snapshot in ${presentation}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const app = await openApp(page, theme, { multipleFeeds: true });
    await page.getByRole('button', { name: 'Settings for Field receiver' }).click();
    const editor = page.getByRole('dialog', { name: 'Sender settings' });
    const name = editor.getByLabel('Sender name');
    await name.fill('Unfinished sender name');
    const remove = editor.getByRole('button', { name: 'Remove sender', exact: true });
    await expect(editor.getByRole('button', { name: /revoke/i })).toHaveCount(0);
    await remove.click();
    const confirmation = page.getByRole('alertdialog', { name: 'Remove trusted sender', exact: true });
    await expect(confirmation).toContainText('Remove Field receiver?');
    await expect(confirmation).toContainText('This removes the sender and all of its remote channels from this receiver.');
    await expect(confirmation.getByRole('button', { name: 'Remove sender', exact: true })).toHaveClass(/ui-button-danger/);
    await expect(confirmation.getByRole('button', { name: 'Cancel', exact: true })).toBeFocused();
    expect(app.removals()).toBe(0);
    if (process.env.FORM_WORKFLOW_AUDIT_DIR) await confirmation.screenshot({
      path: path.join(process.env.FORM_WORKFLOW_AUDIT_DIR, `remove-confirm-${theme}-${viewport.width}.png`),
      animations: 'disabled', caret: 'hide'
    });
    await confirmation.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(editor).toBeVisible();
    await expect(remove).toBeFocused();
    await expect(name).toHaveValue('Unfinished sender name');
    await expect(editor.locator('form')).toHaveClass(/is-dirty/);
    await expect(page.locator('.remote-links-sender-card')).toHaveCount(1);
    await expect(page.locator('.remote-links-feed-card')).toHaveCount(2);
    expect(app.removals()).toBe(0);
    const releaseReads = app.deferReads();
    await remove.click();
    await confirmation.getByRole('button', { name: 'Remove sender', exact: true }).click();
    await expect(editor).toHaveCount(0);
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    await expect(page.locator('.remote-links-sender-card')).toHaveCount(0);
    await expect(page.locator('.remote-links-feed-card')).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'No trusted senders', exact: true })).toBeVisible();
    expect(app.removals()).toBe(1);
    expect(app.removalRequests).toEqual([{ revision: 'fixture-revision' }]);
    if (process.env.FORM_WORKFLOW_AUDIT_DIR) await page.locator('.remote-links-page').screenshot({
      path: path.join(process.env.FORM_WORKFLOW_AUDIT_DIR, `removed-sender-${theme}-${viewport.width}.png`),
      animations: 'disabled', caret: 'hide'
    });
    releaseReads();
  });

  test(`sender removal locks duplicate actions and dismissal, preserves failed drafts, and retries in ${presentation}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const app = await openApp(page, theme, { multipleFeeds: true });
    const writes = [];
    page.on('request', request => {
      if (request.method() === 'PUT' && request.url().includes('/remote-links/senders/')) writes.push(request);
    });
    await page.getByRole('button', { name: 'Settings for Field receiver' }).click();
    const editor = page.getByRole('dialog', { name: 'Sender settings' });
    const name = editor.getByLabel('Sender name');
    await name.fill('');
    const release = app.deferRemoval('Sender could not be removed. Try again.');
    const remove = editor.getByRole('button', { name: 'Remove sender', exact: true });
    await remove.click();
    const confirmation = page.getByRole('alertdialog', { name: 'Remove trusted sender', exact: true });
    await confirmation.getByRole('button', { name: 'Remove sender', exact: true }).click();
    await expect.poll(() => app.removals()).toBe(1);
    await expect(editor).toHaveAttribute('aria-busy', 'true');
    await expect(remove).toBeDisabled();
    await expect(editor.getByRole('button', { name: 'Save sender' })).toBeDisabled();
    await expect(name).toBeDisabled();
    await editor.locator('form').evaluate(form => {
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      form.querySelector('.ui-button-danger').dispatchEvent(new Event('click', { bubbles: true }));
    });
    await page.keyboard.press('Escape');
    await expect(editor).toBeVisible();
    expect(app.removals()).toBe(1);
    expect(writes).toHaveLength(0);
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    release();
    await expect(editor.getByRole('alert')).toContainText('Sender could not be removed. Try again.');
    await expect(editor).not.toHaveAttribute('aria-busy', 'true');
    await expect(remove).toBeEnabled();
    await expect(name).toBeEnabled();
    await expect(name).toHaveValue('');
    await expect(editor.locator('form')).toHaveClass(/is-dirty/);
    await expect(page.locator('.remote-links-sender-card')).toHaveCount(1);
    await expect(page.locator('.remote-links-feed-card')).toHaveCount(2);
    await captureFormWorkflowEvidence(editor.locator('form'), `remove-error-${theme}-${viewport.width}`);
    await remove.click();
    await confirmation.getByRole('button', { name: 'Remove sender', exact: true }).click();
    await expect(editor).toHaveCount(0);
    await expect(page.locator('.remote-links-sender-card')).toHaveCount(0);
    await expect(page.locator('.remote-links-feed-card')).toHaveCount(0);
    expect(app.removals()).toBe(2);
    expect(app.removalRequests).toEqual(Array(2).fill({ revision: 'fixture-revision' }));
    expect(writes).toHaveLength(0);
  });

  test(`legacy revoked senders can be removed with their retained feeds in ${presentation}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const app = await openApp(page, theme, { senderState: 'REVOKED', multipleFeeds: true });
    await page.getByRole('button', { name: 'Settings for Field receiver' }).click();
    const editor = page.getByRole('dialog', { name: 'Sender settings' });
    await editor.getByRole('button', { name: 'Remove sender', exact: true }).click();
    await page.getByRole('alertdialog', { name: 'Remove trusted sender', exact: true })
      .getByRole('button', { name: 'Remove sender', exact: true }).click();
    await expect(editor).toHaveCount(0);
    await expect(page.locator('.remote-links-sender-card, .remote-links-feed-card')).toHaveCount(0);
    expect(app.removals()).toBe(1);
  });

  test(`committed sender removal preserves its restart warning through polling in ${presentation}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const app = await openApp(page, theme, { multipleFeeds: true, restartRequiredOnRemoval: true });
    const warning = page.locator('.remote-links-restart-notice');
    await expect(warning).toBeHidden();
    await page.getByRole('button', { name: 'Settings for Field receiver', exact: true }).click();
    const editor = page.getByRole('dialog', { name: 'Sender settings', exact: true });
    await editor.getByLabel('Sender name').fill('Unfinished sender name');
    await editor.getByRole('button', { name: 'Remove sender', exact: true }).click();
    await page.getByRole('alertdialog', { name: 'Remove trusted sender', exact: true })
      .getByRole('button', { name: 'Remove sender', exact: true }).click();
    await expect(editor).toHaveCount(0);
    await expect(page.locator('.remote-links-sender-card, .remote-links-feed-card')).toHaveCount(0);
    await expect(warning).toHaveText('Sender removed. Restart VCE to finish updating its channel list.');
    await expect(warning).toBeVisible();
    await expect(page.locator('.remote-links-page > [role="status"]').filter({ hasText: /try again|could not/i }))
      .toHaveCount(0);
    const previousReads = app.reads();
    await expect.poll(() => app.reads(), { timeout: 7000 }).toBeGreaterThan(previousReads);
    await expect(warning).toBeVisible();
    await expect(page.locator('.remote-links-sender-card, .remote-links-feed-card')).toHaveCount(0);
    expect(app.removals()).toBe(1);
    if (process.env.FORM_WORKFLOW_AUDIT_DIR) await page.locator('.remote-links-page').screenshot({
      path: path.join(process.env.FORM_WORKFLOW_AUDIT_DIR, `remove-restart-${theme}-${viewport.width}.png`),
      animations: 'disabled', caret: 'hide'
    });
  });
}

test('polling refreshes status and restores a focused remote action', async ({ page }) => {
  const app = await openApp(page);
  const incoming = page.getByRole('region', { name: 'Receive here', exact: true });
  const outbound = page.locator('[data-remote-direction="send"]');
  const receiveTab = page.getByRole('tab', { name: 'Receive here', exact: true });
  const sendTab = page.getByRole('tab', { name: 'Send from here', exact: true });
  await expect(outbound.locator('.ui-fact-list')).toHaveCount(0);
  await expect(outbound).toBeHidden();
  const action = page.getByRole('button', { name: 'Receive settings' });
  await action.focus();
  const handle = await action.elementHandle();
  app.updateListener({ enabled: true, state: 'LISTENING' });
  app.updateSenderConnection({ destination_host: 'remote.example.test', destination_port: 53800,
    enabled: true, state: 'CONNECTED', credential_configured: true,
    exported_channel_configuration_ids: ['local-channel-1', 'local-channel-2'] });
  await expect(incoming).toContainText('Listening', { timeout: 7000 });
  await expect(outbound.locator('.ui-fact-list dt')).toHaveText([
    'Destination', 'Selected feeds', 'Authentication', 'Last connected'
  ]);
  await expect(outbound.locator('.ui-fact-list dd').first()).toHaveText('remote.example.test:53800');
  await expect(outbound.locator('[data-remote-action="sender-connection"]')).toHaveText('Sending settings');
  await expect(outbound.locator('.ui-fact-list dd').nth(1)).toContainText(/\b2\b/);
  await expect(outbound.locator('.ui-fact-list dd').nth(2)).toHaveText('Credential saved');
  await expect(outbound.locator('.ui-fact-list dd').nth(3)).toHaveText('Never');
  expect(await handle.evaluate((element) => element.isConnected)).toBe(false);
  await expect(action).toBeFocused();
  await sendTab.click();
  await expect(outbound.getByRole('button', { name: 'Sending settings', exact: true })).toBeVisible();
  await expect(incoming).toBeHidden();
  const sendTabHandle = await sendTab.elementHandle();
  await sendTab.focus();
  app.updateSenderConnection({ exported_channel_configuration_ids: [] });
  await expect(outbound.locator('.ui-fact-list dd').nth(1)).toContainText(/\b0\b/, { timeout: 7000 });
  await expect(outbound.locator('.ui-fact-list dd').nth(2)).toHaveText('Credential saved');
  await expect(outbound.locator('.ui-fact-list dd').nth(3)).toHaveText('Never');
  await expect(sendTab).toHaveAttribute('aria-selected', 'true');
  await expect(sendTab).toBeFocused();
  expect(await sendTabHandle.evaluate((element) => element.isConnected)).toBe(true);
  await sendTab.press('Home');
  await expect(receiveTab).toHaveAttribute('aria-selected', 'true');
  await expect(receiveTab).toBeFocused();
  await receiveTab.press('End');
  await expect(sendTab).toHaveAttribute('aria-selected', 'true');
  await expect(sendTab).toBeFocused();
  await sendTab.press('ArrowRight');
  await expect(receiveTab).toBeFocused();
  await expect(receiveTab).toHaveAttribute('aria-selected', 'true');
  await receiveTab.press('ArrowLeft');
  await expect(sendTab).toBeFocused();
  await expect(sendTab).toHaveAttribute('aria-selected', 'true');
  await sendTab.press('Home');
  await action.click();
  await expect(page.getByRole('dialog', { name: 'Receive settings' })).toBeVisible();
});

test('sender disclosure choices and focus survive status polling independently', async ({ page }) => {
  const app = await openApp(page, 'light', { multipleSenders: true });
  const senders = page.locator('.remote-links-sender-card');
  const firstToggle = senders.nth(0).locator('.ui-disclosure-toggle');
  const secondToggle = senders.nth(1).locator('.ui-disclosure-toggle');
  await expect(firstToggle).toHaveAttribute('aria-expanded', 'true');
  await expect(secondToggle).toHaveAttribute('aria-expanded', 'false');
  await expect(senders.nth(0).getByRole('button', { name: 'Manage feed' })).toBeVisible();
  await expect(senders.nth(1).getByRole('button', { name: 'Manage feed' })).toBeHidden();
  await firstToggle.click();
  await secondToggle.click();
  await expect(firstToggle).toHaveAttribute('aria-expanded', 'false');
  await expect(secondToggle).toHaveAttribute('aria-expanded', 'true');
  await page.getByRole('tab', { name: 'Send from here', exact: true }).click();
  await expect(senders.nth(1)).toBeHidden();
  await page.getByRole('tab', { name: 'Receive here', exact: true }).click();
  await expect(firstToggle).toHaveAttribute('aria-expanded', 'false');
  await expect(secondToggle).toHaveAttribute('aria-expanded', 'true');
  await secondToggle.focus();
  const before = app.reads();
  await expect.poll(() => app.reads(), { timeout: 7000 }).toBeGreaterThan(before);
  await expect(firstToggle).toHaveAttribute('aria-expanded', 'false');
  await expect(secondToggle).toHaveAttribute('aria-expanded', 'true');
  await expect(senders.nth(0).getByRole('button', { name: 'Manage feed' })).toBeHidden();
  await expect(senders.nth(1).getByRole('button', { name: 'Manage feed' })).toBeVisible();
  await expect(secondToggle).toBeFocused();
  await secondToggle.press('Enter');
  await expect(secondToggle).toHaveAttribute('aria-expanded', 'false');
  await expect(senders.nth(1).getByRole('button', { name: 'Manage feed' })).toBeHidden();
});

for (const activation of ['pointer', 'keyboard']) {
  test(`polling preserves an in-progress ${activation} activation`, async ({ page }) => {
    const app = await openApp(page);
    const action = page.getByRole('button', { name: 'Receive settings' });
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
    await expect(page.getByRole('dialog', { name: 'Receive settings' })).toBeVisible();
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
  await page.getByRole('button', { name: 'Receive settings' }).click();
  const dialog = page.getByRole('dialog', { name: 'Receive settings' });
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
  await page.getByRole('button', { name: 'Add sender' }).click();
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

const editorSpacingCases = [
  { key: 'listener', open: 'Receive settings', title: 'Receive settings', field: 'Bind address',
    value: '127.0.0.1', action: 'Save listener', endpoint: '/listener' },
  { key: 'outbound', open: 'Set up sending', title: 'Sending settings', field: 'Destination host',
    value: 'remote.example.test', action: 'Save sender', endpoint: '/sender-connection' },
  { key: 'create', open: 'Add sender', title: 'Add sender', field: 'Sender name',
    value: 'Synthetic dispatch', action: 'Create credential', endpoint: '/senders' },
  { key: 'sender', open: 'Settings for Field receiver', title: 'Sender settings', field: 'Sender name',
    value: 'Updated fixture sender', action: 'Save sender', endpoint: `/senders/${senderId}` },
  { key: 'feed', open: 'Manage feed', title: 'Remote feed', field: 'Local channel name',
    value: 'Updated fixture feed', action: 'Save feed', endpoint: `/senders/${senderId}/feeds/north-control` }
];

for (const [theme, viewport] of [
  ['light', { width: 1280, height: 900 }],
  ['dark', { width: 320, height: 700 }]
]) {
  for (const editor of editorSpacingCases) {
    test(`workflow spacing remote ${editor.key} keeps dirty, saving, and error content inset in ${theme}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await openApp(page, theme);
      let release;
      await page.route(`**/api/v1/admin/remote-links${editor.endpoint}`, async route => {
        await new Promise(resolve => { release = async () => {
          await route.fulfill({ status: 503, json: { error: { message: 'Fixture settings could not be saved. Try again.' } } });
          resolve();
        }; });
      });
      if (editor.key === 'outbound') await page.getByRole('tab', { name: 'Send from here', exact: true }).click();
      await page.getByRole('button', { name: editor.open, exact: true }).click();
      const dialog = page.getByRole('dialog', { name: editor.title, exact: true });
      await dialog.getByRole('textbox', { name: new RegExp(`^${editor.field}`) }).fill(editor.value);
      await expect(dialog.locator('form')).toHaveClass(/is-dirty/);
      const before = await expectRemoteWorkflowGeometry(dialog);
      await captureFormWorkflowEvidence(dialog.locator('form'), `remote-${editor.key}-${theme}-dirty`);
      await dialog.getByRole('button', { name: editor.action, exact: true }).click();
      await expect.poll(() => typeof release).toBe('function');
      await expect(dialog).toHaveAttribute('aria-busy', 'true');
      await expectRemoteWorkflowGeometry(dialog);
      await expectStableFormWorkflowInsets(dialog.locator('form'), before);
      await captureFormWorkflowEvidence(dialog.locator('form'), `remote-${editor.key}-${theme}-saving`);
      await release();
      await expect(dialog.getByRole('alert')).toHaveText('Fixture settings could not be saved. Try again.');
      await expectRemoteWorkflowGeometry(dialog);
      await expectStableFormWorkflowInsets(dialog.locator('form'), before);
      await captureFormWorkflowEvidence(dialog.locator('form'), `remote-${editor.key}-${theme}-error`);
    });
  }

  test(`workflow spacing remote one-time credential keeps warning, fields, copy controls, and footer separated in ${theme}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openApp(page, theme);
    await page.evaluate(() => {
      Object.defineProperty(navigator, 'clipboard', { value: { writeText: async () => {} }, configurable: true });
    });
    await page.getByRole('button', { name: 'Add sender', exact: true }).click();
    await page.getByLabel('Sender name').fill('Synthetic credential fixture');
    await page.getByRole('button', { name: 'Create credential', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Credential created', exact: true });
    await expect(dialog.getByRole('alert')).toBeVisible();
    await expectRemoteWorkflowGeometry(dialog);
    await expect(dialog.getByRole('button', { name: 'Copy Sender ID', exact: true })).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Copy Shared secret', exact: true })).toBeVisible();
    await captureFormWorkflowEvidence(dialog.locator('form'), `remote-credential-${theme}-created`);
    await dialog.getByRole('button', { name: 'Copy Shared secret', exact: true }).click();
    await expect(dialog.getByRole('status')).toHaveText('Shared secret copied.');
    await expectRemoteWorkflowGeometry(dialog);
    await captureFormWorkflowEvidence(dialog.locator('form'), `remote-credential-${theme}-copied`);
    await dialog.getByRole('button', { name: 'Done', exact: true }).click();
    await expect(dialog).toHaveCount(0);
  });
}
