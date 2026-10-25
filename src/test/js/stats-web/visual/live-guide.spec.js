'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const guide = page => page.locator('.ui-setup-guide[data-guide-id="live"]');
const steps = ['views', 'activity', 'reception', 'select-channel', 'detail-views', 'events', 'pause',
  'event-filters', 'messages-tab', 'messages', 'channel-tab', 'channel', 'workspace-size', 'presentation'];
const memoryKey = 'sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:live:3';
const evidence = process.env.LIVE_GUIDE_EVIDENCE ||
  path.resolve(__dirname, '../../../../../build/playwright-results/live-guide-evidence');
const originalChannel = '11111111-1111-4111-8111-111111111111';
const originalSnapshot = { revision: 1, tables: [{ table_id: originalChannel, configuration_id: originalChannel,
  title: 'Original County', system_name: 'Original County', site_name: 'Original North', channel_running: true,
  rows: [{ key: 'original-control', configuration_id: originalChannel, lcn: 1, frequency_hz: 851012500,
    status: 'CONTROL', tags: ['CURRENT_CONTROL'], role: 'CURRENT_CONTROL', activation_order: 1 },
  { key: 'original-voice', configuration_id: originalChannel, lcn: 2, frequency_hz: 851037500,
    status: 'CALL', tags: ['VOICE'], activation_order: 2, talkgroup: 1201, talkgroup_alias: 'Original Fire',
    source: 30914, source_alias: 'Original Engine' }] }] };
const nonGuideStorage = page => page.evaluate(() => Object.fromEntries(Object.entries(localStorage)
  .filter(([key]) => !key.startsWith('sdrtrunk-vce-guide:'))));

async function openLive(page, { theme = 'light', replay = true, firstVisit = false,
  noHardware = true, denied = [] } = {}) {
  const effects = { writes: [], samples: [], audio: [], sockets: [], controls: [] };
  page.on('request', request => {
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/v1/')) return;
    if (!['GET', 'HEAD'].includes(request.method()) &&
        !(request.method() === 'PUT' && url.pathname === '/api/v1/me/tour-dismissals') && url.pathname !== '/api/v1/live/multiplex/control')
      effects.writes.push(`${request.method()} ${url.pathname}`);
    if (url.pathname !== '/api/v1/me/tour-dismissals' && /guide-|sample-|tour-/.test(url.pathname + (request.postData() || '')))
      effects.samples.push(`${request.method()} ${url.pathname}`);
    if (/audio/.test(url.pathname)) effects.audio.push(url.pathname);
    if (url.pathname === '/api/v1/live/multiplex/control') effects.controls.push(request.postDataJSON());
  });
  page.on('websocket', socket => effects.sockets.push(socket.url()));
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const fixture = await installSiteStyleApplication(page, theme,
    noHardware ? { revision: 1, tables: [] } : originalSnapshot, { guides: firstVisit });
  await page.addInitScript(({ firstVisit, key, noHardware }) => {
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:welcome:1', '1');
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:live:2', '1');
    if (!firstVisit) localStorage.setItem(key, '1');
    localStorage.setItem('sdrtrunk-vce.live-ui-state.v1', JSON.stringify({
      details_collapsed: false, details_active_tab: 'events', details_panel_percent: 50,
      picker_collapsed: false, event_filters: { search: noHardware ? '' : 'Original Fire' },
      message_filters: { search: noHardware ? '' : 'Original message' }
    }));
  }, { firstVisit, key: memoryKey, noHardware });
  if (denied.length) {
    const file = path.resolve(__dirname, '../../../../../src/main/java/io/github/dsheirer/web/auth/WebCapability.java');
    const capabilities = Object.fromEntries([...fs.readFileSync(file, 'utf8').matchAll(/^\s+[A-Z_]+\("([^"]+)",/gm)]
      .map(([, id]) => [id, !denied.includes(id)]));
    await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
      configured: true, authenticated: true, username: 'fixture-admin', tier: 'admin', primary: true,
      csrf_token: 'fixture-csrf', guide_dismissal_scope: 'account-1-100', capabilities
    } } }));
  }
  await page.goto(`/app.html?view=live${replay ? '&guide=live' : ''}`);
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  return { fixture, effects };
}

async function expectInViewport(locator, viewport) {
  await expect.poll(async () => {
    const box = await locator.boundingBox();
    return box && box.x >= 0 && box.y >= 0 && box.x + box.width <= viewport.width + 1 &&
      box.y + box.height <= viewport.height + 1 ? null : box;
  }, { message: 'The real control and guide remain fully visible' }).toBeNull();
}

async function expectOverview(page) {
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await expect(guide(page)).toContainText('Follow your radio traffic');
  await expect(guide(page).locator('.ui-setup-guide-counter')).toHaveText('Overview');
  await expect(page.locator('.ui-setup-guide-highlight')).toHaveCount(0);
  const workspace = page.locator('.live-split[data-guide-example="live"]');
  await expect(workspace).toBeVisible();
  await expect(workspace).toContainText('County P25');
  await expect(workspace).toContainText('City P25');
  await expect(workspace).toContainText('Conventional');
  await expect(workspace.locator('.channels-live-table')).toContainText('Sheriff Dispatch');
  await expectInViewport(guide(page), page.viewportSize());
}

async function start(page) {
  await expectOverview(page);
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).click();
}

async function advance(page, id) {
  await guide(page).getByRole('button', { name: id === steps.at(-1) ? 'Finish' : 'Next', exact: true }).click();
}

async function goTo(page, destination) {
  const current = await guide(page).getAttribute('data-guide-step');
  for (const id of steps.slice(Math.max(0, steps.indexOf(current)))) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    if (id === destination) return;
    await advance(page, id);
  }
  throw new Error(`Unknown Live guide step: ${destination}`);
}

async function expectSpotlight(page, id) {
  const targets = page.locator(`[data-guide-target="lv-${id}"]:visible`);
  await expect(targets.first()).toBeVisible();
  for (const target of await targets.all()) {
    await expectInViewport(target, page.viewportSize());
    await expect.poll(() => target.evaluate(element => {
      const rect = element.getBoundingClientRect();
      const header = document.querySelector('.app-header')?.getBoundingClientRect();
      const card = document.querySelector('.ui-setup-guide').getBoundingClientRect();
      const highlights = [...document.querySelectorAll('.ui-setup-guide-highlight')]
        .map(item => item.getBoundingClientRect());
      const overlap = Math.min(rect.right, card.right) - Math.max(rect.left, card.left) > 1 &&
        Math.min(rect.bottom, card.bottom) - Math.max(rect.top, card.top) > 1;
      const covered = highlights.some(box => box.left <= rect.left + 1 && box.top <= rect.top + 1 &&
        box.right >= rect.right - 1 && box.bottom >= rect.bottom - 1);
      return (!header || rect.top >= header.bottom - 1) && !overlap && covered ? null :
        { target: rect.toJSON(), headerBottom: header?.bottom, overlap, covered };
    }), { message: `lv-${id} highlights its real control below the header and clear of the guide` }).toBeNull();
  }
  await expectInViewport(guide(page), page.viewportSize());
  await expect.poll(() => guide(page).evaluate(element => {
    const header = document.querySelector('.app-header')?.getBoundingClientRect();
    const back = element.querySelector('.ui-setup-guide-back') ||
      [...element.querySelectorAll('button')].find(button => button.textContent.trim() === 'Back');
    const next = [...element.querySelectorAll('button')].find(button => /^(Next|Finish)/.test(button.textContent.trim()));
    const backRect = back?.getBoundingClientRect(), nextRect = next?.getBoundingClientRect();
    return (!header || element.getBoundingClientRect().top >= header.bottom - 1) && backRect && nextRect &&
      Math.abs(backRect.top - nextRect.top) <= 1 && backRect.right <= nextRect.left;
  }), { message: 'The guide clears the header and keeps Back and Next side by side' }).toBe(true);
}

async function expectLessonState(page, id) {
  await expect(page.locator('.read-only-modal:not(.ui-setup-guide), .ui-filter-dialog')).toHaveCount(0);
  const events = page.getByRole('tab', { name: 'Events', exact: true });
  const messages = page.getByRole('tab', { name: 'Messages', exact: true });
  const channel = page.getByRole('tab', { name: 'Channel', exact: true });
  const selectedTab = ['messages', 'channel-tab'].includes(id) ? messages :
    ['channel', 'workspace-size', 'presentation'].includes(id) ? channel : events;
  await expect(selectedTab).toHaveAttribute('aria-selected', 'true');
  if (id === 'views') {
    const target = page.locator('[data-guide-target="lv-views"]');
    for (const name of ['County P25', 'City P25', 'Conventional']) await expect(target).toContainText(name);
  }
  if (id === 'activity') await expect(page.locator('[data-guide-target="lv-activity"]')).toContainText('Sheriff Dispatch');
  if (id === 'reception') {
    const target = page.locator('[data-guide-target="lv-reception"]');
    await expect(target.first()).toBeVisible();
    await expect(page.locator('.channels-live-table')).toContainText(/\d+\.\d/);
  }
  if (id === 'select-channel') {
    const target = page.locator('[data-guide-target="lv-select-channel"]');
    await expect(target).toContainText('Sheriff Dispatch');
    await expect(target).not.toHaveAttribute('aria-selected', 'true');
  }
  if (steps.indexOf(id) >= steps.indexOf('detail-views'))
    await expect(page.locator('.channels-live-table tr[aria-selected="true"]')).toContainText('Sheriff Dispatch');
  if (id === 'detail-views') await expect(page.locator('[data-guide-target="lv-detail-views"]')).toHaveAttribute('role', 'tablist');
  if (id === 'events') {
    await expect(page.locator('.live-events-table tbody tr[data-id]').first()).toBeVisible();
    await expect(page.locator('.live-events-table')).toContainText('Sheriff');
  }
  if (id === 'pause') await expect(page.getByRole('button', { name: 'Pause live details', exact: true }))
    .toHaveAttribute('aria-pressed', 'false');
  if (id === 'event-filters') await expect(page.getByRole('button', { name: 'Filter events', exact: true })).toBeVisible();
  if (id === 'messages-tab') await expect(messages).toHaveAttribute('aria-selected', 'false');
  if (id === 'messages') {
    await expect(page.locator('.live-messages-table tbody tr[data-id]').first()).toBeVisible();
    await expect(page.locator('.live-messages-table')).toContainText(/GRANT|GROUP|Group|Talkgroup/i);
  }
  if (id === 'channel-tab') await expect(channel).toHaveAttribute('aria-selected', 'false');
  if (id === 'channel') {
    const canvases = page.locator('.live-channel-pane canvas.channel-diagnostic-canvas');
    await expect(canvases).toHaveCount(2);
    await expect(page.getByRole('img', { name: 'Selected channel signal spectrum', exact: true })).toBeVisible();
    await expect(page.getByRole('img', { name: 'Selected channel demodulated symbols', exact: true })).toBeVisible();
    await expect.poll(() => canvases.evaluateAll(elements => elements.map(canvas => {
      const rgba = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
      const colors = new Set();
      for (let offset = 0; offset < rgba.length; offset += 4) colors.add(rgba.slice(offset, offset + 4).join(','));
      return colors.size;
    }).every(count => count > 15)), { message: 'Both production diagnostic canvases paint real sample frames' }).toBe(true);
    for (const canvas of await canvases.all()) {
      const first = await canvas.evaluate(element => element.toDataURL());
      await expect.poll(() => canvas.evaluate((element, before) => element.toDataURL() !== before, first),
        { message: 'The production diagnostic animates while its lesson is read' }).toBe(true);
    }
    await expect(page.locator('.channel-diagnostic-overlay:visible')).toHaveCount(0);
  }
  if (id === 'workspace-size') await expect(page.locator('[data-guide-target="lv-workspace-size"]')).toHaveAttribute('role', 'separator');
  if (id === 'presentation') await expect(page.getByRole('button', { name: 'Live presentation settings', exact: true })).toBeVisible();
}

function expectIsolated(fixture, effects) {
  expect(effects.writes).toEqual([]);
  expect(effects.samples).toEqual([]);
  expect(effects.audio).toEqual([]);
  expect(effects.sockets).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
}

async function screenshot(page, name) {
  fs.mkdirSync(path.join(evidence, 'screens'), { recursive: true });
  await page.screenshot({ path: path.join(evidence, 'screens', `${name}.png`) });
}

test('Live sample controls cannot persist settings, operate receivers, play audio, or open unexplained dialogs', async ({ page }) => {
  const { fixture, effects } = await openLive(page);
  await start(page);
  const before = await nonGuideStorage(page), url = page.url();
  let exercised = 0;
  for (const id of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    exercised += await page.evaluate(() => {
      const roots = [...document.querySelectorAll('[data-guide-sample]')];
      const controls = [...new Set(roots.flatMap(root => [...root.querySelectorAll('button, a, input, select, form, canvas')]))];
      for (const control of controls) {
        control.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }));
        if (control.tagName === 'FORM') control.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      }
      return controls.length;
    });
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    await expect(page.locator('.read-only-modal:not(.ui-setup-guide)')).toHaveCount(0);
    await expect(page).toHaveURL(url);
    await advance(page, id);
  }
  expect(exercised).toBeGreaterThan(50);
  expect(await nonGuideStorage(page)).toEqual(before);
  expectIsolated(fixture, effects);
});

test('Live navigation releases samples and diagnostics before another page and a fresh Live visit', async ({ page }) => {
  const { fixture, effects } = await openLive(page);
  await start(page);
  await goTo(page, 'channel');
  await page.locator('.primary-nav [data-view="dashboard"]').evaluate(link => link.click());
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(page.locator('[data-guide-sample], [data-guide-example], .ui-setup-guide, .read-only-modal')).toHaveCount(0);
  await page.locator('.primary-nav [data-view="live"]').evaluate(link => link.click());
  await expect(page.locator('.channels-live-table tbody tr[data-id]')).toHaveCount(0);
  await expect(page.locator('.channel-diagnostic-overlay').first()).toContainText('Select a live row');
  expectIsolated(fixture, effects);
});

test('Live dismissal during preparation cannot restore sample content or restart diagnostic plots', async ({ page }) => {
  const { fixture, effects } = await openLive(page, { replay: false });
  await page.locator('#page-guide-button').evaluate(button => {
    button.click();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
  });
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], [data-guide-example]')).toHaveCount(0);
  await expect(page.locator('.channels-live-table tbody tr[data-id]')).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await start(page);
  await page.keyboard.press('Escape');
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], [data-guide-example]')).toHaveCount(0);
  expectIsolated(fixture, effects);
});
