'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function checkGuidePresentation(assetsRoot) {
  const assets = path.resolve(assetsRoot || process.argv[2] || 'stats-web/assets');
  const [{ pageGuidePresentation }, { createGuideDefinition }] = await Promise.all([
    import(pathToFileURL(path.join(assets, 'guides/presentation.js')).href),
    import(pathToFileURL(path.join(assets, 'guides/catalog.js')).href)
  ]);
  const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
  const calls = [];
  const context = { signal: new AbortController().signal, isCurrent: () => true };
  const windowValue = { scrollX: 18, scrollY: 640, location: { href: 'https://example.invalid/?view=dashboard' },
    scrollTo: options => calls.push(['scroll', options]) };
  const installWindow = value => Object.defineProperty(globalThis, 'window', {
    configurable: true, writable: true, value
  });
  try {
    installWindow(windowValue);
    const presentation = pageGuidePresentation('dashboard', 'calls', async options => {
      calls.push(['player', options]);
      windowValue.scrollY = 0;
      return () => calls.push('release-player');
    }, async (view, section, receivedContext) => {
      calls.push(['page', view, section, receivedContext]);
      return () => calls.push('release-page');
    }, { restoreScroll: true });
    assert.deepEqual(calls, [], 'Declaring a step leaves the real page and player untouched.');
    assert.deepEqual(presentation.samples, [{ id: 'dashboard-workspace', value: { section: 'calls' } }]);
    const release = await presentation.prepare(context);
    assert.deepEqual(calls.splice(0), [
      ['player', { minimized: true }], ['page', 'dashboard', 'calls', context]
    ], 'The player is prepared before the page using the original cancellation context.');
    release();
    assert.deepEqual(calls.splice(0), ['release-page', 'release-player',
      ['scroll', { left: 18, top: 640, behavior: 'instant' }]],
    'Page then player are restored before the scroll position captured ahead of player minimization.');

    const pageFailure = new Error('Page preparation failed');
    const failed = pageGuidePresentation('dashboard', 'calls', () => () => calls.push('release-player'),
      async () => { throw pageFailure; }, { restoreScroll: true });
    await assert.rejects(failed.prepare(context), error => error === pageFailure);
    assert.deepEqual(calls.splice(0), ['release-player',
      ['scroll', { left: 18, top: 0, behavior: 'instant' }]],
    'Asynchronous page failure releases the owned player and restores scroll.');

    const cleanupFailure = new Error('Page cleanup failed');
    const playerCleanupFailure = new Error('Player cleanup failed');
    for (const failPlayer of [false, true]) {
      const broken = pageGuidePresentation('dashboard', 'calls', () => () => {
        calls.push('release-player');
        if (failPlayer) throw playerCleanupFailure;
      }, () => () => { calls.push('release-page'); throw cleanupFailure; }, { restoreScroll: true });
      const releaseBroken = await broken.prepare(context);
      assert.throws(releaseBroken, error => error === (failPlayer ? playerCleanupFailure : cleanupFailure));
      assert.deepEqual(calls.splice(0), ['release-page', 'release-player',
        ['scroll', { left: 18, top: 0, behavior: 'instant' }]],
      'Both resources and scroll are restored even if an earlier cleanup throws.');
    }

    const navigation = pageGuidePresentation('dashboard', 'calls', () => () => calls.push('release-player'),
      () => () => calls.push('release-page'), { restoreScroll: true });
    const releaseNavigation = await navigation.prepare(context);
    windowValue.location.href = 'https://example.invalid/?view=recordings';
    releaseNavigation();
    assert.deepEqual(calls.splice(0), ['release-page', 'release-player'],
      'Leaving the original URL preserves the destination page scroll position.');

    Object.defineProperty(globalThis, 'window', { configurable: true,
      get: () => { throw new Error('Scroll preservation was not requested'); } });
    const noScroll = pageGuidePresentation('aliases', 'lists', () => () => calls.push('release-player'),
      () => () => calls.push('release-page'));
    (await noScroll.prepare(context))();
    assert.deepEqual(calls.splice(0), ['release-page', 'release-player'],
      'Ordinary page presentations never read or change window scroll.');
    (await pageGuidePresentation('aliases', 'lists').prepare(context))();

    installWindow(windowValue);
    for (const view of ['aliases', 'scan-lists', 'radioreference', 'streaming', 'tuners',
      'tuner-spectrum', 'live', 'dashboard', 'scanner', 'recordings']) {
      const definition = createGuideDefinition(view, {
        preparePlayer: () => () => calls.push('release-player'),
        preparePage: (receivedView, section, receivedContext) => {
          assert.equal(receivedView, view);
          assert.equal(receivedContext, context);
          return () => { calls.push('release-page'); throw cleanupFailure; };
        }
      });
      const step = definition.steps.find(step => !step.overview);
      assert.deepEqual(step.samples, [{ id: `${view}-workspace`, value: { section: step.id } }]);
      const releaseStep = await step.prepare(context);
      assert.throws(releaseStep, error => error === cleanupFailure);
      const scrollCalls = ['dashboard', 'scanner', 'recordings'].includes(view) ?
        [['scroll', { left: 18, top: 0, behavior: 'instant' }]] : [];
      assert.deepEqual(calls.splice(0), ['release-page', 'release-player', ...scrollCalls],
        `${view} restores the player even when its page cleanup fails.`);
    }
  } finally {
    if (originalWindow) Object.defineProperty(globalThis, 'window', originalWindow);
    else delete globalThis.window;
  }
}

module.exports = { checkGuidePresentation };
if (require.main === module) checkGuidePresentation().catch(error => { console.error(error); process.exitCode = 1; });
