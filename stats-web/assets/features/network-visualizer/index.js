'use strict';

import {
  createP25HistoryState,
  applyP25ActivityRows,
  applyP25Affiliations,
  applyP25LiveTables,
  buildP25Graph,
  expireP25History,
  nextP25HighlightExpiry,
  p25ActionCount,
  mostActiveP25System
} from './history.js?v=20';
import { createP25ActivityFeed } from './live.js?v=6';
import { createP25CameraCoordinator } from './camera.js?v=5';
import { createP25Renderer } from './renderer.js?v=16';
import { createFormWorkflow } from '../../core/form-workflows.js?v=6';
import {
  P25_EVENT_SETTINGS,
  normalizeP25EventSettings,
  enabledP25EventCategories
} from './settings.js?v=5';


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
  const required = ['node', 'iconGlyph', 'iconButton', 'openReadOnlyModal',
    'preferenceCheckbox', 'modalFooter', 'loadEventSettings', 'saveEventSettings',
    'openActivityStream', 'subscribeActivity'];
  required.forEach((name) => {
    if (typeof dependencies[name] !== 'function') throw new TypeError(`P25 Visualizer dependency ${name} is required.`);
  });
  const { node, iconGlyph, iconButton, openReadOnlyModal, preferenceCheckbox,
    modalFooter, loadEventSettings, saveEventSettings } = dependencies;
  const guideMode = dependencies.guideMode === true;
  const layout = node('section', 'network-visualizer-layout');
  layout.setAttribute('aria-label', 'P25 Visualizer');
  const toolbar = node('div', 'network-visualizer-toolbar ui-surface');
  toolbar.setAttribute('role', 'toolbar');
  toolbar.setAttribute('aria-label', 'P25 Visualizer controls');
  const brand = node('div', 'network-visualizer-brand');
  brand.append(iconGlyph('icon-network-visualizer'), node('strong', '', 'P25 Visualizer'));
  const status = node('span', 'badge ui-pill state-stale network-visualizer-status', 'Connecting to live activity');
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
  auto.dataset.guideTarget = 'p25-auto-camera';
  manual.dataset.guideTarget = 'p25-manual-button';
  const eventsToggle = textButton(node, 'Actions');
  eventsToggle.setAttribute('aria-pressed', 'false');
  eventsToggle.dataset.guideTarget = 'p25-actions-button';
  const fullscreen = iconButton('icon-fullscreen', 'Enter fullscreen');
  fullscreen.classList.add('network-visualizer-fullscreen');
  actions.append(cameraControls, eventsToggle, fullscreen);
  toolbar.append(brand, status, actions);

  const stage = node('div', 'network-visualizer-stage');
  stage.tabIndex = 0;
  stage.setAttribute('aria-label', 'Interactive live P25 activity visualization');
  stage.dataset.guideTarget = 'p25-manual-camera';
  const canvas = node('div', 'network-visualizer-canvas');
  const back = textButton(node, '← All systems',
    'ui-button ui-button-secondary network-visualizer-back');
  back.hidden = true;
  const scopeTitle = node('div', 'network-visualizer-scope-title');
  const scopeOverview = node('span', '', 'P25 radio systems');
  const scopeGroupTitle = node('strong');
  const scopeSystemContext = node('div');
  const scopeSystemPrefix = document.createTextNode('');
  const scopeSystemLink = node('a', 'ui-link-text');
  const scopeSystemName = node('span');
  const scopeSystemSuffix = document.createTextNode('');
  const scopeSystemIdentity = node('div', 'muted');
  scopeGroupTitle.hidden = scopeSystemContext.hidden = scopeSystemIdentity.hidden = true;
  scopeSystemContext.append(scopeSystemPrefix, scopeSystemLink, scopeSystemName, scopeSystemSuffix);
  scopeTitle.append(scopeOverview, scopeGroupTitle, scopeSystemContext, scopeSystemIdentity);
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
  empty.append(iconGlyph('icon-network-visualizer'), node('strong', '', 'Waiting for P25 activity'),
    node('p', '', 'Talkgroups and radios appear as they are heard.'));
  const legend = node('div', 'network-visualizer-legend');
  legend.setAttribute('role', 'group');
  legend.setAttribute('aria-label', 'Shape and line key');
  legend.dataset.guideTarget = 'p25-shapes';
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
  const events = node('aside', 'network-visualizer-events ui-surface');
  events.hidden = true;
  const eventsHeader = node('header', 'network-visualizer-panel-header');
  const eventsHeading = node('div', 'network-visualizer-panel-heading');
  eventsHeading.append(node('h2', '', 'Actions'),
    node('p', '', 'Counts since this page opened.'));
  const closeEvents = iconButton('icon-close', 'Close actions');
  closeEvents.dataset.guideTarget = 'p25-actions-close';
  events.dataset.guideTarget = 'p25-actions';
  eventsHeader.append(eventsHeading, closeEvents);
  const actionsForm = node('form', 'admin-form network-visualizer-actions-form');
  const eventList = node('ul', 'network-visualizer-event-list');
  eventList.setAttribute('aria-label', 'P25 actions');
  const message = node('div', 'admin-form-message');
  message.setAttribute('role', 'status');
  const cancel = textButton(node, 'Cancel');
  const save = textButton(node, 'Save choices', 'ui-button ui-button-primary');
  save.type = 'submit';
  const choicesFooter = modalFooter(cancel, save);
  choicesFooter.classList.add('ui-modal-footer-flush');
  actionsForm.append(eventList, message, choicesFooter);
  events.append(eventsHeader, actionsForm);
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
  let feed = null;
  let activitySubscription = null;
  let serverTimeOffsetMs = 0;
  let scenePresented = false;
  let sceneDirty = false;
  let collectionEnabled = true;
  let sourceEpoch = '';
  let overviewSelected = false;
  let highlightTimer = 0;
  let nextIdleCleanupAtMs = 0;
  let animationFrame = 0;
  let lastAnimationAt = performance.now();
  let closed = false;
  let guideSuspended = false;
  let resolveGuideScene;
  const guideSceneReady = new Promise(resolve => { resolveGuideScene = resolve; });
  let lastCameraPhase = 'roam';
  let eventsModal = null;
  const shortViewport = window.matchMedia('(max-width: 600px), (max-height: 640px)');
  const pressedKeys = new Set();
  const liveTables = new Map();
  const actionControls = new Map();
  const actionCounts = new Map();
  const pendingAffiliations = new Map();
  let pendingFocusCandidates = [];
  const camera = createP25CameraCoordinator({ focusMs: 2_000, transitionMs: 900, returnMs: 900 });

  function setStatus(label, tone = 'current') {
    status.textContent = guideMode ? `Example data · ${label}` : label;
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

  function resetAttention() {
    camera.reset();
    lastCameraPhase = mode === 'auto' ? 'roam' : 'disabled';
    renderer?.resetAttention?.();
    renderer?.setCameraPhase(lastCameraPhase);
    clearAutoFocus();
  }

  function showAutoFocus(event) {
    autoFocusKicker.textContent = `AUTO FOCUS · ${formatTime(event.observedAtMs)}`;
    autoFocusTitle.textContent = event.title || 'P25 activity';
    autoFocusDetail.textContent = event.detail || 'P25 system activity';
    autoFocus.dataset.category = event.category || '';
    autoFocus.dataset.phase = 'focus';
    autoFocus.hidden = false;
  }

  for (const entry of P25_EVENT_SETTINGS) {
    const definition = entry.action ? dependencies.messageTypeDefinition?.(entry.action) : null;
    const label = definition?.label || entry.label || dependencies.messageTypeLabel?.(entry.action) || entry.id;
    const item = node('li', 'network-visualizer-action');
    item.dataset.category = entry.id;
    const heading = node('div', 'network-visualizer-action-heading');
    const count = node('span', 'ui-pill', '0');
    count.setAttribute('aria-label', `${label}: 0 since this page opened`);
    actionCounts.set(entry.id, { count, label });
    heading.append(node('strong', '', label), count);
    const choices = node('div', 'network-visualizer-action-choices');
    for (const [property, title] of [['autoZoom', 'Zoom to'], ['highlight', 'Highlight']]) {
      const choice = preferenceCheckbox(`p25-visualizer-${property}-${entry.id}`, title,
        eventSettings[entry.id][property]);
      choice.input.setAttribute('aria-label', `${title} ${label}`);
      actionControls.set(`${property}:${entry.id}`, choice.input);
      choices.append(choice.control);
    }
    item.append(heading, choices);
    eventList.append(item);
  }
  const choicesChanged = () => P25_EVENT_SETTINGS.some(entry => ['highlight', 'autoZoom'].some(property =>
    actionControls.get(`${property}:${entry.id}`).checked !== eventSettings[entry.id][property]));
  const modalGuard = {
    setDirty: value => eventsModal?.setDirty(value),
    setBusy: value => eventsModal?.setBusy(value)
  };
  const choicesWorkflow = createFormWorkflow({ form: actionsForm, submit: save, feedback: message,
    modal: modalGuard, changed: choicesChanged, hideCleanSubmit: false });
  choicesWorkflow.setModalLayout(false);

  function renderActions() {
    for (const [id, { count, label }] of actionCounts) {
      const value = p25ActionCount(state, id);
      if (count.textContent !== String(value)) {
        count.textContent = String(value);
        count.setAttribute('aria-label', `${label}: ${value} since this page opened`);
      }
    }
    ensureEventsSpace();
  }

  function restoreChoices() {
    for (const entry of P25_EVENT_SETTINGS) for (const property of ['highlight', 'autoZoom'])
      actionControls.get(`${property}:${entry.id}`).checked = eventSettings[entry.id][property];
    choicesWorkflow.setDirty(false);
    choicesWorkflow.showFeedback('status', '');
  }

  function hideActions() {
    if (choicesWorkflow.isBusy()) return;
    if (eventsModal) { eventsModal.close(); return; }
    events.hidden = true;
    eventsToggle.setAttribute('aria-pressed', 'false');
    eventsToggle.focus({ preventScroll: true });
  }

  async function openEventsSheet() {
    if (eventsModal || closed || document.body.classList.contains('modal-open')) return;
    const body = node('div', 'network-visualizer-events-sheet');
    events.classList.remove('ui-surface');
    body.append(node('p', 'modal-introduction', 'Counts since this page opened.'), events);
    eventsModal = openReadOnlyModal('P25 actions', body, {
      id: 'p25-visualizer-actions', className: 'network-visualizer-events-modal',
      onClose: () => {
        eventsModal = null;
        events.classList.add('ui-surface');
        choicesWorkflow.setModalLayout(false);
        restoreChoices();
        events.hidden = true;
        stage.append(events);
        eventsToggle.setAttribute('aria-pressed', 'false');
        if (!closed) eventsToggle.focus({ preventScroll: true });
      }
    });
    if (!eventsModal) { events.classList.add('ui-surface'); stage.append(events); }
    else {
      choicesWorkflow.setModalLayout(true);
      choicesWorkflow.refresh();
      if (document.fullscreenElement === layout) layout.append(eventsModal.state.backdrop);
      if (eventsModal.ready && !await eventsModal.ready) return;
      eventsModal.focus?.(actionControls.values().next().value);
    }
  }

  function ensureEventsSpace() {
    if (events.hidden || eventsModal || closed || presentationPaused()) return;
    const rowHeight = eventList.firstElementChild?.getBoundingClientRect().height || 0;
    if (shortViewport.matches || (rowHeight && eventList.clientHeight < rowHeight)) void openEventsSheet();
  }
  shortViewport.addEventListener('change', ensureEventsSpace);
  const eventsResizeObserver = new ResizeObserver(ensureEventsSpace);
  eventsResizeObserver.observe(events);

  actionsForm.addEventListener('submit', async event => {
    event.preventDefault();
    const next = Object.fromEntries(P25_EVENT_SETTINGS.map(entry => [entry.id, {
      highlight: actionControls.get(`highlight:${entry.id}`).checked,
      autoZoom: actionControls.get(`autoZoom:${entry.id}`).checked
    }]));
    await choicesWorkflow.save(async () => {
      await saveEventSettings(next);
      eventSettings = normalizeP25EventSettings(next);
      highlightedCategories = enabledP25EventCategories(eventSettings, 'highlight');
      autoZoomCategories = enabledP25EventCategories(eventSettings, 'autoZoom');
    }, { saving: 'Saving visualizer choices…', onSuccess: () => {
      resetAttention();
      renderGraph(false);
    }, onError: () => ({ state: 'error', message: 'Visualizer choices could not be saved. Try again.' }) });
  });
  cancel.addEventListener('click', () => { restoreChoices(); hideActions(); });

  function renderGraph(animate = true, affiliationChanges = []) {
    if (closed) return;
    if (!renderer || presentationPaused()) { sceneDirty = true; return; }
    sceneDirty = false;
    const graph = buildP25Graph(state, selectedSystemKey, Date.now() + serverTimeOffsetMs, selectedGroupKey,
      { highlightCategories: highlightedCategories });
    visibleNodeKeys = new Set(graph.nodes.map(value => value.id));
    renderer.setData(graph, { animate, affiliationChanges });
    renderActions();
    if (highlightTimer) window.clearTimeout(highlightTimer);
    const now = Date.now() + serverTimeOffsetMs;
    const expiresAt = nextP25HighlightExpiry(state, now, { highlightCategories: highlightedCategories });
    highlightTimer = expiresAt ? window.setTimeout(() => {
      highlightTimer = 0;
      if (!closed) renderGraph(false);
    }, Math.max(1, expiresAt - now)) : 0;
  }

  function renderScopeTitle(system, group = null) {
    const setText = (element, value) => {
      if (element.textContent !== value) element.textContent = value;
    };
    const setHidden = (element, value) => {
      if (element.hidden !== value) element.hidden = value;
    };
    setHidden(scopeOverview, Boolean(system));
    setHidden(scopeGroupTitle, !group);
    setHidden(scopeSystemContext, !system);
    if (!system) {
      setHidden(scopeSystemIdentity, true);
      return;
    }
    const label = system.name || system.identity || system.label;
    const destination = dependencies.systemHref?.(system);
    setHidden(scopeSystemLink, !destination);
    setHidden(scopeSystemName, Boolean(destination));
    setText(scopeSystemLink, label);
    setText(scopeSystemName, label);
    if (destination && scopeSystemLink.getAttribute('href') !== destination) scopeSystemLink.href = destination;
    else if (!destination && scopeSystemLink.hasAttribute('href')) scopeSystemLink.removeAttribute('href');
    const contextClass = group ? 'muted' : '';
    if (scopeSystemContext.className !== contextClass) scopeSystemContext.className = contextClass;
    setText(scopeGroupTitle, group?.label || '');
    setText(scopeSystemPrefix, group ? 'System: ' : '');
    setText(scopeSystemSuffix, group && system.identity ? ` · ${system.identity}` : '');
    const identity = !group && system.identity !== label ? system.identity || '' : '';
    setText(scopeSystemIdentity, identity);
    setHidden(scopeSystemIdentity, !identity);
  }

  function enterSystem(systemKey, optionsValue = {}) {
    const system = state.systems.get(String(systemKey || ''));
    if (!system || !renderer) return false;
    selectedSystemKey = system.key;
    selectedGroupKey = '';
    overviewSelected = false;
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
    overviewSelected = true;
    back.hidden = true;
    renderScopeTitle(null);
    clearAutoFocus();
    camera.reset();
    lastCameraPhase = 'roam';
    renderer.setCameraPhase('roam');
    renderGraph(false);
    renderer.showOverview();
  }

  function applyAttention(candidates) {
    if (mode !== 'auto' || !selectedSystemKey || document.hidden || renderer?.isInteracting?.()) return;
    const event = candidates.filter(candidate => candidate.systemKey === selectedSystemKey &&
      autoZoomCategories.has(candidate.category) &&
      (!selectedGroupKey || candidate.focusKeys.includes(selectedGroupKey))).reduce((latest, value) =>
      !latest || value.observedAtMs >= latest.observedAtMs ? value : latest, null);
    if (!event) return;
    const focusKeys = event.focusKeys.filter(key => visibleNodeKeys.has(key));
    if (!focusKeys.length) return;
    const decision = camera.consider({ ...event, focusKeys }, Date.now());
    if (!decision.accepted) return;
    lastCameraPhase = 'focus';
    renderer?.setCameraPhase('focus');
    const focused = event.affiliationRadioKey && event.focusKeys.every(key => visibleNodeKeys.has(key)) &&
      renderer?.focusAffiliation?.(
      event.affiliationRadioKey, decision.state.timing.transitionMs) ||
      renderer?.focus(focusKeys, decision.state.timing.transitionMs);
    if (!focused) { resetAttention(); return; }
    showAutoFocus(event);
  }

  function activityOptions(initial = false) {
    return { initial, atMs: Date.now() + serverTimeOffsetMs, highlightAtObservationTime: true,
      highlightCategories: highlightedCategories };
  }

  function updateScene(result = { focusCandidates: [], affiliationChanges: [] }) {
    renderActions();
    if (closed) return;
    if (presentationPaused()) { sceneDirty = true; return; }
    if (!renderer) {
      sceneDirty = true;
      for (const change of result.affiliationChanges || []) {
        pendingAffiliations.set(change.radioKey, change);
        if (pendingAffiliations.size > 256) pendingAffiliations.delete(pendingAffiliations.keys().next().value);
      }
      if (result.focusCandidates?.length) pendingFocusCandidates = result.focusCandidates.slice(-P25_EVENT_SETTINGS.length);
      return;
    }
    if (overviewSelected && state.systems.size) {
      empty.hidden = true;
      renderGraph(true);
      return;
    }
    const firstPresentation = !scenePresented;
    scenePresented = true;
    const systemKey = state.systems.has(selectedSystemKey) ? selectedSystemKey : mostActiveP25System(state)?.key;
    if (!systemKey) {
      empty.hidden = false;
      renderGraph(false);
      return;
    }
    empty.hidden = true;
    if (!selectedSystemKey || systemKey !== selectedSystemKey ||
        (selectedGroupKey && state.groups.get(selectedGroupKey)?.systemKey !== systemKey)) {
      enterSystem(systemKey, { animate: false, immediate: true });
      if (result.affiliationChanges?.length) renderGraph(true, result.affiliationChanges);
    } else {
      renderScopeTitle(state.systems.get(systemKey), state.groups.get(selectedGroupKey));
      renderGraph(!firstPresentation, result.affiliationChanges || []);
    }
    applyAttention(result.focusCandidates || []);
    resolveGuideScene(true);
  }

  function presentationPaused() { return document.hidden || guideSuspended; }

  function expireIdle(now = Date.now() + serverTimeOffsetMs) {
    if (now < nextIdleCleanupAtMs) return false;
    nextIdleCleanupAtMs = now + 60_000;
    return expireP25History(state, now - 15 * 60_000, { atMs: now }).changed;
  }

  function acceptAffiliations(document) {
    collectionEnabled = document.collection_enabled ?? collectionEnabled;
    const result = applyP25Affiliations(state, document, { ...activityOptions(), sequence: document.sequence });
    setLiveStatus('live');
    if (expireIdle() || result.changed) updateScene(result);
  }

  function acceptRows(rows) {
    const result = applyP25ActivityRows(state, rows, activityOptions());
    if (expireIdle() || result.changed) updateScene(result);
  }

  function acceptLiveTables(initial = false) {
    if (closed) return;
    const result = applyP25LiveTables(state, [...liveTables.values()], activityOptions(initial));
    if (expireIdle() || result.changed) updateScene(result);
  }

  function setLiveStatus(value) {
    if (value === 'live') setStatus(!state.available ? 'P25 activity unavailable · Reconnecting' :
      collectionEnabled ? 'Live' : 'Live · Activity collection off', state.available ? 'current' : 'stale');
    else if (['error', 'retrying', 'reconnecting'].includes(value)) setStatus('Reconnecting to live activity', 'stale');
    else setStatus('Connecting to live activity', 'stale');
  }

  function pausePresentation() {
    if (animationFrame) cancelAnimationFrame(animationFrame);
    animationFrame = 0;
    if (highlightTimer) window.clearTimeout(highlightTimer);
    highlightTimer = 0;
    pendingAffiliations.clear(); pendingFocusCandidates = [];
    sceneDirty = true;
    resetAttention();
  }

  function resumePresentation() {
    if (closed || presentationPaused()) return;
    lastAnimationAt = performance.now();
    if (expireIdle()) sceneDirty = true;
    acceptLiveTables(true);
    if (sceneDirty) updateScene();
    if (!animationFrame) animationFrame = requestAnimationFrame(animate);
  }

  function onVisibilityChanged() {
    pressedKeys.clear();
    if (presentationPaused()) pausePresentation();
    else resumePresentation();
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
    if (event.target?.closest?.('button, input, select, textarea, a')) return;
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
    if (closed || presentationPaused()) return;
    if (expireIdle()) updateScene();
    const delta = Math.min(0.05, Math.max(0, (at - lastAnimationAt) / 1_000));
    lastAnimationAt = at;
    if (mode === 'manual') renderer?.moveManualCamera({
      forward: Number(pressedKeys.has('w')) - Number(pressedKeys.has('s')),
      right: Number(pressedKeys.has('d')) - Number(pressedKeys.has('a')),
      up: Number(pressedKeys.has('e')) - Number(pressedKeys.has('q')),
      yaw: Number(pressedKeys.has('ArrowLeft')) - Number(pressedKeys.has('ArrowRight')),
      pitch: Number(pressedKeys.has('ArrowUp')) - Number(pressedKeys.has('ArrowDown'))
    }, delta);
    const cameraState = renderer?.isInteracting?.() ? null : camera.update(Date.now());
    if (mode === 'auto' && cameraState?.changed && cameraState.phase !== lastCameraPhase) {
      lastCameraPhase = cameraState.phase;
      renderer?.setCameraPhase(cameraState.phase);
      if (cameraState.phase === 'return') {
        autoFocus.dataset.phase = 'return';
        renderer?.home(cameraState.timing.returnMs);
      } else if (cameraState.phase === 'roam') clearAutoFocus();
    }
    animationFrame = requestAnimationFrame(animate);
  }

  auto.addEventListener('click', () => mode !== 'auto' && setMode('auto'));
  manual.addEventListener('click', () => mode !== 'manual' && setMode('manual'));
  eventsToggle.addEventListener('click', () => {
    events.hidden = !events.hidden;
    eventsToggle.setAttribute('aria-pressed', String(!events.hidden));
    if (!events.hidden) renderActions();
  });
  closeEvents.addEventListener('click', hideActions);
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
      createDirectListenAction: guideMode ? undefined : selected => {
        const group = state.groups.get(String(selected?.id || ''));
        const system = group && state.systems.get(group.systemKey);
        return group && system ? dependencies.createDirectListenAction?.(group, system, { labelButton: true }) : null;
      },
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
    renderer.setGuideSuspended(guideSuspended);
    const now = Date.now() + serverTimeOffsetMs;
    updateScene({
      affiliationChanges: [...pendingAffiliations.values()].filter(change => change.observedAtMs + 8_000 > now)
        .map(change => ({ ...change, remainingMs: Math.min(change.remainingMs, change.observedAtMs + 8_000 - now) })),
      focusCandidates: pendingFocusCandidates.filter(event => event.observedAtMs + 8_000 > now)
    });
    pendingAffiliations.clear(); pendingFocusCandidates = [];
  }

  const close = () => {
    if (closed) return;
    closed = true;
    resolveGuideScene(false);
    feed?.close();
    activitySubscription?.close();
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
  if (!presentationPaused()) animationFrame = requestAnimationFrame(animate);
  const rendererReady = initialize();
  feed = createP25ActivityFeed({
    openStream: dependencies.openActivityStream,
    onDelta: acceptAffiliations, onAppend: acceptRows, onStatus: setLiveStatus,
    onReset: () => {
      pendingAffiliations.clear(); pendingFocusCandidates = [];
      state.currentAffiliations.clear();
      resetAttention();
      renderGraph(false);
    },
    onBoundary: boundary => {
      if (sourceEpoch && sourceEpoch !== boundary.source_epoch) {
        const counts = state.actionCounts;
        state = createP25HistoryState({ actionLabel: dependencies.messageTypeLabel });
        state.actionCounts = counts;
        liveTables.clear();
        pendingAffiliations.clear(); pendingFocusCandidates = [];
        selectedSystemKey = selectedGroupKey = '';
        overviewSelected = scenePresented = false;
        resetAttention();
        renderGraph(false);
        setEmpty('Waiting for P25 activity', 'Talkgroups and radios appear as they are heard.');
      }
      sourceEpoch = boundary.source_epoch;
      state.available = boundary.available !== false;
      collectionEnabled = boundary.collection_enabled !== false;
      if (Number.isFinite(boundary.server_time_ms)) serverTimeOffsetMs = boundary.server_time_ms - Date.now();
    },
    onError: () => setLiveStatus('error'),
    setTimeout: (callback, delay) => window.setTimeout(callback, delay),
    clearTimeout: timer => window.clearTimeout(timer)
  });
  activitySubscription = dependencies.subscribeActivity({
    snapshot: snapshot => {
      liveTables.clear();
      for (const table of snapshot.tables || []) liveTables.set(table.table_id, table);
      acceptLiveTables(true);
    },
    activityBatch: batch => {
      for (const change of batch.changes || []) {
        if (change.operation === 'remove') liveTables.delete(change.table_id);
        else if (change.table) liveTables.set(change.table.table_id, change.table);
      }
      acceptLiveTables();
    },
    error: () => resetAttention()
  });
  document.addEventListener('visibilitychange', onVisibilityChanged);

  function setGuideSuspended(value) {
    if (closed || guideSuspended === Boolean(value)) return;
    guideSuspended = Boolean(value);
    pressedKeys.clear();
    renderer?.setGuideSuspended(guideSuspended);
    if (presentationPaused()) pausePresentation();
    else resumePresentation();
  }

  const ready = rendererReady.then(async () => {
    if (closed || renderer?.available !== true) return false;
    return guideMode ? Boolean(await guideSceneReady) && !closed : true;
  });
  async function prepareGuide(section) {
    if (!guideMode || !await ready || closed) return false;
    const showActions = ['actions', 'actions-close'].includes(section);
    if (showActions && events.hidden) eventsToggle.click();
    else if (!showActions && !events.hidden) hideActions();
    if (['manual-camera', 'auto-camera'].includes(section)) manual.click();
    else if (mode !== 'auto') auto.click();
    return true;
  }
  const controller = Object.freeze({ element: layout, close, dispose: close, ready,
    prepareGuide, setGuideSuspended });
  layout.p25VisualizerController = controller;
  return controller;
}

export { createP25Visualizer };
