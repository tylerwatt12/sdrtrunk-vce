'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const applicationPath = process.argv[2];
assert.ok(applicationPath, 'The app.js path is required.');
const source = fs.readFileSync(applicationPath, 'utf8');

function functionSource(signature) {
  const start = source.indexOf(signature);
  assert.ok(start >= 0, signature);
  const opening = source.indexOf('{', start + signature.length);
  let depth = 0;
  for (let index = opening; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    else if (source[index] === '}' && --depth === 0) return source.slice(start, index + 1);
  }
  throw new Error('Unterminated ' + signature);
}

class Element {
  constructor(tag = 'div', className = '', text = '') {
    Object.assign(this, { tag, className, textContent: text, children: [], style: {}, attributes: {},
      listeners: {}, hidden: false, checked: false });
  }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  setAttribute(key, value) { this.attributes[key] = value; }
  addEventListener(type, callback) { this.listeners[type] = callback; }
  dispatch(type) { this.listeners[type]?.({ currentTarget: this }); }
  getBoundingClientRect() { return { width: 800 }; }
}

function harness(liveAllowed = true) {
  let preferences = { tuner: { snap_frequency: true, smooth_fft: true,
    highlight_waterfall_channels: false, show_idle_channels: false } };
  let subscriber;
  let closed = 0;
  const context = {
    node: (...args) => new Element(...args),
    speedControl: new Element('label', '', 'Waterfall speed'),
    ACCESS_CAPABILITIES: { LIVE: 'live' }, capabilityAllowed: () => liveAllowed,
    activeUserPreferences: () => preferences,
    settleUserPreferenceMutation: (mutate) => mutate(preferences),
    viewport: { startHz: 150_000_000, endHz: 151_000_000 },
    activeChannelTables: new Map(), activeChannelSource: null, activeFlagSignature: '',
    spectrumActiveFlags: new Element(), waterfallActiveFlags: new Element(),
    waterfall: { host: new Element(), guide: new Element() }, spectrum: { guide: new Element() },
    hoverFlag: null, hoverRatio: null, hoverCanvas: null, hoverYRatio: null,
    cursorChannel: new Element(), cursorSnap: new Element(), cursorPower: new Element(),
    cursorFrequency: new Element(), cursorPopup: new Element(),
    shouldRun: () => true,
    subscribeLiveChannelActivity: (callbacks) => {
      subscriber = callbacks;
      return { close: () => { closed += 1; } };
    },
    decoderLabel: (value) => value || '',
    activeCarrierPower: () => null,
    setCursorGuide: () => {}, positionCursorPopup: () => {},
    updateCursor: () => {},
    frequencySelectionForCarrier: (carrier) => carrier,
    openTunerFrequencyActions: () => {},
    hideCursor: () => { context.hoverFlag = null; context.cursorPopup.hidden = true; }
  };
  context.activeFlagLayers = [context.spectrumActiveFlags, context.waterfallActiveFlags];
  vm.createContext(context);
  vm.runInContext([
    ...source.matchAll(/^const TUNER_(?:SPECTRUM_(?:SNAP|SMOOTH|IDLE)_PREFERENCE|WATERFALL_CHANNELS_PREFERENCE|CHANNEL_\w+) = .*;$/gm)
  ].map((match) => match[0]).join('\n'), context);
  vm.runInContext(source.slice(source.indexOf('const TUNER_ACTIVITY_PRIORITY'),
    source.indexOf('const RADIO_REFERENCE_DETAIL_CACHE_LIMIT')), context);
  [
    'function channelTagSet(...values)', 'function tunerStoredBoolean(key, fallback)',
    'function storeTunerBoolean(key, value)', 'function tunerActivityStatus(row, includeIdle = false)',
    'function updateSpectrumActivityTable(table)', 'function activeCarriers(includeIdle = false)',
    'function activityValues(rows, selector)', 'function activityTokenLabel(value)',
    'function targetIdentifierLabel(form)', 'function activityAliasLabel(row, prefix)',
    'function activeCarrierFields(carrier, fftPower = null)', 'function activeCarrierDescription(carrier)',
    'function renderActiveCarrierFields(carrier, power)', 'function showActiveFlag(carrier, flag)',
    'function hideActiveFlag(flag)', 'function renderActiveChannels()',
    'function connectActiveChannels()', 'function closeActiveChannels()'
  ].forEach((signature) => vm.runInContext(functionSource(signature), context));
  vm.runInContext(source.slice(source.indexOf("  const snapControl = node('label', 'tuner-spectrum-toggle-control')"),
    source.indexOf("  const profilePanel = node('fieldset', 'tuner-spectrum-profile')")), context);
  vm.runInContext(source.slice(source.indexOf("  waterfallChannelsInput.addEventListener('change'"),
    source.indexOf('  [spectrum.canvas, waterfall.canvas].forEach(addPlotInteractions)')), context);
  const controls = vm.runInContext('({ idleChannelsInput, idleChannelsControl, waterfallChannelsInput, fftOptions, waterfallOptions })', context);
  const toggle = (control, value) => { control.checked = value; control.dispatch('change'); };
  return { context, controls, toggle,
    preferences: () => preferences,
    switchUser: (next) => { preferences = next; },
    subscriber: () => subscriber, closed: () => closed };
}

const row = (status, frequency_hz = 150_250_000, extra = {}) => ({ status, frequency_hz, ...extra });
const table = (rows) => ({ table_id: 'test', channel_name: 'Dispatch', system_name: 'Local', rows });
const statuses = (carriers) => Array.from(carriers, (carrier) => carrier.status);

test('FFT and waterfall settings are separate and the new preference is per-user, default off', () => {
  const h = harness();
  assert.equal(h.controls.idleChannelsInput.checked, false);
  assert.equal(h.controls.fftOptions.children[0].textContent, 'FFT');
  assert.equal(h.controls.fftOptions.children.length, 3);
  assert.equal(h.controls.waterfallOptions.children[0].textContent, 'Waterfall');
  assert.equal(h.controls.waterfallOptions.children.length, 3);
  const original = JSON.parse(JSON.stringify(h.preferences()));
  h.toggle(h.controls.idleChannelsInput, true);
  assert.deepEqual(h.preferences(), { tuner: { ...original.tuner, show_idle_channels: true } });
  h.switchUser(original);
  assert.equal(h.context.tunerStoredBoolean('show_idle_channels', false), false);
  assert.equal(harness(false).controls.idleChannelsControl.hidden, true);
});

test('idle rows are retained for immediate FFT toggles but excluded from waterfall and active-only calculations', () => {
  const h = harness();
  h.context.connectActiveChannels();
  h.subscriber().snapshot({ tables: [table([row('IDLE'), row('CALL', 150_500_000)])] });
  assert.equal(h.context.activeChannelTables.get('test').rows.length, 2);
  assert.deepEqual(statuses(h.context.activeCarriers()), ['CALL']);
  assert.equal(h.context.spectrumActiveFlags.children.length, 1);
  h.toggle(h.controls.waterfallChannelsInput, true);
  const waterfallBefore = h.context.waterfallActiveFlags.children.map((flag) => flag.className);
  h.toggle(h.controls.idleChannelsInput, true);
  assert.deepEqual(statuses(h.context.activeCarriers(true)), ['IDLE', 'CALL']);
  assert.deepEqual(statuses(h.context.activeCarriers()), ['CALL'], 'SNR source remains active-only');
  assert.equal(h.context.spectrumActiveFlags.children.length, 2);
  assert.deepEqual(h.context.waterfallActiveFlags.children.map((flag) => flag.className), waterfallBefore);
  h.toggle(h.controls.idleChannelsInput, false);
  assert.equal(h.context.spectrumActiveFlags.children.length, 1);
});

test('active calls take precedence over idle metadata in either arrival order', () => {
  for (const rows of [
    [row('IDLE', undefined, { channel_name: 'Old' }), row('CALL', undefined, { channel_name: 'Current' })],
    [row('CALL', undefined, { channel_name: 'Current' }), row('IDLE', undefined, { channel_name: 'Old' })]
  ]) {
    const h = harness();
    h.context.updateSpectrumActivityTable(table(rows));
    const carriers = h.context.activeCarriers(true);
    assert.deepEqual(statuses(carriers), ['CALL']);
    assert.equal(carriers[0].rows.length, 1);
    assert.equal(carriers[0].rows[0].channel_name, 'Current');
  }
});

test('idle buttons use the same accessible hover details and clear on active transition', () => {
  const h = harness();
  h.context.connectActiveChannels();
  h.subscriber().snapshot({ tables: [table([row('IDLE', undefined, { decoder: 'NBFM', channel_name: 'Analog' })])] });
  h.toggle(h.controls.idleChannelsInput, true);
  const flag = h.context.spectrumActiveFlags.children[0];
  assert.equal(flag.tag, 'button');
  assert.equal(flag.className, 'tuner-spectrum-active-flag status-idle');
  assert.match(flag.attributes['aria-label'], /Idle channel, 150.250000 MHz.*Analog/);
  for (const event of ['pointerenter', 'focus']) {
    flag.dispatch(event);
    assert.equal(h.context.cursorPopup.hidden, false);
    assert.equal(h.context.cursorSnap.textContent, 'Idle channel');
    assert.ok(h.context.cursorChannel.children.some((field) => field.textContent === 'Analog'));
    flag.dispatch(event === 'focus' ? 'blur' : 'pointerleave');
    assert.equal(h.context.cursorPopup.hidden, true);
  }
  flag.dispatch('pointerenter');
  h.subscriber().activityTable({ table: table([row('CALL')]) });
  assert.equal(h.context.cursorPopup.hidden, true);
  assert.equal(h.context.spectrumActiveFlags.children[0].className, 'tuner-spectrum-active-flag status-call');
  h.subscriber().activityTable({ table: table([row('IDLE')]) });
  assert.equal(h.context.spectrumActiveFlags.children[0].className, 'tuner-spectrum-active-flag status-idle');
});

test('current control stays visible, alternate control stays hidden, invalid/out-of-view and removed rows clear', () => {
  const h = harness();
  h.context.connectActiveChannels();
  h.subscriber().snapshot({ tables: [table([
    row('IDLE', 150_100_000, { tags: ['CURRENT_CONTROL'] }),
    row('IDLE', 150_200_000, { tags: ['ALTERNATE_CONTROL'] }),
    row('IDLE', 150_300_000), row('IDLE', 152_000_000), row('IDLE', NaN), row('UNKNOWN')
  ])] });
  assert.deepEqual(statuses(h.context.activeCarriers()), ['CONTROL']);
  assert.deepEqual(statuses(h.context.activeCarriers(true)), ['CONTROL', 'IDLE']);
  h.toggle(h.controls.idleChannelsInput, true);
  h.subscriber().activityTable({ table_id: 'test', operation: 'remove' });
  assert.equal(h.context.spectrumActiveFlags.children.length, 0);
  h.subscriber().snapshot({ tables: [table([row('IDLE')])] });
  h.context.closeActiveChannels();
  assert.equal(h.closed(), 1);
  assert.equal(h.context.activeChannelTables.size, 0);
  assert.equal(h.context.spectrumActiveFlags.children.length, 0);
  assert.equal(h.context.waterfallActiveFlags.children.length, 0);
});

test('saved schema round-trips both boolean values and idle styling is outline-only', async () => {
  const schemaSource = fs.readFileSync(path.join(path.dirname(applicationPath), 'core/preference-schema.js'), 'utf8');
  const schema = await import('data:text/javascript;base64,' + Buffer.from(schemaSource).toString('base64'));
  assert.equal(schema.defaults.tuner.show_idle_channels, false);
  for (const enabled of [false, true]) {
    const preferences = JSON.parse(JSON.stringify(schema.defaults));
    preferences.tuner.show_idle_channels = enabled;
    assert.equal(schema.validate(preferences).tuner.show_idle_channels, enabled);
  }
  const missing = JSON.parse(JSON.stringify(schema.defaults));
  delete missing.tuner.show_idle_channels;
  assert.throws(() => schema.validate(missing), /unknown or missing/);
  const invalid = JSON.parse(JSON.stringify(schema.defaults));
  invalid.tuner.show_idle_channels = 'false';
  assert.throws(() => schema.validate(invalid), /show_idle_channels/);
  const css = fs.readFileSync(path.join(path.dirname(applicationPath), 'app.css'), 'utf8');
  assert.match(css, /\.tuner-spectrum-flag-swatch\.status-idle\s*\{\s*background: transparent;\s*border: 2px solid/);
});
