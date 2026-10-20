'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const feature = path.resolve(process.argv[2] || path.resolve(__dirname,
  '../../../../stats-web/assets/features/network-visualizer'));
const tick = () => new Promise((resolve) => setImmediate(resolve));
function deferred() { let resolve; const promise = new Promise((done) => { resolve = done; }); return { promise, resolve }; }
const affiliations = (revision = 0, rows = [], available = true) => ({ affiliation_revision: revision, affiliations_available: available, affiliations_complete: true, affiliations: rows });
const page = (rows = [], sequence = 10, more = false, after = 0) => ({ source_epoch: 'epoch-a',
  through_sequence: sequence, rows, removed_ids: [], has_more: more, next_after_id: after, ...(!more ? { affiliation_revision: 0, affiliations_available: true, affiliations_complete: true, affiliations_refresh_required: false } : {}) });

async function main() {
  const { createP25ActivityFeed } = await import(pathToFileURL(path.join(feature, 'live.js')).href);
  const seed = deferred(), firstCatchup = deferred(), secondCatchup = deferred();
  const events = [], requests = [], sources = [], timers = new Map();
  let timerId = 0, seedCount = 0, hidden = false, seedSignal;
  const seedOptions = [];
  let nextSeed = { ...affiliations(), source_epoch: 'epoch-a', sequence: 20, rows: [] };
  const feed = createP25ActivityFeed({
    openStream(callbacks) {
      const source = { callbacks, closed: false, close() { this.closed = true; } };
      sources.push(source); events.push('subscribe'); return source;
    },
    async loadSeed(signal, options) { seedOptions.push(options); seedCount++; seedSignal = signal; events.push('seed'); return seedCount === 1 ? seed.promise : nextSeed; },
    requestCatchup(parameters) {
      requests.push(parameters);
      if (requests.length === 1) return firstCatchup.promise;
      if (requests.length === 2) return secondCatchup.promise;
      return Promise.resolve({ ...page([], parameters.after_sequence), affiliation_revision: 5, affiliations_available: true, affiliations_complete: true, affiliations_refresh_required: true });
    },
    onSeed(rows, changed, removed, signal, current) { events.push({ seed: rows, changed, removed }); events.push({ current }); },
    onRecovery(rows, removed, current) { events.push({ recovered: rows, removed, current }); },
    onAffiliations(rows, options) { events.push({ affiliated: rows, options }); },
    onDelta(rows, removed) { events.push({ rows, removed }); },
    onStatus(value) { events.push(value); },
    isHidden: () => hidden,
    setTimeout(callback, delay) { timers.set(++timerId, { callback, delay }); return timerId; },
    clearTimeout(id) { timers.delete(id); }
  });
  assert.equal(sources.length, 1);
  assert.equal(seedCount, 0, 'subscription is registered before loading saved history');
  const first = sources[0].callbacks;
  first.boundary({ source_epoch: 'epoch-a', sequence: 10 });
  assert.equal(seedCount, 1);
  first.delta({ source_epoch: 'epoch-a', base_sequence: 10, sequence: 11,
    rows: [{ id: 1, source: 'old encoded value' }], removed_ids: [], part: 0, parts: 1 });
  first.affiliations({ source_epoch: 'epoch-a', base_affiliation_revision: 0, affiliation_revision: 1,
    affiliations_available: true, affiliations_complete: true, upserts: [{ radio_identity_key: 'old' }], removed_radio_keys: [], part: 0, parts: 1 });
  seed.resolve({ ...affiliations(), source_epoch: 'epoch-a', sequence: 8, rows: [{ id: 1, source: 'newer seed value' }] });
  await tick();
  assert.equal(requests.length, 1);
  assert.deepEqual(requests[0], { source_epoch: 'epoch-a', after_sequence: 8, affiliation_revision: 0, after_id: 0, limit: 500 });
  firstCatchup.resolve(page([{ id: 1, source: 'fresh correction' }], 11, true, 1));
  await tick();
  first.delta({ source_epoch: 'epoch-a', base_sequence: 11, sequence: 12,
    rows: [{ id: 2 }], removed_ids: [], part: 1, parts: 2 });
  secondCatchup.resolve({ ...page([], 11, false, 1), affiliation_revision: 1, affiliations_available: true, affiliations_complete: true, affiliations_refresh_required: true });
  await tick();
  assert.equal(requests[1].after_sequence, 8, 'all pages keep the shared seed baseline even when it precedes the subscription');
  assert.equal(requests[1].through_sequence, 11, 'the first page pins the upper sequence');
  assert.deepEqual(events.find((event) => event?.seed), { seed: [{ id: 1, source: 'newer seed value' }],
    changed: [{ id: 1, source: 'fresh correction' }], removed: [] });
  assert.equal(events.filter((event) => event?.rows).length, 0, 'older encoded rows and incomplete multipart rows cannot be applied');
  first.delta({ source_epoch: 'epoch-a', base_sequence: 11, sequence: 12,
    rows: [{ id: 3 }], removed_ids: [4], part: 0, parts: 2 });
  assert.deepEqual(events.filter((event) => event?.rows), [{ rows: [{ id: 3 }, { id: 2 }], removed: [4] }]);
  first.delta({ source_epoch: 'epoch-a', base_sequence: 11, sequence: 12,
    rows: [{ id: 3 }], removed_ids: [4], part: 0, parts: 2 });
  assert.equal(events.filter((event) => event?.rows).length, 1, 'duplicate frames do not replay activity');
  assert.equal(events.find((event) => event?.current).current.revision, 0);
  assert.equal(events.filter((event) => event?.affiliated).length, 1, 'contiguous buffered state covers the catchup revision without refetch');
  const native = { source_epoch: 'epoch-a', base_affiliation_revision: 1, affiliation_revision: 2,
    affiliations_available: true, affiliations_complete: true, removed_radio_keys: [], parts: 2 };
  first.affiliations({ ...native, upserts: [{ radio_identity_key: 'new-b' }], part: 1 });
  assert.equal(events.filter((event) => event?.affiliated).length, 1, 'current-state multipart is atomic');
  first.affiliations({ ...native, upserts: [{ radio_identity_key: 'new-a' }], part: 0 });
  const applied = events.filter((event) => event?.affiliated).slice(1);
  assert.deepEqual(applied[0].affiliated, [{ radio_identity_key: 'new-a' }, { radio_identity_key: 'new-b' }]);
  assert.equal(applied[0].options.revision, 2);
  first.affiliations({ ...native, upserts: [], part: 0 });
  assert.equal(events.filter((event) => event?.affiliated).length, 2, 'stale native revisions are ignored independently');
  first.affiliations({ source_epoch: 'epoch-a', base_affiliation_revision: 2, affiliation_revision: 3,
    affiliations_available: false, upserts: [], removed_radio_keys: [] });
  assert.equal(events.filter((event) => event?.affiliated).at(-1).options.available, false);
  nextSeed = { ...affiliations(5), source_epoch: 'epoch-a', sequence: 99, rows: [{ id: 99 }] };
  const requestsBeforeStateGap = requests.length;
  first.affiliations({ source_epoch: 'epoch-a', base_affiliation_revision: 4, affiliation_revision: 5,
    affiliations_available: true, affiliations_complete: true, upserts: [], removed_radio_keys: [] });
  await tick();
  assert.equal(requests.length, requestsBeforeStateGap, 'a native gap never starts Activity catchup');
  assert.equal(seedCount, 2, 'a native gap fetches the shared seed for state only');
  assert.equal(events.filter((event) => event?.seed).length, 1, 'state recovery cannot replace recent Activity facts');
  assert.equal(events.filter((event) => event?.affiliated).at(-1).options.replace, true);
  first.error();
  first.boundary({ source_epoch: 'epoch-a', sequence: 15 });
  await tick();
  assert.equal(requests.at(-1).after_sequence, 12, 'reconnect resumes from the last complete multipart sequence');
  assert.equal(seedCount, 2, 'normal reconnect does not reread saved history');
  hidden = true; feed.visibilityChanged();
  assert.equal(sources[0].closed, true);
  hidden = false; feed.visibilityChanged();
  assert.equal(sources.length, 2);
  sources[1].callbacks.boundary({ source_epoch: 'epoch-a', sequence: 16 });
  await tick();
  assert.equal(seedCount, 2, 'a retained hidden-tab range resumes without reseeding');
  sources[1].callbacks.resync({ reason: 'range_expired' });
  assert.equal(timers.size, 1);
  const retry = [...timers.values()][0]; timers.clear(); retry.callback();
  sources[2].callbacks.boundary({ source_epoch: 'epoch-a', sequence: 20 });
  await tick();
  assert.equal(seedCount, 3, 'an expired range automatically reloads saved history');
  assert.equal(requests.at(-1).after_sequence, 99, 'a reseed starts at the fresh boundary rather than repeatedly requesting an expired range');
  feed.reseed({ refresh: true });
  await tick();
  assert.deepEqual(seedOptions.at(-1), { refresh: true }, 'explicit refresh alone requests shared names refresh');
  nextSeed = { ...affiliations(), source_epoch: 'epoch-b', sequence: 0, rows: [] };
  sources[2].callbacks.boundary({ source_epoch: 'epoch-b', sequence: 0 });
  assert.equal(seedCount, 5, 'source replacement reseeds automatically');
  assert.deepEqual(seedOptions.at(-1), {}, 'source changes never force name refresh');
  feed.close();
  assert.equal(seedSignal.aborted, true, 'page close cancels the current request immediately');
  assert.equal(sources.at(-1).closed, true);
  assert.equal(timers.size, 0);
  const laterRequests = [];
  let laterBoundary;
  const later = createP25ActivityFeed({
    openStream(callbacks) { laterBoundary = callbacks.boundary; return { close() {} }; },
    loadSeed: async () => ({ ...affiliations(), source_epoch: 'epoch-a', sequence: 22, rows: [] }),
    requestCatchup: async (parameters) => { laterRequests.push(parameters); return page([], 22); },
    onSeed() {}, onDelta() {}, onRecovery() {}, onAffiliations() {}, setTimeout, clearTimeout
  });
  laterBoundary({ source_epoch: 'epoch-a', sequence: 20 });
  await tick();
  assert.equal(laterRequests[0].after_sequence, 22,
    'a shared seed prepared after subscription also uses its own later sequence');
  later.close();
  const recoveryPage = deferred(), stateOnly = deferred();
  const recoveredFacts = [], nativeFrames = [];
  let concurrentCallbacks, seedLoads = 0, catches = 0;
  const concurrent = createP25ActivityFeed({
    openStream(callbacks) { concurrentCallbacks = callbacks; return { close() {} }; },
    loadSeed: () => { seedLoads++; return seedLoads === 1 ? Promise.resolve({ ...affiliations(1),
      source_epoch: 'epoch-a', sequence: 0, rows: [{ id: 1 }] }) : stateOnly.promise; },
    requestCatchup(parameters) { catches++; assert.ok(Number.isSafeInteger(parameters.affiliation_revision));
      return catches === 1 ? Promise.resolve({ ...page([], 0), affiliation_revision: 1 }) : recoveryPage.promise; },
    onSeed(rows) { recoveredFacts.push(rows); }, onDelta() {}, onRecovery(rows) { recoveredFacts.push(rows); },
    onAffiliations(rows, options) { nativeFrames.push({ rows, options }); }, setTimeout, clearTimeout
  });
  concurrentCallbacks.boundary({ source_epoch: 'epoch-a', sequence: 0 }); await tick();
  concurrentCallbacks.resync({ reason: 'transport_gap' });
  concurrentCallbacks.affiliations({ source_epoch: 'epoch-a', base_affiliation_revision: 1, affiliation_revision: 2,
    affiliations_available: true, affiliations_complete: true, upserts: [{ radio_identity_key: 'during-catchup' }], removed_radio_keys: [] });
  assert.equal(nativeFrames.length, 1, 'state updates advance while independent event recovery waits');
  recoveryPage.resolve({ ...page([{ id: 2 }], 1), affiliation_revision: 3, affiliations_refresh_required: true });
  await tick();
  assert.equal(seedLoads, 2, 'uncovered native revision requests the shared snapshot once');
  stateOnly.resolve({ ...affiliations(3, [{ radio_identity_key: 'snapshot' }]), source_epoch: 'epoch-a',
    sequence: 900, rows: [{ id: 900 }] });
  await tick();
  assert.deepEqual(recoveredFacts, [[{ id: 1 }], [{ id: 2 }]], 'state-only seed cannot replace event facts or cursor');
  assert.equal(nativeFrames.at(-1).options.replace, true);
  concurrentCallbacks.affiliations({ source_epoch: 'epoch-a', base_affiliation_revision: 3, affiliation_revision: 4,
    affiliations_available: true, affiliations_complete: true, upserts: [{ radio_identity_key: 'newer' }], removed_radio_keys: [] });
  assert.equal(nativeFrames.at(-1).options.revision, 4);
  concurrent.close();
  let unknownCallbacks, unknownLoads = 0;
  const unknownUpdates = [];
  const incomplete = { ...affiliations(10, [], false), affiliations_complete: false, source_epoch: 'epoch-a', sequence: 0, rows: [] };
  const unknownFeed = createP25ActivityFeed({
    openStream(callbacks) { unknownCallbacks = callbacks; return { close() {} }; },
    loadSeed: async () => { unknownLoads++; return unknownLoads < 3 ? incomplete : {
      ...incomplete, ...affiliations(12, [{ radio_identity_key: 'new-only' }]) }; },
    requestCatchup: async () => ({ ...page([], 0), affiliation_revision: 10,
      affiliations_available: false, affiliations_complete: false }),
    onSeed(rows, changed, removed, signal, snapshot) { assert.equal(snapshot.complete, false); },
    onRecovery() {}, onDelta() {}, onAffiliations(rows, options) { unknownUpdates.push({ rows, options }); },
    setTimeout, clearTimeout
  });
  unknownCallbacks.boundary({ source_epoch: 'epoch-a', sequence: 0 }); await tick();
  const unknownDelta = { source_epoch: 'epoch-a', base_affiliation_revision: 10, affiliation_revision: 11,
    affiliations_available: true, upserts: [{ radio_identity_key: 'unsafe-partial' }], removed_radio_keys: [] };
  unknownCallbacks.affiliations(unknownDelta); await tick();
  assert.equal(unknownLoads, 2, 'first native delta after unknown state requests a complete shared seed');
  assert.equal(unknownUpdates.some(update => update.rows.some(row => row.radio_identity_key === 'unsafe-partial')), false,
    'the initial server diff from empty cannot merge into older retained client knowledge');
  await tick(); assert.equal(unknownLoads, 2, 'an incomplete response cannot cause an immediate refetch loop');
  unknownCallbacks.affiliations({ ...unknownDelta, base_affiliation_revision: 11, affiliation_revision: 12 }); await tick();
  assert.equal(unknownUpdates.at(-1).options.replace, true);
  assert.equal(unknownUpdates.at(-1).options.complete, true);
  assert.deepEqual(unknownUpdates.at(-1).rows, [{ radio_identity_key: 'new-only' }]);
  unknownFeed.close();
  let oversizedBoundary, oversizedRetry = 0, oversizedApplied = false;
  const oversized = createP25ActivityFeed({
    openStream(callbacks) { oversizedBoundary = callbacks.boundary; return { close() {} }; },
    loadSeed: async () => ({ ...affiliations(), source_epoch: 'epoch-a', sequence: 0, rows: Array.from({ length: 501 }, (_, id) => ({ id })) }),
    requestCatchup: async () => { throw new Error('Oversized seeds must be rejected before catchup'); },
    onSeed() { oversizedApplied = true; }, onDelta() {},
    setTimeout() { oversizedRetry++; return 1; }, clearTimeout() {}
  });
  oversizedBoundary({ source_epoch: 'epoch-a', sequence: 0 });
  await tick();
  assert.equal(oversizedApplied, false);
  assert.equal(oversizedRetry, 1, 'a malformed seed cannot exceed the 500-record initial bound');
  oversized.close();
  console.log('P25 Visualizer live seed, catchup, multipart, reconnect and teardown checks passed.');
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
