const { expect, test } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');
const root = resolve(__dirname, '../../../../..');
const app = readFileSync(resolve(root, 'stats-web/assets/app.js'), 'utf8');
const protocols = require(resolve(root, 'src/main/resources/channel-protocols.json'));

function tuner() {
  return { id: 'idle-a', name: 'Test receiver', tuner_class: 'AIRSPY', status: 'ENABLED',
    available: true, operator_state: 'setup', channel_count: 0, frequency_hz: 851012500,
    spectrum_target_id: 'target-a', sample_rate_hz: 10000000,
    settings: [{ id: 'frequency_mhz', label: 'Center frequency', value: 851.0125,
      editable: true, availability: 'setup', minimum: 24, maximum: 1800,
      unavailable_reason: 'Unlock center', dependencies: [{ setting_id: 'center_frequency_locked', equals: false }] },
    { id: 'center_frequency_locked', label: 'Lock center', kind: 'boolean', value: false,
      editable: true, availability: 'live', dependencies: [] }] };
}

function snapshot(protocolId, state) {
  const profile = protocols.profiles.find((candidate) => candidate.id === protocolId);
  const settings = Object.fromEntries(profile.sections.flatMap((section) => section.fields)
    .filter((field) => field.path.startsWith('settings.') && Object.hasOwn(field, 'default'))
    .map((field) => [field.path.substring(9), field.default]));
  return { session_id: 'discovery-a', state: state.phase || 'ready', reason: state.reason || null,
    protocol_id: protocolId,
    tuner_id: 'idle-a', target_id: 'target-a', frequency_hz: state.frequencyHz || 851012500,
    expires_at_ms: Date.now() + 30000,
    probe: protocolId === 'p25-phase1' ? {
      c4fm: { valid_messages: 18, valid_control_messages: 12, invalid_control_messages: 4, quality_pct: 81.2 },
      cqpsk: { valid_messages: 52, valid_control_messages: 41, invalid_control_messages: 1, quality_pct: 99.1 },
      elapsed_ms: 1500, timeout_ms: 15000,
      selected_modulation: state.phase === 'identifying' ? null : 'CQPSK',
      identity: state.phase === 'identifying' ? null : { wacn: 0xb0001, system: 0x123, rfss: 1, site: 2 }
    } : null,
    review: state.phase === 'identifying' ? null : {
      revision: state.revision || 7, template: { protocol_id: protocolId, name: 'Control', system: 'P25 B0001-123',
        site: 'RFSS 1 Site 2', settings },
      alias_lists: state.ambiguous ? [{ id: 21, name: 'North aliases', matched: true },
        { id: 22, name: 'South aliases', matched: true }] :
        (state.singleMatch ? [{ id: 21, name: 'County aliases', matched: true }] : []),
      suggested_alias_list_id: null, default_new_alias_list_name: 'P25 B0001-123'
    },
    saved: state.saved ? { configuration_id: 'channel-a', alias_list_id: 41,
      running: state.running === true, start_error: state.running ? null : 'Tuner capacity is in use.' } : null
  };
}

async function install(page, state = {}) {
  state.tuner = tuner();
  state.tuner.settings.find((setting) => setting.id === 'center_frequency_locked').value = state.locked === true;
  state.requests = [];
  const preferenceModule = await import(pathToFileURL(resolve(root,
    'stats-web/assets/core/preference-schema.js')).href);
  await page.route('**/assets/app.js*', (route) => route.fulfill({ contentType: 'text/javascript',
    body: `${app}\nexport { openSpectrumDiscoveryWizard, openTunerFrequencyActions };` }));
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const body = JSON.parse(request.postData() || '{}');
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json',
      body: JSON.stringify({ data }) });
    state.requests.push({ path, method: request.method(), body });
    if (path === '/api/v1/auth/session') return respond({ configured: true, authenticated: true,
      tier: 'admin', username: 'admin', csrf_token: 'test-token', capabilities: {
        'web-access': true, dashboard: true, 'tuner-spectrum': true, 'admin-tuners': true,
        'admin-channels': true, 'admin-aliases': true
      } });
    if (path === '/api/v1/me/preferences') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, preferences: { ...preferenceModule.defaults,
        appearance: { theme: state.theme || 'light' } } }) });
    if (path === '/api/v1/spectrum-snap-presets') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, country_code: 'US', country_label: 'United States',
        countries: [{ code: 'US', label: 'United States' }], scopes: [] }) });
    if (path === '/api/v1/admin/tuners') return respond({ tuners: [state.tuner] });
    if (path.startsWith('/api/v1/admin/tuners/idle-a/settings/')) {
      const setting = state.tuner.settings.find((candidate) => candidate.id === path.split('/').at(-1));
      if (state.rejectLock && setting.id === 'center_frequency_locked') return route.fulfill({
        status: 409, contentType: 'application/json',
        body: JSON.stringify({ error: { code: 'busy', message: 'Could not change center lock.' } }) });
      setting.value = body.value;
      if (setting.id === 'frequency_mhz') state.tuner.frequency_hz = Math.round(body.value * 1_000_000);
      return respond({});
    }
    if (path.endsWith('/browse')) {
      if (request.method() === 'DELETE') return respond(null, 204);
      if (state.failBrowseOnce) {
        state.failBrowseOnce = false;
        state.browseError = true;
        return route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'busy', message: 'Tuner is temporarily unavailable.' } }) });
      }
      if (Number.isInteger(state.browseChannelCount)) state.tuner.channel_count = state.browseChannelCount;
      return respond({ lease_id: 'browse-a', expires_at_epoch_ms: Date.now() + 30000,
        can_tune: !state.running && state.tuner.channel_count === 0, tuner: state.tuner });
    }
    if (path === '/api/v1/diagnostics/tuners') return respond({ rows: [] });
    if (path === '/api/v1/admin/channels/protocols') return respond(protocols);
    if (path.endsWith('/eligibility')) return respond({ eligible: !state.known,
      reason: state.known ? 'This frequency belongs to County Control.' : null, matches: [] });
    if (path === '/api/v1/admin/spectrum-discovery' && request.method() === 'POST') {
      state.protocolId = body.protocol_id;
      state.frequencyHz = body.frequency_hz;
      return respond(snapshot(state.protocolId, state));
    }
    if (path === '/api/v1/admin/spectrum-discovery/discovery-a') {
      if (request.method() === 'DELETE') return respond(null, 204);
      if (state.failProbeStatusOnce) {
        state.failProbeStatusOnce = false;
        return route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'unavailable', message: 'Status temporarily unavailable.' } }) });
      }
      return respond(snapshot(state.protocolId, state));
    }
    if (path.endsWith('/save')) {
      if (state.staleOnce) {
        state.staleOnce = false;
        state.revision = 8;
        return route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'stale_revision', message: 'Configuration changed.' } }) });
      }
      state.saved = true;
      state.running = state.failStart !== true;
      return respond(snapshot(state.protocolId, state));
    }
    if (path.endsWith('/start')) {
      state.running = true;
      state.tuner.channel_count = 1;
      state.tuner.operator_state = 'live';
      return respond(snapshot(state.protocolId, state));
    }
    return respond({});
  });
  await page.goto('/app.html?view=tuner-spectrum');
  await expect(page.getByRole('heading', { name: 'Tuner Spectrum', exact: true })).toBeVisible();
  if (!state.browseError) await expect(page.locator('.spectrum-browse-center')).toContainText('0851.01250MHz');
  await page.evaluate(async () => {
    window.discoveryApi = await import('/assets/app.js?v=353');
    window.discoveryProbeStates = [];
    window.openDiscovery = () => window.discoveryApi.openSpectrumDiscoveryWizard({
      tunerId: 'idle-a', targetId: 'target-a', frequencyHz: 851012500, browseLeaseId: 'browse-a',
      setProbeActive: (active) => window.discoveryProbeStates.push(active)
    });
  });
}

const wizard = (page) => page.locator('.spectrum-discovery-modal');

async function begin(page, protocolId = 'p25-phase1') {
  await page.evaluate(() => window.openDiscovery());
  await wizard(page).getByRole('combobox', { name: 'Protocol', exact: true }).selectOption(protocolId);
  await wizard(page).getByRole('button', { name: 'Next', exact: true }).click();
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Spectrum center lock unlocks tuning with its lease in ${theme} at ${width}px`, async ({ page }) => {
    const state = { locked: true, theme };
    await page.setViewportSize({ width, height: 900 });
    await install(page, state);
    const lock = page.locator('.spectrum-browse-center').getByRole('checkbox', { name: 'Lock center' });
    const digit = page.getByRole('button', { name: 'Decrease 100 MHz place', includeHidden: true });
    await expect(lock).toBeChecked();
    await expect(lock).toBeEnabled();
    await expect(digit).toBeDisabled();
    await expect(page.locator('.spectrum-browse-toolbar')).toHaveScreenshot(`spectrum-center-lock-${theme}.png`);
    await lock.press('Space');
    await expect(digit).toBeEnabled();
    await expect(lock).toBeFocused();
    expect(state.requests.find((request) => request.path.endsWith('/settings/center_frequency_locked')).body)
      .toEqual({ value: false, lease_id: 'browse-a' });
    await digit.click();
    await expect(page.locator('.spectrum-browse-center')).toContainText('0751.01250MHz');
    expect(state.requests.find((request) => request.path.endsWith('/settings/frequency_mhz')).body)
      .toEqual({ value: 751.0125, lease_id: 'browse-a' });
    await lock.press('Space');
    await expect(digit).toBeDisabled();
  });
}

test('a rejected Spectrum center lock restores the confirmed switch and allows retry', async ({ page }) => {
  const state = { locked: true, rejectLock: true };
  await install(page, state);
  const lock = page.locator('.spectrum-browse-center').getByRole('checkbox', { name: 'Lock center' });
  await lock.press('Space');
  await expect(lock).toBeChecked();
  await expect(lock).toBeEnabled();
  await expect(page.locator('.spectrum-browse-message')).toContainText('Could not change center lock.');
  state.rejectLock = false;
  await lock.press('Space');
  await expect(lock).not.toBeChecked();
  await expect(page.getByRole('button', { name: 'Decrease 100 MHz place', includeHidden: true })).toBeEnabled();
});

test('active monitoring keeps the Spectrum center lock disabled', async ({ page }) => {
  await install(page, { locked: true, running: true });
  await expect(page.locator('.spectrum-browse-center').getByRole('checkbox', { name: 'Lock center' })).toBeDisabled();
});

test('confirmed lease activity updates the existing tuner option and preserves picker focus', async ({ page }) => {
  const state = { browseChannelCount: 1 };
  await page.clock.install();
  await install(page, state);
  const picker = page.locator('.spectrum-browse-tuner select');
  await expect(picker.locator('option:checked')).toHaveText('Test receiver · 1 active');
  await picker.focus();
  await page.evaluate(() => {
    window.originalSpectrumPicker = document.querySelector('.spectrum-browse-tuner select');
    window.originalSpectrumOption = window.originalSpectrumPicker.selectedOptions[0];
  });
  state.browseChannelCount = 0;
  await page.clock.fastForward(11000);
  await expect(picker.locator('option:checked')).toHaveText('Test receiver · Idle');
  await expect(picker).toBeFocused();
  state.browseChannelCount = 2;
  await page.clock.fastForward(11000);
  await expect(picker.locator('option:checked')).toHaveText('Test receiver · 2 active');
  await expect(picker).toBeFocused();
  expect(await picker.evaluate((element) => element === window.originalSpectrumPicker &&
    element.selectedOptions[0] === window.originalSpectrumOption)).toBe(true);
});

test('an unchanged lease renewal preserves a typed center frequency draft and its focused control', async ({ page }) => {
  const state = {};
  await page.clock.install();
  await install(page, state);
  const center = page.locator('.spectrum-browse-center .tuners-center-frequency');
  await center.focus();
  await page.locator('.spectrum-browse-center .tuners-frequency-digit').first().hover();
  await page.keyboard.type('075');
  await expect(center).toContainText('0751.01250MHz');
  await page.evaluate(() => {
    window.originalSpectrumCenter = document.querySelector('.spectrum-browse-center .tuners-center-frequency');
  });
  await page.clock.fastForward(11000);
  await expect.poll(() => state.requests.some((request) => request.path.endsWith('/browse') &&
    request.body.lease_id === 'browse-a')).toBe(true);
  await expect(center).toBeFocused();
  expect(await center.evaluate((element) => element === window.originalSpectrumCenter)).toBe(true);
  await page.keyboard.type('412345');
  await page.keyboard.press('Enter');
  await expect(center).toContainText('0754.12345MHz');
  expect(state.requests.find((request) => request.path.endsWith('/settings/frequency_mhz')).body)
    .toEqual({ value: 754.12345, lease_id: 'browse-a' });
});

test('idle receiver browsing renews and releases on leaving Spectrum', async ({ page }) => {
  const state = {};
  await page.clock.install();
  await install(page, state);
  await page.clock.fastForward(11000);
  await expect.poll(() => state.requests.filter((request) => request.path.endsWith('/browse') &&
    request.body.lease_id === 'browse-a').length).toBeGreaterThan(0);
  await page.evaluate(() => document.querySelector('a[data-view="dashboard"]').click());
  await expect.poll(() => state.requests.filter((request) => request.path.endsWith('/browse') &&
    request.method === 'DELETE').length).toBeGreaterThan(0);
});

test('a single tuner can retry failed browsing without changing selection', async ({ page }) => {
  const state = { failBrowseOnce: true };
  await install(page, state);
  await expect(page.getByRole('button', { name: 'Retry browsing' })).toBeVisible();
  await page.getByRole('button', { name: 'Retry browsing' }).click();
  await expect(page.locator('.spectrum-browse-center')).toContainText('0851.01250MHz');
  await expect(page.getByRole('button', { name: 'Retry browsing' })).toBeHidden();
  expect(state.requests.filter((request) => request.path.endsWith('/browse') && request.method === 'POST'))
    .toHaveLength(2);
});

test('P25 polling retains Cancel focus and cancellation releases the probe', async ({ page }) => {
  const state = { phase: 'identifying' };
  await install(page, state);
  await begin(page);
  const cancel = wizard(page).getByRole('button', { name: 'Cancel', exact: true });
  await expect(wizard(page)).toContainText('99% quality · 52 valid messages · 41 control · 1 invalid');
  await expect(wizard(page)).toContainText('81% quality · 18 valid messages · 12 control · 4 invalid');
  await expect(cancel).toBeEnabled();
  await cancel.focus();
  await expect(cancel).toBeFocused();
  await page.waitForTimeout(900);
  await expect(cancel).toBeFocused();
  await cancel.click();
  await expect(wizard(page)).toHaveCount(0);
  await expect.poll(() => state.requests.some((request) => request.path.endsWith('/discovery-a') &&
    request.method === 'DELETE')).toBe(true);
  expect(await page.evaluate(() => window.discoveryProbeStates)).toEqual([true, false]);
});

test('retrying inconclusive P25 identification restores Cancel while scanning and saves no channel', async ({ page }) => {
  const state = { phase: 'inconclusive' };
  await install(page, state);
  await begin(page);
  const dialog = wizard(page);
  await expect(dialog.getByRole('button', { name: 'Retry', exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Back', exact: true })).toBeVisible();
  state.phase = 'identifying';
  await dialog.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Retry', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Back', exact: true })).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect.poll(() => state.requests.filter((request) => request.path.endsWith('/discovery-a') &&
    request.method === 'DELETE').length).toBe(2);
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
});

test('recovering P25 probe status restores the scanning footer', async ({ page }) => {
  const state = { phase: 'identifying', failProbeStatusOnce: true };
  await install(page, state);
  await begin(page);
  const dialog = wizard(page);
  await expect(dialog.getByRole('button', { name: 'Retry status', exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Retry status', exact: true }).click();
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Retry status', exact: true })).toHaveCount(0);
  await expect(dialog).toContainText('waiting for a stable system and site.');
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(dialog).toHaveCount(0);
});

test('ambiguous aliases require a choice; saved start failure retries without recreating', async ({ page }) => {
  const state = { ambiguous: true, failStart: true };
  await install(page, state);
  await begin(page);
  const dialog = wizard(page);
  await expect(dialog.getByLabel('Alias List', { exact: true })).toHaveValue('');
  await expect(dialog.getByLabel('Alias List', { exact: true }).locator('option[value="new"]')).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Create & start' }).click();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
  await dialog.getByLabel('Alias List', { exact: true }).selectOption('22');
  await dialog.getByRole('button', { name: 'Create & start' }).click();
  await expect(dialog.getByRole('heading', { name: 'Channel saved' })).toBeVisible();
  await expect(dialog).toContainText('Tuner capacity is in use.');
  await dialog.getByRole('button', { name: 'Retry start' }).click();
  await expect(dialog.getByRole('heading', { name: 'Channel running' })).toBeVisible();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(1);
  expect(state.requests.filter((request) => request.path.endsWith('/start'))).toHaveLength(1);
  const save = state.requests.find((request) => request.path.endsWith('/save')).body;
  expect(save.alias_list_id).toBe(22);
  expect(save.new_alias_list_name).toBeNull();
  await dialog.getByRole('button', { name: 'Done', exact: true }).click();
  await expect.poll(() => state.requests.some((request) => request.path.endsWith('/discovery-a') &&
    request.method === 'DELETE')).toBe(true);
});

test('one P25 identity match reuses its Alias List and offers no new list', async ({ page }) => {
  const state = { singleMatch: true };
  await install(page, state);
  await begin(page);
  const dialog = wizard(page);
  await expect(dialog.getByLabel('Alias List', { exact: true })).toHaveValue('21');
  await expect(dialog.getByLabel('Alias List', { exact: true }).locator('option[value="new"]')).toHaveCount(0);
  await expect(dialog.getByLabel('New Alias List name', { exact: true })).toBeHidden();
  await dialog.getByRole('button', { name: 'Create & start' }).click();
  await expect(dialog.getByRole('heading', { name: 'Channel running' })).toBeVisible();
  expect(state.requests.find((request) => request.path.endsWith('/save')).body.alias_list_id).toBe(21);
});

test('stale save refreshes choices and preserves the draft before an explicit retry', async ({ page }) => {
  const state = { staleOnce: true };
  await install(page, state);
  await begin(page);
  const dialog = wizard(page);
  await dialog.getByLabel('Name', { exact: true }).fill('County Control');
  await dialog.getByLabel('New Alias List name', { exact: true }).fill('County aliases');
  await dialog.getByRole('button', { name: 'Create & start' }).click();
  await expect(dialog).toContainText('Channel or Alias List choices changed.');
  await expect(dialog.getByLabel('Name', { exact: true })).toHaveValue('County Control');
  await expect(dialog.getByLabel('New Alias List name', { exact: true })).toHaveValue('County aliases');
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(1);
  await dialog.getByRole('button', { name: 'Create & start' }).click();
  await expect(dialog.getByRole('heading', { name: 'Channel running' })).toBeVisible();
  const saves = state.requests.filter((request) => request.path.endsWith('/save'));
  expect(saves.map((request) => request.body.revision)).toEqual([7, 8]);
  expect(saves[1].body.alias_list_id).toBe(0);
  expect(saves[1].body.name).toBe('County Control');
});

for (const [protocolId, theme, width] of [['am', 'light', 1280], ['nbfm', 'dark', 390]]) {
  test(`${protocolId} manifest settings and review fit ${theme} at ${width}px`, async ({ page }) => {
    const state = { theme };
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ colorScheme: theme });
    await install(page, state);
    await begin(page, protocolId);
    const dialog = wizard(page);
    const bandwidth = dialog.getByLabel('Bandwidth', { exact: true });
    await expect(bandwidth).toBeVisible();
    await bandwidth.selectOption('BW_25_0');
    await dialog.getByRole('button', { name: 'Next', exact: true }).click();
    await expect(dialog.getByLabel('New Alias List name', { exact: true })).toBeVisible();
    const bounds = await dialog.boundingBox();
    expect(bounds.x).toBeGreaterThanOrEqual(0);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(width);
    await expect(dialog).toHaveScreenshot(`spectrum-discovery-${protocolId}-${theme}.png`);
    await dialog.getByRole('button', { name: 'Create & start' }).click();
    await expect(dialog.getByRole('heading', { name: 'Channel running' })).toBeVisible();
    const save = state.requests.find((request) => request.path.endsWith('/save')).body;
    expect(save.settings.bandwidth).toBe('BW_25_0');
    expect(save.alias_list_id).toBe(0);
    expect(save.new_alias_list_name).toBe('P25 B0001-123');
  });
}

for (const protocolId of ['am', 'nbfm']) {
  test(`${protocolId} interrupted setup stays protocol-specific and retries the selected protocol`, async ({ page }) => {
    const state = {};
    await page.clock.install();
    await install(page, state);
    await begin(page, protocolId);
    const dialog = wizard(page);
    const label = protocols.profiles.find((profile) => profile.id === protocolId).label;
    await expect(dialog.getByRole('heading', { name: `${label} settings`, exact: true })).toBeVisible();
    state.phase = 'failed';
    state.reason = 'The selected tuner is unavailable.';
    await page.clock.fastForward(11000);
    await expect(dialog.getByRole('heading', { name: `${label} setup interrupted` })).toBeVisible();
    await expect(dialog).toContainText('The selected tuner is unavailable.');
    await expect(dialog).not.toContainText('P25');
    await expect(dialog).not.toContainText('C4FM');
    await expect(dialog).not.toContainText('CQPSK');
    await expect(dialog.getByRole('button', { name: 'Back', exact: true })).toBeVisible();
    state.phase = 'ready';
    state.reason = null;
    await dialog.getByRole('button', { name: 'Retry', exact: true }).click();
    await expect(dialog.getByRole('heading', { name: `${label} settings`, exact: true })).toBeVisible();
    const opens = state.requests.filter((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
      request.method === 'POST');
    expect(opens.map((request) => request.body.protocol_id)).toEqual([protocolId, protocolId]);
    expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
  });
}

test('a fractional Hertz spectrum selection displays and submits the same integer frequency', async ({ page }) => {
  const state = {};
  await install(page, state);
  await page.evaluate(() => window.discoveryApi.openTunerFrequencyActions({
    tunerId: 'idle-a', targetId: 'target-a', browseLeaseId: 'browse-a',
    frequencyHz: 776715007.9617834, rawFrequencyHz: 776715007.9617834
  }));
  const add = page.getByRole('button', { name: 'Add channel / system', exact: true });
  await expect(add).toBeEnabled();
  await add.click();
  const dialog = wizard(page);
  await expect(dialog.locator('.spectrum-discovery-workspace > p')).toHaveText('776.715008 MHz');
  await dialog.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(dialog.getByRole('heading', { name: 'Review and start listening' })).toBeVisible();
  await expect(dialog.locator('.spectrum-discovery-workspace > p')).toHaveText('776.715008 MHz');
  expect(state.requests.find((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
    request.method === 'POST').body.frequency_hz).toBe(776715008);
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
});

test('configured or learned channel frequency blocks Add using receiver eligibility', async ({ page }) => {
  const state = { known: true };
  await install(page, state);
  await page.evaluate(() => window.discoveryApi.openTunerFrequencyActions({
    tunerId: 'idle-a', targetId: 'target-a', frequencyHz: 851012500
  }));
  const add = page.getByRole('button', { name: 'Add channel / system', exact: true });
  await expect(add).toBeDisabled();
  await expect(page.locator('.tuner-frequency-popover')).toContainText('This frequency belongs to County Control.');
  expect(state.requests.filter((request) => request.path.endsWith('/eligibility'))).toHaveLength(1);
});

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`P25 discovery gallery ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`/design-system.html?view=spectrum-discovery&theme=${theme}`);
    await expect(page.locator('.spectrum-discovery-modal')).toBeVisible();
    await expect(page.locator('.spectrum-discovery-modal')).toHaveScreenshot(`spectrum-discovery-p25-${theme}.png`);
  });
}
