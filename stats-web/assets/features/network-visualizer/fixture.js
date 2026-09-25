'use strict';

const CONFIG_ALPHA_SITE_1 = '11111111-1111-4111-8111-111111111111';
const CONFIG_ALPHA_SITE_2 = '22222222-2222-4222-8222-222222222222';
const CONFIG_BRAVO = '33333333-3333-4333-8333-333333333333';
const CONFIG_ANALOG = '44444444-4444-4444-8444-444444444444';
const CONFIG_DMR = '55555555-5555-4555-8555-555555555555';
const SYSTEM_ALPHA = 'p25:alpha-proven-system';
const SYSTEM_BRAVO = 'p25:bravo-proven-system';
const SYSTEM_DMR = 'dmr:metro-capacity-plus';

function entity(kind, identityKey, nativeId, label, radioSystemKey = '') {
  return {
    kind,
    identity_key: identityKey,
    native_id: String(nativeId),
    label,
    ...(radioSystemKey ? { radio_system_key: radioSystemKey } : {})
  };
}

function site(configurationId, rfssId, siteId, channelName) {
  return { configuration_id: configurationId, rfss_id: rfssId, site_id: siteId, channel_name: channelName };
}

function envelope(atMs, topic, payload) {
  return Object.freeze({ atMs, topic, payload: Object.freeze(payload) });
}

function createOverflowObservations(count = 1_200, options = {}) {
  const total = Math.max(0, Math.min(25_000, Math.trunc(Number(count) || 0)));
  const startedAtMs = Number(options.startedAtMs) || 1_700_000_100_000;
  const radioSystemKey = options.radioSystemKey || SYSTEM_ALPHA;
  const configurationId = options.configurationId || CONFIG_ALPHA_SITE_1;
  const group = entity('talkgroup', 'tg:overflow', 9090, 'Overflow Operations', radioSystemKey);
  return Array.from({ length: total }, (_value, index) => envelope(startedAtMs + index, 'decode_events', {
    kind: 'affiliation_observed',
    event_id: `fixture-overflow-${index}`,
    observed_at_ms: startedAtMs + index,
    protocol: 'p25',
    radio_system_key: radioSystemKey,
    system_name: 'Alpha Regional',
    wacn: '00101',
    system_id: '0A1',
    ...site(configurationId, 1, 1, 'Alpha Simulcast'),
    comparison_scope_key: `${configurationId}:1:1:x`,
    evidence_type: 'group_affiliation_response',
    outcome: 'accepted',
    sequence: 10_000 + index,
    radio: entity('radio', `radio:overflow:${index}`, 800000 + index, `Overflow ${index + 1}`, radioSystemKey),
    group
  }));
}

function createNetworkVisualizerFixture(options = {}) {
  const startedAtMs = Number(options.startedAtMs) || 1_700_000_000_000;
  const alphaDispatch = entity('talkgroup', 'tg:101', 101, 'North Dispatch', SYSTEM_ALPHA);
  const alphaTac = entity('talkgroup', 'tg:202', 202, 'North Tac', SYSTEM_ALPHA);
  const alphaRadio = entity('radio', 'radio:7001', 7001, 'Unit 12', SYSTEM_ALPHA);
  const bravoDispatch = entity('talkgroup', 'tg:101', 101, 'South Dispatch', SYSTEM_BRAVO);
  const bravoRadio = entity('radio', 'radio:7001', 7001, 'South Unit 7001', SYSTEM_BRAVO);
  const dmrGroup = entity('talkgroup', 'tg:12:slot1', 12, 'Plant Operations', SYSTEM_DMR);
  const dmrRadio = entity('radio', 'radio:7001', 7001, 'Plant Radio 7001', SYSTEM_DMR);
  const scopeAlphaSite1 = `${CONFIG_ALPHA_SITE_1}:1:1:x`;
  const scopeAlphaSite2 = `${CONFIG_ALPHA_SITE_2}:1:2:x`;

  const observations = [
    envelope(startedAtMs + 100, 'decode_events', {
      kind: 'affiliation_observed', event_id: 'fixture-affiliation-alpha-first',
      observed_at_ms: startedAtMs + 100, protocol: 'p25', radio_system_key: SYSTEM_ALPHA,
      system_name: 'Alpha Regional', wacn: '00101', system_id: '0A1',
      ...site(CONFIG_ALPHA_SITE_1, 1, 1, 'Alpha Simulcast'), comparison_scope_key: scopeAlphaSite1,
      evidence_type: 'group_affiliation_response', outcome: 'accepted', sequence: 10,
      radio: alphaRadio, group: alphaDispatch
    }),
    envelope(startedAtMs + 120, 'decode_events', {
      kind: 'affiliation_observed', event_id: 'fixture-affiliation-denied',
      observed_at_ms: startedAtMs + 120, protocol: 'p25', radio_system_key: SYSTEM_ALPHA,
      ...site(CONFIG_ALPHA_SITE_1, 1, 1, 'Alpha Simulcast'), comparison_scope_key: scopeAlphaSite1,
      evidence_type: 'group_affiliation_response', outcome: 'denied', sequence: 11,
      radio: entity('radio', 'radio:denied', 7999, 'Denied Radio', SYSTEM_ALPHA), group: alphaTac
    }),
    envelope(startedAtMs + 140, 'decode_events', {
      kind: 'presence_observed', event_id: 'fixture-registration-only',
      observed_at_ms: startedAtMs + 140, protocol: 'p25', radio_system_key: SYSTEM_ALPHA,
      ...site(CONFIG_ALPHA_SITE_1, 1, 1, 'Alpha Simulcast'), comparison_scope_key: scopeAlphaSite1,
      evidence_type: 'unit_registration', outcome: 'accepted', sequence: 12,
      radio: entity('radio', 'radio:registration', 7998, 'Registration Only', SYSTEM_ALPHA), group: alphaTac
    }),
    envelope(startedAtMs + 200, 'decode_events', {
      kind: 'affiliation_observed', event_id: 'fixture-alpha-other-site-same-group',
      observed_at_ms: startedAtMs + 200, protocol: 'p25', radio_system_key: SYSTEM_ALPHA,
      system_name: 'Alpha Regional', wacn: '00101', system_id: '0A1',
      ...site(CONFIG_ALPHA_SITE_2, 1, 2, 'Alpha East'), comparison_scope_key: scopeAlphaSite2,
      evidence_type: 'group_affiliation_query_response', outcome: 'confirmed', sequence: 30,
      radio: alphaRadio, group: alphaDispatch
    }),
    envelope(startedAtMs + 300, 'decode_events', {
      kind: 'affiliation_observed', event_id: 'fixture-alpha-real-change',
      observed_at_ms: startedAtMs + 300, protocol: 'p25', radio_system_key: SYSTEM_ALPHA,
      system_name: 'Alpha Regional', wacn: '00101', system_id: '0A1',
      ...site(CONFIG_ALPHA_SITE_1, 1, 1, 'Alpha Simulcast'), comparison_scope_key: scopeAlphaSite1,
      evidence_type: 'group_affiliation_response', outcome: 'accepted', sequence: 13,
      radio: alphaRadio, group: alphaTac
    }),
    envelope(startedAtMs + 350, 'decode_events', {
      kind: 'call_start', event_id: 'fixture-alpha-call-leg-1', transmission_state: 'active',
      observed_at_ms: startedAtMs + 350, protocol: 'p25', radio_system_key: SYSTEM_ALPHA,
      system_name: 'Alpha Regional', ...site(CONFIG_ALPHA_SITE_1, 1, 1, 'Alpha Simulcast'),
      call_leg_id: 'alpha-leg-1', resource_context_key: 'traffic:851.0125:slot1',
      radio: alphaRadio, group: alphaTac
    }),
    envelope(startedAtMs + 370, 'decode_events', {
      kind: 'call_start', event_id: 'fixture-alpha-call-leg-2', transmission_state: 'active',
      observed_at_ms: startedAtMs + 370, protocol: 'p25', radio_system_key: SYSTEM_ALPHA,
      system_name: 'Alpha Regional', ...site(CONFIG_ALPHA_SITE_1, 1, 1, 'Alpha Simulcast'),
      call_leg_id: 'alpha-leg-2', resource_context_key: 'traffic:851.0250:slot2',
      encrypted: true, radio: alphaRadio, group: alphaTac
    }),
    envelope(startedAtMs + 390, 'decode_events', {
      kind: 'call_start', event_id: 'fixture-alpha-unknown-source', transmission_state: 'active',
      observed_at_ms: startedAtMs + 390, protocol: 'p25', radio_system_key: SYSTEM_ALPHA,
      system_name: 'Alpha Regional', ...site(CONFIG_ALPHA_SITE_2, 1, 2, 'Alpha East'),
      call_leg_id: 'alpha-leg-unknown', resource_context_key: 'traffic:852.0125:slot1', group: alphaDispatch
    }),
    envelope(startedAtMs + 420, 'decode_events', {
      kind: 'affiliation_observed', event_id: 'fixture-bravo-same-numeric-identities',
      observed_at_ms: startedAtMs + 420, protocol: 'p25', radio_system_key: SYSTEM_BRAVO,
      system_name: 'Bravo County', wacn: '00202', system_id: '0B2',
      ...site(CONFIG_BRAVO, 2, 4, 'Bravo West'), comparison_scope_key: `${CONFIG_BRAVO}:2:4:x`,
      evidence_type: 'group_affiliation_response', outcome: 'accepted', sequence: 5,
      radio: bravoRadio, group: bravoDispatch
    }),
    envelope(startedAtMs + 450, 'decode_events', {
      kind: 'call_start', event_id: 'fixture-dmr-call', transmission_state: 'active',
      observed_at_ms: startedAtMs + 450, protocol: 'dmr', radio_system_key: SYSTEM_DMR,
      system_name: 'Metro Capacity Plus', configuration_id: CONFIG_DMR, channel_name: 'Plant DMR', timeslot: 1,
      call_leg_id: 'dmr-leg-1', resource_context_key: 'dmr:461.200:slot1', radio: dmrRadio, group: dmrGroup
    }),
    envelope(startedAtMs + 480, 'decode_events', {
      kind: 'call_start', event_id: 'fixture-analog-call', transmission_state: 'active',
      observed_at_ms: startedAtMs + 480, protocol: 'nbfm', configuration_id: CONFIG_ANALOG,
      channel_name: 'County Fire Paging', call_leg_id: 'analog-leg-1', resource_context_key: '155.7900',
      source_label: 'decoder display only'
    }),
    envelope(startedAtMs + 500, 'decode_events', {
      kind: 'affiliation_observed', event_id: 'fixture-unresolved-channel-affiliation',
      observed_at_ms: startedAtMs + 500, protocol: 'p25', configuration_id: CONFIG_ALPHA_SITE_2,
      channel_name: 'Unresolved P25 Site', comparison_scope_key: scopeAlphaSite2,
      evidence_type: 'group_affiliation_response', outcome: 'accepted', sequence: 1,
      radio: entity('radio', 'provisional:9001', 9001, 'Provisional Unit'),
      group: entity('talkgroup', 'provisional-tg:301', 301, 'Provisional Group')
    }),
    envelope(startedAtMs + 550, 'decode_events', {
      kind: 'identity_reconciled', event_id: 'fixture-reconciliation', observed_at_ms: startedAtMs + 550,
      from_universe_key: `channel:${CONFIG_ALPHA_SITE_2}`, from_radio_identity_key: 'provisional:9001',
      protocol: 'p25', radio_system_key: SYSTEM_ALPHA, system_name: 'Alpha Regional',
      ...site(CONFIG_ALPHA_SITE_2, 1, 2, 'Alpha East'),
      radio: entity('radio', 'radio:9001', 9001, 'Unit 9001', SYSTEM_ALPHA),
      group: entity('talkgroup', 'tg:301', 301, 'Mutual Aid', SYSTEM_ALPHA)
    })
  ];

  const activeSnapshot = Object.freeze({
    source_key: 'fixture-channel-activity', revision: 1,
    tables: [Object.freeze({
      table_id: 'fixture-alpha-table', configuration_id: CONFIG_ALPHA_SITE_1,
      protocol: 'p25', system_name: 'Alpha Regional', site_name: 'Alpha Simulcast',
      rows: [Object.freeze({
        key: 'traffic-row-1', configuration_id: CONFIG_ALPHA_SITE_1, protocol: 'p25',
        call_leg_id: 'snapshot-leg-current', tx_state: 'active',
        tx_started_at_ms: startedAtMs - 500, tx_last_observed_at_ms: startedAtMs + 50,
        source_entity_ref: alphaRadio, target_entity_ref: alphaDispatch,
        channel_name: 'Alpha Simulcast', status: 'CALL', role: 'TRAFFIC', activation_order: 7
      })]
    })]
  });
  const idleSnapshot = Object.freeze({
    source_key: 'fixture-channel-activity', revision: 2,
    tables: [Object.freeze({
      table_id: 'fixture-alpha-table', configuration_id: CONFIG_ALPHA_SITE_1, protocol: 'p25',
      rows: [Object.freeze({
        key: 'traffic-row-1', configuration_id: CONFIG_ALPHA_SITE_1, protocol: 'p25',
        call_leg_id: 'snapshot-leg-current', tx_state: 'ended', tx_end_proven: true,
        tx_end_reason: 'decoder_end', source_entity_ref: alphaRadio, target_entity_ref: alphaDispatch,
        status: 'IDLE', role: 'TRAFFIC', activation_order: 7
      })]
    })]
  });

  return Object.freeze({
    name: 'Network Visualizer deterministic live fixture',
    startedAtMs,
    constants: Object.freeze({
      CONFIG_ALPHA_SITE_1, CONFIG_ALPHA_SITE_2, CONFIG_BRAVO, CONFIG_ANALOG, CONFIG_DMR,
      SYSTEM_ALPHA, SYSTEM_BRAVO, SYSTEM_DMR
    }),
    observations: Object.freeze(observations),
    snapshots: Object.freeze({ active: activeSnapshot, idle: idleSnapshot }),
    overflow: (count = 1_200) => createOverflowObservations(count, { startedAtMs: startedAtMs + 10_000 })
  });
}

export { createNetworkVisualizerFixture, createOverflowObservations };
