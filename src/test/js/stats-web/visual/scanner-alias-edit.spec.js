'use strict';

const { test, expect } = require('@playwright/test');
const { openAudioApp, liveCall } = require('./fixtures/audio-dock-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

test.setTimeout(15_000);

async function openScannerAliases(page, options = {}) {
  const audio = await openAudioApp(page, { view: 'scanner', ...options });
  const list = { alias_list_id: 1, name: 'County Public Safety', family: 'P25',
    new_alias_behavior: { recordable: true, scan_list_ids: [7], broadcast_configuration_ids: ['stream-1'] } };
  const aliases = new Map([[101, { alias_id: 101, alias_list_id: 1, name: 'Fire range',
    description: 'County fire operations', group: 'Fire', color: 0, recordable: false,
    scan_list_ids: [], broadcast_configuration_ids: [],
    matcher: { type: 'talkgroup_range', protocol: 'p25', variant: 'phase_1', minimum: 1200, maximum: 1300 } }]]);
  const calls = [liveCall, { ...liveCall, call_id: 'dock-live-2',
    audio_url: '/api/v1/calls/dock-live-2/audio', started_at_ms: liveCall.started_at_ms + 60_000,
    completed_at_ms: liveCall.completed_at_ms + 60_000 }]
    .map((call, index) => ({ ...call, source_alias: '', source_id: index ? options.nextSourceId || 30914 : 30914 }));
  const writes = [], contexts = [], presentations = [];
  await page.route('**/api/v1/admin/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname;
    const data = value => route.fulfill({ json: { data: value } });
    if (path === '/api/v1/admin/call-aliases/presentation') {
      const ids = url.searchParams.getAll('call_id');
      presentations.push(ids);
      return data({ calls: ids.map(call_id => ({ call_id,
        target_alias: aliases.get(101).name, target_description: aliases.get(101).description,
        target_group: aliases.get(101).group, source_alias: calls.find(call => call.call_id === call_id)?.source_id === 30914 ? aliases.get(102)?.name || '' : '',
        source_description: aliases.get(102)?.description || '', source_group: aliases.get(102)?.group || '',
        playback_target_label: aliases.get(101).name })) });
    }
    if (/^\/api\/v1\/admin\/call-aliases\//.test(path)) {
      const side = url.searchParams.get('side');
      contexts.push({ call: path.split('/').at(-1), side });
      return data({ available: true, alias_list_id: 1,
        alias_id: side === 'target' ? 101 : aliases.has(102) &&
          calls.find(call => call.call_id === path.split('/').at(-1))?.source_id === 30914 ? 102 : null,
        matcher: side === 'target' ? { type: 'talkgroup', protocol: 'p25', variant: 'phase_1', value: 1201 } :
          { type: 'radio', protocol: 'p25', variant: 'phase_1',
            value: calls.find(call => call.call_id === path.split('/').at(-1))?.source_id || 30914 } });
    }
    if (path === '/api/v1/admin/alias-lists') return data({ revision: 1, alias_lists: [list] });
    if (path === '/api/v1/admin/aliases/options') return data({ revision: 1, alias_list: list,
      scan_lists: [{ id: 7, name: 'County Calls', published: true }],
      streams: [{ configuration_id: 'stream-1', name: 'County Stream' }],
      matchers: [
        { type: 'talkgroup', protocol: 'p25', variant: 'phase_1', label: 'Talkgroup', fields: ['value'], minimum: 1, maximum: 65535 },
        { type: 'talkgroup_range', protocol: 'p25', variant: 'phase_1', label: 'Talkgroup range',
          fields: ['minimum', 'maximum'], minimum: 1, maximum: 65535 },
        { type: 'radio', protocol: 'p25', variant: 'phase_1', label: 'Radio', fields: ['value'], minimum: 1, maximum: 16777215 }
      ] });
    if (path === '/api/v1/admin/aliases' || /^\/api\/v1\/admin\/aliases\/\d+$/.test(path)) {
      const id = path === '/api/v1/admin/aliases' ? 102 : Number(path.split('/').at(-1));
      if (request.method() === 'GET') return data({ revision: 1, edit_revision: `alias-${id}-v1`, alias: aliases.get(id) });
      const body = request.postDataJSON();
      writes.push({ path, method: request.method(), body });
      aliases.set(id, { ...body.alias, alias_id: id });
      return data({ revision: 2, alias_list_id: 1, alias_ids: [id] });
    }
    return route.fallback();
  });
  await page.route('**/api/v1/calls/feed**', route => {
    const delivered = audio.feedDelivered;
    audio.feedDelivered = true;
    return route.fulfill({ json: { data: { cursor: '2', reset: false, calls: delivered ? [] : calls } } });
  });
  audio.feedDelivered = false;
  await page.reload();
  await page.locator('#scanner-play').click();
  await expect(page.locator('.scanner-call-title')).toHaveText('Fire Dispatch');
  await expect(page.locator('.scanner-up-next-list')).toContainText('Fire Dispatch');
  return { audio, aliases, writes, contexts, presentations };
}

async function sourceCount(page) {
  return page.evaluate(() => window.audioTest.liveSources.length);
}

test('Scanner pencil edits the matching range directly and refreshes retained calls without restarting playback', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const fixture = await openScannerAliases(page);
  const sources = await sourceCount(page);
  const button = page.getByRole('button', { name: 'Edit talkgroup alias', exact: true });
  await button.click();
  const dialog = page.getByRole('dialog', { name: 'Edit Fire range', exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog.locator('[name="aliasListId"]')).toHaveValue('1');
  await dialog.getByRole('textbox', { name: /^Alias name/ }).fill('County Fire Dispatch');
  await dialog.getByRole('tab', { name: 'Identifier', exact: true }).click();
  await expect(dialog.locator('[name="matcherType"]')).toHaveValue('talkgroup_range:p25:phase_1');
  await expect(dialog.locator('[name="matcher-minimum"]')).toHaveValue('1200');
  await expect(dialog.locator('[name="matcher-maximum"]')).toHaveValue('1300');
  await dialog.getByRole('button', { name: 'Save Changes', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page).toHaveURL(/view=scanner/);
  await expect(page.locator('.scanner-call-title')).toHaveText('County Fire Dispatch');
  await expect(page.locator('.scanner-up-next-list')).toContainText('County Fire Dispatch');
  expect(fixture.writes).toHaveLength(1);
  expect(fixture.writes[0]).toMatchObject({ method: 'PUT', path: '/api/v1/admin/aliases/101',
    body: { edit_revision: 'alias-101-v1', alias: {
      matcher: { type: 'talkgroup_range', protocol: 'p25', variant: 'phase_1', minimum: 1200, maximum: 1300 } } } });
  expect(fixture.presentations.at(-1)).toEqual(expect.arrayContaining(['dock-live-1', 'dock-live-2']));
  expect(await sourceCount(page)).toBe(sources);
  expect(await page.evaluate(() => window.audioTest.liveSources.filter(source => source.active).length)).toBe(1);
});

test('Scanner plus starts a blank named radio alias with identity and list defaults on a dark phone', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const fixture = await openScannerAliases(page, { theme: 'dark' });
  const sources = await sourceCount(page);
  await page.getByRole('button', { name: 'Add radio alias', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Add Alias to County Public Safety', exact: true });
  const name = dialog.getByRole('textbox', { name: /^Alias name/ });
  await expect(name).toHaveValue('');
  await expect(dialog.locator('[name="aliasListId"]')).toHaveValue('1');
  await expect(dialog.locator('[name="aliasListId"]')).toBeDisabled();
  await dialog.getByRole('tab', { name: 'Identifier', exact: true }).click();
  await expect(dialog.locator('[name="matcherType"]')).toHaveValue('radio:p25:phase_1');
  await expect(dialog.locator('[name="matcher-value"]')).toHaveValue('30914');
  await dialog.getByRole('tab', { name: 'Call Handling', exact: true }).click();
  await expect(dialog.getByRole('checkbox', { name: 'Record calls', exact: true })).toBeChecked();
  await expect(dialog.getByRole('checkbox', { name: 'County Calls', exact: true })).toBeChecked();
  await expect(dialog.getByRole('checkbox', { name: 'County Stream', exact: true })).toBeChecked();
  await expect(dialog).toContainText('Call Handling Defaults → New Aliases');
  await dialog.getByRole('tab', { name: 'Basics', exact: true }).click();
  const radioName = 'Engine Four Countywide Emergency Response and Special Operations';
  await name.fill(radioName);
  await dialog.getByRole('button', { name: 'Create Alias', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page).toHaveURL(/view=scanner/);
  const source = page.locator('.scanner-source-fields .scanner-field-link');
  await expect(source).toHaveText(radioName);
  await expect(source).toHaveCSS('font-size', '20px');
  await expect(page.locator('.scanner-up-next-list')).toContainText(radioName);
  await expect(page.getByRole('button', { name: 'Edit radio alias', exact: true })).toBeVisible();
  expect(fixture.writes).toHaveLength(1);
  expect(fixture.writes[0]).toMatchObject({ method: 'POST', path: '/api/v1/admin/aliases',
    body: { alias: { name: radioName, matcher: { type: 'radio', protocol: 'p25', variant: 'phase_1', value: 30914 },
      recordable: true, scan_list_ids: [7], broadcast_configuration_ids: ['stream-1'] } } });
  expect(await sourceCount(page)).toBe(sources);
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: testInfo.outputPath('scanner-alias-dark-phone.png'), fullPage: true });
});

test('Scanner leaves alias actions hidden without alias administration access', async ({ page }) => {
  const fixture = await openScannerAliases(page, { admin: false, capabilities: { 'admin-aliases': false } });
  await expect(page.locator('.scanner-alias-edit')).toHaveCount(0);
  expect(fixture.contexts).toHaveLength(0);
  expect(fixture.writes).toHaveLength(0);
  await expect(page.locator('.scanner-call-title')).toHaveText('Fire Dispatch');
});

test('Scanner saves the originally clicked radio while another call starts playing', async ({ page }) => {
  const fixture = await openScannerAliases(page, { nextSourceId: 30915 });
  await page.getByRole('button', { name: 'Add radio alias', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Add Alias to County Public Safety', exact: true });
  await expect(dialog).toBeVisible();
  await page.evaluate(() => window.audioTest.liveSources[0].onended());
  await expect.poll(() => fixture.contexts.some(context =>
    context.call === 'dock-live-2' && context.side === 'source')).toBe(true);
  await expect.poll(() => sourceCount(page)).toBe(2);
  const sources = await sourceCount(page);
  await dialog.getByRole('textbox', { name: /^Alias name/ }).fill('Original radio');
  await dialog.getByRole('button', { name: 'Create Alias', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  expect(fixture.writes[0].body.alias.matcher.value).toBe(30914);
  await expect.poll(() => fixture.presentations.length).toBe(1);
  await expect(page.locator('.scanner-source-fields')).not.toContainText('Original radio');
  expect(await sourceCount(page)).toBe(sources);
  await page.getByRole('button', { name: 'Replay last call', exact: true }).click();
  await expect(page.locator('.scanner-source-fields')).toContainText('Original radio');
});
