'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { readStylesheetSource } = require('./stylesheet-source');
const vm = require('node:vm');

const applicationPath = process.argv[2];
assert.ok(applicationPath, 'The app.js path is required.');
const application = fs.readFileSync(applicationPath, 'utf8');
const stylesheet = readStylesheetSource(path.join(path.dirname(applicationPath), 'app.css'));

function functionSource(signature) {
  const start = application.indexOf(signature);
  if (start < 0) throw new Error(`Missing ${signature}`);
  const openingBrace = signature.trimEnd().endsWith('{') ?
    start + signature.lastIndexOf('{') : application.indexOf('{', start + signature.length);
  let depth = 0;
  for (let index = openingBrace; index < application.length; index += 1) {
    if (application[index] === '{') depth += 1;
    else if (application[index] === '}' && --depth === 0) return application.slice(start, index + 1);
  }
  throw new Error(`Unterminated ${signature}`);
}

const context = {
  receiverHealthAlertIds: ['receiver-iq-drop', 'usb-delivery-rate-low'],
  isReceiverHealthAlertEnabled: (preferences, incidentCode) =>
    !preferences?.health_alerts?.disabled_codes?.includes(incidentCode)
};
vm.createContext(context);
vm.runInContext(`
  ${functionSource('function receiverHealthSeverity(value)')}
  ${functionSource('function receiverHealthCount(value, fallback = 0)')}
  ${functionSource('function receiverHealthAccountAlertSummary(snapshot, preferences)')}
  ${functionSource('function receiverHealthDisabledCodesForSave(preferences, controls)')}
  globalThis.summarize = receiverHealthAccountAlertSummary;
  globalThis.disabledCodesForSave = receiverHealthDisabledCodesForSave;
`, context);

function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

const snapshot = {
  summary: { severity: 'critical', active_count: 2, critical_count: 1, warning_count: 1 },
  active: [
    { code: 'receiver-iq-drop', severity: 'critical' },
    { code: 'usb-delivery-rate-low', severity: 'warning' }
  ]
};

assert.deepEqual(plain(context.summarize(snapshot, { health_alerts: { disabled_codes: [] } })), {
  active_count: 2,
  enabled_count: 2,
  disabled_count: 0,
  critical_count: 1,
  warning_count: 1
});
assert.deepEqual(plain(context.summarize(snapshot, {
  health_alerts: { disabled_codes: ['receiver-iq-drop'] }
})), {
  active_count: 2,
  enabled_count: 1,
  disabled_count: 1,
  critical_count: 0,
  warning_count: 1
});
assert.deepEqual(plain(context.summarize(snapshot, {
  health_alerts: { disabled_codes: ['receiver-iq-drop', 'usb-delivery-rate-low'] }
})), {
  active_count: 2,
  enabled_count: 0,
  disabled_count: 2,
  critical_count: 0,
  warning_count: 0
});

const futureAlert = {
  summary: { severity: 'critical', active_count: 1, critical_count: 1, warning_count: 0 },
  active: [{ code: 'future-health-alert', severity: 'critical' }]
};
assert.deepEqual(plain(context.summarize(futureAlert, {
  health_alerts: { disabled_codes: ['receiver-iq-drop'] }
})), {
  active_count: 1,
  enabled_count: 1,
  disabled_count: 0,
  critical_count: 1,
  warning_count: 0
}, 'A newly introduced alert code must default to on.');

const controls = new Map([
  ['receiver-iq-drop', { checked: true }],
  ['usb-delivery-rate-low', { checked: false }]
]);
assert.deepEqual(plain(context.disabledCodesForSave({
  health_alerts: { disabled_codes: ['future-disabled-alert', 'receiver-iq-drop'] }
}, controls)), ['future-disabled-alert', 'usb-delivery-rate-low'],
'Saving known switches must preserve an unknown disabled code while replacing known choices.');

const openAlerts = functionSource('function openStatusIconSettings(returnFocusSelector = null)');
assert.match(openAlerts, /receiverHealthAlertGroups\.map/);
assert.match(openAlerts, /receiverHealthDisabledCodesForSave\(preferences, controls\)/);
assert.match(openAlerts, /openReadOnlyModal\('Status icon issues'/);
assert.match(openAlerts, /className: 'health-alert-settings-modal'/);
assert.match(openAlerts, /createFormWorkflow\(/);
assert.match(openAlerts, /savePreferenceForm\(/);
assert.match(functionSource('async function savePreferenceForm('), /if \(modal\.close\(\)\) void render\(\)/);
assert.match(openAlerts, /Save choices/);
assert.doesNotMatch(openAlerts, /content\.append/,
  'Status icon issues must open in the shared modal instead of rendering as a page.');

const renderHealth = functionSource('function renderReceiverHealthPage(host, snapshot, stale, lastError)');
assert.match(renderHealth, /receiverHealthOverview\(snapshot, stale, lastError\)/);
const healthOverview = functionSource('function receiverHealthOverview(snapshot, stale, lastError)');
assert.match(healthOverview, /receiverHealthIncidentList\(snapshot\.active\)/,
  'The Receiver status page must keep the canonical current issue list.');
assert.match(healthOverview, /receiverHealthAccountSettingNotice\(snapshot\)/);

const healthAccountSettingNotice = functionSource('function receiverHealthAccountSettingNotice(snapshot)');
assert.match(healthAccountSettingNotice, /node\('button', 'ui-button ui-button-link'/,
  'The Receiver status control must use the shared settings action treatment.');
assert.match(healthAccountSettingNotice,
  /openStatusIconSettings\('#receiver-health-alert-settings'\)/,
  'The Receiver status control must open the shared status-icon settings modal.');
assert.doesNotMatch(healthAccountSettingNotice, /href\(/,
  'The Receiver status control must not navigate away from the status page.');

const updateIndicator = functionSource('  updateIndicator() {');
assert.match(updateIndicator, /if \(this\.stale\)/,
  'Stale health must take precedence over personal alert switches.');
assert.match(updateIndicator, /if \(accountAlerts\.critical_count > 0\)/,
  'Stale labels must honor the account alert switches while stale itself remains visible.');
assert.doesNotMatch(updateIndicator, /if \(summary\?\.severity === 'critical'\)/);
assert.match(updateIndicator, /className = 'neutral'/);
assert.match(updateIndicator, /hidden from icon/);
assert.ok(updateIndicator.indexOf('accountAlerts.active_count > 0') <
  updateIndicator.indexOf("className = 'healthy'"),
'The all-disabled neutral state must be chosen before the healthy fallback.');

assert.match(stylesheet, /\.receiver-health-indicator\.receiver-health-neutral\s*,/);
assert.match(stylesheet, /\.receiver-health-account-setting\s*\{/);
assert.doesNotMatch(stylesheet, /\.receiver-health-account-setting \.ui-button\s*\{/,
  'Receiver status must use the shared button appearance without feature overrides.');
assert.match(stylesheet, /\.read-only-modal\.health-alert-settings-modal\s*\{/);
assert.match(stylesheet, /\.settings-card-grid\s*\{[^}]*align-items: stretch/s);
assert.match(stylesheet, /\.settings-card\s*\{[^}]*height: 100%/s,
  'Alert group cards must align to the height of their grid row.');


async function checkServerRelativeFreshness() {
  const serverNow = Date.parse('Thu, 22 Oct 2026 12:00:00 GMT');
  let monotonic = 0;
  let clientOffset = 0;
  let serverDate = new Date(serverNow).toUTCString();
  let payload = { generated_at_ms: serverNow - 1_000 };
  let bodyDelay = 0;
  const healthContext = vm.createContext({ AbortController, Headers,
    Date: { parse: Date.parse, now: () => serverNow + clientOffset },
    performance: { now: () => monotonic }, document: { hidden: false },
    RECEIVER_HEALTH_STALE_MILLISECONDS: 15_000,
    normalizeReceiverHealthSnapshot: value => value,
    api: async (_path, _parameters, options) => {
      options.onResponse({ headers: new Headers(serverDate === null ? {} : { Date: serverDate }) });
      monotonic += bodyDelay;
      return payload;
    }
  });
  vm.runInContext(`globalThis.refreshHealth = ({ ${functionSource('  async refresh() {')} }).refresh;`, healthContext);
  const health = { requestController: null, snapshot: null, stale: false, lastError: '',
    desktopEnabled: () => true, updateIndicator() {}, updatePage() {} };
  for (clientOffset of [-300_000, 300_000]) {
    monotonic += 100;
    await healthContext.refreshHealth.call(health);
    assert.equal(health.stale, false, 'Unsynchronized browser time must not make fresh receiver status stale.');
  }
  serverDate = new Date(serverNow + 20_000).toUTCString();
  await healthContext.refreshHealth.call(health);
  assert.equal(health.stale, true, 'A retained old server payload must become stale as the server clock advances.');
  assert.match(health.lastError, /has not updated recently/);
  payload = { generated_at_ms: serverNow + 20_000 };
  await healthContext.refreshHealth.call(health);
  assert.equal(health.stale, false, 'A genuinely new server snapshot restores current status.');
  for (const rounding of [999, 1000]) {
    payload = { generated_at_ms: serverNow + 20_000 + rounding };
    await healthContext.refreshHealth.call(health);
    assert.equal(health.stale, false, 'HTTP Date rounding may put a valid sample up to one second ahead.');
  }
  const previous = health.snapshot;
  payload = { generated_at_ms: serverNow + 21_001 };
  await healthContext.refreshHealth.call(health);
  assert.equal(health.stale, true, 'A genuinely future-dated sample cannot be declared current after a clock step.');
  assert.match(health.lastError, /unavailable/);
  assert.equal(health.snapshot, previous, 'An untrusted future timestamp must not replace the last complete status.');
  payload = previous;
  for (serverDate of [null, 'not a date']) {
    await healthContext.refreshHealth.call(health);
    assert.equal(health.stale, true);
    assert.match(health.lastError, /unavailable/);
    assert.equal(health.snapshot, previous, 'Missing clock metadata must retain the last complete status.');
  }
  serverDate = new Date(serverNow + 20_000).toUTCString();
  payload = { generated_at_ms: serverNow + 5_500 };
  bodyDelay = 600;
  await healthContext.refreshHealth.call(health);
  assert.equal(health.stale, true, 'Body-reading time must be measured with the browser monotonic clock.');
}

checkServerRelativeFreshness().catch(error => { console.error(error); process.exitCode = 1; });
