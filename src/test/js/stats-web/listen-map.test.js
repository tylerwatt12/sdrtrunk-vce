'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const modulePath = path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/features/listen-map.js'));

async function main() {
  const source = fs.readFileSync(modulePath, 'utf8');
  const map = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
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
    { id: 'radio-1', label: 'Engine 1', icon: 'fire-truck', color: '#123abc', positions: [
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
  assert.equal(snapshot.entities[1].icon, 'no-icon');
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
