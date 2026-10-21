'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { expectFlatFacts } = require('./fixtures/fact-geometry.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

// Check the visible relationship between surfaces rather than tying the
// contract to a token name, selector implementation, or a particular hue.
async function expectFilledWithoutFrame(elements) {
  expect(await elements.count()).toBeGreaterThan(0);
  const surfaces = await elements.evaluateAll((items) => items.map((element) => {
    const transparent = (color) => color === 'transparent' || color === 'rgba(0, 0, 0, 0)';
    let owner = element.parentElement;
    while (owner && transparent(getComputedStyle(owner).backgroundColor)) owner = owner.parentElement;
    const style = getComputedStyle(element);
    return { background: style.backgroundColor, owner: owner && getComputedStyle(owner).backgroundColor,
      border: [style.borderTopWidth, style.borderRightWidth, style.borderBottomWidth, style.borderLeftWidth],
      overflow: element.scrollWidth - element.clientWidth };
  }));
  for (const surface of surfaces) {
    expect(surface.background).not.toBe('rgba(0, 0, 0, 0)');
    expect(surface.background).not.toBe(surface.owner);
    expect(surface.border).toEqual(['0px', '0px', '0px', '0px']);
    expect(surface.overflow).toBeLessThanOrEqual(1);
  }
}

async function expectFieldBoundaryAndFocus(field) {
  await expect(field).toBeEnabled();
  await expect(field).toHaveCSS('border-top-width', '1px');
  await field.focus();
  await expect(field).toBeFocused();
  await expect(field).not.toHaveCSS('box-shadow', 'none');
}

async function installSettingsAndMap(page, theme) {
  const preferences = structuredClone(defaults);
  preferences.appearance.theme = theme;
  const state = { pending: [], writes: [], initial: null, revision: 4, value: 2000 };
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({
    contentType: 'image/png', body: Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64')
  }));
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const data = (value) => route.fulfill({ json: { data: value } });
    if (pathname === '/api/v1/auth/session') return data({ configured: true, authenticated: true,
      username: 'operator', tier: 'admin', primary: true, capabilities: {
        'admin-settings': true, 'admin-aliases': true, 'receiver-health': true,
        'scanner': true, 'call-audio': true, 'live': true, 'user-settings': true,
        'recordings': true, 'credits': true } });
    if (pathname === '/api/v1/me/preferences') return route.fulfill({ json: { revision: 1, preferences } });
    if (['/api/v1/admin/scan-lists', '/api/v1/scan-lists'].includes(pathname)) {
      return data({ revision: 1, scan_lists: [] });
    }
    if (pathname === '/api/v1/admin/receiver-settings') {
      if (request.method() === 'PUT') {
        state.writes.push(request.postDataJSON());
        const status = await new Promise((resolve) => state.pending.push(resolve));
        if (status !== 200) return route.fulfill({ status,
          json: { error: { message: 'The receiver is busy. Try again.' } } });
        state.value = request.postDataJSON().traffic_grant_age_out_milliseconds;
        state.revision += 1;
      } else await new Promise((resolve) => { state.initial = resolve; });
      return route.fulfill({ json: { revision: state.revision,
        settings: { traffic_grant_age_out_milliseconds: state.value } } });
    }
    if (pathname === '/api/v1/admin/spectrum-snap-presets') return route.fulfill({ json: {
      revision: 9, country_code: 'US', country_label: 'United States',
      countries: [{ code: 'US', label: 'United States' }], scopes: [] } });
    if (pathname === '/api/v1/status') return data({ database: { logger: [] }, stats_logging: {
      summary_configured: true, detailed_history_configured: true,
      summary_active: true, detailed_history_active: true, state: 'RUNNING' } });
    if (pathname === '/api/v1/listen/map') return data({ generated_at_ms: Date.now(),
      dropped_observations: 0, evicted_entities: 0, entities: [{ id: 'engine-4',
        label: 'Regional Emergency Communications North County Engine 4 Portable',
        identifier: '1204185', alias_list: 'County', system: 'County Public Safety',
        heading: 92, speed_kph: 41, positions: [
          { latitude: 41.502, longitude: -81.685, timestamp_ms: Date.now() }
        ] }] });
    return route.fulfill({ status: 404, json: { error: { message: 'Unavailable in this fixture' } } });
  });
  return state;
}

for (const theme of ['light', 'dark']) for (const [device, viewport] of [
  ['desktop', { width: 1280, height: 900 }], ['phone', { width: 390, height: 844 }]
]) {
  test(`filled roles preserve hierarchy and meaningful boundaries ${theme} ${device}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=entity-details`);
    const details = page.locator('.visual-entity-details-example');
    await expectFilledWithoutFrame(details.locator('.ui-metric, .ui-facts > .ui-fact'));
    await expect(details.getByRole('link', {
      name: 'County P25 and Regional Emergency Services Shared Alias List', exact: true
    })).toHaveAttribute('href', '?view=aliases&list=7');
    await expectNoHorizontalOverflow(page);

    await page.goto(`/design-system.html?theme=${theme}&view=p25`);
    const overrides = page.locator('.visual-p25-example');
    await expectFilledWithoutFrame(overrides.locator('.ui-surface-inset'));
    await expectFlatFacts(overrides.locator('.ui-record-facts > .ui-fact'));
    await expectNoHorizontalOverflow(page);

    await page.goto(`/design-system.html?theme=${theme}&view=gallery`);
    const gallery = page.locator('.design-system-gallery');
    await expectFilledWithoutFrame(gallery.locator('[data-visual-compact-selection]'));
    const toolbar = gallery.locator('.visual-data-toolbar');
    // A table divider may remain; it must not become another enclosing box.
    for (const edge of ['top', 'right', 'left']) await expect(toolbar).toHaveCSS(`border-${edge}-width`, '0px');
    const notices = gallery.locator('.ui-notice-warning, .ui-notice-danger');
    expect(await notices.count()).toBeGreaterThan(0);
    for (const notice of await notices.all()) {
      await expect(notice).toHaveCSS('border-top-width', '1px');
      await expect(notice).not.toHaveCSS('border-top-color', 'rgba(0, 0, 0, 0)');
    }
    await notices.first().evaluate((notice) => {
      const neutral = document.createElement('div');
      neutral.className = 'ui-notice';
      neutral.dataset.filledNeutralNotice = '';
      neutral.textContent = 'Saved configuration is current.';
      notice.after(neutral);
    });
    const neutralNotice = gallery.locator('[data-filled-neutral-notice]');
    await expect(neutralNotice).toHaveCSS('border-top-width', '1px');
    await expect(neutralNotice).toHaveCSS('border-top-color', 'rgba(0, 0, 0, 0)');
    await expect(neutralNotice).not.toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
    const neutralPill = gallery.locator('[class="ui-pill"]').first();
    await expect(neutralPill).toHaveCSS('border-top-width', '1px');
    await expect(neutralPill).toHaveCSS('border-top-color', 'rgba(0, 0, 0, 0)');
    await expectFilledWithoutFrame(gallery.locator('.ui-feedback-loading, .ui-empty-state'));
    const choices = gallery.locator('label.ui-choice-card');
    const selected = choices.filter({ has: page.locator('input:checked') });
    const unselected = choices.filter({ has: page.locator('input:not(:checked)') });
    await expect(selected).toHaveCSS('border-top-width', '1px');
    expect(await selected.evaluate((element) => getComputedStyle(element).backgroundColor))
      .not.toBe(await unselected.evaluate((element) => getComputedStyle(element).backgroundColor));
    await expect(gallery.getByRole('button', { name: 'Loading settings', exact: true })).toBeDisabled();
    await expect(gallery.getByRole('button', { name: 'Loading settings', exact: true })).toHaveAttribute('aria-busy', 'true');
    await expect(gallery.getByRole('heading', { name: 'No matching channels', exact: true })).toBeVisible();
    await expect(gallery.getByText('The requested information could not be loaded.', { exact: true })).toBeVisible();
    await expectNoHorizontalOverflow(page);

    await page.goto(`/design-system.html?theme=${theme}&view=settings`);
    const settings = page.locator('.visual-settings-example');
    for (const panel of await settings.locator('.settings-card').all()) {
      await expect(panel).toHaveCSS('border-width', '0px');
      await expect(panel.locator('.settings-card-header')).toHaveCSS('border-bottom-width', '0px');
    }
    const nested = settings.locator('.settings-card-body .admin-toggle-control');
    await expect(nested).toHaveCSS('border-width', '0px');
    await expect(nested).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
    await expectFieldBoundaryAndFocus(settings.getByRole('textbox', { name: 'Receiver name' }));
    await expectFieldBoundaryAndFocus(settings.getByRole('combobox', { name: 'Default page' }));
    await expectNoHorizontalOverflow(page);

    if (device === 'phone') {
      await page.goto(`/design-system.html?theme=${theme}&view=mobile-table`);
      const table = page.locator('.visual-mobile-table-example .ui-mobile-cards');
      // Exercise the shared optional selection column with the gallery's real
      // long-form channel records, without replacing their cells or labels.
      await table.evaluate((element) => {
        const checkbox = (label) => {
          const input = document.createElement('input');
          input.type = 'checkbox';
          input.className = 'ui-selection-check';
          input.setAttribute('aria-label', label);
          return input;
        };
        const selectAll = document.createElement('th');
        selectAll.append(checkbox('Select all visible channels'));
        element.tHead.rows[0].prepend(selectAll);
        for (const row of element.tBodies[0].rows) {
          const select = document.createElement('td');
          select.dataset.label = 'Select';
          select.append(checkbox(`Select ${row.cells[0].textContent.trim()}`));
          row.prepend(select);
        }
      });
      await expectFilledWithoutFrame(table.locator('tbody > tr'));
      const selectAll = table.getByRole('checkbox', { name: 'Select all visible channels', exact: true });
      await expect(selectAll.locator('..')).toBeVisible();
      await expect(selectAll.locator('..')).toHaveCSS('border-top-width', '1px');
      for (const input of await table.getByRole('checkbox').all()) {
        await expect(input).toBeVisible();
        await expect(input).toHaveCSS('border-top-width', '1px');
        await input.focus();
        await expect(input).toBeFocused();
        await expect(input).toHaveCSS('outline-width', '2px');
        await input.press('Space');
        await expect(input).toBeChecked();
      }
      await expect(table.locator('tbody > tr').first().locator('td').nth(1))
        .toHaveCSS('border-bottom-width', '1px');
      await expect(table.locator('tbody > tr').first().locator('td').last())
        .toHaveCSS('border-bottom-width', '0px');
      await expectNoHorizontalOverflow(page);
    }
  });

  test(`filled settings retain loading busy failure retry and flat Map facts ${theme} ${device}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const state = await installSettingsAndMap(page, theme);
    await page.goto('/app.html?view=admin&tab=display');
    const form = page.locator('.receiver-settings-form');
    const timing = form.getByRole('spinbutton', { name: /^Mark a traffic row idle/ });
    const save = form.getByRole('button', { name: 'Save Live timing', exact: true, includeHidden: true });
    await expect(form.getByRole('status')).toHaveText('Loading Live timing…');
    await expect(timing).toBeDisabled();
    await expect.poll(() => Boolean(state.initial)).toBe(true);
    state.initial();
    await expect(timing).toHaveValue('2000');
    const panel = form.locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " ui-settings-panel ")][1]');
    await expect(panel).toHaveCSS('border-width', '0px');
    await expect(panel.locator(':scope > .ui-section-title')).toHaveCSS('border-bottom-width', '0px');
    await expectFieldBoundaryAndFocus(timing);
    await timing.fill('3750');
    await expect(form.getByRole('status')).toHaveText('Unsaved changes');
    await save.click();
    await expect.poll(() => state.pending.length).toBe(1);
    await expect(form).toHaveAttribute('aria-busy', 'true');
    await expect(timing).toBeDisabled();
    await expect(save).toBeDisabled();
    state.pending.shift()(503);
    await expect(form.getByRole('alert')).toHaveText('The receiver is busy. Try again.');
    await expect(timing).toHaveValue('3750');
    await expect(save).toBeEnabled();
    await expectFieldBoundaryAndFocus(timing);
    await save.click();
    await expect.poll(() => state.pending.length).toBe(1);
    state.pending.shift()(200);
    await expect(form).not.toHaveClass(/is-dirty/);
    await expect(save).toBeHidden();
    expect(state.writes).toEqual([
      { traffic_grant_age_out_milliseconds: 3750 }, { traffic_grant_age_out_milliseconds: 3750 }
    ]);
    await expectNoHorizontalOverflow(page);

    await page.goto('/app.html?view=map');
    await expect(page.locator('.listen-map-details')).toContainText(
      'Regional Emergency Communications North County Engine 4 Portable');
    await expectFlatFacts(page.locator('.listen-map-facts > .ui-fact'), { padding: '5px 0px', radius: null });
    await expectNoHorizontalOverflow(page);
  });
}
