const assert = require('node:assert/strict');
const test = require('node:test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');

const helper = import(pathToFileURL(resolve(__dirname,
  '../../../..', 'stats-web/assets/features/spectrum-live-tune.js')).href);
const settle = async () => { for (let index = 0; index < 8; index += 1) await Promise.resolve(); };

async function harness() {
  const { createSpectrumLiveTune } = await helper;
  let clock = 0, nextTimer = 0;
  const timers = new Map(), writes = [], previews = [], applied = [], errors = [];
  const queue = createSpectrumLiveTune({
    apply: (frequency) => new Promise((resolve, reject) => writes.push({ frequency, resolve, reject })),
    preview: (frequency) => previews.push(frequency),
    applied: (value, frequency) => applied.push({ value, frequency }),
    failed: (error, frequency) => errors.push({ error, frequency }),
    now: () => clock,
    schedule: (callback, delay) => { const id = ++nextTimer; timers.set(id, { callback, at: clock + delay }); return id; },
    unschedule: (id) => timers.delete(id)
  });
  return { queue, writes, previews, applied, errors, timers,
    advance: async (elapsed) => {
      clock += elapsed;
      for (const [id, timer] of [...timers]) if (timer.at <= clock) { timers.delete(id); timer.callback(); }
      await settle();
    } };
}

test('movement writes immediately and a rapid burst retains only its latest target', async () => {
  const h = await harness();
  h.queue.request(851000000);
  await settle();
  assert.equal(h.writes.length, 1);
  for (let offset = 1; offset <= 100; offset++) h.queue.request(851000000 + offset);
  assert.equal(h.previews.length, 101, 'feedback does not await a server response');
  h.writes[0].resolve('old'); await settle();
  assert.deepEqual(h.applied, [], 'superseded acknowledgement does not update the visible frequency');
  await h.advance(75);
  assert.deepEqual(h.writes.map((write) => write.frequency), [851000000, 851000100]);
  h.writes[1].resolve('new'); await settle();
  assert.equal(h.applied[0].frequency, 851000100);
  assert.equal(h.queue.busy, false);
});

test('release flushes the newest frequency immediately after the active transaction', async () => {
  const h = await harness();
  h.queue.request(851000000); await settle();
  h.queue.request(852000000); h.queue.request(853000000, { final: true });
  h.writes[0].resolve(); await settle();
  assert.deepEqual(h.writes.map((write) => write.frequency), [851000000, 853000000]);
  h.queue.request(853000000, { final: true });
  h.writes[1].resolve(); await settle();
  assert.equal(h.writes.length, 2, 'same release target never creates a duplicate server mutation');
  assert.equal(h.applied.length, 1);
});

test('returning to the active target discards an intermediate movement', async () => {
  const h = await harness();
  h.queue.request(851000000); await settle();
  h.queue.request(852000000); h.queue.request(851000000, { final: true });
  h.writes[0].resolve(); await settle(); await h.advance(100);
  assert.equal(h.writes.length, 1);
  assert.equal(h.applied[0].frequency, 851000000);
});

test('release after the live write settled does not reopen busy preview or repeat the mutation', async () => {
  const h = await harness();
  h.queue.request(851000000); await settle(); h.writes[0].resolve(); await settle();
  h.queue.request(851000000, { final: true }); await settle();
  assert.equal(h.writes.length, 1); assert.equal(h.previews.length, 1);
  assert.equal(h.queue.busy, false);
});

test('a completed transaction invalidates a lease snapshot begun after the final pointer request', async () => {
  const h = await harness();
  h.queue.request(851000000, { final: true }); await settle();
  const revision = h.queue.revision;
  h.writes[0].resolve(); await settle();
  assert.equal(h.queue.busy, false);
  assert.ok(h.queue.revision > revision, 'completion must advance the freshness guard even without a new pointer target');
});

test('cancel discards pending movement and does not accept the old response', async () => {
  const h = await harness();
  h.queue.request(851000000); await settle(); h.queue.request(852000000);
  h.queue.cancel(); const revision = h.queue.revision;
  h.writes[0].resolve(); await settle(); await h.advance(100);
  assert.equal(h.writes.length, 1); assert.deepEqual(h.applied, []);
  assert.ok(h.queue.revision > revision, 'issued completion still invalidates snapshots after cancellation');
  assert.equal(h.queue.frequencyHz, 851000000, 'already issued receiver changes cannot be undone by pointer cancellation');
});

test('cancel removes a coalescing timer and a not-yet-started transaction', async () => {
  const h = await harness();
  h.queue.request(851000000); h.queue.cancel(); await settle();
  assert.equal(h.writes.length, 0);
  h.queue.request(852000000, { final: true }); await settle(); h.writes[0].resolve(); await settle();
  h.queue.request(853000000); assert.equal(h.timers.size, 1);
  h.queue.cancel(); await h.advance(100);
  assert.equal(h.timers.size, 0); assert.equal(h.writes.length, 1);
});

test('reset and a repeated drag wait for the previous write while rejecting its acknowledgement', async () => {
  const h = await harness();
  h.queue.request(851000000); await settle(); h.queue.reset(); h.queue.request(854000000, { final: true });
  assert.equal(h.writes.length, 1);
  h.writes[0].resolve(); await settle();
  assert.equal(h.writes.length, 2); assert.deepEqual(h.applied, []);
  h.writes[1].resolve(); await settle(); assert.equal(h.applied[0].frequency, 854000000);
});

test('navigation closes timers, pending work and late success or error callbacks', async () => {
  for (const rejection of [false, true]) {
    const h = await harness();
    h.queue.request(851000000); await settle(); h.queue.request(852000000); h.queue.close();
    if (rejection) h.writes[0].reject(new Error('old')); else h.writes[0].resolve();
    await settle(); await h.advance(100);
    assert.equal(h.writes.length, 1); assert.deepEqual(h.applied, []); assert.deepEqual(h.errors, []);
  }
});

test('a failure is retryable and invalid targets cannot reach the receiver', async () => {
  const h = await harness();
  for (const value of [NaN, Infinity, 0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1]) h.queue.request(value);
  await settle(); assert.equal(h.writes.length, 0);
  h.queue.request(851000000); await settle(); h.writes[0].reject(new Error('Retry')); await settle();
  assert.equal(h.errors.length, 1);
  h.queue.request(851000000, { final: true }); await settle(); assert.equal(h.writes.length, 2);
});
