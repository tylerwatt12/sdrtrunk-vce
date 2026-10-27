'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const assets = path.resolve(__dirname, '../../../../stats-web/assets');
  const { createDirectListenSession, directListenTargetKey, normalizeDirectListenTarget } =
    await import(pathToFileURL(path.join(assets, 'core/direct-listen.js')).href);
  const { WebCallPlayer } = await import(pathToFileURL(path.join(assets, 'web-call-player.js')).href);
  const group = (id, system = 'p25:bee00:348') => ({ kind: 'talkgroup', radio_system_key: system,
    identity_key: `v1-g-bee00-348-${id}` });
  function player() {
    return Object.assign(Object.create(WebCallPlayer.prototype), {
      directListenSession: createDirectListenSession({ storage: null }), directListenAllowed: true,
      scanListCatalogReady: true, scanListById: new Map([['1', { enabled: true }]]),
      selectedScanListIds: new Set(['1']), maximumSelectedScanLists: 16,
      queuedCalls: [], queuedCount: 0, maximumQueued: 100, seenCallIds: new Set(), seenCallOrder: [],
      arrivalSequence: 0, current: null, currentBuffer: null, holdTarget: null, avoids: new Map(),
      stopped: true, paused: false, replayingLast: false, stopAfterReplay: false,
      feedUrl: '/api/v1/calls/feed', feedCursor: null, feedActive: false, feedGeneration: 0,
      feedController: null, feedTimer: null, transportToken: 0,
      render() {}, synchronizeSubscription() { this.subscriptionChanges = (this.subscriptionChanges || 0) + 1; },
      stopCurrent() { this.current = null; this.currentBuffer = null; },
      playNext() { this.nextRequested = true; }
    });
  }
  const mixed = player();
  const target = group(40);
  const added = mixed.addDirectListenTarget(target, 'Dispatch', 'County');
  assert.equal(mixed.stopped, true, 'Add preserves Stop.');
  assert.deepEqual([...mixed.selectedScanListIds], ['1'], 'Add preserves selected Scan Lists.');
  let url = new URL(mixed.feedRequestUrl(), 'https://example.invalid');
  assert.deepEqual(url.searchParams.getAll('scan_list_id'), ['1']);
  assert.deepEqual(url.searchParams.getAll('direct_target'), [added.key]);
  const call = (id, lists = [], keys = [added.key]) => ({
    call_id: id, audio_url: `/api/v1/calls/${id}/audio`, started_at_ms: 100, completed_at_ms: 200,
    scan_list_ids: lists, direct_target_keys: keys,
    playback_target: { kind: 'talkgroup', key: 'receiver:group:40' }
  });
  mixed.enqueue(call('overlap', [1]));
  mixed.enqueue(call('overlap', [1]));
  mixed.enqueue(call('direct-only'));
  mixed.enqueue(call('other-system', [], [directListenTargetKey(group(40, 'p25:bee00:349'))]));
  assert.equal(mixed.queuedCount, 2, 'A list/direct overlap plays once and unrelated systems remain excluded.');
  mixed.stopped = false; mixed.paused = true;
  mixed.addDirectListenTarget(group(41), 'Other dispatch');
  assert.equal(mixed.paused, true, 'Add preserves Pause.');
  mixed.setDirectListenSelected(false);
  assert.deepEqual(mixed.queuedCalls.map(item => item._callId), ['overlap']);
  mixed.setDirectListenSelected(true);
  mixed.avoids.set('receiver:group:40', { key: 'receiver:group:40' });
  mixed.enqueue(call('avoided'));
  assert.equal(mixed.queuedCount, 1, 'Existing Avoid continues to suppress Direct Listen calls.');
  mixed.avoids.clear();
  mixed.enqueue(call('current'));
  mixed.current = mixed.queuedCalls.find(item => item._callId === 'current');
  mixed.removeDirectListenTarget(added.key);
  assert.equal(mixed.current, null, 'Removing the target cancels a current call unless a selected list also matches.');
  assert.deepEqual(mixed.queuedCalls.map(item => item._callId), ['overlap']);
  mixed.setDirectListenAllowed(false);
  assert.deepEqual(new URL(mixed.feedRequestUrl(), 'https://example.invalid').searchParams.getAll('direct_target'), []);
  assert.throws(() => mixed.addDirectListenTarget(group(50)), /unavailable/);
  assert.equal(mixed.normalizeCall(call('invalid', [], ['invalid'])), null);

  const revoked = player();
  revoked.addDirectListenTarget(target);
  const directCall = revoked.normalizeCall(call('retained-direct'));
  revoked.lastHeard = directCall;
  revoked.lastHeardBuffer = { duration: 3 };
  revoked.lastHeardSourceSampleRate = 8000;
  revoked.heldDisplayCall = directCall;
  revoked.holdTarget = directCall._playbackTargetKey;
  revoked.idleDisplayCall = directCall;
  revoked.clearIdleDisplay = () => { revoked.idleDisplayCall = null; };
  revoked.stopSource = () => {};
  let resumeReplay;
  revoked.audioContext = { state: 'running', resume: () => new Promise(done => { resumeReplay = done; }),
    suspend: async () => {} };
  revoked.ensureAudioContext = () => {};
  revoked.startCurrent = () => { revoked.replayStarted = true; };
  const pendingReplay = revoked.replayLastCall();
  assert.equal(revoked.replayingLast, true);
  revoked.setDirectListenAllowed(false);
  resumeReplay();
  assert.equal(await pendingReplay, false, 'Revocation cancels a replay waiting for browser audio to resume.');
  assert.equal(revoked.replayStarted, undefined);
  assert.equal(revoked.current, null);
  assert.equal(revoked.lastHeard, null);
  assert.equal(revoked.lastHeardBuffer, null);
  assert.equal(revoked.lastHeardSourceSampleRate, null);
  assert.equal(revoked.heldDisplayCall, null);
  assert.equal(revoked.holdTarget, null);
  assert.equal(revoked.idleDisplayCall, null);
  assert.equal(revoked.stopped, true, 'An interrupted one-off replay returns to Stop.');
  assert.equal(await revoked.replayLastCall(), false);

  const overlapRetained = player();
  overlapRetained.addDirectListenTarget(target);
  const overlapCall = overlapRetained.normalizeCall(call('retained-overlap', [1]));
  overlapRetained.lastHeard = overlapCall;
  overlapRetained.lastHeardBuffer = { duration: 3 };
  overlapRetained.setDirectListenAllowed(false);
  assert.equal(overlapRetained.lastHeard, overlapCall,
    'Losing Direct Listen leaves audio available through a selected normal Scan List.');
  const normalCall = overlapRetained.normalizeCall(call('normal-only', [1], []));
  overlapRetained.current = normalCall;
  overlapRetained.setDirectListenIdentity('new-listener');
  assert.equal(overlapRetained.lastHeard, null, 'An identity change clears retained calls tagged by the prior Direct Listen session.');
  assert.equal(overlapRetained.current, normalCall, 'Ordinary Scan List playback keeps its existing identity-change behavior.');

  const independent = player();
  independent.selectedScanListIds.clear();
  independent.scanListCatalogReady = false;
  independent.addDirectListenTarget(target, 'Dispatch');
  independent.stopped = false;
  independent.feedFetch = () => {};
  independent.pollFeed = generation => { independent.startedGeneration = generation; };
  assert.equal(independent.ensureConnected(), true, 'Direct Listen does not require the Scan List catalog.');
  url = new URL(independent.feedRequestUrl(), 'https://example.invalid');
  assert.deepEqual(url.searchParams.getAll('scan_list_id'), []);
  assert.equal(url.searchParams.getAll('direct_target').length, 1);

  function playablePlayer() {
    const value = player();
    const events = [];
    value.addDirectListenTarget(target, 'Dispatch');
    value.ensureAudioContext = () => { events.push('context'); };
    value.audioContext = { state: 'suspended', resume() {
      events.push('resume');
      return Promise.resolve().then(() => { this.state = 'running'; });
    }, async suspend() { events.push('suspend'); this.state = 'suspended'; } };
    value.ensureConnected = () => { events.push('feed'); value.feedActive = true; return true; };
    value.render = () => { if (!value.stopped && !value.paused) events.push('playing-observers'); };
    value.playNext = () => { events.push('next'); };
    value.startProgress = () => { events.push('progress'); };
    value.setStatus = status => { value.statusValue = status; };
    return { value, events };
  }
  const starting = playablePlayer();
  starting.value.avoids.set('other-target', { key: 'other-target' });
  const start = starting.value.startDirectListen();
  assert.deepEqual(starting.events, ['context', 'resume'],
    'AudioContext.resume starts synchronously in the headphone gesture before awaiting or fetching.');
  assert.equal(await start, true);
  assert.equal(starting.value.stopped, false);
  assert.equal(starting.value.paused, false);
  assert.deepEqual([...starting.value.selectedScanListIds], ['1']);
  assert.equal(starting.value.avoids.has('other-target'), true, 'Starting leaves Avoid choices intact.');
  assert.ok(starting.events.indexOf('playing-observers') < starting.events.indexOf('next'),
    'Shared playback observers can stop recordings before queued audio begins.');

  const resuming = playablePlayer();
  resuming.value.stopped = false; resuming.value.paused = true;
  const playingCall = { _callId: 'paused-current' }, playingSource = {};
  resuming.value.current = playingCall;
  resuming.value.currentBuffer = { duration: 3 };
  resuming.value.source = playingSource;
  assert.equal(await resuming.value.startDirectListen(), true);
  assert.equal(resuming.value.paused, false);
  assert.equal(resuming.value.current, playingCall);
  assert.equal(resuming.value.source, playingSource, 'Resuming continues the same call without restarting its source.');
  assert.ok(resuming.events.includes('progress'));
  assert.equal(resuming.events.includes('next'), false);
  const resumes = resuming.events.filter(event => event === 'resume').length;
  const transport = resuming.value.transportToken;
  assert.equal(await resuming.value.startDirectListen(), true);
  assert.equal(resuming.events.filter(event => event === 'resume').length, resumes,
    'An already playing session does not repeat the audio transition.');
  assert.equal(resuming.value.transportToken, transport);

  const blockedAudio = playablePlayer();
  blockedAudio.value.audioContext.resume = () => Promise.reject(new Error('browser policy'));
  await assert.rejects(blockedAudio.value.startDirectListen(), /browser sound settings/);
  assert.equal(blockedAudio.value.stopped, true);
  assert.equal(blockedAudio.value.paused, false);
  assert.equal(blockedAudio.events.includes('feed'), false, 'A failed audio transition cannot arm new playback.');

  const permissionRace = playablePlayer();
  permissionRace.value.stopped = false; permissionRace.value.paused = true;
  let finishResume;
  permissionRace.value.audioContext.resume = () => new Promise(done => { finishResume = done; });
  const pendingStart = permissionRace.value.startDirectListen();
  permissionRace.value.directListenAllowed = false;
  finishResume();
  assert.equal(await pendingStart, false);
  assert.equal(permissionRace.value.paused, true);
  assert.ok(permissionRace.events.includes('suspend'), 'Revocation restores the original suspended audio state.');
  assert.equal(permissionRace.events.includes('feed'), false);

  const stale = player();
  stale.selectedScanListIds.clear();
  stale.addDirectListenTarget(target);
  stale.stopped = false; stale.feedActive = true; stale.feedGeneration = 4;
  let resolve;
  stale.requestFeed = () => new Promise(done => { resolve = done; });
  stale.scheduleFeedPoll = () => { stale.pollScheduled = true; };
  stale.setStatus = () => {};
  stale.synchronizeSubscription = WebCallPlayer.prototype.synchronizeSubscription;
  const pending = stale.pollFeed(4);
  stale.removeDirectListenTarget(added.key);
  resolve({ cursor: '10', reset: false, calls: [call('stale')] });
  await pending;
  assert.equal(stale.queuedCount, 0, 'A response from the old selection cannot add stale calls.');
  assert.equal(stale.pollScheduled, undefined);
}

main().catch(error => { console.error(error); process.exitCode = 1; });
