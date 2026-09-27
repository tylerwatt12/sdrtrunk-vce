'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

function preferences() {
  return {
    revision: 'a'.repeat(64),
    settings: {
      patch_group_streaming_option: 'GROUP', audio_record_format: 'MP3', mp3_setting: 'CBR_16',
      mp3_input_audio_format: 'SR_16000', mp3_normalize_audio: false, stats_logging_enabled: true,
      stats_detailed_history_enabled: false, stats_logging_retention_days: 30
    },
    options: {
      patch_group_streaming_options: [{ value: 'GROUP', label: 'As group' },
        { value: 'INDIVIDUAL', label: 'Individually' }],
      audio_record_formats: [{ value: 'MP3', label: 'MP3' }, { value: 'WAVE', label: 'WAV' }],
      mp3_settings: [{ value: 'CBR_16', label: '16 kbps' }, { value: 'VBR_5', label: 'Variable' }],
      mp3_input_audio_formats_by_setting: {
        CBR_16: [{ value: 'SR_8000', label: '8 kHz' }, { value: 'SR_16000', label: '16 kHz' }],
        VBR_5: [{ value: 'SR_16000', label: '16 kHz' }, { value: 'SR_44100', label: '44.1 kHz' }]
      },
      minimum_stats_logging_retention_days: 1, maximum_stats_logging_retention_days: 365
    }
  };
}

async function openApp(page, tier = 'admin') {
  let current = preferences();
  const writes = [];
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: { data: { configured: true, authenticated: true,
        username: tier === 'admin' ? 'admin' : 'listener', tier, primary: tier === 'admin',
        capabilities: { 'admin-settings': tier === 'admin', credits: true } } } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences: defaultPreferences } });
    } else if (pathname === '/api/v1/admin/operational-preferences') {
      await route.fulfill({ json: current });
    } else if (pathname.startsWith('/api/v1/admin/operational-preferences/')) {
      const field = pathname.split('/').at(-1);
      const value = JSON.parse(route.request().postData() || '{}').value;
      writes.push({ field, value, revision: route.request().headers()['if-match'] });
      if (route.request().headers()['if-match'] !== `"${current.revision}"`) {
        await route.fulfill({ status: 409, json: current });
      } else {
        current = { ...current, revision: 'b'.repeat(64),
          settings: { ...current.settings, [field]: value } };
        await route.fulfill({ json: current });
      }
    } else {
      await route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
    }
  });
  await page.goto('/app.html?view=admin&tab=operations');
  return { writes, current: () => current, changeServer: (change) => {
    current = { ...current, revision: 'c'.repeat(64), settings: { ...current.settings, ...change } };
  } };
}

test('administrator edits one receiver preference at a time', async ({ page }) => {
  const app = await openApp(page);
  const workspace = page.locator('.operational-preferences');
  await expect(workspace).toBeVisible();
  await expect(workspace.locator('.settings-card')).toHaveCount(4);
  await expect(workspace.getByRole('button', { name: 'Save', exact: true })).toHaveCount(0);
  const patch = workspace.locator('form[data-preference="patch_group_streaming_option"]');
  await patch.locator('select').selectOption('INDIVIDUAL');
  await expect(patch.getByText('Unsaved change')).toBeVisible();
  await patch.getByRole('button', { name: 'Save' }).click();
  await expect(workspace).toContainText('Stream a patch-group call as saved.');
  await expect(patch.getByRole('button', { name: 'Save' })).toHaveCount(0);
  expect(app.writes).toEqual([{ field: 'patch_group_streaming_option', value: 'INDIVIDUAL',
    revision: `"${'a'.repeat(64)}"` }]);
  const detailed = workspace.locator('form[data-preference="stats_detailed_history_enabled"]');
  await detailed.locator('.ui-toggle').click();
  await expect(detailed.locator('input')).toBeChecked();
  await detailed.getByRole('button', { name: 'Save' }).click();
  await expect(workspace).toContainText('Store detailed event history saved.');
  expect(app.current().settings.stats_detailed_history_enabled).toBe(true);
  expect(app.writes.at(-1).revision).toBe(`"${'b'.repeat(64)}"`);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(workspace.locator('.settings-card').first()).toBeVisible();
  const width = await workspace.locator('.settings-card').first().boundingBox();
  expect(width.x + width.width).toBeLessThanOrEqual(390);
});

test('receiver settings read as separate output and activity workflows', async ({ page }) => {
  await openApp(page);
  const workspace = page.locator('.operational-preferences');
  await expect(workspace.locator('.operational-preference-lane')).toHaveCount(2);
  await expect(workspace.getByText('Calls & audio', { exact: true })).toBeVisible();
  await expect(workspace.getByText('Activity history', { exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect(page.locator('main')).toHaveScreenshot('admin-operations-light-desktop.png');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect(page.locator('main')).toHaveScreenshot('admin-operations-dark-mobile.png');
});

test('saving one preference keeps another unsaved field', async ({ page }) => {
  const app = await openApp(page);
  const workspace = page.locator('.operational-preferences');
  const patch = workspace.locator('form[data-preference="patch_group_streaming_option"]');
  const recording = workspace.locator('form[data-preference="audio_record_format"]');
  await patch.locator('select').selectOption('INDIVIDUAL');
  await recording.locator('select').selectOption('WAVE');
  await patch.getByRole('button', { name: 'Save' }).click();
  await expect(recording.locator('select')).toHaveValue('WAVE');
  await expect(recording.getByText('Unsaved change')).toBeVisible();
  await recording.getByRole('button', { name: 'Save' }).click();
  expect(app.writes.map((write) => write.field)).toEqual([
    'patch_group_streaming_option', 'audio_record_format'
  ]);
});

test('a stale local edit reloads the current saved values', async ({ page }) => {
  const app = await openApp(page);
  const workspace = page.locator('.operational-preferences');
  await expect(workspace).toBeVisible();
  app.changeServer({ stats_logging_retention_days: 60 });
  const retention = workspace.locator('form[data-preference="stats_logging_retention_days"]');
  await retention.locator('input').fill('90');
  await retention.getByRole('button', { name: 'Save' }).click();
  await expect(workspace).toContainText('Current values were reloaded.');
  await expect(workspace.locator('form[data-preference="stats_logging_retention_days"] input'))
    .toHaveValue('60');
});

test('a regular web user never requests receiver operations', async ({ page }) => {
  const app = await openApp(page, 'user');
  await expect(page.locator('.operational-preferences')).toHaveCount(0);
  expect(app.writes).toEqual([]);
});
