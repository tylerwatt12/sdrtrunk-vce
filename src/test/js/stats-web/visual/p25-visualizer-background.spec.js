'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, systemKey, channelId, groupKey, radioKey } =
  require('./fixtures/site-style-app.cjs');

async function observeBackgroundActivity(page) {
  const fixture = await installSiteStyleApplication(page);
  await page.clock.install({ time: new Date('2026-10-01T12:00:00Z') });
  await page.clock.pauseAt(new Date('2026-10-01T12:00:01Z'));
  await page.addInitScript(() => {
    let hidden = false;
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
    Object.defineProperty(document, 'visibilityState', {
      configurable: true, get: () => hidden ? 'hidden' : 'visible'
    });
    window.p25SetHidden = value => {
      hidden = value;
      document.dispatchEvent(new Event('visibilitychange'));
    };
    window.p25BackgroundTransport = { streamOpens: 0, sourcesOpened: 0, sourcesClosed: 0,
      activityOpened: 0, activityClosed: 0, controls: [] };
    const original = window.fetch.bind(window);
    window.fetch = (input, options) => {
      const url = new URL(typeof input === 'string' ? input : input.url, location.href);
      if (url.pathname === '/api/v1/live/multiplex') window.p25BackgroundTransport.streamOpens++;
      if (url.pathname === '/api/v1/live/multiplex/control' && options?.body)
        window.p25BackgroundTransport.controls.push(JSON.parse(options.body));
      return original(input, options);
    };
  });
  const feature = path.resolve(__dirname, '../../../../../stats-web/assets/features/network-visualizer');
  const createMarker = 'function createP25Visualizer(dependencies = {}) {';
  const stateMarker = '  const close = () => {';
  let indexSource = fs.readFileSync(path.join(feature, 'index.js'), 'utf8');
  expect(indexSource.split(createMarker)).toHaveLength(2);
  expect(indexSource.split(stateMarker)).toHaveLength(2);
  indexSource = indexSource.replace(createMarker, `${createMarker}
  const observed = window.p25BackgroundTransport;
  const originalStream = dependencies.openActivityStream;
  const originalActivity = dependencies.subscribeActivity;
  const observeConnection = (source, field) => {
    let closed = false;
    return { close() { if (closed) return; closed = true; observed[field]++; source.close(); } };
  };
  dependencies = { ...dependencies,
    openActivityStream(callbacks) {
      observed.sourcesOpened++;
      return observeConnection(originalStream(callbacks), 'sourcesClosed');
    },
    subscribeActivity(callbacks) {
      observed.activityOpened++;
      return observeConnection(originalActivity(callbacks), 'activityClosed');
    }
  };`).replace(stateMarker, `
  window.p25BackgroundState = () => ({
    counts: Object.fromEntries(state.actionCounts), active: [...state.activeKeys].sort(),
    affiliations: [...state.currentAffiliations.values()].map(fact => ({
      radio: fact.radio_identity_key, talkgroup: fact.talkgroup_identity_key })),
    radios: [...state.radios.keys()], cameraPhase: camera.snapshot().phase
  });
${stateMarker}`);
  const readyMarker = "\n  host.dataset.rendererState = 'ready';";
  let rendererSource = fs.readFileSync(path.join(feature, 'renderer.js'), 'utf8');
  expect(rendererSource.split(readyMarker)).toHaveLength(2);
  rendererSource = rendererSource.replace(readyMarker, `
  let observedPublications = 0, observedDraws = 0;
  const originalGraphData = graph.graphData;
  graph.graphData = function(...args) {
    if (args.length) observedPublications++;
    return originalGraphData.apply(this, args);
  };
  const painter = graph.renderer(), originalRender = painter.render;
  painter.render = function(...args) { observedDraws++; return originalRender.apply(this, args); };
  window.p25BackgroundScene = () => ({ publications: observedPublications, draws: observedDraws,
    nodes: graph.graphData().nodes.map(({ id, type, x, y, z, active, affiliationGroupKey }) =>
      ({ id, type, x, y, z, active, affiliationGroupKey })),
    camera: cameraPose(), trips: affiliationMoves.size,
    painted: nodeObjects.size >= 3 && observedDraws > 0
  });
${readyMarker}`);
  await page.route('**/assets/features/network-visualizer/index.js?**', route =>
    route.fulfill({ contentType: 'text/javascript', body: indexSource }));
  await page.route('**/assets/features/network-visualizer/renderer.js?**', route =>
    route.fulfill({ contentType: 'text/javascript', body: rendererSource }));
  await page.goto('/app.html?view=network-visualizer');
  await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
  await page.clock.runFor(150);
  await expect(page.locator('.network-visualizer-status')).toHaveText('Live');
  await expect.poll(() => page.evaluate(() => window.p25BackgroundScene().painted)).toBe(true);
  return fixture;
}

const groupB = 'v1-g-00001-001-1202';
const groupC = 'v1-g-00001-001-1203';
const otherRadio = 'v1-r-00001-001-30915';
const key = identity => `${systemKey}:${identity}`;
const liveRow = (radio, talkgroup, id, alias) => ({ key: `voice-${id}`, protocol: 'P25',
  serving_system_key: systemKey, system_name: 'County P25', configuration_id: channelId,
  status: 'CALL', activation_order: id, source_id: id, source_alias: alias,
  source_identity_key: radio, source_identity_kind_code: 2,
  target_id: Number(talkgroup.split('-').at(-1)), target_identity_key: talkgroup,
  target_kind: 'talkgroup', target_alias: talkgroup === groupC ? 'Operations' : 'Fire Tactical' });
const table = rows => ({ table_id: channelId, configuration_id: channelId, system_name: 'County P25',
  channel_running: true, rows });

const counts = page => page.evaluate(() => {
  const value = category => Number(document.querySelector(
    `.network-visualizer-action[data-category="${category}"] .ui-pill`).textContent);
  return { emergency: value('emergency'), call: value('call'), movement: value('movement') };
});

test('hidden tabs keep live counters and state while rendering sleeps, then show the latest scene', async ({ page }) => {
  const fixture = await observeBackgroundActivity(page);
  const before = await page.evaluate(() => structuredClone(window.p25BackgroundTransport));
  expect(before.sourcesOpened).toBe(1);
  expect(before.sourcesClosed).toBe(0);
  expect(before.activityOpened).toBe(1);
  expect(before.activityClosed).toBe(0);
  await page.evaluate(() => window.p25SetHidden(true));
  await page.clock.runFor(32);
  const sleeping = await page.evaluate(() => window.p25BackgroundScene());
  const rows = [
    { id: 2, action: 'EMERGENCY' }, { id: 3, action: 'CALL' }
  ].map(row => ({ ...row, observed_at_ms: Date.parse('2026-10-01T12:00:01Z'),
    protocol: 'P25', channel_kind: 'TRUNKED_SITE', radio_system_key: systemKey,
    configuration_id: channelId, source_identity_key: radioKey, source_radio_id: 30914,
    source_alias_name: 'Engine 4', target_identity_key: groupKey, target_kind: 'talkgroup',
    target_id: 1201, target_alias_name: 'Fire Dispatch' }));
  await page.evaluate(({ rows, systemKey, channelId, groupKey, radioKey, groupB, groupC }) => {
    window.p25HiddenAppend = { source_epoch: 'fixture-p25', after_id: 1, next_after_id: 3,
      watermark_id: 3, rows };
    window.fixtureSendLiveFrame(7, 'activity_append', window.p25HiddenAppend);
    const fact = (talkgroup, at) => ({ radio_system_key: systemKey, radio_identity_key: radioKey,
      talkgroup_identity_key: talkgroup, configuration_id: channelId, confirmed_at_ms: at,
      radio_id: 30914, radio_alias_name: 'Engine 4', talkgroup_id: Number(talkgroup.split('-').at(-1)),
      talkgroup_alias_name: talkgroup === groupC ? 'Operations' : talkgroup === groupB ? 'Fire Tactical' : 'Fire Dispatch' });
    const previous = fact(groupKey, Date.now() - 2_000), next = fact(groupB, Date.now() + 1);
    window.fixtureSendLiveFrame(7, 'p25_state_delta', { source_epoch: 'fixture-p25', base_sequence: 0, sequence: 1,
      part: 0, parts: 1, available: true, collection_enabled: true, removed_radio_keys: [],
      affiliations: [{ ...next, previous_affiliation: previous }] });
    window.p25HiddenDelta = { source_epoch: 'fixture-p25', base_sequence: 1, sequence: 2,
      part: 0, parts: 1, available: true, collection_enabled: true, removed_radio_keys: [],
      affiliations: [{ ...fact(groupC, Date.now() + 2), previous_affiliation: next }] };
    window.fixtureSendLiveFrame(7, 'p25_state_delta', window.p25HiddenDelta);
  }, { rows, systemKey, channelId, groupKey, radioKey, groupB, groupC });
  await expect.poll(() => counts(page)).toEqual({ emergency: 1, call: 1, movement: 2 });
  await expect.poll(() => page.evaluate(() => window.p25BackgroundState().affiliations))
    .toEqual([{ radio: radioKey, talkgroup: groupC }]);
  await page.evaluate(snapshot => window.fixtureSendLiveSnapshot(snapshot), {
    revision: 2, tables: [table([liveRow(radioKey, groupB, 30914, 'Engine 4')])]
  });
  await expect.poll(() => page.evaluate(() => window.p25BackgroundState().active))
    .toEqual([key(groupB), key(radioKey)].sort());
  const latestRows = [liveRow(radioKey, groupC, 30914, 'Engine 4'),
    liveRow(otherRadio, groupC, 30915, 'Patrol 2')];
  await page.evaluate(({ tableId, rows }) => window.fixtureSendLiveFrame(1, 'activity_batch', {
    revision: 3, base_revision: 2, changes: [{ operation: 'upsert', table_id: tableId, replace: true,
      table: { configuration_id: tableId, system_name: 'County P25', channel_running: true },
      rows, row_order: rows.map(row => row.key) }]
  }), { tableId: channelId, rows: latestRows });
  const latestActive = [key(groupC), key(radioKey), key(otherRadio)].sort();
  await expect.poll(() => page.evaluate(() => window.p25BackgroundState().active)).toEqual(latestActive);
  await page.evaluate(() => {
    window.fixtureSendLiveFrame(7, 'activity_append', window.p25HiddenAppend);
    window.fixtureSendLiveFrame(7, 'p25_state_delta', window.p25HiddenDelta);
  });
  await page.clock.runFor(1_500);
  expect(await counts(page)).toEqual({ emergency: 1, call: 1, movement: 2 });
  const hiddenScene = await page.evaluate(() => window.p25BackgroundScene());
  expect(hiddenScene.publications).toBe(sleeping.publications);
  expect(hiddenScene.draws).toBe(sleeping.draws);
  expect(hiddenScene.camera).toEqual(sleeping.camera);
  expect(hiddenScene.nodes).toEqual(sleeping.nodes);
  await expect(page.locator('.network-visualizer-status')).toHaveText('Live');
  const hiddenTransport = await page.evaluate(() => structuredClone(window.p25BackgroundTransport));
  expect(hiddenTransport.sourcesOpened).toBe(before.sourcesOpened);
  expect(hiddenTransport.sourcesClosed).toBe(0);
  expect(hiddenTransport.activityClosed).toBe(0);
  expect(hiddenTransport.streamOpens).toBe(before.streamOpens);
  await page.evaluate(() => window.p25SetHidden(false));
  await page.clock.runFor(100);
  const resumed = await page.evaluate(() => window.p25BackgroundScene());
  expect(resumed.publications).toBeGreaterThan(sleeping.publications);
  expect(resumed.nodes.filter(node => node.active).map(node => node.id).sort()).toEqual(latestActive);
  const radio = resumed.nodes.find(node => node.id === key(radioKey));
  expect(radio.affiliationGroupKey).toBe(key(groupC));
  expect(resumed.trips).toBe(0);
  await expect(page.locator('.network-visualizer-auto-focus')).toBeHidden();
  expect(await page.evaluate(() => window.p25BackgroundState().cameraPhase)).toBe('roam');
  await page.clock.runFor(1_500);
  const settled = await page.evaluate(() => window.p25BackgroundScene());
  expect(settled.nodes.find(node => node.id === key(radioKey))).toEqual(radio);
  expect(settled.trips).toBe(0);
  expect(await counts(page)).toEqual({ emergency: 1, call: 1, movement: 2 });
  const after = await page.evaluate(() => structuredClone(window.p25BackgroundTransport));
  expect(after.sourcesOpened).toBe(1);
  expect(after.sourcesClosed).toBe(0);
  expect(after.streamOpens).toBe(before.streamOpens);
  const p25Ids = after.controls.map(control => control.subscriptions?.p25_activity?.subscription_id).filter(Boolean);
  expect(new Set(p25Ids).size).toBe(1);
  await page.evaluate(() => document.querySelector('.primary-nav a[data-view="credits"]').click());
  await expect(page.locator('.credits-copy').first()).toBeVisible();
  await page.clock.runFor(100);
  const closed = await page.evaluate(() => window.p25BackgroundTransport);
  expect(closed.sourcesClosed).toBe(1);
  expect(closed.activityClosed).toBe(1);
  expect(closed.controls.at(-1).subscriptions?.p25_activity).toBeUndefined();
  expect(closed.controls.at(-1).subscriptions?.channel_activity).toBeUndefined();
  expect(fixture.requests.filter(url => ['/api/v1/activity', '/api/v1/activity/live/seed'].includes(url))).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});
