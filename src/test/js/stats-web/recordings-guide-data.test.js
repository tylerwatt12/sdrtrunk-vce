'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function checkRecordingsGuideData(assetsRoot) {
  const assets = path.resolve(assetsRoot || process.argv[2] || 'stats-web/assets');
  const moduleAt = name => import(pathToFileURL(path.join(assets, name)).href);
  const [{ createRecordingsGuideData, RECORDINGS_GUIDE_CALL_ID }, recordings, { href }] = await Promise.all([
    moduleAt('guides/recordings-data.js'), moduleAt('features/recordings.js'), moduleAt('core/entity-ref.js')
  ]);
  const originals = { now: Date.now, fetch: global.fetch, Audio: global.Audio };
  const owned = [];
  const now = Date.UTC(2026, 9, 24, 15, 0);
  let network = 0, nativeAudio = 0;
  Date.now = () => now;
  global.fetch = () => { network++; throw new Error('A Recordings example reached the network.'); };
  global.Audio = function () { nativeAudio++; throw new Error('A Recordings example created real audio.'); };
  const create = options => { const data = createRecordingsGuideData(options); owned.push(data); return data; };
  const query = values => `/api/v1/recordings/calls?${new URLSearchParams(values)}`;
  try {
    const controller = new AbortController(), data = create({ signal: controller.signal });
    assert.deepEqual(await data.requestJson('/api/v1/recordings/status'),
      { available: true, has_calls: true, mode: 'MANAGED' });
    const result = await data.requestJson(query({ sort: 'desc' }));
    assert.equal(result.calls.length, 7);
    assert.ok(result.calls.every((call, index, calls) => index === 0 || call.start_ms < calls[index - 1].start_ms));
    const primary = result.calls.find(call => call.id === RECORDINGS_GUIDE_CALL_ID);
    assert.equal(primary.source_alias, 'Engine 7');
    assert.equal(primary.talkgroup_alias, 'Regional Fire');
    assert.equal(primary.transcription.status, 'COMPLETE');
    assert.equal(primary.end_ms - primary.start_ms, primary.duration_ms);
    for (const call of result.calls) {
      assert.ok(href(call.channel_entity_ref), 'Sample channels use production navigation identities.');
      if (!call.talkgroup_id) continue;
      assert.ok(href(call.target_entity_ref));
      assert.ok(href(call.source_entity_ref));
      assert.deepEqual(recordings.recordingIdentitySelections([{ id: call.talkgroup_id,
        system_key: call.system_key, identity_key: call.target_entity_ref.identity_key }], 'talkgroup'),
      [{ id: call.talkgroup_id, system_key: call.system_key, identity_key: call.target_entity_ref.identity_key }]);
    }
    primary.source_alias = 'Changed in one response';
    const detail = await data.requestJson(`/api/v1/recordings/calls/${RECORDINGS_GUIDE_CALL_ID}`);
    assert.equal(detail.call.source_alias, 'Engine 7');
    assert.deepEqual(detail.transcription, detail.call.transcription);

    const groups = (await data.requestJson('/api/v1/recordings/identities?kind=talkgroup&grouped=true')).groups;
    assert.deepEqual([...new Set(groups.map(group => group.system_name))], ['County P25', 'City P25']);
    const selections = groups.flatMap(group => group.rows).filter(row => ['Regional Fire', 'City Fire'].includes(row.label));
    assert.equal(selections.length, 2);
    assert.ok(selections.every(row => recordings.recordingIdentitySelections([row.selection], 'talkgroup').length === 1));
    const channels = (await data.requestJson('/api/v1/recordings/entities?kind=channel&grouped=true')).groups.flatMap(group => group.rows);
    assert.equal(channels.length, 1);
    assert.equal(channels[0].label, 'County Fireground');
    assert.equal(recordings.recordingChannelSelections(JSON.stringify([channels[0].selection.channel_id])).length, 1);
    const selected = { talkgroups: JSON.stringify(selections.map(row => row.selection)), targets_any: 'true',
      transcript: '"arrived at staging"', sort: 'asc', from_ms: String(now - 3_600_000), to_ms: String(now) };
    const matches = await data.requestJson(query(selected));
    assert.deepEqual(matches.calls.map(call => call.id), [9702, 9703, 9704]);
    const continuation = await data.requestJson(query({ ...selected, from_ms: String(detail.call.start_ms) }));
    assert.deepEqual(continuation.calls.filter(call => call.start_ms > detail.call.start_ms).map(call => call.id), [9703, 9704],
      'The production continuation can replay newer matching calls from both systems in time order.');
    const first = await data.requestJson(query({ ...selected, limit: '2' }));
    assert.deepEqual(first.calls.map(call => call.id), [9702, 9703]);
    const second = await data.requestJson(query({ ...selected, limit: '2', cursor: first.next_cursor }));
    assert.deepEqual(second.calls.map(call => call.id), [9704]);
    assert.equal(second.next_cursor, null);
    assert.deepEqual((await data.requestJson(query({ ...selected, system_key: 'p25:bee00:456' }))).calls.map(call => call.id), [9703]);
    assert.equal((await data.requestJson(query({ ...selected, transcript: 'unheard words' }))).calls.length, 0);

    for (const method of ['POST', 'PUT', 'DELETE'])
      await assert.rejects(data.requestJson('/api/v1/recordings/calls', { method }), /read only/);
    await assert.rejects(data.requestJson('/api/v1/admin/recordings/settings'), /Unknown Recordings example request/);
    const requestAbort = new AbortController(); requestAbort.abort();
    await assert.rejects(data.requestJson('/api/v1/recordings/calls', { signal: requestAbort.signal }), error => error.name === 'AbortError');
    const audio = data.createAudioElement();
    let plays = 0, pauses = 0;
    audio.addEventListener('play', () => { plays++; });
    audio.addEventListener('pause', () => { pauses++; });
    audio.src = '/api/v1/recordings/calls/9702/audio';
    await audio.play();
    assert.equal(audio.paused, false);
    audio.pause(); assert.equal(audio.paused, true);
    audio.removeAttribute('src'); audio.load();
    assert.equal(audio.src, '');
    assert.equal(plays, 1); assert.equal(pauses, 1);
    controller.abort();
    await assert.rejects(data.requestJson('/api/v1/recordings/status'), error => error.name === 'AbortError');
    await assert.rejects(audio.play(), error => error.name === 'AbortError');
    const disposed = create(); disposed.dispose();
    await assert.rejects(disposed.requestJson('/api/v1/recordings/status'), error => error.name === 'AbortError');
    assert.equal(network, 0); assert.equal(nativeAudio, 0);
  } finally {
    owned.forEach(data => data.dispose());
    Date.now = originals.now;
    for (const key of ['fetch', 'Audio']) {
      if (originals[key] === undefined) delete global[key];
      else global[key] = originals[key];
    }
  }
}

module.exports = { checkRecordingsGuideData };
if (require.main === module) checkRecordingsGuideData().catch(error => { console.error(error); process.exitCode = 1; });
