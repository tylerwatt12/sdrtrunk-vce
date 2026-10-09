'use strict';

const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');

async function expectFullWidthScannerActions(page) {
  const controls = page.locator('.scanner-playback-controls');
  const row = await controls.boundingBox();
  const bar = await page.locator('#playback-bar').boundingBox();
  const padding = await page.locator('#playback-bar').evaluate(element => {
    const style = getComputedStyle(element);
    return { left: parseFloat(style.paddingLeft), right: parseFloat(style.paddingRight) };
  });
  expect(row.x).toBeCloseTo(bar.x + padding.left, 1);
  expect(row.x + row.width).toBeCloseTo(bar.x + bar.width - padding.right, 1);
  const buttons = await controls.locator(':scope > button').evaluateAll(elements =>
    elements.map(element => {
      const box = element.getBoundingClientRect();
      const hit = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
      return { left: box.left, right: box.right, width: box.width, height: box.height,
        reachable: Boolean(hit && element.contains(hit)) };
    }));
  expect(buttons).toHaveLength(5);
  expect(buttons[0].left).toBeCloseTo(row.x, 1);
  expect(buttons.at(-1).right).toBeCloseTo(row.x + row.width, 1);
  for (const button of buttons) {
    expect(button.width).toBeCloseTo((row.width - 4 * 8) / 5, 1);
    expect(button.height).toBeGreaterThanOrEqual(40);
    expect(button.reachable).toBe(true);
  }
  for (let index = 1; index < buttons.length; index += 1) {
    expect(buttons[index].left - buttons[index - 1].right).toBeCloseTo(8, 1);
  }
}

for (const [theme, viewport] of [
  ['light', { width: 1280, height: 900 }],
  ['dark', { width: 390, height: 844 }],
  ['light', { width: 390, height: 844 }],
  ['light', { width: 320, height: 568 }],
  ['dark', { width: 320, height: 568 }],
  ['dark', { width: 844, height: 390 }]
]) {
  test(`Scanner controls ${theme} ${viewport.width}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const fixture = await installSiteStyleApplication(page, theme);
    const defaults = (await import(pathToFileURL(path.resolve(__dirname,
      '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
    let preferences = structuredClone(defaults);
    preferences.appearance.theme = theme;
    let revision = 1;
    await page.route('**/api/v1/me/preferences', async route => {
      if (route.request().method() === 'PUT') {
        preferences = route.request().postDataJSON();
        revision += 1;
      }
      return route.fulfill({ json: { revision, preferences } });
    });
    await page.route('**/api/v1/scan-lists', route => route.fulfill({ json: { data: { scan_lists: [
      { id: 1, name: 'County Fire and Emergency Medical Services', description: 'Fire dispatch', default: true },
      { id: 2, name: 'Regional Interoperability and Emergency Operations Coordination',
        description: 'A long scan-list name remains readable in the dropdown', default: false }
    ] } } }));
    await page.goto('/app.html?view=scanner');
    const modes = page.getByRole('combobox', { name: 'Scanner detail level', exact: true });
    await expect(modes).toHaveValue('normal');
    await expect(modes.locator('option')).toHaveText(['Simple', 'Normal', 'Advanced', 'Engineer']);
    await expect(page.locator('.scanner-playback-primary')).toBeVisible();
    for (const id of ['playback-skip', 'playback-replay', 'playback-hold', 'playback-volume']) {
      await expect(page.locator(`#${id}`)).toBeVisible();
    }
    await expect(page.locator('#playback-queued')).toBeVisible();
    await page.screenshot({ path: test.info().outputPath(`scanner-${theme}-${viewport.width}.png`), fullPage: true });
    await expect(page.locator('.scanner-scan-rail')).toHaveCount(0);
    await page.locator('.scanner-scan-trigger').click();
    const lists = page.getByRole('dialog', { name: 'Listen to scan lists', exact: true });
    await expect(lists).toBeVisible();
    const checkboxes = lists.getByRole('checkbox');
    expect(await checkboxes.count()).toBeGreaterThan(1);
    const first = checkboxes.first();
    const before = await first.isChecked();
    await first.press('Space');
    await expect(first).toBeChecked({ checked: !before });
    await expect(lists).toBeVisible();
    await first.press('Escape');
    await expect(lists).toBeHidden();
    await expect.poll(() => preferences.playback.selected_scan_list_ids.includes(1)).toBe(!before);
    await page.reload();
    await page.locator('.scanner-scan-trigger').click();
    await expect(lists.getByRole('checkbox').first()).toBeChecked({ checked: !before });
    await page.keyboard.press('Escape');
    const primary = page.locator('.scanner-playback-primary');
    await primary.click();
    await expect(primary).toHaveAttribute('aria-label', /Pause audio/);
    await primary.click();
    await expect(primary).toHaveAttribute('aria-label', 'Resume browser call audio');
    await primary.click();
    await expect(primary).toHaveAttribute('aria-label', /Pause audio/);
    await page.locator('.scanner-playback-more-trigger').click();
    const more = page.getByRole('dialog', { name: 'More playback controls', exact: true });
    await expect(more).toBeVisible();
    await expect(more.getByRole('button', { name: 'Stop browser call audio', exact: true })).toBeVisible();
    await expect(more.locator('#playback-avoid')).toBeVisible();
    await expect(more.locator('#playback-clear-queue')).toBeVisible();
    const bounds = await more.boundingBox();
    expect(bounds.x).toBeGreaterThanOrEqual(0);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width);
    expect(bounds.y + bounds.height).toBeLessThanOrEqual(viewport.height);
    await page.keyboard.press('Escape');
    await expect(more).toBeHidden();
    await modes.selectOption('engineer');
    await expect(page.locator('.scanner-page')).toHaveAttribute('data-scanner-mode', 'engineer');
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    const scrollY = await page.evaluate(() => window.scrollY);
    await modes.evaluate(select => {
      select.value = 'simple';
      select.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await expect(page.locator('.scanner-page')).toHaveAttribute('data-scanner-mode', 'simple');
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(scrollY);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width);
    if (viewport.width <= 680) {
      const bar = await page.locator('#playback-bar').boundingBox();
      expect(bar.y + bar.height).toBe(viewport.height);
      const volume = await page.locator('.scanner-playback-transport .ui-audio-volume-control').boundingBox();
      const queue = await page.locator('.playback-queue').boundingBox();
      expect(volume.x + volume.width).toBeLessThanOrEqual(queue.x - 1);
      await expectFullWidthScannerActions(page);
      const reserve = await page.locator('.scanner-page').evaluate(element =>
        parseFloat(getComputedStyle(element).paddingBottom));
      expect(reserve).toBeGreaterThan(bar.height);
      await page.evaluate(() => {
        Object.defineProperty(window.visualViewport, 'height', { configurable: true, value: window.innerHeight - 200 });
        window.visualViewport.dispatchEvent(new Event('resize'));
      });
      await expect.poll(async () => {
        const keyboardBar = await page.locator('#playback-bar').boundingBox();
        return keyboardBar.y + keyboardBar.height;
      }).toBe(viewport.height - 200);
      await page.locator('.scanner-playback-more-trigger').click();
      await expect.poll(async () => {
        const keyboardMore = await page.getByRole('dialog', { name: 'More playback controls', exact: true }).boundingBox();
        return keyboardMore.y >= 0 && keyboardMore.y + keyboardMore.height <= viewport.height - 200;
      }).toBe(true);
      await page.keyboard.press('Escape');
      await page.locator('.playback-queue summary').click();
      const keyboardQueue = await page.locator('.playback-queue-list').boundingBox();
      expect(keyboardQueue.y).toBeGreaterThanOrEqual(0);
      expect(keyboardQueue.y + keyboardQueue.height).toBeLessThanOrEqual(viewport.height - 200);
      await page.locator('.playback-queue summary').click();
      await page.evaluate(() => {
        delete window.visualViewport.height;
        window.visualViewport.dispatchEvent(new Event('resize'));
      });
      const scannerStyles = await page.evaluate(() => fetch('/assets/styles/features/scanner.css').then(response => response.text()));
      await page.addStyleTag({ content: scannerStyles.replaceAll('env(safe-area-inset-bottom)', '20px') });
      expect(await page.locator('#playback-bar').evaluate(element =>
        parseFloat(getComputedStyle(element).paddingBottom))).toBeGreaterThan(20);
    }
    await page.goto('/app.html?view=dashboard');
    await expect(page.locator('#desktop-playback-slot #playback-skip')).toHaveCount(1);
    await expect(page.locator('#desktop-playback-slot #playback-volume')).toHaveCount(1);
    await expect(page.locator('.scanner-playback-transport')).toHaveCount(0);
    expect(fixture.pageErrors).toEqual([]);
  });
}

test('Scanner scan-list loading, retry and selection limit', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const fixture = await installSiteStyleApplication(page, 'light');
  const defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
  const preferences = structuredClone(defaults);
  preferences.playback.selected_scan_list_ids = Array.from({ length: 16 }, (_, index) => index + 1);
  await page.route('**/api/v1/me/preferences', route => route.fulfill({ json: { revision: 1, preferences } }));
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  let reads = 0;
  await page.route('**/api/v1/scan-lists', async route => {
    reads += 1;
    if (reads === 1) {
      await gate;
      return route.fulfill({ status: 503, json: { error: 'temporarily unavailable' } });
    }
    return route.fulfill({ json: { data: { scan_lists: Array.from({ length: 17 }, (_, index) => ({
      id: index + 1, name: `Scan list ${index + 1}`, default: index === 0, description: ''
    })) } } });
  });
  await page.goto('/app.html?view=scanner');
  await page.locator('.scanner-scan-trigger').click();
  const lists = page.getByRole('dialog', { name: 'Listen to scan lists', exact: true });
  await expect(lists.getByRole('status')).toHaveText('Loading available scan lists…');
  release();
  await expect(lists.getByRole('status')).toHaveText('Unable to load scan lists. Try again.');
  await lists.getByRole('button', { name: 'Retry scan lists', exact: true }).click();
  await expect(lists.getByRole('checkbox')).toHaveCount(17);
  await expect(lists.getByRole('checkbox', { name: 'Scan list 17', exact: true })).toBeDisabled();
  await expect(lists.getByRole('checkbox', { name: 'Scan list 1', exact: true })).toBeEnabled();
  expect(fixture.pageErrors).toEqual([]);
});

for (const theme of ['light', 'dark']) {
  for (const width of [320, 390]) {
    test(`Scanner native volume ${theme} ${width}`, async ({ page }) => {
      await page.setViewportSize({ width, height: width === 320 ? 568 : 844 });
      const state = await openAudioApp(page, { view: 'scanner', theme, volume: 0.75, feedCallsEnabled: false });
      const volume = page.locator('#playback-volume');
      const output = page.locator('.ui-audio-volume-output');
      await expect(volume).toHaveClass(/ui-range ui-audio-range/);
      await expect(page.locator('.ui-audio-volume-label')).toHaveText('Volume');
      await expect(output).toHaveText('75%');
      await expectFullWidthScannerActions(page);
      const bounds = await volume.boundingBox();
      expect(bounds.height).toBe(40);
      await expect(page.locator('.ui-audio-volume-control')).toHaveCSS('background-image', 'none');
      await volume.click({ position: { x: bounds.width / 2, y: bounds.height / 2 } });
      await expect(volume).toHaveValue('0.5');
      await expect(output).toHaveText('50%');
      await volume.press('ArrowRight');
      await expect(volume).toHaveValue('0.55');
      await expect(output).toHaveText('55%');
      await expect(volume).toHaveAttribute('aria-valuetext', '55 percent');
      await expect.poll(() => state.preferenceWrites.at(-1)?.playback?.volume).toBe(0.55);
      await page.reload();
      await expect(volume).toHaveValue('0.55');
      await expect(output).toHaveText('55%');
      await page.screenshot({ path: test.info().outputPath(`scanner-proportions-${theme}-${width}.png`) });
      const input = await volume.elementHandle();
      await page.locator('.brand').click();
      await expect(page.locator('body')).toHaveAttribute('data-view', 'dashboard');
      expect(await input.evaluate(element => element.isConnected)).toBe(true);
      expect(await input.evaluate(element => element.parentElement.className)).toBe('playback-volume');
      expect(await input.evaluate(element => element.className)).toBe('');
      expect(await input.evaluate(element => element.value)).toBe('0.55');
      expect(await input.evaluate(element => element.getAttribute('aria-valuetext'))).toBe('55 percent');
      await expect(page.locator('.ui-audio-volume-output')).toHaveCount(0);
      await expect(page.locator('.playback-volume-label')).toHaveText('VOL');
      await page.locator('.primary-nav a[data-view="scanner"]').evaluate(element => element.click());
      await expect(output).toHaveCount(1);
      await expect(output).toHaveText('55%');
      expect(await input.evaluate(element => element.isConnected)).toBe(true);
    });
  }
}

test.describe('Landscape phone playback', () => {
  test.use({ hasTouch: true, isMobile: true, viewport: { width: 844, height: 390 } });
  test('keeps the transport at the bottom in a short coarse-pointer viewport', async ({ page }) => {
    await installSiteStyleApplication(page, 'dark');
    await page.goto('/app.html?view=scanner');
    expect(await page.evaluate(() => matchMedia('(pointer: coarse)').matches)).toBe(true);
    await expect(page.locator('.scanner-playback-primary')).toBeVisible();
    const bar = await page.locator('#playback-bar').boundingBox();
    expect(bar.y + bar.height).toBe(390);
    expect(await page.locator('#playback-bar').evaluate(element => getComputedStyle(element).position)).toBe('fixed');
    const volume = await page.locator('.scanner-playback-transport .ui-audio-volume-control').boundingBox();
    const queue = await page.locator('.playback-queue').boundingBox();
    expect(volume.x + volume.width).toBeLessThan(queue.x);
    await expectFullWidthScannerActions(page);
    const reserve = await page.locator('.scanner-page').evaluate(element =>
      parseFloat(getComputedStyle(element).paddingBottom));
    expect(reserve).toBeGreaterThan(bar.height);
    await page.screenshot({ path: test.info().outputPath('scanner-coarse-landscape.png') });
  });
});

for (const theme of ['light', 'dark']) {
 test(`A scrolled live call stays still when Engineer changes to Simple ${theme}`, async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openAudioApp(page, { view: 'scanner', theme });
  await page.locator('.scanner-scan-trigger').click();
  await page.getByRole('dialog', { name: 'Listen to scan lists', exact: true }).getByRole('checkbox').first().check();
  await page.keyboard.press('Escape');
  await page.locator('.scanner-playback-primary').click();
  await expect(page.locator('.scanner-call-title')).toHaveText('Fire Dispatch');
  await page.screenshot({ path: test.info().outputPath(`scanner-active-${theme}-mobile.png`) });
  const modes = page.getByRole('combobox', { name: 'Scanner detail level', exact: true });
  await modes.selectOption('engineer');
  await expect(page.locator('.scanner-display')).toHaveAttribute('data-scanner-mode', 'engineer');
  const tallHeight = await page.locator('.scanner-console').evaluate(element => element.offsetHeight);
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  const scrollY = await page.evaluate(() => window.scrollY);
  expect(scrollY).toBeGreaterThan(0);
  await modes.evaluate(select => {
    select.value = 'simple';
    select.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await expect(page.locator('.scanner-display')).toHaveAttribute('data-scanner-mode', 'simple');
  expect(await page.locator('.scanner-console').evaluate(element => element.offsetHeight)).toBeLessThan(tallHeight);
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(scrollY);
  await page.locator('.scanner-playback-more-trigger').click();
  const more = page.getByRole('dialog', { name: 'More playback controls', exact: true });
  await more.locator('#playback-clear-queue').click();
  await expect(more).toBeHidden();
  await page.locator('.scanner-playback-more-trigger').click();
  await more.locator('#playback-avoid-list').click();
  await expect(more).toBeHidden();
  await expect(page.getByRole('dialog', { name: 'Avoid List', exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: 'Avoid List', exact: true })).toBeHidden();
});
}

test('Revoked call-audio access closes selection and blocks stale checkbox events', async ({ page }) => {
  await page.clock.install();
  const state = await openAudioApp(page, { view: 'scanner' });
  await page.locator('.scanner-scan-trigger').click();
  const lists = page.getByRole('dialog', { name: 'Listen to scan lists', exact: true });
  const checkbox = await lists.getByRole('checkbox').first().elementHandle();
  const trigger = await page.locator('.scanner-scan-trigger').elementHandle();
  const writes = state.preferenceWrites.length;
  state.session.capabilities['call-audio'] = false;
  await page.clock.runFor(10_010);
  await expect.poll(() => checkbox.evaluate(element => element.disabled)).toBe(true);
  expect(await trigger.evaluate(element => element.disabled)).toBe(true);
  expect(await lists.count()).toBe(0);
  await checkbox.evaluate(element => {
    element.checked = !element.checked;
    element.dispatchEvent(new Event('change', { bubbles: true }));
  });
  expect(state.preferenceWrites.length).toBe(writes);
});
