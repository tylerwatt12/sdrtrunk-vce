'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const feature = path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/features/network-visualizer'));

function call(key, universeKey, groupKey, radioKey, lastObservedAtMs, expiresAtMs = 60_000) {
  return { key, universeKey, groupKey, radioKey, lastObservedAtMs, expiresAtMs };
}

function state(calls, generation = 1, semanticEvents = []) {
  return {
    generation,
    activeCalls: new Map(calls.map((entry) => [entry.key, entry])),
    semanticEvents,
    radios: new Map(),
    pendingGrants: new Map([['grant-only', { universeKey: 'u-grant', groupKey: 'g-grant' }]]),
    universes: new Map([['u-afterglow', { key: 'u-afterglow', afterglowUntilMs: 99_000 }]])
  };
}

async function main() {
  const moduleUrl = `${pathToFileURL(path.join(feature, 'attention.js')).href}?attention-test=1`;
  const { ATTENTION_DEFAULTS, createAttentionCoordinator, rankActiveCallHotspots,
    rankAttentionHotspots } = await import(moduleUrl);
  assert.deepEqual(ATTENTION_DEFAULTS, {
    candidateLeadMs: 2_000,
    minimumDwellMs: 10_000,
    manualCooldownMs: 10_000,
    importantEventHoldMs: 6_000,
    emergencyEventHoldMs: 12_000
  });

  const calls = [
    call('a-1', 'u-a', 'g-a', 'r-a1', 1_000),
    call('a-2', 'u-a', 'g-a', 'r-a2', 1_100),
    call('b-1', 'u-b', 'g-b', 'r-b1', 9_000),
    call('b-2', 'u-b', 'g-b', 'r-b1', 9_100),
    call('b-3', 'u-b', 'g-b', 'r-b1', 9_200),
    call('expired', 'u-z', 'g-z', 'r-z', 10_000, 10)
  ];
  const overview = rankActiveCallHotspots(state(calls), { level: 'overview' }, 100);
  assert.deepEqual(overview.map((entry) => entry.targetKey), ['u-a', 'u-b']);
  assert.deepEqual(overview[0].score, { transmittingRadios: 2, callLegs: 2, lastObservedAtMs: 1_100 });
  assert.deepEqual(overview[0].centroidKeys, ['g-a', 'r-a1', 'r-a2']);
  assert.equal(overview.some((entry) => entry.targetKey === 'u-grant'), false);
  assert.equal(overview.some((entry) => entry.targetKey === 'u-afterglow'), false);

  const systemCalls = [
    call('g1-a', 'u-a', 'g-1', 'r-1', 2_000),
    call('g1-b', 'u-a', 'g-1', 'r-2', 2_100),
    call('g2-a', 'u-a', 'g-2', 'r-3', 3_000),
    call('outside', 'u-b', 'g-x', 'r-4', 9_000)
  ];
  const system = rankActiveCallHotspots(state(systemCalls), { level: 'system', universeKey: 'u-a' }, 100);
  assert.deepEqual(system.map((entry) => entry.targetKey), ['g-1', 'g-2']);
  assert.deepEqual(system[0].centroidKeys, ['g-1', 'r-1', 'r-2']);
  const currentWinsTie = rankActiveCallHotspots(state([
    call('tie-a', 'u-a', 'g-a', 'r-a', 1_000),
    call('tie-b', 'u-a', 'g-b', 'r-b', 1_000)
  ]), { level: 'system', universeKey: 'u-a' }, 100, 'g-b');
  assert.equal(currentWinsTie[0].targetKey, 'g-b');
  const legsThenRecency = rankActiveCallHotspots(state([
    call('legs-a', 'u-a', 'g-legs', 'r-1', 1_000),
    call('legs-b', 'u-a', 'g-legs', 'r-1', 1_001),
    call('recent-a', 'u-a', 'g-recent', 'r-2', 9_000)
  ]), { level: 'system', universeKey: 'u-a' }, 100);
  assert.deepEqual(legsThenRecency.map((entry) => entry.targetKey), ['g-legs', 'g-recent']);
  const recency = rankActiveCallHotspots(state([
    call('old', 'u-a', 'g-old', 'r-1', 1_000),
    call('new', 'u-a', 'g-new', 'r-2', 2_000)
  ]), { level: 'system', universeKey: 'u-a' }, 100);
  assert.deepEqual(recency.map((entry) => entry.targetKey), ['g-new', 'g-old']);

  const important = rankAttentionHotspots(state([
    call('grant-a', 'u-a', 'g-grant', 'r-grant', 9_900),
    call('grant-b', 'u-a', 'g-grant', 'r-grant-2', 9_950)
  ], 1, [
    { type: 'affiliation_observed', universeKey: 'u-a', newGroupKey: 'g-join',
      radioKey: 'r-join', observedAtMs: 9_800 },
    { type: 'signal_denial', universeKey: 'u-a', groupKey: 'g-denial',
      radioKey: 'r-denial', observedAtMs: 9_700 },
    { type: 'signal_emergency', universeKey: 'u-b', groupKey: 'g-emergency',
      radioKey: 'r-emergency', observedAtMs: 1_000 }
  ]), { level: 'system', universeKey: 'u-a' }, 10_000);
  assert.deepEqual(important.map((entry) => entry.targetKey), ['g-denial', 'g-join', 'g-grant']);
  assert.deepEqual(important.map((entry) => entry.attentionKind),
    ['signal_denial', 'affiliation_observed', 'grant']);
  assert.equal(important[0].score.importance > important[2].score.importance, true);

  const overviewEmergency = rankAttentionHotspots(state([
    call('grant-a', 'u-a', 'g-a', 'r-a', 11_900)
  ], 1, [{ type: 'signal_emergency', universeKey: 'u-b', groupKey: 'g-b',
    radioKey: 'r-b', observedAtMs: 1_000 }]), { level: 'overview' }, 12_000);
  assert.equal(overviewEmergency[0].targetKey, 'u-b');
  assert.equal(overviewEmergency[0].attentionKind, 'signal_emergency');
  for (const kind of ['affiliation_observed', 'explicit_presence_remove', 'signal_denial', 'signal_check',
    'signal_emergency', 'signal_page', 'signal_busy', 'observed_affiliation_change']) {
    const ranked = rankAttentionHotspots(state([
      call('grant', 'u-a', 'g-grant', 'r-grant', 9_999)
    ], 1, [{ type: kind, universeKey: 'u-a', groupKey: 'g-important',
      newGroupKey: 'g-important', oldGroupKey: 'g-important', radioKey: 'r-important', observedAtMs: 9_999 }]),
    { level: 'system', universeKey: 'u-a' }, 10_000);
    assert.equal(ranked[0].attentionKind, kind, `${kind} should outrank a routine Grant`);
  }

  const group = rankActiveCallHotspots(state(systemCalls),
    { level: 'group', universeKey: 'u-a', groupKey: 'g-2' }, 100);
  assert.equal(group.length, 1);
  assert.equal(group[0].targetKey, 'g-2');
  assert.deepEqual(group[0].radioKeys, ['r-3']);
  const idleGroup = rankActiveCallHotspots(state([]),
    { level: 'group', universeKey: 'u-a', groupKey: 'g-idle' }, 100);
  assert.deepEqual(idleGroup[0].centroidKeys, ['g-idle']);

  const coordinator = createAttentionCoordinator();
  const activeA = state([call('a', 'u-a', 'g-a', 'r-a', 1_000)]);
  assert.equal(coordinator.update({ state: activeA, scope: { level: 'overview' }, atMs: 0,
    autoRotate: true }).status, 'candidate');
  assert.equal(coordinator.update({ state: activeA, scope: { level: 'overview' }, atMs: 1_999,
    autoRotate: true }).changed, false);
  const firstFocus = coordinator.update({ state: activeA, scope: { level: 'overview' }, atMs: 2_000,
    autoRotate: true });
  assert.equal(firstFocus.status, 'focus');
  assert.equal(firstFocus.target.targetKey, 'u-a');

  const activeB = state([call('b', 'u-b', 'g-b', 'r-b', 4_000)]);
  assert.equal(coordinator.update({ state: activeB, scope: { level: 'overview' }, atMs: 4_000,
    autoRotate: true }).reason, 'candidate_delay');
  assert.equal(coordinator.update({ state: activeB, scope: { level: 'overview' }, atMs: 7_000,
    autoRotate: true }).reason, 'minimum_dwell');
  const afterDwell = coordinator.update({ state: activeB, scope: { level: 'overview' }, atMs: 12_000,
    autoRotate: true });
  assert.equal(afterDwell.changed, true);
  assert.equal(afterDwell.target.targetKey, 'u-b');

  const override = createAttentionCoordinator();
  override.update({ state: activeA, scope: { level: 'overview' }, atMs: 0, autoRotate: true });
  override.update({ state: activeA, scope: { level: 'overview' }, atMs: 2_000, autoRotate: true });
  const emergencyB = state([call('a', 'u-a', 'g-a', 'r-a', 3_000)], 1,
    [{ type: 'signal_emergency', universeKey: 'u-b', groupKey: 'g-b',
      radioKey: 'r-b', observedAtMs: 3_000 }]);
  assert.equal(override.update({ state: emergencyB, scope: { level: 'overview' }, atMs: 3_000,
    autoRotate: true }).reason, 'candidate_delay');
  const emergencyFocus = override.update({ state: emergencyB, scope: { level: 'overview' }, atMs: 5_000,
    autoRotate: true });
  assert.equal(emergencyFocus.changed, true, 'important events should bypass Grant dwell after the lead delay');
  assert.equal(emergencyFocus.target.targetKey, 'u-b');
  assert.equal(emergencyFocus.target.attentionKind, 'signal_emergency');

  const switching = createAttentionCoordinator({ candidateLeadMs: 2_000, minimumDwellMs: 0,
    manualCooldownMs: 10_000 });
  switching.update({ state: activeA, scope: { level: 'overview' }, atMs: 0, autoRotate: true });
  switching.update({ state: activeA, scope: { level: 'overview' }, atMs: 2_000, autoRotate: true });
  switching.update({ state: activeB, scope: { level: 'overview' }, atMs: 3_000, autoRotate: true });
  switching.update({ state: activeA, scope: { level: 'overview' }, atMs: 4_000, autoRotate: true });
  assert.equal(switching.update({ state: activeB, scope: { level: 'overview' }, atMs: 5_000,
    autoRotate: true }).reason, 'candidate_delay');
  assert.equal(switching.update({ state: activeB, scope: { level: 'overview' }, atMs: 6_999,
    autoRotate: true }).changed, false);
  assert.equal(switching.update({ state: activeB, scope: { level: 'overview' }, atMs: 7_000,
    autoRotate: true }).changed, true);

  const manual = createAttentionCoordinator();
  manual.update({ state: activeA, scope: { level: 'overview' }, atMs: 0, autoRotate: true,
    manualInteraction: true });
  assert.equal(manual.update({ state: activeA, scope: { level: 'overview' }, atMs: 9_999,
    autoRotate: true }).status, 'suppressed');
  assert.equal(manual.update({ state: activeA, scope: { level: 'overview' }, atMs: 10_000,
    autoRotate: true }).status, 'candidate');
  assert.equal(manual.update({ state: activeA, scope: { level: 'overview' }, atMs: 12_000,
    autoRotate: true }).status, 'focus');

  assert.equal(manual.update({ state: activeA, scope: { level: 'overview' }, atMs: 13_000,
    autoRotate: false }).reason, 'auto_rotate_off');
  assert.equal(manual.snapshot().current, null);
  assert.equal(manual.update({ state: activeA, scope: { level: 'overview' }, atMs: 14_000,
    autoRotate: true, reducedMotion: true }).reason, 'reduced_motion');
  assert.equal(manual.update({ state: activeA, scope: { level: 'system', universeKey: 'u-a' }, atMs: 15_000,
    autoRotate: true }).status, 'candidate');
  assert.equal(manual.update({ state: state([call('new', 'u-a', 'g-a', 'r-a', 16_000)], 2),
    scope: { level: 'system', universeKey: 'u-a' }, atMs: 16_000, autoRotate: true }).status, 'candidate');
  assert.equal(manual.update({ state: state([call('new', 'u-a', 'g-a', 'r-a', 16_999)], 2),
    scope: { level: 'system', universeKey: 'u-a' }, atMs: 16_999, autoRotate: true }).changed, false);
  assert.equal(manual.update({ state: state([call('new', 'u-a', 'g-a', 'r-a', 18_000)], 2),
    scope: { level: 'system', universeKey: 'u-a' }, atMs: 18_000, autoRotate: true }).changed, true);

  assert.throws(() => createAttentionCoordinator({ candidateLeadMs: -1 }), /candidateLeadMs/);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
