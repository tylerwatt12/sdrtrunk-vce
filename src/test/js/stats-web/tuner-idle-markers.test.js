'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { readStylesheetSource } = require('./stylesheet-source');
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
    this.classList = { add: (...names) => {
      this.className = [...new Set([...(this.className || '').split(/\s+/).filter(Boolean), ...names])].join(' ');
    } };
  }
  append(...children) { this.children.push(...children); }
  prepend(...children) { this.children.unshift(...children); }
  replaceChildren(...children) { this.children = children; }
  querySelector(selector) {
    if(selector === 'input') return this.children.find((child) => child?.tag === 'input') || null;
    return null;
  }
  setAttribute(key, value) { this.attributes[key] = value; }
  addEventListener(type, callback) { this.listeners[type] = callback; }
  dispatch(type) { this.listeners[type]?.({ currentTarget: this }); }
  getBoundingClientRect() { return { width: 800 }; }
}

function harness(liveAllowed = true) {
  let preferences = { tuner: { snap_frequency: true, smooth_fft: true,
    show_idle_channels: false } };
  let subscriber;
  let closed = 0;
  const context = {
    basicOperator: false,
    node: (...args) => new Element(...args),
    uiToggle: (checked, label) => {
      const control = new Element('label', 'ui-toggle');
      const input = new Element('input');
      input.type = 'checkbox';
      input.checked = Boolean(checked);
      input.setAttribute('aria-label', label);
      const track = new Element('span', 'ui-toggle-track');
      track.append(new Element('span', 'ui-toggle-thumb'));
      control.append(input, track, new Element('span', 'ui-toggle-state', checked ? 'On' : 'Off'));
      return control;
    },
    speedControl: new Element('label', '', 'Waterfall speed'),
    rangeControl: new Element('div', '', 'Display range'), rangeHelp: new Element('span'),
    ACCESS_CAPABILITIES: { LIVE: 'live' }, capabilityAllowed: () => liveAllowed,
    activeUserPreferences: () => preferences,
    settleUserPreferenceMutation: (mutate) => mutate(preferences),
    viewport: { startHz: 150_000_000, endHz: 151_000_000 },
    activeChannelTables: new Map(), activeChannelSource: null, activeFlagSignature: '',
    spectrumActiveFlags: new Element(),
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
    setSharedCursorGuides: () => {}, positionCursorPopup: () => {},
    updateCursor: () => {},
    frequencySelectionForCarrier: (carrier) => carrier,
    openTunerFrequencyActions: () => {},
    hideCursor: () => { context.hoverFlag = null; context.cursorPopup.hidden = true; }
  };
  context.activeFlagLayers = [context.spectrumActiveFlags];
  vm.createContext(context);
  vm.runInContext([
    ...source.matchAll(/^const TUNER_(?:SPECTRUM_(?:SNAP|SMOOTH|IDLE)_PREFERENCE) = .*;$/gm)
  ].map((match) => match[0]).join('\n'), context);
  vm.runInContext(source.slice(source.indexOf('const TUNER_ACTIVITY_PRIORITY'),
    source.indexOf('const RADIO_REFERENCE_DETAIL_CACHE_LIMIT')), context);
  [
    'function protocol(value)', 'function protocolFamily(row)', 'function isAnalogChannel(row)',
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
  vm.runInContext(source.slice(source.indexOf('  const optionToggle = (checked, label, detail) => {'),
    source.indexOf("  const profilePanel = node('fieldset', 'tuner-spectrum-profile')")), context);
  vm.runInContext(source.slice(source.indexOf("  idleChannelsInput.addEventListener('change'"),
    source.indexOf('  if (plotInteractions) [spectrum.canvas, waterfall.canvas].forEach(addPlotInteractions)')), context);
  const controls = vm.runInContext('({ idleChannelsInput, idleChannelsControl, fftOptions, waterfallOptions })', context);
  const toggle = (control, value) => { control.checked = value; control.dispatch('change'); };
  return { context, controls, toggle,
    preferences: () => preferences,
    switchUser: (next) => { preferences = next; },
    subscriber: () => subscriber, closed: () => closed };
}

function spectrumLifecycleHarness() {
  const events = [];
  const releases = [];
  let documentFocused = true;
  const context = {
    liveConnections: new Set(), pageConnections: new Set(),
    document: { hidden: false, hasFocus: () => documentFocused }, window: { clearTimeout: () => {} },
    targetSelect: Object.assign(new Element('select'), { value: 'first' }),
    DIAGNOSTIC_FRAME_TYPES: { HEARTBEAT: 127, STATE: 1 },
    setStatus: () => {}, setOverlay: () => {}, setRefining: () => {}, clearSpectrumSmoothing: () => {},
    acceptTunerState: () => {}, acceptTunerFrame: () => {},
    connectActiveChannels: () => {}, closeActiveChannels: () => {},
    storeTunerChoice: () => {}, resetViewportForTarget: () => {}, resetPlots: () => {},
    binaryFrameConnection: (_topic, parameters) => {
      const target = parameters.target_id;
      events.push(`open:${target}`);
      let release;
      const closed = new Promise((resolve) => { release = resolve; });
      releases.push(release);
      return {
        close: () => {
          events.push(`close:${target}`);
          return closed;
        },
        //The close promise is the server-control acknowledgement; this local flag must not win the race.
        whenClosed: () => {
          events.push(`local-close:${target}`);
          return Promise.resolve();
        }
      };
    }
  };
  vm.createContext(context);
  vm.runInContext(`
    let disposed = false;
    let paused = false;
    let pageFocused = true;
    let pageSuspended = false;
    let stream = null;
    let streamRelease = Promise.resolve();
    let streamEpoch = 0;
    let viewportUpdateTimer = null;
    let awaitingViewportState = false;
    let sequence = null;
    let refining = false;
    let drag = null;
    const selectedTargetId = () => targetSelect.value;
    const shouldRun = () => !disposed && !paused && pageFocused && !pageSuspended &&
      !document.hidden && selectedTargetId();
    const diagnosticParameters = () => ({ target_id: selectedTargetId() });
  `, context);
  [
    'function releaseConnection(connection)', 'function closeStreams()',
    'function openDiagnosticStream()', 'function sync()'
  ].forEach((signature) => vm.runInContext(functionSource(signature), context));
  const tunerStart = source.indexOf('function tunerSpectrumPanel');
  vm.runInContext(source.slice(source.indexOf("  targetSelect.addEventListener('change', () => {", tunerStart),
    source.indexOf('  function applySelectedProfile()', tunerStart)), context);
  vm.runInContext(source.slice(source.indexOf('  const onVisibilityChange = () => {', tunerStart),
    source.indexOf('  const onResize = () => {', tunerStart)), context);
  return {
    events,
    run: (expression) => vm.runInContext(expression, context),
    setDocumentFocus: (focused) => { documentFocused = focused; },
    release: () => {
      const release = releases.shift();
      assert.ok(release, 'a connection close acknowledgement is pending');
      release();
    }
  };
}

function sharedCursorHarness() {
  const spectrumCanvas = new Element('canvas');
  const waterfallCanvas = new Element('canvas');
  const context = {
    viewport: { startHz: 100, endHz: 200 },
    spectrum: { canvas: spectrumCanvas, guide: Object.assign(new Element(), { hidden: true }) },
    waterfall: { canvas: waterfallCanvas, guide: Object.assign(new Element(), { hidden: true }) },
    hoverFlag: null, hoverRatio: null, hoverCanvas: null, hoverYRatio: null,
    waterfallHistoryRow: () => ({ observedAtEpochMs: 1, metadata: {} }),
    waterfallFrequencyAt: (_row, ratio) => 100 + ratio * 100,
    waterfallRetuneLabel: () => '',
    snapInput: { checked: false }, frequencyScopes: [], snapFrequencyHz: null,
    tunerSnapFrequency: (frequencyHz) => ({ frequencyHz: context.snapFrequencyHz ?? frequencyHz }),
    cursorFrequency: new Element(), cursorSnap: new Element(), cursorPower: new Element(),
    cursorChannel: new Element(), cursorPopup: new Element(),
    refining: true, fftValues: [], positionCursorPopup: () => {}
  };
  vm.createContext(context);
  [
    'function setSpectrumCursorGuide(frequencyHz)',
    'function setWaterfallCursorGuide(ratio)',
    'function setSharedCursorGuides(frequencyHz)',
    'function updateCursor(ratio)',
    'function showCursor(ratio, canvas, yRatio)'
  ].forEach((signature) => vm.runInContext(functionSource(signature), context));
  return { context, spectrumCanvas, waterfallCanvas,
    show: (ratio, canvas) => context.showCursor(ratio, canvas, 0.5) };
}

function embeddedSpectrumInteractionHarness() {
  const viewportUpdates = [];
  const cursorUpdates = [];
  const frequencyActions = [];
  let pointerCaptures = 0;
  const canvas = {
    classList: { add: () => {}, remove: () => {} },
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 400, height: 200 }),
    setPointerCapture: () => { pointerCaptures += 1; }
  };
  const context = {
    basicOperator: true,
    frequencyCursor: true,
    viewportControls: true,
    frequencyActions: false,
    fullViewport: { startHz: 100, endHz: 200 },
    viewport: { startHz: 100, endHz: 200 },
    spectrum: { overlay: { textContent: '' } },
    drag: null,
    TUNER_SPECTRUM_MAXIMUM_ZOOM: 64,
    TUNER_SPECTRUM_ZOOM_FACTOR: 1.5,
    shouldRun: () => true,
    applyViewport: (nextViewport, requestMode) => viewportUpdates.push({ ...nextViewport, requestMode }),
    queueViewportUpdate: () => {},
    showCursor: (...args) => cursorUpdates.push(args),
    openFrequencyActionsAtPointer: (...args) => frequencyActions.push(args),
    cancelDrag: () => { context.drag = null; }
  };
  vm.createContext(context);
  [
    'function zoomAmount()', 'function clampViewport(startHz, endHz)',
    'function zoomAt(anchor, factor)', 'function panBy(deltaHz, requestMode = \'immediate\')',
    'function canInteract()', 'function onPlotWheel(event)', 'function onPlotKeyDown(event)',
    'function onPlotPointerMove(event)', 'function onPlotPointerDown(event)',
    'function onPlotPointerUp(event)', 'function onPlotClick(event)'
  ].forEach((signature) => vm.runInContext(functionSource(signature), context));
  const event = (overrides = {}) => ({
    currentTarget: canvas,
    clientX: 100,
    clientY: 60,
    deltaY: -1,
    key: '',
    button: 0,
    pointerId: 7,
    preventDefault: () => {},
    ...overrides
  });
  return {
    context,
    canvas,
    cursorUpdates,
    frequencyActions,
    viewportUpdates,
    event,
    pointerCaptures: () => pointerCaptures,
    setViewport: (startHz, endHz) => { context.viewport = { startHz, endHz }; }
  };
}

const row = (status, frequency_hz = 150_250_000, extra = {}) => ({ status, frequency_hz, ...extra });
const table = (rows) => ({ table_id: 'test', channel_name: 'Dispatch', system_name: 'Local', rows });
const statuses = (carriers) => Array.from(carriers, (carrier) => carrier.status);

test('frequency cursor stays aligned and visible across FFT and waterfall plots', () => {
  const h = sharedCursorHarness();
  h.show(0.25, h.spectrumCanvas);
  assert.equal(h.context.spectrum.guide.style.left, '25.000%');
  assert.equal(h.context.waterfall.guide.style.left, '25.000%');
  assert.equal(h.context.spectrum.guide.hidden, false);
  assert.equal(h.context.waterfall.guide.hidden, false);

  h.show(0.75, h.waterfallCanvas);
  assert.equal(h.context.spectrum.guide.style.left, '75.000%');
  assert.equal(h.context.waterfall.guide.style.left, '75.000%');
  assert.equal(h.context.spectrum.guide.hidden, false);
  assert.equal(h.context.waterfall.guide.hidden, false);

  h.context.snapInput.checked = true;
  h.context.snapFrequencyHz = 160;
  h.show(0.75, h.waterfallCanvas);
  assert.equal(h.context.spectrum.guide.style.left, '60.000%');
  assert.equal(h.context.waterfall.guide.style.left, '60.000%');
});

test('embedded spectrum keeps the shared cursor and zoom handlers without enabling frequency actions', () => {
  const h = embeddedSpectrumInteractionHarness();

  h.context.onPlotPointerMove(h.event());
  assert.equal(h.cursorUpdates.length, 1, 'hover uses the shared frequency cursor in the Tuners view');
  assert.equal(h.cursorUpdates[0][0], 0.25);

  h.context.onPlotWheel(h.event({ clientX: 300 }));
  assert.equal(h.viewportUpdates.length, 1, 'the mouse wheel requests a zoomed viewport');
  assert.ok(h.viewportUpdates[0].endHz - h.viewportUpdates[0].startHz < 100);

  h.viewportUpdates.length = 0;
  h.context.onPlotKeyDown(h.event({ key: '+' }));
  assert.equal(h.viewportUpdates.length, 1, 'plus requests the same shared zoom behavior');

  h.viewportUpdates.length = 0;
  h.context.onPlotKeyDown(h.event({ key: 'ArrowRight' }));
  assert.equal(h.viewportUpdates.length, 0, 'keyboard pan is inert at the full tuner span');
  h.context.onPlotPointerDown(h.event());
  assert.equal(h.pointerCaptures(), 0, 'drag pan does not begin at the full tuner span');
  h.context.onPlotClick(h.event());
  assert.equal(h.frequencyActions.length, 0, 'a full-span Tuners plot click never opens frequency actions');

  h.setViewport(120, 180);
  h.context.onPlotKeyDown(h.event({ key: 'ArrowRight' }));
  assert.equal(h.viewportUpdates.length, 1, 'keyboard pan is enabled after zooming');
  h.context.onPlotPointerDown(h.event());
  assert.equal(h.pointerCaptures(), 1, 'drag pan is enabled after zooming');
  h.context.onPlotPointerUp(h.event());
  assert.equal(h.frequencyActions.length, 0,
    'a zoomed pointer press and release without movement never opens frequency actions');

  h.context.onPlotClick(h.event());
  assert.equal(h.frequencyActions.length, 0, 'a zoomed Tuners plot click never opens frequency actions');
});

test('Tuners and Spectrum pages instantiate the same spectrum renderer', () => {
  const spectrumPage = functionSource('async function renderTunerSpectrum()');
  const tunersPage = functionSource('async function renderTuners()');
  assert.match(spectrumPage, /tunerSpectrumPanel\(snapPresetDocument\)/);
  assert.match(tunersPage, /tunerSpectrumPanel\(snapPresetDocument,\s*\{/);
  assert.match(tunersPage, /basicOperator:\s*true/);
  assert.match(tunersPage, /frequencyCursor:\s*true/);
  assert.match(tunersPage, /viewportControls:\s*true/);
  assert.match(tunersPage, /profileSelection:\s*true/);
  assert.match(source, /const frequencyActions = !basicOperator/);
  assert.match(source, /let tunerOperatorSpectrumProfile = 'efficient'/);
  assert.match(source, /if \(basicOperator\) tunerOperatorSpectrumProfile = spectrumProfile/);
  assert.match(source, /if \(!basicOperator && !panelOptions\.inlineDisplayOptions\)/);
  assert.equal((source.match(/function tunerSpectrumPanel\(/g) || []).length, 1);
});

test('FFT and waterfall settings are separate and idle markers remain per-user, default off', () => {
  const h = harness();
  assert.equal(h.controls.idleChannelsInput.checked, false);
  assert.equal(h.controls.fftOptions.children[0].textContent, 'FFT');
  assert.equal(h.controls.fftOptions.children.length, 3);
  assert.equal(h.controls.waterfallOptions.children[0].textContent, 'Waterfall');
  assert.equal(h.controls.waterfallOptions.children.length, 2);
  const original = JSON.parse(JSON.stringify(h.preferences()));
  h.toggle(h.controls.idleChannelsInput, true);
  assert.deepEqual(h.preferences(), { tuner: { ...original.tuner, show_idle_channels: true } });
  h.switchUser(original);
  assert.equal(h.context.tunerStoredBoolean('show_idle_channels', false), false);
  assert.equal(harness(false).controls.idleChannelsControl.hidden, true);
});

test('idle rows are retained for immediate FFT toggles but excluded from active-only calculations', () => {
  const h = harness();
  h.context.connectActiveChannels();
  h.subscriber().snapshot({ tables: [table([row('IDLE'), row('CALL', 150_500_000)])] });
  assert.equal(h.context.activeChannelTables.get('test').rows.length, 2);
  assert.deepEqual(statuses(h.context.activeCarriers()), ['CALL']);
  assert.equal(h.context.spectrumActiveFlags.children.length, 1);
  h.toggle(h.controls.idleChannelsInput, true);
  assert.deepEqual(statuses(h.context.activeCarriers(true)), ['IDLE', 'CALL']);
  assert.deepEqual(statuses(h.context.activeCarriers()), ['CALL'], 'SNR source remains active-only');
  assert.equal(h.context.spectrumActiveFlags.children.length, 2);
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
});

test('saved schema round-trips both boolean values and idle styling is outline-only', async () => {
  const schemaSource = fs.readFileSync(path.join(path.dirname(applicationPath), 'core/preference-schema.js'), 'utf8');
  const schema = await import('data:text/javascript;base64,' + Buffer.from(schemaSource).toString('base64'));
  assert.equal(schema.defaults.tuner.show_idle_channels, false);
  assert.match(source, /'high-detail': Object\.freeze\(\{ fftSize: 16384, fps: 20 \}\)/);
  assert.match(source, /'maximum-detail': Object\.freeze\(\{ fftSize: 32768, fps: 20 \}\)/);
  const maximumDetail = JSON.parse(JSON.stringify(schema.defaults));
  maximumDetail.tuner.profile = 'maximum-detail';
  assert.equal(schema.validate(maximumDetail).tuner.profile, 'maximum-detail');
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
  const css = readStylesheetSource(path.join(path.dirname(applicationPath), 'app.css'));
  assert.match(css, /\.tuner-spectrum-flag-swatch\.status-idle\s*\{\s*background: transparent;\s*border: 2px solid/);
});

test('maximum detail persists while inactive and only updates a running spectrum', () => {
  const stored = [];
  let running = false;
  let updates = 0;
  const profileSelect = Object.assign(new Element('select'), { value: 'maximum-detail' });
  const context = {
    basicOperator: false,
    tunerOperatorSpectrumProfile: 'efficient',
    profileSelect,
    TUNER_SPECTRUM_PROFILE_PREFERENCE: 'profile',
    storeTunerChoice: (key, value) => stored.push([key, value]),
    shouldRun: () => running,
    queueViewportUpdate: () => { updates += 1; }
  };
  vm.createContext(context);
  vm.runInContext("let spectrumProfile = 'balanced';", context);
  const tunerStart = source.indexOf('function tunerSpectrumPanel');
  vm.runInContext(source.slice(source.indexOf('  function applySelectedProfile()', tunerStart),
    source.indexOf("  zoomIn.addEventListener('click'", tunerStart)), context);

  profileSelect.dispatch('change');
  assert.deepEqual(stored, [['profile', 'maximum-detail']]);
  assert.equal(updates, 0);
  running = true;
  profileSelect.dispatch('change');
  assert.deepEqual(stored.at(-1), ['profile', 'maximum-detail']);
  assert.equal(updates, 1);
});

test('embedded quality stays session-local and does not overwrite the Spectrum preference', () => {
  const stored = [];
  const profileSelect = Object.assign(new Element('select'), { value: 'high-detail' });
  const context = {
    basicOperator: true,
    tunerOperatorSpectrumProfile: 'efficient',
    profileSelect,
    TUNER_SPECTRUM_PROFILE_PREFERENCE: 'profile',
    storeTunerChoice: (key, value) => stored.push([key, value]),
    shouldRun: () => false,
    queueViewportUpdate: () => {}
  };
  vm.createContext(context);
  vm.runInContext("let spectrumProfile = 'efficient';", context);
  const tunerStart = source.indexOf('function tunerSpectrumPanel');
  vm.runInContext(source.slice(source.indexOf('  function applySelectedProfile()', tunerStart),
    source.indexOf("  zoomIn.addEventListener('click'", tunerStart)), context);

  profileSelect.dispatch('change');
  assert.equal(context.tunerOperatorSpectrumProfile, 'high-detail');
  assert.deepEqual(stored, []);
});

test('spectrum lifecycle serializes focus and tuner rebinds without duplicate streams', async () => {
  const h = spectrumLifecycleHarness();
  await h.run('sync()');
  assert.deepEqual(h.events, ['open:first']);

  h.run('onBlur()');
  h.run('onFocus()');
  h.run('onFocus()');
  await Promise.resolve();
  assert.deepEqual(h.events, ['open:first', 'close:first']);
  h.release();
  await h.run('streamRelease');
  assert.deepEqual(h.events, ['open:first', 'close:first', 'open:first']);

  h.run("targetSelect.value = 'second'; targetSelect.dispatch('change')");
  await Promise.resolve();
  assert.deepEqual(h.events.slice(-1), ['close:first']);
  h.release();
  await h.run('streamRelease');
  assert.deepEqual(h.events.slice(-2), ['close:first', 'open:second']);

  h.run('onPageHide()');
  h.setDocumentFocus(true);
  h.run('onPageShow()');
  h.run('onPageShow()');
  await Promise.resolve();
  assert.deepEqual(h.events.slice(-1), ['close:second']);
  h.release();
  await h.run('streamRelease');
  assert.deepEqual(h.events.slice(-2), ['close:second', 'open:second']);
  assert.equal(h.events.some((event) => event.startsWith('local-close:')), false);
});

test('multiplexer close waits for server control even while another topic remains subscribed', async () => {
  const context = {
    LIVE_MULTIPLEX_TOPICS: Object.freeze({ 0: 'control', 1: 'channel_activity', 5: 'tuner_diagnostics' }),
    snakeCasePayload: (value) => value,
    invokeLiveSubscriber: () => {},
    window: { setTimeout: () => 1, clearTimeout: () => {} },
    queueMicrotask
  };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf('class LiveMultiplexer'),
    source.indexOf('const liveMultiplexer = new LiveMultiplexer()')), context);
  const multiplexer = vm.runInContext('new LiveMultiplexer()', context);
  multiplexer.controller = {};
  multiplexer.ready = true;
  multiplexer.clientId = 'test-client';
  const diagnostic = multiplexer.subscribe('tuner_diagnostics');
  multiplexer.subscribe('channel_activity');
  let released = false;
  const closing = diagnostic.close().then(() => { released = true; });
  await Promise.resolve();
  assert.equal(released, false);
  multiplexer.settleControlWaiters(multiplexer.controlDesiredRevision, true);
  await closing;
  assert.equal(released, true);
  assert.equal(multiplexer.hasSubscribers(), true);
});

test('multiplexer disconnect settles an idle close before focus can reopen diagnostics', async () => {
  let finishRead;
  const context = {
    LIVE_MULTIPLEX_TOPICS: Object.freeze({ 0: 'control', 5: 'tuner_diagnostics' }),
    LIVE_MULTIPLEX_READY_TIMEOUT_MS: 10_000,
    LIVE_MULTIPLEX_LIVENESS_TIMEOUT_MS: 10_000,
    LIVE_MULTIPLEX_HEADER_BYTES: 12,
    LIVE_MULTIPLEX_MAGIC: 0,
    LIVE_MULTIPLEX_VERSION: 1,
    LIVE_MULTIPLEX_MAXIMUM_BYTES: 1_024,
    LIVE_MULTIPLEX_DECODER: new TextDecoder(),
    snakeCasePayload: (value) => value,
    invokeLiveSubscriber: () => {},
    randomLiveClientId: () => 'test-client',
    fetch: async () => ({
      ok: true,
      status: 200,
      body: { getReader: () => ({
        read: () => new Promise((resolve) => { finishRead = resolve; }),
        cancel: () => Promise.resolve()
      }) }
    }),
    window: {
      setInterval: () => 1,
      clearInterval: () => {},
      setTimeout: () => 1,
      clearTimeout: () => {}
    },
    AbortController,
    queueMicrotask
  };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf('class LiveMultiplexer'),
    source.indexOf('const liveMultiplexer = new LiveMultiplexer()')), context);
  const multiplexer = vm.runInContext('new LiveMultiplexer()', context);
  multiplexer.subscribers.set('tuner_diagnostics', new Set([{}]));
  const connecting = multiplexer.connect();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(typeof finishRead, 'function');
  multiplexer.ready = true;
  multiplexer.subscribers.clear();
  const closing = multiplexer.closeIfIdle(multiplexer.queueControl(true));
  let released = false;
  void closing.then(() => { released = true; });
  await Promise.resolve();
  assert.equal(released, false);
  finishRead({ done: true });
  await connecting;
  await closing;
  assert.equal(released, true);
  assert.equal(multiplexer.controlWaiters.length, 0);
});
