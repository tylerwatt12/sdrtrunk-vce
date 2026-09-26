'use strict';

import { BALANCED_CONFIG, validateConfig } from './config.js';
import {
  groupKeyFor,
  normalizeObservation,
  normalizeSnapshotRow,
  radioKeyFor,
  semanticFingerprint,
  snapshotRowActive,
  snapshotRowPending,
  universeKeyFor
} from './normalize.js';

function boundedMapSet(map, key, value, maximum) {
  if (map.has(key)) map.delete(key);
  map.set(key, value);
  while (map.size > maximum) map.delete(map.keys().next().value);
}

function eventTime(value, fallback = Date.now()) {
  const numeric = Number(value);
  return Number.isSafeInteger(numeric) && numeric > 0 ? numeric : fallback;
}

function createNetworkState(config = BALANCED_CONFIG, atMs = Date.now()) {
  const validated = config === BALANCED_CONFIG ? config : validateConfig(config);
  const startedAtMs = eventTime(atMs);
  return {
    config: validated,
    generation: 1,
    sessionStartedAtMs: startedAtMs,
    universes: new Map(),
    groups: new Map(),
    radios: new Map(),
    activeCalls: new Map(),
    pendingGrants: new Map(),
    overflowActive: new Map(),
    overflowCalls: new Map(),
    semanticEvents: [],
    nextHistoryId: 1,
    dedupe: new Map(),
    incomingQueue: [],
    pendingEffects: [],
    nextEffectId: 1,
    snapshotRows: new Map(),
    snapshotRevisions: new Map(),
    maintenance: {
      nextDedupePruneAtMs: startedAtMs,
      nextRetentionSweepAtMs: startedAtMs
    },
    visual: {
      selectedKey: null,
      pinnedKeys: new Set(),
      membership: new Map()
    },
    metadata: {
      pending: new Map(),
      cache: new Map()
    },
    transport: {
      status: 'connecting',
      gap: null,
      lastObservationAtMs: 0
    },
    overflow: {
      universes: 0,
      groups: 0,
      radios: 0,
      activeCalls: 0,
      queueDropped: 0,
      effectsDropped: 0,
      metadataRequestsRejected: 0
    }
  };
}

function clearNetworkState(state, atMs = Date.now()) {
  state.generation += 1;
  state.sessionStartedAtMs = eventTime(atMs);
  state.universes.clear();
  state.groups.clear();
  state.radios.clear();
  state.activeCalls.clear();
  state.pendingGrants.clear();
  state.overflowActive.clear();
  state.overflowCalls.clear();
  state.semanticEvents.length = 0;
  state.nextHistoryId = 1;
  state.dedupe.clear();
  state.incomingQueue.length = 0;
  state.pendingEffects.length = 0;
  state.nextEffectId = 1;
  state.snapshotRows.clear();
  state.snapshotRevisions.clear();
  state.maintenance.nextDedupePruneAtMs = state.sessionStartedAtMs;
  state.maintenance.nextRetentionSweepAtMs = state.sessionStartedAtMs;
  state.visual.selectedKey = null;
  state.visual.pinnedKeys.clear();
  state.visual.membership.clear();
  state.metadata.pending.clear();
  state.metadata.cache.clear();
  state.transport = { status: 'connecting', gap: null, lastObservationAtMs: 0 };
  Object.keys(state.overflow).forEach((key) => { state.overflow[key] = 0; });
  return state.generation;
}

function appendHistory(state, value) {
  const entry = Object.freeze({ id: `semantic-${state.nextHistoryId++}`, ...value });
  state.semanticEvents.push(entry);
  while (state.semanticEvents.length > state.config.state.hardSemanticEvents) state.semanticEvents.shift();
  return entry;
}

function addEffect(state, effect) {
  const maximum = state.config.state.hardPendingEffects;
  if (maximum === 0) {
    state.overflow.effectsDropped += 1;
    return null;
  }
  const createdAtMs = eventTime(effect.createdAtMs);
  const expiresAtMs = Math.max(createdAtMs, eventTime(effect.expiresAtMs, createdAtMs));
  const requestedAnimationEnd = Number(effect.animationEndsAtMs);
  const animationEndsAtMs = Math.min(expiresAtMs, Number.isSafeInteger(requestedAnimationEnd) ?
    Math.max(createdAtMs, requestedAnimationEnd) : expiresAtMs);
  const value = Object.freeze({
    id: `effect-${state.nextEffectId++}`,
    ...effect,
    createdAtMs,
    animationEndsAtMs,
    expiresAtMs,
    coalesceKey: String(effect.coalesceKey || '').slice(0, 512)
  });
  state.pendingEffects.push(value);
  while (state.pendingEffects.length > maximum) {
    state.pendingEffects.shift();
    state.overflow.effectsDropped += 1;
  }
  return value;
}

function pruneDedupe(state, atMs) {
  if (atMs < state.maintenance.nextDedupePruneAtMs) return;
  const cutoff = atMs - state.config.state.dedupeTtlMs;
  for (const [key, observedAtMs] of state.dedupe) {
    if (observedAtMs < cutoff) state.dedupe.delete(key);
  }
  state.maintenance.nextDedupePruneAtMs = atMs + Math.min(60_000,
    Math.max(1_000, Math.floor(state.config.state.dedupeTtlMs / 8)));
}

function acceptFingerprint(state, fingerprint, atMs) {
  if (!fingerprint) return true;
  pruneDedupe(state, atMs);
  const previous = state.dedupe.get(fingerprint);
  if (previous !== undefined && previous >= atMs - state.config.state.dedupeTtlMs) return false;
  if (previous !== undefined) state.dedupe.delete(fingerprint);
  boundedMapSet(state.dedupe, fingerprint, atMs, state.config.state.hardDedupeEntries);
  return true;
}

function entityProtected(state, entity) {
  return entity.pinned || state.visual.selectedKey === entity.key;
}

function deleteRadio(state, radio) {
  if (!radio || radio.activeCallKeys.size || entityProtected(state, radio)) return false;
  radio.relatedGroupKeys.forEach((key) => state.groups.get(key)?.radioKeys.delete(radio.key));
  radio.affiliatedGroupKeys.forEach((key) => state.groups.get(key)?.affiliatedRadioKeys.delete(radio.key));
  state.universes.get(radio.universeKey)?.radioKeys.delete(radio.key);
  state.radios.delete(radio.key);
  state.visual.membership.delete(radio.key);
  return true;
}

function clearPendingGrant(state, key) {
  const pendingKey = String(key || '');
  if (!pendingKey) return false;
  const record = state.pendingGrants.get(pendingKey);
  if (!record) return false;
  state.pendingGrants.delete(pendingKey);
  state.groups.get(record.groupKey)?.pendingGrantKeys.delete(pendingKey);
  state.universes.get(record.universeKey)?.pendingGrantKeys.delete(pendingKey);
  return true;
}

function clearPendingGrantsForEntity(state, key) {
  const entityKey = String(key || '');
  [...state.pendingGrants.entries()].forEach(([pendingKey, record]) => {
    if (record.groupKey === entityKey || record.universeKey === entityKey) clearPendingGrant(state, pendingKey);
  });
}

function deleteGroup(state, group) {
  if (!group || group.activeCallKeys.size || entityProtected(state, group)) return false;
  const radios = [...group.radioKeys].map((key) => state.radios.get(key)).filter(Boolean);
  if (radios.some((radio) => radio.universeKey === group.universeKey &&
      (radio.activeCallKeys.size || entityProtected(state, radio)))) return false;
  radios.forEach((radio) => {
    for (const [scopeKey, evidence] of radio.affiliations) {
      if (evidence.groupKey === group.key) radio.affiliations.delete(scopeKey);
    }
    const remaining = rebuildAffiliationIndexes(state, radio);
    if (radio.recentTxGroupKey === group.key) radio.recentTxGroupKey = null;
    if (radio.visualParentGroupKey === group.key) {
      radio.visualParentGroupKey = remaining.size === 1 ? [...remaining][0] : null;
    }
    refreshRadioGroupMembership(state, radio);
    if (radio.universeKey === group.universeKey && !radio.affiliations.size &&
        !radio.visualParentGroupKey && !radio.activeCallKeys.size) deleteRadio(state, radio);
  });
  clearPendingGrantsForEntity(state, group.key);
  state.universes.get(group.universeKey)?.groupKeys.delete(group.key);
  state.groups.delete(group.key);
  state.visual.membership.delete(group.key);
  return true;
}

function deleteUniverse(state, universe) {
  if (!universe || entityProtected(state, universe)) return false;
  const groups = [...universe.groupKeys].map((key) => state.groups.get(key)).filter(Boolean);
  if (groups.some((group) => group.activeCallKeys.size || entityProtected(state, group))) return false;
  const ownedRadios = [...universe.radioKeys].map((key) => state.radios.get(key)).filter(Boolean);
  if (ownedRadios.some((radio) => radio.activeCallKeys.size || entityProtected(state, radio))) return false;
  if (groups.some((group) => !deleteGroup(state, group))) return false;
  ownedRadios.forEach((radio) => deleteRadio(state, radio));
  clearPendingGrantsForEntity(state, universe.key);
  state.universes.delete(universe.key);
  state.visual.membership.delete(universe.key);
  return true;
}

function evictOldest(map, eligible, remove) {
  const candidates = [...map.values()].filter(eligible).sort((left, right) =>
    left.lastMeaningfulAtMs - right.lastMeaningfulAtMs || left.createdAtMs - right.createdAtMs ||
    left.key.localeCompare(right.key));
  for (const candidate of candidates) {
    if (remove(candidate)) return true;
  }
  return false;
}

function ensureUniverse(state, event) {
  let universe = state.universes.get(event.universeKey);
  if (universe) {
    universe.lastObservedAtMs = Math.max(universe.lastObservedAtMs, event.observedAtMs);
    if (event.protocol) universe.protocol = event.protocol;
    if (event.wacn) universe.wacn = event.wacn;
    if (event.systemId) universe.systemId = event.systemId;
    if (event.systemName) universe.systemName = event.systemName;
    const baseLabel = universe.systemName || event.channelName ||
      (event.radioSystemKey ? 'Observed radio system' : 'Observed channel');
    universe.label = universe.protocol === 'p25' && universe.wacn && universe.systemId ?
      `${baseLabel} · ${universe.wacn}:${universe.systemId}` : baseLabel;
    if (event.configurationId && !universe.configurationIds.has(event.configurationId)) {
      while (universe.configurationIds.size >= state.config.state.hardSiteEvidencePerEntity) {
        universe.configurationIds.delete(universe.configurationIds.values().next().value);
      }
      universe.configurationIds.add(event.configurationId);
    }
    return universe;
  }
  if (state.universes.size >= state.config.state.hardUniverses && !evictOldest(state.universes,
    (candidate) => !entityProtected(state, candidate), (candidate) => deleteUniverse(state, candidate))) {
    state.overflow.universes += 1;
    return null;
  }
  universe = {
    key: event.universeKey,
    type: 'universe',
    kind: event.radioSystemKey ? 'radio_system' : 'channel',
    radioSystemKey: event.radioSystemKey,
    configurationIds: new Set(event.configurationId ? [event.configurationId] : []),
    protocol: event.protocol,
    systemName: event.systemName || '',
    label: event.protocol === 'p25' && event.wacn && event.systemId ?
      `${event.systemName || 'Observed radio system'} · ${event.wacn}:${event.systemId}` :
      (event.systemName || event.channelName || (event.radioSystemKey ? 'Observed radio system' : 'Observed channel')),
    wacn: event.wacn || '',
    systemId: event.systemId || '',
    groupKeys: new Set(),
    radioKeys: new Set(),
    siteEvidence: [],
    createdAtMs: event.observedAtMs,
    lastObservedAtMs: event.observedAtMs,
    lastMeaningfulAtMs: event.observedAtMs,
    pendingGrantKeys: new Set(),
    afterglowUntilMs: 0,
    pinned: false
  };
  state.universes.set(universe.key, universe);
  return universe;
}

function ensureGroup(state, universe, event, groupEntity, groupKey = '') {
  if (!universe || !groupEntity) return null;
  const kind = groupEntity.kind || groupEntity.entityRef?.kind || 'talkgroup';
  const key = groupKey || groupKeyFor(universe.key, groupEntity.identityKey, kind, event.configurationId);
  if (!key) return null;
  let group = state.groups.get(key);
  if (group) {
    group.lastObservedAtMs = Math.max(group.lastObservedAtMs, event.observedAtMs);
    if (groupEntity.label) group.label = groupEntity.label;
    if (groupEntity.observedLocalId) group.observedLocalId = groupEntity.observedLocalId;
    if (groupEntity.displayId) group.displayId = groupEntity.displayId;
    if (groupEntity.entityRef) group.entityRef = groupEntity.entityRef;
    return group;
  }
  if (state.groups.size >= state.config.state.hardGroups && !evictOldest(state.groups,
    (candidate) => !candidate.activeCallKeys.size && !entityProtected(state, candidate),
    (candidate) => deleteGroup(state, candidate))) {
    state.overflow.groups += 1;
    return null;
  }
  group = {
    key,
    type: 'group',
    kind,
    universeKey: universe.key,
    identityKey: groupEntity.identityKey,
    observedLocalId: groupEntity.observedLocalId || '',
    entityRef: groupEntity.entityRef || null,
    label: groupEntity.label || (kind === 'channel' ? event.channelName : groupEntity.displayId) ||
      (kind === 'patch_group' ? 'Observed patch group' : 'Observed talkgroup'),
    displayId: groupEntity.displayId || '',
    protocol: event.protocol,
    radioKeys: new Set(),
    affiliatedRadioKeys: new Set(),
    activeCallKeys: new Set(),
    siteEvidence: [],
    createdAtMs: event.observedAtMs,
    lastObservedAtMs: event.observedAtMs,
    lastMeaningfulAtMs: event.observedAtMs,
    afterglowUntilMs: 0,
    pendingGrantKeys: new Set(),
    pinned: false
  };
  state.groups.set(key, group);
  universe.groupKeys.add(key);
  universe.lastMeaningfulAtMs = Math.max(universe.lastMeaningfulAtMs, event.observedAtMs);
  return group;
}

function ensureRadio(state, universe, event, radioEntity, radioKey = '') {
  if (!universe || !radioEntity) return null;
  const key = radioKey || radioKeyFor(universe.key, radioEntity.identityKey);
  if (!key) return null;
  let radio = state.radios.get(key);
  if (radio) {
    universe.radioKeys.add(radio.key);
    radio.lastObservedAtMs = Math.max(radio.lastObservedAtMs, event.observedAtMs);
    if (radioEntity.label) radio.label = radioEntity.label;
    if (radioEntity.observedLocalId) radio.observedLocalId = radioEntity.observedLocalId;
    if (radioEntity.displayId) radio.displayId = radioEntity.displayId;
    if (radioEntity.entityRef) radio.entityRef = radioEntity.entityRef;
    return radio;
  }
  if (state.radios.size >= state.config.state.hardRadios && !evictOldest(state.radios,
    (candidate) => !candidate.activeCallKeys.size && !entityProtected(state, candidate),
    (candidate) => deleteRadio(state, candidate))) {
    state.overflow.radios += 1;
    return null;
  }
  radio = {
    key,
    type: 'radio',
    universeKey: universe.key,
    identityKey: radioEntity.identityKey,
    observedLocalId: radioEntity.observedLocalId || '',
    entityRef: radioEntity.entityRef || null,
    label: radioEntity.label || radioEntity.displayId || 'Observed radio',
    displayId: radioEntity.displayId || '',
    protocol: event.protocol,
    affiliations: new Map(),
    affiliationAmbiguous: false,
    affiliationContinuity: 'current',
    comparisonEpoch: 0,
    visualParentGroupKey: null,
    recentTxGroupKey: null,
    activeCallKeys: new Set(),
    affiliatedGroupKeys: new Set(),
    relatedGroupKeys: new Set(),
    transitionEventIds: [],
    siteEvidence: [],
    createdAtMs: event.observedAtMs,
    lastObservedAtMs: event.observedAtMs,
    lastMeaningfulAtMs: event.observedAtMs,
    lastMigrationAtMs: 0,
    afterglowUntilMs: 0,
    grantOnly: false,
    pinned: false
  };
  state.radios.set(key, radio);
  universe.radioKeys.add(key);
  return radio;
}

function addSiteEvidence(entity, event, maximum) {
  if (!entity || !event.site) return;
  const key = [event.configurationId, event.site.rfssId ?? 'x', event.site.siteId ?? 'x',
    event.site.timeslot ?? 'x'].join(':');
  const evidence = Object.freeze({
    key,
    configurationId: event.configurationId,
    rfssId: event.site.rfssId,
    siteId: event.site.siteId,
    nac: event.site.nac,
    timeslot: event.site.timeslot,
    channelName: event.site.channelName,
    siteName: event.site.siteName,
    observedAtMs: event.observedAtMs
  });
  const index = entity.siteEvidence.findIndex((candidate) => candidate.key === key);
  if (index >= 0) entity.siteEvidence.splice(index, 1);
  entity.siteEvidence.push(evidence);
  entity.siteEvidence.sort((left, right) => right.observedAtMs - left.observedAtMs || left.key.localeCompare(right.key));
  if (entity.siteEvidence.length > maximum) entity.siteEvidence.length = maximum;
}

function evidenceNewer(previous, event, comparisonEpoch = previous?.comparisonEpoch ?? 0) {
  if (!previous) return true;
  if ((previous.comparisonEpoch ?? 0) !== comparisonEpoch) return true;
  if (previous.sequence !== null && event.sequence !== null) return event.sequence > previous.sequence;
  return event.observedAtMs > previous.observedAtMs;
}

function refreshRadioGroupMembership(state, radio) {
  if (!radio) return new Set();
  const groupKeys = new Set([...radio.affiliations.values()].map((evidence) => evidence.groupKey));
  if (radio.visualParentGroupKey) groupKeys.add(radio.visualParentGroupKey);
  if (radio.recentTxGroupKey) groupKeys.add(radio.recentTxGroupKey);
  radio.activeCallKeys.forEach((callKey) => {
    const groupKey = state.activeCalls.get(callKey)?.groupKey;
    if (groupKey) groupKeys.add(groupKey);
  });
  for (const key of [...groupKeys]) {
    if (!state.groups.has(key)) groupKeys.delete(key);
  }
  radio.relatedGroupKeys.forEach((key) => {
    if (!groupKeys.has(key)) state.groups.get(key)?.radioKeys.delete(radio.key);
  });
  groupKeys.forEach((key) => state.groups.get(key)?.radioKeys.add(radio.key));
  radio.relatedGroupKeys = groupKeys;
  return groupKeys;
}

function rebuildAffiliationIndexes(state, radio) {
  radio.affiliatedGroupKeys.forEach((key) => state.groups.get(key)?.affiliatedRadioKeys.delete(radio.key));
  const groupKeys = new Set();
  radio.affiliations.forEach((evidence) => {
    groupKeys.add(evidence.groupKey);
    state.groups.get(evidence.groupKey)?.affiliatedRadioKeys.add(radio.key);
  });
  radio.affiliatedGroupKeys = groupKeys;
  radio.affiliationAmbiguous = groupKeys.size > 1;
  radio.affiliationContinuity = [...radio.affiliations.values()]
    .every((evidence) => (evidence.comparisonEpoch ?? 0) === radio.comparisonEpoch) ? 'current' : 'uncertain';
  return groupKeys;
}

function applyAffiliation(state, event) {
  const existingRadio = state.radios.get(radioKeyFor(event.universeKey, event.radio.identityKey));
  const existingEvidence = existingRadio?.affiliations.get(event.scopeKey);
  if (existingEvidence && !evidenceNewer(existingEvidence, event, existingRadio.comparisonEpoch)) {
    return { applied: false, reason: 'out_of_order' };
  }
  const universe = ensureUniverse(state, event);
  const group = ensureGroup(state, universe, event, event.group);
  const radio = ensureRadio(state, universe, event, event.radio);
  if (!universe || !group || !radio) return { applied: false, reason: 'capacity' };
  radio.grantOnly = false;
  const previous = radio.affiliations.get(event.scopeKey);
  if (!evidenceNewer(previous, event, radio.comparisonEpoch)) return { applied: false, reason: 'out_of_order' };
  const firstAffiliation = radio.affiliations.size === 0;
  const comparable = Boolean(previous && (previous.comparisonEpoch ?? 0) === radio.comparisonEpoch);
  const continuityUnknown = Boolean(previous && !comparable);
  const changed = Boolean(comparable && previous.groupKey !== group.key);
  const evidence = Object.freeze({
    scopeKey: event.scopeKey,
    groupKey: group.key,
    observedAtMs: event.observedAtMs,
    sequence: event.sequence,
    comparisonEpoch: radio.comparisonEpoch,
    evidenceType: event.evidenceType,
    configurationId: event.configurationId,
    site: event.site
  });
  radio.affiliations.set(event.scopeKey, evidence);
  while (radio.affiliations.size > state.config.state.hardSiteEvidencePerEntity) {
    const oldestScope = [...radio.affiliations.entries()].sort((left, right) =>
      left[1].observedAtMs - right[1].observedAtMs || left[0].localeCompare(right[0]))[0]?.[0];
    if (!oldestScope) break;
    radio.affiliations.delete(oldestScope);
  }
  const affiliatedGroups = rebuildAffiliationIndexes(state, radio);
  radio.lastMeaningfulAtMs = event.observedAtMs;
  group.lastMeaningfulAtMs = event.observedAtMs;
  universe.lastMeaningfulAtMs = event.observedAtMs;
  addSiteEvidence(radio, event, state.config.state.hardSiteEvidencePerEntity);
  addSiteEvidence(group, event, state.config.state.hardSiteEvidencePerEntity);
  addSiteEvidence(universe, event, state.config.state.hardSiteEvidencePerEntity);

  let transitionEvent = null;
  if (changed) {
    radio.visualParentGroupKey = group.key;
    radio.lastMigrationAtMs = event.observedAtMs;
    transitionEvent = appendHistory(state, {
      type: 'observed_affiliation_change',
      label: 'Observed affiliation change',
      observedAtMs: event.observedAtMs,
      universeKey: universe.key,
      radioKey: radio.key,
      oldGroupKey: previous.groupKey,
      newGroupKey: group.key,
      scopeKey: event.scopeKey,
      configurationId: event.configurationId,
      site: event.site,
      evidenceType: event.evidenceType,
      ambiguous: affiliatedGroups.size > 1
    });
    radio.transitionEventIds.push(transitionEvent.id);
    if (radio.transitionEventIds.length > state.config.state.hardTransitionsPerRadio) {
      radio.transitionEventIds.splice(0,
        radio.transitionEventIds.length - state.config.state.hardTransitionsPerRadio);
    }
    addEffect(state, {
      type: 'migration',
      sourceKey: previous.groupKey,
      targetKey: group.key,
      nodeKey: radio.key,
      createdAtMs: event.observedAtMs,
      animationEndsAtMs: event.observedAtMs + state.config.animation.migrationMotionMs,
      coalesceKey: `radio-motion:${radio.key}`,
      expiresAtMs: event.observedAtMs + state.config.render.migrationTrailTtlMs
    });
    addEffect(state, {
      type: 'destination_highlight',
      nodeKey: group.key,
      createdAtMs: event.observedAtMs,
      animationEndsAtMs: event.observedAtMs + state.config.animation.pulseDurationMs,
      expiresAtMs: event.observedAtMs + state.config.animation.pulseDurationMs,
      coalesceKey: `target-highlight:${group.key}`
    });
  } else if (continuityUnknown) {
    radio.visualParentGroupKey = group.key;
    appendHistory(state, {
      type: 'affiliation_observed_after_gap',
      label: 'Affiliation observed after live gap',
      observedAtMs: event.observedAtMs,
      universeKey: universe.key,
      radioKey: radio.key,
      newGroupKey: group.key,
      scopeKey: event.scopeKey,
      configurationId: event.configurationId,
      site: event.site,
      evidenceType: event.evidenceType,
      certainty: 'unknown_continuity',
      semanticMigration: false
    });
  } else if (firstAffiliation) {
    radio.visualParentGroupKey = group.key;
    appendHistory(state, {
      type: 'affiliation_observed',
      label: 'Successful affiliation observed',
      observedAtMs: event.observedAtMs,
      universeKey: universe.key,
      radioKey: radio.key,
      newGroupKey: group.key,
      scopeKey: event.scopeKey,
      configurationId: event.configurationId,
      site: event.site,
      evidenceType: event.evidenceType
    });
    addEffect(state, {
      type: 'affiliation_arrival',
      nodeKey: radio.key,
      targetKey: group.key,
      createdAtMs: event.observedAtMs,
      animationEndsAtMs: event.observedAtMs + state.config.animation.pulseDurationMs,
      expiresAtMs: event.observedAtMs + state.config.animation.pulseDurationMs,
      coalesceKey: `radio-arrival:${radio.key}`
    });
  } else if (!radio.visualParentGroupKey && affiliatedGroups.size === 1) {
    radio.visualParentGroupKey = [...affiliatedGroups][0];
  }
  refreshRadioGroupMembership(state, radio);
  return { applied: true, changed, first: firstAffiliation, ambiguous: radio.affiliationAmbiguous, transitionEvent };
}

function applyPresenceRemoval(state, event) {
  const radioKey = radioKeyFor(event.universeKey, event.radio.identityKey);
  const radio = state.radios.get(radioKey);
  const previous = radio?.affiliations.get(event.scopeKey);
  if (!radio || !previous || !evidenceNewer(previous, event, radio.comparisonEpoch)) {
    return { applied: false, reason: 'not_current' };
  }
  radio.affiliations.delete(event.scopeKey);
  const remaining = rebuildAffiliationIndexes(state, radio);
  if (radio.visualParentGroupKey === previous.groupKey) {
    radio.visualParentGroupKey = remaining.size === 1 ? [...remaining][0] : null;
  }
  refreshRadioGroupMembership(state, radio);
  radio.lastMeaningfulAtMs = event.observedAtMs;
  appendHistory(state, {
    type: 'explicit_presence_remove',
    label: 'Explicit presence removal observed',
    observedAtMs: event.observedAtMs,
    universeKey: event.universeKey,
    radioKey: radio.key,
    oldGroupKey: previous.groupKey,
    scopeKey: event.scopeKey,
    configurationId: event.configurationId,
    site: event.site,
    reason: event.reason
  });
  return { applied: true };
}

function mergeReconciledGroup(state, sourceKey, target, observedAtMs) {
  const source = state.groups.get(sourceKey);
  if (!source || !target || source.key === target.key) return false;
  target.reconciledFromKey = source.key;
  target.createdAtMs = Math.min(target.createdAtMs, source.createdAtMs);
  target.lastObservedAtMs = Math.max(target.lastObservedAtMs, source.lastObservedAtMs, observedAtMs);
  target.lastMeaningfulAtMs = Math.max(target.lastMeaningfulAtMs, source.lastMeaningfulAtMs);
  target.afterglowUntilMs = Math.max(target.afterglowUntilMs, source.afterglowUntilMs);
  target.pendingGrantUntilMs = Math.max(target.pendingGrantUntilMs, source.pendingGrantUntilMs);
  source.siteEvidence.forEach((evidence) => {
    const existing = target.siteEvidence.findIndex((candidate) => candidate.key === evidence.key);
    if (existing >= 0) target.siteEvidence.splice(existing, 1);
    target.siteEvidence.push(evidence);
  });
  target.siteEvidence.sort((left, right) => right.observedAtMs - left.observedAtMs || left.key.localeCompare(right.key));
  target.siteEvidence.length = Math.min(target.siteEvidence.length, state.config.state.hardSiteEvidencePerEntity);
  source.activeCallKeys.forEach((callKey) => {
    const call = state.activeCalls.get(callKey);
    if (call) call.groupKey = target.key;
    target.activeCallKeys.add(callKey);
  });
  state.radios.forEach((radio) => {
    let remapped = false;
    const wasRelated = radio.relatedGroupKeys.has(source.key);
    const parentRemapped = radio.visualParentGroupKey === source.key;
    const recentRemapped = radio.recentTxGroupKey === source.key;
    radio.affiliations.forEach((evidence, scopeKey) => {
      if (evidence.groupKey !== source.key) return;
      radio.affiliations.set(scopeKey, Object.freeze({ ...evidence, groupKey: target.key,
        reconciledAtMs: observedAtMs }));
      remapped = true;
    });
    if (parentRemapped) radio.visualParentGroupKey = target.key;
    if (recentRemapped) radio.recentTxGroupKey = target.key;
    if (remapped || wasRelated || parentRemapped || recentRemapped) {
      rebuildAffiliationIndexes(state, radio);
      refreshRadioGroupMembership(state, radio);
    }
  });
  if (source.pinned) {
    target.pinned = true;
    state.visual.pinnedKeys.delete(source.key);
    state.visual.pinnedKeys.add(target.key);
  }
  if (state.visual.selectedKey === source.key) state.visual.selectedKey = target.key;
  const membership = state.visual.membership.get(source.key);
  if (membership) state.visual.membership.set(target.key, membership);
  state.visual.membership.delete(source.key);
  state.universes.get(source.universeKey)?.groupKeys.delete(source.key);
  state.groups.delete(source.key);
  return true;
}

function applyIdentityReconciliation(state, event) {
  const sourceKey = radioKeyFor(event.fromUniverseKey, event.fromRadioIdentityKey);
  const source = state.radios.get(sourceKey);
  const universe = ensureUniverse(state, event);
  const target = ensureRadio(state, universe, event, event.radio);
  const mappedGroup = event.group ? ensureGroup(state, universe, event, event.group) : null;
  if (!universe || !target) return { applied: false, reason: 'capacity' };
  if (source && source.key !== target.key) {
    const sourceAffiliationGroups = new Set([...source.affiliations.values()].map((evidence) => evidence.groupKey));
    source.affiliations.forEach((evidence, scopeKey) => {
      const sourceGroup = state.groups.get(evidence.groupKey);
      const canMap = mappedGroup && (sourceAffiliationGroups.size === 1 ||
        sourceGroup?.identityKey === mappedGroup.identityKey);
      const transferred = Object.freeze({
        ...evidence,
        groupKey: canMap ? mappedGroup.key : evidence.groupKey,
        reconciledAtMs: event.observedAtMs
      });
      const current = target.affiliations.get(scopeKey);
      if (!current || transferred.observedAtMs > current.observedAtMs) target.affiliations.set(scopeKey, transferred);
    });
    source.siteEvidence.forEach((evidence) => {
      const existing = target.siteEvidence.findIndex((candidate) => candidate.key === evidence.key);
      if (existing >= 0) target.siteEvidence.splice(existing, 1);
      target.siteEvidence.push(evidence);
    });
    target.siteEvidence.sort((left, right) => right.observedAtMs - left.observedAtMs || left.key.localeCompare(right.key));
    if (target.siteEvidence.length > state.config.state.hardSiteEvidencePerEntity) {
      target.siteEvidence.length = state.config.state.hardSiteEvidencePerEntity;
    }
    source.activeCallKeys.forEach((callKey) => {
      const call = state.activeCalls.get(callKey);
      if (call) {
        call.radioKey = target.key;
        call.universeKey = universe.key;
      }
      target.activeCallKeys.add(callKey);
    });
    target.createdAtMs = Math.min(target.createdAtMs, source.createdAtMs);
    target.lastObservedAtMs = Math.max(target.lastObservedAtMs, source.lastObservedAtMs, event.observedAtMs);
    target.lastMeaningfulAtMs = Math.max(target.lastMeaningfulAtMs, source.lastMeaningfulAtMs);
    target.lastMigrationAtMs = Math.max(target.lastMigrationAtMs, source.lastMigrationAtMs);
    target.comparisonEpoch = Math.max(target.comparisonEpoch, source.comparisonEpoch) + 1;
    target.afterglowUntilMs = Math.max(target.afterglowUntilMs, source.afterglowUntilMs);
    target.transitionEventIds = [...new Set([...source.transitionEventIds, ...target.transitionEventIds])]
      .slice(-state.config.state.hardTransitionsPerRadio);
    if (mappedGroup && source.visualParentGroupKey) target.visualParentGroupKey = mappedGroup.key;
    else if (!target.visualParentGroupKey) target.visualParentGroupKey = source.visualParentGroupKey;
    if (mappedGroup && source.recentTxGroupKey) target.recentTxGroupKey = mappedGroup.key;
    else if (!target.recentTxGroupKey) target.recentTxGroupKey = source.recentTxGroupKey;
    source.relatedGroupKeys.forEach((key) => state.groups.get(key)?.radioKeys.delete(source.key));
    source.affiliatedGroupKeys.forEach((key) => state.groups.get(key)?.affiliatedRadioKeys.delete(source.key));
    state.universes.get(source.universeKey)?.radioKeys.delete(source.key);
    if (source.pinned) {
      state.visual.pinnedKeys.delete(source.key);
      target.pinned = true;
      state.visual.pinnedKeys.add(target.key);
    }
    if (state.visual.selectedKey === source.key) state.visual.selectedKey = target.key;
    const membership = state.visual.membership.get(source.key);
    if (membership) state.visual.membership.set(target.key, membership);
    state.visual.membership.delete(source.key);
    state.pendingEffects = state.pendingEffects.filter((effect) => effect.nodeKey !== source.key &&
      effect.sourceKey !== source.key);
    target.reconciledFromKey = source.key;
    target.reconciledAtMs = event.observedAtMs;
    state.radios.delete(source.key);
  }
  if (mappedGroup && event.fromGroupKey) {
    mergeReconciledGroup(state, event.fromGroupKey, mappedGroup, event.observedAtMs);
  }
  rebuildAffiliationIndexes(state, target);
  refreshRadioGroupMembership(state, target);
  appendHistory(state, {
    type: 'identity_reconciled',
    label: 'Identity scope reconciled',
    observedAtMs: event.observedAtMs,
    fromUniverseKey: event.fromUniverseKey,
    fromRadioKey: sourceKey,
    universeKey: universe.key,
    radioKey: target.key,
    groupKey: mappedGroup?.key || '',
    semanticMigration: false
  });
  return { applied: true, reconciled: Boolean(source), radioKey: target.key };
}

function reconcileProvisionalIdentity(state, event) {
  if (!event?.radioSystemKey || !event.configurationId || !event.radio?.observedLocalId ||
      event.kind === 'identity_reconciled' || (event.kind === 'call' && event.phase === 'end')) return false;
  const canonicalKey = radioKeyFor(event.universeKey, event.radio.identityKey);
  if (!canonicalKey || state.radios.has(canonicalKey)) return false;
  const provisionalUniverseKey = universeKeyFor({ configurationId: event.configurationId });
  if (!provisionalUniverseKey || provisionalUniverseKey === event.universeKey) return false;
  const provisionalUniverse = state.universes.get(provisionalUniverseKey);
  if (!provisionalUniverse) return false;
  const matches = [...provisionalUniverse.radioKeys].map((key) => state.radios.get(key)).filter((radio) => radio &&
    radio.observedLocalId === event.radio.observedLocalId && (!radio.protocol || !event.protocol ||
      radio.protocol === event.protocol));
  if (matches.length !== 1) return false;
  let fromGroupKey = '';
  if (event.group?.observedLocalId) {
    const groupMatches = [...provisionalUniverse.groupKeys].map((key) => state.groups.get(key)).filter((group) =>
      group && group.kind === event.group.kind && group.observedLocalId === event.group.observedLocalId);
    if (groupMatches.length === 1) fromGroupKey = groupMatches[0].key;
  }
  applyIdentityReconciliation(state, {
    ...event,
    kind: 'identity_reconciled',
    eventId: '',
    group: fromGroupKey ? event.group : null,
    fromUniverseKey: provisionalUniverseKey,
    fromRadioIdentityKey: matches[0].identityKey,
    fromGroupKey,
    implicit: true
  });
  const sourceUniverse = state.universes.get(provisionalUniverseKey);
  if (sourceUniverse && !sourceUniverse.groupKeys.size && !sourceUniverse.radioKeys.size &&
      !entityProtected(state, sourceUniverse)) {
    state.universes.delete(provisionalUniverseKey);
    state.visual.membership.delete(provisionalUniverseKey);
  }
  return true;
}

function overflowBucket(state, event, groupKey = '') {
  const key = groupKey || event.universeKey;
  const current = state.overflowActive.get(key) || {
    key,
    universeKey: event.universeKey,
    groupKey,
    count: 0,
    saturated: false,
    lastObservedAtMs: 0,
    expiresAtMs: 0
  };
  const known = state.overflowCalls.get(event.callKey);
  if (known) {
    if (known.groupKey !== key) {
      const previousBucket = state.overflowActive.get(known.groupKey);
      if (previousBucket) {
        previousBucket.count = Math.max(0, previousBucket.count - 1);
        if (previousBucket.count === 0 && !previousBucket.saturated) {
          state.overflowActive.delete(known.groupKey);
        }
      }
      current.count += 1;
      known.groupKey = key;
    }
    if (event.radioKey) known.radioKey = event.radioKey;
    known.expiresAtMs = event.observedAtMs + state.config.state.activeOverflowTtlMs;
  } else if (state.overflowCalls.size < state.config.state.hardOverflowCallKeys) {
    state.overflowCalls.set(event.callKey, {
      callKey: event.callKey,
      groupKey: key,
      radioKey: event.radioKey || '',
      expiresAtMs: event.observedAtMs + state.config.state.activeOverflowTtlMs
    });
    current.count += 1;
  } else {
    current.saturated = true;
  }
  current.lastObservedAtMs = event.observedAtMs;
  current.expiresAtMs = event.observedAtMs + state.config.state.activeOverflowTtlMs;
  boundedMapSet(state.overflowActive, key, current, state.config.state.hardGroups);
  state.overflow.activeCalls += 1;
}

function removeOverflowCall(state, callKey) {
  const record = state.overflowCalls.get(callKey);
  if (!record) return false;
  state.overflowCalls.delete(callKey);
  const bucket = state.overflowActive.get(record.groupKey);
  if (bucket) {
    bucket.count = Math.max(0, bucket.count - 1);
    if (bucket.count === 0 && !bucket.saturated) state.overflowActive.delete(record.groupKey);
  }
  return true;
}

function detachCall(state, call, observedAtMs, proven, reason) {
  const group = state.groups.get(call.groupKey);
  const radio = state.radios.get(call.radioKey);
  const universe = state.universes.get(call.universeKey);
  group?.activeCallKeys.delete(call.key);
  radio?.activeCallKeys.delete(call.key);
  refreshRadioGroupMembership(state, radio);
  if (group) {
    group.afterglowUntilMs = Math.max(group.afterglowUntilMs,
      observedAtMs + state.config.animation.txReleaseMs);
    group.lastObservedAtMs = Math.max(group.lastObservedAtMs, observedAtMs);
  }
  if (radio) {
    radio.afterglowUntilMs = Math.max(radio.afterglowUntilMs,
      observedAtMs + state.config.animation.txReleaseMs);
    radio.lastObservedAtMs = Math.max(radio.lastObservedAtMs, observedAtMs);
  }
  if (universe) {
    universe.afterglowUntilMs = Math.max(universe.afterglowUntilMs,
      observedAtMs + state.config.animation.txReleaseMs);
    universe.lastObservedAtMs = Math.max(universe.lastObservedAtMs, observedAtMs);
  }
  state.activeCalls.delete(call.key);
  addEffect(state, {
    type: 'afterglow',
    nodeKey: radio?.key || group?.key || call.universeKey,
    targetKey: group?.key || '',
    createdAtMs: observedAtMs,
    animationEndsAtMs: observedAtMs + state.config.animation.txReleaseMs,
    expiresAtMs: observedAtMs + state.config.animation.txReleaseMs,
    coalesceKey: `release:${radio?.key || group?.key || call.universeKey}`,
    uncertain: !proven
  });
  appendHistory(state, {
    type: 'transmission_end',
    label: proven ? 'Transmission ended' : 'Transmission activity became uncertain',
    observedAtMs,
    universeKey: call.universeKey,
    groupKey: call.groupKey,
    radioKey: call.radioKey,
    callKey: call.key,
    certainty: proven ? 'confirmed' : 'uncertain',
    reason
  });
}

function updateCallMembership(state, call, group, radio) {
  const previousRadio = state.radios.get(call.radioKey);
  if (call.groupKey && call.groupKey !== group?.key) state.groups.get(call.groupKey)?.activeCallKeys.delete(call.key);
  if (call.radioKey && call.radioKey !== radio?.key) state.radios.get(call.radioKey)?.activeCallKeys.delete(call.key);
  call.groupKey = group?.key || '';
  call.radioKey = radio?.key || '';
  group?.activeCallKeys.add(call.key);
  radio?.activeCallKeys.add(call.key);
  if (previousRadio && previousRadio !== radio) refreshRadioGroupMembership(state, previousRadio);
  refreshRadioGroupMembership(state, radio);
}

function setPendingGrant(state, event, universe, group) {
  const pendingKey = String(event.callKey || event.activationKey || '');
  if (!pendingKey || !universe) return { changed: false, key: '' };
  const current = state.pendingGrants.get(pendingKey);
  if (current?.universeKey === universe.key && current?.groupKey === (group?.key || '')) {
    current.lastObservedAtMs = Math.max(current.lastObservedAtMs, event.observedAtMs);
    return { changed: false, key: pendingKey };
  }
  if (current) clearPendingGrant(state, pendingKey);
  if (state.pendingGrants.size >= state.config.state.hardActiveCalls) {
    const removable = state.pendingGrants.keys().next().value;
    if (removable !== undefined) clearPendingGrant(state, removable);
  }
  const record = {
    key: pendingKey,
    universeKey: universe.key,
    groupKey: group?.key || '',
    activationKey: String(event.activationKey || ''),
    firstObservedAtMs: event.observedAtMs,
    lastObservedAtMs: event.observedAtMs
  };
  state.pendingGrants.set(pendingKey, record);
  universe.pendingGrantKeys.add(pendingKey);
  group?.pendingGrantKeys.add(pendingKey);
  return { changed: true, key: pendingKey };
}

function applyCall(state, event) {
  if (event.phase === 'end') {
    clearPendingGrant(state, event.callKey);
    const call = state.activeCalls.get(event.callKey);
    if (!call) return removeOverflowCall(state, event.callKey) ?
      { applied: true, ended: true, aggregated: true } : { applied: false, reason: 'unknown_call_end' };
    detachCall(state, call, event.observedAtMs, event.endProven, event.endProven ? 'explicit_end' : 'observed_idle');
    return { applied: true, ended: true };
  }
  const universe = ensureUniverse(state, event);
  if (!universe) {
    if (event.phase !== 'granted') overflowBucket(state, event);
    return { applied: false, reason: 'capacity', aggregated: event.phase !== 'granted' };
  }
  const group = ensureGroup(state, universe, event, event.group, event.groupKey);
  if (event.phase === 'granted') {
    const radio = event.radio ? ensureRadio(state, universe, event, event.radio, event.radioKey) : null;
    // A grant can introduce real identities, but it is not evidence that the named radio transmitted to the
    // granted group. Keep it independent from both TX recency and affiliation/visual-parent relationships.
    if (radio) {
      radio.grantOnly = !radio.affiliations.size && !radio.activeCallKeys.size && !radio.recentTxGroupKey;
      refreshRadioGroupMembership(state, radio);
    }
    const pending = setPendingGrant(state, event, universe, group);
    if (pending.changed) {
      appendHistory(state, {
        type: 'grant_pending',
        label: 'Grant observed; transmission pending',
        observedAtMs: event.observedAtMs,
        universeKey: universe.key,
        groupKey: group?.key || '',
        callKey: event.callKey
      });
    }
    return { applied: true, granted: true, visualChanged: pending.changed };
  }
  if (!group) {
    overflowBucket(state, event);
    return { applied: false, reason: 'unknown_target' };
  }
  let call = state.activeCalls.get(event.callKey);
  const introduced = !call;
  if (introduced && state.activeCalls.size >= state.config.state.hardActiveCalls) {
    overflowBucket(state, event, group.key);
    return { applied: false, reason: 'active_call_capacity', aggregated: true };
  }
  if (introduced) removeOverflowCall(state, event.callKey);
  const radio = event.radio ? ensureRadio(state, universe, event, event.radio, event.radioKey) : null;
  if (radio) radio.grantOnly = false;
  const previousGroupKey = call?.groupKey || '';
  const previousRadioKey = call?.radioKey || '';
  const previousEncrypted = call?.encrypted;
  const groupWasActive = group.activeCallKeys.size > 0;
  const radioWasActive = Boolean(radio?.activeCallKeys.size);
  const radioInRelease = Boolean(radio && radio.afterglowUntilMs > event.observedAtMs &&
    radio.recentTxGroupKey === group.key);
  const groupInRelease = group.afterglowUntilMs > event.observedAtMs;
  const pendingCleared = clearPendingGrant(state, event.callKey);
  if (!call) {
    call = {
      key: event.callKey,
      legId: event.legId,
      universeKey: universe.key,
      groupKey: '',
      radioKey: '',
      resourceContext: event.resourceContext,
      protocol: event.protocol,
      encrypted: event.encrypted,
      startedAtMs: event.observedAtMs,
      lastObservedAtMs: event.observedAtMs,
      expiresAtMs: event.observedAtMs + state.config.state.callStaleAfterMs,
      uncertain: event.continuityUncertain === true
    };
    state.activeCalls.set(call.key, call);
  }
  call.lastObservedAtMs = Math.max(call.lastObservedAtMs, event.observedAtMs);
  call.expiresAtMs = Math.max(call.expiresAtMs, event.observedAtMs + state.config.state.callStaleAfterMs);
  call.encrypted = event.encrypted;
  call.universeKey = universe.key;
  updateCallMembership(state, call, group, radio);
  group.lastMeaningfulAtMs = event.observedAtMs;
  universe.lastMeaningfulAtMs = event.observedAtMs;
  addSiteEvidence(group, event, state.config.state.hardSiteEvidencePerEntity);
  addSiteEvidence(universe, event, state.config.state.hardSiteEvidencePerEntity);
  if (radio) {
    radio.recentTxGroupKey = group.key;
    if (!radio.visualParentGroupKey && !radio.affiliations.size) radio.visualParentGroupKey = group.key;
    radio.lastMeaningfulAtMs = event.observedAtMs;
    addSiteEvidence(radio, event, state.config.state.hardSiteEvidencePerEntity);
    refreshRadioGroupMembership(state, radio);
  }
  if (introduced) {
    const uncertainContinuity = event.continuityUncertain === true;
    const afterGap = uncertainContinuity && !['missing_update_timeout', 'missing_from_snapshot']
      .includes(event.continuityReason);
    appendHistory(state, {
      type: uncertainContinuity ? (afterGap ? 'transmission_observed_after_gap' :
        'transmission_observed_after_uncertainty') : 'transmission_start',
      label: uncertainContinuity ? (afterGap ? 'Current transmission observed after live gap' :
        'Current transmission observed after uncertain updates') :
        (radio ? 'Source transmission observed' : 'Transmission observed with unknown source'),
      observedAtMs: event.observedAtMs,
      universeKey: universe.key,
      groupKey: group.key,
      radioKey: radio?.key || '',
      callKey: call.key,
      encrypted: call.encrypted,
      site: event.site,
      certainty: uncertainContinuity ? 'unknown_continuity' : 'current',
      reason: uncertainContinuity ? event.continuityReason || 'live_gap' : ''
    });
    if (event.suppressIntroductionEffect !== true &&
        ((radio && !radioWasActive && !radioInRelease) || (!radio && !groupWasActive && !groupInRelease))) {
      addEffect(state, {
        type: radio ? 'tx_pulse' : 'destination_highlight',
        sourceKey: radio?.key || '',
        targetKey: group.key,
        nodeKey: radio?.key || group.key,
        createdAtMs: event.observedAtMs,
        animationEndsAtMs: event.observedAtMs + state.config.animation.pulseDurationMs,
        expiresAtMs: event.observedAtMs + state.config.animation.pulseDurationMs,
        coalesceKey: radio ? `tx-start:${radio.key}` : `target-start:${group.key}`,
        encrypted: call.encrypted
      });
    }
  }
  const visualChanged = introduced || pendingCleared || previousGroupKey !== group.key ||
    previousRadioKey !== (radio?.key || '') || previousEncrypted !== event.encrypted;
  return { applied: true, introduced, sourceKnown: Boolean(radio), visualChanged };
}

function applyNormalizedObservation(state, event, generation = state.generation, options = {}) {
  if (generation !== state.generation) return { applied: false, reason: 'stale_generation' };
  if (!event) return { applied: false, reason: 'unsupported' };
  const atMs = eventTime(event.observedAtMs);
  const dedupeAtMs = eventTime(options.receivedAtMs, atMs);
  const fingerprint = options.skipDedupe ? '' : semanticFingerprint(event);
  if (!acceptFingerprint(state, fingerprint, dedupeAtMs)) return { applied: false, reason: 'duplicate' };
  state.transport.lastObservationAtMs = Math.max(state.transport.lastObservationAtMs, atMs);
  state.transport.status = 'open';
  reconcileProvisionalIdentity(state, event);
  if (event.kind === 'affiliation') return applyAffiliation(state, event);
  if (event.kind === 'presence_remove') return applyPresenceRemoval(state, event);
  if (event.kind === 'identity_reconciled') return applyIdentityReconciliation(state, event);
  if (event.kind === 'call') return applyCall(state, event);
  return { applied: false, reason: 'unsupported' };
}

function applyObservation(state, raw, generation = state.generation, receivedAtMs = Date.now()) {
  if (generation !== state.generation) return { applied: false, reason: 'stale_generation' };
  return applyNormalizedObservation(state, normalizeObservation(raw, receivedAtMs), generation, { receivedAtMs });
}

function enqueueObservation(state, raw, generation = state.generation, receivedAtMs = Date.now()) {
  if (generation !== state.generation) return { accepted: false, reason: 'stale_generation' };
  if (state.incomingQueue.length >= state.config.state.hardIncomingQueue) {
    const dropped = state.incomingQueue.length + 1;
    state.overflow.queueDropped += dropped;
    markTransportGap(state, { reason: 'incoming_queue_overflow', dropped }, generation, receivedAtMs);
    return { accepted: false, reason: 'queue_capacity' };
  }
  state.incomingQueue.push({ raw, generation, receivedAtMs });
  return { accepted: true };
}

function drainObservationQueue(state, limit = state.config.state.incomingBatchSize) {
  const count = Math.max(0, Math.min(state.config.state.incomingBatchSize, Math.trunc(Number(limit) || 0),
    state.incomingQueue.length));
  return state.incomingQueue.splice(0, count)
    .map((queued) => applyObservation(state, queued.raw, queued.generation, queued.receivedAtMs));
}

function ingestChannelActivitySnapshot(state, snapshot, generation = state.generation, receivedAtMs = Date.now()) {
  if (generation !== state.generation) return { applied: false, reason: 'stale_generation' };
  const snapshotAfterGap = state.transport.status === 'gap';
  const sourceKey = String(snapshot?.source_key || 'channel_activity');
  const revision = Number(snapshot?.revision);
  const previousRevision = state.snapshotRevisions.get(sourceKey) || 0;
  if (Number.isSafeInteger(revision) && revision > 0) {
    if (previousRevision && revision <= previousRevision) return { applied: false, reason: 'stale_snapshot' };
    boundedMapSet(state.snapshotRevisions, sourceKey, revision, state.config.state.hardUniverses);
  }
  const seen = new Set();
  let introduced = 0;
  let updated = 0;
  let ended = 0;
  let pendingChanged = 0;
  let visualChanged = false;
  (Array.isArray(snapshot?.tables) ? snapshot.tables : []).forEach((table) => {
    (Array.isArray(table?.rows) ? table.rows : []).forEach((row) => {
      const snapshotKey = `${String(table?.table_id || '')}|${String(row?.key || '')}`;
      if (snapshotKey === '|') return;
      seen.add(snapshotKey);
      const previous = state.snapshotRows.get(snapshotKey);
      const txState = String(row?.tx_state || '').trim().toLowerCase();
      const active = snapshotRowActive(row);
      const pending = !active && snapshotRowPending(row);
      const normalized = normalizeSnapshotRow(table, row, receivedAtMs);
      if (!active && !pending) {
        if (previous) {
          if (previous.pending && clearPendingGrant(state, previous.callKey)) {
            pendingChanged += 1;
            visualChanged = true;
          }
          if (previous.active && previous.callKey) {
            const call = state.activeCalls.get(previous.callKey);
            if (call) detachCall(state, call, receivedAtMs, normalized?.endProven === true,
              normalized?.txEndReason || (txState === 'uncertain' ? 'uncertain_snapshot' : 'idle_snapshot'));
            ended += 1;
            visualChanged = true;
          }
          previous.active = false;
          previous.pending = false;
          previous.boundary = false;
          previous.recoverAfterGap = false;
          previous.recoveryReason = '';
          previous.txState = normalized?.txState || txState || 'ended';
          previous.activationKey = normalized?.activationKey || previous.activationKey;
          previous.lastObservedAtMs = receivedAtMs;
        }
        return;
      }
      if (!normalized) return;
      const activationFingerprint = `snapshot-activation:${snapshotKey}:${normalized.activationKey}`;
      const sameActivation = previous?.activationKey === normalized.activationKey;
      if (pending) {
        if (previous?.active && previous.callKey) {
          const call = state.activeCalls.get(previous.callKey);
          if (call) detachCall(state, call, receivedAtMs, false, 'snapshot_pending');
          visualChanged = true;
        }
        if (!sameActivation) {
          if (previous?.pending && clearPendingGrant(state, previous.callKey)) pendingChanged += 1;
          const result = applyNormalizedObservation(state, { ...normalized, phase: 'granted' }, generation,
            { skipDedupe: true });
          if (result.visualChanged) {
            pendingChanged += 1;
            visualChanged = true;
          }
        }
        state.snapshotRows.set(snapshotKey, {
          activationKey: normalized.activationKey,
          activationOrder: normalized.activationOrder,
          callKey: normalized.callKey,
          active: false,
          pending: true,
          boundary: Boolean(previous?.boundary && sameActivation),
          recoverAfterGap: false,
          recoveryReason: '',
          txState: normalized.txState,
          lastObservedAtMs: receivedAtMs
        });
        return;
      }
      if (previous?.recoverAfterGap && sameActivation) {
        const recoveryReason = previous.recoveryReason || 'live_gap';
        const result = applyNormalizedObservation(state, {
          ...normalized,
          phase: 'active',
          observedAtMs: Math.max(normalized.observedAtMs, receivedAtMs),
          continuityUncertain: true,
          continuityReason: recoveryReason,
          suppressIntroductionEffect: true
        }, generation, { skipDedupe: true });
        state.snapshotRows.set(snapshotKey, {
          activationKey: normalized.activationKey,
          activationOrder: normalized.activationOrder,
          callKey: normalized.callKey,
          active: result.applied === true,
          pending: false,
          boundary: false,
          recoverAfterGap: false,
          recoveryReason: '',
          txState: normalized.txState,
          lastObservedAtMs: receivedAtMs
        });
        if (result.applied) introduced += 1;
        visualChanged ||= result.visualChanged === true || result.applied === true;
        return;
      }
      if (previous?.active && state.activeCalls.has(previous.callKey)) {
        if (sameActivation && previous.callKey === normalized.callKey) {
          const result = applyNormalizedObservation(state, normalized, generation, { skipDedupe: true });
          previous.activationKey = normalized.activationKey;
          previous.txState = normalized.txState;
          previous.boundary = false;
          previous.lastObservedAtMs = receivedAtMs;
          updated += 1;
          visualChanged ||= result.visualChanged === true;
          return;
        }
        detachCall(state, state.activeCalls.get(previous.callKey), receivedAtMs, false, 'snapshot_replaced');
        previous.active = false;
        visualChanged = true;
      }
      if (sameActivation && previous && !previous.pending) {
        if (previous.boundary || !previous.active) {
          previous.lastObservedAtMs = receivedAtMs;
          return;
        }
      }
      if (previous?.active && previous.callKey) {
        const call = state.activeCalls.get(previous.callKey);
        if (call) detachCall(state, call, receivedAtMs, false, 'snapshot_replaced');
      }
      if (!acceptFingerprint(state, activationFingerprint, receivedAtMs)) {
        state.snapshotRows.set(snapshotKey, {
          activationKey: normalized.activationKey,
          activationOrder: normalized.activationOrder,
          callKey: normalized.callKey,
          active: false,
          pending: false,
          boundary: false,
          recoverAfterGap: false,
          recoveryReason: '',
          txState: normalized.txState,
          lastObservedAtMs: receivedAtMs
        });
        return;
      }
      const result = applyNormalizedObservation(state, {
        ...normalized,
        phase: 'active',
        ...(snapshotAfterGap ? {
          observedAtMs: Math.max(normalized.observedAtMs, receivedAtMs),
          continuityUncertain: true,
          continuityReason: 'live_gap',
          suppressIntroductionEffect: true
        } : {})
      }, generation, { skipDedupe: true });
      state.snapshotRows.set(snapshotKey, {
        activationKey: normalized.activationKey,
        activationOrder: normalized.activationOrder,
        callKey: normalized.callKey,
        active: result.applied === true,
        pending: false,
        boundary: false,
        recoverAfterGap: false,
        recoveryReason: '',
        txState: normalized.txState,
        lastObservedAtMs: receivedAtMs
      });
      if (result.applied) introduced += 1;
      visualChanged ||= result.visualChanged === true || result.applied === true;
    });
  });
  state.snapshotRows.forEach((record, key) => {
    if (!seen.has(key)) {
      if (record.pending && clearPendingGrant(state, record.callKey)) {
        record.pending = false;
        pendingChanged += 1;
        visualChanged = true;
      }
      if (record.active) {
        const call = state.activeCalls.get(record.callKey);
        if (call) detachCall(state, call, receivedAtMs, false, 'missing_from_snapshot');
        record.active = false;
        record.recoverAfterGap = true;
        record.recoveryReason = 'missing_from_snapshot';
        record.lastObservedAtMs = receivedAtMs;
        ended += 1;
        visualChanged = true;
      }
    }
  });
  trimSnapshotRows(state);
  return { applied: true, introduced, updated, ended, pendingChanged, visualChanged };
}

function trimSnapshotRows(state) {
  const snapshotOverflow = state.snapshotRows.size - state.config.state.hardDedupeEntries;
  if (snapshotOverflow <= 0) return 0;
  [...state.snapshotRows.entries()].sort((left, right) =>
    left[1].lastObservedAtMs - right[1].lastObservedAtMs || left[0].localeCompare(right[0]))
    .slice(0, snapshotOverflow).forEach(([key, record]) => {
      if (record.pending) clearPendingGrant(state, record.callKey);
      state.snapshotRows.delete(key);
    });
  return snapshotOverflow;
}

function establishChannelActivityBoundary(state, snapshot, generation = state.generation, receivedAtMs = Date.now()) {
  if (generation !== state.generation) return { applied: false, reason: 'stale_generation' };
  let recorded = 0;
  (Array.isArray(snapshot?.tables) ? snapshot.tables : []).forEach((table) => {
    (Array.isArray(table?.rows) ? table.rows : []).forEach((row) => {
      const tableId = String(table?.table_id || '').trim();
      const rowKey = String(row?.key || '').trim();
      if (!tableId || !rowKey) return;
      const normalized = normalizeSnapshotRow(table, row, receivedAtMs);
      const activationKey = normalized?.activationKey || String(row?.call_leg_id || row?.activation_order || '').trim();
      if (!activationKey) return;
      const snapshotKey = `${tableId}|${rowKey}`;
      const active = snapshotRowActive(row);
      if (active) acceptFingerprint(state, `snapshot-activation:${snapshotKey}:${activationKey}`, receivedAtMs);
      state.snapshotRows.set(snapshotKey, {
        activationKey,
        activationOrder: Number(row?.activation_order) || 0,
        callKey: normalized?.callKey || '',
        active: false,
        pending: snapshotRowPending(row),
        boundary: true,
        recoverAfterGap: false,
        recoveryReason: '',
        txState: String(row?.tx_state || '').trim().toLowerCase(),
        lastObservedAtMs: receivedAtMs
      });
      recorded += 1;
    });
  });
  trimSnapshotRows(state);
  return { applied: true, recorded };
}

function markTransportGap(state, detail = {}, generation = state.generation, atMs = Date.now()) {
  if (generation !== state.generation) return { applied: false, reason: 'stale_generation' };
  const observedAtMs = eventTime(atMs);
  const previousGap = state.transport.status === 'gap' ? state.transport.gap : null;
  const active = [...state.activeCalls.values()];
  active.forEach((call) => detachCall(state, call, observedAtMs, false, detail.reason || 'transport_gap'));
  state.groups.forEach((group) => { group.afterglowUntilMs = 0; });
  state.radios.forEach((radio) => { radio.afterglowUntilMs = 0; });
  state.universes.forEach((universe) => {
    universe.afterglowUntilMs = 0;
    universe.pendingGrantKeys.clear();
  });
  state.groups.forEach((group) => group.pendingGrantKeys.clear());
  state.pendingGrants.clear();
  state.snapshotRows.forEach((record) => {
    if (!record.active) return;
    record.active = false;
    record.recoverAfterGap = true;
    record.recoveryReason = detail.reason || 'transport_gap';
    record.lastObservedAtMs = observedAtMs;
  });
  if (!previousGap) {
    state.radios.forEach((radio) => {
      radio.comparisonEpoch += 1;
      radio.affiliationContinuity = 'uncertain';
    });
  }
  // These bounded structures all belong to the prior live edge. Discarding them prevents queued effects from
  // crossing a gap and allows process-local event IDs and snapshot revisions to restart safely.
  state.incomingQueue.length = 0;
  state.pendingEffects.length = 0;
  state.dedupe.clear();
  state.snapshotRevisions.clear();
  state.maintenance.nextDedupePruneAtMs = observedAtMs;
  state.overflowCalls.clear();
  state.overflowActive.clear();
  state.transport.status = 'gap';
  state.transport.gap = Object.freeze({
    observedAtMs: previousGap?.observedAtMs || observedAtMs,
    dropped: Math.max(0, Math.trunc(Number(previousGap?.dropped) || 0)) +
      Math.max(0, Math.trunc(Number(detail.dropped) || 0)),
    reason: String(previousGap?.reason || detail.reason || 'live_gap').slice(0, 64)
  });
  if (!previousGap) {
    appendHistory(state, {
      type: 'transport_gap',
      label: 'Live observation gap',
      observedAtMs,
      certainty: 'uncertain',
      dropped: state.transport.gap.dropped,
      reason: state.transport.gap.reason
    });
  }
  return { applied: true, expiredCalls: active.length, coalesced: Boolean(previousGap) };
}

function tickNetworkState(state, atMs = Date.now()) {
  const now = eventTime(atMs);
  [...state.activeCalls.values()].filter((call) => call.expiresAtMs <= now)
    .forEach((call) => {
      detachCall(state, call, now, false, 'missing_update_timeout');
      state.snapshotRows.forEach((record) => {
        if (record.callKey !== call.key || !record.active) return;
        record.active = false;
        record.recoverAfterGap = true;
        record.recoveryReason = 'missing_update_timeout';
        record.lastObservedAtMs = now;
      });
    });
  state.pendingEffects = state.pendingEffects.filter((effect) => effect.expiresAtMs > now);
  [...state.overflowCalls.values()].filter((record) => record.expiresAtMs <= now)
    .forEach((record) => removeOverflowCall(state, record.callKey));
  state.overflowActive.forEach((bucket, key) => {
    if (bucket.expiresAtMs <= now) state.overflowActive.delete(key);
  });
  pruneDedupe(state, now);
  if (now >= state.maintenance.nextRetentionSweepAtMs) {
    state.maintenance.nextRetentionSweepAtMs = now + Math.min(60_000,
      Math.max(1_000, Math.floor(state.config.state.inactiveRetentionMs / 8)));
    const cutoff = now - state.config.state.inactiveRetentionMs;
    [...state.radios.values()].filter((radio) => radio.lastMeaningfulAtMs < cutoff && !radio.activeCallKeys.size)
      .forEach((radio) => deleteRadio(state, radio));
    [...state.groups.values()].filter((group) => group.lastMeaningfulAtMs < cutoff && !group.activeCallKeys.size)
      .forEach((group) => deleteGroup(state, group));
    [...state.universes.values()].filter((universe) => universe.lastMeaningfulAtMs < cutoff)
      .forEach((universe) => deleteUniverse(state, universe));
  }
  return networkStateCounts(state);
}

function setSelectedEntity(state, key = null) {
  state.visual.selectedKey = key && (state.universes.has(key) || state.groups.has(key) || state.radios.has(key)) ? key : null;
  return state.visual.selectedKey;
}

function setEntityPinned(state, key, pinned = true) {
  const entity = state.universes.get(key) || state.groups.get(key) || state.radios.get(key);
  if (!entity) return false;
  if (pinned && !state.visual.pinnedKeys.has(key) &&
      state.visual.pinnedKeys.size >= state.config.state.hardPinnedEntities) return false;
  entity.pinned = Boolean(pinned);
  if (entity.pinned) state.visual.pinnedKeys.add(key);
  else state.visual.pinnedKeys.delete(key);
  return true;
}

function requestMetadataSlot(state, key, atMs = Date.now()) {
  const identity = String(key || '').trim();
  if (!identity) return { accepted: false, reason: 'invalid_key' };
  if (state.metadata.cache.has(identity)) return { accepted: false, reason: 'cached',
    value: state.metadata.cache.get(identity).value };
  if (state.metadata.pending.has(identity)) return { accepted: false, reason: 'pending' };
  if (state.metadata.pending.size >= state.config.state.hardMetadataRequests) {
    state.overflow.metadataRequestsRejected += 1;
    return { accepted: false, reason: 'capacity' };
  }
  state.metadata.pending.set(identity, eventTime(atMs));
  return { accepted: true };
}

function completeMetadataRequest(state, key, value, atMs = Date.now()) {
  const identity = String(key || '').trim();
  if (!state.metadata.pending.delete(identity)) return false;
  boundedMapSet(state.metadata.cache, identity, { value, observedAtMs: eventTime(atMs) },
    state.config.state.hardMetadataCacheEntries);
  return true;
}

function networkStateCounts(state) {
  return Object.freeze({
    generation: state.generation,
    universes: state.universes.size,
    groups: state.groups.size,
    radios: state.radios.size,
    activeCalls: state.activeCalls.size,
    pendingGrants: state.pendingGrants.size,
    semanticEvents: state.semanticEvents.length,
    dedupeEntries: state.dedupe.size,
    queuedObservations: state.incomingQueue.length,
    pendingEffects: state.pendingEffects.length,
    pinnedEntities: state.visual.pinnedKeys.size,
    overflowActive: [...state.overflowActive.values()]
      .reduce((sum, bucket) => sum + bucket.count + (bucket.saturated ? 1 : 0), 0)
  });
}

export {
  applyNormalizedObservation,
  applyObservation,
  clearNetworkState,
  completeMetadataRequest,
  createNetworkState,
  drainObservationQueue,
  enqueueObservation,
  establishChannelActivityBoundary,
  ingestChannelActivitySnapshot,
  markTransportGap,
  networkStateCounts,
  requestMetadataSlot,
  setEntityPinned,
  setSelectedEntity,
  tickNetworkState
};
