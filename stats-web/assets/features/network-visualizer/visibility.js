'use strict';

const PRIORITY = Object.freeze({
  selectedOrDragged: 1_000_000_000_000,
  active: 10_000_000_000,
  matched: 500_000_000,
  migration: 100_000_000,
  pending: 50_000_000,
  pinned: 10_000_000,
  freshness: 100_000
});

function compareRank(left, right) {
  return right.score - left.score || right.lastMeaningfulAtMs - left.lastMeaningfulAtMs ||
    left.key.localeCompare(right.key);
}

function selectedWithin(state, entity) {
  const selected = state.visual.selectedKey;
  if (!selected) return false;
  if (selected === entity.key) return true;
  if (entity.type === 'universe') {
    const group = state.groups.get(selected);
    const radio = state.radios.get(selected);
    return group?.universeKey === entity.key || radio?.universeKey === entity.key;
  }
  if (entity.type === 'group') return state.radios.get(selected)?.visualParentGroupKey === entity.key ||
    entity.radioKeys.has(selected);
  return false;
}

function radioActive(state, radio) {
  return radio.activeCallKeys.size > 0;
}

function groupActive(group) {
  return group.activeCallKeys.size > 0;
}

function universeActive(state, universe) {
  const bucketActive = (bucket) => Boolean(bucket && (bucket.count > 0 || bucket.saturated));
  return bucketActive(state.overflowActive.get(universe.key)) || [...universe.groupKeys].some((key) =>
    groupActive(state.groups.get(key) || { activeCallKeys: new Set() }) ||
    bucketActive(state.overflowActive.get(key)));
}

function residenceBoost(state, key, atMs) {
  const membership = state.visual.membership.get(key);
  if (!membership) return 0;
  const age = atMs - membership.visibleSinceMs;
  return age < state.config.render.minimumResidenceMs ? state.config.render.rankHysteresis * 2 :
    state.config.render.rankHysteresis;
}

function searchable(entity) {
  return [entity.key, entity.label, entity.displayId, entity.identityKey, entity.protocol, entity.wacn,
    entity.systemId].filter(Boolean).join(' ').toLowerCase();
}

function queryMatch(entity, query) {
  return Boolean(query) && searchable(entity).includes(query);
}

function freshnessScore(entity, atMs) {
  return Math.max(0, Math.min(PRIORITY.freshness,
    PRIORITY.freshness - Math.floor((atMs - entity.lastMeaningfulAtMs) / 10)));
}

function radioRank(state, radio, atMs, query = '') {
  const selected = state.visual.selectedKey === radio.key;
  const dragged = state.visual.draggedKeys.has(radio.key);
  const active = radioActive(state, radio);
  const migrated = atMs - radio.lastMigrationAtMs <= state.config.render.migrationTrailTtlMs;
  const matched = queryMatch(radio, query);
  const score = (selected || dragged ? PRIORITY.selectedOrDragged : 0) +
    (active ? PRIORITY.active : 0) + (matched ? PRIORITY.matched : 0) +
    (migrated ? PRIORITY.migration : 0) + (radio.pinned ? PRIORITY.pinned : 0) +
    freshnessScore(radio, atMs) +
    residenceBoost(state, radio.key, atMs);
  return { key: radio.key, entity: radio, score, lastMeaningfulAtMs: radio.lastMeaningfulAtMs,
    forced: selected || dragged || matched || active || migrated || radio.pinned, active, matched };
}

function groupRank(state, group, atMs, query = '') {
  const active = groupActive(group);
  const pending = group.pendingGrantUntilMs > atMs;
  const selected = selectedWithin(state, group);
  const dragged = state.visual.draggedKeys.has(group.key);
  const matched = queryMatch(group, query) || (Boolean(query) && [...group.radioKeys]
    .some((key) => queryMatch(state.radios.get(key) || {}, query)));
  const radios = [...group.radioKeys].map((key) => state.radios.get(key)).filter(Boolean);
  const selectedOrDraggedRadio = radios.some((radio) => state.visual.selectedKey === radio.key ||
    state.visual.draggedKeys.has(radio.key));
  const activeRadio = radios.some((radio) => radioActive(state, radio));
  const migratedRadio = radios.some((radio) =>
    atMs - radio.lastMigrationAtMs <= state.config.render.migrationTrailTtlMs);
  const pinnedRadio = radios.some((radio) => radio.pinned);
  const score = (selected || dragged || selectedOrDraggedRadio ? PRIORITY.selectedOrDragged : 0) +
    (active || activeRadio ? PRIORITY.active : 0) + (matched ? PRIORITY.matched : 0) +
    (migratedRadio ? PRIORITY.migration : 0) + (pending ? PRIORITY.pending : 0) +
    (group.pinned || pinnedRadio ? PRIORITY.pinned : 0) + freshnessScore(group, atMs) +
    residenceBoost(state, group.key, atMs);
  return { key: group.key, entity: group, score, lastMeaningfulAtMs: group.lastMeaningfulAtMs,
    forced: selected || dragged || selectedOrDraggedRadio || matched || active || activeRadio || pending ||
      group.pinned || pinnedRadio || migratedRadio,
    active, pending, matched };
}

function universeRank(state, universe, atMs, query = '', signal = {}) {
  const active = signal.active ?? universeActive(state, universe);
  const pending = universe.pendingGrantUntilMs > atMs;
  const selected = selectedWithin(state, universe);
  const dragged = state.visual.draggedKeys.has(universe.key);
  const migration = Boolean(signal.migration);
  const matched = queryMatch(universe, query) || Boolean(signal.matched);
  const selectedOrDraggedDescendant = Boolean(signal.selectedOrDragged);
  const pinnedDescendant = Boolean(signal.pinned);
  const score = (selected || dragged || selectedOrDraggedDescendant ? PRIORITY.selectedOrDragged : 0) +
    (active ? PRIORITY.active : 0) + (matched ? PRIORITY.matched : 0) +
    (migration ? PRIORITY.migration : 0) + (pending ? PRIORITY.pending : 0) +
    (universe.pinned || pinnedDescendant ? PRIORITY.pinned : 0) + freshnessScore(universe, atMs) +
    residenceBoost(state, universe.key, atMs);
  return { key: universe.key, entity: universe, score, lastMeaningfulAtMs: universe.lastMeaningfulAtMs,
    forced: selected || dragged || selectedOrDraggedDescendant || matched || active || pending || migration ||
      universe.pinned || pinnedDescendant,
    active, pending, matched };
}

function universeSignals(state, atMs, query) {
  const signals = new Map();
  const signalFor = (key) => {
    if (!signals.has(key)) signals.set(key, { active: false, migration: false, matched: false,
      selectedOrDragged: false, pinned: false });
    return signals.get(key);
  };
  state.groups.forEach((group) => {
    const signal = signalFor(group.universeKey);
    signal.active ||= groupActive(group);
    signal.selectedOrDragged ||= state.visual.draggedKeys.has(group.key);
    signal.pinned ||= group.pinned;
    if (query) signal.matched ||= queryMatch(group, query);
  });
  state.radios.forEach((radio) => {
    const signal = signalFor(radio.universeKey);
    signal.active ||= radioActive(state, radio);
    signal.migration ||= atMs - radio.lastMigrationAtMs <= state.config.render.migrationTrailTtlMs;
    signal.selectedOrDragged ||= state.visual.draggedKeys.has(radio.key);
    signal.pinned ||= radio.pinned;
    if (query) signal.matched ||= queryMatch(radio, query);
  });
  state.overflowActive.forEach((bucket) => {
    if (bucket.count > 0 || bucket.saturated) signalFor(bucket.universeKey).active = true;
  });
  return signals;
}

function commonNode(state, entity, type, atMs) {
  const active = type === 'radio' ? radioActive(state, entity) :
    (type === 'group' ? groupActive(entity) : universeActive(state, entity));
  return {
    id: entity.key,
    key: entity.key,
    type,
    label: entity.label,
    universeKey: type === 'universe' ? entity.key : entity.universeKey,
    groupKey: type === 'radio' ? entity.visualParentGroupKey : (type === 'group' ? entity.key : ''),
    identityKey: entity.identityKey || '',
    kind: entity.kind || '',
    protocol: entity.protocol || '',
    active,
    afterglow: !active && Number(entity.afterglowUntilMs || 0) > atMs,
    pending: Number(entity.pendingGrantUntilMs || 0) > atMs,
    encrypted: type === 'radio' && [...entity.activeCallKeys]
      .some((key) => state.activeCalls.get(key)?.encrypted === true),
    pinned: entity.pinned,
    selected: state.visual.selectedKey === entity.key,
    quiet: !active && atMs - entity.lastMeaningfulAtMs >= state.config.render.radioQuietAfterMs,
    collapsed: false,
    retainedCount: type === 'group' ? entity.radioKeys.size :
      (type === 'universe' ? entity.groupKeys.size : 1),
    suppressedActiveCount: 0,
    entity
  };
}

function aggregateNode(key, label, universeKey, groupKey, retainedCount, suppressedActiveCount, kind,
  approximate = false, focusKey = '') {
  return {
    id: key,
    key,
    type: 'aggregate',
    label,
    universeKey,
    groupKey,
    identityKey: '',
    kind,
    protocol: '',
    active: suppressedActiveCount > 0,
    encrypted: false,
    pinned: false,
    selected: false,
    quiet: suppressedActiveCount === 0,
    collapsed: true,
    retainedCount,
    suppressedActiveCount,
    suppressedActiveCallLegs: 0,
    approximate,
    focusKey,
    entity: null
  };
}

function filteredUniverses(state, options) {
  const protocols = options.protocols instanceof Set ? options.protocols :
    (Array.isArray(options.protocols) ? new Set(options.protocols) : null);
  return [...state.universes.values()].filter((universe) => {
    if (options.showConventional === false && universe.kind === 'channel') return false;
    return !protocols || protocols.has(universe.protocol);
  });
}

function resolveScope(state, requested, universes) {
  if (!requested || typeof requested !== 'object') return Object.freeze({ level: 'all' });
  const availableUniverseKeys = new Set(universes.map((universe) => universe.key));
  if (requested.level === 'system' || requested.level === 'group') {
    const universeKey = String(requested.universeKey || '');
    if (!universeKey || !availableUniverseKeys.has(universeKey)) {
      return Object.freeze({ level: 'overview' });
    }
    if (requested.level === 'group') {
      const groupKey = String(requested.groupKey || '');
      const group = state.groups.get(groupKey);
      if (group && group.universeKey === universeKey) {
        return Object.freeze({ level: 'group', universeKey, groupKey });
      }
    }
    return Object.freeze({ level: 'system', universeKey });
  }
  return Object.freeze({ level: 'overview' });
}

function scopedUniverses(universes, scope) {
  if (scope.level !== 'system' && scope.level !== 'group') return universes;
  return universes.filter((universe) => universe.key === scope.universeKey);
}

function scopeIncludesGroup(scope, group) {
  if (!group || scope.level === 'overview') return false;
  if (scope.level === 'all') return true;
  if (group.universeKey !== scope.universeKey) return false;
  return scope.level === 'system' || group.key === scope.groupKey;
}

function scopeIncludesRadio(state, scope, radio) {
  if (!radio || (scope.level !== 'all' && scope.level !== 'system' && scope.level !== 'group')) return false;
  if (scope.level === 'all') return true;
  if (radio.universeKey !== scope.universeKey) return false;
  if (scope.level === 'system') return true;
  return state.groups.get(scope.groupKey)?.radioKeys.has(radio.key) === true;
}

function scopeIncludesOverflowBucket(scope, bucket, allowedUniverseKeys) {
  if (!bucket || !allowedUniverseKeys.has(bucket.universeKey)) return false;
  if (scope.level === 'overview') return true;
  if (scope.level === 'group') return bucket.groupKey === scope.groupKey;
  return scope.level === 'all' || scope.level === 'system';
}

function prioritizedVisibilityPaths(state, allowedUniverseKeys, maximumNodes, atMs, scope) {
  const candidates = [];
  const entityFor = (key) => state.radios.get(key) || state.groups.get(key) || state.universes.get(key);
  const entityPath = (entity) => {
    if (!entity) return null;
    const universeKey = entity.type === 'universe' ? entity.key : entity.universeKey;
    if (!allowedUniverseKeys.has(universeKey)) return null;
    const path = { universeKeys: new Set([universeKey]), groupKeys: new Set(), radioKeys: new Set() };
    if (entity.type === 'group' && scopeIncludesGroup(scope, entity)) path.groupKeys.add(entity.key);
    if (entity.type === 'radio') {
      if (!scopeIncludesRadio(state, scope, entity)) {
        if (scope.level === 'system') {
          const parent = state.groups.get(entity.visualParentGroupKey);
          if (parent && scopeIncludesGroup(scope, parent)) path.groupKeys.add(parent.key);
        }
        return scope.level === 'overview' || path.groupKeys.size ? path : null;
      }
      path.radioKeys.add(entity.key);
      const parentKey = scope.level === 'group' ? scope.groupKey : entity.visualParentGroupKey;
      const parent = state.groups.get(parentKey);
      if (parent && scopeIncludesGroup(scope, parent)) path.groupKeys.add(parent.key);
    }
    return path;
  };
  const addCandidate = (candidate) => {
    if (!candidate?.path) return;
    candidates.push(candidate);
  };
  if (state.visual.selectedKey) {
    const entity = entityFor(state.visual.selectedKey);
    addCandidate({ tier: 500, observedAtMs: entity?.lastMeaningfulAtMs || 0,
      key: `selected:${state.visual.selectedKey}`, path: entityPath(entity) });
  }
  state.visual.draggedKeys.forEach((key) => {
    const entity = entityFor(key);
    addCandidate({ tier: 490, observedAtMs: entity?.lastMeaningfulAtMs || 0,
      key: `dragged:${key}`, path: entityPath(entity) });
  });
  state.activeCalls.forEach((call) => {
    const group = state.groups.get(call.groupKey);
    const radio = state.radios.get(call.radioKey);
    const universeKey = group?.universeKey || radio?.universeKey || call.universeKey;
    if (!universeKey || !allowedUniverseKeys.has(universeKey)) return;
    if (group && !scopeIncludesGroup(scope, group) && scope.level !== 'overview') return;
    const path = { universeKeys: new Set([universeKey]), groupKeys: new Set(), radioKeys: new Set() };
    if (group?.universeKey === universeKey && scopeIncludesGroup(scope, group)) path.groupKeys.add(group.key);
    if (radio?.universeKey === universeKey && scopeIncludesRadio(state, scope, radio)) {
      path.radioKeys.add(radio.key);
    }
    addCandidate({ tier: 400, observedAtMs: call.lastObservedAtMs || call.startedAtMs || 0,
      key: `active:${call.key}`, path });
  });
  state.pendingEffects.filter((effect) => effect.type === 'migration' && effect.expiresAtMs > atMs)
    .forEach((effect) => {
      const radio = state.radios.get(effect.nodeKey);
      if (!radio || !allowedUniverseKeys.has(radio.universeKey)) return;
      const destination = state.groups.get(effect.targetKey);
      const source = state.groups.get(effect.sourceKey);
      if (!scopeIncludesRadio(state, scope, radio) && scope.level !== 'overview' &&
          !scopeIncludesGroup(scope, destination) && !scopeIncludesGroup(scope, source)) return;
      const path = { universeKeys: new Set([radio.universeKey]), groupKeys: new Set(),
        radioKeys: new Set(scopeIncludesRadio(state, scope, radio) ? [radio.key] : []) };
      if (destination?.universeKey === radio.universeKey && scopeIncludesGroup(scope, destination)) {
        path.groupKeys.add(destination.key);
      }
      addCandidate({ tier: 300, observedAtMs: effect.createdAtMs || radio.lastMigrationAtMs,
        key: `migration:${effect.id}`, path });
      if (source?.universeKey === radio.universeKey && scopeIncludesGroup(scope, source)) {
        addCandidate({ tier: 299, observedAtMs: effect.createdAtMs || radio.lastMigrationAtMs,
          key: `migration-source:${effect.id}`,
          path: { universeKeys: new Set([radio.universeKey]), groupKeys: new Set([source.key]),
            radioKeys: new Set() } });
      }
    });
  state.radios.forEach((radio) => {
    if (atMs - radio.lastMigrationAtMs > state.config.render.migrationTrailTtlMs) return;
    addCandidate({ tier: 298, observedAtMs: radio.lastMigrationAtMs,
      key: `recent-migration:${radio.key}`, path: entityPath(radio) });
  });
  const pinnedKeys = new Set(state.visual.pinnedKeys);
  state.universes.forEach((entity) => { if (entity.pinned) pinnedKeys.add(entity.key); });
  state.groups.forEach((entity) => { if (entity.pinned) pinnedKeys.add(entity.key); });
  state.radios.forEach((entity) => { if (entity.pinned) pinnedKeys.add(entity.key); });
  pinnedKeys.forEach((key) => {
    const entity = entityFor(key);
    addCandidate({ tier: 200, observedAtMs: entity?.lastMeaningfulAtMs || 0,
      key: `pinned:${key}`, path: entityPath(entity) });
  });
  candidates.sort((left, right) => right.tier - left.tier ||
    right.observedAtMs - left.observedAtMs || left.key.localeCompare(right.key));

  const highPriorityUniverseKeys = new Set();
  const highPriorityGroupKeys = new Set();
  const highPriorityRadioKeys = new Set();
  let hasActiveCandidate = false;
  candidates.filter((candidate) => candidate.tier >= 400).forEach((candidate) => {
    hasActiveCandidate ||= candidate.tier === 400;
    candidate.path.universeKeys.forEach((key) => highPriorityUniverseKeys.add(key));
    candidate.path.groupKeys.forEach((key) => highPriorityGroupKeys.add(key));
    candidate.path.radioKeys.forEach((key) => highPriorityRadioKeys.add(key));
  });
  const highPriorityNodes = (scope.level === 'group' ? 0 : highPriorityUniverseKeys.size) + highPriorityGroupKeys.size +
    highPriorityRadioKeys.size;
  const hasOverflowActivity = [...state.overflowActive.values()]
    .some((bucket) => (scopeIncludesOverflowBucket(scope, bucket, allowedUniverseKeys) ||
      ((scope.level === 'all' || scope.level === 'overview') && !state.universes.has(bucket.universeKey))) &&
      (bucket.count > 0 || bucket.saturated));
  const reserveActiveAggregate = hasOverflowActivity || (hasActiveCandidate && highPriorityNodes > maximumNodes);
  const pathBudget = Math.max(1, maximumNodes - (reserveActiveAggregate ? 1 : 0));
  const universeKeys = new Set();
  const groupKeys = new Set();
  const radioKeys = new Set();
  candidates.forEach((candidate) => {
    const additions = (scope.level === 'group' ? 0 :
      [...candidate.path.universeKeys].filter((key) => !universeKeys.has(key)).length) +
      [...candidate.path.groupKeys].filter((key) => !groupKeys.has(key)).length +
      [...candidate.path.radioKeys].filter((key) => !radioKeys.has(key)).length;
    const currentSize = (scope.level === 'group' ? 0 : universeKeys.size) + groupKeys.size + radioKeys.size;
    if (currentSize + additions > pathBudget) return;
    candidate.path.universeKeys.forEach((key) => universeKeys.add(key));
    candidate.path.groupKeys.forEach((key) => groupKeys.add(key));
    candidate.path.radioKeys.forEach((key) => radioKeys.add(key));
  });
  return { universeKeys, groupKeys, radioKeys, reserveActiveAggregate };
}

function rememberVisible(state, keys, atMs) {
  const visible = new Set(keys);
  visible.forEach((key) => {
    const previous = state.visual.membership.get(key);
    state.visual.membership.set(key, {
      visibleSinceMs: previous?.visibleSinceMs ?? atMs,
      lastVisibleAtMs: atMs
    });
  });
  const cutoff = atMs - Math.max(state.config.render.minimumResidenceMs * 4,
    state.config.render.radioHideAfterMs);
  state.visual.membership.forEach((membership, key) => {
    if (!visible.has(key) && membership.lastVisibleAtMs < cutoff) state.visual.membership.delete(key);
  });
}

function selectVisibleGraph(state, atMs = Date.now(), options = {}) {
  const now = Number.isFinite(Number(atMs)) ? Number(atMs) : Date.now();
  const limits = state.config.render;
  const filters = options.filters && typeof options.filters === 'object' ? options.filters : {};
  const query = String(options.query || '').trim().toLowerCase().slice(0, 128);
  const showAffiliations = options.showAffiliations ?? filters.affiliations ?? true;
  const showActivity = options.showActivity ?? filters.activity ?? true;
  const showQuiet = options.showQuiet ?? filters.quiet ?? true;
  const softRadiosTotal = Math.max(1, Math.min(limits.softRadiosTotal, limits.hardNodes,
    Math.trunc(Number(options.softRadiosTotal) || limits.softRadiosTotal)));
  const hardLabels = Math.max(0, Math.min(limits.hardLabels, limits.hardNodes,
    Math.trunc(Number(options.hardLabels) || limits.hardLabels)));
  const nodes = [];
  const aggregates = [];
  const visibleKeys = new Set();
  const availableUniverses = filteredUniverses(state, options);
  const scope = resolveScope(state, options.scope, availableUniverses);
  const showScopedGroups = scope.level === 'all' || scope.level === 'system' || scope.level === 'group';
  const showScopedRadios = scope.level === 'all' || scope.level === 'system' || scope.level === 'group';
  const signals = universeSignals(state, now, query);
  const universes = scopedUniverses(availableUniverses, scope);
  const renderUniverseNodes = scope.level !== 'group';
  const allowedUniverseKeys = new Set(universes.map((universe) => universe.key));
  const protectedPaths = prioritizedVisibilityPaths(state, allowedUniverseKeys, limits.hardNodes, now, scope);
  const entityNodeLimit = Math.max(1,
    limits.hardNodes - (protectedPaths.reserveActiveAggregate ? 1 : 0));
  const universeRanks = universes
    .map((entity) => universeRank(state, entity, now, query, signals.get(entity.key)))
    .sort(compareRank);

  const reservedDescendants = protectedPaths.groupKeys.size + protectedPaths.radioKeys.size;
  const maximumUniverseSlots = Math.max(protectedPaths.universeKeys.size,
    entityNodeLimit - reservedDescendants);
  let universeSlots = Math.min(universeRanks.length, maximumUniverseSlots);
  if (!protectedPaths.reserveActiveAggregate && universeRanks.length > universeSlots &&
      universeSlots > protectedPaths.universeKeys.size) {
    universeSlots -= 1;
  }
  const shownUniverses = universeRanks.filter((rank) => protectedPaths.universeKeys.has(rank.key));
  universeRanks.forEach((rank) => {
    if (shownUniverses.length < universeSlots && !shownUniverses.includes(rank)) shownUniverses.push(rank);
  });
  const shownUniverseKeys = new Set(shownUniverses.map((rank) => rank.key));
  const hiddenUniverses = universeRanks.filter((rank) => !shownUniverseKeys.has(rank.key));
  if (renderUniverseNodes) shownUniverses.forEach(({ entity }) => {
    const node = commonNode(state, entity, 'universe', now);
    nodes.push(node);
    visibleKeys.add(node.key);
  });
  if (renderUniverseNodes && hiddenUniverses.length && nodes.length < limits.hardNodes - reservedDescendants) {
    const active = hiddenUniverses.some((rank) => rank.active);
    const aggregate = aggregateNode('aggregate:universes', `+${hiddenUniverses.length} retained universes`, '', '',
      hiddenUniverses.length, 0, 'universes', false, hiddenUniverses[0]?.key || '');
    aggregate.active = active;
    aggregate.quiet = !active;
    nodes.push(aggregate);
    aggregates.push(aggregate);
    visibleKeys.add(aggregate.key);
  }

  const forcedUniverses = showScopedGroups ? shownUniverses.filter((rank) => rank.forced ||
    protectedPaths.universeKeys.has(rank.key) || scope.level === 'system' || scope.level === 'group') : [];
  const expandedUniverseRanks = [...forcedUniverses];
  shownUniverses.forEach((rank) => {
    if (expandedUniverseRanks.length < limits.softExpandedUniverses && !expandedUniverseRanks.includes(rank)) {
      expandedUniverseRanks.push(rank);
    }
  });
  const expandedUniverseKeys = new Set(expandedUniverseRanks.map((rank) => rank.key));
  shownUniverses.forEach((rank) => {
    const node = nodes.find((candidate) => candidate.key === rank.key);
    if (!node) return;
    node.collapsed = !showScopedGroups || !expandedUniverseKeys.has(node.key) ||
      (!rank.forced &&
        scope.level === 'all' &&
        now - node.entity.lastMeaningfulAtMs >= limits.universeCollapseAfterMs);
    if (node.collapsed) expandedUniverseKeys.delete(node.key);
  });

  const groupRanks = [];
  const hiddenGroupsByUniverse = new Map();
  shownUniverses.forEach(({ entity: universe }) => {
    const groups = showScopedGroups ? [...universe.groupKeys].map((key) => state.groups.get(key))
      .filter((group) => scopeIncludesGroup(scope, group)) : [];
    if (!expandedUniverseKeys.has(universe.key)) {
      if (showScopedGroups) hiddenGroupsByUniverse.set(universe.key, groups);
      return;
    }
    groups.forEach((group) => groupRanks.push(groupRank(state, group, now, query)));
  });
  groupRanks.sort(compareRank);
  const requiredGroupRanks = groupRanks.filter((rank) => protectedPaths.groupKeys.has(rank.key));
  const forcedGroups = groupRanks.filter((rank) => rank.forced);
  const selectedGroupRanks = [...requiredGroupRanks];
  forcedGroups.forEach((rank) => {
    if (!selectedGroupRanks.includes(rank)) selectedGroupRanks.push(rank);
  });
  groupRanks.forEach((rank) => {
    if (selectedGroupRanks.length < limits.softExpandedGroups && !selectedGroupRanks.includes(rank)) {
      selectedGroupRanks.push(rank);
    }
  });
  const needsGroupAggregateSlot = groupRanks.length > 0 ||
    [...hiddenGroupsByUniverse.values()].some((groups) => groups.length > 0);
  const renderedUniverseCount = renderUniverseNodes ? shownUniverses.length : 0;
  const groupSlotsBeforeAggregate = Math.max(0, (protectedPaths.reserveActiveAggregate ?
    entityNodeLimit - renderedUniverseCount : limits.hardNodes - nodes.length) - protectedPaths.radioKeys.size);
  const hiddenGroupsWithoutAggregate = [...hiddenGroupsByUniverse.values()].some((groups) => groups.length > 0) ||
    groupRanks.length > Math.min(selectedGroupRanks.length, groupSlotsBeforeAggregate);
  const reserveGroupAggregate = !protectedPaths.reserveActiveAggregate && needsGroupAggregateSlot &&
    hiddenGroupsWithoutAggregate &&
    groupSlotsBeforeAggregate > requiredGroupRanks.length;
  const availableGroupSlots = Math.max(0, groupSlotsBeforeAggregate - (reserveGroupAggregate ? 1 : 0));
  const shownGroupRanks = selectedGroupRanks.slice(0, availableGroupSlots);
  const shownGroupKeys = new Set(shownGroupRanks.map((rank) => rank.key));
  groupRanks.filter((rank) => !shownGroupKeys.has(rank.key)).forEach((rank) => {
    if (!hiddenGroupsByUniverse.has(rank.entity.universeKey)) hiddenGroupsByUniverse.set(rank.entity.universeKey, []);
    hiddenGroupsByUniverse.get(rank.entity.universeKey).push(rank.entity);
  });
  shownGroupRanks.forEach((rank) => {
    const { entity } = rank;
    const node = commonNode(state, entity, 'group', now);
    node.collapsed = !showScopedRadios || (!rank.forced && scope.level === 'all' &&
      now - entity.lastMeaningfulAtMs >= limits.groupCollapseAfterMs);
    nodes.push(node);
    visibleKeys.add(node.key);
  });

  const radioRanks = [];
  shownGroupRanks.forEach(({ entity: group }) => {
    const groupNode = nodes.find((node) => node.key === group.key);
    if (groupNode?.collapsed) return;
    const ranked = [...group.radioKeys].map((key) => state.radios.get(key)).filter(Boolean)
      .filter((radio) => showQuiet || radioActive(state, radio) || radio.pinned ||
        state.visual.selectedKey === radio.key || state.visual.draggedKeys.has(radio.key) ||
        now - radio.lastMeaningfulAtMs < limits.radioQuietAfterMs)
      .map((radio) => radioRank(state, radio, now, query)).sort(compareRank);
    const forced = ranked.filter((rank) => rank.forced);
    const selected = [...forced];
    ranked.forEach((rank) => {
      if (selected.length < limits.softRadiosPerGroup && !selected.includes(rank) &&
          (rank.forced || now - rank.entity.lastMeaningfulAtMs < limits.radioHideAfterMs)) selected.push(rank);
    });
    selected.forEach((rank) => radioRanks.push({ ...rank, groupKey: group.key }));
  });
  if (scope.level === 'all' || scope.level === 'system') shownUniverses.forEach(({ entity: universe }) => {
    if (!expandedUniverseKeys.has(universe.key)) return;
    universe.radioKeys.forEach((key) => {
      const radio = state.radios.get(key);
      if (!radio || radio.relatedGroupKeys.size) return;
      if (!showQuiet && !radioActive(state, radio) && !radio.pinned &&
          state.visual.selectedKey !== radio.key && !state.visual.draggedKeys.has(radio.key) &&
          now - radio.lastMeaningfulAtMs >= limits.radioQuietAfterMs) return;
      radioRanks.push({ ...radioRank(state, radio, now, query), groupKey: '' });
    });
  });
  protectedPaths.radioKeys.forEach((key) => {
    const radio = state.radios.get(key);
    if (!radio || !shownUniverseKeys.has(radio.universeKey)) return;
    const groupKey = shownGroupRanks.some((rank) => rank.key === radio.visualParentGroupKey) ?
      radio.visualParentGroupKey : '';
    radioRanks.push({ ...radioRank(state, radio, now, query), groupKey });
  });
  const uniqueRadioRanks = new Map();
  radioRanks.forEach((rank) => {
    const previous = uniqueRadioRanks.get(rank.key);
    if (!previous || compareRank(rank, previous) < 0) uniqueRadioRanks.set(rank.key, rank);
  });
  radioRanks.length = 0;
  radioRanks.push(...[...uniqueRadioRanks.values()].sort(compareRank));
  const requiredRadioRanks = radioRanks.filter((rank) => protectedPaths.radioKeys.has(rank.key));
  const forcedRadios = radioRanks.filter((rank) => rank.forced);
  const selectedRadioRanks = [...requiredRadioRanks];
  forcedRadios.forEach((rank) => {
    if (!selectedRadioRanks.includes(rank)) selectedRadioRanks.push(rank);
  });
  radioRanks.forEach((rank) => {
    if (selectedRadioRanks.length < softRadiosTotal && !selectedRadioRanks.includes(rank)) {
      selectedRadioRanks.push(rank);
    }
  });
  const entityNodesBeforeRadios = renderedUniverseCount + shownGroupRanks.length;
  const radioSlotsBeforeAggregate = Math.max(0, protectedPaths.reserveActiveAggregate ?
    entityNodeLimit - entityNodesBeforeRadios : limits.hardNodes - nodes.length);
  const hiddenRadiosWithoutAggregate = radioRanks.length >
    Math.min(selectedRadioRanks.length, radioSlotsBeforeAggregate);
  const reserveRadioAggregate = !protectedPaths.reserveActiveAggregate && hiddenRadiosWithoutAggregate &&
    radioSlotsBeforeAggregate > requiredRadioRanks.length;
  const radioSlots = Math.max(0, radioSlotsBeforeAggregate - (reserveRadioAggregate ? 1 : 0));
  const shownRadioRanks = selectedRadioRanks.slice(0, radioSlots);
  const shownRadioKeys = new Set(shownRadioRanks.map((rank) => rank.key));
  shownRadioRanks.forEach(({ entity, groupKey }) => {
    const node = commonNode(state, entity, 'radio', now);
    node.groupKey = visibleKeys.has(groupKey) ? groupKey : '';
    nodes.push(node);
    visibleKeys.add(node.key);
  });

  const hiddenRadioCandidates = new Map();
  if (showScopedRadios) shownGroupRanks.forEach(({ entity: group }) => {
    group.radioKeys.forEach((key) => {
      const radio = state.radios.get(key);
      if (radio && !shownRadioKeys.has(radio.key)) hiddenRadioCandidates.set(radio.key, radio);
    });
  });
  const hiddenRadiosByGroup = new Map();
  hiddenRadioCandidates.forEach((radio) => {
    const activeTargets = [...radio.activeCallKeys].map((key) => state.activeCalls.get(key)?.groupKey)
      .filter((key) => key && shownGroupKeys.has(key)).sort();
    const relatedTargets = [...radio.relatedGroupKeys].filter((key) => shownGroupKeys.has(key)).sort();
    const groupKey = activeTargets[0] ||
      (shownGroupKeys.has(radio.visualParentGroupKey) ? radio.visualParentGroupKey : '') ||
      (shownGroupKeys.has(radio.recentTxGroupKey) ? radio.recentTxGroupKey : '') || relatedTargets[0];
    if (!groupKey) return;
    if (!hiddenRadiosByGroup.has(groupKey)) hiddenRadiosByGroup.set(groupKey, []);
    hiddenRadiosByGroup.get(groupKey).push(radio);
  });
  const aggregateCandidates = [...hiddenRadiosByGroup.entries()].map(([groupKey, radios]) => ({
    groupKey,
    universeKey: state.groups.get(groupKey)?.universeKey || '',
    focusKey: groupKey,
    count: radios.length,
    activeRadioKeys: new Set(radios.filter((radio) => radioActive(state, radio)).map((radio) => radio.key)),
    activeSignal: radios.some((radio) => radioActive(state, radio))
  }));
  hiddenGroupsByUniverse.forEach((groups, universeKey) => {
    if (!groups.length) return;
    const activeRadioKeys = new Set();
    groups.forEach((group) => group.activeCallKeys.forEach((callKey) => {
      const radioKey = state.activeCalls.get(callKey)?.radioKey;
      if (radioKey) activeRadioKeys.add(radioKey);
    }));
    aggregateCandidates.push({ universeKey, groupKey: '', focusKey: groups[0]?.key || universeKey,
      count: groups.length,
      activeRadioKeys, activeSignal: groups.some(groupActive), groups: true });
  });
  aggregateCandidates.sort((left, right) => right.activeRadioKeys.size - left.activeRadioKeys.size ||
    Number(right.activeSignal) - Number(left.activeSignal) || right.count - left.count ||
    left.groupKey.localeCompare(right.groupKey));
  const suppressedActiveRadioKeys = new Set(showScopedRadios ? [...state.radios.values()]
    .filter((radio) => allowedUniverseKeys.has(radio.universeKey) && scopeIncludesRadio(state, scope, radio) &&
      radioActive(state, radio) && !shownRadioKeys.has(radio.key)).map((radio) => radio.key) : []);
  const accountedActiveRadioKeys = new Set();
  const hiddenUniverseKeys = new Set(hiddenUniverses.map((rank) => rank.key));
  const universeAggregate = aggregates.find((aggregate) => aggregate.key === 'aggregate:universes');
  if (universeAggregate) {
    suppressedActiveRadioKeys.forEach((key) => {
      if (hiddenUniverseKeys.has(state.radios.get(key)?.universeKey)) accountedActiveRadioKeys.add(key);
    });
    universeAggregate.suppressedActiveCount = accountedActiveRadioKeys.size;
    universeAggregate.active ||= accountedActiveRadioKeys.size > 0;
    universeAggregate.quiet = !universeAggregate.active;
  }
  aggregateCandidates.forEach((candidate) => {
    if (nodes.length >= limits.hardNodes) return;
    const key = candidate.groups ? `aggregate:${candidate.universeKey}:groups` :
      `aggregate:${candidate.groupKey}:radios`;
    const noun = candidate.groups ? 'talkgroup hubs' : 'retained radios';
    const activeRadioKeys = [...candidate.activeRadioKeys]
      .filter((radioKey) => suppressedActiveRadioKeys.has(radioKey) && !accountedActiveRadioKeys.has(radioKey));
    const aggregate = aggregateNode(key, `+${candidate.count} ${noun}`, candidate.universeKey,
      candidate.groupKey, candidate.count, activeRadioKeys.length, candidate.groups ? 'groups' : 'radios', false,
      candidate.focusKey);
    aggregate.active ||= candidate.activeSignal;
    aggregate.quiet = !aggregate.active;
    activeRadioKeys.forEach((radioKey) => accountedActiveRadioKeys.add(radioKey));
    nodes.push(aggregate);
    aggregates.push(aggregate);
    visibleKeys.add(aggregate.key);
  });

  const visibleOverflowBuckets = [...state.overflowActive.values()]
    .filter((bucket) => scopeIncludesOverflowBucket(scope, bucket, allowedUniverseKeys) ||
      ((scope.level === 'all' || scope.level === 'overview') && !state.universes.has(bucket.universeKey)));
  const visibleOverflowBucketKeys = new Set(visibleOverflowBuckets.map((bucket) => bucket.key));
  const visibleOverflowCalls = [...state.overflowCalls.values()]
    .filter((record) => visibleOverflowBucketKeys.has(record.groupKey));
  const overflowKnownRadioKeys = new Set(visibleOverflowCalls
    .map((record) => record.radioKey).filter(Boolean));
  const overflowSuppressedRadioKeys = new Set([...overflowKnownRadioKeys]
    .filter((radioKey) => !shownRadioKeys.has(radioKey)));
  const suppressedKnownRadioKeys = new Set([...suppressedActiveRadioKeys, ...overflowSuppressedRadioKeys]);
  const overflowApproximate = visibleOverflowBuckets.some((bucket) => bucket.saturated);
  const overflowUnknownCallLegs = visibleOverflowCalls
    .filter((record) => !record.radioKey || shownRadioKeys.has(record.radioKey)).length +
    visibleOverflowBuckets.filter((bucket) => bucket.saturated).length;
  const unaccountedKnownRadioKeys = [...suppressedKnownRadioKeys]
    .filter((radioKey) => !accountedActiveRadioKeys.has(radioKey));
  const unaccountedKnownRadios = unaccountedKnownRadioKeys.length;
  const unaccountedActivity = unaccountedKnownRadios + overflowUnknownCallLegs;
  const activeAggregateLabel = (radios, callLegs, approximate = false) => {
    const radioText = radios ? `+${radios} active radio${radios === 1 ? '' : 's'}` : '';
    const legText = callLegs ? `+${callLegs}${approximate ? ' or more' : ''} active call ` +
      `leg${callLegs === 1 && !approximate ? '' : 's'}` : '';
    return [radioText, legText].filter(Boolean).join(' · ');
  };
  const focusKey = unaccountedKnownRadioKeys.find((key) => state.radios.has(key)) ||
    visibleOverflowBuckets.map((bucket) => bucket.groupKey || bucket.universeKey)
      .find((key) => state.groups.has(key) || state.universes.has(key)) || '';
  if (unaccountedActivity > 0) {
    if (nodes.length >= limits.hardNodes) {
      const removableIndex = nodes.findLastIndex((node) => node.type === 'radio' && !node.active && !node.selected &&
        !state.visual.draggedKeys.has(node.key));
      if (removableIndex >= 0) {
        visibleKeys.delete(nodes[removableIndex].key);
        shownRadioKeys.delete(nodes[removableIndex].key);
        nodes.splice(removableIndex, 1);
      }
    }
    if (nodes.length < limits.hardNodes) {
      const kind = overflowUnknownCallLegs ? (unaccountedKnownRadios ? 'active_activity' : 'active_call_legs') :
        'active_radios';
      const aggregate = aggregateNode('aggregate:active-overflow',
        activeAggregateLabel(unaccountedKnownRadios, overflowUnknownCallLegs, overflowApproximate), '', '',
        unaccountedActivity, unaccountedKnownRadios, kind, overflowApproximate, focusKey);
      aggregate.suppressedActiveCallLegs = overflowUnknownCallLegs;
      nodes.push(aggregate);
      aggregates.push(aggregate);
      visibleKeys.add(aggregate.key);
    } else {
      const existing = aggregates.find((aggregate) => aggregate.suppressedActiveCount > 0) || aggregates[0];
      if (existing) {
        existing.retainedCount += unaccountedActivity;
        existing.suppressedActiveCount += unaccountedKnownRadios;
        existing.suppressedActiveCallLegs += overflowUnknownCallLegs;
        existing.active = true;
        existing.quiet = false;
        existing.kind = existing.suppressedActiveCallLegs ?
          (existing.suppressedActiveCount ? 'active_activity' : 'active_call_legs') : 'active_radios';
        existing.approximate = existing.approximate || overflowApproximate;
        existing.focusKey ||= focusKey;
        existing.label = activeAggregateLabel(existing.suppressedActiveCount,
          existing.suppressedActiveCallLegs, existing.approximate);
      }
    }
  }
  const hiddenActiveRadios = suppressedKnownRadioKeys.size;
  const overflowActive = overflowUnknownCallLegs;

  const linkCandidates = [];
  if (showAffiliations !== false) {
    shownRadioRanks.forEach(({ entity: radio }) => {
      if (!shownRadioKeys.has(radio.key)) return;
      const groupKeys = new Set([...radio.affiliations.values()].map((evidence) => evidence.groupKey));
      groupKeys.forEach((groupKey) => {
        if (!visibleKeys.has(groupKey)) return;
        linkCandidates.push({
          id: `affiliation:${radio.key}:${groupKey}`,
          key: `affiliation:${radio.key}:${groupKey}`,
          source: radio.key,
          target: groupKey,
          type: 'affiliation',
          active: false,
          faded: now - (radio.affiliations.size ? Math.max(...[...radio.affiliations.values()]
            .filter((evidence) => evidence.groupKey === groupKey).map((evidence) => evidence.observedAtMs)) : 0) >=
            limits.radioQuietAfterMs,
          dashed: false,
          affiliation: true,
          encrypted: false,
          priority: 3_000_000,
          entity: radio
        });
      });
    });
  }
  if (showActivity !== false) {
    shownRadioRanks.forEach(({ entity: radio }) => {
      if (!shownRadioKeys.has(radio.key)) return;
      const activeCalls = [...radio.activeCallKeys].map((key) => state.activeCalls.get(key)).filter(Boolean);
      const activeTargets = new Set(activeCalls.map((call) => call.groupKey));
      if (!activeTargets.size && radio.recentTxGroupKey) activeTargets.add(radio.recentTxGroupKey);
      activeTargets.forEach((groupKey) => {
        if (!visibleKeys.has(groupKey)) return;
        const calls = activeCalls.filter((candidate) => candidate.groupKey === groupKey);
        const call = calls[0];
        const affiliationLink = linkCandidates.find((link) => link.type === 'affiliation' &&
          link.source === radio.key && link.target === groupKey);
        linkCandidates.push({
          id: `activity:${radio.key}:${groupKey}`,
          key: `activity:${radio.key}:${groupKey}`,
          source: radio.key,
          target: groupKey,
          type: call ? 'tx' : 'activity',
          active: Boolean(call),
          faded: !call,
          dashed: !call || !affiliationLink,
          affiliation: false,
          encrypted: calls.some((candidate) => candidate.encrypted === true),
          priority: call ? 5_000_000 : 1_000_000,
          entity: call || radio
        });
      });
    });
  }
  const effects = state.pendingEffects.filter((effect) => effect.expiresAtMs > now);
  effects.filter((effect) => effect.type === 'migration' && visibleKeys.has(effect.nodeKey) &&
    visibleKeys.has(effect.sourceKey) && visibleKeys.has(effect.targetKey))
    .slice(-limits.hardMigrationTrails).forEach((effect) => {
      const oldAffiliation = linkCandidates.find((link) => link.type === 'affiliation' &&
        link.source === effect.nodeKey && link.target === effect.sourceKey);
      if (oldAffiliation) {
        oldAffiliation.faded = true;
      } else {
        linkCandidates.push({
          id: `migration-old-affiliation:${effect.id}`,
          key: `migration-old-affiliation:${effect.id}`,
          source: effect.nodeKey,
          target: effect.sourceKey,
          type: 'affiliation',
          active: false,
          faded: true,
          dashed: false,
          affiliation: true,
          encrypted: false,
          priority: 2_800_000,
          entity: effect
        });
      }
      linkCandidates.push({
        id: `migration:${effect.id}`,
        key: `migration:${effect.id}`,
        source: effect.nodeKey,
        target: effect.targetKey,
        fromGroupKey: effect.sourceKey,
        type: 'migration',
        active: false,
        faded: false,
        dashed: false,
        affiliation: true,
        encrypted: false,
        priority: 3_200_000,
        entity: effect
      });
    });
  const links = linkCandidates.sort((left, right) => right.priority - left.priority ||
    left.key.localeCompare(right.key)).slice(0, limits.hardLinks).map(({ priority: _priority, ...link }) => link);

  const labelRanks = nodes.map((node) => ({
    key: node.key,
    score: (node.selected ? 5_000_000 : 0) + (node.active ? 4_000_000 : 0) +
      (node.type === 'universe' ? 3_000_000 : 0) + (node.type === 'group' ? 2_000_000 : 0) +
      (node.pinned ? 1_000_000 : 0) + (node.type === 'aggregate' ? 500_000 : 0)
  })).sort((left, right) => right.score - left.score || left.key.localeCompare(right.key));
  const labels = labelRanks.slice(0, hardLabels).map(({ key }) => key);

  const visibleEffects = [];
  let particles = 0;
  let trails = 0;
  effects.slice().sort((left, right) => right.createdAtMs - left.createdAtMs || left.id.localeCompare(right.id))
    .forEach((effect) => {
      if (effect.nodeKey && !visibleKeys.has(effect.nodeKey)) return;
      if (effect.sourceKey && !visibleKeys.has(effect.sourceKey)) return;
      if (effect.targetKey && !visibleKeys.has(effect.targetKey)) return;
      if (effect.type === 'migration') {
        if (trails >= limits.hardMigrationTrails) return;
        trails += 1;
      } else if (effect.type === 'tx_pulse') {
        if (particles >= limits.hardParticles) return;
        particles += 1;
      }
      visibleEffects.push(effect);
    });

  rememberVisible(state, nodes.map((node) => node.key), now);
  const visibleRadios = nodes.filter((node) => node.type === 'radio').length;
  const visibleGroups = nodes.filter((node) => node.type === 'group').length;
  const visibleUniverses = nodes.filter((node) => node.type === 'universe').length;
  const searchResults = query ? [
    ...state.universes.values(), ...state.groups.values(), ...state.radios.values()
  ].filter((entity) => queryMatch(entity, query)).sort((left, right) =>
    Number(state.visual.selectedKey === right.key) - Number(state.visual.selectedKey === left.key) ||
    right.lastMeaningfulAtMs - left.lastMeaningfulAtMs || left.key.localeCompare(right.key))
    .slice(0, Math.min(100, limits.hardLabels)).map((entity) => Object.freeze({
      key: entity.key,
      type: entity.type,
      label: entity.label,
      universeKey: entity.type === 'universe' ? entity.key : entity.universeKey,
      groupKey: entity.type === 'group' ? entity.key : (entity.visualParentGroupKey || '')
    })) : [];
  return Object.freeze({
    scope,
    nodes: Object.freeze(nodes),
    links: Object.freeze(links),
    labels: Object.freeze(labels),
    effects: Object.freeze(visibleEffects),
    aggregates: Object.freeze(aggregates),
    searchResults: Object.freeze(searchResults),
    counts: Object.freeze({
      visibleNodes: nodes.length,
      visibleLinks: links.length,
      visibleLabels: labels.length,
      visibleRadios,
      retainedRadios: state.radios.size,
      visibleGroups,
      retainedGroups: state.groups.size,
      visibleUniverses,
      retainedUniverses: state.universes.size,
      showingText: `Showing ${visibleRadios} of ${state.radios.size} retained radios`,
      suppressedActive: hiddenActiveRadios + overflowActive,
      suppressedActiveRadios: hiddenActiveRadios,
      suppressedActiveCallLegs: overflowActive,
      suppressedActiveApproximate: overflowApproximate
    })
  });
}

export { selectVisibleGraph };
