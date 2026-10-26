'use strict';
const { test, expect } = require('@playwright/test');
const path = require('node:path');
const fs = require('node:fs');
const { coach, nonGuideStorage, installTour, advance, startTour, isolated, screenshot, focusVisible } = require('./fixtures/page-tour.cjs');

const id = 'network-visualizer';
const steps = ['shapes', 'actions-button', 'actions', 'actions-close', 'manual-button', 'manual-camera', 'auto-camera'];
const evidence = process.env.P25_GUIDE_EVIDENCE || path.resolve(__dirname, '../../../../../build/playwright-results/p25-guide-evidence');
const sample = page => page.locator('.network-visualizer-layout[data-guide-example="network-visualizer"]');
async function observeActualRenderer(page) {
  const filename = path.resolve(__dirname, '../../../../../stats-web/assets/features/network-visualizer/renderer.js');
  let source = fs.readFileSync(filename, 'utf8');
  const readyMarker = "\n  host.dataset.rendererState = 'ready';";
  const suspensionMarker = '    guideSuspended = Boolean(value);';
  expect(source.split(readyMarker)).toHaveLength(2);
  expect(source.split(suspensionMarker)).toHaveLength(2);
  source = source.replace(readyMarker, `
  window.p25TourScenes ||= [];
  window.p25TourScenes.push({ host, transitions: [], inspect() {
    const nodeTypes = [];
    graph.scene().traverse(value => { if (value.userData?.p25Node) nodeTypes.push(value.userData.p25Node.type); });
    return { nodeTypes, graphNodes: graph.graphData().nodes.length, graphLinks: graph.graphData().links.length,
      render: { ...graph.renderer().info.render }, suspended: guideSuspended,
      camera: { position: graph.camera().position.toArray(), target: graph.controls().target.toArray() },
      transitions: this.transitions,
      labels: host.querySelectorAll('.network-visualizer-label').length };
  } });
  host.dataset.rendererState = 'ready';`).replace(suspensionMarker, suspensionMarker + `
    window.p25TourScenes?.find(scene => scene.host === host)?.transitions.push({ suspended: guideSuspended,
      camera: { position: graph.camera().position.toArray(), target: graph.controls().target.toArray() } });`);
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
      ['manual-button', 'auto-camera', 'actions-button'].includes(step) ? ['.network-visualizer-toolbar'] :
      ['actions', 'actions-close'].includes(step) ? ['.network-visualizer-events'] : [] });
  const manual = sample(page).getByRole('button', { name: 'Manual', exact: true });
  await expect(manual).toHaveAttribute('aria-pressed', ['manual-camera', 'auto-camera'].includes(step) ? 'true' : 'false');
  await expect(sample(page).locator('.network-visualizer-manual-guide'))[
    ['manual-camera', 'auto-camera'].includes(step) ? 'toBeVisible' : 'toBeHidden']();
  if (step === 'shapes') {
    await expect(sample(page).getByRole('group', { name: 'Shape and line key', exact: true })).toContainText('Last accepted affiliation');
    await expect(coach(page, id)).toContainText('pyramids are radios');
  }
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
    await expect(page.getByRole('dialog', { name: 'P25 actions', exact: true })).toHaveCount(0);
    await expect(page).toHaveURL(url);
    if (step !== steps.at(-1)) await advance(page, id);
  }
  await page.locator('[data-view="topics"]').first().evaluate(link => link.click());
  await expect(page).toHaveURL(/view=topics/);
  await expect(page.locator('[data-guide-example], [data-guide-sample], .ui-setup-guide-highlight, .ui-setup-guide-layer')).toHaveCount(0);
  expect(await nonGuideStorage(page)).toEqual(storage); isolated(state);
});
