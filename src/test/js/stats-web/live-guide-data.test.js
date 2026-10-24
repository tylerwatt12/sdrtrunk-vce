'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { pathToFileURL } = require('node:url');

async function checkLiveGuideData(assetsRoot) {
  const assets = path.resolve(assetsRoot || process.argv[2] || 'stats-web/assets');
  const { createLiveGuideData } = await import(pathToFileURL(path.join(assets, 'guides/live-presentation.js')).href);
  const app = fs.readFileSync(path.join(assets, 'app.js'), 'utf8');
  const production = vm.createContext({ Float32Array, DataView, Math });
  const diagnosticFunctions = app.slice(app.indexOf('function diagnosticValueFormat('),
    app.indexOf('function diagnosticStrengthHistory('));
  vm.runInContext(`const LIVE_DETAIL_SELECTION_KINDS = { CONTROL: 'CONTROL', EXACT: 'EXACT', CONVENTIONAL: 'CONVENTIONAL' };
    ${app.slice(app.indexOf('function liveMessageSourceMatchesSelection('), app.indexOf('function liveDetailCapturedRowKey('))}
    ${app.slice(app.indexOf('function liveChannelStateMatchesSelection('), app.indexOf('function liveEventDuration('))}
    ${diagnosticFunctions}
    this.contracts = { liveMessageSourceMatchesSelection, liveEventScopeMatchesSelection,
      liveEventMatchesSelection, liveMessageMatchesSelection, liveChannelStateMatchesSelection, diagnosticFloatPayload };`, production);
  const contracts = production.contracts;
  const native = { now: Date.now, setInterval: global.setInterval, clearInterval: global.clearInterval,
    fetch: global.fetch };
  const timers = new Map(), owned = [];
  let now = 1800000000000, nextTimer = 0, networkCalls = 0;
  Date.now = () => now;
  global.setInterval = callback => { timers.set(++nextTimer, callback); return nextTimer; };
  global.clearInterval = timer => timers.delete(timer);
  global.fetch = () => { networkCalls += 1; throw new Error('Examples must remain offline.'); };
  const advance = milliseconds => {
    now += milliseconds;
    [...timers.values()].forEach(callback => callback());
  };
  const create = options => {
    const sample = createLiveGuideData(options);
    owned.push(sample);
    return sample;
  };
  const collect = (sample, topic, parameters) => {
    const source = sample.liveConnection(topic, parameters), boundaries = [], rows = [];
    source.addEventListener('source_change', event => boundaries.push(JSON.parse(event.data)));
    source.addEventListener(topic === 'decode_events' ? 'decode_event' : 'decode_message',
      event => rows.push(JSON.parse(event.data)));
    return { source, boundaries, rows };
  };
  try {
    const controller = new AbortController(), sample = create({ signal: controller.signal, section: 'diagnostics' });
    const snapshots = [], batches = [];
    let opens = 0;
    const feed = sample.activityFeed({ snapshot: value => snapshots.push(value),
      activityBatch: value => batches.push(value), open: () => opens++ });
    const parameters = { subscription_id: 'test-current', configuration_id: sample.selectedConfigurationId,
      frequency_hz: sample.selectedFrequencyHz };
    const selection = { kind: 'EXACT', configurationId: parameters.configuration_id,
      bindingFrequencyHz: parameters.frequency_hz, bindingTimeslot: null };
    const events = collect(sample, 'decode_events', parameters);
    const messages = collect(sample, 'decode_messages', parameters);
    const frames = [];
    let diagnosticOpens = 0;
    const diagnostic = sample.connectionFactory('channel_diagnostics', { ...parameters, strength_encoding: 'packed6' }, {
      onOpen: () => diagnosticOpens++, onFrame: frame => frames.push(frame)
    });
    await Promise.resolve();
    assert.equal(opens, 1);
    assert.equal(diagnosticOpens, 1);
    assert.equal(snapshots.length, 1);
    assert.deepEqual(snapshots[0].tables.map(table => table.title), ['County P25', 'City P25', 'Conventional']);
    const county = snapshots[0].tables.find(table => table.table_id === sample.selectedTableId);
    assert.equal(county.configuration_id, parameters.configuration_id);
    assert.equal(county.control_active, true);
    const control = county.rows.find(row => row.role === 'CURRENT_CONTROL');
    assert.equal(control.decode_health_pct, 99.2);
    assert.equal(control.quality_observed_at_ms, now);
    const sheriff = county.rows.find(row => row.key === sample.selectedRowKey);
    assert.equal(sheriff.status, 'CALL');
    assert.equal(sheriff.source_alias, 'Sheriff 214');
    assert.equal(sheriff.target_alias, 'Sheriff Dispatch');
    assert.equal(sheriff.frequency_hz, parameters.frequency_hz);
    assert.ok(sheriff.vc_decoded_frames + sheriff.vc_repeated_frames + sheriff.vc_concealed_frames >= 50,
      'The real production warmup gate already has enough voice frames to show VC quality.');
    assert.ok(sheriff.activation_order > 0, 'The selected example remains available with active-only presentation.');
    snapshots[0].tables[0].rows[1].source_alias = 'Changed only in one delivery';
    const fireStates = new Set();
    for (let index = 0; index < 20; index++) {
      advance(900);
      const latest = batches.at(-1), current = latest.changes.find(change => change.table_id === sample.selectedTableId).table;
      assert.equal(latest.base_revision + 1, latest.revision);
      assert.equal(current.rows.find(row => row.key === sample.selectedRowKey).source_alias, 'Sheriff 214');
      assert.equal(current.rows.find(row => row.key === sample.selectedRowKey).status, 'CALL');
      assert.equal(current.rows[0].quality_observed_at_ms, now);
      fireStates.add(current.rows.find(row => row.key === 'guide-live-regional-fire').status);
    }
    assert.deepEqual([...fireStates].sort(), ['CALL', 'IDLE']);
    assert.equal(snapshots.length, 1, 'Subsequent activity uses the real incremental callback contract.');
    assert.ok(batches.length >= 20);
    for (const value of [events, messages]) {
      assert.equal(value.boundaries[0].subscription_id, parameters.subscription_id);
      assert.equal(value.boundaries[0].configuration_id, parameters.configuration_id);
      assert.equal(value.boundaries[0].frequency_hz, parameters.frequency_hz);
      assert.ok(value.boundaries[0].filter_catalog.groups.length);
      assert.ok(value.rows.length > 8);
      assert.ok(value.rows.every(row => row.configuration_id === parameters.configuration_id &&
        row.frequency_hz === parameters.frequency_hz));
    }
    assert.equal(contracts.liveEventScopeMatchesSelection(selection, parameters.subscription_id, events.boundaries[0]), true);
    assert.equal(contracts.liveMessageSourceMatchesSelection(selection, parameters.subscription_id, messages.boundaries[0]), true);
    assert.equal(contracts.liveEventScopeMatchesSelection(selection, 'stale-subscription', events.boundaries[0]), false);
    assert.equal(contracts.liveMessageSourceMatchesSelection(selection, 'stale-subscription', messages.boundaries[0]), false);
    assert.ok(events.rows.every(row => contracts.liveEventMatchesSelection(selection, row)));
    assert.ok(messages.rows.every(row => contracts.liveMessageMatchesSelection(selection, row)));
    assert.equal(events.rows[0].from_aliases, 'Sheriff 214');
    assert.equal(events.rows[0].to_aliases, 'Sheriff Dispatch');
    assert.ok(messages.rows.some(row => /LDU1/.test(row.text)));
    assert.ok(messages.rows.some(row => /LDU2/.test(row.text)));
    assert.ok(messages.rows.every(row => row.valid));

    const state = JSON.parse(new TextDecoder().decode(frames[0].payload));
    assert.equal(frames[0].type, 1);
    assert.equal(contracts.liveChannelStateMatchesSelection(selection, parameters.subscription_id, state), true);
    assert.equal(contracts.liveChannelStateMatchesSelection(selection, 'stale-subscription', state), false);
    assert.equal(state.signal_state, 'live');
    assert.equal(state.symbols_state, 'live');
    assert.match(state.decoder_profile, /P25/);
    const fft = frames.find(frame => frame.type === 2), symbols = frames.find(frame => frame.type === 3);
    assert.equal(fft.valueCount, 512);
    assert.equal(symbols.valueCount, 480);
    assert.equal(fft.strengthBits, 32);
    assert.equal(fft.payload.byteLength, fft.valueCount * 4);
    assert.ok(frames.filter(frame => frame.type === 3).length >= 12,
      'The initial symbol history fills the production 4,800-symbol plot before the lesson opens.');
    const values = contracts.diagnosticFloatPayload(fft), decodedSymbols = contracts.diagnosticFloatPayload(symbols);
    assert.ok(values.every(Number.isFinite));
    assert.ok(values[256] > -65 && values[0] < -100, 'Real diagnostic decoding produces a visible channel inside the noise floor.');
    assert.ok(decodedSymbols.every(value => Number.isFinite(value) && Math.abs(value) < Math.PI));
    for (const level of [-3 * Math.PI / 4, -Math.PI / 4, Math.PI / 4, 3 * Math.PI / 4]) {
      assert.ok(decodedSymbols.some(value => Math.abs(value - level) < 0.06), 'All four C4FM symbol levels are visible.');
    }
    assert.ok(frames.every(frame => frame.generation === 1));
    assert.ok(frames.slice(1).every((frame, index) => frame.sequence > frames[index].sequence));
    const initialFrameCount = frames.length;
    diagnostic.update({ ...parameters, subscription_id: 'test-next', frequency_hz: 857162500 });
    assert.equal(frames[initialFrameCount].type, 1);
    assert.equal(frames[initialFrameCount].generation, 2);
    assert.equal(JSON.parse(new TextDecoder().decode(frames[initialFrameCount].payload)).frequency_hz, 857162500);
    assert.equal(frames.at(-1).centerFrequencyHz, 857162500);

    const controlParameters = { subscription_id: 'test-control', configuration_id: parameters.configuration_id };
    assert.equal(events.source.update(controlParameters), true);
    const controlSelection = { kind: 'CONTROL', configurationId: parameters.configuration_id, bindingFrequencyHz: 856662500 };
    assert.equal(contracts.liveEventScopeMatchesSelection(controlSelection, 'test-control', events.boundaries.at(-1)), true);
    assert.ok(events.rows.at(-1).frequency_hz === 856662500);
    const conventional = collect(sample, 'decode_messages', { scope: 'conventional', subscription_id: 'test-conventional' });
    await Promise.resolve();
    assert.equal(contracts.liveMessageSourceMatchesSelection({ kind: 'CONVENTIONAL' }, 'test-conventional', conventional.boundaries[0]), true);
    assert.ok(conventional.rows.every(row => row.channel_name === 'County Fire Dispatch' && row.frequency_hz === 154430000));

    for (const topic of ['channel_activity', 'channel_audio', 'tuner_diagnostics', 'actual_receiver']) {
      assert.throws(() => sample.liveConnection(topic, parameters), /Unsupported/);
      assert.throws(() => sample.connectionFactory(topic, parameters), /Unsupported/);
    }
    for (const invalid of [
      {}, { ...parameters, subscription_id: '' }, { ...parameters, configuration_id: 'actual-receiver' },
      { ...parameters, frequency_hz: 1 }, { ...parameters, frequency_hz: parameters.frequency_hz + 0.5 },
      { ...parameters, timeslot: 0 }, { ...parameters, timeslot: 1 }, { ...parameters, timeslot: 2 }, { ...parameters, scope: 'conventional' },
      { ...parameters, takeover: true }, { ...parameters, strength_encoding: 'pcm' }
    ]) {
      assert.throws(() => sample.liveConnection('decode_messages', invalid), /Unknown/);
      assert.throws(() => diagnostic.update(invalid), /Unknown/);
    }
    assert.throws(() => sample.connectionFactory('channel_diagnostics', { scope: 'conventional', subscription_id: 'no-diagnostics' }), /Unknown/);
    assert.throws(() => events.source.update({ ...controlParameters, timeslot: 1 }), /Unknown/);
    const liveBeforeAbort = { snapshots: snapshots.length, batches: batches.length,
      messages: messages.rows.length, events: events.rows.length, frames: frames.length };
    controller.abort();
    await diagnostic.whenClosed();
    sample.dispose(); feed.close(); conventional.source.close();
    assert.equal(sample.stats().disposed, true);
    assert.equal(sample.stats().timers, 0);
    assert.equal(sample.stats().activityFeeds, 0);
    assert.equal(sample.stats().liveConnections, 0);
    assert.equal(sample.stats().diagnosticConnections, 0);
    advance(180000);
    assert.deepEqual({ snapshots: snapshots.length, batches: batches.length,
      messages: messages.rows.length, events: events.rows.length, frames: frames.length }, liveBeforeAbort);
    assert.equal(events.source.update(parameters), false);
    assert.equal(diagnostic.update(parameters), false);
    assert.throws(() => sample.activityFeed({}), { name: 'AbortError' });

    const pending = create(), pendingCalls = [];
    const pendingFeed = pending.activityFeed({ open: () => pendingCalls.push('open'), snapshot: () => pendingCalls.push('snapshot') });
    const pendingEvents = pending.liveConnection('decode_events', parameters);
    pendingEvents.addEventListener('source_change', () => pendingCalls.push('boundary'));
    const pendingDiagnostic = pending.connectionFactory('channel_diagnostics', parameters, {
      onOpen: () => pendingCalls.push('diagnostic-open'), onFrame: () => pendingCalls.push('frame')
    });
    pendingFeed.close(); pendingEvents.close(); pendingDiagnostic.close(); pending.dispose();
    await Promise.resolve();
    await pendingDiagnostic.whenClosed();
    assert.deepEqual(pendingCalls, [], 'Closing during preparation prevents queued delivery and timer creation.');
    assert.equal(pending.stats().timers, 0);
    const beforeOpen = create();
    let closedDuringOpen;
    closedDuringOpen = beforeOpen.activityFeed({ open: () => closedDuringOpen.close() });
    await Promise.resolve();
    assert.equal(beforeOpen.stats().timers, 0, 'Closing inside open cannot start a late activity timer.');
    const closeOnOpen = create();
    let openDiagnostic, openLive;
    openDiagnostic = closeOnOpen.connectionFactory('channel_diagnostics', parameters, {
      onOpen: () => openDiagnostic.close(), onFrame: () => pendingCalls.push('late-frame')
    });
    openLive = closeOnOpen.liveConnection('decode_events', parameters);
    openLive.onopen = () => openLive.close();
    openLive.addEventListener('decode_event', () => pendingCalls.push('late-event'));
    await Promise.resolve();
    await openDiagnostic.whenClosed();
    assert.deepEqual(pendingCalls, []);
    assert.equal(closeOnOpen.stats().timers, 0, 'Closing any transport inside its open callback cannot create a late timer.');
    const alreadyClosed = new AbortController();
    alreadyClosed.abort();
    const preAborted = create({ signal: alreadyClosed.signal });
    assert.equal(preAborted.stats().disposed, true);
    assert.throws(() => preAborted.liveConnection('decode_events', parameters), { name: 'AbortError' });
    assert.equal(networkCalls, 0);
  } finally {
    owned.forEach(sample => sample.dispose());
    Date.now = native.now;
    global.setInterval = native.setInterval;
    global.clearInterval = native.clearInterval;
    global.fetch = native.fetch;
    assert.equal(timers.size, 0, 'Every example-owned timer is released.');
  }
  console.log('Live guide data: isolated activity, production scope matching, real decoded plots, and cleanup passed.');
}

module.exports = { checkLiveGuideData };
if (require.main === module) checkLiveGuideData().catch(error => { console.error(error); process.exitCode = 1; });
