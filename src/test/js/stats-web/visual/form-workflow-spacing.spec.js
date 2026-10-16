'use strict';

const { expect, test } = require('@playwright/test');
const { installSiteStyleApplication, channelId, channelName } = require('./fixtures/site-style-app.cjs');
const { readFormWorkflowGeometry, expectFormWorkflowGeometry, expectStableFormWorkflowInsets,
  captureFormWorkflowEvidence } = require('./fixtures/form-workflow-geometry.cjs');

// These are real app consumers. The gallery separately exercises the shared states.
async function installApplication(page, theme) {
  await installSiteStyleApplication(page, theme);
  await page.route('**/api/v1/admin/aliases/101', route => route.fulfill({ json: { data: {
    revision: 1, alias: { alias_id: 101, alias_list_id: 1, name: 'Fire Dispatch', group: 'Fire',
      recordable: true, matcher: { type: 'talkgroup', protocol: 'APCO25', value: 1201 } }
  } } }));
  await page.route('**/api/v1/admin/aliases/options', route => route.fulfill({ json: { data: {
    revision: 1, alias_list: { alias_list_id: 1, name: 'County Public Safety', family: 'P25' },
    matchers: [{ type: 'talkgroup', protocol: 'APCO25', label: 'Talkgroup', fields: ['value'], minimum: 1, maximum: 65535 }],
    streams: [{ configuration_id: 'county-calls', name: 'County Calls' }]
  } } }));
  await page.route(`**/api/v1/admin/channels/${channelId}`, route => route.fulfill({ json: { data: {
    revision: 1, processing_state: 'STOPPED', channel: { configuration_id: channelId,
      protocol_id: 'p25-phase1', channel_kind: 'TRUNKED', name: channelName, system: 'County Public Safety',
      site: 'North', alias_list_id: 1, source: { frequencies_hz: [851012500] }, settings: {},
      frequency_map: [], event_logs: [], recorders: [], auxiliary_decoders: [],
      observed: { p25_site_identity: { wacn: 1, system: 1, rfss: 1, site: 2 } } }
  } } }));
  await page.route('**/api/v1/admin/radioreference/channels/enrichment/preview', route => route.fulfill({ json: { data: {
    state: 'matched', preview_id: 'spacing-preview', changes: [
      { field: 'name', before: channelName, after: 'North Simulcast' }
    ]
  } } }));
  await page.route('**/api/v1/admin/tuners/recordings', route => route.fulfill({ json: { data: {
    entries: [{ id: 'fixture-wav', name: 'fixture-851.wav', size_bytes: 1_048_576,
      sample_rate_hz: 2_400_000, suggested_center_frequency_hz: 851_012_500 }], rejected_count: 0, truncated: false
  } } }));
  await page.route('**/api/v1/admin/spectrum-snap-presets', route => route.fulfill({ json: {
    revision: 1, country_code: 'US', country_label: 'United States',
    countries: [{ code: 'US', label: 'United States' }, { code: 'CA', label: 'Canada' }], scopes: []
  } }));
  await page.route('**/api/v1/admin/operational-preferences', route => route.fulfill({ json: {
    revision: 'a'.repeat(64), settings: { patch_group_streaming_option: 'PATCH_GROUP', audio_record_format: 'MP3',
      mp3_setting: 'CBR_16', mp3_input_audio_format: 'SR_16000', mp3_normalize_audio: false,
      stats_logging_enabled: true, stats_detailed_history_enabled: true,
      stats_logging_retention_days: 30 }, options: {
      patch_group_streaming_options: [{ value: 'PATCH_GROUP', label: 'Patch Group' }, { value: 'TALKGROUPS', label: 'Individual Talkgroups' }],
      audio_record_formats: [{ value: 'MP3', label: 'MP3' }, { value: 'WAVE', label: 'WAV' }],
      mp3_settings: [{ value: 'CBR_16', label: '16 kbps' }, { value: 'VBR_5', label: 'Variable' }],
      mp3_input_audio_formats_by_setting: {
        CBR_16: [{ value: 'SR_16000', label: '16 kHz' }, { value: 'SR_8000', label: '8 kHz' }],
        VBR_5: [{ value: 'SR_16000', label: '16 kHz' }, { value: 'SR_44100', label: '44.1 kHz' }] },
      minimum_stats_logging_retention_days: 1, maximum_stats_logging_retention_days: 365 }
  } }));
  await page.route('**/api/v1/admin/radioreference', route => route.fulfill({ json: { data: {
    account: { state: 'VALID_PREMIUM', user_name: 'fixture.operator' }, country_id: 1, state_id: null,
    credentials_stored: true
  } } }));
  await page.route('**/api/v1/admin/radioreference/countries', route => route.fulfill({ json: { data: {
    items: [{ id: 1, name: 'United States' }]
  } } }));
  await page.route('**/api/v1/admin/radioreference/states*', route => route.fulfill({ json: { data: {
    items: [{ id: 42, name: 'Pennsylvania', abbreviation: 'PA' }]
  } } }));
}

async function changeFirstField(form) {
  const fields = form.locator('input:not([type="hidden"]):not(:disabled), select:not(:disabled), textarea:not(:disabled)');
  for (let index = 0; index < await fields.count(); index++) {
    const input = fields.nth(index);
    if (!await input.isVisible()) continue;
    const kind = await input.evaluate(element => element.tagName === 'SELECT' ? 'select' : element.type);
    if (kind === 'checkbox') { await input.focus(); await input.press('Space'); return; }
    if (kind === 'radio') { if (await input.isChecked()) continue; await input.check(); return; }
    if (kind === 'select') {
      const next = await input.evaluate(element => [...element.options].find(option =>
        !option.disabled && option.value !== element.value)?.value);
      if (next !== undefined) { await input.selectOption(next); return; }
      continue;
    }
    if (kind === 'range') { await input.focus(); await input.press('ArrowRight'); return; }
    if (await input.getAttribute('readonly') !== null) continue;
    const value = await input.inputValue();
    await input.fill(kind === 'number' ? String(Number(value || 0) + 1) : `${value} changed`);
    return;
  }
  throw new Error('This real workflow needs an explicit editing action.');
}

async function inspectDirty(form, name, modal = true) {
  await expect(form).toBeVisible();
  const before = await readFormWorkflowGeometry(form);
  await changeFirstField(form);
  await expect(form).toHaveClass(/is-dirty/);
  await expectFormWorkflowGeometry(form, { modal });
  await expectStableFormWorkflowInsets(form, before);
  await captureFormWorkflowEvidence(form, name);
}

async function inspectWithoutFields(form, name, modal) {
  const geometry = await readFormWorkflowGeometry(form);
  expect(geometry.padding.every(value => value >= 8)).toBe(true);
  if (modal) {
    expect(geometry.wrapped).toBe(true);
    expect(geometry.footerInsideBody).toBe(false);
    expect(geometry.footer.left).toBeGreaterThanOrEqual(geometry.dialog.left - 1);
    expect(geometry.footer.right).toBeLessThanOrEqual(geometry.dialog.right + 1);
  }
  await captureFormWorkflowEvidence(form, name);
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  const suffix = `${theme}-${width}`;
  test(`every receiver page workflow owns its field inset ${suffix}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await installApplication(page, theme);
    for (const [context, route, count] of [
      ['operations', 'view=admin&tab=operations', 9], ['display', 'view=admin&tab=display', 2],
      ['audio-quality', 'view=admin&tab=audio-quality', 6], ['activity', 'view=admin&tab=activity', 3],
      ['recordings', 'view=admin&tab=recordings', 3], ['streaming', 'view=streaming', 1],
      ['web-diagnostic-log', 'view=admin&tab=web-diagnostic-log', 1]
    ]) {
      await page.goto(`/app.html?${route}`);
      const forms = page.locator('main form.ui-form-workflow');
      await expect(forms).toHaveCount(count);
      for (let index = 0; index < count; index++) {
        const form = forms.nth(index);
        await expect(form.locator('input:not(:disabled), select:not(:disabled)').first()).toBeEnabled();
        const name = await form.getAttribute('data-preference') || String(index);
        await inspectDirty(form, `${context}-${name}-${suffix}`, false);
      }
    }
    await page.goto('/app.html?view=admin&tab=protocol-p25');
    await page.getByRole('button', { name: /^Delete P25 override/ }).click();
    await page.getByRole('dialog', { name: 'Delete P25 override', exact: true })
      .getByRole('button', { name: 'Delete override', exact: true }).click();
    const p25 = page.locator('form.p25-overrides-workspace');
    await expect(p25).toHaveClass(/is-dirty/);
    await inspectWithoutFields(p25, `p25-workspace-${suffix}`, false);
  });

  test(`personal preference dialogs keep their footer outside dirty fields ${suffix}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await installApplication(page, theme);
    for (const [view, trigger, title] of [
      ['settings', '#appearance-settings', 'Appearance'],
      ['settings', '#source-name-settings', 'Source names'],
      ['settings', '#status-icon-settings', 'Status icon issues'],
      ['live', '.live-presentation-button', 'Live presentation'],
      ['scanner', '#scanner-settings', 'Scanner settings']
    ]) {
      await page.goto(`/app.html?view=${view}`);
      if (view === 'live') await page.getByRole('button', { name: 'Live presentation settings', exact: true }).click();
      else await page.locator(trigger).click();
      const dialog = page.getByRole('dialog', { name: title, exact: true });
      await inspectDirty(dialog.locator('form.ui-form-workflow'), `${view}-${title}-${suffix}`);
    }
  });

  test(`account scan-list and tuner dialogs share padded workflow bodies ${suffix}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await installApplication(page, theme);
    for (const [route, button, title] of [
      ['view=admin&tab=users', 'Create account', 'Create account'],
      ['view=admin&tab=users', 'Change password for field.operator', 'Change password · field.operator'],
      ['view=scan-lists', 'Edit County Fire and Emergency Medical Services details', 'Edit scan list · County Fire and Emergency Medical Services'],
      ['view=tuners', 'Add tuner', 'Add recording tuner']
    ]) {
      await page.goto(`/app.html?${route}`);
      await page.getByRole('button', { name: button, exact: true }).click();
      const dialog = page.getByRole('dialog', { name: title, exact: true });
      await inspectDirty(dialog.locator('form.ui-form-workflow'), `${title}-${suffix}`);
    }
  });

  test(`alias workflow variants keep content and footer geometry ${suffix}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await installApplication(page, theme);
    for (const variant of ['create-list', 'create-alias', 'edit-alias', 'clone-alias', 'defaults']) {
      await page.goto(`/app.html?view=aliases&list=1&aliasTab=configure${['edit-alias', 'clone-alias'].includes(variant) ? '&alias=101' : ''}`);
      if (variant === 'create-list') await page.locator('.alias-list-create:visible, .alias-list-mobile-create:visible').first().click();
      if (variant === 'create-alias') await page.getByRole('button', { name: 'Add Alias', exact: true }).click();
      if (variant === 'clone-alias') await page.getByRole('dialog').last().getByRole('button', { name: 'Clone', exact: true }).click();
      if (variant === 'defaults') await page.getByRole('button', { name: 'Call Handling Defaults', exact: true }).click();
      const dialog = page.getByRole('dialog').last();
      const form = dialog.locator('form.ui-form-workflow');
      await inspectDirty(form, `alias-${variant}-${suffix}`);
      if (variant.endsWith('alias')) {
        for (const tab of ['Identifier', 'Call Handling', 'Activity']) {
          await dialog.getByRole('tab', { name: tab, exact: true }).click();
          if (tab === 'Activity') await inspectWithoutFields(form, `alias-${variant}-${tab}-${suffix}`, true);
          else {
            await expectFormWorkflowGeometry(form);
            await captureFormWorkflowEvidence(form, `alias-${variant}-${tab}-${suffix}`);
          }
        }
      } else if (variant === 'defaults') {
        await dialog.getByRole('tab', { name: 'New Aliases', exact: true }).click();
        await expectFormWorkflowGeometry(form);
        await captureFormWorkflowEvidence(form, `alias-defaults-new-alias-${suffix}`);
      }
    }
  });

  test(`channel editor keeps its flush footer separate from highlighted fields ${suffix}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await installApplication(page, theme);
    await page.goto('/app.html?view=channel-setup');
    await page.locator('.channel-edit-name').filter({ hasText: channelName }).click();
    const form = page.getByRole('dialog', { name: `Edit ${channelName}`, exact: true }).locator('form.ui-form-workflow');
    await inspectDirty(form, `channel-editor-${suffix}`);
  });

  test(`RadioReference region switches between inline and modal padding owners ${suffix}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await installApplication(page, theme);
    await page.goto('/app.html?view=radioreference');
    const inline = page.locator('.radioreference-connected-page > form.radioreference-region-form');
    await expect(inline.getByLabel('State or region', { exact: true })).toBeEnabled();
    await inspectDirty(inline, `radioreference-inline-${suffix}`, false);
    await page.getByRole('button', { name: 'RadioReference settings', exact: true }).click();
    const form = page.getByRole('dialog', { name: 'RadioReference settings', exact: true })
      .locator('form.radioreference-region-form');
    await expectFormWorkflowGeometry(form);
    await expect(form.locator(':scope > .ui-form-workflow-body')).toHaveCount(1);
    await captureFormWorkflowEvidence(form, `radioreference-modal-${suffix}`);
    await page.getByRole('dialog', { name: 'RadioReference settings', exact: true })
      .getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.getByRole('alertdialog', { name: 'Discard unsaved changes', exact: true })
      .getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(inline).toBeVisible();
    await expect(inline.locator('.ui-form-workflow-body')).toHaveCount(0);
    await expect(inline).not.toHaveClass(/ui-form-workflow-modal/);
    await expect(inline.getByLabel('State or region', { exact: true })).toBeEnabled();
    await inspectDirty(inline, `radioreference-returned-inline-${suffix}`, false);
    await page.getByRole('button', { name: 'RadioReference settings', exact: true }).click();
    await expect(form.locator('.ui-form-workflow-body')).toHaveCount(1);
    await expect(form.locator(':scope > .ui-modal-footer')).toHaveCount(1);
    await expectFormWorkflowGeometry(form);
    await captureFormWorkflowEvidence(form, `radioreference-reopened-modal-${suffix}`);
  });

  test(`RadioReference name review keeps read-only comparisons inside its body ${suffix}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await installApplication(page, theme);
    await page.goto('/app.html?view=channel-setup');
    await page.locator('.channel-edit-name').filter({ hasText: channelName }).click();
    await page.getByRole('dialog', { name: `Edit ${channelName}`, exact: true })
      .getByRole('button', { name: 'Update from RadioReference', exact: true }).click();
    await page.getByRole('alertdialog', { name: 'Update from RadioReference', exact: true })
      .getByRole('button', { name: 'Look up channel', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Review RadioReference names', exact: true });
    await expect(dialog.getByRole('button', { name: 'Save', exact: true })).toBeEnabled();
    await inspectWithoutFields(dialog.locator('form.ui-form-workflow'), `radioreference-names-${suffix}`, true);
  });
}
