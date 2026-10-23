// Fictional inputs for the Aliases tour. Every screen is rendered by the normal
// workspace, table, dialog and popover builders; this module creates no UI.
export function getAliasesGuideData(section = 'lists') {
  const scanLists = [
    { id: 900001, name: 'Public Safety', published: true },
    { id: 900002, name: 'Unidentified Traffic', published: false }
  ];
  const list = { id: 900001, alias_list_id: 900001, name: 'Example County', family: 'P25',
    alias_count: 4, assigned_channel_count: 1,
    unknown_alias_behavior: { recordable: true, scan_list_ids: [900002], broadcast_configuration_ids: [] },
    new_alias_behavior: { recordable: true, scan_list_ids: [900001], broadcast_configuration_ids: [] } };
  const latest = Date.UTC(2026, 9, 23, 14, 32);
  const rows = [
    { alias_id: 910001, name: 'Fire Dispatch', description: 'County fire dispatch', group: 'Fire',
      identifier_display: '1001', value: 1001, logical_call_count: 42, signaling_observation_count: 68,
      last_evidence_ms: latest, record_enabled: true, scan_lists: ['Public Safety'], scan_list_ids: [900001] },
    { alias_id: 910002, name: 'EMS Dispatch', description: 'County EMS dispatch', group: 'EMS',
      identifier_display: '1002', value: 1002, logical_call_count: 16, signaling_observation_count: 24,
      last_evidence_ms: latest - 180000, record_enabled: true, scan_lists: ['Public Safety'], scan_list_ids: [900001] },
    { alias_id: 910003, name: 'Public Works', description: 'Road and maintenance crews', group: 'Public Works',
      identifier_display: '2001', value: 2001, logical_call_count: 0, signaling_observation_count: 5,
      last_evidence_ms: latest - 3600000, record_enabled: false, scan_lists: [], scan_list_ids: [] },
    { alias_id: 910004, name: 'Events', description: 'Event talkgroup range', group: 'Events',
      identifier_display: '3000–3099', min_value: 3000, max_value: 3099, matcher_label: 'Talkgroup range',
      logical_call_count: null, signaling_observation_count: null, last_evidence_ms: null,
      record_enabled: false, scan_lists: [], scan_list_ids: [] }
  ].map(row => ({ alias_list_id: list.id, alias_list_name: list.name, family: 'P25', protocol: 'p25',
    protocol_variant: 'phase_1', matcher_type: row.min_value ? 'talkgroup_range' : 'talkgroup',
    matcher_label: 'P25 Talkgroup', color: 0, icon_name: null, broadcast_channels: [],
    broadcast_configuration_ids: [], stream_as_talkgroup: null, ...row,
    recordable: row.record_enabled, matcher: { type: row.min_value ? 'talkgroup_range' : 'talkgroup',
      protocol: 'p25', variant: 'phase_1', ...(row.min_value ? { minimum: row.min_value, maximum: row.max_value } : { value: row.value }) }
  }));
  const options = { revision: 0, edit_revision: 'guide-defaults', alias_list: list, scan_lists: scanLists,
    streams: [{ configuration_id: 'example-local', name: 'Example local feed' }],
    group_names: ['Fire', 'EMS', 'Public Works', 'Events'], icon_names: [],
    matchers: [{ type: 'talkgroup', protocol: 'p25', variant: 'phase_1', label: 'P25 Talkgroup',
      fields: ['value'], minimum: 1, maximum: 65535 }] };
  const observed = [{ protocol: 'p25', protocol_variant: 'phase_1', group_identity_id: 2501,
    native_id: 2501, group_identity_kind: 'talkgroup', group_identity_kind_code: 1,
    identity_key: 'v1-g-00001-001-2501', radio_system_key: 'p25:00001:001',
    system_name: 'Example County', channel_names: 'Example County Site 1', match_kind: 'none',
    promotion_supported: true, logical_call_count: 8, signaling_observation_count: 13, last_seen_ms: latest }];
  const page = { rows, total_count: rows.length, offset: 0, limit: 100, has_more: false,
    matcher_types: ['talkgroup', 'talkgroup_range'] };
  const view = ['activity', 'custom', 'discover'].includes(section) ? section : 'configure';
  return { section, list, lists: [list], options, rows, page,
    observedPage: { rows: observed, total_count: observed.length, offset: 0, limit: 100, has_more: false },
    channels: [{ configuration_id: '00000000-0000-4000-8000-000000000001', name: 'Example County Site 1', alias_list_id: list.id }],
    recordResponse: { alias: rows[0], edit_revision: 'guide-alias' },
    analytics: { ...rows[0], first_evidence_ms: latest - 86400000, breakdown: [] },
    selectedIds: ['bulk', 'scan-lists'].includes(section) ? [910001, 910002] : [],
    query: new URLSearchParams({ view: 'aliases', list: String(list.id), aliasTab: view }) };
}
