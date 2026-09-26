'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const feature = path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/features/network-visualizer'));

async function main() {
  const { activityEventDetail, activityEventLabel, coalesceActivityEvents } = await import(
    `${pathToFileURL(path.join(feature, 'ui.js')).href}?ui-test=1`);
  const base = 1_700_100_000_000;
  const events = [
    { id: 'tx-1', kind: 'transmission_observed_after_gap', observedAtMs: base,
      radioLabel: 'Unit 1', groupLabel: 'Dispatch' },
    { id: 'tx-2', kind: 'transmission_observed_after_gap', observedAtMs: base + 200,
      radioLabel: 'Unit 2', groupLabel: 'Dispatch' },
    { id: 'change-1', kind: 'observed_affiliation_change', observedAtMs: base + 300,
      radioLabel: 'Unit 1', fromLabel: 'Dispatch', toLabel: 'Tactical' },
    { id: 'tx-3', kind: 'transmission_observed_after_gap', observedAtMs: base + 600,
      radioLabel: 'Unit 3', groupLabel: 'Tactical' },
    { id: 'change-2', kind: 'observed_affiliation_change', observedAtMs: base + 700,
      radioLabel: 'Unit 2', fromLabel: 'Dispatch', toLabel: 'Tactical' },
    { id: 'tx-4', kind: 'transmission_observed_after_gap', observedAtMs: base + 3_000,
      radioLabel: 'Unit 4', groupLabel: 'Tactical' }
  ];

  const rows = coalesceActivityEvents(events, { windowMs: 2_000 });
  assert.equal(rows.length, 5, 'a rapid routine burst should collapse without hiding changes');
  assert.deepEqual(rows.map((row) => row.kind), [
    'transmission_observed_after_gap',
    'observed_affiliation_change',
    'transmission_observed_after_gap',
    'observed_affiliation_change',
    'transmission_observed_after_gap'
  ]);
  assert.equal(rows[0].count, 2);
  assert.deepEqual(rows[0].sourceEventIds, ['tx-1', 'tx-2']);
  assert.equal(rows[1].count, 1);
  assert.equal(rows[3].count, 1);
  assert.notEqual(rows[1].id, rows[3].id, 'affiliation changes must remain individually addressable');
  assert.equal(rows[4].count, 1, 'routine events outside the grouping window start another row');

  const bounded = coalesceActivityEvents(events, { maximumSourceEvents: 3, windowMs: 2_000 });
  assert.deepEqual(bounded.flatMap((row) => row.sourceEventIds), ['tx-3', 'change-2', 'tx-4'],
    'the drawer should retain a bounded live-session tail');

  assert.equal(activityEventLabel({ kind: 'transmission_start' }), 'Grant');
  assert.equal(activityEventLabel({ kind: 'transmission_end', certainty: 'confirmed' }), 'Grant ended');
  assert.equal(activityEventLabel({ kind: 'transmission_end', certainty: 'uncertain',
    label: 'Grant activity became uncertain' }), 'Grant activity became uncertain');
  assert.equal(activityEventLabel({ kind: 'affiliation_observed' }), 'Join');
  assert.equal(activityEventLabel({ kind: 'explicit_presence_remove' }), 'Logout');
  assert.equal(activityEventLabel({ kind: 'signal_emergency' }), 'Emergency');
  assert.equal(activityEventDetail({ radioLabel: 'Unit 1', groupLabel: 'Dispatch', detail: 'SYSTEM BUSY' }),
    'Unit 1 · Dispatch — SYSTEM BUSY');
  const signals = coalesceActivityEvents([
    { id: 'deny-1', kind: 'signal_denial', observedAtMs: base, detail: 'DENY A' },
    { id: 'deny-2', kind: 'signal_denial', observedAtMs: base + 50, detail: 'DENY B' },
    { id: 'emergency-1', kind: 'signal_emergency', observedAtMs: base + 60, detail: 'EMERGENCY' },
    { id: 'emergency-2', kind: 'signal_emergency', observedAtMs: base + 70, detail: 'EMERGENCY' }
  ]);
  assert.equal(signals[0].count, 2, 'routine signaling bursts should be grouped');
  assert.equal(signals.filter((row) => row.kind === 'signal_emergency').length, 2,
    'emergencies remain individually visible');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
