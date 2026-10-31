const KEYS = Object.freeze(['fft_auto_range_on_retune', 'waterfall_auto_range_on_retune']);

// Storage and identity belong to the shared personal preference controller.
export function createSpectrumDisplayPreferences({ read, update }) {
  if (typeof read !== 'function' || typeof update !== 'function') {
    throw new TypeError('Spectrum display preferences require the personal preference service.');
  }
  const requireKey = key => {
    if (!KEYS.includes(key)) throw new TypeError('Unknown spectrum display preference.');
  };
  const requireBoolean = value => {
    if (typeof value !== 'boolean') throw new TypeError('Spectrum display preferences must be boolean.');
  };
  return {
    autoRangeEnabled: () => KEYS.every(key => read()[key] !== false),
    setAutoRangeEnabled(enabled) {
      requireBoolean(enabled);
      return update(preferences => { for (const key of KEYS) preferences.tuner[key] = enabled; });
    },
    get(key) { requireKey(key); return read()[key] !== false; },
    set(key, enabled) {
      requireKey(key); requireBoolean(enabled);
      return update(preferences => { preferences.tuner[key] = enabled; });
    }
  };
}
