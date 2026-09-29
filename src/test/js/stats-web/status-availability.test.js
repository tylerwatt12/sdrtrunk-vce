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
const grantedCapabilities = new Set(['dashboard', 'admin-settings', 'receiver-health']);
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
  },
  applicationRoutes: { dashboard: { databaseNotice: true } },
  accessSessionAvailable: true,
  ACCESS_CAPABILITIES: {
    DASHBOARD: 'dashboard', ADMIN_SETTINGS: 'admin-settings', RECEIVER_HEALTH: 'receiver-health'
  },
  capabilityAllowed: (capability) => grantedCapabilities.has(capability),
  exactDateTime: (value) => `time ${value}`,
  href: (_view, options) => `/?view=admin&tab=${options.tab}`,
  anchor: (label, target) => ({ label, target }),
  node: (tag, className, value = '') => ({
    tag, className, children: [value],
    append(...children) { this.children.push(...children); }
  })
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
  ${functionSource('function statsLoggingState()')}
  ${functionSource('function databaseLoggingNotice(view)')}
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
  globalThis.noticeFor = databaseLoggingNotice;
`, context);

async function main() {
  const plainState = () => JSON.parse(JSON.stringify(context.statusState()));
  const plainNotice = () => JSON.parse(JSON.stringify(context.noticeFor('dashboard')));
  const noticeText = (notice) => notice.children.map((child) =>
    typeof child === 'string' ? child : child.label).join('');
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

  context.acceptStatus({ stats_logging: { summary_configured: false, summary_active: false,
    state: 'DISABLED', last_successful_write_ms: 42 } });
  const disabled = plainNotice();
  assert.match(disabled.className, /ui-notice-warning/);
  assert.match(noticeText(disabled), /Saved activity summaries are off/);
  assert.doesNotMatch(noticeText(disabled), /Last saved update|not updating/);
  assert.deepEqual(disabled.children.find((child) => child?.label),
    { label: 'Call output & activity', target: '/?view=admin&tab=operations' });

  grantedCapabilities.delete('admin-settings');
  assert.match(noticeText(plainNotice()), /Ask an administrator to turn them on/);
  grantedCapabilities.add('admin-settings');

  context.acceptStatus({ stats_logging: { summary_configured: true, summary_active: false,
    state: 'STARTING', last_successful_write_ms: 42 } });
  const starting = plainNotice();
  assert.match(starting.className, /ui-notice-warning/);
  assert.match(noticeText(starting), /summaries are starting/);

  context.acceptStatus({ stats_logging: { summary_configured: true, summary_active: false,
    state: 'FAILED', last_successful_write_ms: 42 } });
  const failed = plainNotice();
  assert.match(failed.className, /ui-notice-danger/);
  assert.match(noticeText(failed), /Saving activity summaries failed/);
  assert.match(noticeText(failed), /Last saved update: time 42/);
  assert.deepEqual(failed.children.find((child) => child?.label),
    { label: 'Current status', target: '/?view=admin&tab=health' });

  context.acceptStatus({ stats_logging: { summary_configured: true, summary_active: false,
    state: 'STOPPED', last_successful_write_ms: 42 } });
  const stopped = plainNotice();
  assert.match(stopped.className, /ui-notice-warning/);
  assert.match(noticeText(stopped), /summaries are not running/);

  context.acceptStatus({ stats_logging: { summary_configured: true, summary_active: true,
    state: 'RUNNING' } });
  assert.equal(context.noticeFor('dashboard'), null);
}

main().catch((error) => {
  console.error(error?.stack || error);
  process.exitCode = 1;
});
