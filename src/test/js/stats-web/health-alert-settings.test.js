'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const applicationPath = process.argv[2];
assert.ok(applicationPath, 'The app.js path is required.');
const application = fs.readFileSync(applicationPath, 'utf8');
const stylesheet = fs.readFileSync(path.join(path.dirname(applicationPath), 'app.css'), 'utf8');

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
  receiverHealthAlertIds: ['receiver-iq-drop', 'gc-pause'],
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
    { code: 'gc-pause', severity: 'warning' }
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
  health_alerts: { disabled_codes: ['receiver-iq-drop', 'gc-pause'] }
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
  ['gc-pause', { checked: false }]
]);
assert.deepEqual(plain(context.disabledCodesForSave({
  health_alerts: { disabled_codes: ['future-disabled-alert', 'receiver-iq-drop'] }
}, controls)), ['future-disabled-alert', 'gc-pause'],
'Saving known switches must preserve an unknown disabled code while replacing known choices.');

const renderAlerts = functionSource('async function renderAdminAlerts()');
assert.match(renderAlerts, /receiverHealthAlertGroups\.map/);
assert.match(renderAlerts, /receiverHealthDisabledCodesForSave\(preferences, controls\)/);
assert.match(renderAlerts, /Save Alert Settings/);
assert.match(renderAlerts, /does not stop monitoring/);

const renderHealth = functionSource('function renderReceiverHealthPage(host, snapshot, stale, lastError)');
assert.match(renderHealth, /receiverHealthIncidentList\(snapshot\.active\)/,
  'The Health page must keep the canonical active incident list.');
assert.match(renderHealth, /receiverHealthAccountSettingNotice\(snapshot\)/);

const updateIndicator = functionSource('  updateIndicator() {');
assert.match(updateIndicator, /if \(this\.stale\)/,
  'Stale health must take precedence over personal alert switches.');
assert.match(updateIndicator, /if \(accountAlerts\.critical_count > 0\)/,
  'Stale labels must honor the account alert switches while stale itself remains visible.');
assert.doesNotMatch(updateIndicator, /if \(summary\?\.severity === 'critical'\)/);
assert.match(updateIndicator, /className = 'neutral'/);
assert.match(updateIndicator, /alert\$\{accountAlerts\.disabled_count === 1 \? '' : 's'\} turned off/);
assert.ok(updateIndicator.indexOf('accountAlerts.active_count > 0') <
  updateIndicator.indexOf("className = 'healthy'"),
'The all-disabled neutral state must be chosen before the healthy fallback.');

assert.match(stylesheet, /\.receiver-health-indicator\.receiver-health-neutral\s*\{/);
assert.match(stylesheet, /\.receiver-health-account-setting\s*\{/);
assert.match(stylesheet, /\.settings-card-grid\s*\{[^}]*align-items: stretch/s);
assert.match(stylesheet, /\.settings-card\s*\{[^}]*height: 100%/s,
  'Alert group cards must align to the height of their grid row.');
