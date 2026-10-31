const assert = require('node:assert/strict');
const test = require('node:test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const load = name => import(pathToFileURL(path.resolve('stats-web/assets', name)).href);

test('guest guide opt-in uses the guest profile and remembers completion without account requests', async () => {
  const [{ Controller }, schema, { createGuideController }] = await Promise.all([
    load('core/user-preferences.js'), load('core/preference-schema.js'), load('guides/controller.js')
  ]);
  const records = new Map();
  const storage = { getItem: key => records.get(key) ?? null, setItem: (key, value) => records.set(key, value) };
  const profile = new Controller({ defaults: schema.defaults, validate: schema.validate, storage,
    fetch: () => { throw new Error('Guest guide preferences must stay in the browser'); } });
  await profile.activate(null);
  const context = { authenticated: false, account: null, dismissalScope: null, preferenceScope: 'guest',
    view: 'live', isDesktop: true, canPresent: true, canSetup: false, canView: () => true };
  let opened;
  const guide = createGuideController({ ui: {},
    documentValue: { querySelector: () => null, querySelectorAll: () => [] },
    getContext: () => ({ ...context, automaticPageGuides: profile.read().preferences.guides.automatic_page_guides }),
    fetchDocument: () => { throw new Error('Guests must not request account tour history'); },
    guestHistory: { read: () => profile.readGuestTourHistory(), save: dismissed => profile.saveGuestTourHistory(dismissed) },
    openGuide: (_ui, definition) => { opened = definition; return { ready: Promise.resolve(true), isOpen: () => true, close() {} }; }
  });
  assert.equal(await guide.pageReady(), false, 'Guest guides default off');
  assert.equal(opened, undefined);
  await profile.update(value => { value.guides.automatic_page_guides = true; });
  assert.equal(await guide.pageReady(), true);
  assert.equal(opened.id, 'welcome');
  opened.onDismiss();
  await new Promise(resolve => setImmediate(resolve));
  const history = profile.readGuestTourHistory();
  assert.ok(history.dismissed.welcome >= 1);
  guide.destroy();
  const reopened = new Controller({ defaults: schema.defaults, validate: schema.validate, storage,
    fetch: () => { throw new Error('Guest reload must not use an account endpoint'); } });
  await reopened.activate(null);
  assert.deepEqual(reopened.readGuestTourHistory(), history);
  assert.equal(reopened.read().preferences.guides.automatic_page_guides, true);
  await reopened.resetPreferences();
  assert.equal(reopened.read().preferences.guides.automatic_page_guides, false);
  assert.deepEqual(reopened.readGuestTourHistory(), history, 'Preference reset preserves separate completion history');
});
