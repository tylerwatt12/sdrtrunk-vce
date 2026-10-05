'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../../..');
const labelsSource = fs.readFileSync(path.join(root, 'stats-web/assets/core/system-labels.js'), 'utf8');
const labels = vm.runInNewContext(labelsSource.replace(/^export .*;$/m, '') +
  '\n({systemName, systemLabel, systemIdentity, knownSystemNameForKey, rememberSystemNames});');

const marcs = { protocol: 'P25', radio_system_key: 'p25:bee00:348', wacn: 0xbee00, system_id: 0x348,
  system_name: 'Ohio MARCS-IP: Multi-Agency Radio Communications', channel_names: 'MARCS-CuyCoSimul' };
assert.equal(labels.systemLabel(marcs), marcs.system_name);
assert.equal(labels.systemIdentity(marcs), 'BEE00-348');
assert.equal(labels.systemLabel({ protocol: 'P25', wacn: 0, system_id: 0 }), '00000-000');
assert.equal(labels.systemLabel({ radio_system_key: 'dmr:tier3:small:0' }),
  'DMR Tier III · Small model · Network 0');
assert.equal(labels.systemLabel({ radio_system_key: 'nxdn-c:regional:123' }),
  'NXDN Type-C · Regional · System 123');
assert.equal(labels.systemLabel({ radio_system_key: 'dmr:channel:channel-id', system_name: 'Downtown' }), 'Downtown');
assert.equal(labels.systemLabel({ radio_system_key: 'dmr:channel:channel-id' }), 'DMR saved channel scope');
assert.equal(labels.systemName({ name: 'Channel A', system: 0x348 }), '', 'A channel name/number is not a system name');
assert.equal(labels.systemName({ system: '49F' }), '', 'An editable SysID string is not a name');
assert.equal(labels.systemName({ call_id: 'captured-call', system: 'ABC' }), 'ABC',
  'Captured configured names remain usable when the database is unavailable');
assert.equal(labels.systemName({ name: 'MARCS', key: 'p25:bee00:348' }), 'MARCS');
assert.equal(labels.systemName({ system_names: [' West ', 'EAST', 'west'] }), 'EAST / West');

labels.rememberSystemNames({ rows: [marcs] });
assert.equal(labels.systemLabel({ radio_system_key: marcs.radio_system_key }), marcs.system_name);
assert.equal(labels.systemLabel({ radio_system_key: 'p25:00001:348' }), '00001-348',
  'The same SysID in another WACN must not borrow a name');
assert.equal(labels.systemLabel({ radio_system_key: 'dmr:tier3:large:348' }),
  'DMR Tier III · Large model · Network 348');
labels.rememberSystemNames({ radio_system_key: marcs.radio_system_key, system_name: null });
assert.equal(labels.systemLabel({ radio_system_key: marcs.radio_system_key }), 'BEE00-348',
  'A removed configured name must not survive in the cache');

const app = fs.readFileSync(path.join(root, 'stats-web/assets/app.js'), 'utf8');
function declaration(name) {
  const expression = new RegExp(`(?:async )?function ${name}\\(`);
  const match = expression.exec(app);
  assert.ok(match, name);
  const start = match.index;
  const open = app.indexOf('{', start);
  let depth = 0;
  let quote = '';
  for (let index = open; index < app.length; index++) {
    const character = app[index];
    if (quote) {
      if (character === '\\') index++;
      else if (character === quote) quote = '';
    } else if (character === '\'' || character === '"' || character === '`') quote = character;
    else if (character === '{') depth++;
    else if (character === '}' && --depth === 0) return app.slice(start, index + 1);
  }
  throw new Error(`Unclosed ${name}`);
}

function node(_tag, _className, value = '') {
  return { children: [value], classList: { add() {} }, append(...values) { this.children.push(...values); },
    get textContent() { return this.children.map((child) => child?.textContent ?? child ?? '').join(''); } };
}
const headers = [];
const facts = [];
const route = new URLSearchParams('tab=groups');
const context = {
  systemLabels: labels, node, route, window: { history: { replaceState() {} } },
  captureRenderContext: () => ({}), requiredRadioSystem: () => ({ radio_system_key: marcs.radio_system_key }),
  api: async () => marcs, apiPage: async () => ({ rows: [] }), renderIsCurrent: () => true,
  radioSystemApiPath: () => '/', radioSystemTabItems: () => ['info', 'groups', 'radios', 'activity', 'talker-aliases'].map(id => ({ id })),
  radioSystemAssignmentLabel: () => '', radioSystemTabs: () => null,
  pageHeader: (title, subtitle) => { const value = { title, subtitle }; headers.push(value); return value; },
  beginPage: () => true, content: { append() {} }, exportCsvLink: () => null,
  createAsyncSection: () => ({ element: null, load: async (loader, render) => render(await loader()) }),
  searchBar: () => null, pageParameters: () => ({}), groupIdentityColumns: [],
  pagedTableContent: () => null, radioSystemChannelColumns: [], pagedSection: () => null,
  section: (_title, value) => value, metrics: () => null,
  keyValues: (values) => { facts.push(...values); return null; }, dateTime: () => '',
  signalingMetrics: () => null, signalingActionRows: () => [], activityMetricGuide: () => null,
  fragment: (...values) => { const value = node('fragment'); value.append(...values); return value; },
  valueNode: (value) => value, radioSystemCapability: () => false,
  ACCESS_CAPABILITIES: { RADIO: 'radio' }, capabilityAllowed: () => true, entityRefHref: () => '',
  labeledBaseValue: (value, base) => `${value} ${base}`
};
const names = ['protocol', 'protocolFamily', 'isP25', 'identifierNumber', 'hex', 'semanticLabel',
  'savedChannelScopeLabel', 'isSavedChannelRadioSystem', 'radioSystemOwnerLabel', 'radioSystemLabel',
  'radioSystemPrimaryName', 'radioSystemDisplayName', 'radioSystemValue', 'radioSystemAliasLists',
  'radioSystemIdentityValue', 'radioSystemInfoValue', 'radioSystemInfoFact', 'radioSystemLink',
  'sameSiteText', 'radioSystemsDirectoryDetails',
  'entityPageTitle', 'renderRadioSystem'];
vm.createContext(context);
vm.runInContext(names.map(declaration).join('\n'), context);
vm.runInContext(declaration('relatedRadioSystem'), context);
const unknownForeign = context.relatedRadioSystem({ ...marcs,
  radio_system_entity_ref: { kind: 'radio_system', key: marcs.radio_system_key },
  foreign_system: { key: 'p25:00001:348', wacn: 1, system_id: 0x348 } });
assert.equal(labels.systemName(unknownForeign), '', 'Unknown foreign systems never borrow the serving name');
assert.equal(unknownForeign.radio_system_entity_ref, null, 'Unknown foreign systems never link to the serving system');

(async () => {
  await context.renderRadioSystem();
  assert.equal(headers[0].title, `System: ${marcs.system_name}`, 'Groups shares the typed named heading');
  assert.equal(headers[0].subtitle, `P25 · ${marcs.channel_names}`,
    'The protocol distinguishes systems while their exact identity remains in Info');
  route.set('tab', 'info');
  await context.renderRadioSystem();
  assert.equal(headers[1].title, `System: ${marcs.system_name}`);
  const identity = facts.find(([label]) => label === 'Radio System')[1].textContent;
  assert.equal(identity, `${marcs.system_name}BEE00-348 HEX·781824-840 DEC`,
    'System Info makes the friendly name primary and retains the native identity as context');
})().catch((error) => { console.error(error); process.exitCode = 1; });
