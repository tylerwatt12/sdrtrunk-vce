import { guestDefaults } from './preference-registry.js?v=1';

export const GUEST_PROFILE_STORAGE_KEY = 'sdrtrunk-vce-guest-preferences-v1';
export const CURRENT_GUEST_PREFERENCE_VERSION = 15;
export const GUEST_PREFERENCE_MIGRATIONS = Object.freeze([
  Object.freeze({ from: 14, to: 15, convert: migrateVersion14To15 })
]);
const migrationsByVersion = new Map(GUEST_PREFERENCE_MIGRATIONS.map(step => [step.from, step]));
const LEGACY_TABLES_KEY = 'sdrtrunk-vce-anonymous-table-layouts';
const LEGACY_SPECTRUM_PREFIX = 'sdrtrunk-vce-spectrum-display-v1:';
const MAXIMUM_RECORD_LENGTH = 262144;
const copy = value => JSON.parse(JSON.stringify(value));
const plain = value => Boolean(value) && typeof value === 'object' && !Array.isArray(value);
const emptyHistory = () => ({ version: 1, dismissed: {} });

// Frozen field/default contract for this adjacent step. Later schema defaults
// must not add fields to, or change the meaning of, a historical conversion.
const version14Fields = Object.freeze({
  root: Object.freeze(['version', 'appearance', 'page_titles', 'guides', 'playback', 'scanner',
    'presentation', 'tuner', 'health_alerts', 'tables']),
  appearance: Object.freeze(['theme', 'hue']),
  page_titles: Object.freeze(['prepend_playing_call']),
  guides: Object.freeze(['automatic_page_guides']),
  playback: Object.freeze(['volume', 'selected_scan_list_ids', 'target_grouping', 'target_burst_limit',
    'call_display_hangtime_seconds', 'recording_click_mode']),
  scanner: Object.freeze(['detail_mode']),
  presentation: Object.freeze(['source_name_display', 'show_encryption_details', 'show_control_decode_quality',
    'show_voice_decode_quality', 'decode_quality_display_mode', 'live_detail_row_limit',
    'show_only_active_trunked_channels', 'live_channel_sort', 'live_row_density', 'retain_last_call_on_idle_rows',
    'clear_voice_quality_when_idle']),
  tuner: Object.freeze(['floor_db', 'ceiling_db', 'waterfall_speed', 'snap_frequency', 'smooth_fft',
    'highlight_waterfall_channels', 'show_idle_channels', 'profile']),
  health_alerts: Object.freeze(['disabled_codes'])
});

function exactFields(value, keys) {
  return plain(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
}

function migrateVersion14To15(value) {
  if (value?.version !== 14 || !exactFields(value, version14Fields.root) || !plain(value.tables) ||
      Object.entries(version14Fields).some(([section, keys]) => section !== 'root' && !exactFields(value[section], keys))) {
    throw new TypeError('The version-14 browser preference fields are invalid.');
  }
  const filters = () => ({ excluded_leaf_keys: [], excluded_timeslots: [], excluded_validity: [], search: '' });
  const events = Object.fromEntries(['emergency', 'movement', 'denial', 'patch', 'busy', 'queued', 'check', 'page', 'logout', 'call']
    .map(id => [id, { highlight: true, auto_zoom: ['emergency', 'movement', 'patch', 'busy', 'queued'].includes(id) }]));
  const prior = copy(value);
  return { ...prior, version: 15,
    tuner: { ...prior.tuner, fft_auto_range_on_retune: true, waterfall_auto_range_on_retune: true },
    live_workspace: { details_panel_percent: 25, details_visibility: 'automatic', details_active_tab: 'events',
      picker_collapsed: false, active_channel_table_id: null, event_filters: filters(), message_filters: filters() },
    p25_visualizer: { events } };
}

function changedValues(before, after, path = []) {
  if (JSON.stringify(before) === JSON.stringify(after)) return [];
  if (plain(before) && plain(after)) {
    return [...new Set([...Object.keys(before), ...Object.keys(after)])].flatMap(key => {
      if (!Object.hasOwn(after, key)) return [{ path: [...path, key], remove: true }];
      if (!Object.hasOwn(before, key)) return [{ path: [...path, key], value: copy(after[key]) }];
      return changedValues(before[key], after[key], [...path, key]);
    });
  }
  return [{ path, value: copy(after) }];
}

function applyChanges(value, changes) {
  const result = copy(value);
  for (const change of changes) {
    const keys = change.path.slice();
    const key = keys.pop();
    let owner = result;
    for (const part of keys) {
      if (!Object.hasOwn(owner, part) || !plain(owner[part])) {
        Object.defineProperty(owner, part, { value: {}, writable: true, enumerable: true, configurable: true });
      }
      owner = owner[part];
    }
    if (change.remove) delete owner[key];
    else Object.defineProperty(owner, key, { value: copy(change.value), writable: true, enumerable: true, configurable: true });
  }
  return result;
}

function browserStorage() {
  try { return globalThis.localStorage || null; } catch (_) { return null; }
}

function parse(value) {
  return typeof value === 'string' && value.length <= MAXIMUM_RECORD_LENGTH ? JSON.parse(value) : null;
}

function historyDocument(value) {
  if (!plain(value) || value.version !== 1 || !plain(value.dismissed) || Object.keys(value).length !== 2 ||
      Object.keys(value.dismissed).length > 64 || Object.entries(value.dismissed).some(([id, version]) =>
        !/^[a-z][a-z0-9_-]{0,63}$/.test(id) || !Number.isInteger(version) || version < 1 || version > 1000000)) {
    throw new TypeError('The browser tour history is invalid.');
  }
  return copy(value);
}

/** One browser guest profile, kept separate from every signed-in account. */
export function createBrowserPreferences({ defaults, validate, storage } = {}) {
  if (typeof validate !== 'function') throw new TypeError('A preference validator is required.');
  if (defaults?.version !== CURRENT_GUEST_PREFERENCE_VERSION) {
    throw new TypeError('The browser preference migration contract must match the current profile version.');
  }
  const store = storage === undefined ? browserStorage() : storage;
  let preferences = validate(guestDefaults(defaults));
  let confirmedPreferences = copy(preferences);
  let replacementPending = false;
  let protectedRecord = false;
  let readUnavailable = false;
  let resetAuthorized = false;
  let history = emptyHistory();
  const importedLegacyKeys = new Set();
  let initialized = false;
  let persistence = store ? 'browser' : 'memory';
  let persistenceReason = store ? null : 'unavailable';
  const snapshot = () => ({ preferences: copy(preferences), persistence, persistenceReason });
  const readValue = key => {
    try { return parse(store?.getItem(key)); }
    catch (_) { return null; }
  };
  function write() {
    try {
      if (!store || readUnavailable || protectedRecord && !resetAuthorized) {
        throw new Error('Browser storage cannot be replaced.');
      }
      const serialized = JSON.stringify({ version: 1, preferences, tour_history: history });
      if (serialized.length > MAXIMUM_RECORD_LENGTH) throw new Error('The browser preference record is too large.');
      store.setItem(GUEST_PROFILE_STORAGE_KEY, serialized);
      persistence = 'browser';
      persistenceReason = null;
      confirmedPreferences = copy(preferences);
      replacementPending = false;
      protectedRecord = false;
      resetAuthorized = false;
      for (const key of importedLegacyKeys) {
        try { store.removeItem?.(key); importedLegacyKeys.delete(key); } catch (_) { /* Retry cleanup after the next saved change. */ }
      }
    } catch (_) {
      persistence = 'memory';
      persistenceReason = protectedRecord && !resetAuthorized ? 'unsupported' : 'unavailable';
      // Retain this page's latest choices; never reload stale browser values.
    }
    return snapshot();
  }
  function upgradeGuestDocument(value) {
    if (!plain(value) || !Number.isInteger(value.version) || value.version > CURRENT_GUEST_PREFERENCE_VERSION) return null;
    let converted = copy(value);
    while (converted.version < CURRENT_GUEST_PREFERENCE_VERSION) {
      const step = migrationsByVersion.get(converted.version);
      if (!step || step.to !== step.from + 1) return null;
      converted = step.convert(converted);
      if (!plain(converted) || converted.version !== step.to) return null;
    }
    return validate(converted);
  }
  function readStoredProfile() {
    let raw;
    try {
      if (!store) return { state: 'unavailable' };
      raw = store.getItem(GUEST_PROFILE_STORAGE_KEY);
    } catch (_) { return { state: 'unavailable' }; }
    if (raw === null || raw === undefined) return { state: 'missing' };
    try {
      const saved = parse(raw);
      if (!plain(saved) || saved.version !== 1 || !plain(saved.preferences) ||
          Object.keys(saved).some(key => !['version', 'preferences', 'tour_history'].includes(key))) return { state: 'unsupported' };
      const restored = upgradeGuestDocument(saved.preferences);
      if (!restored) return { state: 'unsupported' };
      return { state: 'supported', preferences: restored,
        history: saved.tour_history === undefined ? emptyHistory() : historyDocument(saved.tour_history) };
    } catch (_) { return { state: 'unsupported' }; }
  }
  function retainUnsupported() {
    if (!resetAuthorized) {
      protectedRecord = true;
      persistence = 'memory';
      persistenceReason = 'unsupported';
    }
  }
  function readLegacyGuestSpectrum() {
    const value = readValue(LEGACY_SPECTRUM_PREFIX + 'anonymous');
    if (!plain(value) || value.version !== 1 || Object.keys(value).length !== 3 ||
        typeof value.fft_auto_range_on_retune !== 'boolean' ||
        typeof value.waterfall_auto_range_on_retune !== 'boolean') return null;
    return { fft_auto_range_on_retune: value.fft_auto_range_on_retune,
      waterfall_auto_range_on_retune: value.waterfall_auto_range_on_retune };
  }
  function load() {
    if (initialized) { rebase(); return snapshot(); }
    initialized = true;
    const saved = readStoredProfile();
    if (saved.state === 'unsupported') { retainUnsupported(); return snapshot(); }
    if (saved.state === 'unavailable') { readUnavailable = true; return write(); }
    if (saved.state === 'supported') {
      preferences = saved.preferences;
      confirmedPreferences = copy(preferences);
      history = saved.history;
      return write();
    }
    const tables = readValue(LEGACY_TABLES_KEY);
    if (plain(tables)) {
      const upgraded = Object.fromEntries(Object.entries(tables).map(([id, layout]) => [id,
        plain(layout) && !Array.isArray(layout.collapsed_groups) ? { ...layout, collapsed_groups: [] } : layout]));
      try {
        preferences = validate({ ...preferences, tables: upgraded });
        importedLegacyKeys.add(LEGACY_TABLES_KEY);
      } catch (_) { /* Keep valid defaults. */ }
    }
    const spectrum = readLegacyGuestSpectrum();
    if (spectrum) {
      try {
        preferences = validate({ ...preferences, tuner: { ...preferences.tuner, ...spectrum } });
        importedLegacyKeys.add(LEGACY_SPECTRUM_PREFIX + 'anonymous');
      } catch (_) { }
    }
    // Origin-shared Live state and username-only P25 records remain untouched:
    // neither can establish which account or guest originally chose them.
    return write();
  }
  function mergeHistory(first, second) {
    const dismissed = { ...first.dismissed };
    for (const [id, version] of Object.entries(second.dismissed)) {
      dismissed[id] = Math.max(Object.hasOwn(dismissed, id) ? dismissed[id] : 0, version);
    }
    return historyDocument({ version: 1, dismissed });
  }
  function rebase() {
    const saved = readStoredProfile();
    if (saved.state === 'unsupported') { retainUnsupported(); return; }
    if (saved.state === 'unavailable') {
      readUnavailable = true; persistence = 'memory'; persistenceReason = 'unavailable'; return;
    }
    readUnavailable = false;
    protectedRecord = false;
    if (saved.state === 'missing') return;
    try {
      const latest = saved.preferences;
      const pending = changedValues(confirmedPreferences, preferences);
      preferences = replacementPending ? preferences : validate(applyChanges(latest, pending));
      confirmedPreferences = copy(latest);
      history = mergeHistory(saved.history, history);
      if (!replacementPending && pending.length === 0) {
        persistence = 'browser'; persistenceReason = null;
      }
    } catch (_) { /* Keep this page's valid and possibly unsaved profile. */ }
  }
  return {
    load,
    update(mutator, { reset = false } = {}) {
      if (!initialized) load();
      else rebase();
      const draft = copy(preferences);
      const returned = mutator(draft);
      preferences = validate(copy(returned === undefined ? draft : returned));
      if (returned !== undefined) replacementPending = true;
      if (reset) { resetAuthorized = true; protectedRecord = false; readUnavailable = false; }
      return write();
    },
    readTourHistory() { if (!initialized) load(); else rebase(); return copy(history); },
    saveTourHistory(dismissed) {
      if (!initialized) load();
      else rebase();
      const next = historyDocument({ version: 1, dismissed });
      history = mergeHistory(history, next);
      write();
      return copy(history);
    },
    snapshot
  };
}
