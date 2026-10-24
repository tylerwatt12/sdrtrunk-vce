'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');

const chapters = [
  ['computer', 'Computer & version', /Java|OpenJDK/], ['health', 'Receiver status', /Radio data keeps being lost/],
  ['application-log', 'Application log', /County Site started receiving/],
  ['web-diagnostic-log', 'Web server log', /Diagnostic logging/], ['support', 'Report a problem', /Information for support/],
  ['remote-support', 'Remote Support', /Allow remote support/], ['call-matching', 'Call matching', /Regional Fire/],
  ['tuner-allocation', 'Tuner allocation', /RTL-SDR 1/], ['recordings', 'Recording settings', /Managed/],
  ['audio-quality', 'Audio quality', /Tone detection/], ['transcription', 'Transcription', /Transcription progress/],
  ['push-setup', 'Push setup', /Awaiting approval/], ['activity', 'Activity settings', /Save activity summaries/],
  ['retained-statistics', 'Saved data cleanup', /Choose data/], ['remote-links', 'Remote Links', /Field receiver/],
  ['protocol-p25', 'P25 band plans', /County P25/], ['display', 'Display settings', /Live timing/],
  ['users', 'Web accounts', /example.listener/], ['access', 'Page access', /Pages & features/]
];
const guide = page => page.locator('.ui-setup-guide[data-guide-id="admin"]');
const guideErrors = new WeakMap();
const evidence = process.env.ADMINISTRATION_GUIDE_EVIDENCE;
const nonGuideStorage = page => page.evaluate(() => Object.fromEntries(Object.entries(localStorage)
  .filter(([key]) => !key.startsWith('sdrtrunk-vce-guide:'))));
const playerState = page => page.locator('#audio-dock').evaluate(element => ({ state: element.dataset.state,
  source: element.dataset.source, collapsed: element.dataset.superCollapsed }));

async function installAdministration(page, { theme = 'light', tab = 'health', replay = true, denied = [], firstVisit = false } = {}) {
  const fixture = await installSiteStyleApplication(page, theme, undefined, { guides: firstVisit });
  if (firstVisit) await page.addInitScript(() => {
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:welcome:1', '1');
  });
  const effects = { writes: [], requests: [], sockets: [] };
  const errors = []; guideErrors.set(page, errors);
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  page.on('request', request => {
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/v1/')) return;
    effects.requests.push(`${request.method()} ${url.pathname}`);
    if (!['GET', 'HEAD'].includes(request.method()) && url.pathname !== '/api/v1/live/multiplex/control')
      effects.writes.push(`${request.method()} ${url.pathname}`);
  });
  page.on('websocket', socket => effects.sockets.push(socket.url()));
  if (denied.length) {
    const source = fs.readFileSync(path.resolve(__dirname,
      '../../../../../src/main/java/io/github/dsheirer/web/auth/WebCapability.java'), 'utf8');
    const capabilities = Object.fromEntries([...source.matchAll(/^\s+[A-Z_]+\("([^"]+)",/gm)]
      .map(([, id]) => [id, !denied.includes(id)]));
    await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
      configured: true, authenticated: true, username: 'fixture-admin', tier: 'admin', primary: true,
      csrf_token: 'fixture-csrf', guide_dismissal_scope: 'account-1-100', capabilities
    } } }));
  }
  await page.goto(`/app.html?view=admin&tab=${tab}${replay ? '&guide=admin' : ''}`);
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  if (replay || firstVisit) await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  const initialRequestCount = effects.requests.length;
  return { fixture, effects, initialRequestCount };
}

async function selectChapter(page, id, label) {
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await guide(page).getByRole('button', { name: label, exact: true }).click();
  const initial = id === 'tuner-allocation' ? 'tuner-allocation-channel' : `${id}-purpose`;
  try { await expect(guide(page)).toHaveAttribute('data-guide-step', initial); }
  catch (error) { throw new Error(`${id} preparation failed. ${guideErrors.get(page).join('\n')}`, { cause: error }); }
  await expect(guide(page)).not.toContainText('from the Administration sidebar');
  await expect(guide(page).locator('.ui-setup-guide-counter')).toHaveText(
    `Step 1 of ${id === 'tuner-allocation' ? 4 : ['recordings', 'activity'].includes(id) ? 2 : 1}`);
}

async function openPurpose(page, id, label) {
  await selectChapter(page, id, label);
  if (id === 'tuner-allocation') {
    await expect(guide(page)).toHaveAttribute('data-guide-step', 'tuner-allocation-channel');
    const channel = page.locator('[data-guide-target="admin-tuner-allocation-channel"]');
    await expect(channel).toBeVisible();
    await expect(channel).toHaveAccessibleName(/^Inspect County Site/);
    await expect(page.locator('.tuner-allocation-catalog .tuner-allocation-channel-choice')).toHaveCount(2);
    await expect(page.locator('.tuner-allocation-selected-channel')).toHaveCount(0);
    await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
  }
  try { await expect(guide(page)).toHaveAttribute('data-guide-step', `${id}-purpose`); }
  catch (error) { throw new Error(`${id} preparation failed. ${guideErrors.get(page).join('\n')}`, { cause: error }); }
  await expect(page.locator(`[data-guide-target="admin-${id}-panel"]`)).toBeVisible();
  await expect(page.locator('.admin-settings-content .page-title')).toHaveText(label);
}

async function expectLitPanel(page, id) {
  const panel = page.locator(`[data-guide-target="admin-${id}-panel"]`);
  await expect.poll(() => panel.evaluate(element => {
    const bounds = element.getBoundingClientRect();
    const visible = { left: Math.max(0, bounds.left), top: Math.max(0, bounds.top),
      right: Math.min(innerWidth, bounds.right), bottom: Math.min(innerHeight, bounds.bottom) };
    const dimmer = document.querySelector('.ui-setup-guide-dimmer');
    const maskId = dimmer?.getAttribute('mask')?.match(/#([^)]*)/)?.[1];
    const holes = [...(document.getElementById(maskId)?.querySelectorAll('.ui-setup-guide-mask-hole') || [])];
    const illuminated = holes.some(hole => {
      const left = Number(hole.getAttribute('x')), top = Number(hole.getAttribute('y'));
      return left <= visible.left + 1 && top <= visible.top + 1 &&
        left + Number(hole.getAttribute('width')) >= visible.right - 1 &&
        top + Number(hole.getAttribute('height')) >= visible.bottom - 1;
    });
    const coach = document.querySelector('.ui-setup-guide').getBoundingClientRect();
    const back = document.querySelector('.ui-setup-guide .ui-setup-guide-back') ||
      [...document.querySelectorAll('.ui-setup-guide button')].find(button => button.textContent.trim() === 'Back');
    const forward = [...document.querySelectorAll('.ui-setup-guide button')].find(button => /^(Next|Finish)$/.test(button.textContent.trim()));
    const a = back?.getBoundingClientRect(), b = forward?.getBoundingClientRect();
    return illuminated && a && b && Math.abs(a.top - b.top) <= 1 && a.right <= b.left &&
      coach.left >= 0 && coach.top >= 0 && coach.right <= innerWidth + 1 && coach.bottom <= innerHeight + 1;
  }), { message: 'The actual owning panel stays illuminated and guide navigation stays alongside' }).toBe(true);
}

async function expectRelatedPurposePanelsLit(page, id) {
  const primary = page.locator(`[data-guide-target="admin-${id}-panel"]`);
  const related = id === 'audio-quality' ? page.locator('[data-paging-tone-detection]') :
    page.locator('.admin-settings-content .ui-settings-panel').filter({
      has: page.locator('.ui-section-title').filter({ hasText: 'Pages & features' })
    });
  await expect(related).toBeInViewport();
  await expect.poll(() => related.evaluate(element => {
    const bounds = element.getBoundingClientRect();
    const visible = { left: Math.max(0, bounds.left), top: Math.max(0, bounds.top),
      right: Math.min(innerWidth, bounds.right), bottom: Math.min(innerHeight, bounds.bottom) };
    if (visible.right <= visible.left || visible.bottom <= visible.top) return false;
    const dimmer = document.querySelector('.ui-setup-guide-dimmer');
    const maskId = dimmer?.getAttribute('mask')?.match(/#([^)]*)/)?.[1];
    return [...(document.getElementById(maskId)?.querySelectorAll('.ui-setup-guide-mask-hole') || [])]
      .some(hole => Number(hole.getAttribute('x')) <= visible.left + 1 &&
        Number(hole.getAttribute('y')) <= visible.top + 1 &&
        Number(hole.getAttribute('x')) + Number(hole.getAttribute('width')) >= visible.right - 1 &&
        Number(hole.getAttribute('y')) + Number(hole.getAttribute('height')) >= visible.bottom - 1);
  }), { message: 'The related production panel stays readable around the main purpose highlight' }).toBe(true);
  await expect(page.locator('.ui-setup-guide-highlight')).toHaveCount(1);
  await expect(page.locator('.ui-setup-guide-highlight')).toHaveAttribute('data-setup-guide-target',
    await primary.getAttribute('id'));
}

function expectNoSampleEffects(state) {
  expect(state.effects.writes).toEqual([]);
  const sampleRequests = state.effects.requests.slice(state.initialRequestCount).filter(request =>
    / \/api\/v1\/(admin\/|remote-support(?:$|\/)|application-log(?:$|\/)|channel-catalog(?:$|\/))/.test(request));
  expect(sampleRequests, 'Sample Administration data stays inside its scoped provider').toEqual([]);
  expect(state.effects.sockets).toEqual([]);
  expect(state.fixture.pageErrors).toEqual([]);
  expect(state.fixture.unexpected).toEqual([]);
}

async function capture(page, testInfo, name) {
  if (evidence) fs.mkdirSync(evidence, { recursive: true });
  await page.screenshot({ path: evidence ? path.join(evidence, `${name}.png`) : testInfo.outputPath(`${name}.png`) });
}

for (const [index, [id, label, populated]] of chapters.entries()) {
  const theme = index % 2 ? 'dark' : 'light';
  test(`Administration guide opens ${label} with actual populated panels in ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1600, height: 1100 });
    const state = await installAdministration(page, { theme });
    const url = page.url(), storage = await nonGuideStorage(page);
    await expect(guide(page).locator('.ui-setup-guide-counter')).toHaveText('Overview');
    await expect(guide(page).locator('[data-guide-choice]')).toHaveCount(19);
    if (id === 'computer') await capture(page, testInfo, 'administration-overview-light');
    await openPurpose(page, id, label);
    await expect(page.locator('.admin-settings-content')).toContainText(populated);
    await expect.poll(() => page.locator('.admin-settings-content').evaluate(element => element.innerText))
      .not.toMatch(/could not be loaded|could not be read|Unknown Administration example|invalid response/i);
    await expectLitPanel(page, id);
    if (id === 'application-log') await capture(page, testInfo, `administration-${id}-first-step-${theme}`);
    if (['audio-quality', 'access'].includes(id)) {
      await expectRelatedPurposePanelsLit(page, id);
      await capture(page, testInfo, `administration-${id}-related-context-${theme}`);
    }
    if (id === 'tuner-allocation') {
      await capture(page, testInfo, `administration-${id}-${theme}`);
      await expect(page.locator('.tuner-allocation-selected-channel')).toContainText('County Site');
      await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
      await expect(guide(page)).toHaveAttribute('data-guide-step', 'tuner-allocation-back');
      await expect(page.locator('[data-guide-target="admin-tuner-allocation-back"]')).toHaveAccessibleName('Back to channels');
      await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
      await expect(guide(page)).toHaveAttribute('data-guide-step', 'tuner-allocation-catalog');
      await expect(page.locator('.tuner-allocation-selected-channel')).toHaveCount(0);
      await expect(page.locator('.tuner-allocation-catalog .tuner-allocation-channel-choice')).toHaveCount(2);
      await expectLitPanel(page, id);
    } else if (id === 'recordings') {
      await expect(guide(page)).toContainText('alias recording settings determine which calls are saved');
      await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
      await expect(guide(page)).toHaveAttribute('data-guide-step', 'recordings-retention');
      await expect(page.getByRole('spinbutton', { name: /^Remove managed calls older than \(days\)/ })).toHaveValue('30');
      await expect(guide(page)).toContainText('previously saved managed calls after switching to Classic');
    } else if (id === 'activity') {
      const collectionStatus = page.locator('#receiver-health-saved-activity');
      await expect(collectionStatus).toContainText('Current collection status');
      await expect(collectionStatus.getByRole('link', { name: 'Receiver status', exact: true })).toBeVisible();
      await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
      await expect(guide(page)).toHaveAttribute('data-guide-step', 'activity-retention');
      await expect(page.getByRole('spinbutton', { name: /^Keep activity history for \(days\)/ })).toHaveValue('30');
      await expect(guide(page)).toContainText('Saved audio has a separate retention setting');
    }
    if (['health', 'recordings', 'transcription'].includes(id))
      await capture(page, testInfo, `administration-${id}-${theme}`);
    await guide(page).getByRole('button', { name: 'Finish', exact: true }).click();
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator('.admin-settings-content .page-title')).toHaveText('Receiver status');
    await expect(page).toHaveURL(url);
    expect(await nonGuideStorage(page)).toEqual(storage);
    expectNoSampleEffects(state);
  });
}

for (const dismiss of ['Escape', 'Close guide', 'shade', 'Finish']) {
  test(`Administration guide restores real nodes, URL, scroll, and player after ${dismiss}`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const state = await installAdministration(page, { replay: false });
    await expect(page.locator('.receiver-health-page')).toBeVisible();
    await page.evaluate(() => {
      const content = document.getElementById('content');
      window.administrationOriginal = { content, nodes: [...content.childNodes],
        health: content.querySelector('.receiver-health-page'), player: document.getElementById('audio-dock') };
      window.scrollTo({ top: 120, behavior: 'instant' });
    });
    const url = page.url(), storage = await nonGuideStorage(page), player = await playerState(page);
    const scroll = await page.evaluate(() => ({ x: scrollX, y: scrollY }));
    // Invoke the real action without Playwright first scrolling its header into view.
    await page.locator('#page-guide-button').evaluate(button => { button.focus({ preventScroll: true }); button.click(); });
    await openPurpose(page, 'call-matching', 'Call matching');
    await expect(page.locator('.call-matching-workspace')).toContainText('Regional Fire');
    if (dismiss === 'shade') await page.mouse.click(4, 880);
    else if (dismiss === 'Escape') await page.keyboard.press('Escape');
    else await guide(page).getByRole('button', { name: dismiss, exact: true }).click();
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator('.receiver-health-page')).toBeVisible();
    expect(await page.locator('#content').evaluate(content => {
      const original = window.administrationOriginal;
      return content === original.content && original.nodes.every((node, index) => content.childNodes[index] === node) &&
        content.querySelector('.receiver-health-page') === original.health && document.getElementById('audio-dock') === original.player;
    })).toBe(true);
    await expect(page).toHaveURL(url);
    await expect.poll(() => page.evaluate(() => ({ x: scrollX, y: scrollY }))).toEqual(scroll);
    expect(await playerState(page)).toEqual(player);
    expect(await nonGuideStorage(page)).toEqual(storage);
    await expect(page.locator('[data-guide-sample], .ui-setup-guide-highlight, .ui-setup-guide-layer')).toHaveCount(0);
    await expect(page.locator('#page-guide-button')).toBeFocused();
    expectNoSampleEffects(state);
  });
}

test('Administration guide preserves an unsaved Live timing draft and normal save state', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const state = await installAdministration(page, { tab: 'display', replay: false });
  const field = page.getByRole('spinbutton', { name: /^Mark a traffic row idle after \(milliseconds\)/ });
  await expect(field).toBeEnabled();
  await field.fill('3250');
  const save = page.getByRole('button', { name: 'Save Live timing', exact: true });
  await expect(save).toBeEnabled();
  await page.locator('#page-guide-button').click();
  await openPurpose(page, 'recordings', 'Recording settings');
  await page.keyboard.press('Escape');
  await expect(field).toHaveValue('3250');
  await expect(save).toBeEnabled();
  // The initial real Display settings reads are allowed; the tour adds none.
  expectNoSampleEffects(state);
});

test('Administration guide choices follow the signed-in account permissions', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const state = await installAdministration(page, { denied: ['admin-settings', 'admin-recordings', 'admin-users', 'admin-access'] });
  await expect(guide(page).locator('[data-guide-choice]')).toHaveCount(2);
  await expect(guide(page).getByRole('button', { name: 'Computer & version', exact: true })).toBeVisible();
  await expect(guide(page).getByRole('button', { name: 'Receiver status', exact: true })).toBeVisible();
  for (const label of ['Recording settings', 'Remote Support', 'Web accounts', 'Page access'])
    await expect(guide(page).getByRole('button', { name: label, exact: true })).toHaveCount(0);
  await openPurpose(page, 'computer', 'Computer & version');
  await expect(page.locator('.admin-settings-content')).toContainText('Example 8-core processor');
  await page.keyboard.press('Escape');
  expectNoSampleEffects(state);
});

test('Administration first visit remembers one guide and Help starts the current section', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const state = await installAdministration(page, { firstVisit: true, replay: false });
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await page.keyboard.press('Escape');
  await expect(guide(page)).toHaveCount(0);
  await page.getByRole('navigation', { name: 'Administration sections', exact: true })
    .getByRole('link', { name: 'Computer & version', exact: true }).click();
  await expect(page.locator('.page-title')).toHaveText('Computer & version');
  await expect(guide(page)).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await expect(guide(page).getByRole('button', { name: 'Computer & version', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).click();
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'computer-purpose');
  await guide(page).getByRole('button', { name: 'Finish', exact: true }).click();
  await expect(guide(page)).toHaveCount(0);
  expect(await page.evaluate(() => Object.keys(localStorage).filter(key =>
    key.startsWith('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:admin:'))))
    .toEqual(['sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:admin:3']);
  expectNoSampleEffects(state);
});

test('Administration cancellation during a pending sample import restores the real page and stays closed', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const state = await installAdministration(page);
  let release, requested, served;
  const gate = new Promise(resolve => { release = resolve; });
  const requestStarted = new Promise(resolve => { requested = resolve; });
  const requestServed = new Promise(resolve => { served = resolve; });
  await page.route('**/assets/guides/administration-data.js*', async route => {
    requested();
    await gate;
    await route.continue();
    served();
  });
  await page.evaluate(() => { window.administrationPendingOriginal = document.querySelector('.admin-settings-shell'); });
  const url = page.url();
  try {
    await guide(page).getByRole('button', { name: 'Recording settings', exact: true }).click();
    await requestStarted;
    // Initial sample preparation shows the shared busy dialog before its step identity is committed.
    const pending = page.getByRole('dialog', { name: 'Administration', exact: true });
    await expect(pending).toHaveAttribute('aria-busy', 'true');
    await expect(pending.getByRole('button', { name: 'Close guide', exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(pending).toHaveCount(0);
  } finally { release(); }
  await requestServed;
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await expect(guide(page)).toHaveCount(0);
  expect(await page.evaluate(() => document.querySelector('.admin-settings-shell') === window.administrationPendingOriginal)).toBe(true);
  await expect(page.locator('.page-title')).toHaveText('Receiver status');
  await expect(page).toHaveURL(url);
  await expect(page.locator('[data-guide-example], [data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
  expectNoSampleEffects(state);
});

test('Administration guide Back returns to the common chooser and navigation releases the example', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const state = await installAdministration(page);
  await openPurpose(page, 'remote-links', 'Remote Links');
  await guide(page).getByRole('button', { name: 'Back', exact: true }).click();
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await expect(page.locator('.admin-settings-content .page-title')).toHaveText('Receiver status');
  await openPurpose(page, 'transcription', 'Transcription');
  await page.evaluate(() => { window.administrationLeavingExample = document.querySelector('.recordings-admin'); });
  await page.locator('#primary-navigation a[href="/?view=dashboard"]').evaluate(link => link.click());
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator('.page-title').first()).toHaveText('Main');
  expect(await page.evaluate(() => window.administrationLeavingExample.isConnected)).toBe(false);
  expect(state.effects.writes).toEqual([]);
  expect(state.fixture.pageErrors).toEqual([]);
});

test('Saved data cleanup example leaves an existing real cleanup job untouched', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.addInitScript(() => {
    const key = 'retained-statistics-job';
    const read = Storage.prototype.getItem;
    const write = Storage.prototype.setItem;
    const remove = Storage.prototype.removeItem;
    write.call(sessionStorage, key, 'existing-real-cleanup-job');
    window.cleanupJobStorageAccess = [];
    window.readExistingCleanupJob = () => read.call(sessionStorage, key);
    Storage.prototype.getItem = function (name) {
      if (this === sessionStorage && name === key) window.cleanupJobStorageAccess.push('read');
      return read.call(this, name);
    };
    Storage.prototype.setItem = function (name, value) {
      if (this === sessionStorage && name === key) window.cleanupJobStorageAccess.push('write');
      return write.call(this, name, value);
    };
    Storage.prototype.removeItem = function (name) {
      if (this === sessionStorage && name === key) window.cleanupJobStorageAccess.push('remove');
      return remove.call(this, name);
    };
  });
  const state = await installAdministration(page);
  await openPurpose(page, 'retained-statistics', 'Saved data cleanup');
  await expect(page.locator('.retained-statistics-job-notice')).toBeEmpty();
  expect(await page.evaluate(() => window.cleanupJobStorageAccess)).toEqual([]);
  expect(await page.evaluate(() => window.readExistingCleanupJob())).toBe('existing-real-cleanup-job');
  expect(state.effects.requests.filter(request => request.includes('/retained-statistics/deletions/'))).toEqual([]);
  await page.keyboard.press('Escape');
  await expect(guide(page)).toHaveCount(0);
  expect(await page.evaluate(() => window.cleanupJobStorageAccess)).toEqual([]);
  expect(await page.evaluate(() => window.readExistingCleanupJob())).toBe('existing-real-cleanup-job');
  expectNoSampleEffects(state);
});

test('Administration guide dismissal preserves a real support report recursive poll', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const state = await installAdministration(page, { tab: 'support', replay: false });
  let polls = 0, ready = false;
  await page.route('**/api/v1/admin/support-reports**', async route => {
    const request = route.request();
    if (request.method() === 'POST') {
      await route.fulfill({ json: { data: { id: 'original-report', state: 'queued',
        progress: 0, message: 'Preparing the original report', output: [] } } });
      return;
    }
    polls++;
    await route.fulfill({ json: { data: { id: 'original-report', state: ready ? 'ready' : 'generating',
      progress: ready ? 100 : 25, bytes: ready ? 2048 : 0,
      message: ready ? 'Report ready' : 'Still collecting original report details', output: [] } } });
  });
  const form = page.locator('#support-report-form');
  await form.getByRole('textbox', { name: 'Title', exact: true }).fill('Example reception problem');
  await form.getByRole('textbox', { name: 'Email address' }).fill('operator@example.invalid');
  await form.getByRole('textbox', { name: 'What happened', exact: true }).fill('The example channel stopped receiving.');
  await form.getByRole('textbox', { name: 'What happened before the problem?', exact: true }).fill('The example receiver was restarted.');
  await page.getByRole('button', { name: 'Prepare support report', exact: true }).click();
  await expect(page.locator('.support-report-message')).toHaveText('Preparing the original report');
  await page.evaluate(() => {
    window.administrationOriginalReport = { form: document.getElementById('support-report-form'),
      message: document.querySelector('.support-report-message') };
  });
  await page.locator('#page-guide-button').click();
  await openPurpose(page, 'call-matching', 'Call matching');
  await expect.poll(() => polls).toBeGreaterThan(0);
  await expect.poll(() => page.evaluate(() => window.administrationOriginalReport.message.textContent))
    .toBe('Still collecting original report details');
  const pollsDuringGuide = polls;
  await page.keyboard.press('Escape');
  await expect(guide(page)).toHaveCount(0);
  expect(await form.evaluate(element => element === window.administrationOriginalReport.form)).toBe(true);
  ready = true;
  await expect(page.locator('.support-report-message')).toHaveText('Report ready · 2 KB');
  expect(polls).toBeGreaterThan(pollsDuringGuide);
  await expect(page.getByRole('link', { name: 'Download report files', exact: true }))
    .toHaveAttribute('href', '/api/v1/admin/support-reports/original-report/download');
  expect(state.effects.writes).toEqual(['POST /api/v1/admin/support-reports']);
  expect(state.effects.requests.filter(request => request.includes('/admin/call-matching'))).toEqual([]);
});
