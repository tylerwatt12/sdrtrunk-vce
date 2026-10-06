'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { gzipSync } = require('node:zlib');
const { test } = require('node:test');

const source = fs.readFileSync(process.argv[2] || path.resolve(__dirname,
  '../../../../stats-web/assets/app.js'), 'utf8');

function harness() {
  const events = [];
  const tables = new Map();
  let subscriptionId = 0;
  const context = {
    Uint8Array, DataView, TextDecoder, Blob, DecompressionStream, AbortController, queueMicrotask,
    LIVE_MULTIPLEX_HEADER_BYTES: 16, LIVE_MULTIPLEX_MAGIC: 0x534c4d58,
    LIVE_MULTIPLEX_VERSION: 2, LIVE_MULTIPLEX_MAXIMUM_BYTES: 16 * 1024 * 1024,
    LIVE_MULTIPLEX_READY_TIMEOUT_MS: 10_000, LIVE_MULTIPLEX_LIVENESS_TIMEOUT_MS: 25_000,
    LIVE_MULTIPLEX_DECODER: new TextDecoder(),
    LIVE_MULTIPLEX_TOPICS: { 0: 'control', 1: 'channel_activity', 5: 'tuner_diagnostics' },
    window: { setTimeout, clearTimeout, setInterval, clearInterval },
    invokeLiveSubscriber: (target, method, ...args) => target[method]?.(...args),
    invokeLiveListener: (callback, ...args) => callback(...args),
    snakeCasePayload: (value) => value,
    randomLiveClientId: () => `00000000-0000-4000-8000-${String(++subscriptionId).padStart(12, '0')}`,
    decodeDiagnosticFrame: (bytes) => ({ bytes }),
    liveChannelActivityTables: tables, liveChannelActivityRevision: 0,
    liveChannelActivityNeedsResync: false, liveChannelActivitySubscriptionId: null,
    liveChannelActivitySubscribers: new Set([{ snapshot: (data) => events.push(data) }]),
    liveMultiplexer: { restart() {} }
  };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf('async function inflateLiveMultiplexPayload'),
    source.indexOf('const liveMultiplexer = new LiveMultiplexer()')), context);
  vm.runInContext(source.slice(source.indexOf('function applyLiveChannelActivitySnapshot'),
    source.indexOf('function synchronizeLiveChannelActivitySource')), context);
  const mux = vm.runInContext('new LiveMultiplexer()', context);
  mux.subscribers.set('channel_activity', new Set([{ onEvent: (name, data) => events.push({ name, data }) }]));
  mux.subscribers.set('tuner_diagnostics', new Set([{ onFrame: (frame) => events.push(frame) }]));
  return { context, mux, events, tables };
}

async function flushMicrotasks() {
  for (let count = 0; count < 20; count += 1) await Promise.resolve();
}

function controlHarness() {
  const h = harness();
  const timers = new Map();
  let timerId = 0;
  let now = 0;
  Object.assign(h.context.window, {
    setTimeout(callback, delay) {
      const id = ++timerId;
      timers.set(id, { callback, at: now + delay, delay });
      return id;
    },
    clearTimeout(id) { timers.delete(id); }
  });
  h.mux.subscribers.clear();
  h.mux.ready = true;
  h.mux.clientId = 'control-fixture';
  h.mux.controller = new AbortController();
  h.mux.ensureConnected = () => {};
  Object.assign(h.context, {
    liveMultiplexer: h.mux,
    document: { hidden: false, addEventListener() {}, removeEventListener() {} },
    liveChannelActivitySource: null, liveChannelActivityState: 'connecting',
    liveChannelActivitySubscribers: new Set(), liveConnections: new Set(), pageConnections: new Set()
  });
  vm.runInContext(source.slice(source.indexOf('function liveConnection('),
    source.indexOf('let liveChannelActivitySource')), h.context);
  vm.runInContext(source.slice(source.indexOf('function synchronizeLiveChannelActivitySource'),
    source.indexOf('const DIAGNOSTIC_FRAME_MAGIC')), h.context);
  h.clock = {
    timers,
    async next() {
      const entry = [...timers.entries()].sort((left, right) => left[1].at - right[1].at)[0];
      if (!entry) return null;
      timers.delete(entry[0]);
      now = entry[1].at;
      entry[1].callback();
      await flushMicrotasks();
      return entry[1].delay;
    }
  };
  h.receive = (event, data, topic = 1) => h.mux.consume(envelope(topic, 1,
    Buffer.from(JSON.stringify({ event, data }))));
  return h;
}

function installRetainedActivityServer(h) {
  let active = null;
  const requests = [];
  h.context.requestJson = async (_path, options) => {
    requests.push(options.body);
    const wanted = JSON.stringify(options.body.subscriptions.channel_activity || null);
    // The server retains an existing topic when its complete parameters are unchanged.
    if (wanted !== active) {
      active = wanted;
      if (options.body.subscriptions.channel_activity) {
        await h.receive('snapshot', { revision: 10, tables: [{ table_id: 'site', title: 'North',
          rows: [{ key: 'one', source_alias: 'Dispatch', value: 10 }] }] });
      }
    }
    return {};
  };
  return requests;
}

function deferred() {
  let resolve, reject;
  const promise = new Promise((accept, fail) => { resolve = accept; reject = fail; });
  return { promise, resolve, reject };
}

function envelope(topic, kind, raw, compressed = false, inflatedBytes = raw.length) {
  const payload = compressed ? gzipSync(raw) : raw;
  const frame = new Uint8Array(16 + payload.length);
  const header = new DataView(frame.buffer);
  header.setUint32(0, 0x534c4d58);
  header.setUint8(4, 2);
  header.setUint8(5, kind | (compressed ? 0x80 : 0));
  header.setUint16(6, topic);
  header.setUint32(8, payload.length);
  header.setUint32(12, compressed ? inflatedBytes : 0);
  frame.set(payload, 16);
  return frame;
}

test('mixed compressed/plain frames survive arbitrary fragmentation and retain event order', async () => {
  const { mux, events } = harness();
  const frames = [
    envelope(1, 1, Buffer.from(JSON.stringify({ event: 'snapshot', data: { revision: 7 } })), true),
    envelope(5, 2, Uint8Array.of(4, 8, 12), true),
    envelope(1, 1, Buffer.from(JSON.stringify({ event: 'activity_delta', data: { revision: 8 } })))
  ];
  const bytes = Buffer.concat(frames);
  for (let offset = 0; offset < bytes.length; offset += 7) {
    await mux.consume(new Uint8Array(bytes.subarray(offset, offset + 7)));
  }
  assert.equal(events[0].name, 'snapshot');
  assert.equal(events[0].data.revision, 7);
  assert.deepEqual([...events[1].bytes], [4, 8, 12]);
  assert.equal(events[2].name, 'activity_delta');
  assert.equal(events[2].data.revision, 8);
  assert.equal(mux.pending.length, 0);
});

test('compressed expansion is bounded and declared size is checked', async () => {
  const { mux, events } = harness();
  const tooMuch = envelope(1, 1, Buffer.alloc(20_000, 65), true, 64);
  await assert.rejects(mux.consume(tooMuch), /invalid compressed size/);
  assert.equal(events.length, 0);
  mux.stop();
  const oversized = envelope(1, 1, Buffer.from('{}'), true, 16 * 1024 * 1024 + 1);
  await assert.rejects(mux.consume(oversized), /unsupported frame/);
});

test('reconnection during decompression cannot dispatch stale frames into the new connection', async () => {
  const { mux, events } = harness();
  const consuming = mux.consume(envelope(1, 1,
    Buffer.from(JSON.stringify({ event: 'snapshot', data: { revision: 99 } })), true));
  mux.stop();
  await consuming;
  assert.equal(events.length, 0);
  assert.equal(mux.pending.length, 0);
});

test('row deltas preserve unchanged aliases, update metadata, remove rows and honor explicit order', () => {
  const { context } = harness();
  const first = { key: 'first', source_alias: 'Engine 1', status: 'CALL' };
  const baseline = { table_id: 'site', title: 'North', rows: [first, { key: 'old' }] };
  const update = { table_id: 'site', operation: 'upsert', base_revision: 4, revision: 5,
    table: { title: 'North dispatch' }, rows: [{ key: 'second', status: 'IDLE' }],
    removed_row_keys: ['old'], row_order: ['second', 'first'] };
  const merged = context.mergeLiveChannelActivityDelta(update, baseline);
  assert.equal(merged.table.title, 'North dispatch');
  assert.deepEqual([...merged.table.rows].map((row) => row.key), ['second', 'first']);
  assert.equal(merged.table.rows[1], first);
  assert.equal(merged.table.rows[1].source_alias, 'Engine 1');
  assert.deepEqual(baseline.rows.map((row) => row.key), ['first', 'old']);
});

test('changed complete table metadata clears removed optional fields while row-only deltas retain metadata', () => {
  const { context } = harness();
  const row = { key: 'one', source_alias: 'Dispatch' };
  const baseline = { table_id: 'site', title: 'North', configuration_id: 'configuration',
    site: { nac: 123 }, radio_system_key: 'system', entity_ref: { kind: 'channel', key: 'channel' },
    remote_origin: { remote: true }, rows: [row] };
  const unchanged = context.mergeLiveChannelActivityDelta({ table_id: 'site', rows: [] }, baseline).table;
  assert.deepEqual(JSON.parse(JSON.stringify(unchanged)), baseline);
  const metadata = { table_id: 'site', title: 'North updated', rows_total: 1 };
  const cleared = context.mergeLiveChannelActivityDelta({ table_id: 'site', table: metadata,
    rows: [], removed_row_keys: [] }, baseline).table;
  assert.deepEqual(JSON.parse(JSON.stringify(cleared)), { ...metadata, rows: [row] });
  assert.equal(cleared.rows[0], row);
  assert.equal(baseline.site.nac, 123);
});

test('rebuilding Live after a preference save reacquires a baseline without interrupting another multiplex topic', async (t) => {
  const h = controlHarness();
  t.after(() => h.mux.stop());
  const requests = installRetainedActivityServer(h);
  const diagnostics = [];
  h.mux.subscribe('tuner_diagnostics', {}, { onFrame: (frame) => diagnostics.push(frame) });
  Object.assign(h.context, { pageObservers: new Map(), pageTimers: new Set() });
  vm.runInContext(source.slice(source.indexOf('function closePageConnections()'),
    source.indexOf("window.addEventListener('beforeunload'")), h.context);
  h.context.subscribeLiveChannelActivity();
  await flushMicrotasks();
  await h.clock.next();
  assert.equal(h.context.liveChannelActivityRevision, 10);
  const streamController = h.mux.controller;
  const attempt = h.mux.attempt;
  const rebuiltSnapshots = [];
  // Non-density preference saves call render(), which closes and rebuilds these connections in one turn.
  h.context.closePageConnections();
  h.context.subscribeLiveChannelActivity({ snapshot: (value) => rebuiltSnapshots.push(value) });
  await flushMicrotasks();
  await h.clock.next();
  assert.equal(rebuiltSnapshots.length, 1, 'a retained server topic must send a baseline to the rebuilt view');
  assert.deepEqual(JSON.parse(JSON.stringify(rebuiltSnapshots[0].tables)), [
    { table_id: 'site', title: 'North', rows: [{ key: 'one', source_alias: 'Dispatch', value: 10 }] }
  ]);
  assert.notEqual(requests[0].subscriptions.channel_activity.subscription_id,
    requests.at(-1).subscriptions.channel_activity.subscription_id);
  await h.receive('activity_delta', { table_id: 'site', operation: 'upsert', base_revision: 10,
    revision: 11, rows: [{ key: 'one', source_alias: 'Dispatch', value: 11 }], removed_row_keys: [] });
  assert.equal(h.tables.get('site').rows[0].value, 11);
  assert.equal(h.context.liveChannelActivityNeedsResync, false);
  await h.mux.consume(envelope(5, 2, Uint8Array.of(1, 2, 3)));
  assert.equal(diagnostics.length, 1);
  assert.equal(h.mux.attempt, attempt);
  assert.equal(h.mux.controller, streamController);
  assert.equal(streamController.signal.aborted, false);
});

test('activity reentry after accepted teardown gets a new baseline even before the server applies that teardown', async (t) => {
  const h = controlHarness();
  t.after(() => h.mux.stop());
  const requests = [];
  let active = null;
  h.context.requestJson = async (_path, options) => {
    requests.push(options.body);
    const wanted = options.body.subscriptions.channel_activity;
    // An empty desired topic is accepted immediately, but the stream owner has not applied it yet.
    if (wanted && JSON.stringify(wanted) !== active) {
      active = JSON.stringify(wanted);
      await h.receive('snapshot', { revision: 10, tables: [{ table_id: 'site', rows: [{ key: 'one' }] }] });
    }
    return {};
  };
  h.mux.subscribe('tuner_diagnostics');
  const first = h.context.subscribeLiveChannelActivity();
  await flushMicrotasks();
  await h.clock.next();
  const streamController = h.mux.controller;
  const firstId = requests[0].subscriptions.channel_activity.subscription_id;
  first.close();
  await h.clock.next();
  assert.equal(requests.at(-1).subscriptions.channel_activity, undefined);
  assert.equal(h.context.liveChannelActivitySource, null);
  assert.equal(h.context.liveChannelActivitySubscriptionId, null);
  assert.equal(h.tables.size, 0);
  const snapshots = [];
  h.context.subscribeLiveChannelActivity({ snapshot: (value) => snapshots.push(value) });
  await flushMicrotasks();
  await h.clock.next();
  assert.notEqual(requests.at(-1).subscriptions.channel_activity.subscription_id, firstId);
  assert.equal(snapshots.length, 1);
  assert.equal(h.tables.get('site').rows[0].key, 'one');
  assert.equal(h.context.liveChannelActivityNeedsResync, false);
  assert.equal(h.mux.controller, streamController);
  assert.equal(streamController.signal.aborted, false);
});

test('activity incarnation stays stable while sharing the same source and steady deltas require no new control', async (t) => {
  const h = controlHarness();
  t.after(() => h.mux.stop());
  const requests = installRetainedActivityServer(h);
  const first = h.context.subscribeLiveChannelActivity();
  await flushMicrotasks();
  await h.clock.next();
  const initialId = requests[0].subscriptions.channel_activity.subscription_id;
  const snapshots = [];
  const second = h.context.subscribeLiveChannelActivity({ snapshot: (value) => snapshots.push(value) });
  assert.equal(snapshots.length, 1);
  first.close();
  await h.receive('activity_delta', { table_id: 'site', operation: 'upsert', base_revision: 10,
    revision: 11, rows: [{ key: 'one', value: 11 }], removed_row_keys: [] });
  assert.equal(h.context.liveChannelActivitySubscriptionId, initialId);
  assert.equal(h.tables.get('site').rows[0].value, 11);
  assert.equal(requests.length, 1);
  assert.equal(h.clock.timers.size, 0);
  second.close();
  await h.clock.next();
  assert.equal(h.context.liveChannelActivitySource, null);
  assert.equal(h.context.liveChannelActivitySubscriptionId, null);
  assert.equal(h.tables.size, 0);
  assert.equal(h.mux.ready, false);
});

for (const failure of [Object.assign(new Error('Unavailable'), { status: 503 }),
  Object.assign(new Error('Timed out'), { code: 'request_timeout' })]) {
  test(`activity subscriptions recover exactly after ${failure.status || failure.code} despite healthy heartbeats`, async (t) => {
    const h = controlHarness();
    t.after(() => h.mux.stop());
    const requests = [];
    const observed = [];
    h.context.requestJson = async (_path, options) => {
      requests.push(options.body);
      if (requests.length === 2) throw failure;
      const revision = requests.length === 1 ? 10 : 12;
      await h.receive('snapshot', { revision, tables: [{ table_id: 'site', title: 'North',
        rows: [{ key: 'one', value: revision, ...(revision === 12 ? { source_alias: 'Dispatch' } : {}) }] }] });
      return {};
    };
    h.context.subscribeLiveChannelActivity({}, { markers: true });
    await flushMicrotasks();
    await h.clock.next();
    assert.equal(h.context.liveChannelActivityRevision, 10);
    h.context.subscribeLiveChannelActivity({ snapshot: (value) => observed.push(value) });
    await h.clock.next();
    assert.equal(h.context.liveChannelActivityNeedsResync, true);
    assert.equal(h.context.liveChannelActivityState, 'error');
    await h.receive('heartbeat', {}, 0);
    await h.receive('activity_delta', { table_id: 'site', operation: 'upsert', base_revision: 10,
      revision: 11, rows: [{ key: 'one', value: 11 }], removed_row_keys: [] });
    assert.ok(h.mux.lastFrameAt > 0);
    assert.equal(h.context.liveChannelActivityRevision, 10);
    assert.equal(requests.length, 2);
    assert.equal(await h.clock.next(), 500);
    assert.equal(requests.length, 3);
    assert.deepEqual(JSON.parse(JSON.stringify(requests[2].subscriptions.channel_activity)), {
      delta: true, subscription_id: requests[0].subscriptions.channel_activity.subscription_id
    });
    assert.equal(h.context.liveChannelActivityNeedsResync, false);
    assert.equal(h.context.liveChannelActivityState, 'open');
    assert.equal(observed.length, 1);
    const expected = { table_id: 'site', title: 'North', rows: [{ key: 'one', value: 13,
      source_alias: 'Dispatch' }] };
    await h.receive('activity_delta', { table_id: 'site', operation: 'upsert', base_revision: 12,
      revision: 13, rows: expected.rows, removed_row_keys: [] });
    assert.deepEqual(JSON.parse(JSON.stringify(h.tables.get('site'))), expected);
    assert.equal(h.context.liveChannelActivityRevision, 13);
    assert.equal(h.clock.timers.size, 0);
  });
}

test('repeated control failures back off to ten seconds and retry the latest desired subscription', async (t) => {
  const h = controlHarness();
  t.after(() => h.mux.stop());
  const requests = [];
  h.context.requestJson = async (_path, options) => {
    requests.push(options.body);
    throw Object.assign(new Error('Unavailable'), { status: 503 });
  };
  const subscription = h.mux.subscribe('channel_activity', { selection: 'first' });
  assert.equal(await h.clock.next(), 20);
  subscription.update({ selection: 'latest' });
  assert.equal(h.clock.timers.size, 1, 'new desired values cannot bypass the retry backoff');
  const delays = [];
  for (let count = 0; count < 10; count += 1) delays.push(await h.clock.next());
  assert.deepEqual(delays.slice(0, 3), [500, 850, 1445]);
  assert.equal(delays.at(-1), 10_000);
  assert.ok(delays.every((delay) => delay >= 500 && delay <= 10_000));
  assert.ok(requests.slice(1).every((request) => request.subscriptions.channel_activity.selection === 'latest'));
  assert.equal(h.clock.timers.size, 1);
});

test('closing the final subscriber cancels a queued retry and releases the stream', async () => {
  const h = controlHarness();
  let requests = 0;
  h.context.requestJson = async () => {
    requests += 1;
    throw Object.assign(new Error('Unavailable'), { status: 503 });
  };
  const streamController = h.mux.controller;
  const subscription = h.mux.subscribe('channel_activity');
  await h.clock.next();
  assert.notEqual(h.mux.controlRetryTimer, null);
  await subscription.close();
  assert.equal(streamController.signal.aborted, true);
  assert.equal(h.mux.ready, false);
  assert.equal(h.clock.timers.size, 0);
  assert.equal(await h.clock.next(), null);
  assert.equal(requests, 1);
});

test('closing one topic waits for successful latest control while other topics remain active', async (t) => {
  const h = controlHarness();
  t.after(() => h.mux.stop());
  const requests = [];
  h.context.requestJson = async (_path, options) => {
    requests.push(options.body);
    if (requests.length < 3) throw Object.assign(new Error('Unavailable'), { status: 503 });
    return {};
  };
  h.mux.subscribe('channel_activity');
  const diagnostic = h.mux.subscribe('tuner_diagnostics');
  await h.clock.next();
  let released = false;
  const closing = diagnostic.close().then(() => { released = true; });
  await h.clock.next();
  assert.equal(released, false);
  await h.clock.next();
  await closing;
  assert.deepEqual(Object.keys(requests.at(-1).subscriptions), ['channel_activity']);
  assert.equal(h.mux.hasSubscribers(), true);
  assert.equal(h.mux.ready, true);
});

for (const oldResult of ['success', 'failure']) {
  test(`stale control ${oldResult} cannot settle or cancel control for a new connection`, async (t) => {
    const h = controlHarness();
    t.after(() => h.mux.stop());
    const previous = deferred();
    const current = deferred();
    const requests = [];
    h.context.requestJson = (_path, options) => {
      requests.push(options);
      return requests.length === 1 ? previous.promise : current.promise;
    };
    h.mux.subscribe('channel_activity');
    await h.clock.next();
    h.mux.stop();
    assert.equal(requests[0].signal.aborted, true);
    h.mux.controller = new AbortController();
    h.mux.clientId = 'replacement-fixture';
    h.mux.ready = true;
    const desired = h.mux.queueControl(true);
    await h.clock.next();
    const currentController = h.mux.controlController;
    let applied = false;
    const waiting = h.mux.waitForControlRevision(desired).then((delivered) => { applied = delivered; });
    if (oldResult === 'success') previous.resolve({});
    else previous.reject(Object.assign(new Error('Old failure'), { status: 503 }));
    await flushMicrotasks();
    assert.equal(applied, false);
    assert.equal(h.mux.controlController, currentController);
    assert.equal(h.mux.controlInFlight, true);
    assert.equal(h.clock.timers.size, 0);
    assert.equal(h.mux.controlAppliedRevision, 0);
    current.resolve({});
    await waiting;
    assert.equal(applied, true);
    assert.equal(h.mux.controlAppliedRevision, desired);
  });
}

test('stream disconnect aborts pending control before the replacement connection becomes ready', async (t) => {
  const h = controlHarness();
  t.after(() => h.mux.stop());
  const reading = deferred();
  const previous = deferred();
  const requests = [];
  h.context.randomLiveClientId = () => 'stream-fixture';
  h.context.fetch = async () => ({ ok: true, status: 200, body: { getReader: () => ({
    read: () => reading.promise, cancel: () => Promise.resolve()
  }) } });
  h.context.requestJson = (_path, options) => {
    requests.push(options);
    return requests.length === 1 ? previous.promise : Promise.resolve({});
  };
  h.mux.controller = null;
  h.mux.ready = false;
  h.mux.subscribe('channel_activity');
  const connecting = h.mux.connect();
  await flushMicrotasks();
  await h.receive('ready', { client_id: 'stream-fixture' }, 0);
  await h.clock.next();
  reading.resolve({ done: true });
  await connecting;
  assert.equal(requests[0].signal.aborted, true);
  assert.equal(h.mux.controlInFlight, false);
  assert.equal(h.mux.controlController, null);
  assert.equal(h.mux.controlRetryTimer, null);
  h.mux.controller = new AbortController();
  h.mux.clientId = 'replacement-fixture';
  h.mux.attempt += 1;
  await h.receive('ready', { client_id: 'replacement-fixture' }, 0);
  await h.clock.next();
  const applied = h.mux.controlAppliedRevision;
  previous.resolve({});
  await flushMicrotasks();
  assert.equal(h.mux.controlAppliedRevision, applied);
  assert.equal(h.mux.controlInFlight, false);
});

test('missing control session reconnects while other permanent client errors do not loop', async (t) => {
  for (const status of [400, 404]) {
    const h = controlHarness();
    t.after(() => h.mux.stop());
    h.context.requestJson = async () => { throw Object.assign(new Error('Rejected'), { status }); };
    h.mux.subscribe('channel_activity');
    const applied = h.mux.waitForControlRevision(h.mux.controlDesiredRevision);
    await h.clock.next();
    assert.equal(await applied, false);
    assert.equal(h.clock.timers.size, 0);
    assert.equal(h.mux.attempt, status === 404 ? 1 : 0);
    assert.equal(h.mux.ready, status !== 404);
  }
});

for (const status of [401, 403]) {
  test(`an in-flight authorization failure ${status} releases a closing final subscriber`, async (t) => {
    const h = controlHarness();
    t.after(() => h.mux.stop());
    const pending = deferred();
    h.context.refreshAccessSession = async () => {};
    h.context.requestJson = () => pending.promise;
    const controller = h.mux.controller;
    const subscription = h.mux.subscribe('channel_activity');
    await h.clock.next();
    assert.equal(h.mux.controlInFlight, true);
    const closing = subscription.close();
    pending.reject(Object.assign(new Error('Unauthorized'), { status }));
    await flushMicrotasks();
    await closing;
    assert.equal(controller.signal.aborted, true);
    assert.equal(h.mux.ready, false);
    assert.equal(h.mux.controlWaiters.length, 0);
    assert.equal(h.clock.timers.size, 0);
  });

  test(`closing the final subscriber after authorization failure ${status} releases the stream`, async (t) => {
    const h = controlHarness();
    t.after(() => h.mux.stop());
    h.context.refreshAccessSession = async () => {};
    let requests = 0;
    h.context.requestJson = async () => {
      requests += 1;
      throw Object.assign(new Error('Unauthorized'), { status });
    };
    const subscription = h.mux.subscribe('channel_activity');
    await h.clock.next();
    assert.equal(h.mux.authorizationBlocked, true);
    assert.equal(h.mux.ready, true);
    await subscription.close();
    assert.equal(h.mux.ready, false);
    assert.equal(h.mux.controller, null);
    assert.equal(h.mux.hasSubscribers(), false);
    assert.equal(h.mux.controlWaiters.length, 0);
    assert.equal(h.clock.timers.size, 0);
    assert.equal(requests, 1);
  });

  test(`authorization failure ${status} waits for confirmed access refresh without retrying`, async (t) => {
    const h = controlHarness();
    t.after(() => h.mux.stop());
    let refreshes = 0;
    let requests = 0;
    h.context.refreshAccessSession = async () => { refreshes += 1; };
    h.context.requestJson = async () => {
      requests += 1;
      throw Object.assign(new Error('Unauthorized'), { status });
    };
    const subscription = h.mux.subscribe('channel_activity');
    await h.clock.next();
    subscription.update({ changed: true });
    assert.equal(h.mux.authorizationBlocked, true);
    assert.equal(h.clock.timers.size, 0);
    assert.equal(refreshes, 1);
    assert.equal(requests, 1);
    const attempt = h.mux.attempt;
    h.mux.confirmedAccessRefresh();
    assert.equal(h.mux.authorizationBlocked, false);
    assert.equal(h.mux.attempt, attempt + 1);
  });
}

test('invalid delta baselines/orders are rejected rather than silently dropping rows', () => {
  const { context } = harness();
  assert.throws(() => context.mergeLiveChannelActivityDelta({ table_id: 'missing', rows: [] }), /baseline/);
  const table = { rows: [{ key: 'one' }, { key: 'two' }] };
  assert.throws(() => context.mergeLiveChannelActivityDelta({ table_id: 'site',
    row_order: ['one', 'one'] }, table), /row order/);
  assert.throws(() => context.mergeLiveChannelActivityDelta({ table_id: 'site',
    row_order: ['one', 'absent'] }, table), /Missing activity row/);
});

test('older recovery snapshots cannot roll the current activity state backward', () => {
  const { context, tables, events } = harness();
  context.applyLiveChannelActivitySnapshot({ revision: 9, tables: [{ table_id: 'site', rows: [{ key: 'new' }] }] });
  context.applyLiveChannelActivitySnapshot({ revision: 8, tables: [{ table_id: 'site', rows: [{ key: 'old' }] }] });
  assert.equal(tables.get('site').rows[0].key, 'new');
  assert.equal(context.liveChannelActivityRevision, 9);
  assert.equal(events.length, 1);
});

test('marker subscriptions upgrade to full rows before sharing a cached baseline with a full subscriber', () => {
  const { context } = harness();
  const parameters = [];
  const snapshots = [];
  Object.assign(context, {
    document: { hidden: false, addEventListener() {} },
    liveChannelActivitySource: null, liveChannelActivityState: 'connecting',
    liveChannelActivitySubscribers: new Set(), liveConnections: new Set(), pageConnections: new Set(),
    liveConnection: (_topic, initial) => {
      let current = JSON.stringify(initial);
      parameters.push(initial);
      return {
        addEventListener() {}, close() {},
        update(next) {
          if (current === JSON.stringify(next)) return false;
          current = JSON.stringify(next);
          parameters.push(next);
          return true;
        }
      };
    }
  });
  vm.runInContext(source.slice(source.indexOf('function synchronizeLiveChannelActivitySource'),
    source.indexOf('const DIAGNOSTIC_FRAME_MAGIC')), context);
  const marker = context.subscribeLiveChannelActivity({}, { markers: true });
  const subscriptionId = parameters.at(-1).subscription_id;
  assert.ok(subscriptionId);
  assert.deepEqual(JSON.parse(JSON.stringify(parameters.at(-1))), {
    delta: true, subscription_id: subscriptionId, markers: true
  });
  context.applyLiveChannelActivitySnapshot({ revision: 10, tables: [{ table_id: 'site', rows: [] }] });
  const full = context.subscribeLiveChannelActivity({ snapshot: (data) => snapshots.push(data) });
  assert.deepEqual(JSON.parse(JSON.stringify(parameters.at(-1))), { delta: true, subscription_id: subscriptionId });
  assert.equal(context.liveChannelActivityNeedsResync, true);
  assert.equal(snapshots.length, 0);
  full.close();
  assert.deepEqual(JSON.parse(JSON.stringify(parameters.at(-1))), {
    delta: true, subscription_id: subscriptionId, markers: true
  });
  marker.close();
  assert.equal(context.liveChannelActivitySource, null);
  assert.equal(context.pageConnections.size, 0);
});
