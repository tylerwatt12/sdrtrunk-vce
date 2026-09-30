'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const radioSystemKey = 'p25:00001:001';
const encodedRadioSystemKey = encodeURIComponent(radioSystemKey);
const radioSystemPath = `/api/v1/radio-systems/${encodedRadioSystemKey}`;
const sourceIdentityKey = 'v1-r-00001-001-2808137';
const talkgroupIdentityKey = 'v1-g-00001-001-56131';

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

async function openActivity(page) {
  const requests = { activity: [], groups: [], radios: [] };
  const groupRows = [
    { identity_key: talkgroupIdentityKey, native_id: 56131, group_identity_kind: 'talkgroup',
      alias_name: 'CPD D3 DISP' },
    { identity_key: 'v1-p-00001-001-59001', native_id: 59001, group_identity_kind: 'patch_group',
      alias_name: 'Dispatch Patch' }
  ];
  const radioRows = [
    { identity_key: sourceIdentityKey, native_id: 2808137, alias_name: 'Engine 12',
      last_talker_alias: 'E12 Portable' }
  ];
  const paged = (rows) => ({ data: rows, meta: { limit: 25, offset: 0,
    has_more: false, total_count: rows.length } });

  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const pathname = url.pathname;
    const respond = (data) => route.fulfill({ json: { data } });
    if (pathname === '/api/v1/auth/session') {
      await respond({ configured: true, authenticated: true, username: 'operator', tier: 'admin',
        primary: true, capabilities: { dashboard: true, radio: true, credits: true } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences: defaultPreferences } });
    } else if (pathname === '/api/v1/status') {
      await respond({ stats_logging: { summary_configured: true, detailed_history_configured: true,
        summary_active: true, detailed_history_active: true, state: 'RUNNING' },
      database: { detailed_history_available: true } });
    } else if (pathname === '/api/v1/channel-catalog') {
      await respond({ revision: 1, channels: [] });
    } else if (pathname === radioSystemPath) {
      await respond({ radio_system_key: radioSystemKey, protocol: 'P25', wacn: 1, system_id: 1,
        system_name: 'Metro Public Safety', assignment_state: 'CURRENT',
        channel_names: 'Downtown · North', capabilities: { group_identities: true, radios: true,
          activity: true, talker_aliases: true } });
    } else if (pathname === `${radioSystemPath}/group-identities`) {
      requests.groups.push(url.searchParams.get('q') || '');
      await route.fulfill({ json: paged(groupRows) });
    } else if (pathname === `${radioSystemPath}/radios`) {
      requests.radios.push(url.searchParams.get('q') || '');
      await route.fulfill({ json: paged(radioRows) });
    } else if (pathname === `${radioSystemPath}/channels`) {
      await route.fulfill({ json: paged([{ configuration_id: '11111111-1111-4111-8111-111111111111',
        site_name: 'Downtown', name: 'GCRCN' }]) });
    } else if (pathname === '/api/v1/activity') {
      requests.activity.push(Object.fromEntries(url.searchParams));
      await respond({ rows: [{ id: 17, observed_at_ms: 1_790_720_000_000, action: 'CALL',
        event_type: 'CALL_GROUP', protocol: 'P25', source_radio_id: 2808137,
        source_identity_key: sourceIdentityKey, source_alias_name: 'Engine 12',
        source_talker_alias: 'E12 Portable', target_id: 56131,
        target_identity_key: talkgroupIdentityKey, target_kind: 'talkgroup',
        target_alias_name: 'CPD D3 DISP', name: 'GCRCN', frequency_hz: 855_737_500,
        lcn: '0-757', encrypted: false,
        configuration_id: '11111111-1111-4111-8111-111111111111' }],
      has_more: false, next_before_id: null });
    } else {
      await route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
    }
  });

  await page.goto(`/app.html?view=radio-system&radio_system_key=${encodedRadioSystemKey}&tab=activity`);
  await expect(page.locator('.activity-filter-toolbar')).toBeVisible();
  return requests;
}

test('P25 Activity provides compact identity-first filters in the actual application', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const requests = await openActivity(page);
  const toolbar = page.locator('.activity-filter-toolbar');
  const primary = toolbar.locator('.activity-filter-primary');

  await expect(toolbar.locator(
    'label.activity-filter-field:has(> .ui-field-label:text-is("Time")) select')).toHaveValue('all');
  const action = toolbar.locator(
    'label.activity-filter-field:has(> .ui-field-label:text-is("Action")) select');
  await expect(action).toContainText('All actions (grants hidden)');
  await expect(action).toContainText('All actions (including grants)');
  await expect(primary.getByText('Include grants', { exact: true })).toHaveCount(0);
  await expect(primary.getByRole('button', { name: 'More filters' })).toBeVisible();
  await expect(primary.getByRole('button', { name: 'Apply', exact: true })).toBeVisible();

  const sourceTrigger = toolbar.locator('.activity-filter-picker-source .activity-filter-picker-trigger');
  await sourceTrigger.click();
  const sourcePicker = page.getByRole('dialog', { name: 'Choose a source radio' });
  await sourcePicker.locator('input[type="search"]').fill('Engine 12');
  await expect(sourcePicker.locator(`.activity-identity-result[data-identity-key="${sourceIdentityKey}"]`))
    .toContainText('2808137');
  await expect(sourcePicker.locator(`.activity-identity-result[data-identity-key="${sourceIdentityKey}"]`))
    .toContainText('Engine 12');
  await sourcePicker.locator(`.activity-identity-result[data-identity-key="${sourceIdentityKey}"]`).click();

  const destinationTrigger = toolbar.locator(
    '.activity-filter-picker-destination .activity-filter-picker-trigger');
  await destinationTrigger.click();
  const destinationPicker = page.getByRole('dialog', { name: 'Choose a destination' });
  await destinationPicker.locator('input[type="search"]').fill('disp');
  const identityResults = destinationPicker.locator('.activity-identity-result[data-identity-key]');
  await expect(identityResults).toHaveCount(3);
  await expect(identityResults.filter({ hasText: 'CPD D3 DISP' })).toContainText('56131');
  await expect(identityResults.filter({ hasText: 'Engine 12' })).toContainText('2808137');
  await expect(identityResults.filter({ hasText: 'Dispatch Patch' })).toContainText('59001');

  await destinationPicker.getByRole('button', { name: 'Talkgroups', exact: true }).click();
  await expect(identityResults).toHaveCount(1);
  await expect(identityResults.first().locator('.activity-identity-result-kind')).toHaveText('Talkgroup');
  await destinationPicker.getByRole('button', { name: 'Radios', exact: true }).click();
  await expect(identityResults).toHaveCount(1);
  await expect(identityResults.first().locator('.activity-identity-result-kind')).toHaveText('Radio');
  await destinationPicker.getByRole('button', { name: 'Patches', exact: true }).click();
  await expect(identityResults).toHaveCount(1);
  await expect(identityResults.first().locator('.activity-identity-result-kind')).toHaveText('Patch');
  await destinationPicker.getByRole('button', { name: 'All', exact: true }).click();
  await identityResults.filter({ hasText: 'CPD D3 DISP' }).click();

  await action.selectOption('__include_grants__');
  await primary.getByRole('button', { name: 'More filters' }).click();
  const more = page.getByRole('dialog', { name: 'More activity filters' });
  await expect(more.locator('.activity-filter-group-title')).toHaveText([
    'Scope', 'Event details', 'Technical'
  ]);
  await expect(more.locator('.ui-field-label:text-is("Channel / site")')).toBeVisible();
  await expect(more.locator('.ui-field-label:text-is("Event subtype")')).toBeVisible();
  await expect(more.locator('.ui-field-label:text-is("Frequency (MHz)")')).toBeVisible();
  await expect(more.locator('.ui-field-label:text-is("LCN")')).toBeVisible();
  await expect(more.getByText('Timeslot', { exact: true })).toHaveCount(0);
  await more.locator(
    'label.activity-filter-field:has(> .ui-field-label:text-is("Encryption")) select')
    .selectOption('encrypted');
  await more.getByRole('button', { name: 'Done', exact: true }).click();
  await primary.getByRole('button', { name: 'Apply', exact: true }).click();

  await expect.poll(() => new URL(page.url()).searchParams.get('activity_include_grants')).toBe('true');
  await expect.poll(() => new URL(page.url()).searchParams.get('activity_target_identity_key'))
    .toBe(talkgroupIdentityKey);
  const chips = page.locator('.activity-filter-chips');
  await expect(chips).toContainText('Action:Grants included');
  await expect(chips).toContainText('Source:2808137 · Engine 12');
  await expect(chips).toContainText('Destination:Talkgroup56131 · CPD D3 DISP');
  await expect(chips).toContainText('Encrypted only');
  await expect(chips.locator('.activity-filter-chip-label')).toHaveText([
    'Action:', 'Source:', 'Destination:'
  ]);
  expect(await chips.locator('.activity-filter-chip-label').evaluateAll((labels) => labels.every((label) =>
    label.scrollWidth <= label.clientWidth))).toBe(true);
  await expect.poll(() => requests.radios.includes('Engine 12')).toBe(true);
  await expect.poll(() => requests.radios.includes('disp')).toBe(true);
  await expect.poll(() => requests.groups.includes('disp')).toBe(true);
  await expect.poll(() => requests.activity.at(-1)?.hide_grants).toBe('false');
});

test('P25 Activity More filters is a usable mobile sheet and the table stays contained', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openActivity(page);
  const toolbar = page.locator('.activity-filter-toolbar');
  const toolbarBox = await toolbar.boundingBox();
  expect(toolbarBox.x).toBeGreaterThanOrEqual(0);
  expect(toolbarBox.x + toolbarBox.width).toBeLessThanOrEqual(390);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);

  await toolbar.getByRole('button', { name: 'More filters' }).click();
  const more = page.getByRole('dialog', { name: 'More activity filters' });
  await expect(more).toBeVisible();
  const panelBox = await more.boundingBox();
  expect(panelBox.x).toBeGreaterThanOrEqual(7);
  expect(panelBox.x + panelBox.width).toBeLessThanOrEqual(383);
  expect(panelBox.y).toBeGreaterThanOrEqual(7);
  expect(panelBox.y + panelBox.height).toBeLessThanOrEqual(837);
  await expect(more.locator('.activity-filter-group-title')).toHaveText([
    'Scope', 'Event details', 'Technical'
  ]);
  await expect(more.getByText('Timeslot', { exact: true })).toHaveCount(0);
  await expect(more.getByRole('button', { name: 'Done', exact: true })).toBeVisible();
  const doneBox = await more.getByRole('button', { name: 'Done', exact: true }).boundingBox();
  expect(doneBox.y + doneBox.height).toBeLessThanOrEqual(837);
});
