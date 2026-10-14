'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../../../stats-web/assets/app.js'), 'utf8');

function declaration(name) {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, `Missing ${name}`);
  let quote = '', depth = 0;
  for (let index = source.indexOf('{', source.indexOf(')', start)); index < source.length; index++) {
    const character = source[index];
    if (quote) {
      if (character === '\\') index++;
      else if (character === quote) quote = '';
    } else if (['\'', '"', '`'].includes(character)) quote = character;
    else if (character === '{') depth++;
    else if (character === '}' && --depth === 0) return source.slice(start, index + 1);
  }
  throw new Error(`Unclosed ${name}`);
}

const node = (tag, className = '', textContent = '') => ({
  tag, className, textContent, children: [], append(...children) { this.children.push(...children); },
  classList: { add() {} }
});
const context = {
  node, number: String, identifierNumber: String,
  aliasListFamily: row => row?.family,
  entityTarget: (reference, tabs = {}) => reference ? `/?view=channel&configuration_id=${reference.key}&tab=${tabs.channel || 'info'}` : '',
  identitySummaryValue: (label, details, target) => ({ label, details, target }),
  groupIdentifierText: (_row, value) => String(value),
  groupIdentityLabel: () => 'Talkgroup',
  aliasCoverageTime: (value, label) => ({ value, label }),
  metrics: entries => Object.assign(node('div'), { entries }),
  aliasAdminAllowed: () => true,
  isP25: row => String(row.protocol).toLowerCase() === 'p25',
  protocolFamily: row => row.protocol,
  frequency: value => String(value / 1e6),
  radioSystemDisplayName: row => row.system_name || '',
  aliasMetricValue: (row, field) => row[field] == null ? '—' : String(row[field]),
  aliasMetricTime: (_row, field) => field,
  observedGroupIdentityFocusKey: () => 'selected',
  aliasListId: row => row.alias_list_id,
  aliasCoverageAliasCell: () => {}, aliasCoverageIdentityCell: () => {},
  aliasCoverageOwnerCell: () => {}, aliasCoverageEvidenceTime: () => {},
  aliasDetailMetricBand: (_row, definitions) => definitions,
  section: (title, body) => ({ title, body }),
  fragment: (...children) => children.filter(value => value != null),
  keyValues: values => values,
  tableSection: title => ({ title })
};
vm.createContext(context);
const names = ['observedGroupIdentityDiscoverySupported', 'analogAliasList', 'configuredAliasAssignment',
  'aliasIdentityDetailTarget', 'aliasCoverageAliasCell', 'identityActivityValue', 'aliasCoverageIdentityCell',
  'aliasCoverageMetric', 'aliasCoverageEvidenceTime', 'aliasCoverageAliasesColumns',
  'aliasCoverageUnassignedIdentity', 'aliasCoverageUnassignedColumns', 'aliasCoverageSummaryCards',
  'observedGroupIdentityProtocol', 'observedGroupIdentityVariant', 'observedGroupIdentityPromotionSupported',
  'observedGroupIdentityPromotionReason', 'observedGroupIdentityPrefill', 'aliasActivityContent'];
vm.runInContext(names.map(declaration).join('\n'), context);

assert.equal(context.observedGroupIdentityDiscoverySupported({ family: 'NBFM' }), true);
assert.equal(context.observedGroupIdentityDiscoverySupported({ family: 'NBFM',
  capabilities: { observed_group_discovery: false } }), false,
'Server capabilities control discovery availability');

for (const protocol of ['AM', 'nbfm']) {
  const row = { protocol, identity_type: 'talkgroup', value: 1, group_identity_id: 1,
    name: 'Dispatch', entity_ref: { kind: 'channel', key: 'saved-1' },
    metrics_state: 'observed', logical_call_count: 5, promotion_supported: true };
  assert.match(context.aliasCoverageAliasCell(row).target, /tab=info$/,
    'Analog alias names open their saved channel, never a synthetic group tab');
  assert.equal(context.aliasCoverageIdentityCell(row).details, 'Assigned number');
  assert.match(context.aliasCoverageUnassignedIdentity(row).target, /tab=info$/);
  const prefill = context.observedGroupIdentityPrefill(row, { alias_list_id: 7 });
  assert.equal(prefill.matcher.protocol, protocol.toLowerCase());
  assert.equal(prefill.matcher.value, 1);
  assert.equal(prefill.alias_list_id, 7);
  const panels = context.aliasActivityContent(row).children;
  assert.deepEqual(Array.from(panels, panel => panel.title), ['Calls', 'Activity dates'],
    'Analog details must not invent signaling, affiliations, or historical source breakdown');
  assert.equal(panels[0].body.some(([label]) => label === 'Encrypted'), false);
}
assert.match(context.aliasCoverageAliasCell({ protocol: 'P25', identity_type: 'talkgroup',
  entity_ref: { key: 'digital-1' } }).target, /tab=groups$/,
'Existing digital group navigation remains available');

assert.equal(context.aliasCoverageMetric({ metrics_state: 'covered_no_evidence', logical_call_count: 0 },
  'logical_call_count'), '0');
assert.equal(context.aliasCoverageMetric({ metrics_state: 'unsupported', logical_call_count: 12 },
  'logical_call_count').textContent, 'Not tracked');
assert.equal(context.aliasCoverageMetric({ metrics_state: 'not_collected' },
  'logical_call_count').textContent, 'Not collected');
assert.equal(context.aliasCoverageEvidenceTime({ metrics_state: 'unsupported' }, 'last_evidence_ms').label,
  'Not tracked');
assert.equal(context.aliasCoverageEvidenceTime({ metrics_state: 'not_collected' }, 'last_evidence_ms').label,
  'Not collected');
assert.deepEqual(Array.from(context.aliasCoverageAliasesColumns(true), column => column.id),
  ['name', 'identity', 'calls', 'first', 'heard']);
assert.deepEqual(Array.from(context.aliasCoverageUnassignedColumns(true), column => column.id),
  ['system', 'identity', 'calls', 'first', 'last']);
const summary = context.aliasCoverageSummaryCards({ configured_alias_count: 0,
  logical_call_count: 8, matched_logical_call_count: 5, unmatched_logical_call_count: 3 },
{ total_count: 2 }, false, true);
assert.deepEqual(Array.from(summary.entries.slice(4), entry => [entry[0], entry[1]]),
  [['Total calls', 8], ['Matched calls', 5], ['Unmatched calls', 3]],
'A list with no configured aliases can still show received and unmatched calls');
console.log('Analog alias inventory checks passed');
