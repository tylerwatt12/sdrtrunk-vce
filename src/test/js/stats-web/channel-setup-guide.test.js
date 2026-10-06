'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function main() {
  const assets = path.resolve(process.argv[2] || 'stats-web/assets');
  const { hasUsableSetupTuner, createChannelSetupGuide, channelSetupGuideStorageKey } =
    await import(pathToFileURL(path.join(assets, 'features/channel-setup-guide.js')).href);
  for (const catalog of [null, {}, { tuners: null }, { tuners: [] }, { tuners: [null] },
    { tuners: [{ eligible: true, source_type: 'recording' }] },
    { tuners: [{ eligible: false, source_type: 'receiver', takeover_allowed: true }] },
    { tuners: [{ eligible: 'true', source_type: 'receiver' }] }]) {
    assert.equal(hasUsableSetupTuner(catalog), false, 'Unknown, busy, and recording tuners cannot trigger setup.');
  }
  assert.equal(hasUsableSetupTuner({ tuners: [{ eligible: true, source_type: 'receiver' }] }), true);
  assert.notEqual(channelSetupGuideStorageKey('admin', 'account-1-100'), channelSetupGuideStorageKey('operator', 'account-1-100'));
  assert.notEqual(channelSetupGuideStorageKey('admin', 'account-1-100'), channelSetupGuideStorageKey('admin', 'account-1-200'),
    'A fresh installation with the same username must have its own dismissal.');
  assert.equal(channelSetupGuideStorageKey('admin'), null, 'Unknown account scope must not read an older persistent dismissal.');

  let loads = 0;
  let current = true;
  let catalogChannels = [];
  let resolveTuners;
  const storage = { getItem: () => null };
  const config = { ui: { openReadOnlyModal: () => assert.fail('A stale or invalid state must not present a guide.') },
    account: 'async-test', dismissalScope: 'account-1-100', channels: () => catalogChannels, isCurrent: () => current,
    canPresent: () => true, storage, newChannelButton: {}, findSystemsButton: {},
    loadTuners: () => { loads++; return new Promise(resolve => { resolveTuners = resolve; }); } };
  const guide = createChannelSetupGuide(config);
  const pending = guide.refresh();
  await guide.refresh();
  assert.equal(loads, 1, 'Overlapping refreshes share one readiness check.');
  current = false;
  resolveTuners({ tuners: [{ eligible: true, source_type: 'receiver' }] });
  await pending;
  current = true;
  const second = guide.refresh();
  catalogChannels = [{ configuration_id: 'added-in-another-window' }];
  resolveTuners({ tuners: [{ eligible: true, source_type: 'receiver' }] });
  await second;
  await guide.refresh();
  assert.equal(loads, 2, 'Configured channels prevent additional tuner checks, even when filters hide them.');

  for (const channels of [undefined, null, {}, []]) {
    await createChannelSetupGuide({ ...config, channels: () => channels,
      loadTuners: async () => { throw new Error('Offline'); } }).refresh();
  }
  await createChannelSetupGuide({ ...config, channels: () => [], storage: { getItem: () => '1' },
    loadTuners: () => assert.fail('Remembered dismissal must prevent the tuner request.') }).refresh();
  const oldKey = channelSetupGuideStorageKey(config.account, config.dismissalScope);
  let freshLoads = 0;
  await createChannelSetupGuide({ ...config, channels: () => [], dismissalScope: 'account-1-200',
    storage: { getItem: key => key === oldKey || key === `sdrtrunk-vce-channel-setup-guide-dismissed:${config.account}` ? '1' : null },
    loadTuners: async () => { freshLoads++; return { tuners: [] }; } }).refresh();
  assert.equal(freshLoads, 1, 'Old-install and legacy username-only dismissals must not suppress readiness checks.');
  await createChannelSetupGuide({ ...config, channels: () => [], dismissalScope: null,
    storage: { getItem: () => assert.fail('Without a known account scope, persistent dismissal must not be consulted.') },
    loadTuners: async () => ({ tuners: [] }) }).refresh();
  await createChannelSetupGuide({ ...config, channels: () => [], findSystemsButton: null,
    loadTuners: () => assert.fail('Unavailable search access must prevent the tuner request.') }).refresh();
  await createChannelSetupGuide({ ...config, channels: () => [],
    storage: { getItem: () => { throw new Error('Storage blocked'); } },
    loadTuners: async () => ({ tuners: [] }) }).refresh();
  console.log('Channel setup guide readiness and asynchronous lifecycle checks passed.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
