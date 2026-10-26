'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function installEditor(page, query = 'list=1&aliasTab=configure', options = {}) {
  const preferences = structuredClone((await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/core/preference-schema.js')).href)).defaults);
  await page.addInitScript(() => {
    for (const id of ['welcome', 'channel-setup', 'aliases']) {
      localStorage.setItem(`sdrtrunk-vce-guide:v1:alias-admin:alias-workspace-fixture:${id}:${id === 'aliases' ? 5 : id === 'channel-setup' ? 4 : 1}`, '1');
    }
  });
  const lists = [
    { alias_list_id: 1, name: 'County', family: 'P25', assigned_channel_count: 1,
      new_alias_behavior: { recordable: true, scan_list_ids: [], broadcast_configuration_ids: [] } },
    { alias_list_id: 2, name: 'City', family: 'P25', assigned_channel_count: 0 }
  ];
  const aliases = [
    { alias_id: 101, alias_list_id: 1, name: 'Dispatch', description: 'Primary', group: 'Fire' },
    { alias_id: 102, alias_list_id: 1, name: 'Fireground', description: 'Tactical', group: 'Fire' },
    { alias_id: 103, alias_list_id: 1, name: 'Medic', description: 'Medical', group: 'Medical' },
    { alias_id: 201, alias_list_id: 2, name: 'City Dispatch', description: '', group: 'City' }
  ].map((row, index) => ({ ...row, recordable: true, color: 0,
    matcher_type: 'TALKGROUP', identifier_display: `TG ${1000 + index}`,
    matcher: { type: 'talkgroup', protocol: 'p25', variant: 'phase_1', value: 1000 + index } }));
  const observed = options.observed || [1, 2].map((system) => ({
    protocol: 'p25', protocol_variant: 'phase_1', topology: 'TRUNKED', group_identity_id: 1201,
    native_id: 1201, group_identity_kind: 'talkgroup', group_identity_kind_code: 1,
    match_kind: 'none', promotion_supported: true,
    radio_system_key: `p25:0000${system}:00${system}`, identity_key: `v1-g-0000${system}-00${system}-1201`,
    system_name: `County System ${system}`, logical_call_count: 4, signaling_observation_count: 2,
    entity_ref: { kind: 'talkgroup', radio_system_key: `p25:0000${system}:00${system}`,
      identity_key: `v1-g-0000${system}-00${system}-1201` }
  }));
  const requests = [], writes = [], held = [];
  let revision = 1, failNext = false, holdNext = false;
  const countLists = () => lists.map((list) => ({ ...list,
    alias_count: aliases.filter((row) => row.alias_list_id === list.alias_list_id).length }));
  let documentRequests = 0;
  page.on('request', (request) => { if (request.isNavigationRequest()) documentRequests += 1; });
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname;
    const send = (data) => route.fulfill({ json: { data } });
    if (path === '/api/v1/auth/session') return send({ configured: true, authenticated: true,
      username: 'alias-admin', tier: 'admin', primary: true, csrf_token: 'fixture', guide_dismissal_scope: 'alias-workspace-fixture',
      capabilities: { 'admin-aliases': true, 'admin-channels': true, radio: options.radio !== false, 'csv-export': true } });
    if (path === '/api/v1/me/tour-dismissals') return send({ version: 1,
      dismissed: { welcome: 1, 'channel-setup': 4, aliases: 5 } });
    if (path === '/api/v1/me/preferences') return route.fulfill({ json: { revision: 1, preferences } });
    if (request.method() !== 'GET' && path.startsWith('/api/v1/admin/aliases')) {
      const body = request.postDataJSON();
      writes.push({ path, method: request.method(), body });
      if (request.method() !== 'POST' && body.edit_revision !== `alias-${path.split('/').at(-1)}-${revision}`) return route.fulfill({ status: 409,
        json: { error: { code: 'stale_revision', message: 'Reload current values.' } } });
      if (request.method() === 'DELETE') {
        const index = aliases.findIndex((row) => row.alias_id === Number(path.split('/').at(-1)));
        aliases.splice(index, 1);
      } else if (request.method() === 'POST') {
        aliases.push({ ...body.alias, alias_id: 104, matcher_type: 'TALKGROUP', identifier_display: 'TG 1201' });
        observed.splice(0);
      } else Object.assign(aliases.find((row) => row.alias_id === Number(path.split('/').at(-1))), body.alias);
      revision += 1;
      return send({ revision, alias_list_id: body.alias?.alias_list_id || 1 });
    }
    if (path === '/api/v1/alias-lists') return send({ rows: countLists(), offset: 0, limit: 500, has_more: false });
    if (path === '/api/v1/admin/alias-lists') return send({ revision, alias_lists: countLists() });
    if (path === '/api/v1/admin/channels') return send({ revision, channels: [
      { configuration_id: '10000000-0000-4000-8000-000000000001', alias_list_id: 1, name: 'County Control' }
    ] });
    if (path === '/api/v1/admin/aliases/options') {
      const list = countLists().find((row) => row.alias_list_id === Number(url.searchParams.get('alias_list_id')));
      return send({ revision, alias_list: list, scan_lists: [], streams: [],
        group_names: [...new Set(aliases.filter((row) => row.alias_list_id === list?.alias_list_id)
          .map((row) => row.group))], matchers: [{ type: 'talkgroup', protocol: 'p25', variant: 'phase_1',
          label: 'P25 Talkgroup', fields: ['value'], minimum: 1, maximum: 65534 }] });
    }
    if (/\/api\/v1\/admin\/aliases\/\d+$/.test(path)) return send({ revision, edit_revision: `alias-${path.split('/').at(-1)}-${revision}`,
      alias: aliases.find((row) => row.alias_id === Number(path.split('/').at(-1))) });
    if (/\/api\/v1\/aliases\/\d+$/.test(path)) return send({ breakdown: [] });
    if (path === '/api/v1/aliases/ids') return send({ alias_ids: [101, 102, 103], count: 3 });
    if (path === '/api/v1/aliases' || path.endsWith('/observed-group-identities')) {
      const parameters = Object.fromEntries(url.searchParams);
      requests.push({ path, parameters });
      const isObserved = path.endsWith('/observed-group-identities');
      let rows = isObserved ? [...observed] : aliases.filter((row) =>
        row.alias_list_id === Number(url.searchParams.get('list') || 1));
      const term = (parameters.q || '').toLowerCase();
      rows = rows.filter((row) => !term || `${row.name || ''} ${row.system_name || ''}`.toLowerCase().includes(term));
      if (parameters.group) rows = rows.filter((row) => row.group === parameters.group);
      if (!isObserved) rows.sort((left, right) => left.name.localeCompare(right.name));
      if (parameters.direction === 'desc' && !isObserved) rows.reverse();
      const offset = Number(parameters.offset || 0), limit = 2;
      const payload = { rows: rows.slice(offset, offset + limit), offset, limit, total_count: rows.length,
        has_more: offset + limit < rows.length, next_offset: offset + limit < rows.length ? offset + limit : null };
      if (holdNext) {
        holdNext = false;
        await new Promise((resolve) => held.push(async () => {
          try { await send(payload); } catch (_) { /* A newer request may have canceled this response. */ }
          resolve();
        }));
        return;
      }
      if (failNext) {
        failNext = false;
        return route.fulfill({ status: 503, json: { error: { message: 'Try again.' } } });
      }
      return send(payload);
    }
    return route.fulfill({ status: 404, json: { error: { message: 'Unavailable in this fixture.' } } });
  });
  await page.goto(`/app.html?view=aliases&${query}`);
  await expect(page.locator('.alias-editor-table-host, .observed-group-identity-table-host')).toBeVisible();
  await page.evaluate(() => {
    window.aliasWorkspaceIdentity = document.querySelector('.alias-editor-workspace');
    window.aliasRailIdentity = document.querySelector('.alias-list-rail');
  });
  return { requests, writes, held, hold: () => { holdNext = true; }, fail: () => { failNext = true; },
    documentRequests: () => documentRequests, revision: () => revision };
}

async function expectStableWorkspace(page, fixture) {
  expect(await page.evaluate(() => window.aliasWorkspaceIdentity ===
    document.querySelector('.alias-editor-workspace') && window.aliasRailIdentity ===
    document.querySelector('.alias-list-rail'))).toBe(true);
  expect(fixture.documentRequests()).toBe(1);
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`Alias headings use ordinary text and Discover exposes direct details actions in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 850 });
    await installEditor(page);
    await page.evaluate((theme) => { document.documentElement.dataset.theme = theme; }, theme);
    const headings = page.locator('.alias-list-summary h2, .alias-channel-usage h3');
    await expect(headings).toHaveCount(2);
    const colors = await headings.evaluateAll((elements) => elements.map((element) =>
      ({ heading: getComputedStyle(element).color, normal: getComputedStyle(document.body).color })));
    expect(colors.every((color) => color.heading === color.normal)).toBe(true);
    const channel = page.locator('.alias-channel-usage').getByRole('link', { name: 'County Control', exact: true });
    await expect(channel).toHaveAttribute('href', /view=channel-setup&channel=10000000-0000-4000-8000-000000000001/);
    expect(await channel.evaluate((element) => getComputedStyle(element).color)).not.toBe(colors[0].normal);
    await page.locator('.alias-discover-link').click();
    const row = page.locator('.observed-group-identity-row').first();
    const details = row.getByRole('link', { name: 'Details', exact: true });
    await expect(details).toBeVisible();
    await expect(row.getByRole('button', { name: 'Create Alias', exact: true })).toBeVisible();
    await expect(details).toHaveAttribute('href', /view=group-identity/);
    await details.focus();
    await expect(details).toBeFocused();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    expect(await row.getAttribute('tabindex')).toBeNull();
    const actions = row.locator('.ui-action-row');
    // The table's initial frame may restore scrolling after focus; compare one geometry sample.
    const { cellBounds, controls } = await row.evaluate((element) => ({
      cellBounds: element.querySelector('td[data-column="action"]').getBoundingClientRect().toJSON(),
      controls: [...element.querySelectorAll('.ui-action-row a, .ui-action-row button')]
        .map((control) => control.getBoundingClientRect().toJSON())
    }));
    for (const controlBounds of controls) {
      expect(controlBounds.x).toBeGreaterThanOrEqual(cellBounds.x);
      expect(controlBounds.x + controlBounds.width).toBeLessThanOrEqual(cellBounds.x + cellBounds.width);
    }
    await actions.locator('a, button').last().focus();
    if (process.env.WEB_REVIEW_DIR) await page.screenshot({ path: path.join(process.env.WEB_REVIEW_DIR,
      `discover-direct-details-${theme}-${width}.png`), fullPage: true });
  });
}

for (const width of [1280, 390]) test(`Alias browsing and selection update in place at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 850 });
  const fixture = await installEditor(page);
  const selected = page.getByRole('checkbox', { name: 'Select Dispatch', exact: true });
  await selected.check();
  await page.getByRole('link', { name: 'Next', exact: true }).click();
  await expect(page.getByRole('checkbox', { name: 'Select Medic', exact: true })).toBeVisible();
  await page.getByRole('checkbox', { name: 'Select Medic', exact: true }).check();
  await page.getByRole('link', { name: 'Previous', exact: true }).click();
  await expect(selected).toBeChecked();
  await page.locator('th[data-column="alias"] a').click();
  await expect(page.getByRole('checkbox', { name: 'Select Medic', exact: true })).toBeChecked();
  await page.locator('.alias-editor-filter-toolbar input[name="q"]').fill('Fireground');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.getByRole('checkbox', { name: 'Select Fireground', exact: true })).not.toBeChecked();
  await expect(page.locator('.alias-bulk-bar')).toBeHidden();
  await expect(page.locator('.alias-editor-filter-toolbar input[name="q"]')).toHaveValue('Fireground');
  await page.locator('.alias-discover-link').click();
  await expect(page.locator('.observed-group-identity-row')).toHaveCount(2);
  await page.locator('.observed-group-identity-toolbar input[name="q"]').fill('System 2');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.locator('.observed-group-identity-row')).toHaveCount(1);
  await page.goBack();
  await expect(page.locator('.observed-group-identity-row')).toHaveCount(2);
  await page.goForward();
  await expect(page.locator('.observed-group-identity-row')).toHaveCount(1);
  if (width < 600) await page.getByRole('combobox', { name: 'Alias list', exact: true }).selectOption('2');
  else await page.locator('.alias-list-item').filter({ hasText: 'City' }).click();
  await expect(page.locator('.alias-list-summary h2')).toHaveText('City');
  await expect(page.getByRole('checkbox', { name: 'Select City Dispatch', exact: true })).toBeVisible();
  await expectStableWorkspace(page, fixture);
});

test('Alias query races keep the latest results and failed searches retain drafts for retry', async ({ page }) => {
  const fixture = await installEditor(page);
  const search = page.locator('.alias-editor-filter-toolbar input[name="q"]');
  fixture.hold();
  await page.getByRole('link', { name: 'Activity', exact: true }).click();
  await expect.poll(() => fixture.held.length).toBe(1);
  await page.getByRole('link', { name: 'Configure', exact: true }).click();
  await expect(page.getByRole('link', { name: 'Configure', exact: true })).toHaveAttribute('aria-current', 'page');
  await fixture.held.shift()();
  await expect(page.locator('th[data-column="calls"]')).toHaveCount(0);
  fixture.hold();
  await search.fill('Dispatch');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect.poll(() => fixture.held.length).toBe(1);
  await search.fill('Medic');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.getByRole('checkbox', { name: 'Select Medic', exact: true })).toBeVisible();
  await fixture.held.shift()();
  await expect(search).toHaveValue('Medic');
  await expect(page.getByRole('checkbox', { name: 'Select Dispatch', exact: true })).toHaveCount(0);
  fixture.hold();
  await search.fill('Dispatch');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect.poll(() => fixture.held.length).toBe(1);
  fixture.fail();
  await search.fill('Fireground');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.locator('.alias-editor-main > .alias-editor-view-feedback').first()).toContainText('Could not load aliases');
  await expect(search).toHaveValue('Fireground');
  expect(new URL(page.url()).searchParams.get('q')).toBe('Medic');
  await expect(page.getByRole('checkbox', { name: 'Select Medic', exact: true })).toBeVisible();
  await fixture.held.shift()();
  await page.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(page.getByRole('checkbox', { name: 'Select Fireground', exact: true })).toBeVisible();
  await expectStableWorkspace(page, fixture);
});

test('Discover links preserve canonical systems and promotion refreshes all alias state', async ({ page }) => {
  const fixture = await installEditor(page, 'list=1&aliasTab=discover');
  const rows = page.locator('.observed-group-identity-row');
  for (const index of [0, 1]) {
    const link = rows.nth(index).getByRole('link', { name: 'Details', exact: true });
    const query = new URL(await link.getAttribute('href'), page.url()).searchParams;
    expect(query.get('view')).toBe('group-identity');
    expect(query.get('radio_system_key')).toBe(`p25:0000${index + 1}:00${index + 1}`);
    expect(query.get('identity_key')).toBe(`v1-g-0000${index + 1}-00${index + 1}-1201`);
    await expect(link).not.toHaveAttribute('aria-haspopup', 'dialog');
  }
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await rows.first().getByRole('button', { name: 'Create Alias', exact: true }).click();
  const editor = page.getByRole('dialog', { name: 'Add Alias to County', exact: true });
  await editor.getByRole('textbox', { name: /^Alias name/ }).fill('New Dispatch');
  await editor.getByRole('combobox', { name: 'Group', exact: true }).fill('New Category');
  await editor.getByRole('button', { name: 'Create Alias', exact: true }).click();
  await expect(editor).toHaveCount(0);
  await expect(rows).toHaveCount(0);
  await expect(page.locator('.alias-list-summary-metrics')).toContainText('4 aliases');
  await expect(page.locator('.alias-list-item.active')).toContainText('4 aliases');
  expect(fixture.writes[0].body.alias).toMatchObject({ recordable: true,
    matcher: { type: 'talkgroup', protocol: 'p25', value: 1201 } });
  await page.getByRole('link', { name: 'Configure', exact: true }).click();
  await expect(page.locator('#alias-editor-group-filter-options option[value="New Category"]')).toHaveCount(1);
  await expectStableWorkspace(page, fixture);
});

test('Saving an alias from a later page clears selection and refreshes the route page using the latest revision', async ({ page }) => {
  const fixture = await installEditor(page, 'list=1&aliasTab=configure&offset=2');
  await page.getByRole('checkbox', { name: 'Select Medic', exact: true }).check();
  await page.locator('.alias-detail-link').filter({ hasText: 'Medic' }).click();
  const editor = page.getByRole('dialog', { name: 'Edit Medic', exact: true });
  await editor.getByRole('textbox', { name: /^Alias name/ }).fill('A Medic');
  await editor.getByRole('button', { name: 'Save Changes', exact: true }).click();
  await expect(editor).toHaveCount(0);
  await expect(page.getByRole('checkbox', { name: 'Select A Medic', exact: true })).not.toBeChecked();
  expect(new URL(page.url()).searchParams.has('offset')).toBe(false);
  expect(fixture.requests.at(-1).parameters.offset || '0').toBe('0');
  await page.locator('.alias-detail-link').filter({ hasText: 'A Medic' }).click();
  const nextEditor = page.getByRole('dialog', { name: 'Edit A Medic', exact: true });
  await nextEditor.getByRole('textbox', { name: /^Alias name/ }).fill('A Medic Updated');
  await nextEditor.getByRole('button', { name: 'Save Changes', exact: true }).click();
  await expect(nextEditor).toHaveCount(0);
  expect(fixture.writes.map((write) => write.body.edit_revision)).toEqual(['alias-103-1', 'alias-103-2']);
  await expectStableWorkspace(page, fixture);
});


test('Discover does not fabricate a destination for unresolved observations or bypass Radio access', async ({ page }) => {
  await installEditor(page, 'list=1&aliasTab=discover', { observed: [{ protocol: 'p25', topology: 'TRUNKED',
    group_identity_id: 77, native_id: 77, group_identity_kind: 'talkgroup', match_kind: 'range',
    promotion_supported: true, logical_call_count: 1, entity_ref: null }] });
  const row = page.locator('.observed-group-identity-row');
  await expect(row).toContainText('Covered by range');
  await expect(row.getByRole('link', { name: 'Details', exact: true })).toHaveCount(0);
  await expect(row.getByRole('button', { name: 'Create Alias', exact: true })).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await installEditor(page, 'list=1&aliasTab=discover', { radio: false });
  await expect(page.locator('.observed-group-identity-row')).toHaveCount(2);
  await expect(page.locator('.observed-group-identity-details')).toHaveCount(0);
});
