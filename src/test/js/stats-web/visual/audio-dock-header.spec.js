'use strict';

const { expect, test } = require('@playwright/test');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');

const viewports = [{ width: 1280, height: 900 }, { width: 320, height: 740 }, { width: 390, height: 844 }];
const handle = (dock) => dock.getByRole('button', { name: 'Change audio player size', exact: true });

async function setSize(dock, state) {
  await handle(dock).press('Home');
  if (state === 'minimal') await handle(dock).press('ArrowUp');
  if (state === 'full') await handle(dock).press('End');
  await expect(dock).toHaveAttribute('data-state', state);
}

async function openGallery(page, source, state = 'full', stress = false) {
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
  const header = dock.locator('.audio-dock-header');
  const grip = header.locator('.ui-audio-grip');
  const left = state === 'collapsed' ? header.locator('.audio-dock-single-line') :
    header.getByRole('group', { name: 'Audio source', exact: true });
  const right = state === 'collapsed' ? header.locator('.audio-dock-meta') :
    header.getByRole('button', { name: `Queue ${queued}`, exact: true });
  await expect(handle(dock)).toHaveCount(1);
  await expect(left).toBeVisible();
  await expect(right).toBeVisible();
  if (state === 'collapsed') {
    await expect(left).toHaveText(title);
    await expect(left).toHaveAttribute('title', title);
    await expect(right).toHaveText(`${source === 'live' ? 'Live audio' : 'Recording'} · ${queued} queued`);
    await expect(header.getByRole('group', { name: 'Audio source', exact: true })).toBeHidden();
    await expect(dock.getByRole('slider')).toHaveCount(0);
  } else {
    for (const label of ['Live', 'Recordings', `Queue ${queued}`]) {
      const button = header.getByRole('button', { name: label, exact: true });
      const textFits = await button.evaluate((element) => {
        const range = document.createRange();
        range.selectNodeContents(element);
        const text = range.getBoundingClientRect();
        const bounds = element.getBoundingClientRect();
        return text.left >= bounds.left - 1 && text.right <= bounds.right + 1;
      });
      expect(textFits, `${label} remains inside its button`).toBe(true);
    }
  }
  const [d, h, g, l, r, control] = await Promise.all([
    dock.boundingBox(), header.boundingBox(), grip.boundingBox(), left.boundingBox(), right.boundingBox(),
    handle(dock).boundingBox(),
  ]);
  for (const bounds of [h, g, l, r, control]) {
    expect(bounds).not.toBeNull();
    expect(bounds.width).toBeGreaterThan(0);
    expect(bounds.height).toBeGreaterThan(0);
  }
  // The grip stays centered on the entire dock, even when the two sides have
  // different text widths. All three elements share the same horizontal row.
  expect(Math.abs(g.x + g.width / 2 - (d.x + d.width / 2))).toBeLessThanOrEqual(1.5);
  expect(Math.abs(g.y + g.height / 2 - (l.y + l.height / 2))).toBeLessThanOrEqual(2);
  expect(Math.abs(g.y + g.height / 2 - (r.y + r.height / 2))).toBeLessThanOrEqual(2);
  expect(l.x).toBeGreaterThanOrEqual(h.x);
  expect(r.x + r.width).toBeLessThanOrEqual(h.x + h.width);
  expect(l.x + l.width).toBeLessThanOrEqual(control.x + 1);
  expect(control.x + control.width).toBeLessThanOrEqual(r.x + 1);
  expect(await header.evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);
  if (state === 'collapsed') {
    // Metadata remains readable; the title has a hover hint when it is
    // intentionally ellipsized on a narrow screen.
    expect(await right.evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);
  }
  const reachable = await handle(dock).evaluate((element) => {
    const r = element.getBoundingClientRect();
    const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    return Boolean(hit && element.contains(hit));
  });
  expect(reachable).toBe(true);
}

for (const viewport of viewports) {
  for (const source of ['live', 'recordings']) {
    test(`${source} header keeps title/source, center grip and queue in one row at ${viewport.width}px`, async ({ page }) => {
      await page.setViewportSize(viewport);
      const dock = await openGallery(page, source);
      for (const state of ['collapsed', 'minimal', 'full']) {
        await setSize(dock, state);
        await expectSingleRow(dock, state, source, source === 'live' ? 2 : 3, 'Fire Dispatch');
      }
    });

    test(`long ${source} title and four-digit queue keep the grip clear at ${viewport.width}px`, async ({ page }) => {
      await page.setViewportSize(viewport);
      const dock = await openGallery(page, source, 'collapsed', true);
      for (const state of ['collapsed', 'minimal', 'full']) {
        await setSize(dock, state);
        await expectSingleRow(dock, state, source, 1234,
          'Metropolitan Emergency Communications County Public Safety Dispatch');
      }
    });
  }

  test(`center grip retains click, drag and keyboard size changes at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const dock = await openGallery(page, 'live', 'collapsed');
    for (const state of ['minimal', 'full', 'collapsed']) {
      await handle(dock).click();
      await expect(dock).toHaveAttribute('data-state', state);
    }
    const drag = async (dx, dy) => {
      const bounds = await handle(dock).boundingBox();
      const x = bounds.x + bounds.width / 2, y = bounds.y + bounds.height / 2;
      await page.mouse.move(x, y);
      await page.mouse.down();
      await page.mouse.move(x + dx, y + dy, { steps: 5 });
      await page.mouse.up();
    };
    for (const [dy, state] of [[-48, 'minimal'], [-48, 'full'], [48, 'minimal']]) {
      await drag(0, dy);
      await expect(dock).toHaveAttribute('data-state', state);
    }
    await drag(60, -35);
    await expect(dock).toHaveAttribute('data-state', 'minimal');
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
    await dock.getByRole('button', { name: 'Recordings', exact: true }).click();
    await expect(page).toHaveURL(/view=recordings/);
    await expect(dock).toHaveAttribute('data-source', 'recordings');
    await expect(dock).toHaveAttribute('data-state', 'full');
    await dock.getByRole('button', { name: 'Queue 0', exact: true }).click();
    await expect(dock.getByRole('tab', { name: 'Queue', exact: true })).toHaveAttribute('aria-selected', 'true');
    await expect(dock).toHaveAttribute('data-state', 'full');
    await dock.getByRole('button', { name: 'Live', exact: true }).click();
    await expect(dock).toHaveAttribute('data-source', 'live');
    await expect(dock).toHaveAttribute('data-state', 'full');
  });
}
