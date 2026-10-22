'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const feature = path.resolve(process.argv[2] || path.resolve(__dirname, '../../../../stats-web/assets/features/network-visualizer'));
const tick = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; }
const snapshot = (sequence = 0, overrides = {}) => ({ source_epoch: 'epoch-a', sequence,
  affiliations: [], available: true, ...overrides });
const delta = (base, sequence, overrides = {}) => ({ source_epoch: 'epoch-a', base_sequence: base, sequence,
  affiliations: [], removed_radio_keys: [], available: true, part: 0, parts: 1, ...overrides });
const page = (rows = [], watermark = 0, more = false, next = watermark) =>
  ({ rows, watermark_id: watermark, next_after_id: next, has_more: more, reset_required: false });
const append = (rows, next, watermark = next, after = Math.min(...rows.map(row => row.id)) - 1) =>
  ({ source_epoch: 'epoch-a', history_generation: 0, rows, after_id: after, next_after_id: next, watermark_id: watermark });

async function main() {
  const { createP25ActivityFeed } = await import(pathToFileURL(path.join(feature, 'live.js')).href);
  const history = await import(pathToFileURL(path.join(feature, 'history.js')).href);
  const firstPage = deferred(), lastPage = deferred(), gapPage = deferred(), pages = [], delivered = [], native = [], signals = [], sources = [];
  let state = history.createP25HistoryState(), loads = 0, resets = 0, hidden = false;
  const fact = { radio_system_key: 'system', radio_identity_key: 'v1-r-1', talkgroup_identity_key: 'v1-g-1',
    confirmed_at_ms: 1_000, configuration_id: 'site', radio_id: 1, talkgroup_id: 1 };
  const row = (id, action = 'GRANT') => ({ id, action, observed_at_ms: id * 100,
    protocol: 'p25', channel_kind: 'trunked_site', radio_system_key: 'system',
    source_identity_key: 'v1-r-2', target_identity_key: 'v1-g-2' });
  let currentNative = snapshot(0, { affiliations: [fact], collection_enabled: false });
  function accept(rows, initial) { delivered.push(...rows.map(row => row.id)); history.applyP25ActivityRows(state, rows, { initial }); }
  const feed = createP25ActivityFeed({
    openStream(callbacks) { const source = { callbacks, closed: false, close() { this.closed = true; } }; sources.push(source); return source; },
    loadSeed: async () => { loads++; return currentNative; },
    onSeed(document) { native.push(document); history.applyP25Affiliations(state, document, { replace: true, initial: true }); },
    onDelta(document) { native.push(document); history.applyP25Affiliations(state, document); },
    historyParameters: () => ({ projection: 'p25_visualizer', from_ms: 100, to_ms: 86_400_100, limit: 5000 }),
    loadHistory(parameters, signal) {
      pages.push(parameters); signals.push(signal);
      if (pages.length === 1) return firstPage.promise;
      if (pages.length === 2) return lastPage.promise;
      if (parameters.after_id === 603) return gapPage.promise;
      return Promise.resolve(page([row(605)], 605));
    },
    onHistory: rows => accept(rows, true), onAppend: rows => accept(rows, false),
    onHistoryReset() {
      resets++; const affiliations = [...state.currentAffiliations.values()]; state = history.createP25HistoryState();
      history.applyP25Affiliations(state, { affiliations }, { replace: true, initial: true });
    },
    isHidden: () => hidden, setTimeout, clearTimeout
  });
  assert.equal(loads, 0, 'subscribe before requesting either source');
  const stream = sources[0].callbacks;
  stream.boundary({ source_epoch: 'epoch-a', sequence: 0, watermark_id: 0, history_generation: 0 }); await tick();
  assert.equal(state.currentAffiliations.size, 1, 'native scene is ready while the first history page is pending');
  assert.equal(pages.length, 1, 'native seed and history fetch run independently');
  stream.append(append([row(599), row(601)], 601));
  assert.deepEqual(delivered, [], 'only the unknown first history watermark delays append delivery');
  firstPage.resolve(page([row(1, 'EMERGENCY')], 600, true, 1)); await tick();
  assert.deepEqual(delivered, [1, 601], 'fixed history watermark excludes overlap and immediately releases newer live facts');
  assert.equal(pages[1].watermark_id, 600); assert.equal(pages[1].after_id, 1);
  stream.append(append([row(602)], 602));
  assert.equal(delivered.at(-1), 602, 'live observations continue while older history pages are pending');
  currentNative = snapshot(1, { affiliations: [{ ...fact, talkgroup_identity_key: 'v1-g-3' }], collection_enabled: false });
  stream.delta(delta(0, 1, { affiliations: currentNative.affiliations }));
  assert.equal(state.currentAffiliations.get('system:v1-r-1').talkgroup_identity_key, 'v1-g-3',
    'activity collection off does not pause native affiliation updates');
  lastPage.resolve(page(Array.from({ length: 599 }, (_, index) => row(index + 2)), 600)); await tick();
  assert.equal(delivered.length, 602); assert.equal(new Set(delivered).size, 602);
  assert.equal(history.groupedP25Events(state).some(event => event.category === 'emergency'), true,
    'more than 500 routine records do not evict an earlier noteworthy fact');
  assert.equal(state.currentAffiliations.get('system:v1-r-1').talkgroup_identity_key, 'v1-g-3',
    'late historical pages never replace live affiliations');
  stream.append(append([row(603)], 603));
  // A later delivered frame cannot prove that the missing 604 frame was received.
  stream.append(append([row(605)], 605));
  assert.equal(delivered.includes(605), false, 'after_id reveals the gap before later605 can be applied');
  gapPage.resolve(page([row(604), row(605)], 605)); await tick();
  assert.equal(pages[2].after_id, 603, 'recovery starts at the completed history plus verified live prefix');
  assert.equal(delivered.filter(id => id === 603).length, 1, 'catchup does not replay already applied live facts');
  assert.equal(delivered.at(-1), 605, 'catchup supplies missing604 then605 in order');
  assert.equal(delivered.filter(id => id === 605).length, 1);
  assert.equal(delivered.filter(id => id === 604).length, 1);
  stream.error(); stream.boundary({ source_epoch: 'epoch-a', sequence: 0, watermark_id: 605, history_generation: 0 }); await tick();
  assert.equal(pages.at(-1).after_id, 605, 'healthy completed catchup leaves no retained live-ID collection');
  // A reset frame may be lost while MAX(id) remains unchanged after a scoped deletion.
  stream.error(); stream.boundary({ source_epoch: 'epoch-a', sequence: 1, watermark_id: 605, history_generation: 1 }); await tick();
  assert.equal(pages.at(-1).after_id, 0, 'published history generation detects a lost scoped-reset frame');
  assert.equal(resets, 1); assert.equal(state.currentAffiliations.size, 1);
  assert.equal(history.groupedP25Events(state).some(event => event.category === 'emergency'), false);
  const beforeResetLoads = loads;
  const beforeResetPages = pages.length;
  stream.boundary({ source_epoch: 'epoch-a', sequence: 1, watermark_id: 605, history_generation: 2 });
  stream.historyReset({ source_epoch: 'epoch-a', watermark_id: 605, history_generation: 2 }); await tick();
  assert.equal(pages.length, beforeResetPages + 1, 'boundary and reset for one generation share one history reload');
  assert.equal(resets, 2); assert.equal(pages.at(-1).after_id, 0);
  assert.equal(loads, beforeResetLoads, 'committed history maintenance refetches history alone');
  assert.equal(state.currentAffiliations.size, 1, 'history maintenance preserves the independent native map');
  assert.equal(history.groupedP25Events(state).some(event => event.category === 'emergency'), false);
  currentNative = snapshot(2, { affiliations: [], collection_enabled: false });
  stream.delta(delta(1, 2, { removed_radio_keys: [{ radio_system_key: 'system', radio_identity_key: 'v1-r-1' }] }));
  assert.equal(state.currentAffiliations.size, 0, 'native removal remains live with collection off');
  feed.reloadHistory(); await tick(); assert.equal(resets, 3);
  hidden = true; feed.visibilityChanged(); assert.equal(sources[0].closed, true);
  hidden = false; feed.visibilityChanged(); sources[1].callbacks.boundary({ source_epoch: 'epoch-a', sequence: 2, watermark_id: 605, history_generation: 2 }); await tick();
  feed.close(); assert.equal(sources[1].closed, true);

  const abandonedPage = deferred(), resumed = [], partialSources = [], partialRequests = [];
  const partial = createP25ActivityFeed({
    openStream(callbacks) { partialSources.push(callbacks); return { close() {} }; },
    loadSeed: async () => snapshot(), onSeed() {}, onDelta() {}, historyParameters: () => ({}),
    loadHistory(parameters, signal) {
      partialRequests.push({ parameters, signal });
      if (partialRequests.length === 1) return Promise.resolve(page([row(1)], 10, true, 1));
      if (partialRequests.length === 2) return abandonedPage.promise;
      return Promise.resolve(page(Array.from({ length: 11 }, (_, index) => row(index + 2)), 12));
    }, onHistory: rows => resumed.push(...rows.map(row => row.id)), onAppend: rows => resumed.push(...rows.map(row => row.id)),
    setTimeout, clearTimeout
  });
  partialSources[0].boundary({ source_epoch: 'epoch-a', sequence: 0, watermark_id: 0, history_generation: 0 }); await tick();
  partialSources[0].append(append([row(11)], 11));
  partialSources[0].error(); assert.equal(partialRequests[1].signal.aborted, true);
  partialSources[0].boundary({ source_epoch: 'epoch-a', sequence: 0, watermark_id: 0, history_generation: 0 }); await tick();
  assert.equal(partialRequests[2].parameters.after_id, 1, 'interrupted backfill resumes the last applied page');
  assert.equal(resumed.length, 12); assert.equal(new Set(resumed).size, 12,
    'live overlap tracking does not skip unfinished older pages or replay the received live row');
  abandonedPage.resolve(page(Array.from({ length: 9 }, (_, index) => row(index + 2)), 10)); await tick();
  assert.equal(resumed.length, 12, 'the abandoned old page cannot mutate a resumed history'); partial.close();

  let completedStream, publishedEdge = 600;
  const completedRequests = [], completedRows = [];
  const completed = createP25ActivityFeed({
    openStream(callbacks) { completedStream = callbacks; return { close() {} }; },
    loadSeed: async () => snapshot(), onSeed() {}, onDelta() {}, historyParameters: () => ({}),
    loadHistory(parameters) {
      completedRequests.push(parameters);
      return Promise.resolve(page(parameters.after_id < 600 ? [row(600)] : [], publishedEdge));
    }, onHistory: rows => completedRows.push(...rows.map(row => row.id)),
    onAppend: rows => completedRows.push(...rows.map(row => row.id)), setTimeout, clearTimeout
  });
  completedStream.boundary({ source_epoch: 'epoch-a', sequence: 0, watermark_id: 600, history_generation: 0 }); await tick();
  completedStream.append(append([row(601)], 601, 601, 600));
  publishedEdge = 1_000_000;
  completedStream.append(append([], publishedEdge, publishedEdge, 601));
  completedStream.error();
  completedStream.boundary({ source_epoch: 'epoch-a', sequence: 0, watermark_id: publishedEdge, history_generation: 0 }); await tick();
  assert.equal(completedRequests.at(-1).after_id, 1_000_000,
    'completed history reconnect skips the entire already verified live span, including empty filtered progress');
  assert.deepEqual(completedRows, [600, 601], 'completed reconnect neither rereads nor replays already applied observations');
  completed.close();

  // Native multipart revisions are atomic and have no dataset-count cutoff.
  let callbacks, seedLoads = 0, accepted, frames = [], next = snapshot(0, { affiliations: Array.from({ length: 10_001 }, (_, id) => ({ id })) });
  const atomic = createP25ActivityFeed({ openStream(value) { callbacks = value; return { close() {} }; },
    loadSeed: async () => { seedLoads++; return next; }, onSeed: document => { accepted = document; }, onDelta: value => frames.push(value),
    historyParameters: () => ({}), loadHistory: async () => page(), onHistory() {}, onAppend() {}, setTimeout, clearTimeout });
  callbacks.boundary({ source_epoch: 'epoch-a', sequence: 0, watermark_id: 0, history_generation: 0 }); await tick();
  assert.equal(accepted.affiliations.length, 10_001, 'all native facts pass through the full seed');
  callbacks.delta(delta(0, 1, { part: 1, parts: 2, affiliations: [{ id: 'second' }] }));
  assert.equal(frames.length, 0);
  callbacks.delta(delta(0, 1, { part: 0, parts: 2, affiliations: [{ id: 'first' }] }));
  assert.deepEqual(frames[0].affiliations, [{ id: 'first' }, { id: 'second' }]);
  callbacks.delta(delta(0, 1)); assert.equal(frames.length, 1, 'duplicate native revisions never replay motion');
  next = snapshot(5); callbacks.delta(delta(4, 5)); await tick();
  assert.equal(seedLoads, 2, 'a native revision gap recovers from a complete seed');
  callbacks.resync({ source_epoch: 'obsolete', sequence: 900 }); await tick(); assert.equal(seedLoads, 2);
  atomic.close();

  // Closing or changing source aborts both independent requests and ignores late responses.
  const waitingSeed = deferred(), waitingHistory = deferred(); let pendingCallbacks, seedSignal, historySignal, applied = 0;
  const canceled = createP25ActivityFeed({ openStream(value) { pendingCallbacks = value; return { close() {} }; },
    loadSeed(signal) { seedSignal = signal; return waitingSeed.promise; }, onSeed() { applied++; }, onDelta() {},
    historyParameters: () => ({}), loadHistory(parameters, signal) { historySignal = signal; return waitingHistory.promise; },
    onHistory() { applied++; }, onAppend() {}, setTimeout, clearTimeout });
  pendingCallbacks.boundary({ source_epoch: 'epoch-a', sequence: 0, watermark_id: 0, history_generation: 0 }); canceled.close();
  assert.equal(seedSignal.aborted, true); assert.equal(historySignal.aborted, true);
  waitingSeed.resolve(snapshot()); waitingHistory.resolve(page()); await tick(); assert.equal(applied, 0);

  const delayed = deferred(); let changingCallbacks, oldSignal, changes = [], requests = 0;
  const changing = createP25ActivityFeed({ openStream(value) { changingCallbacks = value; return { close() {} }; },
    loadSeed: async () => snapshot(0, { source_epoch: requests ? 'epoch-b' : 'epoch-a' }), onSeed() {}, onDelta() {},
    onBoundary: value => changes.push(value.source_epoch), historyParameters: () => ({}),
    loadHistory(parameters, signal) { if (++requests === 1) { oldSignal = signal; return delayed.promise; } return Promise.resolve(page()); },
    onHistory: rows => { if (rows.length) throw new Error('Old source leaked'); }, onAppend() {}, setTimeout, clearTimeout });
  changingCallbacks.boundary({ source_epoch: 'epoch-a', sequence: 0, watermark_id: 0, history_generation: 0 }); await tick();
  changingCallbacks.boundary({ source_epoch: 'epoch-b', sequence: 0, watermark_id: 0, history_generation: 0 }); await tick();
  assert.equal(oldSignal.aborted, true); assert.deepEqual(changes, ['epoch-a', 'epoch-b']);
  delayed.resolve(page([row(1)], 1)); await tick(); changing.close();
  for (const race of ['resync-sequence', 'resync-unknown', 'higher-boundary', 'while-http', 'already-covered']) {
    const http = deferred(), renderer = deferred(), revisions = [];
    let callbacks, loads = 0;
    const racing = createP25ActivityFeed({ openStream(value) { callbacks = value; return { close() {} }; },
      loadSeed: () => ++loads === 1 ? http.promise : Promise.resolve(snapshot(2)),
      async onSeed(document) { if (!revisions.length) await renderer.promise; revisions.push(document.sequence); }, onDelta() {},
      historyParameters: () => ({}), loadHistory: async () => page(), onHistory() {}, onAppend() {}, setTimeout, clearTimeout });
    callbacks.boundary({ source_epoch: 'epoch-a', sequence: 0, watermark_id: 0, history_generation: 0 });
    if (race !== 'while-http') { http.resolve(snapshot(race === 'already-covered' ? 2 : 0)); await tick(); }
    if (race === 'higher-boundary') {
      callbacks.boundary({ source_epoch: 'epoch-a', sequence: 2, watermark_id: 0, history_generation: 0 }); callbacks.boundary({ source_epoch: 'epoch-a', sequence: 1, watermark_id: 0, history_generation: 0 });
    } else for (let repeat = 0; repeat < 3; repeat++) callbacks.resync(race === 'resync-unknown' ?
      { reason: 'transport_gap' } : { source_epoch: 'epoch-a', sequence: 2 });
    assert.equal(loads, 1, 'native HTTP and renderer recovery requests coalesce');
    if (race === 'while-http') http.resolve(snapshot());
    renderer.resolve(); await tick(); await tick();
    assert.equal(loads, race === 'already-covered' ? 1 : 2);
    assert.equal(revisions.at(-1), 2, 'recovery cannot strand a quiet receiver at an obsolete revision'); racing.close();
  }
  console.log('P25 native/live history paging, overlap, maintenance, reconnect and cancellation contracts passed.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
