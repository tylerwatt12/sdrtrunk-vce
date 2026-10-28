const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const guideDismissalScope = 'channel-setup-fixture';
const accountFixtures = new WeakSet();

async function openFixturePage(page, url) {
  if (!accountFixtures.has(page)) {
    const { defaults } = await import(pathToFileURL(path.resolve(__dirname,
      '../../../../../stats-web/assets/core/preference-schema.js')).href);
    let preferences = structuredClone(defaults), revision = 1;
    const history = { version: 1, dismissed: { welcome: 1, 'channel-setup': 5 } };
    // Register after the case's broad API fixture so account state stays healthy.
    await page.route('**/api/v1/me/preferences', async route => {
      if (route.request().method() === 'PUT') {
        preferences = route.request().postDataJSON();
        revision++;
      }
      await route.fulfill({ json: { revision, preferences } });
    });
    await page.route('**/api/v1/me/tour-dismissals', async route => {
      if (route.request().method() === 'PUT') {
        for (const [id, version] of Object.entries(route.request().postDataJSON().dismissed))
          history.dismissed[id] = Math.max(history.dismissed[id] || 0, version);
      }
      await route.fulfill({ json: apiData(history) });
    });
    accountFixtures.add(page);
  }
  await page.goto(url);
}

const p25Phase1Profile = require(path.resolve(__dirname,
  '../../../../../src/main/resources/channel-protocols.json')).profiles
  .find((profile) => profile.id === 'p25-phase1');

function apiData(data) {
  return { data };
}

async function trackOrderHighlight(row) {
  await row.evaluate((element) => {
    element.orderHighlightEvents = [];
    let wasHighlighted = element.classList.contains('channel-order-row-moved');
    const observer = new MutationObserver(() => {
      const highlighted = element.classList.contains('channel-order-row-moved');
      if (highlighted === wasHighlighted) return;
      wasHighlighted = highlighted;
      const style = getComputedStyle(element.querySelector('td'));
      element.orderHighlightEvents.push({
        type: highlighted ? 'added' : 'removed',
        time: performance.now(),
        animationName: style.animationName,
        animationDuration: style.animationDuration,
        backgroundColor: style.backgroundColor
      });
      if (!highlighted) observer.disconnect();
    });
    observer.observe(element, { attributes: true, attributeFilter: ['class'] });
  });
}

function orderHighlightEvents(row) {
  return row.evaluate((element) => element.orderHighlightEvents || []);
}

function channelFixture(index) {
  const order = index <= 30 ? index : null;
  const protocol = index % 3 === 0 ? ['dmr', 'DMR'] :
    index % 3 === 1 ? ['p25-phase1', 'P25 Phase 1'] : ['nbfm', 'NBFM'];
  return {
    configuration_id: `channel-${String(index).padStart(2, '0')}`,
    protocol_id: protocol[0],
    protocol_label: protocol[1],
    channel_kind: index % 3 === 2 ? 'CONVENTIONAL' : 'TRUNKED',
    system: `System ${index % 4 + 1}`,
    site: `Site ${index % 6 + 1}`,
    name: `Channel ${String(index).padStart(2, '0')}`,
    frequencies_hz: [851_000_000 + index * 12_500],
    processing_state: index % 5 === 0 ? 'RUNNING' : 'STOPPED',
    auto_start_order: order,
    alias_list_id: index,
    alias_list_name: `Aliases ${index % 4 + 1}`,
    editable: true,
    restriction_message: null
  };
}

test('channel editor refreshes the voice-module override and renders the effective toggle state', async ({ page }) => {
  const { defaults: preferences } = await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href);
  let moduleLoaded = true;
  let protocolRequests = 0;
  const profile = {
    id: 'dmr',
    label: 'DMR',
    channel_kind: 'TRUNKED',
    alias_family: 'DMR',
    sections: [{
      id: 'protocol',
      label: 'Decoder',
      fields: [{
        path: 'settings.ignore_encrypted_calls',
        label: 'Skip encrypted traffic channels (performance)',
        type: 'boolean',
        default: false
      }]
    }]
  };
  const template = {
    protocol_id: 'dmr',
    name: 'DMR Channel',
    alias_list_id: 1,
    source: { frequencies_hz: [451012500] },
    settings: { ignore_encrypted_calls: true },
    frequency_map: [],
    event_logs: [],
    recorders: [],
    auxiliary_decoders: []
  };

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const respond = (data) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiData(data))
    });
    if(path === '/api/v1/auth/session') {
      await respond({
        configured: true,
        authenticated: true,
        username: 'channel-admin', guide_dismissal_scope: guideDismissalScope,
        tier: 'admin',
        primary: true,
        csrf_token: 'test-token',
        capabilities: { 'admin-channels': true }
      });
      return;
    }
    if(path === '/api/v1/admin/channels' && request.method() === 'GET') {
      await respond({ revision: 1, channels: [] });
      return;
    }
    if(path === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } });
      return;
    }
    if(path === '/api/v1/admin/channels/options') {
      await respond({ revision: 1, alias_lists: [{ id: 1, name: 'Default DMR', family: 'DMR' }], tuners: [] });
      return;
    }
    if(path === '/api/v1/admin/channels/protocols') {
      protocolRequests += 1;
      await respond({ voice_decryption_module_loaded: moduleLoaded, profiles: [profile] });
      return;
    }
    if(path === '/api/v1/admin/channels/protocols/dmr/template') {
      await respond(template);
      return;
    }
    await route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify({ error: { message: 'Not available in this browser contract' } })
    });
  });

  await openFixturePage(page, '/app.html?view=channel-setup');
  await expect(page.locator('.channel-admin-catalog')).toBeVisible();
  expect(protocolRequests).toBe(1);

  await page.getByRole('button', { name: 'New channel' }).click();
  await page.getByRole('dialog', { name: 'How would you like to create a channel?', exact: true })
    .getByRole('button', { name: 'Create a channel from scratch', exact: true }).click();
  const encryptedSkip = page.getByRole('checkbox', {
    name: 'Skip encrypted traffic channels (performance)'
  });
  await expect(encryptedSkip).toBeDisabled();
  await expect(encryptedSkip).not.toBeChecked();
  await expect(encryptedSkip.locator('xpath=..').locator('.ui-toggle-state')).toHaveText('Off');
  expect(protocolRequests).toBe(2);

  await page.getByRole('button', { name: 'Cancel' }).click();
  moduleLoaded = false;
  await page.getByRole('button', { name: 'New channel' }).click();
  await page.getByRole('dialog', { name: 'How would you like to create a channel?', exact: true })
    .getByRole('button', { name: 'Create a channel from scratch', exact: true }).click();
  await expect(encryptedSkip).toBeEnabled();
  await expect(encryptedSkip).toBeChecked();
  await expect(encryptedSkip.locator('xpath=..').locator('.ui-toggle-state')).toHaveText('On');
  expect(protocolRequests).toBe(3);
});

test('editing a channel opens and creates the selected Alias List without losing its draft', async ({ page }) => {
  const configurationId = '00000000-0000-0000-0000-000000000001';
  const profile = {
    id: 'nbfm', label: 'NBFM', channel_kind: 'CONVENTIONAL', alias_family: 'NBFM',
    sections: [{ id: 'general', label: 'General', fields: [
      { path: 'system', label: 'System', type: 'text' },
      { path: 'site', label: 'Site', type: 'text' },
      { path: 'name', label: 'Name', type: 'text' },
      { path: 'radioresolve_id', label: 'RadioResolve ID', type: 'text' },
      { path: 'alias_list_id', label: 'Alias List', type: 'dynamic_select', required: true }
    ] }]
  };
  const channel = {
    configuration_id: configurationId, protocol_id: 'nbfm', system: 'Test system', site: 'Test site',
    name: 'Test channel', radioresolve_id: '', alias_list_id: 1, alias_list_name: 'First list',
    source: {}, settings: {}, frequency_map: [], event_logs: [], recorders: [], auxiliary_decoders: []
  };
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    const data = path === '/api/v1/auth/session' ? {
      configured: true, authenticated: true, username: 'channel-admin', guide_dismissal_scope: guideDismissalScope, tier: 'admin', primary: true,
      csrf_token: 'test-token', capabilities: { 'admin-channels': true, 'admin-aliases': true }
    } : path === '/api/v1/admin/channels' ? {
      revision: 1, channels: [{ ...channel, processing_state: 'STOPPED', editable: true }]
    } : path === '/api/v1/admin/channels/options' ? {
      revision: 1, alias_lists: [
        { id: 1, name: 'First list', family: 'NBFM' },
        { id: 2, name: 'Second list', family: 'NBFM' }
      ], tuners: []
    } : path === '/api/v1/admin/channels/protocols' ? { profiles: [profile] } :
      path === `/api/v1/admin/channels/${configurationId}` ?
        { revision: 1, channel, processing_state: 'STOPPED' } : null;
    await route.fulfill({ status: data ? 200 : 404, contentType: 'application/json',
      body: JSON.stringify(data ? apiData(data) : { error: { message: 'Not available in this browser contract' } }) });
  });

  await openFixturePage(page, `/app.html?view=channel-setup&channel=${configurationId}`);
  const dialog = page.getByRole('dialog', { name: 'Edit Test channel' });
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  const edit = page.locator(`tr[data-id="${configurationId}"]`)
    .getByRole('button', { name: 'Test channel', exact: true });
  await edit.focus();
  await page.keyboard.press('Enter');
  await expect(dialog).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(edit).toBeFocused();
  await edit.click();
  await expect(dialog).toBeVisible();
  const select = dialog.getByLabel('Alias List', { exact: true });
  const link = dialog.getByRole('link', { name: 'Open Alias List' });
  await expect(link).toHaveAttribute('href', /view=aliases&list=1$/);
  await expect(link).toHaveAttribute('target', '_blank');
  await expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  const selectBox = await select.boundingBox();
  const linkBox = await link.boundingBox();
  const radioResolveBox = await dialog.getByLabel('RadioResolve ID').boundingBox();
  expect(linkBox.x).toBeGreaterThan(selectBox.x + selectBox.width);
  expect(linkBox.y).toBeGreaterThan(radioResolveBox.y);

  await select.selectOption('2');
  await expect(link).toHaveAttribute('href', /view=aliases&list=2$/);
  const popupPromise = page.waitForEvent('popup');
  await link.click();
  const popup = await popupPromise;
  await expect(popup).toHaveURL(/view=aliases&list=2$/);
  await expect(dialog).toBeVisible();
  await popup.close();

  await page.setViewportSize({ width: 390, height: 800 });
  const mobileSelectBox = await select.boundingBox();
  const mobileLinkBox = await link.boundingBox();
  expect(mobileLinkBox.y).toBeGreaterThan(mobileSelectBox.y + mobileSelectBox.height);
  expect(mobileLinkBox.x + mobileLinkBox.width).toBeLessThanOrEqual(390);

  let createdList = null;
  await page.route('**/api/v1/admin/alias-lists**', async (route) => {
    const request = route.request();
    if (request.method() === 'POST') createdList = request.postDataJSON();
    const data = request.method() === 'POST' ? { alias_list_id: 3, revision: 8 } : { revision: 7 };
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(apiData(data)) });
  });
  const draft = dialog.getByLabel('Name', { exact: true });
  await draft.fill('Unsaved channel name');
  await dialog.getByRole('button', { name: 'New list' }).click();
  const child = page.getByRole('dialog', { name: 'Create Alias List' });
  await expect(child).toBeVisible();
  await child.getByLabel('Name').fill('Local Analog');
  await child.getByRole('button', { name: 'Create and use' }).click();
  await expect(child).toHaveCount(0);
  await expect(dialog).toBeVisible();
  await expect(draft).toHaveValue('Unsaved channel name');
  await expect(select).toHaveValue('3');
  await expect(link).toHaveAttribute('href', /view=aliases&list=3$/);
  expect(createdList).toMatchObject({ name: 'Local Analog', family: 'nbfm' });
  expect(createdList).not.toHaveProperty('revision');

  await page.setViewportSize({ width: 1280, height: 900 });
  await openFixturePage(page, '/app.html?view=channel-setup');
  const aliasCell = page.locator(`.channel-admin-catalog tr[data-id="${configurationId}"] td[data-column="alias-list"]`);
  const aliasLink = aliasCell.getByRole('link', { name: 'First list' });
  await expect(aliasLink).toHaveAttribute('href', /view=aliases&list=1$/);
  await aliasLink.click();
  await expect(page).toHaveURL(/view=aliases&list=1$/);
});

async function installEditableChannelCatalog(page) {
  const remoteId = '11111111-1111-4111-8111-111111111111';
  const localId = '22222222-2222-4222-8222-222222222222';
  const senderId = '33333333-3333-4333-8333-333333333333';
  const feedId = '44444444-4444-4444-8444-444444444444';
  const settings = {
    modulation: 'C4FM', traffic_channel_pool_size: 12, ignore_data_calls: true,
    ignore_encrypted_calls: true, learn_announced_control_channels: false,
    use_bandplan_override: false
  };
  const remoteChannel = {
    configuration_id: remoteId, protocol_id: 'p25-phase1', system: 'Metro', site: 'North',
    name: 'Remote Control', radio_resolve_id: null, alias_list_id: 1,
    source: {
      frequencies_hz: [851_012_500], minimum_frequency_hz: null, maximum_frequency_hz: null,
      preferred_frequency_hz: null, preferred_tuner: null, rotation_delay_ms: null,
      source_type: 'REMOTE', sender_id: senderId, feed_id: feedId
    },
    settings, frequency_map: [], event_logs: ['CALL_EVENT'], recorders: ['BASEBAND'],
    auxiliary_decoders: [], observed: { learned_control_frequencies_hz: [], p25_site_identity: {} }
  };
  const localChannel = {
    ...remoteChannel, configuration_id: localId, name: 'Local Control',
    source: {
      frequencies_hz: [852_012_500], minimum_frequency_hz: null, maximum_frequency_hz: null,
      preferred_frequency_hz: null, preferred_tuner: null, rotation_delay_ms: 500,
      source_type: 'TUNER_MULTIPLE_FREQUENCIES', sender_id: null, feed_id: null
    },
    recorders: []
  };
  const channels = new Map([[remoteId, remoteChannel], [localId, localChannel]]);
  let savedRemote = null;
  let entryRequests = 0, orderMoves = 0, entryHold = null, releaseEntry = null;

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const respond = (data) => route.fulfill({ json: apiData(data) });
    if (pathname === '/api/v1/auth/session') {
      await respond({ configured: true, authenticated: true, username: 'channel-admin', guide_dismissal_scope: guideDismissalScope, tier: 'admin',
        primary: true, csrf_token: 'test-token',
        capabilities: { 'admin-channels': true, 'admin-aliases': true, 'admin-settings': true } });
    } else if (pathname === '/api/v1/admin/channels' && request.method() === 'GET') {
      await respond({ revision: 1, channels: [...channels.values()].map((channel) => ({
        ...channel, frequencies_hz: channel.source.frequencies_hz, protocol_label: 'P25 Phase 1',
        channel_kind: 'TRUNKED', alias_list_name: 'Default P25', processing_state: 'STOPPED',
        auto_start_order: 1, editable: channel.editable !== false,
        remote_origin: channel.source.source_type === 'REMOTE' ? { remote: true } : null
      })) });
    } else if (pathname === '/api/v1/admin/channels/options') {
      await respond({ revision: 1, alias_lists: [{ id: 1, name: 'Default P25', family: 'P25' }],
        tuners: ['Test tuner'] });
    } else if (pathname === '/api/v1/admin/channels/protocols') {
      await respond({ voice_decryption_module_loaded: false, profiles: [p25Phase1Profile] });
    } else if (pathname === `/api/v1/admin/channels/${remoteId}/auto-start/move`) {
      orderMoves++;
      await respond({ revision: 1, configuration_ids: [remoteId] });
    } else if (pathname === `/api/v1/admin/channels/${remoteId}` && request.method() === 'PUT') {
      savedRemote = request.postDataJSON();
      channels.set(remoteId, { ...remoteChannel, ...savedRemote });
      await respond({ revision: 2, configuration_ids: [remoteId] });
    } else if (pathname === `/api/v1/admin/channels/${remoteId}` ||
        pathname === `/api/v1/admin/channels/${localId}`) {
      entryRequests++;
      if (entryHold) await entryHold;
      await respond({ revision: 1, channel: channels.get(pathname.split('/').at(-1)),
        processing_state: 'STOPPED' });
    } else {
      await route.fulfill({ status: 404, json: { error: { message: 'Not available in this browser contract' } } });
    }
  });

  return { remoteId, localId, senderId, feedId, channels, settings,
    get savedRemote() { return savedRemote; },
    get entryRequests() { return entryRequests; },
    get orderMoves() { return orderMoves; },
    holdEntry() { entryHold = new Promise(resolve => { releaseEntry = resolve; }); },
    releaseEntry() { releaseEntry?.(); entryHold = null; }
  };
}

test('remote P25 editor shows applicable controls and preserves its linked source on save', async ({ page }) => {
  const fixture = await installEditableChannelCatalog(page);
  const { remoteId, localId, senderId, feedId, settings } = fixture;
  await openFixturePage(page, '/app.html?view=channel-setup');
  await page.locator(`tr[data-id="${remoteId}"]`).getByRole('button', { name: 'Remote Control', exact: true }).click();
  const remoteDialog = page.getByRole('dialog', { name: 'Edit Remote Control' });
  await expect(remoteDialog).toBeVisible();
  await expect(remoteDialog.getByLabel('Maximum traffic channels')).toHaveValue('12');
  await expect(remoteDialog.getByLabel('Use P25 bandplan override')).toBeVisible();
  await expect(remoteDialog.getByRole('group', { name: 'Remote source' }))
    .toContainText('The sending VCE controls tuning');
  for (const label of ['Preferred Tuner', 'Frequency rotation delay', 'Modulation',
    'Learn announced control channels', 'Ignore data calls',
    'Skip encrypted traffic channels (performance)', 'Recordings']) {
    await expect(remoteDialog.getByLabel(label, { exact: true })).toHaveCount(0);
  }
  await remoteDialog.locator('details[data-channel-section="output"] > summary').click();
  await expect(remoteDialog.locator('[data-channel-path="event_logs"]')).toBeVisible();
  await remoteDialog.getByLabel('Name', { exact: true }).fill('Remote Control Renamed');
  await remoteDialog.getByRole('button', { name: 'Save changes' }).click();
  await expect(remoteDialog).toHaveCount(0);
  await expect(page.locator(`tr[data-id="${remoteId}"] .channel-edit-name`)).toBeFocused();
  expect(fixture.savedRemote).not.toBeNull();
  expect(fixture.savedRemote.name).toBe('Remote Control Renamed');
  expect(fixture.savedRemote.source).toMatchObject({ source_type: 'REMOTE', sender_id: senderId,
    feed_id: feedId, frequencies_hz: [851_012_500] });
  expect(fixture.savedRemote.settings).toMatchObject(settings);
  expect(fixture.savedRemote.event_logs).toEqual(['CALL_EVENT']);
  expect(fixture.savedRemote.recorders).toEqual(['BASEBAND'],
    'editing a remote channel must not erase hidden saved recorder choices');

  await openFixturePage(page, `/app.html?view=channel-setup&channel=${localId}`);
  const localDialog = page.getByRole('dialog', { name: 'Edit Local Control' });
  await expect(localDialog).toBeVisible();
  await expect(localDialog.getByLabel('Preferred Tuner')).toBeVisible();
  await expect(localDialog.getByLabel('Frequency rotation delay')).toBeVisible();
  await expect(localDialog.getByLabel('Modulation')).toBeVisible();
  await expect(localDialog.getByLabel('Learn announced control channels')).toBeVisible();
  await expect(localDialog.getByLabel('Ignore data calls')).toBeVisible();
  await expect(localDialog.getByLabel('Skip encrypted traffic channels (performance)')).toBeVisible();
  await localDialog.locator('details[data-channel-section="output"] > summary').click();
  await expect(localDialog.locator('[data-channel-path="recorders"]')).toBeVisible();
});

for (const width of [1280, 390]) {
  test(`editable channel row space opens its editor without stealing modal focus at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const fixture = await installEditableChannelCatalog(page);
    const readOnlyId = '55555555-5555-4555-8555-555555555555';
    fixture.channels.set(readOnlyId, { ...fixture.channels.get(fixture.localId),
      configuration_id: readOnlyId, name: 'Read-only Control', editable: false });
    await openFixturePage(page, '/app.html?view=channel-setup');
    const row = page.locator(`tr[data-id="${fixture.remoteId}"]`);
    const dialogs = page.getByRole('dialog');
    const select = row.getByRole('checkbox', { name: 'Select Remote Control', exact: true });
    await select.check();
    await expect(dialogs).toHaveCount(0);
    await select.uncheck();
    const rowBeforeMove = await row.elementHandle();
    await row.getByRole('button', { name: 'Start earlier', exact: true }).click();
    await expect.poll(() => fixture.orderMoves).toBe(1);
    await expect.poll(() => rowBeforeMove.evaluate(element => element.isConnected)).toBe(false);
    await expect(dialogs).toHaveCount(0);
    expect(fixture.entryRequests).toBe(0);

    const clickRowSpace = async targetRow => {
      const cell = targetRow.locator('td[data-column="name"]');
      await expect(cell).toBeVisible();
      const point = await cell.evaluate(element => {
        const box = element.getBoundingClientRect();
        const x = box.width - 3, y = box.height - 3;
        const hit = document.elementFromPoint(box.left + x, box.top + y);
        return { x, y, blank: element.contains(hit) && !hit.closest('a, button, input, select, textarea, label') };
      });
      expect(point.blank, 'the visible cell corner is outside nested controls').toBe(true);
      await cell.click({ position: { x: point.x, y: point.y } });
    };
    fixture.holdEntry();
    await clickRowSpace(row);
    const loadingDialog = page.getByRole('dialog', { name: 'Edit Channel', exact: true });
    await expect(loadingDialog).toBeVisible();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    expect(await loadingDialog.evaluate(element => element.contains(document.activeElement)),
      'row activation must not focus the background after opening the modal').toBe(true);
    await page.keyboard.press('Tab');
    expect(await loadingDialog.evaluate(element => element.contains(document.activeElement))).toBe(true);
    fixture.releaseEntry();
    const editor = page.getByRole('dialog', { name: 'Edit Remote Control', exact: true });
    await expect(editor).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialogs).toHaveCount(0);
    expect(await row.evaluate(element => element.contains(document.activeElement))).toBe(true);

    for (const key of ['Enter', ' ']) {
      await row.focus();
      await row.press(key);
      await expect(editor).toBeVisible();
      await page.keyboard.press('Shift+Tab');
      expect(await editor.evaluate(element => element.contains(document.activeElement))).toBe(true);
      await page.keyboard.press('Escape');
      await expect(row).toBeFocused();
    }
    const entries = fixture.entryRequests;
    const readOnlyRow = page.locator(`tr[data-id="${readOnlyId}"]`);
    await clickRowSpace(readOnlyRow);
    await expect(dialogs).toHaveCount(0);
    await expect(readOnlyRow).toBeFocused();
    expect(fixture.entryRequests).toBe(entries);
    await row.getByRole('link', { name: 'Default P25', exact: true }).click();
    await expect(page).toHaveURL(/view=aliases&list=1$/);
    await expect(dialogs).toHaveCount(0);
    expect(fixture.entryRequests).toBe(entries);
  });
}

test('bulk Clone is unavailable whenever the selection includes a remote channel', async ({ page }) => {
  const local = { ...channelFixture(1), name: 'Local Control', remote_origin: null };
  const remote = { ...channelFixture(4), name: 'Remote Control', remote_origin: { remote: true } };
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    const respond = (data) => route.fulfill({ json: apiData(data) });
    if (pathname === '/api/v1/auth/session') {
      await respond({ configured: true, authenticated: true, username: 'channel-admin', guide_dismissal_scope: guideDismissalScope, tier: 'admin',
        primary: true, csrf_token: 'test-token', capabilities: { 'admin-channels': true } });
    } else if (pathname === '/api/v1/admin/channels') {
      await respond({ revision: 1, channels: [local, remote] });
    } else if (pathname === '/api/v1/admin/channels/options') {
      await respond({ revision: 1, alias_lists: [], tuners: [] });
    } else if (pathname === '/api/v1/admin/channels/protocols') {
      await respond({ profiles: [] });
    } else {
      await route.fulfill({ status: 404, json: { error: { message: 'Not available in this browser contract' } } });
    }
  });

  await openFixturePage(page, '/app.html?view=channel-setup');
  const clone = page.locator('.channel-selection-bar').getByRole('button', { name: 'Clone' });
  await page.getByRole('checkbox', { name: 'Select Local Control' }).check();
  await expect(clone).toBeEnabled();
  await page.getByRole('checkbox', { name: 'Select Remote Control' }).check();
  await expect(clone).toBeDisabled();
  await expect(clone).toHaveAttribute('data-ui-hint', 'Remote channels cannot be cloned.');

  await page.getByRole('searchbox', { name: 'Search channels, systems, protocols, frequencies, or alias lists' })
    .fill('Local Control');
  await expect(clone).toBeDisabled();
  await page.getByRole('searchbox', { name: 'Search channels, systems, protocols, frequencies, or alias lists' })
    .fill('');
  await page.getByRole('checkbox', { name: 'Select Remote Control' }).uncheck();
  await expect(clone).toBeEnabled();
});

test('bulk disable and stop keep old channels off when enabling a new startup channel', async ({ page }) => {
  await page.clock.install();
  let revision = 40;
  const channels = Array.from({ length: 5 }, (_, index) => ({
    ...channelFixture(index + 1),
    protocol_id: 'p25-phase1', protocol_label: 'P25 Phase 1', channel_kind: 'TRUNKED',
    auto_start_order: index < 4 ? index + 1 : null,
    processing_state: 'RUNNING'
  }));
  const ids = channels.map((channel) => channel.configuration_id);
  const mutations = [];
  let catalogRequests = 0;
  let delayedPollStarted = false;
  let releasePoll;
  const delayedPoll = new Promise((resolve) => { releasePoll = resolve; });

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const respond = (data) => route.fulfill({ json: apiData(data) });
    if (pathname === '/api/v1/auth/session') {
      await respond({ configured: true, authenticated: true, username: 'channel-admin',
        guide_dismissal_scope: guideDismissalScope, tier: 'admin', primary: true,
        csrf_token: 'test-token', capabilities: { 'admin-channels': true } });
    } else if (pathname === '/api/v1/admin/channels' && request.method() === 'GET') {
      const snapshot = structuredClone({ revision, channels });
      catalogRequests += 1;
      if (catalogRequests === 2) {
        delayedPollStarted = true;
        await delayedPoll;
      }
      await respond(snapshot);
    } else if (pathname === '/api/v1/admin/channels/options') {
      await respond({ revision, alias_lists: [], tuners: [] });
    } else if (pathname === '/api/v1/admin/channels/protocols') {
      await respond({ profiles: [] });
    } else if (pathname === '/api/v1/admin/channels/actions') {
      const body = request.postDataJSON();
      mutations.push(body);
      expect(body.revision).toBe(revision);
      expect([...body.configuration_ids].sort()).toEqual([...ids].sort());
      if (body.action === 'DISABLE_AUTO_START') {
        channels.forEach((channel) => { channel.auto_start_order = null; });
        revision += 1;
        await respond({ revision, configuration_ids: ids, affected: 4 });
      } else {
        expect(body.action).toBe('STOP');
        channels.forEach((channel) => { channel.processing_state = 'STOPPED'; });
        await respond({ revision, results: ids.map((id) => ({
          configuration_id: id, success: true, processing_state: 'STOPPED'
        })) });
      }
    } else if (pathname === `/api/v1/admin/channels/${ids[4]}/auto-start/move`) {
      const body = request.postDataJSON();
      mutations.push(body);
      expect(body).toEqual({ revision, direction: 'EARLIER' });
      expect(channels.every((channel) => channel.auto_start_order == null)).toBe(true);
      channels[4].auto_start_order = 1;
      revision += 1;
      await respond({ revision, configuration_ids: [ids[4]], affected: 1 });
    } else {
      await route.fulfill({ status: 404, json: { error: { message: 'Not available in this browser contract' } } });
    }
  });

  await openFixturePage(page, '/app.html?view=channel-setup');
  const catalog = page.locator('.channel-admin-catalog');
  await expect(catalog).toBeVisible();
  await page.clock.fastForward(5_000);
  await expect.poll(() => delayedPollStarted).toBe(true);

  await page.getByRole('checkbox', { name: 'Select all matching channels' }).check();
  await page.getByRole('button', { name: 'Disable auto-start', exact: true }).click();
  await expect(catalog.locator('.channel-setup-summary')).toContainText('0 auto start');
  await page.getByRole('checkbox', { name: 'Select all matching channels' }).check();
  await page.getByRole('button', { name: 'Stop', exact: true }).click();
  await expect(catalog.locator('.channel-setup-summary')).toContainText('0 running');

  // The response captured before bulk disable arrives after both actions have finished.
  const staleResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname === '/api/v1/admin/channels' &&
    response.request().method() === 'GET');
  releasePoll();
  await (await staleResponse).finished();
  await page.getByRole('button', { name: 'Startup order', exact: true }).click();
  await expect(catalog.locator('.channel-order-number')).toHaveText(['Off', 'Off', 'Off', 'Off', 'Off']);
  await catalog.locator(`tr[data-id="${ids[4]}"]`)
    .getByRole('button', { name: 'Enable auto start at the end', exact: true }).click();
  await expect(catalog.locator('.channel-setup-summary')).toContainText('1 auto start');
  await expect(catalog.locator(`tr[data-id="${ids[4]}"] .channel-order-number`)).toHaveText('1');
  for (const id of ids.slice(0, 4)) {
    await expect(catalog.locator(`tr[data-id="${id}"] .channel-order-number`)).toHaveText('Off');
  }
  expect(mutations).toEqual([
    { revision: 40, action: 'DISABLE_AUTO_START', configuration_ids: ids },
    { revision: 41, action: 'STOP', configuration_ids: ids },
    { revision: 41, direction: 'EARLIER' }
  ]);
});

test('channel refresh stays unavailable until bulk startup changes are verified', async ({ page }) => {
  let revision = 40;
  const channel = channelFixture(1);
  let mutationStarted = false;
  let verificationStarted = false;
  let catalogRequests = 0;
  let releaseMutation;
  let releaseVerification;
  const pendingMutation = new Promise((resolve) => { releaseMutation = resolve; });
  const pendingVerification = new Promise((resolve) => { releaseVerification = resolve; });

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const respond = (data) => route.fulfill({ json: apiData(data) });
    if (pathname === '/api/v1/auth/session') {
      await respond({ configured: true, authenticated: true, username: 'channel-admin',
        guide_dismissal_scope: guideDismissalScope, tier: 'admin', primary: true,
        csrf_token: 'test-token', capabilities: { 'admin-channels': true } });
    } else if (pathname === '/api/v1/admin/channels' && request.method() === 'GET') {
      catalogRequests += 1;
      if (revision > 40) {
        verificationStarted = true;
        await pendingVerification;
      }
      await respond({ revision, channels: [channel] });
    } else if (pathname === '/api/v1/admin/channels/options') {
      await respond({ revision, alias_lists: [], tuners: [] });
    } else if (pathname === '/api/v1/admin/channels/protocols') {
      await respond({ profiles: [] });
    } else if (pathname === '/api/v1/admin/channels/actions') {
      expect(request.postDataJSON()).toEqual({ revision: 40, action: 'DISABLE_AUTO_START',
        configuration_ids: [channel.configuration_id] });
      mutationStarted = true;
      await pendingMutation;
      channel.auto_start_order = null;
      revision += 1;
      await respond({ revision, configuration_ids: [channel.configuration_id], affected: 1 });
    } else {
      await route.fulfill({ status: 404, json: { error: { message: 'Not available in this browser contract' } } });
    }
  });

  try {
    await openFixturePage(page, '/app.html?view=channel-setup');
    const catalog = page.locator('.channel-admin-catalog');
    await expect(catalog).toBeVisible();
    const originalCatalog = await catalog.elementHandle();
    const refresh = page.getByRole('button', { name: 'Refresh channels', exact: true });
    await expect(refresh).toBeEnabled();
    await page.getByRole('checkbox', { name: 'Select all matching channels' }).check();
    await page.getByRole('button', { name: 'Disable auto-start', exact: true }).click();
    await expect.poll(() => mutationStarted).toBe(true);
    await expect(refresh).toBeVisible();
    await expect(refresh).toBeDisabled();

    // The catalog's capture guard also rejects clicks dispatched while the button is locked.
    await refresh.dispatchEvent('click');
    expect(await originalCatalog.evaluate((element) => element.isConnected)).toBe(true);
    expect(catalogRequests).toBe(1);

    releaseMutation();
    await expect.poll(() => verificationStarted).toBe(true);
    // A full reload may replace the locked button with a loading view during verification.
    await expect(page.locator('button[aria-label="Refresh channels"]:enabled')).toHaveCount(0);
    releaseVerification();
    await expect(catalog.locator('.channel-setup-summary')).toContainText('0 auto start');
    await expect(refresh).toBeEnabled();
    expect(catalogRequests).toBe(2);
  } finally {
    releaseMutation();
    releaseVerification();
  }
});

test('startup-order view moves keyed rows without replacing the page or losing position', async ({ page }) => {
  await page.setViewportSize({ width: 1100, height: 720 });
  let revision = 40;
  const channels = Array.from({ length: 60 }, (_, index) => channelFixture(index + 1));
  let moveRequest = null;
  let delayMoveResponse = null;

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const respond = (data, status = 200) => route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify(data)
    });
    if(path === '/api/v1/auth/session') {
      await respond(apiData({
        configured: true,
        authenticated: true,
        username: 'channel-admin', guide_dismissal_scope: guideDismissalScope,
        tier: 'admin',
        primary: true,
        csrf_token: 'test-token',
        capabilities: { 'admin-channels': true }
      }));
      return;
    }
    if(path === '/api/v1/admin/channels' && request.method() === 'GET') {
      await respond(apiData({ revision, channels }));
      return;
    }
    if(path === '/api/v1/admin/channels/protocols') {
      await respond(apiData({ revision, protocols: [] }));
      return;
    }
    if(path === '/api/v1/admin/channels/options') {
      await respond(apiData({ revision, alias_lists: [], tuners: [] }));
      return;
    }
    const move = path.match(/^\/api\/v1\/admin\/channels\/([^/]+)\/auto-start\/move$/);
    if(move && request.method() === 'POST') {
      moveRequest = request.postDataJSON();
      if(delayMoveResponse) await delayMoveResponse;
      const id = decodeURIComponent(move[1]);
      const enabled = channels.filter((row) => row.auto_start_order != null)
        .sort((left, right) => left.auto_start_order - right.auto_start_order);
      const position = enabled.findIndex((row) => row.configuration_id === id);
      let affected = 0;
      if(moveRequest.direction === 'EARLIER' && position > 0) {
        const previous = enabled[position - 1];
        const current = enabled[position];
        [previous.auto_start_order, current.auto_start_order] =
          [current.auto_start_order, previous.auto_start_order];
        affected = 2;
      }
      revision += 1;
      await respond(apiData({ revision, configuration_ids: [id], affected }));
      return;
    }
    await respond({ error: { message: 'Not available in this browser contract' } }, 404);
  });

  await openFixturePage(page, '/app.html?view=channel-setup');
  const catalog = page.locator('.channel-admin-catalog');
  await expect(catalog).toBeVisible();
  const restrictedAliasCell = catalog.locator('tr[data-id="channel-01"] td[data-column="alias-list"]');
  await expect(restrictedAliasCell).toHaveText('Aliases 2');
  await expect(restrictedAliasCell.locator('a')).toHaveCount(0);
  const startupOrder = page.getByRole('button', { name: 'Startup order', exact: true });
  await expect(startupOrder).toHaveAttribute('aria-pressed', 'false');
  await expect(page.getByRole('button', { name: 'Grouped', exact: true })).toHaveCount(0);
  await expect(catalog.locator('.table-row-group-disclosure')).toHaveCount(1);
  await expect(catalog.locator('.table-row-group-label')).toHaveText(['P25 Trunked']);

  await page.getByRole('button', { name: 'Startup order', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Startup order', exact: true }))
    .toHaveAttribute('aria-pressed', 'true');
  await expect(catalog.locator('.table-row-group-disclosure')).toHaveCount(0);
  await expect(catalog.locator('tbody tr[data-id]')).toHaveCount(20);
  await expect(catalog.locator('tbody tr[data-id]').first()).toHaveAttribute('data-id', 'channel-01');
  await expect(catalog.locator('tbody tr[data-id]').last()).toHaveAttribute('data-id', 'channel-20');
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(catalog.locator('tbody tr[data-id]').first()).toHaveAttribute('data-id', 'channel-21');
  await expect(catalog.locator('tbody tr[data-id]').last()).toHaveAttribute('data-id', 'channel-40');
  await page.getByRole('button', { name: 'Previous', exact: true }).click();

  const search = page.getByLabel('Search channels, systems, protocols, frequencies, or alias lists');
  await search.fill('Channel 2');
  const targetRow = catalog.locator('tr[data-id="channel-22"]');
  const targetHandle = await targetRow.elementHandle();
  const catalogHandle = await catalog.elementHandle();
  const earlier = targetRow.getByRole('button', { name: 'Start earlier' });
  await trackOrderHighlight(targetRow);
  await earlier.scrollIntoViewIfNeeded();
  await earlier.focus();
  const beforeScroll = await page.evaluate(() => ({ x: window.scrollX, y: window.scrollY }));
  await earlier.click();

  await expect.poll(() => moveRequest).toEqual({ revision: 40, direction: 'EARLIER' });
  await expect(catalog.locator('tbody tr[data-id]').nth(1)).toHaveAttribute('data-id', 'channel-22');
  await expect(catalog.locator('tbody tr[data-id]').nth(2)).toHaveAttribute('data-id', 'channel-21');
  await expect.poll(async () => (await orderHighlightEvents(targetRow))[0] || null).toMatchObject({
    type: 'added',
    animationName: 'channel-order-row-highlight',
    animationDuration: '1s'
  });
  await search.evaluate((input) => input.dispatchEvent(new Event('input', { bubbles: true })));
  await expect(targetRow).toHaveClass(/channel-order-row-moved/);
  await expect(targetRow.locator('.channel-order-number')).toHaveText('21');
  await expect(targetRow.getByRole('button', { name: 'Start earlier' })).toBeFocused();
  await expect(search).toHaveValue('Channel 2');
  await expect(page.getByRole('button', { name: 'Startup order', exact: true }))
    .toHaveAttribute('aria-pressed', 'true');

  const retained = await page.evaluate(({ root, row }) => ({
    root: root === document.querySelector('.channel-admin-catalog'),
    row: row === document.querySelector('tr[data-id="channel-22"]'),
    scroll: { x: window.scrollX, y: window.scrollY }
  }), { root: catalogHandle, row: targetHandle });
  expect(retained.root).toBe(true);
  expect(retained.row).toBe(true);
  expect(Math.abs(retained.scroll.x - beforeScroll.x)).toBeLessThanOrEqual(1);
  expect(Math.abs(retained.scroll.y - beforeScroll.y)).toBeLessThanOrEqual(1);
  await expect.poll(async () => (await orderHighlightEvents(targetRow)).map((event) => event.type))
    .toEqual(['added', 'removed']);
  const highlightEvents = await orderHighlightEvents(targetRow);
  expect(highlightEvents[1].time - highlightEvents[0].time).toBeGreaterThanOrEqual(900);

  moveRequest = null;
  let releaseMoveResponse;
  delayMoveResponse = new Promise((resolve) => { releaseMoveResponse = resolve; });
  const delayedRow = catalog.locator('tr[data-id="channel-23"]');
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await trackOrderHighlight(delayedRow);
  await delayedRow.getByRole('button', { name: 'Start earlier' }).focus();
  await delayedRow.getByRole('button', { name: 'Start earlier' }).click();
  await expect.poll(() => moveRequest).toEqual({ revision: 41, direction: 'EARLIER' });
  await search.focus();
  await expect(search).toBeFocused();
  releaseMoveResponse();
  delayMoveResponse = null;
  await expect(delayedRow.locator('.channel-order-number')).toHaveText('22');
  await expect(search).toBeFocused();
  await expect.poll(async () => (await orderHighlightEvents(delayedRow))[0] || null).toMatchObject({
    type: 'added',
    animationName: 'channel-order-row-highlight',
    animationDuration: '1s'
  });
  expect((await orderHighlightEvents(delayedRow))[0].backgroundColor).not.toBe('rgba(0, 0, 0, 0)');
  await expect.poll(async () => (await orderHighlightEvents(delayedRow)).map((event) => event.type))
    .toEqual(['added', 'removed']);

  moveRequest = null;
  await search.fill('');
  const firstRow = catalog.locator('tr[data-id="channel-01"]');
  await trackOrderHighlight(firstRow);
  await firstRow.getByRole('button', { name: 'Start earlier' }).click();
  await expect.poll(() => moveRequest).toEqual({ revision: 42, direction: 'EARLIER' });
  await expect(firstRow.getByRole('button', { name: 'Start earlier' })).toBeEnabled();
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  expect(await orderHighlightEvents(firstRow)).toEqual([]);
});
