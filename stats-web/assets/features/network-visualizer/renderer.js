'use strict';

import { resolveP25NodeAttention, resolveP25LabelTier, isP25LinkDimmed } from './emphasis.js?v=1';

const VENDOR_ASSET = '../../vendor/network-visualizer-vendor.js?v=4';
const SYSTEM_RADIUS = 300;
const LABEL_LIMIT = 90;
const AUTO_ROTATE_SPEED = 0.34;
const FOCUS_FADE_MS = 220;
const SYSTEM_FOG_DEPTH = 0.83;
const AFFILIATION_MOVE_MS = 1_100;

let vendorPromise;
const loadVendor = () => vendorPromise ||= import(new URL(VENDOR_ASSET, import.meta.url).href);

function finite(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function clamp(value, minimum, maximum) {
  return Math.max(minimum, Math.min(maximum, value));
}

function ease(value) {
  const progress = clamp(value, 0, 1);
  return progress < 0.5 ? 4 * progress ** 3 : 1 - (-2 * progress + 2) ** 3 / 2;
}

function calculateFocusBounds(keys, nodes) {
  const values = [...new Set(keys)].map((key) => nodes.get(String(key))).filter(Boolean);
  if (!values.length) return null;
  const center = values.reduce((sum, node) => ({ x: sum.x + node.x, y: sum.y + node.y, z: sum.z + node.z }),
    { x: 0, y: 0, z: 0 });
  center.x /= values.length;
  center.y /= values.length;
  center.z /= values.length;
  const radius = Math.max(12, ...values.map((node) => Math.hypot(node.x - center.x, node.y - center.y,
    node.z - center.z) + finite(node.radius, 8)));
  return { center, radius };
}

function element(documentValue, tag, className = '') {
  const value = documentValue.createElement(tag);
  if (className) value.className = className;
  return value;
}

function nodeState(node) {
  return String(node?.signalAction || (node?.active ? 'call' : '') || node?.type || 'radio').toLowerCase();
}

function createUnavailable(host, message, signal) {
  const status = element(host.ownerDocument, 'div', 'network-visualizer-renderer-status');
  status.setAttribute('role', 'status');
  status.textContent = message;
  host.append(status);
  let disposed = false;
  const dispose = () => {
    if (disposed) return;
    disposed = true;
    status.remove();
  };
  signal?.addEventListener?.('abort', dispose, { once: true });
  return Object.freeze({ available: false, setData: () => {}, setMode: () => {}, setCameraPhase: () => {},
    enterSystem: () => false, enterTalkgroup: () => false, showOverview: () => false, focus: () => false,
    home: () => false, focusAffiliation: () => false, resetAttention: () => {},
    moveManualCamera: () => false, enterFullscreen: async () => false, exitFullscreen: async () => false,
    setGuideSuspended: () => {}, dispose });
}

async function createP25Renderer(options = {}) {
  const host = options.host;
  if (!(host instanceof Element)) throw new TypeError('A P25 Visualizer renderer host is required.');
  const documentValue = host.ownerDocument;
  const surface = element(documentValue, 'div', 'network-visualizer-graph-surface');
  const labels = element(documentValue, 'div', 'network-visualizer-label-layer');
  host.append(surface, labels);

  let library;
  try {
    library = options.library || await (options.vendorLoader || loadVendor)();
  } catch (_) {
    surface.remove();
    labels.remove();
    return createUnavailable(host, 'P25 visualization is unavailable in this browser. Live activity remains available on the Live page.',
      options.signal);
  }

  const palette = () => {
    const styles = getComputedStyle(host);
    const color = (name, fallback) => styles.getPropertyValue(name).trim() || fallback;
    return {
      background: color('--network-space', '#081019'),
      system: color('--network-system', '#73a8bc'),
      talkgroup: color('--network-hub', '#8fc4d3'),
      radio: color('--network-radio', '#78909c'),
      current: color('--network-affiliation', '#d59a46'),
      activity: color('--network-activity', '#80bfe5'),
      history: color('--network-space-muted', '#8ea1ac'),
      call: color('--network-tx', '#60df98'),
      movement: color('--network-arrival', '#ffc366'),
      emergency: color('--network-emergency', '#ff7d72'),
      denial: color('--network-denial', '#f38eae'),
      check: color('--network-check', '#d2a06d'),
      page: color('--network-page', '#bc91c1'),
      busy: color('--network-busy', '#e6a17f'),
      queued: color('--chart-queued', '#c9b86a'),
      logout: color('--chart-logout', '#d1ae50'),
      patch: color('--chart-patch', '#7fa866')
    };
  };

  let colors = palette();
  let graph = null;
  let controls = null;
  let scene = null;
  let canvas = null;
  let disposed = false;
  let guideSuspended = false;
  let frame = 0;
  let cameraFrame = 0;
  let resizeObserver = null;
  let themeObserver = null;
  let graphDirty = false;
  let themeDirty = false;
  let refreshPending = false;
  let mode = options.mode === 'manual' ? 'manual' : 'auto';
  let cameraPhase = 'roam';
  let scope = { level: 'overview', systemKey: '' };
  let interactionUntil = 0;
  let programmatic = false;
  let focusKeys = new Set();
  let focusAmount = 0;
  let focusTarget = 0;
  let focusFrom = 0;
  let focusStartedAt = 0;
  const nodes = new Map();
  const links = new Map();
  const nodeObjects = new Map();
  const linkObjects = new Map();
  const labelElements = new Map();
  const interactingLabels = new Set();
  const labelGestureReleases = new Map();
  let labelRevision = 0;
  let labelPointer = null;
  let hoveredLabelKey = '';
  let nativeHoveredLabelKey = '';
  let hoveredShapeKey = '';
  let pointerInsideHost = false;
  let pointerInsideShapeSurface = false;
  let shapeHoverAt = -Infinity;
  const shapeRaycaster = new library.Raycaster();
  const affiliationMoves = new Map();
  const movementParticipants = new Set();
  let labelCandidates = [];
  const canonicalPositions = new Map();
  let canonicalLinks = [];
  const geometries = new Map();
  const materials = new Map();

  const renderingPaused = () => documentValue.hidden || guideSuspended;

  function colorFor(state) {
    return colors[state] || colors.radio;
  }

  function nodeAttention(node) {
    return resolveP25NodeAttention(node, focusKeys, movementParticipants);
  }

  function opacityRange(state, clear, dimmed) {
    const base = state === 'system' ? 0.48 : clear ? 0.98 : 0.76;
    return { base, dim: dimmed ? state === 'system' ? 0.12 : 0.05 : base };
  }

  function material(state, optionsValue = {}) {
    const side = optionsValue.side === library.BackSide ? 'back' : 'front';
    const clear = optionsValue.clear === true;
    const dimmed = optionsValue.dimmed === true;
    const key = `${state}:${side}:${clear}:${dimmed}`;
    if (materials.has(key)) return materials.get(key);
    const { base: baseOpacity, dim: dimOpacity } = opacityRange(state, clear, dimmed);
    const value = new library.MeshBasicMaterial({ color: colorFor(state), wireframe: true, transparent: true,
      opacity: baseOpacity + (dimOpacity - baseOpacity) * focusAmount, depthTest: true, depthWrite: false,
      fog: !clear });
    if (side === 'back') value.side = library.BackSide;
    value.userData.p25BaseOpacity = baseOpacity;
    value.userData.p25DimOpacity = dimOpacity;
    materials.set(key, value);
    return value;
  }

  function geometry(type) {
    if (geometries.has(type)) return geometries.get(type);
    const value = type === 'system' ? new library.SphereGeometry(1, 28, 20) :
      type === 'talkgroup' ? new library.BoxGeometry(2, 2, 2) :
        new library.CylinderGeometry(0, 1, 2.2, 3);
    geometries.set(type, value);
    return value;
  }

  function applyNodeStyle(node, object = nodeObjects.get(node.id)) {
    if (!object) return;
    const state = nodeState(node);
    const { clear, dimmed } = nodeAttention(node);
    object.userData.p25Node = node;
    if (node.type === 'system') {
      object.children[0].material = material('system', { dimmed });
      object.children[1].material = material('system', { side: library.BackSide, dimmed });
    } else object.material = material(state, { clear, dimmed });
    object.scale.setScalar(finite(node.radius, node.type === 'talkgroup' ? 13 : 6.5));
  }

  function createNodeObject(node) {
    let object;
    if (node.type === 'system') {
      object = new library.Group();
      object.add(new library.Mesh(geometry('system'), material('system')),
        new library.Mesh(geometry('system'), material('system', { side: library.BackSide })));
    } else {
      object = new library.Mesh(geometry(node.type), material(nodeState(node), { clear: Boolean(node.signalAction) }));
      if (node.type === 'radio') object.rotation.z = (String(node.id).length % 6) * Math.PI / 9;
    }
    nodeObjects.set(node.id, object);
    applyNodeStyle(node, object);
    return object;
  }

  function lineMaterial(kind, dimmed = false) {
    const key = `line:${kind}:${dimmed}`;
    if (materials.has(key)) return materials.get(key);
    const historical = kind === 'history';
    const dashed = historical || kind === 'activity';
    const baseOpacity = historical ? 0.18 : 0.58;
    const dimOpacity = dimmed ? 0.012 : baseOpacity;
    const value = new library.LineMaterial({ color: colorFor(kind), linewidth: historical ? 1.25 : 2.5,
      transparent: true, opacity: baseOpacity + (dimOpacity - baseOpacity) * focusAmount,
      dashed, dashSize: 6, gapSize: 5,
      depthTest: true, depthWrite: false });
    value.userData.p25BaseOpacity = baseOpacity;
    value.userData.p25DimOpacity = dimOpacity;
    value.resolution?.set(Math.max(1, host.clientWidth), Math.max(1, host.clientHeight));
    materials.set(key, value);
    return value;
  }

  function createLinkObject(link) {
    const geometryValue = new library.LineGeometry();
    geometryValue.setPositions([0, 0, 0, 0, 0, 0]);
    const object = new library.Line2(geometryValue, lineMaterial(link.kind, linkIsDimmed(link)));
    if (link.kind === 'history' || link.kind === 'activity') object.computeLineDistances?.();
    linkObjects.set(link.id, object);
    return object;
  }

  function linkIsDimmed(link) {
    return isP25LinkDimmed(link, focusKeys);
  }

  function applyLinkStyle(link, object = linkObjects.get(link.id)) {
    if (object) object.material = lineMaterial(link.kind, linkIsDimmed(link));
  }

  function updateFocusOpacity() {
    if (renderingPaused()) { graphDirty = true; return; }
    materials.forEach((value) => {
      const base = Number(value.userData?.p25BaseOpacity);
      const dim = Number(value.userData?.p25DimOpacity);
      if (Number.isFinite(base) && Number.isFinite(dim)) value.opacity = base + (dim - base) * focusAmount;
    });
  }

  function applyFocusStyles() {
    if (renderingPaused()) { graphDirty = true; return; }
    movementParticipants.clear();
    for (const [radioKey, trip] of affiliationMoves) {
      for (const key of [radioKey, trip.change.fromGroupKey, trip.change.toGroupKey]) movementParticipants.add(key);
    }
    const priority = node => interactingLabels.has(labelElements.get(node.id)) ? 7 : focusKeys.has(node.id) ? 6 : node.signalAction || node.active ? 5 :
      node.type === 'system' ? 4 : node.type === 'talkgroup' ? 3 : 1;
    labelCandidates = [...nodes.values()].sort((left, right) => priority(right) - priority(left)).slice(0, LABEL_LIMIT);
    nodes.forEach((node) => applyNodeStyle(node));
    links.forEach((link) => applyLinkStyle(link));
  }

  function transitionFocus(value) {
    const target = value ? 1 : 0;
    if (focusTarget === target) return;
    focusFrom = focusAmount;
    focusTarget = target;
    focusStartedAt = performance.now();
  }

  function setFocus(keys) {
    focusKeys = new Set(keys.map(String));
    applyFocusStyles();
    transitionFocus(true);
  }

  function clearFocus(immediate = false) {
    if (immediate) {
      focusKeys.clear();
      focusAmount = focusTarget = focusFrom = 0;
      applyFocusStyles();
      updateFocusOpacity();
    } else transitionFocus(false);
  }

  function updateLinkPosition(object, start, end, link) {
    object.geometry.setPositions([finite(start?.x), finite(start?.y), finite(start?.z),
      finite(end?.x), finite(end?.y), finite(end?.z)]);
    if (link.kind === 'history' || link.kind === 'activity') object.computeLineDistances?.();
    return true;
  }

  function pruneObjects(nextNodes, nextLinks) {
    const nodeIds = new Set(nextNodes.map((node) => node.id));
    const linkIds = new Set(nextLinks.map((link) => link.id));
    for (const [key, object] of nodeObjects) {
      if (nodeIds.has(key)) continue;
      object.removeFromParent?.();
      nodeObjects.delete(key);
      const label = labelElements.get(key);
      labelGestureReleases.get(label)?.();
      labelGestureReleases.delete(label);
      label?.remove();
      labelElements.delete(key);
      affiliationMoves.delete(key);
    }
    for (const [key, object] of linkObjects) {
      if (linkIds.has(key)) continue;
      object.removeFromParent?.();
      object.geometry?.dispose?.();
      linkObjects.delete(key);
    }
  }

  function publishGraph() {
    graphDirty = true;
    if (!graph || disposed || renderingPaused()) return;
    graphDirty = false;
    const nextNodes = [...nodes.values()];
    const presented = canonicalLinks.filter(link => link.kind !== 'history' ||
      affiliationMoves.get(link.source)?.change.fromGroupKey !== link.target);
    for (const [radioKey, trip] of affiliationMoves) {
      presented.push({ id: `movement:${radioKey}:${trip.change.fromGroupKey}`, source: radioKey,
        target: trip.change.fromGroupKey, kind: 'movement' });
    }
    const nextLinks = presented.map((incoming) => {
      const previous = links.get(incoming.id);
      return previous ? Object.assign(previous, incoming) : { ...incoming };
    });
    pruneObjects(nextNodes, nextLinks);
    links.clear();
    nextLinks.forEach((link) => links.set(link.id, link));
    graph.graphData({ nodes: nextNodes, links: nextLinks });
    updateSystemFog();
    applyFocusStyles();
    updateFocusOpacity();
  }

  function clearAffiliationMoves() {
    const hadMoves = affiliationMoves.size > 0;
    for (const key of affiliationMoves.keys()) {
      const node = nodes.get(key), target = canonicalPositions.get(key);
      if (node && target) Object.assign(node, target, { fx: target.x, fy: target.y, fz: target.z });
    }
    affiliationMoves.clear();
    if (hadMoves && graph && !disposed) publishGraph();
    else applyFocusStyles();
  }

  function resetAttention() {
    releaseLabelGestures();
    stopCamera();
    clearAffiliationMoves();
    cameraPhase = 'roam';
    clearFocus(true);
    updateAutoRotate();
  }

  function setData(value = {}, optionsValue = {}) {
    if (!graph || disposed) return;
    const animate = optionsValue.animate !== false && !renderingPaused();
    const now = performance.now();
    labelRevision++;
    const incomingNodes = new Map((value.nodes || []).map((node) => [String(node.id), node]));
    const overviewChanged = scope.level === 'overview' && [...incomingNodes.values()].every(node => node.type === 'system') &&
      (incomingNodes.size !== nodes.size || [...incomingNodes.keys()].some(id => !nodes.has(id)));
    const incomingChanges = new Map((optionsValue.affiliationChanges || []).map((change) => [change.radioKey, change]));
    let cancelled = false;
    for (const [key, trip] of affiliationMoves) {
      if (!incomingNodes.has(key) || !incomingNodes.has(trip.change.fromGroupKey) ||
          !incomingNodes.has(trip.change.toGroupKey) ||
          (incomingNodes.get(key).affiliationGroupKey !== trip.change.toGroupKey &&
            incomingChanges.get(key)?.toGroupKey !== incomingNodes.get(key).affiliationGroupKey)) {
        affiliationMoves.delete(key);
        cancelled = true;
      }
    }
    if (cancelled || renderingPaused()) resetAttention();
    const startedMoves = new Set();
    if (animate) for (const change of optionsValue.affiliationChanges || []) {
      if (incomingNodes.get(change.radioKey)?.type !== 'radio' ||
          incomingNodes.get(change.fromGroupKey)?.type !== 'talkgroup' ||
          incomingNodes.get(change.toGroupKey)?.type !== 'talkgroup' ||
          incomingNodes.get(change.radioKey).affiliationGroupKey !== change.toGroupKey || !change.remainingMs) continue;
      const previous = nodes.get(change.radioKey);
      const trip = affiliationMoves.get(change.radioKey);
      if (trip?.change.id === change.id) continue;
      // Keep only the latest destination. Repeated data updates cannot restart this trip.
      const from = trip && previous ? { x: previous.x, y: previous.y, z: previous.z } : change.from;
      startedMoves.add(change.radioKey);
      affiliationMoves.set(change.radioKey, { change, from, startedAt: now,
        duration: Math.min(AFFILIATION_MOVE_MS, change.remainingMs) });
    }
    const nextNodes = [...incomingNodes].map(([id, incoming]) => {
      const previous = nodes.get(id);
      const target = { x: finite(incoming.x), y: finite(incoming.y), z: finite(incoming.z) };
      canonicalPositions.set(id, target);
      const trip = affiliationMoves.get(id);
      const position = trip ? !startedMoves.has(id) && previous ?
        { x: previous.x, y: previous.y, z: previous.z } : trip.from : target;
      return Object.assign(previous || {}, { active: false, signalAction: '', groupKey: '', affiliationGroupKey: '' },
        incoming, position, { id,
        fx: position.x, fy: position.y, fz: position.z });
    });
    nodes.clear();
    nextNodes.forEach((node) => nodes.set(node.id, node));
    for (const key of canonicalPositions.keys()) if (!nodes.has(key)) canonicalPositions.delete(key);
    canonicalLinks = (value.links || []).map((incoming) => ({ ...incoming, id: String(incoming.id),
      source: String(typeof incoming.source === 'object' ? incoming.source.id : incoming.source),
      target: String(typeof incoming.target === 'object' ? incoming.target.id : incoming.target) }))
      .filter((link) => nodes.has(link.source) && nodes.has(link.target));
    if (focusKeys.size && ![...focusKeys].some((key) => nodes.has(key))) resetAttention();
    publishGraph();
    if (overviewChanged && mode === 'auto') frameOverview(true);
  }

  function resize() {
    if (!graph || disposed || renderingPaused()) return;
    const width = Math.max(1, Math.floor(host.clientWidth));
    const height = Math.max(1, Math.floor(host.clientHeight));
    graph.width(width).height(height);
    materials.forEach((value, key) => key.startsWith('line:') && value.resolution?.set(width, height));
    if (scope.level === 'overview') frameOverview(true);
  }

  function systemNode() {
    return scope.level === 'system' || scope.level === 'talkgroup' ? nodes.get(scope.systemKey) : null;
  }

  function talkgroupNode() {
    return scope.level === 'talkgroup' ? nodes.get(scope.groupKey) : null;
  }

  function updateSystemFog() {
    if (!scene) return;
    if (renderingPaused()) { graphDirty = true; return; }
    if (scope.level === 'overview') { scene.fog = null; return; }
    const radius = finite(systemNode()?.radius, SYSTEM_RADIUS);
    const density = SYSTEM_FOG_DEPTH / Math.max(SYSTEM_RADIUS, radius);
    if (scene.fog) scene.fog.density = density;
    else scene.fog = new library.FogExp2(colors.background, density);
  }

  function containSystemCamera() {
    const system = systemNode();
    if (!system || !controls || !graph || renderingPaused()) return;
    // Framing a complete journey can require stepping outside the system sphere.
    if (focusKeys.size) { controls.maxDistance = Infinity; return; }
    const center = new library.Vector3(system.x, system.y, system.z);
    const camera = graph.camera();
    const offset = new library.Vector3().copy(camera.position).sub(center);
    const systemRadius = finite(system.radius, SYSTEM_RADIUS);
    const maximum = systemRadius * 0.88;
    if (offset.length() > maximum) camera.position.copy(center.clone().add(offset.setLength(maximum)));
    const targetOffset = new library.Vector3().copy(controls.target).sub(center);
    if (targetOffset.length() > systemRadius * 0.96) {
      controls.target.copy(center.clone().add(targetOffset.setLength(systemRadius * 0.96)));
    }
    camera.lookAt(controls.target);
    controls.maxDistance = maximum;
  }

  function updateAutoRotate() {
    if (!controls) return;
    controls.autoRotate = Boolean(mode === 'auto' && cameraPhase === 'roam' &&
      (scope.level === 'system' || scope.level === 'talkgroup') &&
      !programmatic && !interactingLabels.size && performance.now() >= interactionUntil && !documentValue.hidden && !guideSuspended);
    controls.autoRotateSpeed = AUTO_ROTATE_SPEED;
  }

  function cameraPose() {
    if (!graph || !controls) return null;
    const camera = graph.camera();
    return { position: { x: camera.position.x, y: camera.position.y, z: camera.position.z },
      target: { x: controls.target.x, y: controls.target.y, z: controls.target.z } };
  }

  function stopCamera() {
    if (cameraFrame) cancelAnimationFrame(cameraFrame);
    cameraFrame = 0;
    programmatic = false;
    if (controls) controls.enabled = true;
    updateAutoRotate();
  }

  function tweenCamera(position, target, duration = 900) {
    if (!graph || !controls || disposed || renderingPaused()) return false;
    stopCamera();
    const camera = graph.camera();
    const from = cameraPose();
    const toPosition = { x: finite(position.x), y: finite(position.y), z: finite(position.z) };
    const toTarget = { x: finite(target.x), y: finite(target.y), z: finite(target.z) };
    const milliseconds = Math.max(0, duration);
    const apply = (progress) => {
      const amount = ease(progress);
      camera.position.set(from.position.x + (toPosition.x - from.position.x) * amount,
        from.position.y + (toPosition.y - from.position.y) * amount,
        from.position.z + (toPosition.z - from.position.z) * amount);
      controls.target.set(from.target.x + (toTarget.x - from.target.x) * amount,
        from.target.y + (toTarget.y - from.target.y) * amount,
        from.target.z + (toTarget.z - from.target.z) * amount);
      camera.lookAt(controls.target);
      containSystemCamera();
    };
    if (!milliseconds) {
      apply(1);
      return true;
    }
    programmatic = true;
    controls.enabled = false;
    updateAutoRotate();
    const startedAt = performance.now();
    let previousAt = startedAt, heldMs = 0;
    const step = (at) => {
      cameraFrame = 0;
      if (disposed || documentValue.hidden || guideSuspended) return stopCamera();
      const elapsed = Math.max(0, at - previousAt);
      previousAt = at;
      if (interactingLabels.size) {
        heldMs += elapsed;
        cameraFrame = requestAnimationFrame(step);
        return;
      }
      const progress = clamp((at - startedAt - heldMs) / milliseconds, 0, 1);
      apply(progress);
      if (progress < 1) cameraFrame = requestAnimationFrame(step);
      else {
        programmatic = false;
        controls.enabled = true;
        updateAutoRotate();
      }
    };
    cameraFrame = requestAnimationFrame(step);
    return true;
  }

  function homePose(system) {
    const radius = finite(system?.radius, SYSTEM_RADIUS);
    return { position: { x: system.x + radius * 0.47, y: system.y + radius * 0.25,
      z: system.z + radius * 0.53 }, target: { x: system.x, y: system.y, z: system.z } };
  }

  function talkgroupHomePose(group, system) {
    const related = [...nodes.values()].filter((node) => node.type !== 'system');
    const radius = Math.max(34, ...related.map((node) => Math.hypot(node.x - group.x, node.y - group.y,
      node.z - group.z) + finite(node.radius, 8)));
    const distance = clamp(radius * 2.25, 86, 160);
    const inward = new library.Vector3(system.x - group.x, system.y - group.y, system.z - group.z);
    if (inward.lengthSq() < 0.001) inward.set(0.62, 0.34, 0.72);
    inward.normalize();
    return { position: { x: group.x + inward.x * distance, y: group.y + inward.y * distance,
      z: group.z + inward.z * distance }, target: { x: group.x, y: group.y, z: group.z } };
  }

  function home(duration = 900) {
    const system = systemNode();
    if (!system) return false;
    clearFocus();
    const group = talkgroupNode();
    const pose = group ? talkgroupHomePose(group, system) : homePose(system);
    return tweenCamera(pose.position, pose.target, duration);
  }

  function boundsFor(keys) {
    return calculateFocusBounds(keys, nodes);
  }

  function focus(keys, duration = 900) {
    if (mode !== 'auto' || renderingPaused() || !['system', 'talkgroup'].includes(scope.level)) return false;
    const bounds = boundsFor(Array.isArray(keys) ? keys : [keys]);
    return focusBounds(bounds, Array.isArray(keys) ? keys : [keys], duration);
  }

  function focusBounds(bounds, keys, duration) {
    const system = systemNode();
    if (!bounds || !system) return false;
    setFocus(keys);
    const inward = new library.Vector3(system.x - bounds.center.x, system.y - bounds.center.y,
      system.z - bounds.center.z);
    if (inward.lengthSq() < 0.001) inward.set(0.7, 0.35, 1);
    inward.normalize();
    const camera = graph.camera();
    const vertical = finite(camera.fov, 50) * Math.PI / 360;
    const horizontal = Math.atan(Math.tan(vertical) * Math.max(0.1, finite(camera.aspect, 1)));
    const distance = Math.max(64, bounds.radius / Math.sin(Math.min(vertical, horizontal)) * 1.18);
    return tweenCamera({ x: bounds.center.x + inward.x * distance, y: bounds.center.y + inward.y * distance,
      z: bounds.center.z + inward.z * distance }, bounds.center, duration);
  }

  function focusAffiliation(radioKey, duration = 600) {
    if (mode !== 'auto' || renderingPaused() || !['system', 'talkgroup'].includes(scope.level)) return false;
    const trip = affiliationMoves.get(String(radioKey));
    if (!trip) return false;
    const keys = [String(radioKey), trip.change.fromGroupKey, trip.change.toGroupKey];
    // Both cubes and the full path stay in the same view; the camera does not chase the radio.
    const framingMs = Math.min(Math.max(0, duration), Math.max(0, trip.change.remainingMs - trip.duration));
    if (!focusBounds(calculateFocusBounds(keys, nodes), keys, framingMs)) return false;
    trip.startedAt = performance.now() + framingMs;
    return true;
  }

  function frameOverview(immediate = false) {
    if (!nodes.size || !graph || !controls) return false;
    const systems = [...nodes.values()].filter(node => node.type === 'system');
    if (!systems.length) return false;
    const center = systems.reduce((sum, node) => ({ x: sum.x + node.x, y: sum.y + node.y, z: sum.z + node.z }),
      { x: 0, y: 0, z: 0 });
    center.x /= systems.length;
    center.y /= systems.length;
    center.z /= systems.length;
    // Keep the original floating positions and leave room for captions below the spheres.
    const radius = Math.max(80, ...systems.map(node => Math.hypot(node.x - center.x, node.y - center.y,
      node.z - center.z) + finite(node.radius, 180) * 1.5));
    const camera = graph.camera();
    const vertical = finite(camera.fov, 50) * Math.PI / 360;
    const aspect = Math.max(0.1, host.clientWidth / Math.max(1, host.clientHeight));
    const horizontal = Math.atan(Math.tan(vertical) * aspect);
    const distance = Math.max(260, radius / Math.sin(Math.min(vertical, horizontal)) * 1.18);
    const direction = new library.Vector3(0.72, 0.42, 1).normalize().multiplyScalar(distance);
    return tweenCamera({ x: center.x + direction.x, y: center.y + direction.y,
      z: center.z + direction.z }, center, immediate ? 0 : 900);
  }

  function enterSystem(systemKey, optionsValue = {}) {
    resetAttention();
    scope = { level: 'system', systemKey: String(systemKey || '') };
    updateSystemFog();
    const result = home(optionsValue.immediate ? 0 : finite(optionsValue.duration, 900));
    containSystemCamera();
    updateAutoRotate();
    return result;
  }

  function enterTalkgroup(systemKey, groupKey, optionsValue = {}) {
    resetAttention();
    scope = { level: 'talkgroup', systemKey: String(systemKey || ''), groupKey: String(groupKey || '') };
    updateSystemFog();
    const result = home(optionsValue.immediate ? 0 : finite(optionsValue.duration, 900));
    containSystemCamera();
    updateAutoRotate();
    return result;
  }

  function showOverview(optionsValue = {}) {
    resetAttention();
    scope = { level: 'overview', systemKey: '' };
    clearFocus();
    updateSystemFog();
    if (controls) controls.maxDistance = Infinity;
    updateAutoRotate();
    return frameOverview(optionsValue.immediate === true);
  }

  function setMode(value) {
    mode = value === 'manual' ? 'manual' : 'auto';
    resetAttention();
    if (mode === 'manual' && controls && !renderingPaused()) {
      // Drain OrbitControls' residual Auto rotation before handing the camera to the user.
      const damping = controls.enableDamping;
      controls.enableDamping = false;
      controls.update();
      controls.enableDamping = damping;
    }
  }

  function setCameraPhase(value) {
    cameraPhase = ['focus', 'return', 'roam'].includes(value) ? value : 'roam';
    if (cameraPhase !== 'focus') clearFocus();
    updateAutoRotate();
  }

  function moveManualCamera(input = {}, deltaSeconds = 0) {
    if (mode !== 'manual' || !graph || !controls || programmatic || renderingPaused()) return false;
    const delta = clamp(finite(deltaSeconds), 0, 0.05);
    if (!delta) return false;
    if (!['forward', 'right', 'up', 'yaw', 'pitch'].some(key => finite(input[key]))) return false;
    const camera = graph.camera();
    const direction = new library.Vector3().copy(controls.target).sub(camera.position).normalize();
    const up = new library.Vector3(0, 1, 0);
    const right = new library.Vector3().crossVectors(direction, up).normalize();
    const move = new library.Vector3().addScaledVector(direction, finite(input.forward) * 125 * delta)
      .addScaledVector(right, finite(input.right) * 125 * delta).addScaledVector(up, finite(input.up) * 125 * delta);
    camera.position.add(move);
    controls.target.add(move);
    const yaw = finite(input.yaw) * 1.15 * delta;
    const pitch = finite(input.pitch) * 1.15 * delta;
    const distance = camera.position.distanceTo(controls.target);
    direction.applyAxisAngle(up, yaw).applyAxisAngle(right, pitch).normalize();
    controls.target.copy(camera.position.clone().add(direction.multiplyScalar(distance)));
    containSystemCamera();
    return Boolean(move.lengthSq() || yaw || pitch);
  }

  function releaseLabelGestures() {
    for (const label of [...interactingLabels]) labelGestureReleases.get(label)?.();
  }

  function wireListenLabel(label) {
    let pointerId = null;
    const keys = new Set();
    const sync = () => {
      if (pointerId !== null || keys.size) interactingLabels.add(label);
      else interactingLabels.delete(label);
      updateAutoRotate();
    };
    const release = () => {
      const captured = pointerId;
      pointerId = null;
      keys.clear();
      interactingLabels.delete(label);
      if (captured !== null && label.hasPointerCapture?.(captured)) label.releasePointerCapture(captured);
      updateAutoRotate();
    };
    labelGestureReleases.set(label, release);
    label.addEventListener('pointerenter', () => {
      if (!label.disabled) { nativeHoveredLabelKey = label.dataset.nodeKey; pointerInsideHost = true; }
    });
    label.addEventListener('pointerleave', () => {
      if (nativeHoveredLabelKey === label.dataset.nodeKey) nativeHoveredLabelKey = '';
    });
    label.addEventListener('pointerdown', event => {
      if (event.button !== 0 || !event.isPrimary || label.disabled) return;
      pointerId = event.pointerId;
      label.setPointerCapture?.(pointerId);
      sync();
      event.stopPropagation();
    });
    const releasePointer = event => {
      if (event.pointerId === pointerId) release();
    };
    label.addEventListener('pointerup', releasePointer);
    label.addEventListener('pointercancel', releasePointer);
    label.addEventListener('lostpointercapture', releasePointer);
    label.addEventListener('keydown', event => {
      if (!['Enter', ' '].includes(event.key) || label.disabled) return;
      keys.add(event.key);
      sync();
    });
    label.addEventListener('keyup', event => {
      if (keys.delete(event.key)) sync();
    });
    label.addEventListener('focusout', release);
  }

  function labelFor(node) {
    let label = labelElements.get(node.id);
    if (!label || node.type === 'talkgroup' && !label.hasAttribute('data-direct-listen-target-key') &&
        label.dataset.labelRevision !== String(labelRevision)) {
      const action = node.type === 'talkgroup' ? options.createDirectListenAction?.(node) : null;
      if (action) {
        action.classList.add('network-visualizer-label', 'network-visualizer-label-action');
        wireListenLabel(action);
        if (label) label.replaceWith(action);
        else labels.append(action);
        labelElements.set(node.id, action);
        label = action;
      } else if (!label) {
        label = element(documentValue, node.type === 'system' ? 'button' : 'span',
          'network-visualizer-label network-visualizer-label-text');
        if (node.type === 'system') {
          label.type = 'button';
          label.addEventListener('click', () => {
            if (scope.level === 'overview' && !renderingPaused()) options.onNodeClick?.(nodes.get(node.id) || node);
          });
        } else label.setAttribute('aria-hidden', 'true');
        labels.append(label);
        labelElements.set(node.id, label);
      }
      label.dataset.labelRevision = String(labelRevision);
    }
    const text = String(node.label || node.id);
    const content = label.querySelector('[data-direct-listen-text]') || label;
    if (content.textContent !== text) {
      content.textContent = text;
      if (label.hasAttribute('data-direct-listen-target-key')) {
        label.dataset.directListenLabel = text;
        const description = label.getAttribute('aria-pressed') === 'true' ?
          `Remove ${text} from Direct Listen` : `Add ${text} to Direct Listen and play`;
        label.setAttribute('aria-label', description);
        label.dataset.uiHint = description;
      }
    }
    if (node.type === 'system' && label.dataset.uiHint !== text) label.dataset.uiHint = text;
    if (label.dataset.type !== node.type) label.dataset.type = node.type;
    const signal = node.signalAction || (node.active ? 'call' : '');
    if (label.dataset.signal !== signal) label.dataset.signal = signal;
    label.dataset.nodeKey = node.id;
    return label;
  }

  function rememberLabelPointer(event) {
    pointerInsideHost = true;
    labelPointer = event.pointerType === 'touch' ? null : { x: event.clientX, y: event.clientY };
  }

  function enterShapeSurface() {
    if (pointerInsideShapeSurface) return;
    pointerInsideShapeSurface = true;
    shapeHoverAt = -Infinity;
  }

  function leaveShapeSurface() {
    pointerInsideShapeSurface = false;
    hoveredShapeKey = '';
  }

  function updateShapeHover(camera) {
    if (!pointerInsideShapeSurface || !labelPointer || renderingPaused()) {
      hoveredShapeKey = '';
      return;
    }
    const at = performance.now();
    if (at - shapeHoverAt < 50) return;
    shapeHoverAt = at;
    const bounds = canvas.getBoundingClientRect();
    if (!bounds.width || !bounds.height) { hoveredShapeKey = ''; return; }
    shapeRaycaster.setFromCamera({ x: (labelPointer.x - bounds.left) / bounds.width * 2 - 1,
      y: 1 - (labelPointer.y - bounds.top) / bounds.height * 2 }, camera);
    const objects = [...nodeObjects.values()].filter(object => object.parent && object.visible &&
      ['talkgroup', 'radio'].includes(object.userData.p25Node?.type));
    objects.forEach(object => object.updateWorldMatrix(true, false));
    // Pick our meshes directly: the vendor retains hover across DOM overlays and same-shape reentry.
    // This only promotes names; native graph clicks and mesh paint/order stay independent.
    hoveredShapeKey = shapeRaycaster.intersectObjects(objects, false)[0]?.object.userData.p25Node?.id || '';
  }

  function clearLabelPointer() {
    labelPointer = null;
    hoveredLabelKey = '';
    nativeHoveredLabelKey = '';
    pointerInsideHost = false;
    leaveShapeSurface();
  }

  function onWindowBlur() {
    clearLabelPointer();
    releaseLabelGestures();
  }

  function renderLabels() {
    if (!graph || !controls || disposed || documentValue.hidden) return;
    const camera = graph.camera();
    camera.updateMatrixWorld();
    const width = host.clientWidth, height = host.clientHeight;
    const hostBounds = host.getBoundingClientRect();
    const hostStyle = getComputedStyle(host);
    const gap = parseFloat(hostStyle.getPropertyValue('--space-2')) || 8;
    const projectionScale = height / (2 * Math.tan(finite(camera.fov, 50) * Math.PI / 360));
    const overlays = [...(host.parentElement?.querySelectorAll(
      '.network-visualizer-back, .network-visualizer-scope-title, .network-visualizer-auto-focus, ' +
      '.network-visualizer-manual-guide, .network-visualizer-legend') || [])]
      .filter(overlay => !overlay.hidden && overlay.getClientRects().length)
      .map(overlay => {
        const bounds = overlay.getBoundingClientRect();
        return { left: bounds.left - hostBounds.left, right: bounds.right - hostBounds.left,
          top: bounds.top - hostBounds.top, bottom: bounds.bottom - hostBounds.top };
      });
    const visible = new Set(), entries = [];
    for (const node of labelCandidates) {
      if (scope.level !== 'overview' && node.type === 'system') continue;
      const retained = labelElements.get(node.id);
      const point = new library.Vector3(node.x, node.y, node.z);
      const viewDepth = -point.clone().applyMatrix4(camera.matrixWorldInverse).z;
      const projected = point.clone().project(camera);
      const screen = graph.graph2ScreenCoords(node.x, node.y, node.z);
      if (!screen || ![viewDepth, projected.z, screen.x, screen.y].every(Number.isFinite) || viewDepth <= 0 ||
          projected.z < -1 || projected.z > 1 || screen.x < -120 || screen.y < -50 ||
          screen.x > width + 120 || screen.y > height + 50) {
        labelGestureReleases.get(retained)?.();
        continue;
      }
      const label = labelFor(node);
      label.hidden = false;
      if (label.disabled && interactingLabels.has(label)) labelGestureReleases.get(label)?.();
      visible.add(node.id);
      const screenRadius = finite(node.radius, 8) * projectionScale / viewDepth;
      let labelY = node.type === 'talkgroup' ? screen.y + screenRadius * 1.75 + 14 :
        node.type === 'radio' ? screen.y - screenRadius * 1.5 - 14 :
          screen.y + screenRadius * 1.25 + label.offsetHeight / 2 + gap;
      let labelX = screen.x;
      if (node.type === 'system') {
        const halfWidth = label.offsetWidth / 2;
        labelX = clamp(labelX, halfWidth + gap, Math.max(halfWidth + gap, width - halfWidth - gap));
      }
      if (label.hasAttribute('data-direct-listen-target-key')) {
        const halfWidth = label.offsetWidth / 2, halfHeight = label.offsetHeight / 2;
        labelX = clamp(labelX, halfWidth + gap, width - halfWidth - gap);
        let top = gap, bottom = height - gap;
        for (const overlay of overlays) {
          if (labelX + halfWidth < overlay.left || labelX - halfWidth > overlay.right) continue;
          if (overlay.top < height / 2) top = Math.max(top, overlay.bottom + gap);
          else bottom = Math.min(bottom, overlay.top - gap);
        }
        labelY = clamp(labelY, top + halfHeight, Math.max(top + halfHeight, bottom - halfHeight));
      }
      const position = `calc(${Math.round(labelX)}px - 50%) calc(${Math.round(labelY)}px - 50%)`;
      if (!interactingLabels.has(label) && label.style.translate !== position) label.style.translate = position;
      const attention = nodeAttention(node);
      const namedEntity = node.type === 'talkgroup' || node.type === 'radio';
      entries.push({ node, label, viewDepth, attention,
        active: namedEntity && (attention.eventFocused || attention.activity && !attention.dimmed) });
    }
    // Camera-space ranks avoid distant labels sharing a rounded NDC z-index.
    entries.sort((left, right) => right.viewDepth - left.viewDepth || String(left.node.id).localeCompare(String(right.node.id)));
    entries.forEach((entry, index) => { entry.rank = index + 1; });
    const hoverable = entries.filter(entry => ['talkgroup', 'radio'].includes(entry.node.type) && !entry.label.disabled);
    const containsPointer = entry => {
      if (!labelPointer) return false;
      const bounds = entry.label.getBoundingClientRect();
      return labelPointer.x >= bounds.left && labelPointer.x <= bounds.right &&
        labelPointer.y >= bounds.top && labelPointer.y <= bounds.bottom;
    };
    // Native action hover is authoritative; it must not depend on separately cached coordinates.
    let hovered = hoverable.find(entry => entry.label.hasAttribute('data-direct-listen-target-key') &&
      (entry.node.id === nativeHoveredLabelKey || pointerInsideHost && entry.label.matches(':hover')));
    const readOnly = hoverable.filter(entry => !entry.label.hasAttribute('data-direct-listen-target-key'));
    hovered ||= readOnly.find(entry => entry.node.id === hoveredLabelKey && containsPointer(entry));
    hovered ||= readOnly.filter(containsPointer).sort((left, right) =>
      Number(right.active) - Number(left.active) || right.rank - left.rank)[0];
    if (!hovered) {
      updateShapeHover(camera);
      hovered = hoverable.find(entry => entry.node.id === hoveredShapeKey);
    }
    hoveredLabelKey = hovered?.node.id || '';
    for (const { node, label, viewDepth, attention, rank } of entries) {
      const pointed = node.id === hoveredLabelKey;
      const pressing = !label.disabled && interactingLabels.has(label);
      const focused = !label.disabled && label.matches(':focus-visible');
      const tier = resolveP25LabelTier(attention, { pointed, pressing, focused });
      label.style.zIndex = String(tier * entries.length + rank);
      if (tier) {
        label.style.opacity = '1';
        label.style.filter = '';
        continue;
      }
      // Use the mesh's attention alpha and camera-space FogExp2 transmittance for names too.
      const { base, dim } = opacityRange(node.type === 'system' ? 'system' : nodeState(node), attention.clear, attention.dimmed);
      const transmission = attention.clear || !scene?.fog ? 1 : Math.exp(-((scene.fog.density * viewDepth) ** 2));
      const opacity = node.type === 'system' ? 1 : (base + (dim - base) * focusAmount) * transmission;
      const blur = (1 - transmission) * 2.8 + (attention.dimmed ? focusAmount * 2 : 0);
      label.style.opacity = String(opacity);
      label.style.filter = blur ? `blur(${blur.toFixed(2)}px)` : '';
    }
    for (const [key, label] of labelElements) {
      const hidden = !visible.has(key);
      if (hidden && interactingLabels.has(label)) labelGestureReleases.get(label)?.();
      label.hidden = hidden;
    }
  }

  function animateAffiliations(at) {
    let finished = false;
    for (const [key, trip] of affiliationMoves) {
      const node = nodes.get(key);
      if (!node) { affiliationMoves.delete(key); finished = true; continue; }
      const progress = clamp((at - trip.startedAt) / trip.duration, 0, 1);
      const amount = ease(progress), target = trip.change.target;
      node.x = trip.from.x + (target.x - trip.from.x) * amount;
      node.y = trip.from.y + (target.y - trip.from.y) * amount;
      node.z = trip.from.z + (target.z - trip.from.z) * amount;
      node.fx = node.x; node.fy = node.y; node.fz = node.z;
      if (progress >= 1) {
        affiliationMoves.delete(key);
        const settled = canonicalPositions.get(key);
        if (settled) Object.assign(node, settled, { fx: settled.x, fy: settled.y, fz: settled.z });
        finished = true;
      }
    }
    if (finished) publishGraph();
  }

  function animate(at) {
    frame = 0;
    if (disposed || renderingPaused()) return;
    if (graphDirty) publishGraph();
    if (focusAmount !== focusTarget) {
      const progress = clamp((at - focusStartedAt) / FOCUS_FADE_MS, 0, 1);
      focusAmount = focusFrom + (focusTarget - focusFrom) * ease(progress);
      updateFocusOpacity();
      if (progress >= 1 && !focusTarget) {
        focusKeys.clear();
        applyFocusStyles();
      }
    }
    animateAffiliations(at);
    updateAutoRotate();
    renderLabels();
    frame = requestAnimationFrame(animate);
  }

  const onControlsStart = () => {
    stopCamera();
    cameraPhase = 'roam';
    clearFocus(true);
    interactionUntil = Number.POSITIVE_INFINITY;
    updateAutoRotate();
    options.onInteraction?.();
  };
  const onControlsEnd = () => {
    interactionUntil = performance.now() + 2_500;
    containSystemCamera();
    updateAutoRotate();
  };
  const onVisibility = () => {
    if (!graph) return;
    if (renderingPaused()) {
      clearLabelPointer();
      resetAttention();
      if (frame) cancelAnimationFrame(frame);
      frame = 0;
      graph.pauseAnimation();
    } else {
      if (themeDirty) updateTheme();
      resize();
      // Reconcile retained data before the vendor's next frame. A caller can supply a newer
      // snapshot during this visibility event without publishing the previous one first.
      if (!frame) frame = requestAnimationFrame(animate);
      if (refreshPending) { refreshPending = false; graph.refresh(); }
      graph.resumeAnimation();
    }
    updateAutoRotate();
  };
  const onContextLost = (event) => {
    event.preventDefault();
    host.dataset.rendererState = 'context-lost';
  };
  const onContextRestored = () => {
    host.dataset.rendererState = 'ready';
    if (renderingPaused()) refreshPending = true;
    else graph?.refresh();
  };

  function updateTheme() {
    if (renderingPaused()) { themeDirty = true; return; }
    themeDirty = false;
    colors = palette();
    materials.forEach((value, key) => {
      const state = key.startsWith('line:') ? key.split(':')[1] : key.split(':')[0];
      value.color?.set?.(colorFor(state));
      value.needsUpdate = true;
    });
    if (scene?.fog) scene.fog.color?.set?.(colors.background);
  }

  try {
    graph = library.ForceGraph3D({ controlType: 'orbit', rendererConfig: { alpha: true, antialias: true,
      powerPreference: 'high-performance' } })(surface).showNavInfo(false).backgroundColor('rgba(0, 0, 0, 0)')
      .nodeId('id').nodeVal(1).nodeThreeObject((node) => nodeObjects.get(node.id) || createNodeObject(node))
      .nodeThreeObjectExtend(false).linkSource('source').linkTarget('target')
      .linkThreeObject((link) => linkObjects.get(link.id) || createLinkObject(link)).linkThreeObjectExtend(false)
      .linkPositionUpdate(updateLinkPosition).onNodeClick((node) => options.onNodeClick?.(node))
      .onBackgroundClick(() => options.onBackgroundClick?.()).cooldownTicks(Infinity).cooldownTime(Infinity);
    // With no forces and pinned coordinates, ticks only synchronize meshes and links with our tweens.
    graph.d3Force('link', null);
    graph.d3Force('charge', null);
    graph.d3Force('center', null);
    graph.numDimensions(3);
    graph.enableNodeDrag?.(false);
    scene = graph.scene();
    controls = graph.controls();
    Object.assign(controls, { autoRotate: false, autoRotateSpeed: AUTO_ROTATE_SPEED, enableDamping: true,
      dampingFactor: 0.06, rotateSpeed: 0.42, zoomSpeed: 0.62, enablePan: true, zoomToCursor: true });
    if (library.MOUSE && controls.mouseButtons) {
      controls.mouseButtons.LEFT = library.MOUSE.ROTATE;
      controls.mouseButtons.MIDDLE = library.MOUSE.DOLLY;
      controls.mouseButtons.RIGHT = library.MOUSE.PAN;
    }
    controls.addEventListener('start', onControlsStart);
    controls.addEventListener('end', onControlsEnd);
    canvas = graph.renderer()?.domElement || null;
    canvas?.addEventListener('webglcontextlost', onContextLost, false);
    canvas?.addEventListener('webglcontextrestored', onContextRestored, false);
  } catch (_) {
    try { graph?._destructor?.(); } catch (_) { /* Partial renderer. */ }
    surface.remove();
    labels.remove();
    return createUnavailable(host, 'P25 visualization is unavailable in this browser. Live activity remains available on the Live page.',
      options.signal);
  }

  host.dataset.rendererState = 'ready';
  if (typeof ResizeObserver === 'function') {
    resizeObserver = new ResizeObserver(resize);
    resizeObserver.observe(host);
  }
  themeObserver = new MutationObserver(updateTheme);
  themeObserver.observe(documentValue.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  documentValue.addEventListener('visibilitychange', onVisibility);
  documentValue.defaultView?.addEventListener('blur', onWindowBlur);
  host.addEventListener('pointerenter', rememberLabelPointer, { passive: true, capture: true });
  host.addEventListener('pointermove', rememberLabelPointer, { passive: true, capture: true });
  host.addEventListener('pointerleave', clearLabelPointer, { passive: true });
  surface.addEventListener('pointerenter', enterShapeSurface, { passive: true });
  surface.addEventListener('pointermove', enterShapeSurface, { passive: true, capture: true });
  surface.addEventListener('pointerleave', leaveShapeSurface, { passive: true });
  async function enterFullscreen() {
    const target = host.closest('.network-visualizer-layout') || host;
    if (documentValue.fullscreenElement === target) return true;
    if (!target.requestFullscreen) return false;
    await target.requestFullscreen();
    return true;
  }

  async function exitFullscreen() {
    if (!documentValue.fullscreenElement || !documentValue.exitFullscreen) return false;
    await documentValue.exitFullscreen();
    return true;
  }

  function setGuideSuspended(value) {
    if (disposed || guideSuspended === Boolean(value)) return;
    guideSuspended = Boolean(value);
    onVisibility();
  }

  function dispose() {
    if (disposed) return;
    releaseLabelGestures();
    disposed = true;
    stopCamera();
    if (frame) cancelAnimationFrame(frame);
    resizeObserver?.disconnect();
    themeObserver?.disconnect();
    documentValue.removeEventListener('visibilitychange', onVisibility);
    documentValue.defaultView?.removeEventListener('blur', onWindowBlur);
    host.removeEventListener('pointerenter', rememberLabelPointer, true);
    host.removeEventListener('pointermove', rememberLabelPointer, true);
    host.removeEventListener('pointerleave', clearLabelPointer);
    surface.removeEventListener('pointerenter', enterShapeSurface);
    surface.removeEventListener('pointermove', enterShapeSurface, true);
    surface.removeEventListener('pointerleave', leaveShapeSurface);
    clearLabelPointer();
    controls?.removeEventListener('start', onControlsStart);
    controls?.removeEventListener('end', onControlsEnd);
    canvas?.removeEventListener('webglcontextlost', onContextLost, false);
    canvas?.removeEventListener('webglcontextrestored', onContextRestored, false);
    try { graph?._destructor?.(); } catch (_) { /* Release everything else. */ }
    try { graph?.renderer?.()?.forceContextLoss?.(); } catch (_) { /* Already released. */ }
    linkObjects.forEach((object) => object.geometry?.dispose?.());
    geometries.forEach((value) => value.dispose?.());
    materials.forEach((value) => value.dispose?.());
    labelElements.forEach((value) => value.remove());
    surface.remove();
    labels.remove();
    nodes.clear();
    links.clear();
    nodeObjects.clear();
    linkObjects.clear();
    labelElements.clear();
    interactingLabels.clear();
    labelGestureReleases.clear();
    affiliationMoves.clear();
    canonicalPositions.clear();
    canonicalLinks = [];
    movementParticipants.clear();
    labelCandidates = [];
  }

  options.signal?.addEventListener?.('abort', dispose, { once: true });
  onVisibility();

  return Object.freeze({ available: true, setData, setMode, setCameraPhase, enterSystem, enterTalkgroup,
    showOverview, focus, focusAffiliation, home, moveManualCamera, enterFullscreen, exitFullscreen,
    isInteracting: () => interactingLabels.size > 0,
    setGuideSuspended, resetAttention, dispose });
}

export { calculateFocusBounds, createP25Renderer };
