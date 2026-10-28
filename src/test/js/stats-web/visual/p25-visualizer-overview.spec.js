'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { test, expect } = require('@playwright/test');

async function openOverview(page, theme, width) {
  await page.clock.install({ time: new Date('2026-10-01T12:00:00Z') });
  await page.clock.pauseAt(new Date('2026-10-01T12:00:01Z'));
  await page.setViewportSize({ width, height: 844 });
  await page.goto(`/design-system.html?theme=${theme}&view=health`);
  await page.evaluate(async () => {
    const stage = document.createElement('section');
    stage.className = 'network-visualizer-stage';
    stage.style.height = '600px';
    const host = document.createElement('div');
    host.className = 'network-visualizer-canvas';
    stage.append(host);
    document.querySelector('.visual-health-example').replaceChildren(stage);
    const vendor = await import('/assets/vendor/network-visualizer-vendor.js');
    const { createP25Renderer } = await import('/assets/features/network-visualizer/renderer.js');
    const { createP25HistoryState, applyP25ActivityRows, buildP25Graph } =
      await import('/assets/features/network-visualizer/history.js');
    const state = createP25HistoryState();
    const names = ['Metro Public Safety', 'Regional Transit', 'County Fire', 'Valley Utilities',
      'Airport Operations', 'University Safety', 'River County'];
    applyP25ActivityRows(state, names.map((name, index) => ({
      id: index + 1, action: 'CALL', observed_at_ms: Date.now(), protocol: 'P25', channel_kind: 'TRUNKED_SITE',
      radio_system_key: `p25:00001:${(index + 1).toString(16).padStart(3, '0')}`,
      system_name: name, wacn: 1, system_id: index + 1, configuration_id: `overview-${index + 1}`,
      source_identity_key: `v1-r-overview-${index + 1}`, source_identity_kind_code: 2,
      source_radio_id: 31001 + index, source_alias_name: `Unit ${index + 1}`,
      target_identity_key: `v1-g-overview-${index + 1}`, target_kind: 'talkgroup',
      target_id: 1001 + index, target_alias_name: 'Dispatch'
    })), { initial: true, atMs: Date.now() });
    let graph, renderer;
    const library = { ...vendor, ForceGraph3D: options => {
      const factory = vendor.ForceGraph3D(options);
      return surface => { graph = factory(surface); return graph; };
    } };
    const clicks = [];
    renderer = await createP25Renderer({ host, mode: 'manual', library,
      onNodeClick: node => {
        if (node.type !== 'system') return;
        clicks.push(node.id);
        renderer.setData(buildP25Graph(state, node.systemKey), { animate: false });
        renderer.enterSystem(node.systemKey, { immediate: true });
      } });
    const overview = () => {
      renderer.setData(buildP25Graph(state), { animate: false });
      renderer.showOverview({ immediate: true });
    };
    const singleOverview = () => {
      const node = buildP25Graph(state).nodes[0];
      renderer.setData({ nodes: [node], links: [] }, { animate: false });
      renderer.showOverview({ immediate: true });
    };
    const objects = () => {
      const result = new Map();
      graph.scene().traverse(object => {
        if (object.userData?.p25Node?.type === 'system') result.set(object.userData.p25Node.id, object);
      });
      return result;
    };
    const inspect = () => {
      const camera = graph.camera(), bounds = host.getBoundingClientRect();
      camera.updateMatrixWorld(); graph.scene().updateMatrixWorld(true);
      const meshObjects = objects();
      const project = point => {
        const projected = point.project(camera);
        return { x: bounds.left + (projected.x + 1) * host.clientWidth / 2,
          y: bounds.top + (1 - projected.y) * host.clientHeight / 2 };
      };
      const systems = graph.graphData().nodes.filter(node => node.type === 'system').map(node => {
        const object = meshObjects.get(node.id), mesh = object?.children.find(child => child.isMesh);
        const label = [...host.querySelectorAll('.network-visualizer-label[data-type="system"]')]
          .find(element => element.dataset.nodeKey === node.id);
        if (!mesh || !label) return { id: node.id, ready: false };
        const scale = object.getWorldScale(new vendor.Vector3());
        const center = object.getWorldPosition(new vendor.Vector3());
        const positions = mesh.geometry.getAttribute('position');
        const current = [], previous = [];
        for (let index = 0; index < positions.count; index++) {
          const point = new vendor.Vector3().fromBufferAttribute(positions, index).applyMatrix4(mesh.matrixWorld);
          current.push(project(point.clone()));
          previous.push(project(point.clone().sub(center).multiplyScalar(58 / node.radius).add(center)));
        }
        const box = points => ({ left: Math.min(...points.map(value => value.x)),
          right: Math.max(...points.map(value => value.x)), top: Math.min(...points.map(value => value.y)),
          bottom: Math.max(...points.map(value => value.y)) });
        const labelBounds = label.getBoundingClientRect(), style = getComputedStyle(label);
        const screen = project(center.clone());
        return { id: node.id, ready: true, radius: node.radius, scale: { x: scale.x, y: scale.y, z: scale.z },
          sphere: box(current), previousSphere: box(previous), screen,
          clearClick: document.elementFromPoint(screen.x, screen.y)?.tagName === 'CANVAS',
          label: { left: labelBounds.left, right: labelBounds.right, top: labelBounds.top, bottom: labelBounds.bottom,
            opacity: style.opacity, filter: style.filter, fontSize: parseFloat(style.fontSize), hidden: label.hidden,
            text: label.textContent } };
      });
      return { width: host.clientWidth, height: host.clientHeight,
        host: { left: bounds.left, right: bounds.right, top: bounds.top, bottom: bounds.bottom },
        systems, painted: meshObjects.size === 7 && graph.renderer().info.render.calls > 0,
        clicks: [...clicks], nodeTypes: graph.graphData().nodes.map(node => node.type).sort() };
    };
    window.overviewFixture = { renderer, graph, overview, singleOverview, inspect };
    overview();
  });
  await page.clock.runFor(150);
  await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
  await expect.poll(() => page.evaluate(() => window.overviewFixture.inspect().painted)).toBe(true);
}

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 390], ['dark', 390]]) {
  test(`seven overview systems have clear enlarged spheres and labels in ${theme} at ${width}px`, async ({ page }, testInfo) => {
    await openOverview(page, theme, width);
    const scene = await page.evaluate(() => window.overviewFixture.inspect());
    expect(scene.systems).toHaveLength(7);
    for (const system of scene.systems) {
      expect(system.ready, system.id).toBe(true);
      expect(system.radius).toBe(180);
      for (const value of Object.values(system.scale)) expect(value).toBeCloseTo(system.radius, 4);
      const diameter = system.sphere.bottom - system.sphere.top;
      const previousDiameter = system.previousSphere.bottom - system.previousSphere.top;
      expect(diameter / previousDiameter, `${system.id} projected enlargement`).toBeGreaterThan(2.9);
      expect(system.sphere.left, `${system.id} sphere fits left`).toBeGreaterThanOrEqual(scene.host.left + 1);
      expect(system.sphere.right, `${system.id} sphere fits right`).toBeLessThanOrEqual(scene.host.right - 1);
      expect(system.sphere.top, `${system.id} sphere fits top`).toBeGreaterThanOrEqual(scene.host.top + 1);
      expect(system.sphere.bottom, `${system.id} sphere fits bottom`).toBeLessThanOrEqual(scene.host.bottom - 1);
      expect(system.label.hidden).toBe(false);
      expect(system.label.opacity).toBe('1');
      expect(system.label.filter).toBe('none');
      expect(system.label.fontSize).toBeGreaterThanOrEqual(13);
      expect(system.label.top, `${system.id} label clears projected sphere`).toBeGreaterThanOrEqual(system.sphere.bottom + 5);
      expect(system.label.left, `${system.id} name fits left`).toBeGreaterThanOrEqual(scene.host.left + 1);
      expect(system.label.right, `${system.id} name fits right`).toBeLessThanOrEqual(scene.host.right - 1);
      expect(system.label.bottom, `${system.id} name fits below sphere`).toBeLessThanOrEqual(scene.host.bottom - 1);
    }
    const directory = process.env.P25_OVERVIEW_EVIDENCE;
    if (directory) {
      fs.mkdirSync(directory, { recursive: true });
      fs.writeFileSync(path.join(directory, `overview-${theme}-${width}.json`), `${JSON.stringify(scene, null, 2)}\n`);
    }
    await page.locator('.network-visualizer-stage').screenshot({
      path: directory ? path.join(directory, `overview-${theme}-${width}.png`) :
        testInfo.outputPath(`overview-${theme}-${width}.png`)
    });
    const overlappingNames = [];
    for (let first = 0; first < scene.systems.length; first++) {
      for (let second = first + 1; second < scene.systems.length; second++) {
        const a = scene.systems[first], b = scene.systems[second];
        if (Math.min(a.label.right, b.label.right) - Math.max(a.label.left, b.label.left) > 2 &&
            Math.min(a.label.bottom, b.label.bottom) - Math.max(a.label.top, b.label.top) > 2)
          overlappingNames.push([a.label.text, b.label.text]);
      }
    }
    expect(overlappingNames, 'Overview names remain individually readable').toEqual([]);
    const target = scene.systems.find(system => system.clearClick && scene.systems.every(other =>
      other.id === system.id || system.screen.x < other.sphere.left || system.screen.x > other.sphere.right ||
      system.screen.y < other.sphere.top || system.screen.y > other.sphere.bottom));
    expect(target, 'At least one sphere has an unobstructed clickable center').toBeTruthy();
    await page.mouse.move(target.screen.x, target.screen.y);
    await page.clock.runFor(32);
    await page.mouse.click(target.screen.x, target.screen.y);
    await page.clock.runFor(100);
    await expect.poll(() => page.evaluate(() => window.overviewFixture.inspect().clicks)).toEqual([target.id]);
    expect(await page.evaluate(() => window.overviewFixture.inspect().nodeTypes)).toEqual(['radio', 'system', 'talkgroup']);
    await page.evaluate(() => window.overviewFixture.overview());
    await page.clock.runFor(100);
    await page.locator(`.network-visualizer-label[data-type="system"][data-node-key="${target.id}"]`).click();
    await page.clock.runFor(100);
    await expect.poll(() => page.evaluate(() => window.overviewFixture.inspect().clicks)).toEqual([target.id, target.id]);
    expect(await page.evaluate(() => window.overviewFixture.inspect().nodeTypes)).toEqual(['radio', 'system', 'talkgroup']);
    await page.evaluate(() => window.overviewFixture.overview());
    await page.clock.runFor(100);
    const keyboardLabel = page.locator(`.network-visualizer-label[data-type="system"][data-node-key="${target.id}"]`);
    await keyboardLabel.focus();
    await keyboardLabel.press('Enter');
    await page.clock.runFor(100);
    await expect.poll(() => page.evaluate(() => window.overviewFixture.inspect().clicks))
      .toEqual([target.id, target.id, target.id]);
    expect(await page.evaluate(() => window.overviewFixture.inspect().nodeTypes)).toEqual(['radio', 'system', 'talkgroup']);
    await page.evaluate(() => window.overviewFixture.singleOverview());
    await page.clock.runFor(100);
    const single = await page.evaluate(() => window.overviewFixture.inspect());
    if (directory) fs.writeFileSync(path.join(directory, `overview-single-${theme}-${width}.json`),
      `${JSON.stringify(single, null, 2)}\n`);
    await page.locator('.network-visualizer-stage').screenshot({
      path: directory ? path.join(directory, `overview-single-${theme}-${width}.png`) :
        testInfo.outputPath(`overview-single-${theme}-${width}.png`)
    });
    expect(single.systems).toHaveLength(1);
    const system = single.systems[0];
    expect(system.radius).toBe(180);
    expect(system.sphere.left).toBeGreaterThanOrEqual(single.host.left + 1);
    expect(system.sphere.right).toBeLessThanOrEqual(single.host.right - 1);
    expect(system.sphere.top).toBeGreaterThanOrEqual(single.host.top + 1);
    expect(system.sphere.bottom).toBeLessThanOrEqual(single.host.bottom - 1);
    expect(system.label.hidden).toBe(false);
    expect(system.label.top).toBeGreaterThanOrEqual(system.sphere.bottom + 5);
    expect(system.label.left).toBeGreaterThanOrEqual(single.host.left + 1);
    expect(system.label.right).toBeLessThanOrEqual(single.host.right - 1);
    expect(system.label.bottom).toBeLessThanOrEqual(single.host.bottom - 1);
    await page.evaluate(() => window.overviewFixture.renderer.dispose());
  });
}
