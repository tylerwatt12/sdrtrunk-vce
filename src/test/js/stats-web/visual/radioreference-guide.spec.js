'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const guide = page => page.locator('.ui-setup-guide[data-guide-id="radioreference"]');
const samples = page => page.locator('.radioreference-page[data-guide-sample]');
const memoryKey = 'sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:radioreference:3';
const steps = [
  { id: 'area-groups', targets: ['rr-area-county', 'rr-area-state', 'rr-area-national'] },
  { id: 'sort', targets: ['rr-sort'] },
  { id: 'bookmark', targets: ['rr-bookmark'] },
  { id: 'bookmarks-button', targets: ['rr-bookmarks-button'] },
  { id: 'saved-system', targets: ['rr-saved-system'] },
  { id: 'system-sections', targets: ['rr-sites', 'rr-talkgroups-button'] },
  { id: 'site-button', targets: ['rr-site-button'] },
  { id: 'site-import', targets: ['rr-site-import'] },
  { id: 'channel-review-button', targets: ['rr-channel-review-button'] },
  { id: 'channel-preview', targets: ['rr-channel-preview', 'rr-channel-apply'] },
  { id: 'channel-complete-close', targets: ['rr-channel-complete-close'] },
  { id: 'talkgroups-button', targets: ['rr-talkgroups-button'] },
  { id: 'alias-list', targets: ['rr-alias-list'] },
  { id: 'comparison', targets: ['rr-comparison'] },
  { id: 'select-update', targets: ['rr-select-update'] },
  { id: 'import-selected', targets: ['rr-import-selected'] },
  { id: 'alias-preview', targets: ['rr-alias-preview', 'rr-alias-apply'] },
  { id: 'alias-complete-close', targets: ['rr-alias-complete-close'] }
];

async function openRadioReference(page, { theme = 'light', replay = true, firstVisit = false } = {}) {
  const requests = { writes: [], samplePaths: [], external: [], audio: [] };
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/v1/') && !['GET', 'HEAD'].includes(request.method()) &&
        !(request.method() === 'PUT' && url.pathname === '/api/v1/me/tour-dismissals')) {
      requests.writes.push(`${request.method()} ${url.pathname}`);
    }
    if (/guide-sample|example-county|sample-preview/.test(url.pathname)) requests.samplePaths.push(url.pathname);
    if (/radioreference\.com$/i.test(url.hostname)) requests.external.push(url.href);
    if (/\/api\/v1\/.*\/audio(?:\?|$)/.test(url.pathname)) requests.audio.push(url.pathname);
  });
  const fixture = await installSiteStyleApplication(page, theme, undefined, { guides: firstVisit });
  if (firstVisit) await page.addInitScript(() => {
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:welcome:1', '1');
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:radioreference:1', '1');
  });
  await page.goto(`/app.html?view=radioreference${replay ? '&guide=radioreference' : ''}`);
  await expect(page.getByRole('heading', { name: 'RadioReference', exact: true })).toBeVisible();
  return { fixture, requests };
}

async function startTour(page) {
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await expect(guide(page).getByRole('heading', { name: 'Bring radio information into VCE', exact: true })).toBeVisible();
  await expect(guide(page).locator('.ui-setup-guide-counter')).toHaveText('Overview');
  await expect(guide(page)).not.toContainText(/Step \d+ of/);
  await expect(page.locator('.ui-setup-guide-highlight, [data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).click();
}

async function expectWithinViewport(locator, viewport) {
  await expect.poll(async () => {
    const bounds = await locator.boundingBox();
    return bounds && bounds.x >= 0 && bounds.y >= 0 &&
      bounds.x + bounds.width <= viewport.width + 1 && bounds.y + bounds.height <= viewport.height + 1;
  }).toBeTruthy();
}

async function expectSpotlight(page, id) {
  const items = page.locator(`[data-guide-target="${id}"]:visible`);
  await expect(items.first()).toBeVisible();
  for (const item of await items.all()) {
    await expectWithinViewport(item, page.viewportSize());
    await expect.poll(() => item.evaluate(element => {
      const rect = element.getBoundingClientRect();
      const clipped = { left: Math.max(0, rect.left), top: Math.max(0, rect.top),
        right: Math.min(innerWidth, rect.right), bottom: Math.min(innerHeight, rect.bottom) };
      if (clipped.right <= clipped.left || clipped.bottom <= clipped.top) return false;
      return [...document.querySelectorAll('.ui-setup-guide-highlight')].some(highlight => {
        const bounds = highlight.getBoundingClientRect();
        return bounds.left <= clipped.left + 1 && bounds.top <= clipped.top + 1 &&
          bounds.right >= clipped.right - 1 && bounds.bottom >= clipped.bottom - 1;
      });
    }), { message: `${id} has a spotlight on its actual visible control` }).toBe(true);
    await expect.poll(() => item.evaluate(element => {
      const rect = element.getBoundingClientRect();
      const card = document.querySelector('.ui-setup-guide').getBoundingClientRect();
      return Math.min(rect.right, card.right) - Math.max(rect.left, card.left) <= 1 ||
        Math.min(rect.bottom, card.bottom) - Math.max(rect.top, card.top) <= 1;
    }), { message: `${id} remains readable beside the guide` }).toBe(true);
  }
}

async function next(page, id) {
  await guide(page).getByRole('button', { name: id === steps.at(-1).id ? 'Finish' : 'Next', exact: true }).click();
}

async function goToStep(page, id) {
  for (const step of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    if (step.id === id) return;
    await next(page, step.id);
  }
  throw new Error(`Unknown RadioReference tour step: ${id}`);
}

function expectNoEffects(fixture, requests) {
  expect(requests.writes).toEqual([]);
  expect(requests.samplePaths).toEqual([]);
  expect(requests.external).toEqual([]);
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
      structure: [...root.querySelectorAll('form, fieldset, legend, .radioreference-frequency-modes, .radioreference-frequency-choices, .radioreference-preview-summary, .radioreference-preview-facts, .radioreference-talkgroup-changes, .radioreference-import-complete, .ui-modal-footer')]
        .map(element => [element.tagName, element.className]),
      fields: [...root.querySelectorAll('input, select, textarea')].map(element => [element.tagName,
        element.getAttribute('name'), element.getAttribute('type'), element.className,
        element.value, element.type === 'checkbox' || element.type === 'radio' ? element.checked : null]),
      actions: [...root.querySelectorAll('.ui-modal-footer button, .radioreference-completion-actions a')]
        .map(element => element.textContent),
      paint: { font: style.fontFamily, background: style.backgroundColor, color: style.color,
        radius: style.borderRadius, shadow: style.boxShadow }
    };
  });
}

test('the tour matches ordinary site import, channel preview, update comparison, and completion dialogs', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const { fixture } = await openRadioReference(page, { replay: false });
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/guides/radioreference-presentation.js');
  const { createRadioReferenceGuideData } = await import(pathToFileURL(file).href);
  const sample = createRadioReferenceGuideData();
  await page.route('**/api/v1/admin/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/v1/admin/radioreference') && url.pathname !== '/api/v1/admin/alias-lists') {
      return route.fallback();
    }
    const body = request.postData() ? request.postDataJSON() : undefined;
    const data = await sample.requestJson(url.pathname + url.search, { method: request.method(), body });
    return route.fulfill({ json: { data } });
  });
  await page.reload();
  const original = {};
  const county = page.locator('.radioreference-directory-branch').filter({ hasText: 'County results' }).first();
  await expect(county).toBeVisible();
  if (!await county.evaluate(element => element.open)) await county.locator(':scope > summary').click();
  await page.locator('.radioreference-result-open').filter({ hasText: 'County P25' }).click();
  await page.locator('.radioreference-site-import').filter({ hasText: 'North Site' }).click();
  const dialog = page.locator('.radioreference-import-modal');
  original['site-import'] = await surfaceSignature(dialog);
  await dialog.getByRole('button', { name: 'Review Channel', exact: true }).click();
  await expect(dialog.locator('.radioreference-preview')).toBeVisible();
  original['channel-preview'] = await surfaceSignature(dialog);
  await dialog.getByRole('button', { name: 'Apply Channel', exact: true }).click();
  await expect(dialog).toHaveClass(/radioreference-import-modal-complete/);
  original['channel-complete-close'] = await surfaceSignature(dialog);
  await dialog.locator('.modal-close').click();
  await page.getByRole('button', { name: 'Talkgroups & Aliases', exact: true }).click();
  const changedRow = page.locator('.radioreference-talkgroup-table tbody tr').filter({ hasText: 'Sheriff Dispatch 1' });
  await changedRow.getByRole('checkbox').check();
  await page.getByRole('button', { name: 'Import selected', exact: true }).click();
  await expect(dialog.locator('.radioreference-talkgroup-changes')).toBeVisible();
  original['alias-preview'] = await surfaceSignature(dialog);
  await dialog.getByRole('button', { name: 'Apply Import', exact: true }).click();
  await expect(dialog).toHaveClass(/radioreference-import-modal-complete/);
  original['alias-complete-close'] = await surfaceSignature(dialog);
  await dialog.locator('.modal-close').click();
  await page.locator('#page-guide-button').click();
  await startTour(page);
  for (const step of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    if (original[step.id]) {
      expect(await surfaceSignature(dialog), `${step.id} uses the ordinary production dialog`).toEqual(original[step.id]);
      await page.screenshot({ path: testInfo.outputPath(`ordinary-parity-${step.id}.png`) });
    }
    await next(page, step.id);
  }
  expect(fixture.pageErrors).toEqual([]);
});

for (const { theme, width, height } of [
  { theme: 'light', width: 1280, height: 720 },
  { theme: 'dark', width: 1440, height: 1000 }
]) {
  test(`RadioReference tour follows actual import and comparison surfaces ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height });
    const { fixture, requests } = await openRadioReference(page, { theme });
    const originalUrl = page.url();
    const playerBefore = await page.locator('#audio-dock').evaluate(element => ({
      source: element.dataset.source, state: element.dataset.state }));
    await startTour(page);
    for (const [index, step] of steps.entries()) {
      await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
      await expect(guide(page)).toContainText(`Step ${index + 1} of ${steps.length}`);
      for (const id of step.targets) await expectSpotlight(page, id);
      await expectWithinViewport(guide(page), { width, height });
      await expectWithinViewport(guide(page).getByRole('button', { name: 'Close guide', exact: true }), { width, height });
      await expectWithinViewport(guide(page).getByRole('button', {
        name: index === steps.length - 1 ? 'Finish' : 'Next', exact: true }), { width, height });
      await expect(page.locator('.primary-nav details[open]')).toHaveCount(0);
      if (['site-import', 'channel-review-button'].includes(step.id)) {
        const dialog = page.locator('.radioreference-import-modal');
        await expect(dialog.locator('form.radioreference-site-form')).toBeVisible();
        await expect(dialog.getByRole('button', { name: 'Review Channel', exact: true })).toBeVisible();
        await expect(dialog).toContainText('North Site');
        await expect(samples(page).locator('.radioreference-system-workspace')).toBeVisible();
      }
      if (step.id === 'channel-preview') {
        await expect(page.locator('.radioreference-import-modal .radioreference-preview-facts')).toContainText('P25');
        await expect(page.locator('.radioreference-preview-facts')).toContainText('851.0125');
      }
      if (step.id === 'channel-complete-close') {
        await expect(page.locator('.radioreference-import-modal-complete')).toContainText('Channel saved');
        await expect(page.locator('.radioreference-completion-actions')).toContainText('Open channel');
      }
      if (step.id === 'comparison') {
        const status = page.locator('.radioreference-talkgroup-status');
        await expect(status).toHaveCount(3);
        expect((await status.allTextContents()).sort()).toEqual(['Different', 'Identical', 'Not present'].sort());
      }
      if (step.id === 'alias-preview') {
        const change = page.locator('.radioreference-talkgroup-changes');
        await expect(change).toContainText('Sheriff Dispatch');
        await expect(change).toContainText('Sheriff Dispatch 1');
        await expect(page.locator('.radioreference-preview-summary')).toContainText('Update');
        await expect(page.locator('.radioreference-import-modal')).toContainText('Local handling stays intact');
      }
      if (step.id === 'alias-complete-close') {
        await expect(page.locator('.radioreference-import-modal-complete')).toContainText('Talkgroups imported');
        await expect(page.locator('.radioreference-completion-actions')).toContainText('Open Alias List');
      }
      if (['area-groups', 'system-sections', 'site-import', 'channel-preview', 'channel-complete-close',
        'comparison', 'alias-preview', 'alias-complete-close'].includes(step.id)) {
        await page.screenshot({ path: testInfo.outputPath(`radioreference-${step.id}-${theme}.png`) });
      }
      await next(page, step.id);
    }
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .radioreference-import-modal')).toHaveCount(0);
    await expect(page.locator('.radioreference-login-gate')).toBeVisible();
    await expect(page).toHaveURL(originalUrl);
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-source', playerBefore.source);
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-state', playerBefore.state);
    expectNoEffects(fixture, requests);
  });
}

test('RadioReference sample actions cannot persist bookmarks, imports, updates, credentials, or preferences', async ({ page }) => {
  const { fixture, requests } = await openRadioReference(page);
  await startTour(page);
  const routeBefore = page.url();
  const preferencesBefore = await page.evaluate(() => Object.fromEntries(Object.entries(localStorage)
    .filter(([key]) => !key.startsWith('sdrtrunk-vce-guide:'))));
  let exercised = 0;
  for (const step of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    exercised += await page.evaluate(() => {
      const roots = [...document.querySelectorAll('[data-guide-sample], .radioreference-import-modal')];
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
  expect(exercised).toBeGreaterThan(30);
  expect(await page.evaluate(() => Object.fromEntries(Object.entries(localStorage)
    .filter(([key]) => !key.startsWith('sdrtrunk-vce-guide:'))))).toEqual(preferencesBefore);
  expectNoEffects(fixture, requests);
});

for (const { dismissal, stop } of [
  { dismissal: 'Close guide', stop: 'site-import' },
  { dismissal: 'Escape', stop: 'channel-preview' },
  { dismissal: 'darkened area', stop: 'alias-preview' }
]) {
  test(`RadioReference restores the original page and drafts after ${dismissal} from ${stop}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    const { fixture, requests } = await openRadioReference(page, { replay: false });
    const username = page.locator('input[name="radioreference-username"]');
    const password = page.locator('input[name="radioreference-password"]');
    await username.fill('unsaved-radio-account');
    await password.fill('unsaved-test-password');
    const original = await page.locator('.radioreference-page').evaluate(element => {
      window.radioReferenceGuideOriginal = { page: element, children: [...element.childNodes],
        account: element.querySelector('.radioreference-account-form') };
      return { html: element.innerHTML, scroll: window.scrollY };
    });
    const routeBefore = page.url();
    await page.locator('#page-guide-button').click();
    await startTour(page);
    await goToStep(page, stop);
    await guide(page).getByRole('button', { name: 'Back', exact: true }).click();
    await expect(guide(page)).toHaveAttribute('data-guide-step', steps[steps.findIndex(step => step.id === stop) - 1].id);
    await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
    await expect(guide(page)).toHaveAttribute('data-guide-step', stop);
    if (dismissal === 'Escape') await page.keyboard.press('Escape');
    else if (dismissal === 'darkened area') await page.mouse.click(8, 500);
    else await guide(page).getByRole('button', { name: dismissal, exact: true }).click();
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .radioreference-import-modal')).toHaveCount(0);
    await expect(page).toHaveURL(routeBefore);
    await expect(username).toHaveValue('unsaved-radio-account');
    await expect(password).toHaveValue('unsaved-test-password');
    expect(await page.locator('.radioreference-page').evaluate(element => {
      const original = window.radioReferenceGuideOriginal;
      return element === original.page && element.querySelector('.radioreference-account-form') === original.account &&
        original.children.every((node, index) => element.childNodes[index] === node);
    })).toBe(true);
    expect(await page.locator('.radioreference-page').innerHTML()).toEqual(original.html);
    await expect(page.locator('#page-guide-button')).toBeFocused();
    expect(await page.evaluate(() => window.scrollY)).toEqual(original.scroll);
    expectNoEffects(fixture, requests);
  });
}

test('RadioReference first desktop visit opens once and the named URL and Help replay it', async ({ page }) => {
  const { fixture, requests } = await openRadioReference(page, { replay: false, firstVisit: true });
  await startTour(page);
  await expect(guide(page)).toHaveAttribute('data-guide-step', steps[0].id);
  await page.keyboard.press('Escape');
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBe('1');
  await page.reload();
  await expect(page.locator('#page-guide-button')).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await startTour(page);
  await page.keyboard.press('Escape');
  await page.goto('/app.html?view=radioreference&guide=radioreference');
  await startTour(page);
  expectNoEffects(fixture, requests);
});

test('navigation releases RadioReference samples and actual import layers before rendering another page', async ({ page }) => {
  const { fixture, requests } = await openRadioReference(page);
  await startTour(page);
  await goToStep(page, 'alias-preview');
  await page.locator('.primary-nav [data-view="dashboard"]').evaluate(link => link.click());
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .radioreference-import-modal, .ui-setup-guide')).toHaveCount(0);
  await page.locator('.primary-nav [data-view="radioreference"]').evaluate(link => link.click());
  await expect(page.locator('.radioreference-login-gate')).toBeVisible();
  await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .radioreference-import-modal, .ui-setup-guide')).toHaveCount(0);
  expectNoEffects(fixture, requests);
});

test('phone visits keep the RadioReference tour and its samples closed', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const { fixture, requests } = await openRadioReference(page, { replay: false, firstVisit: true });
  await expect(page.locator('.radioreference-login-gate')).toBeVisible();
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBeNull();
  expectNoEffects(fixture, requests);
});

test('dismissal during a step transition cannot reopen a RadioReference import dialog', async ({ page }) => {
  const { fixture, requests } = await openRadioReference(page);
  await startTour(page);
  await goToStep(page, 'site-button');
  await guide(page).getByRole('button', { name: 'Next', exact: true }).evaluate(button => {
    button.click();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
  });
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator('.radioreference-login-gate')).toBeVisible();
  await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .radioreference-import-modal')).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await startTour(page);
  await expect(guide(page)).toHaveAttribute('data-guide-step', steps[0].id);
  await page.keyboard.press('Escape');
  await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .radioreference-import-modal')).toHaveCount(0);
  expectNoEffects(fixture, requests);
});

for (const theme of ['light', 'dark']) {
  test(`RadioReference tour surfaces retain readable contrast in ${theme} and representative hues`, async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.setViewportSize({ width: 1440, height: 1000 });
    const { fixture, requests } = await openRadioReference(page, { theme });
    await startTour(page);
    for (const step of steps) {
      await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
      if (['area-groups', 'site-import', 'alias-preview'].includes(step.id)) {
        await page.mouse.move(0, 0);
        await page.evaluate(() => {
          const backdrop = document.querySelector('.ui-setup-guide-backdrop');
          window.radioReferenceGuideContrastState = { backdrop, visibility: backdrop.style.visibility,
            inert: [...document.querySelectorAll('[inert]')] };
          backdrop.style.visibility = 'hidden';
          window.radioReferenceGuideContrastState.inert.forEach(element => { element.inert = false; });
        });
        try {
          for (const hue of [null, 0, 120, 240]) {
            await page.evaluate(async value => {
              const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
              applyThemeHue(value);
            }, hue);
            const result = await expectContrast(page, {
              rootSelector: '.radioreference-page[data-guide-sample], .radioreference-import-modal',
              reportName: `radioreference-guide-${step.id}-${theme}-hue-${hue}`
            });
            expect(result.summary.text.checked).toBeGreaterThan(10);
          }
        } finally {
          await page.evaluate(async () => {
            const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
            applyThemeHue(null);
            const state = window.radioReferenceGuideContrastState;
            state.inert.forEach(element => { element.inert = true; });
            state.backdrop.style.visibility = state.visibility;
            delete window.radioReferenceGuideContrastState;
          });
        }
      }
      await next(page, step.id);
    }
    expectNoEffects(fixture, requests);
  });
}
