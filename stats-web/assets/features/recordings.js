import { systemLabel, systemName, homeSystemName } from '../core/system-labels.js?v=2';
import { formatP25RadioIdentifier, p25IdentityFacts, p25IdentifierText, p25ServingSystemKey } from '../core/radio-labels.js?v=7';
import { formatSourceName } from '../core/source-names.js?v=1';
import { formatHexDecimal } from '../core/call-value-format.js?v=1';
import { callAudioDetailGroups } from '../core/call-audio-details.js?v=6';
import { createFormWorkflow } from '../core/form-workflows.js?v=4';

const CALLS = '/api/v1/recordings/calls';
const IDENTITIES = '/api/v1/recordings/identities';
const ENTITIES = '/api/v1/recordings/entities';
const STATUS = '/api/v1/recordings/status';
const ADMIN = '/api/v1/admin/recordings';
const PAGE_SIZE = 25;
const SUGGESTION_DELAY_MS = 240;

function queryPath(root, values = {}) {
  const query = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== '' && value !== null && value !== undefined) query.set(key, String(value));
  });
  return query.size ? `${root}?${query}` : root;
}

export function recordingSuggestionFilters(filters, suggestion) {
  const next = { ...filters };
  if (!suggestion?.name_query) return next;
  for (const key of ['radio_id', 'radio_identity_key', 'radio_min', 'radio_max', 'talkgroup_id', 'talkgroup_identity_key',
    'talkgroup_min', 'talkgroup_max', 'alias_list_id']) next[key] = '';
  next.q = String(suggestion.name_query);
  if (suggestion.system_key) next.system_key = String(suggestion.system_key);
  return next;
}

export function recordingDiscoveryPeriod(filters, now = Date.now()) {
  const range = filters.transcript && filters.text_range !== 'time' ? '24h' : filters.range || '24h';
  if (range === 'today') {
    const start = new Date(now); start.setHours(0, 0, 0, 0);
    return { from_ms: String(start.getTime()), to_ms: String(now) };
  }
  const span = { '1h': 3_600_000, '24h': 86_400_000, '7d': 7 * 86_400_000, '30d': 30 * 86_400_000,
    '10y': 3650 * 86_400_000 }[range];
  return span ? { from_ms: String(now - span), to_ms: String(now) } :
    { from_ms: filters.from_ms, to_ms: filters.to_ms };
}

export function recordingIdentitySelections(value, kind = 'radio') {
  let rows;
  try { rows = Array.isArray(value) ? value : JSON.parse(value || '[]'); } catch (_error) { return []; }
  if (!Array.isArray(rows)) return [];
  const selections = [];
  for (const item of rows) {
    if (!item || !(item.system_key === null || typeof item.system_key === 'string')) continue;
    const protocol = item.protocol === 'APCO25_PHASE2' ? 'APCO25' : item.protocol;
    if (protocol && !['UNKNOWN', 'APCO25', 'DMR', 'NXDN', 'NBFM', 'AM', 'DCS', 'FLEETSYNC', 'MDC1200', 'LOJACK', 'TAIT1200'].includes(protocol)) continue;
    if (kind === 'radio' && item.id === null && ['FLEETSYNC', 'MDC1200', 'LOJACK', 'TAIT1200'].includes(item.protocol) &&
        typeof item.value === 'string' && item.value.length && !item.identity_key) {
      selections.push({ id: null, system_key: item.system_key, protocol: item.protocol, value: item.value });
    } else if ((typeof item.id === 'number' || typeof item.id === 'string' && item.id.trim() !== '') &&
        Number.isInteger(Number(item.id)) &&
        Number(item.id) >= (kind === 'talkgroup' ? 0 : 1) &&
        (Number(item.id) !== 0 || (protocol ? protocol === 'NXDN' : /^nxdn(?:-|:)/i.test(item.system_key || '')))) selections.push({
      id: Number(item.id), system_key: item.system_key,
      ...(item.identity_key ? { identity_key: String(item.identity_key) } : {}),
      ...(protocol ? { protocol } : {})
    });
  }
  return selections;
}

export function recordingChannelSelections(value) {
  let rows;
  try { rows = Array.isArray(value) ? value : JSON.parse(value || '[]'); } catch (_error) { return []; }
  return Array.isArray(rows) ? [...new Set(rows.filter(item => typeof item === 'string' && item.trim()).map(item => item.trim()))] : [];
}

export function recordingIdentitySelectionKey(item) {
  return item.id === null && item.protocol ? JSON.stringify([item.system_key ?? null, item.protocol, item.value]) :
    JSON.stringify([item.system_key ?? null, Number(item.id), item.identity_key || '', item.protocol === 'APCO25_PHASE2' ? 'APCO25' : item.protocol || '']);
}

function recordingNamespaceLabel(protocol) {
  return ['APCO25', 'APCO25_PHASE2'].includes(protocol) ? 'P25' : protocolLabel(protocol);
}

function recordingSelectionText(item, kind = 'radio') {
  return item.id === null && item.protocol ? `${protocolLabel(item.protocol)} ${item.value}` :
    `${kind === 'radio' ? 'Radio' : 'Talkgroup'} ${item.id}`;
}

export function recordingResultFilter(row, reference, field = '') {
  const match = recordingResultConstraints(row, reference, field);
  if (!match || !['talkgroup_id', 'radio_id'].includes(match.key)) return match;
  const radio = match.key === 'radio_id', kind = radio ? 'radio' : 'talkgroup';
  const prefix = field === 'source' || field === 'source_entity_ref' ? 'source' : 'target';
  const tuple = /^v1-([rgp])-([0-9a-f]{5})-([0-9a-f]{3})-(0|[1-9][0-9]*)$/.exec(reference?.identity_key || '');
  let home = tuple ? reference.identity_key : null;
  const fields = [`${prefix}_home_wacn`, `${prefix}_home_system_id`, `${prefix}_home_id`].map(key => value(row, key));
  if (!home && fields.every(item => item !== null) && /^APCO25/.test(row?.protocol || '')) {
    home = `v1-${radio ? 'r' : row.call_type === 'PATCH' ? 'p' : 'g'}-${Number(fields[0]).toString(16).padStart(5, '0')}-${Number(fields[1]).toString(16).padStart(3, '0')}-${fields[2]}`;
  }
  // Existing qualified P25 links remain exact; raw observations gain an explicit protocol namespace.
  if (home && match.filters[radio ? 'radio_identity_key' : 'talkgroup_identity_key']) return match;
  const observed = match.filters[radio ? 'radio_id' : 'talkgroup_id'];
  const fallback = radio ? value(row, prefix === 'source' ? 'source_id' : 'destination_radio_id', prefix === 'source' ? 'radio_id' : 'target_id') : value(row, 'talkgroup_id', 'group_id');
  const raw = home ? Number(home.split('-').at(-1)) : Number(observed !== '' && observed != null ? observed : fallback);
  const system = value(row, 'system_key', 'radio_system_key') || row?.radio_system_entity_ref?.key || null;
  const protocol = row?.protocol === 'APCO25_PHASE2' ? 'APCO25' : row?.protocol;
  const selection = recordingIdentitySelections([{ id: raw, system_key: system,
    ...(home ? { identity_key: home } : protocol ? { protocol } : {}) }], kind)[0];
  if (!selection) return null;
  const filters = { ...match.filters };
  for (const key of radio ? ['radio_id', 'radio_identity_key', 'radio_min', 'radio_max'] :
    ['talkgroup_id', 'talkgroup_identity_key', 'talkgroup_min', 'talkgroup_max']) filters[key] = '';
  const key = radio ? 'radios' : 'talkgroups'; filters[key] = JSON.stringify([selection]);
  if (radio) filters.radio_role = prefix === 'source' ? 'source' : 'any';
  else filters.targets_any = 'true';
  return { key, filters };
}

function value(row, ...names) {
  for (const name of names) {
    const candidate = row?.[name];
    if (candidate !== null && candidate !== undefined && candidate !== '') return candidate;
  }
  return null;
}

// Convert recorded identities into search constraints, never display names or detail-page routes.
function recordingResultConstraints(row, reference, field = '') {
  const system = value(row, 'system_key', 'radio_system_key') || row?.radio_system_entity_ref?.key;
  const channel = value(row, 'channel_id') || row?.channel_entity_ref?.key;
  const scope = system ? { system_key: String(system) } : channel ? { channel_id: String(channel) } : null;
  if (reference?.auxiliary && reference.kind === 'radio') {
    const item = recordingIdentitySelections([{ id: null, system_key: system ? String(system) : null,
      protocol: reference.protocol, value: reference.value }], 'radio')[0];
    return item ? { key: 'radios', filters: { radios: JSON.stringify([item]),
      radio_id: '', radio_identity_key: '', radio_min: '', radio_max: '',
      radio_role: reference.role === 'source' ? 'source' : 'any' } } : null;
  }
  if (/home.system/i.test(field)) return null;
  if (field === 'site' || field === 'site_entity_ref') {
    const site = row?.audio_from || row;
    const coordinates = {
      wacn: value(site, 'wacn'), sysid: value(site, 'system_id', 'sysid'),
      rfss: value(site, 'rfss', 'rfss_id'), site_id: value(site, 'site_id')
    };
    if (Object.values(coordinates).some(item => item === null || !Number.isInteger(Number(item)) || Number(item) < 0)) return null;
    return { key: 'site', filters: { ...scope, site: '', ...Object.fromEntries(
      Object.entries(coordinates).map(([key, item]) => [key, String(item)])) } };
  }
  if (field === 'alias_list_entity_ref') {
    const id = value(row, 'alias_list_id');
    return id !== null && Number(id) > 0 ? { key: 'alias_list_id', filters: { alias_list_id: String(id) } } : null;
  }
  if (reference?.kind === 'radio_system' || field === 'system' || field === 'radio_system_entity_ref') {
    const key = reference?.key || system;
    return key && (!system || key === system) ? { key: 'system_key', filters: { system_key: String(key) } } : null;
  }
  if (reference?.kind === 'channel' || field === 'channel' || field === 'channel_entity_ref') {
    const key = reference?.key || channel;
    return key ? { key: 'channel_id', filters: { channel_id: String(key) } } : null;
  }
  const kind = reference?.kind || (field === 'source' || field === 'source_entity_ref' ? 'radio' :
    field === 'target' || field === 'target_entity_ref' ?
      (value(row, 'destination_radio_id') !== null ? 'radio' : 'talkgroup') : '');
  if (!['radio', 'talkgroup', 'patch_group'].includes(kind)) return null;
  const tuple = /^v1-([rgp])-(x|[0-9a-f]{5})-(x|[0-9a-f]{3})-(0|[1-9][0-9]*)$/.exec(reference?.identity_key || '');
  if (reference?.identity_key && (!tuple || (tuple[2] === 'x') !== (tuple[3] === 'x') ||
      Number(tuple[4]) === 0 && !(kind !== 'radio' && /^NXDN/i.test(String(row?.protocol || ''))) ||
      Number(tuple[4]) > (tuple[2] === 'x' ? 0xFFFFFF : kind === 'radio' ? 0xFFFFFC : 0xFFFE) ||
      (kind === 'radio' ? tuple[1] !== 'r' : !['g', 'p'].includes(tuple[1])))) return null;
  if (reference?.radio_system_key && system && reference.radio_system_key !== system) return null;
  if (kind !== 'radio') {
    const target = field === 'target' || field === 'target_entity_ref';
    const p25 = /^p25:[0-9a-f]{5}:[0-9a-f]{3}$/.test(system || '');
    const facts = target ? p25IdentityFacts(row, 'target', kind) : null;
    let exact = tuple && tuple[2] !== 'x' && tuple[3] !== 'x' ? reference.identity_key : null;
    if (p25 && !exact && facts) {
      const home = facts.canonical;
      exact = `v1-${kind === 'patch_group' || String(row.call_type).toUpperCase() === 'PATCH' ? 'p' : 'g'}-${
        Number(home.wacn).toString(16).padStart(5, '0')}-${Number(home.system_id).toString(16).padStart(3, '0')}-${home.talkgroup_id}`;
    }
    const homeFields = ['target_home_wacn', 'target_home_system_id', 'target_home_id'].map(key => value(row, key));
    if (p25 && target && !exact && homeFields.some(item => item !== null)) return null;
    if (p25 && exact) return { key: 'talkgroup_id', filters: { ...scope,
      talkgroup_identity_key: exact, talkgroup_id: '', talkgroup_min: '', talkgroup_max: '' } };
    const id = target ? value(row, 'talkgroup_id', 'group_id') ?? tuple?.[4] :
      tuple?.[4] ?? value(row, 'talkgroup_id', 'group_id');
    return id != null && (Number(id) > 0 || Number(id) === 0 && /^NXDN/i.test(String(row?.protocol || ''))) ? { key: 'talkgroup_id', filters: { ...scope,
      talkgroup_id: String(id), talkgroup_identity_key: '', talkgroup_min: '', talkgroup_max: '' } } : null;
  }
  const target = field === 'target' || field === 'target_entity_ref';
  const source = field === 'source' || field === 'source_entity_ref';
  const prefix = target ? 'target' : 'source';
  const id = target ? value(row, 'destination_radio_id', 'target_id') : source ?
    value(row, 'source_id', 'radio_id') : tuple?.[4] ?? value(row, 'source_id', 'radio_id');
  const p25 = /^p25:[0-9a-f]{5}:[0-9a-f]{3}$/.test(system || '');
  let exact = tuple && tuple[2] !== 'x' && tuple[3] !== 'x' ? reference.identity_key : null;
  if (p25 && !exact) {
    const homeWacn = source || target ? value(row, `${prefix}_home_wacn`) : null;
    const homeSystem = source || target ? value(row, `${prefix}_home_system_id`) : null;
    const homeId = source || target ? value(row, `${prefix}_home_id`) : null;
    if ([homeWacn, homeSystem, homeId].every(item => item !== null)) {
      exact = `v1-r-${Number(homeWacn).toString(16).padStart(5, '0')}-${Number(homeSystem).toString(16).padStart(3, '0')}-${homeId}`;
    } else if ([homeWacn, homeSystem, homeId].some(item => item !== null)) return null;
    else if (id !== null && Number(id) > 0) exact = `v1-r-${system.slice(4, 9)}-${system.slice(10)}-${id}`;
  }
  if (p25 && exact) return { key: 'radio_id', filters: {
    ...scope, radio_identity_key: exact, radio_id: '', radio_min: '', radio_max: ''
  } };
  const nativeId = id ?? tuple?.[4];
  return nativeId !== null && nativeId !== undefined && Number(nativeId) > 0 ? { key: 'radio_id', filters: {
    ...scope, radio_id: String(nativeId), radio_identity_key: '', radio_min: '', radio_max: ''
  } } : null;
}

function recordingRadioId(row, prefix, id) {
  if (!/^(?:APCO25|P25)(?:_|$)/i.test(String(row?.protocol || ''))) return id;
  const identity = {
    wacn: row?.[`${prefix}_home_wacn`],
    system_id: row?.[`${prefix}_home_system_id`],
    subscriber_id: row?.[`${prefix}_home_id`]
  };
  const home = row?.[`${prefix}_home_system`];
  const homeKey = home?.radio_system_key || home?.key ||
    (identity.wacn != null && identity.system_id != null ?
      `p25:${Number(identity.wacn).toString(16).padStart(5, '0')}:${
        Number(identity.system_id).toString(16).padStart(3, '0')}` : '');
  return formatP25RadioIdentifier(identity, {
    servingSystemKey: p25ServingSystemKey(row),
    homeSystemName: homeSystemName(row, prefix, homeKey),
    workingId: id
  }) || id;
}

function recordingGroupId(row, prefix, id) {
  if (id == null || row?.protocol && !/^APCO25/.test(row.protocol)) return id;
  const observedRow = { ...row, [`${prefix}_id`]: id,
    [`${prefix}_observed_local_id`]: Object.prototype.hasOwnProperty.call(row, `${prefix}_observed_local_id`) ?
      row[`${prefix}_observed_local_id`] : id };
  const facts = p25IdentityFacts(observedRow, prefix, 'talkgroup');
  return p25IdentifierText(observedRow, id, { prefix, kind: 'talkgroup',
    homeSystemName: homeSystemName(row, prefix, facts?.homeSystemKey || '') }) || id;
}

function recordingMemberId(row, member, id) {
  if (!member || typeof member !== 'object') return id;
  const kind = String(member.kind || member.form || '').toLowerCase() === 'radio' ? 'radio' : 'talkgroup';
  const observed = { ...member, serving_system_key: p25ServingSystemKey(row) };
  const identity = p25IdentityFacts(observed, '', kind);
  return identity ? p25IdentifierText(observed, id, { kind,
    homeSystemName: homeSystemName(member, '', identity.homeSystemKey) }) || id : id;
}

function label(row) {
  const group = value(row, 'talkgroup_alias', 'group_alias', 'talkgroup_name');
  const rawGroupId = value(row, 'talkgroup_id', 'group_id');
  const groupId = Number(rawGroupId) === 0 && !/^NXDN/.test(String(row?.protocol || '')) ? null : rawGroupId;
  const destination = value(row, 'destination_radio_id');
  const destinationAlias = value(row, 'destination_radio_alias', 'destination_radio_ota_alias');
  const channel = value(row, 'channel_name', 'analog_channel_name');
  if (['NBFM', 'AM'].includes(value(row, 'protocol'))) return channel || 'Recorded channel';
  return (groupId !== null ? group : null) || (groupId !== null ? `${String(row.call_type).toUpperCase() === 'PATCH' ? 'Patch' : 'Talkgroup'} ${recordingGroupId(row, 'target', groupId)}` :
    (destination !== null ? `Direct to ${destinationAlias || `Radio ${recordingRadioId(row, 'target', destination)}`}` : channel)) ||
    systemName(row) || 'Recorded call';
}

function recordingHasTalkgroup(row) {
  const id = value(row, 'talkgroup_id', 'group_id');
  return ['APCO25', 'APCO25_PHASE2', 'DMR', 'NXDN'].includes(row?.protocol) && id !== null &&
    (Number(id) > 0 || Number(id) === 0 && row.protocol === 'NXDN');
}

function recordingSourceReference(row) {
  if (Number(value(row, 'source_id', 'radio_id')) > 0) return row.source_entity_ref;
  const auxiliary = row?.auxiliary_identifiers?.find(item => item.kind === 'radio' && item.role === 'source');
  return auxiliary ? { ...auxiliary, kind: 'radio', auxiliary: true } : row.source_entity_ref;
}

function sourceLabel(row, mode) {
  const auxiliary = row?.auxiliary_identifiers?.find(item => item.kind === 'radio' && item.role === 'source');
  if (!(Number(value(row, 'source_id', 'radio_id')) > 0) && auxiliary)
    return [auxiliary.ota_alias || auxiliary.alias, `${protocolLabel(auxiliary.protocol)} ${auxiliary.value}`].filter(Boolean).join(' · ');
  const source = value(row, 'source_alias', 'radio_alias');
  const id = recordingRadioId(row, 'source', value(row, 'source_id', 'radio_id'));
  const ota = value(row, 'source_ota_alias', 'source_ota_ta', 'ota_alias', 'talker_alias');
  return [formatSourceName(source, ota, mode), id !== null ? `Radio ${id}` : null].filter(Boolean).join(' · ');
}

function sourceBrief(row, mode) {
  const id = recordingRadioId(row, 'source', value(row, 'source_id', 'radio_id'));
  return formatSourceName(value(row, 'source_alias', 'radio_alias'),
    value(row, 'source_ota_alias', 'source_ota_ta', 'ota_alias', 'talker_alias'), mode) ||
    (id !== null ? `Radio ${id}` : '');
}

function millis(row) {
  const numeric = Number(value(row, 'start_ms', 'started_at_ms', 'call_start_ms'));
  return Number.isFinite(numeric) ? numeric : 0;
}

function recordingTimestamp(row, ...keys) {
  const raw = value(row, ...keys);
  const timestamp = raw === null || raw === '' ? NaN : Number(raw);
  return Number.isFinite(timestamp) && timestamp >= 0 ? timestamp : null;
}

function recordingEndMilliseconds(row, start) {
  const end = recordingTimestamp(row, 'end_ms', 'ended_at_ms', 'call_end_ms');
  if (end !== null && end >= start) return end;
  const length = recordingTimestamp(row, 'duration_ms', 'call_duration_ms');
  return length !== null && Number.isFinite(start + length) ? start + length : null;
}

export function recordingGapMilliseconds(first, second) {
  const firstStart = recordingTimestamp(first, 'start_ms', 'started_at_ms', 'call_start_ms');
  const secondStart = recordingTimestamp(second, 'start_ms', 'started_at_ms', 'call_start_ms');
  if (firstStart === null || secondStart === null || firstStart === secondStart) return null;
  const earlier = firstStart < secondStart ? first : second;
  const earlierStart = Math.min(firstStart, secondStart);
  const end = recordingEndMilliseconds(earlier, earlierStart);
  if (end === null) return null;
  const gap = Math.max(firstStart, secondStart) - end;
  return Number.isFinite(gap) && gap >= 30_000 ? gap : null;
}

export function recordingPageGaps(rows) {
  const gaps = new Map();
  const ordered = rows.map((row, index) => ({ row, index,
    start: recordingTimestamp(row, 'start_ms', 'started_at_ms', 'call_start_ms') }));
  // An unpositioned call could cover any apparent gap in this page.
  if (ordered.some(item => item.start === null)) return gaps;
  const descending = ordered.length > 1 && ordered[0].start > ordered[ordered.length - 1].start;
  ordered.sort((a, b) => a.start - b.start || (descending ? b.index - a.index : a.index - b.index));
  let coveredThrough = null;
  let endsKnown = true;
  let previous = null;
  for (const item of ordered) {
    const date = dateTime(item.start, true);
    if (previous && endsKnown && date && date === previous.date &&
        Math.abs(item.index - previous.index) === 1) {
      const gap = item.start - coveredThrough;
      if (gap >= 30_000) gaps.set(Math.max(item.index, previous.index), gap);
    }
    const end = recordingEndMilliseconds(item.row, item.start);
    if (end === null) endsKnown = false;
    else coveredThrough = coveredThrough === null ? end : Math.max(coveredThrough, end);
    previous = { ...item, date };
  }
  return gaps;
}

export function recordingGapLabel(milliseconds) {
  let seconds = Math.floor(milliseconds / 1000);
  const parts = [];
  for (const [unit, span] of [['d', 86_400], ['h', 3600], ['m', 60]]) {
    if (seconds >= span) { parts.push(`${Math.floor(seconds / span)}${unit}`); seconds %= span; }
  }
  if (seconds || !parts.length) parts.push(`${seconds}s`);
  return parts.join(' ');
}

export function recordingSearchTiming(milliseconds) {
  const elapsed = Math.max(0, milliseconds);
  return elapsed < 1000 ? `Loaded in ${Math.round(elapsed)} ms` : `Loaded in ${(elapsed / 1000).toFixed(1)} s`;
}

function duration(row) {
  const number = Number(value(row, 'duration_ms', 'call_duration_ms'));
  if (!Number.isFinite(number) || number < 0) return '';
  const seconds = Math.round(number / 1000);
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
}

function dateTime(timestamp, dateOnly = false) {
  if (!timestamp) return '';
  return new Intl.DateTimeFormat(undefined, dateOnly ?
    { year: 'numeric', month: 'short', day: 'numeric' } :
    { year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', second: '2-digit' })
    .format(new Date(timestamp));
}

function timeOnly(timestamp) {
  if (!timestamp) return '';
  return new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit', second: '2-digit' })
    .format(new Date(timestamp));
}

function localDateTime(milliseconds) {
  if (!milliseconds) return '';
  const date = new Date(Number(milliseconds));
  if (!Number.isFinite(date.getTime())) return '';
  const pad = (number) => String(number).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function numberOrNull(input) {
  const text = String(input || '').trim();
  if (!text) return null;
  const number = Number(text);
  return Number.isFinite(number) ? number : null;
}

function frequency(row) {
  const hertz = Number(value(row, 'frequency_hz', 'winner_frequency_hz'));
  return Number.isFinite(hertz) && hertz > 0 ? `${(hertz / 1_000_000).toFixed(4)} MHz` : '';
}

function siteText(site) {
  if (!site) return '';
  if (typeof site === 'string') return site;
  const name = value(site, 'site_name', 'name', 'site_key');
  if (name) return String(name);
  const rfss = value(site, 'rfss_id', 'rfss');
  const id = value(site, 'site_id');
  return [rfss !== null ? `RFSS ${formatHexDecimal(rfss, 2)}` : '',
    id !== null ? `Site ${formatHexDecimal(id, 2)}` : '']
    .filter(Boolean).join(' · ');
}

function winningSite(row) {
  const source = value(row, 'audio_from');
  return value(row, 'site_name', 'winner_site_name') || siteText(source);
}

function prettify(valueToFormat) {
  return String(valueToFormat || '').replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, (match) => match.toUpperCase());
}

function protocolLabel(protocol) {
  return ({ APCO25: 'P25 Phase 1', APCO25_PHASE2: 'P25 Phase 2',
    NBFM: 'Analog FM', AM: 'Analog AM', DMR: 'DMR', NXDN: 'NXDN', DCS: 'DCS', FLEETSYNC: 'FleetSync', MDC1200: 'MDC1200', LOJACK: 'LoJack', TAIT1200: 'Tait1200' })[protocol] || prettify(protocol);
}

function callKey(row) {
  return `${millis(row)}:${String(row?.id ?? '')}`;
}

function afterCall(row, selected) {
  if (millis(row) !== millis(selected)) return millis(row) > millis(selected);
  const id = Number(row?.id);
  const selectedId = Number(selected?.id);
  return Number.isFinite(id) && Number.isFinite(selectedId) ? id > selectedId :
    String(row?.id ?? '') > String(selected?.id ?? '');
}

function appendFact(node, host, name, detail) {
  if (detail === null || detail === undefined || detail === '') return;
  const term = node('dt', '', name);
  const description = node('dd', '');
  description.append(detail);
  host.append(term, description);
}

function button(node, text, action, className = 'ui-button ui-button-secondary') {
  const control = node('button', className, text);
  control.type = 'button';
  if (action) control.addEventListener('click', action);
  return control;
}

function select(node, options, labelText) {
  const control = node('select', 'ui-select');
  control.setAttribute('aria-label', labelText);
  options.forEach(([key, name]) => {
    const choice = node('option', '', name);
    choice.value = key;
    control.append(choice);
  });
  return control;
}

// Reuse map: recording-owned controls compose the approved sidebar and focused pickers.
// The shared modal foundation owns focus, Escape, footer and cleanup; existing identity
// labels, detail tabs, browsing pager and global audio player retain their behavior.
// Administration keeps its established shared controls and compositions.
export function createRecordingsFeature(deps) {
  const { node, requestJson, openReadOnlyModal, section, pageHeader, beginPage,
    captureRenderContext, renderIsCurrent, content, isPrimaryAdmin, canViewRadio,
    entityRefHref, stopLiveAudio, href, anchor, iconButton, iconGlyph, uiToggleField, metrics, browsingWorkflows, modalFooter, closeReadOnlyModal,
    getSourceNameDisplay = () => 'talker_alias', openCallRelations } = deps;
  const search = {
    q: '', transcript: '', from_ms: '', to_ms: '', system_key: '', site: '', talkgroup_id: '', radio_id: '',
    channel_id: '', alias_list_id: '', radio_identity_key: '', talkgroup_identity_key: '', min_duration_ms: '', max_duration_ms: '', frequency_hz: '',
    talkgroup_min: '', talkgroup_max: '', radio_min: '', radio_max: '',
    call_type: '', voice_type: '', protocol: '', wacn: '', sysid: '', rfss: '', site_id: '',
    talkgroups: '', channel_ids: '', targets_any: '', radios: '', radio_role: '', paging_tones: '',
    text_range: '', high_interest: '', dcs_code: '', has_transcript: '',
    tone_frequency_hz: '', tone_then_hz: '', tone_tolerance_hz: ''
  };
  const selectedSuggestions = new Map();
  const identityLabels = new Map();
  const selection = new Set();
  let currentResults = [];
  let currentFilters = {};
  let appliedSuggestions = new Map();
  let appliedRange = '24h';
  let appliedSearch = null;
  let draftFeedback = null;
  let searchError = null;
  let resultScope = null;
  const channelLabels = new Map();
  let currentPage = 0;
  let cursors = [null];
  let nextCursor = null;
  let resultTotal = null;
  let pageHost = null;
  let resultHost = null;
  let resultStatus = null;
  let countHost = null;
  let countText = null;
  let pageSelect = null;
  let pagerHosts = [];
  let selectedFiltersHost = null;
  let filterCountHost = null;
  let selectedHost = null;
  let sortControl = null;
  let pageSizeControl = null;
  let pageSize = PAGE_SIZE;
  let appliedSort = 'desc';
  let searchTiming = null;
  let searchRequest = 0;
  let searchLoading = false;
  let searchSignal = null;
  let player = null;

  const makeNotice = (text, type = '') => {
    const notice = node('div', `ui-feedback${type ? ` ui-feedback-${type}` : ''}`, text);
    notice.setAttribute('role', type === 'error' ? 'alert' : 'status');
    return notice;
  };

  function addGlyph(control, icon, className = 'recordings-action-icon') {
    if (!iconGlyph) return control;
    const glyph = node('span', className);
    glyph.append(iconGlyph(icon));
    control.prepend(glyph);
    return control;
  }

  function resultSearchFilters(match, field) {
    const filters = { ...match.filters };
    if (['talkgroup_id', 'talkgroups'].includes(match.key)) {
      filters.channel_ids = '';
      if (match.key === 'talkgroup_id') { filters.talkgroups = ''; filters.targets_any = ''; }
    }
    if (['radio_id', 'radios'].includes(match.key)) {
      if (match.key === 'radio_id') filters.radios = '';
      if (field === 'target' || field === 'target_entity_ref') filters.radio_role = 'any';
    }
    return filters;
  }

  const entityLink = (text, reference, row, field, filterLabel = text) => {
    if (pageHost?.isConnected && row) {
      const match = recordingResultFilter(row, reference, field);
      if (!match) return String(text);
      match.filters = resultSearchFilters(match, field);
      const values = Object.fromEntries(Object.entries({ ...currentFilters, ...match.filters,
        sort: sortControl?.value || 'desc', recording_filters: '1' }).filter(([, item]) => item !== ''));
      const link = anchor(String(text), href('recordings', values));
      link.title = `Filter recordings by ${filterLabel}`;
      link.addEventListener('click', (event) => {
        if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        const apply = () => {
          Object.keys(search).forEach(key => { search[key] = currentFilters[key] ?? ''; });
          search.range = appliedRange;
          selectedSuggestions.clear();
          appliedSuggestions.forEach((suggestion, key) => selectedSuggestions.set(key, suggestion));
          Object.assign(search, match.filters);
          if (match.key === 'radios') for (const item of recordingIdentitySelections(match.filters.radios, 'radio'))
            identityLabels.set(`radio:${recordingIdentitySelectionKey(item)}`, `${filterLabel} · ${systemLabel(row)}`);
          selectedSuggestions.set(match.key, { label: String(filterLabel),
            appliedKeys: match.key === 'site' ? ['wacn', 'sysid', 'rfss', 'site_id'] :
              match.filters.radio_identity_key ? ['radio_identity_key'] :
                match.filters.talkgroup_identity_key ? ['talkgroup_identity_key'] : [] });
          if (match.filters.system_key && match.key !== 'system_key') selectedSuggestions.set('system_key', {
            label: systemLabel(row), id: match.filters.system_key
          });
          replaceSearchForm();
          submitSearch();
        };
        if (closeReadOnlyModal && !closeReadOnlyModal(false, false, closed => { if (closed) apply(); })) return;
        apply();
      });
      return link;
    }
    const target = canViewRadio?.() ? entityRefHref?.(reference) : null;
    return target ? anchor(String(text), target) : String(text);
  };

  function query(filters, extra = {}) {
    return queryPath(CALLS, { ...filters, limit: PAGE_SIZE, ...extra });
  }

  function replaceSearchForm() {
    const previous = pageHost?.querySelector('.recordings-search');
    if (!previous) return;
    const results = previous.querySelector('.recordings-results-panel');
    const next = makeSearchForm();
    if (results) next.querySelector('.recordings-browser').append(results);
    previous.replaceWith(next);
  }

  function effectiveFilters() {
    const values = { ...search, ...recordingDiscoveryPeriod(search) };
    if (values.high_interest) values.min_duration_ms = String(Math.max(Number(values.min_duration_ms) || 0, 10_000));
    if (recordingChannelSelections(values.channel_ids).length || recordingIdentitySelections(values.talkgroups, 'talkgroup').length)
      values.targets_any = 'true';
    return Object.fromEntries(Object.entries(values).filter(([key, item]) =>
      key !== 'range' && item !== '' && !(key === 'site' && selectedSuggestions.get('site')?.appliedKeys?.length)));
  }

  function updateDraftFeedback() {
    const dirty = appliedSearch && JSON.stringify(search) !== JSON.stringify(appliedSearch);
    if (draftFeedback) draftFeedback.textContent = dirty ? 'Changes ready to search' : '';
    pageHost?.querySelector('#recordings-discard')?.toggleAttribute('hidden', !dirty);
    drawSelectedFilters();
  }

  function searchPeriodLabel(filters = search) {
    const range = filters.transcript && filters.text_range !== 'time' ? '24h' : filters.range || '24h';
    return { '1h': 'Past hour', '24h': 'Past 24 hours', today: 'Today', '7d': 'Past 7 days',
      '30d': 'Past 30 days', '10y': 'Past 10 years', custom: 'Custom range' }[range] || 'Custom range';
  }

  function validateSearch(status = searchError, values = search, suggestions = selectedSuggestions) {
    const fail = message => { status?.replaceChildren(makeNotice(message, 'error')); return false; };
    if (values.site && !suggestions.has('site')) return fail('Choose a site from the list before searching.');
    const period = recordingDiscoveryPeriod(values);
    const first = Number(period.from_ms), last = Number(period.to_ms);
    if (!period.from_ms || !period.to_ms || !Number.isFinite(first) || !Number.isFinite(last) || last <= first || last - first > 3650 * 86_400_000)
      return fail('Choose a date range of up to 10 years, with the end after the start.');
    const min = Math.max(Number(values.min_duration_ms) || 0, values.high_interest ? 10_000 : 0);
    if (values.max_duration_ms && Number(values.max_duration_ms) < min)
      return fail(`Maximum duration must be at least ${min / 1000} seconds.`);
    for (const [key, title, minimum, maximum] of [['tone_frequency_hz', 'First tone', 1, 8000],
      ['tone_then_hz', 'Following tone', 1, 8000], ['tone_tolerance_hz', 'Tolerance', 0, 1000]]) {
      if (values[key] && (!Number.isFinite(Number(values[key])) || Number(values[key]) < minimum || Number(values[key]) > maximum))
        return fail(`${title} must be between ${minimum} and ${maximum} Hz.`);
    }
    if (values.tone_then_hz && !values.tone_frequency_hz) return fail('Enter the first tone before adding a following tone.');
    if (values.frequency_hz && (!Number.isFinite(Number(values.frequency_hz)) || Number(values.frequency_hz) <= 0))
      return fail('Enter a frequency greater than zero.');
    status?.replaceChildren(); return true;
  }

  function submitSearch() {
    if (!validateSearch()) return false;
    Object.assign(search, recordingDiscoveryPeriod(search));
    const submission = { filters: effectiveFilters(), search: { ...search }, suggestions: new Map(selectedSuggestions) };
    void loadPage(0, submission);
    return true;
  }

  function audioUrl(row) {
    return `${CALLS}/${encodeURIComponent(String(row.id))}/audio`;
  }

  const playbackObservers = new Set();
  const maximumSharedCalls = 100;
  let sharedQueueRequest = 0;
  let clickMode = 'queue';
  let queueEntrySequence = 0;
  let queueResume = null;

  function queueEntry(row) {
    return { ...row, _queueEntryKey: `recording-entry-${++queueEntrySequence}` };
  }

  function queuedIndex(state, reference) {
    const key = String(reference);
    return state.queue.findIndex((row) => row._queueEntryKey === key || String(row.id) === key);
  }

  function recordedPlayer() {
    if (player) return player;
    const audio = new Audio();
    audio.preload = 'metadata';
    player = { audio, current: null, queue: [], history: [], mode: 'idle', continuation: null,
      loading: false, pendingStart: false, stopped: true, token: 0, detailsToken: 0, detailsLoading: false, detailsError: '', status: '' };
    ['timeupdate', 'loadedmetadata', 'durationchange', 'play', 'pause', 'volumechange']
      .forEach((event) => audio.addEventListener(event, drawQueue));
    audio.addEventListener('ended', () => void advance());
    audio.addEventListener('error', () => {
      if (!player?.current) return;
      player.status = 'This recording could not be played. Moving to the next call.';
      void advance();
    });
    return player;
  }

  function playbackState() {
    const state = recordedPlayer();
    const measuredDuration = Number(state.audio.duration);
    const callDuration = Number(value(state.current, 'duration_ms', 'call_duration_ms')) / 1000;
    const total = Number.isFinite(measuredDuration) && measuredDuration > 0 ? measuredDuration :
      (Number.isFinite(callDuration) && callDuration > 0 ? callDuration : 0);
    return { current: state.current, queue: [...state.queue], history: [...state.history],
      playing: Boolean(state.current && !state.stopped && !state.audio.paused),
      paused: Boolean(state.current && !state.stopped && state.audio.paused), stopped: state.stopped, loading: state.loading,
      status: state.status, duration: total, currentTime: Number(state.audio.currentTime) || 0,
      volume: state.audio.volume, mode: state.mode, clickMode,
      continuation: Boolean(state.continuation && !state.continuation.done),
      canNext: Boolean(state.queue.length || (state.continuation && !state.continuation.done)),
      canPrevious: Boolean(state.history.length), detailsLoading: state.detailsLoading,
      detailsError: state.detailsError };
  }

  function drawQueue() {
    if (!playbackObservers.size && !resultHost?.isConnected) return;
    const snapshot = playbackState();
    drawResultPlayback(snapshot);
    playbackObservers.forEach((observer) => {
      try { observer(snapshot); } catch (_error) { /* Playback must outlive its presentation. */ }
    });
  }

  function subscribeState(observer) {
    if (typeof observer !== 'function') return () => {};
    playbackObservers.add(observer);
    observer(playbackState());
    return () => playbackObservers.delete(observer);
  }

  async function toggleAudio() {
    const state = recordedPlayer();
    if (state.current) {
      if (state.audio.paused || state.stopped) {
        const token = state.token;
        const current = state.current;
        const currentKey = current._queueEntryKey || String(current.id);
        await stopLiveAudio();
        if (token !== state.token || currentKey !== (state.current?._queueEntryKey || String(state.current?.id))) return;
        if (state.stopped) {
          state.audio.src = audioUrl(current);
          state.audio.currentTime = 0;
          state.stopped = false;
        }
        state.status = state.mode === 'continue' && state.continuation ? 'Continuing through matching calls' : '';
        try { await state.audio.play(); }
        catch (_error) {
          if (state.token === token) state.status = 'Playback could not start. Try again.';
        }
      } else state.audio.pause();
      drawQueue();
      return;
    }
    await advance();
  }

  function stopAudio() {
    sharedQueueRequest++;
    if (!player) return;
    const state = player;
    state.token++;
    state.audio.pause();
    state.audio.removeAttribute('src');
    state.audio.load();
    state.continuation = null;
    state.loading = false;
    state.pendingStart = false;
    state.stopped = true;
    state.status = 'Stopped.';
    drawQueue();
  }

  function resetAudio() {
    stopAudio();
    if (!player) return;
    player.detailsToken++;
    player.current = null;
    player.queue = [];
    player.history = [];
    player.mode = 'idle';
    player.detailsLoading = false;
    player.detailsError = '';
    player.status = '';
    drawQueue();
  }

  async function loadCurrentDetails(state, row) {
    const token = ++state.detailsToken;
    state.detailsLoading = true;
    state.detailsError = '';
    drawQueue();
    try {
      const result = await requestJson(`${CALLS}/${encodeURIComponent(String(row.id))}`, { page: false });
      if (token !== state.detailsToken || String(state.current?.id) !== String(row.id)) return;
      state.current = { ...row, ...(result?.call || result || {}),
        ...(result?.transcription ? { transcription: result.transcription } : {}) };
    } catch (_error) {
      if (token === state.detailsToken) state.detailsError = 'Call details are unavailable. Try this call again.';
    } finally {
      if (token === state.detailsToken) {
        state.detailsLoading = false;
        drawQueue();
      }
    }
  }

  function loadContinuation(state) {
    const continuation = state.continuation;
    if (!continuation || continuation.done) return Promise.resolve();
    if (continuation.loading) return continuation.request;
    continuation.loading = true;
    continuation.request = (async () => {
      try {
        const result = await requestJson(query(continuation.filters, {
          from_ms: continuation.fromMs, sort: 'asc', cursor: continuation.cursor
        }), { page: false });
        if (state.continuation !== continuation) return;
        for (const row of Array.isArray(result?.calls) ? result.calls : []) {
          if (!afterCall(row, continuation.anchor)) continue;
          if (continuation.seen.has(callKey(row))) continue;
          continuation.seen.add(callKey(row));
          state.queue.push(queueEntry(row));
        }
        continuation.cursor = result?.next_cursor || null;
        continuation.done = !continuation.cursor;
        if (continuation.done && !state.queue.length) state.continuation = null;
        drawQueue();
      } catch (_error) {
        if (state.continuation === continuation) {
          continuation.done = true;
          state.status = 'Could not load the next matching calls.';
          drawQueue();
        }
      } finally {
        continuation.loading = false;
      }
    })();
    return continuation.request;
  }

  async function advance({ remember = true } = {}) {
    const state = recordedPlayer();
    if (state.loading) return;
    const token = state.token;
    state.loading = true;
    state.pendingStart = true;
    drawQueue();
    try {
      state.audio.pause();
      while (!state.queue.length && state.continuation) {
        await loadContinuation(state);
        if (token !== state.token) return;
        if (state.continuation?.loading || state.continuation?.done) break;
      }
      const next = state.queue[0];
      if (!next) {
        state.stopped = true;
        state.continuation = null;
        state.audio.removeAttribute('src');
        state.audio.load();
        if (state.status !== 'Could not load the next matching calls.') state.status = 'Playback finished.';
        drawQueue();
        return;
      }
      await stopLiveAudio();
      if (token !== state.token) return;
      state.queue.shift();
      if (remember && state.current) {
        state.history.push(state.current);
        if (state.history.length > maximumSharedCalls) state.history.shift();
      }
      state.current = next;
      state.pendingStart = false;
      state.stopped = false;
      state.status = state.mode === 'continue' ? 'Continuing through matching calls' : '';
      state.audio.src = audioUrl(next);
      state.audio.currentTime = 0;
      void loadCurrentDetails(state, next);
      drawQueue();
      try { await state.audio.play(); }
      catch (_error) {
        if (token === state.token) state.status = 'Playback could not start. Press Play to retry.';
      }
      if (token === state.token && state.continuation && state.queue.length < 8) void loadContinuation(state);
    } finally {
      if (token === state.token) {
        state.loading = false;
        state.pendingStart = false;
        drawQueue();
      }
    }
  }

  function playbackAction(row, action = 'once') {
    if (!row || row.id === null || row.id === undefined) return;
    const state = recordedPlayer();
    sharedQueueRequest++;
    if (action === 'queue') {
      state.queue.push(queueEntry(row));
      state.mode = state.mode === 'idle' ? 'queue' : state.mode;
      state.status = 'Call added to the recording queue.';
      drawQueue();
      return;
    }
    state.token++;
    state.detailsToken++;
    state.audio.pause();
    state.current = null;
    state.queue = [queueEntry(row)];
    state.history = [];
    state.loading = false;
    state.pendingStart = false;
    state.detailsLoading = false;
    state.detailsError = '';
    state.continuation = action === 'continue' ? {
      filters: { ...currentFilters }, anchor: row, fromMs: millis(row), cursor: null,
      seen: new Set([callKey(row)]), loading: false, done: false
    } : null;
    state.mode = action;
    drawQueue();
    void advance();
  }

  function playClicked(row) {
    if (!row || row.id === null || row.id === undefined) return;
    if (clickMode !== 'queue') return playbackAction(row, clickMode);
    playbackAction(row, 'queue');
    const state = recordedPlayer();
    if (state.loading) return;
    if (!state.current || state.stopped) {
      void advance();
      return;
    }
    if (!state.audio.paused) return;
    const token = state.token;
    const currentKey = state.current._queueEntryKey || String(state.current.id);
    if (queueResume?.token === token && queueResume.currentKey === currentKey) return;
    const resume = { token, currentKey };
    queueResume = resume;
    void toggleAudio().catch(() => {
      if (state.token === token && (state.current?._queueEntryKey || String(state.current?.id)) === currentKey) {
        state.status = 'Playback could not start. Press Play to retry.';
        drawQueue();
      }
    }).finally(() => {
      if (queueResume === resume) queueResume = null;
    });
  }

  function seekAudio(seconds) {
    const state = recordedPlayer();
    const target = Number(seconds);
    const total = playbackState().duration;
    if (!state.current || !Number.isFinite(target) || !total) return;
    state.audio.currentTime = Math.max(0, Math.min(total, target));
    drawQueue();
  }

  function previousAudio() {
    const state = recordedPlayer();
    const previous = state.history.pop();
    if (!previous) return;
    state.token++;
    state.loading = false;
    state.pendingStart = false;
    if (state.current) state.queue.unshift(state.current);
    state.queue.unshift(previous);
    void advance({ remember: false });
  }

  function clearQueue() {
    const state = recordedPlayer();
    sharedQueueRequest++;
    state.token++;
    state.loading = false;
    state.pendingStart = false;
    state.queue = [];
    state.continuation = null;
    state.status = 'Recording queue cleared.';
    drawQueue();
  }

  function queueHref() {
    const state = recordedPlayer();
    const ids = [state.current, ...state.queue].filter(Boolean).map((row) => String(row.id));
    if (!ids.length || ids.length > maximumSharedCalls || ids.some((id) => !/^[1-9]\d{0,18}$/.test(id))) return null;
    return href('recordings', { recording_queue: ids.join(',') });
  }

  async function loadSharedQueue(params) {
    const raw = typeof params === 'string' ? new URLSearchParams(params).get('recording_queue') :
      params?.get ? params.get('recording_queue') : params?.recording_queue;
    if (!raw) return false;
    const ids = String(raw).split(',');
    if (ids.length > maximumSharedCalls || ids.some((id) => !/^[1-9]\d{0,18}$/.test(id))) return false;
    const token = ++sharedQueueRequest;
    const state = recordedPlayer();
    state.status = 'Loading shared recording queue…';
    drawQueue();
    const rows = [];
    let unavailable = 0;
    // Bound concurrent detail reads as well as the URL's call count.
    for (let offset = 0; offset < ids.length; offset += 5) {
      const results = await Promise.allSettled(ids.slice(offset, offset + 5).map(async (id) => {
        const detail = await requestJson(`${CALLS}/${encodeURIComponent(id)}`, { page: false });
        const call = detail?.call || detail;
        if (!call || String(call.id) !== id) throw new Error('Recording unavailable');
        return { ...call, ...(detail?.transcription ? { transcription: detail.transcription } : {}) };
      }));
      if (token !== sharedQueueRequest) return false;
      results.forEach((result) => {
        if (result.status === 'fulfilled') rows.push(result.value);
        else unavailable++;
      });
    }
    state.queue.push(...rows.map(queueEntry));
    state.mode = state.mode === 'idle' ? 'queue' : state.mode;
    state.status = unavailable ? `${rows.length} calls queued; ${unavailable} calls are unavailable.` :
      `${rows.length} calls added to the recording queue. Press Play to start.`;
    drawQueue();
    return rows.length > 0;
  }

  const playback = {
    subscribeState, viewState: playbackState, toggle: toggleAudio, next: advance, previous: previousAudio,
    stop: stopAudio, reset: resetAudio, seek: seekAudio, skipSeconds: (seconds) => seekAudio(playbackState().currentTime + Number(seconds)),
    setVolume: (volume) => {
      if (!Number.isFinite(Number(volume))) return;
      recordedPlayer().audio.volume = Math.max(0, Math.min(1, Number(volume)));
      drawQueue();
    },
    clearQueue,
    setClickMode: (mode) => {
      if (['queue', 'once', 'continue'].includes(mode)) { clickMode = mode; drawQueue(); }
    },
    queueHref, loadSharedQueue, renderDetails, renderTranscript, play: playbackAction, playClicked,
    removeQueued: (reference) => {
      const state = recordedPlayer();
      const index = queuedIndex(state, reference);
      if (index < 0) return;
      const restart = state.loading && state.pendingStart;
      if (restart) {
        state.token++;
        state.loading = false;
        state.pendingStart = false;
      }
      state.queue.splice(index, 1);
      drawQueue();
      if (restart) void advance();
    },
    playQueued: (reference) => {
      const state = recordedPlayer();
      const index = queuedIndex(state, reference);
      if (index < 0) return;
      // Invalidate an in-flight start before reordering. It must neither consume
      // the selected row nor clear the loading state of the replacement start.
      state.token++;
      state.loading = false;
      state.pendingStart = false;
      const [row] = state.queue.splice(index, 1);
      state.queue.unshift(row);
      void advance();
    }
  };

  function fileSize(bytes) {
    if (bytes === null || bytes === undefined || bytes === '') return '';
    const count = Number(bytes);
    if (!Number.isFinite(count) || count < 0) return '';
    return count < 1024 ? `${count} bytes` : count < 1_048_576 ? `${(count / 1024).toFixed(1)} KB` :
      `${(count / 1_048_576).toFixed(1)} MB`;
  }

  function receivedSites(row) {
    const sites = value(row, 'also_received_on', 'also_received_sites');
    if (!Array.isArray(sites) || !sites.length) return '';
    const list = node('span', '');
    sites.forEach((site) => {
      const text = siteText(site);
      if (!text) return;
      if (list.childNodes.length) list.append(', ');
      list.append(entityLink(text, site?.entity_ref, { ...row, audio_from: site }, 'site'));
    });
    return list.childNodes.length ? list : '';
  }

  function patchMembers(row) {
    const members = value(row, 'patch_members', 'patch_talkgroups');
    if (!Array.isArray(members) || !members.length) return '';
    const list = node('span', '');
    members.forEach((member) => {
      const id = typeof member === 'object' ? value(member, 'id', 'talkgroup_id') : member;
      const name = typeof member === 'object' ? value(member, 'alias', 'name', 'ota_alias') : null;
      if (id === null && !name) return;
      if (list.childNodes.length) list.append(', ');
      const kind = String(member?.kind || member?.form || '').toLowerCase();
      const radio = kind === 'radio';
      const display = recordingMemberId(row, member, id);
      const text = name ? `${name}${display != null ? ` · ${display}` : ''}` : String(display);
      list.append(entityLink(text, member?.entity_ref, { ...row,
        ...(radio ? { destination_radio_id: id, talkgroup_id: null } : { talkgroup_id: id }),
        target_home_wacn: member?.home_wacn, target_home_system_id: member?.home_system_id,
        target_home_id: member?.home_identity_id }, 'member'));
    });
    return list.childNodes.length ? list : '';
  }

  function factGroups(row, { includeEmpty = false, empty = false } = {}) {
    const group = value(row, 'call_type') === 'PATCH' ? 'Patch' : 'Talkgroup';
    const target = label(row);
    const source = sourceLabel(row, getSourceNameDisplay());
    const savedChannel = value(row, 'channel_name', 'analog_channel_name') ||
      (row?.channel_entity_ref ? 'Open channel' : null);
    const groups = [
      ['', [
        ['Target', target, 'target_entity_ref'], ['Source', source, 'source_entity_ref'],
        ['Latest OTA name', value(row, 'source_ota_alias', 'source_ota_ta', 'ota_alias', 'talker_alias'), 'source_entity_ref'],
        ['Radio system', systemLabel(row), 'radio_system_entity_ref'],
        ['Saved channel', savedChannel, 'channel_entity_ref'],
        ['Winning site', winningSite(row), 'site_entity_ref'], ['Also received on', receivedSites(row)],
        ['Started', dateTime(millis(row))], ['Duration', duration(row)],
        ['Call type', prettify(value(row, 'call_type'))], ['Voice type', prettify(value(row, 'voice_type'))],
        ['Decoder', protocolLabel(value(row, 'protocol'))], ['Frequency', frequency(row)],
        ['Timeslot', value(row, 'timeslot')],
        ['Encrypted', typeof row?.encrypted === 'boolean' ? (row.encrypted ? 'Yes' : 'No') : null],
        ['Alias List', value(row, 'alias_list_name', 'alias_list'), 'alias_list_entity_ref'],
        ['Matched Scan Lists', Array.isArray(row?.matched_scan_lists) ? row.matched_scan_lists.map((item) =>
          typeof item === 'string' ? item : value(item, 'name', 'id')).filter(Boolean).join(', ') : null]
      ]],
      ['Source & target identities', [
        ['Source Radio ID', recordingRadioId(row, 'source', value(row, 'source_id', 'radio_id')), 'source_entity_ref'],
        ['Source alias', value(row, 'source_alias', 'radio_alias'), 'source_entity_ref'],
        ['Source group', value(row, 'source_group', 'radio_group')],
        ['Source description', value(row, 'source_description', 'radio_description')],
        [`${group} ID`, recordingGroupId(row, 'target', value(row, 'talkgroup_id', 'group_id')), 'target_entity_ref'],
        [`${group} alias`, value(row, 'talkgroup_alias', 'group_alias', 'talkgroup_name'), 'target_entity_ref'],
        [`${group} group`, value(row, 'talkgroup_group', 'group_group')],
        [`${group} description`, value(row, 'talkgroup_description', 'group_description')],
        ['Destination Radio ID', recordingRadioId(row, 'target', value(row, 'destination_radio_id')), 'target_entity_ref'],
        ['Destination alias', value(row, 'destination_radio_alias', 'destination_radio_ota_alias'), 'target_entity_ref'],
        ['Destination group', value(row, 'destination_radio_group')],
        ['Destination description', value(row, 'destination_radio_description')], ['Patch members', patchMembers(row)]
      ]],
      ['Protocol & signaling', [
        ['WACN', formatHexDecimal(value(row, 'wacn'), 5)],
        ['SysID', formatHexDecimal(value(row, 'system_id', 'sysid'), 3)],
        ['Network ID', value(row, 'network_id', 'network')],
        ['Model', value(row, 'model', 'network_model')],
        ['Location category', value(row, 'location_category')],
        ['RFSS', formatHexDecimal(value(row, 'rfss_id', 'rfss'), 2)],
        ['Site ID', formatHexDecimal(value(row, 'site_id'), 2)],
        ['NAC', formatHexDecimal(value(row, 'nac'), 3)], ['Tone kind', value(row, 'tone_kind')],
        ['Tone', value(row, 'tone')], ['PL', value(row, 'pl')], ['DPL', value(row, 'dpl')]
      ]],
      ['Recording identifiers', [
        ['Recording ID', value(row, 'id')], ['Call ID', value(row, 'call_id')],
        ['Ended', dateTime(Number(value(row, 'end_ms', 'ended_at_ms')))],
        ['File size', fileSize(value(row, 'size_bytes', 'file_size_bytes'))],
        ['System key', value(row, 'system_key')], ['Channel ID', value(row, 'channel_id'), 'channel_entity_ref'],
        ['Alias List ID', value(row, 'alias_list_id'), 'alias_list_entity_ref'],
        ['Raw target ID', value(row, 'target_id'), 'target_entity_ref'],
        ['Source home system name', homeSystemName(row, 'source'),
          row?.source_home_system?.entity_ref],
        ['Source home WACN', formatHexDecimal(value(row, 'source_home_wacn'), 5)],
        ['Source home system', formatHexDecimal(value(row, 'source_home_system_id'), 3)],
        ['Source home identity', value(row, 'source_home_id'), 'source_entity_ref'],
        ['Target home system name', homeSystemName(row, 'target'),
          row?.target_home_system?.entity_ref],
        ['Target home WACN', formatHexDecimal(value(row, 'target_home_wacn'), 5)],
        ['Target home system', formatHexDecimal(value(row, 'target_home_system_id'), 3)],
        ['Target home identity', value(row, 'target_home_id'), 'target_entity_ref']
      ]]
    ];
    if (Array.isArray(row.auxiliary_identifiers) && row.auxiliary_identifiers.length) {
      groups.push(['Additional signaling', row.auxiliary_identifiers.filter(item => item && item.value != null).map(item => [
        `${protocolLabel(item.protocol)} ${item.role && item.role !== 'other' ? `${item.role} ` : ''}${item.kind || 'identifier'}`,
        item.label ? `${item.label} · ${item.value}` : String(item.value),
        item.kind === 'radio' ? { kind: 'radio', auxiliary: true, protocol: item.protocol,
          value: item.value, role: item.role } : null
      ])]);
    }
    const sites = [value(row, 'audio_from'), ...(Array.isArray(value(row, 'also_received_on', 'also_received_sites')) ?
      value(row, 'also_received_on', 'also_received_sites') : [])].filter((site) => site && typeof site === 'object');
    if (sites.length) groups.push(['Received site identities', sites.flatMap((site, index) => {
      const prefix = index === 0 && row.audio_from ? 'Winning site' : `Received site ${index + (row.audio_from ? 0 : 1)}`;
      return [[`${prefix} name`, siteText(site), 'site_entity_ref', { ...row, audio_from: site }],
        [`${prefix} system name`, systemName(site) || systemName(site.system_identity),
          site.radio_system_entity_ref || site.system_identity?.entity_ref],
        [`${prefix} WACN`, formatHexDecimal(value(site, 'wacn'), 5)],
        [`${prefix} SysID`, formatHexDecimal(value(site, 'system_id', 'sysid'), 3)],
        [`${prefix} RFSS`, formatHexDecimal(value(site, 'rfss_id', 'rfss'), 2)],
        [`${prefix} ID`, formatHexDecimal(value(site, 'site_id'), 2)]];
    })]);
    const members = value(row, 'patch_members', 'patch_talkgroups');
    if (Array.isArray(members) && members.length) groups.push(['Patch member identities', members.flatMap((member, index) => {
      if (!member || typeof member !== 'object') return [];
      const prefix = `Member ${index + 1}`;
      return [[`${prefix} kind`, prettify(value(member, 'kind'))],
        [`${prefix} ID`, recordingMemberId(row, member, value(member, 'id', 'talkgroup_id')), member.entity_ref],
        [`${prefix} alias`, value(member, 'alias', 'name', 'ota_alias'), member.entity_ref],
        [`${prefix} home system name`, homeSystemName(member),
          member.home_system?.entity_ref],
        [`${prefix} home WACN`, formatHexDecimal(value(member, 'home_wacn'), 5)],
        [`${prefix} home system`, formatHexDecimal(value(member, 'home_system_id'), 3)],
        [`${prefix} home identity`, value(member, 'home_identity_id'), member.entity_ref]];
    })]);
    return groups.map(([title, facts]) => [title, facts.filter(([, detail]) => includeEmpty ||
      detail !== null && detail !== undefined && detail !== '').map(([name, detail, reference, context]) => [name,
      empty || detail === null || detail === undefined || detail === '' ? '' :
        reference ? entityLink(detail, typeof reference === 'string' ? row?.[reference] : reference, context || row,
          typeof reference === 'string' ? reference : name) : detail])])
      .filter(([, facts]) => facts.length);
  }

  function renderDetails(host, call = player?.current, { shell = false, empty = false } = {}) {
    host.replaceChildren();
    if (!call && !shell) {
      host.append(makeNotice('Choose a recording to see its call details.'));
      return;
    }
    factGroups(call || {}, { includeEmpty: shell, empty }).forEach(([title, facts]) => {
      const list = node('dl', 'ui-fact-list audio-dock-facts');
      facts.forEach(([name, detail]) => {
        if (shell && (detail === '' || detail === null || detail === undefined)) {
          list.append(node('dt', '', name), node('dd', '', ''));
        } else appendFact(node, list, name, detail);
      });
      if (!title) host.append(list);
      else {
        list.classList.add('ui-form-section');
        const group = node('details', 'ui-section-disclosure');
        group.append(node('summary', 'ui-section-summary', title), list);
        host.append(group);
      }
    });
    if (empty || !call) return;
    if (String(player?.current?.id) === String(call.id)) {
      if (player.detailsLoading) host.append(makeNotice('Loading complete call details…', 'loading'));
      else if (player.detailsError) host.append(makeNotice(player.detailsError, 'error'));
    }
    const actions = node('div', 'ui-action-row');
    const download = anchor('Download recording', audioUrl(call), 'ui-button ui-button-secondary');
    download.setAttribute('download', '');
    actions.append(download);
    host.append(actions);
  }

  function renderTranscript(host, call = player?.current) {
    host.replaceChildren();
    if (!call) { host.append(makeNotice('Choose a recording to see its transcript.')); return; }
    const transcript = call.transcription;
    if (!transcript && player?.detailsLoading && String(player.current?.id) === String(call.id)) {
      host.append(makeNotice('Loading transcript…', 'loading'));
      return;
    }
    if (!transcript && player?.detailsError && String(player.current?.id) === String(call.id)) {
      host.append(makeNotice(player.detailsError, 'error'));
      return;
    }
    const state = String(transcript?.status || 'PENDING').toUpperCase();
    const message = state === 'COMPLETE' ?
      (transcript?.text ? `Transcribed ${dateTime(transcript.stored_at_ms)}` : 'No speech was returned.') :
      state === 'FAILED' ? 'Transcription failed.' : state === 'DISABLED' ? 'Transcription is off.' :
      state === 'TOO_SHORT' || transcript?.too_short ? 'Call is shorter than the minimum length.' :
      state === 'PENDING' ? 'Pending transcription.' : prettify(state);
    host.append(node('p', 'recordings-transcript-status', message));
    if (state === 'COMPLETE' && transcript?.text) {
      host.append(node('p', 'recordings-transcript-text', String(transcript.text)));
    }
    if (state !== 'FAILED' || !isPrimaryAdmin()) return;
    const retryStatus = node('div', 'recordings-form-status');
    retryStatus.setAttribute('role', 'status');
    const retry = button(node, 'Retry transcription', async () => {
      retry.disabled = true;
      try {
        const result = await requestJson(`${ADMIN}/calls/${encodeURIComponent(String(call.id))}/transcription/retry`,
          { method: 'POST', page: false });
        if (String(player?.current?.id) === String(call.id)) {
          player.current = { ...player.current, transcription: result };
          drawQueue();
        }
        if (host.isConnected) renderTranscript(host, { ...call, transcription: result });
      } catch (error) {
        if (host.isConnected) {
          retryStatus.replaceChildren(makeNotice(error.message || 'Transcription could not be retried. Try again.', 'error'));
          retry.disabled = false;
        }
      }
    });
    host.append(retry, retryStatus);
  }

  async function openDetails(row) {
    const body = node('div', 'recordings-detail');
    body.append(makeNotice('Loading call details…', 'loading'));
    const modal = openReadOnlyModal('Call details', body, { id: `recording-${row.id}`, className: 'recordings-detail-modal' });
    if (!modal || (modal.ready && !await modal.ready)) return;
    try {
      const detail = await requestJson(`${CALLS}/${encodeURIComponent(String(row.id))}`, { page: false });
      if (!modal.dialog.isConnected) return;
      const call = { ...row, ...(detail?.call || detail || {}),
        ...(detail?.transcription ? { transcription: detail.transcription } : {}) };
      const heading = node('div', 'recordings-detail-heading');
      heading.append(node('h3', '', label(call)), node('span', '', dateTime(millis(call))));
      const tabs = node('nav', 'ui-segmented recordings-detail-tabs');
      tabs.setAttribute('role', 'tablist');
      tabs.setAttribute('aria-label', 'Call information');
      const definitions = [['overview', 'Overview'], ['transcript', 'Transcript'],
        ['identities', 'Identities'], ['technical', 'Technical'], ['recording', 'Recording']];
      const panels = new Map();
      const controls = new Map();
      const activate = (id) => {
        for (const [key, panel] of panels) {
          panel.hidden = key !== id;
          controls.get(key).setAttribute('aria-selected', String(key === id));
          controls.get(key).tabIndex = key === id ? 0 : -1;
        }
      };
      for (const [id, title] of definitions) {
        const panel = node('section', 'ui-editor-sections recordings-detail-facts recordings-detail-panel');
        if (id === 'transcript') panel.classList.add('recordings-transcript');
        panel.id = `recording-${row.id}-panel-${id}`;
        panel.setAttribute('role', 'tabpanel');
        const tab = button(node, title, () => activate(id), 'ui-segmented-option');
        tab.id = `recording-${row.id}-tab-${id}`;
        tab.setAttribute('role', 'tab');
        tab.setAttribute('aria-controls', panel.id);
        panel.setAttribute('aria-labelledby', tab.id);
        tab.addEventListener('keydown', (event) => {
          if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
          event.preventDefault();
          const index = definitions.findIndex(([key]) => key === id);
          const next = event.key === 'Home' ? 0 : event.key === 'End' ? definitions.length - 1 :
            (index + (event.key === 'ArrowRight' ? 1 : -1) + definitions.length) % definitions.length;
          const key = definitions[next][0];
          activate(key);
          modal.focus(controls.get(key));
        });
        panels.set(id, panel);
        controls.set(id, tab);
        tabs.append(tab);
      }
      const locations = { 'Source & target identities': 'identities', 'Patch member identities': 'identities',
        'Protocol & signaling': 'technical', 'Additional signaling': 'technical', 'Received site identities': 'technical',
        'Audio': 'technical', 'Tones': 'technical',
        'Recording identifiers': 'recording' };
      for (const [title, facts] of [...factGroups(call), ...callAudioDetailGroups(call)]) {
        const group = node('section', 'ui-form-section');
        if (title) group.append(node('h4', 'ui-section-heading', title));
        const list = node('dl', 'ui-fact-list');
        facts.forEach(([name, detail]) => appendFact(node, list, name, detail));
        group.append(list);
        panels.get(locations[title] || 'overview').append(group);
      }
      renderTranscript(panels.get('transcript'), call);
      for (const [id, panel] of panels) {
        if (!panel.childNodes.length) panel.append(makeNotice(
          `No ${id === 'identities' ? 'identity' : id} details were recorded for this call.`));
      }
      const download = addGlyph(anchor('Download recording', audioUrl(call), 'ui-button ui-button-secondary'), 'icon-download');
      download.setAttribute('download', '');
      const playAfterClose = (mode) => {
        let started = false;
        const start = () => { if (!started) { started = true; playbackAction(call, mode); } };
        if (modal.close(start)) start();
      };
      const playbackActions = node('div', 'recordings-detail-playback-actions');
      playbackActions.setAttribute('role', 'group');
      playbackActions.setAttribute('aria-label', 'Playback actions');
      playbackActions.append(
        addGlyph(button(node, 'Play once', () => playAfterClose('once'), 'ui-button ui-button-primary'), 'icon-play'),
        addGlyph(button(node, 'Continue from here', () => playAfterClose('continue')), 'icon-forward'),
        addGlyph(button(node, 'Add to queue', () => playAfterClose('queue')), 'icon-plus'));
      download.classList.add('recordings-detail-download');
      const footer = modalFooter(playbackActions, download);
      footer.classList.add('recordings-detail-footer');
      activate('overview');
      body.replaceChildren(heading, tabs, ...panels.values(), footer);
      modal.focus(controls.get('overview'));
    } catch (error) {
      if (modal.dialog.isConnected) body.replaceChildren(makeNotice(error.message ||
        'Call details are unavailable. Refresh the results and try again.', 'error'));
    }
  }

  function openCallActions(row) {
    const body = node('div', 'recordings-call-menu');
    body.append(node('p', 'recordings-dialog-help', `${dateTime(millis(row))} · ${protocolLabel(row.protocol)} · ${systemLabel(row)}`));
    let modal;
    let actionIndex = 0;
    const run = perform => { let done = false; const next = () => { if (!done) { done = true; perform(); } }; if (modal.close(next)) next(); };
    const playback = node('div', 'recordings-call-menu-grid recordings-playback-grid');
    const actions = node('div', 'recordings-call-menu-grid');
    const explain = (control, title, description, icon) => {
      const copy = node('span', 'recordings-action-copy');
      const help = node('span', 'recordings-action-description', description);
      help.id = `recording-${row.id}-action-description-${++actionIndex}`;
      copy.append(node('strong', 'recordings-action-title', title), help);
      control.replaceChildren(copy);
      control.setAttribute('aria-label', title);
      control.setAttribute('aria-describedby', help.id);
      return addGlyph(control, icon);
    };
    for (const [mode, title, description, icon] of [
      ['once', 'Play once', 'Replace the queue and play only this call.', 'icon-play'],
      ['continue', 'Continue from here', 'Replace the queue and play this call followed by newer calls that match your search.', 'icon-forward'],
      ['queue', 'Add to queue', 'Add this call to the end of the queue without starting or interrupting playback.', 'icon-plus']])
      playback.append(explain(button(node, title, () => run(() => playbackAction(row, mode)), 'recordings-secondary-action'), title, description, icon));
    actions.append(explain(button(node, 'Call details', () => run(() => void openDetails(row)), 'recordings-secondary-action'),
      'Call details', 'View the transcript and recording details for this call.', 'icon-info-circle'));
    if (openCallRelations) actions.append(explain(button(node, 'Related activity and alerts',
      () => run(() => openCallRelations('recording', row.id)), 'recordings-secondary-action'),
      'Related activity and alerts', 'View activity and alerts saved for this recorded call.', 'icon-open-details'));
    const use = (title, reference, field, description, icon) => {
      const filter = recordingResultFilter(row, reference, field);
      if (!filter) return;
      const filterLabel = field === 'source' ? sourceLabel(row, getSourceNameDisplay()) : label(row);
      const link = entityLink(title, reference, row, field, filterLabel);
      if (typeof link !== 'string') {
        link.classList.add('recordings-secondary-action');
        actions.append(explain(link, title, description, icon));
      }
    };
    const hasGroup = recordingHasTalkgroup(row);
    use(hasGroup ? 'Search this talkgroup' : 'Search this channel', hasGroup ? row.target_entity_ref : row.channel_entity_ref,
      hasGroup ? 'target' : 'channel', hasGroup ? 'Show calls on this talkgroup within your current search.' :
        'Show calls recorded on this channel within your current search.', hasGroup ? 'icon-identities' : 'icon-channel');
    const sourceRef = recordingSourceReference(row);
    use('Search this radio', sourceRef, 'source', 'Show calls involving this radio within your current search.', 'icon-scanner');
    const source = recordingResultFilter(row, sourceRef, 'source');
    const target = recordingResultFilter(row, hasGroup ? row.target_entity_ref : row.channel_entity_ref, hasGroup ? 'target' : 'channel');
    const bothTitle = hasGroup ? 'Search talkgroup + radio' : 'Search channel + radio';
    if (source && target) actions.append(explain(button(node, bothTitle, () => run(() => {
      Object.assign(search, appliedSearch || search, resultSearchFilters(target, 'target'), resultSearchFilters(source, 'source')); replaceSearchForm(); submitSearch();
    }), 'recordings-secondary-action'), bothTitle, hasGroup ? 'Show calls involving this radio on this talkgroup.' :
      'Show calls involving this radio on this channel.', 'icon-search'));
    actions.append(explain(button(node, 'Open context · ±30 min', () => run(() => {
      Object.keys(search).forEach(key => { search[key] = ''; }); selectedSuggestions.clear();
      Object.assign(search, target?.filters || {}, { transcript: '', text_range: 'time', range: 'custom',
        from_ms: String(millis(row) - 1_800_000), to_ms: String(millis(row) + 1_800_000) });
      replaceSearchForm(); submitSearch();
    }), 'recordings-secondary-action'), 'Open context · ±30 min', target ?
      `Show this ${hasGroup ? 'talkgroup’s' : 'channel’s'} calls from 30 minutes before to 30 minutes after this call.` :
      'Show calls from 30 minutes before to 30 minutes after this call.', 'icon-replay'));
    if (canViewRadio?.()) {
      const entityNames = { talkgroup: 'talkgroup', patch_group: 'patch group', radio: 'radio', channel: 'channel' };
      for (const reference of [row.target_entity_ref, row.channel_entity_ref]) {
        const name = entityNames[reference?.kind];
        const destination = name && entityRefHref?.(reference);
        if (!destination) continue;
        const title = `Open ${name}`;
        actions.append(explain(anchor(title, destination, 'recordings-secondary-action'), title,
          `View this ${name}’s details page.`, 'icon-open-details'));
        break;
      }
    }
    const channel = row.channel_id || row.channel_entity_ref?.key;
    actions.append(explain(anchor('Go to Live', href('live', channel ? { channel } : {}), 'recordings-secondary-action'),
      'Go to Live', channel ? 'Open this channel on the Live page.' : 'Open the Live page to browse current traffic.', 'icon-live'));
    for (const [title, key, grid] of [['Playback', 'playback', playback], ['Explore this call', 'explore', actions]]) {
      const section = node('section', 'recordings-call-action-section');
      const heading = node('h3', 'ui-section-heading', title);
      heading.id = `recording-${row.id}-${key}-actions-title`;
      section.setAttribute('aria-labelledby', heading.id);
      section.append(heading, grid);
      body.append(section);
    }
    modal = openReadOnlyModal('Call actions', body, { id: 'recordings-call-actions', className: 'recordings-actions-modal' });
  }

  function callCard(row) {
    const card = node('article', 'recordings-call'); card.dataset.recordingId = String(row.id);
    const action = (icon, glyph, labelText, hint, perform, className) => {
      const control = iconButton ? iconButton(icon, labelText, className) : button(node, glyph, null, className);
      control.setAttribute('aria-label', labelText); control.title = hint; control.addEventListener('click', perform); return control;
    };
    const description = `${label(row)} at ${dateTime(millis(row))}`;
    card.setAttribute('aria-label', description);
    const play = action('icon-play', '▶', `Play ${description}`, 'Play recording', () => playClicked(row), 'ui-button ui-icon-button recordings-card-play recordings-card-action');
    const detail = action('icon-info-circle', 'i', 'Call details', 'Call details', () => void openDetails(row), 'ui-button ui-icon-button recordings-card-action recordings-call-details');
    const info = action('icon-more', '…', `More actions for ${description}`, 'Call actions', () => openCallActions(row), 'ui-button ui-icon-button recordings-call-info recordings-card-action');
    const head = node('div', 'recordings-call-head');
    const time = node('div', 'recordings-call-time'); time.title = dateTime(millis(row)); time.append(node('strong', '', timeOnly(millis(row))));
    const groupId = value(row, 'talkgroup_id', 'group_id');
    const hasGroup = recordingHasTalkgroup(row);
    const conventional = !hasGroup && value(row, 'destination_radio_id') === null;
    head.append(time, node('span', 'recordings-call-duration', duration(row)),
      node('span', 'recordings-call-protocol', `${protocolLabel(row.protocol)}${conventional ? ' · Conventional' : ''}`));
    const actions = node('div', 'recordings-call-actions'); actions.setAttribute('role', 'group'); actions.setAttribute('aria-label', `Actions for ${description}`); actions.append(play, detail, info);
    const main = node('div', 'recordings-call-main');
    const heading = node('div', 'recordings-call-heading'); const title = node('strong', 'recordings-call-title');
    title.append(entityLink(label(row), conventional ? row.channel_entity_ref : row.target_entity_ref, row, conventional ? 'channel' : 'target'));
    const playback = node('span', 'recordings-call-status'); playback.hidden = true; heading.append(title, playback); main.append(heading, head);
    const context = node('p', 'recordings-call-meta');
    const parts = [];
    const system = systemLabel(row);
    const rowSystemKey = value(row, 'system_key', 'radio_system_key') || row.radio_system_entity_ref?.key;
    if (system && (!currentFilters.system_key || currentFilters.system_key !== rowSystemKey))
      parts.push(['system', system, row.radio_system_entity_ref]);
    const group = value(row, 'talkgroup_group', 'group_name'); if (group) parts.push(['group', group]);
    const identity = node('span', 'recordings-call-identity');
    if (hasGroup) identity.append(entityLink(`${row.call_type === 'PATCH' ? 'Patch' : 'Talkgroup'} ${recordingGroupId(row, 'target', groupId)}`, row.target_entity_ref, row, 'target'));
    else if (value(row, 'destination_radio_id') !== null) identity.append(entityLink(`Radio ${recordingRadioId(row, 'target', row.destination_radio_id)}`, row.target_entity_ref, row, 'target'));
    else if (frequency(row)) identity.textContent = frequency(row);
    if (identity.textContent) head.append(identity);
    parts.forEach(([key, text, ref], index) => { if (index) context.append(' · '); context.append(entityLink(text, ref, row, key)); });
    if (parts.length) main.append(context);
    const sourceText = sourceLabel(row, getSourceNameDisplay());
    const channelName = value(row, 'channel_name', 'analog_channel_name');
    if (sourceText || channelName && channelName !== label(row)) {
      const source = node('div', 'recordings-call-source');
      if (sourceText) source.append(node('span', '', 'Source '), entityLink(sourceText, recordingSourceReference(row), row, 'source'));
      if (channelName && channelName !== label(row)) {
        if (sourceText) source.append(node('span', '', ' · '));
        source.append(entityLink(channelName, row.channel_entity_ref, row, 'channel'));
      }
      main.append(source);
    }
    for (const item of row.auxiliary_identifiers || []) if (item.kind === 'tone' && item.protocol === 'DCS') {
      const tone = button(node, `DCS ${item.value}`, () => { search.dcs_code = String(item.value); replaceSearchForm(); updateDraftFeedback(); }, 'recordings-call-tone'); main.append(tone);
    }
    if (row.transcript_excerpt) main.append(node('p', 'recordings-transcript-preview', row.transcript_excerpt));
    const details = node('details', 'recordings-call-summary'); details.append(node('summary', '', 'Details'));
    const facts = node('p', '', [value(row, 'channel_name', 'analog_channel_name'), frequency(row), value(row, 'site_name'), conventional ? 'No talkgroup recorded' : ''].filter(Boolean).join(' · ')); details.append(facts);
    main.append(details);
    if (isPrimaryAdmin()) {
      const checkbox = node('input', 'recordings-call-select'); checkbox.type = 'checkbox'; checkbox.checked = selection.has(String(row.id));
      checkbox.setAttribute('aria-label', `Select ${label(row)} at ${dateTime(millis(row))}`);
      checkbox.addEventListener('change', () => { if (checkbox.checked) selection.add(String(row.id)); else selection.delete(String(row.id)); drawSelection(); });
      card.classList.add('recordings-call-selectable'); card.append(checkbox);
    }
    card.append(main, actions); return card;
  }

  function drawResultPlayback(snapshot = player ? playbackState() : null) {
    if (!resultHost?.isConnected) return;
    const queued = new Set((snapshot?.queue || []).map((row) => String(row.id)));
    resultHost.querySelectorAll('.recordings-call').forEach((card) => {
      const active = snapshot?.current && !snapshot.stopped &&
        String(snapshot.current.id) === card.dataset.recordingId;
      const waiting = queued.has(card.dataset.recordingId);
      const status = card.querySelector('.recordings-call-status');
      const text = active ? snapshot.loading ? 'Loading' : snapshot.paused ? 'Paused' :
        '' : waiting ? 'Queued' : '';
      card.classList.toggle('recordings-call-playing', Boolean(active));
      card.classList.toggle('recordings-call-queued', waiting);
      if (active) card.setAttribute('aria-current', 'true'); else card.removeAttribute('aria-current');
      card.setAttribute('aria-busy', String(Boolean(active && snapshot.loading)));
      const play = card.querySelector('.recordings-card-play');
      if (play) play.title = { once: 'Play once', continue: 'Continue from here', queue: 'Queue and play' }[clickMode];
      if (status) { status.textContent = text; status.hidden = !text; }
    });
  }

  function drawSelectedFilters() {
    if (!selectedFiltersHost) return;
    selectedFiltersHost.replaceChildren();
    const chips = [];
    const represented = new Set();
    const groups = [
      ['system_key', ['system_key'], 'System'],
      ['site', ['site', 'wacn', 'sysid', 'rfss', 'site_id'], 'Site'],
      ['talkgroup_id', ['talkgroup_id', 'talkgroup_identity_key', 'talkgroup_min', 'talkgroup_max'], 'Talkgroup'],
      ['radio_id', ['radio_id', 'radio_identity_key', 'radio_min', 'radio_max'], 'Radio'],
      ['channel_id', ['channel_id'], 'Channel'], ['alias_list_id', ['alias_list_id'], 'Alias List']
    ];
    for (const [key, keys, title] of groups) {
      if (keys.every(name => represented.has(name))) continue;
      const suggestion = appliedSuggestions.get(key);
      const applied = [...new Set([...keys, ...(suggestion?.appliedKeys || [])])];
      if (!applied.some((name) => currentFilters[name])) continue;
      const systemRow = key === 'system_key' && currentResults.find(row =>
        (value(row, 'system_key', 'radio_system_key') || row.radio_system_entity_ref?.key) === currentFilters.system_key);
      const systemText = key === 'system_key' ? systemLabel(systemRow || { system_key: currentFilters.system_key }) : '';
      const suggestedName = key === 'system_key' && suggestion?.label === currentFilters.system_key ? '' : suggestion?.label;
      const text = suggestedName || systemText || suggestion?.id || keys.map((name) => currentFilters[name])
        .filter(Boolean).join('–');
      chips.push({ key, keys: applied, text: `${title}: ${text}`, displayText: suggestion?.label || systemText ? text : null });
      applied.forEach((name) => represented.add(name));
    }
    for (const [key, kind, title] of [['talkgroups', 'talkgroup', 'Talkgroup'], ['radios', 'radio', 'Radio']]) {
      for (const item of recordingIdentitySelections(currentFilters[key], kind)) {
        const id = recordingIdentitySelectionKey(item);
        const name = identityLabels.get(`${kind}:${id}`) || `${recordingSelectionText(item, kind)} · ${item.system_key || 'Unidentified system'}`;
        chips.push({ key, keys: [key], selection: id, text: `${title}: ${name}`, displayText: name });
      }
    }
    for (const id of recordingChannelSelections(currentFilters.channel_ids))
      chips.push({ key: 'channel_ids', keys: ['channel_ids'], channel: id, text: channelLabels.get(id) || `Channel: ${id}` });
    if (currentFilters.high_interest) chips.push({ key: 'high_interest', keys: ['high_interest'], text: 'High Interest · 10 seconds or longer' });
    if (currentFilters.dcs_code) chips.push({ key: 'dcs_code', keys: ['dcs_code'], text: `DCS ${currentFilters.dcs_code}` });
    if (currentFilters.has_transcript) chips.push({ key: 'has_transcript', keys: ['has_transcript'], text: currentFilters.has_transcript === 'false' ? 'No saved transcript' : 'Has transcript' });
    if (currentFilters.paging_tones || currentFilters.tone_frequency_hz) {
      const text = currentFilters.tone_frequency_hz ? `${currentFilters.tone_frequency_hz} Hz` +
        (currentFilters.tone_then_hz ? ` → ${currentFilters.tone_then_hz} Hz` : '') +
        ` (±${currentFilters.tone_tolerance_hz || 20} Hz)` : 'Detected paging tones';
      chips.push({ key: 'tones', keys: ['paging_tones', 'tone_frequency_hz', 'tone_then_hz', 'tone_tolerance_hz'], text });
    }
    if (currentFilters.radio_role && (currentFilters.radios || currentFilters.radio_id || currentFilters.radio_identity_key || currentFilters.radio_min))
      chips.push({ key: 'radio_role', keys: ['radio_role'], text: currentFilters.radio_role === 'source' ? 'Source radio' : 'Any participant' });
    const simple = [['q', 'Name'], ['transcript', 'Full text'], ['frequency_hz', 'Frequency'],
      ['call_type', 'Call type'], ['voice_type', 'Voice type'], ['protocol', 'Protocol']];
    for (const [key, title] of simple) {
      if (!currentFilters[key] || represented.has(key)) continue;
      const text = key === 'frequency_hz' ? `${Number(currentFilters[key]) / 1_000_000} MHz` :
        key === 'protocol' ? protocolLabel(currentFilters[key]) :
          ['call_type', 'voice_type'].includes(key) ? prettify(currentFilters[key]) : currentFilters[key];
      chips.push({ key, keys: [key], text: `${title}: ${text}` });
    }
    if ((currentFilters.min_duration_ms && !(currentFilters.high_interest && Number(currentFilters.min_duration_ms) === 10000)) || currentFilters.max_duration_ms) {
      const from = currentFilters.min_duration_ms ? `${Number(currentFilters.min_duration_ms) / 1000}s` : '0s';
      const to = currentFilters.max_duration_ms ? Number(currentFilters.max_duration_ms) < 1000 ? '<1s' :
        `${Number(currentFilters.max_duration_ms) / 1000}s` : 'No maximum';
      chips.push({ key: 'duration', keys: ['min_duration_ms', 'max_duration_ms'], text: `Call length: ${from}–${to}` });
    }
    if (appliedRange !== '24h') {
      const label = { '7d': 'Past 7d', '30d': 'Past 30d', '10y': 'Past 10y' }[appliedRange] ||
        `${dateTime(Number(currentFilters.from_ms))} – ${dateTime(Number(currentFilters.to_ms))}`;
      chips.push({ key: 'range', keys: ['range', 'from_ms', 'to_ms'], text: label });
    }
    if (filterCountHost) {
      filterCountHost.textContent = String(chips.length);
      filterCountHost.hidden = chips.length === 0;
    }
    for (const chip of chips) {
      const chipId = JSON.stringify([chip.key, chip.selection || '', chip.channel || '']);
      const applied = appliedSearch || currentFilters;
      const kind = chip.key === 'talkgroups' ? 'talkgroup' : 'radio';
      const pending = chip.selection ? !recordingIdentitySelections(search[chip.key], kind)
        .some(item => recordingIdentitySelectionKey(item) === chip.selection) : chip.channel ?
        !recordingChannelSelections(search.channel_ids).includes(chip.channel) :
          chip.keys.some(key => String(search[key] || '') !== String(applied[key] || ''));
      const remove = button(node, '', () => {
        if (pending) {
          if (chip.selection) {
            const choices = recordingIdentitySelections(search[chip.key], kind);
            const originals = recordingIdentitySelections(applied[chip.key], kind);
            const original = originals
              .find(item => recordingIdentitySelectionKey(item) === chip.selection);
            if (original) {
              const selected = new Map([...choices, original].map(item => [recordingIdentitySelectionKey(item), item]));
              const restored = originals.filter(item => selected.has(recordingIdentitySelectionKey(item)));
              restored.forEach(item => selected.delete(recordingIdentitySelectionKey(item)));
              search[chip.key] = JSON.stringify([...restored, ...selected.values()]);
            }
          } else if (chip.channel) {
            const selected = new Set([...recordingChannelSelections(search.channel_ids), chip.channel]);
            const restored = recordingChannelSelections(applied.channel_ids).filter(id => selected.has(id));
            restored.forEach(id => selected.delete(id));
            search.channel_ids = JSON.stringify([...restored, ...selected]);
          }
          else chip.keys.forEach(key => { search[key] = applied[key] || ''; });
          for (const [key, suggestion] of appliedSuggestions) {
            if (key === chip.key || suggestion.appliedKeys?.some(name => chip.keys.includes(name))) selectedSuggestions.set(key, suggestion);
          }
        } else {
          if (chip.selection) {
            const remaining = recordingIdentitySelections(search[chip.key], kind)
              .filter(item => recordingIdentitySelectionKey(item) !== chip.selection);
            search[chip.key] = remaining.length ? JSON.stringify(remaining) : '';
          } else if (chip.channel) {
            const remaining = recordingChannelSelections(search.channel_ids).filter(id => id !== chip.channel);
            search.channel_ids = remaining.length ? JSON.stringify(remaining) : '';
          } else chip.keys.forEach(key => { search[key] = ''; });
          for (const [key, suggestion] of selectedSuggestions) {
            if (key === chip.key || suggestion.appliedKeys?.some(name => chip.keys.includes(name))) selectedSuggestions.delete(key);
          }
          if (chip.key === 'range') search.range = '24h';
        }
        replaceSearchForm(); updateDraftFeedback();
        [...selectedFiltersHost.querySelectorAll('.recordings-filter-chip')]
          .find(control => control.dataset.filterChip === chipId)?.focus();
      }, `recordings-filter-chip${pending ? ' recordings-filter-chip-pending' : ''}`);
      remove.dataset.filterChip = chipId;
      remove.append(node('span', 'recordings-filter-chip-label', chip.displayText || chip.text),
        node('span', 'recordings-filter-chip-state', pending ? 'Remove on search · Undo' : '×'));
      remove.setAttribute('aria-label', pending ? `Undo removal of ${chip.text} filter` : `Remove ${chip.text} filter`);
      remove.setAttribute('aria-pressed', String(pending));
      remove.title = `${chip.text} · ${pending ? 'Undo removal · applies on next search' : 'Remove on next search'}`;
      remove.dataset.uiHint = remove.title;
      selectedFiltersHost.append(remove);
    }
  }

  function drawSelection() {
    if (pageSelect) {
      const selected = currentResults.filter((row) => selection.has(String(row.id))).length;
      pageSelect.checked = currentResults.length > 0 && selected === currentResults.length;
      pageSelect.indeterminate = selected > 0 && selected < currentResults.length;
      pageSelect.disabled = currentResults.length === 0;
    }
    const showSelection = selection.size > 0 && isPrimaryAdmin();
    if (countText) countText.hidden = showSelection || !(currentResults.length || nextCursor);
    if (!selectedHost) return;
    selectedHost.replaceChildren();
    selectedHost.hidden = !selection.size || !isPrimaryAdmin();
    if (selectedHost.hidden) return;
    selectedHost.append(node('strong', '', `${selection.size} selected`),
      button(node, 'Review delete', () => confirmDelete(), 'ui-button ui-button-danger-quiet'),
      button(node, 'Clear selection', () => {
        selection.clear();
        resultHost.querySelectorAll('input.recordings-call-select').forEach((input) => {
          input.checked = false;
        });
        drawSelection();
        resultHost.querySelector('input.recordings-call-select')?.focus();
      }, 'ui-button ui-button-link'));
  }

  function confirmDelete() {
    if (!isPrimaryAdmin() || !selection.size) return;
    const ids = [...selection];
    const body = node('div', 'recordings-delete-confirm');
    body.append(node('p', '', `Delete ${ids.length} selected managed ${ids.length === 1 ? 'recording' : 'recordings'}? ` +
      'Their audio files and call entries will be removed.'));
    const status = node('div', 'recordings-form-status');
    status.setAttribute('role', 'status');
    const cancel = button(node, 'Cancel', () => modal.close());
    const remove = button(node, 'Delete recordings', async () => {
      remove.disabled = true;
      modal.setBusy(true);
      status.replaceChildren(makeNotice('Deleting selected calls…', 'loading'));
      try {
        await requestJson(`${ADMIN}/calls`, { method: 'DELETE',
          body: { ids: ids.map((id) => Number(id)) }, page: false });
        if (player && ids.includes(String(player.current?.id))) resetAudio();
        else if (player) {
          player.queue = player.queue.filter((row) => !ids.includes(String(row.id)));
          drawQueue();
        }
        ids.forEach((id) => selection.delete(id));
        modal.setBusy(false);
        modal.close();
        void renderSearchPage();
      } catch (error) {
        modal.setBusy(false);
        remove.disabled = false;
        status.replaceChildren(makeNotice(error.message || 'Calls could not be deleted.', 'error'));
      }
    }, 'ui-button ui-button-danger');
    body.append(status, modalFooter(cancel, remove));
    const modal = openReadOnlyModal('Delete recordings', body, { id: 'delete-recordings',
      className: 'recordings-detail-modal' });
  }

  function updatePagers() {
    const showPager = currentPage > 0 || Boolean(nextCursor);
    for (const pager of pagerHosts) {
      pager.hidden = !showPager;
      pager.update({ countText: `Page ${currentPage + 1}`,
        previous: { enabled: !searchLoading && currentPage > 0, onClick: () => void loadPage(currentPage - 1) },
        next: { enabled: !searchLoading && Boolean(nextCursor), onClick: () => void loadPage(currentPage + 1) } });
      const first = pager.querySelector('[data-page-action="first"]');
      first.disabled = searchLoading || currentPage === 0;
      first.setAttribute('aria-disabled', String(first.disabled));
    }
  }

  function makePager(position) {
    const pager = browsingWorkflows.createBrowsingPager({ node,
      className: `recordings-pager recordings-pager-${position}`,
      ariaLabel: position === 'top' ? 'Call result pages above calls' : 'Call result pages below calls' });
    pager.classList.remove('pager', 'ui-pager');
    const first = button(node, 'First', () => void loadPage(0), 'ui-button ui-button-secondary ui-button-compact');
    first.dataset.pageAction = 'first';
    const actions = pager.querySelector('.ui-browse-pager-actions');
    actions.classList.add('recordings-pager-actions'); actions.prepend(first);
    const count = pager.querySelector('.ui-browse-pager-count');
    count.classList.add('recordings-pager-count');
    actions.insertBefore(count, actions.querySelector('[data-page-action="next"]'));
    actions.querySelectorAll('button').forEach(control => control.classList.add('ui-button-compact'));
    pagerHosts.push(pager);
    return pager;
  }

  function drawResults() {
    if (!resultHost) return;
    if (resultScope) resultScope.textContent = searchPeriodLabel(appliedSearch || search);
    resultHost.replaceChildren();
    if (!currentResults.length) {
      resultHost.append(makeNotice(nextCursor ?
        'No matches in this batch. More calls may match; select Next to continue.' :
        'No matching calls. Try a wider time range or fewer filters.', 'empty'));
    } else {
      let lastDate = '';
      const gaps = recordingPageGaps(currentResults);
      for (const [index, row] of currentResults.entries()) {
        const date = dateTime(millis(row), true);
        if (date !== lastDate) { resultHost.append(node('h3', 'recordings-result-date', date)); lastDate = date; }
        else {
          const gap = gaps.get(index);
          if (gap !== undefined) {
            const label = recordingGapLabel(gap);
            const divider = node('div', 'recordings-call-gap');
            divider.setAttribute('role', 'separator');
            divider.title = `Gap between matching calls: ${label}`;
            divider.setAttribute('aria-label', divider.title);
            divider.append(node('span', 'recordings-call-gap-label', `Gap ${label}`));
            resultHost.append(divider);
          }
        }
        resultHost.append(callCard(row));
      }
    }
    drawResultPlayback();
    const showCount = Boolean(currentResults.length || nextCursor);
    const showPager = currentPage > 0 || Boolean(nextCursor);
    if (countHost) {
      countHost.hidden = false;
      countText.hidden = !showCount;
      if (pageSelect) pageSelect.closest('label').hidden = !showCount;
      const count = resultTotal === null ? currentResults.length : Number(resultTotal);
      countText.textContent = `${count.toLocaleString()} ${count === 1 ? 'call' : 'calls'}` +
        (resultTotal === null ? ' shown' : '') +

        (showPager && nextCursor ? ' · more available' : '');
    }
    updatePagers();
    drawSelection();
    drawSelectedFilters();
  }

  async function loadPage(page = 0, submission = null, browsing = {}) {
    if (!pageHost) return;
    const reset = Boolean(submission || browsing.reset);
    if (reset) page = 0;
    const cursor = reset || page === 0 ? null : cursors[page] || (page === currentPage + 1 ? nextCursor : null);
    if (page < 0 || (page > 0 && !cursor)) return;
    const requestedSize = browsing.pageSize || pageSize;
    const requestedSort = browsing.sort || appliedSort;
    const activeControl = pageHost.ownerDocument.activeElement;
    const activePager = activeControl?.closest('.recordings-pager');
    const requestedCursors = reset ? [null] : cursors.slice(0, page + 1);
    requestedCursors[page] = cursor;
    const request = ++searchRequest;
    const startedAt = performance.now();
    searchLoading = true;
    if (sortControl) sortControl.disabled = true;
    if (pageSizeControl) pageSizeControl.disabled = true;
    pageHost.querySelector('button[type="submit"]')?.setAttribute('disabled', '');
    updatePagers();
    resultHost?.setAttribute('aria-busy', 'true');
    searchSignal?.abort();
    searchSignal = new AbortController();
    resultStatus.replaceChildren(makeNotice('Finding calls…', 'loading'));
    try {
      const result = await requestJson(query(submission?.filters || currentFilters, {
        cursor, sort: requestedSort, limit: requestedSize
      }), { page: false, signal: searchSignal.signal });
      if (request !== searchRequest || !pageHost.isConnected) return;
      if (submission) {
        const anchors = {};
        for (const key of ['from_ms', 'to_ms']) if (Number.isFinite(Number(result?.[key])) && result?.[key] != null) anchors[key] = String(result[key]);
        if (JSON.stringify(search) === JSON.stringify(submission.search)) Object.assign(search, anchors);
        currentFilters = { ...submission.filters, ...anchors }; appliedSearch = { ...submission.search, ...anchors }; appliedRange = submission.search.transcript && submission.search.text_range !== 'time' ? '24h' : submission.search.range || '24h';
        appliedSuggestions = submission.suggestions; selection.clear(); resetAudio();
        updateDraftFeedback();
      }
      currentResults = Array.isArray(result?.calls) ? result.calls : [];
      currentPage = page;
      pageSize = requestedSize;
      appliedSort = requestedSort;
      cursors = requestedCursors;
      nextCursor = result?.next_cursor || null;
      resultTotal = result?.total !== null && result?.total !== undefined &&
        Number.isFinite(Number(result.total)) ? Number(result.total) : null;
      resultStatus.replaceChildren();
      drawResults();
      searchTiming.textContent = recordingSearchTiming(performance.now() - startedAt);
    } catch (error) {
      if (request !== searchRequest || error?.name === 'AbortError') return;
      resultStatus.replaceChildren(makeNotice(`${error.message || 'Calls could not be loaded. Try again.'}${currentResults.length ? ' Previous results are still shown.' : ''}`, 'error'));
    } finally {
      if (request === searchRequest) {
        searchLoading = false;
        if (sortControl) { sortControl.disabled = false; sortControl.value = appliedSort; }
        if (pageSizeControl) { pageSizeControl.disabled = false; pageSizeControl.value = String(pageSize); }
        pageHost?.querySelector('button[type="submit"]')?.removeAttribute('disabled'); resultHost?.setAttribute('aria-busy', 'false');
        updatePagers();
        if (activePager && (pageHost.ownerDocument.activeElement === activeControl ||
            pageHost.ownerDocument.activeElement === pageHost.ownerDocument.body)) {
          const target = !activePager.hidden && !activeControl.disabled ? activeControl :
            !activePager.hidden && activePager.querySelector('button:not(:disabled)') || sortControl;
          target?.focus();
        }
      }
    }
  }

  function field(title, control) {
    const wrapper = node('label', 'ui-field recordings-field');
    wrapper.append(node('span', 'ui-field-label', title), control);
    return wrapper;
  }

  function textInput(placeholder, type = 'search') {
    const input = node('input', 'ui-input');
    input.type = type;
    input.placeholder = placeholder;
    input.autocomplete = 'off';
    return input;
  }

  async function openFilterDialog(topic = 'targets') {
    const draft = { ...search };
    const suggestions = new Map(selectedSuggestions);
    const body = node('form', 'recordings-filter-editor recordings-workspace-dialog');
    body.noValidate = true;
    const status = node('div', 'recordings-form-status');
    let modal;
    let disposed = false;
    let abort;
    let generation = 0;
    let timer;
    const title = { targets: 'Talkgroups & channels', radios: 'Source radios', systems: 'System', sites: 'Site',
      tones: 'Tones & signaling', more: 'More filters' }[topic];
    const stagedLabels = new Map();
    const panels = node('div', 'recordings-dialog-content');
    let commit = () => {};
    let load = () => {};
    let focus;
    const addInput = (host, key, title, placeholder = '', type = 'text', transform = value => value) => {
      const input = textInput(placeholder, type);
      input.value = draft[key] || '';
      input.addEventListener('input', () => { draft[key] = transform(input.value); });
      host.append(field(title, input)); return input;
    };
    if (['targets', 'radios', 'systems', 'sites'].includes(topic)) {
      const target = topic === 'targets', radio = topic === 'radios';
      const single = !target && !radio;
      const initialKind = topic === 'systems' ? 'system' : topic === 'sites' ? 'site' : radio ? 'radio' : 'all';
      let activeKind = initialKind;
      const choices = new Map();
      const identityKey = (kind, selection) => `${kind}:${kind === 'channel' ? selection : recordingIdentitySelectionKey(selection)}`;
      if (target || radio) {
        const kind = radio ? 'radio' : 'talkgroup';
        for (const selection of recordingIdentitySelections(draft[radio ? 'radios' : 'talkgroups'], kind))
          choices.set(identityKey(kind, selection), { kind, selection });
        if (target) for (const id of recordingChannelSelections(draft.channel_ids))
          choices.set(identityKey('channel', id), { kind: 'channel', selection: id });
      }
      let singleChoice = null;
      let singleCleared = false;
      const tabs = node('div', 'recordings-picker-tabs');
      tabs.setAttribute('role', 'tablist'); tabs.setAttribute('aria-label', 'Recording targets');
      if (target) for (const [key, text] of [['all', 'All'], ['talkgroup', 'Talkgroups'], ['channel', 'Channels']]) {
        const tab = button(node, text, () => {
          activeKind = key;
          for (const control of tabs.children) { const selected = control.dataset.kind === key;
            control.setAttribute('aria-selected', String(selected)); control.tabIndex = selected ? 0 : -1; }
          void load();
        }, 'recordings-tab');
        tab.dataset.kind = key; tab.setAttribute('role', 'tab');
        tab.setAttribute('aria-selected', String(key === 'all')); tab.tabIndex = key === 'all' ? 0 : -1;
        tab.addEventListener('keydown', event => {
          if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
          event.preventDefault(); const controls = [...tabs.children], index = controls.indexOf(tab);
          const next = event.key === 'Home' ? 0 : event.key === 'End' ? controls.length - 1 :
            (index + (event.key === 'ArrowRight' ? 1 : -1) + controls.length) % controls.length;
          controls[next].click(); modal.focus(controls[next]);
        });
        tabs.append(tab);
      }
      const input = textInput(radio ? 'Talker alias, configured name, or radio ID' :
        topic === 'systems' ? 'System or channel name' : topic === 'sites' ? 'Site name, channel name, or ID' : 'Name, ID, category, or frequency');
      input.setAttribute('aria-label', `Find ${radio ? 'radios' : topic === 'systems' ? 'systems' : topic === 'sites' ? 'sites' : 'talkgroups and channels'}`);
      focus = input;
      const sort = select(node, [['calls', 'Most calls'], ['name', 'Name'], ['recent', 'Last active']], 'Sort picker list');
      const tools = node('div', 'recordings-picker-tools'); tools.append(input, sort);
      const period = node('span', 'recordings-picker-period', `Calls · ${searchPeriodLabel(draft).toLowerCase()}`);
      const selected = node('div', 'recordings-picker-selected'); selected.setAttribute('aria-label', 'Selected choices');
      const feedback = node('div', 'recordings-picker-feedback');
      const results = node('div', 'recordings-picker-results'); results.setAttribute('aria-label', `Available ${title.toLowerCase()}`);
      const selectionTools = node('div', 'recordings-picker-list-tools');
      const clear = button(node, 'Clear selection', () => { choices.clear(); singleChoice = null; singleCleared = true; drawSelected(); drawRows(); }, 'recordings-text-action');
      const selectShown = button(node, 'Select shown', () => {
        const shown = new Set([...results.querySelectorAll('details[open][data-group]')].map(group => group.dataset.group));
        selectRows(groups.filter(group => shown.has(group.group_key)).flatMap(group => group.rows.map(row => ({ row, kind: group.kind }))), true);
      }, 'recordings-text-action');
      if (!single) selectionTools.append(selectShown);
      selectionTools.append(clear);
      const listTools = node('div', 'recordings-picker-list-tools'); listTools.append(period, selectionTools);
      let groups = [];
      const expanded = new Map();
      const display = (kind, selection) => kind === 'channel' ? channelLabels.get(selection) || `Channel ${selection}` :
        stagedLabels.get(identityKey(kind, selection)) || identityLabels.get(identityKey(kind, selection)) || recordingSelectionText(selection, kind);
      const normalize = (kind, row) => kind === 'channel' ? String(row.selection?.channel_id || row.channel_id || row.id || '') :
        recordingIdentitySelections([row.selection || row], kind)[0];
      const remember = (kind, row) => {
        const selection = normalize(kind, row); if (!selection && selection !== 0) return;
        const name = radio ? row.ota_alias || row.alias || row.label : row.label;
        const context = row.system_name || row.system_key || 'Unidentified system';
        const labelText = `${name || (kind === 'channel' ? 'Channel' : recordingSelectionText(selection, kind))} · ${context}`;
        stagedLabels.set(identityKey(kind, selection), labelText);
        if (kind === 'channel') channelLabels.set(selection, labelText);
      };
      const drawSelected = () => {
        selected.replaceChildren();
        if (single) {
          const key = topic === 'systems' ? 'system_key' : 'site';
          const text = singleCleared ? '' : singleChoice?.label || suggestions.get(key)?.label || draft[key];
          selected.append(node('span', 'recordings-picker-selection-summary', text || `Any ${topic === 'systems' ? 'system' : 'site'}`));
          return;
        }
        if (!choices.size) selected.append(node('span', 'recordings-picker-selection-summary', `Any ${radio ? 'source radio' : 'talkgroup or channel'}`));
        for (const [key, item] of choices) {
          const remove = button(node, `${display(item.kind, item.selection)} ×`, () => {
            choices.delete(key); drawSelected(); drawRows();
          }, 'recordings-selection-chip');
          remove.setAttribute('aria-label', `Remove ${display(item.kind, item.selection)} from selection`); selected.append(remove);
        }
        selected.dataset.count = String(choices.size);
      };
      const selectRows = (items, checked) => {
        const next = new Map(choices);
        for (const { kind, row } of items) {
          const selection = normalize(kind, row); if (!selection && selection !== 0) continue;
          const key = identityKey(kind, selection);
          if (checked) next.set(key, { kind, selection }); else next.delete(key);
        }
        if (next.size > 100) { feedback.replaceChildren(makeNotice('Choose up to 100 talkgroups, channels, or radios.', 'error')); drawRows(); return; }
        choices.clear(); next.forEach((value, key) => choices.set(key, value)); drawSelected(); drawRows();
      };
      const choice = (group, row) => {
        const kind = group.kind, selection = normalize(kind, row), key = single ? JSON.stringify(row.selection || row.id) : identityKey(kind, selection);
        const wrapper = node('label', 'recordings-picker-choice');
        const check = node('input', 'recordings-picker-check'); check.type = single ? 'radio' : 'checkbox';
        check.name = 'recordings-picker-choice'; check.dataset.recordingIdentity = key;
        check.checked = single ? !singleCleared && Boolean(singleChoice ? JSON.stringify(singleChoice.selection || singleChoice.id) === key :
          row.selection && Object.entries(row.selection).every(([name, value]) => String(draft[name] || '') === String(value ?? ''))) : choices.has(key);
        check.addEventListener('change', () => { if (single) { singleChoice = row; singleCleared = false; drawSelected(); drawRows(); }
          else selectRows([{ kind, row }], check.checked); });
        const copy = node('span', 'recordings-identity-copy');
        const primary = radio ? row.ota_alias || row.alias || row.label : row.label;
        copy.append(node('strong', '', String(primary || (kind === 'channel' ? 'Unnamed channel' : recordingSelectionText(selection, kind)))));
        const context = [kind === 'channel' ? [row.protocols?.map(protocolLabel).join(' / ') || protocolLabel(row.protocol), row.frequency_hz ? `${Number(row.frequency_hz) / 1e6} MHz` : '', 'Channel'].filter(Boolean).join(' · ') :
          single ? row.detail || row.system_name || row.system_key : [recordingSelectionText(selection, kind), row.protocol ? recordingNamespaceLabel(row.protocol) : ''].filter(Boolean).join(' · ')];
        if (row.description) context.push(row.description);
        copy.append(node('span', 'recordings-option-context', context.filter(Boolean).join(' · ')));
        const count = node('span', 'recordings-option-count', row.call_count != null ? Number(row.call_count).toLocaleString() : '');
        count.append(node('small', '', row.count_kind === 'heard_at' ? 'calls heard' : 'calls'));
        wrapper.append(check, copy, count); return wrapper;
      };
      const drawRows = () => {
        const active = globalThis.document?.activeElement?.dataset?.recordingIdentity;
        const activeMore = globalThis.document?.activeElement?.dataset?.recordingMore;
        for (const detail of results.querySelectorAll('details[data-group]')) expanded.set(detail.dataset.group, detail.open);
        results.replaceChildren();
        const systems = new Map();
        for (const group of groups) {
          const systemKey = group.system_key || group.system_name || (single ? '' : 'Unidentified system');
          if (!systems.has(systemKey)) systems.set(systemKey, []); systems.get(systemKey).push(group);
        }
        for (const systemGroups of systems.values()) {
          const system = node('section', 'recordings-picker-system');
          const first = systemGroups[0];
          if (!single) system.append(node('h3', '', first.system_name || first.system_key || 'Unidentified system'));
          for (const group of systemGroups) {
            const block = node('details', 'recordings-picker-group'); block.dataset.group = group.group_key;
            block.open = expanded.has(group.group_key) ? expanded.get(group.group_key) : true;
            const heading = node('summary', 'recordings-picker-group-heading', group.label || (group.kind === 'channel' ? 'Channels' : 'Ungrouped'));
            if (group.alias_list_name) heading.append(node('small', 'recordings-group-alias-list', group.alias_list_name));
            const shown = group.rows.length;
            heading.append(node('span', 'recordings-group-count', `${shown} of ${Number(group.match_count ?? shown).toLocaleString()}`));
            block.append(heading);
            if (!single && group.rows.length) {
              const selectGroup = button(node, 'Select shown', () => selectRows(group.rows.map(row => ({ row, kind: group.kind })), true), 'recordings-text-action');
              selectGroup.setAttribute('aria-label', `Select shown ${group.kind === 'channel' ? 'channels' : radio ? 'radios' : 'talkgroups'} in ${group.label || 'Ungrouped'} on ${group.system_name || group.system_key || 'Unidentified system'}`);
              block.append(selectGroup);
            }
            for (const row of group.rows) block.append(choice(group, row));
            if (group.has_more) {
              const more = button(node, 'Load 10 more', () => void loadGroup(group, more), 'recordings-load-more');
              more.dataset.recordingMore = group.group_key;
              more.setAttribute('aria-label', `Load 10 more in ${group.label || 'Ungrouped'} on ${group.system_name || group.system_key || 'Unidentified system'}`);
              block.append(more);
            }
            system.append(block);
          }
          results.append(system);
        }
        if (active) { const target = [...results.querySelectorAll('input')].find(input => input.dataset.recordingIdentity === active); if (target) modal.focus(target); }
        else if (activeMore) { const target = [...results.querySelectorAll('[data-recording-more]')].find(control => control.dataset.recordingMore === activeMore); if (target) modal.focus(target); }
      };
      const endpoint = kind => ['talkgroup', 'radio'].includes(kind) ? IDENTITIES : ENTITIES;
      let discoveryPeriod = recordingDiscoveryPeriod(draft);
      const parameters = kind => ({ kind, grouped: true, q: input.value.trim(), sort: sort.value,
        ...discoveryPeriod, system_key: kind === 'system' ? '' : draft.system_key,
        radio_role: radio ? draft.radio_role || 'source' : '', limit: 10 });
      const unpack = (result, kind) => Array.isArray(result?.groups) ? result.groups.map(group => ({ ...group, kind: group.kind || kind, rows: group.rows || [] })) :
        (result?.rows?.length ? [{ group_key: kind, kind, label: kind === 'channel' ? 'Channels' : kind === 'radio' ? 'Source radios' : 'Ungrouped',
          rows: result.rows, match_count: result.rows.length, has_more: result.has_more, next_offset: result.next_offset }] : []);
      async function loadGroup(group, control) {
        control.disabled = true;
        const request = generation;
        try {
          const result = await requestJson(queryPath(endpoint(group.kind), { ...parameters(group.kind), group_key: group.group_key,
            offset: group.next_offset ?? group.rows.length }), { page: false, signal: abort?.signal });
          if (disposed || request !== generation) return;
          const next = unpack(result, group.kind).find(item => item.group_key === group.group_key);
          if (next) {
            const existing = new Set(group.rows.map(row => JSON.stringify(row.selection || row.id)));
            next.rows = [...group.rows, ...next.rows.filter(row => !existing.has(JSON.stringify(row.selection || row.id)))];
            Object.assign(group, next); group.rows.forEach(row => remember(group.kind, row)); drawSelected(); drawRows();
          }
        } catch (error) { if (error.name !== 'AbortError' && !disposed) feedback.replaceChildren(makeNotice('More choices could not load. Try again.', 'error')); }
        finally { control.disabled = false; }
      }
      load = async () => {
        const request = ++generation; discoveryPeriod = recordingDiscoveryPeriod(draft); abort?.abort(); abort = new AbortController();
        feedback.replaceChildren(makeNotice('Finding choices…', 'loading')); results.replaceChildren();
        try {
          const kinds = activeKind === 'all' ? ['talkgroup', 'channel'] : [activeKind];
          const responses = await Promise.all(kinds.map(async kind => [kind,
            await requestJson(queryPath(endpoint(kind), parameters(kind)), { page: false, signal: abort.signal })]));
          if (disposed || request !== generation) return;
          groups = responses.flatMap(([kind, result]) => unpack(result, kind));
          groups.forEach(group => group.rows.forEach(row => remember(group.kind, row)));
          feedback.replaceChildren(...(groups.length ? [] : [makeNotice('No choices recorded in this period. Try another name or a wider time range.', 'empty')]));
          drawSelected(); drawRows();
        } catch (error) { if (!disposed && request === generation && error.name !== 'AbortError')
          feedback.replaceChildren(makeNotice('Choices could not load. Try again.', 'error'), button(node, 'Try again', () => void load())); }
      };
      input.addEventListener('input', () => { clearTimeout(timer); expanded.clear(); timer = setTimeout(() => void load(), SUGGESTION_DELAY_MS); });
      sort.addEventListener('change', () => { expanded.clear(); void load(); });
      if (radio) {
        const role = select(node, [['source', 'Source radio'], ['any', 'Any participant']], 'Match radio as');
        role.value = draft.radio_role || (draft.radio_id || draft.radio_identity_key || draft.radio_min ? 'any' : 'source');
        role.addEventListener('change', () => { draft.radio_role = role.value; void load(); });
        panels.append(field('Match radio as', role));
      }
      panels.append(...(target ? [tabs] : []), tools, listTools, selected, feedback, results);
      commit = () => {
        if (single) {
          const key = topic === 'systems' ? 'system_key' : 'site';
          if (topic === 'sites' && (singleChoice || singleCleared)) {
            for (const key of ['site', 'wacn', 'sysid', 'rfss', 'site_id', ...(suggestions.get('site')?.appliedKeys || [])]) draft[key] = '';
          }
          if (singleChoice) {
            if (topic === 'systems' && String(singleChoice.selection?.system_key || singleChoice.id || '') !== draft.system_key) {
              for (const name of ['talkgroups', 'channel_ids', 'targets_any', 'radios', 'talkgroup_id', 'talkgroup_identity_key', 'radio_id', 'radio_identity_key', 'talkgroup_min', 'talkgroup_max', 'radio_min', 'radio_max', 'channel_id', 'site', 'wacn', 'sysid', 'rfss', 'site_id']) draft[name] = '';
              for (const name of ['talkgroup_id', 'radio_id', 'channel_id', 'site']) suggestions.delete(name);
            }
            Object.assign(draft, singleChoice.selection || {}); suggestions.set(key, { ...singleChoice,
              appliedKeys: topic === 'sites' ? Object.keys(singleChoice.selection || {}) : [] });
            if (topic === 'sites') draft.site = singleChoice.label || '';
          } else if (singleCleared) { draft[key] = ''; for (const key of suggestions.get(topic === 'systems' ? 'system_key' : 'site')?.appliedKeys || []) draft[key] = ''; suggestions.delete(key); }
        } else if (radio) {
          const radios = [...choices.values()].map(item => item.selection);
          draft.radios = radios.length ? JSON.stringify(radios) : ''; draft.radio_role ||= 'source';
          for (const key of ['radio_id', 'radio_identity_key', 'radio_min', 'radio_max']) draft[key] = '';
        } else {
          const talkgroups = [...choices.values()].filter(item => item.kind === 'talkgroup').map(item => item.selection);
          const channels = [...choices.values()].filter(item => item.kind === 'channel').map(item => item.selection);
          draft.talkgroups = talkgroups.length ? JSON.stringify(talkgroups) : ''; draft.channel_ids = channels.length ? JSON.stringify(channels) : '';
          draft.targets_any = talkgroups.length || channels.length ? 'true' : '';
          for (const key of ['talkgroup_id', 'talkgroup_identity_key', 'talkgroup_min', 'talkgroup_max', 'channel_id']) draft[key] = '';
        }
      };
    } else if (topic === 'tones') {
      panels.append(node('p', 'recordings-dialog-help', 'Search saved paging tones and DCS codes. A recording without saved detections may still contain audible tones.'));
      const fields = node('div', 'recordings-tone-fields');
      const presence = select(node, [['', 'Any recording'], ['detected', 'Has detected tones']], 'Tones');
      presence.value = draft.paging_tones || (draft.tone_frequency_hz ? 'detected' : '');
      fields.append(field('Tones', presence));
      const first = addInput(fields, 'tone_frequency_hz', 'First tone (Hz)', 'Any frequency', 'number');
      const second = addInput(fields, 'tone_then_hz', 'Then tone (Hz)', 'Optional second tone', 'number');
      const tolerance = addInput(fields, 'tone_tolerance_hz', 'Tolerance (± Hz)', '20', 'number');
      first.min = second.min = '1'; first.max = second.max = '8000'; tolerance.min = '0'; tolerance.max = '1000';
      for (const input of [first, second, tolerance]) input.step = '0.1';
      const sync = () => { const enabled = presence.value === 'detected'; for (const control of [first, second, tolerance]) control.disabled = !enabled; };
      presence.addEventListener('change', () => { draft.paging_tones = presence.value;
        if (!presence.value) for (const key of ['tone_frequency_hz', 'tone_then_hz', 'tone_tolerance_hz']) draft[key] = '';
        if (!presence.value) first.value = second.value = tolerance.value = ''; sync(); });
      panels.append(fields, node('p', 'recordings-dialog-help', 'A single frequency matches any saved tone. Add a second frequency to match the order.'));
      const dcs = addInput(panels, 'dcs_code', 'DCS code', 'Any code, e.g. N023 or I023');
      const recorded = node('div', 'recordings-dcs-choices');
      const moreDcs = button(node, 'Load 10 more', () => void loadDcs(true), 'recordings-load-more'); moreDcs.hidden = true;
      panels.append(recorded, moreDcs);
      let dcsOffset = 0;
      let dcsPeriod = recordingDiscoveryPeriod(draft);
      async function loadDcs(append = false) {
        abort?.abort(); abort = new AbortController(); moreDcs.disabled = true;
        if (!append) { recorded.replaceChildren(); dcsOffset = 0; dcsPeriod = recordingDiscoveryPeriod(draft); }
        try {
          const result = await requestJson(queryPath(ENTITIES, { kind: 'dcs', q: '', sort: 'calls', grouped: true,
            ...dcsPeriod, system_key: draft.system_key, limit: 10, offset: dcsOffset,
            ...(moreDcs.dataset.group ? { group_key: moreDcs.dataset.group } : {}) }), { page: false, signal: abort.signal });
          if (disposed) return;
          const group = result?.groups?.[0];
          for (const row of group?.rows || result?.rows || []) {
            const code = row.selection?.dcs_code || row.value || row.id;
            const choice = button(node, `${row.label || `DCS ${code}`} · ${Number(row.call_count || 0).toLocaleString()} calls`, () => { dcs.value = String(code); draft.dcs_code = String(code); }, 'recordings-secondary-action'); recorded.append(choice);
          }
          dcsOffset = group?.next_offset ?? result?.next_offset ?? dcsOffset + 10;
          moreDcs.hidden = !(group?.has_more || result?.has_more);
          if (group?.group_key) moreDcs.dataset.group = group.group_key;
        } catch (error) { if (!disposed && error.name !== 'AbortError') recorded.append(makeNotice('Recorded DCS codes could not load. You can still enter a code.', 'error')); }
        finally { moreDcs.disabled = false; }
      }
      load = () => loadDcs(); sync(); focus = presence;
    } else {
      const fields = node('div', 'recordings-property-fields');
      for (const [key, label, options] of [
        ['protocol', 'Protocol', [['', 'All protocols'], ['APCO25', 'P25 Phase 1'], ['APCO25_PHASE2', 'P25 Phase 2'], ['DMR', 'DMR'], ['NXDN', 'NXDN'], ['NBFM', 'Analog FM'], ['AM', 'AM'], ['DCS', 'DCS'], ['FLEETSYNC', 'FleetSync'], ['MDC1200', 'MDC1200'], ['LOJACK', 'LoJack'], ['TAIT1200', 'Tait1200']]],
        ['call_type', 'Call type', [['', 'Any call type'], ['CONVENTIONAL', 'Conventional'], ['GROUP', 'Group'], ['DIRECT', 'Direct'], ['PATCH', 'Patch']]],
        ['voice_type', 'Voice', [['', 'Any voice'], ['CLEAR', 'Clear'], ['ENCRYPTED', 'Encrypted'], ['UNKNOWN', 'Unknown']]]]) {
        const control = select(node, options, label); control.value = draft[key];
        control.addEventListener('change', () => { draft[key] = control.value; }); fields.append(field(label, control));
        focus ||= control;
      }
      const frequency = textInput('Any frequency', 'number'); frequency.value = draft.frequency_hz ? String(Number(draft.frequency_hz) / 1e6) : '';
      frequency.step = '0.0001'; frequency.min = '0'; frequency.addEventListener('input', () => { draft.frequency_hz = frequency.value ? String(Math.round(Number(frequency.value) * 1e6)) : ''; }); fields.append(field('Frequency (MHz)', frequency));
      for (const [key, title] of [['min_duration_ms', 'Minimum duration (seconds)'], ['max_duration_ms', 'Maximum duration (seconds)']]) {
        const control = textInput('Any duration', 'number'); control.value = draft[key] ? String(Number(draft[key]) / 1000) : ''; control.min = '0'; control.step = '0.1';
        control.addEventListener('input', () => { draft[key] = control.value ? String(Math.round(Number(control.value) * 1000)) : ''; }); fields.append(field(title, control));
      }
      panels.append(fields);
      if (draft.high_interest) panels.append(node('p', 'recordings-dialog-help', 'High Interest keeps the minimum at 10 seconds or longer.'));
      const transcript = select(node, [['', 'Any transcript state'], ['true', 'Has saved transcript'], ['false', 'No saved transcript']], 'Transcript availability');
      transcript.value = draft.has_transcript || '';
      transcript.addEventListener('change', () => { draft.has_transcript = transcript.value; });
      panels.append(field('Transcript availability', transcript));
      const site = select(node, [['', 'Any site']], 'Site');
      const selectedSite = suggestions.get('site');
      if (draft.site || selectedSite) { const option = node('option', '', selectedSite?.label || draft.site); option.value = 'current'; site.append(option); site.value = 'current'; }
      site.addEventListener('change', () => {
        for (const key of ['site', 'wacn', 'sysid', 'rfss', 'site_id']) draft[key] = '';
        for (const key of suggestions.get('site')?.appliedKeys || []) draft[key] = '';
        suggestions.delete('site');
        if (!site.value || site.value === 'current') return;
        const row = JSON.parse(site.value); Object.assign(draft, row.selection || {});
        draft.site = row.label || '';
        suggestions.set('site', { ...row, appliedKeys: Object.keys(row.selection || {}) });
      });
      panels.append(field('Site', site));
      load = async () => {
        abort = new AbortController();
        try { const result = await requestJson(queryPath(ENTITIES, { kind: 'site', ...recordingDiscoveryPeriod(draft), system_key: draft.system_key, limit: 100, sort: 'calls' }), { page: false, signal: abort.signal });
          if (disposed) return;
          for (const row of result?.rows || []) { const option = node('option', '', `${row.label} · ${row.system_name || row.system_key || 'Unidentified system'}`); option.value = JSON.stringify(row); site.append(option); }
        } catch (error) { if (!disposed && error.name !== 'AbortError') status.replaceChildren(makeNotice('Sites could not load. Close and reopen More filters to retry.', 'error')); }
      };
      const legacy = node('details', 'recordings-exact-filters'); legacy.append(node('summary', '', 'Names, IDs & ranges'));
      const legacyFields = node('div', 'recordings-property-fields');
      for (const [key, label] of [['q', 'System, site, talkgroup, radio, or channel name'], ['alias_list_id', 'Alias List ID'],
        ['talkgroup_id', 'Talkgroup ID'], ['radio_id', 'Radio ID'], ['talkgroup_min', 'First talkgroup ID'], ['talkgroup_max', 'Last talkgroup ID'],
        ['radio_min', 'First radio ID'], ['radio_max', 'Last radio ID'], ['channel_id', 'Channel ID'],
        ['radio_identity_key', 'Exact radio identity'], ['talkgroup_identity_key', 'Exact talkgroup identity'],
        ['wacn', 'WACN'], ['sysid', 'System ID'], ['rfss', 'RFSS'], ['site_id', 'Site ID']]) addInput(legacyFields, key, label);
      legacy.append(legacyFields); panels.append(legacy);
    }
    const cancel = button(node, 'Cancel', () => modal.close(), 'recordings-secondary-action');
    const apply = button(node, ['targets', 'radios', 'systems', 'sites'].includes(topic) ? 'Use selection' : 'Use filters', null, 'recordings-primary-action'); apply.type = 'submit';
    body.append(panels, status, modalFooter(cancel, apply));
    body.addEventListener('submit', event => {
      event.preventDefault(); commit();
      if (!validateSearch(status, draft, suggestions)) return;
      Object.assign(search, draft); selectedSuggestions.clear(); suggestions.forEach((value, key) => selectedSuggestions.set(key, value));
      stagedLabels.forEach((value, key) => identityLabels.set(key, value));
      let finished = false;
      const finish = () => {
        if (finished) return; finished = true;
        replaceSearchForm(); updateDraftFeedback();
        pageHost?.querySelector(`#recordings-open-${topic}`)?.focus();
      };
      if (modal.close(finish)) finish();
    });
    modal = openReadOnlyModal(title, body, { id: 'recordings-filters', className: 'ui-filter-modal recordings-filters-modal',
      returnFocusSelector: `#recordings-open-${topic}`,
      onClose: () => { disposed = true; generation++; abort?.abort(); clearTimeout(timer); } });
    if (!modal || modal.ready && !await modal.ready) return;
    modal.focus(focus || cancel); void load();
  }

  function makeSearchForm() {
    const form = node('form', 'recordings-search');
    const sidebar = node('aside', 'recordings-search-panel'); sidebar.setAttribute('aria-label', 'Recording search');
    const fullText = textInput('Words or "exact phrase"'); fullText.id = 'recordings-input-transcript'; fullText.value = search.transcript; fullText.maxLength = 240;
    const textRange = select(node, [['recent', 'Past 24 hours'], ['time', 'Use Time filter']], 'Full text range'); textRange.value = search.text_range || 'recent';
    const textRangeField = field('Full text range', textRange); textRangeField.hidden = !search.transcript;
    const syntax = node('details', 'recordings-search-syntax'); syntax.append(node('summary', '', 'Search transcript text'), node('p', '', 'Use AND, OR, or quotes.'));
    const range = select(node, [['24h', 'Past 24 hours'], ['1h', 'Past hour'], ['today', 'Today'], ['7d', 'Past 7 days'], ['30d', 'Past 30 days'], ['10y', 'Past 10 years'], ['custom', 'Custom range']], 'Time range');
    range.value = search.range || '24h';
    const from = textInput('', 'datetime-local'), to = textInput('', 'datetime-local');
    from.value = localDateTime(search.from_ms); to.value = localDateTime(search.to_ms);
    const custom = node('div', 'recordings-custom-dates'); custom.append(field('From', from), field('To', to));
    const override = node('p', 'recordings-search-help', 'Full text uses the past 24 hours.');
    const syncTime = () => {
      const recent = Boolean(search.transcript) && textRange.value !== 'time';
      textRangeField.hidden = !search.transcript; range.disabled = recent; override.hidden = !recent;
      custom.hidden = range.value !== 'custom' || recent;
    };
    fullText.addEventListener('input', () => { search.transcript = fullText.value; search.text_range = textRange.value; syncTime(); updateDraftFeedback(); });
    textRange.addEventListener('change', () => { search.text_range = textRange.value; syncTime(); updateDraftFeedback(); });
    range.addEventListener('change', () => { search.range = range.value; syncTime(); updateDraftFeedback(); });
    from.addEventListener('input', () => { const value = new Date(from.value).getTime(); search.from_ms = Number.isFinite(value) ? String(value) : ''; updateDraftFeedback(); });
    to.addEventListener('input', () => { const value = new Date(to.value).getTime(); search.to_ms = Number.isFinite(value) ? String(value + 59_999) : ''; updateDraftFeedback(); });
    syncTime();
    const highState = node('span', 'recordings-interest-state');
    const syncInterest = () => {
      high.setAttribute('aria-pressed', String(Boolean(search.high_interest)));
      highState.textContent = search.high_interest ? 'On' : 'Off';
    };
    const high = addGlyph(button(node, '', () => { search.high_interest = search.high_interest ? '' : '1'; syncInterest(); updateDraftFeedback(); }, 'recordings-interest'), 'icon-filter', 'recordings-interest-icon');
    const highCopy = node('span', 'recordings-interest-copy'); highCopy.append(node('strong', '', 'High Interest'), node('small', '', '10 seconds or longer'));
    high.append(highCopy, highState); syncInterest();
    const pickerButton = (key, title, summary, icon) => {
      const buttonNode = button(node, '', () => void openFilterDialog(key), 'recordings-picker-button');
      buttonNode.id = `recordings-open-${key}`; buttonNode.setAttribute('aria-haspopup', 'dialog');
      const copy = node('span', 'recordings-picker-copy'); copy.append(node('strong', '', title), node('small', '', summary)); buttonNode.append(copy, node('span', 'recordings-picker-chevron', '›')); return addGlyph(buttonNode, icon, 'recordings-control-icon');
    };
    const system = pickerButton('systems', 'System', selectedSuggestions.get('system_key')?.label || search.system_key || 'All systems & channels', 'icon-trunked');
    const count = recordingIdentitySelections(search.talkgroups, 'talkgroup').length + recordingChannelSelections(search.channel_ids).length;
    const targets = pickerButton('targets', 'Talkgroups & channels', count ? `${count} selected · Browse hierarchy` : 'All talkgroups and channels', 'icon-identities');
    const paths = node('div', 'recordings-selection-paths');
    for (const item of recordingIdentitySelections(search.talkgroups, 'talkgroup').slice(0, 3)) paths.append(node('p', '', identityLabels.get(`talkgroup:${recordingIdentitySelectionKey(item)}`) || recordingSelectionText(item, 'talkgroup')));
    for (const id of recordingChannelSelections(search.channel_ids).slice(0, Math.max(0, 3 - paths.children.length))) paths.append(node('p', '', channelLabels.get(id) || `Channel ${id}`));
    const radioCount = recordingIdentitySelections(search.radios, 'radio').length;
    const radios = pickerButton('radios', 'Radios', radioCount ? `${radioCount} selected` : 'Any source · name or radio ID', 'icon-scanner');
    const tones = pickerButton('tones', 'Tones & signaling', search.dcs_code ? `DCS ${search.dcs_code}` : search.tone_frequency_hz ? `${search.tone_frequency_hz}${search.tone_then_hz ? ` → ${search.tone_then_hz}` : ''} Hz` : search.paging_tones ? 'Has detected tones' : 'Tones or DCS', 'icon-audio-quality');
    const more = pickerButton('more', 'More filters', [search.protocol ? protocolLabel(search.protocol) : '', search.site ? 'Site' : '', search.min_duration_ms || search.max_duration_ms ? 'Duration' : ''].filter(Boolean).join(' · ') || 'Protocol, site, duration…', 'icon-filter');
    filterCountHost = node('span', 'recordings-filter-count'); filterCountHost.hidden = true; more.append(filterCountHost);
    const actions = node('div', 'recordings-search-actions');
    draftFeedback = node('p', 'recordings-draft-feedback'); draftFeedback.setAttribute('aria-live', 'polite');
    searchError = node('div', 'recordings-search-error'); searchError.setAttribute('role', 'alert');
    const submit = addGlyph(button(node, 'Search recordings', null, 'recordings-primary-action'), 'icon-search', 'recordings-control-icon'); submit.type = 'submit'; submit.disabled = searchLoading;
    const clear = button(node, 'Clear filters', () => { Object.keys(search).forEach(key => { search[key] = ''; }); search.range = '24h'; selectedSuggestions.clear(); replaceSearchForm(); updateDraftFeedback(); }, 'recordings-text-action');
    const discard = button(node, 'Discard changes', () => { if (appliedSearch) Object.assign(search, appliedSearch); selectedSuggestions.clear(); appliedSuggestions.forEach((value, key) => selectedSuggestions.set(key, value)); replaceSearchForm(); updateDraftFeedback(); }, 'recordings-text-action'); discard.id = 'recordings-discard'; discard.hidden = true;
    actions.append(draftFeedback, searchError, submit, clear, discard);
    sidebar.append(field('Full text', fullText), syntax, textRangeField, field('Time range', range), override, custom, high, system, targets, paths, radios, tones, more, actions);
    const browser = node('div', 'recordings-browser'); browser.append(sidebar); form.append(browser);
    form.addEventListener('submit', event => { event.preventDefault(); submitSearch(); });
    updateDraftFeedback(); return form;
  }

  async function renderSearchPage() {
    const parameters = new URLSearchParams(globalThis.location?.search || '');
    if (parameters.get('recording_filters') === '1') {
      Object.keys(search).forEach(key => { search[key] = parameters.get(key) || ''; });
      search.high_interest = /^(?:1|true)$/i.test(search.high_interest) ? '1' : '';
      search.has_transcript = /^(?:1|true)$/i.test(search.has_transcript) ? 'true' : /^(?:0|false)$/i.test(search.has_transcript) ? 'false' : '';
      search.range = search.from_ms && search.to_ms ? 'custom' : '24h';
      appliedRange = search.range;
      selectedSuggestions.clear();
      for (const [key, names] of [['system_key', ['system_key']], ['talkgroup_id', ['talkgroup_identity_key', 'talkgroup_id']],
        ['radio_id', ['radio_identity_key', 'radio_id']], ['site', ['rfss', 'site_id']],
        ['channel_id', ['channel_id']], ['alias_list_id', ['alias_list_id']]]) {
        const values = names.map(name => search[name]).filter(Boolean);
        if (values.length) selectedSuggestions.set(key, { label: values.join(' · '),
          appliedKeys: key === 'radio_id' && search.radio_identity_key ? ['radio_identity_key'] :
            key === 'talkgroup_id' && search.talkgroup_identity_key ? ['talkgroup_identity_key'] :
            key === 'site' ? ['wacn', 'sysid', 'rfss', 'site_id'] : [] });
      }
    }
    const context = captureRenderContext();
    searchRequest++;
    searchSignal?.abort();
    if (!beginPage(context, pageHeader('Recordings',
      'Find and play saved calls across radio systems and conventional channels.'))) return;
    pageHost = node('div', 'recordings-library data-workspace');
    const host = pageHost;
    host.append(makeNotice('Loading recordings…', 'loading'));
    content.append(host);
    let status;
    try {
      status = await requestJson(STATUS, { signal: context.signal });
    } catch (error) {
      if (renderIsCurrent(context) && host.isConnected) host.replaceChildren(makeNotice(
        error.message || 'Recording availability could not be checked. Try again.', 'error'));
      return;
    }
    if (!renderIsCurrent(context) || !host.isConnected) return;
    host.replaceChildren();
    if (status?.available !== true) {
      host.append(makeNotice('The managed recordings library is unavailable. Check receiver status and try again.',
        'error'));
      return;
    }
    if (!status?.has_calls) {
      const empty = node('section', 'ui-empty-state recordings-library-empty');
      if (status?.mode === 'MANAGED') {
        empty.append(node('h2', '', 'Ready for the first call'), node('p', '',
          'Managed Recordings is enabled. New saved calls will appear here after they finish.'));
        empty.append(button(node, 'Refresh calls', () => void renderSearchPage(),
          'ui-button ui-button-primary'));
      } else if (isPrimaryAdmin()) {
        empty.append(node('h2', '', 'Make every call easy to find'), node('p', '',
          'Enable Managed Recordings to organize calls into folders, search by radio or talkgroup, ' +
          'play them in your browser, and set an optional age limit.'));
        empty.append(anchor('Enable Managed Recordings', href('admin', { tab: 'recordings' }),
          'ui-button ui-button-primary'));
        empty.append(node('p', 'recordings-empty-footnote',
          'Using Trunking Recorder or another tool that watches the flat recordings folder? Keep Classic mode.'));
      } else {
        empty.append(node('h2', '', 'No managed recordings yet'), node('p', '',
          'This receiver saves new calls to the Classic recordings folder. Managed calls will appear here if ' +
          'the primary administrator enables Managed Recordings.'));
      }
      host.append(empty);
      return;
    }
    if (status?.mode === 'CLASSIC') host.append(node('p', 'ui-notice recordings-mode-notice',
      'Classic mode is saving new calls in the flat recordings folder. Earlier Managed calls remain searchable here.'));
    const form = makeSearchForm();
    const browser = form.querySelector('.recordings-browser');
    const resultSection = node('section', 'recordings-results-panel');
    resultSection.setAttribute('aria-label', 'Call results');
    const titleBar = node('div', 'recordings-results-heading');
    resultScope = node('p', 'recordings-results-scope', '');
    titleBar.append(resultScope);
    const titleActions = node('div', 'ui-action-row recordings-results-actions');
    sortControl = select(node, [['desc', 'Newest first'], ['asc', 'Oldest first']], 'Sort calls');
    sortControl.value = parameters.get('recording_filters') === '1' && parameters.get('sort') === 'asc' ? 'asc' : 'desc';
    appliedSort = sortControl.value;
    sortControl.addEventListener('change', () => {
      void loadPage(0, null, { reset: true, sort: sortControl.value });
    });
    const pageSizeField = node('label', 'recordings-page-size-field');
    pageSizeControl = select(node, [['25', '25 per page'], ['50', '50 per page']], 'Calls per page');
    pageSizeControl.classList.add('recordings-page-size');
    pageSizeControl.value = String(pageSize);
    pageSizeControl.addEventListener('change', () => {
      void loadPage(0, null, { reset: true, pageSize: Number(pageSizeControl.value) });
    });
    pageSizeField.append(node('span', 'visually-hidden', 'Calls per page'), pageSizeControl);
    titleActions.append(sortControl, pageSizeField);
    titleBar.append(titleActions);
    const body = node('div', 'recordings-results-body');
    selectedFiltersHost = node('div', 'recordings-selected-filters'); selectedFiltersHost.setAttribute('aria-label', 'Applied filters');
    selectedHost = node('div', 'recordings-selected ui-action-row');
    selectedHost.hidden = true;
    countHost = node('div', 'recordings-result-count');
    countText = node('span');
    searchTiming = node('span', 'recordings-search-timing');
    countHost.append(countText, searchTiming);
    const toolbar = node('div', 'recordings-results-toolbar');
    const resultState = node('div', 'recordings-results-state');
    pageSelect = null;
    if (isPrimaryAdmin()) {
      const pageSelection = node('label', 'recordings-page-selection');
      pageSelect = node('input', 'recordings-page-select ui-selection-check');
      pageSelect.type = 'checkbox';
      pageSelect.setAttribute('aria-label', 'Select all calls on this page');
      pageSelect.disabled = true;
      pageSelect.addEventListener('change', () => {
        currentResults.forEach((row) => pageSelect.checked ? selection.add(String(row.id)) :
          selection.delete(String(row.id)));
        resultHost.querySelectorAll('input.recordings-call-select').forEach((input) => {
          input.checked = pageSelect.checked;
        });
        drawSelection();
      });
      pageSelection.append(pageSelect, node('span', '', 'Select page'));
      resultState.append(pageSelection);
    }
    resultStatus = node('div', 'recordings-result-status');
    resultStatus.setAttribute('role', 'status');
    resultHost = node('div', 'ui-record-grid recordings-results recordings-call-list');
    pagerHosts = [];
    const topPager = makePager('top');
    const bottomPager = makePager('bottom');
    resultState.append(countHost, selectedHost); toolbar.append(resultState, topPager);
    body.append(selectedFiltersHost, toolbar, resultStatus, resultHost, bottomPager);
    resultSection.append(titleBar, body);
    browser.append(resultSection);
    host.append(form);
    if (Object.values(search).every((item) => !item)) search.from_ms = String(Date.now() - 86_400_000);
    currentFilters = effectiveFilters();
    appliedSuggestions = new Map(selectedSuggestions);
    appliedRange = search.transcript && search.text_range !== 'time' ? '24h' : search.range || '24h';
    appliedSearch = { ...search }; updateDraftFeedback();
    cursors = [null];
    currentPage = 0;
    void loadPage(0);
  }

  function adminLink(title, tab) {
    return anchor(title, href('admin', { tab }), 'ui-button ui-button-secondary');
  }

  function adminActions(...controls) {
    const actions = node('div', 'ui-action-row');
    actions.append(...controls);
    return actions;
  }

  function confirmAdminAction({ title, description, confirmLabel, perform, onError, danger = false,
    details = [] }) {
    const body = node('div', 'recordings-admin-dialog-body');
    const status = node('div', 'recordings-form-status');
    status.setAttribute('role', 'status');
    let modal;
    const cancel = button(node, 'Cancel', () => modal?.close());
    const confirm = button(node, confirmLabel, async () => {
      confirm.disabled = true;
      cancel.disabled = true;
      modal.setBusy(true);
      try {
        await perform();
        modal.setBusy(false);
        modal.close();
      } catch (error) {
        onError?.(error);
        status.replaceChildren(makeNotice(error.message || `${title} could not be completed. Try again.`, 'error'));
      } finally {
        modal.setBusy(false);
        confirm.disabled = false;
        cancel.disabled = false;
      }
    }, danger ? 'ui-button ui-button-danger' : 'ui-button ui-button-primary');
    body.append(node('p', '', description), ...details, status, modalFooter(cancel, confirm));
    modal = openReadOnlyModal(title, body, { id: 'recordings-administration-action' });
  }

  async function renderAdminRecordings(options = {}) {
    const wrapper = node('div', 'recordings-admin editor-workspace');
    const status = node('div', 'recordings-form-status');
    status.setAttribute('role', 'status');
    wrapper.append(status);
    content.append(wrapper);
    try {
      const [settings, catalog] = await Promise.all([
        requestJson(`${ADMIN}/settings`), requestJson(`${ADMIN}/status`)
      ]);
      if (!wrapper.isConnected) return;
      const modeBody = node('form', 'recordings-admin-body');
      const modes = node('div', 'recordings-mode-choices');
      const radios = {};
      for (const [mode, title, detail] of [
        ['CLASSIC', 'Classic', 'Keep a flat recordings folder for tools such as Trunking Recorder.'],
        ['MANAGED', 'Managed · Recommended', 'Organize calls automatically, search by radio or talkgroup, ' +
          'listen in your browser, and clear old calls by age.']
      ]) {
        const choice = node('label', 'ui-choice-card recordings-mode-choice');
        const radio = node('input', 'ui-choice-radio');
        radio.type = 'radio';
        radio.name = 'recording-mode';
        radio.value = mode;
        radio.checked = settings?.mode === mode;
        radios[mode] = radio;
        choice.append(radio, node('strong', '', title), node('span', '', detail));
        modes.append(choice);
      }
      const folder = node('dl', 'ui-fact-list recordings-managed-folder');
      appendFact(node, folder, 'Managed folder', settings?.managed_directory);
      const modeStatus = node('div', 'recordings-form-status');
      modeStatus.setAttribute('role', 'status');
      const saveMode = button(node, 'Save mode', null, 'ui-button ui-button-primary');
      saveMode.type = 'submit';
      const selectedMode = () => radios.MANAGED.checked ? 'MANAGED' : 'CLASSIC';
      const modeWorkflow = createFormWorkflow({ form: modeBody, submit: saveMode, feedback: modeStatus,
        changed: () => selectedMode() !== settings.mode });
      modeBody.addEventListener('submit', async (event) => {
        event.preventDefault();
        const mode = selectedMode();
        await modeWorkflow.save(async () => {
          await requestJson(`${ADMIN}/settings`, { method: 'PUT', body: { mode }, page: false });
          settings.mode = mode;
        }, { onError: (error) => ({ state: 'error', message: error.message || 'Mode could not be saved.' }) });
      });
      const modeFooter = node('div', 'settings-form-footer');
      modeFooter.append(modeStatus, adminActions(saveMode, adminLink('Audio quality settings', 'audio-quality')));
      modeBody.append(modes, folder, node('p', 'recordings-admin-note',
        'Managed calls are saved as MP3 for browser playback. The recording format setting applies to Classic mode. ' +
        'Existing managed calls stay searchable when you switch to Classic, and their age limit still applies.'),
      modeFooter);
      const ageBody = node('form', 'recordings-admin-body');
      ageBody.noValidate = true;
      const age = textInput('No age limit', 'number');
      age.min = '1';
      age.step = '1';
      age.value = settings?.retention_days == null ? '' : String(settings.retention_days);
      const ageStatus = node('div', 'recordings-form-status');
      ageStatus.setAttribute('role', 'status');
      const saveAge = button(node, 'Save age limit', null, 'ui-button ui-button-primary');
      saveAge.type = 'submit';
      const ageWorkflow = createFormWorkflow({ form: ageBody, submit: saveAge, feedback: ageStatus,
        changed: () => numberOrNull(age.value) !== (settings.retention_days ?? null) });
      ageBody.addEventListener('submit', async (event) => {
        event.preventDefault();
        const days = numberOrNull(age.value);
        if (age.value && (!Number.isInteger(days) || days < 1)) {
          ageWorkflow.showFeedback('error', 'Enter a whole number of days, or leave the field blank.');
          return;
        }
        await ageWorkflow.save(async () => {
          await requestJson(`${ADMIN}/settings`, { method: 'PUT', body: { retention_days: days }, page: false });
          settings.retention_days = days;
        }, { onError: (error) => ({ state: 'error', message: error.message || 'Age limit could not be saved.' }) });
      });
      const ageField = field('Remove managed calls older than (days)', age);
      ageField.append(node('small', 'ui-field-detail', 'Leave blank for no age limit. The age limit continues to ' +
        'apply to existing managed calls in Classic mode.'));
      const ageFooter = node('div', 'settings-form-footer');
      ageFooter.append(ageStatus, adminActions(saveAge, adminLink('Transcription settings', 'transcription')));
      ageBody.append(ageField, ageFooter);
      const catalogBody = node('div', 'recordings-admin-body recordings-catalog-body');
      const catalogSummary = node('div', 'recordings-catalog-summary');
      const catalogCount = node('div', 'recordings-catalog-count');
      const catalogFacts = node('dl', 'ui-facts ui-record-facts recordings-catalog-facts');
      catalogSummary.append(catalogCount, catalogFacts);
      const stat = (name, detail) => {
        if (detail === null || detail === undefined || detail === '') return;
        const fact = node('div', 'ui-fact');
        const description = node('dd');
        description.append(detail);
        fact.append(node('dt', '', name), description);
        catalogFacts.append(fact);
      };
      const operationStatus = node('div', 'recordings-form-status');
      operationStatus.setAttribute('role', 'status');
      let currentCatalog = catalog;
      const operation = (snapshot) => snapshot?.maintenance || snapshot?.operation || snapshot || {};
      const operationState = (snapshot) => String(value(operation(snapshot), 'state', 'status') || 'IDLE')
        .toUpperCase();
      const activeOperation = (snapshot) => ['RUNNING', 'QUEUED', 'IN_PROGRESS'].includes(operationState(snapshot));
      const drawCatalog = (snapshot) => {
        currentCatalog = snapshot;
        catalogFacts.replaceChildren();
        const counts = snapshot?.catalog || snapshot;
        catalogCount.replaceChildren(metrics([
          ['Listed calls', Number(value(counts, 'call_count', 'total_calls') || 0)]
        ], true));
        stat('Oldest call', dateTime(value(counts, 'oldest_call_ms', 'oldest_start_ms'), true));
        stat('Last recount', dateTime(value(snapshot, 'last_recount_ms')));
        const state = operationState(snapshot);
        const statePill = node('span', `ui-pill${state === 'FAILED' ? ' ui-pill-danger' : ''}`,
          prettify(state));
        stat('Maintenance', statePill);
        const current = operation(snapshot);
        if (activeOperation(snapshot)) operationStatus.replaceChildren(makeNotice(
          `${prettify(value(current, 'kind') || 'Maintenance')} running` +
            `${value(current, 'inspected') !== null ? ` · ${Number(current.inspected).toLocaleString()} checked` : ''}`,
          'loading'));
        else if (state === 'FAILED') operationStatus.replaceChildren(makeNotice(
          'Maintenance could not finish. Review the receiver log before trying again.', 'error'));
        else if (state === 'COMPLETED') operationStatus.replaceChildren(makeNotice(
          'Maintenance finished. Call totals are current.'));
        else operationStatus.replaceChildren();
      };
      const refreshCatalog = async () => {
        const latest = await requestJson(`${ADMIN}/status`, { page: false });
        if (wrapper.isConnected) {
          drawCatalog(latest);
        }
        return latest;
      };
      const refreshStatus = button(node, 'Refresh status', async () => {
        refreshStatus.disabled = true;
        try { await refreshCatalog(); }
        catch (error) {
          operationStatus.replaceChildren(makeNotice(error.message ||
            'Call catalog status could not be loaded. Try again.', 'error'));
        } finally { refreshStatus.disabled = false; }
      });
      const watchMaintenance = async (initial) => {
        let latest = initial;
        while (wrapper.isConnected && activeOperation(latest)) {
          await new Promise((resolve) => window.setTimeout(resolve, 1_500));
          if (!wrapper.isConnected) return;
          try { latest = await refreshCatalog(); }
          catch (error) {
            operationStatus.replaceChildren(makeNotice(error.message || 'Maintenance status could not be read.',
              'error'));
            return;
          }
        }
        if (wrapper.isConnected && initial !== latest) {
          const failed = operationState(latest) === 'FAILED';
          operationStatus.replaceChildren(makeNotice(failed ?
            'Maintenance could not finish. Review the receiver log before trying again.' :
            'Maintenance finished. Call totals are current.', failed ? 'error' : ''));
        }
      };
      drawCatalog(catalog);
      const reindexWarning = 'On a very large library, reindexing can delay new managed calls and may cause some ' +
        'to be dropped. Run during quiet reception.';
      const reindexLimitation = 'Reindex checks listed calls. It does not recover unlisted audio files.';
      const maintenance = node('div', 'recordings-maintenance');
      const run = (name, description, action) => {
        const row = node('div', 'recordings-maintenance-row');
        const copy = node('div', 'ui-record-card-copy');
        copy.append(node('strong', 'ui-record-title', name), node('p', 'recordings-admin-note', description));
        const control = button(node, `Run ${name.toLowerCase()}`, () => {
          const details = [];
          if (action === 'recount') {
            const counts = currentCatalog?.catalog || currentCatalog;
            details.push(metrics([
              ['Listed calls', Number(value(counts, 'call_count', 'total_calls') || 0)]
            ], true));
            const lastRecount = dateTime(value(currentCatalog, 'last_recount_ms'));
            if (lastRecount) {
              const facts = node('dl', 'ui-facts ui-admin-facts recordings-admin-facts');
              const fact = node('div', 'ui-fact');
              fact.append(node('dt', '', 'Last recount'), node('dd', '', lastRecount));
              facts.append(fact);
              details.push(facts);
            }
          } else {
            details.push(node('div', 'ui-notice ui-notice-warning', reindexWarning),
              node('p', 'recordings-admin-note', reindexLimitation));
          }
          confirmAdminAction({
            title: name, confirmLabel: `Run ${name.toLowerCase()}`, details,
            description: action === 'recount' ? 'Refresh the displayed call total for the managed recording catalog.' :
              'Check listed calls and remove broken entries from the managed call catalog.',
            perform: async () => {
              control.disabled = true;
              operationStatus.replaceChildren(makeNotice(`${name} started…`, 'loading'));
              try {
                await requestJson(`${ADMIN}/${action}`, { method: 'POST', page: false,
                  timeoutMs: 60_000 });
                const latest = await refreshCatalog();
                if (activeOperation(latest)) void watchMaintenance(latest).finally(() => {
                  control.disabled = false;
                });
                else {
                  operationStatus.replaceChildren(makeNotice(`${name} complete.`));
                  control.disabled = false;
                }
              } catch (error) {
                control.disabled = false;
                throw error;
              }
            },
            onError: (error) => operationStatus.replaceChildren(makeNotice(error.message ||
              `${name} could not be started.`, 'error'))
          });
        });
        row.append(copy, control);
        maintenance.append(row);
      };
      run('Recount calls', 'Refresh the displayed call total.', 'recount');
      run('Reindex calls', 'Check listed calls and remove broken entries.', 'reindex');
      const maintenanceDisclosure = node('details', 'ui-section-disclosure ui-section-disclosure-flat');
      maintenanceDisclosure.append(node('summary', 'ui-section-summary', 'Maintenance'), maintenance,
        node('div', 'ui-notice ui-notice-warning recordings-maintenance-warning', reindexWarning),
        node('p', 'recordings-admin-note recordings-maintenance-note', reindexLimitation));
      catalogBody.append(catalogSummary, operationStatus, maintenanceDisclosure);
      wrapper.append(section('Recording mode', modeBody));
      if (options.classicFormatPanel) wrapper.append(options.classicFormatPanel);
      wrapper.append(section('Managed call retention', ageBody),
        section('Call catalog', catalogBody, refreshStatus));
      if (activeOperation(catalog)) void watchMaintenance(catalog);
    } catch (error) {
      if (wrapper.isConnected) status.replaceChildren(makeNotice(error.message ||
        'Recording settings could not be loaded. Try again.', 'error'));
    }
  }

  async function renderAdminTranscription() {
    const wrapper = node('div', 'recordings-admin editor-workspace');
    const status = node('div', 'recordings-form-status');
    status.setAttribute('role', 'status');
    wrapper.append(status);
    content.append(wrapper);
    try {
      const [settings, catalog] = await Promise.all([
        requestJson(`${ADMIN}/settings`), requestJson(`${ADMIN}/status`)
      ]);
      if (!wrapper.isConnected) return;
      const transcriptionBody = node('form', 'recordings-admin-body');
      transcriptionBody.noValidate = true;
      const enabledField = uiToggleField('Transcribe managed calls',
        settings?.transcription_enabled === true, 'Transcribe managed calls',
        'Includes retained calls that have not been transcribed.');
      const enabled = enabledField.querySelector('input');
      const endpoint = textInput('http://127.0.0.1:8000/v1/audio/transcriptions', 'url');
      endpoint.value = settings?.transcription_url || '';
      const model = textInput('Model ID', 'text');
      model.value = settings?.transcription_model || '';
      const minimum = textInput('500', 'number');
      minimum.min = '500';
      minimum.max = '600000';
      minimum.step = '1';
      minimum.value = String(settings?.transcription_min_duration_ms ?? 500);
      const key = textInput(settings?.transcription_key_configured ? 'Leave blank to keep saved key' :
        'Optional API key', 'password');
      key.autocomplete = 'new-password';
      const keyField = field('API key', key);
      const keyState = node('small', 'ui-field-detail', settings?.transcription_key_configured ?
        'An API key is saved on this receiver.' : 'No API key is saved.');
      keyField.append(keyState);
      const clearKey = button(node, 'Clear saved key', () => {
        confirmAdminAction({
          title: 'Clear saved transcription key', confirmLabel: 'Clear saved key', danger: true,
          description: 'Clear the saved transcription API key from this receiver.',
          details: [node('p', 'recordings-admin-note', 'The endpoint and model remain configured. A service that ' +
            'requires a key may stop accepting transcription requests.')],
          perform: async () => {
            clearKey.disabled = true;
            try {
              await requestJson(`${ADMIN}/settings`, { method: 'PUT',
                body: { transcription_clear_api_key: true }, page: false });
              key.value = '';
              settings.transcription_key_configured = false;
              keyState.textContent = 'No API key is saved.';
              key.placeholder = 'Optional API key';
              clearKey.hidden = true;
              transcriptionWorkflow.showFeedback('status', '');
              transcriptionWorkflow.refresh();
            } finally { clearKey.disabled = false; }
          },
          onError: (error) => transcriptionWorkflow.showFeedback('error', error.message ||
            'API key could not be cleared.')
        });
      });
      clearKey.hidden = settings?.transcription_key_configured !== true;
      const transcriptionFields = node('div', 'recordings-transcription-fields');
      const endpointField = field('Transcription endpoint URL', endpoint);
      endpointField.classList.add('recordings-transcription-wide');
      keyField.classList.add('recordings-transcription-wide');
      const minimumField = field('Minimum call length (ms)', minimum);
      minimumField.append(node('small', 'ui-field-detail', 'Only calls at or above this length are eligible.'));
      transcriptionFields.append(endpointField, field('Model ID', model), minimumField, keyField);
      const transcriptionStatus = node('div', 'recordings-form-status');
      transcriptionStatus.setAttribute('role', 'status');
      const saveTranscription = button(node, 'Save transcription settings', null, 'ui-button ui-button-primary');
      saveTranscription.type = 'submit';
      const transcriptionValues = () => ({ transcription_enabled: enabled.checked,
        transcription_url: endpoint.value.trim(), transcription_model: model.value.trim(),
        transcription_min_duration_ms: numberOrNull(minimum.value) });
      let confirmedTranscription = transcriptionValues();
      const transcriptionChanged = () => {
        const draft = transcriptionValues();
        return Boolean(key.value) || Object.entries(draft).some(([name, value]) => value !== confirmedTranscription[name]);
      };
      const transcriptionWorkflow = createFormWorkflow({ form: transcriptionBody, submit: saveTranscription,
        feedback: transcriptionStatus, changed: transcriptionChanged });
      transcriptionBody.addEventListener('submit', async (event) => {
        event.preventDefault();
        const milliseconds = numberOrNull(minimum.value);
        if (!Number.isInteger(milliseconds) || milliseconds < 500 || milliseconds > 600_000) {
          transcriptionWorkflow.showFeedback('error', 'Enter a whole number from 500 to 600,000 milliseconds.');
          return;
        }
        if (enabled.checked && (!endpoint.value.trim() || !model.value.trim())) {
          transcriptionWorkflow.showFeedback('error', 'Enter a transcription endpoint URL and model ID.');
          return;
        }
        const body = transcriptionValues();
        if (key.value) body.transcription_api_key = key.value;
        await transcriptionWorkflow.save(async () => {
          await requestJson(`${ADMIN}/settings`, { method: 'PUT', body, page: false });
          confirmedTranscription = transcriptionValues();
          if (key.value) {
            settings.transcription_key_configured = true;
            key.value = '';
            key.placeholder = 'Leave blank to keep saved key';
            keyState.textContent = 'An API key is saved on this receiver.';
            clearKey.hidden = false;
          }
        }, { onError: (error) => ({ state: 'error', message: error.message ||
          'Transcription settings could not be saved.' }) });
      });
      const transcriptionProgress = node('div', 'recordings-transcription-progress');
      const drawTranscription = (snapshot) => {
        transcriptionProgress.replaceChildren();
        const progress = snapshot?.transcription || {};
        const counts = [
          ['Pending', progress.pending], ['Completed', progress.completed], ['Failed', progress.failed]
        ].filter(([, count]) => count !== null && count !== undefined);
        if (counts.length) {
          const countTiles = metrics(counts, true);
          countTiles.classList.add('ui-metric-grid-fit', 'ui-metric-grid-inline', 'recordings-transcription-counts');
          transcriptionProgress.append(countTiles);
        }
        if (progress.active) {
          const worker = node('dl', 'ui-facts ui-admin-facts recordings-transcription-worker');
          const fact = node('div', 'ui-fact recordings-transcription-worker-row');
          const status = node('dd');
          status.append(node('span', 'ui-pill state-current', 'Transcribing a call'));
          fact.append(node('dt', '', 'Worker'), status);
          worker.append(fact);
          transcriptionProgress.append(worker);
        }
        if (progress.last_error) {
          const error = node('div', 'ui-notice ui-notice-danger recordings-transcription-error');
          error.append(node('strong', '', 'Last error'), node('p', '', String(progress.last_error)));
          transcriptionProgress.append(error);
        }
      };
      drawTranscription(catalog);
      const keyActions = node('div', 'ui-action-row');
      keyActions.append(saveTranscription, clearKey);
      const transcriptionFooter = node('div', 'settings-form-footer');
      transcriptionFooter.append(transcriptionStatus, keyActions);
      transcriptionBody.append(enabledField, transcriptionFields, transcriptionFooter);
      const progressBody = node('div', 'recordings-admin-body');
      const progressStatus = node('div', 'recordings-form-status');
      progressStatus.setAttribute('role', 'status');
      const refreshStatus = button(node, 'Refresh status', async () => {
        refreshStatus.disabled = true;
        try {
          const latest = await requestJson(`${ADMIN}/status`, { page: false });
          if (wrapper.isConnected) {
            drawTranscription(latest);
            progressStatus.replaceChildren();
          }
        } catch (error) {
          if (wrapper.isConnected) progressStatus.replaceChildren(makeNotice(error.message ||
            'Transcription status could not be loaded. Try again.', 'error'));
        } finally { refreshStatus.disabled = false; }
      });
      progressBody.append(transcriptionProgress, progressStatus,
        adminActions(adminLink('Recording settings', 'recordings')));
      wrapper.append(section('Transcription progress', progressBody, refreshStatus),
        section('Transcription settings', transcriptionBody));
    } catch (error) {
      if (wrapper.isConnected) status.replaceChildren(makeNotice(error.message ||
        'Transcription settings could not be loaded. Try again.', 'error'));
    }
  }

  return { renderSearchPage, renderAdminRecordings, renderAdminTranscription, stopAudio, playback };
}
