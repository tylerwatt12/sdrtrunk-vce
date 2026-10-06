'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const modulePath = path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/features/listen-map.js'));

async function main() {
  const source = fs.readFileSync(modulePath, 'utf8');
  const map = await import(pathToFileURL(modulePath).href);
  const center = map.project(39.5, -98.35, 5);
  const inverse = map.unproject(center.x, center.y, 5);
  assert.ok(Math.abs(inverse.latitude - 39.5) < 0.000001);
  assert.ok(Math.abs(inverse.longitude + 98.35) < 0.000001);
  const tiles = map.visibleTiles(center, 5, 720, 480);
  assert.ok(tiles.length > 0 && tiles.length < 20, 'Only viewport tiles should load');
  assert.ok(tiles.every((tile) => tile.x >= 0 && tile.x < 32 && tile.y >= 0 && tile.y < 32));
  assert.ok(tiles.every((tile) => tile.left < 720 && tile.left + 256 > 0 &&
    tile.top < 480 && tile.top + 256 > 0), 'Every tile must intersect the viewport');
  const dateLinePoints = [{ latitude: 10, longitude: 179 }, { latitude: 10, longitude: -179 }];
  const fitted = map.fitPoints(dateLinePoints, 720, 480);
  const fittedLongitude = map.unproject(fitted.center.x, fitted.center.y, fitted.zoom).longitude;
  assert.ok(Math.abs(fittedLongitude + 180) < 0.000001,
    'Show all centers date-line neighbors on the short geographic arc');
  assert.ok(fitted.zoom >= 8, 'Show all does not zoom out to fit a false 358° span');
  const worldWidth = 256 * 2 ** fitted.zoom;
  for (const point of dateLinePoints) {
    const projected = map.project(point.latitude, point.longitude, fitted.zoom);
    assert.ok(Math.abs(map.wrappedOffset(projected.x, fitted.center.x, worldWidth)) <= 312,
      'Every fitted point is inside the visible map width');
    assert.ok(Math.abs(map.wrappedOffset(projected.x, fitted.center.x + 3 * worldWidth, worldWidth)) <= 312,
      'Markers stay visible after panning across multiple wrapped worlds');
  }
  const snapshot = map.normalizeSnapshot({ entities: [
    { id: 'radio-1', label: 'Engine 1', system: 'County P25', identifier: 'ISSI 778240.1183.1001',
      serving_system: { key: 'p25:bee00:4a2', name: 'County P25', wacn: 0xBEE00, system_id: 0x4A2,
        entity_ref: { kind: 'radio_system', key: 'p25:bee00:4a2' } },
      home_system: { key: 'p25:bee00:49f', name: 'GCRCN', wacn: 0xBEE00, system_id: 0x49F,
        entity_ref: { kind: 'radio_system', key: 'p25:bee00:49f' } },
      icon: 'fire-truck', color: '#123abc', positions: [
      { latitude: 39.6, longitude: -98.34, timestamp_ms: 3 },
      { latitude: 200, longitude: -98.35, timestamp_ms: 2 },
      { latitude: 39.5, longitude: -98.35, timestamp_ms: 1 }
    ] },
    { id: 'radio-2', icon: '../../private', positions: [{ latitude: 40, longitude: -99 }] }
  ] });
  assert.equal(snapshot.entities.length, 2);
  assert.equal(snapshot.entities[0].positions.length, 2);
  assert.equal(snapshot.entities[0].positions[0].timestamp_ms, 1, 'Trail begins with the oldest point');
  assert.equal(snapshot.entities[0].positions.at(-1).timestamp_ms, 3,
    'The moving marker and details use the newest receiver point');
  assert.equal(snapshot.entities[0].color, '#123abc');
  assert.equal(snapshot.entities[0].system, 'County P25');
  assert.equal(snapshot.entities[0].serving_system.system_id, 0x4A2,
    'The inspector retains the exact serving identity separately from the home identity');
  assert.equal(snapshot.entities[0].serving_system.entity_ref.key, 'p25:bee00:4a2');
  assert.equal(snapshot.entities[0].home_system_name, 'GCRCN', 'Home system names survive map normalization');
  assert.equal(snapshot.entities[0].identifier, 'ISSI 778240.1183.1001', 'The qualified Identifier stays exact');
  assert.equal(snapshot.entities[0].home_system.system_id, 0x49F);
  assert.deepEqual(map.mapSystemFacts(snapshot.entities[0], (ref, name) => `${ref.key}: ${name}`), [
    ['System', 'p25:bee00:4a2: County P25'], ['Radio identity', 'BEE00-4A2'],
    ['Home system', 'p25:bee00:49f: GCRCN'], ['Home radio identity', 'BEE00-49F']
  ], 'Map names link to their own serving/home systems and native identities remain distinct');
  assert.deepEqual(map.mapSystemFacts({ system: 'Conventional', system_name: 'Conventional',
    serving_system: { key: 'dmr:channel:channel-id', name: 'Conventional' } }),
  [['System', 'Conventional']], 'A saved channel scope does not become a radio-frequency identity');
  assert.equal(snapshot.entities[1].icon, 'no-icon');
  const localEntity = {
    id: 'local-radio', identifier: 'BEE00.49F.1872114 (Working ID 1872114)',
    home_wacn: 0xBEE00, home_system_id: 0x49F, radio_id: 1872114,
    serving_system: { key: 'p25:bee00:49f', name: 'GCRCN' },
    home_system_name: 'GCRCN', positions: [{ latitude: 40, longitude: -99 }]
  };
  const normalizedRadio = (overrides = {}) => map.normalizeSnapshot({
    entities: [{ ...localEntity, ...overrides }]
  }).entities[0];
  assert.equal(normalizedRadio().identifier, '1872114',
    'A local map radio uses the number and omits an equal Working ID.');
  assert.equal(normalizedRadio().label, '1872114', 'Raw radio fallback labels use the shared format.');
  assert.equal(normalizedRadio().id, 'local-radio', 'Presentation cannot change a track key.');
  assert.equal(normalizedRadio().raw_identifier, localEntity.identifier, 'The original identity remains inspectable.');
  assert.equal(normalizedRadio({ label: 'NRL-MEDIC 3' }).label, 'NRL-MEDIC 3', 'Configured alias labels survive.');
  assert.equal(normalizedRadio({ identifier: 'BEE00.49F.1872114 (Working ID 501)' }).identifier,
    '1872114 (Working ID 501)', 'A different confirmed Working ID remains useful.');
  assert.equal(normalizedRadio({ identifier: 'BEE01.49F.1872114 (Working ID 501)' }).identifier,
    '1872114', 'Unrelated text cannot supply a Working ID for the numeric identity facts.');
  assert.equal(normalizedRadio({ serving_system: { key: 'p25:bee00:4a2' } }).identifier,
    'GCRCN · 1872114', 'Foreign map radios retain their friendly home-system name.');
  assert.equal(normalizedRadio({ serving_system: { key: 'p25:bee01:49f' } }).identifier,
    'GCRCN · 1872114', 'Both WACN and SysID must match before hiding the home context.');
  assert.equal(normalizedRadio({ serving_system: { key: 'p25:bee00:4a2' }, home_system_name: '' }).identifier,
    'BEE00.49F.1872114', 'Unnamed foreign radios retain the full identity.');
  assert.equal(normalizedRadio({ serving_system: null }).identifier,
    'GCRCN · 1872114', 'An unknown serving system must not imply a local radio.');
  assert.equal(normalizedRadio({ radio_system_key: 'p25:bee00:4a2' }).identifier,
    'GCRCN · 1872114', 'An explicit receiving system takes precedence over nested serving context.');
  for (const missing of [null, undefined, '', false]) {
    assert.equal(normalizedRadio({ home_wacn: missing }).identifier, localEntity.identifier,
      'Incomplete native facts cannot reinterpret current or legacy radio text.');
  }
  const cutoff = map.positionSignature(snapshot.entities[0].positions[0]);
  assert.equal(map.positionsAfterCutoff(snapshot.entities[0].positions, cutoff).length, 1,
    'Deleting a track resets its browser-session history at the last observed point');
  assert.deepEqual(map.positionsAfterCutoff(snapshot.entities[0].positions, 'missing'),
    snapshot.entities[0].positions, 'A cutoff that aged out of bounded history admits the newer points');
  assert.throws(() => map.normalizeSnapshot({}), /invalid map snapshot/);
  assert.match(source, /image\.referrerPolicy = 'origin'/);
  assert.match(source, /tile\.openstreetmap\.org/);
  assert.match(source, /© OpenStreetMap contributors/);
  assert.doesNotMatch(source, /cache:\s*'no-store'/);
  assert.match(source, /window\.clearInterval\(timer\)/);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
