'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { installSiteStyleApplication, systemName } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const guide = page => page.locator('.ui-setup-guide[data-guide-id="dashboard"]');
const steps = ['systems', 'reception', 'calls-tab', 'call-totals', 'call-history', 'identities',
  'activity-tab', 'event-type', 'activity-radios', 'inventory-tab', 'alias-list', 'configured', 'unknown-tab', 'unknown'];
const radioSteps = new Set(['identities', 'activity-tab', 'event-type', 'activity-radios',
  'inventory-tab', 'alias-list', 'configured', 'unknown-tab', 'unknown']);
const memoryKey = 'sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:dashboard:3';
const evidence = process.env.MAIN_GUIDE_EVIDENCE ||
  path.resolve(__dirname, '../../../../../build/playwright-results/main-guide-evidence');
const nonGuideStorage = page => page.evaluate(() => Object.fromEntries(Object.entries(localStorage)
  .filter(([key]) => !key.startsWith('sdrtrunk-vce-guide:'))));

async function openMain(page, { theme = 'light', replay = true, firstVisit = false,
  empty = false, denied = [], tab = 'health', parameters = '', historyDisabled = true } = {}) {
  const effects = { writes: [], samples: [], audio: [], sockets: [] };
  page.on('request', request => {
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/v1/')) return;
    if (!['GET', 'HEAD'].includes(request.method()) &&
        !(request.method() === 'PUT' && url.pathname === '/api/v1/me/tour-dismissals') && url.pathname !== '/api/v1/live/multiplex/control')
      effects.writes.push(`${request.method()} ${url.pathname}`);
    if (/guide-main|main-guide|sample-main|tour-main|b00b9000|p25:bee00:(123|456)|v1-[rg]-bee00|\b940[12]\b/
      .test(decodeURIComponent(url.pathname + url.search + (request.postData() || ''))))
      effects.samples.push(`${request.method()} ${url.pathname}`);
    if (/audio/.test(url.pathname)) effects.audio.push(url.pathname);
  });
  page.on('websocket', socket => effects.sockets.push(socket.url()));
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const fixture = await installSiteStyleApplication(page, theme, { revision: 1, tables: [] }, { guides: firstVisit });
  await page.addInitScript(({ firstVisit, key }) => {
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:welcome:1', '1');
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:dashboard:2', '1');
    if (!firstVisit) localStorage.setItem(key, '1');
  }, { firstVisit, key: memoryKey });
  if (empty) await page.route('**/api/v1/dashboard/main', route => route.fulfill({ json: { data: {
    quality: { current: [] }, directory: { systems: [], channels: [] }
  } } }));
  if (historyDisabled) await page.route('**/api/v1/status', route => route.fulfill({ json: { data: {
    p25_visualizer_available: true, stats_logging: { summary_configured: true, summary_active: true,
      detailed_history_configured: false, detailed_history_active: false }, database: { detailed_history_available: false }
  } } }));
  if (denied.length) {
    const file = path.resolve(__dirname, '../../../../../src/main/java/io/github/dsheirer/web/auth/WebCapability.java');
    const capabilities = Object.fromEntries([...fs.readFileSync(file, 'utf8').matchAll(/^\s+[A-Z_]+\("([^"]+)",/gm)]
      .map(([, id]) => [id, !denied.includes(id)]));
    await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
      configured: true, authenticated: true, username: 'fixture-admin', tier: 'admin', primary: true,
      csrf_token: 'fixture-csrf', guide_dismissal_scope: 'account-1-100', capabilities
    } } }));
  }
  await page.goto(`/app.html?view=dashboard&tab=${tab}${parameters ? `&${parameters}` : ''}${replay ? '&guide=dashboard' : ''}`);
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  return { fixture, effects };
}

async function expectInViewport(locator) {
  await expect.poll(async () => {
    const box = await locator.boundingBox(), viewport = locator.page().viewportSize();
    return box && box.x >= 0 && box.y >= 0 && box.x + box.width <= viewport.width + 1 &&
      box.y + box.height <= viewport.height + 1 ? null : box;
  }, { message: 'The actual control and guide remain fully visible' }).toBeNull();
}

async function expectOverview(page) {
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await expect(guide(page).locator('.ui-setup-guide-counter')).toHaveText('Overview');
  await expect(guide(page).locator('h2')).not.toBeEmpty();
  await expect(page.locator('.ui-setup-guide-highlight, [data-guide-sample], [data-guide-example]')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expectInViewport(guide(page));
}

async function start(page) {
  await expectOverview(page);
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).click();
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'systems');
}

async function advance(page, id, available = steps) {
  await guide(page).getByRole('button', { name: id === available.at(-1) ? 'Finish' : 'Next', exact: true }).click();
}

async function goTo(page, destination) {
  const current = await guide(page).getAttribute('data-guide-step');
  for (const id of steps.slice(Math.max(0, steps.indexOf(current)))) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    if (id === destination) return;
    await advance(page, id);
  }
  throw new Error(`Unknown Main guide step: ${destination}`);
}

async function expectSpotlight(page, id) {
  const targets = page.locator(`[data-guide-target="mn-${id}"]:visible`);
  await expect(targets.first()).toBeVisible();
  for (const target of await targets.all()) {
    await expectInViewport(target);
    await expect.poll(() => target.evaluate(element => {
      const rect = element.getBoundingClientRect();
      const header = document.querySelector('.app-header')?.getBoundingClientRect();
      const card = document.querySelector('.ui-setup-guide').getBoundingClientRect();
      const highlights = [...document.querySelectorAll('.ui-setup-guide-highlight')].map(item => item.getBoundingClientRect());
      const overlap = Math.min(rect.right, card.right) - Math.max(rect.left, card.left) > 1 &&
        Math.min(rect.bottom, card.bottom) - Math.max(rect.top, card.top) > 1;
      const covered = highlights.some(box => box.left <= rect.left + 1 && box.top <= rect.top + 1 &&
        box.right >= rect.right - 1 && box.bottom >= rect.bottom - 1);
      return (!header || rect.top >= header.bottom - 1) && !overlap && covered ? null :
        { target: rect.toJSON(), headerBottom: header?.bottom, overlap, covered };
    }), { message: `mn-${id} highlights its actual control below the header and clear of the guide` }).toBeNull();
  }
  await expectInViewport(guide(page));
  await expect.poll(() => guide(page).evaluate(element => {
    const header = document.querySelector('.app-header')?.getBoundingClientRect();
    const back = [...element.querySelectorAll('button')].find(button => button.textContent.trim() === 'Back');
    const next = [...element.querySelectorAll('button')].find(button => /^(Next|Finish)/.test(button.textContent.trim()));
    const b = back?.getBoundingClientRect(), n = next?.getBoundingClientRect();
    return (!header || element.getBoundingClientRect().top >= header.bottom - 1) && b && n &&
      Math.abs(b.top - n.top) <= 1 && b.right <= n.left;
  }), { message: 'Guide navigation stays horizontal and below the header' }).toBe(true);
}

async function expectLessonState(page, id) {
  await expect(page.locator('.read-only-modal:not(.ui-setup-guide), [popover]:popover-open')).toHaveCount(0);
  const tab = ['systems', 'reception', 'calls-tab'].includes(id) ? 'Overview' :
    ['call-totals', 'call-history', 'identities', 'activity-tab'].includes(id) ? 'Call Statistics' :
    ['event-type', 'activity-radios', 'inventory-tab'].includes(id) ? 'Radio Activity' : 'Alias Inventory';
  const navigation = page.getByRole('navigation', { name: 'Main sections', exact: true });
  await expect(navigation.getByRole('link', { name: tab, exact: true })).toHaveClass(/\bactive\b/);
  if (id === 'systems') {
    await expect(page.locator('.radio-directory-content')).toContainText('County P25');
    await expect(page.locator('.radio-directory-content')).toContainText('City P25');
    await expect(page.locator('.radio-directory-conventional-card').first()).toBeVisible();
  }
  if (id === 'reception') {
    const readings = page.locator('.radio-directory-site-row');
    await expect(readings.first()).toContainText(/dBFS/);
    await expect(readings.first()).toContainText(/\d+\.\d+%/);
  }
  if (id === 'call-totals') {
    const target = page.locator('[data-guide-target="mn-call-totals"]');
    for (const total of ['1,000', '800', '250']) await expect(target).toContainText(total);
    await expect(guide(page)).toContainText('80%');
    await expect(guide(page)).toContainText('25%');
  }
  if (id === 'call-history') {
    const history = page.locator('.dashboard-call-activity');
    await expect(history.getByRole('group', { name: 'Call activity metric', exact: true })).toBeVisible();
    await expect(history.getByRole('group', { name: 'Channel type', exact: true })).toBeVisible();
    await expect(history.locator('svg')).toBeVisible();
    const bars = history.locator('svg .count-time-chart-bar');
    await expect(bars).toHaveCount(24);
    expect(await bars.evaluateAll(elements => elements.reduce((total, bar) =>
      total + Number(bar.querySelector('title').textContent.match(/:\s*([\d,]+)$/)[1].replaceAll(',', '')), 0))).toBe(1000);
    await expect(history).toContainText('P25');
  }
  if (id === 'identities') {
    for (const type of ['dashboard-destinations', 'dashboard-sources']) {
      const table = page.locator(`table[data-table-type="${type}"]`);
      await expect(table.locator('tbody tr[data-id], tbody tr')).not.toHaveCount(0);
      await expect(table.getByRole('link').first()).toBeVisible();
    }
    await expect(page.locator('table[data-table-type="dashboard-destinations"]')).toContainText('Sheriff');
  }
  if (id === 'event-type') {
    await expect(page.locator('.dashboard-activity-donut')).toBeVisible();
    const emergency = page.locator('.dashboard-activity-legend-button').filter({ hasText: /Emergency/i });
    await expect(emergency).toBeVisible();
    await expect(emergency).toHaveAttribute('aria-pressed', 'false');
    await expect(page.locator('table[data-table-type="dashboard-activity-radios"]')).toHaveCount(0);
  }
  if (['activity-radios', 'inventory-tab'].includes(id)) {
    await expect(page.locator('.dashboard-activity-legend-button').filter({ hasText: /Emergency/i }))
      .toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('table[data-table-type="dashboard-activity-radios"] tbody tr').first()).toBeVisible();
    await expect(page.locator('.dashboard-activity-radio-result')).toContainText(/Sheriff|Engine|Police/);
  }
  if (id === 'alias-list') {
    await expect(page.getByRole('combobox', { name: 'Alias List', exact: true })).toHaveValue('');
    await expect(page.locator('.alias-coverage-selection-prompt')).toBeVisible();
    await expect(page.locator('.alias-coverage-table-section')).toHaveCount(0);
  }
  if (['configured', 'unknown-tab', 'unknown'].includes(id)) {
    const selector = page.getByRole('combobox', { name: 'Alias List', exact: true });
    await expect(selector).not.toHaveValue('');
    await expect(selector.locator('option:checked')).toContainText('County');
  }
  if (['configured', 'unknown-tab'].includes(id)) {
    await expect(page.locator('.alias-coverage-table-section')).toContainText('Sheriff');
    await expect(page.getByRole('button', { name: /^Configured aliases/ })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('.alias-coverage-table-section th[data-column="name"]')).toHaveAttribute('aria-sort', 'ascending');
  }
  if (id === 'unknown-tab') await expect(page.getByRole('button', { name: /^Unknown aliases/ }))
    .toHaveAttribute('aria-pressed', 'false');
  if (id === 'unknown') {
    await expect(page.getByRole('button', { name: /^Unknown aliases/ })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('table[data-table-type="alias-coverage-unassigned-v1"] tbody tr').first()).toBeVisible();
  }
}

function expectIsolated(fixture, effects) {
  expect(effects.writes).toEqual([]);
  expect(effects.samples).toEqual([]);
  expect(effects.audio).toEqual([]);
  expect(effects.sockets).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
}

async function screenshot(page, name) {
  fs.mkdirSync(path.join(evidence, 'screens'), { recursive: true });
  await page.screenshot({ path: path.join(evidence, 'screens', `${name}.png`) });
}

for (const { theme, width, height } of [
  { theme: 'light', width: 1280, height: 720 }, { theme: 'dark', width: 1440, height: 1000 }
]) {
  test(`Main tours actual systems, statistics, charts, activity and inventory in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    const { fixture, effects } = await openMain(page, { theme, empty: true });
    await expectOverview(page);
    await expect(page.locator('.radio-directory-site-row')).toHaveCount(0);
    const storage = await nonGuideStorage(page), url = page.url();
    await screenshot(page, `main-overview-${theme}`);
    await start(page);
    for (const [index, id] of steps.entries()) {
      await expect(guide(page)).toHaveAttribute('data-guide-step', id);
      await expect(guide(page)).toContainText(`Step ${index + 1} of ${steps.length}`);
      try { await expectSpotlight(page, id); }
      catch (error) {
        await screenshot(page, `main-failure-${id}-${theme}`);
        fs.writeFileSync(path.join(evidence, `geometry-${id}-${theme}.json`), JSON.stringify(await page.evaluate(() => ({
          scroll: [scrollX, scrollY], elements: ['.ui-setup-guide', '.app-header', '[data-guide-target]']
            .flatMap(selector => [...document.querySelectorAll(selector)].map(element => ({
              selector, target: element.dataset.guideTarget, box: element.getBoundingClientRect().toJSON()
            })))
        })), null, 2));
        throw error;
      }
      await expectLessonState(page, id);
      await expect(page).toHaveURL(url);
      await screenshot(page, `main-${id}-${theme}`);
      await advance(page, id);
    }
    await expect(page.locator('.ui-setup-guide, [data-guide-sample], [data-guide-example], .ui-setup-guide-highlight')).toHaveCount(0);
    await expect(page.locator('.radio-directory-site-row')).toHaveCount(0);
    await expect(page).toHaveURL(url);
    expect(await nonGuideStorage(page)).toEqual(storage);
    expectIsolated(fixture, effects);
  });
}

for (const { stop, dismiss } of [
  { stop: 'call-history', dismiss: 'Close guide' }, { stop: 'activity-radios', dismiss: 'Escape' },
  { stop: 'unknown', dismiss: 'shade' }
]) {
  test(`Main restores original inventory, selection, draft, scroll and player after ${dismiss}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    const { fixture, effects } = await openMain(page, { replay: false, tab: 'inventory',
      parameters: 'alias_list_id=1&sort=last_evidence&direction=desc' });
    await expect(page.getByRole('combobox', { name: 'Alias List', exact: true })).toHaveValue('1');
    await expect(page.locator('.alias-coverage-table-section th[data-column="heard"]')).toHaveAttribute('aria-sort', 'descending');
    const search = page.locator('.alias-coverage-search input[type="search"]');
    await expect(search).toBeVisible();
    await search.fill('Unsaved search draft');
    await page.evaluate(() => {
      const content = document.getElementById('content');
      window.mainGuideOriginal = { content, nodes: [...content.childNodes], inventory: content.querySelector('.alias-coverage'),
        selector: content.querySelector('.alias-coverage select'), search: content.querySelector('.alias-coverage-search input') };
      window.scrollTo(0, Math.min(140, document.scrollingElement.scrollHeight - innerHeight));
    });
    const scroll = await page.evaluate(() => [scrollX, scrollY]);
    const storage = await nonGuideStorage(page), url = page.url(), title = await page.title();
    const player = page.locator('#audio-dock');
    const playerBefore = await player.evaluate(element => ({ source: element.dataset.source,
      state: element.dataset.state, collapsed: element.dataset.superCollapsed }));
    const scrollPhases = [{ phase: 'before-help', scroll }];
    scrollPhases.push(await page.locator('#page-guide-button').evaluate(button => {
      window.mainGuidePointerScroll = [];
      for (const event of ['pointerdown', 'click']) button.addEventListener(event,
        () => window.mainGuidePointerScroll.push({ event, scroll: [scrollX, scrollY] }), { capture: true, once: true });
      const header = document.querySelector('.app-header');
      return { phase: 'help-geometry', help: button.getBoundingClientRect().toJSON(),
        header: header.getBoundingClientRect().toJSON(), position: getComputedStyle(header).position };
    }));
    // Exercise the real Help handler without Playwright first scrolling its sticky
    // toolbar to the document origin, so the restoration assertion tests a real offset.
    await page.locator('#page-guide-button').evaluate(button => {
      button.focus({ preventScroll: true });
      button.click();
    });
    scrollPhases.push({ phase: 'pointer-events', events: await page.evaluate(() => window.mainGuidePointerScroll) });
    scrollPhases.push({ phase: 'after-overview', scroll: await page.evaluate(() => [scrollX, scrollY]) });
    await start(page);
    scrollPhases.push({ phase: 'after-start', scroll: await page.evaluate(() => [scrollX, scrollY]) });
    await goTo(page, stop);
    await guide(page).getByRole('button', { name: 'Back', exact: true }).click();
    await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
    await expect(guide(page)).toHaveAttribute('data-guide-step', stop);
    if (dismiss === 'Escape') await page.keyboard.press('Escape');
    else if (dismiss === 'shade') await page.mouse.click(8, 500);
    else await guide(page).getByRole('button', { name: dismiss, exact: true }).click();
    await expect(guide(page)).toHaveCount(0);
    expect(await page.locator('#content').evaluate(element => {
      const original = window.mainGuideOriginal;
      return element === original.content && element.querySelector('.alias-coverage') === original.inventory &&
        element.querySelector('.alias-coverage select') === original.selector &&
        element.querySelector('.alias-coverage-search input') === original.search &&
        original.nodes.every((node, index) => element.childNodes[index] === node);
    })).toBe(true);
    await expect(page.getByRole('combobox', { name: 'Alias List', exact: true })).toHaveValue('1');
    await expect(page.locator('.alias-coverage-table-section th[data-column="heard"]')).toHaveAttribute('aria-sort', 'descending');
    await expect(search).toHaveValue('Unsaved search draft');
    await expect(page).toHaveURL(url);
    await expect(page).toHaveTitle(title);
    expect(await nonGuideStorage(page)).toEqual(storage);
    const restoredScroll = await page.evaluate(() => [scrollX, scrollY]);
    if (restoredScroll.toString() !== scroll.toString()) {
      fs.mkdirSync(evidence, { recursive: true });
      fs.writeFileSync(path.join(evidence, `restore-scroll-${dismiss.replaceAll(' ', '-')}.json`),
        JSON.stringify([...scrollPhases, { phase: 'after-dismiss', scroll: restoredScroll }], null, 2));
    }
    expect(restoredScroll).toEqual(scroll);
    await expect(player).toHaveAttribute('data-source', playerBefore.source);
    await expect(player).toHaveAttribute('data-state', playerBefore.state);
    await expect(player).toHaveAttribute('data-super-collapsed', playerBefore.collapsed);
    await expect(page.locator('#page-guide-button')).toBeFocused();
    await expect(page.locator('[data-guide-sample], [data-guide-example], .ui-setup-guide-highlight')).toHaveCount(0);
    expectIsolated(fixture, effects);
  });
}

test('Main sample controls cannot save, request sample records, change routes, or start audio', async ({ page }) => {
  const { fixture, effects } = await openMain(page);
  await start(page);
  const storage = await nonGuideStorage(page), url = page.url();
  let exercised = 0;
  for (const id of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    exercised += await page.evaluate(() => {
      const roots = [...document.querySelectorAll('[data-guide-sample]')];
      const controls = [...new Set(roots.flatMap(root => [...root.querySelectorAll('button, a, input, select, form, [role="button"]')]))];
      for (const control of controls) {
        control.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
        if (control.tagName === 'FORM') control.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      }
      return controls.length;
    });
    await expect(page).toHaveURL(url);
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    await expect(page.locator('.read-only-modal:not(.ui-setup-guide)')).toHaveCount(0);
    await advance(page, id);
  }
  expect(exercised).toBeGreaterThan(50);
  expect(await nonGuideStorage(page)).toEqual(storage);
  expectIsolated(fixture, effects);
});

test('Main restores the original Recorded and Conventional chart choices after its example history', async ({ page }) => {
  const { fixture, effects } = await openMain(page, { replay: false, tab: 'calls', historyDisabled: false });
  const history = page.locator('.dashboard-call-activity');
  const metric = history.getByRole('group', { name: 'Call activity metric', exact: true });
  const channelType = history.getByRole('group', { name: 'Channel type', exact: true });
  await metric.getByRole('button', { name: 'Recorded', exact: true }).click();
  await channelType.getByRole('button', { name: 'Conventional', exact: true }).click();
  await expect(metric.getByRole('button', { name: 'Recorded', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(channelType.getByRole('button', { name: 'Conventional', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(history.locator('.count-time-chart-bar')).toHaveCount(1);
  await expect(history.locator('.count-time-chart-bar title')).toContainText('Total: 18');
  await page.evaluate(() => {
    const content = document.getElementById('content');
    window.originalMainChart = { nodes: [...content.childNodes], history: content.querySelector('.dashboard-call-activity') };
  });
  const storage = await nonGuideStorage(page), url = page.url();
  await page.locator('#page-guide-button').evaluate(button => {
    button.focus({ preventScroll: true });
    button.click();
  });
  await start(page);
  await goTo(page, 'call-history');
  await expect(history.getByRole('group', { name: 'Call activity metric', exact: true })
    .getByRole('button', { name: 'Calls', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(history.getByRole('group', { name: 'Channel type', exact: true })
    .getByRole('button', { name: 'All', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expectLessonState(page, 'call-history');
  await page.keyboard.press('Escape');
  await expect(guide(page)).toHaveCount(0);
  expect(await page.locator('#content').evaluate(element => element.querySelector('.dashboard-call-activity') ===
    window.originalMainChart.history && window.originalMainChart.nodes.every((node, index) => element.childNodes[index] === node))).toBe(true);
  await expect(metric.getByRole('button', { name: 'Recorded', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(channelType.getByRole('button', { name: 'Conventional', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(history.locator('.count-time-chart-bar')).toHaveCount(1);
  await expect(history.locator('.count-time-chart-bar title')).toContainText('Total: 18');
  await expect(page).toHaveURL(url);
  expect(await nonGuideStorage(page)).toEqual(storage);
  expectIsolated(fixture, effects);
});

test('Main authored version replaces its placeholder and replays from Help and named URL', async ({ page }) => {
  const { fixture, effects } = await openMain(page, { replay: false, firstVisit: true });
  await expectOverview(page);
  await page.keyboard.press('Escape');
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBe('1');
  await page.reload();
  await expect(page.locator('#page-guide-button')).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await start(page);
  await page.keyboard.press('Escape');
  await page.goto('/app.html?view=dashboard&guide=dashboard');
  await expectOverview(page);
  await page.keyboard.press('Escape');
  expectIsolated(fixture, effects);
});

test('Main navigation releases its samples before leaving and restores a normal page on return', async ({ page }) => {
  const { fixture, effects } = await openMain(page);
  await start(page);
  await goTo(page, 'activity-radios');
  await page.locator('.primary-nav [data-view="live"]').evaluate(link => link.click());
  await expect(page.locator('.live-split')).toBeVisible();
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], [data-guide-example]')).toHaveCount(0);
  await page.locator('.primary-nav [data-view="dashboard"]').evaluate(link => link.click());
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(page.locator('.radio-directory-content')).toContainText(systemName);
  await expect(page.locator('.radio-directory-content')).not.toContainText('County P25');
  expectIsolated(fixture, effects);
});

test('Main dismissal during preparation cannot repaint sample content afterward', async ({ page }) => {
  const { fixture, effects } = await openMain(page, { replay: false, empty: true });
  await page.locator('#page-guide-button').click();
  await expectOverview(page);
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).evaluate(button => {
    button.click();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
  });
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], [data-guide-example]')).toHaveCount(0);
  await expect(page.locator('.radio-directory-site-row')).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await start(page);
  await page.keyboard.press('Escape');
  await expect(page.locator('[data-guide-sample], [data-guide-example]')).toHaveCount(0);
  expectIsolated(fixture, effects);
});

test('phone visits keep the desktop Main tour and examples closed', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const { fixture, effects } = await openMain(page, { firstVisit: true });
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], [data-guide-example]')).toHaveCount(0);
  await expect(page.locator('#page-guide-button')).toBeHidden();
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBeNull();
  expectIsolated(fixture, effects);
});

test('Main omits identity, activity and inventory lessons when Radio access is denied', async ({ page }) => {
  const { fixture, effects } = await openMain(page, { denied: ['radio'] });
  await start(page);
  const available = steps.filter(id => !radioSteps.has(id));
  for (const id of available) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    await expectSpotlight(page, id);
    await expect(page.getByRole('navigation', { name: 'Main sections', exact: true })
      .getByRole('link', { name: 'Alias Inventory', exact: true })).toHaveCount(0);
    await expect(page.locator('[data-guide-sample] a[href*="view=radio"], [data-guide-sample] a[href*="view=channel"]')).toHaveCount(0);
    if (['systems', 'reception', 'calls-tab'].includes(id)) {
      await expect(page.locator('.radio-directory-conventional-card')).toHaveCount(0);
      await expect(page.getByRole('group', { name: 'Filter radio directory', exact: true })).toHaveCount(0);
      await expect(page.getByRole('link', { name: 'Export channels as CSV', exact: true })).toHaveCount(0);
    }
    await advance(page, id, available);
  }
  await expect(guide(page)).toHaveCount(0);
  expectIsolated(fixture, effects);
});

test('Main access denial cannot reveal its guide or sample records', async ({ page }) => {
  const { fixture, effects } = await openMain(page, { denied: ['dashboard'], firstVisit: true });
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], [data-guide-example]')).toHaveCount(0);
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBeNull();
  expectIsolated(fixture, effects);
});

for (const theme of ['light', 'dark']) {
  test(`Main guide and actual hourly SVG labels retain contrast across all 360 hues in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const { fixture, effects } = await openMain(page, { theme });
    await start(page);
    await goTo(page, 'call-history');
    const result = await page.evaluate(async () => {
      const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
      const chart = document.querySelector('.count-time-chart-svg');
      const panel = chart.closest('.ui-section');
      const samples = [...document.querySelectorAll('.ui-setup-guide h2, .ui-setup-guide p, .ui-setup-guide button, .ui-setup-guide-counter'),
        ...chart.querySelectorAll('text')];
      const original = [...document.querySelectorAll('.ui-setup-guide *, .dashboard-guide-workspace *')]
        .map(element => [element, element.style.transition]);
      original.forEach(([element]) => { element.style.transition = 'none'; });
      const canvas = document.createElement('canvas'); canvas.width = canvas.height = 1;
      const context = canvas.getContext('2d', { willReadFrequently: true }), colors = new Map();
      const rgba = color => {
        if (!colors.has(color)) {
          context.clearRect(0, 0, 1, 1); context.fillStyle = color; context.fillRect(0, 0, 1, 1);
          colors.set(color, [...context.getImageData(0, 0, 1, 1).data]);
        }
        return colors.get(color);
      };
      const background = element => {
        for (let owner = element; owner; owner = owner.parentElement) {
          const css = getComputedStyle(owner), color = css.backgroundColor;
          if (css.backgroundImage !== 'none' || css.filter !== 'none' || Number(css.opacity) !== 1)
            throw new Error('Main text acquired non-solid paint requiring a fresh pixel review.');
          if (rgba(color)[3] === 255) return color;
          if (rgba(color)[3] !== 0) throw new Error('Main text needs a solid painted owner.');
        }
        throw new Error('Main text has no painted owner.');
      };
      const luminance = color => rgba(color).slice(0, 3).reduce((total, channel, index) => {
        const value = channel / 255;
        return total + (value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4) * [.2126, .7152, .0722][index];
      }, 0);
      const contrast = (front, back) => {
        const values = [luminance(front), luminance(back)].sort((a, b) => b - a);
        return (values[0] + .05) / (values[1] + .05);
      };
      let worstText = { ratio: Infinity }, worstPanel = { ratio: Infinity }, checked = 0;
      try {
        for (let hue = 0; hue < 360; hue++) {
          applyThemeHue(hue);
          for (const sample of samples) {
            const css = getComputedStyle(sample), foreground = sample instanceof SVGElement ? css.fill : css.color;
            const behind = background(sample), ratio = contrast(foreground, behind); checked++;
            if (ratio < worstText.ratio) worstText = { ratio, hue, text: sample.textContent.trim(), foreground, background: behind };
          }
          const foreground = background(panel), behind = background(panel.parentElement), ratio = contrast(foreground, behind);
          if (ratio < worstPanel.ratio) worstPanel = { ratio, hue, foreground, background: behind };
        }
      } finally {
        applyThemeHue(null);
        original.forEach(([element, transition]) => { element.style.transition = transition; });
      }
      return { samples: samples.length, checked, svgLabels: chart.querySelectorAll('text').length, worstText, worstPanel };
    });
    fs.mkdirSync(evidence, { recursive: true });
    fs.writeFileSync(path.join(evidence, `palette-${theme}-360-hues.json`), `${JSON.stringify(result, null, 2)}\n`);
    expect(result.svgLabels).toBeGreaterThan(8);
    expect(result.checked).toBe(result.samples * 360);
    expect(result.worstText.ratio, JSON.stringify(result.worstText)).toBeGreaterThanOrEqual(4.5);
    expect(result.worstPanel.ratio, JSON.stringify(result.worstPanel)).toBeGreaterThanOrEqual(1.10);
    await page.keyboard.press('Escape');
    expectIsolated(fixture, effects);
  });

  test(`Main populated guide, charts and tables retain contrast in ${theme} and representative hues`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const { fixture, effects } = await openMain(page, { theme });
    const summaries = [];
    const audit = async (rootSelector, section) => {
      await page.evaluate(rootSelector => {
        window.mainGuideAuditInert = [...document.querySelectorAll('[inert]')];
        window.mainGuideAuditInert.forEach(element => { element.inert = false; });
        const backdrop = document.querySelector('.ui-setup-guide-backdrop');
        window.mainGuideAuditBackdrop = [backdrop, backdrop.style.visibility];
        if (rootSelector !== '.ui-setup-guide') backdrop.style.visibility = 'hidden';
      }, rootSelector);
      try {
        for (const hue of [null, 0, 120, 240]) {
          await page.evaluate(async value => {
            const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
            applyThemeHue(value);
          }, hue);
          const result = await expectContrast(page, { rootSelector,
            reportName: `main-guide-${section}-${theme}-hue-${hue}` });
          expect(result.summary.text.checked).toBeGreaterThan(3);
          summaries.push({ section, hue, summary: result.summary });
          await screenshot(page, `main-${section}-${theme}-hue-${hue}`);
        }
      } finally {
        await page.evaluate(async () => {
          const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
          applyThemeHue(null);
          window.mainGuideAuditInert.forEach(element => { element.inert = true; });
          const [backdrop, visibility] = window.mainGuideAuditBackdrop;
          backdrop.style.visibility = visibility;
          delete window.mainGuideAuditInert;
          delete window.mainGuideAuditBackdrop;
        });
      }
    };
    await expectOverview(page);
    await audit('.ui-setup-guide', 'overview');
    await start(page);
    await audit('[data-guide-target="mn-systems"]', 'actual-systems');
    await goTo(page, 'call-history');
    await audit('.dashboard-call-activity', 'actual-call-history');
    await goTo(page, 'event-type');
    await audit('.dashboard-activity-layout', 'actual-activity-mix');
    await goTo(page, 'activity-radios');
    await audit('.dashboard-activity-radio-result', 'actual-emergency-radios');
    await goTo(page, 'configured');
    await audit('.alias-coverage-table-section', 'actual-configured');
    await goTo(page, 'unknown');
    await audit('.alias-coverage-table-section', 'actual-unknown');
    await audit('.ui-setup-guide', 'unknown-guide');
    fs.writeFileSync(path.join(evidence, `contrast-summary-${theme}.json`), `${JSON.stringify(summaries, null, 2)}\n`);
    await page.keyboard.press('Escape');
    expectIsolated(fixture, effects);
  });
}
