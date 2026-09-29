'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

const call = {
  id: 17, start_ms: Date.parse('2026-09-28T10:08:42Z'), duration_ms: 42_000,
  system_name: 'Metro Public Safety', talkgroup_id: 1201, talkgroup_alias: 'Fire Dispatch',
  source_id: 30914, source_alias: 'Engine 4', source_ota_alias: 'ENG 4',
  site_name: 'North Ridge', call_type: 'GROUP', protocol: 'APCO25_PHASE2', voice_type: 'CLEAR'
};

async function openRecordings(page, options = {}) {
  const state = {
    mode: options.mode || 'MANAGED', available: options.available !== false,
    hasCalls: options.hasCalls !== false, matchCalls: true, admin: options.admin !== false,
    requestedFilters: []
  };
  const preferences = structuredClone(defaultPreferences);
  preferences.appearance.theme = options.theme || 'light';
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const pathname = url.pathname;
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: { data: { configured: true, authenticated: state.admin,
        username: state.admin ? 'admin' : null, tier: state.admin ? 'admin' : 'public',
        primary: state.admin, capabilities: { recordings: true,
          'admin-recordings': state.admin, 'admin-settings': state.admin, dashboard: true } } } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } });
    } else if (pathname === '/api/v1/recordings/status') {
      await route.fulfill({ json: { data: { mode: state.mode, available: state.available,
        has_calls: state.hasCalls } } });
    } else if (pathname === '/api/v1/recordings/calls') {
      state.requestedFilters.push(Object.fromEntries(url.searchParams));
      const matching = state.hasCalls && state.matchCalls;
      await route.fulfill({ json: { data: { calls: matching ? [call] : [],
        next_cursor: null, total: null } } });
    } else if (pathname === '/api/v1/recordings/suggestions') {
      const q = url.searchParams.get('q') || '';
      if (q === 'error') {
        await route.fulfill({ status: 500, json: { error: { message: 'Suggestion error' } } });
      } else {
        if (q === 'slow') await new Promise((resolve) => setTimeout(resolve, 350));
        const suggestions = q.toLowerCase().includes('fire') ? [
          { kind: 'talkgroup', id: 1201, label: 'Fire Dispatch', detail: 'Metro Public Safety · TG 1201',
            system_key: 'metro', system_name: 'Metro Public Safety' }
        ] : [];
        await route.fulfill({ json: { data: { suggestions } } });
      }
    } else if (pathname === '/api/v1/status') {
      await route.fulfill({ json: { data: {} } });
    } else {
      await route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
    }
  });
  await page.goto('/app.html?view=recordings');
  return state;
}

test('Classic empty page invites only the primary administrator', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openRecordings(page, { mode: 'CLASSIC', hasCalls: false });
  await expect(page.getByRole('heading', { name: 'Make every call easy to find' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Enable Managed Recordings' }))
    .toHaveAttribute('href', '/?view=admin&tab=recordings');
  await expect(page.locator('.recordings-search')).toHaveCount(0);
  await expect(page.locator('.recordings-pager')).toHaveCount(0);
  await expect(page.locator('.recordings-library')).toHaveScreenshot('recordings-classic-admin-empty.png');

  await page.setViewportSize({ width: 390, height: 844 });
  await openRecordings(page, { mode: 'CLASSIC', hasCalls: false, admin: false, theme: 'dark' });
  await expect(page.getByRole('heading', { name: 'No managed recordings yet' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Enable Managed Recordings' })).toHaveCount(0);
  await expect(page.locator('.recordings-search')).toHaveCount(0);
  await expect(page.locator('.recordings-library')).toHaveScreenshot('recordings-classic-guest-empty.png');
});

test('Classic history remains searchable and unavailable catalog has no enable pitch', async ({ page }) => {
  await openRecordings(page, { mode: 'CLASSIC' });
  await expect(page.locator('.recordings-mode-notice')).toContainText('Earlier Managed calls remain searchable');
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  await expect(page.getByRole('combobox', { name: 'Find a call' })).toBeVisible();
  await expect(page.locator('.recordings-library')).toHaveScreenshot('recordings-classic-history.png');

  await openRecordings(page, { mode: 'CLASSIC', hasCalls: false, available: false });
  await expect(page.locator('.recordings-library')).toContainText('library is unavailable');
  await expect(page.getByRole('link', { name: 'Enable Managed Recordings' })).toHaveCount(0);
});

test('Managed empty page refreshes when the first call arrives', async ({ page }) => {
  const state = await openRecordings(page, { hasCalls: false });
  await expect(page.getByRole('heading', { name: 'Ready for the first call' })).toBeVisible();
  await expect(page.locator('.recordings-library')).toHaveScreenshot('recordings-managed-empty.png');
  state.hasCalls = true;
  await page.getByRole('button', { name: 'Refresh calls' }).click();
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  expect(state.requestedFilters.length).toBeGreaterThan(0);
});

for (const theme of ['light', 'dark']) {
  test(`desktop ${theme} search aligns controls and exposes structured autocomplete`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await openRecordings(page, { theme });
    await expect(page.locator('.recordings-call')).toHaveCount(1);
    const time = page.getByRole('combobox', { name: 'Date & time' });
    const query = page.getByRole('combobox', { name: 'Find a call' });
    const timeBox = await time.boundingBox();
    const queryBox = await query.boundingBox();
    expect(Math.abs(timeBox.y - queryBox.y)).toBeLessThan(2);
    expect(Math.abs(timeBox.height - queryBox.height)).toBeLessThan(2);
    expect(queryBox.width).toBeLessThan(470);
    await expect(page.getByRole('button', { name: 'More filters' })).toBeVisible();
    await expect(page.locator('.recordings-library')).toHaveScreenshot(`recordings-desktop-${theme}.png`);

    await query.fill('fire');
    const suggestions = page.getByRole('listbox');
    await expect(suggestions.getByRole('option')).toHaveCount(1);
    await expect(suggestions.getByRole('option')).toContainText('Talkgroup');
    await expect(suggestions.getByRole('option')).toContainText('Metro Public Safety');
    if (theme === 'light') await expect(page.locator('.recordings-library'))
      .toHaveScreenshot('recordings-suggestions-light.png');
    await query.press('ArrowDown');
    await expect(query).toHaveAttribute('aria-activedescendant', /recordings-options-q-0/);
    await query.press('Enter');
    await expect(suggestions).toBeHidden();
    await expect(page.locator('.recordings-selected-filters')).toContainText('Fire Dispatch');
    await page.getByRole('button', { name: 'Search', exact: true }).click();
    await expect(page.locator('.recordings-shared-overline')).toHaveText('ALL MATCHING CALLS');
    await expect(page.locator('.recordings-shared-heading')).toContainText('Fire Dispatch');
    if (theme === 'light') await expect(page.locator('.recordings-library'))
      .toHaveScreenshot('recordings-filtered-context-light.png');
    await page.getByRole('button', { name: /More filters/ }).click();
    await expect(page.getByRole('combobox', { name: 'Radio system' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Clear filters' })).toBeVisible();
  });
}

test('suggestions show no-match and failure states, then stay closed after blur', async ({ page }) => {
  await openRecordings(page);
  const query = page.getByRole('combobox', { name: 'Find a call' });
  await query.fill('zzzz');
  await expect(page.getByRole('listbox')).toContainText('No matching names or IDs');
  await query.fill('error');
  await expect(page.getByRole('listbox')).toContainText('Suggestions could not load');
  const requested = page.waitForRequest((request) => request.url().includes('/recordings/suggestions?q=slow'));
  await query.fill('slow');
  await requested;
  await page.getByRole('combobox', { name: 'Date & time' }).focus();
  await page.waitForTimeout(450);
  await expect(page.getByRole('listbox')).toBeHidden();
});

test('empty search results omit count and pagination', async ({ page }) => {
  const state = await openRecordings(page);
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  state.matchCalls = false;
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.locator('.recordings-results')).toContainText('No matching calls');
  await expect(page.locator('.recordings-result-count')).toBeHidden();
  await expect(page.locator('.recordings-pager')).toBeHidden();
});

test('search controls stay within the narrow desktop boundary', async ({ page }) => {
  await page.setViewportSize({ width: 768, height: 900 });
  await openRecordings(page);
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  const time = await page.getByRole('combobox', { name: 'Date & time' }).boundingBox();
  const query = await page.getByRole('combobox', { name: 'Find a call' }).boundingBox();
  expect(Math.abs(time.y - query.y)).toBeLessThan(2);
  expect(Math.abs(time.height - query.height)).toBeLessThan(2);
});

for (const theme of ['light', 'dark']) {
  test(`mobile ${theme} filter sheet fills viewport and applies fields`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const state = await openRecordings(page, { theme });
    await expect(page.locator('.recordings-call')).toHaveCount(1);
    await expect(page.locator('.recordings-library')).toHaveScreenshot(`recordings-mobile-${theme}.png`);
    await page.getByRole('button', { name: 'Filters' }).click();
    const sheet = page.getByRole('dialog', { name: 'Filters' });
    await expect(sheet).toBeVisible();
    const bounds = await sheet.boundingBox();
    expect(bounds.width).toBe(390);
    expect(bounds.height).toBe(844);
    await expect(sheet.getByRole('combobox', { name: 'Radio system' })).toBeVisible();
    await expect(sheet).toHaveScreenshot(`recordings-mobile-filters-${theme}.png`);
    await sheet.locator('.modal-content').evaluate((content) => { content.scrollTop = content.scrollHeight; });
    const lastField = await sheet.locator('.recordings-search-fields .recordings-field').last().boundingBox();
    const footer = await sheet.locator('.recordings-filter-sheet-actions').boundingBox();
    expect(lastField.y + lastField.height).toBeLessThanOrEqual(footer.y);
    await sheet.getByRole('button', { name: 'Show calls' }).click();
    await expect(sheet).toBeHidden();
    expect(state.requestedFilters.length).toBeGreaterThan(1);
  });
}
