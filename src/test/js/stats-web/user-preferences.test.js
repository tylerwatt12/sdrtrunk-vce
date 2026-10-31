'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const resource = path.resolve(process.argv[2] || 'stats-web/assets/core');
const core = path.extname(resource) === '.js' ? path.dirname(resource) : resource;
const modules = Promise.all(['user-preferences', 'browser-preferences', 'preference-schema']
  .map(name => import(pathToFileURL(path.join(core, `${name}.js`)).href)));
const copy = value => JSON.parse(JSON.stringify(value));
const response = (status, value) => ({ ok: status >= 200 && status < 300, status, json: async () => value });
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
function browser(records = new Map()) {
  let blocked = false;
  return { records, block(value) { blocked = value; },
    getItem(key) { return records.get(key) ?? null; },
    setItem(key, value) { if (blocked) throw new Error('Storage is unavailable'); records.set(key, value); },
    removeItem(key) { if (blocked) throw new Error('Storage is unavailable'); records.delete(key); } };
}

test('guest activation and reload use one profile without account requests or constructor events', async () => {
  const [{ Controller }, { GUEST_PROFILE_STORAGE_KEY }, schema] = await modules;
  const storage = browser();
  const changes = [];
  const options = { defaults: schema.defaults, validate: schema.validate, storage,
    fetch: () => { throw new Error('Guests must not request account preferences'); },
    onChange: value => changes.push(value) };
  const controller = new Controller(options);
  assert.equal(changes.length, 0);
  assert.equal(storage.records.size, 0);
  assert.equal(controller.snapshot().ready, false);
  const initial = await controller.activate(null);
  assert.equal(initial.scope, 'guest');
  assert.equal(initial.loaded, true);
  assert.equal(initial.persistence, 'browser');
  assert.equal(initial.preferences.guides.automatic_page_guides, false);
  const immutable = controller.read();
  assert.throws(() => { immutable.preferences.playback.volume = 0.2; }, TypeError);
  await Promise.all([
    controller.update(preferences => { preferences.playback.recording_click_mode = 'once'; }),
    controller.update(preferences => { preferences.playback.volume = 0.3; })
  ]);
  const reopened = new Controller(options);
  const restored = await reopened.activate(null);
  assert.equal(restored.preferences.playback.recording_click_mode, 'once');
  assert.equal(restored.preferences.playback.volume, 0.3);
  assert.equal(storage.records.size, 1);
  assert.equal(JSON.parse(storage.records.get(GUEST_PROFILE_STORAGE_KEY)).preferences.playback.volume, 0.3);
});

test('account loading and account changes never inherit the guest browser profile', async () => {
  const [{ Controller }, { GUEST_PROFILE_STORAGE_KEY }, schema] = await modules;
  const storage = browser();
  const queued = [];
  const requests = [];
  const controller = new Controller({ defaults: schema.defaults, validate: schema.validate, storage,
    fetch: async (_url, options) => { requests.push(options); return queued.shift(); } });
  await controller.activate(null);
  await controller.update(profile => { profile.appearance.theme = 'dark'; profile.playback.recording_click_mode = 'once'; });
  const guest = storage.records.get(GUEST_PROFILE_STORAGE_KEY);
  queued.push(response(200, { revision: 1, preferences: schema.defaults }));
  await controller.activate('["alice","account-1-100"]');
  assert.equal(controller.read().scope, 'account');
  assert.equal(controller.read().preferences.appearance.theme, 'light');
  assert.equal(controller.read().preferences.guides.automatic_page_guides, true);
  const alice = copy(schema.defaults); alice.playback.recording_click_mode = 'continue';
  queued.push(response(200, { revision: 2, preferences: alice }));
  await controller.update(profile => { profile.playback.recording_click_mode = 'continue'; });
  queued.push(response(200, { revision: 1, preferences: schema.defaults }));
  await controller.activate('["bob","account-2-101"]');
  assert.equal(controller.read().preferences.playback.recording_click_mode, 'queue');
  assert.equal(storage.records.get(GUEST_PROFILE_STORAGE_KEY), guest);
  await controller.activate(null);
  assert.equal(controller.read().preferences.playback.recording_click_mode, 'once');
  assert.equal(requests.length, 3);
});

test('blocked guest storage keeps the latest memory profile across account transitions', async () => {
  const [{ Controller }, , schema] = await modules;
  const storage = browser();
  const errors = [];
  const controller = new Controller({ defaults: schema.defaults, validate: schema.validate, storage,
    fetch: async () => response(503, {}), onError: error => errors.push(error) });
  await controller.activate(null);
  storage.block(true);
  await controller.update(profile => { profile.playback.volume = 0.2; profile.appearance.theme = 'dark'; });
  assert.equal(controller.read().persistence, 'memory');
  assert.equal(errors.length, 0, 'Guest storage failure is not an account-save failure');
  await assert.rejects(controller.activate('unavailable-account'), /load user preferences/);
  assert.equal(controller.read().scope, 'account');
  assert.equal(controller.read().loaded, false);
  assert.equal(controller.read().persistence, 'unavailable');
  assert.equal(controller.read().preferences.appearance.theme, 'light');
  assert.throws(() => controller.update(() => {}), /not loaded/);
  await controller.activate(null);
  assert.equal(controller.read().preferences.playback.volume, 0.2);
  assert.equal(controller.read().preferences.appearance.theme, 'dark');
  assert.equal(controller.read().persistence, 'memory');
  storage.block(false);
  await controller.update(profile => { profile.playback.volume = 0.4; });
  assert.equal(controller.read().persistence, 'browser');
});

test('guest reset uses guest defaults and preserves separate tour history in the same record', async () => {
  const [{ Controller }, { GUEST_PROFILE_STORAGE_KEY }, schema] = await modules;
  const storage = browser();
  const options = { defaults: schema.defaults, validate: schema.validate, storage,
    fetch: async () => response(200, { revision: 1, preferences: schema.defaults }) };
  const controller = new Controller(options);
  await controller.activate(null);
  controller.saveGuestTourHistory({ welcome: 1, live: 2 });
  controller.saveGuestTourHistory({ live: 1, recordings: 3 });
  await controller.update(profile => { profile.guides.automatic_page_guides = true; profile.appearance.hue = 120; });
  await controller.resetPreferences();
  assert.equal(controller.read().preferences.guides.automatic_page_guides, false);
  assert.equal(controller.read().preferences.appearance.hue, null);
  assert.deepEqual(controller.readGuestTourHistory(), { version: 1, dismissed: { welcome: 1, live: 2, recordings: 3 } });
  assert.equal(storage.records.size, 1);
  assert.deepEqual(JSON.parse(storage.records.get(GUEST_PROFILE_STORAGE_KEY)).tour_history, controller.readGuestTourHistory());
  assert.throws(() => controller.saveGuestTourHistory({ invalid: -1 }), /invalid/);
  await controller.activate('alice');
  assert.equal(controller.readGuestTourHistory(), null);
  assert.throws(() => controller.saveGuestTourHistory({ welcome: 2 }), error => error.code === 'preference_session_changed');
  const reopened = new Controller(options);
  await reopened.activate(null);
  assert.equal(reopened.readGuestTourHistory().dismissed.live, 2);
});

test('legacy guest layouts and anonymous Spectrum import without touching ambiguous records', async () => {
  const [{ Controller }, { GUEST_PROFILE_STORAGE_KEY }, schema] = await modules;
  const storage = browser();
  storage.records.set('sdrtrunk-vce-anonymous-table-layouts', JSON.stringify({ sample: {
    schema: ['name'], column_order: ['name'], column_widths: { name: 180 }, hidden_columns: [] } }));
  storage.records.set('sdrtrunk-vce-spectrum-display-v1:anonymous', JSON.stringify({ version: 1,
    fft_auto_range_on_retune: false, waterfall_auto_range_on_retune: true }));
  storage.records.set('sdrtrunk-vce.live-ui-state.v1', JSON.stringify({ details_panel_percent: 60 }));
  storage.records.set('sdrtrunk-vce-p25-visualizer-events-v1:anonymous', JSON.stringify({ emergency: { highlight: false } }));
  const old = new Map(storage.records);
  const controller = new Controller({ defaults: schema.defaults, validate: schema.validate, storage, fetch: async () => {
    return response(200, { revision: 1, preferences: schema.defaults });
  } });
  await controller.activate(null);
  assert.equal(controller.read().preferences.tables.sample.column_widths.name, 180);
  assert.deepEqual(controller.read().preferences.tables.sample.collapsed_groups, []);
  assert.equal(controller.read().preferences.tuner.fft_auto_range_on_retune, false);
  assert.equal(controller.read().preferences.live_workspace.details_panel_percent, 25);
  assert.equal(controller.read().preferences.p25_visualizer.events.emergency.highlight, true);
  for (const [key, value] of old) {
    if (key === 'sdrtrunk-vce-anonymous-table-layouts' || key === 'sdrtrunk-vce-spectrum-display-v1:anonymous') {
      assert.equal(storage.records.has(key), false, 'Confirmed imports remove only the obsolete guest-owned copies');
    } else assert.equal(storage.records.get(key), value, 'Ambiguous records remain untouched');
  }
  const identity = '["alice","account-1-100"]';
  const oldAccountKey = `sdrtrunk-vce-spectrum-display-v1:account:${encodeURIComponent(identity)}`;
  const oldAccountValue = JSON.stringify({ version: 1, fft_auto_range_on_retune: false, waterfall_auto_range_on_retune: false });
  storage.records.set(oldAccountKey, oldAccountValue);
  await controller.activate(identity);
  assert.equal(controller.read().preferences.tuner.fft_auto_range_on_retune, true, 'An account value must not be overwritten by an older browser choice');
  assert.equal(storage.records.get(oldAccountKey), oldAccountValue, 'Retired account browser records are preserved without assigning them to a profile');
  assert.ok(storage.records.has(GUEST_PROFILE_STORAGE_KEY));
});

test('a complete version-14 guest document upgrades to the current typed profile', async () => {
  const [{ Controller }, { GUEST_PROFILE_STORAGE_KEY }, schema] = await modules;
  const storage = browser();
  const prior = copy(schema.defaults);
  prior.version = 14;
  delete prior.live_workspace; delete prior.p25_visualizer;
  delete prior.tuner.fft_auto_range_on_retune; delete prior.tuner.waterfall_auto_range_on_retune;
  prior.appearance.theme = 'dark'; prior.playback.volume = 0.6;
  storage.records.set(GUEST_PROFILE_STORAGE_KEY, JSON.stringify({ version: 1, preferences: prior }));
  const controller = new Controller({ defaults: schema.defaults, validate: schema.validate, storage, fetch: async () => { throw new Error('No account'); } });
  await controller.activate(null);
  assert.equal(controller.read().preferences.version, schema.defaults.version);
  assert.equal(controller.read().preferences.appearance.theme, 'dark');
  assert.equal(controller.read().preferences.playback.volume, 0.6);
  assert.equal(controller.read().preferences.live_workspace.details_visibility, 'automatic');
});

test('obsolete guest copies are kept until their consolidated replacement saves successfully', async () => {
  const [{ Controller }, { GUEST_PROFILE_STORAGE_KEY }, schema] = await modules;
  const storage = browser();
  const legacy = 'sdrtrunk-vce-spectrum-display-v1:anonymous';
  storage.records.set(legacy, JSON.stringify({ version: 1, fft_auto_range_on_retune: false,
    waterfall_auto_range_on_retune: true }));
  storage.block(true);
  const controller = new Controller({ defaults: schema.defaults, validate: schema.validate, storage,
    fetch: () => { throw new Error('Guests do not use the account endpoint'); } });
  await controller.activate(null);
  assert.equal(controller.read().preferences.tuner.fft_auto_range_on_retune, false);
  assert.equal(storage.records.has(legacy), true);
  assert.equal(storage.records.has(GUEST_PROFILE_STORAGE_KEY), false);
  storage.block(false);
  await controller.update(profile => { profile.playback.volume = 0.3; });
  assert.equal(storage.records.has(legacy), false);
  assert.equal(JSON.parse(storage.records.get(GUEST_PROFILE_STORAGE_KEY)).preferences.tuner.fft_auto_range_on_retune, false);
});

test('account conflicts preserve server authority and obsolete responses cannot overwrite a guest', async () => {
  const [{ Controller }, , schema] = await modules;
  const queued = [];
  const controller = new Controller({ defaults: schema.defaults, validate: schema.validate, storage: browser(),
    fetch: async () => { const next = queued.shift(); return next?.promise || next; } });
  queued.push(response(200, { revision: 1, preferences: schema.defaults }));
  await controller.activate('alice');
  const current = copy(schema.defaults); current.appearance.theme = 'dark';
  queued.push(response(409, {}), response(200, { revision: 2, preferences: current }));
  await assert.rejects(controller.update(profile => { profile.playback.volume = 0.5; }), error => error.code === 'preference_conflict');
  assert.equal(controller.read().preferences.appearance.theme, 'dark');
  const slow = deferred(); queued.push(slow);
  const obsolete = controller.update(profile => { profile.playback.volume = 0.1; });
  await Promise.resolve();
  await controller.activate(null);
  await controller.update(profile => { profile.playback.recording_click_mode = 'once'; });
  slow.resolve(response(200, { revision: 3, preferences: current }));
  assert.equal((await obsolete).state, 'stale');
  assert.equal(controller.read().scope, 'guest');
  assert.equal(controller.read().preferences.appearance.theme, 'light');
  assert.equal(controller.read().preferences.playback.recording_click_mode, 'once');
});

test('independent guest tabs rebase field edits and merge tour history without reviving reset choices', async () => {
  const [{ Controller }, { GUEST_PROFILE_STORAGE_KEY }, schema] = await modules;
  const records = new Map();
  const create = () => new Controller({ defaults: schema.defaults, validate: schema.validate, storage: browser(records),
    fetch: async () => response(200, { revision: 1, preferences: schema.defaults }) });
  const first = create(), second = create();
  await first.activate(null); await second.activate(null);
  await first.update(profile => { profile.appearance.hue = 128; });
  await second.update(profile => { profile.playback.volume = 0.4; });
  assert.equal(second.read().preferences.appearance.hue, 128);
  first.saveGuestTourHistory({ live: 1 });
  assert.equal(first.read().preferences.playback.volume, 0.4, 'History writes rebase the profile as well');
  second.saveGuestTourHistory({ recordings: 2 });
  assert.deepEqual(first.readGuestTourHistory().dismissed, { live: 1, recordings: 2 });
  await first.resetPreferences();
  await second.update(profile => { profile.playback.volume = 0.6; });
  assert.equal(second.read().preferences.appearance.hue, null, 'A stale tab must not revive a reset color');
  const replacement = second.defaultsForScope(); replacement.appearance.theme = 'dark';
  await first.update(() => replacement);
  await second.update(profile => { profile.playback.recording_click_mode = 'once'; });
  assert.equal(second.read().preferences.playback.volume, 1, 'An explicit replacement intentionally resets the whole profile');
  assert.equal(second.read().preferences.appearance.theme, 'dark');
  const guestRecord = records.get(GUEST_PROFILE_STORAGE_KEY);
  await second.activate('alice');
  assert.equal(second.read().preferences.appearance.theme, 'light');
  assert.equal(records.get(GUEST_PROFILE_STORAGE_KEY), guestRecord, 'Account activation does not rewrite the guest profile');
  const reopened = create(); await reopened.activate(null);
  assert.deepEqual(reopened.readGuestTourHistory().dismissed, { live: 1, recordings: 2 });
});

test('unsaved guest fields survive storage failure while later edits retain other tabs changes', async () => {
  const [{ Controller }, , schema] = await modules;
  const records = new Map(), firstStorage = browser(records), secondStorage = browser(records);
  const create = storage => new Controller({ defaults: schema.defaults, validate: schema.validate, storage,
    fetch: () => { throw new Error('Guests do not use the account endpoint'); } });
  const first = create(firstStorage), second = create(secondStorage);
  await first.activate(null); await second.activate(null);
  firstStorage.block(true);
  await first.update(profile => { profile.playback.volume = 0.2; });
  await second.update(profile => { profile.appearance.hue = 210; });
  firstStorage.block(false);
  await first.update(profile => { profile.playback.target_grouping = false; });
  assert.equal(first.read().preferences.playback.volume, 0.2);
  assert.equal(first.read().preferences.appearance.hue, 210);
  assert.equal(first.read().preferences.playback.target_grouping, false);
  assert.equal(first.read().persistence, 'browser');
});

test('unsupported guest records remain untouched until an explicit guest reset', async () => {
  const [{ Controller }, { GUEST_PROFILE_STORAGE_KEY }, schema] = await modules;
  for (const record of [
    { version: 2, preferences: schema.defaults },
    { version: 1, preferences: { ...schema.defaults, version: schema.defaults.version + 1 } },
    { version: 1, preferences: { ...schema.defaults, version: 13 } }
  ]) {
    const storage = browser();
    const original = JSON.stringify(record);
    storage.records.set(GUEST_PROFILE_STORAGE_KEY, original);
    const controller = new Controller({ defaults: schema.defaults, validate: schema.validate, storage,
      fetch: () => { throw new Error('Guests do not use the account endpoint'); } });
    await controller.activate(null);
    assert.equal(controller.read().persistence, 'memory');
    assert.equal(controller.read().persistenceReason, 'unsupported');
    await controller.update(profile => { profile.playback.volume = 0.2; });
    controller.saveGuestTourHistory({ welcome: 1 });
    assert.equal(storage.records.get(GUEST_PROFILE_STORAGE_KEY), original);
    await controller.resetPreferences();
    assert.equal(controller.read().persistence, 'browser');
    assert.equal(controller.read().preferences.playback.volume, 1);
    assert.equal(controller.read().preferences.guides.automatic_page_guides, false);
    assert.notEqual(storage.records.get(GUEST_PROFILE_STORAGE_KEY), original);
    assert.equal(JSON.parse(storage.records.get(GUEST_PROFILE_STORAGE_KEY)).preferences.version, schema.defaults.version);
  }
});

test('returning to guest refreshes choices and storage status after another tab resets its profile', async () => {
  const [{ Controller }, { GUEST_PROFILE_STORAGE_KEY }, schema] = await modules;
  const storage = browser();
  storage.records.set(GUEST_PROFILE_STORAGE_KEY, JSON.stringify({ version: 2, preferences: schema.defaults }));
  const create = () => new Controller({ defaults: schema.defaults, validate: schema.validate, storage,
    fetch: async () => response(200, { revision: 1, preferences: schema.defaults }) });
  const first = create(), second = create();
  await first.activate(null); await second.activate(null);
  assert.equal(first.read().persistenceReason, 'unsupported');
  await second.resetPreferences();
  await second.update(profile => { profile.appearance.hue = 210; });
  await first.activate('sample-account');
  await first.activate(null);
  assert.equal(first.read().preferences.appearance.hue, 210);
  assert.equal(first.read().persistence, 'browser');
  assert.equal(first.read().persistenceReason, null);
});

test('unreadable browser storage is preserved while guest changes remain usable in memory', async () => {
  const [{ Controller }, { GUEST_PROFILE_STORAGE_KEY }, schema] = await modules;
  const existing = copy(schema.defaults); existing.appearance.hue = 90;
  const original = JSON.stringify({ version: 1, preferences: existing });
  let stored = original, readable = false, writes = 0;
  const storage = { getItem() { if (!readable) throw new Error('Read is unavailable'); return stored; },
    setItem(key, value) { assert.equal(key, GUEST_PROFILE_STORAGE_KEY); writes++; stored = value; } };
  const controller = new Controller({ defaults: schema.defaults, validate: schema.validate, storage,
    fetch: () => { throw new Error('No account endpoint for guests'); } });
  await controller.activate(null);
  await controller.update(profile => { profile.playback.volume = 0.2; });
  assert.equal(writes, 0, 'A readable write method does not authorize overwriting an unreadable saved record');
  assert.equal(stored, original);
  assert.equal(controller.read().persistenceReason, 'unavailable');
  readable = true;
  await controller.update(profile => { profile.scanner.detail_mode = 'advanced'; });
  assert.equal(controller.read().preferences.appearance.hue, 90);
  assert.equal(controller.read().preferences.playback.volume, 0.2);
  assert.equal(controller.read().preferences.scanner.detail_mode, 'advanced');
  assert.equal(controller.read().persistence, 'browser');
});
