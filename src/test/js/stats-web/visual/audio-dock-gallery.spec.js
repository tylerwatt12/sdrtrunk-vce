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
    listening: 'Scan Lists', settings: 'Settings', queue: 'Queue' }[panel]);
  await page.mouse.move(0, 0);
  return dock;
}

for (const [theme, device, viewport] of [
  ['light', 'desktop', { width: 1280, height: 900 }],
  ['dark', 'mobile', { width: 390, height: 844 }]
]) {
  test(`audio dock recording playback error ${theme} ${device}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const dock = await openGallery(page, { theme, source: 'recordings', feedback: 'error' });
    const notice = page.locator('.audio-dock-notice');
    await expect(notice).toBeVisible();
    await expect(notice).toHaveText('Playback could not start. Press Play to retry.');
    await expect(notice).toHaveClass(/ui-notice-danger/);
    await expect(notice).toHaveAttribute('role', 'alert');
    expect(await notice.evaluate((element) => {
      const dock = document.querySelector('#audio-dock');
      return element.parentElement === dock.parentElement && !dock.contains(element);
    })).toBe(true);
    const dockBounds = await dock.boundingBox();
    const noticeBounds = await notice.boundingBox();
    expect(noticeBounds.x).toBeGreaterThanOrEqual(0);
    expect(noticeBounds.y).toBeGreaterThanOrEqual(0);
    expect(noticeBounds.x + noticeBounds.width).toBeLessThanOrEqual(viewport.width);
    expect(noticeBounds.y + noticeBounds.height).toBeLessThanOrEqual(dockBounds.y);
    for (const label of ['Play recording', 'Stop recording', 'Volume, 70 percent']) {
      const control = dock.getByRole('button', { name: label, exact: true });
      await expect(control).toBeVisible();
      expect(await control.evaluate((element) => {
        const bounds = element.getBoundingClientRect();
        return element.contains(document.elementFromPoint(bounds.x + bounds.width / 2,
          bounds.y + bounds.height / 2));
      })).toBe(true);
    }
    await expect(page).toHaveScreenshot(`recordings-feedback-error-${theme}-${device}.png`);
    await dock.getByRole('button', { name: 'Play recording', exact: true }).click();
    await expect(notice).toBeHidden();
    expect((await dock.boundingBox()).height).toBe(dockBounds.height);
  });

  test(`audio dock partial drag keeps the handle and playback visible ${theme} ${device}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const dock = await openGallery(page, { theme, state: 'minimal' });
    const resize = dock.locator('#audio-dock-size-handle');
    const compactBounds = await dock.boundingBox();
    await resize.press('End');
    const fullHeight = (await dock.boundingBox()).height;
    await resize.press('ArrowDown');
    await expect(dock).toHaveAttribute('data-state', 'minimal');
    const controlBounds = await resize.boundingBox();
    const x = controlBounds.x + controlBounds.width / 2;
    const y = controlBounds.y + controlBounds.height / 2;
    await resize.evaluate((element) => element.addEventListener('pointerdown', (event) => {
      window.audioGalleryPointerId = event.pointerId;
    }, { once: true }));
    await page.mouse.move(x, y);
    await page.mouse.down();
    const delta = (fullHeight - compactBounds.height) * 0.4;
    await page.mouse.move(x, y - delta, { steps: 5 });
    await expect(dock).toHaveAttribute('data-dragging', 'true');
    await expect.poll(async () => Math.abs((await dock.boundingBox()).height - compactBounds.height - delta))
      .toBeLessThanOrEqual(2);
    await expect(resize).toBeVisible();
    const handleBounds = await resize.boundingBox();
    const draggingBounds = await dock.boundingBox();
    expect(handleBounds.y).toBeGreaterThanOrEqual(draggingBounds.y);
    expect(handleBounds.y + handleBounds.height).toBeLessThanOrEqual(draggingBounds.y + controlBounds.height + 10);
    const visiblePlayback = dock.getByRole('button', { name: 'Pause live audio', exact: true });
    await expect(visiblePlayback).toBeVisible();
    for (const control of [resize, visiblePlayback]) {
      expect(await control.evaluate((element) => {
        const bounds = element.getBoundingClientRect();
        return element.contains(document.elementFromPoint(bounds.x + bounds.width / 2,
          bounds.y + bounds.height / 2));
      })).toBe(true);
    }
    const mainBounds = await dock.locator('.audio-dock-player-main').boundingBox();
    for (const control of await dock.locator('.audio-dock-primary-controls button:visible').all()) {
      const bounds = await control.boundingBox();
      expect(bounds.x).toBeGreaterThanOrEqual(mainBounds.x);
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(mainBounds.x + mainBounds.width);
      expect(await control.evaluate((element) => {
        const bounds = element.getBoundingClientRect();
        return element.contains(document.elementFromPoint(bounds.x + bounds.width / 2,
          bounds.y + bounds.height / 2));
      })).toBe(true);
    }
    await expect(dock.locator('.audio-dock-main-scroll')).toBeVisible();
    await expect(dock.locator('.audio-dock-identity')).toContainText('Fire Dispatch');
    await expect(dock).toHaveScreenshot(`live-dragging-${theme}-${device}.png`);
    await resize.evaluate((element) => element.releasePointerCapture(window.audioGalleryPointerId));
    await page.mouse.up();
    await expect(dock).not.toHaveAttribute('data-dragging', 'true');
    await expect(dock).toHaveAttribute('data-state', 'minimal');
    expect((await dock.boundingBox()).height).toBe(compactBounds.height);
  });
}

for (const viewport of [{ width: 844, height: 390 }, { width: 320, height: 480 }]) {
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

for (const theme of ['light', 'dark']) {
  test(`audio dock desktop header Player button ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    const dock = await openGallery(page, { theme, hidden: true });
    await expect(dock).toBeHidden();
    await expect(dock.getByRole('button')).toHaveCount(0);
    const show = page.locator('.visual-audio-dock-example .header-controls').getByRole('button', { name: 'Restore player', exact: true });
    await expect(show).toBeFocused();
    await expect(show).toHaveText('Player');
    await expect(show).toHaveAttribute('aria-expanded', 'false');
    await expect(show).toHaveAttribute('aria-controls', 'audio-dock-presentation');
    await expect(show.locator('use')).toHaveAttribute('href', '#icon-maximize-corners');
    expect(await show.evaluate((element) => element.nextElementSibling?.id)).toBe('receiver-health-indicator');
    await expect(page.locator('.audio-dock-motion')).toHaveCount(0);
    await expect(page).toHaveScreenshot(`super-collapsed-${theme}-desktop.png`);
    await show.press('Space');
    await expect(dock).toHaveAttribute('data-state', 'full');
    await expect(dock.getByRole('button', { name: 'Minimize player to header', exact: true })).toHaveText('');
    await expect(dock.getByRole('button', { name: 'Minimize player to header', exact: true }).locator('use')).toHaveAttribute('href', '#icon-close');
    await expect(dock.getByRole('button', { name: 'Minimize player to header', exact: true })).toHaveAttribute('aria-expanded', 'true');
    await expect(dock.getByRole('button', { name: 'Pause live audio', exact: true })).toBeVisible();
  });
  for (const [device, viewport] of [['desktop', { width: 1280, height: 900 }],
    ['mobile', { width: 390, height: 844 }]]) {
    for (const source of ['live', 'recordings']) {
      for (const state of ['collapsed', 'minimal', 'full']) {
        test(`audio dock ${source} ${state} ${theme} ${device}`, async ({ page }) => {
          await page.setViewportSize(viewport);
          const dock = await openGallery(page, { theme, source, state });
          const bounds = await dock.boundingBox();
          expect(bounds.x).toBeGreaterThanOrEqual(0);
          expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width);
          expect(bounds.y + bounds.height).toBeLessThanOrEqual(viewport.height);
          if (device === 'mobile') {
            expect(bounds.x).toBe(0);
            expect(bounds.width).toBe(viewport.width);
            expect(bounds.y + bounds.height).toBe(viewport.height);
          } else {
            expect(bounds.x).toBeGreaterThanOrEqual(state === 'full' ? 0 : viewport.width / 2);
            expect(viewport.width - bounds.x - bounds.width).toBe(18);
            expect(viewport.height - bounds.y - bounds.height).toBe(18);
          }
          if (state === 'collapsed') {
            await expect(dock.locator('#audio-dock-slim-expand')).toBeVisible();
            await expect(dock.getByRole('tab')).toHaveCount(0);
            await expect(dock.getByRole('slider')).toHaveCount(0);
          } else {
            await expect(dock.getByRole('button', { name: source === 'live' ? 'Pause live audio' : 'Play recording' }))
              .toBeVisible();
            await expect(dock.locator('.audio-dock-title:visible')).toHaveText('Fire Dispatch');
            await expect(dock.getByRole('button', { name: 'Change audio player size' }))
              .toHaveAttribute('aria-expanded', 'true');
            if (state === 'full') {
              await expect(dock.locator('.audio-dock-description')).toHaveText(source === 'live' ?
                'Live dispatch and tactical fire calls' : 'County fire dispatch and incident response operations');
              await expect(dock.locator('.audio-dock-identity')).toContainText('Metro Public Safety');
              await expect(dock.locator('.audio-dock-identity')).toContainText('North Ridge Channel');
              await expect(dock.locator('.audio-dock-transmitting')).toContainText('ENG 4');
            } else {
              await expect(dock.locator('.audio-dock-description')).toBeHidden();
              await expect(dock.locator('.audio-dock-identity')).toBeHidden();
            }
          }
          if (device === 'desktop') {
            const close = dock.getByRole('button', { name: 'Minimize player to header', exact: true });
            await expect(close).toHaveText('');
            await expect(close).toHaveAttribute('aria-expanded', 'true');
            await expect(close.locator('use')).toHaveAttribute('href', '#icon-close');
          } else {
            await expect(dock.getByRole('button', { name: 'Minimize player to header', exact: true })).toBeHidden();
            await expect(page.getByRole('button', { name: 'Restore player', exact: true })).toBeHidden();
          }
          await expect(dock).toHaveScreenshot(`${source}-${state}-${theme}-${device}.png`);
          if (source === 'live' && state === 'full') {
            for (const [name, key] of [['Scan Lists', 'scan-lists'], ['Settings', 'settings']]) {
              await selectAudioPanel(dock, name);
              await page.mouse.move(0, 0);
              expect(await dock.evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);
              await expect(dock).toHaveScreenshot(`live-${key}-${theme}-${device}.png`);
            }
          }
        });
      }
    }
  }
}

for (const [source, panel] of [['live', 'queue'], ['live', 'listening'], ['live', 'settings'],
  ['recordings', 'queue'], ['recordings', 'transcript']]) {
  for (const theme of ['light', 'dark']) {
    test(`audio dock long ${source} ${panel} ${theme} 320px`, async ({ page }) => {
      await page.setViewportSize({ width: 320, height: 740 });
      const dock = await openGallery(page, { theme, source, panel, fixture: 'long' });
      const scroller = dock.locator('.audio-dock-main-scroll');
      expect(await dock.evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);
      expect(await scroller.evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);
      await expect(dock).toHaveScreenshot(`long-${source}-${panel}-${theme}-320.png`);
      await scroller.evaluate((element) => { element.scrollTop = element.scrollHeight; });
      const finalChild = scroller.locator(':scope > :last-child');
      const bottom = await finalChild.evaluate((element) => element.getBoundingClientRect().bottom);
      const scrollerBottom = await scroller.evaluate((element) => element.getBoundingClientRect().bottom);
      expect(bottom).toBeLessThanOrEqual(scrollerBottom + 1);
    });
  }
}

for (const source of ['live', 'recordings']) {
  test(`audio dock empty ${source} controls and queue`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const dock = await openGallery(page, { source, panel: 'queue', fixture: 'empty' });
    await expect(dock).toContainText('Queue is empty.');
    await expect(dock.locator('.audio-dock-description')).toBeHidden();
    await expect(dock.locator('.audio-dock-identity')).toBeHidden();
    await expect(dock.locator('#audio-dock-panel').getByRole('button', { name: 'Clear queue', exact: true })).toBeDisabled();
    await expect(dock.getByRole('button', { name: 'Next call', exact: true })).toBeDisabled();
    await expect(dock).toHaveScreenshot(`empty-${source}-mobile.png`);
  });
}

test('audio dock restricted source remains visible and disabled', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  const dock = await openGallery(page, { fixture: 'restricted', theme: 'dark' });
  const picker = dock.getByRole('combobox', { name: 'Audio source', exact: true });
  await expect(picker).toBeVisible();
  await expect.poll(() => picker.evaluate((element) => element.selectedOptions[0]?.label)).toBe('Live');
  await expect(picker.locator('option[value="recordings"]')).toHaveJSProperty('disabled', true);
  await expect(picker.locator('option[value="live"]')).toHaveJSProperty('disabled', false);
  await expect(dock.locator('.audio-dock-description')).toBeHidden();
  await expect(dock).toHaveScreenshot('restricted-source-dark-320.png');
});

test('audio dock recording identifiers retain long values and download at the end', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const dock = await openGallery(page, { source: 'recordings', theme: 'dark', fixture: 'long', panel: 'details' });
  for (const name of ['Source & target identities', 'Protocol & signaling', 'Recording identifiers',
    'Received site identities']) await dock.locator('summary').filter({ hasText: name }).click();
  const scroller = dock.locator('.audio-dock-main-scroll');
  await scroller.evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await expect(dock.getByRole('link', { name: 'Download recording', exact: true })).toBeVisible();
  expect(await scroller.evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);
  await expect(dock).toHaveScreenshot('recording-identifiers-dark-mobile.png');
});

test('audio dock full controls remain scrollable in short landscape', async ({ page }) => {
  await page.setViewportSize({ width: 844, height: 390 });
  const dock = await openGallery(page, { source: 'recordings', panel: 'queue', fixture: 'long' });
  await expect(dock).toHaveScreenshot('recording-queue-landscape.png');
  const body = dock.locator('.audio-dock-main-scroll');
  await body.evaluate((element) => { element.scrollTop = element.scrollHeight; });
  const lastRow = dock.locator('#audio-dock-panel .audio-dock-queue-item').last();
  const bottom = await lastRow.evaluate((element) => element.getBoundingClientRect().bottom);
  expect(bottom).toBeLessThanOrEqual(390);
  await expect(dock).toHaveScreenshot('recording-queue-landscape-end.png');
});

test('audio dock gallery uses production transport and direct keyboard context actions', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const dock = await openGallery(page);
  await dock.getByRole('button', { name: 'Pause live audio', exact: true }).click();
  await expect(dock.getByRole('button', { name: 'Resume live audio', exact: true })).toBeVisible();
  const details = dock.locator('#audio-dock-call-details');
  await details.focus();
  await details.press('Enter');
  await expect(dock.locator('#audio-dock-context-back')).toBeFocused();
  await expect(dock.locator('#audio-dock-context-title')).toHaveText('Call details');
  await dock.locator('#audio-dock-context-back').press('Enter');
  await expect(details).toBeFocused();
  await selectAudioPanel(dock, 'Queue');
  await expect(dock.locator('#audio-dock-panel')).toContainText('ENG 5');
  await expect(dock.locator('#audio-dock-panel')).toContainText('851.01250 MHz');
  await selectAudioPanel(dock, 'Scan Lists');
  await expect(dock.getByRole('checkbox', { name: /Fire/ }).first()).toBeVisible();
  await selectAudioPanel(dock, 'Settings');
  await expect(dock.getByRole('checkbox', { name: 'Group calls by target', exact: true })).toHaveCount(1);
  await dock.locator('#audio-dock-context-back').press('Escape');
  await expect(dock.locator('#audio-dock-settings')).toBeFocused();
  await dock.getByRole('combobox', { name: 'Audio source', exact: true }).selectOption('recordings');
  await expect(dock.locator('.audio-dock-lists-trigger:visible')).toHaveCount(0);
  await expect(dock.locator('#audio-dock-settings:visible')).toHaveCount(0);
  await dock.getByRole('button', { name: 'Play recording', exact: true }).click();
  await expect(dock.getByRole('button', { name: 'Pause recording', exact: true })).toBeVisible();
  const slider = dock.getByRole('slider', { name: 'Recording position', exact: true });
  await slider.evaluate((input) => { input.value = '15'; input.dispatchEvent(new Event('input', { bubbles: true })); });
  await expect(slider).toHaveAttribute('aria-valuetext', '00:15 of 00:42');
  await dock.locator('#audio-dock-size-handle').press('Home');
  await expect(dock).toHaveAttribute('data-state', 'collapsed');
  await dock.locator('#audio-dock-slim-expand').press('End');
  await expect(dock).toHaveAttribute('data-state', 'full');
});
