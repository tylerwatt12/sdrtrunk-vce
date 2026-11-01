// Recording completion is bounded tab-session history, separate from saved preferences.
export const RECORDING_PLAY_HISTORY_KEY = 'sdrtrunk-vce-recording-play-history-v1';
const MAXIMUM_CALLS = 500;
const MAXIMUM_PROFILES = 8;
const validKey = key => typeof key === 'string' && key.length > 0 && key.length <= 256;

function sessionStorage() {
  try { return globalThis.sessionStorage || null; } catch (_) { return null; }
}

export function createRecordingPlayHistory({ storage = sessionStorage() } = {}) {
  const profiles = new Map();
  let writable = true, profile = 'guest', generation = 0;
  try {
    const raw = storage?.getItem(RECORDING_PLAY_HISTORY_KEY);
    if (raw) {
      const saved = raw.length <= 1_100_000 ? JSON.parse(raw) : null;
      if (saved?.version !== 1 || !Array.isArray(saved.profiles) || saved.profiles.length > MAXIMUM_PROFILES ||
          saved.profiles.some(entry => !Array.isArray(entry) || entry.length !== 2 ||
            typeof entry[0] !== 'string' || entry[0].length > 1024 || !Array.isArray(entry[1]) ||
            entry[1].length > MAXIMUM_CALLS || !entry[1].every(validKey))) {
        writable = false;
      } else for (const [owner, calls] of saved.profiles) profiles.set(owner, new Set(calls));
    }
  } catch (_) { writable = false; }
  const current = () => {
    if (!profiles.has(profile)) {
      profiles.set(profile, new Set());
      while (profiles.size > MAXIMUM_PROFILES) profiles.delete(profiles.keys().next().value);
    }
    return profiles.get(profile);
  };
  const save = () => {
    try {
      if (writable) storage?.setItem(RECORDING_PLAY_HISTORY_KEY, JSON.stringify({ version: 1,
        profiles: [...profiles].map(([owner, calls]) => [owner, [...calls]]) }));
    } catch (_) { /* Optional session history never interrupts playback. */ }
  };
  return {
    activate(identity) {
      profile = identity == null ? 'guest' : `account:${identity}`;
      generation++;
      const calls = current();
      profiles.delete(profile); profiles.set(profile, calls);
    },
    has: key => current().has(key),
    onCompletion(key) {
      const ownerGeneration = generation;
      return () => {
        if (ownerGeneration !== generation || !validKey(key)) return;
        const calls = current();
        calls.delete(key); calls.add(key);
        while (calls.size > MAXIMUM_CALLS) calls.delete(calls.values().next().value);
        save();
      };
    }
  };
}
