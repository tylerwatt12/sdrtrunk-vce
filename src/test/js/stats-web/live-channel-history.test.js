'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2] || path.resolve(__dirname,
  '../../../../stats-web/assets/app.js'), 'utf8');

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
        rows: new Map(), points: [], setTransform() {}, beginPath() {}, moveTo() {}, lineTo() {}, stroke() {},
        drawImage() {},
        fillRect: (x, y, width, height) => {
          if (this.offscreen) this.context.rows.clear();
          else if (this.context.fillStyle === '#65d6a6') this.context.points.push([x, y]);
          else this.context.points.length = 0;
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
    document: { hidden: false, createElement: tag => new Element(tag, true),
      addEventListener() {}, removeEventListener() {} },
    window: { devicePixelRatio: 1, requestAnimationFrame: callback => draws.push(callback),
      addEventListener: (name, callback) => windowEvents.set(name, callback), removeEventListener() {} },
    updateDiagnosticReadouts() {}, frequency: value => value / 1_000_000,
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
  const frame = (type, sequence, values, generation = 7) => {
    connection.callbacks.onFrame({ type, sequence, generation, values: Float32Array.from(values) });
    flush();
  };
  const waterfall = elements.find(element => element.offscreen);
  const symbols = elements.find(element => element.attributes['aria-label'] === 'Selected channel demodulated symbols');
  return { pane, state, frame, waterfall, symbols, select, connection: () => connection,
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
