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

test('desktop native source picker switches playback sources and opens Recordings without resizing', async ({ page }) => {
  await openSourceApp(page, { width: 1280 });
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
    await expect(picker(page).locator(`option[value="${allowed}"]`)).toHaveJSProperty('disabled', false);
    await expect(picker(page).locator(`option[value="${blocked}"]`)).toHaveJSProperty('disabled', true);
    await picker(page).focus();
    await picker(page).press(blocked === 'recordings' ? 'r' : 'l');
    await expectPickerSource(page, allowed);
    await expect(player(page)).toHaveAttribute('data-state', 'minimal');
  });
}

for (const source of ['live', 'recordings']) {
  test(`${source} native source selection and keyboard focus survive desktop and mobile resizing`, async ({ page }) => {
    await openSourceApp(page);
    await selectDockSource(player(page), source);
    await picker(page).focus();
    await page.setViewportSize({ width: 1280, height: 900 });
    await expect(picker(page)).toBeVisible();
    await expect(picker(page)).toBeFocused();
    await expectPickerSource(page, source);
    await expect(group(page)).toHaveCount(0);
    await expect(player(page)).toHaveAttribute('data-state', 'minimal');
    await page.setViewportSize({ width: 320, height: 844 });
    await expect(picker(page)).toBeVisible();
    await expect(group(page)).toBeHidden();
    await expectPickerSource(page, source);
    await expect(picker(page)).toBeFocused();
    await expect(player(page)).toHaveAttribute('data-state', 'minimal');
  });
}

for (const width of [1050, 1280]) {
  for (const theme of ['light']) {
    test(`desktop native source dropdown is readable and comfortable at ${width}px in ${theme}`, async ({ page }) => {
      await openSourceApp(page, { width, theme });
      await expect(picker(page)).toBeVisible();
      await expect(group(page)).toHaveCount(0);
      const layout = await picker(page).evaluate(element => {
        const style = getComputedStyle(element), bounds = element.getBoundingClientRect();
        const canvas = document.createElement('canvas'), context = canvas.getContext('2d');
        context.font = `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`;
        return { height: bounds.height, width: bounds.width,
          start: parseFloat(style.paddingInlineStart), end: parseFloat(style.paddingInlineEnd),
          labelWidth: context.measureText('Recordings').width,
          reachable: element.contains(document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)) };
      });
      expect(layout.height).toBeGreaterThanOrEqual(40);
      expect(layout.start).toBeGreaterThanOrEqual(8);
      expect(layout.end).toBeGreaterThanOrEqual(16);
      expect(layout.labelWidth + layout.start + layout.end).toBeLessThanOrEqual(layout.width + 1);
      expect(layout.reachable).toBe(true);
    });
  }
}

test('native source dropdown keeps the centered grip clear at every width', async ({ page }) => {
  await openSourceApp(page, { width: 359 });
  for (const width of [320, 390, 900, 901, 1280]) {
    await page.setViewportSize({ width, height: 844 });
    const source = picker(page);
    const handle = player(page).getByRole('button', { name: 'Change audio player size', exact: true });
    const context = player(page).locator('.audio-dock-header-end');
    await expect(source).toBeVisible();
    const [dock, left, center, right] = await Promise.all([
      player(page).boundingBox(), source.boundingBox(), handle.boundingBox(), context.boundingBox(),
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
  await page.locator('.recordings-call-info').first().click();
  await page.getByRole('dialog', { name: 'Call actions', exact: true })
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
