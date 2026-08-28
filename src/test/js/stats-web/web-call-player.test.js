'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

async function main() {
  const playerPath = path.resolve(process.argv[2] ||
    path.resolve(__dirname, '../../../../stats-web/assets/web-call-player.js'));
  const source = fs.readFileSync(playerPath, 'utf8');
  const { WebCallPlayer } = await import(
    `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

  let now = 0;
  let nextTimerId = 1;
  const timers = new Map();
  const originalNow = Date.now;
  const originalWindow = global.window;
  Date.now = () => now;
  global.window = {
    setTimeout(callback, delay) {
      const id = nextTimerId++;
      timers.set(id, { callback, delay });
      return id;
    },
    clearTimeout(id) {
      timers.delete(id);
    }
  };

  try {
    const player = Object.create(WebCallPlayer.prototype);
    Object.assign(player, {
      current: null,
      currentBuffer: null,
      idleDisplayCall: null,
      idleDisplayTimer: null,
      idleDisplayDeadline: 0,
      paused: false,
      queuedCount: 0,
      renderCount: 0,
      render() { this.renderCount++; },
      setStatus(value) { this.status = value; }
    });
    const first = { _callId: 'first' };
    player.showIdleDisplay(first);
    const firstTimer = timers.values().next().value;
    assert.equal(firstTimer.delay, 5000);
    now = 4999;
    assert.equal(player.displayCall(), first);
    firstTimer.callback();
    const finalTimer = [...timers.values()].at(-1);
    assert.equal(finalTimer.delay, 1);
    assert.equal(player.displayCall(), first);
    now = 5000;
    assert.equal(player.displayCall(), null,
      'Reading presentation state at the deadline must not keep an expired call visible');
    assert.equal(timers.has([...timers.keys()].at(-1)), true,
      'Reading presentation state must not cancel the timer that repaints the UI');
    finalTimer.callback();
    assert.equal(player.displayCall(), null);
    assert.equal(player.renderCount, 1);

    now = 10_000;
    player.showIdleDisplay(first);
    const staleTimer = [...timers.values()].at(-1).callback;
    const second = { _callId: 'second' };
    player.showIdleDisplay(second);
    staleTimer();
    assert.equal(player.displayCall(), second, 'A stale timer must not clear a newer call');

    const next = { _callId: 'next' };
    player.takeNextCall = () => next;
    player.callMatchesSelection = () => true;
    player.isAllowed = () => true;
    player.loadCurrent = async () => {};
    await player.playNext();
    assert.equal(player.current, next);
    assert.equal(player.idleDisplayCall, null, 'A queued call replaces the lingering call immediately');

    player.current = null;
    player.takeNextCall = () => null;
    await player.playNext(first);
    assert.equal(player.displayCall(), first);
    assert.equal(player.status, 'Waiting');

    assert.doesNotMatch(source, /addEventListener\('snapshot'|consumeSnapshot\(/,
      'Automatic browser playback must not rebuild its queue from cached snapshots');
    assert.match(source, /addEventListener\('live_gap'/,
      'Dropped live calls must be reported instead of replayed from history');

    const listeners = new Map();
    const events = {
      addEventListener(name, listener) { listeners.set(name, listener); },
      close() {},
      update() {}
    };
    const connected = Object.create(WebCallPlayer.prototype);
    Object.assign(connected, {
      events: null,
      connectionFactory: () => events,
      connectionTopic: 'calls',
      scanListCatalogReady: true,
      scanListById: new Map([['1', { enabled: true }]]),
      selectedScanListIds: new Set(['1']),
      maximumSelectedScanLists: 8,
      maximumQueued: 100,
      queuedCount: 0,
      conversationLanes: new Map(),
      paused: false,
      current: null,
      currentBuffer: null,
      source: null,
      statusValue: '',
      missedCallCount: 0,
      possibleCallGap: false,
      stateObservers: new Set(),
      ui: { status: { textContent: '' } },
      renderCount: 0,
      render() { this.renderCount++; }
    });
    connected.ensureConnected();
    listeners.get('ready')({ data: JSON.stringify({ waiting_calls_per_listener: 12 }) });
    assert.equal(connected.maximumQueued, 12, 'A fresh subscription must apply the current queue limit');
    listeners.get('live_gap')({ data: JSON.stringify({ dropped: 2 }) });
    assert.equal(connected.missedCallCount, 2);
    assert.match(connected.ui.status.textContent, /2 calls skipped/);
    events.onerror();
    events.onopen();
    assert.equal(connected.possibleCallGap, true);
    assert.match(connected.ui.status.textContent, /additional calls may have been skipped during reconnect/);
  } finally {
    Date.now = originalNow;
    global.window = originalWindow;
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
