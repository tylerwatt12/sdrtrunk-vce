'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/app.js')), 'utf8');

function closingBrace(start) {
  let depth = 0;
  let quote = '';
  for (let index = start; index < source.length; index += 1) {
    const character = source[index];
    if (quote) {
      if (character === '\\') index += 1;
      else if (character === quote) quote = '';
      continue;
    }
    if (character === '\'' || character === '"' || character === '`') quote = character;
    else if (character === '{') depth += 1;
    else if (character === '}' && --depth === 0) return index;
  }
  throw new Error('Unclosed function');
}

function functionSource(name) {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, `Missing ${name}`);
  const open = source.indexOf('{', start);
  return source.slice(start, closingBrace(open) + 1);
}

const behavior = vm.runInNewContext(`(() => {
  ${functionSource('protocol')}
  ${functionSource('protocolFamily')}
  ${functionSource('isP25')}
  ${functionSource('identifierNumber')}
  ${functionSource('hex')}
  ${functionSource('semanticLabel')}
  ${functionSource('trunkedVariant')}
  ${functionSource('savedChannelScopeLabel')}
  ${functionSource('isSavedChannelRadioSystem')}
  ${functionSource('radioSystemLabel')}
  ${functionSource('trunkedSiteLabel')}
  ${functionSource('dashboardChannelKind')}
  ${functionSource('dashboardChannelContext')}
  ${functionSource('channelDirectoryRfIdentity')}
  ${functionSource('channelLocationIdentity')}
  ${functionSource('distinctObservedVariant')}
  ${functionSource('distinctObservedClassification')}
  ${functionSource('dmrChannelDetailRows')}
  ${functionSource('scannerIdentifierNumber')}
  ${functionSource('scannerHex')}
  ${functionSource('scannerIsP25')}
  ${functionSource('scannerNetworkSiteIdentity')}
  return { radioSystemLabel, trunkedSiteLabel, dashboardChannelContext,
    channelDirectoryRfIdentity, channelLocationIdentity, dmrChannelDetailRows,
    scannerNetworkSiteIdentity };
})()`);

const nativeDmr = Object.freeze({
  protocol: 'DMR', channel_kind: 'TRUNKED', radio_system_key: 'dmr:tier3:small:0',
  variant: 'TIER_III', model: 'small', network_id: 0, site_id: 12
});
assert.equal(behavior.radioSystemLabel(nativeDmr), 'DMR Tier III · Small model · Network 0');
assert.equal(behavior.trunkedSiteLabel(nativeDmr), 'DMR site 12');
assert.equal(behavior.dashboardChannelContext(nativeDmr),
  'DMR Tier III · Small model · Network 0 · Site 12');
assert.equal(behavior.channelDirectoryRfIdentity(nativeDmr), 'Site 12');
assert.equal(behavior.channelLocationIdentity(nativeDmr), 'Small model · Network 0 · Site 12');
assert.deepEqual(JSON.parse(JSON.stringify(behavior.dmrChannelDetailRows(nativeDmr).slice(0, 4))), [
  ['Radio System Variant', 'Tier III'], ['Radio System Network', '0'],
  ['Radio System Model', 'Small'], ['Observed Site', '12']
]);
assert.equal(behavior.scannerNetworkSiteIdentity(nativeDmr), 'Network 0 · Site 12');

// A channel-scoped fallback can still have friendly configured names and site observations. Neither is a native
// system identity, so the dashboard must say that the scope is the saved channel.
const fallbackDmr = Object.freeze({
  protocol: 'DMR', channel_kind: 'TRUNKED',
  radio_system_key: 'dmr:channel:00000000-0000-0000-0000-000000000012',
  system_name: 'Downtown', site_name: 'North', site_id: 7
});
assert.equal(behavior.radioSystemLabel(fallbackDmr), 'DMR saved channel scope');
assert.equal(behavior.dashboardChannelContext(fallbackDmr), 'DMR saved channel scope · Site 7');
assert.equal(behavior.trunkedSiteLabel({ ...fallbackDmr, system_name: '' }), 'DMR site 7');
