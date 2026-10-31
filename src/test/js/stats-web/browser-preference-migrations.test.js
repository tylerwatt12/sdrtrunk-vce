'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const resource = path.resolve(process.argv[2] || 'stats-web/assets/core');
const core = path.extname(resource) === '.js' ? path.dirname(resource) : resource;
const modules = Promise.all(['browser-preferences', 'preference-schema']
  .map(name => import(pathToFileURL(path.join(core, `${name}.js`)).href)));
const clone = value => JSON.parse(JSON.stringify(value));

// Complete historical input, independent of additions to current defaults.
function version14() {
  return {
    version: 14, appearance: { theme: 'dark', hue: 210 },
    page_titles: { prepend_playing_call: false }, guides: { automatic_page_guides: true },
    playback: { volume: 0.35, selected_scan_list_ids: [2, 5], target_grouping: false,
      target_burst_limit: 7, call_display_hangtime_seconds: 9, recording_click_mode: 'once' },
    scanner: { detail_mode: 'advanced' },
    presentation: { source_name_display: 'both', show_encryption_details: false,
      show_control_decode_quality: true, show_voice_decode_quality: false,
      decode_quality_display_mode: 'detailed', live_detail_row_limit: 150,
      show_only_active_trunked_channels: false, live_channel_sort: 'frequency', live_row_density: 'dense',
      retain_last_call_on_idle_rows: true, clear_voice_quality_when_idle: false },
    tuner: { floor_db: -130, ceiling_db: -15, waterfall_speed: 0.5, snap_frequency: false,
      smooth_fft: true, highlight_waterfall_channels: true, show_idle_channels: true, profile: 'high-detail' },
    health_alerts: { disabled_codes: [] },
    tables: { sample: { schema: ['name'], column_order: ['name'], column_widths: { name: 180 },
      hidden_columns: [], collapsed_groups: [] } }
  };
}

function storage(record, key) {
  const records = new Map([[key, record]]);
  return { records, getItem: name => records.get(name) ?? null,
    setItem: (name, value) => records.set(name, value) };
}

test('the executable adjacent guest migration preserves historical choices and owns independent additions', async () => {
  const [browser] = await modules;
  const step = browser.GUEST_PREFERENCE_MIGRATIONS.find(migration => migration.from === 14);
  assert.equal(step.to, 15);
  assert.equal(typeof step.convert, 'function');
  assert.ok(Object.isFrozen(browser.GUEST_PREFERENCE_MIGRATIONS));
  assert.ok(Object.isFrozen(step));
  const prior = version14(), original = clone(prior);
  const migrated = step.convert(prior);
  assert.deepEqual(prior, original, 'A converter must not alter its admitted source');
  const preserved = clone(migrated);
  preserved.version = 14;
  delete preserved.live_workspace; delete preserved.p25_visualizer;
  delete preserved.tuner.fft_auto_range_on_retune; delete preserved.tuner.waterfall_auto_range_on_retune;
  assert.deepEqual(preserved, original);
  assert.equal(migrated.version, 15);
  assert.equal(migrated.tuner.fft_auto_range_on_retune, true);
  assert.equal(migrated.tuner.waterfall_auto_range_on_retune, true);
  assert.deepEqual(migrated.live_workspace, {
    details_panel_percent: 25, details_visibility: 'automatic', details_active_tab: 'events',
    picker_collapsed: false, active_channel_table_id: null,
    event_filters: { excluded_leaf_keys: [], excluded_timeslots: [], excluded_validity: [], search: '' },
    message_filters: { excluded_leaf_keys: [], excluded_timeslots: [], excluded_validity: [], search: '' }
  });
  const categories = ['emergency', 'movement', 'denial', 'patch', 'busy', 'queued', 'check', 'page', 'logout', 'call'];
  assert.deepEqual(Object.keys(migrated.p25_visualizer.events), categories);
  for (const id of categories) {
    assert.equal(migrated.p25_visualizer.events[id].highlight, true);
    assert.equal(migrated.p25_visualizer.events[id].auto_zoom, ['emergency', 'movement', 'patch', 'busy', 'queued'].includes(id));
  }
  migrated.live_workspace.event_filters.excluded_leaf_keys.push('sample');
  migrated.p25_visualizer.events.emergency.highlight = false;
  const next = step.convert(prior);
  assert.deepEqual(next.live_workspace.event_filters.excluded_leaf_keys, []);
  assert.equal(next.p25_visualizer.events.emergency.highlight, true);
});

test('stored migration dispatch uses frozen step defaults rather than later default customizations', async () => {
  const [browser, schema] = await modules;
  const currentDefaults = clone(schema.defaults);
  currentDefaults.tuner.fft_auto_range_on_retune = false;
  currentDefaults.tuner.waterfall_auto_range_on_retune = false;
  currentDefaults.live_workspace.details_panel_percent = 50;
  currentDefaults.p25_visualizer.events.emergency.highlight = false;
  const history = { version: 1, dismissed: { welcome: 1, recordings: 6 } };
  const prior = version14();
  const store = storage(JSON.stringify({ version: 1, preferences: prior, tour_history: history }), browser.GUEST_PROFILE_STORAGE_KEY);
  const adapter = browser.createBrowserPreferences({ defaults: currentDefaults, validate: schema.validate, storage: store });
  const restored = adapter.load();
  assert.equal(restored.persistence, 'browser');
  const step = browser.GUEST_PREFERENCE_MIGRATIONS.find(migration => migration.from === prior.version);
  assert.deepEqual(restored.preferences, schema.validate(step.convert(prior)));
  assert.deepEqual(adapter.readTourHistory(), history);
  const saved = JSON.parse(store.records.get(browser.GUEST_PROFILE_STORAGE_KEY));
  assert.equal(saved.version, 1, 'The envelope does not change');
  assert.equal(saved.preferences.version, 15);
  assert.deepEqual(saved.tour_history, history);
});

test('malformed historical fields and values remain protected throughout attempted guest changes', async () => {
  const [browser, schema] = await modules;
  const malformed = [
    profile => { profile.future_section = {}; },
    profile => { delete profile.tuner.profile; },
    profile => { profile.playback.future_setting = true; },
    profile => { profile.playback.volume = '0.35'; },
    profile => { profile.playback.recording_click_mode = 'loop'; },
    profile => { profile.tuner.fft_auto_range_on_retune = false; },
    profile => { profile.live_workspace = {}; },
    profile => { profile.appearance.theme = 'automatic'; }
  ];
  for (const damage of malformed) {
    const prior = version14(); damage(prior);
    const original = JSON.stringify({ version: 1, preferences: prior });
    const store = storage(original, browser.GUEST_PROFILE_STORAGE_KEY);
    const adapter = browser.createBrowserPreferences({ defaults: schema.defaults, validate: schema.validate, storage: store });
    assert.equal(adapter.load().persistenceReason, 'unsupported');
    assert.equal(adapter.update(profile => { profile.playback.volume = 0.6; }).persistence, 'memory');
    adapter.saveTourHistory({ live: 3 });
    assert.equal(store.records.get(browser.GUEST_PROFILE_STORAGE_KEY), original);
  }
});

test('unregistered and newer guest versions never advance or overwrite their original records', async () => {
  const [browser, schema] = await modules;
  for (const record of [
    { version: 2, preferences: version14() },
    { version: 1, preferences: { ...version14(), version: 13 } },
    { version: 1, preferences: { ...schema.defaults, version: 16 } },
    { version: 1, preferences: { ...version14(), version: '14' } }
  ]) {
    const original = JSON.stringify(record);
    const store = storage(original, browser.GUEST_PROFILE_STORAGE_KEY);
    const adapter = browser.createBrowserPreferences({ defaults: schema.defaults, validate: schema.validate, storage: store });
    assert.equal(adapter.load().persistenceReason, 'unsupported');
    adapter.update(profile => { profile.appearance.theme = 'dark'; });
    assert.equal(store.records.get(browser.GUEST_PROFILE_STORAGE_KEY), original);
  }
});
