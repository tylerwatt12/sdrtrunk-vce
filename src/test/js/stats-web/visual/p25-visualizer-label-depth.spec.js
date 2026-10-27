'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');

// Compare two real renderer nodes at each pose. Only one receives an action from the
// factory; projection, attention, animation and all label styling remain production code.
async function openLabels(page, theme = 'light', width = 1280) {
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
    let clicks = 0;
    const renderer = await createP25Renderer({ host, mode: 'manual', library,
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
    const data = () => ({ nodes: [
      { id: 'system', systemKey: 'system', type: 'system', label: 'Metro Public Safety',
        x: 0, y: 0, z: 0, radius: 300 },
      ...Object.entries(poses).flatMap(([key, pose]) => ['button', 'static'].map(kind => ({
        id: `${kind}-${key}`, systemKey: 'system', type: 'talkgroup',
        label: key === 'near' ? 'Near Dispatch' : 'Far Tactical', radius: 13, ...pose, ...states[key]
      })))
    ], links: [] });
    const update = () => renderer.setData(data(), { animate: false });
    const camera = () => {
      graph.camera().position.set(0, 0, 250);
      graph.controls().target.set(0, 0, 0);
      graph.controls().enableDamping = false;
      graph.camera().lookAt(graph.controls().target);
      graph.camera().updateMatrixWorld();
    };
    const inspect = () => {
      const values = {};
      for (const key of ['near', 'far']) for (const kind of ['button', 'static']) {
        const id = `${kind}-${key}`;
        const label = host.querySelector(`[data-node-key="${id}"]`);
        const styles = label && getComputedStyle(label);
        const bounds = label?.getBoundingClientRect();
        values[id] = label ? { hidden: label.hidden, opacity: Number(styles.opacity),
          blur: Number(styles.filter.match(/blur\(([\d.]+)px\)/)?.[1] || 0),
          z: Number(styles.zIndex), position: label.style.translate,
          center: [bounds.x + bounds.width / 2, bounds.y + bounds.height / 2], signal: label.dataset.signal } : null;
      }
      return { labels: values, interacting: renderer.isInteracting(), rotating: graph.controls().autoRotate,
        camera: graph.camera().position.toArray(), target: graph.controls().target.toArray(), clicks,
        focused: document.activeElement?.dataset.nodeKey,
        hovered: host.querySelector('[data-node-key="button-near"]')?.matches(':hover') };
    };
    window.labelDepthFixture = { renderer, graph, host, poses, states, update, camera, inspect };
    update();
    renderer.enterSystem('system', { immediate: true });
    camera();
  });
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
  for (const property of ['hidden', 'opacity', 'blur', 'z', 'signal']) {
    expect(interactive[property], `${key} ${property}`).toEqual(ordinary[property]);
  }
}
async function evidence(page, name) {
  if (!process.env.WEB_REVIEW_DIR) return;
  fs.mkdirSync(process.env.WEB_REVIEW_DIR, { recursive: true });
  await page.locator('.network-visualizer-stage').screenshot({
    path: path.join(process.env.WEB_REVIEW_DIR, `${name}.png`) });
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
    expect(focused.labels['button-near'].opacity).toBe(1);
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

test('held label freezes only its position while live depth and visibility keep updating', async ({ page }) => {
  await openLabels(page);
  await press(page);
  const held = await state(page);
  await page.evaluate(() => {
    const f = window.labelDepthFixture;
    f.poses.near = { x: 40, y: -20, z: -120 }; f.update();
  });
  await tick(page);
  const deeper = await state(page);
  expectPair(deeper.labels, 'near');
  expect(deeper.labels['button-near'].position).toBe(held.labels['button-near'].position);
  expect(deeper.labels['button-near'].opacity).toBeLessThan(held.labels['button-near'].opacity);
  expect(deeper.labels['button-near'].blur).toBeGreaterThan(held.labels['button-near'].blur);
  expect(deeper.labels['button-near'].z).toBeLessThan(held.labels['button-near'].z);
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
