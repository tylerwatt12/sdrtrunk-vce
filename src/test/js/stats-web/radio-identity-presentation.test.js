'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const callFormatsSource = fs.readFileSync(path.resolve(__dirname,
  '../../../../stats-web/assets/core/call-value-format.js'), 'utf8');
const callFormats = vm.runInNewContext(callFormatsSource.replace(/^export /gm, '') +
  '\n({formatHexDecimal, formatVoiceQuality});');
const callAudioDetailsSource = fs.readFileSync(path.resolve(__dirname,
  '../../../../stats-web/assets/core/call-audio-details.js'), 'utf8');
const callAudioDetails = vm.runInNewContext(callAudioDetailsSource.replace(/^export /gm, '') +
  '\n({callAudioDetailGroups});');
for (const rate of [8000, 16000, 32000, 44100, 48000]) {
  const groups = callAudioDetails.callAudioDetailGroups({ audio_sample_rate: rate,
    voice_enhancement: true, voice_model: 'retired-metadata' });
  assert.deepEqual(JSON.parse(JSON.stringify(groups)), [['Audio', [['Sample rate', `${rate / 1000} kHz`]]]],
    'Call details show the supplied sample rate without codec-specific enhancement or model controls.');
}
for (const rate of [0, -8000, 1.5, '16000', NaN, Infinity]) {
  assert.equal(callAudioDetails.callAudioDetailGroups({ audio_sample_rate: rate }).length, 0);
}
assert.equal(callFormats.formatHexDecimal(0xBEE00, 5), '0xBEE00 (781824)');
assert.equal(callFormats.formatHexDecimal('0x293', 3), '0x293 (659)');
assert.equal(callFormats.formatHexDecimal('BEE00', 5), '0xBEE00 (781824)');
assert.equal(callFormats.formatHexDecimal(0, 2), '0x00 (0)', 'Zero remains a reported identity.');
assert.equal(callFormats.formatHexDecimal(0xFF, 2), '0xFF (255)');
assert.equal(callFormats.formatHexDecimal('Unavailable', 3), 'Unavailable', 'Unknown text is preserved.');
for (const missing of [null, undefined, '', ' ']) {
  assert.equal(callFormats.formatHexDecimal(missing, 3), '');
  assert.equal(callFormats.formatVoiceQuality(missing), '', 'Missing quality is never fabricated as zero.');
}
assert.equal(callFormats.formatVoiceQuality(97.45678), '97.46%');
assert.equal(callFormats.formatVoiceQuality(0), '0.00%');
assert.equal(callFormats.formatVoiceQuality(100), '100.00%');
assert.equal(callFormats.formatVoiceQuality('unknown'), '');

const source = fs.readFileSync(path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/app.js')), 'utf8');
assert.ok(source.includes('liveDetailCaptureRow(events, order, event,'),
  'Live capture retains the event directly instead of inferring scope from a table selection.');
assert.ok(!source.includes('liveEventCaptured'), 'Live capture needs no passthrough presentation wrapper.');
const labelsSource = fs.readFileSync(path.resolve(__dirname,
  '../../../../stats-web/assets/core/system-labels.js'), 'utf8');
const systemLabels = vm.runInNewContext(labelsSource.replace(/^export .*;$/m, '') +
  '\n({systemName, homeSystemName, systemLabel, systemIdentity, rememberSystemNames});');
const radioLabelsSource = fs.readFileSync(path.resolve(__dirname,
  '../../../../stats-web/assets/core/radio-labels.js'), 'utf8');
const radioLabels = vm.runInNewContext(radioLabelsSource.replace(/^export .*;$/m, '') +
  '\n({formatP25RadioIdentifier, formatP25GroupIdentifier, p25IdentityFacts, p25IdentifierText, formatP25QualifiedIdentifierText, p25ServingSystemKey});');
const sourceNamesSource = fs.readFileSync(path.resolve(__dirname,
  '../../../../stats-web/assets/core/source-names.js'), 'utf8');
const sourceNames = vm.runInNewContext(sourceNamesSource.replace(/^export /m, '') + '\n({formatSourceName});');

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
  function aliasLabel(row) { return row.alias_name || ''; }
  function activeUserPreferences() { return { presentation: { source_name_display: 'talker_alias' } }; }
  function capabilityAllowed() { return true; }
  const ACCESS_CAPABILITIES = { RADIO: 'radio' };
  function entityRefHref(reference) { return reference?.href || ''; }
  function radioSystemContextLink() { return 'Receiving system'; }
  function liveIdentityActionLink(_row, _kind, label) { return label; }
  function identitySummaryValue(primary, secondary, target) { return primary ? {primary, secondary, target} : ''; }
  function keyValues(values) { return {values, classList: {add() {}}}; }
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
  ${functionSource('groupIdentityPrefix')}
  ${functionSource('groupIdentifierText')}
  ${functionSource('partyIdentifierText')}
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
  ${functionSource('activityPartyName')}
  ${functionSource('activityPartyIdentifier')}
  ${functionSource('activityPartyValue')}
  ${functionSource('activityIdentityKey')}
  ${functionSource('activityIdentityKindFromKey')}
  ${functionSource('activityIdentityKindLabel')}
  ${functionSource('activityIdentitySuggestion')}
  ${functionSource('activityIdentityInitialSelection')}
  ${functionSource('liveIdentityType')}
  ${functionSource('sourceNameDisplayMode')}
  ${functionSource('liveAliasReferences')}
  ${functionSource('liveSourceName')}
  ${functionSource('liveIdentityLabel')}
  ${functionSource('liveIdentityActionTitle')}
  ${functionSource('p25IdentityEvidenceLabel')}
  ${functionSource('liveIdentityFacts')}
  ${functionSource('liveIdentifierAliasValue')}
  ${functionSource('liveEventRadioRow')}
  ${functionSource('liveEventReplaceRadioText')}
  ${functionSource('liveEventIdentifierText')}
  ${functionSource('liveEventPartyText')}
  ${functionSource('liveEventDetailsText')}
  ${functionSource('liveDetailText')}
  ${functionSource('liveEventMatchesQuery')}
  return { radioSystemLabel, trunkedSiteLabel, dashboardChannelContext,
    channelDirectoryRfIdentity, channelLocationIdentity, dmrChannelDetailRows,
    scannerNetworkSiteIdentity, observedGroupIdentityKey, radioIdentifierText, groupIdentifierText, partyIdentifierText, liveIdentityActionTitle,
    activityIdentifier, activitySourceAlias, activitySourceTalkerAlias, activityTargetAlias, activityPartyValue,
    activityIdentitySuggestion, activityIdentityInitialSelection, liveIdentityFacts, liveIdentifierAliasValue,
    liveEventRadioRow, liveEventIdentifierText, liveEventPartyText, liveEventDetailsText,
    liveEventMatchesQuery };
})()`, { URLSearchParams, systemLabels, ...radioLabels, ...sourceNames });

assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 9_601_699 },
  working_subscriber_id: 130_001
}, 130_001), '9601699 (Working ID 130001)');
assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 2_115_288 },
  observed_working_id: 501
}, 2_115_288), '2115288 (Working ID 501)');
assert.equal(behavior.radioIdentifierText({
  protocol: 'APCO25', source_canonical_identity: {
    wacn: 0xBEE00, system_id: 0x348, subscriber_id: 9_601_699
  }, source_id: 130_001, source_observed_working_id: 130_001
}, 130_001, 'source'), '9601699 (Working ID 130001)');
assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', home_wacn: 0xBEE00, home_system_id: 0x348, native_id: 130_001
}, 130_001), '130001',
  'An ordinary local P25 radio must not be relabeled as a roaming working ID.');
assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', source_canonical_identity: {
    wacn: 0x92498, system_id: 0x926, subscriber_id: 34_006
  }, source_id: 130_002, source_observed_working_id: 130_002
}, 130_002), '34006 (Working ID 130002)');
assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', target_canonical_identity: {
    wacn: 0xBEE00, system_id: 0x348, subscriber_id: 0xFFFFFC
  }, target_id: 130_003, target_observed_working_id: 130_003
}, 130_003), '16777212 (Working ID 130003)');
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
  canonical_system_id: 0, canonical_subscriber_id: 501 }, 501), '501',
  'Explicit numeric zero home values are retained.');
assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 501 },
  observed_working_id: 0
}, 501), '501', 'Zero must not be appended as an observed working ID.');
assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 501 },
  observed_working_id: 0xFFFFFD
}, 501), '501', 'Reserved addresses must not be appended as observed working IDs.');
assert.equal(behavior.radioIdentifierText({
  protocol: 'P25', canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 501 },
  observed_working_id: 501
}, 501), '501',
  'An equal Working ID must not repeat the subscriber number in the radio label.');
assert.equal(behavior.liveIdentityActionTitle({
  protocol: 'APCO25', source_form: 'RADIO', source_id: 130_001,
  source_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 9_601_699 },
  source_observed_working_id: 130_001
}, 'source'), 'Radio 9601699 (Working ID 130001)',
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
let sourceMode = 'talker_alias';
const dock = vm.runInNewContext(`(() => {
  ${dockSource.slice(dockSource.indexOf('  const identityType ='), dockSource.indexOf('  const time ='))}
  return { title, sourceName, sourceSummary, sourceId, targetId };
})()`, { radioIdentifier: behavior.partyIdentifierText, ...sourceNames, getSourceNameDisplay: () => sourceMode });
const dockCall = {
  protocol: 'P25', source_id: 130_001, source_form: 'RADIO', source_alias: 'Engine 42',
  source_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 9_601_699 },
  source_observed_working_id: 130_001, target_id: 130_002, target_form: 'RADIO',
  target_canonical_identity: { wacn: 0x92498, system_id: 0x926, subscriber_id: 16_777_212 },
  target_observed_working_id: 130_002
};
assert.equal(dock.sourceSummary(dockCall), 'Engine 42 · Radio 9601699 (Working ID 130001)');
const namedDockCall = { ...dockCall, talker_alias: 'ENG 42' };
assert.equal(dock.sourceName(namedDockCall), 'ENG 42');
assert.equal(dock.sourceSummary(namedDockCall), 'ENG 42 · Radio 9601699 (Working ID 130001)',
  'The audio dock prefers the talker alias while retaining the full radio identity');
sourceMode = 'source_alias';
assert.equal(dock.sourceName(namedDockCall), 'Engine 42');
sourceMode = 'both';
assert.equal(dock.sourceName(namedDockCall), 'Engine 42 · ENG 42');
assert.equal(dock.sourceSummary(namedDockCall), 'Engine 42 · ENG 42 · Radio 9601699 (Working ID 130001)');
assert.equal(dock.sourceName({ ...namedDockCall, talker_alias: 'engine 42' }), 'Engine 42');
sourceMode = 'talker_alias';
assert.equal(dock.sourceId(dockCall), '9601699 (Working ID 130001)');
assert.equal(dock.targetId(dockCall), '16777212 (Working ID 130002)');
assert.equal(dock.title(dockCall), 'Radio 16777212 (Working ID 130002)');
assert.equal(dock.title({ target_id: 1201, target_form: 'TALKGROUP' }), 'TGID 1201');
assert.equal(dock.title({ destination_radio_id: 501 }), 'Radio 501');
assert.equal(dock.sourceSummary({}), '', 'Missing source facts remain empty in the audio player.');
assert.match(dockSource, /\['Target ID', targetId\(call\),/);
assert.match(dockSource, /\['Source ID', sourceId\(call\),/);
assert.match(dockSource, /JSON\.stringify\(\[source, panel, getSourceNameDisplay\(\),/,
  'Changing the source name preference must invalidate an open audio detail or queue panel');

const detailNode = (tag, className = '', initialText = '') => ({
  nodeType: 1, tag, className, childNodes: [], ownText: initialText,
  append(...children) { this.childNodes.push(...children); },
  get children() { return this.childNodes.filter(child => child?.nodeType === 1); },
  get textContent() { return this.ownText + this.childNodes.map(child =>
    typeof child === 'string' ? child : child.textContent).join(''); },
  set textContent(value) { this.ownText = value; this.childNodes = []; }
});
const dockDetails = vm.runInNewContext(`(() => {
  let liveState = {};
  let navigationAllowed = true;
  const panelHost = node('div');
  const canViewRadio = () => navigationAllowed;
  ${dockSource.slice(dockSource.indexOf('  function patchMembers('),
    dockSource.indexOf('  const queueEntryKey ='))}
  return { render(call, allowed = true) {
    liveState = { current: call, stopped: false, scanLists: [] };
    navigationAllowed = allowed; panelHost.childNodes = []; renderLiveDetails(); return panelHost;
  }, renderIdle() { liveState = {}; panelHost.childNodes = []; renderLiveDetails(); return panelHost; } };
})()`, { node: detailNode, ...radioLabels, ...systemLabels, ...callFormats, ...callAudioDetails,
  title: dock.title, sourceName: dock.sourceName, sourceId: dock.sourceId, targetId: dock.targetId,
  date: () => '', entityRefHref: reference => reference?.href || '' });
const descendants = node => [node, ...node.children.flatMap(descendants)];
const patchMemberFact = root => {
  const list = descendants(root).find(item => item.tag === 'dl' &&
    item.children.some(child => child.tag === 'dt' && child.textContent === 'Patch members'));
  const index = list.children.findIndex(child => child.tag === 'dt' && child.textContent === 'Patch members');
  return list.children[index + 1];
};
const memberCall = {
  protocol: 'P25', target_form: 'PATCH_GROUP', target_id: 10,
  // Routing alone must never override authoritative unknown captured serving context.
  radio_system_key: 'p25:00001:001', serving_system_key: null,
  entity_ref: { href: '/owner' }, patch_member_count: 2,
  patch_members: [
    { kind: 'talkgroup', id: 20, alias: 'Dispatch',
      canonical_group_identity: { wacn: 0xBEE00, system_id: 0x348, talkgroup_id: 1001 },
      observed_local_id: 20, entity_ref: { href: '/member-group' } },
    { kind: 'radio', id: 501, alias: 'Engine 4',
      canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 9001 },
      observed_working_id: 501, entity_ref: { href: '/member-radio' } }
  ]
};
for (const serving_system_key of [null, 'p25:bee00:348', 'p25:00001:001']) {
  const fact = patchMemberFact(dockDetails.render({ ...memberCall, serving_system_key }));
  const prefix = serving_system_key === 'p25:00001:001' ? 'BEE00.348.' : '';
  assert.equal(fact.textContent, `Dispatch · TGID ${prefix}1001 (Local TG ID 20), ` +
    `Engine 4 · Radio ${prefix}9001 (Working ID 501)`,
  'Actual live detail rows use each member identity and parent captured serving proof.');
  assert.deepEqual(descendants(fact).filter(item => item.tag === 'a').map(item => item.href),
    ['/member-group', '/member-radio'], 'Each patch member retains its own reference.');
}
const homeOnlyMembers = { ...memberCall, patch_members: memberCall.patch_members.map(member => ({
  ...member, id: member.kind === 'radio' ? 9001 : 1001, observed_working_id: null,
  observed_local_id: null, entity_ref: null
})) };
const homeOnlyFact = patchMemberFact(dockDetails.render(homeOnlyMembers));
assert.equal(homeOnlyFact.textContent, 'Dispatch · TGID 1001, Engine 4 · Radio 9001',
  'Home-only member facts never fabricate a local or working ID.');
assert.equal(descendants(homeOnlyFact).filter(item => item.tag === 'a').length, 0,
  'A missing member reference never borrows the call owner link.');
const hiddenLinks = patchMemberFact(dockDetails.render(memberCall, false));
assert.equal(descendants(hiddenLinks).filter(item => item.tag === 'a').length, 0,
  'Patch member navigation uses the existing Radio capability boundary.');
const namedMembers = { ...memberCall, serving_system_key: 'p25:00001:001',
  patch_members: memberCall.patch_members.map(member => ({ ...member, home_system_name: 'County network' })) };
assert.equal(patchMemberFact(dockDetails.render(namedMembers)).textContent,
  'Dispatch · TGID County network · 1001 (Local TG ID 20), ' +
  'Engine 4 · Radio County network · 9001 (Working ID 501)',
  'Known foreign home names remain member context while member aliases stay primary.');
const truncatedMembers = patchMemberFact(dockDetails.render({ ...memberCall,
  patch_members_truncated: true, patch_member_count: 5 }));
assert.match(truncatedMembers.textContent, /Showing 2 of 5 members$/);
assert.equal(patchMemberFact(dockDetails.renderIdle()).textContent, '',
  'Idle and active detail views retain the same Patch members fact label.');

assert.equal(behavior.activityIdentifier({
  protocol: 'P25', source_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 16_777_212 },
  source_observed_working_id: 130_001
}, 16_777_212, 'radio', null, false, 'source'), '16777212 (Working ID 130001)',
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

for (const protocol of ['P25', 'DMR', 'NXDN']) {
  for (const missing of [null, undefined, '']) {
    const row = { protocol, source_radio_id: missing, target_id: missing, target_kind: 'talkgroup' };
    for (const party of ['source', 'target']) {
      assert.equal(behavior.activityPartyValue(row, party), '',
        `${protocol} missing ${party} IDs must remain blank rather than becoming a special zero ID.`);
      const named = behavior.activityPartyValue({ ...row, source_alias_name: 'Dispatch radio',
        target_alias_name: 'Dispatch group' }, party);
      assert.equal(named.primary, party === 'source' ? 'Dispatch radio' : 'Dispatch group');
      assert.equal(named.secondary, '', 'A supplied name without an ID does not fabricate an ID below it.');
    }
  }
}
for (const [protocol, sourceLabel, targetLabel] of [['P25', 'No Unit', 'No Talkgroup'],
  ['DMR', 'Reserved', 'Reserved'], ['NXDN', 'No Unit', 'Null Group']]) {
  const row = { protocol, source_radio_id: 0, target_id: 0, target_kind: 'talkgroup' };
  for (const [party, expectedLabel] of [['source', sourceLabel], ['target', targetLabel]]) {
    const summary = behavior.activityPartyValue(row, party);
    assert.equal(summary.primary, expectedLabel, `${protocol} retains its actual zero-ID meaning.`);
    assert.equal(summary.secondary, '0', 'A reported special zero ID remains explicitly visible.');
  }
}
const namedSpecial = behavior.activityPartyValue({ protocol: 'P25', source_radio_id: 16_777_212,
  source_alias_name: 'Console' }, 'source');
assert.equal(namedSpecial.primary, 'Console');
assert.equal(namedSpecial.secondary, '16777212', 'A named special identity still shows its exact ID.');
for (const missing of [null, undefined, '']) {
  const canonicalRows = [
    ['source', '202', { source_radio_id: missing, source_id: missing,
      source_canonical_identity: { wacn: 1, system_id: 1, subscriber_id: 202 } }],
    ['target', '303', { target_id: missing, target_kind: 'talkgroup', target_form: 'TALKGROUP',
      target_canonical_group_identity: { wacn: 1, system_id: 1, talkgroup_id: 303 } }],
    ['target', '404', { target_id: missing, target_kind: 'radio', target_form: 'RADIO',
      target_canonical_identity: { wacn: 1, system_id: 1, subscriber_id: 404 } }]
  ];
  for (const [party, expectedId, fields] of canonicalRows) {
    const row = { protocol: 'P25', serving_system_key: 'p25:00001:001', ...fields };
    const unnamed = behavior.activityPartyValue(row, party);
    assert.equal(unnamed.primary, expectedId,
      'Activity retains a confirmed permanent identity when its observed local address is absent.');
    assert.equal(unnamed.secondary, '', 'An unnamed confirmed identity appears once, without a fabricated special label.');
    const named = behavior.activityPartyValue({ ...row, source_alias_name: 'Dispatch radio',
      target_alias_name: 'Dispatch target' }, party);
    assert.equal(named.primary, party === 'source' ? 'Dispatch radio' : 'Dispatch target');
    assert.equal(named.secondary, expectedId, 'A named confirmed identity keeps its exact permanent ID below the name.');
    assert.equal(behavior.liveIdentifierAliasValue(row, party), expectedId,
      'Live also retains the supplied permanent identity when the observed local address is absent.');
  }
}

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

// Exercise the actual page renderers through their header boundary. Stopping at beginPage keeps this contract
// independent of the tables below each heading while proving that every protocol uses its saved topology.
const pageHeaders = [];
let pageResponse;
const pageRoute = new URLSearchParams('tab=info');
const pageContext = {
  systemLabels, ...radioLabels, route: pageRoute,
  captureRenderContext: () => ({}), renderIsCurrent: () => true,
  requiredRadioSystem: () => ({ radio_system_key: 'p25:bee00:348' }),
  requiredIdentityKey: () => 'v1-g-bee00-348-7',
  api: async () => pageResponse, channelApiPath: () => '/', radioSystemApiPath: () => '/',
  groupIdentityApiPath: () => '/',
  pageHeader: (title, subtitle) => { pageHeaders.push({ title, subtitle }); return null; },
  beginPage: () => false, fragment: (...values) => values,
  radioSystemContextLink: () => '', radioSystemsDirectoryDetails: () => '',
  trunkedChannelTabItems: () => [{ id: 'info' }], trunkedChannelTabs: () => null,
  channelTabItems: () => [{ id: 'info' }], tabs: () => null, entityTabs: () => null,
  radioSystemTabItems: () => [{ id: 'info' }], radioSystemTabs: () => null
};
const pageFunctions = [
  'protocol', 'protocolFamily', 'isP25', 'identifierNumber', 'identityNumber', 'hex', 'frequency',
  'normalizedSiteText', 'sameSiteText', 'siteNameValue', 'nameValue', 'channelDisplayParts',
  'channelLabel', 'channelValue', 'observedSiteLabel', 'trunkedSiteLabel', 'trunkedVariant',
  'semanticLabel', 'channelLocationIdentity', 'channelSiteIdentity', 'savedChannelScopeLabel',
  'isSavedChannelRadioSystem', 'radioSystemLabel', 'radioSystemPrimaryName', 'radioSystemDisplayName',
  'radioSystemValue', 'aliasLabel', 'identityKind', 'rowGroupIdentityKind', 'groupIdentityLabel',
  'p25CanonicalSubscriber', 'canonicalSubscriberText', 'radioIdentityPrefix', 'workingSubscriberId',
  'radioIdentifierText', 'groupIdentityPrefix', 'groupIdentifierText', 'partyIdentifierText', 'entityPageTitle', 'renderChannel', 'renderTrunkedChannel',
  'renderConventionalChannel', 'renderRadioSystem', 'renderGroupIdentity', 'renderRadio'
];
vm.createContext(pageContext);
vm.runInContext(pageFunctions.map((name) => {
  const start = source.indexOf(`function ${name}(`);
  return `${source.slice(start - 6, start) === 'async ' ? 'async ' : ''}${functionSource(name)}`;
}).join('\n'), pageContext);

async function pageHeading(renderer, response) {
  pageResponse = response;
  const before = pageHeaders.length;
  await pageContext[renderer]();
  assert.equal(pageHeaders.length, before + 1, `${renderer} renders exactly one header`);
  return pageHeaders.at(-1);
}

(async () => {
  const configurationId = '00000000-0000-0000-0000-000000000074';
  pageRoute.set('configuration_id', configurationId);
  const protocols = [
    ['P25', 'P25_PHASE1', 'TRUNKED'], ['P25', 'P25_PHASE2', 'TRUNKED'],
    ['P25', 'P25_CONVENTIONAL', 'CONVENTIONAL'],
    ['DMR', 'DMR', 'TRUNKED'], ['DMR', 'DMR', 'CONVENTIONAL'],
    ['NXDN', 'NXDN', 'TRUNKED'], ['NXDN', 'NXDN', 'CONVENTIONAL'],
    ['AM', 'AM', 'CONVENTIONAL'], ['NBFM', 'NBFM', 'CONVENTIONAL']
  ];
  for (const [protocol, decoder, kind] of protocols) {
    const channel = { configuration_id: configurationId, protocol, decoder, channel_kind: kind,
      name: ' North ', site_name: 'North', source_frequencies_hz: [460012500, 461012500] };
    const heading = await pageHeading('renderChannel', { channel });
    assert.equal(heading.title, `${kind === 'TRUNKED' ? 'Site' : 'Channel'}: North`,
      `${decoder} ${kind} uses the saved topology even with multiple frequencies`);
  }
  for (const auxiliary of ['DCS', 'FLEETSYNC2', 'LJ_1200', 'MDC1200', 'TAIT_1200']) {
    const heading = await pageHeading('renderChannel', { channel: { configuration_id: configurationId,
      protocol: 'NBFM', decoder: 'NBFM', channel_kind: 'CONVENTIONAL', name: 'Dispatch',
      auxiliary_decoders: [auxiliary] } });
    assert.equal(heading.title, 'Channel: Dispatch', `${auxiliary} does not change primary channel topology`);
  }
  for (const kind of [undefined, 'FUTURE']) {
    const heading = await pageHeading('renderChannel', { channel: { configuration_id: configurationId,
      protocol: 'DMR', decoder: 'DMR', channel_kind: kind, name: 'Dispatch' } });
    assert.equal(heading.title, 'Channel: Dispatch', 'Missing or unknown topology does not imply a trunked site');
  }
  assert.equal((await pageHeading('renderChannel', { channel: { configuration_id: configurationId,
    protocol: 'NBFM', channel_kind: 'CONVENTIONAL', name: ' ', primary_frequency_hz: 460012500 }
  })).title, 'Channel: 460.01250 MHz', 'An unnamed conventional channel falls back to its frequency');
  assert.equal((await pageHeading('renderChannel', { channel: { configuration_id: configurationId,
    protocol: 'NBFM', channel_kind: 'CONVENTIONAL', name: null }
  })).title, 'Channel Details', 'Missing names do not produce Channel: Channel');

  for (const system of [
    { protocol: 'P25', radio_system_key: 'p25:bee00:348', wacn: 0xbee00, system_id: 0x348 },
    nativeDmr,
    { protocol: 'NXDN', radio_system_key: 'nxdn-c:regional:123', location_category: 'regional', system_id: 123 }
  ]) {
    assert.equal((await pageHeading('renderRadioSystem', { ...system, system_name: 'Metro' })).title,
      'System: Metro', `${system.protocol} native system retains its type when named`);
    const heading = await pageHeading('renderRadioSystem', system);
    assert.match(heading.title, /^System: /, 'An unmatched system identity keeps its type prefix');
    assert.ok(heading.subtitle.startsWith(system.protocol), 'System context includes its protocol');
  }
  for (const [protocol, family] of [['DMR', 'dmr'], ['NXDN', 'nxdn-c'], ['NXDN', 'nxdn-d']]) {
    const scoped = { protocol, radio_system_key: `${family}:channel:${configurationId}` };
    assert.equal((await pageHeading('renderRadioSystem', { ...scoped, system_name: 'Metro' })).title,
      'Channel Activity: Metro', 'A saved channel scope never presents itself as a proven native system');
    assert.equal((await pageHeading('renderRadioSystem', { ...scoped, channel_names: 'North' })).title,
      'Channel Activity: North');
    assert.equal((await pageHeading('renderRadioSystem', scoped)).title, `Channel Activity: ${protocol}`,
      'Unnamed saved channel scopes fall back without repeating their type');
  }

  for (const protocol of ['P25', 'DMR', 'NXDN']) {
    for (const [kind, label] of [['talkgroup', 'Talkgroup'], ['patch_group', 'Patch Group']]) {
      const group = { protocol, group_identity_kind: kind, native_id: 7 };
      assert.equal((await pageHeading('renderGroupIdentity', { ...group, alias_name: 'Dispatch' })).title,
        `${label}: Dispatch`, `${protocol} aliases retain their actual group kind`);
      assert.equal((await pageHeading('renderGroupIdentity', group)).title, `${label}: 7`,
        'Unmatched identities get one prefix and their formatted number');
    }
  }
  assert.equal((await pageHeading('renderGroupIdentity', { protocol: 'NXDN',
    address_domain: 'nxdn_type_d', group_identity_kind: 'talkgroup', native_id: (12 << 11) | 34 }
  )).title, 'Talkgroup: 12-0034', 'NXDN Type-D retains its home/repeater group format');

  const radio = { protocol: 'DMR', native_id: 42 };
  assert.equal((await pageHeading('renderRadio', { ...radio, alias_name: 'Engine', last_talker_alias: 'ENG' })).title,
    'Radio: Engine', 'A configured radio alias keeps its prefix and wins over OTA text');
  assert.equal((await pageHeading('renderRadio', { ...radio, last_talker_alias: 'ENG' })).title,
    'Radio: ENG', 'An OTA-only radio name keeps its type prefix');
  assert.equal((await pageHeading('renderRadio', { ...radio, alias_name: ' ', last_talker_alias: ' ' })).title,
    'Radio: 42', 'Whitespace names fall through to the usable ID');
  assert.equal((await pageHeading('renderRadio', radio)).title, 'Radio: 42');
  assert.equal((await pageHeading('renderRadio', { protocol: 'P25', radio_system_key: 'p25:bee00:348', native_id: 130001,
    canonical_identity: { wacn: 0xbee00, system_id: 0x348, subscriber_id: 9601699 },
    home_system_name: 'County Radio', working_subscriber_id: 130001 })).title,
    'Radio: 9601699 (Working ID 130001)',
  'Local P25 radio headings retain the permanent number and a different Working ID');
  assert.equal((await pageHeading('renderRadio', { protocol: 'NXDN',
    address_domain: 'nxdn_type_d', native_id: (12 << 11) | 34 })).title, 'Radio: 12-0034');
})().catch((error) => { console.error(error); process.exitCode = 1; });

const localRadio = {
  protocol: 'P25', radio_system_key: 'p25:bee00:348',
  canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 4326018 },
  observed_working_id: 4326018
};
assert.equal(behavior.radioIdentifierText(localRadio, 4326018), '4326018');
for (const observed_working_id of [true, false, [], [501], {}, '', ' ']) {
  assert.equal(behavior.radioIdentifierText({ ...localRadio, observed_working_id }, 4326018), '4326018',
    'Invalid typed Working ID fields must not create a different radio display.');
}
assert.equal(behavior.radioIdentifierText({ ...localRadio, radio_system_key: '',
  entity_ref: { kind: 'radio', radio_system_key: localRadio.radio_system_key,
    identity_key: 'v1-r-bee00-348-4326018' } }, 4326018), '4326018',
  'Radio detail navigation context uses the same compact local display.');
assert.equal(behavior.radioIdentifierText({ protocol: 'P25', radio_system_key: localRadio.radio_system_key },
  4326018), behavior.radioIdentifierText(localRadio, 4326018),
  'Complete registration and ordinary local activity should display the same radio number.');
assert.equal(behavior.radioIdentifierText({ ...localRadio, observed_working_id: 12345 }, 12345),
  '4326018 (Working ID 12345)');
const foreignRadio = { ...localRadio, radio_system_key: 'p25:00001:047', home_system_name: 'County Radio' };
assert.equal(behavior.radioIdentifierText(foreignRadio, 4326018), 'County Radio · 4326018',
  'Foreign radios retain their home system even when the numeric IDs match.');
assert.equal(behavior.radioIdentifierText({ ...foreignRadio, home_system_name: '', radio_system_key: '' },
  4326018), '4326018', 'Unknown serving context cannot add a home prefix.');
assert.equal(behavior.radioIdentifierText({ ...localRadio, radio_system_key: 'p25:00001:348' },
  4326018), 'BEE00.348.4326018', 'Matching SysID alone does not establish local ownership.');
const prefixedLocal = { protocol: 'P25', radio_system_key: localRadio.radio_system_key,
  source_id: 4326018, source_canonical_identity: localRadio.canonical_identity,
  source_observed_working_id: 4326018 };
assert.equal(behavior.activityIdentifier(prefixedLocal, 4326018, 'radio', null, false, 'source'), '4326018');
assert.equal(dock.sourceId(prefixedLocal), '4326018', 'The shared audio dock follows the table display rule.');
const liveSource = { protocol: 'P25', source_id: 4326018, source_form: 'RADIO',
  source_canonical_identity: localRadio.canonical_identity, source_observed_working_id: 4326018,
  source_home_system_name: 'County Radio',
  source_entity_ref: { kind: 'radio', radio_system_key: 'p25:bee00:348',
    identity_key: 'v1-r-bee00-348-4326018' } };
assert.equal(behavior.liveIdentifierAliasValue(liveSource, 'source'), '4326018',
  'Actual Live rows carry serving scope in the source drilldown, not in a direct row field.');
assert.equal(behavior.liveIdentifierAliasValue({ ...liveSource,
  source_canonical_identity: { ...localRadio.canonical_identity, system_id: 0x349 } }, 'source'),
  'County Radio · 4326018', 'A foreign radio keeps home context despite the same numeric ID.');
assert.equal(behavior.liveIdentifierAliasValue({ ...liveSource, source_observed_working_id: 501 }, 'source'),
  '4326018 (Working ID 501)');
assert.equal(behavior.liveIdentifierAliasValue({ ...liveSource, source_entity_ref: null }, 'source'),
  '4326018', 'Retained rows without safe navigation must not inherit the current table scope.');
const liveTarget = { protocol: 'P25', target_id: 4326018, target_form: 'RADIO',
  target_canonical_identity: localRadio.canonical_identity, target_home_system_name: 'County Radio',
  target_entity_ref: liveSource.source_entity_ref };
assert.equal(behavior.liveIdentifierAliasValue(liveTarget, 'target'), '4326018');
assert.equal(behavior.liveIdentifierAliasValue({ ...liveTarget, target_entity_ref: null,
  source_entity_ref: liveSource.source_entity_ref }, 'target'), '4326018',
  'A target radio must never borrow the source radio serving scope.');
assert.equal(behavior.liveIdentifierAliasValue({ ...liveSource, radio_system_key: 'p25:00001:047' }, 'source'),
  'County Radio · 4326018', 'Explicit receiving scope wins over a conflicting endpoint reference.');
const eventRadio = { text: 'BEE00.348.4326018 (Working ID 4326018)', form: 'RADIO', protocol: 'APCO25',
  canonical_identity: localRadio.canonical_identity, observed_working_id: 4326018 };
const rawEvent = { protocol: 'APCO25', from_party: [eventRadio], from_identifiers: eventRadio.text,
  to_party: [{ text: '91', form: 'TALKGROUP', protocol: 'APCO25' }, eventRadio],
  to_identifiers: `91, ${eventRadio.text}`, details: `ACCEPTED UNIT REGISTRATION - UNIT ID:${eventRadio.text}` };
const capturedEvent = rawEvent;
assert.equal(capturedEvent.radio_system_key, undefined,
  'A live table selection must not invent serving-system facts for a captured event.');
assert.equal(behavior.liveEventPartyText({ ...rawEvent, serving_system_key: null,
  radio_system_key: 'p25:00001:047' }, 'from'), '4326018',
  'Captured unknown context takes precedence over a routing scope.');
assert.equal(behavior.liveEventPartyText(capturedEvent, 'from'), '4326018',
  'Events use complete radio facts instead of displaying decoder-formatted text.');
assert.equal(behavior.liveEventPartyText(capturedEvent, 'to'), '91, 4326018',
  'Mixed talkgroup and radio parties retain the talkgroup unchanged.');
assert.equal(behavior.liveEventDetailsText(capturedEvent), 'ACCEPTED UNIT REGISTRATION - UNIT ID:4326018');
assert.equal(capturedEvent.details, rawEvent.details, 'Original decoder detail text remains available for inspection.');
assert.equal(behavior.liveEventPartyText({ ...rawEvent, radio_system_key: 'p25:00001:047' }, 'from'),
  'BEE00.348.4326018', 'Legacy events retain their explicit receiving scope.');
assert.equal(behavior.liveEventPartyText(rawEvent, 'from'),
  '4326018', 'Unknown serving scope suppresses the home prefix and omits an equal Working ID.');
assert.equal(behavior.liveEventPartyText({ ...capturedEvent, from_party: [
  { ...eventRadio, observed_working_id: 501 }] }, 'from'), '4326018 (Working ID 501)');
assert.equal(behavior.liveEventPartyText({ ...capturedEvent, from_party: [
  { ...eventRadio, form: 'TALKGROUP' }] }, 'from'), eventRadio.text,
  'Dotted text and unrelated identifier types do not establish a radio identity.');
assert.equal(behavior.liveEventPartyText({ ...capturedEvent, from_party: [
  { ...eventRadio, canonical_identity: null }] }, 'from'), eventRadio.text);
assert.equal(behavior.liveEventDetailsText({ ...capturedEvent, details: `${eventRadio.text}9` }),
  `${eventRadio.text}9`, 'A known radio token cannot replace part of a longer token.');
assert.equal(behavior.liveEventDetailsText({ ...capturedEvent, details: 'UNIT ID:BEE00.348.12345' }),
  'UNIT ID:BEE00.348.12345', 'Only typed radio identifiers belonging to this event are rewritten.');
assert.equal(behavior.liveEventPartyText({ ...rawEvent, from_party: undefined }, 'from'), eventRadio.text,
  'The previous event contract remains readable without guessing identities from arbitrary strings.');
assert.equal(behavior.liveEventPartyText({ ...rawEvent, from_party: [] }, 'from'), eventRadio.text,
  'Legacy producers with an empty typed list still display their original party text.');
assert.equal(behavior.liveEventDetailsText({ ...rawEvent, from_party: {}, to_party: null }), rawEvent.details);
const immutableScope = { ...rawEvent, serving_system_key: 'p25:bee00:348' };
assert.equal(behavior.liveEventPartyText(immutableScope, 'from'), '4326018',
  'A captured event retains its receiving system independently of later table changes.');
const patchRadio = { ...eventRadio, observed_working_id: 501,
  text: 'BEE00.348.4326018 (Working ID 501)' };
const patchText = `P:91 [92] [${eventRadio.text}, ${patchRadio.text}]`;
const patchEvent = { ...capturedEvent, from_party: [], from_identifiers: '',
  to_party: [{ form: 'PATCH_GROUP', text: patchText, radio_members: [eventRadio, patchRadio] }],
  to_identifiers: patchText, details: `PATCH ADDED: ${patchText}` };
assert.equal(behavior.liveEventPartyText(patchEvent, 'to'), 'P:91 [92] [4326018, 4326018 (Working ID 501)]',
  'Known radio members inside a patch use the same local/Working ID rule without changing talkgroups.');
assert.equal(behavior.liveEventDetailsText(patchEvent), 'PATCH ADDED: P:91 [92] [4326018, 4326018 (Working ID 501)]');
assert.equal(behavior.liveEventPartyText({ ...patchEvent, to_party: [{ form: 'TALKGROUP', text: patchText,
  radio_members: [eventRadio] }] }, 'to'), patchText,
  'Only an actual typed patch establishes radio members.');
const structuredPatch = { ...patchEvent.to_party[0], patch_group: '91', talkgroup_members: ['92'],
  text: `P:91 [92] [${eventRadio.text.slice(0, 17)}` };
assert.equal(behavior.liveEventPartyText({ ...patchEvent, to_party: [structuredPatch] }, 'to'),
  'P:91 [92] [4326018, 4326018 (Working ID 501)]',
  'Structured patch members stay complete even when the bounded raw diagnostic text ends mid-radio.');
assert.equal(behavior.liveEventPartyText({ ...patchEvent, to_party: [{ ...structuredPatch,
  talkgroup_members_truncated: true, radio_members_truncated: true }] }, 'to'),
  'P:91 [92, …] [4326018, 4326018 (Working ID 501), …]',
  'A capped member list ends after a whole identity and marks the omitted members.');
const qualifiedPatch = { ...structuredPatch, patch_group: 'ISSI 77(781824.840.7001)',
  talkgroup_members: ['ISSI 781824.840.7002', 'ISSI 79(781824.840.7003)'], radio_members: [],
  text: 'P:ISSI 77(781824.840.7001) [ISSI 781824.840.7002, ISSI 79(781824.840.7003)]' };
for (const servingKey of [null, 'p25:bee00:348']) {
  const event = { ...patchEvent, serving_system_key: servingKey, to_party: [qualifiedPatch],
    details: qualifiedPatch.text };
  assert.equal(behavior.liveEventPartyText(event, 'to'), 'P:7001 (Local TG ID 77) [7002, 7003 (Local TG ID 79)]');
  assert.equal(behavior.liveEventDetailsText(event), 'P:7001 (Local TG ID 77) [7002, 7003 (Local TG ID 79)]');
  assert.equal(behavior.liveEventMatchesQuery(event, '781824.840.7001'), true,
    'The raw full destination remains searchable after its visible prefix is suppressed.');
}
assert.equal(behavior.liveEventPartyText({ ...patchEvent, serving_system_key: 'p25:bee00:349',
  to_party: [qualifiedPatch] }, 'to'), 'P:BEE00.348.7001 (Local TG ID 77) [BEE00.348.7002, BEE00.348.7003 (Local TG ID 79)]');
const pickerContext = { radioSystemKey: localRadio.radio_system_key };
const suggestion = behavior.activityIdentitySuggestion({ ...localRadio, native_id: 4326018,
  identity_key: 'v1-r-bee00-348-4326018', alias_name: 'Engine 42' }, 'radio', pickerContext);
assert.equal(suggestion.label, 'Radio 4326018 · Engine 42');
assert.equal(suggestion.key, 'v1-r-bee00-348-4326018', 'Short labels preserve the complete filter key.');
assert.equal(behavior.activityIdentityInitialSelection(suggestion.key, 'radio', pickerContext).label,
  'Radio 4326018', 'A saved local filter should not regain the redundant system prefix.');
systemLabels.rememberSystemNames([{ radio_system_key: 'p25:bee00:348', system_name: 'County Radio' }]);
const foreignEvent = { ...capturedEvent, radio_system_key: 'p25:00001:047' };
assert.equal(behavior.liveEventPartyText(foreignEvent, 'from'), 'County Radio · 4326018');
assert.equal(behavior.liveEventPartyText({ ...patchEvent, radio_system_key: foreignEvent.radio_system_key }, 'to'),
  'P:91 [92] [County Radio · 4326018, County Radio · 4326018 (Working ID 501)]');
assert.equal(behavior.liveEventMatchesQuery(foreignEvent, 'county radio'), true,
  'Event search includes friendly home names shown to the user.');
assert.equal(behavior.liveEventMatchesQuery(foreignEvent, 'bee00.348.4326018'), true,
  'The original full address remains searchable.');
assert.equal(behavior.liveEventMatchesQuery(capturedEvent, '4326018'), true);
assert.equal(behavior.activityIdentityInitialSelection(suggestion.key, 'radio',
  { radioSystemKey: 'p25:00001:047' }).label, 'Radio County Radio · 4326018');

const links = vm.runInNewContext(`${functionSource('radioDisplayId')}\n${functionSource('radioLink')}` +
  '\n({radioLink});', { ...radioLabels, radioIdentifierText: behavior.radioIdentifierText,
    ACCESS_CAPABILITIES: { RADIO: 'radio' }, capabilityAllowed: () => true,
    entityTarget: reference => reference?.identity_key || '',
    anchor: (label, target) => ({ label, target }) });
const relationship = { protocol: 'P25', radio_native_id: 4326018,
  radio_canonical_identity: localRadio.canonical_identity };
const localRef = { kind: 'radio', radio_system_key: 'p25:bee00:348',
  identity_key: 'v1-r-bee00-348-4326018' };
assert.equal(links.radioLink(relationship, 4326018, undefined, localRef).label, '4326018',
  'A Talkgroup Radios link supplies its receiving scope when the row omits it.');
assert.equal(links.radioLink({ ...relationship, radio_system_key: 'p25:00001:047' }, 4326018,
  undefined, localRef).label, 'County Radio · 4326018',
  'An explicit receiving scope wins over conflicting navigation context.');
assert.equal(links.radioLink(relationship, 4326018, undefined, localRef).target, localRef.identity_key,
  'The shorter visible number does not replace its complete drilldown identity.');
const sharedLocal = { protocol: 'P25', serving_system_key: 'p25:00001:047',
  source_radio_id: 501, source_id: 501, target_id: 501,
  source_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 9001 },
  target_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 9002 },
  source_observed_working_id: 501, target_observed_working_id: 501 };
assert.equal(links.radioLink(sharedLocal, 501, undefined, localRef, 'source').label,
  'County Radio · 9001 (Working ID 501)');
assert.equal(links.radioLink(sharedLocal, 501, undefined, localRef, 'target').label,
  'County Radio · 9002 (Working ID 501)',
  'A direct target uses its explicit party facts even when the source has the same local ID.');
assert.equal(links.radioLink({ protocol: 'P25', native_id: 9001,
  canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 9001 },
  entity_ref: { kind: 'radio', radio_system_key: 'p25:bee00:348', identity_key: 'v1-r-bee00-348-9001' }
}, 42, undefined, undefined, 'last_peer'), '42',
  'A peer without its own reference remains plain text and cannot borrow the owner’s drilldown.');
assert.equal(links.radioLink({ protocol: 'P25', native_id: 9001, entity_ref: localRef,
  target_id: 501, target_canonical_identity: sharedLocal.target_canonical_identity,
  target_observed_working_id: 501 }, 501, undefined, undefined, 'target'), '9002 (Working ID 501)',
  'A missing target reference never falls back to the directory owner’s link.');
const groupLinks = vm.runInNewContext(`${functionSource('groupIdentityDisplayId')}\n${functionSource('groupIdentityLink')}` +
  '\n({groupIdentityLink});', { groupIdentifierText: behavior.groupIdentifierText,
    rowGroupIdentityKind: () => 'talkgroup', specialIdentifierLabel: () => '',
    ACCESS_CAPABILITIES: { RADIO: 'radio' }, capabilityAllowed: () => true,
    entityTarget: reference => reference?.identity_key || '', anchor: (label, target) => ({ label, target }) });
const groupOwner = { protocol: 'P25', native_id: 7001,
  canonical_group_identity: { wacn: 0xBEE00, system_id: 0x348, talkgroup_id: 7001 },
  entity_ref: { kind: 'talkgroup', radio_system_key: 'p25:bee00:348', identity_key: 'v1-g-bee00-348-7001' } };
assert.equal(groupLinks.groupIdentityLink(groupOwner, 42, undefined, undefined, 'last_talkgroup'), '42',
  'A missing group-party reference cannot borrow the owner’s drilldown.');
assert.equal(groupLinks.groupIdentityLink(groupOwner).target, 'v1-g-bee00-348-7001',
  'Own directory rows keep their complete identity links.');
const directoryColumns = vm.runInNewContext(`${functionSource('channelGroupIdentityColumns')}\n` +
  `${functionSource('channelRadioColumns')}\n({channelGroupIdentityColumns, channelRadioColumns});`, {
  radioLink: links.radioLink, groupIdentityLink: groupLinks.groupIdentityLink,
  aliasLabel: row => row.alias_name || ''
});
const sourceAliasColumn = directoryColumns.channelGroupIdentityColumns().find(column => column.id === 'source-alias');
const groupAliasColumn = directoryColumns.channelRadioColumns().find(column => column.id === 'talkgroup-name');
const lastSourceRow = { ...groupOwner, last_source_radio_id: 42, last_source_alias_name: 'Engine 42',
  last_source_entity_ref: { identity_key: 'v1-r-bee00-348-42' } };
assert.equal(sourceAliasColumn.render(lastSourceRow).target, lastSourceRow.last_source_entity_ref.identity_key,
  'The directory Last Source alias links to that source instead of its group owner.');
assert.equal(sourceAliasColumn.render({ ...lastSourceRow, last_source_entity_ref: undefined }), 'Engine 42',
  'A missing Last Source reference keeps its alias as plain text.');
const lastGroupRow = { protocol: 'P25', native_id: 9001, entity_ref: localRef,
  last_talkgroup_id: 42, last_talkgroup_alias_name: 'Dispatch',
  last_talkgroup_entity_ref: { identity_key: 'v1-g-bee00-348-42' } };
assert.equal(groupAliasColumn.render(lastGroupRow).target, lastGroupRow.last_talkgroup_entity_ref.identity_key,
  'The directory Last Talkgroup alias links to that group instead of its radio owner.');
assert.equal(groupAliasColumn.render({ ...lastGroupRow, last_talkgroup_entity_ref: undefined }), 'Dispatch',
  'A missing Last Talkgroup reference keeps its alias as plain text.');
assert.equal(behavior.groupIdentifierText({ ...sharedLocal,
  source_canonical_group_identity: { wacn: 0xBEE00, system_id: 0x348, talkgroup_id: 7001 },
  target_canonical_group_identity: { wacn: 0xBEE00, system_id: 0x348, talkgroup_id: 7002 },
  source_observed_local_id: 501, target_observed_local_id: 501 }, 501, 'target'),
  'County Radio · 7002 (Local TG ID 501)', 'Group targets also use explicit party ownership.');
assert.equal(behavior.activityIdentitySuggestion({ ...localRadio, radio_system_key: '',
  system_key: 'p25:00001:047', native_id: 4326018, identity_key: suggestion.key }, 'radio', pickerContext).displayId,
  'County Radio · 4326018', 'An explicit system scope is preserved before picker context is used.');
for (const invalid of [false, true, [], {}]) {
  assert.equal(behavior.radioIdentifierText({ ...localRadio,
    canonical_identity: { ...localRadio.canonical_identity, wacn: invalid } }, 4326018), '4326018',
    'Invalid typed home fields do not become fabricated complete identities.');
}
const targetFacts = behavior.liveIdentityFacts({ protocol: 'P25', target_form: 'RADIO', target_id: 501,
  target_canonical_identity: { wacn: 0xBEE01, system_id: 0x348, subscriber_id: 501 },
  home_system_name: 'Source home name', home_system_entity_ref: { href: '/source-home' } }, 'target');
const targetHome = targetFacts.values.find(([label]) => label === 'Home System')[1];
assert.equal(targetHome.primary, 'BEE01.348', 'Target details do not borrow the source home-system name.');
assert.equal(targetHome.target, '', 'Target details do not navigate to the source home system.');
assert.equal(targetFacts.values.find(([label]) => label === 'Permanent Radio ID')[1], 'BEE01.348.501',
  'Explicit identity inspection retains the exact permanent address.');

const fullGroup = { protocol: 'P25', target_id: 77, target_form: 'TALKGROUP',
  target_canonical_group_identity: { wacn: 0xBEE00, system_id: 0x348, talkgroup_id: 7001 },
  target_observed_local_id: 77, target_home_system_name: 'County Radio' };
for (const serving_system_key of [null, 'p25:bee00:348']) {
  assert.equal(behavior.partyIdentifierText({ ...fullGroup, serving_system_key }, 77, 'target'),
    '7001 (Local TG ID 77)');
}
assert.equal(behavior.partyIdentifierText({ ...fullGroup, serving_system_key: 'p25:00001:047' }, 77, 'target'),
  'County Radio · 7001 (Local TG ID 77)');
assert.equal(behavior.liveIdentityFacts({ ...fullGroup, target_identity_source: 'group_affiliation_mapping' },
  'target').values.find(([label]) => label === 'ID Source')[1], 'Group affiliation response');
assert.equal(behavior.liveIdentityFacts({ ...fullGroup, target_identity_source: 'local_id' },
  'target').values.find(([label]) => label === 'ID Source')[1], 'Local talkgroup ID only');
assert.equal(behavior.groupIdentifierText({ protocol: 'P25', native_id: 7001,
  identity_key: 'v1-p-bee00-348-7001', serving_system_key: null }, 7001), '7001');
assert.equal(behavior.radioIdentifierText({ protocol: 'P25', native_id: 9001,
  canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 9001 } }, 42), '42',
  'A peer/member scalar cannot reuse a row’s own canonical radio ID.');
const typedGroupEvent = { protocol: 'P25', serving_system_key: 'p25:00001:047',
  to_party: [{ form: 'TALKGROUP', text: 'ISSI 77(781824.840.7001)',
    canonical_group_identity: fullGroup.target_canonical_group_identity, observed_local_id: 77 }] };
assert.equal(behavior.liveEventPartyText(typedGroupEvent, 'to'), 'County Radio · 7001 (Local TG ID 77)');
assert.equal(behavior.liveEventPartyText({ ...typedGroupEvent, serving_system_key: null }, 'to'),
  '7001 (Local TG ID 77)');
const groupSuggestion = behavior.activityIdentitySuggestion({ protocol: 'P25', native_id: 7001,
  identity_key: 'v1-g-bee00-348-7001', serving_system_key: null, alias_name: 'Dispatch' }, 'talkgroup',
  { radioSystemKey: 'p25:00001:047' });
assert.equal(groupSuggestion.label, 'Talkgroup 7001 · Dispatch');
assert.equal(groupSuggestion.key, 'v1-g-bee00-348-7001', 'Group filters preserve the complete canonical key.');

const catalogEvent = { protocol: 'P25', serving_system_key: 'p25:bee00:349', details: 'Unchanged decoder detail',
  from_identifiers: 'ROAM 501(703710.291.42)', from_party: [{ form: 'RADIO', protocol: 'APCO25',
    text: 'ROAM 501(703710.291.42)', canonical_identity: { wacn: 0xABCDE, system_id: 0x123, subscriber_id: 42 },
    observed_working_id: 501, catalog_name: 'Catalog Radio / Other Radio' }],
  to_party: [{ form: 'PATCH_GROUP', text: 'raw patch', patch_group: '77',
    canonical_group_identity: { wacn: 0xABCDE, system_id: 0x124, talkgroup_id: 7001 },
    observed_local_id: 77, catalog_name: 'Catalog Group', radio_members: [], talkgroup_members: [{
      form: 'TALKGROUP', text: 'raw group',
      canonical_group_identity: { wacn: 0xABCDE, system_id: 0x125, talkgroup_id: 7002 },
      observed_local_id: 78, catalog_name: 'Catalog Member' }] }] };
const rawCatalogEvent = JSON.stringify(catalogEvent);
assert.equal(behavior.liveEventPartyText(catalogEvent, 'from'),
  'Catalog Radio / Other Radio · 42 (Working ID 501)');
assert.equal(behavior.liveEventPartyText(catalogEvent, 'to'),
  'P:Catalog Group · 7001 (Local TG ID 77) [Catalog Member · 7002 (Local TG ID 78)]');
assert.equal(behavior.liveEventPartyText({ ...catalogEvent, serving_system_key: null }, 'from'),
  '42 (Working ID 501)', 'A catalog home name does not establish the serving system.');
assert.equal(JSON.stringify(catalogEvent), rawCatalogEvent, 'Catalog formatting does not rewrite decoder fields.');
systemLabels.rememberSystemNames({ radio_system_key: 'p25:abcde:123', system_name: 'P25 ABCDE-123' });
assert.equal(behavior.liveEventPartyText(catalogEvent, 'from'), 'P25 ABCDE-123 · 42 (Working ID 501)',
  'Every saved label, including protocol-looking text, takes precedence over catalog suggestions.');
const catalogRestRow = { protocol: 'P25', serving_system_key: 'p25:bee00:349', source_id: 501,
  source_canonical_identity: catalogEvent.from_party[0].canonical_identity, source_observed_working_id: 501,
  source_home_system_name: 'Catalog Radio / Other Radio', source_home_system_name_source: 'catalog',
  source_home_system: { radio_system_key: 'p25:abcde:123', system_name: 'Catalog Radio / Other Radio',
    system_name_source: 'catalog' } };
assert.equal(behavior.radioIdentifierText(catalogRestRow, 501, 'source'), 'P25 ABCDE-123 · 42 (Working ID 501)',
  'A REST fallback field cannot override an already known configured home name.');
assert.equal(behavior.liveIdentityFacts({ ...catalogRestRow, source_form: 'RADIO' }, 'source').values
  .find(([label]) => label === 'Home System')[1].primary, 'P25 ABCDE-123');
assert.equal(behavior.liveEventIdentifierText(catalogEvent, { ...catalogEvent.from_party[0],
  home_system_name: 'Explicit saved label' }), 'Explicit saved label · 42 (Working ID 501)');
const catalogLiveRow = { protocol: 'P25', serving_system_key: 'p25:bee00:349', target_id: 77,
  target_form: 'TALKGROUP', target_canonical_group_identity: catalogEvent.to_party[0].canonical_group_identity,
  target_observed_local_id: 77, target_home_catalog_name: 'Catalog Group' };
assert.equal(behavior.groupIdentifierText(catalogLiveRow, 77, 'target'),
  'Catalog Group · 7001 (Local TG ID 77)');
assert.equal(behavior.liveIdentityFacts(catalogLiveRow, 'target').values
  .find(([label]) => label === 'Home System')[1].primary, 'Catalog Group');
