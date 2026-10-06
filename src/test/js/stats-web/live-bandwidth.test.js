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
  const context = {
    Uint8Array, DataView, TextDecoder, Blob, DecompressionStream, AbortController,
    LIVE_MULTIPLEX_HEADER_BYTES: 16, LIVE_MULTIPLEX_MAGIC: 0x534c4d58,
    LIVE_MULTIPLEX_VERSION: 2, LIVE_MULTIPLEX_MAXIMUM_BYTES: 16 * 1024 * 1024,
    LIVE_MULTIPLEX_DECODER: new TextDecoder(),
    LIVE_MULTIPLEX_TOPICS: { 0: 'control', 1: 'channel_activity', 5: 'tuner_diagnostics' },
    window: { setTimeout, clearTimeout, setInterval, clearInterval },
    invokeLiveSubscriber: (target, method, ...args) => target[method]?.(...args),
    decodeDiagnosticFrame: (bytes) => ({ bytes }),
    liveChannelActivityTables: tables, liveChannelActivityRevision: 0,
    liveChannelActivityNeedsResync: false,
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
  assert.deepEqual(JSON.parse(JSON.stringify(parameters.at(-1))), { delta: true, markers: true });
  context.applyLiveChannelActivitySnapshot({ revision: 10, tables: [{ table_id: 'site', rows: [] }] });
  const full = context.subscribeLiveChannelActivity({ snapshot: (data) => snapshots.push(data) });
  assert.deepEqual(JSON.parse(JSON.stringify(parameters.at(-1))), { delta: true });
  assert.equal(context.liveChannelActivityNeedsResync, true);
  assert.equal(snapshots.length, 0);
  full.close();
  assert.deepEqual(JSON.parse(JSON.stringify(parameters.at(-1))), { delta: true, markers: true });
  marker.close();
  assert.equal(context.liveChannelActivitySource, null);
  assert.equal(context.pageConnections.size, 0);
});
