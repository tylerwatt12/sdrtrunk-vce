'use strict';

const { test, expect } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { presentations } = require('./fixtures/site-style-cases.cjs');
const { installSiteStyleApplication, systemKey, channelId, groupKey, radioKey,
  channelName, systemName } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const scope = `radio_system_key=${encodeURIComponent(systemKey)}`;
const routes = [
  ['dashboard', 'view=dashboard', '.radio-directory-site-row', channelName],
  ['live', `view=live&channel=${channelId}`, '.channels-live-table', channelName],
  ['topics', 'view=topics', '.alerts-public-topics', 'Fire activity'],
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
  ['alerts', 'view=alerts', '.alerts-workspace', 'Fire call activity'],
  ['streaming', 'view=streaming', '.streaming-page', 'County Calls'],
  ['tuners', 'view=tuners', '.tuners-workspace', 'Airspy R2'],
  ['admin', 'view=admin', '.receiver-health-overview', 'Receiver status'],
  ['settings', 'view=settings', '.user-settings-workspace', 'Appearance'],
  ['credits', 'view=credits', '.credits-copy', 'SDRTrunk']
];
const administration = [
  ['computer', '.receiver-computer-page', 'Synthetic workstation'],
  ['health', '.receiver-health-overview', 'Receiver status'],
  ['application-log', '.application-log-workspace', 'County receiver started'],
  ['web-diagnostic-log', '.web-diagnostic-log-workspace', 'Web server log'],
  ['call-matching', '.call-matching-workspace', 'County Fire Dispatch'],
  ['tuner-allocation', '.tuner-allocation-workspace', channelName],
  ['support', '.support-report-form', 'Prepare support report'],
  ['recordings', '.recordings-admin', 'Managed'],
  ['audio-quality', '.operational-preferences', 'MP3 encoding'],
  ['transcription', '.recordings-admin', 'Transcription'],
  ['push-setup', '.alerts-workspace', 'Awaiting approval'],
  ['activity', '.operational-preferences', 'Saved activity'],
  ['retained-statistics', '.retained-statistics-page', 'Saved data'],
  ['remote-links', '.remote-links-page', 'Regional receiver'],
  ['remote-support', '[data-remote-support]', 'Allow remote support'],
  ['protocol-p25', '.p25-overrides-workspace', '851'],
  ['display', '.admin-display-settings', 'United States'],
  ['users', '.admin-account-card', 'field.operator'],
  ['access', '.admin-access-gate', 'Page access']
].map(([tab, selector, content]) => [`admin-${tab}`, `view=admin&tab=${tab}`, selector, content]);
// Views within a production route belong here so the route registry stays a
// strict one-to-one inventory while each major workspace gets a full-page audit.
const openAliasChannelScope = async (page) => {
  await page.locator('details.alias-coverage-scope > summary').click();
  await expect(page.locator('.alias-coverage-channel-grid')).toBeVisible();
};
const additionalCases = [
  ['dashboard-calls', 'view=dashboard&tab=calls', '.dashboard-call-activity', 'Fire Dispatch'],
  ['dashboard-activity', 'view=dashboard&tab=activity', '.dashboard-activity-radio-result', 'Engine 4',
    async (page) => {
      await page.locator('.dashboard-activity-legend-button[data-action="GRANT"]').click();
      await expect(page.locator('.dashboard-activity-radio-host')).toHaveAttribute('aria-busy', 'false');
      await expect(page.locator('.dashboard-activity-radio-result tbody tr')).toHaveCount(1);
      await expect(page.locator('.dashboard-activity-radio-result')).toContainText('Source radios 1–1 of 1');
    }],
  ['dashboard-inventory', 'view=dashboard&tab=inventory&alias_list_id=1',
    '.alias-coverage-table-section', 'Fire Dispatch', openAliasChannelScope],
  ['dashboard-inventory-unassigned', 'view=dashboard&tab=inventory&alias_list_id=1&identity_tab=unassigned',
    '.alias-coverage-table-section', '1301', openAliasChannelScope],
  ['admin-health-diagnostics', 'view=admin&tab=health', '.receiver-health-diagnostics', 'Receiver diagnostics',
    async (page) => {
      await page.getByRole('tab', { name: 'Diagnostics', exact: true }).click();
      const toggles = page.locator('.receiver-health-diagnostics .receiver-health-section-toggle');
      await expect(toggles).toHaveCount(3);
      for (const toggle of await toggles.all()) {
        if (await toggle.getAttribute('aria-expanded') === 'false') await toggle.click();
      }
      await expect(page.locator('.receiver-health-diagnostics')).not.toContainText('Synthetic workstation');
    }]
];
const cases = [...routes, ...administration, ...additionalCases];
const operationalFields = {
  streaming: ['patch_group_streaming_option'],
  'admin-recordings': ['audio_record_format'],
  'admin-audio-quality': ['mp3_setting', 'mp3_input_audio_format', 'mp3_normalize_audio'],
  'admin-activity': ['stats_logging_enabled', 'stats_detailed_history_enabled', 'stats_logging_retention_days']
};

// Keep this full-page style sample compact. The shared fixture also serves the
// dedicated Activity pagination tests, which deliberately provide 101 radios.
async function installCompactActivityStyleSample(page, fixture) {
  const now = Date.parse('2026-10-01T12:00:00Z');
  await page.route('**/api/v1/activity/{actions,radios}*', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    fixture.requests.push(pathname);
    expect(route.request().method()).toBe('GET');
    if (pathname.endsWith('/actions')) return route.fulfill({ json: { data: { rows: [
      { action: 'GRANT', observation_count: 12 }, { action: 'REGISTER', observation_count: 4 }
    ] } } });
    await route.fulfill({ json: { data: { rows: [{
      radio_system_key: systemKey, system_name: systemName, channel_kind: 'TRUNKED',
      native_id: 30914, radio_id: 30914, alias_name: 'Engine 4', observation_count: 10,
      last_seen_ms: now, radio_system_entity_ref: { kind: 'radio_system', key: systemKey },
      radio_entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: radioKey }
    }], limit: 100, offset: 0, total_count: 1, has_more: false, next_offset: null,
    action_observation_count: 12, retained_observation_count: 12,
    identified_observation_count: 10, unknown_source_observation_count: 2 } } });
  });
}

async function installNamedP25StyleSample(page, fixture) {
  await page.route('**/api/v1/admin/p25-bandplan-overrides', async (route) => {
    fixture.requests.push(new URL(route.request().url()).pathname);
    expect(route.request().method()).toBe('GET');
    const system = { protocol: 'P25', wacn: 1, system: 1, system_id: 1,
      system_name: systemName, radio_system_key: systemKey,
      radio_system_entity_ref: { kind: 'radio_system', key: systemKey },
      system_identity: { key: systemKey, name: systemName, protocol: 'P25', wacn: 1, system_id: 1 } };
    await route.fulfill({ json: { known_systems: [system], profiles: [{ ...system, rfss: 1, site: 2,
      bands: [{ identifier: 0, type: 'FDMA', base_frequency: 851006250, bandwidth: 12500,
        channel_spacing: 12500, transmit_offset: -45000000 }] }] } });
  });
}

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
  if (['admin', 'admin-health'].includes(name)) {
    const resources = page.locator('.receiver-health-resources');
    await expect(resources).toBeVisible();
    await expect(resources.locator('.receiver-health-resource-bar')).toHaveCount(3);
    for (const [label, value] of [['Processor', '12.5 %'], ['Memory', '25 %'], ['Free storage', '75 %']]) {
      await expect(resources.getByRole('progressbar', { name: label, exact: true }))
        .toHaveAttribute('aria-valuetext', value);
    }
    await expect(resources.locator('.receiver-health-empty')).toHaveCount(0);
    expect(fixture.requests).toContain('/api/v1/receiver-health');
  }
  if (name === 'settings') {
    const appearance = page.getByRole('form', { name: 'Appearance', exact: true });
    await expect(appearance).toBeVisible();
    const themes = appearance.getByRole('radiogroup', { name: 'Theme', exact: true });
    await expect(themes.getByRole('radio', { name: 'Light', exact: true })).toBeEnabled();
    await expect(themes.getByRole('radio', { name: 'Dark', exact: true })).toBeEnabled();
    await expect(themes.locator('input:checked')).toHaveCount(1);
    await expect(appearance.getByRole('radio', { name: 'Teal', exact: true })).toBeChecked();
    await expect(appearance.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled();
    await expect(appearance.locator('.admin-form-message')).toHaveText('');
    expect(fixture.requests).toContain('/api/v1/me/preferences');
  }
  if (name === 'admin-display') {
    const liveTiming = page.locator('.receiver-settings-form input[type="number"]');
    await expect(liveTiming).toBeEnabled();
    await expect(liveTiming).toHaveValue('2000');
    await expect(page.locator('.receiver-settings-form .admin-form-message')).toHaveText('');
  }
  if (['admin-application-log', 'admin-web-diagnostic-log'].includes(name)) {
    await expect(page.locator('.application-log-entry')).toHaveCount(3);
    await expect(page.locator('.application-log-state')).toHaveText('Live');
    await expect(page.getByRole('button', { name: 'Copy shown', exact: true })).toBeEnabled();
  }
  if (name === 'admin-remote-support') {
    await expect(page.getByRole('button', { name: 'Allow remote support', exact: true })).toBeEnabled();
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

// Exercise the same rendered page under representative custom colors without
// saving a preference or reloading its populated workflow.
async function auditPageContrast(page, name) {
  await expectContrast(page, { reportName: name });
  const hues = (process.env.CONTRAST_AUDIT_HUES ?? '0,120,240').split(',')
    .filter(value => value.trim()).map(Number);
  try {
    for (const hue of hues) {
      await page.evaluate(async (value) => {
        const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
        applyThemeHue(value);
      }, hue);
      await expectContrast(page, { reportName: `${name}-hue-${hue}` });
    }
  } finally {
    if (hues.length) await page.evaluate(async () => {
      const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
      applyThemeHue(null);
    });
  }
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

test('Spectrum frequency lookup opens shared account connection and keeps its selection on cancel', async ({ page }) => {
  const fixture = await installSiteStyleApplication(page);
  const application = readFileSync(path.resolve(__dirname, '../../../../../stats-web/assets/app.js'), 'utf8');
  await page.route('**/assets/app.js*', route => route.fulfill({ contentType: 'text/javascript',
    body: `${application}\nexport { openTunerFrequencyActions };` }));
  await page.goto('/app.html?view=tuner-spectrum');
  await expect(page.getByRole('heading', { name: 'Browse Spectrum', exact: true })).toBeVisible();
  await expect(page.locator('.spectrum-browse-control-rail')).toBeVisible();
  const originalUrl = page.url();
  await page.evaluate(async () => {
    const application = await import(document.querySelector('script[type="module"][src*="/assets/app.js"]').src);
    application.openTunerFrequencyActions({ frequencyHz: 851012500, targetId: 'target-a',
      actionHost: document.querySelector('.spectrum-browse-control-rail') });
  });
  await page.locator('.spectrum-browse-control-rail')
    .getByRole('button', { name: 'Look up on RadioReference', exact: true }).click();
  const lookup = page.getByRole('dialog', { name: 'RadioReference lookup', exact: true });
  const connection = page.getByRole('dialog', { name: 'Connect RadioReference', exact: true });
  await expect(connection).toBeVisible();
  await expect(connection).toContainText('Connect your account to look up the selected frequency.');
  await expect(connection.getByRole('textbox', { name: 'Username', exact: true })).toBeVisible();
  await connection.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(connection).toHaveCount(0);
  await expect(lookup).toContainText('851.012500 MHz');
  await expect(lookup).toContainText('Connect an account to look up this frequency.');
  await expect(lookup.getByRole('button', { name: 'Connect account', exact: true })).toBeVisible();
  await expect(page).toHaveURL(originalUrl);
  expect(fixture.unexpected).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
});

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
      const save = region.getByRole('button', { name: 'Save lookup region', exact: true, includeHidden: true });
      await expect(save).toBeDisabled();
      await expect(save).toBeHidden();
      await state.selectOption('42');
      await expect(save).toBeEnabled();
      await expectSharedTypography(page);
      await expectNoHorizontalOverflow(page);
      await page.locator('#content').screenshot({ path: testInfo.outputPath('region-setup.png') });
      await save.click();
      await expect(region).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'RadioReference settings', exact: true })).toBeFocused();
      await expect(page.locator('.radioreference-connected-page')).not.toContainText('Lookup region saved.');
      expect(requests.filter(({ endpoint }) => endpoint.endsWith('/location'))).toEqual([
        { endpoint: '/api/v1/admin/radioreference/location', method: 'PUT', body: { country_id: 1, state_id: 42 } }
      ]);
      await page.getByRole('button', { name: 'RadioReference settings', exact: true }).click();
      const modal = page.getByRole('dialog', { name: 'RadioReference settings', exact: true });
      await expect(modal.getByLabel('State or region', { exact: true })).toHaveValue('42');
      await expect(modal.locator('.ui-modal-footer')).toBeVisible();
      await expect(modal.getByRole('button', { name: 'Save lookup region', exact: true })).toBeHidden();
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
  await expect(inlineRegion.getByRole('button', { name: 'Save lookup region', exact: true })).toBeHidden();
});
