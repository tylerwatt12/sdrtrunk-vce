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
    clearTimeout(id) { timers.delete(id); }
  };

  try {
    const presentation = Object.create(WebCallPlayer.prototype);
    Object.assign(presentation, {
      current: null,
      idleDisplayCall: null,
      idleDisplayTimer: null,
      idleDisplayDeadline: 0,
      renderCount: 0,
      render() { this.renderCount++; }
    });
    const first = { _callId: 'first' };
    presentation.showIdleDisplay(first);
    const firstTimer = timers.values().next().value;
    assert.equal(firstTimer.delay, 5000);
    now = 4999;
    assert.equal(presentation.displayCall(), first);
    firstTimer.callback();
    const finalTimer = [...timers.values()].at(-1);
    assert.equal(finalTimer.delay, 1);
    now = 5000;
    assert.equal(presentation.displayCall(), null);
    finalTimer.callback();
    assert.equal(presentation.renderCount, 1);

    const queuePlayer = Object.create(WebCallPlayer.prototype);
    Object.assign(queuePlayer, {
      queuedCalls: [],
      queuedCount: 0,
      conversationGrouping: false,
      conversationBurstLimit: 2,
      lastConversationKey: 'A',
      consecutiveConversationCalls: 1
    });
    const call = (id, started, conversation) => ({
      _callId: id, _startedAtMs: started, _arrivalSequence: started, _conversationKey: conversation
    });
    queuePlayer.insertQueuedCall(call('a-late', 30, 'A'));
    queuePlayer.insertQueuedCall(call('b-first', 10, 'B'));
    queuePlayer.insertQueuedCall(call('a-first', 20, 'A'));
    assert.equal(queuePlayer.takeNextCall()._callId, 'b-first',
      'Conversation Mode off must choose the globally earliest waiting call');

    queuePlayer.queuedCalls = [];
    queuePlayer.queuedCount = 0;
    queuePlayer.conversationGrouping = true;
    queuePlayer.lastConversationKey = 'A';
    queuePlayer.consecutiveConversationCalls = 1;
    queuePlayer.insertQueuedCall(call('b-oldest', 10, 'B'));
    queuePlayer.insertQueuedCall(call('a-one', 20, 'A'));
    queuePlayer.insertQueuedCall(call('a-two', 30, 'A'));
    assert.equal(queuePlayer.takeNextCall()._callId, 'a-one',
      'Conversation Mode may regroup only a call that is already waiting');
    assert.equal(queuePlayer.takeNextCall()._callId, 'b-oldest',
      'The burst limit must give another waiting conversation its turn');
    assert.equal(queuePlayer.takeNextCall()._callId, 'a-two');
    queuePlayer.queuedCalls = [];
    queuePlayer.queuedCount = 0;
    queuePlayer.lastConversationKey = 'A';
    queuePlayer.consecutiveConversationCalls = 0;
    queuePlayer.conversationBurstLimit = 20;
    queuePlayer.insertQueuedCall(call('same-later', 30, 'A'));
    queuePlayer.insertQueuedCall(call('same-earlier', 20, 'A'));
    assert.deepEqual(queuePlayer.scheduledQueue().map((item) => item._callId), ['same-earlier', 'same-later'],
      'Calls from one conversation must remain chronological');

    const trunked = {
      protocol: 'P25', system: 'Display name can change', system_identity: 'p25:BEE00:49F',
      target_form: 'TALKGROUP', target_id: 56735, timeslot: 0,
      conversation_key: 'p25|system:p25:BEE00:49F|talkgroup:56735|slot:0'
    };

    const normalized = Object.assign(Object.create(WebCallPlayer.prototype), { arrivalSequence: 0 });
    const normalizedCall = normalized.normalizeCall({
      ...trunked,
      call_id: 'instance:1', audio_url: '/api/v1/calls/instance:1/audio',
      started_at_ms: 100, completed_at_ms: 200, scan_list_ids: [1, 1, 2]
    });
    assert.deepEqual(normalizedCall._matchedScanListIds, ['1', '2']);
    assert.equal(normalizedCall._conversationKey, trunked.conversation_key);

    const dedupe = Object.assign(Object.create(WebCallPlayer.prototype), {
      arrivalSequence: 0,
      selectedScanListIds: new Set(['1', '2']),
      scanListById: new Map([['1', { enabled: true }], ['2', { enabled: true }]]),
      maximumSelectedScanLists: 128,
      maximumQueued: 100,
      seenCallIds: new Set(),
      seenCallOrder: [],
      queuedCalls: [],
      queuedCount: 0,
      avoids: new Map(),
      holdTarget: null,
      paused: true,
      current: null,
      render() {}
    });
    const overlap = {
      ...trunked,
      call_id: 'instance:overlap', audio_url: '/api/v1/calls/instance:overlap/audio',
      started_at_ms: 300, completed_at_ms: 400, scan_list_ids: [1, 2]
    };
    dedupe.enqueue(overlap);
    dedupe.enqueue(overlap);
    assert.equal(dedupe.queuedCount, 1,
      'One call matching several selected Scan Lists must enter the browser queue only once');

    const feedPlayer = Object.assign(Object.create(WebCallPlayer.prototype), {
      feedUrl: '/api/v1/calls/feed',
      feedCursor: null,
      scanListById: new Map([['1', { enabled: true }], ['2', { enabled: true }]]),
      selectedScanListIds: new Set(['2', '1']),
      maximumSelectedScanLists: 128
    });
    assert.equal(feedPlayer.feedRequestUrl(), '/api/v1/calls/feed?scan_list_id=1&scan_list_id=2');
    feedPlayer.feedCursor = '42';
    assert.equal(feedPlayer.feedRequestUrl(), '/api/v1/calls/feed?scan_list_id=1&scan_list_id=2&cursor=42');
    feedPlayer.feedFetch = async () => ({ cursor: '43', reset: false, calls: [] });
    assert.deepEqual(await feedPlayer.requestFeed({}), { cursor: '43', reset: false, calls: [] });
    feedPlayer.feedFetch = async () => ({ cursor: 43, reset: false, calls: [] });
    await assert.rejects(() => feedPlayer.requestFeed({}), /invalid response/);
    feedPlayer.feedCursor = '99';
    feedPlayer.feedActive = true;
    feedPlayer.feedGeneration = 0;
    feedPlayer.feedController = null;
    feedPlayer.feedTimer = null;
    feedPlayer.stopFeed();
    assert.equal(feedPlayer.feedCursor, null);
    assert.equal(feedPlayer.feedRequestUrl(), '/api/v1/calls/feed?scan_list_id=1&scan_list_id=2',
      'A restarted player must omit its old cursor and begin at the live edge');
    feedPlayer.paused = false;
    feedPlayer.feedActive = false;
    feedPlayer.scanListCatalogReady = true;
    feedPlayer.pollFeed = async function () { this.firstRestartUrl = this.feedRequestUrl(); };
    assert.equal(feedPlayer.ensureConnected(), true);
    await Promise.resolve();
    assert.equal(feedPlayer.firstRestartUrl, '/api/v1/calls/feed?scan_list_id=1&scan_list_id=2');

    const notice = Object.assign(Object.create(WebCallPlayer.prototype), {
      skippedNotice: false,
      statusValue: 'Waiting',
      ui: { status: { textContent: '' } },
      stateObservers: new Set()
    });
    notice.recordSkippedCallNotice();
    notice.recordSkippedCallNotice();
    assert.equal(notice.ui.status.textContent, 'Waiting · Some calls were skipped',
      'Feed resets and queue overflow use one generic notice instead of an exact count');
    const resetPoll = Object.assign(Object.create(WebCallPlayer.prototype), {
      feedActive: true,
      paused: false,
      feedGeneration: 7,
      feedController: null,
      feedCursor: '10',
      current: null,
      queuedCount: 0,
      skippedNotice: false,
      statusValue: '',
      ui: { status: { textContent: '' } },
      stateObservers: new Set(),
      requestFeed: async () => ({ cursor: '20', reset: true, calls: [] }),
      scheduleFeedPoll() {},
      enqueue() {}
    });
    await resetPoll.pollFeed(7);
    assert.equal(resetPoll.feedCursor, '20');
    assert.equal(resetPoll.ui.status.textContent, 'Waiting · Some calls were skipped');

    const stopped = Object.assign(Object.create(WebCallPlayer.prototype), {
      paused: false,
      transportToken: 0,
      feedStopped: 0,
      queueCleared: 0,
      currentStopped: 0,
      replayingLast: false,
      stopAfterReplay: false,
      lastConversationKey: 'A',
      consecutiveConversationCalls: 2,
      lastHeard: { _callId: 'retained' },
      lastHeardBuffer: { duration: 3 },
      audioContext: { state: 'running', async suspend() {} },
      stopFeed() { this.feedStopped++; },
      clearQueuedCalls() { this.queueCleared++; },
      stopCurrent() { this.currentStopped++; },
      clearLossNotice() {},
      setStatus(value) { this.status = value; },
      render() {}
    });
    await stopped.togglePlayback();
    assert.equal(stopped.paused, true);
    assert.equal(stopped.feedStopped, 1);
    assert.equal(stopped.queueCleared, 1);
    assert.equal(stopped.currentStopped, 1);
    assert.equal(stopped.lastHeard._callId, 'retained');
    assert.equal(stopped.lastHeardBuffer.duration, 3,
      'Stop must retain exactly the one local Replay Last buffer');
    assert.equal(stopped.lastConversationKey, null);
    assert.equal(stopped.status, 'Ready');

    const selection = Object.assign(Object.create(WebCallPlayer.prototype), {
      scanListById: new Map([['1', { id: '1', enabled: true }]]),
      selectedScanListIds: new Set(),
      maximumSelectedScanLists: 128,
      scanListCatalogReady: true,
      paused: true,
      toggleCount: 0,
      togglePlayback() { this.toggleCount++; },
      clearLossNotice() {}, writePreferences() {}, updateScanListStatus() {}, filterQueueForSelectedLists() {},
      renderScanLists() {},
      ensureConnected() { this.feedStartCount = (this.feedStartCount || 0) + 1; return true; },
      stopFeed() {}, setStatus() {}, render() {}
    });
    selection.setScanListSelected('1', true);
    assert.equal(selection.toggleCount, 0, 'Selecting a Scan List while stopped must not start playback');
    assert.equal(selection.feedStartCount || 0, 0, 'Selecting a Scan List while stopped must not start the feed');

    const preferencePlayer = Object.assign(Object.create(WebCallPlayer.prototype), {
      selectedScanListIds: new Set(['1']),
      ui: { volume: { value: '1' } },
      volume: 1,
      conversationGrouping: true,
      conversationBurstLimit: 4,
      subscriptionChanges: 0,
      filterQueueForSelectedLists() {}, renderScanLists() {}, render() {},
      synchronizeSubscription() { this.subscriptionChanges++; }
    });
    preferencePlayer.applyPreferences({
      volume: 0.5, selected_scan_list_ids: [1], conversation_grouping: false,
      conversation_burst_limit: 2
    });
    assert.equal(preferencePlayer.subscriptionChanges, 0,
      'Unrelated preference saves must not restart a live call feed at a new cursor');
    preferencePlayer.applyPreferences({
      volume: 0.5, selected_scan_list_ids: [2], conversation_grouping: false,
      conversation_burst_limit: 2
    });
    assert.equal(preferencePlayer.subscriptionChanges, 1);

    let audioSource;
    const heard = { _callId: 'heard' };
    const heardBuffer = { duration: 1 };
    const replay = Object.assign(Object.create(WebCallPlayer.prototype), {
      current: heard,
      currentBuffer: heardBuffer,
      lastHeard: null,
      lastHeardBuffer: null,
      replayingLast: false,
      stopAfterReplay: false,
      paused: false,
      source: null,
      playbackStartedAt: 0,
      loadToken: 1,
      transportToken: 0,
      loadController: null,
      audioContext: {
        currentTime: 0, state: 'running', async resume() {}, async suspend() {},
        createBufferSource() {
          audioSource = { connect() {}, disconnect() {}, start() {}, stop() {}, onended: null, buffer: null };
          return audioSource;
        }
      },
      analyserNode: {},
      setStatus(value) { this.status = value; }, render() {}, startProgress() {}, stopProgress() {},
      clearIdleDisplay() {}, playNext() {}
    });
    replay.startCurrent();
    assert.equal(replay.lastHeard, null,
      'Replay Last must refer to the prior completed call, not the call currently playing');
    audioSource.onended();
    assert.equal(replay.lastHeard, heard);
    assert.equal(replay.lastHeardBuffer, heardBuffer,
      'A naturally completed call must keep one decoded audio buffer for local replay');
    replay.paused = true;
    replay.current = null;
    replay.currentBuffer = null;
    assert.equal(await replay.replayLastCall(), true);
    assert.equal(replay.current, heard);
    assert.equal(replay.currentBuffer, heardBuffer);
    assert.equal(replay.replayingLast, true);
    assert.equal(replay.stopAfterReplay, true);

    assert.doesNotMatch(source, /Recent Calls|recentCalls|recentReplay|live_gap|conversationLanes|playbackOffset/);
    assert.match(source, /feedCursor|recordSkippedCallNotice/);
  } finally {
    Date.now = originalNow;
    global.window = originalWindow;
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
