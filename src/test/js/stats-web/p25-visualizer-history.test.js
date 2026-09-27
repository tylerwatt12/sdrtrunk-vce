'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

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

  assert.deepEqual(history.P25_HISTORY_ACTIONS, [
    'JOIN', 'LOGOUT', 'DENIAL', 'EMERGENCY', 'CHECK', 'PAGE', 'BUSY', 'QUEUED',
    'PATCH', 'PATCH_CREATE', 'PATCH_CANCEL'
  ]);

  const state = history.createP25HistoryState();
  const seed = history.applyP25ActivityRows(state, [
    row(4, 'JOIN', { target_identity_key: 'v1-g-202', target_id: 202, target_alias_name: 'Tactical' }),
    row(1, 'CALL'),
    row(3, 'JOIN'),
    row(2, 'JOIN', { protocol: 'dmr', radio_system_key: 'dmr-system' }),
    row(5, 'JOIN', { channel_kind: 'conventional', radio_system_key: '' })
  ], { initial: true });

  assert.equal(seed.accepted, 2, 'only supported P25 trunked history should enter the model');
  assert.equal(seed.ignored, 3, 'ordinary calls and non-P25/non-trunked rows should be ignored');
  assert.deepEqual(seed.focusCandidates, [], 'seed history must never request camera attention');
  assert.equal(state.systems.size, 1);
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
  assert.equal(history.buildP25Graph(state, SYSTEM_A, 0).nodes.some((node) => node.signalAction), false,
    'seed history builds the scene without replaying old highlights');

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
