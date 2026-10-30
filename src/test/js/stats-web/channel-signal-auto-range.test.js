const assert = require('node:assert/strict');
const test = require('node:test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');

const helper = import(pathToFileURL(resolve(process.argv[2] ||
  'stats-web/assets/features/channel-signal-auto-range.js')).href);

function spectrum({ noise = -90, signal = -60, edge = -165, signalWidth = 0.16, size = 512 } = {}, frame = 0) {
  return Float32Array.from({ length: size }, (_value, index) => {
    const offset = Math.abs(index / size - 0.5);
    const flutter = ((index * 17 + frame * 13) % 11 - 5) * 0.45;
    if (offset > 0.38) return edge + flutter;
    if (offset > 0.28) return noise - (offset - 0.28) / 0.1 * (noise - edge) + flutter;
    if (signal !== null && offset <= signalWidth / 2) return signal + flutter;
    return noise + flutter;
  });
}

function calibrate(range, options, transform = (values) => values) {
  let changed = false;
  for (let frame = 0; frame < 8; frame += 1)
    changed = range.observe(transform(spectrum(options, frame), frame)) || changed;
  return changed;
}

test('passed noise, rather than rejected outer bins, sets the channel range', async () => {
  const { createChannelSignalAutoRange } = await helper;
  const range = createChannelSignalAutoRange();
  assert.deepEqual(range.range(), { floorDb: -120, ceilingDb: 0 });
  assert.equal(calibrate(range, {}), true);
  const { floorDb, ceilingDb } = range.range();
  assert.ok(floorDb >= -96 && floorDb <= -92, `${floorDb} follows the -90 dB passed noise`);
  assert.ok(ceilingDb >= -56 && ceilingDb <= -52, `${ceilingDb} gives the -60 dB signal headroom`);
  assert.equal(range.pending(), false);
});

test('persistent control channel energy is preserved and the completed range is stable', async () => {
  const { createChannelSignalAutoRange } = await helper;
  const range = createChannelSignalAutoRange();
  calibrate(range, { noise: -100, signal: -55 });
  const captured = range.range();
  assert.ok(captured.floorDb < -100 && captured.ceilingDb > -55);
  for (let frame = 0; frame < 20; frame += 1)
    assert.equal(range.observe(spectrum({ noise: -60, signal: -20 }, frame)), false);
  assert.deepEqual(range.range(), captured);
  const external = range.range();
  external.floorDb = 100;
  assert.deepEqual(range.range(), captured, 'callers cannot mutate the held range');
});

test('weak signals retain a conservative display span without disappearing below the floor', async () => {
  const { createChannelSignalAutoRange } = await helper;
  const range = createChannelSignalAutoRange();
  calibrate(range, { noise: -90, signal: -84 });
  const { floorDb, ceilingDb } = range.range();
  assert.ok(ceilingDb - floorDb >= 32);
  assert.ok((-90 - floorDb) / (ceilingDb - floorDb) < 0.2);
  assert.ok(-84 > floorDb, 'the weak signal remains in the display range');
});

test('a short spike and an isolated persistent outlier do not determine the ceiling', async () => {
  const { createChannelSignalAutoRange } = await helper;
  const range = createChannelSignalAutoRange();
  calibrate(range, {}, (values, frame) => {
    if (frame === 2) values.fill(-2, 230, 280);
    values[100] = -1;
    return values;
  });
  assert.ok(range.range().ceilingDb <= -52);
  assert.ok(range.range().floorDb <= -92);
});

test('persistent narrow centered carriers retain peak headroom at one, three and five bins', async () => {
  const { createChannelSignalAutoRange } = await helper;
  for (const width of [1, 3, 5]) {
    const range = createChannelSignalAutoRange();
    calibrate(range, { noise: -95, signal: null }, (values) => {
      const first = values.length / 2 - Math.floor(width / 2);
      values.fill(-45, first, first + width);
      return values;
    });
    const { floorDb, ceilingDb } = range.range();
    assert.ok(floorDb >= -101 && floorDb <= -97, `${width}-bin carrier preserves the passed-noise floor`);
    assert.equal(ceilingDb, -39, `${width}-bin persistent carrier receives 6 dB of headroom`);
  }
});

test('unfiltered noise and spectra with only one rejected edge still calibrate conservatively', async () => {
  const { createChannelSignalAutoRange } = await helper;
  for (const mode of ['unfiltered', 'one-edge']) {
    const range = createChannelSignalAutoRange();
    calibrate(range, {}, (values, frame) => {
      for (let index = 0; index < values.length; index += 1) {
        if ((mode === 'unfiltered' && Math.abs(index / values.length - 0.5) > 0.08) ||
            (mode === 'one-edge' && index > values.length * 0.6))
          values[index] = -90 + ((index + frame) % 5 - 2) * 0.5;
      }
      return values;
    });
    assert.ok(range.range().floorDb >= -96 && range.range().floorDb <= -92);
    assert.ok(range.range().ceilingDb - range.range().floorDb >= 32);
  }
});

test('lower noise shoulders stay the floor when the centered signal occupies most of the passband', async () => {
  const { createChannelSignalAutoRange } = await helper;
  for (const signalWidth of [0.4, 0.5]) {
    const range = createChannelSignalAutoRange();
    calibrate(range, { noise: -90, signal: -60, signalWidth });
    const { floorDb, ceilingDb } = range.range();
    assert.ok(floorDb >= -97 && floorDb <= -92, `${signalWidth} width keeps the lower supported noise plateau`);
    assert.ok(ceilingDb >= -56 && ceilingDb <= -52);
    assert.ok((-60 - floorDb) / (ceilingDb - floorDb) > 0.75, 'the broad signal remains bright');
  }
});

test('noise-only and full-passband signals without identifiable shoulders retain the safe range', async () => {
  const { createChannelSignalAutoRange } = await helper;
  for (const options of [{ signal: null }, { signalWidth: 0.56 }, { signalWidth: 0.8 }]) {
    const range = createChannelSignalAutoRange();
    assert.equal(calibrate(range, options), false);
    assert.deepEqual(range.range(), { floorDb: -120, ceilingDb: 0 });
    assert.equal(range.pending(), false);
  }
  const held = createChannelSignalAutoRange();
  calibrate(held, {});
  const captured = held.range();
  held.reset({ keepRange: true });
  assert.equal(calibrate(held, { noise: -70, signal: null }), false);
  assert.deepEqual(held.range(), captured, 'uncertain recalibration preserves the previous safe range');
});

test('empty, invalid and transport-empty frames do not advance calibration', async () => {
  const { createChannelSignalAutoRange } = await helper;
  const range = createChannelSignalAutoRange();
  for (let frame = 0; frame < 10; frame += 1) {
    for (const invalid of [null, [], new Float32Array(512).fill(-196),
      new Float32Array(512).fill(NaN), new Float32Array(512).fill(Infinity)])
      assert.equal(range.observe(invalid), false);
  }
  assert.equal(range.pending(), true);
  for (let frame = 0; frame < 7; frame += 1) assert.equal(range.observe(spectrum({}, frame)), false);
  assert.equal(range.pending(), true);
  assert.equal(range.observe(spectrum({}, 7)), true);
});

test('recalibration may retain the previous range while source resets restore the default', async () => {
  const { createChannelSignalAutoRange } = await helper;
  const range = createChannelSignalAutoRange();
  calibrate(range, {});
  const original = range.range();
  range.reset({ keepRange: true });
  assert.equal(range.pending(), true);
  assert.deepEqual(range.range(), original);
  calibrate(range, { noise: -110, signal: -75 });
  assert.ok(range.range().floorDb < original.floorDb);
  range.reset();
  assert.deepEqual(range.range(), { floorDb: -120, ceilingDb: 0 });
  assert.equal(range.pending(), true);
});

test('a changed bin count discards partial calibration and impossible plateaus use the safe fallback', async () => {
  const { createChannelSignalAutoRange } = await helper;
  const range = createChannelSignalAutoRange();
  for (let frame = 0; frame < 7; frame += 1) range.observe(spectrum({}, frame));
  assert.equal(range.observe(spectrum({ size: 256 })), false);
  assert.equal(range.pending(), true);
  for (let frame = 1; frame < 8; frame += 1) range.observe(spectrum({ size: 256 }, frame));
  assert.equal(range.pending(), false);
  range.reset();
  for (let frame = 0; frame < 8; frame += 1)
    range.observe(Float32Array.from({ length: 16 }, (_value, index) => -180 + index * 10));
  assert.deepEqual(range.range(), { floorDb: -120, ceilingDb: 0 });
  assert.equal(range.pending(), false);
});
