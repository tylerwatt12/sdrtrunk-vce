'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const feature = path.resolve(process.argv[2] || path.resolve(__dirname, '../../../../stats-web/assets/features/network-visualizer'));
const fact = (radio, group, at) => ({ radio_system_key: 'system', radio_identity_key: `v1-r-${radio}`,
  talkgroup_identity_key: `v1-g-${group}`, configuration_id: 'site', confirmed_at_ms: at, radio_id: radio, talkgroup_id: group });
const voice = (overrides = {}) => ({ protocol: 'P25', serving_system_key: 'system',
  status: 'CALL', activation_order: 2, source_id: 10, target_id: 20,
  source_entity_ref: { kind: 'radio', identity_key: 'v1-r-10' },
  target_entity_ref: { kind: 'talkgroup', identity_key: 'v1-g-20' },
  source_alias: 'Engine 10', target_alias: 'Dispatch', ...overrides });
const table = rows => [{ table_id: 'site', channel_running: true, configuration_id: 'site', rows }];
async function main() {
  const history = await import(pathToFileURL(path.join(feature, 'history.js')).href);
  const state = history.createP25HistoryState(), now = Date.now();
  history.applyP25LiveTables(state, table([voice()]), { atMs: now, initial: true, highlightCategories: new Set() });
  const graph = () => history.buildP25Graph(state, 'system', now, '', { highlightCategories: new Set() });
  assert.equal(state.radios.get('system:v1-r-10').label, 'Engine 10');
  assert.equal(state.groups.get('system:v1-g-20').label, 'Dispatch');
  assert.equal(history.applyP25LiveTables(state, table([voice({ signal_dbfs: -40 })]), { atMs: now + 1 }).changed, false,
    'quality-only Live updates refresh seen times without rebuilding the graph');
  assert.equal(state.radios.get('system:v1-r-10').lastAtMs, now + 1);
  assert.equal(history.applyP25LiveTables(state, table([voice({ source_alias: 'Engine 10 portable' })]), { atMs: now + 2 }).changed, true,
    'a changed alias updates visible presentation');
  assert.equal(state.radios.get('system:v1-r-10').label, 'Engine 10 portable');
  assert.equal(graph().nodes.filter(node => node.active).length, 2,
    'current calls light the radio and group even with every optional highlight off');
  assert.equal(history.p25ActionCount(state, 'call'), 0, 'snapshots and table updates never double count Activity actions');
  const firstPosition = graph().nodes.find(node => node.type === 'radio');
  history.applyP25LiveTables(state, table([voice({ target_id: 21,
    target_entity_ref: { kind: 'talkgroup', identity_key: 'v1-g-21' } })]), { atMs: now + 1 });
  const nextPosition = graph().nodes.find(node => node.type === 'radio');
  assert.deepEqual([nextPosition.x, nextPosition.y, nextPosition.z], [firstPosition.x, firstPosition.y, firstPosition.z],
    'hearing another call cannot fabricate affiliation travel');
  assert.equal(graph().links.find(link => link.kind === 'activity').target, 'system:v1-g-21');
  const focusedCall = history.buildP25Graph(state, 'system', now, 'system:v1-g-21');
  const focusedRadio = focusedCall.nodes.find(node => node.type === 'radio');
  assert.ok(focusedRadio, 'the current call group drilldown includes its active speaker');
  assert.ok(focusedCall.nodes.some(node => node.id === firstPosition.groupKey),
    'every included radio retains a visible physical anchor cube');
  assert.deepEqual([focusedRadio.x, focusedRadio.y, focusedRadio.z], [firstPosition.x, firstPosition.y, firstPosition.z],
    'drilldown cannot move a radio away from its established physical anchor');
  history.applyP25LiveTables(state, table([voice({ status: 'IDLE' })]), { atMs: now + 2 });
  assert.equal(graph().nodes.some(node => node.active), false, 'idle clears activity immediately with no highlight timeout');
  history.applyP25ActivityRows(state, [{ id: 1, action: 'CALL', observed_at_ms: now,
    protocol: 'p25', channel_kind: 'trunked_site', radio_system_key: 'system',
    source_identity_key: 'v1-r-10', target_identity_key: 'v1-g-20' }], { atMs: now });
  assert.equal(history.buildP25Graph(state, 'system', now).nodes.some(node => node.signalAction === 'call'), false,
    'a delayed call observation cannot light an entity that Live has already declared idle');
  history.applyP25LiveTables(state, table([voice({ activation_order: 0 })]));
  assert.equal(state.activeKeys.size, 0);
  const unscoped = history.createP25HistoryState();
  history.applyP25LiveTables(unscoped, [{ ...table([])[0], radio_system_key: 'wrong-configured-system', rows: [
    voice({ serving_system_key: null, radio_system_key: 'wrong-configured-system' }),
    voice({ protocol: 'DMR' })] }]);
  assert.equal(unscoped.systems.size, 0, 'missing captured serving scope cannot inherit a configured or home system');
  const unresolved = history.createP25HistoryState();
  history.applyP25LiveTables(unresolved, table([voice({ source_entity_ref: null, target_entity_ref: null,
    source_identity_key: 'channel-scoped-radio', target_identity_key: 'channel-scoped-group',
    source_type: 'RADIO', target_type: 'TALKGROUP' })]), { atMs: now });
  assert.equal(unresolved.radios.size, 1); assert.equal(unresolved.groups.size, 1,
    'unresolved Live channel identities retain their typed scope and remain visible');
  history.applyP25LiveTables(unscoped, [{ ...table([voice()])[0], table_id: 'conventional' }]);
  assert.equal(unscoped.systems.size, 0, 'conventional rows do not join the trunked visualizer');

  // Idle retention releases stale entities, including old known affiliations, but
  // current Live activity and in-flight endpoints remain complete.
  history.applyP25Affiliations(state, { affiliations: [fact(30, 40, now - 10_000)] }, { initial: true, atMs: now });
  history.applyP25LiveTables(state, table([voice()]), { atMs: now });
  state.actionCounts.set('emergency', 5);
  history.expireP25History(state, now + 1);
  assert.equal(state.radios.has('system:v1-r-30'), false, 'quiet ancient affiliations do not grow the scene forever');
  assert.equal(state.currentAffiliations.size, 0);
  assert.equal(state.radios.has('system:v1-r-10'), true, 'an ongoing call is protected even beyond its observation time');
  assert.equal(state.groups.has('system:v1-g-20'), true);
  assert.equal(history.p25ActionCount(state, 'emergency'), 5, 'session action totals outlive quiet entities');
  history.applyP25LiveTables(state, []);
  history.expireP25History(state, now + 3);
  assert.equal(state.radios.size + state.groups.size + state.systems.size, 0);
  assert.deepEqual(state.systemOrdinals, { next: 0, free: [], changed: false });

  const motion = history.createP25HistoryState();
  history.applyP25Affiliations(motion, { affiliations: [{ ...fact(1, 2, now), previous_affiliation: fact(1, 1, now - 1) }] }, { atMs: now });
  history.expireP25History(motion, now + 1);
  assert.equal(motion.radios.size, 1); assert.equal(motion.groups.size, 2, 'cleanup pins both moving endpoints');
  const current = motion.groups.get('system:v1-g-2'), position = [current.x, current.y, current.z];
  history.applyP25Affiliations(motion, { affiliations: [fact(2, 3, now + 1)] }, { initial: true, atMs: now + 1 });
  assert.deepEqual([current.x, current.y, current.z], position, 'new activity leaves existing cube anchors stationary');
  const unaffiliated = history.createP25HistoryState();
  const activityRow = (id, overrides = {}) => ({ id, action: 'CHECK', observed_at_ms: now,
    protocol: 'p25', channel_kind: 'trunked_site', radio_system_key: 'system',
    source_identity_key: 'v1-r-99', ...overrides });
  history.applyP25ActivityRows(unaffiliated, [activityRow(1)]);
  const beforeGrowth = history.buildP25Graph(unaffiliated, 'system', now).nodes.find(node => node.type === 'radio');
  history.applyP25ActivityRows(unaffiliated, Array.from({ length: 100 }, (_, index) => activityRow(index + 2, {
    action: 'PATCH', source_identity_key: null, target_identity_key: `v1-g-${index + 100}` })));
  const afterGrowth = history.buildP25Graph(unaffiliated, 'system', now).nodes.find(node => node.type === 'radio');
  assert.deepEqual([afterGrowth.x, afterGrowth.y, afterGrowth.z], [beforeGrowth.x, beforeGrowth.y, beforeGrowth.z],
    'growing the system cloud cannot relocate an unrelated unaffiliated radio');
  console.log('P25 current activity parity, identity scope and bounded idle cleanup checks passed.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
