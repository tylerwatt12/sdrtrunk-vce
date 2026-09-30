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

function mixedRecordingCalls() {
  return Array.from({ length: 30 }, (_, index) => {
    const secondSystem = index % 4 === 3;
    const scope = secondSystem ? 'p25:00002:002' : systemKey;
    const home = secondSystem ? '00002-002' : '00001-001';
    const groupId = 1201 + index % 3;
    const sourceId = 30914 + index;
    const configuredChannel = `00000000-0000-4000-8000-${String(17 + index % 4).padStart(12, '0')}`;
    const aliases = [
      'Central / East Ridge / Valley Township / River District Fire Dispatch and Mutual Aid',
      'Northern District Police Dispatch and Regional Public Safety Coordination',
      'Countywide Emergency Medical Services and Hospital Transport Coordination'
    ];
    return {
      ...linkedCall, id: 17 + index, start_ms: call.start_ms - index * 60_000,
      duration_ms: 7_000 + index * 1_100,
      system_key: scope,
      system_name: secondSystem ? 'County Regional Public Safety Communications' :
        'Metro Regional Interoperability and Public Safety Communications Network',
      radio_system_entity_ref: { kind: 'radio_system', key: scope },
      talkgroup_id: groupId, talkgroup_alias: aliases[index % aliases.length],
      talkgroup_description: 'Dispatch operations serving several municipalities, regional response teams, hospitals, and mutual aid partners.',
      talkgroup_group: 'Regional public safety operations and interagency mutual aid',
      target_entity_ref: { kind: 'talkgroup', radio_system_key: scope,
        identity_key: `v1-g-${home}-${groupId}` },
      source_id: sourceId, source_alias: `Regional response unit ${index + 1}`,
      source_ota_alias: `FIELD UNIT ${index + 1}`,
      source_entity_ref: { kind: 'radio', radio_system_key: scope,
        identity_key: `v1-r-${home}-${sourceId}` },
      site_name: index % 2 ? 'East Ridge / River Valley Simulcast' : 'Central / Valley Township Simulcast',
      audio_from: { wacn: secondSystem ? 2 : 1, system_id: secondSystem ? 2 : 1,
        rfss: 1, site_id: index % 2 + 1 },
      channel_id: configuredChannel,
      channel_name: `Regional Receiver / ${secondSystem ? 'County' : 'Metro'} / Simulcast Channel ${index % 4 + 1}`,
      channel_entity_ref: { kind: 'channel', key: configuredChannel },
      frequency_hz: 851_012_500 + index * 25_000,
      transcript_excerpt: 'Unit arriving at the designated staging location. Requesting the next available response team and confirming the information with dispatch before proceeding.'
    };
  });
}

async function openRecordings(page, options = {}) {
  const state = {
    mode: options.mode || 'MANAGED', available: options.available !== false,
    hasCalls: options.hasCalls !== false, matchCalls: true, admin: options.admin !== false,
    call: options.call || call, calls: options.calls || null, callDetail: options.callDetail || null, requestedFilters: [],
    paginate: options.paginate === true,
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
      const calls = matching ? state.calls || [state.call] : [];
      const offset = state.paginate ? Number((url.searchParams.get('cursor') || 'fixture:0').split(':')[1]) : 0;
      const limit = state.paginate ? Number(url.searchParams.get('limit')) : calls.length;
      await route.fulfill({ json: { data: { calls: calls.slice(offset, offset + limit),
        next_cursor: state.paginate && offset + limit < calls.length ? `fixture:${offset + limit}` : null,
        total: null } } });
    } else if (/^\/api\/v1\/recordings\/calls\/\d+$/.test(pathname)) {
      const id = Number(pathname.split('/').at(-1));
      const selected = state.calls?.find((item) => item.id === id) || state.call;
      await route.fulfill({ json: { data: { call: state.callDetail || selected,
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
    expect(queryBox.width).toBeGreaterThan(450);
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
    await expect(page.locator('.recordings-selected-filters')).toContainText('Fire Dispatch');
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
  await expect(card.locator('.recordings-call-title')).toBeVisible();
  const cardLabels = ['Metro Public Safety', 'North Ridge Channel', 'Fire Dispatch', 'Engine 4'];
  for (const [index, target] of linkedHrefs.entries()) {
    if (index === 1) {
      await expect(entityLink(card, target)).toHaveCount(0);
      continue;
    }
    await expect(entityLink(card, target).first()).toBeVisible();
    await expect(entityLink(card, target).first()).toContainText(cardLabels[index]);
  }
  await expect(card.locator('.recordings-call-title')).toContainText('Fire Dispatch');
  await expect(card).toHaveScreenshot('recordings-linked-desktop.png');
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(card).toHaveScreenshot('recordings-linked-mobile.png');
  await card.locator('.recordings-call-info').click();
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
  await expect(card.locator('.recordings-call-title')).toBeVisible();
  await expect(entityLink(card, channelHref).first()).toContainText('Hilltop FM');
  await card.locator('.recordings-call-info').click();
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
    .toContainText('Direct to Unit 12');
  await page.locator('.recordings-call-info').click();
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
    .toContainText('Dispatch Patch');
  await page.locator('.recordings-call-info').click();
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
  await card.locator('.recordings-call-info').click();
  let detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail).toContainText('Fire Dispatch');
  await expect(detail.locator('a[href*="view="]')).toHaveCount(0);

  await openRecordings(page, { call: linkedCall, radio: false });
  card = page.locator('.recordings-call');
  await expect(card).toContainText('Fire Dispatch');
  await expect(card).toContainText('Engine 4');
  await expect(card.locator('a[href*="view="]')).toHaveCount(0);
  await card.locator('.recordings-call-info').click();
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
  await page.locator('.recordings-call-info').click();
  let detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail.locator('.recordings-transcript')).toContainText('Engine four arriving.');
  await expect(detail.locator('.recordings-transcript')).toContainText('Requesting another unit.');
  await expect(detail.getByRole('button', { name: 'Retry transcription' })).toHaveCount(0);

  const retryState = await openRecordings(page, { transcription: { status: 'failed',
    text: null, stored_at_ms: null } });
  await page.locator('.recordings-call-info').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail.getByRole('button', { name: 'Retry transcription' })).toBeVisible();
  await detail.getByRole('button', { name: 'Retry transcription' }).click();
  await expect(detail.locator('.recordings-transcript')).toContainText('Pending transcription.');
  expect(retryState.transcription.status).toBe('PENDING');

  await openRecordings(page, { admin: false,
    transcription: { status: 'failed', text: null, stored_at_ms: null } });
  await page.locator('.recordings-call-info').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail.locator('.recordings-transcript')).toContainText('Transcription failed.');
  await expect(detail.getByRole('button', { name: 'Retry transcription' })).toHaveCount(0);

  await openRecordings(page, { transcription: { status: 'too_short', text: null, stored_at_ms: null } });
  await page.locator('.recordings-call-info').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail.locator('.recordings-transcript')).toContainText('shorter than the minimum length');

  await openRecordings(page, { transcription: { status: 'disabled', text: null, stored_at_ms: null } });
  await page.locator('.recordings-call-info').click();
  detail = page.getByRole('dialog', { name: 'Call details' });
  await expect(detail.locator('.recordings-transcript')).toContainText('Transcription is off.');
});

for (const theme of ['light', 'dark']) {
  test(`approved cards and playback chooser ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    const first = { ...linkedCall, transcript_excerpt: 'Engine four arriving. Requesting another unit.' };
    const second = { ...first, id: 18, source_id: 30915, source_alias: 'Engine 5',
      source_ota_alias: null, source_entity_ref: null, start_ms: first.start_ms + 60_000 };
    const state = await openRecordings(page, { theme, call: first, calls: [second, first] });
    await expect(page.locator('.recordings-shared')).toContainText('Radio system');
    await expect(page.locator('.recordings-shared')).toContainText('Talkgroup');
    await expect(page.locator('.recordings-shared')).toContainText('Site');
    await expect(page.locator('.recordings-library')).not.toContainText('Audio from');
    await expect(page.locator('.recordings-call-actions')).toHaveCount(0);
    await expect(page.locator('.recordings-transcript-preview')).toHaveCount(2);
    await expect(page.locator('.recordings-library')).toHaveScreenshot(`recordings-approved-${theme}.png`);
    const firstCard = page.locator('.recordings-call').first();
    await firstCard.locator('.recordings-card-play').click({ position: { x: 180, y: 20 } });
    let chooser = page.getByRole('dialog', { name: 'Play recording', exact: true });
    await expect(chooser).toBeVisible();
    await expect(chooser.getByRole('button', { name: /Play once/ })).toContainText('then stop');
    await expect(chooser.getByRole('button', { name: /Continue from here/ })).toContainText('newer matching calls');
    await expect(chooser).toHaveScreenshot(`recordings-playback-${theme}.png`);
    await page.keyboard.press('Escape');
    await expect(firstCard.locator('.recordings-card-play')).toBeFocused();
    await firstCard.locator('.recordings-call-select').check();
    await expect(chooser).toHaveCount(0);
    await expect(page.locator('.recordings-selected')).toContainText('1 selected');
    await page.getByRole('button', { name: /More filters/ }).click();
    await page.getByLabel('Transcript text').fill('arriving');
    await page.getByRole('button', { name: 'Search', exact: true }).click();
    await expect.poll(() => state.requestedFilters.at(-1).transcript).toBe('arriving');
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.locator('.recordings-results-body')).toHaveScreenshot(`recordings-approved-mobile-${theme}.png`);
    await firstCard.locator('.recordings-card-play').focus();
    await page.keyboard.press('Enter');
    chooser = page.getByRole('dialog', { name: 'Play recording', exact: true });
    await expect(chooser).toBeVisible();
    await expect(chooser).toHaveScreenshot(`recordings-playback-mobile-${theme}.png`);
    await chooser.getByRole('button', { name: /Add to queue/ }).click();
    await expect(chooser).toBeHidden();
    await expect(page.locator('.recordings-player')).toContainText('1 loaded calls');
  });
}

test('playback choices retain the selected call and continuation search', async ({ page }) => {
  await page.addInitScript(() => {
    window.playedRecordingSources = [];
    HTMLMediaElement.prototype.play = function () {
      window.playedRecordingSources.push(this.src);
      return Promise.resolve();
    };
    HTMLMediaElement.prototype.pause = function () {};
  });
  const state = await openRecordings(page, { call: { ...call, transcript_excerpt: 'Engine arriving' } });
  await page.getByRole('button', { name: /More filters/ }).click();
  await page.getByLabel('Transcript text').fill('arriving');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect.poll(() => state.requestedFilters.at(-1).transcript).toBe('arriving');
  await page.locator('.recordings-card-play').click();
  await page.getByRole('dialog', { name: 'Play recording', exact: true })
    .getByRole('button', { name: /Play once/ }).click();
  await expect(page.locator('.recordings-player')).toContainText('Fire Dispatch');
  await expect.poll(() => page.evaluate(() => window.playedRecordingSources.at(-1))).toMatch(/calls\/17\/audio$/);
  await page.locator('.recordings-card-play').click();
  await page.getByRole('dialog', { name: 'Play recording', exact: true })
    .getByRole('button', { name: /Continue from here/ }).click();
  await expect.poll(() => state.requestedFilters.some((filter) => filter.sort === 'asc' &&
    filter.transcript === 'arriving' && filter.from_ms === String(call.start_ms))).toBe(true);
  await page.locator('.recordings-call-info').click();
  const details = page.getByRole('dialog', { name: 'Call details' });
  await expect(details.getByRole('button', { name: /Play once|Continue from here|Add to queue/ })).toHaveCount(0);
});

test('unfiltered calls keep repeated identities in different systems separate', async ({ page }) => {
  const other = { ...linkedCall, id: 18, system_key: 'p25:00002:002',
    system_name: 'Other Metro Public Safety', source_alias: 'Other Engine',
    radio_system_entity_ref: null, target_entity_ref: null, source_entity_ref: null,
    channel_name: 'Other channel', channel_id: 'other-channel', channel_entity_ref: null };
  await openRecordings(page, { calls: [linkedCall, other] });
  await expect(page.locator('.recordings-call')).toHaveCount(2);
  await expect(page.locator('.recordings-shared')).toBeEmpty();
  await expect(page.locator('.recordings-call').first()).toContainText('Fire Dispatch');
  await expect(page.locator('.recordings-call').last()).toContainText('Other Metro Public Safety');
  await expect(page.locator('.recordings-call').last()).toContainText('Other Engine');
});

for (const theme of ['light', 'dark']) {
  test(`mixed ${theme} results stay compact with twenty-five calls per page`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    const calls = mixedRecordingCalls();
    const state = await openRecordings(page, { theme, calls, paginate: true });
    const cards = page.locator('.recordings-call');
    await expect(cards).toHaveCount(25);
    expect(state.requestedFilters.at(-1).limit).toBe('25');
    await expect(page.locator('.recordings-result-count')).toContainText('25 calls shown');
    await expect(page.locator('.recordings-shared')).toBeEmpty();
    await expect(cards.first().locator('.recordings-call-title')).toContainText(calls[0].talkgroup_alias);
    await expect(cards.first()).toContainText('TG 1201');
    await expect(cards.first()).toContainText(calls[0].source_alias);
    await expect(cards.first()).toContainText(calls[0].system_name);
    await expect(cards.first()).toContainText(calls[0].site_name);
    for (const detail of [calls[0].talkgroup_description, calls[0].talkgroup_group, calls[0].channel_name, 'MHz']) {
      await expect(cards.filter({ hasText: detail })).toHaveCount(0);
    }
    await expect(page.locator('.recordings-transcript-preview')).toHaveCount(25);
    await expect(page.locator('.recordings-library')).toHaveScreenshot(`recordings-mixed-compact-${theme}.png`);

    await cards.first().locator('.recordings-call-info').click();
    const details = page.getByRole('dialog', { name: 'Call details' });
    await expect(details).toContainText(calls[0].talkgroup_description);
    await expect(details).toContainText(calls[0].talkgroup_group);
    await expect(details).toContainText(calls[0].channel_name);
    await expect(details).toContainText('851.0125 MHz');
    await expect(entityLink(details, `/?view=channel&configuration_id=${calls[0].channel_id}`).first()).toBeVisible();
    await page.keyboard.press('Escape');

    await page.setViewportSize({ width: 390, height: 844 });
    await expect(cards).toHaveCount(25);
    const layout = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth - window.innerWidth,
      excerpts: [...document.querySelectorAll('.recordings-transcript-preview')].map((element) => {
        const style = getComputedStyle(element);
        return { height: element.getBoundingClientRect().height,
          lineHeight: Number.parseFloat(style.lineHeight) || Number.parseFloat(style.fontSize) * 1.5 };
      })
    }));
    expect(layout.overflow).toBeLessThanOrEqual(0);
    for (const excerpt of layout.excerpts) expect(excerpt.height).toBeLessThanOrEqual(excerpt.lineHeight + 1);
    await expect(page.locator('.recordings-results-body')).toHaveScreenshot(`recordings-mixed-compact-mobile-${theme}.png`);
    await page.getByRole('button', { name: 'Next', exact: true }).click();
    await expect(cards).toHaveCount(5);
    await expect(cards.first().locator('.recordings-call-title')).toContainText(calls[25].talkgroup_alias);
    expect(state.requestedFilters.at(-1)).toMatchObject({ limit: '25', cursor: 'fixture:25' });
    await page.getByRole('button', { name: 'Previous', exact: true }).click();
    await expect(cards).toHaveCount(25);
  });
}

test('shared system and channel use stable identities when some labels and links are missing', async ({ page }) => {
  const first = { ...linkedCall, system_name: null, radio_system_entity_ref: null,
    channel_name: null, channel_entity_ref: null };
  const second = { ...linkedCall, id: 18, source_id: 30915, source_alias: 'Engine 5',
    source_entity_ref: null, source_ota_alias: null };
  await openRecordings(page, { calls: [first, second] });
  const shared = page.locator('.recordings-shared');
  await expect(shared).toContainText('Metro Public Safety');
  await expect(shared).toContainText('North Ridge Channel');
  await expect(page.locator('.recordings-call').filter({ hasText: 'Metro Public Safety' })).toHaveCount(0);
  await expect(page.locator('.recordings-call').filter({ hasText: 'North Ridge Channel' })).toHaveCount(0);
  await expect(page.locator('.recordings-call').last().locator('.recordings-call-title')).toContainText('Engine 5');
  await shared.getByRole('button', { name: 'Show all shared call details' }).click();
  const details = page.getByRole('dialog', { name: 'Shared call details' });
  await expect(details).toContainText('Metro Public Safety');
  await expect(details).toContainText('North Ridge Channel');
});

test('same display names do not share identities across different systems', async ({ page }) => {
  const first = { ...linkedCall, talkgroup_description: 'Regional response coordination',
    talkgroup_group: 'Emergency services', audio_from: { wacn: 1, system_id: 1, rfss: 1, site_id: 1 } };
  const second = { ...first, id: 18, system_key: 'p25:00002:002',
    radio_system_entity_ref: null, target_entity_ref: null, source_entity_ref: null,
    channel_id: '00000000-0000-4000-8000-000000000018', channel_entity_ref: null,
    audio_from: { wacn: 2, system_id: 2, rfss: 1, site_id: 1 } };
  await openRecordings(page, { calls: [first, second] });
  await expect(page.locator('.recordings-shared')).toBeEmpty();
  const headings = page.locator('.recordings-call-heading');
  await expect(headings).toHaveCount(2);
  for (const heading of await headings.all()) {
    await expect(heading).toContainText('Fire Dispatch');
    await expect(heading).toContainText('TG 1201');
  }
  await expect(page.locator('.recordings-call').filter({ hasText: 'Regional response coordination' })).toHaveCount(0);
  await expect(page.locator('.recordings-call').filter({ hasText: 'Emergency services' })).toHaveCount(0);
});

test('displayed radio and talkgroup IDs with different home identities stay distinct', async ({ page }) => {
  const first = { ...linkedCall, source_entity_ref: null, target_entity_ref: null,
    source_home_wacn: 1, source_home_system_id: 1, source_home_id: 30914,
    target_home_wacn: 1, target_home_system_id: 1, target_home_id: 1201,
    talkgroup_description: 'Regional response coordination', talkgroup_group: 'Emergency services' };
  const second = { ...first, id: 18, source_home_wacn: 2, source_home_system_id: 2,
    target_home_wacn: 2, target_home_system_id: 2 };
  await openRecordings(page, { calls: [first, second] });
  const shared = page.locator('.recordings-shared');
  await expect(shared).toContainText('Metro Public Safety');
  await expect(shared.locator('dt').filter({ hasText: 'Talkgroup' })).toHaveCount(0);
  await expect(shared.locator('dt').filter({ hasText: 'Source radio' })).toHaveCount(0);
  await shared.getByRole('button', { name: 'Show all shared call details' }).click();
  const details = page.getByRole('dialog', { name: 'Shared call details' });
  await expect(details).not.toContainText('Regional response coordination');
  await expect(details).not.toContainText('Emergency services');
});

test('shared target descriptions and groups are available in info without cluttering the summary', async ({ page }) => {
  const first = { ...linkedCall, talkgroup_description: 'Regional response coordination',
    talkgroup_group: 'Emergency services' };
  const second = { ...first, id: 18, source_id: 30915, source_alias: 'Engine 5', source_entity_ref: null };
  await openRecordings(page, { calls: [first, second] });
  const shared = page.locator('.recordings-shared');
  await expect(shared).toContainText('Fire Dispatch');
  await expect(shared).not.toContainText('Regional response coordination');
  await expect(shared).not.toContainText('Emergency services');
  await shared.getByRole('button', { name: 'Show all shared call details' }).click();
  const details = page.getByRole('dialog', { name: 'Shared call details' });
  await expect(details).toContainText('Regional response coordination');
  await expect(details).toContainText('Emergency services');
});

test('calls on one local date show that date once above the results', async ({ page }) => {
  const second = { ...linkedCall, id: 18, start_ms: linkedCall.start_ms + 60_000 };
  await openRecordings(page, { calls: [linkedCall, second] });
  const dateLabel = await page.evaluate((timestamp) => new Intl.DateTimeFormat(undefined,
    { year: 'numeric', month: 'short', day: 'numeric' }).format(new Date(timestamp)), linkedCall.start_ms);
  await expect(page.locator('.recordings-result-count')).toContainText(dateLabel);
  const times = page.locator('.recordings-call-time');
  await expect(times).toHaveCount(2);
  await expect(times.filter({ hasText: dateLabel })).toHaveCount(0);
  for (const time of await times.all()) {
    await expect(time.locator('strong')).toHaveText(/\d/);
    await expect(time).toContainText('00:42');
  }
});

test('calls spanning local dates retain a date on each card', async ({ page }) => {
  const previousDay = { ...linkedCall, id: 18, start_ms: linkedCall.start_ms - 86_400_000 };
  await openRecordings(page, { calls: [linkedCall, previousDay] });
  const dates = await page.evaluate((timestamps) => timestamps.map((timestamp) =>
    new Intl.DateTimeFormat(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
      .format(new Date(timestamp))), [linkedCall.start_ms, previousDay.start_ms]);
  expect(dates[0]).not.toBe(dates[1]);
  const cards = page.locator('.recordings-call');
  await expect(cards).toHaveCount(2);
  await expect(cards.first().locator('.recordings-call-time')).toContainText(dates[0]);
  await expect(cards.last().locator('.recordings-call-time')).toContainText(dates[1]);
});
