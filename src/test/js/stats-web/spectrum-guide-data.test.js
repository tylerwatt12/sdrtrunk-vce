'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function checkSpectrumGuideData(assetsRoot) {
  const assets = path.resolve(assetsRoot || process.argv[2] || 'stats-web/assets');
  const { createSpectrumGuideData } = await import(pathToFileURL(path.join(assets,
    'guides/spectrum-presentation.js')).href);
  const native = { now: Date.now, setInterval: global.setInterval, clearInterval: global.clearInterval };
  const timers = new Map(), owned = [];
  let now = 1800000000000, nextTimer = 0;
  Date.now = () => now;
  global.setInterval = callback => { timers.set(++nextTimer, callback); return nextTimer; };
  global.clearInterval = id => timers.delete(id);
  const advance = milliseconds => {
    now += milliseconds;
    [...timers.values()].forEach(callback => callback());
  };
  const create = options => {
    const sample = createSpectrumGuideData(options);
    owned.push(sample);
    return sample;
  };
  try {
    const controller = new AbortController(), sample = create({ signal: controller.signal });
    const inventory = await sample.requestJson('/api/v1/admin/tuners');
    assert.equal(inventory.tuners.length, 2);
    assert.ok(inventory.tuners.every(tuner => tuner.tuner_class === 'RTL2832'));
    assert.equal(inventory.tuners[0].id, sample.selectedTunerId);
    assert.equal(inventory.tuners[0].frequency_hz, 856162500);
    assert.equal(inventory.tuners[0].channel_count, 1);
    assert.equal(inventory.tuners[1].channel_count, 0);
    inventory.tuners[0].name = 'Changed only in this response';
    assert.equal((await sample.requestJson('/api/v1/admin/tuners')).tuners[0].name, 'RTL-SDR 1');
    const browse = `/api/v1/admin/tuners/${sample.selectedTunerId}/browse`;
    const lease = await sample.requestJson(browse, { method: 'POST' });
    assert.equal(lease.takeover, false);
    assert.equal(lease.can_tune, false);
    assert.equal((await sample.requestJson(browse, { method: 'POST',
      body: { lease_id: lease.lease_id } })).lease_id, lease.lease_id);
    const targets = await sample.api('/api/v1/diagnostics/tuners');
    assert.equal(targets.rows.length, 2);
    assert.equal(targets.rows[0].target_id, lease.tuner.spectrum_target_id);
    const eligibility = frequency => '/api/v1/admin/spectrum-discovery/eligibility?' +
      new URLSearchParams({ tuner_id: sample.selectedTunerId, frequency_hz: frequency });
    assert.deepEqual(await sample.requestJson(eligibility(sample.selectedAnalogHz)), { eligible: true, matches: [] });
    const configured = await sample.requestJson(eligibility(856662500));
    assert.equal(configured.eligible, false);
    assert.equal(configured.matches[0].name, 'County P25');
    for (const invalid of [eligibility('NaN'), eligibility(20), eligibility(856412500.5),
      eligibility(856412500).replace(sample.selectedTunerId, 'actual-hardware-id'),
      `${eligibility(856412500)}&unknown=true`]) {
      await assert.rejects(sample.requestJson(invalid), /Unknown Spectrum example frequency/);
    }
    await assert.rejects(sample.requestJson(eligibility(856412500), { method: 'POST' }), /Unsupported/);
    for (const [endpoint, body] of [
      ['/api/v1/admin/channels', {}], [browse, { takeover: true }],
      [`/api/v1/admin/tuners/${sample.selectedTunerId}/state`, { state: 'setup' }],
      [`/api/v1/admin/tuners/${sample.selectedTunerId}/settings/frequency_mhz`, { value: 850 }]
    ]) await assert.rejects(sample.requestJson(endpoint, { method: 'POST', body }), /Unsupported/);
    await assert.rejects(sample.requestJson('/api/v1/admin/radioreference'), /Unsupported/);
    assert.throws(() => sample.connectionFactory('channel_audio', {}, {}), /Unsupported/);
    assert.throws(() => sample.connectionFactory('tuner_diagnostics', { target_id: 'real-target' }, {}), /Unknown/);

    const frames = [], snapshots = [], parameters = { target_id: lease.tuner.spectrum_target_id, profile: 'balanced' };
    const stream = sample.connectionFactory('tuner_diagnostics', parameters, { onFrame: frame => frames.push(frame) });
    const activity = sample.activityFeed({ snapshot: snapshot => snapshots.push(snapshot) }, { markers: true });
    await Promise.resolve();
    assert.equal(frames[0].type, 1);
    assert.equal(JSON.parse(new TextDecoder().decode(frames[0].payload)).target_id, parameters.target_id);
    const initialHistory = frames.filter(frame => frame.type === 4);
    assert.equal(initialHistory.length, 129, 'The real waterfall receives an immediately populated signal history.');
    assert.equal(initialHistory.at(-1).observedAtEpochMs - initialHistory[0].observedAtEpochMs, 20480);
    assert.equal(initialHistory.at(-1).observedAtEpochMs, now);
    assert.ok(initialHistory.every((frame, index) => index === 0 ||
      frame.observedAtEpochMs - initialHistory[index - 1].observedAtEpochMs === 160));
    const first = initialHistory.at(-1), values = new Float32Array(first.payload.buffer);
    const bin = frequency => Math.round((frequency - (first.centerFrequencyHz - first.sampleRateHz / 2)) /
      first.sampleRateHz * values.length);
    assert.equal(first.type, 4);
    assert.equal(first.valueCount, 2048);
    assert.ok(values.every(Number.isFinite));
    assert.ok(values[bin(856662500)] > -65, 'The control signal appears off the center frequency.');
    assert.ok(values[bin(sample.selectedAnalogHz)] > -75);
    assert.ok(values[bin(856912500)] > -70);
    assert.ok(values[bin(856162500)] < -105, 'The receiver center remains a quiet part of the window.');
    assert.ok(initialHistory.every(frame =>
      new Float32Array(frame.payload.buffer)[bin(856662500)] > -65), 'History retains a continuous control signal.');
    for (const frequencyHz of [sample.selectedAnalogHz, 856912500]) {
      const powers = initialHistory.map(frame => new Float32Array(frame.payload.buffer)[bin(frequencyHz)]);
      assert.ok(powers.some(value => value > -75) && powers.some(value => value < -105),
        'Initial history already distinguishes intermittent transmissions from the continuous control channel.');
    }
    assert.equal(snapshots[0].tables[0].rows[1].target_alias, 'Sheriff Dispatch');
    assert.equal(snapshots[0].tables[0].rows[1].status, 'CALL');
    assert.equal(snapshots[0].tables[0].rows[0].tags[0], 'CURRENT_CONTROL');
    assert.equal(sample.stats().timers, 2);
    advance(3500);
    const quiet = new Float32Array(frames.at(-1).payload.buffer);
    assert.ok(quiet[bin(sample.selectedAnalogHz)] < -105,
      'The sample transmission ends, leaving its trace in the real waterfall.');
    assert.ok(quiet[bin(856662500)] > -65, 'The control signal stays on the air.');
    advance(2100);
    assert.equal(snapshots.at(-1).tables[0].rows.length, 1, 'Voice activity follows the burst timing.');
    assert.equal(stream.update({ ...parameters, viewport_start_hz: 856300000,
      viewport_end_hz: 856700000 }), true);
    const refined = JSON.parse(new TextDecoder().decode(frames.at(-2).payload));
    assert.equal(refined.visible_start_hz, 856300000);
    assert.equal(refined.requested_end_hz, 856700000);
    assert.equal(frames.at(-1).generation, 2);
    assert.throws(() => stream.update({ target_id: 'real-target' }), /Unknown/);
    const observed = { frames: frames.length, snapshots: snapshots.length };
    controller.abort();
    await stream.whenClosed();
    assert.deepEqual({ ...sample.stats(), frames: 0, snapshots: 0 }, { disposed: true,
      connections: 0, activityFeeds: 0, timers: 0, leases: 0, frames: 0, snapshots: 0 });
    advance(30000);
    assert.equal(frames.length, observed.frames);
    assert.equal(snapshots.length, observed.snapshots);
    await assert.rejects(sample.requestJson('/api/v1/admin/tuners'), { name: 'AbortError' });
    assert.equal(stream.update(parameters), false);
    activity.close();

    const release = create();
    const releaseBrowse = `/api/v1/admin/tuners/${release.selectedTunerId}/browse`;
    const acquired = await release.requestJson(releaseBrowse, { method: 'POST' });
    assert.equal((await release.requestJson(releaseBrowse, { method: 'DELETE',
      body: { lease_id: acquired.lease_id } })).status, 'released');
    assert.equal(release.stats().leases, 0);
    const perRequest = new AbortController();
    perRequest.abort();
    await assert.rejects(release.requestJson('/api/v1/admin/tuners', { signal: perRequest.signal }),
      { name: 'AbortError' });

    const reentrantAbort = new AbortController(), reentrant = create({ signal: reentrantAbort.signal });
    reentrant.connectionFactory('tuner_diagnostics', parameters, { onFrame: () => reentrantAbort.abort() });
    await Promise.resolve();
    assert.equal(reentrant.stats().timers, 0, 'Aborting while the first frame arrives cannot start a timer.');
    const beforeOpen = create();
    const pending = beforeOpen.activityFeed({ snapshot: () => assert.fail('A closed source emitted data.') });
    pending.close();
    await Promise.resolve();
    assert.equal(beforeOpen.stats().timers, 0);
    const alreadyClosed = new AbortController();
    alreadyClosed.abort();
    const preAborted = create({ signal: alreadyClosed.signal });
    assert.throws(() => preAborted.activityFeed({}), { name: 'AbortError' });
    assert.equal(preAborted.stats().disposed, true);
  } finally {
    owned.forEach(sample => sample.dispose());
    Date.now = native.now;
    global.setInterval = native.setInterval;
    global.clearInterval = native.clearInterval;
    assert.equal(timers.size, 0, 'Every owned sample timer is released.');
  }
  console.log('Browser Spectrum guide data: isolated receivers, real diagnostic frames, burst activity, eligibility, and cleanup passed.');
}

module.exports = { checkSpectrumGuideData };
if (require.main === module) checkSpectrumGuideData().catch(error => { console.error(error); process.exitCode = 1; });
