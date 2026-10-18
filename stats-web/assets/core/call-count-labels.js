const HOUR_END_OFFSET_MS = 3_600_000 - 1;

/** Shared by count help and column descriptions. */
export const CALL_COUNT_HELP = 'Matching copies of a call heard on several channels count as one call. ' +
  'Copies that cannot be matched may count separately.';

function channelOwnedSystem(row) {
  return /^(?:dmr|nxdn-c|nxdn-d):channel:/i.test(String(row?.radio_system_key || '').trim());
}

/** Some systems are identified only within their saved channel. */
export function systemCallTitle(row) {
  return channelOwnedSystem(row) ? 'Calls heard on this channel' : 'Calls heard across this system';
}

/** Observed identities use different counter owners depending on protocol and channel type. */
export function observedCallPresentation(row, { assigned = false } = {}) {
  const protocol = String(row?.protocol || ({ 1: 'P25', 3: 'DMR', 4: 'NXDN' })[row?.protocol_code] || '')
    .trim().toUpperCase();
  const p25 = protocol === 'P25' || protocol.startsWith('APCO25') ||
    String(row?.decoder || '').trim().toUpperCase().startsWith('P25');
  const trunked = String(row?.topology || '').trim().toUpperCase() === 'TRUNKED';
  const channel = String(row?.channel_names || '').trim() || 'this channel';
  if (!assigned && trunked && !p25) {
    return { title: channelOwnedSystem(row) ? `Calls heard on ${channel}` : systemCallTitle(row),
      countLabel: 'Calls', dateMode: 'hourly' };
  }
  return {
    title: `Calls heard on ${channel}`,
    countLabel: !assigned && trunked && p25 ? 'Calls heard' : 'Calls',
    dateMode: assigned || (p25 && trunked) || (protocol === 'DMR' && !trunked) ?
      'observed' : 'hourly'
  };
}

function timestamp(value) {
  if (!['number', 'string'].includes(typeof value) || value === '') return null;
  const result = Number(value);
  return Number.isFinite(result) && result > 0 ? result : null;
}

function hourEnd(value, asOf) {
  const start = timestamp(value);
  if (start === null) return null;
  const end = start + HOUR_END_OFFSET_MS;
  const limit = timestamp(asOf);
  return limit === null ? end : Math.min(end, limit);
}

/** Dates belong to a counter's own source; identity activity dates are never a fallback. */
export function callCountDateRows(row, mode) {
  switch (mode) {
    case 'totals':
      return [['Since', timestamp(row?.call_totals_started_at_ms)],
        ['As of', timestamp(row?.call_totals_as_of_ms)]];
    case 'system':
      return [['From', timestamp(row?.calls_from_ms)],
        ['To', timestamp(row?.call_totals_as_of_ms) === null ? null :
          hourEnd(row?.calls_through_hour_ms, row?.call_totals_as_of_ms)]];
    case 'channels': {
      const latest = timestamp(row?.channel_observations_to_ms);
      return [['From', timestamp(row?.channel_observations_from_ms)],
        latest === null ? ['To', hourEnd(row?.channel_observations_through_hour_ms,
          row?.call_totals_as_of_ms)] : ['Latest call', latest]];
    }
    case 'selected':
      return [['From', timestamp(row?.from_ms)], ['To', timestamp(row?.to_ms)]];
    case 'observed':
      return [['From', timestamp(row?.first_seen_ms)], ['Latest call', timestamp(row?.last_seen_ms)]];
    case 'hourly':
      return [['From', timestamp(row?.first_seen_ms)], ['To', hourEnd(row?.last_seen_ms,
        row?.counts_as_of_ms)]];
    default:
      return [['From', null], ['To', null]];
  }
}
