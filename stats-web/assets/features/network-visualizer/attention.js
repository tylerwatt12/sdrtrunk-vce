'use strict';

const ATTENTION_DEFAULTS = Object.freeze({
  candidateLeadMs: 2_000,
  minimumDwellMs: 10_000,
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

/** Ranks recent semantic events ahead of confirmed Grant hotspots without changing either underlying state model. */
function rankAttentionHotspots(state, scopeValue, atMs = 0, currentTargetKey = '', options = {}) {
  const scope = normalizeScope(scopeValue);
  const now = finiteTime(atMs);
  const settings = { ...ATTENTION_DEFAULTS, ...options };
  const ranked = rankActiveCallHotspots(state, scope, now, currentTargetKey).map((grant) => {
    const active = grant.callKeys.length > 0;
    return Object.freeze({ ...grant,
      attentionKind: active ? 'grant' : 'idle',
      score: Object.freeze({ ...grant.score, importance: active ? 1 : 0 })
    });
  });

  const events = Array.isArray(state?.semanticEvents) ? state.semanticEvents : [];
  for (let index = events.length - 1; index >= 0; index -= 1) {
    const event = events[index];
    const kind = String(event?.type || event?.kind || '');
    const importance = IMPORTANT_EVENT_PRIORITY[kind] || 0;
    if (!importance) continue;
    const observedAtMs = finiteTime(event?.observedAtMs);
    const holdMs = kind === 'signal_emergency' ? settings.emergencyEventHoldMs : settings.importantEventHoldMs;
    if (!observedAtMs || observedAtMs > now || now - observedAtMs > holdMs) continue;
    const target = eventTarget(state, event, scope);
    if (!target?.targetKey) continue;
    const radioKeys = target.radioKey ? [target.radioKey] : [];
    const groupKeys = target.groupKey ? [target.groupKey] : [];
    ranked.push(Object.freeze({ ...target,
      radioKeys: Object.freeze(radioKeys), groupKeys: Object.freeze(groupKeys), callKeys: Object.freeze([]),
      centroidKeys: Object.freeze([...new Set([...groupKeys, ...radioKeys, target.targetKey])]),
      attentionKind: kind,
      score: Object.freeze({ importance, transmittingRadios: 0, callLegs: 0, lastObservedAtMs: observedAtMs })
    }));
  }

  return Object.freeze(ranked.sort((left, right) => compareAttentionHotspots(left, right, currentTargetKey)));
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

    const ranked = rankAttentionHotspots(input.state, scope, now, current?.targetKey || '', settings);
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
    const higherPriority = (candidate?.score?.importance || 0) > (current?.score?.importance || 0);
    const dwelledLongEnough = higherPriority || !current || now - currentSinceMs >= settings.minimumDwellMs;
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

export { ATTENTION_DEFAULTS, createAttentionCoordinator, rankActiveCallHotspots, rankAttentionHotspots };
