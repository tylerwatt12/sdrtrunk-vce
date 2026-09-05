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
      targetGrouping: false,
      targetBurstLimit: 2,
      lastPlaybackTargetKey: 'A',
      consecutiveTargetCalls: 1
    });
    const call = (id, started, target) => ({
      _callId: id, _startedAtMs: started, _arrivalSequence: started, _playbackTargetKey: target
    });
    queuePlayer.insertQueuedCall(call('a-late', 30, 'A'));
    queuePlayer.insertQueuedCall(call('b-first', 10, 'B'));
    queuePlayer.insertQueuedCall(call('a-first', 20, 'A'));
    assert.equal(queuePlayer.takeNextCall()._callId, 'b-first',
      'Conversation Mode off must choose the globally earliest waiting call');

    queuePlayer.queuedCalls = [];
    queuePlayer.queuedCount = 0;
    queuePlayer.targetGrouping = true;
    queuePlayer.lastPlaybackTargetKey = 'A';
    queuePlayer.consecutiveTargetCalls = 1;
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
    queuePlayer.lastPlaybackTargetKey = 'A';
    queuePlayer.consecutiveTargetCalls = 0;
    queuePlayer.targetBurstLimit = 20;
    queuePlayer.insertQueuedCall(call('same-later', 30, 'A'));
    queuePlayer.insertQueuedCall(call('same-earlier', 20, 'A'));
    assert.deepEqual(queuePlayer.scheduledQueue().map((item) => item._callId), ['same-earlier', 'same-later'],
      'Calls from one conversation must remain chronological');

    const trunked = {
      protocol: 'P25', system: 'Display name can change', radio_system_key: 'p25:bee00:49f',
      target_form: 'TALKGROUP', target_id: 56735, timeslot: 0,
      playback_target: {
        key: 'system:p25:bee00:49f:talkgroup:56735', kind: 'talkgroup',
        system_key: 'p25:bee00:49f', label: 'Talkgroup 56735'
      }
    };

    const normalized = Object.assign(Object.create(WebCallPlayer.prototype), { arrivalSequence: 0 });
    const normalizedCall = normalized.normalizeCall({
      ...trunked,
      call_id: 'instance:1', audio_url: '/api/v1/calls/instance:1/audio',
      started_at_ms: 100, completed_at_ms: 200, scan_list_ids: [1, 1, 2]
    });
    assert.deepEqual(normalizedCall._matchedScanListIds, ['1', '2']);
    assert.equal(normalizedCall._playbackTargetKey, trunked.playback_target.key);

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
      stopped: true,
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
    feedPlayer.stopped = false;
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
      stopped: false,
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
      stopped: false,
      transportToken: 0,
      feedStopped: 0,
      queueCleared: 0,
      currentStopped: 0,
      replayingLast: false,
      stopAfterReplay: false,
      lastPlaybackTargetKey: 'A',
      consecutiveTargetCalls: 2,
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
    assert.equal(stopped.stopped, true);
    assert.equal(stopped.feedStopped, 1);
    assert.equal(stopped.queueCleared, 1);
    assert.equal(stopped.currentStopped, 1);
    assert.equal(stopped.lastHeard._callId, 'retained');
    assert.equal(stopped.lastHeardBuffer.duration, 3,
      'Stop must retain exactly the one local Replay Last buffer');
    assert.equal(stopped.lastPlaybackTargetKey, null);
    assert.equal(stopped.status, 'Ready');

    function playerReadyToStart(selectedScanListIds = []) {
      const defaultScanList = { id: '2', name: 'Default', enabled: true, default: true };
      const otherScanList = { id: '1', name: 'Dispatch', enabled: true, default: false };
      return Object.assign(Object.create(WebCallPlayer.prototype), {
        stopped: true,
        scanListCatalogReady: true,
        scanLists: [otherScanList, defaultScanList],
        scanListById: new Map([[otherScanList.id, otherScanList], [defaultScanList.id, defaultScanList]]),
        selectedScanListIds: new Set(selectedScanListIds),
        maximumSelectedScanLists: 16,
        transportToken: 0,
        preferenceWrites: 0,
        feedStarts: 0,
        clearLossNotice() {},
        writePreferences() { this.preferenceWrites++; },
        updateScanListStatus() {},
        filterQueueForSelectedLists() {},
        renderScanLists() {},
        ensureConnected() { this.feedStarts++; return true; },
        ensureAudioContext() {},
        audioContext: { state: 'suspended', async resume() {} },
        setStatus(value) { this.status = value; },
        render() {}
      });
    }

    const defaultOnPlay = playerReadyToStart();
    await defaultOnPlay.togglePlayback();
    assert.deepEqual([...defaultOnPlay.selectedScanListIds], ['2'],
      'Play with no selection must choose the administrator-configured default Scan List');
    assert.equal(defaultOnPlay.preferenceWrites, 1,
      'The automatic default selection must be saved through the current user preferences');
    assert.equal(defaultOnPlay.feedStarts, 1);
    assert.equal(defaultOnPlay.stopped, false);

    const staleSelectionOnPlay = playerReadyToStart(['999']);
    await staleSelectionOnPlay.togglePlayback();
    assert.deepEqual([...staleSelectionOnPlay.selectedScanListIds], ['2'],
      'A stale saved selection must not prevent fallback to the current default Scan List');
    assert.equal(staleSelectionOnPlay.preferenceWrites, 1);

    const explicitSelectionOnPlay = playerReadyToStart(['1']);
    await explicitSelectionOnPlay.togglePlayback();
    assert.deepEqual([...explicitSelectionOnPlay.selectedScanListIds], ['1'],
      'Play must preserve an available Scan List explicitly selected by the user');
    assert.equal(explicitSelectionOnPlay.preferenceWrites, 0);

    const selection = Object.assign(Object.create(WebCallPlayer.prototype), {
      scanListById: new Map([['1', { id: '1', enabled: true }]]),
      selectedScanListIds: new Set(),
      maximumSelectedScanLists: 128,
      scanListCatalogReady: true,
      stopped: true,
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
      targetGrouping: true,
      targetBurstLimit: 4,
      subscriptionChanges: 0,
      filterQueueForSelectedLists() {}, renderScanLists() {}, render() {},
      synchronizeSubscription() { this.subscriptionChanges++; }
    });
    preferencePlayer.applyPreferences({
      volume: 0.5, selected_scan_list_ids: [1], target_grouping: false,
      target_burst_limit: 2
    });
    assert.equal(preferencePlayer.subscriptionChanges, 0,
      'Unrelated preference saves must not restart a live call feed at a new cursor');
    preferencePlayer.applyPreferences({
      volume: 0.5, selected_scan_list_ids: [2], target_grouping: false,
      target_burst_limit: 2
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
      stopped: false,
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
    replay.stopped = true;
    replay.current = null;
    replay.currentBuffer = null;
    assert.equal(await replay.replayLastCall(), true);
    assert.equal(replay.current, heard);
    assert.equal(replay.currentBuffer, heardBuffer);
    assert.equal(replay.replayingLast, true);
    assert.equal(replay.stopAfterReplay, true);

    function pauseFixture() {
      const player = Object.assign(Object.create(WebCallPlayer.prototype), {
        stopped: false, paused: false, transportToken: 0, loadToken: 0, loadController: null,
        current: null, currentBuffer: null, source: null, lastHeard: heard, lastHeardBuffer: heardBuffer,
        replayingLast: false, stopAfterReplay: false, playbackStartedAt: 0,
        queuedCalls: [], queuedCount: 0, maximumQueued: 100, targetGrouping: false,
        seenCallIds: new Set(), seenCallOrder: [], arrivalSequence: 0,
        avoids: new Map(), holdTarget: null, selectedScanListIds: new Set(['1']),
        scanLists: [{ id: '1', name: 'Test', enabled: true, default: true }],
        scanListById: new Map([['1', { id: '1', enabled: true }]]),
        maximumSelectedScanLists: 16, scanListCatalogReady: true,
        feedActive: true, feedGeneration: 1, feedCursor: '10', feedTimer: null,
        feedController: null, feedUrl: '/api/v1/calls/feed',
        skippedNotice: false, statusValue: 'Waiting', stateObservers: new Set(),
        ui: { status: { textContent: '' } }, progressStarts: 0, progressStops: 0,
        ensureAudioContext() {}, clearIdleDisplay() {}, renderScanLists() {},
        writePreferences() {}, updateScanListStatus() {},
        startProgress() { this.progressStarts++; }, stopProgress() { this.progressStops++; },
        render() { this.renderStatus(); },
        audioContext: {
          state: 'running', currentTime: 3,
          async suspend() { this.state = 'suspended'; },
          async resume() { this.state = 'running'; },
          createBufferSource() {
            return { connect() {}, disconnect() {}, start() {}, stop() {}, onended: null };
          }
        }
      });
      return player;
    }

    const pausing = pauseFixture();
    pausing.current = { ...overlap, _callId: 'current' };
    pausing.currentBuffer = { duration: 20 };
    pausing.startCurrent();
    pausing.audioContext.currentTime = 7;
    const retainedSource = pausing.source;
    const retainedAudio = pausing.currentBuffer;
    await pausing.togglePause();
    assert.equal(pausing.getPlaybackPosition(), 4);
    assert.equal(pausing.source, retainedSource, 'Pause must preserve the actual audio source');
    assert.equal(pausing.audioContext.state, 'suspended');
    assert.equal(pausing.feedActive, true);
    assert.equal(pausing.feedCursor, '10', 'Pause must not restart the feed at the live edge');
    assert.equal(pausing.viewState().playing, false);
    assert.equal(pausing.viewState().stopped, false);
    assert.equal(await pausing.replayLastCall(), false, 'Replay must not silently unpause playback');
    await pausing.togglePause();
    assert.equal(pausing.source, retainedSource);
    assert.equal(pausing.currentBuffer, retainedAudio);
    assert.equal(pausing.getPlaybackPosition(), 4, 'Resume must not restart the call');
    assert.equal(pausing.audioContext.state, 'running');
    await pausing.togglePause();
    pausing.enqueue(overlap);
    assert.equal(pausing.queuedCount, 1);
    await pausing.togglePlayback();
    assert.equal(pausing.stopped, true);
    assert.equal(pausing.paused, false);
    assert.equal(pausing.source, null);
    assert.equal(pausing.currentBuffer, null);
    assert.equal(pausing.queuedCount, 0);
    assert.equal(pausing.feedActive, false);
    assert.equal(pausing.feedCursor, null);
    await pausing.togglePause();
    assert.equal(pausing.stopped, true, 'Pause while stopped is a no-op');

    const collecting = pauseFixture();
    await collecting.togglePause();
    collecting.requestFeed = async () => ({ cursor: '11', reset: false, calls: [overlap] });
    await collecting.pollFeed(1);
    assert.equal(collecting.queuedCount, 1);
    assert.equal(collecting.current, null, 'Feed arrivals must not start paused audio');
    assert.equal(collecting.feedCursor, '11');
    assert.ok(collecting.feedTimer !== null, 'Paused playback must keep polling');
    for (let i = 0; i < 105; i++) collecting.enqueue({
      ...overlap, call_id: 'overflow-' + i, started_at_ms: 500 + i
    });
    assert.equal(collecting.queuedCount, 100);
    assert.equal(collecting.queuedCalls[0]._callId, 'overflow-5');
    assert.equal(collecting.ui.status.textContent, 'Paused — 100 calls queued · Some calls were skipped');
    collecting.skip();
    assert.equal(collecting.queuedCount, 99);
    assert.equal(collecting.paused, true);
    collecting.clearQueue();
    assert.equal(collecting.paused, true);
    assert.equal(collecting.queuedCount, 0);
    collecting.stopFeed();

    const selectionWhilePaused = pauseFixture();
    selectionWhilePaused.current = selectionWhilePaused.normalizeCall(overlap);
    selectionWhilePaused.currentBuffer = { duration: 20 };
    await selectionWhilePaused.togglePause();
    selectionWhilePaused.toggleHold();
    assert.equal(selectionWhilePaused.holdTarget, overlap.playback_target.key);
    selectionWhilePaused.avoidCurrent();
    assert.equal(selectionWhilePaused.current, null);
    assert.equal(selectionWhilePaused.paused, true);
    assert.equal(selectionWhilePaused.avoids.size, 1);
    selectionWhilePaused.setScanListSelected('1', false);
    assert.equal(selectionWhilePaused.stopped, true, 'Removing the last list also stops a paused feed');
    assert.equal(selectionWhilePaused.paused, false);
    assert.equal(selectionWhilePaused.feedActive, false);

    const originalFetch = global.fetch;
    try {
      let finishDownload;
      global.fetch = () => new Promise((resolve) => { finishDownload = resolve; });
      const loading = pauseFixture();
      loading.current = loading.normalizeCall(overlap);
      loading.audioContext.decodeAudioData = async () => ({ duration: 10 });
      const download = loading.loadCurrent();
      await loading.togglePause();
      finishDownload({ ok: true, arrayBuffer: async () => new ArrayBuffer(4) });
      await download;
      assert.equal(loading.source, null, 'Download completion while paused must remain silent');
      assert.equal(loading.currentBuffer.duration, 10);
      await loading.togglePause();
      assert.ok(loading.source, 'Resume starts audio that finished downloading while paused');
      await loading.togglePlayback();

      const stoppedDownload = pauseFixture();
      stoppedDownload.current = stoppedDownload.normalizeCall(overlap);
      stoppedDownload.audioContext.decodeAudioData = async () => ({ duration: 10 });
      const staleDownload = stoppedDownload.loadCurrent();
      await stoppedDownload.togglePause();
      await stoppedDownload.togglePlayback();
      finishDownload({ ok: true, arrayBuffer: async () => new ArrayBuffer(4) });
      await staleDownload;
      assert.equal(stoppedDownload.currentBuffer, null, 'Late downloads cannot resurrect stopped audio');
      assert.equal(stoppedDownload.source, null);

      global.fetch = async () => ({ ok: false, status: 410 });
      const expired = pauseFixture();
      expired.current = expired.normalizeCall(overlap);
      await expired.togglePause();
      await expired.loadCurrent();
      assert.equal(expired.skippedNotice, true);
      assert.equal(expired.current, null);
      assert.equal(expired.paused, true, 'Expired audio must not resume playback');
    } finally {
      global.fetch = originalFetch;
    }

    const resumeQueue = pauseFixture();
    await resumeQueue.togglePause();
    resumeQueue.enqueue(overlap);
    resumeQueue.loadCurrent = async function () { this.loadedId = this.current._callId; };
    await resumeQueue.togglePause();
    assert.equal(resumeQueue.loadedId, overlap.call_id, 'Resume while idle must start the queued call');

    const racing = pauseFixture();
    let finishSuspend;
    racing.audioContext.suspend = () => new Promise((resolve) => { finishSuspend = resolve; });
    const pendingPause = racing.togglePause();
    racing.audioContext.suspend = async function () { this.state = 'suspended'; };
    await racing.togglePlayback();
    finishSuspend();
    await pendingPause;
    assert.equal(racing.stopped, true);
    assert.equal(racing.paused, false);
    assert.equal(racing.ui.status.textContent, 'Ready', 'Stale pause completion must not undo Stop');

    const rejectedResume = pauseFixture();
    await rejectedResume.togglePause();
    rejectedResume.audioContext.resume = async () => { throw new Error('Audio unavailable'); };
    await rejectedResume.togglePause();
    assert.equal(rejectedResume.paused, true, 'Rejected resume must remain paused');

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
