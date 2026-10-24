// Fictional read-only observations for the production Main page and its tabs.
// This module creates data, never UI, receiver actions, or saved preferences.
import { decodeOffsetPage } from '../core/page-lifecycle.js';

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const COUNTY_LIST = 9401;
const CITY_LIST = 9402;
const COUNTY = 'p25:bee00:123';
const CITY = 'p25:bee00:456';
const DMR = 'dmr:channel:b00b9000-0000-4000-8000-000000000005';
const NORTH = 'b00b9000-0000-4000-8000-000000000001';
const SOUTH = 'b00b9000-0000-4000-8000-000000000002';
const CITY_SITE = 'b00b9000-0000-4000-8000-000000000003';
const FIREGROUND = 'b00b9000-0000-4000-8000-000000000004';
const DMR_SITE = 'b00b9000-0000-4000-8000-000000000005';
const ORIGIN = 'https://main-guide.invalid';
const clone = value => structuredClone(value);
const ranges = new Map([['1h', HOUR], ['6h', 6 * HOUR], ['24h', DAY], ['7d', 7 * DAY], ['30d', 30 * DAY]]);
const systemReference = key => ({ kind: 'radio_system', key });
const channelReference = key => ({ kind: 'channel', key });
const identityReference = (system, kind, id) => ({ kind, radio_system_key: system,
  identity_key: `v1-${kind === 'radio' ? 'r' : 'g'}-bee00-${system === CITY ? '456' : '123'}-${id}` });

const messageTypes = [
  [3, 'BUSY', 'Busy', 'The requested radio, talkgroup or service was busy.', true, true, false],
  [4, 'CALL', 'Call', 'An accepted voice call was observed.', true, true, true],
  [9, 'DENIAL', 'Denied', 'The system or radio refused a request.', true, true, true],
  [10, 'EMERGENCY', 'Emergency', 'Emergency signaling was observed.', true, false, false],
  [12, 'GRANT', 'Channel grant', 'The trunked system assigned a traffic channel.', true, false, false],
  [13, 'JOIN', 'Talkgroup affiliation', 'A radio reported or received a talkgroup affiliation.', true, true, true],
  [20, 'REGISTER', 'Registration', 'A radio registration message was observed.', true, true, true],
  [25, 'STUN', 'Stun', 'A command or response to disable a radio temporarily; a command alone does not confirm success.', false, true, true],
  [27, 'KILL', 'Kill', 'A command or response to disable a radio; a command alone does not confirm success.', false, true, true],
  [28, 'RADIO_INHIBIT', 'Radio inhibit', 'A command to inhibit a radio was observed; its acknowledgment is a separate message.', true, false, false]
].map(([code, name, label, meaning, p25, dmr, nxdn]) => ({ definition_kind: 'ACTION', code,
  name, label, meaning, p25, dmr, nxdn, sort_order: code }));

function distribute(total, weights) {
  const sum = weights.reduce((value, weight) => value + weight, 0);
  const shares = weights.map((weight, index) => ({ index, exact: total * weight / sum }));
  const values = shares.map(share => Math.floor(share.exact));
  const remaining = total - values.reduce((value, count) => value + count, 0);
  shares.sort((left, right) => (right.exact % 1) - (left.exact % 1) || left.index - right.index)
    .slice(0, remaining).forEach(share => { values[share.index] += 1; });
  return values;
}

function p25Identity(system, kind, id, aliasName) {
  return { protocol: 'P25', protocol_code: 1, channel_kind: 'TRUNKED',
    radio_system_key: system, system_name: system === CITY ? 'City P25' : 'County P25',
    wacn: 0xBEE00, system_id: system === CITY ? 0x456 : 0x123,
    identity_kind: kind, identity_id: id, native_id: id, alias_name: aliasName,
    entity_ref: identityReference(system, kind, id), radio_system_entity_ref: systemReference(system) };
}

function channel(id, name, system, site, frequency, list) {
  return { configuration_id: id, name, system_name: system === CITY ? 'City P25' : 'County P25',
    site_name: site, protocol: 'P25', protocol_label: 'P25 Phase 1', decoder: 'P25_PHASE1',
    channel_kind: 'TRUNKED', radio_system_key: system, processing_state: 'RUNNING',
    frequencies_hz: [frequency], display_frequency_hz: frequency,
    display_frequency_source: 'active-control', alias_list_id: list,
    alias_list_name: system === CITY ? 'City P25' : 'County P25',
    channel_entity_ref: channelReference(id), radio_system_entity_ref: systemReference(system) };
}

/** All requests stay inside this instance and use the production decoded API shapes. */
export function createMainGuideData({ signal, section } = {}) {
  let disposed = false;
  const requests = [];
  const check = requestSignal => {
    if (disposed || signal?.aborted || requestSignal?.aborted)
      throw new DOMException('The Main example was closed.', 'AbortError');
  };
  const north = channel(NORTH, 'North Site', COUNTY, 'North', 856662500, COUNTY_LIST);
  const south = channel(SOUTH, 'South Site', COUNTY, 'South', 857662500, COUNTY_LIST);
  const city = channel(CITY_SITE, 'City Site', CITY, 'City', 858162500, CITY_LIST);
  const fireground = { configuration_id: FIREGROUND, name: 'County Fireground',
    system_name: 'County Fireground', protocol: 'NBFM', protocol_label: 'NBFM', decoder: 'NBFM',
    channel_kind: 'CONVENTIONAL', frequencies_hz: [154280000], frequency_hz: 154280000,
    processing_state: 'RUNNING', entity_ref: channelReference(FIREGROUND) };
  const lists = [
    { alias_list_id: COUNTY_LIST, name: 'County P25', family: 'P25', alias_count: 5,
      correlated_channel_count: 2, capabilities: { observed_groups: true } },
    { alias_list_id: CITY_LIST, name: 'City P25', family: 'P25', alias_count: 3,
      correlated_channel_count: 1, capabilities: { observed_groups: true } }
  ];
  const requireList = value => {
    const list = lists.find(candidate => String(candidate.alias_list_id) === String(value));
    if (!list) throw new Error('Unknown Main example Alias List.');
    return list;
  };
  const rangeFor = (parameters, allowed = [...ranges.keys()]) => {
    const value = parameters.get('range') || '24h';
    if (!allowed.includes(value)) throw new Error('Unknown Main example time range.');
    return value;
  };
  const paging = (parameters, maximum = 100) => {
    const integer = (name, fallback, min, max) => {
      const raw = parameters.get(name);
      if (raw === null) return fallback;
      if (!/^\d+$/.test(raw) || !Number.isSafeInteger(Number(raw)) || Number(raw) < min || Number(raw) > max)
        throw new Error('Unknown Main example paging state.');
      return Number(raw);
    };
    return { limit: integer('limit', maximum, 1, maximum), offset: integer('offset', 0, 0, 10_000) };
  };
  const page = (values, parameters, maximum = 100, metadata = {}) => {
    const { limit, offset } = paging(parameters, maximum);
    const rows = values.slice(offset, offset + limit), hasMore = offset + rows.length < values.length;
    return { ...metadata, rows, limit, offset, total_count: values.length,
      has_more: hasMore, next_offset: hasMore ? offset + rows.length : null };
  };
  const search = (values, parameters) => {
    const term = parameters.get('q') || '';
    if (term.length > 160) throw new Error('Unknown Main example search.');
    return term ? values.filter(value => JSON.stringify(value).toLowerCase().includes(term.toLowerCase())) : values;
  };
  const ordered = (values, parameters, fields, defaultSort, defaultDirection = 'desc') => {
    const sort = parameters.get('sort') || defaultSort, direction = parameters.get('direction') || defaultDirection;
    if (!fields[sort] || !['asc', 'desc'].includes(direction)) throw new Error('Unknown Main example sort.');
    return [...values].sort((left, right) => {
      const a = left[fields[sort]], b = right[fields[sort]];
      const result = typeof a === 'number' || typeof b === 'number' ? Number(a || 0) - Number(b || 0) :
        String(a || '').localeCompare(String(b || ''), undefined, { numeric: true });
      return (direction === 'asc' ? result : -result) || Number(left.alias_id || left.native_id || 0) - Number(right.alias_id || right.native_id || 0);
    });
  };
  const mainSnapshot = () => {
    const now = Date.now();
    const current = [[north, -48.2, -49.1, 99.3], [south, -68.4, -67.6, 82.4], [city, -53.1, -53.7, 98.6]]
      .map(([source, signalDbfs, averageDbfs, decode]) => ({ ...source, last_observed_ms: now,
        signal_dbfs: signalDbfs, average_signal_dbfs: averageDbfs, decode_health_pct: decode }));
    return { quality: { current }, directory: { systems: [
      { system_name: 'County P25', protocol: 'P25', protocol_label: 'P25', radio_system_key: COUNTY,
        entity_ref: systemReference(COUNTY), alias_lists: [{ id: COUNTY_LIST, name: 'County P25' }], children: [north, south] },
      { system_name: 'City P25', protocol: 'P25', protocol_label: 'P25', radio_system_key: CITY,
        entity_ref: systemReference(CITY), alias_lists: [{ id: CITY_LIST, name: 'City P25' }], children: [city] }
    ], channels: [fireground] } };
  };
  const callStatistics = () => {
    const now = Date.now(), firstHour = Math.floor((now - 1) / HOUR) * HOUR - 23 * HOUR;
    const weights = [2, 2, 1, 1, 1, 2, 5, 10, 14, 11, 7, 6, 5, 7, 9, 12, 15, 13, 10, 7, 5, 4, 3, 2];
    const configurations = [['P25', 1, 'TRUNKED', 864, 696, 216], ['NBFM', 10, 'CONVENTIONAL', 136, 104, 34]];
    const series = configurations.flatMap(([protocol, protocolCode, kind, calls, recorded, streamed]) => {
      const c = distribute(calls, weights), r = distribute(recorded, weights), s = distribute(streamed, weights);
      return weights.map((_, index) => ({ time_ms: firstHour + index * HOUR, protocol,
        protocol_code: protocolCode, channel_kind: kind, logical_call_count: c[index],
        recorded_logical_call_count: r[index], stream_submitted_logical_call_count: s[index] }));
    });
    const counts = (calls, recorded, streamed) => ({ logical_call_count: calls,
      recorded_logical_call_count: recorded, stream_submitted_logical_call_count: streamed });
    return { last_seen_ms: now, call_activity: { from_ms: firstHour, to_ms: now, bucket_ms: HOUR,
      totals: counts(1000, 800, 250), series,
      metric_coverage: { logical_call_count: 'COLLECTED', recorded_logical_call_count: 'COLLECTED',
        stream_submitted_logical_call_count: 'COLLECTED' },
      coverage: ['AM', 'P25', 'DMR', 'NXDN', 'NBFM'].flatMap(protocol =>
        ['TRUNKED', 'CONVENTIONAL'].map(kind => ({ protocol, channel_kind: kind,
          status: configurations.some(entry => entry[0] === protocol && entry[2] === kind) ? 'COLLECTED' : 'NOT_COLLECTED' }))) },
      source_activity_24h: { ...page([{ ...fireground, source_label: fireground.name, ...counts(136, 104, 34) }],
        new URLSearchParams()), from_ms: firstHour, to_ms: now },
      top_destinations: [
        { ...p25Identity(COUNTY, 'talkgroup', 1001, 'Sheriff Dispatch'), ...counts(330, 290, 90) },
        { ...p25Identity(COUNTY, 'talkgroup', 1002, 'Regional Fire'), ...counts(260, 235, 85) }
      ], top_sources: [
        { ...p25Identity(COUNTY, 'radio', 214, 'Sheriff 214'), ...counts(180, 165, 45) },
        { ...p25Identity(COUNTY, 'radio', 7007, 'Engine 7'), ...counts(145, 132, 47) }
      ] };
  };
  const configuredAliases = (list, now = Date.now()) => {
    const county = list.alias_list_id === COUNTY_LIST, system = county ? COUNTY : CITY;
    const row = (aliasId, name, kind, id, calls, signals, age, description) => ({
      alias_id: aliasId, alias_list_id: list.alias_list_id, name, description,
      protocol: 'APCO25', identity_type: kind, matcher_type: kind === 'radio' ? 'RADIO_ID' : 'TALKGROUP',
      matcher_label: kind === 'radio' ? 'Radio ID' : 'Talkgroup', identity_display: String(id), value: id,
      metrics_state: age === null ? 'not_collected' : 'observed',
      logical_call_count: age === null ? null : calls, signaling_observation_count: age === null ? null : signals,
      first_evidence_ms: age === null ? null : now - 19 * DAY, last_evidence_ms: age === null ? null : now - age,
      entity_ref: identityReference(system, kind, id) });
    return county ? [
      row(94101, 'Sheriff Dispatch', 'talkgroup', 1001, 2840, 5620, 3 * 60_000, 'County law enforcement'),
      row(94102, 'Regional Fire', 'talkgroup', 1002, 1960, 3140, 8 * 60_000, 'County fire and EMS'),
      row(94103, 'Sheriff 214', 'radio', 214, 640, 1120, 3 * 60_000, 'Patrol unit'),
      row(94104, 'Public Works', 'talkgroup', 1501, 0, 8, 2 * DAY, 'Road and maintenance crews'),
      row(94105, 'Special Events', 'talkgroup', 1601, 0, 0, null, 'Event coordination')
    ] : [
      row(94201, 'Police Department', 'talkgroup', 2001, 1840, 3260, 5 * 60_000, 'City police'),
      row(94202, 'City Fire', 'talkgroup', 2002, 1240, 2080, 11 * 60_000, 'City fire and EMS'),
      row(94203, 'Patrol 12', 'radio', 12012, 470, 980, 5 * 60_000, 'Patrol unit')
    ];
  };
  const overview = (list, parameters) => {
    const range = rangeFor(parameters), activeAfter = Date.now() - ranges.get(range);
    const aliases = configuredAliases(list), channels = list.alias_list_id === COUNTY_LIST ? [north, south] : [city];
    return { alias_list: list, range, active_after_ms: activeAfter, totals: {
      configured_alias_count: aliases.length, activity_eligible_alias_count: aliases.length,
      active_alias_count: aliases.filter(row => row.last_evidence_ms !== null && row.last_evidence_ms >= activeAfter).length,
      zero_call_alias_count: aliases.filter(row => Number(row.logical_call_count || 0) === 0).length,
      never_heard_alias_count: aliases.filter(row => row.last_evidence_ms === null).length
    }, channels, correlated_channel_count: channels.length, correlated_radio_system_count: 1 };
  };
  const actions = [['GRANT', 920], ['JOIN', 240], ['REGISTER', 180], ['CALL', 122],
    ['EMERGENCY', 12], ['DENIAL', 9], ['BUSY', 6], ['RADIO_INHIBIT', 3], ['STUN', 2], ['KILL', 1]];
  const activityRows = (action, now) => {
    const dmr = ['STUN', 'KILL'].includes(action);
    const data = action === 'EMERGENCY' ? [[COUNTY, 214, 'Sheriff 214', 7], [COUNTY, 7007, 'Engine 7', 3], [CITY, 12012, 'Patrol 12', 2]] :
      [[dmr ? DMR : COUNTY, dmr ? 88 : 214, dmr ? 'Utilities 88' : 'Sheriff 214', actions.find(row => row[0] === action)[1]]];
    return data.map(([system, id, name, count], index) => ({
      ...(dmr ? { protocol: 'DMR', protocol_code: 3, channel_kind: 'TRUNKED', radio_system_key: DMR,
        system_name: 'County Utilities DMR', configuration_id: DMR_SITE,
        entity_ref: { kind: 'radio', radio_system_key: DMR, identity_key: 'v1-r-x-x-88' },
        radio_system_entity_ref: systemReference(DMR) } : p25Identity(system, 'radio', id, name)),
      native_id: id, radio_id: id, alias_name: name, alias_description: dmr ? 'Utilities field radio' : 'Public safety field radio',
      radio_entity_ref: dmr ? { kind: 'radio', radio_system_key: DMR, identity_key: 'v1-r-x-x-88' } : identityReference(system, 'radio', id),
      observation_count: count, last_seen_ms: now - (index + 1) * 60_000 }));
  };
  const requestEnvelope = async (path, options = {}) => {
    check(options.signal);
    if (typeof path !== 'string' || !path.startsWith('/api/v1/') || path.startsWith('//') ||
        String(options.method || 'GET').toUpperCase() !== 'GET' || options.body !== undefined)
      throw new Error('Unsupported Main example request.');
    const url = new URL(path, ORIGIN), parameters = url.searchParams;
    if (url.origin !== ORIGIN || url.hash || url.pathname !== path.split('?')[0])
      throw new Error('Unsupported Main example request.');
    const allow = names => {
      const keys = [...parameters.keys()];
      if (keys.some(key => !names.includes(key)) || new Set(keys).size !== keys.length)
        throw new Error('Unknown Main example parameters.');
    };
    let result;
    if (url.pathname === '/api/v1/dashboard/main') { allow([]); result = mainSnapshot(); }
    else if (url.pathname === '/api/v1/dashboard') { allow([]); result = callStatistics(); }
    else if (url.pathname === '/api/v1/activity/message-types') { allow([]); result = { rows: messageTypes }; }
    else if (url.pathname === '/api/v1/activity/actions') {
      allow(['range']);
      const range = rangeFor(parameters, ['6h', '24h', '7d']), to = Date.now();
      result = { range, from_ms: to - ranges.get(range), to_ms: to,
        rows: actions.map(([action, observation_count]) => ({ action, observation_count })),
        total_observation_count: actions.reduce((sum, row) => sum + row[1], 0) };
    } else if (url.pathname === '/api/v1/activity/radios') {
      allow(['range', 'action', 'limit', 'offset']);
      const action = parameters.get('action'), range = rangeFor(parameters, ['6h', '24h', '7d']);
      if (!actions.some(row => row[0] === action)) throw new Error('Unknown Main example activity type.');
      const to = Date.now(), values = activityRows(action, to), count = values.reduce((sum, row) => sum + row.observation_count, 0);
      result = page(values, parameters, 100, { action, range, from_ms: to - ranges.get(range), to_ms: to,
        action_observation_count: count, retained_observation_count: count,
        identified_observation_count: count, unknown_source_observation_count: 0 });
    } else if (url.pathname === '/api/v1/identities/lists') {
      allow(['limit', 'offset']); result = page(lists, parameters, 500);
    } else {
      const match = url.pathname.match(/^\/api\/v1\/identities\/lists\/(\d+)\/(overview|aliases|unassigned)$/);
      if (!match) throw new Error('Unsupported Main example request.');
      const list = requireList(match[1]), kind = match[2];
      if (kind === 'overview') { allow(['range']); result = overview(list, parameters); }
      else if (kind === 'aliases') {
        allow(['range', 'status', 'type', 'q', 'limit', 'offset', 'sort', 'direction']);
        const range = rangeFor(parameters), status = parameters.get('status') || 'all', type = parameters.get('type');
        if (!['all', 'recent', 'zero_calls', 'never_heard'].includes(status) || type !== null && !['talkgroup', 'radio', 'other'].includes(type))
          throw new Error('Unknown Main example alias filter.');
        const cutoff = Date.now() - ranges.get(range);
        let values = configuredAliases(list).filter(row => !type || row.identity_type === type);
        values = values.filter(row => status === 'recent' ? row.last_evidence_ms !== null && row.last_evidence_ms >= cutoff :
          status === 'zero_calls' ? Number(row.logical_call_count || 0) === 0 : status === 'never_heard' ? row.last_evidence_ms === null : true);
        values = ordered(search(values, parameters), parameters, { name: 'name', identity: 'value',
          logical_call_count: 'logical_call_count', signaling_observation_count: 'signaling_observation_count',
          first_evidence: 'first_evidence_ms', last_evidence: 'last_evidence_ms' }, 'name', 'asc');
        result = page(values, parameters, 100, { range, status });
      } else {
        allow(['q', 'limit', 'offset', 'sort', 'direction']);
        const county = list.alias_list_id === COUNTY_LIST, system = county ? COUNTY : CITY, id = county ? 1645 : 2445;
        const source = county ? north : city, now = Date.now();
        const values = [{ protocol: 'P25', protocol_code: 1, topology: 'TRUNKED',
          group_identity_id: id, native_id: id, group_identity_kind: 'talkgroup', group_identity_kind_code: 1,
          identity_key: identityReference(system, 'talkgroup', id).identity_key, radio_system_key: system,
          wacn: 0xBEE00, system_id: county ? 0x123 : 0x456, system_name: county ? 'County P25' : 'City P25',
          channel_names: source.name, configuration_id: source.configuration_id, frequency_hz: source.display_frequency_hz,
          logical_call_count: 18, first_seen_ms: now - 3 * DAY, last_seen_ms: now - 12 * 60_000,
          counts_as_of_ms: now, entity_ref: identityReference(system, 'talkgroup', id),
          channel_entity_ref: channelReference(source.configuration_id), radio_system_entity_ref: systemReference(system) }];
        result = page(ordered(search(values, parameters), parameters, { logical_call_count: 'logical_call_count',
          group_identity: 'group_identity_id', system: 'system_name', first_seen: 'first_seen_ms', last_seen: 'last_seen_ms' },
        'logical_call_count'), parameters);
      }
    }
    check(options.signal);
    requests.push({ path: url.pathname, parameters: Object.fromEntries(parameters), method: 'GET' });
    const { rows, ...metadata } = result;
    return clone(rows ? { data: rows, meta: metadata } : { data: result });
  };
  const requestJson = async (path, options = {}) => {
    const envelope = await requestEnvelope(path, options);
    check(options.signal);
    return Array.isArray(envelope.data) ? { rows: envelope.data, ...(envelope.meta || {}) } :
      envelope.meta ? { ...envelope.data, ...envelope.meta } : envelope.data;
  };
  const api = async (path, parameters = {}, options = {}) => {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(parameters)) {
      if (value !== null && value !== undefined && value !== '') query.set(key, String(value));
    }
    if (path.includes('?') && query.size) throw new Error('Unknown Main example parameters.');
    return requestJson(`${path}${query.size ? `?${query}` : ''}`, options);
  };
  const apiPage = async (path, parameters = {}, options = {}) => decodeOffsetPage(await api(path, parameters, options), path);
  const dispose = () => {
    if (disposed) return;
    disposed = true;
    signal?.removeEventListener('abort', dispose);
  };
  if (signal?.aborted) dispose();
  else signal?.addEventListener('abort', dispose, { once: true });
  return { api, apiPage, requestJson, requestEnvelope, dispose, section,
    selectedListId: COUNTY_LIST, cityListId: CITY_LIST, selectedAction: 'EMERGENCY',
    selectedSystemKey: COUNTY, countySystemKey: COUNTY, citySystemKey: CITY, selectedConfigurationId: NORTH,
    messageTypes: clone(messageTypes),
    stats: () => ({ disposed, requests: clone(requests), timers: 0, connections: 0 }) };
}
