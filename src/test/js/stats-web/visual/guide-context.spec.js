'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');

const evidence = process.env.GUIDE_CONTEXT_EVIDENCE ||
  path.resolve(__dirname, '../../../../../build/playwright-results/guide-context-evidence');
const coach = page => page.locator('.ui-setup-guide');
const target = (page, name) => page.locator(`[data-guide-target="${name}"]:visible`).first();

async function next(page, step) {
  await coach(page).getByRole('button', { name: /^(Next|Start tour)$/, exact: true }).click();
  if (step) await expect(coach(page)).toHaveAttribute('data-guide-step', step);
}

async function advanceTo(page, step) {
  for (let index = 0; index < 30; index += 1) {
    if (await coach(page).getAttribute('data-guide-step') === step) return;
    await next(page);
  }
  throw new Error(`Guide did not reach ${step}`);
}

// Inspect the mask actually attached to the dimmer, rather than outline masks:
// context must be readable while its nested control keeps the only outline.
async function illumination(page, owner, focus) {
  return owner.evaluate((element, focusSelector) => {
    const box = value => {
      const rect = value.getBoundingClientRect();
      return { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom };
    };
    const contains = (outer, inner) => outer.left <= inner.left + 1 && outer.top <= inner.top + 1 &&
      outer.right >= inner.right - 1 && outer.bottom >= inner.bottom - 1;
    const dimmer = document.querySelector('.ui-setup-guide-dimmer');
    const maskId = dimmer.getAttribute('mask').match(/#([^)]*)/)[1];
    const holes = [...document.getElementById(maskId).querySelectorAll('.ui-setup-guide-mask-hole')].map(hole => {
      const left = Number(hole.getAttribute('x')), top = Number(hole.getAttribute('y'));
      return { left, top, right: left + Number(hole.getAttribute('width')),
        bottom: top + Number(hole.getAttribute('height')) };
    });
    const focus = document.querySelector(focusSelector);
    const highlights = [...document.querySelectorAll('.ui-setup-guide-highlight')];
    const focusOutline = highlights.find(highlight => highlight.dataset.setupGuideTarget === focus.id);
    const ownerOutline = highlights.find(highlight => highlight.dataset.setupGuideTarget === element.id);
    const guide = box(document.querySelector('.ui-setup-guide'));
    const rectangle = box(element);
    const overlap = Math.max(0, Math.min(guide.right, rectangle.right) - Math.max(guide.left, rectangle.left)) *
      Math.max(0, Math.min(guide.bottom, rectangle.bottom) - Math.max(guide.top, rectangle.top));
    return { ownerClear: holes.some(hole => contains(hole, rectangle)),
      focusOutlined: Boolean(focusOutline && contains(box(focusOutline), box(focus))),
      ownerOutlined: Boolean(ownerOutline), highlights: highlights.length, overlap,
      owner: rectangle, holes, guide, viewport: { width: innerWidth, height: innerHeight } };
  }, focus);
}

async function expectContext(page, owner, focus, { count = 1, clearPlacement = true } = {}) {
  await expect(owner).toBeVisible();
  await expect.poll(async () => {
    const result = await illumination(page, owner, focus);
    return result.ownerClear && result.focusOutlined && !result.ownerOutlined &&
      result.highlights === count && (!clearPlacement || result.overlap < 1) ? null : result;
  }, { message: 'The whole owner is clear, with an outline only on the important control' }).toBeNull();
}

async function screenshot(page, name) {
  fs.mkdirSync(evidence, { recursive: true });
  await page.screenshot({ path: path.join(evidence, `${name}.png`) });
}

async function openPageGuide(page, id, theme = 'light') {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const fixture = await installSiteStyleApplication(page, theme);
  await page.route('**/api/v1/listen/map/icons', route => route.fulfill({ json: { data: { icons: [] } } }));
  await page.goto(`/app.html?view=${id}&guide=${id}`);
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  await expect(coach(page)).toHaveAttribute('data-guide-step', 'overview');
  await expect(page.locator('.ui-setup-guide-mask-hole, .ui-setup-guide-highlight')).toHaveCount(0);
  return fixture;
}

for (const theme of ['light', 'dark']) {
  test(`Recordings keeps the actual call card and dialog readable in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const fixture = await openPageGuide(page, 'recordings', theme);
    await advanceTo(page, 'call-card');
    const cardIntroduction = target(page, 'rec-call-card');
    await expect(cardIntroduction).toHaveClass(/recordings-call/);
    await expect(coach(page)).toContainText(/call/i);
    await expect(coach(page)).not.toContainText('Open Call details');
    await expect(page.getByRole('dialog', { name: 'Call details', exact: true })).toHaveCount(0);
    await expect(page.locator('.ui-setup-guide-highlight')).toHaveCount(1);
    await expect.poll(() => cardIntroduction.evaluate(element =>
      document.querySelector('.ui-setup-guide-highlight')?.dataset.setupGuideTarget === element.id)).toBe(true);
    await screenshot(page, `recordings-call-card-introduction-${theme}`);
    await next(page, 'actions-button');
    const actionButton = target(page, 'rec-actions-button');
    const actionCard = actionButton.locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " recordings-call ")][1]');
    await expectContext(page, actionCard, '[data-guide-target="rec-actions-button"]');
    await screenshot(page, `recordings-card-actions-${theme}`);
    await next(page, 'playback');
    const dialog = page.getByRole('dialog', { name: 'Call actions', exact: true });
    await expectContext(page, dialog, '[data-guide-target="rec-playback"]');
    await screenshot(page, `recordings-actual-playback-${theme}`);
    await next(page, 'details-button');
    await expectContext(page, dialog, '[data-guide-target="rec-details-button"]');
    await expect(target(page, 'rec-details-button')).toHaveText(/Show more/);
    await screenshot(page, `recordings-show-more-${theme}`);
    await next(page, 'details');
    await expectContext(page, dialog, '[data-guide-target="rec-details"]');
    await screenshot(page, `recordings-actual-details-${theme}`);
    await coach(page).getByRole('button', { name: 'Back', exact: true }).click();
    await expect(coach(page)).toHaveAttribute('data-guide-step', 'details-button');
    await expectContext(page, dialog, '[data-guide-target="rec-details-button"]');
    await next(page, 'details');
    await next(page, 'details-close');
    await expectContext(page, dialog, '[data-guide-target="rec-details-close"]');
    await next(page, 'player-overview');
    await expect(target(page, 'player')).toHaveAttribute('id', 'audio-dock');
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-source', 'recordings');
    await expect(page.locator('#audio-dock')).not.toHaveAttribute('data-state', 'full');
    await expect(coach(page)).not.toContainText('Expand the player');
    await expect(page.locator('.ui-setup-guide-highlight')).toHaveCount(1);
    await expect(page.locator('.ui-setup-guide-highlight')).toHaveAttribute('data-setup-guide-target', 'audio-dock');
    await screenshot(page, `recordings-player-introduction-${theme}`);
    await next(page, 'player-button');
    await expectContext(page, page.locator('#audio-dock'), '[data-guide-target="rec-player-button"]');
    await screenshot(page, `recordings-compact-player-${theme}`);
    await next(page, 'click-mode');
    await expectContext(page, page.locator('#audio-dock'), '[data-guide-target="rec-click-mode"]');
    await screenshot(page, `recordings-expanded-player-${theme}`);
    await page.keyboard.press('Escape');
    await expect(page.locator('.ui-setup-guide, .ui-setup-guide-highlight, .ui-setup-guide-mask-hole, .ui-setup-guide-layer, [data-guide-sample]')).toHaveCount(0);
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });
}

test('Aliases keeps the normal editor dialog clear around its tab lesson', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const fixture = await openPageGuide(page, 'aliases');
  await advanceTo(page, 'alias-editor');
  await expectContext(page, page.locator('.alias-record-modal'), '[data-guide-target="aliases-editor-tabs"]');
  await screenshot(page, 'aliases-actual-editor-context');
  await page.keyboard.press('Escape');
  await expect(page.locator('.ui-setup-guide, .ui-setup-guide-layer, [data-guide-sample]')).toHaveCount(0);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

async function installEngineHarness(page) {
  await page.goto('/design-system.html');
  await page.locator('.remote-support-workspace').waitFor({ state: 'attached' });
  await page.evaluate(async () => {
    const { openSetupGuide } = await import('/assets/core/setup-guide.js');
    document.body.replaceChildren();
    const node = (tag, className = '', text = '') => {
      const element = document.createElement(tag);
      element.className = className; element.textContent = text;
      return element;
    };
    const region = node('section', 'ui-section');
    region.style.cssText = 'position:absolute;left:750px;top:160px;width:400px;height:270px';
    const focus = node('button', 'ui-button', 'Inspect the call');
    focus.dataset.testFocus = '';
    const adjacent = node('button', 'ui-button', 'Another real action');
    region.append(focus, adjacent);
    const overlap = node('section', 'ui-section');
    overlap.style.cssText = 'position:absolute;left:1010px;top:330px;width:180px;height:160px';
    const outside = node('button', 'ui-button', 'Unrelated live action');
    outside.style.cssText = 'position:absolute;left:1280px;top:800px;width:120px;height:48px';
    const spacer = node('div'); spacer.style.height = '1800px';
    document.body.append(region, overlap, outside, spacer);
    const harness = window.contextHarness = { node, region, overlap, focus, adjacent, outside,
      clicks: 0, outsideClicks: 0, errors: [] };
    for (const button of [focus, adjacent]) button.addEventListener('click', () => { harness.clicks += 1; });
    outside.addEventListener('click', () => { harness.outsideClicks += 1; });
    const openReadOnlyModal = (title, body, options) => {
      const backdrop = node('div', 'modal-backdrop');
      const dialog = node('section', `read-only-modal ${options.className}`);
      const header = node('header', 'modal-header');
      const heading = node('h2', '', title); heading.id = 'context-test-heading';
      const content = node('div', 'modal-content'); content.append(body);
      header.append(heading); dialog.append(header, content); backdrop.append(dialog); document.body.append(backdrop);
      const close = () => { options.cleanup(); backdrop.remove(); return true; };
      backdrop.addEventListener('click', event => {
        if (event.target === backdrop || event.target.hasAttribute('data-modal-dismiss-surface')) close();
      });
      return { dialog, state: { backdrop, guideTargets: new Set() }, ready: Promise.resolve(true), close,
        focus: element => element.focus(), setTitle: value => { heading.textContent = value; } };
    };
    const steps = [
      { id: 'overview', overview: true, title: 'Overview', paragraphs: ['Choose a call to inspect.'],
        targets: () => [focus], contextTargets: () => [region] },
      { id: 'control', title: 'Inspect this call', paragraphs: ['Open its details.'], targets: () => [focus],
        contextTargets: () => [region, overlap, region, focus] },
      { id: 'context-only', title: 'Review the call', paragraphs: ['Its details stay together.'],
        contextTargets: () => [region] }
    ];
    harness.steps = steps;
    harness.open = () => openSetupGuide({ node, openReadOnlyModal }, {
      id: 'context-engine', steps, onError: error => harness.errors.push(error.message)
    });
    harness.presentation = harness.open();
  });
}

test('shared context masks union without extra outlines, follow reflow and restore IDs on exit', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await installEngineHarness(page);
  await expect(coach(page)).toHaveAttribute('data-guide-step', 'overview');
  await expect(page.locator('.ui-setup-guide-mask-hole, .ui-setup-guide-highlight')).toHaveCount(0);
  await next(page, 'control');
  const owner = page.locator('section.ui-section').first();
  await expectContext(page, owner, '[data-test-focus]');
  await expect(page.locator('.ui-setup-guide-shade > defs > mask').first().locator('.ui-setup-guide-mask-hole')).toHaveCount(3);
  expect(await owner.getAttribute('id')).toBeNull();
  await expect(page.locator('[data-test-focus]')).toHaveAttribute('id', /target/);
  await page.setViewportSize({ width: 1280, height: 900 });
  await expectContext(page, owner, '[data-test-focus]');
  await page.evaluate(() => {
    window.contextHarness.region.style.width = '420px';
    window.scrollTo(0, 80);
  });
  await expectContext(page, owner, '[data-test-focus]');
  const action = await page.locator('[data-test-focus]').boundingBox();
  await page.mouse.click(action.x + action.width / 2, action.y + action.height / 2);
  const sibling = await page.getByRole('button', { name: 'Another real action' }).boundingBox();
  await page.mouse.click(sibling.x + sibling.width / 2, sibling.y + sibling.height / 2);
  expect(await page.evaluate(() => window.contextHarness.clicks)).toBe(0);
  await expect(coach(page)).toHaveAttribute('data-guide-step', 'control');
  await next(page, 'context-only');
  await expect(page.locator('.ui-setup-guide-highlight')).toHaveCount(0);
  await expect(page.locator('.ui-setup-guide-shade > defs > mask').first().locator('.ui-setup-guide-mask-hole')).toHaveCount(1);
  await coach(page).getByRole('button', { name: 'Back', exact: true }).click();
  await expectContext(page, owner, '[data-test-focus]');
  await coach(page).getByRole('button', { name: 'Back', exact: true }).click();
  await expect(page.locator('.ui-setup-guide-mask-hole, .ui-setup-guide-highlight')).toHaveCount(0);
  await page.evaluate(() => window.contextHarness.presentation.close());
  await expect(page.locator('.ui-setup-guide, .ui-setup-guide-mask-hole, .ui-setup-guide-highlight')).toHaveCount(0);
  expect(await page.locator('[data-test-focus]').getAttribute('id')).toBeNull();
  expect(await owner.getAttribute('id')).toBeNull();
  expect(await page.evaluate(() => window.contextHarness.errors)).toEqual([]);
});

test('context illumination shields live controls while the surrounding shade still dismisses', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await installEngineHarness(page);
  await next(page, 'control');
  const unrelated = await page.getByRole('button', { name: 'Unrelated live action' }).boundingBox();
  await page.mouse.click(unrelated.x + unrelated.width / 2, unrelated.y + unrelated.height / 2);
  await expect(coach(page)).toHaveCount(0);
  expect(await page.evaluate(() => window.contextHarness.clicks)).toBe(0);
  expect(await page.evaluate(() => window.contextHarness.outsideClicks)).toBe(0);
  expect(await page.locator('[data-test-focus]').getAttribute('id')).toBeNull();
  expect(await page.evaluate(() => window.contextHarness.errors)).toEqual([]);
});

test('overlapping precise targets retain their union outline inside a clear parent context', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await installEngineHarness(page);
  await page.evaluate(() => {
    const harness = window.contextHarness;
    harness.adjacent.style.marginLeft = '-8px';
    harness.steps[1].targets = () => [harness.focus, harness.adjacent];
  });
  await next(page, 'control');
  await expectContext(page, page.locator('section.ui-section').first(), '[data-test-focus]', { count: 2 });
  await expect.poll(() => page.evaluate(() => {
    const outlines = [...document.querySelectorAll('.ui-setup-guide-highlight')];
    const rect = element => ['x', 'y', 'width', 'height'].map(name => Number(element.getAttribute(name)));
    const checks = outlines.map((outline, index) => {
      const id = outline.getAttribute('mask')?.match(/#([^)]*)/)[1];
      const holes = id ? [...document.getElementById(id).querySelectorAll('.ui-setup-guide-mask-hole')] : [];
      const other = outlines[1 - index];
      return holes.length === 1 && JSON.stringify(rect(holes[0])) === JSON.stringify(rect(other));
    });
    const [first, second] = outlines.map(outline => outline.getBoundingClientRect());
    return checks.every(Boolean) && Math.min(first.right, second.right) > Math.max(first.left, second.left);
  }), { message: 'Only the other precise target cuts each outline; the owning context never masks a focus ring' }).toBe(true);
  await page.evaluate(() => window.contextHarness.presentation.close());
  expect(await page.locator('[data-test-focus]').getAttribute('id')).toBeNull();
  expect(await page.getByRole('button', { name: 'Another real action' }).getAttribute('id')).toBeNull();
  expect(await page.evaluate(() => window.contextHarness.errors)).toEqual([]);
});
