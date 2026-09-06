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

function constantSource(name, ending) {
  const start = source.indexOf(`const ${name} =`);
  assert.notEqual(start, -1, `Missing ${name}`);
  const end = source.indexOf(ending, start);
  assert.notEqual(end, -1, `Unclosed ${name}`);
  return source.slice(start, end + ending.length);
}

const behavior = vm.runInNewContext(`(() => {
  ${constantSource('LIVE_IDLE_CALL_FIELDS', '];')}
  ${constantSource('LIVE_VOICE_QUALITY_FIELDS', '];')}
  ${functionSource('liveRowIsActive')}
  ${functionSource('livePresentedRow')}
  ${functionSource('livePresentedTableRows')}
  ${functionSource('liveIdentityRenderKey')}
  ${functionSource('liveDetailSelectionUnchanged')}
  ${functionSource('liveIdentityType')}
  ${functionSource('liveIdentityLabel')}
  ${functionSource('identityKind')}
  ${functionSource('rowGroupIdentityKind')}
  ${functionSource('groupIdentityLabel')}
  ${functionSource('activityTargetKind')}
  return { liveRowIsActive, livePresentedRow, livePresentedTableRows,
    liveIdentityRenderKey, liveDetailSelectionUnchanged, liveIdentityType, liveIdentityLabel,
    rowGroupIdentityKind, groupIdentityLabel, activityTargetKind };
})()`);

const preferences = {
  show_only_active_trunked_channels: true,
  retain_last_call_on_idle_rows: false,
  clear_voice_quality_when_idle: false
};
const row = (key, status, extra = {}) => ({ key, status, ...extra });
const keys = (rows) => JSON.parse(JSON.stringify(rows.map((value) => value.key)));

assert.equal(behavior.liveIdentityType({ target_form: 'PATCH_GROUP' }, 'target'), 'patch_group');
assert.equal(behavior.liveIdentityType({
  target_entity_ref: { kind: 'patch_group', radio_system_key: 'p25:bee00:49f',
    identity_key: 'v1-p-bee00-49f-4400' }
}, 'target'), 'patch_group');
assert.equal(behavior.liveIdentityLabel({ target_matcher: { type: 'patch_group' } }, 'target'), 'patch group');
assert.equal(behavior.liveIdentityLabel({ target_form: 'PATCH_GROUP' }, 'target', true), 'Patch Group');
assert.equal(behavior.liveIdentityType({}, 'target'), 'unknown');
assert.equal(behavior.liveIdentityLabel({}, 'target'), 'identity');
assert.equal(behavior.liveIdentityLabel({ target_form: 'UNKNOWN' }, 'target', true), 'Identity');
assert.equal(behavior.liveIdentityLabel({ target_form: 'TELEPHONE_NUMBER' }, 'target', true), 'Identity');
assert.equal(behavior.rowGroupIdentityKind({}), 'unknown');
assert.equal(behavior.rowGroupIdentityKind({ target_kind: 'telephone_number' }), 'unknown');
assert.equal(behavior.groupIdentityLabel({}), 'ID');
assert.equal(behavior.groupIdentityLabel({ target_kind: 'telephone_number' }, null, false), 'Identity');
assert.equal(behavior.activityTargetKind({ target_kind: 'patch_group' }), 'patch_group');
assert.equal(behavior.activityTargetKind({ target_kind: 'talkgroup' }), 'talkgroup');
assert.equal(behavior.activityTargetKind({ target_kind: 'radio' }), 'radio');
assert.equal(behavior.activityTargetKind({ target_kind: 'channel' }), '');

const sourceIdentity = {
  source_id: '1201', source_alias: 'Engine 1', source_aliases: [{ alias_id: 1, alias_list_id: 2 }],
  source_entity_ref: { kind: 'radio', identity_key: 'v1-r-bee00-49f-1201' },
  protocol: 'P25', signal_dbfs: -71, decode_health_pct: 99
};
assert.equal(behavior.liveIdentityRenderKey(sourceIdentity, 'source'),
  behavior.liveIdentityRenderKey({ ...sourceIdentity, signal_dbfs: -82, decode_health_pct: 91 }, 'source'),
  'Signal and decode updates must not replace an unchanged source cell');
assert.notEqual(behavior.liveIdentityRenderKey(sourceIdentity, 'source'),
  behavior.liveIdentityRenderKey({ ...sourceIdentity, source_id: '1202' }, 'source'));
const selected = { kind: 'CONTROL', role: 'CURRENT_CONTROL', logicalKey: 'CONTROL:channel',
  transportKey: 'channel:851012500:', rowKey: 'control', configurationId: 'channel',
  bindingFrequencyHz: 851012500, bindingTimeslot: null, label: 'Site', channelLabel: 'Site · LCN 1' };
assert.equal(behavior.liveDetailSelectionUnchanged(selected, { ...selected }), true);
assert.equal(behavior.liveDetailSelectionUnchanged(selected,
  { ...selected, transportKey: 'channel:852012500:', bindingFrequencyHz: 852012500 }), false);

for (const status of ['CONTROL', 'ACTIVE', 'CALL', 'DATA', 'ENCRYPTED']) {
  assert.equal(behavior.liveRowIsActive(row(status, status, { activation_order: 1 })), true);
}
for (const status of ['IDLE', 'FADE', 'RESET', 'TEARDOWN']) {
  assert.equal(behavior.liveRowIsActive(row(status, status)), false);
}
assert.equal(behavior.liveRowIsActive(row('missing', 'CALL')), false,
  'An active-looking row without authoritative order must fail closed');
assert.equal(behavior.liveRowIsActive(row('unsafe', 'CALL', {
  activation_order: Number.MAX_SAFE_INTEGER + 1
})), false, 'An unsafe order must fail closed');

assert.deepEqual(keys(behavior.livePresentedTableRows({ table_id: 'site', rows: [
  row('control', 'CONTROL', { activation_order: 1 }),
  row('first', 'CALL', { activation_order: 2 }), row('idle', 'IDLE')
] }, preferences)), ['control', 'first']);
assert.deepEqual(keys(behavior.livePresentedTableRows({ table_id: 'site', rows: [
  row('new', 'CALL', { activation_order: 3 }), row('control', 'IDLE'),
  row('first', 'CALL', { activation_order: 2 })
] }, preferences)), ['first', 'new']);
assert.deepEqual(keys(behavior.livePresentedTableRows({ table_id: 'site', rows: [
  row('control', 'CONTROL', { activation_order: 4 }),
  row('new', 'CALL', { activation_order: 3 }),
  row('first', 'CALL', { activation_order: 2 })
] }, preferences)), ['first', 'new', 'control'],
'A coalesced snapshot must retain the server activation order when a control channel returns');
assert.deepEqual(keys(behavior.livePresentedTableRows({ table_id: 'site', rows: [
  row('control', 'CONTROL', { activation_order: 4 }),
  row('new', 'CALL', { activation_order: 3 }), row('first', 'IDLE')
] }, preferences)), ['new', 'control']);

const idle = row('conventional', 'IDLE', {
  source_id: '1201', source_alias: 'Engine 1', source_aliases: [{ alias_id: 1 }],
  target_id: '44', target_alias: 'Dispatch', talker_alias: 'CAR 1', encryption_details: 'AES',
  callsign: 'WPFF205', vc_quality_pct: 98, vc_decoded_frames: 50
});
const conventionalRows = behavior.livePresentedTableRows({ table_id: 'conventional', rows: [idle] },
  preferences);
assert.equal(conventionalRows.length, 1, 'Active-only filtering must never hide conventional channels');
assert.equal(conventionalRows[0].source_id, undefined);
assert.equal(conventionalRows[0].target_alias, undefined);
assert.equal(conventionalRows[0].callsign, 'WPFF205', 'Callsign describes the channel, not the completed call');
assert.equal(idle.source_id, '1201', 'Presentation must not mutate the shared Live row');
assert.equal(idle.vc_quality_pct, 98, 'Unrelated retained quality must remain on the source row');

const retained = behavior.livePresentedRow(idle, {
  retain_last_call_on_idle_rows: true, clear_voice_quality_when_idle: true
});
assert.equal(retained.source_id, '1201');
assert.equal(retained.target_alias, 'Dispatch');
assert.equal(retained.vc_quality_pct, undefined);
assert.equal(retained.vc_decoded_frames, undefined);

const untouched = behavior.livePresentedRow(idle, {
  retain_last_call_on_idle_rows: true, clear_voice_quality_when_idle: false
});
assert.equal(untouched, idle, 'Rows that need no presentation change should not be copied');

const channels = functionSource('liveChannelsSection');
assert.doesNotMatch(channels, /activeRowOrders|activeOrders/,
  'Frontend ordering must come from the authoritative snapshot without duplicate state');
assert.match(channels, /liveTable\.tableController\.setSortable\(!activeFilter\)/,
  'Conventional tables stay sortable while active-only trunked tables retain activation order');
assert.match(channels, /liveDetailSelectionUnchanged\(selection, nextSelection\)/,
  'Repeated snapshots must not redispatch an unchanged selected row');
assert.match(channels, /liveTable\.tableController\.reconcileRows\(displayed\.rows\)/,
  'Live updates must reconcile stable row cells instead of rebuilding the table body');
assert.match(channels, /if \(!tableIds\.has\(tableId\)\) removeTable\(tableId\)/,
  'A resync must remove local tables absent from the authoritative snapshot');
assert.match(channels, /if \(activeFilter && selection && !incoming\.has\(selection\.rowKey\)\) clearSelection\(\)/);
