// Fictional live observations for the production P25 graph. Every subscription
// stays inside this provider and has no receiver or preference side effects.
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
  const requests = [], connections = new Set(), activitySources = new Set();
  let disposed = false, sequence = 0, nextId = 0;
  const check = () => {
    if (disposed || signal?.aborted)
      throw new DOMException('The P25 Visualizer example was closed.', 'AbortError');
  };
  const affiliation = ([id, name, groupId], at = Date.now()) => ({
    radio_system_key: SYSTEM, radio_identity_key: radioKey(id), talkgroup_identity_key: groupKey(groupId),
    configuration_id: CONFIGURATION, confirmed_at_ms: at, protocol: 'p25', system_name: 'County P25',
    radio_id: id, talkgroup_id: groupId, radio_alias_name: name,
    talkgroup_alias_name: groups.find(group => group[0] === groupId)[1]
  });
  const current = new Map(radios.map(radio => [radio[0], affiliation(radio)]));
  const tables = () => [{ table_id: 'guide-p25', configuration_id: CONFIGURATION,
    system_name: 'County P25', channel_running: true,
    rows: [...current.values()].map((fact, index) => ({
      key: `guide-p25-${fact.radio_id}`, protocol: 'P25', serving_system_key: SYSTEM,
      system_name: 'County P25', configuration_id: CONFIGURATION, status: 'CALL', activation_order: index + 1,
      source_identity_key: fact.radio_identity_key, source_identity_kind_code: 2,
      source_form: 'RADIO', source_id: fact.radio_id, source_alias: fact.radio_alias_name,
      target_identity_key: fact.talkgroup_identity_key, target_kind: 'talkgroup',
      target_form: 'TALKGROUP', target_id: fact.talkgroup_id, target_alias: fact.talkgroup_alias_name
    })) }];
  const subscribeActivity = callbacks => {
    check();
    const source = { callbacks, close() { activitySources.delete(source); } };
    activitySources.add(source);
    queueMicrotask(() => {
      if (disposed || signal?.aborted || !activitySources.has(source)) return;
      callbacks.snapshot?.({ tables: clone(tables()) });
    });
    return source;
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
        available: true, collection_enabled: true, server_time_ms: Date.now() });
      timer = setInterval(() => {
        if (closed || disposed || signal?.aborted || globalThis.document?.hidden) return;
        move = !move;
        const radio = [3101, 'Engine 7', move ? 1003 : 1002], at = Date.now();
        const previous = current.get(radio[0]), fact = affiliation(radio, at);
        current.set(radio[0], fact);
        const base = sequence++;
        callbacks.delta({ source_epoch: EPOCH, base_sequence: base, sequence, part: 0, parts: 1,
          affiliations: [{ ...clone(fact), previous_affiliation: clone(previous) }],
          removed_radio_keys: [], available: true, collection_enabled: true });
        const id = ++nextId;
        callbacks.append({ source_epoch: EPOCH, after_id: id - 1, next_after_id: id, watermark_id: id,
          rows: [{ id, observed_at_ms: at, action: 'CALL', protocol: 'p25', channel_kind: 'trunked_site',
            radio_system_key: SYSTEM, system_name: 'County P25', configuration_id: CONFIGURATION,
            source_identity_key: fact.radio_identity_key, source_radio_id: fact.radio_id,
            source_alias_name: fact.radio_alias_name, target_identity_key: fact.talkgroup_identity_key,
            target_kind: 'talkgroup', target_id: fact.talkgroup_id, target_alias_name: fact.talkgroup_alias_name }] });
        for (const source of activitySources) source.callbacks.activityBatch?.({
          changes: tables().map(table => ({ operation: 'upsert', table_id: table.table_id, table: clone(table) })) });
      }, 7000);
    });
    return connection;
  };
  const dispose = () => {
    disposed = true;
    [...connections, ...activitySources].forEach(connection => connection.close());
  };
  signal?.addEventListener('abort', dispose, { once: true });
  return { guideMode: true, subscribeActivity, openActivityStream,
    loadEventSettings: () => null, saveEventSettings: async () => { check(); },
    systemHref: () => '', requests, dispose };
}
