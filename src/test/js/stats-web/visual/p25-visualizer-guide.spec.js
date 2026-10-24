'use strict';
const { test, expect } = require('@playwright/test');
const path = require('node:path');
const fs = require('node:fs');
const { coach, nonGuideStorage, installTour, advance, startTour, isolated, screenshot, focusVisible } = require('./fixtures/page-tour.cjs');

const id = 'network-visualizer';
const steps = ['shapes', 'manual-button', 'manual-camera', 'auto-camera'];
const evidence = process.env.P25_GUIDE_EVIDENCE || path.resolve(__dirname, '../../../../../build/playwright-results/p25-guide-evidence');
const sample = page => page.locator('.network-visualizer-layout[data-guide-example="network-visualizer"]');
async function observeActualRenderer(page) {
  const filename = path.resolve(__dirname, '../../../../../stats-web/assets/features/network-visualizer/renderer.js');
  const source = fs.readFileSync(filename, 'utf8').replace("\n  host.dataset.rendererState = 'ready';", `
  window.p25TourScenes ||= [];
  window.p25TourScenes.push({ host, inspect() {
    const nodeTypes = [];
    graph.scene().traverse(value => { if (value.userData?.p25Node) nodeTypes.push(value.userData.p25Node.type); });
    return { nodeTypes, graphNodes: graph.graphData().nodes.length, graphLinks: graph.graphData().links.length,
      render: { ...graph.renderer().info.render }, suspended: guideSuspended,
      camera: { position: graph.camera().position.toArray(), target: graph.controls().target.toArray() },
      labels: host.querySelectorAll('.network-visualizer-label').length };
  } });
  host.dataset.rendererState = 'ready';`);
  await page.route('**/assets/features/network-visualizer/renderer.js?*', route => route.fulfill({ contentType: 'text/javascript', body: source }));
}
async function openVisualizer(page, options = {}) {
  await observeActualRenderer(page);
  const state = await installTour(page, id, options);
  // ForceGraph debounces scene construction with Date.now; keep time advancing.
  await page.clock.setSystemTime(new Date('2026-10-01T12:00:00Z'));
  await page.goto(`/app.html?view=${id}${options.replay === false ? '' : `&guide=${id}`}`);
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
async function expectStep(page, step) {
  await expect(sample(page).locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
  await expect(sample(page).locator('.network-visualizer-canvas canvas')).toBeVisible();
  await expect.poll(() => sample(page).locator('.network-visualizer-canvas').evaluate(host => {
    const scene = window.p25TourScenes.find(scene => scene.host === host)?.inspect();
    return scene && scene.graphNodes === 11 && scene.nodeTypes.filter(type => type === 'radio').length === 6 &&
      scene.render.calls > 0 && scene.render.triangles + scene.render.lines > 0 && !scene.suspended ? null : scene;
  }), { message: 'The production renderer actually paints the system, four talkgroups and six radios' }).toBeNull();
  await expect(sample(page).locator('.network-visualizer-scope-title')).toContainText('County P25');
  await expect(sample(page).locator('.network-visualizer-status')).toContainText('Example data');
  await focusVisible(page, id, [`p25-${step}`], { allowFocusOverlap: step === 'manual-camera',
    owners: step === 'shapes' ? ['.network-visualizer-stage'] :
      ['manual-button', 'auto-camera'].includes(step) ? ['.network-visualizer-toolbar'] : [] });
  const manual = sample(page).getByRole('button', { name: 'Manual', exact: true });
  await expect(manual).toHaveAttribute('aria-pressed', ['manual-camera', 'auto-camera'].includes(step) ? 'true' : 'false');
  await expect(sample(page).locator('.network-visualizer-manual-guide'))[
    ['manual-camera', 'auto-camera'].includes(step) ? 'toBeVisible' : 'toBeHidden']();
  if (step === 'shapes') {
    await expect(sample(page).getByRole('group', { name: 'Shape and line key', exact: true })).toContainText('Last accepted affiliation');
    await expect(coach(page, id)).toContainText('pyramids are radios');
  }
}

for (const [theme, width, height] of [['light', 1280, 900], ['dark', 1440, 1000]]) {
  test(`P25 tour uses the actual graph, key and camera controls in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    const state = await openVisualizer(page, { theme });
    const storage = await nonGuideStorage(page), url = page.url();
    await expect(sample(page)).toBeVisible();
    await expect(sample(page).locator('.network-visualizer-canvas canvas')).toBeVisible();
    await startTour(page, id, steps[0]);
    for (const [index, step] of steps.entries()) {
      await expect(coach(page, id)).toHaveAttribute('data-guide-step', step);
      await expect(coach(page, id)).toContainText(`Step ${index + 1} of 4`);
      await expectStep(page, step);
      await screenshot(page, evidence, `p25-${step}-${theme}`);
      await advance(page, id, undefined, step === steps.at(-1) ? 'Finish' : 'Next');
    }
    await expect(coach(page, id)).toHaveCount(0);
    await expect(page.locator('.network-visualizer-layout')).not.toHaveAttribute('data-guide-example', id);
    await expect(page.locator('.network-visualizer-canvas canvas')).toBeVisible();
    await expect(page.locator('[data-guide-example], [data-guide-sample], .ui-setup-guide-highlight')).toHaveCount(0);
    expect(await nonGuideStorage(page)).toEqual(storage); await expect(page).toHaveURL(url); isolated(state);
  });
}

for (const dismiss of ['Escape', 'Close guide', 'shade']) {
  test(`P25 ${dismiss} restores the original graph, camera mode, player and preferences`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const state = await openVisualizer(page, { replay: false });
    await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
    await page.getByRole('button', { name: 'Manual', exact: true }).click();
    await page.evaluate(() => {
      const content = document.getElementById('content');
      window.p25Original = { element: content.querySelector('.network-visualizer-layout'),
        canvas: content.querySelector('canvas'), children: [...content.childNodes] };
    });
    const storage = await nonGuideStorage(page);
    const camera = dismiss === 'Escape' ? await page.locator('.network-visualizer-canvas').evaluate(host =>
      window.p25TourScenes.find(scene => scene.host === host).inspect().camera) : null;
    const player = await page.locator('#audio-dock').evaluate(element => ({ ...element.dataset }));
    await page.locator('#page-guide-button').click(); await startTour(page, id, steps[0]);
    await goTo(page, 'manual-camera');
    await coach(page, id).getByRole('button', { name: 'Back', exact: true }).click();
    await expect(coach(page, id)).toHaveAttribute('data-guide-step', 'manual-button');
    await expect(sample(page).getByRole('button', { name: 'Auto', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await advance(page, id, 'manual-camera');
    if (dismiss === 'Escape') await page.keyboard.press('Escape');
    else if (dismiss === 'shade') await page.mouse.click(5, 50);
    else await coach(page, id).getByRole('button', { name: dismiss, exact: true }).click();
    await expect(coach(page, id)).toHaveCount(0);
    expect(await page.locator('.network-visualizer-layout').evaluate(element => element === window.p25Original.element &&
      element.querySelector('canvas') === window.p25Original.canvas &&
      window.p25Original.children.every((node, index) => document.getElementById('content').childNodes[index] === node))).toBe(true);
    await expect(page.getByRole('button', { name: 'Manual', exact: true })).toHaveAttribute('aria-pressed', 'true');
    if (camera) {
      const restored = await page.locator('.network-visualizer-canvas').evaluate(host =>
        window.p25TourScenes.find(scene => scene.host === host).inspect().camera);
      for (const part of ['position', 'target']) restored[part].forEach((value, index) => expect(value).toBeCloseTo(camera[part][index], 8));
    }
    expect(await page.locator('#audio-dock').evaluate(element => ({ ...element.dataset }))).toEqual(player);
    expect(await nonGuideStorage(page)).toEqual(storage); isolated(state);
  });
}

test('P25 sample controls and settings cannot persist, request receiver data or navigate; leaving closes examples', async ({ page }) => {
  const state = await openVisualizer(page);
  const storage = await nonGuideStorage(page), url = page.url();
  await startTour(page, id, steps[0]);
  for (const step of steps) {
    await sample(page).evaluate(element => {
      for (const control of element.querySelectorAll('button,a,input,select')) {
        control.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
      }
    });
    await expect(coach(page, id)).toHaveAttribute('data-guide-step', step);
    await expect(page.getByRole('dialog', { name: 'P25 Visualizer settings', exact: true })).toHaveCount(0);
    await expect(page).toHaveURL(url);
    if (step !== steps.at(-1)) await advance(page, id);
  }
  await page.locator('[data-view="topics"]').first().evaluate(link => link.click());
  await expect(page).toHaveURL(/view=topics/);
  await expect(page.locator('[data-guide-example], [data-guide-sample], .ui-setup-guide-highlight, .ui-setup-guide-layer')).toHaveCount(0);
  expect(await nonGuideStorage(page)).toEqual(storage); isolated(state);
});

test('P25 sample overview works with no real activity and replays through Help without phone onboarding', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await installTour(page, id, { firstVisit: true });
  await page.clock.setSystemTime(new Date('2026-10-01T12:00:00Z'));
  await page.route('**/api/v1/activity/live/seed*', route => route.fulfill({ json: { data: {
    source_epoch: 'empty-real-system', sequence: 0, available: true, collection_enabled: true,
    affiliations: [], captured_at_ms: 1792800000000, traffic_grant_age_out_milliseconds: 1200
  } } }));
  await page.goto(`/app.html?view=${id}`);
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  await expect(coach(page, id)).toHaveCount(0);
  await expect(page.locator('[data-guide-example]')).toHaveCount(0);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect(coach(page, id)).toHaveAttribute('data-guide-step', 'overview');
  await expect(sample(page).locator('.network-visualizer-scope-title')).toContainText('County P25');
  await page.keyboard.press('Escape');
  await expect(page.locator('[data-guide-example]')).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await expect(coach(page, id)).toHaveAttribute('data-guide-step', 'overview');
  await page.keyboard.press('Escape'); isolated(state);
});
