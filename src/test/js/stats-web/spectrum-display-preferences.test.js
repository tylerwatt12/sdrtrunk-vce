const assert = require('node:assert/strict');
const test = require('node:test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');
const helper = import(pathToFileURL(resolve(process.argv[2] ||
  'stats-web/assets/core/spectrum-display-preferences.js')).href);
const FFT = 'fft_auto_range_on_retune', WATERFALL = 'waterfall_auto_range_on_retune';

test('Spectrum panels share the personal service and preserve unrelated tuner settings', async () => {
  const { createSpectrumDisplayPreferences } = await helper;
  const preferences = { tuner: { floor_db: -140, [FFT]: true, [WATERFALL]: true } };
  let writes = 0;
  const options = { read: () => preferences.tuner, update: async mutator => { writes++; mutator(preferences); } };
  const first = createSpectrumDisplayPreferences(options), second = createSpectrumDisplayPreferences(options);
  await first.setAutoRangeEnabled(false);
  assert.equal(writes, 1);
  assert.equal(second.autoRangeEnabled(), false);
  assert.equal(second.get(FFT), false); assert.equal(second.get(WATERFALL), false);
  await second.setAutoRangeEnabled(true);
  await first.set(FFT, false);
  assert.equal(second.autoRangeEnabled(), false, 'An individual plot opt-out remains effective');
  assert.equal(preferences.tuner.floor_db, -140);
  assert.throws(() => first.setAutoRangeEnabled('false'), /boolean/);
  assert.throws(() => first.set('unknown', false), /Unknown/);
  assert.throws(() => first.get('unknown'), /Unknown/);
});

test('failed service saves retain the confirmed display choice', async () => {
  const { createSpectrumDisplayPreferences } = await helper;
  const panel = createSpectrumDisplayPreferences({ read: () => ({ [FFT]: true, [WATERFALL]: true }),
    update: async () => { throw new Error('Save unavailable'); } });
  await assert.rejects(panel.setAutoRangeEnabled(false), /Save unavailable/);
  assert.equal(panel.autoRangeEnabled(), true);
  assert.throws(() => createSpectrumDisplayPreferences({}), /personal preference service/);
});
