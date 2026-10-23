'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function checkGuides(assetsRoot) {
  const assets = path.resolve(assetsRoot || process.argv[2] || 'stats-web/assets');
  const load = (relative) => import(pathToFileURL(path.join(assets, relative)).href);
  const [{ createGuideController }, { createGuideDefinition, pageGuideTemplates, pageGuideOverviews },
    { definitions }] = await Promise.all([
    load('guides/controller.js'), load('guides/catalog.js'), load('core/routes.js')
  ]);
  assert.deepEqual(Object.keys(pageGuideTemplates).sort(), definitions.map(({ id }) => id).sort(),
    'Every application route has a central guide template.');
  assert.deepEqual(Object.keys(pageGuideOverviews).sort(), definitions.map(({ id }) => id).sort(),
    'Every page has centrally authored concept copy before its numbered walkthrough.');
  for (const { id } of definitions) {
    const guide = createGuideDefinition(id);
    assert.ok(guide.steps.length, 'Every Help button can show an authored guide or a useful placeholder.');
    assert.equal(guide.authored, ['channel-setup', 'aliases', 'scan-lists'].includes(id));
    const overview = guide.steps[0];
    assert.equal(overview.id, 'overview');
    assert.equal(overview.overview, true);
    assert.ok(overview.title && overview.paragraphs.length, 'Each overview explains its page’s purpose.');
    assert.equal(overview.samples, undefined, 'An overview leaves real page data in place.');
    assert.equal(overview.prepare, undefined, 'An overview opens no menus, dialogs, or player panels.');
    assert.equal(overview.targets, undefined, 'An overview is a centered concept introduction.');
    assert.equal(guide.steps.filter(step => step.overview).length, 1);
    if (!guide.authored) {
      assert.equal(overview.nextLabel, 'Continue', 'Pending walkthroughs keep their honest welcome-tour invitation.');
      assert.equal(guide.steps[1].id, 'coming-soon');
      assert.equal(guide.steps[1].nextLabel, 'Take the welcome tour');
    }
  }
  assert.equal(createGuideDefinition('welcome').steps.length, 11);
  assert.equal(createGuideDefinition('welcome').steps.some(step => step.overview), false,
    'The general welcome retains its own introduction and numbered progression.');
  assert.equal(createGuideDefinition('channel-setup').version, 3);
  assert.equal(createGuideDefinition('aliases').version, 5);
  assert.equal(createGuideDefinition('missing-page'), null);
  const prepared = [];
  const released = [];
  const targetNames = (step) => step.targets().map(({ selector }) => /data-guide-target="([^"]+)"/.exec(selector)[1]);
  const targetDocument = { querySelectorAll: selector => [{ selector }] };
  const channelTour = createGuideDefinition('channel-setup', {
    documentValue: targetDocument,
    preparePlayer: () => { throw new Error('The page sample owns its player presentation.'); },
    preparePage: async (view, section, context) => {
      prepared.push([view, section, context]);
      return () => released.push(section);
    }
  });
  const channelSections = ['overview', 'new-channel', 'channel-choice', 'channel-choice-close',
    'discover', 'select-channel', 'receiving', 'startup', 'listening-controls', 'listening'];
  assert.deepEqual(channelTour.steps.map(step => step.id), channelSections,
    'Channel Setup teaches the chooser opening and return actions, then the actual player entry point.');
  const channelStop = id => channelTour.steps.find(step => step.id === id);
  for (const [id, target] of [['new-channel', 'channel-new'], ['channel-choice', 'channel-choice'],
    ['channel-choice-close', 'channel-choice-close'], ['discover', 'channel-discover'],
    ['select-channel', 'channel-sample-select'],
    ['startup', 'channel-startup'], ['listening-controls', 'player-lists'],
    ['listening', 'scan-lists-player-lists']]) {
    assert.deepEqual(targetNames(channelStop(id)), [target],
      `${id} highlights its own control or destination instead of a later feature’s opener.`);
  }
  for (const id of ['channel-choice', 'channel-choice-close', 'listening-controls', 'listening']) {
    const context = { registerLayer() {} };
    const release = await channelStop(id).prepare(context);
    assert.deepEqual(prepared.splice(0), [['channel-setup', id, context]],
      'Actual channel chooser and player layers use the central page preparation context.');
    release();
    assert.deepEqual(released.splice(0), [id], 'Leaving each prepared channel step restores its actual interface.');
  }
  assert.ok(channelTour.steps.filter(step => !['channel-choice', 'channel-choice-close', 'listening-controls', 'listening'].includes(step.id))
    .every(step => step.prepare === undefined), 'Channel steps open a dialog or player only when their lesson needs it.');
  assert.deepEqual(channelStop('receiving').samples[0].value.selected_channel_ids, ['guide-sample-channel']);
  assert.deepEqual(channelStop('select-channel').samples[0].value.selected_channel_ids, [],
    'The sample channel is unselected while the actual selection action is introduced.');
  assert.match(channelStop('receiving').targets()[0].selector, /channel-sample-start/,
    'The receiving lesson exposes the Start action on its sample channel.');
  assert.equal(channelTour.steps.at(-1).nextLabel, 'Finish');
  assert.equal(channelTour.steps.some(step => step.id === 'filters'), false,
    'The Channel Setup tour omits the self-explanatory filter lesson.');
  const channelOnly = createGuideDefinition('channel-setup', { canView: view => view === 'channel-setup' });
  assert.deepEqual(channelOnly.steps.map(step => step.id), channelSections.filter(id =>
    !['discover', 'listening-controls', 'listening'].includes(id)),
  'Unavailable tuner and player actions are omitted together with their destinations.');
  assert.equal(channelOnly.steps.at(-1).nextLabel, 'Finish');
  const channelOnlyCopy = channelOnly.steps.flatMap(step => step.paragraphs || []).join(' ');
  assert.doesNotMatch(channelOnlyCopy, /RadioReference|Scan Lists you want to hear/,
    'The channel chooser describes only source and listening actions available to the account.');
  const aliasesTour = createGuideDefinition('aliases', {
    preparePlayer: (options) => { prepared.push(['player', options]); return () => released.push('player'); },
    preparePage: async (view, section, context) => {
      prepared.push([view, section, context]);
      return () => released.push(section);
    }
  });
  const aliasSections = ['overview', 'lists', 'views', 'alias-button', 'alias-editor', 'alias-close',
    'defaults-button', 'defaults-unmatched', 'defaults-new-button', 'defaults-new', 'defaults-close',
    'bulk', 'scan-lists-button', 'scan-lists', 'clear-selection', 'activity-button', 'activity',
    'custom-button', 'custom', 'columns-button', 'discover-button', 'discover', 'configure-button', 'transfer'];
  assert.deepEqual(aliasesTour.steps.map(({ id }) => id), aliasSections,
    'The Aliases tour introduces its views and shows each opening, tab, and return action before changing context.');
  assert.equal(new Set(aliasesTour.steps.map(({ id }) => id)).size, aliasesTour.steps.length,
    'Each Aliases stop has a stable distinct identity for progression and cleanup.');
  for (const step of aliasesTour.steps.filter(step => !step.overview)) {
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
  for (const id of ['defaults-unmatched', 'defaults-new']) {
    const step = createGuideDefinition('aliases', { documentValue: targetDocument }).steps.find(step => step.id === id);
    assert.deepEqual(targetNames(step), ['aliases-defaults-content'],
      'Defaults concepts highlight the actual visible dialog viewport rather than fields clipped below its sticky footer.');
  }
  const editor = aliasesTour.steps.find(({ id }) => id === 'alias-editor');
  assert.equal(editor.paragraphs.length, 0, 'Alias editor guidance is separated by tab rather than a dense paragraph.');
  assert.deepEqual(editor.items.map(({ label }) => label), ['Basics', 'Identifier', 'Call Handling', 'Activity']);
  assert.match(editor.items[1].text, /talkgroup ID or radio ID/);
  assert.equal(aliasesTour.steps.at(-1).nextLabel, 'Finish');
  const aliasTargets = createGuideDefinition('aliases', { documentValue: targetDocument });
  const aliasStop = id => aliasTargets.steps.find(step => step.id === id);
  for (const [opener, target, destination] of [
    ['alias-button', 'aliases-open-alias', 'alias-editor'],
    ['defaults-button', 'aliases-defaults', 'defaults-unmatched'],
    ['defaults-new-button', 'aliases-defaults-new-tab', 'defaults-new'],
    ['scan-lists-button', 'aliases-bulk-scan-lists', 'scan-lists'],
    ['activity-button', 'aliases-activity', 'activity'],
    ['custom-button', 'aliases-custom', 'custom'],
    ['discover-button', 'aliases-discover', 'discover'],
    ['configure-button', 'aliases-configure', 'transfer']
  ]) {
    assert.deepEqual(targetNames(aliasStop(opener)), [target],
      `${opener} has a dedicated spotlight on the actual opening or tab control.`);
    assert.equal(aliasTargets.steps.indexOf(aliasStop(opener)) + 1,
      aliasTargets.steps.indexOf(aliasStop(destination)),
      `The ${destination} destination follows its own visible navigation action.`);
  }
  for (const [id, target] of [['alias-close', 'aliases-editor-close'],
    ['defaults-close', 'aliases-defaults-close'], ['clear-selection', 'aliases-clear-selection'],
    ['bulk', 'aliases-selection'], ['columns-button', 'aliases-columns']]) {
    assert.deepEqual(targetNames(aliasStop(id)), [target],
      'Returns, selection, and Columns each receive an explanation for the highlighted action.');
  }
  for (const [returnStep, nextStep] of [['alias-close', 'defaults-button'], ['defaults-close', 'bulk'],
    ['clear-selection', 'activity-button']]) {
    assert.equal(aliasTargets.steps.indexOf(aliasStop(returnStep)) + 1,
      aliasTargets.steps.indexOf(aliasStop(nextStep)),
      'The tour teaches the actual return action before moving to another section.');
  }
  assert.deepEqual(targetNames(aliasStop('activity')), ['aliases-activity-columns']);
  assert.deepEqual(targetNames(aliasStop('custom')), ['aliases-table']);
  assert.equal(aliasTargets.steps.some(step => step.id === 'columns'), false,
    'The Columns lesson stays on its real button without opening an unexplained popover.');
  const failedPreparation = createGuideDefinition('aliases', {
    preparePlayer: () => () => released.push('player'),
    preparePage: () => { throw new Error('sample preparation failed'); }
  });
  await assert.rejects(failedPreparation.steps.find(step => step.id === 'alias-editor').prepare({}),
    /sample preparation failed/);
  assert.deepEqual(released.splice(0), ['player'],
    'A failed page preparation releases the temporary player presentation.');
  const scanListsTour = createGuideDefinition('scan-lists', {
    preparePlayer: (options) => { prepared.push(['player', options]); return () => released.push('player'); },
    preparePage: async (view, section, context) => {
      prepared.push([view, section, context]);
      return () => released.push(section);
    }
  });
  const scanListSections = ['overview', 'lists', 'members', 'add-list-button', 'add-alias-list',
    'back-to-lists', 'listening-controls', 'listening'];
  assert.equal(scanListsTour.version, 6, 'The focused Scan Lists tour supersedes its earlier unmatched-traffic walkthrough.');
  assert.equal(scanListsTour.steps[0].diagram, 'scan-lists',
    'The opening overview uses the approved relationship chart without preparing a sample workspace.');
  assert.deepEqual(scanListsTour.steps.map(({ id }) => id), scanListSections);
  for (const step of scanListsTour.steps.filter(step => !step.overview)) {
    assert.deepEqual(step.samples, [{ id: 'scan-lists-workspace', value: { section: step.id } }]);
    assert.doesNotThrow(() => step.targets());
    const context = { registerLayer() {} };
    const release = await step.prepare(context);
    assert.deepEqual(prepared.splice(0), [['player', { minimized: true }], ['scan-lists', step.id, context]]);
    release();
    assert.deepEqual(released.splice(0), [step.id, 'player']);
  }
  const scanListCopy = scanListsTour.steps.flatMap(({ title, paragraphs = [] }) => [title, ...paragraphs]).join(' ');
  assert.doesNotMatch(scanListCopy, /\b(?:not|doesn’t|doesn't|don’t|don't|no longer)\b/i,
    'The Scan Lists tour explains the listening benefit positively.');
  assert.ok(scanListCopy.split(/\s+/).length < 250, 'The tour stays short enough to read while following the interface.');
  assert.doesNotMatch(scanListCopy, /display order|lower numbers|name and organize|available to listeners|choose the default/i,
    'The tour omits self-explanatory list settings.');
  assert.match(scanListsTour.steps.find(({ id }) => id === 'add-list-button').paragraphs[1], /individual aliases.*Manage > Aliases/);
  assert.match(scanListsTour.steps.at(-1).paragraphs[0], /queues completed calls.*one at a time.*plays once/);
  const scanListTargets = createGuideDefinition('scan-lists', { documentValue: {
    querySelectorAll: selector => [{ selector }]
  } });
  const scanStop = id => scanListTargets.steps.find(step => step.id === id);
  assert.deepEqual(targetNames(scanStop('lists')), ['scan-lists-manage-aliases'],
    'Manage aliases receives its own explanation before the member destination opens.');
  assert.deepEqual(targetNames(scanStop('members')),
    ['scan-lists-member-alias', 'scan-lists-member-alias-list'],
    'The member lesson highlights only the columns it explains.');
  assert.deepEqual(targetNames(scanStop('add-list-button')), ['scan-lists-add-list-button'],
    'The Add an Entire Alias List button receives a separate, single-purpose stop.');
  assert.deepEqual(targetNames(scanStop('add-alias-list')), ['scan-lists-add-list-dialog']);
  assert.deepEqual(targetNames(scanStop('back-to-lists')), ['scan-lists-back-to-lists'],
    'Leaving the member workspace gets a dedicated stop on the actual return control.');
  assert.equal(scanListTargets.steps.indexOf(scanStop('add-alias-list')) + 1,
    scanListTargets.steps.indexOf(scanStop('back-to-lists')),
    'The dialog closes onto its originating member workspace before the return action is taught.');
  assert.equal(scanListTargets.steps.indexOf(scanStop('back-to-lists')) + 1,
    scanListTargets.steps.indexOf(scanStop('listening-controls')),
    'The return control is taught immediately before the Scan Lists catalog is shown again.');
  assert.deepEqual(targetNames(scanStop('listening-controls')), ['player-lists'],
    'The actual Lists button is shown before its player panel opens.');
  assert.deepEqual(targetNames(scanStop('listening')), ['scan-lists-player-lists']);
  assert.equal(scanListTargets.steps.indexOf(scanStop('add-list-button')) + 1,
    scanListTargets.steps.indexOf(scanStop('add-alias-list')),
    'A dialog’s own opening control is introduced immediately before that dialog.');
  assert.doesNotMatch(scanStop('add-alias-list').paragraphs.join(' '), /Call Handling Defaults|New Aliases/,
    'The add-list dialog lesson stays on its own operation.');
  assert.equal(scanListsTour.steps.at(-1).nextLabel, 'Finish');
  const noAudioScanListsTour = createGuideDefinition('scan-lists', { canView: view => view !== 'scanner' });
  assert.deepEqual(noAudioScanListsTour.steps.map(({ id }) => id), scanListSections.slice(0, -2),
    'An account without Live audio access is not shown player listening choices.');
  assert.equal(noAudioScanListsTour.steps.at(-1).id, 'back-to-lists');
  assert.equal(noAudioScanListsTour.steps.at(-1).nextLabel, 'Finish');
  assert.ok(noAudioScanListsTour.steps.slice(0, -1).every(step => step.nextLabel === undefined));
  const noAliasesScanListsTour = createGuideDefinition('scan-lists', { canView: view => view !== 'aliases' });
  assert.deepEqual(noAliasesScanListsTour.steps.map(({ id }) => id),
    ['overview', 'lists', 'listening-controls', 'listening'],
    'An account without Alias administration is not sent into member management.');
  assert.equal(noAliasesScanListsTour.steps.at(-1).nextLabel, 'Finish');
  assert.ok(noAliasesScanListsTour.steps.slice(0, -1).every(step => step.nextLabel === undefined));
  const scanListOnlyTour = createGuideDefinition('scan-lists', { canView: view => view === 'scan-lists' });
  assert.deepEqual(scanListOnlyTour.steps.map(({ id }) => id), ['overview', 'lists']);
  assert.equal(scanListOnlyTour.steps.at(-1).nextLabel, 'Finish');
  const listOnlyTargets = createGuideDefinition('scan-lists', {
    canView: view => view === 'scan-lists', documentValue: { querySelectorAll: selector => [{ selector }] }
  });
  assert.deepEqual(targetNames(listOnlyTargets.steps.find(step => step.id === 'lists')), ['scan-lists-selector'],
    'A listener without member-management access receives an available page target instead of its launch button.');
  const { getScanListsGuideData } = await load('guides/scan-lists-data.js');
  const scanListData = getScanListsGuideData('members');
  assert.equal(scanListData.scanLists.filter(row => row.default).length, 1);
  assert.deepEqual(scanListData.scanLists.map(row => [row.name, row.published, row.alias_count]),
    [['Public Safety', true, 4], ['Fire & EMS', true, 2]]);
  assert.equal(scanListData.scanLists.filter(row => row.published && row.enabled).length, 2,
    'Every listening group introduced by the tour is available in its player panel.');
  assert.equal(new Set(scanListData.memberPage.rows.map(row => row.alias_list_id)).size, 2,
    'The actual membership table demonstrates aliases from several Alias Lists.');
  assert.deepEqual(scanListData.rows.slice(0, 2).map(row => row.scan_list_ids), [[900001, 900002], [900001, 900002]]);
  assert.ok(scanListData.scanLists.every(row => row.unmatched_alias_lists.length === 0));
  assert.deepEqual(scanListData.lists[0].unknown_alias_behavior.scan_list_ids, []);
  assert.deepEqual(getScanListsGuideData('members').selectedIds, [], 'The members stop shows the normal unselected table.');
  assert.equal(getScanListsGuideData('lists').selectedScanList.id, 900001);
  assert.equal(getScanListsGuideData('listening-controls').selectedScanList.id, 900001);
  assert.equal(getScanListsGuideData('listening').selectedScanList.id, 900001,
    'The catalog retains its current selection while the player is introduced.');
  scanListData.rows[0].name = 'Changed sample';
  assert.equal(getScanListsGuideData().rows[0].name, 'Fire Dispatch', 'Each step receives independent sample records.');
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
  assert.equal(pagePrepared.shown.at(-1).options.steps[0].prepare, undefined);
  assert.deepEqual(pagePreparation, [], 'Opening the concept overview leaves sample workspaces inactive.');
  await pagePrepared.shown.at(-1).options.steps[1].prepare({ registerLayer() {} });
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
  assert.equal(dismissedWelcome.shown.at(-1).options.steps[0].overview, true);
  assert.equal(dismissedWelcome.shown.at(-1).options.steps[1].title, 'Guide coming soon');
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
    getItem: key => /:welcome:1$|:aliases:[1234]$/.test(key) ? '1' : null,
    setItem() {}
  } });
  await dismissedPlaceholder.controller.pageReady();
  assert.equal(dismissedPlaceholder.shown.at(-1).options.id, 'aliases');
  assert.equal(dismissedPlaceholder.shown.at(-1).options.version, 5,
    'Completing an older Aliases tour cannot suppress the walkthrough with visible navigation actions.');

  const dismissedScanListPlaceholder = makeHarness({ context: { view: 'scan-lists' }, storage: {
    getItem: key => /:welcome:1$|:scan-lists:[12345]$/.test(key) ? '1' : null,
    setItem() {}
  } });
  await dismissedScanListPlaceholder.controller.pageReady();
  assert.equal(dismissedScanListPlaceholder.shown.at(-1).options.id, 'scan-lists');
  assert.equal(dismissedScanListPlaceholder.shown.at(-1).options.version, 6,
    'Completing an older Scan Lists tour cannot suppress its focused membership and listening walkthrough.');
  dismissedScanListPlaceholder.finish();
  await dismissedScanListPlaceholder.controller.openPage();
  assert.equal(dismissedScanListPlaceholder.shown.at(-1).options.id, 'scan-lists',
    'Help replays the Scan Lists tour after completion.');
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
  const replayScanLists = makeHarness({ context: { view: 'dashboard', guide: 'scan-lists' } });
  await replayScanLists.controller.pageReady();
  assert.deepEqual(replayScanLists.navigations, ['scan-lists']);
  await replayScanLists.controller.pageReady();
  assert.equal(replayScanLists.shown.at(-1).options.id, 'scan-lists');
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

  for (const [view, expected, excluded] of [
    ['tuners', /^In Tuners,/, /Browser Spectrum/],
    ['tuner-spectrum', /^Use Browser Spectrum/, /In Tuners,/]
  ]) {
    const hardwareOnly = createGuideDefinition('welcome', {
      canView: candidate => candidate === view, canSetup: false, canReport: false
    }).steps.find(step => step.id === 'hardware');
    assert.equal(hardwareOnly.paragraphs.length, 1);
    assert.match(hardwareOnly.paragraphs[0], expected,
      'Hardware permission filtering follows the same order as its displayed explanations.');
    assert.doesNotMatch(hardwareOnly.paragraphs[0], excluded);
  }

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
