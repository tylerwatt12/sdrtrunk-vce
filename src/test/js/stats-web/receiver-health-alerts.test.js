'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const repository = path.resolve(__dirname, '../../../..');
const core = path.resolve(process.argv[2] || path.join(repository, 'stats-web/assets/core'));

async function loadModule(name) {
  const source = fs.readFileSync(path.join(core, `${name}.js`), 'utf8');
  return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
}

function copy(value) {
  return JSON.parse(JSON.stringify(value));
}

test('health alert preferences are versioned, bounded, and use stable unique codes', async () => {
  const schema = await loadModule('preference-schema');
  const defaults = schema.validate(copy(schema.defaults));
  assert.equal(defaults.version, 4);
  assert.deepEqual(defaults.health_alerts, { disabled_codes: [] });

  const maximum = Array.from({ length: 128 }, (_unused, index) => `alert-${index}`);
  assert.deepEqual(schema.validate({
    ...defaults,
    health_alerts: { disabled_codes: maximum }
  }).health_alerts.disabled_codes, maximum);
  assert.throws(() => schema.validate({
    ...defaults,
    health_alerts: { disabled_codes: [...maximum, 'one-too-many'] }
  }), /health_alerts\.disabled_codes/);
  assert.throws(() => schema.validate({
    ...defaults,
    health_alerts: { disabled_codes: ['receiver-iq-drop', 'receiver-iq-drop'] }
  }), /duplicate alert codes/);
  assert.throws(() => schema.validate({
    ...defaults,
    health_alerts: { disabled_codes: ['Receiver IQ drop'] }
  }), /health_alerts\.disabled_codes/);
  assert.throws(() => schema.validate({ ...defaults, version: 3 }), /version is unsupported/);
  const { health_alerts: _removed, ...withoutHealthAlerts } = defaults;
  assert.throws(() => schema.validate(withoutHealthAlerts), /unknown or missing settings/);
});

test('the friendly catalog covers every incident code emitted by ReceiverHealthService', async () => {
  const alerts = await loadModule('receiver-health-alerts');
  const stableId = /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/;
  assert.ok(Object.isFrozen(alerts.receiverHealthAlertGroups));
  assert.ok(Object.isFrozen(alerts.receiverHealthAlertIds));

  const catalogIds = [];
  for (const group of alerts.receiverHealthAlertGroups) {
    assert.match(group.id, stableId);
    assert.ok(group.name.length > 0);
    assert.ok(group.description.length > 0);
    assert.ok(Object.isFrozen(group));
    assert.ok(Object.isFrozen(group.alerts));
    for (const alert of group.alerts) {
      assert.match(alert.id, stableId);
      assert.ok(alert.name.length > 0);
      assert.ok(alert.description.length > 0);
      assert.ok(Object.isFrozen(alert));
      catalogIds.push(alert.id);
    }
  }
  assert.equal(new Set(catalogIds).size, catalogIds.length, 'Alert codes must be unique');
  assert.deepEqual(alerts.receiverHealthAlertIds, catalogIds);

  const serviceSource = fs.readFileSync(path.join(repository,
    'src/main/java/io/github/dsheirer/stats/health/ReceiverHealthService.java'), 'utf8');
  const directIds = [...serviceSource.matchAll(/mIncidents\.observe\(\s*"([^"]+)"/g)]
    .map((match) => match[1]);
  const outputIds = [...serviceSource.matchAll(/observeOutputDrop\(\s*now\s*,\s*"([^"]+)"/g)]
    .map((match) => match[1]);
  const serviceIds = [...new Set([...directIds, ...outputIds])].sort();
  assert.deepEqual([...catalogIds].sort(), serviceIds,
    'Every receiver-health incident must have exactly one friendly alert setting');
});

test('only explicitly disabled codes are suppressed', async () => {
  const alerts = await loadModule('receiver-health-alerts');
  const preferences = { health_alerts: { disabled_codes: ['receiver-iq-drop'] } };
  assert.equal(alerts.isReceiverHealthAlertEnabled(preferences, 'receiver-iq-drop'), false);
  assert.equal(alerts.isReceiverHealthAlertEnabled(preferences, 'host-cpu-pressure'), true);
  assert.equal(alerts.isReceiverHealthAlertEnabled(preferences, 'future-incident'), true,
    'New incident codes must remain enabled until a user explicitly disables them');
  assert.equal(alerts.isReceiverHealthAlertEnabled({}, 'receiver-iq-drop'), true);
});
