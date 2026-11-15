const BSI_FRESHNESS_MS = 60 * 60 * 1000;
const SOURCE_LABELS = { control: 'Control', voice: 'Voice' };

function observationOrder(left, right) {
  return (right.lastHeardMs ?? 0) - (left.lastHeardMs ?? 0) ||
    (left.source === right.source ? left.callsign.localeCompare(right.callsign) :
      left.source === 'control' ? -1 : 1);
}

function lastHeardMs(observation) {
  const value = observation?.lastHeardMs;
  if (!['number', 'string'].includes(typeof value) || String(value).trim() === '') return null;
  const timestamp = Number(value);
  return Number.isFinite(timestamp) && timestamp > 0 ? timestamp : null;
}

/** Only an actual BSI reception supplies the freshness clock. */
export function bsiObservationFreshness(observation, now = Date.now()) {
  const timestamp = lastHeardMs(observation);
  return {
    lastHeardMs: timestamp,
    freshness: timestamp === null ? null : now - timestamp <= BSI_FRESHNESS_MS ? 'Fresh' : 'Historical'
  };
}

/** Summarize confirmed observations for one site/carrier, retaining both sources as evidence. */
export function summarizeBsi(observations) {
  const bySource = new Map();
  for (const observation of Array.isArray(observations) ? observations : []) {
    if (!Object.hasOwn(SOURCE_LABELS, observation?.source)) continue;
    const current = {
      source: observation.source,
      callsign: String(observation.callsign ?? '').trim(),
      lastHeardMs: lastHeardMs(observation)
    };
    if (!current.callsign) continue;
    const previous = bySource.get(current.source);
    if (!previous || observationOrder(current, previous) < 0) bySource.set(current.source, current);
  }
  const evidence = [...bySource.values()].sort(observationOrder);
  const primary = evidence[0];
  const matchingSources = new Set(evidence.filter((observation) => observation.callsign === primary?.callsign)
    .map((observation) => observation.source));
  return {
    callsign: primary?.callsign ?? '',
    sourceLabel: matchingSources.size === 2 ? 'Control + voice' : SOURCE_LABELS[primary?.source] ?? '',
    lastHeardMs: primary?.lastHeardMs ?? null,
    conflict: new Set(evidence.map((observation) => observation.callsign)).size > 1,
    observations: evidence
  };
}
