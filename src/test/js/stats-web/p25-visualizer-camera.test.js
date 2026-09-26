'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const feature = path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/features/network-visualizer'));

function event(key, category, observedAtMs, focusKeys = [`${category}-target`]) {
  return { key, category, observedAtMs, focusKeys };
}

async function main() {
  const { CAMERA_PRIORITY, createP25CameraCoordinator } = await import(
    `${pathToFileURL(path.join(feature, 'camera.js')).href}?camera-test=1`);
  assert.deepEqual(CAMERA_PRIORITY, {
    emergency: 4,
    movement: 3,
    denial: 3,
    patch: 2,
    busy: 2,
    queued: 2,
    check: 1,
    page: 1,
    logout: 1
  });

  const camera = createP25CameraCoordinator({ transitionMs: 100, focusMs: 500, returnMs: 100,
    cooldownMs: 800 });
  assert.equal(camera.snapshot().phase, 'roam', 'Auto starts in its centered idle-orbit state');

  const denial = camera.consider(event('denial:radio-1', 'denial', 10), 1_000);
  assert.equal(denial.accepted, true);
  assert.equal(denial.interrupted, false);
  assert.equal(denial.state.phase, 'focus');
  assert.equal(camera.update(1_099).phase, 'focus');
  assert.equal(camera.update(1_100).phase, 'hold');

  assert.equal(camera.consider(event('movement:radio-2', 'movement', 11), 1_200).accepted, false,
    'equal-priority activity is discarded during an accepted sequence');
  assert.equal(camera.consider(event('check:radio-3', 'check', 12), 1_200).accepted, false,
    'lower-priority activity is discarded rather than queued');

  const emergency = camera.consider(event('emergency:radio-4', 'emergency', 13), 1_250);
  assert.equal(emergency.accepted, true, 'strictly higher priority may interrupt focus or cooldown');
  assert.equal(emergency.interrupted, true);
  assert.equal(emergency.state.current.category, 'emergency');

  assert.equal(camera.consider(event('emergency:radio-4', 'emergency', 13), 1_300).accepted, false,
    'the same stored event must not replay camera attention');
  assert.equal(camera.update(1_350).phase, 'hold');
  assert.equal(camera.update(1_850).phase, 'return');
  assert.equal(camera.update(1_950).phase, 'roam', 'the accepted sequence returns to idle orbit');
  assert.equal(camera.snapshot().current, null);

  assert.equal(camera.consider(event('page:radio-5', 'page', 14), 2_000).accepted, false,
    'the cooldown remains global after the return completes');
  camera.update(2_050);
  assert.equal(camera.consider(event('page:radio-5', 'page', 14), 2_051).accepted, false,
    'a request discarded during cooldown must not be replayed later');
  assert.equal(camera.consider(event('page:radio-6', 'page', 15), 2_051).accepted, true,
    'a genuinely new event may focus after cooldown');

  const delayed = createP25CameraCoordinator({ transitionMs: 100, focusMs: 500, returnMs: 100,
    cooldownMs: 800 });
  delayed.consider(event('denial:background', 'denial', 16), 0);
  assert.equal(delayed.update(10_000).phase, 'hold');
  assert.equal(delayed.update(10_000).phase, 'return',
    'a delayed animation frame must still expose the return-home phase');
  assert.equal(delayed.update(10_000).phase, 'roam');

  const manual = createP25CameraCoordinator({ mode: 'manual' });
  assert.equal(manual.snapshot().phase, 'disabled');
  assert.equal(manual.consider(event('emergency:manual', 'emergency', 20), 2_000).accepted, false,
    'Manual mode never moves the camera automatically');
  assert.equal(manual.update(20_000).phase, 'disabled');
  assert.equal(manual.setMode('auto').phase, 'roam');
  assert.equal(manual.setMode('manual').phase, 'disabled');

  const noTarget = createP25CameraCoordinator();
  assert.equal(noTarget.consider(event('emergency:no-target', 'emergency', 30, []), 1).accepted, false,
    'events without a visible focus target cannot move the camera');

  console.log('P25 Visualizer camera checks passed.');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
