'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const vm = require('node:vm');

function checkAdministrationGuideResources(assets) {
  const application = fs.readFileSync(path.join(assets, 'app.js'), 'utf8');
  const start = application.indexOf('function createAdministrationGuideResources(signal) {');
  const end = application.indexOf('// Administration renderers capture', start);
  assert.ok(start >= 0 && end > start, 'Administration examples must have an explicit resource owner.');
  let nextTimer = 0;
  const timers = new Map(), cleared = [], document = { hidden: false };
  const schedule = (callback, repeating) => {
    const id = ++nextTimer;
    timers.set(id, { callback, repeating });
    return id;
  };
  const window = {
    setInterval: callback => schedule(callback, true),
    setTimeout: callback => schedule(callback, false),
    clearInterval: id => { cleared.push(id); timers.delete(id); },
    clearTimeout: id => { cleared.push(id); timers.delete(id); }
  };
  const fire = id => {
    const timer = timers.get(id);
    assert.ok(timer, `Timer ${id} is still owned and scheduled.`);
    if (!timer.repeating) timers.delete(id);
    timer.callback();
  };
  const context = vm.createContext({ window, document });
  vm.runInContext(`${application.slice(start, end)}\nglobalThis.createResources = createAdministrationGuideResources;`, context);
  const signal = new AbortController();
  const scope = context.createResources(signal.signal);
  const { interval, timeout, registerConnection } = scope;
  let realPolls = 0, nextRealPoll, expired = false, sampleTicks = 0, sampleTimeouts = 0;
  const originalPoll = () => { realPolls++; nextRealPoll = window.setTimeout(originalPoll, 750); };
  const initialPoll = window.setTimeout(originalPoll, 500);
  const sampleInterval = interval(() => sampleTicks++, 1000);
  const completedSampleTimeout = timeout(() => sampleTimeouts++, 50);
  fire(completedSampleTimeout);
  assert.equal(sampleTimeouts, 1);
  document.hidden = true;
  fire(sampleInterval);
  assert.equal(sampleTicks, 0, 'Guide polling retains the normal hidden-document behavior.');
  document.hidden = false;
  fire(sampleInterval);
  assert.equal(sampleTicks, 1);
  // These real resources are created after the guide mounts. Snapshot-difference
  // cleanup would wrongly remove both on dismissal.
  fire(initialPoll);
  const realExpiry = window.setTimeout(() => { expired = true; }, 8000);
  const pendingSampleTimeout = timeout(() => sampleTimeouts++, 8000);
  let brokenClosed = 0, healthyClosed = 0, lateClosed = 0;
  registerConnection({ close() { brokenClosed++; throw new Error('Example close failure'); } });
  registerConnection({ close() { healthyClosed++; } });
  const failures = scope.close();
  assert.equal(failures.length, 1, 'A failing resource must not prevent the rest of the example from closing.');
  assert.equal(brokenClosed, 1);
  assert.equal(healthyClosed, 1);
  assert.equal(timers.has(sampleInterval), false);
  assert.equal(timers.has(pendingSampleTimeout), false);
  assert.equal(cleared.includes(completedSampleTimeout), false, 'Finished example timers leave the owner immediately.');
  assert.equal(cleared.includes(nextRealPoll), false);
  assert.equal(cleared.includes(realExpiry), false);
  fire(nextRealPoll);
  fire(realExpiry);
  assert.equal(realPolls, 2, 'Original recursive support polling continues after guide cleanup.');
  assert.equal(expired, true, 'A real monitor highlight expiry created during the guide still runs.');
  assert.equal(scope.close().length, 0, 'Closing the same example again is harmless.');
  registerConnection({ close() { lateClosed++; } });
  assert.equal(lateClosed, 1, 'A late connection is closed without joining the real page resources.');
  assert.equal(interval(() => {}, 100), null);
  assert.equal(timeout(() => {}, 100), null);
  const aborted = new AbortController(), abortedScope = context.createResources(aborted.signal);
  const stoppedTimer = abortedScope.timeout(() => { throw new Error('An aborted example callback ran.'); }, 100);
  aborted.abort();
  fire(stoppedTimer);
  assert.equal(abortedScope.interval(() => {}, 100), null);
  assert.equal(abortedScope.timeout(() => {}, 100), null);
  assert.equal(abortedScope.close().length, 0);
}

async function checkAdministrationGuideData(assetsRoot) {
  const assets = path.resolve(assetsRoot || process.argv[2] || 'stats-web/assets');
  checkAdministrationGuideResources(assets);
  const load = name => import(pathToFileURL(path.join(assets, name)).href);
  const [{ createAdministrationGuideData, ADMINISTRATION_GUIDE_IDS: ids }, { decodePagingToneDetection },
    { decodeWebDiagnosticLog }, { decodeRemoteSupport }, { decodeOffsetPage }] = await Promise.all([
    load('guides/administration-data.js'), load('features/paging-tone-detection.js'),
    load('features/web-diagnostic-log.js'), load('features/remote-support.js'), load('core/page-lifecycle.js')
  ]);
  const now = Date.UTC(2026, 9, 24, 15, 0), owned = [];
  const originalFetch = global.fetch;
  let network = 0;
  global.fetch = () => { network++; throw new Error('An Administration example reached the network.'); };
  const create = options => { const data = createAdministrationGuideData({ now, ...options }); owned.push(data); return data; };
  const raw = async (data, endpoint) => (await data.fetchDocument(endpoint)).json();
  const query = values => `/api/v1/admin/retained-statistics/results?${new URLSearchParams(values)}`;
  try {
    const data = create();
    const health = data.getReceiverHealth(), status = data.getStatus();
    assert.deepEqual(await data.requestJson('/api/v1/receiver-health'), health);
    assert.deepEqual(await data.requestJson('/api/v1/status'), status);
    assert.equal(health.active.length, 1);
    assert.equal(health.resolved.length, 1);
    assert.equal(health.summary.active_count, health.active.length);
    assert.equal(health.computer.java_version, '25');
    assert.equal(health.computer.application_uptime_ms, now - health.started_at_ms);
    assert.ok(health.resolved[0].resolved_at_ms > health.resolved[0].opened_at_ms);
    assert.ok(health.measurements.find(group => group.id === 'host').rows.length >= 3);
    health.active[0].title = 'Changed by a caller';
    status.stats_logging.summary_active = false;
    assert.notEqual(data.getReceiverHealth().active[0].title, health.active[0].title);
    assert.equal(data.getStatus().stats_logging.summary_active, true);

    // Every Administration page uses its normal API document, or its existing
    // support form. Computer & version shares the Receiver status snapshot.
    const pages = {
      health: ['/api/v1/receiver-health'], computer: ['/api/v1/receiver-health'],
      'application-log': ['/api/v1/application-log'], 'web-diagnostic-log': ['/api/v1/admin/web-diagnostic-log', '/api/v1/admin/web-diagnostic-log/entries'],
      support: ['/api/v1/remote-support'], 'remote-support': ['/api/v1/remote-support'],
      'call-matching': ['/api/v1/admin/call-matching'], 'tuner-allocation': ['/api/v1/admin/tuner-allocation', '/api/v1/channel-catalog'],
      recordings: ['/api/v1/admin/recordings/settings', '/api/v1/admin/recordings/status'],
      'audio-quality': ['/api/v1/admin/paging-tone-detection', '/api/v1/admin/operational-preferences'],
      transcription: ['/api/v1/admin/recordings/settings', '/api/v1/admin/recordings/status'],
      'push-setup': ['/api/v1/admin/alerts/config'], activity: ['/api/v1/admin/operational-preferences'],
      'retained-statistics': ['/api/v1/admin/retained-statistics/sources'],
      'remote-links': ['/api/v1/admin/remote-links'], 'protocol-p25': ['/api/v1/admin/p25-bandplan-overrides'],
      display: ['/api/v1/admin/receiver-settings', '/api/v1/admin/spectrum-snap-presets'],
      users: ['/api/v1/admin/users'], access: ['/api/v1/admin/access']
    };
    assert.equal(Object.keys(pages).length, 19);
    for (const [page, endpoints] of Object.entries(pages)) for (const endpoint of endpoints) {
      const response = await data.fetchDocument(endpoint);
      assert.equal(response.ok, true, `${page} can load its real renderer.`);
      assert.equal(response.status, 200);
      assert.equal(response.headers.get('Content-Type'), 'application/json');
      const document = await response.json();
      assert.equal(typeof document, 'object');
      await assert.rejects(response.json(), /already read/);
    }
    const tone = decodePagingToneDetection(await raw(data, '/api/v1/admin/paging-tone-detection'));
    assert.equal(tone.enabled, true);
    assert.equal(decodeWebDiagnosticLog(await data.requestJson('/api/v1/admin/web-diagnostic-log')).active, true);
    assert.equal(decodeRemoteSupport(await raw(data, '/api/v1/remote-support')).canStart, true);
    assert.equal(decodeRemoteSupport(await raw(data, '/api/v1/remote-support')).enabled, false);
    const operations = await raw(data, '/api/v1/admin/operational-preferences');
    assert.match(operations.revision, /^[a-f0-9]{64}$/);
    assert.equal(operations.settings.stats_logging_retention_days, 30);
    assert.equal(operations.settings.stats_detailed_history_enabled, true);
    const formatOptions = operations.options.mp3_input_audio_formats_by_setting[operations.settings.mp3_setting];
    assert.ok(formatOptions.some(format => format.value === operations.settings.mp3_input_audio_format));
    const recordings = await data.requestJson('/api/v1/admin/recordings/settings');
    assert.equal(recordings.mode, 'MANAGED');
    assert.equal(recordings.retention_days, 30);
    assert.equal(recordings.transcription_enabled, true, 'The progress example has an explicitly configured service.');
    assert.equal(recordings.transcription_min_duration_ms, 500);
    assert.equal(recordings.transcription_key_configured, false);
    assert.equal(recordings.transcription_url, 'https://speech.example.invalid/v1/audio/transcriptions');
    assert.equal(recordings.transcription_model, 'example-speech-model');
    const catalog = await data.requestJson('/api/v1/admin/recordings/status');
    assert.ok(catalog.catalog.call_count > 0);
    assert.ok(catalog.transcription.pending > 0 && catalog.transcription.completed > 0 && catalog.transcription.failed > 0);
    assert.equal(catalog.transcription.active, true);
    assert.equal(catalog.transcription.pending + catalog.transcription.completed + catalog.transcription.failed, catalog.catalog.call_count);
    const users = await data.requestJson('/api/v1/admin/users');
    assert.deepEqual(users.users.map(user => user.primary), [true, false]);
    assert.ok(users.users.every(user => !Object.hasOwn(user, 'password')));
    const access = await data.requestJson('/api/v1/admin/access');
    assert.ok(access.capabilities.some(policy => policy.id === 'web-access'));
    assert.ok(access.capabilities.filter(policy => policy.id.startsWith('admin-')).every(policy =>
      policy.required_tier === 'admin' && policy.configurable === false));
    const remote = await data.requestJson('/api/v1/admin/remote-links');
    assert.equal(remote.senders[0].feeds[0].alias_list_id, ids.countyList);
    assert.ok(remote.export_channel_options.every(row => row.channel_configuration_id));
    assert.equal(Object.hasOwn(remote.senders[0], 'secret'), false);
    assert.equal((await data.requestJson('/api/v1/admin/alerts/config')).registration.status, 'pending');

    const matching = await data.requestJson('/api/v1/admin/call-matching?summary=true');
    assert.equal(matching.duplicates.length, 2);
    for (const decision of matching.duplicates) {
      assert.equal(decision.outcome, 'MERGED');
      assert.equal(decision.legs.length, 2);
      assert.equal(decision.legs.filter(leg => leg.selected).length, 1);
      const winner = decision.legs.find(leg => leg.copy_index === decision.winner.selected_copy_index);
      assert.equal(winner.selected, true);
      assert.equal(decision.call_identity.unique_learned_site_count, 2);
      assert.ok(winner.start_timestamp <= decision.call_identity.end_timestamp);
    }
    const allocation = await data.requestJson('/api/v1/admin/tuner-allocation');
    assert.deepEqual(allocation.tuners.map(tuner => tuner.name), ['RTL-SDR 1', 'RTL-SDR 2']);
    assert.equal(allocation.tuners[0].center_locked, true);
    assert.equal(allocation.tuners[1].center_locked, false);
    assert.deepEqual(allocation.sites.map(site => site.system_name), ['County P25', 'City P25']);
    assert.ok(allocation.sites.every(site => site.frequencies.some(frequency => !frequency.covered)));
    for (const site of allocation.sites) {
      assert.equal(site.known_frequency_count, site.frequencies.length);
      assert.equal(site.covered_frequency_count, site.frequencies.filter(frequency => frequency.covered).length);
    }
    const channels = await data.requestJson('/api/v1/channel-catalog');
    assert.deepEqual(allocation.sites.map(site => site.id), channels.channels.slice(0, 2).map(channel => channel.configuration_id));
    const logs = await data.requestJson('/api/v1/application-log?log=current&compact=true');
    assert.ok(logs.entries.some(entry => entry.level === 'WARN'));
    assert.equal((await data.requestJson('/api/v1/application-log?log=previous')).log, 'previous');

    const sources = await data.requestJson('/api/v1/admin/retained-statistics/sources?kind=radio_system&limit=1');
    decodeOffsetPage(sources, 'example statistics');
    assert.equal(sources.rows[0].source_key, ids.countySystem);
    assert.equal(sources.next_offset, 1);
    assert.equal((await data.requestJson('/api/v1/admin/retained-statistics/sources?kind=radio_system&offset=1')).rows[0].source_key, ids.citySystem);
    const radioQuery = { source_kind: 'radio_system', source_key: ids.countySystem, data_type: 'radios' };
    const radios = await data.requestJson(query(radioQuery));
    assert.deepEqual(radios.rows.map(row => row.label), ['Engine 7', 'Sheriff 214']);
    assert.equal((await data.requestJson(query({ ...radioQuery, q: 'Sheriff' }))).rows.length, 1);
    assert.equal((await data.requestJson(query({ ...radioQuery, source_key: ids.citySystem }))).rows[0].label, 'Engine 2');
    for (const type of ['all', 'hourly_history', 'detailed_events', 'control_quality', 'alias_activity']) {
      const result = await data.requestJson(query({ source_kind: type === 'alias_activity' ? 'alias_activity' : 'radio_system', data_type: type }));
      decodeOffsetPage(result, 'example statistics');
      assert.equal(result.rows[0].target.kind, 'scoped_data');
      assert.ok(result.rows[0].target.parts.length > 0);
    }
    const frequency = await data.requestJson(query({ source_kind: 'saved_channel', source_key: ids.conventionalChannel, data_type: 'frequencies' }));
    assert.equal(frequency.rows[0].frequency_hz, 154_280_000);
    assert.equal(frequency.rows[0].channel_kind, 'CONVENTIONAL');
    const sites = await data.fetchDocument(`/api/v1/admin/retained-statistics/sites?source_key=${encodeURIComponent(ids.citySystem)}`);
    assert.equal((await sites.json()).data[0].configuration_id, ids.cityChannel);
    await assert.rejects(data.requestJson(query({ ...radioQuery, source_key: 'unknown' })), /Unknown example statistics source/);

    for (const method of ['POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']) {
      await assert.rejects(data.requestJson('/api/v1/admin/users', { method }), /read only/);
      await assert.rejects(data.fetchDocument('/api/v1/remote-support', { method }), /read only/);
    }
    await assert.rejects(data.requestJson('/api/v1/admin/users', { body: {} }), /read only/);
    for (const endpoint of ['/api/v1/auth/session', '/api/v1/auth/login', '/api/v1/me/preferences',
      '/api/v1/admin/retained-statistics/preview', '/api/v1/admin/support-reports', '/api/v1/admin/web-diagnostic-log/download',
      '/api/v1/live/multiplex', '/api/v1/calls/feed', '/api/v1/admin/users/secret',
      'https://receiver.invalid/api/v1/status', '//receiver.invalid/api/v1/status', '/api/v1/admin/../status',
      '/api/v1/status#fragment', '/api/v1/status\\extra'])
      await assert.rejects(data.fetchDocument(endpoint), /Unknown Administration example request/);
    await assert.rejects(data.requestJson('/api/v1/admin/operational-preferences'), /raw JSON document/);
    let observedResponse = false;
    await data.requestJson('/api/v1/status', { onResponse(response) { observedResponse = response.headers.has('Date'); } });
    assert.equal(observedResponse, true);
    const responseAbort = new AbortController();
    const response = await data.fetchDocument('/api/v1/status', { signal: responseAbort.signal });
    responseAbort.abort();
    await assert.rejects(response.json(), { name: 'AbortError' });
    const during = new AbortController();
    const pending = data.requestJson('/api/v1/admin/users', { signal: during.signal });
    during.abort();
    await assert.rejects(pending, { name: 'AbortError' });
    const lifetime = new AbortController(), expired = create({ signal: lifetime.signal });
    const unread = await expired.fetchDocument('/api/v1/status');
    lifetime.abort();
    assert.throws(() => expired.getReceiverHealth(), { name: 'AbortError' });
    await assert.rejects(unread.text(), { name: 'AbortError' });
    await assert.rejects(expired.requestJson('/api/v1/status'), { name: 'AbortError' });
    data.dispose(); data.dispose();
    assert.throws(() => data.getStatus(), { name: 'AbortError' });
    await assert.rejects(data.fetchDocument('/api/v1/status'), { name: 'AbortError' });
    assert.equal(network, 0);
  } finally {
    owned.forEach(data => data.dispose());
    global.fetch = originalFetch;
  }
  console.log('Administration guide data checks passed.');
}

module.exports = { checkAdministrationGuideData };
if (require.main === module) checkAdministrationGuideData().catch(error => { console.error(error); process.exitCode = 1; });
