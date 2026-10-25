// Fictional receiving windows. All hardware examples follow the same calls;
// the actual tuner page, saved settings and receiver remain untouched.
const freezeRows = rows => Object.freeze(rows.map(row => Object.freeze(row)));
const rtl = Object.freeze({ name: 'RTL-SDR R820T2', sampleRate: 2.4, usableBandwidth: 2.352, centerGap: .005 });
// Match VCE's Airspy 10 MHz receiving geometry: 90% usable, no center exclusion.
const airspy = Object.freeze({ name: 'Airspy', sampleRate: 10, usableBandwidth: 9, centerGap: 0 });
const primaryCenter = 856.1625, initialCenter = 860.6625, unlockAt = 13.5;
const duration = 360, history = 24, speed = 6;
const groups = [
  ['Regional Fire', 857.9125, 'Regional Fireground', 858.1125],
  ['City Police', 859.5125, 'City Police Tactical', 859.8125],
  ['City Fire', 861.5125, 'City EMS', 861.8125],
  ['County Roads', 862.8125, 'Public Works', 863.1125]
];
const groupOrder = [0, 2, 0, 3, 1, 2, 3, 0, 1, 3];
const traffic = [];
const addCall = (id, name, frequency, start, length) => traffic.push({ id, name, frequency,
  start, end: Math.min(start + length, duration - .25), type: 'voice' });
// Several conversations can occupy one receiving window at once. Each frequency
// has a clear interval between transmissions, while calls elsewhere overlap it.
const local = [['Fire Dispatch', 856.4125], ['Sheriff', 856.9125],
  ['Sheriff Tactical', 857.1125], ['County EMS', 855.4125]];
for (let index = 0; index < 120; index += 1) {
  const [name, frequency] = local[index % local.length];
  addCall(index === 0 ? 'fire' : index === 1 ? 'sheriff' : `local-${index}`, name,
    frequency, .5 + index * 3, 4.2 + (index * 7 % 6) * .35);
}
for (let index = 0; index < 40; index += 1) {
  const slot = groupOrder[index % groupOrder.length], group = groups[slot];
  const start = .75 + index * 9;
  const id = index === 0 ? 'regional-missed' : index === 2 ? 'regional-later' :
    index === 3 ? 'distant-followed' : index === 6 ? 'distant-later' : `voice-${index}`;
  addCall(id, group[0], group[1], start, 5 + index % 4 * .4);
  addCall(`nearby-${index}`, group[2], group[3], start + .65, 4.8 + index % 3 * .4);
  const other = groups[(slot + 1) % groups.length], farther = groups[(slot + 2) % groups.length];
  addCall(`competing-${index}`, other[0], other[1], start + 1.5, 3.6 + index % 4 * .35);
  addCall(`elsewhere-${index}`, farther[0], farther[1], start + 2.75, 3.5 + index % 3 * .45);
}
const calls = freezeRows(traffic.sort((a, b) => a.start - b.start || a.frequency - b.frequency));
const fitsWindow = (frequency, center, hardware = rtl) => {
  const lower = frequency - .00625, upper = frequency + .00625;
  return lower >= center - hardware.usableBandwidth / 2 && upper <= center + hardware.usableBandwidth / 2 &&
    (!hardware.centerGap || !(lower < center + hardware.centerGap && upper > center - hardware.centerGap));
};
const retunes = [];
let movingCenter = initialCenter;
let receiving = [];
for (const call of calls) {
  receiving = receiving.filter(active => active.end > call.start - .25);
  if (fitsWindow(call.frequency, primaryCenter)) continue;
  if (fitsWindow(call.frequency, movingCenter)) { receiving.push(call); continue; }
  // Keep every received call in coverage until it ends. A competing call outside
  // both windows is missed when the roaming tuner is already receiving traffic.
  if (call.start < unlockAt || receiving.length) continue;
  const center = call.frequency === 857.9125 ? 858.1625 : call.frequency - .35;
  retunes.push({ start: call.start - .25, end: call.start - .125, from: movingCenter, to: center, receiverIndex: 1 });
  movingCenter = center;
  receiving.push(call);
}
// A retune must retain every channel already being received, including the
// persistent trunked control channel. Match the passband and center-gap checks
// used by VCE's tuner coverage planner; choose the nearest legal placement.
const legalCenter = (frequencies, center, hardware = rtl) => {
  const half = hardware.usableBandwidth / 2, channelHalf = .00625;
  const low = Math.max(...frequencies.map(frequency => frequency + channelHalf - half));
  const high = Math.min(...frequencies.map(frequency => frequency - channelHalf + half));
  if (low > high) return null;
  const bounded = value => Math.max(low, Math.min(value, high));
  const candidates = [bounded(center), low, high, (low + high) / 2];
  for (const frequency of frequencies) candidates.push(
    bounded(frequency - channelHalf - hardware.centerGap - .000001),
    bounded(frequency + channelHalf + hardware.centerGap + .000001));
  return candidates.filter(candidate => frequencies.every(frequency => fitsWindow(frequency, candidate, hardware)))
    .sort((a, b) => Math.abs(a - center) - Math.abs(b - center) || a - b)[0] ?? null;
};
const controlFrequency = 856.6625;
const buildPlacements = (centers, lockedUntil) => {
  const current = [...centers], active = centers.map(() => []), changes = [];
  for (const call of calls) {
    const retuneStart = call.start - .25;
    active.forEach((received, index) => { active[index] = received.filter(item => item.end > retuneStart); });
    const covered = current.findIndex(center => fitsWindow(call.frequency, center));
    if (covered >= 0) { active[covered].push(call); continue; }
    const candidates = current.flatMap((center, receiverIndex) => {
      if (call.start < lockedUntil[receiverIndex]) return [];
      const required = active[receiverIndex].map(item => item.frequency);
      if (receiverIndex === 0) required.push(controlFrequency);
      const next = legalCenter([...required, call.frequency], center);
      return next === null ? [] : [{ receiverIndex, center: next, distance: Math.abs(next - center) }];
    }).sort((a, b) => a.distance - b.distance || a.receiverIndex - b.receiverIndex);
    const placement = candidates[0];
    if (!placement) continue;
    const index = placement.receiverIndex;
    changes.push({ start: retuneStart, end: call.start - .125,
      from: current[index], to: placement.center, receiverIndex: index });
    current[index] = placement.center;
    active[index].push(call);
  }
  return freezeRows(changes);
};
const shared = { duration, history, speed, unlockAt, rtl, airspy, calls,
  frequencyMin: 854, frequencyMax: 864.4,
  maximumCallDuration: Math.max(...calls.map(call => call.end - call.start)),
  controls: freezeRows([{ name: 'County P25 control', frequency: controlFrequency }]) };
const singleCenters = Object.freeze([primaryCenter]);
const doubleCenters = Object.freeze([primaryCenter, initialCenter]);
const tripleCenters = Object.freeze([primaryCenter, initialCenter, 858.1625]);
export const TUNER_COVERAGE_EXAMPLES = Object.freeze({
  'rtl-single': Object.freeze({ ...shared, primaryCenter, initialCenter: primaryCenter,
    initialCenters: singleCenters, lockedUntil: Object.freeze([0]),
    retunes: buildPlacements(singleCenters, [0]),
    receivers: freezeRows([{ ...rtl, name: 'RTL-SDR 1' }]) }),
  rtl: Object.freeze({ ...shared, primaryCenter, initialCenter, initialCenters: doubleCenters,
    lockedUntil: Object.freeze([Infinity, unlockAt]), retunes: freezeRows(retunes),
    receivers: freezeRows([{ ...rtl, name: 'RTL-SDR 1' }, { ...rtl, name: 'RTL-SDR 2' }]) }),
  'rtl-three': Object.freeze({ ...shared, primaryCenter, initialCenter, initialCenters: tripleCenters,
    lockedUntil: Object.freeze([Infinity, unlockAt, unlockAt]),
    retunes: buildPlacements(tripleCenters, [Infinity, unlockAt, unlockAt]),
    receivers: freezeRows([{ ...rtl, name: 'RTL-SDR 1' }, { ...rtl, name: 'RTL-SDR 2' }, { ...rtl, name: 'RTL-SDR 3' }]) }),
  airspy: Object.freeze({ ...shared, primaryCenter: 859.1625, initialCenter: 859.1625,
    initialCenters: Object.freeze([859.1625]), lockedUntil: Object.freeze([Infinity]),
    retunes: Object.freeze([]), receivers: freezeRows([{ ...airspy, name: 'Airspy' }]) })
});
export const TUNER_COVERAGE_MODEL = TUNER_COVERAGE_EXAMPLES.rtl;
const exampleModel = example => TUNER_COVERAGE_EXAMPLES[example] || TUNER_COVERAGE_MODEL;

export function coverageTimeAt(time) {
  const remainder = time % duration;
  return remainder < 0 ? remainder + duration : remainder;
}

export function coverageCentersAt(time, includeRetune = true, example = 'rtl') {
  const model = exampleModel(example), phase = coverageTimeAt(time);
  const centers = [...model.initialCenters];
  // Retuning is a discrete placement change, not a sweep through the frequencies.
  if (includeRetune) for (const retune of model.retunes) {
    // Wrapped decimal timestamps can lose a few machine-precision bits.
    if (phase + 1e-10 < retune.end) break;
    centers[retune.receiverIndex] = retune.to;
  }
  return centers;
}

export function coverageCenterAt(time, includeRetune = true, example = 'rtl') {
  const centers = coverageCentersAt(time, includeRetune, example);
  // Preserve the original two-tuner API, which reports the roaming receiver.
  return centers[centers.length > 1 ? 1 : 0];
}

export function coverageCallsAt(time) {
  const visible = [];
  // The shared schedule contains overlapping arrivals at irregular intervals.
  // Include complete intersecting transmissions from both sides of the loop.
  const first = Math.floor((time - history - TUNER_COVERAGE_MODEL.maximumCallDuration) / duration);
  for (let cycle = first; cycle <= Math.floor(time / duration); cycle += 1) {
    const offset = cycle * duration;
    for (const call of calls) {
      if (call.start + offset > time) break;
      if (call.end + offset >= time - history) visible.push({ ...call, sourceId: call.id,
        cycle, start: call.start + offset, end: call.end + offset });
    }
  }
  return visible;
}

export function coverageReceiverFor(call, includeRetune = true, example = 'rtl') {
  const model = exampleModel(example), centers = coverageCentersAt(call.start, includeRetune, example);
  for (let index = 0; index < model.receivers.length; index += 1) {
    const hardware = model.receivers[index];
    if (!fitsWindow(call.frequency, centers[index], hardware)) continue;
    let covered = true;
    // Keep the outcome fixed for the whole call, including wrapped history.
    if (includeRetune && Number.isFinite(call.end)) {
      const firstCycle = Math.floor(call.start / duration), lastCycle = Math.floor(call.end / duration);
      const boundaries = [0, ...model.retunes.filter(retune => retune.receiverIndex === index).map(retune => retune.end)];
      for (let cycle = firstCycle; covered && cycle <= lastCycle; cycle += 1) {
        for (const boundary of boundaries) {
          const moment = cycle * duration + boundary;
          if (moment > call.start && moment < call.end && !fitsWindow(call.frequency,
            coverageCentersAt(moment, true, example)[index], hardware)) { covered = false; break; }
        }
      }
    }
    if (covered) return hardware.name;
  }
  return null;
}

export function createTunerCoverageDiagram({ document, idPrefix }) {
  let example = 'rtl', model = TUNER_COVERAGE_MODEL;
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
  let receiverModes = [];
  const renderReceivers = () => {
    receivers.replaceChildren();
    receiverModes = [];
    model.receivers.forEach((hardware, index) => {
      const receiver = node('div', 'ui-setup-guide-coverage-receiver');
      const identity = node('div', 'ui-setup-guide-coverage-identity');
      const key = node('i', `ui-setup-guide-coverage-key ${index ? 'secondary' : 'rtl'}`);
      key.setAttribute('aria-hidden', 'true');
      const mode = node('span');
      receiverModes.push(mode);
      identity.append(key, node('strong', '', hardware.name), mode);
      receiver.append(identity, node('span', 'ui-setup-guide-coverage-detail',
        `${hardware.sampleRate} MHz sampled · ${hardware.usableBandwidth} MHz usable`));
      receivers.append(receiver);
    });
  };
  renderReceivers();
  const scene = node('div', 'ui-setup-guide-coverage-scene');
  scene.dataset.coverageScene = '';
  const sceneTitle = node('strong');
  const sceneCopy = node('span');
  scene.append(sceneTitle, sceneCopy);
  const svg = svgNode('svg', { class: 'ui-setup-guide-coverage-chart', role: 'img',
    'aria-labelledby': `${idPrefix}-title`, 'aria-describedby': `${idPrefix}-description` });
  const title = svgNode('title', { id: `${idPrefix}-title` }, 'Calls moving through receiving windows over time');
  const description = svgNode('desc', { id: `${idPrefix}-description` });
  const legend = node('div', 'ui-setup-guide-coverage-legend');
  for (const [kind, text] of [['received', 'Received'], ['missed', 'Missed'],
    ['control', 'Control channel'], ['edge', 'Excluded edges']]) {
    const entry = node('span');
    const key = node('i', `ui-setup-guide-coverage-mark ${kind}`);
    key.setAttribute('aria-hidden', 'true');
    entry.append(key, node('span', '', text));
    legend.append(entry);
  }
  const choices = node('div', 'ui-setup-guide-coverage-examples');
  const toggle = node('div', 'ui-segmented');
  toggle.setAttribute('role', 'group');
  toggle.setAttribute('aria-label', 'Example tuner hardware');
  const exampleButtons = Object.entries({ 'rtl-single': '1× RTL-SDR (unlocked)', rtl: '2× RTL-SDR',
    'rtl-three': '3× RTL-SDR', airspy: '1× Airspy' }).map(([id, label]) => {
    const button = node('button', 'ui-segmented-option', label);
    button.type = 'button';
    button.setAttribute('aria-pressed', String(id === example));
    toggle.append(button);
    return { id, button };
  });
  choices.append(node('strong', '', 'Example:'), toggle);
  root.append(node('hr', 'ui-setup-guide-divider'),
    node('p', 'ui-setup-guide-description', 'This example follows one trunked radio system.'),
    receivers, scene, svg, legend, choices);

  const mount = ({ signal } = {}) => {
    // This instructional simulation autoplays even under reduced-motion preferences.
    let time = 0, playing = !document.hidden;
    let frame = null, lastFrame = null, lastPaint = null, disposed = false, observer = null;
    let lastScene = '', width = 960;
    const left = 44, right = 12, top = 36, plotHeight = 132, height = 207;
    const clipId = `${idPrefix}-clip`, edgeId = `${idPrefix}-edge`;
    const x = frequency => left + (frequency - model.frequencyMin) /
      (model.frequencyMax - model.frequencyMin) * (width - left - right);
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
    let receiverBands = [], receiverCaptions = [];
    const renderBands = () => {
      receiverBands = model.receivers.map((hardware, index) => {
        const paths = bandPaths(index ? 'secondary' : 'rtl');
        paths.forEach(path => path.setAttribute('data-receiver-index', String(index)));
        return paths;
      });
      receiverCaptions = model.receivers.map((hardware, index) => svgNode('text', {
        y: 22, 'text-anchor': 'middle', 'data-receiver-index': index }, hardware.name));
      bands.replaceChildren(...receiverBands.flat());
    };
    renderBands();
    chart.append(bands, grid, controlTraces, callTraces);
    const annotations = svgNode('g');
    svg.replaceChildren(title, description, defs, chart, annotations);
    const activeCalls = new Map();
    const sceneAt = () => {
      if (example === 'airspy') return ['One wider window covers this system',
        'The control channel and all these simultaneous voice calls fit inside the Airspy’s usable bandwidth.'];
      if (example === 'rtl-single') return ['Unlocked still keeps the control channel covered',
        'The window can shift while current calls and the control channel still fit. Calls farther away are missed.'];
      if (example === 'rtl-three') return ['Two tuners follow the voice traffic',
        'RTL-SDR 1 keeps the control channel. RTL-SDR 2 and 3 move independently while keeping their current calls covered.'];
      const phase = coverageTimeAt(time);
      if (phase < 5.25) return ['One tuner holds the control channel', 'RTL-SDR 1 receives the control channel and other frequencies inside its usable window.'];
      if (phase < model.unlockAt) return ['Locked windows stay in place', 'Regional Fire is beyond both usable windows, so that call is missed.'];
      return ['Busy traffic spreads across the system',
        'RTL-SDR 2 retunes when its calls finish. Simultaneous calls beyond both windows are missed.'];
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
      root.dataset.example = example;
      const centers = coverageCentersAt(time, true, example), phase = coverageTimeAt(time);
      root.dataset.receiverCount = String(model.receivers.length);
      root.dataset.centers = JSON.stringify(centers);
      root.dataset.rtlCenter = String(centers[0]);
      root.dataset.primaryCenter = String(centers[0]);
      root.dataset.secondaryCenter = centers.length > 1 ? String(centers[1]) : '';
      root.dataset.tertiaryCenter = centers.length > 2 ? String(centers[2]) : '';
      root.dataset.secondaryLocked = String(centers.length > 1 && phase < model.lockedUntil[1]);
      receiverModes.forEach((mode, index) => {
        mode.textContent = phase < model.lockedUntil[index] ? 'Locked' : 'Unlocked';
      });
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
      model.receivers.forEach((hardware, index) => {
        drawBand(receiverBands[index], moment => coverageCentersAt(moment, true, example)[index],
          hardware.usableBandwidth / 2, hardware.sampleRate / 2, moments);
        receiverBands[index][2].setAttribute('data-center', String(centers[index]));
        receiverCaptions[index].setAttribute('x', x(centers[index]));
        // Nearby windows can share frequencies; keep their names readable.
        receiverCaptions[index].setAttribute('y', index > 0 && centers.slice(0, index)
          .some(center => Math.abs(x(center) - x(centers[index])) < 85) ? '10' : '22');
      });
      const visible = coverageCallsAt(time), keys = new Set();
      for (const call of visible) {
        const key = `${call.cycle}:${call.id}`;
        keys.add(key);
        let trace = activeCalls.get(key);
        if (!trace) {
          const receiver = coverageReceiverFor(call, true, example);
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
      for (const tick of width < 500 ? [855, 859, 863] : [855, 857, 859, 861, 863]) {
        lines.push(svgNode('line', { x1: x(tick), x2: x(tick), y1: top, y2: top + plotHeight, class: 'coverage-grid' }));
        labels.push(svgNode('text', { x: x(tick), y: top + plotHeight + 18, 'text-anchor': 'middle' }, tick));
      }
      for (const age of [0, 6, 12, 18, 24]) {
        const position = top + age / model.history * plotHeight;
        lines.push(svgNode('line', { x1: left, x2: width - right, y1: position, y2: position, class: 'coverage-grid' }));
        labels.push(svgNode('text', { x: left - 7, y: position + 4, 'text-anchor': 'end' }, age ? `${age}s` : 'Now'));
      }
      labels.push(svgNode('text', { x: (left + width - right) / 2, y: height - 2,
        'text-anchor': 'middle' }, 'Frequency (MHz)'), ...receiverCaptions);
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
    const describeExample = () => {
      description.textContent = 'Frequency increases left to right. New transmissions appear at the top and move down into history. ' +
        'Colored windows show usable bandwidth. Solid calls are received; dashed calls outside coverage are missed. ' +
        (example === 'airspy' ? 'One Airspy receives every call shown within its wider window.' :
          example === 'rtl-single' ? 'One unlocked RTL-SDR shifts only when its control channel and every current call remain covered.' :
          example === 'rtl-three' ? 'RTL-SDR 1 holds the control channel. RTL-SDR 2 and 3 follow voice traffic independently.' :
            'RTL-SDR 1 holds the control channel while RTL-SDR 2 follows voice calls. Multiple calls inside either window can be received together.');
    };
    const selectExample = id => {
      if (disposed || example === id) return;
      example = id; model = exampleModel(id);
      exampleButtons.forEach(({ id: option, button }) => button.setAttribute('aria-pressed', String(option === id)));
      renderReceivers();
      renderBands();
      // Compare identical traffic at the current moment, including its history.
      // No extra animation loop, timeline reset or saved receiver setting is used.
      activeCalls.clear(); callTraces.replaceChildren(); lastScene = '';
      describeExample();
      resize();
    };
    const choiceListeners = exampleButtons.map(({ id, button }) => {
      const listener = () => selectExample(id);
      button.addEventListener('click', listener);
      return () => button.removeEventListener('click', listener);
    });
    describeExample();
    const cleanup = () => {
      if (disposed) return;
      disposed = true; playing = false; cancelFrame(); observer?.disconnect();
      document.removeEventListener('visibilitychange', visibility);
      windowValue.removeEventListener('pagehide', cleanup);
      signal?.removeEventListener('abort', cleanup);
      choiceListeners.forEach(release => release());
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
