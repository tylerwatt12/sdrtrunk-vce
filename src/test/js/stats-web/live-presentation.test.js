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
  return { liveRowIsActive, livePresentedRow, livePresentedTableRows };
})()`);

const preferences = {
  show_only_active_trunked_channels: true,
  retain_last_call_on_idle_rows: false,
  clear_voice_quality_when_idle: false
};
const row = (key, status, extra = {}) => ({ key, status, ...extra });
const keys = (rows) => JSON.parse(JSON.stringify(rows.map((value) => value.key)));

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

const systems = functionSource('liveSystemsSection');
assert.doesNotMatch(systems, /activeRowOrders|activeOrders/,
  'Frontend ordering must come from the authoritative snapshot without duplicate state');
assert.match(systems, /liveTable\.tableController\.setSortable\(!activeFilter\)/,
  'Conventional tables stay sortable while active-only trunked tables retain activation order');
assert.match(systems, /if \(!tableIds\.has\(tableId\)\) removeTable\(tableId\)/,
  'A resync must remove local tables absent from the authoritative snapshot');
assert.match(systems, /if \(activeFilter && selection && !incoming\.has\(selection\.rowKey\)\) clearSelection\(\)/);
