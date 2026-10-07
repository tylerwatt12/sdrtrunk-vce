const { expect, test } = require('@playwright/test');

async function openGallery(page, { theme = 'light', source = 'live', state = 'full',
  panel = 'details', fixture = 'normal', hidden = false } = {}) {
  await page.goto(`/design-system.html?${new URLSearchParams({ view: 'audio-dock', theme,
    audioSource: source, audioState: state, audioPanel: panel, audioFixture: fixture, audioHidden: String(hidden) })}`);
  await expect(page.locator('.visual-audio-dock-example')).toHaveAttribute('data-ready', 'true');
  const dock = page.locator('#audio-dock');
  await expect(dock).toHaveAttribute('data-state', state);
  await expect(dock).toHaveAttribute('data-source', source);
  const missingIcons = await dock.locator('svg use').evaluateAll((icons) => icons
    .map((icon) => icon.getAttribute('href')).filter((href) => !document.getElementById(href?.slice(1))));
  expect(missingIcons).toEqual([]);
  return dock;
}

for (const theme of ['light', 'dark']) {
  test(`audio dock desktop Player pill ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    const dock = await openGallery(page, { theme, hidden: true });
    await expect(dock.getByRole('button')).toHaveCount(1);
    const show = dock.getByRole('button', { name: 'Show audio player', exact: true });
    await expect(show).toBeFocused();
    await expect(show).toHaveText('Player');
    await expect(show).toHaveAttribute('aria-expanded', 'false');
    await expect(show).toHaveAttribute('aria-controls', 'audio-dock-presentation');
    await expect(show.locator('use')).toHaveAttribute('href', '#icon-chevron-down');
    await expect(page).toHaveScreenshot(`super-collapsed-${theme}-desktop.png`);
    await show.press('Space');
    await expect(dock).toHaveAttribute('data-state', 'full');
    await expect(dock.getByRole('button', { name: 'Hide audio player', exact: true })).toHaveText('');
    await expect(dock.getByRole('button', { name: 'Hide audio player', exact: true }).locator('use')).toHaveAttribute('href', '#icon-close');
    await expect(dock.getByRole('button', { name: 'Hide audio player', exact: true })).toHaveAttribute('aria-expanded', 'true');
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
            expect(bounds.x).toBeGreaterThan(viewport.width / 2);
            expect(viewport.width - bounds.x - bounds.width).toBe(18);
            expect(viewport.height - bounds.y - bounds.height).toBe(18);
          }
          if (state === 'collapsed') {
            await expect(dock.getByRole('button', { name: 'Change audio player size' })).toBeVisible();
            await expect(dock.getByRole('tab')).toHaveCount(0);
            await expect(dock.getByRole('slider')).toHaveCount(0);
          } else {
            await expect(dock.getByRole('button', { name: source === 'live' ? 'Pause live audio' : 'Play recording' }))
              .toBeVisible();
            await expect(dock.locator('.audio-dock-title')).toHaveText('Fire Dispatch');
            await expect(dock.getByRole('button', { name: 'Change audio player size' }))
              .toHaveAttribute('aria-expanded', 'true');
            if (state === 'full') {
              await expect(dock.locator('.audio-dock-description')).toHaveText(source === 'live' ?
                'Live dispatch and tactical fire calls' : 'County fire dispatch and incident response operations');
              await expect(dock.locator('.audio-dock-system')).toHaveText('Metro Public Safety');
              await expect(dock.locator('.audio-dock-now .audio-dock-meta').first()).toContainText('North Ridge Channel');
              await expect(dock.locator('.audio-dock-now .audio-dock-meta').first()).not.toContainText('Metro Public Safety');
            } else {
              await expect(dock.locator('.audio-dock-description')).toBeHidden();
              await expect(dock.locator('.audio-dock-system')).toBeHidden();
            }
          }
          if (device === 'desktop') {
            const close = dock.getByRole('button', { name: 'Hide audio player', exact: true });
            await expect(close).toHaveText('');
            await expect(close).toHaveAttribute('aria-expanded', 'true');
            await expect(close.locator('use')).toHaveAttribute('href', '#icon-close');
          } else {
            await expect(dock.getByRole('button', { name: 'Hide audio player', exact: true })).toBeHidden();
            await expect(dock.getByRole('button', { name: 'Show audio player', exact: true })).toBeHidden();
          }
          await expect(dock).toHaveScreenshot(`${source}-${state}-${theme}-${device}.png`);
          if (source === 'live' && state === 'full') {
            for (const [name, key] of [['Scan Lists', 'scan-lists'], ['Settings', 'settings']]) {
              await dock.getByRole('tab', { name, exact: true }).click();
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
      const scroller = dock.locator('.audio-dock-panel');
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
    await expect(dock.locator('.audio-dock-system')).toBeHidden();
    await expect(dock.getByRole('button', { name: 'Clear queue', exact: true })).toBeDisabled();
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
  await expect(picker.locator('option[value="recordings"]')).toBeDisabled();
  await expect(picker.locator('option[value="live"]')).toBeEnabled();
  await expect(dock.locator('.audio-dock-description')).toBeHidden();
  await expect(dock).toHaveScreenshot('restricted-source-dark-320.png');
});

test('audio dock recording identifiers retain long values and download at the end', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const dock = await openGallery(page, { source: 'recordings', theme: 'dark', fixture: 'long' });
  for (const name of ['Source & target identities', 'Protocol & signaling', 'Recording identifiers',
    'Received site identities']) await dock.locator('summary').filter({ hasText: name }).click();
  const scroller = dock.locator('.audio-dock-panel');
  await scroller.evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await expect(dock.getByRole('link', { name: 'Download recording', exact: true })).toBeVisible();
  expect(await scroller.evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);
  await expect(dock).toHaveScreenshot('recording-identifiers-dark-mobile.png');
});

test('audio dock full controls remain scrollable in short landscape', async ({ page }) => {
  await page.setViewportSize({ width: 844, height: 390 });
  const dock = await openGallery(page, { source: 'recordings', panel: 'queue', fixture: 'long' });
  await expect(dock).toHaveScreenshot('recording-queue-landscape.png');
  const body = dock.locator('.audio-dock-body');
  await body.evaluate((element) => { element.scrollTop = element.scrollHeight; });
  const lastRow = dock.locator('.audio-dock-queue-row').last();
  const bottom = await lastRow.evaluate((element) => element.getBoundingClientRect().bottom);
  expect(bottom).toBeLessThanOrEqual(390);
  await expect(dock).toHaveScreenshot('recording-queue-landscape-end.png');
});

test('audio dock gallery uses working production state controls and keyboard tabs', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const dock = await openGallery(page);
  await dock.getByRole('button', { name: 'Pause live audio', exact: true }).click();
  await expect(dock.getByRole('button', { name: 'Resume live audio', exact: true })).toBeVisible();
  await dock.getByRole('tab', { name: 'Details', exact: true }).focus();
  await dock.getByRole('tab', { name: 'Details', exact: true }).press('ArrowRight');
  await expect(dock.getByRole('tab', { name: 'Queue', exact: true })).toBeFocused();
  await expect(dock).toContainText('ENG 5');
  await expect(dock).toContainText('Radio 30915');
  await dock.getByRole('tab', { name: 'Queue', exact: true }).press('ArrowRight');
  await expect(dock.getByRole('tab', { name: 'Scan Lists', exact: true })).toBeFocused();
  await dock.getByRole('tab', { name: 'Scan Lists', exact: true }).press('ArrowRight');
  await expect(dock.getByRole('tab', { name: 'Settings', exact: true })).toBeFocused();
  await expect(dock.getByRole('checkbox', { name: 'Group calls by target', exact: true })).toHaveCount(1);
  await dock.getByRole('tab', { name: 'Settings', exact: true }).press('Home');
  await expect(dock.getByRole('tab', { name: 'Details', exact: true })).toBeFocused();
  await dock.getByRole('tab', { name: 'Details', exact: true }).press('End');
  await expect(dock.getByRole('tab', { name: 'Settings', exact: true })).toBeFocused();
  await dock.getByRole('button', { name: 'Recordings', exact: true }).click();
  await expect(dock.getByRole('tab', { name: 'Scan Lists', exact: true })).toHaveCount(0);
  await expect(dock.getByRole('tab', { name: 'Settings', exact: true })).toHaveCount(0);
  await expect(dock.getByRole('tab', { name: 'Details', exact: true })).toHaveAttribute('aria-selected', 'true');
  await dock.getByRole('button', { name: 'Play recording', exact: true }).click();
  await expect(dock.getByRole('button', { name: 'Pause recording', exact: true })).toBeVisible();
  const slider = dock.getByRole('slider', { name: 'Recording position', exact: true });
  await slider.evaluate((input) => { input.value = '15'; input.dispatchEvent(new Event('input', { bubbles: true })); });
  await expect(slider).toHaveAttribute('aria-valuetext', '00:15 of 00:42');
  await dock.getByRole('button', { name: 'Change audio player size', exact: true }).press('Home');
  await expect(dock).toHaveAttribute('data-state', 'collapsed');
  await dock.getByRole('button', { name: 'Change audio player size', exact: true }).click();
  await expect(dock).toHaveAttribute('data-state', 'minimal');
});
