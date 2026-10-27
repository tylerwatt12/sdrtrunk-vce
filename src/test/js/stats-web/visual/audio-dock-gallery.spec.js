const { expect, test } = require('@playwright/test');
const { selectAudioPanel, openAudioVolume } = require('./audio-dock-controls.cjs');

async function openGallery(page, { theme = 'light', source = 'live', state = 'full',
  panel = '', fixture = 'normal', feedback = '', hidden = false } = {}) {
  await page.clock.setFixedTime( new Date('2026-09-30T14:24:18Z'));
  await page.goto(`/design-system.html?${new URLSearchParams({ view: 'audio-dock', theme,
    audioSource: source, audioState: state, audioPanel: panel, audioFixture: fixture,
    audioFeedback: feedback, audioHidden: String(hidden) })}`);
  await expect(page.locator('.visual-audio-dock-example')).toHaveAttribute('data-ready', 'true');
  const dock = page.locator('#audio-dock');
  await expect(dock).toHaveAttribute('data-state', state);
  await expect(dock).toHaveAttribute('data-source', source);
  const missingIcons = await dock.locator('svg use').evaluateAll((icons) => icons
    .map((icon) => icon.getAttribute('href')).filter((href) => !document.getElementById(href?.slice(1))));
  expect(missingIcons).toEqual([]);
  if (state === 'full' && panel) await selectAudioPanel(dock, { details: 'Details', transcript: 'Transcript',
    menu: 'Player menu', listening: 'Lists', direct: 'Direct Listen', avoids: 'Avoid List',
    settings: 'Listening settings', queue: 'Queue' }[panel]);
  await page.mouse.move(0, 0);
  return dock;
}

for (const viewport of [{ width: 320, height: 480 }]) {
  test(`audio dock error leaves the handle and transport reachable in a short ${viewport.width}px viewport`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const dock = await openGallery(page, { source: 'recordings', feedback: 'error', fixture: 'long' });
    const notice = page.locator('.audio-dock-notice');
    await expect(notice).toBeVisible();
    const resize = dock.locator('#audio-dock-size-handle');
    const expectReachableControls = async () => {
      for (const control of [resize, dock.getByRole('button', { name: 'Play recording', exact: true })]) {
        await expect(control).toBeVisible();
        expect(await control.evaluate((element) => {
          const bounds = element.getBoundingClientRect();
          return element.contains(document.elementFromPoint(bounds.x + bounds.width / 2,
            bounds.y + bounds.height / 2));
        })).toBe(true);
      }
    };
    const noticeBounds = await notice.boundingBox();
    const dockBounds = await dock.boundingBox();
    const headerBounds = await dock.locator('.audio-dock-header').boundingBox();
    const controlsBounds = await dock.locator('.audio-dock-controls').boundingBox();
    expect(noticeBounds.y + noticeBounds.height <= dockBounds.y ||
      noticeBounds.y >= headerBounds.y + headerBounds.height &&
      noticeBounds.y + noticeBounds.height <= controlsBounds.y).toBe(true);
    expect(noticeBounds.x).toBeGreaterThanOrEqual(0);
    expect(noticeBounds.x + noticeBounds.width).toBeLessThanOrEqual(viewport.width);
    await expectReachableControls();

    // The app normally measures this header token at runtime. Supply the same
    // measurement in the gallery, then expand beyond the natural full height.
    await page.evaluate(() => {
      const header = document.querySelector('.visual-audio-dock-example .app-header');
      document.documentElement.style.setProperty('--app-header-offset', `${header.getBoundingClientRect().height}px`);
    });
    await resize.evaluate((element) => element.addEventListener('pointerdown', (event) => {
      window.audioGalleryPointerId = event.pointerId;
    }, { once: true }));
    const handleBounds = await resize.boundingBox();
    const x = handleBounds.x + handleBounds.width / 2;
    const y = handleBounds.y + handleBounds.height / 2;
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x, -viewport.height, { steps: 5 });
    await expect(dock).toHaveAttribute('data-dragging', 'true');
    await expect.poll(async () => {
      const [noticeBounds, headerBounds, controlsBounds] = await Promise.all([
        notice.boundingBox(), dock.locator('.audio-dock-header').boundingBox(),
        dock.locator('.audio-dock-controls').boundingBox()
      ]);
      return noticeBounds.y >= headerBounds.y + headerBounds.height &&
        noticeBounds.y + noticeBounds.height <= controlsBounds.y;
    }).toBe(true);
    await expectReachableControls();
    await resize.evaluate((element) => element.releasePointerCapture(window.audioGalleryPointerId));
    await page.mouse.up();
    await expect(dock).not.toHaveAttribute('data-dragging', 'true');
  });
}
