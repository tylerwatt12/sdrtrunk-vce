'use strict';

import { BALANCED_CONFIG } from './config.js';

const VENDOR_ASSET = '../../vendor/network-visualizer-vendor.js?v=1';
const LINK_SEGMENTS = 16;
const LABEL_CELL_WIDTH = 56;
const LABEL_CELL_HEIGHT = 28;
const LABEL_PADDING = 4;
const MINIMUM_CAMERA_DISTANCE = 72;
const DEFAULT_CAMERA_DISTANCE = 96;
const EFFECT_SEEN_MULTIPLIER = 2;
const OVERVIEW_DISTANCE_RATIO = 0.85;
const MID_DISTANCE_RATIO = 0.32;

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
    const left = Math.round(finite(candidate.x) + finite(candidate.offsetX, 7));
    const top = Math.round(finite(candidate.y) - labelHeight / 2 + finite(candidate.offsetY));
    const rectangle = { left, top, right: left + labelWidth, bottom: top + labelHeight };
    if (rectangle.right < 0 || rectangle.left > width || rectangle.bottom < 0 || rectangle.top > height) continue;

    const minimumColumn = Math.floor((rectangle.left - padding) / cellWidth);
    const maximumColumn = Math.floor((rectangle.right + padding) / cellWidth);
    const minimumRow = Math.floor((rectangle.top - padding) / cellHeight);
    const maximumRow = Math.floor((rectangle.bottom + padding) / cellHeight);
    const possible = new Set();
    for (let column = minimumColumn; column <= maximumColumn; column += 1) {
      for (let row = minimumRow; row <= maximumRow; row += 1) {
        (grid.get(`${column}:${row}`) || []).forEach((index) => possible.add(index));
      }
    }
    if ([...possible].some((index) => rectanglesOverlap(rectangle, accepted[index].rectangle, padding))) continue;

    const placement = { ...candidate, left, top, rectangle };
    delete placement.__order;
    const acceptedIndex = accepted.length;
    accepted.push(placement);
    for (let column = minimumColumn; column <= maximumColumn; column += 1) {
      for (let row = minimumRow; row <= maximumRow; row += 1) {
        const key = `${column}:${row}`;
        const contents = grid.get(key) || [];
        contents.push(acceptedIndex);
        grid.set(key, contents);
      }
    }
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
  if (node?.active || effect?.type === 'destination_highlight' || effect?.type === 'tx_pulse') return 'active';
  if (node?.pending) return 'pending';
  if (effect?.type === 'affiliation_arrival' || effect?.type === 'migration') return 'arrival';
  if (effect?.type === 'afterglow' || node?.afterglow) return 'afterglow';
  if (node?.quiet || node?.stale) return 'quiet';
  return nodeType(node);
}

function linkVisualState(link) {
  const type = normalizedLinkType(link);
  if (link?.active) return 'active';
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
  if (Number.isFinite(specified) && specified > 0) return clamp(specified, 0.6, 24);
  return ({ universe: 8.5, group: 4.8, aggregate: 3.6, radio: 1.9 })[nodeType(node)];
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
    nodes: 0, links: 0, labels: 0, steadyParticles: 0, pendingParticles: 0,
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
  let autoFitPerformed = false;
  let focusedKey = '';
  let lastOffscreenActivityCount = -1;
  let currentGeneration = options.generation ?? null;
  let currentDocument = { nodes: [], links: [], labels: [], effects: [], rendererLimits: {} };
  let currentStructure = '';
  let requestedLabelKeys = [];
  let particleAllocation = new Map();
  let labelFrame = null;
  let cameraFrame = null;
  let autoFitFrame = null;
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
  const pulseTimers = new Set();
  const seenEffects = new Map();
  const animatedEffects = new Map();
  const nodeEffects = new Map();
  const geometries = new Map();
  const materials = new Map();

  const prefersReducedMotion = () => reducedMotionOverride ?? Boolean(mediaQuery?.matches);

  function materialColor(state) {
    return palette[state] || palette.radio;
  }

  function materialOpacity(state) {
    return ({ quiet: 0.28, faded: 0.16, activity: 0.42, affiliation: 0.48,
      afterglow: 0.62, universe: 0.38 })[state] ?? 0.88;
  }

  function meshMaterial(kind, state) {
    const key = `mesh:${kind}:${state}`;
    if (materials.has(key)) return materials.get(key).material;
    const wireframe = kind === 'universe';
    const material = wireframe ? new library.MeshBasicMaterial({
      color: materialColor(state), transparent: true, opacity: materialOpacity(state), wireframe: true,
      depthWrite: false
    }) : new library.MeshLambertMaterial({
      color: materialColor(state), transparent: true, opacity: materialOpacity(state), depthWrite: false
    });
    materials.set(key, { material, state, category: 'node' });
    return material;
  }

  function specialMaterial(name) {
    const key = `special:${name}`;
    if (materials.has(key)) return materials.get(key).material;
    const state = name === 'outline' ? 'selected' : 'encrypted';
    const material = new library.MeshBasicMaterial({ color: materialColor(state), transparent: true,
      opacity: name === 'outline' ? 0.98 : 0.82, wireframe: name === 'encrypted',
      side: name === 'outline' ? library.BackSide : undefined, depthWrite: false });
    materials.set(key, { material, state, category: 'special' });
    return material;
  }

  function lineMaterial(state, dashed, encrypted = false) {
    const pattern = encrypted ? 'encrypted' : dashed ? 'dashed' : 'solid';
    const key = `line:${state}:${pattern}`;
    if (materials.has(key)) return materials.get(key).material;
    const settings = { color: materialColor(state), transparent: true, opacity: materialOpacity(state),
      depthWrite: false };
    const material = pattern !== 'solid' ? new library.LineDashedMaterial({ ...settings,
      dashSize: encrypted ? 2.4 : 8, gapSize: encrypted ? 2.4 : 5, scale: 1 }) : new library.LineBasicMaterial(settings);
    materials.set(key, { material, state, category: 'line' });
    return material;
  }

  function geometry(kind) {
    if (geometries.has(kind)) return geometries.get(kind);
    let value;
    if (kind === 'universe') value = new library.IcosahedronGeometry(1, 1);
    else if (kind === 'conventional-universe') value = new library.TorusGeometry(1, 0.13, 8, 28);
    else if (kind === 'conventional-group') value = new library.BoxGeometry(1.55, 0.42, 1.55);
    else if (kind === 'group') value = new library.CylinderGeometry(1, 1, 0.42, 18, 1, false);
    else if (kind === 'aggregate') value = new library.BoxGeometry(1.45, 1.45, 1.45);
    else if (kind === 'encrypted') value = new library.TorusGeometry(1.28, 0.11, 6, 18);
    else value = new library.SphereGeometry(1, 10, 8);
    geometries.set(kind, value);
    return value;
  }

  function activeNodeEffect(key) {
    const effect = nodeEffects.get(key);
    if (!effect || finite(effect.expiresAtMs) <= clock()) {
      if (effect) nodeEffects.delete(key);
      return null;
    }
    return effect;
  }

  function applyNodeVisual(node, object = nodeObjects.get(nodeKey(node))) {
    if (!object) return;
    const kind = nodeGeometryKind(node);
    const state = nodeVisualState(node, activeNodeEffect(nodeKey(node)));
    const base = object.userData.visualizerBase;
    const outline = object.userData.visualizerOutline;
    const encrypted = object.userData.visualizerEncrypted;
    const radius = nodeRadius(node);
    const encryptedVisible = Boolean(node.encrypted || node.entity?.encrypted);
    const signature = `${kind}:${state}:${Boolean(node.selected)}:${encryptedVisible}:${node.visible !== false}:${radius}`;
    if (object.userData.visualizerStyleSignature === signature) return;
    object.userData.visualizerStyleSignature = signature;
    if (base.geometry !== geometry(kind)) base.geometry = geometry(kind);
    base.material = meshMaterial(kind, state);
    outline.geometry = geometry(kind);
    outline.material = specialMaterial('outline');
    outline.visible = Boolean(node.selected);
    encrypted.material = specialMaterial('encrypted');
    encrypted.visible = encryptedVisible;
    object.userData.visualizerBaseRadius = radius;
    if (!object.userData.visualizerAnimating) object.scale.setScalar(radius);
    object.visible = node.visible !== false;
  }

  function createNodeObject(node) {
    const key = nodeKey(node);
    const object = new library.Group();
    const kind = nodeGeometryKind(node);
    const base = new library.Mesh(geometry(kind), meshMaterial(kind, nodeVisualState(node)));
    const outline = new library.Mesh(geometry(kind), specialMaterial('outline'));
    outline.scale.setScalar(1.18);
    outline.visible = false;
    const encrypted = new library.Mesh(geometry('encrypted'), specialMaterial('encrypted'));
    encrypted.scale.setScalar(0.92);
    encrypted.visible = false;
    object.add(base, outline, encrypted);
    object.userData.visualizerKey = key;
    object.userData.visualizerBase = base;
    object.userData.visualizerOutline = outline;
    object.userData.visualizerEncrypted = encrypted;
    nodeObjects.set(key, object);
    applyNodeVisual(node, object);
    return object;
  }

  function createLinkObject(link) {
    const key = linkKey(link);
    const positions = new Float32Array((LINK_SEGMENTS + 1) * 3);
    const linkGeometry = new library.BufferGeometry();
    const attribute = new library.Float32BufferAttribute(positions, 3);
    attribute.setUsage(library.DynamicDrawUsage);
    linkGeometry.setAttribute('position', attribute);
    const line = new library.Line(linkGeometry,
      lineMaterial(linkVisualState(link), linkIsDashed(link), Boolean(link.encrypted)));
    line.userData.visualizerKey = key;
    linkObjects.set(key, line);
    return line;
  }

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
    if (!object?.geometry?.attributes?.position) return false;
    const array = object.geometry.attributes.position.array;
    const startX = finite(start?.x), startY = finite(start?.y), startZ = mode === 'flat' ? 0 : finite(start?.z);
    const endX = finite(end?.x), endY = finite(end?.y), endZ = mode === 'flat' ? 0 : finite(end?.z);
    const deltaX = endX - startX, deltaY = endY - startY, deltaZ = endZ - startZ;
    const length = Math.hypot(deltaX, deltaY, deltaZ);
    const curvature = finite(link?.curvature,
      normalizedLinkType(link) === 'migration' ? (link?.curveDirection === -1 ? -0.18 : 0.18) : 0);
    const path = object.userData.visualizerPath;
    if (path && path[0] === startX && path[1] === startY && path[2] === startZ &&
        path[3] === endX && path[4] === endY && path[5] === endZ && path[6] === curvature) return true;
    object.userData.visualizerPath = [startX, startY, startZ, endX, endY, endZ, curvature];
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
    object.geometry.attributes.position.needsUpdate = true;
    object.geometry.computeBoundingSphere();
    if (object.material?.isLineDashedMaterial) object.computeLineDistances();
    return true;
  }

  function renderedPosition(node) {
    return { x: finite(node.x), y: finite(node.y), z: mode === 'flat' ? 0 : finite(node.z) };
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
    for (const link of renderLinks.values()) {
      const object = linkObjects.get(linkKey(link));
      const source = renderNodes.get(link.__sourceKey);
      const target = renderNodes.get(link.__targetKey);
      if (!object || !source || !target) continue;
      updateLinkPosition(object, renderedPosition(source), renderedPosition(target), link);
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

  function resetAnimatedNodeVisuals() {
    for (const node of renderNodes.values()) {
      const object = nodeObjects.get(nodeKey(node));
      if (!object || !object.userData.visualizerAnimating) continue;
      object.userData.visualizerAnimating = false;
      object.scale.setScalar(object.userData.visualizerBaseRadius || nodeRadius(node));
      applyNodeVisual(node, object);
    }
  }

  function animateEffects() {
    effectFrame = null;
    if (disposed) return;
    if (documentValue.hidden || prefersReducedMotion()) {
      resetAnimatedNodeVisuals();
      return;
    }
    const now = clock();
    let continuing = false;
    for (const [id, effect] of animatedEffects) {
      const node = renderNodes.get(effectNodeKey(effect));
      const object = node && nodeObjects.get(nodeKey(node));
      const start = finite(effect.createdAtMs, now);
      const end = Math.max(start + 1, finite(effect.expiresAtMs, start + 800));
      if (!object || now >= end) {
        animatedEffects.delete(id);
        if (object) {
          object.userData.visualizerAnimating = false;
          object.scale.setScalar(object.userData.visualizerBaseRadius || nodeRadius(node));
          applyNodeVisual(node, object);
        }
        continue;
      }
      continuing = true;
      const progress = clamp((now - start) / (end - start), 0, 1);
      const pulse = 1 + Math.sin(progress * Math.PI) * (1 - progress) * 0.32;
      object.userData.visualizerAnimating = true;
      object.scale.setScalar((object.userData.visualizerBaseRadius || nodeRadius(node)) * pulse);
    }
    if (continuing) effectFrame = requestAnimationFrame(animateEffects);
  }

  function scheduleEffectAnimation() {
    if (effectFrame === null && animatedEffects.size && !prefersReducedMotion() && !documentValue.hidden) {
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

  function pulse(linkOrKey) {
    if (!graph || disposed || documentValue.hidden || prefersReducedMotion()) return false;
    const link = typeof linkOrKey === 'object' ? linkOrKey : renderLinks.get(String(linkOrKey));
    if (!link || !renderLinks.has(linkKey(link))) return false;
    const steady = [...steadyParticleCounts.values()].reduce((total, count) => total + count, 0);
    if (steady + pulseTimers.size >= particleBudget) return false;
    graph.emitParticle(link);
    const timer = setTimeout(() => pulseTimers.delete(timer), 1_500);
    pulseTimers.add(timer);
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
        if (!current || finite(current.expiresAtMs) <= finite(effect.expiresAtMs)) nodeEffects.set(key, effect);
      }
      if (['affiliation_arrival', 'migration', 'destination_highlight', 'tx_pulse'].includes(effect.type) &&
          animatedEffects.size < particleBudget) animatedEffects.set(id, effect);
      if (['tx_pulse', 'migration'].includes(effect.type) && !documentValue.hidden && !prefersReducedMotion()) {
        const link = findEffectLink(effect);
        if (link) queueMicrotask(() => { if (!disposed) pulse(link); });
      }
    }
    expireSeenEffects();
    scheduleEffectAnimation();
  }

  function labelText(node) {
    const label = String(node?.label || node?.entity?.displayName || node?.entity?.name || nodeKey(node));
    return node?.pending ? `${label} · grant pending` : label;
  }

  function labelCandidateNodes() {
    if (requestedLabelKeys.length) return requestedLabelKeys.map((key, index) => ({
      node: renderNodes.get(key), rank: requestedLabelKeys.length - index
    })).filter((entry) => entry.node);
    return [...renderNodes.values()].map((node) => ({ node, rank: 0 }))
      .sort((left, right) => labelPriority(right.node) - labelPriority(left.node))
      .slice(0, labelBudget * 3);
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
    return labelZoomTier(camera.position.distanceTo(target), span, Boolean(focusedKey));
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
    const width = Math.max(0, surface.clientWidth || host.clientWidth);
    const height = Math.max(0, surface.clientHeight || host.clientHeight);
    if (!width || !height) return;
    const tier = currentLabelZoomTier();
    updateOffscreenActivityCount(width, height);
    const candidates = [];
    for (const { node, rank } of labelCandidateNodes()) {
      if (!node || node.labelVisible === false || !labelEligible(node, tier)) continue;
      const position = renderedPosition(node);
      let screen;
      try {
        screen = graph.graph2ScreenCoords(position.x, position.y, position.z);
      } catch (_) {
        continue;
      }
      const text = labelText(node);
      candidates.push({ key: nodeKey(node), node, text, x: screen.x, y: screen.y,
        offsetX: nodeRadius(node) + 5, width: clamp(text.length * 7 + (node.encrypted ? 24 : 12), 48, 240),
        height: nodeType(node) === 'universe' ? 30 : 22, priority: labelPriority(node, rank) });
    }
    const placements = chooseLabelPlacements(candidates, { width, height }, labelBudget);
    const visible = new Set();
    for (const placement of placements) {
      let element = labelElements.get(placement.key);
      if (!element) {
        element = createElement(documentValue, 'span', 'network-visualizer-label');
        element.dataset.key = placement.key;
        labelElements.set(placement.key, element);
        labelLayer.append(element);
      }
      visible.add(placement.key);
      element.textContent = `${placement.node.encrypted ? '🔒 ' : ''}${placement.text}`;
      element.dataset.type = nodeType(placement.node);
      element.dataset.active = String(Boolean(placement.node.active));
      element.dataset.pending = String(Boolean(placement.node.pending));
      element.dataset.selected = String(Boolean(placement.node.selected));
      element.dataset.quiet = String(Boolean(placement.node.quiet));
      element.style.transform = `translate3d(${placement.left}px, ${placement.top}px, 0)`;
      element.hidden = false;
    }
    for (const [key, element] of labelElements) {
      if (visible.has(key)) continue;
      element.remove();
      labelElements.delete(key);
    }
  }

  function scheduleLabels() {
    if (labelFrame === null && !disposed && !documentValue.hidden) labelFrame = requestAnimationFrame(renderLabels);
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
    for (const node of renderNodes.values()) applyNodeVisual(node);
    for (const link of renderLinks.values()) applyLinkVisual(link);
    synchronizeVisualPositions();
    // The graph owns a continuous RAF. Rebuilding it every external layout tick is expensive and
    // unnecessary because custom objects are updated in place.
    if (optionsValue.rebuild === true) graph.refresh();
    scheduleLabels();
  }

  function clearEffects() {
    clearEffectAnimationFrame();
    animatedEffects.clear();
    nodeEffects.clear();
    seenEffects.clear();
    pulseTimers.forEach(clearTimeout);
    pulseTimers.clear();
    resetAnimatedNodeVisuals();
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
    // Custom objects are created by the graph update; refresh once more only after a structural change.
    if (structureChanged) queueMicrotask(() => { if (!disposed) refresh(); });
    else if (particlesChanged) graph.refresh();
    if (nodes.length && !autoFitPerformed && !cameraInteracted && options.autoFitOnFirstData !== false) {
      autoFitPerformed = true;
      autoFitFrame = requestAnimationFrame(() => {
        autoFitFrame = null;
        if (!disposed && !cameraInteracted) fitAll();
      });
    }
    if (bounded.rendererLimits.suppressedNodes || bounded.rendererLimits.suppressedLinks ||
        bounded.rendererLimits.suppressedEffects) callbacks.onLimit?.(bounded.rendererLimits);
    return diagnostics();
  }

  function resize() {
    if (!graph || disposed) return;
    const width = Math.max(1, Math.floor(surface.clientWidth || host.clientWidth || 1));
    const height = Math.max(1, Math.floor(surface.clientHeight || host.clientHeight || 1));
    graph.width(width).height(height);
    scheduleLabels();
  }

  function cameraDuration(requested = 360) {
    return prefersReducedMotion() ? 0 : Math.max(0, finite(requested, 360));
  }

  function fitAll(duration = 360, padding = 48) {
    if (!graph || disposed || !renderNodes.size) return false;
    focusedKey = '';
    const milliseconds = cameraDuration(duration);
    graph.zoomToFit(milliseconds, Math.max(0, finite(padding, 48)), (node) => node.visible !== false);
    trackCamera(milliseconds);
    return true;
  }

  function focus(keyOrNode, duration = 420) {
    if (!graph || disposed) return false;
    const key = typeof keyOrNode === 'object' ? nodeKey(keyOrNode) : String(keyOrNode || '');
    const node = renderNodes.get(key);
    if (!node) return false;
    focusedKey = key;
    const target = renderedPosition(node);
    const controls = graph.controls();
    const camera = graph.camera();
    const distance = Math.max(MINIMUM_CAMERA_DISTANCE, finite(node.focusDistance,
      nodeRadius(node) * 10 + DEFAULT_CAMERA_DISTANCE));
    let cameraPosition;
    if (mode === 'flat') cameraPosition = { x: target.x, y: target.y, z: distance };
    else {
      const direction = new library.Vector3().copy(camera.position)
        .sub(controls?.target || new library.Vector3()).normalize();
      if (!Number.isFinite(direction.x) || direction.lengthSq() < 0.001) direction.set(0.65, 0.45, 1).normalize();
      cameraPosition = { x: target.x + direction.x * distance, y: target.y + direction.y * distance,
        z: target.z + direction.z * distance };
    }
    const milliseconds = cameraDuration(duration);
    graph.cameraPosition(cameraPosition, target, milliseconds);
    trackCamera(milliseconds);
    return true;
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
    }
    if (mode === 'flat') {
      const target = controls?.target || { x: 0, y: 0, z: 0 };
      const distance = Math.max(MINIMUM_CAMERA_DISTANCE, graph.camera().position.distanceTo(controls?.target ||
        new library.Vector3()));
      graph.camera().up.set(0, 1, 0);
      const milliseconds = cameraDuration(optionsValue.duration ?? 320);
      graph.cameraPosition({ x: target.x, y: target.y, z: distance }, { x: target.x, y: target.y, z: 0 }, milliseconds);
      trackCamera(milliseconds);
    }
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
    graph?.backgroundColor(palette.background);
    refresh();
  }

  function setReducedMotion(value = null) {
    reducedMotionOverride = typeof value === 'boolean' ? value : null;
    const particlesChanged = updateParticleAllocation();
    if (prefersReducedMotion()) {
      clearEffectAnimationFrame();
      animatedEffects.clear();
      resetAnimatedNodeVisuals();
    } else scheduleEffectAnimation();
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
    clearEffects();
    requestedLabelKeys = [];
    currentStructure = '';
    currentDocument = { nodes: [], links: [], labels: [], effects: [], rendererLimits: {} };
    focusedKey = '';
    renderNodes.clear();
    renderLinks.clear();
    nodeObjects.clear();
    linkObjects.forEach((object) => object.geometry?.dispose());
    linkObjects.clear();
    labelElements.forEach((element) => element.remove());
    labelElements.clear();
    particleAllocation.clear();
    steadyParticleCounts.clear();
    graph?.graphData({ nodes: [], links: [] });
    if (lastOffscreenActivityCount !== 0) {
      lastOffscreenActivityCount = 0;
      callbacks.onOffscreenActivity?.({ count: 0 });
    }
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
      steadyParticles: [...steadyParticleCounts.values()].reduce((total, count) => total + count, 0),
      pendingParticles: pulseTimers.size, animatedEffects: animatedEffects.size,
      contextLost, paused, rendererMemory, limits: currentDocument.rendererLimits || {}
    });
  }

  let scene = null;
  let controls = null;
  const onControlsStart = () => {
    cameraInteracted = true;
    focusedKey = '';
    callbacks.onCameraInteraction?.();
  };
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
      graph.pauseAnimation();
      paused = true;
      if (labelFrame !== null) cancelAnimationFrame(labelFrame);
      labelFrame = null;
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
      .backgroundColor(palette.background)
      .nodeId('id')
      .nodeVal(1)
      .nodeThreeObject((node) => nodeObjects.get(nodeKey(node)) || createNodeObject(node))
      .nodeThreeObjectExtend(false)
      .linkSource('source')
      .linkTarget('target')
      .linkThreeObject((link) => linkObjects.get(linkKey(link)) || createLinkObject(link))
      .linkThreeObjectExtend(false)
      .linkPositionUpdate((object, coordinates, link) =>
        updateLinkPosition(object, coordinates.start, coordinates.end, link))
      .linkCurvature((link) => finite(link.curvature,
        normalizedLinkType(link) === 'migration' ? (link.curveDirection === -1 ? -0.18 : 0.18) : 0))
      .linkDirectionalParticles((link) => particleAllocation.get(linkKey(link)) || 0)
      .linkDirectionalParticleWidth((link) => normalizedLinkType(link) === 'tx' ? 1.8 : 1.2)
      .linkDirectionalParticleSpeed(() => prefersReducedMotion() ? 0 : 0.012)
      .linkDirectionalParticleColor(() => palette.active)
      .onNodeClick((node, event) => callbacks.onNodeClick?.(node.__source || node, event))
      .onBackgroundClick((event) => callbacks.onBackgroundClick?.(event))
      .onNodeDrag((node, translate) => {
        const key = nodeKey(node);
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
      controls.enableDamping = true;
      controls.dampingFactor = 0.09;
      controls.enablePan = true;
      controls.enableZoom = true;
      controls.enableRotate = mode !== 'flat';
      controls.screenSpacePanning = true;
      controls.addEventListener('start', onControlsStart);
      controls.addEventListener('change', onControlsChange);
    }
    rendererCanvas = graph.renderer()?.domElement || null;
    rendererCanvas?.addEventListener('webglcontextlost', onContextLost, false);
    rendererCanvas?.addEventListener('webglcontextrestored', onContextRestored, false);
  } catch (error) {
    try { graph?._destructor?.(); } catch (_) { /* The partial renderer is already unavailable. */ }
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
    clearEffects();
    if (labelFrame !== null) cancelAnimationFrame(labelFrame);
    if (cameraFrame !== null) cancelAnimationFrame(cameraFrame);
    if (autoFitFrame !== null) cancelAnimationFrame(autoFitFrame);
    labelFrame = null;
    cameraFrame = null;
    autoFitFrame = null;
    resizeObserver?.disconnect();
    if (resizeFallback) window.removeEventListener('resize', resizeFallback);
    themeObserver?.disconnect();
    mediaQuery?.removeEventListener?.('change', onReducedMotionChange);
    documentValue.removeEventListener('visibilitychange', onVisibilityChange);
    documentValue.removeEventListener('fullscreenchange', onFullscreenChange);
    options.signal?.removeEventListener('abort', abort);
    controls?.removeEventListener('start', onControlsStart);
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
    labelElements.forEach((element) => element.remove());
    renderNodes.clear();
    renderLinks.clear();
    nodeObjects.clear();
    linkObjects.clear();
    labelElements.clear();
    dragging.clear();
    particleAllocation.clear();
    steadyParticleCounts.clear();
    geometries.clear();
    materials.clear();
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
    focus,
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
  labelEligible,
  labelZoomTier,
  linkIsDashed,
  linkVisualState,
  nodeGeometryKind,
  nodeVisualState
};
