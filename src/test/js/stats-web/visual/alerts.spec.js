'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, channelId } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');
const path = require('node:path');
const fs = require('node:fs');
const { expectFormWorkflowGeometry, expectStableFormWorkflowInsets, captureFormWorkflowEvidence,
  holdFormWorkflowFailure } = require('./fixtures/form-workflow-geometry.cjs');

async function auditScreenshot(page, name, fullPage = true) {
  if (!process.env.ALERTS_AUDIT_DIR) return;
  fs.mkdirSync(process.env.ALERTS_AUDIT_DIR, { recursive: true });
  await page.screenshot({ path: path.join(process.env.ALERTS_AUDIT_DIR, name + '.png'), fullPage });
}

async function revealPrimaryAction(page, action) {
  await action.evaluate(button => button.scrollIntoView({ block: 'center' }));
  await expect.poll(() => action.evaluate(button => {
    const rect = button.getBoundingClientRect();
    const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
    return Boolean(hit && (hit === button || button.contains(hit)));
  })).toBe(true);
}

async function selectManagedTopic(page, name) {
  const mobileTopic = page.getByRole('combobox', { name: 'Topic', exact: true });
  if (await mobileTopic.isVisible()) await mobileTopic.selectOption({ label: name });
  else await page.getByRole('navigation', { name: 'Topics', exact: true })
    .getByRole('link', { name: new RegExp(name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) }).click();
}

async function installAlerts(page, { guest = false, theme = 'light' } = {}) {
  const fixture = await installSiteStyleApplication(page, theme);
  const state = { revision: 1, topics: [
    { id: 'fire', name: 'Fire activity', description: 'Dispatch and tactical activity.', visible: true, enabled: true, built_in: false, rule_count: 0, subscription_count: 0 },
    { id: 'paused', name: 'Weather operations', description: 'Regional weather activity.', visible: true, enabled: false, built_in: false },
    { id: 'administrator-alerts', name: 'Administrator alerts', description: 'Tuner and system health.', visible: false, enabled: true, built_in: true }
  ], rules: [], options: { systems: [{ value: systemKey, label: 'County Public Safety', protocol_family: 'P25', system_key: systemKey }],
    channels: [{ value: channelId, label: 'County Dispatch', protocol_family: 'P25', channel_kind: 'TRUNKED', system_key: systemKey },
      { value: 'conventional-channel', label: 'Local repeater', protocol_family: 'NBFM', channel_kind: 'CONVENTIONAL', decoder_type: 'NBFM', system_key: '' }],
    sites: [{ value: '1', label: 'North site', system_key: systemKey, protocol_family: 'P25', channel_id: channelId }],
    protocols: [{ value: 'APCO25', label: 'P25 Phase I' }, { value: 'DCS', label: 'DCS' }], message_types: [{ value: 'PAGE', label: 'Page' }],
    tone_types: [{ value: 'INVALID', label: 'INVALID' }, { value: 'CALL_PROGRESS', label: 'Call progress tone' },
      { value: 'DTMF_1', label: 'DTMF 1' }, { value: 'KNOX_1', label: 'KNOX 1' },
      { value: 'HZ_1000_00', label: 'TONE 1000.00' }, { value: 'HZ_382_30', label: 'TONE 382.30', analog_only: true },
      { value: 'HZ_3487_00', label: 'TONE 3487.00', analog_only: true }],
    dcs_codes: [{ value: 'N023', label: 'DCS 023' }] },
    config: { revision: 1, enabled: true, mode: 'proxy', site_url: '', sender_label: 'VCE installation', registration: { status: 'approved', sender_id: 'synthetic-sender' } },
    writes: [], requests: [], pending: [], enrollmentStatus: 'pending', publicMode: 'proxy', publicReady: true,
    deviceMode: 'proxy', deviceStatus: 'connected', deviceTopics: ['fire'],
    holdTopic: false, holdRuleTest: false, ruleTestFailure: false, localExpiry: false, enrollmentOutage: false,
    enrollmentChecks: 0, registrationFailure: false, publicTestFailure: false, configSaveFailure: false };
  await page.route('**/api/v1/**', async route => {
    const request = route.request(); const url = new URL(request.url()); const p = url.pathname;
    state.requests.push({ p, method: request.method() });
    const data = value => route.fulfill({ json: { data: value } });
    if (guest && p === '/api/v1/auth/session') return data({ configured: true, authenticated: false, capabilities: { 'web-access': false } });
    if (/^\/api\/v1\/admin\/alerts\/rules\/[^/]+\/test$/.test(p)) {
      state.writes.push({ p, method: request.method(), value: request.postDataJSON() });
      const complete = () => state.ruleTestFailure ? route.fulfill({ status: 503,
        json: { error: { message: 'Test alert could not be sent. Try the Test button again.' } } }) : data({ queued: true });
      if (state.holdRuleTest) return new Promise(resolve => state.pending.push(async () => { await complete(); resolve(); }));
      return complete();
    }
    if (p === '/api/v1/admin/alerts/topics' || p === '/api/v1/admin/alerts/rules') {
      if (request.method() === 'GET') return data({ ...state, enabled: state.config.enabled });
      const value = request.postDataJSON(); state.writes.push({ p, value });
      if (state.holdTopic && value.topic) {
        await new Promise(resolve => state.pending.push(async () => {
          await route.fulfill({ status: 503, json: { error: { message: 'Topic could not be saved. Try again.' } } });
          resolve();
        }));
        return;
      }
      if (value.topic) state.topics = [...state.topics.filter(topic => topic.id !== value.topic.id), value.topic];
      if (value.rule) state.rules = [...state.rules.filter(rule => rule.id !== value.rule.id), value.rule];
      state.revision++; return data({ ...state, enabled: state.config.enabled });
    }
    if (p === '/api/v1/admin/alerts/config') {
      if (request.method() === 'GET') return data(state.config);
      const value = request.postDataJSON(); state.writes.push({ p, value });
      if (state.configSaveFailure) return route.fulfill({ status: 503,
        json: { error: { message: 'Push settings could not be saved. Try again.' } } });
      state.config = { ...state.config, ...value, revision: state.config.revision + 1 }; return data(state.config);
    }
    if (p === '/api/v1/admin/alerts/public-test') {
      const value = request.postDataJSON(); state.writes.push({ p, value });
      if (state.publicTestFailure) return route.fulfill({ status: 503, json: { error: { message: 'Public checker unavailable. Try again.' } } });
      return data({ public_access: { status: 'passed', checked_url: value.site_url, message: 'Public access passed.' }, test_token: 'synthetic-proof' });
    }
    if (p === '/api/v1/admin/alerts/registration') {
      if (request.method() !== 'GET') {
        state.writes.push({ p, method: request.method(), value: request.postDataJSON() });
        if (state.registrationFailure) return route.fulfill({ status: 503, json: { error: { message: 'Approval service unavailable. Try again.' } } });
        state.config.registration = { sender_id: 'synthetic-sender', status: request.method() === 'POST' ? 'pending' : 'deregistered' };
      }
      return data(state.config.registration);
    }
    if (p === '/api/v1/alerts/topics') return data({ topics: state.topics.filter(topic => topic.visible), mode: state.publicMode,
      enabled: state.config.enabled, push_enabled: state.config.enabled && state.publicReady,
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
      topic_ids: state.deviceTopics });
    }
    if (p === '/api/v1/alerts/subscriptions/me') {
      if (request.method() === 'DELETE') { state.writes.push({ p, method: 'DELETE' }); return data({ status: 'disconnected' }); }
      if (request.method() === 'PUT') {
        const value = request.postDataJSON(); state.writes.push({ p, method: 'PUT', value });
        state.deviceTopics = value.topic_ids;
      }
      return data({ device_id: 'synthetic-device', topic_ids: state.deviceTopics, status: state.deviceStatus, mode: state.deviceMode });
    }
    if (p === '/api/v1/alerts/subscriptions/me/refresh') {
      state.writes.push({ p, value: request.postDataJSON(), authorization: request.headers().authorization });
      if (request.postDataJSON().subscription.endpoint.endsWith('/fresh')) state.deviceStatus = 'connected';
      return data({ device_id: 'synthetic-device', topic_ids: state.deviceTopics, status: state.deviceStatus, mode: state.deviceMode });
    }
    return route.fallback();
  });
  return { state, fixture };
}

async function inspectAlertSaveStates(page, form, held, before, label, modal = true) {
  await expect(form).toHaveClass(/is-dirty/);
  await expectFormWorkflowGeometry(form, { modal });
  await expectStableFormWorkflowInsets(form, before);
  await captureFormWorkflowEvidence(form, `${label}-dirty`);
  await form.locator('.ui-form-submit').click();
  await expect.poll(held.pending).toBe(true);
  await expect(form).toHaveAttribute('aria-busy', 'true');
  await expectStableFormWorkflowInsets(form, before);
  await captureFormWorkflowEvidence(form, `${label}-saving`);
  await held.fail();
  await expect(form.getByRole('alert')).toContainText('could not be saved');
  await expectFormWorkflowGeometry(form, { modal });
  await expectStableFormWorkflowInsets(form, before);
  await captureFormWorkflowEvidence(form, `${label}-error`);
}

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 320], ['dark', 320]]) {
  test(`alert editor form spacing survives dirty busy and error states in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await installAlerts(page, { theme });
    const held = await holdFormWorkflowFailure(page, '**/api/v1/admin/alerts/**');
    for (const kind of ['topic', 'rule']) {
      await page.goto('/app.html?view=alerts');
      await page.getByRole('button', { name: kind === 'topic' ? 'New topic' : 'Create alert rule', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: kind === 'topic' ? 'Create topic' : 'Create alert rule', exact: true });
      const form = dialog.locator('form');
      const before = await expectFormWorkflowGeometry(form);
      await dialog.getByRole('textbox', { name: kind === 'topic' ? 'Topic name' : 'Rule name', exact: true })
        .fill(`Regional ${kind}`);
      await inspectAlertSaveStates(page, form, held, before, `alerts-${kind}-${theme}-${width}`);
      if (kind === 'rule') {
        for (const tab of ['Conditions', 'Timing', 'Notification', 'When']) {
          await dialog.getByRole('button', { name: tab, exact: true }).click();
          await expectFormWorkflowGeometry(form);
        }
      }
    }
  });

  test(`push setup form spacing survives dirty busy and error states in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await installAlerts(page, { theme });
    await page.goto('/app.html?view=admin&tab=push-setup');
    const form = page.locator('.alerts-workspace form');
    const before = await expectFormWorkflowGeometry(form, { modal: false });
    const held = await holdFormWorkflowFailure(page, '**/api/v1/admin/alerts/config');
    await page.getByRole('textbox', { name: 'VCE name', exact: true }).fill('Regional notification delivery');
    await inspectAlertSaveStates(page, form, held, before, `alerts-push-${theme}-${width}`, false);
  });

  test(`subscription form spacing survives dirty busy and error states in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    const { state } = await installAlerts(page, { guest: true, theme });
    state.deviceTopics = ['fire', 'paused'];
    await page.addInitScript(() => localStorage.setItem('vce-alert-device-v1', JSON.stringify({
      id: 'synthetic-device', manage_token: 'synthetic-manage-token', mode: 'proxy', status: 'connected' })));
    await page.goto('/app.html?view=topics');
    await page.getByRole('button', { name: 'Notification settings', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Alert subscription', exact: true });
    const form = dialog.locator('form');
    await expect(dialog.getByRole('checkbox', { name: 'Fire activity', exact: true })).toBeEnabled();
    const before = await expectFormWorkflowGeometry(form);
    const held = await holdFormWorkflowFailure(page, '**/api/v1/alerts/subscriptions/me');
    await dialog.getByRole('checkbox', { name: 'Fire activity', exact: true }).uncheck();
    await inspectAlertSaveStates(page, form, held, before, `alerts-subscription-${theme}-${width}`);
  });

  test(`alert alias search form spacing survives loading and error states in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await installAlerts(page, { theme });
    let failSearch;
    await page.route('**/api/v1/aliases?**', route => new Promise(resolve => {
      failSearch = async () => {
        await route.fulfill({ status: 503, json: { error: { message: 'Alias search unavailable.' } } });
        resolve();
      };
    }));
    await page.goto('/app.html?view=alerts');
    await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
    const parent = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
    await parent.getByRole('textbox', { name: 'Rule name', exact: true }).fill('Regional response');
    await parent.getByRole('button', { name: 'Conditions', exact: true }).click();
    await parent.getByRole('button', { name: 'Add condition', exact: true }).click();
    await parent.getByRole('combobox', { name: 'Field', exact: true }).selectOption('alias_id');
    await parent.getByRole('button', { name: 'Choose alias', exact: true }).click();
    const child = page.getByRole('dialog', { name: 'Choose an alias', exact: true });
    const form = child.locator('form');
    await expect.poll(() => Boolean(failSearch)).toBe(true);
    await expect(child).toContainText('Searching aliases…');
    const before = await expectFormWorkflowGeometry(form, { modal: false });
    await captureFormWorkflowEvidence(form, `alerts-alias-search-${theme}-${width}-loading`);
    await failSearch();
    await expect(child).toContainText('Aliases could not be searched. Try again.');
    await expectFormWorkflowGeometry(form, { modal: false });
    await expectStableFormWorkflowInsets(form, before);
    await expectNoHorizontalOverflow(page);
    await captureFormWorkflowEvidence(form, `alerts-alias-search-${theme}-${width}-error`);
  });
}

for (const [theme, width] of [['light', 1280], ['dark', 320]]) {
  test(`globally disabled alerts replace management actions with Push setup in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    const { state, fixture } = await installAlerts(page, { theme });
    state.config.enabled = false;
    state.rules = [{ id: 'dispatch', name: 'Dispatch activity', topic_id: 'fire', enabled: true, trigger: 'call' }];
    await page.goto('/app.html?view=alerts&topic=fire');
    const workspace = page.locator('.alerts-workspace');
    await expect(workspace).toContainText('Push notifications are disabled.');
    await expect(workspace.getByRole('link', { name: 'Open Push setup', exact: true }))
      .toHaveAttribute('href', /view=admin.*tab=push-setup/);
    await expect(workspace.getByRole('button')).toHaveCount(0);
    await expect(workspace.getByRole('navigation', { name: 'Topics', exact: true })).toHaveCount(0);
    await expect(workspace).not.toContainText('Dispatch activity');
    expect(state.writes).toEqual([]);
    await expectNoHorizontalOverflow(page, ['.alerts-workspace']);
    await auditScreenshot(page, `alerts-disabled-management-${theme}-${width}`, false);
    expect(fixture.pageErrors).toEqual([]);
  });

  test(`globally disabled public Topics retain the device without history or connection work in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    const { state, fixture } = await installAlerts(page, { guest: true, theme });
    state.config.enabled = false;
    state.publicMode = state.deviceMode = 'direct';
    await installDirectDevice(page, { missing: true });
    await page.goto('/app.html?view=topics&topic=fire&alert=older-alert');
    const workspace = page.locator('.alerts-workspace');
    await expect(workspace).toContainText('Push notifications are disabled.');
    if (theme === 'dark') {
      if (await page.locator('html').getAttribute('data-theme') !== 'dark') {
        await page.getByRole('button', { name: 'Use dark theme', exact: true }).click();
      }
      await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    }
    await expect(workspace.getByRole('button')).toHaveCount(0);
    await expect(workspace.locator('.alerts-topic-card, .alerts-timeline')).toHaveCount(0);
    expect(state.requests.filter(({ p }) => /^\/api\/v1\/alerts\/(history|subscriptions|enrollments)(?:\/|$)/.test(p))).toEqual([]);
    expect(state.writes).toEqual([]);
    expect(await page.evaluate(() => JSON.parse(localStorage.getItem('vce-alert-device-v1'))))
      .toMatchObject({ id: 'synthetic-device', mode: 'direct', status: 'connected' });
    expect(await page.evaluate(() => window.syntheticPermissionCalls)).toBe(0);
    expect(await page.evaluate(() => window.syntheticUnsubscribeCalls)).toBe(0);
    expect(await page.evaluate(() => window.syntheticWorkerMessages)).toEqual([]);
    await expectNoHorizontalOverflow(page, ['.alerts-workspace']);
    await auditScreenshot(page, `alerts-disabled-public-${theme}-${width}`, false);
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('enabled alerts retain management and listener navigation while delivery awaits approval', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  state.publicReady = false;
  state.config.registration = { status: 'pending', sender_id: 'synthetic-sender' };
  state.rules = [{ id: 'dispatch', name: 'Dispatch activity', topic_id: 'fire', enabled: true, trigger: 'call' }];
  await page.goto('/app.html?view=alerts');
  await expect(page.getByRole('button', { name: 'New topic', exact: true })).toBeEnabled();
  await expect(page.getByRole('button', { name: 'Edit topic', exact: true })).toBeEnabled();
  await expect(page.getByRole('button', { name: 'Create alert rule', exact: true })).toBeEnabled();
  await expect(page.getByRole('button', { name: 'Test alert rule: Dispatch activity', exact: true })).toBeEnabled();
  await expect(page.locator('#primary-navigation a[data-view="topics"]')).toHaveJSProperty('hidden', false);
  await expect(page.locator('.alerts-workspace')).not.toContainText('Push notifications are disabled.');
  expect(fixture.pageErrors).toEqual([]);
});

test('saving the global toggle updates listener navigation after success and restores management without a reload', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  await page.goto('/app.html?view=admin&tab=push-setup');
  await page.evaluate(() => { window.syntheticPushSettingsDocument = 'same-document'; });
  const master = page.getByRole('checkbox', { name: 'Enable push notifications', exact: true });
  const topicsLink = page.locator('#primary-navigation a[data-view="topics"]');
  await expect(master).toBeChecked();
  await expect(topicsLink).toHaveJSProperty('hidden', false);
  await master.focus(); await master.press('Space');
  await expect(master).toBeEnabled();
  await expect(page.getByRole('textbox', { name: 'VCE name', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Direct', exact: true })).toBeDisabled();
  await expect(topicsLink).toHaveJSProperty('hidden', false);
  const save = page.getByRole('button', { name: 'Save settings', exact: true });
  await expect(save).toBeEnabled();
  await revealPrimaryAction(page, save);
  state.configSaveFailure = true;
  await save.click();
  await expect(page.locator('.alerts-workspace').getByRole('alert')).toContainText('Push settings could not be saved. Try again.');
  await expect(topicsLink).toHaveJSProperty('hidden', false);
  expect(state.config.enabled).toBe(true);
  state.configSaveFailure = false;
  await save.click();
  await expect(topicsLink).toHaveJSProperty('hidden', true);
  expect(state.config.enabled).toBe(false);
  await expect(master).not.toBeChecked();
  await master.focus(); await master.press('Space');
  await expect(page.getByRole('textbox', { name: 'VCE name', exact: true })).toBeEnabled();
  await expect(page.getByRole('button', { name: 'Direct', exact: true })).toBeEnabled();
  await expect(topicsLink).toHaveJSProperty('hidden', true);
  await revealPrimaryAction(page, save);
  await save.click();
  await expect(topicsLink).toHaveJSProperty('hidden', false);
  expect(state.config.enabled).toBe(true);
  expect(state.writes.map(write => write.value.enabled)).toEqual([false, false, true]);
  expect(await page.evaluate(() => window.syntheticPushSettingsDocument)).toBe('same-document');
  await page.locator('#primary-navigation [data-nav-group="configuration"] > summary').click();
  await page.locator('#primary-navigation a[data-view="alerts"]').click();
  await expect(page.getByRole('button', { name: 'New topic', exact: true })).toBeVisible();
  expect(fixture.pageErrors).toEqual([]);
});

test('disabling Direct delivery saves without another public access test and locks dependent controls', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  state.config.mode = 'direct';
  state.config.site_url = 'https://vce.example.net';
  state.publicMode = state.deviceMode = 'direct';
  await page.goto('/app.html?view=admin&tab=push-setup');
  const master = page.getByRole('checkbox', { name: 'Enable push notifications', exact: true });
  await master.focus(); await master.press('Space');
  await expect(page.getByRole('textbox', { name: 'VCE site URL', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Test public access', exact: true })).toBeDisabled();
  const save = page.getByRole('button', { name: 'Save settings', exact: true });
  await expect(save).toBeEnabled();
  await revealPrimaryAction(page, save);
  await save.click();
  await expect(page.locator('#primary-navigation a[data-view="topics"]')).toHaveJSProperty('hidden', true);
  expect(state.writes).toHaveLength(1);
  expect(state.writes[0].p).toBe('/api/v1/admin/alerts/config');
  expect(state.writes[0].value).toMatchObject({ enabled: false, mode: 'direct', site_url: 'https://vce.example.net' });
  expect(fixture.pageErrors).toEqual([]);
});

test('disabled cloud setup does not offer approval until push notifications are enabled', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  state.config.enabled = false;
  state.config.registration = { status: 'unregistered', sender_id: '' };
  await page.goto('/app.html?view=admin&tab=push-setup');
  const master = page.getByRole('checkbox', { name: 'Enable push notifications', exact: true });
  await expect(master).not.toBeChecked();
  await expect(master).toBeEnabled();
  await expect(page.getByRole('textbox', { name: 'VCE name', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Direct', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Request approval', exact: true })).toHaveCount(0);
  await master.focus(); await master.press('Space');
  await expect(page.getByRole('button', { name: 'Request approval', exact: true })).toBeEnabled();
  await expect(page.locator('#primary-navigation a[data-view="topics"]')).toHaveJSProperty('hidden', true);
  expect(state.writes).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
});

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
  await expect(dialog.getByRole('textbox')).toHaveCount(0);
  await expect(dialog.getByRole('checkbox', { name: 'Pause notifications', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('checkbox', { name: 'Quiet hours', exact: true })).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Connect notifications', exact: true }).click();
  await expect(dialog.getByRole('link', { name: 'Continue on RadioResolve' })).toBeVisible();
  await auditScreenshot(page, 'subscription-light-phone');
  expect(state.writes[0].value.topic_ids).toEqual(['fire']);
  expect(state.writes[0].value).not.toHaveProperty('prefs');
  expect(state.writes[0].value).not.toHaveProperty('email');
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await page.getByRole('button', { name: 'Subscribe', exact: true }).first().click();
  await expect(page.getByRole('dialog').getByRole('link', { name: 'Continue on RadioResolve' })).toBeVisible();
  await page.getByRole('dialog').getByText('Selected topics', { exact: true }).click();
  await expect(page.getByRole('dialog').getByRole('checkbox', { name: 'Fire activity', exact: true })).toBeChecked();
  await expect(page.getByRole('dialog').getByRole('button', { name: 'Waiting for device', exact: true, includeHidden: true })).toBeDisabled();
  await expectNoHorizontalOverflow(page, ['.alerts-workspace', '.read-only-modal']);
  expect(fixture.pageErrors).toEqual([]);
});

test('Direct save waits for the exact URL proof and invalidates it on edit', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  await page.goto('/app.html?view=admin&tab=push-setup');
  const name = page.getByRole('textbox', { name: 'VCE name', exact: true });
  await expect(name).toBeVisible();
  await name.fill('Regional dispatch');
  await page.getByRole('button', { name: 'Direct', exact: true }).click();
  await expect(name).toBeVisible();
  await expect(name).toHaveValue('Regional dispatch');
  await expect(name).toHaveAttribute('maxlength', '80');
  const save = page.getByRole('button', { name: 'Save settings', exact: true });
  await expect(save).toBeDisabled();
  await revealPrimaryAction(page, save);
  await auditScreenshot(page, 'direct-setup-desktop', false);
  const url = page.getByRole('textbox', { name: 'VCE site URL', exact: true });
  await url.fill('https://vce.example.net:8443');
  await page.getByRole('button', { name: 'Test public access', exact: true }).click();
  await expect(save).toBeEnabled();
  await url.fill('https://other.example.net');
  await expect(save).toBeDisabled();
  expect(state.config.mode).toBe('proxy');
  await page.getByRole('button', { name: 'Test public access', exact: true }).click();
  await name.fill('   ');
  await save.click();
  await expect(page.getByRole('alertdialog')).toHaveCount(0);
  expect(state.writes.some(write => write.p === '/api/v1/admin/alerts/config')).toBe(false);
  await name.fill('Regional dispatch');
  await save.click();
  const confirmation = page.getByRole('alertdialog', { name: 'Change delivery', exact: true });
  await confirmation.getByRole('button', { name: 'Switch delivery', exact: true }).click();
  await expect(page.locator('.alerts-workspace .ui-form-submit')).toBeHidden();
  await expect(page.locator('.alerts-workspace')).not.toContainText('Settings saved.');
  await expect(page.locator('.alerts-workspace form')).not.toHaveClass(/is-dirty/);
  expect(state.writes.at(-1).value.test_token).toBe('synthetic-proof');
  expect(state.writes.at(-1).value.site_url).toBe('https://other.example.net');
  expect(state.writes.at(-1).value.sender_label).toBe('Regional dispatch');
  await name.fill('Edited again');
  await expect(page.locator('.alerts-workspace form')).toHaveClass(/is-dirty/);
  await expect(page.locator('.alerts-workspace .ui-form-state')).toHaveText('Unsaved changes');
  await expect(save).toBeVisible();
  await expect(page.locator('.alerts-workspace')).not.toContainText('Settings saved.');
  expect(fixture.pageErrors).toEqual([]);
});

test('cloud approval saves the installation name and requests approval in one action', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  state.config.registration = { status: 'unregistered', sender_id: '' };
  await page.goto('/app.html?view=admin&tab=push-setup');
  await expect(page.locator('.alerts-workspace')).toContainText('Sends push notifications through the cloud to your device. Choose this if you do not expose VCE for public access.');
  await expect(page.locator('.alerts-workspace')).toContainText('Usage is subject to approval. Most requests are granted within 24 hours. Abuse is subject to ban.');
  await expect(page.getByRole('textbox', { name: 'VCE site URL', exact: true })).toHaveCount(0);
  await revealPrimaryAction(page, page.getByRole('button', { name: 'Request approval', exact: true }));
  await auditScreenshot(page, 'cloud-delivery-desktop', false);
  await page.getByRole('textbox', { name: 'VCE name', exact: true }).fill('Regional dispatch');
  await page.getByRole('button', { name: 'Request approval', exact: true }).click();
  await expect(page.locator('.alerts-workspace')).toContainText('Awaiting approval');
  expect(state.writes.map(write => write.p)).toEqual(['/api/v1/admin/alerts/config', '/api/v1/admin/alerts/registration']);
  expect(state.writes[1].value.sender_label).toBe('Regional dispatch');
  await page.getByRole('button', { name: 'Check status', exact: true }).click();
  expect(state.writes).toHaveLength(2);
  await page.getByRole('textbox', { name: 'VCE name', exact: true }).fill('Updated dispatch');
  await expect(page.locator('.alerts-workspace')).not.toContainText('Approval requested.');
  await expect(page.getByRole('button', { name: 'Save settings', exact: true })).toBeVisible();
  expect(fixture.pageErrors).toEqual([]);
});

test('rule source selection progressively scopes protocol, system, and site and skips source for health', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  await page.goto('/app.html?view=alerts');
  await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
  await dialog.getByRole('textbox', { name: 'Rule name', exact: true }).fill('North site call');
  await expect(dialog.getByRole('combobox', { name: 'Radio system', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('combobox', { name: 'Channel', exact: true })).toHaveCount(0);
  await dialog.getByRole('combobox', { name: 'Protocol', exact: true }).selectOption('P25');
  await dialog.getByRole('button', { name: 'System', exact: true }).click();
  await dialog.getByRole('combobox', { name: 'Radio system', exact: true }).selectOption(systemKey);
  await expect(dialog.getByRole('combobox', { name: 'Site', exact: true })).toHaveValue('');
  await expect(dialog.getByRole('combobox', { name: 'Narrow source', exact: true })).toHaveCount(0);
  await dialog.getByRole('combobox', { name: 'Site', exact: true }).selectOption('1');
  await auditScreenshot(page, 'rule-source-desktop');
  await dialog.getByRole('combobox', { name: 'Activity', exact: true }).selectOption('health');
  await expect(dialog.getByRole('combobox', { name: 'Protocol', exact: true })).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Create rule', exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(state.writes[0].value.rule.protocol_family).toBe('');
  expect(state.writes[0].value.rule.system_key).toBe('');
  expect(state.writes[0].value.rule.site_id).toBe('');
  expect(fixture.pageErrors).toEqual([]);
});

test('conventional source family does not replace its decoded DCS event protocol', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  await page.goto('/app.html?view=alerts');
  await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
  await dialog.getByRole('textbox', { name: 'Rule name', exact: true }).fill('Repeater DCS');
  await dialog.getByRole('combobox', { name: 'Protocol', exact: true }).selectOption('NBFM');
  await dialog.getByRole('button', { name: 'Channel', exact: true }).click();
  const channel = dialog.getByRole('combobox', { name: 'Channel', exact: true });
  await expect(channel.locator('option')).toHaveCount(2);
  await channel.selectOption('conventional-channel');
  await expect(dialog).toContainText('Conventional · NBFM · Local repeater');
  await dialog.getByRole('combobox', { name: 'Activity', exact: true }).selectOption('dcs');
  await dialog.getByText('More source filters', { exact: true }).click();
  await dialog.getByRole('combobox', { name: 'Message protocol', exact: true }).selectOption('DCS');
  await dialog.getByRole('button', { name: 'Create rule', exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(state.writes[0].value.rule).toMatchObject({ protocol_family: 'NBFM', protocol: 'DCS', channel_id: 'conventional-channel', system_key: '' });
  expect(fixture.pageErrors).toEqual([]);
});

test('system scopes without observed sites omit Site and retain the optional configured channel filter', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  state.options.sites = [];
  await page.goto('/app.html?view=alerts');
  await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
  await dialog.getByRole('button', { name: 'System', exact: true }).click();
  await dialog.getByRole('combobox', { name: 'Radio system', exact: true }).selectOption(systemKey);
  await expect(dialog.getByRole('combobox', { name: 'Site', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('combobox', { name: 'Channel', exact: true })).toHaveCount(0);
  await dialog.getByText('More source filters', { exact: true }).click();
  await expect(dialog.getByRole('combobox', { name: 'Channel', exact: true })).toBeVisible();
  expect(fixture.pageErrors).toEqual([]);
});

test('activity choices reflect source capabilities without clearing an existing selection', async ({ page }) => {
  const { fixture } = await installAlerts(page);
  await page.goto('/app.html?view=alerts');
  await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
  const family = dialog.getByRole('combobox', { name: 'Protocol', exact: true });
  const activity = dialog.getByRole('combobox', { name: 'Activity', exact: true });
  await family.selectOption('DMR');
  await expect(activity.locator('option[value="dcs"]')).toHaveCount(0);
  await activity.selectOption('tone');
  for (const protocol of ['NBFM', 'AM', 'NXDN', 'P25']) {
    await family.selectOption(protocol);
    await expect(activity).toHaveValue('tone');
    await expect(activity.locator('option[value="tone"]')).toHaveText('Tone detected');
    await expect(dialog.getByText('Choose another activity or source.', { exact: true })).toBeHidden();
  }
  await family.selectOption('NBFM');
  await activity.selectOption('dcs');
  await family.selectOption('AM');
  await expect(activity).toHaveValue('dcs');
  await family.selectOption('NXDN');
  await expect(activity).toHaveValue('dcs');
  await expect(activity.locator('option[value="dcs"]')).toContainText('unavailable for this source');
  await expect(dialog.getByText('Choose another activity or source.', { exact: true })).toBeVisible();
  await expect(activity.locator('option[value="health"]')).toHaveCount(1);
  expect(fixture.pageErrors).toEqual([]);
});

for (const protocol of ['NBFM', 'AM', 'NXDN']) {
  for (const source of ['protocol', 'channel']) {
    test(`tone alerts save and reopen for ${protocol} selected by ${source}`, async ({ page }) => {
      const phone = protocol === 'AM' && source === 'channel';
      await page.setViewportSize({ width: phone ? 320 : 1280, height: phone ? 844 : 900 });
      const { state, fixture } = await installAlerts(page, { theme: phone ? 'dark' : 'light' });
      const selectedChannel = `tone-channel-${protocol.toLowerCase()}`;
      const toneCode = protocol === 'NXDN' ? 'HZ_1000_00' : 'HZ_382_30';
      state.options.channels.push({ value: selectedChannel, label: `${protocol} Dispatch`, protocol_family: protocol,
        channel_kind: 'CONVENTIONAL', decoder_type: protocol, system_key: '' });
      await page.goto('/app.html?view=alerts');
      await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
      const name = `${protocol} paging tone by ${source}`;
      await dialog.getByRole('textbox', { name: 'Rule name', exact: true }).fill(name);
      if (source === 'protocol') {
        await dialog.getByRole('combobox', { name: 'Protocol', exact: true }).selectOption(protocol);
      } else {
        await dialog.getByRole('button', { name: 'Channel', exact: true }).click();
        await dialog.getByRole('combobox', { name: 'Channel', exact: true }).selectOption(selectedChannel);
        await expect(dialog.getByRole('combobox', { name: 'Protocol', exact: true })).toHaveValue('');
        await expect(dialog).toContainText(`Conventional · ${protocol} · ${protocol} Dispatch`);
      }
      const activity = dialog.getByRole('combobox', { name: 'Activity', exact: true });
      await expect(activity.locator('option[value="tone"]')).toHaveText('Tone detected');
      await activity.selectOption('tone');
      await expect(dialog.getByText('Matches detected tones when the call ends.', { exact: true })).toBeVisible();
      await expect(dialog.getByText('Choose another activity or source.', { exact: true })).toBeHidden();
      await expect(dialog).not.toContainText('AMBE tones require');
      if (phone || protocol === 'NBFM' && source === 'protocol') {
        await expectNoHorizontalOverflow(page, ['.ui-modal-dialog', '.alerts-form']);
        await auditScreenshot(page, `tone-editor-${protocol.toLowerCase()}-${source}-${phone ? 'dark-phone' : 'light-desktop'}`);
      }
      await dialog.getByRole('button', { name: 'Conditions', exact: true }).click();
      await dialog.getByRole('button', { name: 'Add condition', exact: true }).click();
      const field = dialog.getByRole('combobox', { name: 'Field', exact: true });
      await expect(field.locator('option[value="tone"]')).toHaveText('Tone');
      await field.selectOption('tone');
      const tone = dialog.getByRole('combobox', { name: 'Value', exact: true });
      await expect(tone.locator('option[value="INVALID"]')).toHaveCount(0);
      await expect(tone.locator('option[value="DTMF_1"]')).toHaveCount(1);
      await expect(tone.locator('option[value="CALL_PROGRESS"]')).toHaveCount(protocol === 'NXDN' ? 1 : 0);
      await expect(tone.locator('option[value="KNOX_1"]')).toHaveCount(protocol === 'NXDN' ? 1 : 0);
      await expect(tone.locator('option[value="HZ_382_30"]')).toHaveCount(protocol === 'NXDN' ? 0 : 1);
      await expect(tone.locator('option[value="HZ_3487_00"]')).toHaveCount(protocol === 'NXDN' ? 0 : 1);
      await tone.selectOption(toneCode);
      if (phone) await auditScreenshot(page, 'tone-editor-am-conditions-dark-phone');
      await revealPrimaryAction(page, dialog.getByRole('button', { name: 'Create rule', exact: true }));
      await dialog.getByRole('button', { name: 'Create rule', exact: true }).click();
      await expect(dialog).toBeHidden();
      expect(state.writes).toHaveLength(1);
      expect(state.writes[0].value.rule).toMatchObject({
        trigger: 'tone', protocol_family: source === 'protocol' ? protocol : '', protocol: '',
        channel_id: source === 'channel' ? selectedChannel : '',
        conditions: { op: 'all', items: [{ field: 'tone', operator: 'is', value: toneCode }] }
      });
      await page.locator('.alerts-management-rules').getByRole('button', { name, exact: true }).click();
      const edit = page.getByRole('dialog', { name: 'Edit alert rule', exact: true });
      await expect(edit.getByRole('combobox', { name: 'Activity', exact: true })).toHaveValue('tone');
      await expect(edit.getByRole('combobox', { name: 'Activity', exact: true }).locator('option:checked')).toHaveText('Tone detected');
      await expect(edit.getByRole('combobox', { name: 'Protocol', exact: true })).toHaveValue(source === 'protocol' ? protocol : '');
      if (source === 'channel') await expect(edit.getByRole('combobox', { name: 'Channel', exact: true })).toHaveValue(selectedChannel);
      await expect(edit.getByText('Choose another activity or source.', { exact: true })).toBeHidden();
      await edit.getByRole('button', { name: 'Conditions', exact: true }).click();
      await expect(edit.getByRole('combobox', { name: 'Field', exact: true })).toHaveValue('tone');
      await expect(edit.getByRole('combobox', { name: 'Value', exact: true })).toHaveValue(toneCode);
      expect(fixture.pageErrors).toEqual([]);
    });
  }
}

test('tone choices follow a source change and preserve an existing encoded tone condition', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  await page.goto('/app.html?view=alerts');
  await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
  await dialog.getByRole('textbox', { name: 'Rule name', exact: true }).fill('Existing encoded tone');
  await dialog.getByRole('combobox', { name: 'Protocol', exact: true }).selectOption('NXDN');
  await dialog.getByRole('combobox', { name: 'Activity', exact: true }).selectOption('tone');
  await dialog.getByRole('button', { name: 'Conditions', exact: true }).click();
  await dialog.getByRole('button', { name: 'Add condition', exact: true }).click();
  await dialog.getByRole('combobox', { name: 'Field', exact: true }).selectOption('tone');
  await dialog.getByRole('combobox', { name: 'Value', exact: true }).selectOption('CALL_PROGRESS');
  await dialog.getByRole('button', { name: 'When', exact: true }).click();
  await dialog.getByRole('combobox', { name: 'Protocol', exact: true }).selectOption('NBFM');
  await dialog.getByRole('button', { name: 'Conditions', exact: true }).click();
  const tone = dialog.getByRole('combobox', { name: 'Value', exact: true });
  await expect(tone).toHaveValue('CALL_PROGRESS');
  await expect(tone.locator('option:checked')).toHaveText('CALL_PROGRESS · unavailable');
  await expect(tone.locator('option[value="KNOX_1"]')).toHaveCount(0);
  await expect(tone.locator('option[value="DTMF_1"]')).toHaveCount(1);
  await dialog.getByRole('button', { name: 'Create rule', exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(state.writes[0].value.rule.conditions.items[0].value).toBe('CALL_PROGRESS');
  await page.locator('.alerts-management-rules').getByRole('button', { name: 'Existing encoded tone', exact: true }).click();
  const edit = page.getByRole('dialog', { name: 'Edit alert rule', exact: true });
  await edit.getByRole('button', { name: 'Conditions', exact: true }).click();
  await expect(edit.getByRole('combobox', { name: 'Value', exact: true })).toHaveValue('CALL_PROGRESS');
  await expect(edit.getByRole('combobox', { name: 'Value', exact: true }).locator('option:checked')).toContainText('unavailable');
  await edit.getByRole('button', { name: 'When', exact: true }).click();
  await edit.getByRole('combobox', { name: 'Protocol', exact: true }).selectOption('NXDN');
  await edit.getByRole('button', { name: 'Conditions', exact: true }).click();
  await expect(edit.getByRole('combobox', { name: 'Value', exact: true })).toHaveValue('CALL_PROGRESS');
  await expect(edit.getByRole('combobox', { name: 'Value', exact: true }).locator('option:checked')).toHaveText('Call progress tone');
  await expect(edit.getByRole('combobox', { name: 'Value', exact: true }).locator('option[value="KNOX_1"]')).toHaveCount(1);
  expect(fixture.pageErrors).toEqual([]);
});

test('approval failure retains the name and uses the new saved revision on retry', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  state.config.registration = { status: 'unregistered', sender_id: '' };
  state.registrationFailure = true;
  await page.goto('/app.html?view=admin&tab=push-setup');
  await page.getByRole('textbox', { name: 'VCE name', exact: true }).fill('Regional dispatch');
  await page.getByRole('button', { name: 'Request approval', exact: true }).click();
  await expect(page.locator('.alerts-workspace')).toContainText('Approval service unavailable. Try again.');
  await expect(page.getByRole('textbox', { name: 'VCE name', exact: true })).toHaveValue('Regional dispatch');
  state.registrationFailure = false;
  await page.getByRole('button', { name: 'Request approval', exact: true }).click();
  await expect(page.locator('.alerts-workspace')).toContainText('Awaiting approval');
  expect(state.writes[2].value.revision).toBe(2);
  expect(fixture.pageErrors).toEqual([]);
});

test('Direct public-test errors identify the unavailable checker and keep saving disabled', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  state.publicTestFailure = true;
  await page.goto('/app.html?view=admin&tab=push-setup');
  await page.getByRole('button', { name: 'Direct', exact: true }).click();
  await page.getByRole('textbox', { name: 'VCE site URL', exact: true }).fill('https://vce.example.net');
  await page.getByRole('button', { name: 'Test public access', exact: true }).click();
  await expect(page.locator('.alerts-workspace')).toContainText('Public checker unavailable. Try again.');
  await expect(page.getByRole('status').filter({ hasText: 'Public checker unavailable. Try again.' })).toHaveAttribute('aria-live', 'polite');
  await expect(page.getByRole('button', { name: 'Save settings', exact: true })).toBeDisabled();
  expect(state.config.mode).toBe('proxy');
  expect(fixture.pageErrors).toEqual([]);
});

for (const terminal of ['expired', 'cancelled']) {
  test(`a pending proxy link that becomes ${terminal} enables connection controls without a reload`, async ({ page }) => {
    const { state, fixture } = await installAlerts(page, { guest: true });
    await page.goto('/app.html?view=topics');
    await page.locator('.alerts-topic-card').filter({ hasText: 'Fire activity' }).getByRole('button', { name: 'Subscribe', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Alert subscription' });
    await dialog.getByRole('button', { name: 'Connect notifications', exact: true }).click();
    await expect(dialog.getByRole('button', { name: 'Waiting for device', exact: true, includeHidden: true })).toBeDisabled();
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
  await expect(dialog.getByRole('button', { name: 'Waiting for device', exact: true, includeHidden: true })).toBeDisabled();
  state.enrollmentOutage = false;
  await expect(dialog.getByRole('link', { name: 'Continue on RadioResolve', exact: true })).toBeVisible();
  await expect.poll(() => state.enrollmentChecks, { timeout: 6000 }).toBeGreaterThanOrEqual(2);
  await expect(dialog).not.toContainText('Retrying shortly');
  expect(state.writes.filter(write => write.p === '/api/v1/alerts/enrollments')).toHaveLength(1);
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  expect(fixture.pageErrors).toEqual([]);
});

test('topic save locks edits and dismissal, and a failure preserves its draft', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  state.holdTopic = true;
  await page.goto('/app.html?view=alerts');
  await page.getByRole('button', { name: 'Edit topic', exact: true }).click();
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
  await expect(dialog.getByRole('checkbox', { name: 'Pause notifications', exact: true })).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Connect notifications', exact: true }).click();
  await expect(dialog.getByRole('link', { name: 'Continue on RadioResolve' })).toBeVisible();
  expect(state.writes[1].value.topic_ids).toEqual(['fire']);
  expect(state.writes[1].value).not.toHaveProperty('prefs');
  expect(fixture.pageErrors).toEqual([]);
});

test('the Follow alert topics Subscribe button starts a fresh cloud connection after unsubscribe', async ({ page }) => {
  const { state, fixture } = await installAlerts(page, { guest: true });
  await page.addInitScript(() => localStorage.setItem('vce-alert-device-v1', JSON.stringify({
    id: 'synthetic-device', manage_token: 'synthetic-old-token', mode: 'proxy', status: 'connected' })));
  await page.goto('/app.html?view=topics');
  const introduction = page.locator('.alerts-public-sidebar > header');
  await introduction.getByRole('button', { name: 'Notification settings', exact: true }).click();
  let dialog = page.getByRole('dialog', { name: 'Alert subscription' });
  await expect(dialog.locator('.ui-form-submit')).toBeHidden();
  await dialog.getByRole('button', { name: 'Unsubscribe this device', exact: true }).click();
  await page.getByRole('alertdialog', { name: 'Unsubscribe device', exact: true })
    .getByRole('button', { name: 'Unsubscribe', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  expect(await page.evaluate(() => localStorage.getItem('vce-alert-device-v1'))).toBeNull();
  expect(state.writes).toEqual([{ p: '/api/v1/alerts/subscriptions/me', method: 'DELETE' }]);

  await introduction.getByRole('button', { name: 'Subscribe', exact: true }).click();
  dialog = page.getByRole('dialog', { name: 'Alert subscription' });
  await expect(dialog.getByRole('checkbox', { name: 'Fire activity', exact: true })).not.toBeChecked();
  await dialog.getByRole('checkbox', { name: 'Fire activity', exact: true }).check();
  expect(state.writes).toHaveLength(1);
  await dialog.getByRole('button', { name: 'Connect notifications', exact: true }).click();
  await expect(dialog.getByRole('link', { name: 'Continue on RadioResolve', exact: true })).toBeVisible();
  await expect(dialog).toContainText('add VCE Notifications to your Home Screen');
  await auditScreenshot(page, 'resubscribe-connect-notifications');
  expect(state.writes).toHaveLength(2);
  expect(state.writes[1]).toEqual({ p: '/api/v1/alerts/enrollments', value: { topic_ids: ['fire'] } });
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

test('unsubscribing an expired Direct device resets renewal state only after success and allows a fresh subscription', async ({ page }) => {
  const { state, fixture } = await installAlerts(page, { guest: true });
  state.publicMode = state.deviceMode = 'direct';
  await installDirectDevice(page, { missing: true, manual: true });
  let rejectDisconnect = true;
  await page.route('**/api/v1/alerts/subscriptions/me', route => {
    if (route.request().method() === 'DELETE' && rejectDisconnect) return route.fulfill({ status: 503,
      json: { error: { message: 'Could not disconnect. Try again.' } } });
    return route.fallback();
  });
  await page.goto('/app.html?view=topics');
  const introduction = page.locator('.alerts-public-sidebar > header');
  await introduction.getByRole('button', { name: 'Notification settings', exact: true }).click();
  let dialog = page.getByRole('dialog', { name: 'Alert subscription' });
  const unsubscribe = async () => {
    await dialog.getByRole('button', { name: 'Unsubscribe this device', exact: true }).click();
    await page.getByRole('alertdialog', { name: 'Unsubscribe device', exact: true })
      .getByRole('button', { name: 'Unsubscribe', exact: true }).click();
  };
  await unsubscribe();
  await expect(dialog.getByRole('alert')).toContainText('Could not disconnect. Try again.');
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('vce-alert-device-v1')).id)).toBe('synthetic-device');
  await expect(dialog.getByRole('button', { name: 'Reconnect notifications', exact: true })).toBeEnabled();
  await expect(page.locator('.alerts-workspace')).toContainText('connection expired');
  expect(await page.evaluate(() => window.syntheticPermissionCalls)).toBe(0);

  rejectDisconnect = false;
  await unsubscribe();
  await expect(dialog).toHaveCount(0);
  expect(await page.evaluate(() => localStorage.getItem('vce-alert-device-v1'))).toBeNull();
  await expect(page.locator('.alerts-workspace')).not.toContainText('connection expired');
  await introduction.getByRole('button', { name: 'Subscribe', exact: true }).click();
  dialog = page.getByRole('dialog', { name: 'Alert subscription' });
  await dialog.getByRole('checkbox', { name: 'Fire activity', exact: true }).check();
  const connect = dialog.getByRole('button', { name: 'Connect notifications', exact: true });
  await expect(connect).toBeEnabled();
  await expect(dialog.getByRole('checkbox', { name: 'Fire activity', exact: true })).toBeChecked();
  expect(await page.evaluate(() => window.syntheticPermissionCalls)).toBe(0);
  expect(state.writes.filter(write => write.p === '/api/v1/alerts/enrollments')).toHaveLength(0);
  if (process.env.ALERTS_AUDIT_DIR) {
    fs.mkdirSync(process.env.ALERTS_AUDIT_DIR, { recursive: true });
    await page.screenshot({ path: path.join(process.env.ALERTS_AUDIT_DIR, 'direct-resubscribe-ready.png'), fullPage: true, animations: 'disabled' });
  }
  await connect.click();
  await expect(dialog).toHaveCount(0);
  expect(await page.evaluate(() => window.syntheticPermissionCalls)).toBe(1);
  expect(await page.evaluate(() => window.syntheticSubscribeCalls)).toBe(1);
  expect(state.writes.filter(write => write.p === '/api/v1/alerts/enrollments')).toHaveLength(1);
  expect(state.writes.find(write => write.p === '/api/v1/alerts/enrollments').value.topic_ids).toEqual(['fire']);
  expect(fixture.pageErrors).toEqual([]);
});

for (const status of [401, 403, 404, 410]) {
  test(`lost Direct ownership (${status}) clears stale renewal state and waits for explicit permission before a fresh subscription`, async ({ page }) => {
    const { state, fixture } = await installAlerts(page, { guest: true });
    state.publicMode = state.deviceMode = 'direct';
    await installDirectDevice(page, { missing: true, manual: true });
    await page.route('**/api/v1/alerts/subscriptions/me', route => {
      if (route.request().method() === 'GET') return route.fulfill({ status,
        json: { error: { message: 'This device connection is unavailable. Subscribe again.' } } });
      return route.fallback();
    });
    await page.goto('/app.html?view=topics');
    await page.getByRole('button', { name: 'Notification settings', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Alert subscription' });
    await dialog.getByRole('checkbox', { name: 'Fire activity', exact: true }).check();
    const connect = dialog.getByRole('button', { name: 'Connect notifications', exact: true });
    await expect(connect).toBeEnabled();
    await expect(dialog).not.toContainText('expired or is unavailable');
    expect(await page.evaluate(() => localStorage.getItem('vce-alert-device-v1'))).toBeNull();
    expect(await page.evaluate(() => window.syntheticPermissionCalls)).toBe(0);
    expect(state.writes).toHaveLength(0);
    await connect.click();
    await expect(dialog).toHaveCount(0);
    expect(await page.evaluate(() => window.syntheticPermissionCalls)).toBe(1);
    expect(state.writes.filter(write => write.p === '/api/v1/alerts/enrollments')).toHaveLength(1);
    expect(state.writes.some(write => write.method === 'DELETE')).toBe(false);
    expect(fixture.pageErrors).toEqual([]);
  });
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

test('explicit Direct renewal creates fresh material while retaining device ownership and hidden topics', async ({ page }) => {
  const { state, fixture } = await installAlerts(page, { guest: true });
  state.publicMode = state.deviceMode = 'direct'; state.deviceStatus = 'reconnect_required';
  state.deviceTopics = ['fire', 'paused', 'administrator-alerts'];
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
  expect(state.writes.find(write => write.method === 'PUT').value).not.toHaveProperty('prefs');
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

test('an expired cloud subscription keeps topic saving and phone reconnect guidance without deletion or enrollment', async ({ page }) => {
  const { state, fixture } = await installAlerts(page, { guest: true });
  state.deviceStatus = 'reconnect_required';
  state.deviceTopics = ['fire', 'paused', 'administrator-alerts'];
  await page.addInitScript(() => localStorage.setItem('vce-alert-device-v1', JSON.stringify({
    id: 'synthetic-device', manage_token: 'synthetic-manage-token', mode: 'proxy', status: 'connected' })));
  await page.goto('/app.html?view=topics');
  await page.getByRole('button', { name: 'Notification settings', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Alert subscription' });
  await expect(dialog.locator('.ui-form-submit')).toBeHidden();
  await expect(dialog.getByRole('button', { name: 'Disconnect & reconnect', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('link', { name: 'RadioResolve notification settings', exact: true })).toHaveAttribute('href',
    'https://radioresolve.com/vce-push/settings.php');
  await expect(dialog.getByRole('img', { name: 'Scan to open RadioResolve notification settings', exact: true })).toBeVisible();
  await dialog.getByRole('checkbox', { name: 'Fire activity', exact: true }).uncheck();
  await expect(dialog.getByRole('button', { name: 'Save topics', exact: true })).toBeEnabled();
  await dialog.getByRole('button', { name: 'Save topics', exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(state.writes).toHaveLength(1);
  expect(state.writes[0].method).toBe('PUT');
  expect(state.deviceTopics).toEqual(['paused', 'administrator-alerts']);
  expect(state.writes[0].value).not.toHaveProperty('prefs');
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
  await page.goto('/app.html?view=alerts');
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
  await page.goto('/app.html?view=alerts');
  await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
  await dialog.getByRole('textbox', { name: 'Rule name', exact: true }).fill('Tactical tone');
  await dialog.getByRole('combobox', { name: 'Activity', exact: true }).selectOption('tone');
  await expect(dialog).toContainText('Matches detected tones when the call ends.');
  await dialog.getByRole('button', { name: 'Conditions', exact: true }).click();
  await dialog.getByRole('button', { name: 'Add group', exact: true }).click();
  await dialog.getByRole('combobox', { name: 'Field', exact: true }).selectOption('tone');
  await dialog.getByRole('combobox', { name: 'Value', exact: true }).selectOption('CALL_PROGRESS');
  await dialog.getByRole('button', { name: 'Create rule', exact: true }).click();
  await expect(dialog).toBeHidden();
  const rule = state.writes[0].value.rule;
  expect(rule.conditions.items[0].op).toBe('any');
  expect(rule.conditions.items[0].items[0].value).toBe('CALL_PROGRESS');
  expect(rule.click_action).toBe('details');
  expect(fixture.pageErrors).toEqual([]);
});

for (const theme of ['light', 'dark']) {
  test(`delivery choices and the direct test fit a 320px phone in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 700 });
    const { fixture } = await installAlerts(page, { theme });
    await page.goto('/app.html?view=admin&tab=push-setup');
    await expect(page.getByRole('textbox', { name: 'VCE site URL', exact: true })).toHaveCount(0);
    await expectNoHorizontalOverflow(page, ['.alerts-workspace']);
    await auditScreenshot(page, 'cloud-delivery-' + theme + '-phone');
    await page.getByRole('button', { name: 'Direct', exact: true }).click();
    const url = page.getByRole('textbox', { name: 'VCE site URL', exact: true });
    await url.fill('https://vce.example.net:8443');
    await page.getByRole('button', { name: 'Test public access', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Save settings', exact: true })).toBeEnabled();
    await revealPrimaryAction(page, page.getByRole('button', { name: 'Save settings', exact: true }));
    await expectNoHorizontalOverflow(page, ['.alerts-workspace', '.alerts-direct-test']);
    await auditScreenshot(page, 'direct-delivery-' + theme + '-phone', false);
    expect(fixture.pageErrors).toEqual([]);
  });

  test(`the transparent phone enrollment QR fits 320px and follows theme changes in ${theme}`, async ({ page }) => {
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
    await expect(qr).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
    await expect(qr).toHaveCSS('filter', theme === 'dark' ? 'invert(1)' : 'none');
    const pixels = await qr.evaluate(canvas => {
      const { data } = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height);
      const colors = new Set();
      for (let index = 0; index < data.length; index += 4) colors.add([data[index], data[index + 1], data[index + 2], data[index + 3]].join(','));
      return [...colors].sort();
    });
    expect(pixels).toEqual(['0,0,0,0', '0,0,0,255']);
    const bounds = await qr.boundingBox();
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(320);
    await expect(dialog.getByRole('textbox', { name: 'Connection code', exact: true })).toHaveCount(0);
    await dialog.getByText('Use a connection code', { exact: true }).click();
    await expect(dialog.getByRole('textbox', { name: 'Connection code', exact: true })).toHaveValue('demo-id.demo-pairing-code-22');
    await expect(dialog.getByRole('button', { name: 'Copy connection code', exact: true })).toBeEnabled();
    await dialog.getByText('Use a connection code', { exact: true }).click();
    if (process.env.ALERTS_AUDIT_DIR) {
      await qr.screenshot({ path: path.join(process.env.ALERTS_AUDIT_DIR, 'phone-enrollment-qr-' + theme + '.png') });
      await auditScreenshot(page, 'phone-enrollment-dialog-' + theme);
    }
    // Exercise the existing theme control while the same QR remains mounted.
    await page.locator('#theme-toggle').evaluate(control => control.click());
    const nextTheme = theme === 'dark' ? 'light' : 'dark';
    await expect(qr).toHaveCSS('filter', nextTheme === 'dark' ? 'invert(1)' : 'none');
    await expect(qr).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
    await expect(dialog.getByRole('link', { name: 'Continue on RadioResolve', exact: true })).toHaveAttribute('href',
      'https://radioresolve.example.net/enroll?code=demo-id.demo-pairing-code-22');
    if (process.env.ALERTS_AUDIT_DIR) {
      await qr.screenshot({ path: path.join(process.env.ALERTS_AUDIT_DIR, `phone-enrollment-qr-${theme}-to-${nextTheme}.png`) });
    }
  });

  test(`topics and rule editor fit a 320px phone in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 700 });
    const { fixture } = await installAlerts(page, { theme });
    await page.goto('/app.html?view=topics');
    await expect(page.locator('.alerts-topic-card').first()).toBeVisible();
    await expectNoHorizontalOverflow(page, ['.alerts-workspace']);
    await page.goto('/app.html?view=alerts');
    await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
    await expect(dialog).toBeVisible();
    await auditScreenshot(page, 'rule-source-' + theme + '-phone');
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

test('topic management keeps selection, public navigation, built-in protection, and confirmed deletion', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  state.topics[0].rule_count = 2; state.topics[0].subscription_count = 18;
  await page.route('**/api/v1/admin/alerts/topics/fire', async route => {
    expect(route.request().method()).toBe('DELETE');
    state.writes.push({ p: '/api/v1/admin/alerts/topics/fire', method: 'DELETE', value: route.request().postDataJSON() });
    state.topics = state.topics.filter(topic => topic.id !== 'fire');
    state.revision++;
    return route.fulfill({ json: { data: state } });
  });
  await page.goto('/app.html?view=alerts');
  const rail = page.getByRole('navigation', { name: 'Topics', exact: true });
  const main = page.locator('.alerts-management-main');
  await expect(main).toContainText('Dispatch and tactical activity.');
  await expect(main).toContainText('18 subscriptions');
  await expect(main.getByRole('link', { name: 'View public topic: Fire activity', exact: true }))
    .toHaveAttribute('href', /view=topics.*topic=fire/);
  await selectManagedTopic(page, 'Administrator alerts');
  await expect(main).toContainText('Built-in');
  await expect(page.getByRole('button', { name: /Delete topic/ })).toHaveCount(0);
  await expect(main.getByRole('link', { name: /View public topic/ })).toHaveCount(0);
  await selectManagedTopic(page, 'Fire activity');
  await page.getByRole('button', { name: 'Edit topic', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Edit topic', exact: true })).toBeVisible();
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel', exact: true }).click();
  await page.getByRole('button', { name: 'Delete topic: Fire activity', exact: true }).click();
  const confirmation = page.getByRole('alertdialog', { name: 'Delete topic', exact: true });
  await expect(confirmation).toContainText('Existing subscriptions to this topic will stop.');
  await confirmation.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(state.writes).toHaveLength(0);
  await page.getByRole('button', { name: 'Delete topic: Fire activity', exact: true }).click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Delete topic', exact: true }).click();
  await expect(rail.getByRole('link', { name: /Fire activity/ })).toHaveCount(0);
  await expect(main.getByRole('heading', { name: 'Weather operations', exact: true })).toBeVisible();
  expect(state.writes[0].value.revision).toBe(1);
  expect(fixture.pageErrors).toEqual([]);
});

test('rule catalog keeps linked rule names, topic context, and confirmed deletion', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  state.rules.push({ id: 'dispatch', name: 'Dispatch activity', topic_id: 'fire', enabled: true, trigger: 'call',
    conditions: { op: 'all', items: [] }, timing: { mode: 'immediate' }, schedule: { mode: 'always' },
    cooldown_minutes: 15, repeat_key: 'rule', click_action: 'details', title_template: 'Dispatch is active', body_template: '{radio} transmitted.' });
  await page.route('**/api/v1/admin/alerts/rules/dispatch', async route => {
    expect(route.request().method()).toBe('DELETE');
    state.writes.push({ p: '/api/v1/admin/alerts/rules/dispatch', value: route.request().postDataJSON() });
    state.rules = []; state.revision++;
    return route.fulfill({ json: { data: state } });
  });
  await page.goto('/app.html?view=alerts');
  const rule = page.locator('.alerts-management-rules tbody tr[data-id="dispatch"]');
  await expect(rule).toContainText('Call activity');
  await expect(page.locator('.alerts-management-main').getByRole('heading', { name: 'Fire activity', exact: true })).toBeVisible();
  await expect(rule.getByRole('button', { name: 'Dispatch activity', exact: true })).toHaveClass(/ui-button-link/);
  await rule.getByRole('button', { name: 'Dispatch activity', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Edit alert rule', exact: true })).toBeVisible();
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel', exact: true }).click();
  await rule.getByRole('button', { name: 'Delete rule: Dispatch activity', exact: true }).click();
  await page.getByRole('alertdialog', { name: 'Delete alert rule', exact: true }).getByRole('button', { name: 'Delete rule', exact: true }).click();
  await expect(page.locator('.alerts-management-rules')).toContainText('No alert rules yet.');
  expect(state.writes[0].value.revision).toBe(1);
  expect(fixture.pageErrors).toEqual([]);
});

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 320], ['dark', 320]]) {
  test(`rule tests confirm all subscribers and prevent duplicate sends in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    const { state, fixture } = await installAlerts(page, { theme });
    state.revision = 7;
    state.holdRuleTest = true;
    const rule = { id: 'dispatch / west', name: 'Dispatch activity', topic_id: 'fire', enabled: true, trigger: 'call',
      conditions: { op: 'all', items: [] }, timing: { mode: 'immediate' }, schedule: { mode: 'always' },
      cooldown_minutes: 15, repeat_key: 'rule', title_template: 'Dispatch is active', body_template: '{radio} transmitted.' };
    state.rules = [rule, { ...rule, id: 'weather', name: 'Weather activity', topic_id: 'fire', enabled: false }];
    await page.goto('/app.html?view=alerts');
    const tests = page.locator('.alerts-management-rules [data-alert-rule-test]');
    const dispatch = page.getByRole('button', { name: 'Test alert rule: Dispatch activity', exact: true });
    const weather = page.getByRole('button', { name: 'Test alert rule: Weather activity', exact: true });
    await expect(tests).toHaveCount(2);
    await expect(dispatch).toHaveText('Test');
    await expect(weather).toBeEnabled();
    await expect(page.locator('.alerts-management-rules tbody tr[data-id="weather"]')).toContainText('Paused');
    await dispatch.click();
    const confirmation = page.getByRole('alertdialog', { name: 'Send test alert', exact: true });
    await expect(confirmation).toContainText('This will send an alert to all users subscribed to the “Fire activity” topic.');
    await expect(confirmation).toContainText('The title and text will start with “TEST: ”.');
    await expect(confirmation.getByRole('button', { name: 'Cancel', exact: true })).toBeFocused();
    await expect(dispatch).toBeDisabled();
    await expect(weather).toBeDisabled();
    await auditScreenshot(page, `rule-test-confirmation-${theme}-${width}`, false);
    await confirmation.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(dispatch).toBeEnabled();
    await expect(dispatch).toBeFocused();
    expect(state.writes).toHaveLength(0);
    await dispatch.click();
    await dispatch.evaluate(button => button.dispatchEvent(new MouseEvent('click', { bubbles: true })));
    await expect(confirmation).toHaveCount(1);
    await confirmation.getByRole('button', { name: 'Send test alert', exact: true }).click();
    await expect.poll(() => state.pending.length).toBe(1);
    await expect(dispatch).toBeDisabled();
    await expect(weather).toBeDisabled();
    await dispatch.evaluate(button => button.dispatchEvent(new MouseEvent('click', { bubbles: true })));
    expect(state.writes).toEqual([{ p: '/api/v1/admin/alerts/rules/dispatch%20%2F%20west/test', method: 'POST', value: { revision: 7 } }]);
    await state.pending.shift()();
    const feedback = page.locator('.alerts-workspace > [role="status"]');
    await expect(feedback).toHaveAttribute('aria-live', 'polite');
    await expect(feedback).toHaveText('Test alert queued for subscribed users.');
    await expect(dispatch).toBeEnabled();
    await expect(weather).toBeEnabled();
    state.holdRuleTest = false;
    state.ruleTestFailure = true;
    await weather.click();
    await expect(confirmation).toContainText('all users subscribed to the “Fire activity” topic');
    await confirmation.getByRole('button', { name: 'Send test alert', exact: true }).click();
    await expect(feedback).toHaveText('Test alert could not be sent. Try the Test button again.');
    await expect(weather).toBeEnabled();
    await expect(dispatch).toBeEnabled();
    await feedback.scrollIntoViewIfNeeded();
    await auditScreenshot(page, `rule-test-error-${theme}-${width}`, false);
    state.ruleTestFailure = false;
    await weather.click();
    await confirmation.getByRole('button', { name: 'Send test alert', exact: true }).click();
    await expect(feedback).toHaveText('Test alert queued for subscribed users.');
    expect(state.writes).toHaveLength(3);
    expect(state.writes.slice(1)).toEqual(Array(2).fill({ p: '/api/v1/admin/alerts/rules/weather/test', method: 'POST', value: { revision: 7 } }));
    expect(state.revision).toBe(7);
    expect(state.rules[1].enabled).toBe(false);
    await expectNoHorizontalOverflow(page, ['.alerts-workspace', '.read-only-modal']);
    await feedback.scrollIntoViewIfNeeded();
    await auditScreenshot(page, `rule-test-success-${theme}-${width}`, false);
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('managed topic drilldown keeps its selection after creating, editing, and deleting rules and editing the topic', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  const baseRule = { enabled: true, trigger: 'call', conditions: { op: 'all', items: [] },
    timing: { mode: 'immediate' }, schedule: { mode: 'always' }, cooldown_minutes: 15,
    repeat_key: 'rule', title_template: 'Activity', body_template: '{radio} transmitted.' };
  state.rules = [{ ...baseRule, id: 'dispatch', name: 'Dispatch activity', topic_id: 'fire' },
    { ...baseRule, id: 'weather', name: 'Storm activity', topic_id: 'paused' }];
  await page.goto('/app.html?view=alerts');
  const main = page.locator('.alerts-management-main');
  const rules = page.locator('.alerts-management-rules');
  await expect(rules.getByRole('button', { name: 'Dispatch activity', exact: true })).toBeVisible();
  await expect(rules.getByRole('button', { name: 'Storm activity', exact: true })).toHaveCount(0);
  await selectManagedTopic(page, 'Weather operations');
  await expect(rules.getByRole('button', { name: 'Storm activity', exact: true })).toBeVisible();
  await expect(rules.getByRole('button', { name: 'Dispatch activity', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
  let dialog = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
  await expect(dialog.getByRole('combobox', { name: 'Publish to topic', exact: true })).toHaveValue('paused');
  await dialog.getByRole('textbox', { name: 'Rule name', exact: true }).fill('Weather warning');
  await dialog.getByRole('button', { name: 'Create rule', exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(state.writes[0].value.rule.topic_id).toBe('paused');
  await expect(main.getByRole('heading', { name: 'Weather operations', exact: true })).toBeVisible();
  await rules.getByRole('button', { name: 'Weather warning', exact: true }).click();
  dialog = page.getByRole('dialog', { name: 'Edit alert rule', exact: true });
  await dialog.getByRole('textbox', { name: 'Rule name', exact: true }).fill('Weather advisory');
  await dialog.getByRole('button', { name: 'Save rule', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(main.getByRole('heading', { name: 'Weather operations', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Edit topic', exact: true }).click();
  dialog = page.getByRole('dialog', { name: 'Edit topic', exact: true });
  await dialog.getByRole('textbox', { name: 'Topic name', exact: true }).fill('Weather coverage');
  await dialog.getByRole('button', { name: 'Save topic', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(main.getByRole('heading', { name: 'Weather coverage', exact: true })).toBeVisible();
  const created = state.rules.find(rule => rule.name === 'Weather advisory');
  await page.route('**/api/v1/admin/alerts/rules/' + encodeURIComponent(created.id), async route => {
    state.writes.push({ p: new URL(route.request().url()).pathname, method: route.request().method(), value: route.request().postDataJSON() });
    state.rules = state.rules.filter(rule => rule.id !== created.id);
    state.revision++;
    return route.fulfill({ json: { data: state } });
  });
  const remove = rules.getByRole('button', { name: 'Delete rule: Weather advisory', exact: true });
  await revealPrimaryAction(page, remove);
  await remove.click();
  await page.getByRole('alertdialog', { name: 'Delete alert rule', exact: true }).getByRole('button', { name: 'Delete rule', exact: true }).click();
  await expect(rules.getByRole('button', { name: 'Weather advisory', exact: true })).toHaveCount(0);
  await expect(main.getByRole('heading', { name: 'Weather coverage', exact: true })).toBeVisible();
  await expect(rules.getByRole('button', { name: 'Storm activity', exact: true })).toBeVisible();
  expect(fixture.pageErrors).toEqual([]);
});

test('managed rule search resolves aliases and sources across the scoped catalog before paging and combines with state', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  const baseRule = { topic_id: 'fire', enabled: true, trigger: 'call', conditions: { op: 'all', items: [] },
    timing: { mode: 'immediate' }, schedule: { mode: 'always' }, cooldown_minutes: 15,
    repeat_key: 'rule', title_template: 'Activity', body_template: '{radio} transmitted.' };
  state.rules = Array.from({ length: 36 }, (_, index) => ({ ...baseRule, id: 'rule-' + index, name: 'Activity rule ' + (index + 1) }));
  state.rules[0] = { ...state.rules[0], name: 'Dispatch activity for the regional emergency response coordination channel',
    system_key: systemKey, conditions: { op: 'all', items: [{ field: 'alias_id', operator: 'is', value: '101' }] } };
  state.rules[34] = { ...baseRule, id: 'paused-rule', name: 'Weather standby', enabled: false, channel_id: channelId };
  state.rules[35] = { ...baseRule, id: 'remote', name: 'Remote response',
    conditions: { op: 'all', items: [{ op: 'any', items: [{ field: 'alias_id', operator: 'is', value: '101' }] }] } };
  state.rules.push({ ...state.rules[35], id: 'other-topic', name: 'Other topic response', topic_id: 'paused' });
  await page.route('**/api/v1/aliases/101', route => route.fulfill({ json: { data: {
    alias_id: 101, name: 'Fire Dispatch', alias_list_name: 'County Public Safety' } } }));
  await page.goto('/app.html?view=alerts');
  const rules = page.locator('.alerts-management-rules');
  const search = page.getByRole('searchbox', { name: 'Find an alert rule', exact: true });
  const stateFilter = page.getByRole('combobox', { name: 'State', exact: true });
  await expect(rules.locator('tbody tr[data-id]')).toHaveCount(30);
  await expect(rules.locator('tbody tr[data-id="rule-0"]')).toContainText('Fire Dispatch');
  await auditScreenshot(page, 'manage-alerts-light-desktop', false);
  await search.fill('Remote response');
  await expect(rules.locator('tbody tr[data-id]')).toHaveCount(1);
  await expect(rules.getByRole('button', { name: 'Remote response', exact: true })).toBeVisible();
  await search.fill('Fire Dispatch');
  await expect(rules.getByRole('button', { name: 'Remote response', exact: true })).toBeVisible();
  await expect(rules.getByRole('button', { name: 'Other topic response', exact: true })).toHaveCount(0);
  await stateFilter.selectOption({ label: 'Paused' });
  await expect(rules).toContainText('No matching alert rules. Try another search or state.');
  await search.fill('County Dispatch');
  await expect(rules.getByRole('button', { name: 'Weather standby', exact: true })).toBeVisible();
  await stateFilter.selectOption({ label: 'Enabled' });
  await expect(rules).toContainText('No matching alert rules. Try another search or state.');
  await search.clear();
  await expect(rules.getByRole('button', { name: 'Weather standby', exact: true })).toHaveCount(0);
  await expect(rules.locator('tbody tr[data-id]')).toHaveCount(30);
  expect(fixture.pageErrors).toEqual([]);
});

test('an empty managed catalog creates and selects its first topic before adding a rule', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  state.topics = [];
  await page.goto('/app.html?view=alerts');
  await page.getByRole('button', { name: 'New topic', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Create topic', exact: true });
  await dialog.getByRole('textbox', { name: 'Topic name', exact: true }).fill('Regional operations');
  await dialog.getByRole('textbox', { name: 'Description', exact: true }).fill('Regional dispatch activity.');
  await dialog.getByRole('button', { name: 'Create topic', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.locator('.alerts-management-main').getByRole('heading', { name: 'Regional operations', exact: true })).toBeVisible();
  const createdTopic = state.writes[0].value.topic;
  await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
  const ruleDialog = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
  await expect(ruleDialog.getByRole('combobox', { name: 'Publish to topic', exact: true })).toHaveValue(createdTopic.id);
  await ruleDialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(fixture.pageErrors).toEqual([]);
});

for (const theme of ['light', 'dark']) {
  test(`managed alerts use the topic selector without overflow on a 320px phone in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 700 });
    const { state, fixture } = await installAlerts(page, { theme });
    state.rules = [{ id: 'weather-mobile', name: 'Regional weather dispatch activity', topic_id: 'paused', enabled: false,
      trigger: 'call', system_key: systemKey, channel_id: channelId, conditions: { op: 'all', items: [] },
      timing: { mode: 'immediate' }, schedule: { mode: 'always' } }];
    await page.goto('/app.html?view=alerts');
    const selector = page.getByRole('combobox', { name: 'Topic', exact: true });
    await expect(selector).toBeVisible();
    await expect(selector).toHaveValue('fire');
    await expect(page.getByRole('navigation', { name: 'Topics', exact: true })).toBeHidden();
    await selector.selectOption('paused');
    await expect(page.locator('.alerts-management-main').getByRole('heading', { name: 'Weather operations', exact: true })).toBeVisible();
    await expectNoHorizontalOverflow(page, ['.alerts-workspace', '.alerts-management-main']);
    await auditScreenshot(page, 'manage-alerts-' + theme + '-phone');
    await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
    await expect(dialog.getByRole('combobox', { name: 'Publish to topic', exact: true })).toHaveValue('paused');
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(selector).toHaveValue('paused');
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('topic search reaches the complete bounded catalog and keeps later edit actions reachable', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  state.topics = Array.from({ length: 65 }, (_, index) => ({ id: 'topic-' + index, name: 'Topic ' + (index + 1),
    description: 'Activity alerts.', visible: true, enabled: true, rule_count: 0, subscription_count: 0 }));
  await page.goto('/app.html?view=alerts');
  const rail = page.getByRole('navigation', { name: 'Topics', exact: true });
  await page.getByRole('searchbox', { name: 'Find a topic', exact: true }).fill('Topic 65');
  await expect(rail.getByRole('link')).toHaveCount(1);
  await selectManagedTopic(page, 'Topic 65');
  await page.getByRole('button', { name: 'Edit topic', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Edit topic', exact: true });
  await expect(dialog.getByRole('textbox', { name: 'Topic name', exact: true })).toHaveValue('Topic 65');
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await page.getByRole('searchbox', { name: 'Find a topic', exact: true }).fill('No such topic');
  await expect(rail.getByRole('link')).toHaveCount(0);
  await expect(page.locator('.alerts-management-main').getByRole('heading', { name: 'Topic 65', exact: true })).toBeVisible();
  await page.getByRole('searchbox', { name: 'Find a topic', exact: true }).clear();
  await expect(rail.getByRole('link')).toHaveCount(65);
  expect(fixture.pageErrors).toEqual([]);
});

test('rule action toggle previews the activity destination and saves an explicit choice', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  await page.goto('/app.html?view=alerts');
  await page.getByRole('button', { name: 'Create alert rule', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Create alert rule', exact: true });
  await dialog.getByRole('textbox', { name: 'Rule name', exact: true }).fill('Dispatch popup action');
  await dialog.getByRole('button', { name: 'Notification', exact: true }).click();
  const preview = dialog.getByRole('complementary', { name: 'Notification preview', exact: true });
  const toggle = dialog.getByRole('checkbox', { name: 'Show action button', exact: true });
  await expect(toggle).not.toBeChecked();
  await expect(preview.getByRole('button')).toHaveCount(0);
  await toggle.focus(); await toggle.press('Space');
  await expect(toggle).toBeChecked();
  await expect(preview.getByRole('button', { name: 'View talkgroup', exact: true })).toBeDisabled();
  await dialog.getByRole('textbox', { name: 'Title', exact: true }).fill('{radio} called {talkgroup}');
  await expect(preview).toContainText('Engine 12 called Fire Tactical');
  await dialog.getByRole('button', { name: 'When', exact: true }).click();
  await dialog.getByRole('combobox', { name: 'Activity', exact: true }).selectOption('health');
  await dialog.getByRole('button', { name: 'Notification', exact: true }).click();
  await expect(preview.getByRole('button', { name: 'View live channels', exact: true })).toBeDisabled();
  await dialog.getByRole('button', { name: 'Create rule', exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(state.writes[0].value.rule.action_button_enabled).toBe(true);
  expect(state.writes[0].value.rule.trigger).toBe('health');
  await page.locator('.alerts-management-rules').getByRole('button', { name: 'Dispatch popup action', exact: true }).click();
  const edit = page.getByRole('dialog', { name: 'Edit alert rule', exact: true });
  await edit.getByRole('button', { name: 'Notification', exact: true }).click();
  const editToggle = edit.getByRole('checkbox', { name: 'Show action button', exact: true });
  await editToggle.focus(); await editToggle.press('Space');
  await expect(editToggle).not.toBeChecked();
  await expect(edit.getByRole('complementary', { name: 'Notification preview', exact: true }).getByRole('button')).toHaveCount(0);
  await edit.getByRole('button', { name: 'Save rule', exact: true }).click();
  await expect(edit).toBeHidden();
  expect(state.writes[1].value.rule.action_button_enabled).toBe(false);
  expect(fixture.pageErrors).toEqual([]);
});

test('built-in topic saves its independent action flag and confirms new public visibility', async ({ page }) => {
  const { state, fixture } = await installAlerts(page);
  state.builtin_action_button_enabled = false;
  await page.goto('/app.html?view=alerts');
  await selectManagedTopic(page, 'Administrator alerts');
  await page.getByRole('button', { name: 'Edit topic', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Edit topic', exact: true });
  await expect(dialog.getByRole('textbox', { name: 'Topic name', exact: true })).toBeDisabled();
  const actionToggle = dialog.getByRole('checkbox', { name: 'Show action button', exact: true });
  await actionToggle.focus(); await actionToggle.press('Space');
  const publicToggle = dialog.getByRole('checkbox', { name: 'Available to listeners', exact: true });
  await publicToggle.focus(); await publicToggle.press('Space');
  await dialog.getByRole('button', { name: 'Save topic', exact: true }).click();
  const confirmation = page.getByRole('alertdialog', { name: 'Make administrator alerts public', exact: true });
  await confirmation.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(state.writes).toHaveLength(0);
  await expect(dialog.getByRole('checkbox', { name: 'Show action button', exact: true })).toBeChecked();
  await dialog.getByRole('button', { name: 'Save topic', exact: true }).click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Make available', exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(state.writes[0].value.builtin_action_button_enabled).toBe(true);
  expect(state.writes[0].value.topic.visible).toBe(true);
  expect(state.writes[0].value.topic.name).toBe('Administrator alerts');
  await selectManagedTopic(page, 'Fire activity');
  await page.getByRole('button', { name: 'Edit topic', exact: true }).click();
  await expect(page.getByRole('dialog').getByRole('checkbox', { name: 'Show action button', exact: true })).toHaveCount(0);
  await page.getByRole('dialog').getByRole('button', { name: 'Save topic', exact: true }).click();
  expect(state.writes[1].value).not.toHaveProperty('builtin_action_button_enabled');
  expect(fixture.pageErrors).toEqual([]);
});

test('public alert popup exposes only validated public entity actions and never administrator destinations', async ({ page }) => {
  const { fixture } = await installAlerts(page, { guest: true });
  const scenarios = [
    { name: 'Radio action', action: { kind: 'radio', entity_ref: { kind: 'radio', radio_system_key: 'p25:00001:001', identity_key: 'v1-r-00001-001-12' } }, label: 'View radio', destination: 'radio' },
    { name: 'Talkgroup action', action: { kind: 'talkgroup', entity_ref: { kind: 'talkgroup', radio_system_key: 'p25:00001:001', identity_key: 'v1-g-00001-001-1201' } }, label: 'View talkgroup', destination: 'group-identity' },
    { name: 'Patch group action', action: { kind: 'patch_group', entity_ref: { kind: 'patch_group', radio_system_key: 'p25:00001:001', identity_key: 'v1-p-00001-001-1202' } }, label: 'View patch group', destination: 'group-identity' },
    { name: 'Channel action', action: { kind: 'channel', entity_ref: { kind: 'channel', key: channelId } }, label: 'View channel', destination: 'channel' },
    { name: 'System action', action: { kind: 'radio_system', entity_ref: { kind: 'radio_system', key: systemKey } }, label: 'View radio system', destination: 'radio-system' },
    { name: 'Live action', action: { kind: 'live', entity_ref: null }, label: 'View live channels', destination: 'live' },
    { name: 'Disabled action', action: null, click_action: 'listen' },
    { name: 'Absent action' },
    { name: 'Administrator action', action: { kind: 'admin', entity_ref: { kind: 'admin', key: 'settings' } } },
    { name: 'Injected channel action', action: { kind: 'channel', entity_ref: { kind: 'channel', key: '/?view=admin&tab=push-setup' } } }
  ];
  await page.route('**/api/v1/alerts/history?**', route => route.fulfill({ json: { data: { items: scenarios.map((item, index) => ({
    id: 'event-' + index, topic_id: 'fire', title: item.name, body: 'Synthetic alert details.', created_at_ms: 1770000000000, ...item
  })), total: scenarios.length } } }));
  await page.goto('/app.html?view=topics');
  for (const scenario of scenarios) {
    await page.locator('.alerts-timeline').getByRole('button', { name: scenario.name, exact: true }).click();
    const dialog = page.getByRole('dialog', { name: scenario.name, exact: true });
    if (scenario.label) {
      const action = dialog.getByRole('link', { name: scenario.label, exact: true });
      await expect(action).toHaveAttribute('href', new RegExp('^/\\?view=' + scenario.destination + '(?:&|$)'));
      await expect(action).toHaveClass(/ui-button-primary/);
    } else await expect(dialog.getByRole('link')).toHaveCount(0);
    await expect(dialog.locator('a[href*="view=admin"]')).toHaveCount(0);
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
  }
  expect(fixture.pageErrors).toEqual([]);
});

test('alert details open exact local related calls without notification credentials', async ({ page }) => {
  const { fixture } = await installAlerts(page);
  const requests = [];
  await page.route('**/api/v1/alerts/history?**', route => route.fulfill({ json: { data: { items: [
    { id: 'alert-exact-call', topic_id: 'fire', title: 'Dispatch tones', body: 'Engine 4 dispatched.', created_at_ms: 1770000000000 }
  ], total: 1 } } }));
  await page.route('**/api/v1/alerts/history/alert-exact-call/related', route => {
    requests.push({ url: route.request().url(), authorization: route.request().headers().authorization });
    if (requests.length === 1) return route.fulfill({ status: 503, json: { error: { message: 'Temporarily unavailable.' } } });
    return route.fulfill({ json: { data: { activity_rows: [], recordings: requests.length > 2 ? [
      { id: 81, start_ms: 1770000000000, group_alias: 'Dispatch', talkgroup_id: 1201, channel_name: 'North Site' }
    ] : [], alerts: [] } } });
  });
  await page.goto('/app.html?view=topics');
  await page.locator('.alerts-timeline').getByRole('button', { name: 'Dispatch tones', exact: true }).click();
  const detail = page.getByRole('dialog', { name: 'Dispatch tones', exact: true });
  await detail.getByRole('button', { name: 'Related activity and recordings', exact: true }).click();
  await expect(detail).toHaveCount(0);
  const related = page.getByRole('dialog', { name: 'Related call details', exact: true });
  await expect(related).toBeVisible();
  await related.getByRole('button', { name: 'Try again', exact: true }).click();
  await expect(related).toContainText('No related recording is available.');
  await related.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(related.getByRole('button', { name: 'Play', exact: true })).toBeVisible();
  await expect.poll(() => requests.length).toBe(3);
  expect(new URL(requests[0].url).pathname).toBe('/api/v1/alerts/history/alert-exact-call/related');
  expect(requests.every(request => request.authorization === undefined)).toBe(true);
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: 'Related call details', exact: true })).toHaveCount(0);
  expect(fixture.pageErrors).toEqual([]);
});
