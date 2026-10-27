'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');

// Compare two real renderer nodes at each pose. Only one receives an action from the
// factory; projection, attention, animation and all label styling remain production code.
async function openLabels(page, theme = 'light', width = 1280, includeRadios = false) {
  await page.clock.install({ time: new Date('2026-10-01T12:00:00Z') });
  await page.clock.pauseAt(new Date('2026-10-01T12:00:01Z'));
  await page.setViewportSize({ width, height: 844 });
  await page.goto(`/design-system.html?theme=${theme}&view=health`);
  await page.evaluate(async withRadios => {
    const stage = document.createElement('section');
    stage.className = 'network-visualizer-stage';
    stage.style.height = '420px';
    const host = document.createElement('div');
    host.className = 'network-visualizer-canvas';
    stage.append(host);
    const outside = document.createElement('button');
    outside.className = 'ui-button ui-button-secondary';
    outside.textContent = 'Outside renderer';
    document.querySelector('.visual-health-example').replaceChildren(stage, outside);
    const vendor = await import('/assets/vendor/network-visualizer-vendor.js');
    const { createP25Renderer } = await import('/assets/features/network-visualizer/renderer.js');
    let graph;
    const library = { ...vendor, ForceGraph3D: options => {
      const factory = vendor.ForceGraph3D(options);
      return surface => (graph = factory(surface));
    } };
    let clicks = 0, interactions = 0;
    const renderer = await createP25Renderer({ host, mode: 'manual', library,
      onInteraction: () => { interactions++; },
      createDirectListenAction: node => {
        if (!node.id.startsWith('button-')) return null;
        const action = document.createElement('button');
        action.type = 'button';
        action.className = 'ui-button ui-button-secondary ui-button-compact';
        action.dataset.directListenTargetKey = node.id;
        action.dataset.directListenLabelButton = '';
        action.setAttribute('aria-label', `Add ${node.label} to Direct Listen and play`);
        action.setAttribute('aria-pressed', 'false');
        const text = document.createElement('span');
        text.className = 'ui-text-truncate';
        text.dataset.directListenText = '';
        text.textContent = node.label;
        action.append(text);
        action.addEventListener('click', () => { clicks++; });
        action.addEventListener('pointerdown', event => { window.labelDepthFixture.pointerId = event.pointerId; });
        return action;
      } });
    const poses = { near: { x: -15, y: 0, z: 140 }, far: { x: 40, y: 0, z: -120 } };
    const states = { near: {}, far: {} };
    const radioPoses = { near: { x: -22, y: 25, z: 120 }, far: { x: 55, y: -25, z: -120 } };
    const radioStates = { near: {}, far: {} };
    const data = () => ({ nodes: [
      { id: 'system', systemKey: 'system', type: 'system', label: 'Metro Public Safety',
        x: 0, y: 0, z: 0, radius: 300 },
      ...Object.entries(poses).flatMap(([key, pose]) => ['button', 'static'].map(kind => ({
        id: `${kind}-${key}`, systemKey: 'system', type: 'talkgroup',
        label: key === 'near' ? 'Near Dispatch' : 'Far Tactical', radius: 13, ...pose, ...states[key]
      }))),
      ...(withRadios ? [
        ...Object.entries(radioPoses).map(([key, pose]) => ({
          id: `radio-${key}`, systemKey: 'system', type: 'radio',
          label: key === 'near' ? 'Engine 4' : 'Rescue 7', radius: 6.5, ...pose, ...radioStates[key]
        })),
        { id: 'readonly-group', systemKey: 'system', type: 'talkgroup', label: 'Countywide',
          radius: 13, x: -75, y: 0, z: -40 }
      ] : [])
    ], links: [] });
    const update = (options = { animate: false }) => renderer.setData(data(), options);
    const camera = (position = [0, 0, 250], target = [0, 0, 0]) => {
      graph.camera().position.set(...position);
      graph.controls().target.set(...target);
      graph.controls().enableDamping = false;
      graph.camera().lookAt(graph.controls().target);
      graph.camera().updateMatrixWorld();
    };
    const inspect = () => {
      const values = {};
      const meshes = {};
      graph.scene().traverse(object => {
        const id = object.userData?.p25Node?.id;
        if (!id || !object.material) return;
        const node = object.userData.p25Node;
        const viewDepth = -new vendor.Vector3(node.x, node.y, node.z)
          .applyMatrix4(graph.camera().matrixWorldInverse).z;
        const density = graph.scene().fog?.density || 0;
        const transmission = object.material.fog ? Math.exp(-((density * Math.max(0, viewDepth)) ** 2)) : 1;
        meshes[id] = { opacity: object.material.opacity, fog: object.material.fog,
          viewDepth, transmission, effectiveOpacity: object.material.opacity * transmission,
          renderOrder: object.renderOrder, depthTest: object.material.depthTest };
      });
      for (const node of graph.graphData().nodes.filter(node => node.type !== 'system')) {
        const id = node.id;
        const label = host.querySelector(`[data-node-key="${id}"]`);
        const styles = label && getComputedStyle(label);
        const bounds = label?.getBoundingClientRect();
        values[id] = label ? { hidden: label.hidden, opacity: Number(styles.opacity),
          blur: Number(styles.filter.match(/blur\(([\d.]+)px\)/)?.[1] || 0),
          z: Number(styles.zIndex), position: label.style.translate,
          center: [bounds.x + bounds.width / 2, bounds.y + bounds.height / 2], signal: label.dataset.signal,
          focusVisible: label.matches(':focus-visible'), hovered: label.matches(':hover') } : null;
      }
      return { labels: values, meshes, interactions, interacting: renderer.isInteracting(), rotating: graph.controls().autoRotate,
        camera: graph.camera().position.toArray(), target: graph.controls().target.toArray(), clicks,
        focused: document.activeElement?.dataset.nodeKey,
        hovered: host.querySelector('[data-node-key="button-near"]')?.matches(':hover') };
    };
    window.labelDepthFixture = { renderer, graph, host, poses, states, radioPoses, radioStates, update, camera, inspect };
    update();
    renderer.enterSystem('system', { immediate: true });
    camera();
  }, includeRadios);
  await page.clock.runFor(100);
  await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
  await expect(page.locator('[data-node-key="button-near"]')).toBeVisible();
}

const state = page => page.evaluate(() => window.labelDepthFixture.inspect());
const action = page => page.locator('[data-node-key="button-near"]');
async function tick(page, milliseconds = 50) { await page.clock.runFor(milliseconds); }
async function press(page) {
  const bounds = await action(page).evaluate(label => label.getBoundingClientRect().toJSON());
  expect(bounds.width).toBeGreaterThan(0);
  expect(bounds.height).toBeGreaterThan(0);
  const point = { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 };
  const hit = await action(page).evaluate((label, point) => {
    const target = document.elementFromPoint(point.x, point.y);
    return { own: label.contains(target), target: target?.outerHTML.slice(0, 150),
      label: label.outerHTML, camera: window.labelDepthFixture.inspect().camera };
  }, point);
  expect(hit.own, `native pointer hit reaches the label: ${JSON.stringify({ point, hit })}`).toBe(true);
  await page.mouse.move(point.x, point.y);
  await page.mouse.down();
  expect((await state(page)).interacting).toBe(true);
}
function expectPair(labels, key) {
  const interactive = labels[`button-${key}`], ordinary = labels[`static-${key}`];
  expect(interactive).not.toBeNull();
  expect(ordinary).not.toBeNull();
  for (const property of ['hidden', 'opacity', 'blur', 'signal']) {
    expect(interactive[property], `${key} ${property}`).toEqual(ordinary[property]);
  }
}
async function evidence(page, name) {
  if (!process.env.WEB_REVIEW_DIR) return;
  fs.mkdirSync(process.env.WEB_REVIEW_DIR, { recursive: true });
  await page.locator('.network-visualizer-stage').screenshot({
    path: path.join(process.env.WEB_REVIEW_DIR, `${name}.png`) });
}

async function pointAtLabel(page, id) {
  const label = page.locator(`[data-node-key="${id}"]`);
  const center = await label.evaluate(value => {
    const bounds = value.getBoundingClientRect();
    return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 };
  });
  expect(await label.evaluate((value, point) => value.contains(document.elementFromPoint(point.x, point.y)), center),
    `native pointer reaches ${id}`).toBe(true);
  await page.mouse.move(center.x, center.y);
}

function expectMeshAlpha(snapshot, id) {
  expect(snapshot.labels[id].opacity, `${id} follows rendered mesh alpha and FogExp2 depth`)
    .toBeCloseTo(snapshot.meshes[id].effectiveOpacity, 6);
}

function expectFront(snapshot, id) {
  expect(snapshot.labels[id].opacity).toBe(1);
  expect(snapshot.labels[id].blur).toBe(0);
  for (const [other, label] of Object.entries(snapshot.labels)) {
    if (other !== id && label && !label.hidden) expect(snapshot.labels[id].z, `${id} above ${other}`).toBeGreaterThan(label.z);
  }
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`hover sharpens and lifts only the listen label then restores current depth ${theme} ${width}px`, async ({ page }) => {
    await openLabels(page, theme, width);
    await page.evaluate(() => {
      const f = window.labelDepthFixture; f.states.near.active = true; f.update();
    });
    await tick(page);
    const baseline = await state(page);
    await pointAtLabel(page, 'button-far');
    await tick(page);
    const hovered = await state(page);
    expect(hovered.labels['button-far'].hovered).toBe(true);
    expectFront(hovered, 'button-far');
    expect(hovered.interacting).toBe(false);
    expect(hovered.interactions).toBe(baseline.interactions);
    hovered.camera.forEach((coordinate, index) => expect(coordinate).toBeCloseTo(baseline.camera[index], 10));
    hovered.target.forEach((coordinate, index) => expect(coordinate).toBeCloseTo(baseline.target[index], 10));
    for (const [id, mesh] of Object.entries(hovered.meshes)) {
      for (const property of ['opacity', 'fog', 'renderOrder', 'depthTest']) {
        expect(mesh[property], `${id} ${property} unchanged by hover`).toBe(baseline.meshes[id][property]);
      }
    }
    await evidence(page, `hover-${theme}-${width}`);
    await page.evaluate(() => {
      const f = window.labelDepthFixture;
      f.poses.far.z -= 50; f.update();
    });
    await tick(page);
    const updated = await state(page);
    expectFront(updated, 'button-far');
    expect(updated.labels['static-far'].opacity).toBeLessThan(baseline.labels['static-far'].opacity);
    await page.mouse.move(10, 10);
    await tick(page);
    const left = await state(page);
    expect(left.labels['button-far'].hovered).toBe(false);
    expectPair(left.labels, 'far');
    expectMeshAlpha(left, 'button-far');
    expect(left.labels['button-far'].z).toBeLessThan(left.labels['button-near'].z);
    expect(left.labels['button-far'].opacity).toBeLessThan(baseline.labels['button-far'].opacity);
    await pointAtLabel(page, 'button-far');
    await page.evaluate(() => { window.labelDepthFixture.host.querySelector('[data-node-key="button-far"]').disabled = true; });
    await tick(page);
    const disabled = await state(page);
    expectMeshAlpha(disabled, 'button-far');
    expect(disabled.labels['button-far'].z).toBeLessThan(disabled.labels['button-near'].z);
    expect(disabled.interacting).toBe(false);
    await page.evaluate(() => window.labelDepthFixture.renderer.dispose());
  });
}

test('keyboard-visible focus lifts a label but retained mouse focus does not', async ({ page }) => {
  await openLabels(page);
  await page.evaluate(() => {
    const f = window.labelDepthFixture; f.states.near.active = true; f.update();
  });
  await tick(page);
  await page.getByRole('button', { name: 'Outside renderer', exact: true }).focus();
  await page.keyboard.press('Shift+Tab');
  await tick(page);
  const keyboard = await state(page);
  expect(keyboard.focused).toBe('button-far');
  expect(keyboard.labels['button-far'].focusVisible).toBe(true);
  expectFront(keyboard, 'button-far');
  expect(keyboard.interacting).toBe(false);
  await page.keyboard.press('Tab');
  await tick(page);
  const blurred = await state(page);
  expectMeshAlpha(blurred, 'button-far');
  await pointAtLabel(page, 'button-far');
  await page.mouse.down();
  await page.mouse.up();
  await page.mouse.move(10, 10);
  await tick(page);
  const mouse = await state(page);
  expect(mouse.focused).toBe('button-far');
  expect(mouse.labels['button-far'].focusVisible).toBe(false);
  expect(mouse.labels['button-far'].hovered).toBe(false);
  expect(mouse.interacting).toBe(false);
  expectMeshAlpha(mouse, 'button-far');
  expect(mouse.labels['button-far'].blur).toBeGreaterThan(0);
  expect(mouse.labels['button-far'].z).toBeLessThan(mouse.labels['button-near'].z);
  expect(mouse.clicks).toBe(1);
  await page.locator('[data-node-key="button-far"]').evaluate(label => label.setAttribute('aria-pressed', 'true'));
  await tick(page);
  const member = await state(page);
  expectMeshAlpha(member, 'button-far');
  expect(member.labels['button-far'].z).toBeLessThan(member.labels['button-near'].z);
  await page.evaluate(() => window.labelDepthFixture.renderer.dispose());
});

test('hover leaves automatic rotation and attention camera motion running', async ({ page }) => {
  await openLabels(page);
  await page.evaluate(() => window.labelDepthFixture.renderer.setMode('auto'));
  const before = await state(page);
  await pointAtLabel(page, 'button-far');
  await tick(page, 100);
  const rotating = await state(page);
  expect(rotating.interacting).toBe(false);
  expect(rotating.rotating).toBe(true);
  expect(rotating.camera).not.toEqual(before.camera);
  expect(rotating.interactions).toBe(before.interactions);
  await page.evaluate(() => {
    const f = window.labelDepthFixture;
    f.renderer.setCameraPhase('focus');
    f.renderer.focus(['button-near', 'static-near'], 1000);
  });
  await pointAtLabel(page, 'button-far');
  const attention = await state(page);
  await tick(page, 500);
  const moving = await state(page);
  expect(moving.interacting).toBe(false);
  expect(moving.camera).not.toEqual(attention.camera);
  expect(moving.meshes['button-near'].fog).toBe(false);
  expect(await page.evaluate(() => window.labelDepthFixture.graph.controls().enabled)).toBe(false);
  await tick(page, 600);
  expect(await page.evaluate(() => window.labelDepthFixture.graph.controls().enabled)).toBe(true);
  expect((await state(page)).interactions).toBe(before.interactions);
  await page.evaluate(() => window.labelDepthFixture.renderer.dispose());
});

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`radio labels follow camera-space mesh fog and activity ordering ${theme} ${width}px`, async ({ page }) => {
    await openLabels(page, theme, width, true);
    const initial = await state(page);
    for (const id of ['radio-near', 'radio-far']) expectMeshAlpha(initial, id);
    expect(initial.labels['radio-near'].opacity).toBeGreaterThan(initial.labels['radio-far'].opacity);
    expect(initial.labels['radio-near'].blur).toBeLessThan(initial.labels['radio-far'].blur);
    expect(initial.labels['radio-near'].z).toBeGreaterThan(initial.labels['radio-far'].z);
    await page.evaluate(() => {
      const f = window.labelDepthFixture;
      f.renderer.enterTalkgroup('system', 'button-near', { immediate: true }); f.camera();
    });
    await tick(page);
    const scoped = await state(page);
    for (const id of ['radio-near', 'radio-far']) expectMeshAlpha(scoped, id);
    expect(scoped.labels['radio-far'].opacity).toBeLessThan(scoped.labels['radio-near'].opacity);
    expect(scoped.labels['radio-far'].blur).toBeGreaterThan(0);
    expect(scoped.meshes['radio-far'].fog).toBe(true);
    await evidence(page, `radio-depth-${theme}-${width}`);
    for (const id of ['radio-near', 'readonly-group']) {
      const label = page.locator(`[data-node-key="${id}"]`);
      const point = await label.evaluate(value => {
        const bounds = value.getBoundingClientRect();
        const x = bounds.x + bounds.width / 2, y = bounds.y + bounds.height / 2;
        return { x, y, tag: value.tagName, action: value.hasAttribute('data-direct-listen-target-key'),
          pointerEvents: getComputedStyle(value).pointerEvents,
          intercepts: value.contains(document.elementFromPoint(x, y)) };
      });
      expect(point.tag).toBe('SPAN');
      expect(point.action).toBe(false);
      expect(point.pointerEvents).toBe('none');
      expect(point.intercepts).toBe(false);
      expect(await page.evaluate(({ x, y }) => window.labelDepthFixture.host.contains(document.elementFromPoint(x, y)), point))
        .toBe(true);
      await page.mouse.move(point.x, point.y);
      await tick(page);
      const hovered = await state(page);
      expectFront(hovered, id);
      expect(hovered.interacting).toBe(false);
      expect(hovered.interactions).toBe(scoped.interactions);
      expect(hovered.clicks).toBe(0);
      for (const property of ['opacity', 'fog', 'renderOrder', 'depthTest']) {
        expect(hovered.meshes[id][property]).toBe(scoped.meshes[id][property]);
      }
      await page.mouse.move(10, 10);
      await tick(page);
      expectMeshAlpha(await state(page), id);
    }
    await page.evaluate(() => {
      const f = window.labelDepthFixture;
      f.radioPoses.near = { x: 0, y: 0, z: -80 };
      f.radioPoses.far = { x: 90, y: 0, z: -80 }; f.update();
    });
    await tick(page);
    const equalDepth = await state(page);
    expect(equalDepth.meshes['radio-near'].viewDepth).toBeCloseTo(equalDepth.meshes['radio-far'].viewDepth, 8);
    expect(equalDepth.labels['radio-near'].opacity).toBeCloseTo(equalDepth.labels['radio-far'].opacity, 6);
    expect(equalDepth.labels['radio-near'].blur).toBe(equalDepth.labels['radio-far'].blur);
    for (const id of ['radio-near', 'radio-far']) expectMeshAlpha(equalDepth, id);
    await page.evaluate(() => window.labelDepthFixture.camera([180, 0, 170]));
    await tick(page);
    const angled = await state(page);
    for (const id of ['radio-near', 'radio-far']) expectMeshAlpha(angled, id);
    expect(angled.meshes['radio-far'].viewDepth).toBeLessThan(angled.meshes['radio-near'].viewDepth);
    expect(angled.labels['radio-far'].opacity).toBeGreaterThan(angled.labels['radio-near'].opacity);
    expect(angled.labels['radio-far'].blur).toBeLessThan(angled.labels['radio-near'].blur);
    expect(angled.labels['radio-far'].z).toBeGreaterThan(angled.labels['radio-near'].z);
    await page.evaluate(() => window.labelDepthFixture.camera([0, 0, 180]));
    await tick(page);
    const closer = await state(page);
    for (const id of ['radio-near', 'radio-far']) expectMeshAlpha(closer, id);
    expect(closer.labels['radio-near'].opacity).toBeGreaterThan(equalDepth.labels['radio-near'].opacity);
    expect(closer.labels['radio-near'].blur).toBeLessThan(equalDepth.labels['radio-near'].blur);
    await page.evaluate(() => {
      const f = window.labelDepthFixture;
      f.radioStates.far.active = true; f.update();
    });
    await tick(page);
    const active = await state(page);
    expectFront(active, 'radio-far');
    expect(active.meshes['radio-far'].fog).toBe(false);
    expect(active.meshes['radio-far'].renderOrder).toBe(initial.meshes['radio-far'].renderOrder);
    expect(active.meshes['radio-far'].depthTest).toBe(initial.meshes['radio-far'].depthTest);
    await page.evaluate(() => {
      const f = window.labelDepthFixture;
      f.radioStates.far = { signalAction: 'call' }; f.update();
    });
    await tick(page);
    const signaled = await state(page);
    expectFront(signaled, 'radio-far');
    expect(signaled.labels['radio-far'].signal).toBe('call');
    await page.evaluate(() => {
      const f = window.labelDepthFixture;
      f.radioStates.far = {}; f.update();
      f.renderer.setMode('auto'); f.renderer.setCameraPhase('focus');
      f.renderer.focus(['radio-far'], 0); f.camera();
    });
    await tick(page, 300);
    const focused = await state(page);
    expect(focused.meshes['radio-far'].fog).toBe(false);
    expectMeshAlpha(focused, 'radio-far');
    expect(focused.labels['radio-far'].opacity).toBeCloseTo(.98, 6);
    expect(focused.labels['radio-far'].blur).toBe(0);
    expect(focused.labels['radio-far'].z).toBeLessThan(focused.labels['radio-near'].z);
    expectMeshAlpha(focused, 'radio-near');
    await page.evaluate(() => {
      const f = window.labelDepthFixture;
      f.renderer.resetAttention(); f.renderer.setCameraPhase('focus'); f.camera();
      f.radioStates.far = { affiliationGroupKey: 'button-near' };
      f.radioPoses.far = { x: -15, y: 0, z: 140 };
      f.update({ affiliationChanges: [{ id: 'radio-movement', radioKey: 'radio-far',
        fromGroupKey: 'readonly-group', toGroupKey: 'button-near',
        from: { x: 90, y: 0, z: -80 }, target: f.radioPoses.far, remainingMs: 8000 }] });
    });
    await tick(page, 100);
    const moving = await state(page);
    for (const id of ['radio-far', 'readonly-group', 'button-near']) {
      expect(moving.labels[id].opacity).toBe(1);
      expect(moving.labels[id].blur).toBe(0);
      expect(moving.meshes[id].fog).toBe(false);
      expect(moving.labels[id].z).toBeGreaterThan(moving.labels['static-near'].z);
    }
    await page.evaluate(() => {
      const f = window.labelDepthFixture;
      f.renderer.setMode('manual');
      f.radioPoses.far.z = 400; f.update();
    });
    await tick(page);
    expect((await state(page)).labels['radio-far'].hidden).toBe(true);
    await page.evaluate(() => window.labelDepthFixture.renderer.dispose());
  });
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Direct Listen labels share shape depth and attention ${theme} ${width}px`, async ({ page }) => {
    await openLabels(page, theme, width);
    const initial = await state(page);
    for (const key of ['near', 'far']) expectPair(initial.labels, key);
    expect(initial.labels['button-near'].opacity).toBeGreaterThan(initial.labels['button-far'].opacity);
    expect(initial.labels['button-near'].blur).toBeLessThan(initial.labels['button-far'].blur);
    expect(initial.labels['button-near'].z).toBeGreaterThan(initial.labels['button-far'].z);
    await evidence(page, `depth-${theme}-${width}`);
    await page.evaluate(() => {
      const f = window.labelDepthFixture;
      f.renderer.setMode('auto');
      f.renderer.setCameraPhase('focus');
      f.renderer.focus(['button-near', 'static-near'], 0);
      f.camera();
    });
    await tick(page, 300);
    const focused = await state(page);
    for (const key of ['near', 'far']) expectPair(focused.labels, key);
    expect(focused.labels['button-near'].opacity).toBeCloseTo(focused.meshes['button-near'].effectiveOpacity, 6);
    expect(focused.labels['button-near'].blur).toBe(0);
    expect(focused.labels['button-far'].opacity).toBeLessThan(initial.labels['button-far'].opacity / 2);
    expect(focused.labels['button-far'].blur).toBeGreaterThan(initial.labels['button-far'].blur);
    await page.evaluate(() => {
      const f = window.labelDepthFixture;
      f.states.far = { active: true }; f.update();
    });
    await tick(page);
    const active = await state(page);
    expectPair(active.labels, 'far');
    expect(active.labels['button-far'].opacity).toBe(1);
    expect(active.labels['button-far'].blur).toBe(0);
    expect(active.labels['button-far'].signal).toBe('call');
    for (const kind of ['button', 'static']) {
      expect(active.labels[`${kind}-far`].z).toBeGreaterThan(active.labels['button-near'].z);
      expect(active.labels[`${kind}-far`].z).toBeGreaterThan(active.labels['static-near'].z);
    }
    await page.evaluate(() => {
      const f = window.labelDepthFixture;
      f.poses.near.x += 12; f.poses.far.x -= 12; f.update();
    });
    await tick(page, 20);
    const moved = await state(page);
    for (const key of ['near', 'far']) {
      for (let axis = 0; axis < 2; axis++) {
        expect(Math.abs(moved.labels[`button-${key}`].center[axis] - moved.labels[`static-${key}`].center[axis]))
          .toBeLessThanOrEqual(0.5);
      }
    }
    await page.evaluate(() => {
      const f = window.labelDepthFixture;
      f.poses.far.z = 400; f.update();
    });
    await tick(page);
    const culled = await state(page);
    expectPair(culled.labels, 'far');
    expect(culled.labels['button-far'].hidden).toBe(true);
    await page.evaluate(() => window.labelDepthFixture.renderer.dispose());
  });
}

test('held hovered label stays clear while depth changes and restores current depth after leaving', async ({ page }) => {
  await openLabels(page);
  await press(page);
  const held = await state(page);
  await page.evaluate(() => {
    const f = window.labelDepthFixture;
    f.poses.near = { x: 40, y: -20, z: -180 }; f.update();
  });
  await tick(page);
  const deeper = await state(page);
  expect(deeper.labels['button-near'].position).toBe(held.labels['button-near'].position);
  expect(deeper.labels['button-near'].opacity).toBe(1);
  expect(deeper.labels['button-near'].blur).toBe(0);
  expect(deeper.labels['button-near'].z).toBeGreaterThan(deeper.labels['static-near'].z);
  expect(deeper.labels['static-near'].opacity).toBeLessThan(held.labels['static-near'].opacity);
  expect(deeper.labels['static-near'].blur).toBeGreaterThan(held.labels['static-near'].blur);
  expect(deeper.labels['static-near'].z).toBeLessThan(held.labels['static-near'].z);
  await page.mouse.up();
  await page.mouse.move(10, 10);
  await tick(page);
  const left = await state(page);
  expect(left.focused).toBe('button-near');
  expect(left.labels['button-near'].focusVisible).toBe(false);
  expectPair(left.labels, 'near');
  expect(left.labels['button-near'].opacity).toBeLessThan(held.labels['static-near'].opacity);
  await page.evaluate(() => {
    const f = window.labelDepthFixture;
    f.states.near = { active: true }; f.update();
  });
  await tick(page);
  const active = await state(page);
  expectPair(active.labels, 'near');
  expect(active.labels['button-near'].opacity).toBe(1);
  expect(active.labels['button-near'].blur).toBe(0);
  expect(active.labels['button-near'].signal).toBe('call');
  await press(page);
  await page.evaluate(() => {
    const f = window.labelDepthFixture;
    f.poses.near.z = 400; f.update();
  });
  await tick(page);
  const culled = await state(page);
  expect(culled.labels['button-near'].hidden).toBe(true);
  expect(culled.interacting).toBe(false);
  await page.mouse.up();
  await page.evaluate(() => window.labelDepthFixture.renderer.dispose());
});

test('mouse and native Enter or Space presses resume rotation with hover and focus retained', async ({ page }) => {
  await openLabels(page);
  await page.evaluate(() => window.labelDepthFixture.renderer.setMode('auto'));
  await action(page).focus();
  const bounds = await action(page).boundingBox();
  await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
  await tick(page, 50);
  expect((await state(page)).interacting).toBe(false);
  expect((await state(page)).rotating).toBe(true);
  await page.mouse.down();
  expect((await state(page)).interacting).toBe(true);
  expect((await state(page)).rotating).toBe(false);
  await page.mouse.up();
  const released = await state(page);
  expect(released.interacting).toBe(false);
  expect(released.rotating).toBe(true);
  expect(released.hovered).toBe(true);
  expect(released.focused).toBe('button-near');
  expect(released.clicks).toBe(1);
  await tick(page, 100);
  expect((await state(page)).camera).not.toEqual(released.camera);
  for (const [key, clicks] of [['Enter', 2], ['Space', 3]]) {
    await page.keyboard.down(key);
    expect((await state(page)).interacting).toBe(true);
    expect((await state(page)).rotating).toBe(false);
    const held = await state(page);
    await tick(page, 100);
    (await state(page)).camera.forEach((coordinate, index) => expect(coordinate).toBeCloseTo(held.camera[index], 10));
    await page.keyboard.up(key);
    const after = await state(page);
    expect(after.interacting).toBe(false);
    expect(after.rotating).toBe(true);
    expect(after.focused).toBe('button-near');
    expect(after.clicks).toBe(clicks);
  }
  await page.keyboard.down('Space');
  expect((await state(page)).interacting).toBe(true);
  await page.getByRole('button', { name: 'Outside renderer', exact: true }).focus();
  expect((await state(page)).interacting).toBe(false);
  expect((await state(page)).rotating).toBe(true);
  await page.keyboard.up('Space');
  expect((await state(page)).clicks).toBe(3);
  await page.evaluate(() => window.labelDepthFixture.renderer.dispose());
});

test('press cancellation, permission loss and renderer lifecycle never leave rotation held', async ({ page }) => {
  await openLabels(page);
  await page.evaluate(() => window.labelDepthFixture.renderer.setMode('auto'));
  for (const cancel of ['pointercancel', 'capture', 'reset', 'manual', 'hidden', 'guide', 'disabled']) {
    await page.evaluate(() => {
      const f = window.labelDepthFixture;
      f.renderer.setMode('manual'); f.camera(); f.renderer.setMode('auto');
    });
    await tick(page);
    await press(page);
    if (cancel === 'capture') {
      const bounds = await action(page).boundingBox();
      // Establish native capture before testing its loss; pending capture can be
      // withdrawn without a got/lost pair if no pointer event has followed down.
      await page.mouse.move(bounds.x + bounds.width / 2 + 1, bounds.y + bounds.height / 2);
    }
    await page.evaluate(kind => {
      const f = window.labelDepthFixture;
      const label = f.host.querySelector('[data-node-key="button-near"]');
      if (kind === 'pointercancel') label.dispatchEvent(new PointerEvent('pointercancel', { pointerId: f.pointerId }));
      if (kind === 'capture') label.releasePointerCapture(f.pointerId);
      if (kind === 'reset') f.renderer.resetAttention();
      if (kind === 'manual') f.renderer.setMode('manual');
      if (kind === 'hidden') {
        f.hidden = true;
        Object.defineProperty(document, 'hidden', { configurable: true, get: () => f.hidden });
        document.dispatchEvent(new Event('visibilitychange'));
      }
      if (kind === 'guide') f.renderer.setGuideSuspended(true);
      if (kind === 'disabled') label.disabled = true;
    }, cancel);
    // Chromium delivers a pending lostpointercapture before the next pointer event.
    if (cancel === 'capture') {
      const bounds = await action(page).boundingBox();
      await page.mouse.move(bounds.x + bounds.width / 2 + 2, bounds.y + bounds.height / 2);
    }
    await tick(page);
    expect((await state(page)).interacting, cancel).toBe(false);
    await page.mouse.up();
    await page.evaluate(() => {
      const f = window.labelDepthFixture;
      if (f.hidden) { f.hidden = false; document.dispatchEvent(new Event('visibilitychange')); }
      f.renderer.setGuideSuspended(false);
      f.host.querySelector('[data-node-key="button-near"]').disabled = false;
      f.renderer.setMode('auto');
    });
    await tick(page);
    expect((await state(page)).rotating, cancel).toBe(true);
  }
  await press(page);
  await page.evaluate(() => {
    const f = window.labelDepthFixture;
    f.renderer.setData({ nodes: f.graph.graphData().nodes.filter(node => node.id !== 'button-near'), links: [] });
  });
  await tick(page);
  expect((await state(page)).interacting).toBe(false);
  expect((await state(page)).rotating).toBe(true);
  await page.mouse.up();
  await page.evaluate(() => window.labelDepthFixture.update());
  await tick(page);
  // Wait for the renderer's scene placement before sending a real pointer press.
  await expect.poll(() => action(page).evaluate(label => label.style.translate)).not.toBe('');
  await tick(page, 150);
  await press(page);
  await page.evaluate(() => { delete document.hidden; window.labelDepthFixture.renderer.dispose(); });
  expect(await page.evaluate(() => window.labelDepthFixture.renderer.isInteracting())).toBe(false);
  await page.mouse.up();
  await expect(page.locator('.network-visualizer-canvas canvas')).toHaveCount(0);
});

test('a label press pauses an attention tween and release resumes its remaining duration', async ({ page }) => {
  await openLabels(page);
  await page.evaluate(() => {
    const f = window.labelDepthFixture;
    f.renderer.setMode('auto'); f.renderer.setCameraPhase('focus');
    f.renderer.focus(['button-near', 'static-near'], 1000);
  });
  await tick(page, 100);
  await press(page);
  const held = await state(page);
  await tick(page, 2000);
  const paused = await state(page);
  paused.camera.forEach((coordinate, index) => expect(coordinate).toBeCloseTo(held.camera[index], 10));
  paused.target.forEach((coordinate, index) => expect(coordinate).toBeCloseTo(held.target[index], 10));
  await page.mouse.up();
  expect((await state(page)).interacting).toBe(false);
  await tick(page, 50);
  const resuming = await state(page);
  expect(resuming.camera).not.toEqual(held.camera);
  expect(await page.evaluate(() => window.labelDepthFixture.graph.controls().enabled)).toBe(false);
  await tick(page, 1000);
  expect(await page.evaluate(() => window.labelDepthFixture.graph.controls().enabled)).toBe(true);
  await page.evaluate(() => window.labelDepthFixture.renderer.dispose());
});
