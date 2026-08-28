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

    const heard = { _callId: 'heard', completed_at_ms: now };
    const announcedLater = { _callId: 'announced-later', completed_at_ms: now + 1 };
    const replayPlayer = Object.create(WebCallPlayer.prototype);
    let audioSource;
    Object.assign(replayPlayer, {
      current: heard,
      currentBuffer: { duration: 1 },
      recentCalls: [heard, announcedLater],
      recentReplay: null,
      lastHeard: null,
      paused: false,
      source: null,
      playbackOffset: 0,
      playbackStartedAt: 0,
      loadToken: 1,
      audioContext: {
        currentTime: 0,
        createBufferSource() {
          audioSource = {
            connect() {}, disconnect() {}, start() {}, stop() {}, onended: null, buffer: null
          };
          return audioSource;
        }
      },
      analyserNode: {},
      setStatus() {},
      render() {},
      startProgress() {},
      stopProgress() {},
      playNext(completed) { this.completed = completed; }
    });
    replayPlayer.startCurrent();
    assert.equal(replayPlayer.lastHeard, heard,
      'The replay target must be set only when normal browser audio actually starts');
    assert.equal(replayPlayer.lastHeardCall(), heard,
      'A newer announced call must not replace the last call the listener heard');
    audioSource.onended();
    assert.equal(replayPlayer.current, null);
    assert.equal(replayPlayer.lastHeardCall(), heard,
      'The last-heard replay target must remain available after normal completion');
    let replayedId = null;
    replayPlayer.replayCall = async (call) => { replayedId = call?._callId; return true; };
    assert.equal(await replayPlayer.replayLastCall(), true);
    assert.equal(replayedId, 'heard');

    replayPlayer.current = heard;
    replayPlayer.currentBuffer = { duration: 1 };
    replayPlayer.paused = true;
    let currentReplayCount = 0;
    replayPlayer.replayCurrent = async () => { currentReplayCount++; };
    assert.equal(await replayPlayer.replayLastCall(), true);
    assert.equal(replayPlayer.paused, false, 'Replay last call must start playback even when live audio was paused');
    assert.equal(currentReplayCount, 1);

    replayPlayer.lastHeard = heard;
    replayPlayer.current = announcedLater;
    replayPlayer.currentBuffer = { duration: 1 };
    replayPlayer.recentReplay = { current: null };
    replayPlayer.source = null;
    replayPlayer.startCurrent();
    assert.equal(replayPlayer.lastHeard, heard,
      'Replaying history must not redefine the normal last-heard target');

    replayPlayer.recentReplay = null;
    replayPlayer.current = null;
    replayPlayer.currentBuffer = null;
    replayPlayer.recentCalls = Array.from({ length: WebCallPlayer.MAXIMUM_RECENT_CALLS + 20 }, (_, index) => ({
      _callId: `newer-${index}`,
      completed_at_ms: now + index + 1
    }));
    replayPlayer.pruneRecentCalls();
    assert.equal(replayPlayer.lastHeardCall(), heard,
      'Queued announcements must not evict the independently bounded last-heard call');

    const avoidedCall = {
      _conversationKey: 'p25|GCRCN|target:1234',
      target_alias: 'GCRCN (Greater Cleveland Radio Communications Network)',
      target_id: 1234,
      target_form: 'TALKGROUP',
      source_alias: 'Cleveland',
      source_id: 5678,
      channel: 'T-GCRCN'
    };
    const avoidPlayer = Object.create(WebCallPlayer.prototype);
    Object.assign(avoidPlayer, {
      current: avoidedCall,
      currentBuffer: {},
      recentReplay: null,
      avoids: new Map(),
      holdTarget: null,
      paused: true,
      filterQueuedCalls() {},
      stopCurrent() {},
      setStatus() {},
      render() {}
    });
    avoidPlayer.avoidCurrent();
    assert.deepEqual([...avoidPlayer.avoids.values()], [{
      key: avoidedCall._conversationKey,
      label: 'GCRCN (Greater Cleveland Radio Communications Network)'
    }], 'The Avoid List must retain only the target label while removal continues to use the exact hidden key');
    assert.equal(avoidPlayer.removeAvoid(avoidedCall._conversationKey), true);
    assert.equal(avoidPlayer.avoids.size, 0, 'Removing the concise row must still remove its exact target key');

    const liveCall = { _callId: 'live' };
    const historicalCall = { _callId: 'historical' };
    const liveBuffer = { duration: 10 };
    const overlayPlayer = Object.create(WebCallPlayer.prototype);
    Object.assign(overlayPlayer, {
      current: liveCall,
      currentBuffer: liveBuffer,
      playbackOffset: 0.25,
      playbackStartedAt: 0,
      paused: false,
      source: {},
      recentReplay: null,
      transportToken: 0,
      loadToken: 0,
      loadController: null,
      queuedCount: 2,
      audioContext: { async resume() {} },
      getPlaybackPosition: () => 4.5,
      stopSource() { this.source = null; },
      clearIdleDisplay() {},
      ensureAudioContext() {},
      ensureConnected: () => true,
      setStatus() {},
      render() {},
      async loadCurrent() { this.loadedCall = this.current; },
      startCurrent() { this.restartedCall = this.current; }
    });
    assert.equal(await overlayPlayer.replayCall(historicalCall), true);
    assert.equal(overlayPlayer.current, historicalCall);
    assert.equal(overlayPlayer.loadedCall, historicalCall);
    assert.equal(overlayPlayer.recentReplay.current, liveCall);
    assert.equal(overlayPlayer.recentReplay.currentBuffer, liveBuffer);
    assert.equal(overlayPlayer.recentReplay.playbackOffset, 4.5);
    assert.equal(overlayPlayer.queuedCount, 2, 'Starting Replay Last Call must not alter the live queue');
    overlayPlayer.queuedCount = 3;
    overlayPlayer.currentBuffer = { duration: 2 };
    await overlayPlayer.returnToLive();
    assert.equal(overlayPlayer.current, liveCall);
    assert.equal(overlayPlayer.currentBuffer, liveBuffer);
    assert.equal(overlayPlayer.playbackOffset, 4.5);
    assert.equal(overlayPlayer.restartedCall, liveCall);
    assert.equal(overlayPlayer.queuedCount, 3, 'Calls arriving during replay must remain queued on return to live');

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
