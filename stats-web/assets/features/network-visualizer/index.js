'use strict';

import {
  P25_HISTORY_ACTIONS,
  createP25HistoryState,
  applyP25ActivityRows,
  buildP25Graph,
  groupedP25Events,
  mostActiveP25System
} from './history.js';
import { createP25CameraCoordinator } from './camera.js';
import { createP25Renderer } from './renderer.js';

const HOUR_MS = 60 * 60 * 1_000;
const POLL_MS = 5_000;
const PAGE_LIMIT = 5_000;
const MAX_SEED_ROWS = 250_000;
const MAX_POLL_ROWS = 25_000;
const CLOCK_TOLERANCE_MS = 2_000;

function textButton(node, label, className = 'ui-button ui-button-secondary') {
  const button = node('button', className, label);
  button.type = 'button';
  return button;
}

function formatTime(value) {
  const timestamp = Number(value);
  return Number.isFinite(timestamp) && timestamp > 0 ? new Intl.DateTimeFormat(undefined, {
    hour: 'numeric', minute: '2-digit', second: '2-digit'
  }).format(timestamp) : 'Unknown time';
}

function validPage(value) {
  if (!value || typeof value !== 'object' || !Array.isArray(value.rows) || typeof value.has_more !== 'boolean') {
    throw new Error('The receiver returned invalid saved P25 activity.');
  }
  return value;
}

function reseedError(message) {
  const error = new Error(message);
  error.reseed = true;
  return error;
}

function createP25Visualizer(dependencies = {}) {
  const required = ['node', 'iconGlyph', 'iconButton', 'requestActivity'];
  required.forEach((name) => {
    if (typeof dependencies[name] !== 'function') throw new TypeError(`P25 Visualizer dependency ${name} is required.`);
  });
  const { node, iconGlyph, iconButton, requestActivity } = dependencies;
  const layout = node('section', 'network-visualizer-layout');
  layout.setAttribute('aria-label', 'P25 Visualizer');
  const toolbar = node('div', 'network-visualizer-toolbar');
  toolbar.setAttribute('role', 'toolbar');
  toolbar.setAttribute('aria-label', 'P25 Visualizer controls');
  const brand = node('div', 'network-visualizer-brand');
  brand.append(iconGlyph('icon-network-visualizer'), node('strong', '', 'P25 Visualizer'));
  const status = node('span', 'badge ui-pill state-stale network-visualizer-status', 'Loading saved activity');
  status.setAttribute('role', 'status');
  status.setAttribute('aria-live', 'polite');

  const actions = node('div', 'network-visualizer-actions');
  const scopeControls = node('div', 'network-visualizer-choice-group');
  scopeControls.setAttribute('role', 'group');
  scopeControls.setAttribute('aria-label', 'History scope');
  const oneHour = textButton(node, '1 hour');
  const day = textButton(node, '24 hours');
  oneHour.setAttribute('aria-pressed', 'true');
  day.setAttribute('aria-pressed', 'false');
  scopeControls.append(oneHour, day);
  const cameraControls = node('div', 'network-visualizer-choice-group');
  cameraControls.setAttribute('role', 'group');
  cameraControls.setAttribute('aria-label', 'Camera mode');
  const auto = textButton(node, 'Auto');
  const manual = textButton(node, 'Manual');
  auto.setAttribute('aria-pressed', 'true');
  manual.setAttribute('aria-pressed', 'false');
  cameraControls.append(auto, manual);
  const eventsToggle = textButton(node, 'Events');
  eventsToggle.setAttribute('aria-pressed', 'false');
  const fullscreen = iconButton('icon-fullscreen', 'Enter fullscreen');
  fullscreen.classList.add('network-visualizer-fullscreen');
  actions.append(scopeControls, cameraControls, eventsToggle, fullscreen);
  toolbar.append(brand, status, actions);

  const stage = node('div', 'network-visualizer-stage');
  stage.tabIndex = 0;
  stage.setAttribute('aria-label', 'Interactive P25 history visualization');
  const canvas = node('div', 'network-visualizer-canvas');
  const back = textButton(node, '← All systems',
    'ui-button ui-button-secondary network-visualizer-back');
  back.hidden = true;
  const scopeTitle = node('div', 'network-visualizer-scope-title', 'P25 radio systems');
  const autoFocus = node('aside', 'network-visualizer-auto-focus');
  autoFocus.hidden = true;
  autoFocus.setAttribute('role', 'status');
  autoFocus.setAttribute('aria-live', 'polite');
  autoFocus.setAttribute('aria-atomic', 'true');
  const autoFocusKicker = node('span', 'network-visualizer-auto-focus-kicker', 'AUTO FOCUS');
  const autoFocusTitle = node('strong', 'network-visualizer-auto-focus-title');
  const autoFocusDetail = node('span', 'network-visualizer-auto-focus-detail');
  autoFocus.append(autoFocusKicker, autoFocusTitle, autoFocusDetail);
  const manualGuide = node('aside', 'network-visualizer-manual-guide');
  manualGuide.hidden = true;
  manualGuide.append(node('strong', '', 'Manual camera'), node('span', '', 'W/S move · A/D strafe · Q/E rise'),
    node('span', '', 'Arrow keys look · Drag orbits · Wheel zooms at cursor'));
  const empty = node('div', 'network-visualizer-empty');
  empty.append(iconGlyph('icon-network-visualizer'), node('strong', '', 'Loading saved P25 activity…'),
    node('p', '', 'This view uses only persisted Activity history.'));
  const notice = node('div', 'network-visualizer-notice');
  notice.hidden = true;
  const legend = node('div', 'network-visualizer-legend');
  legend.append(node('span', '', 'Sphere · system'), node('span', '', 'Cube · talkgroup'),
    node('span', '', 'Triangle · radio'), node('span', '', 'Faint line · earlier affiliation'));
  const events = node('aside', 'network-visualizer-events');
  events.hidden = true;
  const eventsHeader = node('header', 'network-visualizer-panel-header');
  const eventsHeading = node('div', 'network-visualizer-panel-heading');
  eventsHeading.append(node('h2', '', 'Noteworthy P25 activity'),
    node('p', '', 'Grouped from saved Activity history'));
  const closeEvents = iconButton('icon-close', 'Close noteworthy activity');
  eventsHeader.append(eventsHeading, closeEvents);
  const eventList = node('ol', 'network-visualizer-event-list');
  events.append(eventsHeader, eventList);
  stage.append(canvas, back, scopeTitle, autoFocus, manualGuide, empty, notice, legend, events);
  layout.append(toolbar, stage);

  let state = createP25HistoryState();
  let renderer = null;
  let selectedSystemKey = '';
  let selectedGroupKey = '';
  let visibleNodeKeys = new Set();
  let historyHours = 1;
  let mode = 'auto';
  let cursor = 0;
  let generation = 0;
  let requestController = null;
  let pollTimer = 0;
  let introTimer = 0;
  let animationFrame = 0;
  let lastAnimationAt = performance.now();
  let closed = false;
  let polling = false;
  let lastCameraPhase = 'roam';
  let navigationRevision = 0;
  const pressedKeys = new Set();
  const camera = createP25CameraCoordinator({ focusMs: 5_000, cooldownMs: 8_000, transitionMs: 900,
    returnMs: 900 });

  function setStatus(label, tone = 'current') {
    status.textContent = label;
    status.className = `badge ui-pill network-visualizer-status state-${tone}`;
  }

  function setEmpty(title, detail = '', kind = '') {
    empty.hidden = false;
    empty.dataset.kind = kind;
    empty.replaceChildren(iconGlyph('icon-network-visualizer'), node('strong', '', title));
    if (detail) empty.append(node('p', '', detail));
  }

  function clearAutoFocus() {
    autoFocus.hidden = true;
    autoFocus.dataset.phase = '';
    autoFocus.dataset.category = '';
  }

  function showAutoFocus(event) {
    autoFocusKicker.textContent = `AUTO FOCUS · ${formatTime(event.observedAtMs)}`;
    autoFocusTitle.textContent = event.title || 'Noteworthy P25 activity';
    autoFocusDetail.textContent = event.detail || 'P25 system activity';
    autoFocus.dataset.category = event.category || '';
    autoFocus.dataset.phase = 'focus';
    autoFocus.hidden = false;
  }

  function renderEvents() {
    const rows = groupedP25Events(state, selectedSystemKey).filter((event) => !selectedGroupKey ||
      event.focusKeys.some((key) => visibleNodeKeys.has(key)));
    if (!rows.length) {
      eventList.replaceChildren(node('li', 'network-visualizer-event network-visualizer-event-empty',
        'No noteworthy saved activity in this scope.'));
      return;
    }
    eventList.replaceChildren(...rows.map((event) => {
      const item = node('li', 'network-visualizer-event');
      item.dataset.category = event.category;
      item.append(node('strong', 'network-visualizer-event-kind', event.title),
        node('span', 'network-visualizer-event-detail', event.detail),
        node('time', '', formatTime(event.observedAtMs)));
      return item;
    }));
  }

  function renderGraph(animate = true) {
    if (!renderer) return;
    const graph = buildP25Graph(state, selectedSystemKey, Date.now(), selectedGroupKey);
    visibleNodeKeys = new Set(graph.nodes.map((value) => value.id));
    renderer.setData(graph, { animate });
    notice.hidden = !graph.truncated;
    notice.textContent = graph.truncated ? 'Some entities were omitted by visualization safety limits.' : '';
    renderEvents();
  }

  function enterSystem(systemKey, optionsValue = {}) {
    const system = state.systems.get(String(systemKey || ''));
    if (!system || !renderer) return false;
    if (introTimer) window.clearTimeout(introTimer);
    introTimer = 0;
    if (optionsValue.automatic !== true) navigationRevision += 1;
    selectedSystemKey = system.key;
    selectedGroupKey = '';
    back.hidden = false;
    back.textContent = '← All systems';
    scopeTitle.textContent = system.label;
    clearAutoFocus();
    camera.reset();
    lastCameraPhase = 'roam';
    renderer.setCameraPhase('roam');
    renderGraph(optionsValue.animate !== false);
    renderer.enterSystem(system.key, { immediate: optionsValue.immediate === true });
    return true;
  }

  function enterTalkgroup(groupKey) {
    const group = state.groups.get(String(groupKey || ''));
    if (!group || group.systemKey !== selectedSystemKey || !renderer) return false;
    navigationRevision += 1;
    selectedGroupKey = group.key;
    back.hidden = false;
    back.textContent = '← System';
    scopeTitle.textContent = group.label;
    clearAutoFocus();
    camera.reset();
    lastCameraPhase = 'roam';
    renderer.setCameraPhase('roam');
    renderGraph(true);
    renderer.enterTalkgroup(selectedSystemKey, group.key);
    return true;
  }

  function showOverview() {
    if (!renderer) return;
    if (introTimer) window.clearTimeout(introTimer);
    introTimer = 0;
    navigationRevision += 1;
    selectedSystemKey = '';
    selectedGroupKey = '';
    back.hidden = true;
    scopeTitle.textContent = 'P25 radio systems';
    clearAutoFocus();
    camera.reset();
    lastCameraPhase = 'roam';
    renderer.setCameraPhase('roam');
    renderGraph(false);
    renderer.showOverview();
  }

  function applyAttention(candidates) {
    if (mode !== 'auto' || !selectedSystemKey) return;
    for (const event of candidates.filter((candidate) => candidate.systemKey === selectedSystemKey)) {
      const focusKeys = event.focusKeys.filter((key) => visibleNodeKeys.has(key));
      if (!focusKeys.length) continue;
      const visibleEvent = { ...event, focusKeys };
      const decision = camera.consider(visibleEvent, Date.now());
      if (!decision.accepted) continue;
      lastCameraPhase = 'focus';
      renderer?.setCameraPhase('focus');
      if (!renderer?.focus(focusKeys, decision.state.timing.transitionMs)) {
        camera.cancel();
        lastCameraPhase = 'roam';
        renderer?.setCameraPhase('roam');
        clearAutoFocus();
        break;
      }
      showAutoFocus(event);
      break;
    }
  }

  function historyBounds() {
    const toMs = Date.now() + CLOCK_TOLERANCE_MS;
    return { fromMs: toMs - historyHours * HOUR_MS, toMs };
  }

  async function requestForwardPage(afterId, watermarkId, signal, bounds) {
    const { fromMs, toMs } = bounds;
    return validPage(await requestActivity({ from_ms: fromMs, to_ms: toMs,
      actions: P25_HISTORY_ACTIONS.join(','), after_id: afterId, watermark_id: watermarkId,
      limit: PAGE_LIMIT }, { signal }));
  }

  async function loadSeed() {
    const localGeneration = ++generation;
    navigationRevision += 1;
    if (introTimer) window.clearTimeout(introTimer);
    introTimer = 0;
    requestController?.abort();
    const controller = new AbortController();
    requestController = controller;
    const abort = () => controller.abort();
    dependencies.signal?.addEventListener?.('abort', abort, { once: true });
    state = createP25HistoryState();
    selectedSystemKey = '';
    selectedGroupKey = '';
    visibleNodeKeys = new Set();
    cursor = 0;
    camera.reset();
    clearAutoFocus();
    renderer?.setData({ nodes: [], links: [] }, { animate: false });
    back.hidden = true;
    scopeTitle.textContent = 'P25 radio systems';
    notice.hidden = true;
    setEmpty(`Loading ${historyHours === 1 ? 'one hour' : '24 hours'} of saved P25 activity…`,
      'Routine calls and grants are intentionally excluded.');
    setStatus('Loading saved activity', 'stale');
    const history = dependencies.historyStatus || {};
    if (history.available && !history.historyActive && !history.historyRetained) {
      setEmpty('Saved P25 activity is unavailable',
        'Enable Store Detailed Event History in Stats & Web settings. This page does not fall back to decoder data.',
        'error');
      setStatus('History unavailable', 'error');
      dependencies.signal?.removeEventListener?.('abort', abort);
      return;
    }
    try {
      const bounds = historyBounds();
      let afterId = 0;
      let watermarkId = null;
      let processed = 0;
      const seedRows = [];
      do {
        const page = await requestForwardPage(afterId, watermarkId, controller.signal, bounds);
        if (closed || localGeneration !== generation) return;
        if (page.reset_required === true || page.reseed_required === true ||
            Number(page.watermark_id) < afterId) {
          throw reseedError('Saved Activity history restarted. Reloading the selected scope.');
        }
        if (watermarkId === null) watermarkId = Number(page.watermark_id) || 0;
        const capacity = Math.max(0, MAX_SEED_ROWS - processed);
        if (page.rows.length > capacity || page.has_more && processed + page.rows.length >= MAX_SEED_ROWS) {
          throw new Error('This history scope contains too much noteworthy activity to load safely. Choose 1 hour.');
        }
        const rows = page.rows.slice(0, capacity);
        seedRows.push(...rows);
        processed += rows.length;
        const next = Number(page.next_after_id);
        if (!Number.isSafeInteger(next) || next < afterId || page.has_more && next === afterId) {
          throw new Error('Saved activity paging did not advance.');
        }
        afterId = next;
        if (!page.has_more) break;
      } while (!controller.signal.aborted);
      if (closed || localGeneration !== generation) return;
      applyP25ActivityRows(state, seedRows, { initial: true });
      cursor = Math.max(afterId, watermarkId || 0, state.latestId);
      const mostActive = mostActiveP25System(state);
      if (!mostActive) {
        renderGraph(false);
        setEmpty('No noteworthy P25 activity was saved in this scope',
          'Affiliation changes, emergencies, denials, checks, pages, busy, queued, logout, and patch events appear here.');
      } else {
        empty.hidden = true;
        selectedSystemKey = '';
        renderGraph(false);
        renderer?.showOverview({ immediate: true });
        const expectedNavigation = navigationRevision;
        introTimer = window.setTimeout(() => {
          introTimer = 0;
          if (!closed && localGeneration === generation && navigationRevision === expectedNavigation &&
              !selectedSystemKey) {
            enterSystem(mostActive.key, { automatic: true, animate: false });
          }
        }, 2_500);
      }
      const paused = history.available && !history.historyActive;
      setStatus(paused ? 'Saved history · updates paused' : 'Saved history', paused ? 'stale' : 'current');
      schedulePoll();
    } catch (error) {
      if (error?.name === 'AbortError' || closed || localGeneration !== generation) return;
      if (error?.reseed) {
        setStatus('Reloading saved history', 'stale');
        window.setTimeout(() => void loadSeed(), 0);
        return;
      }
      setEmpty('Saved P25 activity could not be loaded',
        error?.message || 'The existing Activity history remains unchanged. Try this view again.', 'error');
      setStatus('History error', 'error');
    } finally {
      dependencies.signal?.removeEventListener?.('abort', abort);
    }
  }

  function schedulePoll(delay = POLL_MS) {
    if (pollTimer) window.clearTimeout(pollTimer);
    if (!closed) pollTimer = window.setTimeout(() => void poll(), delay);
  }

  async function poll() {
    if (closed || polling || document.hidden) return schedulePoll();
    polling = true;
    const localGeneration = generation;
    const controller = requestController;
    let reseed = false;
    try {
      const bounds = historyBounds();
      let afterId = cursor;
      let watermarkId = null;
      let processed = 0;
      const pollRows = [];
      do {
        const page = await requestForwardPage(afterId, watermarkId, controller.signal, bounds);
        if (closed || localGeneration !== generation) return;
        if (page.reset_required === true || page.reseed_required === true ||
            Number(page.watermark_id) < afterId) {
          throw reseedError('Saved Activity history restarted. Reloading the selected scope.');
        }
        if (watermarkId === null) watermarkId = Number(page.watermark_id) || afterId;
        const capacity = Math.max(0, MAX_POLL_ROWS - processed);
        const rows = page.rows.slice(0, capacity);
        pollRows.push(...rows);
        processed += rows.length;
        const next = Number(page.next_after_id);
        if (!Number.isSafeInteger(next) || next < afterId || page.has_more && next === afterId) {
          throw new Error('Saved activity paging did not advance.');
        }
        afterId = next;
        if (page.rows.length > capacity || page.has_more && processed >= MAX_POLL_ROWS) {
          throw reseedError('More saved activity arrived than one update can safely process. Reloading the selected scope.');
        }
        if (!page.has_more) break;
      } while (!controller.signal.aborted);
      cursor = Math.max(cursor, afterId, watermarkId || 0);
      const result = applyP25ActivityRows(state, pollRows, { initial: false, atMs: Date.now() });
      renderGraph(true);
      result.focusCandidates.sort((left, right) => right.priority - left.priority ||
        right.observedAtMs - left.observedAtMs);
      applyAttention(result.focusCandidates);
      if (status.classList.contains('state-error')) setStatus('Saved history', 'current');
    } catch (error) {
      if (error?.reseed && !closed && localGeneration === generation) {
        reseed = true;
        setStatus('Reloading saved history', 'stale');
        window.setTimeout(() => void loadSeed(), 0);
      } else if (error?.name !== 'AbortError' && !closed && localGeneration === generation) {
        setStatus('History update delayed', 'error');
      }
    } finally {
      polling = false;
      if (!reseed) schedulePoll();
    }
  }

  function setHistoryHours(value) {
    historyHours = value === 24 ? 24 : 1;
    oneHour.setAttribute('aria-pressed', String(historyHours === 1));
    day.setAttribute('aria-pressed', String(historyHours === 24));
    void loadSeed();
  }

  function setMode(value) {
    mode = value === 'manual' ? 'manual' : 'auto';
    auto.setAttribute('aria-pressed', String(mode === 'auto'));
    manual.setAttribute('aria-pressed', String(mode === 'manual'));
    manualGuide.hidden = mode !== 'manual';
    camera.setMode(mode);
    clearAutoFocus();
    lastCameraPhase = mode === 'auto' ? 'roam' : 'disabled';
    renderer?.setMode(mode);
    renderer?.setCameraPhase(mode === 'auto' ? 'roam' : 'disabled');
    if (mode === 'manual') stage.focus({ preventScroll: true });
    else if (selectedSystemKey) renderer?.home(900);
  }

  function keyName(event) {
    return event.key.length === 1 ? event.key.toLowerCase() : event.key;
  }

  function onKeyDown(event) {
    if (mode !== 'manual' || !['w', 'a', 's', 'd', 'q', 'e', 'ArrowLeft', 'ArrowRight', 'ArrowUp',
      'ArrowDown'].includes(keyName(event))) return;
    pressedKeys.add(keyName(event));
    event.preventDefault();
  }

  function onKeyUp(event) {
    pressedKeys.delete(keyName(event));
  }

  function animate(at) {
    animationFrame = 0;
    if (closed) return;
    const delta = Math.min(0.05, Math.max(0, (at - lastAnimationAt) / 1_000));
    lastAnimationAt = at;
    if (mode === 'manual') renderer?.moveManualCamera({
      forward: Number(pressedKeys.has('w')) - Number(pressedKeys.has('s')),
      right: Number(pressedKeys.has('d')) - Number(pressedKeys.has('a')),
      up: Number(pressedKeys.has('e')) - Number(pressedKeys.has('q')),
      yaw: Number(pressedKeys.has('ArrowRight')) - Number(pressedKeys.has('ArrowLeft')),
      pitch: Number(pressedKeys.has('ArrowUp')) - Number(pressedKeys.has('ArrowDown'))
    }, delta);
    const cameraState = camera.update(Date.now());
    if (mode === 'auto' && cameraState.changed && cameraState.phase !== lastCameraPhase) {
      lastCameraPhase = cameraState.phase;
      renderer?.setCameraPhase(cameraState.phase);
      if (cameraState.phase === 'return') {
        autoFocus.dataset.phase = 'return';
        renderer?.home(cameraState.timing.returnMs);
      } else if (cameraState.phase === 'roam') clearAutoFocus();
    }
    animationFrame = requestAnimationFrame(animate);
  }

  oneHour.addEventListener('click', () => historyHours !== 1 && setHistoryHours(1));
  day.addEventListener('click', () => historyHours !== 24 && setHistoryHours(24));
  auto.addEventListener('click', () => mode !== 'auto' && setMode('auto'));
  manual.addEventListener('click', () => mode !== 'manual' && setMode('manual'));
  eventsToggle.addEventListener('click', () => {
    events.hidden = !events.hidden;
    eventsToggle.setAttribute('aria-pressed', String(!events.hidden));
    if (!events.hidden) renderEvents();
  });
  closeEvents.addEventListener('click', () => {
    events.hidden = true;
    eventsToggle.setAttribute('aria-pressed', 'false');
    eventsToggle.focus();
  });
  back.addEventListener('click', () => selectedGroupKey ? enterSystem(selectedSystemKey) : showOverview());
  fullscreen.addEventListener('click', async () => {
    if (!renderer) return;
    if (document.fullscreenElement === layout) await renderer.exitFullscreen();
    else await renderer.enterFullscreen();
  });
  stage.addEventListener('keydown', onKeyDown);
  window.addEventListener('keyup', onKeyUp);
  const onVisibilityChange = () => {
    if (!document.hidden) schedulePoll(0);
  };
  document.addEventListener('visibilitychange', onVisibilityChange);

  async function initialize() {
    renderer = await createP25Renderer({ host: canvas, mode, signal: dependencies.signal,
      onNodeClick: (selected) => {
        if (selected?.type === 'system' && selectedSystemKey !== selected.systemKey) enterSystem(selected.systemKey);
        else if (selected?.type === 'talkgroup' && selectedSystemKey && selected.id !== selectedGroupKey) {
          enterTalkgroup(selected.id);
        }
      }, onInteraction: () => {
        if (mode === 'auto') {
          camera.cancel(Date.now());
          clearAutoFocus();
          lastCameraPhase = 'roam';
          renderer?.setCameraPhase('roam');
        }
      } });
    if (closed) return renderer.dispose();
    void loadSeed();
  }

  const close = () => {
    if (closed) return;
    closed = true;
    generation += 1;
    requestController?.abort();
    if (pollTimer) window.clearTimeout(pollTimer);
    if (introTimer) window.clearTimeout(introTimer);
    if (animationFrame) cancelAnimationFrame(animationFrame);
    window.removeEventListener('keyup', onKeyUp);
    document.removeEventListener('visibilitychange', onVisibilityChange);
    renderer?.dispose();
  };
  dependencies.signal?.addEventListener?.('abort', close, { once: true });
  animationFrame = requestAnimationFrame(animate);
  void initialize();

  return Object.freeze({ element: layout, close });
}

export { createP25Visualizer };
