const assert = require('node:assert/strict');
const test = require('node:test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');
const schema = import(pathToFileURL(resolve('stats-web/assets/core/preference-schema.js')).href);

test('workspace defaults agree with the exact Java wire contract and existing P25 behavior', async () => {
  const { defaults, validate } = await schema;
  const javaTest = readFileSync('src/test/java/io/github/dsheirer/web/settings/WebUserPreferencesCodecTest.java', 'utf8');
  const wire = JSON.parse(javaTest.match(/DEFAULT_JSON = """\s*([\s\S]*?)""";/)[1]);
  assert.deepEqual(validate(structuredClone(defaults)), wire);
  const { defaultP25EventSettings } = await import(pathToFileURL(resolve('stats-web/assets/features/network-visualizer/settings.js')).href);
  assert.deepEqual(defaults.p25_visualizer.events, Object.fromEntries(Object.entries(defaultP25EventSettings())
    .map(([id, value]) => [id, { highlight: value.highlight, auto_zoom: value.autoZoom }])));
});

test('explicit workspace values round trip and strict validation refuses malformed choices', async () => {
  const { defaults, validate } = await schema;
  const accepted = structuredClone(defaults);
  accepted.live_workspace.details_visibility = 'expanded';
  accepted.live_workspace.details_panel_percent = 65;
  accepted.live_workspace.details_active_tab = 'channel';
  accepted.live_workspace.active_channel_table_id = 'named-channel-table';
  accepted.live_workspace.event_filters = { excluded_leaf_keys: ['CALL:grant'], excluded_timeslots: ['1'],
    excluded_validity: ['invalid'], search: 'Dispatch' };
  accepted.p25_visualizer.events.emergency.highlight = false;
  accepted.tuner.fft_auto_range_on_retune = false;
  assert.deepEqual(validate(accepted), accepted);
  const edits = [
    value => { delete value.live_workspace; }, value => { value.live_workspace.active_channel_table_id = 1; },
    value => { value.live_workspace.details_panel_percent = 14; }, value => { value.live_workspace.details_panel_percent = 25.5; },
    value => { value.live_workspace.details_visibility = 'auto'; }, value => { value.live_workspace.picker_collapsed = 'true'; },
    value => { value.live_workspace.event_filters.excluded_leaf_keys = ['x', 'x']; },
    value => { value.live_workspace.event_filters.excluded_leaf_keys = Array.from({ length: 257 }, (_, i) => `key-${i}`); },
    value => { value.live_workspace.event_filters.excluded_timeslots = ['x'.repeat(65)]; },
    value => { value.live_workspace.message_filters.excluded_validity = ['other']; },
    value => { value.live_workspace.message_filters.search = 'x'.repeat(257); },
    value => { delete value.p25_visualizer.events.call; }, value => { value.p25_visualizer.events.extra = {}; },
    value => { value.p25_visualizer.events.call.auto_zoom = 1; }, value => { value.tuner.fft_auto_range_on_retune = null; }
  ];
  for (const edit of edits) { const value = structuredClone(defaults); edit(value); assert.throws(() => validate(value)); }
});
