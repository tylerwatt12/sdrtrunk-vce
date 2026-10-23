'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function checkGuidePersistence(assetsRoot) {
  const assets = path.resolve(assetsRoot || process.argv[2] || 'stats-web/assets');
  const { guideStorageKey, createGuideMemory } = await import(pathToFileURL(path.join(assets, 'guides/persistence.js')).href);
  const guide = { id: 'welcome', version: 1 };
  const context = { account: 'alice', dismissalScope: 'install-1' };
  assert.notEqual(guideStorageKey('alice', 'install-1', 'welcome'), guideStorageKey('bob', 'install-1', 'welcome'));
  assert.notEqual(guideStorageKey('alice', 'install-1', 'welcome'), guideStorageKey('alice', 'install-2', 'welcome'));
  assert.notEqual(guideStorageKey('alice', 'install-1', 'welcome', 1), guideStorageKey('alice', 'install-1', 'welcome', 2));
  assert.notEqual(guideStorageKey('alice', 'install-1', 'welcome'), guideStorageKey('alice', 'install-1', 'channel-setup'));
  assert.equal(guideStorageKey('alice', null, 'welcome'), null);
  assert.equal(guideStorageKey('alice', '  ', 'welcome'), null);

  const saved = new Map();
  const storage = { getItem: (key) => saved.get(key), setItem: (key, value) => saved.set(key, value) };
  const persistent = createGuideMemory(storage);
  assert.equal(persistent.has(context, guide), false);
  persistent.remember(context, guide);
  assert.equal(createGuideMemory(storage).has(context, guide), true, 'The dismissal survives a fresh browser session.');
  assert.equal(createGuideMemory(storage).has({ ...context, account: 'bob' }, guide), false);
  assert.equal(createGuideMemory(storage).has({ ...context, dismissalScope: 'install-2' }, guide), false);
  assert.equal(createGuideMemory(storage).has(context, { ...guide, version: 2 }), false);

  const blockedStorage = { getItem() { throw new Error('Blocked'); }, setItem() { throw new Error('Blocked'); } };
  const memory = createGuideMemory(blockedStorage);
  assert.equal(memory.has(context, guide), false);
  memory.remember(context, guide);
  assert.equal(memory.has(context, guide), true, 'Storage denial keeps a usable in-memory dismissal.');
  assert.equal(memory.has({ ...context, dismissalScope: 'install-2' }, guide), false);
  const unknownScope = createGuideMemory({ getItem() { assert.fail('An unknown scope must not read persistent state.'); },
    setItem() { assert.fail('An unknown scope must not write persistent state.'); } });
  assert.equal(unknownScope.has({ account: 'alice' }, guide), false);
  unknownScope.remember({ account: 'alice' }, guide);
  assert.equal(unknownScope.has({ account: 'alice' }, guide), true);
  assert.equal(unknownScope.has({ account: 'bob' }, guide), false);
  console.log('Guide persistence, account/installation/version isolation, and blocked-storage fallback checks passed.');
}
module.exports = { checkGuidePersistence };
if (require.main === module) checkGuidePersistence().catch((error) => { console.error(error); process.exitCode = 1; });
