'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const guide = page => page.locator('.ui-setup-guide[data-guide-id="streaming"]');
const memoryKey = 'sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:streaming:3';
const steps = ['destinations', 'add-button', 'provider', 'add-cancel', 'feeds-button', 'feeds',
  'feeds-close', 'manage-button', 'sections', 'aliases-button', 'assign-alias', 'save-assignments',
  'status-button', 'delivery', 'recovery', 'destination-close', 'patch-routing'].map(id => ({ id,
  targets: id === 'recovery' ? ['st-recovery-state', 'st-recovery-error'] : [`st-${id}`] }));
const dialogSelector = '.streaming-editor-modal, .streaming-feeds-modal';
const nonGuideStorage = page => page.evaluate(() => Object.fromEntries(Object.entries(localStorage)
  .filter(([key]) => !key.startsWith('sdrtrunk-vce-guide:'))));

async function openStreaming(page, { theme = 'light', replay = true, firstVisit = false,
  denySettings = false } = {}) {
  const requests = { writes: [], samplePaths: [], audio: [] };
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/v1/') && !['GET', 'HEAD'].includes(request.method()) &&
        !(request.method() === 'PUT' && url.pathname === '/api/v1/me/tour-dismissals')) {
      requests.writes.push(`${request.method()} ${url.pathname}`);
    }
    if (/guide-sample|sample-stream|example-stream/.test(url.pathname)) requests.samplePaths.push(url.pathname);
    if (/\/api\/v1\/.*\/audio(?:\?|$)/.test(url.pathname)) requests.audio.push(url.pathname);
  });
  const fixture = await installSiteStyleApplication(page, theme, undefined, { guides: firstVisit });
  if (firstVisit) await page.addInitScript(() => {
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:welcome:1', '1');
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:streaming:2', '1');
  });
  if (denySettings) {
    const capabilityFile = path.resolve(__dirname, '../../../../../src/main/java/io/github/dsheirer/web/auth/WebCapability.java');
    const capabilities = Object.fromEntries([...fs.readFileSync(capabilityFile, 'utf8')
      .matchAll(/^\s+[A-Z_]+\("([^"]+)",/gm)].map(([, id]) => [id, id !== 'admin-settings']));
    await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
      configured: true, authenticated: true, username: 'fixture-admin', tier: 'admin', primary: true,
      csrf_token: 'fixture-csrf', guide_dismissal_scope: 'account-1-100', capabilities
    } } }));
  }
  await page.goto(`/app.html?view=streaming${replay ? '&guide=streaming' : ''}`);
  await expect(page.getByRole('heading', { name: 'Streaming', exact: true })).toBeVisible();
  return { fixture, requests };
}

async function startTour(page) {
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await expect(guide(page).getByRole('heading', { name: 'Share your radio traffic', exact: true })).toBeVisible();
  await expect(guide(page).locator('.ui-setup-guide-counter')).toHaveText('Overview');
  await expect(page.locator('.ui-setup-guide-highlight, [data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).click();
}

async function next(page, id, sequence = steps) {
  await guide(page).getByRole('button', { name: id === sequence.at(-1).id ? 'Finish' : 'Next', exact: true }).click();
}

async function goToStep(page, id) {
  for (const step of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    if (step.id === id) return;
    await next(page, step.id);
  }
  throw new Error(`Unknown Streaming tour step: ${id}`);
}

async function expectWithinViewport(locator, viewport) {
  await expect.poll(async () => {
    const bounds = await locator.boundingBox();
    return bounds && bounds.x >= 0 && bounds.y >= 0 &&
      bounds.x + bounds.width <= viewport.width + 1 && bounds.y + bounds.height <= viewport.height + 1 ? null : bounds;
  }, { message: `${await locator.getAttribute('data-guide-target') || 'Guide control'} is fully within the viewport` }).toBeNull();
}

async function expectSpotlight(page, id) {
  const items = page.locator(`[data-guide-target="${id}"]:visible`);
  await expect(items.first()).toBeVisible();
  for (const item of await items.all()) {
    await expectWithinViewport(item, page.viewportSize());
    await expect.poll(() => item.evaluate(element => {
      const rect = element.getBoundingClientRect();
      return [...document.querySelectorAll('.ui-setup-guide-highlight')].some(highlight => {
        const bounds = highlight.getBoundingClientRect();
        return bounds.left <= rect.left + 1 && bounds.top <= rect.top + 1 &&
          bounds.right >= rect.right - 1 && bounds.bottom >= rect.bottom - 1;
      });
    }), { message: `${id} has a spotlight on its real control` }).toBe(true);
    await expect.poll(() => item.evaluate(element => {
      const rect = element.getBoundingClientRect();
      const card = document.querySelector('.ui-setup-guide').getBoundingClientRect();
      return Math.min(rect.right, card.right) - Math.max(rect.left, card.left) <= 1 ||
        Math.min(rect.bottom, card.bottom) - Math.max(rect.top, card.top) <= 1;
    }), { message: `${id} remains readable beside the guide` }).toBe(true);
  }
}

function expectNoEffects(fixture, requests) {
  expect(requests.writes).toEqual([]);
  expect(requests.samplePaths).toEqual([]);
  expect(requests.audio).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
}

async function surfaceSignature(dialog) {
  await expect(dialog).toBeVisible();
  return dialog.evaluate(root => {
    const style = getComputedStyle(root);
    return {
      classes: [...root.classList].filter(name => name !== 'ui-setup-guide-layer').sort(),
      title: root.querySelector('.modal-header h2')?.textContent,
      structure: [...root.querySelectorAll('form, fieldset, legend, .ui-form-workflow-body, .ui-segmented-control, .streaming-panel, .streaming-fields, .streaming-advanced, .streaming-alias-filters, .streaming-alias-actions, .streaming-alias-table-region, .streaming-feed-grid, .streaming-feed-card, .ui-metric-grid, .ui-facts, .ui-modal-footer')]
        .map(element => [element.tagName, element.className, element.hidden]),
      fields: [...root.querySelectorAll('input, select, textarea')].map(element => [element.tagName,
        element.getAttribute('name'), element.getAttribute('type'), element.className, element.value,
        element.type === 'checkbox' || element.type === 'radio' ? element.checked : null]),
      actions: [...root.querySelectorAll('.ui-modal-footer button')].map(element => [element.textContent, element.hidden, element.disabled]),
      visibleText: [...root.querySelectorAll('.streaming-panel:not([hidden]) .ui-metric, .streaming-panel:not([hidden]) .ui-fact, .streaming-feed-card')]
        .map(element => element.textContent),
      paint: { font: style.fontFamily, background: style.backgroundColor, color: style.color,
        radius: style.borderRadius, shadow: style.boxShadow }
    };
  });
}

for (const { theme, width, height } of [
  { theme: 'light', width: 1280, height: 720 },
  { theme: 'dark', width: 1440, height: 1000 }
]) {
  test(`Streaming follows actual destination, feed, assignment, and status surfaces in ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height });
    const { fixture, requests } = await openStreaming(page, { theme });
    const playerBefore = await page.locator('#audio-dock').evaluate(element => ({ source: element.dataset.source,
      state: element.dataset.state, collapsed: element.dataset.superCollapsed }));
    await startTour(page);
    const originalUrl = page.url();
    for (const [index, step] of steps.entries()) {
      await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
      await expect(guide(page)).toContainText(`Step ${index + 1} of ${steps.length}`);
      for (const id of step.targets) await expectSpotlight(page, id);
      await expectWithinViewport(guide(page), { width, height });
      await expectWithinViewport(guide(page).getByRole('button', { name: 'Close guide', exact: true }), { width, height });
      await expectWithinViewport(guide(page).getByRole('button', {
        name: index === steps.length - 1 ? 'Finish' : 'Next', exact: true }), { width, height });
      await expect(page.locator('.primary-nav details[open]')).toHaveCount(0);
      if (['provider', 'add-cancel'].includes(step.id)) {
        await expect(page.locator('.streaming-editor-modal')).toContainText('Add streaming destination');
        await expect(page.locator('.streaming-editor-modal').getByRole('combobox', { name: 'Provider', exact: true })).toBeVisible();
      }
      if (step.id === 'feeds') {
        await expect(page.locator('.streaming-feed-card')).toHaveCount(2);
        await expect(page.locator('.streaming-feed-browser')).toContainText('Already added');
        await expect(page.locator('.streaming-feed-browser')).toContainText('Add feed');
      }
      if (step.id === 'sections') {
        await expect(page.locator('.streaming-editor-modal [aria-label="Streaming destination sections"] button')).toHaveText(['Settings', 'Aliases', 'Status']);
        await expect(page.locator('.streaming-editor-modal .streaming-fields input').first()).toHaveValue('County Calls');
      }
      if (['assign-alias', 'save-assignments'].includes(step.id)) {
        const regional = page.locator('.streaming-editor-modal').getByRole('checkbox', { name: 'Send Regional Fire to this destination', exact: true });
        await expect(regional).toBeVisible();
        if (step.id === 'assign-alias') await expect(regional).not.toBeChecked();
        else {
          await expect(regional).toBeChecked();
          await expect(page.locator('.streaming-editor-modal').getByRole('button', { name: 'Save assignments', exact: true })).toBeVisible();
        }
        await expect(page.locator('table[data-table-type="streaming-aliases"]')).toContainText('County P25');
        await expect(page.locator('table[data-table-type="streaming-aliases"]')).toContainText('City P25');
      }
      if (['delivery', 'recovery'].includes(step.id)) {
        const status = page.locator('.streaming-panel:not([hidden])');
        await expect(status).toContainText('Connected');
        await expect(status).toContainText('128');
        await expect(status).toContainText(/timed out|timeout/i);
      }
      if (['destinations', 'provider', 'feeds', 'sections', 'assign-alias', 'delivery', 'recovery', 'patch-routing'].includes(step.id)) {
        await page.screenshot({ path: testInfo.outputPath(`streaming-${step.id}-${theme}.png`) });
      }
      await next(page, step.id);
    }
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator(`[data-guide-sample], .ui-setup-guide-layer, ${dialogSelector}`)).toHaveCount(0);
    await expect(page).toHaveURL(originalUrl);
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-source', playerBefore.source);
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-state', playerBefore.state);
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-super-collapsed', playerBefore.collapsed);
    expectNoEffects(fixture, requests);
  });
}

test('Streaming tour matches the ordinary production editor, feed picker, assignments, and status', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const { fixture } = await openStreaming(page, { replay: false });
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/guides/streaming-presentation.js');
  const { createStreamingGuideData } = await import(pathToFileURL(file).href);
  const sample = createStreamingGuideData();
  const sampleResponse = async route => {
    const request = route.request(), url = new URL(request.url());
    const body = request.postData() ? request.postDataJSON() : undefined;
    const data = await sample.requestJson(url.pathname + url.search, { method: request.method(), body });
    return route.fulfill({ json: { data } });
  };
  await page.route('**/api/v1/admin/streaming**', sampleResponse);
  await page.route('**/api/v1/admin/radioreference', sampleResponse);
  await page.reload();
  const originals = {};
  await page.getByRole('button', { name: 'Add destination', exact: true }).click();
  await expect(page.locator('.streaming-editor-modal .streaming-fields input').first()).toBeVisible();
  originals.provider = await surfaceSignature(page.locator('.streaming-editor-modal'));
  await page.locator('.streaming-editor-modal').getByRole('button', { name: 'Cancel', exact: true }).click();
  await page.getByRole('button', { name: 'Find Broadcastify feeds', exact: true }).click();
  await expect(page.locator('.streaming-feed-card')).toHaveCount(2);
  originals.feeds = await surfaceSignature(page.locator('.streaming-feeds-modal'));
  await page.locator('.streaming-feeds-modal .modal-close').click();
  const county = page.locator('.streaming-destination-card').filter({ hasText: 'County Calls' });
  await county.getByRole('button', { name: 'Manage destination', exact: true }).click();
  await expect(page.locator('.streaming-editor-modal .streaming-fields input').first()).toBeVisible();
  originals.sections = await surfaceSignature(page.locator('.streaming-editor-modal'));
  await page.locator('.streaming-editor-modal').getByRole('button', { name: 'Aliases', exact: true }).click();
  const regional = page.locator('.streaming-editor-modal').getByRole('checkbox', { name: 'Send Regional Fire to this destination', exact: true });
  await expect(regional).toBeVisible();
  originals['assign-alias'] = await surfaceSignature(page.locator('.streaming-editor-modal'));
  await regional.check();
  originals['save-assignments'] = await surfaceSignature(page.locator('.streaming-editor-modal'));
  await page.locator('.streaming-editor-modal').getByRole('button', { name: 'Save assignments', exact: true }).click();
  await expect(page.locator('.streaming-editor-modal').getByRole('button', { name: 'Save assignments', exact: true })).toBeHidden();
  await page.locator('.streaming-editor-modal').getByRole('button', { name: 'Status', exact: true }).click();
  await expect(page.locator('.streaming-panel:not([hidden])')).toContainText('128');
  originals.delivery = await surfaceSignature(page.locator('.streaming-editor-modal'));
  await page.locator('.streaming-editor-modal .modal-close').click();
  await page.locator('#page-guide-button').click();
  await startTour(page);
  for (const step of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    if (originals[step.id]) {
      const selector = step.id === 'feeds' ? '.streaming-feeds-modal' : '.streaming-editor-modal';
      expect(await surfaceSignature(page.locator(selector)), `${step.id} matches ordinary production UI`).toEqual(originals[step.id]);
      await page.screenshot({ path: testInfo.outputPath(`streaming-parity-${step.id}.png`) });
    }
    await next(page, step.id);
  }
  expect(fixture.pageErrors).toEqual([]);
});

test('Streaming sample controls cannot save, connect, add feeds, change assignments, or patch preferences', async ({ page }) => {
  const { fixture, requests } = await openStreaming(page);
  await startTour(page);
  const routeBefore = page.url(), preferencesBefore = await nonGuideStorage(page);
  let exercised = 0;
  for (const step of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    exercised += await page.evaluate(() => {
      const roots = [...document.querySelectorAll('[data-guide-sample], .streaming-editor-modal, .streaming-feeds-modal')];
      const controls = [...new Set(roots.flatMap(root => [...root.querySelectorAll('button, a, input, select, form')]))];
      for (const control of controls) {
        control.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
        if (control.tagName === 'FORM') control.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      }
      return controls.length;
    });
    await expect(page).toHaveURL(routeBefore);
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    await next(page, step.id);
  }
  expect(exercised).toBeGreaterThan(50);
  expect(await nonGuideStorage(page)).toEqual(preferencesBefore);
  expectNoEffects(fixture, requests);
});

for (const { dismissal, stop } of [
  { dismissal: 'Close guide', stop: 'provider' },
  { dismissal: 'Escape', stop: 'feeds' },
  { dismissal: 'darkened area', stop: 'save-assignments' },
  { dismissal: 'Escape', stop: 'recovery' },
  { dismissal: 'Close guide', stop: 'patch-routing' }
]) {
  test(`Streaming restores page nodes, scroll, preferences, and player after ${dismissal} from ${stop}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    const { fixture, requests } = await openStreaming(page, { replay: false });
    await page.locator('#content').evaluate(element => {
      window.streamingGuideOriginal = { content: element, children: [...element.childNodes],
        page: element.querySelector('.streaming-page') };
    });
    const routeBefore = page.url(), titleBefore = await page.title();
    const storageBefore = await nonGuideStorage(page);
    const scrollBefore = await page.evaluate(() => ({ x: scrollX, y: scrollY }));
    const player = page.locator('#audio-dock');
    const playerBefore = await player.evaluate(element => ({ source: element.dataset.source,
      state: element.dataset.state, collapsed: element.dataset.superCollapsed }));
    await page.locator('#page-guide-button').click();
    await startTour(page);
    await goToStep(page, stop);
    await guide(page).getByRole('button', { name: 'Back', exact: true }).click();
    await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
    await expect(guide(page)).toHaveAttribute('data-guide-step', stop);
    if (dismissal === 'Escape') await page.keyboard.press('Escape');
    else if (dismissal === 'darkened area') await page.mouse.click(8, 500);
    else await guide(page).getByRole('button', { name: dismissal, exact: true }).click();
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator(`[data-guide-sample], .ui-setup-guide-layer, ${dialogSelector}`)).toHaveCount(0);
    await expect(page).toHaveURL(routeBefore);
    await expect(page).toHaveTitle(titleBefore);
    expect(await page.locator('#content').evaluate(element => {
      const original = window.streamingGuideOriginal;
      return element === original.content && element.querySelector('.streaming-page') === original.page &&
        original.children.every((node, index) => element.childNodes[index] === node);
    })).toBe(true);
    expect(await nonGuideStorage(page)).toEqual(storageBefore);
    expect(await page.evaluate(() => ({ x: scrollX, y: scrollY }))).toEqual(scrollBefore);
    await expect(player).toHaveAttribute('data-source', playerBefore.source);
    await expect(player).toHaveAttribute('data-state', playerBefore.state);
    await expect(player).toHaveAttribute('data-super-collapsed', playerBefore.collapsed);
    await expect(page.locator('#page-guide-button')).toBeFocused();
    expectNoEffects(fixture, requests);
  });
}

test('Streaming first desktop visit opens once and Help and named URL replay it', async ({ page }) => {
  const { fixture, requests } = await openStreaming(page, { replay: false, firstVisit: true });
  await startTour(page);
  await page.keyboard.press('Escape');
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBe('1');
  await page.reload();
  await expect(page.locator('#page-guide-button')).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await startTour(page);
  await page.keyboard.press('Escape');
  await page.goto('/app.html?view=streaming&guide=streaming');
  await startTour(page);
  await page.keyboard.press('Escape');
  expectNoEffects(fixture, requests);
});

test('Streaming navigation releases its sample page and actual dialogs before another route', async ({ page }) => {
  const { fixture, requests } = await openStreaming(page);
  await startTour(page);
  await goToStep(page, 'save-assignments');
  await page.locator('.primary-nav [data-view="dashboard"]').evaluate(link => link.click());
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(page.locator(`[data-guide-sample], .ui-setup-guide-layer, .ui-setup-guide, ${dialogSelector}`)).toHaveCount(0);
  await page.locator('.primary-nav [data-view="streaming"]').evaluate(link => link.click());
  await expect(page.locator('.streaming-page')).toBeVisible();
  await expect(page.locator('.streaming-destination-card')).toHaveCount(1);
  expectNoEffects(fixture, requests);
});

test('phone visits keep the desktop Streaming tour and samples closed', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const { fixture, requests } = await openStreaming(page, { replay: false, firstVisit: true });
  await expect(page.locator('.streaming-page')).toBeVisible();
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
  await expect(page.locator('#page-guide-button')).toBeHidden();
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBeNull();
  expectNoEffects(fixture, requests);
});

test('dismissal during preparation cannot reopen a Streaming editor', async ({ page }) => {
  const { fixture, requests } = await openStreaming(page);
  await startTour(page);
  await goToStep(page, 'add-button');
  await guide(page).getByRole('button', { name: 'Next', exact: true }).evaluate(button => {
    button.click();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
  });
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator(`[data-guide-sample], .ui-setup-guide-layer, ${dialogSelector}`)).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await startTour(page);
  await expect(guide(page)).toHaveAttribute('data-guide-step', steps[0].id);
  await page.keyboard.press('Escape');
  expectNoEffects(fixture, requests);
});

test('Streaming omits Broadcastify account discovery and patch preference lessons without settings access', async ({ page }) => {
  const { fixture, requests } = await openStreaming(page, { denySettings: true });
  const permitted = steps.filter(({ id }) => !['feeds-button', 'feeds', 'feeds-close', 'patch-routing'].includes(id));
  await startTour(page);
  for (const [index, step] of permitted.entries()) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    await expect(guide(page)).toContainText(`Step ${index + 1} of 13`);
    for (const id of step.targets) await expectSpotlight(page, id);
    await expect(page.locator('.streaming-feeds-modal')).toHaveCount(0);
    await next(page, step.id, permitted);
  }
  await expect(guide(page)).toHaveCount(0);
  expect(requests.writes).toEqual([]);
  expect(fixture.requests.filter(value => String(value).includes('/radioreference'))).toEqual([]);
  expectNoEffects(fixture, requests);
});

for (const theme of ['light', 'dark']) {
  test(`Streaming actual populated surfaces retain readable contrast in ${theme} and representative hues`, async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.setViewportSize({ width: 1440, height: 1000 });
    const { fixture, requests } = await openStreaming(page, { theme });
    await startTour(page);
    for (const step of steps) {
      await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
      if (['destinations', 'provider', 'feeds', 'assign-alias', 'delivery'].includes(step.id)) {
        await page.mouse.move(0, 0);
        await page.evaluate(() => {
          const backdrop = document.querySelector('.ui-setup-guide-backdrop');
          window.streamingGuideContrastState = { backdrop, visibility: backdrop.style.visibility,
            inert: [...document.querySelectorAll('[inert]')] };
          backdrop.style.visibility = 'hidden';
          window.streamingGuideContrastState.inert.forEach(element => { element.inert = false; });
        });
        try {
          for (const hue of [null, 0, 120, 240]) {
            await page.evaluate(async value => {
              const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
              applyThemeHue(value);
            }, hue);
            const rootSelector = step.id === 'destinations' ? '.streaming-page[data-guide-sample]' :
              step.id === 'feeds' ? '.streaming-feeds-modal' : '.streaming-editor-modal';
            const result = await expectContrast(page, { rootSelector,
              reportName: `streaming-guide-${step.id}-${theme}-hue-${hue}` });
            expect(result.summary.text.checked).toBeGreaterThan(5);
          }
        } finally {
          await page.evaluate(async () => {
            const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
            applyThemeHue(null);
            const state = window.streamingGuideContrastState;
            state.inert.forEach(element => { element.inert = true; });
            state.backdrop.style.visibility = state.visibility;
            delete window.streamingGuideContrastState;
          });
        }
      }
      await next(page, step.id);
    }
    expectNoEffects(fixture, requests);
  });
}
