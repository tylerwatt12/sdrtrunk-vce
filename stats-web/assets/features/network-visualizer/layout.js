'use strict';

import { BALANCED_CONFIG, validateConfig } from './config.js';

const TYPES = new Set(['universe', 'group', 'radio', 'aggregate']);
const TYPE_ORDER = Object.freeze({ universe: 0, group: 1, radio: 2, aggregate: 3 });
const SETTLE_EPSILON = 0.02;

function finite(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function boundedCoordinate(value) {
  return Math.max(-1_000_000, Math.min(1_000_000, finite(value)));
}

function stableHash(value) {
  let hash = 2166136261;
  const input = String(value || '');
  for (let index = 0; index < input.length; index += 1) {
    hash ^= input.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

function unit(value) {
  return (stableHash(value) & 0xffff) / 0xffff;
}

function copyPosition(position = {}) {
  return {
    x: boundedCoordinate(position.x),
    y: boundedCoordinate(position.y),
    z: boundedCoordinate(position.z)
  };
}

function containedGroupPosition(layout, record, position) {
  const next = copyPosition(position);
  if (record?.type !== 'group') return next;
  const parent = layout.positions.get(record.universeKey);
  if (!parent) return next;
  const dx = next.x - parent.x;
  const dy = next.y - parent.y;
  const dz = next.z - parent.z;
  const distance = Math.hypot(dx, dy, dz);
  const maximum = layout.config.layout.groupOrbitRadius;
  if (distance <= maximum || distance <= 0) return next;
  const scale = maximum / distance;
  return { x: parent.x + dx * scale, y: parent.y + dy * scale, z: parent.z + dz * scale };
}

function createLayoutState(config = BALANCED_CONFIG, options = {}) {
  const validated = config === BALANCED_CONFIG ? config : validateConfig(config);
  return {
    config: validated,
    frozen: Boolean(options.frozen),
    reducedMotion: Boolean(options.reducedMotion),
    profileKey: String(options.profileKey || 'default').slice(0, 128),
    positions: new Map(),
    saved: new Map(),
    pinned: new Set(),
    graphKeys: new Set(),
    universeOrder: new Map(),
    sleeping: true,
    disposed: false,
    lastStepAtMs: 0
  };
}

function universeAnchor(layout, key) {
  if (!layout.universeOrder.has(key)) {
    const limit = layout.config.state.hardUniverses;
    if (layout.universeOrder.size >= limit) {
      const active = new Set();
      layout.positions.forEach((record) => {
        if (record.universeKey) active.add(record.universeKey);
      });
      const removable = [...layout.universeOrder.keys()].find((candidate) =>
        !active.has(candidate) && !layout.saved.has(candidate));
      const evicted = removable || layout.universeOrder.keys().next().value;
      if (evicted !== undefined) layout.universeOrder.delete(evicted);
    }
    const used = new Set(layout.universeOrder.values());
    let ordinal = 0;
    while (used.has(ordinal) && ordinal < limit) ordinal += 1;
    layout.universeOrder.set(key, Math.min(ordinal, Math.max(0, limit - 1)));
  }
  const index = layout.universeOrder.get(key);
  if (index === 0) return { x: 0, y: 0, z: 0 };
  const spacing = layout.config.layout.universeSpacing;
  const angle = index * Math.PI * (3 - Math.sqrt(5));
  const radius = spacing * Math.sqrt(index);
  return {
    x: Math.cos(angle) * radius,
    y: ((index % 5) - 2) * spacing * 0.32,
    z: Math.sin(angle) * radius
  };
}

function localSlot(layout, record) {
  if (record.type === 'universe') return { x: 0, y: 0, z: 0 };
  const key = record.key;
  const baseRadius = record.type === 'group' ? layout.config.layout.groupOrbitRadius :
    layout.config.layout.radioOrbitRadius * (record.type === 'aggregate' ? 1.35 : 1);
  const radius = baseRadius * (0.64 + unit(`${key}:radius`) * 0.36);
  const vertical = unit(`${key}:vertical`) * 2 - 1;
  const angle = unit(`${key}:angle`) * Math.PI * 2;
  const horizontal = Math.sqrt(Math.max(0, 1 - vertical * vertical));
  return {
    x: Math.cos(angle) * horizontal * radius,
    y: vertical * radius,
    z: Math.sin(angle) * horizontal * radius
  };
}

function parentKey(record) {
  if (record.type === 'group') return record.universeKey;
  if (record.type === 'radio' || record.type === 'aggregate') {
    return record.groupKey || record.universeKey;
  }
  return '';
}

function targetFor(layout, record) {
  if (record.type === 'universe') {
    return { x: record.anchorX, y: record.anchorY, z: record.anchorZ };
  }
  const parent = layout.positions.get(parentKey(record));
  if (!parent) return { x: record.x, y: record.y, z: record.z };
  return {
    x: parent.targetX + record.localX,
    y: parent.targetY + record.localY,
    z: parent.targetZ + record.localZ
  };
}

function setTarget(layout, record) {
  const target = targetFor(layout, record);
  record.targetX = boundedCoordinate(target.x);
  record.targetY = boundedCoordinate(target.y);
  record.targetZ = boundedCoordinate(target.z);
}

function initialRecord(layout, node, atMs) {
  const key = String(node.key || node.id || '');
  const type = TYPES.has(node.type) ? node.type : 'aggregate';
  const reconciledFromKey = String(node.entity?.reconciledFromKey || '');
  const reconciled = reconciledFromKey ? layout.positions.get(reconciledFromKey) : null;
  const saved = layout.saved.get(key) || (reconciledFromKey ? layout.saved.get(reconciledFromKey) : null);
  const universeKey = String(node.universeKey || (type === 'universe' ? key : ''));
  const groupKey = String(node.groupKey || (type === 'group' ? key : ''));
  const shell = localSlot(layout, { key, type });
  const record = {
    key,
    type,
    universeKey,
    groupKey,
    x: 0,
    y: 0,
    z: 0,
    targetX: 0,
    targetY: 0,
    targetZ: 0,
    anchorX: 0,
    anchorY: 0,
    anchorZ: 0,
    localX: shell.x,
    localY: shell.y,
    localZ: shell.z,
    pinned: Boolean(reconciled?.pinned || node.pinned),
    createdAtMs: atMs,
    updatedAtMs: atMs
  };

  if (reconciled) {
    Object.assign(record, copyPosition(reconciled));
    record.anchorX = reconciled.anchorX;
    record.anchorY = reconciled.anchorY;
    record.anchorZ = reconciled.anchorZ;
    record.localX = reconciled.localX;
    record.localY = reconciled.localY;
    record.localZ = reconciled.localZ;
  } else if (type === 'universe') {
    const position = saved ? copyPosition(saved) : universeAnchor(layout, key);
    record.x = position.x;
    record.y = position.y;
    record.z = position.z;
    record.anchorX = position.x;
    record.anchorY = position.y;
    record.anchorZ = position.z;
  } else {
    const parent = layout.positions.get(parentKey(record));
    const origin = parent ? { x: parent.targetX, y: parent.targetY, z: parent.targetZ } :
      universeAnchor(layout, universeKey || key);
    const position = containedGroupPosition(layout, record, saved || {
      x: origin.x + record.localX,
      y: origin.y + record.localY,
      z: origin.z + record.localZ
    });
    record.x = boundedCoordinate(position.x);
    record.y = boundedCoordinate(position.y);
    record.z = boundedCoordinate(position.z);
    if (saved) {
      record.localX = record.x - origin.x;
      record.localY = record.y - origin.y;
      record.localZ = record.z - origin.z;
    }
  }
  record.targetX = record.x;
  record.targetY = record.y;
  record.targetZ = record.z;
  layout.positions.set(key, record);
  if (record.pinned) layout.pinned.add(key);
  return record;
}

function applyRecord(node, record) {
  node.x = record.x;
  node.y = record.y;
  node.z = record.z;
  node.vx = 0;
  node.vy = 0;
  node.vz = 0;
  if (record.pinned) {
    node.fx = record.x;
    node.fy = record.y;
    node.fz = record.z;
  } else {
    delete node.fx;
    delete node.fy;
    delete node.fz;
  }
}

function orderedNodes(graph) {
  return [...(Array.isArray(graph.nodes) ? graph.nodes : [])].sort((left, right) =>
    (TYPE_ORDER[left.type] ?? 3) - (TYPE_ORDER[right.type] ?? 3) ||
    String(left.key || left.id || '').localeCompare(String(right.key || right.id || '')));
}

function synchronizeLayout(layout, graph = {}, atMs = Date.now()) {
  if (layout.disposed) return graph;
  const previousKeys = layout.graphKeys;
  const nodes = orderedNodes(graph);
  const nextKeys = new Set();
  let changed = previousKeys.size !== nodes.length;

  nodes.forEach((node) => {
    const key = String(node.key || node.id || '');
    if (!key || nextKeys.has(key)) return;
    nextKeys.add(key);
    let record = layout.positions.get(key);
    if (!record) {
      record = initialRecord(layout, node, atMs);
      changed = true;
    }
    const oldUniverseKey = record.universeKey;
    const oldGroupKey = record.groupKey;
    record.type = TYPES.has(node.type) ? node.type : record.type;
    record.universeKey = String(node.universeKey || (record.type === 'universe' ? key : record.universeKey));
    record.groupKey = String(node.groupKey || (record.type === 'group' ? key : record.groupKey));
    const parentChanged = oldUniverseKey !== record.universeKey || oldGroupKey !== record.groupKey;
    changed ||= parentChanged || !previousKeys.has(key);

    const reconciledFromKey = String(node.entity?.reconciledFromKey || '');
    if (reconciledFromKey && reconciledFromKey !== key) {
      const hadSaved = layout.saved.has(reconciledFromKey);
      layout.pinned.delete(reconciledFromKey);
      layout.positions.delete(reconciledFromKey);
      layout.saved.delete(reconciledFromKey);
      if (hadSaved) updateSaved(layout, record, atMs);
    }
    if (node.pinned && !record.pinned && layout.pinned.size < layout.config.state.hardPinnedEntities) {
      record.pinned = true;
      layout.pinned.add(key);
    }
    setTarget(layout, record);
    if (parentChanged && layout.reducedMotion && !record.pinned) {
      record.x = record.targetX;
      record.y = record.targetY;
      record.z = record.targetZ;
    }
  });

  layout.graphKeys = nextKeys;
  layout.positions.forEach((record, key) => {
    if (nextKeys.has(key) || record.pinned) return;
    layout.positions.delete(key);
  });
  nodes.forEach((node) => {
    const record = layout.positions.get(String(node.key || node.id || ''));
    if (record) applyRecord(node, record);
  });
  if (changed) layout.sleeping = false;
  return graph;
}

function stepLayout(layout, graph = {}, deltaMs = 16, atMs = Date.now()) {
  synchronizeLayout(layout, graph, atMs);
  if (layout.disposed || layout.frozen) return graph;
  const milliseconds = Math.max(0, Math.min(layout.config.layout.maximumDeltaMs, finite(deltaMs, 16)));
  const frameScale = milliseconds / 16.6667;
  let moving = false;

  layout.graphKeys.forEach((key) => {
    const record = layout.positions.get(key);
    if (!record || record.pinned) return;
    const dx = record.targetX - record.x;
    const dy = record.targetY - record.y;
    const dz = record.targetZ - record.z;
    const distance = Math.hypot(dx, dy, dz);
    if (layout.reducedMotion || distance <= SETTLE_EPSILON) {
      record.x = record.targetX;
      record.y = record.targetY;
      record.z = record.targetZ;
      return;
    }
    const strength = record.type === 'universe' ? layout.config.layout.universeStrength :
      record.type === 'group' ? layout.config.layout.groupStrength : layout.config.layout.radioStrength;
    const amount = 1 - Math.pow(1 - Math.max(0.001, strength), frameScale);
    record.x = boundedCoordinate(record.x + dx * amount);
    record.y = boundedCoordinate(record.y + dy * amount);
    record.z = boundedCoordinate(record.z + dz * amount);
    record.updatedAtMs = atMs;
    moving = true;
  });

  const nodeByKey = new Map((Array.isArray(graph.nodes) ? graph.nodes : [])
    .map((node) => [String(node.key || node.id || ''), node]));
  layout.graphKeys.forEach((key) => {
    const node = nodeByKey.get(key);
    const record = layout.positions.get(key);
    if (node && record) applyRecord(node, record);
  });
  layout.lastStepAtMs = atMs;
  layout.sleeping = !moving;
  return graph;
}

function setLayoutFrozen(layout, frozen = true) {
  layout.frozen = Boolean(frozen);
  if (!layout.frozen) {
    layout.sleeping = [...layout.graphKeys].every((key) => {
      const record = layout.positions.get(key);
      return !record || record.pinned || Math.hypot(record.targetX - record.x,
        record.targetY - record.y, record.targetZ - record.z) <= SETTLE_EPSILON;
    });
  }
  return layout.frozen;
}

function setReducedMotion(layout, reduced = true) {
  layout.reducedMotion = Boolean(reduced);
  if (layout.reducedMotion) {
    layout.positions.forEach((record) => {
      if (record.pinned) return;
      record.x = record.targetX;
      record.y = record.targetY;
      record.z = record.targetZ;
    });
    layout.sleeping = true;
  }
  return layout.reducedMotion;
}

function updateSaved(layout, record, atMs = Date.now()) {
  if (!record) return;
  const value = Object.freeze({
    profileKey: layout.profileKey,
    key: record.key,
    type: record.type,
    x: record.x,
    y: record.y,
    z: record.z,
    pinned: record.pinned,
    updatedAtMs: finite(atMs, Date.now())
  });
  if (layout.saved.has(record.key)) layout.saved.delete(record.key);
  layout.saved.set(record.key, value);
  while (layout.saved.size > layout.config.state.hardSavedLayoutRecords) {
    const removable = [...layout.saved.keys()].find((key) => !layout.pinned.has(key)) ||
      layout.saved.keys().next().value;
    layout.saved.delete(removable);
  }
}

function translateEntity(layout, key, delta = {}, atMs = Date.now()) {
  const root = layout.positions.get(String(key || ''));
  if (!root || layout.disposed) return false;
  let offset = copyPosition(delta);
  if (root.type === 'group') {
    const next = containedGroupPosition(layout, root, {
      x: root.x + offset.x, y: root.y + offset.y, z: root.z + offset.z
    });
    offset = { x: next.x - root.x, y: next.y - root.y, z: next.z - root.z };
  }
  layout.positions.forEach((record) => {
    const included = record.key === root.key ||
      (root.type === 'universe' && record.universeKey === root.key && !record.pinned) ||
      (root.type === 'group' && record.groupKey === root.key && !record.pinned);
    if (!included) return;
    record.x = boundedCoordinate(record.x + offset.x);
    record.y = boundedCoordinate(record.y + offset.y);
    record.z = boundedCoordinate(record.z + offset.z);
    record.targetX = boundedCoordinate(record.targetX + offset.x);
    record.targetY = boundedCoordinate(record.targetY + offset.y);
    record.targetZ = boundedCoordinate(record.targetZ + offset.z);
    record.updatedAtMs = atMs;
  });
  if (root.type === 'universe') {
    root.anchorX += offset.x;
    root.anchorY += offset.y;
    root.anchorZ += offset.z;
  } else {
    root.localX += offset.x;
    root.localY += offset.y;
    root.localZ += offset.z;
  }
  updateSaved(layout, root, atMs);
  layout.sleeping = true;
  return true;
}

function dragEntity(layout, key, position, atMs = Date.now()) {
  const record = layout.positions.get(String(key || ''));
  if (!record) return false;
  const next = containedGroupPosition(layout, record, position);
  const translated = translateEntity(layout, record.key, {
    x: next.x - record.x,
    y: next.y - record.y,
    z: next.z - record.z
  }, atMs);
  if (!translated) return false;
  const correction = { x: next.x - record.x, y: next.y - record.y, z: next.z - record.z };
  record.x = next.x;
  record.y = next.y;
  record.z = next.z;
  record.targetX = boundedCoordinate(record.targetX + correction.x);
  record.targetY = boundedCoordinate(record.targetY + correction.y);
  record.targetZ = boundedCoordinate(record.targetZ + correction.z);
  updateSaved(layout, record, atMs);
  return true;
}

function setLayoutPinned(layout, key, pinned = true, position = null, atMs = Date.now()) {
  const record = layout.positions.get(String(key || ''));
  if (!record) return false;
  if (position) dragEntity(layout, record.key, position, atMs);
  if (pinned && !record.pinned && layout.pinned.size >= layout.config.state.hardPinnedEntities) return false;
  record.pinned = Boolean(pinned);
  if (record.pinned) {
    layout.pinned.add(record.key);
    updateSaved(layout, record, atMs);
  } else {
    layout.pinned.delete(record.key);
    if (layout.saved.has(record.key)) updateSaved(layout, record, atMs);
  }
  return true;
}

function resetTarget(layout, record) {
  if (record.type === 'universe') {
    const anchor = universeAnchor(layout, record.key);
    record.anchorX = anchor.x;
    record.anchorY = anchor.y;
    record.anchorZ = anchor.z;
  } else {
    const slot = localSlot(layout, record);
    record.localX = slot.x;
    record.localY = slot.y;
    record.localZ = slot.z;
  }
}

function unlockLayout(layout, key = null) {
  const selected = key === null ? null : String(key || '');
  const records = [...layout.positions.values()].sort((left, right) =>
    (TYPE_ORDER[left.type] ?? 3) - (TYPE_ORDER[right.type] ?? 3) || left.key.localeCompare(right.key));
  records.forEach((record) => {
    if (selected !== null && record.key !== selected) return;
    record.pinned = false;
    layout.pinned.delete(record.key);
    layout.saved.delete(record.key);
    resetTarget(layout, record);
  });
  records.forEach((record) => setTarget(layout, record));
  if (layout.reducedMotion) setReducedMotion(layout, true);
  else layout.sleeping = false;
  if (selected === null) {
    layout.pinned.clear();
    layout.saved.clear();
  }
  return true;
}

function savedLayoutRecords(layout) {
  return [...layout.saved.values()].sort((left, right) => right.updatedAtMs - left.updatedAtMs ||
    left.key.localeCompare(right.key)).slice(0, layout.config.state.hardSavedLayoutRecords);
}

function restoreLayoutRecords(layout, records = []) {
  if (!Array.isArray(records)) return 0;
  let restored = 0;
  records.slice(0, layout.config.state.hardSavedLayoutRecords).forEach((candidate) => {
    if (!candidate || candidate.profileKey !== layout.profileKey || !String(candidate.key || '').trim()) return;
    const value = {
      profileKey: layout.profileKey,
      key: String(candidate.key).slice(0, 512),
      type: TYPES.has(candidate.type) ? candidate.type : 'radio',
      x: boundedCoordinate(candidate.x),
      y: boundedCoordinate(candidate.y),
      z: boundedCoordinate(candidate.z),
      pinned: Boolean(candidate.pinned),
      updatedAtMs: finite(candidate.updatedAtMs)
    };
    layout.saved.set(value.key, Object.freeze(value));
    restored += 1;
  });
  return restored;
}

function resetLayoutSession(layout) {
  if (!layout || layout.disposed) return false;
  layout.positions.clear();
  layout.pinned.clear();
  layout.graphKeys.clear();
  layout.universeOrder.clear();
  layout.sleeping = true;
  layout.lastStepAtMs = 0;
  return true;
}

function disposeLayout(layout) {
  layout.disposed = true;
  layout.positions.clear();
  layout.saved.clear();
  layout.pinned.clear();
  layout.graphKeys.clear();
  layout.universeOrder.clear();
}

export {
  createLayoutState,
  disposeLayout,
  dragEntity,
  resetLayoutSession,
  restoreLayoutRecords,
  savedLayoutRecords,
  setLayoutFrozen,
  setLayoutPinned,
  setReducedMotion,
  stepLayout,
  synchronizeLayout,
  translateEntity,
  unlockLayout
};
