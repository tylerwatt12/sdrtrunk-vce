'use strict';

const assert = require('node:assert/strict');
const fs = require('fs');
const vm = require('vm');

const applicationPath = process.argv[2];
assert.ok(applicationPath, 'The app.js path is required.');
const application = fs.readFileSync(applicationPath, 'utf8');

function functionSource(signature) {
  const start = application.indexOf(signature);
  if (start < 0) throw new Error(`Missing ${signature}`);
  const openingBrace = application.indexOf('{', start + signature.length);
  let depth = 0;
  for (let index = openingBrace; index < application.length; index += 1) {
    if (application[index] === '{') depth += 1;
    else if (application[index] === '}' && --depth === 0) return application.slice(start, index + 1);
  }
  throw new Error(`Unterminated ${signature}`);
}

const responses = [];
const delays = [];
let requestCount = 0;
const context = {
  api: async () => {
    requestCount += 1;
    const response = responses.shift();
    if (response instanceof Error) throw response;
    return response;
  },
  window: {
    setTimeout: (callback, delay) => {
      delays.push(delay);
      callback();
      return delays.length;
    }
  }
};
vm.createContext(context);
vm.runInContext(`
  const SERVICE_STATUS_FAILURE_WARNING_THRESHOLD = 3;
  const SERVICE_STATUS_INITIAL_ATTEMPTS = 3;
  const SERVICE_STATUS_RETRY_DELAY_MS = 500;
  let serviceStatus = null;
  let serviceStatusRequestPending = false;
  let serviceStatusConsecutiveFailures = 0;
  ${functionSource('function beginServiceStatusRequest()')}
  ${functionSource('function acceptServiceStatus(value)')}
  ${functionSource('function rejectServiceStatusRequest()')}
  ${functionSource('function clearServiceStatus()')}
  ${functionSource('function serviceStatusWarningRequired()')}
  ${functionSource('function serviceStatusRetryDelay(milliseconds)')}
  ${functionSource('async function requestServiceStatus()')}
  globalThis.statusState = () => ({
    value: serviceStatus,
    pending: serviceStatusRequestPending,
    failures: serviceStatusConsecutiveFailures,
    warning: serviceStatusWarningRequired()
  });
  globalThis.clearStatus = clearServiceStatus;
  globalThis.acceptStatus = acceptServiceStatus;
  globalThis.beginStatus = beginServiceStatusRequest;
  globalThis.requestStatus = requestServiceStatus;
`, context);

async function main() {
  const plainState = () => JSON.parse(JSON.stringify(context.statusState()));
  assert.deepEqual(plainState(), { value: null, pending: false, failures: 0, warning: false });
  context.beginStatus();
  assert.equal(context.statusState().pending, true);
  context.clearStatus();

  const recovered = { stats_logging: { summary_active: true } };
  responses.push(new Error('starting'), recovered);
  assert.strictEqual(await context.requestStatus(), recovered);
  assert.equal(requestCount, 2);
  assert.deepEqual(delays, [500]);
  assert.deepEqual(plainState(), { value: recovered, pending: false, failures: 0, warning: false });

  context.clearStatus();
  responses.push(new Error('first'), new Error('second'), new Error('third'));
  await assert.rejects(context.requestStatus(), /third/);
  assert.deepEqual(delays, [500, 500, 1000]);
  assert.deepEqual(plainState(), { value: null, pending: false, failures: 3, warning: true });

  const cached = { stats_logging: { summary_active: true }, generation: 1 };
  context.acceptStatus(cached);
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    responses.push(new Error(`refresh ${attempt}`));
    await assert.rejects(context.requestStatus(), new RegExp(`refresh ${attempt}`));
    const state = context.statusState();
    assert.strictEqual(state.value, cached, 'A failed refresh must preserve the last confirmed status.');
    assert.equal(state.failures, attempt);
    assert.equal(state.warning, attempt >= 3);
  }

  const refreshed = { stats_logging: { summary_active: true }, generation: 2 };
  responses.push(refreshed);
  assert.strictEqual(await context.requestStatus(), refreshed);
  assert.deepEqual(plainState(), { value: refreshed, pending: false, failures: 0, warning: false });
}

main().catch((error) => {
  console.error(error?.stack || error);
  process.exitCode = 1;
});
