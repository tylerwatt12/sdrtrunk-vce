'use strict';

import { BALANCED_CONFIG, validateConfig } from './config.js';

const TYPES = new Set(['universe', 'group', 'radio', 'aggregate']);
const COLLISION_RADII = Object.freeze({ universe: 30, group: 18, aggregate: 12, radio: 7 });
const MAXIMUM_COLLISION_RADIUS = Math.max(...Object.values(COLLISION_RADII));

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

function unit(hash, shift = 0) {
  return ((hash >>> shift) & 0xffff) / 0xffff;
}

function copyPosition(position = {}) {
  return {
    x: boundedCoordinate(position.x),
    y: boundedCoordinate(position.y),
    z: boundedCoordinate(position.z)
  };
}

function createLayoutState(config = BALANCED_CONFIG, options = {}) {
  const validated = config === BALANCED_CONFIG ? config : validateConfig(config);
  return {
    config: validated,
    mode: options.mode === 'flat' || options.mode === '2d' ? 'flat' : '3d',
    frozen: Boolean(options.frozen),
    reducedMotion: Boolean(options.reducedMotion),
    profileKey: String(options.profileKey || 'default').slice(0, 128),
    positions: new Map(),
    velocities: new Map(),
    saved: new Map(),
    pinned: new Set(),
    graphKeys: new Set(),
    universeOrder: new Map(),
    settledFrames: 0,
    sleeping: false,
    snapToParents: false,
    disposed: false,
    lastStepAtMs: 0
  };
}

function universeAnchor(layout, key) {
  if (!layout.universeOrder.has(key)) {
    const limit = layout.config.state.hardUniverses;
    if (layout.universeOrder.size >= limit) {
      const activeUniverseKeys = new Set();
      layout.positions.forEach((record) => {
        if (record.universeKey) activeUniverseKeys.add(record.universeKey);
      });
      const removable = [...layout.universeOrder.keys()].find((candidate) =>
        !activeUniverseKeys.has(candidate) && !layout.saved.has(candidate));
      const evicted = removable || layout.universeOrder.keys().next().value;
      if (evicted !== undefined) layout.universeOrder.delete(evicted);
    }
    const used = new Set(layout.universeOrder.values());
    let ordinal = 0;
    while (used.has(ordinal) && ordinal < limit) ordinal += 1;
    layout.universeOrder.set(key, Math.min(ordinal, Math.max(0, limit - 1)));
  }
  const index = layout.universeOrder.get(key);
  const spacing = layout.config.layout.universeSpacing;
  if (index === 0) return { x: 0, y: 0, z: 0 };
  const angle = index * Math.PI * (3 - Math.sqrt(5));
  const radius = spacing * Math.sqrt(index);
  return { x: Math.cos(angle) * radius, y: Math.sin(angle) * radius,
    z: layout.mode === 'flat' ? 0 : ((index % 5) - 2) * spacing * 0.12 };
}

function initialRecord(layout, node, graphNodes, atMs) {
  const key = String(node.key || node.id || '');
  const type = TYPES.has(node.type) ? node.type : 'aggregate';
  const reconciledFromKey = String(node.entity?.reconciledFromKey || '');
  const reconciledRecord = reconciledFromKey ? layout.positions.get(reconciledFromKey) : null;
  const reconciledSaved = reconciledFromKey ? layout.saved.get(reconciledFromKey) : null;
  const saved = layout.saved.get(key) || reconciledSaved;
  const hash = stableHash(key);
  const parentKey = type === 'group' ? node.universeKey : (node.groupKey || node.universeKey);
  const parentPosition = type === 'universe' ? null :
    (layout.positions.get(parentKey) || universeAnchor(layout, node.universeKey || key));
  let position;
  let local = { x: 0, y: 0, z: 0 };
  if (reconciledRecord) {
    position = copyPosition(reconciledRecord);
    local = { x: reconciledRecord.localX, y: reconciledRecord.localY, z: reconciledRecord.localZ };
  } else if (saved) {
    position = copyPosition(saved);
    if (type !== 'universe') {
      local = {
        x: position.x - parentPosition.x,
        y: position.y - parentPosition.y,
        z: layout.mode === 'flat' ? 0 : position.z - parentPosition.z
      };
    }
  } else if (type === 'universe') {
    position = universeAnchor(layout, key);
  } else {
    const radius = type === 'group' ? layout.config.layout.groupOrbitRadius :
      (type === 'aggregate' ? layout.config.layout.radioOrbitRadius * 1.45 : layout.config.layout.radioOrbitRadius);
    const azimuth = unit(hash) * Math.PI * 2;
    const elevation = layout.mode === 'flat' ? 0 : (unit(hash, 16) - 0.5) * Math.PI * 0.75;
    local = {
      x: Math.cos(azimuth) * Math.cos(elevation) * radius,
      y: Math.sin(azimuth) * Math.cos(elevation) * radius,
      z: layout.mode === 'flat' ? 0 : Math.sin(elevation) * radius
    };
    position = {
      x: parentPosition.x + local.x,
      y: parentPosition.y + local.y,
      z: layout.mode === 'flat' ? 0 : parentPosition.z + local.z
    };
  }
  const record = {
    key,
    type,
    universeKey: String(node.universeKey || (type === 'universe' ? key : '')),
    groupKey: String(node.groupKey || (type === 'group' ? key : '')),
    x: boundedCoordinate(position.x),
    y: boundedCoordinate(position.y),
    z: layout.mode === 'flat' ? 0 : boundedCoordinate(position.z),
    anchorX: type === 'universe' ? boundedCoordinate(reconciledRecord?.anchorX ?? position.x) : 0,
    anchorY: type === 'universe' ? boundedCoordinate(reconciledRecord?.anchorY ?? position.y) : 0,
    anchorZ: type === 'universe' && layout.mode !== 'flat' ?
      boundedCoordinate(reconciledRecord?.anchorZ ?? position.z) : 0,
    localX: boundedCoordinate(local.x),
    localY: boundedCoordinate(local.y),
    localZ: layout.mode === 'flat' ? 0 : boundedCoordinate(local.z),
    // Saved coordinates are advisory until the independently observed entity is admitted through
    // the semantic pin budget. The accepted node flag prevents saved records bypassing that cap.
    pinned: Boolean(reconciledRecord?.pinned || node.pinned),
    createdAtMs: atMs,
    updatedAtMs: atMs
  };
  layout.positions.set(key, record);
  layout.velocities.set(key, { x: 0, y: 0, z: 0 });
  if (record.pinned) layout.pinned.add(key);
  return record;
}

function applyRecord(node, record, velocity, mode) {
  node.x = record.x;
  node.y = record.y;
  node.z = mode === 'flat' ? 0 : record.z;
  node.vx = velocity.x;
  node.vy = velocity.y;
  node.vz = mode === 'flat' ? 0 : velocity.z;
  if (record.pinned) {
    node.fx = record.x;
    node.fy = record.y;
    node.fz = mode === 'flat' ? 0 : record.z;
  } else {
    delete node.fx;
    delete node.fy;
    delete node.fz;
  }
}

function synchronizeLayout(layout, graph = {}, atMs = Date.now()) {
  if (layout.disposed) return graph;
  const previousKeys = layout.graphKeys;
  const nodes = Array.isArray(graph.nodes) ? graph.nodes : [];
  const ordered = [...nodes].sort((left, right) => {
    const order = { universe: 0, group: 1, radio: 2, aggregate: 3 };
    return (order[left.type] ?? 3) - (order[right.type] ?? 3) ||
      String(left.key || left.id || '').localeCompare(String(right.key || right.id || ''));
  });
  const nextKeys = new Set();
  ordered.forEach((node) => {
    const key = String(node.key || node.id || '');
    if (!key || nextKeys.has(key)) return;
    nextKeys.add(key);
    let record = layout.positions.get(key);
    if (!record) record = initialRecord(layout, node, ordered, atMs);
    const previousUniverseKey = record.universeKey;
    const previousGroupKey = record.groupKey;
    record.type = TYPES.has(node.type) ? node.type : record.type;
    record.universeKey = String(node.universeKey || (record.type === 'universe' ? key : record.universeKey));
    record.groupKey = String(node.groupKey || (record.type === 'group' ? key : record.groupKey));
    const parentChanged = record.universeKey !== previousUniverseKey || record.groupKey !== previousGroupKey;
    if (parentChanged) {
      layout.sleeping = false;
      layout.settledFrames = 0;
    }
    const reconciledFromKey = String(node.entity?.reconciledFromKey || '');
    let hadReconciledSavedPosition = false;
    if (reconciledFromKey && reconciledFromKey !== key) {
      hadReconciledSavedPosition = layout.saved.has(reconciledFromKey);
      layout.pinned.delete(reconciledFromKey);
      layout.positions.delete(reconciledFromKey);
      layout.velocities.delete(reconciledFromKey);
      layout.saved.delete(reconciledFromKey);
    }
    if (node.pinned && !record.pinned && layout.pinned.size < layout.config.state.hardPinnedEntities) {
      record.pinned = true;
      layout.pinned.add(key);
    }
    if (hadReconciledSavedPosition) updateSaved(layout, record, atMs);
    const velocity = layout.velocities.get(key) || { x: 0, y: 0, z: 0 };
    layout.velocities.set(key, velocity);
    if (record.type === 'radio' && !record.pinned && layout.reducedMotion &&
        (parentChanged || layout.snapToParents)) {
      const parent = layout.positions.get(record.groupKey || record.universeKey);
      if (parent) {
        record.x = boundedCoordinate(parent.x + record.localX);
        record.y = boundedCoordinate(parent.y + record.localY);
        record.z = layout.mode === 'flat' ? 0 : boundedCoordinate(parent.z + record.localZ);
        record.updatedAtMs = atMs;
        velocity.x = 0;
        velocity.y = 0;
        velocity.z = 0;
      }
    }
    applyRecord(node, record, velocity, layout.mode);
  });
  layout.snapToParents = false;
  layout.graphKeys = nextKeys;
  if (previousKeys.size !== nextKeys.size || [...nextKeys].some((key) => !previousKeys.has(key))) {
    layout.sleeping = false;
    layout.settledFrames = 0;
  }
  for (const [key, record] of layout.positions) {
    if (nextKeys.has(key) || record.pinned) continue;
    layout.positions.delete(key);
    layout.velocities.delete(key);
  }
  return graph;
}

function targetFor(layout, record, migrations = new Map(), atMs = Date.now()) {
  if (record.type === 'universe') {
    return { x: record.anchorX, y: record.anchorY, z: layout.mode === 'flat' ? 0 : record.anchorZ,
      strength: layout.config.layout.universeStrength };
  }
  const parentKey = record.type === 'group' ? record.universeKey : (record.groupKey || record.universeKey);
  const parent = layout.positions.get(parentKey);
  if (!parent) return { x: record.x, y: record.y, z: layout.mode === 'flat' ? 0 : record.z, strength: 0 };
  const target = {
    x: parent.x + record.localX,
    y: parent.y + record.localY,
    z: layout.mode === 'flat' ? 0 : parent.z + record.localZ,
    strength: record.type === 'group' ? layout.config.layout.groupStrength : layout.config.layout.radioStrength
  };
  const migration = !layout.reducedMotion && record.type === 'radio' ? migrations.get(record.key) : null;
  if (migration) {
    const oldGroup = layout.positions.get(migration.sourceKey);
    const newGroup = layout.positions.get(migration.targetKey);
    const motionEndsAtMs = Number.isFinite(Number(migration.animationEndsAtMs)) ?
      Math.min(migration.expiresAtMs, Number(migration.animationEndsAtMs)) : migration.expiresAtMs;
    const duration = Math.max(1, motionEndsAtMs - migration.createdAtMs);
    const progress = Math.max(0, Math.min(1, (atMs - migration.createdAtMs) / duration));
    const envelope = Math.sin(progress * Math.PI);
    if (oldGroup && newGroup && progress > 0 && progress < 1) {
      const dx = newGroup.x - oldGroup.x;
      const dy = newGroup.y - oldGroup.y;
      const distance = Math.hypot(dx, dy) || 1;
      const direction = stableHash(record.key) % 2 ? 1 : -1;
      const arc = Math.min(72, Math.max(18, distance * 0.16)) * envelope * direction;
      target.x += (-dy / distance) * arc;
      target.y += (dx / distance) * arc;
      if (layout.mode !== 'flat') target.z += Math.abs(arc) * 0.35;
    }
  }
  return target;
}

function collisionRadius(record) {
  return COLLISION_RADII[record.type] ?? COLLISION_RADII.radio;
}

function collisionImpulse(layout) {
  // Scanning the 3x3x3 neighboring cells is complete only when a cell is at least as wide as the
  // largest possible collision distance. Treat the configured size as a lower bound so a small
  // tuning value cannot make broad-phase bucketing silently miss large universe/group overlaps.
  const size = Math.max(layout.config.layout.collisionCellSize,
    MAXIMUM_COLLISION_RADIUS * 2 + layout.config.layout.collisionPadding);
  const cells = new Map();
  const movable = [...layout.graphKeys].map((key) => layout.positions.get(key)).filter(Boolean);
  movable.forEach((record) => {
    const cell = `${Math.floor(record.x / size)}:${Math.floor(record.y / size)}:` +
      `${layout.mode === 'flat' ? 0 : Math.floor(record.z / size)}`;
    if (!cells.has(cell)) cells.set(cell, []);
    cells.get(cell).push(record);
  });
  const offsets = [-1, 0, 1];
  const visited = new Set();
  cells.forEach((records, cellKey) => {
    const [cellX, cellY, cellZ] = cellKey.split(':').map(Number);
    offsets.forEach((dx) => offsets.forEach((dy) => offsets.forEach((dz) => {
      if (layout.mode === 'flat' && dz !== 0) return;
      const peers = cells.get(`${cellX + dx}:${cellY + dy}:${cellZ + dz}`);
      if (!peers) return;
      records.forEach((left) => peers.forEach((right) => {
        if (left.key === right.key) return;
        const pairKey = left.key < right.key ? `${left.key}\u0000${right.key}` : `${right.key}\u0000${left.key}`;
        if (visited.has(pairKey)) return;
        visited.add(pairKey);
        const x = right.x - left.x;
        const y = right.y - left.y;
        const z = layout.mode === 'flat' ? 0 : right.z - left.z;
        const minimum = collisionRadius(left) + collisionRadius(right) + layout.config.layout.collisionPadding;
        const squared = x * x + y * y + z * z;
        if (squared >= minimum * minimum) return;
        const distance = Math.sqrt(squared) || 0.001;
        const force = Math.min(4, (minimum - distance) * 0.08);
        const nx = squared ? x / distance : (stableHash(pairKey) % 2 ? 1 : -1);
        const ny = squared ? y / distance : (stableHash(pairKey) % 3 ? 0.5 : -0.5);
        const nz = squared && layout.mode !== 'flat' ? z / distance : 0;
        const leftVelocity = layout.velocities.get(left.key);
        const rightVelocity = layout.velocities.get(right.key);
        if (!left.pinned) {
          leftVelocity.x -= nx * force;
          leftVelocity.y -= ny * force;
          leftVelocity.z -= nz * force;
        }
        if (!right.pinned) {
          rightVelocity.x += nx * force;
          rightVelocity.y += ny * force;
          rightVelocity.z += nz * force;
        }
      }));
    })));
  });
}

function stepLayout(layout, graph = {}, deltaMs = 16, atMs = Date.now()) {
  synchronizeLayout(layout, graph, atMs);
  if (layout.disposed || layout.frozen || layout.reducedMotion) return graph;
  const hasMigration = (Array.isArray(graph.effects) ? graph.effects : [])
    .some((effect) => effect?.type === 'migration' &&
      Number(effect.animationEndsAtMs || effect.expiresAtMs) > atMs);
  if (hasMigration) {
    layout.sleeping = false;
    layout.settledFrames = 0;
  } else if (layout.sleeping) return graph;
  const milliseconds = Math.max(0, Math.min(layout.config.layout.maximumDeltaMs, finite(deltaMs, 16)));
  const scale = milliseconds / 16.6667;
  const damping = Math.pow(layout.config.layout.damping, scale);
  const migrations = new Map((Array.isArray(graph.effects) ? graph.effects : [])
    .filter((effect) => effect?.type === 'migration' && effect.nodeKey && effect.expiresAtMs > atMs)
    .map((effect) => [effect.nodeKey, effect]));
  let maximumMotion = 0;
  layout.graphKeys.forEach((key) => {
    const record = layout.positions.get(key);
    const velocity = layout.velocities.get(key);
    if (!record || !velocity || record.pinned) return;
    const target = targetFor(layout, record, migrations, atMs);
    velocity.x += (target.x - record.x) * target.strength * scale;
    velocity.y += (target.y - record.y) * target.strength * scale;
    velocity.z += ((layout.mode === 'flat' ? 0 : target.z) - record.z) *
      (layout.mode === 'flat' ? layout.config.layout.flattenStrength : target.strength) * scale;
  });
  collisionImpulse(layout);
  layout.graphKeys.forEach((key) => {
    const record = layout.positions.get(key);
    const velocity = layout.velocities.get(key);
    if (!record || !velocity) return;
    if (record.pinned) {
      velocity.x = 0;
      velocity.y = 0;
      velocity.z = 0;
    } else {
      velocity.x *= damping;
      velocity.y *= damping;
      velocity.z *= damping;
      const magnitude = Math.hypot(velocity.x, velocity.y, velocity.z);
      maximumMotion = Math.max(maximumMotion, magnitude);
      if (magnitude > layout.config.layout.maximumVelocity) {
        const ratio = layout.config.layout.maximumVelocity / magnitude;
        velocity.x *= ratio;
        velocity.y *= ratio;
        velocity.z *= ratio;
      }
      record.x = boundedCoordinate(record.x + velocity.x * scale);
      record.y = boundedCoordinate(record.y + velocity.y * scale);
      record.z = layout.mode === 'flat' ? 0 : boundedCoordinate(record.z + velocity.z * scale);
      record.updatedAtMs = atMs;
    }
  });
  const nodeByKey = new Map((Array.isArray(graph.nodes) ? graph.nodes : [])
    .map((node) => [String(node.key || node.id || ''), node]));
  layout.graphKeys.forEach((key) => {
    const node = nodeByKey.get(key);
    const record = layout.positions.get(key);
    const velocity = layout.velocities.get(key);
    if (node && record && velocity) applyRecord(node, record, velocity, layout.mode);
  });
  layout.lastStepAtMs = atMs;
  if (!hasMigration && maximumMotion < 0.012) layout.settledFrames += 1;
  else layout.settledFrames = 0;
  layout.sleeping = layout.settledFrames >= 24;
  return graph;
}

function setLayoutMode(layout, mode) {
  layout.mode = mode === 'flat' || mode === '2d' ? 'flat' : '3d';
  if (layout.mode === 'flat') {
    layout.positions.forEach((record) => { record.z = 0; });
    layout.velocities.forEach((velocity) => { velocity.z = 0; });
  }
  layout.sleeping = false;
  layout.settledFrames = 0;
  return layout.mode;
}

function setLayoutFrozen(layout, frozen = true) {
  layout.frozen = Boolean(frozen);
  if (!layout.frozen) {
    layout.sleeping = false;
    layout.settledFrames = 0;
  }
  return layout.frozen;
}

function setReducedMotion(layout, reduced = true) {
  const next = Boolean(reduced);
  if (next && !layout.reducedMotion) layout.snapToParents = true;
  layout.reducedMotion = next;
  if (layout.reducedMotion) {
    layout.velocities.forEach((velocity) => {
      velocity.x = 0;
      velocity.y = 0;
      velocity.z = 0;
    });
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
    const removable = [...layout.saved.keys()].find((key) => !layout.pinned.has(key)) || layout.saved.keys().next().value;
    layout.saved.delete(removable);
  }
}

function translateEntity(layout, key, delta = {}, atMs = Date.now()) {
  const root = layout.positions.get(String(key || ''));
  if (!root || layout.disposed) return false;
  layout.sleeping = false;
  layout.settledFrames = 0;
  const offset = copyPosition(delta);
  const targets = [];
  layout.positions.forEach((record) => {
    if (record.key === root.key) targets.push(record);
    else if (root.type === 'universe' && record.universeKey === root.key && !record.pinned) targets.push(record);
    else if (root.type === 'group' && record.groupKey === root.key && !record.pinned) targets.push(record);
  });
  targets.forEach((record) => {
    record.x = boundedCoordinate(record.x + offset.x);
    record.y = boundedCoordinate(record.y + offset.y);
    record.z = layout.mode === 'flat' ? 0 : boundedCoordinate(record.z + offset.z);
    record.updatedAtMs = atMs;
    const velocity = layout.velocities.get(record.key);
    if (velocity) velocity.x = velocity.y = velocity.z = 0;
  });
  if (root.type === 'universe') {
    root.anchorX += offset.x;
    root.anchorY += offset.y;
    root.anchorZ = layout.mode === 'flat' ? 0 : root.anchorZ + offset.z;
  } else if (root.type === 'group') {
    root.localX += offset.x;
    root.localY += offset.y;
    root.localZ = layout.mode === 'flat' ? 0 : root.localZ + offset.z;
  } else {
    root.localX += offset.x;
    root.localY += offset.y;
    root.localZ = layout.mode === 'flat' ? 0 : root.localZ + offset.z;
  }
  // Only deliberate interaction writes preferences. Physics steps never call this path.
  updateSaved(layout, root, atMs);
  return true;
}

function dragEntity(layout, key, position, atMs = Date.now()) {
  const record = layout.positions.get(String(key || ''));
  if (!record) return false;
  const next = copyPosition(position);
  const translated = translateEntity(layout, record.key, {
    x: next.x - record.x,
    y: next.y - record.y,
    z: layout.mode === 'flat' ? 0 : next.z - record.z
  }, atMs);
  if (!translated) return false;
  // Preserve the exact pointer-plane coordinate instead of retaining floating-point residue from
  // translating an already-computed orbit position. Descendants still receive the same cluster delta.
  record.x = next.x;
  record.y = next.y;
  record.z = layout.mode === 'flat' ? 0 : next.z;
  updateSaved(layout, record, atMs);
  return true;
}

function setLayoutPinned(layout, key, pinned = true, position = null, atMs = Date.now()) {
  const record = layout.positions.get(String(key || ''));
  if (!record) return false;
  layout.sleeping = false;
  layout.settledFrames = 0;
  if (position) dragEntity(layout, record.key, position, atMs);
  if (pinned && !record.pinned && layout.pinned.size >= layout.config.state.hardPinnedEntities) return false;
  record.pinned = Boolean(pinned);
  if (record.pinned) {
    layout.pinned.add(record.key);
    updateSaved(layout, record, atMs);
  } else {
    layout.pinned.delete(record.key);
    const saved = layout.saved.get(record.key);
    if (saved) updateSaved(layout, record, atMs);
  }
  return true;
}

function unlockLayout(layout, key = null) {
  const selected = key === null ? null : String(key || '');
  layout.sleeping = false;
  layout.settledFrames = 0;
  layout.positions.forEach((record) => {
    if (selected !== null && record.key !== selected) return;
    record.pinned = false;
    layout.pinned.delete(record.key);
    layout.saved.delete(record.key);
  });
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
  layout.velocities.clear();
  layout.pinned.clear();
  layout.graphKeys.clear();
  layout.universeOrder.clear();
  layout.settledFrames = 0;
  layout.sleeping = false;
  layout.snapToParents = false;
  layout.lastStepAtMs = 0;
  return true;
}

function disposeLayout(layout) {
  layout.disposed = true;
  layout.positions.clear();
  layout.velocities.clear();
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
  setLayoutMode,
  setLayoutPinned,
  setReducedMotion,
  stepLayout,
  synchronizeLayout,
  translateEntity,
  unlockLayout
};
