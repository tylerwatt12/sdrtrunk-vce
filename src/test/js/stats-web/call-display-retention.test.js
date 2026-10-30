'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const { createCallDisplayRetention } = await import(pathToFileURL(path.resolve(process.argv[2] ||
    path.resolve(__dirname, '../../../../stats-web/assets/core/call-display-retention.js'))).href);
  let now = 1_000, seconds = 5, nextId = 0, notifications = 0;
  const timers = new Map();
  const retention = createCallDisplayRetention({ now: () => now, getHangtimeSeconds: () => seconds,
    onChange: () => notifications++,
    setTimeout: (callback, delay) => { const id = ++nextId; timers.set(id, { callback, delay }); return id; },
    clearTimeout: id => timers.delete(id) });
  const first = { id: 'first' }, next = { id: 'next' };
  retention.show(first);
  assert.equal(retention.current(), first);
  assert.equal(retention.deadline, 6_000);
  now = 3_000;
  assert.equal(retention.current(), first);
  assert.equal(retention.deadline, 6_000, 'Reading another presentation must not extend the deadline.');
  seconds = 10;
  retention.refresh();
  assert.equal(retention.deadline, 11_000, 'A changed preference uses the original completion time.');
  assert.equal(timers.size, 1);
  now = 11_000;
  assert.equal(retention.current(), null, 'A throttled browser timer must not expose expired data.');
  [...timers.values()][0].callback();
  assert.equal(retention.endedAt, null);
  assert.equal(timers.size, 0);
  retention.refresh();
  assert.equal(retention.current(), null, 'Changing settings cannot resurrect expired data.');
  seconds = 0;
  retention.show(first);
  assert.equal(retention.current(), null);
  assert.equal(timers.size, 0);
  seconds = 60;
  retention.show(first);
  now += 500;
  retention.show(next);
  assert.equal(retention.current(), next);
  assert.equal(timers.size, 1, 'New completions replace the old timer.');
  retention.clear();
  assert.equal(retention.current(), null);
  assert.equal(timers.size, 0);
  assert.ok(notifications >= 5);
}
main().catch(error => { console.error(error); process.exitCode = 1; });
