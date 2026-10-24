'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function checkGuideAutostart(assetsRoot = 'stats-web/assets') {
  const { createGuideController } = await import(pathToFileURL(path.resolve(assetsRoot, 'guides/controller.js')).href);
  const tick = () => new Promise(resolve => setImmediate(resolve));
  function harness({ enabled = true, view = 'aliases', welcomed = true, save } = {}) {
    const context = { account: 'alice', dismissalScope: 'account-1-100', authenticated: true,
      isDesktop: true, canPresent: true, canSetup: true, view, automaticPageGuides: enabled };
    const history = { ...(welcomed ? { welcome: 1 } : {}) }, shown = [], writes = [], navigations = [];
    const controller = createGuideController({ ui: {}, documentValue: null, getContext: () => context,
      storage: { getItem() { return null; }, setItem() {} },
      fetchDocument: async (_url, options) => {
        if (options.method === 'PUT') {
          for (const [id, version] of Object.entries(JSON.parse(options.body).dismissed))
            history[id] = Math.max(history[id] || 0, version);
        }
        return { ok: true, status: 200, json: async () => ({ data: { version: 1, dismissed: { ...history } } }) };
      },
      setAutomaticPageGuides: async value => {
        writes.push(value);
        if (save) await save(value);
        context.automaticPageGuides = value;
      },
      consumeGuide: () => { context.guide = null; },
      navigate: page => { navigations.push(page); context.view = page; },
      openGuide: (_ui, options) => {
        const presentation = { options, closed: false, ready: Promise.resolve(true),
          isOpen() { return !this.closed; }, close() { this.closed = true; } };
        shown.push(presentation); return presentation;
      }
    });
    const finish = () => { shown.at(-1).closed = true; shown.at(-1).options.onComplete(); };
    return { controller, context, history, shown, writes, navigations, finish };
  }

  const enabled = harness();
  assert.equal(await enabled.controller.pageReady(), true);
  assert.equal(enabled.shown.at(-1).options.id, 'aliases');
  enabled.controller.destroy();

  for (const enabled of [false, null]) {
    const skipped = harness({ enabled });
    assert.equal(await skipped.controller.pageReady(), false);
    assert.equal(skipped.shown.length, 0, 'Opt-out and unavailable account preferences prevent automatic page guides.');
    assert.deepEqual(skipped.history, { welcome: 1 }, 'Skipping never marks unseen guides as dismissed.');
    assert.equal(await skipped.controller.openPage(), true, 'The ? button remains available.');
    skipped.controller.close(); skipped.context.guide = 'aliases';
    assert.equal(await skipped.controller.pageReady(), true, 'An explicit guide URL remains available.');
    skipped.controller.destroy();
  }

  for (const enabled of [true, false]) {
    const admin = harness({ enabled, view: 'admin' });
    assert.equal(await admin.controller.pageReady(), false, 'Administration is never automatic, even for a new account.');
    assert.equal(await admin.controller.openPage(), true);
    assert.equal(admin.shown.at(-1).options.id, 'admin');
    admin.controller.close(); admin.context.guide = 'admin';
    assert.equal(await admin.controller.pageReady(), true);
    admin.controller.destroy();
  }

  const welcome = harness({ welcomed: false, view: 'channel-setup' });
  assert.equal(await welcome.controller.pageReady(), true);
  const first = welcome.shown.at(-1).options;
  assert.equal(first.id, 'welcome');
  assert.equal(first.steps[0].pageGuidePreference, true, 'The choice belongs on the very first Welcome card.');
  assert.equal(first.pageGuidePreference.read(), true);
  await first.pageGuidePreference.save(false);
  assert.deepEqual(welcome.writes, [false]);
  assert.equal(first.steps.at(-1).nextLabel, 'Finish');
  assert.equal(first.steps.at(-1).paragraphs.some(copy => copy.includes('adding your first channel')), false);
  welcome.finish(); await tick();
  assert.equal(welcome.shown.length, 1, 'Welcome completion honors the choice before handing off to Channel Setup.');
  welcome.context.view = 'tuners';
  assert.equal(await welcome.controller.pageReady(), false);
  assert.equal(welcome.navigations.length, 0);
  assert.equal(welcome.history['channel-setup'], undefined);
  await welcome.controller.openWelcome();
  const replay = welcome.shown.at(-1).options;
  assert.equal(replay.pageGuidePreference.read(), false);
  await replay.pageGuidePreference.save(true);
  assert.equal(replay.steps.at(-1).nextLabel, 'Set up channels');
  welcome.controller.close();
  assert.equal(await welcome.controller.pageReady(), true, 'Re-enabling preserves the unseen page guide for its next visit.');
  assert.equal(welcome.shown.at(-1).options.id, 'tuners');
  welcome.controller.destroy();

  const failed = harness({ welcomed: false, save: async () => { throw new Error('Unavailable'); } });
  await failed.controller.pageReady();
  await assert.rejects(failed.shown.at(-1).options.pageGuidePreference.save(false), /Unavailable/);
  assert.equal(failed.context.automaticPageGuides, true, 'An unconfirmed save keeps the previous account choice.');
  failed.controller.destroy();

  let release;
  const pending = harness({ welcomed: false, save: () => new Promise(resolve => { release = resolve; }) });
  await pending.controller.pageReady();
  const stale = pending.shown.at(-1).options.pageGuidePreference;
  const saving = stale.save(false);
  pending.context.account = 'bob'; pending.context.dismissalScope = 'account-2-200';
  pending.controller.close(); release();
  await assert.rejects(saving, /account changed/);
  assert.equal(stale.read(), null, 'A stale first card cannot read the new account preference.');
  assert.equal(pending.shown.length, 1);
  pending.controller.destroy();
}

module.exports = { checkGuideAutostart };
if (require.main === module) checkGuideAutostart(process.argv[2]).catch(error => { console.error(error); process.exitCode = 1; });
