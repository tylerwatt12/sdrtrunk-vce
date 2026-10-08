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

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`radio transitions retain rendered motion through activity updates ${theme} ${width}px`, async ({ page }) => {
    await openRenderer(page, theme, width, false, true);
    await page.evaluate(() => {
      const fixture = window.visualizerFixture;
      fixture.initialMesh = fixture.mesh();
      fixture.startedAt = performance.now();
      fixture.renderer.setData(fixture.data(140, 'call'));
    });
    // Drive the actual renderer frames on an exact clock; wall-clock sleeps race GPU warm-up in CI.
    await page.clock.runFor(250);
    const midpoint = await page.evaluate(() => window.visualizerFixture.mesh().position.x);
    expect(midpoint).toBeGreaterThan(0);
    expect(midpoint).toBeLessThan(140);

    // Expiry repaint and unchanged stream updates must leave the original tween deadline intact.
    await page.evaluate(() => {
      const fixture = window.visualizerFixture;
      fixture.renderer.setData(fixture.data(140), { animate: false });
    });
    for (let update = 0; update < 4; update += 1) {
      await page.clock.runFor(180);
      await page.evaluate(() => {
        const fixture = window.visualizerFixture;
        fixture.renderer.setData(fixture.data(140));
      });
    }
    await page.clock.runFor(180);
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
    await page.clock.resume();
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

async function affiliationScene(page) {
  await page.evaluate(() => {
    const fixture = window.visualizerFixture;
    const system = { id: 'system', systemKey: 'system', type: 'system', label: 'Metro Public Safety',
      x: 0, y: 0, z: 0, radius: 300 };
    const groups = [
      { id: 'A', label: 'Dispatch', x: -100, y: 20, z: 0 },
      { id: 'B', label: 'Tactical', x: 100, y: 20, z: 0 },
      { id: 'C', label: 'Operations', x: 0, y: -80, z: 0 }
    ].map((group) => ({ ...group, type: 'talkgroup', systemKey: 'system', radius: 13 }));
    const poses = Object.fromEntries(groups.map((group) => [group.id,
      { x: group.x + 18, y: group.y, z: group.z + 10 }]));
    fixture.affiliationData = (groupKey, visible = ['A', 'B', 'C']) => ({
      nodes: [system, ...groups.filter((group) => visible.includes(group.id)), {
        id: 'radio-7001', systemKey: 'system', type: 'radio', label: 'Engine 4', radius: 6.5,
        ...poses[groupKey], groupKey, affiliationGroupKey: groupKey, signalAction: 'movement'
      }], links: [{ id: `current:radio-7001:${groupKey}`, source: 'radio-7001', target: groupKey,
        kind: 'current' }, ...(groupKey === 'C' ? ['A', 'B'] : groupKey === 'B' ? ['A'] : []).map((key) =>
        ({ id: `history:radio-7001:${key}`, source: 'radio-7001', target: key, kind: 'history' }))] });
    fixture.changes = [['A', 'B'], ['B', 'C']].map(([fromGroupKey, toGroupKey], index) => ({
      id: `move-${index}`, radioKey: 'radio-7001', systemKey: 'system', fromGroupKey, toGroupKey,
      from: poses[fromGroupKey], target: poses[toGroupKey], remainingMs: 8_000
    }));
    fixture.renderer.setData(fixture.affiliationData('A'), { animate: false });
    fixture.renderer.enterSystem('system', { immediate: true });
    fixture.renderer.setMode('auto');
    fixture.renderer.setCameraPhase('focus');
    fixture.capture = () => {
      const point = (value) => ({ x: value.x, y: value.y, z: value.z });
      return { at: performance.now() - fixture.startedAt, radio: point(fixture.mesh().position),
        data: point(fixture.graph.graphData().nodes.find((node) => node.id === 'radio-7001')),
        camera: point(fixture.graph.camera().position), target: point(fixture.graph.controls().target),
        attached: fixture.graph.graphData().links.filter((link) => link.kind === 'current').map((link) =>
          typeof link.target === 'object' ? link.target.id : link.target),
        history: fixture.graph.graphData().links.filter((link) => link.kind === 'history').map((link) =>
          typeof link.target === 'object' ? link.target.id : link.target) };
    };
    fixture.samples = [];
    fixture.record = () => {
      fixture.samples.push(fixture.capture());
      fixture.recording = requestAnimationFrame(fixture.record);
    };
    fixture.stop = () => {
      cancelAnimationFrame(fixture.recording);
      fixture.renderer.dispose();
    };
  });
  await expect.poll(() => page.evaluate(() => window.visualizerFixture.mesh().position.x)).toBe(-82);
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`affiliation focus shows the old attachment, follows FIFO moves, then attaches ${theme} ${width}px`,
    async ({ page }) => {
      await openRenderer(page, theme, width);
      await affiliationScene(page);
      await page.evaluate(() => {
        const fixture = window.visualizerFixture;
        fixture.startedAt = performance.now();
        fixture.renderer.setData(fixture.affiliationData('C'), { affiliationChanges: fixture.changes });
        fixture.focused = fixture.renderer.focusAffiliation('radio-7001', 900);
        fixture.record();
      });
      expect(await page.evaluate(() => window.visualizerFixture.focused)).toBe(true);
      await page.waitForTimeout(600);
      await page.evaluate(() => {
        const fixture = window.visualizerFixture;
        fixture.renderer.setData(fixture.affiliationData('C'), { animate: false });
      });
      await expect.poll(() => page.evaluate(() => window.visualizerFixture.samples.some((sample) =>
        sample.attached[0] === 'B')), { timeout: 3_500 }).toBe(true);
      await expect.poll(() => page.evaluate(() => window.visualizerFixture.capture().attached[0]),
        { timeout: 2_500 }).toBe('C');
      const result = await page.evaluate(() => {
        const fixture = window.visualizerFixture;
        cancelAnimationFrame(fixture.recording);
        return { samples: fixture.samples, final: fixture.capture(), refreshes: fixture.refreshes() };
      });
      const old = result.samples.find((sample) => sample.at >= 950 && sample.at < 1_070);
      expect(old).toBeTruthy();
      expect(old.radio.x).toBeCloseTo(-82, 1);
      expect(old.attached).toEqual(['A']);
      expect(old.history).toEqual([], 'queued destination history links remain hidden before arrival');
      expect(old.target.x).toBeCloseTo(-91, 0);
      const travelling = result.samples.find((sample) => sample.at > 1_300 && sample.at < 1_800 &&
        sample.radio.x > -70 && sample.radio.x < 100 && sample.attached.length === 0);
      expect(travelling).toBeTruthy();
      expect(travelling.history).toEqual(['A']);
      expect(travelling.camera.x - old.camera.x).toBeCloseTo(travelling.data.x - old.data.x, 0);
      expect(travelling.target.x - old.target.x).toBeCloseTo(travelling.data.x - old.data.x, 0);
      const firstArrival = result.samples.findIndex((sample) => sample.attached[0] === 'B');
      const finalArrival = result.samples.findIndex((sample) => sample.attached[0] === 'C');
      expect(firstArrival).toBeGreaterThan(0);
      expect(finalArrival).toBeGreaterThan(firstArrival);
      expect(result.samples[firstArrival].data.x).toBeCloseTo(118, 1);
      expect(result.final.radio.x).toBeCloseTo(18, 1);
      expect(result.final.radio.y).toBeCloseTo(-80, 1);
      expect(result.final.attached).toEqual(['C']);
      expect(result.refreshes).toBe(0);
      await expect(page.locator('.network-visualizer-stage')).toHaveScreenshot(
        `network-visualizer-affiliation-${theme}-${width}.png`);
      await page.evaluate(() => window.visualizerFixture.stop());
      await expect(page.locator('.network-visualizer-canvas canvas')).toHaveCount(0);
    });
}

test('manual camera and scope changes cancel affiliation follow without dangling links', async ({ page }) => {
  await openRenderer(page, 'light', 1280);
  await affiliationScene(page);
  await page.evaluate(() => {
    const fixture = window.visualizerFixture;
    fixture.startedAt = performance.now();
    fixture.renderer.setData(fixture.affiliationData('C'), { affiliationChanges: fixture.changes });
    fixture.renderer.focusAffiliation('radio-7001');
    fixture.renderer.setMode('manual');
    fixture.beforeManual = fixture.capture();
  });
  await page.waitForTimeout(250);
  const manual = await page.evaluate(() => ({ before: window.visualizerFixture.beforeManual,
    after: window.visualizerFixture.capture() }));
  expect(manual.after.attached).toEqual(['C']);
  expect(manual.after.data.x).toBe(18);
  expect(manual.after.target).toEqual(manual.before.target);
  await page.evaluate(() => {
    const fixture = window.visualizerFixture;
    fixture.renderer.setMode('auto');
    fixture.renderer.setCameraPhase('focus');
    fixture.renderer.setData(fixture.affiliationData('A'), { animate: false });
    fixture.renderer.setData(fixture.affiliationData('C'), { affiliationChanges: fixture.changes });
    fixture.renderer.focusAffiliation('radio-7001');
    const scoped = fixture.affiliationData('A', ['A']);
    scoped.nodes.at(-1).affiliationGroupKey = 'C';
    scoped.links = [{ id: 'previous', source: 'radio-7001', target: 'A', kind: 'history' }];
    fixture.renderer.setData(scoped, { animate: false });
    fixture.renderer.enterTalkgroup('system', 'A', { immediate: true });
  });
  await page.waitForTimeout(250);
  const scoped = await page.evaluate(() => {
    const fixture = window.visualizerFixture;
    const graph = fixture.graph.graphData();
    const ids = new Set(graph.nodes.map((node) => node.id));
    return { sample: fixture.capture(), allEndpointsPresent: graph.links.every((link) =>
      [link.source, link.target].every((node) => ids.has(typeof node === 'object' ? node.id : node))) };
  });
  expect(scoped.sample.radio.x).toBe(-82);
  expect(scoped.sample.attached).toEqual([]);
  expect(scoped.allEndpointsPresent).toBe(true);
  await page.evaluate(() => window.visualizerFixture.stop());
});

test('reduced motion and expired queued evidence settle once without replay', async ({ page }) => {
  await openRenderer(page, 'dark', 390, true);
  await affiliationScene(page);
  await page.evaluate(() => {
    const fixture = window.visualizerFixture;
    fixture.startedAt = performance.now();
    fixture.renderer.setData(fixture.affiliationData('C'), { affiliationChanges: fixture.changes });
    fixture.focused = fixture.renderer.focusAffiliation('radio-7001');
  });
  expect(await page.evaluate(() => window.visualizerFixture.focused)).toBe(false);
  await expect.poll(() => page.evaluate(() => window.visualizerFixture.mesh().position.x)).toBe(18);
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await page.evaluate(() => {
    const fixture = window.visualizerFixture;
    fixture.renderer.setData(fixture.affiliationData('A'), { animate: false });
    fixture.renderer.setData(fixture.affiliationData('C'), { affiliationChanges:
      fixture.changes.map((change) => ({ ...change, remainingMs: 100 })) });
    fixture.renderer.focusAffiliation('radio-7001');
  });
  await page.waitForTimeout(300);
  const settled = await page.evaluate(() => window.visualizerFixture.capture());
  expect(settled.data.x).toBe(18);
  expect(settled.attached).toEqual(['C']);
  await page.waitForTimeout(150);
  expect(await page.evaluate(() => window.visualizerFixture.mesh().position.x)).toBe(18);
  await page.evaluate(() => window.visualizerFixture.stop());
});

test('new canonical evidence cancels an obsolete waiting or travelling affiliation', async ({ page }) => {
  await openRenderer(page, 'light', 1280);
  await affiliationScene(page);
  for (const [destination, delay] of [['', 100], ['C', 500]]) {
    await page.evaluate(() => {
      const fixture = window.visualizerFixture;
      fixture.startedAt = performance.now();
      fixture.renderer.setData(fixture.affiliationData('A'), { animate: false });
      fixture.renderer.setData(fixture.affiliationData('B'), { affiliationChanges: [fixture.changes[0]] });
    });
    await page.waitForTimeout(delay);
    await page.evaluate((current) => {
      const fixture = window.visualizerFixture;
      const corrected = fixture.affiliationData(current || 'C');
      corrected.nodes.at(-1).affiliationGroupKey = current;
      if (!current) corrected.links = [];
      fixture.renderer.setData(corrected);
    }, destination);
    await page.waitForTimeout(250);
    const corrected = await page.evaluate(() => window.visualizerFixture.capture());
    expect(corrected.data.x).toBe(18);
    expect(corrected.attached).toEqual(destination ? ['C'] : []);
    await page.waitForTimeout(900);
    expect((await page.evaluate(() => window.visualizerFixture.capture())).attached)
      .toEqual(destination ? ['C'] : []);
  }
  await page.evaluate(() => window.visualizerFixture.stop());
});

test('later moves of the focused radio continue tracking until manual interaction', async ({ page }) => {
  await openRenderer(page, 'dark', 390);
  await affiliationScene(page);
  await page.evaluate(() => {
    const fixture = window.visualizerFixture;
    fixture.startedAt = performance.now();
    fixture.renderer.setData(fixture.affiliationData('B'), { affiliationChanges: [fixture.changes[0]] });
    fixture.renderer.focusAffiliation('radio-7001', 120);
  });
  await expect.poll(() => page.evaluate(() => window.visualizerFixture.capture().attached[0]),
    { timeout: 2_500 }).toBe('B');
  await page.evaluate(() => {
    const fixture = window.visualizerFixture;
    fixture.renderer.setCameraPhase('hold');
    fixture.beforeLater = fixture.capture();
    fixture.renderer.setData(fixture.affiliationData('C'), { affiliationChanges: [fixture.changes[1]] });
  });
  await page.waitForTimeout(600);
  const tracking = await page.evaluate(() => ({ before: window.visualizerFixture.beforeLater,
    after: window.visualizerFixture.capture() }));
  expect(tracking.after.data.x).toBeLessThan(118);
  expect(tracking.after.data.x).toBeGreaterThan(18);
  expect(tracking.after.target.x - tracking.before.target.x)
    .toBeCloseTo(tracking.after.data.x - tracking.before.data.x, 0);
  await page.evaluate(() => {
    const fixture = window.visualizerFixture;
    fixture.graph.controls().dispatchEvent({ type: 'start' });
    fixture.afterInteraction = fixture.capture();
  });
  await expect.poll(() => page.evaluate(() => window.visualizerFixture.capture().attached[0])).toBe('C');
  const interrupted = await page.evaluate(() => ({ before: window.visualizerFixture.afterInteraction,
    after: window.visualizerFixture.capture() }));
  for (const axis of ['x', 'y', 'z']) {
    expect(interrupted.after.target[axis]).toBeCloseTo(interrupted.before.target[axis], 5);
  }
  await page.evaluate(() => window.visualizerFixture.stop());
});

test('fresh Manual updates move the radio and attach on arrival while the camera stays put', async ({ page }) => {
  await openRenderer(page, 'light', 1280);
  await affiliationScene(page);
  await page.evaluate(() => {
    const fixture = window.visualizerFixture;
    fixture.startedAt = performance.now();
    fixture.renderer.setMode('manual');
    fixture.beforeManual = fixture.capture();
    fixture.renderer.setData(fixture.affiliationData('B'), { affiliationChanges: [fixture.changes[0]] });
    fixture.focused = fixture.renderer.focusAffiliation('radio-7001');
  });
  expect(await page.evaluate(() => window.visualizerFixture.focused)).toBe(false);
  await page.waitForTimeout(600);
  const moving = await page.evaluate(() => ({ before: window.visualizerFixture.beforeManual,
    after: window.visualizerFixture.capture() }));
  expect(moving.after.radio.x).toBeGreaterThan(-82);
  expect(moving.after.radio.x).toBeLessThan(118);
  expect(moving.after.attached).toEqual([]);
  expect(moving.after.target).toEqual(moving.before.target);
  await expect.poll(() => page.evaluate(() => window.visualizerFixture.capture().attached[0])).toBe('B');
  await page.evaluate(() => window.visualizerFixture.stop());
});

test('a response arriving after the view becomes hidden updates state without queued replay', async ({ page }) => {
  await openRenderer(page, 'light', 1280);
  await affiliationScene(page);
  await page.evaluate(() => {
    const fixture = window.visualizerFixture;
    fixture.startedAt = performance.now();
    fixture.renderer.setData(fixture.affiliationData('B'), { affiliationChanges: [fixture.changes[0]] });
    fixture.renderer.focusAffiliation('radio-7001');
    fixture.hidden = true;
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => fixture.hidden });
    document.dispatchEvent(new Event('visibilitychange'));
    fixture.beforeHidden = fixture.capture();
    fixture.renderer.setData(fixture.affiliationData('C'), { affiliationChanges: [fixture.changes[1]] });
    fixture.focused = fixture.renderer.focusAffiliation('radio-7001');
    fixture.genericFocus = fixture.renderer.focus(['radio-7001', 'C']);
  });
  expect(await page.evaluate(() => window.visualizerFixture.focused)).toBe(false);
  expect(await page.evaluate(() => window.visualizerFixture.genericFocus)).toBe(false);
  expect((await page.evaluate(() => window.visualizerFixture.capture())).data.x).toBe(18);
  await page.evaluate(() => {
    window.visualizerFixture.hidden = false;
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await page.waitForTimeout(300);
  const resumed = await page.evaluate(() => ({ before: window.visualizerFixture.beforeHidden,
    after: window.visualizerFixture.capture() }));
  expect(resumed.after.radio.x).toBe(18);
  expect(resumed.after.attached).toEqual(['C']);
  expect(resumed.after.target).toEqual(resumed.before.target);
  await page.evaluate(() => { delete document.hidden; window.visualizerFixture.stop(); });
});
