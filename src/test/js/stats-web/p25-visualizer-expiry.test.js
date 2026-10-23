'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { pathToFileURL } = require('node:url');
const feature = path.resolve(process.argv[2] || path.resolve(__dirname,
  '../../../../stats-web/assets/features/network-visualizer'));
const row = (id, at, overrides = {}) => ({ id, observed_at_ms: at, action: 'JOIN', protocol: 'p25',
  channel_kind: 'trunked_site', radio_system_key: 'history', source_identity_kind_code: 2, source_identity_key: `r${id}`,
  target_identity_key: `g${id}`, target_kind: 'talkgroup', ...overrides });
const fact = (radio = 'native', group = 'native', at = 1, system = 'native') => ({
  radio_system_key: system, radio_identity_key: radio, talkgroup_identity_key: group,
  configuration_id: 'site', confirmed_at_ms: at, radio_id: 1, talkgroup_id: 1 });

async function main() {
  const history = await import(pathToFileURL(path.join(feature, 'history.js')).href);
  const apply = (state, rows) => history.applyP25ActivityRows(state, rows, { initial: true });
  const native = (state, facts, options = {}) => history.applyP25Affiliations(state,
    { affiliations: facts, available: options.available }, { ...options, initial: true });

  for (const hours of [1, 24]) {
    const now = 10 * 86_400_000, cutoff = now - hours * 3_600_000;
    const state = history.createP25HistoryState();
    native(state, Array.from({ length: 10_001 }, (_, id) => fact(`native${id}`)), { replace: true, available: false });
    apply(state, [row(1, cutoff - 1), row(2, cutoff)]);
    const cursors = [state.historyAfterId, state.liveAfterId];
    assert.equal(history.expireP25History(state, cutoff).changed, true);
    assert.equal(state.currentAffiliations.size, 10_001, `${hours}h keeps every ancient unavailable native fact`);
    assert.equal(state.available, false);
    assert.equal(state.radios.size, 10_002);
    assert.equal(state.groups.size, 2);
    assert.equal(state.radios.has('history:r1'), false);
    assert.equal(state.radios.has('history:r2'), true, 'the exact time boundary remains included');
    assert.deepEqual([state.historyAfterId, state.liveAfterId], cursors, 'expiry cannot rewind verified cursors');
  }

  const cutoff = 100_000, links = history.createP25HistoryState();
  native(links, [fact('radio', 'current', cutoff + 1, 'system')]);
  apply(links, [row(1, cutoff - 2, { radio_system_key: 'system', source_identity_key: 'radio',
    target_identity_key: 'old', action: 'CALL' }), row(2, cutoff - 1, {
    radio_system_key: 'system', source_identity_key: 'radio', target_identity_key: 'old' }),
  row(3, cutoff, { radio_system_key: 'system', source_identity_key: 'radio', target_identity_key: 'recent' })]);
  history.expireP25History(links, cutoff);
  const radio = links.radios.get('system:radio');
  assert.equal(radio.activityGroupKey, '', 'recent native confirmation does not preserve an old heard-on link');
  assert.deepEqual(radio.priorGroupKeys, ['system:recent']);
  assert.equal(links.groups.has('system:old'), false);
  assert.equal(links.groups.has('system:current'), true);
  assert.equal([...links.events.values()].every(event => event.observedAtMs >= cutoff), true);
  native(links, [], { replace: true });
  history.expireP25History(links, cutoff + 2);
  assert.equal(links.currentAffiliations.size, 0);
  assert.equal(links.radios.size + links.groups.size + links.systems.size, 0,
    'removing native protection permits expiry despite prior sticky current flags');
  assert.deepEqual(links.systemOrdinals, { next: 0, free: [], changed: false });

  const layout = history.createP25HistoryState();
  apply(layout, [row(1, 1, { radio_system_key: 'gone' }), row(2, 1, {
    radio_system_key: 'kept', source_identity_key: 'a', target_identity_key: 'a' })]);
  native(layout, [fact('b', 'b', 1, 'kept')]);
  apply(layout, [row(3, 1, { radio_system_key: 'kept', source_identity_key: 'c', target_identity_key: 'c' })]);
  const system = layout.systems.get('kept'), anchor = layout.groups.get('kept:b');
  const position = { x: anchor.x, y: anchor.y, z: anchor.z }, systemOrdinal = system.ordinal;
  history.expireP25History(layout, 2);
  assert.equal(system.groupSlots.length, 0, 'expired layout scratch is released without moving retained anchors');
  apply(layout, [row(4, 3, { radio_system_key: 'new' }), row(5, 3, {
    radio_system_key: 'kept', source_identity_key: 'd', target_identity_key: 'd' })]);
  assert.notEqual(layout.systems.get('new').ordinal, systemOrdinal);
  assert.notEqual(layout.groups.get('kept:d').ordinal, anchor.ordinal);
  assert.deepEqual({ x: anchor.x, y: anchor.y, z: anchor.z }, position);
  assert.ok(Math.hypot(anchor.x - layout.groups.get('kept:d').x,
    anchor.y - layout.groups.get('kept:d').y, anchor.z - layout.groups.get('kept:d').z) >= 103.99);

  const cycling = history.createP25HistoryState();
  native(cycling, [fact()]);
  let id = 0;
  for (let cycle = 1; cycle <= 6; cycle++) {
    const at = cycle * 7_200_000;
    apply(cycling, Array.from({ length: 601 }, () => row(++id, at)));
    assert.equal(cycling.radios.size, 602, 'the selected window has no total entity cutoff');
    history.expireP25History(cycling, at + 1);
    assert.equal(cycling.radios.size, 1);
    assert.equal(cycling.groups.size, 1);
    assert.equal(cycling.systems.size, 1);
    assert.equal(cycling.systemOrdinals.next, 1);
    assert.equal(cycling.systemOrdinals.free.length, 0);
    assert.equal(cycling.systems.get('native').groupOrdinals.next, 1);
    assert.equal(cycling.events.size, 0);
  }
  assert.equal(cycling.historyAfterId, id, 'many rolling windows keep cursor progress without storing old rows');
  assert.equal(Object.hasOwn(cycling, 'recentRows'), false);

  const ordered = history.createP25HistoryState();
  apply(ordered, [row(1, 200), row(2, 100, { source_identity_key: 'r1', target_identity_key: 'g1' })]);
  history.expireP25History(ordered, 150);
  assert.equal(ordered.radios.get('history:r1').lastAtMs, 200,
    'a later ID with an old timestamp cannot shorten the entity lifetime');
  history.expireP25History(ordered, 201); assert.equal(ordered.systems.size, 0);

  const scoring = history.createP25HistoryState();
  native(scoring, [fact('radio', 'group', 1, 'old')]);
  apply(scoring, [row(1, 10, { radio_system_key: 'old', action: 'EMERGENCY' }),
    row(2, 100, { radio_system_key: 'recent', action: 'PAGE' })]);
  assert.equal(history.mostActiveP25System(scoring).key, 'old');
  history.expireP25History(scoring, 50);
  assert.equal(history.mostActiveP25System(scoring).key, 'recent',
    'selection uses retained grouped priorities rather than an expired lifetime score');

  const index = fs.readFileSync(path.join(feature, 'index.js'), 'utf8');
  const expirySource = index.match(/  function expireHistory\(\) \{[\s\S]+?\n  \}/)[0];
  const visibilitySource = index.match(/  function onVisibilityChanged\(\) \{[\s\S]+?\n  \}/)[0];
  let now = 3_600_100, runs = 0, updates = 0, visibility = 0;
  const timed = history.createP25HistoryState(); apply(timed, [row(1, 100)]);
  const context = { state: timed, historyHours: 1, nextHistoryExpiryAtMs: 0, serverTimeOffsetMs: 0,
    historySceneDirty: true, Date: { now: () => now }, document: { hidden: false }, pressedKeys: new Set(['w']),
    expireP25History(state, cutoffMs) { runs++; return history.expireP25History(state, cutoffMs); },
    updateScene(result) { assert.equal(result.changed, true); updates++; },
    feed: { visibilityChanged() { visibility++; } } };
  vm.createContext(context); vm.runInContext(expirySource + '\n' + visibilitySource, context);
  context.expireHistory(); assert.equal(updates, 0, 'expiry retains the exact rolling boundary');
  now += 59_999; context.expireHistory(); assert.equal(runs, 1, 'full scans are limited to once a minute');
  now++; context.expireHistory(); assert.equal(updates, 1); assert.equal(timed.systems.size, 0);
  assert.equal(context.historySceneDirty, false);
  now += 60_000; context.document.hidden = true; context.onVisibilityChanged(); assert.equal(runs, 2);
  context.document.hidden = false; context.onVisibilityChanged(); assert.equal(runs, 3);
  assert.equal(visibility, 2); assert.equal(context.pressedKeys.size, 0);
  assert.match(index, /if \(!document\.hidden\) expireHistory\(\);/);
  assert.doesNotMatch(expirySource, /requestActivity|reloadHistory|reseed|loadSeed/,
    'quiet-receiver expiry makes no new history or native requests');
  console.log('P25 rolling history expiry, native protection, geometry reuse and quiet-page contracts passed.');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
