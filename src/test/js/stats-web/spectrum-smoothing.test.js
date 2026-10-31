const assert = require('node:assert/strict');
const test = require('node:test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');
const fs = require('node:fs');
const vm = require('node:vm');

const helper = import(pathToFileURL(resolve(process.argv[2] ||
  'stats-web/assets/features/spectrum-smoothing.js')).href);

test('the initial frame is copied without sharing the raw spectrum buffer', async () => {
  const { smoothSpectrumValues } = await helper;
  const raw = Float32Array.of(-100, -80, -60);
  for (const previous of [undefined, null, new Float32Array(0)]) {
    const smoothed = smoothSpectrumValues(previous, raw);
    assert.ok(smoothed instanceof Float32Array);
    assert.deepEqual(smoothed, raw);
    assert.notEqual(smoothed.buffer, raw.buffer);
    smoothed[0] = 0;
    assert.equal(raw[0], -100);
  }
});

test('successive accepted frames produce the existing 25 percent exponential step response', async () => {
  const { smoothSpectrumValues } = await helper;
  const raw = Float32Array.of(-60, -40);
  const smoothed = smoothSpectrumValues(null, Float32Array.of(-100, -80));
  for (const expected of [[-90, -70], [-82.5, -62.5], [-76.875, -56.875], [-72.65625, -52.65625]]) {
    assert.equal(smoothSpectrumValues(smoothed, raw), smoothed, 'the established smoothing buffer is reused');
    assert.deepEqual(Array.from(smoothed), expected);
    assert.deepEqual(Array.from(raw), [-60, -40], 'accepted raw values remain available to other displays');
  }
});

test('bins and separate display histories update independently without changing incoming values', async () => {
  const { smoothSpectrumValues } = await helper;
  const initial = Float32Array.of(-100, -50, 0);
  const next = Float32Array.of(-60, -30, -20);
  const first = smoothSpectrumValues(null, initial);
  const second = smoothSpectrumValues(null, initial);
  smoothSpectrumValues(first, next);
  assert.deepEqual(Array.from(first), [-90, -45, -5]);
  assert.deepEqual(Array.from(second), [-100, -50, 0]);
  assert.deepEqual(Array.from(initial), [-100, -50, 0]);
  assert.deepEqual(Array.from(next), [-60, -30, -20]);
  smoothSpectrumValues(second, Float32Array.of(-140, -90, -40));
  assert.deepEqual(Array.from(second), [-110, -60, -10]);
  assert.deepEqual(Array.from(first), [-90, -45, -5]);
});

test('a changed bin count starts from a fresh copy instead of blending unrelated bins', async () => {
  const { smoothSpectrumValues } = await helper;
  const previous = Float32Array.of(-100, -80);
  const raw = Float32Array.of(-50, -40, -30);
  const resized = smoothSpectrumValues(previous, raw);
  assert.deepEqual(Array.from(resized), [-50, -40, -30]);
  assert.notEqual(resized.buffer, raw.buffer);
  assert.deepEqual(Array.from(previous), [-100, -80]);
  assert.deepEqual(Array.from(raw), [-50, -40, -30]);
  const empty = smoothSpectrumValues(resized, new Float32Array(0));
  assert.ok(empty instanceof Float32Array);
  assert.equal(empty.length, 0);
});

test('nonfinite values keep the existing current-frame fallback and recover on the next finite frame', async () => {
  const { smoothSpectrumValues } = await helper;
  const previous = Float32Array.of(NaN, Infinity, -Infinity, -100, -90, -80);
  const current = Float32Array.of(-70, -60, -50, NaN, Infinity, -Infinity);
  smoothSpectrumValues(previous, current);
  assert.deepEqual(Array.from(previous), [-70, -60, -50, NaN, Infinity, -Infinity]);
  assert.deepEqual(Array.from(current), [-70, -60, -50, NaN, Infinity, -Infinity]);
  smoothSpectrumValues(previous, Float32Array.of(-30, -20, -10, -60, -50, -40));
  assert.deepEqual(Array.from(previous), [-60, -50, -40, -60, -50, -40]);
});

test('passing the raw array as the initial history still produces an independent buffer', async () => {
  const { smoothSpectrumValues } = await helper;
  const raw = Float32Array.of(-95, -75);
  const smoothed = smoothSpectrumValues(raw, raw);
  assert.deepEqual(smoothed, raw);
  assert.notEqual(smoothed.buffer, raw.buffer);
});


test('the tuner adapter honors its toggle and resets the shared filter for every source-domain key', async () => {
  const { smoothSpectrumValues } = await helper;
  const source = fs.readFileSync(resolve(__dirname, '../../../../stats-web/assets/app.js'), 'utf8');
  const start = source.indexOf('function updateSpectrumSmoothing(values, frame, domain) {');
  assert.ok(start >= 0, 'the actual tuner smoothing adapter exists');
  let depth = 0;
  let end = source.indexOf('{', start);
  for (let index = end; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    if (source[index] === '}' && --depth === 0) {
      end = index + 1;
      break;
    }
  }
  const adapter = source.slice(start, end);
  const baseline = { startHz: 100, endHz: 200, rawBinWidthHz: 50,
    sourceBinCount: 2, transmittedBinCount: 2 };
  const harness = () => {
    const context = vm.createContext({
      Float32Array, smoothInput: { checked: true }, smoothedFftValues: new Float32Array(0),
      spectrumSmoothingKey: '', calls: 0,
      smoothSpectrumValues(previous, values) {
        context.calls += 1;
        return smoothSpectrumValues(previous, values);
      }
    });
    vm.runInContext(adapter, context);
    return context;
  };
  const panel = harness();
  panel.updateSpectrumSmoothing(Float32Array.of(-100, -80), { generation: 1 }, baseline);
  panel.updateSpectrumSmoothing(Float32Array.of(-60, -40), { generation: 1 }, baseline);
  assert.deepEqual(Array.from(panel.smoothedFftValues), [-90, -70]);
  assert.equal(panel.calls, 1);
  const held = panel.smoothedFftValues;
  const heldKey = panel.spectrumSmoothingKey;
  panel.smoothInput.checked = false;
  panel.updateSpectrumSmoothing(Float32Array.of(-20, 0), { generation: 2 }, { ...baseline, startHz: 110 });
  assert.equal(panel.smoothedFftValues, held);
  assert.deepEqual(Array.from(panel.smoothedFftValues), [-90, -70]);
  assert.equal(panel.spectrumSmoothingKey, heldKey);
  assert.equal(panel.calls, 1);
  panel.smoothInput.checked = true;
  panel.updateSpectrumSmoothing(Float32Array.of(-60, -40), { generation: 1 }, baseline);
  assert.deepEqual(Array.from(panel.smoothedFftValues), [-82.5, -62.5]);

  for (const changedKey of ['generation', ...Object.keys(baseline)]) {
    const resetPanel = harness();
    resetPanel.updateSpectrumSmoothing(Float32Array.of(-100, -80), { generation: 1 }, baseline);
    resetPanel.updateSpectrumSmoothing(Float32Array.of(-60, -40), { generation: 1 }, baseline);
    const nextFrame = { generation: changedKey === 'generation' ? 2 : 1 };
    const nextDomain = { ...baseline };
    if (changedKey !== 'generation') nextDomain[changedKey] += 1;
    const raw = Float32Array.of(-20, 0);
    resetPanel.updateSpectrumSmoothing(raw, nextFrame, nextDomain);
    assert.deepEqual(Array.from(resetPanel.smoothedFftValues), [-20, 0], changedKey + ' starts a fresh history');
    assert.notEqual(resetPanel.smoothedFftValues.buffer, raw.buffer);
    assert.equal(resetPanel.calls, 1, changedKey + ' does not blend across sources');
  }
});
