const assert = require('node:assert/strict');
const test = require('node:test');
const { pathToFileURL } = require('node:url');
const { resolve } = require('node:path');
const modulePromise = import(pathToFileURL(resolve('stats-web/assets/core/recording-play-history.js')).href);
const store = () => {
  const records = new Map();
  return { records, getItem: key => records.get(key) ?? null, setItem: (key, value) => records.set(key, value) };
};

test('completed calls survive reload in this tab while fresh sessions have no played history', async () => {
  const { createRecordingPlayHistory } = await modulePromise;
  const storage = store();
  const first = createRecordingPlayHistory({ storage }); first.activate(null);
  const completed = first.onCompletion('1000:1');
  assert.equal(first.has('1000:1'), false, 'Starting or skipping does not mark a call');
  completed();
  const reloaded = createRecordingPlayHistory({ storage }); reloaded.activate(null);
  assert.equal(reloaded.has('1000:1'), true);
  assert.equal(reloaded.has('2000:1'), false, 'A reused recording ID with another start is a different call');
  const fresh = createRecordingPlayHistory({ storage: store() }); fresh.activate(null);
  assert.equal(fresh.has('1000:1'), false);
});

test('guest and account session histories remain isolated and obsolete completions are ignored', async () => {
  const { createRecordingPlayHistory } = await modulePromise;
  const history = createRecordingPlayHistory({ storage: store() });
  history.activate(null); history.onCompletion('1000:1')();
  const oldGuest = history.onCompletion('2000:2');
  history.activate('alice'); oldGuest();
  assert.equal(history.has('1000:1'), false); assert.equal(history.has('2000:2'), false);
  history.onCompletion('3000:3')();
  const oldAccount = history.onCompletion('4000:4');
  history.activate('bob'); oldAccount();
  assert.equal(history.has('3000:3'), false); assert.equal(history.has('4000:4'), false);
  history.activate('alice'); assert.equal(history.has('3000:3'), true);
  history.activate(null); assert.equal(history.has('1000:1'), true);
  assert.equal(history.has('3000:3'), false);
  assert.equal(history.has('2000:2'), false);
});

test('history is bounded and unavailable or incompatible storage cannot interrupt playback', async () => {
  const { createRecordingPlayHistory, RECORDING_PLAY_HISTORY_KEY } = await modulePromise;
  const storage = store();
  const history = createRecordingPlayHistory({ storage }); history.activate(null);
  for (let i = 0; i < 510; i++) history.onCompletion(`${i}:1`)();
  assert.equal(history.has('0:1'), false); assert.equal(history.has('509:1'), true);
  for (let i = 0; i < 10; i++) { history.activate(`account-${i}`); history.onCompletion('1000:1')(); }
  assert.equal(JSON.parse(storage.records.get(RECORDING_PLAY_HISTORY_KEY)).profiles.length, 8);
  const reopened = createRecordingPlayHistory({ storage });
  reopened.onCompletion('2000:2')();
  assert.equal(JSON.parse(storage.records.get(RECORDING_PLAY_HISTORY_KEY)).profiles.length, 8,
    'A default guest history must also stay bounded after loading eight account histories');
  const blocked = createRecordingPlayHistory({ storage: { getItem() { throw Error('Blocked'); }, setItem() { throw Error('Blocked'); } } });
  blocked.activate(null); blocked.onCompletion('1000:1')(); assert.equal(blocked.has('1000:1'), true);
  const future = store(); future.records.set(RECORDING_PLAY_HISTORY_KEY, '{"version":2}');
  const incompatible = createRecordingPlayHistory({ storage: future }); incompatible.activate(null);
  incompatible.onCompletion('1000:1')();
  assert.equal(future.records.get(RECORDING_PLAY_HISTORY_KEY), '{"version":2}');
});
