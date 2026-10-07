'use strict';

const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { installSiteStyleApplication, systemKey, groupKey, radioKey, systemName } =
  require('./fixtures/site-style-app.cjs');
const { presentations } = require('./fixtures/site-style-cases.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const foreignKey = 'v1-r-00002-001-30914';
const scope = `radio_system_key=${encodeURIComponent(systemKey)}`;
const homeRadio = {
  protocol: 'P25', radio_system_key: systemKey, identity_key: radioKey, native_id: 30914,
  canonical_identity: { wacn: 1, system_id: 1, subscriber_id: 30914 }, observed_working_id: 30914,
  alias_name: 'Engine 4', last_talker_alias: 'ENG 4', home_system_name: systemName,
  system_name: systemName, logical_call_count: 7, groups: 1,
  entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: radioKey },
  radio_system_entity_ref: { kind: 'radio_system', key: systemKey },
  capabilities: { talker_aliases: true, activity: true, group_identities: true, radios: true }
};
const foreignRadio = {
  ...homeRadio, identity_key: foreignKey, alias_name: '', last_talker_alias: '',
  canonical_identity: { wacn: 2, system_id: 1, subscriber_id: 30914 }, home_system_name: 'West County',
  entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: foreignKey }
};

async function installIdentities(page, theme) {
  const fixture = await installSiteStyleApplication(page, theme);
  const radios = [homeRadio, foreignRadio];
  const paged = rows => ({ data: rows, meta: { limit: 25, offset: 0,
    has_more: false, total_count: rows.length } });
  await page.route('**/api/v1/**', async route => {
    const pathname = decodeURIComponent(new URL(route.request().url()).pathname);
    const base = `/api/v1/radio-systems/${systemKey}`;
    if (pathname === base) return route.fulfill({ json: { data: { ...homeRadio,
      wacn: 1, system_id: 1, radios: 2, talkgroups: 1 } } });
    if (pathname === `${base}/group-identities/${groupKey}`) return route.fulfill({ json: { data: {
      ...homeRadio, native_id: 1201, group_identity_kind: 'talkgroup', alias_name: 'Fire Dispatch'
    } } });
    if (pathname === `${base}/radios`) return route.fulfill({ json: paged(radios) });
    if (pathname === `${base}/relationships`) return route.fulfill({ json: paged([{
      protocol: 'P25', radio_system_key: systemKey, radio_native_id: homeRadio.native_id,
      radio_canonical_identity: homeRadio.canonical_identity,
      radio_observed_working_id: homeRadio.observed_working_id,
      radio_alias_name: homeRadio.alias_name, last_talker_alias: homeRadio.last_talker_alias,
      radio_entity_ref: homeRadio.entity_ref, logical_call_count: homeRadio.logical_call_count
    }]) });
    if (pathname === `${base}/issi/current-assignments`) return route.fulfill({ json: { data: {
      rows: [{ ...foreignRadio, confirmed_at_ms: 1_790_856_000_000, evidence: 'registration' }],
      total_count: 1, limit: 20, offset: 0, has_more: false,
      current_state: { state: 'current', snapshot_at_ms: 1_790_856_000_000, snapshot_stale: false }
    } } });
    const radio = radios.find(item => pathname === `${base}/radios/${item.identity_key}`);
    if (radio) return route.fulfill({ json: { data: radio } });
    if (pathname === '/api/v1/activity') return route.fulfill({ json: { data: {
      rows: [{ id: 1, observed_at_ms: 1_790_856_000_000, action: 'REGISTER', protocol: 'P25',
        radio_system_key: systemKey, system_name: systemName, source_radio_id: 30914,
        source_native_id: 30914, source_identity_key: radioKey, source_alias_name: 'Engine 4',
        source_talker_alias: 'ENG 4', source_canonical_identity: homeRadio.canonical_identity,
        source_observed_working_id: 30914, source_entity_ref: homeRadio.entity_ref,
        target_id: 1201, target_native_id: 1201, target_kind: 'talkgroup',
        target_identity_key: groupKey, target_alias_name: 'Fire Dispatch' }],
      total_count: 1, limit: 100, offset: 0, has_more: false, watermark_id: 1, next_after_id: 1
    } } });
    return route.fallback();
  });
  return fixture;
}

for (const presentation of presentations) {
  test(`home radio labels and authoritative navigation ${presentation.name}`, async ({ page }) => {
    await page.setViewportSize(presentation.viewport);
    const fixture = await installIdentities(page, presentation.theme);
    const capture = async screen => {
      if (process.env.VCE_IDENTITY_REVIEW_DIR) await page.screenshot({
        path: path.join(process.env.VCE_IDENTITY_REVIEW_DIR, `${screen}-${presentation.name}.png`),
        fullPage: true
      });
    };
    await page.goto(`/app.html?view=radio-system&${scope}&tab=radios`);
    await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
    await expect(page.getByRole('link', { name: '30914', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: 'West County · 30914', exact: true })).toBeVisible();
    await expect(page.locator('#content')).toContainText('ENG 4');
    const localLink = page.getByRole('link', { name: '30914', exact: true });
    expect(new URL(await localLink.getAttribute('href'), page.url()).searchParams.get('identity_key')).toBe(radioKey);
    const foreignLink = page.getByRole('link', { name: 'West County · 30914', exact: true });
    expect(new URL(await foreignLink.getAttribute('href'), page.url()).searchParams.get('identity_key')).toBe(foreignKey);
    await expectNoHorizontalOverflow(page, '#content');
    await capture('system-radios');

    await page.goto(`/app.html?view=group-identity&${scope}&identity_key=${groupKey}&tab=radios`);
    await expect(page.getByRole('link', { name: '30914', exact: true })).toBeVisible();
    await page.getByRole('link', { name: '30914', exact: true }).click();
    await expect(page.locator('.ui-page-header')).toContainText('Engine 4');
    await expect(page.locator('.entity-info-column')).toContainText('30914');
    await expect(page.locator('.entity-info-column')).not.toContainText('Last Observed Working ID');
    await expect(page.locator('.entity-info-column')).not.toContainText('00001.001.30914');
    await expectNoHorizontalOverflow(page, '#content');
    await capture('home-radio-info');

    await page.goto(`/app.html?view=radio&${scope}&identity_key=${radioKey}&tab=activity`);
    await expect(page.locator('#content')).toContainText('Engine 4');
    await expect(page.locator('#content')).not.toContainText('00001.001.30914');
    const sourceLink = page.locator('#content a[href]').filter({ hasText: /^30914$/ }).first();
    await expect(sourceLink).toBeVisible();
    expect(new URL(await sourceLink.getAttribute('href'), page.url()).searchParams.get('identity_key')).toBe(radioKey);
    await expectNoHorizontalOverflow(page, '#content');
    await capture('home-radio-activity');

    await page.goto(`/app.html?view=radio&${scope}&identity_key=${foreignKey}`);
    await expect(page.locator('.ui-page-header')).toContainText('West County · 30914');
    await expect(page.locator('.entity-info-column')).toContainText('Current Working ID');
    await expect(page.locator('.entity-info-column')).toContainText('West County');
    await expectNoHorizontalOverflow(page, '#content');
    await capture('foreign-radio-info');
    expect(fixture.pageErrors).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });
}
