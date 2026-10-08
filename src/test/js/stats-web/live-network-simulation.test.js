'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { gzipSync } = require('node:zlib');
const { test } = require('node:test');

const source = fs.readFileSync(process.argv[2] || path.resolve(__dirname,
  '../../../../stats-web/assets/app.js'), 'utf8');
const milliseconds = () => performance.now();
const pause = delay => new Promise(resolve => setTimeout(resolve, delay));

function frame(topic, event, data, compress = true) {
  const raw = Buffer.from(JSON.stringify({ event, data }));
  const compressed = compress && raw.length >= 1024 ? gzipSync(raw, { level: 1 }) : null;
  const gzip = compressed && compressed.length <= raw.length * 0.85;
  const payload = gzip ? compressed : raw;
  const result = new Uint8Array(16 + payload.length);
  const header = new DataView(result.buffer);
  header.setUint32(0, 0x534c4d58);
  header.setUint8(4, 2);
  header.setUint8(5, 1 | (gzip ? 0x80 : 0));
  header.setUint16(6, topic);
  header.setUint32(8, payload.length);
  header.setUint32(12, gzip ? raw.length : 0);
  result.set(payload, 16);
  return result;
}

// This shapes the response byte stream, not each frame's write. Propagation delay is paid once per packet;
// back-to-back packets serialize at 2 Mbps without incorrectly adding a round trip to every frame.
function shapedHarness(roundTripMs, bitsPerSecond = 2_000_000) {
  const oneWayMs = roundTripMs / 2;
  const timers = new Set();
  const deliveries = [];
  const controls = [];
  const state = { controller: null, downstreamAvailableAt: milliseconds(), stopped: false };
  let identifier = 0;
  const later = (callback, delay) => {
    const timer = setTimeout(() => { timers.delete(timer); callback(); }, Math.max(0, delay));
    timers.add(timer);
    return timer;
  };
  const send = bytes => {
    for (let offset = 0; offset < bytes.length; offset += 1400) {
      const packet = bytes.slice(offset, offset + 1400);
      state.downstreamAvailableAt = Math.max(milliseconds(), state.downstreamAvailableAt) +
        packet.length * 8_000 / bitsPerSecond;
      later(() => {
        if (!state.stopped && state.controller?.desiredSize !== null) state.controller.enqueue(packet);
      }, state.downstreamAvailableAt + oneWayMs - milliseconds());
    }
  };
  const context = {
    Uint8Array, DataView, TextDecoder, Blob, DecompressionStream, AbortController, queueMicrotask,
    console, Date, window: { setTimeout, clearTimeout, setInterval, clearInterval },
    LIVE_MULTIPLEX_HEADER_BYTES: 16, LIVE_MULTIPLEX_MAGIC: 0x534c4d58, LIVE_MULTIPLEX_VERSION: 2,
    LIVE_MULTIPLEX_MAXIMUM_BYTES: 16 * 1024 * 1024,
    LIVE_MULTIPLEX_READY_TIMEOUT_MS: 10_000, LIVE_MULTIPLEX_LIVENESS_TIMEOUT_MS: 25_000,
    LIVE_MULTIPLEX_DECODER: new TextDecoder(),
    LIVE_MULTIPLEX_TOPICS: { 0: 'control', 1: 'channel_activity', 2: 'decode_events',
      3: 'decode_messages', 4: 'channel_diagnostics' },
    invokeLiveSubscriber: (target, method, ...args) => target[method]?.(...args),
    snakeCasePayload: value => value,
    randomLiveClientId: () => `00000000-0000-4000-8000-${String(++identifier).padStart(12, '0')}`,
    decodeDiagnosticFrame: bytes => ({ bytes }),
    fetch: async (url, options) => {
      const clientId = new URL(url, 'http://fixture.invalid').searchParams.get('client_id');
      const body = new ReadableStream({ start(controller) { state.controller = controller; } });
      options.signal.addEventListener('abort', () => {
        state.stopped = true;
        try { state.controller.close(); } catch (_) {}
      }, { once: true });
      await pause(oneWayMs);
      send(frame(0, 'ready', { client_id: clientId }));
      await pause(oneWayMs);
      return { ok: true, status: 200, body };
    },
    requestJson: async (_url, options) => {
      await pause(oneWayMs + Buffer.byteLength(JSON.stringify(options.body)) * 8_000 / bitsPerSecond);
      controls.push({ at: milliseconds(), subscriptions: options.body.subscriptions });
      for (const [topic, number] of [['decode_events', 2], ['decode_messages', 3], ['channel_diagnostics', 4]]) {
        const parameters = options.body.subscriptions[topic];
        if (!parameters) continue;
        send(frame(number, 'source_change', { ...parameters, bound: true }));
        // Events arrive afresh; messages bind on the observer worker; channel bindings refresh at 4 Hz.
        const observerDelay = topic === 'channel_diagnostics' ? 250 : topic === 'decode_messages' ? 100 : 20;
        later(() => send(frame(number, topic === 'channel_diagnostics' ? 'state' :
          topic === 'decode_messages' ? 'decode_message' : 'decode_event', {
          subscription_id: parameters.subscription_id, bound: true, state: 'live',
          text: 'Synthetic decoded payload '.repeat(80)
        })), observerDelay);
      }
      await pause(oneWayMs);
      return {};
    }
  };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf('async function inflateLiveMultiplexPayload'),
    source.indexOf('const liveMultiplexer = new LiveMultiplexer()')), context);
  const mux = vm.runInContext('new LiveMultiplexer()', context);
  const subscribe = (topic, subscriptionId) => mux.subscribe(topic, { subscription_id: subscriptionId }, {
    onOpen: () => deliveries.push({ topic, event: 'open', at: milliseconds() }),
    onEvent: (event, data) => deliveries.push({ topic, event, data, at: milliseconds() }),
    onError: error => deliveries.push({ topic, event: 'error', error, at: milliseconds() })
  });
  return {
    mux, controls, deliveries, subscribe, send,
    async waitFor(topic, event, since = 0) {
      const deadline = milliseconds() + 8_000;
      let delivery;
      while (!(delivery = deliveries.find(item => item.topic === topic && item.event === event && item.at >= since))) {
        assert.ok(milliseconds() < deadline, `${topic}/${event} did not arrive`);
        await pause(5);
      }
      return delivery;
    },
    close() {
      mux.stop();
      state.stopped = true;
      timers.forEach(clearTimeout);
    }
  };
}

for (const roundTripMs of [125, 250]) {
  test(`Live topic handshakes and fresh delivery at ${roundTripMs} ms RTT and 2 Mbps`, async t => {
    const h = shapedHarness(roundTripMs);
    t.after(() => h.close());
    const startedAt = milliseconds();
    h.subscribe('channel_activity', 'activity');
    let topic = h.subscribe('decode_events', 'events');
    const opened = await h.waitFor('channel_activity', 'open');
    const event = await h.waitFor('decode_events', 'decode_event');
    assert.ok(opened.at - startedAt < 1_000, 'stream ready must not wait for a liveness timeout');
    assert.ok(event.at - startedAt < 1_500, 'fresh events must arrive without multi-second transport startup');

    const messagesAt = milliseconds();
    void topic.close();
    topic = h.subscribe('decode_messages', 'messages');
    const message = await h.waitFor('decode_messages', 'decode_message', messagesAt);
    assert.ok(message.at - messagesAt < 1_000, 'switching topics must reuse the existing stream');
    const channelAt = milliseconds();
    void topic.close();
    h.subscribe('channel_diagnostics', 'channel');
    const channel = await h.waitFor('channel_diagnostics', 'state', channelAt);
    assert.ok(channel.at - channelAt < 1_500, 'channel state must arrive after the bounded binding refresh');
    assert.equal(h.mux.attempt, 1);
    assert.equal(h.deliveries.filter(item => item.event === 'error').length, 0);
    t.diagnostic(JSON.stringify({ round_trip_ms: roundTripMs, bits_per_second: 2_000_000,
      ready_ms: Math.round(opened.at - startedAt), first_event_ms: Math.round(event.at - startedAt),
      switch_message_ms: Math.round(message.at - messagesAt), channel_state_ms: Math.round(channel.at - channelAt) }));
  });
}

test('a large in-flight frame postpones another topic until its bytes arrive at 2 Mbps', async t => {
  const h = shapedHarness(125);
  t.after(() => h.close());
  h.subscribe('channel_activity', 'activity');
  await h.waitFor('channel_activity', 'open');
  const startedAt = milliseconds();
  const bytes = frame(1, 'snapshot', { revision: 1, tables: [], synthetic_payload: 'x'.repeat(512 * 1024) }, false);
  h.send(bytes);
  h.send(frame(4, 'state', { state: 'live' }));
  h.subscribe('channel_diagnostics', 'channel');
  const state = await h.waitFor('channel_diagnostics', 'state', startedAt);
  const elapsed = state.at - startedAt;
  assert.ok(elapsed >= bytes.length * 8_000 / 2_000_000, 'one stream preserves frame byte order');
  assert.ok(elapsed < 4_000);
  assert.equal(h.deliveries.filter(item => item.event === 'error').length, 0);
  t.diagnostic(JSON.stringify({ round_trip_ms: 125, bits_per_second: 2_000_000,
    in_flight_wire_bytes: bytes.length, channel_state_ms: Math.round(elapsed),
    limitation: 'Deliberately uncompressed synthetic payload; demonstrates byte-stream head-of-line delay, not receiver load.' }));
});

test('compressed activity baselines leave topic state responsive at 2 Mbps', async t => {
  const h = shapedHarness(125);
  t.after(() => h.close());
  h.subscribe('channel_activity', 'activity');
  await h.waitFor('channel_activity', 'open');
  h.subscribe('channel_diagnostics', 'channel');
  const rows = Array.from({ length: 1024 }, (_, index) => ({
    key: `call-${index}`, status: 'CALL', source_id: String(10000 + index), source_alias: `Engine ${index % 80}`,
    target_id: String(20000 + index % 40), target_alias: `Dispatch ${index % 40}`,
    target_alias_description: 'Synthetic dispatch channel for the impaired network fixture.',
    configuration_id: '00000000-0000-4000-8000-000000000001', channel_name: 'Synthetic control channel',
    frequency_hz: 851_012_500, role: 'TRAFFIC', decoder: 'P25_PHASE1', timeslot: 1
  }));
  const tables = Array.from({ length: 4 }, (_, index) => ({ table_id: `site-${index}`, title: 'Synthetic system',
    rows: rows.slice(index * 256, (index + 1) * 256) }));
  const data = { revision: 1, tables };
  const raw = frame(1, 'snapshot', data, false);
  const encoded = frame(1, 'snapshot', data, true);
  assert.ok(encoded.length < raw.length / 10, 'repeated activity metadata should compress materially');
  const startedAt = milliseconds();
  h.send(encoded);
  h.send(frame(4, 'state', { state: 'live', fixture_marker: 'after-baseline' }));
  const state = await h.waitFor('channel_diagnostics', 'state', startedAt);
  const elapsed = state.at - startedAt;
  assert.ok(elapsed < 1_000);
  assert.equal(h.deliveries.filter(item => item.event === 'error').length, 0);
  t.diagnostic(JSON.stringify({ round_trip_ms: 125, bits_per_second: 2_000_000,
    uncompressed_bytes: raw.length, wire_bytes: encoded.length, channel_state_ms: Math.round(elapsed) }));
});

test('continuous activity below 2 Mbps remains continuous with 125 ms one-way latency', async t => {
  const h = shapedHarness(250);
  t.after(() => h.close());
  h.subscribe('channel_activity', 'activity');
  h.subscribe('decode_events', 'events');
  h.subscribe('channel_diagnostics', 'channel');
  await h.waitFor('decode_events', 'decode_event');
  await h.waitFor('channel_diagnostics', 'state');
  const startedAt = milliseconds();
  let wireBytes = 0;
  for (let sequence = 0; sequence < 40; sequence += 1) {
    const event = frame(2, 'decode_event', { fixture_sequence: sequence,
      details: 'Synthetic decoded call '.repeat(90) }, false);
    h.send(event);
    wireBytes += event.length;
    if (sequence % 2 === 0) {
      const state = frame(4, 'state', { fixture_sequence: sequence, state: 'live',
        synthetic_samples: '0123456789'.repeat(400) }, false);
      h.send(state);
      wireBytes += state.length;
    }
    await pause(25);
  }
  const deadline = milliseconds() + 2_000;
  let events;
  do {
    events = h.deliveries.filter(item => item.topic === 'decode_events' &&
      Number.isInteger(item.data?.fixture_sequence));
    if (events.length === 40) break;
    await pause(5);
  } while (milliseconds() < deadline);
  assert.equal(events.length, 40);
  assert.deepEqual(events.map(item => item.data.fixture_sequence), Array.from({ length: 40 }, (_, index) => index));
  const maximumGap = Math.max(...events.slice(1).map((item, index) => item.at - events[index].at));
  assert.ok(maximumGap < 250, 'a steady stream must not pause for reconnect/backoff intervals');
  assert.equal(h.mux.attempt, 1);
  assert.equal(h.deliveries.filter(item => item.event === 'error').length, 0);
  t.diagnostic(JSON.stringify({ round_trip_ms: 250, bits_per_second: 2_000_000,
    synthetic_wire_bytes: wireBytes, generated_frames: 60, received_events: events.length,
    maximum_event_gap_ms: Math.round(maximumGap), total_ms: Math.round(events.at(-1).at - startedAt) }));
});
