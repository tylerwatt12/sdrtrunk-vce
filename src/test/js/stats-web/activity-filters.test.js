'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const applicationPath = process.argv[2];
assert.ok(applicationPath, 'The app.js path is required.');
const application = fs.readFileSync(applicationPath, 'utf8');

const constantsStart = application.indexOf('const ACTIVITY_IDENTITY_SEARCH_DELAY_MILLISECONDS');
const constantsEnd = application.indexOf('const RADIO_REFERENCE_DIRECTORY_TIMEOUT_MILLISECONDS', constantsStart);
const modelStart = application.indexOf('function activityFilterContext(');
const modelEnd = application.indexOf('function activityFilterField(', modelStart);
assert.ok(constantsStart >= 0 && constantsEnd > constantsStart, 'Activity filter constants are required.');
assert.ok(modelStart >= 0 && modelEnd > modelStart, 'Activity filter model functions are required.');

const context = { URLSearchParams };
vm.createContext(context);
vm.runInContext(`${application.slice(constantsStart, constantsEnd)}\n${application.slice(modelStart, modelEnd)}`,
  context);

function parameters(values = {}) {
  return new URLSearchParams(Object.entries(values).map(([key, value]) => [key, String(value)]));
}

const systemContext = context.activityFilterContext({
  radio_system_key: 'p25:bee00:49f',
  ignored: 'must-not-reach-api',
  _activity_context: { kind: 'system', protocol: 'P25' }
});
assert.equal(systemContext.kind, 'system');
assert.equal(systemContext.radioSystemKey, 'p25:bee00:49f');
assert.equal(systemContext.radioIdentityKey, '');
assert.deepEqual(Object.assign({}, context.activityScopeParameters({
  radio_system_key: 'p25:bee00:49f', ignored: 'no', _activity_context: { kind: 'system' }
})), { radio_system_key: 'p25:bee00:49f' });

const nativeSystemCapabilities = context.activityContextCapabilities(systemContext);
assert.equal(nativeSystemCapabilities.channel, true);
assert.equal(nativeSystemCapabilities.sourceIdentity, true);
assert.equal(nativeSystemCapabilities.targetIdentity, true);
assert.equal(nativeSystemCapabilities.timeslot, true,
  'Existing P25 timeslot deep links must remain supported.');
assert.equal(nativeSystemCapabilities.timeslotFilter, false,
  'The sparse P25 timeslot dimension must not occupy the filter UI.');
const savedSystemCapabilities = context.activityContextCapabilities({
  kind: 'saved-system', protocol: 'DMR', radioSystemKey: 'dmr:channel:one'
});
assert.equal(savedSystemCapabilities.channel, false, 'A saved channel must not offer a redundant channel picker.');
assert.equal(savedSystemCapabilities.timeslotFilter, true,
  'DMR activity keeps its meaningful two-slot filter under More filters.');
assert.equal(context.activityContextCapabilities({
  kind: 'patch-group', protocol: 'P25', radioSystemKey: 'p25:bee00:49f'
}).groupMatch, false, 'Patch pages do not have meaningful direct/via-patch membership.');
assert.equal(context.activityContextCapabilities({
  kind: 'talkgroup', protocol: 'P25', radioSystemKey: 'p25:bee00:49f'
}).groupMatch, true);

const rolling = context.activityRouteFilters(parameters({
  activity_range: '6h',
  activity_action: 'grant',
  activity_event_type: 'call_group',
  activity_encryption: 'encrypted',
  activity_include_grants: 'false',
  activity_configuration_id: 'channel-1',
  activity_source_identity_key: 'v1-r-bee00-49f-202',
  activity_target_identity_key: 'v1-g-bee00-49f-101',
  activity_target_kind: 'radio',
  activity_frequency_hz: '851012500',
  activity_lcn: '1-125',
  activity_timeslot: '2',
  activity_radio_role: 'target',
  activity_source_id: '999'
}), systemContext, 50_000_000);
assert.equal(rolling.action, 'GRANT');
assert.equal(rolling.includeGrants, true, 'Selecting Grant must override hidden grants.');
assert.equal(rolling.radioRole, 'any', 'Radio-only role state must be masked on a system page.');
assert.equal(rolling.sourceId, null, 'Raw IDs are not valid stable-system identity filters.');
const rollingApi = context.activityApiFilterParameters(rolling, 50_000_000);
assert.equal(rollingApi.from_ms, 50_000_000 - 6 * 60 * 60 * 1000);
assert.equal(rollingApi.hide_grants, false);
assert.equal(rollingApi.action, 'GRANT');
assert.equal(rollingApi.event_type, 'CALL_GROUP');
assert.equal(rollingApi.configuration_id, 'channel-1');
assert.equal(rollingApi.source_identity_key, 'v1-r-bee00-49f-202');
assert.equal(rollingApi.target_identity_key, 'v1-g-bee00-49f-101');
assert.equal(rollingApi.target_kind, 'talkgroup',
  'A canonical target key must override stale route kind metadata.');
assert.equal(rollingApi.frequency_hz, 851012500);
assert.equal(rollingApi.lcn, '1-125');
assert.equal(rollingApi.timeslot, 2);
assert.equal(context.activityActionControlValue(rolling, nativeSystemCapabilities), 'GRANT');
assert.equal(context.activityActionControlValue({ action: '', includeGrants: true },
  nativeSystemCapabilities), '__include_grants__');
assert.equal(context.activityActionControlValue({ action: '', includeGrants: false },
  nativeSystemCapabilities), '');
assert.equal(context.activityMoreFilterCount(rolling, nativeSystemCapabilities), 5,
  'Only controls actually available under More contribute to its count.');
assert.equal(context.activityMoreFilterCount({ ...rolling, configurationId: '', lcn: '' },
  savedSystemCapabilities), 4,
  'DMR timeslot contributes when that technical control is meaningful and available.');
assert.equal(context.activityListboxNavigationIndex(0, 4, 'ArrowDown'), 1);
assert.equal(context.activityListboxNavigationIndex(3, 4, 'ArrowDown'), 0,
  'Down arrow wraps from the last identity result to the first.');
assert.equal(context.activityListboxNavigationIndex(0, 4, 'ArrowUp'), 3,
  'Up arrow wraps from the first identity result to the last.');
assert.equal(context.activityListboxNavigationIndex(2, 4, 'Home'), 0);
assert.equal(context.activityListboxNavigationIndex(1, 4, 'End'), 3);
assert.equal(context.activityListboxNavigationIndex(1, 4, 'Escape'), -1);
assert.equal(context.activityListboxNavigationIndex(0, 0, 'ArrowDown'), -1);

for (const eventType of [
  'RADIO_UNINHIBIT', 'RADIO_INHIBIT', 'RADIO_UNINHIBIT_ACK', 'RADIO_INHIBIT_ACK'
]) {
  const eventFilters = context.activityRouteFilters(parameters({
    activity_event_type: eventType.toLowerCase()
  }), systemContext);
  assert.equal(eventFilters.eventType, eventType);
  assert.equal(context.activityApiFilterParameters(eventFilters).event_type, eventType);
}

const custom = context.activityRouteFilters(parameters({
  activity_range: 'custom', activity_from_ms: '1000', activity_to_ms: '2000'
}), systemContext, 99_000);
assert.deepEqual(Object.assign({}, context.activityApiFilterParameters(custom, 100_000)), {
  hide_grants: true, from_ms: 1000, to_ms: 2000
});

const analogContext = context.activityFilterContext({
  configuration_id: 'analog-channel',
  _activity_context: { kind: 'conventional-analog', protocol: 'NBFM' }
});
const analog = context.activityRouteFilters(parameters({
  activity_action: 'call', activity_event_type: 'call', activity_encryption: 'encrypted',
  activity_include_grants: 'true', activity_configuration_id: 'stale-channel',
  activity_radio_role: 'source', activity_group_match: 'via_patch',
  activity_source_identity_key: 'v1-r-bee00-49f-202',
  activity_target_identity_key: 'v1-g-bee00-49f-101', activity_source_id: '202',
  activity_target_id: '101', activity_target_kind: 'talkgroup',
  activity_frequency_hz: '154430000', activity_lcn: '1-125', activity_timeslot: '2'
}), analogContext);
assert.equal(analog.encryption, 'all');
assert.equal(analog.includeGrants, false);
assert.equal(analog.configurationId, '');
assert.equal(analog.radioRole, 'any');
assert.equal(analog.groupMatch, 'all');
assert.equal(analog.sourceIdentityKey, '');
assert.equal(analog.targetIdentityKey, '');
assert.equal(analog.sourceId, null);
assert.equal(analog.targetId, null);
assert.equal(analog.targetKind, '');
assert.equal(analog.frequencyHz, 154430000);
assert.equal(analog.lcn, '');
assert.equal(analog.timeslot, null);

const digitalContext = context.activityFilterContext({
  configuration_id: 'digital-channel',
  _activity_context: { kind: 'conventional-digital', protocol: 'DMR' }
});
const digital = context.activityRouteFilters(parameters({
  activity_source_id: '202', activity_target_id: '101', activity_target_kind: 'talkgroup',
  activity_timeslot: '1', activity_source_identity_key: 'v1-r-bee00-49f-202'
}), digitalContext);
assert.equal(digital.sourceId, 202);
assert.equal(digital.targetId, 101);
assert.equal(digital.targetKind, 'talkgroup');
assert.equal(digital.timeslot, 1);
assert.equal(digital.sourceIdentityKey, '');
const digitalPatch = context.activityRouteFilters(parameters({
  activity_target_kind: 'patch_group'
}), digitalContext);
assert.equal(digitalPatch.targetKind, '', 'Conventional pages must mask the trunked-only patch kind.');

const nxdnDigital = context.activityRouteFilters(parameters({ activity_timeslot: '2' }),
  context.activityFilterContext({
    configuration_id: 'nxdn-channel',
    _activity_context: { kind: 'conventional-digital', protocol: 'NXDN' }
  }));
assert.equal(nxdnDigital.timeslot, null, 'NXDN conventional activity has no protocol timeslot.');

const radioContext = context.activityFilterContext({
  radio_system_key: 'p25:bee00:49f',
  radio_identity_key: 'v1-r-bee00-49f-202',
  _activity_context: { kind: 'radio', protocol: 'P25' }
});
assert.equal(radioContext.radioIdentityKey, 'v1-r-bee00-49f-202');
const maskedRadioCounterpart = context.activityRouteFilters(parameters({
  activity_target_identity_key: 'v1-g-bee00-49f-101'
}), radioContext);
assert.equal(maskedRadioCounterpart.radioRole, 'any');
assert.equal(maskedRadioCounterpart.targetIdentityKey, '',
  'A hidden counterpart must not constrain the Source-or-target role.');
assert.equal(maskedRadioCounterpart.sourceIdentityKey, '');
const sourceRoleRadio = context.activityRouteFilters(parameters({
  activity_radio_role: 'source', activity_target_identity_key: 'v1-g-bee00-49f-101'
}), radioContext);
assert.equal(sourceRoleRadio.targetIdentityKey, 'v1-g-bee00-49f-101');

const routeOverrides = context.activityFilterRouteOverrides(rolling);
assert.equal(routeOverrides.before_id, null);
assert.equal(routeOverrides.activity_range, '6h');
assert.equal(routeOverrides.activity_action, 'GRANT');
assert.equal(routeOverrides.activity_include_grants, null,
  'Grant implies inclusion without a redundant route switch.');
for (const key of Object.keys(routeOverrides)) {
  assert.ok(key === 'before_id' || key.startsWith('activity_'), `Unexpected generic route key ${key}`);
}

function plain(value) {
  return value === null ? null : Object.assign({}, value);
}

function routeParameters(overrides) {
  return new URLSearchParams(Object.entries(overrides)
    .filter(([, value]) => value !== null && value !== undefined && value !== '')
    .map(([key, value]) => [key, String(value)]));
}

const activityRow = {
  action: 'active',
  event_type: 'radio_inhibit',
  encrypted: true,
  configuration_id: 'channel-1',
  source_radio_id: 202,
  source_identity_key: 'v1-r-bee00-49f-202',
  target_id: 101,
  target_kind: 'talkgroup',
  target_identity_key: 'v1-g-bee00-49f-101'
};

assert.deepEqual(plain(context.activityCellFilterPatch(systemContext, activityRow, 'action')),
  { action: 'ACTIVE' });
assert.deepEqual(plain(context.activityCellFilterPatch(systemContext, activityRow, 'event')),
  { eventType: 'RADIO_INHIBIT' });
assert.deepEqual(plain(context.activityCellFilterPatch(systemContext, activityRow, 'encryption')),
  { encryption: 'encrypted' });
assert.deepEqual(plain(context.activityCellFilterPatch(systemContext, { encrypted: 1 }, 'encryption')),
  { encryption: 'encrypted' });
assert.deepEqual(plain(context.activityCellFilterPatch(systemContext, activityRow, 'channel')),
  { configurationId: 'channel-1' });
for (const column of ['source', 'source-alias', 'source-ota-alias']) {
  assert.deepEqual(plain(context.activityCellFilterPatch(systemContext, activityRow, column)),
    { sourceIdentityKey: 'v1-r-bee00-49f-202', sourceId: null });
}
for (const column of ['target', 'target-alias']) {
  assert.deepEqual(plain(context.activityCellFilterPatch(systemContext, activityRow, column)),
    { targetIdentityKey: 'v1-g-bee00-49f-101', targetId: null, targetKind: 'talkgroup' });
}

for (const eventType of [
  'RADIO_UNINHIBIT', 'RADIO_INHIBIT', 'RADIO_UNINHIBIT_ACK', 'RADIO_INHIBIT_ACK'
]) {
  assert.deepEqual(plain(context.activityCellFilterPatch(systemContext,
    { event_type: eventType.toLowerCase() }, 'event')), { eventType });
}

const grantOverrides = context.activityCellFilterRouteOverrides(rolling, systemContext,
  { action: 'grant' }, 'action');
assert.equal(grantOverrides.before_id, null);
assert.equal(grantOverrides.activity_range, '6h');
assert.equal(grantOverrides.activity_action, 'GRANT');
assert.equal(grantOverrides.activity_event_type, 'CALL_GROUP');
const grantFilters = context.activityRouteFilters(routeParameters(grantOverrides), systemContext);
assert.equal(grantFilters.includeGrants, true);
assert.equal(context.activityApiFilterParameters(grantFilters).hide_grants, false);

const conventionalRow = {
  source_radio_id: 404,
  source_identity_key: 'v1-r-bee00-49f-404',
  target_id: 505,
  target_kind: 'radio',
  target_identity_key: 'v1-r-bee00-49f-505'
};
assert.deepEqual(plain(context.activityCellFilterPatch(digitalContext, conventionalRow, 'source-alias')),
  { sourceIdentityKey: '', sourceId: 404 });
assert.deepEqual(plain(context.activityCellFilterPatch(digitalContext, conventionalRow, 'target-alias')),
  { targetIdentityKey: '', targetId: 505, targetKind: 'radio' });

assert.deepEqual(plain(context.activityCellFilterPatch(radioContext, activityRow, 'source')),
  { radioRole: 'source', sourceIdentityKey: '', sourceId: null });
assert.deepEqual(plain(context.activityCellFilterPatch(radioContext, activityRow, 'target')),
  {
    radioRole: 'source', targetIdentityKey: 'v1-g-bee00-49f-101', targetId: null,
    targetKind: 'talkgroup', sourceIdentityKey: '', sourceId: null
  });
const selectedRadioAsTarget = {
  ...activityRow,
  source_identity_key: 'v1-r-bee00-49f-303',
  target_identity_key: 'v1-r-bee00-49f-202',
  target_kind: 'radio'
};
assert.deepEqual(plain(context.activityCellFilterPatch(radioContext, selectedRadioAsTarget, 'source')),
  {
    radioRole: 'target', sourceIdentityKey: 'v1-r-bee00-49f-303', sourceId: null,
    targetIdentityKey: '', targetId: null, targetKind: ''
  });
assert.deepEqual(plain(context.activityCellFilterPatch(radioContext, selectedRadioAsTarget, 'target')),
  { radioRole: 'target', targetIdentityKey: '', targetId: null, targetKind: '' });

const talkgroupContext = context.activityFilterContext({
  radio_system_key: 'p25:bee00:49f', group_identity_key: 'v1-g-bee00-49f-101',
  _activity_context: { kind: 'talkgroup', protocol: 'P25' }
});
const channelContext = context.activityFilterContext({
  radio_system_key: 'p25:bee00:49f', configuration_id: 'channel-1',
  _activity_context: { kind: 'trunked-channel', protocol: 'P25' }
});
assert.equal(context.activityCellFilterPatch(talkgroupContext, activityRow, 'target'), null,
  'A group-scoped page must not expose a target filter that its toolbar masks.');
assert.equal(context.activityCellFilterPatch(channelContext, activityRow, 'channel'), null,
  'A channel-scoped page must not offer a redundant channel filter.');
assert.equal(context.activityCellFilterPatch(analogContext, activityRow, 'source'), null);
assert.equal(context.activityCellFilterPatch(systemContext, { action: 'not-real' }, 'action'), null);
assert.equal(context.activityCellFilterPatch(systemContext, { event_type: '' }, 'event'), null);

assert.match(application, /All actions \(grants hidden\)/,
  'Grant inclusion belongs in the Action select.');
assert.match(application, /All actions \(including grants\)/);
assert.doesNotMatch(application, /uiToggleField\('Include grants'/,
  'The standalone grants toggle must not return.');
assert.match(application, /activity-identity-picker-popover/,
  'Identity filtering uses a custom popover rather than an unreadable datalist.');
assert.match(application, /if \(!query && !passive\) \{/,
  'Opening an empty picker must not load an arbitrary first page of identities.');
assert.match(application, /activityIdentityDirectoryPath\(context, 'radio'\)/);
assert.match(application, /activityIdentityDirectoryPath\(context, 'talkgroup'\)/,
  'The unified destination picker searches radio and group directories together.');
assert.match(application, /bindAnchoredDropdown\(trigger, panel, activeRenderController\?\.signal\)/,
  'Identity pickers must be anchored to their triggers in the real application.');
assert.match(application,
  /bindAnchoredDropdown\(moreTrigger, morePanel, activeRenderController\?\.signal, \{ mobileSheet: true \}\)/,
  'More filters must be anchored on desktop and switch to the mobile sheet treatment.');
assert.match(application, /activity-filter-more-footer/);
assert.match(application, /activity-filter-more-content/);
assert.match(application, /: initialFilters\.timeslot/,
  'A hidden P25 timeslot deep link must survive applying another filter.');
assert.match(application, /Event subtype/);

const pickerStart = application.indexOf('function activityIdentityPicker(');
const pickerEnd = application.indexOf('function activityChannelFilter(', pickerStart);
assert.ok(pickerStart >= 0 && pickerEnd > pickerStart, 'The shared activity identity picker is required.');
const picker = application.slice(pickerStart, pickerEnd);
assert.match(picker, /const cancelIdentitySearch = \(\) => \{/);
assert.match(picker, /const selectEntry = \(entry\) => \{\s*cancelIdentitySearch\(\);/,
  'Selecting a result must invalidate and abort any in-flight lookup.');
assert.match(picker, /clear\.addEventListener\('click', \(\) => \{\s*cancelIdentitySearch\(\);/,
  'Clearing an identity must invalidate and abort any in-flight lookup.');
assert.match(picker, /renderResults\(true\);/,
  'A completed lookup must replace the searching announcement with its final result status.');
assert.match(picker, /repositionPanel\(\);/,
  'Asynchronous destination results must be placed inside the viewport before the user scrolls them.');
assert.match(picker, /setAttribute\('aria-selected'/,
  'Listbox options must expose selection state.');
assert.match(picker, /results\.setAttribute\('aria-label'/,
  'The identity result listbox must have an accessible name.');
assert.match(picker, /activityListboxNavigationIndex\(/,
  'Identity results must use the shared Arrow, Home, and End navigation model.');
assert.match(picker, /event\.key === 'Escape'[\s\S]*?event\.stopPropagation\(\);[\s\S]*?input\.focus\(\);/,
  'Escape from a result must return focus to the combobox without dismissing the picker.');
