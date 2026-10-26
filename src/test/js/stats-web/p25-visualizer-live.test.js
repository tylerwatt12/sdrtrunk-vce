'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const feature = path.resolve(process.argv[2] || path.resolve(__dirname, '../../../../stats-web/assets/features/network-visualizer'));
const boundary = (sequence = 10, watermark = 100, epoch = 'source') =>
  ({ source_epoch: epoch, sequence, watermark_id: watermark, history_generation: 0 });
const delta = (base, sequence, overrides = {}) => ({ source_epoch: 'source', base_sequence: base, sequence,
  affiliations: [], removed_radio_keys: [], available: true, part: 0, parts: 1, ...overrides });
const append = (after, next, ids, epoch = 'source') => ({ source_epoch: epoch, history_generation: 0,
  after_id: after, next_after_id: next, watermark_id: next, rows: ids.map(id => ({ id })) });

async function main() {
  const { createP25ActivityFeed } = await import(pathToFileURL(path.join(feature, 'live.js')).href);
  const delivered = [], native = [], states = [], resets = [], sources = [], timers = new Map();
  let hidden = false, timerId = 0, preloadCalls = 0;
  const feed = createP25ActivityFeed({
    openStream(callbacks) { const source = { callbacks, closed: false, close() { this.closed = true; } }; sources.push(source); return source; },
    loadSeed() { preloadCalls++; throw new Error('No preload'); },
    loadHistory() { preloadCalls++; throw new Error('No preload'); },
    onDelta: value => native.push(value), onAppend: rows => delivered.push(...rows.map(row => row.id)),
    onBoundary: () => {}, onStatus: value => states.push(value), onReset: reason => resets.push(reason),
    isHidden: () => hidden,
    setTimeout(callback) { timers.set(++timerId, callback); return timerId; },
    clearTimeout: id => timers.delete(id)
  });
  const stream = sources[0].callbacks;
  stream.delta(delta(0, 1)); stream.append(append(0, 1, [1]));
  assert.equal(native.length + delivered.length, 0, 'nothing before a stream baseline is replayed');
  stream.boundary(boundary());
  assert.equal(preloadCalls, 0, 'startup does not request affiliation seeds or saved history');
  assert.equal(states.at(-1), 'live');
  stream.append(append(99, 101, [100, 101]));
  stream.append(append(100, 101, [101]));
  assert.deepEqual(delivered, [101], 'only observations beyond the initial live edge count');
  stream.delta(delta(10, 11, { parts: 2, part: 1, affiliations: [{ id: 'second' }] }));
  assert.equal(native.length, 0, 'split native update waits for every part');
  stream.delta(delta(10, 11, { parts: 2, part: 0, affiliations: [{ id: 'first' }] }));
  assert.deepEqual(native[0].affiliations.map(fact => fact.id), ['first', 'second']);
  stream.delta(delta(10, 11));
  assert.equal(native.length, 1, 'duplicate revision cannot repeat motion');
  stream.boundary(boundary(12, 102));
  stream.delta(delta(11, 12));
  assert.equal(native.length, 2, 'same-source metadata boundary does not swallow a following live delta');
  stream.append(append(101, 102, [102]));
  assert.deepEqual(delivered, [101, 102], 'metadata refresh does not advance away unseen actions');
  stream.append(append(104, 105, [105]));
  assert.equal(sources[0].closed, true);
  assert.equal(resets.at(-1), 'gap');
  assert.equal(timers.size, 1, 'a gap reconnects at the present instead of fetching/replaying history');
  const retry = [...timers.values()][0]; timers.clear(); retry();
  const recovered = sources[1].callbacks;
  recovered.boundary(boundary(20, 200));
  recovered.delta(delta(19, 20)); recovered.append(append(100, 200, [150, 200]));
  assert.deepEqual(delivered, [101, 102], 'missed history is never replayed as new activity');
  recovered.delta(delta(20, 21)); recovered.append(append(200, 201, [201]));
  assert.equal(native.length, 3); assert.equal(delivered.at(-1), 201);
  hidden = true;
  recovered.append(append(201, 202, [202])); recovered.delta(delta(21, 22));
  recovered.append(append(201, 202, [202]));
  assert.equal(sources[1].closed, false, 'presentation visibility never closes data delivery');
  assert.equal(delivered.at(-1), 202, 'background actions continue to reach the counters');
  assert.equal(delivered.filter(id => id === 202).length, 1, 'background duplicates are ignored');
  assert.equal(native.length, 4, 'background affiliation state stays current');
  assert.equal(states.at(-1), 'live');
  recovered.error();
  assert.equal(timers.size, 1, 'connection recovery still runs in the background');
  const backgroundRetry = [...timers.values()][0]; timers.clear(); backgroundRetry();
  assert.equal(sources.length, 3, 'background recovery reopens without waiting for visibility');
  hidden = false;
  sources[2].callbacks.boundary(boundary(30, 300, 'new-source'));
  sources[2].callbacks.append(append(300, 301, [301], 'new-source'));
  assert.equal(delivered.at(-1), 301);
  feed.reseed(); assert.equal(sources[2].closed, true);
  assert.equal(preloadCalls, 0, 'Refresh is also live-only');
  sources[3].callbacks.boundary(boundary());
  sources[3].callbacks.error(); assert.equal(timers.size, 1);
  feed.close(); assert.equal(timers.size, 0); assert.equal(sources[3].closed, true);
  sources[3].callbacks.boundary(boundary()); assert.equal(sources.length, 4, 'closed pages stay closed');
  console.log('P25 live-only boundaries, ordered delivery, deduplication and reconnect checks passed.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
