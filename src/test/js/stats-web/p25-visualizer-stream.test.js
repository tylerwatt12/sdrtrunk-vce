'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { pathToFileURL } = require('node:url');
const { test } = require('node:test');

const feature = path.resolve(__dirname, '../../../../stats-web/assets/features/network-visualizer');
const source = fs.readFileSync(path.join(feature, 'index.js'), 'utf8');
const functions = source.slice(source.indexOf('  function applyActivityUpdate('),
  source.indexOf('  function setHistoryHours('));
const observedAt = 1_700_000_000_000;
const row = (id, timestamp = observedAt) => ({ id, observed_at_ms: timestamp, action: 'GRANT',
  protocol: 'p25', channel_kind: 'trunked_site', radio_system_key: 'system',
  source_identity_key: 'v1-r-7001', target_identity_key: 'v1-g-101', target_kind: 'talkgroup' });

async function harness() {
  const history = await import(pathToFileURL(path.join(feature, 'history.js')).href);
  const frames = [], delays = [], attention = [], affiliationChanges = [];
  let callbacks;
  const context = {
    ...history, state: history.createP25HistoryState(), P25_ROUTINE_ACTIONS: history.P25_ROUTINE_ACTIONS,
    generation: 1, closed: false, cursor: 0, grantActivityTimeoutMs: 1_000, serverTimeOffsetMs: 0,
    empty: { hidden: false },
    liveSource: null, liveBound: false, liveReady: false, collectionEnabled: true, catchupRequested: false,
    bufferedActivity: [], bufferedRowCount: 0, polling: false, pollTimer: 0,
    MAX_POLL_ROWS: 25_000, MAX_BUFFERED_ACTIVITY_BATCHES: 64, eventSettings: {},
    HOUR_MS: 3_600_000, CLOCK_TOLERANCE_MS: 2_000, historyHours: 1,
    routineP25ActivityEnabled: () => true, highlightedCategories: new Set(['call']),
    requestController: { signal: new AbortController().signal }, document: { hidden: false },
    Date: { now: () => observedAt }, window: { clearTimeout() {}, setTimeout() {} },
    status: { classList: { contains: () => false } }, setStatus() {},
    schedulePoll: (delay = 5_000) => delays.push(delay), loadSeed() {},
    applyAttention: (values) => attention.push(...values),
    renderGraph: (_animate, changes = []) => {
      affiliationChanges.push(...changes);
      frames.push(history.buildP25Graph(context.state, 'system', observedAt + context.serverTimeOffsetMs));
    },
    dependencies: { subscribeActivity: (value) => { callbacks = value; return { close() {} }; } }
  };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf('  function historyBounds('),
    source.indexOf('  async function requestForwardPage(')), context);
  vm.runInContext(functions, context);
  context.connectLiveActivity(1);
  return { context, frames, delays, attention, affiliationChanges, callbacks, history };
}

test('saved activity bridges seed/subscription gap, deduplicates overlap and then updates without polling', async () => {
  const { context, frames, callbacks, history } = await harness();
  callbacks.sourceChange({ server_time_ms: observedAt + 2_000,
    traffic_grant_age_out_milliseconds: 1_200 });
  callbacks.activityAppend({ rows: [row(2, observedAt + 1_900), row(3, observedAt + 1_950)], next_after_id: 3 });
  assert.equal(context.bufferedRowCount, 2);
  let requests = 0;
  context.requestForwardPage = async (_after, _watermark, _signal, bounds) => {
    requests += 1;
    assert.equal(bounds.toMs, observedAt + 4_000, 'catch-up queries use the receiver clock plus tolerance');
    return { rows: [row(1, observedAt + 1_800), row(2, observedAt + 1_900)], watermark_id: 2,
      next_after_id: 2, has_more: false };
  };
  await context.poll();
  assert.equal(context.liveReady, true);
  assert.equal(context.empty.hidden, true, 'the first pushed activity clears a previously empty history overlay');
  assert.equal(context.cursor, 3);
  assert.equal(context.state.seenIds.size, 3, 'the HTTP/push overlap is accepted once');
  assert.equal(context.bufferedRowCount, 0);
  assert.equal(history.nextP25HighlightExpiry(context.state, observedAt + 2_000), observedAt + 3_150,
    'receiver clock offset preserves the remaining global grant timeout');
  callbacks.activityAppend({ rows: [row(4, observedAt + 2_000)], next_after_id: 4 });
  assert.equal(context.cursor, 4);
  assert.equal(requests, 1, 'fresh push batches cause no additional history request');
  assert.equal(frames.length, 3);
});

test('failed catch-up retains its barrier; bounded overflow and a gap recover through history', async () => {
  const { context, callbacks, delays, attention } = await harness();
  callbacks.sourceChange({ server_time_ms: observedAt });
  context.requestForwardPage = async () => { throw new Error('Unavailable'); };
  await context.poll();
  assert.equal(context.liveReady, false, 'a failed request cannot promote buffered updates to live');
  assert.equal(delays.at(-1), 5_000, 'failed requests use the existing bounded retry cadence');
  for (let batch = 0; batch < 65; batch += 1) {
    callbacks.activityAppend({ rows: [row(batch + 1)], next_after_id: batch + 1 });
  }
  assert.equal(context.bufferedActivity.length, 0, 'overflow discards only the push buffer and requests catch-up');
  assert.equal(context.catchupRequested, true);
  context.requestForwardPage = async () => ({ rows: [row(1, observedAt - 2_000)], watermark_id: 65,
    next_after_id: 65, has_more: false });
  await context.poll();
  assert.equal(context.cursor, 65);
  assert.equal(context.liveReady, true);
  assert.equal(attention.length, 0, 'old recovery rows do not replay call attention');
  callbacks.gap();
  assert.equal(context.liveReady, false);
  assert.equal(context.catchupRequested, true);
  assert.equal(delays.at(-1), 0);
});

test('callbacks from a prior history scope or a closed visualizer cannot update state', async () => {
  const { context, callbacks, delays } = await harness();
  context.generation = 2;
  callbacks.sourceChange({ server_time_ms: observedAt });
  callbacks.activityAppend({ rows: [row(1)], next_after_id: 1 });
  callbacks.gap();
  callbacks.error();
  assert.equal(context.state.seenIds.size, 0);
  assert.equal(context.bufferedRowCount, 0);
  assert.equal(delays.length, 0);
  context.generation = 1;
  context.closed = true;
  callbacks.activityAppend({ rows: [row(1)], next_after_id: 1 });
  assert.equal(context.bufferedRowCount, 0);
});

test('live affiliation batches hand the renderer FIFO changes while exposing the latest canonical state', async () => {
  const { context, frames, affiliationChanges, callbacks, history } = await harness();
  const join = (id, group, atMs) => ({ ...row(id, atMs), action: 'JOIN', configuration_id: 'site-a',
    target_identity_key: `v1-g-${group}` });
  history.applyP25ActivityRows(context.state, [join(1, '101', observedAt - 100)], { initial: true });
  context.liveReady = true;
  callbacks.activityAppend({ rows: [join(3, '303', observedAt), join(2, '202', observedAt - 50)],
    next_after_id: 3 });
  assert.deepEqual(affiliationChanges.map((change) => [change.fromGroupKey, change.toGroupKey]),
    [['system:v1-g-101', 'system:v1-g-202'], ['system:v1-g-202', 'system:v1-g-303']]);
  assert.equal(frames.at(-1).links.find((link) => link.kind === 'current').target, 'system:v1-g-303');
  assert.equal(context.cursor, 3);
  const count = affiliationChanges.length;
  callbacks.activityAppend({ rows: [join(4, '404', observedAt - 10_000)], next_after_id: 4 });
  assert.equal(affiliationChanges.length, count, 'late older scoped evidence cannot add presentation moves');
  assert.equal(frames.at(-1).links.find((link) => link.kind === 'current').target, 'system:v1-g-303');
});
