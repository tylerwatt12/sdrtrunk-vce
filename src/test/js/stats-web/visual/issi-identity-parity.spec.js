'use strict';

const { test, expect } = require('@playwright/test');
const { openAudioApp, recording, liveCall } = require('./fixtures/audio-dock-app.cjs');
const { selectAudioPanel } = require('./audio-dock-controls.cjs');
const { installSiteStyleApplication, systemKey, channelId } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const home = 'p25:bee00:348';
const groupKey = 'v1-g-bee00-348-7001';
const calls = [null, home, systemKey].map((serving, index) => ({
  ...recording, id: 201 + index, serving_system_key: serving,
  talkgroup_id: 77, talkgroup_alias: index === 2 ? 'Dispatch' : '',
  source_id: 501, source_alias: index === 2 ? 'Engine 4' : '', source_ota_alias: '',
  source_home_wacn: 0xBEE00, source_home_system_id: 0x348, source_home_id: 9001,
  source_home_system_name: 'County Radio',
  target_home_wacn: 0xBEE00, target_home_system_id: 0x348, target_home_id: 7001,
  target_home_system_name: 'County Radio',
  source_entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: 'v1-r-bee00-348-9001' },
  target_entity_ref: { kind: 'talkgroup', radio_system_key: systemKey, identity_key: groupKey },
  patch_members: [{ kind: 'talkgroup', id: 78, home_wacn: 0xBEE00, home_system_id: 0x348,
    home_identity_id: 7002, alias: 'Tactical', home_system_name: 'County Radio',
    entity_ref: { kind: 'talkgroup', radio_system_key: systemKey, identity_key: 'v1-g-bee00-348-7002' } },
  { kind: 'radio', id: 502, home_wacn: 0xBEE00, home_system_id: 0x348,
    home_identity_id: 9003, alias: 'Medic 2', home_system_name: 'County Radio',
    entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: 'v1-r-bee00-348-9003' } }]
}));
calls.push({ ...calls[2], id: 204, call_type: 'DIRECT', talkgroup_id: null, talkgroup_alias: null,
  destination_radio_id: 501, destination_radio_alias: 'Supervisor', target_home_id: 9002,
  target_entity_ref: { kind: 'radio', radio_system_key: systemKey, identity_key: 'v1-r-bee00-348-9002' } });

for (const presentation of [
  { name: 'desktop', viewport: { width: 1440, height: 1000 } },
  { name: 'phone', viewport: { width: 390, height: 844 } }
]) {
  test(`ISSI recording parties retain captured scope, aliases and complete filters on ${presentation.name}`,
    async ({ page }) => {
      await page.setViewportSize(presentation.viewport);
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await openAudioApp(page, { calls });
      const card = id => page.locator(`.recordings-call[data-recording-id="${id}"]`);
      for (const id of [201, 202]) {
        await expect(card(id)).toContainText('7001 (Local TG ID 77)');
        await expect(card(id)).toContainText('9001 (Working ID 501)');
        await expect(card(id)).not.toContainText('County Radio');
      }
      await expect(card(203)).toContainText('Dispatch');
      await expect(card(203)).toContainText('County Radio · 7001 (Local TG ID 77)');
      await expect(card(203)).toContainText('Engine 4');
      await expect(card(203)).toContainText('County Radio · 9001 (Working ID 501)');
      await expect(card(204)).toContainText('Supervisor');
      await expect(card(204)).toContainText('County Radio · 9002 (Working ID 501)');
      await expect(card(204)).toContainText('County Radio · 9001 (Working ID 501)');
      await card(203).getByRole('button', { name: /^Call details/ }).click();
      await page.getByRole('tab', { name: 'Identities', exact: true }).click();
      const identities = page.getByRole('tabpanel', { name: 'Identities', exact: true });
      await expect(identities).toContainText('Tactical · County Radio · 7002 (Local TG ID 78)');
      await expect(identities).toContainText('Medic 2 · County Radio · 9003 (Working ID 502)');
      await page.getByRole('button', { name: 'Close Call details', exact: true }).click();
      const group = card(203).getByRole('link', { name: 'Dispatch', exact: true });
      const target = new URL(await group.getAttribute('href'), page.url());
      expect(target.searchParams.get('view')).toBe('group-identity');
      expect(target.searchParams.get('identity_key')).toBe(groupKey);
      expect(target.searchParams.get('radio_system_key')).toBe(systemKey);
      expect(target.searchParams.has('talkgroup_id')).toBe(false);
      await card(203).getByRole('button', { name: /^More actions for/ }).click();
      const search = page.getByRole('dialog', { name: 'Call actions', exact: true })
        .getByRole('link', { name: 'Search this talkgroup', exact: true });
      const filter = new URL(await search.getAttribute('href'), page.url());
      expect(filter.searchParams.get('talkgroup_identity_key')).toBe(groupKey);
      expect(filter.searchParams.get('system_key')).toBe(systemKey);
      expect(filter.searchParams.has('talkgroup_id')).toBe(false);
      await search.click();
      if (presentation.name === 'phone') {
        await page.locator('#recordings-filters-toggle').click();
        await expect(page.locator('.recordings-selected-filters')).toBeVisible();
      }
      await expect(page.locator('.recordings-selected-filters')
        .getByRole('button', { name: 'Remove Talkgroup: Dispatch filter', exact: true })).toContainText('Dispatch');
      await expectNoHorizontalOverflow(page);
      expect(errors).toEqual([]);
    });

  test(`ISSI live call parties and patch members retain their own identities on ${presentation.name}`,
    async ({ page }, testInfo) => {
      await page.setViewportSize(presentation.viewport);
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await openAudioApp(page, { feedCallsEnabled: false, callDisplayHangtimeSeconds: 0 });
      const pending = [];
      let cursor = 0;
      await page.route('**/api/v1/calls/feed*', route => route.fulfill({ json: {
        data: { cursor: String(++cursor), reset: false, calls: pending.splice(0) }
      } }));
      const dock = page.locator('#audio-dock');
      await dock.locator('#audio-dock-size-handle').focus();
      await dock.locator('#audio-dock-size-handle').press('End');
      await selectAudioPanel(dock, 'Details');
      const identities = dock.locator('details').filter({ has: page.locator('summary', { hasText: 'Identity & aliases' }) });
      if (await identities.getAttribute('open') === null) await identities.locator('summary').click();
      for (const [index, serving] of [null, home, systemKey].entries()) {
        pending.push({ ...liveCall, call_id: `issi-live-${index}`, protocol: 'APCO25_PHASE2',
          serving_system_key: serving, radio_system_key: systemKey,
          source_alias: 'Command', source_form: 'TALKGROUP', source_id: 77,
          source_canonical_group_identity: { wacn: 0xBEE00, system_id: 0x348, talkgroup_id: 7003 },
          source_observed_local_id: 77, source_home_system_name: 'County Radio',
          source_entity_ref: { kind: 'talkgroup', radio_system_key: systemKey, identity_key: 'v1-g-bee00-348-7003' },
          target_alias: 'Dispatch', target_form: 'PATCH_GROUP', target_id: 77,
          target_canonical_group_identity: { wacn: 0xBEE00, system_id: 0x348, talkgroup_id: 7001 },
          target_observed_local_id: 77, target_home_system_name: 'County Radio', patch_group_id: 77,
          target_entity_ref: { kind: 'patch_group', radio_system_key: systemKey, identity_key: 'v1-p-bee00-348-7001' },
          patch_members: calls[2].patch_members.map(member => ({ ...member,
            ...(member.kind === 'talkgroup' ? {
              canonical_group_identity: { wacn: member.home_wacn, system_id: member.home_system_id,
                talkgroup_id: member.home_identity_id }, observed_local_id: member.id
            } : { canonical_identity: { wacn: member.home_wacn, system_id: member.home_system_id,
              subscriber_id: member.home_identity_id }, observed_working_id: member.id })
          })) });
        if (index === 0) await dock.getByRole('button', { name: 'Listen live', exact: true }).click();
        const facts = dock.locator('#audio-dock-panel');
        await expect(facts).toContainText('7003 (Local TG ID 77)');
        await expect(facts).toContainText('7001 (Local TG ID 77)');
        await expect(identities).toContainText('Tactical');
        await expect(identities).toContainText('7002 (Local TG ID 78)');
        await expect(identities).toContainText('Medic 2');
        await expect(identities).toContainText('9003 (Working ID 502)');
        if (index === 2) await expect(identities).toContainText('County Radio');
        else await expect(identities).not.toContainText('County Radio');
        const member = identities.getByRole('link', { name: /Tactical/ });
        expect(new URL(await member.getAttribute('href'), page.url()).searchParams.get('identity_key'))
          .toBe('v1-g-bee00-348-7002');
        await member.scrollIntoViewIfNeeded();
        await expect(member).toBeVisible();
        await expectNoHorizontalOverflow(page);
        if (index === 2) await dock.screenshot({ path: testInfo.outputPath('issi-live-identities.png') });
        await page.evaluate(() => {
          const source = window.audioTest.liveSources.find(value => value.active);
          if (!source?.onended) throw new Error('No fixture call is playing.');
          source.active = false;
          source.onended();
        });
        await expect(facts).not.toContainText('7003 (Local TG ID 77)');
      }
      expect(errors).toEqual([]);
    });

  test(`ISSI talkgroup associations own their controls and page independently on ${presentation.name}`,
    async ({ page }) => {
      await page.setViewportSize(presentation.viewport);
      const fixture = await installSiteStyleApplication(page, 'light');
      const requests = [];
      const association = {
        protocol: 'P25', radio_system_key: systemKey, serving_system_key: systemKey,
        canonical_group_identity: { wacn: 0xBEE00, system_id: 0x348, talkgroup_id: 7001 },
        observed_local_id: 77, native_id: 7001, alias_name: 'Dispatch', alias_description: 'Dispatch operations',
        home_system_name: 'County Radio', home_system_entity_ref: { kind: 'radio_system', key: home },
        entity_ref: { kind: 'talkgroup', radio_system_key: systemKey, identity_key: groupKey },
        observed_at_ms: Date.parse('2026-10-01T12:00:00Z'), expires_at_ms: Date.parse('2026-10-01T12:05:00Z'),
        evidence: 'group_affiliation', observed_on: { configuration_id: channelId, name: 'North Ridge',
          entity_ref: { kind: 'channel', key: channelId } }
      };
      await page.route('**/api/v1/**', async route => {
        const url = new URL(route.request().url());
        const pathname = decodeURIComponent(url.pathname);
        const base = `/api/v1/radio-systems/${systemKey}`;
        if (pathname === base) return route.fulfill({ json: { data: { protocol: 'P25', radio_system_key: systemKey,
          system_name: 'Receiving Network', capabilities: { issi: true, group_identities: true, radios: true } } } });
        if (pathname !== `${base}/issi/current-assignments`) return route.fallback();
        const groups = url.searchParams.get('identity_kind') === 'talkgroup';
        requests.push(Object.fromEntries(url.searchParams));
        const offset = groups ? Number(url.searchParams.get('offset') || 0) : 0;
        return route.fulfill({ json: { data: { rows: groups ? [{ ...association, observed_local_id: 77 + offset }] : [],
          offset, limit: 1, total_count: groups ? 2 : 0, has_more: groups && offset === 0,
          next_offset: groups && offset === 0 ? 1 : null,
          current_state: { state: 'current', snapshot_stale: false, snapshot_at_ms: association.observed_at_ms,
            current_assignment_count: 0, group_association_count: 2, observed_channel_count: 1 } } } });
      });
      await page.goto(`/app.html?view=radio-system&radio_system_key=${encodeURIComponent(systemKey)}&tab=issi`);
      const section = page.locator('#content > .ui-section').filter({
        has: page.locator('.ui-section-title').filter({ hasText: /^Talkgroup Associations/ })
      });
      await expect(section.locator('table')).toContainText('Dispatch');
      await expect(section.locator('table')).toContainText('County Radio · 7001 (Local TG ID 77)');
      await expect(section.locator('table')).toContainText('Dispatch operations');
      await expect(section.getByRole('button', { name: 'Choose table columns' })).toHaveCount(1);
      await section.getByRole('button', { name: 'Next', exact: true }).click();
      await expect(section.locator('table')).toContainText('Local TG ID 78');
      const safeArea = await page.evaluate(() => ({
        dockHeight: document.querySelector('#audio-dock').getBoundingClientRect().height,
        scrollPadding: parseFloat(getComputedStyle(document.documentElement).scrollPaddingBottom),
        contentSpacer: parseFloat(getComputedStyle(document.querySelector('#content'), '::after').height)
      }));
      expect(safeArea.scrollPadding).toBeGreaterThan(safeArea.dockHeight);
      expect(safeArea.contentSpacer).toBeGreaterThan(safeArea.dockHeight);
      expect(requests.filter(row => row.identity_kind === 'talkgroup').at(-1).offset).toBe('1');
      expect(new URL(page.url()).searchParams.has('offset')).toBe(false);
      await expectNoHorizontalOverflow(page);
      expect(fixture.pageErrors).toEqual([]);
      expect(fixture.unexpected).toEqual([]);
    });
}
