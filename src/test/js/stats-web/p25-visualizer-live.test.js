'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const feature = path.resolve(process.argv[2] || path.resolve(__dirname, '../../../../stats-web/assets/features/network-visualizer'));
const tick = () => new Promise((resolve) => setImmediate(resolve));
function deferred() { let resolve; const promise = new Promise((done) => { resolve = done; }); return { promise, resolve }; }
const snapshot = (sequence = 10, overrides = {}) => ({ source_epoch: 'epoch-a', sequence,
  rows: [{ id: sequence }], affiliations: [{ radio_identity_key: `r${sequence}` }], available: true, affiliations_truncated: false, ...overrides });
const delta = (base, sequence, overrides = {}) => ({ source_epoch: 'epoch-a', base_sequence: base, sequence,
  rows: [], removed_ids: [], affiliations: [], removed_radio_keys: [], available: true, affiliations_truncated: false, part: 0, parts: 1, ...overrides });

async function main() {
  const { createP25ActivityFeed } = await import(pathToFileURL(path.join(feature, 'live.js')).href);
  const pending = deferred(), sources = [], events = [], timers = new Map(), parameters = [];
  let loads = 0, timerId = 0, hidden = false, lastSignal, next = snapshot(14);
  const feed = createP25ActivityFeed({
    openStream(callbacks) { const source = { callbacks, closed: false, close() { this.closed = true; } };
      sources.push(source); events.push('subscribe'); return source; },
    loadSeed(signal, options) { loads++; lastSignal = signal; parameters.push(options); events.push('seed');
      return loads === 1 ? pending.promise : Promise.resolve(next); },
    onSeed(document) { events.push({ seed: document }); }, onDelta(document) { events.push({ delta: document }); },
    onStatus(status) { events.push(status); }, isHidden: () => hidden,
    setTimeout(callback, delay) { timers.set(++timerId, { callback, delay }); return timerId; },
    clearTimeout(id) { timers.delete(id); }
  });
  assert.equal(loads, 0, 'register the stream before requesting the shared full seed');
  const first = sources[0].callbacks;
  first.boundary({ source_epoch: 'epoch-a', sequence: 10 });
  first.delta(delta(10, 11, { rows: [{ id: 11, name: 'older frame' }] }));
  pending.resolve(snapshot(11, { rows: [{ id: 11, name: 'fresh seed' }] })); await tick();
  assert.equal(events.filter(event => event?.delta).length, 0, 'snapshot R supersedes every buffered frame through R');
  assert.deepEqual(events.find(event => event?.seed).seed.rows, [{ id: 11, name: 'fresh seed' }]);
  first.delta(delta(11, 12, { rows: [{ id: 12 }], parts: 2, part: 1 }));
  assert.equal(events.filter(event => event?.delta).length, 0, 'multipart applies neither map partially');
  first.delta(delta(11, 12, { affiliations: [{ radio_identity_key: 'new-current' }], removed_ids: [11],
    removed_radio_keys: [{ radio_system_key: 'system', radio_identity_key: 'old-current' }], parts: 2, part: 0 }));
  const applied = events.find(event => event?.delta).delta;
  assert.deepEqual(applied.rows, [{ id: 12 }]); assert.deepEqual(applied.affiliations, [{ radio_identity_key: 'new-current' }]);
  assert.deepEqual(applied.removed_ids, [11]); assert.equal(applied.removed_radio_keys.length, 1);
  first.delta(delta(11, 12));
  assert.equal(events.filter(event => event?.delta).length, 1, 'duplicate revisions cannot replay either map');
  first.delta(delta(13, 14)); await tick();
  assert.equal(loads, 2, 'a global base gap reloads the one shared complete snapshot');
  assert.equal(events.filter(event => event?.seed).at(-1).seed.sequence, 14);
  first.delta(delta(14, 15, { available: false }));
  assert.equal(events.filter(event => event?.delta).at(-1).delta.available, false, 'stale metadata travels on the same sequence');
  next = snapshot(16); first.error(); first.boundary({ source_epoch: 'epoch-a', sequence: 16 }); await tick();
  assert.equal(loads, 3, 'every reconnect replaces both maps, without an Activity catchup request');
  hidden = true; feed.visibilityChanged(); assert.equal(sources[0].closed, true);
  hidden = false; feed.visibilityChanged(); next = snapshot(20);
  sources[1].callbacks.boundary({ source_epoch: 'epoch-a', sequence: 20 }); await tick();
  assert.equal(loads, 4, 'returning to a hidden view recovers one complete snapshot');
  feed.reseed({ refresh: true }); await tick(); assert.deepEqual(parameters.at(-1), { refresh: true });
  next = snapshot(0, { source_epoch: 'epoch-b', rows: [], affiliations: [] });
  sources[1].callbacks.boundary({ source_epoch: 'epoch-b', sequence: 0 }); await tick();
  assert.equal(events.filter(event => event?.seed).at(-1).seed.source_epoch, 'epoch-b');
  assert.deepEqual(parameters.at(-1), {}, 'automatic recovery never forces name refresh');
  feed.close(); assert.equal(sources.at(-1).closed, true); assert.equal(timers.size, 0);
  const waiting = deferred(); let waitingCallbacks, waitingSignal, delivered = false;
  const canceled = createP25ActivityFeed({
    openStream(callbacks) { waitingCallbacks = callbacks; return { close() {} }; },
    loadSeed(signal) { waitingSignal = signal; return waiting.promise; }, onSeed() { delivered = true; }, onDelta() {}, setTimeout, clearTimeout
  });
  waitingCallbacks.boundary({ source_epoch: 'epoch-a', sequence: 0 }); canceled.close();
  assert.equal(waitingSignal.aborted, true, 'page teardown cancels the in-flight complete seed immediately');
  waiting.resolve(snapshot()); await tick(); assert.equal(delivered, false);
  let failedCallbacks, failedTimer, failureLoads = 0;
  const failures = createP25ActivityFeed({
    openStream(callbacks) { failedCallbacks = callbacks; return { close() {} }; },
    loadSeed: async () => { failureLoads++; throw new Error('First complete state is not ready'); }, onSeed() {}, onDelta() {},
    setTimeout(callback, delay) { failedTimer = { callback, delay }; return 1; }, clearTimeout() {}
  });
  failedCallbacks.boundary({ source_epoch: 'epoch-a', sequence: 0 }); await tick();
  assert.equal(failureLoads, 1); assert.equal(failedTimer.delay, 500);
  failedTimer.callback(); failedCallbacks.boundary({ source_epoch: 'epoch-a', sequence: 0 }); await tick();
  assert.equal(failedTimer.delay, 1_000, 'initial failures retry with bounded backoff rather than exposing unknown empty state'); failures.close();
  for (const oversized of [{ rows: Array.from({ length: 501 }, (_, id) => ({ id })) },
    { affiliations: Array.from({ length: 10_001 }, () => ({})) }]) {
    let callbacks, retried = false, accepted = false;
    const bounded = createP25ActivityFeed({ openStream(value) { callbacks = value; return { close() {} }; },
      loadSeed: async () => snapshot(0, oversized), onSeed() { accepted = true; }, onDelta() {},
      setTimeout() { retried = true; return 1; }, clearTimeout() {} });
    callbacks.boundary({ source_epoch: 'epoch-a', sequence: 0 }); await tick();
    assert.equal(accepted, false); assert.equal(retried, true); bounded.close();
  }
  for (const race of ['resync-sequence', 'resync-unknown', 'higher-boundary', 'while-http', 'already-covered']) {
    const http = deferred(), renderer = deferred(), accepted = [];
    let callbacks, loads = 0, retries = 0, closed = false;
    const racing = createP25ActivityFeed({
      openStream(value) { callbacks = value; return { close() { closed = true; } }; },
      loadSeed: () => ++loads === 1 ? http.promise : Promise.resolve(snapshot(2)),
      async onSeed(document) { if (!accepted.length) await renderer.promise; accepted.push(document.sequence); }, onDelta() {},
      setTimeout() { retries++; return 1; }, clearTimeout() {}
    });
    callbacks.boundary({ source_epoch: 'epoch-a', sequence: 0 });
    if (race !== 'while-http') { http.resolve(snapshot(race === 'already-covered' ? 2 : 0)); await tick(); }
    if (race === 'higher-boundary') {
      callbacks.boundary({ source_epoch: 'epoch-a', sequence: 2 });
      callbacks.boundary({ source_epoch: 'epoch-a', sequence: 1 });
    } else {
      for (let repeat = 0; repeat < 3; repeat++) callbacks.resync(race === 'resync-unknown' ?
        { reason: 'transport_gap' } : { source_epoch: 'epoch-a', sequence: 2 });
    }
    assert.equal(loads, 1, 'requests arriving during HTTP/renderer work coalesce');
    if (race === 'while-http') http.resolve(snapshot(0));
    renderer.resolve(); await tick(); await tick();
    assert.equal(loads, race === 'already-covered' ? 1 : 2,
      'a quiet receiver gets one followup seed unless the requested revision was already covered');
    assert.equal(accepted.at(-1), 2); assert.equal(retries, 0); assert.equal(closed, false);
    callbacks.resync({ source_epoch: 'obsolete-epoch', sequence: 999 }); await tick();
    assert.equal(accepted.at(-1), 2, 'stale source resync cannot raise the new source minimum');
    assert.equal(loads, race === 'already-covered' ? 1 : 2);
    racing.close();
  }
  // Full native DTOs with legal long configured aliases produce near-256KiB parts.
  const wideFact = (id, sequence) => ({ radio_system_key: 'p25:bee00:123', radio_identity_key: `v1-r-bee00-123-${id}`,
    talkgroup_identity_key: 'v1-g-bee00-123-1201', configuration_id: 'site-a', confirmed_at_ms: 1_790_000_000_000 + sequence,
    protocol: 'p25', wacn: 0xbee00, system_id: 0x123, radio_id: id, talkgroup_id: 1201,
    radio_home_wacn: 0xbee00, radio_home_system_id: 0x123, talkgroup_home_wacn: 0xbee00, talkgroup_home_system_id: 0x123,
    radio_alias_name: 'R'.repeat(1024), radio_talker_alias: 'T'.repeat(32), talkgroup_alias_name: 'G'.repeat(1024),
    system_name: 'County public safety', system_names: ['County public safety'] });
  const widePart = (sequence, part) => delta(sequence - 1, sequence, { part, parts: 32,
    collection_enabled: true, traffic_grant_age_out_milliseconds: 1_200,
    affiliations: Array.from({ length: 100 }, (_, index) => wideFact(7001 + part * 100 + index, sequence)) });
  async function bufferedProbe(seedSequence = 0) {
    const renderer = deferred(); let callbacks, stopped = false, retries = 0, deliveries = 0, signal;
    const feed = createP25ActivityFeed({ openStream(value) { callbacks = value; return { close() { stopped = true; } }; },
      loadSeed: async (value) => { signal = value; return snapshot(seedSequence, { rows: [],
        affiliations: seedSequence ? Array.from({ length: 3200 }, (_, index) => wideFact(7001 + index, seedSequence)) : [] }); },
      onSeed: () => renderer.promise, onDelta() { deliveries++; }, setTimeout() { retries++; return 1; }, clearTimeout() {} });
    callbacks.boundary({ source_epoch: 'epoch-a', sequence: 0 }); await tick();
    return { feed, callbacks, renderer, get stopped() { return stopped; }, get retries() { return retries; },
      get deliveries() { return deliveries; }, get signal() { return signal; } };
  }
  const overflow = await bufferedProbe(); let receivedBytes = 0, receivedParts = 0;
  for (let sequence = 1; sequence <= 3 && !overflow.stopped; sequence++) for (let part = 0; part < 32 && !overflow.stopped; part++) {
    const wire = JSON.stringify(widePart(sequence, part)), bytes = Buffer.byteLength(wire);
    assert.ok(bytes >= 250 * 1024 && bytes <= 256 * 1024, 'fixture exercises realistic near-maximum frames');
    receivedBytes += bytes; receivedParts++;
    overflow.callbacks.delta(JSON.parse(wire), wire);
  }
  assert.ok(receivedParts >= 64 && receivedParts <= 66, 'byte cap trips before count/part bounds');
  assert.ok(receivedBytes > 16 * 1024 * 1024);
  assert.equal(overflow.retries, 1); assert.equal(overflow.signal.aborted, true);
  assert.equal(overflow.deliveries, 0, 'renderer wait never exposes a partial combined state');
  console.log(`P25 buffer rejected part ${receivedParts} at ${receivedBytes} incoming bytes before count bounds.`);
  overflow.feed.close(); overflow.renderer.resolve(); await tick();
  const released = await bufferedProbe(2);
  for (let sequence = 1; sequence <= 2; sequence++) for (let part = 0; part < 32; part++) {
    const wire = JSON.stringify(widePart(sequence, part)); released.callbacks.delta(JSON.parse(wire), wire);
    if (sequence === 1 && part === 0) released.callbacks.delta(JSON.parse(wire), wire);
  }
  assert.equal(released.retries, 0, 'identical duplicate delivery does not charge the buffer twice');
  released.renderer.resolve(); await tick();
  for (let part = 0; part < 32; part++) {
    const wire = JSON.stringify(widePart(3, part)); released.callbacks.delta(JSON.parse(wire), wire);
  }
  assert.equal(released.retries, 0, 'seed discard releases cumulative bytes and change counts');
  assert.equal(released.deliveries, 1); released.feed.close();
  for (const conflict of [{ collection_enabled: false }, { traffic_grant_age_out_milliseconds: 2_000 },
    { affiliations_truncated: true }, { rows: [{ id: 123, name: 'conflicting duplicate' }] }]) {
    const probe = await bufferedProbe();
    const original = delta(0, 1, { parts: 2, collection_enabled: true, traffic_grant_age_out_milliseconds: 1_200 });
    probe.callbacks.delta(original, JSON.stringify(original));
    const changed = { ...original, ...conflict }; probe.callbacks.delta(changed, JSON.stringify(changed));
    assert.equal(probe.retries, 1, 'conflicting duplicate parts or repeated metadata cannot merge');
    assert.equal(probe.deliveries, 0); probe.feed.close(); probe.renderer.resolve(); await tick();
  }
  console.log('P25 combined snapshot, atomic delta, gap/reconnect, bounds and teardown contracts passed.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
