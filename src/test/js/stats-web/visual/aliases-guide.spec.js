'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const guide = page => page.locator('.ui-setup-guide[data-guide-id="aliases"]');
const samples = page => page.locator('.alias-editor-workspace[data-guide-sample]');
const target = (page, name) => page.locator(`[data-guide-target="${name}"]:visible`).first();
const memoryKey = id => `sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:${id}:${id === 'aliases' ? 5 : id === 'channel-setup' ? 4 : 1}`;
const steps = [
  { id: 'lists', targets: ['aliases-lists'] },
  { id: 'views', targets: ['aliases-configure', 'aliases-activity', 'aliases-custom', 'aliases-discover'] },
  { id: 'alias-button', targets: ['aliases-open-alias'] },
  { id: 'alias-editor', targets: ['aliases-editor-tabs'] },
  { id: 'alias-close', targets: ['aliases-editor-close'] },
  { id: 'defaults-button', targets: ['aliases-defaults'] },
  { id: 'defaults-unmatched', targets: ['aliases-defaults-content'] },
  { id: 'defaults-new-button', targets: ['aliases-defaults-new-tab'] },
  { id: 'defaults-new', targets: ['aliases-defaults-content'] },
  { id: 'defaults-close', targets: ['aliases-defaults-close'] },
  { id: 'bulk', targets: ['aliases-selection'] },
  { id: 'scan-lists-button', targets: ['aliases-bulk-scan-lists'] },
  { id: 'scan-lists', targets: ['aliases-scan-list-dialog'] },
  { id: 'clear-selection', targets: ['aliases-clear-selection'] },
  { id: 'activity-button', targets: ['aliases-activity'] },
  { id: 'activity', targets: ['aliases-activity-columns'] },
  { id: 'custom-button', targets: ['aliases-custom'] },
  { id: 'custom', targets: ['aliases-table'] },
  { id: 'columns-button', targets: ['aliases-columns'] },
  { id: 'discover-button', targets: ['aliases-discover'] },
  { id: 'discover', targets: ['aliases-discover-identities'] },
  { id: 'configure-button', targets: ['aliases-configure'] },
  { id: 'transfer', targets: ['aliases-add', 'aliases-import', 'aliases-export'] }
];

const modalClasses = {
  'alias-editor': 'alias-record-modal', 'alias-close': 'alias-record-modal',
  'defaults-unmatched': 'alias-policy-modal', 'defaults-new-button': 'alias-policy-modal',
  'defaults-new': 'alias-policy-modal', 'defaults-close': 'alias-policy-modal',
  'scan-lists': 'alias-bulk-modal'
};

async function openAliases(page, { theme = 'light', replay = true, firstVisit = false,
  empty = false, customLayout = false, query = 'list=1' } = {}) {
  const requests = { writes: [], samplePaths: [], radioReference: [] };
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/v1/') && !['GET', 'HEAD'].includes(request.method()) &&
        !(request.method() === 'PUT' && url.pathname === '/api/v1/me/tour-dismissals')) {
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
    for (const [id, version] of [['welcome', 1], ['channel-setup', 4]]) {
      localStorage.setItem(`sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:${id}:${version}`, '1');
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
  if ((replay || firstVisit) && page.viewportSize().width >= 760) await startTour(page);
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

async function expectOverview(page) {
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await expect(guide(page).locator('.ui-setup-guide-counter')).toHaveText('Overview');
  await expect(guide(page)).not.toContainText(/Step \d+ of/);
  await expect(guide(page).getByRole('button', { name: 'Start tour', exact: true })).toBeVisible();
  await expect(guide(page).getByRole('button', { name: 'Back', exact: true })).toBeHidden();
  await expect(page.locator('.ui-setup-guide-highlight, [data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
  await expect(page.locator('.alias-record-modal, .alias-policy-modal, .alias-bulk-modal, .alias-confirm-modal')).toHaveCount(0);
}

async function startTour(page) {
  await expectOverview(page);
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).click();
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
  await startTour(page);
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
  await startTour(page);
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
