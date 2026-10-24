// Fictional affiliations and saved activity for the production P25 graph. Every
// request and stream stays inside this provider, including refresh and settings.
const SYSTEM = 'p25:bee00:101';
const CONFIGURATION = 'cafe2500-0000-4000-8000-000000000001';
const EPOCH = 'guide-p25-visualizer';
const clone = value => structuredClone(value);
const groupKey = id => `v1-g-bee00-101-${id}`;
const radioKey = id => `v1-r-bee00-101-${id}`;
const groups = [[1001, 'County Sheriff'], [1002, 'Regional Fire'], [1003, 'Fireground'], [1004, 'Public Works']];
const radios = [[2101, 'Sheriff 12', 1001], [2102, 'Sheriff 18', 1001],
  [3101, 'Engine 7', 1002], [3102, 'Rescue 2', 1002],
  [3103, 'Fire Command', 1003], [4101, 'Road Crew 4', 1004]];

export function createNetworkVisualizerGuideData({ signal } = {}) {
  const requests = [], connections = new Set();
  let disposed = false, sequence = 0, nextId = radios.length;
  const now = Date.now();
  const check = options => {
    if (disposed || signal?.aborted || options?.signal?.aborted)
      throw new DOMException('The P25 Visualizer example was closed.', 'AbortError');
  };
  const affiliation = ([id, name, groupId], at = now - 45_000) => ({
    radio_system_key: SYSTEM, radio_identity_key: radioKey(id), talkgroup_identity_key: groupKey(groupId),
    configuration_id: CONFIGURATION, confirmed_at_ms: at, protocol: 'p25', system_name: 'County P25',
    radio_id: id, talkgroup_id: groupId, radio_alias_name: name,
    talkgroup_alias_name: groups.find(group => group[0] === groupId)[1]
  });
  const activity = ([id, name, groupId], index, action = 'CALL', at = now - (index + 1) * 20_000) => ({
    id: index + 1, observed_at_ms: at, action, protocol: 'p25', channel_kind: 'trunked_site',
    radio_system_key: SYSTEM, system_name: 'County P25', configuration_id: CONFIGURATION,
    source_identity_key: radioKey(id), source_radio_id: id, source_alias_name: name,
    target_identity_key: groupKey(groupId), target_kind: 'talkgroup', target_id: groupId,
    target_alias_name: groups.find(group => group[0] === groupId)[1]
  });
  const rows = [...radios.map((radio, index) => activity(radio, index)),
    activity([2102, 'Sheriff 18', 1004], 6, 'JOIN', now - 180_000),
    activity([3102, 'Rescue 2', 1003], 7, 'CALL', now - 10_000)];
  nextId = rows.length;
  const current = new Map(radios.map(radio => [radio[0], affiliation(radio)]));
  const requestActivitySeed = async (parameters = {}, options = {}) => {
    check(options);
    if (Object.keys(parameters).some(key => key !== 'refresh')) throw new Error('Unknown P25 example refresh.');
    requests.push({ kind: 'seed', parameters: clone(parameters) });
    return { source_epoch: EPOCH, sequence, available: true, collection_enabled: true,
      captured_at_ms: Date.now(), traffic_grant_age_out_milliseconds: 1200,
      affiliations: clone([...current.values()]) };
  };
  const requestActivity = async (parameters = {}, options = {}) => {
    check(options);
    if (parameters.projection !== 'p25_visualizer' ||
        Object.keys(parameters).some(key => !['from_ms', 'to_ms', 'actions', 'projection', 'limit',
          'after_id', 'watermark_id'].includes(key))) throw new Error('Unknown P25 example history.');
    requests.push({ kind: 'history', parameters: clone(parameters) });
    const watermark = Number(parameters.watermark_id ?? nextId), after = Number(parameters.after_id || 0);
    return { rows: clone(rows.filter(row => row.id > after && row.id <= watermark)),
      next_after_id: watermark, watermark_id: watermark, has_more: false };
  };
  const openActivityStream = callbacks => {
    check();
    let closed = false, timer = 0, move = false;
    const connection = { close() {
      if (closed) return;
      closed = true;
      clearInterval(timer);
      connections.delete(connection);
    } };
    connections.add(connection);
    queueMicrotask(() => {
      if (closed || disposed || signal?.aborted) return;
      callbacks.boundary({ source_epoch: EPOCH, sequence, watermark_id: nextId,
        history_generation: 0, available: true, collection_enabled: true, server_time_ms: Date.now(),
        traffic_grant_age_out_milliseconds: 1200 });
      timer = setInterval(() => {
        if (closed || disposed || signal?.aborted || globalThis.document?.hidden) return;
        move = !move;
        const radio = [3101, 'Engine 7', move ? 1003 : 1002], at = Date.now();
        const fact = affiliation(radio, at);
        current.set(radio[0], fact);
        const base = sequence++;
        callbacks.delta({ source_epoch: EPOCH, base_sequence: base, sequence, part: 0, parts: 1,
          affiliations: [clone(fact)], removed_radio_keys: [], available: true, collection_enabled: true });
        const row = activity(radio, nextId++, 'CALL', at);
        rows.push(row);
        callbacks.append({ source_epoch: EPOCH, history_generation: 0, after_id: row.id - 1,
          next_after_id: row.id, watermark_id: row.id, rows: [clone(row)] });
      }, 7000);
    });
    return connection;
  };
  const dispose = () => {
    disposed = true;
    [...connections].forEach(connection => connection.close());
  };
  signal?.addEventListener('abort', dispose, { once: true });
  return { guideMode: true, requestActivity, requestActivitySeed, openActivityStream,
    loadEventSettings: () => null,
    saveEventSettings: async () => { check(); }, systemHref: () => '',
    requests, dispose };
}
