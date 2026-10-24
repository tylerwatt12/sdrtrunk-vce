'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function checkMainGuideData(assetsRoot) {
  const assets = path.resolve(assetsRoot || process.argv[2] || 'stats-web/assets');
  const moduleAt = name => import(pathToFileURL(path.join(assets, name)).href);
  const [{ createMainGuideData }, { href }, { decodeOffsetPage }, { createMessageTypeCatalog }] = await Promise.all([
    moduleAt('guides/main-presentation.js'), moduleAt('core/entity-ref.js'),
    moduleAt('core/page-lifecycle.js'), moduleAt('core/message-types.js')
  ]);
  const original = { now: Date.now, fetch: global.fetch };
  const owned = [];
  let now = Date.UTC(2026, 9, 24, 14, 32), networkCalls = 0;
  Date.now = () => now;
  global.fetch = () => { networkCalls += 1; throw new Error('Guide data must remain offline.'); };
  const create = options => {
    const data = createMainGuideData(options);
    owned.push(data);
    return data;
  };
  const total = (rows, field) => rows.reduce((sum, row) => sum + row[field], 0);
  const validReference = reference => assert.ok(href(reference), `Production navigation accepts ${JSON.stringify(reference)}`);
  try {
    const controller = new AbortController(), data = create({ signal: controller.signal, section: 'systems' });
    assert.equal(data.selectedListId, 9401);
    assert.equal(data.selectedSystemKey, 'p25:bee00:123');
    assert.equal(data.selectedAction, 'EMERGENCY');
    const snapshot = await data.api('/api/v1/dashboard/main');
    assert.deepEqual(snapshot.directory.systems.map(system => system.system_name), ['County P25', 'City P25']);
    const county = snapshot.directory.systems[0], city = snapshot.directory.systems[1];
    assert.deepEqual(county.children.map(channel => channel.name), ['North Site', 'South Site']);
    assert.equal(city.children[0].name, 'City Site');
    assert.equal(snapshot.directory.channels[0].name, 'County Fireground');
    assert.equal(snapshot.directory.channels[0].channel_kind, 'CONVENTIONAL');
    assert.ok(snapshot.directory.systems.every(system => {
      validReference(system.entity_ref);
      return system.children.every(channel => channel.processing_state === 'RUNNING' &&
        channel.display_frequency_source === 'active-control' && channel.display_frequency_hz > 0);
    }));
    const quality = snapshot.quality.current;
    assert.equal(quality.find(row => row.configuration_id === data.selectedConfigurationId).decode_health_pct, 99.3);
    assert.equal(quality.find(row => row.name === 'South Site').decode_health_pct, 82.4);
    assert.ok(quality.every(row => row.last_observed_ms === now && Number.isFinite(row.signal_dbfs) &&
      Number.isFinite(row.average_signal_dbfs)));
    snapshot.directory.systems[0].children[0].name = 'Changed only in one response';
    snapshot.quality.current[0].decode_health_pct = 0;
    now += 5_000;
    const refreshed = await data.requestJson('/api/v1/dashboard/main');
    assert.equal(refreshed.directory.systems[0].children[0].name, 'North Site');
    assert.equal(refreshed.quality.current[0].decode_health_pct, 99.3);
    assert.equal(refreshed.quality.current[0].last_observed_ms, now,
      'A refreshed example keeps the production quality-freshness gate satisfied.');

    const statistics = await data.api('/api/v1/dashboard'), calls = statistics.call_activity;
    assert.deepEqual(calls.totals, { logical_call_count: 1000, recorded_logical_call_count: 800,
      stream_submitted_logical_call_count: 250 });
    assert.equal(calls.totals.recorded_logical_call_count / calls.totals.logical_call_count, 0.8);
    assert.equal(calls.totals.stream_submitted_logical_call_count / calls.totals.logical_call_count, 0.25);
    assert.ok(calls.totals.recorded_logical_call_count + calls.totals.stream_submitted_logical_call_count >
      calls.totals.logical_call_count, 'The example permits the same call to be recorded and streamed.');
    assert.equal(calls.series.length, 48);
    assert.equal(calls.bucket_ms, 3_600_000);
    assert.equal(calls.from_ms, Math.floor(now / calls.bucket_ms) * calls.bucket_ms - 23 * calls.bucket_ms);
    assert.equal(calls.to_ms, now);
    for (const field of Object.keys(calls.totals)) {
      assert.equal(total(calls.series, field), calls.totals[field], 'The actual chart and summary have the same count.');
      assert.equal(calls.metric_coverage[field], 'COLLECTED');
    }
    assert.ok(calls.series.every(row => row.time_ms >= calls.from_ms && row.time_ms < calls.to_ms));
    const nonboundaryNow = now;
    now = Math.floor(now / calls.bucket_ms) * calls.bucket_ms;
    const exactHourData = create(), exactHour = (await exactHourData.api('/api/v1/dashboard')).call_activity;
    assert.equal(exactHour.to_ms, now);
    assert.equal(exactHour.from_ms, now - 24 * calls.bucket_ms);
    const visibleAtExactHour = exactHour.series.filter(row => row.time_ms >= exactHour.from_ms && row.time_ms < exactHour.to_ms);
    assert.equal(visibleAtExactHour.length, 48,
      'Every sample bucket remains visible under the production chart’s exclusive end boundary.');
    for (const field of Object.keys(exactHour.totals)) {
      assert.equal(total(visibleAtExactHour, field), exactHour.totals[field]);
      assert.equal(exactHour.totals[field], calls.totals[field]);
    }
    exactHourData.dispose();
    now = nonboundaryNow;
    for (const protocol of ['P25', 'NBFM']) {
      const series = calls.series.filter(row => row.protocol === protocol);
      assert.equal(series.length, 24);
      assert.equal(new Set(series.map(row => row.time_ms)).size, 24);
      assert.ok(new Set(series.map(row => row.logical_call_count)).size > 3, 'Hourly traffic has visible quiet and busy periods.');
      assert.ok(series.every(row => Number.isInteger(row.logical_call_count) && row.logical_call_count >= 0 &&
        row.recorded_logical_call_count <= row.logical_call_count &&
        row.stream_submitted_logical_call_count <= row.logical_call_count));
      assert.equal(calls.coverage.find(row => row.protocol === protocol &&
        row.channel_kind === series[0].channel_kind).status, 'COLLECTED');
    }
    const conventional = decodeOffsetPage(statistics.source_activity_24h, '/api/v1/dashboard');
    assert.equal(conventional.rows[0].logical_call_count, total(calls.series.filter(row => row.protocol === 'NBFM'), 'logical_call_count'));
    assert.deepEqual(statistics.top_destinations.map(row => row.alias_name), ['Sheriff Dispatch', 'Regional Fire']);
    assert.deepEqual(statistics.top_sources.map(row => row.alias_name), ['Sheriff 214', 'Engine 7']);
    for (const row of [...statistics.top_destinations, ...statistics.top_sources, ...conventional.rows]) {
      validReference(row.entity_ref);
      assert.ok(row.recorded_logical_call_count <= row.logical_call_count);
      assert.ok(row.stream_submitted_logical_call_count <= row.logical_call_count);
    }
    const trunked = calls.series.filter(row => row.channel_kind === 'TRUNKED');
    for (const rows of [statistics.top_destinations, statistics.top_sources]) {
      for (const field of Object.keys(calls.totals)) {
        assert.ok(total(rows, field) < total(trunked, field),
          'Top identities show a bounded selection rather than every call-producing identity.');
      }
      assert.ok(rows[0].logical_call_count >= rows[1].logical_call_count,
        'The selected identities are ranked by calls.');
    }

    const catalog = createMessageTypeCatalog();
    const deliveredCatalog = await data.api('/api/v1/activity/message-types');
    assert.deepEqual(deliveredCatalog.rows, data.messageTypes);
    catalog.replace(deliveredCatalog.rows);
    for (const [name, label, code] of [['EMERGENCY', 'Emergency', 10], ['DENIAL', 'Denied', 9], ['BUSY', 'Busy', 3],
      ['RADIO_INHIBIT', 'Radio inhibit', 28], ['STUN', 'Stun', 25], ['KILL', 'Kill', 27]]) {
      assert.equal(catalog.label('ACTION', name), label);
      assert.equal(catalog.get('ACTION', name).code, code);
      const radios = await data.apiPage('/api/v1/activity/radios', { action: name, range: '24h', limit: 100, offset: 0 });
      assert.ok(radios.rows.length);
      assert.equal(total(radios.rows, 'observation_count'), radios.identified_observation_count);
      assert.equal(radios.unknown_source_observation_count, 0);
      for (const radio of radios.rows) {
        assert.equal(catalog.get('ACTION', name)[radio.protocol.toLowerCase()], true,
          'The example never assigns an unsupported command to a protocol.');
        validReference(radio.radio_entity_ref);
        validReference(radio.radio_system_entity_ref);
      }
    }
    const activity = await data.api('/api/v1/activity/actions', { range: '24h' });
    assert.equal(activity.total_observation_count, total(activity.rows, 'observation_count'));
    const emergency = await data.apiPage('/api/v1/activity/radios', { action: 'EMERGENCY', range: '24h' });
    assert.deepEqual(emergency.rows.map(row => row.alias_name), ['Sheriff 214', 'Engine 7', 'Patrol 12']);
    assert.equal(emergency.action_observation_count, activity.rows.find(row => row.action === 'EMERGENCY').observation_count);
    const nextRadio = await data.apiPage('/api/v1/activity/radios', { action: 'EMERGENCY', limit: 1, offset: 1 });
    assert.equal(nextRadio.rows[0].alias_name, 'Engine 7');
    assert.equal(nextRadio.has_more, true);
    assert.equal(nextRadio.next_offset, 2);
    assert.equal(nextRadio.total_count, 3);
    for (const [range, duration] of [['6h', 6 * 3_600_000], ['7d', 7 * 86_400_000]]) {
      const response = await data.api('/api/v1/activity/actions', { range });
      assert.equal(response.to_ms - response.from_ms, duration);
    }

    const lists = await data.apiPage('/api/v1/identities/lists', { limit: 500 });
    assert.deepEqual(lists.rows.map(list => list.name), ['County P25', 'City P25']);
    assert.equal(lists.rows[0].alias_list_id, data.selectedListId);
    const listPath = `/api/v1/identities/lists/${data.selectedListId}`;
    const inventory = await data.apiPage(`${listPath}/aliases`, { range: '24h', status: 'all', sort: 'name', direction: 'asc' });
    assert.equal(inventory.rows.length, lists.rows[0].alias_count);
    assert.ok(inventory.rows.some(row => row.name === 'Sheriff Dispatch' && row.logical_call_count > calls.totals.logical_call_count),
      'Alias tracking totals have their own cumulative history, distinct from the 24-hour call totals.');
    inventory.rows.forEach(row => validReference(row.entity_ref));
    const longer = await data.apiPage(`${listPath}/aliases`, { range: '7d', status: 'all', sort: 'name', direction: 'asc' });
    assert.deepEqual(longer.rows, inventory.rows, 'Heard within does not recalculate cumulative Calls or Signals.');
    const recent = await data.apiPage(`${listPath}/aliases`, { range: '24h', status: 'recent' });
    const recentWeek = await data.apiPage(`${listPath}/aliases`, { range: '7d', status: 'recent' });
    assert.equal(recent.rows.length, 3);
    assert.equal(recentWeek.rows.length, 4);
    const summary = await data.api(`${listPath}/overview`, { range: '24h' });
    assert.equal(summary.totals.active_alias_count, recent.rows.length);
    assert.equal(summary.correlated_channel_count, 2);
    assert.equal(summary.correlated_radio_system_count, 1);
    assert.deepEqual(summary.channels.map(row => row.configuration_id), county.children.map(row => row.configuration_id));
    const zero = await data.apiPage(`${listPath}/aliases`, { status: 'zero_calls' });
    assert.ok(zero.rows.some(row => row.name === 'Public Works' && row.signaling_observation_count > 0),
      'A zero-call alias can have signaling evidence.');
    const never = await data.apiPage(`${listPath}/aliases`, { status: 'never_heard' });
    assert.deepEqual(never.rows.map(row => row.name), ['Special Events']);
    assert.equal(never.rows[0].last_evidence_ms, null);
    assert.equal(never.rows[0].metrics_state, 'not_collected');
    const radioAliases = await data.apiPage(`${listPath}/aliases`, { type: 'radio' });
    assert.deepEqual(radioAliases.rows.map(row => row.name), ['Sheriff 214']);
    const searched = await data.apiPage(`${listPath}/aliases`, { q: 'road' });
    assert.deepEqual(searched.rows.map(row => row.name), ['Public Works']);
    const unknown = await data.apiPage(`${listPath}/unassigned`, { sort: 'logical_call_count', direction: 'desc' });
    assert.equal(unknown.rows[0].group_identity_id, 1645);
    assert.equal(unknown.rows[0].group_identity_kind, 'talkgroup');
    assert.equal(unknown.rows[0].channel_names, 'North Site');
    assert.equal(unknown.rows[0].configuration_id, data.selectedConfigurationId);
    for (const reference of [unknown.rows[0].entity_ref, unknown.rows[0].channel_entity_ref,
      unknown.rows[0].radio_system_entity_ref]) validReference(reference);
    assert.ok(!inventory.rows.some(row => row.value === unknown.rows[0].group_identity_id));
    const cityAliases = await data.apiPage(`/api/v1/identities/lists/${data.cityListId}/aliases`);
    assert.deepEqual(cityAliases.rows.map(row => row.name), ['City Fire', 'Patrol 12', 'Police Department']);
    assert.ok(cityAliases.rows.every(row => row.entity_ref.radio_system_key === data.citySystemKey));
    unknown.rows[0].logical_call_count = 99;
    assert.equal((await data.apiPage(`${listPath}/unassigned`)).rows[0].logical_call_count, 18);

    const envelope = await data.requestEnvelope('/api/v1/identities/lists?limit=1&offset=1');
    assert.equal(Array.isArray(envelope.data), true);
    assert.equal(envelope.meta.limit, 1);
    assert.equal(envelope.meta.offset, 1);
    assert.equal(envelope.data[0].alias_list_id, data.cityListId);
    assert.equal(envelope.meta.total_count, 2);
    const beforeInvalid = data.stats().requests.length;
    for (const path of ['/api/v1/admin/channels', '/api/v1/auth/session', '/api/v1/me/preferences',
      '/api/v1/status', '/api/v1/identities/lists/1/overview', '/api/v1/identities/lists/09401/aliases',
      '/api/v1/dashboard?unknown=1', '/api/v1/dashboard#fragment', '/api/v1/activity/actions?range=30d',
      '/api/v1/activity/radios?action=UNKNOWN', '/api/v1/activity/radios?action=EMERGENCY&limit=0',
      '/api/v1/activity/radios?action=EMERGENCY&limit=101', '/api/v1/activity/radios?action=EMERGENCY&offset=-1',
      '/api/v1/activity/radios?action=EMERGENCY&offset=10001', '/api/v1/identities/lists?limit=501',
      `${listPath}/aliases?status=unused`, `${listPath}/aliases?sort=private_key`, `${listPath}/aliases?direction=up`,
      `${listPath}/aliases?type=stream`, `${listPath}/aliases?q=${'x'.repeat(161)}`, `${listPath}/unassigned?range=24h`,
      `${listPath}/overview?range=all`, '/api/v1/activity/actions?range=24h&range=7d',
      'https://example.invalid/api/v1/dashboard', '//example.invalid/api/v1/dashboard', '/api/v1/../v1/dashboard']) {
      await assert.rejects(data.requestJson(path), /Unsupported|Unknown/, path);
    }
    for (const method of ['POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']) {
      await assert.rejects(data.requestJson('/api/v1/dashboard', { method }), /Unsupported/);
    }
    await assert.rejects(data.api('/api/v1/dashboard', {}, { body: {} }), /Unsupported/);
    assert.equal(data.stats().requests.length, beforeInvalid, 'Rejected actions cannot become successful sample reads.');

    const abortedRequest = new AbortController();
    abortedRequest.abort();
    await assert.rejects(data.api('/api/v1/dashboard', {}, { signal: abortedRequest.signal }), { name: 'AbortError' });
    const pendingController = new AbortController(), pending = create({ signal: pendingController.signal });
    const pendingRead = pending.api('/api/v1/dashboard/main');
    pendingController.abort();
    await assert.rejects(pendingRead, { name: 'AbortError' });
    assert.equal(pending.stats().disposed, true);
    controller.abort();
    data.dispose(); data.dispose();
    assert.equal(data.stats().disposed, true);
    assert.equal(data.stats().timers, 0);
    assert.equal(data.stats().connections, 0);
    await assert.rejects(data.api('/api/v1/dashboard'), { name: 'AbortError' });
    const alreadyAborted = new AbortController();
    alreadyAborted.abort();
    const closed = create({ signal: alreadyAborted.signal });
    await assert.rejects(closed.api('/api/v1/dashboard/main'), { name: 'AbortError' });
    const next = create();
    assert.equal((await next.api('/api/v1/dashboard')).call_activity.totals.logical_call_count, 1000,
      'A later guide instance receives fresh, independent data.');
    assert.equal(networkCalls, 0);
    console.log('Main guide offline data, production paging/navigation/catalog, totals, inventory scopes and cancellation checks passed.');
  } finally {
    owned.forEach(data => data.dispose());
    Date.now = original.now;
    global.fetch = original.fetch;
  }
}

module.exports = { checkMainGuideData };
if (require.main === module) checkMainGuideData().catch(error => { console.error(error); process.exitCode = 1; });
