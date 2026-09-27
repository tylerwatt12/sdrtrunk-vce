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
      accepting: true, writer_terminated: false, queued_records: 0, queue_capacity: 256,
      recorded_confirmations_observed: 142, stream_submitted_confirmations_observed: 139,
      records_dropped_at_queue: 0, records_rejected_after_close: 0,
      file_records_dropped: 0, oversized_records_dropped: 0, file_write_failures: 0,
      file_state: 'ACTIVE', file_health_state: 'ACTIVE',
      active_file_bytes: 18_400_000, maximum_file_bytes: 50_000_000,
      retained_file_count: 3, maximum_files: 8
    },
    history: {
      recent_decisions_evicted: 6, recent_decisions_retained: duplicates.length,
      duplicate_decisions_shown: duplicates.filter((item) => item.outcome === 'MERGED').length,
      limit: 100
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
  const preferences = JSON.parse(JSON.stringify(defaultPreferences));
  preferences.appearance.theme = options.theme || 'light';
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === '/api/v1/auth/session') {
      await route.fulfill({ json: { data: session(options.tier || 'admin') } });
    } else if (pathname === '/api/v1/me/preferences') {
      await route.fulfill({ json: { revision: 1, preferences } });
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
  return { requests: () => requestCount };
}

test('administrator sees only confirmed duplicates and compares receiver copies', async ({ page }) => {
  const data = snapshot([duplicate(71), { ...duplicate(70), outcome: 'INDEPENDENT' }]);
  await openApp(page, { matching: async () => ({ data }) });
  await expect(page.getByRole('heading', { name: 'Call matching monitor' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Call matching' })).toHaveAttribute('aria-current', 'page');
  const table = page.locator('table[data-table-type="call-matching-duplicates"]');
  await expect(table.locator('tbody tr')).toHaveCount(1);
  await expect(table).toContainText('Matching voice frames');
  await expect(table).not.toContainText('Single call');
  await expect(page.locator('.call-matching-workspace .ui-toggle')).toHaveCount(0);
  const compare = page.getByRole('button', { name: 'Compare duplicate decision 71' });
  await compare.click();
  const dialog = page.getByRole('dialog', { name: 'Duplicate call details' });
  await expect(dialog).toContainText('167 usable frames');
  await expect(dialog).toContainText('164 usable frames');
  await expect(dialog).toContainText('3.5 s shared');
  await expect(dialog).toContainText('Matching voice frames');
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(compare).toBeFocused();
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
  await expect(table.locator('tbody tr')).toHaveCount(100);
  await expect(table.locator('tbody tr').first()).toHaveAttribute('data-id', '125');
  await page.getByRole('button', { name: 'Compare duplicate decision 125' }).click();
  await expect(page.locator('tbody tr[data-id="125"]')).toHaveClass(/selected/);
  current = snapshot([duplicate(126), duplicate(125)]);
  await expect(table.locator('tbody tr').first()).toHaveAttribute('data-id', '126', { timeout: 3500 });
  await expect(page.locator('tbody tr[data-id="125"]')).toHaveClass(/selected/);
  await expect(page.getByRole('dialog', { name: 'Duplicate call details' })).toBeVisible();
  current = snapshot([duplicate(127)]);
  await expect(page.getByRole('dialog', { name: 'Duplicate call details' })).not.toBeVisible({ timeout: 3500 });
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
  await page.getByRole('button', { name: 'Compare duplicate decision 71' }).click();
  await expect(page.locator('.call-matching-live-status')).toContainText('Access denied', { timeout: 3500 });
  await expect(page.getByRole('dialog', { name: 'Duplicate call details' })).not.toBeVisible();
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
  await expect(page.locator('.call-matching-status-content')).toContainText('Healthy');
  await expect(page.locator('table[data-table-type="call-matching-duplicates"] tbody td.empty')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Compare duplicate decision 71' })).toBeVisible({ timeout: 3500 });
});

test('published warning states appear in matching and file health', async ({ page }) => {
  const data = snapshot();
  data.resolver.health_state = 'WARNING';
  data.diagnostic_status.file_health_state = 'WARNING';
  await openApp(page, { matching: async () => ({ data }) });
  await expect(page.locator('.call-matching-health-state')).toContainText('Warning');
  await expect(page.locator('.call-matching-health-summary')).toContainText('Diagnostic file');
  await expect(page.locator('.call-matching-health-summary')).toContainText('Warning');
});

for (const [name, viewport, theme] of [
  ['desktop-light', { width: 1440, height: 1000 }, 'light'],
  ['mobile-dark', { width: 390, height: 844 }, 'dark']
]) {
  test(`call matching ${name} page and comparison render`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.emulateMedia({ colorScheme: theme });
    await openApp(page, { theme });
    await expect(page.locator('.call-matching-live-status')).toContainText('Live');
    await page.screenshot({ path: `build/playwright-results/call-matching-${name}-page.png`, fullPage: true });
    await page.getByRole('button', { name: 'Compare duplicate decision 71' }).click();
    await expect(page.getByRole('dialog', { name: 'Duplicate call details' })).toBeVisible();
    if (viewport.width < 760) {
      await expect(page.locator('.call-matching-comparison-hint')).toBeVisible();
    }
    await page.screenshot({ path: `build/playwright-results/call-matching-${name}-modal.png` });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
    expect(overflow).toBe(false);
  });
}
