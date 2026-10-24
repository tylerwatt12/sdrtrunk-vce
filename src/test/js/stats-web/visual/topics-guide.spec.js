'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const guide = page => page.locator('.ui-setup-guide[data-guide-id="topics"]');
const steps = ['read-alerts', 'recent-alerts', 'notifications'];
const memoryKey = 'sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:topics:3';
const evidence = process.env.TOPICS_GUIDE_EVIDENCE || path.resolve(__dirname, '../../../../../build/playwright-results/topics-guide-evidence');
const nonGuideStorage = page => page.evaluate(() => Object.fromEntries(Object.entries(localStorage)
  .filter(([key]) => !key.startsWith('sdrtrunk-vce-guide:'))));
const realTopics = [
  { id: 'real-fire', name: 'Existing fire activity', description: 'Saved local fire notifications.', visible: true, enabled: true },
  { id: 'real-health', name: 'Existing receiver health', description: 'Saved local receiver notifications.', visible: true, enabled: true }
];
const realEvents = realTopics.map((topic, index) => ({ id: `real-alert-${index}`, topic_id: topic.id,
  title: index ? 'Saved receiver event' : 'Saved fire event', body: 'An existing alert remains available.', created_at_ms: 1792800000000 - index * 60000 }));

async function installTopics(page, { theme = 'light', firstVisit = false, connected = false, disabled = false, empty = false } = {}) {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const fixture = await installSiteStyleApplication(page, theme, { revision: 1, tables: [] }, { guides: firstVisit });
  const effects = { writes: [], samples: [], subscriptions: [], sockets: [] };
  page.on('request', request => {
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/v1/')) return;
    if (!['GET', 'HEAD'].includes(request.method()) && url.pathname !== '/api/v1/live/multiplex/control')
      effects.writes.push(`${request.method()} ${url.pathname}`);
    if (/guide-fire-activity|guide-receiver-health|guide-topic|guide-alert/.test(decodeURIComponent(url.pathname + url.search + (request.postData() || ''))))
      effects.samples.push(`${request.method()} ${url.pathname}`);
    if (/\/alerts\/(subscriptions|enrollments)/.test(url.pathname)) effects.subscriptions.push(`${request.method()} ${url.pathname}`);
  });
  page.on('websocket', socket => effects.sockets.push(socket.url()));
  await page.addInitScript(({ firstVisit, connected, memoryKey }) => {
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:welcome:1', '1');
    for (const version of [1, 2]) localStorage.setItem(`sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:topics:${version}`, '1');
    if (!firstVisit) localStorage.setItem(memoryKey, '1');
    if (connected) localStorage.setItem('vce-alert-device-v1', JSON.stringify({
      id: 'existing-test-device', manage_token: 'existing-test-token', mode: 'proxy', status: 'connected'
    }));
    window.topicsGuideEffects = { prompts: 0, workers: 0, audio: 0 };
    if (window.Notification) Notification.requestPermission = () => {
      window.topicsGuideEffects.prompts++; return Promise.resolve('denied');
    };
    if (navigator.serviceWorker) navigator.serviceWorker.register = () => {
      window.topicsGuideEffects.workers++; return Promise.reject(new Error('Unexpected worker registration'));
    };
    const play = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function (...args) {
      window.topicsGuideEffects.audio++; return play.apply(this, args);
    };
  }, { firstVisit, connected, memoryKey });
  await page.route('**/api/v1/alerts/**', route => {
    const url = new URL(route.request().url());
    const fulfill = data => route.fulfill({ json: { data } });
    if (url.pathname === '/api/v1/alerts/topics') return fulfill({ enabled: !disabled, mode: 'proxy',
      push_enabled: true, topics: empty ? [] : realTopics });
    if (url.pathname === '/api/v1/alerts/history') {
      const items = empty ? [] : realEvents.filter(event => !url.searchParams.get('topic_id') || event.topic_id === url.searchParams.get('topic_id'));
      return fulfill({ items, total: items.length, limit: 30, offset: 0 });
    }
    return route.fulfill({ status: 501, json: { error: { message: 'Unexpected Topics request' } } });
  });
  return { fixture, effects };
}

async function openTopics(page, options = {}) {
  const state = await installTopics(page, options);
  await page.goto(`/app.html?view=topics${options.replay === false ? '' : '&guide=topics'}`);
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  return state;
}
async function start(page) {
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await expect(guide(page)).toContainText('Topics group alerts about radio activity and receiver conditions.');
  await expect(guide(page).locator('.ui-setup-guide-counter')).toHaveText('Overview');
  await expect(page.locator('.ui-setup-guide-highlight, [data-guide-sample]')).toHaveCount(0);
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).click();
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'read-alerts');
}
async function next(page, id) {
  await guide(page).getByRole('button', { name: id === 'notifications' ? 'Finish' : 'Next', exact: true }).click();
}
async function advanceTo(page, destination) {
  for (const id of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    if (id === destination) return;
    await next(page, id);
  }
}
async function screenshot(page, name) {
  fs.mkdirSync(evidence, { recursive: true });
  await page.screenshot({ path: path.join(evidence, name + '.png') });
}
function expectIsolated({ fixture, effects }) {
  expect(effects.writes).toEqual([]); expect(effects.samples).toEqual([]);
  expect(effects.subscriptions).toEqual([]); expect(effects.sockets).toEqual([]);
  expect(fixture.pageErrors).toEqual([]); expect(fixture.unexpected).toEqual([]);
}
async function expectNoEffects(page) {
  expect(await page.evaluate(() => window.topicsGuideEffects)).toEqual({ prompts: 0, workers: 0, audio: 0 });
}

async function expectFocus(page, id) {
  const selector = `[data-guide-target="topic-${id}"]`;
  const target = page.locator(selector);
  await expect(target).toBeVisible();
  await expect.poll(() => target.evaluate((focus, id) => {
    const rect = element => element.getBoundingClientRect();
    const contains = (outer, inner) => outer.left <= inner.left + 1 && outer.top <= inner.top + 1 &&
      outer.right >= inner.right - 1 && outer.bottom >= inner.bottom - 1;
    const focusBox = rect(focus);
    const owner = id === 'read-alerts' ? focus.closest('.alerts-topic-card') : id === 'notifications' ?
      focus.closest('.alerts-public-sidebar > header') : focus;
    const ownerBox = rect(owner), coach = rect(document.querySelector('.ui-setup-guide'));
    const dimmer = document.querySelector('.ui-setup-guide-dimmer');
    const maskId = dimmer.getAttribute('mask').match(/#([^)]*)/)[1];
    const holes = [...document.getElementById(maskId).querySelectorAll('.ui-setup-guide-mask-hole')].map(hole => {
      const left = Number(hole.getAttribute('x')), top = Number(hole.getAttribute('y'));
      return { left, top, right: left + Number(hole.getAttribute('width')), bottom: top + Number(hole.getAttribute('height')) };
    });
    const outlines = [...document.querySelectorAll('.ui-setup-guide-highlight')];
    const focusOutline = outlines.find(outline => outline.dataset.setupGuideTarget === focus.id);
    const parentOutline = id !== 'recent-alerts' && outlines.some(outline => outline.dataset.setupGuideTarget === owner.id);
    const overlap = Math.max(0, Math.min(coach.right, ownerBox.right) - Math.max(coach.left, ownerBox.left)) *
      Math.max(0, Math.min(coach.bottom, ownerBox.bottom) - Math.max(coach.top, ownerBox.top));
    const buttons = [...document.querySelector('.ui-setup-guide').querySelectorAll('button')];
    const back = buttons.find(button => button.textContent.trim() === 'Back');
    const forward = buttons.find(button => /^(Next|Finish)/.test(button.textContent.trim()));
    const a = rect(back), b = rect(forward);
    const viewport = box => box.left >= 0 && box.top >= 0 && box.right <= innerWidth + 1 && box.bottom <= innerHeight + 1;
    return holes.some(hole => contains(hole, ownerBox)) && focusOutline && contains(rect(focusOutline), focusBox) &&
      !parentOutline && outlines.length === 1 && overlap < 1 && viewport(focusBox) && viewport(coach) &&
      Math.abs(a.top - b.top) <= 1 && a.right <= b.left ? null :
      { owner: ownerBox.toJSON(), focus: focusBox.toJSON(), coach: coach.toJSON(), overlap, holes, parentOutline, outlines: outlines.length };
  }, id), { message: 'The whole actual Topics card or panel is clear, with its precise lesson outlined and navigation alongside' }).toBeNull();
}

for (const { theme, width, height } of [
  { theme: 'light', width: 1280, height: 720 }, { theme: 'dark', width: 1440, height: 1000 }
]) {
  test(`Topics tours actual cards and filtered Recent alerts in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    const state = await openTopics(page, { theme });
    const storage = await nonGuideStorage(page), url = page.url();
    await start(page);
    for (const [index, id] of steps.entries()) {
      await expect(guide(page)).toHaveAttribute('data-guide-step', id);
      await expect(guide(page)).toContainText(`Step ${index + 1} of 3`);
      await expectFocus(page, id);
      await expect(page.locator('#content .page-header')).toContainText('Example data');
      await expect(page.locator('.alerts-topic-card')).toHaveCount(2);
      await expect(page.locator('.alerts-topic-card').first()).toContainText('Fire activity');
      await expect(page.locator('.alerts-topic-card').last()).toContainText('Receiver health');
      await expect(page.getByRole('dialog', { name: 'Alert subscription', exact: true })).toHaveCount(0);
      if (id === 'read-alerts') await expect(page.getByRole('combobox', { name: 'Show alerts from', exact: true })).toHaveValue('');
      else {
        await expect(page.getByRole('combobox', { name: 'Show alerts from', exact: true })).toHaveValue('guide-fire-activity');
        await expect(page.locator('.alerts-event')).not.toHaveCount(0);
        await expect(page.locator('.alerts-event').filter({ hasText: 'Receiver health' })).toHaveCount(0);
      }
      if (id === 'notifications') await expect(page.locator('[data-guide-target="topic-notifications"]')).toHaveText('Subscribe');
      await screenshot(page, `${id}-${theme}`);
      await expect(page).toHaveURL(url);
      await next(page, id);
    }
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator('.alerts-topic-card').first()).toContainText('Existing fire activity');
    await expect(page.locator('[data-guide-sample], [data-guide-example], .ui-setup-guide-highlight, .ui-setup-guide-layer')).toHaveCount(0);
    expect(await nonGuideStorage(page)).toEqual(storage);
    await expectNoEffects(page); expectIsolated(state);
  });
}

for (const [stop, dismiss] of [['read-alerts', 'shade'], ['recent-alerts', 'Escape'], ['notifications', 'Close guide']]) {
  test(`Topics restores original filter, nodes and player on ${dismiss}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    const state = await openTopics(page, { replay: false, connected: true });
    const filter = page.getByRole('combobox', { name: 'Show alerts from', exact: true });
    await filter.selectOption('real-health');
    await expect(page.locator('.alerts-event')).toHaveCount(1);
    await expect(page.locator('.alerts-event')).toContainText('Saved receiver event');
    await page.evaluate(() => {
      const content = document.getElementById('content');
      window.topicsOriginal = { content, nodes: [...content.childNodes], filter: content.querySelector('select'),
        card: content.querySelector('.alerts-topic-card'), event: content.querySelector('.alerts-event') };
    });
    const storage = await nonGuideStorage(page), url = page.url();
    const player = await page.locator('#audio-dock').evaluate(element => ({ state: element.dataset.state,
      source: element.dataset.source, collapsed: element.dataset.superCollapsed }));
    await page.locator('#page-guide-button').click();
    await start(page);
    await advanceTo(page, stop);
    await guide(page).getByRole('button', { name: 'Back', exact: true }).click();
    if (stop === 'read-alerts') {
      await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
      await expect(filter).toHaveValue('real-health');
      await expect(page.locator('[data-guide-sample]')).toHaveCount(0);
      await guide(page).getByRole('button', { name: 'Start tour', exact: true }).click();
    } else await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
    await expect(guide(page)).toHaveAttribute('data-guide-step', stop);
    if (dismiss === 'shade') await page.mouse.click(8, 680);
    else if (dismiss === 'Escape') await page.keyboard.press('Escape');
    else await guide(page).getByRole('button', { name: dismiss, exact: true }).click();
    await expect(guide(page)).toHaveCount(0);
    expect(await page.locator('#content').evaluate(content => {
      const original = window.topicsOriginal;
      return content === original.content && content.querySelector('select') === original.filter &&
        content.querySelector('.alerts-topic-card') === original.card && content.querySelector('.alerts-event') === original.event &&
        original.nodes.every((node, index) => content.childNodes[index] === node);
    })).toBe(true);
    await expect(filter).toHaveValue('real-health');
    await expect(page.locator('#page-guide-button')).toBeFocused();
    await expect(page).toHaveURL(url);
    expect(await page.locator('#audio-dock').evaluate(element => ({ state: element.dataset.state,
      source: element.dataset.source, collapsed: element.dataset.superCollapsed }))).toEqual(player);
    expect(await nonGuideStorage(page)).toEqual(storage);
    await expectNoEffects(page); expectIsolated(state);
  });
}

test('Topics sample controls cannot subscribe, prompt, register workers, write or change real navigation', async ({ page }) => {
  const state = await openTopics(page);
  const storage = await nonGuideStorage(page), url = page.url();
  await start(page);
  let count = 0;
  for (const id of steps) {
    count += await page.evaluate(() => {
      const controls = [...document.querySelectorAll('[data-guide-sample] button, [data-guide-sample] a, [data-guide-sample] select')];
      for (const control of controls) {
        control.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
      }
      return controls.length;
    });
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    await expect(page.getByRole('dialog', { name: 'Alert subscription', exact: true })).toHaveCount(0);
    await expect(page).toHaveURL(url);
    await next(page, id);
  }
  expect(count).toBeGreaterThanOrEqual(12);
  expect(await nonGuideStorage(page)).toEqual(storage);
  await expectNoEffects(page); expectIsolated(state);
});

test('Topics uses existing connection only to select its normal Notification settings opener', async ({ page }) => {
  const state = await openTopics(page, { connected: true });
  const storage = await nonGuideStorage(page);
  await start(page); await advanceTo(page, 'notifications');
  await expect(page.locator('[data-guide-target="topic-notifications"]')).toHaveText('Notification settings');
  await expectFocus(page, 'notifications');
  await expect(page.getByRole('dialog', { name: 'Alert subscription', exact: true })).toHaveCount(0);
  await next(page, 'notifications');
  expect(await nonGuideStorage(page)).toEqual(storage);
  await expectNoEffects(page); expectIsolated(state);
});

test('Topics first desktop visit, Help and named URL use the same authored overview', async ({ page }) => {
  const state = await openTopics(page, { firstVisit: true, replay: false });
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await page.keyboard.press('Escape');
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBe('1');
  await page.reload();
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  await expect(guide(page)).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await page.keyboard.press('Escape');
  await page.goto('/app.html?view=topics&guide=topics');
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await page.keyboard.press('Escape');
  await expectNoEffects(page); expectIsolated(state);
});

for (const state of ['disabled', 'empty']) {
  test(`Topics examples work when the real page is ${state}`, async ({ page }) => {
    const result = await openTopics(page, { [state]: true });
    await start(page);
    for (const id of steps) {
      await expectFocus(page, id);
      await next(page, id);
    }
    await expect(page.locator('.alerts-topic-card')).toHaveCount(0);
    await expect(page.locator('.alerts-workspace')).toContainText(state === 'disabled' ? 'Push notifications are disabled.' : 'No topics are available to listeners yet.');
    await expectNoEffects(page); expectIsolated(result);
  });
}

test('Topics phone visit remains ordinary and navigation releases desktop sample state', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await openTopics(page, { firstVisit: true, replay: false });
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator('.alerts-topic-card').first()).toContainText('Existing fire activity');
  await page.setViewportSize({ width: 1440, height: 1000 });
  // The first desktop-sized visit now qualifies for automatic orientation.
  await start(page); await advanceTo(page, 'recent-alerts');
  await page.locator('[data-view="dashboard"]').first().evaluate(link => link.click());
  await expect(page).toHaveURL(/view=dashboard/);
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator('[data-guide-sample], [data-guide-example], .ui-setup-guide-highlight, .ui-setup-guide-layer')).toHaveCount(0);
  await expectNoEffects(page);
  expect(state.effects.writes).toEqual([]); expect(state.effects.samples).toEqual([]);
  expect(state.effects.subscriptions).toEqual([]); expect(state.fixture.pageErrors).toEqual([]);
});

async function surfaceSignature(workspace) {
  return workspace.evaluate(element => ({
    classes: [...element.querySelectorAll('*')].map(node => [node.tagName, node.className]),
    controls: [...element.querySelectorAll('button,select')].map(node => ({ tag: node.tagName,
      label: node.tagName === 'SELECT' ? node.closest('label')?.textContent : node.textContent,
      value: node.value, disabled: node.disabled })),
    topics: [...element.querySelectorAll('.alerts-topic-card h3')].map(node => node.textContent),
    alerts: [...element.querySelectorAll('.alerts-event .ui-record-title')].map(node => node.textContent)
  }));
}

test('Topics examples retain the ordinary public renderer and card layout', async ({ page }) => {
  const state = await openTopics(page, { replay: false });
  const { createTopicsGuideData } = await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/guides/topics-presentation.js')).href);
  const sample = createTopicsGuideData();
  await page.route('**/api/v1/alerts/**', async route => {
    const request = route.request(), url = new URL(request.url());
    const data = await sample.requestJson(url.pathname + url.search, { method: request.method() });
    return route.fulfill({ json: { data } });
  });
  await page.reload();
  await expect(page.locator('.alerts-topic-card')).toHaveCount(2);
  const ordinary = await surfaceSignature(page.locator('.alerts-workspace'));
  await page.locator('#page-guide-button').click(); await start(page);
  const toured = await surfaceSignature(page.locator('.alerts-workspace'));
  expect(toured).toEqual(ordinary);
  await page.keyboard.press('Escape');
  await expectNoEffects(page); expectIsolated(state);
});


test('Topics dismissal cancels a delayed sample module without reopening its workspace', async ({ page }) => {
  const state = await openTopics(page);
  const storage = await nonGuideStorage(page);
  let unblock, received;
  const pending = new Promise(resolve => { unblock = resolve; });
  const arrived = new Promise(resolve => { received = resolve; });
  await page.route('**/assets/guides/topics-presentation.js*', async route => {
    received(); await pending; await route.continue();
  });
  try {
    await guide(page).getByRole('button', { name: 'Start tour', exact: true }).click();
    await arrived;
    await page.keyboard.press('Escape');
    await expect(guide(page)).toHaveCount(0);
    unblock();
    await page.evaluate(async () => {
      await import('/assets/guides/topics-presentation.js?v=1');
      await new Promise(requestAnimationFrame);
    });
    await expect(page.locator('[data-guide-sample], [data-guide-example], .ui-setup-guide-highlight, .ui-setup-guide-layer')).toHaveCount(0);
    await expect(page.locator('.alerts-topic-card').first()).toContainText('Existing fire activity');
    expect(await nonGuideStorage(page)).toEqual(storage);
    await page.locator('#page-guide-button').click(); await start(page);
    await page.keyboard.press('Escape');
    await expectNoEffects(page); expectIsolated(state);
  } finally { unblock(); }
});

for (const theme of ['light', 'dark']) {
  test(`Topics populated cards, panel and coach retain readable paint in ${theme} and representative hues`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const state = await openTopics(page, { theme });
    await start(page); await next(page, 'read-alerts');
    const summaries = [];
    for (const hue of [null, 0, 120, 240]) {
      await page.evaluate(async value => { (await import('/assets/core/theme.js?v=2')).applyThemeHue(value); }, hue);
      await page.evaluate(() => {
        window.topicsAuditInert = [...document.querySelectorAll('[inert]')];
        window.topicsAuditInert.forEach(element => { element.inert = false; });
        document.querySelector('.ui-setup-guide-backdrop').style.visibility = 'hidden';
      });
      try {
        const result = await expectContrast(page, { rootSelector: '.alerts-workspace',
          reportName: `topics-populated-${theme}-${hue}` });
        expect(result.summary.text.checked).toBeGreaterThan(8);
        summaries.push({ section: 'topics-workspace', hue, summary: result.summary });
      } finally {
        await page.evaluate(() => {
          window.topicsAuditInert.forEach(element => { element.inert = true; });
          document.querySelector('.ui-setup-guide-backdrop').style.visibility = '';
        });
      }
      const result = await expectContrast(page, { rootSelector: '.ui-setup-guide', reportName: `topics-coach-${theme}-${hue}` });
      expect(result.summary.text.checked).toBeGreaterThan(3);
      summaries.push({ section: 'topics-coach', hue, summary: result.summary });
    }
    fs.mkdirSync(evidence, { recursive: true });
    fs.writeFileSync(path.join(evidence, `contrast-${theme}.json`), JSON.stringify(summaries, null, 2));
    await page.evaluate(async () => { (await import('/assets/core/theme.js?v=2')).applyThemeHue(null); });
    await page.keyboard.press('Escape');
    await expectNoEffects(page); expectIsolated(state);
  });
}
