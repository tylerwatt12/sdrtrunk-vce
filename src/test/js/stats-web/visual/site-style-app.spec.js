'use strict';

const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { presentations } = require('./fixtures/site-style-cases.cjs');
const { installSiteStyleApplication, systemKey, channelId, groupKey, radioKey,
  channelName, systemName } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const scope = `radio_system_key=${encodeURIComponent(systemKey)}`;
const routes = [
  ['dashboard', 'view=dashboard', '.signal-current-tile', channelName],
  ['live', `view=live&channel=${channelId}`, '.channels-live-table', channelName],
  ['recordings', 'view=recordings', '.recordings-library', 'Fire Dispatch'],
  ['map', 'view=map', '.listen-map-details', 'Engine 4'],
  ['network-visualizer', 'view=network-visualizer', '.network-visualizer-label', systemName],
  ['scanner', 'view=scanner', '.scanner-page', 'County Fire and Emergency Medical Services'],
  ['tuner-spectrum', 'view=tuner-spectrum', '.spectrum-browse-center', '0851.01250MHz'],
  ['radio-system', `view=radio-system&${scope}`, '.system-info-column', systemName],
  ['group-identity', `view=group-identity&${scope}&identity_key=${groupKey}`, '.entity-info-column', 'Fire Dispatch'],
  ['radio', `view=radio&${scope}&identity_key=${radioKey}`, '.entity-info-column', 'Engine 4'],
  ['channel-setup', 'view=channel-setup', '.channel-admin-catalog', channelName],
  ['channel', `view=channel&configuration_id=${channelId}`, '.ui-page-header', channelName],
  ['aliases', 'view=aliases&list=1&aliasTab=configure', '.alias-editor-table-section', 'Fire Dispatch'],
  ['scan-lists', 'view=scan-lists', '.scan-list-detail-host', 'County Fire and Emergency Medical Services'],
  ['radioreference', 'view=radioreference', '.radioreference-login-gate', 'Connect your account'],
  ['streaming', 'view=streaming', '.streaming-page', 'County Calls'],
  ['tuners', 'view=tuners', '.tuners-workspace', 'Airspy R2'],
  ['admin', 'view=admin', '.receiver-health-overview', 'Receiver status'],
  ['settings', 'view=settings', '.user-settings-summary', 'Theme'],
  ['credits', 'view=credits', '.credits-copy', 'SDRTrunk']
];
const administration = [
  ['health', '.receiver-health-overview', 'Receiver status'],
  ['application-log', '.application-log-workspace', 'County receiver started'],
  ['call-matching', '.call-matching-workspace', 'County Fire Dispatch'],
  ['support', '.support-report-form', 'Prepare support report'],
  ['recordings', '.recordings-admin', 'Managed'],
  ['audio-quality', '.operational-preferences', 'MP3 encoding'],
  ['transcription', '.recordings-admin', 'Transcription'],
  ['activity', '.operational-preferences', 'Saved activity'],
  ['retained-statistics', '.retained-statistics-page', 'Saved data'],
  ['remote-links', '.remote-links-page', 'Regional receiver'],
  ['protocol-p25', '.p25-overrides-workspace', '851'],
  ['display', '.admin-display-settings', 'United States'],
  ['users', '.admin-account-card', 'field.operator'],
  ['access', '.admin-access-gate', 'Page access']
].map(([tab, selector, content]) => [`admin-${tab}`, `view=admin&tab=${tab}`, selector, content]);
const cases = [...routes, ...administration];
const operationalFields = {
  streaming: ['patch_group_streaming_option'],
  'admin-recordings': ['audio_record_format'],
  'admin-audio-quality': ['mp3_setting', 'mp3_input_audio_format', 'mp3_normalize_audio'],
  'admin-activity': ['stats_logging_enabled', 'stats_detailed_history_enabled', 'stats_logging_retention_days']
};

async function expectSuccessfulPage(page, fixture, name, selector, content) {
  const example = page.locator(selector);
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  await expect(example.first()).toBeVisible();
  if (content) await expect(page.locator('#content')).toContainText(content);
  for (const field of operationalFields[name] || []) {
    // These forms are built only after the raw settings document has decoded successfully.
    const input = page.locator(`.operational-preference-form[data-preference="${field}"] input, ` +
      `.operational-preference-form[data-preference="${field}"] select`).first();
    await expect(input).toBeVisible();
    await expect(input).toBeEnabled();
  }
  if (operationalFields[name]) await expect(page.locator('.operational-preferences > .admin-form-message')).toHaveText('');
  if (name === 'admin-display') {
    const liveTiming = page.locator('.receiver-settings-form input[type="number"]');
    await expect(liveTiming).toBeEnabled();
    await expect(liveTiming).toHaveValue('2000');
    await expect(page.locator('.receiver-settings-form .admin-form-message')).toHaveText('');
  }
  if (name === 'admin-application-log') {
    await expect(page.locator('.application-log-entry')).toHaveCount(3);
    await expect(page.locator('.application-log-state')).toHaveText('Live');
    await expect(page.getByRole('button', { name: 'Copy shown', exact: true })).toBeEnabled();
  }
  const errors = page.locator('#content .ui-feedback-error:visible, #content > .error:visible, ' +
    '#content [role="alert"]:visible').filter({ hasText: /\S/ });
  await expect(errors).toHaveCount(0);
  const formErrors = page.locator('#content .admin-form-message:visible, #content [role="status"]:visible')
    .filter({ hasText: /could not|unavailable|failed|\berror\b|reload the page and try again/i });
  await expect(formErrors, 'Form-level load failures must not count as successful page coverage').toHaveCount(0);
  expect(fixture.unexpected, 'Every API response is explicit; unknown requests may not silently return empty data').toEqual([]);
  expect(fixture.pageErrors, 'Production route must mount without a JavaScript error').toEqual([]);
}

async function expectSharedTypography(page) {
  const inconsistent = await page.evaluate(() => {
    const family = getComputedStyle(document.body).fontFamily;
    return [...document.querySelectorAll('#content .ui-button, #content .ui-input, #content .ui-select, #content .ui-segmented-option')]
      .filter(element => element.getClientRects().length && getComputedStyle(element).fontFamily !== family)
      .map(element => ({ control: element.getAttribute('aria-label') || element.textContent?.trim().slice(0, 80),
        className: element.className, font: getComputedStyle(element).fontFamily }));
  });
  expect(inconsistent).toEqual([]);
}

test('actual route style matrix includes every production route and administration section', async ({ page }) => {
  const registry = await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/routes.js')).href);
  expect(routes.map(([id]) => id).sort()).toEqual(registry.definitions.map(({ id }) => id).sort());
  await installSiteStyleApplication(page, 'light');
  await page.goto('/app.html?view=admin');
  await expect(page.locator('.admin-settings-tree')).toBeVisible();
  const availableLeaves = await page.locator('.admin-settings-tree a[href]').evaluateAll(links =>
    links.map(link => new URL(link.href).searchParams.get('tab')).filter(Boolean));
  expect(administration.map(([, query]) => new URLSearchParams(query).get('tab')).sort())
    .toEqual(availableLeaves.sort());
});

for (const [name, query, selector, content] of cases) {
  for (const presentation of presentations) {
    test(`actual ${name} ${presentation.name}`, async ({ page }) => {
      await page.setViewportSize(presentation.viewport);
      const fixture = await installSiteStyleApplication(page, presentation.theme);
      await page.goto(`/app.html?${query}`);
      if (presentation.theme === 'dark') await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
      else await expect(page.locator('html')).not.toHaveAttribute('data-theme', 'dark');
      await expectSuccessfulPage(page, fixture, name, selector, content);
      await expectSharedTypography(page);
      await expectNoHorizontalOverflow(page);
      if (process.env.STYLE_AUDIT_ASSERTIONS_ONLY !== '1') {
        await expect(page.locator('#content')).toHaveScreenshot(`${name}-${presentation.name}.png`);
      }
    });
  }
}

for (const [name, viewport] of [
  ['small-phone', { width: 320, height: 568 }],
  ['tablet', { width: 768, height: 1024 }],
  ['landscape', { width: 844, height: 390 }]
]) {
  for (const id of ['dashboard', 'channel-setup', 'aliases', 'settings', 'admin-users', 'admin-application-log']) {
    test(`actual responsive ${id} ${name}`, async ({ page }) => {
      const [, query, selector, content] = cases.find(([caseId]) => caseId === id);
      await page.setViewportSize(viewport);
      const fixture = await installSiteStyleApplication(page, 'dark');
      await page.goto(`/app.html?${query}`);
      await expectSuccessfulPage(page, fixture, id, selector, content);
      await expectNoHorizontalOverflow(page);
      await expectSharedTypography(page);
      const control = page.locator('#content .ui-button:not(:disabled):visible, #content .ui-input:not(:disabled):visible').first();
      if (await control.count()) {
        await control.focus();
        await expect(control).toBeFocused();
        expect(await page.evaluate(() => document.documentElement.scrollLeft)).toBe(0);
      }
    });
  }
}

module.exports = { routes, administration };

async function installRadioReferenceRegionApplication(page, theme, options = {}) {
  const fixture = await installSiteStyleApplication(page, theme);
  let configuration = { account: { state: options.signedOut ? 'SIGNED_OUT' : 'VALID_PREMIUM',
    user_name: options.signedOut ? '' : 'fixture.operator' }, credentials_stored: !options.signedOut,
  country_id: options.countryId ?? null, state_id: options.stateId ?? null };
  const requests = [];
  await page.route('**/api/v1/admin/radioreference**', async route => {
    const request = route.request(), url = new URL(request.url()), endpoint = url.pathname;
    requests.push({ endpoint, method: request.method(), body: request.postDataJSON() });
    const respond = data => route.fulfill({ json: { data } });
    if (endpoint === '/api/v1/admin/radioreference') return respond(configuration);
    if (endpoint.endsWith('/session') && request.method() === 'PUT') {
      configuration = { ...configuration, account: { state: 'VALID_PREMIUM', user_name: 'fixture.operator' },
        credentials_stored: true };
      return respond(configuration);
    }
    if (endpoint.endsWith('/countries')) return respond({ items: [
      { id: 1, name: 'United States', abbreviation: 'US' },
      { id: 2, name: 'Canada', abbreviation: 'CA' }
    ] });
    if (endpoint.endsWith('/states')) return respond({ items: url.searchParams.get('country_id') === '2' ?
      [{ id: 57, name: 'Ontario' }] : [{ id: 39, name: 'Ohio' }, { id: 42, name: 'Pennsylvania' }] });
    if (endpoint.endsWith('/location') && request.method() === 'PUT') {
      if (options.save) return options.save(route, request.postDataJSON());
      const location = request.postDataJSON();
      configuration = { ...configuration, country_id: location.country_id, state_id: location.state_id };
      return respond(configuration);
    }
    if (['/counties', '/bookmarks', '/system-preferences', '/browse/catalog'].some(suffix => endpoint.endsWith(suffix))) {
      return respond({ items: [], total_count: 0 });
    }
    fixture.unexpected.push(`${request.method()} ${endpoint}`);
    return route.fulfill({ status: 501, json: { error: { message: `Region fixture has no response for ${endpoint}` } } });
  });
  return { fixture, requests };
}

for (const [theme, viewport, signedOut] of [
  ['light', { width: 1280, height: 900 }, true],
  ['dark', { width: 390, height: 844 }, false]
]) {
  test(`RadioReference lookup region is offered after ${signedOut ? 'connection' : 'returning sign-in'} ${theme}`,
    async ({ page }, testInfo) => {
      await page.setViewportSize(viewport);
      const { fixture, requests } = await installRadioReferenceRegionApplication(page, theme, { signedOut });
      await page.goto('/app.html?view=radioreference');
      if (signedOut) {
        await expect(page.locator('.radioreference-region-form')).toHaveCount(0);
        await page.getByLabel('Username', { exact: true }).fill('fixture.operator');
        await page.getByLabel('Password', { exact: true }).fill('fixture-password');
        await page.getByRole('button', { name: 'Connect RadioReference', exact: true }).click();
      }
      const region = page.locator('.radioreference-connected-page > .radioreference-region-form');
      await expect(region).toBeVisible();
      const state = region.getByLabel('State or region', { exact: true });
      await expect(state).toBeEnabled();
      await expect(state).toHaveValue('');
      await expect(region.locator('.ui-select-frame')).toHaveCount(2);
      const save = region.getByRole('button', { name: 'Save lookup region', exact: true });
      await expect(save).toBeDisabled();
      await state.selectOption('42');
      await expect(save).toBeEnabled();
      await expectSharedTypography(page);
      await expectNoHorizontalOverflow(page);
      await page.locator('#content').screenshot({ path: testInfo.outputPath('region-setup.png') });
      await save.click();
      await expect(region).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'RadioReference settings', exact: true })).toBeFocused();
      await expect(page.locator('.radioreference-connected-page')).toContainText('Lookup region saved.');
      expect(requests.filter(({ endpoint }) => endpoint.endsWith('/location'))).toEqual([
        { endpoint: '/api/v1/admin/radioreference/location', method: 'PUT', body: { country_id: 1, state_id: 42 } }
      ]);
      await page.getByRole('button', { name: 'RadioReference settings', exact: true }).click();
      const modal = page.getByRole('dialog', { name: 'RadioReference settings', exact: true });
      await expect(modal.getByLabel('State or region', { exact: true })).toHaveValue('42');
      await expect(modal.locator('.ui-modal-footer')).toBeVisible();
      await expect(modal.getByRole('button', { name: 'Save lookup region', exact: true })).toBeDisabled();
      await modal.getByRole('button', { name: 'Cancel', exact: true }).click();
      await expect(modal).toHaveCount(0);
      expect(fixture.unexpected).toEqual([]);
      expect(fixture.pageErrors).toEqual([]);
    });
}

test('RadioReference lookup region failed save preserves draft and blocks duplicate save or dismissal',
  async ({ page }) => {
    let releaseSave;
    const saveGate = new Promise(resolve => { releaseSave = resolve; });
    const { fixture, requests } = await installRadioReferenceRegionApplication(page, 'dark', {
      save: async route => {
        await saveGate;
        return route.fulfill({ status: 503, json: { error: {
          message: 'Lookup region could not be saved. Try again.'
        } } });
      }
    });
    await page.goto('/app.html?view=radioreference');
    const inlineRegion = page.locator('.radioreference-connected-page > .radioreference-region-form');
    await expect(inlineRegion.getByLabel('State or region', { exact: true })).toBeEnabled();
    await inlineRegion.getByLabel('State or region', { exact: true }).selectOption('42');
    await page.getByRole('button', { name: 'RadioReference settings', exact: true }).click();
    const modal = page.getByRole('dialog', { name: 'RadioReference settings', exact: true });
    const state = modal.getByLabel('State or region', { exact: true });
    await expect(state).toHaveValue('42');
    await modal.getByRole('button', { name: 'Save lookup region', exact: true }).click();
    await expect(modal).toHaveAttribute('aria-busy', 'true');
    await expect(state).toBeDisabled();
    await modal.locator('.radioreference-account-details > summary').click();
    await expect(modal.getByRole('button', { name: 'Update account', exact: true })).toBeDisabled();
    await expect(modal.getByRole('button', { name: 'Log out and clear saved credentials', exact: true })).toBeDisabled();
    await modal.locator('form.radioreference-account-form').evaluate(form =>
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
    await modal.locator('form.radioreference-region-form').evaluate(form =>
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
    await page.keyboard.press('Escape');
    await expect(modal).toBeVisible();
    expect(requests.filter(({ endpoint }) => endpoint.endsWith('/location'))).toHaveLength(1);
    expect(requests.filter(({ endpoint }) => endpoint.endsWith('/session'))).toHaveLength(0);
    releaseSave();
    await expect(modal.getByRole('alert')).toHaveText('Lookup region could not be saved. Try again.');
    await expect(state).toBeEnabled();
    await expect(state).toHaveValue('42');
    await expect(modal.getByRole('button', { name: 'Save lookup region', exact: true })).toBeEnabled();
    await page.keyboard.press('Escape');
    const confirmation = page.getByRole('alertdialog', { name: 'Discard unsaved changes', exact: true });
    await expect(confirmation).toBeVisible();
    await confirmation.getByRole('button', { name: 'Keep editing', exact: true }).click();
    await expect(state).toHaveValue('42');
    await expect(modal).toBeVisible();
    await modal.getByLabel('Username', { exact: true }).fill('fixture.changed');
    await modal.getByLabel('Password', { exact: true }).fill('fixture-new-password');
    const remember = modal.getByRole('checkbox', { name: 'Remember credentials on this receiver', exact: true });
    await remember.focus();
    await remember.press('Space');
    await expect(remember).not.toBeChecked();
    await page.keyboard.press('Escape');
    await confirmation.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(modal).toHaveCount(0);
    await expect(inlineRegion.getByLabel('State or region', { exact: true })).toBeEnabled();
    await expect(inlineRegion.getByLabel('State or region', { exact: true })).toHaveValue('');
    await page.getByRole('button', { name: 'RadioReference settings', exact: true }).click();
    await expect(modal.getByLabel('State or region', { exact: true })).toHaveValue('');
    await modal.locator('.radioreference-account-details > summary').click();
    await expect(modal.getByLabel('Username', { exact: true })).toHaveValue('fixture.operator');
    await expect(modal.getByLabel('Password', { exact: true })).toHaveValue('');
    await expect(modal.getByRole('checkbox', { name: 'Remember credentials on this receiver', exact: true })).toBeChecked();
    expect(fixture.unexpected).toEqual([]);
    expect(fixture.pageErrors).toEqual([]);
  });

test('RadioReference lookup region saved state stays in settings and invalid state is offered again', async ({ page }) => {
  const { fixture } = await installRadioReferenceRegionApplication(page, 'light', { countryId: 1, stateId: 39 });
  await page.goto('/app.html?view=radioreference');
  await expect(page.locator('.radioreference-connected-page')).toBeVisible();
  await expect(page.locator('.radioreference-connected-page > .radioreference-region-form')).toHaveCount(0);
  await page.getByRole('button', { name: 'RadioReference settings', exact: true }).click();
  const modal = page.getByRole('dialog', { name: 'RadioReference settings', exact: true });
  await expect(modal.getByLabel('State or region', { exact: true })).toHaveValue('39');
  await modal.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(modal).toHaveCount(0);
  expect(fixture.unexpected).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
  await installRadioReferenceRegionApplication(page, 'light', { countryId: 1, stateId: 999 });
  await page.reload();
  const inlineRegion = page.locator('.radioreference-connected-page > .radioreference-region-form');
  await expect(inlineRegion).toBeVisible();
  await expect(inlineRegion.getByLabel('State or region', { exact: true })).toHaveValue('');
  await expect(inlineRegion.getByRole('button', { name: 'Save lookup region', exact: true })).toBeDisabled();
});
