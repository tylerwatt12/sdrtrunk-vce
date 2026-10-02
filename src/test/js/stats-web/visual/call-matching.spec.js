'use strict';

const { expect, test } = require('@playwright/test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

let defaultPreferences;

test.beforeAll(async () => {
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/core/preference-schema.js');
  defaultPreferences = (await import(pathToFileURL(file).href)).defaults;
});

function copy(index, selected) {
  return {
    copy_index: index, selected, decoder: 'P25 Phase 1',
    channel_name: selected ? 'Cuyahoga Simulcast' : 'Elyria',
    configuration_ref: index, frequency_hz: selected ? 851_012_500 : 852_012_500, timeslot: 1,
    wacn: 1, system: 1, rfss: 1, site: selected ? 1 : 6,
    start_timestamp: 1_700_000_000_000 + (selected ? 0 : 48),
    end_timestamp: 1_700_000_003_600 - (selected ? 0 : 10),
    duration_milliseconds: selected ? 3600 : 3542,
    expected_frame_count: 180, observed_frame_count: selected ? 173 : 170,
    usable_frame_count: selected ? 167 : 164,
    decoded_frame_count: selected ? 169 : 166,
    repeated_frame_count: selected ? 2 : 3,
    concealed_frame_count: selected ? 4 : 5,
    missing_frame_count: selected ? 9 : 11,
    fec_error_count: selected ? 3 : 6,
    fec_protected_bit_count: 10_000,
    quality_percent: selected ? 92.8 : 91.1,
    missing_and_concealed_rate: selected ? 0.072 : 0.089,
    repeated_frame_rate: selected ? 0.011 : 0.017,
    normalized_fec_error_rate: selected ? 0.0003 : 0.0006,
    retained_audio_sample_count: selected ? 28_800 : 28_336,
    ingress_loss: false, audio_truncated: false,
    overlap: {
      overlap_milliseconds: selected ? 3600 : 3542,
      shorter_copy_overlap_percent: 100,
      selected_copy_coverage_percent: selected ? 100 : 98.4,
      start_offset_from_selected_milliseconds: selected ? 0 : 48,
      end_offset_from_selected_milliseconds: selected ? 0 : -10
    }
  };
}

function duplicate(sequence = 71) {
  return {
    decision_sequence: sequence,
    decided_at_ms: 1_700_000_004_000 + sequence,
    outcome: 'MERGED',
    call_identity: {
      protocol: 'APCO25', decoder: 'P25 Phase 1',
      start_timestamp: 1_700_000_000_000, end_timestamp: 1_700_000_003_600,
      destination_value: '27101', destination_alias: 'County Fire Dispatch',
      source_value: '1204185', source_alias: 'Engine 4', encryption_state: 'CLEAR',
      unique_learned_site_count: 2
    },
    output_policy: { record_requested: true, stream_routing_key_count: 1, browser_offered: true },
    winner: {
      selected_copy_index: 1, runner_up_copy_index: 2, criterion: 'USABLE_FRAME_COUNT',
      winner_value: { display: '167 usable frames', numerator: 167, denominator: 180 },
      runner_up_value: { display: '164 usable frames', numerator: 164, denominator: 180 }
    },
    legs: [copy(1, true), copy(2, false)],
    evidence: {
      confirmed_duplicate_pair_count: 1, separated_pair_count: 0, uncertain_pair_count: 0,
      merge_proof_counts: { shared_voice_content: 1 }, rejection_reason_counts: {}
    },
    decision_reasons: []
  };
}

function snapshot(duplicates = [duplicate()]) {
  return {
    available: true, session_id: 'diagnostic-session',
    session_started_at_epoch_millis: 1_700_000_000_000,
    resolver: {
      session_id: 'coordinator-session', started_at_ms: 1_700_000_000_000,
      generated_at_ms: Date.now(), revision: 7, snapshot_age_ms: 0,
      health_state: 'HEALTHY', accepting: true, disposed: false,
      active_leg_count: 3, active_cohort_count: 1, retained_audio_sample_count: 28_800,
      counters: {
        merged_logical_calls: 147, merged_receiver_copies: 153,
        fail_open_logical_calls: 2, emitted_logical_calls: 210,
        diagnostic_decisions_rejected: 0
      }
    },
    queue: {
      ingress_depth: 2, regular_ingress_capacity: 504, total_ingress_capacity: 512,
      accepted_ingress: 1000, dropped_ingress: 0, dropped_lifecycle: 0,
      dropped_operations: 0, aborted_calls: 0
    },
    diagnostic_status: {
      accepting: true, decisions_observed: 147, records_rejected_after_close: 0
    },
    history: {
      duplicates_evicted: 6,
      duplicates_retained: duplicates.filter((item) => item.outcome === 'MERGED').length,
      limit: 100,
      visible_count: Math.min(100, duplicates.filter((item) => item.outcome === 'MERGED').length),
      retention_capacity: 256
    },
    duplicates
  };
}

function session(tier = 'admin') {
  return {
    configured: true, authenticated: true, username: tier === 'admin' ? 'admin' : 'listener',
    tier, primary: tier === 'admin',
    capabilities: { 'admin-settings': true, credits: true }
  };
}

async function openApp(page, options = {}) {
  let requestCount = 0;
  const matching = options.matching || (async () => ({ status: 200, data: snapshot() }));
  let preferences = JSON.parse(JSON.stringify(defaultPreferences));
  let preferenceRevision = 1;
  const preferenceWrites = [];
  preferences.appearance.theme = options.theme || 'light';
  if (options.tables) preferences.tables = JSON.parse(JSON.stringify(options.tables));
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: { data: session(options.tier || 'admin') } });
    } else if (pathname === '/api/v1/me/preferences') {
      if (route.request().method() === 'PUT') {
        preferences = route.request().postDataJSON();
        preferenceWrites.push(JSON.parse(JSON.stringify(preferences)));
        preferenceRevision++;
      }
      await route.fulfill({ json: { revision: preferenceRevision, preferences } });
    } else if (pathname === '/api/v1/admin/call-matching') {
      requestCount++;
      const response = await matching(requestCount, route);
      if (response) {
        const status = response.status || 200;
        await route.fulfill({ status, json: status === 200 ? { data: response.data } :
          { error: { status, code: 'call_matching_unavailable', message: response.message || 'Unavailable' } } });
      }
    } else {
      await route.fulfill({ status: 404, json: { error: { status: 404, message: 'Unavailable' } } });
    }
  });
  await page.goto('/app.html?view=admin&tab=call-matching');
  return { requests: () => requestCount, preferences: () => preferences,
    preferenceWrites: () => preferenceWrites };
}

test('matching status keeps wrapped labels and counter values aligned', async ({ page }, testInfo) => {
  await openApp(page);
  const status = page.locator('.call-matching-status-content');
  await expect(status.locator('.ui-metric')).toHaveCount(5);
  for (const width of [1440, 1180, 390, 320]) {
    await page.setViewportSize({ width, height: 900 });
    const groups = await status.locator('.call-matching-metric-group').evaluateAll((sections) =>
      sections.map((section) => [...section.querySelectorAll('.ui-metric')].map((card) => {
        const bounds = card.getBoundingClientRect();
        const label = card.querySelector('.ui-metric-label').getBoundingClientRect();
        const value = card.querySelector('strong').getBoundingClientRect();
        return { top: bounds.top, bottom: bounds.bottom, height: bounds.height,
          labelTop: label.top, valueTop: value.top, labelBottom: label.bottom,
          left: bounds.left, right: bounds.right, textClipped: card.scrollWidth > card.clientWidth };
      })));
    const rows = width > 1100 ? [groups.flat()] : groups;
    for (const row of rows) {
      for (const property of ['top', 'bottom', 'height', 'labelTop', 'valueTop']) {
        const positions = row.map((card) => card[property]);
        expect(Math.max(...positions) - Math.min(...positions), `${property} at ${width}px`).toBeLessThan(1);
      }
      for (const card of row) {
        expect(card.labelBottom).toBeLessThanOrEqual(card.valueTop);
        expect(card.left).toBeGreaterThanOrEqual(0);
        expect(card.right).toBeLessThanOrEqual(width);
        expect(card.textClipped).toBe(false);
      }
    }
    if (width === 1440 || width === 390) {
      await status.screenshot({ path: testInfo.outputPath(`matching-status-${width}.png`) });
    }
  }
});

test('administrator sees only confirmed duplicates and compares receiver copies', async ({ page }) => {
  const data = snapshot([duplicate(71), { ...duplicate(70), outcome: 'INDEPENDENT' }]);
  await openApp(page, { matching: async () => ({ data }) });
  await expect(page.getByRole('heading', { name: 'Call matching' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Call matching' })).toHaveAttribute('aria-current', 'page');
  const table = page.locator('table[data-table-type="call-matching-duplicates"]');
  await expect(table.locator('tbody tr')).toHaveCount(1);
  await expect(table.locator('tbody td')).toHaveCount(8);
  await expect(table).toContainText('More usable voice frames');
  await expect(table).not.toContainText('Matching voice frames');
  await expect(table).toContainText('County Fire Dispatch');
  await expect(table).toContainText('27101');
  await expect(table).toContainText('Engine 4');
  await expect(table).toContainText('1204185');
  await expect(table).toContainText('Cuyahoga Simulcast');
  await expect(table).toContainText('RFSS 1 · Site 1');
  await expect(table).toContainText('Browser audio');
  await expect(table).not.toContainText('Single call');
  await expect(table).toHaveClass(/ui-record-list/);
  await expect(page.locator('.call-matching-metric-group').first()).toContainText('Matching now');
  await expect(page.locator('.call-matching-metric-group').first().locator('.ui-metric')).toHaveCount(2);
  await expect(page.locator('.call-matching-metric-group').last()).toContainText('This receiver session');
  await expect(page.locator('.call-matching-metric-group').last().locator('.ui-metric')).toHaveCount(3);
  await expect(page.locator('.call-matching-status-content')).toContainText('Calls with matched copies');
  await expect(page.locator('.call-matching-status-content')).toContainText('Extra copies removed');
  await expect(page.locator('.call-matching-status-content')).toContainText('Calls kept with incomplete matching');
  await expect(page.locator('.call-matching-workspace')).toContainText(
    'Latest 100 matched calls; totals above cover this receiver session.');
  await expect(page.locator('.call-matching-workspace .ui-toggle')).toHaveCount(0);
  await expect(page.locator('.call-matching-status-content')).not.toContainText('Diagnostic file');
  await expect(page.locator('.call-matching-status-content')).not.toContainText('Diagnostic queue');
  await expect(page.locator('.call-matching-history-footer')).toHaveCount(0);
  const compare = page.getByRole('button', { name: 'Compare matched call 71' });
  await compare.click();
  const dialog = page.getByRole('dialog', { name: 'Matched call details' });
  await expect(dialog).toContainText('167 usable frames');
  await expect(dialog).toContainText('164 usable frames');
  await expect(dialog).toContainText('Time overlap');
  await expect(dialog).not.toContainText('s shared');
  await expect(dialog).toContainText('Configuration 1');
  await expect(dialog).toContainText('Configuration 2');
  await expect(dialog).toContainText('851.01250 MHz');
  await expect(dialog).toContainText('852.01250 MHz');
  await expect(dialog).toContainText('Timeslot');
  await expect(dialog).toContainText('Selected for');
  await expect(dialog).toContainText('Matching voice frames');
  await expect(dialog).toContainText('27101');
  await expect(dialog).toContainText('1204185');
  await expect(dialog.locator('.call-matching-comparison-summary .ui-fact')).toHaveCount(7);
  await expect(dialog.locator('.call-matching-comparison-table tbody tr')).toHaveCount(12);
  await expect(dialog.locator('.call-matching-comparison-table thead th.ui-data-table-selected-column'))
    .toHaveCount(1);
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(compare).toBeFocused();
});

test('inferred matches stay in the details without splitting session totals', async ({ page }) => {
  const decision = duplicate();
  decision.evidence.merge_proof_counts = { matching_source_identity_fallback: 1 };
  await openApp(page, { matching: async () => ({ data: snapshot([decision]) }) });
  const table = page.locator('table[data-table-type="call-matching-duplicates"]');
  await expect(table).not.toContainText('Inferred');
  await expect(page.locator('.call-matching-session-counts .ui-metric')).toHaveCount(3);
  await page.getByRole('button', { name: 'Compare matched call 71' }).click();
  await expect(page.getByRole('dialog', { name: 'Matched call details' })).toContainText(
    'Inferred from matching radio and overlapping timing');
});

test('same-configuration encrypted matches expose their basis only in the comparison', async ({ page }) => {
  const decision = duplicate();
  decision.call_identity.encryption_state = 'ENCRYPTED';
  decision.evidence.merge_proof_counts = { matching_encryption_message_indicator: 1 };
  decision.legs[1].configuration_ref = 1;
  decision.legs[1].channel_name = decision.legs[0].channel_name;
  decision.legs[1].site = 1;
  await openApp(page, { matching: async () => ({ data: snapshot([decision]) }) });
  const table = page.locator('table[data-table-type="call-matching-duplicates"]');
  await expect(table).not.toContainText('encryption');
  await expect(page.locator('.call-matching-status-content')).not.toContainText('Encrypted');
  await page.getByRole('button', { name: 'Compare matched call 71' }).click();
  const dialog = page.getByRole('dialog', { name: 'Matched call details' });
  await expect(dialog).toContainText('Matching encryption message indicator');
  const configuration = dialog.locator('.call-matching-comparison-table tbody tr')
    .filter({ has: page.getByRole('rowheader', { name: 'Configuration', exact: true }) });
  await expect(configuration.locator('td')).toHaveText(['Configuration 1', 'Configuration 1']);
});

test('non-administrator never requests the protected monitor', async ({ page }) => {
  const app = await openApp(page, { tier: 'user' });
  await expect(page.getByRole('heading', { name: 'Access', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Call matching' })).toHaveCount(0);
  expect(app.requests()).toBe(0);
});

test('bounded duplicate history keeps the selected sequence across refreshes', async ({ page }) => {
  let current = snapshot(Array.from({ length: 125 }, (_, index) => duplicate(index + 1)));
  await openApp(page, { matching: async () => ({ data: current }) });
  const table = page.locator('table[data-table-type="call-matching-duplicates"]');
  await expect(table.locator('tbody tr')).toHaveCount(20);
  await expect(table.locator('tbody tr').first()).toHaveAttribute('data-id', '125');
  await expect(page.locator('.call-matching-history-pager'))
    .toContainText('Matched calls 1–20 of 100 · Page 1 of 5');
  await page.getByRole('button', { name: 'Compare matched call 125' }).click();
  await expect(page.locator('tbody tr[data-id="125"]')).toHaveClass(/selected/);
  current = snapshot([duplicate(126), duplicate(125)]);
  await expect(table.locator('tbody tr').first()).toHaveAttribute('data-id', '126', { timeout: 3500 });
  await expect(page.locator('tbody tr[data-id="125"]')).toHaveClass(/selected/);
  await expect(page.getByRole('dialog', { name: 'Matched call details' })).toBeVisible();
  current = snapshot([duplicate(127)]);
  await expect(page.getByRole('dialog', { name: 'Matched call details' })).not.toBeVisible({ timeout: 3500 });
});

test('new duplicate calls use the shared row fade across refreshes', async ({ page }) => {
  let current = snapshot([duplicate(71)]);
  const app = await openApp(page, { matching: async () => ({ data: current }) });
  const table = page.locator('table[data-table-type="call-matching-duplicates"]');
  const initial = table.locator('tbody tr[data-id="71"]');
  await expect(initial).not.toHaveClass(/activity-row-new/);

  current = snapshot([duplicate(72), duplicate(71)]);
  const added = table.locator('tbody tr[data-id="72"]');
  await expect(added).toHaveClass(/activity-row-new/, { timeout: 3500 });
  await expect(initial).not.toHaveClass(/activity-row-new/);
  expect(await added.locator('td').first().evaluate((cell) => getComputedStyle(cell).animationName))
    .toBe('activity-row-highlight');
  await expect.poll(() => app.requests()).toBeGreaterThanOrEqual(3);
  await expect(added).toHaveClass(/activity-row-new/);
  await expect(added).not.toHaveClass(/activity-row-new/, { timeout: 10_000 });
});

test('confirmed duplicate history paginates and clamps after live history shrinks', async ({ page }) => {
  let current = snapshot(Array.from({ length: 45 }, (_, index) => duplicate(index + 1)));
  await openApp(page, { matching: async () => ({ data: current }) });
  const table = page.locator('table[data-table-type="call-matching-duplicates"]');
  const rows = table.locator('tbody tr');
  const pager = page.locator('.call-matching-history-pager');
  const previous = pager.getByRole('button', { name: 'Previous' });
  const next = pager.getByRole('button', { name: 'Next' });

  await expect(rows).toHaveCount(20);
  await expect(rows.first()).toHaveAttribute('data-id', '45');
  await expect(rows.last()).toHaveAttribute('data-id', '26');
  await expect(pager).toContainText('Matched calls 1–20 of 45 · Page 1 of 3');
  await expect(previous).toBeDisabled();
  await expect(next).toBeEnabled();

  await next.click();
  await expect(rows).toHaveCount(20);
  await expect(rows.first()).toHaveAttribute('data-id', '25');
  await expect(rows.last()).toHaveAttribute('data-id', '6');
  await expect(pager).toContainText('Matched calls 21–40 of 45 · Page 2 of 3');
  await expect(previous).toBeEnabled();
  await expect(next).toBeEnabled();

  await next.click();
  await expect(rows).toHaveCount(5);
  await expect(rows.first()).toHaveAttribute('data-id', '5');
  await expect(rows.last()).toHaveAttribute('data-id', '1');
  await expect(pager).toContainText('Matched calls 41–45 of 45 · Page 3 of 3');
  await expect(next).toBeDisabled();

  current = snapshot([duplicate(46)]);
  await expect(pager).toContainText('Matched calls 1–1 of 1 · Page 1 of 1', { timeout: 3500 });
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toHaveAttribute('data-id', '46');
  await expect(previous).toBeDisabled();
  await expect(next).toBeDisabled();
});

test('slow polling has one request in flight and navigation aborts it', async ({ page }) => {
  let release;
  const app = await openApp(page, { matching: async (count) => {
    if (count === 1) return { data: snapshot() };
    await new Promise((resolve) => { release = resolve; });
    return { data: snapshot() };
  } });
  await expect(page.locator('.call-matching-live-status')).toContainText('Live');
  await expect.poll(() => app.requests()).toBe(2);
  await page.waitForTimeout(1300);
  expect(app.requests()).toBe(2);
  await page.getByRole('link', { name: 'About' }).click();
  await expect(page.getByRole('heading', { name: 'Credits & Licensing' })).toBeVisible();
  release();
  await page.waitForTimeout(1300);
  expect(app.requests()).toBe(2);
});

test('late authorization loss clears details and stops polling', async ({ page }) => {
  const app = await openApp(page, { matching: async (count) => count === 1 ?
    { data: snapshot() } : { status: 403, message: 'Administrator required' } });
  await page.getByRole('button', { name: 'Compare matched call 71' }).click();
  await expect(page.locator('.call-matching-live-status')).toContainText('Access denied', { timeout: 3500 });
  await expect(page.getByRole('dialog', { name: 'Matched call details' })).not.toBeVisible();
  await expect(page.locator('table[data-table-type="call-matching-duplicates"] tbody td.empty')).toBeVisible();
  const count = app.requests();
  await page.waitForTimeout(1300);
  expect(app.requests()).toBe(count);
});

test('empty and failed snapshots recover on a later poll', async ({ page }) => {
  const app = await openApp(page, { matching: async (count) => count === 1 ?
    { status: 503, message: 'Diagnostics unavailable' } :
    { data: count === 2 ? snapshot([]) : snapshot() } });
  await expect(page.locator('.call-matching-live-status')).toContainText('Unavailable');
  await expect(page.locator('.call-matching-status-content')).toContainText('Diagnostics unavailable');
  await expect(page.locator('table[data-table-type="call-matching-duplicates"] tbody td.empty')).toBeVisible();
  await expect.poll(() => app.requests()).toBeGreaterThanOrEqual(2);
  await expect(page.locator('.call-matching-live-status')).toContainText('Live');
  await expect(page.locator('.call-matching-health-state')).toContainText('Healthy');
  await expect(page.locator('table[data-table-type="call-matching-duplicates"] tbody td.empty')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Compare matched call 71' })).toBeVisible({ timeout: 3500 });
});

test('published warning state appears without file-debug health', async ({ page }) => {
  const data = snapshot();
  data.resolver.health_state = 'WARNING';
  await openApp(page, { matching: async () => ({ data }) });
  await expect(page.locator('.call-matching-health-state')).toContainText('Warning');
  await expect(page.locator('.call-matching-health-summary')).not.toContainText('Diagnostic file');
  await expect(page.locator('.call-matching-status-content')).toContainText('Calls awaiting a decision');
});

test('saved displayed fields remain editable and do not force a wide history table', async ({ page }) => {
  const schema = ['time', 'talkgroup', 'radio', 'site', 'copies', 'match', 'outputs', 'action'];
  const saved = { schema, column_order: ['talkgroup', 'time', 'site', 'radio', 'copies', 'match', 'outputs', 'action'],
    hidden_columns: ['radio'], column_widths: { talkgroup: 1200 }, collapsed_groups: [] };
  const app = await openApp(page, { tables: { 'call-matching-duplicates': saved } });
  const table = page.locator('table[data-table-type="call-matching-duplicates"]');
  await expect(table).toHaveClass(/ui-record-list-custom-order/);
  await expect(table).toHaveClass(/has-custom-field-order/);
  await expect(table.locator('tbody td[data-column="radio"]')).toHaveCount(0);
  expect(await table.evaluate(element => element.scrollWidth <= element.parentElement.clientWidth + 1)).toBe(true);
  await page.getByRole('button', { name: 'Displayed fields', exact: true }).click();
  const menu = page.getByRole('dialog', { name: 'Displayed fields', exact: true });
  await expect(menu.getByRole('checkbox', { name: 'Show Compare receiver copies field' })).toBeDisabled();
  await menu.getByRole('checkbox', { name: 'Show Radio field' }).check();
  await expect(table.locator('tbody td[data-column="radio"]')).toHaveCount(1);
  await expect.poll(() => app.preferenceWrites().length).toBe(1);
  expect(app.preferences().tables['call-matching-duplicates'].column_widths.talkgroup).toBe(1200);
  await menu.getByRole('button', { name: 'Move Talkgroup right', exact: true }).click();
  await expect.poll(() => table.locator('tbody tr').first().locator('td').first().getAttribute('data-column'))
    .toBe('time');
  await menu.getByRole('button', { name: 'Reset displayed fields' }).click();
  await expect(table).not.toHaveClass(/ui-record-list-custom-order/);
  await expect(table).not.toHaveClass(/has-custom-field-order/);
  await expect(table.locator('tbody td')).toHaveCount(8);
  await expect(table.locator('tbody tr').first().locator('td').first()).toHaveAttribute('data-column', 'talkgroup');
  await expect(menu.locator('.table-layout-column > span')).toHaveText([
    'Talkgroup', 'Radio', 'Selected site', 'Compare receiver copies', 'Matched at', 'Copies',
    'Why selected', 'Selected for'
  ]);
  await expect(menu.getByRole('button', { name: 'Move Talkgroup left', exact: true })).toBeDisabled();
  expect(app.preferences().tables['call-matching-duplicates']).toBeUndefined();
});

const matchingFieldSchema = ['time', 'talkgroup', 'radio', 'site', 'copies', 'match', 'outputs', 'action'];

for (const width of [1440, 390, 320]) {
  for (const layout of ['default', 'legacy', 'hidden-and-reordered']) {
    for (const longNames of [false, true]) {
      test(`twenty matched calls fit ${width}px with ${layout} fields and ${longNames ? 'long' : 'normal'} names`,
        async ({ page }, testInfo) => {
          await page.setViewportSize({ width, height: width === 1440 ? 1000 : 844 });
          const records = Array.from({ length: 20 }, (_, index) => {
            const record = duplicate(71 - index);
            if (longNames) {
              record.call_identity.destination_alias =
                'County Public Safety Fire and Emergency Operations Dispatch';
              record.call_identity.source_alias = 'Regional Fire Department Engine 14 Command Radio';
              record.legs[0].channel_name = index % 2 ?
                'Northwest County Public Safety Regional Simulcast' :
                'NORTHWESTCOUNTYPUBLICSAFETYEMERGENCYOPERATIONS';
            }
            return record;
          });
          const saved = layout === 'default' ? null : {
            schema: matchingFieldSchema,
            column_order: layout === 'legacy' ? matchingFieldSchema :
              ['outputs', 'action', 'talkgroup', 'site', 'radio', 'match', 'copies', 'time'],
            hidden_columns: layout === 'hidden-and-reordered' ? ['radio', 'time'] : [],
            column_widths: { talkgroup: 1200 }, collapsed_groups: []
          };
          const app = await openApp(page, {
            matching: async () => ({ data: snapshot(records) }),
            ...(saved ? { tables: { 'call-matching-duplicates': saved } } : {})
          });
          const table = page.locator('table[data-table-type="call-matching-duplicates"]');
          const rows = table.locator('tbody tr');
          const pager = page.getByRole('navigation', { name: 'Matched call pages' });
          await expect(rows).toHaveCount(20);
          await expect(pager).toContainText('Matched calls 1–20 of 20 · Page 1 of 1');
          await expect(rows.first().locator('td')).toHaveCount(saved?.hidden_columns.length ? 6 : 8);
          await expect(rows.first()).toContainText(records[0].call_identity.destination_alias);
          await expect(rows.first()).toContainText(records[0].legs[0].channel_name);

          const geometry = await table.evaluate((element) => {
            const rect = (node) => {
              const bounds = node.getBoundingClientRect();
              return { x: bounds.x, y: bounds.y, right: bounds.right, bottom: bounds.bottom,
                width: bounds.width, height: bounds.height };
            };
            const inside = (child, parent) => child.x >= parent.x - 1 && child.y >= parent.y - 1 &&
              child.right <= parent.right + 1 && child.bottom <= parent.bottom + 1;
            const issues = [];
            const heights = [];
            element.querySelectorAll('tbody tr').forEach((row) => {
              const rowBounds = rect(row);
              heights.push(rowBounds.height);
              const fields = [...row.querySelectorAll('td')];
              fields.forEach((field, index) => {
                const fieldBounds = rect(field);
                if (!inside(fieldBounds, rowBounds)) issues.push(`${row.dataset.id}:${field.dataset.column}:outside`);
                if (field.scrollWidth > field.clientWidth + 1) {
                  issues.push(`${row.dataset.id}:${field.dataset.column}:overflow`);
                }
                fields.slice(index + 1).forEach((other) => {
                  const otherBounds = rect(other);
                  if (Math.min(fieldBounds.right, otherBounds.right) -
                      Math.max(fieldBounds.x, otherBounds.x) > 1 &&
                      Math.min(fieldBounds.bottom, otherBounds.bottom) -
                      Math.max(fieldBounds.y, otherBounds.y) > 1) {
                    issues.push(`${row.dataset.id}:${field.dataset.column}/${other.dataset.column}:overlap`);
                  }
                });
              });
              const compare = row.querySelector('.call-matching-compare');
              if (!compare || !inside(rect(compare), rowBounds)) issues.push(`${row.dataset.id}:compare:outside`);
            });
            const last = rect(element.querySelector('tbody tr:last-child'));
            const pager = document.querySelector('.call-matching-history-pager');
            return { issues, heights, lastBottom: last.bottom, pagerTop: rect(pager).y,
              pagerInRow: Boolean(pager.closest('tr')),
              documentOverflow: document.documentElement.scrollWidth > window.innerWidth + 1,
              tableOverflow: element.scrollWidth > element.parentElement.clientWidth + 1 };
          });
          expect(geometry.issues).toEqual([]);
          expect(geometry.pagerInRow).toBe(false);
          expect(geometry.pagerTop).toBeGreaterThanOrEqual(geometry.lastBottom - 1);
          expect(geometry.documentOverflow).toBe(false);
          expect(geometry.tableOverflow).toBe(false);
          if (width === 1440) {
            expect(Math.max(...geometry.heights)).toBeLessThanOrEqual(longNames ? 230 : 180);
          }
          if (saved) {
            expect(app.preferences().tables['call-matching-duplicates']).toEqual(saved);
            expect(app.preferenceWrites()).toHaveLength(0);
          }
          if (layout !== 'hidden-and-reordered' &&
              ((width !== 320 && !longNames) || (width === 320 && longNames))) {
            await page.screenshot({
              path: testInfo.outputPath(`call-matching-twenty-records-${layout}-${width}.png`), fullPage: true
            });
          }
        });
    }
  }
}

test('comparison keeps every receiver copy and trusts the selected copy index', async ({ page }) => {
  const decision = duplicate();
  decision.legs[0].selected = false;
  const third = { ...copy(3, false), channel_name: 'West Tower', site: 7,
    fec_protected_bit_count: 0, ingress_loss: true, audio_truncated: true };
  decision.legs.push(third);
  await openApp(page, { matching: async () => ({ data: snapshot([decision]) }) });
  await page.getByRole('button', { name: 'Compare matched call 71' }).click();
  const dialog = page.getByRole('dialog', { name: 'Matched call details' });
  await expect(dialog.locator('.call-matching-comparison-table thead th')).toHaveCount(4);
  await expect(dialog.locator('.ui-data-table-selected-column').first()).toContainText('Selected copy');
  await expect(dialog.locator('.ui-data-table-selected-column').first()).toContainText('Cuyahoga Simulcast');
  await expect(dialog).toContainText('West Tower');
  await expect(dialog).toContainText('Not measured');
  await expect(dialog).toContainText('Receiver input loss');
  await expect(dialog).toContainText('Audio truncated');
  await expect(dialog.locator('.call-matching-comparison-table tbody tr')).toHaveCount(12);
});

test('transient polling failure retains the latest call list and health', async ({ page }) => {
  await openApp(page, { matching: async (count) => count === 1 ? { data: snapshot() } :
    { status: 503, message: 'Temporarily unavailable' } });
  await expect(page.locator('.call-matching-live-status')).toContainText('Unavailable', { timeout: 3500 });
  await expect(page.locator('.call-matching-health-state')).toContainText('Healthy');
  await expect(page.getByRole('button', { name: 'Compare matched call 71' })).toBeVisible();
  await expect(page.locator('.call-matching-metric-group')).toHaveCount(2);
});

test('a new receiver session resets history paging and closes old comparisons', async ({ page }) => {
  let current = snapshot(Array.from({ length: 45 }, (_, index) => duplicate(index + 1)));
  await openApp(page, { matching: async () => ({ data: current }) });
  await page.locator('.call-matching-history-pager').getByRole('button', { name: 'Next' }).click();
  await page.getByRole('button', { name: 'Compare matched call 25' }).click();
  current = { ...current, session_id: 'replacement-session' };
  await expect(page.getByRole('dialog', { name: 'Matched call details' })).not.toBeVisible({ timeout: 3500 });
  await expect(page.locator('.call-matching-history-pager')).toContainText('Page 1 of 3');
});

test('pausing freezes matching metrics and history while polling keeps the newest snapshot ready', async ({ page }) => {
  let current = snapshot([duplicate(71)]);
  const app = await openApp(page, { matching: async () => ({ data: current }) });
  const metrics = page.locator('.call-matching-status-content .ui-metric-copy > strong');
  const rows = page.locator('table[data-table-type="call-matching-duplicates"] tbody tr');
  await expect(rows.first()).toHaveAttribute('data-id', '71');
  const originalMetrics = await metrics.allTextContents();
  const pause = page.getByRole('button', { name: 'Pause call matching monitor', exact: true });
  await expect(pause).toHaveAttribute('aria-pressed', 'false');
  await expect(pause).toHaveClass(/ui-icon-button/);
  await pause.click();
  const resume = page.getByRole('button', { name: 'Resume call matching monitor', exact: true });
  await expect(resume).toHaveAttribute('aria-pressed', 'true');

  let before = app.requests();
  current = snapshot([duplicate(72)]);
  current.resolver.active_leg_count = 9;
  current.resolver.counters.merged_logical_calls = 222;
  await expect.poll(() => app.requests()).toBeGreaterThanOrEqual(before + 2);
  await expect(metrics).toHaveText(originalMetrics);
  await expect(rows.first()).toHaveAttribute('data-id', '71');

  before = app.requests();
  current = snapshot([duplicate(73)]);
  current.resolver.active_leg_count = 12;
  current.resolver.counters.merged_logical_calls = 333;
  await expect.poll(() => app.requests()).toBeGreaterThanOrEqual(before + 2);
  await expect(metrics).toHaveText(originalMetrics);
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toHaveAttribute('data-id', '71');

  await resume.click();
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toHaveAttribute('data-id', '73');
  await expect(metrics).toHaveText(['12', '1', '333', '153', '2']);
  await expect(page.getByRole('button', { name: 'Pause call matching monitor', exact: true }))
    .toHaveAttribute('aria-pressed', 'false');
});

test('paused history keeps paging, displayed fields, and copy comparison usable', async ({ page }) => {
  let current = snapshot(Array.from({ length: 45 }, (_, index) => duplicate(index + 1)));
  const app = await openApp(page, { matching: async () => ({ data: current }) });
  const table = page.locator('table[data-table-type="call-matching-duplicates"]');
  const pager = page.getByRole('navigation', { name: 'Matched call pages' });
  await expect(table.locator('tbody tr').first()).toHaveAttribute('data-id', '45');
  await page.getByRole('button', { name: 'Pause call matching monitor', exact: true }).click();
  const before = app.requests();
  current = snapshot([duplicate(100)]);
  await expect.poll(() => app.requests()).toBeGreaterThanOrEqual(before + 2);
  await pager.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(pager).toContainText('Matched calls 21–40 of 45 · Page 2 of 3');
  await expect(table.locator('tbody tr').first()).toHaveAttribute('data-id', '25');

  await page.getByRole('button', { name: 'Displayed fields', exact: true }).click();
  const menu = page.getByRole('dialog', { name: 'Displayed fields', exact: true });
  await menu.getByRole('checkbox', { name: 'Show Radio field' }).uncheck();
  await expect(table.locator('tbody td[data-column="radio"]')).toHaveCount(0);
  await expect.poll(() => app.preferenceWrites().length).toBe(1);
  await page.keyboard.press('Escape');
  const compare = page.getByRole('button', { name: 'Compare matched call 25', exact: true });
  await compare.click();
  const dialog = page.getByRole('dialog', { name: 'Matched call details' });
  await expect(dialog).toContainText('Cuyahoga Simulcast');
  await expect(dialog.locator('.call-matching-comparison-table tbody tr')).toHaveCount(12);
  await page.keyboard.press('Escape');
  await expect(compare).toBeFocused();
  await expect(pager).toContainText('Page 2 of 3');
  await pager.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(pager).toContainText('Matched calls 41–45 of 45 · Page 3 of 3');
  await page.getByRole('button', { name: 'Resume call matching monitor', exact: true }).click();
  await expect(pager).toContainText('Matched calls 1–1 of 1 · Page 1 of 1');
  await expect(table.locator('tbody tr').first()).toHaveAttribute('data-id', '100');
  await expect(table.locator('tbody td[data-column="radio"]')).toHaveCount(0);
});

test('resuming a paused replacement receiver resets frozen paging and the selected copy', async ({ page }) => {
  let current = snapshot(Array.from({ length: 45 }, (_, index) => duplicate(index + 1)));
  const app = await openApp(page, { matching: async () => ({ data: current }) });
  await expect(page.locator('.call-matching-live-status')).toContainText('Live');
  await page.getByRole('button', { name: 'Pause call matching monitor', exact: true }).click();
  const pager = page.getByRole('navigation', { name: 'Matched call pages', includeHidden: true });
  await pager.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByRole('button', { name: 'Compare matched call 25', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Matched call details' });
  const before = app.requests();
  current = { ...snapshot([duplicate(200)]), session_id: 'replacement-session' };
  await expect.poll(() => app.requests()).toBeGreaterThanOrEqual(before + 2);
  await expect(dialog).toBeVisible();
  await expect(pager).toContainText('Page 2 of 3');
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Resume call matching monitor', exact: true }).click();
  await expect(pager).toContainText('Matched calls 1–1 of 1 · Page 1 of 1');
  await expect(page.locator('table[data-table-type="call-matching-duplicates"] tbody tr').first())
    .toHaveAttribute('data-id', '200');
  await expect(dialog).not.toBeVisible();
  await expect(page.locator('tbody tr.selected')).toHaveCount(0);
});

for (const authorizationStatus of [401, 403]) {
  test(`authorization loss ${authorizationStatus} clears paused data and stops polling`, async ({ page }) => {
    let authorized = true;
    const app = await openApp(page, { matching: async () => authorized ?
      { data: snapshot() } : { status: authorizationStatus, message: 'Administrator required' } });
    await expect(page.getByRole('button', { name: 'Compare matched call 71', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Pause call matching monitor', exact: true }).click();
    await page.getByRole('button', { name: 'Compare matched call 71', exact: true }).click();
    authorized = false;
    await expect(page.locator('.call-matching-live-status')).toContainText('Access denied', { timeout: 3500 });
    await expect(page.getByRole('dialog', { name: 'Matched call details' })).not.toBeVisible();
    await expect(page.locator('table[data-table-type="call-matching-duplicates"] tbody td.empty')).toBeVisible();
    await expect(page.locator('.call-matching-status-content .ui-metric')).toHaveCount(0);
    await expect(page.getByRole('navigation', { name: 'Matched call pages' })).toHaveCount(0);
    const count = app.requests();
    await page.waitForTimeout(1300);
    expect(app.requests()).toBe(count);
  });
}

test('pausing during an in-flight update freezes its result without starting overlapping requests', async ({ page }) => {
  let release;
  const app = await openApp(page, { matching: async (count) => {
    if (count === 1) return { data: snapshot([duplicate(71)]) };
    if (count === 2) await new Promise((resolve) => { release = resolve; });
    return { data: snapshot([duplicate(72)]) };
  } });
  const rows = page.locator('table[data-table-type="call-matching-duplicates"] tbody tr');
  await expect.poll(() => app.requests()).toBe(2);
  await page.getByRole('button', { name: 'Pause call matching monitor', exact: true }).click();
  await page.waitForTimeout(1300);
  expect(app.requests()).toBe(2);
  await expect(rows.first()).toHaveAttribute('data-id', '71');
  release();
  await expect.poll(() => app.requests()).toBeGreaterThanOrEqual(3);
  await expect(rows.first()).toHaveAttribute('data-id', '71');
  await page.getByRole('button', { name: 'Resume call matching monitor', exact: true }).click();
  await expect(rows.first()).toHaveAttribute('data-id', '72');
});

test('paused error recovery preserves the frozen view and navigation cancels future polling', async ({ page }) => {
  let response = { data: snapshot([duplicate(71)]) };
  const app = await openApp(page, { matching: async () => response });
  const rows = page.locator('table[data-table-type="call-matching-duplicates"] tbody tr');
  await expect(rows.first()).toHaveAttribute('data-id', '71');
  await page.getByRole('button', { name: 'Pause call matching monitor', exact: true }).click();
  let before = app.requests();
  response = { data: snapshot([duplicate(72)]) };
  await expect.poll(() => app.requests()).toBeGreaterThanOrEqual(before + 2);
  await expect(rows.first()).toHaveAttribute('data-id', '71');
  response = { status: 503, message: 'Temporarily unavailable' };
  await expect(page.locator('.call-matching-live-status')).toContainText('Unavailable', { timeout: 3500 });
  await expect(page.locator('.call-matching-pause-state')).toBeVisible();
  await expect(page.locator('.call-matching-pause-state')).toHaveText('Paused');
  await expect(rows.first()).toHaveAttribute('data-id', '71');
  await page.getByRole('button', { name: 'Resume call matching monitor', exact: true }).click();
  await expect(rows.first()).toHaveAttribute('data-id', '71');
  await expect(page.locator('.call-matching-connection-state')).toContainText('Unavailable');
  await expect(page.locator('.call-matching-connection-state')).not.toContainText('Live');
  await page.getByRole('button', { name: 'Pause call matching monitor', exact: true }).click();
  before = app.requests();
  response = { data: snapshot([duplicate(73)]) };
  await expect.poll(() => app.requests()).toBeGreaterThanOrEqual(before + 2);
  await expect(rows.first()).toHaveAttribute('data-id', '71');
  await page.getByRole('button', { name: 'Resume call matching monitor', exact: true }).click();
  await expect(rows.first()).toHaveAttribute('data-id', '73');
  await page.getByRole('button', { name: 'Pause call matching monitor', exact: true }).click();
  await page.getByRole('link', { name: 'About', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Credits & Licensing' })).toBeVisible();
  const count = app.requests();
  await page.waitForTimeout(1300);
  expect(app.requests()).toBe(count);
});

for (const theme of ['light', 'dark']) {
  test(`pause and resume fit at 320px and retain keyboard focus in ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 320, height: 844 });
    await page.emulateMedia({ colorScheme: theme });
    let current = snapshot([duplicate(71)]);
    const app = await openApp(page, { theme, matching: async () => ({ data: current }) });
    await expect(page.getByRole('button', { name: 'Compare matched call 71', exact: true })).toBeVisible();
    const pause = page.getByRole('button', { name: 'Pause call matching monitor', exact: true });
    await pause.focus();
    await pause.press('Enter');
    const resume = page.getByRole('button', { name: 'Resume call matching monitor', exact: true });
    await expect(resume).toBeFocused();
    await expect(resume).toHaveAttribute('aria-pressed', 'true');
    const before = app.requests();
    current = snapshot([duplicate(72)]);
    await expect.poll(() => app.requests()).toBeGreaterThanOrEqual(before + 2);
    await expect(resume).toBeFocused();
    const fit = await resume.evaluate((button) => {
      const bounds = button.getBoundingClientRect();
      const header = button.closest('.ui-section-title').getBoundingClientRect();
      return bounds.x >= header.x - 1 && bounds.right <= header.right + 1 &&
        bounds.y >= header.y - 1 && bounds.bottom <= header.bottom + 1 &&
        document.documentElement.scrollWidth <= window.innerWidth + 1;
    });
    expect(fit).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`call-matching-paused-320-${theme}.png`), fullPage: true });
    await resume.press('Space');
    const live = page.getByRole('button', { name: 'Pause call matching monitor', exact: true });
    await expect(live).toBeFocused();
    await expect(live).toHaveAttribute('aria-pressed', 'false');
    await expect(page.locator('table[data-table-type="call-matching-duplicates"] tbody tr').first())
      .toHaveAttribute('data-id', '72');
  });
}

for (const [published, label] of [['HEALTHY', 'Healthy'], ['WARNING', 'Warning'], ['DRAINING', 'Stopping'],
  ['STOPPED', 'Stopped'], ['UNRESPONSIVE', 'Not responding']]) {
  test(`published ${published} matching status keeps its label`, async ({ page }) => {
    const data = snapshot();
    data.resolver.health_state = published;
    await openApp(page, { matching: async () => ({ data }) });
    await expect(page.locator('.call-matching-health-state')).toHaveText(label);
  });
}

for (const [name, viewport, theme] of [
  ['desktop-light', { width: 1440, height: 1000 }, 'light'],
  ['mobile-dark', { width: 390, height: 844 }, 'dark']
]) {
  test(`call matching ${name} page and comparison render`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    await page.emulateMedia({ colorScheme: theme });
    await openApp(page, { theme });
    await expect(page.locator('.call-matching-live-status')).toContainText('Live');
    await page.screenshot({ path: testInfo.outputPath(`call-matching-${name}-page.png`), fullPage: true });
    await page.getByRole('button', { name: 'Pause call matching monitor', exact: true }).click();
    const resume = page.getByRole('button', { name: 'Resume call matching monitor', exact: true });
    await expect(resume).toHaveAttribute('aria-pressed', 'true');
    await page.screenshot({ path: testInfo.outputPath(`call-matching-${name}-paused.png`), fullPage: true });
    await resume.click();
    await page.getByRole('button', { name: 'Compare matched call 71' }).click();
    await expect(page.getByRole('dialog', { name: 'Matched call details' })).toBeVisible();
    if (viewport.width < 760) {
      await expect(page.locator('.call-matching-comparison-hint')).toBeVisible();
    }
    await page.screenshot({ path: testInfo.outputPath(`call-matching-${name}-modal.png`) });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
    expect(overflow).toBe(false);
  });
}
