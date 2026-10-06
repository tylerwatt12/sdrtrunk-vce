'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { writeFileSync } = require('node:fs');
const { pathToFileURL } = require('node:url');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

// Different LCN, frequency and activation orders make every selection observable.
const snapshot = {
  revision: 1,
  tables: [{ table_id: 'metro-north', configuration_id: 'metro-north', title: 'Metro North',
    system_name: 'Metro', site_name: 'North', channel_running: true,
    rows: [
      { key: 'lcn-30', configuration_id: 'metro-north', lcn: 30,
        frequency_hz: 851_012_500, status: 'ACTIVE', activation_order: 2 },
      { key: 'lcn-10', configuration_id: 'metro-north', lcn: 10,
        frequency_hz: 851_062_500, status: 'ACTIVE', activation_order: 3 },
      { key: 'lcn-20', configuration_id: 'metro-north', lcn: 20,
        frequency_hz: 851_037_500, status: 'ACTIVE', activation_order: 1 },
      { key: 'lcn-5', configuration_id: 'metro-north', lcn: 5,
        frequency_hz: 851_050_000, status: 'IDLE', activation_order: 0 }
    ] }]
};

function densitySnapshot() {
  return { revision: 1, tables: [{ ...snapshot.tables[0], title: 'County Public Safety',
    system_name: 'County Public Safety', site_name: 'North Simulcast',
    rows: Array.from({ length: 36 }, (_, index) => ({
      key: `call-${index + 1}`, configuration_id: 'metro-north', lcn: index + 1,
      frequency_hz: 851012500 + index * 25000, status: index === 3 ? 'ENCRYPTED' : 'CALL',
      tags: ['VOICE'], activation_order: index + 1, decoder: 'P25_PHASE1', protocol: 'APCO25',
      source_id: 30914 + index, source_alias: index === 0 ? 'Emergency Management Operations Supervisor — Engine 4' : `Unit ${index + 1}`,
      target_id: 1201 + index % 3, target_alias: index === 0 ? 'County Fire and Emergency Medical Services Dispatch' :
        ['Fire Dispatch', 'Police Dispatch', 'County Operations'][index % 3],
      signal_dbfs: -58.2 - index / 10, vc_quality_pct: 92.4,
      vc_decoded_frames: 120, vc_repeated_frames: 1, vc_concealed_frames: 0
    })) }] };
}

async function openLive(page, theme = 'light', presentation = {}, liveSnapshot = snapshot) {
  let revision = 1;
  let preferences = structuredClone(defaultPreferences);
  preferences.appearance.theme = theme;
  preferences.presentation.source_name_display = 'both';
  Object.assign(preferences.presentation, presentation);
  let nextSaveFailure = null;
  const writes = [];
  const reads = [];
  const receiverWrites = [];
  await page.addInitScript((liveSnapshot) => {
    const originalFetch = window.fetch.bind(window);
    const encoder = new TextEncoder();
    const frame = (topic, event, data) => {
      const payload = encoder.encode(JSON.stringify({ event, data }));
      const bytes = new Uint8Array(16 + payload.length);
      const header = new DataView(bytes.buffer);
      header.setUint32(0, 0x534c4d58);
      header.setUint8(4, 2);
      header.setUint8(5, 1);
      header.setUint16(6, topic);
      header.setUint32(8, payload.length);
      bytes.set(payload, 16);
      return bytes;
    };
    window.fetch = (input, options) => {
      const url = new URL(typeof input === 'string' ? input : input.url, location.href);
      if (url.pathname === '/api/v1/live/multiplex/control') {
        const body = JSON.parse(options?.body || '{}');
        if (body.subscriptions?.channel_activity) window.fixtureSendLiveSnapshot?.();
        return Promise.resolve(new Response(JSON.stringify({ data: { accepted: true } }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      if (url.pathname !== '/api/v1/live/multiplex') return originalFetch(input, options);
      const stream = new ReadableStream({
        start(controller) {
          window.fixtureSendLiveSnapshot = () => controller.enqueue(frame(1, 'snapshot', liveSnapshot));
          controller.enqueue(frame(0, 'ready', { client_id: url.searchParams.get('client_id') }));
          window.fixtureSendLiveSnapshot();
        }
      });
      return Promise.resolve(new Response(stream, { status: 200 }));
    };
  }, liveSnapshot);
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: { data: { configured: true, authenticated: true,
        username: 'operator', tier: 'admin', primary: true,
        capabilities: { live: true, radio: true, 'call-audio': true, 'user-settings': true } } } });
    } else if (pathname === '/api/v1/me/preferences') {
      if (request.method() === 'PUT') {
        expect(request.headers()['if-match']).toBe(`"${revision}"`);
        if (nextSaveFailure) {
          const failure = nextSaveFailure;
          nextSaveFailure = null;
          if (failure.presentation) {
            Object.assign(preferences.presentation, failure.presentation);
            revision += 1;
          }
          await route.fulfill({ status: failure.status, json: { error: { message: 'Unable to save preferences.' } } });
          return;
        }
        preferences = request.postDataJSON();
        writes.push(structuredClone(preferences));
        revision += 1;
      } else reads.push(structuredClone(preferences));
      await route.fulfill({ json: { revision, preferences } });
    } else if (pathname === '/api/v1/live/multiplex/control') {
      await route.fulfill({ json: { data: { accepted: true } } });
    } else {
      if (request.method() !== 'GET') receiverWrites.push(pathname);
      await route.fulfill({ status: 404, json: { error: { message: 'Unavailable' } } });
    }
  });
  await page.goto('/app.html?view=live&channel=metro-north');
  return { writes, reads, receiverWrites, preferences: () => preferences,
    failNextSave: (status, presentation = null) => { nextSaveFailure = { status, presentation }; } };
}

async function presentationDialog(page) {
  await page.getByRole('button', { name: 'Live presentation settings', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Live presentation', exact: true });
  await expect(dialog).toBeVisible();
  return dialog;
}

async function savePresentation(dialog) {
  await dialog.getByRole('button', { name: 'Save Live Presentation', exact: true }).click();
  await expect(dialog).toBeHidden();
}

async function expectCallOrder(page, keys) {
  const table = page.locator('.channels-live-table');
  await expect(table.locator('tbody tr[data-id]')).toHaveCount(keys.length);
  await expect.poll(() => table.locator('tbody tr[data-id]')
    .evaluateAll((rows) => rows.map((row) => row.dataset.id))).toEqual(keys);
  await expect(table.locator('thead th button')).toHaveCount(0);
}

const idleRowOptionLabels = ['Retain the last call on idle rows', 'Clear voice quality when a row becomes idle'];

async function expectIdleRowOptions(dialog, disabled, checked = false) {
  for (const label of idleRowOptionLabels) {
    const input = dialog.getByRole('checkbox', { name: label, exact: true });
    if (disabled) await expect(input).toBeDisabled();
    else await expect(input).toBeEnabled();
    if (checked) await expect(input).toBeChecked();
    else await expect(input).not.toBeChecked();
    await expect(input.locator('..').locator('.ui-toggle-state')).toHaveText(checked ? 'On' : 'Off');
    if (disabled) await expect(input.locator('..')).toHaveCSS('opacity', '0.48');
  }
}

test('Live idle-row options require visible idle rows on load, toggle, save and personal reset', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const app = await openLive(page, 'light', {
    show_only_active_trunked_channels: true,
    retain_last_call_on_idle_rows: true,
    clear_voice_quality_when_idle: true
  });
  let dialog = await presentationDialog(page);
  const activeOnly = () => dialog.getByRole('checkbox', { name: 'Show only active trunked channels', exact: true });
  await expectIdleRowOptions(dialog, true);
  await dialog.screenshot({ path: test.info().outputPath('live-idle-options-light-desktop.png') });
  expect(app.writes).toEqual([]);
  await expect(dialog.getByText('Turn off “Show only active trunked channels” to use idle-row options.',
    { exact: true })).toBeVisible();
  await activeOnly().press('Space');
  await expectIdleRowOptions(dialog, false);
  for (const label of idleRowOptionLabels) await dialog.getByRole('checkbox', { name: label, exact: true }).press('Space');
  await expectIdleRowOptions(dialog, false, true);
  await activeOnly().press('Space');
  await expectIdleRowOptions(dialog, true);
  await activeOnly().press('Space');
  await expectIdleRowOptions(dialog, false);
  for (const label of idleRowOptionLabels) await dialog.getByRole('checkbox', { name: label, exact: true }).press('Space');
  await savePresentation(dialog);
  expect(app.preferences().presentation).toMatchObject({
    show_only_active_trunked_channels: false, retain_last_call_on_idle_rows: true,
    clear_voice_quality_when_idle: true
  });
  dialog = await presentationDialog(page);
  await expectIdleRowOptions(dialog, false, true);
  await activeOnly().press('Space');
  await expectIdleRowOptions(dialog, true);
  // Disabled inputs can still hold stale browser state; submitted preferences must enforce the dependency.
  for (const label of idleRowOptionLabels) {
    await dialog.getByRole('checkbox', { name: label, exact: true }).evaluate((input) => { input.checked = true; });
  }
  await savePresentation(dialog);
  expect(app.preferences().presentation).toMatchObject({
    show_only_active_trunked_channels: true, retain_last_call_on_idle_rows: false,
    clear_voice_quality_when_idle: false
  });

  await page.goto('/app.html?view=settings');
  await page.getByRole('button', { name: 'Reset All Personal Preferences', exact: true }).press('Enter');
  const resetDialog = page.getByRole('dialog', { name: 'Reset personal preferences', exact: true });
  await resetDialog.getByRole('button', { name: 'Reset All Personal Preferences', exact: true }).click();
  await expect(resetDialog).toBeHidden();
  await page.goto('/app.html?view=live&channel=metro-north');
  dialog = await presentationDialog(page);
  await expect(activeOnly()).toBeChecked();
  await expectIdleRowOptions(dialog, true);
  expect(app.receiverWrites).toEqual([]);
});

test('Live idle-row options stay disabled after save failures and follow reloaded preferences', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const app = await openLive(page, 'dark');
  const dialog = await presentationDialog(page);
  const save = dialog.getByRole('button', { name: 'Save Live Presentation', exact: true });
  const message = dialog.locator('.admin-form-message');
  await expectIdleRowOptions(dialog, true);
  await dialog.screenshot({ path: test.info().outputPath('live-idle-options-dark-phone.png') });
  app.failNextSave(500);
  await save.click();
  await expect(message).toHaveText('Unable to save user preferences.');
  await expect(save).toBeEnabled();
  await expectIdleRowOptions(dialog, true);
  await expect(dialog.getByRole('checkbox', { name: 'Show only active trunked channels', exact: true })).toBeEnabled();

  app.failNextSave(409, {
    show_only_active_trunked_channels: false, retain_last_call_on_idle_rows: true,
    clear_voice_quality_when_idle: true
  });
  await save.click();
  await expect(message).toHaveText('These settings changed in another session. The current saved values were loaded.');
  await expect(save).toBeEnabled();
  await expectIdleRowOptions(dialog, false, true);

  app.failNextSave(409, {
    show_only_active_trunked_channels: true, retain_last_call_on_idle_rows: true,
    clear_voice_quality_when_idle: true
  });
  await save.click();
  await expect(save).toBeEnabled();
  await expectIdleRowOptions(dialog, true);
  expect(app.writes).toEqual([]);
  expect(app.receiverWrites).toEqual([]);
});

test('Live presentation switches sort defaults and saves explicit sorting through rerenders', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const app = await openLive(page);
  await expectCallOrder(page, ['lcn-20', 'lcn-30', 'lcn-10']);
  let dialog = await presentationDialog(page);
  const sort = () => dialog.getByRole('combobox', { name: /^Sort calls by/ });
  const activeOnly = () => dialog.getByRole('checkbox', { name: 'Show only active trunked channels', exact: true });
  await expect(activeOnly()).toBeChecked();
  await expect(sort()).toHaveValue('order_appeared');
  await expect(sort().locator('option')).toHaveText(['LCN', 'Order appeared', 'Frequency']);

  await activeOnly().press('Space');
  await expect(activeOnly()).not.toBeChecked();
  await expect(sort()).toHaveValue('lcn');
  await savePresentation(dialog);
  await expectCallOrder(page, ['lcn-5', 'lcn-10', 'lcn-20', 'lcn-30']);
  expect(app.preferences().presentation.live_channel_sort).toBe('lcn');

  dialog = await presentationDialog(page);
  await sort().selectOption('order_appeared');
  await savePresentation(dialog);
  await expectCallOrder(page, ['lcn-20', 'lcn-30', 'lcn-10', 'lcn-5']);

  dialog = await presentationDialog(page);
  await sort().selectOption('frequency');
  await savePresentation(dialog);
  await expectCallOrder(page, ['lcn-30', 'lcn-20', 'lcn-5', 'lcn-10']);
  // The app canonicalizes its URL to /, which this harness reserves for its gallery.
  await page.goto('/app.html?view=live&channel=metro-north');
  await expectCallOrder(page, ['lcn-30', 'lcn-20', 'lcn-5', 'lcn-10']);
  expect(app.reads.at(-1).presentation.source_name_display).toBe('both');
  dialog = await presentationDialog(page);
  await expect(sort()).toHaveValue('frequency');
  await expect(activeOnly()).not.toBeChecked();

  await activeOnly().press('Space');
  await expect(activeOnly()).toBeChecked();
  await expect(sort()).toHaveValue('order_appeared');
  await savePresentation(dialog);
  await expectCallOrder(page, ['lcn-20', 'lcn-30', 'lcn-10']);
  dialog = await presentationDialog(page);
  await sort().selectOption('frequency');
  await savePresentation(dialog);
  await expectCallOrder(page, ['lcn-30', 'lcn-20', 'lcn-10']);
  await page.goto('/app.html?view=live&channel=metro-north');
  await expectCallOrder(page, ['lcn-30', 'lcn-20', 'lcn-10']);
  expect(app.reads.at(-1).presentation.source_name_display).toBe('both');
  dialog = await presentationDialog(page);
  await expect(sort()).toHaveValue('frequency');
  await expect(activeOnly()).toBeChecked();
  expect(app.writes.map((write) => write.presentation.live_channel_sort))
    .toEqual(['lcn', 'order_appeared', 'frequency', 'order_appeared', 'frequency']);
  expect(app.writes.map((write) => write.presentation.source_name_display))
    .toEqual(Array(5).fill('both'));
  expect(app.receiverWrites).toEqual([]);
});

async function expectReachable(control) {
  await control.scrollIntoViewIfNeeded();
  // Center fields in the shared scrolling dialog above its sticky save footer.
  await control.evaluate((element) => element.scrollIntoView({ block: 'center' }));
  await expect(control).toBeVisible();
  const geometry = await control.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    const hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
    return { reachable: element === hit || element.contains(hit),
      top: rect.top, bottom: rect.bottom, hitClass: hit?.className,
      hitText: hit?.textContent?.slice(0, 80) };
  });
  expect(geometry.reachable, JSON.stringify(geometry)).toBe(true);
}

const liveTable = page => page.locator('.channels-live-table');
const rowDensity = dialog => dialog.getByRole('combobox', { name: 'Row density', exact: true });

async function densityGeometry(page) {
  return liveTable(page).evaluate(table => {
    const scroll = table.closest('.table-scroll');
    const viewport = scroll.getBoundingClientRect();
    const rows = [...table.querySelectorAll('tbody tr[data-id]')];
    const height = rows[0].getBoundingClientRect().height;
    const rowStyle = getComputedStyle(rows[0]);
    const cells = [...rows[0].querySelectorAll('td')].filter(cell => cell.getClientRects().length);
    const cellPadding = cells.map(cell => {
      const style = getComputedStyle(cell);
      return { column: cell.dataset.column, top: Number.parseFloat(style.paddingTop),
        bottom: Number.parseFloat(style.paddingBottom) };
    });
    const lineHeight = Math.max(...cells.map(cell => Number.parseFloat(getComputedStyle(cell).lineHeight)));
    const visible = rows.filter(row => {
      const rect = row.getBoundingClientRect();
      const x = Math.max(viewport.left + 1, Math.min(viewport.right - 1, rect.left + rect.width / 2));
      return rect.top >= Math.max(0, viewport.top) && rect.bottom <= Math.min(innerHeight, viewport.bottom) &&
        row.contains(document.elementFromPoint(x, rect.bottom - 1));
    }).length;
    const panes = Object.fromEntries(['.live-split', '.live-right-workspace', '.live-channels-section', '.live-details']
      .map(selector => {
        const element = document.querySelector(selector);
        const rect = element.getBoundingClientRect();
        return [selector, { x: rect.x, y: rect.y, width: rect.width, height: rect.height }];
      }));
    return { rowHeight: height, fullyVisibleRows: visible, bodyHeight: table.querySelector('tbody').getBoundingClientRect().height,
      rowPadding: { top: Number.parseFloat(rowStyle.paddingTop), bottom: Number.parseFloat(rowStyle.paddingBottom) },
      rowGap: rowStyle.rowGap, cellPadding, maximumLineHeight: lineHeight, panes };
  });
}

async function liveCallContents(page) {
  return liveTable(page).evaluate(table => ({
    columns: [...table.querySelectorAll('thead th')].map(cell => cell.dataset.column),
    rows: [...table.querySelectorAll('tbody tr[data-id]')].map(row => ({
      key: row.dataset.id, cells: [...row.querySelectorAll('td')].map(cell => cell.textContent)
    }))
  }));
}

async function liveCellTypography(page) {
  return liveTable(page).evaluate(table => [...table.querySelectorAll('tbody tr[data-id]')].map(row => ({
    key: row.dataset.id, cells: [...row.querySelectorAll('td')].map(cell => {
      const style = getComputedStyle(cell);
      return { column: cell.dataset.column, family: style.fontFamily, size: style.fontSize,
        weight: style.fontWeight, lineHeight: style.lineHeight };
    })
  })));
}

async function expectNoVerticalGlyphClipping(page) {
  const clipped = await liveTable(page).evaluate(table => {
    const result = [];
    for (const cell of table.querySelectorAll('tbody tr[data-id] td')) {
      if (!cell.getClientRects().length) continue;
      const bounds = cell.getBoundingClientRect();
      const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT);
      while (walker.nextNode()) {
        const text = walker.currentNode;
        if (!text.textContent.trim() || text.parentElement.closest('.visually-hidden, [hidden]')) continue;
        const range = document.createRange();
        range.selectNodeContents(text);
        if ([...range.getClientRects()].some(rect => rect.height &&
          (rect.top < bounds.top - 1 || rect.bottom > bounds.bottom + 1))) {
          result.push({ row: cell.closest('tr').dataset.id, column: cell.dataset.column, text: text.textContent });
        }
      }
    }
    return result;
  });
  expect(clipped, 'Dense rows must retain room for the unchanged text glyphs').toEqual([]);
}

for (const theme of ['light', 'dark']) {
  for (const width of [1280, 390]) {
    test(`Live row density compacts calls without changing content or selection in ${theme} at ${width}px`,
      async ({ page }, testInfo) => {
        await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
        const app = await openLive(page, theme, {}, densitySnapshot());
        const table = liveTable(page);
        await expect(table).toHaveAttribute('data-row-density', 'normal');
        await expect(table.locator('tbody tr[data-id]')).toHaveCount(36);
        await table.locator('tbody tr[data-id="call-2"] [data-column="status"]').click();
        await expect(table.locator('tbody tr.selected')).toHaveAttribute('data-id', 'call-2');
        if (width === 390) await expect(page.getByRole('button', { name: 'Expand live details', exact: true })).toBeVisible();
        const contents = await liveCallContents(page);
        const typography = await liveCellTypography(page);
        const normal = await densityGeometry(page);
        await expectNoVerticalGlyphClipping(page);
        await page.screenshot({ path: testInfo.outputPath('live-normal.png') });
        let dialog = await presentationDialog(page);
        const density = rowDensity(dialog);
        await expect(density).toHaveValue('normal');
        await expect(density.locator('option')).toHaveText(['Normal', 'Dense']);
        await expectReachable(density);
        await density.selectOption('dense');
        await density.focus();
        await expect(density).toBeFocused();
        await dialog.screenshot({ path: testInfo.outputPath('live-row-density-settings.png') });
        await savePresentation(dialog);
        await expect(table).toHaveAttribute('data-row-density', 'dense');
        await expect(table.locator('tbody tr.selected')).toHaveAttribute('data-id', 'call-2');
        expect(await liveCallContents(page)).toEqual(contents);
        expect(await liveCellTypography(page)).toEqual(typography);
        const dense = await densityGeometry(page);
        expect(dense.cellPadding.every(cell => cell.top === 0 && cell.bottom === 0)).toBe(true);
        if (width === 1280) expect(dense.rowHeight).toBeLessThanOrEqual(dense.maximumLineHeight + 1);
        else {
          expect(dense.rowPadding).toEqual({ top: 0, bottom: 0 });
          expect(dense.rowGap).toBe('0px');
        }
        await expectNoVerticalGlyphClipping(page);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        expect(dense.rowHeight).toBeLessThan(normal.rowHeight);
        expect(dense.bodyHeight).toBeLessThan(normal.bodyHeight);
        expect(dense.fullyVisibleRows).toBeGreaterThan(normal.fullyVisibleRows);
        if (width === 1280) expect(dense.panes).toEqual(normal.panes);
        const geometryPath = testInfo.outputPath('row-density-geometry.json');
        writeFileSync(geometryPath, JSON.stringify({ normal, dense, unchangedTypography: typography[0].cells }, null, 2));
        await testInfo.attach('Row density geometry', { path: geometryPath, contentType: 'application/json' });
        await page.screenshot({ path: testInfo.outputPath('live-dense.png') });
        expect(app.preferences().presentation.live_row_density).toBe('dense');
        expect(app.writes[0].version).toBe(11);
        expect(app.preferences().presentation.source_name_display).toBe('both');
        if (theme === 'light' && width === 1280) {
          const dock = page.locator('#audio-dock');
          const handle = dock.getByRole('button', { name: 'Change audio player size', exact: true });
          for (const [key, size] of [['Home', 'collapsed'], ['ArrowUp', 'minimal'], ['End', 'full']]) {
            await handle.press(key);
            await expect(dock).toHaveAttribute('data-state', size);
            await expect.poll(async () => (await densityGeometry(page)).panes).toEqual(dense.panes);
          }
        }
        await page.goto('/app.html?view=live&channel=metro-north');
        await expect(table).toHaveAttribute('data-row-density', 'dense');
        expect(await liveCallContents(page)).toEqual(contents);
        dialog = await presentationDialog(page);
        await expect(rowDensity(dialog)).toHaveValue('dense');
        expect(app.receiverWrites).toEqual([]);
        if ((theme === 'light' && width === 1280) || (theme === 'dark' && width === 390)) {
          await page.goto(`/design-system.html?view=live-notice&theme=${theme}&row-density=dense`);
          await expect(liveTable(page)).toHaveAttribute('data-row-density', 'dense');
          await page.screenshot({ path: testInfo.outputPath('gallery-live-dense.png') });
        }
      });
  }
}

test('Live density keeps a failed draft, reloads a conflict and resets to Normal', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const app = await openLive(page, 'dark');
  const dialog = await presentationDialog(page);
  const density = rowDensity(dialog);
  const save = dialog.getByRole('button', { name: 'Save Live Presentation', exact: true });
  const message = dialog.locator('.admin-form-message');
  await density.selectOption('dense');
  app.failNextSave(500);
  await save.click();
  await expect(message).toHaveText('Unable to save user preferences.');
  await expect(density).toHaveValue('dense');
  await expect(density).toBeEnabled();
  await expect(liveTable(page)).toHaveAttribute('data-row-density', 'normal');
  expect(app.preferences().presentation.live_row_density).toBe('normal');
  await expectReachable(density);
  await dialog.screenshot({ path: testInfo.outputPath('live-row-density-failed-save.png') });
  app.failNextSave(409, { live_row_density: 'normal', live_channel_sort: 'frequency' });
  await save.click();
  await expect(message).toHaveText('These settings changed in another session. The current saved values were loaded.');
  await expect(density).toHaveValue('normal');
  await expect(dialog.getByRole('combobox', { name: /^Sort calls by/ })).toHaveValue('frequency');
  expect(app.writes).toEqual([]);
  await density.selectOption('dense');
  await savePresentation(dialog);
  await expect(liveTable(page)).toHaveAttribute('data-row-density', 'dense');
  expect(app.preferences().presentation.live_channel_sort).toBe('frequency');
  await page.goto('/app.html?view=settings');
  await expect(page.locator('.user-settings-summary')).toContainText('Row density');
  await expect(page.locator('.user-settings-summary')).toContainText('Dense');
  await page.getByRole('button', { name: 'Reset All Personal Preferences', exact: true }).click();
  const reset = page.getByRole('dialog', { name: 'Reset personal preferences', exact: true });
  await reset.getByRole('button', { name: 'Reset All Personal Preferences', exact: true }).click();
  await expect(reset).toBeHidden();
  await expect(page.locator('.user-settings-summary')).toContainText('Normal');
  await page.goto('/app.html?view=live&channel=metro-north');
  await expect(liveTable(page)).toHaveAttribute('data-row-density', 'normal');
  await expect(rowDensity(await presentationDialog(page))).toHaveValue('normal');
  expect(app.preferences().presentation.live_row_density).toBe('normal');
  expect(app.receiverWrites).toEqual([]);
});

for (const [name, saved] of [['missing', undefined], ['invalid', 'compressed']]) {
  test(`Live uses Normal for ${name} saved row density`, async ({ page }) => {
    const app = await openLive(page, 'light', { live_row_density: saved });
    await expect(liveTable(page)).toHaveAttribute('data-row-density', 'normal');
    // An invalid saved document keeps the existing safe defaults until preferences can load again.
    await expect(page.locator('#preference-status')).toBeVisible();
    await expect(page.locator('#preference-status')).toContainText('My Settings are unavailable.');
    await expectCallOrder(page, ['lcn-20', 'lcn-30', 'lcn-10']);
    expect(app.writes).toEqual([]);
    expect(app.receiverWrites).toEqual([]);
  });
}

for (const theme of ['light', 'dark']) {
  for (const viewport of [{ width: 1280, height: 900 }, { width: 320, height: 740 }]) {
    test(`Live sort control remains reachable in ${theme} at ${viewport.width}px`, async ({ page }) => {
      await page.setViewportSize(viewport);
      const app = await openLive(page, theme);
      const dialog = await presentationDialog(page);
      const sort = dialog.getByRole('combobox', { name: /^Sort calls by/ });
      await expectReachable(sort);
      await sort.focus();
      await expect(sort).toBeFocused();
      await expect(sort.locator('option')).toHaveText(['LCN', 'Order appeared', 'Frequency']);
      const screenshot = test.info().outputPath(`live-sort-${theme}-${viewport.width}.png`);
      await page.screenshot({ path: screenshot });
      await test.info().attach('Live presentation sort', { path: screenshot, contentType: 'image/png' });
      expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width);
      await expectReachable(dialog.getByRole('button', { name: 'Save Live Presentation', exact: true }));
      expect(app.receiverWrites).toEqual([]);
    });
  }
}
