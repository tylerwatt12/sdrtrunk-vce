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
