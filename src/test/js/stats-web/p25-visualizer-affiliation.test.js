'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const feature = path.resolve(__dirname, '../../../../stats-web/assets/features/network-visualizer');
const row = (id, group, observedAtMs, scope = 'site-a') => ({ id, action: 'JOIN',
  observed_at_ms: observedAtMs, protocol: 'p25', channel_kind: 'trunked_site', configuration_id: scope,
  radio_system_key: 'system', source_identity_key: 'radio', source_identity_kind_code: 2, target_identity_key: group,
  target_kind: 'talkgroup' });

async function main() {
  const history = await import(pathToFileURL(path.join(feature, 'history.js')).href);
  const applyRows = (state, rows, options = {}) => history.applyP25State(state, { rows, removed_ids: options.removedIds }, options);
  const applyAffiliations = (state, affiliations, options = {}) => history.applyP25State(state, { affiliations,
    removed_radio_keys: options.removedRadioKeys, available: options.available }, { ...options, sequence: options.revision });
  const { enqueueAffiliationChange } = await import(pathToFileURL(path.join(feature, 'renderer.js')).href);
  const fact = (group, at, overrides = {}) => ({ radio_system_key: 'system', radio_identity_key: 'radio',
    talkgroup_identity_key: group, configuration_id: 'site-a', confirmed_at_ms: at, protocol: 'p25',
    radio_id: 7001, talkgroup_id: Number(group) || 1, ...overrides });
  const state = history.createP25HistoryState();
  const seed = applyAffiliations(state, [fact('A', 1_000)], { replace: true, initial: true });
  assert.deepEqual(seed.affiliationChanges, [], 'current-state seed never queues fresh motion');
  assert.equal(state.recentRows.size, 0, 'current state does not fabricate Activity records');
  assert.equal(history.groupedP25Events(state).length, 0);
  assert.equal(history.buildP25Graph(state, 'system').links[0].kind, 'current',
    'a long-lived affiliation appears without any recent Activity');
  applyRows(state, [row(1, 'A', 1_000), row(2, 'B', 2_000), row(3, 'C', 3_000)], { initial: true });
  const radioKey = 'system:radio';
  assert.equal(state.radios.get(radioKey).visualGroupKey, 'system:A', 'JOIN events do not override current state');
  const before = history.buildP25Graph(state, 'system', 10_000);
  const first = applyAffiliations(state, [fact('B', 9_800)], { atMs: 10_000, revision: 1 });
  const second = applyAffiliations(state, [fact('C', 9_900)], { atMs: 10_000, revision: 2 });
  const motion = [...first.affiliationChanges, ...second.affiliationChanges];
  assert.deepEqual(motion.map((value) => [value.fromGroupKey, value.toGroupKey]),
    [['system:A', 'system:B'], ['system:B', 'system:C']], 'successive canonical motion retain FIFO animation');
  const original = before.nodes.find((value) => value.id === radioKey);
  assert.deepEqual(motion[0].from, { x: original.x, y: original.y, z: original.z });
  assert.deepEqual(motion[0].target, motion[1].from, 'the next trip begins at the preceding arrival');
  assert.equal(motion[0].remainingMs, 7_800);
  assert.equal(state.radios.get(radioKey).visualGroupKey, 'system:C', 'motion never delays canonical state');
  assert.deepEqual(applyAffiliations(state, [fact('C', 9_900)], { revision: 2 }).affiliationChanges, []);
  const retainedRadio = state.radios.get(radioKey), retainedGroup = state.groups.get('system:C');
  const confirmation = applyAffiliations(state, [fact('C', 9_950)], { atMs: 10_000, revision: 3 });
  assert.equal(confirmation.changed, false, 'confirmation-only work never rebuilds or replays attention');
  assert.equal(state.radios.get(radioKey), retainedRadio);
  assert.equal(state.groups.get('system:C'), retainedGroup);
  assert.equal(state.currentAffiliations.get(radioKey).confirmed_at_ms, 9_950);
  const site = applyAffiliations(state, [fact('D', 9_980, { configuration_id: 'site-b' })], {
    atMs: 10_000, revision: 4 });
  assert.deepEqual(site.affiliationChanges, [], 'a different observation channel is not a comparable trip');
  assert.deepEqual(site.focusCandidates, []);
  assert.equal(state.radios.get(radioKey).visualGroupKey, 'system:D', 'the server still owns the chosen association');
  const stale = applyAffiliations(state, [fact('E', 10_100, { configuration_id: 'site-b' })], {
    atMs: 20_000, revision: 5 });
  assert.deepEqual(stale.affiliationChanges, [], 'old confirmation updates state without fresh motion');
  assert.deepEqual(stale.focusCandidates, []);
  applyRows(state, [{ ...row(4, '', 10_500), action: 'LOGOUT' }], { initial: true });
  assert.equal(state.radios.get(radioKey).visualGroupKey, 'system:E', 'observed logout cannot undo canonical decisions');
  applyAffiliations(state, [], { available: false, revision: 6 });
  assert.equal(state.available, false);
  assert.equal(state.radios.get(radioKey).visualGroupKey, 'system:E', 'unavailable retains the last complete map');
  applyAffiliations(state, [], { removedRadioKeys: [{ radio_system_key: 'system', radio_identity_key: 'radio' }],
    available: true, revision: 7 });
  assert.equal(state.radios.get(radioKey).visualGroupKey, '', 'explicit canonical removal has no event-history fallback');
  assert.equal(state.currentAffiliations.size, 0);
  const missed = history.createP25HistoryState();
  applyAffiliations(missed, [fact('A', 100)], { replace: true, initial: true });
  applyAffiliations(missed, [fact('B', 200)], { replace: true, initial: true, available: false });
  assert.equal(missed.radios.get(radioKey).visualGroupKey, 'system:B',
    'newer unavailable recovery contains the server’s last COMPLETE map, including missed changes');
  assert.equal(missed.available, false);
  applyAffiliations(missed, [], { replace: true, initial: true, available: false });
  assert.equal(missed.currentAffiliations.size, 0, 'complete recovery honors real removals before availability failed');
  const retained = history.createP25HistoryState();
  applyAffiliations(retained, [fact('A', 100)], { initial: true });
  applyRows(retained, [row(1, 'B', 1_000)], { initial: true });
  history.applyP25State(retained, { rows: [row(2, 'C', 2_000)], removed_ids: [1], affiliations: [fact('D', 2_000)] }, { initial: true });
  assert.equal(retained.recentRows.size, 1, 'events exactly follow the combined server removals');
  assert.equal(retained.radios.get(radioKey).visualGroupKey, 'system:D');
  history.applyP25State(retained, { rows: [], affiliations: [], available: false }, { replace: true, initial: true });
  assert.equal(retained.currentAffiliations.size, 0, 'a complete stale snapshot can carry an authoritative empty map');
  assert.equal(retained.available, false);
  const aliasRefresh = history.createP25HistoryState();
  applyRows(aliasRefresh, [{ ...row(1, 'A', 100), source_alias_name: 'Old Alias' }], { initial: true });
  applyAffiliations(aliasRefresh, [fact('A', 200, { radio_alias_name: 'New Alias' })], { initial: true });
  assert.equal(aliasRefresh.radios.get(radioKey).label, 'New Alias');
  applyAffiliations(aliasRefresh, [fact('A', 300)], { initial: true });
  assert.equal(aliasRefresh.radios.get(radioKey).label, '7001', 'cached Activity cannot restore an alias removed from canonical state');
  const nativeLabels = history.createP25HistoryState();
  applyAffiliations(nativeLabels, [fact('A', 100, { radio_system_key: 'p25:bee00:123',
    radio_canonical_identity: { wacn: 0xbee00, system_id: 0x124, subscriber_id: 9001 },
    radio_home_system_name: 'County P25', radio_observed_working_id: 501 })], { initial: true });
  assert.equal([...nativeLabels.radios.values()][0].label, 'County P25 · 9001 (Working ID 501)',
    'current state shares canonical identity and home-name formatting with Activity');
  assert.throws(() => applyAffiliations(state, Array.from({ length: 10_001 }, (_, index) => fact('A', 1, { radio_identity_key: `r${index}` }))),
    /bounds/, 'native state has an independent 10k bound');

  const partial = history.createP25HistoryState();
  applyAffiliations(partial, Array.from({ length: 901 }, (_, index) => fact('A', 100, {
    radio_identity_key: `r${index}`, radio_id: index })), { initial: true });
  history.applyP25State(partial, { affiliations_truncated: true });
  assert.equal(partial.affiliationsTruncated, true, 'server inventory truncation remains independent of local display caps');
  const partialGraph = history.buildP25Graph(partial, 'system');
  assert.equal(partial.currentAffiliations.size, 901, 'display caps do not discard canonical knowledge');
  assert.equal(partialGraph.nodes.filter((node) => node.type === 'radio').length, 900);
  assert.equal(partialGraph.truncated, true, 'a bounded display explicitly reports partial data');

  const queue = [];
  const changes = Array.from({ length: 9 }, (_, index) => ({ id: index, fromGroupKey: `g${index}`,
    toGroupKey: `g${index + 1}`, from: { x: index }, target: { x: index + 1 } }));
  changes.forEach((change) => enqueueAffiliationChange(queue, change));
  assert.equal(queue.length, 4, 'bursts cannot grow the per-radio presentation queue');
  assert.deepEqual(queue.map((change) => change.id), [0, 1, 2, 8],
    'the current trip and bounded FIFO remain; overflow coalesces to the latest saved destination');
  assert.equal(queue.at(-1).fromGroupKey, queue.at(-2).toGroupKey);
  assert.deepEqual(queue.at(-1).from, queue.at(-2).target);
  assert.equal(queue.at(-1).toGroupKey, 'g9');
  assert.equal(enqueueAffiliationChange(queue, changes.at(-1)), false, 'duplicate delivery stays bounded');
  console.log('P25 visualizer affiliation contracts passed');
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
