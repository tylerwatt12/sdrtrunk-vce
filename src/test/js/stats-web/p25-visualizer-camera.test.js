'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const feature = path.resolve(process.argv[2] || path.resolve(__dirname,
  '../../../../stats-web/assets/features/network-visualizer'));
const event = (key, category, observedAtMs, focusKeys = [`${category}-target`]) =>
  ({ key, category, observedAtMs, focusKeys });

async function main() {
  const { createP25CameraCoordinator } = await import(pathToFileURL(path.join(feature, 'camera.js')).href);
  const camera = createP25CameraCoordinator({ transitionMs: 100, focusMs: 500, returnMs: 100 });
  assert.equal(camera.snapshot().phase, 'roam');
  assert.equal(camera.consider(event('emergency:one', 'emergency', 10), 1_000).accepted, true);
  assert.equal(camera.consider(event('movement:two', 'movement', 11), 1_200).accepted, true,
    'new selected actions replace attention regardless of event priority');
  assert.equal(camera.snapshot().current.category, 'movement');
  assert.equal(camera.consider(event('movement:two', 'movement', 11), 1_300).accepted, false,
    'duplicate delivery does not restart attention');
  assert.equal(camera.update(1_799).phase, 'focus');
  assert.equal(camera.update(1_800).phase, 'return');
  assert.equal(camera.update(1_900).phase, 'roam');
  assert.equal(camera.consider(event('check:three', 'check', 12), 1_901).accepted, true,
    'there is no cooldown after returning');
  camera.reset();
  assert.equal(camera.update(1_902).changed, true, 'a reset explicitly propagates normal scene state');
  assert.equal(camera.snapshot().phase, 'roam');
  assert.equal(camera.consider(event('check:three', 'check', 12), 1_903).accepted, false,
    'resetting presentation does not forget delivered event identities');
  camera.consider(event('page:four', 'page', 13), 2_000);
  assert.equal(camera.update(100_000).phase, 'return', 'late frames still initiate a complete return');
  assert.equal(camera.update(100_099).phase, 'return');
  assert.equal(camera.update(100_100).phase, 'roam');
  camera.setMode('manual');
  assert.equal(camera.update(200_000).phase, 'disabled');
  assert.equal(camera.consider(event('emergency:manual', 'emergency', 20), 200_000).accepted, false);
  camera.setMode('auto');
  assert.equal(camera.snapshot().phase, 'roam');
  assert.equal(camera.consider(event('empty', 'emergency', 30, []), 200_000).accepted, false);
  console.log('P25 Visualizer camera checks passed.');
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
