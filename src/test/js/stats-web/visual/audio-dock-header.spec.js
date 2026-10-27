'use strict';

const { expect, test } = require('@playwright/test');
const { selectAudioPanel, openAudioVolume } = require('./audio-dock-controls.cjs');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');
const { dockSourceControl, selectDockSource } = require('./fixtures/audio-dock-source.cjs');

const viewports = [{ width: 1280, height: 900 }, { width: 320, height: 740 }, { width: 390, height: 844 }];
const handle = (dock) => dock.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible');

async function setSize(dock, state) {
  if (state === 'collapsed') await handle(dock).press('Home');
  else if (state === 'full') await handle(dock).press('End');
  else { await handle(dock).press('Home'); await handle(dock).press('ArrowUp'); }
  await expect(dock).toHaveAttribute('data-state', state);
}

async function openGallery(page, source, state = 'full', stress = false) {
  await page.clock.setFixedTime( new Date('2026-09-30T14:24:18Z'));
  if (stress) {
    // Only the gallery's synthetic call data changes. The mounted production
    // component and all playback/size interactions remain unchanged.
    await page.route('**/visual/audio-dock-gallery.js', async (route) => {
      const response = await route.fetch();
      const original = await response.text();
      expect(original).toContain('queuedCount: empty ? 0 : 2');
      expect(original).toContain('[2, 3, 4].map');
      const body = original.replace('queuedCount: empty ? 0 : 2', 'queuedCount: empty ? 0 : 1234')
        .replace('[2, 3, 4].map', 'Array.from({ length: 1234 }, (_, index) => index + 2).map');
      await route.fulfill({ response, body });
    });
  }
  await page.goto(`/design-system.html?${new URLSearchParams({ view: 'audio-dock', audioSource: source,
    audioState: state, audioFixture: stress ? 'long' : 'normal' })}`);
  await expect(page.locator('.visual-audio-dock-example')).toHaveAttribute('data-ready', 'true');
  const dock = page.locator('#audio-dock');
  await expect(dock).toHaveAttribute('data-source', source);
  await expect(dock).toHaveAttribute('data-state', state);
  return dock;
}

async function expectSingleRow(dock, state, source, queued, title) {
  const row = dock.locator(state === 'collapsed' ? '.audio-dock-slim' : '.audio-dock-header');
  const resize = handle(dock);
  const left = state === 'collapsed' ? row.locator('.audio-dock-slim-copy') : await dockSourceControl(dock);
  const right = state === 'collapsed' ? resize : row.locator('.audio-dock-header-end');
  await expect(resize).toHaveCount(1);
  await expect(left).toBeVisible();
  await expect(right).toBeVisible();
  if (state === 'collapsed') {
    await expect(row.locator('.audio-dock-single-line')).toHaveText(title);
    await expect(row.locator('.audio-dock-single-line')).toHaveAttribute('data-ui-hint', title);
    await expect(row.locator('.audio-dock-collapsed-meta')).toHaveText(`${source === 'live' ? 'Live' : 'Recording'} · ${queued} queued`);
    await expect(dock.getByRole('group', { name: 'Audio source', exact: true })).toBeHidden();
    await expect(dock.getByRole('combobox', { name: 'Audio source', exact: true })).toBeHidden();
    await expect(dock.getByRole('slider')).toHaveCount(0);
  } else {
    await expect(row.locator('[data-volume-trigger]')).toHaveCount(0);
    if (state === 'minimal') await expect(dock.locator('.audio-dock-queue-count')).toContainText(`${queued} queued`);
    else await expect(dock.locator('.audio-dock-up-next')).toContainText(`${queued} queued`);
  }
  const [d, h, l, r, control] = await Promise.all([
    dock.boundingBox(), row.boundingBox(), left.boundingBox(), right.boundingBox(), resize.boundingBox(),
  ]);
  for (const bounds of [h, l, r, control]) {
    expect(bounds).not.toBeNull();
    expect(bounds.width).toBeGreaterThan(0);
    expect(bounds.height).toBeGreaterThan(0);
    expect(bounds.x).toBeGreaterThanOrEqual(h.x - 1);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(h.x + h.width + 1);
  }
  expect(l.x + l.width).toBeLessThanOrEqual(control.x + 1);
  if (state !== 'collapsed') {
    const g = await row.locator('.ui-audio-grip').boundingBox();
    expect(Math.abs(g.x + g.width / 2 - (d.x + d.width / 2))).toBeLessThanOrEqual(1.5);
    expect(Math.abs(g.y + g.height / 2 - (l.y + l.height / 2))).toBeLessThanOrEqual(2);
    expect(Math.abs(g.y + g.height / 2 - (r.y + r.height / 2))).toBeLessThanOrEqual(2);
    expect(control.x + control.width).toBeLessThanOrEqual(r.x + 1);
  }
  expect(await row.evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);
  const reachable = await resize.evaluate((element) => {
    const r = element.getBoundingClientRect();
    const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    return Boolean(hit && element.contains(hit));
  });
  expect(reachable).toBe(true);
}

for (const viewport of viewports) {
  for (const source of ['live', 'recordings']) {
    test(`${source} player keeps identity, source and resize controls reachable at ${viewport.width}px`, async ({ page }) => {
      await page.setViewportSize(viewport);
      const dock = await openGallery(page, source);
      for (const state of ['collapsed', 'minimal', 'full']) {
        await setSize(dock, state);
        await expectSingleRow(dock, state, source, source === 'live' ? 2 : 3, 'Fire Dispatch');
      }
    });

    test(`long ${source} title and four-digit queue keep the resize control clear at ${viewport.width}px`, async ({ page }) => {
      await page.setViewportSize(viewport);
      const dock = await openGallery(page, source, 'collapsed', true);
      for (const state of ['collapsed', 'minimal', 'full']) {
        await setSize(dock, state);
        await expectSingleRow(dock, state, source, 1234,
          'Metropolitan Emergency Communications County Public Safety Dispatch');
      }
    });
  }

  test(`center grip follows the pointer and retains click, snap and keyboard size changes at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const dock = await openGallery(page, 'live', 'collapsed');
    for (const state of ['minimal', 'full', 'collapsed']) {
      await handle(dock).click();
      await expect(dock).toHaveAttribute('data-state', state);
    }
    const heights = {};
    for (const state of ['collapsed', 'minimal', 'full']) {
      await setSize(dock, state);
      heights[state] = (await dock.boundingBox()).height;
    }
    await setSize(dock, 'collapsed');
    const drag = async (target, expected) => {
      const before = await dock.boundingBox();
      const bounds = await handle(dock).boundingBox();
      const x = bounds.x + bounds.width / 2, y = bounds.y + bounds.height / 2;
      await page.mouse.move(x, y);
      await page.mouse.down();
      const dy = before.height - target;
      for (const fraction of [0.5, 1]) {
        await page.mouse.move(x, y + dy * fraction, { steps: 3 });
        await expect.poll(async () => Math.abs((await dock.boundingBox()).height -
          (before.height - dy * fraction))).toBeLessThanOrEqual(2);
        const during = await dock.boundingBox();
        expect(during.x).toBeGreaterThanOrEqual(0);
        expect(during.x + during.width).toBeLessThanOrEqual(viewport.width);
        expect(during.y).toBeGreaterThanOrEqual(0);
        expect(Math.abs(during.y + during.height - before.y - before.height)).toBeLessThanOrEqual(1);
      }
      await page.mouse.up();
      await expect(dock).toHaveAttribute('data-state', expected);
      await expect.poll(async () => Math.abs((await dock.boundingBox()).height - heights[expected]))
        .toBeLessThanOrEqual(1);
      await expect(dock).not.toHaveAttribute('data-dragging', 'true');
    };
    await drag(heights.minimal, 'minimal');
    await drag(heights.full, 'full');
    await drag(heights.minimal, 'minimal');
    // Releasing closer to the compact size returns there even after a visible resize.
    await drag(heights.minimal + (heights.full - heights.minimal) * 0.35, 'minimal');
    await handle(dock).press('Home');
    await expect(dock).toHaveAttribute('data-state', 'collapsed');
    await handle(dock).press('End');
    await expect(dock).toHaveAttribute('data-state', 'full');
    await expect(handle(dock)).toBeFocused();
  });

  test(`source and queue actions do not activate the center grip at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openAudioApp(page, { view: 'dashboard', feedCallsEnabled: false });
    const dock = page.locator('#audio-dock');
    await setSize(dock, 'full');
    await selectDockSource(dock, 'recordings');
    await expect(page).toHaveURL(/view=recordings/);
    await expect(dock).toHaveAttribute('data-source', 'recordings');
    await expect(dock).toHaveAttribute('data-state', 'full');
    await selectAudioPanel(dock, 'Queue');
    await expect(dock.locator('#audio-dock-context-title')).toHaveText('Queue');
    await expect(dock).toHaveAttribute('data-state', 'full');
    await selectDockSource(dock, 'live');
    await expect(dock).toHaveAttribute('data-source', 'live');
    await expect(dock).toHaveAttribute('data-state', 'full');
  });
}
