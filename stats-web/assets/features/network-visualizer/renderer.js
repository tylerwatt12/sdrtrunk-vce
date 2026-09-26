'use strict';

import { BALANCED_CONFIG } from './config.js';

const VENDOR_ASSET = '../../vendor/network-visualizer-vendor.js?v=2';
const LINK_SEGMENTS = 10;
const DEFAULT_ANIMATION = Object.freeze({
  particleFlightMs: 1_500,
  effectCoalesceMs: 2_200,
  cameraTransitionMs: 720,
  cameraBackTransitionMs: 620,
  autoRotateIdleDelayMs: 1_500,
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
  const type = String(node?.type || '').toLowerCase();
  if (type === 'system') return 'universe';
  if (type === 'talkgroup' || type === 'channel' || type === 'hub') return 'group';
  if (type === 'subscriber') return 'radio';
  return ['universe', 'group', 'radio', 'aggregate'].includes(type) ? type : 'radio';
}

function nodeGeometryKind(node) {
  return nodeType(node);
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
  for (const candidate of inputLinks) {
    const source = endpointKey(candidate?.source);
    const target = endpointKey(candidate?.target);
    if (!source || !target || !included.has(source) || !included.has(target) || links.length >= maximumLinks) continue;
    links.push(candidate);
  }
  const labels = (Array.isArray(value.labels) ? value.labels : []).map((entry) =>
    typeof entry === 'object' && entry !== null ? nodeKey(entry) : String(entry)).filter((key) => included.has(key));
  const effects = (Array.isArray(value.effects) ? value.effects : []).slice(0, maximumEffects);
  return { ...value, nodes, links, labels, effects, rendererLimits: Object.freeze({
    inputNodes: inputNodes.length,
    inputLinks: inputLinks.length,
    inputEffects: Array.isArray(value.effects) ? value.effects.length : 0,
    suppressedNodes: Math.max(0, inputNodes.length - nodes.length),
    suppressedLinks: Math.max(0, inputLinks.length - links.length),
    suppressedEffects: Math.max(0, (Array.isArray(value.effects) ? value.effects.length : 0) - effects.length)
  }) };
}

/** Labels intentionally overlap; this helper only applies priority, clipping, and the hard budget. */
function chooseLabelPlacements(candidates, viewport, budget) {
  const width = Math.max(0, finite(viewport?.width));
  const height = Math.max(0, finite(viewport?.height));
  return (Array.isArray(candidates) ? candidates : []).map((candidate, index) => ({ ...candidate, __order: index }))
    .sort((left, right) => finite(right.priority) - finite(left.priority) || left.__order - right.__order)
    .map((candidate) => {
      const offset = candidate.offsets?.[0] || { x: finite(candidate.offsetX, 7), y: finite(candidate.offsetY) };
      const labelWidth = Math.max(1, finite(candidate.width, 80));
      const labelHeight = Math.max(1, finite(candidate.height, 22));
      const left = Math.round(finite(candidate.x) + finite(offset.x, 7));
      const top = Math.round(finite(candidate.y) - labelHeight / 2 + finite(offset.y));
      return { ...candidate, left, top,
        rectangle: { left, top, right: left + labelWidth, bottom: top + labelHeight },
        offsetIndex: Number.isSafeInteger(offset.index) ? offset.index : 0 };
    }).filter(({ rectangle }) => rectangle.right > 0 && rectangle.bottom > 0 && rectangle.left < width &&
      rectangle.top < height).slice(0, Math.max(0, Math.floor(finite(budget))))
    .map(({ __order, offsets, ...placement }) => placement);
}

function allocateParticles(links, budget) {
  let remaining = Math.max(0, Math.floor(finite(budget)));
  const allocation = new Map();
  const priority = (link) => normalizedLinkType(link) === 'tx' && link.active ? 4 :
    normalizedLinkType(link) === 'migration' ? 3 : normalizedLinkType(link) === 'activity' && link.active ? 2 : 0;
  const ordered = (Array.isArray(links) ? links : []).map((link, index) => ({ link, index,
    priority: priority(link) })).filter((entry) => entry.priority)
    .sort((left, right) => right.priority - left.priority || left.index - right.index);
  for (const { link, index } of ordered) {
    if (!remaining) break;
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
  if (['affiliation_arrival', 'migration', 'destination_highlight'].includes(effect?.type)) return 'arrival';
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

function labelZoomTier() { return 'all'; }
function labelEligible() { return true; }

function resolvedPalette(host, overrides = {}) {
  const root = host?.ownerDocument?.documentElement;
  const styles = root && typeof getComputedStyle === 'function' ? getComputedStyle(root) : null;
  const css = (preferred, fallbackName, fallback) => styles?.getPropertyValue(preferred)?.trim() ||
    styles?.getPropertyValue(fallbackName)?.trim() || fallback;
  return {
    background: overrides.background || css('--network-space', '--bg', '#090e13'),
    universe: overrides.universe || css('--network-system', '--accent', '#44b8aa'),
    group: overrides.group || css('--network-hub', '--link', '#75b9ff'),
    radio: overrides.radio || css('--network-radio', '--warning', '#e6a64c'),
    aggregate: overrides.aggregate || css('--network-space-muted', '--muted', '#9eabb6'),
    arrival: overrides.arrival || css('--network-arrival', '--warning', '#e6a64c'),
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

function createElement(documentValue, tag, className) {
  const element = documentValue.createElement(tag);
  if (className) element.className = className;
  return element;
}

function createUnavailableRenderer(host, surface, labelLayer, status, error, callbacks = {}, signal = null) {
  host.dataset.rendererState = 'unavailable';
  status.hidden = false;
  status.textContent = 'Network visualization is unavailable in this browser. Live activity continues in the inspector.';
  status.setAttribute('role', 'status');
  callbacks.onUnavailable?.(error);
  let disposed = false;
  const diagnostics = () => Object.freeze({
    available: false, disposed, layoutOwner: 'external', mode: '3d', frozen: false,
    nodes: 0, links: 0, labels: 0, steadyParticles: 0, pendingParticles: 0,
    animatedEffects: 0, contextLost: false, paused: false, autoRotateRequested: false,
    autoRotateEffective: false, arrangeMode: false,
    scope: { level: 'overview', universeKey: '', groupKey: '' }, camera: null, rendererMemory: null, limits: {}
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
    available: false, layoutOwner: 'external', setGraphData: diagnostics, refresh: () => {}, resize: () => {},
    setFrozen: Boolean, fitAll: () => false, focus: () => false,
    setPinned: () => false, pulse: () => false, frameScope: () => false, getCameraPose: () => null,
    restoreCameraPose: () => false, setNavigationScope: (value = {}) => value, setAutoRotate: Boolean,
    setArrange: Boolean, setPalette: () => {}, setReducedMotion: Boolean, setLabelBudget: () => 0,
    setParticleBudget: () => 0, clearEffects: () => {}, clear: () => {}, enterFullscreen: async () => false,
    exitFullscreen: async () => false, diagnostics, dispose
  });
  if (signal?.aborted) dispose();
  else signal?.addEventListener?.('abort', dispose, { once: true });
  return renderer;
}

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
  const maximumNodes = Math.min(config.render.hardNodes,
    Math.max(1, Math.floor(finite(options.maximumNodes, config.render.hardNodes))));
  const maximumLinks = Math.min(config.render.hardLinks,
    Math.max(0, Math.floor(finite(options.maximumLinks, config.render.hardLinks))));
  const effectLimit = Math.max(0, Math.floor(finite(config.state.hardPendingEffects)));
  let labelBudget = Math.min(config.render.hardLabels,
    Math.max(1, Math.floor(finite(options.labelBudget, config.render.hardLabels))));
  let particleBudget = Math.min(config.render.hardParticles,
    Math.max(0, Math.floor(finite(options.particleBudget, config.render.hardParticles))));
  let paletteOverrides = { ...(options.palette || {}) };
  let palette = resolvedPalette(host, paletteOverrides);
  let graph = null;
  let controls = null;
  let scene = null;
  let rendererCanvas = null;
  let disposed = false;
  let frozen = Boolean(options.frozen);
  let paused = false;
  let contextLost = false;
  let arrangeMode = Boolean(options.arrange);
  let autoRotateRequested = options.autoRotate ?? animation.autoRotateDefault ?? true;
  let autoRotatePaused = false;
  let programmaticCamera = false;
  let reducedMotionOverride = typeof options.reducedMotion === 'boolean' ? options.reducedMotion : null;
  let navigationScope = { level: 'overview', universeKey: '', groupKey: '' };
  let currentGeneration = options.generation ?? null;
  let currentDocument = { nodes: [], links: [], labels: [], effects: [], rendererLimits: {} };
  let currentStructure = '';
  let requestedLabelKeys = [];
  let particleAllocation = new Map();
  let labelFrame = null;
  let cameraTweenFrame = null;
  let autoRotateTimer = null;
  let effectTimer = null;
  let initialFrameScheduled = false;
  let lastOffscreenActivityCount = -1;
  let nextPulseId = 1;
  let resizeObserver = null;
  let resizeFallback = null;
  let themeObserver = null;
  let mediaQuery = null;

  const renderNodes = new Map();
  const renderLinks = new Map();
  const nodeObjects = new Map();
  const linkObjects = new Map();
  const labelElements = new Map();
  const dragging = new Map();
  const geometries = new Map();
  const materials = new Map();
  const sharedResources = new Map();
  const nodeEffects = new Map();
  const seenEffects = new Map();
  const effectCooldowns = new Map();
  const pulseReservations = new Map();

  const protectShared = (resource) => {
    if (!resource || sharedResources.has(resource)) return resource;
    const actualDispose = typeof resource.dispose === 'function' ? resource.dispose.bind(resource) : null;
    sharedResources.set(resource, actualDispose);
    if (actualDispose) resource.dispose = () => {};
    return resource;
  };
  const disposeShared = () => {
    for (const [resource, actualDispose] of sharedResources) actualDispose?.();
    sharedResources.clear();
  };
  const ownDisposable = (resource) => {
    if (!resource || typeof resource.dispose !== 'function') return resource;
    const actualDispose = resource.dispose.bind(resource);
    let released = false;
    resource.dispose = () => {
      if (released) return;
      released = true;
      actualDispose();
    };
    return resource;
  };
  const prefersReducedMotion = () => reducedMotionOverride ?? Boolean(mediaQuery?.matches);

  const materialOpacity = (state) => ({ universe: 0.46, group: 0.66, radio: 0.72, aggregate: 0.56,
    quiet: 0.26, active: 0.98, afterglow: 0.58, arrival: 0.8, affiliation: 0.48,
    activity: 0.42, migration: 0.78, faded: 0.18, selected: 1, encrypted: 0.86 })[state] ?? 0.68;
  const materialColor = (state) => palette[state] || palette.radio;

  function meshMaterial(state) {
    const key = `mesh:${state}`;
    if (materials.has(key)) return materials.get(key).material;
    const material = protectShared(new library.MeshBasicMaterial({ color: materialColor(state),
      transparent: true, opacity: materialOpacity(state), wireframe: true, depthTest: true, depthWrite: false }));
    materials.set(key, { material, state, category: 'mesh' });
    return material;
  }

  function lineMaterial(state, dashed, encrypted = false) {
    const pattern = encrypted ? 'encrypted' : dashed ? 'dashed' : 'solid';
    const key = `line:${state}:${pattern}`;
    if (materials.has(key)) return materials.get(key).material;
    const material = protectShared(new library.LineMaterial({ color: materialColor(state), transparent: true,
      opacity: materialOpacity(state), depthWrite: false, linewidth: linkWidth({ type: state,
        active: state === 'active', afterglow: state === 'afterglow', faded: state === 'faded' }),
      worldUnits: false, dashed: pattern !== 'solid', dashScale: 1, dashSize: encrypted ? 2.4 : 8,
      gapSize: encrypted ? 2.4 : 5, alphaToCoverage: true }));
    material.dashed = pattern !== 'solid';
    material.resolution?.set(Math.max(1, surface.clientWidth || host.clientWidth || 1),
      Math.max(1, surface.clientHeight || host.clientHeight || 1));
    materials.set(key, { material, state, category: 'line' });
    return material;
  }

  function geometry(kind) {
    if (geometries.has(kind)) return geometries.get(kind);
    let value;
    if (kind === 'universe') value = new library.SphereGeometry(1, 16, 12);
    else if (kind === 'group') value = new library.BoxGeometry(1.55, 1.55, 1.55);
    else if (kind === 'aggregate') value = new library.IcosahedronGeometry(1, 0);
    else value = new library.CylinderGeometry(0, 1, 1.7, 3, 1, false);
    protectShared(value);
    geometries.set(kind, value);
    return value;
  }

  const particleGeometry = protectShared(new library.SphereGeometry(0.9, 6, 4));
  const particleMaterial = protectShared(new library.MeshBasicMaterial({ color: palette.active,
    transparent: true, opacity: 0.9, depthWrite: false }));
  geometries.set('particle', particleGeometry);
  materials.set('particle', { material: particleMaterial, state: 'active', category: 'particle' });

  function activeNodeEffect(key) {
    const effect = nodeEffects.get(key);
    if (!effect) return null;
    if (finite(effect.expiresAtMs) <= clock()) {
      nodeEffects.delete(key);
      return null;
    }
    return effect;
  }

  function applyNodeVisual(node, object = nodeObjects.get(nodeKey(node))) {
    if (!object) return;
    const kind = nodeGeometryKind(node);
    const state = nodeVisualState(node, activeNodeEffect(nodeKey(node)));
    const signature = `${kind}:${state}:${Boolean(node.selected)}:${Boolean(node.encrypted)}:` +
      `${node.visible !== false}:${nodeRadius(node)}`;
    if (object.userData.visualizerStyleSignature === signature) return;
    object.userData.visualizerStyleSignature = signature;
    const base = object.userData.visualizerBase;
    const selected = object.userData.visualizerSelected;
    const encrypted = object.userData.visualizerEncrypted;
    base.geometry = geometry(kind);
    base.material = meshMaterial(state);
    selected.geometry = base.geometry;
    selected.material = meshMaterial('selected');
    selected.visible = Boolean(node.selected);
    encrypted.geometry = base.geometry;
    encrypted.material = meshMaterial('encrypted');
    encrypted.visible = Boolean(node.encrypted || node.entity?.encrypted);
    object.scale.setScalar(nodeRadius(node));
    object.visible = node.visible !== false;
  }

  function createNodeObject(node) {
    const key = nodeKey(node);
    const kind = nodeGeometryKind(node);
    const object = new library.Group();
    const base = new library.Mesh(geometry(kind), meshMaterial(nodeVisualState(node)));
    const selected = new library.Mesh(geometry(kind), meshMaterial('selected'));
    const encrypted = new library.Mesh(geometry(kind), meshMaterial('encrypted'));
    selected.scale.setScalar(1.18);
    selected.visible = false;
    selected.raycast = () => {};
    encrypted.scale.setScalar(0.78);
    encrypted.rotation.y = Math.PI / 4;
    encrypted.visible = false;
    encrypted.raycast = () => {};
    object.add(base, selected, encrypted);
    object.userData.visualizerKey = key;
    object.userData.visualizerBase = base;
    object.userData.visualizerSelected = selected;
    object.userData.visualizerEncrypted = encrypted;
    nodeObjects.set(key, object);
    applyNodeVisual(node, object);
    return object;
  }

  function createLinkObject(link) {
    const key = linkKey(link);
    const linkGeometry = ownDisposable(new library.LineGeometry());
    linkGeometry.setPositions(new Float32Array((LINK_SEGMENTS + 1) * 3));
    const line = new library.Line2(linkGeometry,
      lineMaterial(linkVisualState(link), linkIsDashed(link), Boolean(link.encrypted)));
    line.computeLineDistances?.();
    line.userData.visualizerKey = key;
    line.userData.visualizerPath = '';
    linkObjects.set(key, line);
    return line;
  }

  function applyLinkVisual(link, object = linkObjects.get(linkKey(link))) {
    if (!object) return;
    const state = linkVisualState(link);
    const dashed = linkIsDashed(link);
    const encrypted = Boolean(link.encrypted);
    const signature = `${state}:${dashed}:${encrypted}:${link.visible !== false}`;
    if (object.userData.visualizerStyleSignature === signature) return;
    object.userData.visualizerStyleSignature = signature;
    object.material = lineMaterial(state, dashed, encrypted);
    object.visible = link.visible !== false;
  }

  function updateLinkPosition(object, start, end, link) {
    if (!object?.geometry?.setPositions) return false;
    const startX = finite(start?.x), startY = finite(start?.y), startZ = finite(start?.z);
    const endX = finite(end?.x), endY = finite(end?.y), endZ = finite(end?.z);
    const curvature = finite(link?.curvature,
      normalizedLinkType(link) === 'migration' ? (link?.curveDirection === -1 ? -0.18 : 0.18) : 0);
    const signature = `${startX}:${startY}:${startZ}:${endX}:${endY}:${endZ}:${curvature}`;
    if (object.userData.visualizerPath === signature) return true;
    object.userData.visualizerPath = signature;
    const deltaX = endX - startX, deltaY = endY - startY, deltaZ = endZ - startZ;
    const length = Math.hypot(deltaX, deltaY, deltaZ);
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
    const positions = new Float32Array((LINK_SEGMENTS + 1) * 3);
    for (let index = 0; index <= LINK_SEGMENTS; index += 1) {
      const progress = index / LINK_SEGMENTS;
      const inverse = 1 - progress;
      positions[index * 3] = inverse * inverse * startX + 2 * inverse * progress * controlX + progress * progress * endX;
      positions[index * 3 + 1] = inverse * inverse * startY + 2 * inverse * progress * controlY +
        progress * progress * endY;
      positions[index * 3 + 2] = inverse * inverse * startZ + 2 * inverse * progress * controlZ +
        progress * progress * endZ;
    }
    object.geometry.setPositions(positions);
    if (object.material?.dashed) object.computeLineDistances?.();
    object.geometry.computeBoundingBox?.();
    object.geometry.computeBoundingSphere?.();
    return true;
  }

  const renderedPosition = (node) => ({ x: finite(node?.x), y: finite(node?.y), z: finite(node?.z) });

  function synchronizeFromLiveSources() {
    const nodeFields = ['x', 'y', 'z', 'fx', 'fy', 'fz', 'pinned', 'selected', 'active', 'quiet', 'stale',
      'afterglow', 'pending', 'encrypted', 'visible', 'renderRadius', 'labelPriority', 'labelVisible'];
    for (const node of renderNodes.values()) {
      if (dragging.has(nodeKey(node))) continue;
      const source = node.__source;
      if (!source || source === node) continue;
      for (const field of nodeFields) {
        if (Object.hasOwn(source, field)) node[field] = source[field];
        else if (['fx', 'fy', 'fz'].includes(field)) delete node[field];
      }
    }
    const linkFields = ['active', 'afterglow', 'faded', 'dashed', 'encrypted', 'visible', 'curvature',
      'curveDirection', 'particleCount'];
    for (const link of renderLinks.values()) {
      const source = link.__source;
      if (!source || source === link) continue;
      for (const field of linkFields) if (Object.hasOwn(source, field)) link[field] = source[field];
    }
  }

  function synchronizeVisualPositions() {
    for (const node of renderNodes.values()) {
      if (dragging.has(nodeKey(node))) continue;
      const object = nodeObjects.get(nodeKey(node));
      if (!object) continue;
      const position = renderedPosition(node);
      object.position.set(position.x, position.y, position.z);
      Object.assign(node, position);
    }
    for (const link of renderLinks.values()) {
      const object = linkObjects.get(linkKey(link));
      const source = renderNodes.get(link.__sourceKey);
      const target = renderNodes.get(link.__targetKey);
      if (object && source && target) updateLinkPosition(object, renderedPosition(source), renderedPosition(target), link);
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
      nodeEffects.delete(key);
      dragging.delete(key);
      labelElements.get(key)?.remove();
      labelElements.delete(key);
    }
    return result;
  }

  function releaseLinkPulses(link) {
    for (const [id, reservation] of pulseReservations) {
      if (reservation.link !== link) continue;
      clearTimeout(reservation.timer);
      reservation.particle?.parent?.remove?.(reservation.particle);
      pulseReservations.delete(id);
    }
    const group = link?.__singleHopPhotonsObj;
    while (group?.children?.length) group.remove?.(group.children[0]);
    group?.parent?.remove?.(group);
    if (link) delete link.__singleHopPhotonsObj;
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
        rendered = { ...input, id: key, key, source, target };
        renderLinks.set(key, rendered);
      } else {
        Object.assign(rendered, input, { id: key, key });
        rendered.source = rendered.__sourceKey === source && typeof rendered.source === 'object' ? rendered.source : source;
        rendered.target = rendered.__targetKey === target && typeof rendered.target === 'object' ? rendered.target : target;
      }
      rendered.__sourceKey = source;
      rendered.__targetKey = target;
      rendered.__source = input;
      result.push(rendered);
    });
    for (const key of [...renderLinks.keys()]) {
      if (next.has(key)) continue;
      releaseLinkPulses(renderLinks.get(key));
      renderLinks.delete(key);
      linkObjects.delete(key);
    }
    return result;
  }

  const structureSignature = (nodes, links) => `${nodes.map(nodeKey).join('\u001f')}\u001e${links.map((link) =>
    `${linkKey(link)}:${link.__sourceKey}>${link.__targetKey}`).join('\u001f')}`;

  function updateParticleAllocation() {
    const next = prefersReducedMotion() ? new Map() : allocateParticles([...renderLinks.values()], particleBudget);
    const changed = next.size !== particleAllocation.size || [...next].some(([key, count]) =>
      particleAllocation.get(key) !== count);
    particleAllocation = next;
    return changed;
  }

  const effectNodeKey = (effect) => String(effect?.nodeKey || effect?.targetKey || effect?.sourceKey || '');
  function findEffectLink(effect) {
    if (effect?.linkKey && renderLinks.has(String(effect.linkKey))) return renderLinks.get(String(effect.linkKey));
    const source = String(effect?.sourceKey || '');
    const target = String(effect?.targetKey || '');
    return [...renderLinks.values()].find((link) => (!source || link.__sourceKey === source) &&
      (!target || link.__targetKey === target) && ['tx', 'activity', 'migration'].includes(normalizedLinkType(link))) || null;
  }

  function pulse(linkOrKey) {
    if (!graph || disposed || documentValue.hidden || prefersReducedMotion()) return false;
    const link = typeof linkOrKey === 'object' ? linkOrKey : renderLinks.get(String(linkOrKey));
    if (!link || !renderLinks.has(linkKey(link)) || pulseReservations.size >= particleBudget) return false;
    graph.emitParticle(link);
    const particle = link.__singleHopPhotonsObj?.children?.at?.(-1) || null;
    const id = nextPulseId++;
    const timer = setTimeout(() => {
      const reservation = pulseReservations.get(id);
      reservation?.particle?.parent?.remove?.(reservation.particle);
      pulseReservations.delete(id);
    }, Math.max(120, animation.particleFlightMs + 100));
    pulseReservations.set(id, { link, particle, timer });
    return true;
  }

  function scheduleEffectExpiry() {
    if (effectTimer !== null) clearTimeout(effectTimer);
    effectTimer = null;
    const now = clock();
    const expiries = [...nodeEffects.values()].map((effect) => finite(effect.expiresAtMs, now));
    if (!expiries.length) return;
    effectTimer = setTimeout(() => {
      effectTimer = null;
      const at = clock();
      for (const [key, effect] of nodeEffects) {
        if (finite(effect.expiresAtMs) > at) continue;
        nodeEffects.delete(key);
        const node = renderNodes.get(key);
        if (node) applyNodeVisual(node);
      }
      scheduleEffectExpiry();
    }, Math.max(16, Math.min(...expiries) - now));
  }

  function processEffects(effects) {
    const now = clock();
    for (const effect of effects) {
      const id = String(effect?.id || '');
      if (!id || seenEffects.has(id) || finite(effect.expiresAtMs, now + 1) <= now) continue;
      seenEffects.set(id, now);
      const visualKey = String(effect.coalesceKey ||
        `${effect.type}:${effectNodeKey(effect)}:${effect.sourceKey || ''}:${effect.targetKey || ''}`);
      const coalesced = now - finite(effectCooldowns.get(visualKey), -Infinity) < animation.effectCoalesceMs;
      effectCooldowns.set(visualKey, now);
      if (!coalesced && ['affiliation_arrival', 'migration', 'destination_highlight'].includes(effect.type)) {
        const key = effectNodeKey(effect);
        if (key && renderNodes.has(key)) nodeEffects.set(key, effect);
      }
      if (!coalesced && ['tx_pulse', 'migration'].includes(effect.type) && !prefersReducedMotion()) {
        const link = findEffectLink(effect);
        if (link) queueMicrotask(() => { if (!disposed) pulse(link); });
      }
    }
    while (seenEffects.size > Math.max(32, effectLimit * 2)) seenEffects.delete(seenEffects.keys().next().value);
    for (const [key, at] of effectCooldowns) {
      if (now - at > Math.max(5_000, animation.effectCoalesceMs * 4)) effectCooldowns.delete(key);
    }
    for (const node of renderNodes.values()) applyNodeVisual(node);
    scheduleEffectExpiry();
  }

  function clearEffects() {
    if (effectTimer !== null) clearTimeout(effectTimer);
    effectTimer = null;
    nodeEffects.clear();
    seenEffects.clear();
    effectCooldowns.clear();
    for (const reservation of pulseReservations.values()) {
      clearTimeout(reservation.timer);
      reservation.particle?.parent?.remove?.(reservation.particle);
    }
    pulseReservations.clear();
    for (const link of renderLinks.values()) releaseLinkPulses(link);
    for (const node of renderNodes.values()) applyNodeVisual(node);
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
    updateOffscreenActivityCount(width, height);
    const requested = [...new Set(requestedLabelKeys)].map((key, index) => ({
      node: renderNodes.get(key), rank: requestedLabelKeys.length - index
    })).filter(({ node }) => node && node.labelVisible !== false)
      .sort((left, right) => Number(Boolean(right.node.selected)) - Number(Boolean(left.node.selected)) ||
        Number(Boolean(right.node.active)) - Number(Boolean(left.node.active)) || right.rank - left.rank)
      .slice(0, labelBudget);
    const visible = new Set();
    for (const { node } of requested) {
      const key = nodeKey(node);
      let screen;
      const position = renderedPosition(node);
      try { screen = graph.graph2ScreenCoords(position.x, position.y, position.z); } catch (_) { continue; }
      if (!Number.isFinite(screen?.x) || !Number.isFinite(screen?.y)) continue;
      let element = labelElements.get(key);
      if (!element) {
        element = createElement(documentValue, 'span', 'network-visualizer-label');
        element.dataset.key = key;
        element.addEventListener('pointerdown', (event) => event.stopPropagation());
        element.addEventListener('click', (event) => {
          event.stopPropagation();
          const rendered = renderNodes.get(element.dataset.key);
          if (rendered) callbacks.onNodeClick?.(rendered.__source || rendered, event);
        });
        labelElements.set(key, element);
        labelLayer.append(element);
      }
      visible.add(key);
      element.textContent = `${node.encrypted ? '🔒 ' : ''}` +
        String(node.label || node.entity?.displayName || node.entity?.name || key);
      element.dataset.type = nodeType(node);
      element.dataset.active = String(Boolean(node.active));
      element.dataset.pending = String(Boolean(node.pending));
      element.dataset.selected = String(Boolean(node.selected));
      element.dataset.quiet = String(Boolean(node.quiet));
      element.dataset.visible = 'true';
      element.style.transform = `translate3d(${Math.round(screen.x + nodeRadius(node) + 7)}px, ` +
        `${Math.round(screen.y - 14)}px, 0)`;
      element.hidden = false;
    }
    for (const [key, element] of labelElements) {
      if (visible.has(key)) continue;
      element.remove();
      labelElements.delete(key);
    }
  }

  function scheduleLabels() {
    if (labelFrame !== null || disposed || documentValue.hidden) return;
    labelFrame = requestAnimationFrame(renderLabels);
  }

  function refresh(optionsValue = {}) {
    if (!graph || disposed) return;
    synchronizeFromLiveSources();
    for (const node of renderNodes.values()) applyNodeVisual(node);
    for (const link of renderLinks.values()) applyLinkVisual(link);
    synchronizeVisualPositions();
    if (optionsValue.rebuild === true) graph.refresh();
    scheduleLabels();
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
    if (structureChanged) queueMicrotask(() => { if (!disposed) refresh(); });
    else if (particlesChanged) graph.refresh();
    if (nodes.length && !initialFrameScheduled && options.autoFitOnFirstData !== false) {
      initialFrameScheduled = true;
      queueMicrotask(() => { if (!disposed && renderNodes.size) frameScope({ hero: true }); });
    }
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
    for (const { material, category } of materials.values()) {
      if (category === 'line') material.resolution?.set(width, height);
    }
    scheduleLabels();
  }

  function clearAutoRotateTimer() {
    if (autoRotateTimer !== null) clearTimeout(autoRotateTimer);
    autoRotateTimer = null;
  }

  function updateAutoRotate() {
    if (!controls) return false;
    const effective = Boolean(autoRotateRequested && !autoRotatePaused && !programmaticCamera && !arrangeMode &&
      dragging.size === 0 && !prefersReducedMotion() && !documentValue.hidden && renderNodes.size);
    controls.autoRotate = effective;
    controls.autoRotateSpeed = animation.autoRotateSpeed;
    callbacks.onAutoRotateState?.({ requested: autoRotateRequested, effective,
      paused: autoRotateRequested && !effective });
    return effective;
  }

  function scheduleAutoRotateResume() {
    clearAutoRotateTimer();
    if (!autoRotateRequested || prefersReducedMotion() || documentValue.hidden || arrangeMode || dragging.size) {
      updateAutoRotate();
      return;
    }
    autoRotatePaused = true;
    updateAutoRotate();
    autoRotateTimer = setTimeout(() => {
      autoRotateTimer = null;
      autoRotatePaused = false;
      updateAutoRotate();
    }, animation.autoRotateIdleDelayMs);
  }

  function stopCameraTween({ resume = true } = {}) {
    if (cameraTweenFrame !== null) cancelAnimationFrame(cameraTweenFrame);
    cameraTweenFrame = null;
    programmaticCamera = false;
    if (resume) scheduleAutoRotateResume();
    else updateAutoRotate();
  }

  function cameraPose() {
    if (!graph || !controls) return null;
    const camera = graph.camera();
    return Object.freeze({ position: Object.freeze({ x: finite(camera.position.x), y: finite(camera.position.y),
      z: finite(camera.position.z) }), target: Object.freeze({ x: finite(controls.target.x),
      y: finite(controls.target.y), z: finite(controls.target.z) }) });
  }

  function tweenCamera(position, target, requestedDuration = animation.cameraTransitionMs) {
    if (!graph || disposed || !controls) return false;
    stopCameraTween({ resume: false });
    clearAutoRotateTimer();
    autoRotatePaused = true;
    programmaticCamera = true;
    updateAutoRotate();
    const camera = graph.camera();
    const fromPosition = { x: finite(camera.position.x), y: finite(camera.position.y), z: finite(camera.position.z) };
    const fromTarget = { x: finite(controls.target.x), y: finite(controls.target.y), z: finite(controls.target.z) };
    const toPosition = { x: finite(position?.x), y: finite(position?.y), z: finite(position?.z) };
    const toTarget = { x: finite(target?.x), y: finite(target?.y), z: finite(target?.z) };
    const duration = prefersReducedMotion() ? 0 : Math.max(0, finite(requestedDuration));
    const apply = (progress) => {
      const eased = easeInOutCubic(progress);
      camera.position.set(fromPosition.x + (toPosition.x - fromPosition.x) * eased,
        fromPosition.y + (toPosition.y - fromPosition.y) * eased,
        fromPosition.z + (toPosition.z - fromPosition.z) * eased);
      controls.target.set(fromTarget.x + (toTarget.x - fromTarget.x) * eased,
        fromTarget.y + (toTarget.y - fromTarget.y) * eased,
        fromTarget.z + (toTarget.z - fromTarget.z) * eased);
      camera.lookAt?.(controls.target);
      controls.update?.();
      scheduleLabels();
    };
    if (!duration) {
      apply(1);
      programmaticCamera = false;
      scheduleAutoRotateResume();
      return true;
    }
    const startedAt = performance.now();
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
        scheduleAutoRotateResume();
      }
    };
    cameraTweenFrame = requestAnimationFrame(frame);
    return true;
  }

  function scopeBounds(nodes) {
    if (!nodes.length) return null;
    let minimumX = Infinity, minimumY = Infinity, minimumZ = Infinity;
    let maximumX = -Infinity, maximumY = -Infinity, maximumZ = -Infinity;
    for (const node of nodes) {
      const position = renderedPosition(node);
      const radius = nodeRadius(node);
      minimumX = Math.min(minimumX, position.x - radius);
      minimumY = Math.min(minimumY, position.y - radius);
      minimumZ = Math.min(minimumZ, position.z - radius);
      maximumX = Math.max(maximumX, position.x + radius);
      maximumY = Math.max(maximumY, position.y + radius);
      maximumZ = Math.max(maximumZ, position.z + radius);
    }
    return { center: { x: (minimumX + maximumX) / 2, y: (minimumY + maximumY) / 2,
      z: (minimumZ + maximumZ) / 2 }, radius: Math.max(1,
      Math.hypot(maximumX - minimumX, maximumY - minimumY, maximumZ - minimumZ) / 2) };
  }

  function frameScope(optionsValue = {}) {
    if (!graph || disposed || !renderNodes.size) return false;
    const keys = Array.isArray(optionsValue.keys) ? new Set(optionsValue.keys.map(String)) : null;
    const nodes = [...renderNodes.values()].filter((node) => node.visible !== false &&
      (!keys || keys.has(nodeKey(node))));
    const bounds = scopeBounds(nodes);
    if (!bounds) return false;
    const camera = graph.camera();
    const aspect = Math.max(0.25, (surface.clientWidth || host.clientWidth || 1) /
      Math.max(1, surface.clientHeight || host.clientHeight || 1));
    const verticalHalfFov = clamp(finite(camera.fov, 60), 25, 100) * Math.PI / 360;
    const horizontalHalfFov = Math.atan(Math.tan(verticalHalfFov) * aspect);
    const padding = clamp(finite(optionsValue.padding, 1.08), 0.75, 2);
    const distance = Math.max(70, bounds.radius * padding /
      Math.sin(Math.max(0.1, Math.min(verticalHalfFov, horizontalHalfFov))));
    const direction = new library.Vector3().copy(camera.position).sub(controls.target).normalize();
    if (!Number.isFinite(direction.x) || direction.lengthSq() < 0.001 || optionsValue.hero === true) {
      direction.set(0.72, 0.48, 1).normalize();
    }
    return tweenCamera({ x: bounds.center.x + direction.x * distance,
      y: bounds.center.y + direction.y * distance, z: bounds.center.z + direction.z * distance },
    bounds.center, optionsValue.duration ?? animation.cameraTransitionMs);
  }

  const fitAll = (duration = animation.cameraTransitionMs, padding = 24) =>
    frameScope({ duration, padding: 1 + Math.max(0, finite(padding, 24)) / 260 });
  function focus(keyOrNode, duration = animation.cameraTransitionMs) {
    const key = typeof keyOrNode === 'object' ? nodeKey(keyOrNode) : String(keyOrNode || '');
    return renderNodes.has(key) ? frameScope({ keys: [key], duration, padding: 1.3 }) : false;
  }
  const restoreCameraPose = (pose, duration = animation.cameraBackTransitionMs) =>
    pose?.position && pose?.target ? tweenCamera(pose.position, pose.target, duration) : false;

  function setNavigationScope(value = {}) {
    const level = value.level === 'group' || value.level === 'talkgroup' ? 'group' :
      value.level === 'system' ? 'system' : 'overview';
    navigationScope = { level, universeKey: level === 'overview' ? '' : String(value.universeKey || ''),
      groupKey: level === 'group' ? String(value.groupKey || '') : '' };
    return { ...navigationScope };
  }
  function setAutoRotate(value) {
    autoRotateRequested = Boolean(value);
    autoRotatePaused = false;
    clearAutoRotateTimer();
    updateAutoRotate();
    return autoRotateRequested;
  }
  function setArrange(value) {
    arrangeMode = Boolean(value);
    graph?.enableNodeDrag?.(arrangeMode);
    dragging.clear();
    if (arrangeMode) {
      autoRotatePaused = true;
      clearAutoRotateTimer();
      updateAutoRotate();
    } else scheduleAutoRotateResume();
    return arrangeMode;
  }
  function setFrozen(value) {
    frozen = Boolean(value);
    callbacks.onFreezeChange?.(frozen);
    return frozen;
  }
  function setPinned(keyOrNode, pinned) {
    const key = typeof keyOrNode === 'object' ? nodeKey(keyOrNode) : String(keyOrNode || '');
    const node = renderNodes.get(key);
    if (!node) return false;
    node.pinned = Boolean(pinned);
    const position = renderedPosition(node);
    if (node.pinned) Object.assign(node, { fx: position.x, fy: position.y, fz: position.z });
    else { delete node.fx; delete node.fy; delete node.fz; }
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
    graph?.backgroundColor('transparent');
    refresh();
  }
  function setReducedMotion(value = null) {
    reducedMotionOverride = typeof value === 'boolean' ? value : null;
    const particlesChanged = updateParticleAllocation();
    if (prefersReducedMotion()) {
      stopCameraTween({ resume: false });
      clearAutoRotateTimer();
      autoRotatePaused = true;
      clearEffects();
    } else scheduleAutoRotateResume();
    updateAutoRotate();
    if (particlesChanged) graph?.refresh();
    return prefersReducedMotion();
  }
  async function enterFullscreen() {
    if (disposed || typeof host.requestFullscreen !== 'function') return false;
    try { await host.requestFullscreen(); return true; } catch (error) {
      callbacks.onFullscreenError?.(error); return false;
    }
  }
  async function exitFullscreen() {
    if (documentValue.fullscreenElement !== host || typeof documentValue.exitFullscreen !== 'function') return false;
    try { await documentValue.exitFullscreen(); return true; } catch (error) {
      callbacks.onFullscreenError?.(error); return false;
    }
  }

  function clear() {
    stopCameraTween({ resume: false });
    clearAutoRotateTimer();
    clearEffects();
    currentDocument = { nodes: [], links: [], labels: [], effects: [], rendererLimits: {} };
    currentStructure = '';
    requestedLabelKeys = [];
    renderNodes.clear();
    renderLinks.clear();
    nodeObjects.clear();
    linkObjects.clear();
    dragging.clear();
    particleAllocation.clear();
    labelElements.forEach((element) => element.remove());
    labelElements.clear();
    graph?.graphData({ nodes: [], links: [] });
    setNavigationScope({ level: 'overview' });
    if (lastOffscreenActivityCount !== 0) callbacks.onOffscreenActivity?.({ count: 0 });
    lastOffscreenActivityCount = 0;
    updateAutoRotate();
  }

  function diagnostics() {
    let rendererMemory = null;
    try {
      const memory = graph?.renderer()?.info?.memory;
      if (memory) rendererMemory = { geometries: finite(memory.geometries), textures: finite(memory.textures) };
    } catch (_) { /* Context loss can make this unavailable. */ }
    return Object.freeze({ available: Boolean(graph), disposed, layoutOwner: 'external', mode: '3d', frozen,
      nodes: renderNodes.size, links: renderLinks.size, labels: labelElements.size,
      steadyParticles: [...particleAllocation.values()].reduce((sum, count) => sum + count, 0),
      pendingParticles: pulseReservations.size, animatedEffects: nodeEffects.size, contextLost, paused,
      autoRotateRequested, autoRotateEffective: Boolean(controls?.autoRotate), arrangeMode,
      scope: { ...navigationScope }, camera: cameraPose(), rendererMemory,
      limits: currentDocument.rendererLimits || {} });
  }

  const onControlsStart = () => {
    stopCameraTween({ resume: false });
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
      stopCameraTween({ resume: false });
      graph.pauseAnimation();
      paused = true;
      if (labelFrame !== null) cancelAnimationFrame(labelFrame);
      labelFrame = null;
      clearEffects();
    } else {
      graph.resumeAnimation();
      paused = false;
      refresh();
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
      .showNavInfo(false).backgroundColor('transparent').nodeId('id').nodeVal(1)
      .nodeThreeObject((node) => nodeObjects.get(nodeKey(node)) || createNodeObject(node))
      .nodeThreeObjectExtend(false).linkSource('source').linkTarget('target')
      .linkThreeObject((link) => linkObjects.get(linkKey(link)) || createLinkObject(link))
      .linkThreeObjectExtend(false)
      .linkPositionUpdate((object, coordinates, link) => updateLinkPosition(object, coordinates.start,
        coordinates.end, link))
      .linkDirectionalParticles((link) => particleAllocation.get(linkKey(link)) || 0)
      .linkDirectionalParticleWidth(1.6)
      .linkDirectionalParticleSpeed(() => prefersReducedMotion() ? 0 : 0.012)
      .linkDirectionalParticleColor(() => palette.active)
      .linkDirectionalParticleThreeObject(() => new library.Mesh(particleGeometry, particleMaterial))
      .onNodeClick((node, event) => callbacks.onNodeClick?.(node.__source || node, event))
      .onBackgroundClick((event) => callbacks.onBackgroundClick?.(event))
      .onNodeDrag((node, translate = {}) => {
        const key = nodeKey(node);
        if (!dragging.has(key)) {
          autoRotatePaused = true;
          clearAutoRotateTimer();
          updateAutoRotate();
          dragging.set(key, { x: finite(node.x) - finite(translate.x), y: finite(node.y) - finite(translate.y),
            z: finite(node.z) - finite(translate.z) });
        }
        callbacks.onNodeDrag?.({ key, position: renderedPosition(node),
          delta: { x: finite(translate.x), y: finite(translate.y), z: finite(translate.z) },
          node: node.__source || node });
        synchronizeVisualPositions();
        scheduleLabels();
      })
      .onNodeDragEnd((node) => {
        const key = nodeKey(node);
        const start = dragging.get(key) || renderedPosition(node);
        const position = renderedPosition(node);
        dragging.delete(key);
        callbacks.onNodeDragEnd?.({ key, position,
          delta: { x: position.x - start.x, y: position.y - start.y, z: position.z - start.z },
          node: node.__source || node });
        if (!arrangeMode) scheduleAutoRotateResume();
      })
      .onEngineTick(() => { synchronizeVisualPositions(); scheduleLabels(); })
      .cooldownTicks(0).cooldownTime(0);
    graph.d3Force('link', null);
    graph.d3Force('charge', null);
    graph.d3Force('center', null);
    graph.numDimensions(3);
    scene = graph.scene();
    controls = graph.controls();
    if (controls) {
      Object.assign(controls, { autoRotate: false, autoRotateSpeed: animation.autoRotateSpeed,
        enableDamping: true, dampingFactor: 0.07, rotateSpeed: 0.45, zoomSpeed: 0.65,
        enablePan: true, enableZoom: true, enableRotate: true, screenSpacePanning: true, zoomToCursor: true });
      if (library.MOUSE && library.TOUCH && controls.mouseButtons && controls.touches) {
        controls.mouseButtons.LEFT = library.MOUSE.ROTATE;
        controls.mouseButtons.MIDDLE = library.MOUSE.DOLLY;
        controls.mouseButtons.RIGHT = library.MOUSE.PAN;
        controls.touches.ONE = library.TOUCH.ROTATE;
        controls.touches.TWO = library.TOUCH.DOLLY_ROTATE;
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
    try { graph?._destructor?.(); } catch (_) { /* Partial renderer. */ }
    disposeShared();
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
  options.signal?.addEventListener?.('abort', abort, { once: true });

  function dispose() {
    if (disposed) return;
    disposed = true;
    if (labelFrame !== null) cancelAnimationFrame(labelFrame);
    if (cameraTweenFrame !== null) cancelAnimationFrame(cameraTweenFrame);
    if (autoRotateTimer !== null) clearTimeout(autoRotateTimer);
    if (effectTimer !== null) clearTimeout(effectTimer);
    labelFrame = cameraTweenFrame = autoRotateTimer = effectTimer = null;
    clearEffects();
    resizeObserver?.disconnect();
    if (resizeFallback) window.removeEventListener('resize', resizeFallback);
    themeObserver?.disconnect();
    mediaQuery?.removeEventListener?.('change', onReducedMotionChange);
    documentValue.removeEventListener('visibilitychange', onVisibilityChange);
    documentValue.removeEventListener('fullscreenchange', onFullscreenChange);
    options.signal?.removeEventListener?.('abort', abort);
    controls?.removeEventListener('start', onControlsStart);
    controls?.removeEventListener('end', onControlsEnd);
    controls?.removeEventListener('change', onControlsChange);
    rendererCanvas?.removeEventListener('webglcontextlost', onContextLost, false);
    rendererCanvas?.removeEventListener('webglcontextrestored', onContextRestored, false);
    const ownedScene = scene;
    let ownedWebglRenderer = null;
    try { ownedWebglRenderer = graph?.renderer?.() || null; } catch (_) { /* Continue teardown. */ }
    try { graph?._destructor?.(); } catch (_) { /* Continue teardown. */ }
    try { ownedWebglRenderer?.forceContextLoss?.(); } catch (_) { /* Already released. */ }
    if (globalThis.scene === ownedScene) {
      try { delete globalThis.scene; } catch (_) { globalThis.scene = undefined; }
    }
    linkObjects.forEach((object) => object.geometry?.dispose?.());
    disposeShared();
    labelElements.forEach((element) => element.remove());
    renderNodes.clear(); renderLinks.clear(); nodeObjects.clear(); linkObjects.clear(); labelElements.clear();
    dragging.clear(); geometries.clear(); materials.clear(); nodeEffects.clear(); seenEffects.clear();
    effectCooldowns.clear(); particleAllocation.clear();
    currentDocument = { nodes: [], links: [], labels: [], effects: [], rendererLimits: {} };
    requestedLabelKeys = [];
    graph = controls = scene = rendererCanvas = resizeObserver = resizeFallback = themeObserver = mediaQuery = null;
    surface.remove();
    labelLayer.remove();
    status.remove();
    delete host.dataset.rendererState;
  }

  resize();
  if (documentValue.hidden) onVisibilityChange();
  if (options.graphData) setGraphData(options.graphData);

  return Object.freeze({
    available: true, layoutOwner: 'external', setGraphData, refresh, resize, setFrozen, setPinned,
    setPalette, setReducedMotion,
    setLabelBudget: (value) => {
      labelBudget = Math.min(config.render.hardLabels, Math.max(1, Math.floor(finite(value, labelBudget))));
      scheduleLabels();
      return labelBudget;
    },
    setParticleBudget: (value) => {
      particleBudget = Math.min(config.render.hardParticles, Math.max(0, Math.floor(finite(value, particleBudget))));
      if (updateParticleAllocation()) graph.refresh();
      return particleBudget;
    },
    fitAll, frameScope, focus, getCameraPose: cameraPose, restoreCameraPose, setNavigationScope,
    setAutoRotate, setArrange, pulse, clearEffects, clear, enterFullscreen, exitFullscreen, diagnostics, dispose
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
