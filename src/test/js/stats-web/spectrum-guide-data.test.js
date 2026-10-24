'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function checkSpectrumGuideData(assetsRoot) {
  const assets = path.resolve(assetsRoot || process.argv[2] || 'stats-web/assets');
  const { createSpectrumGuideData } = await import(pathToFileURL(path.join(assets,
    'guides/spectrum-presentation.js')).href);
  const { createSpectrumAutoRange } = await import(pathToFileURL(path.join(assets,
    'features/spectrum-auto-range.js')).href);
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
    assert.equal(sample.selectedCarrierHz, 856912500);
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
    const initialStates = frames.filter(frame => frame.type === 1);
    assert.deepEqual(initialStates.map(frame => ({ generation: frame.generation,
      center: JSON.parse(new TextDecoder().decode(frame.payload)).center_frequency_hz })),
    [{ generation: 1, center: 855662500 }, { generation: 2, center: 856162500 }],
    'Real state frames move the receiving window, triggering the production waterfall retune divider.');
    const accepted = createSpectrumAutoRange();
    accepted.selectTarget(parameters.target_id);
    frames.forEach((frame, index) => {
      assert.equal(frame.sequence, index + 1, 'Seeded states and FFTs retain transport ordering.');
      if (frame.type === 1) {
        const state = JSON.parse(new TextDecoder().decode(frame.payload));
        assert.equal(accepted.confirm({ targetId: state.target_id, centerFrequencyHz: state.center_frequency_hz,
          sampleRateHz: state.sample_rate_hz, generation: frame.generation, revision: frame.sequence,
          live: state.stream_state === 'live' }), true);
      } else assert.equal(accepted.acceptsFrame(frame.generation), true,
        'Every seeded FFT follows its own accepted state generation.');
    });
    const initialHistory = frames.filter(frame => frame.type === 4);
    assert.equal(initialHistory.length, 129, 'The real waterfall receives an immediately populated signal history.');
    assert.equal(initialHistory.at(-1).observedAtEpochMs - initialHistory[0].observedAtEpochMs, 20480);
    assert.equal(initialHistory.at(-1).observedAtEpochMs, now);
    assert.ok(initialHistory.every((frame, index) => index === 0 ||
      frame.observedAtEpochMs - initialHistory[index - 1].observedAtEpochMs === 160));
    const oldHistory = initialHistory.filter(frame => frame.generation === 1);
    const currentHistory = initialHistory.filter(frame => frame.generation === 2);
    assert.equal(oldHistory.length, 64);
    assert.equal(currentHistory.length, 65);
    assert.ok(oldHistory.every(frame => frame.centerFrequencyHz === 855662500));
    assert.ok(currentHistory.every(frame => frame.centerFrequencyHz === inventory.tuners[0].frequency_hz));
    assert.equal(currentHistory[0].observedAtEpochMs, now - 10240,
      'The retained current-window history leaves the genuine retune boundary visible.');
    const first = initialHistory.at(-1), values = new Float32Array(first.payload.buffer);
    const bin = (frequency, frame = first) => Math.round((frequency -
      (frame.centerFrequencyHz - frame.sampleRateHz / 2)) / frame.sampleRateHz * frame.fftSize) - frame.firstBin;
    assert.equal(first.type, 4);
    assert.equal(first.valueCount, 2048);
    assert.ok(values.every(Number.isFinite));
    assert.ok(values[bin(856662500)] > -65, 'The control signal appears off the center frequency.');
    assert.ok(values[bin(sample.selectedAnalogHz)] > -75);
    assert.ok(values[bin(856912500)] > -70);
    assert.ok(values[bin(856162500)] < -105, 'The receiver center remains a quiet part of the window.');
    assert.ok(initialHistory.every(frame =>
      new Float32Array(frame.payload.buffer)[bin(856662500, frame)] > -65),
    'Historical rows retain their actual frequency domains and a continuous control signal.');
    for (const frequencyHz of [sample.selectedAnalogHz, 856912500]) {
      const powers = currentHistory.map(frame => new Float32Array(frame.payload.buffer)[bin(frequencyHz, frame)]);
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
    assert.ok(refined.visible_start_hz <= 856300000);
    assert.ok(refined.visible_start_hz > 856300000 - first.sampleRateHz / first.fftSize);
    assert.equal(refined.requested_end_hz, 856700000);
    assert.equal(frames.at(-1).generation, 3);
    const zoomed = frames.at(-1);
    assert.ok(zoomed.firstBin > 0 && zoomed.sourceBinCount < 2048);
    assert.equal(zoomed.valueCount, zoomed.sourceBinCount);
    assert.equal(zoomed.payload.byteLength, zoomed.valueCount * Float32Array.BYTES_PER_ELEMENT);
    assert.equal(zoomed.centerFrequencyHz - zoomed.sampleRateHz / 2 +
      zoomed.firstBin * zoomed.sampleRateHz / zoomed.fftSize, refined.visible_start_hz);
    assert.equal(refined.visible_end_hz, refined.visible_start_hz +
      zoomed.sourceBinCount * zoomed.sampleRateHz / zoomed.fftSize);
    assert.ok(new Float32Array(zoomed.payload.buffer)[bin(856662500, zoomed)] > -65,
      'Actual zoom refinement retains the selected signal inside the acknowledged FFT domain.');
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

    for (const section of ['channel-marker', 'channel-details']) {
      const steady = create({ section }), steadyFrames = [], steadySnapshots = [];
      const steadyStream = steady.connectionFactory('tuner_diagnostics', parameters,
        { onFrame: frame => steadyFrames.push(frame) });
      steady.activityFeed({ snapshot: snapshot => steadySnapshots.push(snapshot) });
      await Promise.resolve();
      for (let index = 0; index < 30; index++) {
        advance(350);
        const voice = steadySnapshots.at(-1).tables[0].rows.find(row => row.frequency_hz === steady.selectedCarrierHz);
        assert.equal(voice.status, 'CALL');
        assert.equal(voice.target_alias, 'Sheriff Dispatch');
        assert.equal(voice.target_id, 1001);
        assert.equal(voice.source_alias, 'Sheriff 214');
        assert.equal(voice.source_id, 214);
        assert.equal(voice.vc_quality_pct, 98.7);
        assert.equal(steadySnapshots.at(-1).tables[0].system_name, 'County P25');
        assert.equal(steadySnapshots.at(-1).tables[0].site_name, 'North Site');
        const frame = steadyFrames.at(-1);
        assert.ok(new Float32Array(frame.payload.buffer)[bin(steady.selectedCarrierHz, frame)] > -70,
          'The real FFT peak and activity details remain present throughout the channel lesson.');
      }
      steady.dispose();
      await steadyStream.whenClosed();
      assert.equal(steady.stats().timers, 0);
    }

    const historyController = new AbortController();
    const held = create({ section: 'waterfall-details', signal: historyController.signal });
    const heldFrames = [], heldSnapshots = [];
    const heldBrowse = `/api/v1/admin/tuners/${held.selectedTunerId}/browse`;
    await held.requestJson(heldBrowse, { method: 'POST' });
    const heldStream = held.connectionFactory('tuner_diagnostics', parameters,
      { onFrame: frame => heldFrames.push(frame) });
    held.activityFeed({ snapshot: snapshot => heldSnapshots.push(snapshot) });
    await Promise.resolve();
    const heldHistory = heldFrames.filter(frame => frame.type === 4);
    assert.equal(heldHistory.length, 129);
    assert.deepEqual([...new Set(heldHistory.map(frame => frame.centerFrequencyHz))],
      [855662500, 856162500], 'The held lesson retains genuine observations from both receiving windows.');
    const seededCount = heldFrames.length, firstSnapshotCount = heldSnapshots.length;
    for (let index = 0; index < 180; index++) advance(1000);
    assert.equal(heldFrames.length, seededCount,
      'A three-minute reading dwell cannot scroll the retained retune divider out of the real waterfall.');
    assert.deepEqual(heldFrames.filter(frame => frame.type === 4), heldHistory);
    assert.ok(heldSnapshots.length > firstSnapshotCount, 'Activity remains available beside the captured history.');
    assert.equal(held.stats().connections, 1);
    assert.equal(held.stats().leases, 1);
    assert.equal(held.stats().timers, 1, 'Only the activity source owns a timer during the history lesson.');
    assert.equal(heldStream.update({ ...parameters, viewport_start_hz: 856300000,
      viewport_end_hz: 856700000 }), true);
    assert.equal(heldFrames.length, seededCount + 2,
      'Explicit viewport refinement still receives its state and matching FFT acknowledgement.');
    assert.deepEqual(heldFrames.slice(-2).map(frame => frame.type), [1, 4]);
    assert.equal(heldFrames.at(-1).generation, heldFrames.at(-2).generation);
    advance(180000);
    assert.equal(heldFrames.length, seededCount + 2, 'Refinement does not restart periodic history replacement.');
    historyController.abort();
    await heldStream.whenClosed();
    assert.deepEqual({ ...held.stats(), frames: 0, snapshots: 0 }, { disposed: true,
      connections: 0, activityFeeds: 0, timers: 0, leases: 0, frames: 0, snapshots: 0 });

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
