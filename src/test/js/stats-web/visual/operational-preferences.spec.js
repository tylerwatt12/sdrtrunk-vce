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
      patch_group_streaming_option: 'PATCH_GROUP', audio_record_format: 'MP3', mp3_setting: 'CBR_16',
      mp3_input_audio_format: 'SR_16000', mp3_normalize_audio: false, stats_logging_enabled: true,
      stats_detailed_history_enabled: false, stats_logging_retention_days: 30
    },
    options: {
      patch_group_streaming_options: [{ value: 'PATCH_GROUP', label: 'Patch Group' },
        { value: 'TALKGROUPS', label: 'Individual Talkgroups' }],
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

async function openApp(page, tier = 'admin', summaryEnabled = true) {
  let current = preferences();
  current.settings.stats_logging_enabled = summaryEnabled;
  const writes = [];
  let statusRequests = 0;
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: { data: { configured: true, authenticated: true,
        username: tier === 'admin' ? 'admin' : 'listener', tier, primary: tier === 'admin',
        capabilities: { dashboard: true, 'admin-settings': tier === 'admin',
          'receiver-health': tier === 'admin',
          'admin-users': tier === 'admin', 'admin-access': tier === 'admin', credits: true } } } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences: defaultPreferences } });
    } else if (pathname === '/api/v1/status') {
      statusRequests += 1;
      const enabled = current.settings.stats_logging_enabled;
      await route.fulfill({ json: { data: {
        stats_logging: { summary_configured: enabled, summary_active: enabled,
          state: enabled ? 'RUNNING' : 'DISABLED', last_successful_write_ms: 42 },
        database: { stats_logging_enabled: enabled, logger: [] }
      } } });
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
  return { writes, current: () => current, statusRequests: () => statusRequests, changeServer: (change) => {
    current = { ...current, revision: 'c'.repeat(64), settings: { ...current.settings, ...change } };
  } };
}

test('saved statistics notice explains Off and clears after saving On', async ({ page }) => {
  const app = await openApp(page, 'admin', false);
  await page.locator('.primary-nav [data-nav-group="listen"] summary').click();
  await page.locator('.primary-nav a[data-view="dashboard"]').click();
  const notice = page.locator('main .ui-notice').filter({ hasText: 'Saved activity summaries are off.' });
  await expect(notice).toBeVisible();
  await expect(notice).toHaveClass(/ui-notice-warning/);
  await expect(notice).not.toContainText('Last update');
  await notice.getByRole('link', { name: 'Call output & activity' }).click();
  const summary = page.locator('form[data-preference="stats_logging_enabled"]');
  await summary.locator('.ui-toggle').click();
  const previousStatusRequests = app.statusRequests();
  await summary.getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => app.statusRequests()).toBeGreaterThan(previousStatusRequests);
  await expect(page.locator('.operational-preferences .admin-form-message')).toHaveText('Saved.');
  await page.locator('.primary-nav [data-nav-group="listen"] summary').click();
  await page.locator('.primary-nav a[data-view="dashboard"]').click();
  await expect(page.locator('main .ui-notice').filter({ hasText: 'Saved activity summaries are off.' }))
    .toHaveCount(0);
});

async function openCurrentStatus(page, tab = 'health') {
  const requests = { status: 0, health: 0 };
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: { data: { configured: true, authenticated: true,
        username: 'admin', tier: 'admin', primary: true,
        capabilities: { dashboard: true, 'admin-settings': true, 'receiver-health': true,
          'admin-users': true, 'admin-access': true, credits: true } } } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences: defaultPreferences } });
    } else if (pathname === '/api/v1/status') {
      requests.status += 1;
      await route.fulfill({ json: { data: {
        stats_logging: { summary_configured: true, detailed_history_configured: true,
          summary_active: true, detailed_history_active: true, state: 'RUNNING' },
        database: { database_bytes: 1_048_576, detailed_history_available: true, logger: [] }
      } } });
    } else if (pathname === '/api/v1/receiver-health') {
      requests.health += 1;
      await route.fulfill({ json: { data: { started_at_ms: Date.now() - 60_000,
        generated_at_ms: Date.now(), summary: { severity: 'healthy', active_count: 0,
          warning_count: 0, critical_count: 0 }, active: [], resolved: [], measurements: [] } } });
    } else {
      await route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
    }
  });
  await page.goto(`/app.html?view=admin&tab=${tab}`);
  return requests;
}

test('administrator edits one receiver preference at a time', async ({ page }) => {
  const app = await openApp(page);
  const workspace = page.locator('.operational-preferences');
  await expect(workspace).toBeVisible();
  await expect(workspace.locator('.settings-card')).toHaveCount(4);
  await expect(workspace.getByRole('button', { name: 'Save', exact: true })).toHaveCount(0);
  const patch = workspace.locator('form[data-preference="patch_group_streaming_option"]');
  await patch.locator('select').selectOption('TALKGROUPS');
  await expect(patch.getByText('Unsaved change')).toBeVisible();
  await patch.getByRole('button', { name: 'Save' }).click();
  await expect(workspace.locator('.admin-form-message')).toHaveText('Saved.');
  await expect(patch.getByRole('button', { name: 'Save' })).toHaveCount(0);
  expect(app.writes).toEqual([{ field: 'patch_group_streaming_option', value: 'TALKGROUPS',
    revision: `"${'a'.repeat(64)}"` }]);
  const detailed = workspace.locator('form[data-preference="stats_detailed_history_enabled"]');
  await detailed.locator('.ui-toggle').click();
  await expect(detailed.locator('input')).toBeChecked();
  await detailed.getByRole('button', { name: 'Save' }).click();
  await expect(workspace.locator('.admin-form-message')).toHaveText('Saved.');
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
  const section = page.locator('.admin-settings-content > .section');
  await expect(workspace).toHaveClass(/settings-page-form/);
  await expect(section.locator('.admin-workflow-note')).toHaveCount(0);
  await expect(section.locator(':scope > .ui-section-title')
    .getByRole('button', { name: 'Reload saved settings' })).toBeVisible();
  await expect(workspace.locator('.operational-preference-lane')).toHaveCount(2);
  await expect(workspace.getByText('Calls & audio', { exact: true })).toBeVisible();
  await expect(workspace.getByText('Activity history', { exact: true })).toBeVisible();
  const mp3Setting = workspace.locator('form[data-preference="mp3_setting"] select');
  const mp3Format = workspace.locator('form[data-preference="mp3_input_audio_format"] select');
  await expect(mp3Setting).toHaveValue('CBR_16');
  await expect(mp3Setting.locator('option')).toHaveCount(2);
  await expect(mp3Format).toHaveValue('SR_16000');
  await expect(mp3Format.locator('option')).toHaveCount(2);
  await expect(workspace.getByText('Unsaved change')).toHaveCount(0);
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect(page.locator('main')).toHaveScreenshot('admin-operations-light-desktop.png');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect(page.locator('main')).toHaveScreenshot('admin-operations-dark-mobile.png');
});

test('administration navigation becomes a complete compact picker on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openApp(page);
  const navigation = page.locator('.admin-settings-tree');
  const picker = navigation.locator('.admin-settings-picker');
  const select = picker.getByRole('combobox', { name: 'Administration section' });

  await expect(picker).toBeHidden();
  await expect(navigation.locator('.admin-settings-branch').first()).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(picker).toBeVisible();
  await expect(select).toHaveValue('operations');
  await expect(navigation.locator('.admin-settings-branch:visible')).toHaveCount(0);
  expect(await select.locator('option').evaluateAll((options) => options.map((option) => option.value)))
    .toEqual(['health', 'call-matching', 'support', 'operations', 'retained-statistics',
      'spectrum', 'protocol-p25',
      'users', 'access', 'live-timing']);

  await select.selectOption('support');
  await expect(page).toHaveURL(/view=admin&tab=support/);
  await expect(page.getByRole('heading', { level: 1, name: 'Report a problem' })).toBeVisible();
});

test('current status owns saved activity and refreshes both status sources', async ({ page }) => {
  const requests = await openCurrentStatus(page, 'activity');
  await expect(page).toHaveURL(/view=admin&tab=health/);
  await expect(page.getByRole('heading', { level: 1, name: 'Current status' })).toBeVisible();
  await expect(page.locator('#receiver-health-saved-activity')).toContainText('Saved activity');
  await expect(page.locator('#receiver-health-saved-activity')).toContainText('Activity summaries');
  const initialStatusRequests = requests.status;
  const initialHealthRequests = requests.health;
  await page.getByRole('button', { name: 'Check again' }).click();
  await expect.poll(() => requests.status).toBeGreaterThan(initialStatusRequests);
  await expect.poll(() => requests.health).toBeGreaterThan(initialHealthRequests);
});

test('saving one preference keeps another unsaved field', async ({ page }) => {
  const app = await openApp(page);
  const workspace = page.locator('.operational-preferences');
  const patch = workspace.locator('form[data-preference="patch_group_streaming_option"]');
  const recording = workspace.locator('form[data-preference="audio_record_format"]');
  await patch.locator('select').selectOption('TALKGROUPS');
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
  await expect(workspace).toContainText('These settings changed elsewhere. The saved settings were reloaded.');
  await expect(workspace.locator('form[data-preference="stats_logging_retention_days"] input'))
    .toHaveValue('60');
});

test('a regular web user never requests receiver operations', async ({ page }) => {
  const app = await openApp(page, 'user');
  await expect(page.locator('.operational-preferences')).toHaveCount(0);
  expect(app.writes).toEqual([]);
});
