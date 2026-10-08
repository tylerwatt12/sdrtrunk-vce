const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2] || 'stats-web/assets/app.js', 'utf8');
const start = source.indexOf('let browserLiveRecoveryTimer = null;');
const end = source.indexOf("window.addEventListener('focus', recoverBrowserLiveDelivery);", start);
assert.ok(start >= 0 && end > start);

function harness({ ready = true, age = 0, aborted = false, connected = true } = {}) {
  const timers = new Map();
  let sequence = 0;
  let restarts = 0;
  let audioRecoveries = 0;
  const context = vm.createContext({
    document: { hidden: false }, Date: { now: () => 100_000 },
    LIVE_MULTIPLEX_LIVENESS_TIMEOUT_MS: 25_000, LIVE_MULTIPLEX_READY_TIMEOUT_MS: 10_000,
    liveMultiplexer: { ready, lastFrameAt: 100_000 - age,
      controller: connected ? { signal: { aborted } } : null, restart: () => restarts++ },
    webCallPlayer: { recoverFeed: () => audioRecoveries++ },
    window: { setTimeout: (callback) => { timers.set(++sequence, callback); return sequence; },
      clearTimeout: (id) => timers.delete(id) }
  });
  vm.runInContext(source.slice(start, end), context);
  return { context, recover: () => context.recoverBrowserLiveDelivery(),
    flush: () => { for (const [id, callback] of [...timers]) { timers.delete(id); callback(); } },
    counts: () => ({ restarts, audioRecoveries, timers: timers.size }) };
}

test('returning focus preserves a healthy stream and a still-progressing initial connection', () => {
  for (const options of [{ age: 24_999 }, { ready: false, age: 9_999 }]) {
    const h = harness(options);
    h.recover(); h.recover(); h.flush();
    assert.deepEqual(h.counts(), { restarts: 0, audioRecoveries: 1, timers: 0 });
  }
});

test('focus recovery restarts stale, aborted and missing connections at the appropriate deadline', () => {
  for (const options of [{ age: 25_000 }, { ready: false, age: 10_000 },
    { aborted: true }, { connected: false }]) {
    const h = harness(options);
    h.recover(); h.flush();
    assert.equal(h.counts().restarts, 1);
  }
});

test('a hidden page does not restart and a queued focus event cannot revive a now-hidden page', () => {
  const h = harness({ connected: false });
  h.context.document.hidden = true;
  h.recover();
  assert.equal(h.counts().timers, 0);
  h.context.document.hidden = false;
  h.recover();
  h.context.document.hidden = true;
  h.flush();
  assert.deepEqual(h.counts(), { restarts: 0, audioRecoveries: 0, timers: 0 });
});
