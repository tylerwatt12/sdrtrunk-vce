'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2] || path.resolve(__dirname,
  '../../../../stats-web/assets/app.js'), 'utf8');
const rangeSource = fs.readFileSync(path.resolve(__dirname,
  '../../../../stats-web/assets/features/channel-signal-auto-range.js'), 'utf8').replace(/^export /gm, '');
const smoothingSource = fs.readFileSync(path.resolve(__dirname,
  '../../../../stats-web/assets/features/spectrum-smoothing.js'), 'utf8').replace(/^export /gm, '');

function functionSource(name) {
  const start = source.indexOf(`function ${name}(`);
  assert.ok(start >= 0, name);
  const end = source.indexOf('\nfunction ', start + 1);
  return source.slice(start, end < 0 ? source.length : end);
}

function harness() {
  const elements = [];
  const draws = [];
  const windowEvents = new Map();
  const size = { width: 800, height: 180 };
  let connection;
  let nextSubscription = 0;
  class Element {
    constructor(tag, offscreen = false) {
      this.tag = tag;
      this.offscreen = offscreen;
      this.children = [];
      this.dataset = {};
      this.attributes = {};
      this.events = new Map();
      this.classList = { toggle() {} };
      this._width = 300;
      this._height = 150;
      if (tag === 'canvas') this.context = {
        rows: new Map(), points: [], trace: [], setTransform() {}, beginPath() {},
        moveTo: (x, y) => { if (this.context.strokeStyle === '#55c7ff') this.context.trace.push([x, y]); },
        lineTo: (x, y) => { if (this.context.strokeStyle === '#55c7ff') this.context.trace.push([x, y]); },
        stroke() {},
        drawImage() {},
        fillRect: (x, y, width, height) => {
          if (this.offscreen) this.context.rows.clear();
          else if (this.context.fillStyle === '#65d6a6') this.context.points.push([x, y]);
          else { this.context.points.length = 0; this.context.trace.length = 0; }
        },
        createImageData: (width, height) => ({ data: new Uint8ClampedArray(width * height * 4) }),
        putImageData: (image, _x, y) => this.context.rows.set(y, Array.from(image.data))
      };
      elements.push(this);
    }
    append(...children) { this.children.push(...children); }
    setAttribute(name, value) { this.attributes[name] = value; }
    addEventListener(name, callback) { this.events.set(name, callback); }
    click() { this.events.get('click')?.(); }
    getBoundingClientRect() { return size; }
    getContext() { return this.context; }
    set width(value) { this._width = value; this.context?.rows.clear(); }
    get width() { return this._width; }
    set height(value) { this._height = value; this.context?.rows.clear(); }
    get height() { return this._height; }
  }
  const context = vm.createContext({
    Float32Array, Uint8ClampedArray,
    node: (tag, className = '', textContent = '') => Object.assign(new Element(tag), { className, textContent }),
    badge: textContent => Object.assign(new Element('span'), { textContent }),
    iconButton: (_icon, label) => { const button = new Element('button');
      button.setAttribute('aria-label', label); return button; },
    document: { hidden: false, createElement: tag => new Element(tag, true),
      addEventListener() {}, removeEventListener() {} },
    window: { devicePixelRatio: 1, requestAnimationFrame: callback => draws.push(callback),
      addEventListener: (name, callback) => windowEvents.set(name, callback), removeEventListener() {} },
    updateDiagnosticReadouts: (element, values) => { element.readouts = values; },
    frequency: value => value / 1_000_000,
    tunerWaterfallPalette: () => Uint8ClampedArray.from({ length: 1024 }, (_, index) => index % 256),
    DIAGNOSTIC_FRAME_TYPES: { HEARTBEAT: 0, STATE: 1, CHANNEL_SIGNAL: 2, CHANNEL_SYMBOLS: 3 },
    diagnosticJsonPayload: frame => frame.state, diagnosticFloatPayload: frame => frame.values,
    diagnosticFrameLatency: () => 0, randomLiveClientId: () => `subscription-${++nextSubscription}`,
    liveConnections: new Set(), pageConnections: new Set(),
    LIVE_DETAIL_SELECTION_KINDS: { CONVENTIONAL: 'conventional' },
    binaryFrameConnection: (_topic, parameters, callbacks) => {
      connection = { parameters, callbacks, close() {}, update(next) { this.parameters = next; } };
      return connection;
    }
  });
  vm.runInContext(rangeSource, context);
  context.smoothSpectrumValues = vm.runInContext(`(() => { ${smoothingSource}\nreturn smoothSpectrumValues; })()`, context);
  for (const name of ['liveDetailSelectionDelta', 'liveDetailTransportParameters',
    'liveChannelStateMatchesSelection', 'liveChannelPane']) vm.runInContext(functionSource(name), context);
  const pane = context.liveChannelPane();
  const select = (suffix = 'a', frequency = 851_012_500) => pane.select({ logicalKey: suffix, transportKey: suffix,
    configurationId: 'configuration', bindingFrequencyHz: frequency, bindingTimeslot: null });
  const flush = () => { while (draws.length) draws.shift()(); };
  select();
  pane.setActive(true);
  elements.find(element => element.dataset.view === 'waterfall').click();
  const state = (generation = 7, overrides = {}, subscription = connection.parameters.subscription_id) => {
    connection.callbacks.onFrame({ type: 1, generation, state: { configuration_id: 'configuration',
      frequency_hz: connection.parameters.frequency_hz, subscription_id: subscription,
      signal_state: 'live', symbols_state: 'live', maximum_visible_symbols: 8, ...overrides } });
    flush();
  };
  const frame = (type, sequence, values, generation = 7, metadata = {}) => {
    connection.callbacks.onFrame({ type, sequence, generation, ...metadata, values: Float32Array.from(values) });
    flush();
  };
  const waterfall = elements.find(element => element.offscreen);
  const symbols = elements.find(element => element.attributes['aria-label'] === 'Selected channel demodulated symbols');
  const signal = elements.find(element => element.tag === 'canvas' && !element.offscreen);
  const signalReadouts = elements.find(element => element.readouts?.[0]?.[0] === 'Center');
  const rangeButton = elements.find(element => element.attributes['aria-label'] === 'Recalculate signal display range');
  return { pane, state, frame, waterfall, symbols, signal, rangeButton, select, connection: () => connection,
    readout: name => signalReadouts.readouts.find(([label]) => label === name)?.[1],
    view(name) { elements.find(element => element.dataset.view === name).click(); flush(); },
    recalculate() { rangeButton.click(); flush(); },
    resize(width, ratio) { size.width = width; context.window.devicePixelRatio = ratio;
      windowEvents.get('resize')(); flush(); } };
}

test('metadata-only state updates preserve half-filled symbols, waterfall and sequence guards', () => {
  const h = harness();
  try {
    h.state();
    h.frame(2, 1, [-100, -80, -40, -20]);
    h.frame(3, 1, [0, 0.1, 0.2, 0.3]);
    const firstRow = [...h.waterfall.context.rows.values()][0];
    assert.equal(h.symbols.context.points.length, 4);
    h.state(7, { revision: 2, frequency_correction: { channel_correction_hz: -6 } });
    assert.equal(h.symbols.context.points.length, 4, 'Correction metadata must not erase half of the symbol window');
    assert.deepEqual([...h.waterfall.context.rows.values()], [firstRow]);
    h.frame(2, 1, [-20, -20, -20, -20]);
    h.frame(3, 1, [1, 1, 1, 1]);
    assert.equal(h.waterfall.context.rows.size, 1, 'A metadata revision must not admit duplicate FFT frames');
    assert.equal(h.symbols.context.points.length, 4, 'A metadata revision must not admit duplicate symbol frames');
    h.frame(2, 2, [-90, -70, -30, -10]);
    h.frame(3, 2, [0.4, 0.5, 0.6, 0.7]);
    assert.equal(h.waterfall.context.rows.size, 2);
    assert.equal(h.symbols.context.points.length, 8);
    assert.equal(h.symbols.context.points.at(-1)[0], 798.5, 'Symbols reach the far edge instead of restarting halfway');
    h.resize(900, 2);
    assert.equal(h.waterfall.context.rows.size, 2, 'Canvas/DPR resizing retains the offscreen waterfall');
    assert.equal(h.symbols.context.points.length, 8);
    assert.equal(h.symbols.context.points.at(-1)[0], 898.5);
    assert.equal(h.symbols.width, 1800);
  } finally { h.pane.close(); }
});

test('new producer generations clear histories and reject late frames from the old producer', () => {
  const h = harness();
  try {
    h.state(); h.frame(2, 10, [-80, -40]); h.frame(3, 10, [0, 1]);
    h.state(8);
    assert.equal(h.waterfall.context.rows.size, 0);
    assert.equal(h.symbols.context.points.length, 0);
    h.frame(2, 11, [-30, -20]); h.frame(3, 11, [1, 2]);
    assert.equal(h.waterfall.context.rows.size, 0);
    assert.equal(h.symbols.context.points.length, 0);
    h.frame(2, 1, [-90, -50], 8); h.frame(3, 1, [0.1, 0.2], 8);
    assert.equal(h.waterfall.context.rows.size, 1);
    assert.equal(h.symbols.context.points.length, 2);
  } finally { h.pane.close(); }
});

test('symbol-window changes and real selection changes reset only the relevant histories', () => {
  const h = harness();
  try {
    h.state(); h.frame(2, 1, [-80, -40]); h.frame(3, 1, [0, 1]);
    h.state(7, { maximum_visible_symbols: 4 });
    assert.equal(h.waterfall.context.rows.size, 1, 'A symbol-window change does not erase signal history');
    assert.equal(h.symbols.context.points.length, 0);
    h.frame(3, 2, [0, 0.1, 0.2, 0.3]);
    assert.equal(h.symbols.context.points.length, 4);
    const oldSubscription = h.connection().parameters.subscription_id;
    h.select('b', 852_012_500);
    h.state(7, {}, oldSubscription);
    h.frame(2, 3, [-70, -30]); h.frame(3, 3, [0, 1]);
    assert.equal(h.waterfall.context.rows.size, 0);
    assert.equal(h.symbols.context.points.length, 0);
    h.state(); h.frame(2, 1, [-70, -30]); h.frame(3, 1, [0, 1]);
    assert.equal(h.waterfall.context.rows.size, 1);
    assert.equal(h.symbols.context.points.length, 2);
  } finally { h.pane.close(); }
});

test('reconnecting to a restarted producer with the same generation resets its sequence baseline', () => {
  const h = harness();
  try {
    h.state(); h.frame(2, 100, [-80, -40]); h.frame(3, 100, [0, 0.1]);
    h.connection().callbacks.onError(new Error('The live connection ended.'));
    h.connection().callbacks.onOpen();
    h.state(7);
    assert.equal(h.waterfall.context.rows.size, 0, 'The first state of a new connection is a fresh source boundary');
    assert.equal(h.symbols.context.points.length, 0);
    h.frame(2, 1, [-70, -30]); h.frame(3, 1, [1, 2]);
    assert.equal(h.waterfall.context.rows.size, 1, 'Restarted sequence one must not be rejected as old');
    assert.equal(h.symbols.context.points.length, 2);
    h.state(7, { frequency_correction: { channel_correction_hz: -6 } });
    assert.equal(h.waterfall.context.rows.size, 1, 'Later metadata on that connection still preserves history');
    assert.equal(h.symbols.context.points.length, 2);
  } finally { h.pane.close(); }
});

function channelSpectrum({ bins = 128, noiseDb = -86, signalDb = -59 } = {}) {
  return Array.from({ length: bins }, (_, index) => {
    const offset = Math.abs(index - bins / 2) / bins;
    if (offset > 0.35) return -155;
    if (offset < 0.055) return signalDb;
    return noiseDb + (index % 3 - 1);
  });
}

function displayRange(h) {
  const match = /^(-?\d+(?:\.\d+)?) to (-?\d+(?:\.\d+)?) dB$/.exec(h.readout('Display range'));
  assert.ok(match, `Expected a calibrated display range, got ${h.readout('Display range')}`);
  return { floorDb: Number(match[1]), ceilingDb: Number(match[2]) };
}

function expectedColor(raw, { floorDb, ceilingDb }) {
  const clamped = Math.max(floorDb, Math.min(ceilingDb, raw));
  const color = Math.round((clamped - floorDb) / (ceilingDb - floorDb) * 255);
  return [color * 4 % 256, (color * 4 + 1) % 256, (color * 4 + 2) % 256, 255];
}

test('channel calibration recolors retained waterfall rows and shares its range with the FFT', () => {
  const h = harness();
  try {
    h.state();
    const values = channelSpectrum();
    h.frame(2, 1, values);
    const uncalibrated = h.waterfall.context.rows.get(255).slice();
    assert.equal(h.readout('Display range'), 'Calibrating…');
    assert.equal(h.rangeButton.disabled, true);
    for (let sequence = 2; sequence <= 8; sequence++) h.frame(2, sequence, values);
    const range = displayRange(h);
    assert.ok(range.floorDb >= -85 && range.floorDb <= -83,
      'Display floor sits about 2 dB above the passed noise instead of following suppressed outer bins');
    assert.equal(range.ceilingDb, 0, 'Automatic floor calibration keeps the upper display limit fixed');
    assert.equal(h.rangeButton.disabled, false);
    assert.equal(h.readout('Peak'), '-59.0 dB', 'Display scaling does not alter the measured peak');
    const calibrated = h.waterfall.context.rows.get(255);
    assert.notDeepEqual(calibrated, uncalibrated, 'Existing history is repainted when calibration finishes');
    for (const row of h.waterfall.context.rows.values()) assert.deepEqual(row, calibrated);
    assert.equal(h.waterfall.context.rows.size, 8);
    const center = values.length / 2;
    assert.deepEqual(calibrated.slice(center * 4, center * 4 + 4), expectedColor(values[center], range));
    const minimumColor = expectedColor(range.floorDb, range);
    assert.deepEqual(calibrated.slice(32 * 4, 32 * 4 + 4), minimumColor,
      'The broad passed noise shelf is painted at the palette minimum');
    assert.notDeepEqual(calibrated.slice(center * 4, center * 4 + 4), minimumColor,
      'The wanted signal remains visible above the display floor');
    h.view('fft');
    assert.equal(h.signal.context.trace.length, values.length);
    const expectedY = (range.ceilingDb - values[center]) / (range.ceilingDb - range.floorDb) * 180;
    assert.ok(Math.abs(h.signal.context.trace[center][1] - expectedY) < 1e-8,
      'FFT positions use the same limits as waterfall colors');
    assert.equal(h.signal.context.trace[32][1], 180, 'FFT noise below the floor is clamped to its baseline');
    const withRolloff = values.slice();
    const rolloff = [-150, -130, -115, -101, -95, -89, -86];
    withRolloff.splice(18, rolloff.length, ...rolloff);
    h.frame(2, 9, withRolloff);
    const rolloffRow = h.waterfall.context.rows.get(247);
    for (let index = 18; index < 18 + rolloff.length; index++)
      assert.deepEqual(rolloffRow.slice(index * 4, index * 4 + 4), minimumColor,
        'Channelizer rolloff below the passed noise uses the same minimum color');
    const retainedRange = h.readout('Display range');
    h.state(7, { revision: 2, frequency_correction: { channel_correction_hz: -6 } });
    h.frame(2, 10, channelSpectrum({ noiseDb: -60, signalDb: -30 }));
    assert.equal(h.readout('Display range'), retainedRange, 'Later signal variations do not pump the range');
    assert.deepEqual(h.waterfall.context.rows.get(255), calibrated);
    h.frame(2, 11, channelSpectrum({ noiseDb: -50, signalDb: -20 }), 6);
    assert.equal(h.waterfall.context.rows.size, 10, 'A stale producer cannot affect calibrated history');
    assert.equal(h.readout('Display range'), retainedRange);
  } finally { h.pane.close(); }
});

test('manual recalibration keeps the current colors until the new range can repaint all history', () => {
  const h = harness();
  try {
    h.state();
    const initial = channelSpectrum();
    for (let sequence = 1; sequence <= 8; sequence++) h.frame(2, sequence, initial);
    const beforeRange = displayRange(h);
    const beforeRow = h.waterfall.context.rows.get(255).slice();
    h.recalculate();
    assert.equal(h.readout('Display range'), 'Calibrating…');
    assert.equal(h.rangeButton.disabled, true);
    assert.deepEqual(h.waterfall.context.rows.get(255), beforeRow);
    const shifted = channelSpectrum({ noiseDb: -76, signalDb: -49 });
    for (let sequence = 9; sequence <= 15; sequence++) h.frame(2, sequence, shifted);
    assert.equal(h.readout('Display range'), 'Calibrating…');
    assert.deepEqual(h.waterfall.context.rows.get(255), beforeRow, 'Collecting samples preserves the existing scale');
    h.frame(2, 16, shifted);
    const afterRange = displayRange(h);
    assert.notDeepEqual(afterRange, beforeRange);
    assert.equal(beforeRange.ceilingDb, 0);
    assert.equal(afterRange.ceilingDb, 0, 'Manual recalibration also adjusts only the floor');
    const center = initial.length / 2;
    assert.deepEqual(h.waterfall.context.rows.get(255).slice(center * 4, center * 4 + 4),
      expectedColor(initial[center], afterRange), 'Original dB samples survive a display-range change');
    assert.deepEqual(h.waterfall.context.rows.get(240).slice(center * 4, center * 4 + 4),
      expectedColor(shifted[center], afterRange));
    assert.equal(h.waterfall.context.rows.size, 16);
  } finally { h.pane.close(); }
});

test('signal source changes recalibrate signal history while preserving symbols and sequence guards', () => {
  const h = harness();
  try {
    h.state(7, { sample_rate_hz: 50_000, fft_size: 128 });
    const values = channelSpectrum();
    const metadata = { sampleRateHz: 50_000, centerFrequencyHz: 851_012_500 };
    for (let sequence = 1; sequence <= 8; sequence++) h.frame(2, sequence, values, 7, metadata);
    h.frame(3, 1, [0, 1]);
    displayRange(h);
    h.state(7, { sample_rate_hz: 100_000, fft_size: 128 });
    assert.equal(h.waterfall.context.rows.size, 0);
    assert.equal(h.readout('Display range'), '—');
    assert.equal(h.symbols.context.points.length, 2);
    h.frame(2, 8, values, 7, metadata);
    assert.equal(h.waterfall.context.rows.size, 0, 'A state-only source change does not reset duplicate protection');
    h.frame(2, 9, values, 7, { ...metadata, sampleRateHz: 100_000 });
    assert.equal(h.waterfall.context.rows.size, 1);
    assert.equal(h.readout('Display range'), 'Calibrating…');
    h.frame(2, 10, channelSpectrum({ bins: 256 }), 7, { ...metadata, sampleRateHz: 100_000 });
    assert.equal(h.waterfall.width, 256);
    assert.equal(h.waterfall.context.rows.size, 1, 'Changing frame bin count clears incompatible signal history');
    assert.equal(h.symbols.context.points.length, 2);
    h.frame(2, 10, channelSpectrum({ bins: 256 }), 7, { ...metadata, sampleRateHz: 100_000 });
    assert.equal(h.waterfall.context.rows.size, 1);
    h.frame(2, 11, channelSpectrum({ bins: 256 }), 7,
      { sampleRateHz: 96_000, centerFrequencyHz: 851_037_500 });
    assert.equal(h.waterfall.context.rows.size, 1, 'Frame domain metadata also invalidates calibration');
    assert.equal(h.symbols.context.points.length, 2);
    h.frame(3, 1, [1, 2]);
    assert.equal(h.symbols.context.points.length, 2, 'Signal-only changes retain symbol duplicate protection');
  } finally { h.pane.close(); }
});

test('the raw waterfall ring remains bounded while wrapping and repainting', () => {
  const h = harness();
  try {
    h.state();
    const values = channelSpectrum();
    for (let sequence = 1; sequence <= 300; sequence++) h.frame(2, sequence, values);
    assert.equal(h.waterfall.context.rows.size, 256);
    h.recalculate();
    for (let sequence = 301; sequence <= 308; sequence++)
      h.frame(2, sequence, channelSpectrum({ noiseDb: -76, signalDb: -49 }));
    assert.equal(h.waterfall.context.rows.size, 256);
    const range = displayRange(h);
    const center = values.length / 2;
    assert.deepEqual(h.waterfall.context.rows.get(0).slice(center * 4, center * 4 + 4),
      expectedColor(values[center], range), 'Wrapped older rows are repainted from their retained dB values');
  } finally { h.pane.close(); }
});

function expectFftValues(h, expected) {
  assert.equal(h.signal.context.trace.length, expected.length);
  expected.forEach((value, index) => {
    const actualDb = -h.signal.context.trace[index][1] / 180 * 120;
    assert.ok(Math.abs(actualDb - value) < 1e-5,
      `FFT bin ${index}: expected ${value} dB, got ${actualDb} dB`);
  });
}

test('Live FFT smoothing advances while Waterfall is selected without smoothing raw power or history', () => {
  const h = harness();
  try {
    h.state();
    h.frame(2, 1, [-100, -80, -90, -70]);
    h.frame(2, 2, [-60, -40, -50, -30]);
    assert.equal(h.readout('Peak'), '-30.0 dB', 'Peak remains the latest received power');
    const defaultRange = { floorDb: -120, ceilingDb: 0 };
    assert.deepEqual(h.waterfall.context.rows.get(254).slice(0, 4), expectedColor(-60, defaultRange),
      'Waterfall records the current raw frame instead of its smoothed FFT');
    assert.deepEqual(h.waterfall.context.rows.get(255).slice(0, 4), expectedColor(-100, defaultRange));
    h.view('fft');
    expectFftValues(h, [-90, -70, -80, -60]);
    h.frame(2, 3, [-60, -40, -50, -30]);
    expectFftValues(h, [-82.5, -62.5, -72.5, -52.5]);
  } finally { h.pane.close(); }
});

test('Live smoothing ignores duplicate and stale frames and never advances on redraws', () => {
  const h = harness();
  try {
    h.state();
    h.view('fft');
    h.frame(2, 1, [-100, -80]);
    h.frame(2, 2, [-60, -40]);
    expectFftValues(h, [-90, -70]);
    h.frame(2, 2, [-10, -10]);
    h.frame(2, 3, [-10, -10], 6);
    h.frame(2, 3, []);
    expectFftValues(h, [-90, -70]);
    assert.equal(h.waterfall.context.rows.size, 2);
    h.view('waterfall');
    h.view('fft');
    h.resize(900, 2);
    h.state(7, { revision: 2, frequency_correction: { channel_correction_hz: -6 } });
    expectFftValues(h, [-90, -70]);
    h.frame(2, 4, [-60, -40]);
    expectFftValues(h, [-82.5, -62.5]);
    assert.equal(h.waterfall.context.rows.size, 3);
  } finally { h.pane.close(); }
});

test('Live smoothing starts fresh after source, bin-count, producer, reconnect and selection changes', () => {
  const h = harness();
  try {
    h.state(7, { sample_rate_hz: 50_000, fft_size: 2 });
    h.view('fft');
    const metadata = { sampleRateHz: 50_000, centerFrequencyHz: 851_012_500 };
    h.frame(2, 1, [-100, -80], 7, metadata);
    h.frame(2, 2, [-60, -40], 7, metadata);
    expectFftValues(h, [-90, -70]);
    h.state(7, { sample_rate_hz: 100_000, fft_size: 2 });
    const changedRate = { ...metadata, sampleRateHz: 100_000 };
    h.frame(2, 3, [-50, -30], 7, changedRate);
    expectFftValues(h, [-50, -30]);
    h.frame(2, 4, [-90, -70], 7, changedRate);
    expectFftValues(h, [-60, -40]);
    h.frame(2, 5, [-110, -90, -70, -50], 7, changedRate);
    expectFftValues(h, [-110, -90, -70, -50]);
    h.frame(2, 6, [-80, -60, -40, -20], 7,
      { ...changedRate, centerFrequencyHz: 851_037_500 });
    expectFftValues(h, [-80, -60, -40, -20]);
    h.state(8, { sample_rate_hz: 100_000, fft_size: 4 });
    h.frame(2, 1, [-40, -20, -80, -60], 8, changedRate);
    expectFftValues(h, [-40, -20, -80, -60]);
    h.connection().callbacks.onError(new Error('The live connection ended.'));
    h.connection().callbacks.onOpen();
    h.state(8, { sample_rate_hz: 100_000, fft_size: 4 });
    h.frame(2, 1, [-100, -80, -60, -40], 8, changedRate);
    expectFftValues(h, [-100, -80, -60, -40]);
    h.select('b', 852_012_500);
    h.state(8, { sample_rate_hz: 100_000, fft_size: 4 });
    h.frame(2, 1, [-70, -50, -30, -10], 8,
      { ...changedRate, centerFrequencyHz: 852_012_500 });
    expectFftValues(h, [-70, -50, -30, -10]);
  } finally { h.pane.close(); }
});

test('real multiplex ready events reopen diagnostic callbacks while ordinary STATE frames do not', () => {
  let target;
  let opens = 0;
  let frames = 0;
  const context = vm.createContext({
    Uint8Array, DataView, TextDecoder, Error,
    LIVE_MULTIPLEX_DECODER: new TextDecoder(), DIAGNOSTIC_FRAME_HEADER_BYTES: 64,
    DIAGNOSTIC_FRAME_MAXIMUM_BYTES: 16 * 1024 * 1024, DIAGNOSTIC_FRAME_MAGIC: 0x53444447,
    liveConnections: new Set(), pageConnections: new Set(),
    invokeLiveSubscriber: (subscriber, name, ...args) => subscriber[name]?.(...args),
    liveMultiplexer: { subscribe(_topic, _parameters, callbacks) { target = callbacks; return { close() {} }; } }
  });
  for (const name of ['decodeDiagnosticFrame', 'binaryFrameConnection']) vm.runInContext(functionSource(name), context);
  context.binaryFrameConnection('channel_diagnostics', {}, { onOpen: () => opens++, onFrame: () => frames++ });
  const start = source.indexOf('  dispatch(topic, kind, payload) {');
  const end = source.indexOf('\n  dispatchError(', start);
  assert.ok(start >= 0 && end > start);
  const dispatch = vm.runInContext(`(function ${source.slice(start, end).trim()})`, context);
  const transport = { clientId: 'first', failedTopics: new Set(),
    subscribers: new Map([['channel_diagnostics', new Set([target])]]), queueControl() {} };
  const state = new Uint8Array(64);
  const header = new DataView(state.buffer);
  header.setUint32(0, 0x53444447, true); header.setUint8(4, 1); header.setUint8(5, 1);
  header.setUint16(6, 64, true); header.setBigInt64(16, 7n, true);
  for (const [index, clientId] of ['first', 'reconnected'].entries()) {
    transport.clientId = clientId;
    dispatch.call(transport, 'control', 1, Buffer.from(JSON.stringify({ event: 'ready', data: { client_id: clientId } })));
    assert.equal(opens, index + 1);
    dispatch.call(transport, 'channel_diagnostics', 2, state);
    assert.equal(opens, index + 1, 'Metadata STATE frames do not signal a transport reopen');
    assert.equal(frames, index + 1);
  }
});
