'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;
let healthAlertIds;
const capabilitySource = fs.readFileSync(path.resolve(__dirname,
  '../../../../../src/main/java/io/github/dsheirer/web/auth/WebCapability.java'), 'utf8');
const accessPolicies = [...capabilitySource.matchAll(
  /^\s+[A-Z_]+\("([^"]+)", "([^"]+)", AccessTier\.(PUBLIC|USER|ADMIN)(, false)?\)/gm)]
  .map(([, id, display_name, required_tier, fixed]) => ({
    id, display_name, required_tier, configurable: !fixed
  }));

test.beforeAll(async () => {
  const core = path.resolve(__dirname, '../../../../../stats-web/assets/core');
  defaultPreferences = (await import(pathToFileURL(path.join(core, 'preference-schema.js')).href)).defaults;
  healthAlertIds = (await import(pathToFileURL(path.join(core, 'receiver-health-alerts.js')).href))
    .receiverHealthAlertIds;
});

function operationalDocument() {
  return {
    revision: 'a'.repeat(64),
    settings: {
      patch_group_streaming_option: 'PATCH_GROUP', audio_record_format: 'MP3', mp3_setting: 'CBR_16',
      mp3_input_audio_format: 'SR_16000', mp3_normalize_audio: false,
      stats_logging_enabled: true, stats_detailed_history_enabled: true, stats_logging_retention_days: 30
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

async function openAdmin(page, tab = 'audio-quality', denied = [], options = {}) {
  await page.addInitScript(() => {
    for (const id of ['welcome', 'channel-setup']) {
      localStorage.setItem(`sdrtrunk-vce-guide:v1:admin:account-1-100:${id}:1`, '1');
    }
  });
  let operational = operationalDocument();
  let receiver = { revision: 4, settings: { traffic_grant_age_out_milliseconds: 2000 } };
  let spectrum = { revision: 9, country_code: 'US', country_label: 'United States',
    countries: [{ code: 'US', label: 'United States' }], scopes: [], ...options.spectrumDocument };
  let p25 = { profiles: [{ wacn: 0xBEE00, system: 0x49F, rfss: 1, site: 2, bands: [
    { identifier: 0, type: 'FDMA', base_frequency: 851_006_250, bandwidth: 12_500,
      channel_spacing: 12_500, transmit_offset: -45_000_000 },
    { identifier: 1, type: 'TDMA', base_frequency: 851_000_000, bandwidth: 12_500,
      channel_spacing: 12_500, transmit_offset: -45_000_000 }
  ] }], ...options.p25Document };
  const preferences = structuredClone(defaultPreferences);
  preferences.appearance.theme = options.theme || 'light';
  if (options.disabledHealthCodes) preferences.health_alerts.disabled_codes = [...options.disabledHealthCodes];
  const capabilities = Object.fromEntries(accessPolicies.map(({ id }) => [id, !denied.includes(id)]));
  const writes = [];
  const requests = [];
  let failedOperationalRequest = false;
  let remoteSupport = { enabled: false, connected: false, code: '', expires_at_ms: 0,
    unlimited: false, can_start: true, ...options.remoteSupport };
  let remoteSupportFailure = options.remoteSupportFailure === true;
  let remoteSupportStopNotSaved = options.remoteSupportStopNotSavedOnce === true;
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    requests.push(pathname);
    const data = (value) => route.fulfill({ json: { data: value } });
    if (pathname === '/api/v1/auth/session') {
      await data({ configured: true, authenticated: true, username: 'admin', tier: options.tier || 'admin',
        primary: true, guide_dismissal_scope: 'account-1-100', capabilities });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } });
    } else if (pathname === '/api/v1/remote-support') {
      if (remoteSupportFailure) {
        remoteSupportFailure = false;
        await route.fulfill({ status: 503, json: { error: { message: 'Unavailable' } } });
      } else {
        if (request.method() === 'POST') {
          const change = request.postDataJSON(); writes.push(change);
          if (change.action === 'stop' && remoteSupportStopNotSaved) {
            remoteSupportStopNotSaved = false;
            remoteSupport = { ...remoteSupport, enabled: false, connected: false, code: '', stop_not_saved: true };
            await route.fulfill({ status: 503, json: { error: { code: 'remote_support_stop_not_saved',
              message: 'Private file details must not be shown.' } } });
            return;
          }
          remoteSupport = change.action === 'stop' ? { ...remoteSupport, enabled: false, stop_not_saved: false } :
            { ...remoteSupport, enabled: true, connected: false, code: '482719356',
              expires_at_ms: change.duration_seconds ? Date.now() + change.duration_seconds * 1000 : 0,
              unlimited: change.duration_seconds === 0 };
        }
        await data(remoteSupport);
      }
    } else if (pathname === '/api/v1/admin/support-reports' ||
        pathname.startsWith('/api/v1/admin/support-reports/')) {
      if (request.method() === 'POST' && pathname === '/api/v1/admin/support-reports') {
        writes.push({ field: 'support-report', report: request.postDataJSON() });
      }
      await data({ id: 'prepared-report', state: pathname.endsWith('/cancel') ? 'cancelled' : 'ready',
        bytes: 2048, progress: 100, message: 'Report ready', output: [] });
    } else if (pathname === '/api/v1/admin/paging-tone-detection') {
      await route.fulfill({ json: { revision: '1'.repeat(64), enabled: true, status: 'ready' } });
    } else if (pathname === '/api/v1/admin/operational-preferences') {
      if (options.operationalErrorOnce && !failedOperationalRequest) {
        failedOperationalRequest = true;
        await route.fulfill({ status: 503, json: {
          error: { message: 'Settings temporarily unavailable. Reload saved settings and try again.' }
        } });
      } else await route.fulfill({ json: operational });
    } else if (pathname.startsWith('/api/v1/admin/operational-preferences/')) {
      const field = pathname.split('/').at(-1);
      const value = JSON.parse(request.postData()).value;
      writes.push({ field, value, revision: request.headers()['if-match'] });
      if (request.headers()['if-match'] !== `"${operational.revision}"`) {
        await route.fulfill({ status: 409, json: operational });
      } else {
        operational = { ...operational, revision: 'b'.repeat(64),
          settings: { ...operational.settings, [field]: value } };
        await route.fulfill({ json: operational });
      }
    } else if (pathname === '/api/v1/admin/receiver-settings') {
      if (request.method() === 'PUT') {
        const settings = JSON.parse(request.postData());
        writes.push({ field: 'receiver-settings', settings, revision: request.headers()['if-match'] });
        receiver = { revision: receiver.revision + 1, settings };
      }
      await route.fulfill({ json: receiver });
    } else if (pathname === '/api/v1/admin/spectrum-snap-presets') {
      if (request.method() === 'PUT') {
        const settings = JSON.parse(request.postData());
        writes.push({ field: 'spectrum-country', settings, revision: request.headers()['if-match'] });
        spectrum = { ...spectrum, revision: spectrum.revision + 1, ...settings,
          country_label: spectrum.countries.find(({ code }) => code === settings.country_code).label };
      }
      await route.fulfill({ json: spectrum });
    } else if (pathname === '/api/v1/admin/access') {
      await data({ capabilities: accessPolicies.map((policy) => ({ ...policy,
        required_tier: policy.required_tier.toLowerCase() })) });
    } else if (pathname === '/api/v1/admin/p25-bandplan-overrides') {
      if (request.method() === 'PUT') {
        p25 = JSON.parse(request.postData());
        writes.push({ field: 'p25-bandplan-overrides', profiles: p25.profiles });
      }
      await route.fulfill({ json: p25 });
    } else if (pathname === '/api/v1/alerts/topics') {
      await data({ enabled: options.pushEnabled !== false, push_enabled: options.pushReady === true, topics: [] });
    } else if (pathname === '/api/v1/admin/alerts/topics') {
      await data({ revision: 1, enabled: options.pushEnabled !== false, topics: [
        { id: 'fire', name: 'Fire activity', enabled: true, visible: true },
        { id: 'administrator-alerts', name: 'Administrator alerts', enabled: true, visible: false, built_in: true }
      ], rules: [], options: {} });
    } else if (pathname === '/api/v1/admin/streaming') {
      await data({ destinations: [{ configuration_id: 'county-calls',
        name: 'County Calls', provider: 'BROADCASTIFY_CALL', provider_label: 'Broadcastify Calls',
        enabled: true, state: 'CONNECTED', state_label: 'Connected', queued: 3, sent: 128,
        aged_off: 4, errors: 2, last_error: null, attention: false }] });
    } else if (pathname === '/api/v1/status') {
      await data({ stats_logging: { summary_configured: operational.settings.stats_logging_enabled,
        detailed_history_configured: true, summary_active: operational.settings.stats_logging_enabled,
        detailed_history_active: true, state: 'RUNNING' },
      database: { database_exists: true, database_bytes: 1_048_576, detailed_history_available: true, logger: [] } });
    } else if (pathname === '/api/v1/receiver-health') {
      if (options.healthError) {
        await route.fulfill({ status: 503, json: { error: { message: 'Status temporarily unavailable.' } } });
        return;
      }
      await route.fulfill({ headers: options.omitHealthDate ? {} : {
        Date: options.healthDate || new Date().toUTCString()
      }, json: { data: { started_at_ms: Date.now() - 60_000, generated_at_ms: Date.now(),
        summary: { severity: 'healthy', active_count: 0, warning_count: 0, critical_count: 0 },
        active: [], resolved: [], measurements: [], ...options.healthDocument } } });
    } else {
      await route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
    }
  });
  await page.goto(options.view ? `/app.html?view=${options.view}` : `/app.html?view=admin&tab=${tab}`);
  return { writes, requests };
}

async function preferenceIds(page) {
  return page.locator('.admin-settings-content form[data-preference]').evaluateAll((forms) =>
    forms.map((form) => form.dataset.preference));
}

async function expectRemoteSupportDetails(workspace) {
  for (const text of [
    'The developer can review radio activity, recordings, and logs, and change VCE settings to investigate your issue.',
    'This does not share your computer’s desktop or provide control of other applications.',
    'You can select Stop remote support at any time to end access. Changes already made will remain.'
  ]) {
    await expect(workspace.getByText(text, { exact: true })).toBeVisible();
  }
}

test('desktop administration navigation includes every grouped page and its icon', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openAdmin(page);
  const navigation = page.getByRole('navigation', { name: 'Administration sections' });
  const links = navigation.getByRole('link');
  await expect(links).toHaveText(['Receiver status', 'Application log', 'Web server log', 'Report a problem', 'Remote Support',
    'Call matching', 'Tuner allocation', 'Recording settings', 'Audio quality', 'Transcription',
    'Push setup', 'Activity settings', 'Saved data cleanup',
    'Remote Links', 'P25 band plans', 'Display settings', 'Web accounts', 'Page access']);
  await expect(links.locator('svg')).toHaveCount(18);
  await expect(links.locator('svg:not([aria-hidden="true"])')).toHaveCount(0);
  for (const label of ['Status & support', 'Decisions engine', 'Audio & recordings', 'Alerts', 'Activity & storage',
    'Receiver configuration', 'Web interface']) {
    await expect(navigation.getByText(label, { exact: true })).toBeVisible();
  }
  await expect(navigation.getByRole('link', { name: 'Audio quality', exact: true }))
    .toHaveAttribute('aria-current', 'page');
  await expect(navigation.getByRole('link', { name: 'Call output & activity' })).toHaveCount(0);
});

test('remote support stays simple in light and dark desktop and phone layouts', async ({ page }) => {
  for (const theme of ['light', 'dark']) {
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 900 });
      const app = await openAdmin(page, 'remote-support', [], { theme });
      const workspace = page.locator('[data-remote-support]');
      await expect(page.getByRole('heading', { level: 1, name: 'Remote Support', exact: true })).toBeVisible();
      await expect(page.getByText('Allow VCE’s developer to access this instance for troubleshooting.', { exact: true })).toBeVisible();
      await expectRemoteSupportDetails(workspace);
      await expect(workspace.getByLabel('Allow access for')).toHaveValue('21600');
      await expect(workspace.locator('option')).toHaveText(['30 minutes', '1 hour', '6 hours', '24 hours', 'Unlimited']);
      await expect(workspace.getByRole('button', { name: 'Allow remote support', exact: true })).toBeEnabled();
      await workspace.getByLabel('Allow access for').selectOption('0');
      await expect(workspace.getByText('Unsaved changes', { exact: true })).toHaveCount(0);
      await workspace.getByRole('button', { name: 'Allow remote support', exact: true }).click();
      await expect(workspace.getByText('Support code', { exact: true })).toBeVisible();
      await expect(workspace).not.toContainText(/Tyler/);
      await expect(workspace.getByText('482 719 356', { exact: true })).toBeVisible();
      await expect(workspace.getByText('No time limit', { exact: true })).toBeVisible();
      await expect(workspace.getByText('Connecting… VCE will keep trying.', { exact: true })).toBeVisible();
      await expect(workspace.getByRole('button', { name: 'Copy', exact: true })).toBeEnabled();
      await page.evaluate(() => { navigator.clipboard.writeText = async text => { window.supportCodeCopied = text; }; });
      await workspace.getByRole('button', { name: 'Copy', exact: true }).click();
      await expect(workspace.getByText('Code copied.', { exact: true })).toBeVisible();
      expect(await page.evaluate(() => window.supportCodeCopied)).toBe('482719356');
      await expect(workspace.getByLabel('Allow access for')).toBeHidden();
      await expect(workspace.getByRole('button', { name: 'Stop remote support', exact: true })).toBeEnabled();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await workspace.screenshot({ path: `build/playwright-results/remote-support-${theme}-${width}.png` });
      await workspace.getByRole('button', { name: 'Stop remote support', exact: true }).click();
      await expect(workspace.getByRole('button', { name: 'Allow remote support', exact: true })).toBeEnabled();
      expect(app.writes).toEqual([{ action: 'start', duration_seconds: 0 }, { action: 'stop' }]);
    }
  }
});

test('remote support retries safely, permits remote stop, and stops polling after navigation', async ({ page }, testInfo) => {
  await page.clock.install();
  const app = await openAdmin(page, 'remote-support', [], { remoteSupportFailure: true,
    remoteSupport: { enabled: true, connected: true, code: '482719356', can_start: false,
      expires_at_ms: Date.now() + 21_600_000 } });
  const workspace = page.locator('[data-remote-support]');
  await expect(workspace.getByText('Remote support status is unavailable. Try again.', { exact: true })).toBeVisible();
  await workspace.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(workspace.getByText('Remote support allowed.', { exact: true })).toBeVisible();
  await expect(workspace.getByText(/^Access ends /)).toBeVisible();
  await workspace.screenshot({ path: testInfo.outputPath('remote-support-allowed.png') });
  await workspace.getByRole('button', { name: 'Stop remote support', exact: true }).click();
  await expect(workspace.getByText('Start remote support from this installation.', { exact: true })).toBeVisible();
  await expect(workspace.getByRole('button', { name: 'Allow remote support', exact: true })).toBeHidden();
  await page.getByRole('navigation', { name: 'Administration sections' }).getByRole('link', { name: 'Audio quality', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Audio quality' })).toBeVisible();
  const count = app.requests.filter(pathname => pathname === '/api/v1/remote-support').length;
  await page.clock.runFor(5500);
  expect(app.requests.filter(pathname => pathname === '/api/v1/remote-support')).toHaveLength(count);
});

test('problem reports allow optional remote support with empty required fields in every layout', async ({ page }) => {
  for (const theme of ['light', 'dark']) {
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 900 });
      const app = await openAdmin(page, 'support', [], { theme });
      const report = page.locator('#support-report-form');
      const workspace = page.locator('[data-remote-support]');
      await expect(workspace.getByRole('heading', { name: 'Remote support (optional)', exact: true })).toBeVisible();
      await expect(workspace.getByText('Allow VCE’s developer to access this instance for troubleshooting.', { exact: true })).toBeVisible();
      await expectRemoteSupportDetails(workspace);
      await expect(workspace.getByLabel('Allow access for')).toHaveValue('21600');
      await expect(page.getByRole('button', { name: 'Prepare support report', exact: true }))
        .toHaveAttribute('form', 'support-report-form');
      expect(await workspace.evaluate(panel => panel.previousElementSibling.id)).toBe('support-report-form');
      expect(await workspace.evaluate(panel => panel.nextElementSibling.classList.contains('support-report-footer'))).toBe(true);
      expect(await workspace.locator('form').evaluate(form => form.parentElement.closest('form'))).toBeNull();
      for (const label of ['Title', 'Email address', 'What happened', 'What happened before the problem?']) {
        await report.getByRole('textbox', { name: label, exact: label !== 'Email address' }).fill('');
      }
      await report.evaluate(form => {
        window.reportInvalidEvents = 0;
        form.addEventListener('invalid', () => { window.reportInvalidEvents += 1; }, true);
      });
      await workspace.getByRole('button', { name: 'Allow remote support', exact: true }).click();
      await expect(workspace.getByText('Support code', { exact: true })).toBeVisible();
      await expect(workspace.getByText('482 719 356', { exact: true })).toBeVisible();
      await expect(workspace).not.toContainText(/Tyler/);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await workspace.screenshot({ path: `build/playwright-results/report-remote-support-${theme}-${width}.png` });
      await report.getByRole('textbox', { name: 'Title', exact: true }).fill('Keep this draft');
      await workspace.getByRole('button', { name: 'Stop remote support', exact: true }).click();
      await expect(workspace.getByRole('button', { name: 'Allow remote support', exact: true })).toBeEnabled();
      await expect(report.getByRole('textbox', { name: 'Title', exact: true })).toHaveValue('Keep this draft');
      expect(await page.evaluate(() => window.reportInvalidEvents)).toBe(0);
      expect(app.requests.some(pathname => pathname.startsWith('/api/v1/admin/support-reports'))).toBe(false);
      expect(app.writes).toEqual([{ action: 'start', duration_seconds: 21600 }, { action: 'stop' }]);
    }
  }
});

test('remote support leaves a prepared report intact and stops polling when leaving the report page', async ({ page }) => {
  await page.clock.install();
  const app = await openAdmin(page, 'support');
  const report = page.locator('#support-report-form');
  const workspace = page.locator('[data-remote-support]');
  await report.getByRole('textbox', { name: 'Title', exact: true }).fill('Audio playback is silent');
  await report.getByRole('textbox', { name: 'Email address' }).fill('operator@example.com');
  await report.getByRole('textbox', { name: 'What happened', exact: true }).fill('Expected audio, but playback is silent.');
  await report.getByRole('textbox', { name: 'What happened before the problem?', exact: true }).fill('Started playback from Live.');
  await page.getByRole('button', { name: 'Prepare support report', exact: true }).click();
  const submit = page.getByRole('button', { name: 'Submit report', exact: true });
  const download = page.getByRole('link', { name: 'Download report files', exact: true });
  await expect(submit).toBeVisible();
  await expect(download).toHaveAttribute('href', '/api/v1/admin/support-reports/prepared-report/download');
  await workspace.getByLabel('Allow access for').selectOption('3600');
  await workspace.getByRole('button', { name: 'Allow remote support', exact: true }).click();
  await workspace.getByRole('button', { name: 'Stop remote support', exact: true }).click();
  await expect(submit).toBeVisible();
  await expect(download).toBeVisible();
  await expect(page.locator('.support-report-message')).toHaveText('Report ready · 2 KB');
  expect(app.requests.some(pathname => pathname.endsWith('/cancel'))).toBe(false);
  expect(app.writes.filter(change => change.field === 'support-report')).toHaveLength(1);
  expect(app.writes.filter(change => change.action)).toEqual([
    { action: 'start', duration_seconds: 3600 }, { action: 'stop' }
  ]);
  const count = app.requests.filter(pathname => pathname === '/api/v1/remote-support').length;
  await page.clock.runFor(5100);
  await expect.poll(() => app.requests.filter(pathname => pathname === '/api/v1/remote-support').length).toBeGreaterThan(count);
  await page.getByRole('navigation', { name: 'Administration sections' })
    .getByRole('link', { name: 'Audio quality', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Audio quality' })).toBeVisible();
  const countAfterLeaving = app.requests.filter(pathname => pathname === '/api/v1/remote-support').length;
  await page.clock.runFor(5500);
  expect(app.requests.filter(pathname => pathname === '/api/v1/remote-support')).toHaveLength(countAfterLeaving);
});

test('problem reports preserve the existing remote support start restriction and settings permission', async ({ page }) => {
  const remote = await openAdmin(page, 'support', [], { remoteSupport: { can_start: false } });
  const workspace = page.locator('[data-remote-support]');
  await expect(workspace.getByText('Start remote support from this installation.', { exact: true })).toBeVisible();
  await expect(workspace.getByRole('button', { name: 'Allow remote support', exact: true })).toBeHidden();
  expect(remote.writes).toEqual([]);
  const denied = await openAdmin(page, 'support', ['admin-settings']);
  await expect(page.getByRole('navigation', { name: 'Administration sections' })
    .getByRole('link', { name: 'Report a problem', exact: true })).toHaveCount(0);
  await expect(page.locator('[data-remote-support]')).toHaveCount(0);
  expect(denied.requests).not.toContain('/api/v1/remote-support');
});

test('remote support keeps the unsaved-stop warning and Stop retry until the setting is saved', async ({ page }) => {
  await page.clock.install();
  await page.setViewportSize({ width: 390, height: 900 });
  const app = await openAdmin(page, 'remote-support', [], { remoteSupportStopNotSavedOnce: true,
    remoteSupport: { enabled: true, connected: true, code: '482719356', expires_at_ms: Date.now() + 21_600_000 } });
  const workspace = page.locator('[data-remote-support]');
  const warning = workspace.locator('p.ui-notice-warning');
  await workspace.getByRole('button', { name: 'Stop remote support', exact: true }).click();
  await expect(warning).toHaveText('Remote access is off for now, but this setting could not be saved. Check the application data folder before restarting VCE.');
  await expect(warning).toBeVisible();
  await expect(workspace.locator('.ui-notice-warning:visible')).toHaveCount(1);
  await expect(workspace.getByRole('button', { name: 'Stop remote support', exact: true })).toBeEnabled();
  await expect(workspace.getByLabel('Allow access for')).toBeHidden();
  const count = app.requests.filter(pathname => pathname === '/api/v1/remote-support').length;
  await page.clock.runFor(5100);
  await expect.poll(() => app.requests.filter(pathname => pathname === '/api/v1/remote-support').length).toBeGreaterThan(count);
  await expect(warning).toBeVisible();
  await expect(workspace.getByRole('button', { name: 'Stop remote support', exact: true })).toBeEnabled();
  await expect(workspace.getByRole('button', { name: 'Allow remote support', exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await workspace.screenshot({ path: 'build/playwright-results/remote-support-stop-warning.png' });
  await workspace.getByRole('button', { name: 'Stop remote support', exact: true }).click();
  await expect(warning).toBeHidden();
  await expect(workspace.getByLabel('Allow access for')).toBeVisible();
  await expect(workspace.getByRole('button', { name: 'Allow remote support', exact: true })).toBeEnabled();
  expect(app.writes).toEqual([{ action: 'stop' }, { action: 'stop' }]);
});

test('alert management opens under Manage and legacy links keep the selected topic', async ({ page }) => {
  const app = await openAdmin(page, null, [], { view: 'alerts' });
  await expect(page.getByRole('heading', { level: 1, name: 'Alerts', exact: true })).toBeVisible();
  await expect(page.locator('.primary-nav [data-nav-group="configuration"] a[data-view="alerts"]'))
    .toHaveAttribute('href', '/?view=alerts');
  await expect(page.getByRole('navigation', { name: 'Topics', exact: true })
    .getByRole('link', { name: /Fire activity/ })).toHaveAttribute('aria-current', 'true');
  for (const tab of ['alert-topics', 'alert-rules']) {
    await page.goto(`/app.html?view=admin&tab=${tab}&topic=administrator-alerts`);
    await expect(page).toHaveURL(/\?view=alerts&topic=administrator-alerts$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Alerts', exact: true })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Topics', exact: true })
      .getByRole('link', { name: /Administrator alerts/ })).toHaveAttribute('aria-current', 'true');
  }
  expect(app.requests).toContain('/api/v1/admin/alerts/topics');
});

test('alert management requires an administrator with settings access', async ({ page }) => {
  for (const [tier, denied] of [['user', []], ['admin', ['admin-settings']]]) {
    const app = await openAdmin(page, null, denied, { view: 'alerts', tier });
    await expect(page.locator('body')).toHaveAttribute('data-view', 'access-denied');
    await expect(page.locator('.access-denied-card')).toBeVisible();
    expect(app.requests).not.toContain('/api/v1/admin/alerts/topics');
  }
});

test('listener Topics follows the saved push setting while delivery is unavailable', async ({ page }) => {
  for (const tier of ['admin', 'user']) {
    await openAdmin(page, 'audio-quality', [], { tier, pushEnabled: false, pushReady: false });
    await expect(page.locator('.primary-nav a[data-view="topics"]')).toHaveAttribute('hidden', '');
    await openAdmin(page, 'audio-quality', [], { tier, pushEnabled: true, pushReady: false });
    await expect(page.locator('.primary-nav a[data-view="topics"]')).not.toHaveAttribute('hidden', '');
  }
});

test('audio quality retains MP3 choices, draft fields, and revision saves without enhancement controls', async ({ page }) => {
  const app = await openAdmin(page);
  await expect.poll(() => preferenceIds(page))
    .toEqual(['mp3_setting', 'mp3_input_audio_format', 'mp3_normalize_audio']);
  await expect(page.getByText('Continuous automatic gain', { exact: true })).toHaveCount(0);
  await expect(page.locator('[data-voice-enhancement]')).toHaveCount(0);
  const quality = page.locator('form[data-preference="mp3_setting"]');
  const sampleRate = page.locator('form[data-preference="mp3_input_audio_format"]');
  const normalize = page.locator('form[data-preference="mp3_normalize_audio"]');
  await expect(quality.locator('option')).toHaveText(['16 kbps', 'Variable']);
  await expect(sampleRate.locator('option')).toHaveText(['8 kHz', '16 kHz']);
  await normalize.locator('.ui-toggle').click();
  await quality.locator('select').selectOption('VBR_5');
  await quality.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(sampleRate.locator('option')).toHaveText(['16 kHz', '44.1 kHz']);
  await expect(normalize.getByText('Unsaved changes', { exact: true })).toBeVisible();
  await expect(normalize.locator('input')).toBeEnabled();
  await normalize.getByRole('button', { name: 'Save', exact: true }).click();
  expect(app.writes).toEqual([
    { field: 'mp3_setting', value: 'VBR_5', revision: `"${'a'.repeat(64)}"` },
    { field: 'mp3_normalize_audio', value: true, revision: `"${'b'.repeat(64)}"` }
  ]);
  await expect(normalize.getByRole('button', { name: 'Save', exact: true })).toBeHidden();
  await expect(page.locator('.operational-preferences .admin-form-message')).toHaveText('');
});

test('activity settings keep collection switches and server retention limits together', async ({ page }) => {
  const app = await openAdmin(page, 'activity');
  await expect(page.getByRole('heading', { level: 1, name: 'Activity settings' })).toBeVisible();
  await expect.poll(() => preferenceIds(page)).toEqual([
    'stats_logging_enabled', 'stats_detailed_history_enabled', 'stats_logging_retention_days'
  ]);
  const retention = page.locator('form[data-preference="stats_logging_retention_days"] input');
  await expect(retention).toHaveAttribute('min', '1');
  await expect(retention).toHaveAttribute('max', '365');
  await expect(retention).toHaveValue('30');
  const summary = page.locator('form[data-preference="stats_logging_enabled"]');
  const detail = page.locator('form[data-preference="stats_detailed_history_enabled"]');
  await expect(summary).toContainText('Main charts');
  await expect(detail).toContainText('Requires activity summaries');
  await retention.fill('60');
  const statusRequests = app.requests.filter((request) => request === '/api/v1/status').length;
  await summary.locator('.ui-toggle').click();
  await summary.getByRole('button', { name: 'Save', exact: true }).click();
  expect(app.writes[0]).toEqual({ field: 'stats_logging_enabled', value: false,
    revision: `"${'a'.repeat(64)}"` });
  await expect.poll(() => app.requests.filter((request) => request === '/api/v1/status').length)
    .toBeGreaterThan(statusRequests);
  await expect(page.locator('#receiver-health-saved-activity .ui-metric')
    .filter({ hasText: 'Activity summaries' })).toContainText('Off');
  await expect(retention).toHaveValue('60');
  await expect(page.locator('form[data-preference="stats_logging_retention_days"]'))
    .toContainText('Unsaved changes');
});

test('a focused settings page can retry its initial failed load', async ({ page }) => {
  const app = await openAdmin(page, 'audio-quality', [], { operationalErrorOnce: true });
  await expect(page.getByText('Settings temporarily unavailable. Reload saved settings and try again.'))
    .toBeVisible();
  const retry = page.getByRole('button', { name: 'Reload saved settings', exact: true });
  await expect(retry).toBeEnabled();
  await retry.click();
  await expect.poll(() => preferenceIds(page))
    .toEqual(['mp3_setting', 'mp3_input_audio_format', 'mp3_normalize_audio']);
  expect(app.requests.filter((request) => request === '/api/v1/admin/operational-preferences'))
    .toHaveLength(2);
  await expect(page.getByText('Settings temporarily unavailable. Reload saved settings and try again.'))
    .toHaveCount(0);
});

test('display combines the original timing and spectrum settings and preserves their independent data', async ({ page }) => {
  const app = await openAdmin(page, 'display', [], { spectrumDocument: {
    countries: [{ code: 'US', label: 'United States' }, { code: 'CA', label: 'Canada' }]
  } });
  await expect(page.getByRole('heading', { level: 1, name: 'Display settings' })).toBeVisible();
  const timing = page.getByLabel('Mark a traffic row idle after (milliseconds)');
  await expect(timing).toHaveValue('2000');
  await expect(timing).toHaveAttribute('min', '100');
  await expect(timing).toHaveAttribute('max', '15000');
  const country = page.getByRole('combobox', { name: /^Country/ });
  await expect(country).toHaveValue('US');
  await expect(country.locator('option')).toHaveText(['United States', 'Canada']);
  await timing.fill('3750');
  await page.getByRole('button', { name: 'Save Live timing', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Save Live timing', exact: true })).toBeHidden();
  await expect(page.getByText('Live timing saved.', { exact: true })).toHaveCount(0);
  expect(app.writes).toEqual([{ field: 'receiver-settings',
    settings: { traffic_grant_age_out_milliseconds: 3750 }, revision: '"4"' }]);
  await expect(country).toHaveValue('US');
  await country.selectOption('CA');
  await page.getByRole('button', { name: 'Change audio player size', exact: true }).press('Home');
  await page.getByRole('button', { name: 'Save country', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Save country', exact: true })).toBeHidden();
  await expect(page.getByText('Country saved.', { exact: true })).toHaveCount(0);
  expect(app.writes[1]).toEqual({ field: 'spectrum-country',
    settings: { country_code: 'CA' }, revision: '"9"' });
  await expect(timing).toHaveValue('3750');
});

for (const legacy of ['live-timing', 'spectrum']) {
  test(`the ${legacy} bookmark resolves to display with both original settings`, async ({ page }) => {
    await openAdmin(page, legacy);
    await expect(page).toHaveURL(/view=admin&tab=display/);
    await expect(page.getByLabel('Mark a traffic row idle after (milliseconds)')).toHaveValue('2000');
    await expect(page.getByRole('combobox', { name: /^Country/ })).toHaveValue('US');
  });
}

test('page access preserves all nineteen backend policies and every tier option', async ({ page }) => {
  expect(accessPolicies).toHaveLength(19);
  await openAdmin(page, 'access');
  const controls = page.locator('.admin-settings-content')
    .getByRole('combobox', { name: /^Minimum access level for / });
  await expect(controls).toHaveCount(19);
  for (const policy of accessPolicies) {
    const select = page.getByRole('combobox', { name: `Minimum access level for ${policy.display_name}` });
    await expect(select).toHaveValue(policy.required_tier);
    expect(await select.locator('option').evaluateAll((options) => options.map((option) => option.value)))
      .toEqual(['PUBLIC', 'USER', 'ADMIN']);
    if (!policy.configurable || policy.id.startsWith('admin-')) await expect(select).toBeDisabled();
    else await expect(select).toBeEnabled();
  }
});

test('receiver status opens a focused healthy overview and supports keyboard tabs', async ({ page }) => {
  await openAdmin(page, 'health', [], { healthDocument: {
    resolved: [{ occurrence_id: 'past-critical', code: 'control-stopped', severity: 'critical',
      title: 'Control channel stopped decoding', scope: 'County Dispatch',
      resolved_at_ms: 1_780_000_002_000 }]
  } });
  const overview = page.getByRole('tab', { name: 'Overview', exact: true });
  const history = page.getByRole('tab', { name: 'Issue history', exact: true });
  const diagnostics = page.getByRole('tab', { name: 'Diagnostics', exact: true });
  await expect(overview).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('#receiver-health-panel-overview')).toBeVisible();
  await expect(page.locator('.receiver-health-overview')).toContainText('Receiver operating normally');
  await expect(page.locator('.receiver-health-incident')).toHaveCount(0);
  await expect(page.locator('.receiver-health-diagnostics')).toHaveCount(0);
  await expect(page.locator('#receiver-health-saved-activity')).toHaveCount(0);
  await expect(page.locator('[data-receiver-health-section="computer"]')).toHaveCount(0);
  await overview.focus();
  await overview.press('ArrowRight');
  await expect(history).toBeFocused();
  await expect(history).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('#receiver-health-panel-history')).toBeVisible();
  await expect(page.locator('#receiver-health-panel-overview')).toBeHidden();
  await expect(page.locator('summary .ui-pill')).toHaveText('Resolved');
  await history.press('End');
  await expect(diagnostics).toBeFocused();
  await expect(diagnostics).toHaveAttribute('aria-selected', 'true');
  await diagnostics.press('Home');
  await expect(overview).toBeFocused();
  await expect(overview).toHaveAttribute('aria-selected', 'true');
});

test('receiver status can retry a failed load and marks the retained snapshot as out of date', async ({ page }) => {
  const options = { healthError: true };
  await openAdmin(page, 'health', [], options);
  await expect(page.locator('#receiver-health-panel-overview')).toContainText(
    'Receiver status is unavailable right now. Select Refresh to try again.');
  await expect(page.locator('#receiver-health-indicator')).toHaveClass(/receiver-health-stale/);
  await expect(page.locator('.receiver-health-summary')).toHaveCount(0);
  options.healthError = false;
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.locator('.receiver-health-summary')).toContainText('Receiver operating normally');
  await page.getByRole('tab', { name: 'Diagnostics', exact: true }).click();
  options.healthError = true;
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByRole('tab', { name: 'Diagnostics', exact: true }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('#receiver-health-panel-diagnostics > .ui-notice')).toHaveText(
    'Live status is delayed. Showing the last update received.');
  await page.getByRole('tab', { name: 'Overview', exact: true }).click();
  await expect(page.locator('.receiver-health-summary')).toContainText('Receiver status out of date');
  await expect(page.locator('.receiver-health-summary')).not.toContainText('Receiver operating normally');
  options.healthError = false;
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.locator('.receiver-health-summary')).toContainText('Receiver operating normally');
});

for (const [theme, viewport] of [['light', { width: 1280, height: 900 }],
  ['dark', { width: 390, height: 844 }]]) {
  test(`receiver status retains the focused personal status icon issue picker in ${theme}`, async ({ page }, testInfo) => {
    expect(healthAlertIds).toHaveLength(8);
    await page.setViewportSize(viewport);
    const hidden = theme === 'dark';
    await openAdmin(page, 'health', [], { theme, disabledHealthCodes: hidden ? healthAlertIds : [],
      healthDocument: {
        summary: { severity: 'critical', active_count: 1, warning_count: 0, critical_count: 1 },
        active: [{ code: 'receiver-iq-drop', severity: 'critical', title: 'Radio data keeps being lost',
          scope: 'Main tuner', opened_at_ms: Date.now() - 10_000, last_seen_ms: Date.now() }]
      }
    });
    const indicator = page.locator('#receiver-health-indicator');
    await expect(indicator).toHaveClass(hidden ? /receiver-health-neutral/ : /receiver-health-critical/);
    await expect(indicator).not.toHaveClass(/receiver-health-healthy/);
    await expect(page.locator('.receiver-health-current-issues')).toContainText('Radio data keeps being lost');
    await page.locator('#receiver-health-alert-settings').click();
    const dialog = page.getByRole('dialog', { name: 'Status icon issues' });
    await expect(dialog).toBeVisible();
    const choices = dialog.locator('input[type="checkbox"]');
    await expect(choices).toHaveCount(8);
    expect(await choices.evaluateAll(inputs => inputs.map(input => input.dataset.alertCode))).toEqual(healthAlertIds);
    for (const choice of await choices.all()) await expect(choice).toBeChecked({ checked: !hidden });
    await expect(dialog).not.toContainText(/ingress|channelizer|frames|transfers|chunks/i);
    await expect(dialog.getByRole('button', { name: 'Save choices', exact: true })).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath(`health-picker-${theme}.png`) });
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(page.locator('#receiver-health-alert-settings')).toBeFocused();
  });
}

test('receiver status preserves issue evidence, cleared history paging, and detailed measurements', async ({ page }) => {
  const incident = { code: 'receiver-iq-drop', severity: 'warning', title: 'Radio data was lost',
    scope: 'Primary tuner', opened_at_ms: 1_780_000_000_000, last_seen_ms: 1_780_000_001_000,
    observed: '32 samples were dropped.', likely_cause: 'Transfer delivery slowed down.',
    impact: 'Call audio may have gaps.', check_next: 'Check the USB connection.' };
  await openAdmin(page, 'health', [], { healthDocument: {
    summary: { severity: 'warning', active_count: 1, warning_count: 1, critical_count: 0 },
    active: [incident], resolved: Array.from({ length: 7 }, (_, index) => ({ ...incident,
      occurrence_id: `cleared-${index}`, scope: `Tuner ${index}`, title: `Cleared issue ${index}`,
      resolved_at_ms: 1_780_000_002_000 + index })),
    measurements: [{ id: 'receiver', title: 'Radio data measurements', rows: [{
      severity: 'warning', scope: 'Primary tuner', label: 'Lost radio samples', value: 32,
      unit: 'samples', detail: 'Observed during the last sample.'
    }] }]
  } });
  const current = page.locator('.receiver-health-incident').filter({ hasText: 'Radio data was lost' });
  await expect(current).toContainText('32 samples were dropped.');
  await expect(current).toContainText('Transfer delivery slowed down.');
  await expect(current).toContainText('Call audio may have gaps.');
  await expect(current).toContainText('Check the USB connection.');
  const [issuesBounds, resourcesBounds] = await Promise.all([
    page.locator('.receiver-health-current-issues').boundingBox(),
    page.locator('.receiver-health-resources').boundingBox()
  ]);
  expect(issuesBounds.y + issuesBounds.height).toBeLessThanOrEqual(resourcesBounds.y);
  await expect(page.getByRole('tab', { name: 'Overview', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('.receiver-health-diagnostics')).toHaveCount(0);
  await page.getByRole('tab', { name: 'Issue history', exact: true }).click();
  const pager = page.getByRole('navigation', { name: 'Issue history' });
  await expect(pager).toContainText('Page 1 of 2');
  await pager.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(pager).toContainText('Page 2 of 2');
  const resolved = page.locator('details.receiver-health-incident').first();
  await resolved.locator('summary').click();
  await expect(resolved).toContainText('What happened');
  await expect(resolved).toContainText('Original severity');
  await expect(resolved).toContainText('Check soon');
  await expect(resolved).toContainText('32 samples were dropped.');
  await expect(resolved.locator('summary .ui-pill')).toHaveText('Resolved');
  await expect(resolved.locator('summary .ui-pill')).not.toHaveClass(/ui-pill-(warning|danger)/);
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByRole('tab', { name: 'Issue history', exact: true }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(pager).toContainText('Page 2 of 2');
  await expect(resolved).toHaveAttribute('open', '');
  await page.getByRole('tab', { name: 'Diagnostics', exact: true }).click();
  const measurements = page.locator('[data-receiver-health-focus="section:measurement:receiver"]');
  await expect(measurements).toHaveAttribute('aria-expanded', 'false');
  await measurements.click();
  await expect(page.locator('.receiver-health-measurement-row')).toContainText('Primary tuner');
  await expect(page.locator('.receiver-health-measurement-row')).toContainText('Lost radio samples');
  await expect(page.locator('.receiver-health-measurement-row')).toContainText('32');
  await expect(page.locator('.receiver-health-measurement-row')).toContainText('Observed during the last sample.');
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByRole('tab', { name: 'Diagnostics', exact: true }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(measurements).toHaveAttribute('aria-expanded', 'true');
  await page.getByRole('tab', { name: 'Issue history', exact: true }).click();
  await expect(pager).toContainText('Page 2 of 2');
});

test('receiver status groups repeated history while preserving each occurrence and severity', async ({ page }) => {
  const base = { code: 'control-stopped', configuration_id: 'county', severity: 'critical',
    title: 'Control channel stopped decoding', scope: 'County Dispatch',
    opened_at_ms: 1_780_000_000_000, last_seen_ms: 1_780_000_001_000,
    observed: 'Current occurrence evidence', likely_cause: 'Signal changed.',
    impact: 'New calls may be missed.', check_next: 'Check channel signal.' };
  await openAdmin(page, 'health', [], { healthDocument: { resolved: [
    { ...base, occurrence_id: 'older', severity: 'warning', scope: 'Previous channel name',
      observed: 'Earlier occurrence evidence', resolved_at_ms: 1_780_000_002_000 },
    { ...base, occurrence_id: 'newer', resolved_at_ms: 1_780_000_003_000 },
    { ...base, occurrence_id: 'other', configuration_id: 'north', scope: 'North Dispatch',
      resolved_at_ms: 1_780_000_001_500 }
  ] } });
  await page.getByRole('tab', { name: 'Issue history', exact: true }).click();
  const groups = page.locator('#receiver-health-panel-history .receiver-health-incident-list')
    .first().locator(':scope > details');
  await expect(groups).toHaveCount(2);
  const group = groups.first();
  await expect(group.locator(':scope > summary')).toContainText('2 occurrences');
  await expect(group.locator(':scope > summary .ui-pill')).toHaveText('Resolved');
  await group.locator(':scope > summary').click();
  const occurrences = group.locator('.receiver-health-incident-body > .receiver-health-incident-list > details');
  await expect(occurrences).toHaveCount(2);
  await occurrences.first().locator(':scope > summary').click();
  await expect(occurrences.first()).toContainText('Current occurrence evidence');
  await expect(occurrences.first()).toContainText('Original severityAction needed');
  await occurrences.last().locator(':scope > summary').click();
  await expect(occurrences.last()).toContainText('Earlier occurrence evidence');
  await expect(occurrences.last()).toContainText('Original severityCheck soon');
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(group).toHaveAttribute('open', '');
  await expect(occurrences.first()).toHaveAttribute('open', '');
  await expect(occurrences.last()).toHaveAttribute('open', '');
});

for (const [theme, width] of [['light', 1440], ['dark', 1440], ['light', 320], ['dark', 320]]) {
  test(`receiver status keeps compact summaries and complete diagnostics at ${width}px in ${theme}`,
    async ({ page }, testInfo) => {
      await page.setViewportSize({ width, height: 900 });
      const resourceRows = [
        { label: 'VCE processor use', value: 10.3, unit: '%', detail: '' },
        { label: 'VCE memory use', value: 15, unit: '%', detail: 'Used 307 MB of 2.0 GB' },
        { label: 'Time spent freeing memory', value: 13, unit: 'ms in last sample',
          detail: 'Total since startup 25925 ms' },
        { label: 'Free storage space', value: 30.2, unit: '%', detail: 'Free 69 GB of 228 GB' }
      ].map((row) => ({ ...row, severity: 'healthy', scope: 'Computer' }));
      const row = { severity: 'healthy', scope: 'County Dispatch · Primary site',
        label: 'Received radio samples', display_label: 'Radio samples received', value: 2048, unit: 'samples',
        detail: 'Measurements remain available for the configured receiver.' };
      await openAdmin(page, 'health', [], { theme, healthDocument: {
        measurements: [
          { id: 'tuners', title: 'Tuners', rows: [row] },
          { id: 'usb', title: 'USB tuner connection', rows: [{ ...row, severity: 'warning' }] },
          { id: 'control', title: 'Control channel', rows: [{ ...row, severity: 'critical' }] },
          { id: 'host', title: 'Computer resources', rows: resourceRows }
        ]
      } });
      const resources = page.locator('.receiver-health-resource-bars .ui-metric');
      await expect(resources).toHaveCount(3);
      for (const card of await resources.all()) {
        await expect.poll(() => card.evaluate((element) => {
          const content = element.querySelector('.ui-metric-copy');
          return content.getBoundingClientRect().top - element.getBoundingClientRect().top -
            parseFloat(getComputedStyle(element).paddingTop) - parseFloat(getComputedStyle(element).borderTopWidth);
        }).then(Math.abs)).toBeLessThan(1);
      }
      await expect(resources.nth(2)).toContainText('Free storage');
      await expect(page.getByRole('progressbar', { name: 'Time spent freeing memory', exact: true }))
        .toHaveCount(0);
      await expect(page.locator('#receiver-health-saved-activity')).toHaveCount(0);
      if (width > 720) {
        const cards = await resources.evaluateAll((items) => items.map((item) => {
          const bounds = item.getBoundingClientRect();
          return { left: bounds.left, right: bounds.right };
        }));
        expect(cards[1].left - cards[0].right).toBeGreaterThanOrEqual(6);
      }
      await page.getByRole('tab', { name: 'Diagnostics', exact: true }).click();
      await expect(page.locator('#receiver-health-saved-activity .ui-metric strong'))
        .toHaveText(['On', 'On', '1.0 MB']);
      const diagnostics = page.locator('.receiver-health-diagnostics-grid');
      const tuner = diagnostics.locator('[data-receiver-health-section="measurement:tuners"]');
      const usb = diagnostics.locator('[data-receiver-health-section="measurement:usb"]');
      const control = diagnostics.locator('[data-receiver-health-section="measurement:control"]');
      await expect(tuner).toContainText('1 measurement');
      await expect(usb.locator('.ui-disclosure-toggle .ui-status')).toHaveText('Check soon');
      await expect(control.locator('.ui-disclosure-toggle .ui-status')).toHaveText('Action needed');
      await expect(control.locator('.receiver-health-section-body')).toBeHidden();
      for (const card of await diagnostics.locator('.receiver-health-section').all()) {
        const inset = await card.evaluate((element) => {
          const panel = element.getBoundingClientRect();
          const copy = element.querySelector('.ui-disclosure-copy').getBoundingClientRect();
          return { top: copy.top - panel.top, bottom: panel.bottom - copy.bottom };
        });
        expect(inset.bottom).toBeGreaterThanOrEqual(12);
        expect(Math.abs(inset.top - inset.bottom)).toBeLessThan(1);
      }
      if (width > 720) {
        const [left, right] = await Promise.all([tuner.boundingBox(), usb.boundingBox()]);
        expect(right.x).toBeGreaterThan(left.x + left.width);
        expect(Math.abs(right.y - left.y)).toBeLessThan(2);
      }
      const toggle = control.locator('.ui-disclosure-toggle');
      await toggle.focus();
      await page.keyboard.press('Enter');
      const measurement = control.locator('.receiver-health-measurement-row');
      await expect(measurement).toBeVisible();
      for (const text of [row.scope, row.label, String(row.value), row.unit, row.detail]) {
        await expect(measurement).toContainText(text);
      }
      const reading = measurement.locator('.receiver-health-measurement-value');
      const line = await reading.evaluate((element) => {
        const value = element.querySelector('strong').firstChild;
        const range = document.createRange();
        range.selectNodeContents(value);
        return { value: range.getBoundingClientRect().top,
          unit: element.querySelector('.ui-metric-unit').getBoundingClientRect().top };
      });
      expect(Math.abs(line.value - line.unit)).toBeLessThan(4);
      const identity = measurement.getByText('Radio identity', { exact: true });
      const [identityBounds, titleBounds] = await Promise.all([
        identity.boundingBox(), measurement.locator('.receiver-health-measurement-label').boundingBox()
      ]);
      expect(Math.abs(identityBounds.x - titleBounds.x)).toBeLessThan(1);
      await identity.click();
      await expect(measurement).toContainText(row.label);
      const [panel, grid] = await Promise.all([control.boundingBox(), diagnostics.boundingBox()]);
      expect(Math.abs(panel.width - grid.width)).toBeLessThan(2);
      await page.getByRole('button', { name: 'Refresh', exact: true }).click();
      await expect(toggle).toHaveAttribute('aria-expanded', 'true');
      expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth))
        .toBeLessThanOrEqual(1);
      await page.getByRole('button', { name: 'Change audio player size', exact: true }).press('Home');
      await page.screenshot({ path: testInfo.outputPath('receiver-status.png'), fullPage: true });
    });
}

test('new pages honor the original settings and recordings capabilities', async ({ page }) => {
  const app = await openAdmin(page, 'audio-quality', ['admin-settings', 'admin-recordings']);
  const navigation = page.getByRole('navigation', { name: 'Administration sections' });
  await expect(navigation.getByRole('link', { name: 'Audio quality', exact: true })).toHaveCount(0);
  await expect(navigation.getByRole('link', { name: 'Activity settings', exact: true })).toHaveCount(0);
  await expect(navigation.getByRole('link', { name: 'Display settings', exact: true })).toHaveCount(0);
  await expect(navigation.getByRole('link', { name: 'Recording settings', exact: true })).toHaveCount(0);
  await expect(navigation.getByRole('link', { name: 'Transcription', exact: true })).toHaveCount(0);
  expect(app.requests).not.toContain('/api/v1/admin/operational-preferences');
  expect(app.requests).not.toContain('/api/v1/admin/recordings');
});

test('Manage Streaming owns patch-group routing and retains live destination status', async ({ page }) => {
  const app = await openAdmin(page, null, [], { view: 'streaming' });
  await expect(page.getByRole('heading', { level: 1, name: 'Streaming', exact: true })).toBeVisible();
  const routing = page.locator('form[data-preference="patch_group_streaming_option"]');
  await expect(routing.locator('select')).toHaveValue('PATCH_GROUP');
  await expect(routing.locator('option')).toHaveText(['Patch Group', 'Individual Talkgroups']);
  await expect(page.locator('.streaming-destination-card')).toContainText('County Calls');
  await expect(page.locator('.streaming-destination-card')).toContainText('Connected');
  await expect(page.locator('.streaming-destination-card')).toContainText('128');
  await expect(page.getByRole('button', { name: 'Add destination', exact: true })).toBeVisible();
  await routing.locator('select').selectOption('TALKGROUPS');
  await routing.getByRole('button', { name: 'Save', exact: true }).click();
  expect(app.writes).toEqual([{ field: 'patch_group_streaming_option', value: 'TALKGROUPS',
    revision: `"${'a'.repeat(64)}"` }]);
  await expect(page.locator('.streaming-destination-card')).toContainText('Connected');
  await expect(page.locator('form[data-preference]')).toHaveCount(1);
});

test('streaming management does not broaden receiver settings access', async ({ page }) => {
  const app = await openAdmin(page, null, ['admin-settings'], { view: 'streaming' });
  await expect(page.locator('.streaming-destination-card')).toContainText('County Calls');
  await expect(page.locator('form[data-preference="patch_group_streaming_option"]')).toHaveCount(0);
  expect(app.requests).not.toContain('/api/v1/admin/operational-preferences');
});

test('P25 inventory retains every original identity and band field in the focused editor', async ({ page }) => {
  const app = await openAdmin(page, 'protocol-p25');
  const inventory = page.locator('.p25-overrides-workspace .p25-override-profile-list');
  await expect(page.locator('.p25-overrides-workspace table')).toHaveCount(0);
  await expect(inventory.locator('.p25-override-record')).toHaveCount(1);
  await expect(inventory).toContainText('BEE00-49F');
  await expect(inventory).toContainText('RFSS 01 · Site 02');
  await expect(inventory.locator('.p25-override-profile-facts dt'))
    .toHaveText(['WACN', 'System ID', 'RFSS', 'Site ID']);
  await expect(inventory.locator('.p25-override-profile-facts dd'))
    .toHaveText(['BEE00', '49F', '01', '02']);
  const firstBand = inventory.locator('.p25-override-band-record').first();
  await expect(firstBand).toContainText('Band 0');
  await expect(firstBand).toContainText('FDMA');
  await expect(firstBand.locator('dt')).toHaveText(['Base frequency', 'Bandwidth', 'Spacing', 'Offset']);
  await expect(firstBand.locator('dd'))
    .toHaveText(['851.006250 MHz', '12.500 kHz', '12.500 kHz', '-45.000000 MHz']);
  await expect(inventory.locator('.p25-override-band-record').last()).toContainText('P25 2-slot TDMA');
  await page.getByRole('button', { name: 'Edit P25 override BEE00-49F', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Edit P25 override · BEE00-49F' });
  await expect(dialog.getByLabel('WACN (hex)', { exact: true })).toHaveValue('BEE00');
  await expect(dialog.getByLabel('System ID (hex)', { exact: true })).toHaveValue('49F');
  const rfss = dialog.getByLabel('RFSS (hex, optional)', { exact: true });
  await expect(rfss).toHaveValue('01');
  await expect(dialog.getByLabel('Site ID (hex, optional)', { exact: true })).toHaveValue('02');
  await expect(dialog.locator('.p25-override-band-row')).toHaveCount(2);
  const band = dialog.locator('.p25-override-band-row').first();
  const bandId = band.getByLabel('Band ID', { exact: true });
  await expect(bandId).toHaveValue('0');
  await expect(bandId).toHaveAttribute('min', '0');
  await expect(bandId).toHaveAttribute('max', '15');
  await expect(band.getByRole('combobox', { name: 'Type', exact: true }).locator('option'))
    .toHaveText(['FDMA', 'P25 2-slot TDMA']);
  await expect(band.getByLabel('Base frequency (MHz)', { exact: true })).toHaveValue('851.00625');
  await expect(band.getByLabel('Bandwidth (kHz)', { exact: true })).toHaveValue('12.5');
  await expect(band.getByLabel('Spacing (kHz)', { exact: true })).toHaveValue('12.5');
  const offset = band.getByLabel('Offset (MHz)', { exact: true });
  await expect(offset).toHaveValue('-45');
  await rfss.fill('');
  await dialog.getByRole('button', { name: 'Apply changes', exact: true }).click();
  await expect(dialog).toContainText('Enter both RFSS and Site ID, or leave both blank.');
  await rfss.fill('01');
  await offset.fill('-30');
  await dialog.getByRole('button', { name: 'Add band', exact: true }).click();
  await expect(dialog.locator('.p25-override-band-row')).toHaveCount(3);
  await dialog.locator('.p25-override-band-row').last().getByRole('button', { name: 'Remove band' }).click();
  await expect(dialog.locator('.p25-override-band-row')).toHaveCount(2);
  await dialog.getByRole('button', { name: 'Apply changes', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('button', { name: 'Edit P25 override BEE00-49F', exact: true })).toBeFocused();
  await expect(page.locator('.admin-settings-content').getByRole('button', { name: 'Choose table columns' }))
    .toHaveCount(0);
  await expect(page.getByText('Unsaved changes', { exact: true })).toBeVisible();
  expect(app.writes).toEqual([]);
  await page.getByRole('button', { name: 'Save overrides', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Save overrides', exact: true })).toBeHidden();
  await expect(page.getByText('Overrides saved.', { exact: true })).toHaveCount(0);
  await expect(page.locator('.admin-settings-content').getByRole('button', { name: 'Choose table columns' }))
    .toHaveCount(0);
  expect(app.writes).toHaveLength(1);
  expect(app.writes[0].profiles[0]).toEqual({ wacn: 0xBEE00, system: 0x49F, rfss: 1, site: 2,
    bands: [{ identifier: 0, type: 'FDMA', base_frequency: 851_006_250, bandwidth: 12_500,
      channel_spacing: 12_500, transmit_offset: -30_000_000 },
    { identifier: 1, type: 'TDMA', base_frequency: 851_000_000, bandwidth: 12_500,
      channel_spacing: 12_500, transmit_offset: -45_000_000 }] });
});

test('P25 removal confirms the selected profile and remains a draft until Save overrides', async ({ page }) => {
  const app = await openAdmin(page, 'protocol-p25');
  await page.getByRole('button', { name: 'Delete P25 override BEE00-49F', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Delete P25 override', exact: true });
  await expect(dialog).toContainText('BEE00-49F');
  await expect(dialog).toContainText('remains a draft until you save overrides');
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(page.locator('.p25-overrides-workspace .p25-override-record')).toContainText('BEE00-49F');
  await page.getByRole('button', { name: 'Delete P25 override BEE00-49F', exact: true }).click();
  await dialog.getByRole('button', { name: 'Delete override', exact: true }).click();
  await expect(page.getByText('No P25 overrides configured.', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Add P25 override', exact: true })).toBeFocused();
  expect(app.writes).toEqual([]);
  await page.getByRole('button', { name: 'Save overrides', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Save overrides', exact: true })).toBeHidden();
  await expect(page.getByText('Overrides saved.', { exact: true })).toHaveCount(0);
  expect(app.writes).toEqual([{ field: 'p25-bandplan-overrides', profiles: [] }]);
});

test('P25 system-wide records retain zero values and the maximum Band ID', async ({ page }) => {
  const app = await openAdmin(page, 'protocol-p25');
  await page.getByRole('button', { name: 'Add P25 override', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Add P25 override', exact: true });
  await dialog.getByLabel('WACN (hex)', { exact: true }).fill('abcde');
  await dialog.getByLabel('System ID (hex)', { exact: true }).fill('012');
  const band = dialog.locator('.p25-override-band-row');
  await band.getByLabel('Band ID', { exact: true }).fill('15');
  await band.getByRole('combobox', { name: 'Type', exact: true }).selectOption('TDMA');
  await band.getByLabel('Base frequency (MHz)', { exact: true }).fill('0');
  await band.getByLabel('Bandwidth (kHz)', { exact: true }).fill('6.25');
  await band.getByLabel('Spacing (kHz)', { exact: true }).fill('6.25');
  await band.getByLabel('Offset (MHz)', { exact: true }).fill('0');
  await dialog.getByRole('button', { name: 'Add override', exact: true }).click();
  const record = page.locator('.p25-override-record').last();
  await expect(record).toContainText('ABCDE-012');
  await expect(record).toContainText('Entire system');
  await expect(record.locator('.p25-override-profile-facts dd')).toHaveText(['ABCDE', '012', 'All', 'All']);
  await expect(record).toContainText('Band 15');
  await expect(record).toContainText('P25 2-slot TDMA');
  await expect(record.locator('.p25-override-band-facts dd'))
    .toHaveText(['0.000000 MHz', '6.250 kHz', '6.250 kHz', '0.000000 MHz']);
  expect(app.writes).toEqual([]);
  await page.getByRole('button', { name: 'Save overrides', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Save overrides', exact: true })).toBeHidden();
  await expect(page.getByText('Overrides saved.', { exact: true })).toHaveCount(0);
  expect(app.writes[0].profiles[1]).toEqual({ wacn: 0xABCDE, system: 0x012, rfss: null, site: null,
    bands: [{ identifier: 15, type: 'TDMA', base_frequency: 0, bandwidth: 6250,
      channel_spacing: 6250, transmit_offset: 0 }] });
});

test('P25 editor keeps unsaved fields when discarding is declined', async ({ page }) => {
  const app = await openAdmin(page, 'protocol-p25');
  await page.getByRole('button', { name: 'Edit P25 override BEE00-49F', exact: true }).click();
  const editor = page.getByRole('dialog', { name: 'Edit P25 override · BEE00-49F', exact: true });
  await editor.getByLabel('Offset (MHz)', { exact: true }).first().fill('-30');
  await editor.getByRole('button', { name: 'Cancel', exact: true }).click();
  const confirmation = page.getByRole('alertdialog', { name: 'Discard unsaved changes' });
  await expect(confirmation).toContainText('Discard your unsaved changes?');
  await confirmation.getByRole('button', { name: 'Keep editing' }).click();
  await expect(editor).toBeVisible();
  await expect(editor.getByLabel('Offset (MHz)', { exact: true }).first()).toHaveValue('-30');
  await page.keyboard.press('Escape');
  await confirmation.getByRole('button', { name: 'Discard changes' }).click();
  await expect(editor).toBeHidden();
  await expect(page.getByRole('button', { name: 'Edit P25 override BEE00-49F', exact: true })).toBeFocused();
  await expect(page.locator('.p25-override-band-facts').first()).toContainText('-45.000000 MHz');
  expect(app.writes).toEqual([]);
});

test('P25 records and complete editor fit a 320 px mobile viewport', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 760 });
  await openAdmin(page, 'protocol-p25');
  await expect(page.locator('.p25-override-record')).toBeVisible();
  expect(await page.locator('.p25-overrides-workspace').evaluate((inventory) =>
    inventory.scrollWidth <= inventory.clientWidth && inventory.getBoundingClientRect().right <= innerWidth)).toBe(true);
  await page.getByRole('button', { name: 'Edit P25 override BEE00-49F', exact: true }).click();
  const editor = page.getByRole('dialog', { name: 'Edit P25 override · BEE00-49F', exact: true });
  await expect(editor).toBeVisible();
  await editor.getByRole('button', { name: 'Add band', exact: true }).scrollIntoViewIfNeeded();
  await expect(editor.getByRole('button', { name: 'Add band', exact: true })).toBeVisible();
  expect(await editor.evaluate((dialog) => dialog.scrollWidth <= dialog.clientWidth)).toBe(true);
  expect(await editor.evaluate((dialog) => dialog.getBoundingClientRect().right <= innerWidth)).toBe(true);
});

for (const [theme, viewport, size] of [
  ['light', { width: 1440, height: 1000 }, 'desktop'],
  ['dark', { width: 1440, height: 1000 }, 'desktop'],
  ['light', { width: 390, height: 844 }, 'mobile'],
  ['dark', { width: 390, height: 844 }, 'mobile']
]) {
  for (const [tab, title, field] of [
    ['audio-quality', 'Audio quality', 'mp3_setting'],
    ['activity', 'Activity settings', 'stats_logging_retention_days'],
    ['display', 'Display settings', null]
  ]) {
    test(`focused ${tab} layout ${theme} ${size}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await page.clock.setFixedTime(new Date('2026-09-30T18:30:00Z'));
      await openAdmin(page, tab, [], { theme });
      await expect(page.getByRole('heading', { level: 1, name: title, exact: true })).toBeVisible();
      if (field) await expect(page.locator(`.admin-settings-content form[data-preference="${field}"]`))
        .toBeVisible();
      else await expect(page.getByRole('combobox', { name: /^Country/ })).toHaveValue('US');
      if (tab === 'audio-quality') {
        await expect(page.locator('[data-paging-tone-detection] input')).toBeEnabled();
        await expect(page.locator('[data-paging-tone-detection] [role=alert]')).toBeHidden();
      }
      await page.evaluate(() => window.scrollTo(0, 0));
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      await expect(page.locator('body')).toHaveScreenshot(`admin-${tab}-${theme}-${size}.png`, { fullPage: true });
    });
  }
  test(`P25 records preserve their responsive layout ${theme} ${size}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openAdmin(page, 'protocol-p25', [], { theme, p25Document: { profiles: [
      { wacn: 0xBEE00, system: 0x49F, rfss: 1, site: 2, bands: [
        { identifier: 0, type: 'FDMA', base_frequency: 851_006_250, bandwidth: 12_500,
          channel_spacing: 12_500, transmit_offset: -45_000_000 },
        { identifier: 1, type: 'TDMA', base_frequency: 851_000_000, bandwidth: 12_500,
          channel_spacing: 12_500, transmit_offset: -45_000_000 }
      ] },
      { wacn: 0xABCDE, system: 0x012, rfss: null, site: null, bands: [
        { identifier: 2, type: 'FDMA', base_frequency: 450_000_000, bandwidth: 12_500,
          channel_spacing: 6_250, transmit_offset: 5_000_000 }
      ] }
    ] } });
    const inventory = page.locator('.p25-overrides-workspace');
    const records = inventory.locator('.p25-override-record');
    await expect(records).toHaveCount(2);
    await expect(inventory.locator('.p25-override-band-record')).toHaveCount(3);
    await expect(inventory.locator('table')).toHaveCount(0);
    const appearance = await records.evaluateAll((items) => items.map((record) => {
      const bounds = record.getBoundingClientRect();
      const band = record.querySelector('.p25-override-band-record');
      return { top: bounds.top, bottom: bounds.bottom,
        fill: getComputedStyle(record).backgroundColor,
        pageFill: getComputedStyle(record.closest('.ui-section')).backgroundColor,
        bandFill: getComputedStyle(band).backgroundColor,
        border: getComputedStyle(record).borderTopWidth };
    }));
    for (const record of appearance) {
      expect(record.fill).not.toBe(record.pageFill);
      expect(record.bandFill).not.toBe(record.fill);
      expect(record.border).toBe('0px');
    }
    expect(appearance[1].top - appearance[0].bottom).toBeGreaterThanOrEqual(12);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await expect(page.locator('body')).toHaveScreenshot(`admin-p25-records-${theme}-${size}.png`, { fullPage: true });
  });
  test(`P25 editor preserves its complete responsive layout ${theme} ${size}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openAdmin(page, 'protocol-p25', [], { theme });
    await page.getByRole('button', { name: 'Edit P25 override BEE00-49F', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Edit P25 override · BEE00-49F' });
    await expect(dialog).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await expect(dialog).toHaveScreenshot(`admin-p25-editor-${theme}-${size}.png`);
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(page.getByRole('button', { name: 'Edit P25 override BEE00-49F', exact: true })).toBeFocused();
  });
}


for (const clientOffset of [-300_000, 300_000]) {
  test(`receiver status uses server time with client clock offset ${clientOffset}`, async ({ page }) => {
    const serverTime = Date.now();
    await page.clock.install({ time: serverTime + clientOffset });
    const options = { healthDate: new Date(serverTime).toUTCString(),
      healthDocument: { generated_at_ms: serverTime } };
    await openAdmin(page, 'health', [], options);
    const indicator = page.locator('#receiver-health-indicator');
    await expect(indicator).toHaveClass(/receiver-health-healthy/);
    const refresh = page.getByRole('button', { name: 'Refresh', exact: true });
    options.healthDate = new Date(serverTime + 30_000).toUTCString();
    await refresh.click();
    await expect(indicator).toHaveClass(/receiver-health-stale/);
    await expect(page.locator('.receiver-health-summary')).toContainText('Receiver status out of date');
    options.healthDocument.generated_at_ms = serverTime + 30_000;
    await refresh.click();
    await expect(indicator).toHaveClass(/receiver-health-healthy/);
    const headerTime = Date.parse(options.healthDate);
    options.healthDocument.generated_at_ms = headerTime + 1001;
    await refresh.click();
    await expect(indicator).toHaveClass(/receiver-health-stale/);
    await expect(page.locator('.receiver-health-summary')).toContainText('Receiver status is unavailable right now.');
    options.healthDocument.generated_at_ms = headerTime + 1000;
    await refresh.click();
    await expect(indicator).toHaveClass(/receiver-health-healthy/);
    options.omitHealthDate = true;
    await refresh.click();
    await expect(indicator).toHaveClass(/receiver-health-stale/);
    await expect(page.locator('.receiver-health-summary')).toContainText('Receiver status is unavailable right now.');
  });
}
