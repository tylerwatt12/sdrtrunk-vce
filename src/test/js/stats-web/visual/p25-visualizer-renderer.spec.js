'use strict';

const { test, expect } = require('@playwright/test');

async function openRenderer(page, theme, width, reducedMotion = false) {
  await page.setViewportSize({ width, height: 844 });
  await page.emulateMedia({ reducedMotion: reducedMotion ? 'reduce' : 'no-preference' });
  await page.goto(`/design-system.html?theme=${theme}&view=health`);
  await page.evaluate(async () => {
    const stage = document.createElement('section');
    stage.className = 'network-visualizer-stage';
    stage.style.height = '420px';
    const host = document.createElement('div');
    host.className = 'network-visualizer-canvas';
    stage.append(host);
    document.querySelector('.visual-health-example').replaceChildren(stage);
    const vendor = await import('/assets/vendor/network-visualizer-vendor.js');
    const { createP25Renderer } = await import('/assets/features/network-visualizer/renderer.js');
    let graph;
    let refreshes = 0;
    const library = { ...vendor, ForceGraph3D: (options) => {
      const factory = vendor.ForceGraph3D(options);
      return (surface) => {
        graph = factory(surface);
        const refresh = graph.refresh;
        graph.refresh = (...args) => { refreshes += 1; return refresh(...args); };
        return graph;
      };
    } };
    const renderer = await createP25Renderer({ host, mode: 'manual', library });
    const system = { id: 'system', systemKey: 'system', type: 'system', label: 'Metro Public Safety',
      x: 0, y: 0, z: 0, radius: 300 };
    const group = { id: 'group', systemKey: 'system', type: 'talkgroup', label: 'Dispatch',
      x: 100, y: 20, z: 0, radius: 13 };
    const radio = { id: 'radio-7001', systemKey: 'system', type: 'radio', label: 'Engine 4',
      x: 0, y: 0, z: 0, radius: 6.5 };
    const data = (x, signalAction = '') => ({ nodes: [system, group, { ...radio, x, signalAction }],
      links: [{ id: 'affiliation', source: radio.id, target: group.id, kind: 'current' }] });
    renderer.setData(data(0), { animate: false });
    renderer.enterSystem(system.id, { immediate: true });
    graph.camera().position.set(180, 80, 210);
    graph.controls().target.set(70, 20, 0);
    graph.camera().lookAt(graph.controls().target);
    const mesh = () => {
      let result;
      graph.scene().traverse((value) => {
        if (value.userData?.p25Node?.id === radio.id) result = value;
      });
      return result;
    };
    window.visualizerFixture = { renderer, graph, data, mesh, refreshes: () => refreshes };
  });
  await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
  await expect.poll(() => page.evaluate(() => !!window.visualizerFixture.mesh())).toBe(true);
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`radio transitions retain rendered motion through activity updates ${theme} ${width}px`, async ({ page }) => {
    await openRenderer(page, theme, width);
    await page.evaluate(() => {
      const fixture = window.visualizerFixture;
      fixture.initialMesh = fixture.mesh();
      fixture.startedAt = performance.now();
      fixture.renderer.setData(fixture.data(140, 'call'));
    });
    await page.waitForTimeout(250);
    const midpoint = await page.evaluate(() => window.visualizerFixture.mesh().position.x);
    expect(midpoint).toBeGreaterThan(0);
    expect(midpoint).toBeLessThan(140);

    // Expiry repaint and unchanged stream updates must leave the original tween deadline intact.
    await page.evaluate(() => {
      const fixture = window.visualizerFixture;
      fixture.renderer.setData(fixture.data(140), { animate: false });
    });
    for (let update = 0; update < 4; update += 1) {
      await page.waitForTimeout(180);
      await page.evaluate(() => {
        const fixture = window.visualizerFixture;
        fixture.renderer.setData(fixture.data(140));
      });
    }
    await page.waitForTimeout(180);
    const settled = await page.evaluate(() => {
      const fixture = window.visualizerFixture;
      return { x: fixture.mesh().position.x, sameMesh: fixture.mesh() === fixture.initialMesh,
        refreshes: fixture.refreshes(), elapsed: performance.now() - fixture.startedAt,
        dataX: fixture.graph.graphData().nodes.find((node) => node.id === 'radio-7001').x };
    });
    expect(settled.elapsed).toBeLessThan(1_650);
    expect(settled.x).toBeCloseTo(140, 1);
    expect(settled.dataX).toBeCloseTo(140, 1);
    expect(settled.sameMesh).toBe(true);
    expect(settled.refreshes).toBe(0);
    await expect(page.locator('.network-visualizer-stage')).toHaveScreenshot(
      `network-visualizer-motion-${theme}-${width}.png`);
    await page.evaluate(() => window.visualizerFixture.renderer.dispose());
    await expect(page.locator('.network-visualizer-canvas canvas')).toHaveCount(0);
  });
}

test('reduced motion places radios directly without a later stale tween', async ({ page }) => {
  await openRenderer(page, 'light', 1280, true);
  await page.evaluate(() => {
    const fixture = window.visualizerFixture;
    fixture.renderer.setData(fixture.data(140));
  });
  await expect.poll(() => page.evaluate(() => window.visualizerFixture.mesh().position.x)).toBe(140);
  await page.waitForTimeout(100);
  expect(await page.evaluate(() => window.visualizerFixture.mesh().position.x)).toBe(140);
  await page.evaluate(() => window.visualizerFixture.renderer.dispose());
});
