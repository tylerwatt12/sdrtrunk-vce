'use strict';

const { expect, test } = require('@playwright/test');
const { openAudioApp } = require('./fixtures/audio-dock-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const labels = ['Main', 'Live', 'Scanner', 'Topics', 'Recordings', 'Map', 'P25 Visualizer', 'Channels',
  'Aliases', 'Scan Lists', 'RadioReference', 'Alerts', 'Streaming', 'Tuners', 'Browser Spectrum',
  'Administration', 'My Settings', 'About'];
const agreedDescriptions = {
  'Scan Lists': 'Create groups of aliases that listeners can choose to hear.',
  Scanner: 'Listen to calls from your selected Scan Lists.'
};
const completeMenu = {
  capabilities: { 'admin-streaming': true },
  serviceStatus: { stats_logging: { summary_active: true, detailed_history_active: true } },
  channelCatalog: [{ channel_kind: 'TRUNKED', processing_state: 'RUNNING', protocol_id: 'p25-phase2' }]
};

async function expectDescriptions(links, viewportWidth) {
  for (const link of await links.all()) {
    const label = await link.evaluate((element) =>
      document.getElementById(element.getAttribute('aria-labelledby'))?.textContent.trim());
    expect(labels).toContain(label);
    await expect(link).toHaveAccessibleName(label);
    const descriptionId = await link.getAttribute('aria-describedby');
    expect(descriptionId).toBeTruthy();
    const description = link.locator(`[id="${descriptionId}"]`);
    await expect(description).toBeVisible();
    const copy = (await description.textContent()).trim();
    expect(copy.length).toBeGreaterThan(20);
    if (agreedDescriptions[label]) expect(copy).toBe(agreedDescriptions[label]);
    await expect(link).toHaveAccessibleDescription(copy);
    await expect(link.locator(':scope > .nav-icon[aria-hidden="true"] use')).toHaveCount(1);
    const geometry = await link.evaluate((element) => {
      const rect = element.getBoundingClientRect();
      const copy = document.getElementById(element.getAttribute('aria-describedby'));
      const style = getComputedStyle(copy);
      return { left: rect.left, right: rect.right, height: rect.height,
        copyFits: copy.scrollWidth <= copy.clientWidth + 1 && copy.scrollHeight <= copy.clientHeight + 1,
        textOverflow: style.textOverflow, whiteSpace: style.whiteSpace };
    });
    expect(geometry.left).toBeGreaterThanOrEqual(0);
    expect(geometry.right).toBeLessThanOrEqual(viewportWidth);
    expect(geometry.height).toBeGreaterThanOrEqual(44);
    expect(geometry.copyFits, `${label} description must remain fully readable`).toBe(true);
    expect(geometry.textOverflow).not.toBe('ellipsis');
    expect(geometry.whiteSpace).not.toBe('nowrap');
  }
}

for (const theme of ['light', 'dark']) {
  test(`desktop menus explain every destination and retain keyboard access in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await openAudioApp(page, { ...completeMenu, theme });
    const navigation = page.locator('#primary-navigation');
    await expect(navigation.locator('a[data-view]')).toHaveCount(18);
    await expect(navigation.locator('a[data-view="network-visualizer"]')).not.toHaveAttribute('hidden');
    const groups = navigation.locator('.nav-group');
    await expect(groups.locator(':scope > summary')).toHaveText(['Listen', 'Manage', 'Hardware', 'Settings']);
    for (const group of await groups.all()) {
      const summary = group.locator(':scope > summary');
      await summary.focus();
      await expect(group).toHaveAttribute('open');
      const links = group.locator('a[data-view]');
      await expectDescriptions(links, 1280);
      const columns = await links.evaluateAll((elements) =>
        new Set(elements.map(element => Math.round(element.getBoundingClientRect().x))).size);
      expect(columns).toBe(['Listen', 'Manage'].includes(await summary.textContent()) ? 2 : 1);
      await page.keyboard.press('Tab');
      await expect(links.first()).toBeFocused();
      await page.keyboard.press('Escape');
      await expect(group).not.toHaveAttribute('open');
      await expect(summary).toBeFocused();
    }
    await expect(navigation.getByRole('link', { name: 'Recordings', exact: true, includeHidden: true }))
      .toHaveAttribute('aria-current', 'page');
    await expectNoHorizontalOverflow(page);
  });
}

for (const viewport of [{ width: 320, height: 568 }, { width: 844, height: 390 }]) {
  test(`drawer descriptions wrap and remain tappable at ${viewport.width}×${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openAudioApp(page, { ...completeMenu, theme: 'dark' });
    const navigation = page.locator('#primary-navigation');
    await expect(navigation).toHaveAttribute('inert', '');
    await page.locator('#navigation-toggle').click();
    await expect.poll(() => navigation.evaluate(element => Math.round(element.getBoundingClientRect().left))).toBe(0);
    for (const group of await navigation.locator('.nav-group').all()) {
      const summary = group.locator(':scope > summary');
      if (!await group.evaluate(element => element.open)) await summary.click();
      await expectDescriptions(group.locator('a[data-view]'), viewport.width);
      for (const link of await group.locator('a[data-view]').all()) {
        await link.scrollIntoViewIfNeeded();
        expect(await link.evaluate((element) => {
          const rect = element.getBoundingClientRect();
          return element.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2));
        }), `${await link.getAttribute('data-view')} remains tappable`).toBe(true);
      }
    }
    await expectNoHorizontalOverflow(page);
    await page.keyboard.press('Escape');
    await expect(navigation).toHaveAttribute('inert', '');
    await expect(page.locator('#navigation-toggle')).toBeFocused();
  });
}

test('short desktop menus scroll their final destination into reach', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 360 });
  await openAudioApp(page, completeMenu);
  const group = page.locator('#primary-navigation [data-nav-group="listen"]');
  await group.locator(':scope > summary').focus();
  const last = group.getByRole('link', { name: 'P25 Visualizer', exact: true });
  await last.scrollIntoViewIfNeeded();
  const panel = group.locator('.nav-group-links');
  expect(await panel.evaluate(element => element.scrollTop)).toBeGreaterThan(0);
  expect(await last.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return rect.top >= 0 && rect.bottom <= innerHeight &&
      element.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2));
  })).toBe(true);
});
