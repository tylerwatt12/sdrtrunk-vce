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
  if (replay) {
    // The replay request is consumed after the page renders, before the guide overview opens.
    await expect(page).toHaveURL(new URL('/?view=radioreference', page.url()).href);
    await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  }
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

for (const { dismissal, stop } of [{ dismissal: 'Close guide', stop: 'site-import' }]) {
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
