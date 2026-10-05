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
const radioLabelsSource = fs.readFileSync(path.resolve(__dirname,
  '../../../../stats-web/assets/core/radio-labels.js'), 'utf8');
const radioLabels = vm.runInNewContext(radioLabelsSource.replace(/^export .*;$/m, '') +
  '\n({formatP25RadioIdentifier, p25ServingSystemKey});');

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
  function capabilityAllowed() { return true; }
  const ACCESS_CAPABILITIES = { RADIO: 'radio' };
  function entityRefHref(reference) { return reference?.href || ''; }
  function radioSystemContextLink() { return 'Receiving system'; }
  function identitySummaryValue(primary, secondary, target) { return {primary, secondary, target}; }
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
  ${functionSource('activityIdentityKey')}
  ${functionSource('activityIdentityKindFromKey')}
  ${functionSource('activityIdentityKindLabel')}
  ${functionSource('activityIdentitySuggestion')}
  ${functionSource('activityIdentityInitialSelection')}
  ${functionSource('liveIdentityType')}
  ${functionSource('liveIdentityLabel')}
  ${functionSource('liveIdentityActionTitle')}
  ${functionSource('p25IdentityEvidenceLabel')}
  ${functionSource('liveIdentityFacts')}
  return { radioSystemLabel, trunkedSiteLabel, dashboardChannelContext,
    channelDirectoryRfIdentity, channelLocationIdentity, dmrChannelDetailRows,
    scannerNetworkSiteIdentity, observedGroupIdentityKey, radioIdentifierText, liveIdentityActionTitle,
    activityIdentifier, activitySourceAlias, activitySourceTalkerAlias, activityTargetAlias,
    activityIdentitySuggestion, activityIdentityInitialSelection, liveIdentityFacts };
})()`, { URLSearchParams, systemLabels, ...radioLabels });

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
  'radioIdentifierText', 'entityPageTitle', 'renderChannel', 'renderTrunkedChannel',
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
    working_subscriber_id: 130001 })).title, 'Radio: 9601699 (Working ID 130001)',
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
assert.equal(behavior.radioIdentifierText({ protocol: 'P25', radio_system_key: localRadio.radio_system_key },
  4326018), behavior.radioIdentifierText(localRadio, 4326018),
  'Complete registration and ordinary local activity should display the same radio number.');
assert.equal(behavior.radioIdentifierText({ ...localRadio, observed_working_id: 12345 }, 12345),
  '4326018 (Working ID 12345)');
const foreignRadio = { ...localRadio, radio_system_key: 'p25:00001:047', home_system_name: 'County Radio' };
assert.equal(behavior.radioIdentifierText(foreignRadio, 4326018), 'County Radio · 4326018',
  'Foreign radios retain their home system even when the numeric IDs match.');
assert.equal(behavior.radioIdentifierText({ ...foreignRadio, home_system_name: '', radio_system_key: '' },
  4326018), 'BEE00.348.4326018', 'Unknown serving context retains a full address.');
assert.equal(behavior.radioIdentifierText({ ...localRadio, radio_system_key: 'p25:00001:348' },
  4326018), 'BEE00.348.4326018', 'Matching SysID alone does not establish local ownership.');
const prefixedLocal = { protocol: 'P25', radio_system_key: localRadio.radio_system_key,
  source_id: 4326018, source_canonical_identity: localRadio.canonical_identity,
  source_observed_working_id: 4326018 };
assert.equal(behavior.activityIdentifier(prefixedLocal, 4326018, 'radio', null, false, 'source'), '4326018');
assert.equal(dock.sourceId(prefixedLocal), '4326018', 'The shared audio dock follows the table display rule.');
const pickerContext = { radioSystemKey: localRadio.radio_system_key };
const suggestion = behavior.activityIdentitySuggestion({ ...localRadio, native_id: 4326018,
  identity_key: 'v1-r-bee00-348-4326018', alias_name: 'Engine 42' }, 'radio', pickerContext);
assert.equal(suggestion.label, 'Radio 4326018 · Engine 42');
assert.equal(suggestion.key, 'v1-r-bee00-348-4326018', 'Short labels preserve the complete filter key.');
assert.equal(behavior.activityIdentityInitialSelection(suggestion.key, 'radio', pickerContext).label,
  'Radio 4326018', 'A saved local filter should not regain the redundant system prefix.');
systemLabels.rememberSystemNames([{ radio_system_key: 'p25:bee00:348', system_name: 'County Radio' }]);
assert.equal(behavior.activityIdentityInitialSelection(suggestion.key, 'radio',
  { radioSystemKey: 'p25:00001:047' }).label, 'Radio County Radio · 4326018');

const links = vm.runInNewContext(`${functionSource('radioDisplayId')}\n${functionSource('radioLink')}` +
  '\n({radioLink});', { ...radioLabels, radioIdentifierText: behavior.radioIdentifierText,
    ACCESS_CAPABILITIES: { RADIO: 'radio' }, capabilityAllowed: () => true,
    entityTarget: reference => reference.identity_key,
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
