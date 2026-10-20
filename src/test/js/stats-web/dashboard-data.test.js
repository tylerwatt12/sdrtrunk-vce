'use strict';

const assert = require('node:assert/strict');
const vm = require('node:vm');

async function checkDashboardData(appSource, functionBinding) {
  const main = { quality: { current: [{ configuration_id: 'north' }] },
    directory: { systems: [{ children: [] }], channels: [] } };
  const requests = [];
  let next = main;
  const context = vm.createContext({
    ACCESS_CAPABILITIES: { RADIO: 'radio' }, capabilityAllowed: () => true,
    api: async (path) => { requests.push(path); return next; }
  });
  vm.runInContext(`async function loadMainDashboard() ${functionBinding(appSource, 'loadMainDashboard')}`, context);
  assert.strictEqual(await context.loadMainDashboard(), main);
  assert.deepEqual(requests, ['/api/v1/dashboard/main']);
  for (const invalid of [null, {}, { quality: { current: [] }, directory: { systems: [{}], channels: [] } }]) {
    next = invalid;
    await assert.rejects(context.loadMainDashboard(), /Reload the page/);
  }
  context.capabilityAllowed = () => false;
  next = { quality: { current: [] } };
  assert.strictEqual(await context.loadMainDashboard(), next,
    'A dashboard-only visitor does not require radio directory data.');

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
    beginPage: () => true, pageHeader: () => ({}), tabs: () => ({}), href: () => '',
    node: () => ({ replaceWith() {} }), content: { append() {} }, capabilityAllowed: () => true,
    api: async (path) => { dataRequests.push(path); return {}; },
    loadMainDashboard: async () => {
      loads += 1;
      if (pauseNext) { pauseNext = false; await new Promise((resolve) => { release = resolve; }); }
      if (failNext) { failNext = false; throw new Error('Temporarily unavailable'); }
      return main;
    },
    signalHealthSection: (rows) => {
      assert.strictEqual(rows, main.quality.current);
      return { element: {}, update(next) { assert.strictEqual(next, rows); qualityUpdates += 1; },
        failed() { failures += 1; } };
    },
    renderNestedRadioDirectory: async (_renderContext, directory) => {
      assert.strictEqual(directory, main.directory);
      return { update(next) { assert.strictEqual(next, directory); directoryUpdates += 1; } };
    },
    pageInterval: (refresh, interval) => { assert.equal(interval, 5_000); callback = refresh; },
    renderDashboardActivity: async () => {}
  });
  vm.runInContext(`async function renderDashboard() ${functionBinding(appSource, 'renderDashboard')}`, page);
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
  for (const tab of ['health', 'activity', 'calls']) {
    page.route = new URLSearchParams(`tab=${tab}`);
    dataRequests.length = 0;
    await page.renderDashboard();
    assert.deepEqual(dataRequests, tab === 'calls' ? ['/api/v1/dashboard'] : [],
      'Only Call Statistics loads retained call aggregates.');
  }
  console.log('Main dashboard data ownership, request bounds, recovery, and lifecycle checks passed.');
}

module.exports = { checkDashboardData };
