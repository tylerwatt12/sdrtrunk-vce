'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const feature = path.resolve(process.argv[2] || path.resolve(__dirname,
  '../../../../stats-web/assets/features/network-visualizer'));
const tick = () => new Promise((resolve) => setImmediate(resolve));
function deferred() { let resolve; const promise = new Promise((done) => { resolve = done; }); return { promise, resolve }; }
const page = (rows = [], sequence = 10, more = false, after = 0) => ({ source_epoch: 'epoch-a',
  through_sequence: sequence, rows, removed_ids: [], has_more: more, next_after_id: after });

async function main() {
  const { createP25ActivityFeed } = await import(pathToFileURL(path.join(feature, 'live.js')).href);
  const seed = deferred(), firstCatchup = deferred(), secondCatchup = deferred();
  const events = [], requests = [], sources = [], timers = new Map();
  let timerId = 0, seedCount = 0, hidden = false, seedSignal;
  const feed = createP25ActivityFeed({
    openStream(callbacks) {
      const source = { callbacks, closed: false, close() { this.closed = true; } };
      sources.push(source); events.push('subscribe'); return source;
    },
    async loadSeed(signal) { seedCount++; seedSignal = signal; events.push('seed'); return seed.promise; },
    requestCatchup(parameters) {
      requests.push(parameters);
      if (requests.length === 1) return firstCatchup.promise;
      if (requests.length === 2) return secondCatchup.promise;
      return Promise.resolve(page([], parameters.after_sequence));
    },
    onSeed(rows, changed, removed) { events.push({ seed: rows, changed, removed }); },
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
  seed.resolve([{ id: 1, source: 'newer seed value' }]);
  await tick();
  assert.equal(requests.length, 1);
  assert.deepEqual(requests[0], { source_epoch: 'epoch-a', after_sequence: 10, after_id: 0, limit: 500 });
  firstCatchup.resolve(page([{ id: 1, source: 'fresh correction' }], 11, true, 1));
  await tick();
  first.delta({ source_epoch: 'epoch-a', base_sequence: 11, sequence: 12,
    rows: [{ id: 2 }], removed_ids: [], part: 1, parts: 2 });
  secondCatchup.resolve(page([], 11, false, 1));
  await tick();
  assert.equal(requests[1].after_sequence, 10, 'all pages keep the original journal baseline');
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
  first.error();
  first.boundary({ source_epoch: 'epoch-a', sequence: 15 });
  await tick();
  assert.equal(requests.at(-1).after_sequence, 12, 'reconnect resumes from the last complete multipart sequence');
  assert.equal(seedCount, 1, 'normal reconnect does not reread saved history');
  hidden = true; feed.visibilityChanged();
  assert.equal(sources[0].closed, true);
  hidden = false; feed.visibilityChanged();
  assert.equal(sources.length, 2);
  sources[1].callbacks.boundary({ source_epoch: 'epoch-a', sequence: 16 });
  await tick();
  assert.equal(seedCount, 1, 'a retained hidden-tab range resumes without reseeding');
  sources[1].callbacks.resync({ reason: 'range_expired' });
  assert.equal(timers.size, 1);
  const retry = [...timers.values()][0]; timers.clear(); retry.callback();
  sources[2].callbacks.boundary({ source_epoch: 'epoch-a', sequence: 20 });
  await tick();
  assert.equal(seedCount, 2, 'an expired range automatically reloads saved history');
  assert.equal(requests.at(-1).after_sequence, 20, 'a reseed starts at the fresh boundary rather than repeatedly requesting an expired range');
  sources[2].callbacks.boundary({ source_epoch: 'epoch-b', sequence: 0 });
  assert.equal(seedCount, 3, 'source replacement reseeds automatically');
  feed.close();
  assert.equal(seedSignal.aborted, true, 'page close cancels the current request immediately');
  assert.equal(sources.at(-1).closed, true);
  assert.equal(timers.size, 0);
  console.log('P25 Visualizer live seed, catchup, multipart, reconnect and teardown checks passed.');
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
