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
  const { enqueueAffiliationChange } = await import(pathToFileURL(path.join(feature, 'renderer.js')).href);
  const state = history.createP25HistoryState();
  const seed = history.applyP25ActivityRows(state, [row(1, 'A', 1_000)], { initial: true });
  assert.deepEqual(seed.affiliationChanges, [], 'historical placement does not queue fresh motion');
  const before = history.buildP25Graph(state, 'system', 10_000);
  const result = history.applyP25ActivityRows(state, [row(3, 'C', 9_900), row(2, 'B', 9_800)], {
    atMs: 10_000, highlightAtObservationTime: true
  });
  assert.deepEqual(result.affiliationChanges.map((value) => [value.fromGroupKey, value.toGroupKey]),
    [['system:A', 'system:B'], ['system:B', 'system:C']], 'fresh batched observations retain their FIFO');
  const radioKey = result.affiliationChanges[0].radioKey;
  const original = before.nodes.find((value) => value.id === radioKey);
  assert.deepEqual(result.affiliationChanges[0].from, { x: original.x, y: original.y, z: original.z });
  assert.deepEqual(result.affiliationChanges[0].target, result.affiliationChanges[1].from,
    'the next trip begins at the preceding arrival');
  assert.equal(result.affiliationChanges[0].remainingMs, 7_800);
  assert.equal(state.radios.get(radioKey).visualGroupKey, 'system:C',
    'animation never delays the canonical affiliation');
  const after = history.buildP25Graph(state, 'system', 10_000);
  const final = after.nodes.find((value) => value.id === radioKey);
  assert.deepEqual(result.affiliationChanges.at(-1).target, { x: final.x, y: final.y, z: final.z });
  assert.equal(history.applyP25ActivityRows(state, [row(3, 'C', 9_900)]).affiliationChanges.length, 0,
    'duplicate saved rows cannot replay a trip');
  assert.equal(history.applyP25ActivityRows(state, [row(4, 'D', 9_950, 'site-b')], {
    atMs: 10_000, highlightAtObservationTime: true
  }).affiliationChanges.length, 0, 'a first observation in another scope is not a comparable move');
  const stale = history.applyP25ActivityRows(state, [row(5, 'E', 10_100)], {
    atMs: 20_000, highlightAtObservationTime: true
  });
  assert.deepEqual(stale.affiliationChanges, [], 'old catch-up evidence updates history without fresh motion');
  assert.deepEqual(stale.focusCandidates, []);
  assert.equal(state.radios.get(radioKey).visualGroupKey, 'system:E');

  const ordered = history.createP25HistoryState();
  history.applyP25ActivityRows(ordered, [row(10, 'A', 1_000)], { initial: true });
  history.applyP25ActivityRows(ordered, [row(11, 'B', 9_000)]);
  const orderedRadio = [...ordered.radios.values()][0];
  const oldLogout = history.applyP25ActivityRows(ordered, [{ ...row(12, '', 2_000), action: 'LOGOUT' }]);
  assert.equal(orderedRadio.visualGroupKey, 'system:B', 'late older logout cannot undo a newer scoped join');
  assert.deepEqual(oldLogout.focusCandidates, []);
  history.applyP25ActivityRows(ordered, [{ ...row(14, '', 9_500), action: 'LOGOUT' }]);
  assert.equal(orderedRadio.affiliations.size, 0, 'logout ordering markers are not active affiliations');
  const oldJoin = history.applyP25ActivityRows(ordered, [row(13, 'C', 9_500)]);
  assert.equal(orderedRadio.visualGroupKey, '', 'older ID at the same observation time cannot resurrect logout');
  assert.deepEqual(oldJoin.affiliationChanges, []);
  assert.deepEqual(oldJoin.focusCandidates, []);
  history.applyP25ActivityRows(ordered, [row(15, 'C', 9_600)]);
  assert.equal(orderedRadio.visualGroupKey, 'system:C', 'newer scoped observation can join after logout');
  const siteB = history.applyP25ActivityRows(ordered, [row(16, 'D', 9_700, 'site-b')]);
  assert.deepEqual(siteB.affiliationChanges, [], 'a new scope still does not fabricate a comparable move');
  history.applyP25ActivityRows(ordered, [{ ...row(17, '', 9_800, 'site-b'), action: 'LOGOUT' }]);
  assert.equal(orderedRadio.visualGroupKey, 'system:C', 'logout in one scope preserves other active evidence');
  history.applyP25ActivityRows(ordered, Array.from({ length: 100 }, (_, index) =>
    ({ ...row(100 + index, '', 10_000 + index, `old-site-${index}`), action: 'LOGOUT' })), { initial: true });
  assert.equal(orderedRadio.affiliationOrder.size, 64, 'inactive-scope ordering markers remain bounded');
  assert.equal(orderedRadio.affiliations.size, 1);

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
