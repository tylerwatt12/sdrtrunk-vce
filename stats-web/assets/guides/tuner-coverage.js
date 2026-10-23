// Fictional receiving windows. This illustration owns only its animation and DOM;
// the actual tuner page and receiver remain untouched throughout the overview.
const freezeRows = rows => Object.freeze(rows.map(row => Object.freeze(row)));
export const TUNER_COVERAGE_MODEL = Object.freeze({
  duration: 26, history: 24, speed: 2,
  initialCenter: 856.1625, shiftedCenter: 857.1625,
  rtl: Object.freeze({ name: 'RTL-SDR R820T2', sampleRate: 2.4, usableBandwidth: 2.352 }),
  airspy: Object.freeze({ name: 'Airspy R2', center: 862.6625, sampleRate: 10, usableBandwidth: 9 }),
  controls: freezeRows([{ name: 'County P25 control', frequency: 856.3625 },
    { name: 'City P25 control', frequency: 862.3125 }]),
  calls: freezeRows([
    { id: 'fire', name: 'Fire Dispatch', frequency: 856.6125, start: -1, end: 2.5, type: 'conventional' },
    { id: 'sheriff', name: 'Sheriff', frequency: 856.9125, start: 1, end: 5.2, type: 'voice' },
    { id: 'city-fire', name: 'City Fire', frequency: 861.5125, start: 3.5, end: 7.5, type: 'voice' },
    { id: 'regional-missed', name: 'Regional Fire', frequency: 857.9125, start: 7, end: 10.6, type: 'voice' },
    { id: 'public-works', name: 'Public Works', frequency: 865.8125, start: 9, end: 12, type: 'conventional' },
    { id: 'distant-missed', name: 'Distant voice call', frequency: 868.1125, start: 10.5, end: 13.5, type: 'voice' },
    { id: 'regional-later', name: 'Regional Fire', frequency: 857.9125, start: 14, end: 18.5, type: 'voice' },
    { id: 'ems', name: 'County EMS', frequency: 856.7625, start: 16, end: 19.2, type: 'voice' },
    { id: 'city-police', name: 'City Police', frequency: 863.5125, start: 18.5, end: 22.5, type: 'voice' },
    { id: 'distant-later', name: 'Distant voice call', frequency: 868.1125, start: 21, end: 24, type: 'voice' }
  ])
});

export function coverageCenterAt(time, includeRetune = true) {
  const model = TUNER_COVERAGE_MODEL;
  return model.initialCenter + (includeRetune ? model.shiftedCenter - model.initialCenter : 0) *
    Math.max(0, Math.min(1, (time - 13.5) / .5));
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
  const controls = node('div', 'ui-setup-guide-coverage-controls');
  const play = node('button', 'ui-button ui-button-secondary', 'Pause');
  const replay = node('button', 'ui-button ui-button-secondary', 'Replay');
  play.type = replay.type = 'button';
  play.dataset.coveragePlay = replay.dataset.coverageReplay = '';
  const retuneLabel = node('label', 'ui-setup-guide-coverage-retune');
  const retune = node('input', 'ui-selection-check');
  retune.type = 'checkbox';
  retune.checked = true;
  retune.dataset.coverageRetune = '';
  retuneLabel.append(retune, node('span', '', 'Include an unlocked RTL-SDR example'));
  const elapsed = node('span', 'ui-setup-guide-coverage-time');
  elapsed.dataset.coverageTime = '';
  controls.append(play, replay, retuneLabel, elapsed);
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
  scene.setAttribute('aria-live', 'polite');
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
  root.append(controls, receivers, scene, svg, legend, recent, note);

  const mount = ({ signal } = {}) => {
    const reduced = windowValue.matchMedia('(prefers-reduced-motion: reduce)');
    let time = 0, includeRetune = true, playing = !reduced.matches && !document.hidden;
    let frame = null, lastFrame = null, lastPaint = null, disposed = false;
    let lastScene = '', lastRecent = '', width = 960;
    const left = 44, right = 12, top = 36, plotHeight = 132, height = 207;
    const clipId = `${idPrefix}-clip`, edgeId = `${idPrefix}-edge`;
    const x = frequency => left + (frequency - 854.2) / (868.8 - 854.2) * (width - left - right);
    const y = transmissionTime => top + (time - transmissionTime) / model.history * plotHeight;
    const sceneAt = () => {
      if (time < 6.5) return ['Several channels share each window', 'Control channels stay present while conventional and voice calls arrive.'];
      if (time < 13) return ['A call falls outside usable coverage', 'Regional Fire begins beyond both usable receiving windows, so its audio is missed.'];
      if (!includeRetune) return ['Locked windows stay in place', 'The later Regional Fire call also falls outside coverage.'];
      if (time < 14) return ['The RTL-SDR is unlocked', 'Its window can move when existing channels and the new frequency fit together.'];
      if (time < 20.5) return ['A later call fits after retuning', 'Regional Fire now fits alongside the RTL-SDR’s existing control and conventional channels.'];
      return ['Each call needs usable coverage', 'Distant voice calls remain outside both windows. Earlier missed calls stay missed in the history.'];
    };
    const band = (target, centerAt, usableHalf, sampledHalf, className) => {
      const points = [];
      for (let moment = time; moment > time - model.history; moment -= .25) points.push([moment, centerAt(moment)]);
      points.push([time - model.history, centerAt(time - model.history)]);
      const path = (start, end) => `M${points.map(([moment, center]) => `${x(center + start)},${y(moment)}`).join('L')}` +
        `L${[...points].reverse().map(([moment, center]) => `${x(center + end)},${y(moment)}`).join('L')}Z`;
      target.append(svgNode('path', { d: path(-sampledHalf, -usableHalf), fill: `url(#${edgeId})` }),
        svgNode('path', { d: path(usableHalf, sampledHalf), fill: `url(#${edgeId})` }),
        svgNode('path', { d: path(-usableHalf, usableHalf), class: className }));
    };
    const render = () => {
      if (disposed) return;
      root.dataset.time = time.toFixed(2);
      root.dataset.playing = String(playing);
      root.dataset.rtlCenter = String(coverageCenterAt(time, includeRetune));
      play.textContent = playing ? 'Pause' : time >= model.duration ? 'Play again' : 'Play';
      elapsed.textContent = `${Math.floor(time)} / ${model.duration} s`;
      rtlMode.textContent = includeRetune && time >= 13 ? 'Unlocked' : 'Locked';
      const nextScene = sceneAt();
      if (nextScene[0] !== lastScene) {
        [sceneTitle.textContent, sceneCopy.textContent] = nextScene;
        lastScene = nextScene[0];
      }
      svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
      const defs = svgNode('defs');
      const clip = svgNode('clipPath', { id: clipId });
      clip.append(svgNode('rect', { x: left, y: top, width: width - left - right, height: plotHeight }));
      const edge = svgNode('pattern', { id: edgeId, width: 6, height: 6, patternUnits: 'userSpaceOnUse' });
      edge.append(svgNode('path', { d: 'M-1 1L1-1M0 6L6 0M5 7L7 5', class: 'coverage-edge-line' }));
      defs.append(clip, edge);
      const chart = svgNode('g', { 'clip-path': `url(#${clipId})` });
      band(chart, moment => coverageCenterAt(moment, includeRetune), model.rtl.usableBandwidth / 2,
        model.rtl.sampleRate / 2, 'coverage-band-rtl');
      band(chart, () => model.airspy.center, model.airspy.usableBandwidth / 2,
        model.airspy.sampleRate / 2, 'coverage-band-airspy');
      const annotations = [];
      for (const tick of width < 500 ? [855, 859, 863, 867] : [855, 857, 859, 861, 863, 865, 867]) {
        chart.append(svgNode('line', { x1: x(tick), x2: x(tick), y1: top, y2: top + plotHeight, class: 'coverage-grid' }));
        annotations.push(svgNode('text', { x: x(tick), y: top + plotHeight + 18, 'text-anchor': 'middle' }, tick));
      }
      for (const age of [0, 6, 12, 18, 24]) {
        const position = top + age / model.history * plotHeight;
        chart.append(svgNode('line', { x1: left, x2: width - right, y1: position, y2: position, class: 'coverage-grid' }));
        annotations.push(svgNode('text', { x: left - 7, y: position + 4, 'text-anchor': 'end' }, age ? `${age}s` : 'Now'));
      }
      annotations.push(svgNode('text', { x: (left + width - right) / 2, y: height - 2,
        'text-anchor': 'middle' }, 'Frequency (MHz)'),
      svgNode('text', { x: x(coverageCenterAt(time, includeRetune)), y: 22, 'text-anchor': 'middle' }, 'RTL-SDR'),
      svgNode('text', { x: x(model.airspy.center), y: 22, 'text-anchor': 'middle' }, 'Airspy'));
      model.controls.forEach(control => {
        const trace = svgNode('line', { x1: x(control.frequency), x2: x(control.frequency), y1: top,
          y2: top + plotHeight, class: 'coverage-control', 'data-control': control.name });
        trace.append(svgNode('title', {}, `${control.name} · ${control.frequency.toFixed(4)} MHz`));
        chart.append(trace);
      });
      model.calls.forEach(call => {
        if (time < call.start || call.end < time - model.history) return;
        const receiver = coverageReceiverFor(call, includeRetune);
        const trace = svgNode('line', { x1: x(call.frequency), x2: x(call.frequency), y1: y(Math.min(call.end, time)),
          y2: y(call.start), class: `coverage-call ${call.type} ${receiver ? 'received' : 'missed'}`,
          'data-call': call.id, 'data-received': String(Boolean(receiver)) });
        trace.append(svgNode('title', {}, `${call.name} · ${call.frequency.toFixed(4)} MHz · ${receiver ? `Received by ${receiver}` : 'Missed outside usable coverage'}`));
        chart.append(trace);
      });
      svg.replaceChildren(title, description, defs, chart, ...annotations);
      const latest = model.calls.filter(call => call.start <= time).at(-1);
      if (latest && latest.id !== lastRecent) {
        const receiver = coverageReceiverFor(latest, includeRetune);
        recent.textContent = `${latest.name} · ${latest.frequency.toFixed(4)} MHz · ${receiver ? `Received by ${receiver}` : 'Missed outside usable coverage'}`;
        recent.dataset.received = String(Boolean(receiver));
        lastRecent = latest.id;
      }
    };
    const cancelFrame = () => {
      if (frame !== null) windowValue.cancelAnimationFrame(frame);
      frame = lastFrame = lastPaint = null;
    };
    const stop = () => { playing = false; cancelFrame(); render(); };
    const animate = timestamp => {
      frame = null;
      if (disposed || !playing) return;
      if (lastFrame !== null) time = Math.min(model.duration, time + (timestamp - lastFrame) / 1000 * model.speed);
      lastFrame = timestamp;
      if (time >= model.duration) { stop(); return; }
      if (lastPaint === null || timestamp - lastPaint >= 40) { render(); lastPaint = timestamp; }
      frame = windowValue.requestAnimationFrame(animate);
    };
    const start = () => {
      if (disposed || document.hidden) return;
      cancelFrame();
      if (time >= model.duration) time = 0;
      playing = true;
      render();
      frame = windowValue.requestAnimationFrame(animate);
    };
    const toggle = () => { if (playing) stop(); else start(); };
    const reset = () => { stop(); time = 0; lastScene = lastRecent = ''; start(); };
    const changeRetune = () => { includeRetune = retune.checked; reset(); };
    const visibility = () => { if (document.hidden) stop(); };
    const motion = () => { if (reduced.matches) stop(); };
    const resize = () => { width = Math.max(300, Math.round(root.getBoundingClientRect().width) || 960); render(); };
    const cleanup = () => {
      if (disposed) return;
      disposed = true;
      playing = false;
      cancelFrame();
      observer?.disconnect();
      play.removeEventListener('click', toggle);
      replay.removeEventListener('click', reset);
      retune.removeEventListener('change', changeRetune);
      document.removeEventListener('visibilitychange', visibility);
      windowValue.removeEventListener('pagehide', cleanup);
      reduced.removeEventListener('change', motion);
      signal?.removeEventListener('abort', cleanup);
      root.dataset.disposed = 'true';
      root.dataset.playing = 'false';
    };
    let observer = null;
    play.addEventListener('click', toggle);
    replay.addEventListener('click', reset);
    retune.addEventListener('change', changeRetune);
    document.addEventListener('visibilitychange', visibility);
    windowValue.addEventListener('pagehide', cleanup);
    reduced.addEventListener('change', motion);
    signal?.addEventListener('abort', cleanup, { once: true });
    if (windowValue.ResizeObserver) {
      observer = new windowValue.ResizeObserver(resize);
      observer.observe(root);
    }
    resize();
    if (signal?.aborted) cleanup();
    else if (playing) frame = windowValue.requestAnimationFrame(animate);
    return cleanup;
  };
  return { element: root, mount };
}
