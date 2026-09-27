'use strict';

const VENDOR_ASSET = '../../vendor/network-visualizer-vendor.js?v=3';
const SYSTEM_RADIUS = 260;
const LABEL_LIMIT = 90;
const AUTO_ROTATE_SPEED = 0.34;
const FOCUS_FADE_MS = 220;

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

function element(documentValue, tag, className = '') {
  const value = documentValue.createElement(tag);
  if (className) value.className = className;
  return value;
}

function nodeState(node) {
  return String(node?.signalAction || node?.type || 'radio').toLowerCase();
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
    home: () => false,
    moveManualCamera: () => false, enterFullscreen: async () => false, exitFullscreen: async () => false,
    dispose });
}

async function createP25Renderer(options = {}) {
  const host = options.host;
  if (!(host instanceof Element)) throw new TypeError('A P25 Visualizer renderer host is required.');
  const documentValue = host.ownerDocument;
  const surface = element(documentValue, 'div', 'network-visualizer-graph-surface');
  const labels = element(documentValue, 'div', 'network-visualizer-label-layer');
  labels.setAttribute('aria-hidden', 'true');
  host.append(surface, labels);

  let library;
  try {
    library = options.library || await (options.vendorLoader || loadVendor)();
  } catch (_) {
    surface.remove();
    labels.remove();
    return createUnavailable(host, 'P25 visualization is unavailable in this browser. Saved events remain available.',
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
      history: color('--network-space-muted', '#8ea1ac'),
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
  let frame = 0;
  let cameraFrame = 0;
  let resizeObserver = null;
  let themeObserver = null;
  let mode = options.mode === 'manual' ? 'manual' : 'auto';
  let cameraPhase = 'roam';
  let scope = { level: 'overview', systemKey: '' };
  let interactionUntil = 0;
  let programmatic = false;
  let reducedMotion = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches || false;
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
  const movements = new Map();
  const geometries = new Map();
  const materials = new Map();

  function colorFor(state) {
    return colors[state] || colors.radio;
  }

  function material(state, optionsValue = {}) {
    const side = optionsValue.side === library.BackSide ? 'back' : 'front';
    const clear = optionsValue.clear === true;
    const dimmed = optionsValue.dimmed === true;
    const key = `${state}:${side}:${clear}:${dimmed}`;
    if (materials.has(key)) return materials.get(key);
    const baseOpacity = state === 'system' ? 0.48 : clear ? 0.98 : 0.76;
    const dimOpacity = dimmed ? state === 'system' ? 0.12 : 0.05 : baseOpacity;
    const value = new library.MeshBasicMaterial({ color: colorFor(state), wireframe: true, transparent: true,
      opacity: baseOpacity + (dimOpacity - baseOpacity) * focusAmount, depthTest: true, depthWrite: false,
      fog: !clear, side: optionsValue.side });
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
    const clear = Boolean(node.signalAction);
    const dimmed = focusKeys.size > 0 && !focusKeys.has(node.id) && node.type !== 'system';
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
    const baseOpacity = historical ? 0.18 : 0.58;
    const dimOpacity = dimmed ? 0.012 : baseOpacity;
    const value = new library.LineMaterial({ color: colorFor(kind), linewidth: historical ? 1.25 : 2.5,
      transparent: true, opacity: baseOpacity + (dimOpacity - baseOpacity) * focusAmount,
      dashed: historical, dashSize: 6, gapSize: 5,
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
    if (link.kind === 'history') object.computeLineDistances?.();
    linkObjects.set(link.id, object);
    return object;
  }

  function linkIsDimmed(link) {
    const source = typeof link.source === 'object' ? link.source.id : link.source;
    const target = typeof link.target === 'object' ? link.target.id : link.target;
    return focusKeys.size > 0 && !(focusKeys.has(String(source)) && focusKeys.has(String(target)));
  }

  function applyLinkStyle(link, object = linkObjects.get(link.id)) {
    if (object) object.material = lineMaterial(link.kind, linkIsDimmed(link));
  }

  function updateFocusOpacity() {
    materials.forEach((value) => {
      const base = Number(value.userData?.p25BaseOpacity);
      const dim = Number(value.userData?.p25DimOpacity);
      if (Number.isFinite(base) && Number.isFinite(dim)) value.opacity = base + (dim - base) * focusAmount;
    });
  }

  function applyFocusStyles() {
    nodes.forEach((node) => applyNodeStyle(node));
    links.forEach((link) => applyLinkStyle(link));
    graph?.refresh();
  }

  function transitionFocus(value) {
    const target = value ? 1 : 0;
    if (focusTarget === target) return;
    focusFrom = focusAmount;
    focusTarget = target;
    focusStartedAt = performance.now();
    if (reducedMotion) {
      focusAmount = focusTarget;
      updateFocusOpacity();
      if (!focusTarget) {
        focusKeys.clear();
        applyFocusStyles();
      }
    }
  }

  function setFocus(keys) {
    focusKeys = new Set(keys.map(String));
    applyFocusStyles();
    transitionFocus(true);
  }

  function clearFocus() {
    transitionFocus(false);
  }

  function updateLinkPosition(object, start, end, link) {
    object.geometry.setPositions([finite(start?.x), finite(start?.y), finite(start?.z),
      finite(end?.x), finite(end?.y), finite(end?.z)]);
    if (link.kind === 'history') object.computeLineDistances?.();
    return true;
  }

  function pruneObjects(nextNodes, nextLinks) {
    const nodeIds = new Set(nextNodes.map((node) => node.id));
    const linkIds = new Set(nextLinks.map((link) => link.id));
    for (const [key, object] of nodeObjects) {
      if (nodeIds.has(key)) continue;
      object.removeFromParent?.();
      nodeObjects.delete(key);
      labelElements.get(key)?.remove();
      labelElements.delete(key);
      movements.delete(key);
    }
    for (const [key, object] of linkObjects) {
      if (linkIds.has(key)) continue;
      object.removeFromParent?.();
      object.geometry?.dispose?.();
      linkObjects.delete(key);
    }
  }

  function setData(value = {}, optionsValue = {}) {
    if (!graph || disposed) return;
    const animate = optionsValue.animate !== false && !reducedMotion;
    const now = performance.now();
    const nextNodes = (Array.isArray(value.nodes) ? value.nodes : []).map((incoming) => {
      const id = String(incoming.id);
      const previous = nodes.get(id);
      if (!previous) return { ...incoming, id, x: finite(incoming.x), y: finite(incoming.y),
        z: finite(incoming.z), fx: finite(incoming.x), fy: finite(incoming.y), fz: finite(incoming.z) };
      const original = { x: finite(previous.x), y: finite(previous.y), z: finite(previous.z) };
      const target = { x: finite(incoming.x), y: finite(incoming.y), z: finite(incoming.z) };
      const distance = Math.hypot(target.x - original.x, target.y - original.y, target.z - original.z);
      Object.assign(previous, incoming, { id });
      if (animate && previous.type === 'radio' && distance > 1) {
        Object.assign(previous, original, { fx: original.x, fy: original.y, fz: original.z });
        movements.set(id, { startedAt: now, duration: 1_100, from: original, target });
      }
      else Object.assign(previous, target, { fx: target.x, fy: target.y, fz: target.z });
      return previous;
    });
    const nextLinks = (Array.isArray(value.links) ? value.links : []).map((incoming) => {
      const id = String(incoming.id);
      const previous = links.get(id);
      const source = typeof incoming.source === 'object' ? incoming.source.id : incoming.source;
      const target = typeof incoming.target === 'object' ? incoming.target.id : incoming.target;
      return previous ? Object.assign(previous, incoming, { source: String(source), target: String(target) }) :
        { ...incoming, id, source: String(source), target: String(target) };
    });
    pruneObjects(nextNodes, nextLinks);
    nodes.clear();
    nextNodes.forEach((node) => nodes.set(node.id, node));
    links.clear();
    nextLinks.forEach((link) => links.set(link.id, link));
    graph.graphData({ nodes: nextNodes, links: nextLinks });
    nextNodes.forEach((node) => applyNodeStyle(node));
    nextLinks.forEach((link) => applyLinkStyle(link));
    graph.refresh();
  }

  function resize() {
    if (!graph || disposed) return;
    const width = Math.max(1, Math.floor(host.clientWidth));
    const height = Math.max(1, Math.floor(host.clientHeight));
    graph.width(width).height(height);
    materials.forEach((value, key) => key.startsWith('line:') && value.resolution?.set(width, height));
  }

  function systemNode() {
    return scope.level === 'system' || scope.level === 'talkgroup' ? nodes.get(scope.systemKey) : null;
  }

  function talkgroupNode() {
    return scope.level === 'talkgroup' ? nodes.get(scope.groupKey) : null;
  }

  function containSystemCamera() {
    const system = systemNode();
    if (!system || !controls || !graph) return;
    const center = new library.Vector3(system.x, system.y, system.z);
    const camera = graph.camera();
    const offset = new library.Vector3().copy(camera.position).sub(center);
    const maximum = finite(system.radius, SYSTEM_RADIUS) * 0.88;
    if (offset.length() > maximum) camera.position.copy(center.clone().add(offset.setLength(maximum)));
    const targetOffset = new library.Vector3().copy(controls.target).sub(center);
    if (targetOffset.length() > maximum * 0.82) controls.target.copy(center.clone().add(targetOffset.setLength(maximum * 0.82)));
    camera.lookAt(controls.target);
    controls.maxDistance = maximum;
  }

  function updateAutoRotate() {
    if (!controls) return;
    controls.autoRotate = Boolean(mode === 'auto' && cameraPhase === 'roam' &&
      (scope.level === 'system' || scope.level === 'talkgroup') &&
      !programmatic && performance.now() >= interactionUntil && !documentValue.hidden && !reducedMotion);
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
    if (!graph || !controls || disposed) return false;
    stopCamera();
    const camera = graph.camera();
    const from = cameraPose();
    const toPosition = { x: finite(position.x), y: finite(position.y), z: finite(position.z) };
    const toTarget = { x: finite(target.x), y: finite(target.y), z: finite(target.z) };
    const milliseconds = reducedMotion ? 0 : Math.max(0, duration);
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
    const step = (at) => {
      cameraFrame = 0;
      if (disposed || documentValue.hidden) return stopCamera();
      const progress = clamp((at - startedAt) / milliseconds, 0, 1);
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

  function focus(keys, duration = 900) {
    if (mode !== 'auto' || !['system', 'talkgroup'].includes(scope.level)) return false;
    const bounds = boundsFor(Array.isArray(keys) ? keys : [keys]);
    const system = systemNode();
    if (!bounds || !system) return false;
    setFocus(Array.isArray(keys) ? keys : [keys]);
    const inward = new library.Vector3(system.x - bounds.center.x, system.y - bounds.center.y,
      system.z - bounds.center.z);
    if (inward.lengthSq() < 0.001) inward.set(0.7, 0.35, 1);
    inward.normalize();
    const distance = clamp(bounds.radius * 3.2, 58, 125);
    return tweenCamera({ x: bounds.center.x + inward.x * distance, y: bounds.center.y + inward.y * distance,
      z: bounds.center.z + inward.z * distance }, bounds.center, duration);
  }

  function frameOverview(immediate = false) {
    if (!nodes.size || !graph || !controls) return false;
    const systems = [...nodes.values()].filter((node) => node.type === 'system');
    if (!systems.length) return false;
    const center = systems.reduce((sum, node) => ({ x: sum.x + node.x, y: sum.y + node.y, z: sum.z + node.z }),
      { x: 0, y: 0, z: 0 });
    center.x /= systems.length;
    center.y /= systems.length;
    center.z /= systems.length;
    const radius = Math.max(80, ...systems.map((node) => Math.hypot(node.x - center.x, node.y - center.y,
      node.z - center.z) + finite(node.radius, 58)));
    const distance = Math.max(260, radius * 1.75);
    return tweenCamera({ x: center.x + distance * 0.72, y: center.y + distance * 0.42,
      z: center.z + distance }, center, immediate ? 0 : 900);
  }

  function enterSystem(systemKey, optionsValue = {}) {
    scope = { level: 'system', systemKey: String(systemKey || '') };
    if (scene) scene.fog = new library.FogExp2(colors.background, 0.0032);
    const result = home(optionsValue.immediate ? 0 : finite(optionsValue.duration, 900));
    containSystemCamera();
    updateAutoRotate();
    return result;
  }

  function enterTalkgroup(systemKey, groupKey, optionsValue = {}) {
    scope = { level: 'talkgroup', systemKey: String(systemKey || ''), groupKey: String(groupKey || '') };
    if (scene) scene.fog = new library.FogExp2(colors.background, 0.0032);
    const result = home(optionsValue.immediate ? 0 : finite(optionsValue.duration, 900));
    containSystemCamera();
    updateAutoRotate();
    return result;
  }

  function showOverview(optionsValue = {}) {
    scope = { level: 'overview', systemKey: '' };
    clearFocus();
    if (scene) scene.fog = null;
    if (controls) controls.maxDistance = Infinity;
    updateAutoRotate();
    return frameOverview(optionsValue.immediate === true);
  }

  function setMode(value) {
    mode = value === 'manual' ? 'manual' : 'auto';
    if (mode === 'manual') {
      stopCamera();
      clearFocus();
    }
    updateAutoRotate();
  }

  function setCameraPhase(value) {
    cameraPhase = ['focus', 'hold', 'return', 'roam'].includes(value) ? value : 'roam';
    if (cameraPhase === 'return' || cameraPhase === 'roam') clearFocus();
    updateAutoRotate();
  }

  function moveManualCamera(input = {}, deltaSeconds = 0) {
    if (mode !== 'manual' || !graph || !controls || programmatic) return false;
    const delta = clamp(finite(deltaSeconds), 0, 0.05);
    if (!delta) return false;
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

  function renderLabels() {
    if (!graph || !controls || disposed || documentValue.hidden) return;
    const camera = graph.camera();
    const system = systemNode();
    const candidates = [...nodes.values()].sort((left, right) => {
      const priority = (node) => focusKeys.has(node.id) ? 6 : node.signalAction ? 5 : node.type === 'system' ? 4 :
        node.type === 'talkgroup' ? 3 : 1;
      return priority(right) - priority(left);
    }).slice(0, LABEL_LIMIT);
    const visible = new Set();
    for (const node of candidates) {
      if (scope.level === 'talkgroup' && node.type === 'system') continue;
      const screen = graph.graph2ScreenCoords(node.x, node.y, node.z);
      const projected = new library.Vector3(node.x, node.y, node.z).project(camera);
      if (!screen || projected.z < -1 || projected.z > 1 || screen.x < -120 || screen.y < -50 ||
          screen.x > host.clientWidth + 120 || screen.y > host.clientHeight + 50) continue;
      let label = labelElements.get(node.id);
      if (!label) {
        label = element(documentValue, 'span', 'network-visualizer-label');
        labels.append(label);
        labelElements.set(node.id, label);
      }
      visible.add(node.id);
      label.textContent = node.label || node.id;
      label.dataset.type = node.type;
      label.dataset.signal = node.signalAction || '';
      label.style.transform = `translate3d(${Math.round(screen.x)}px, ${Math.round(screen.y)}px, 0)`;
      label.style.zIndex = String(Math.round((1 - projected.z) * 5_000));
      const clear = scope.level === 'overview' || scope.level === 'talkgroup' || Boolean(node.signalAction) ||
        node.type === 'system';
      const depth = system ? camera.position.distanceTo(new library.Vector3(node.x, node.y, node.z)) /
        (finite(system.radius, SYSTEM_RADIUS) * 1.55) : 0;
      const haze = clear ? 0 : clamp((depth - 0.28) * 1.45, 0, 0.82);
      const dimmed = focusKeys.size > 0 && !focusKeys.has(node.id) && node.type !== 'system';
      label.style.opacity = String((1 - haze * 0.72) * (dimmed ? 1 - focusAmount * 0.96 : 1));
      const blur = haze * 2.8 + (dimmed ? focusAmount * 2 : 0);
      label.style.filter = blur ? `blur(${blur.toFixed(2)}px)` : '';
    }
    for (const [key, label] of labelElements) label.hidden = !visible.has(key);
  }

  function animate(at) {
    frame = 0;
    if (disposed) return;
    if (focusAmount !== focusTarget) {
      const progress = clamp((at - focusStartedAt) / FOCUS_FADE_MS, 0, 1);
      focusAmount = focusFrom + (focusTarget - focusFrom) * ease(progress);
      updateFocusOpacity();
      if (progress >= 1 && !focusTarget) {
        focusKeys.clear();
        applyFocusStyles();
      }
    }
    for (const [key, movement] of movements) {
      const node = nodes.get(key);
      if (!node) {
        movements.delete(key);
        continue;
      }
      const progress = clamp((at - movement.startedAt) / movement.duration, 0, 1);
      const amount = ease(progress);
      node.x = movement.from.x + (movement.target.x - movement.from.x) * amount;
      node.y = movement.from.y + (movement.target.y - movement.from.y) * amount;
      node.z = movement.from.z + (movement.target.z - movement.from.z) * amount;
      node.fx = node.x;
      node.fy = node.y;
      node.fz = node.z;
      if (progress >= 1) movements.delete(key);
    }
    if (movements.size) graph.refresh();
    updateAutoRotate();
    renderLabels();
    frame = requestAnimationFrame(animate);
  }

  const onControlsStart = () => {
    stopCamera();
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
    if (documentValue.hidden) graph.pauseAnimation();
    else graph.resumeAnimation();
    updateAutoRotate();
  };
  const onContextLost = (event) => {
    event.preventDefault();
    host.dataset.rendererState = 'context-lost';
  };
  const onContextRestored = () => {
    host.dataset.rendererState = 'ready';
    graph?.refresh();
  };

  try {
    graph = library.ForceGraph3D({ controlType: 'orbit', rendererConfig: { alpha: true, antialias: true,
      powerPreference: 'high-performance' } })(surface).showNavInfo(false).backgroundColor('rgba(0, 0, 0, 0)')
      .nodeId('id').nodeVal(1).nodeThreeObject((node) => nodeObjects.get(node.id) || createNodeObject(node))
      .nodeThreeObjectExtend(false).linkSource('source').linkTarget('target')
      .linkThreeObject((link) => linkObjects.get(link.id) || createLinkObject(link)).linkThreeObjectExtend(false)
      .linkPositionUpdate(updateLinkPosition).onNodeClick((node) => options.onNodeClick?.(node))
      .onBackgroundClick(() => options.onBackgroundClick?.()).cooldownTicks(0).cooldownTime(0);
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
    return createUnavailable(host, 'P25 visualization is unavailable in this browser. Saved events remain available.',
      options.signal);
  }

  host.dataset.rendererState = 'ready';
  if (typeof ResizeObserver === 'function') {
    resizeObserver = new ResizeObserver(resize);
    resizeObserver.observe(host);
  }
  themeObserver = new MutationObserver(() => {
    colors = palette();
    materials.forEach((value, key) => {
      const state = key.startsWith('line:') ? key.split(':')[1] : key.split(':')[0];
      value.color?.set?.(colorFor(state));
      value.needsUpdate = true;
    });
    if (scene?.fog) scene.fog.color?.set?.(colors.background);
  });
  themeObserver.observe(documentValue.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  documentValue.addEventListener('visibilitychange', onVisibility);
  const media = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)');
  const onMotion = () => {
    reducedMotion = Boolean(media?.matches);
    if (reducedMotion && focusAmount !== focusTarget) {
      focusAmount = focusTarget;
      updateFocusOpacity();
      if (!focusTarget) {
        focusKeys.clear();
        applyFocusStyles();
      }
    }
    updateAutoRotate();
  };
  media?.addEventListener?.('change', onMotion);

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

  function dispose() {
    if (disposed) return;
    disposed = true;
    stopCamera();
    if (frame) cancelAnimationFrame(frame);
    resizeObserver?.disconnect();
    themeObserver?.disconnect();
    documentValue.removeEventListener('visibilitychange', onVisibility);
    media?.removeEventListener?.('change', onMotion);
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
    movements.clear();
  }

  options.signal?.addEventListener?.('abort', dispose, { once: true });
  resize();
  frame = requestAnimationFrame(animate);

  return Object.freeze({ available: true, setData, setMode, setCameraPhase, enterSystem, enterTalkgroup,
    showOverview, focus, home, moveManualCamera, enterFullscreen, exitFullscreen, dispose });
}

export { createP25Renderer };
