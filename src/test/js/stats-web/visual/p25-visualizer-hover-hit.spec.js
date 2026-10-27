'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');

async function openApplication(page, theme, width, direct = true) {
  await page.setViewportSize({ width, height: 900 });
  const fixture = await installSiteStyleApplication(page, theme);
  // The ordinary page fixture freezes Date for screenshots; the actual vendor's
  // debounced geometry updates require Date to advance alongside real RAF.
  await page.clock.setSystemTime(new Date('2026-10-01T12:00:00Z'));
  const requests = [];
  await page.addInitScript(() => {
    window.hoverAudioResumes = 0;
    class Context {
      constructor() { this.state = 'suspended'; this.currentTime = 0; this.destination = {}; }
      resume() { window.hoverAudioResumes++; this.state = 'running'; return Promise.resolve(); }
      suspend() { this.state = 'suspended'; return Promise.resolve(); }
      close() { this.state = 'closed'; return Promise.resolve(); }
      createGain() { return { gain: { value: 1 }, connect() {}, disconnect() {} }; }
      createAnalyser() { return { frequencyBinCount: 256, connect() {}, disconnect() {}, getByteFrequencyData(values) { values.fill(0); } }; }
    }
    window.AudioContext = Context;
    window.webkitAudioContext = Context;
  });
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
    configured: true, authenticated: true, username: 'fixture-admin', tier: 'user', primary: false,
    csrf_token: 'fixture-csrf', guide_dismissal_scope: 'account-1-100',
    capabilities: { 'web-access': true, dashboard: true, live: true, radio: true, credits: true,
      'call-audio': true, 'direct-listen': direct, recordings: true, 'user-settings': true }
  } } }));
  await page.route('**/api/v1/calls/feed*', route => {
    requests.push(new URL(route.request().url()).pathname);
    return route.fulfill({ json: { data: { cursor: '1', reset: false, calls: [] } } });
  });
  const rendererPath = process.env.P25_HOVER_SOURCE || path.resolve(__dirname,
    '../../../../../stats-web/assets/features/network-visualizer/renderer.js');
  const original = fs.readFileSync(rendererPath, 'utf8');
  const marker = "\n  host.dataset.rendererState = 'ready';";
  expect(original.split(marker)).toHaveLength(2);
  // Observe the real vendor's native raycaster without replacing production hover behavior.
  const source = original.replace(marker, `
  const priorHover = graph.onNodeHover();
  let observedHover = null, observedHoverCount = 0;
  graph.onNodeHover((node, previous) => { observedHover = node?.id || null; observedHoverCount++; priorHover?.(node, previous); });
  window.p25HoverHit = {
    inspect: () => {
      const meshes = {}, labelStyles = {};
      scene.traverse(object => {
        const node = object.userData?.p25Node;
        if (!node || !object.material) return;
        meshes[node.id] = { opacity: object.material.opacity, fog: object.material.fog,
          color: object.material.color.getHex(), wireframe: object.material.wireframe,
          renderOrder: object.renderOrder, depthTest: object.material.depthTest, depthWrite: object.material.depthWrite,
          position: object.position.toArray(), scale: object.scale.toArray() };
      });
      for (const [id, label] of labelElements) {
        const styles = getComputedStyle(label);
        labelStyles[id] = { hidden: label.hidden, opacity: Number(styles.opacity),
          blur: Number(styles.filter.match(/blur\\(([\\d.]+)px\\)/)?.[1] || 0),
          z: Number(styles.zIndex), hovered: label.matches(':hover'),
          tag: label.tagName, action: label.hasAttribute('data-direct-listen-target-key') };
      }
      return { nodes: [...nodes.values()].map(node => ({ id: node.id, type: node.type,
        active: node.active, signalAction: node.signalAction, label: node.label })), meshes, labels: labelStyles,
        fog: scene.fog ? { density: scene.fog.density, color: scene.fog.color.getHex() } : null,
        vendorHover: observedHover, vendorHoverCount: observedHoverCount,
        rotating: controls.autoRotate, interacting: interactingLabels.size > 0,
        camera: graph.camera().position.toArray(), target: controls.target.toArray() };
    },
    shapePoints: id => {
      const object = nodeObjects.get(id), bounds = host.getBoundingClientRect();
      if (!object?.geometry) return [];
      object.updateMatrixWorld(true);
      const position = object.geometry.getAttribute('position');
      const index = object.geometry.getIndex();
      const vertices = [...Array(position.count)].map((_, i) => new library.Vector3().fromBufferAttribute(position, i));
      const candidates = [];
      const count = index?.count || position.count;
      for (let i = 0; i < count; i += 3) {
        const triangle = [0, 1, 2].map(offset => vertices[index ? index.getX(i + offset) : i + offset]);
        for (let edge = 0; edge < 3; edge++) candidates.push(triangle[edge].clone().add(triangle[(edge + 1) % 3]).multiplyScalar(.5));
      }
      candidates.push(new library.Vector3());
      return candidates.map(local => {
        const projected = local.applyMatrix4(object.matrixWorld).project(graph.camera());
        return { x: bounds.left + (projected.x + 1) * bounds.width / 2,
          y: bounds.top + (1 - projected.y) * bounds.height / 2, z: projected.z };
      }).filter(point => point.z >= -1 && point.z <= 1 && point.x > bounds.left + 4 &&
        point.x < bounds.right - 4 && point.y > bounds.top + 4 && point.y < bounds.bottom - 4);
    }
  };${marker}`);
  await page.route('**/assets/features/network-visualizer/renderer.js?**', route =>
    route.fulfill({ contentType: 'text/javascript', body: source }));
  await page.goto('/app.html?view=network-visualizer');
  await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
  await expect.poll(async () => Object.keys((await inspect(page)).meshes).length).toBeGreaterThan(1);
  await page.evaluate(() => window.fixtureSendLiveSnapshot({ revision: 2, tables: [] }));
  await frames(page);
  await page.getByRole('button', { name: 'Manual', exact: true }).click();
  await page.mouse.move(5, 5);
  await frames(page);
  await expect.poll(() => page.evaluate(() => window.p25HoverHit.inspect().nodes.filter(node => node.active || node.signalAction).length)).toBe(0);
  return { fixture, requests };
}

const inspect = page => page.evaluate(() => window.p25HoverHit.inspect());
const frames = page => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
const labelFor = (page, id) => page.locator(`.network-visualizer-label[data-node-key="${id}"]`);
const nodeId = async (page, type) => (await inspect(page)).nodes.find(node => node.type === type).id;

async function moveToName(page, id) {
  const label = labelFor(page, id);
  await expect(label).toBeVisible();
  const point = await label.evaluate(element => {
    const bounds = element.getBoundingClientRect();
    return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 };
  });
  const hit = await page.evaluate(({ x, y }) => {
    const element = document.elementFromPoint(x, y);
    return { nodeKey: element?.closest('[data-node-key]')?.dataset.nodeKey,
      canvas: !!element?.closest('.network-visualizer-canvas') };
  }, point);
  expect(hit.canvas).toBe(true);
  if ((await inspect(page)).labels[id].action) expect(hit.nodeKey).toBe(id);
  await page.mouse.move(point.x, point.y);
  await frames(page);
  return point;
}

async function moveToShape(page, id) {
  const points = await page.evaluate(key => window.p25HoverHit.shapePoints(key), id);
  expect(points.length).toBeGreaterThan(0);
  for (const point of points) {
    const hit = await page.evaluate(({ x, y }) => {
      const element = document.elementFromPoint(x, y);
      return !!element?.closest('.network-visualizer-graph-surface') && !element.closest('[data-node-key]');
    }, point);
    if (!hit) continue;
    await page.mouse.move(point.x, point.y);
    await frames(page);
    if ((await inspect(page)).vendorHover === id) {
      // Shape picking runs every 50 ms, independently of the vendor's cached hover.
      // Two RAFs can finish sooner; require the real name to become sharp before proceeding.
      await expect.poll(async () => {
        const label = (await inspect(page)).labels[id];
        return { opacity: label.opacity, blur: label.blur };
      }, { timeout: 750, intervals: [10, 25, 50], message: `native shape hover emphasizes ${id}` })
        .toEqual({ opacity: 1, blur: 0 });
      return point;
    }
  }
  expect((await inspect(page)).vendorHover, `native vendor raycast reaches ${id}`).toBe(id);
}

function expectEmphasis(snapshot, id, baseline) {
  expect(snapshot.labels[id].opacity).toBe(1);
  expect(snapshot.labels[id].blur).toBe(0);
  for (const [other, label] of Object.entries(snapshot.labels)) {
    if (other !== id && !label.hidden) expect(snapshot.labels[id].z).toBeGreaterThan(label.z);
  }
  expect(snapshot.meshes).toEqual(baseline.meshes);
  expect(snapshot.fog).toEqual(baseline.fog);
  expect(snapshot.interacting).toBe(false);
  expect(snapshot.rotating).toBe(baseline.rotating);
}

async function saveEvidence(page, name, details) {
  if (!process.env.WEB_REVIEW_DIR) return;
  fs.mkdirSync(process.env.WEB_REVIEW_DIR, { recursive: true });
  const stem = `${process.env.P25_HOVER_SOURCE ? 'before-' : ''}${name}`;
  fs.writeFileSync(path.join(process.env.WEB_REVIEW_DIR, `${stem}.json`), JSON.stringify(details, null, 2));
  await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR, `${stem}.png`) });
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  for (const target of ['name', 'readonly name', 'shape']) {
    test(`full P25 application native ${target} hover lifts inactive TG and radio labels ${theme} ${width}px`, async ({ page }) => {
      const state = await openApplication(page, theme, width, target !== 'readonly name');
      for (const type of ['talkgroup', 'radio']) {
        const id = await nodeId(page, type);
        const baseline = await inspect(page);
        expect(baseline.labels[id].opacity).toBeLessThan(1);
        expect(baseline.labels[id].blur).toBeGreaterThan(0);
        if (target.includes('name')) await moveToName(page, id);
        else await moveToShape(page, id);
        const hovered = await inspect(page);
        await saveEvidence(page, `${target}-${type}-${theme}-${width}`, { baseline, hovered });
        expectEmphasis(hovered, id, baseline);
        if (target === 'shape' && type === 'talkgroup') {
          // Moving from the WebGL surface to its DOM name must keep emphasis,
          // then restore genuine ray hover when returning to the same cube.
          await moveToName(page, id);
          const overlay = await inspect(page);
          expectEmphasis(overlay, id, baseline);
          await moveToShape(page, id);
          const reentered = await inspect(page);
          await saveEvidence(page, `shape-reentry-${theme}-${width}`, { baseline, hovered, overlay, reentered });
          expectEmphasis(reentered, id, baseline);
        }
        await page.mouse.move(5, 5);
        await frames(page);
        const left = await inspect(page);
        expect(left.labels[id].opacity).toBeCloseTo(baseline.labels[id].opacity, 6);
        expect(left.labels[id].blur).toBe(baseline.labels[id].blur);
        expect(left.labels[id].z).toBe(baseline.labels[id].z);
        if (target === 'shape') {
          const blank = await page.locator('.network-visualizer-canvas').evaluate(host => {
            const bounds = host.getBoundingClientRect();
            return { x: bounds.left + 15, y: bounds.top + bounds.height / 2 };
          });
          expect(await page.evaluate(({ x, y }) => !!document.elementFromPoint(x, y)?.closest('.network-visualizer-graph-surface'), blank))
            .toBe(true);
          await page.mouse.move(blank.x, blank.y);
          await frames(page);
          expect((await inspect(page)).labels[id].opacity).toBeCloseTo(baseline.labels[id].opacity, 6);
          await moveToShape(page, id);
          expectEmphasis(await inspect(page), id, baseline);
          await page.mouse.move(5, 5);
          await frames(page);
          expect((await inspect(page)).labels[id].opacity).toBeCloseTo(baseline.labels[id].opacity, 6);
        }
      }
      await page.getByRole('button', { name: 'Auto', exact: true }).click();
      await page.mouse.move(5, 5);
      await frames(page);
      await expect.poll(async () => (await inspect(page)).rotating).toBe(true);
      const group = await nodeId(page, 'talkgroup');
      const beforeAuto = await inspect(page);
      expect(beforeAuto.rotating).toBe(true);
      if (target.includes('name')) await moveToName(page, group);
      else await moveToShape(page, group);
      const afterAuto = await inspect(page);
      expect(afterAuto.rotating).toBe(true);
      expect(afterAuto.interacting).toBe(false);
      expect(afterAuto.camera).not.toEqual(beforeAuto.camera);
      if (target === 'shape') {
        const point = await moveToShape(page, group);
        await page.mouse.move(point.x, point.y);
        await page.mouse.down();
        await page.mouse.up();
        await expect(page.locator('.network-visualizer-back')).toHaveText('← System');
        await expect(page.locator('.network-visualizer-scope-title strong')).toHaveText('Fire Dispatch');
      }
      expect(state.requests).toEqual([]);
      expect(await page.evaluate(() => window.hoverAudioResumes)).toBe(0);
      expect(state.fixture.pageErrors).toEqual([]);
      expect(state.fixture.unexpected).toEqual([]);
    });
  }
}
