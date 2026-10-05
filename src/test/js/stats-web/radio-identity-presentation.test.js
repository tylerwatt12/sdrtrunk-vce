'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/app.js')), 'utf8');
const labelsSource = fs.readFileSync(path.resolve(__dirname,
  '../../../../stats-web/assets/core/system-labels.js'), 'utf8');
const systemLabels = vm.runInNewContext(labelsSource.replace(/^export .*;$/m, '') +
  '\n({systemName, systemLabel, systemIdentity, rememberSystemNames});');

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

// Evaluate related declarations together so a stale dependency name fails at startup here too.
const activitySeries = vm.runInNewContext(source.slice(
  source.indexOf('const GROUP_IDENTITY_SIGNALING_SERIES ='),
  source.indexOf('const DASHBOARD_ACTIVITY_RANGES =')) + '\nDASHBOARD_ACTIVITY_SERIES;');
assert.ok(activitySeries.some(series => series.action === 'GRANT'));
assert.ok(activitySeries.some(series => series.action === 'REGISTER'));
assert.ok(!activitySeries.some(series => series.action === 'CONTINUE'));

function functionSource(name) {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, `Missing ${name}`);
  const open = source.indexOf('{', start);
  return source.slice(start, closingBrace(open) + 1);
}

const behavior = vm.runInNewContext(`(() => {
  function aliasAdminAllowed() { return true; }
  function node(tag, className, textContent) {
    return { tag, className, textContent, setAttribute() {} };
  }
  function radioLink(_row, value, label) { return { value, label }; }
  function isAnalogChannel() { return false; }
  function href(view, values) {
    const parameters = new URLSearchParams();
    parameters.set('view', view);
    Object.entries(values).forEach(([key, value]) => {
      if (value !== null && value !== undefined && value !== '') parameters.set(key, String(value));
    });
    return '/?' + parameters.toString();
  }
  ${functionSource('protocol')}
  ${functionSource('protocolFamily')}
  ${functionSource('isP25')}
  ${functionSource('identifierNumber')}
  ${functionSource('identityNumber')}
  ${functionSource('hex')}
  ${functionSource('p25CanonicalSubscriber')}
  ${functionSource('canonicalSubscriberText')}
  ${functionSource('radioIdentityPrefix')}
  ${functionSource('workingSubscriberId')}
  ${functionSource('radioIdentifierText')}
  ${functionSource('semanticLabel')}
  ${functionSource('trunkedVariant')}
  ${functionSource('savedChannelScopeLabel')}
  ${functionSource('isSavedChannelRadioSystem')}
  ${functionSource('radioSystemLabel')}
  ${functionSource('radioSystemPrimaryName')}
  ${functionSource('radioSystemDisplayName')}
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
  ${functionSource('identityKind')}
  ${functionSource('rowGroupIdentityKind')}
  ${functionSource('observedGroupIdentityProtocol')}
  ${functionSource('observedGroupIdentityKey')}
  ${functionSource('specialIdentifierLabel')}
  ${functionSource('activityIdentifier')}
  ${functionSource('activityTargetKind')}
  ${functionSource('activitySourceAlias')}
  ${functionSource('activitySourceTalkerAlias')}
  ${functionSource('activityTargetAlias')}
  ${functionSource('liveIdentityType')}
  ${functionSource('liveIdentityLabel')}
  ${functionSource('liveIdentityActionTitle')}
  return { radioSystemLabel, trunkedSiteLabel, dashboardChannelContext,
    channelDirectoryRfIdentity, channelLocationIdentity, dmrChannelDetailRows,
    scannerNetworkSiteIdentity, observedGroupIdentityKey, radioIdentifierText, liveIdentityActionTitle,
    activityIdentifier, activitySourceAlias, activitySourceTalkerAlias, activityTargetAlias };
})()`, { URLSearchParams, systemLabels });

assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 9_601_699 },
  working_subscriber_id: 130_001
}, 130_001), 'BEE00.348.9601699 (Working ID 130001)');
assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 2_115_288 },
  observed_working_id: 501
}, 2_115_288), 'BEE00.348.2115288 (Working ID 501)');
assert.equal(behavior.radioIdentifierText({
  protocol: 'APCO25', source_canonical_identity: {
    wacn: 0xBEE00, system_id: 0x348, subscriber_id: 9_601_699
  }, source_id: 130_001, source_observed_working_id: 130_001
}, 130_001, 'source'), 'BEE00.348.9601699 (Working ID 130001)');
assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', home_wacn: 0xBEE00, home_system_id: 0x348, native_id: 130_001
}, 130_001), '130001',
  'An ordinary local P25 radio must not be relabeled as a roaming working ID.');
assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', source_canonical_identity: {
    wacn: 0x92498, system_id: 0x926, subscriber_id: 34_006
  }, source_id: 130_002, source_observed_working_id: 130_002
}, 130_002), '92498.926.34006 (Working ID 130002)');
assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', target_canonical_identity: {
    wacn: 0xBEE00, system_id: 0x348, subscriber_id: 0xFFFFFC
  }, target_id: 130_003, target_observed_working_id: 130_003
}, 130_003), 'BEE00.348.16777212 (Working ID 130003)');
assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', target_canonical_identity: {
    wacn: 0xBEE00, system_id: 0x348, subscriber_id: 0xFFFFFD
  }, target_id: 130_004, target_observed_working_id: 130_004
}, 130_004), '130004');
assert.equal(behavior.radioIdentifierText({ protocol: 'P25' }, 0), '0');
assert.equal(behavior.radioIdentifierText({ protocol: 'P25' }, 0xFFFFFD), '16777213');
assert.equal(behavior.radioIdentifierText({ protocol: 'P25' }, 0xFFFFFF), '16777215');
for (const missing of [null, undefined, '', ' ']) {
  assert.equal(behavior.radioIdentifierText({ protocol: 'P25', canonical_wacn: missing,
    canonical_system_id: missing, canonical_subscriber_id: 501 }, 130_001), '130001',
  'A partial scalar identity must not fabricate a zero home system.');
}
assert.equal(behavior.radioIdentifierText({ protocol: 'P25', canonical_wacn: 0,
  canonical_system_id: 0, canonical_subscriber_id: 501 }, 501), '00000.000.501',
  'Explicit numeric zero home values are retained.');
assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 501 },
  observed_working_id: 0
}, 501), 'BEE00.348.501', 'Zero must not be appended as an observed working ID.');
assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 501 },
  observed_working_id: 0xFFFFFD
}, 501), 'BEE00.348.501', 'Reserved addresses must not be appended as observed working IDs.');
assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 501 },
  observed_working_id: 501
}, 501), 'BEE00.348.501',
  'An equal Working ID must not repeat the subscriber number in the radio label.');
assert.equal(behavior.liveIdentityActionTitle({
  protocol: 'APCO25', source_form: 'RADIO', source_id: 130_001,
  source_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 9_601_699 },
  source_observed_working_id: 130_001
}, 'source'), 'Radio BEE00.348.9601699 (Working ID 130001)',
  'The Live action dialog title must retain the canonical subscriber and explicit working ID.');
assert.match(functionSource('liveIdentityActionLink'),
  /openReadOnlyModal\(liveIdentityActionTitle\(row, kind, label\),/,
  'The Live action dialog must pass its selected label to the identity-aware title formatter.');
assert.equal(behavior.liveIdentityActionTitle({
  protocol: 'P25', source_form: 'RADIO', source_id: 130_001, source_alias: 'Engine 42',
  source_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 9_601_699 }
}, 'source', 'Engine 42'), 'Radio Engine 42', 'A known friendly Alias is the Live dialog title.');

const dockSource = fs.readFileSync(path.resolve(__dirname,
  '../../../../stats-web/assets/core/audio-dock.js'), 'utf8');
const dock = vm.runInNewContext(`(() => {
  ${dockSource.slice(dockSource.indexOf('  const identityType ='), dockSource.indexOf('  const time ='))}
  return { title, sourceSummary, sourceId, targetId };
})()`, { radioIdentifier: behavior.radioIdentifierText });
const dockCall = {
  protocol: 'P25', source_id: 130_001, source_form: 'RADIO', source_alias: 'Engine 42',
  source_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 9_601_699 },
  source_observed_working_id: 130_001, target_id: 130_002, target_form: 'RADIO',
  target_canonical_identity: { wacn: 0x92498, system_id: 0x926, subscriber_id: 16_777_212 },
  target_observed_working_id: 130_002
};
assert.equal(dock.sourceSummary(dockCall), 'Engine 42 · Radio BEE00.348.9601699 (Working ID 130001)');
assert.equal(dock.sourceId(dockCall), 'BEE00.348.9601699 (Working ID 130001)');
assert.equal(dock.targetId(dockCall), '92498.926.16777212 (Working ID 130002)');
assert.equal(dock.title(dockCall), 'Radio 92498.926.16777212 (Working ID 130002)');
assert.equal(dock.title({ target_id: 1201, target_form: 'TALKGROUP' }), 'TGID 1201');
assert.equal(dock.title({ destination_radio_id: 501 }), 'Radio 501');
assert.equal(dock.sourceSummary({}), '', 'Missing source facts remain empty in the audio player.');
assert.match(dockSource, /\['Target ID', targetId\(call\),/);
assert.match(dockSource, /\['Source ID', sourceId\(call\),/);
assert.equal(behavior.activityIdentifier({
  protocol: 'P25', source_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 16_777_212 },
  source_observed_working_id: 130_001
}, 16_777_212, 'radio', null, false, 'source'), 'BEE00.348.16777212 (Working ID 130001)',
'An explicit permanent subscriber tuple takes precedence over a bare special-number interpretation.');
assert.equal(behavior.activityIdentifier({ protocol: 'P25' }, 16_777_212, 'radio', null, false).textContent, 'FNE',
  'Bare special signaling remains labeled FNE.');
const activityMaximum = {
  protocol: 'P25', source_radio_id: 16_777_212, source_alias_name: 'Engine 42', source_talker_alias: 'ENG42',
  source_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 16_777_212 },
  target_kind: 'radio', target_id: 16_777_212, target_alias_name: 'Command',
  target_canonical_identity: { wacn: 0x92498, system_id: 0x926, subscriber_id: 16_777_212 }
};
assert.equal(behavior.activitySourceAlias(activityMaximum).label, 'Engine 42');
assert.equal(behavior.activitySourceTalkerAlias(activityMaximum).label, 'ENG42');
assert.equal(behavior.activityTargetAlias(activityMaximum).label, 'Command');
assert.equal(behavior.activitySourceAlias({ ...activityMaximum, source_canonical_identity: null }), 'Engine 42');
assert.equal(behavior.activityTargetAlias({ ...activityMaximum, target_canonical_identity: null }), 'Command',
  'A canonical source does not turn an unrelated bare special target into a subscriber.');

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

// A configured name is the primary label; the saved-channel scope remains the exact identity fallback.
const fallbackDmr = Object.freeze({
  protocol: 'DMR', channel_kind: 'TRUNKED',
  radio_system_key: 'dmr:channel:00000000-0000-0000-0000-000000000012',
  system_name: 'Downtown', site_name: 'North', site_id: 7
});
assert.equal(behavior.radioSystemLabel(fallbackDmr), 'DMR saved channel scope');
assert.equal(behavior.dashboardChannelContext(fallbackDmr), 'Downtown · Site 7');
assert.equal(behavior.trunkedSiteLabel({ ...fallbackDmr, system_name: '' }), 'DMR site 7');

const conventionalDmr = {
  topology: 'CONVENTIONAL', protocol: 'DMR',
  configuration_id: '00000000-0000-0000-0000-000000000074',
  group_identity_kind: 'talkgroup', native_id: 7, group_identity_id: 7,
  frequency_hz: 460012500
};
assert.notEqual(behavior.observedGroupIdentityKey({ ...conventionalDmr, timeslot: 1 }),
  behavior.observedGroupIdentityKey({ ...conventionalDmr, timeslot: 2 }),
  'Conventional DMR discovery rows must retain their independent timeslot identity');
