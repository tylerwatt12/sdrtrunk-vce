'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function checkGuides(assetsRoot) {
  const assets = path.resolve(assetsRoot || process.argv[2] || 'stats-web/assets');
  const load = (relative) => import(pathToFileURL(path.join(assets, relative)).href);
  const [{ createGuideController }, { createGuideDefinition, pageGuideTemplates },
    { definitions }] = await Promise.all([
    load('guides/controller.js'), load('guides/catalog.js'), load('core/routes.js')
  ]);
  assert.deepEqual(Object.keys(pageGuideTemplates).sort(), definitions.map(({ id }) => id).sort(),
    'Every application route has a central guide template.');
  for (const { id } of definitions) {
    const guide = createGuideDefinition(id);
    assert.ok(guide.steps.length, 'Every Help button can show an authored guide or a useful placeholder.');
    assert.equal(guide.authored, ['channel-setup', 'aliases'].includes(id));
  }
  assert.equal(createGuideDefinition('welcome').steps.length, 11);
  assert.equal(createGuideDefinition('missing-page'), null);
  const prepared = [];
  const released = [];
  const aliasesTour = createGuideDefinition('aliases', {
    preparePlayer: (options) => { prepared.push(['player', options]); return () => released.push('player'); },
    preparePage: async (view, section, context) => {
      prepared.push([view, section, context]);
      return () => released.push(section);
    }
  });
  const aliasSections = ['lists', 'views', 'alias-editor', 'defaults-unmatched', 'defaults-new',
    'bulk', 'scan-lists', 'activity', 'custom', 'discover', 'transfer'];
  assert.deepEqual(aliasesTour.steps.map(({ id }) => id), aliasSections,
    'The Aliases tour introduces all views before editors, defaults, bulk tools, and imports.');
  assert.equal(new Set(aliasesTour.steps.map(({ id }) => id)).size, aliasesTour.steps.length,
    'Each Aliases stop has a stable distinct identity for progression and cleanup.');
  for (const step of aliasesTour.steps) {
    assert.deepEqual(step.samples, [{ id: 'aliases-workspace', value: { section: step.id } }],
      'Every Aliases stop identifies its temporary workspace state centrally.');
    assert.doesNotThrow(() => step.targets(), 'A sample target can resolve safely before its page has rendered.');
    const context = { registerLayer() {} };
    const release = await step.prepare(context);
    assert.deepEqual(prepared.splice(0), [['player', { minimized: true }], ['aliases', step.id, context]],
      'The real page prepares each sample presentation with the guide layer context.');
    release();
    assert.deepEqual(released.splice(0), [step.id, 'player'],
      'The actual workspace closes its dialogs and popovers before restoring the player.');
  }
  assert.deepEqual(aliasesTour.steps.find(({ id }) => id === 'views').items.map(({ label }) => label),
    ['Configure', 'Activity', 'Custom', 'Discover']);
  const editor = aliasesTour.steps.find(({ id }) => id === 'alias-editor');
  assert.equal(editor.paragraphs.length, 0, 'Alias editor guidance is separated by tab rather than a dense paragraph.');
  assert.deepEqual(editor.items.map(({ label }) => label), ['Basics', 'Identifier', 'Call Handling', 'Activity']);
  assert.match(editor.items[1].text, /talkgroup ID or radio ID/);
  assert.equal(aliasesTour.steps.at(-1).nextLabel, 'Finish');
  const makeHarness = (overrides = {}) => {
    const stored = new Map();
    const shown = [];
    const navigations = [];
    const context = { view: 'channel-setup', account: 'alice', dismissalScope: 'install-1',
      authenticated: true, isDesktop: true, canPresent: true, canSetup: true, ...overrides.context };
    const openGuide = (_, options) => {
      const presentation = { options, closed: false, ready: overrides.ready?.() ?? Promise.resolve(true),
        isOpen() { return !this.closed; }, close() { this.closed = true; } };
      shown.push(presentation);
      return presentation;
    };
    const controller = createGuideController({ ui: {}, getContext: () => context, openGuide,
      storage: overrides.storage || { getItem: (key) => stored.get(key), setItem: (key, value) => stored.set(key, value) },
      navigate: (view) => { navigations.push(view); context.view = view; },
      consumeGuide: () => { context.guide = null; }, preparePage: overrides.preparePage, documentValue: null });
    const finish = () => { shown.at(-1).closed = true; shown.at(-1).options.onComplete(); };
    const dismiss = () => { shown.at(-1).closed = true; shown.at(-1).options.onDismiss(); };
    return { controller, context, shown, stored, navigations, finish, dismiss };
  };

  const pagePreparation = [];
  const pagePrepared = makeHarness({ context: { view: 'aliases' },
    preparePage: (...args) => { pagePreparation.push(args); } });
  await pagePrepared.controller.openPage();
  await pagePrepared.shown.at(-1).options.steps[0].prepare({ registerLayer() {} });
  assert.equal(pagePreparation[0][0], 'aliases', 'The controller forwards page preparation to authored guides.');
  assert.equal(pagePreparation[0][1], 'lists');
  pagePrepared.controller.destroy();

  const firstRun = makeHarness();
  await firstRun.controller.pageReady();
  assert.equal(firstRun.shown.at(-1).options.id, 'welcome', 'General welcome precedes the page tour.');
  await firstRun.controller.pageReady();
  assert.equal(firstRun.shown.length, 1, 'Repeated successful renders do not stack tours.');
  firstRun.finish();
  await Promise.resolve();
  assert.equal(firstRun.shown.at(-1).options.id, 'channel-setup', 'Completing welcome hands off to Channel Setup.');
  firstRun.finish();
  await firstRun.controller.pageReady();
  assert.equal(firstRun.shown.length, 2, 'Completed guides do not reopen automatically.');
  await firstRun.controller.openPage();
  assert.equal(firstRun.shown.length, 3, 'Help replays a completed page guide.');
  firstRun.dismiss();
  firstRun.context.guide = 'welcome';
  await firstRun.controller.pageReady();
  assert.equal(firstRun.shown.at(-1).options.id, 'welcome', 'The test URL bypasses remembered completion.');
  assert.equal(firstRun.context.guide, null, 'The explicit replay parameter is consumed once.');
  firstRun.finish();
  await Promise.resolve();
  assert.equal(firstRun.shown.at(-1).options.id, 'channel-setup', 'A welcome replay includes its page-guide handoff.');

  const dismissedWelcome = makeHarness();
  await dismissedWelcome.controller.pageReady();
  dismissedWelcome.dismiss();
  await dismissedWelcome.controller.pageReady();
  assert.equal(dismissedWelcome.shown.length, 1, 'Dismissing welcome does not immediately launch another tour.');
  dismissedWelcome.context.view = 'dashboard';
  await dismissedWelcome.controller.pageReady();
  assert.equal(dismissedWelcome.shown.length, 1, 'Blank page templates do not interrupt users automatically.');
  await dismissedWelcome.controller.openPage();
  assert.equal(dismissedWelcome.shown.at(-1).options.steps[0].title, 'Guide coming soon');
  dismissedWelcome.finish();
  await Promise.resolve();
  assert.equal(dismissedWelcome.shown.at(-1).options.id, 'welcome', 'A placeholder offers a working welcome-tour action.');
  dismissedWelcome.controller.close();
  dismissedWelcome.context.view = 'channel-setup';
  await dismissedWelcome.controller.pageReady();
  assert.equal(dismissedWelcome.shown.at(-1).options.id, 'channel-setup', 'The page guide remains available on the next visit.');
  dismissedWelcome.finish();
  dismissedWelcome.context.view = 'aliases';
  await dismissedWelcome.controller.pageReady();
  assert.equal(dismissedWelcome.shown.at(-1).options.id, 'aliases',
    'The authored Aliases tour opens automatically on its first visit after welcome.');
  dismissedWelcome.finish();
  const afterAliasesCompletion = dismissedWelcome.shown.length;
  await dismissedWelcome.controller.pageReady();
  assert.equal(dismissedWelcome.shown.length, afterAliasesCompletion,
    'Finishing the Aliases tour remembers completion without reopening another guide.');
  dismissedWelcome.context.view = 'dashboard';
  await dismissedWelcome.controller.pageReady();
  dismissedWelcome.context.view = 'aliases';
  await dismissedWelcome.controller.pageReady();
  assert.equal(dismissedWelcome.shown.length, afterAliasesCompletion,
    'A later Aliases visit keeps its completed tour closed.');
  await dismissedWelcome.controller.openPage();
  assert.equal(dismissedWelcome.shown.at(-1).options.id, 'aliases', 'Help replays the Aliases tour after completion.');
  dismissedWelcome.dismiss();

  const dismissedPlaceholder = makeHarness({ context: { view: 'aliases' }, storage: {
    getItem: key => /:(welcome|aliases):1$/.test(key) ? '1' : null,
    setItem() {}
  } });
  await dismissedPlaceholder.controller.pageReady();
  assert.equal(dismissedPlaceholder.shown.at(-1).options.id, 'aliases');
  assert.equal(dismissedPlaceholder.shown.at(-1).options.version, 3,
    'Dismissing the old coming-soon placeholder cannot suppress the new authored tour.');

  const isolation = makeHarness();
  await isolation.controller.pageReady();
  isolation.dismiss();
  isolation.context.account = 'bob';
  await isolation.controller.pageReady();
  assert.equal(isolation.shown.length, 2, 'A different account receives its own welcome.');
  isolation.dismiss();
  isolation.context.dismissalScope = 'install-2';
  await isolation.controller.pageReady();
  assert.equal(isolation.shown.length, 3, 'A new receiver scope receives its own welcome.');

  let resolveReady;
  const deferred = makeHarness({ ready: () => new Promise((resolve) => { resolveReady = resolve; }) });
  const pending = deferred.controller.pageReady();
  await deferred.controller.pageReady();
  assert.equal(deferred.shown.length, 1, 'A deferred modal does not create duplicate presentations.');
  deferred.context.account = 'bob';
  resolveReady(true);
  assert.equal(await pending, false);
  assert.equal(deferred.shown[0].closed, true, 'A modal resolving after the account changes is closed.');
  assert.equal(deferred.stored.size, 0, 'A stale presentation cannot dismiss the new account’s guide.');
  const routeDeferred = makeHarness({ ready: () => new Promise((resolve) => { resolveReady = resolve; }) });
  const routePending = routeDeferred.controller.pageReady();
  routeDeferred.context.view = 'settings';
  resolveReady(true);
  assert.equal(await routePending, false);
  assert.equal(routeDeferred.shown[0].closed, true, 'A modal resolving after navigation is closed.');

  for (const context of [{ authenticated: false }, { isDesktop: false }]) {
    const restricted = makeHarness({ context });
    assert.equal(await restricted.controller.pageReady(), false);
    assert.equal(restricted.shown.length, 0, 'Only authenticated desktop sessions receive automatic guides.');
  }
  const busy = makeHarness({ context: { canPresent: false, guide: 'welcome' } });
  assert.equal(await busy.controller.pageReady(), false);
  assert.equal(busy.context.guide, 'welcome', 'A busy dialog does not lose the requested replay.');
  busy.context.canPresent = true;
  await busy.controller.pageReady();
  assert.equal(busy.shown.length, 1);
  assert.equal(busy.context.guide, null);
  busy.controller.destroy();
  assert.equal(busy.shown[0].closed, true);
  assert.equal(await busy.controller.openWelcome(), false, 'Destroy prevents deferred or future presentations.');

  const replaced = makeHarness();
  await replaced.controller.pageReady();
  replaced.shown[0].close();
  replaced.context.canPresent = false;
  assert.equal(await replaced.controller.openPage(), false,
    'An externally replaced tour cannot bypass a busy or dirty modal with a stale ownership record.');
  assert.equal(replaced.shown.length, 1);
  replaced.context.canPresent = true;
  assert.equal(await replaced.controller.openPage(), true, 'Help works again after the other modal closes.');

  const replayPage = makeHarness({ context: { view: 'dashboard', guide: 'channel-setup' } });
  await replayPage.controller.pageReady();
  assert.deepEqual(replayPage.navigations, ['channel-setup']);
  await replayPage.controller.pageReady();
  assert.equal(replayPage.shown.at(-1).options.id, 'channel-setup', 'A named page URL navigates before replaying its guide.');
  const replayAliases = makeHarness({ context: { view: 'dashboard', guide: 'aliases' } });
  await replayAliases.controller.pageReady();
  assert.deepEqual(replayAliases.navigations, ['aliases']);
  await replayAliases.controller.pageReady();
  assert.equal(replayAliases.shown.at(-1).options.id, 'aliases', 'A named Aliases URL navigates before showing its sample tour.');
  const restrictedWelcome = makeHarness({ context: { canSetup: false } });
  await restrictedWelcome.controller.pageReady();
  assert.equal(restrictedWelcome.shown[0].options.steps.at(-1).nextLabel, 'Finish');
  restrictedWelcome.finish();
  assert.equal(restrictedWelcome.shown.length, 1, 'A listener is not sent to channel administration.');

  const listenerViews = new Set(['dashboard', 'live', 'scanner', 'settings', 'credits']);
  const listener = createGuideDefinition('welcome', { canView: (view) => listenerViews.has(view),
    canReport: false, canSetup: false });
  const listenerSteps = listener.steps.map(({ id }) => id);
  for (const unavailable of ['hardware', 'manage', 'scan-lists', 'report-issue']) {
    assert.equal(listenerSteps.includes(unavailable), false, `Listeners are not directed to unavailable ${unavailable} controls.`);
  }
  assert.equal(listener.steps.at(-1).nextLabel, 'Finish');
  assert.equal(listener.steps.find(({ id }) => id === 'settings').items.some(({ label }) => label === 'Administration'), false);
  assert.deepEqual(listener.steps.find(({ id }) => id === 'listening').items.map(({ label }) => label), ['Main', 'Scanner']);
  const listenerPlayer = listener.steps.find(({ id }) => id === 'player');
  assert.equal(listenerPlayer.items[0].label, 'Live');
  assert.ok(listenerPlayer.items.some(({ label }) => label === 'Lists'), 'Listeners can still choose available Scan Lists in the player.');
  const noAudio = createGuideDefinition('welcome', { canView: (view) => view === 'dashboard', canReport: false, canSetup: false });
  assert.equal(noAudio.steps.some(({ id }) => id === 'player'), false, 'Accounts without audio access do not receive a player step.');
  const denied = makeHarness({ context: { canView: () => false } });
  assert.equal(await denied.controller.pageReady(), false, 'An inaccessible route does not start automatic onboarding.');
  assert.equal(denied.shown.length, 0);
  const deniedReplay = makeHarness({ context: { view: 'dashboard', guide: 'channel-setup', canView: (view) => view === 'dashboard' } });
  await deniedReplay.controller.pageReady();
  assert.deepEqual(deniedReplay.navigations, [], 'A replay request cannot navigate a listener to an inaccessible page.');

  const hiddenControls = new Set(['tuners', 'tuner-spectrum', 'channel-setup', 'aliases', 'radioreference',
    'streaming', 'scan-lists', 'alerts', 'admin']);
  const domFiltered = createGuideDefinition('welcome', { canSetup: false, documentValue: {
    querySelector(selector) {
      const view = /data-view="([^"]+)"/.exec(selector)?.[1];
      return { hidden: hiddenControls.has(view) || selector.includes('report-issue'), closest: () => null };
    }
  } });
  assert.equal(domFiltered.steps.some(({ id }) => id === 'hardware'), false);
  assert.ok(domFiltered.steps.some(({ id }) => id === 'live'), 'A closed but available menu is retained without measuring client rects.');

  console.log('Guide registry, first-run order, manual replay, account scope, and deferred lifecycle checks passed.');
}
module.exports = { checkGuides };
if (require.main === module) checkGuides().catch((error) => { console.error(error); process.exitCode = 1; });
