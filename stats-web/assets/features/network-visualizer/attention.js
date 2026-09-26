'use strict';

const ATTENTION_DEFAULTS = Object.freeze({
  candidateLeadMs: 2_000,
  minimumDwellMs: 10_000,
  manualCooldownMs: 10_000
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
 * Ranks exact active-call hotspots for the current hierarchy level. Pending grants and entity afterglow are
 * intentionally invisible to this function because neither is an active call leg.
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

function validateOptions(value = {}) {
  const result = { ...ATTENTION_DEFAULTS, ...value };
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
  let candidate = null;
  let candidateSinceMs = 0;
  let suppressedUntilMs = 0;

  function reset() {
    current = null;
    currentSinceMs = 0;
    candidate = null;
    candidateSinceMs = 0;
    suppressedUntilMs = 0;
  }

  function result(status, changed, target = current, reason = '') {
    return Object.freeze({
      status,
      changed,
      reason,
      target,
      candidate,
      suppressedUntilMs
    });
  }

  function update(input = {}) {
    const now = finiteTime(input.atMs);
    const scope = normalizeScope(input.scope);
    const nextScopeKey = scopeSignature(scope);
    const nextGeneration = input.generation ?? input.state?.generation ?? 0;
    if (generation !== nextGeneration || scopeKey !== nextScopeKey) {
      reset();
      generation = nextGeneration;
      scopeKey = nextScopeKey;
    }

    if (input.autoRotate === false || input.reducedMotion === true) {
      reset();
      return result('disabled', false, null,
        input.reducedMotion === true ? 'reduced_motion' : 'auto_rotate_off');
    }

    if (input.manualInteraction === true) {
      suppressedUntilMs = Math.max(suppressedUntilMs, now + settings.manualCooldownMs);
      candidate = null;
      candidateSinceMs = 0;
    }

    const ranked = rankActiveCallHotspots(input.state, scope, now, current?.targetKey || '');
    if (scope.level === 'group') {
      current = ranked[0] || null;
      currentSinceMs ||= now;
      candidate = null;
      candidateSinceMs = 0;
      return now < suppressedUntilMs ? result('suppressed', false, current, 'manual_camera') :
        result(current ? 'hold' : 'idle', false, current, current ? 'current_group' : 'no_group');
    }

    if (now < suppressedUntilMs) return result('suppressed', false, current, 'manual_camera');

    const leader = ranked[0] || null;
    const refreshedCurrent = current ? ranked.find((entry) => entry.targetKey === current.targetKey) : null;
    if (current && refreshedCurrent) current = refreshedCurrent;

    if (!leader) {
      candidate = null;
      candidateSinceMs = 0;
      return result(current ? 'hold' : 'idle', false, current, 'no_active_calls');
    }

    if (current?.targetKey === leader.targetKey) {
      candidate = null;
      candidateSinceMs = 0;
      return result('hold', false, current, 'current_leads');
    }

    if (candidate?.targetKey !== leader.targetKey) {
      candidate = leader;
      candidateSinceMs = now;
    } else {
      candidate = leader;
    }

    const ledLongEnough = now - candidateSinceMs >= settings.candidateLeadMs;
    const dwelledLongEnough = !current || now - currentSinceMs >= settings.minimumDwellMs;
    if (!ledLongEnough || !dwelledLongEnough) {
      return result(current ? 'hold' : 'candidate', false, current,
        !ledLongEnough ? 'candidate_delay' : 'minimum_dwell');
    }

    current = candidate;
    currentSinceMs = now;
    candidate = null;
    candidateSinceMs = 0;
    return result('focus', true, current, 'hotspot_changed');
  }

  function noteManualInteraction(atMs) {
    const now = finiteTime(atMs);
    suppressedUntilMs = Math.max(suppressedUntilMs, now + settings.manualCooldownMs);
    candidate = null;
    candidateSinceMs = 0;
    return suppressedUntilMs;
  }

  function snapshot() {
    return Object.freeze({ generation, scopeKey, current, currentSinceMs, candidate,
      candidateSinceMs, suppressedUntilMs });
  }

  return Object.freeze({ update, reset, noteManualInteraction, snapshot });
}

export { ATTENTION_DEFAULTS, createAttentionCoordinator, rankActiveCallHotspots };
