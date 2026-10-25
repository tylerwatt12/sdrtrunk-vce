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
