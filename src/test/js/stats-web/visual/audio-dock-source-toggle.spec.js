'use strict';

const { expect, test } = require('@playwright/test');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');
const { selectDockSource } = require('./fixtures/audio-dock-source.cjs');

const player = (page) => page.locator('#audio-dock');
const picker = (page) => player(page).getByRole('combobox', { name: 'Audio source', exact: true });
const group = (page) => player(page).getByRole('group', { name: 'Audio source', exact: true });

async function expectPickerSource(page, source) {
  await expect(player(page)).toHaveAttribute('data-source', source);
  await expect.poll(() => picker(page).evaluate((element) => element.selectedOptions[0]?.label))
    .toBe(source === 'live' ? 'Live' : 'Recordings');
}

async function openSourceApp(page, { width = 320, ...options } = {}) {
  await page.setViewportSize({ width, height: width >= 1000 ? 900 : 844 });
  await openAudioApp(page, { view: 'dashboard', feedCallsEnabled: false, ...options });
  await expect(player(page)).toHaveAttribute('data-state', 'minimal');
}

test('narrow source picker switches playback sources and opens Recordings without resizing', async ({ page }) => {
  await openSourceApp(page);
  await expect(picker(page)).toBeVisible();
  await expect(group(page)).toBeHidden();
  await expectPickerSource(page, 'live');
  await picker(page).focus();
  await selectDockSource(player(page), 'recordings');
  await expect(page).toHaveURL(/view=recordings/);
  await expect(player(page)).toHaveAttribute('data-source', 'recordings');
  await expect(player(page)).toHaveAttribute('data-state', 'minimal');
  await expect(picker(page)).toBeFocused();

  // Selecting the current source must not restart or duplicate navigation.
  await selectDockSource(player(page), 'recordings');
  await expect(page).toHaveURL(/view=recordings/);
  await expect(player(page)).toHaveAttribute('data-state', 'minimal');
  await selectDockSource(player(page), 'live');
  await expect(player(page)).toHaveAttribute('data-source', 'live');
  await expect(page).toHaveURL(/view=recordings/);
  await expect(player(page)).toHaveAttribute('data-state', 'minimal');
  await expect(picker(page)).toBeFocused();
  await selectDockSource(player(page), 'live');
  await expectPickerSource(page, 'live');
  await expect(player(page)).toHaveAttribute('data-state', 'minimal');
});

for (const [source, key] of [['recordings', 'r'], ['live', 'l']]) {
  test(`narrow native picker selects ${source} with keyboard typeahead`, async ({ page }) => {
    await openSourceApp(page);
    if (source === 'live') await selectDockSource(player(page), 'recordings');
    await picker(page).focus();
    await picker(page).press(key);
    await expectPickerSource(page, source);
    await expect(page).toHaveURL(/view=recordings/);
    await expect(player(page)).toHaveAttribute('data-state', 'minimal');
    await expect(picker(page)).toBeFocused();
  });
}

for (const [name, capabilities, allowed, blocked] of [
  ['live only', { recordings: false }, 'live', 'recordings'],
  ['recordings only', { 'call-audio': false }, 'recordings', 'live'],
]) {
  test(`narrow ${name} picker preserves playback permissions`, async ({ page }) => {
    await openSourceApp(page, { capabilities });
    await expectPickerSource(page, allowed);
    await expect(picker(page).locator(`option[value="${allowed}"]`)).toBeEnabled();
    await expect(picker(page).locator(`option[value="${blocked}"]`)).toBeDisabled();
    await picker(page).focus();
    await picker(page).press(blocked === 'recordings' ? 'r' : 'l');
    await expectPickerSource(page, allowed);
    await expect(player(page)).toHaveAttribute('data-state', 'minimal');
  });
}

for (const source of ['live', 'recordings']) {
  test(`${source} selection and keyboard focus survive the responsive source-control swap`, async ({ page }) => {
    await openSourceApp(page);
    await selectDockSource(player(page), source);
    await picker(page).focus();
    await page.setViewportSize({ width: 390, height: 844 });
    const selected = player(page).getByRole('button', { name: source === 'live' ? 'Live' : 'Recordings', exact: true });
    await expect(group(page)).toBeVisible();
    await expect(picker(page)).toBeHidden();
    await expect(selected).toHaveAttribute('aria-pressed', 'true');
    await expect(selected).toBeFocused();
    await expect(player(page)).toHaveAttribute('data-state', 'minimal');
    await page.setViewportSize({ width: 320, height: 844 });
    await expect(picker(page)).toBeVisible();
    await expect(group(page)).toBeHidden();
    await expectPickerSource(page, source);
    await expect(picker(page)).toBeFocused();
    await expect(player(page)).toHaveAttribute('data-state', 'minimal');
  });
}

for (const width of [390, 1280]) {
  for (const theme of ['light', 'dark']) {
    test(`segmented source labels have comfortable insets and spacing at ${width}px in ${theme}`, async ({ page }) => {
      await openSourceApp(page, { width, theme });
      await expect(group(page)).toBeVisible();
      await expect(picker(page)).toBeHidden();
      const layout = await group(page).evaluate((element) => {
        const container = element.getBoundingClientRect();
        const style = getComputedStyle(element);
        return { padding: [style.paddingTop, style.paddingRight, style.paddingBottom, style.paddingLeft].map(parseFloat),
          gap: parseFloat(style.columnGap),
          items: [...element.querySelectorAll('button')].map((button) => {
            const bounds = button.getBoundingClientRect();
            const text = document.createRange();
            text.selectNodeContents(button);
            const label = text.getBoundingClientRect();
            return { height: bounds.height, labelLeft: label.left - bounds.left,
              labelRight: bounds.right - label.right, left: bounds.left - container.left,
              right: container.right - bounds.right, top: bounds.top - container.top,
              bottom: container.bottom - bounds.bottom };
          }) };
      });
      for (const inset of layout.padding) expect(inset).toBeGreaterThanOrEqual(4);
      expect(layout.gap).toBeGreaterThanOrEqual(2);
      for (const item of layout.items) {
        expect(item.height).toBeGreaterThanOrEqual(36);
        expect(item.height).toBeLessThanOrEqual(40);
        expect(item.labelLeft).toBeGreaterThanOrEqual(8);
        expect(item.labelRight).toBeGreaterThanOrEqual(8);
        expect(item.top).toBeGreaterThanOrEqual(4);
        expect(item.bottom).toBeGreaterThanOrEqual(4);
      }
    });
  }
}

test('source-control breakpoint keeps both labels, the center grip and queue clear', async ({ page }) => {
  await openSourceApp(page, { width: 359 });
  for (const width of [359, 360, 390]) {
    await page.setViewportSize({ width, height: 844 });
    const source = width < 360 ? picker(page) : group(page);
    const handle = player(page).getByRole('button', { name: 'Change audio player size', exact: true });
    const queue = player(page).getByRole('button', { name: 'Queue 0', exact: true });
    await expect(source).toBeVisible();
    const [dock, left, center, right] = await Promise.all([
      player(page).boundingBox(), source.boundingBox(), handle.boundingBox(), queue.boundingBox(),
    ]);
    expect(Math.abs(center.x + center.width / 2 - (dock.x + dock.width / 2))).toBeLessThanOrEqual(1);
    expect(left.x + left.width).toBeLessThanOrEqual(center.x);
    expect(center.x + center.width).toBeLessThanOrEqual(right.x);
    expect(await player(page).evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);
  }
});

test('choosing current Recordings from the narrow picker navigates back and retains playback', async ({ page }) => {
  await openSourceApp(page);
  await selectDockSource(player(page), 'recordings');
  await page.locator('.recordings-play-glyph').first().click();
  await page.getByRole('dialog', { name: 'Play recording', exact: true })
    .getByRole('button', { name: /^Play once/ }).click();
  await expect(player(page).getByRole('button', { name: 'Pause recording', exact: true })).toBeVisible();
  const plays = () => page.evaluate(() => window.audioTest.recordings.filter((event) => event.action === 'play').length);
  const before = await plays();

  await page.getByRole('button', { name: 'Open navigation', exact: true }).click();
  const dashboard = page.locator('#primary-navigation a[href="/?view=dashboard"]');
  if (!await dashboard.isVisible()) await page.locator('[data-nav-group="listen"] summary').click();
  await dashboard.click();
  await expect(page).toHaveURL(/view=dashboard/);
  await expectPickerSource(page, 'recordings');
  await selectDockSource(player(page), 'recordings');
  await expect(page).toHaveURL(/view=recordings/);
  await expect(player(page).getByRole('button', { name: 'Pause recording', exact: true })).toBeVisible();
  await expect(player(page)).toHaveAttribute('data-state', 'minimal');
  expect(await plays()).toBe(before);
});
