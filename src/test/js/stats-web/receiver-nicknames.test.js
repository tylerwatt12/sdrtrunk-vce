'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { pathToFileURL } = require('node:url');

const app = path.resolve(process.argv[2] || path.resolve(__dirname, '../../../../stats-web/assets/app.js'));
const source = fs.readFileSync(app, 'utf8');
const binding = (name) => vm.runInNewContext(`(${source.match(new RegExp(`function ${name}\\([^)]*\\) \\{[\\s\\S]*?\\n\\}`))[0]})`);
const nickname = binding('tunerNicknameValue');
const receiverLabel = binding('liveReceiverLabel');

assert.equal(nickname('  North antenna  '), 'North antenna');
assert.equal(nickname('   '), '');
assert.equal(nickname('📻'.repeat(80)), '📻'.repeat(80));
assert.throws(() => nickname('📻'.repeat(81)), /80 characters/);
assert.throws(() => nickname('North\u0000antenna'), /control characters/);
assert.equal(receiverLabel({ receiver_id: 'receiver-a', receiver_nickname: 'North antenna' }), 'North antenna');
assert.equal(receiverLabel({ receiver_id: 'receiver-a' }), 'receiver-a');
assert.equal(receiverLabel({}), '—');
assert.equal(receiverLabel({ receiver_id: 'receiver-a', receiver_nickname: '<img onerror=alert(1)>' }),
  '<img onerror=alert(1)>', 'The label remains text for the shared table renderer.');

async function check() {
  const core = path.join(path.dirname(app), 'core');
  const { receiverDisplayName } = await import(pathToFileURL(path.join(core, 'receiver-names.js')).href);
  assert.equal(receiverDisplayName({ nickname: ' North antenna ', name: 'Airspy R2' }), 'North antenna');
  assert.equal(receiverDisplayName({ nickname: ' ', name: 'Airspy R2' }), 'Airspy R2');
  assert.equal(receiverDisplayName({ name: 'Airspy R2' }), 'Airspy R2');
  assert.equal(receiverDisplayName(null), 'Receiver');
  assert.equal(receiverDisplayName(null, 'Choose a receiver'), 'Choose a receiver');
  assert.equal(receiverDisplayName({ nickname: '<North>', name: 'Airspy R2' }), '<North>');
  const searchSource = fs.readFileSync(path.join(path.dirname(app), 'features/spectrum-search.js'), 'utf8');
  const refreshStart = searchSource.indexOf('  const updateReceiverMeasurements = ');
  const refreshEnd = searchSource.indexOf('  const updateContext = ', refreshStart);
  assert.ok(refreshStart >= 0 && refreshEnd > refreshStart);
  const refreshed = { catalog: { tuners: [{ id: 'receiver-a', name: 'North antenna', eligible: true }] },
    receiverMeasurementRevision: 0 };
  vm.createContext(refreshed);
  vm.runInContext(searchSource.slice(refreshStart, refreshEnd), refreshed);
  refreshed.inventoryUpdate = { id: 'receiver-a', name: 'Airspy R2', nickname: '' };
  vm.runInContext("updateReceiverMeasurements(inventoryUpdate, 'receiver-a')", refreshed);
  assert.equal(receiverDisplayName(refreshed.catalog.tuners[0]), 'Airspy R2',
    'A fresh inventory clear replaces the old display-only catalog nickname with its hardware fallback.');
  assert.equal(refreshed.catalog.tuners[0].id, 'receiver-a');
  assert.equal(refreshed.catalog.tuners[0].eligible, true);
  assert.equal(refreshed.receiverMeasurementRevision, 0, 'A name refresh does not invalidate RF measurements.');
  refreshed.inventoryUpdate.nickname = 'South antenna';
  vm.runInContext("updateReceiverMeasurements(inventoryUpdate, 'receiver-a')", refreshed);
  assert.equal(receiverDisplayName(refreshed.catalog.tuners[0]), 'South antenna');
  const fieldOptions = binding('channelFieldOptions');
  const tunerChoices = fieldOptions({ path: 'source.preferred_tuner' }, {}, {
    tuners: ['AIRSPY R2 native-id'],
    tuner_options: [{ value: 'AIRSPY R2 native-id', label: 'North antenna' }]
  });
  assert.equal(tunerChoices[0].value, 'AIRSPY R2 native-id', 'Nicknames never replace the preferred-tuner value.');
  assert.equal(tunerChoices[0].label, 'North antenna');
  const legacyChoices = fieldOptions({ path: 'source.preferred_tuner' }, {}, { tuners: ['AIRSPY R2 native-id'] });
  assert.equal(legacyChoices[0].value, 'AIRSPY R2 native-id');
  assert.equal(legacyChoices[0].label, 'AIRSPY R2 native-id');
  const layouts = await import(pathToFileURL(path.join(core, 'table-layout.js')).href);
  const defaults = await import(pathToFileURL(path.join(core, 'table-defaults.js')).href);
  const old = ['status', 'tags', 'channel', 'frequency', 'signal', 'decode-health', 'source', 'target', 'decoder'];
  const legacy = ['status', 'tags', 'channel', 'frequency', 'signal', 'decode-health',
    'source-alias', 'source', 'target-alias', 'target', 'decoder'];
  const columns = [...old, 'receiver'].map(id => ({ id }));
  const initial = defaults.layout('live-channels', columns);
  assert.deepEqual(initial.hidden_columns, ['tags', 'receiver']);
  assert.equal(layouts.normalize(columns, initial).reset, false);
  for (const previous of [old, legacy]) {
    const saved = { schema: previous, column_order: [...previous].reverse(),
      column_widths: { source: 240, ...(previous === legacy ? { 'source-alias': 280 } : {}) },
      hidden_columns: ['signal'], collapsed_groups: ['site-a'] };
    const original = structuredClone(saved);
    const result = layouts.upgradeTableLayout('live-channels', columns, saved);
    assert.equal(layouts.normalize(columns, result).reset, false);
    assert.deepEqual(result.schema, [...old, 'receiver']);
    assert.deepEqual(result.column_order, [...old].reverse().concat('receiver'));
    assert.deepEqual(result.hidden_columns, ['signal', 'receiver']);
    assert.deepEqual(result.column_widths, { source: previous === legacy ? 280 : 240 });
    assert.deepEqual(result.collapsed_groups, ['site-a']);
    assert.deepEqual(saved, original, 'Upgrading does not mutate persisted preferences.');
    const visible = layouts.persisted(layouts.setHidden(layouts.normalize(columns, result), 'receiver', false));
    assert.equal(layouts.upgradeTableLayout('live-channels', columns, visible), visible,
      'Already upgraded layouts preserve an explicitly visible receiver.');
    for (const invalidate of [
      value => value.column_order.pop(), value => value.hidden_columns.push('unknown'),
      value => value.hidden_columns.push('signal'), value => value.column_widths.source = 47,
      value => value.column_widths.source = '240', value => value.schema = [...value.schema].reverse()
    ]) {
      const invalid = structuredClone(saved);
      invalidate(invalid);
      assert.equal(layouts.upgradeTableLayout('live-channels', columns, invalid), invalid);
      assert.equal(layouts.normalize(columns, invalid).reset, true, 'Malformed layouts retain strict reset handling.');
    }
  }
  const future = [...columns, { id: 'future' }];
  assert.equal(layouts.upgradeTableLayout('live-channels', future, initial), initial);
  console.log('Receiver nickname and optional column checks passed.');
}
check().catch(error => { console.error(error); process.exitCode = 1; });
