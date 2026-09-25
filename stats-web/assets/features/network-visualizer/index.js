'use strict';

import { BALANCED_CONFIG, createConfig } from './config.js';
import {
  clearNetworkState,
  createNetworkState,
  drainObservationQueue,
  enqueueObservation,
  establishChannelActivityBoundary,
  ingestChannelActivitySnapshot,
  markTransportGap,
  networkStateCounts,
  setEntityPinned,
  setSelectedEntity,
  tickNetworkState
} from './state.js';
import { selectVisibleGraph } from './visibility.js';
import {
  createLayoutState,
  disposeLayout,
  dragEntity,
  restoreLayoutRecords,
  savedLayoutRecords,
  setLayoutFrozen,
  setLayoutMode,
  setLayoutPinned,
  setReducedMotion,
  stepLayout,
  synchronizeLayout,
  translateEntity,
  unlockLayout
} from './layout.js';
import { createNetworkVisualizerRenderer } from './renderer.js';
import { createNetworkVisualizerUi } from './ui.js';
import { createNetworkVisualizerFixture } from './fixture.js';

const STORAGE_PREFIX = 'sdrtrunk-vce.network-visualizer.v1';
const MAXIMUM_COORDINATE = 1_000_000;
const FIXTURE_HOSTS = new Set(['127.0.0.1', 'localhost', '[::1]']);

function freshSubscriptionId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  const bytes = new Uint8Array(16);
  globalThis.crypto?.getRandomValues?.(bytes);
  if (!bytes.some(Boolean)) {
    for (let index = 0; index < bytes.length; index += 1) bytes[index] = Math.floor(Math.random() * 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((value) => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function boundedInteger(value, fallback, minimum, maximum) {
  const number = Number(value);
  return Number.isSafeInteger(number) ? Math.max(minimum, Math.min(maximum, number)) : fallback;
}

function profileStorageKey(profileKey) {
  return `${STORAGE_PREFIX}:${encodeURIComponent(String(profileKey || 'anonymous').slice(0, 128))}`;
}

function validLayoutRecord(value, profileKey = 'default') {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const key = String(value.key || '').trim().slice(0, 512);
  const x = Number(value.x);
  const y = Number(value.y);
  const z = Number(value.z || 0);
  if (!key || ![x, y, z].every((coordinate) => Number.isFinite(coordinate) &&
      Math.abs(coordinate) <= MAXIMUM_COORDINATE)) return null;
  const type = ['universe', 'group', 'radio', 'aggregate'].includes(value.type) ? value.type : 'radio';
  return Object.freeze({ profileKey: String(profileKey || 'default').slice(0, 128), key, type, x, y, z,
    pinned: value.pinned === true, updatedAtMs: Number(value.updatedAtMs) || 0 });
}

function filterSuppressedEffects(graph, suppressedEffectIds) {
  if (!graph || !suppressedEffectIds?.size || !Array.isArray(graph.effects)) return graph;
  const effects = graph.effects.filter((effect) => !suppressedEffectIds.has(effect.id));
  if (effects.length === graph.effects.length) return graph;
  return Object.freeze({ ...graph, effects: Object.freeze(effects) });
}

function loadPreferences(profileKey, config = BALANCED_CONFIG) {
  const defaults = {
    mode: '3d',
    filters: { affiliations: true, activity: true, quiet: true },
    softRadiosTotal: config.render.softRadiosTotal,
    hardLabels: config.render.hardLabels,
    positions: []
  };
  try {
    const raw = JSON.parse(localStorage.getItem(profileStorageKey(profileKey)) || '{}');
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return defaults;
    const positions = (Array.isArray(raw.positions) ? raw.positions : [])
      .map((value) => validLayoutRecord(value, profileKey)).filter(Boolean)
      .slice(0, config.state.hardSavedLayoutRecords);
    let pinCount = 0;
    const boundedPositions = positions.map((record) => {
      const pinned = record.pinned && pinCount < config.state.hardPinnedEntities;
      if (pinned) pinCount += 1;
      return pinned === record.pinned ? record : Object.freeze({ ...record, pinned: false });
    });
    return {
      mode: raw.mode === 'flat' ? 'flat' : '3d',
      filters: {
        affiliations: raw.filters?.affiliations !== false,
        activity: raw.filters?.activity !== false,
        quiet: raw.filters?.quiet !== false
      },
      softRadiosTotal: boundedInteger(raw.softRadiosTotal, defaults.softRadiosTotal, 100,
        config.render.softRadiosTotal),
      hardLabels: boundedInteger(raw.hardLabels, defaults.hardLabels, 10, config.render.hardLabels),
      positions: boundedPositions
    };
  } catch (_error) {
    return defaults;
  }
}

function persistPreferences(profileKey, preferences, layout, config = BALANCED_CONFIG) {
  try {
    const records = savedLayoutRecords(layout).map((value) => validLayoutRecord(value, profileKey)).filter(Boolean)
      .slice(0, config.state.hardSavedLayoutRecords);
    let pinCount = 0;
    const positions = records.map((record) => {
      const pinned = record.pinned && pinCount < config.state.hardPinnedEntities;
      if (pinned) pinCount += 1;
      return { ...record, pinned };
    });
    localStorage.setItem(profileStorageKey(profileKey), JSON.stringify({
      version: 1,
      mode: preferences.mode,
      filters: preferences.filters,
      softRadiosTotal: preferences.softRadiosTotal,
      hardLabels: preferences.hardLabels,
      positions
    }));
  } catch (_error) {
    // Layout persistence is optional when browser storage is unavailable or full.
  }
}

function parseEventData(event) {
  if (!event) return null;
  try {
    return typeof event.data === 'string' ? JSON.parse(event.data) : event.data;
  } catch (_error) {
    return null;
  }
}

function entityForKey(state, key) {
  return state.universes.get(key) || state.groups.get(key) || state.radios.get(key) || null;
}

function p25Hex(value, width) {
  const numeric = Number(value);
  return Number.isSafeInteger(numeric) && numeric >= 0 ? numeric.toString(16).toUpperCase().padStart(width, '0') : '';
}

function selectedEntityView(state) {
  const entity = entityForKey(state, state.visual.selectedKey);
  if (!entity) return null;
  const universe = entity.type === 'universe' ? entity : state.universes.get(entity.universeKey);
  const affiliations = entity.type === 'radio' ? [...entity.affiliations.values()] : [];
  const latestAffiliation = affiliations.slice().sort((left, right) =>
    (right.observedAtMs || 0) - (left.observedAtMs || 0))[0] || null;
  const affiliationGroups = affiliations.map((item) => state.groups.get(item.groupKey)).filter(Boolean);
  const affiliationNames = affiliationGroups.map((group) => group.label).join(' · ');
  const activeCalls = entity.activeCallKeys ? [...entity.activeCallKeys].map((key) => state.activeCalls.get(key))
    .filter(Boolean) : [];
  const txGroup = activeCalls.map((call) => state.groups.get(call.groupKey)).find(Boolean);
  const siteEvidence = entity.siteEvidence?.[0] || null;
  const p25 = entity.protocol === 'p25' || universe?.protocol === 'p25';
  const affiliationCapability = entity.type === 'radio' ? (p25 ? 'supported' : 'unsupported') : 'not_applicable';
  const affiliationLabel = affiliationCapability === 'unsupported' ? 'Unsupported by this live feed' :
    (entity.affiliationAmbiguous ?
      `Ambiguous across observation scopes${affiliationNames ? ` · ${affiliationNames}` : ''}` :
      (entity.affiliationContinuity === 'uncertain' ?
        `Uncertain after live gap${affiliationNames ? ` · ${affiliationNames}` : ''}` : affiliationNames));
  return {
    ...entity,
    kind: entity.kind || entity.type,
    systemName: universe?.label || '',
    radioSystemKey: universe?.radioSystemKey || '',
    wacn: universe?.wacn || '',
    systemId: universe?.systemId || '',
    configurationId: siteEvidence?.configurationId || [...(universe?.configurationIds || [])][0] || '',
    siteName: siteEvidence?.siteName || '',
    channelName: siteEvidence?.channelName || '',
    rfssId: p25 ? p25Hex(siteEvidence?.rfssId, 2) : siteEvidence?.rfssId,
    siteId: p25 ? p25Hex(siteEvidence?.siteId, 2) : siteEvidence?.siteId,
    nac: p25 ? p25Hex(siteEvidence?.nac, 3) : siteEvidence?.nac,
    timeslot: siteEvidence?.timeslot,
    affiliationCapability,
    affiliationLabel,
    affiliationObservedAtMs: affiliations.reduce((latest, item) => Math.max(latest, item.observedAtMs || 0), 0),
    affiliationEvidenceType: latestAffiliation?.evidenceType || '',
    txTargetLabel: txGroup?.label || '',
    txContinuityUncertain: activeCalls.some((call) => call.uncertain),
    encrypted: activeCalls.some((call) => call.encrypted)
  };
}

function selectedTransitions(state) {
  const entity = state.radios.get(state.visual.selectedKey);
  if (!entity) return [];
  const ids = new Set(entity.transitionEventIds || []);
  return state.semanticEvents.filter((event) => ids.has(event.id)).map((event) => ({
    ...event,
    fromLabel: state.groups.get(event.oldGroupKey)?.label || 'Unknown',
    toLabel: state.groups.get(event.newGroupKey)?.label || 'Unknown'
  }));
}

function eventViews(state) {
  return state.semanticEvents.map((event) => ({
    ...event,
    kind: event.type,
    radioLabel: state.radios.get(event.radioKey)?.label || '',
    groupLabel: state.groups.get(event.groupKey || event.newGroupKey)?.label || '',
    fromLabel: state.groups.get(event.oldGroupKey)?.label || '',
    toLabel: state.groups.get(event.newGroupKey)?.label || ''
  }));
}

function createNetworkVisualizer(dependencies = {}) {
  const required = ['node', 'iconGlyph', 'iconButton', 'liveConnection', 'subscribeLiveChannelActivity'];
  required.forEach((name) => {
    if (typeof dependencies[name] !== 'function') throw new TypeError(`Network Visualizer dependency ${name} is required.`);
  });

  const preferences = loadPreferences(dependencies.profileKey, BALANCED_CONFIG);
  const config = createConfig({ render: {
    softRadiosTotal: preferences.softRadiosTotal,
    hardLabels: preferences.hardLabels
  } });
  const state = createNetworkState(config);
  const reducedMotionMedia = window.matchMedia?.('(prefers-reduced-motion: reduce)') || null;
  const layout = createLayoutState(config, { mode: preferences.mode, profileKey: dependencies.profileKey,
    reducedMotion: Boolean(reducedMotionMedia?.matches) });
  restoreLayoutRecords(layout, preferences.positions);
  const activityTables = new Map();
  let renderer = null;
  let currentGraph = { nodes: [], links: [], counts: {} };
  let networkConnection = null;
  let channelConnection = null;
  let closed = false;
  let frozen = false;
  let dirty = true;
  let graphDirty = true;
  let channelOpen = false;
  let networkOpen = false;
  let networkSourceToken = '';
  let hadGap = false;
  let suppressNextSnapshotEffects = false;
  let suppressNextIngestEffects = false;
  let raf = null;
  let lastFrameAt = performance.now();
  let lastTickAt = 0;
  let lastStaticVisualRefreshAt = 0;
  let lastEventsRevision = -1;
  let ingestionTimer = null;
  let persistTimer = null;
  let rendererInitialization = null;
  let controller = null;
  let searchPendingFocus = false;
  let pendingFocusKey = '';
  const suppressedEffectIds = new Set();
  const fixtureMode = new URLSearchParams(window.location.search).get('network_fixture') === '1' &&
    FIXTURE_HOSTS.has(window.location.hostname);

  function invalidateGraph() {
    graphDirty = true;
    dirty = true;
  }

  const ui = createNetworkVisualizerUi({
    ...dependencies,
    config,
    initialFilters: preferences.filters,
    callbacks: {
      onFilters: (filters) => {
        preferences.filters = filters;
        invalidateGraph();
        schedulePersist();
      },
      onSearch: () => invalidateGraph(),
      onSearchCommit: () => {
        searchPendingFocus = true;
        invalidateGraph();
      },
      onFit: () => renderer?.fitAll?.(),
      onFocus: () => {
        if (state.visual.selectedKey) renderer?.focus?.(state.visual.selectedKey);
      },
      onMode: (mode) => {
        preferences.mode = mode === 'flat' ? 'flat' : '3d';
        setLayoutMode(layout, preferences.mode);
        renderer?.setMode?.(preferences.mode);
        ui.setMode(preferences.mode);
        invalidateGraph();
        schedulePersist();
      },
      onFreeze: (value) => {
        frozen = Boolean(value);
        setLayoutFrozen(layout, frozen);
        renderer?.setFrozen?.(frozen);
        ui.setFrozen(frozen);
        invalidateGraph();
      },
      onClear: () => clearMap(),
      onResize: () => renderer?.resize?.(),
      onSelectionClear: () => selectEntity(null),
      onPin: (key, value) => pinEntity(key, value),
      onDensity: (value) => {
        preferences.softRadiosTotal = boundedInteger(value.softRadiosTotal, preferences.softRadiosTotal,
          100, config.render.softRadiosTotal);
        preferences.hardLabels = boundedInteger(value.hardLabels, preferences.hardLabels,
          10, config.render.hardLabels);
        renderer?.setLabelBudget?.(preferences.hardLabels);
        invalidateGraph();
        schedulePersist();
      },
      onUnlock: () => {
        unlockLayout(layout);
        [...state.visual.pinnedKeys].forEach((key) => setEntityPinned(state, key, false));
        preferences.positions = [];
        invalidateGraph();
        schedulePersist(true);
      }
    }
  });
  ui.setMode(preferences.mode);

  function schedulePersist(immediate = false) {
    if (persistTimer !== null) window.clearTimeout(persistTimer);
    persistTimer = window.setTimeout(() => {
      persistTimer = null;
      persistPreferences(dependencies.profileKey, preferences, layout, config);
    }, immediate ? 0 : 250);
  }

  function combinedTransportStatus() {
    if (hadGap) return 'gap';
    if (networkOpen && channelOpen) return 'open';
    return state.transport.status === 'error' ? 'error' : 'connecting';
  }

  function updateTransport() {
    const status = combinedTransportStatus();
    ui.setTransport(status, hadGap ? 'A live observation interval was lost; active call continuity is uncertain.' : '');
  }

  function selectEntity(value) {
    let key = typeof value === 'string' ? value : value?.key || value?.id || null;
    if (value?.type === 'aggregate') {
      key = value.focusKey || value.groupKey || value.universeKey || null;
      if (!key) {
        ui.showNotice(value.label || 'Additional live activity is retained in the bounded aggregate.');
        return;
      }
      pendingFocusKey = key;
    } else {
      pendingFocusKey = '';
    }
    setSelectedEntity(state, key);
    const selected = selectedEntityView(state);
    ui.setSelection(selected, selectedTransitions(state));
    currentGraph.nodes?.forEach((nodeValue) => { nodeValue.selected = nodeValue.key === state.visual.selectedKey; });
    renderer?.refresh?.();
    invalidateGraph();
  }

  function pinEntity(key, value) {
    if (!setEntityPinned(state, key, value)) {
      ui.showNotice(`No more than ${config.state.hardPinnedEntities} entities can be pinned.`);
      return;
    }
    setLayoutPinned(layout, key, value);
    ui.setSelection(selectedEntityView(state), selectedTransitions(state));
    invalidateGraph();
    schedulePersist();
  }

  function onRendererDrag(payload, finished = false) {
    const key = payload?.key || payload?.node?.key;
    if (!key) return;
    const entity = entityForKey(state, key);
    if (!entity) return;
    const draggingStarted = !finished && !state.visual.draggedKeys.has(key);
    if (!finished) state.visual.draggedKeys.add(key);
    const delta = payload?.delta;
    if (!finished && (entity.type === 'universe' || entity.type === 'group') && delta) {
      translateEntity(layout, key, delta);
    } else if (!(finished && (entity.type === 'universe' || entity.type === 'group')) && payload?.position) {
      dragEntity(layout, key, payload.position);
    }
    if (finished) {
      state.visual.draggedKeys.delete(key);
      schedulePersist();
    }
    if (draggingStarted || finished) invalidateGraph();
    else dirty = true;
  }

  function initializeRenderer() {
    rendererInitialization = Promise.resolve(createNetworkVisualizerRenderer({
      host: ui.canvas,
      signal: dependencies.signal,
      config,
      mode: preferences.mode,
      frozen,
      graphData: currentGraph,
      clock: () => Date.now(),
      labelBudget: preferences.hardLabels,
      particleBudget: config.render.hardParticles,
      callbacks: {
        onNodeClick: (value) => selectEntity(value),
        onBackgroundClick: () => selectEntity(null),
        onNodeDrag: (payload) => onRendererDrag(payload),
        onNodeDragEnd: (payload) => onRendererDrag(payload, true),
        onContextLost: () => {
          ui.setWebglState('failed', 'The graphics context was lost. Live observations continue in the event drawer.');
          ui.showNotice('WebGL context lost — live observations are still being retained.');
        },
        onContextRestored: () => ui.setWebglState('ready'),
        onUnavailable: (error) => ui.setWebglState('failed', error?.message ||
          'The WebGL renderer could not be initialized.'),
        onOffscreenActivity: ({ count } = {}) => ui.setOffscreenActivity(count),
        onCameraInteraction: () => { /* The renderer permanently disables automatic framing after interaction. */ }
      },
      reducedMotion: Boolean(reducedMotionMedia?.matches)
    })).then((value) => {
      if (closed) {
        value?.dispose?.();
        return;
      }
      renderer = value;
      ui.setWebglState(value?.available === false ? 'failed' : 'ready');
      renderer.setMode?.(preferences.mode);
      renderer.setFrozen?.(frozen);
      invalidateGraph();
    }).catch((error) => {
      if (closed) return;
      ui.setWebglState('failed', error?.message || 'The WebGL renderer could not be initialized.');
    });
  }

  function fullActivitySnapshot(revision = 0) {
    return { source_key: 'channel_activity', revision, tables: [...activityTables.values()] };
  }

  function receiveChannelSnapshot(snapshot) {
    if (closed || !snapshot || !Array.isArray(snapshot.tables)) return;
    activityTables.clear();
    snapshot.tables.forEach((table) => {
      if (table?.table_id) activityTables.set(String(table.table_id), table);
    });
    const complete = fullActivitySnapshot(Number(snapshot.revision) || 0);
    const generation = state.generation;
    const effectsBefore = state.pendingEffects.length;
    ingestChannelActivitySnapshot(state, complete, generation, Date.now());
    if (suppressNextSnapshotEffects || document.hidden) {
      state.pendingEffects.slice(effectsBefore).forEach((effect) => suppressedEffectIds.add(effect.id));
    }
    suppressNextSnapshotEffects = false;
    invalidateGraph();
  }

  function receiveChannelTable(update) {
    if (closed || !update) return;
    const id = String(update.table_id || update.table?.table_id || '');
    if (!id) return;
    if (update.operation === 'remove') activityTables.delete(id);
    else if (update.table) activityTables.set(id, update.table);
    const effectsBefore = state.pendingEffects.length;
    ingestChannelActivitySnapshot(state, fullActivitySnapshot(Number(update.revision) || 0), state.generation,
      Date.now());
    if (document.hidden) {
      state.pendingEffects.slice(effectsBefore).forEach((effect) => suppressedEffectIds.add(effect.id));
    }
    invalidateGraph();
  }

  function observeGap(detail = {}) {
    const now = Date.now();
    const first = !hadGap;
    hadGap = true;
    suppressNextSnapshotEffects = true;
    markTransportGap(state, detail, state.generation, now);
    suppressedEffectIds.clear();
    renderer?.clearEffects?.();
    if (first) ui.showNotice('Live observation gap — active transmission continuity is uncertain.', true);
    updateTransport();
    invalidateGraph();
  }

  function openNetworkConnection(generation = state.generation) {
    const subscriptionId = freshSubscriptionId();
    const connection = dependencies.liveConnection('network_activity', { subscription_id: subscriptionId });
    networkConnection = connection;
    connection.addEventListener('source_change', (event) => {
      if (closed || generation !== state.generation) return;
      const data = parseEventData(event) || {};
      if (data.subscription_id && data.subscription_id !== subscriptionId) return;
      const sourceGeneration = String(data.source_generation ?? data.generation ?? '');
      const liveEdgeEpoch = String(data.live_edge_epoch ?? '');
      const token = sourceGeneration || liveEdgeEpoch ? `${sourceGeneration}:${liveEdgeEpoch}` : '';
      if (networkSourceToken && token && token !== networkSourceToken) observeGap({ reason: 'source_reset' });
      if (token) networkSourceToken = token;
      networkOpen = true;
      updateTransport();
    });
    connection.addEventListener('network_event', (event) => {
      if (closed || generation !== state.generation) return;
      const value = parseEventData(event);
      if (value) enqueueObservation(state, value, generation, Date.now());
    });
    connection.addEventListener('live_gap', (event) => {
      if (closed || generation !== state.generation) return;
      observeGap(parseEventData(event) || { reason: 'network_activity_gap' });
    });
    connection.onopen = () => {
      if (closed || generation !== state.generation) return;
      networkOpen = true;
      updateTransport();
    };
    connection.onerror = () => {
      if (closed || generation !== state.generation) return;
      networkOpen = false;
      state.transport.status = 'error';
      observeGap({ reason: 'network_activity_disconnected' });
    };
  }

  function reopenNetworkConnection(generation = state.generation) {
    const previous = networkConnection;
    networkConnection = null;
    networkOpen = false;
    Promise.resolve(previous?.close?.()).catch(() => {}).finally(() => {
      if (!closed && generation === state.generation) openNetworkConnection(generation);
    });
  }

  function openChannelConnection(generation = state.generation) {
    const connection = dependencies.subscribeLiveChannelActivity({
      snapshot: (snapshot) => {
        if (closed || generation !== state.generation) return;
        receiveChannelSnapshot(snapshot);
      },
      activityTable: (update) => {
        if (closed || generation !== state.generation) return;
        receiveChannelTable(update);
      },
      gap: (detail) => {
        if (closed || generation !== state.generation) return;
        observeGap(detail || { reason: 'channel_activity_gap' });
      },
      open: () => {
        if (closed || generation !== state.generation) return;
        channelOpen = true;
        updateTransport();
      },
      error: () => {
        if (closed || generation !== state.generation) return;
        channelOpen = false;
        state.transport.status = 'error';
        observeGap({ reason: 'channel_activity_disconnected' });
      }
    });
    channelConnection = connection;
  }

  function reopenChannelConnection(generation = state.generation) {
    const previous = channelConnection;
    channelConnection = null;
    channelOpen = false;
    Promise.resolve(previous?.close?.()).catch(() => {}).finally(() => {
      if (!closed && generation === state.generation) openChannelConnection(generation);
    });
  }

  function clearMap() {
    clearNetworkState(state, Date.now());
    establishChannelActivityBoundary(state, fullActivitySnapshot(), state.generation, Date.now());
    suppressedEffectIds.clear();
    currentGraph = { nodes: [], links: [], counts: {} };
    renderer?.clearEffects?.();
    renderer?.setGraphData?.(currentGraph);
    ui.setCounts({ visibleRadios: 0, retainedRadios: 0, renderedNodes: 0 });
    ui.setSelection(null);
    ui.setEvents([]);
    ui.setOffscreenActivity(0);
    ui.searchInput.value = '';
    ui.showNotice('Map cleared. Listening from a new live edge.');
    networkOpen = false;
    channelOpen = false;
    hadGap = false;
    suppressNextSnapshotEffects = false;
    suppressNextIngestEffects = false;
    pendingFocusKey = '';
    networkSourceToken = '';
    if (fixtureMode) {
      ui.setTransport('open', 'Deterministic development fixture');
    } else {
      reopenChannelConnection(state.generation);
      reopenNetworkConnection(state.generation);
    }
    invalidateGraph();
  }

  function applySavedPins() {
    let count = 0;
    preferences.positions.forEach((record) => {
      if (!record.pinned || count >= config.state.hardPinnedEntities || !entityForKey(state, record.key)) return;
      if (setEntityPinned(state, record.key, true)) {
        setLayoutPinned(layout, record.key, true);
        count += 1;
      }
    });
  }

  function consumeEffects() {
    const activeIds = new Set(state.pendingEffects.map((effect) => effect.id));
    [...suppressedEffectIds].forEach((id) => { if (!activeIds.has(id)) suppressedEffectIds.delete(id); });
    if (document.hidden) {
      state.pendingEffects.forEach((effect) => suppressedEffectIds.add(effect.id));
    }
  }

  function updateUiFromState() {
    if (lastEventsRevision !== state.nextHistoryId) {
      lastEventsRevision = state.nextHistoryId;
      ui.setEvents(eventViews(state));
      ui.setSelection(selectedEntityView(state), selectedTransitions(state));
    }
    consumeEffects();
  }

  function ingest() {
    if (closed) return;
    const effectsBefore = state.pendingEffects.length;
    const results = drainObservationQueue(state);
    if (document.hidden || suppressNextIngestEffects) {
      state.pendingEffects.slice(effectsBefore).forEach((effect) => suppressedEffectIds.add(effect.id));
    }
    suppressNextIngestEffects = false;
    if (results.some((result) => result?.applied)) invalidateGraph();
    const now = Date.now();
    if (now - lastTickAt >= 250) {
      const before = networkStateCounts(state);
      tickNetworkState(state, now);
      const after = networkStateCounts(state);
      if (before.radios !== after.radios || before.groups !== after.groups ||
          before.activeCalls !== after.activeCalls || before.pendingEffects !== after.pendingEffects) invalidateGraph();
      lastTickAt = now;
    }
    if (!document.hidden && now - lastStaticVisualRefreshAt >= 1_000 &&
        (state.universes.size || state.groups.size || state.radios.size)) {
      lastStaticVisualRefreshAt = now;
      invalidateGraph();
    }
    updateUiFromState();
  }

  function draw(frameAt) {
    if (closed) return;
    raf = window.requestAnimationFrame(draw);
    if (document.hidden || frameAt - lastFrameAt < 30) return;
    const delta = Math.min(config.layout.maximumDeltaMs, Math.max(1, frameAt - lastFrameAt));
    lastFrameAt = frameAt;
    if (!dirty && (frozen || layout.reducedMotion)) return;
    const now = Date.now();
    if (graphDirty) applySavedPins();
    const selectionChanged = graphDirty;
    if (selectionChanged) {
      currentGraph = filterSuppressedEffects(selectVisibleGraph(state, now, {
        filters: preferences.filters,
        query: ui.searchInput.value,
        softRadiosTotal: preferences.softRadiosTotal,
        hardLabels: preferences.hardLabels
      }), suppressedEffectIds);
      graphDirty = false;
      if (searchPendingFocus) {
        searchPendingFocus = false;
        const match = currentGraph.searchResults?.[0];
        if (match) {
          selectEntity(match.key);
          queueMicrotask(() => renderer?.focus?.(match.key));
        } else if (ui.searchInput.value.trim()) ui.showNotice('No retained entity matches that search.');
      }
    }
    synchronizeLayout(layout, currentGraph, now);
    if (!frozen) stepLayout(layout, currentGraph, delta);
    if (selectionChanged) {
      renderer?.setGraphData?.(currentGraph);
      const counts = currentGraph.counts || {};
      ui.setCounts({
        ...counts,
        visibleRadios: counts.visibleRadios ?? currentGraph.nodes.filter((value) => value.type === 'radio').length,
        retainedRadios: state.radios.size,
        renderedNodes: currentGraph.nodes.length,
        suppressedActiveRadios: counts.suppressedActiveRadios ?? counts.activeAggregated ?? 0
      });
      if (pendingFocusKey && currentGraph.nodes.some((node) => node.key === pendingFocusKey)) {
        const key = pendingFocusKey;
        pendingFocusKey = '';
        queueMicrotask(() => renderer?.focus?.(key));
      }
    } else {
      renderer?.refresh?.();
    }
    dirty = graphDirty || (!frozen && !layout.reducedMotion && currentGraph.nodes.length > 0);
  }

  function handleVisibility() {
    if (closed) return;
    if (document.hidden) {
      state.pendingEffects.forEach((effect) => suppressedEffectIds.add(effect.id));
      renderer?.setFrozen?.(true);
    } else {
      state.pendingEffects.forEach((effect) => suppressedEffectIds.add(effect.id));
      suppressNextIngestEffects = state.incomingQueue.length > 0;
      renderer?.setFrozen?.(frozen);
      lastFrameAt = performance.now();
      invalidateGraph();
    }
  }

  function handleReducedMotion(event) {
    const reduced = Boolean(event?.matches);
    setReducedMotion(layout, reduced);
    renderer?.setReducedMotion?.(reduced);
    invalidateGraph();
  }

  function startLive() {
    openChannelConnection(state.generation);
    openNetworkConnection(state.generation);
  }

  function startFixture() {
    const fixture = createNetworkVisualizerFixture({ startedAtMs: Date.now() - 600 });
    ui.setTransport('open', 'Deterministic development fixture');
    ui.showNotice('Development fixture active — this is not receiver activity.');
    const observations = Array.isArray(fixture) ? fixture : [
      ...(fixture.observations || fixture.events || []),
      ...(typeof fixture.overflow === 'function' ? fixture.overflow(1_200) : [])
    ];
    observations.forEach((entry) => {
      const raw = entry?.payload || entry?.observation || entry?.event || entry;
      enqueueObservation(state, raw, state.generation, Number(raw?.observed_at_ms) || Date.now());
    });
    if (fixture?.snapshots?.active || fixture?.snapshot) {
      receiveChannelSnapshot(fixture.snapshots?.active || fixture.snapshot);
    }
  }

  const handleAbort = () => controller?.close();
  document.addEventListener('visibilitychange', handleVisibility);
  reducedMotionMedia?.addEventListener?.('change', handleReducedMotion);
  dependencies.signal?.addEventListener('abort', handleAbort, { once: true });
  ingestionTimer = window.setInterval(ingest, 50);
  initializeRenderer();
  if (fixtureMode) startFixture();
  else startLive();
  raf = window.requestAnimationFrame(draw);

  controller = {
    element: ui.element,
    clear: clearMap,
    diagnostics() {
      return {
        state: networkStateCounts(state),
        graph: {
          nodes: currentGraph.nodes.length,
          links: currentGraph.links.length,
          counts: currentGraph.counts || {}
        },
        renderer: renderer?.diagnostics?.() || null,
        subscriptions: { channel: Boolean(channelConnection), network: Boolean(networkConnection) },
        timers: { ingestion: ingestionTimer !== null, animation: raf !== null },
        generation: state.generation,
        fixture: fixtureMode
      };
    },
    close() {
      if (closed) return;
      closed = true;
      document.removeEventListener('visibilitychange', handleVisibility);
      reducedMotionMedia?.removeEventListener?.('change', handleReducedMotion);
      dependencies.signal?.removeEventListener?.('abort', handleAbort);
      if (ingestionTimer !== null) window.clearInterval(ingestionTimer);
      if (persistTimer !== null) window.clearTimeout(persistTimer);
      if (raf !== null) window.cancelAnimationFrame(raf);
      ingestionTimer = null;
      persistTimer = null;
      raf = null;
      persistPreferences(dependencies.profileKey, preferences, layout, config);
      channelConnection?.close?.();
      networkConnection?.close?.();
      channelConnection = null;
      networkConnection = null;
      renderer?.dispose?.();
      renderer = null;
      disposeLayout(layout);
      ui.close();
    }
  };

  return controller;
}

export {
  createNetworkVisualizer,
  filterSuppressedEffects,
  loadPreferences,
  persistPreferences,
  profileStorageKey,
  selectedEntityView
};
