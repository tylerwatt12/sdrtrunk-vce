'use strict';

const assert = require('node:assert/strict');
const vm = require('node:vm');

async function checkDashboardData(appSource, functionBinding) {
  const main = { quality: { current: [{ configuration_id: 'north' }] },
    directory: { systems: [{ children: [] }], channels: [] } };
  const requests = [];
  let next = main;
  const receiverTime = 1_800_000_000_000;
  let clientTime = receiverTime - 86_400_000;
  let elapsed = 100;
  let dateHeader = new Date(receiverTime).toUTCString();
  const context = vm.createContext({
    Date: { now: () => clientTime, parse: Date.parse }, performance: { now: () => elapsed },
    ACCESS_CAPABILITIES: { RADIO: 'radio' }, capabilityAllowed: () => true,
    api: async (path, parameters, options) => {
      requests.push(path);
      options.onResponse({ headers: { get: () => dateHeader } });
      // Body parsing time must count towards sample age, too.
      elapsed += 25;
      return next;
    }
  });
  vm.runInContext(`async function loadMainDashboard(requestApi = api) ${functionBinding(appSource, 'loadMainDashboard')}`, context);
  let snapshot = await context.loadMainDashboard();
  assert.strictEqual(snapshot.quality, main.quality);
  assert.strictEqual(snapshot.directory, main.directory);
  assert.deepEqual(requests, ['/api/v1/dashboard/main']);
  assert.equal(snapshot.receiverNow(), receiverTime + 25);
  clientTime = receiverTime + 86_400_000;
  elapsed += 45_000;
  assert.equal(snapshot.receiverNow(), receiverTime + 45_025,
    'Sample age advances even if the client wall clock changes or refreshes fail.');
  dateHeader = new Date(receiverTime + 60_000).toUTCString();
  snapshot = await context.loadMainDashboard();
  assert.equal(snapshot.receiverNow(), receiverTime + 60_025, 'Each successful response replaces the time anchor.');
  for (dateHeader of [null, 'invalid']) {
    snapshot = await context.loadMainDashboard();
    assert.equal(snapshot.receiverNow(), clientTime, 'Missing response time retains the prior local-time behavior.');
  }
  for (const invalid of [null, {}, { quality: { current: [] }, directory: { systems: [{}], channels: [] } }]) {
    next = invalid;
    await assert.rejects(context.loadMainDashboard(), /Reload the page/);
  }
  context.capabilityAllowed = () => false;
  next = { quality: { current: [] } };
  assert.strictEqual((await context.loadMainDashboard()).quality, next.quality,
    'A dashboard-only visitor does not require radio directory data.');

  main.receiverNow = () => receiverTime;

  const dataRequests = [];
  let current = true;
  let callback;
  let loads = 0;
  let qualityUpdates = 0;
  let directoryUpdates = 0;
  let failures = 0;
  let failNext = false;
  let release;
  let pauseNext = false;
  const page = vm.createContext({
    route: new URLSearchParams(), ACCESS_CAPABILITIES: { RADIO: 'radio' },
    captureRenderContext: () => ({}), renderIsCurrent: () => current,
    beginPage: () => true, pageHeader: () => ({}),
    tabs: () => ({ classList: { add() {} }, setAttribute() {} }), href: () => '',
    node: () => ({ remove() {} }), content: { append() {} }, capabilityAllowed: () => true,
    api: async (path) => { dataRequests.push(path); return {}; },
    loadMainDashboard: async () => {
      loads += 1;
      if (pauseNext) { pauseNext = false; await new Promise((resolve) => { release = resolve; }); }
      if (failNext) { failNext = false; throw new Error('Temporarily unavailable'); }
      return main;
    },
    renderNestedRadioDirectory: (_renderContext, directory, quality, options) => {
      assert.strictEqual(directory, main.directory);
      assert.strictEqual(quality, main.quality.current);
      assert.strictEqual(options.now, main.receiverNow);
      return { update(next, rows, now) {
        assert.strictEqual(next, directory); assert.strictEqual(rows, quality);
        assert.strictEqual(now, main.receiverNow);
        directoryUpdates += 1; qualityUpdates += 1;
      }, failed() { failures += 1; } };
    },
    pageInterval: (refresh, interval) => { assert.equal(interval, 5_000); callback = refresh; },
    renderDashboardActivity: async () => {}
  });
  vm.runInContext(`async function renderDashboard(options = {}) ${functionBinding(appSource, 'renderDashboard')}`, page);
  await page.renderDashboard();
  assert.equal(loads, 1);
  assert.deepEqual(dataRequests, [], 'Main does not load the call-statistics dashboard.');
  await callback();
  assert.equal(qualityUpdates, 1);
  assert.equal(directoryUpdates, 1, 'Quality and directory share each confirmed refresh.');
  failNext = true;
  await callback();
  assert.equal(failures, 1);
  assert.equal(qualityUpdates, 1, 'A failed refresh retains the last confirmed values.');
  pauseNext = true;
  const refresh = callback();
  const inFlightLoads = loads;
  await callback();
  assert.equal(loads, inFlightLoads, 'A slow snapshot cannot overlap another refresh.');
  release(); await refresh;
  current = false;
  await callback();
  assert.equal(loads, inFlightLoads, 'Leaving Main prevents further snapshot requests.');

  current = true;
  page.beginPage = () => false;
  for (const tab of ['health', 'activity', 'calls', 'inventory']) {
    page.route = new URLSearchParams(`tab=${tab}`);
    dataRequests.length = 0;
    await page.renderDashboard();
    assert.deepEqual(dataRequests, tab === 'calls' ? ['/api/v1/dashboard'] : [],
      'Only Call Statistics loads retained call aggregates.');
  }
  console.log('Main dashboard data ownership, request bounds, recovery, and lifecycle checks passed.');
}

module.exports = { checkDashboardData };
