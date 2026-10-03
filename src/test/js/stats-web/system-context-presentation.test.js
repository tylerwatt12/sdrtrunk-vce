'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const applicationPath = process.argv[2];
assert.ok(applicationPath, 'The app.js path is required.');
const application = fs.readFileSync(applicationPath, 'utf8');
const assets = path.dirname(applicationPath);
const labels = fs.readFileSync(path.join(assets, 'core/system-labels.js'), 'utf8');
const spectrum = fs.readFileSync(path.join(assets, 'features/spectrum-search.js'), 'utf8');

function functionSource(source, signature) {
  const start = source.indexOf(signature);
  assert.ok(start >= 0, `Missing ${signature}`);
  const openingBrace = source.indexOf('{', start + signature.length);
  let depth = 0;
  for (let index = openingBrace; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    else if (source[index] === '}' && --depth === 0) return source.slice(start, index + 1);
  }
  throw new Error(`Unterminated ${signature}`);
}

const context = {
  identitySummaryValue: (primary, secondary) => ({ primary, secondary }),
  hex: (value, width) => Number(value).toString(16).toUpperCase().padStart(width, '0')
};
vm.createContext(context);
vm.runInContext(labels.replace(/export\s*\{[^}]+\};?\s*$/, '') +
  '\nglobalThis.systemLabels = { systemName, systemIdentity, rememberSystemNames };', context);
context.radioSystemLabel = context.systemLabels.systemIdentity;
const functions = [
  'function radioSystemPrimaryName(row)', 'function radioSystemDisplayName(row)',
  'function identifierNumber(value)', 'function spectrumDiscoverySystemName(session, draft = null)',
  "function spectrumDiscoveryIdentity(identity, systemName = '')",
  'function p25OverrideIdentityRow(profile, knownSystems = [])',
  'function p25OverrideDisplayName(profile, knownSystems = [])',
  "async function requestP25BandplanOverrides(method = 'GET', profiles = null)",
  "function receiverHealthText(value, fallback = '—')", 'function receiverHealthScope(row)',
  'function callMatchingIdentity(alias, value, fallback)', 'function callMatchingSourceValue(identity)',
  'function callMatchingIdentitySystem(identity, destination = true)',
  'function callMatchingIdentitySummary(identity, destination = true)', 'function callMatchingCopySite(leg)'
];
vm.runInContext(functions.map((signature) => functionSource(application, signature)).join('\n') + '\n' +
  functionSource(spectrum, 'function spectrumSearchSystemName(candidate)'), context);

const known = [{ wacn: 0xbee00, system: 0x49f, system_name: 'GCRCN',
  radio_system_key: 'p25:bee00:49f', radio_system_entity_ref: { kind: 'radio_system', key: 'p25:bee00:49f' } }];
context.systemLabels.rememberSystemNames(known);
assert.equal(context.p25OverrideDisplayName({ wacn: 0xbee00, system: 0x49f }, known), 'GCRCN');
assert.equal(context.p25OverrideDisplayName({ wacn: 0xabcde, system: 0x49f }, known), 'ABCDE-49F',
  'A reused SysID must not borrow the name of another WACN.');
assert.equal(context.p25OverrideIdentityRow({ wacn: -1, system: 0x49f }, known).radio_system_key, '');

assert.equal(context.spectrumSearchSystemName({ identity: { wacn: 0xbee00, system: 0x49f } }), 'GCRCN');
assert.equal(context.spectrumSearchSystemName({ identity: { wacn: 0xabcde, system: 0x49f },
  alias_name: 'GCRCN' }), '', 'An Alias List name does not identify a discovered system.');
assert.equal(context.spectrumSearchSystemName({ identity: { wacn: undefined, system: 0x49f } }), '');
assert.equal(context.spectrumSearchSystemName({ system_name: 'P25 BEE00-49F', identity: {
  wacn: 0xbee00, system: 0x49f, system_identity: known[0] } }), 'GCRCN');

const session = { review: { template: { system: 'GCRCN' } } };
assert.equal(context.spectrumDiscoverySystemName(session), 'GCRCN');
assert.equal(context.spectrumDiscoverySystemName({ review: { template: { system: 'P25 BEE00-49F' },
  identity: { system_identity: known[0] } } }), 'GCRCN');
assert.equal(context.spectrumDiscoverySystemName(session, { system: '' }), '',
  'Clearing the optional System field must not restore the suggested name.');
assert.equal(context.spectrumDiscoverySystemName(session, { system: ' New name ' }), 'New name');
assert.deepEqual(JSON.parse(JSON.stringify(context.spectrumDiscoveryIdentity(
  { wacn: 0xbee00, system: 0x49f, rfss: 1, site: 2 }, 'GCRCN'))),
[['System', 'GCRCN'], ['WACN', 'BEE00'], ['System ID', '49F'], ['RFSS', '1'], ['Site ID', '2']],
'Discovery detail facts retain all four native identity fields.');

const foreignRadio = { source_value: 'v1-r-abcde-123-456', source_alias: 'Medic 4',
  system_name: 'GCRCN', radio_system_key: 'p25:bee00:49f',
  home_system: { system_name: 'County Radio', radio_system_key: 'p25:abcde:123' } };
assert.equal(context.callMatchingSourceValue(foreignRadio), '456');
assert.deepEqual(JSON.parse(JSON.stringify(context.callMatchingIdentitySummary(foreignRadio, false))),
  { primary: 'Medic 4', secondary: '456 · County Radio' });
assert.equal(foreignRadio.source_value, 'v1-r-abcde-123-456', 'The canonical identity stays available in details.');
const unknownHome = { ...foreignRadio, home_system: undefined };
assert.equal(context.radioSystemPrimaryName(context.callMatchingIdentitySystem(unknownHome, false)), '',
  'An unresolved foreign home system must not use the serving system name.');
assert.equal(context.systemLabels.systemIdentity(context.callMatchingIdentitySystem(unknownHome, false)), 'ABCDE-123');
assert.equal(context.callMatchingCopySite({ system_name: 'GCRCN', channel_name: 'Downtown', rfss: 1, site: 2 }),
  'GCRCN · Downtown');
assert.equal(context.callMatchingCopySite({ channel_name: 'Downtown', rfss: 1, site: 2 }),
  'Downtown · RFSS 1 · Site 2');

assert.equal(context.receiverHealthScope({ scope: 'p25:bee00:49f', system_name: 'GCRCN',
  channel_name: 'Downtown' }), 'GCRCN · Downtown');
assert.equal(context.receiverHealthScope({ scope: 'tuner', display_scope: 'Receiver 1',
  system_name: 'GCRCN' }), 'Receiver 1');
assert.equal(context.receiverHealthScope({ scope: 'unresolved-channel' }), 'unresolved-channel');

async function verifyOverridePayload() {
  let written;
  context.jsonDocumentFetch = async (url, options) => {
    assert.equal(url, '/api/v1/admin/p25-bandplan-overrides');
    written = JSON.parse(options.body);
    return { ok: true, json: async () => ({ profiles: written.profiles }) };
  };
  await context.requestP25BandplanOverrides('PUT', [{ ...known[0], rfss: null, site: null, bands: [] }]);
  assert.deepEqual(written, { profiles: [{ wacn: 0xbee00, system: 0x49f,
    rfss: null, site: null, bands: [] }] }, 'Read-only friendly labels must not enter the saved override format.');
}
verifyOverridePayload().catch((error) => { console.error(error); process.exitCode = 1; });
