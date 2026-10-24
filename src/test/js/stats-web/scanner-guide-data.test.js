'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function checkScannerGuideData(assetsRoot) {
  const assets = path.resolve(assetsRoot || process.argv[2] || 'stats-web/assets');
  const moduleAt = name => import(pathToFileURL(path.join(assets, name)).href);
  const [{ createScannerGuideData }, { href }, schema] = await Promise.all([
    moduleAt('guides/scanner-presentation.js'), moduleAt('core/entity-ref.js'), moduleAt('core/preference-schema.js')
  ]);
  const originals = { now: Date.now, fetch: global.fetch, Audio: global.Audio,
    AudioContext: global.AudioContext, window: global.window, document: global.document,
    setTimeout: global.setTimeout, setInterval: global.setInterval, requestAnimationFrame: global.requestAnimationFrame };
  const owned = [];
  let now = Date.UTC(2026, 9, 24, 15, 32), externalEffects = 0;
  const forbidden = () => { externalEffects += 1; throw new Error('Scanner examples must remain offline and silent.'); };
  Date.now = () => now;
  global.fetch = forbidden;
  global.Audio = forbidden;
  global.AudioContext = forbidden;
  global.setTimeout = forbidden;
  global.setInterval = forbidden;
  global.requestAnimationFrame = forbidden;
  global.window = new Proxy({}, { get: forbidden, set: forbidden });
  global.document = new Proxy({}, { get: forbidden, set: forbidden });
  const create = options => { const data = createScannerGuideData(options); owned.push(data); return data; };
  const flush = async () => { for (let index = 0; index < 6; index++) await Promise.resolve(); };
  const validReference = reference => assert.ok(href(reference), `Production navigation accepts ${JSON.stringify(reference)}`);
  try {
    const controller = new AbortController(), sample = create({ signal: controller.signal, section: 'call' });
    const initial = sample.player.viewState();
    assert.equal(initial.current.call_id, sample.selectedCallId);
    assert.equal(initial.current.target_alias, 'Sheriff Dispatch');
    assert.equal(initial.current.source_alias, 'Sheriff 214');
    assert.equal(initial.current.source_id, 214);
    assert.equal(initial.current.target_id, 1001);
    assert.equal(initial.currentReady, true);
    assert.equal(initial.playing, true);
    assert.equal(initial.stopped, false);
    assert.equal(initial.lastCallReady, true);
    assert.equal(initial.holdTarget, null);
    assert.deepEqual(initial.avoids, []);
    assert.equal(initial.queuedCount, 4);
    assert.deepEqual(initial.queue.map(call => call.target_alias), ['Regional Fire', 'Regional Fire', 'Police Department', 'City Fire']);
    assert.deepEqual(initial.queue.map(call => call.source_alias), ['Engine 7', 'Engine 8', 'Patrol 12', 'Engine 2']);
    const arrivalOrder = [...initial.queue].sort((a, b) => a._arrivalSequence - b._arrivalSequence);
    assert.deepEqual(arrivalOrder.map(call => call.source_alias), ['Engine 7', 'Patrol 12', 'Engine 8', 'Engine 2']);
    assert.notDeepEqual(initial.queue.map(call => call.call_id), arrivalOrder.map(call => call.call_id),
      'The actual queue visibly groups a later follow-up ahead of another waiting target.');
    assert.deepEqual(initial.scanLists.map(list => list.name), ['Law Enforcement', 'Fire/EMS']);
    assert.ok(initial.scanLists.every(list => list.selected && list.enabled));
    assert.equal(initial.targetGrouping, true);
    assert.equal(initial.targetBurstLimit, 4);
    for (const call of [initial.current, ...initial.queue]) {
      assert.ok(call.started_at_ms < call.completed_at_ms && call.completed_at_ms < now);
      assert.equal(call.completed_at_ms - call.started_at_ms, call.duration_ms);
      assert.equal(call._playbackTargetKey, call.playback_target.key);
      assert.equal(call.playback_target.radio_system_key, call.radio_system_key);
      assert.ok(call._matchedScanListIds.every(id => initial.scanLists.some(list => list.id === id)));
      for (const ref of [call.entity_ref, call.channel_entity_ref, call.radio_system_entity_ref,
        call.source_entity_ref, call.target_entity_ref]) validReference(ref);
      const channel = await sample.channelMetadata(call.configuration_id);
      assert.equal(channel.radio_system_key, call.radio_system_key);
      assert.equal(channel.channel_kind, 'TRUNKED');
      validReference(channel.entity_ref);
      assert.ok(sample.player.targetLabel(call).includes(call.target_alias));
      assert.ok(sample.player.callDetails(call).includes(call.system_name));
      assert.ok(sample.player.callDetails(call).includes(call.channel));
    }
    assert.equal(initial.current.vc_decoded_frames / (initial.current.vc_decoded_frames +
      initial.current.vc_repeated_frames + initial.current.vc_concealed_frames + initial.current.vc_missing_frames) * 100,
    initial.current.vc_quality_pct);
    initial.current.source_alias = 'Changed only in this response';
    initial.queue.length = 0;
    assert.equal(sample.player.viewState().current.source_alias, 'Sheriff 214');
    assert.equal(sample.player.viewState().queue.length, 4);
    const metadata = await sample.channelMetadata(sample.selectedConfigurationId);
    metadata.name = 'Changed only in this response';
    assert.equal((await sample.channelMetadata(sample.selectedConfigurationId)).name, 'North Site');

    const updates = [];
    const unsubscribe = sample.player.subscribeState(state => { updates.push(state); });
    assert.equal(updates.length, 1);
    assert.equal(sample.stats().subscribers, 1);
    updates[0].current.target_alias = 'Observer copy';
    assert.equal(sample.player.viewState().current.target_alias, 'Sheriff Dispatch');
    const levels = new Float32Array(64);
    assert.equal(sample.player.readAudioSpectrum(levels, 8000), true);
    assert.ok(levels.every(value => Number.isFinite(value) && value >= 0 && value <= 1));
    assert.ok(levels.some(value => value > 0.2));
    const before = [...levels];
    now += 175;
    sample.player.readAudioSpectrum(levels, 8000);
    assert.notDeepEqual([...levels], before, 'The actual spectrum renderer receives changing offline samples.');
    assert.equal(sample.player.viewState().current.call_id, sample.selectedCallId,
      'The example call remains available while the operator reads.');

    await sample.player.togglePause();
    assert.equal(sample.player.viewState().paused, true);
    assert.equal(sample.player.viewState().playing, false);
    assert.match(sample.player.viewState().status, /Paused/);
    assert.equal(sample.player.viewState().queuedCount, 4);
    assert.equal(sample.player.readAudioSpectrum(levels), false);
    assert.ok(levels.every(value => value === 0));
    await sample.player.togglePause();
    assert.equal(sample.player.viewState().playing, true);
    assert.equal(sample.player.viewState().queuedCount, 4);
    await sample.player.togglePlayback();
    assert.equal(sample.player.viewState().stopped, true);
    assert.equal(sample.player.viewState().current, null);
    assert.equal(sample.player.viewState().queuedCount, 0);
    assert.equal(sample.player.viewState().lastCallReady, true, 'Stop retains the previous finished call for Replay.');
    assert.equal(await sample.player.replayLastCall(), true);
    assert.equal(sample.player.viewState().replayingLast, true);
    assert.equal(sample.player.viewState().current.call_id, sample.previousCallId);
    assert.equal(sample.player.viewState().current.target_alias, 'Police Department');
    assert.notEqual(sample.previousCallId, sample.selectedCallId);
    const replayState = sample.player.viewState();
    sample.player.toggleHold(); sample.player.avoidCurrent();
    assert.equal(sample.player.viewState().holdTarget, replayState.holdTarget);
    assert.deepEqual(sample.player.viewState().avoids, replayState.avoids,
      'The production player suppresses Hold/Avoid while replaying the prior call.');

    const hold = create();
    hold.player.toggleHold();
    assert.equal(hold.player.viewState().holdTarget, hold.selectedTargetKey);
    assert.equal(hold.player.viewState().queuedCount, 0,
      'The production Hold action drops calls for other playback targets.');
    hold.player.skip();
    assert.equal(hold.player.viewState().current, null);
    assert.equal(hold.player.viewState().displayCall.target_alias, 'Sheriff Dispatch',
      'Held target details remain available while waiting for another matching call.');
    hold.player.toggleHold();
    assert.equal(hold.player.viewState().holdTarget, null);
    assert.equal(hold.player.viewState().displayCall, null);

    const avoid = create();
    avoid.player.avoidCurrent();
    const avoided = avoid.player.viewState();
    assert.equal(avoided.current.target_alias, 'Regional Fire');
    assert.equal(avoided.current.source_alias, 'Engine 7');
    assert.equal(avoided.queuedCount, 3);
    assert.deepEqual(avoided.queue.map(call => call.source_alias), ['Engine 8', 'Patrol 12', 'Engine 2']);
    assert.equal(avoided.avoids.length, 1);
    assert.equal(avoided.avoids[0].key, avoid.selectedTargetKey);
    assert.match(avoided.avoids[0].label, /Sheriff Dispatch/);
    assert.equal(avoided.avoids[0].system_scope, 'County P25');
    assert.equal(avoid.player.removeAvoid(avoid.selectedTargetKey), true);
    assert.deepEqual(avoid.player.viewState().avoids, []);
    assert.equal(avoid.player.viewState().current.call_id, avoided.current.call_id,
      'Remove restores future listening eligibility without replacing the current call.');
    assert.equal(avoid.player.removeAvoid(avoid.selectedTargetKey), false);
    const clear = create();
    clear.player.clearQueue();
    assert.equal(clear.player.viewState().queuedCount, 0);
    assert.equal(clear.player.viewState().current.call_id, clear.selectedCallId);
    const skip = create();
    skip.player.skip();
    assert.equal(skip.player.viewState().current.target_alias, 'Regional Fire');
    assert.equal(skip.player.viewState().queuedCount, 3);
    assert.equal(skip.player.viewState().queue[0].source_alias, 'Engine 8');
    await skip.updatePreferences(draft => { draft.playback.target_grouping = false; });
    assert.equal(skip.player.viewState().queue[0].source_alias, 'Patrol 12',
      'Disabling grouping restores the oldest waiting call first.');
    await skip.updatePreferences(draft => { draft.playback.target_grouping = true; draft.playback.target_burst_limit = 1; });
    assert.equal(skip.player.viewState().queue[0].source_alias, 'Patrol 12',
      'Reaching the burst limit gives another waiting target its turn.');
    const replay = create();
    await replay.player.replayLastCall();
    assert.equal(replay.player.viewState().current.call_id, replay.previousCallId);
    assert.equal(replay.player.viewState().queuedCount, 4);
    replay.player.skip();
    assert.equal(replay.player.viewState().current.target_alias, 'Regional Fire');
    const transport = create();
    await transport.player.togglePlayback();
    await transport.player.togglePlayback();
    assert.equal(transport.player.viewState().stopped, false);
    assert.equal(transport.player.viewState().queuedCount, 0);
    assert.equal(transport.player.viewState().status, 'Waiting');

    const settings = create(), snapshot = settings.readSnapshot();
    assert.equal(snapshot.identity, 'guide-scanner');
    assert.equal(snapshot.loaded, true);
    assert.deepEqual(schema.validate(snapshot.preferences), snapshot.preferences);
    snapshot.preferences.playback.target_burst_limit = 20;
    assert.equal(settings.readSnapshot().preferences.playback.target_burst_limit, 4);
    const saved = await settings.updatePreferences(draft => {
      draft.playback.target_grouping = false;
      draft.playback.target_burst_limit = 2;
      draft.page_titles.prepend_playing_call = true;
      draft.scanner.detail_mode = 'advanced';
    });
    assert.equal(saved.scanner.detail_mode, 'advanced');
    assert.equal(settings.readSnapshot().revision, 2);
    assert.equal(settings.player.viewState().targetGrouping, false);
    assert.equal(settings.player.viewState().targetBurstLimit, 2);
    saved.playback.target_burst_limit = 10;
    assert.equal(settings.readSnapshot().preferences.playback.target_burst_limit, 2);
    assert.equal(create().readSnapshot().preferences.playback.target_burst_limit, 4,
      'A later guide receives defaults instead of a previous sample’s settings.');
    settings.player.setVolume(0.35);
    await flush();
    assert.equal(settings.player.viewState().volume, 0.35);
    assert.equal(settings.readSnapshot().preferences.playback.volume, 0.35);
    settings.player.setScanListSelected('9502', false);
    await flush();
    assert.deepEqual(settings.player.viewState().scanLists.filter(list => list.selected).map(list => list.name), ['Law Enforcement']);
    assert.deepEqual(settings.player.viewState().queue.map(call => call.target_alias), ['Police Department']);
    assert.deepEqual(settings.readSnapshot().preferences.playback.selected_scan_list_ids, [9501]);
    for (const change of [
      draft => { draft.appearance.theme = 'dark'; }, draft => { draft.playback.target_burst_limit = 0; },
      draft => { draft.playback.volume = 3; }, draft => { draft.playback.selected_scan_list_ids = [1]; },
      draft => { draft.scanner.detail_mode = 'invented'; }
    ]) await assert.rejects(settings.updatePreferences(change), /Unknown|invalid|must/i);
    await assert.rejects(settings.updatePreferences({}), /Unknown/);
    await assert.rejects(settings.channelMetadata('actual-receiver-channel'), /Unknown/);
    assert.throws(() => settings.player.targetLabel({ call_id: 'actual-call' }), /Unknown/);
    assert.throws(() => settings.player.removeAvoid('actual-target'), /Unknown/);
    assert.throws(() => settings.player.setScanListSelected(1, true), /Unknown/);
    assert.throws(() => settings.player.setScanListSelected('9501', 'true'), /Unknown/);
    assert.throws(() => settings.player.readAudioSpectrum(new Float32Array(513)), /Unknown/);
    for (const method of ['connect', 'disconnect', 'enqueue', 'loadCurrent', 'ensureAudioContext', 'pollFeed'])
      assert.equal(settings.player[method], undefined, 'Receiver and audio operations are excluded from the sample facade.');

    unsubscribe(); unsubscribe();
    assert.equal(sample.stats().subscribers, 0);
    const beforeDispose = updates.length;
    controller.abort(); sample.dispose(); sample.dispose();
    assert.equal(sample.stats().disposed, true);
    assert.equal(sample.stats().subscribers, 0);
    assert.equal(sample.stats().timers, 0);
    assert.equal(sample.stats().connections, 0);
    assert.equal(sample.stats().audioContexts, 0);
    assert.equal(sample.player.readAudioSpectrum(levels), false);
    assert.equal(updates.length, beforeDispose);
    assert.throws(() => sample.player.viewState(), { name: 'AbortError' });
    assert.throws(() => sample.player.toggleHold(), { name: 'AbortError' });
    await assert.rejects(sample.channelMetadata(sample.selectedConfigurationId), { name: 'AbortError' });
    await assert.rejects(sample.updatePreferences(draft => { draft.scanner.detail_mode = 'engineer'; }), { name: 'AbortError' });
    const pendingController = new AbortController(), pending = create({ signal: pendingController.signal });
    const read = pending.channelMetadata(pending.selectedConfigurationId);
    const pausing = pending.player.togglePause();
    let mutationRan = false;
    const mutation = pending.updatePreferences(draft => { mutationRan = true; draft.playback.target_burst_limit = 9; });
    pendingController.abort();
    await assert.rejects(read, { name: 'AbortError' });
    await assert.rejects(pausing, { name: 'AbortError' });
    await assert.rejects(mutation, { name: 'AbortError' });
    assert.equal(mutationRan, false, 'An aborted queued settings action never changes the example.');
    const closedSignal = new AbortController();
    closedSignal.abort();
    const closed = create({ signal: closedSignal.signal });
    assert.equal(closed.stats().disposed, true);
    assert.throws(() => closed.player.viewState(), { name: 'AbortError' });
    assert.equal(externalEffects, 0);
    console.log('Scanner guide production playback actions, queue, identities, local settings, silence and lifecycle checks passed.');
  } finally {
    owned.forEach(data => data.dispose());
    Date.now = originals.now;
    for (const key of ['fetch', 'Audio', 'AudioContext', 'window', 'document',
      'setTimeout', 'setInterval', 'requestAnimationFrame']) {
      if (originals[key] === undefined) delete global[key];
      else global[key] = originals[key];
    }
  }
}

module.exports = { checkScannerGuideData };
if (require.main === module) checkScannerGuideData().catch(error => { console.error(error); process.exitCode = 1; });
