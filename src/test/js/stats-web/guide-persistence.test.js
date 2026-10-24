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

  const dismissed = {};
  const fetchDocument = async (_, options) => {
    if (options.method === 'PUT') Object.assign(dismissed, JSON.parse(options.body).dismissed);
    return { ok: true, status: 200, json: async () => ({ data: { version: 1, dismissed: { ...dismissed } } }) };
  };
  const blockedStorage = { getItem() { throw new Error('Blocked'); }, setItem() { throw new Error('Blocked'); } };
  const authenticated = { ...context, authenticated: true };
  const memory = createGuideMemory(blockedStorage, { definitions: [guide], fetchDocument });
  await memory.synchronize(authenticated);
  assert.equal(memory.has(authenticated, guide), false);
  memory.remember(authenticated, guide);
  await memory.retry(authenticated);
  assert.equal(memory.has(authenticated, guide), true, 'Storage denial still permits account-backed dismissal.');
  assert.equal(memory.has({ ...authenticated, dismissalScope: 'install-2' }, guide), false);
  memory.destroy();

  const unknownScope = createGuideMemory({ getItem() { assert.fail('An unknown scope must not read persistent state.'); },
    setItem() { assert.fail('An unknown scope must not write persistent state.'); } }, {
    definitions: [guide], fetchDocument() { assert.fail('An unknown or anonymous account cannot read account history.'); }
  });
  const unidentified = { account: 'alice', authenticated: true };
  assert.equal(await unknownScope.synchronize(unidentified), false);
  unknownScope.remember(unidentified, guide);
  assert.equal(unknownScope.has(unidentified, guide), false);
  assert.equal(await unknownScope.synchronize({ ...authenticated, authenticated: false }), false);
  unknownScope.destroy();
  await require('./guide-history.test.js').checkGuideHistory(assets);
  console.log('Guide keys, account/installation/version isolation, and blocked-storage account persistence checks passed.');
}
module.exports = { checkGuidePersistence };
if (require.main === module) checkGuidePersistence().catch((error) => { console.error(error); process.exitCode = 1; });
