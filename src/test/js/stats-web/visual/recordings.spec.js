'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

const call = {
  id: 17, start_ms: Date.parse('2026-09-28T10:08:42Z'), duration_ms: 42_000,
  system_name: 'Metro Public Safety', talkgroup_id: 1201, talkgroup_alias: 'Fire Dispatch',
  source_id: 30914, source_alias: 'Engine 4', source_ota_alias: 'ENG 4',
  site_name: 'North Ridge', call_type: 'GROUP', protocol: 'APCO25_PHASE2', voice_type: 'CLEAR'
};

const systemKey = 'p25:00001:001';
const channelId = '00000000-0000-4000-8000-000000000017';
const linkedCall = {
  ...call,
  system_key: systemKey,
  channel_id: channelId,
  channel_name: 'North Ridge Channel',
  radio_system_entity_ref: { kind: 'radio_system', key: systemKey },
  channel_entity_ref: { kind: 'channel', key: channelId },
  target_entity_ref: {
    kind: 'talkgroup', radio_system_key: systemKey, identity_key: 'v1-g-00001-001-1201'
  },
  source_entity_ref: {
    kind: 'radio', radio_system_key: systemKey, identity_key: 'v1-r-00001-001-30914'
  }
};

const linkedHrefs = [
  '/?view=radio-system&radio_system_key=p25%3A00001%3A001',
  `/?view=channel&configuration_id=${channelId}`,
  '/?view=group-identity&radio_system_key=p25%3A00001%3A001&identity_key=v1-g-00001-001-1201',
  '/?view=radio&radio_system_key=p25%3A00001%3A001&identity_key=v1-r-00001-001-30914'
];

function entityLink(host, href) {
  return host.locator(`a[href="${href}"]`);
}

async function openRecordings(page, options = {}) {
  const state = {
    mode: options.mode || 'MANAGED', available: options.available !== false,
    hasCalls: options.hasCalls !== false, matchCalls: true, admin: options.admin !== false,
    call: options.call || call, callDetail: options.callDetail || null, requestedFilters: [],
    transcription: options.transcription || null, settingsWrites: [],
    settings: { mode: options.mode || 'MANAGED', managed_directory: '/recordings',
      retention_days: null, transcription_enabled: false, transcription_url: '',
      transcription_model: '', transcription_min_duration_ms: 500,
      transcription_key_configured: Boolean(options.keyConfigured) }
  };
  const preferences = structuredClone(defaultPreferences);
  preferences.appearance.theme = options.theme || 'light';
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const pathname = url.pathname;
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: { data: { configured: true, authenticated: state.admin,
        username: state.admin ? 'admin' : null, tier: state.admin ? 'admin' : 'public',
        primary: state.admin, capabilities: { recordings: true,
          'admin-recordings': state.admin, 'admin-settings': state.admin, dashboard: true,
          radio: options.radio !== false } } } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } });
    } else if (pathname === '/api/v1/recordings/status') {
      await route.fulfill({ json: { data: { mode: state.mode, available: state.available,
        has_calls: state.hasCalls } } });
    } else if (pathname === '/api/v1/recordings/calls') {
      state.requestedFilters.push(Object.fromEntries(url.searchParams));
      const matching = state.hasCalls && state.matchCalls;
      await route.fulfill({ json: { data: { calls: matching ? [state.call] : [],
        next_cursor: null, total: null } } });
    } else if (pathname === '/api/v1/recordings/calls/17') {
      await route.fulfill({ json: { data: { call: state.callDetail || state.call,
        ...(state.transcription ? { transcription: state.transcription } : {}) } } });
    } else if (pathname === '/api/v1/admin/recordings/settings') {
      if (route.request().method() === 'PUT') {
        const update = route.request().postDataJSON();
        state.settingsWrites.push(update);
        Object.assign(state.settings, update);
        if (update.transcription_api_key) state.settings.transcription_key_configured = true;
        if (update.transcription_clear_api_key) state.settings.transcription_key_configured = false;
      }
      await route.fulfill({ json: { data: state.settings } });
    } else if (pathname === '/api/v1/admin/recordings/status') {
      await route.fulfill({ json: { data: { catalog: { call_count: 1 },
        transcription: { pending: 3, completed: 1, failed: 1, active: false } } } });
    } else if (pathname === '/api/v1/admin/recordings/calls/17/transcription/retry') {
      state.transcription = { status: 'PENDING', text: null, stored_at_ms: null };
      await route.fulfill({ json: { data: state.transcription } });
    } else if (pathname === '/api/v1/recordings/suggestions') {
      const q = url.searchParams.get('q') || '';
      if (q === 'error') {
        await route.fulfill({ status: 500, json: { error: { message: 'Suggestion error' } } });
      } else {
        if (q === 'slow') await new Promise((resolve) => setTimeout(resolve, 350));
        const suggestions = q.toLowerCase().includes('fire') ? [
          { kind: 'talkgroup', id: 1201, label: 'Fire Dispatch', detail: 'Metro Public Safety · TG 1201',
            system_key: 'metro', system_name: 'Metro Public Safety' }
        ] : [];
        await route.fulfill({ json: { data: { suggestions } } });
      }
    } else if (pathname === '/api/v1/status') {
      await route.fulfill({ json: { data: {} } });
    } else {
      await route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
    }
  });
  await page.goto(options.view === 'admin' ? '/app.html?view=admin&tab=recordings' :
    '/app.html?view=recordings');
  return state;
}

test('Classic empty page invites only the primary administrator', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openRecordings(page, { mode: 'CLASSIC', hasCalls: false });
  await expect(page.getByRole('heading', { name: 'Make every call easy to find' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Enable Managed Recordings' }))
    .toHaveAttribute('href', '/?view=admin&tab=recordings');
  await expect(page.locator('.recordings-search')).toHaveCount(0);
  await expect(page.locator('.recordings-pager')).toHaveCount(0);
  await expect(page.locator('.recordings-library')).toHaveScreenshot('recordings-classic-admin-empty.png');

  await page.setViewportSize({ width: 390, height: 844 });
  await openRecordings(page, { mode: 'CLASSIC', hasCalls: false, admin: false, theme: 'dark' });
  await expect(page.getByRole('heading', { name: 'No managed recordings yet' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Enable Managed Recordings' })).toHaveCount(0);
  await expect(page.locator('.recordings-search')).toHaveCount(0);
  await expect(page.locator('.recordings-library')).toHaveScreenshot('recordings-classic-guest-empty.png');
});

test('Classic history remains searchable and unavailable catalog has no enable pitch', async ({ page }) => {
  await openRecordings(page, { mode: 'CLASSIC' });
  await expect(page.locator('.recordings-mode-notice')).toContainText('Earlier Managed calls remain searchable');
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  await expect(page.getByRole('combobox', { name: 'Find a call' })).toBeVisible();
  await expect(page.locator('.recordings-library')).toHaveScreenshot('recordings-classic-history.png');

  await openRecordings(page, { mode: 'CLASSIC', hasCalls: false, available: false });
  await expect(page.locator('.recordings-library')).toContainText('library is unavailable');
  await expect(page.getByRole('link', { name: 'Enable Managed Recordings' })).toHaveCount(0);
});

test('Managed empty page refreshes when the first call arrives', async ({ page }) => {
  const state = await openRecordings(page, { hasCalls: false });
  await expect(page.getByRole('heading', { name: 'Ready for the first call' })).toBeVisible();
  await expect(page.locator('.recordings-library')).toHaveScreenshot('recordings-managed-empty.png');
  state.hasCalls = true;
  await page.getByRole('button', { name: 'Refresh calls' }).click();
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  expect(state.requestedFilters.length).toBeGreaterThan(0);
});

for (const theme of ['light', 'dark']) {
  test(`desktop ${theme} search aligns controls and exposes structured autocomplete`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await openRecordings(page, { theme });
    await expect(page.locator('.recordings-call')).toHaveCount(1);
    const rowSelection = page.locator('.recordings-call-select');
    await expect(rowSelection).toHaveClass(/ui-selection-check/);
    await expect(rowSelection).toHaveCSS('appearance', 'none');
    const time = page.getByRole('combobox', { name: 'Date & time' });
    const query = page.getByRole('combobox', { name: 'Find a call' });
    const timeBox = await time.boundingBox();
    const queryBox = await query.boundingBox();
    expect(Math.abs(timeBox.y - queryBox.y)).toBeLessThan(2);
    expect(Math.abs(timeBox.height - queryBox.height)).toBeLessThan(2);
    expect(queryBox.width).toBeLessThan(470);
    await expect(page.getByRole('button', { name: 'More filters' })).toBeVisible();
    await expect(page.locator('.recordings-library')).toHaveScreenshot(`recordings-desktop-${theme}.png`);

    await query.fill('fire');
    const suggestions = page.getByRole('listbox');
    await expect(suggestions.getByRole('option')).toHaveCount(1);
    await expect(suggestions.getByRole('option')).toContainText('Talkgroup');
    await expect(suggestions.getByRole('option')).toContainText('Metro Public Safety');
    if (theme === 'light') await expect(page.locator('.recordings-library'))
      .toHaveScreenshot('recordings-suggestions-light.png');
    await query.press('ArrowDown');
    await expect(query).toHaveAttribute('aria-activedescendant', /recordings-options-q-0/);
    await query.press('Enter');
    await expect(suggestions).toBeHidden();
    await expect(page.locator('.recordings-selected-filters')).toContainText('Fire Dispatch');
    await page.getByRole('button', { name: 'Search', exact: true }).click();
    await expect(page.locator('.recordings-shared-overline')).toHaveText('ALL MATCHING CALLS');
    await expect(page.locator('.recordings-shared-heading')).toContainText('Fire Dispatch');
    if (theme === 'light') await expect(page.locator('.recordings-library'))
      .toHaveScreenshot('recordings-filtered-context-light.png');
    await page.getByRole('button', { name: /More filters/ }).click();
    await expect(page.getByRole('combobox', { name: 'Radio system' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Clear filters' })).toBeVisible();
  });
}

test('suggestions show no-match and failure states, then stay closed after blur', async ({ page }) => {
  await openRecordings(page);
  const query = page.getByRole('combobox', { name: 'Find a call' });
  await query.fill('zzzz');
  await expect(page.getByRole('listbox')).toContainText('No matching names or IDs');
  await query.fill('error');
  await expect(page.getByRole('listbox')).toContainText('Suggestions could not load');
  const requested = page.waitForRequest((request) => request.url().includes('/recordings/suggestions?q=slow'));
  await query.fill('slow');
  await requested;
  await page.getByRole('combobox', { name: 'Date & time' }).focus();
  await page.waitForTimeout(450);
  await expect(page.getByRole('listbox')).toBeHidden();
});

test('empty search results omit count and pagination', async ({ page }) => {
  const state = await openRecordings(page);
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  state.matchCalls = false;
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.locator('.recordings-results')).toContainText('No matching calls');
  await expect(page.locator('.recordings-result-count')).toBeHidden();
  await expect(page.locator('.recordings-pager')).toBeHidden();
});

test('search controls stay within the narrow desktop boundary', async ({ page }) => {
  await page.setViewportSize({ width: 768, height: 900 });
  await openRecordings(page);
  await expect(page.locator('.recordings-call')).toHaveCount(1);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  const time = await page.getByRole('combobox', { name: 'Date & time' }).boundingBox();
  const query = await page.getByRole('combobox', { name: 'Find a call' }).boundingBox();
  expect(Math.abs(time.y - query.y)).toBeLessThan(2);
  expect(Math.abs(time.height - query.height)).toBeLessThan(2);
});

for (const theme of ['light', 'dark']) {
  test(`mobile ${theme} filter sheet fills viewport and applies fields`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const state = await openRecordings(page, { theme });
    await expect(page.locator('.recordings-call')).toHaveCount(1);
    await expect(page.locator('.recordings-library')).toHaveScreenshot(`recordings-mobile-${theme}.png`);
    await page.getByRole('button', { name: 'Filters' }).click();
    const sheet = page.getByRole('dialog', { name: 'Filters' });
    await expect(sheet).toBeVisible();
    const bounds = await sheet.boundingBox();
    expect(bounds.width).toBe(390);
    expect(bounds.height).toBe(844);
    await expect(sheet.getByRole('combobox', { name: 'Radio system' })).toBeVisible();
    await expect(sheet).toHaveScreenshot(`recordings-mobile-filters-${theme}.png`);
    await sheet.locator('.modal-content').evaluate((content) => { content.scrollTop = content.scrollHeight; });
    const lastField = await sheet.locator('.recordings-search-fields .recordings-field').last().boundingBox();
    const footer = await sheet.locator('.recordings-filter-sheet-actions').boundingBox();
    expect(lastField.y + lastField.height).toBeLessThanOrEqual(footer.y);
    await sheet.getByRole('button', { name: 'Show calls' }).click();
    await expect(sheet).toBeHidden();
    expect(state.requestedFilters.length).toBeGreaterThan(1);
  });
}

test('linked recording cards and details open canonical entity pages', async ({ page }) => {
  await openRecordings(page, { call: linkedCall });
  const card = page.locator('.recordings-call');
  await expect(card).toHaveCount(1);
  await expect(card.getByRole('button', { name: 'Fire Dispatch' })).toBeVisible();
  const cardLabels = ['Metro Public Safety', 'North Ridge Channel', '1201', 'Engine 4'];
  for (const [index, target] of linkedHrefs.entries()) {
    await expect(entityLink(card, target).first()).toBeVisible();
    await expect(entityLink(card, target).first()).toContainText(cardLabels[index]);
  }
  await expect(card).toHaveScreenshot('recordings-linked-desktop.png');
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(card).toHaveScreenshot('recordings-linked-mobile.png');
  await card.getByRole('button', { name: 'Fire Dispatch' }).click();
  const detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail).toBeVisible();
  for (const target of linkedHrefs) {
    await expect(entityLink(detail, target).first()).toBeVisible();
  }
  await expect(detail).toHaveScreenshot('recordings-linked-detail-mobile.png');
});

test('conventional recordings link their configured channel without a radio system', async ({ page }) => {
  const analog = {
    ...call, system_name: null, site_name: null, talkgroup_id: null, talkgroup_alias: null,
    source_id: null, source_alias: null, source_ota_alias: null,
    call_type: 'CONVENTIONAL', protocol: 'NBFM',
    channel_id: channelId, channel_name: 'Hilltop FM',
    channel_entity_ref: { kind: 'channel', key: channelId }
  };
  await openRecordings(page, { call: analog });
  const card = page.locator('.recordings-call');
  const channelHref = `/?view=channel&configuration_id=${channelId}`;
  await expect(card.getByRole('button', { name: 'Hilltop FM' })).toBeVisible();
  await expect(entityLink(card, channelHref).first()).toContainText('Hilltop FM');
  await card.getByRole('button', { name: 'Hilltop FM' }).click();
  const detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(entityLink(detail, channelHref).first()).toContainText('Hilltop FM');
});

test('direct destination and patch member facts link to their identities', async ({ page }) => {
  const direct = {
    ...linkedCall, call_type: 'DIRECT', talkgroup_id: null, talkgroup_alias: null,
    destination_radio_id: 42137, destination_radio_alias: 'Unit 12',
    target_entity_ref: {
      kind: 'radio', radio_system_key: systemKey, identity_key: 'v1-r-00001-001-42137'
    }
  };
  await openRecordings(page, { call: direct });
  await expect(entityLink(page.locator('.recordings-call'),
    '/?view=radio&radio_system_key=p25%3A00001%3A001&identity_key=v1-r-00001-001-42137').first())
    .toContainText('To Radio 42137');
  await page.locator('.recordings-call-title').click();
  let detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(entityLink(detail,
    '/?view=radio&radio_system_key=p25%3A00001%3A001&identity_key=v1-r-00001-001-42137').first())
    .toBeVisible();

  const patch = {
    ...linkedCall, call_type: 'PATCH', talkgroup_alias: 'Dispatch Patch',
    target_entity_ref: {
      kind: 'patch_group', radio_system_key: systemKey, identity_key: 'v1-p-00001-001-1201'
    },
    patch_members: [{ kind: 'talkgroup', id: 1202, alias: 'Fireground',
      entity_ref: { kind: 'talkgroup', radio_system_key: systemKey,
        identity_key: 'v1-g-00001-001-1202' } }]
  };
  await openRecordings(page, { call: patch });
  await expect(entityLink(page.locator('.recordings-call'),
    '/?view=group-identity&radio_system_key=p25%3A00001%3A001&identity_key=v1-p-00001-001-1201').first())
    .toContainText('Patch 1201');
  await page.locator('.recordings-call-title').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(entityLink(detail,
    '/?view=group-identity&radio_system_key=p25%3A00001%3A001&identity_key=v1-p-00001-001-1201').first())
    .toBeVisible();
  await expect(entityLink(detail,
    '/?view=group-identity&radio_system_key=p25%3A00001%3A001&identity_key=v1-g-00001-001-1202').first())
    .toContainText('Fireground');
});

test('recording identities remain readable when references or radio access are absent', async ({ page }) => {
  await openRecordings(page);
  let card = page.locator('.recordings-call');
  await expect(card).toContainText('Fire Dispatch');
  await expect(card).toContainText('Engine 4');
  await expect(card.locator('a[href*="view="]')).toHaveCount(0);
  await card.locator('.recordings-call-title').click();
  let detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail).toContainText('Fire Dispatch');
  await expect(detail.locator('a[href*="view="]')).toHaveCount(0);

  await openRecordings(page, { call: linkedCall, radio: false });
  card = page.locator('.recordings-call');
  await expect(card).toContainText('Fire Dispatch');
  await expect(card).toContainText('Engine 4');
  await expect(card.locator('a[href*="view="]')).toHaveCount(0);
  await card.locator('.recordings-call-title').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail).toContainText('North Ridge Channel');
  await expect(detail.locator('a[href*="view="]')).toHaveCount(0);
});

test('transcription settings save a write-only key and show catalog progress', async ({ page }) => {
  const state = await openRecordings(page, { view: 'admin', keyConfigured: true });
  await expect(page.getByRole('checkbox', { name: 'Transcribe managed calls' })).toBeVisible();
  await expect(page.locator('.recordings-admin')).toContainText('3');
  await expect(page.getByRole('button', { name: 'Clear saved key' })).toBeVisible();
  await page.locator('.recordings-admin .ui-toggle').click();
  await expect(page.getByRole('checkbox', { name: 'Transcribe managed calls' })).toBeChecked();
  await page.getByLabel('Transcription endpoint URL').fill('http://127.0.0.1:8000/v1/audio/transcriptions');
  await page.getByLabel('Model ID').fill('test-model');
  await page.getByLabel('Minimum call length (ms)').fill('750');
  await page.getByLabel('API key').fill('replacement-key');
  await page.getByRole('button', { name: 'Save transcription settings' }).click();
  await expect(page.locator('.recordings-admin')).toContainText('Transcription settings saved.');
  expect(state.settingsWrites.at(-1)).toMatchObject({ transcription_enabled: true,
    transcription_url: 'http://127.0.0.1:8000/v1/audio/transcriptions',
    transcription_model: 'test-model', transcription_min_duration_ms: 750,
    transcription_api_key: 'replacement-key' });
  await expect(page.getByLabel('API key')).toHaveValue('');
  await expect(page.locator('.recordings-admin')).not.toContainText('replacement-key');
  await page.getByRole('button', { name: 'Save transcription settings' }).click();
  expect(state.settingsWrites.at(-1)).not.toHaveProperty('transcription_api_key');
  await page.getByRole('button', { name: 'Clear saved key' }).click();
  expect(state.settingsWrites.at(-1)).toMatchObject({ transcription_clear_api_key: true });
  await expect(page.getByRole('button', { name: 'Clear saved key' })).toBeHidden();
});

test('transcription settings fit a narrow dark viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openRecordings(page, { view: 'admin', theme: 'dark', keyConfigured: true });
  await expect(page.getByLabel('Transcription endpoint URL')).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});

test('call details show a transcript and allow administrators to retry failures', async ({ page }) => {
  await openRecordings(page, { transcription: { status: 'complete',
    text: 'Engine four arriving.\nRequesting another unit.', stored_at_ms: Date.parse('2026-09-28T10:09:00Z') } });
  await page.locator('.recordings-call-title').click();
  let detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail.locator('.recordings-transcript')).toContainText('Engine four arriving.');
  await expect(detail.locator('.recordings-transcript')).toContainText('Requesting another unit.');
  await expect(detail.getByRole('button', { name: 'Retry transcription' })).toHaveCount(0);

  const retryState = await openRecordings(page, { transcription: { status: 'failed',
    text: null, stored_at_ms: null } });
  await page.locator('.recordings-call-title').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail.getByRole('button', { name: 'Retry transcription' })).toBeVisible();
  await detail.getByRole('button', { name: 'Retry transcription' }).click();
  await expect(detail.locator('.recordings-transcript')).toContainText('Pending transcription.');
  expect(retryState.transcription.status).toBe('PENDING');

  await openRecordings(page, { admin: false,
    transcription: { status: 'failed', text: null, stored_at_ms: null } });
  await page.locator('.recordings-call-title').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail.locator('.recordings-transcript')).toContainText('Transcription failed.');
  await expect(detail.getByRole('button', { name: 'Retry transcription' })).toHaveCount(0);

  await openRecordings(page, { transcription: { status: 'too_short', text: null, stored_at_ms: null } });
  await page.locator('.recordings-call-title').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail.locator('.recordings-transcript')).toContainText('shorter than the minimum length');

  await openRecordings(page, { transcription: { status: 'disabled', text: null, stored_at_ms: null } });
  await page.locator('.recordings-call-title').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail.locator('.recordings-transcript')).toContainText('Transcription is off.');
});
