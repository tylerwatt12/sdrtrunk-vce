// Fictional receiver observations for the normal Administration renderers.
// This provider owns no network connections, timers, accounts, or receiver state.
export const ADMINISTRATION_GUIDE_IDS = Object.freeze({
  countySystem: 'p25:bee00:123', citySystem: 'p25:bee00:456',
  countyChannel: 'ad000000-0000-4000-8000-000000000001',
  cityChannel: 'ad000000-0000-4000-8000-000000000002',
  conventionalChannel: 'ad000000-0000-4000-8000-000000000003',
  countyList: 9401, cityList: 9402, tunerOne: 'guide-rtl-one', tunerTwo: 'guide-rtl-two'
});

const clone = value => structuredClone(value);
const wrap = data => ({ data });
const ROOT = 'https://administration-guide.invalid';
const REVISION = 'a'.repeat(64);
const IDS = ADMINISTRATION_GUIDE_IDS;
const typeNames = Object.freeze({ site_state: 'Sites & state', frequencies: 'Frequencies',
  band_plans: 'Band plans', foreign_band_plans: 'Foreign band plans', neighbors: 'Neighbors',
  patches: 'Patch groups', control_quality: 'Quality history', radios: 'Radio IDs', talkgroups: 'Talkgroups',
  relationships: 'Radio relationships', affiliations: 'Affiliations & presence', call_activity: 'Call totals',
  signaling_activity: 'Signaling totals', hourly_history: 'Hourly history', detailed_events: 'Detailed events',
  issi_assignment_history: 'ISSI assignment history', alias_activity: 'Alias Activity', all: 'All retained data' });

const accessPolicies = [
  ['web-access', 'Entire web interface', 'public', true], ['dashboard', 'Dashboard', 'public', true],
  ['live', 'Live', 'public', true], ['tuner-spectrum', 'Tuner Spectrum', 'admin', false],
  ['radio', 'Radio systems and channels', 'public', true], ['credits', 'Credits', 'public', true],
  ['csv-export', 'CSV export', 'public', true], ['call-audio', 'Scanner and call audio', 'user', true],
  ['recordings', 'Recordings search and playback', 'user', true], ['user-settings', 'Personal settings', 'user', false],
  ['admin-users', 'User management', 'admin', false], ['admin-access', 'Access management', 'admin', false],
  ['admin-aliases', 'Alias and scan-list management', 'admin', false],
  ['admin-streaming', 'Streaming management', 'admin', false], ['admin-channels', 'Channel management', 'admin', false],
  ['admin-tuners', 'Tuner management', 'admin', false], ['admin-settings', 'Receiver settings', 'admin', false],
  ['admin-recordings', 'Manage recordings', 'admin', false], ['receiver-health', 'Receiver status', 'admin', false]
].map(([id, display_name, required_tier, configurable]) => ({ id, display_name, required_tier, configurable }));

function offsetPage(rows, parameters, defaultLimit = 25) {
  const requestedLimit = Number(parameters.get('limit') || defaultLimit);
  const limit = Number.isSafeInteger(requestedLimit) ? Math.max(1, Math.min(100, requestedLimit)) : defaultLimit;
  const requestedOffset = Number(parameters.get('offset') || 0);
  const offset = Number.isSafeInteger(requestedOffset) ? Math.max(0, requestedOffset) : 0;
  const term = (parameters.get('q') || '').trim().toLowerCase();
  const matches = term ? rows.filter(row => JSON.stringify(row).toLowerCase().includes(term)) : rows;
  const hasMore = offset + limit < matches.length;
  return { data: matches.slice(offset, offset + limit), meta: { limit, offset, total_count: matches.length,
    has_more: hasMore, next_offset: hasMore ? offset + limit : null } };
}

export function createAdministrationGuideData({ signal, now = Date.now() } = {}) {
  if (!Number.isSafeInteger(now) || now <= 0) throw new TypeError('An example timestamp is required.');
  let disposed = false;
  const check = requestSignal => {
    if (disposed || signal?.aborted || requestSignal?.aborted)
      throw new DOMException('The Administration example was closed.', 'AbortError');
  };
  const lists = [
    { id: IDS.countyList, alias_list_id: IDS.countyList, name: 'County P25', family: 'P25', alias_count: 4 },
    { id: IDS.cityList, alias_list_id: IDS.cityList, name: 'City P25', family: 'P25', alias_count: 4 }
  ];
  const channel = (configuration_id, name, system, site, frequency, aliasList) => ({ configuration_id, name,
    system, system_name: system, site, site_name: site, protocol: 'P25_PHASE1', protocol_id: 'p25-phase1',
    protocol_label: 'P25 Phase 1', channel_kind: 'TRUNKED', frequencies_hz: [frequency], frequency_hz: frequency,
    processing_state: 'RUNNING', auto_start_order: 1, alias_list_id: aliasList, alias_list_name: system,
    channel_entity_ref: { kind: 'channel', key: configuration_id }, radio_system_key: system === 'City P25' ? IDS.citySystem : IDS.countySystem,
    radio_system_entity_ref: { kind: 'radio_system', key: system === 'City P25' ? IDS.citySystem : IDS.countySystem }, remote_origin: null });
  const channels = [channel(IDS.countyChannel, 'County Site', 'County P25', 'County', 856_162_500, IDS.countyList),
    channel(IDS.cityChannel, 'City Site', 'City P25', 'City', 858_162_500, IDS.cityList),
    { configuration_id: IDS.conventionalChannel, name: 'County Fireground', system: 'County Fireground',
      system_name: 'County Fireground', site: 'Repeater', site_name: 'Repeater', protocol: 'NBFM', protocol_id: 'nbfm',
      protocol_label: 'NBFM', channel_kind: 'CONVENTIONAL', frequencies_hz: [154_280_000], frequency_hz: 154_280_000,
      processing_state: 'STOPPED', auto_start_order: null, remote_origin: null,
      channel_entity_ref: { kind: 'channel', key: IDS.conventionalChannel } }];
  const status = { generated_at_ms: now, p25_visualizer_available: true,
    stats_logging: { summary_configured: true, detailed_history_configured: true, summary_active: true,
      detailed_history_active: true, detailed_history_available: true, state: 'RUNNING', last_error: null,
      last_successful_write_ms: now - 500 },
    database: { database_exists: true, database_bytes: 88_080_384, stats_logging_enabled: true,
      detailed_history_enabled: true, detailed_history_available: true, retention_days: 30,
      last_detailed_history_ms: now - 500, last_successful_write_ms: now - 500, logger: [] } };
  const currentIssue = { occurrence_id: 'guide-usb-current', code: 'receiver-iq-drop', severity: 'warning',
    title: 'Radio data keeps being lost', scope: 'RTL-SDR 2', display_scope: 'RTL-SDR 2 · City Site',
    configuration_id: IDS.cityChannel, opened_at_ms: now - 120_000, last_seen_ms: now - 1_000,
    observed: 'Incoming radio data was incomplete during several USB transfers.',
    likely_cause: 'The USB connection is not keeping up with the tuner.',
    impact: 'Calls may be missed or interrupted.', check_next: 'Check the USB connection and try a different port.' };
  const health = { started_at_ms: now - 14_400_000, generated_at_ms: now,
    computer: { application: 'sdrtrunk VCE', version: '0.6.2', update_track: 'Nightly', update_build: 'example-build',
      build_timestamp: new Date(now - 86_400_000).toISOString(), operating_system: 'Windows 11',
      operating_system_version: '24H2', architecture: 'amd64', cpu_model: 'Example 8-core processor',
      processors_available: 8, installed_memory_bytes: 17_179_869_184, system_memory_bytes: 16_106_127_360,
      jvm_memory_limit_bytes: 4_294_967_296, java_version: '25', java_vendor: 'OpenJDK', application_uptime_ms: 14_400_000 },
    summary: { severity: 'warning', active_count: 1, warning_count: 1, critical_count: 0 }, active: [currentIssue],
    resolved: [{ ...currentIssue, occurrence_id: 'guide-control-resolved', code: 'control-channel-lock-lost',
      title: 'Control channel is not receiving messages', scope: 'County Site', display_scope: 'County P25 · County Site',
      configuration_id: IDS.countyChannel, opened_at_ms: now - 3_600_000, last_seen_ms: now - 3_550_000,
      resolved_at_ms: now - 3_540_000, observed: 'Control-channel messages stopped arriving.',
      likely_cause: 'The control signal was temporarily weak.', impact: 'New voice calls could not be followed.',
      check_next: 'Check antenna placement and the control-channel signal.' }],
    measurements: [{ id: 'host', title: 'Computer resources', rows: [
      { severity: 'healthy', scope: 'VCE', label: 'VCE processor use', value: 18, unit: '%' },
      { severity: 'healthy', scope: 'VCE', label: 'VCE memory use', value: 32, unit: '%', detail: '1.3 GiB of 4.0 GiB' },
      { severity: 'healthy', scope: 'Computer', label: 'Free storage space', value: 68, unit: '%', detail: '136 GiB available' }
    ] }, { id: 'tuners', title: 'Tuners', rows: [
      { severity: 'healthy', scope: 'RTL-SDR 1', label: 'Active channels', value: 3, unit: 'channels' },
      { severity: 'warning', scope: 'RTL-SDR 2', label: 'Lost radio samples', value: 8192, unit: 'samples',
        detail: 'Observed during the last measurement.' }
    ] }, { id: 'channels', title: 'Channel decoding', rows: [
      { severity: 'healthy', scope: 'County Site', label: 'Control-channel decode quality', value: 98.6, unit: '%' },
      { severity: 'healthy', scope: 'City Site', label: 'Control-channel decode quality', value: 97.3, unit: '%' }
    ] }] };
  const operational = { revision: REVISION, settings: { patch_group_streaming_option: 'PATCH_GROUP',
    audio_record_format: 'MP3', mp3_setting: 'CBR_16', mp3_input_audio_format: 'SR_16000', mp3_normalize_audio: true,
    stats_logging_enabled: true, stats_detailed_history_enabled: true, stats_logging_retention_days: 30 }, options: {
    patch_group_streaming_options: [{ value: 'PATCH_GROUP', label: 'Patch Group' }, { value: 'TALKGROUPS', label: 'Individual Talkgroups' }],
    audio_record_formats: [{ value: 'MP3', label: 'MP3' }, { value: 'WAVE', label: 'WAVE' }],
    mp3_settings: [{ value: 'CBR_16', label: '16 kbps' }, { value: 'CBR_32', label: '32 kbps' }],
    mp3_input_audio_formats_by_setting: { CBR_16: [{ value: 'SR_8000', label: '8 kHz / 16 bit' },
      { value: 'SR_16000', label: '16 kHz / 16 bit' }], CBR_32: [{ value: 'SR_16000', label: '16 kHz / 16 bit' }] },
    minimum_stats_logging_retention_days: 1, maximum_stats_logging_retention_days: 365 } };
  const recordingSettings = { mode: 'MANAGED', managed_directory: 'data/recordings/managed', retention_days: 30,
    transcription_enabled: true, transcription_url: 'https://speech.example.invalid/v1/audio/transcriptions',
    transcription_model: 'example-speech-model',
    transcription_min_duration_ms: 500, transcription_key_configured: false };
  const recordingStatus = { catalog: { call_count: 12_408, bytes: 1_489_920_000, oldest_call_ms: now - 2_505_600_000 },
    last_recount_ms: now - 3_600_000, maintenance: { state: 'IDLE' },
    transcription: { pending: 36, completed: 12_370, failed: 2, active: true, last_error: null } };
  const logEntries = [
    ['INFO', 'County Site started receiving control-channel messages.', ''],
    ['WARN', 'RTL-SDR 2 lost incoming radio data.', 'Several USB transfers arrived incomplete.'],
    ['INFO', 'County Site control-channel reception recovered.', 'Normal reception resumed after the signal returned.']
  ].map(([level, message, details], index) => ({ id: String(index + 1), time: new Date(now - (3 - index) * 20_000).toISOString(),
    level, source: 'Receiver', message, details,
    text: `${new Date(now - (3 - index) * 20_000).toISOString()} ${level} Receiver - ${message}${details ? `\n${details}` : ''}` }));
  const logDocument = (parameters, web = false) => ({ log: parameters.get('log') === 'previous' ? 'previous' : 'current',
    file_name: web ? 'sdrtrunk_web.log' : 'sdrtrunk_app.log', available: true, entries: logEntries,
    updated_at: now, max_entries: 500, truncated: false, latest_id: '3', gap: false, incremental: false });
  const duplicate = (sequence, group, radio, elapsed) => {
    const start = now - elapsed;
    const leg = (index, selected) => ({ copy_index: index, selected, decoder: 'P25 Phase 1',
      channel_name: selected ? 'County Site' : 'County South Site', configuration_ref: index,
      frequency_hz: selected ? 856_662_500 : 857_162_500, timeslot: 1, wacn: 0xBEE00, system: 0x123,
      rfss: 1, site: index, start_timestamp: start + (selected ? 0 : 48), end_timestamp: start + 3600,
      duration_milliseconds: selected ? 3600 : 3552, expected_frame_count: 180, observed_frame_count: selected ? 173 : 170,
      usable_frame_count: selected ? 167 : 164, decoded_frame_count: selected ? 169 : 166,
      repeated_frame_count: selected ? 2 : 3, concealed_frame_count: selected ? 4 : 5, missing_frame_count: selected ? 9 : 11,
      fec_error_count: selected ? 3 : 6, fec_protected_bit_count: 10_000, quality_percent: selected ? 92.8 : 91.1,
      missing_and_concealed_rate: selected ? 0.072 : 0.089, repeated_frame_rate: selected ? 0.011 : 0.017,
      normalized_fec_error_rate: selected ? 0.0003 : 0.0006, retained_audio_sample_count: 28_800,
      ingress_loss: false, audio_truncated: false, overlap: { overlap_milliseconds: selected ? 3600 : 3552,
        shorter_copy_overlap_percent: 100, selected_copy_coverage_percent: selected ? 100 : 98.7,
        start_offset_from_selected_milliseconds: selected ? 0 : 48, end_offset_from_selected_milliseconds: 0 } });
    return { decision_sequence: sequence, decided_at_ms: start + 4000, outcome: 'MERGED',
      call_identity: { protocol: 'APCO25', decoder: 'P25 Phase 1', start_timestamp: start, end_timestamp: start + 3600,
        destination_value: String(group), destination_alias: group === 1001 ? 'Regional Fire' : 'Sheriff',
        source_value: String(radio), source_alias: radio === 2107 ? 'Engine 7' : 'Sheriff 214', encryption_state: 'CLEAR',
        unique_learned_site_count: 2, destination_wacn: 0xBEE00, destination_system_id: 0x123,
        source_wacn: 0xBEE00, source_system_id: 0x123 },
      output_policy: { record_requested: true, stream_routing_key_count: 1, browser_offered: true },
      winner: { selected_copy_index: 1, runner_up_copy_index: 2, criterion: 'USABLE_FRAME_COUNT',
        winner_value: { display: '167 usable frames', numerator: 167, denominator: 180 },
        runner_up_value: { display: '164 usable frames', numerator: 164, denominator: 180 } },
      legs: [leg(1, true), leg(2, false)], evidence: { confirmed_duplicate_pair_count: 1, separated_pair_count: 0,
        uncertain_pair_count: 0, merge_proof_counts: { shared_voice_content: 1 }, rejection_reason_counts: {} }, decision_reasons: [] };
  };
  const matching = { available: true, session_id: 'guide-call-matching', session_started_at_epoch_millis: now - 14_400_000,
    cursor: 72, incremental: false, resolver: { session_id: 'guide-coordinator', started_at_ms: now - 14_400_000,
      generated_at_ms: now, revision: 7, snapshot_age_ms: 0, health_state: 'HEALTHY', accepting: true, disposed: false,
      active_leg_count: 3, active_cohort_count: 1, retained_audio_sample_count: 28_800,
      counters: { merged_logical_calls: 147, merged_receiver_copies: 153, fail_open_logical_calls: 2,
        emitted_logical_calls: 210, diagnostic_decisions_rejected: 0 } },
    queue: { ingress_depth: 2, regular_ingress_capacity: 504, total_ingress_capacity: 512, accepted_ingress: 1000,
      dropped_ingress: 0, dropped_lifecycle: 0, dropped_operations: 0, aborted_calls: 0 },
    diagnostic_status: { accepting: true, decisions_observed: 147, records_rejected_after_close: 0 },
    history: { duplicates_evicted: 6, duplicates_retained: 2, limit: 100, visible_count: 2, retention_capacity: 256 },
    duplicates: [duplicate(72, 1001, 2107, 15_000), duplicate(71, 1002, 1214, 32_000)] };
  const received = (frequency_hz, role, site_id, channel_name) => ({ frequency_hz, bandwidth_hz: 12_500,
    role, site_id, channel_name });
  const allocation = { available: true, session_id: 'guide-allocation', cursor: 2, generated_at_ms: now,
    sites: channels.slice(0, 2).map((row, index) => ({ id: row.configuration_id, name: row.name,
      system_name: row.system_name, channel_name: row.name, kind: 'trunked', known_frequency_count: 3,
      covered_frequency_count: 2, tuners: [index ? IDS.tunerTwo : IDS.tunerOne], status: 'control_and_calls',
      frequencies: [[row.frequency_hz, 'control', true], [row.frequency_hz + 500_000, 'call', true],
        [row.frequency_hz + 1_700_000, 'call', false]].map(([frequency_hz, role, covered]) =>
        ({ frequency_hz, bandwidth_hz: 12_500, role, covered })) })),
    tuners: [IDS.tunerOne, IDS.tunerTwo].map((id, index) => ({ id, name: `RTL-SDR ${index + 1}`,
      frequency_hz: index ? 858_500_000 : 856_500_000, usable_bandwidth_hz: 2_352_000, dc_gap_hz: 10_000,
      status: 'enabled', available: true, center_locked: index === 0, min_frequency_hz: 24_000_000,
      max_frequency_hz: 1_766_000_000, preferred_for: [], supports_shared_window: true, reason: null,
      receiving: [received(channels[index].frequency_hz, 'control', channels[index].configuration_id, channels[index].name),
        received(channels[index].frequency_hz + 500_000, 'call', channels[index].configuration_id, channels[index].name)] })),
    decisions: [{ decision_id: 2, sequence: 2, timestamp: now - 2000, site_id: IDS.cityChannel, site_name: 'City Site',
      tuner_id: IDS.tunerTwo, tuner_name: 'RTL-SDR 2', type: 'move', outcome: 'moved',
      reason: 'An available tuner moved to receive the voice call.', before_frequency_hz: 857_500_000,
      requested_center_frequency_hz: 858_500_000, after_frequency_hz: 858_500_000, usable_bandwidth_hz: 2_352_000,
      dc_gap_hz: 10_000, requested_frequency_hz: 858_662_500, covered_before: 1, covered_after: 2,
      repeat_count: 1, first_timestamp: now - 2000, last_timestamp: now - 2000, candidates: [] },
    { decision_id: 1, sequence: 1, timestamp: now - 8000, site_id: IDS.countyChannel, site_name: 'County Site',
      tuner_id: IDS.tunerOne, tuner_name: 'RTL-SDR 1', type: 'allocate', outcome: 'unavailable',
      reason: 'The requested frequency is outside the locked tuner window.', before_frequency_hz: 856_500_000,
      after_frequency_hz: 856_500_000, usable_bandwidth_hz: 2_352_000, dc_gap_hz: 10_000,
      requested_frequency_hz: 857_862_500, covered_before: 2, covered_after: 2,
      repeat_count: 1, first_timestamp: now - 8000, last_timestamp: now - 8000, candidates: [] }] };

  const documents = new Map([
    ['/api/v1/status', wrap(status)], ['/api/v1/receiver-health', wrap(health)],
    ['/api/v1/channel-catalog', wrap({ revision: 1, channels })],
    ['/api/v1/admin/channels', wrap({ revision: 1, channels })],
    ['/api/v1/admin/call-matching', wrap(matching)], ['/api/v1/admin/tuner-allocation', wrap(allocation)],
    ['/api/v1/admin/operational-preferences', operational],
    ['/api/v1/admin/paging-tone-detection', { revision: REVISION, enabled: true, status: 'ready' }],
    ['/api/v1/admin/call-filtering', { revision: REVISION, enabled: false, minimum_usable_voice_percent: null }],
    ['/api/v1/admin/recordings/settings', wrap(recordingSettings)], ['/api/v1/admin/recordings/status', wrap(recordingStatus)],
    ['/api/v1/admin/receiver-settings', { revision: 1, settings: { traffic_grant_age_out_milliseconds: 2000 } }],
    ['/api/v1/admin/spectrum-snap-presets', { revision: 1, country_code: 'US', country_label: 'United States',
      countries: [{ code: 'US', label: 'United States' }, { code: 'CA', label: 'Canada' }], scopes: [] }],
    ['/api/v1/admin/p25-bandplan-overrides', { profiles: [{ wacn: 0xBEE00, system: 0x123, rfss: 1, site: 1,
      bands: [{ identifier: 0, type: 'FDMA', base_frequency: 851_000_000, bandwidth: 12_500,
        channel_spacing: 12_500, transmit_offset: -45_000_000 }] }], known_systems: [
      { wacn: 0xBEE00, system: 0x123, system_name: 'County P25', radio_system_key: IDS.countySystem,
        radio_system_entity_ref: { kind: 'radio_system', key: IDS.countySystem } },
      { wacn: 0xBEE00, system: 0x456, system_name: 'City P25', radio_system_key: IDS.citySystem,
        radio_system_entity_ref: { kind: 'radio_system', key: IDS.citySystem } }] }],
    ['/api/v1/remote-support', wrap({ enabled: false, connected: false, code: '', expires_at_ms: 0, unlimited: false, can_start: true })],
    ['/api/v1/admin/web-diagnostic-log', wrap({ enabled: true, active: true, file_name: 'sdrtrunk_web.log',
      retention_days: 7, maximum_bytes: 104_857_600, queue_depth: 0, dropped_records: 0, written_records: 248, last_error: null, can_download: true })],
    ['/api/v1/admin/users', wrap({ users: [{ username: 'example.admin', primary: true, tier: 'admin',
      password_changed_at_epoch_millis: now - 604_800_000 }, { username: 'example.listener', primary: false, tier: 'user',
      password_changed_at_epoch_millis: now - 86_400_000 }], maximum_users: 8 })],
    ['/api/v1/admin/access', wrap({ capabilities: accessPolicies })],
    ['/api/v1/admin/remote-links', wrap({ revision: 'guide-remote-links',
      listener: { enabled: true, bind_address: '0.0.0.0', port: 53800, state: 'LISTENING', dependencies: [] },
      sender_connection: { enabled: false, state: 'DISABLED', credential_configured: false,
        exported_channel_configuration_ids: [], host: '', port: 53800 },
      senders: [{ sender_id: 'guide-field-receiver', display_name: 'Field receiver', state: 'CONNECTED', last_seen_at_ms: now,
        default_alias_list_id: IDS.countyList, feeds: [{ feed_id: 'guide-county-feed', display_name: 'County Remote Site',
          protocol: 'P25_PHASE1', frequency_hz: 856_162_500, system_name: 'County P25', site_name: 'Remote', state: 'CONNECTED',
          channel_configuration_id: IDS.countyChannel, alias_list_id: IDS.countyList, enabled: true }] }],
      alias_lists: lists, export_channel_options: channels.slice(0, 2).map(row => ({ ...row,
        channel_configuration_id: row.configuration_id })) })],
    ['/api/v1/admin/alerts/config', wrap({ revision: 1, enabled: true, mode: 'proxy', site_url: '',
      sender_label: 'Example receiver', registration: { status: 'pending', sender_id: 'guide-cloud-registration' } })],
    ['/api/v1/admin/alerts/registration', wrap({ status: 'pending', sender_id: 'guide-cloud-registration' })],
    ['/api/v1/admin/alerts/topics', wrap({ revision: 1, enabled: true, topics: [
      { id: 'guide-fire', name: 'Fire/EMS', enabled: true, visible: true, rule_count: 1, subscription_count: 4 },
      { id: 'guide-admin', name: 'Administrator alerts', enabled: true, visible: false, built_in: true, rule_count: 0 }], rules: [], options: {} })]
  ]);

  function retainedSources(parameters) {
    const kind = parameters.get('kind') || 'radio_system';
    if (kind === 'alias_activity') return [{ source_kind: kind, source_key: kind, label: 'Alias Activity',
      target: { kind: 'scoped_data', source_kind: kind, data_type: kind, parts: ['summary'] } }];
    if (kind === 'saved_channel') return [{ source_kind: kind, source_key: IDS.conventionalChannel,
      label: 'County Fireground', protocol: 'NBFM', channel_kind: 'CONVENTIONAL', primary_frequency_hz: 154_280_000,
      entity_ref: { kind: 'channel', key: IDS.conventionalChannel } }];
    if (kind !== 'radio_system') throw new Error('Unknown example statistics scope.');
    return lists.map((list, index) => ({ source_kind: kind, source_key: index ? IDS.citySystem : IDS.countySystem,
      label: list.name, system_name: list.name, protocol: 'P25', alias_list_id: list.id, alias_list_name: list.name,
      entity_ref: { kind: 'radio_system', key: index ? IDS.citySystem : IDS.countySystem } }));
  }
  function retainedResults(parameters) {
    const kind = parameters.get('source_kind') || 'radio_system';
    const dataType = parameters.get('data_type') || 'all';
    if (!['radio_system', 'saved_channel', 'alias_activity'].includes(kind) || !Object.hasOwn(typeNames, dataType))
      throw new Error('Unknown example statistics selection.');
    const city = parameters.get('source_key') === IDS.citySystem;
    const selected = parameters.get('source_key');
    if (selected && (kind === 'radio_system' && ![IDS.countySystem, IDS.citySystem].includes(selected) ||
        kind === 'saved_channel' && selected !== IDS.conventionalChannel))
      throw new Error('Unknown example statistics source.');
    const sourceKey = kind === 'saved_channel' ? IDS.conventionalChannel : city ? IDS.citySystem : IDS.countySystem;
    const base = { kind: 'scoped_data', source_kind: kind, ...(kind === 'alias_activity' ? {} : { source_key: sourceKey }), data_type: dataType };
    if (parameters.get('site_configuration_id')) Object.assign(base, {
      site_configuration_id: city ? IDS.cityChannel : IDS.countyChannel, expected_site_key: city ? 'guide-city-site' : 'guide-county-site' });
    const target = (record_key, parts) => ({ ...base, ...(record_key ? { record_key } : {}), parts });
    if (dataType === 'radios' || dataType === 'talkgroups') {
      const radio = dataType === 'radios';
      const identities = city ? radio ? [[3202, 'Engine 2'], [4212, 'City Police 12']] : [[2001, 'City Fire'], [2002, 'Police Department']] :
        radio ? [[2107, 'Engine 7'], [1214, 'Sheriff 214']] : [[1001, 'Regional Fire'], [1002, 'Sheriff']];
      return identities
        .map(([id, name], index) => { const key = `v1-${radio ? 'r' : 'g'}-bee00-${city ? '456' : '123'}-${id}`;
          return { label: name, alias_name: name, native_id: id, alias_list_id: city ? IDS.cityList : IDS.countyList,
            alias_list_name: city ? 'City P25' : 'County P25', logical_call_count: 128 - index * 24, last_seen_ms: now - index * 5000,
            entity_ref: { kind: radio ? 'radio' : 'talkgroup', radio_system_key: sourceKey, identity_key: key },
            target: target(key, ['summary', 'buckets', 'events']) }; });
    }
    if (dataType === 'frequencies') return (kind === 'saved_channel' ? [154_280_000] :
      city ? [858_162_500, 858_662_500] : [856_162_500, 856_662_500]).map((frequency, index) => ({
      label: `${(frequency / 1_000_000).toFixed(5)} MHz`, frequency_hz: frequency, current_tags: [index ? 'VOICE' : 'CURRENT_CONTROL'],
      channel_kind: kind === 'saved_channel' ? 'CONVENTIONAL' : 'TRUNKED', observation_count: 120 + index * 8, last_seen_ms: now,
      entity_ref: { kind: 'channel', key: kind === 'saved_channel' ? IDS.conventionalChannel : city ? IDS.cityChannel : IDS.countyChannel },
      target: target(String(frequency), kind === 'saved_channel' ? ['summary', 'buckets', 'events'] : ['current', 'summary', 'buckets', 'events']) }));
    if (dataType === 'alias_activity') return [{ label: 'Regional Fire', alias_id: 9411, alias_list_id: IDS.countyList,
      alias_list_name: 'County P25', alias_group: 'Fire/EMS', matcher_type: 'TALKGROUP', logical_call_count: 128,
      signaling_observation_count: 164, last_seen_ms: now, target: target('9411', ['summary']) }];
    return [{ label: typeNames[dataType], detail: kind === 'saved_channel' ? 'County Fireground' : city ? 'City P25' : 'County P25',
      target: target(null, dataType === 'all' ? ['current', 'summary', 'buckets', 'events'] :
        ['hourly_history', 'control_quality'].includes(dataType) ? ['buckets'] : dataType === 'detailed_events' ? ['events'] : ['summary']) }];
  }
  function documentFor(path, options) {
    check(options.signal);
    if (String(options.method || 'GET').toUpperCase() !== 'GET' || options.body !== undefined)
      throw new Error('Administration examples are read only.');
    if (typeof path !== 'string' || !path.startsWith('/api/v1/') || path.includes('\\') || /[\r\n]/.test(path))
      throw new Error('Unknown Administration example request.');
    const url = new URL(path, ROOT);
    if (url.origin !== ROOT || url.hash || url.username || url.password || !path.startsWith(`${url.pathname}`))
      throw new Error('Unknown Administration example request.');
    if (url.pathname === '/api/v1/application-log' || url.pathname === '/api/v1/admin/web-diagnostic-log/entries')
      return wrap(logDocument(url.searchParams, url.pathname.includes('web-diagnostic')));
    if (url.pathname === '/api/v1/admin/retained-statistics/sources') return offsetPage(retainedSources(url.searchParams), url.searchParams, 50);
    if (url.pathname === '/api/v1/admin/retained-statistics/sites') {
      const city = url.searchParams.get('source_key') === IDS.citySystem;
      return offsetPage([{ configuration_id: city ? IDS.cityChannel : IDS.countyChannel, label: city ? 'City Site' : 'County Site',
        site_key: city ? 'guide-city-site' : 'guide-county-site', entity_ref: { kind: 'channel', key: city ? IDS.cityChannel : IDS.countyChannel } }], url.searchParams, 50);
    }
    if (url.pathname === '/api/v1/admin/retained-statistics/results') return offsetPage(retainedResults(url.searchParams), url.searchParams);
    const document = documents.get(url.pathname);
    if (!document) throw new Error('Unknown Administration example request.');
    return document;
  }
  async function read(path, options = {}) {
    const document = clone(documentFor(path, options));
    await Promise.resolve();
    check(options.signal);
    return document;
  }
  function response(document, requestSignal) {
    let consumed = false;
    const consume = () => {
      check(requestSignal);
      if (consumed) throw new TypeError('The example response was already read.');
      consumed = true;
      return clone(document);
    };
    return { ok: true, status: 200, headers: new Headers({ 'Content-Type': 'application/json', Date: new Date(now).toUTCString() }),
      async json() { return consume(); }, async text() { return JSON.stringify(consume()); } };
  }
  return {
    getStatus() { check(); return clone(status); },
    getReceiverHealth() { check(); return clone(health); },
    async requestJson(path, options = {}) {
      const document = await read(path, options);
      if (!Object.hasOwn(document, 'data')) throw new Error('This example endpoint returns a raw JSON document.');
      options.onResponse?.(response(document, options.signal));
      check(options.signal);
      return Array.isArray(document.data) ? { rows: document.data, ...(document.meta || {}) } :
        document.meta ? { ...document.data, ...document.meta } : document.data;
    },
    async fetchDocument(path, options = {}) { return response(await read(path, options), options.signal); },
    dispose() { disposed = true; }
  };
}
