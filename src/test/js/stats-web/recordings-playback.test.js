'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

class AudioFixture {
  constructor() {
    this.events = new Map();
    this.paused = true;
    this.currentTime = 0;
    this.duration = NaN;
    this.volume = 1;
    this.src = '';
  }
  addEventListener(name, callback) {
    const callbacks = this.events.get(name) || [];
    callbacks.push(callback);
    this.events.set(name, callbacks);
  }
  emit(name) { (this.events.get(name) || []).forEach((callback) => callback()); }
  async play() { this.paused = false; this.emit('play'); }
  pause() { this.paused = true; this.emit('pause'); }
  removeAttribute(name) { if (name === 'src') this.src = ''; }
  load() { this.currentTime = 0; this.duration = NaN; }
}

class ElementFixture {
  constructor(tag, className, text) {
    this.tagName = tag;
    this.className = className || '';
    this.classList = { add: (...names) => { this.className = [this.className, ...names].filter(Boolean).join(' '); } };
    this.textContent = text || '';
    this.children = [];
    this.childNodes = this.children;
    this.attributes = new Map();
    this.events = new Map();
    this.isConnected = true;
  }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; this.childNodes = this.children; }
  setAttribute(name, value) { this.attributes.set(name, value); }
  addEventListener(name, callback) { this.events.set(name, callback); }
  focus() { this.focused = true; }
  get firstElementChild() { return this.children[0]; }
}

const node = (...args) => new ElementFixture(...args);
const text = (element) => typeof element === 'object' ?
  [element.textContent, ...(element.children || []).map(text)].join(' ') : String(element);
const descendants = (element) => [element, ...(element.children || []).filter((child) =>
  typeof child === 'object').flatMap(descendants)];
const settle = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
const deferred = () => { let resolve; const promise = new Promise((done) => { resolve = done; }); return { promise, resolve }; };
const call = (id, start = id * 1000) => ({ id, start_ms: start, duration_ms: 30_000,
  talkgroup_id: 1201, talkgroup_alias: 'North Dispatch', source_id: 1863924, protocol: 'APCO25_PHASE2' });

async function main() {
  const modulePath = path.resolve(process.argv[2] || path.resolve(__dirname,
    '../../../../stats-web/assets/features/recordings.js'));
  const source = fs.readFileSync(modulePath, 'utf8');
  const labelsUrl = pathToFileURL(path.resolve(path.dirname(modulePath), '../core/system-labels.js')).href;
  const radioLabelsUrl = pathToFileURL(path.resolve(path.dirname(modulePath), '../core/radio-labels.js')).href;
  const sourceNamesUrl = pathToFileURL(path.resolve(path.dirname(modulePath), '../core/source-names.js')).href;
  const executable = source.replace(/^import .*dual-range.*;\r?\n/m, 'const createDualRange = () => {};\n')
    .replace(/from '\.\.\/core\/system-labels\.js\?v=\d+'/, `from '${labelsUrl}'`)
    .replace(/from '\.\.\/core\/radio-labels\.js\?v=\d+'/, `from '${radioLabelsUrl}'`)
    .replace(/from '\.\.\/core\/source-names\.js\?v=\d+'/, `from '${sourceNamesUrl}'`);
  const { createRecordingsFeature, recordingSuggestionFilters } = await import(
    `data:text/javascript;base64,${Buffer.from(executable).toString('base64')}`);
  const previousFilters = Object.freeze({ q: 'Previous name', system_key: 'p25:00002:002',
    radio_id: '401', talkgroup_id: '1201', radio_min: '400', radio_max: '500',
    talkgroup_min: '1200', talkgroup_max: '1300', alias_list_id: '7',
    channel_id: 'channel-7', wacn: '1', sysid: '1', rfss: '1', site_id: '3',
    from_ms: '1000', to_ms: '9000', min_duration_ms: '500', transcript: 'arrival',
    protocol: 'APCO25', voice_type: 'CLEAR' });
  for (const id of [null, 0, '401', '777']) {
    const suggestion = Object.freeze({ kind: 'radio', id, label: 'Foreign OTA %_401',
      name_query: 'Foreign OTA %_401', system_key: 'p25:00001:001' });
    const selected = recordingSuggestionFilters(previousFilters, suggestion);
    assert.deepEqual(selected, { ...previousFilters, q: 'Foreign OTA %_401',
      system_key: 'p25:00001:001', radio_id: '', talkgroup_id: '',
      radio_min: '', radio_max: '', talkgroup_min: '', talkgroup_max: '', alias_list_id: '' },
    'OTA selection must search its exact name in the receiving system without stale raw identity constraints');
    assert.notEqual(selected, previousFilters, 'Selecting a suggestion must return independent filter state');
    assert.equal(previousFilters.radio_id, '401', 'Selecting a foreign subscriber must preserve the prior input object');
    assert.equal(suggestion.id, id, 'Missing, zero, and canonical IDs must not be rewritten into Working IDs');
  }
  for (const suggestion of [
    { kind: 'radio', id: '401', label: '401', system_key: 'p25:00001:001' },
    { kind: 'radio', id: '401', label: 'Local configured Alias', alias_list_id: 7 },
    { kind: 'talkgroup', id: '1201', label: 'Fire Dispatch' },
    { kind: 'radio_range', id: '400-500', min_id: 400, max_id: 500, label: 'Field radios' },
    { kind: 'talkgroup_range', id: '1200-1300', min_id: 1200, max_id: 1300, label: 'Fire groups' }
  ]) {
    const selected = recordingSuggestionFilters(previousFilters, Object.freeze(suggestion));
    assert.deepEqual(selected, previousFilters,
      'Numeric and configured suggestions must retain the existing numeric selection path');
    assert.notEqual(selected, previousFilters, 'Unchanged suggestion filters still return a shallow copy');
  }
  const originalAudio = global.Audio;
  const audios = [];
  global.Audio = class extends AudioFixture { constructor() { super(); audios.push(this); } };
  const make = (requestJson, overrides = {}) => createRecordingsFeature({ node, requestJson,
    stopLiveAudio: async () => {}, isPrimaryAdmin: () => false, canViewRadio: () => true,
    entityRefHref: (ref) => ref?.key ? `/entity/${ref.key}` : null,
    anchor: (label, target, className) => { const link = node('a', className, label); link.setAttribute('href', target); return link; },
    href: (view, query) => `?view=${view}&${new URLSearchParams(query)}`, ...overrides });
  try {
    let stoppedLive = 0;
    const metadata = deferred();
    const feature = make(() => metadata.promise, { stopLiveAudio: async () => { stoppedLive++; } });
    const playback = feature.playback;
    const notifications = [];
    const unsubscribe = playback.subscribeState((state) => notifications.push(state));
    assert.equal(playback.viewState().stopped, true);
    assert.equal(audios.length, 1, 'One feature instance must own one persistent Audio instance');
    playback.play(call(1));
    await settle();
    assert.equal(stoppedLive, 1, 'Recordings must stop live audio before starting');
    assert.equal(playback.viewState().playing, true);
    assert.equal(playback.viewState().detailsLoading, true);
    metadata.resolve({ call: { ...call(1), size_bytes: 88_000 }, transcription: { status: 'complete', text: 'Unit clear.' } });
    await settle();
    assert.equal(playback.viewState().current.size_bytes, 88_000);
    assert.equal(playback.viewState().current.transcription.text, 'Unit clear.');
    playback.seek(45);
    assert.equal(playback.viewState().currentTime, 30, 'Seek must clamp to the recording duration');
    playback.skipSeconds(-10);
    assert.equal(playback.viewState().currentTime, 20);
    playback.setVolume(0.35);
    assert.equal(playback.viewState().volume, 0.35);
    playback.stop();
    assert.equal(playback.viewState().current.id, 1, 'Stop must retain the displayed recording metadata');
    assert.equal(playback.viewState().stopped, true);
    await playback.toggle();
    assert.equal(playback.viewState().playing, true, 'Play after Stop must restart the retained recording');
    await playback.toggle();
    assert.equal(playback.viewState().paused, true);
    const notificationCount = notifications.length;
    unsubscribe();
    playback.setVolume(0.4);
    assert.equal(notifications.length, notificationCount, 'Detached presentations must receive no updates');
    playback.reset();
    assert.equal(playback.viewState().current, null, 'Permission loss must be able to clear retained metadata');

    const firstDetail = deferred();
    const secondDetail = deferred();
    const stale = make((url) => url.endsWith('/1') ? firstDetail.promise : secondDetail.promise).playback;
    stale.play(call(1));
    await settle();
    stale.play(call(2));
    await settle();
    secondDetail.resolve({ call: { ...call(2), channel_name: 'Current channel' }, transcription: { status: 'pending' } });
    await settle();
    firstDetail.resolve({ call: { ...call(1), channel_name: 'Stale channel' }, transcription: { status: 'complete', text: 'Stale.' } });
    await settle();
    assert.equal(stale.viewState().current.id, 2);
    assert.equal(stale.viewState().current.channel_name, 'Current channel');
    assert.equal(stale.viewState().current.transcription.status, 'pending', 'Late details cannot replace a newer call');

    const queue = make(async (url) => ({ call: call(Number(url.split('/').at(-1))) })).playback;
    queue.play(call(1));
    await settle();
    queue.play(call(2), 'queue');
    queue.play(call(3), 'queue');
    await queue.next();
    await settle();
    assert.equal(queue.viewState().current.id, 2);
    assert.equal(queue.viewState().canPrevious, true);
    queue.previous();
    await settle();
    assert.equal(queue.viewState().current.id, 1);
    assert.deepEqual(queue.viewState().queue.map((row) => row.id), [2, 3], 'Previous must retain forward queue order');
    await queue.next();
    assert.equal(queue.viewState().current.id, 2);
    queue.clearQueue();
    assert.equal(queue.viewState().current.id, 2, 'Clear queue must preserve current playback');
    assert.equal(queue.viewState().playing, true);
    assert.equal(queue.viewState().canNext, false);

    const reorderStop = deferred();
    const reordering = make(async (url) => ({ call: call(Number(url.split('/').at(-1))) }),
      { stopLiveAudio: () => reorderStop.promise }).playback;
    reordering.play(call(1), 'queue');
    reordering.play(call(2), 'queue');
    const staleAdvance = reordering.next();
    await settle();
    reordering.playQueued(reordering.viewState().queue[1]._queueEntryKey);
    await settle();
    reorderStop.resolve();
    await staleAdvance;
    await settle();
    assert.equal(reordering.viewState().current.id, 2,
      'Selecting a queue row while live audio is stopping must invalidate the previously captured row');
    assert.deepEqual(reordering.viewState().queue.map((row) => row.id), [1],
      'The replacement start must consume only the requested queue row');

    const removalStop = deferred();
    const removing = make(async (url) => ({ call: call(Number(url.split('/').at(-1))) }),
      { stopLiveAudio: () => removalStop.promise }).playback;
    removing.play(call(1), 'queue');
    removing.play(call(2), 'queue');
    const removedAdvance = removing.next();
    await settle();
    removing.removeQueued(removing.viewState().queue[0]._queueEntryKey);
    await settle();
    removalStop.resolve();
    await removedAdvance;
    await settle();
    assert.equal(removing.viewState().current.id, 2,
      'Removing a row during a pending start must never play the removed recording');
    assert.deepEqual(removing.viewState().queue, []);

    const repeated = make(async (url) => ({ call: call(Number(url.split('/').at(-1))) })).playback;
    const repeatedCall = call(1);
    repeated.play(repeatedCall, 'queue');
    repeated.play(repeatedCall, 'queue');
    repeated.play(call(2), 'queue');
    const repeatedRows = repeated.viewState().queue;
    assert.notEqual(repeatedRows[0]._queueEntryKey, repeatedRows[1]._queueEntryKey,
      'Repeated recordings must have independently actionable queue entries');
    assert.match(repeated.queueHref(), /recording_queue=1%2C1%2C2/,
      'Shared links must preserve repeated public call IDs without internal queue keys');
    assert.doesNotMatch(repeated.queueHref(), /recording-entry/);
    repeated.playQueued(repeatedRows[1]._queueEntryKey);
    await settle();
    assert.equal(repeated.viewState().current._queueEntryKey, repeatedRows[1]._queueEntryKey,
      'Play must target the requested repeated occurrence');
    assert.deepEqual(repeated.viewState().queue.map((row) => row._queueEntryKey),
      [repeatedRows[0]._queueEntryKey, repeatedRows[2]._queueEntryKey]);
    repeated.play(repeatedCall, 'queue');
    const secondRepeatedKey = repeated.viewState().queue.at(-1)._queueEntryKey;
    repeated.removeQueued(secondRepeatedKey);
    assert.equal(repeated.viewState().queue[0]._queueEntryKey, repeatedRows[0]._queueEntryKey,
      'Removing the later repeated entry must preserve the earlier occurrence');
    repeated.play(repeatedCall, 'queue');
    repeated.removeQueued(1);
    assert.equal(repeated.viewState().queue.filter((row) => row.id === 1).length, 1,
      'ID-only compatibility must remove exactly one occurrence');

    const pendingAudioPlay = deferred();
    const delayedPlayback = make(async (url) => ({ call: call(Number(url.split('/').at(-1))) })).playback;
    delayedPlayback.viewState();
    const delayedAudio = audios.at(-1);
    let audioPlayCount = 0;
    delayedAudio.play = async function() {
      this.paused = false;
      this.emit('play');
      if (++audioPlayCount === 1) await pendingAudioPlay.promise;
    };
    delayedPlayback.play(call(1));
    await settle();
    assert.equal(delayedPlayback.viewState().loading, true);
    delayedPlayback.play(call(2), 'queue');
    delayedPlayback.playQueued(delayedPlayback.viewState().queue[0]._queueEntryKey);
    await settle();
    assert.equal(delayedPlayback.viewState().current.id, 2);
    pendingAudioPlay.resolve();
    await settle();
    assert.equal(delayedPlayback.viewState().current.id, 2,
      'Late audio play completion cannot restore an interrupted queue entry');
    assert.equal(delayedPlayback.viewState().loading, false);

    const pendingContinuation = deferred();
    const continuationRequests = [];
    const continuing = make(async (url) => {
      if (!url.includes('?')) return { call: call(Number(url.split('/').at(-1)), 1000) };
      continuationRequests.push(url);
      return pendingContinuation.promise;
    }).playback;
    continuing.play(call(1, 1000), 'continue');
    await settle();
    const pendingAdvance = continuing.next();
    await settle();
    assert.equal(continuationRequests.length, 1, 'Next must await an in-progress continuation request');
    pendingContinuation.resolve({ calls: [call(0, 999), call(1, 1000), call(2, 1000), call(3, 1001)], next_cursor: null });
    await pendingAdvance;
    await settle();
    assert.equal(continuing.viewState().current.id, 2, 'Continuation must exclude the anchor and older calls');
    assert.deepEqual(continuing.viewState().queue.map((row) => row.id), [3]);
    assert.match(continuationRequests[0], /sort=asc/);

    const canceledContinuation = deferred();
    const cancellation = make(async (url) => url.includes('?') ? canceledContinuation.promise : { call: call(1) }).playback;
    cancellation.play(call(1), 'continue');
    await settle();
    cancellation.clearQueue();
    canceledContinuation.resolve({ calls: [call(2)], next_cursor: null });
    await settle();
    assert.deepEqual(cancellation.viewState().queue, [], 'Cleared continuation results must not repopulate the queue');
    assert.equal(cancellation.viewState().current.id, 1);

    const delayedLiveStop = deferred();
    const canceledStart = make(async () => ({ call: call(1) }), { stopLiveAudio: () => delayedLiveStop.promise }).playback;
    canceledStart.play(call(1));
    await settle();
    canceledStart.clearQueue();
    delayedLiveStop.resolve();
    await settle();
    assert.equal(canceledStart.viewState().current, null, 'Clearing while transport is loading cannot resurrect a cleared call');
    assert.equal(canceledStart.viewState().playing, false);

    const sharedRequests = [];
    let sourceMode = 'talker_alias';
    const sharing = make(async (url) => {
      sharedRequests.push(url);
      if (url.endsWith('/2')) throw new Error('Recording unavailable');
      return { call: call(Number(url.split('/').at(-1))) };
    }, { getSourceNameDisplay: () => sourceMode }).playback;
    assert.equal(await sharing.loadSharedQueue(new URLSearchParams('recording_queue=3,2,1')), true);
    assert.deepEqual(sharing.viewState().queue.map((row) => row.id), [3, 1], 'Shared queue must preserve URL order');
    assert.equal(sharing.viewState().playing, false, 'Opening a shared URL must never autoplay');
    assert.match(sharing.viewState().status, /1 calls are unavailable/);
    assert.match(sharing.queueHref(), /recording_queue=3%2C1/);
    const beforeInvalid = sharedRequests.length;
    assert.equal(await sharing.loadSharedQueue({ recording_queue: '1,x,2' }), false);
    assert.equal(await sharing.loadSharedQueue({ recording_queue: Array.from({ length: 101 }, (_, i) => i + 1).join(',') }), false);
    assert.equal(sharedRequests.length, beforeInvalid, 'Invalid or unbounded queue links must perform no fetches');
    const loadingShared = deferred();
    const cancelShared = make(() => loadingShared.promise).playback;
    const pendingShared = cancelShared.loadSharedQueue({ recording_queue: '1' });
    cancelShared.reset();
    loadingShared.resolve({ call: call(1) });
    assert.equal(await pendingShared, false);
    assert.deepEqual(cancelShared.viewState().queue, [], 'Permission reset must invalidate pending shared queue results');

    const richCall = { ...call(8), end_ms: 31_000, size_bytes: 88_000, timeslot: 1,
      system_name: 'GCRCN', system_key: 'p25:bee00:49f',
      source_home_system_name: 'Home Network', target_home_system_name: 'Home Network',
      source_home_wacn: 0xBEE00, source_home_system_id: 0x4A2, source_home_id: 1863924,
      target_home_wacn: 0xBEE00, target_home_system_id: 0x4A2, target_home_id: 1201,
      source_entity_ref: { key: 'source' }, target_entity_ref: { key: 'target' },
      channel_name: 'County North', channel_id: 'channel-7', channel_entity_ref: { key: 'channel' },
      audio_from: { system_name: 'Receiving Network', wacn: 0xBEE00, system_id: 0x4A2, rfss_id: 1, site_id: 3 },
      also_received_on: [{ wacn: 0xBEE00, system_id: 0x4A2, rfss_id: 1, site_id: 4 }],
      patch_members: [{ kind: 'talkgroup', id: 1202, home_system_name: 'Member Home Network', home_wacn: 0xBEE00,
        home_system_id: 0x4A2, home_identity_id: 1202, entity_ref: { key: 'member' } }] };
    const details = node('div');
    sharing.renderDetails(details, richCall);
    const rendered = text(details);
    for (const field of ['Recording ID', 'Ended', 'File size', 'Timeslot', 'Source home WACN', 'Target home identity',
      'Received site identities', 'Winning site WACN', 'Patch member identities', 'Member 1 home identity', 'BEE00',
      'GCRCN', 'Source home system name', 'Target home system name', 'Home Network',
      'Winning site system name', 'Receiving Network', 'Member 1 home system name', 'Member Home Network']) {
      assert.ok(rendered.includes(field), `Recording detail must include ${field}`);
    }
    assert.ok(descendants(details).some((child) => child.attributes?.get('href') === '/entity/member'),
      'Patch member identities must preserve available drilldown links');

    const primaryFact = (recording, name) => {
      sharing.renderDetails(details, recording);
      const facts = details.firstElementChild.children;
      const index = facts.findIndex((child) => child.tagName === 'dt' && child.textContent === name);
      return text(facts[index + 1]).trim();
    };
    const localRadio = { ...call(9), system_key: 'p25:bee00:348',
      source_home_wacn: 0xBEE00, source_home_system_id: 0x348, source_home_id: 1863924 };
    assert.equal(primaryFact(localRadio, 'Source'), 'Radio 1863924',
      'Confirmed local recording radios must not repeat their WACN and SysID');
    const namedSource = { ...localRadio, source_alias: 'Engine 4', source_ota_alias: 'ENG 4' };
    assert.equal(primaryFact(namedSource, 'Source'), 'ENG 4 · Radio 1863924',
      'Recording source summaries prefer the latest OTA alias and retain the radio ID');
    assert.equal(primaryFact(namedSource, 'Latest OTA name'), 'ENG 4',
      'The explicit latest OTA fact remains available regardless of display preference');
    sourceMode = 'source_alias';
    assert.equal(primaryFact(namedSource, 'Source'), 'Engine 4 · Radio 1863924');
    sourceMode = 'both';
    assert.equal(primaryFact(namedSource, 'Source'), 'Engine 4 · ENG 4 · Radio 1863924');
    assert.equal(primaryFact({ ...namedSource, source_ota_alias: ' engine 4 ' }, 'Source'),
      'Engine 4 · Radio 1863924', 'Recording names deduplicate across case and surrounding spaces');
    sourceMode = 'talker_alias';
    assert.equal(primaryFact({ ...namedSource, source_ota_alias: '' }, 'Source'), 'Engine 4 · Radio 1863924');
    assert.equal(primaryFact({ ...localRadio, source_home_id: 12345 }, 'Source'),
      'Radio BEE00.348.12345 (Working ID 1863924)', 'A different observed Working ID retains home context');
    const foreignRadio = { ...localRadio, source_home_system_id: 0x4A2 };
    assert.equal(primaryFact({ ...foreignRadio, source_home_system_name: 'Home Network' }, 'Source'),
      'Radio Home Network · 1863924', 'Foreign recording radios use their friendly home system name');
    assert.equal(primaryFact(foreignRadio, 'Source'), 'Radio BEE00.4A2.1863924',
      'An unnamed foreign recording radio must retain its full home address');
    assert.equal(primaryFact({ ...foreignRadio, source_home_id: null }, 'Source'), 'Radio 1863924',
      'An incomplete saved home address must not imply a permanent radio ID');
    const { rememberSystemNames } = await import(labelsUrl);
    rememberSystemNames({ radio_system_key: 'p25:bee00:4a2', system_name: 'Home Network' });
    assert.equal(primaryFact(foreignRadio, 'Source'), 'Radio Home Network · 1863924',
      'Recording labels can reuse home names already returned by an authorized read');
    const directRadio = { ...foreignRadio, call_type: 'DIRECT', talkgroup_id: null, talkgroup_alias: null,
      destination_radio_id: 71, target_home_wacn: 0xBEE00, target_home_system_id: 0x4A2, target_home_id: 12345 };
    assert.equal(primaryFact(directRadio, 'Target'), 'Direct to Radio Home Network · 12345 (Working ID 71)',
      'Direct call destinations follow the same permanent and Working ID display rule');
    assert.equal(primaryFact({ ...directRadio, destination_radio_ota_alias: 'Foreign destination OTA' }, 'Target'),
      'Direct to Foreign destination OTA', 'Direct destinations show their OTA name when no configured Alias exists');
    assert.equal(primaryFact({ ...directRadio, destination_radio_alias: 'Configured destination',
      destination_radio_ota_alias: 'Foreign destination OTA' }, 'Target'), 'Direct to Configured destination',
    'Direct destination configured Aliases retain precedence over OTA names');
    const memberFacts = (members) => {
      sharing.renderDetails(details, { ...richCall, patch_members: members });
      return descendants(details).filter((element) => element.tagName === 'dl').flatMap((list) =>
        list.children.filter((element) => element.tagName === 'dt').map((term) => ({
          name: term.textContent, detail: text(list.children[list.children.indexOf(term) + 1]).trim()
        })));
    };
    const otaMember = { kind: 'radio', id: 401, ota_alias: 'Foreign member OTA',
      home_wacn: 2, home_system_id: 2, home_identity_id: 777, entity_ref: { key: 'member' } };
    const otaMemberFacts = memberFacts([otaMember]);
    assert.equal(otaMemberFacts.find((fact) => fact.name === 'Patch members').detail, 'Foreign member OTA · 401',
      'Patch summaries show the OTA name while preserving the recorded local ID');
    assert.equal(otaMemberFacts.find((fact) => fact.name === 'Member 1 alias').detail, 'Foreign member OTA',
      'Patch member details expose the OTA name in the existing alias slot');
    assert.equal(memberFacts([{ ...otaMember, alias: 'Configured member' }])
      .find((fact) => fact.name === 'Member 1 alias').detail, 'Configured member',
    'Patch configured Aliases retain precedence over OTA names');
    sharing.renderDetails(details, directRadio);
    assert.ok(text(details).includes('Source home WACN') && text(details).includes('Target home identity'),
      'Complete recording identifiers remain available in the detail disclosures');
    const transcript = node('div');
    sharing.renderTranscript(transcript, { ...richCall, transcription: { status: 'failed' } });
    assert.ok(!text(transcript).includes('Retry transcription'), 'Non-administrators cannot retry transcription');
    const admin = make(async () => ({ status: 'pending' }), { isPrimaryAdmin: () => true }).playback;
    admin.renderTranscript(transcript, { ...richCall, transcription: { status: 'failed' } });
    assert.ok(text(transcript).includes('Retry transcription'));
    assert.doesNotMatch(source, /node\('section', 'recordings-player'\)/, 'Recordings must not mount a competing dock');
  } finally {
    global.Audio = originalAudio;
  }
}

main().catch((error) => {
  console.error(String(error.stack || error).replace(/data:text\/javascript;base64,[A-Za-z0-9+/=]+/g, 'recordings.js'));
  process.exitCode = 1;
});
