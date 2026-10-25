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
