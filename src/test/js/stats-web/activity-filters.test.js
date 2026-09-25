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
assert.deepEqual(Object.assign({}, context.activityScopeParameters({
  radio_system_key: 'p25:bee00:49f', ignored: 'no', _activity_context: { kind: 'system' }
})), { radio_system_key: 'p25:bee00:49f' });

const nativeSystemCapabilities = context.activityContextCapabilities(systemContext);
assert.equal(nativeSystemCapabilities.channel, true);
assert.equal(nativeSystemCapabilities.sourceIdentity, true);
assert.equal(nativeSystemCapabilities.targetIdentity, true);
const savedSystemCapabilities = context.activityContextCapabilities({
  kind: 'saved-system', protocol: 'DMR', radioSystemKey: 'dmr:channel:one'
});
assert.equal(savedSystemCapabilities.channel, false, 'A saved channel must not offer a redundant channel picker.');
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
  _activity_context: { kind: 'radio', protocol: 'P25' }
});
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
