'use strict';

const { test, expect } = require('@playwright/test');

async function openRenderer(page, theme, width, reducedMotion = false, controlledClock = false) {
  if (controlledClock) {
    await page.clock.install({ time: new Date('2026-10-01T12:00:00Z') });
    await page.clock.pauseAt(new Date('2026-10-01T12:00:01Z'));
  }
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
  if (controlledClock) await page.clock.runFor(100);
  await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
  await expect.poll(() => page.evaluate(() => !!window.visualizerFixture.mesh())).toBe(true);
}

async function affiliationScene(page, includeRadio = true) {
  await page.evaluate((withRadio) => {
    const fixture = window.visualizerFixture;
    const system = { id: 'system', systemKey: 'system', type: 'system', label: 'Metro Public Safety',
      x: 0, y: 0, z: 0, radius: 300 };
    const groups = [{ id: 'A', label: 'Dispatch', x: -100, y: 20, z: 0 },
      { id: 'B', label: 'Tactical', x: 100, y: 20, z: 0 },
      { id: 'C', label: 'Operations', x: 0, y: -80, z: 0 }]
      .map((group) => ({ ...group, type: 'talkgroup', systemKey: 'system', radius: 13 }));
    const poses = Object.fromEntries(groups.map((group) => [group.id,
      { x: group.x + 18, y: group.y, z: group.z + 10 }]));
    fixture.dataAt = (groupKey, withRadio = true) => ({ nodes: [system, ...groups, ...(withRadio ? [{
      id: 'radio-7001', systemKey: 'system', type: 'radio', label: 'Engine 4', radius: 6.5,
      ...poses[groupKey], groupKey, affiliationGroupKey: groupKey, signalAction: 'movement'
    }] : [])], links: withRadio ? [{ id: `current:radio-7001:${groupKey}`, source: 'radio-7001',
      target: groupKey, kind: 'current' }] : [] });
    fixture.change = (fromGroupKey, toGroupKey, id = `${fromGroupKey}-${toGroupKey}`) => ({ id,
      radioKey: 'radio-7001', fromGroupKey, toGroupKey, from: poses[fromGroupKey],
      target: poses[toGroupKey], remainingMs: 8_000 });
    fixture.renderer.setData(fixture.dataAt('A', withRadio), { animate: false });
    fixture.renderer.enterSystem('system', { immediate: true });
    fixture.renderer.setMode('auto');
    fixture.renderer.setCameraPhase('focus');
    fixture.inspect = () => {
      const graph = fixture.graph.graphData(), camera = fixture.graph.camera();
      const point = (value) => ({ x: value.x, y: value.y, z: value.z });
      const cubes = {};
      fixture.graph.scene().traverse((object) => {
        if (['A', 'B', 'C'].includes(object.userData?.p25Node?.id)) {
          cubes[object.userData.p25Node.id] = { opacity: object.material.opacity, fog: object.material.fog };
        }
      });
      return { radio: point(graph.nodes.find((node) => node.id === 'radio-7001') || {}),
        target: point(fixture.graph.controls().target), camera: point(camera.position), cubes,
        projected: Object.fromEntries(['A', 'B'].map((key) => {
          const node = graph.nodes.find((value) => value.id === key);
          const projected = fixture.graph.graph2ScreenCoords(node.x, node.y, node.z);
          return [key, projected];
        })), width: fixture.graph.width(), height: fixture.graph.height(),
        rotating: fixture.graph.controls().autoRotate,
        movementLinks: graph.links.filter(link => link.id.startsWith('movement:')).length,
        endpoints: graph.links.every((link) => [link.source, link.target].every((value) =>
          graph.nodes.some((node) => node.id === (typeof value === 'object' ? value.id : value)))) };
    };
  }, includeRadio);
  await page.clock.runFor(100);
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`both affiliation cubes stay clear and framed throughout movement ${theme} ${width}px`, async ({ page }) => {
    await openRenderer(page, theme, width, false, true);
    await affiliationScene(page);
    await page.evaluate(() => {
      const f = window.visualizerFixture;
      f.renderer.setData(f.dataAt('B'), { affiliationChanges: [f.change('A', 'B')] });
      f.renderer.focusAffiliation('radio-7001', 0);
      f.framed = f.inspect();
    });
    await page.clock.runFor(400);
    const during = await page.evaluate(() => ({ before: window.visualizerFixture.framed,
      after: window.visualizerFixture.inspect() }));
    expect(during.after.radio.x).toBeGreaterThan(-82);
    expect(during.after.radio.x).toBeLessThan(118);
    for (const axis of ['x', 'y', 'z']) {
      expect(during.after.target[axis]).toBeCloseTo(during.before.target[axis], 8);
      expect(during.after.camera[axis]).toBeCloseTo(during.before.camera[axis], 8);
    }
    for (const key of ['A', 'B']) {
      expect(during.after.cubes[key].opacity).toBeGreaterThan(0.9);
      expect(during.after.cubes[key].fog).toBe(false);
      expect(during.after.projected[key].x).toBeGreaterThan(15);
      expect(during.after.projected[key].x).toBeLessThan(during.after.width - 15);
      expect(during.after.projected[key].y).toBeGreaterThan(15);
      expect(during.after.projected[key].y).toBeLessThan(during.after.height - 15);
    }
    await page.locator('.network-visualizer-stage').screenshot({ path: test.info().outputPath('journey.png') });
    await page.evaluate(() => {
      const f = window.visualizerFixture;
      f.renderer.setData(f.dataAt('B'), { animate: false });
    });
    await page.clock.runFor(800);
    expect((await page.evaluate(() => window.visualizerFixture.inspect())).radio.x).toBe(118);
    await page.evaluate(() => {
      const f = window.visualizerFixture;
      f.renderer.setCameraPhase('return'); f.renderer.home(0); f.renderer.setCameraPhase('roam');
    });
    await page.clock.runFor(300);
    const idle = await page.evaluate(() => window.visualizerFixture.inspect());
    expect(idle.rotating).toBe(true);
    expect(idle.cubes.A.opacity).toBeGreaterThan(0.7);
    expect(idle.cubes.B.opacity).toBeGreaterThan(0.7);
    await page.evaluate(() => window.visualizerFixture.renderer.dispose());
    await expect(page.locator('.network-visualizer-canvas canvas')).toHaveCount(0);
  });
}

test('first-observed radio moves from a known previous cube without a preloaded radio', async ({ page }) => {
  await openRenderer(page, 'light', 1280, false, true);
  await affiliationScene(page, false);
  await page.evaluate(() => {
    const f = window.visualizerFixture;
    f.renderer.setData(f.dataAt('B'), { affiliationChanges: [f.change('A', 'B')] });
  });
  await page.clock.runFor(300);
  const moving = await page.evaluate(() => window.visualizerFixture.inspect());
  expect(moving.radio.x).toBeGreaterThan(-82);
  expect(moving.radio.x).toBeLessThan(118);
  expect(moving.cubes.A.opacity).toBeGreaterThan(0.9);
  expect(moving.cubes.B.opacity).toBeGreaterThan(0.9);
  await page.evaluate(() => window.visualizerFixture.renderer.dispose());
});

test('newer affiliation retargets from its current position without replaying a backlog', async ({ page }) => {
  await openRenderer(page, 'dark', 390, true, true);
  await affiliationScene(page);
  await page.evaluate(() => {
    const f = window.visualizerFixture;
    f.renderer.setData(f.dataAt('B'), { affiliationChanges: [f.change('A', 'B')] });
  });
  await page.clock.runFor(400);
  await page.evaluate(() => {
    const f = window.visualizerFixture;
    f.before = f.inspect();
    f.renderer.setData(f.dataAt('C'), { affiliationChanges: [f.change('B', 'C')] });
    f.after = f.inspect();
  });
  const retarget = await page.evaluate(() => ({ before: window.visualizerFixture.before,
    after: window.visualizerFixture.after }));
  expect(retarget.after.radio).toEqual(retarget.before.radio);
  await page.clock.runFor(1_200);
  const settled = await page.evaluate(() => window.visualizerFixture.inspect());
  expect(settled.radio.x).toBe(18);
  expect(settled.radio.y).toBe(-80);
  expect(settled.endpoints).toBe(true);
  await page.evaluate(() => window.visualizerFixture.renderer.dispose());
});

test('reset restores visibility and rotation and Manual movement leaves the camera alone', async ({ page }) => {
  await openRenderer(page, 'light', 1280, false, true);
  await affiliationScene(page);
  await page.evaluate(() => {
    const f = window.visualizerFixture;
    f.renderer.setData(f.dataAt('B'), { affiliationChanges: [f.change('A', 'B')] });
    f.renderer.focusAffiliation('radio-7001', 0);
  });
  await page.clock.runFor(300);
  await page.evaluate(() => window.visualizerFixture.renderer.resetAttention());
  await page.clock.runFor(100);
  const reset = await page.evaluate(() => window.visualizerFixture.inspect());
  expect(reset.radio.x).toBe(118);
  expect(reset.rotating).toBe(true);
  expect(reset.movementLinks).toBe(0);
  expect(reset.cubes.C.opacity).toBeGreaterThan(0.7);
  await page.evaluate(() => {
    const f = window.visualizerFixture;
    f.renderer.setMode('manual'); f.beforeManual = f.inspect();
    f.renderer.setData(f.dataAt('C'), { affiliationChanges: [f.change('B', 'C')] });
  });
  await page.clock.runFor(500);
  const manual = await page.evaluate(() => ({ before: window.visualizerFixture.beforeManual,
    after: window.visualizerFixture.inspect() }));
  expect(manual.after.radio.x).toBeGreaterThan(18);
  expect(manual.after.radio.x).toBeLessThan(118);
  expect(manual.after.camera).toEqual(manual.before.camera);
  expect(manual.after.target).toEqual(manual.before.target);
  await page.evaluate(() => window.visualizerFixture.renderer.dispose());
});

test('ordinary activity repaints reuse the mesh without inventing flight', async ({ page }) => {
  await openRenderer(page, 'dark', 390, false, true);
  await page.evaluate(() => {
    const f = window.visualizerFixture; f.initialMesh = f.mesh();
    f.renderer.setData(f.data(140, 'call'));
  });
  await page.clock.runFor(100);
  expect(await page.evaluate(() => window.visualizerFixture.mesh().position.x)).toBe(140);
  await page.evaluate(() => window.visualizerFixture.renderer.setData(window.visualizerFixture.data(140)));
  await page.clock.runFor(300);
  const repeated = await page.evaluate(() => ({ same: window.visualizerFixture.mesh() ===
    window.visualizerFixture.initialMesh, x: window.visualizerFixture.mesh().position.x,
    refreshes: window.visualizerFixture.refreshes() }));
  expect(repeated).toEqual({ same: true, x: 140, refreshes: 0 });
  await page.evaluate(() => window.visualizerFixture.renderer.dispose());
});

test('hidden updates retain current data without rendering and resume without stale motion or fading', async ({ page }) => {
  await openRenderer(page, 'light', 1280, false, true);
  await affiliationScene(page);
  await page.evaluate(() => {
    const f = window.visualizerFixture;
    f.renderer.setData(f.dataAt('B'), { affiliationChanges: [f.change('A', 'B')] });
    f.renderer.focusAffiliation('radio-7001', 600);
  });
  await page.clock.runFor(100);
  await page.evaluate(() => {
    const f = window.visualizerFixture;
    f.work = { publications: 0, renders: 0, labels: 0, controls: 0 };
    const publish = f.graph.graphData;
    f.graph.graphData = (...args) => {
      if (args.length) f.work.publications += 1;
      return publish(...args);
    };
    const renderer = f.graph.renderer(), render = renderer.render;
    renderer.render = (...args) => { f.work.renders += 1; return render.apply(renderer, args); };
    const project = f.graph.graph2ScreenCoords;
    f.graph.graph2ScreenCoords = (...args) => { f.work.labels += 1; return project(...args); };
    const controls = f.graph.controls(), update = controls.update;
    controls.update = (...args) => { f.work.controls += 1; return update.apply(controls, args); };
    f.hidden = true;
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => f.hidden });
    document.dispatchEvent(new Event('visibilitychange'));
    // Journey framing can leave the camera outside the normal system envelope.
    f.graph.camera().position.set(900, 500, 600);
    f.hiddenBefore = { camera: f.inspect().camera, labels: document.querySelector('.network-visualizer-label-layer').innerHTML,
      opacity: f.mesh().material.opacity, work: { ...f.work } };
    f.renderer.setMode('manual');
    f.renderer.enterTalkgroup('system', 'B', { immediate: true });
    f.renderer.enterSystem('system', { immediate: true });
    f.renderer.moveManualCamera({ forward: 1 }, 0.05);
    f.renderer.setMode('auto');
    f.renderer.setData(f.dataAt('B'), { affiliationChanges: [f.change('A', 'B')] });
    f.renderer.setData(f.dataAt('C'), { affiliationChanges: [f.change('B', 'C')] });
  });
  await page.clock.runFor(5_000);
  const hidden = await page.evaluate(() => {
    const f = window.visualizerFixture;
    return { before: f.hiddenBefore, camera: { ...f.graph.camera().position },
      labels: document.querySelector('.network-visualizer-label-layer').innerHTML,
      opacity: f.mesh().material.opacity, work: f.work, rotating: f.graph.controls().autoRotate };
  });
  expect(hidden.work).toEqual(hidden.before.work);
  expect(hidden.camera).toEqual(hidden.before.camera);
  expect(hidden.labels).toBe(hidden.before.labels);
  expect(hidden.opacity).toBe(hidden.before.opacity);
  expect(hidden.rotating).toBe(false);
  await page.evaluate(() => {
    const f = window.visualizerFixture;
    f.hidden = false;
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await page.clock.runFor(300);
  const resumed = await page.evaluate(() => window.visualizerFixture.inspect());
  expect(resumed.radio.x).toBe(18);
  expect(resumed.radio.y).toBe(-80);
  expect(resumed.movementLinks).toBe(0);
  expect(resumed.rotating).toBe(true);
  expect(resumed.cubes.A.opacity).toBeGreaterThan(0.7);
  expect(await page.evaluate(() => window.visualizerFixture.work.publications)).toBe(1);
  await page.evaluate(() => { delete document.hidden; window.visualizerFixture.renderer.dispose(); });
});

test('live activity stays bright with optional call highlighting disabled', async ({ page }) => {
  await openRenderer(page, 'dark', 390, false, true);
  await page.evaluate(() => {
    const f = window.visualizerFixture;
    f.renderer.setData(f.data(0, 'call'));
    f.callColor = f.mesh().material.color.getHex();
    const active = f.data(0); active.nodes.at(-1).active = true;
    f.renderer.setData(active);
    f.activeColor = f.mesh().material.color.getHex();
    f.renderer.setData(f.data(0));
    f.idleColor = f.mesh().material.color.getHex();
  });
  const colors = await page.evaluate(() => ({ call: window.visualizerFixture.callColor,
    active: window.visualizerFixture.activeColor, idle: window.visualizerFixture.idleColor }));
  expect(colors.active).toBe(colors.call);
  expect(colors.idle).not.toBe(colors.call);
  await page.evaluate(() => window.visualizerFixture.renderer.dispose());
});

test('automatic movement starts after both endpoints have been framed', async ({ page }) => {
  await openRenderer(page, 'dark', 390, false, true);
  await affiliationScene(page);
  await page.evaluate(() => {
    const f = window.visualizerFixture;
    f.renderer.setData(f.dataAt('B'), { affiliationChanges: [f.change('A', 'B')] });
    f.renderer.focusAffiliation('radio-7001', 300);
  });
  await page.clock.runFor(200);
  expect((await page.evaluate(() => window.visualizerFixture.inspect())).radio.x).toBe(-82);
  await page.clock.runFor(300);
  const travelling = await page.evaluate(() => window.visualizerFixture.inspect());
  expect(travelling.radio.x).toBeGreaterThan(-82);
  expect(travelling.radio.x).toBeLessThan(118);
  for (const key of ['A', 'B']) {
    expect(travelling.projected[key].x).toBeGreaterThan(15);
    expect(travelling.projected[key].x).toBeLessThan(travelling.width - 15);
  }
  await page.clock.runFor(1_000);
  expect((await page.evaluate(() => window.visualizerFixture.inspect())).radio.x).toBe(118);
  await page.evaluate(() => window.visualizerFixture.renderer.dispose());
});
