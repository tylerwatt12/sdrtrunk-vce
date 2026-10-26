'use strict';

function finite(value, fallback) {
  return Number.isFinite(Number(value)) ? Number(value) : fallback;
}

// Camera attention never gates live state. The newest selected action replaces the current view.
function createP25CameraCoordinator(options = {}) {
  const timing = Object.freeze({
    transitionMs: Math.max(0, finite(options.transitionMs, 600)),
    focusMs: Math.max(0, finite(options.focusMs, 2_200)),
    returnMs: Math.max(0, finite(options.returnMs, 600))
  });
  let mode = options.mode === 'manual' ? 'manual' : 'auto';
  let phase = mode === 'auto' ? 'roam' : 'disabled';
  let current = null, returnAtMs = 0, roamAtMs = 0, dirty = false;
  const seen = new Map();
  const snapshot = (changed = false) => Object.freeze({ mode, phase, current, changed,
    returnAtMs, roamAtMs, timing });

  function reset() {
    phase = mode === 'auto' ? 'roam' : 'disabled';
    current = null;
    returnAtMs = roamAtMs = 0;
    dirty = true;
    return snapshot(true);
  }

  function setMode(value) {
    mode = value === 'manual' ? 'manual' : 'auto';
    return reset();
  }

  function consider(event, atMs = Date.now()) {
    const now = finite(atMs, Date.now());
    const key = String(event?.key || '');
    const observedAtMs = finite(event?.observedAtMs, now);
    const focusKeys = Array.isArray(event?.focusKeys) ? event.focusKeys.filter(Boolean).map(String) : [];
    if (mode !== 'auto' || !key || !focusKeys.length ||
        (seen.has(key) && seen.get(key) >= observedAtMs)) {
      return Object.freeze({ accepted: false, interrupted: false, state: snapshot() });
    }
    seen.set(key, observedAtMs);
    if (seen.size > 512) seen.delete(seen.keys().next().value);
    const interrupted = phase !== 'roam';
    current = Object.freeze({ ...event, key, observedAtMs, focusKeys });
    phase = 'focus';
    returnAtMs = now + timing.transitionMs + timing.focusMs;
    roamAtMs = 0;
    dirty = false;
    return Object.freeze({ accepted: true, interrupted, state: snapshot(true) });
  }

  function update(atMs = Date.now()) {
    const now = finite(atMs, Date.now());
    let changed = dirty;
    dirty = false;
    if (phase === 'focus' && now >= returnAtMs) {
      phase = 'return';
      // Give the return tween its full duration even after a suspended animation frame.
      roamAtMs = now + timing.returnMs;
      changed = true;
    } else if (phase === 'return' && now >= roamAtMs) {
      phase = 'roam';
      current = null;
      changed = true;
    }
    return snapshot(changed);
  }

  return Object.freeze({ consider, update, setMode, cancel: reset, reset, snapshot });
}

export { createP25CameraCoordinator };
