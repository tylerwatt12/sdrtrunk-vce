'use strict';
const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { coach, nonGuideStorage, installTour, advance, startTour, isolated, screenshot, focusVisible } = require('./fixtures/page-tour.cjs');

const id = 'map';
const steps = ['explore', 'select-radio', 'history', 'follow'];
const evidence = process.env.MAP_GUIDE_EVIDENCE || path.resolve(__dirname, '../../../../../build/playwright-results/map-guide-evidence');
const original = page => page.locator('.listen-map-workspace:not([data-guide-example])');

async function openMap(page, options = {}) {
  const state = await installTour(page, id, options);
  await page.goto(`/app.html?view=map${options.replay === false ? '' : '&guide=map'}`);
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  return state;
}
async function goTo(page, destination) {
  for (const step of steps) {
    await expect(coach(page, id)).toHaveAttribute('data-guide-step', step);
    if (step === destination) return;
    await advance(page, id);
  }
}
async function sampleState(page) {
  return page.locator('.listen-map-workspace[data-guide-example="map"]').evaluate(element => element.listenMapController.getGuideState());
}
async function expectStep(page, step) {
  const names = step === 'explore' ? ['map-explore', 'map-show-all'] : [`map-${step}`];
  await focusVisible(page, id, names, { allowFocusOverlap: step === 'explore', owners: step === 'explore' ? ['.listen-map-panel', '.listen-map-toolbar'] : step === 'select-radio' ? ['.listen-map-panel', '.listen-map-sidebar'] : ['.listen-map-sidebar'] });
  await expect(page.locator('.listen-map-marker')).toHaveCount(2);
  await expect(page.locator('#content .page-header')).toContainText('Example data');
  await expect(page.locator('.listen-map-list')).toContainText('Engine 7');
  await expect(page.locator('.listen-map-list')).toContainText('Patrol 12');
  const state = await sampleState(page);
  expect(state.guideMode).toBe(true);
  expect(state.selectedId).toBe(['history', 'follow'].includes(step) ? 'guide-map-patrol12' : 'guide-map-engine7');
  if (step === 'select-radio') {
    const button = page.getByRole('button', { name: 'Select Patrol 12', exact: true });
    await expect(button).toBeVisible();
    await expect(page.locator('.listen-map-details')).toContainText('Engine 7');
  }
  if (step === 'history') {
    await expect(page.locator('.listen-map-details')).toContainText('Patrol 12');
    await expect(page.locator('.listen-map-history-item')).not.toHaveCount(0);
    await expect(page.getByLabel('Trail length')).toBeAttached();
  }
  if (step === 'follow') await expect(page.locator('[data-guide-target="map-follow"]')).toBeVisible();
}

for (const [theme, width, height] of [['light', 1280, 900], ['dark', 1440, 1000]]) {
  test(`Map tour uses actual markers, selection and history in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    const state = await openMap(page, { theme });
    const storage = await nonGuideStorage(page), url = page.url();
    await expect(page.locator('.listen-map-workspace[data-guide-example="map"]')).toBeVisible();
    await startTour(page, id, steps[0]);
    for (const [index, step] of steps.entries()) {
      await expect(coach(page, id)).toHaveAttribute('data-guide-step', step);
      await expect(coach(page, id)).toContainText(`Step ${index + 1} of 4`);
      await expectStep(page, step);
      await screenshot(page, evidence, `map-${step}-${theme}`);
      await advance(page, id, undefined, step === steps.at(-1) ? 'Finish' : 'Next');
    }
    await expect(coach(page, id)).toHaveCount(0);
    await expect(original(page)).toBeVisible();
    await expect(page.locator('.listen-map-details')).toContainText('Engine 4');
    await expect(page.locator('[data-guide-example], [data-guide-sample], .ui-setup-guide-highlight')).toHaveCount(0);
    expect(await nonGuideStorage(page)).toEqual(storage); await expect(page).toHaveURL(url);
    isolated(state);
  });
}

for (const dismiss of ['Escape', 'Close guide', 'shade']) {
  test(`Map ${dismiss} restores real selection, map controls, nodes and player`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const state = await openMap(page, { replay: false });
    await expect(page.locator('.listen-map-details')).toContainText('Engine 4');
    await page.getByLabel('Trail length').selectOption('1');
    const storage = await nonGuideStorage(page);
    const before = await page.locator('.listen-map-workspace').evaluate(element => {
      window.mapOriginal = { element, children: [...document.getElementById('content').childNodes],
        trail: element.querySelector('.listen-map-trail-select') };
      return element.listenMapController.getGuideState();
    });
    const player = await page.locator('#audio-dock').evaluate(element => ({ ...element.dataset }));
    await page.locator('#page-guide-button').click(); await startTour(page, id, steps[0]);
    await goTo(page, 'history');
    await coach(page, id).getByRole('button', { name: 'Back', exact: true }).click();
    await expect(coach(page, id)).toHaveAttribute('data-guide-step', 'select-radio');
    expect((await sampleState(page)).selectedId).toBe('guide-map-engine7');
    await advance(page, id, 'history');
    if (dismiss === 'Escape') await page.keyboard.press('Escape');
    else if (dismiss === 'shade') await page.mouse.click(5, 980);
    else await coach(page, id).getByRole('button', { name: dismiss, exact: true }).click();
    await expect(coach(page, id)).toHaveCount(0);
    expect(await original(page).evaluate(element => element === window.mapOriginal.element &&
      element.querySelector('.listen-map-trail-select') === window.mapOriginal.trail &&
      window.mapOriginal.children.every((node, index) => document.getElementById('content').childNodes[index] === node))).toBe(true);
    const after = await original(page).evaluate(element => element.listenMapController.getGuideState());
    for (const key of ['selectedId', 'followingId', 'zoom', 'trailLength', 'suspended', 'closed']) expect(after[key]).toEqual(before[key]);
    await expect(page.getByLabel('Trail length')).toHaveValue('1');
    expect(await page.locator('#audio-dock').evaluate(element => ({ ...element.dataset }))).toEqual(player);
    expect(await nonGuideStorage(page)).toEqual(storage); isolated(state);
  });
}

test('Map sample controls cannot write, navigate or modify real tracks and navigation releases samples', async ({ page }) => {
  const state = await openMap(page);
  const storage = await nonGuideStorage(page), url = page.url();
  await startTour(page, id, steps[0]);
  for (const step of steps) {
    await page.locator('.listen-map-workspace[data-guide-example="map"]').evaluate(element => {
      for (const control of element.querySelectorAll('button,a,input,select')) {
        control.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
      }
    });
    await expect(coach(page, id)).toHaveAttribute('data-guide-step', step);
    await expect(page).toHaveURL(url);
    if (step !== steps.at(-1)) await advance(page, id);
  }
  await page.locator('[data-view="topics"]').first().evaluate(link => link.click());
  await expect(page).toHaveURL(/view=topics/);
  await expect(page.locator('[data-guide-example], [data-guide-sample], .ui-setup-guide-highlight, .ui-setup-guide-layer')).toHaveCount(0);
  expect(await nonGuideStorage(page)).toEqual(storage); isolated(state);
});

test('Map sample overview works without real locations, replays by Help, and avoids phone onboarding', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await installTour(page, id, { firstVisit: true });
  await page.route('**/api/v1/listen/map', route => route.fulfill({ json: { data: { entities: [], generated_at_ms: 1792800000000 } } }));
  await page.goto('/app.html?view=map');
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  await expect(coach(page, id)).toHaveCount(0);
  await expect(page.locator('.listen-map-marker')).toHaveCount(0);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect(coach(page, id)).toHaveAttribute('data-guide-step', 'overview');
  await expect(page.locator('.listen-map-marker')).toHaveCount(2);
  await page.keyboard.press('Escape');
  await expect(page.locator('.listen-map-marker')).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await expect(coach(page, id)).toHaveAttribute('data-guide-step', 'overview');
  await page.keyboard.press('Escape'); isolated(state);
});
