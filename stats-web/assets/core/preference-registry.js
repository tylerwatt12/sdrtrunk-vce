// Personal choices share one typed profile. These records describe ownership and
// presentation; preference-schema owns the saved values and their validation.
// To add a choice, update that schema and its server/guest migrations, declare
// the field here, and use user-preferences Controller.update from its editor.
// Features read the active snapshot and never choose their own persistence.
export const preferenceGroups = Object.freeze([
  { id: 'appearance', label: 'Appearance', icon: 'icon-display', capability: null },
  { id: 'names', label: 'Source names', icon: 'icon-identities', capability: null },
  { id: 'status', label: 'Status icon', icon: 'icon-health', capability: 'receiver-health' },
  { id: 'listening', label: 'Listening settings', icon: 'icon-playback-controls', capability: null },
  { id: 'guides', label: 'Guides', icon: 'icon-help-circle', capability: null },
  { id: 'scanner', label: 'Scanner', icon: 'icon-scanner', view: 'scanner', capability: 'call-audio',
    location: 'Scanner → Detail level' },
  { id: 'live_workspace', label: 'Live', icon: 'icon-live', view: 'live', capability: 'live',
    location: 'Live → Presentation' },
  { id: 'p25_visualizer', label: 'P25 Visualizer', icon: 'icon-network-visualizer', view: 'network-visualizer',
    capability: 'radio', location: 'P25 Visualizer → Events' },
  { id: 'tuner', label: 'Tuner spectrum', icon: 'icon-spectrum', view: 'tuners', capability: 'admin-tuners',
    location: 'Tuners → Spectrum' },
  { id: 'tables', label: 'Table layouts', icon: 'icon-columns', capability: null,
    location: 'Any table → Columns' }
].map(group => Object.freeze(group)));

const fields = (group, paths, capability = null) => paths.map(path => ({
  path, group, owner: 'personal', lifetime: 'profile', guest: true, capability, reset: true
}));
const eventCategories = ['emergency', 'movement', 'denial', 'patch', 'busy', 'queued', 'check', 'page', 'logout', 'call'];

export const preferenceRegistry = Object.freeze([
  ...fields('appearance', ['appearance.theme', 'appearance.hue']),
  ...fields('names', ['presentation.source_name_display']),
  ...fields('listening', ['page_titles.prepend_playing_call', 'playback.volume', 'playback.selected_scan_list_ids',
    'playback.target_grouping', 'playback.target_burst_limit', 'playback.call_display_hangtime_seconds',
    'playback.recording_click_mode']),
  { ...fields('guides', ['guides.automatic_page_guides'])[0], guestDefault: false },
  ...fields('scanner', ['scanner.detail_mode'], 'call-audio'),
  ...fields('live_workspace', ['presentation.show_encryption_details', 'presentation.show_control_decode_quality',
    'presentation.show_voice_decode_quality', 'presentation.decode_quality_display_mode', 'presentation.live_detail_row_limit',
    'presentation.show_only_active_trunked_channels', 'presentation.live_channel_sort', 'presentation.live_row_density',
    'presentation.retain_last_call_on_idle_rows', 'presentation.clear_voice_quality_when_idle',
    'live_workspace.details_panel_percent', 'live_workspace.details_visibility', 'live_workspace.details_active_tab',
    'live_workspace.picker_collapsed', 'live_workspace.active_channel_table_id',
    ...['event_filters', 'message_filters'].flatMap(filter =>
      ['excluded_leaf_keys', 'excluded_timeslots', 'excluded_validity', 'search'].map(key => `live_workspace.${filter}.${key}`))], 'live'),
  ...fields('p25_visualizer', eventCategories.flatMap(category =>
    ['highlight', 'auto_zoom'].map(key => `p25_visualizer.events.${category}.${key}`)), 'radio'),
  ...fields('tuner', ['tuner.floor_db', 'tuner.ceiling_db', 'tuner.waterfall_speed', 'tuner.snap_frequency',
    'tuner.smooth_fft', 'tuner.highlight_waterfall_channels', 'tuner.show_idle_channels', 'tuner.profile',
    'tuner.fft_auto_range_on_retune', 'tuner.waterfall_auto_range_on_retune'], 'admin-tuners'),
  ...fields('status', ['health_alerts.disabled_codes'], 'receiver-health'),
  ...fields('tables', ['tables'])
].map(preference => Object.freeze(preference)));

// Explicit exceptions are device credentials, bounded public caches, or workflow
// recovery/history. They must not become another home for personal preferences.
export const browserStateRegistry = Object.freeze([
  { file: 'core/browser-preferences.js', owner: 'personal', lifetime: 'browser', apis: ['localStorage'],
    records: ['sdrtrunk-vce-guest-preferences-v1'] },
  { file: 'guides/persistence.js', owner: 'guide-history', lifetime: 'account-retry', apis: ['localStorage'],
    records: ['sdrtrunk-vce-guide:v1:'] },
  { file: 'features/alerts-device-storage.js', owner: 'device-credential', lifetime: 'device', apis: ['localStorage'],
    records: ['vce-alert-device-v1'] },
  { file: 'features/alerts.js', owner: 'device-credential-consumer', lifetime: 'device', apis: ['localStorage'], records: [] },
  { file: 'features/radioreference-locality-cache.js', owner: 'public-cache', lifetime: '30-days', apis: ['localStorage'],
    records: ['sdrtrunk-vce-radioreference-localities-v1'] },
  { file: 'core/direct-listen.js', owner: 'listening-session', lifetime: 'tab', apis: ['sessionStorage'],
    records: ['sdrtrunk.direct-listen.v1'] },
  { file: 'features/retained-statistics.js', owner: 'workflow', lifetime: 'tab', apis: ['sessionStorage'],
    records: ['retained-statistics-job'] },
  { file: 'features/alerts-worker-storage.js', owner: 'device-credential', lifetime: 'device', apis: ['indexedDB'],
    records: ['vce-alert-worker-v1'] },
  { file: '../alerts-worker.js', owner: 'device-credential-consumer', lifetime: 'device', apis: ['indexedDB'], records: [] },
  { file: 'app.js', owner: 'download-workflow', lifetime: '30-seconds', apis: ['cookie'],
    records: ['sdrtrunk_alias_export_ready_'] }
].map(record => Object.freeze({ ...record, apis: Object.freeze(record.apis), records: Object.freeze(record.records) })));

export function guestDefaults(defaults) {
  const preferences = JSON.parse(JSON.stringify(defaults));
  for (const preference of preferenceRegistry) {
    if (!Object.hasOwn(preference, 'guestDefault')) continue;
    const keys = preference.path.split('.');
    const property = keys.pop();
    const owner = keys.reduce((value, key) => value[key], preferences);
    owner[property] = preference.guestDefault;
  }
  return preferences;
}
