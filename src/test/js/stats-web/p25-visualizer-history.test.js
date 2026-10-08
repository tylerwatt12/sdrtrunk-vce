'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const fs = require('node:fs');
const vm = require('node:vm');

const feature = path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/features/network-visualizer'));

const SYSTEM_A = 'system-a';
const SYSTEM_B = 'system-b';

function row(id, action, overrides = {}) {
  return {
    id,
    observed_at_ms: 1_700_000_000_000 + id,
    protocol: 'p25',
    channel_kind: 'trunked_site',
    channel_id: 10,
    configuration_id: 'configuration-a',
    radio_system_key: SYSTEM_A,
    system_name: 'Metro P25',
    wacn: 0xbee00,
    system_id: 0x123,
    action,
    source_identity_key: 'v1-r-7001',
    source_radio_id: 7001,
    source_alias_name: 'Unit 7001',
    target_identity_key: 'v1-g-101',
    target_kind: 'talkgroup',
    target_id: 101,
    target_alias_name: 'Dispatch',
    ...overrides
  };
}

async function main() {
  const history = await import(`${pathToFileURL(path.join(feature, 'history.js')).href}?history-test=1`);
  const settings = await import(`${pathToFileURL(path.join(feature, 'settings.js')).href}?settings-test=1`);

  assert.deepEqual(history.P25_HISTORY_ACTIONS, [
    'JOIN', 'LOGOUT', 'DENIAL', 'EMERGENCY', 'CHECK', 'PAGE', 'BUSY', 'QUEUED',
    'PATCH', 'PATCH_CREATE', 'PATCH_CANCEL'
  ]);
  assert.deepEqual(history.P25_ROUTINE_ACTIONS, ['CALL', 'GRANT']);
  const defaults = settings.defaultP25EventSettings();
  assert.deepEqual(defaults.call, { highlight: false, autoZoom: false },
    'routine calls and grants must remain opt-in');
  assert.equal(settings.routineP25ActivityEnabled(defaults), false);
  const callsEnabled = settings.normalizeP25EventSettings({ call: { highlight: true, autoZoom: false } });
  assert.equal(settings.routineP25ActivityEnabled(callsEnabled), true);
  assert.equal(settings.enabledP25EventCategories(callsEnabled, 'highlight').has('call'), true);
  assert.equal(settings.enabledP25EventCategories(callsEnabled, 'autoZoom').has('call'), false);

  const state = history.createP25HistoryState();
  const seed = history.applyP25ActivityRows(state, [
    row(4, 'JOIN', { target_identity_key: 'v1-g-202', target_id: 202, target_alias_name: 'Tactical' }),
    row(1, 'ACTIVE'),
    row(3, 'JOIN'),
    row(2, 'JOIN', { protocol: 'dmr', radio_system_key: 'dmr-system' }),
    row(5, 'JOIN', { channel_kind: 'conventional', radio_system_key: '' })
  ], { initial: true });

  assert.equal(seed.accepted, 2, 'only supported P25 trunked history should enter the model');
  assert.equal(seed.ignored, 3, 'ordinary calls and non-P25/non-trunked rows should be ignored');
  assert.deepEqual(seed.focusCandidates, [], 'seed history must never request camera attention');
  assert.equal(state.systems.size, 1);
  assert.equal(state.systems.get(SYSTEM_A).name, 'Metro P25', 'The scope title has a friendly system name');
  assert.equal(state.systems.get(SYSTEM_A).identity, 'BEE00-123', 'The native system facts remain separate');
  assert.equal(state.systems.get(SYSTEM_A).label, 'Metro P25 · BEE00:123',
    'Graph context retains both the friendly name and exact P25 identity');
  assert.equal(state.radios.size, 1, 'a changed affiliation must move one stable radio node');
  assert.equal(state.groups.size, 2);
  assert.equal(history.groupedP25Events(state).length, 1);
  assert.equal(history.groupedP25Events(state)[0].category, 'movement');

  const radio = [...state.radios.values()][0];
  assert.equal(radio.visualGroupKey, `${SYSTEM_A}:v1-g-202`);
  const graph = history.buildP25Graph(state, SYSTEM_A, Number.MAX_SAFE_INTEGER);
  assert.equal(graph.nodes.filter((node) => node.type === 'system').length, 1,
    'the enclosing system sphere remains present inside the system');
  assert.equal(graph.nodes.filter((node) => node.type === 'talkgroup').length, 2);
  assert.equal(graph.nodes.filter((node) => node.type === 'radio').length, 1);
  assert.deepEqual(graph.links.map((link) => link.kind).sort(), ['current', 'history']);
  const dispatchGraph = history.buildP25Graph(state, SYSTEM_A, Number.MAX_SAFE_INTEGER,
    `${SYSTEM_A}:v1-g-101`);
  assert.deepEqual(dispatchGraph.nodes.map((node) => node.type).sort(), ['radio', 'system', 'talkgroup'],
    'talkgroup drill-down keeps its enclosing system, hub, and historically connected radios');
  assert.deepEqual(dispatchGraph.links.map((link) => link.kind), ['history'],
    'a radio that moved away remains connected by one faint historical link');
  const tacticalGraph = history.buildP25Graph(state, SYSTEM_A, Number.MAX_SAFE_INTEGER,
    `${SYSTEM_A}:v1-g-202`);
  assert.deepEqual(tacticalGraph.links.map((link) => link.kind), ['current'],
    'the selected talkgroup retains its current radio relationships');
  assert.equal(history.buildP25Graph(state, SYSTEM_A, 0).nodes.some((node) => node.signalAction), false,
    'seed history builds the scene without replaying old highlights');

  const routineState = history.createP25HistoryState();
  const routineSeed = history.applyP25ActivityRows(routineState, [row(50, 'GRANT')], { initial: true });
  assert.equal(routineSeed.accepted, 1);
  assert.deepEqual(routineSeed.focusCandidates, [], 'saved grant history must not replay camera attention');
  const routineRadio = [...routineState.radios.values()][0];
  assert.equal(routineRadio.visualGroupKey, '', 'a grant must not fabricate an affiliation');
  assert.equal(routineRadio.activityGroupKey, `${SYSTEM_A}:v1-g-101`);
  assert.deepEqual(history.buildP25Graph(routineState, SYSTEM_A).links.map((link) => link.kind), ['activity'],
    'a heard-on relationship is distinct from affiliation');
  const routineUpdate = history.applyP25ActivityRows(routineState, [row(51, 'CALL', {
    target_identity_key: 'v1-g-202', target_id: 202, target_alias_name: 'Tactical'
  })], { atMs: 2_000_000_000_000, highlightCategories: new Set(['call']) });
  assert.equal(routineUpdate.focusCandidates[0]?.category, 'call');
  assert.equal(routineUpdate.focusCandidates[0]?.priority, 0.5);
  assert.equal(routineRadio.visualGroupKey, '', 'call activity must still not become affiliation evidence');
  assert.equal(routineRadio.activityGroupKey, `${SYSTEM_A}:v1-g-202`);
  assert.equal(history.buildP25Graph(routineState, SYSTEM_A, 2_000_000_000_001, '', {
    highlightCategories: new Set(['call'])
  }).nodes.find((entry) => entry.id === routineRadio.key)?.signalAction, 'call');

  const timingState = history.createP25HistoryState();
  const highlightedCategories = new Set(['call']);
  history.applyP25ActivityRows(timingState, [row(70, 'CALL')], {
    atMs: 10_000, highlightCategories: highlightedCategories, grantActivityTimeoutMs: 1_200
  });
  assert.equal(history.nextP25HighlightExpiry(timingState, 10_000), 11_200);
  assert.equal(history.buildP25Graph(timingState, SYSTEM_A, 11_200).nodes.some((node) => node.signalAction), false);
  history.applyP25ActivityRows(timingState, [row(71, 'GRANT')], {
    atMs: 10_800, highlightCategories: highlightedCategories, grantActivityTimeoutMs: 1_200
  });
  assert.equal(history.nextP25HighlightExpiry(timingState, 10_800), 12_000,
    'another call or grant extends its visible activity timeout');
  history.applyP25ActivityRows(timingState, [row(71, 'GRANT')], {
    atMs: 11_900, highlightCategories: highlightedCategories, grantActivityTimeoutMs: 1_200
  });
  assert.equal(history.nextP25HighlightExpiry(timingState, 11_900), 12_000,
    'repeated delivery of the same saved row must not extend activity');
  assert.equal(history.nextP25HighlightExpiry(timingState, 12_000), 0);

  // Run the actual graph repaint against a local clock, with no polling or network work.
  const indexSource = fs.readFileSync(path.join(feature, 'index.js'), 'utf8');
  const renderGraphSource = indexSource.slice(indexSource.indexOf('  function renderGraph('),
    indexSource.indexOf('  function renderScopeTitle('));
  let now = 10_800;
  let timerId = 0;
  const scheduled = new Map();
  const frames = [];
  const context = {
    state: timingState, selectedSystemKey: SYSTEM_A, selectedGroupKey: '', highlightedCategories,
    buildP25Graph: history.buildP25Graph, nextP25HighlightExpiry: history.nextP25HighlightExpiry,
    renderer: { setData: (graph) => frames.push(graph) }, renderEvents: () => {},
    highlightTimer: 0, closed: false, Set, Date: { now: () => now },
    window: {
      setTimeout: (callback, delay) => { scheduled.set(++timerId, { callback, at: now + delay }); return timerId; },
      clearTimeout: (id) => scheduled.delete(id)
    }
  };
  vm.createContext(context);
  vm.runInContext(renderGraphSource, context);
  context.renderGraph(false);
  assert.equal(scheduled.size, 1);
  assert.equal([...scheduled.values()][0].at, 12_000);
  assert.equal(frames.at(-1).nodes.filter((node) => node.signalAction === 'call').length, 2);
  now = 11_500;
  history.applyP25ActivityRows(timingState, [row(72, 'CALL')], {
    atMs: now, highlightCategories: highlightedCategories, grantActivityTimeoutMs: 1_200
  });
  context.renderGraph(false);
  assert.equal(scheduled.size, 1, 'a repeated call replaces the pending expiry timer');
  const expiry = [...scheduled.values()][0];
  assert.equal(expiry.at, 12_700);
  now = expiry.at;
  scheduled.clear();
  expiry.callback();
  assert.equal(frames.at(-1).nodes.some((node) => node.signalAction), false,
    'the local timeout clears the rendered highlight without another history fetch');
  assert.equal(scheduled.size, 0);
  history.applyP25ActivityRows(timingState, [row(73, 'EMERGENCY')], { atMs: 13_000 });
  history.applyP25ActivityRows(timingState, [row(74, 'CALL')], {
    atMs: 13_100, grantActivityTimeoutMs: 1_200
  });
  assert.equal(history.nextP25HighlightExpiry(timingState, 13_100), 14_300,
    'routine activity uses its own timeout rather than inheriting a longer prior event highlight');
  const unknownSource = history.createP25HistoryState();
  history.applyP25ActivityRows(unknownSource, [row(52, 'GRANT', {
    source_identity_key: null, source_radio_id: null, source_alias_name: null
  })], { initial: true });
  assert.equal(unknownSource.groups.size, 1);
  assert.equal(unknownSource.radios.size, 0, 'a source-less grant must not fabricate a subscriber radio');

  const labelRow = row(60, 'EMERGENCY', {
    radio_system_key: 'p25:bee00:123', source_alias_name: '', source_talker_alias: '',
    source_identity_key: 'v1-r-bee00-123-7001',
    source_canonical_identity: { wacn: 0xBEE00, system_id: 0x123, subscriber_id: 7001 },
    source_observed_working_id: 7001
  });
  const displayedRadio = (overrides = {}) => {
    const labelState = history.createP25HistoryState();
    history.applyP25ActivityRows(labelState, [{ ...labelRow, ...overrides }], { initial: true });
    const value = [...labelState.radios.values()][0];
    assert.equal(value.identityKey, labelRow.source_identity_key, 'A display label cannot change radio identity.');
    assert.equal(history.buildP25Graph(labelState, labelRow.radio_system_key).nodes
      .find((entry) => entry.type === 'radio').label, value.label, 'Scene nodes use the shared radio label.');
    assert.ok(history.groupedP25Events(labelState)[0].detail.includes(value.label),
      'Visualizer event captions use the same radio presentation.');
    return value.label;
  };
  assert.equal(displayedRadio(), '7001', 'Local visualizer radios omit home prefixes and equal Working IDs.');
  assert.equal(displayedRadio({ source_alias_name: 'Engine 1' }), 'Engine 1', 'Configured aliases remain primary.');
  assert.equal(displayedRadio({ source_observed_working_id: 501 }), 'BEE00.123.7001 (Working ID 501)');
  assert.equal(displayedRadio({ source_canonical_identity: { wacn: 0xBEE00, system_id: 0x124, subscriber_id: 7001 },
    source_home_system_name: 'County P25' }), 'County P25 · 7001', 'Foreign home names appear in graph labels.');
  assert.equal(displayedRadio({ source_canonical_identity: { wacn: 0xBEE01, system_id: 0x123, subscriber_id: 7001 } }),
    'BEE01.123.7001', 'A different WACN needs home context even when SysID and number match.');
  assert.equal(displayedRadio({ source_canonical_identity: null, source_canonical_wacn: 0xBEE00,
    source_canonical_system_id: 0x124, source_canonical_subscriber_id: 7001 }), 'BEE00.124.7001',
    'The persisted Activity scalar identity fields use the same formatter as nested facts.');
  assert.equal(displayedRadio({ source_canonical_identity: { wacn: null, system_id: 0x124, subscriber_id: 7001 } }),
    '7001', 'Incomplete native facts preserve the existing observed-number fallback.');
  const evolvingLabels = history.createP25HistoryState();
  const foreignRow = { ...labelRow,
    source_canonical_identity: { wacn: 0xBEE00, system_id: 0x124, subscriber_id: 7001 } };
  history.applyP25ActivityRows(evolvingLabels, [foreignRow], { initial: true });
  history.applyP25ActivityRows(evolvingLabels, [{ ...foreignRow, id: 61,
    source_home_system_name: 'County P25' }], { initial: true });
  assert.equal([...evolvingLabels.radios.values()][0].label, 'County P25 · 7001',
    'An unnamed foreign label can acquire a known system name on a later observation.');
  history.applyP25ActivityRows(evolvingLabels, [{ ...foreignRow, id: 62,
    source_alias_name: 'Engine 1' }], { initial: true });
  assert.equal([...evolvingLabels.radios.values()][0].label, 'Engine 1',
    'A later configured alias can replace a formatted identity fallback.');
  const targetLabels = history.createP25HistoryState();
  history.applyP25ActivityRows(targetLabels, [row(63, 'DENIAL', {
    radio_system_key: 'p25:bee00:123', source_identity_key: null,
    target_identity_key: 'v1-r-bee00-123-7002', target_kind: 'radio', target_id: 7002,
    target_alias_name: '', target_canonical_identity: {
      wacn: 0xBEE00, system_id: 0x123, subscriber_id: 7002 }, target_observed_working_id: 7002,
    source_home_system_name: 'Wrong source home'
  })], { initial: true });
  assert.equal([...targetLabels.radios.values()][0].label, '7002',
    'Target radio activity uses its own home identity and hides an equal local Working ID.');

  const cloudRows = Array.from({ length: 220 }, (_, index) => row(100 + index, 'JOIN', {
    source_identity_key: `v1-r-${8_000 + index}`,
    source_radio_id: 8_000 + index,
    source_alias_name: `Unit ${8_000 + index}`,
    target_identity_key: `v1-g-${300 + index}`,
    target_id: 300 + index,
    target_alias_name: `Group ${300 + index}`
  }));
  const cloudState = history.createP25HistoryState();
  history.applyP25ActivityRows(cloudState, cloudRows, { initial: true });
  const cloudGraph = history.buildP25Graph(cloudState, SYSTEM_A, Number.MAX_SAFE_INTEGER);
  const cloudGroups = cloudGraph.nodes.filter((node) => node.type === 'talkgroup');
  const cloudRadios = cloudGraph.nodes.filter((node) => node.type === 'radio');
  const minimumGroupDistance = Math.min(...cloudGroups.flatMap((left, index) =>
    cloudGroups.slice(index + 1).map((right) => Math.hypot(left.x - right.x, left.y - right.y,
      left.z - right.z))));
  assert.ok(minimumGroupDistance >= 103.99,
    `talkgroup cloud anchors should remain visibly separated (minimum ${minimumGroupDistance})`);
  cloudRadios.forEach((cloudRadio) => {
    const parent = cloudGroups.find((group) => group.id === cloudRadio.groupKey);
    assert.ok(parent, 'each affiliated radio should retain its talkgroup cloud anchor');
    assert.ok(Math.hypot(cloudRadio.x - parent.x, cloudRadio.y - parent.y, cloudRadio.z - parent.z) <= 40.001,
      'affiliated radios should stay in a compact cloud around their talkgroup');
    const nearest = cloudGroups.slice().sort((left, right) =>
      Math.hypot(cloudRadio.x - left.x, cloudRadio.y - left.y, cloudRadio.z - left.z) -
      Math.hypot(cloudRadio.x - right.x, cloudRadio.y - right.y, cloudRadio.z - right.z))[0];
    assert.equal(nearest.id, parent.id, 'a radio should remain closest to its own talkgroup cloud anchor');
  });
  const repeatState = history.createP25HistoryState();
  history.applyP25ActivityRows(repeatState, cloudRows, { initial: true });
  const repeatedGroups = history.buildP25Graph(repeatState, SYSTEM_A, Number.MAX_SAFE_INTEGER).nodes
    .filter((node) => node.type === 'talkgroup').map(({ id, x, y, z }) => ({ id, x, y, z }));
  assert.deepEqual(repeatedGroups, cloudGroups.map(({ id, x, y, z }) => ({ id, x, y, z })),
    'the same saved history should always produce the same talkgroup cloud');

  const mixedRows = Array.from({ length: 500 }, (_, index) => row(1_000 + index, 'JOIN', {
    source_identity_key: `v1-r-${9_000 + index}`,
    source_radio_id: 9_000 + index,
    target_identity_key: `v1-g-${1_000 + index}`,
    target_id: 1_000 + index
  }));
  const mixedState = history.createP25HistoryState();
  history.applyP25ActivityRows(mixedState, mixedRows, { initial: true });
  const beforeMixedGroups = new Map(history.buildP25Graph(mixedState, SYSTEM_A, Number.MAX_SAFE_INTEGER).nodes
    .filter((node) => node.type === 'talkgroup').map(({ id, x, y, z }) => [id, { x, y, z }]));
  history.applyP25ActivityRows(mixedState, [{ ...mixedRows[0], id: 2_000,
    observed_at_ms: mixedRows[0].observed_at_ms + 10_000 }], { initial: true });
  const mixedGraph = history.buildP25Graph(mixedState, SYSTEM_A, Number.MAX_SAFE_INTEGER);
  const mixedGroups = mixedGraph.nodes.filter((node) => node.type === 'talkgroup');
  const mixedMinimum = Math.min(...mixedGroups.flatMap((left, index) => mixedGroups.slice(index + 1)
    .map((right) => Math.hypot(left.x - right.x, left.y - right.y, left.z - right.z))));
  assert.ok(mixedMinimum >= 103.99,
    'visible talkgroups should retain their separation when older groups become recent again');
  mixedGroups.filter((group) => beforeMixedGroups.has(group.id)).forEach((group) => {
    assert.deepEqual({ x: group.x, y: group.y, z: group.z }, beforeMixedGroups.get(group.id),
      'changing the visible set must not move retained talkgroup cloud anchors');
  });
  mixedGraph.nodes.filter((node) => node.type === 'radio' && node.groupKey).forEach((mixedRadio) => {
    const nearest = mixedGroups.slice().sort((left, right) =>
      Math.hypot(mixedRadio.x - left.x, mixedRadio.y - left.y, mixedRadio.z - left.z) -
      Math.hypot(mixedRadio.x - right.x, mixedRadio.y - right.y, mixedRadio.z - right.z))[0];
    assert.equal(nearest.id, mixedRadio.groupKey,
      'recency changes must not make a radio appear closer to another talkgroup cloud');
  });

  const ingestionTime = 2_000_000_000_000;
  const denied = history.applyP25ActivityRows(state, [
    row(6, 'DENIAL', {
      source_identity_key: null,
      source_identity_kind_code: null,
      target_identity_key: 'v1-r-7001',
      target_identity_kind_code: 2,
      target_kind: 'radio',
      target_id: 7001,
      target_alias_name: 'Unit 7001'
    }),
    row(7, 'DENIAL', {
      observed_at_ms: 1_700_000_000_500,
      source_identity_key: null,
      source_identity_kind_code: null,
      target_identity_key: 'v1-r-7001',
      target_identity_kind_code: 2,
      target_kind: 'radio',
      target_id: 7001,
      target_alias_name: 'Unit 7001'
    }),
    row(8, 'DENIAL', {
      source_identity_key: null,
      source_identity_kind_code: null,
      target_identity_key: null,
      target_identity_kind_code: null,
      target_kind: null
    })
  ], { atMs: ingestionTime });
  assert.equal(denied.accepted, 3, 'valid stored rows advance the high-water state even when not renderable');
  const groupedDenials = history.groupedP25Events(state).filter((event) => event.category === 'denial');
  assert.equal(groupedDenials.length, 1,
    'repeated denials are one current entry scoped to the canonical radio');
  assert.equal('count' in groupedDenials[0], false, 'the grouped entry must not expose a repeat counter');
  assert.equal(denied.focusCandidates.filter((event) => event.category === 'denial').length, 1,
    'rapid repeats must not repeatedly refocus the camera');
  assert.equal(radio.visualGroupKey, `${SYSTEM_A}:v1-g-202`, 'a denial must not move the radio');
  assert.equal(history.buildP25Graph(state, SYSTEM_A, ingestionTime + 1).nodes
    .find((node) => node.id === radio.key)?.signalAction, 'denial',
    'new highlights use ingestion time instead of already-old observation time');

  const unknownDenial = history.createP25HistoryState();
  history.applyP25ActivityRows(unknownDenial, [row(80, 'DENIAL', {
    source_identity_key: null,
    source_identity_kind_code: null,
    target_identity_key: null,
    target_identity_kind_code: null,
    target_kind: null
  })]);
  assert.equal(unknownDenial.systems.size, 0,
    'a denial without a scoped radio must not create a system or fabricated entity');

  const overlap = history.applyP25ActivityRows(state, [row(7, 'DENIAL')]);
  assert.equal(overlap.accepted, 0);
  assert.equal(overlap.ignored, 1, 'overlapping forward pages must deduplicate by stored row ID');

  history.applyP25ActivityRows(state, [row(9, 'JOIN', {
    radio_system_key: SYSTEM_B,
    configuration_id: 'configuration-b',
    system_name: 'County P25',
    source_identity_key: 'v1-r-7001',
    target_identity_key: 'v1-g-101'
  })], { initial: true });
  assert.equal(state.radios.size, 2, 'matching numeric IDs in different systems must remain separate');
  assert.equal(state.systems.size, 2);
  assert.equal(history.mostActiveP25System(state).key, SYSTEM_A,
    'the overview should select the system with the greatest noteworthy score');

  const firstOnly = history.createP25HistoryState();
  history.applyP25ActivityRows(firstOnly, [row(20, 'JOIN')], { initial: true });
  assert.equal(history.groupedP25Events(firstOnly).length, 0,
    'the first affiliation in the window is placement, not an invented movement');

  const incompatibleScopes = history.createP25HistoryState();
  history.applyP25ActivityRows(incompatibleScopes, [
    row(30, 'JOIN', { configuration_id: 'site-a' }),
    row(31, 'JOIN', { configuration_id: 'site-b', target_identity_key: 'v1-g-202', target_id: 202 })
  ], { initial: true });
  assert.equal(history.groupedP25Events(incompatibleScopes).length, 0,
    'different observation scopes must not fabricate an affiliation change');
  history.applyP25ActivityRows(incompatibleScopes, [row(32, 'LOGOUT', { configuration_id: 'site-b' })],
    { initial: true });
  assert.equal([...incompatibleScopes.radios.values()][0].visualGroupKey, `${SYSTEM_A}:v1-g-101`,
    'a logout clears only its compatible observation scope');

  console.log('P25 Visualizer history checks passed.');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
