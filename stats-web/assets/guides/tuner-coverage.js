// Fictional receiving windows. This illustration owns only its animation and DOM;
// the actual tuner page and receiver remain untouched throughout the overview.
const freezeRows = rows => Object.freeze(rows.map(row => Object.freeze(row)));
const templates = freezeRows([
  { key: 'fire', name: 'Fire Dispatch', frequency: 856.4125, duration: 3, type: 'conventional' },
  { key: 'regional', name: 'Regional Fire', frequency: 857.9125, duration: 3.5, type: 'voice' },
  { key: 'sheriff', name: 'Sheriff', frequency: 856.9125, duration: 3.75, type: 'voice' },
  { key: 'regional', name: 'Regional Fire', frequency: 857.9125, duration: 3.5, type: 'voice' },
  { key: 'city-fire', name: 'City Fire', frequency: 861.5125, duration: 4.25, type: 'voice' },
  { key: 'distant', name: 'Distant voice call', frequency: 868.1125, duration: 3, type: 'voice' },
  { key: 'public-works', name: 'Public Works', frequency: 865.8125, duration: 2.75, type: 'voice' },
  { key: 'ems', name: 'County EMS', frequency: 856.8125, duration: 3.25, type: 'voice' },
  { key: 'city-police', name: 'City Police', frequency: 863.5125, duration: 4, type: 'voice' },
  { key: 'county-tac', name: 'Sheriff Tactical', frequency: 857.1125, duration: 3.25, type: 'voice' },
  { key: 'utilities', name: 'Utilities', frequency: 859.5125, duration: 2.5, type: 'voice' },
  { key: 'city-ems', name: 'City EMS', frequency: 862.8125, duration: 4.25, type: 'voice' }
]);
const initialIds = ['fire', 'regional-missed', 'sheriff', 'regional-later', 'city-fire', 'distant-followed',
  'public-works', 'ems', 'city-police', 'county-tac', 'utilities', 'city-ems'];
const variedSlots = [0, 2, 4, 5, 6, 7, 8, 9, 10, 11];
let namedLaterDistant = false;
const calls = freezeRows(Array.from({ length: 80 }, (_, index) => {
  const batch = Math.floor(index / templates.length), slot = index % templates.length;
  // Vary ordering and lengths while leaving clear gaps between transmissions
  // for receiver changes, including the receiving-window reset at the loop boundary.
  const rotation = batch ? (batch * 7 + Math.floor(batch / 3)) % variedSlots.length : 0;
  const sourceSlot = slot === 1 || slot === 3 ? slot :
    variedSlots[(variedSlots.indexOf(slot) + rotation) % variedSlots.length];
  const template = templates[sourceSlot], start = .75 + index * 4.5;
  const variedDuration = template.key === 'regional' || !batch ? template.duration :
    template.duration + ((index * 7 + batch) % 5 - 2) * .2;
  const duration = Math.min(variedDuration, 4, 359.75 - start);
  let id = batch ? `call-${index}-${template.key}` : initialIds[slot];
  if (batch && template.key === 'distant' && !namedLaterDistant) { id = 'distant-later'; namedLaterDistant = true; }
  return { id, name: template.name, frequency: template.frequency, start, end: start + duration, type: template.type };
}));
const rtl = Object.freeze({ name: 'RTL-SDR R820T2', sampleRate: 2.4, usableBandwidth: 2.352 });
const primaryCenter = 856.1625, initialCenter = 860.6625, unlockAt = 13.5;
const fitsWindow = (frequency, center) => {
  const lower = frequency - .00625, upper = frequency + .00625;
  return lower >= center - rtl.usableBandwidth / 2 && upper <= center + rtl.usableBandwidth / 2 &&
    !(lower < center + .005 && upper > center - .005);
};
const retunes = [];
let movingCenter = initialCenter;
for (const call of calls) {
  if (call.start < unlockAt || fitsWindow(call.frequency, primaryCenter) || fitsWindow(call.frequency, movingCenter)) continue;
  const center = call.frequency === 857.9125 ? 858.1625 : call.frequency - .35;
  const end = call.id === 'regional-later' ? 14 : call.start - .125;
  retunes.push({ start: call.id === 'regional-later' ? unlockAt : end - .125,
    end, from: movingCenter, to: center });
  movingCenter = center;
}
export const TUNER_COVERAGE_MODEL = Object.freeze({
  duration: 360, history: 24, speed: 6, arrivalInterval: 4.5, unlockAt,
  maximumCallDuration: Math.max(...calls.map(call => call.end - call.start)),
  primaryCenter, initialCenter, rtl,
  receivers: freezeRows([{ name: 'RTL-SDR 1', sampleRate: rtl.sampleRate, usableBandwidth: rtl.usableBandwidth },
    { name: 'RTL-SDR 2', sampleRate: rtl.sampleRate, usableBandwidth: rtl.usableBandwidth }]),
  controls: freezeRows([{ name: 'County P25 control', frequency: 856.6625 }]),
  retunes: freezeRows(retunes),
  calls
});

export function coverageTimeAt(time) {
  const duration = TUNER_COVERAGE_MODEL.duration;
  return ((time % duration) + duration) % duration;
}

export function coverageCenterAt(time, includeRetune = true) {
  const model = TUNER_COVERAGE_MODEL;
  const phase = coverageTimeAt(time);
  // Receiver retunes are discrete. Keeping the change discrete avoids depicting
  // a sweep that could receive channels between the two actual placements.
  if (includeRetune) {
    for (let index = model.retunes.length - 1; index >= 0; index -= 1) {
      if (phase >= model.retunes[index].end) return model.retunes[index].to;
    }
  }
  return model.initialCenter;
}

export function coverageCallsAt(time) {
  const model = TUNER_COVERAGE_MODEL;
  const first = Math.ceil((time - model.history - model.maximumCallDuration - .75) / model.arrivalInterval);
  const last = Math.floor((time - .75) / model.arrivalInterval);
  const visible = [];
  for (let index = first; index <= last; index += 1) {
    const cycle = Math.floor(index / model.calls.length);
    const sourceIndex = ((index % model.calls.length) + model.calls.length) % model.calls.length;
    const call = model.calls[sourceIndex], offset = cycle * model.duration;
    if (call.end + offset >= time - model.history) visible.push({ ...call, sourceId: call.id,
      cycle, start: call.start + offset, end: call.end + offset });
  }
  return visible;
}

export function coverageReceiverFor(call, includeRetune = true) {
  const model = TUNER_COVERAGE_MODEL;
  if (fitsWindow(call.frequency, model.primaryCenter)) return model.receivers[0].name;
  // A later retune cannot recover the beginning of an earlier missed call.
  // Also retain full coverage for the duration of an already received call.
  if (!fitsWindow(call.frequency, coverageCenterAt(call.start, includeRetune))) return null;
  if (includeRetune && Number.isFinite(call.end)) {
    const firstCycle = Math.floor(call.start / model.duration), lastCycle = Math.floor(call.end / model.duration);
    for (let cycle = firstCycle; cycle <= lastCycle; cycle += 1) {
      for (const boundary of [0, ...model.retunes.map(retune => retune.end)]) {
        const moment = cycle * model.duration + boundary;
        if (moment > call.start && moment < call.end && !fitsWindow(call.frequency, coverageCenterAt(moment))) return null;
      }
    }
  }
  return model.receivers[1].name;
}

export function createTunerCoverageDiagram({ document, idPrefix }) {
  const model = TUNER_COVERAGE_MODEL;
  const windowValue = document.defaultView;
  const node = (tag, className = '', value = '') => {
    const item = document.createElement(tag);
    if (className) item.className = className;
    if (value) item.textContent = value;
    return item;
  };
  const svgNode = (tag, attributes = {}, value) => {
    const item = document.createElementNS('http://www.w3.org/2000/svg', tag);
    Object.entries(attributes).forEach(([name, content]) => item.setAttribute(name, String(content)));
    if (value !== undefined) item.textContent = value;
    return item;
  };
  const root = node('div', 'ui-setup-guide-coverage');
  root.dataset.guideDiagram = 'tuner-coverage';
  const receivers = node('div', 'ui-setup-guide-coverage-receivers');
  const secondaryMode = node('span', '', 'Locked');
  for (const [name, detail, mode, className] of [
    [model.receivers[0].name, '2.4 MHz sampled · 2.352 MHz usable', node('span', '', 'Locked'), 'rtl'],
    [model.receivers[1].name, '2.4 MHz sampled · 2.352 MHz usable', secondaryMode, 'secondary']
  ]) {
    const receiver = node('div', 'ui-setup-guide-coverage-receiver');
    const identity = node('div', 'ui-setup-guide-coverage-identity');
    const key = node('i', `ui-setup-guide-coverage-key ${className}`);
    key.setAttribute('aria-hidden', 'true');
    identity.append(key, node('strong', '', name), mode);
    receiver.append(identity, node('span', 'ui-setup-guide-coverage-detail', detail));
    receivers.append(receiver);
  }
  const scene = node('div', 'ui-setup-guide-coverage-scene');
  scene.dataset.coverageScene = '';
  const sceneTitle = node('strong');
  const sceneCopy = node('span');
  scene.append(sceneTitle, sceneCopy);
  const svg = svgNode('svg', { class: 'ui-setup-guide-coverage-chart', role: 'img',
    'aria-labelledby': `${idPrefix}-title`, 'aria-describedby': `${idPrefix}-description` });
  const title = svgNode('title', { id: `${idPrefix}-title` }, 'Calls moving through receiving windows over time');
  const description = svgNode('desc', { id: `${idPrefix}-description` },
    'Frequency increases left to right. New transmissions appear at the top and move down into history. Colored windows show usable bandwidth. Solid calls are received; dashed calls outside coverage are missed. RTL-SDR 1 holds the control channel while unlocked RTL-SDR 2 hops between voice frequencies after each call ends.');
  const legend = node('div', 'ui-setup-guide-coverage-legend');
  for (const [kind, text] of [['received', 'Received'], ['missed', 'Missed'],
    ['control', 'Control channel'], ['edge', 'Excluded edges']]) {
    const entry = node('span');
    const key = node('i', `ui-setup-guide-coverage-mark ${kind}`);
    key.setAttribute('aria-hidden', 'true');
    entry.append(key, node('span', '', text));
    legend.append(entry);
  }
  root.append(receivers, scene, svg, legend);

  const mount = ({ signal } = {}) => {
    // This instructional simulation autoplays even under reduced-motion preferences.
    let time = 0, playing = !document.hidden;
    let frame = null, lastFrame = null, lastPaint = null, disposed = false, observer = null;
    let lastScene = '', width = 960;
    const left = 44, right = 12, top = 36, plotHeight = 132, height = 207;
    const clipId = `${idPrefix}-clip`, edgeId = `${idPrefix}-edge`;
    const x = frequency => left + (frequency - 854.2) / (869.2 - 854.2) * (width - left - right);
    const y = moment => top + (time - moment) / model.history * plotHeight;
    const defs = svgNode('defs'), clip = svgNode('clipPath', { id: clipId });
    const clipRect = svgNode('rect', { x: left, y: top, height: plotHeight });
    clip.append(clipRect);
    const edge = svgNode('pattern', { id: edgeId, width: 6, height: 6, patternUnits: 'userSpaceOnUse' });
    edge.append(svgNode('path', { d: 'M-1 1L1-1M0 6L6 0M5 7L7 5', class: 'coverage-edge-line' }));
    defs.append(clip, edge);
    const chart = svgNode('g', { 'clip-path': `url(#${clipId})` });
    const bands = svgNode('g'), grid = svgNode('g'), controlTraces = svgNode('g'), callTraces = svgNode('g');
    const bandPaths = name => [svgNode('path', { fill: `url(#${edgeId})` }),
      svgNode('path', { fill: `url(#${edgeId})` }), svgNode('path', { class: `coverage-band-${name}` })];
    const rtlPaths = bandPaths('rtl'), secondaryPaths = bandPaths('secondary');
    bands.append(...rtlPaths, ...secondaryPaths);
    chart.append(bands, grid, controlTraces, callTraces);
    const annotations = svgNode('g');
    const rtlCaption = svgNode('text', { y: 22, 'text-anchor': 'middle' }, 'RTL-SDR 1');
    const secondaryCaption = svgNode('text', { y: 22, 'text-anchor': 'middle' }, 'RTL-SDR 2');
    svg.replaceChildren(title, description, defs, chart, annotations);
    const activeCalls = new Map();
    const sceneAt = () => {
      const phase = coverageTimeAt(time);
      if (phase < 5.25) return ['One tuner holds the control channel', 'RTL-SDR 1 receives the control channel and other frequencies inside its usable window.'];
      if (phase < model.unlockAt) return ['Locked windows stay in place', 'Regional Fire is beyond both usable windows, so that call is missed.'];
      return ['The second tuner follows voice calls', 'Unlocked RTL-SDR 2 hops to new voice frequencies after each call. RTL-SDR 1 keeps the control channel in coverage.'];
    };
    const drawBand = (paths, centerAt, usableHalf, sampledHalf, moments) => {
      const points = moments.map(moment => [moment, centerAt(moment)]);
      const path = (start, end) => `M${points.map(([moment, center]) => `${x(center + start)},${y(moment)}`).join('L')}` +
        `L${[...points].reverse().map(([moment, center]) => `${x(center + end)},${y(moment)}`).join('L')}Z`;
      paths[0].setAttribute('d', path(-sampledHalf, -usableHalf));
      paths[1].setAttribute('d', path(usableHalf, sampledHalf));
      paths[2].setAttribute('d', path(-usableHalf, usableHalf));
    };
    const render = () => {
      if (disposed) return;
      root.dataset.time = coverageTimeAt(time).toFixed(2);
      root.dataset.cycle = String(Math.floor(time / model.duration));
      root.dataset.playing = String(playing);
      root.dataset.rtlCenter = String(model.primaryCenter);
      root.dataset.primaryCenter = String(model.primaryCenter);
      root.dataset.secondaryCenter = String(coverageCenterAt(time));
      root.dataset.secondaryLocked = String(coverageTimeAt(time) < model.unlockAt);
      secondaryMode.textContent = coverageTimeAt(time) < model.unlockAt ? 'Locked' : 'Unlocked';
      const nextScene = sceneAt();
      if (nextScene[0] !== lastScene) {
        [sceneTitle.textContent, sceneCopy.textContent] = nextScene;
        lastScene = nextScene[0];
      }
      const moments = [time, time - model.history];
      for (let cycle = Math.floor((time - model.history) / model.duration); cycle <= Math.floor(time / model.duration); cycle += 1) {
        for (const offset of [0, ...model.retunes.map(retune => retune.end)]) {
          const boundary = cycle * model.duration + offset;
          if (boundary <= time && boundary > time - model.history) moments.push(boundary, boundary - .000001);
        }
      }
      moments.sort((a, b) => b - a);
      drawBand(rtlPaths, () => model.primaryCenter, model.rtl.usableBandwidth / 2, model.rtl.sampleRate / 2,
        [time, time - model.history]);
      drawBand(secondaryPaths, coverageCenterAt, model.rtl.usableBandwidth / 2, model.rtl.sampleRate / 2, moments);
      rtlCaption.setAttribute('x', x(model.primaryCenter));
      secondaryCaption.setAttribute('x', x(coverageCenterAt(time)));
      const visible = coverageCallsAt(time), keys = new Set();
      for (const call of visible) {
        const key = `${call.cycle}:${call.id}`;
        keys.add(key);
        let trace = activeCalls.get(key);
        if (!trace) {
          const receiver = coverageReceiverFor(call);
          trace = svgNode('line', { class: `coverage-call ${call.type} ${receiver ? 'received' : 'missed'}`,
            'data-call': call.id, 'data-received': String(Boolean(receiver)) });
          trace.append(svgNode('title', {}, `${call.name} · ${call.frequency.toFixed(4)} MHz · ${receiver ? `Received by ${receiver}` : 'Missed outside usable coverage'}`));
          activeCalls.set(key, trace);
          callTraces.append(trace);
        }
        trace.setAttribute('x1', x(call.frequency)); trace.setAttribute('x2', x(call.frequency));
        trace.setAttribute('y1', y(Math.min(call.end, time))); trace.setAttribute('y2', y(call.start));
      }
      activeCalls.forEach((trace, key) => { if (!keys.has(key)) { trace.remove(); activeCalls.delete(key); } });
    };
    const resize = () => {
      if (disposed) return;
      width = Math.max(300, Math.round(root.getBoundingClientRect().width) || 960);
      svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
      clipRect.setAttribute('width', width - left - right);
      const lines = [], labels = [];
      for (const tick of width < 500 ? [855, 859, 863, 867] : [855, 857, 859, 861, 863, 865, 867]) {
        lines.push(svgNode('line', { x1: x(tick), x2: x(tick), y1: top, y2: top + plotHeight, class: 'coverage-grid' }));
        labels.push(svgNode('text', { x: x(tick), y: top + plotHeight + 18, 'text-anchor': 'middle' }, tick));
      }
      for (const age of [0, 6, 12, 18, 24]) {
        const position = top + age / model.history * plotHeight;
        lines.push(svgNode('line', { x1: left, x2: width - right, y1: position, y2: position, class: 'coverage-grid' }));
        labels.push(svgNode('text', { x: left - 7, y: position + 4, 'text-anchor': 'end' }, age ? `${age}s` : 'Now'));
      }
      labels.push(svgNode('text', { x: (left + width - right) / 2, y: height - 2,
        'text-anchor': 'middle' }, 'Frequency (MHz)'), rtlCaption, secondaryCaption);
      grid.replaceChildren(...lines); annotations.replaceChildren(...labels);
      controlTraces.replaceChildren(...model.controls.map(control => {
        const trace = svgNode('line', { x1: x(control.frequency), x2: x(control.frequency), y1: top,
          y2: top + plotHeight, class: 'coverage-control', 'data-control': control.name });
        trace.append(svgNode('title', {}, `${control.name} · ${control.frequency.toFixed(4)} MHz`));
        return trace;
      }));
      render();
    };
    const cancelFrame = () => {
      if (frame !== null) windowValue.cancelAnimationFrame(frame);
      frame = lastFrame = lastPaint = null;
    };
    const stop = () => { playing = false; cancelFrame(); render(); };
    const animate = timestamp => {
      frame = null;
      if (disposed || !playing) return;
      if (lastFrame !== null) time += Math.max(0, timestamp - lastFrame) / 1000 * model.speed;
      lastFrame = timestamp;
      if (lastPaint === null || timestamp - lastPaint >= 40) { render(); lastPaint = timestamp; }
      frame = windowValue.requestAnimationFrame(animate);
    };
    const start = () => {
      if (disposed || signal?.aborted || document.hidden) return;
      cancelFrame(); playing = true; render();
      frame = windowValue.requestAnimationFrame(animate);
    };
    const visibility = () => { if (document.hidden) stop(); else start(); };
    const cleanup = () => {
      if (disposed) return;
      disposed = true; playing = false; cancelFrame(); observer?.disconnect();
      document.removeEventListener('visibilitychange', visibility);
      windowValue.removeEventListener('pagehide', cleanup);
      signal?.removeEventListener('abort', cleanup);
      root.dataset.disposed = 'true'; root.dataset.playing = 'false';
    };
    document.addEventListener('visibilitychange', visibility);
    windowValue.addEventListener('pagehide', cleanup);
    signal?.addEventListener('abort', cleanup, { once: true });
    if (windowValue.ResizeObserver) { observer = new windowValue.ResizeObserver(resize); observer.observe(root); }
    resize();
    if (signal?.aborted) cleanup();
    else if (playing) frame = windowValue.requestAnimationFrame(animate);
    return cleanup;
  };
  return { element: root, mount };
}
