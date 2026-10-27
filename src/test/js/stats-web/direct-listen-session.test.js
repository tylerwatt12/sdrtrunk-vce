'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const assets = path.resolve(__dirname, '../../../../stats-web/assets');
  const { createDirectListenSession, directListenTargetKey, normalizeDirectListenTarget } =
    await import(pathToFileURL(path.join(assets, 'core/direct-listen.js')).href);
  const group = (id, system = 'p25:bee00:348') => ({ kind: 'talkgroup', radio_system_key: system,
    identity_key: `v1-g-bee00-348-${id}` });
  const channel = { kind: 'channel', key: '00000000-0000-0000-0000-000000000001',
    identity_key: 'v1-g-x-x-40', timeslot: 2 };
  const stored = new Map();
  const storage = { getItem: key => stored.get(key), setItem: (key, value) => stored.set(key, value),
    removeItem: key => stored.delete(key) };
  const session = createDirectListenSession({ storage });
  session.setIdentity('listener-one');
  const first = session.add(group(40), 'Fire Dispatch', 'County');
  assert.equal(first.added, true);
  assert.equal(session.add(group(40), 'Fire Dispatch', 'County').added, false);
  session.add(channel, 'Repeater · Timeslot 2', 'Local');
  assert.equal(session.snapshot().targets.length, 2);
  assert.equal(session.snapshot().selected, true);
  const refresh = createDirectListenSession({ storage });
  refresh.setIdentity('listener-one');
  assert.deepEqual(refresh.snapshot(), session.snapshot(), 'Refresh retains this tab’s membership and selection.');
  refresh.select(false);
  const unchecked = createDirectListenSession({ storage });
  unchecked.setIdentity('listener-one');
  assert.equal(unchecked.snapshot().selected, false);
  assert.equal(unchecked.snapshot().targets.length, 2);
  unchecked.setIdentity('listener-two');
  assert.deepEqual(unchecked.snapshot().targets, [], 'Changing access identity clears the previous listener’s session.');
  assert.equal(stored.size, 0);
  unchecked.add(group(50), 'Dispatch');
  unchecked.setIdentity(null);
  assert.equal(unchecked.snapshot().targets.length, 0, 'Logout clears saved membership.');
  assert.equal(directListenTargetKey({ identity_key: group(40).identity_key, kind: 'talkgroup',
    radio_system_key: group(40).radio_system_key }), first.key, 'Property input order cannot change identity.');
  assert.notEqual(directListenTargetKey(group(40)), directListenTargetKey(group(40, 'p25:bee00:349')));
  for (const invalid of [null, {}, { ...group(40), kind: 'radio' }, { ...group(40), identity_key: 'v1-r-x-x-40' },
    { ...group(40), radio_system_key: 'County' }, { ...channel, timeslot: 0 }, { ...channel, timeslot: '2' },
    { ...channel, identity_key: 'v1-p-x-x-40' }, { ...group(40), label: 'unexpected' }, { ...group(40), identity_key: ' v1-g-bee00-348-40' },
    { ...channel, key: channel.key + ' ' }]) {
    assert.equal(normalizeDirectListenTarget(invalid), null);
  }
  for (let id = 1; id <= 32; id++) unchecked.add(group(id), `Group ${id}`);
  assert.throws(() => unchecked.add(group(33)), /up to 32/);
  assert.equal(unchecked.add(group(1)).added, false, 'Duplicate Add remains valid at capacity.');
  assert.equal(unchecked.remove(directListenTargetKey(group(1))), true);
  unchecked.add(group(33));
  const unavailableStorage = createDirectListenSession({ storage: {
    getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); }, removeItem() { throw new Error('blocked'); }
  } });
  unavailableStorage.setIdentity('listener');
  unavailableStorage.add(group(1));
  assert.equal(unavailableStorage.snapshot().targets.length, 1);

}

main().catch(error => { console.error(error); process.exitCode = 1; });
