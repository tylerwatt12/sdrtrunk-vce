// Fictional receiving windows. This illustration owns only its animation and DOM;
// the actual tuner page and receiver remain untouched throughout the overview.
const freezeRows = rows => Object.freeze(rows.map(row => Object.freeze(row)));
const templates = freezeRows([
  { key: 'fire', name: 'Fire Dispatch', frequency: 856.6125, duration: 3, type: 'conventional' },
  { key: 'regional', name: 'Regional Fire', frequency: 857.9125, duration: 3.5, type: 'voice' },
  { key: 'sheriff', name: 'Sheriff', frequency: 856.9125, duration: 3.75, type: 'voice' },
  { key: 'regional', name: 'Regional Fire', frequency: 857.9125, duration: 3.5, type: 'voice' },
  { key: 'city-fire', name: 'City Fire', frequency: 861.5125, duration: 4.25, type: 'voice' },
  { key: 'distant', name: 'Distant voice call', frequency: 868.1125, duration: 3, type: 'voice' },
  { key: 'public-works', name: 'Public Works', frequency: 865.8125, duration: 2.75, type: 'conventional' },
  { key: 'ems', name: 'County EMS', frequency: 856.7625, duration: 3.25, type: 'voice' },
  { key: 'city-police', name: 'City Police', frequency: 863.5125, duration: 4, type: 'voice' },
  { key: 'county-tac', name: 'Sheriff Tactical', frequency: 857.1125, duration: 3.25, type: 'voice' },
  { key: 'utilities', name: 'Utilities', frequency: 859.5125, duration: 2.5, type: 'conventional' },
  { key: 'city-ems', name: 'City EMS', frequency: 862.8125, duration: 4.25, type: 'voice' }
]);
const initialIds = ['fire', 'regional-missed', 'sheriff', 'regional-later', 'city-fire', 'distant-missed',
  'public-works', 'ems', 'city-police', 'county-tac', 'utilities', 'city-ems'];
const variedSlots = [0, 2, 4, 5, 6, 7, 8, 9, 10, 11];
let namedLaterDistant = false;
const calls = freezeRows(Array.from({ length: 80 }, (_, index) => {
  const batch = Math.floor(index / templates.length), slot = index % templates.length;
  // Keep the outer-edge calls in safe gaps around the receiving-window moves.
  // Vary other ordering and lengths throughout the one-minute illustration.
  const rotation = batch ? (batch * 7 + Math.floor(batch / 3)) % variedSlots.length : 0;
  const sourceSlot = slot === 1 || slot === 3 ? slot :
    variedSlots[(variedSlots.indexOf(slot) + rotation) % variedSlots.length];
  const template = templates[sourceSlot], start = .75 + index * 4.5;
  const duration = template.key === 'regional' || !batch ? template.duration :
    template.duration + ((index * 7 + batch) % 5 - 2) * .2;
  let id = batch ? `call-${index}-${template.key}` : initialIds[slot];
  if (batch && template.key === 'distant' && !namedLaterDistant) { id = 'distant-later'; namedLaterDistant = true; }
  return { id, name: template.name, frequency: template.frequency, start, end: start + duration, type: template.type };
}));
export const TUNER_COVERAGE_MODEL = Object.freeze({
  duration: 360, history: 24, speed: 6, arrivalInterval: 4.5, retuneCycle: 180,
  maximumCallDuration: Math.max(...calls.map(call => call.end - call.start)),
  initialCenter: 856.1625, shiftedCenter: 857.1625,
  rtl: Object.freeze({ name: 'RTL-SDR R820T2', sampleRate: 2.4, usableBandwidth: 2.352 }),
  airspy: Object.freeze({ name: 'Airspy R2', center: 862.6625, sampleRate: 10, usableBandwidth: 9 }),
  controls: freezeRows([{ name: 'County P25 control', frequency: 856.3625 },
    { name: 'City P25 control', frequency: 862.3125 }]),
  retunes: freezeRows(Array.from({ length: 2 }, (_, cycle) => [
    { start: cycle * 180 + 13.5, end: cycle * 180 + 14, from: 856.1625, to: 857.1625 },
    { start: cycle * 180 + 154.5, end: cycle * 180 + 155, from: 857.1625, to: 856.1625 }
  ]).flat()),
  calls
});

export function coverageTimeAt(time) {
  const duration = TUNER_COVERAGE_MODEL.duration;
  return ((time % duration) + duration) % duration;
}

export function coverageCenterAt(time, includeRetune = true) {
  const model = TUNER_COVERAGE_MODEL;
  const phase = ((time % model.retuneCycle) + model.retuneCycle) % model.retuneCycle;
  // Receiver retunes are discrete. Keeping the change discrete also avoids
  // depicting an invented sweep of its center notch through existing channels.
  return includeRetune && phase >= 14 && phase < 155 ? model.shiftedCenter : model.initialCenter;
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
  const lower = call.frequency - .00625, upper = call.frequency + .00625;
  // Reception is decided when the transmission begins. A later retune cannot
  // recover audio from an earlier transmission already outside coverage.
  const rtlCenter = coverageCenterAt(call.start, includeRetune);
  if (lower >= rtlCenter - model.rtl.usableBandwidth / 2 && upper <= rtlCenter + model.rtl.usableBandwidth / 2 &&
      !(lower < rtlCenter + .005 && upper > rtlCenter - .005)) return 'RTL-SDR';
  if (lower >= model.airspy.center - model.airspy.usableBandwidth / 2 &&
      upper <= model.airspy.center + model.airspy.usableBandwidth / 2) return 'Airspy';
  return null;
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
  const rtlMode = node('span', '', 'Locked');
  for (const [name, detail, mode, className] of [
    [model.rtl.name, '2.4 MHz sampled · 2.352 MHz usable', rtlMode, 'rtl'],
    [model.airspy.name, '10 MHz sampled · 9 MHz usable', node('span', '', 'Locked'), 'airspy']
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
    'Frequency increases left to right. New transmissions appear at the top and move down into history. Colored windows show usable bandwidth. Solid calls are received; dashed calls outside coverage are missed. The unlocked RTL-SDR example shifts its window while retaining its existing channels.');
  const legend = node('div', 'ui-setup-guide-coverage-legend');
  for (const [kind, text] of [['received', 'Received'], ['missed', 'Missed'],
    ['control', 'Control channel'], ['edge', 'Excluded edges']]) {
    const entry = node('span');
    const key = node('i', `ui-setup-guide-coverage-mark ${kind}`);
    key.setAttribute('aria-hidden', 'true');
    entry.append(key, node('span', '', text));
    legend.append(entry);
  }
  const recent = node('div', 'ui-setup-guide-coverage-recent');
  recent.setAttribute('aria-label', 'Latest transmission');
  const note = node('p', 'ui-setup-guide-coverage-note',
    'Received calls can play through your selected Scan Lists after the transmission ends.');
  root.append(receivers, scene, svg, legend, recent, note);

  const mount = ({ signal } = {}) => {
    const reduced = windowValue.matchMedia('(prefers-reduced-motion: reduce)');
    let time = 0, playing = !reduced.matches && !document.hidden;
    let frame = null, lastFrame = null, lastPaint = null, disposed = false, observer = null;
    let lastScene = '', lastRecent = '', width = 960;
    const left = 44, right = 12, top = 36, plotHeight = 132, height = 207;
    const clipId = `${idPrefix}-clip`, edgeId = `${idPrefix}-edge`;
    const x = frequency => left + (frequency - 854.2) / (868.8 - 854.2) * (width - left - right);
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
    const rtlPaths = bandPaths('rtl'), airspyPaths = bandPaths('airspy');
    bands.append(...rtlPaths, ...airspyPaths);
    chart.append(bands, grid, controlTraces, callTraces);
    const annotations = svgNode('g');
    const rtlCaption = svgNode('text', { y: 22, 'text-anchor': 'middle' }, 'RTL-SDR');
    const airspyCaption = svgNode('text', { y: 22, 'text-anchor': 'middle' }, 'Airspy');
    svg.replaceChildren(title, description, defs, chart, annotations);
    const activeCalls = new Map();
    const sceneAt = () => {
      const phase = ((time % model.retuneCycle) + model.retuneCycle) % model.retuneCycle;
      if (phase < 5.25) return ['Several channels share each window', 'Control channels stay present while conventional and voice calls arrive.'];
      if (phase < 13.5) return ['A call falls outside usable coverage', 'Regional Fire lies beyond both usable receiving windows, so its audio is missed.'];
      if (phase < 14) return ['The RTL-SDR is unlocked', 'Its window can move when existing channels and the new frequency fit together.'];
      if (phase < 40) return ['Retuning changes which calls fit', 'The shifted RTL-SDR window covers Regional Fire alongside its existing control and conventional channels.'];
      if (phase < 154.5) return ['Each call needs usable coverage', 'Distant voice calls remain outside both windows. Earlier missed calls stay missed in the history.'];
      if (phase < 155) return ['The receiving window returns', 'The existing channels fit in both positions.'];
      return ['Locked windows stay in place', 'Calls outside these usable receiving windows are missed.'];
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
      root.dataset.rtlCenter = String(coverageCenterAt(time));
      const phase = ((time % model.retuneCycle) + model.retuneCycle) % model.retuneCycle;
      rtlMode.textContent = phase >= 13.5 && phase < 155 ? 'Unlocked' : 'Locked';
      const nextScene = sceneAt();
      if (nextScene[0] !== lastScene) {
        [sceneTitle.textContent, sceneCopy.textContent] = nextScene;
        lastScene = nextScene[0];
      }
      const moments = [time, time - model.history];
      for (let cycle = Math.floor((time - model.history) / model.retuneCycle); cycle <= Math.floor(time / model.retuneCycle); cycle += 1) {
        for (const offset of [14, 155]) {
          const boundary = cycle * model.retuneCycle + offset;
          if (boundary <= time && boundary > time - model.history) moments.push(boundary, boundary - .000001);
        }
      }
      moments.sort((a, b) => b - a);
      drawBand(rtlPaths, coverageCenterAt, model.rtl.usableBandwidth / 2, model.rtl.sampleRate / 2, moments);
      rtlCaption.setAttribute('x', x(coverageCenterAt(time)));
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
      const latest = visible.at(-1), recentKey = latest && `${latest.cycle}:${latest.id}`;
      if (latest && recentKey !== lastRecent) {
        const receiver = coverageReceiverFor(latest);
        recent.textContent = `${latest.name} · ${latest.frequency.toFixed(4)} MHz · ${receiver ? `Received by ${receiver}` : 'Missed outside usable coverage'}`;
        recent.dataset.received = String(Boolean(receiver));
        lastRecent = recentKey;
      }
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
        'text-anchor': 'middle' }, 'Frequency (MHz)'), rtlCaption, airspyCaption);
      airspyCaption.setAttribute('x', x(model.airspy.center));
      grid.replaceChildren(...lines); annotations.replaceChildren(...labels);
      controlTraces.replaceChildren(...model.controls.map(control => {
        const trace = svgNode('line', { x1: x(control.frequency), x2: x(control.frequency), y1: top,
          y2: top + plotHeight, class: 'coverage-control', 'data-control': control.name });
        trace.append(svgNode('title', {}, `${control.name} · ${control.frequency.toFixed(4)} MHz`));
        return trace;
      }));
      drawBand(airspyPaths, () => model.airspy.center, model.airspy.usableBandwidth / 2,
        model.airspy.sampleRate / 2, [time, time - model.history]);
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
      if (disposed || signal?.aborted || document.hidden || reduced.matches) return;
      cancelFrame(); playing = true; render();
      frame = windowValue.requestAnimationFrame(animate);
    };
    const visibility = () => { if (document.hidden) stop(); else start(); };
    const motion = () => { if (reduced.matches) stop(); else start(); };
    const cleanup = () => {
      if (disposed) return;
      disposed = true; playing = false; cancelFrame(); observer?.disconnect();
      document.removeEventListener('visibilitychange', visibility);
      windowValue.removeEventListener('pagehide', cleanup);
      reduced.removeEventListener('change', motion);
      signal?.removeEventListener('abort', cleanup);
      root.dataset.disposed = 'true'; root.dataset.playing = 'false';
    };
    document.addEventListener('visibilitychange', visibility);
    windowValue.addEventListener('pagehide', cleanup);
    reduced.addEventListener('change', motion);
    signal?.addEventListener('abort', cleanup, { once: true });
    if (windowValue.ResizeObserver) { observer = new windowValue.ResizeObserver(resize); observer.observe(root); }
    resize();
    if (signal?.aborted) cleanup();
    else if (playing) frame = windowValue.requestAnimationFrame(animate);
    return cleanup;
  };
  return { element: root, mount };
}
