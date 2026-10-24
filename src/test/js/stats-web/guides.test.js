'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

function coverageDiagramHarness() {
  const frames = new Map(), nodes = [], observers = [];
  const events = () => {
    const listeners = new Map();
    return { listeners,
      addEventListener(type, callback) { if (!listeners.has(type)) listeners.set(type, new Set()); listeners.get(type).add(callback); },
      removeEventListener(type, callback) { listeners.get(type)?.delete(callback); },
      emit(type) { [...(listeners.get(type) || [])].forEach(callback => callback()); }
    };
  };
  const element = tag => {
    const item = { tag, children: [], dataset: {}, attributes: {}, ...events(),
      append(...children) { children.forEach(child => { child.remove?.(); child.parentNode = item; item.children.push(child); }); },
      replaceChildren(...children) { item.children.forEach(child => { child.parentNode = null; }); item.children = []; item.append(...children); },
      setAttribute(name, value) { item.attributes[name] = String(value); },
      remove() { if (item.parentNode) item.parentNode.children = item.parentNode.children.filter(child => child !== item); item.parentNode = null; },
      getBoundingClientRect: () => ({ width: 960, height: 207 }) };
    nodes.push(item); return item;
  };
  let nextFrame = 0;
  const motion = { matches: false, ...events() };
  const windowValue = { ...events(), matchMedia: () => motion,
    requestAnimationFrame(callback) { frames.set(++nextFrame, callback); return nextFrame; },
    cancelAnimationFrame(id) { frames.delete(id); },
    ResizeObserver: class {
      constructor(callback) { this.callback = callback; observers.push(this); }
      observe() { this.observing = true; }
      disconnect() { this.observing = false; }
    } };
  const document = { hidden: false, defaultView: windowValue, ...events(),
    createElement: element, createElementNS: (namespace, tag) => element(tag) };
  return { document, windowValue, motion, nodes, frames, observers,
    flush(timestamp) { const queued = [...frames.values()]; frames.clear(); queued.forEach(callback => callback(timestamp)); } };
}

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
    assert.equal(guide.authored, ['channel-setup', 'aliases', 'scan-lists', 'radioreference', 'streaming', 'tuners', 'tuner-spectrum', 'live', 'dashboard', 'scanner'].includes(id));
    const overview = guide.steps[0];
    assert.equal(overview.id, 'overview');
    assert.equal(overview.overview, true);
    assert.ok(overview.title && overview.paragraphs.length, 'Each overview explains its page’s purpose.');
    if (['tuner-spectrum', 'live'].includes(id)) {
      assert.equal(overview.samplePresentation, true,
        'Hardware-free Live and Spectrum explicitly opt into sample overviews while keeping controls unhighlighted.');
      assert.deepEqual(overview.samples, [{ id: `${id}-workspace`, value: { section: 'overview' } }]);
      assert.equal(typeof overview.prepare, 'function');
    } else {
      assert.equal(overview.samples, undefined, 'Other overviews leave real page data in place.');
      assert.equal(overview.prepare, undefined, 'Other overviews open no menus, dialogs, or player panels.');
    }
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
  assert.equal(createGuideDefinition('radioreference').version, 3);
  assert.equal(createGuideDefinition('streaming').version, 3);
  assert.equal(createGuideDefinition('tuners').version, 3);
  assert.equal(createGuideDefinition('tuner-spectrum').version, 4);
  assert.equal(createGuideDefinition('live').version, 3);
  assert.equal(createGuideDefinition('dashboard').version, 3);
  assert.equal(createGuideDefinition('scanner').version, 3);
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
  const radioReferenceTour = createGuideDefinition('radioreference', {
    documentValue: targetDocument,
    preparePlayer: options => { prepared.push(['player', options]); return () => released.push('player'); },
    preparePage: async (view, section, context) => {
      prepared.push([view, section, context]);
      return () => released.push(section);
    }
  });
  const radioReferenceSections = ['overview', 'area-groups', 'sort', 'bookmark', 'bookmarks-button',
    'saved-system', 'system-sections', 'site-button', 'site-import', 'channel-review-button',
    'channel-preview', 'channel-complete-close', 'talkgroups-button', 'alias-list', 'comparison',
    'select-update', 'import-selected', 'alias-preview', 'alias-complete-close'];
  assert.deepEqual(radioReferenceTour.steps.map(step => step.id), radioReferenceSections,
    'RadioReference follows browsing, a site import, and an existing Alias List update with visible opening and return actions.');
  const rrStop = id => radioReferenceTour.steps.find(step => step.id === id);
  assert.equal(radioReferenceTour.steps[0].title, 'Bring radio information into VCE');
  assert.equal(radioReferenceTour.steps[0].prepare, undefined,
    'The RadioReference concept overview leaves the real account, locality, and workspace untouched.');
  assert.deepEqual(targetNames(rrStop('area-groups')), ['rr-area-county', 'rr-area-state', 'rr-area-national']);
  assert.deepEqual(targetNames(rrStop('system-sections')), ['rr-sites', 'rr-talkgroups-button']);
  assert.deepEqual(rrStop('system-sections').items.map(item => item.label),
    ['Sites & Channels', 'Talkgroups & Aliases'], 'Receiving frequencies and alias identities get separate explanations.');
  assert.deepEqual(rrStop('comparison').items.map(item => item.label), ['Not present', 'Different', 'Identical']);
  assert.match(rrStop('alias-preview').paragraphs[0], /keeping your recording, Scan List, and streaming choices/,
    'An update explains which listening settings remain in the operator’s control.');
  for (const step of radioReferenceTour.steps.filter(step => !step.overview)) {
    assert.deepEqual(step.samples, [{ id: 'radioreference-workspace', value: { section: step.id } }],
      'Every RadioReference stop activates the same protected, temporary workspace lifecycle.');
    const context = { registerLayer() {} };
    const release = await step.prepare(context);
    assert.deepEqual(prepared.splice(0), [['player', { minimized: true }], ['radioreference', step.id, context]],
      'The shared page preparation renders the real interface and import dialogs beneath the guide.');
    release();
    assert.deepEqual(released.splice(0), [step.id, 'player'],
      'Leaving a RadioReference stop releases its page and owned dialogs before restoring the player.');
  }
  for (const [opener, target, destination] of [
    ['bookmarks-button', 'rr-bookmarks-button', 'saved-system'],
    ['saved-system', 'rr-saved-system', 'system-sections'],
    ['site-button', 'rr-site-button', 'site-import'],
    ['channel-review-button', 'rr-channel-review-button', 'channel-preview'],
    ['channel-complete-close', 'rr-channel-complete-close', 'talkgroups-button'],
    ['talkgroups-button', 'rr-talkgroups-button', 'alias-list'],
    ['alias-list', 'rr-alias-list', 'comparison'],
    ['select-update', 'rr-select-update', 'import-selected'],
    ['import-selected', 'rr-import-selected', 'alias-preview']
  ]) {
    assert.deepEqual(targetNames(rrStop(opener)), [target],
      `${opener} teaches only its own real opening, selection, or return control.`);
    assert.equal(radioReferenceTour.steps.indexOf(rrStop(opener)) + 1,
      radioReferenceTour.steps.indexOf(rrStop(destination)),
      `${destination} follows the visible action that gets the operator there.`);
  }
  assert.deepEqual(targetNames(rrStop('site-import')), ['rr-site-import']);
  assert.deepEqual(targetNames(rrStop('channel-preview')), ['rr-channel-preview', 'rr-channel-apply']);
  assert.deepEqual(targetNames(rrStop('alias-preview')), ['rr-alias-preview', 'rr-alias-apply']);
  assert.deepEqual(targetNames(rrStop('comparison')), ['rr-comparison']);
  assert.equal(rrStop('back-results'), undefined, 'Desktop results stay beside the system; its hidden Back control is omitted.');
  assert.deepEqual(targetNames(rrStop('alias-complete-close')), ['rr-alias-complete-close']);
  assert.equal(radioReferenceTour.steps.at(-1).nextLabel, 'Finish');
  const failedRadioReferencePreparation = createGuideDefinition('radioreference', {
    preparePlayer: () => () => released.push('player'),
    preparePage: () => { throw new Error('RadioReference sample preparation failed'); }
  });
  await assert.rejects(failedRadioReferencePreparation.steps.find(step => step.id === 'site-import').prepare({}),
    /RadioReference sample preparation failed/);
  assert.deepEqual(released.splice(0), ['player'],
    'A failed actual RadioReference dialog preparation cannot leave the player minimized.');
  const failedRadioReferenceCleanup = createGuideDefinition('radioreference', {
    preparePlayer: () => () => released.push('player'),
    preparePage: () => () => { throw new Error('RadioReference cleanup failed'); }
  });
  const releaseFailedRadioReference = await failedRadioReferenceCleanup.steps[1].prepare({});
  assert.throws(releaseFailedRadioReference, /RadioReference cleanup failed/);
  assert.deepEqual(released.splice(0), ['player'],
    'Even a failed workspace cleanup releases the player presentation.');
  const { createRadioReferenceGuideData, RADIO_REFERENCE_GUIDE_IDS: rrIds } =
    await load('guides/radioreference-presentation.js');
  const rrData = createRadioReferenceGuideData();
  const rrExampleApi = '/api/v1/admin/radioreference';
  const comparisonPath = `${rrExampleApi}/systems/talkgroups/catalog?system_id=${rrIds.system}&alias_list_id=${rrIds.aliasList}`;
  const sampleComparison = await rrData.requestJson(comparisonPath);
  assert.deepEqual(sampleComparison.items.map(row => row.status), ['DIFFERENT', 'NOT_PRESENT', 'IDENTICAL'],
    'The real comparison table receives one changed, one new, and one current talkgroup.');
  sampleComparison.items[0].talkgroup.alpha_tag = 'Changed outside the fixture';
  assert.equal((await rrData.requestJson(comparisonPath)).items[0].talkgroup.alpha_tag, 'Sheriff Dispatch 1',
    'An actual renderer cannot mutate the shared tutorial examples.');
  const samplePreview = await rrData.requestJson(`${rrExampleApi}/imports/talkgroups/preview`, {
    method: 'POST', body: { alias_list_id: rrIds.aliasList, system_id: rrIds.system, talkgroup_ids: [rrIds.sheriff] }
  });
  assert.equal(samplePreview.rows.length, 1, 'The update preview follows the selected talkgroup instead of importing every example.');
  assert.deepEqual([samplePreview.add, samplePreview.update, samplePreview.unchanged], [0, 1, 0]);
  assert.deepEqual(samplePreview.rows[0].changes,
    [{ field: 'name', before: 'Sheriff Dispatch', after: 'Sheriff Dispatch 1' }]);
  await rrData.requestJson(`${rrExampleApi}/imports/${samplePreview.preview_id}/apply`, { method: 'POST' });
  assert.equal((await rrData.requestJson(comparisonPath)).items[0].status, 'IDENTICAL',
    'The completion dialog and refreshed comparison use the same temporary import result.');
  assert.equal((await createRadioReferenceGuideData().requestJson(comparisonPath)).items[0].status, 'DIFFERENT',
    'A fresh presentation restores its own example rather than inheriting a previous update.');
  await assert.rejects(rrData.requestJson('/api/v1/admin/channels', { method: 'POST' }),
    /Unsupported RadioReference example request/,
    'An unexpected operation fails within the sample transport rather than reaching a real receiver.');
  const closedExamples = new AbortController();
  const abortableRadioReferenceData = createRadioReferenceGuideData({ signal: closedExamples.signal });
  closedExamples.abort();
  await assert.rejects(abortableRadioReferenceData.requestJson(comparisonPath), error => error.name === 'AbortError',
    'Dismissed examples cannot continue a pending interface preparation.');
  const streamingTour = createGuideDefinition('streaming', {
    documentValue: targetDocument,
    preparePlayer: options => { prepared.push(['player', options]); return () => released.push('player'); },
    preparePage: async (view, section, context) => {
      prepared.push([view, section, context]);
      return () => released.push(section);
    }
  });
  const streamingSections = ['overview', 'destinations', 'add-button', 'provider', 'add-cancel',
    'feeds-button', 'feeds', 'feeds-close', 'manage-button', 'sections', 'aliases-button',
    'assign-alias', 'save-assignments', 'status-button', 'delivery', 'recovery',
    'destination-close', 'patch-routing'];
  assert.deepEqual(streamingTour.steps.map(step => step.id), streamingSections,
    'Streaming follows destination creation, feed lookup, assignment and delivery through visible opening and return actions.');
  const streamingStop = id => streamingTour.steps.find(step => step.id === id);
  assert.equal(streamingTour.steps[0].title, 'Share your radio traffic');
  assert.deepEqual(streamingStop('sections').items.map(item => item.label), ['Settings', 'Aliases', 'Status']);
  assert.deepEqual(streamingStop('delivery').items.map(item => item.label),
    ['Queued', 'Sent / uploaded', 'Aged off', 'Errors']);
  assert.deepEqual(targetNames(streamingStop('recovery')), ['st-recovery-state', 'st-recovery-error'],
    'Recovery connects the current state with the earlier error rather than treating Last error as the current connection.');
  for (const step of streamingTour.steps.filter(step => !step.overview)) {
    assert.deepEqual(step.samples, [{ id: 'streaming-workspace', value: { section: step.id } }],
      'Each Streaming stop shares the protected sample workspace lifecycle.');
    const context = { registerLayer() {} };
    const release = await step.prepare(context);
    assert.deepEqual(prepared.splice(0), [['player', { minimized: true }], ['streaming', step.id, context]]);
    release();
    assert.deepEqual(released.splice(0), [step.id, 'player'],
      'Streaming releases its real dialogs and workspace before restoring the player.');
  }
  for (const [opener, destination] of [['add-button', 'provider'], ['feeds-button', 'feeds'],
    ['manage-button', 'sections'], ['aliases-button', 'assign-alias'], ['status-button', 'delivery'],
    ['add-cancel', 'feeds-button'], ['feeds-close', 'manage-button'], ['destination-close', 'patch-routing']]) {
    assert.deepEqual(targetNames(streamingStop(opener)), [`st-${opener}`],
      `${opener} spotlights its own actual opening or return action.`);
    assert.equal(streamingTour.steps.indexOf(streamingStop(opener)) + 1,
      streamingTour.steps.indexOf(streamingStop(destination)),
      `${destination} follows the action that opens or returns to its context.`);
  }
  assert.equal(streamingTour.steps.at(-1).nextLabel, 'Finish');
  const streamingOnly = createGuideDefinition('streaming', {
    canView: view => view === 'streaming', canAccess: () => false
  });
  const adminStreamingSections = ['feeds-button', 'feeds', 'feeds-close', 'patch-routing'];
  assert.deepEqual(streamingOnly.steps.map(step => step.id),
    streamingSections.filter(id => !adminStreamingSections.includes(id)),
    'An account with only Streaming access keeps destination workflows while omitting administration-only feed and patch controls.');
  assert.equal(streamingOnly.steps.at(-1).id, 'destination-close');
  assert.equal(streamingOnly.steps.at(-1).nextLabel, 'Finish');
  assert.ok(streamingOnly.steps.slice(0, -1).every(step => step.nextLabel === undefined),
    'Role filtering leaves one final Finish action.');
  const hiddenFeeds = createGuideDefinition('streaming', { documentValue: {
    querySelector: () => ({ disabled: true }), querySelectorAll: () => []
  } });
  assert.deepEqual(hiddenFeeds.steps.map(step => step.id), streamingOnly.steps.map(step => step.id),
    'The actual disabled feed opener supplies the same availability fallback when no capability callback is provided.');
  const failedStreamingPreparation = createGuideDefinition('streaming', {
    preparePlayer: () => () => released.push('player'),
    preparePage: () => { throw new Error('Streaming sample preparation failed'); }
  });
  await assert.rejects(failedStreamingPreparation.steps.find(step => step.id === 'provider').prepare({}),
    /Streaming sample preparation failed/);
  assert.deepEqual(released.splice(0), ['player'],
    'A failed Streaming dialog preparation releases the minimized player presentation.');
  const failedStreamingCleanup = createGuideDefinition('streaming', {
    preparePlayer: () => () => released.push('player'),
    preparePage: () => () => { throw new Error('Streaming cleanup failed'); }
  });
  const releaseFailedStreaming = await failedStreamingCleanup.steps[1].prepare({});
  assert.throws(releaseFailedStreaming, /Streaming cleanup failed/);
  assert.deepEqual(released.splice(0), ['player'],
    'A workspace cleanup error still restores the player.');
  const { createStreamingGuideData } = await load('guides/streaming-presentation.js');
  const streamingData = createStreamingGuideData();
  const streamingApi = '/api/v1/admin/streaming';
  const sampleDestinations = await streamingData.requestJson(streamingApi);
  const countyCalls = sampleDestinations.destinations.find(row => row.configuration_id === 'county-calls');
  assert.deepEqual([countyCalls.state, countyCalls.queued, countyCalls.sent, countyCalls.aged_off, countyCalls.errors],
    ['CONNECTED', 3, 128, 2, 1], 'The recovery example pairs successful current delivery with an earlier timeout.');
  assert.equal(countyCalls.last_error, 'Connection timed out');
  countyCalls.name = 'Changed outside the fixture';
  assert.equal((await streamingData.requestJson(streamingApi)).destinations[0].name, 'County Calls',
    'Production rendering cannot mutate the Streaming sample documents.');
  const assignmentsPath = `${streamingApi}/county-calls/aliases`;
  const sampleAssignments = await streamingData.requestJson(assignmentsPath);
  const regionalFire = sampleAssignments.items.find(row => row.name === 'Regional Fire');
  assert.equal(regionalFire.assigned, false);
  assert.deepEqual([...new Set(sampleAssignments.items.map(row => row.alias_list_name))], ['County P25', 'City P25'],
    'The assignment example shows traffic from several Alias Lists reaching one destination.');
  await streamingData.requestJson(assignmentsPath, { method: 'POST',
    body: { revision: sampleAssignments.revision, add: [regionalFire.id], remove: [] } });
  assert.equal((await streamingData.requestJson(assignmentsPath)).items.find(row => row.id === regionalFire.id).assigned, true,
    'The real Save assignments action updates its isolated sample result.');
  assert.equal((await createStreamingGuideData().requestJson(assignmentsPath)).items.find(row => row.id === regionalFire.id).assigned, false,
    'Leaving and revisiting the lesson starts with fresh assignment examples.');
  await assert.rejects(streamingData.requestJson(assignmentsPath, { method: 'POST',
    body: { revision: sampleAssignments.revision, add: [], remove: [regionalFire.id] } }),
  error => error.code === 'stale_revision', 'Sample writes follow the real revision safety contract.');
  const sampleFeeds = await streamingData.requestJson(`${streamingApi}/feeds/refresh`, { method: 'POST' });
  assert.deepEqual(sampleFeeds.items.map(row => row.configured), [true, false],
    'The actual feed picker receives both an already-added feed and one ready to add.');
  const samplePreferences = await streamingData.requestOperationalPreference();
  await streamingData.requestOperationalPreference('PUT', 'patch_group_streaming_option', 'TALKGROUPS', samplePreferences.revision);
  assert.equal((await streamingData.requestOperationalPreference()).settings.patch_group_streaming_option, 'TALKGROUPS');
  assert.equal((await createStreamingGuideData().requestOperationalPreference()).settings.patch_group_streaming_option, 'PATCH_GROUP',
    'Patch routing examples leave receiver preferences untouched.');
  await assert.rejects(streamingData.requestJson(`${streamingApi}/county-calls`, { method: 'DELETE' }),
    /Unsupported Streaming example request/, 'Unexpected sample operations fail locally.');
  const closedStreamingExamples = new AbortController();
  const abortableStreamingData = createStreamingGuideData({ signal: closedStreamingExamples.signal });
  closedStreamingExamples.abort();
  await assert.rejects(abortableStreamingData.requestJson(streamingApi), error => error.name === 'AbortError');
  await assert.rejects(abortableStreamingData.requestOperationalPreference(), error => error.name === 'AbortError',
    'Dismissed Streaming examples cannot continue a pending page or preference request.');
  const tunersTour = createGuideDefinition('tuners', {
    documentValue: targetDocument,
    preparePlayer: options => { prepared.push(['player', options]); return () => released.push('player'); },
    preparePage: async (view, section, context) => {
      prepared.push([view, section, context]); return () => released.push(section);
    }
  });
  const tunerSections = ['overview', 'modes', 'setup-button', 'enter-setup', 'receiving-window',
    'center-frequency', 'lock-center', 'gain', 'frequency-correction', 'frequency-limits-button',
    'frequency-limits', 'resume-button', 'resume-channels', 'disable-button', 'disable-tuner',
    'disabled', 'restart-setup-button', 'restart-channels', 'live'];
  assert.deepEqual(tunersTour.steps.map(step => step.id), tunerSections,
    'Tuners establishes its receiving concept, then modes, adjustments, and a complete stop/restart workflow.');
  const tunerStop = id => tunersTour.steps.find(step => step.id === id);
  assert.equal(tunerStop('overview').diagram, 'tuner-coverage');
  assert.equal(tunerStop('overview').prepare, undefined,
    'The animated overview operates independently of the actual receiver page and player.');
  assert.deepEqual(tunerStop('modes').items.map(item => item.label), ['Live', 'Setup', 'Disabled']);
  assert.deepEqual(targetNames(tunerStop('disabled')), ['tn-disabled-mode', 'tn-disabled-signal']);
  assert.deepEqual(targetNames(tunerStop('live')), ['tn-live-mode', 'tn-live-count']);
  for (const [opener, destination] of [['setup-button', 'enter-setup'],
    ['frequency-limits-button', 'frequency-limits'], ['resume-button', 'resume-channels'],
    ['disable-button', 'disable-tuner'], ['restart-setup-button', 'restart-channels']]) {
    assert.deepEqual(targetNames(tunerStop(opener)), [`tn-${opener}`]);
    assert.equal(tunersTour.steps.indexOf(tunerStop(opener)) + 1,
      tunersTour.steps.indexOf(tunerStop(destination)),
      `${destination} follows its actual opening control instead of appearing unexpectedly.`);
  }
  for (const step of tunersTour.steps.filter(step => !step.overview)) {
    assert.deepEqual(step.samples, [{ id: 'tuners-workspace', value: { section: step.id } }]);
    const context = { registerLayer() {} };
    const release = await step.prepare(context);
    assert.deepEqual(prepared.splice(0), [['player', { minimized: true }], ['tuners', step.id, context]]);
    release();
    assert.deepEqual(released.splice(0), [step.id, 'player'],
      'Tuner examples release actual dialogs and page state before restoring the player.');
  }
  assert.equal(tunersTour.steps.at(-1).nextLabel, 'Finish');
  const failedTunerCleanup = createGuideDefinition('tuners', {
    preparePlayer: () => () => released.push('player'),
    preparePage: () => () => { throw new Error('Tuner cleanup failed'); }
  });
  const releaseFailedTuner = await failedTunerCleanup.steps[1].prepare({});
  assert.throws(releaseFailedTuner, /Tuner cleanup failed/);
  assert.deepEqual(released.splice(0), ['player']);
  const { TUNER_COVERAGE_MODEL: coverageModel, coverageCenterAt, coverageReceiverFor, coverageTimeAt,
    coverageCallsAt, createTunerCoverageDiagram } =
    await load('guides/tuner-coverage.js');
  assert.equal(coverageModel.receivers.length, 2);
  assert.deepEqual(coverageModel.receivers.map(receiver => [receiver.name, receiver.sampleRate, receiver.usableBandwidth]),
    [['RTL-SDR 1', 2.4, 2.352], ['RTL-SDR 2', 2.4, 2.352]]);
  assert.equal(coverageModel.controls.length, 1, 'One RTL-SDR holds the single control channel throughout the example.');
  assert.ok(Math.abs(coverageModel.controls[0].frequency - coverageModel.primaryCenter) >=
    coverageModel.rtl.usableBandwidth * .2, 'The control channel stays visibly away from its tuner center.');
  assert.equal(coverageModel.duration / coverageModel.speed, 60,
    'The overview runs for one minute before repeating.');
  assert.ok(coverageModel.history / coverageModel.speed <= 4,
    'Twenty-four seconds of waterfall history scroll through at least three times faster than before.');
  assert.equal(new Set(coverageModel.calls.map(call => call.id)).size, coverageModel.calls.length);
  for (const call of coverageModel.calls) {
    assert.ok([call.frequency, call.start, call.end].every(Number.isFinite) && call.end > call.start);
    assert.ok(call.start >= 0 && call.start < coverageModel.duration);
    assert.ok(call.end <= coverageModel.duration - .25 && call.end - call.start <= coverageModel.arrivalInterval - .5,
      'Calls finish before the next placement and before the loop resets.');
  }
  for (let time = coverageModel.history; time <= coverageModel.duration; time += coverageModel.history) {
    const arrivals = coverageModel.calls.filter(call => call.start >= time - coverageModel.history && call.start < time);
    assert.ok(arrivals.length >= 2, 'Each faster-scrolling history window continues to show radio traffic.');
  }
  for (const time of [-24, -.25, 0, 14, 155, coverageModel.duration - .25]) {
    assert.equal(coverageTimeAt(time + coverageModel.duration), coverageTimeAt(time));
    assert.equal(coverageCenterAt(time + coverageModel.duration), coverageCenterAt(time),
      'Receiving-window placement repeats continuously across the loop boundary.');
    assert.equal(coverageCenterAt(time, false), coverageModel.initialCenter);
  }
  assert.equal(coverageTimeAt(coverageModel.duration), 0);
  assert.equal(coverageTimeAt(-.25), coverageModel.duration - .25);
  const coverageCall = id => coverageModel.calls.find(call => call.id === id);
  assert.equal(coverageReceiverFor(coverageCall('fire')), 'RTL-SDR 1');
  assert.equal(coverageReceiverFor(coverageCall('sheriff')), 'RTL-SDR 1');
  assert.equal(coverageReceiverFor(coverageCall('regional-missed')), null);
  assert.equal(coverageReceiverFor(coverageCall('regional-later')), 'RTL-SDR 2');
  assert.equal(coverageReceiverFor(coverageCall('regional-later'), false), null);
  assert.equal(coverageReceiverFor(coverageCall('distant-followed')), 'RTL-SDR 2');
  assert.equal(coverageReceiverFor(coverageCall('distant-later')), 'RTL-SDR 2');
  assert.equal(coverageReceiverFor(coverageCall('regional-missed')), null,
    'The earlier missed transmission stays missed after a later transmission fits the shifted window.');
  const rtlCovers = (frequency, center) => frequency - .00625 >= center - coverageModel.rtl.usableBandwidth / 2 &&
    frequency + .00625 <= center + coverageModel.rtl.usableBandwidth / 2 &&
    !(frequency - .00625 < center + .005 && frequency + .00625 > center - .005);
  assert.ok(coverageModel.retunes.length > 30, 'The second RTL-SDR hops frequently throughout the minute.');
  assert.ok(new Set(coverageModel.retunes.map(retune => retune.to)).size >= 4,
    'The hopping example visits several distinct receiving windows.');
  assert.ok(rtlCovers(coverageModel.controls[0].frequency, coverageModel.primaryCenter));
  for (const retune of coverageModel.retunes) {
    assert.ok([retune.start, retune.end, retune.from, retune.to].every(Number.isFinite));
    assert.ok(retune.end > retune.start && retune.start >= coverageModel.unlockAt && retune.end <= coverageModel.duration);
    assert.equal(coverageCenterAt(retune.start), retune.from);
    assert.equal(coverageCenterAt(retune.end), retune.to);
    const activeCalls = coverageModel.calls.filter(call => call.start < retune.end && call.end > retune.end &&
      coverageReceiverFor(call) === 'RTL-SDR 2');
    for (const call of activeCalls) {
      assert.ok(rtlCovers(call.frequency, retune.from) && rtlCovers(call.frequency, retune.to),
        'The roaming tuner retains full voice coverage for every in-progress received call.');
    }
  }
  for (const call of coverageModel.calls) {
    const receiver = coverageReceiverFor(call);
    const fitsAtStart = rtlCovers(call.frequency, coverageModel.primaryCenter) ||
      rtlCovers(call.frequency, coverageCenterAt(call.start));
    assert.equal(Boolean(receiver), fitsAtStart,
      'Every call fitting at its start retains reception through its full transmission.');
    if (!receiver) continue;
    const placements = receiver === 'RTL-SDR 1' ? [coverageModel.primaryCenter] :
      [coverageCenterAt(call.start), ...coverageModel.retunes.filter(retune => retune.end > call.start &&
        retune.end < call.end).map(retune => retune.to)];
    assert.ok(placements.every(center => rtlCovers(call.frequency, center)),
      'Every received channel fits completely inside its assigned tuner bandwidth and avoids its center notch.');
  }
  const retainedHistory = coverageModel.retunes.map(retune => ({ retune, earlierReceived: coverageModel.calls.find(call =>
    call.end < retune.end && call.end > retune.end - coverageModel.history &&
    coverageReceiverFor(call) === 'RTL-SDR 2' && !rtlCovers(call.frequency, retune.to)) }))
    .find(example => example.earlierReceived);
  assert.ok(retainedHistory, 'A received call remains in visible history when the roaming tuner hops elsewhere.');
  assert.equal(coverageReceiverFor(retainedHistory.earlierReceived), 'RTL-SDR 2',
    'A later center change preserves the reception outcome of calls already in the waterfall.');
  const lowerEdge = coverageModel.initialCenter - coverageModel.rtl.usableBandwidth / 2;
  assert.equal(coverageReceiverFor({ frequency: lowerEdge + .001, start: 0 }), null,
    'The whole channel must fit inside usable bandwidth, including its width near an edge.');
  for (const center of [coverageModel.primaryCenter, coverageModel.initialCenter]) {
    assert.equal(coverageReceiverFor({ frequency: center, start: 0 }), null,
      'Both RTL-SDR center notches stay excluded just as they do for actual channel placement.');
  }
  const firstHop = coverageModel.retunes[0];
  assert.equal(coverageReceiverFor({ frequency: 861.5125, start: firstHop.start - 1, end: firstHop.end + 1 }), null,
    'A synthetic call spanning an out-of-band retune cannot claim uninterrupted reception.');
  const precedingHistory = coverageCallsAt(0);
  assert.ok(precedingHistory.length >= 2 && precedingHistory.some(call => call.start < 0),
    'The first displayed history window retains transmissions from the preceding loop.');
  assert.deepEqual(coverageCallsAt(coverageModel.duration).map(call => call.sourceId),
    precedingHistory.map(call => call.sourceId), 'The loop boundary keeps a populated waterfall.');
  for (const elapsed of [0, 14, 155, coverageModel.duration - .1, coverageModel.duration + 2]) {
    for (const call of coverageCallsAt(elapsed)) {
      assert.ok(call.start <= elapsed && call.end >= elapsed - coverageModel.history,
        'Visible traces belong to the current history interval.');
      const original = coverageModel.calls.find(candidate => candidate.id === call.sourceId);
      assert.ok(original);
      assert.equal(coverageReceiverFor(call), coverageReceiverFor(original),
        'Carrying a transmission across the loop boundary retains its received or missed outcome.');
    }
  }
  const animation = coverageDiagramHarness();
  animation.motion.matches = true;
  const coverageDiagram = createTunerCoverageDiagram({ document: animation.document, idPrefix: 'coverage-contract' });
  const diagramAbort = new AbortController();
  const closeDiagram = coverageDiagram.mount({ signal: diagramAbort.signal });
  assert.equal(animation.nodes.some(node => ['button', 'input'].includes(node.tag)), false,
    'The overview animation runs automatically without adding operating controls.');
  assert.equal(animation.frames.size, 1,
    'The explicitly requested demonstration autoplays even when reduced motion is preferred.');
  animation.flush(0); animation.flush(100);
  animation.flush(60100);
  assert.equal(coverageDiagram.element.dataset.cycle, '1');
  assert.equal(coverageDiagram.element.dataset.playing, 'true');
  assert.equal(animation.frames.size, 1, 'The overview continues animating after its one-minute boundary.');
  assert.equal(animation.nodes.filter(node => node.attributes['data-call'] && node.parentNode).length,
    coverageCallsAt(coverageModel.duration + coverageModel.speed / 10).length,
    'Looping retains only the transmissions in the currently displayed history.');
  const beforeHidden = Number(coverageDiagram.element.dataset.time);
  assert.ok(beforeHidden > 0);
  animation.document.hidden = true; animation.document.emit('visibilitychange');
  assert.equal(animation.frames.size, 0, 'A hidden overview releases its animation frame.');
  animation.document.hidden = false; animation.document.emit('visibilitychange');
  assert.equal(animation.frames.size, 1, 'Returning to the visible overview resumes its animation.');
  animation.flush(200000);
  assert.equal(Number(coverageDiagram.element.dataset.time), beforeHidden,
    'Time spent in a hidden tab does not fast-forward the diagram.');
  animation.flush(200100);
  assert.ok(Number(coverageDiagram.element.dataset.time) > beforeHidden);
  animation.motion.matches = false; animation.motion.emit('change');
  assert.equal(animation.frames.size, 1);
  const beforeMotionChange = Number(coverageDiagram.element.dataset.time);
  animation.motion.matches = true; animation.motion.emit('change');
  assert.equal(animation.frames.size, 1, 'Changing the motion preference keeps the demonstration playing.');
  animation.flush(200200);
  assert.ok(Number(coverageDiagram.element.dataset.time) > beforeMotionChange);
  const lateFrame = [...animation.frames.values()][0];
  const beforeDismissal = coverageDiagram.element.dataset.time;
  diagramAbort.abort(); closeDiagram();
  assert.equal(animation.frames.size, 0);
  lateFrame(300000);
  assert.equal(coverageDiagram.element.dataset.time, beforeDismissal,
    'A frame already queued before dismissal cannot update the removed overview.');
  assert.ok(animation.observers.every(observer => !observer.observing));
  for (const owner of [animation.document, animation.windowValue, animation.motion]) {
    assert.ok([...owner.listeners.values()].every(listeners => listeners.size === 0),
      'Dismissal releases visibility, motion, and page lifecycle listeners.');
    owner.emit('visibilitychange'); owner.emit('change');
  }
  assert.equal(animation.frames.size, 0, 'A dismissed overview cannot restart from a later lifecycle event.');
  const { createTunersGuideData } = await load('guides/tuners-presentation.js');
  const tunerExamples = createTunersGuideData();
  const tunerApi = '/api/v1/admin/tuners';
  const initialTuners = await tunerExamples.requestJson(tunerApi);
  const exampleTuner = initialTuners.tuners[0];
  assert.equal(exampleTuner.channel_count, 2);
  exampleTuner.name = 'Changed outside the fixture';
  assert.equal((await tunerExamples.requestJson(tunerApi)).tuners[0].name, 'Airspy R2');
  const exampleTunerRow = async () => (await tunerExamples.requestJson(tunerApi)).tuners[0];
  const setTunerMode = state => tunerExamples.requestJson(`${tunerApi}/${exampleTuner.id}/state`,
    { method: 'PUT', body: { state } });
  await setTunerMode('setup');
  assert.deepEqual([(await exampleTunerRow()).operator_state, (await exampleTunerRow()).channel_count,
    (await exampleTunerRow()).stopped_channels.length], ['setup', 0, 2],
  'Entering actual Setup remembers two stopped sample channels.');
  await tunerExamples.requestJson(`${tunerApi}/${exampleTuner.id}/restore`, { method: 'POST' });
  assert.equal((await exampleTunerRow()).channel_count, 2);
  await setTunerMode('disabled');
  assert.deepEqual([(await exampleTunerRow()).operator_state, (await exampleTunerRow()).channel_count,
    (await exampleTunerRow()).spectrum_available], ['disabled', 0, false],
  'The sample Disabled mode turns off its diagnostic signal while retaining restartable channels.');
  await setTunerMode('setup');
  assert.equal((await exampleTunerRow()).stopped_channels.length, 2);
  await tunerExamples.requestJson(`${tunerApi}/${exampleTuner.id}/restore`, { method: 'POST' });
  assert.deepEqual([(await exampleTunerRow()).operator_state, (await exampleTunerRow()).channel_count], ['live', 2]);
  assert.equal((await createTunersGuideData().requestJson(tunerApi)).tuners[0].operator_state, 'live',
    'A new step starts with independent receiver state.');
  await assert.rejects(tunerExamples.requestJson(`${tunerApi}/${exampleTuner.id}/settings`,
    { method: 'PUT', body: { gain: 10 } }), /Unsupported tuner example request/,
  'An unsupported operation fails inside the sample transport.');
  const tunerFrames = [];
  const exampleConnection = tunerExamples.connectionFactory('tuner_diagnostics', { profile: 'spectrum' },
    { onFrame: frame => tunerFrames.push(frame) });
  await Promise.resolve();
  exampleConnection.close();
  assert.ok(tunerFrames.some(frame => frame.type === 1) && tunerFrames.some(frame => frame.type === 4 && frame.valueCount === 1024),
    'The actual spectrum receives fictional metadata and 1024-bin diagnostic frames.');
  const originalInterval = globalThis.setInterval;
  const openedExampleTimers = [];
  globalThis.setInterval = (...args) => {
    const timer = originalInterval(...args); openedExampleTimers.push(timer); return timer;
  };
  try {
    let closeOnOpen;
    closeOnOpen = tunerExamples.connectionFactory('tuner_diagnostics', { profile: 'spectrum' }, {
      onOpen: () => closeOnOpen.close(), onFrame() {}
    });
    await Promise.resolve();
    assert.equal(openedExampleTimers.length, 0,
      'Closing a fictional connection inside onOpen prevents its queued opener from creating a later interval.');
    const abortDuringFrame = new AbortController();
    const closingFrames = [];
    const closingTransport = createTunersGuideData({ signal: abortDuringFrame.signal });
    closingTransport.connectionFactory('tuner_diagnostics', { profile: 'spectrum' }, {
      onFrame: frame => { closingFrames.push(frame.type); abortDuringFrame.abort(); }
    });
    await Promise.resolve();
    assert.deepEqual(closingFrames, [1], 'Aborting during state delivery prevents the following FFT frame.');
    assert.equal(openedExampleTimers.length, 0,
      'Aborting inside a frame callback prevents a timer from being installed after cleanup.');
  } finally {
    openedExampleTimers.forEach(timer => clearInterval(timer));
    globalThis.setInterval = originalInterval;
  }
  const closedTunerExamples = new AbortController();
  const abortableTuners = createTunersGuideData({ signal: closedTunerExamples.signal });
  closedTunerExamples.abort();
  await assert.rejects(abortableTuners.requestJson(tunerApi), error => error.name === 'AbortError');
  assert.throws(() => abortableTuners.connectionFactory('tuner_diagnostics', {}, {}), error => error.name === 'AbortError');
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
  await require('./spectrum-guide-data.test.js').checkSpectrumGuideData(assets);
  await require('./live-guide-data.test.js').checkLiveGuideData(assets);
  await require('./main-guide-data.test.js').checkMainGuideData(assets);
  await require('./scanner-guide-data.test.js').checkScannerGuideData(assets);

  const mainTour = createGuideDefinition('dashboard', {
    documentValue: targetDocument,
    preparePlayer: () => { prepared.push('main-player'); return () => released.push('main-player'); },
    preparePage: async (view, section, context) => {
      prepared.push([view, section, context]);
      return () => released.push(section);
    }
  });
  const mainSections = ['overview', 'systems', 'reception', 'calls-tab', 'call-totals', 'call-history',
    'identities', 'activity-tab', 'event-type', 'activity-radios', 'inventory-tab', 'alias-list',
    'configured', 'unknown-tab', 'unknown'];
  assert.deepEqual(mainTour.steps.map(step => step.id), mainSections);
  assert.equal(mainTour.steps[0].items.length, 4, 'Main introduces its four real sections before numbered stops.');
  assert.equal(mainTour.steps[0].prepare, undefined, 'Main’s concept overview keeps the original page visible.');
  for (const step of mainTour.steps.slice(1)) {
    assert.deepEqual(targetNames(step), [`mn-${step.id}`]);
    assert.deepEqual(step.samples, [{ id: 'dashboard-workspace', value: { section: step.id } }]);
    const context = { registerLayer() {} };
    const release = await step.prepare(context);
    assert.deepEqual(prepared.splice(0), ['main-player', ['dashboard', step.id, context]]);
    release();
    assert.deepEqual(released.splice(0), [step.id, 'main-player']);
  }
  for (const [opener, destination] of [['calls-tab', 'call-totals'], ['activity-tab', 'event-type'],
    ['event-type', 'activity-radios'], ['inventory-tab', 'alias-list'], ['alias-list', 'configured'],
    ['unknown-tab', 'unknown']]) {
    assert.equal(mainSections.indexOf(opener) + 1, mainSections.indexOf(destination),
      'Every Main tab, event and Alias List destination follows its dedicated real opening control.');
  }
  assert.equal(mainTour.steps.at(-1).nextLabel, 'Finish');
  const restrictedMain = createGuideDefinition('dashboard', { canAccess: capability => capability !== 'radio' });
  assert.deepEqual(restrictedMain.steps.map(step => step.id),
    ['overview', 'systems', 'reception', 'calls-tab', 'call-totals', 'call-history']);
  assert.equal(restrictedMain.steps.at(-1).nextLabel, 'Finish');
  assert.equal(restrictedMain.steps[0].items.some(item => item.label === 'Alias Inventory'), false);
  const failedMain = createGuideDefinition('dashboard', {
    preparePlayer: () => () => released.push('main-player'),
    preparePage: () => { throw new Error('Main sample preparation failed'); }
  });
  await assert.rejects(failedMain.steps[1].prepare({}), /Main sample preparation failed/);
  assert.deepEqual(released.splice(0), ['main-player']);

  const scannerTour = createGuideDefinition('scanner', {
    documentValue: targetDocument,
    preparePlayer: () => { prepared.push('scanner-player'); return () => released.push('scanner-player'); },
    preparePage: async (view, section, context) => {
      prepared.push([view, section, context]);
      return () => released.push(section);
    }
  });
  const scannerSections = ['overview', 'lists-button', 'lists', 'lists-close', 'call', 'queue',
    'playback', 'hold', 'avoid', 'detail-level', 'details', 'settings-button', 'playback-order',
    'settings-close', 'avoids-button', 'avoids', 'avoids-close'];
  assert.deepEqual(scannerTour.steps.map(step => step.id), scannerSections);
  assert.equal(scannerTour.steps[0].title, 'Follow the traffic');
  assert.equal(scannerTour.steps[0].prepare, undefined,
    'Scanner begins with its concept while the real listening state remains visible.');
  const scannerStop = id => scannerTour.steps.find(step => step.id === id);
  assert.deepEqual(scannerStop('queue').paragraphs,
    ['When several calls arrive together, VCE reorders them in queue so you hear them in turn.']);
  assert.match(scannerStop('hold').paragraphs[0], /^H stands for Hold\./);
  assert.deepEqual(scannerStop('playback').items.map(item => item.label), ['Pause', 'Stop', 'Replay']);
  for (const step of scannerTour.steps.slice(1)) {
    assert.deepEqual(targetNames(step), [`sc-${step.id}`]);
    assert.deepEqual(step.samples, [{ id: 'scanner-workspace', value: { section: step.id } }]);
    const context = { registerLayer() {} };
    const release = await step.prepare(context);
    assert.deepEqual(prepared.splice(0), ['scanner-player', ['scanner', step.id, context]]);
    release();
    assert.deepEqual(released.splice(0), [step.id, 'scanner-player']);
  }
  for (const [opener, destination] of [['lists-button', 'lists'], ['detail-level', 'details'],
    ['settings-button', 'playback-order'], ['avoids-button', 'avoids']]) {
    assert.equal(scannerSections.indexOf(opener) + 1, scannerSections.indexOf(destination),
      'Scanner teaches each actual opening control before the destination it opens.');
  }
  for (const [returnStep, nextStep] of [['lists-close', 'call'], ['settings-close', 'avoids-button']]) {
    assert.equal(scannerSections.indexOf(returnStep) + 1, scannerSections.indexOf(nextStep),
      'An actual return control precedes the next Scanner workspace lesson.');
  }
  assert.equal(scannerTour.steps.at(-1).nextLabel, 'Finish');
  assert.equal(scannerTour.steps.at(-1).id, 'avoids-close',
    'The final dialog has a visible close-control lesson before completion.');
  const restrictedScanner = createGuideDefinition('scanner', { canAccess: capability => capability !== 'radio' });
  assert.deepEqual(restrictedScanner.steps.map(step => step.id), scannerSections.filter(id => id !== 'details'),
    'The tour preserves listening access without offering restricted radio-detail navigation.');
  const failedScanner = createGuideDefinition('scanner', {
    preparePlayer: () => () => released.push('scanner-player'),
    preparePage: () => { throw new Error('Scanner sample preparation failed'); }
  });
  await assert.rejects(failedScanner.steps[1].prepare({}), /Scanner sample preparation failed/);
  assert.deepEqual(released.splice(0), ['scanner-player']);
  const failedScannerCleanup = createGuideDefinition('scanner', {
    preparePlayer: () => () => released.push('scanner-player'),
    preparePage: () => () => { throw new Error('Scanner page cleanup failed'); }
  });
  const releaseFailedScanner = await failedScannerCleanup.steps[1].prepare({});
  assert.throws(releaseFailedScanner, /Scanner page cleanup failed/);
  assert.deepEqual(released.splice(0), ['scanner-player'],
    'Even a failing sample cleanup restores the player.');

  const liveTour = createGuideDefinition('live', {
    documentValue: targetDocument,
    preparePlayer: () => { prepared.push('live-player'); return () => released.push('live-player'); },
    preparePage: async (view, section, context) => {
      prepared.push([view, section, context]);
      return () => released.push(section);
    }
  });
  const liveSections = ['overview', 'views', 'activity', 'reception', 'select-channel', 'detail-views',
    'events', 'pause', 'event-filters', 'messages-tab', 'messages', 'channel-tab', 'channel', 'workspace-size', 'presentation'];
  assert.deepEqual(liveTour.steps.map(step => step.id), liveSections);
  for (const step of liveTour.steps) {
    if (!step.overview) assert.deepEqual(targetNames(step), [`lv-${step.id}`]);
    const context = { registerLayer() {} };
    const release = await step.prepare(context);
    assert.deepEqual(prepared.splice(0), ['live-player', ['live', step.id, context]]);
    release();
    assert.deepEqual(released.splice(0), [step.id, 'live-player']);
  }
  for (const [opener, destination] of [['select-channel', 'detail-views'], ['messages-tab', 'messages'],
    ['channel-tab', 'channel']]) {
    assert.equal(liveSections.indexOf(opener) + 1, liveSections.indexOf(destination),
      'Live tab and selection destinations follow their dedicated actual opening controls.');
  }
  assert.equal(liveTour.steps.at(-1).nextLabel, 'Finish');
  const failedLive = createGuideDefinition('live', {
    preparePlayer: () => () => released.push('live-player'),
    preparePage: () => { throw new Error('Live sample preparation failed'); }
  });
  await assert.rejects(failedLive.steps[0].prepare({}), /Live sample preparation failed/);
  assert.deepEqual(released.splice(0), ['live-player']);

  const spectrumTour = createGuideDefinition('tuner-spectrum', {
    documentValue: targetDocument,
    preparePlayer: () => { prepared.push('spectrum-player'); return () => released.push('spectrum-player'); },
    preparePage: async (view, section, context) => {
      prepared.push([view, section, context]);
      return () => released.push(section);
    }
  });
  const spectrumSections = ['overview', 'receiver', 'activity', 'measurements', 'zoom', 'pan', 'reset-zoom',
    'channel-marker', 'channel-details', 'waterfall', 'waterfall-details', 'pause', 'select-signal',
    'listen', 'lookup', 'add-channel', 'modes', 'display-options', 'display-settings', 'plot-settings', 'performance', 'display-close'];
  assert.deepEqual(spectrumTour.steps.map(step => step.id), spectrumSections,
    'Spectrum teaches actual instrument interactions while keeping service branches at entry points.');
  assert.equal(spectrumTour.steps[0].targets, undefined);
  for (const step of spectrumTour.steps) {
    if (!step.overview) assert.deepEqual(targetNames(step), [`bs-${step.id}`]);
    const context = { registerLayer() {} };
    const release = await step.prepare(context);
    assert.deepEqual(prepared.splice(0), ['spectrum-player', ['tuner-spectrum', step.id, context]],
      'The hardware-free sample and minimized player use the same owned preparation lifecycle.');
    release();
    assert.deepEqual(released.splice(0), [step.id, 'spectrum-player']);
  }
  const spectrumStop = id => spectrumTour.steps.find(step => step.id === id);
  for (const [opener, destination] of [['zoom', 'pan'], ['channel-marker', 'channel-details'],
    ['waterfall', 'waterfall-details'], ['display-options', 'display-settings'],
    ['display-settings', 'plot-settings'], ['plot-settings', 'performance'], ['performance', 'display-close']]) {
    assert.equal(spectrumTour.steps.indexOf(spectrumStop(opener)) + 1,
      spectrumTour.steps.indexOf(spectrumStop(destination)),
      'Every instrument detail destination and return follows its visible actual entry point.');
  }
  assert.ok(spectrumStop('zoom').paragraphs.join(' ').includes('mouse wheel'));
  assert.ok(spectrumStop('pan').paragraphs.join(' ').includes('click and drag'));
  assert.ok(spectrumStop('modes').paragraphs.join(' ').includes('full view'));
  assert.equal(spectrumTour.steps.at(-1).nextLabel, 'Finish');
  const restrictedSpectrum = createGuideDefinition('tuner-spectrum', { canAccess: () => false });
  assert.deepEqual(restrictedSpectrum.steps.map(step => step.id),
    ['overview', 'receiver', 'activity', 'measurements', 'zoom', 'pan', 'reset-zoom', 'waterfall', 'waterfall-details',
      'pause', 'select-signal', 'modes', 'display-options', 'display-settings', 'plot-settings', 'performance', 'display-close'],
    'Restricted accounts are not taught audio, RadioReference or channel actions they cannot use.');
  const failedSpectrum = createGuideDefinition('tuner-spectrum', {
    preparePlayer: () => () => released.push('spectrum-player'),
    preparePage: () => { throw new Error('Spectrum sample preparation failed'); }
  });
  await assert.rejects(failedSpectrum.steps[0].prepare({}), /Spectrum sample preparation failed/);
  assert.deepEqual(released.splice(0), ['spectrum-player'], 'A failed overview sample releases its player state.');
  const failedSpectrumCleanup = createGuideDefinition('tuner-spectrum', {
    preparePlayer: () => () => released.push('spectrum-player'),
    preparePage: () => () => { throw new Error('Spectrum cleanup failed'); }
  });
  const failRelease = await failedSpectrumCleanup.steps[1].prepare({});
  assert.throws(failRelease, /Spectrum cleanup failed/);
  assert.deepEqual(released.splice(0), ['spectrum-player'], 'Player restoration survives a sample cleanup failure.');

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

  const firstRadioReferenceVisit = makeHarness({ context: { view: 'radioreference' }, storage: {
    getItem: key => /:welcome:1$|:radioreference:[12]$/.test(key) ? '1' : null,
    setItem() {}
  } });
  await firstRadioReferenceVisit.controller.pageReady();
  assert.equal(firstRadioReferenceVisit.shown.at(-1).options.id, 'radioreference');
  assert.equal(firstRadioReferenceVisit.shown.at(-1).options.version, 3,
    'An earlier RadioReference placeholder cannot suppress its authored tour.');
  assert.equal(firstRadioReferenceVisit.shown.at(-1).options.steps[0].overview, true);
  await firstRadioReferenceVisit.controller.pageReady();
  assert.equal(firstRadioReferenceVisit.shown.length, 1,
    'A successful RadioReference render cannot stack another automatic tour.');
  firstRadioReferenceVisit.finish();
  await firstRadioReferenceVisit.controller.openPage();
  assert.equal(firstRadioReferenceVisit.shown.at(-1).options.id, 'radioreference',
    'The question-mark action replays the RadioReference tour after completion.');
  firstRadioReferenceVisit.controller.destroy();

  const completedRadioReference = makeHarness({ context: { view: 'radioreference' }, storage: {
    getItem: key => /:welcome:1$|:radioreference:3$/.test(key) ? '1' : null,
    setItem() {}
  } });
  await completedRadioReference.controller.pageReady();
  assert.equal(completedRadioReference.shown.length, 0,
    'Completing the current RadioReference tour keeps future visits quiet.');
  const restrictedRadioReference = makeHarness({ context: { view: 'radioreference',
    canView: view => view !== 'radioreference', guide: 'radioreference' } });
  assert.equal(await restrictedRadioReference.controller.pageReady(), false);
  assert.equal(restrictedRadioReference.shown.length, 0,
    'A requested RadioReference tour preserves the production route’s access restrictions.');

  const firstMainVisit = makeHarness({ context: { view: 'dashboard' }, storage: {
    getItem: key => /:welcome:1$|:dashboard:[12]$/.test(key) ? '1' : null,
    setItem() {}
  } });
  await firstMainVisit.controller.pageReady();
  assert.equal(firstMainVisit.shown.at(-1).options.id, 'dashboard');
  assert.equal(firstMainVisit.shown.at(-1).options.version, 3,
    'The earlier Main placeholder does not suppress its authored tour.');
  firstMainVisit.finish();
  firstMainVisit.controller.destroy();
  const completedMain = makeHarness({ context: { view: 'dashboard' }, storage: {
    getItem: key => /:welcome:1$|:dashboard:3$/.test(key) ? '1' : null,
    setItem() {}
  } });
  assert.equal(await completedMain.controller.pageReady(), false);
  completedMain.controller.destroy();
  const deniedMain = makeHarness({ context: { view: 'dashboard', guide: 'dashboard', canView: view => view !== 'dashboard' } });
  assert.equal(await deniedMain.controller.pageReady(), false);
  assert.equal(deniedMain.shown.length, 0);
  deniedMain.controller.destroy();

  const firstScannerVisit = makeHarness({ context: { view: 'scanner' }, storage: {
    getItem: key => /:welcome:1$|:scanner:[12]$/.test(key) ? '1' : null,
    setItem() {}
  } });
  await firstScannerVisit.controller.pageReady();
  assert.equal(firstScannerVisit.shown.at(-1).options.id, 'scanner');
  assert.equal(firstScannerVisit.shown.at(-1).options.version, 3,
    'The earlier Scanner placeholder does not suppress its authored tour.');
  firstScannerVisit.finish();
  firstScannerVisit.controller.destroy();
  const completedScanner = makeHarness({ context: { view: 'scanner' }, storage: {
    getItem: key => /:welcome:1$|:scanner:3$/.test(key) ? '1' : null,
    setItem() {}
  } });
  assert.equal(await completedScanner.controller.pageReady(), false);
  assert.equal(await completedScanner.controller.openPage(), true,
    'Help can replay a completed Scanner tour.');
  completedScanner.controller.destroy();
  const deniedScanner = makeHarness({ context: { view: 'scanner', guide: 'scanner', canView: view => view !== 'scanner' } });
  assert.equal(await deniedScanner.controller.pageReady(), false);
  assert.equal(deniedScanner.shown.length, 0);
  deniedScanner.controller.destroy();

  const firstLiveVisit = makeHarness({ context: { view: 'live' }, storage: {
    getItem: key => /:welcome:1$|:live:[12]$/.test(key) ? '1' : null,
    setItem() {}
  } });
  await firstLiveVisit.controller.pageReady();
  assert.equal(firstLiveVisit.shown.at(-1).options.id, 'live');
  assert.equal(firstLiveVisit.shown.at(-1).options.version, 3,
    'The earlier Live placeholder does not suppress its authored tour.');
  firstLiveVisit.finish();
  firstLiveVisit.controller.destroy();
  const completedLive = makeHarness({ context: { view: 'live' }, storage: {
    getItem: key => /:welcome:1$|:live:3$/.test(key) ? '1' : null,
    setItem() {}
  } });
  assert.equal(await completedLive.controller.pageReady(), false);
  completedLive.controller.destroy();
  const deniedLive = makeHarness({ context: { view: 'live', guide: 'live', canView: view => view !== 'live' } });
  assert.equal(await deniedLive.controller.pageReady(), false);
  assert.equal(deniedLive.shown.length, 0);
  deniedLive.controller.destroy();

  const firstSpectrumVisit = makeHarness({ context: { view: 'tuner-spectrum' }, storage: {
    getItem: key => key.endsWith(':welcome:1') || key.endsWith(':tuner-spectrum:3') ? '1' : null,
    setItem() {}
  } });
  await firstSpectrumVisit.controller.pageReady();
  assert.equal(firstSpectrumVisit.shown.at(-1).options.id, 'tuner-spectrum');
  assert.equal(firstSpectrumVisit.shown.at(-1).options.version, 4,
    'The earlier short Spectrum tour does not suppress its revised interaction walkthrough.');

  const firstStreamingVisit = makeHarness({ context: { view: 'streaming', canAccess: () => false }, storage: {
    getItem: key => /:welcome:1$|:streaming:[12]$/.test(key) ? '1' : null,
    setItem() {}
  } });
  await firstStreamingVisit.controller.pageReady();
  assert.equal(firstStreamingVisit.shown.at(-1).options.id, 'streaming');
  assert.equal(firstStreamingVisit.shown.at(-1).options.version, 3,
    'Completing the old Streaming placeholder does not suppress its first authored tour.');
  assert.deepEqual(firstStreamingVisit.shown.at(-1).options.steps.map(step => step.id),
    streamingOnly.steps.map(step => step.id),
    'The controller forwards account capabilities to the actual page guide.');
  firstStreamingVisit.finish();
  firstStreamingVisit.controller.destroy();
  const completedStreaming = makeHarness({ context: { view: 'streaming' }, storage: {
    getItem: key => /:welcome:1$|:streaming:3$/.test(key) ? '1' : null,
    setItem() {}
  } });
  await completedStreaming.controller.pageReady();
  assert.equal(completedStreaming.shown.length, 0, 'Completing the Streaming tour keeps subsequent visits quiet.');
  await completedStreaming.controller.openPage();
  assert.equal(completedStreaming.shown.at(-1).options.id, 'streaming',
    'The question-mark action can replay the completed Streaming tour.');
  completedStreaming.controller.destroy();

  const firstTunerVisit = makeHarness({ context: { view: 'tuners' }, storage: {
    getItem: key => /:welcome:1$|:tuners:[12]$/.test(key) ? '1' : null, setItem() {}
  } });
  await firstTunerVisit.controller.pageReady();
  assert.equal(firstTunerVisit.shown.at(-1).options.id, 'tuners');
  assert.equal(firstTunerVisit.shown.at(-1).options.version, 3,
    'The old Tuners placeholder does not suppress its first authored tour.');
  assert.equal(firstTunerVisit.shown.at(-1).options.steps[0].diagram, 'tuner-coverage');
  firstTunerVisit.finish();
  firstTunerVisit.controller.destroy();
  const completedTuners = makeHarness({ context: { view: 'tuners' }, storage: {
    getItem: key => /:welcome:1$|:tuners:3$/.test(key) ? '1' : null, setItem() {}
  } });
  await completedTuners.controller.pageReady();
  assert.equal(completedTuners.shown.length, 0);
  await completedTuners.controller.openPage();
  assert.equal(completedTuners.shown.at(-1).options.id, 'tuners');
  completedTuners.controller.destroy();
  const forbiddenTuners = makeHarness({ context: { view: 'tuners', guide: 'tuners', canView: view => view !== 'tuners' } });
  assert.equal(await forbiddenTuners.controller.pageReady(), false);
  assert.equal(forbiddenTuners.shown.length, 0, 'The Tuners guide preserves actual route access restrictions.');

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
  dismissedWelcome.context.view = 'recordings';
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
  dismissedWelcome.context.view = 'recordings';
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
  const replayRadioReference = makeHarness({ context: { view: 'dashboard', guide: 'radioreference' } });
  await replayRadioReference.controller.pageReady();
  assert.deepEqual(replayRadioReference.navigations, ['radioreference']);
  await replayRadioReference.controller.pageReady();
  assert.equal(replayRadioReference.shown.at(-1).options.id, 'radioreference',
    'A named RadioReference URL navigates before presenting its actual-interface sample tour.');
  assert.equal(replayRadioReference.context.guide, null);
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
