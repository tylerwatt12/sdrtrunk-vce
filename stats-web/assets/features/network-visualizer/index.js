'use strict';

import {
  P25_HISTORY_ACTIONS,
  P25_ROUTINE_ACTIONS,
  createP25HistoryState,
  applyP25ActivityRows,
  applyP25Affiliations,
  expireP25History,
  buildP25Graph,
  nextP25HighlightExpiry,
  groupedP25Events,
  mostActiveP25System
} from './history.js?v=19';
import { createP25ActivityFeed } from './live.js?v=4';
import { createP25CameraCoordinator } from './camera.js?v=4';
import { createP25Renderer } from './renderer.js?v=8';
import { createFormWorkflow } from '../../core/form-workflows.js?v=6';
import {
  P25_EVENT_SETTINGS,
  normalizeP25EventSettings,
  enabledP25EventCategories,
  routineP25ActivityEnabled
} from './settings.js?v=4';


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


function createP25Visualizer(dependencies = {}) {
  const required = ['node', 'iconGlyph', 'iconButton', 'requestActivity', 'requestActivitySeed', 'openReadOnlyModal',
    'preferenceCheckbox', 'settingsCard', 'settingsCardGrid', 'modalFooter',
    'loadEventSettings', 'saveEventSettings', 'openActivityStream'];
  required.forEach((name) => {
    if (typeof dependencies[name] !== 'function') throw new TypeError(`P25 Visualizer dependency ${name} is required.`);
  });
  const { node, iconGlyph, iconButton, requestActivity, requestActivitySeed, openReadOnlyModal, preferenceCheckbox,
    settingsCard, settingsCardGrid, modalFooter, loadEventSettings, saveEventSettings } = dependencies;
  const layout = node('section', 'network-visualizer-layout');
  layout.setAttribute('aria-label', 'P25 Visualizer');
  const toolbar = node('div', 'network-visualizer-toolbar ui-surface');
  toolbar.setAttribute('role', 'toolbar');
  toolbar.setAttribute('aria-label', 'P25 Visualizer controls');
  const brand = node('div', 'network-visualizer-brand');
  brand.append(iconGlyph('icon-network-visualizer'), node('strong', '', 'P25 Visualizer'));
  const status = node('span', 'badge ui-pill state-stale network-visualizer-status', 'Loading recent activity');
  status.setAttribute('role', 'status');
  status.setAttribute('aria-live', 'polite');

  const actions = node('div', 'network-visualizer-actions');
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
  const scopes = node('div', 'network-visualizer-choice-group');
  scopes.setAttribute('role', 'group');
  scopes.setAttribute('aria-label', 'History scope');
  const hour = textButton(node, '1h'), day = textButton(node, '24h');
  hour.setAttribute('aria-pressed', 'true'); day.setAttribute('aria-pressed', 'false');
  scopes.append(hour, day);
  const refresh = textButton(node, 'Refresh');
  const settingsButton = textButton(node, 'Settings');
  const fullscreen = iconButton('icon-fullscreen', 'Enter fullscreen');
  fullscreen.classList.add('network-visualizer-fullscreen');
  actions.append(scopes, refresh, cameraControls, eventsToggle, settingsButton, fullscreen);
  toolbar.append(brand, status, actions);

  const stage = node('div', 'network-visualizer-stage');
  stage.tabIndex = 0;
  stage.setAttribute('aria-label', 'Interactive recent P25 activity visualization');
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
  empty.append(iconGlyph('icon-network-visualizer'), node('strong', '', 'Loading recent P25 activity…'),
    node('p', '', 'New saved activity appears automatically.'));
  const legend = node('div', 'network-visualizer-legend');
  legend.setAttribute('role', 'group');
  legend.setAttribute('aria-label', 'Shape and line key');
  for (const [label, path, attributes] of [
    ['System', 'M21 12a9 9 0 1 1-18 0a9 9 0 0 1 18 0ZM3 12h18M12 3c-4 4-4 14 0 18c4-4 4-14 0-18Z'],
    ['Talkgroup', 'm12 2 9 5v10l-9 5-9-5V7Zm0 10 9-5M12 12 3 7m9 5v10'],
    ['Radio', 'm12 3 9 17H3ZM12 3v13m0 0L3 20m9-4 9 4'],
    ['Last accepted affiliation', 'M2 12h20'],
    ['Earlier affiliation', 'M2 12h20', { opacity: '0.4' }],
    ['Call activity', 'M2 12h20', { 'stroke-dasharray': '4 3' }]
  ]) {
    const entry = node('span');
    const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    const shape = document.createElementNS(icon.namespaceURI, 'path');
    Object.entries({ viewBox: '0 0 24 24', 'aria-hidden': 'true', width: 24, height: 24,
      fill: 'none', stroke: 'currentColor', 'stroke-width': 1.5 }).forEach(([key, value]) => icon.setAttribute(key, value));
    Object.entries({ d: path, ...attributes }).forEach(([key, value]) => shape.setAttribute(key, value));
    icon.append(shape);
    entry.append(icon, node('span', '', label));
    legend.append(entry);
  }
  const events = node('aside', 'network-visualizer-events');
  events.hidden = true;
  const eventsHeader = node('header', 'network-visualizer-panel-header');
  const eventsHeading = node('div', 'network-visualizer-panel-heading');
  eventsHeading.append(node('h2', '', 'Noteworthy P25 activity'),
    node('p', '', 'Grouped P25 observations'));
  const closeEvents = iconButton('icon-close', 'Close noteworthy activity');
  eventsHeader.append(eventsHeading, closeEvents);
  const eventList = node('ol', 'network-visualizer-event-list');
  eventList.tabIndex = 0;
  eventList.setAttribute('aria-label', 'Recent noteworthy P25 activity');
  events.append(eventsHeader, eventList);
  stage.append(canvas, back, scopeTitle, autoFocus, manualGuide, empty, legend, events);
  layout.append(toolbar, stage);

  let state = createP25HistoryState({ actionLabel: dependencies.messageTypeLabel });
  let eventSettings = normalizeP25EventSettings(loadEventSettings());
  let highlightedCategories = enabledP25EventCategories(eventSettings, 'highlight');
  let autoZoomCategories = enabledP25EventCategories(eventSettings, 'autoZoom');
  let renderer = null;
  let selectedSystemKey = '';
  let selectedGroupKey = '';
  let visibleNodeKeys = new Set();
  let mode = 'auto';
  let hasSnapshot = false;
  let feed = null;
  let serverTimeOffsetMs = 0;
  let scenePresented = false;
  let collectionEnabled = true;
  let sourceEpoch = '';
  let historyHours = 1, historyLoading = false, historySceneDirty = false;
  let nextHistoryExpiryAtMs = 0;
  let feedStatus = 'connecting';
  let highlightTimer = 0;
  let grantActivityTimeoutMs = 1_000;
  let animationFrame = 0;
  let lastAnimationAt = performance.now();
  let closed = false;
  let lastCameraPhase = 'roam';
  let eventsModal = null;
  const shortViewport = window.matchMedia('(max-height: 640px)');
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
      ensureEventsSpace();
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
    ensureEventsSpace();
  }

  function openEventsSheet() {
    if (eventsModal || closed || document.body.classList.contains('modal-open')) return;
    const body = node('div', 'network-visualizer-events-sheet');
    body.append(node('p', 'modal-introduction', 'Grouped P25 observations'), events);
    eventsModal = openReadOnlyModal('Noteworthy P25 activity', body, {
      id: 'p25-visualizer-events', className: 'network-visualizer-events-modal',
      onClose: () => {
        eventsModal = null;
        events.hidden = true;
        stage.append(events);
        eventsToggle.setAttribute('aria-pressed', 'false');
        if (!closed) eventsToggle.focus({ preventScroll: true });
      }
    });
    if (!eventsModal) stage.append(events);
    else if (document.fullscreenElement === layout) layout.append(eventsModal.state.backdrop);
  }

  function ensureEventsSpace() {
    if (events.hidden || eventsModal || closed) return;
    const entryHeight = eventList.firstElementChild?.getBoundingClientRect().height || 0;
    if (shortViewport.matches || eventList.clientHeight < entryHeight) openEventsSheet();
  }
  shortViewport.addEventListener('change', ensureEventsSpace);
  const eventsResizeObserver = new ResizeObserver(ensureEventsSpace);
  eventsResizeObserver.observe(stage);

  async function openSettings() {
    const form = node('form', 'admin-form network-visualizer-settings-form');
    const message = node('div', 'admin-form-message');
    message.setAttribute('role', 'status');
    const controls = new Map();
    const choices = (property) => P25_EVENT_SETTINGS.map((entry) => {
      const definition = entry.action ? dependencies.messageTypeDefinition?.(entry.action) : null;
      const label = definition?.label || entry.label || dependencies.messageTypeLabel?.(entry.action) || entry.id;
      const choice = preferenceCheckbox(`p25-visualizer-${property}-${entry.id}`, label,
        eventSettings[entry.id][property], definition?.meaning || entry.detail || '');
      controls.set(`${property}:${entry.id}`, choice.input);
      return choice.control;
    });
    const highlightCard = settingsCard('Scene highlights',
      'Briefly emphasize new saved observations.', ...choices('highlight'));
    const zoomCard = settingsCard('Automatic camera',
      'Let new saved observations move the Auto camera.',
      ...choices('autoZoom'));
    const cancel = textButton(node, 'Cancel');
    const save = textButton(node, 'Save choices', 'ui-button ui-button-primary');
    save.type = 'submit';
    form.append(node('p', 'modal-introduction',
      'These choices are saved in this browser for the current web profile.'),
      settingsCardGrid(highlightCard, zoomCard), message,
      modalFooter(cancel, save));
    const modal = openReadOnlyModal('P25 Visualizer settings', form, {
      id: 'p25-visualizer-settings', className: 'network-visualizer-settings-modal'
    });
    if (!modal || (modal.ready && !await modal.ready)) return;
    cancel.addEventListener('click', modal.close);
    const workflow = createFormWorkflow({ form, submit: save, feedback: message, modal,
      changed: () => P25_EVENT_SETTINGS.some((entry) => ['highlight', 'autoZoom'].some((property) =>
        controls.get(`${property}:${entry.id}`).checked !== eventSettings[entry.id][property])) });
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      const next = Object.fromEntries(P25_EVENT_SETTINGS.map((entry) => [entry.id, {
        highlight: controls.get(`highlight:${entry.id}`).checked,
        autoZoom: controls.get(`autoZoom:${entry.id}`).checked
      }]));
      await workflow.save(async () => {
        await saveEventSettings(next);
        eventSettings = normalizeP25EventSettings(next);
        highlightedCategories = enabledP25EventCategories(eventSettings, 'highlight');
        autoZoomCategories = enabledP25EventCategories(eventSettings, 'autoZoom');
      }, { saving: 'Saving visualizer choices…',
        onSuccess: () => { if (modal.close()) feed?.reloadHistory(); },
        onError: (error) => ({ state: 'error', message: error?.message ||
          'Visualizer choices could not be saved in this browser.' }) });
    });
  }

  function renderGraph(animate = true, affiliationChanges = []) {
    if (!renderer) return;
    const graph = buildP25Graph(state, selectedSystemKey, Date.now() + serverTimeOffsetMs, selectedGroupKey,
      { highlightCategories: highlightedCategories });
    if (feedStatus === 'live') setLiveStatus('live');
    visibleNodeKeys = new Set(graph.nodes.map((value) => value.id));
    renderer.setData(graph, { animate, affiliationChanges });
    renderEvents();
    if (highlightTimer) window.clearTimeout(highlightTimer);
    const now = Date.now() + serverTimeOffsetMs;
    const expiresAt = nextP25HighlightExpiry(state, now, { highlightCategories: highlightedCategories });
    highlightTimer = expiresAt ? window.setTimeout(() => {
      highlightTimer = 0;
      if (!closed) renderGraph(false);
    }, Math.max(1, expiresAt - now)) : 0;
  }

  function renderScopeTitle(system, group = null) {
    const label = system.name || system.identity || system.label;
    const context = node('div', 'muted');
    const destination = dependencies.systemHref?.(system);
    const systemText = destination ? node('a', 'ui-link-text', label) : node('span', '', label);
    if (destination) systemText.href = destination;
    if (group) {
      context.append('System: ', systemText);
      if (system.identity) context.append(` · ${system.identity}`);
      scopeTitle.replaceChildren(node('strong', '', group.label), context);
    } else {
      scopeTitle.replaceChildren(systemText);
      if (system.identity && system.identity !== label) scopeTitle.append(node('div', 'muted', system.identity));
    }
  }

  function enterSystem(systemKey, optionsValue = {}) {
    const system = state.systems.get(String(systemKey || ''));
    if (!system || !renderer) return false;
    selectedSystemKey = system.key;
    selectedGroupKey = '';
    back.hidden = false;
    back.textContent = '← All systems';
    renderScopeTitle(system);
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
    selectedGroupKey = group.key;
    back.hidden = false;
    back.textContent = '← System';
    renderScopeTitle(state.systems.get(group.systemKey), group);
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
    if (mode !== 'auto' || !selectedSystemKey || document.hidden) return;
    for (const event of candidates.filter((candidate) => candidate.systemKey === selectedSystemKey &&
      autoZoomCategories.has(candidate.category))) {
      const focusKeys = event.focusKeys.filter((key) => visibleNodeKeys.has(key));
      if (!focusKeys.length) continue;
      const visibleEvent = { ...event, focusKeys };
      const decision = camera.consider(visibleEvent, Date.now());
      if (!decision.accepted) continue;
      lastCameraPhase = 'focus';
      renderer?.setCameraPhase('focus');
      const focused = event.affiliationRadioKey && renderer?.focusAffiliation?.(
        event.affiliationRadioKey, decision.state.timing.transitionMs) ||
        renderer?.focus(focusKeys, decision.state.timing.transitionMs);
      if (!focused) {
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

  function waitForRenderer(signal) {
    return new Promise((resolve, reject) => {
      const abort = () => reject(signal.reason || new DOMException('Loading canceled.', 'AbortError'));
      if (signal.aborted) return abort();
      signal.addEventListener('abort', abort, { once: true });
      rendererReady.then(() => {
        signal.removeEventListener('abort', abort);
        resolve();
      }, (error) => {
        signal.removeEventListener('abort', abort);
        reject(error);
      });
    });
  }

  async function loadSeed(signal, options = {}) {
    refresh.disabled = true;
    if (!hasSnapshot) setEmpty('Loading recent P25 activity…', 'New saved activity appears automatically.');
    return requestActivitySeed(options.refresh === true ? { refresh: true } : {}, { signal });
  }

  function activityOptions(initial = false) {
    return { initial, atMs: Date.now() + serverTimeOffsetMs, highlightAtObservationTime: true,
      highlightCategories: highlightedCategories, grantActivityTimeoutMs };
  }

  function updateScene(result, replace = false) {
    if (scenePresented && !selectedSystemKey && !replace && state.systems.size) {
      empty.hidden = true;
      renderGraph(true);
      return;
    }
    scenePresented = true;
    const previousSystemKey = selectedSystemKey;
    const systemKey = state.systems.has(selectedSystemKey) ? selectedSystemKey :
      mostActiveP25System(state)?.key;
    if (!systemKey) {
      selectedSystemKey = selectedGroupKey = '';
      back.hidden = true;
      scopeTitle.textContent = 'P25 radio systems';
      clearAutoFocus();
      renderGraph(false);
      renderer?.showOverview();
      setEmpty('No P25 activity or affiliations to show',
        routineP25ActivityEnabled(eventSettings) ?
          'New saved activity will appear automatically.' :
          'Enable calls and grants in Visualizer settings to include routine activity.');
      return;
    }
    empty.hidden = true;
    if (!previousSystemKey || systemKey !== previousSystemKey) {
      enterSystem(systemKey, { animate: false, immediate: true });
    } else {
      selectedSystemKey = systemKey;
      if (selectedGroupKey && state.groups.get(selectedGroupKey)?.systemKey !== systemKey) {
        enterSystem(systemKey, { animate: false, immediate: true });
        applyAttention(result.focusCandidates);
        return;
      }
      back.hidden = false;
      back.textContent = selectedGroupKey ? '← System' : '← All systems';
      renderScopeTitle(state.systems.get(systemKey), state.groups.get(selectedGroupKey));
      renderGraph(!replace, result.affiliationChanges);
    }
    applyAttention(result.focusCandidates);
  }

  function acceptAffiliations(document, replace = false) {
    collectionEnabled = document.collection_enabled ?? collectionEnabled;
    const timing = document.traffic_grant_age_out_milliseconds;
    if (Number.isInteger(timing) && timing >= 100 && timing <= 15_000) grantActivityTimeoutMs = timing;
    const result = applyP25Affiliations(state, document, { ...activityOptions(replace), replace, sequence: document.sequence });
    if (result.changed || replace) { hasSnapshot = true; updateScene(result, replace); }
  }

  function resetHistory() {
    historySceneDirty = false;
    nextHistoryExpiryAtMs = 0;
    const affiliations = [...state.currentAffiliations.values()], available = state.available;
    state = createP25HistoryState({ actionLabel: dependencies.messageTypeLabel });
    const result = applyP25Affiliations(state, { affiliations, available }, { ...activityOptions(true), replace: true });
    clearAutoFocus(); camera.reset(); updateScene(result, true);
  }

  function historyParameters() {
    const now = Date.now() + serverTimeOffsetMs;
    const actions = routineP25ActivityEnabled(eventSettings) ?
      [...P25_HISTORY_ACTIONS, ...P25_ROUTINE_ACTIONS] : P25_HISTORY_ACTIONS;
    return { from_ms: now - historyHours * 3_600_000, to_ms: now,
      actions: actions.join(','), projection: 'p25_visualizer', limit: 5_000 };
  }

  function acceptHistory(rows, initial) {
    const now = Date.now() + serverTimeOffsetMs;
    const selected = rows.filter(row => row.observed_at_ms >= now - historyHours * 3_600_000 &&
      row.observed_at_ms <= now + 60_000 &&
      (routineP25ActivityEnabled(eventSettings) || !P25_ROUTINE_ACTIONS.includes(row.action)));
    const result = applyP25ActivityRows(state, selected, activityOptions(initial));
    if (result.changed) {
      hasSnapshot = true;
      if (initial) historySceneDirty = true; else updateScene(result);
    }
  }

  function setLiveStatus(value) {
    feedStatus = value;
    refresh.disabled = value === 'loading' || value === 'refreshing';
    if (value === 'live') {
      const label = !state.available ? 'P25 data unavailable · Showing previous data' :
        collectionEnabled ? 'Live' : 'Live affiliations · Activity collection off';
      setStatus(label + (historyLoading ? ` · Loading ${historyHours}h history…` : ''), state.available ? 'current' : 'stale');
    }
    else if (value === 'paused') setStatus('Paused while this tab is hidden', 'stale');
    else if (value === 'loading') setStatus('Loading recent activity', 'stale');
    else if (value === 'refreshing') setStatus('Refreshing recent activity…', 'stale');
    else if (value === 'retrying' || value === 'error') setStatus(hasSnapshot ?
      'Reconnecting · showing previous activity' : 'Activity unavailable · retrying', 'stale');
    else setStatus(hasSnapshot ? 'Reconnecting · showing previous activity' : 'Connecting to live activity', 'stale');
  }

  function loadSnapshot(options = {}) { feed?.reseed(options); }

  function onVisibilityChanged() {
    pressedKeys.clear();
    if (!document.hidden) expireHistory();
    feed?.visibilityChanged();
  }

  function expireHistory() {
    const now = Date.now() + serverTimeOffsetMs;
    if (now < nextHistoryExpiryAtMs) return;
    nextHistoryExpiryAtMs = now + 60_000;
    const result = expireP25History(state, now - historyHours * 3_600_000);
    if (result.changed) { historySceneDirty = false; updateScene(result); }
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
    if (!document.hidden) expireHistory();
    if (historySceneDirty && renderer) {
      historySceneDirty = false; updateScene({ focusCandidates: [], affiliationChanges: [] });
    }
    const delta = Math.min(0.05, Math.max(0, (at - lastAnimationAt) / 1_000));
    lastAnimationAt = at;
    if (mode === 'manual') renderer?.moveManualCamera({
      forward: Number(pressedKeys.has('w')) - Number(pressedKeys.has('s')),
      right: Number(pressedKeys.has('d')) - Number(pressedKeys.has('a')),
      up: Number(pressedKeys.has('e')) - Number(pressedKeys.has('q')),
      yaw: Number(pressedKeys.has('ArrowLeft')) - Number(pressedKeys.has('ArrowRight')),
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

  function selectHistory(hours) {
    if (hours === historyHours) return;
    historyHours = hours;
    hour.setAttribute('aria-pressed', String(hours === 1)); day.setAttribute('aria-pressed', String(hours === 24));
    feed?.reloadHistory();
  }
  hour.addEventListener('click', () => selectHistory(1));
  day.addEventListener('click', () => selectHistory(24));
  refresh.addEventListener('click', () => void loadSnapshot({ refresh: true }));
  auto.addEventListener('click', () => mode !== 'auto' && setMode('auto'));
  manual.addEventListener('click', () => mode !== 'manual' && setMode('manual'));
  eventsToggle.addEventListener('click', () => {
    events.hidden = !events.hidden;
    eventsToggle.setAttribute('aria-pressed', String(!events.hidden));
    if (!events.hidden) renderEvents();
  });
  settingsButton.addEventListener('click', openSettings);
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
    renderer.setMode(mode);
    renderer.setCameraPhase(mode === 'auto' ? 'roam' : 'disabled');
  }

  const close = () => {
    if (closed) return;
    closed = true;
    feed?.close();
    document.removeEventListener('visibilitychange', onVisibilityChanged);
    if (highlightTimer) window.clearTimeout(highlightTimer);
    if (animationFrame) cancelAnimationFrame(animationFrame);
    window.removeEventListener('keyup', onKeyUp);
    shortViewport.removeEventListener('change', ensureEventsSpace);
    eventsResizeObserver.disconnect();
    eventsModal?.close();
    renderer?.dispose();
  };
  dependencies.signal?.addEventListener?.('abort', close, { once: true });
  animationFrame = requestAnimationFrame(animate);
  const rendererReady = initialize();
  feed = createP25ActivityFeed({
    openStream: dependencies.openActivityStream,
    loadSeed, onDelta: acceptAffiliations, onStatus: setLiveStatus,
    historyParameters, loadHistory: (parameters, signal) => requestActivity(parameters, { signal }),
    onHistory: async (rows, signal) => {
      await waitForRenderer(signal);
      if (!closed && !signal.aborted) acceptHistory(rows, true);
    },
    onAppend: (rows) => acceptHistory(rows, false), onHistoryReset: resetHistory,
    onHistoryStatus: (loading) => { historyLoading = loading; if (feedStatus === 'live') setLiveStatus('live'); },
    onSeed: async (document, signal) => {
      await waitForRenderer(signal);
      if (!closed && !signal.aborted) acceptAffiliations(document, true);
    },
    onBoundary: (boundary) => {
      if (sourceEpoch && sourceEpoch !== boundary.source_epoch) {
        state = createP25HistoryState({ actionLabel: dependencies.messageTypeLabel });
        nextHistoryExpiryAtMs = 0;
        hasSnapshot = false;
        clearAutoFocus();
        if (renderer) renderGraph(false);
      }
      sourceEpoch = boundary.source_epoch;
      collectionEnabled = boundary.collection_enabled !== false;
      if (Number.isFinite(boundary.server_time_ms)) serverTimeOffsetMs = boundary.server_time_ms - Date.now();
      const timing = boundary.traffic_grant_age_out_milliseconds;
      if (Number.isInteger(timing) && timing >= 100 && timing <= 15_000) grantActivityTimeoutMs = timing;
    },
    onError: (error) => {
      if (!hasSnapshot) setEmpty('Recent P25 activity could not be loaded',
        error?.message || 'Reconnecting automatically. You can also select Refresh.', 'error');
    },
    isHidden: () => document.hidden,
    setTimeout: (callback, delay) => window.setTimeout(callback, delay),
    clearTimeout: (timer) => window.clearTimeout(timer)
  });
  document.addEventListener('visibilitychange', onVisibilityChanged);

  return Object.freeze({ element: layout, close });
}

export { createP25Visualizer };
