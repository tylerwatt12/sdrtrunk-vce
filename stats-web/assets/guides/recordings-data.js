// Fictional API observations and inert audio for the production Recordings page.
// No example request reaches a receiver or a stored recording.
const COUNTY = 'p25:bee00:123';
const CITY = 'p25:bee00:456';
const COUNTY_CHANNEL = 'b00bc000-0000-4000-8000-000000000001';
const CITY_CHANNEL = 'b00bc000-0000-4000-8000-000000000002';
const FIREGROUND = 'b00bc000-0000-4000-8000-000000000003';
export const RECORDINGS_GUIDE_CALL_ID = 9702;
const clone = value => structuredClone(value);

export function createRecordingsGuideData({ signal } = {}) {
  let disposed = false;
  const now = Date.now();
  const check = requestSignal => {
    if (disposed || signal?.aborted || requestSignal?.aborted)
      throw new DOMException('The Recordings example was closed.', 'AbortError');
  };
  const identity = (system, kind, id) => ({ kind, radio_system_key: system,
    identity_key: `v1-${kind === 'radio' ? 'r' : 'g'}-bee00-${system === CITY ? '456' : '123'}-${id}` });
  const call = (id, system, group, groupName, radio, radioName, elapsed, text) => {
    const city = system === CITY, systemId = city ? 0x456 : 0x123;
    const start = now - elapsed;
    return { id, call_id: `guide-recordings-${id}`, start_ms: start, end_ms: start + 14_000,
      duration_ms: 14_000, size_bytes: 112_400, protocol: 'APCO25_PHASE2', call_type: 'GROUP', voice_type: 'CLEAR',
      system_key: system, system_name: city ? 'City P25' : 'County P25',
      channel_id: city ? CITY_CHANNEL : COUNTY_CHANNEL, channel_name: city ? 'City Site' : 'County Site',
      alias_list_id: city ? 9402 : 9401, alias_list_name: city ? 'City P25' : 'County P25',
      talkgroup_id: group, talkgroup_alias: groupName, talkgroup_group: 'Fire/EMS',
      source_id: radio, source_alias: radioName, source_ota_alias: radioName,
      source_home_wacn: 0xBEE00, source_home_system_id: systemId, source_home_id: radio,
      target_home_wacn: 0xBEE00, target_home_system_id: systemId, target_home_id: group,
      wacn: 0xBEE00, system_id: systemId, rfss_id: 1, site_id: 1,
      site_name: city ? 'City' : 'County', frequency_hz: city ? 858162500 : 856662500,
      audio_from: { wacn: 0xBEE00, system_id: systemId, rfss: 1, site_id: 1, site_name: city ? 'City' : 'County' },
      matched_scan_lists: [{ id: 9502, name: 'Fire/EMS' }],
      radio_system_entity_ref: { kind: 'radio_system', key: system },
      channel_entity_ref: { kind: 'channel', key: city ? CITY_CHANNEL : COUNTY_CHANNEL },
      target_entity_ref: identity(system, 'talkgroup', group), source_entity_ref: identity(system, 'radio', radio),
      transcript_excerpt: text,
      transcription: { status: 'COMPLETE', text, stored_at_ms: start + 20_000 } };
  };
  const calls = [
    call(9701, COUNTY, 1001, 'Regional Fire', 2100, 'Fire Dispatch', 600_000,
      'Engine 7, proceed to staging on the north side.'),
    call(9702, COUNTY, 1001, 'Regional Fire', 2107, 'Engine 7', 540_000,
      'Engine 7 has arrived at staging. Standing by for assignment.'),
    call(9703, CITY, 2001, 'City Fire', 3202, 'Engine 2', 480_000,
      'Engine 2 has arrived at staging with the second crew.'),
    call(9704, COUNTY, 1001, 'Regional Fire', 2100, 'Fire Dispatch', 420_000,
      'Engine 7, take the east access road. The relief crew has arrived at staging.'),
    call(9705, CITY, 2002, 'Police Department', 4212, 'City Police 12', 360_000,
      'The north intersection is open. Traffic is moving normally.'),
    { ...call(9706, COUNTY, 1002, 'Sheriff', 1214, 'Sheriff 214', 300_000,
      'Sheriff 214 clear of the south entrance.'), talkgroup_group: 'Law Enforcement' },
    { id: 9707, call_id: 'guide-recordings-9707', start_ms: now - 240_000, end_ms: now - 226_000,
      duration_ms: 14_000, protocol: 'NBFM', channel_id: FIREGROUND, channel_name: 'County Fireground',
      system_name: 'County Fireground', channel_entity_ref: { kind: 'channel', key: FIREGROUND },
      frequency_hz: 154280000, voice_type: 'CLEAR', size_bytes: 112_400,
      transcript_excerpt: 'Water supply established at the east entrance.',
      transcription: { status: 'COMPLETE', text: 'Water supply established at the east entrance.', stored_at_ms: now - 220_000 } }
  ];
  const array = raw => { try { return JSON.parse(raw || '[]'); } catch { return []; } };
  const filtered = parameters => calls.filter(row => {
    if (parameters.get('system_key') && row.system_key !== parameters.get('system_key')) return false;
    if (parameters.get('from_ms') && row.start_ms < Number(parameters.get('from_ms'))) return false;
    if (parameters.get('to_ms') && row.start_ms > Number(parameters.get('to_ms'))) return false;
    const targets = array(parameters.get('talkgroups')), channels = array(parameters.get('channel_ids'));
    if ((targets.length || channels.length) && !targets.some(target => row.system_key === target.system_key && row.talkgroup_id === Number(target.id)) &&
      !channels.includes(row.channel_id)) return false;
    const transcript = (parameters.get('transcript') || '').replaceAll('"', '').trim().toLowerCase();
    if (transcript && !row.transcription?.text.toLowerCase().includes(transcript)) return false;
    return true;
  });
  const grouped = (kind, parameters) => {
    const rows = new Map();
    for (const row of calls) {
      if (kind === 'talkgroup' && !row.talkgroup_id || kind === 'radio' && !row.source_id) continue;
      if (kind === 'channel' && row.protocol !== 'NBFM') continue;
      const id = kind === 'talkgroup' ? row.talkgroup_id : kind === 'radio' ? row.source_id : row.channel_id;
      const key = `${row.system_key || row.channel_id}:${id}`;
      if (!rows.has(key)) rows.set(key, { kind, id, label: kind === 'talkgroup' ? row.talkgroup_alias : kind === 'radio' ? row.source_alias : row.channel_name,
        system_key: row.system_key ?? null, system_name: row.system_name,
        group: row.talkgroup_group || 'Channels', alias_list_name: row.alias_list_name,
        protocol: row.protocol === 'APCO25_PHASE2' ? 'APCO25' : row.protocol,
        frequency_hz: row.frequency_hz, call_count: 0,
        selection: kind === 'channel' ? { channel_id: id } : { id, system_key: row.system_key,
          identity_key: (kind === 'radio' ? row.source_entity_ref : row.target_entity_ref).identity_key } });
      rows.get(key).call_count++;
    }
    const groups = new Map(), term = (parameters.get('q') || '').toLowerCase();
    for (const row of rows.values()) {
      if (term && !JSON.stringify(row).toLowerCase().includes(term)) continue;
      const key = `${kind}:${row.system_key || row.id}:${row.group}`;
      if (!groups.has(key)) groups.set(key, { group_key: key, kind, system_key: row.system_key,
        system_name: row.system_name, label: row.group, alias_list_name: row.alias_list_name, rows: [] });
      groups.get(key).rows.push(row);
    }
    return { groups: [...groups.values()].map(group => ({ ...group, match_count: group.rows.length, has_more: false })) };
  };
  return {
    calls: clone(calls),
    async requestJson(path, options = {}) {
      check(options.signal);
      if (options.method && options.method !== 'GET') throw new Error('Example recordings are read only.');
      const url = new URL(path, 'https://recordings-guide.invalid');
      const parameters = url.searchParams;
      let result;
      if (url.pathname === '/api/v1/recordings/status') result = { available: true, has_calls: true, mode: 'MANAGED' };
      else if (url.pathname === '/api/v1/recordings/calls') {
        const rows = filtered(parameters).sort((a, b) => (parameters.get('sort') === 'asc' ? 1 : -1) * (a.start_ms - b.start_ms));
        const offset = Number((parameters.get('cursor') || 'guide:0').split(':')[1]), limit = Number(parameters.get('limit') || 25);
        result = { calls: rows.slice(offset, offset + limit), total: rows.length, next_cursor: offset + limit < rows.length ? `guide:${offset + limit}` : null,
          from_ms: parameters.get('from_ms'), to_ms: parameters.get('to_ms') };
      } else if (/^\/api\/v1\/recordings\/calls\/\d+$/.test(url.pathname)) {
        const row = calls.find(call => String(call.id) === url.pathname.split('/').at(-1));
        if (!row) throw new Error('Unknown example recording.');
        result = { call: row, transcription: row.transcription };
      } else if (['/api/v1/recordings/identities', '/api/v1/recordings/entities'].includes(url.pathname))
        result = grouped(parameters.get('kind'), parameters);
      else throw new Error('Unknown Recordings example request.');
      check(options.signal);
      return clone(result);
    },
    createAudioElement() {
      const audio = new EventTarget();
      Object.assign(audio, { paused: true, currentTime: 0, duration: NaN, volume: 1, src: '', preload: '' });
      audio.play = async () => { check(); audio.paused = false; audio.dispatchEvent(new Event('play')); };
      audio.pause = () => { audio.paused = true; audio.dispatchEvent(new Event('pause')); };
      audio.load = () => {};
      audio.removeAttribute = name => { if (name === 'src') audio.src = ''; };
      return audio;
    },
    dispose() { disposed = true; }
  };
}
