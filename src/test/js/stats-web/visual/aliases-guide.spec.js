'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const guide = page => page.locator('.ui-setup-guide[data-guide-id="aliases"]');
const samples = page => page.locator('.alias-editor-workspace[data-guide-sample]');
const target = (page, name) => page.locator(`[data-guide-target="${name}"]:visible`).first();
const memoryKey = id => `sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:${id}:${id === 'aliases' ? 3 : 1}`;
const steps = [
  { id: 'lists', targets: ['aliases-lists', 'aliases-new-list'] },
  { id: 'views', targets: ['aliases-configure', 'aliases-activity', 'aliases-custom', 'aliases-discover'] },
  { id: 'alias-editor', targets: ['aliases-editor-tabs'] },
  { id: 'defaults-unmatched', targets: ['aliases-defaults-tabs', 'aliases-defaults-recording',
    'aliases-defaults-scan-lists', 'aliases-defaults-streaming'] },
  { id: 'defaults-new', targets: ['aliases-defaults-tabs', 'aliases-defaults-recording',
    'aliases-defaults-scan-lists', 'aliases-defaults-streaming'] },
  { id: 'bulk', targets: ['aliases-selection', 'aliases-bulk', 'aliases-select-all'] },
  { id: 'scan-lists', targets: ['aliases-scan-list-dialog'] },
  { id: 'activity', targets: ['aliases-activity', 'aliases-activity-columns'] },
  { id: 'custom', targets: ['aliases-custom', 'aliases-columns'] },
  { id: 'discover', targets: ['aliases-discover', 'aliases-discover-identities'] },
  { id: 'transfer', targets: ['aliases-add', 'aliases-import', 'aliases-export'] }
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

// Feed the ordinary workspace the same fictional records used by the guide.
// This lets fidelity checks compare production dialogs with identical data,
// rather than accepting a second implementation that only resembles them.
async function installSampleAliasCatalog(page) {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/guides/aliases-presentation.js');
  const { getAliasesGuideData } = await import(pathToFileURL(file).href);
  const sample = getAliasesGuideData('lists');
  await page.route('**/api/v1/**', route => {
    const pathname = new URL(route.request().url()).pathname;
    let data;
    if (pathname === '/api/v1/admin/alias-lists') data = { revision: sample.options.revision, alias_lists: sample.lists };
    else if (pathname === '/api/v1/alias-lists') data = { rows: sample.lists, total_count: sample.lists.length,
      limit: 500, offset: 0, has_more: false, next_offset: null };
    else if (pathname === '/api/v1/aliases') data = sample.page;
    else if (pathname === '/api/v1/admin/aliases/options') data = sample.options;
    else if (pathname === `/api/v1/admin/aliases/${sample.rows[0].alias_id}`) data = sample.recordResponse;
    else if (pathname === `/api/v1/aliases/${sample.rows[0].alias_id}`) data = sample.analytics;
    else return route.fallback();
    return route.fulfill({ json: { data } });
  });
  return sample;
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
      structure: [...root.querySelectorAll('form, nav, fieldset, legend, .alias-editor-panel, .alias-defaults-tab-panel, .table-layout-panel-header, .table-layout-options, .table-layout-column, .table-layout-panel-footer, .ui-modal-footer')]
        .map(element => [element.tagName, element.className]),
      fields: [...root.querySelectorAll('input, select, textarea')].map(element =>
        [element.tagName, element.getAttribute('name'), element.getAttribute('type'), element.className]),
      tabs: [...root.querySelectorAll('[role="tab"]')].map(element => element.textContent),
      actions: [...root.querySelectorAll('.ui-modal-footer button, .table-layout-panel-footer button')]
        .map(element => element.textContent),
      paint: { font: styles.fontFamily, background: styles.backgroundColor, color: styles.color,
        radius: styles.borderRadius, shadow: styles.boxShadow }
    };
  });
}

async function expectWithinViewport(locator, viewport) {
  await expect.poll(async () => {
    const bounds = await locator.boundingBox();
    return bounds !== null && bounds.x >= 0 && bounds.y >= 0 &&
      bounds.x + bounds.width <= viewport.width + 1 && bounds.y + bounds.height <= viewport.height + 1;
  }).toBe(true);
}

async function expectSpotlight(page, name) {
  const items = page.locator(`[data-guide-target="${name}"]:visible`);
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
  await expect(samples(page)).toHaveCount(1);
  await expect(page.locator('.alias-guide-preview')).toHaveCount(0);
  await expect(samples(page)).not.toContainText('Channel names could not be');
  if (step.id === 'lists') {
    await expect(samples(page).locator('.alias-channel-usage')).toContainText('Example County Site 1');
    await expect(guide(page)).not.toContainText('Channels with a matching protocol');
    await expect(guide(page)).not.toContainText('nothing is saved');
    await expect(page.locator('[data-guide-target="aliases-usage"]')).toHaveCount(0);
  }
  await expect(page.locator('#audio-dock')).toBeHidden();
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
      if (step.id === 'views') {
        await expect(target(page, 'aliases-table')).toContainText('Fire Dispatch');
        await expect(target(page, 'aliases-table')).toContainText('Public Works');
      }
      if (['lists', 'views', 'alias-editor', 'defaults-unmatched', 'defaults-new', 'bulk', 'scan-lists', 'activity', 'custom', 'discover'].includes(step.id)) {
        await page.screenshot({ path: testInfo.outputPath(`aliases-${step.id}-${theme}.png`) });
      }
      if (step.id === 'alias-editor') {
        await expect(target(page, 'aliases-editor-tabs')).toContainText('Basics');
        await expect(target(page, 'aliases-editor-tabs')).toContainText('Identifier');
        await expect(target(page, 'aliases-editor-tabs')).toContainText('Call Handling');
        await expect(target(page, 'aliases-editor-tabs')).toContainText('Activity');
      }
      if (step.id === 'views') {
        for (const label of ['Configure', 'Activity', 'Custom', 'Discover']) {
          await expect(target(page, 'aliases-views')).toContainText(label);
          await expect(guide(page).locator('.ui-setup-guide-items')).toContainText(label);
        }
      }
      const modalClass = { 'alias-editor': 'alias-record-modal', 'defaults-unmatched': 'alias-policy-modal',
        'defaults-new': 'alias-policy-modal', 'scan-lists': 'alias-bulk-modal' }[step.id];
      if (modalClass) {
        const dialog = page.locator(`.read-only-modal.${modalClass}`);
        await expect(dialog).toBeVisible();
        await expect(dialog.locator('.modal-header h2')).toBeVisible();
        await expect(dialog.locator('.modal-content form')).toBeVisible();
        await expect(dialog.locator('.ui-modal-footer')).toBeVisible();
        await expect(samples(page).locator('.alias-list-rail')).toBeVisible();
        await expect(samples(page).locator('.alias-editor-table-host')).toBeVisible();
      } else await expect(page.locator('.alias-record-modal, .alias-policy-modal, .alias-bulk-modal')).toHaveCount(0);
      if (step.id === 'alias-editor') {
        await expect(guide(page)).toContainText('talkgroup ID or radio ID');
        await expect(guide(page).locator('.ui-setup-guide-items')).toHaveCount(1);
        await expect(page.locator('.alias-record-modal input[name="name"]')).toHaveValue('Fire Dispatch');
        await expect(guide(page)).not.toContainText('Add Alias opens');
      }
      if (step.id === 'defaults-unmatched') {
        await expect(guide(page)).toContainText('This is where you configure what to do with unknown talkgroups.');
        await expect(page.locator('.alias-policy-modal [role="tab"][aria-selected="true"]')).toHaveText('Unmatched Calls');
      }
      if (step.id === 'defaults-new') {
        await expect(guide(page)).toContainText('This is where you configure what settings newly added or RadioReference-imported aliases use.');
        await expect(page.locator('.alias-policy-modal [role="tab"][aria-selected="true"]')).toHaveText('New Aliases');
      }
      if (step.id === 'scan-lists') {
        await expect(guide(page)).toContainText('Add or remove the selected aliases from a Scan List here.');
        await expect(page.locator('.alias-bulk-modal .modal-header')).toContainText('2 aliases');
        await expect(page.locator('.alias-bulk-modal select[name="scanListId"]')).toHaveValue('900001');
        await expect(page.locator('.alias-bulk-modal .ui-modal-footer')).toContainText('Apply Change');
      }
      if (step.id === 'activity') {
        await expect(page.locator('[data-guide-target="aliases-activity-columns"]'))
          .toHaveText(['Calls', 'Signaling', 'Last Seen']);
      }
      if (step.id === 'custom') {
        const columns = page.locator('.table-layout-panel.ui-setup-guide-layer');
        await expect(columns).toBeVisible();
        await expect(columns).toHaveClass(/ui-popover/);
        await expect(columns.locator('.table-layout-panel-header')).toContainText('Table columns');
        await expect(columns.locator('.table-layout-column')).not.toHaveCount(0);
        await expect(columns.locator('.table-layout-panel-footer .table-layout-reset')).toBeVisible();
        const id = await columns.getAttribute('id');
        await expect(page.locator(`.table-layout-trigger[aria-controls="${id}"]`)).toHaveAttribute('aria-expanded', 'true');
        await expectWithinViewport(columns, page.viewportSize());
      }
      if (step.id === 'discover') await expect(target(page, 'aliases-discover-table')).toContainText('2501');
      if (step.id === 'transfer') {
        await expect(guide(page)).toContainText('RadioReference or a file');
        await expect(guide(page)).toContainText('configuration file');
        await expect(guide(page)).not.toContainText('How aliases become listening choices');
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

for (const theme of ['light', 'dark']) {
  test(`Aliases guide uses the ordinary dialog and Columns surfaces in ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const { fixture, requests } = await openAliases(page, { theme, replay: false });
    const sample = await installSampleAliasCatalog(page);
    await page.goto(`/app.html?view=aliases&list=${sample.list.id}`);
    const baseline = {};
    await page.locator(`.alias-detail-link[data-alias-id="${sample.rows[0].alias_id}"]`).click();
    const editor = page.locator('.alias-record-modal');
    baseline['alias-editor'] = await surfaceSignature(editor);
    await page.screenshot({ path: testInfo.outputPath(`ordinary-alias-editor-${theme}.png`) });
    await editor.locator('.modal-close').click();
    await page.getByRole('button', { name: 'Call Handling Defaults', exact: true }).click();
    const defaults = page.locator('.alias-policy-modal');
    baseline['defaults-unmatched'] = await surfaceSignature(defaults);
    baseline['defaults-new'] = baseline['defaults-unmatched'];
    await page.screenshot({ path: testInfo.outputPath(`ordinary-defaults-${theme}.png`) });
    await defaults.locator('.modal-close').click();
    for (const row of sample.rows.slice(0, 2)) {
      await page.getByRole('checkbox', { name: `Select ${row.name}`, exact: true }).check();
    }
    await page.locator('.alias-bulk-bar').getByRole('button', { name: 'Scan Lists', exact: true }).click();
    const bulk = page.locator('.alias-bulk-modal');
    baseline['scan-lists'] = await surfaceSignature(bulk);
    await page.screenshot({ path: testInfo.outputPath(`ordinary-scan-lists-${theme}.png`) });
    await bulk.locator('.modal-close').click();
    await page.locator('[data-alias-view="custom"]').click();
    await expect(page.locator('.alias-editor-table-host table')).toHaveAttribute('data-table-type', 'alias-editor-custom');
    await page.locator('.alias-editor-main .table-layout-trigger').click();
    const columns = page.locator('.table-layout-panel:popover-open');
    baseline.custom = await surfaceSignature(columns);
    await page.screenshot({ path: testInfo.outputPath(`ordinary-columns-${theme}.png`) });
    await page.keyboard.press('Escape');
    await expect(columns).toHaveCount(0);
    await page.locator('#page-guide-button').click();
    for (const step of steps) {
      await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
      if (baseline[step.id]) {
        const selector = { 'alias-editor': '.alias-record-modal', 'defaults-unmatched': '.alias-policy-modal',
          'defaults-new': '.alias-policy-modal', 'scan-lists': '.alias-bulk-modal',
          custom: '.table-layout-panel.ui-setup-guide-layer' }[step.id];
        expect(await surfaceSignature(page.locator(selector))).toEqual(baseline[step.id]);
        await page.screenshot({ path: testInfo.outputPath(`guide-${step.id}-${theme}.png`) });
      }
      await guide(page).getByRole('button', { name: step.id === 'transfer' ? 'Finish' : 'Next', exact: true }).click();
    }
    await expect(guide(page)).toHaveCount(0);
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
    const count = await page.evaluate(() => {
      const roots = [...document.querySelectorAll('.alias-editor-workspace[data-guide-sample], .alias-record-modal, .alias-policy-modal, .alias-bulk-modal, .table-layout-panel.ui-setup-guide-layer')];
      const controls = [...new Set(roots.flatMap(root => [...root.querySelectorAll('button, a, input, select, form')]))];
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

for (const { dismissal, stop } of [
  { dismissal: 'Close guide', stop: 'custom' }, { dismissal: 'Escape', stop: 'custom' },
  { dismissal: 'darkened area', stop: 'custom' }, { dismissal: 'Close guide', stop: 'alias-editor' },
  { dismissal: 'Escape', stop: 'defaults-new' }, { dismissal: 'darkened area', stop: 'scan-lists' }
]) {
  test(`Aliases sample restores the real workspace after ${dismissal} from ${stop}`, async ({ page }) => {
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
    await goToStep(page, stop);
    await expect(samples(page)).toBeVisible();
    await guide(page).getByRole('button', { name: 'Back', exact: true }).click();
    await expect(guide(page)).toHaveAttribute('data-guide-step', steps[steps.findIndex(step => step.id === stop) - 1].id);
    await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
    await expect(guide(page)).toHaveAttribute('data-guide-step', stop);
    if (dismissal === 'Escape') await page.keyboard.press('Escape');
    else if (dismissal === 'darkened area') await page.mouse.click(8, 500);
    else await guide(page).getByRole('button', { name: dismissal, exact: true }).click();
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator('[data-guide-sample]')).toHaveCount(0);
    await expect(page.locator('.ui-setup-guide-backdrop')).toHaveCount(0);
    await expect(page.locator('.alias-record-modal, .alias-policy-modal, .alias-bulk-modal, .ui-setup-guide-layer')).toHaveCount(0);
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

test('the tour preserves original table overflow controls and keyboard scrolling', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.setViewportSize({ width: 1280, height: 800 });
  const { fixture, requests } = await openAliases(page, { replay: false });
  const main = page.locator('.alias-editor-main');
  const wrapper = main.locator('.alias-editor-table-host .ui-table-wrap');
  const scrolling = main.locator('.ui-table-overflow-controls');
  await expect(scrolling).toBeVisible();
  await expect(wrapper).toHaveAttribute('aria-keyshortcuts', 'ArrowLeft ArrowRight Home End');
  await wrapper.focus();
  await page.keyboard.press('End');
  await expect.poll(() => wrapper.evaluate(element => element.scrollLeft)).toBeGreaterThan(0);
  await page.keyboard.press('Home');
  await expect.poll(() => wrapper.evaluate(element => element.scrollLeft)).toBe(0);
  await wrapper.evaluate(element => {
    window.aliasOriginalOverflow = { wrapper: element,
      controls: document.querySelector('.alias-editor-main .ui-table-overflow-controls') };
  });
  await page.locator('#page-guide-button').click();
  await goToStep(page, 'defaults-new');
  await guide(page).getByRole('button', { name: 'Close guide', exact: true }).click();
  await expect(scrolling).toBeVisible();
  expect(await wrapper.evaluate(element => element === window.aliasOriginalOverflow.wrapper &&
    document.querySelector('.alias-editor-main .ui-table-overflow-controls') === window.aliasOriginalOverflow.controls)).toBe(true);
  await wrapper.focus();
  await page.keyboard.press('End');
  await expect.poll(() => wrapper.evaluate(element => element.scrollLeft)).toBeGreaterThan(0);
  await page.keyboard.press('Home');
  await expect.poll(() => wrapper.evaluate(element => element.scrollLeft)).toBe(0);
  await scrolling.getByRole('button', { name: 'Scroll table right', exact: true }).click();
  await expect.poll(() => wrapper.evaluate(element => element.scrollLeft)).toBeGreaterThan(0);
  await page.setViewportSize({ width: 1100, height: 800 });
  await expect(scrolling).toBeVisible();
  await expect(wrapper).toHaveAttribute('aria-keyshortcuts', 'ArrowLeft ArrowRight Home End');
  expectNoEffects(fixture, requests);
});

test('the tour restores a Scan List members origin and its page title', async ({ page }) => {
  const { fixture, requests } = await openAliases(page, { replay: false, query: 'scanListId=1' });
  await expect(page.getByRole('heading', { name: 'Scan List Members', exact: true })).toBeVisible();
  const titleBefore = await page.title();
  const routeBefore = page.url();
  await page.locator('#page-guide-button').click();
  await goToStep(page, 'scan-lists');
  await guide(page).getByRole('button', { name: 'Close guide', exact: true }).click();
  await expect(page).toHaveURL(routeBefore);
  await expect(page.getByRole('heading', { name: 'Scan List Members', exact: true })).toBeVisible();
  await expect(page).toHaveTitle(titleBefore);
  // A subsequent player update must continue to use the restored page title.
  await page.locator('#audio-dock').getByRole('button', { name: 'Expand player', exact: true }).first().click();
  await expect(page).toHaveTitle(titleBefore);
  await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .alias-bulk-modal')).toHaveCount(0);
  expectNoEffects(fixture, requests);
});

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
  await goToStep(page, 'transfer');
  await expect(player).toBeHidden();
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
  test(`Aliases real views and dialogs retain readable contrast in ${theme} and representative hues`, async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const { fixture, requests } = await openAliases(page, { theme });
    const audited = ['views', 'alias-editor', 'defaults-unmatched', 'defaults-new', 'bulk', 'scan-lists', 'custom', 'discover'];
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
            const result = await expectContrast(page, { rootSelector: '.alias-editor-workspace[data-guide-sample], .alias-record-modal, .alias-policy-modal, .alias-bulk-modal, .table-layout-panel.ui-setup-guide-layer',
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
