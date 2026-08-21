'use strict';

const assert = require('node:assert/strict');

const modulePath = process.argv[2];
assert.ok(modulePath, 'The page lifecycle module path is required.');
const lifecycle = require(modulePath);

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

async function main() {
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
}

main().catch((error) => {
  console.error(error?.stack || error);
  process.exitCode = 1;
});
