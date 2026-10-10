const { expect, test } = require('@playwright/test');
const { selectAudioPanel, openAudioVolume } = require('./audio-dock-controls.cjs');

async function openGallery(page, { theme = 'light', source = 'live', state = 'full',
  panel = '', fixture = 'normal', hidden = false } = {}) {
  await page.clock.setFixedTime( new Date('2026-09-30T14:24:18Z'));
  await page.goto(`/design-system.html?${new URLSearchParams({ view: 'audio-dock', theme,
    audioSource: source, audioState: state, audioPanel: panel, audioFixture: fixture, audioHidden: String(hidden) })}`);
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
