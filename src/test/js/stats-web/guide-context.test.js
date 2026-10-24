'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function checkGuideContext(assetsRoot) {
  const assets = path.resolve(assetsRoot || process.argv[2] || 'stats-web/assets');
  const [{ contextualTargets }, { createGuideDefinition }] = await Promise.all([
    import(pathToFileURL(path.join(assets, 'guides/context.js')).href),
    import(pathToFileURL(path.join(assets, 'guides/catalog.js')).href)
  ]);
  const element = (selectors = [], parent = null, children = []) => ({
    parent, selectors,
    closest(selector) {
      for (let node = this; node; node = node.parent) {
        if (selector.split(',').some(value => node.selectors.includes(value.trim()))) return node;
      }
      return null;
    },
    querySelectorAll(selector) { return children.filter(child => child.selectors.includes(selector)); }
  });

  const card = element(['.call-card']);
  const first = element(['.details'], card), second = element(['.actions'], card);
  let focus = [first, second];
  assert.equal(contextualTargets(() => focus), undefined,
    'A step without a declared context never guesses an enclosing surface.');
  const readCard = contextualTargets(() => focus, '.call-card');
  assert.deepEqual(readCard(), [card], 'Several focus controls expose their owning card once.');
  assert.deepEqual(contextualTargets(() => focus, '.unrelated-panel')(), [],
    'Missing owners do not fall back to the page, viewport, or another container.');
  assert.deepEqual(contextualTargets(null, '.call-card')(), []);
  const otherCard = element(['.call-card']);
  focus = [element(['.details'], otherCard)];
  assert.deepEqual(readCard(), [otherCard],
    'Contexts resolve current production nodes after a rerender rather than retaining stale nodes.');
  assert.deepEqual(contextualTargets([first], '.call-card')(), [card]);

  const panel = element(['.options-panel']);
  const options = element(['.options'], null, [panel]);
  const summary = element(['summary'], options);
  assert.deepEqual(contextualTargets(() => [summary], {
    owner: '.options', surfaces: '.options-panel'
  })(), [options, panel], 'An explicitly owned floating panel remains visible beside its trigger.');

  const contexts = [
    ['recordings', 'details-button', 'rec-details-button', '.recordings-call'],
    ['recordings', 'actions-button', 'rec-actions-button', '.recordings-call'],
    ['recordings', 'targets', 'rec-targets', '.read-only-modal'],
    ['recordings', 'details', 'rec-details', '.read-only-modal'],
    ['recordings', 'details-close', 'rec-details-close', '.read-only-modal'],
    ['recordings', 'playback', 'rec-playback', '.read-only-modal'],
    ['recordings', 'player-button', 'rec-player-button', '.audio-dock'],
    ['recordings', 'click-mode', 'rec-click-mode', '.audio-dock'],
    ['aliases', 'alias-button', 'aliases-open-alias', 'tr'],
    ['aliases', 'alias-editor', 'aliases-editor-tabs', '.read-only-modal'],
    ['aliases', 'defaults-new-button', 'aliases-defaults-new-tab', '.read-only-modal'],
    ['channel-setup', 'channel-choice-close', 'channel-choice-close', '.read-only-modal'],
    ['scan-lists', 'add-alias-list', 'scan-lists-add-list-dialog', '.read-only-modal'],
    ['scan-lists', 'listening-controls', 'player-lists', '.audio-dock'],
    ['radioreference', 'channel-review-button', 'rr-channel-review-button', '.read-only-modal'],
    ['streaming', 'manage-button', 'st-manage-button', '.streaming-destination-card'],
    ['streaming', 'sections', 'st-sections', '.read-only-modal'],
    ['tuners', 'enter-setup', 'tn-enter-setup', '.read-only-modal'],
    ['tuner-spectrum', 'listen', 'bs-listen', '.spectrum-browse-control-rail'],
    ['live', 'pause', 'lv-pause', '.live-details'],
    ['dashboard', 'reception', 'mn-reception', '[data-directory-card-key]'],
    ['scanner', 'playback-order', 'sc-playback-order', '.read-only-modal'],
    ['scanner', 'settings-close', 'sc-settings-close', '.read-only-modal']
  ];
  for (const [page, id, marker, ownerSelector] of contexts) {
    const owner = element([ownerSelector]);
    const control = element([], owner);
    const documentValue = { querySelector: () => null, querySelectorAll: selector =>
      selector === `[data-guide-target="${marker}"]` ? [control] : [] };
    const definition = createGuideDefinition(page, { documentValue, canView: () => true, canAccess: () => true });
    const step = definition.steps.find(value => value.id === id);
    assert.deepEqual(step.targets(), [control], `${page}/${id} retains its precise production focus target.`);
    assert.deepEqual(step.contextTargets(), [owner], `${page}/${id} exposes its declared owning surface.`);
    assert.equal(definition.steps[0].contextTargets, undefined, `${page} keeps its overview free of spotlights.`);
  }
  const recordings = createGuideDefinition('recordings');
  assert.equal(recordings.version, 4, 'New surface introductions receive their own completed-guide version.');
  assert.deepEqual(recordings.steps.slice(1).map(step => step.id), [
    'targets-button', 'targets', 'time-range', 'search', 'transcript-search', 'transcript-submit',
    'call-card', 'details-button', 'details', 'details-close', 'actions-button', 'playback',
    'player-overview', 'player-button', 'click-mode'
  ], 'Context preserves the approved Recordings workflow and dedicated opening and return stops.');
  assert.deepEqual(recordings.steps.find(step => step.id === 'details-button').paragraphs,
    ['Open Call details to inspect this transmission.'], 'The visual context update preserves the approved copy.');
}

module.exports = { checkGuideContext };
if (require.main === module) checkGuideContext().catch(error => { console.error(error); process.exitCode = 1; });
