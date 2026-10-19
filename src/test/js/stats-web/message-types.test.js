'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { pathToFileURL } = require('node:url');

function binding(source, name) {
  const start = source.indexOf(`function ${name}(`);
  assert.ok(start >= 0, `Missing ${name}`);
  const next = source.indexOf('\nfunction ', start + 1);
  const declaration = source.slice(start, next < 0 ? source.length : next);
  // These presentation functions end before any following top-level declaration.
  let depth = 0, quote = '';
  const opening = declaration.indexOf('{');
  for (let index = opening; index < declaration.length; index += 1) {
    const character = declaration[index];
    if (quote) {
      if (character === '\\') index += 1;
      else if (character === quote) quote = '';
    } else if (['"', "'", '`'].includes(character)) quote = character;
    else if (character === '{') depth += 1;
    else if (character === '}' && --depth === 0) return declaration.slice(0, index + 1);
  }
  throw new Error(`Unclosed ${name}`);
}

async function checkMessageTypes(core, source) {
  const { createMessageTypeCatalog } = await import(pathToFileURL(path.join(core, 'message-types.js')).href);
  const rows = [
    { definition_kind: 'ACTION', code: 12, name: 'GRANT', label: 'Assigned channel',
      meaning: 'A channel was assigned.', p25: true, dmr: false, nxdn: false, sort_order: 2 },
    { definition_kind: 'ACTION', code: 90, name: 'NEW_ACTION', label: 'New receiver operation',
      meaning: 'A new operation supplied by the receiver.', p25: false, dmr: true, nxdn: true, sort_order: 1 },
    { definition_kind: 'EVENT', code: 90, name: 'NEW_EVENT', label: 'New event subtype',
      meaning: 'New event details.', p25: false, dmr: true, nxdn: false, sort_order: 1 },
    { definition_kind: 'EVENT', code: 12, name: 'GRANT', label: 'Different event label',
      meaning: 'An event with the same name.', p25: true, dmr: false, nxdn: false, sort_order: 2 },
    { definition_kind: 'ACTION', code: 10, name: 'EMERGENCY', label: 'Receiver emergency wording',
      meaning: 'An emergency reported by the receiver.', p25: true, dmr: true, nxdn: true, sort_order: 3 }
  ];
  let requests = 0;
  const node = (tag, className = '', text = '') => ({ tag, className, text,
    children: [], append(...values) { this.children.push(...values); } });
  const context = vm.createContext({ createMessageTypeCatalog, node,
    identitySummaryValue: (primary, secondary, target) => ({ primary, secondary, target }),
    radioIdentifierText: (_row, id) => `Radio ${id}`, radioDisplayId: row => row.native_id,
    entityReferenceAllowed: reference => Boolean(reference?.href),
    entityTarget: reference => reference.href,
    api: async () => { requests += 1; return { rows }; }
  });
  const catalogStart = source.indexOf('const activityMessageTypes =');
  const catalogEnd = source.indexOf('const DASHBOARD_ACTIVITY_RANGES =', catalogStart);
  vm.runInContext(source.slice(catalogStart, catalogEnd), context);
  vm.runInContext(['activityOptionLabel', 'activityEventValue', 'dashboardActivityActionConfiguration',
    'signalingActionRows', 'activityMetricGuide', 'dashboardActivityRadio'].map(name => binding(source, name)).join('\n'), context);
  await Promise.all([context.loadActivityMessageTypes(), context.loadActivityMessageTypes()]);
  assert.equal(requests, 1, 'Concurrent page work shares the catalog request.');
  assert.equal(context.activityOptionLabel('GRANT'), 'Assigned channel');
  assert.equal(context.activityEventValue({ event_type: 'GRANT' }), 'Different event label',
    'Action and event definitions with the same name remain distinct.');
  assert.equal(context.dashboardActivityActionConfiguration('NEW_ACTION').label, 'New receiver operation',
    'The activity chart displays a newly supplied action without a client type list.');
  assert.equal(context.signalingActionRows([{ action: 'NEW_ACTION' }])[0].label, 'New receiver operation',
    'System signaling summaries share the catalog label.');
  assert.equal(context.activitySignalingSeries()[0].label, 'New receiver operation',
    'Radio and group totals share the catalog label.');
  const guide = JSON.stringify(context.activityMetricGuide());
  assert.ok(guide.includes('A new operation supplied by the receiver.'));
  assert.ok(guide.includes('DMR · NXDN'));
  assert.ok(guide.includes('Assigned channel'));
  const catalog = vm.runInContext('activityMessageTypes', context);
  assert.equal(catalog.has('ACTION', 'NEW_ACTION'), true);
  assert.equal(catalog.has('EVENT', 'NEW_EVENT'), true);
  rows[1] = { ...rows[1], label: 'Updated receiver wording', meaning: 'Updated meaning.', dmr: false };
  await context.loadActivityMessageTypes();
  assert.equal(requests, 2, 'A later page render reads current database definitions.');
  assert.equal(context.activityOptionLabel('NEW_ACTION'), 'Updated receiver wording');
  const updatedGuide = context.activityMetricGuide();
  assert.ok(JSON.stringify(updatedGuide).includes('Updated meaning.'));
  const updatedRow = updatedGuide.children[1].children[0].children[1].children
    .find((row) => row.children[0].text === 'Updated receiver wording');
  assert.equal(updatedRow.children[2].text, 'NXDN', 'Protocol edits are reflected on the next render.');
  const history = await import(pathToFileURL(path.resolve(core, '../features/network-visualizer/history.js')).href);
  const historyState = history.createP25HistoryState({ actionLabel: (name) => catalog.label('ACTION', name) });
  history.applyP25ActivityRows(historyState, [{ id: 1, observed_at_ms: 1_790_000_000_000,
    protocol: 'p25', channel_kind: 'trunked_site', configuration_id: 'site',
    radio_system_key: 'system', action: 'EMERGENCY', source_identity_key: 'v1-r-123',
    source_radio_id: 123, target_identity_key: 'v1-g-456', target_kind: 'talkgroup', target_id: 456 }]);
  assert.equal(history.groupedP25Events(historyState)[0].title, 'Receiver emergency wording',
    'The visualizer uses the same database action label as tables and charts.');
  for (const [alias, expected] of [['Dispatch', 'Dispatch'], ['', 'Radio 123']]) {
    const identity = context.dashboardActivityRadio({ native_id: 123, alias_name: alias,
      radio_entity_ref: { href: '/radio/123' } });
    assert.equal(identity.primary, expected);
    assert.equal(identity.secondary, alias ? 'Radio 123' : '');
    assert.equal(identity.target, '/radio/123', 'The consolidated radio preserves its typed detail link.');
  }
  assert.match(source, /activityDefinitions\(\)\.map\(\(definition\) => \(\{ value: definition\.name, label: definition\.label \}\)\)/,
    'Action filter choices come from the database catalog.');
  assert.match(source, /activityDefinitions\('EVENT'\)\.map\(\(definition\) => \(\{ value: definition\.name, label: definition\.label \}\)\)/,
    'Event filter choices come from the database catalog.');
  assert.doesNotMatch(source, /const (?:ACTIVITY_ACTION_VALUES|ACTIVITY_EVENT_TYPE_VALUES|ACTION_METRIC_GUIDE)/,
    'The browser does not maintain another message definition catalog.');
  console.log('Shared message catalog presentation checks passed.');
}

module.exports = { checkMessageTypes };
if (require.main === module) {
  const core = path.resolve(process.argv[2] || path.resolve(__dirname, '../../../../stats-web/assets/core'));
  checkMessageTypes(core, fs.readFileSync(path.resolve(core, '../app.js'), 'utf8'))
    .catch((error) => { console.error(error); process.exitCode = 1; });
}
