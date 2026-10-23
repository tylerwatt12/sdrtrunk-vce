'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { execFileSync } = require('node:child_process');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

test.use({ viewport: { width: 1280, height: 800 } });

const guide = page => page.locator('.ui-setup-guide[data-guide-id="scan-lists"]');
const memoryKey = 'sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:scan-lists:2';
const steps = [
  { id: 'lists', targets: ['scan-lists-selector', 'scan-lists-search', 'scan-lists-filters'] },
  { id: 'sources', targets: ['scan-lists-counts', 'scan-lists-routes'] },
  { id: 'details', targets: ['scan-lists-details-fields'] },
  { id: 'availability', targets: ['scan-lists-availability'] },
  { id: 'default', targets: ['scan-lists-default'] },
  { id: 'members', targets: ['scan-lists-member-table', 'scan-lists-member-alias-list'] },
  { id: 'add-alias-list', targets: ['scan-lists-add-list-dialog'] },
  { id: 'alias-membership', targets: ['scan-lists-alias-membership'] },
  { id: 'remove-members', targets: ['scan-lists-remove-selected', 'scan-lists-remove-all'] },
  { id: 'listening', targets: ['scan-lists-player-lists'] }
];

async function sampleData() {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/guides/scan-lists-data.js');
  // Playwright's CommonJS hook rewrites nested browser-cache imports. Load the
  // unchanged production fixture with Node's native ES module loader instead.
  return JSON.parse(execFileSync(process.execPath, ['--input-type=module', '-e',
    'const module = await import(process.argv[1]); process.stdout.write(JSON.stringify(module.getScanListsGuideData()));',
    pathToFileURL(file).href], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }));
}

async function installOrdinarySampleCatalog(page) {
  const sample = await sampleData();
  await page.route('**/api/v1/**', route => {
    const url = new URL(route.request().url());
    const pathname = url.pathname;
    let data;
    if (pathname === '/api/v1/admin/scan-lists') data = sample.scanListCatalog;
    else if (pathname === '/api/v1/scan-lists') data = { revision: 0,
      scan_lists: sample.scanLists.filter(row => row.published) };
    else if (pathname === '/api/v1/admin/alias-lists') data = { revision: 0, alias_lists: sample.lists };
    else if (pathname === '/api/v1/alias-lists') data = { rows: sample.lists,
      total_count: sample.lists.length, limit: 500, offset: 0, has_more: false, next_offset: null };
    else if (pathname === '/api/v1/aliases') data = sample.memberPage;
    else if (pathname === '/api/v1/admin/aliases/options') data = sample.options;
    else if (pathname === `/api/v1/admin/aliases/${sample.rows[0].alias_id}`) data = sample.recordResponse;
    else if (pathname === `/api/v1/aliases/${sample.rows[0].alias_id}`) data = sample.analytics;
    else return route.fallback();
    return route.fulfill({ json: { data } });
  });
  return sample;
}

async function openScanLists(page, { theme = 'light', replay = true, firstVisit = false,
  ordinarySamples = false, origin = 'scan-lists', empty = false } = {}) {
  const requests = { writes: [], audio: [], samples: [], external: [] };
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/v1/') && !['GET', 'HEAD'].includes(request.method())) {
      requests.writes.push(`${request.method()} ${url.pathname}`);
    }
    if (/\/audio(?:$|\/)/.test(url.pathname)) requests.audio.push(url.pathname);
    if (/\/90000[123](?:$|\/)|\/91000[1234](?:$|\/)/.test(url.pathname) ||
        [...url.searchParams.values()].some(value => /^(?:90000[123]|91000[1234])$/.test(value))) {
      requests.samples.push(`${url.pathname}${url.search}`);
    }
    if (/radioreference\.com$/i.test(url.hostname)) requests.external.push(url.pathname);
  });
  const fixture = await installSiteStyleApplication(page, theme, undefined, { guides: firstVisit });
  if (firstVisit) await page.addInitScript(() => {
    for (const [id, version] of [['welcome', 1], ['channel-setup', 1], ['aliases', 3]]) {
      localStorage.setItem(`sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:${id}:${version}`, '1');
    }
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:scan-lists:1', '1');
  });
  if (ordinarySamples) await installOrdinarySampleCatalog(page);
  if (empty) await page.route('**/api/v1/admin/scan-lists', route =>
    route.fulfill({ json: { data: { revision: 1, scan_lists: [] } } }));
  const query = origin === 'members' ? 'view=aliases&scanListId=1' : 'view=scan-lists';
  await page.goto(`/app.html?${query}${replay ? '&guide=scan-lists' : ''}`);
  return { fixture, requests };
}

async function expectSpotlight(page, name) {
  const items = page.locator(`[data-guide-target="${name}"]:visible`);
  await expect(items.first()).toBeVisible();
  for (const item of await items.all()) {
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
    await expect.poll(() => item.evaluate(element => {
      const target = element.getBoundingClientRect();
      const card = document.querySelector('.ui-setup-guide').getBoundingClientRect();
      const width = Math.min(target.right, card.right) - Math.max(target.left, card.left);
      const height = Math.min(target.bottom, card.bottom) - Math.max(target.top, card.top);
      return width <= 1 || height <= 1;
    }), { message: `${name} remains visible beside the guide card` }).toBe(true);
  }
}

async function expectStep(page, step, index) {
  await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
  await expect(guide(page)).toContainText(`Step ${index + 1} of ${steps.length}`);
  await expect(page.locator('[data-guide-sample]').first()).toBeVisible();
  await expect(page.locator('.alias-guide-preview, .scan-lists-guide-preview')).toHaveCount(0);
  for (const name of step.targets) await expectSpotlight(page, name);
  const bounds = await guide(page).boundingBox();
  const viewport = page.viewportSize();
  expect(bounds.x).toBeGreaterThanOrEqual(0);
  expect(bounds.y).toBeGreaterThanOrEqual(0);
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width + 1);
  expect(bounds.y + bounds.height).toBeLessThanOrEqual(viewport.height + 1);
  await expect(guide(page).getByRole('button', { name: 'Close guide', exact: true })).toBeVisible();
  await expect(guide(page).getByRole('button', {
    name: index === steps.length - 1 ? 'Finish' : 'Next', exact: true
  })).toBeVisible();
  await expect(page.locator('.primary-nav details[open]')).toHaveCount(0);
}

async function goToStep(page, id) {
  for (const step of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    if (step.id === id) return;
    await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
  }
  throw new Error(`Unknown Scan Lists stop ${id}`);
}

function expectNoEffects(fixture, requests, { ordinarySamples = false } = {}) {
  expect(requests.writes).toEqual([]);
  expect(requests.audio).toEqual([]);
  if (!ordinarySamples) expect(requests.samples).toEqual([]);
  expect(requests.external).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
}

async function surfaceSignature(locator) {
  await expect(locator).toBeVisible();
  if (await locator.evaluate(root => root.matches('.read-only-modal'))) {
    await expect(locator.locator('.modal-content form')).toBeVisible();
  }
  return locator.evaluate(root => {
    const styles = getComputedStyle(root);
    return {
      classes: [...root.classList].filter(name => name !== 'ui-setup-guide-layer').sort(),
      structure: [...root.querySelectorAll('form, nav, fieldset, legend, .ui-field, .alias-editor-panel, .ui-modal-footer, .ui-audio-selection-row')]
        .map(element => [element.tagName, element.className]),
      fields: [...root.querySelectorAll('input, select, textarea')].map(element =>
        [element.tagName, element.getAttribute('name'), element.getAttribute('type'), element.className]),
      tabs: [...root.querySelectorAll('[role="tab"]')].map(element => element.textContent),
      actions: [...root.querySelectorAll('.ui-modal-footer button')].map(element => element.textContent),
      paint: { font: styles.fontFamily, background: styles.backgroundColor, color: styles.color,
        radius: styles.borderRadius, shadow: styles.boxShadow }
    };
  });
}

for (const { theme, width, height } of [
  { theme: 'light', width: 1280, height: 720 },
  { theme: 'light', width: 1280, height: 800 },
  { theme: 'dark', width: 1440, height: 1000 }
]) {
  test(`Scan Lists tour uses the actual populated interface at every stop in ${theme} ${width}x${height}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height });
    const { fixture, requests } = await openScanLists(page, { theme });
    for (const [index, step] of steps.entries()) {
      await expectStep(page, step, index);
      if (['lists', 'sources', 'details', 'availability', 'default', 'listening'].includes(step.id)) {
        await expect(page.locator('.scan-list-catalog')).toBeVisible();
        await expect(page.locator('.scan-list-selector')).toHaveCount(3);
      }
      if (step.id === 'sources') {
        await expect(page.locator('.scan-list-detail-title')).toHaveText('Unidentified Traffic');
        await expect(page.locator('.scan-list-detail-routes')).toContainText('Example County');
      }
      if (['details', 'availability', 'default'].includes(step.id)) {
        const modal = page.locator('.scan-list-admin-modal');
        await expect(modal).toBeVisible();
        await expect(modal.getByRole('textbox', { name: 'Name', exact: false })).toHaveValue('Fire & EMS');
        await expect(modal.getByRole('spinbutton')).toHaveValue('10');
        await expect(modal.locator('.ui-modal-footer')).toBeVisible();
      }
      if (['members', 'add-alias-list', 'alias-membership', 'remove-members'].includes(step.id)) {
        await expect(page.getByRole('heading', { name: 'Scan List Members', exact: true })).toBeVisible();
        const table = page.locator('table[data-table-type="alias-scan-list-members"]');
        await expect(table).toContainText('Fire Dispatch');
        await expect(table).toContainText('Regional Services');
        await expect(table).toContainText('Example County');
      }
      if (step.id === 'add-alias-list') {
        const modal = page.locator('.alias-full-membership-form');
        await expect(modal.getByRole('combobox')).toHaveValue('900001');
        await expect(modal.getByRole('button', { name: 'Add All Aliases', exact: true })).toBeVisible();
      }
      if (step.id === 'alias-membership') {
        const modal = page.locator('.alias-record-modal');
        await expect(modal).toBeVisible();
        await expect(modal.locator('[role="tab"][aria-selected="true"]')).toHaveText('Call Handling');
        const membership = page.locator('[data-guide-target="scan-lists-alias-membership"]');
        await expect(membership).toContainText('Public Safety');
        await expect(membership).toContainText('Fire & EMS');
      }
      if (step.id === 'remove-members') {
        await expect(page.locator('.scan-list-member-bulk-bar')).toContainText('2 selected');
        await expect(page.getByRole('checkbox', { name: 'Select Fire Dispatch', exact: true })).toBeChecked();
        await expect(page.getByRole('checkbox', { name: 'Select EMS Dispatch', exact: true })).toBeChecked();
      }
      if (step.id === 'listening') {
        const player = page.locator('#audio-dock');
        await expect(player).toBeVisible();
        await expect(player).toHaveAttribute('data-source', 'live');
        const choices = player.locator('.audio-dock-selection-list');
        await expect(choices).toContainText('Public Safety');
        await expect(choices).toContainText('Fire & EMS');
        await expect(choices).not.toContainText('Unidentified Traffic');
        await expect(choices.getByRole('checkbox')).toHaveCount(2);
      } else await expect(page.locator('#audio-dock')).toBeHidden();
      await page.screenshot({ path: testInfo.outputPath(`scan-lists-${step.id}-${theme}.png`) });
      await guide(page).getByRole('button', {
        name: index === steps.length - 1 ? 'Finish' : 'Next', exact: true
      }).click();
    }
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
    expectNoEffects(fixture, requests);
  });
}

for (const theme of ['light', 'dark']) {
  test(`Scan Lists tour keeps production dialog and player structure and paint in ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const { fixture, requests } = await openScanLists(page, { theme, replay: false, ordinarySamples: true });
    await page.locator('.scan-list-selector[data-scan-list-id="900002"]').click();
    await page.getByRole('button', { name: 'Edit Fire & EMS details', exact: true }).click();
    const modal = page.locator('.scan-list-admin-modal');
    const details = await surfaceSignature(modal);
    await page.screenshot({ path: testInfo.outputPath(`ordinary-details-${theme}.png`) });
    await modal.locator('.modal-close').click();
    await page.locator('.scan-list-selector[data-scan-list-id="900001"]').click();
    await page.locator('.admin-scan-list-members').click();
    await expect(page.locator('table[data-table-type="alias-scan-list-members"]')).toBeVisible();
    await page.getByRole('button', { name: 'Add an Entire Alias List', exact: true }).click();
    const addAll = page.locator('.alias-confirm-modal');
    const adding = await surfaceSignature(addAll);
    await page.screenshot({ path: testInfo.outputPath(`ordinary-add-alias-list-${theme}.png`) });
    await addAll.locator('.modal-close').click();
    await page.locator('table .alias-cell').filter({ hasText: 'Fire Dispatch' }).first().click();
    const editor = page.locator('.alias-record-modal');
    await expect(editor.locator('.modal-content form')).toBeVisible();
    await editor.getByRole('tab', { name: 'Call Handling', exact: true }).click();
    const membership = await surfaceSignature(editor);
    await page.screenshot({ path: testInfo.outputPath(`ordinary-alias-membership-${theme}.png`) });
    await editor.locator('.modal-close').click();
    await page.locator('.primary-nav [data-view="scan-lists"]').evaluate(link => link.click());
    const player = page.locator('#audio-dock');
    await player.locator('.audio-dock-lists-trigger:visible').first().click();
    const choices = player.locator('.audio-dock-selection-list');
    const listening = await surfaceSignature(choices);
    await page.screenshot({ path: testInfo.outputPath(`ordinary-listening-${theme}.png`) });
    await page.locator('#page-guide-button').click();
    for (const [index, step] of steps.entries()) {
      await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
      if (['details', 'availability', 'default'].includes(step.id)) {
        expect(await surfaceSignature(page.locator('.scan-list-admin-modal'))).toEqual(details);
      }
      if (step.id === 'add-alias-list') expect(await surfaceSignature(page.locator('.alias-confirm-modal'))).toEqual(adding);
      if (step.id === 'alias-membership') expect(await surfaceSignature(page.locator('.alias-record-modal'))).toEqual(membership);
      if (step.id === 'listening') expect(await surfaceSignature(page.locator('#audio-dock .audio-dock-selection-list'))).toEqual(listening);
      if (['details', 'add-alias-list', 'alias-membership', 'listening'].includes(step.id)) {
        await page.screenshot({ path: testInfo.outputPath(`guide-${step.id}-${theme}.png`) });
      }
      await guide(page).getByRole('button', { name: index === steps.length - 1 ? 'Finish' : 'Next', exact: true }).click();
    }
    expectNoEffects(fixture, requests, { ordinarySamples: true });
  });
}

test('Scan Lists sample controls cannot save, navigate, delete, change membership, or player preferences', async ({ page }) => {
  const { fixture, requests } = await openScanLists(page);
  await expectStep(page, steps[0], 0);
  const before = page.url();
  const originalStorage = await page.evaluate(() => Object.fromEntries(Object.entries(localStorage)));
  let exercised = 0;
  for (const [index, step] of steps.entries()) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    exercised += await page.evaluate(() => {
      const roots = [...document.querySelectorAll('[data-guide-sample], .scan-list-admin-modal, .alias-confirm-modal, .alias-record-modal')];
      const controls = [...new Set(roots.flatMap(root => [...root.querySelectorAll('button, a, input, select, form')]))];
      for (const control of controls) {
        control.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
        if (control.tagName === 'FORM') control.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      }
      return controls.length;
    });
    await expect(page).toHaveURL(before);
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    await guide(page).getByRole('button', { name: index === steps.length - 1 ? 'Finish' : 'Next', exact: true }).click();
  }
  expect(exercised).toBeGreaterThan(30);
  const stored = await page.evaluate(() => Object.fromEntries(Object.entries(localStorage)
    .filter(([key]) => !key.startsWith('sdrtrunk-vce-guide:'))));
  expect(stored).toEqual(Object.fromEntries(Object.entries(originalStorage)
    .filter(([key]) => !key.startsWith('sdrtrunk-vce-guide:'))));
  expectNoEffects(fixture, requests);
});

for (const { stop, dismiss } of [
  { stop: 'details', dismiss: 'Close guide' },
  { stop: 'add-alias-list', dismiss: 'Escape' },
  { stop: 'alias-membership', dismiss: 'shade' },
  { stop: 'listening', dismiss: 'Close guide' }
]) {
  test(`Scan Lists restores the original page and player after ${dismiss} from ${stop}`, async ({ page }) => {
    const { fixture, requests } = await openScanLists(page, { replay: false });
    const search = page.locator('.scan-list-search-input');
    const searchFallback = page.locator('.scan-list-catalog input[type="search"]');
    const field = await search.count() ? search : searchFallback;
    await expect(field).toBeVisible();
    await field.fill('County');
    const routeBefore = page.url();
    const titleBefore = await page.title();
    const playerBefore = await page.locator('#audio-dock').evaluate(element => ({
      source: element.dataset.source, state: element.dataset.state }));
    await page.locator('#content').evaluate(element => { window.scanListOriginalNodes = [...element.childNodes]; });
    await page.locator('#page-guide-button').click();
    await goToStep(page, stop);
    await guide(page).getByRole('button', { name: 'Back', exact: true }).click();
    await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
    await expect(guide(page)).toHaveAttribute('data-guide-step', stop);
    if (dismiss === 'Escape') await page.keyboard.press('Escape');
    else if (dismiss === 'shade') await page.mouse.click(8, 500);
    else await guide(page).getByRole('button', { name: dismiss, exact: true }).click();
    await expect(guide(page)).toHaveCount(0);
    await expect(page).toHaveURL(routeBefore);
    await expect(page).toHaveTitle(titleBefore);
    await expect(field).toHaveValue('County');
    expect(await page.locator('#content').evaluate(element =>
      window.scanListOriginalNodes.every((node, index) => element.childNodes[index] === node))).toBe(true);
    await expect(page.locator('[data-guide-sample], .scan-list-admin-modal, .alias-confirm-modal, .alias-record-modal, .ui-setup-guide-layer')).toHaveCount(0);
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-source', playerBefore.source);
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-state', playerBefore.state);
    await expect(page.locator('#page-guide-button')).toBeFocused();
    expectNoEffects(fixture, requests);
  });
}

test('Scan Lists tour restores an expanded Recordings player after showing sample Live choices', async ({ page }) => {
  const { fixture, requests } = await openScanLists(page, { replay: false });
  const player = page.locator('#audio-dock');
  await player.getByRole('combobox', { name: 'Audio source', exact: true }).selectOption('recordings');
  await expect(page).toHaveURL(/view=recordings/);
  await player.getByRole('button', { name: 'Expand player', exact: true }).first().click();
  await expect(player).toHaveAttribute('data-state', 'full');
  await page.locator('.primary-nav [data-view="scan-lists"]').evaluate(link => link.click());
  await expect(page.locator('.scan-list-catalog')).toBeVisible();
  await expect(player).toHaveAttribute('data-source', 'recordings');
  const routeBefore = page.url();
  const titleBefore = await page.title();
  await page.locator('#page-guide-button').click();
  await goToStep(page, 'listening');
  await expect(player).toHaveAttribute('data-source', 'live');
  await expect(player.locator('.audio-dock-selection-list')).toContainText('Fire & EMS');
  // Sample player command guards must allow Escape to reach the active guide.
  await player.evaluate(element => element.dispatchEvent(new KeyboardEvent('keydown', {
    key: 'Escape', bubbles: true, cancelable: true
  })));
  await expect(guide(page)).toHaveCount(0);
  await expect(page).toHaveURL(routeBefore);
  await expect(page).toHaveTitle(titleBefore);
  await expect(player).toHaveAttribute('data-source', 'recordings');
  await expect(player).toHaveAttribute('data-state', 'full');
  await expect(player.locator('.audio-dock-selection-list')).toHaveCount(0);
  await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
  expectNoEffects(fixture, requests);
});

test('Scan Lists navigation releases all sample pages, dialogs, and player choices', async ({ page }) => {
  const { fixture, requests } = await openScanLists(page);
  await goToStep(page, 'alias-membership');
  await page.locator('.primary-nav [data-view="dashboard"]').evaluate(link => link.click());
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator('[data-guide-sample], .scan-list-admin-modal, .alias-confirm-modal, .alias-record-modal')).toHaveCount(0);
  await page.locator('.primary-nav [data-view="scan-lists"]').evaluate(link => link.click());
  await expect(page.locator('.scan-list-catalog')).toContainText('County Fire and Emergency Medical Services');
  expectNoEffects(fixture, requests);
});

test('Scan Lists Help works with an empty catalog and restores it on dismissal', async ({ page }) => {
  const { fixture, requests } = await openScanLists(page, { empty: true });
  await expectStep(page, steps[0], 0);
  await page.keyboard.press('Escape');
  await expect(page.locator('.scan-list-catalog')).not.toContainText('Public Safety');
  await page.locator('#page-guide-button').click();
  await expectStep(page, steps[0], 0);
  await page.keyboard.press('Escape');
  expectNoEffects(fixture, requests);
});

test('first Scan Lists visit supersedes a dismissed placeholder and remembers completion', async ({ page }) => {
  const { fixture, requests } = await openScanLists(page, { firstVisit: true, replay: false });
  await expectStep(page, steps[0], 0);
  await page.keyboard.press('Escape');
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBe('1');
  await page.reload();
  await expect(page.locator('#page-guide-button')).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await expectStep(page, steps[0], 0);
  await page.keyboard.press('Escape');
  expectNoEffects(fixture, requests);
});

test('phone visits keep the desktop Scan Lists tour and samples closed', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const { fixture, requests } = await openScanLists(page, { firstVisit: true });
  await expect(page.locator('.scan-list-catalog')).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator('[data-guide-sample]')).toHaveCount(0);
  await expect(page.locator('#page-guide-button')).toBeHidden();
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBeNull();
  expectNoEffects(fixture, requests);
});

for (const theme of ['light', 'dark']) {
  test(`Scan Lists actual guide surfaces retain readable contrast in ${theme} and representative hues`, async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.setViewportSize({ width: 1440, height: 1000 });
    const { fixture, requests } = await openScanLists(page, { theme });
    for (const [index, step] of steps.entries()) {
      await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
      if (['sources', 'details', 'add-alias-list', 'alias-membership', 'listening'].includes(step.id)) {
        await page.evaluate(() => {
          const backdrop = document.querySelector('.ui-setup-guide-backdrop');
          window.scanGuideContrast = { backdrop, visibility: backdrop.style.visibility,
            inert: [...document.querySelectorAll('[inert]')] };
          backdrop.style.visibility = 'hidden';
          window.scanGuideContrast.inert.forEach(element => { element.inert = false; });
        });
        try {
          for (const hue of [null, 0, 120, 240]) {
            await page.evaluate(async value => {
              const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
              applyThemeHue(value);
            }, hue);
            const selector = step.id === 'sources' ? '.scan-list-catalog' :
              step.id === 'details' ? '.scan-list-admin-modal' :
                step.id === 'add-alias-list' ? '.alias-confirm-modal' :
                  step.id === 'alias-membership' ? '.alias-record-modal' : '#audio-dock .audio-dock-selection-list';
            const result = await expectContrast(page, { rootSelector: selector,
              reportName: `scan-lists-${step.id}-${theme}-hue-${hue}` });
            expect(result.summary.text.checked).toBeGreaterThan(2);
          }
        } finally {
          await page.evaluate(async () => {
            const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
            applyThemeHue(null);
            const state = window.scanGuideContrast;
            state.inert.forEach(element => { element.inert = true; });
            state.backdrop.style.visibility = state.visibility;
            delete window.scanGuideContrast;
          });
        }
      }
      await guide(page).getByRole('button', { name: index === steps.length - 1 ? 'Finish' : 'Next', exact: true }).click();
    }
    expectNoEffects(fixture, requests);
  });
}
