const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaults;
test.beforeAll(async () => {
  defaults = (await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults;
});

async function openEditor(page, theme) {
  const preferences = structuredClone(defaults);
  preferences.appearance.theme = theme;
  const channel = {
    configuration_id: 'mobile-editor', protocol_id: 'nbfm', name: 'County channel',
    source: { frequency_hz: 154000000 }, settings: { squelch: 20 },
    event_logs: [], recorders: [], auxiliary_decoders: []
  };
  const profile = {
    id: 'nbfm', label: 'NBFM', channel_kind: 'CONVENTIONAL', sections: [
      { id: 'general', label: 'General', fields: [
        { path: 'name', label: 'Name', type: 'text', required: true }
      ] },
      { id: 'source', label: 'Source', fields: [
        { path: 'source.frequency_hz', label: 'Frequency', type: 'frequency', required: true }
      ] },
      { id: 'protocol', label: 'Decoder', fields: [
        { path: 'settings.squelch', label: 'Squelch threshold', type: 'integer', default: 20 }
      ] },
      { id: 'output', label: 'Logging & Recording', fields: [
        { path: 'recorders', label: 'Recorders', type: 'multi_select', options: [
          { value: 'AUDIO', label: 'Audio' }
        ] }
      ] }
    ]
  };
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } });
      return;
    }
    const data = pathname === '/api/v1/auth/session' ? {
      configured: true, authenticated: true, username: 'operator', tier: 'admin', primary: true,
      csrf_token: 'test-token', capabilities: { 'admin-channels': true }
    } : pathname === '/api/v1/admin/channels' ? {
      revision: 1, channels: [{ ...channel, protocol_label: 'NBFM', channel_kind: 'CONVENTIONAL',
        frequencies_hz: [channel.source.frequency_hz], processing_state: 'STOPPED', editable: true }]
    } : pathname === '/api/v1/admin/channels/mobile-editor' ? {
      revision: 1, channel, processing_state: 'STOPPED'
    } : pathname === '/api/v1/admin/channels/options' ? {
      revision: 1, alias_lists: [], tuners: []
    } : pathname === '/api/v1/admin/channels/protocols' ? { profiles: [profile] } : null;
    await route.fulfill(data ? { json: { data } } : {
      status: 404, json: { error: { message: 'Unavailable in this browser contract.' } }
    });
  });
  await page.goto('/app.html?view=channel-setup&channel=mobile-editor');
  const dialog = page.getByRole('dialog', { name: 'Edit County channel' });
  await expect(dialog).toBeVisible();
  return dialog;
}

for (const [width, theme] of [[320, 'light'], [390, 'dark']]) {
  test(`mobile channel editor keeps one section open and preserves drafts ${theme} ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    const dialog = await openEditor(page, theme);
    const sections = dialog.locator('details.channel-editor-section-disclosure');
    await expect(sections).toHaveCount(4);
    const section = (id) => dialog.locator(`details.channel-editor-section-disclosure[data-channel-section="${id}"]`);
    await expect(dialog.locator('details[open]')).toHaveCount(1);
    await expect(section('general')).toHaveAttribute('open', '');
    if (theme === 'dark') await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    else await expect(page.locator('html')).not.toHaveAttribute('data-theme', 'dark');
    await expect(dialog).toHaveScreenshot(`channel-editor-${theme}-${width}.png`);
    await dialog.getByLabel('Name', { exact: true }).fill('Draft channel');
    for (const id of ['source', 'protocol', 'output']) {
      await section(id).locator('summary').focus();
      await page.keyboard.press('Enter');
      await expect(section(id)).toHaveAttribute('open', '');
      await expect(dialog.locator('details[open]')).toHaveCount(1);
    }
    await section('general').locator('summary').click();
    await expect(dialog.getByLabel('Name', { exact: true })).toHaveValue('Draft channel');
    await section('source').locator('summary').click();
    await dialog.getByLabel('Frequency', { exact: true }).fill('');
    await section('general').locator('summary').click();
    await dialog.getByRole('button', { name: 'Save changes', exact: true }).click();
    await expect(section('source')).toHaveAttribute('open', '');
    await expect(dialog.locator('details[open]')).toHaveCount(1);
    await expect(dialog.getByLabel('Frequency', { exact: true })).toBeFocused();
    await section('general').locator('summary').click();
    await dialog.getByLabel('Name', { exact: true }).fill('');
    await section('output').locator('summary').click();
    await dialog.getByRole('button', { name: 'Save changes', exact: true }).click();
    await expect(section('general')).toHaveAttribute('open', '');
    await expect(dialog.locator('details[open]')).toHaveCount(1);
    await expect(dialog.getByLabel('Name', { exact: true })).toBeFocused();
    await dialog.getByLabel('Name', { exact: true }).fill('Draft channel');
    await section('source').locator('summary').click();
    await dialog.getByLabel('Frequency', { exact: true }).fill('155.125');
    await dialog.screenshot({ path: test.info().outputPath(`editor-${theme}-${width}.png`) });

    await page.setViewportSize({ width: 1280, height: 900 });
    for (const id of ['general', 'source', 'protocol']) {
      await expect(section(id)).toHaveAttribute('open', '');
      await expect(section(id).locator('summary')).toBeHidden();
    }
    await dialog.getByRole('navigation', { name: 'Jump to channel editor section' })
      .getByRole('link', { name: 'Logging & Recording' }).click();
    await expect(section('output')).toHaveAttribute('open', '');
    await page.setViewportSize({ width, height: 844 });
    await expect(dialog.locator('details[open]')).toHaveCount(1);
    await expect(section('output')).toHaveAttribute('open', '');
    await section('general').locator('summary').click();
    await expect(dialog.getByLabel('Name', { exact: true })).toHaveValue('Draft channel');
    expect(await dialog.evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);
  });
}
