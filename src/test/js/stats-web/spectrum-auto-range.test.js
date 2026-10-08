const assert = require('node:assert/strict');
const test = require('node:test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');
const fs = require('node:fs');
const vm = require('node:vm');

const helper = import(pathToFileURL(resolve(__dirname,
  '../../../../stats-web/assets/features/spectrum-auto-range.js')).href);
const state = (overrides = {}) => ({ targetId: 'first', centerFrequencyHz: 150_000_000,
  sampleRateHz: 2_400_000, generation: 1, revision: 1, live: true, ...overrides });
const noise = (floor) => Float32Array.from({ length: 100 }, (_value, index) => floor + index % 4);
const source = fs.readFileSync(resolve(process.argv[2] || 'stats-web/assets/app.js'), 'utf8');

function bindingSource(signature) {
  const start = source.indexOf(signature);
  assert.ok(start >= 0, `panel binding ${signature}`);
  const opening = source.indexOf('{', start);
  let depth = 0;
  for (let index = opening; index < source.length; index++) {
    if (source[index] === '{') depth++;
    else if (source[index] === '}' && --depth === 0) return source.slice(start, index + 1);
  }
  throw new Error(`Unterminated panel binding ${signature}`);
}

const functionSource = (name) => bindingSource(`function ${name}(`);

async function panelHarness({ fft = true, waterfall = true } = {}) {
  const { createSpectrumAutoRange } = await helper;
  const automaticDisplayRange = createSpectrumAutoRange();
  automaticDisplayRange.selectTarget('first');
  let clock = 0;
  const context = vm.createContext({
    automaticDisplayRange, target: 'first', selectedTargetId: () => context.target,
    panelOptions: {}, drag: null, basicOperator: false,
    fftAutoRangeInput: { checked: fft }, waterfallAutoRangeInput: { checked: waterfall },
    fftAutoRangeValue: {}, waterfallAutoRangeValue: {}, fftAutoFloorDb: null, waterfallAutoFloorDb: null,
    dbFloor: -140, dbCeiling: -15, TUNER_SPECTRUM_MINIMUM_DISPLAY_SPAN_DB: 5,
    TUNER_SPECTRUM_PROFILES: { balanced: {} }, spectrumProfile: 'balanced', profileSelect: {},
    DIAGNOSTIC_FRAME_TYPES: { TUNER_FFT: 4 }, generation: -1, sequence: null, droppedFrames: 0,
    fftValues: new Float32Array(), frameMetadata: null, hoverFlag: null, hoverRatio: null,
    fullViewport: { startHz: 148_800_000, endHz: 151_200_000 },
    viewport: { startHz: 148_800_000, endHz: 151_200_000 },
    analysisViewport: { startHz: 148_800_000, endHz: 151_200_000 }, awaitingViewportState: false, refining: false,
    diagnosticJsonPayload: (frame) => frame.state, diagnosticFloatPayload: (frame) => frame.values,
    diagnosticFrameLatency: () => 0, latencyClock: {}, frameTimes: [], performance: { now: () => ++clock },
    waterfallScrollAccumulator: 0, waterfallHistoryRows: [], retainedWaterfallRows: 0,
    pendingWaterfallRetune: null, resetWaterfallBuffer: () => {},
    shouldRun: () => true, queueViewportUpdate: () => { context.awaitingViewportState = true; },
    restoreWaterfallHistory: () => {}, clearSpectrumSmoothing: () => {}, updateSpectrumSmoothing: () => {},
    updateSpectrumPeak: () => {}, renderFrequencyBands: () => {}, renderActiveChannels: () => {},
    setOverlay: () => {}, setStatus: () => {}, setReadouts: () => {}, setRefining: () => {},
    scheduleDraw: () => {}, addWaterfallFrame: () => {},
    storeTunerNumber: () => { throw new Error('Automatic range must not write account preferences.'); }
  });
  for (const name of ['tunerFrameDomain', 'stateNumber', 'requestedViewport', 'stateViewport', 'sameViewport',
    'stateMatchesRequest', 'zoomAmount', 'spectrumDisplayFloorDb', 'waterfallDisplayFloorDb',
    'syncAutomaticDisplayRangeReadouts', 'resetAutomaticDisplayRange', 'applyAutomaticDisplayRange',
    'acceptTunerState', 'acceptTunerFrame']) vm.runInContext(functionSource(name), context);
  vm.runInContext(bindingSource('const resetPlots = (message) =>') + ';', context);
  const confirm = ({ target = context.target, center = 150_000_000, generation = 1, revision = 1,
    live = true } = {}) => context.acceptTunerState({ generation, sequence: revision,
    state: { target_id: target, center_frequency_hz: center, sample_rate_hz: 2_400_000,
      profile: 'balanced', stream_state: live ? 'live' : 'waiting' } });
  const frame = ({ floor = -63, center = 150_000_000, generation = 1, sequence = 1,
    values = noise(floor) } = {}) => context.acceptTunerFrame({ type: 4, generation, sequence,
    centerFrequencyHz: center, sampleRateHz: 2_400_000, fftSize: values.length,
    valueCount: values.length, sourceBinCount: values.length, values });
  const rebind = (target) => {
    context.target = target;
    vm.runInContext('resetPlots("Waiting for tuner data…")', context);
  };
  return { context, confirm, frame, rebind };
}

test('lower percentile ignores isolated weak bins and strong carriers without assuming a typical floor', async () => {
  const { estimateSpectrumDisplayFloor } = await helper;
  const values = noise(-63);
  values[0] = -190;
  values[1] = NaN;
  values[2] = Infinity;
  for (let index = 90; index < values.length; index++) values[index] = -12;
  assert.equal(estimateSpectrumDisplayFloor(values, 0), -70);
  assert.equal(estimateSpectrumDisplayFloor(noise(-108), 0), -115);
  assert.equal(estimateSpectrumDisplayFloor(noise(-41), -10), -50);
});

test('invalid samples wait for valid data and bounds preserve the configured upper limit', async () => {
  const { estimateSpectrumDisplayFloor } = await helper;
  for (const values of [null, [], [NaN, Infinity, -Infinity]])
    assert.equal(estimateSpectrumDisplayFloor(values, 0), null);
  assert.equal(estimateSpectrumDisplayFloor(noise(-500), 0), -200);
  assert.equal(estimateSpectrumDisplayFloor(noise(20), -30), -35);
  assert.equal(estimateSpectrumDisplayFloor(noise(-90), NaN), null);
});

test('captures once initially and once after retune, with no recalibration on zoom/profile/gain changes', async () => {
  const { createSpectrumAutoRange } = await helper;
  const range = createSpectrumAutoRange();
  assert.equal(range.selectTarget('first'), true);
  assert.equal(range.confirm(state()), true);
  assert.equal(range.sample([NaN, Infinity], 1, 0), null);
  assert.equal(range.sample(noise(-63), 1, 0), -70);
  assert.equal(range.sample(noise(-90), 1, 0), null);
  range.confirm(state({ revision: 2, sampleRateHz: 300_000 }));
  assert.equal(range.sample(noise(-100), 1, 0), null);
  range.confirm(state({ generation: 2, revision: 1 }));
  assert.equal(range.sample(noise(-100), 2, 0), null, 'a restarted producer is not a retune');
  range.confirm(state({ generation: 2, revision: 2, centerFrequencyHz: 151_000_000 }));
  assert.equal(range.sample(noise(-87), 2, -20), -95);
  assert.equal(range.sample(noise(-110), 2, -20), null);
});

test('stale generations, stale states, wrong targets and unavailable samples cannot consume a pending retune', async () => {
  const { createSpectrumAutoRange } = await helper;
  const range = createSpectrumAutoRange();
  range.selectTarget('first');
  range.confirm(state({ generation: 2, revision: 4 }));
  assert.equal(range.confirm(state({ generation: 1 })), false);
  assert.equal(range.confirm(state({ generation: 2, revision: 3, centerFrequencyHz: 120_000_000 })), false);
  assert.equal(range.confirm(state({ targetId: 'other', generation: 3 })), false);
  assert.equal(range.sample(noise(-90), 1, 0), null);
  range.confirm(state({ generation: 2, revision: 5, live: false }));
  assert.equal(range.sample(noise(-90), 2, 0), null);
  range.confirm(state({ generation: 2, revision: 6 }));
  assert.equal(range.sample(noise(-63), 2, 0), -70);
});

test('manual adjustments cancel capture until the next retune and a changed tuner starts a new capture', async () => {
  const { createSpectrumAutoRange } = await helper;
  const range = createSpectrumAutoRange();
  range.selectTarget('first');
  range.confirm(state());
  range.discard();
  assert.equal(range.sample(noise(-63), 1, 0), null);
  range.confirm(state({ revision: 2 }));
  assert.equal(range.sample(noise(-63), 1, 0), null);
  range.confirm(state({ revision: 3, centerFrequencyHz: 151_000_000 }));
  assert.equal(range.sample(noise(-63), 1, 0), -70);
  assert.equal(range.selectTarget('second'), true);
  assert.equal(range.sample(noise(-90), 1, 0), null);
  range.confirm(state({ targetId: 'second' }));
  assert.equal(range.sample(noise(-90), 1, 0), -95);
  assert.equal(range.selectTarget('second'), false);
  assert.equal(range.sample(noise(-60), 1, 0), null);
});

test('temporary empty selection pauses acceptance while retaining pending capture and manual discard', async () => {
  const { createSpectrumAutoRange } = await helper;
  const range = createSpectrumAutoRange();
  range.selectTarget('first');
  range.confirm(state());
  assert.equal(range.selectTarget(''), false);
  assert.equal(range.acceptsFrame(1), false);
  assert.equal(range.confirm(state({ revision: 2 })), false, 'unbound state cannot resume acceptance');
  assert.equal(range.sample(noise(-63), 1, 0), null);
  assert.equal(range.selectTarget('first'), false);
  assert.equal(range.acceptsFrame(1), false, 'a rebound tuner needs a current live confirmation');
  range.confirm(state({ generation: 2 }));
  assert.equal(range.sample(noise(-63), 2, 0), -70, 'unfinished capture survives the temporary unbind');
  range.confirm(state({ generation: 2, revision: 2, centerFrequencyHz: 151_000_000 }));
  range.discard();
  range.selectTarget('');
  range.selectTarget('first');
  range.confirm(state({ generation: 3, centerFrequencyHz: 151_000_000 }));
  assert.equal(range.sample(noise(-100), 3, 0), null, 'manual discard survives the temporary unbind');
  range.selectTarget('');
  assert.equal(range.selectTarget('second'), true);
  range.confirm(state({ targetId: 'second', generation: 4 }));
  assert.equal(range.sample(noise(-90), 4, 0), -95, 'a genuinely different tuner begins a new capture');
});

test('shared panel applies each enabled minimum once while leaving manual baseline and maximum unchanged', async () => {
  const { context: panel, confirm, frame } = await panelHarness({ fft: false });
  confirm();
  frame({ values: new Float32Array(100).fill(NaN) });
  assert.equal(panel.waterfallAutoFloorDb, null);
  frame({ sequence: 2 });
  assert.equal(panel.spectrumDisplayFloorDb(), -140);
  assert.equal(panel.waterfallDisplayFloorDb(), -70);
  assert.equal(panel.dbFloor, -140);
  assert.equal(panel.dbCeiling, -15);
  assert.equal(panel.waterfallAutoRangeValue.textContent, '-70 to -15 dB');
  panel.fftAutoRangeInput.checked = true;
  frame({ sequence: 3, floor: -110 });
  assert.equal(panel.spectrumDisplayFloorDb(), -140, 'enabling later waits for the next retune');
  assert.equal(panel.waterfallDisplayFloorDb(), -70);
  confirm({ center: 151_000_000, revision: 2 });
  frame({ center: 151_000_000, sequence: 4, floor: -87 });
  assert.equal(panel.spectrumDisplayFloorDb(), -95);
  assert.equal(panel.waterfallDisplayFloorDb(), -95);
  panel.waterfallAutoRangeInput.checked = false;
  assert.equal(panel.waterfallDisplayFloorDb(), -140, 'disabled mode preserves the manual range');
});

test('shared panel rejects old FFT windows/generations and manual changes survive ordinary frames', async () => {
  const { context: panel, confirm, frame } = await panelHarness();
  confirm();
  frame();
  panel.dbFloor = -120;
  panel.resetAutomaticDisplayRange();
  frame({ sequence: 2, floor: -100 });
  assert.equal(panel.spectrumDisplayFloorDb(), -120);
  assert.equal(panel.waterfallDisplayFloorDb(), -120);
  confirm({ center: 151_000_000, generation: 2, revision: 2 });
  frame({ center: 151_000_000, generation: 1, sequence: 3, floor: -50 });
  frame({ generation: 2, sequence: 4, floor: -50 });
  assert.equal(panel.spectrumDisplayFloorDb(), -120, 'old data cannot consume the pending capture');
  frame({ center: 151_000_000, generation: 2, sequence: 5, floor: -92 });
  assert.equal(panel.spectrumDisplayFloorDb(), -100);
  confirm({ generation: 1, revision: 10 });
  frame({ generation: 1, sequence: 6, floor: -50 });
  assert.equal(panel.spectrumDisplayFloorDb(), -100, 'a stale state cannot rearm an old tuner window');
});

test('shared panel waits for an actual requested tune and a canceled preview never arms that target', async () => {
  const { context: panel, confirm, frame } = await panelHarness();
  confirm();
  let pending = true;
  panel.panelOptions.retunePending = () => pending;
  panel.panelOptions.retuneFrequencyHz = () => 155_000_000;
  frame();
  assert.equal(panel.fftAutoFloorDb, null, 'old samples wait while a different tune is pending');
  pending = false;
  frame({ sequence: 2 });
  assert.equal(panel.spectrumDisplayFloorDb(), -70);
  assert.equal(panel.fullViewport.startHz, 148_800_000, 'a preview cannot replace confirmed receiver state');
  frame({ sequence: 3, floor: -100 });
  assert.equal(panel.spectrumDisplayFloorDb(), -70);
});

test('shared panel mode and lease rebinds retain captured minima, manual range, and unfinished retune capture', async () => {
  const { context: panel, confirm, frame, rebind } = await panelHarness();
  confirm();
  frame();
  rebind('');
  frame({ sequence: 2, floor: -100 });
  assert.equal(panel.fftValues.length, 0, 'unbound frames cannot repopulate the plots');
  assert.equal(panel.spectrumDisplayFloorDb(), -70);
  rebind('first');
  confirm({ generation: 2 });
  frame({ generation: 2, floor: -100 });
  assert.equal(panel.spectrumDisplayFloorDb(), -70, 'same-frequency rebind keeps the captured minimum');
  assert.equal(panel.waterfallDisplayFloorDb(), -70);

  panel.dbFloor = -120;
  panel.resetAutomaticDisplayRange();
  rebind('');
  rebind('first');
  confirm({ generation: 3 });
  frame({ generation: 3, floor: -100 });
  assert.equal(panel.spectrumDisplayFloorDb(), -120, 'mode changes cannot undo the manual adjustment');
  assert.equal(panel.waterfallDisplayFloorDb(), -120);

  confirm({ center: 151_000_000, generation: 3, revision: 2 });
  rebind('');
  rebind('first');
  confirm({ center: 151_000_000, generation: 4 });
  frame({ center: 151_000_000, generation: 4, floor: -92 });
  assert.equal(panel.spectrumDisplayFloorDb(), -100, 'retune capture still runs when its first FFT arrives');
});

test('shared panel changes from an empty binding to a different tuner reset manual capture suppression', async () => {
  const { context: panel, confirm, frame, rebind } = await panelHarness();
  confirm();
  frame();
  panel.dbFloor = -120;
  panel.resetAutomaticDisplayRange();
  rebind('');
  rebind('second');
  confirm({ generation: 2 });
  frame({ generation: 2, floor: -87 });
  assert.equal(panel.spectrumDisplayFloorDb(), -95);
  assert.equal(panel.waterfallDisplayFloorDb(), -95);
  assert.equal(panel.dbFloor, -120);
});
