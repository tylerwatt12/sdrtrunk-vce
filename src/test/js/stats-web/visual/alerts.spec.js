'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, channelId } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');
const path = require('node:path');
const fs = require('node:fs');

async function auditScreenshot(page, name) {
  if (!process.env.ALERTS_AUDIT_DIR) return;
  fs.mkdirSync(process.env.ALERTS_AUDIT_DIR, { recursive: true });
  await page.screenshot({ path: path.join(process.env.ALERTS_AUDIT_DIR, name + '.png'), fullPage: true });
}

async function installAlerts(page, { guest = false, theme = 'light' } = {}) {
  const fixture = await installSiteStyleApplication(page, theme);
  const state = { revision: 1, topics: [
    { id: 'fire', name: 'Fire activity', description: 'Dispatch and tactical activity.', visible: true, enabled: true, built_in: false, rule_count: 0, subscription_count: 0 },
    { id: 'paused', name: 'Weather operations', description: 'Regional weather activity.', visible: true, enabled: false, built_in: false },
    { id: 'administrator-alerts', name: 'Administrator alerts', description: 'Tuner and system health.', visible: false, enabled: true, built_in: true }
  ], rules: [], options: { systems: [{ value: systemKey, label: 'County Public Safety' }],
    channels: [{ value: channelId, label: 'County Dispatch' }], sites: [{ value: '1', label: 'North site', system_key: systemKey }],
    protocols: [{ value: 'P25', label: 'P25' }], message_types: [{ value: 'PAGE', label: 'Page' }],
    tone_types: [{ value: 'CALL_PROGRESS_TONE', label: 'Call progress tone' }], dcs_codes: [{ value: 'N023', label: 'DCS 023' }] },
    config: { revision: 1, enabled: true, mode: 'proxy', site_url: '', registration: { status: 'approved', sender_id: 'synthetic-sender' } },
    writes: [], pending: [], enrollmentStatus: 'pending', publicMode: 'proxy', deviceMode: 'proxy', deviceStatus: 'connected', deviceTopics: ['fire'],
    devicePrefs: { paused: false, quiet_enabled: false, quiet_start: '', quiet_end: '', time_zone: 'UTC' },
    holdTopic: false, localExpiry: false, enrollmentOutage: false, enrollmentChecks: 0 };
  await page.route('**/api/v1/**', async route => {
    const request = route.request(); const url = new URL(request.url()); const p = url.pathname;
    const data = value => route.fulfill({ json: { data: value } });
    if (guest && p === '/api/v1/auth/session') return data({ configured: true, authenticated: false, capabilities: { 'web-access': false } });
    if (p === '/api/v1/admin/alerts/topics' || p === '/api/v1/admin/alerts/rules') {
      if (request.method() === 'GET') return data(state);
      const value = request.postDataJSON(); state.writes.push({ p, value });
      if (state.holdTopic && value.topic) {
        await new Promise(resolve => state.pending.push(async () => {
          await route.fulfill({ status: 503, json: { error: { message: 'Topic could not be saved. Try again.' } } });
          resolve();
        }));
        return;
      }
      if (value.topic) state.topics.push(value.topic);
      if (value.rule) state.rules.push(value.rule);
      state.revision++; return data(state);
    }
    if (p === '/api/v1/admin/alerts/config') {
      if (request.method() === 'GET') return data(state.config);
      const value = request.postDataJSON(); state.writes.push({ p, value });
      state.config = { ...state.config, ...value }; return data(state.config);
    }
    if (p === '/api/v1/admin/alerts/public-test') {
      const value = request.postDataJSON(); state.writes.push({ p, value });
      return data({ public_access: { status: 'passed', checked_url: value.site_url, message: 'Public access passed.' }, test_token: 'synthetic-proof' });
    }
    if (p === '/api/v1/alerts/topics') return data({ topics: state.topics.filter(topic => topic.visible), mode: state.publicMode, push_enabled: true,
      vapid_public_key: state.publicMode === 'direct' ? 'AQID' : '' });
    if (p === '/api/v1/alerts/history') return data({ items: [{ id: 'alert-1', topic_id: 'fire', title: 'Fire Tactical is active', body: 'Engine 12 transmitted.', created_at_ms: 1770000000000 }], total: 1 });
    if (p === '/api/v1/alerts/history/older-alert') return data({ id: 'older-alert', topic_id: 'fire',
      title: 'Earlier fire alert', body: 'An older alert reached by notification.', created_at_ms: 1760000000000 });
    if (p === '/api/v1/alerts/enrollments') {
      const value = request.postDataJSON(); state.writes.push({ p, value });
      if (state.publicMode === 'direct') return data({ device_id: 'synthetic-device', manage_token: 'synthetic-manage-token',
        status: state.deviceStatus, mode: 'direct' });
      return data({ id: 'synthetic-enrollment', manage_token: 'synthetic-manage-token', status: 'pending',
        enrollment_url: 'https://radioresolve.example.net/enroll?code=demo-id.demo-pairing-code-22', code: 'demo-id.demo-pairing-code-22',
        expires_at_ms: state.localExpiry ? Date.parse('2026-10-01T11:59:59Z') : Date.parse('2026-10-01T12:15:00Z') });
    }
    if (p === '/api/v1/alerts/enrollments/synthetic-enrollment') {
      state.enrollmentChecks++;
      if (state.enrollmentOutage) return route.fulfill({ status: 503, json: { error: { message: 'Temporarily unavailable.' } } });
      return data({ id: 'synthetic-enrollment', status: state.enrollmentStatus,
      device_id: state.enrollmentStatus === 'completed' ? 'synthetic-device' : '', enrollment_url: 'https://radioresolve.example.net/enroll?code=demo-id.demo-pairing-code-22', code: 'demo-id.demo-pairing-code-22',
      topic_ids: state.deviceTopics, prefs: state.devicePrefs });
    }
    if (p === '/api/v1/alerts/subscriptions/me') {
      if (request.method() === 'DELETE') { state.writes.push({ p, method: 'DELETE' }); return data({ status: 'disconnected' }); }
      if (request.method() === 'PUT') {
        const value = request.postDataJSON(); state.writes.push({ p, method: 'PUT', value });
        state.deviceTopics = value.topic_ids; state.devicePrefs = value.prefs;
      }
      return data({ device_id: 'synthetic-device', topic_ids: state.deviceTopics, status: state.deviceStatus, mode: state.deviceMode, prefs: state.devicePrefs });
    }
    if (p === '/api/v1/alerts/subscriptions/me/refresh') {
      state.writes.push({ p, value: request.postDataJSON(), authorization: request.headers().authorization });
      if (request.postDataJSON().subscription.endpoint.endsWith('/fresh')) state.deviceStatus = 'connected';
      return data({ device_id: 'synthetic-device', topic_ids: state.deviceTopics, status: state.deviceStatus, mode: state.deviceMode, prefs: state.devicePrefs });
    }
    return route.fallback();
  });
  return { state, fixture };
}

test('guest topics hide private names and connect through RadioResolve without an account', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 700 });
  const { state, fixture } = await installAlerts(page, { guest: true });
  await page.goto('/app.html?view=topics');
  await expect(page.getByRole('heading', { name: 'Topics', exact: true })).toBeVisible();
  await expect(page.locator('.alerts-content')).not.toContainText('Administrator alerts');
  await expect(page.locator('.alerts-content')).toContainText('Fire Tactical is active');
  await auditScreenshot(page, 'topics-light-phone');
  await expect(page.locator('.alerts-topic-card').filter({ hasText: 'Weather operations' }).getByRole('button', { name: 'Subscribe' })).toBeDisabled();
  await page.locator('.alerts-topic-card').filter({ hasText: 'Fire activity' }).getByRole('button', { name: 'Subscribe' }).click();
  const dialog = page.getByRole('dialog', { name: 'Alert subscription' });
  await expect(dialog.getByRole('textbox')).toHaveCount(1); // Time zone only; no account fields.
  await dialog.getByRole('button', { name: 'Connect notifications', exact: true }).click();
  await expect(dialog.getByRole('link', { name: 'Continue on RadioResolve' })).toBeVisible();
  await auditScreenshot(page, 'subscription-light-phone');
  expect(state.writes[0].value.topic_ids).toEqual(['fire']);
  expect(state.writes[0].value.prefs.quiet_start).toBe('');
  expect(state.writes[0].value).not.toHaveProperty('email');
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await page.getByRole('button', { name: 'Subscribe', exact: true }).first().click();
  await expect(page.getByRole('dialog').getByRole('link', { name: 'Continue on RadioResolve' })).toBeVisible();
  await expect(page.getByRole('dialog').getByRole('checkbox', { name: 'Fire activity', exact: true })).toBeChecked();
  await expect(page.getByRole('dialog').getByRole('button', { name: 'Waiting for device', exact: true })).toBeDisabled();
  await expectNoHorizontalOverflow(page, ['.alerts-workspace', '.read-only-modal']);
  expect(fixture.pageErrors).toEqual([]);
});

test('Direct save waits for the exact URL proof and invalidates it on edit', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  await page.goto('/app.html?view=admin&tab=push-setup');
  await page.getByRole('button', { name: 'Direct', exact: true }).click();
  const save = page.getByRole('button', { name: 'Save push settings', exact: true });
  await expect(save).toBeDisabled();
  await auditScreenshot(page, 'direct-setup-desktop');
  const url = page.getByRole('textbox', { name: 'VCE site URL', exact: true });
  await url.fill('https://vce.example.net:8443');
  await page.getByRole('button', { name: 'Test public access', exact: true }).click();
  await expect(save).toBeEnabled();
  await url.fill('https://other.example.net');
  await expect(save).toBeDisabled();
  expect(state.config.mode).toBe('proxy');
  await page.getByRole('button', { name: 'Test public access', exact: true }).click();
  await save.click();
  await expect(page.locator('.alerts-workspace')).toContainText('Push settings saved.');
  expect(state.writes.at(-1).value.test_token).toBe('synthetic-proof');
  expect(state.writes.at(-1).value.site_url).toBe('https://other.example.net');
  expect(fixture.pageErrors).toEqual([]);
});

for (const terminal of ['expired', 'cancelled']) {
  test(`a pending proxy link that becomes ${terminal} enables connection controls without a reload`, async ({ page }) => {
    const { state, fixture } = await installAlerts(page, { guest: true });
    await page.goto('/app.html?view=topics');
    await page.locator('.alerts-topic-card').filter({ hasText: 'Fire activity' }).getByRole('button', { name: 'Subscribe', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Alert subscription' });
    await dialog.getByRole('button', { name: 'Connect notifications', exact: true }).click();
    await expect(dialog.getByRole('button', { name: 'Waiting for device', exact: true })).toBeDisabled();
    state.enrollmentStatus = terminal;
    await expect(dialog.getByRole('button', { name: 'Connect notifications', exact: true })).toBeEnabled();
    await expect(dialog.getByRole('checkbox', { name: 'Fire activity', exact: true })).toBeEnabled();
    state.enrollmentStatus = 'pending';
    await dialog.getByRole('button', { name: 'Connect notifications', exact: true }).click();
    await expect(dialog.getByRole('link', { name: 'Continue on RadioResolve', exact: true })).toBeVisible();
    expect(state.writes.filter(write => write.p === '/api/v1/alerts/enrollments')).toHaveLength(2);
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('local pairing expiry restores usable connection controls and replaces the expired QR with retry guidance', async ({ page }) => {
  const { state, fixture } = await installAlerts(page, { guest: true });
  state.localExpiry = true;
  await page.goto('/app.html?view=topics');
  await page.locator('.alerts-topic-card').filter({ hasText: 'Fire activity' }).getByRole('button', { name: 'Subscribe', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Alert subscription' });
  await dialog.getByRole('button', { name: 'Connect notifications', exact: true }).click();
  await expect(dialog).toContainText('connection link expired');
  await expect(dialog.getByRole('button', { name: 'Connect notifications', exact: true })).toBeEnabled();
  await expect(dialog.getByRole('checkbox', { name: 'Fire activity', exact: true })).toBeEnabled();
  await expect(dialog.getByRole('link', { name: 'Continue on RadioResolve', exact: true })).toHaveCount(0);
  state.localExpiry = false;
  await dialog.getByRole('button', { name: 'Connect notifications', exact: true }).click();
  await expect(dialog.getByRole('link', { name: 'Continue on RadioResolve', exact: true })).toBeVisible();
  expect(state.writes.filter(write => write.p === '/api/v1/alerts/enrollments')).toHaveLength(2);
  expect(fixture.pageErrors).toEqual([]);
});

test('a temporary pending enrollment outage retries the same session without an additional enrollment', async ({ page }) => {
  const { state, fixture } = await installAlerts(page, { guest: true });
  state.enrollmentOutage = true;
  await page.goto('/app.html?view=topics');
  await page.locator('.alerts-topic-card').filter({ hasText: 'Fire activity' }).getByRole('button', { name: 'Subscribe', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Alert subscription' });
  await dialog.getByRole('button', { name: 'Connect notifications', exact: true }).click();
  await expect(dialog).toContainText('Retrying shortly');
  await expect(dialog.getByRole('button', { name: 'Waiting for device', exact: true })).toBeDisabled();
  state.enrollmentOutage = false;
  await expect(dialog.getByRole('link', { name: 'Continue on RadioResolve', exact: true })).toBeVisible();
  expect(state.enrollmentChecks).toBeGreaterThanOrEqual(2);
  expect(state.writes.filter(write => write.p === '/api/v1/alerts/enrollments')).toHaveLength(1);
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  expect(fixture.pageErrors).toEqual([]);
});

test('topic save locks edits and dismissal, and a failure preserves its draft', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  state.holdTopic = true;
  await page.goto('/app.html?view=admin&tab=alert-topics');
  await page.locator('.alerts-workspace').getByRole('button', { name: 'Fire activity', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Edit topic', exact: true });
  const name = dialog.getByRole('textbox', { name: 'Topic name', exact: true });
  await name.fill('Fire dispatch');
  await dialog.getByRole('button', { name: 'Save topic', exact: true }).click();
  await expect.poll(() => state.pending.length).toBe(1);
  await expect(name).toBeDisabled();
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  expect(state.writes).toHaveLength(1);
  await state.pending.shift()();
  await expect(dialog.getByRole('alert')).toContainText('Topic could not be saved');
  await expect(name).toBeEnabled();
  await expect(name).toHaveValue('Fire dispatch');
  expect(fixture.pageErrors).toEqual([]);
});

test('a declined Direct permission request retains topic choices and shows how to retry', async ({ page }) => {
  const { state, fixture } = await installAlerts(page, { guest: true });
  state.publicMode = 'direct';
  await page.addInitScript(() => { Object.defineProperty(Notification, 'requestPermission', { value: async () => 'denied' }); });
  await page.goto('/app.html?view=topics');
  await page.locator('.alerts-topic-card').filter({ hasText: 'Fire activity' }).getByRole('button', { name: 'Subscribe', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Alert subscription' });
  await dialog.getByRole('button', { name: 'Connect notifications', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('Notifications are blocked');
  await expect(dialog.getByRole('checkbox', { name: 'Fire activity', exact: true })).toBeChecked();
  expect(state.writes).toHaveLength(0);
  expect(fixture.pageErrors).toEqual([]);
});

test('mode changes require confirmed disconnection before a new enrollment and preserve available choices', async ({ page }) => {
  const { state, fixture } = await installAlerts(page, { guest: true });
  state.deviceMode = 'direct'; state.deviceTopics = ['fire', 'paused', 'administrator-alerts'];
  state.devicePrefs = { paused: true, quiet_enabled: true, quiet_start: '22:00', quiet_end: '07:00', time_zone: 'UTC' };
  await page.addInitScript(() => localStorage.setItem('vce-alert-device-v1', JSON.stringify({
    id: 'synthetic-device', manage_token: 'synthetic-manage-token', mode: 'direct', status: 'connected' })));
  await page.goto('/app.html?view=topics');
  await page.getByRole('button', { name: 'Notification settings', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Alert subscription' });
  await expect(dialog.getByRole('button', { name: 'Disconnect before reconnecting', exact: true })).toBeDisabled();
  await expect(dialog).not.toContainText('Administrator alerts');
  await dialog.getByRole('button', { name: 'Disconnect & reconnect', exact: true }).click();
  const confirmation = page.getByRole('alertdialog', { name: 'Reconnect notifications', exact: true });
  await confirmation.getByRole('button', { name: 'Disconnect previous connection', exact: true }).click();
  await expect(dialog.getByRole('button', { name: 'Connect notifications', exact: true })).toBeEnabled();
  expect(state.writes).toHaveLength(1);
  expect(state.writes[0].method).toBe('DELETE');
  await expect(dialog.getByRole('checkbox', { name: 'Fire activity', exact: true })).toBeChecked();
  await expect(dialog.getByRole('checkbox', { name: 'Pause notifications', exact: true })).toBeChecked();
  await dialog.getByRole('button', { name: 'Connect notifications', exact: true }).click();
  await expect(dialog.getByRole('link', { name: 'Continue on RadioResolve' })).toBeVisible();
  expect(state.writes[1].value.topic_ids).toEqual(['fire']);
  expect(state.writes[1].value.prefs.quiet_start).toBe('22:00');
  expect(fixture.pageErrors).toEqual([]);
});

async function installDirectDevice(page, { missing = false, manual = false, stored = true } = {}) {
  await page.addInitScript(({ missing, manual, stored }) => {
    if (stored) localStorage.setItem('vce-alert-device-v1', JSON.stringify({ id: 'synthetic-device',
      manage_token: 'synthetic-manage-token', mode: 'direct', status: 'connected' }));
    window.syntheticPermissionCalls = 0;
    window.syntheticUnsubscribeCalls = 0;
    window.syntheticSubscribeCalls = 0;
    window.syntheticWorkerMessages = [];
    Object.defineProperty(Notification, 'permission', { value: 'granted' });
    Object.defineProperty(Notification, 'requestPermission', { value: async () => {
      window.syntheticPermissionCalls++;
      if (manual) return 'granted';
      throw new Error('Unexpected automatic permission prompt');
    } });
    let subscription = missing ? null : { expirationTime: null,
      toJSON: () => ({ endpoint: 'https://push.example.net/renewed', keys: { p256dh: 'synthetic', auth: 'synthetic' } }),
      async unsubscribe() { window.syntheticUnsubscribeCalls++; subscription = null; return true; } };
    const registration = { active: { postMessage(value, ports) { window.syntheticWorkerMessages.push(value); ports[0].postMessage({ ok: true }); } },
      pushManager: { async getSubscription() { return subscription; },
      async subscribe() {
        window.syntheticSubscribeCalls++;
        if (!manual) throw new Error('Unexpected automatic subscription');
        subscription = { expirationTime: null, toJSON: () => ({ endpoint: 'https://push.example.net/fresh', keys: { p256dh: 'synthetic-new', auth: 'synthetic-new' } }) };
        return subscription;
      } } };
    Object.defineProperty(navigator, 'serviceWorker', { configurable: true, value: {
      getRegistration: async () => registration, register: async () => registration, ready: Promise.resolve(registration) } });
  }, { missing, manual, stored });
}

test('opening Topics renews a Direct endpoint using the existing token without permission prompts', async ({ page }) => {
  const { state, fixture } = await installAlerts(page, { guest: true });
  state.publicMode = state.deviceMode = 'direct';
  await installDirectDevice(page);
  await page.goto('/app.html?view=topics');
  await expect(page.locator('.alerts-topic-card').first()).toBeVisible();
  expect(state.writes).toHaveLength(1);
  expect(state.writes[0].p).toBe('/api/v1/alerts/subscriptions/me/refresh');
  expect(state.writes[0].authorization).toBe('Bearer synthetic-manage-token');
  expect(Object.keys(state.writes[0].value)).toEqual(['subscription']);
  expect(await page.evaluate(() => window.syntheticPermissionCalls)).toBe(0);
  expect(await page.evaluate(() => window.syntheticWorkerMessages[0].device.device_id)).toBe('synthetic-device');
  expect(fixture.pageErrors).toEqual([]);
});

test('a missing Direct subscription shows reconnect guidance and never prompts automatically', async ({ page }) => {
  const { state, fixture } = await installAlerts(page, { guest: true });
  state.publicMode = state.deviceMode = 'direct';
  await installDirectDevice(page, { missing: true });
  await page.goto('/app.html?view=topics');
  await expect(page.locator('.alerts-workspace')).toContainText('connection expired');
  await page.getByRole('button', { name: 'Notification settings', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Alert subscription' });
  await expect(dialog.getByRole('button', { name: 'Reconnect notifications', exact: true })).toBeVisible();
  expect(state.writes).toHaveLength(0);
  expect(await page.evaluate(() => window.syntheticPermissionCalls)).toBe(0);
  expect(fixture.pageErrors).toEqual([]);
});

test('explicit Direct renewal creates fresh material while retaining device ownership, hidden topics, and quiet preferences', async ({ page }) => {
  const { state, fixture } = await installAlerts(page, { guest: true });
  state.publicMode = state.deviceMode = 'direct'; state.deviceStatus = 'reconnect_required';
  state.deviceTopics = ['fire', 'paused', 'administrator-alerts'];
  state.devicePrefs = { paused: true, quiet_enabled: true, quiet_start: '22:00', quiet_end: '07:00', time_zone: 'UTC' };
  await installDirectDevice(page, { manual: true });
  await page.goto('/app.html?view=topics');
  await expect(page.locator('.alerts-workspace')).toContainText('connection expired');
  expect(await page.evaluate(() => window.syntheticPermissionCalls)).toBe(0);
  await page.getByRole('button', { name: 'Notification settings', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Alert subscription' });
  await expect(dialog).not.toContainText('Administrator alerts');
  await dialog.getByRole('button', { name: 'Reconnect notifications', exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(await page.evaluate(() => window.syntheticPermissionCalls)).toBe(1);
  expect(await page.evaluate(() => window.syntheticUnsubscribeCalls)).toBe(1);
  expect(await page.evaluate(() => window.syntheticSubscribeCalls)).toBe(1);
  expect(state.writes.filter(write => write.p === '/api/v1/alerts/enrollments')).toHaveLength(0);
  expect(state.writes.some(write => write.method === 'DELETE')).toBe(false);
  expect(state.deviceTopics).toEqual(['fire', 'paused', 'administrator-alerts']);
  expect(state.devicePrefs.quiet_start).toBe('22:00');
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('vce-alert-device-v1')).id)).toBe('synthetic-device');
  expect(fixture.pageErrors).toEqual([]);
});

test('reclaiming an expired Direct connection keeps management and offers renewal when local storage was cleared', async ({ page }) => {
  const { state, fixture } = await installAlerts(page, { guest: true });
  state.publicMode = state.deviceMode = 'direct'; state.deviceStatus = 'reconnect_required';
  await installDirectDevice(page, { manual: true, stored: false });
  await page.goto('/app.html?view=topics');
  await page.locator('.alerts-topic-card').filter({ hasText: 'Fire activity' }).getByRole('button', { name: 'Subscribe', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Alert subscription' });
  await dialog.getByRole('button', { name: 'Connect notifications', exact: true }).click();
  await expect(dialog.getByRole('button', { name: 'Reconnect notifications', exact: true })).toBeEnabled();
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('vce-alert-device-v1')).status)).toBe('connected');
  expect(state.writes.filter(write => write.p === '/api/v1/alerts/enrollments')).toHaveLength(1);
  await dialog.getByRole('button', { name: 'Reconnect notifications', exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(state.writes.filter(write => write.p === '/api/v1/alerts/enrollments')).toHaveLength(1);
  expect(state.writes.some(write => write.method === 'DELETE')).toBe(false);
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('vce-alert-device-v1')).id)).toBe('synthetic-device');
  expect(fixture.pageErrors).toEqual([]);
});

test('an expired proxy subscription keeps preference saving and phone reconnect guidance without deletion or enrollment', async ({ page }) => {
  const { state, fixture } = await installAlerts(page, { guest: true });
  state.deviceStatus = 'reconnect_required';
  state.deviceTopics = ['fire', 'paused', 'administrator-alerts'];
  state.devicePrefs = { paused: true, quiet_enabled: true, quiet_start: '22:00', quiet_end: '07:00', time_zone: 'UTC' };
  await page.addInitScript(() => localStorage.setItem('vce-alert-device-v1', JSON.stringify({
    id: 'synthetic-device', manage_token: 'synthetic-manage-token', mode: 'proxy', status: 'connected' })));
  await page.goto('/app.html?view=topics');
  await page.getByRole('button', { name: 'Notification settings', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Alert subscription' });
  await expect(dialog.getByRole('button', { name: 'Save preferences', exact: true })).toBeEnabled();
  await expect(dialog.getByRole('button', { name: 'Disconnect & reconnect', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('link', { name: 'RadioResolve notification settings', exact: true })).toHaveAttribute('href',
    'https://radioresolve.com/vce-push/settings.php');
  await expect(dialog.getByRole('img', { name: 'Scan to open RadioResolve notification settings', exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Save preferences', exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(state.writes).toHaveLength(1);
  expect(state.writes[0].method).toBe('PUT');
  expect(state.deviceTopics).toEqual(['fire', 'paused', 'administrator-alerts']);
  expect(state.devicePrefs.quiet_start).toBe('22:00');
  expect(fixture.pageErrors).toEqual([]);
});

test('worker device credentials round-trip through browser IndexedDB and are removed on disconnect', async ({ page }) => {
  await installAlerts(page, { guest: true });
  await page.goto('/app.html?view=topics');
  await page.addScriptTag({ path: path.resolve(__dirname, '../../../../../stats-web/assets/features/alerts-worker-storage.js') });
  const result = await page.evaluate(async () => {
    const store = self.VCEAlertWorkerStorage.createStore(indexedDB);
    await store.save({ device_id: 'synthetic-device', manage_token: 'synthetic-device-token', endpoint: 'discard' });
    const saved = await store.read();
    await store.clear();
    return { saved, cleared: await store.read() };
  });
  expect(Object.keys(result.saved).sort()).toEqual(['device_id', 'manage_token']);
  expect(result.cleared).toBeNull();
});

test('a notification link opens its exact older alert without depending on the first history page', async ({ page }) => {
  const { fixture } = await installAlerts(page, { guest: true });
  await page.goto('/app.html?view=topics&topic=fire&alert=older-alert');
  const dialog = page.getByRole('dialog', { name: 'Earlier fire alert', exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('An older alert reached by notification.');
  expect(fixture.pageErrors).toEqual([]);
});

test('alias child picker preserves the parent rule draft and returns a stable alias ID', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  await page.route('**/api/v1/aliases?**', route => route.fulfill({ json: { data: [{ id: 812,
    name: 'Engine 12', alias_list: 'County radios' }], meta: { total_count: 1, limit: 25, offset: 0, has_more: false, next_offset: null } } }));
  await page.goto('/app.html?view=admin&tab=alert-rules');
  await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
  const parent = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
  await parent.getByRole('textbox', { name: 'Rule name', exact: true }).fill('Engine response');
  await parent.getByRole('button', { name: 'Conditions', exact: true }).click();
  await parent.getByRole('button', { name: 'Add condition', exact: true }).click();
  await parent.getByRole('combobox', { name: 'Field', exact: true }).selectOption('alias_id');
  await parent.getByRole('button', { name: 'Choose alias', exact: true }).click();
  const child = page.getByRole('dialog', { name: 'Choose an alias', exact: true });
  await expect(child).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(child).toBeHidden();
  await expect(parent).toBeVisible();
  await parent.getByRole('button', { name: 'When', exact: true }).click();
  await expect(parent.getByRole('textbox', { name: 'Rule name', exact: true })).toHaveValue('Engine response');
  await parent.getByRole('button', { name: 'Conditions', exact: true }).click();
  await parent.getByRole('button', { name: 'Choose alias', exact: true }).click();
  await page.getByRole('dialog', { name: 'Choose an alias', exact: true }).getByRole('button', { name: /Engine 12.*812/ }).click();
  await expect(parent.getByRole('button', { name: 'Engine 12', exact: true })).toBeVisible();
  await parent.getByRole('button', { name: 'Create rule', exact: true }).click();
  await expect(parent).toBeHidden();
  expect(state.writes[0].value.rule.conditions.items[0].value).toBe('812');
  expect(fixture.pageErrors).toEqual([]);
});

test('rule builder preserves nested conditions and uses typed tone codes', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  await page.goto('/app.html?view=admin&tab=alert-rules');
  await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
  await dialog.getByRole('textbox', { name: 'Rule name', exact: true }).fill('Tactical tone');
  await dialog.getByRole('combobox', { name: 'Activity', exact: true }).selectOption('tone');
  await expect(dialog).toContainText('P25 Phase II');
  await dialog.getByRole('button', { name: 'Conditions', exact: true }).click();
  await dialog.getByRole('button', { name: 'Add group', exact: true }).click();
  await dialog.getByRole('combobox', { name: 'Field', exact: true }).selectOption('tone');
  await dialog.getByRole('combobox', { name: 'Value', exact: true }).selectOption('CALL_PROGRESS_TONE');
  await dialog.getByRole('button', { name: 'Create rule', exact: true }).click();
  await expect(dialog).toBeHidden();
  const rule = state.writes[0].value.rule;
  expect(rule.conditions.items[0].op).toBe('any');
  expect(rule.conditions.items[0].items[0].value).toBe('CALL_PROGRESS_TONE');
  expect(rule.click_action).toBe('details');
  expect(fixture.pageErrors).toEqual([]);
});

for (const theme of ['light', 'dark']) {
  test(`the phone enrollment QR is rendered in black and white and fits 320px in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 700 });
    await installAlerts(page, { guest: true, theme });
    await page.goto('/app.html?view=topics');
    if (theme === 'dark') {
      await page.getByRole('button', { name: 'Use dark theme', exact: true }).click();
      await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    }
    await page.locator('.alerts-topic-card').filter({ hasText: 'Fire activity' }).getByRole('button', { name: 'Subscribe', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Alert subscription' });
    await dialog.getByRole('button', { name: 'Connect notifications', exact: true }).click();
    const qr = dialog.getByRole('img', { name: 'Scan to connect a phone on RadioResolve', exact: true });
    await expect(qr).toBeVisible();
    const pixels = await qr.evaluate(canvas => {
      const { data } = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height);
      const colors = new Set();
      for (let index = 0; index < data.length; index += 4) colors.add([data[index], data[index + 1], data[index + 2], data[index + 3]].join(','));
      return [...colors].sort();
    });
    expect(pixels).toEqual(['0,0,0,255', '255,255,255,255']);
    const bounds = await qr.boundingBox();
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(320);
    await expect(dialog.getByRole('textbox', { name: 'Connection code', exact: true })).toHaveValue('demo-id.demo-pairing-code-22');
    if (process.env.ALERTS_AUDIT_DIR) {
      await qr.screenshot({ path: path.join(process.env.ALERTS_AUDIT_DIR, 'phone-enrollment-qr-' + theme + '.png') });
      await auditScreenshot(page, 'phone-enrollment-dialog-' + theme);
    }
  });

  test(`topics and rule editor fit a 320px phone in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 700 });
    const { fixture } = await installAlerts(page, { theme });
    await page.goto('/app.html?view=topics');
    await expect(page.locator('.alerts-topic-card').first()).toBeVisible();
    await expectNoHorizontalOverflow(page, ['.alerts-workspace']);
    await page.goto('/app.html?view=admin&tab=alert-rules');
    await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Conditions', exact: true }).click();
    await dialog.getByRole('button', { name: 'Add condition', exact: true }).click();
    await expectNoHorizontalOverflow(page, ['.read-only-modal', '.alerts-condition-row']);
    const clipped = await dialog.evaluate(element => {
      const bounds = element.getBoundingClientRect();
      return [...element.querySelectorAll('.alerts-editor-panel input, .alerts-editor-panel select, .alerts-editor-panel textarea, .alerts-editor-panel button')]
        .filter(control => control.getClientRects().length)
        .filter(control => { const rect = control.getBoundingClientRect(); return rect.left < bounds.left || rect.right > bounds.right; })
        .map(control => control.getAttribute('aria-label') || control.textContent);
    });
    expect(clipped).toEqual([]);
    await auditScreenshot(page, 'rule-editor-' + theme + '-phone');
    expect(fixture.pageErrors).toEqual([]);
  });
}
