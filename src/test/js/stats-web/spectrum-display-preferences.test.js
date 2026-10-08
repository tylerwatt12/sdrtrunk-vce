const assert = require('node:assert/strict');
const test = require('node:test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');

const helper = import(pathToFileURL(resolve(process.argv[2] ||
  'stats-web/assets/core/spectrum-display-preferences.js')).href);
const FFT = 'fft_auto_range_on_retune';
const WATERFALL = 'waterfall_auto_range_on_retune';
const storageFixture = () => {
  const records = new Map();
  return { records, getItem: (key) => records.get(key) ?? null,
    setItem: (key, value) => records.set(key, value) };
};

test('one display choice controls both plots with one browser write and preserves previous opt-outs', async () => {
  const { createSpectrumDisplayPreferences } = await helper;
  const storage = storageFixture();
  let writes = 0;
  const store = storage.setItem;
  storage.setItem = (key, value) => { writes++; store(key, value); };
  const first = createSpectrumDisplayPreferences({ identity: 'user-a', storage });
  assert.equal(first.autoRangeEnabled(), true);
  first.setAutoRangeEnabled(false);
  assert.equal(writes, 1);
  assert.equal(first.get(FFT), false);
  assert.equal(first.get(WATERFALL), false);
  const reopened = createSpectrumDisplayPreferences({ identity: 'user-a', storage });
  assert.equal(reopened.autoRangeEnabled(), false);
  reopened.setAutoRangeEnabled(true);
  assert.equal(first.autoRangeEnabled(), true, 'shared panels read the saved choice');
  first.set(FFT, false);
  assert.equal(reopened.autoRangeEnabled(), false, 'a previous single-plot opt-out is preserved');
  assert.throws(() => first.setAutoRangeEnabled('false'), /boolean/);
});

test('both automatic display options default on and persist independently in this browser', async () => {
  const { createSpectrumDisplayPreferences } = await helper;
  const storage = storageFixture();
  const first = createSpectrumDisplayPreferences({ identity: 'user-a', storage });
  assert.equal(first.get(FFT), true);
  assert.equal(first.get(WATERFALL), true);
  first.set(FFT, false);
  const reopened = createSpectrumDisplayPreferences({ identity: 'user-a', storage });
  assert.equal(reopened.get(FFT), false);
  assert.equal(reopened.get(WATERFALL), true);
  reopened.set(WATERFALL, false);
  assert.equal(first.get(WATERFALL), false, 'another panel reads the current shared browser choice');
  assert.deepEqual(JSON.parse([...storage.records.values()][0]), {
    version: 1, [FFT]: false, [WATERFALL]: false
  });
});

test('different accounts and anonymous visitors never share saved choices', async () => {
  const { createSpectrumDisplayPreferences } = await helper;
  const storage = storageFixture();
  createSpectrumDisplayPreferences({ identity: 'user-a', storage }).set(FFT, false);
  const second = createSpectrumDisplayPreferences({ identity: 'user-b', storage });
  const anonymous = createSpectrumDisplayPreferences({ storage });
  assert.equal(second.get(FFT), true);
  assert.equal(anonymous.get(FFT), true);
  anonymous.set(WATERFALL, false);
  const namedAnonymous = createSpectrumDisplayPreferences({ identity: 'anonymous', storage });
  assert.equal(namedAnonymous.get(WATERFALL), true);
  assert.equal(second.get(WATERFALL), true);
  assert.equal(storage.records.size, 2);
});

test('invalid and unsupported records fall back to enabled without copying unknown fields', async () => {
  const { createSpectrumDisplayPreferences } = await helper;
  for (const saved of ['broken', 'null', '[]', JSON.stringify({ version: 2, [FFT]: false, [WATERFALL]: false }),
    JSON.stringify({ version: 1, [FFT]: 'false', [WATERFALL]: true }),
    JSON.stringify({ version: 1, [FFT]: false }),
    JSON.stringify({ version: 1, [FFT]: false, [WATERFALL]: false, extra: true }), ' '.repeat(513)]) {
    const storage = storageFixture();
    const writer = createSpectrumDisplayPreferences({ identity: 'user-a', storage });
    writer.set(FFT, false);
    storage.records.set([...storage.records.keys()][0], saved);
    const preferences = createSpectrumDisplayPreferences({ identity: 'user-a', storage });
    assert.equal(preferences.get(FFT), true);
    assert.equal(preferences.get(WATERFALL), true);
    preferences.set(WATERFALL, false);
    assert.deepEqual(JSON.parse([...storage.records.values()][0]), {
      version: 1, [FFT]: true, [WATERFALL]: false
    });
  }
});

test('blocked or full browser storage retains choices in memory and rejects unsupported keys', async () => {
  const { createSpectrumDisplayPreferences } = await helper;
  const preferences = createSpectrumDisplayPreferences({ identity: 'user-a', storage: {
    getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('full'); }
  } });
  preferences.set(FFT, false);
  assert.equal(preferences.get(FFT), false);
  assert.equal(preferences.get(WATERFALL), true);
  assert.throws(() => preferences.set(FFT, 'false'), /boolean/);
  assert.throws(() => preferences.set('unknown', false), /Unknown/);
  assert.throws(() => preferences.get('unknown'), /Unknown/);
  const storage = storageFixture();
  const saved = createSpectrumDisplayPreferences({ identity: 'user-a', storage });
  saved.set(FFT, true);
  storage.setItem = () => { throw new Error('full'); };
  saved.set(FFT, false);
  assert.equal(saved.get(FFT), false, 'an earlier saved value cannot overwrite the unsaved choice');
});
