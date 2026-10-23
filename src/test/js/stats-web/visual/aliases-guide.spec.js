'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const guide = page => page.locator('.ui-setup-guide[data-guide-id="aliases"]');
const samples = page => page.locator('.alias-editor-workspace[data-guide-sample]');
const target = (page, name) => page.locator(`[data-guide-target="${name}"]`).first();
const memoryKey = id => `sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:${id}:${id === 'aliases' ? 2 : 1}`;
const steps = [
  { id: 'lists', targets: ['aliases-lists', 'aliases-new-list', 'aliases-usage'] },
  { id: 'configure', targets: ['aliases-configure', 'aliases-filters', 'aliases-defaults'] },
  { id: 'alias-editor', targets: ['aliases-editor-tabs'] },
  { id: 'defaults-unmatched', targets: ['aliases-defaults-tabs', 'aliases-default-record',
    'aliases-default-scan', 'aliases-default-stream'] },
  { id: 'defaults-new', targets: ['aliases-defaults-tabs', 'aliases-default-record',
    'aliases-default-scan', 'aliases-default-stream'] },
  { id: 'bulk', targets: ['aliases-selection', 'aliases-bulk', 'aliases-select-all'] },
  { id: 'activity', targets: ['aliases-activity', 'aliases-activity-columns'] },
  { id: 'custom', targets: ['aliases-custom', 'aliases-columns'] },
  { id: 'discover', targets: ['aliases-discover', 'aliases-discover-table'] },
  { id: 'transfer', targets: ['aliases-add', 'aliases-import', 'aliases-export'] },
  { id: 'listening', targets: ['aliases-memberships', 'player-lists'] }
];

async function openAliases(page, { theme = 'light', replay = true, firstVisit = false,
  empty = false, customLayout = false, query = 'list=1' } = {}) {
  const requests = { writes: [], samplePaths: [], radioReference: [] };
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/v1/') && !['GET', 'HEAD'].includes(request.method())) {
      requests.writes.push(`${request.method()} ${url.pathname}`);
    }
    if (/guide-sample|example-county/.test(url.pathname)) requests.samplePaths.push(url.pathname);
    if (/radioreference\.com$/i.test(url.hostname) ||
        url.pathname.startsWith('/api/v1/') && /radioreference/i.test(url.pathname)) {
      requests.radioReference.push(url.pathname);
    }
  });
  const fixture = await installSiteStyleApplication(page, theme, undefined, { guides: firstVisit });
  if (customLayout) {
    const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
    const preferences = structuredClone((await import(pathToFileURL(file).href)).defaults);
    preferences.appearance.theme = theme;
    const schema = ['select', 'alias-id', 'alias-list', 'family', 'alias', 'description', 'group', 'color',
      'icon', 'matcher', 'matcher-type', 'identity-type', 'protocol', 'protocol-variant', 'identifier',
      'exact', 'ranged', 'value', 'minimum', 'maximum', 'text-value', 'numeric-value', 'tone-sequence',
      'scan-lists', 'record', 'broadcast-channels', 'stream-as-talkgroup', 'behavior', 'overlap',
      'calls', 'signaling', 'last-seen'];
    preferences.tables['alias-editor-custom'] = { schema,
      column_order: ['select', 'alias', 'calls', ...schema.filter(id => !['select', 'alias', 'calls'].includes(id))],
      column_widths: { alias: 225, calls: 100 },
      hidden_columns: schema.filter(id => !['select', 'alias', 'calls'].includes(id)), collapsed_groups: [] };
    await page.route('**/api/v1/me/preferences', route =>
      route.fulfill({ json: { revision: 1, preferences } }));
  }
  if (firstVisit) await page.addInitScript(() => {
    for (const id of ['welcome', 'channel-setup']) {
      localStorage.setItem(`sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:${id}:1`, '1');
    }
    // A dismissed coming-soon placeholder must not suppress the authored tour.
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:aliases:1', '1');
  });
  if (empty) await page.route(/\/api\/v1\/(?:admin\/)?alias-lists(?:\?|$)/, route => {
    const admin = new URL(route.request().url()).pathname.includes('/admin/');
    return route.fulfill({ json: { data: admin ? { revision: 1, alias_lists: [] } :
      { rows: [], total_count: 0, limit: 500, offset: 0, has_more: false, next_offset: null } } });
  });
  // The fixture server mounts the production index at /app.html; / is its gallery.
  await page.goto(`/app.html?view=aliases${query ? `&${query}` : ''}${replay ? '&guide=aliases' : ''}`);
  return { fixture, requests };
}

async function expectWithinViewport(locator, viewport) {
  await expect.poll(async () => {
    const bounds = await locator.boundingBox();
    return bounds !== null && bounds.x >= 0 && bounds.y >= 0 &&
      bounds.x + bounds.width <= viewport.width + 1 && bounds.y + bounds.height <= viewport.height + 1;
  }).toBe(true);
}

async function expectSpotlight(page, name) {
  const items = page.locator(`[data-guide-target="${name}"]`);
  await expect(items.first()).toBeVisible();
  for (const item of await items.all()) {
    await expect(item).toBeVisible();
    await expect.poll(() => item.evaluate(element => {
      const rect = element.getBoundingClientRect();
      const clipped = { left: Math.max(0, rect.left), top: Math.max(0, rect.top),
        right: Math.min(innerWidth, rect.right), bottom: Math.min(innerHeight, rect.bottom) };
      if (clipped.right <= clipped.left || clipped.bottom <= clipped.top) return false;
      return [...document.querySelectorAll('.ui-setup-guide-highlight')].some(highlight => {
        const bounds = highlight.getBoundingClientRect();
        return bounds.left <= clipped.left + 1 && bounds.top <= clipped.top + 1 &&
          bounds.right >= clipped.right - 1 && bounds.bottom >= clipped.bottom - 1;
      });
    }), { message: `${name} has a visible spotlight` }).toBe(true);
  }
}

async function expectStep(page, step, index) {
  await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
  await expect(guide(page)).toContainText(`Step ${index + 1} of ${steps.length}`);
  await expect(samples(page)).toHaveCount(1);
  await expect(samples(page)).toContainText('Example data');
  if (step.id !== 'listening') await expect(page.locator('#audio-dock')).toBeHidden();
  for (const name of step.targets) await expectSpotlight(page, name);
  await expectWithinViewport(guide(page), page.viewportSize());
  await expectWithinViewport(guide(page).getByRole('button', { name: 'Close guide', exact: true }), page.viewportSize());
  await expectWithinViewport(guide(page).getByRole('button', {
    name: index === steps.length - 1 ? 'Finish' : 'Next', exact: true
  }), page.viewportSize());
  await expect(page.locator('.primary-nav details[open]')).toHaveCount(0);
}

async function goToStep(page, id) {
  for (const step of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    if (step.id === id) return;
    await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
  }
  throw new Error(`Unknown Aliases guide step: ${id}`);
}

function expectNoEffects(fixture, requests) {
  expect(requests.writes).toEqual([]);
  expect(requests.samplePaths).toEqual([]);
  expect(requests.radioReference).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
}

for (const { theme, width, height } of [
  { theme: 'light', width: 1280, height: 800 },
  { theme: 'dark', width: 1440, height: 1000 }
]) {
  test(`Aliases tour shows each populated section without changing receiver state ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height });
    const { fixture, requests } = await openAliases(page, { theme });
    const player = page.locator('#audio-dock');
    const playerBefore = await player.evaluate(element => ({ source: element.dataset.source,
      state: element.dataset.state }));
    for (const [index, step] of steps.entries()) {
      await expectStep(page, step, index);
      if (step.id === 'configure') {
        await expect(target(page, 'aliases-table')).toContainText('Fire Dispatch');
        await expect(target(page, 'aliases-table')).toContainText('Public Works');
      }
      if (['lists', 'defaults-unmatched', 'defaults-new', 'bulk', 'custom', 'discover', 'listening'].includes(step.id)) {
        await page.screenshot({ path: testInfo.outputPath(`aliases-${step.id}-${theme}.png`) });
      }
      if (step.id === 'alias-editor') {
        await expect(target(page, 'aliases-editor-tabs')).toContainText('Basics');
        await expect(target(page, 'aliases-editor-tabs')).toContainText('Identifier');
        await expect(target(page, 'aliases-editor-tabs')).toContainText('Call Handling');
        await expect(target(page, 'aliases-editor-tabs')).toContainText('Activity');
      }
      if (step.id === 'defaults-unmatched') {
        await expect(guide(page)).toContainText('catch-all');
        await expect(guide(page)).toContainText('without creating aliases');
        await expectWithinViewport(samples(page).locator('.ui-notice-danger'), page.viewportSize());
      }
      if (step.id === 'defaults-new') {
        await expect(guide(page)).toContainText('does not update existing aliases');
        await expect(target(page, 'aliases-defaults-tabs')).toContainText('New Aliases');
      }
      if (step.id === 'activity') {
        await expect(page.locator('[data-guide-target="aliases-activity-columns"]'))
          .toHaveText(['Calls', 'Signaling', 'Last Seen']);
      }
      if (step.id === 'custom') {
        await expect.poll(() => target(page, 'aliases-columns').evaluate(element => {
          const player = document.querySelector('#audio-dock');
          if (!player?.getClientRects().length) return [];
          const dock = player.getBoundingClientRect();
          return [...element.querySelectorAll('button, input')].filter(control => {
            const bounds = control.getBoundingClientRect();
            const x = bounds.left + bounds.width / 2, y = bounds.top + bounds.height / 2;
            return bounds.width && bounds.height && x > dock.left && x < dock.right &&
              y > dock.top && y < dock.bottom;
          }).map(control => control.getAttribute('aria-label') || control.textContent);
        }), { message: 'The audio player leaves the demonstrated column controls visible' }).toEqual([]);
      }
      if (step.id === 'discover') await expect(samples(page)).toContainText('Covered by range');
      if (step.id === 'transfer') {
        await expect(guide(page)).toContainText('RadioReference');
        await expect(guide(page)).toContainText('CSV');
        await expect(guide(page)).toContainText('Premium');
      }
      if (step.id === 'listening') {
        await expect(guide(page)).toContainText('Scan Lists replace');
        await expect(guide(page)).toContainText('Available to listeners');
      }
      await guide(page).getByRole('button', {
        name: index === steps.length - 1 ? 'Finish' : 'Next', exact: true
      }).click();
    }
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator('[data-guide-sample]')).toHaveCount(0);
    await expect(page.locator('.alias-list-summary')).toContainText('County Public Safety');
    await expect(player).toHaveAttribute('data-source', playerBefore.source);
    await expect(player).toHaveAttribute('data-state', playerBefore.state);
    await expect(player).toBeVisible();
    await expect(player).toHaveAttribute('data-super-collapsed', 'false');
    expectNoEffects(fixture, requests);
  });
}

test('sample controls cannot save, navigate, import, or change defaults even when dispatched directly', async ({ page }) => {
  const { fixture, requests } = await openAliases(page);
  await expect(guide(page)).toHaveAttribute('data-guide-step', steps[0].id);
  const before = page.url();
  let exercised = 0;
  for (const [index, step] of steps.entries()) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    const count = await samples(page).evaluate(root => {
      const controls = [...root.querySelectorAll('button, a, input, select, form')];
      for (const control of controls) {
        control.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
        if (control.tagName === 'FORM') control.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      }
      return controls.length;
    });
    exercised += count;
    await expect(page).toHaveURL(before);
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    await guide(page).getByRole('button', {
      name: index === steps.length - 1 ? 'Finish' : 'Next', exact: true
    }).click();
  }
  expect(exercised).toBeGreaterThan(20);
  await expect(page.locator('[data-guide-sample]')).toHaveCount(0);
  expectNoEffects(fixture, requests);
});

for (const dismissal of ['Close guide', 'Escape', 'darkened area']) {
  test(`Aliases sample restores the real workspace after ${dismissal}`, async ({ page }) => {
    const { fixture, requests } = await openAliases(page, { replay: false,
      customLayout: true, query: 'list=1&aliasTab=custom&q=fire&group=Fire' });
    const search = page.locator('.alias-editor-filter-toolbar input[name="q"]');
    const table = page.locator('.alias-editor-table-host table');
    await expect(table).toHaveAttribute('data-table-type', 'alias-editor-custom');
    expect(await table.locator('thead th[data-column]').evaluateAll(headers =>
      headers.map(header => header.dataset.column))).toEqual(['select', 'alias', 'calls']);
    await page.getByRole('checkbox', { name: 'Select Fire Dispatch', exact: true }).check();
    await search.fill('unfinished search');
    const routeBefore = page.url();
    const retained = await page.locator('.alias-editor-workspace').evaluate(element => {
      window.aliasGuideOriginalChildren = [...element.childNodes];
      return { headings: [...element.querySelectorAll('th')].map(header => header.textContent),
        search: element.querySelector('input[name="q"]').value,
        table: element.querySelector('table').dataset.tableType };
    });
    await page.locator('#page-guide-button').click();
    await goToStep(page, 'custom');
    await expect(samples(page)).toBeVisible();
    await guide(page).getByRole('button', { name: 'Back', exact: true }).click();
    await expect(guide(page)).toHaveAttribute('data-guide-step', 'activity');
    await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
    await expect(guide(page)).toHaveAttribute('data-guide-step', 'custom');
    if (dismissal === 'Escape') await page.keyboard.press('Escape');
    else if (dismissal === 'darkened area') await page.mouse.click(8, 500);
    else await guide(page).getByRole('button', { name: dismissal, exact: true }).click();
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator('[data-guide-sample]')).toHaveCount(0);
    await expect(page.locator('.ui-setup-guide-backdrop')).toHaveCount(0);
    await expect(page).toHaveURL(routeBefore);
    await expect(search).toHaveValue(retained.search);
    await expect(table).toHaveAttribute('data-table-type', retained.table);
    expect(await table.locator('th').allTextContents()).toEqual(retained.headings);
    await expect(page.getByRole('checkbox', { name: 'Select Fire Dispatch', exact: true })).toBeChecked();
    await expect(page.locator('.alias-bulk-bar')).toContainText('1 selected');
    expect(await page.locator('.alias-editor-workspace').evaluate(element =>
      window.aliasGuideOriginalChildren.every((node, index) => element.childNodes[index] === node))).toBe(true);
    await expect(page.locator('#page-guide-button')).toBeFocused();
    await expect(page.locator('#audio-dock')).toBeVisible();
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-source', 'live');
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-state', 'minimal');
    expectNoEffects(fixture, requests);
  });
}

test('Aliases tour works with no real lists and Help replays after dismissal', async ({ page }) => {
  const { fixture, requests } = await openAliases(page, { empty: true, query: '' });
  await expectStep(page, steps[0], 0);
  await guide(page).getByRole('button', { name: 'Close guide', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'No alias lists are configured', exact: true })).toBeVisible();
  await expect(page.locator('[data-guide-sample]')).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await expectStep(page, steps[0], 0);
  for (let index = 0; index < 5; index += 1) {
    await page.keyboard.press('Tab');
    expect(await page.evaluate(() => Boolean(document.activeElement.closest('.ui-setup-guide')))).toBe(true);
  }
  await page.keyboard.press('Escape');
  await expect(guide(page)).toHaveCount(0);
  expectNoEffects(fixture, requests);
});

test('a first Aliases visit shows the authored tour once after the welcome tour', async ({ page }) => {
  const { fixture, requests } = await openAliases(page, { firstVisit: true, replay: false });
  await expectStep(page, steps[0], 0);
  await guide(page).getByRole('button', { name: 'Close guide', exact: true }).click();
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey('aliases'))).toBe('1');
  await page.reload();
  await expect(page.locator('#page-guide-button')).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  await page.goto('/app.html?view=aliases&guide=aliases');
  await expectStep(page, steps[0], 0);
  expectNoEffects(fixture, requests);
});

test('navigation releases the Aliases samples before another page renders', async ({ page }) => {
  const { fixture, requests } = await openAliases(page);
  await goToStep(page, 'defaults-new');
  await page.locator('.primary-nav [data-view="dashboard"]').evaluate(link => link.click());
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator('[data-guide-sample]')).toHaveCount(0);
  await page.locator('.primary-nav [data-view="aliases"]').evaluate(link => link.click());
  await expect(page.getByRole('heading', { name: 'Alias Editor', exact: true })).toBeVisible();
  await expect(page.locator('.alias-list-rail')).toContainText('County Public Safety');
  await expect(page.locator('[data-guide-sample]')).toHaveCount(0);
  expectNoEffects(fixture, requests);
});

test('the tour restores an expanded Recordings player without loading or playing audio', async ({ page }) => {
  const { fixture, requests } = await openAliases(page, { replay: false });
  const audioRequests = [];
  page.on('request', request => {
    if (/\/api\/v1\/.*\/audio(?:\?|$)/.test(request.url())) audioRequests.push(request.url());
  });
  const player = page.locator('#audio-dock');
  await player.getByRole('combobox', { name: 'Audio source', exact: true }).selectOption('recordings');
  await expect(page).toHaveURL(/view=recordings/);
  await player.getByRole('button', { name: 'Expand player', exact: true }).first().click();
  await expect(player).toHaveAttribute('data-state', 'full');
  await page.locator('.primary-nav [data-view="aliases"]').evaluate(link => link.click());
  await expect(page.getByRole('heading', { name: 'Alias Editor', exact: true })).toBeVisible();
  await expect(player).toHaveAttribute('data-source', 'recordings');
  const before = audioRequests.length;
  await page.locator('#page-guide-button').click();
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'lists');
  await expect(player).toBeHidden();
  await goToStep(page, 'listening');
  await expect(player).toBeVisible();
  await expect(player).toHaveAttribute('data-source', 'live');
  await expect(player).toHaveAttribute('data-state', 'minimal');
  await guide(page).getByRole('button', { name: 'Finish', exact: true }).click();
  await expect(guide(page)).toHaveCount(0);
  await expect(player).toBeVisible();
  await expect(player).toHaveAttribute('data-source', 'recordings');
  await expect(player).toHaveAttribute('data-state', 'full');
  expect(audioRequests.length).toBe(before);
  expectNoEffects(fixture, requests);
});

test('phone visits do not show the desktop Aliases tour or its samples', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const { fixture, requests } = await openAliases(page, { firstVisit: true });
  await expect(page.getByRole('heading', { name: 'Alias Editor', exact: true })).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator('[data-guide-sample]')).toHaveCount(0);
  await expect(page.locator('#page-guide-button')).toBeHidden();
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey('aliases'))).toBeNull();
  expectNoEffects(fixture, requests);
});

for (const theme of ['light', 'dark']) {
  test(`Aliases sample panels retain readable contrast in ${theme} and representative hues`, async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const { fixture, requests } = await openAliases(page, { theme });
    const audited = ['configure', 'alias-editor', 'defaults-unmatched', 'defaults-new', 'bulk', 'custom', 'discover'];
    for (const [index, step] of steps.entries()) {
      await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
      if (audited.includes(step.id)) {
        await page.mouse.move(0, 0);
        // Audit the actual sample paint before the tour dimmer is applied. The
        // preview remains protected by its registry, and receives no UI events.
        // Temporarily release inert solely so its visible copy is measured.
        await page.evaluate(() => {
          const backdrop = document.querySelector('.ui-setup-guide-backdrop');
          window.aliasGuideContrastState = { backdrop, visibility: backdrop.style.visibility,
            inert: [...document.querySelectorAll('[inert]')] };
          backdrop.style.visibility = 'hidden';
          window.aliasGuideContrastState.inert.forEach(element => { element.inert = false; });
        });
        try {
          for (const hue of [null, 0, 120, 240]) {
            await page.evaluate(async value => {
              const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
              applyThemeHue(value);
            }, hue);
            const result = await expectContrast(page, { rootSelector: '.alias-editor-workspace[data-guide-sample]',
              reportName: `aliases-guide-${step.id}-${theme}-hue-${hue}` });
            expect(result.summary.text.checked).toBeGreaterThan(10);
          }
        } finally {
          await page.evaluate(async () => {
            const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
            applyThemeHue(null);
            const state = window.aliasGuideContrastState;
            state.inert.forEach(element => { element.inert = true; });
            state.backdrop.style.visibility = state.visibility;
            delete window.aliasGuideContrastState;
          });
        }
      }
      await guide(page).getByRole('button', {
        name: index === steps.length - 1 ? 'Finish' : 'Next', exact: true
      }).click();
    }
    expectNoEffects(fixture, requests);
  });
}
