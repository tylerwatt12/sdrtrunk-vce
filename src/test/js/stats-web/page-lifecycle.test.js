'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const modulePath = process.argv[2];
assert.ok(modulePath, 'The page lifecycle module path is required.');
let lifecycle = null;

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolveValue, rejectValue) => {
    resolve = resolveValue;
    reject = rejectValue;
  });
  return { promise, resolve, reject };
}

function assertInvalid(action, path = '/api/test') {
  assert.throws(action, (error) => error?.code === 'invalid_response' && error?.path === path);
}

async function verifyDetailPageOrientation() {
  const app = fs.readFileSync(path.resolve(path.dirname(modulePath), '../app.js'), 'utf8');
  const start = app.indexOf('function pendingDetailPageTitle(');
  const end = app.indexOf("\ndocument.addEventListener('click'", start);
  assert.ok(start >= 0 && end > start, 'The application render lifecycle is available.');
  const node = (_tag, className, textContent) => ({
    className, textContent, setAttribute() {}
  });
  const content = {
    children: [],
    setAttribute() {},
    replaceChildren(...children) { this.children = children; },
    querySelector() { return this.children.find((child) => child.className === 'page-header'); }
  };
  const context = {
    URLSearchParams, AbortController, node, content,
    route: new URLSearchParams('view=radio'), activeRenderEpoch: 0,
    activeRenderController: null, document: { body: { dataset: {} } },
    ACCESS_CAPABILITIES: { RECORDINGS: 'recordings' },
    recordingsFeature: { playback: { reset() {} } }, audioDock: { synchronize() {} },
    setNavigationOpen() {}, capabilityAllowed: () => true,
    closeReadOnlyModal: () => true, restorePlaybackBarBeforeRender() {},
    closePageConnections() {}, synchronizeAccessLanding: () => false,
    clearAliasSelectionOutsideEditor() {}, activateNavigation() {},
    clearInactiveAliasSelection() {}, databaseLoggingNotice: () => null,
    pageHeader: (title) => node('h1', 'page-header', title),
    pageTitleController: { update(values) { context.pageTitle = values.pageTitle; } },
    applicationRoutes: Object.fromEntries([
      ['radio-system', 'Radio System Details'], ['group-identity', 'Group Identity Details'],
      ['radio', 'Radio Details'], ['channel', 'Channel Details'], ['aliases', 'Aliases']
    ].map(([id, title]) => [id, { id, title, allowed: () => true }])),
    routeFoundation: {
      requestedView: (parameters) => parameters.get('view') || 'dashboard',
      resolve: (registry, parameters) => registry[parameters.get('view')]
    },
    renderIsCurrent: (renderContext) => renderContext.epoch === context.activeRenderEpoch &&
      !renderContext.signal.aborted,
    beginPage(renderContext, ...children) {
      if (!context.renderIsCurrent(renderContext)) return false;
      content.replaceChildren(...children);
      const header = content.querySelector();
      if (header) context.pageTitle = header.textContent;
      return true;
    }
  };
  vm.createContext(context);
  vm.runInContext(app.slice(start, end), context);
  const heading = () => content.querySelector()?.textContent;
  for (const view of ['radio-system', 'group-identity', 'radio', 'channel']) {
    assert.equal(context.pendingDetailPageTitle(view), context.applicationRoutes[view].title);
  }
  assert.equal(context.pendingDetailPageTitle('aliases', new URLSearchParams('list=1')),
    'Alias List Details');
  assert.equal(context.pendingDetailPageTitle('aliases', new URLSearchParams('scanListId=2')),
    'Scan List Members');
  assert.equal(context.pendingDetailPageTitle('aliases', new URLSearchParams()), '');
  assert.equal(context.pendingDetailPageTitle('not-found'), '');

  const loading = deferred();
  context.applicationRoutes.radio.handler = () => loading.promise;
  const firstRender = context.render();
  assert.equal(heading(), 'Radio Details', 'A detail route retains its type while loading.');
  assert.equal(context.pageTitle, 'Radio Details');
  assert.equal(content.children[1].className, 'loading');
  loading.reject(new Error('The radio could not be loaded.'));
  await firstRender;
  assert.equal(heading(), 'Radio Details', 'A failed detail request retains its type heading.');
  assert.equal(content.children[1].className, 'error');

  context.applicationRoutes.radio.handler = async () => {
    context.beginPage({ epoch: context.activeRenderEpoch, signal: context.activeRenderController.signal },
      context.pageHeader('Radio: Engine 4'));
    throw new Error('Radio activity could not be loaded.');
  };
  await context.render();
  assert.equal(heading(), 'Radio: Engine 4', 'Later failure retains an already loaded entity heading.');

  const obsolete = deferred();
  const current = deferred();
  context.applicationRoutes.radio.handler = () => obsolete.promise;
  const obsoleteRender = context.render();
  context.route = new URLSearchParams('view=channel');
  context.applicationRoutes.channel.handler = () => current.promise;
  const currentRender = context.render();
  obsolete.reject(new Error('Obsolete radio failure.'));
  await obsoleteRender;
  assert.equal(heading(), 'Channel Details', 'A stale failure cannot replace the active route heading.');
  assert.equal(content.children[1].className, 'loading');
  current.reject(new Error('The channel could not be loaded.'));
  await currentRender;
  assert.equal(heading(), 'Channel Details');
}

async function main() {
  const source = fs.readFileSync(modulePath, 'utf8');
  lifecycle = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
  const row = { id: 1 };
  const collectionResponse = { rows: [row], range: '24h' };
  const collection = lifecycle.decodeCollection(collectionResponse, '/api/test');
  assert.notStrictEqual(collection, collectionResponse);
  assert.notStrictEqual(collection.rows, collectionResponse.rows);
  assert.equal(collection.range, '24h');
  assertInvalid(() => lifecycle.decodeCollection({ rows: 'invalid' }, '/api/test'));

  const response = {
    rows: [row], limit: 25, offset: 0, has_more: true, next_offset: 25,
    total_count: 30, site_preview_limit_per_system: 25
  };
  const page = lifecycle.decodeOffsetPage(response, '/api/test');
  assert.notStrictEqual(page, response);
  assert.notStrictEqual(page.rows, response.rows);
  assert.strictEqual(page.rows[0], row);
  assert.equal(page.total_count, 30);
  assert.equal(page.site_preview_limit_per_system, 25);

  const empty = lifecycle.decodeOffsetPage({
    rows: [], limit: 25, offset: 0, has_more: false, next_offset: null
  }, '/api/test');
  assert.deepEqual(empty.rows, []);

  [
    null,
    {},
    { rows: 'invalid', limit: 1, offset: 0, has_more: false, next_offset: null },
    { rows: [], limit: '1', offset: 0, has_more: false, next_offset: null },
    { rows: [], limit: 0, offset: 0, has_more: false, next_offset: null },
    { rows: [], limit: 1, offset: -1, has_more: false, next_offset: null },
    { rows: [], limit: 1, offset: 0, has_more: 0, next_offset: null },
    { rows: [{}, {}], limit: 1, offset: 0, has_more: false, next_offset: null },
    { rows: [], limit: 1, offset: 0, has_more: true, next_offset: null },
    { rows: [], limit: 1, offset: 5, has_more: true, next_offset: 5 },
    { rows: [], limit: 1, offset: 0, has_more: false, next_offset: 1 },
    { rows: [], limit: 1, offset: 0, has_more: false, next_offset: null, total_count: 0.5 }
  ].forEach((value) => assertInvalid(() => lifecycle.decodeOffsetPage(value, '/api/test')));

  const firstLoad = deferred();
  const successEvents = [];
  const success = lifecycle.run({
    onLoading: () => successEvents.push('loading'),
    load: () => {
      successEvents.push('load');
      return firstLoad.promise;
    },
    onReady: (value) => successEvents.push(`ready:${value}`),
    onError: () => successEvents.push('error')
  });
  assert.deepEqual(successEvents, ['loading', 'load']);
  firstLoad.resolve('page');
  assert.equal((await success).state, 'ready');
  assert.deepEqual(successEvents, ['loading', 'load', 'ready:page']);

  const loads = [Promise.reject(new Error('temporary')), deferred(), deferred()];
  let loadIndex = 0;
  let retry;
  const retryFlags = [];
  const readyValues = [];
  const initialFailure = await lifecycle.run({
    onLoading: ({ retry: isRetry }) => retryFlags.push(isRetry),
    load: () => {
      const value = loads[loadIndex++];
      return value?.promise || value;
    },
    onReady: (value) => readyValues.push(value),
    onError: (error, retryAction) => {
      assert.equal(error.message, 'temporary');
      retry = retryAction;
    }
  });
  assert.equal(initialFailure.state, 'error');
  assert.equal(typeof retry, 'function');
  const olderRetry = retry();
  const currentRetry = retry();
  loads[1].resolve('old');
  loads[2].resolve('new');
  assert.equal((await olderRetry).state, 'stale');
  assert.equal((await currentRetry).state, 'ready');
  assert.deepEqual(readyValues, ['new']);
  assert.deepEqual(retryFlags, [false, true, true]);

  let current = true;
  const staleLoad = deferred();
  let staleCommitted = false;
  const stale = lifecycle.run({
    isCurrent: () => current,
    load: () => staleLoad.promise,
    onReady: () => { staleCommitted = true; },
    onError: () => { staleCommitted = true; }
  });
  current = false;
  staleLoad.resolve('ignored');
  assert.equal((await stale).state, 'stale');
  assert.equal(staleCommitted, false);

  current = true;
  const staleFailureLoad = deferred();
  const staleAuthError = Object.assign(new Error('obsolete session'), { status: 401 });
  const staleFailure = lifecycle.run({
    isCurrent: () => current,
    load: () => staleFailureLoad.promise,
    onError: () => { staleCommitted = true; }
  });
  current = false;
  staleFailureLoad.reject(staleAuthError);
  const staleFailureResult = await staleFailure;
  assert.equal(staleFailureResult.state, 'stale');
  assert.strictEqual(staleFailureResult.error, staleAuthError);
  assert.equal(staleCommitted, false);

  let skippedLoad = false;
  const alreadyStale = await lifecycle.run({
    isCurrent: () => false,
    load: () => { skippedLoad = true; }
  });
  assert.equal(alreadyStale.state, 'stale');
  assert.equal(skippedLoad, false);

  for (const error of [
    Object.assign(new Error('aborted'), { name: 'AbortError' }),
    Object.assign(new Error('sign in'), { status: 401 }),
    Object.assign(new Error('forbidden'), { status: 403 })
  ]) {
    let errorCommitted = false;
    await assert.rejects(lifecycle.run({
      load: async () => { throw error; },
      onError: () => { errorCommitted = true; }
    }), (caught) => caught === error);
    assert.equal(errorCommitted, false);
  }
  await verifyDetailPageOrientation();
}

main().catch((error) => {
  console.error(error?.stack || error);
  process.exitCode = 1;
});
