'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const fs = require('node:fs');
const { pathToFileURL } = require('node:url');
const { execFileSync } = require('node:child_process');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

test.use({ viewport: { width: 1280, height: 800 } });

const guide = page => page.locator('.ui-setup-guide[data-guide-id="scan-lists"]');
const memoryKey = 'sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:scan-lists:8';
const steps = [
  { id: 'lists', targets: ['scan-lists-manage-aliases'],
    copy: 'Open this Scan List’s members to see the aliases that feed your listening group.' },
  { id: 'members', targets: ['scan-lists-member-alias', 'scan-lists-member-alias-list'],
    copy: 'Bring related talkgroups from several radio systems together in one listening group.' },
  { id: 'add-list-button', targets: ['scan-lists-add-list-button'],
    copy: 'To add individual aliases to a Scan List, go to Manage > Aliases.' },
  { id: 'add-alias-list', targets: ['scan-lists-add-list-dialog'],
    copy: 'Choose an Alias List. Add All Aliases adds its current aliases and returns you to the members page.' },
  { id: 'back-to-lists', targets: ['scan-lists-back-to-lists'],
    copy: 'Return to Scan Lists to review your listening groups.' },
  { id: 'player-overview', targets: ['player'],
    copy: 'The player plays completed calls from the Scan Lists you choose.' },
  { id: 'listening-controls', targets: ['player-menu'],
    copy: 'Open Player menu for your listening choices and playback preferences.' },
  { id: 'listening-menu', targets: ['player-menu-lists'],
    copy: 'Open Lists to choose the groups whose calls reach your player.' },
  { id: 'listening', targets: ['scan-lists-player-lists'],
    copy: 'VCE queues completed calls from your selected lists and plays them one at a time.' },
  { id: 'listening-back', targets: ['player-menu-back'], copy: 'Back returns to Player menu.' },
  { id: 'player-back', targets: ['player-menu-back'], copy: 'Back closes Player menu and returns you to your Scan Lists.' }
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
  ordinarySamples = false, origin = 'scan-lists', empty = false, deniedCapabilities = [] } = {}) {
  const requests = { writes: [], audio: [], samples: [], external: [] };
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/v1/') && !['GET', 'HEAD'].includes(request.method()) &&
        !(request.method() === 'PUT' && url.pathname === '/api/v1/me/tour-dismissals')) {
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
    for (const [id, version] of [['welcome', 1], ['channel-setup', 5], ['aliases', 5]]) {
      localStorage.setItem(`sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:${id}:${version}`, '1');
    }
    for (const version of [1, 2, 3, 4, 5, 6, 7]) {
      localStorage.setItem(`sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:scan-lists:${version}`, '1');
    }
  });
  if (deniedCapabilities.length) {
    const source = fs.readFileSync(path.resolve(__dirname,
      '../../../../../src/main/java/io/github/dsheirer/web/auth/WebCapability.java'), 'utf8');
    const capabilities = Object.fromEntries([...source.matchAll(/^\s+[A-Z_]+\("([^"]+)",/gm)]
      .map(([, id]) => [id, !deniedCapabilities.includes(id)]));
    await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
      configured: true, authenticated: true, username: 'fixture-admin', tier: 'admin', primary: true,
      csrf_token: 'fixture-csrf', guide_dismissal_scope: 'account-1-100', capabilities
    } } }));
  }
  if (ordinarySamples) await installOrdinarySampleCatalog(page);
  if (empty) await page.route('**/api/v1/admin/scan-lists', route =>
    route.fulfill({ json: { data: { revision: 1, scan_lists: [] } } }));
  const query = origin === 'members' ? 'view=aliases&scanListId=1' : 'view=scan-lists';
  await page.goto(`/app.html?${query}${replay ? '&guide=scan-lists' : ''}`);
  if ((replay || firstVisit) && page.viewportSize().width >= 760) await startTour(page);
  return { fixture, requests };
}

async function expectRelationshipChart(page) {
  const chart = guide(page).locator('.ui-setup-guide-diagram[data-guide-diagram="scan-lists"]');
  await expect(chart).toBeVisible();
  await expect(chart.getByRole('img', { name: 'County and city aliases grouped into Scan Lists', exact: true })).toBeVisible();
  const expectedRoutes = [
    ['sheriff', 'alias-sheriff', 'member-law-sheriff', 'Sheriff'],
    ['regional-fire', 'alias-regional-fire', 'member-fire-regional-fire', 'Regional Fire'],
    ['police-department', 'alias-police-department', 'member-law-police-department', 'Police Department'],
    ['city-fire', 'alias-city-fire', 'member-fire-city-fire', 'City Fire']
  ];
  const rendered = await chart.evaluate(root => {
    const svg = root.querySelector('svg');
    const ids = [...svg.querySelectorAll('[id]')].map(node => node.id);
    return {
      duplicateIds: ids.filter((id, index) => ids.indexOf(id) !== index),
      unresolvedReferences: [...svg.querySelectorAll('[href], [marker-end]')].flatMap(node => {
        const reference = node.getAttribute('href') || /url\(#([^)]*)\)/.exec(node.getAttribute('marker-end'))?.[1];
        const id = reference?.replace(/^#/, '');
        return id && !document.getElementById(id) ? [id] : [];
      }),
      routes: [...svg.querySelectorAll('[data-guide-diagram-route]')].map(route => {
        const source = route.dataset.source;
        const target = route.dataset.target;
        const boxFor = id => [...svg.querySelectorAll('[id]')].find(node => node.id.endsWith(`-${id}`) || node.id === id)?.getBBox();
        const labelFor = id => [...svg.querySelectorAll('[data-container]')]
          .find(node => node.dataset.container === id)?.textContent;
        const start = route.getPointAtLength(0);
        const end = route.getPointAtLength(route.getTotalLength());
        const from = boxFor(source);
        const to = boxFor(target);
        const matrix = route.getScreenCTM();
        return { name: route.dataset.guideDiagramRoute, source, target,
          sourceLabel: labelFor(source), targetLabel: labelFor(target),
          stroke: Number(route.getAttribute('stroke-width')),
          renderedStroke: Number(route.getAttribute('stroke-width')) * Math.hypot(matrix.a, matrix.b),
          arrow: route.getAttribute('marker-end'),
          startOnSource: !!from && Math.abs(start.x - from.x - from.width) < 1 &&
            Math.abs(start.y - from.y - from.height / 2) < 1,
          endOnTarget: !!to && Math.abs(end.x - to.x) < 1 &&
            Math.abs(end.y - to.y - to.height / 2) < 1 };
      })
    };
  });
  expect(rendered.duplicateIds).toEqual([]);
  expect(rendered.unresolvedReferences).toEqual([]);
  expect(rendered.routes).toHaveLength(4);
  for (const [name, source, target, label] of expectedRoutes) {
    const route = rendered.routes.find(item => item.name === name);
    expect(route, name).toMatchObject({ source, target, sourceLabel: label, targetLabel: label,
      startOnSource: true, endOnTarget: true });
    expect(route.stroke).toBeGreaterThanOrEqual(8);
    expect(route.renderedStroke).toBeGreaterThanOrEqual(4);
    expect(route.arrow).toMatch(/^url\(#.+\)$/);
  }
  for (const label of ['County P25', 'City P25', 'Law Enforcement', 'Fire/EMS']) {
    await expect(chart.getByText(label, { exact: true }).first()).toBeVisible();
  }
  const bounds = await guide(page).boundingBox();
  const viewport = page.viewportSize();
  expect(bounds.x).toBeGreaterThanOrEqual(0);
  expect(bounds.y).toBeGreaterThanOrEqual(0);
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width + 1);
  expect(bounds.y + bounds.height).toBeLessThanOrEqual(viewport.height + 1);
  await expect(guide(page).getByRole('button', { name: 'Close guide', exact: true })).toBeVisible();
}

async function expectOverview(page) {
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await expect(guide(page).locator('.ui-setup-guide-counter')).toHaveText('Overview');
  await expect(guide(page)).not.toContainText(/Step \d+ of/);
  await expect(guide(page).getByRole('button', { name: 'Start tour', exact: true })).toBeVisible();
  await expect(guide(page).getByRole('button', { name: 'Back', exact: true })).toBeHidden();
  await expect(page.locator('.ui-setup-guide-highlight, [data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
  await expect(page.locator('.alias-record-modal, .alias-policy-modal, .alias-bulk-modal, .alias-confirm-modal')).toHaveCount(0);
  await expectRelationshipChart(page);
}

async function startTour(page) {
  await expectOverview(page);
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).click();
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
  await expect(guide(page)).toContainText(step.copy);
  await expect(guide(page)).not.toContainText(/Hiding a list|Display order|Choose another default|does not|doesn’t|not automatically|not saved|nothing is saved/i);
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
  await expect(page.locator('.ui-setup-guide-diagram')).toHaveCount(0);
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

test('Scan Lists sample controls cannot save, navigate, delete, change membership, or player preferences', async ({ page }) => {
  const { fixture, requests } = await openScanLists(page);
  await expectStep(page, steps[0], 0);
  const before = page.url();
  const originalStorage = await page.evaluate(() => Object.fromEntries(Object.entries(localStorage)));
  let exercised = 0;
  for (const [index, step] of steps.entries()) {
    await expectStep(page, step, index);
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

test('Scan Lists navigation releases all sample pages, dialogs, and player choices', async ({ page }) => {
  const { fixture, requests } = await openScanLists(page);
  await goToStep(page, 'add-alias-list');
  await page.locator('.primary-nav [data-view="dashboard"]').evaluate(link => link.click());
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator('[data-guide-sample], .scan-list-admin-modal, .alias-confirm-modal, .alias-record-modal')).toHaveCount(0);
  await page.locator('.primary-nav [data-view="scan-lists"]').evaluate(link => link.click());
  await expect(page.locator('.scan-list-catalog')).toContainText('County Fire and Emergency Medical Services');
  expectNoEffects(fixture, requests);
});
