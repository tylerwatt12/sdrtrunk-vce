'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');

async function openScene(page, theme, width) {
  await page.clock.install({ time: new Date('2026-10-01T12:00:00Z') });
  await page.clock.pauseAt(new Date('2026-10-01T12:00:01Z'));
  await page.setViewportSize({ width, height: 844 });
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
    const links = new Map();
    const library = { ...vendor, ForceGraph3D: options => {
      const factory = vendor.ForceGraph3D(options);
      return surface => {
        graph = factory(surface);
        const register = graph.linkThreeObject;
        graph.linkThreeObject = (...args) => !args.length ? register() : register(link => {
          const [callback] = args;
          const object = callback(link);
          links.set(link.id, object);
          return object;
        });
        return graph;
      };
    } };
    const renderer = await createP25Renderer({ host, mode: 'manual', library,
      createDirectListenAction: node => {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'ui-button ui-button-secondary ui-button-compact';
        button.dataset.directListenTargetKey = node.id;
        button.setAttribute('aria-label', `Add ${node.label} to Direct Listen and play`);
        button.textContent = node.label;
        return button;
      } });
    const system = { id: 'system', systemKey: 'system', type: 'system', label: 'Metro Public Safety',
      x: 0, y: 0, z: 0, radius: 300 };
    const groups = [
      { id: 'A', label: 'Dispatch', x: -90, y: 35, z: 0 },
      { id: 'B', label: 'Tactical', x: 35, y: 35, z: 0 },
      { id: 'C', label: 'Fireground', x: -90, y: -55, z: -20, active: true },
      { id: 'D', label: 'Operations', x: 35, y: -55, z: -20, signalAction: 'page' },
      { id: 'E', label: 'Countywide', x: 100, y: 0, z: -80 }
    ].map(group => ({ ...group, systemKey: system.id, type: 'talkgroup', radius: 13 }));
    const poses = Object.fromEntries(groups.map(group => [group.id,
      { x: group.x + 18, y: group.y, z: group.z + 10 }]));
    const radio = (id, group, extra = {}) => ({ id, systemKey: system.id, type: 'radio',
      label: id === 'selected-radio' ? 'Engine 4' : id === 'other-radio' ? 'Rescue 7' : 'Unit 9',
      radius: 6.5, ...poses[group], groupKey: group, affiliationGroupKey: group, ...extra });
    const data = moving => ({ nodes: [system, ...groups,
      radio('selected-radio', moving ? 'B' : 'A', { signalAction: 'movement' }),
      radio('other-radio', moving ? 'D' : 'C', { active: true, signalAction: 'call' }),
      radio('idle-radio', 'E')],
    links: [
      { id: 'selected-current', source: 'selected-radio', target: moving ? 'B' : 'A', kind: 'current' },
      { id: 'other-current', source: 'other-radio', target: moving ? 'D' : 'C', kind: 'current' },
      { id: 'idle-current', source: 'idle-radio', target: 'E', kind: 'current' },
      { id: 'other-call', source: 'C', target: 'D', kind: 'activity' }
    ] });
    const camera = () => {
      graph.camera().position.set(0, 0, 350);
      graph.controls().target.set(0, 0, 0);
      graph.controls().enableDamping = false;
      graph.camera().lookAt(graph.controls().target);
      graph.camera().updateMatrixWorld();
    };
    const inspect = () => {
      const meshes = {}, labels = {};
      graph.scene().traverse(object => {
        const node = object.userData?.p25Node;
        if (!node) return;
        const materials = object.material ? [object.material] : object.children.map(child => child.material);
        meshes[node.id] = { opacity: materials[0]?.opacity, fog: materials[0]?.fog,
          shellOpacities: materials.map(material => material?.opacity),
          order: object.renderOrder, depthTest: materials[0]?.depthTest, depthWrite: materials[0]?.depthWrite };
      });
      for (const label of host.querySelectorAll('[data-node-key]')) {
        const style = getComputedStyle(label);
        labels[label.dataset.nodeKey] = { hidden: label.hidden, opacity: Number(style.opacity),
          blur: Number(style.filter.match(/blur\(([\d.]+)px\)/)?.[1] || 0), z: Number(style.zIndex) };
      }
      return { meshes, labels,
        links: Object.fromEntries([...links].filter(([, object]) => object.parent).map(([id, object]) =>
          [id, { opacity: object.material.opacity, order: object.renderOrder,
            depthTest: object.material.depthTest, depthWrite: object.material.depthWrite }])),
        rotating: graph.controls().autoRotate, interacting: renderer.isInteracting() };
    };
    const begin = () => {
      renderer.setMode('auto');
      renderer.setData(data(true), { affiliationChanges: [
        { id: 'selected-A-B', radioKey: 'selected-radio', fromGroupKey: 'A', toGroupKey: 'B',
          from: poses.A, target: poses.B, remainingMs: 8_000 },
        { id: 'other-C-D', radioKey: 'other-radio', fromGroupKey: 'C', toGroupKey: 'D',
          from: poses.C, target: poses.D, remainingMs: 8_000 }
      ] });
      renderer.setCameraPhase('focus');
      renderer.focusAffiliation('selected-radio', 0);
      camera();
    };
    renderer.setData(data(false), { animate: false });
    renderer.enterSystem('system', { immediate: true });
    camera();
    window.focusFadeFixture = { renderer, graph, inspect, camera, begin };
  });
  await page.clock.runFor(100);
  await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
  await expect(page.locator('[data-node-key="C"]')).toBeVisible();
  await page.mouse.move(5, 5);
}

const state = page => page.evaluate(() => window.focusFadeFixture.inspect());

async function evidence(page, name, details) {
  if (!process.env.WEB_REVIEW_DIR) return;
  fs.mkdirSync(process.env.WEB_REVIEW_DIR, { recursive: true });
  fs.writeFileSync(path.join(process.env.WEB_REVIEW_DIR, `${name}.json`), JSON.stringify(details, null, 2));
  await page.locator('.network-visualizer-stage').screenshot({ path: path.join(process.env.WEB_REVIEW_DIR, `${name}.png`) });
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`event focus fades unrelated calls and concurrent moves then restores them ${theme} ${width}px`, async ({ page }) => {
    await openScene(page, theme, width);
    const baseline = await state(page);
    for (const id of ['C', 'D', 'other-radio']) expect(baseline.labels[id].opacity).toBe(1);
    await page.evaluate(() => window.focusFadeFixture.begin());
    await page.clock.runFor(80);
    const intermediate = await state(page);
    expect(intermediate.meshes.C.opacity).toBeLessThan(baseline.meshes.C.opacity);
    expect(intermediate.meshes.C.opacity).toBeGreaterThan(.1);
    expect(intermediate.labels.C.opacity).toBeGreaterThan(.1);
    await page.clock.runFor(240);
    const focused = await state(page);
    for (const id of ['A', 'B', 'selected-radio']) {
      expect(focused.meshes[id].opacity).toBeGreaterThan(.9);
      expect(focused.labels[id].opacity).toBe(1);
      expect(focused.labels[id].blur).toBe(0);
      for (const other of ['C', 'D', 'E', 'other-radio', 'idle-radio']) {
        expect(focused.labels[id].z).toBeGreaterThan(focused.labels[other].z);
      }
    }
    for (const id of ['C', 'D', 'E', 'other-radio', 'idle-radio']) {
      expect(focused.meshes[id].opacity).toBeLessThan(.08);
      expect(focused.labels[id].opacity).toBeLessThan(.1);
      expect(focused.labels[id].blur).toBeGreaterThan(0);
    }
    expect(focused.meshes.system.shellOpacities.every(opacity => opacity < .2)).toBe(true);
    for (const id of ['selected-current', 'movement:selected-radio:A']) expect(focused.links[id].opacity).toBeGreaterThan(.5);
    for (const id of ['other-current', 'movement:other-radio:C', 'idle-current', 'other-call']) expect(focused.links[id].opacity).toBeLessThan(.02);
    for (const id of Object.keys(baseline.meshes)) {
      for (const property of ['order', 'depthTest', 'depthWrite']) expect(focused.meshes[id][property]).toBe(baseline.meshes[id][property]);
    }
    await evidence(page, `focused-${theme}-${width}`, { baseline, intermediate, focused });

    const label = page.locator('[data-node-key="C"]');
    const point = await label.evaluate(element => {
      const bounds = element.getBoundingClientRect();
      return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 };
    });
    expect(await label.evaluate((element, value) => element.contains(document.elementFromPoint(value.x, value.y)), point)).toBe(true);
    await page.mouse.move(point.x, point.y);
    await page.clock.runFor(50);
    const hovered = await state(page);
    expect(hovered.labels.C.opacity).toBe(1);
    expect(hovered.labels.C.blur).toBe(0);
    expect(hovered.labels.C.z).toBeGreaterThan(Math.max(...Object.entries(hovered.labels).filter(([id]) => id !== 'C').map(([, value]) => value.z)));
    expect(hovered.meshes).toEqual(focused.meshes);
    expect(hovered.links).toEqual(focused.links);
    expect(hovered.interacting).toBe(false);
    await page.mouse.move(5, 5);
    await page.clock.runFor(50);
    expect((await state(page)).labels.C.opacity).toBeLessThan(.1);

    // Keyboard focus stays above event focus; a separate hovered name is above both.
    await page.keyboard.press('Tab');
    await label.focus();
    await page.clock.runFor(50);
    expect(await label.evaluate(element => element.matches(':focus-visible'))).toBe(true);
    const keyboard = await state(page);
    expect(keyboard.labels.C.opacity).toBe(1);
    expect(keyboard.labels.C.blur).toBe(0);
    for (const id of ['A', 'B', 'selected-radio']) expect(keyboard.labels.C.z).toBeGreaterThan(keyboard.labels[id].z);
    const otherLabel = page.locator('[data-node-key="D"]');
    const otherPoint = await otherLabel.evaluate(element => {
      const bounds = element.getBoundingClientRect();
      return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 };
    });
    expect(await otherLabel.evaluate((element, value) => element.contains(document.elementFromPoint(value.x, value.y)), otherPoint)).toBe(true);
    await page.mouse.move(otherPoint.x, otherPoint.y);
    await page.clock.runFor(50);
    const pointer = await state(page);
    expect(pointer.labels.D.opacity).toBe(1);
    expect(pointer.labels.D.blur).toBe(0);
    expect(pointer.labels.D.z).toBeGreaterThan(pointer.labels.C.z);
    expect(pointer.meshes).toEqual(focused.meshes);
    await label.evaluate(element => element.blur());
    await page.mouse.move(5, 5);
    await page.clock.runFor(50);
    const unpointed = await state(page);
    expect(unpointed.labels.C.opacity).toBeLessThan(.1);
    expect(unpointed.labels.D.opacity).toBeLessThan(.1);

    await page.evaluate(() => {
      const fixture = window.focusFadeFixture;
      fixture.renderer.focusAffiliation('other-radio', 0);
      fixture.camera();
    });
    await page.clock.runFor(50);
    const retargeted = await state(page);
    for (const id of ['C', 'D', 'other-radio']) expect(retargeted.meshes[id].opacity).toBeGreaterThan(.9);
    for (const id of ['A', 'B', 'selected-radio']) expect(retargeted.meshes[id].opacity).toBeLessThan(.08);
    expect(retargeted.links['selected-current'].opacity).toBeLessThan(.02);
    expect(retargeted.links['other-current'].opacity).toBeGreaterThan(.5);

    await page.evaluate(() => window.focusFadeFixture.renderer.setCameraPhase('return'));
    await page.clock.runFor(80);
    const returning = await state(page);
    expect(returning.meshes['idle-radio'].opacity).toBeGreaterThan(focused.meshes['idle-radio'].opacity);
    expect(returning.meshes['idle-radio'].opacity).toBeLessThan(baseline.meshes['idle-radio'].opacity);
    await page.clock.runFor(240);
    const restored = await state(page);
    for (const id of ['C', 'D', 'other-radio']) expect(restored.labels[id].opacity).toBe(1);
    expect(restored.meshes['idle-radio'].opacity).toBeCloseTo(baseline.meshes['idle-radio'].opacity, 8);
    expect(restored.links['idle-current'].opacity).toBeCloseTo(baseline.links['idle-current'].opacity, 8);
    expect(restored.meshes.system.shellOpacities).toEqual(baseline.meshes.system.shellOpacities);
    await evidence(page, `restored-${theme}-${width}`, { focused, retargeted, returning, restored });

    await page.evaluate(() => {
      const fixture = window.focusFadeFixture;
      fixture.renderer.setCameraPhase('focus');
      fixture.renderer.focus(['selected-radio'], 0);
      fixture.camera();
    });
    await page.clock.runFor(300);
    expect((await state(page)).labels.C.opacity).toBeLessThan(.1);
    await page.evaluate(() => window.focusFadeFixture.renderer.setMode('manual'));
    await page.clock.runFor(50);
    expect((await state(page)).labels.C.opacity).toBe(1);
    await page.evaluate(() => window.focusFadeFixture.renderer.dispose());
    await expect(page.locator('.network-visualizer-canvas canvas')).toHaveCount(0);
  });
}
