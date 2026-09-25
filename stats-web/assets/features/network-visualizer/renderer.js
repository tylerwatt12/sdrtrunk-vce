'use strict';

import { BALANCED_CONFIG } from './config.js';

const VENDOR_ASSET = '../../vendor/network-visualizer-vendor.js?v=2';
const LINK_SEGMENTS = 16;
const LABEL_CELL_WIDTH = 72;
const LABEL_CELL_HEIGHT = 34;
const LABEL_PADDING = 6;
const LABEL_RENDER_INTERVAL_MS = 80;
const LABEL_RESIDENCE_MS = 1_600;
const LABEL_HIDDEN_RESIDENCE_MS = 420;
const MINIMUM_CAMERA_DISTANCE = 68;
const DEFAULT_CAMERA_DISTANCE = 112;
const EFFECT_SEEN_MULTIPLIER = 2;
const OVERVIEW_DISTANCE_RATIO = 0.85;
const MID_DISTANCE_RATIO = 0.32;
const DEFAULT_ANIMATION = Object.freeze({
  txAttackMs: 180,
  txReleaseMs: 2_400,
  pulseDurationMs: 700,
  particleFlightMs: 1_500,
  pulseScale: 0.1,
  migrationMotionMs: 1_400,
  effectCoalesceMs: 2_200,
  labelMinimumResidenceMs: LABEL_RESIDENCE_MS,
  labelHiddenResidenceMs: LABEL_HIDDEN_RESIDENCE_MS,
  softAnimatedEffects: 24,
  cameraTransitionMs: 720,
  cameraBackTransitionMs: 620,
  autoRotateIdleDelayMs: 2_400,
  autoRotateSpeed: 0.35
});

let vendorPromise = null;

function loadVendor() {
  if (!vendorPromise) vendorPromise = import(new URL(VENDOR_ASSET, import.meta.url).href);
  return vendorPromise;
}

function finite(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function clamp(value, minimum, maximum) {
  return Math.max(minimum, Math.min(maximum, value));
}

function easeInOutCubic(value) {
  const progress = clamp(finite(value), 0, 1);
  return progress < 0.5 ? 4 * progress * progress * progress :
    1 - Math.pow(-2 * progress + 2, 3) / 2;
}

function nodeKey(node) {
  const value = node?.key ?? node?.id;
  return value === null || value === undefined ? '' : String(value);
}

function endpointKey(endpoint) {
  return typeof endpoint === 'object' && endpoint !== null ? nodeKey(endpoint) : String(endpoint ?? '');
}

function linkKey(link, fallback = '') {
  const value = link?.key ?? link?.id;
  return value === null || value === undefined ? String(fallback) : String(value);
}

function nodeType(node) {
  const type = String(node?.type || node?.kind || '').toLowerCase();
  if (type === 'system') return 'universe';
  if (type === 'talkgroup' || type === 'channel' || type === 'hub') return 'group';
  if (type === 'subscriber') return 'radio';
  return ['universe', 'group', 'radio', 'aggregate'].includes(type) ? type : 'radio';
}

function nodeGeometryKind(node) {
  const type = nodeType(node);
  const kind = String(node?.kind || node?.entity?.kind || '').toLowerCase();
  if (kind === 'channel' && type === 'universe') return 'conventional-universe';
  if (kind === 'channel' && type === 'group') return 'conventional-group';
  return type;
}

function normalizedLinkType(link) {
  const type = String(link?.type || link?.kind || '').toLowerCase();
  return ['affiliation', 'activity', 'tx', 'migration'].includes(type) ? type : 'activity';
}

function boundedGraphData(value = {}, limits = {}) {
  const maximumNodes = Math.max(1, Math.floor(finite(limits.nodes, BALANCED_CONFIG.render.hardNodes)));
  const maximumLinks = Math.max(0, Math.floor(finite(limits.links, BALANCED_CONFIG.render.hardLinks)));
  const maximumEffects = Math.max(0,
    Math.floor(finite(limits.effects, BALANCED_CONFIG.state.hardPendingEffects)));
  const inputNodes = Array.isArray(value.nodes) ? value.nodes : [];
  const inputLinks = Array.isArray(value.links) ? value.links : [];
  const nodes = [];
  const included = new Set();

  for (const candidate of inputNodes) {
    const key = nodeKey(candidate);
    if (!key || included.has(key) || nodes.length >= maximumNodes) continue;
    nodes.push(candidate);
    included.add(key);
  }

  const links = [];
  let invalidLinks = 0;
  for (const candidate of inputLinks) {
    const source = endpointKey(candidate?.source);
    const target = endpointKey(candidate?.target);
    if (!source || !target || !included.has(source) || !included.has(target)) {
      invalidLinks += 1;
      continue;
    }
    if (links.length < maximumLinks) links.push(candidate);
  }

  const requestedLabels = Array.isArray(value.labels) ? value.labels.map((entry) =>
    typeof entry === 'object' && entry !== null ? nodeKey(entry) : String(entry)).filter((key) => included.has(key)) : [];
  const effects = Array.isArray(value.effects) ? value.effects.slice(0, maximumEffects) : [];
  return {
    ...value,
    nodes,
    links,
    labels: requestedLabels,
    effects,
    rendererLimits: Object.freeze({
      inputNodes: inputNodes.length,
      inputLinks: inputLinks.length,
      inputEffects: Array.isArray(value.effects) ? value.effects.length : 0,
      suppressedNodes: Math.max(0, inputNodes.length - nodes.length),
      suppressedLinks: Math.max(0, inputLinks.length - links.length),
      suppressedEffects: Math.max(0, (Array.isArray(value.effects) ? value.effects.length : 0) - effects.length)
    })
  };
}

function rectanglesOverlap(left, right, padding) {
  return left.left < right.right + padding && left.right + padding > right.left &&
    left.top < right.bottom + padding && left.bottom + padding > right.top;
}

/** Pure screen-space label selection used by the renderer and deterministic tests. */
function chooseLabelPlacements(candidates, viewport, budget, options = {}) {
  const width = Math.max(0, finite(viewport?.width));
  const height = Math.max(0, finite(viewport?.height));
  const maximum = Math.max(0, Math.floor(finite(budget)));
  const padding = Math.max(0, finite(options.padding, LABEL_PADDING));
  const cellWidth = Math.max(8, finite(options.cellWidth, LABEL_CELL_WIDTH));
  const cellHeight = Math.max(8, finite(options.cellHeight, LABEL_CELL_HEIGHT));
  const grid = new Map();
  const accepted = [];
  const ordered = (Array.isArray(candidates) ? candidates : []).map((candidate, index) => ({
    ...candidate,
    __order: index
  })).sort((left, right) => finite(right.priority) - finite(left.priority) || left.__order - right.__order);

  for (const candidate of ordered) {
    if (accepted.length >= maximum) break;
    const labelWidth = Math.max(1, finite(candidate.width, 80));
    const labelHeight = Math.max(1, finite(candidate.height, 22));
    const offsets = Array.isArray(candidate.offsets) && candidate.offsets.length ? candidate.offsets :
      [{ x: finite(candidate.offsetX, 7), y: finite(candidate.offsetY), index: 0 }];
    let placement = null;
    let occupiedCells = null;
    const offsetCandidates = offsets.map((offset, offsetOrder) => {
      const left = Math.round(finite(candidate.x) + finite(offset?.x, 7));
      const top = Math.round(finite(candidate.y) - labelHeight / 2 + finite(offset?.y));
      const rectangle = { left, top, right: left + labelWidth, bottom: top + labelHeight };
      const visibleWidth = Math.max(0, Math.min(width, rectangle.right) - Math.max(0, rectangle.left));
      const visibleHeight = Math.max(0, Math.min(height, rectangle.bottom) - Math.max(0, rectangle.top));
      return { offset, offsetOrder, left, top, rectangle,
        contained: rectangle.left >= 0 && rectangle.top >= 0 && rectangle.right <= width &&
          rectangle.bottom <= height,
        visibleArea: visibleWidth * visibleHeight };
    }).filter((entry) => entry.visibleArea > 0).sort((left, right) =>
      Number(right.contained) - Number(left.contained) ||
      (left.contained ? left.offsetOrder - right.offsetOrder :
        right.visibleArea - left.visibleArea || left.offsetOrder - right.offsetOrder));
    for (const entry of offsetCandidates) {
      const { offset, offsetOrder, left, top, rectangle } = entry;

      const minimumColumn = Math.floor((rectangle.left - padding) / cellWidth);
      const maximumColumn = Math.floor((rectangle.right + padding) / cellWidth);
      const minimumRow = Math.floor((rectangle.top - padding) / cellHeight);
      const maximumRow = Math.floor((rectangle.bottom + padding) / cellHeight);
      const possible = new Set();
      const cells = [];
      for (let column = minimumColumn; column <= maximumColumn; column += 1) {
        for (let row = minimumRow; row <= maximumRow; row += 1) {
          const cell = `${column}:${row}`;
          cells.push(cell);
          (grid.get(cell) || []).forEach((index) => possible.add(index));
        }
      }
      if ([...possible].some((index) => rectanglesOverlap(rectangle, accepted[index].rectangle, padding))) continue;
      placement = { ...candidate, left, top, rectangle,
        offsetIndex: Number.isSafeInteger(offset?.index) ? offset.index : offsetOrder };
      occupiedCells = cells;
      break;
    }
    if (!placement) continue;
    delete placement.__order;
    delete placement.offsets;
    const acceptedIndex = accepted.length;
    accepted.push(placement);
    occupiedCells.forEach((key) => {
      const contents = grid.get(key) || [];
      contents.push(acceptedIndex);
      grid.set(key, contents);
    });
  }
  return accepted;
}

function particlePriority(link) {
  const type = normalizedLinkType(link);
  if (type === 'tx' && link.active) return 4;
  if (type === 'migration') return 3;
  if (type === 'activity' && link.active) return 2;
  return 0;
}

/** Returns a bounded allocation keyed by rendered link ID. */
function allocateParticles(links, budget) {
  let remaining = Math.max(0, Math.floor(finite(budget)));
  const allocation = new Map();
  const ordered = (Array.isArray(links) ? links : []).map((link, index) => ({ link, index,
    priority: particlePriority(link) })).filter((entry) => entry.priority > 0)
    .sort((left, right) => right.priority - left.priority || left.index - right.index);
  for (const { link, index, priority } of ordered) {
    if (!remaining) break;
    // Live key-up particles are emitted from semantic effects. Continuous particles are opt-in so
    // an active system does not become a noisy or expensive stream of perpetual animation.
    const requested = Math.max(0, Math.min(3, Math.floor(finite(link.particleCount, 0))));
    if (!requested) continue;
    const count = Math.min(requested, remaining);
    allocation.set(linkKey(link, index), count);
    remaining -= count;
  }
  return allocation;
}

function nodeVisualState(node, effect = null) {
  if (node?.active || effect?.type === 'tx_pulse') return 'active';
  if (effect?.type === 'affiliation_arrival' || effect?.type === 'migration' ||
      effect?.type === 'destination_highlight') return 'arrival';
  if (effect?.type === 'afterglow' || node?.afterglow) return 'afterglow';
  if (node?.quiet || node?.stale) return 'quiet';
  return nodeType(node);
}

function linkVisualState(link) {
  const type = normalizedLinkType(link);
  if (link?.active) return 'active';
  if (link?.afterglow) return 'afterglow';
  if (link?.faded) return 'faded';
  if (type === 'migration') return 'migration';
  if (type === 'tx') return 'activity';
  return type;
}

function linkIsDashed(link) {
  const type = normalizedLinkType(link);
  return link?.dashed === true || type === 'activity' || type === 'tx' && link?.affiliation !== true;
}

function nodeRadius(node) {
  const specified = finite(node?.radius ?? node?.renderRadius, NaN);
  if (Number.isFinite(specified) && specified > 0) return clamp(specified, 0.8, 32);
  return ({ universe: 32, group: 13, aggregate: 8, radio: 5.25 })[nodeType(node)];
}

function linkWidth(link) {
  const state = linkVisualState(link);
  if (state === 'active') return 5.2;
  if (state === 'afterglow') return 3.4;
  if (state === 'migration') return 3;
  if (state === 'faded') return 1.75;
  return 2.8;
}

function labelPriority(node, explicitRank = 0) {
  let priority = finite(node?.labelPriority);
  if (node?.selected) priority += 20_000;
  if (node?.active) priority += 10_000;
  priority += ({ universe: 5_000, group: 3_000, aggregate: 2_000, radio: 1_000 })[nodeType(node)];
  return priority + explicitRank;
}

function labelZoomTier(cameraDistance, sceneSpan, focused = false) {
  if (focused) return 'close';
  const ratio = Math.max(0, finite(cameraDistance)) / Math.max(24, finite(sceneSpan, 24));
  if (ratio >= OVERVIEW_DISTANCE_RATIO) return 'overview';
  if (ratio >= MID_DISTANCE_RATIO) return 'mid';
  return 'close';
}

function labelEligible(node, tier) {
  if (node?.selected || node?.active) return true;
  const kind = nodeType(node);
  if (kind === 'universe' || kind === 'aggregate') return true;
  if (tier === 'overview') return false;
  if (kind === 'group') return true;
  return tier === 'close';
}

function resolvedPalette(host, overrides = {}) {
  const root = host?.ownerDocument?.documentElement;
  const styles = root && typeof getComputedStyle === 'function' ? getComputedStyle(root) : null;
  const css = (preferred, fallbackName, fallback) => {
    const preferredValue = styles?.getPropertyValue(preferred)?.trim();
    if (preferredValue) return preferredValue;
    const fallbackValue = styles?.getPropertyValue(fallbackName)?.trim();
    return fallbackValue || fallback;
  };
  return {
    background: overrides.background || css('--network-space', '--bg', '#090e13'),
    universe: overrides.universe || css('--network-system', '--accent', '#44b8aa'),
    group: overrides.group || css('--network-hub', '--link', '#75b9ff'),
    radio: overrides.radio || css('--network-radio', '--warning', '#e6a64c'),
    aggregate: overrides.aggregate || css('--network-space-muted', '--muted', '#9eabb6'),
    arrival: overrides.arrival || css('--network-arrival', '--warning', '#e6a64c'),
    pending: overrides.pending || css('--network-arrival', '--warning', '#e6a64c'),
    active: overrides.active || css('--network-tx', '--success', '#69d59f'),
    afterglow: overrides.afterglow || css('--network-tx', '--success', '#69d59f'),
    quiet: overrides.quiet || css('--network-space-muted', '--muted', '#9eabb6'),
    affiliation: overrides.affiliation || css('--network-affiliation', '--warning', '#e6a64c'),
    activity: overrides.activity || css('--network-activity', '--muted', '#9eabb6'),
    migration: overrides.migration || css('--network-arrival', '--warning', '#e6a64c'),
    faded: overrides.faded || css('--network-space-muted', '--line', '#52616e'),
    selected: overrides.selected || css('--network-selected', '--ink', '#ffffff'),
    encrypted: overrides.encrypted || css('--network-encrypted', '--warning', '#e6a64c')
  };
}

function createElement(documentValue, tag, className, text = '') {
  const element = documentValue.createElement(tag);
  if (className) element.className = className;
  if (text) element.textContent = text;
  return element;
}

function createUnavailableRenderer(host, surface, labelLayer, status, error, callbacks = {}, signal = null) {
  host.dataset.rendererState = 'unavailable';
  status.hidden = false;
  status.textContent = 'Network visualization is unavailable in this browser. Live activity continues in the inspector.';
  status.setAttribute('role', 'status');
  callbacks.onUnavailable?.(error);
  let disposed = false;
  const emptyDiagnostics = () => Object.freeze({
    available: false, disposed, layoutOwner: 'external', mode: '3d', frozen: false,
    nodes: 0, links: 0, labels: 0, expandedFields: 0, steadyParticles: 0, pendingParticles: 0,
    animatedEffects: 0, contextLost: false, paused: false, rendererMemory: null
  });
  const dispose = () => {
    if (disposed) return;
    disposed = true;
    signal?.removeEventListener?.('abort', dispose);
    surface.remove();
    labelLayer.remove();
    status.remove();
    delete host.dataset.rendererState;
  };
  const renderer = Object.freeze({
    available: false,
    layoutOwner: 'external',
    setGraphData: () => emptyDiagnostics(),
    refresh: () => {}, resize: () => {}, setMode: () => '3d', setFrozen: (value) => Boolean(value),
    fitAll: () => false, focus: () => false, setPinned: () => false, pulse: () => false,
    frameScope: () => false, getCameraPose: () => null, restoreCameraPose: () => false,
    setNavigationScope: (value = {}) => value, setAutoRotate: (value) => Boolean(value),
    setArrange: (value) => Boolean(value),
    setPalette: () => {}, setReducedMotion: (value) => Boolean(value),
    setLabelBudget: () => 0, setParticleBudget: () => 0,
    clearEffects: () => {}, clear: () => {},
    enterFullscreen: async () => false, exitFullscreen: async () => false,
    diagnostics: emptyDiagnostics,
    dispose
  });
  if (signal?.aborted) dispose();
  else signal?.addEventListener?.('abort', dispose, { once: true });
  return renderer;
}

/**
 * Creates one WebGL renderer whose positions are owned by the visualizer layout module.
 * 3D and Flatten reuse the same graph, scene objects, semantic data, and camera controls.
 */
async function createNetworkVisualizerRenderer(options = {}) {
  const host = options.host;
  if (!(host instanceof Element)) throw new TypeError('A Network Visualizer renderer host is required.');
  const documentValue = host.ownerDocument;
  const surface = createElement(documentValue, 'div', 'network-visualizer-graph-surface');
  const labelLayer = createElement(documentValue, 'div', 'network-visualizer-label-layer');
  labelLayer.setAttribute('aria-hidden', 'true');
  const status = createElement(documentValue, 'div', 'network-visualizer-renderer-status');
  status.hidden = true;
  host.append(surface, labelLayer, status);

  let library;
  try {
    library = options.library || await (options.vendorLoader || loadVendor)();
  } catch (error) {
    return createUnavailableRenderer(host, surface, labelLayer, status, error, options.callbacks, options.signal);
  }

  const callbacks = options.callbacks || {};
  const config = options.config || BALANCED_CONFIG;
  const animation = { ...DEFAULT_ANIMATION, ...(config.animation || {}) };
  const clock = typeof options.clock === 'function' ? options.clock : Date.now;
  const configuredMaximumNodes = Math.max(1, Math.floor(finite(config.render?.hardNodes,
    BALANCED_CONFIG.render.hardNodes)));
  const configuredMaximumLinks = Math.max(0, Math.floor(finite(config.render?.hardLinks,
    BALANCED_CONFIG.render.hardLinks)));
  const configuredMaximumLabels = Math.max(1, Math.floor(finite(config.render?.hardLabels,
    BALANCED_CONFIG.render.hardLabels)));
  const configuredMaximumParticles = Math.max(0, Math.floor(finite(config.render?.hardParticles,
    BALANCED_CONFIG.render.hardParticles)));
  const maximumNodes = Math.min(configuredMaximumNodes,
    Math.max(1, Math.floor(finite(options.maximumNodes, configuredMaximumNodes))));
  const maximumLinks = Math.min(configuredMaximumLinks,
    Math.max(0, Math.floor(finite(options.maximumLinks, configuredMaximumLinks))));
  let labelBudget = Math.min(maximumNodes, configuredMaximumLabels,
    Math.max(1, Math.floor(finite(options.labelBudget, configuredMaximumLabels))));
  let particleBudget = Math.min(configuredMaximumParticles,
    Math.max(0, Math.floor(finite(options.particleBudget, configuredMaximumParticles))));
  const effectLimit = Math.max(0, Math.floor(finite(config.state?.hardPendingEffects,
    BALANCED_CONFIG.state.hardPendingEffects)));
  const maximumVelocity = Math.max(1, finite(config.layout?.maximumVelocity,
    BALANCED_CONFIG.layout.maximumVelocity));
  let paletteOverrides = { ...(options.palette || {}) };
  let palette = resolvedPalette(host, paletteOverrides);
  let disposed = false;
  let graph = null;
  const initialMode = options.mode === 'flat' || options.mode === '2d' ? 'flat' : '3d';
  let mode = '3d';
  let frozen = Boolean(options.frozen);
  let paused = false;
  let contextLost = false;
  let cameraInteracted = false;
  let heroFramed = false;
  let bootstrapStartedAt = 0;
  let focusedKey = '';
  let navigationScope = { level: 'overview', universeKey: '', groupKey: '' };
  let lastScopeCenter = null;
  let arrangeMode = Boolean(options.arrange);
  let autoRotateRequested = options.autoRotate ?? animation.autoRotateDefault ?? true;
  let autoRotatePaused = false;
  let programmaticCamera = false;
  let lastOffscreenActivityCount = -1;
  let currentGeneration = options.generation ?? null;
  let currentDocument = { nodes: [], links: [], labels: [], effects: [], rendererLimits: {} };
  let currentStructure = '';
  let requestedLabelKeys = [];
  let particleAllocation = new Map();
  let labelFrame = null;
  let labelTimer = null;
  let lastLabelRenderAt = 0;
  let cameraFrame = null;
  let cameraTweenFrame = null;
  let heroFrameTimer = null;
  let autoRotateTimer = null;
  let cameraTrackUntil = 0;
  let effectFrame = null;
  let resizeObserver = null;
  let resizeFallback = null;
  let mediaQuery = null;
  let reducedMotionOverride = typeof options.reducedMotion === 'boolean' ? options.reducedMotion : null;
  let rendererCanvas = null;
  let themeObserver = null;
  const renderNodes = new Map();
  const renderLinks = new Map();
  const nodeObjects = new Map();
  const linkObjects = new Map();
  const labelElements = new Map();
  const dragging = new Map();
  const steadyParticleCounts = new Map();
  const pulseReservations = new Map();
  const seenEffects = new Map();
  const animatedEffects = new Map();
  const nodeEffects = new Map();
  const effectCooldowns = new Map();
  const labelResidence = new Map();
  const labelHiddenAt = new Map();
  const labelMembership = new Set();
  const labelAnchorIndexes = new Map();
  const geometries = new Map();
  const materials = new Map();
  const ownedNodeMaterials = new Map();
  const ownDisposableResource = (resource) => {
    if (!resource || typeof resource.dispose !== 'function') return resource;
    const dispose = resource.dispose.bind(resource);
    let resourceDisposed = false;
    resource.dispose = () => {
      if (resourceDisposed) return;
      resourceDisposed = true;
      dispose();
    };
    return resource;
  };
  const sharedResourceDisposers = new Map();
  const protectSharedResource = (resource) => {
    if (!resource || sharedResourceDisposers.has(resource)) return resource;
    const dispose = typeof resource.dispose === 'function' ? resource.dispose.bind(resource) : null;
    sharedResourceDisposers.set(resource, dispose);
    // 3d-force-graph recursively disposes removed custom objects. Cached resources are shared by
    // retained objects, so defer their real disposal until this renderer is torn down.
    if (dispose) resource.dispose = () => {};
    return resource;
  };
  const disposeSharedResources = () => {
    for (const [resource, dispose] of sharedResourceDisposers) {
      dispose?.();
      sharedResourceDisposers.delete(resource);
    }
  };
  const sharedParticleGeometry = protectSharedResource(new library.SphereGeometry(0.9, 8, 6));
  const sharedParticleMaterial = protectSharedResource(new library.MeshLambertMaterial({ color: palette.active,
    transparent: true, opacity: 0.9, depthWrite: false }));
  geometries.set('particle:shared', sharedParticleGeometry);
  materials.set('particle:shared', { material: sharedParticleMaterial, state: 'active', category: 'particle' });

  const prefersReducedMotion = () => reducedMotionOverride ?? Boolean(mediaQuery?.matches);

  function materialColor(state) {
    return palette[state] || palette.radio;
  }

  function materialOpacity(state) {
    return ({ quiet: 0.22, faded: 0.12, activity: 0.38, affiliation: 0.34,
      afterglow: 0.52, universe: 0.34, group: 0.58, radio: 0.52, aggregate: 0.52,
      active: 0.86, migration: 0.68, arrival: 0.62 })[state] ?? 0.68;
  }

  function meshMaterial(kind, state) {
    const key = `mesh:${kind}:${state}`;
    if (materials.has(key)) return materials.get(key).material;
    const wireframe = kind.startsWith('universe');
    const material = wireframe ? new library.MeshBasicMaterial({
      color: materialColor(state), transparent: true, opacity: materialOpacity(state), wireframe: true,
      depthWrite: false
    }) : new library.MeshLambertMaterial({
      color: materialColor(state), transparent: true, opacity: materialOpacity(state), depthWrite: false
    });
    protectSharedResource(material);
    materials.set(key, { material, state, category: 'node' });
    return material;
  }

  function specialMaterial(name) {
    const key = `special:${name}`;
    if (materials.has(key)) return materials.get(key).material;
    const field = name.startsWith('system-field');
    const pending = name === 'pending';
    const state = name === 'outline' ? 'selected' : field ? 'universe' : pending ? 'arrival' : 'encrypted';
    const settings = { color: materialColor(state), transparent: true,
      opacity: name === 'outline' ? 0.98 : name === 'system-field-fill' ? 0.075 :
        name === 'system-field' ? 0.34 : name === 'system-field-ring' ? 0.42 : pending ? 0.34 : 0.82,
      wireframe: name === 'encrypted' || name === 'system-field', depthWrite: false };
    if (name === 'outline') settings.side = library.BackSide;
    const material = protectSharedResource(new library.MeshBasicMaterial(settings));
    materials.set(key, { material, state, category: 'special' });
    return material;
  }

  function lineMaterial(state, dashed, encrypted = false) {
    const pattern = encrypted ? 'encrypted' : dashed ? 'dashed' : 'solid';
    const key = `line:${state}:${pattern}`;
    if (materials.has(key)) return materials.get(key).material;
    const settings = { color: materialColor(state), transparent: true, opacity: materialOpacity(state),
      depthWrite: false, linewidth: linkWidth({ type: state, active: state === 'active',
        afterglow: state === 'afterglow', faded: state === 'faded' }),
      worldUnits: false, dashed: pattern !== 'solid', dashScale: 1,
      dashSize: encrypted ? 2.4 : 8, gapSize: encrypted ? 2.4 : 5, alphaToCoverage: true };
    const material = protectSharedResource(new library.LineMaterial(settings));
    material.dashed = pattern !== 'solid';
    material.resolution?.set(Math.max(1, surface.clientWidth || host.clientWidth || 1),
      Math.max(1, surface.clientHeight || host.clientHeight || 1));
    materials.set(key, { material, state, category: 'line' });
    return material;
  }

  function geometry(kind) {
    if (geometries.has(kind)) return geometries.get(kind);
    let value;
    if (kind === 'universe') value = new library.IcosahedronGeometry(1, 2);
    else if (kind === 'universe-field') value = new library.IcosahedronGeometry(1, 3);
    else if (kind === 'universe-field-ring') value = new library.TorusGeometry(1, 0.012, 8, 96);
    else if (kind === 'pending-ring') value = new library.TorusGeometry(1.28, 0.055, 8, 36);
    else if (kind === 'conventional-universe') value = new library.TorusGeometry(1, 0.13, 8, 28);
    else if (kind === 'conventional-group') value = new library.BoxGeometry(1.55, 0.42, 1.55);
    else if (kind === 'group') value = new library.CylinderGeometry(1, 1, 0.34, 32, 1, false);
    else if (kind === 'aggregate') value = new library.BoxGeometry(1.45, 1.45, 1.45);
    else if (kind === 'encrypted') value = new library.TorusGeometry(1.28, 0.11, 6, 18);
    else value = new library.SphereGeometry(1, 18, 14);
    protectSharedResource(value);
    geometries.set(kind, value);
    return value;
  }

  function activeNodeEffect(key) {
    const effect = nodeEffects.get(key);
    const visualEnd = finite(effect?.animationEndsAtMs, finite(effect?.expiresAtMs));
    if (!effect || visualEnd <= clock()) {
      if (effect) nodeEffects.delete(key);
      return null;
    }
    return effect;
  }

  function underlyingNodeState(node, visualState) {
    if (['active', 'afterglow', 'arrival'].includes(visualState)) {
      return node?.quiet || node?.stale ? 'quiet' : nodeType(node);
    }
    return visualState;
  }

  function emphasisForState(state) {
    if (state === 'active') return { state: 'active', intensity: 1 };
    if (state === 'afterglow') return { state: 'active', intensity: 0.34 };
    if (state === 'arrival') return { state: 'arrival', intensity: 0.72 };
    return { state: 'arrival', intensity: 0 };
  }

  function ownedEmphasisMaterial(key, state = 'arrival') {
    let metadata = ownedNodeMaterials.get(key);
    if (!metadata) {
      const material = ownDisposableResource(new library.MeshBasicMaterial({ color: materialColor(state),
        transparent: true, opacity: 0, depthWrite: false }));
      metadata = { material, state, current: 0, target: 0, updatedAt: clock() };
      ownedNodeMaterials.set(key, metadata);
    }
    if (metadata.state !== state) {
      metadata.state = state;
      metadata.material.color?.set(materialColor(state));
    }
    return metadata;
  }

  function setMeshScale(object, x, y = x, z = x) {
    if (!object?.scale) return;
    if (typeof object.scale.set === 'function') object.scale.set(x, y, z);
    else {
      object.scale.x = x;
      object.scale.y = y;
      object.scale.z = z;
      object.scale.value = Math.max(x, y, z);
    }
  }

  function applyNodeVisual(node, object = nodeObjects.get(nodeKey(node))) {
    if (!object) return;
    const kind = nodeGeometryKind(node);
    const state = nodeVisualState(node, activeNodeEffect(nodeKey(node)));
    const base = object.userData.visualizerBase;
    const emphasisMesh = object.userData.visualizerEmphasis;
    const outline = object.userData.visualizerOutline;
    const encrypted = object.userData.visualizerEncrypted;
    const pendingRing = object.userData.visualizerPending;
    const fieldBase = object.userData.visualizerFieldBase;
    const fieldFill = object.userData.visualizerFieldFill;
    const fieldEmphasis = object.userData.visualizerFieldEmphasis;
    const fieldOutline = object.userData.visualizerFieldOutline;
    const fieldRings = object.userData.visualizerFieldRings || [];
    const radius = nodeRadius(node);
    const encryptedVisible = Boolean(node.encrypted || node.entity?.encrypted);
    const expandedField = kind === 'universe' && node.expandedField === true;
    const signature = `${kind}:${state}:${Boolean(node.selected)}:${Boolean(node.pending)}:${encryptedVisible}:` +
      `${expandedField}:${node.visible !== false}:${radius}:${mode}`;
    if (object.userData.visualizerStyleSignature === signature) return;
    object.userData.visualizerStyleSignature = signature;
    if (base.geometry !== geometry(kind)) base.geometry = geometry(kind);
    base.material = meshMaterial(kind, underlyingNodeState(node, state));
    outline.geometry = geometry(kind);
    outline.material = specialMaterial('outline');
    outline.visible = Boolean(node.selected) && !expandedField;
    encrypted.material = specialMaterial('encrypted');
    encrypted.visible = encryptedVisible && !expandedField;
    pendingRing.material = specialMaterial('pending');
    pendingRing.visible = Boolean(node.pending) && !node.active && !expandedField;
    const emphasis = emphasisForState(state);
    const existingEmphasis = ownedNodeMaterials.get(nodeKey(node));
    const emphasisMetadata = ownedEmphasisMaterial(nodeKey(node),
      emphasis.intensity > 0 ? emphasis.state : (existingEmphasis?.state || emphasis.state));
    emphasisMetadata.target = emphasis.intensity;
    emphasisMetadata.updatedAt ||= clock();
    emphasisMesh.material = emphasisMetadata.material;
    emphasisMesh.visible = !expandedField && (emphasisMetadata.current > 0.005 || emphasisMetadata.target > 0.005);
    base.visible = !expandedField;
    if (kind === 'universe') {
      const compactX = radius * 1.22;
      const compactY = radius * 0.82;
      const compactZ = radius * 0.82;
      setMeshScale(base, compactX, compactY, compactZ);
      setMeshScale(emphasisMesh, compactX * 1.08, compactY * 1.08, compactZ * 1.08);
      setMeshScale(outline, compactX * 1.14, compactY * 1.14, compactZ * 1.14);
      setMeshScale(pendingRing, radius, radius, radius);
      object.scale.setScalar(1);
      fieldBase.visible = expandedField;
      fieldFill.visible = expandedField;
      fieldEmphasis.material = emphasisMetadata.material;
      fieldEmphasis.visible = expandedField &&
        (emphasisMetadata.current > 0.005 || emphasisMetadata.target > 0.005);
      fieldOutline.visible = expandedField && Boolean(node.selected);
      fieldRings.forEach((ring, index) => { ring.visible = expandedField && (mode !== 'flat' || index === 0); });
    }
    object.userData.visualizerBaseRadius = radius;
    if (kind !== 'universe' && !object.userData.visualizerAnimating) object.scale.setScalar(radius);
    object.visible = node.visible !== false;
  }

  function createNodeObject(node) {
    const key = nodeKey(node);
    const object = new library.Group();
    const kind = nodeGeometryKind(node);
    const base = new library.Mesh(geometry(kind), meshMaterial(kind, nodeVisualState(node)));
    const emphasis = new library.Mesh(geometry(kind), ownedEmphasisMaterial(key).material);
    emphasis.scale.setScalar(1.08);
    emphasis.visible = false;
    const outline = new library.Mesh(geometry(kind), specialMaterial('outline'));
    outline.scale.setScalar(1.18);
    outline.visible = false;
    const encrypted = new library.Mesh(geometry('encrypted'), specialMaterial('encrypted'));
    encrypted.scale.setScalar(0.92);
    encrypted.visible = false;
    const pending = new library.Mesh(geometry('pending-ring'), specialMaterial('pending'));
    pending.visible = false;
    object.add(base, emphasis, outline, encrypted, pending);
    if (kind === 'universe') {
      const fieldFill = new library.Mesh(geometry('universe-field'), specialMaterial('system-field-fill'));
      const fieldBase = new library.Mesh(geometry('universe-field'), specialMaterial('system-field'));
      const fieldEmphasis = new library.Mesh(geometry('universe-field'), ownedEmphasisMaterial(key).material);
      const fieldOutline = new library.Mesh(geometry('universe-field'), specialMaterial('outline'));
      const fieldRings = [0, 1, 2].map(() =>
        new library.Mesh(geometry('universe-field-ring'), specialMaterial('system-field-ring')));
      fieldRings[1].rotation.x = Math.PI / 2;
      fieldRings[2].rotation.y = Math.PI / 2;
      [fieldFill, fieldBase, fieldEmphasis, fieldOutline, ...fieldRings].forEach((mesh) => {
        mesh.visible = false;
        mesh.raycast = () => {};
      });
      object.add(fieldFill, fieldBase, fieldEmphasis, fieldOutline, ...fieldRings);
      object.userData.visualizerFieldFill = fieldFill;
      object.userData.visualizerFieldBase = fieldBase;
      object.userData.visualizerFieldEmphasis = fieldEmphasis;
      object.userData.visualizerFieldOutline = fieldOutline;
      object.userData.visualizerFieldRings = fieldRings;
      object.userData.visualizerFieldScale = { x: 0, y: 0, z: 0, updatedAt: clock() };
    }
    object.userData.visualizerKey = key;
    object.userData.visualizerBase = base;
    object.userData.visualizerEmphasis = emphasis;
    object.userData.visualizerOutline = outline;
    object.userData.visualizerEncrypted = encrypted;
    object.userData.visualizerPending = pending;
    nodeObjects.set(key, object);
    applyNodeVisual(node, object);
    return object;
  }

  const nodeObjectAccessor = (node) => {
    return nodeObjects.get(nodeKey(node)) || createNodeObject(node);
  };

  function createLinkObject(link) {
    const key = linkKey(link);
    const positions = new Float32Array((LINK_SEGMENTS + 1) * 3);
    const linkGeometry = ownDisposableResource(new library.LineGeometry());
    linkGeometry.setPositions(positions);
    const line = new library.Line2(linkGeometry,
      lineMaterial(linkVisualState(link), linkIsDashed(link), Boolean(link.encrypted)));
    line.computeLineDistances?.();
    const segmentBuffer = linkGeometry.attributes?.instanceStart?.data || null;
    const distanceBuffer = linkGeometry.attributes?.instanceDistanceStart?.data || null;
    segmentBuffer?.setUsage?.(library.DynamicDrawUsage);
    distanceBuffer?.setUsage?.(library.DynamicDrawUsage);
    line.userData.visualizerKey = key;
    line.userData.visualizerPositions = positions;
    line.userData.visualizerPath = new Float64Array(7);
    line.userData.visualizerPath.fill(Number.NaN);
    line.userData.visualizerSegmentBuffer = segmentBuffer;
    line.userData.visualizerDistanceBuffer = distanceBuffer;
    linkObjects.set(key, line);
    return line;
  }

  const linkObjectAccessor = (link) => {
    return linkObjects.get(linkKey(link)) || createLinkObject(link);
  };

  function applyLinkVisual(link, object = linkObjects.get(linkKey(link))) {
    if (!object) return;
    const state = linkVisualState(link);
    const dashed = linkIsDashed(link);
    const encrypted = Boolean(link.encrypted);
    const visible = link.visible !== false;
    const signature = `${state}:${dashed}:${encrypted}:${visible}`;
    if (object.userData.visualizerStyleSignature === signature) return;
    object.userData.visualizerStyleSignature = signature;
    object.material = lineMaterial(state, dashed, encrypted);
    object.visible = visible;
  }

  function updateLinkPosition(object, start, end, link) {
    if (!object?.geometry?.setPositions) return false;
    const array = object.userData.visualizerPositions;
    const startX = finite(start?.x), startY = finite(start?.y), startZ = mode === 'flat' ? 0 : finite(start?.z);
    const endX = finite(end?.x), endY = finite(end?.y), endZ = mode === 'flat' ? 0 : finite(end?.z);
    const deltaX = endX - startX, deltaY = endY - startY, deltaZ = endZ - startZ;
    const length = Math.hypot(deltaX, deltaY, deltaZ);
    const curvature = finite(link?.curvature,
      normalizedLinkType(link) === 'migration' ? (link?.curveDirection === -1 ? -0.18 : 0.18) : 0);
    const path = object.userData.visualizerPath;
    if (path && path[0] === startX && path[1] === startY && path[2] === startZ &&
        path[3] === endX && path[4] === endY && path[5] === endZ && path[6] === curvature) return true;
    path[0] = startX;
    path[1] = startY;
    path[2] = startZ;
    path[3] = endX;
    path[4] = endY;
    path[5] = endZ;
    path[6] = curvature;
    let perpendicularX = deltaY, perpendicularY = -deltaX, perpendicularZ = 0;
    if (Math.hypot(perpendicularX, perpendicularY) < 0.001) {
      perpendicularX = -deltaZ;
      perpendicularY = 0;
      perpendicularZ = deltaX;
    }
    const perpendicularLength = Math.hypot(perpendicularX, perpendicularY, perpendicularZ) || 1;
    const offset = length * curvature;
    const controlX = (startX + endX) / 2 + perpendicularX / perpendicularLength * offset;
    const controlY = (startY + endY) / 2 + perpendicularY / perpendicularLength * offset;
    const controlZ = (startZ + endZ) / 2 + perpendicularZ / perpendicularLength * offset;
    for (let segment = 0; segment <= LINK_SEGMENTS; segment += 1) {
      const progress = segment / LINK_SEGMENTS;
      const inverse = 1 - progress;
      const index = segment * 3;
      array[index] = inverse * inverse * startX + 2 * inverse * progress * controlX + progress * progress * endX;
      array[index + 1] = inverse * inverse * startY + 2 * inverse * progress * controlY + progress * progress * endY;
      array[index + 2] = inverse * inverse * startZ + 2 * inverse * progress * controlZ + progress * progress * endZ;
    }
    const segmentBuffer = object.userData.visualizerSegmentBuffer;
    const segmentArray = segmentBuffer?.array;
    if (segmentArray && segmentArray.length >= LINK_SEGMENTS * 6) {
      const distanceBuffer = object.userData.visualizerDistanceBuffer;
      const distanceArray = distanceBuffer?.array;
      let cumulativeDistance = 0;
      for (let segment = 0; segment < LINK_SEGMENTS; segment += 1) {
        const pointIndex = segment * 3;
        const nextPointIndex = pointIndex + 3;
        const segmentIndex = segment * 6;
        segmentArray[segmentIndex] = array[pointIndex];
        segmentArray[segmentIndex + 1] = array[pointIndex + 1];
        segmentArray[segmentIndex + 2] = array[pointIndex + 2];
        segmentArray[segmentIndex + 3] = array[nextPointIndex];
        segmentArray[segmentIndex + 4] = array[nextPointIndex + 1];
        segmentArray[segmentIndex + 5] = array[nextPointIndex + 2];
        if (distanceArray && distanceArray.length >= LINK_SEGMENTS * 2) {
          const distanceIndex = segment * 2;
          distanceArray[distanceIndex] = cumulativeDistance;
          cumulativeDistance += Math.hypot(array[nextPointIndex] - array[pointIndex],
            array[nextPointIndex + 1] - array[pointIndex + 1],
            array[nextPointIndex + 2] - array[pointIndex + 2]);
          distanceArray[distanceIndex + 1] = cumulativeDistance;
        }
      }
      segmentBuffer.needsUpdate = true;
      if (distanceBuffer) distanceBuffer.needsUpdate = true;
    } else {
      // Retain compatibility with small test doubles and older compatible LineGeometry builds.
      object.geometry.setPositions(array);
      if (object.material?.dashed) object.computeLineDistances?.();
    }
    object.geometry.computeBoundingBox?.();
    object.geometry.computeBoundingSphere?.();
    return true;
  }

  function renderedPosition(node) {
    return { x: finite(node.x), y: finite(node.y), z: mode === 'flat' ? 0 : finite(node.z) };
  }

  function updateUniverseFields(atMs = performance.now()) {
    for (const universe of renderNodes.values()) {
      if (nodeType(universe) !== 'universe') continue;
      const object = nodeObjects.get(nodeKey(universe));
      const state = object?.userData?.visualizerFieldScale;
      if (!object || !state || universe.expandedField !== true) continue;
      const origin = renderedPosition(universe);
      const members = [...renderNodes.values()].filter((node) => node.visible !== false &&
        node.universeKey === universe.key && ['group', 'aggregate'].includes(nodeType(node)));
      let targetX = 138;
      let targetY = 106;
      let targetZ = mode === 'flat' ? 2.5 : 88;
      members.forEach((node) => {
        const position = renderedPosition(node);
        const padding = nodeRadius(node) + 42;
        targetX = Math.max(targetX, Math.abs(position.x - origin.x) + padding);
        targetY = Math.max(targetY, Math.abs(position.y - origin.y) + padding);
        if (mode !== 'flat') targetZ = Math.max(targetZ, Math.abs(position.z - origin.z) + padding);
      });
      targetX = clamp(targetX, 90, 620);
      targetY = clamp(targetY, 78, 520);
      targetZ = mode === 'flat' ? 2.5 : clamp(targetZ, 70, 460);
      if (!state.x || prefersReducedMotion()) {
        state.x = targetX;
        state.y = targetY;
        state.z = targetZ;
      } else {
        const elapsed = clamp(atMs - finite(state.updatedAt, atMs), 0, 80);
        const expanding = targetX > state.x || targetY > state.y || targetZ > state.z;
        const duration = expanding ? 360 : 1_600;
        const response = 1 - Math.exp(-elapsed / duration);
        state.x += (targetX - state.x) * response;
        state.y += (targetY - state.y) * response;
        state.z += (targetZ - state.z) * response;
      }
      state.updatedAt = atMs;
      setMeshScale(object.userData.visualizerFieldFill, state.x * 0.985, state.y * 0.985, state.z * 0.985);
      setMeshScale(object.userData.visualizerFieldBase, state.x, state.y, state.z);
      setMeshScale(object.userData.visualizerFieldEmphasis, state.x * 1.018, state.y * 1.018, state.z * 1.018);
      setMeshScale(object.userData.visualizerFieldOutline, state.x * 1.035, state.y * 1.035, state.z * 1.035);
      const rings = object.userData.visualizerFieldRings || [];
      setMeshScale(rings[0], state.x, state.y, Math.max(2.5, state.z));
      setMeshScale(rings[1], state.x, state.z, Math.max(2.5, state.y));
      setMeshScale(rings[2], state.z, state.y, Math.max(2.5, state.x));
    }
  }

  function synchronizeVisualPositions() {
    for (const node of renderNodes.values()) {
      if (dragging.has(nodeKey(node))) continue;
      const object = nodeObjects.get(nodeKey(node));
      if (!object) continue;
      const position = renderedPosition(node);
      object.position.set(position.x, position.y, position.z);
      node.x = position.x;
      node.y = position.y;
      node.z = position.z;
      const velocity = Math.hypot(finite(node.vx), finite(node.vy), finite(node.vz));
      if (velocity > maximumVelocity) {
        const ratio = maximumVelocity / velocity;
        node.vx = finite(node.vx) * ratio;
        node.vy = finite(node.vy) * ratio;
        node.vz = finite(node.vz) * ratio;
      }
    }
    updateUniverseFields();
    for (const link of renderLinks.values()) {
      const object = linkObjects.get(linkKey(link));
      const source = renderNodes.get(link.__sourceKey);
      const target = renderNodes.get(link.__targetKey);
      if (!object || !source || !target) continue;
      updateLinkPosition(object, renderedPosition(source), renderedPosition(target), link);
    }
    if (controls && !programmaticCamera && navigationScope.level !== 'overview') {
      const center = scopeCenter();
      if (lastScopeCenter) {
        const dx = center.x - lastScopeCenter.x;
        const dy = center.y - lastScopeCenter.y;
        const dz = center.z - lastScopeCenter.z;
        if (Math.hypot(dx, dy, dz) > 0.001) {
          controls.target.set(controls.target.x + dx, controls.target.y + dy, controls.target.z + dz);
          const camera = graph?.camera();
          camera?.position?.set(camera.position.x + dx, camera.position.y + dy, camera.position.z + dz);
        }
      }
      lastScopeCenter = center;
    }
  }

  function synchronizeFromLiveSources() {
    const nodeFields = ['x', 'y', 'z', 'vx', 'vy', 'vz', 'fx', 'fy', 'fz', 'pinned', 'selected', 'active',
      'quiet', 'stale', 'afterglow', 'pending', 'encrypted', 'visible', 'renderRadius', 'labelPriority',
      'expandedField', 'labelVisible', 'scopeLevel'];
    for (const node of renderNodes.values()) {
      if (dragging.has(nodeKey(node))) continue;
      const source = node.__source;
      if (!source || source === node) continue;
      for (const field of nodeFields) {
        if (Object.hasOwn(source, field)) node[field] = source[field];
        else if (field === 'fx' || field === 'fy' || field === 'fz') delete node[field];
      }
    }
    const linkFields = ['active', 'afterglow', 'faded', 'dashed', 'encrypted', 'visible', 'curvature', 'curveDirection',
      'particleCount'];
    for (const link of renderLinks.values()) {
      const source = link.__source;
      if (!source || source === link) continue;
      for (const field of linkFields) {
        if (Object.hasOwn(source, field)) link[field] = source[field];
      }
    }
  }

  function reconcileNodes(nodes) {
    const next = new Set();
    const result = [];
    for (const input of nodes) {
      const key = nodeKey(input);
      if (!key || next.has(key)) continue;
      next.add(key);
      let rendered = renderNodes.get(key);
      if (!rendered) {
        rendered = { ...input, id: key, key };
        renderNodes.set(key, rendered);
      } else {
        const runtime = { x: rendered.x, y: rendered.y, z: rendered.z,
          vx: rendered.vx, vy: rendered.vy, vz: rendered.vz,
          fx: rendered.fx, fy: rendered.fy, fz: rendered.fz };
        Object.assign(rendered, input, { id: key, key });
        for (const [name, value] of Object.entries(runtime)) {
          if (input[name] === undefined && value !== undefined) rendered[name] = value;
        }
      }
      rendered.__source = input;
      result.push(rendered);
    }
    for (const key of [...renderNodes.keys()]) {
      if (next.has(key)) continue;
      renderNodes.delete(key);
      nodeObjects.delete(key);
      dragging.delete(key);
      nodeEffects.delete(key);
      labelResidence.delete(key);
      labelHiddenAt.delete(key);
      labelMembership.delete(key);
      labelAnchorIndexes.delete(key);
      const label = labelElements.get(key);
      label?.remove();
      labelElements.delete(key);
      ownedNodeMaterials.get(key)?.material?.dispose?.();
      ownedNodeMaterials.delete(key);
    }
    return result;
  }

  function reconcileLinks(links) {
    const next = new Set();
    const result = [];
    links.forEach((input, index) => {
      const source = endpointKey(input.source);
      const target = endpointKey(input.target);
      const key = linkKey(input, `${source}>${target}:${normalizedLinkType(input)}:${index}`);
      if (!key || next.has(key)) return;
      next.add(key);
      let rendered = renderLinks.get(key);
      if (!rendered) {
        rendered = { ...input, id: key, key };
        renderLinks.set(key, rendered);
      } else {
        const previousSource = rendered.source;
        const previousTarget = rendered.target;
        const sameEndpoints = rendered.__sourceKey === source && rendered.__targetKey === target;
        Object.assign(rendered, input, { id: key, key });
        rendered.source = sameEndpoints && typeof previousSource === 'object' ? previousSource : source;
        rendered.target = sameEndpoints && typeof previousTarget === 'object' ? previousTarget : target;
      }
      if (!rendered.__sourceKey) rendered.source = source;
      if (!rendered.__targetKey) rendered.target = target;
      rendered.__sourceKey = source;
      rendered.__targetKey = target;
      rendered.__source = input;
      result.push(rendered);
    });
    const removed = [];
    for (const key of [...renderLinks.keys()]) {
      if (next.has(key)) continue;
      const removedLink = renderLinks.get(key);
      drainPulseLink(removedLink);
      releasePulseReservations(removedLink);
      renderLinks.delete(key);
      steadyParticleCounts.delete(key);
      const object = linkObjects.get(key);
      if (object) removed.push(object);
      linkObjects.delete(key);
    }
    if (removed.length) queueMicrotask(() => removed.forEach((object) => object.geometry?.dispose()));
    return result;
  }

  function structureSignature(nodes, links) {
    return `${nodes.map(nodeKey).join('\u001f')}\u001e${links.map((link) =>
      `${linkKey(link)}:${link.__sourceKey}>${link.__targetKey}`).join('\u001f')}`;
  }

  function updateParticleAllocation() {
    const next = prefersReducedMotion() ? new Map() : allocateParticles([...renderLinks.values()], particleBudget);
    const changed = next.size !== particleAllocation.size || [...next].some(([key, count]) =>
      particleAllocation.get(key) !== count);
    particleAllocation = next;
    steadyParticleCounts.clear();
    particleAllocation.forEach((count, key) => steadyParticleCounts.set(key, count));
    return changed;
  }

  function expireSeenEffects() {
    const maximum = Math.max(32, effectLimit * EFFECT_SEEN_MULTIPLIER);
    while (seenEffects.size > maximum) seenEffects.delete(seenEffects.keys().next().value);
  }

  function effectNodeKey(effect) {
    return String(effect?.nodeKey || effect?.targetKey || effect?.sourceKey || '');
  }

  function clearEffectAnimationFrame() {
    if (effectFrame !== null) cancelAnimationFrame(effectFrame);
    effectFrame = null;
  }

  function updateNodeEmphasis(now, immediate = false) {
    let continuing = false;
    for (const [key, metadata] of ownedNodeMaterials) {
      const object = nodeObjects.get(key);
      const mesh = object?.userData?.visualizerEmphasis;
      if (!object || !mesh) continue;
      const elapsed = Math.max(0, Math.min(64, now - finite(metadata.updatedAt, now)));
      metadata.updatedAt = now;
      const rising = metadata.target > metadata.current;
      const duration = metadata.state === 'active' ?
        (rising ? animation.txAttackMs : animation.txReleaseMs) : animation.pulseDurationMs;
      if (immediate || prefersReducedMotion() || duration <= 0) metadata.current = metadata.target;
      else {
        const response = 1 - Math.exp(-elapsed / Math.max(16, duration * 0.22));
        metadata.current += (metadata.target - metadata.current) * response;
        if (Math.abs(metadata.target - metadata.current) < 0.004) metadata.current = metadata.target;
      }
      metadata.material.opacity = clamp(metadata.current * materialOpacity(metadata.state), 0, 0.94);
      mesh.visible = metadata.current > 0.005 || metadata.target > 0.005;
      if (metadata.current !== metadata.target) continuing = true;
    }
    return continuing;
  }

  function resetAnimatedNodeVisuals() {
    for (const node of renderNodes.values()) {
      const object = nodeObjects.get(nodeKey(node));
      if (!object || !object.userData.visualizerAnimating) continue;
      object.userData.visualizerAnimating = false;
      object.scale.setScalar(nodeType(node) === 'universe' ? 1 :
        (object.userData.visualizerBaseRadius || nodeRadius(node)));
      applyNodeVisual(node, object);
    }
    updateNodeEmphasis(clock(), true);
  }

  function animateEffects() {
    effectFrame = null;
    if (disposed) return;
    if (documentValue.hidden || prefersReducedMotion()) {
      resetAnimatedNodeVisuals();
      return;
    }
    const now = clock();
    let continuing = updateNodeEmphasis(now);
    for (const [id, effect] of animatedEffects) {
      const node = renderNodes.get(effectNodeKey(effect));
      const object = node && nodeObjects.get(nodeKey(node));
      const start = finite(effect.createdAtMs, now);
      const end = Math.max(start + 1, finite(effect.animationEndsAtMs,
        Math.min(finite(effect.expiresAtMs, start + animation.pulseDurationMs),
          start + animation.pulseDurationMs)));
      if (!object || nodeType(node) === 'universe' || now >= end) {
        animatedEffects.delete(id);
        if (object) {
          object.userData.visualizerAnimating = false;
          object.scale.setScalar(nodeType(node) === 'universe' ? 1 :
            (object.userData.visualizerBaseRadius || nodeRadius(node)));
          applyNodeVisual(node, object);
        }
        continue;
      }
      continuing = true;
      const progress = clamp((now - start) / (end - start), 0, 1);
      const pulse = 1 + Math.sin(progress * Math.PI) * (1 - progress) * animation.pulseScale;
      object.userData.visualizerAnimating = true;
      object.scale.setScalar((object.userData.visualizerBaseRadius || nodeRadius(node)) * pulse);
    }
    if (continuing) effectFrame = requestAnimationFrame(animateEffects);
  }

  function scheduleEffectAnimation() {
    const transitioning = [...ownedNodeMaterials.values()].some((value) => value.current !== value.target);
    if (effectFrame === null && (animatedEffects.size || transitioning) &&
        !prefersReducedMotion() && !documentValue.hidden) {
      effectFrame = requestAnimationFrame(animateEffects);
    }
  }

  function findEffectLink(effect) {
    if (effect.linkKey && renderLinks.has(String(effect.linkKey))) return renderLinks.get(String(effect.linkKey));
    const source = String(effect.sourceKey || '');
    const target = String(effect.targetKey || '');
    return [...renderLinks.values()].find((link) => (!source || link.__sourceKey === source) &&
      (!target || link.__targetKey === target) && ['tx', 'activity', 'migration'].includes(normalizedLinkType(link))) || null;
  }

  function drainPulseLink(link) {
    const group = link?.__singleHopPhotonsObj;
    if (!group) return;
    while (group.children?.length) {
      const particle = group.children[0];
      if (typeof group.remove === 'function') group.remove(particle);
      else group.children.shift();
    }
    group.parent?.remove?.(group);
    delete link.__singleHopPhotonsObj;
  }

  function releasePulseReservations(link = null) {
    for (const [reservation, reservedLink] of pulseReservations) {
      if (link && reservedLink !== link) continue;
      clearTimeout(reservation.timer);
      pulseReservations.delete(reservation);
    }
  }

  function drainPulseParticles() {
    const links = new Set([...renderLinks.values(), ...pulseReservations.values()]);
    links.forEach(drainPulseLink);
  }

  function pulse(linkOrKey) {
    if (!graph || disposed || documentValue.hidden || prefersReducedMotion()) return false;
    const link = typeof linkOrKey === 'object' ? linkOrKey : renderLinks.get(String(linkOrKey));
    if (!link || !renderLinks.has(linkKey(link))) return false;
    const steady = [...steadyParticleCounts.values()].reduce((total, count) => total + count, 0);
    if (steady + pulseReservations.size >= particleBudget) return false;
    graph.emitParticle(link);
    const group = link.__singleHopPhotonsObj;
    const particle = group?.children?.at?.(-1) || null;
    const reservation = { link, particle, timer: null };
    const releaseWhenRemoved = () => {
      reservation.timer = null;
      if (!pulseReservations.has(reservation)) return;
      const currentGroup = link.__singleHopPhotonsObj;
      if (!particle) return;
      if (!currentGroup?.children?.includes(particle)) {
        pulseReservations.delete(reservation);
        return;
      }
      reservation.timer = setTimeout(releaseWhenRemoved,
        Math.max(120, Math.min(500, animation.particleFlightMs / 4)));
    };
    reservation.timer = setTimeout(releaseWhenRemoved,
      Math.max(120, Math.min(500, animation.particleFlightMs / 4)));
    pulseReservations.set(reservation, link);
    return true;
  }

  function processEffects(effects) {
    const now = clock();
    for (const effect of effects) {
      const id = String(effect?.id || '');
      if (!id || seenEffects.has(id)) continue;
      seenEffects.set(id, now);
      if (finite(effect.expiresAtMs, now + 1) <= now) continue;
      const key = effectNodeKey(effect);
      if (key) {
        const current = nodeEffects.get(key);
        const currentEnd = finite(current?.animationEndsAtMs, finite(current?.expiresAtMs));
        const nextEnd = finite(effect.animationEndsAtMs, finite(effect.expiresAtMs));
        if (!current || currentEnd <= nextEnd) nodeEffects.set(key, effect);
      }
      const visualKey = String(effect.coalesceKey ||
        `${effect.type}:${effectNodeKey(effect)}:${effect.sourceKey || ''}:${effect.targetKey || ''}`);
      const previousAt = finite(effectCooldowns.get(visualKey), -Infinity);
      const coalesced = now - previousAt < animation.effectCoalesceMs;
      effectCooldowns.set(visualKey, now);
      if (!coalesced && ['affiliation_arrival', 'migration', 'destination_highlight', 'tx_pulse'].includes(effect.type) &&
          animatedEffects.size < animation.softAnimatedEffects) animatedEffects.set(id, effect);
      if (!coalesced && ['tx_pulse', 'migration'].includes(effect.type) &&
          !documentValue.hidden && !prefersReducedMotion()) {
        const link = findEffectLink(effect);
        if (link) queueMicrotask(() => { if (!disposed) pulse(link); });
      }
    }
    for (const [key, observedAt] of effectCooldowns) {
      if (now - observedAt > Math.max(5_000, animation.effectCoalesceMs * 4)) effectCooldowns.delete(key);
    }
    const maximumCooldowns = Math.max(32, effectLimit * EFFECT_SEEN_MULTIPLIER);
    while (effectCooldowns.size > maximumCooldowns) effectCooldowns.delete(effectCooldowns.keys().next().value);
    expireSeenEffects();
    for (const node of renderNodes.values()) applyNodeVisual(node);
    scheduleEffectAnimation();
  }

  function labelText(node) {
    const label = String(node?.label || node?.entity?.displayName || node?.entity?.name || nodeKey(node));
    return label;
  }

  function labelCandidateNodes() {
    const requested = requestedLabelKeys.length ? requestedLabelKeys.map((key, index) => ({
      node: renderNodes.get(key), rank: requestedLabelKeys.length - index
    })).filter((entry) => entry.node) : [...renderNodes.values()].map((node) => ({ node, rank: 0 }))
      .sort((left, right) => labelPriority(right.node) - labelPriority(left.node))
      .slice(0, labelBudget * 3);
    if (!labelMembership.size) return requested;
    const requestedByKey = new Map(requested.map((entry) => [nodeKey(entry.node), entry]));
    const existing = [...labelMembership].map((key) => {
      const requestedEntry = requestedByKey.get(key);
      if (requestedEntry) return requestedEntry;
      const node = renderNodes.get(key);
      return node ? { node, rank: 0 } : null;
    }).filter((entry) => entry?.node?.labelVisible !== false);
    requested.filter(({ node }) => node.active || node.selected).forEach((entry) => {
      if (!existing.some(({ node }) => nodeKey(node) === nodeKey(entry.node))) existing.push(entry);
    });
    requested.forEach((entry) => {
      if (!existing.some(({ node }) => nodeKey(node) === nodeKey(entry.node))) existing.push(entry);
    });
    return existing;
  }

  function currentLabelZoomTier() {
    const camera = graph?.camera();
    if (!camera || !renderNodes.size) return 'overview';
    let minimumX = Infinity, minimumY = Infinity, minimumZ = Infinity;
    let maximumX = -Infinity, maximumY = -Infinity, maximumZ = -Infinity;
    for (const node of renderNodes.values()) {
      if (node.visible === false) continue;
      const position = renderedPosition(node);
      minimumX = Math.min(minimumX, position.x);
      minimumY = Math.min(minimumY, position.y);
      minimumZ = Math.min(minimumZ, position.z);
      maximumX = Math.max(maximumX, position.x);
      maximumY = Math.max(maximumY, position.y);
      maximumZ = Math.max(maximumZ, position.z);
    }
    if (!Number.isFinite(minimumX)) return 'overview';
    const span = Math.hypot(maximumX - minimumX, maximumY - minimumY, maximumZ - minimumZ);
    const target = graph.controls()?.target || new library.Vector3();
    const explicitlyFocused = Boolean(focusedKey && renderNodes.get(focusedKey)?.selected);
    const distanceTier = labelZoomTier(camera.position.distanceTo(target), span, explicitlyFocused);
    // Hierarchy navigation establishes the semantic detail floor. Camera distance may reveal more detail,
    // but a fitted system must keep every admitted talkgroup discoverable and a fitted group must expose
    // its budgeted radio labels even when the containing cluster has a wide 3D span.
    if (navigationScope.level === 'group') return 'close';
    if (navigationScope.level === 'system' && distanceTier === 'overview') return 'mid';
    return distanceTier;
  }

  function updateOffscreenActivityCount(width, height) {
    if (typeof callbacks.onOffscreenActivity !== 'function') return;
    const camera = graph?.camera();
    let count = 0;
    if (camera && width && height) {
      const projected = new library.Vector3();
      for (const node of renderNodes.values()) {
        if (!node.active || node.visible === false) continue;
        const position = renderedPosition(node);
        projected.set(position.x, position.y, position.z).project(camera);
        if (!Number.isFinite(projected.x) || !Number.isFinite(projected.y) || !Number.isFinite(projected.z) ||
            projected.z < -1 || projected.z > 1 || projected.x < -1 || projected.x > 1 ||
            projected.y < -1 || projected.y > 1) count += 1;
      }
    }
    if (count === lastOffscreenActivityCount) return;
    lastOffscreenActivityCount = count;
    callbacks.onOffscreenActivity({ count });
  }

  function renderLabels() {
    labelFrame = null;
    if (!graph || disposed || documentValue.hidden || contextLost) return;
    const renderedAt = performance.now();
    lastLabelRenderAt = renderedAt;
    const width = Math.max(0, surface.clientWidth || host.clientWidth);
    const height = Math.max(0, surface.clientHeight || host.clientHeight);
    if (!width || !height) return;
    const tier = currentLabelZoomTier();
    updateOffscreenActivityCount(width, height);
    const candidates = [];
    const previouslyVisible = new Set([...labelElements.entries()]
      .filter(([, element]) => element.dataset.visible === 'true').map(([key]) => key));
    for (const { node, rank } of labelCandidateNodes()) {
      if (!node || node.labelVisible === false || !labelEligible(node, tier)) continue;
      const hiddenAt = finite(labelHiddenAt.get(nodeKey(node)), -Infinity);
      if (!node.active && !node.selected && !labelMembership.has(nodeKey(node)) &&
          renderedAt - hiddenAt < animation.labelHiddenResidenceMs) continue;
      const position = renderedPosition(node);
      let screen;
      try {
        screen = graph.graph2ScreenCoords(position.x, position.y, position.z);
      } catch (_) {
        continue;
      }
      const text = labelText(node);
      const recentlyVisible = renderedAt - finite(labelResidence.get(nodeKey(node)), -Infinity) <=
        animation.labelMinimumResidenceMs;
      const kind = nodeType(node);
      const radius = nodeRadius(node);
      const labelWidth = clamp(text.length * 8.2 + (node.encrypted ? 30 : 18), 64, 300);
      const labelHeight = kind === 'universe' ? 38 : kind === 'group' ? 32 : 28;
      const allowAlternateAnchors = kind === 'universe' || kind === 'group' || node.selected || node.active;
      const rawOffsets = allowAlternateAnchors ? [
        { x: radius + 7, y: 0, index: 0 },
        { x: -radius - 7 - labelWidth, y: 0, index: 1 },
        { x: radius + 7, y: -labelHeight - 8, index: 2 },
        { x: radius + 7, y: labelHeight + 8, index: 3 },
        { x: -radius - 7 - labelWidth, y: -labelHeight - 8, index: 4 },
        { x: -radius - 7 - labelWidth, y: labelHeight + 8, index: 5 }
      ] : [{ x: radius + 7, y: 0, index: 0 }];
      const preferredAnchor = labelAnchorIndexes.get(nodeKey(node));
      const offsets = Number.isSafeInteger(preferredAnchor) ? [
        ...rawOffsets.filter((offset) => offset.index === preferredAnchor),
        ...rawOffsets.filter((offset) => offset.index !== preferredAnchor)
      ] : rawOffsets;
      candidates.push({ key: nodeKey(node), node, text, x: screen.x, y: screen.y,
        offsets, width: labelWidth, height: labelHeight,
        priority: labelPriority(node, rank) + (labelMembership.has(nodeKey(node)) ? 10_000 : 0) +
          (recentlyVisible ? 900 : 0) });
    }
    const placements = chooseLabelPlacements(candidates, { width, height }, labelBudget);
    const visible = new Set();
    for (const placement of placements) {
      let element = labelElements.get(placement.key);
      if (!element) {
        element = createElement(documentValue, 'span', 'network-visualizer-label');
        element.dataset.key = placement.key;
        element.addEventListener('pointerdown', (event) => event.stopPropagation());
        element.addEventListener('wheel', (event) => {
          if (!rendererCanvas || !documentValue.defaultView?.WheelEvent) return;
          event.preventDefault();
          event.stopPropagation();
          rendererCanvas.dispatchEvent(new documentValue.defaultView.WheelEvent('wheel', {
            bubbles: true,
            cancelable: true,
            clientX: event.clientX,
            clientY: event.clientY,
            deltaX: event.deltaX,
            deltaY: event.deltaY,
            deltaZ: event.deltaZ,
            deltaMode: event.deltaMode,
            ctrlKey: event.ctrlKey,
            metaKey: event.metaKey,
            shiftKey: event.shiftKey,
            altKey: event.altKey
          }));
        }, { passive: false });
        element.addEventListener('click', (event) => {
          event.stopPropagation();
          const rendered = renderNodes.get(element.dataset.key);
          if (rendered) callbacks.onNodeClick?.(rendered.__source || rendered, event);
        });
        labelElements.set(placement.key, element);
        labelLayer.append(element);
      }
      visible.add(placement.key);
      labelResidence.set(placement.key, renderedAt);
      labelAnchorIndexes.set(placement.key, placement.offsetIndex);
      element.textContent = `${placement.node.encrypted ? '🔒 ' : ''}${placement.text}`;
      element.dataset.type = nodeType(placement.node);
      element.dataset.active = String(Boolean(placement.node.active));
      element.dataset.pending = String(Boolean(placement.node.pending));
      element.dataset.selected = String(Boolean(placement.node.selected));
      element.dataset.quiet = String(Boolean(placement.node.quiet));
      element.dataset.nodeScreenX = String(placement.x);
      element.dataset.nodeScreenY = String(placement.y);
      element.style.transform = `translate3d(${placement.left}px, ${placement.top}px, 0)`;
      element.hidden = false;
      element.dataset.visible = 'true';
    }
    for (const [key, element] of labelElements) {
      if (visible.has(key)) continue;
      if (previouslyVisible.has(key)) labelHiddenAt.set(key, renderedAt);
      element.dataset.visible = 'false';
    }
    [...labelMembership].forEach((key) => {
      const node = renderNodes.get(key);
      if (!node || node.labelVisible === false) {
        labelMembership.delete(key);
        return;
      }
      if (visible.has(key)) return;
      const hiddenAt = finite(labelHiddenAt.get(key), renderedAt);
      if (renderedAt - hiddenAt >= animation.labelMinimumResidenceMs) labelMembership.delete(key);
    });
    const admit = (key) => {
      if (labelMembership.has(key)) return;
      if (labelMembership.size >= labelBudget) {
        const removable = [...labelMembership].map((candidateKey) => renderNodes.get(candidateKey))
          .filter((candidate) => candidate && !candidate.active && !candidate.selected)
          .sort((left, right) => labelPriority(left) - labelPriority(right) ||
            nodeKey(left).localeCompare(nodeKey(right)))[0];
        if (!removable) return;
        labelMembership.delete(nodeKey(removable));
      }
      labelMembership.add(key);
    };
    [...visible].filter((key) => {
      const node = renderNodes.get(key);
      return node?.active || node?.selected;
    }).forEach(admit);
    [...visible].forEach(admit);
  }

  function scheduleLabels() {
    if (labelFrame !== null || labelTimer !== null || disposed || documentValue.hidden) return;
    const delay = Math.max(0, LABEL_RENDER_INTERVAL_MS - (performance.now() - lastLabelRenderAt));
    if (delay > 1) {
      labelTimer = setTimeout(() => {
        labelTimer = null;
        scheduleLabels();
      }, delay);
      return;
    }
    labelFrame = requestAnimationFrame(renderLabels);
  }

  function trackCamera(duration = 0) {
    cameraTrackUntil = Math.max(cameraTrackUntil, performance.now() + Math.max(0, duration) + 80);
    if (cameraFrame !== null) return;
    const frame = () => {
      cameraFrame = null;
      if (disposed || documentValue.hidden) return;
      scheduleLabels();
      if (performance.now() < cameraTrackUntil) cameraFrame = requestAnimationFrame(frame);
    };
    cameraFrame = requestAnimationFrame(frame);
  }

  function refresh(optionsValue = {}) {
    if (!graph || disposed) return;
    // The external force layout mutates the stable source objects between structural graph updates.
    // Pull those coordinates into the renderer-owned objects so motion remains continuous without
    // asking force-graph to tear down and rebuild the scene on every animation frame.
    synchronizeFromLiveSources();
    for (const node of renderNodes.values()) applyNodeVisual(node);
    for (const link of renderLinks.values()) applyLinkVisual(link);
    synchronizeVisualPositions();
    // The graph owns a continuous RAF. Rebuilding it every external layout tick is expensive and
    // unnecessary because custom objects are updated in place.
    if (optionsValue.rebuild === true) graph.refresh();
    scheduleLabels();
    scheduleEffectAnimation();
  }

  function clearEffects() {
    clearEffectAnimationFrame();
    animatedEffects.clear();
    nodeEffects.clear();
    effectCooldowns.clear();
    seenEffects.clear();
    drainPulseParticles();
    releasePulseReservations();
    for (const node of renderNodes.values()) applyNodeVisual(node);
    resetAnimatedNodeVisuals();
  }

  function scheduleHeroFrame() {
    if (heroFramed || cameraInteracted || navigationScope.level !== 'overview' || !renderNodes.size ||
        disposed || documentValue.hidden) return;
    if (!bootstrapStartedAt) bootstrapStartedAt = performance.now();
    if (heroFrameTimer !== null) clearTimeout(heroFrameTimer);
    const elapsed = performance.now() - bootstrapStartedAt;
    const delay = elapsed >= 2_400 ? 0 : 360;
    heroFrameTimer = setTimeout(() => {
      heroFrameTimer = null;
      if (disposed || cameraInteracted || navigationScope.level !== 'overview' || !renderNodes.size) return;
      heroFramed = true;
      frameScope({ duration: animation.cameraTransitionMs, padding: 1.18, hero: true });
    }, delay);
  }

  function setGraphData(value = {}) {
    if (!graph || disposed) return diagnostics();
    if (value.generation !== undefined && value.generation !== currentGeneration) {
      currentGeneration = value.generation;
      clearEffects();
    }
    const bounded = boundedGraphData(value, { nodes: maximumNodes, links: maximumLinks, effects: effectLimit });
    if (!bounded.nodes.length && !bounded.effects.length) clearEffects();
    const nodes = reconcileNodes(bounded.nodes);
    const links = reconcileLinks(bounded.links);
    currentDocument = { ...bounded, nodes, links };
    requestedLabelKeys = bounded.labels;
    const particlesChanged = updateParticleAllocation();
    const signature = structureSignature(nodes, links);
    const structureChanged = signature !== currentStructure;
    if (structureChanged) {
      currentStructure = signature;
      graph.graphData({ nodes, links });
    }
    processEffects(bounded.effects);
    refresh();
    // The graph digests custom objects asynchronously; apply their final state once that update lands.
    if (structureChanged) queueMicrotask(() => { if (!disposed) refresh(); });
    else if (particlesChanged) graph.refresh();
    if (nodes.length && options.autoFitOnFirstData !== false) scheduleHeroFrame();
    updateAutoRotate();
    if (bounded.rendererLimits.suppressedNodes || bounded.rendererLimits.suppressedLinks ||
        bounded.rendererLimits.suppressedEffects) callbacks.onLimit?.(bounded.rendererLimits);
    return diagnostics();
  }

  function resize() {
    if (!graph || disposed) return;
    const width = Math.max(1, Math.floor(surface.clientWidth || host.clientWidth || 1));
    const height = Math.max(1, Math.floor(surface.clientHeight || host.clientHeight || 1));
    graph.width(width).height(height);
    materials.forEach(({ material, category }) => {
      if (category === 'line') material.resolution?.set(width, height);
    });
    scheduleLabels();
  }

  function cameraDuration(requested = 360) {
    return prefersReducedMotion() ? 0 : Math.max(0, finite(requested, 360));
  }

  function clearAutoRotateTimer() {
    if (autoRotateTimer !== null) clearTimeout(autoRotateTimer);
    autoRotateTimer = null;
  }

  function updateAutoRotate() {
    if (!controls) return false;
    const effective = Boolean(autoRotateRequested && !autoRotatePaused && !programmaticCamera &&
      !arrangeMode && dragging.size === 0 &&
      mode === '3d' && !prefersReducedMotion() && !documentValue.hidden && renderNodes.size &&
      (navigationScope.level !== 'overview' || heroFramed || cameraInteracted));
    controls.autoRotate = effective;
    controls.autoRotateSpeed = animation.autoRotateSpeed;
    callbacks.onAutoRotateState?.({ requested: autoRotateRequested, effective,
      paused: autoRotateRequested && !effective });
    return effective;
  }

  function scheduleAutoRotateResume() {
    clearAutoRotateTimer();
    if (!autoRotateRequested || mode !== '3d' || prefersReducedMotion() || documentValue.hidden ||
        arrangeMode || dragging.size) {
      if (arrangeMode || dragging.size) autoRotatePaused = true;
      updateAutoRotate();
      return;
    }
    autoRotatePaused = true;
    updateAutoRotate();
    autoRotateTimer = setTimeout(() => {
      autoRotateTimer = null;
      resumeAutoRotateAroundScope();
    }, animation.autoRotateIdleDelayMs);
  }

  function resumeAutoRotateAroundScope() {
    if (!autoRotateRequested || mode !== '3d' || prefersReducedMotion() || documentValue.hidden || !controls ||
        !graph || !renderNodes.size || arrangeMode || dragging.size) {
      updateAutoRotate();
      return false;
    }
    const target = scopeCenter();
    const currentTarget = controls.target;
    const delta = Math.hypot(target.x - currentTarget.x, target.y - currentTarget.y, target.z - currentTarget.z);
    if (delta > 0.25) {
      const camera = graph.camera();
      const position = {
        x: target.x + camera.position.x - currentTarget.x,
        y: target.y + camera.position.y - currentTarget.y,
        z: target.z + camera.position.z - currentTarget.z
      };
      return tweenCamera(position, target, Math.min(360, animation.cameraBackTransitionMs),
        { resumeAutoRotateImmediately: true });
    }
    autoRotatePaused = false;
    updateAutoRotate();
    return true;
  }

  function stopCameraTween({ resumeAutoRotate = true } = {}) {
    if (cameraTweenFrame !== null) cancelAnimationFrame(cameraTweenFrame);
    cameraTweenFrame = null;
    programmaticCamera = false;
    if (resumeAutoRotate) scheduleAutoRotateResume();
    else updateAutoRotate();
  }

  function cameraPose() {
    if (!graph || !controls) return null;
    const camera = graph.camera();
    return Object.freeze({
      position: Object.freeze({ x: finite(camera.position.x), y: finite(camera.position.y),
        z: finite(camera.position.z) }),
      target: Object.freeze({ x: finite(controls.target.x), y: finite(controls.target.y),
        z: finite(controls.target.z) })
    });
  }

  function tweenCamera(position, target, requestedDuration = animation.cameraTransitionMs, optionsValue = {}) {
    if (!graph || disposed || !controls) return false;
    stopCameraTween({ resumeAutoRotate: false });
    clearAutoRotateTimer();
    autoRotatePaused = true;
    programmaticCamera = true;
    updateAutoRotate();
    const camera = graph.camera();
    const startedAt = performance.now();
    const duration = cameraDuration(requestedDuration);
    const fromPosition = { x: finite(camera.position.x), y: finite(camera.position.y), z: finite(camera.position.z) };
    const fromTarget = { x: finite(controls.target.x), y: finite(controls.target.y), z: finite(controls.target.z) };
    const toPosition = { x: finite(position?.x), y: finite(position?.y), z: finite(position?.z) };
    const toTarget = { x: finite(target?.x), y: finite(target?.y), z: finite(target?.z) };
    const apply = (progress) => {
      const eased = easeInOutCubic(progress);
      camera.position.set(
        fromPosition.x + (toPosition.x - fromPosition.x) * eased,
        fromPosition.y + (toPosition.y - fromPosition.y) * eased,
        fromPosition.z + (toPosition.z - fromPosition.z) * eased);
      controls.target.set(
        fromTarget.x + (toTarget.x - fromTarget.x) * eased,
        fromTarget.y + (toTarget.y - fromTarget.y) * eased,
        fromTarget.z + (toTarget.z - fromTarget.z) * eased);
      camera.lookAt?.(controls.target);
      controls.update?.();
      scheduleLabels();
    };
    if (!duration) {
      apply(1);
      programmaticCamera = false;
      if (optionsValue.resumeAutoRotateImmediately) {
        autoRotatePaused = false;
        updateAutoRotate();
      } else scheduleAutoRotateResume();
      return true;
    }
    const frame = (at) => {
      cameraTweenFrame = null;
      if (disposed || documentValue.hidden) {
        programmaticCamera = false;
        updateAutoRotate();
        return;
      }
      const progress = clamp((at - startedAt) / duration, 0, 1);
      apply(progress);
      if (progress < 1) cameraTweenFrame = requestAnimationFrame(frame);
      else {
        programmaticCamera = false;
        if (optionsValue.resumeAutoRotateImmediately) {
          autoRotatePaused = false;
          updateAutoRotate();
        } else scheduleAutoRotateResume();
      }
    };
    cameraTweenFrame = requestAnimationFrame(frame);
    return true;
  }

  function scopeCenter() {
    const focalKey = navigationScope.level === 'group' ? navigationScope.groupKey :
      navigationScope.level === 'system' ? navigationScope.universeKey : '';
    const focal = focalKey && renderNodes.get(focalKey);
    if (focal) return renderedPosition(focal);
    const visible = [...renderNodes.values()].filter((node) => node.visible !== false);
    if (!visible.length) return { x: 0, y: 0, z: 0 };
    return visible.reduce((center, node) => {
      const position = renderedPosition(node);
      center.x += position.x / visible.length;
      center.y += position.y / visible.length;
      center.z += position.z / visible.length;
      return center;
    }, { x: 0, y: 0, z: 0 });
  }

  function scopeBounds(nodes = [...renderNodes.values()].filter((node) => node.visible !== false)) {
    if (!nodes.length) return null;
    let minimumX = Infinity, minimumY = Infinity, minimumZ = Infinity;
    let maximumX = -Infinity, maximumY = -Infinity, maximumZ = -Infinity;
    nodes.forEach((node) => {
      const position = renderedPosition(node);
      const radius = nodeRadius(node);
      minimumX = Math.min(minimumX, position.x - radius);
      minimumY = Math.min(minimumY, position.y - radius);
      minimumZ = Math.min(minimumZ, position.z - radius);
      maximumX = Math.max(maximumX, position.x + radius);
      maximumY = Math.max(maximumY, position.y + radius);
      maximumZ = Math.max(maximumZ, position.z + radius);
      const field = nodeType(node) === 'universe' && node.expandedField === true ?
        nodeObjects.get(nodeKey(node))?.userData?.visualizerFieldScale : null;
      if (field?.x) {
        minimumX = Math.min(minimumX, position.x - field.x);
        minimumY = Math.min(minimumY, position.y - field.y);
        minimumZ = Math.min(minimumZ, position.z - field.z);
        maximumX = Math.max(maximumX, position.x + field.x);
        maximumY = Math.max(maximumY, position.y + field.y);
        maximumZ = Math.max(maximumZ, position.z + field.z);
      }
    });
    const center = { x: (minimumX + maximumX) / 2, y: (minimumY + maximumY) / 2,
      z: mode === 'flat' ? 0 : (minimumZ + maximumZ) / 2 };
    const radius = Math.max(1, Math.hypot(maximumX - minimumX, maximumY - minimumY,
      mode === 'flat' ? 0 : maximumZ - minimumZ) / 2);
    return { center, radius };
  }

  function frameScope(optionsValue = {}) {
    if (!graph || disposed || !renderNodes.size) return false;
    const keys = Array.isArray(optionsValue.keys) ? new Set(optionsValue.keys.map(String)) : null;
    if (!keys) focusedKey = '';
    const nodes = [...renderNodes.values()].filter((node) => node.visible !== false && (!keys || keys.has(nodeKey(node))));
    const bounds = scopeBounds(nodes);
    if (!bounds) return false;
    const target = keys ? bounds.center : scopeCenter();
    const targetRadius = nodes.reduce((maximum, node) => {
      const position = renderedPosition(node);
      const field = nodeType(node) === 'universe' && node.expandedField === true ?
        nodeObjects.get(nodeKey(node))?.userData?.visualizerFieldScale : null;
      const extent = field?.x ? Math.max(field.x, field.y, mode === 'flat' ? 0 : field.z) * 1.12 : nodeRadius(node);
      return Math.max(maximum, Math.hypot(position.x - target.x, position.y - target.y,
        mode === 'flat' ? 0 : position.z - target.z) + extent);
    }, 1);
    const camera = graph.camera();
    const aspect = Math.max(0.25, (surface.clientWidth || host.clientWidth || 1) /
      Math.max(1, surface.clientHeight || host.clientHeight || 1));
    const verticalHalfFov = clamp(finite(camera.fov, 60), 25, 100) * Math.PI / 360;
    const horizontalHalfFov = Math.atan(Math.tan(verticalHalfFov) * aspect);
    const limitingHalfFov = Math.max(0.1, Math.min(verticalHalfFov, horizontalHalfFov));
    const padding = clamp(finite(optionsValue.padding, 1.22), 1, 2);
    const scopedMinimum = navigationScope.level === 'overview' ? 150 :
      navigationScope.level === 'system' ? 170 : 115;
    const distance = Math.max(scopedMinimum, targetRadius * padding / Math.sin(limitingHalfFov));
    const currentDirection = new library.Vector3().copy(camera.position).sub(controls.target).normalize();
    if (!Number.isFinite(currentDirection.x) || currentDirection.lengthSq() < 0.001 || optionsValue.hero === true) {
      currentDirection.set(mode === 'flat' ? 0 : 0.72, mode === 'flat' ? 0 : 0.48, 1).normalize();
    }
    const position = mode === 'flat' ? { x: target.x, y: target.y, z: distance } : {
      x: target.x + currentDirection.x * distance,
      y: target.y + currentDirection.y * distance,
      z: target.z + currentDirection.z * distance
    };
    return tweenCamera(position, target, optionsValue.duration ?? animation.cameraTransitionMs);
  }

  function fitAll(duration = animation.cameraTransitionMs, padding = 48) {
    return frameScope({ duration, padding: 1 + Math.max(0, finite(padding, 48)) / 220 });
  }

  function focus(keyOrNode, duration = animation.cameraTransitionMs) {
    if (!graph || disposed) return false;
    const key = typeof keyOrNode === 'object' ? nodeKey(keyOrNode) : String(keyOrNode || '');
    const node = renderNodes.get(key);
    if (!node) return false;
    focusedKey = key;
    return frameScope({ keys: [key], duration, padding: 1.35 });
  }

  function restoreCameraPose(pose, duration = animation.cameraBackTransitionMs) {
    if (!pose?.position || !pose?.target) return false;
    return tweenCamera(pose.position, pose.target, duration);
  }

  function setNavigationScope(value = {}) {
    const level = value.level === 'group' || value.level === 'talkgroup' ? 'group' :
      value.level === 'system' ? 'system' : 'overview';
    const next = { level, universeKey: String(value.universeKey || ''),
      groupKey: String(value.groupKey || '') };
    const changed = next.level !== navigationScope.level || next.universeKey !== navigationScope.universeKey ||
      next.groupKey !== navigationScope.groupKey;
    navigationScope = next;
    if (changed) focusedKey = '';
    if (changed) {
      labelMembership.clear();
      labelHiddenAt.clear();
    }
    lastScopeCenter = null;
    if (changed) {
      clearAutoRotateTimer();
      autoRotatePaused = true;
      updateAutoRotate();
    }
    return { ...navigationScope };
  }

  function setAutoRotate(value) {
    autoRotateRequested = Boolean(value);
    autoRotatePaused = false;
    clearAutoRotateTimer();
    if (autoRotateRequested && cameraInteracted && controls?.target && mode === '3d' && !prefersReducedMotion() &&
        !documentValue.hidden && renderNodes.size) {
      const center = scopeCenter();
      if (Math.hypot(center.x - controls.target.x, center.y - controls.target.y,
        center.z - controls.target.z) > 0.25) {
        autoRotatePaused = true;
        updateAutoRotate();
        resumeAutoRotateAroundScope();
        return autoRotateRequested;
      }
    }
    updateAutoRotate();
    return autoRotateRequested;
  }

  function setArrange(value) {
    arrangeMode = Boolean(value);
    graph?.enableNodeDrag?.(arrangeMode);
    dragging.clear();
    clearAutoRotateTimer();
    if (arrangeMode) {
      autoRotatePaused = true;
      updateAutoRotate();
    } else scheduleAutoRotateResume();
    return arrangeMode;
  }

  function setMode(nextMode, optionsValue = {}) {
    const selected = nextMode === 'flat' || nextMode === '2d' ? 'flat' : '3d';
    if (!graph || disposed || selected === mode) return mode;
    mode = selected;
    graph.numDimensions(3);
    const controls = graph.controls();
    if (controls) {
      controls.enableRotate = mode !== 'flat';
      controls.enablePan = true;
      controls.enableZoom = true;
      controls.screenSpacePanning = true;
      controls.zoomToCursor = true;
      if (library.MOUSE && library.TOUCH && controls.mouseButtons && controls.touches) {
        controls.mouseButtons.LEFT = mode === 'flat' ? library.MOUSE.PAN : library.MOUSE.ROTATE;
        controls.mouseButtons.MIDDLE = library.MOUSE.DOLLY;
        controls.mouseButtons.RIGHT = library.MOUSE.PAN;
        controls.touches.ONE = mode === 'flat' ? library.TOUCH.PAN : library.TOUCH.ROTATE;
        controls.touches.TWO = mode === 'flat' ? library.TOUCH.DOLLY_PAN : library.TOUCH.DOLLY_ROTATE;
      }
    }
    if (mode === 'flat') {
      const target = controls?.target || { x: 0, y: 0, z: 0 };
      const distance = Math.max(MINIMUM_CAMERA_DISTANCE, graph.camera().position.distanceTo(controls?.target ||
        new library.Vector3()));
      graph.camera().up.set(0, 1, 0);
      const milliseconds = cameraDuration(optionsValue.duration ?? 320);
      tweenCamera({ x: target.x, y: target.y, z: distance }, { x: target.x, y: target.y, z: 0 }, milliseconds);
    } else {
      scheduleAutoRotateResume();
    }
    updateAutoRotate();
    synchronizeVisualPositions();
    graph.refresh();
    callbacks.onModeChange?.(mode);
    scheduleLabels();
    return mode;
  }

  function setFrozen(nextFrozen) {
    const selected = Boolean(nextFrozen);
    if (selected === frozen) return frozen;
    frozen = selected;
    synchronizeVisualPositions();
    callbacks.onFreezeChange?.(frozen);
    return frozen;
  }

  function setPinned(keyOrNode, pinned) {
    const key = typeof keyOrNode === 'object' ? nodeKey(keyOrNode) : String(keyOrNode || '');
    const node = renderNodes.get(key);
    if (!node) return false;
    node.pinned = Boolean(pinned);
    const position = renderedPosition(node);
    if (node.pinned) {
      node.fx = position.x;
      node.fy = position.y;
      node.fz = position.z;
    } else {
      delete node.fx;
      delete node.fy;
      delete node.fz;
    }
    callbacks.onPinChange?.({ key, pinned: node.pinned, position, node: node.__source || node });
    return true;
  }

  function setPalette(nextPalette = {}) {
    paletteOverrides = { ...paletteOverrides, ...nextPalette };
    palette = resolvedPalette(host, paletteOverrides);
    for (const metadata of materials.values()) {
      metadata.material.color?.set(materialColor(metadata.state));
      metadata.material.needsUpdate = true;
    }
    for (const metadata of ownedNodeMaterials.values()) {
      metadata.material.color?.set(materialColor(metadata.state));
      metadata.material.needsUpdate = true;
    }
    graph?.backgroundColor('transparent');
    refresh();
  }

  function setReducedMotion(value = null) {
    reducedMotionOverride = typeof value === 'boolean' ? value : null;
    const particlesChanged = updateParticleAllocation();
    if (prefersReducedMotion()) {
      if (cameraTweenFrame !== null || programmaticCamera) stopCameraTween({ resumeAutoRotate: false });
      clearAutoRotateTimer();
      autoRotatePaused = true;
      updateAutoRotate();
      clearEffectAnimationFrame();
      animatedEffects.clear();
      drainPulseParticles();
      releasePulseReservations();
      resetAnimatedNodeVisuals();
    } else {
      scheduleEffectAnimation();
      scheduleAutoRotateResume();
    }
    if (particlesChanged) graph?.refresh();
    return prefersReducedMotion();
  }

  async function enterFullscreen() {
    if (disposed || typeof host.requestFullscreen !== 'function') return false;
    try {
      await host.requestFullscreen();
      return true;
    } catch (error) {
      callbacks.onFullscreenError?.(error);
      return false;
    }
  }

  async function exitFullscreen() {
    if (documentValue.fullscreenElement !== host || typeof documentValue.exitFullscreen !== 'function') return false;
    try {
      await documentValue.exitFullscreen();
      return true;
    } catch (error) {
      callbacks.onFullscreenError?.(error);
      return false;
    }
  }

  function clear() {
    stopCameraTween({ resumeAutoRotate: false });
    if (heroFrameTimer !== null) clearTimeout(heroFrameTimer);
    heroFrameTimer = null;
    heroFramed = false;
    bootstrapStartedAt = 0;
    cameraInteracted = false;
    setNavigationScope({ level: 'overview' });
    clearEffects();
    clearAutoRotateTimer();
    stopCameraTween({ resumeAutoRotate: false });
    if (heroFrameTimer !== null) clearTimeout(heroFrameTimer);
    requestedLabelKeys = [];
    currentStructure = '';
    currentDocument = { nodes: [], links: [], labels: [], effects: [], rendererLimits: {} };
    focusedKey = '';
    renderNodes.clear();
    renderLinks.clear();
    dragging.clear();
    nodeObjects.clear();
    linkObjects.forEach((object) => object.geometry?.dispose());
    linkObjects.clear();
    labelElements.forEach((element) => element.remove());
    labelElements.clear();
    labelResidence.clear();
    labelHiddenAt.clear();
    labelMembership.clear();
    labelAnchorIndexes.clear();
    ownedNodeMaterials.forEach(({ material }) => material.dispose?.());
    ownedNodeMaterials.clear();
    particleAllocation.clear();
    steadyParticleCounts.clear();
    graph?.graphData({ nodes: [], links: [] });
    if (lastOffscreenActivityCount !== 0) {
      lastOffscreenActivityCount = 0;
      callbacks.onOffscreenActivity?.({ count: 0 });
    }
    scheduleAutoRotateResume();
  }

  function diagnostics() {
    let rendererMemory = null;
    try {
      const memory = graph?.renderer()?.info?.memory;
      if (memory) rendererMemory = { geometries: finite(memory.geometries), textures: finite(memory.textures) };
    } catch (_) {
      // A lost context may make renderer diagnostics temporarily unavailable.
    }
    return Object.freeze({
      available: Boolean(graph), disposed, layoutOwner: 'external', mode, frozen,
      nodes: renderNodes.size, links: renderLinks.size, labels: labelElements.size,
      expandedFields: [...renderNodes.values()].filter((node) =>
        nodeType(node) === 'universe' && node.expandedField === true).length,
      steadyParticles: [...steadyParticleCounts.values()].reduce((total, count) => total + count, 0),
      pendingParticles: pulseReservations.size, animatedEffects: animatedEffects.size,
      contextLost, paused, autoRotateRequested,
      autoRotateEffective: Boolean(controls?.autoRotate), arrangeMode,
      scope: { ...navigationScope }, camera: cameraPose(), rendererMemory,
      limits: currentDocument.rendererLimits || {}
    });
  }

  let scene = null;
  let controls = null;
  const onControlsStart = () => {
    cameraInteracted = true;
    if (heroFrameTimer !== null) clearTimeout(heroFrameTimer);
    heroFrameTimer = null;
    stopCameraTween({ resumeAutoRotate: false });
    autoRotatePaused = true;
    clearAutoRotateTimer();
    updateAutoRotate();
    callbacks.onCameraInteraction?.();
  };
  const onControlsEnd = () => scheduleAutoRotateResume();
  const onControlsChange = () => scheduleLabels();
  const onContextLost = (event) => {
    event.preventDefault();
    contextLost = true;
    host.dataset.rendererState = 'context-lost';
    status.hidden = false;
    status.textContent = 'Visualization paused while WebGL recovers. Live activity continues.';
    status.setAttribute('role', 'status');
    callbacks.onContextLost?.();
  };
  const onContextRestored = () => {
    contextLost = false;
    host.dataset.rendererState = 'ready';
    status.hidden = true;
    refresh({ rebuild: true });
    callbacks.onContextRestored?.();
  };
  const onVisibilityChange = () => {
    if (!graph || disposed) return;
    if (documentValue.hidden) {
      clearAutoRotateTimer();
      stopCameraTween({ resumeAutoRotate: false });
      updateAutoRotate();
      graph.pauseAnimation();
      paused = true;
      if (labelFrame !== null) cancelAnimationFrame(labelFrame);
      if (labelTimer !== null) clearTimeout(labelTimer);
      labelFrame = null;
      labelTimer = null;
      if (cameraFrame !== null) cancelAnimationFrame(cameraFrame);
      cameraFrame = null;
      clearEffects();
    } else {
      graph.resumeAnimation();
      paused = false;
      for (const [id, effect] of animatedEffects) {
        if (finite(effect.expiresAtMs) <= clock()) animatedEffects.delete(id);
      }
      resetAnimatedNodeVisuals();
      refresh();
      scheduleEffectAnimation();
      scheduleAutoRotateResume();
    }
  };
  const onFullscreenChange = () => {
    resize();
    callbacks.onFullscreenChange?.(documentValue.fullscreenElement === host);
  };
  const onReducedMotionChange = () => setReducedMotion(null);

  try {
    graph = library.ForceGraph3D({ controlType: 'orbit', rendererConfig: {
      alpha: true, antialias: true, powerPreference: 'high-performance'
    }})(surface)
      .showNavInfo(false)
      .backgroundColor('transparent')
      .nodeId('id')
      .nodeVal(1)
      .nodeThreeObject(nodeObjectAccessor)
      .nodeThreeObjectExtend(false)
      .linkSource('source')
      .linkTarget('target')
      .linkThreeObject(linkObjectAccessor)
      .linkThreeObjectExtend(false)
      .linkPositionUpdate((object, coordinates, link) =>
        updateLinkPosition(object, coordinates.start, coordinates.end, link))
      .linkCurvature((link) => finite(link.curvature,
        normalizedLinkType(link) === 'migration' ? (link.curveDirection === -1 ? -0.18 : 0.18) : 0))
      .linkDirectionalParticles((link) => particleAllocation.get(linkKey(link)) || 0)
      .linkDirectionalParticleWidth((link) => normalizedLinkType(link) === 'tx' ? 1.8 : 1.2)
      .linkDirectionalParticleSpeed(() => prefersReducedMotion() ? 0 : 0.012)
      .linkDirectionalParticleColor(() => palette.active)
      .linkDirectionalParticleThreeObject(() =>
        new library.Mesh(sharedParticleGeometry, sharedParticleMaterial))
      .onNodeClick((node, event) => callbacks.onNodeClick?.(node.__source || node, event))
      .onBackgroundClick((event) => callbacks.onBackgroundClick?.(event))
      .onNodeDrag((node, translate) => {
        const key = nodeKey(node);
        if (!dragging.has(key)) {
          clearAutoRotateTimer();
          autoRotatePaused = true;
          updateAutoRotate();
        }
        const start = dragging.get(key) || { x: finite(node.x) - finite(translate.x),
          y: finite(node.y) - finite(translate.y), z: finite(node.z) - finite(translate.z) };
        dragging.set(key, start);
        const position = { x: finite(node.x), y: finite(node.y), z: mode === 'flat' ? 0 : finite(node.z) };
        callbacks.onNodeDrag?.({ key, position,
          delta: { x: finite(translate.x), y: finite(translate.y), z: mode === 'flat' ? 0 : finite(translate.z) },
          node: node.__source || node });
        synchronizeVisualPositions();
        scheduleLabels();
      })
      .onNodeDragEnd((node, _translate) => {
        const key = nodeKey(node);
        const start = dragging.get(key) || { x: finite(node.x), y: finite(node.y), z: finite(node.z) };
        dragging.delete(key);
        const position = { x: finite(node.x), y: finite(node.y), z: mode === 'flat' ? 0 : finite(node.z) };
        callbacks.onNodeDragEnd?.({ key, position,
          delta: { x: position.x - start.x, y: position.y - start.y, z: position.z - start.z },
          node: node.__source || node });
        if (!arrangeMode) scheduleAutoRotateResume();
        scheduleLabels();
      })
      .onEngineTick(() => {
        synchronizeVisualPositions();
        scheduleLabels();
      })
      .cooldownTicks(0)
      .cooldownTime(0);

    // Layout is deliberately external. The graph library owns rendering, picking, camera and drag controls only.
    graph.d3Force('link', null);
    graph.d3Force('charge', null);
    graph.d3Force('center', null);
    graph.numDimensions(mode === 'flat' ? 2 : 3);
    scene = graph.scene();
    controls = graph.controls();
    if (controls) {
      controls.autoRotate = false;
      controls.autoRotateSpeed = animation.autoRotateSpeed;
      controls.enableDamping = true;
      controls.dampingFactor = 0.07;
      controls.rotateSpeed = 0.45;
      controls.zoomSpeed = 0.65;
      controls.enablePan = true;
      controls.enableZoom = true;
      controls.enableRotate = mode !== 'flat';
      controls.screenSpacePanning = true;
      controls.zoomToCursor = true;
      if (library.MOUSE && library.TOUCH && controls.mouseButtons && controls.touches) {
        controls.mouseButtons.LEFT = mode === 'flat' ? library.MOUSE.PAN : library.MOUSE.ROTATE;
        controls.mouseButtons.MIDDLE = library.MOUSE.DOLLY;
        controls.mouseButtons.RIGHT = library.MOUSE.PAN;
        controls.touches.ONE = mode === 'flat' ? library.TOUCH.PAN : library.TOUCH.ROTATE;
        controls.touches.TWO = mode === 'flat' ? library.TOUCH.DOLLY_PAN : library.TOUCH.DOLLY_ROTATE;
      }
      controls.addEventListener('start', onControlsStart);
      controls.addEventListener('end', onControlsEnd);
      controls.addEventListener('change', onControlsChange);
    }
    graph.enableNodeDrag?.(arrangeMode);
    rendererCanvas = graph.renderer()?.domElement || null;
    rendererCanvas?.addEventListener('webglcontextlost', onContextLost, false);
    rendererCanvas?.addEventListener('webglcontextrestored', onContextRestored, false);
  } catch (error) {
    try { graph?._destructor?.(); } catch (_) { /* The partial renderer is already unavailable. */ }
    disposeSharedResources();
    graph = null;
    return createUnavailableRenderer(host, surface, labelLayer, status, error, callbacks, options.signal);
  }

  host.dataset.rendererState = 'ready';
  mediaQuery = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : null;
  mediaQuery?.addEventListener?.('change', onReducedMotionChange);
  documentValue.addEventListener('visibilitychange', onVisibilityChange);
  documentValue.addEventListener('fullscreenchange', onFullscreenChange);
  if (typeof ResizeObserver === 'function') {
    resizeObserver = new ResizeObserver(resize);
    resizeObserver.observe(host);
  } else {
    resizeFallback = resize;
    window.addEventListener('resize', resizeFallback);
  }
  if (documentValue.documentElement && typeof MutationObserver === 'function') {
    themeObserver = new MutationObserver(() => setPalette());
    themeObserver.observe(documentValue.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  }
  const abort = () => dispose();
  options.signal?.addEventListener('abort', abort, { once: true });

  function dispose() {
    if (disposed) return;
    disposed = true;
    if (cameraTweenFrame !== null) cancelAnimationFrame(cameraTweenFrame);
    if (heroFrameTimer !== null) clearTimeout(heroFrameTimer);
    if (autoRotateTimer !== null) clearTimeout(autoRotateTimer);
    cameraTweenFrame = null;
    heroFrameTimer = null;
    autoRotateTimer = null;
    programmaticCamera = false;
    if (controls) controls.autoRotate = false;
    clearEffects();
    if (labelFrame !== null) cancelAnimationFrame(labelFrame);
    if (labelTimer !== null) clearTimeout(labelTimer);
    if (cameraFrame !== null) cancelAnimationFrame(cameraFrame);
    labelFrame = null;
    labelTimer = null;
    cameraFrame = null;
    resizeObserver?.disconnect();
    if (resizeFallback) window.removeEventListener('resize', resizeFallback);
    themeObserver?.disconnect();
    mediaQuery?.removeEventListener?.('change', onReducedMotionChange);
    documentValue.removeEventListener('visibilitychange', onVisibilityChange);
    documentValue.removeEventListener('fullscreenchange', onFullscreenChange);
    options.signal?.removeEventListener('abort', abort);
    controls?.removeEventListener('start', onControlsStart);
    controls?.removeEventListener('end', onControlsEnd);
    controls?.removeEventListener('change', onControlsChange);
    rendererCanvas?.removeEventListener('webglcontextlost', onContextLost, false);
    rendererCanvas?.removeEventListener('webglcontextrestored', onContextRestored, false);
    const ownedScene = scene;
    let ownedWebglRenderer = null;
    try { ownedWebglRenderer = graph?.renderer?.() || null; } catch (_) { /* Continue teardown. */ }
    try { graph?._destructor?.(); } catch (_) { /* Continue disposing feature-owned resources. */ }
    try { ownedWebglRenderer?.forceContextLoss?.(); } catch (_) { /* The renderer was already released. */ }
    if (globalThis.scene === ownedScene) {
      try { delete globalThis.scene; } catch (_) { globalThis.scene = undefined; }
    }
    linkObjects.forEach((object) => object.geometry?.dispose());
    geometries.forEach((value) => value.dispose?.());
    materials.forEach(({ material }) => material.dispose?.());
    ownedNodeMaterials.forEach(({ material }) => material.dispose?.());
    disposeSharedResources();
    labelElements.forEach((element) => element.remove());
    renderNodes.clear();
    renderLinks.clear();
    nodeObjects.clear();
    linkObjects.clear();
    labelElements.clear();
    labelResidence.clear();
    labelHiddenAt.clear();
    labelMembership.clear();
    dragging.clear();
    particleAllocation.clear();
    steadyParticleCounts.clear();
    geometries.clear();
    materials.clear();
    ownedNodeMaterials.clear();
    labelResidence.clear();
    labelAnchorIndexes.clear();
    effectCooldowns.clear();
    requestedLabelKeys = [];
    currentDocument = { nodes: [], links: [], labels: [], effects: [], rendererLimits: {} };
    currentStructure = '';
    graph = null;
    scene = null;
    controls = null;
    rendererCanvas = null;
    resizeObserver = null;
    resizeFallback = null;
    themeObserver = null;
    mediaQuery = null;
    surface.remove();
    labelLayer.remove();
    status.remove();
    delete host.dataset.rendererState;
  }

  resize();
  if (initialMode === 'flat') setMode('flat', { duration: 0 });
  if (documentValue.hidden) onVisibilityChange();
  if (options.graphData) setGraphData(options.graphData);

  return Object.freeze({
    available: true,
    layoutOwner: 'external',
    setGraphData,
    refresh,
    resize,
    setMode,
    setFrozen,
    setPinned,
    setPalette,
    setReducedMotion,
    setLabelBudget: (value) => {
      labelBudget = Math.min(maximumNodes, configuredMaximumLabels,
        Math.max(1, Math.floor(finite(value, labelBudget))));
      scheduleLabels();
      return labelBudget;
    },
    setParticleBudget: (value) => {
      particleBudget = Math.min(configuredMaximumParticles,
        Math.max(0, Math.floor(finite(value, particleBudget))));
      const particlesChanged = updateParticleAllocation();
      if (particlesChanged) graph.refresh();
      return particleBudget;
    },
    fitAll,
    frameScope,
    focus,
    getCameraPose: cameraPose,
    restoreCameraPose,
    setNavigationScope,
    setAutoRotate,
    setArrange,
    pulse,
    clearEffects,
    clear,
    enterFullscreen,
    exitFullscreen,
    diagnostics,
    dispose
  });
}

export {
  allocateParticles,
  boundedGraphData,
  chooseLabelPlacements,
  createNetworkVisualizerRenderer,
  easeInOutCubic,
  labelEligible,
  labelZoomTier,
  linkIsDashed,
  linkVisualState,
  linkWidth,
  nodeGeometryKind,
  nodeRadius,
  nodeVisualState
};
