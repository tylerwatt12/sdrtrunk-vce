'use strict';

const ATTENTION_DEFAULTS = Object.freeze({
  routineHoldMs: 6_000,
  manualCooldownMs: 10_000,
  importantEventHoldMs: 6_000,
  emergencyEventHoldMs: 12_000
});

const IMPORTANT_EVENT_PRIORITY = Object.freeze({
  affiliation_observed: 2,
  affiliation_observed_after_gap: 2,
  explicit_presence_remove: 3,
  signal_check: 4,
  signal_page: 5,
  signal_busy: 6,
  signal_denial: 7,
  observed_affiliation_change: 8,
  affiliation_change: 8,
  signal_emergency: 9
});

function finiteTime(value) {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? Math.max(0, numeric) : 0;
}

function normalizeScope(value = {}) {
  const level = value.level === 'group' ? 'group' : value.level === 'system' ? 'system' : 'overview';
  return Object.freeze({
    level,
    universeKey: level === 'overview' ? '' : String(value.universeKey || ''),
    groupKey: level === 'group' ? String(value.groupKey || '') : ''
  });
}

function scopeSignature(scope) {
  return `${scope.level}|${scope.universeKey}|${scope.groupKey}`;
}

function activeCalls(state, atMs) {
  if (!(state?.activeCalls instanceof Map)) return [];
  return [...state.activeCalls.values()].filter((call) => {
    if (!call || call.active === false || !call.universeKey) return false;
    const expiresAtMs = Number(call.expiresAtMs);
    return !Number.isFinite(expiresAtMs) || expiresAtMs > atMs;
  });
}

function targetForCall(call, scope) {
  if (scope.level === 'overview') return call.universeKey;
  if (call.universeKey !== scope.universeKey) return '';
  if (scope.level === 'system') return call.groupKey || '';
  return call.groupKey === scope.groupKey ? scope.groupKey : '';
}

function frozenHotspot(bucket, scope) {
  const radioKeys = [...bucket.radioKeys].sort();
  const callKeys = [...bucket.callKeys].sort();
  const groupKeys = [...bucket.groupKeys].sort();
  const centroidKeys = [...new Set([...groupKeys, ...radioKeys])];
  if (!centroidKeys.length && bucket.targetKey) centroidKeys.push(bucket.targetKey);
  return Object.freeze({
    attentionKey: scope.level === 'overview' ? `overview|${bucket.targetKey}` : `grant|${bucket.targetKey}`,
    targetKey: bucket.targetKey,
    targetType: scope.level === 'overview' ? 'universe' : 'group',
    universeKey: scope.level === 'overview' ? bucket.targetKey : scope.universeKey,
    groupKey: scope.level === 'overview' ? '' : bucket.targetKey,
    radioKeys: Object.freeze(radioKeys),
    groupKeys: Object.freeze(groupKeys),
    callKeys: Object.freeze(callKeys),
    centroidKeys: Object.freeze(centroidKeys),
    score: Object.freeze({
      transmittingRadios: radioKeys.length,
      callLegs: callKeys.length,
      lastObservedAtMs: bucket.lastObservedAtMs
    })
  });
}

function compareHotspots(left, right, currentTargetKey = '') {
  const scoreDelta = right.score.transmittingRadios - left.score.transmittingRadios ||
    right.score.callLegs - left.score.callLegs ||
    right.score.lastObservedAtMs - left.score.lastObservedAtMs;
  if (scoreDelta) return scoreDelta;
  if (left.targetKey === currentTargetKey) return -1;
  if (right.targetKey === currentTargetKey) return 1;
  return left.targetKey.localeCompare(right.targetKey);
}

/**
 * Ranks exact active-call hotspots for consumers that need the individual hierarchy buckets. Pending grants and
 * entity afterglow are intentionally invisible because neither is a confirmed active call leg.
 */
function rankActiveCallHotspots(state, scopeValue, atMs = 0, currentTargetKey = '') {
  const scope = normalizeScope(scopeValue);
  const now = finiteTime(atMs);
  const buckets = new Map();
  activeCalls(state, now).forEach((call) => {
    const targetKey = targetForCall(call, scope);
    if (!targetKey) return;
    const bucket = buckets.get(targetKey) || {
      targetKey,
      radioKeys: new Set(),
      groupKeys: new Set(),
      callKeys: new Set(),
      lastObservedAtMs: 0
    };
    if (call.radioKey) bucket.radioKeys.add(String(call.radioKey));
    if (call.groupKey) bucket.groupKeys.add(String(call.groupKey));
    if (call.key) bucket.callKeys.add(String(call.key));
    bucket.lastObservedAtMs = Math.max(bucket.lastObservedAtMs,
      finiteTime(call.lastObservedAtMs || call.startedAtMs));
    buckets.set(targetKey, bucket);
  });

  if (scope.level === 'group' && scope.groupKey && !buckets.has(scope.groupKey)) {
    buckets.set(scope.groupKey, { targetKey: scope.groupKey, radioKeys: new Set(),
      groupKeys: new Set([scope.groupKey]), callKeys: new Set(), lastObservedAtMs: 0 });
  }

  return Object.freeze([...buckets.values()].map((bucket) => frozenHotspot(bucket, scope))
    .sort((left, right) => compareHotspots(left, right, currentTargetKey)));
}

function combinedActiveCallTarget(state, scope, atMs) {
  if (scope.level === 'group') return null;
  const calls = activeCalls(state, atMs).filter((call) =>
    scope.level === 'overview' || call.universeKey === scope.universeKey);
  if (!calls.length) return null;

  const universeKeys = [...new Set(calls.map((call) => String(call.universeKey || '')).filter(Boolean))].sort();
  const groupKeys = [...new Set(calls.map((call) => String(call.groupKey || '')).filter(Boolean))].sort();
  const radioKeys = [...new Set(calls.map((call) => String(call.radioKey || '')).filter(Boolean))].sort();
  const callKeys = [...new Set(calls.map((call) => String(call.key || '')).filter(Boolean))].sort();
  const visibleAnchors = scope.level === 'overview' ? universeKeys : groupKeys;
  const centroidKeys = [...new Set([...visibleAnchors, ...groupKeys, ...radioKeys])];
  const targetKey = visibleAnchors[0] || radioKeys[0] || scope.universeKey;
  const lastObservedAtMs = calls.reduce((latest, call) => Math.max(latest,
    finiteTime(call.lastObservedAtMs || call.startedAtMs)), 0);

  return Object.freeze({
    attentionKey: `grant|active-set|${scopeSignature(scope)}`,
    attentionKind: 'grant',
    targetKey,
    targetType: 'active_set',
    universeKey: scope.level === 'system' ? scope.universeKey : (universeKeys.length === 1 ? universeKeys[0] : ''),
    universeKeys: Object.freeze(universeKeys),
    groupKey: groupKeys.length === 1 ? groupKeys[0] : '',
    groupKeys: Object.freeze(groupKeys),
    radioKeys: Object.freeze(radioKeys),
    callKeys: Object.freeze(callKeys),
    centroidKeys: Object.freeze(centroidKeys),
    score: Object.freeze({
      importance: 1,
      transmittingRadios: radioKeys.length,
      callLegs: callKeys.length,
      lastObservedAtMs
    })
  });
}

function eventGroupKey(state, event) {
  const direct = String(event?.newGroupKey || event?.groupKey || event?.oldGroupKey || '');
  if (direct) return direct;
  const radio = state?.radios instanceof Map ? state.radios.get(event?.radioKey) : null;
  return String(radio?.visualParentGroupKey || radio?.recentTxGroupKey || '');
}

function eventTarget(state, event, scope) {
  const universeKey = String(event?.universeKey || '');
  if (!universeKey) return null;
  const groupKey = eventGroupKey(state, event);
  const radioKey = String(event?.radioKey || '');
  if (scope.level === 'overview') {
    return { targetKey: universeKey, targetType: 'universe', universeKey, groupKey, radioKey };
  }
  if (universeKey !== scope.universeKey) return null;
  if (scope.level === 'system') {
    return { targetKey: groupKey || radioKey || universeKey,
      targetType: groupKey ? 'group' : radioKey ? 'radio' : 'universe', universeKey, groupKey, radioKey };
  }
  if (groupKey !== scope.groupKey) return null;
  return { targetKey: scope.groupKey, targetType: 'group', universeKey, groupKey, radioKey };
}

function compareAttentionHotspots(left, right, currentTargetKey = '') {
  const scoreDelta = right.score.importance - left.score.importance ||
    right.score.transmittingRadios - left.score.transmittingRadios ||
    right.score.callLegs - left.score.callLegs ||
    right.score.lastObservedAtMs - left.score.lastObservedAtMs;
  if (scoreDelta) return scoreDelta;
  if (left.targetKey === currentTargetKey) return -1;
  if (right.targetKey === currentTargetKey) return 1;
  return left.targetKey.localeCompare(right.targetKey);
}

/** Ranks fresh high-priority events ahead of one combined confirmed-call target for the current scope. */
function rankAttentionHotspots(state, scopeValue, atMs = 0, currentTargetKey = '', options = {}) {
  const scope = normalizeScope(scopeValue);
  const now = finiteTime(atMs);
  const settings = { ...ATTENTION_DEFAULTS, ...options };
  const activeSet = combinedActiveCallTarget(state, scope, now);
  const ranked = activeSet ? [activeSet] : [];
  const events = Array.isArray(state?.semanticEvents) ? state.semanticEvents : [];
  const transportInterrupted = ['gap', 'error'].includes(String(state?.transport?.status || ''));

  if (!transportInterrupted) {
    for (let index = events.length - 1; index >= 0; index -= 1) {
      const event = events[index];
      const kind = String(event?.type || event?.kind || '');
      const importance = IMPORTANT_EVENT_PRIORITY[kind] || 0;
      if (!importance) continue;
      const observedAtMs = finiteTime(event?.observedAtMs);
      const holdMs = kind === 'signal_emergency' ? settings.emergencyEventHoldMs : settings.importantEventHoldMs;
      if (!observedAtMs || observedAtMs > now || now >= observedAtMs + holdMs) continue;
      const target = eventTarget(state, event, scope);
      if (!target?.targetKey) continue;
      const radioKeys = target.radioKey ? [target.radioKey] : [];
      const groupKeys = target.groupKey ? [target.groupKey] : [];
      const eventIdentity = String(event?.id ?? `${observedAtMs}|${index}`);
      ranked.push(Object.freeze({ ...target,
        attentionKey: `event|${kind}|${eventIdentity}|${target.targetKey}|${target.radioKey}`,
        universeKeys: Object.freeze([target.universeKey]),
        radioKeys: Object.freeze(radioKeys),
        groupKeys: Object.freeze(groupKeys),
        callKeys: Object.freeze([]),
        centroidKeys: Object.freeze([...new Set([target.targetKey, ...groupKeys, ...radioKeys])]),
        attentionKind: kind,
        expiresAtMs: observedAtMs + holdMs,
        holdMs,
        score: Object.freeze({ importance, transmittingRadios: 0, callLegs: 0, lastObservedAtMs: observedAtMs })
      }));
    }
  }

  return Object.freeze(ranked.sort((left, right) => compareAttentionHotspots(left, right, currentTargetKey)));
}

function validateOptions(value = {}) {
  const result = { ...ATTENTION_DEFAULTS };
  Object.entries(value).forEach(([key, entry]) => {
    if (!Object.hasOwn(ATTENTION_DEFAULTS, key)) throw new TypeError(`${key} is not an attention option.`);
    result[key] = entry;
  });
  Object.entries(result).forEach(([key, entry]) => {
    if (!Number.isSafeInteger(entry) || entry < 0 || entry > 60_000) {
      throw new TypeError(`${key} must be an integer between 0 and 60000.`);
    }
  });
  return Object.freeze(result);
}

function createAttentionCoordinator(options = {}) {
  const settings = validateOptions(options);
  let generation = null;
  let scopeKey = '';
  let current = null;
  let currentSinceMs = 0;
  let holdUntilMs = 0;
  let suppressedUntilMs = 0;

  function clearShot() {
    current = null;
    currentSinceMs = 0;
    holdUntilMs = 0;
  }

  function reset() {
    generation = null;
    scopeKey = '';
    clearShot();
    suppressedUntilMs = 0;
  }

  function result(status, changed, target = current, reason = '') {
    return Object.freeze({ status, changed, reason, target, holdUntilMs, suppressedUntilMs });
  }

  function shot(target, now, reason) {
    current = target;
    currentSinceMs = now;
    holdUntilMs = target.attentionKind === 'grant' ? now + settings.routineHoldMs :
      Math.max(now, finiteTime(target.expiresAtMs || now + settings.importantEventHoldMs));
    return result('shot', true, current, reason);
  }

  function noteManualInteraction(atMs) {
    const now = finiteTime(atMs);
    suppressedUntilMs = Math.max(suppressedUntilMs, now + settings.manualCooldownMs);
    clearShot();
    return suppressedUntilMs;
  }

  function update(input = {}) {
    const now = finiteTime(input.atMs);
    const scope = normalizeScope(input.scope);
    const nextScopeKey = scopeSignature(scope);
    const nextGeneration = input.generation ?? input.state?.generation ?? 0;
    if (generation !== nextGeneration || scopeKey !== nextScopeKey) {
      clearShot();
      generation = nextGeneration;
      scopeKey = nextScopeKey;
    }

    if (input.manualInteraction === true) noteManualInteraction(now);

    if (input.autoRotate === false || input.reducedMotion === true) {
      clearShot();
      return result('disabled', false, null,
        input.reducedMotion === true ? 'reduced_motion' : 'auto_rotate_off');
    }

    if (now < suppressedUntilMs) return result('manual', false, null, 'manual_camera');

    if (scope.level === 'group') {
      const groupActive = activeCalls(input.state, now).some((call) =>
        call.universeKey === scope.universeKey && call.groupKey === scope.groupKey);
      clearShot();
      return result(groupActive ? 'hold' : 'roam', false, null,
        groupActive ? 'current_group_active' : 'group_scope_idle');
    }

    const ranked = rankAttentionHotspots(input.state, scope, now, current?.targetKey || '', settings);
    const leader = ranked[0] || null;
    const importantLeader = ranked.find((entry) => entry.attentionKind !== 'grant') || null;

    if (current && now < holdUntilMs) {
      const currentImportance = current.score?.importance || 0;
      const shouldPreempt = importantLeader && importantLeader.attentionKey !== current.attentionKey &&
        (current.attentionKind === 'grant' || importantLeader.score.importance > currentImportance);
      if (shouldPreempt) return shot(importantLeader, now, 'important_event');
      return result('hold', false, current,
        current.attentionKind === 'grant' ? 'routine_hold' : 'event_hold');
    }

    clearShot();
    if (!leader) return result('roam', false, null, 'no_active_calls');
    return shot(leader, now, leader.attentionKind === 'grant' ? 'active_calls' : 'important_event');
  }

  function snapshot() {
    return Object.freeze({ generation, scopeKey, current, currentSinceMs, holdUntilMs, suppressedUntilMs });
  }

  return Object.freeze({ update, reset, noteManualInteraction, snapshot });
}

export { ATTENTION_DEFAULTS, createAttentionCoordinator, rankActiveCallHotspots, rankAttentionHotspots };
